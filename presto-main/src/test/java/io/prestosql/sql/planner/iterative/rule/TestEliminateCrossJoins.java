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
package io.prestosql.sql.planner.iterative.rule;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.prestosql.spi.plan.Assignments;
import io.prestosql.spi.plan.GroupReference;
import io.prestosql.spi.plan.JoinNode;
import io.prestosql.spi.plan.JoinNode.EquiJoinClause;
import io.prestosql.spi.plan.PlanNode;
import io.prestosql.spi.plan.PlanNodeIdAllocator;
import io.prestosql.spi.plan.ProjectNode;
import io.prestosql.spi.plan.Symbol;
import io.prestosql.spi.plan.ValuesNode;
import io.prestosql.sql.planner.iterative.rule.test.BaseRuleTest;
import io.prestosql.sql.planner.iterative.rule.test.PlanBuilder;
import io.prestosql.sql.planner.optimizations.joins.JoinGraph;
import io.prestosql.sql.relational.OriginalExpressionUtils;
import io.prestosql.sql.tree.ArithmeticUnaryExpression;
import io.prestosql.sql.tree.Expression;
import io.prestosql.sql.tree.SymbolReference;
import org.testng.annotations.Test;

import java.util.Arrays;
import java.util.Optional;
import java.util.function.Function;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.Iterables.getOnlyElement;
import static io.prestosql.SystemSessionProperties.JOIN_REORDERING_STRATEGY;
import static io.prestosql.spi.plan.JoinNode.Type.INNER;
import static io.prestosql.sql.planner.assertions.PlanMatchPattern.any;
import static io.prestosql.sql.planner.assertions.PlanMatchPattern.join;
import static io.prestosql.sql.planner.assertions.PlanMatchPattern.node;
import static io.prestosql.sql.planner.iterative.rule.EliminateCrossJoins.getJoinOrder;
import static io.prestosql.sql.planner.iterative.rule.EliminateCrossJoins.isOriginalOrder;
import static io.prestosql.sql.tree.ArithmeticUnaryExpression.Sign.MINUS;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

