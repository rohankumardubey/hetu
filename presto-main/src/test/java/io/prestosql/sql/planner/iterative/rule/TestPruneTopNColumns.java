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

import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.prestosql.spi.plan.Assignments;
import io.prestosql.spi.plan.ProjectNode;
import io.prestosql.spi.plan.Symbol;
import io.prestosql.sql.planner.iterative.rule.test.BaseRuleTest;
import io.prestosql.sql.planner.iterative.rule.test.PlanBuilder;
import org.testng.annotations.Test;

import java.util.function.Predicate;
import java.util.stream.Collectors;

import static io.prestosql.spi.type.BigintType.BIGINT;
import static io.prestosql.sql.planner.assertions.PlanMatchPattern.expression;
import static io.prestosql.sql.planner.assertions.PlanMatchPattern.sort;
import static io.prestosql.sql.planner.assertions.PlanMatchPattern.strictProject;
import static io.prestosql.sql.planner.assertions.PlanMatchPattern.topN;
import static io.prestosql.sql.planner.assertions.PlanMatchPattern.values;
import static io.prestosql.sql.tree.SortItem.NullOrdering.FIRST;
import static io.prestosql.sql.tree.SortItem.Ordering.ASCENDING;

public class TestPruneTopNColumns
        extends BaseRuleTest
{
    private static final long COUNT = 10;

    @Test
    public void testNotAllInputsReferenced()
    {
        tester().assertThat(new PruneTopNColumns())
                .on(p -> buildProjectedTopN(p, symbol -> symbol.getName().equals("b")))
                .matches(
                        strictProject(
                                ImmutableMap.of("b", expression("b")),
                                topN(
                                        COUNT,
                                        ImmutableList.of(sort("b", ASCENDING, FIRST)),
                                        strictProject(
                                                ImmutableMap.of("b", expression("b")),
                                                values("a", "b")))));
    }

    @Test
    public void testAllInputsRereferenced()
    {
        tester().assertThat(new PruneTopNColumns())
                .on(p -> buildProjectedTopN(p, symbol -> symbol.getName().equals("a")))
                .doesNotFire();
    }

    @Test
    public void testAllOutputsReferenced()
    {
        tester().assertThat(new PruneTopNColumns())
                .on(p -> buildProjectedTopN(p, Predicates.alwaysTrue()))
                .doesNotFire();
    }

    private ProjectNode buildProjectedTopN(PlanBuilder planBuilder, Predicate<Symbol> projectionTopN)
    {
        Symbol a = planBuilder.symbol("a");
        Symbol b = planBuilder.symbol("b");
        return planBuilder.project(
                Assignments.copyOf(ImmutableList.of(a, b).stream().filter(projectionTopN).collect(Collectors.toMap(v -> v, v -> planBuilder.variable(v.getName(), BIGINT)))),
                planBuilder.topN(
                        COUNT,
                        ImmutableList.of(b),
                        planBuilder.values(a, b)));
    }
}
