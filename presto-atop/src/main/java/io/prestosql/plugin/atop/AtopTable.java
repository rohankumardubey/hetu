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
package io.prestosql.plugin.atop;

import com.google.common.collect.ImmutableList;
import io.airlift.slice.Slices;
import io.prestosql.spi.block.BlockBuilder;
import io.prestosql.spi.connector.ConnectorSession;
import io.prestosql.spi.type.Type;
import io.prestosql.spi.type.TypeSignature;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static io.prestosql.plugin.atop.AtopTable.AtopColumn.END_TIME;
import static io.prestosql.plugin.atop.AtopTable.AtopColumn.HOST_IP;
import static io.prestosql.plugin.atop.AtopTable.AtopColumn.START_TIME;
import static io.prestosql.plugin.atop.AtopTable.AtopColumnParser.bigintParser;
import static io.prestosql.plugin.atop.AtopTable.AtopColumnParser.varcharParser;
import static io.prestosql.spi.type.DateTimeEncoding.packDateTimeWithZone;
import static io.prestosql.spi.type.StandardTypes.BIGINT;
import static io.prestosql.spi.type.StandardTypes.DOUBLE;
import static io.prestosql.spi.type.StandardTypes.INTERVAL_DAY_TO_SECOND;
import static io.prestosql.spi.type.StandardTypes.TIMESTAMP_WITH_TIME_ZONE;
import static io.prestosql.spi.type.StandardTypes.VARCHAR;
import static io.prestosql.spi.type.TypeSignature.parseTypeSignature;
import static java.util.Objects.requireNonNull;

public enum AtopTable
{
    DISKS("disks", "DSK", baseColumnsAnd(
            new AtopColumn("device_name", VARCHAR, varcharParser(6)),
            new AtopColumn("utilization_percent", DOUBLE, (fields, type, builder, session) -> {
                long durationMillis = Long.valueOf(fields.get(5)) * 1000;
                long ioMillis = Long.valueOf(fields.get(7));
                double utilization = Math.round(100.0 * ioMillis / (double) durationMillis);
                if (utilization > 100) {
                    utilization = 100;
                }
                type.writeDouble(builder, utilization);
            }),
            new AtopColumn("io_time", INTERVAL_DAY_TO_SECOND, (fields, type, builder, session) -> {
                long millis = Long.valueOf(fields.get(7));
                type.writeLong(builder, millis);
            }),
            new AtopColumn("read_requests", BIGINT, bigintParser(8)),
            new AtopColumn("sectors_read", BIGINT, bigintParser(9)),
            new AtopColumn("write_requests", BIGINT, bigintParser(10)),
            new AtopColumn("sectors_written", BIGINT, bigintParser(11)))),

    REBOOTS("reboots", "DSK", ImmutableList.of(HOST_IP, new AtopColumn("power_on_time", TIMESTAMP_WITH_TIME_ZONE, (fields, type, builder, session) -> {
        long millisUtc = Long.valueOf(fields.get(2)) * 1000;
        long durationMillis = Long.valueOf(fields.get(5)) * 1000;
        long value = packDateTimeWithZone(millisUtc - durationMillis, session.getTimeZoneKey());
        type.writeLong(builder, value);
    })));

    private final String name;
    private final String atopLabel;
    private final List<AtopColumn> columns;
    private final Map<String, AtopColumn> columnIndex;

    AtopTable(String name, String atopLabel, List<AtopColumn> columns)
    {
        this.atopLabel = atopLabel;
        this.name = requireNonNull(name, "name is null");
        this.columns = ImmutableList.copyOf(requireNonNull(columns, "columns is null"));
        columnIndex = this.columns.stream().collect(Collectors.toMap(AtopColumn::getName, Function.identity()));
    }

    private static List<AtopColumn> baseColumnsAnd(AtopColumn... additionalColumns)
    {
        ImmutableList.Builder<AtopColumn> atopColumnBuilder = ImmutableList.builder();
        atopColumnBuilder.add(HOST_IP);
        // 0th field is the label (i.e. table name)
        // 1st field is the name of the host, but isn't fully qualified
        atopColumnBuilder.add(START_TIME);
        // 2nd field is the end timestamp as unix time
        atopColumnBuilder.add(END_TIME);
        // 3rd field is the date, but we already have the epoch
        // 4th field is the time, but we already have the epoch
        // 5th field is the duration, and will be combined with 2 to compute start_time
        atopColumnBuilder.addAll(Arrays.asList(additionalColumns));
        return atopColumnBuilder.build();
    }

    public String getName()
    {
        return name;
    }

    public String getAtopLabel()
    {
        return atopLabel;
    }

    public AtopColumn getColumn(String name)
    {
        return columnIndex.get(name);
    }

    public List<AtopColumn> getColumns()
    {
        return columns;
    }

    public static class AtopColumn
    {
        public static final AtopColumn HOST_IP = new AtopColumn("host_ip", VARCHAR, (fields, type, builder, session) -> { throw new UnsupportedOperationException(); });

        public static final AtopColumn START_TIME = new AtopColumn("start_time", TIMESTAMP_WITH_TIME_ZONE, ((fields, type, builder, session) -> {
            long millisUtc = Long.valueOf(fields.get(2)) * 1000;
            long durationMillis = Long.valueOf(fields.get(5)) * 1000;
            long value = packDateTimeWithZone(millisUtc - durationMillis, session.getTimeZoneKey());
            type.writeLong(builder, value);
        }));

        public static final AtopColumn END_TIME = new AtopColumn("end_time", TIMESTAMP_WITH_TIME_ZONE, ((fields, type, builder, session) -> {
            long millisUtc = Long.valueOf(fields.get(2)) * 1000;
            type.writeLong(builder, packDateTimeWithZone(millisUtc, session.getTimeZoneKey()));
        }));

        private final String name;
        private final TypeSignature type;
        private final AtopColumnParser parser;

        private AtopColumn(String name, String type, AtopColumnParser parser)
        {
            this.name = requireNonNull(name, "name is null");
            this.type = parseTypeSignature(type);
            this.parser = requireNonNull(parser, "parser is null");
        }

        public String getName()
        {
            return name;
        }

        public TypeSignature getType()
        {
            return type;
        }

        public AtopColumnParser getParser()
        {
            return parser;
        }
    }

    public interface AtopColumnParser
    {
        void parse(List<String> fields, Type type, BlockBuilder builder, ConnectorSession session);

        static AtopColumnParser varcharParser(int index)
        {
            return ((fields, type, builder, session) -> type.writeSlice(builder, Slices.utf8Slice(fields.get(index))));
        }

        static AtopColumnParser bigintParser(int index)
        {
            return ((fields, type, builder, session) -> type.writeLong(builder, Long.valueOf(fields.get(index))));
        }
    }
}
