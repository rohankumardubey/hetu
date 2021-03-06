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

import com.google.common.base.Functions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import io.prestosql.Session;
import io.prestosql.execution.warnings.WarningCollector;
import io.prestosql.expressions.LogicalRowExpressions;
import io.prestosql.metadata.Metadata;
import io.prestosql.metadata.ResolvedIndex;
import io.prestosql.spi.connector.ColumnHandle;
import io.prestosql.spi.plan.AggregationNode;
import io.prestosql.spi.plan.FilterNode;
import io.prestosql.spi.plan.JoinNode;
import io.prestosql.spi.plan.PlanNode;
import io.prestosql.spi.plan.PlanNodeIdAllocator;
import io.prestosql.spi.plan.ProjectNode;
import io.prestosql.spi.plan.Symbol;
import io.prestosql.spi.plan.TableScanNode;
import io.prestosql.spi.plan.WindowNode;
import io.prestosql.spi.predicate.TupleDomain;
import io.prestosql.spi.relation.DomainTranslator;
import io.prestosql.spi.relation.RowExpression;
import io.prestosql.spi.relation.VariableReferenceExpression;
import io.prestosql.spi.sql.expression.Types;
import io.prestosql.sql.planner.PlanSymbolAllocator;
import io.prestosql.sql.planner.TypeProvider;
import io.prestosql.sql.planner.plan.AssignmentUtils;
import io.prestosql.sql.planner.plan.IndexJoinNode;
import io.prestosql.sql.planner.plan.IndexSourceNode;
import io.prestosql.sql.planner.plan.InternalPlanVisitor;
import io.prestosql.sql.planner.plan.SimplePlanRewriter;
import io.prestosql.sql.planner.plan.SortNode;
import io.prestosql.sql.relational.FunctionResolution;
import io.prestosql.sql.relational.RowExpressionDeterminismEvaluator;
import io.prestosql.sql.relational.RowExpressionDomainTranslator;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.base.Predicates.in;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static io.prestosql.expressions.LogicalRowExpressions.TRUE_CONSTANT;
import static io.prestosql.spi.function.FunctionKind.AGGREGATE;
import static io.prestosql.sql.planner.plan.AssignmentUtils.identityAssignments;
import static java.util.Objects.requireNonNull;
import static java.util.function.Function.identity;

