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
import io.prestosql.spi.plan.AggregationNode;
import io.prestosql.spi.plan.FilterNode;
import io.prestosql.spi.plan.ValuesNode;
import io.prestosql.sql.planner.iterative.rule.test.BaseRuleTest;
import io.prestosql.sql.planner.plan.SortNode;
import org.testng.annotations.Test;

import static io.prestosql.spi.type.BigintType.BIGINT;
import static io.prestosql.sql.planner.assertions.PlanMatchPattern.node;
import static io.prestosql.sql.planner.assertions.PlanMatchPattern.values;
import static io.prestosql.sql.planner.iterative.rule.test.PlanBuilder.constantExpressions;
import static io.prestosql.sql.planner.iterative.rule.test.PlanBuilder.expression;

public class TestRemoveRedundantTopN
        extends BaseRuleTest
{
    @Test
    public void test()
    {
        tester().assertThat(new RemoveRedundantTopN())
                .on(p ->
                        p.topN(
                                10,
                                ImmutableList.of(p.symbol("c")),
                                p.aggregation(builder -> builder
                                        .addAggregation(p.symbol("c"), expression("count(foo)"), ImmutableList.of(BIGINT))
                                        .globalGrouping()
                                        .source(p.values(p.symbol("foo"))))))
                .matches(
                        node(AggregationNode.class,
                                node(ValuesNode.class)));

        tester().assertThat(new RemoveRedundantTopN())
                .on(p ->
                        p.topN(
                                10,
                                ImmutableList.of(p.symbol("a")),
                                p.filter(
                                        expression("b > 5"),
                                        p.values(
                                                ImmutableList.of(p.symbol("a"), p.symbol("b")),
                                                ImmutableList.of(
                                                        constantExpressions(BIGINT, 1L, 10L),
                                                        constantExpressions(BIGINT, 2L, 11L))))))
                // TODO: verify contents
                .matches(
                        node(SortNode.class,
                                node(FilterNode.class,
                                        node(ValuesNode.class))));
    }

    @Test
    public void testZeroTopN()
    {
        tester().assertThat(new RemoveRedundantTopN())
                .on(p ->
                        p.topN(
                                0,
                                ImmutableList.of(p.symbol("a")),
                                p.filter(
                                        expression("b > 5"),
                                        p.values(
                                                ImmutableList.of(p.symbol("a"), p.symbol("b")),
                                                ImmutableList.of(
                                                        constantExpressions(BIGINT, 1L, 10L),
                                                        constantExpressions(BIGINT, 2L, 11L))))))
                // TODO: verify contents
                .matches(values(ImmutableMap.of()));
    }

    @Test
    public void doesNotFire()
    {
        tester().assertThat(new RemoveRedundantTopN())
                .on(p ->
                        p.topN(
                                10,
                                ImmutableList.of(p.symbol("c")),
                                p.aggregation(builder -> builder
                                        .addAggregation(p.symbol("c"), expression("count(foo)"), ImmutableList.of(BIGINT))
                                        .singleGroupingSet(p.symbol("foo"))
                                        .source(p.values(20, p.symbol("foo"))))))
                .doesNotFire();
    }
}
