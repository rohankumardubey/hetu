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
package io.prestosql.testing;

import io.prestosql.operator.DriverContext;
import io.prestosql.operator.Operator;
import io.prestosql.operator.OperatorContext;
import io.prestosql.operator.OperatorFactory;
import io.prestosql.operator.OutputFactory;
import io.prestosql.operator.SinkOperator;
import io.prestosql.operator.TaskContext;
import io.prestosql.spi.Page;
import io.prestosql.spi.plan.PlanNodeId;
import io.prestosql.spi.snapshot.RestorableConfig;
import io.prestosql.spi.type.Type;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

@RestorableConfig(unsupported = true)
public class PageConsumerOperator
        implements SinkOperator
{
    public static class PageConsumerOutputFactory
            implements OutputFactory
    {
        private final Function<List<Type>, Consumer<Page>> pageConsumerFactory;

        public PageConsumerOutputFactory(Function<List<Type>, Consumer<Page>> pageConsumerFactory)
        {
            this.pageConsumerFactory = requireNonNull(pageConsumerFactory, "pageConsumerFactory is null");
        }

        @Override
        public OperatorFactory createOutputOperator(
                int operatorId,
                PlanNodeId planNodeId,
                List<Type> types,
                Function<Page, Page> pagePreprocessor,
                TaskContext taskContext)
        {
            return new PageConsumerOperatorFactory(operatorId, planNodeId, pageConsumerFactory.apply(types), pagePreprocessor);
        }
    }

    public static class PageConsumerOperatorFactory
            implements OperatorFactory
    {
        private final int operatorId;
        private final PlanNodeId planNodeId;
        private final Consumer<Page> pageConsumer;
        private final Function<Page, Page> pagePreprocessor;
        private boolean closed;

        public PageConsumerOperatorFactory(int operatorId, PlanNodeId planNodeId, Consumer<Page> pageConsumer, Function<Page, Page> pagePreprocessor)
        {
            this.operatorId = operatorId;
            this.planNodeId = requireNonNull(planNodeId, "planNodeId is null");
            this.pageConsumer = requireNonNull(pageConsumer, "pageConsumer is null");
            this.pagePreprocessor = requireNonNull(pagePreprocessor, "pagePreprocessor is null");
        }

        @Override
        public Operator createOperator(DriverContext driverContext)
        {
            checkState(!closed, "Factory is already closed");
            OperatorContext oprContext = driverContext.addOperatorContext(operatorId, planNodeId, PageConsumerOperator.class.getSimpleName());
            return new PageConsumerOperator(oprContext, pageConsumer, pagePreprocessor);
        }

        @Override
        public void noMoreOperators()
        {
            closed = true;
        }

        @Override
        public OperatorFactory duplicate()
        {
            return new PageConsumerOperatorFactory(operatorId, planNodeId, pageConsumer, pagePreprocessor);
        }
    }

    private final OperatorContext operatorContext;
    private final Consumer<Page> pageConsumer;
    private final Function<Page, Page> pagePreprocessor;
    private boolean finished;
    private boolean closed;

    public PageConsumerOperator(OperatorContext operatorContext, Consumer<Page> pageConsumer, Function<Page, Page> pagePreprocessor)
    {
        this.operatorContext = requireNonNull(operatorContext, "operatorContext is null");
        this.pageConsumer = requireNonNull(pageConsumer, "pageConsumer is null");
        this.pagePreprocessor = requireNonNull(pagePreprocessor, "pagePreprocessor is null");
    }

    public boolean isClosed()
    {
        return closed;
    }

    @Override
    public OperatorContext getOperatorContext()
    {
        return operatorContext;
    }

    @Override
    public void finish()
    {
        finished = true;
    }

    @Override
    public boolean isFinished()
    {
        return finished;
    }

    @Override
    public boolean needsInput()
    {
        return !finished;
    }

    @Override
    public void addInput(Page page)
    {
        requireNonNull(page, "page is null");
        checkState(!finished, "operator finished");

        Page tmpPage = pagePreprocessor.apply(page);
        pageConsumer.accept(tmpPage);
        operatorContext.recordOutput(tmpPage.getSizeInBytes(), tmpPage.getPositionCount());
    }

    @Override
    public void close()
    {
        closed = true;
    }
}
