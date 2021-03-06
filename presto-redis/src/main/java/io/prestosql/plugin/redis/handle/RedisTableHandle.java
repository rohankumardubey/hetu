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
package io.prestosql.plugin.redis.handle;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.prestosql.spi.connector.ConnectorTableHandle;
import io.prestosql.spi.connector.SchemaTableName;

import java.util.Objects;

import static com.google.common.base.MoreObjects.toStringHelper;
import static java.util.Objects.requireNonNull;

public final class RedisTableHandle
        implements ConnectorTableHandle
{
    private final String schemaName;
    private final String tableName;
    private final String keyName;
    private final String keyDataFormat;
    private final String valueDataFormat;

    @JsonCreator
    public RedisTableHandle(
            @JsonProperty("schemaName")final String schemaName,
            @JsonProperty("tableName")final String tableName,
            @JsonProperty("keyDataFormat")final String keyDataFormat,
            @JsonProperty("valueDataFormat")final String valueDataFormat,
            @JsonProperty("keyName")final String keyName)
    {
        this.schemaName = requireNonNull(schemaName, "schemaName is null");
        this.tableName = requireNonNull(tableName, "tableName is null");
        this.keyDataFormat = requireNonNull(keyDataFormat, "keyDataFormat is null");
        this.valueDataFormat = requireNonNull(valueDataFormat, "valueDataFormat is null");
        this.keyName = keyName;
    }

    @JsonProperty
    public String getKeyName()
    {
        return String.valueOf(keyName);
    }

    @JsonProperty
    public String getKeyDataFormat()
    {
        return keyDataFormat;
    }

    @JsonProperty
    public String getValueDataFormat()
    {
        return valueDataFormat;
    }

    @JsonProperty
    public String getSchemaName()
    {
        return schemaName;
    }

    @JsonProperty
    public String getTableName()
    {
        return tableName;
    }

    public SchemaTableName toSchemaTableName()
    {
        return new SchemaTableName(schemaName, tableName);
    }

    @Override
    public int hashCode()
    {
        return Objects.hash(schemaName, tableName);
    }

    @Override
    public boolean equals(final Object obj)
    {
        if (this == obj) {
            return true;
        }
        if ((obj == null) || (getClass() != obj.getClass())) {
            return false;
        }

        RedisTableHandle other = (RedisTableHandle) obj;
        return Objects.equals(this.schemaName, other.schemaName)
                && Objects.equals(this.tableName, other.tableName)
                && Objects.equals(this.keyDataFormat, other.keyDataFormat)
                && Objects.equals(this.valueDataFormat, other.valueDataFormat)
                && Objects.equals(this.keyName, other.keyName);
    }

    @Override
    public String toString()
    {
        return toStringHelper(this)
                .add("schemaName", schemaName)
                .add("tableName", tableName)
                .add("keyDataFormat", keyDataFormat)
                .add("valueDataFormat", valueDataFormat)
                .add("keyName", keyName)
                .toString();
    }
}
