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
package io.prestosql.sql.planner;

import com.google.common.collect.ImmutableBiMap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import io.prestosql.Session;
import io.prestosql.metadata.Metadata;
import io.prestosql.spi.connector.ColumnHandle;
import io.prestosql.spi.plan.AggregationNode;
import io.prestosql.spi.plan.FilterNode;
import io.prestosql.spi.plan.JoinNode;
import io.prestosql.spi.plan.LimitNode;
import io.prestosql.spi.plan.PlanNode;
import io.prestosql.spi.plan.ProjectNode;
import io.prestosql.spi.plan.Symbol;
import io.prestosql.spi.plan.TableScanNode;
import io.prestosql.spi.plan.TopNNode;
import io.prestosql.spi.plan.UnionNode;
import io.prestosql.spi.plan.ValuesNode;
import io.prestosql.spi.plan.WindowNode;
import io.prestosql.spi.predicate.Domain;
import io.prestosql.spi.predicate.TupleDomain;
import io.prestosql.spi.type.Type;
import io.prestosql.sql.planner.optimizations.JoinNodeUtils;
import io.prestosql.sql.planner.plan.AssignUniqueId;
import io.prestosql.sql.planner.plan.DistinctLimitNode;
import io.prestosql.sql.planner.plan.ExchangeNode;
import io.prestosql.sql.planner.plan.InternalPlanVisitor;
import io.prestosql.sql.planner.plan.SemiJoinNode;
import io.prestosql.sql.planner.plan.SortNode;
import io.prestosql.sql.planner.plan.SpatialJoinNode;
import io.prestosql.sql.relational.OriginalExpressionUtils;
import io.prestosql.sql.tree.ComparisonExpression;
import io.prestosql.sql.tree.Expression;
import io.prestosql.sql.tree.NodeRef;
import io.prestosql.sql.tree.SymbolReference;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static com.google.common.base.Predicates.in;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.Maps.transformValues;
import static io.prestosql.sql.ExpressionUtils.combineConjuncts;
import static io.prestosql.sql.ExpressionUtils.expressionOrNullSymbols;
import static io.prestosql.sql.ExpressionUtils.extractConjuncts;
import static io.prestosql.sql.ExpressionUtils.filterDeterministicConjuncts;
import static io.prestosql.sql.planner.EqualityInference.createEqualityInference;
import static io.prestosql.sql.planner.SymbolUtils.toSymbolReference;
import static io.prestosql.sql.planner.optimizations.SetOperationNodeUtils.outputSymbolMap;
import static io.prestosql.sql.relational.OriginalExpressionUtils.castToExpression;
import static io.prestosql.sql.tree.BooleanLiteral.TRUE_LITERAL;
import static io.prestosql.sql.tree.ComparisonExpression.Operator.EQUAL;
import static java.util.Objects.requireNonNull;

/**
 * Computes the effective predicate at the top of the specified PlanNode
 * <p>
 * Note: non-deterministic predicates can not be pulled up (so they will be ignored)
 */
public class EffectivePredicateExtractor
{
    private static final Predicate<Map.Entry<Symbol, ? extends Expression>> SYMBOL_MATCHES_EXPRESSION =
            entry -> entry.getValue().equals(toSymbolReference(entry.getKey()));

    private static final Function<Map.Entry<Symbol, ? extends Expression>, Expression> ENTRY_TO_EQUALITY =
            entry -> {
                SymbolReference reference = toSymbolReference(entry.getKey());
                Expression expression = entry.getValue();
                // TODO: this is not correct with respect to NULLs ('reference IS NULL' would be correct, rather than 'reference = NULL')
                // TODO: switch this to 'IS NOT DISTINCT FROM' syntax when EqualityInference properly supports it
                return new ComparisonExpression(EQUAL, reference, expression);
            };

    private final ExpressionDomainTranslator domainTranslator;
    private final Metadata metadata;
    private final boolean useTableProperties;

    public EffectivePredicateExtractor(ExpressionDomainTranslator domainTranslator, Metadata metadata, boolean useTableProperties)
    {
        this.domainTranslator = requireNonNull(domainTranslator, "domainTranslator is null");
        this.metadata = requireNonNull(metadata, "metadata is null");
        this.useTableProperties = useTableProperties;
    }

    public Expression extract(Session session, PlanNode node, TypeProvider types, TypeAnalyzer typeAnalyzer)
    {
        return node.accept(new Visitor(domainTranslator, metadata, session, types, typeAnalyzer, useTableProperties), null);
    }

