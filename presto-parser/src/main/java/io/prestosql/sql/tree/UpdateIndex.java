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
package io.prestosql.sql.tree;

import com.google.common.collect.ImmutableList;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static com.google.common.base.MoreObjects.toStringHelper;
import static java.util.Objects.requireNonNull;

public class UpdateIndex
        extends Statement
{
    private final QualifiedName indexName;
    private final boolean notExists;
    private final List<Property> properties;

    public UpdateIndex(QualifiedName indexName, boolean notExists, List<Property> properties)
    {
        this(Optional.empty(), indexName, notExists, properties);
    }

    public UpdateIndex(NodeLocation location, QualifiedName indexName, boolean notExists, List<Property> properties)
    {
        this(Optional.of(location), indexName, notExists, properties);
    }

    private UpdateIndex(Optional<NodeLocation> location, QualifiedName indexName, boolean notExists, List<Property> properties)
    {
        super(location);
        this.indexName = requireNonNull(indexName, "indexName is null");
        this.notExists = notExists;
        this.properties = properties;
    }

    public QualifiedName getIndexName()
    {
        return indexName;
    }

    public List<Property> getProperties()
    {
        return properties;
    }

    public boolean isNotExists()
    {
        return notExists;
    }

    @Override
    public <R, C> R accept(AstVisitor<R, C> visitor, C context)
    {
        return visitor.visitUpdateIndex(this, context);
    }

    @Override
    public List<Node> getChildren()
    {
        ImmutableList.Builder<Node> nodes = ImmutableList.builder();
        nodes.add((Node) properties);
        return nodes.build();
    }

    @Override
    public int hashCode()
    {
        return Objects.hash(indexName, notExists, properties);
    }

    @Override
    public boolean equals(Object obj)
    {
        if (this == obj) {
            return true;
        }
        if ((obj == null) || (getClass() != obj.getClass())) {
            return false;
        }
        UpdateIndex o = (UpdateIndex) obj;
        return Objects.equals(indexName, o.indexName) &&
                Objects.equals(notExists, o.notExists) &&
                Objects.equals(properties, o.properties);
    }

    @Override
    public String toString()
    {
        return toStringHelper(this)
                .add("indexName", indexName)
                .add("notExists", notExists)
                .add("properties", properties)
                .toString();
    }
}
