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
package io.prestosql.sql.planner.plan;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.collect.ImmutableList;
import io.prestosql.spi.metadata.TableHandle;
import io.prestosql.spi.plan.PlanNode;
import io.prestosql.spi.plan.PlanNodeId;
import io.prestosql.spi.plan.Symbol;

import javax.annotation.concurrent.Immutable;

import java.util.List;

import static java.util.Objects.requireNonNull;

@Immutable
public class TableUpdateNode
        extends InternalPlanNode
{
    private final TableHandle target;
    private final Symbol output;

    @JsonCreator
    public TableUpdateNode(
            @JsonProperty("id") PlanNodeId id,
            @JsonProperty("target") TableHandle target,
            @JsonProperty("output") Symbol output)
    {
        super(id);
        this.target = requireNonNull(target, "target is null");
        this.output = requireNonNull(output, "output is null");
    }

    @JsonProperty
    public TableHandle getTarget()
    {
        return target;
    }

    @JsonProperty
    public Symbol getOutput()
    {
        return output;
    }

    @Override
    public List<Symbol> getOutputSymbols()
    {
        return ImmutableList.of(output);
    }

    @Override
    public List<PlanNode> getSources()
    {
        return ImmutableList.of();
    }

    @Override
    public <R, C> R accept(InternalPlanVisitor<R, C> visitor, C context)
    {
        return visitor.visitTableUpdate(this, context);
    }

    @Override
    public PlanNode replaceChildren(List<PlanNode> newChildren)
    {
        return new TableUpdateNode(getId(), target, output);
    }
}
