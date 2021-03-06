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
import io.prestosql.spi.StandardErrorCode;
import io.prestosql.spi.plan.Symbol;
import io.prestosql.spi.relation.RowExpression;
import io.prestosql.spi.type.StandardTypes;
import io.prestosql.sql.planner.FunctionCallBuilder;
import io.prestosql.sql.planner.iterative.Rule;
import io.prestosql.sql.planner.iterative.rule.test.BaseRuleTest;
import io.prestosql.sql.tree.Cast;
import io.prestosql.sql.tree.Expression;
import io.prestosql.sql.tree.LongLiteral;
import io.prestosql.sql.tree.QualifiedName;
import io.prestosql.sql.tree.SimpleCaseExpression;
import io.prestosql.sql.tree.StringLiteral;
import io.prestosql.sql.tree.SymbolReference;
import io.prestosql.sql.tree.WhenClause;
import org.testng.annotations.Test;

import java.util.List;
import java.util.Optional;

import static io.prestosql.metadata.MetadataManager.createTestMetadataManager;
import static io.prestosql.spi.type.BigintType.BIGINT;
import static io.prestosql.spi.type.IntegerType.INTEGER;
import static io.prestosql.spi.type.VarcharType.VARCHAR;
import static io.prestosql.sql.planner.assertions.PlanMatchPattern.assignUniqueId;
import static io.prestosql.sql.planner.assertions.PlanMatchPattern.expression;
import static io.prestosql.sql.planner.assertions.PlanMatchPattern.filter;
import static io.prestosql.sql.planner.assertions.PlanMatchPattern.lateral;
import static io.prestosql.sql.planner.assertions.PlanMatchPattern.markDistinct;
import static io.prestosql.sql.planner.assertions.PlanMatchPattern.project;
import static io.prestosql.sql.planner.assertions.PlanMatchPattern.values;
import static io.prestosql.sql.planner.iterative.rule.test.PlanBuilder.assignment;
import static io.prestosql.sql.planner.iterative.rule.test.PlanBuilder.constantExpressions;
import static io.prestosql.sql.relational.Expressions.constant;
import static io.prestosql.sql.tree.BooleanLiteral.TRUE_LITERAL;

