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
package io.prestosql.operator;

import com.google.common.util.concurrent.ListenableFuture;
import io.hetu.core.transport.execution.buffer.PagesSerdeFactory;
import io.hetu.core.transport.execution.buffer.SerializedPage;
import io.prestosql.metadata.Split;
import io.prestosql.snapshot.MultiInputRestorable;
import io.prestosql.snapshot.MultiInputSnapshotState;
import io.prestosql.spi.Page;
import io.prestosql.spi.connector.CatalogName;
import io.prestosql.spi.connector.UpdatablePageSource;
import io.prestosql.spi.plan.PlanNodeId;
import io.prestosql.spi.snapshot.BlockEncodingSerdeProvider;
import io.prestosql.spi.snapshot.RestorableConfig;
import io.prestosql.split.RemoteSplit;

import java.io.Closeable;
import java.net.URI;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

@RestorableConfig(uncapturedFields = {"snapshotState", "sourceId", "exchangeClient"})
public class ExchangeOperator
        implements SourceOperator, MultiInputRestorable, Closeable
{
    public static final CatalogName REMOTE_CONNECTOR_ID = new CatalogName("$remote");

    public static class ExchangeOperatorFactory
            implements SourceOperatorFactory
    {
        private final int operatorId;
        private final PlanNodeId sourceId;
        private final ExchangeClientSupplier exchangeClientSupplier;
        private ExchangeClient exchangeClient;
        private boolean closed;

        public ExchangeOperatorFactory(
                int operatorId,
                PlanNodeId sourceId,
                ExchangeClientSupplier exchangeClientSupplier,
                PagesSerdeFactory serdeFactory)
        {
            this.operatorId = operatorId;
            this.sourceId = sourceId;
            this.exchangeClientSupplier = exchangeClientSupplier;
        }

        @Override
        public PlanNodeId getSourceId()
        {
            return sourceId;
        }

        @Override
        public SourceOperator createOperator(DriverContext driverContext)
        {
            checkState(!closed, "Factory is already closed");
            OperatorContext operatorContext = driverContext.addOperatorContext(operatorId, sourceId, ExchangeOperator.class.getSimpleName());
            if (exchangeClient == null) {
                exchangeClient = exchangeClientSupplier.get(driverContext.getPipelineContext().localSystemMemoryContext());
            }

            return new ExchangeOperator(
                    operatorContext,
                    sourceId,
                    exchangeClient);
        }

        @Override
        public void noMoreOperators()
        {
            closed = true;
        }
    }

    private final OperatorContext operatorContext;
    private final MultiInputSnapshotState snapshotState;
    private final PlanNodeId sourceId;
    private final ExchangeClient exchangeClient;

    public ExchangeOperator(
            OperatorContext operatorContext,
            PlanNodeId sourceId,
            ExchangeClient exchangeClient)
    {
        this.operatorContext = requireNonNull(operatorContext, "operatorContext is null");
        this.sourceId = requireNonNull(sourceId, "sourceId is null");
        this.exchangeClient = requireNonNull(exchangeClient, "exchangeClient is null");
        this.snapshotState = operatorContext.isSnapshotEnabled()
                ? MultiInputSnapshotState.forOperator(this, operatorContext)
                : null;

        operatorContext.setInfoSupplier(exchangeClient::getStatus);
    }

    @Override
    public PlanNodeId getSourceId()
    {
        return sourceId;
    }

    @Override
    public Supplier<Optional<UpdatablePageSource>> addSplit(Split split)
    {
        requireNonNull(split, "split is null");
        checkArgument(split.getCatalogName().equals(REMOTE_CONNECTOR_ID), "split is not a remote split");

        URI location = ((RemoteSplit) split.getConnectorSplit()).getLocation();
        exchangeClient.addLocation(location);

        return Optional::empty;
    }

    @Override
    public void noMoreSplits()
    {
        exchangeClient.noMoreLocations();
    }

    @Override
    public OperatorContext getOperatorContext()
    {
        return operatorContext;
    }

    @Override
    public void finish()
    {
        close();
    }

    @Override
    public boolean isFinished()
    {
        return exchangeClient.isFinished();
    }

    @Override
    public ListenableFuture<?> isBlocked()
    {
        ListenableFuture<?> blocked = exchangeClient.isBlocked();
        if (blocked.isDone()) {
            return NOT_BLOCKED;
        }
        return blocked;
    }

    @Override
    public Page getOutput()
    {
        SerializedPage page = exchangeClient.pollPage();
        if (page == null) {
            return null;
        }

        operatorContext.recordNetworkInput(page.getSizeInBytes(), page.getPositionCount());

        Page deserializedPage = operatorContext.getDriverContext().getSerde().deserialize(page);
        operatorContext.recordProcessedInput(deserializedPage.getSizeInBytes(), page.getPositionCount());

        return deserializedPage;
    }

    @Override
    public Page pollMarker()
    {
        return null;
    }

    @Override
    public void close()
    {
        exchangeClient.close();
    }

    @Override
    public Optional<Set<String>> getInputChannels()
    {
        return Optional.empty();
    }

    @Override
    public Object capture(BlockEncodingSerdeProvider serdeProvider)
    {
        return operatorContext.capture(serdeProvider);
    }

    @Override
    public void restore(Object state, BlockEncodingSerdeProvider serdeProvider)
    {
        operatorContext.restore(state, serdeProvider);
    }
}
