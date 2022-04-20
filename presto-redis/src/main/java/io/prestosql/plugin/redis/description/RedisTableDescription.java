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
package io.prestosql.plugin.redis.description;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import static com.google.common.base.MoreObjects.toStringHelper;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Strings.isNullOrEmpty;

public class RedisTableDescription
{
    private final String tableName;
    private final String schemaName;
    private final RedisTableFieldGroup key;
    private final RedisTableFieldGroup value;

    @JsonCreator
    public RedisTableDescription(
            @JsonProperty("tableName")final String tableName,
            @JsonProperty("schemaName")final String schemaName,
            @JsonProperty("key")final RedisTableFieldGroup key,
            @JsonProperty("value")final RedisTableFieldGroup value)
    {
        checkArgument(!isNullOrEmpty(tableName), "tablename is null");
        this.tableName = tableName;
        this.schemaName = isNullOrEmpty(schemaName) ? "default" : schemaName;
        this.key = key;
        this.value = value;
    }

    @JsonProperty
    public String getTableName()
    {
        return tableName;
    }

    @JsonProperty
    public String getSchemaName()
    {
        return schemaName;
    }

    @JsonProperty
    public RedisTableFieldGroup getKey()
    {
        return key;
    }

    @JsonProperty
    public RedisTableFieldGroup getValue()
    {
        return value;
    }

    @Override
    public String toString()
    {
        return toStringHelper(this)
                .add("tableName", tableName)
                .add("schemaName", schemaName)
                .add("key", key)
                .add("value", value)
                .toString();
    }
}
