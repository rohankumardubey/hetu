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
package io.prestosql.plugin.jmx;

import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.google.common.collect.Streams;
import io.prestosql.spi.connector.ColumnHandle;
import io.prestosql.spi.connector.ColumnMetadata;
import io.prestosql.spi.connector.ConnectorMetadata;
import io.prestosql.spi.connector.ConnectorSession;
import io.prestosql.spi.connector.ConnectorTableHandle;
import io.prestosql.spi.connector.ConnectorTableMetadata;
import io.prestosql.spi.connector.ConnectorTableProperties;
import io.prestosql.spi.connector.Constraint;
import io.prestosql.spi.connector.ConstraintApplicationResult;
import io.prestosql.spi.connector.SchemaTableName;
import io.prestosql.spi.connector.SchemaTablePrefix;
import io.prestosql.spi.predicate.Domain;
import io.prestosql.spi.predicate.TupleDomain;
import io.prestosql.spi.type.Type;

import javax.inject.Inject;
import javax.management.JMException;
import javax.management.MBeanAttributeInfo;
import javax.management.MBeanInfo;
import javax.management.MBeanServer;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static io.prestosql.spi.type.BigintType.BIGINT;
import static io.prestosql.spi.type.BooleanType.BOOLEAN;
import static io.prestosql.spi.type.DoubleType.DOUBLE;
import static io.prestosql.spi.type.TimestampType.TIMESTAMP;
import static io.prestosql.spi.type.VarcharType.createUnboundedVarcharType;
import static java.util.Comparator.comparing;
import static java.util.Locale.ENGLISH;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toList;
import static javax.management.ObjectName.WILDCARD;