public class IndexJoinOptimizer
        implements PlanOptimizer
{
    private final Metadata metadata;

    public IndexJoinOptimizer(Metadata metadata)
    {
        this.metadata = requireNonNull(metadata, "metadata is null");
    }

    @Override
    public PlanNode optimize(PlanNode plan, Session session, TypeProvider type, PlanSymbolAllocator planSymbolAllocator, PlanNodeIdAllocator idAllocator, WarningCollector warningCollector)
    {
        requireNonNull(plan, "plan is null");
        requireNonNull(session, "session is null");
        requireNonNull(planSymbolAllocator, "symbolAllocator is null");
        requireNonNull(idAllocator, "idAllocator is null");

        return SimplePlanRewriter.rewriteWith(new Rewriter(planSymbolAllocator, idAllocator, metadata, session), plan, null);
    }

    private static class Rewriter
            extends SimplePlanRewriter<Void>
    {
        private final PlanSymbolAllocator planSymbolAllocator;
        private final PlanNodeIdAllocator idAllocator;
        private final Metadata metadata;
        private final Session session;

        private Rewriter(PlanSymbolAllocator planSymbolAllocator, PlanNodeIdAllocator idAllocator, Metadata metadata, Session session)
        {
            this.planSymbolAllocator = requireNonNull(planSymbolAllocator, "symbolAllocator is null");
            this.idAllocator = requireNonNull(idAllocator, "idAllocator is null");
            this.metadata = requireNonNull(metadata, "metadata is null");
            this.session = requireNonNull(session, "session is null");
        }

        @Override
        public PlanNode visitJoin(JoinNode node, RewriteContext<Void> context)
        {
            PlanNode leftRewritten = context.rewrite(node.getLeft());
            PlanNode rightRewritten = context.rewrite(node.getRight());

            if (!node.getCriteria().isEmpty()) { // Index join only possible with JOIN criteria
                List<Symbol> leftJoinSymbols = Lists.transform(node.getCriteria(), JoinNode.EquiJoinClause::getLeft);
                List<Symbol> rightJoinSymbols = Lists.transform(node.getCriteria(), JoinNode.EquiJoinClause::getRight);

                Optional<PlanNode> leftIndexCandidate = IndexSourceRewriter.rewriteWithIndex(
                        leftRewritten,
                        ImmutableSet.copyOf(leftJoinSymbols),
                        planSymbolAllocator,
                        idAllocator,
                        metadata,
                        session);
                if (leftIndexCandidate.isPresent()) {
                    // Sanity check that we can trace the path for the index lookup key
                    Map<Symbol, Symbol> trace = IndexKeyTracer.trace(leftIndexCandidate.get(), ImmutableSet.copyOf(leftJoinSymbols));
                    checkState(!trace.isEmpty() && leftJoinSymbols.containsAll(trace.keySet()));
                }

                Optional<PlanNode> rightIndexCandidate = IndexSourceRewriter.rewriteWithIndex(
                        rightRewritten,
                        ImmutableSet.copyOf(rightJoinSymbols),
                        planSymbolAllocator,
                        idAllocator,
                        metadata,
                        session);
                if (rightIndexCandidate.isPresent()) {
                    // Sanity check that we can trace the path for the index lookup key
                    Map<Symbol, Symbol> trace = IndexKeyTracer.trace(rightIndexCandidate.get(), ImmutableSet.copyOf(rightJoinSymbols));
                    checkState(!trace.isEmpty() && rightJoinSymbols.containsAll(trace.keySet()));
                }

                switch (node.getType()) {
                    case INNER:
                        // Prefer the right candidate over the left candidate
                        PlanNode indexJoinNode = null;
                        if (rightIndexCandidate.isPresent()) {
                            indexJoinNode = new IndexJoinNode(idAllocator.getNextId(), IndexJoinNode.Type.INNER, leftRewritten, rightIndexCandidate.get(), createEquiJoinClause(leftJoinSymbols, rightJoinSymbols), Optional.empty(), Optional.empty());
                        }
                        else if (leftIndexCandidate.isPresent()) {
                            indexJoinNode = new IndexJoinNode(idAllocator.getNextId(), IndexJoinNode.Type.INNER, rightRewritten, leftIndexCandidate.get(), createEquiJoinClause(rightJoinSymbols, leftJoinSymbols), Optional.empty(), Optional.empty());
                        }

                        if (indexJoinNode != null) {
                            if (node.getFilter().isPresent()) {
                                indexJoinNode = new FilterNode(idAllocator.getNextId(),
                                        indexJoinNode, node.getFilter().get());
                            }

                            if (!indexJoinNode.getOutputSymbols().equals(node.getOutputSymbols())) {
                                indexJoinNode = new ProjectNode(
                                        idAllocator.getNextId(),
                                        indexJoinNode,
                                        identityAssignments(planSymbolAllocator.getTypes(), node.getOutputSymbols()));
                            }

                            return indexJoinNode;
                        }
                        break;

                    case LEFT:
                        // We cannot use indices for outer joins until index join supports in-line filtering
                        if (!node.getFilter().isPresent() && rightIndexCandidate.isPresent()) {
                            return createIndexJoinWithExpectedOutputs(node.getOutputSymbols(), IndexJoinNode.Type.SOURCE_OUTER, leftRewritten, rightIndexCandidate.get(), createEquiJoinClause(leftJoinSymbols, rightJoinSymbols), idAllocator, planSymbolAllocator.getTypes());
                        }
                        break;

                    case RIGHT:
                        // We cannot use indices for outer joins until index join supports in-line filtering
                        if (!node.getFilter().isPresent() && leftIndexCandidate.isPresent()) {
                            return createIndexJoinWithExpectedOutputs(node.getOutputSymbols(), IndexJoinNode.Type.SOURCE_OUTER, rightRewritten, leftIndexCandidate.get(), createEquiJoinClause(rightJoinSymbols, leftJoinSymbols), idAllocator, planSymbolAllocator.getTypes());
                        }
                        break;

                    case FULL:
                        break;

                    default:
                        throw new IllegalArgumentException("Unknown type: " + node.getType());
                }
            }

            if (leftRewritten != node.getLeft() || rightRewritten != node.getRight()) {
                return new JoinNode(node.getId(), node.getType(), leftRewritten, rightRewritten, node.getCriteria(), node.getOutputSymbols(), node.getFilter(), node.getLeftHashSymbol(), node.getRightHashSymbol(), node.getDistributionType(), node.isSpillable(), node.getDynamicFilters());
            }
            return node;
        }

        private static PlanNode createIndexJoinWithExpectedOutputs(List<Symbol> expectedOutputs, IndexJoinNode.Type type, PlanNode probe, PlanNode index, List<IndexJoinNode.EquiJoinClause> equiJoinClause, PlanNodeIdAllocator idAllocator, TypeProvider types)
        {
            PlanNode result = new IndexJoinNode(idAllocator.getNextId(), type, probe, index, equiJoinClause, Optional.empty(), Optional.empty());
            if (!result.getOutputSymbols().equals(expectedOutputs)) {
                result = new ProjectNode(
                        idAllocator.getNextId(),
                        result,
                        AssignmentUtils.identityAssignments(types, expectedOutputs));
            }
            return result;
        }

        private static List<IndexJoinNode.EquiJoinClause> createEquiJoinClause(List<Symbol> probeSymbols, List<Symbol> indexSymbols)
        {
            checkArgument(probeSymbols.size() == indexSymbols.size());
            ImmutableList.Builder<IndexJoinNode.EquiJoinClause> builder = ImmutableList.builder();
            for (int i = 0; i < probeSymbols.size(); i++) {
                builder.add(new IndexJoinNode.EquiJoinClause(probeSymbols.get(i), indexSymbols.get(i)));
            }
            return builder.build();
        }
    }

    /**
     * Tries to rewrite a PlanNode tree with an IndexSource instead of a TableScan
     */
    private static class IndexSourceRewriter
            extends SimplePlanRewriter<IndexSourceRewriter.Context>
    {
        private final PlanSymbolAllocator planSymbolAllocator;
        private final PlanNodeIdAllocator idAllocator;
        private final Metadata metadata;
        private final RowExpressionDomainTranslator domainTranslator;
        private final Session session;

        private IndexSourceRewriter(PlanSymbolAllocator planSymbolAllocator, PlanNodeIdAllocator idAllocator, Metadata metadata, Session session)
        {
            this.metadata = requireNonNull(metadata, "metadata is null");
            this.domainTranslator = new RowExpressionDomainTranslator(metadata);
            this.planSymbolAllocator = requireNonNull(planSymbolAllocator, "symbolAllocator is null");
            this.idAllocator = requireNonNull(idAllocator, "idAllocator is null");
            this.session = requireNonNull(session, "session is null");
        }

        public static Optional<PlanNode> rewriteWithIndex(
                PlanNode planNode,
                Set<Symbol> lookupSymbols,
                PlanSymbolAllocator planSymbolAllocator,
                PlanNodeIdAllocator idAllocator,
                Metadata metadata,
                Session session)
        {
            AtomicBoolean success = new AtomicBoolean();
            IndexSourceRewriter indexSourceRewriter = new IndexSourceRewriter(planSymbolAllocator, idAllocator, metadata, session);
            PlanNode rewritten = SimplePlanRewriter.rewriteWith(indexSourceRewriter, planNode, new Context(lookupSymbols, success));
            if (success.get()) {
                return Optional.of(rewritten);
            }
            return Optional.empty();
        }

        @Override
        public PlanNode visitPlan(PlanNode node, RewriteContext<Context> context)
        {
            // We don't know how to process this PlanNode in the context of an IndexJoin, so just give up by returning something
            return node;
        }

        @Override
        public PlanNode visitTableScan(TableScanNode node, RewriteContext<Context> context)
        {
            return planTableScan(node, TRUE_CONSTANT, context.get());
        }

        private PlanNode planTableScan(TableScanNode node, RowExpression predicate, Context context)
        {
            DomainTranslator.ExtractionResult<VariableReferenceExpression> decomposedPredicate = domainTranslator.fromPredicate(session.toConnectorSession(), predicate);

            TupleDomain<ColumnHandle> simplifiedConstraint = decomposedPredicate.getTupleDomain()
                    .transform(variable -> node.getAssignments().get(new Symbol(variable.getName())))
                    .intersect(node.getEnforcedConstraint());

            checkState(node.getOutputSymbols().containsAll(context.getLookupSymbols()));

            Set<ColumnHandle> lookupColumns = context.getLookupSymbols().stream()
                    .map(node.getAssignments()::get)
                    .collect(toImmutableSet());

            Set<ColumnHandle> outputColumns = node.getOutputSymbols().stream().map(node.getAssignments()::get).collect(toImmutableSet());

            Optional<ResolvedIndex> optionalResolvedIndex = metadata.resolveIndex(session, node.getTable(), lookupColumns, outputColumns, simplifiedConstraint);
            if (!optionalResolvedIndex.isPresent()) {
                // No index available, so give up by returning something
                return node;
            }
            ResolvedIndex resolvedIndex = optionalResolvedIndex.get();

            Map<ColumnHandle, VariableReferenceExpression> inverseAssignments = new HashMap<>();
            for (Symbol symbol : node.getAssignments().keySet()) {
                inverseAssignments.put(node.getAssignments().get(symbol), new VariableReferenceExpression(symbol.getName(), planSymbolAllocator.getTypes().get(symbol)));
            }

            PlanNode source = new IndexSourceNode(
                    idAllocator.getNextId(),
                    resolvedIndex.getIndexHandle(),
                    node.getTable(),
                    context.getLookupSymbols(),
                    node.getOutputSymbols(),
                    node.getAssignments(),
                    simplifiedConstraint);

            LogicalRowExpressions logicalRowExpressions = new LogicalRowExpressions(new RowExpressionDeterminismEvaluator(metadata),
                                                                                    new FunctionResolution(metadata.getFunctionAndTypeManager()),
                                                                                    metadata.getFunctionAndTypeManager());
            RowExpression resultingPredicate = logicalRowExpressions.combineConjuncts(
                    domainTranslator.toPredicate(resolvedIndex.getUnresolvedTupleDomain().transform(inverseAssignments::get)),
                    decomposedPredicate.getRemainingExpression());

            if (!resultingPredicate.equals(TRUE_CONSTANT)) {
                // todo it is likely we end up with redundant filters here because the predicate push down has already been run... the fix is to run predicate push down again
                source = new FilterNode(idAllocator.getNextId(), source, resultingPredicate);
            }
            context.markSuccess();
            return source;
        }

        @Override
        public PlanNode visitProject(ProjectNode node, RewriteContext<Context> context)
        {
            // Rewrite the lookup symbols in terms of only the pre-projected symbols that have direct translations
            Set<Symbol> newLookupSymbols = context.get().getLookupSymbols().stream()
                    .map(node.getAssignments()::get)
                    .filter(VariableReferenceExpression.class::isInstance)
                    .map(variable -> new Symbol(((VariableReferenceExpression) variable).getName()))
                    .collect(toImmutableSet());

            if (newLookupSymbols.isEmpty()) {
                return node;
            }

            return context.defaultRewrite(node, new Context(newLookupSymbols, context.get().getSuccess()));
        }

        @Override
        public PlanNode visitFilter(FilterNode node, RewriteContext<Context> context)
        {
            if (node.getSource() instanceof TableScanNode) {
                return planTableScan((TableScanNode) node.getSource(), node.getPredicate(), context.get());
            }

            return context.defaultRewrite(node, new Context(context.get().getLookupSymbols(), context.get().getSuccess()));
        }

        @Override
        public PlanNode visitWindow(WindowNode node, RewriteContext<Context> context)
        {
            if (!node.getWindowFunctions().values().stream()
                    .allMatch(function -> metadata.getFunctionAndTypeManager().getFunctionMetadata(function.getFunctionHandle()).getFunctionKind() == AGGREGATE)) {
                return node;
            }

            // Don't need this restriction if we can prove that all order by symbols are deterministically produced
            if (node.getOrderingScheme().isPresent()) {
                return node;
            }

            // Only RANGE frame type currently supported for aggregation functions because it guarantees the
            // same value for each peer group.
            // ROWS frame type requires the ordering to be fully deterministic (e.g. deterministically sorted on all columns)
            if (node.getFrames().stream().map(WindowNode.Frame::getType).anyMatch(type -> type != Types.WindowFrameType.RANGE)) { // TODO: extract frames of type RANGE and allow optimization on them
                return node;
            }

            // Lookup symbols can only be passed through if they are part of the partitioning
            Set<Symbol> partitionByLookupSymbols = context.get().getLookupSymbols().stream()
                    .filter(node.getPartitionBy()::contains)
                    .collect(toImmutableSet());

            if (partitionByLookupSymbols.isEmpty()) {
                return node;
            }

            return context.defaultRewrite(node, new Context(partitionByLookupSymbols, context.get().getSuccess()));
        }

        @Override
        public PlanNode visitIndexSource(IndexSourceNode node, RewriteContext<Context> context)
        {
            throw new IllegalStateException("Should not be trying to generate an Index on something that has already been determined to use an Index");
        }

        @Override
        public PlanNode visitIndexJoin(IndexJoinNode node, RewriteContext<Context> context)
        {
            // Lookup symbols can only be passed through the probe side of an index join
            Set<Symbol> probeLookupSymbols = context.get().getLookupSymbols().stream()
                    .filter(node.getProbeSource().getOutputSymbols()::contains)
                    .collect(toImmutableSet());

            if (probeLookupSymbols.isEmpty()) {
                return node;
            }

            PlanNode rewrittenProbeSource = context.rewrite(node.getProbeSource(), new Context(probeLookupSymbols, context.get().getSuccess()));

            PlanNode source = node;
            if (rewrittenProbeSource != node.getProbeSource()) {
                source = new IndexJoinNode(node.getId(), node.getType(), rewrittenProbeSource, node.getIndexSource(), node.getCriteria(), node.getProbeHashSymbol(), node.getIndexHashSymbol());
            }

            return source;
        }

        @Override
        public PlanNode visitAggregation(AggregationNode node, RewriteContext<Context> context)
        {
            // Lookup symbols can only be passed through if they are part of the group by columns
            Set<Symbol> groupByLookupSymbols = context.get().getLookupSymbols().stream()
                    .filter(node.getGroupingKeys()::contains)
                    .collect(toImmutableSet());

            if (groupByLookupSymbols.isEmpty()) {
                return node;
            }

            return context.defaultRewrite(node, new Context(groupByLookupSymbols, context.get().getSuccess()));
        }

        @Override
        public PlanNode visitSort(SortNode node, RewriteContext<Context> context)
        {
            // Sort has no bearing when building an index, so just ignore the sort
            return context.rewrite(node.getSource(), context.get());
        }

        public static class Context
        {
            private final Set<Symbol> lookupSymbols;
            private final AtomicBoolean success;

            public Context(Set<Symbol> lookupSymbols, AtomicBoolean success)
            {
                checkArgument(!lookupSymbols.isEmpty(), "lookupSymbols can not be empty");
                this.lookupSymbols = ImmutableSet.copyOf(requireNonNull(lookupSymbols, "lookupSymbols is null"));
                this.success = requireNonNull(success, "success is null");
            }

            public Set<Symbol> getLookupSymbols()
            {
                return lookupSymbols;
            }

            public AtomicBoolean getSuccess()
            {
                return success;
            }

            public void markSuccess()
            {
                checkState(success.compareAndSet(false, true), "Can only have one success per context");
            }
        }
    }

    /**
     * Identify the mapping from the lookup symbols used at the top of the index plan to
     * the actual symbols produced by the IndexSource. Note that multiple top-level lookup symbols may share the same
     * underlying IndexSource symbol. Also note that lookup symbols that do not correspond to underlying index source symbols
     * will be omitted from the returned Map.
     */
    public static class IndexKeyTracer
    {
        public static Map<Symbol, Symbol> trace(PlanNode node, Set<Symbol> lookupSymbols)
        {
            return node.accept(new Visitor(), lookupSymbols);
        }

        private static class Visitor
                extends InternalPlanVisitor<Map<Symbol, Symbol>, Set<Symbol>>
        {
            @Override
            public Map<Symbol, Symbol> visitPlan(PlanNode node, Set<Symbol> lookupSymbols)
            {
                throw new UnsupportedOperationException("Node not expected to be part of Index pipeline: " + node);
            }

            @Override
            public Map<Symbol, Symbol> visitProject(ProjectNode node, Set<Symbol> lookupSymbols)
            {
                // Map from output Symbols to source Symbols
                Map<Symbol, Symbol> directSymbolTranslationOutputMap = new HashMap<>();
                for (Map.Entry<Symbol, RowExpression> entry : node.getAssignments().getMap().entrySet()) {
                    if (entry.getValue() instanceof VariableReferenceExpression) {
                        directSymbolTranslationOutputMap.put(entry.getKey(), new Symbol(((VariableReferenceExpression) entry.getValue()).getName()));
                    }
                }

                Map<Symbol, Symbol> outputToSourceMap = lookupSymbols.stream()
                        .filter(directSymbolTranslationOutputMap.keySet()::contains)
                        .collect(toImmutableMap(identity(), directSymbolTranslationOutputMap::get));

                checkState(!outputToSourceMap.isEmpty(), "No lookup symbols were able to pass through the projection");

                // Map from source Symbols to underlying index source Symbols
                Map<Symbol, Symbol> sourceToIndexMap = node.getSource().accept(this, ImmutableSet.copyOf(outputToSourceMap.values()));

                // Generate the Map the connects lookup symbols to underlying index source symbols
                Map<Symbol, Symbol> outputToIndexMap = Maps.transformValues(Maps.filterValues(outputToSourceMap, in(sourceToIndexMap.keySet())), Functions.forMap(sourceToIndexMap));
                return ImmutableMap.copyOf(outputToIndexMap);
            }

            @Override
            public Map<Symbol, Symbol> visitFilter(FilterNode node, Set<Symbol> lookupSymbols)
            {
                return node.getSource().accept(this, lookupSymbols);
            }

            @Override
            public Map<Symbol, Symbol> visitWindow(WindowNode node, Set<Symbol> lookupSymbols)
            {
                Set<Symbol> partitionByLookupSymbols = lookupSymbols.stream()
                        .filter(node.getPartitionBy()::contains)
                        .collect(toImmutableSet());
                checkState(!partitionByLookupSymbols.isEmpty(), "No lookup symbols were able to pass through the aggregation group by");
                return node.getSource().accept(this, partitionByLookupSymbols);
            }

            @Override
            public Map<Symbol, Symbol> visitIndexJoin(IndexJoinNode node, Set<Symbol> lookupSymbols)
            {
                Set<Symbol> probeLookupSymbols = lookupSymbols.stream()
                        .filter(node.getProbeSource().getOutputSymbols()::contains)
                        .collect(toImmutableSet());
                checkState(!probeLookupSymbols.isEmpty(), "No lookup symbols were able to pass through the index join probe source");
                return node.getProbeSource().accept(this, probeLookupSymbols);
            }

            @Override
            public Map<Symbol, Symbol> visitAggregation(AggregationNode node, Set<Symbol> lookupSymbols)
            {
                Set<Symbol> groupByLookupSymbols = lookupSymbols.stream()
                        .filter(node.getGroupingKeys()::contains)
                        .collect(toImmutableSet());
                checkState(!groupByLookupSymbols.isEmpty(), "No lookup symbols were able to pass through the aggregation group by");
                return node.getSource().accept(this, groupByLookupSymbols);
            }

            @Override
            public Map<Symbol, Symbol> visitSort(SortNode node, Set<Symbol> lookupSymbols)
            {
                return node.getSource().accept(this, lookupSymbols);
            }

            @Override
            public Map<Symbol, Symbol> visitIndexSource(IndexSourceNode node, Set<Symbol> lookupSymbols)
            {
                checkState(node.getLookupSymbols().equals(lookupSymbols), "lookupSymbols must be the same as IndexSource lookup symbols");
                return lookupSymbols.stream().collect(toImmutableMap(identity(), identity()));
            }
        }
    }
}
