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

import io.prestosql.metadata.FunctionAndTypeManager;
import io.prestosql.spi.function.FunctionMetadata;
import io.prestosql.sql.tree.DefaultExpressionTraversalVisitor;
import io.prestosql.sql.tree.FunctionCall;

import static io.prestosql.spi.function.FunctionKind.WINDOW;
import static io.prestosql.sql.analyzer.SemanticErrorCode.WINDOW_REQUIRES_OVER;
import static java.util.Objects.requireNonNull;

class WindowFunctionValidator
        extends DefaultExpressionTraversalVisitor<Void, Analysis>
{
    private final FunctionAndTypeManager functionAndTypeManager;

    public WindowFunctionValidator(FunctionAndTypeManager functionAndTypeManager)
    {
        this.functionAndTypeManager = requireNonNull(functionAndTypeManager, "functionManager is null");
    }

    @Override
    protected Void visitFunctionCall(FunctionCall functionCall, Analysis analysis)
    {
        requireNonNull(analysis, "analysis is null");

        FunctionMetadata functionMetadata = functionAndTypeManager.getFunctionMetadata(analysis.getFunctionHandle(functionCall));
        if (functionMetadata != null && functionMetadata.getFunctionKind() == WINDOW && !functionCall.getWindow().isPresent()) {
            throw new SemanticException(WINDOW_REQUIRES_OVER, functionCall, "Window function %s requires an OVER clause", functionMetadata.getName());
        }
        return super.visitFunctionCall(functionCall, analysis);
    }
}