    private static class Visitor
            extends InternalPlanVisitor<Expression, Void>
    {
        private final ExpressionDomainTranslator domainTranslator;
        private final Metadata metadata;
        private final Session session;
        private final TypeProvider types;
        private final TypeAnalyzer typeAnalyzer;
        private final boolean useTableProperties;

        public Visitor(ExpressionDomainTranslator domainTranslator, Metadata metadata, Session session, TypeProvider types, TypeAnalyzer typeAnalyzer, boolean useTableProperties)
        {
            this.domainTranslator = requireNonNull(domainTranslator, "domainTranslator is null");
            this.metadata = requireNonNull(metadata, "metadata is null");
            this.session = requireNonNull(session, "session is null");
            this.types = requireNonNull(types, "types is null");
            this.typeAnalyzer = requireNonNull(typeAnalyzer, "typeAnalyzer is null");
            this.useTableProperties = useTableProperties;
        }

        @Override
        public Expression visitPlan(PlanNode node, Void context)
        {
            return TRUE_LITERAL;
        }

        @Override
        public Expression visitAggregation(AggregationNode node, Void context)
        {
            // GROUP BY () always produces a group, regardless of whether there's any
            // input (unlike the case where there are group by keys, which produce
            // no output if there's no input).
            // Therefore, we can't say anything about the effective predicate of the
            // output of such an aggregation.
            if (node.getGroupingKeys().isEmpty()) {
                return TRUE_LITERAL;
            }

            Expression underlyingPredicate = node.getSource().accept(this, context);

            return pullExpressionThroughSymbols(underlyingPredicate, node.getGroupingKeys());
        }

        @Override
        public Expression visitFilter(FilterNode node, Void context)
        {
            Expression underlyingPredicate = node.getSource().accept(this, context);

            Expression predicate = castToExpression(node.getPredicate());

            // Remove non-deterministic conjuncts
            predicate = filterDeterministicConjuncts(predicate);

            return combineConjuncts(predicate, underlyingPredicate);
        }

        @Override
        public Expression visitExchange(ExchangeNode node, Void context)
        {
            return deriveCommonPredicates(node, source -> {
                Map<Symbol, SymbolReference> mappings = new HashMap<>();
                for (int i = 0; i < node.getInputs().get(source).size(); i++) {
                    mappings.put(
                            node.getOutputSymbols().get(i),
                            toSymbolReference(node.getInputs().get(source).get(i)));
                }
                return mappings.entrySet();
            });
        }

        @Override
        public Expression visitProject(ProjectNode node, Void context)
        {
            // TODO: add simple algebraic solver for projection translation (right now only considers identity projections)

            Expression underlyingPredicate = node.getSource().accept(this, context);

            List<Expression> projectionEqualities = transformValues(node.getAssignments().getMap(), OriginalExpressionUtils::castToExpression).entrySet().stream()
                    .filter(SYMBOL_MATCHES_EXPRESSION.negate())
                    .map(ENTRY_TO_EQUALITY)
                    .collect(toImmutableList());

            return pullExpressionThroughSymbols(combineConjuncts(
                    ImmutableList.<Expression>builder()
                            .addAll(projectionEqualities)
                            .add(underlyingPredicate)
                            .build()),
                    node.getOutputSymbols());
        }

        @Override
        public Expression visitTopN(TopNNode node, Void context)
        {
            return node.getSource().accept(this, context);
        }

        @Override
        public Expression visitLimit(LimitNode node, Void context)
        {
            return node.getSource().accept(this, context);
        }

        @Override
        public Expression visitAssignUniqueId(AssignUniqueId node, Void context)
        {
            return node.getSource().accept(this, context);
        }

        @Override
        public Expression visitDistinctLimit(DistinctLimitNode node, Void context)
        {
            return node.getSource().accept(this, context);
        }

        @Override
        public Expression visitTableScan(TableScanNode node, Void context)
        {
            Map<ColumnHandle, Symbol> assignments = ImmutableBiMap.copyOf(node.getAssignments()).inverse();

            TupleDomain<ColumnHandle> predicate = node.getEnforcedConstraint();
            if (useTableProperties) {
                predicate = metadata.getTableProperties(session, node.getTable()).getPredicate();
            }

            return domainTranslator.toPredicate(predicate.simplify().transform(assignments::get));
        }

        @Override
        public Expression visitSort(SortNode node, Void context)
        {
            return node.getSource().accept(this, context);
        }

        @Override
        public Expression visitWindow(WindowNode node, Void context)
        {
            return node.getSource().accept(this, context);
        }

        @Override
        public Expression visitUnion(UnionNode node, Void context)
        {
            return deriveCommonPredicates(node, source -> outputSymbolMap(node, source).entries());
        }

        @Override
        public Expression visitJoin(JoinNode node, Void context)
        {
            Expression leftPredicate = node.getLeft().accept(this, context);
            Expression rightPredicate = node.getRight().accept(this, context);

            List<Expression> joinConjuncts = node.getCriteria().stream()
                    .map(JoinNodeUtils::toExpression)
                    .collect(toImmutableList());

            switch (node.getType()) {
                case INNER:
                    return pullExpressionThroughSymbols(combineConjuncts(ImmutableList.<Expression>builder()
                            .add(leftPredicate)
                            .add(rightPredicate)
                            .add(combineConjuncts(joinConjuncts))
                            .add(node.getFilter().map(OriginalExpressionUtils::castToExpression).orElse(TRUE_LITERAL))
                            .build()), node.getOutputSymbols());
                case LEFT:
                    return combineConjuncts(ImmutableList.<Expression>builder()
                            .add(pullExpressionThroughSymbols(leftPredicate, node.getOutputSymbols()))
                            .addAll(pullNullableConjunctsThroughOuterJoin(extractConjuncts(rightPredicate), node.getOutputSymbols(), node.getRight().getOutputSymbols()::contains))
                            .addAll(pullNullableConjunctsThroughOuterJoin(joinConjuncts, node.getOutputSymbols(), node.getRight().getOutputSymbols()::contains))
                            .build());
                case RIGHT:
                    return combineConjuncts(ImmutableList.<Expression>builder()
                            .add(pullExpressionThroughSymbols(rightPredicate, node.getOutputSymbols()))
                            .addAll(pullNullableConjunctsThroughOuterJoin(extractConjuncts(leftPredicate), node.getOutputSymbols(), node.getLeft().getOutputSymbols()::contains))
                            .addAll(pullNullableConjunctsThroughOuterJoin(joinConjuncts, node.getOutputSymbols(), node.getLeft().getOutputSymbols()::contains))
                            .build());
                case FULL:
                    return combineConjuncts(ImmutableList.<Expression>builder()
                            .addAll(pullNullableConjunctsThroughOuterJoin(extractConjuncts(leftPredicate), node.getOutputSymbols(), node.getLeft().getOutputSymbols()::contains))
                            .addAll(pullNullableConjunctsThroughOuterJoin(extractConjuncts(rightPredicate), node.getOutputSymbols(), node.getRight().getOutputSymbols()::contains))
                            .addAll(pullNullableConjunctsThroughOuterJoin(joinConjuncts, node.getOutputSymbols(), node.getLeft().getOutputSymbols()::contains, node.getRight().getOutputSymbols()::contains))
                            .build());
                default:
                    throw new UnsupportedOperationException("Unknown join type: " + node.getType());
            }
        }

        @Override
        public Expression visitValues(ValuesNode node, Void context)
        {
            if (node.getOutputSymbols().isEmpty()) {
                return TRUE_LITERAL;
            }

            // get all types in one shot -- needed for the expression optimizer below
            List<Expression> allExpressions = node.getRows().stream()
                    .flatMap(List::stream)
                    .map(OriginalExpressionUtils::castToExpression)
                    .collect(Collectors.toList());

            Map<NodeRef<Expression>, Type> expressionTypes = typeAnalyzer.getTypes(session, types, allExpressions);

            ImmutableMap.Builder<Symbol, Domain> domains = ImmutableMap.builder();

            for (int column = 0; column < node.getOutputSymbols().size(); column++) {
                Symbol symbol = node.getOutputSymbols().get(column);
                Type type = types.get(symbol);

                ImmutableList.Builder<Object> builder = ImmutableList.builder();
                boolean hasNull = false;
                boolean nonDeterministic = false;
                for (int row = 0; row < node.getRows().size(); row++) {
                    Expression value = castToExpression(node.getRows().get(row).get(column));

                    if (!ExpressionDeterminismEvaluator.isDeterministic(value)) {
                        nonDeterministic = true;
                        break;
                    }

                    ExpressionInterpreter interpreter = ExpressionInterpreter.expressionOptimizer(value, metadata, session, expressionTypes);
                    Object evaluated = interpreter.optimize(NoOpSymbolResolver.INSTANCE);

                    if (evaluated instanceof Expression) {
                        return TRUE_LITERAL;
                    }

                    if (evaluated == null) {
                        hasNull = true;
                    }
                    else {
                        builder.add(evaluated);
                    }
                }

                if (nonDeterministic) {
                    // We can't describe a predicate for this column because at least
                    // one cell is non-deterministic, so skip it.
                    continue;
                }

                List<Object> values = builder.build();

                Domain domain = Domain.none(type);

                if (!values.isEmpty()) {
                    domain = domain.union(Domain.multipleValues(type, values));
                }

                if (hasNull) {
                    domain = domain.union(Domain.onlyNull(type));
                }

                domains.put(symbol, domain);
            }

            // simplify to avoid a large expression if there are many rows in ValuesNode
            return domainTranslator.toPredicate(TupleDomain.withColumnDomains(domains.build()).simplify());
        }

        private static Iterable<Expression> pullNullableConjunctsThroughOuterJoin(List<Expression> conjuncts, Collection<Symbol> outputSymbols, Predicate<Symbol>... nullSymbolScopes)
        {
            // Conjuncts without any symbol dependencies cannot be applied to the effective predicate (e.g. FALSE literal)
            return conjuncts.stream()
                    .map(expression -> pullExpressionThroughSymbols(expression, outputSymbols))
                    .map(expression -> SymbolsExtractor.extractAll(expression).isEmpty() ? TRUE_LITERAL : expression)
                    .map(expressionOrNullSymbols(nullSymbolScopes))
                    .collect(toImmutableList());
        }

        @Override
        public Expression visitSemiJoin(SemiJoinNode node, Void context)
        {
            // Filtering source does not change the effective predicate over the output symbols
            return node.getSource().accept(this, context);
        }

        @Override
        public Expression visitSpatialJoin(SpatialJoinNode node, Void context)
        {
            Expression leftPredicate = node.getLeft().accept(this, context);
            Expression rightPredicate = node.getRight().accept(this, context);

            switch (node.getType()) {
                case INNER:
                    return combineConjuncts(ImmutableList.<Expression>builder()
                            .add(pullExpressionThroughSymbols(leftPredicate, node.getOutputSymbols()))
                            .add(pullExpressionThroughSymbols(rightPredicate, node.getOutputSymbols()))
                            .build());
                case LEFT:
                    return combineConjuncts(ImmutableList.<Expression>builder()
                            .add(pullExpressionThroughSymbols(leftPredicate, node.getOutputSymbols()))
                            .addAll(pullNullableConjunctsThroughOuterJoin(extractConjuncts(rightPredicate), node.getOutputSymbols(), node.getRight().getOutputSymbols()::contains))
                            .build());
                default:
                    throw new IllegalArgumentException("Unsupported spatial join type: " + node.getType());
            }
        }

        private Expression deriveCommonPredicates(PlanNode node, Function<Integer, Collection<Map.Entry<Symbol, SymbolReference>>> mapping)
        {
            // Find the predicates that can be pulled up from each source
            List<Set<Expression>> sourceOutputConjuncts = new ArrayList<>();
            for (int i = 0; i < node.getSources().size(); i++) {
                Expression underlyingPredicate = node.getSources().get(i).accept(this, null);

                List<Expression> equalities = mapping.apply(i).stream()
                        .filter(SYMBOL_MATCHES_EXPRESSION.negate())
                        .map(ENTRY_TO_EQUALITY)
                        .collect(toImmutableList());

                sourceOutputConjuncts.add(ImmutableSet.copyOf(extractConjuncts(pullExpressionThroughSymbols(combineConjuncts(
                        ImmutableList.<Expression>builder()
                                .addAll(equalities)
                                .add(underlyingPredicate)
                                .build()),
                        node.getOutputSymbols()))));
            }

            // Find the intersection of predicates across all sources
            // TODO: use a more precise way to determine overlapping conjuncts (e.g. commutative predicates)
            Iterator<Set<Expression>> iterator = sourceOutputConjuncts.iterator();
            Set<Expression> potentialOutputConjuncts = iterator.next();
            while (iterator.hasNext()) {
                potentialOutputConjuncts = Sets.intersection(potentialOutputConjuncts, iterator.next());
            }

            return combineConjuncts(potentialOutputConjuncts);
        }

        private static List<Expression> pullExpressionsThroughSymbols(List<Expression> expressions, Collection<Symbol> symbols)
        {
            return expressions.stream()
                    .map(expression -> pullExpressionThroughSymbols(expression, symbols))
                    .collect(toImmutableList());
        }

        private static Expression pullExpressionThroughSymbols(Expression expression, Collection<Symbol> symbols)
        {
            EqualityInference equalityInference = createEqualityInference(expression);

            ImmutableList.Builder<Expression> effectiveConjuncts = ImmutableList.builder();
            for (Expression conjunct : EqualityInference.nonInferrableConjuncts(expression)) {
                if (ExpressionDeterminismEvaluator.isDeterministic(conjunct)) {
                    Expression rewritten = equalityInference.rewriteExpression(conjunct, in(symbols));
                    if (rewritten != null) {
                        effectiveConjuncts.add(rewritten);
                    }
                }
            }

            effectiveConjuncts.addAll(equalityInference.generateEqualitiesPartitionedBy(in(symbols)).getScopeEqualities());

            return combineConjuncts(effectiveConjuncts.build());
        }
    }
}
