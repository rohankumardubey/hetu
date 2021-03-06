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
package io.prestosql.sql.planner.assertions;

import com.google.common.collect.Iterables;
import io.prestosql.Session;
import io.prestosql.metadata.Metadata;
import io.prestosql.spi.connector.ColumnHandle;
import io.prestosql.spi.metadata.TableHandle;
import io.prestosql.spi.plan.OrderingScheme;
import io.prestosql.spi.plan.Symbol;
import io.prestosql.spi.predicate.Domain;
import io.prestosql.spi.predicate.TupleDomain;
import io.prestosql.sql.planner.SymbolUtils;
import io.prestosql.sql.planner.assertions.PlanMatchPattern.Ordering;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

import static com.google.common.collect.ImmutableList.toImmutableList;

final class Util
{
    private Util() {}

    static boolean domainsMatch(TupleDomain<Predicate<ColumnHandle>> expected, TupleDomain<ColumnHandle> actual)
    {
        Optional<Map<Predicate<ColumnHandle>, Domain>> expectedDomains = expected.getDomains();
        Optional<Map<ColumnHandle, Domain>> actualDomains = actual.getDomains();

        if (expectedDomains.isPresent() != actualDomains.isPresent()) {
            return false;
        }

        if (expectedDomains.isPresent()) {
            if (expectedDomains.get().size() != actualDomains.get().size()) {
                return false;
            }

            for (Map.Entry<Predicate<ColumnHandle>, Domain> entry : expectedDomains.get().entrySet()) {
                // There should be exactly one column matching the expected column matcher
                ColumnHandle actualColumn = Iterables.getOnlyElement(actualDomains.get().keySet().stream()
                        .filter(x -> entry.getKey().test(x))
                        .collect(toImmutableList()));

                if (!actualDomains.get().get(actualColumn).contains(entry.getValue())) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * @param expectedDomains if empty, the actualConstraint's domains must also be empty.
     */
    static boolean domainsMatch(
            Optional<Map<String, Domain>> expectedDomains,
            TupleDomain<ColumnHandle> actualConstraint,
            TableHandle tableHandle,
            Session session,
            Metadata metadata)
    {
        Optional<Map<ColumnHandle, Domain>> actualDomains = actualConstraint.getDomains();

        if (expectedDomains.isPresent() != actualDomains.isPresent()) {
            return false;
        }

        if (!expectedDomains.isPresent()) {
            return true;
        }

        Map<String, ColumnHandle> columnHandles = metadata.getColumnHandles(session, tableHandle);
        for (Map.Entry<String, Domain> expectedColumnConstraint : expectedDomains.get().entrySet()) {
            if (!columnHandles.containsKey(expectedColumnConstraint.getKey())) {
                return false;
            }
            ColumnHandle columnHandle = columnHandles.get(expectedColumnConstraint.getKey());
            if (!actualDomains.get().containsKey(columnHandle)) {
                return false;
            }
            if (!expectedColumnConstraint.getValue().contains(actualDomains.get().get(columnHandle))) {
                return false;
            }
        }

        return true;
    }

    static boolean orderingSchemeMatches(List<Ordering> expectedOrderBy, OrderingScheme orderingScheme, SymbolAliases symbolAliases)
    {
        if (expectedOrderBy.size() != orderingScheme.getOrderBy().size()) {
            return false;
        }

        for (int i = 0; i < expectedOrderBy.size(); ++i) {
            Ordering ordering = expectedOrderBy.get(i);
            Symbol symbol = SymbolUtils.from(symbolAliases.get(ordering.getField()));
            if (!symbol.equals(orderingScheme.getOrderBy().get(i))) {
                return false;
            }
            if (!ordering.getSortOrder().equals(orderingScheme.getOrdering(symbol))) {
                return false;
            }
        }

        return true;
    }
}
