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
package io.prestosql.metadata;

import com.google.common.collect.ImmutableSet;
import io.prestosql.operator.scalar.annotations.ScalarFromAnnotationsParser;
import io.prestosql.operator.window.WindowAnnotationsParser;
import io.prestosql.spi.connector.CatalogSchemaName;
import io.prestosql.spi.function.AggregationFunction;
import io.prestosql.spi.function.ExternalFunctionHub;
import io.prestosql.spi.function.ExternalFunctionInfo;
import io.prestosql.spi.function.RoutineCharacteristics;
import io.prestosql.spi.function.ScalarFunction;
import io.prestosql.spi.function.ScalarOperator;
import io.prestosql.spi.function.SqlFunction;
import io.prestosql.spi.function.SqlInvokedFunction;
import io.prestosql.spi.function.WindowFunction;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static com.google.common.collect.ImmutableList.toImmutableList;

public final class FunctionExtractor
{
    private FunctionExtractor() {}

    public static List<? extends SqlFunction> extractFunctions(Collection<Class<?>> classes)
    {
        return classes.stream()
                .map(FunctionExtractor::extractFunctions)
                .flatMap(Collection::stream)
                .collect(toImmutableList());
    }

    public static List<? extends SqlFunction> extractFunctions(Class<?> clazz)
    {
        if (WindowFunction.class.isAssignableFrom(clazz)) {
            @SuppressWarnings("unchecked")
            Class<? extends WindowFunction> windowClazz = (Class<? extends WindowFunction>) clazz;
            return WindowAnnotationsParser.parseFunctionDefinition(windowClazz);
        }

        if (clazz.isAnnotationPresent(AggregationFunction.class)) {
            return SqlAggregationFunction.createFunctionsByAnnotations(clazz);
        }

        if (clazz.isAnnotationPresent(ScalarFunction.class) ||
                clazz.isAnnotationPresent(ScalarOperator.class)) {
            return ScalarFromAnnotationsParser.parseFunctionDefinition(clazz);
        }

        return ScalarFromAnnotationsParser.parseFunctionDefinitions(clazz);
    }

    public static Set<SqlInvokedFunction> extractExternalFunctions(ExternalFunctionHub externalFunctionHub)
    {
        RoutineCharacteristics.Language language = externalFunctionHub.getExternalFunctionLanguage();
        Optional<CatalogSchemaName> catalogSchemaName = externalFunctionHub.getExternalFunctionCatalogSchemaName();
        if (!catalogSchemaName.isPresent()) {
            return Collections.emptySet();
        }
        ImmutableSet.Builder<SqlInvokedFunction> builder = new ImmutableSet.Builder<>();
        for (ExternalFunctionInfo externalFunctionInfo : externalFunctionHub.getExternalFunctions()) {
            Optional<SqlInvokedFunction> sqlInvokedFunctionOptional = ExternalFunctionsParser.parseExternalFunction(externalFunctionInfo, catalogSchemaName.get(), language);
            sqlInvokedFunctionOptional.ifPresent(builder::add);
        }
        return builder.build();
    }
}
