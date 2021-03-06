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
package io.prestosql.spi.plan;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.collect.ImmutableList;
import io.prestosql.spi.relation.RowExpression;

import javax.annotation.concurrent.Immutable;

import java.util.List;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

@Immutable
public class ValuesNode
        extends PlanNode
{
    private final List<Symbol> outputSymbols;
    private final List<List<RowExpression>> rows;
    private Long resumeSnapshotId;
    private long nextSnapshotId;

    public ValuesNode(PlanNodeId id, List<Symbol> outputSymbols, List<List<RowExpression>> rows)
    {
        this(id, outputSymbols, rows, null, 0);
    }

    @JsonCreator
    public ValuesNode(@JsonProperty("id") PlanNodeId id,
            @JsonProperty("outputSymbols") List<Symbol> outputSymbols,
            @JsonProperty("rows") List<List<RowExpression>> rows,
            @JsonProperty("resumeSnapshotId") Long resumeSnapshotId,
            @JsonProperty("nextSnapshotId") long nextSnapshotId)
    {
        super(id);
        this.outputSymbols = ImmutableList.copyOf(outputSymbols);
        this.rows = listOfListsCopy(rows);

        for (List<RowExpression> row : rows) {
            checkArgument(row.size() == outputSymbols.size() || row.size() == 0,
                    "Expected row to have %s values, but row has %s values", outputSymbols.size(), row.size());
        }

        this.resumeSnapshotId = resumeSnapshotId;
        this.nextSnapshotId = nextSnapshotId;
    }

    @Override
    @JsonProperty
    public List<Symbol> getOutputSymbols()
    {
        return outputSymbols;
    }

    @JsonProperty
    public List<List<RowExpression>> getRows()
    {
        return rows;
    }

    @Override
    public List<PlanNode> getSources()
    {
        return ImmutableList.of();
    }

    @JsonProperty
    public Long getResumeSnapshotId()
    {
        return resumeSnapshotId;
    }

    @JsonProperty
    public long getNextSnapshotId()
    {
        return nextSnapshotId;
    }

    @Override
    public <R, C> R accept(PlanVisitor<R, C> visitor, C context)
    {
        return visitor.visitValues(this, context);
    }

    @Override
    public PlanNode replaceChildren(List<PlanNode> newChildren)
    {
        checkArgument(newChildren.isEmpty(), "newChildren is not empty");
        return this;
    }

    private static <T> List<List<T>> listOfListsCopy(List<List<T>> lists)
    {
        return requireNonNull(lists, "lists is null").stream()
                .map(ImmutableList::copyOf)
                .collect(toImmutableList());
    }

    public void setupSnapshot(Long resumeSnapshotId, long nextSnapshotId)
    {
        this.resumeSnapshotId = resumeSnapshotId;
        this.nextSnapshotId = nextSnapshotId;
    }
}
