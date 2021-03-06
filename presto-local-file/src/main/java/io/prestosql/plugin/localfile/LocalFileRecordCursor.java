/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.prestosql.plugin.localfile;

import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.base.Strings;
import com.google.common.collect.Iterables;
import io.airlift.slice.Slice;
import io.airlift.slice.Slices;
import io.prestosql.spi.HostAddress;
import io.prestosql.spi.PrestoException;
import io.prestosql.spi.connector.RecordCursor;
import io.prestosql.spi.connector.SchemaTableName;
import io.prestosql.spi.predicate.Domain;
import io.prestosql.spi.predicate.TupleDomain;
import io.prestosql.spi.type.Type;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.zip.GZIPInputStream;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static io.prestosql.plugin.localfile.LocalFileColumnHandle.SERVER_ADDRESS_ORDINAL_POSITION;
import static io.prestosql.plugin.localfile.LocalFileErrorCode.LOCAL_FILE_READ_ERROR;
import static io.prestosql.spi.type.BigintType.BIGINT;
import static io.prestosql.spi.type.BooleanType.BOOLEAN;
import static io.prestosql.spi.type.DoubleType.DOUBLE;
import static io.prestosql.spi.type.IntegerType.INTEGER;
import static io.prestosql.spi.type.TimestampType.TIMESTAMP;
import static io.prestosql.spi.type.VarcharType.createUnboundedVarcharType;
import static java.lang.String.format;
import static java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toSet;
import static java.util.zip.GZIPInputStream.GZIP_MAGIC;