public class JmxMetadata
        implements ConnectorMetadata
{
    public static final String JMX_SCHEMA_NAME = "current";
    public static final String HISTORY_SCHEMA_NAME = "history";
    public static final String NODE_COLUMN_NAME = "node";
    public static final String OBJECT_NAME_NAME = "object_name";
    public static final String TIMESTAMP_COLUMN_NAME = "timestamp";

    private final MBeanServer mbeanServer;
    private final JmxHistoricalData jmxHistoricalData;

    @Inject
    public JmxMetadata(MBeanServer mbeanServer, JmxHistoricalData jmxHistoricalData)
    {
        this.mbeanServer = requireNonNull(mbeanServer, "mbeanServer is null");
        this.jmxHistoricalData = requireNonNull(jmxHistoricalData, "jmxStatsHolder is null");
    }

    @Override
    public List<String> listSchemaNames(ConnectorSession session)
    {
        return ImmutableList.of(JMX_SCHEMA_NAME, HISTORY_SCHEMA_NAME);
    }

    @Override
    public JmxTableHandle getTableHandle(ConnectorSession session, SchemaTableName tableName)
    {
        return getTableHandle(tableName);
    }

    @Override
    public boolean usesLegacyTableLayouts()
    {
        return false;
    }

    @Override
    public ConnectorTableProperties getTableProperties(ConnectorSession session, ConnectorTableHandle table)
    {
        return new ConnectorTableProperties();
    }

    public JmxTableHandle getTableHandle(SchemaTableName tableName)
    {
        requireNonNull(tableName, "tableName is null");
        if (tableName.getSchemaName().equals(JMX_SCHEMA_NAME)) {
            return getJmxTableHandle(tableName);
        }
        if (tableName.getSchemaName().equals(HISTORY_SCHEMA_NAME)) {
            return getJmxHistoryTableHandle(tableName);
        }
        return null;
    }

    private JmxTableHandle getJmxHistoryTableHandle(SchemaTableName tableName)
    {
        JmxTableHandle handle = getJmxTableHandle(tableName);
        if (handle == null) {
            return null;
        }
        ImmutableList.Builder<JmxColumnHandle> builder = ImmutableList.builder();
        builder.add(new JmxColumnHandle(TIMESTAMP_COLUMN_NAME, TIMESTAMP));
        builder.addAll(handle.getColumnHandles());
        return new JmxTableHandle(handle.getTableName(), handle.getObjectNames(), builder.build(), false, TupleDomain.all());
    }

    private JmxTableHandle getJmxTableHandle(SchemaTableName tableName)
    {
        try {
            String objectNamePattern = toPattern(tableName.getTableName().toLowerCase(ENGLISH));
            List<ObjectName> objectNames = mbeanServer.queryNames(WILDCARD, null).stream()
                    .filter(name -> name.getCanonicalName().toLowerCase(ENGLISH).matches(objectNamePattern))
                    .collect(toImmutableList());
            if (objectNames.isEmpty()) {
                return null;
            }
            List<JmxColumnHandle> columns = new ArrayList<>();
            columns.add(new JmxColumnHandle(NODE_COLUMN_NAME, createUnboundedVarcharType()));
            columns.add(new JmxColumnHandle(OBJECT_NAME_NAME, createUnboundedVarcharType()));
            for (ObjectName objectName : objectNames) {
                MBeanInfo mbeanInfo = mbeanServer.getMBeanInfo(objectName);

                getColumnHandles(mbeanInfo).forEach(columns::add);
            }

            // Since this method is being called on all nodes in the cluster, we must ensure (by sorting)
            // that attributes are in the same order on all of them.
            columns = columns.stream()
                    .distinct()
                    .sorted(comparing(JmxColumnHandle::getColumnName))
                    .collect(toImmutableList());

            return new JmxTableHandle(tableName, objectNames.stream().map(ObjectName::toString).collect(toImmutableList()), columns, true, TupleDomain.all());
        }
        catch (JMException e) {
            return null;
        }
    }

    private String toPattern(String tableName)
            throws MalformedObjectNameException
    {
        if (!tableName.contains("*")) {
            return Pattern.quote(new ObjectName(tableName).getCanonicalName());
        }
        return Streams.stream(Splitter.on('*').split(tableName))
                .map(Pattern::quote)
                .collect(Collectors.joining(".*"));
    }

    private Stream<JmxColumnHandle> getColumnHandles(MBeanInfo mbeanInfo)
    {
        return Arrays.stream(mbeanInfo.getAttributes())
                .filter(MBeanAttributeInfo::isReadable)
                .map(attribute -> new JmxColumnHandle(attribute.getName(), getColumnType(attribute)));
    }

    @Override
    public ConnectorTableMetadata getTableMetadata(ConnectorSession session, ConnectorTableHandle tableHandle)
    {
        return ((JmxTableHandle) tableHandle).getTableMetadata();
    }

    @Override
    public List<SchemaTableName> listTables(ConnectorSession session, Optional<String> schemaName)
    {
        Set<String> schemaNames = schemaName.map(ImmutableSet::of)
                .orElseGet(() -> ImmutableSet.copyOf(listSchemaNames(session)));
        ImmutableList.Builder<SchemaTableName> schemaTableNames = ImmutableList.builder();
        for (String schema : schemaNames) {
            if (JMX_SCHEMA_NAME.equals(schema)) {
                return listJmxTables();
            }
            else if (HISTORY_SCHEMA_NAME.equals(schema)) {
                return jmxHistoricalData.getTables().stream()
                        .map(tableName -> new SchemaTableName(JmxMetadata.HISTORY_SCHEMA_NAME, tableName))
                        .collect(toList());
            }
        }
        return schemaTableNames.build();
    }

    private List<SchemaTableName> listJmxTables()
    {
        ImmutableList.Builder<SchemaTableName> tableNames = ImmutableList.builder();
        for (ObjectName objectName : mbeanServer.queryNames(WILDCARD, null)) {
            // todo remove lower case when presto supports mixed case names
            tableNames.add(new SchemaTableName(JMX_SCHEMA_NAME, objectName.getCanonicalName().toLowerCase(ENGLISH)));
        }
        return tableNames.build();
    }

    @Override
    public Map<String, ColumnHandle> getColumnHandles(ConnectorSession session, ConnectorTableHandle tableHandle)
    {
        JmxTableHandle jmxTableHandle = (JmxTableHandle) tableHandle;
        return ImmutableMap.copyOf(Maps.uniqueIndex(jmxTableHandle.getColumnHandles(), column -> column.getColumnName().toLowerCase(ENGLISH)));
    }

    @Override
    public ColumnMetadata getColumnMetadata(ConnectorSession session, ConnectorTableHandle tableHandle, ColumnHandle columnHandle)
    {
        return ((JmxColumnHandle) columnHandle).getColumnMetadata();
    }

    @Override
    public Map<SchemaTableName, List<ColumnMetadata>> listTableColumns(ConnectorSession session, SchemaTablePrefix prefix)
    {
        requireNonNull(prefix, "prefix is null");
        if (prefix.getSchema().isPresent() &&
                !prefix.getSchema().get().equals(JMX_SCHEMA_NAME) &&
                !prefix.getSchema().get().equals(HISTORY_SCHEMA_NAME)) {
            return ImmutableMap.of();
        }

        List<SchemaTableName> tableNames;
        if (!prefix.getTable().isPresent()) {
            tableNames = listTables(session, prefix.getSchema());
        }
        else {
            tableNames = ImmutableList.of(prefix.toSchemaTableName());
        }

        return tableNames.stream()
                .collect(toImmutableMap(Function.identity(), tableName -> getTableHandle(session, tableName).getTableMetadata().getColumns()));
    }

    @Override
    public Optional<ConstraintApplicationResult<ConnectorTableHandle>> applyFilter(ConnectorSession session, ConnectorTableHandle handle, Constraint constraint)
    {
        Optional<Map<ColumnHandle, Domain>> domains = constraint.getSummary().getDomains();
        if (!domains.isPresent()) {
            return Optional.empty();
        }

        JmxTableHandle tableHandle = (JmxTableHandle) handle;

        Map<ColumnHandle, Domain> nodeDomains = new LinkedHashMap<>();
        Map<ColumnHandle, Domain> otherDomains = new LinkedHashMap<>();
        domains.get().forEach((column, domain) -> {
            JmxColumnHandle columnHandle = (JmxColumnHandle) column;
            if (columnHandle.getColumnName().equals(NODE_COLUMN_NAME)) {
                nodeDomains.put(column, domain);
            }
            else {
                otherDomains.put(column, domain);
            }
        });

        TupleDomain<ColumnHandle> oldDomain = tableHandle.getNodeFilter();
        TupleDomain<ColumnHandle> newDomain = oldDomain.intersect(TupleDomain.withColumnDomains(nodeDomains));

        if (oldDomain.equals(newDomain)) {
            return Optional.empty();
        }

        JmxTableHandle newTableHandle = new JmxTableHandle(tableHandle.getTableName(), tableHandle.getObjectNames(), tableHandle.getColumnHandles(), tableHandle.isLiveData(), newDomain);

        return Optional.of(new ConstraintApplicationResult<>(newTableHandle, TupleDomain.withColumnDomains(otherDomains)));
    }

    private static Type getColumnType(MBeanAttributeInfo attribute)
    {
        switch (attribute.getType()) {
            case "boolean":
            case "java.lang.Boolean":
                return BOOLEAN;
            case "byte":
            case "java.lang.Byte":
            case "short":
            case "java.lang.Short":
            case "int":
            case "java.lang.Integer":
            case "long":
            case "java.lang.Long":
                return BIGINT;
            case "java.lang.Number":
            case "float":
            case "java.lang.Float":
            case "double":
            case "java.lang.Double":
                return DOUBLE;
            default:
                break;
        }
        return createUnboundedVarcharType();
    }
}