public class TestTransformCorrelatedScalarSubquery
        extends BaseRuleTest
{
    private static final ImmutableList<List<RowExpression>> ONE_ROW = ImmutableList.of(ImmutableList.of(constant(1L, BIGINT)));
    private static final ImmutableList<List<RowExpression>> TWO_ROWS = ImmutableList.of(ImmutableList.of(constant(1L, BIGINT)), ImmutableList.of(constant(2L, BIGINT)));

    private Rule<?> rule = new TransformCorrelatedScalarSubquery(createTestMetadataManager());

    @Test
    public void doesNotFireOnPlanWithoutLateralNode()
    {
        tester().assertThat(rule)
                .on(p -> p.values(p.symbol("a")))
                .doesNotFire();
    }

    @Test
    public void doesNotFireOnCorrelatedNonScalar()
    {
        tester().assertThat(rule)
                .on(p -> p.lateral(
                        ImmutableList.of(p.symbol("corr")),
                        p.values(p.symbol("corr")),
                        p.values(p.symbol("a"))))
                .doesNotFire();
    }

    @Test
    public void doesNotFireOnUncorrelated()
    {
        tester().assertThat(rule)
                .on(p -> p.lateral(
                        ImmutableList.<Symbol>of(),
                        p.values(p.symbol("a")),
                        p.values(ImmutableList.of(p.symbol("b")), ImmutableList.of(constantExpressions(BIGINT, 1L)))))
                .doesNotFire();
    }

    @Test
    public void rewritesOnSubqueryWithoutProjection()
    {
        tester().assertThat(rule)
                .on(p -> p.lateral(
                        ImmutableList.of(p.symbol("corr")),
                        p.values(p.symbol("corr")),
                        p.enforceSingleRow(
                                p.filter(
                                        p.expression("1 = a"), // TODO use correlated predicate, it requires support for correlated subqueries in plan matchers
                                        p.values(ImmutableList.of(p.symbol("a")), TWO_ROWS)))))
                .matches(
                        project(
                                filter(
                                        ensureScalarSubquery(),
                                        markDistinct(
                                                "is_distinct",
                                                ImmutableList.of("corr", "unique"),
                                                lateral(
                                                        ImmutableList.of("corr"),
                                                        assignUniqueId(
                                                                "unique",
                                                                values("corr")),
                                                        filter(
                                                                "1 = a",
                                                                values("a")))))));
    }

    @Test
    public void rewritesOnSubqueryWithProjection()
    {
        tester().assertThat(rule)
                .on(p -> p.lateral(
                        ImmutableList.of(p.symbol("corr")),
                        p.values(p.symbol("corr")),
                        p.enforceSingleRow(
                                p.project(
                                        assignment(p.symbol("a2"), p.expression("a * 2")),
                                        p.filter(
                                                p.expression("1 = a"), // TODO use correlated predicate, it requires support for correlated subqueries in plan matchers
                                                p.values(ImmutableList.of(p.symbol("a")), TWO_ROWS))))))
                .matches(
                        project(
                                filter(
                                        ensureScalarSubquery(),
                                        markDistinct(
                                                "is_distinct",
                                                ImmutableList.of("corr", "unique"),
                                                lateral(
                                                        ImmutableList.of("corr"),
                                                        assignUniqueId(
                                                                "unique",
                                                                values("corr")),
                                                        project(ImmutableMap.of("a2", expression("a * 2")),
                                                                filter("1 = a",
                                                                        values("a"))))))));
    }

    @Test
    public void rewritesOnSubqueryWithProjectionOnTopEnforceSingleNode()
    {
        tester().assertThat(rule)
                .on(p -> p.lateral(
                        ImmutableList.of(p.symbol("corr")),
                        p.values(p.symbol("corr")),
                        p.project(
                                assignment(p.symbol("a3"), p.expression("a2 + 1")),
                                p.enforceSingleRow(
                                        p.project(
                                                assignment(p.symbol("a2"), p.expression("a * 2")),
                                                p.filter(
                                                        p.expression("1 = a"), // TODO use correlated predicate, it requires support for correlated subqueries in plan matchers
                                                        p.values(ImmutableList.of(p.symbol("a")), TWO_ROWS)))))))
                .matches(
                        project(
                                filter(
                                        ensureScalarSubquery(),
                                        markDistinct(
                                                "is_distinct",
                                                ImmutableList.of("corr", "unique"),
                                                lateral(
                                                        ImmutableList.of("corr"),
                                                        assignUniqueId(
                                                                "unique",
                                                                values("corr")),
                                                        project(
                                                                ImmutableMap.of("a3", expression("a2 + 1")),
                                                                project(
                                                                        ImmutableMap.of("a2", expression("a * 2")),
                                                                        filter(
                                                                                "1 = a",
                                                                                values("a")))))))));
    }

    @Test
    public void rewritesScalarSubquery()
    {
        tester().assertThat(rule)
                .on(p -> p.lateral(
                        ImmutableList.of(p.symbol("corr")),
                        p.values(p.symbol("corr")),
                        p.enforceSingleRow(
                                p.filter(
                                        p.expression("1 = a"), // TODO use correlated predicate, it requires support for correlated subqueries in plan matchers
                                        p.values(ImmutableList.of(p.symbol("a")), ONE_ROW)))))
                .matches(
                        lateral(
                                ImmutableList.of("corr"),
                                values("corr"),
                                filter(
                                        "1 = a",
                                        values("a"))));
    }

    private Expression ensureScalarSubquery()
    {
        return new SimpleCaseExpression(
                new SymbolReference("is_distinct"),
                ImmutableList.of(new WhenClause(TRUE_LITERAL, TRUE_LITERAL)),
                Optional.of(new Cast(
                        new FunctionCallBuilder(tester().getMetadata())
                                .setName(QualifiedName.of("fail"))
                                .addArgument(INTEGER, new LongLiteral(Long.toString(StandardErrorCode.SUBQUERY_MULTIPLE_ROWS.ordinal())))
                                .addArgument(VARCHAR, new StringLiteral("Scalar sub-query has returned multiple rows"))
                                .build(),
                        StandardTypes.BOOLEAN)));
    }
}
