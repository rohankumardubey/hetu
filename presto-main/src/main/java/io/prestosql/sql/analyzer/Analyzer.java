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
package io.prestosql.sql.analyzer;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import io.prestosql.Session;
import io.prestosql.cube.CubeManager;
import io.prestosql.execution.warnings.WarningCollector;
import io.prestosql.heuristicindex.HeuristicIndexerManager;
import io.prestosql.metadata.FunctionAndTypeManager;
import io.prestosql.metadata.Metadata;
import io.prestosql.security.AccessControl;
import io.prestosql.spi.function.FunctionHandle;
import io.prestosql.sql.parser.SqlParser;
import io.prestosql.sql.rewrite.StatementRewrite;
import io.prestosql.sql.tree.Expression;
import io.prestosql.sql.tree.FunctionCall;
import io.prestosql.sql.tree.GroupingOperation;
import io.prestosql.sql.tree.NodeRef;
import io.prestosql.sql.tree.Statement;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static io.prestosql.sql.analyzer.ExpressionTreeUtils.extractAggregateFunctions;
import static io.prestosql.sql.analyzer.ExpressionTreeUtils.extractExpressions;
import static io.prestosql.sql.analyzer.ExpressionTreeUtils.extractExternalFunctions;
import static io.prestosql.sql.analyzer.ExpressionTreeUtils.extractWindowFunctions;
import static io.prestosql.sql.analyzer.SemanticErrorCode.CANNOT_HAVE_AGGREGATIONS_WINDOWS_OR_GROUPING;
import static io.prestosql.sql.analyzer.SemanticErrorCode.NOT_SUPPORTED;
import static java.util.Objects.requireNonNull;

public class Analyzer
{
    private final Metadata metadata;
    private final SqlParser sqlParser;
    private final AccessControl accessControl;
    private final Session session;
    private final Optional<QueryExplainer> queryExplainer;
    private final List<Expression> parameters;
    private final WarningCollector warningCollector;
    private HeuristicIndexerManager heuristicIndexerManager;
    private final CubeManager cubeManager;

    public Analyzer(Session session,
            Metadata metadata,
            SqlParser sqlParser,
            AccessControl accessControl,
            Optional<QueryExplainer> queryExplainer,
            List<Expression> parameters,
            WarningCollector warningCollector,
            CubeManager cubeManager)
    {
        this(session, metadata, sqlParser, accessControl, queryExplainer, parameters, warningCollector, HeuristicIndexerManager.getNoOpHeuristicIndexerManager(), cubeManager);
    }

    public Analyzer(Session session,
            Metadata metadata,
            SqlParser sqlParser,
            AccessControl accessControl,
            Optional<QueryExplainer> queryExplainer,
            List<Expression> parameters,
            WarningCollector warningCollector,
            HeuristicIndexerManager heuristicIndexerManager,
            CubeManager cubeManager)
    {
        this.session = requireNonNull(session, "session is null");
        this.metadata = requireNonNull(metadata, "metadata is null");
        this.sqlParser = requireNonNull(sqlParser, "sqlParser is null");
        this.accessControl = requireNonNull(accessControl, "accessControl is null");
        this.queryExplainer = requireNonNull(queryExplainer, "query explainer is null");
        this.parameters = parameters;
        this.warningCollector = requireNonNull(warningCollector, "warningCollector is null");
        this.cubeManager = cubeManager;
        this.heuristicIndexerManager = requireNonNull(heuristicIndexerManager, "heuristicIndexerManager is null");
    }

    public Analysis analyze(Statement statement)
    {
        return analyze(statement, false);
    }

    public Analysis analyze(Statement statement, boolean isDescribe)
    {
        Statement rewrittenStatement = StatementRewrite.rewrite(session, metadata, cubeManager, sqlParser, queryExplainer, statement, parameters, accessControl, warningCollector, heuristicIndexerManager);
        Analysis analysis = new Analysis(rewrittenStatement, parameters, isDescribe);
        analysis.setOriginalStatement(statement);

        StatementAnalyzer analyzer = new StatementAnalyzer(analysis, metadata, sqlParser, accessControl, session, warningCollector, heuristicIndexerManager, cubeManager);
        analyzer.analyze(rewrittenStatement, Optional.empty());

        // check column access permissions for each table
        analysis.getTableColumnReferences().forEach((accessControlInfo, tableColumnReferences) ->
                tableColumnReferences.forEach((tableName, columns) ->
                        accessControlInfo.getAccessControl().checkCanSelectFromColumns(
                                session.getRequiredTransactionId(),
                                accessControlInfo.getIdentity(),
                                tableName,
                                columns)));
        return analysis;
    }

    static void verifyNoAggregateWindowOrGroupingFunctions(Metadata metadata, Expression predicate, String clause)
    {
        List<FunctionCall> aggregates = extractAggregateFunctions(ImmutableList.of(predicate), metadata);

        List<FunctionCall> windowExpressions = extractWindowFunctions(ImmutableList.of(predicate));

        List<GroupingOperation> groupingOperations = extractExpressions(ImmutableList.of(predicate), GroupingOperation.class);

        List<Expression> found = ImmutableList.copyOf(Iterables.concat(
                aggregates,
                windowExpressions,
                groupingOperations));

        if (!found.isEmpty()) {
            throw new SemanticException(CANNOT_HAVE_AGGREGATIONS_WINDOWS_OR_GROUPING, predicate, "%s cannot contain aggregations, window functions or grouping operations: %s", clause, found);
        }
    }

    static void verifyNoAggregateWindowOrGroupingFunctions(Map<NodeRef<FunctionCall>, FunctionHandle> functionHandles, FunctionAndTypeManager functionAndTypeManager, Expression predicate, String clause)
    {
        List<FunctionCall> aggregates = extractAggregateFunctions(functionHandles, ImmutableList.of(predicate), functionAndTypeManager);

        List<FunctionCall> windowExpressions = extractWindowFunctions(ImmutableList.of(predicate));

        List<GroupingOperation> groupingOperations = extractExpressions(ImmutableList.of(predicate), GroupingOperation.class);

        List<Expression> found = ImmutableList.copyOf(Iterables.concat(
                aggregates,
                windowExpressions,
                groupingOperations));

        if (!found.isEmpty()) {
            throw new SemanticException(CANNOT_HAVE_AGGREGATIONS_WINDOWS_OR_GROUPING, predicate, "%s cannot contain aggregations, window functions or grouping operations: %s", clause, found);
        }
    }

    static void verifyNoExternalFunctions(Map<NodeRef<FunctionCall>, FunctionHandle> functionHandles, FunctionAndTypeManager functionAndTypeManager, Expression predicate, String clause)
    {
        List<FunctionCall> externalFunctions = extractExternalFunctions(functionHandles, ImmutableList.of(predicate), functionAndTypeManager);
        if (!externalFunctions.isEmpty()) {
            throw new SemanticException(NOT_SUPPORTED, predicate, "External functions in %s is not supported: %s", clause, externalFunctions);
        }
    }
}
