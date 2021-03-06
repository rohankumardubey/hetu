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
package io.prestosql.sql.planner.optimizations;

import io.prestosql.Session;
import io.prestosql.execution.warnings.WarningCollector;
import io.prestosql.spi.plan.PlanNode;
import io.prestosql.spi.plan.PlanNodeIdAllocator;
import io.prestosql.sql.planner.PlanSymbolAllocator;
import io.prestosql.sql.planner.TypeProvider;
import io.prestosql.sql.planner.plan.SemiJoinNode;
import io.prestosql.sql.planner.plan.SimplePlanRewriter;
import io.prestosql.sql.planner.plan.UpdateNode;

import static io.prestosql.sql.planner.plan.SemiJoinNode.DistributionType.REPLICATED;
import static java.util.Objects.requireNonNull;

public class ReplicateSemiJoinInUpdate
        implements PlanOptimizer
{
    @Override
    public PlanNode optimize(PlanNode plan, Session session, TypeProvider types, PlanSymbolAllocator planSymbolAllocator, PlanNodeIdAllocator idAllocator, WarningCollector warningCollector)
    {
        requireNonNull(plan, "plan is null");
        return SimplePlanRewriter.rewriteWith(new Rewriter(), plan);
    }

    private static class Rewriter
            extends SimplePlanRewriter<Void>
    {
        private boolean isUpdateQuery;

        @Override
        public PlanNode visitSemiJoin(SemiJoinNode node, RewriteContext<Void> context)
        {
            PlanNode sourceRewritten = context.rewrite(node.getSource(), context.get());
            PlanNode filteringSourceRewritten = context.rewrite(node.getFilteringSource(), context.get());

            SemiJoinNode rewrittenNode = new SemiJoinNode(
                    node.getId(),
                    sourceRewritten,
                    filteringSourceRewritten,
                    node.getSourceJoinSymbol(),
                    node.getFilteringSourceJoinSymbol(),
                    node.getSemiJoinOutput(),
                    node.getSourceHashSymbol(),
                    node.getFilteringSourceHashSymbol(),
                    node.getDistributionType(),
                    node.getDynamicFilterId());

            if (isUpdateQuery) {
                return rewrittenNode.withDistributionType(REPLICATED);
            }

            return rewrittenNode;
        }

        @Override
        public PlanNode visitUpdate(UpdateNode node, RewriteContext<Void> context)
        {
            // For Update queries, the TableScan node that corresponds to the table being updated must be collocated with the Update node,
            // so you can't do a distributed semi-join
            isUpdateQuery = true;
            PlanNode rewrittenSource = context.rewrite(node.getSource());
            return new UpdateNode(
                    node.getId(),
                    rewrittenSource,
                    node.getTarget(),
                    node.getRowId(),
                    node.getColumnValueAndRowIdSymbols(),
                    node.getOutputSymbols(),
                    node.getUpdateColumnExpression());
        }
    }
}
