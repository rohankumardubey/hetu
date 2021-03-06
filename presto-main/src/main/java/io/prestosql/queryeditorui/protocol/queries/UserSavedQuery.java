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
package io.prestosql.queryeditorui.protocol.queries;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.joda.time.DateTime;

import java.util.UUID;

public class UserSavedQuery
        implements SavedQuery
{
    private QueryWithPlaceholders queryWithPlaceholders;

    private String user;

    private String name;

    private String description;

    private DateTime createdAt;

    private UUID uuid;

    private boolean featured;

    public UserSavedQuery()
    {
    }

    public UserSavedQuery(@JsonProperty("queryWithPlaceholders") QueryWithPlaceholders queryWithPlaceholders,
                          @JsonProperty("user") String user,
                          @JsonProperty("name") String name,
                          @JsonProperty("description") String description,
                          @JsonProperty("createdAt") DateTime createdAt,
                          @JsonProperty("uuid") UUID uuid,
                          @JsonProperty("featured") boolean featured)
    {
        this.queryWithPlaceholders = queryWithPlaceholders;
        this.user = user;
        this.name = name;
        this.description = description;
        this.createdAt = createdAt;
        this.uuid = uuid;
        this.featured = featured;
    }

    @JsonProperty
    @Override
    public QueryWithPlaceholders getQueryWithPlaceholders()
    {
        return queryWithPlaceholders;
    }

    @JsonProperty
    @Override
    public String getUser()
    {
        return user;
    }

    @JsonProperty
    @Override
    public String getName()
    {
        return name;
    }

    @JsonProperty
    @Override
    public String getDescription()
    {
        return description;
    }

    @JsonProperty
    @Override
    public UUID getUuid()
    {
        return uuid;
    }

    @JsonProperty
    public void setFeatured(boolean featured)
    {
        this.featured = featured;
    }

    @JsonProperty
    public boolean isFeatured()
    {
        return featured;
    }

    @JsonProperty
    public String getCreatedAt()
    {
        if (createdAt != null) {
            return createdAt.toDateTimeISO().toString();
        }
        else {
            return null;
        }
    }

    @JsonProperty
    public void setQueryWithPlaceholders(QueryWithPlaceholders queryWithPlaceholders)
    {
        this.queryWithPlaceholders = queryWithPlaceholders;
    }

    @JsonProperty
    public void setUser(String user)
    {
        this.user = user;
    }

    @JsonProperty
    public void setName(String name)
    {
        this.name = name;
    }

    @JsonProperty
    public void setDescription(String description)
    {
        this.description = description;
    }

    @JsonProperty
    public void setCreatedAt(DateTime createdAt)
    {
        this.createdAt = createdAt;
    }

    @JsonProperty
    public void setUuid(UUID uuid)
    {
        this.uuid = uuid;
    }
}
