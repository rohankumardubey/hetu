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
package io.prestosql.operator.scalar;

import com.fasterxml.jackson.core.JsonGenerator;
import com.google.common.collect.ImmutableList;
import io.airlift.slice.DynamicSliceOutput;
import io.airlift.slice.Slice;
import io.airlift.slice.SliceOutput;
import io.prestosql.metadata.BoundVariables;
import io.prestosql.metadata.FunctionAndTypeManager;
import io.prestosql.metadata.SqlOperator;
import io.prestosql.spi.annotation.UsedByGeneratedCode;
import io.prestosql.spi.block.Block;
import io.prestosql.spi.function.BuiltInScalarFunctionImplementation;
import io.prestosql.spi.function.OperatorType;
import io.prestosql.spi.type.StandardTypes;
import io.prestosql.spi.type.Type;
import io.prestosql.util.JsonUtil.JsonGeneratorWriter;

import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.util.ArrayList;
import java.util.List;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Throwables.throwIfUnchecked;
import static io.prestosql.operator.scalar.JsonOperators.JSON_FACTORY;
import static io.prestosql.spi.StandardErrorCode.INVALID_CAST_ARGUMENT;
import static io.prestosql.spi.function.BuiltInScalarFunctionImplementation.ArgumentProperty.valueTypeArgumentProperty;
import static io.prestosql.spi.function.BuiltInScalarFunctionImplementation.NullConvention.RETURN_NULL_ON_NULL;
import static io.prestosql.spi.function.Signature.withVariadicBound;
import static io.prestosql.spi.type.TypeSignature.parseTypeSignature;
import static io.prestosql.spi.util.Reflection.methodHandle;
import static io.prestosql.util.Failures.checkCondition;
import static io.prestosql.util.JsonUtil.JsonGeneratorWriter.createJsonGeneratorWriter;
import static io.prestosql.util.JsonUtil.canCastToJson;
import static io.prestosql.util.JsonUtil.createJsonGenerator;

public class RowToJsonCast
        extends SqlOperator
{
    public static final RowToJsonCast ROW_TO_JSON = new RowToJsonCast();
    private static final MethodHandle METHOD_HANDLE = methodHandle(RowToJsonCast.class, "toJson", List.class, Block.class);

    private RowToJsonCast()
    {
        super(OperatorType.CAST,
                ImmutableList.of(withVariadicBound("T", "row")),
                ImmutableList.of(),
                parseTypeSignature(StandardTypes.JSON),
                ImmutableList.of(parseTypeSignature("T")));
    }

    @Override
    public BuiltInScalarFunctionImplementation specialize(BoundVariables boundVariables, int arity, FunctionAndTypeManager functionAndTypeManager)
    {
        checkArgument(arity == 1, "Expected arity to be 1");
        Type type = boundVariables.getTypeVariable("T");
        checkCondition(canCastToJson(type), INVALID_CAST_ARGUMENT, "Cannot cast %s to JSON", type);

        List<Type> fieldTypes = type.getTypeParameters();
        List<JsonGeneratorWriter> fieldWriters = new ArrayList<>(fieldTypes.size());
        for (int i = 0; i < fieldTypes.size(); i++) {
            fieldWriters.add(createJsonGeneratorWriter(fieldTypes.get(i)));
        }
        MethodHandle methodHandle = METHOD_HANDLE.bindTo(fieldWriters);

        return new BuiltInScalarFunctionImplementation(
                false,
                ImmutableList.of(valueTypeArgumentProperty(RETURN_NULL_ON_NULL)),
                methodHandle);
    }

    @UsedByGeneratedCode
    public static Slice toJson(List<JsonGeneratorWriter> fieldWriters, Block block)
    {
        try {
            SliceOutput output = new DynamicSliceOutput(40);
            try (JsonGenerator jsonGenerator = createJsonGenerator(JSON_FACTORY, output)) {
                jsonGenerator.writeStartArray();
                for (int i = 0; i < block.getPositionCount(); i++) {
                    fieldWriters.get(i).writeJsonValue(jsonGenerator, block, i);
                }
                jsonGenerator.writeEndArray();
            }
            return output.slice();
        }
        catch (IOException e) {
            throwIfUnchecked(e);
            throw new RuntimeException(e);
        }
    }
}