@Test(singleThreaded = true)
public class TestEliminateCrossJoins
        extends BaseRuleTest
{
    private final PlanNodeIdAllocator idAllocator = new PlanNodeIdAllocator();

    @Test
    public void testEliminateCrossJoin()
    {
        tester().assertThat(new EliminateCrossJoins())
                .setSystemProperty(JOIN_REORDERING_STRATEGY, "ELIMINATE_CROSS_JOINS")
                .on(crossJoinAndJoin(INNER))
                .matches(
                        join(INNER,
                                ImmutableList.of(aliases -> new EquiJoinClause(new Symbol("cySymbol"), new Symbol("bySymbol"))),
                                join(INNER,
                                        ImmutableList.of(aliases -> new EquiJoinClause(new Symbol("axSymbol"), new Symbol("cxSymbol"))),
                                        any(),
                                        any()),
                                any()));
    }

    @Test
    public void testRetainOutgoingGroupReferences()
    {
        tester().assertThat(new EliminateCrossJoins())
                .setSystemProperty(JOIN_REORDERING_STRATEGY, "ELIMINATE_CROSS_JOINS")
                .on(crossJoinAndJoin(INNER))
                .matches(
                        node(JoinNode.class,
                                node(JoinNode.class,
                                        node(GroupReference.class),
                                        node(GroupReference.class)),
                                node(GroupReference.class)));
    }

    @Test
    public void testDoNotReorderOuterJoin()
    {
        tester().assertThat(new EliminateCrossJoins())
                .setSystemProperty(JOIN_REORDERING_STRATEGY, "ELIMINATE_CROSS_JOINS")
                .on(crossJoinAndJoin(JoinNode.Type.LEFT))
                .doesNotFire();
    }

    @Test
    public void testIsOriginalOrder()
    {
        assertTrue(isOriginalOrder(ImmutableList.of(0, 1, 2, 3, 4)));
        assertFalse(isOriginalOrder(ImmutableList.of(0, 2, 1, 3, 4)));
    }

    @Test
    public void testJoinOrder()
    {
        PlanNode plan =
                joinNode(
                        joinNode(
                                values(symbol("a")),
                                values(symbol("b"))),
                        values(symbol("c")),
                        symbol("a"), symbol("c"),
                        symbol("c"), symbol("b"));

        JoinGraph joinGraph = getOnlyElement(JoinGraph.buildFrom(plan));

        assertEquals(
                getJoinOrder(joinGraph),
                ImmutableList.of(0, 2, 1));
    }

    @Test
    public void testJoinOrderWithRealCrossJoin()
    {
        PlanNode leftPlan =
                joinNode(
                        joinNode(
                                values(symbol("a")),
                                values(symbol("b"))),
                        values(symbol("c")),
                        symbol("a"), symbol("c"),
                        symbol("c"), symbol("b"));

        PlanNode rightPlan =
                joinNode(
                        joinNode(
                                values(symbol("x")),
                                values(symbol("y"))),
                        values(symbol("z")),
                        symbol("x"), symbol("z"),
                        symbol("z"), symbol("y"));

        PlanNode plan = joinNode(leftPlan, rightPlan);

        JoinGraph joinGraph = getOnlyElement(JoinGraph.buildFrom(plan));

        assertEquals(
                getJoinOrder(joinGraph),
                ImmutableList.of(0, 2, 1, 3, 5, 4));
    }

    @Test
    public void testJoinOrderWithMultipleEdgesBetweenNodes()
    {
        PlanNode plan =
                joinNode(
                        joinNode(
                                values(symbol("a")),
                                values(symbol("b1"), symbol("b2"))),
                        values(symbol("c1"), symbol("c2")),
                        symbol("a"), symbol("c1"),
                        symbol("c1"), symbol("b1"),
                        symbol("c2"), symbol("b2"));

        JoinGraph joinGraph = getOnlyElement(JoinGraph.buildFrom(plan));

        assertEquals(
                getJoinOrder(joinGraph),
                ImmutableList.of(0, 2, 1));
    }

    @Test
    public void testDonNotChangeOrderWithoutCrossJoin()
    {
        PlanNode plan =
                joinNode(
                        joinNode(
                                values(symbol("a")),
                                values(symbol("b")),
                                symbol("a"), symbol("b")),
                        values(symbol("c")),
                        symbol("c"), symbol("b"));

        JoinGraph joinGraph = getOnlyElement(JoinGraph.buildFrom(plan));

        assertEquals(
                getJoinOrder(joinGraph),
                ImmutableList.of(0, 1, 2));
    }

    @Test
    public void testDoNotReorderCrossJoins()
    {
        PlanNode plan =
                joinNode(
                        joinNode(
                                values(symbol("a")),
                                values(symbol("b"))),
                        values(symbol("c")),
                        symbol("c"), symbol("b"));

        JoinGraph joinGraph = getOnlyElement(JoinGraph.buildFrom(plan));

        assertEquals(
                getJoinOrder(joinGraph),
                ImmutableList.of(0, 1, 2));
    }

    @Test
    public void testGiveUpOnNonIdentityProjections()
    {
        PlanNode plan =
                joinNode(
                        projectNode(
                                joinNode(
                                        values(symbol("a1")),
                                        values(symbol("b"))),
                                symbol("a2"),
                                new ArithmeticUnaryExpression(MINUS, new SymbolReference("a1"))),
                        values(symbol("c")),
                        symbol("a2"), symbol("c"),
                        symbol("c"), symbol("b"));

        assertEquals(JoinGraph.buildFrom(plan).size(), 2);
    }

    private Function<PlanBuilder, PlanNode> crossJoinAndJoin(JoinNode.Type secondJoinType)
    {
        return p -> {
            Symbol axSymbol = p.symbol("axSymbol");
            Symbol bySymbol = p.symbol("bySymbol");
            Symbol cxSymbol = p.symbol("cxSymbol");
            Symbol cySymbol = p.symbol("cySymbol");

            // (a inner join b) inner join c on c.x = a.x and c.y = b.y
            return p.join(INNER,
                    p.join(secondJoinType,
                            p.values(axSymbol),
                            p.values(bySymbol)),
                    p.values(cxSymbol, cySymbol),
                    new EquiJoinClause(cxSymbol, axSymbol),
                    new EquiJoinClause(cySymbol, bySymbol));
        };
    }

    private PlanNode projectNode(PlanNode source, String symbol, Expression expression)
    {
        return new ProjectNode(
                idAllocator.getNextId(),
                source,
                Assignments.of(new Symbol(symbol), OriginalExpressionUtils.castToRowExpression(expression)));
    }

    private String symbol(String name)
    {
        return name;
    }

    private JoinNode joinNode(PlanNode left, PlanNode right, String... symbols)
    {
        checkArgument(symbols.length % 2 == 0);
        ImmutableList.Builder<JoinNode.EquiJoinClause> criteria = ImmutableList.builder();

        for (int i = 0; i < symbols.length; i += 2) {
            criteria.add(new JoinNode.EquiJoinClause(new Symbol(symbols[i]), new Symbol(symbols[i + 1])));
        }

        return new JoinNode(
                idAllocator.getNextId(),
                JoinNode.Type.INNER,
                left,
                right,
                criteria.build(),
                ImmutableList.<Symbol>builder()
                        .addAll(left.getOutputSymbols())
                        .addAll(right.getOutputSymbols())
                        .build(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                ImmutableMap.of());
    }

    private ValuesNode values(String... symbols)
    {
        return new ValuesNode(
                idAllocator.getNextId(),
                Arrays.stream(symbols).map(Symbol::new).collect(toImmutableList()),
                ImmutableList.of());
    }
}
