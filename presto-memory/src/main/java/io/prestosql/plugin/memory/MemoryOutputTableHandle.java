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
package io.prestosql.plugin.memory;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.prestosql.spi.connector.ConnectorOutputTableHandle;

import java.util.Collections;
import java.util.List;
import java.util.Set;

import static com.google.common.base.MoreObjects.toStringHelper;
import static java.util.Objects.requireNonNull;

public final class MemoryOutputTableHandle
        implements ConnectorOutputTableHandle
{
    private final long table;
    private final Set<Long> activeTableIds;
    private final List<ColumnInfo> columns;
    private final List<SortingColumn> sortedBy;
    private final List<String> indexColumns;

    @JsonCreator
    public MemoryOutputTableHandle(
            @JsonProperty("table") long table,
            @JsonProperty("activeTableIds") Set<Long> activeTableIds,
            @JsonProperty("columns") List<ColumnInfo> columns,
            @JsonProperty("sortedBy") List<SortingColumn> sortedBy,
            @JsonProperty("indexColumns") List<String> indexColumns)
    {
        this.table = requireNonNull(table, "table is null");
        this.activeTableIds = requireNonNull(activeTableIds, "activeTableIds is null");
        this.columns = requireNonNull(columns, "columns is null");
        this.sortedBy = requireNonNull(sortedBy, "sortedBy is null");
        this.indexColumns = requireNonNull(indexColumns, "indexColumns is null");
    }

    public MemoryOutputTableHandle(long table, Set<Long> activeTableIds)
    {
        this(table, activeTableIds, Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
    }

    @JsonProperty
    public long getTable()
    {
        return table;
    }

    @JsonProperty
    public Set<Long> getActiveTableIds()
    {
        return activeTableIds;
    }

    @JsonProperty
    public List<SortingColumn> getSortedBy()
    {
        return sortedBy;
    }

    @JsonProperty
    public List<ColumnInfo> getColumns()
    {
        return columns;
    }

    @JsonProperty
    public List<String> getIndexColumns()
    {
        return indexColumns;
    }

    @Override
    public String toString()
    {
        return toStringHelper(this)
                .add("table", table)
                .add("activeTableIds", activeTableIds)
                .add("sortedBy", sortedBy)
                .add("indexColumns", indexColumns)
                .toString();
    }
}
