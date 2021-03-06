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

import com.google.common.collect.ImmutableList;
import io.prestosql.operator.aggregation.TypedSet;
import io.prestosql.spi.PageBuilder;
import io.prestosql.spi.block.Block;
import io.prestosql.spi.block.BlockBuilder;
import io.prestosql.spi.function.Description;
import io.prestosql.spi.function.OperatorDependency;
import io.prestosql.spi.function.ScalarFunction;
import io.prestosql.spi.function.SqlType;
import io.prestosql.spi.function.TypeParameter;
import io.prestosql.spi.type.Type;

import java.lang.invoke.MethodHandle;
import java.util.Optional;

import static io.prestosql.spi.function.OperatorType.IS_DISTINCT_FROM;
import static io.prestosql.spi.type.StandardTypes.BOOLEAN;

@ScalarFunction("array_intersect")
@Description("Intersects elements of the two given arrays")
public final class ArrayIntersectFunction
{
    private final PageBuilder pageBuilder;

    @TypeParameter("E")
    public ArrayIntersectFunction(@TypeParameter("E") Type elementType)
    {
        pageBuilder = new PageBuilder(ImmutableList.of(elementType));
    }

    @TypeParameter("E")
    @SqlType("array(E)")
    public Block intersect(
            @TypeParameter("E") Type type,
            @OperatorDependency(operator = IS_DISTINCT_FROM, returnType = BOOLEAN, argumentTypes = {"E", "E"}) MethodHandle elementIsDistinctFrom,
            @SqlType("array(E)") Block leftArray,
            @SqlType("array(E)") Block rightArray)
    {
        Block inputLeftArray = leftArray;
        Block inputRightArray = rightArray;
        if (inputLeftArray.getPositionCount() < inputRightArray.getPositionCount()) {
            Block tempArray = inputLeftArray;
            inputLeftArray = inputRightArray;
            inputRightArray = tempArray;
        }

        int leftPositionCount = inputLeftArray.getPositionCount();
        int rightPositionCount = inputRightArray.getPositionCount();

        if (rightPositionCount == 0) {
            return inputRightArray;
        }

        if (pageBuilder.isFull()) {
            pageBuilder.reset();
        }

        TypedSet rightTypedSet = new TypedSet(type, elementIsDistinctFrom, rightPositionCount, "array_intersect");
        for (int i = 0; i < rightPositionCount; i++) {
            rightTypedSet.add(inputRightArray, i);
        }

        BlockBuilder blockBuilder = pageBuilder.getBlockBuilder(0);

        // The intersected set can have at most rightPositionCount elements
        TypedSet intersectTypedSet = new TypedSet(type, Optional.of(elementIsDistinctFrom), blockBuilder, rightPositionCount, "array_intersect");
        for (int i = 0; i < leftPositionCount; i++) {
            if (rightTypedSet.contains(inputLeftArray, i)) {
                intersectTypedSet.add(inputLeftArray, i);
            }
        }

        pageBuilder.declarePositions(intersectTypedSet.size());

        return blockBuilder.getRegion(blockBuilder.getPositionCount() - intersectTypedSet.size(), intersectTypedSet.size());
    }
}
