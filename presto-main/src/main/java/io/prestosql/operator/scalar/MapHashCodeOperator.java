package io.prestosql.operator.scalar;
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

import com.google.common.collect.ImmutableList;
import io.prestosql.metadata.BoundVariables;
import io.prestosql.metadata.FunctionAndTypeManager;
import io.prestosql.metadata.SqlOperator;
import io.prestosql.spi.annotation.UsedByGeneratedCode;
import io.prestosql.spi.block.Block;
import io.prestosql.spi.function.BuiltInScalarFunctionImplementation;
import io.prestosql.spi.type.StandardTypes;
import io.prestosql.spi.type.Type;
import io.prestosql.sql.analyzer.TypeSignatureProvider;

import java.lang.invoke.MethodHandle;

import static io.prestosql.spi.function.BuiltInScalarFunctionImplementation.ArgumentProperty.valueTypeArgumentProperty;
import static io.prestosql.spi.function.BuiltInScalarFunctionImplementation.NullConvention.RETURN_NULL_ON_NULL;
import static io.prestosql.spi.function.OperatorType.HASH_CODE;
import static io.prestosql.spi.function.Signature.comparableTypeParameter;
import static io.prestosql.spi.type.TypeSignature.parseTypeSignature;
import static io.prestosql.spi.util.Reflection.methodHandle;
import static io.prestosql.type.TypeUtils.hashPosition;

public class MapHashCodeOperator
        extends SqlOperator
{
    public static final MapHashCodeOperator MAP_HASH_CODE = new MapHashCodeOperator();
    private static final MethodHandle METHOD_HANDLE = methodHandle(MapHashCodeOperator.class, "hash", MethodHandle.class, MethodHandle.class, Type.class, Type.class, Block.class);

    private MapHashCodeOperator()
    {
        super(HASH_CODE,
                ImmutableList.of(comparableTypeParameter("K"), comparableTypeParameter("V")),
                ImmutableList.of(),
                parseTypeSignature(StandardTypes.BIGINT),
                ImmutableList.of(parseTypeSignature("map(K,V)")));
    }

    @Override
    public BuiltInScalarFunctionImplementation specialize(BoundVariables boundVariables, int arity, FunctionAndTypeManager functionAndTypeManager)
    {
        Type keyType = boundVariables.getTypeVariable("K");
        Type valueType = boundVariables.getTypeVariable("V");

        MethodHandle keyHashCodeFunction = functionAndTypeManager.getBuiltInScalarFunctionImplementation(functionAndTypeManager.resolveOperatorFunctionHandle(HASH_CODE, TypeSignatureProvider.fromTypes(keyType))).getMethodHandle();
        MethodHandle valueHashCodeFunction = functionAndTypeManager.getBuiltInScalarFunctionImplementation(functionAndTypeManager.resolveOperatorFunctionHandle(HASH_CODE, TypeSignatureProvider.fromTypes(valueType))).getMethodHandle();

        MethodHandle method = METHOD_HANDLE.bindTo(keyHashCodeFunction).bindTo(valueHashCodeFunction).bindTo(keyType).bindTo(valueType);
        return new BuiltInScalarFunctionImplementation(
                false,
                ImmutableList.of(valueTypeArgumentProperty(RETURN_NULL_ON_NULL)),
                method);
    }

    @UsedByGeneratedCode
    public static long hash(MethodHandle keyHashCodeFunction, MethodHandle valueHashCodeFunction, Type keyType, Type valueType, Block block)
    {
        long result = 0;
        for (int position = 0; position < block.getPositionCount(); position += 2) {
            result += hashPosition(keyHashCodeFunction, keyType, block, position) ^ hashPosition(valueHashCodeFunction, valueType, block, position + 1);
        }
        return result;
    }
}