public class LocalFileRecordCursor
        implements RecordCursor
{
    private static final Splitter LINE_SPLITTER = Splitter.on("\t").trimResults();

    // TODO This should be a config option as it may be different for different log files
    public static final DateTimeFormatter ISO_FORMATTER = ISO_OFFSET_DATE_TIME.withZone(ZoneId.systemDefault());

    private final int[] fieldToColumnIndex;
    private final HostAddress address;
    private final List<LocalFileColumnHandle> columns;
    private final FilesReader reader;
    private final boolean includeServer;

    private List<String> fields;

    public LocalFileRecordCursor(LocalFileTables localFileTables, List<LocalFileColumnHandle> columns, SchemaTableName tableName, HostAddress address, TupleDomain<LocalFileColumnHandle> predicate)
    {
        this.columns = requireNonNull(columns, "columns is null");
        this.address = requireNonNull(address, "address is null");

        fieldToColumnIndex = new int[columns.size()];
        for (int i = 0; i < columns.size(); i++) {
            LocalFileColumnHandle columnHandle = columns.get(i);
            fieldToColumnIndex[i] = columnHandle.getOrdinalPosition();
        }
        this.includeServer = isThisServerIncluded(address, predicate, localFileTables.getTable(tableName));
        this.reader = includeServer ? getFilesReader(localFileTables, predicate, tableName) : null;
    }

    private static boolean isThisServerIncluded(HostAddress address, TupleDomain<LocalFileColumnHandle> predicate, LocalFileTableHandle table)
    {
        if (!table.getServerAddressColumn().isPresent()) {
            return true;
        }

        Optional<Map<LocalFileColumnHandle, Domain>> domains = predicate.getDomains();
        if (!domains.isPresent()) {
            return true;
        }

        Set<Domain> serverAddressDomain = domains.get().entrySet().stream()
                .filter(entry -> entry.getKey().getOrdinalPosition() == table.getServerAddressColumn().getAsInt())
                .map(Map.Entry::getValue)
                .collect(toSet());

        if (serverAddressDomain.isEmpty()) {
            return true;
        }

        for (Domain domain : serverAddressDomain) {
            if (domain.includesNullableValue(Slices.utf8Slice(address.toString()))) {
                return true;
            }
        }
        return false;
    }

    private static FilesReader getFilesReader(LocalFileTables localFileTables, TupleDomain<LocalFileColumnHandle> predicate, SchemaTableName tableName)
    {
        LocalFileTableHandle table = localFileTables.getTable(tableName);
        List<File> fileNames = localFileTables.getFiles(tableName);
        int maxRowFromFile = localFileTables.getMaxTablesRowFromFile();
        try {
            return new FilesReader(table.getTimestampColumn(), fileNames.iterator(), predicate, maxRowFromFile);
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public long getCompletedBytes()
    {
        return 0;
    }

    @Override
    public long getReadTimeNanos()
    {
        return 0;
    }

    @Override
    public Type getType(int field)
    {
        checkArgument(field < columns.size(), "Invalid field index");
        return columns.get(field).getColumnType();
    }

    @Override
    public boolean advanceNextPosition()
    {
        if (!includeServer) {
            return false;
        }

        try {
            fields = reader.readFields();
            return fields != null;
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private String getFieldValue(int field)
    {
        checkState(fields != null, "Cursor has not been advanced yet");

        int columnIndex = fieldToColumnIndex[field];
        if (columnIndex == SERVER_ADDRESS_ORDINAL_POSITION) {
            return address.toString();
        }
        if (columnIndex >= fields.size()) {
            return null;
        }
        return fields.get(columnIndex);
    }

    @Override
    public boolean getBoolean(int field)
    {
        checkFieldType(field, BOOLEAN);
        return Boolean.parseBoolean(getFieldValue(field));
    }

    @Override
    public long getLong(int field)
    {
        if (getType(field).equals(TIMESTAMP)) {
            return Instant.from(ISO_FORMATTER.parse(getFieldValue(field))).toEpochMilli();
        }
        else {
            checkFieldType(field, BIGINT, INTEGER);
            return Long.parseLong(getFieldValue(field));
        }
    }

    @Override
    public double getDouble(int field)
    {
        checkFieldType(field, DOUBLE);
        return Double.parseDouble(getFieldValue(field));
    }

    @Override
    public Slice getSlice(int field)
    {
        checkFieldType(field, createUnboundedVarcharType());
        return Slices.utf8Slice(getFieldValue(field));
    }

    @Override
    public Object getObject(int field)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isNull(int field)
    {
        checkArgument(field < columns.size(), "Invalid field index");
        String fieldValue = getFieldValue(field);
        return "null".equals(fieldValue) || Strings.isNullOrEmpty(fieldValue);
    }

    private void checkFieldType(int field, Type... expected)
    {
        Type actual = getType(field);
        for (Type type : expected) {
            if (actual.equals(type)) {
                return;
            }
        }
        String expectedTypes = Joiner.on(", ").join(expected);
        throw new IllegalArgumentException(format("Expected field %s to be type %s but is %s", field, expectedTypes, actual));
    }

    @Override
    public void close()
    {
        reader.close();
    }

    private static class FilesReader
    {
        private final Iterator<File> files;
        private final Optional<Domain> domain;
        private final OptionalInt timestampOrdinalPosition;
        private int maxRowFromFile;
        private BufferedReader reader;

        public FilesReader(OptionalInt timestampOrdinalPosition, Iterator<File> files, TupleDomain<LocalFileColumnHandle> predicate, int maxRowFromFiles)
                throws IOException
        {
            this.maxRowFromFile = maxRowFromFiles;

            requireNonNull(files, "files is null");
            this.files = files;

            requireNonNull(predicate, "predicate is null");
            this.domain = getDomain(timestampOrdinalPosition, predicate);

            this.timestampOrdinalPosition = timestampOrdinalPosition;

            reader = createNextReader();
        }

        private static Optional<Domain> getDomain(OptionalInt timestampOrdinalPosition, TupleDomain<LocalFileColumnHandle> predicate)
        {
            Optional<Map<LocalFileColumnHandle, Domain>> domains = predicate.getDomains();
            Domain localDomain = null;
            if (domains.isPresent() && timestampOrdinalPosition.isPresent()) {
                Map<LocalFileColumnHandle, Domain> domainMap = domains.get();
                Set<Domain> timestampDomain = domainMap.entrySet().stream()
                        .filter(entry -> entry.getKey().getOrdinalPosition() == timestampOrdinalPosition.getAsInt())
                        .map(Map.Entry::getValue)
                        .collect(toSet());

                if (!timestampDomain.isEmpty()) {
                    localDomain = Iterables.getOnlyElement(timestampDomain);
                }
            }
            return Optional.ofNullable(localDomain);
        }

        private BufferedReader createNextReader()
                throws IOException
        {
            if (!files.hasNext()) {
                return null;
            }
            File file = files.next();
            FileInputStream fileInputStream = new FileInputStream(file);

            InputStream in = isGZipped(file) ? new GZIPInputStream(fileInputStream) : fileInputStream;
            return new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        }

        public static boolean isGZipped(File file)
        {
            try (RandomAccessFile inputFile = new RandomAccessFile(file, "r")) {
                int magic = inputFile.read() & 0xff | ((inputFile.read() << 8) & 0xff00);
                return magic == GZIP_MAGIC;
            }
            catch (IOException e) {
                throw new PrestoException(LOCAL_FILE_READ_ERROR, "Error reading file: " + file.getName(), e);
            }
        }

        public List<String> readFields()
                throws IOException
        {
            List<String> fieldsList = null;
            boolean newReader = false;
            if (maxRowFromFile <= 0) {
                throw new PrestoException(LOCAL_FILE_READ_ERROR, "Local file too large for presto.");
            }
            while (fieldsList == null) {
                if (reader == null) {
                    return null;
                }
                String line = reader.readLine();
                if (line != null) {
                    fieldsList = LINE_SPLITTER.splitToList(line);
                    if (!newReader || meetsPredicate(fieldsList)) {
                        maxRowFromFile--;
                        return fieldsList;
                    }
                }
                reader.close();
                reader = createNextReader();
                newReader = true;
            }
            maxRowFromFile--;
            return fieldsList;
        }

        private boolean meetsPredicate(List<String> fields)
        {
            if (!timestampOrdinalPosition.isPresent() || !domain.isPresent()) {
                return true;
            }

            long millis = Instant.from(ISO_FORMATTER.parse(fields.get(timestampOrdinalPosition.getAsInt()))).toEpochMilli();
            return domain.get().includesNullableValue(millis);
        }

        public void close()
        {
            if (reader != null) {
                try {
                    reader.close();
                }
                catch (IOException ignored) {
                }
            }
        }
    }
}
