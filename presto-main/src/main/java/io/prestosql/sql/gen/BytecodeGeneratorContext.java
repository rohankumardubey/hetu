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
package io.prestosql.sql.gen;

import io.airlift.bytecode.BytecodeNode;
import io.airlift.bytecode.FieldDefinition;
import io.airlift.bytecode.Scope;
import io.airlift.bytecode.Variable;
import io.prestosql.metadata.FunctionAndTypeManager;
import io.prestosql.spi.function.BuiltInScalarFunctionImplementation;
import io.prestosql.spi.function.ScalarFunctionImplementation;
import io.prestosql.spi.relation.RowExpression;

import java.util.List;
import java.util.Optional;

import static io.prestosql.sql.gen.BytecodeUtils.generateInvocation;
import static java.util.Objects.requireNonNull;

public class BytecodeGeneratorContext
{
    private final RowExpressionCompiler rowExpressionCompiler;
    private final Scope scope;
    private final CallSiteBinder callSiteBinder;
    private final CachedInstanceBinder cachedInstanceBinder;
    private final FunctionAndTypeManager manager;
    private final Variable wasNull;

    public BytecodeGeneratorContext(
            RowExpressionCompiler rowExpressionCompiler,
            Scope scope,
            CallSiteBinder callSiteBinder,
            CachedInstanceBinder cachedInstanceBinder,
            FunctionAndTypeManager manager)
    {
        requireNonNull(rowExpressionCompiler, "bytecodeGenerator is null");
        requireNonNull(cachedInstanceBinder, "cachedInstanceBinder is null");
        requireNonNull(scope, "scope is null");
        requireNonNull(callSiteBinder, "callSiteBinder is null");
        requireNonNull(manager, "manager is null");

        this.rowExpressionCompiler = rowExpressionCompiler;
        this.scope = scope;
        this.callSiteBinder = callSiteBinder;
        this.cachedInstanceBinder = cachedInstanceBinder;
        this.manager = manager;
        this.wasNull = scope.getVariable("wasNull");
    }

    public Scope getScope()
    {
        return scope;
    }

    public CallSiteBinder getCallSiteBinder()
    {
        return callSiteBinder;
    }

    public BytecodeNode generate(RowExpression expression)
    {
        return generate(expression, Optional.empty());
    }

    public BytecodeNode generate(RowExpression expression, Optional<Class<?>> lambdaInterface)
    {
        return rowExpressionCompiler.compile(expression, scope, lambdaInterface);
    }

    public FunctionAndTypeManager getFunctionManager()
    {
        return manager;
    }

    /**
     * Generates a function call with null handling, automatic binding of session parameter, etc.
     */
    public BytecodeNode generateCall(String name, ScalarFunctionImplementation function, List<BytecodeNode> arguments)
    {
        Optional<BytecodeNode> instance = Optional.empty();
        if (function instanceof BuiltInScalarFunctionImplementation && ((BuiltInScalarFunctionImplementation) function).getInstanceFactory().isPresent()) {
            FieldDefinition field = cachedInstanceBinder.getCachedInstance(((BuiltInScalarFunctionImplementation) function).getInstanceFactory().get());
            instance = Optional.of(scope.getThis().getField(field));
        }
        return generateInvocation(scope, name, function, instance, arguments, callSiteBinder);
    }

    public Variable wasNull()
    {
        return wasNull;
    }
}
