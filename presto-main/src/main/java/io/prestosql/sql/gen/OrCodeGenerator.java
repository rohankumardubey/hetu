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

import com.google.common.base.Preconditions;
import io.airlift.bytecode.BytecodeBlock;
import io.airlift.bytecode.BytecodeNode;
import io.airlift.bytecode.Variable;
import io.airlift.bytecode.control.IfStatement;
import io.airlift.bytecode.instruction.LabelNode;
import io.prestosql.spi.function.FunctionHandle;
import io.prestosql.spi.relation.RowExpression;
import io.prestosql.spi.type.Type;

import java.util.List;

import static io.airlift.bytecode.expression.BytecodeExpressions.constantFalse;

public class OrCodeGenerator
        implements BytecodeGenerator
{
    @Override
    public BytecodeNode generateExpression(FunctionHandle functionHandle, BytecodeGeneratorContext generator, Type returnType, List<RowExpression> arguments)
    {
        Preconditions.checkArgument(arguments.size() == 2);

        Variable wasNull = generator.wasNull();
        BytecodeBlock block = new BytecodeBlock()
                .comment("OR")
                .setDescription("OR");

        BytecodeNode left = generator.generate(arguments.get(0));
        BytecodeNode right = generator.generate(arguments.get(1));

        block.append(left);

        IfStatement ifLeftIsNull = new IfStatement("if left wasNull...")
                .condition(wasNull);

        LabelNode end = new LabelNode("end");
        ifLeftIsNull.ifTrue(new BytecodeBlock()
                .comment("clear the null flag, pop left value off stack, and push left null flag on the stack (true)")
                .append(wasNull.set(constantFalse()))
                .pop(arguments.get(0).getType().getJavaType()) // discard left value
                .push(true));

        LabelNode leftIsFalse = new LabelNode("leftIsFalse");
        ifLeftIsNull.ifFalse(new BytecodeBlock()
                .comment("if left is true, push true, and goto end")
                .ifFalseGoto(leftIsFalse)
                .push(true)
                .gotoLabel(end)
                .comment("left was false; push left null flag on the stack (false)")
                .visitLabel(leftIsFalse)
                .push(false));

        block.append(ifLeftIsNull);

        // At this point we know the left expression was either NULL or FALSE.  The stack contains a single boolean
        // value for this expression which indicates if the left value was NULL.

        // eval right!
        block.append(right);

        IfStatement ifRightIsNull = new IfStatement("if right wasNull...")
                .condition(wasNull);

        // this leaves a single boolean on the stack which is ignored since the value in NULL
        ifRightIsNull.ifTrue()
                .comment("right was null, pop the right value off the stack; wasNull flag remains set to TRUE")
                .pop(arguments.get(1).getType().getJavaType());

        LabelNode rightIsTrue = new LabelNode("rightIsTrue");
        ifRightIsNull.ifFalse()
                .comment("if right is true, pop left null flag off stack, push true and goto end")
                .ifFalseGoto(rightIsTrue)
                .pop(boolean.class)
                .push(true)
                .gotoLabel(end)
                .comment("right was false; store left null flag (on stack) in wasNull variable, and push false")
                .visitLabel(rightIsTrue)
                .putVariable(wasNull)
                .push(false);

        block.append(ifRightIsNull)
                .visitLabel(end);

        return block;
    }
}
