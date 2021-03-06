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

import com.google.common.collect.ImmutableList;
import io.prestosql.snapshot.SingleInputSnapshotState;
import io.prestosql.spi.Page;
import io.prestosql.spi.PageBuilder;
import io.prestosql.spi.block.Block;
import io.prestosql.spi.block.BlockBuilder;
import io.prestosql.spi.plan.PlanNodeId;
import io.prestosql.spi.snapshot.BlockEncodingSerdeProvider;
import io.prestosql.spi.snapshot.RestorableConfig;
import io.prestosql.spi.statistics.ComputedStatistics;
import io.prestosql.spi.type.Type;
import io.prestosql.sql.planner.plan.StatisticAggregationsDescriptor;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

import static com.google.common.base.Preconditions.checkState;
import static io.prestosql.spi.statistics.TableStatisticType.ROW_COUNT;
import static io.prestosql.spi.type.BigintType.BIGINT;
import static java.util.Objects.requireNonNull;

@RestorableConfig(uncapturedFields = {"statisticsWriter", "descriptor", "state", "snapshotState"})
public class StatisticsWriterOperator
        implements Operator
{
    public static final List<Type> TYPES = ImmutableList.of(BIGINT);

    public static class StatisticsWriterOperatorFactory
            implements OperatorFactory
    {
        private final int operatorId;
        private final PlanNodeId planNodeId;
        private final StatisticsWriter statisticsWriter;
        private final boolean rowCountEnabled;
        private final StatisticAggregationsDescriptor<Integer> descriptor;
        private boolean closed;

        public StatisticsWriterOperatorFactory(int operatorId, PlanNodeId planNodeId, StatisticsWriter statisticsWriter, boolean rowCountEnabled, StatisticAggregationsDescriptor<Integer> descriptor)
        {
            this.operatorId = operatorId;
            this.planNodeId = requireNonNull(planNodeId, "planNodeId is null");
            this.statisticsWriter = requireNonNull(statisticsWriter, "statisticsWriter is null");
            this.rowCountEnabled = rowCountEnabled;
            this.descriptor = requireNonNull(descriptor, "descriptor is null");
        }

        @Override
        public Operator createOperator(DriverContext driverContext)
        {
            checkState(!closed, "Factory is already closed");
            OperatorContext context = driverContext.addOperatorContext(operatorId, planNodeId, StatisticsWriterOperator.class.getSimpleName());
            return new StatisticsWriterOperator(context, statisticsWriter, descriptor, rowCountEnabled);
        }

        @Override
        public void noMoreOperators()
        {
            closed = true;
        }

        @Override
        public OperatorFactory duplicate()
        {
            return new StatisticsWriterOperatorFactory(operatorId, planNodeId, statisticsWriter, rowCountEnabled, descriptor);
        }
    }

    private enum State
    {
        RUNNING, FINISHING, FINISHED
    }

    private final OperatorContext operatorContext;
    private final StatisticsWriter statisticsWriter;
    private final StatisticAggregationsDescriptor<Integer> descriptor;
    private final boolean rowCountEnabled;

    private State state = State.RUNNING;
    private ImmutableList.Builder<ComputedStatistics> computedStatisticsBuilder = ImmutableList.builder();

    private final SingleInputSnapshotState snapshotState;

    public StatisticsWriterOperator(OperatorContext operatorContext, StatisticsWriter statisticsWriter, StatisticAggregationsDescriptor<Integer> descriptor, boolean rowCountEnabled)
    {
        this.operatorContext = requireNonNull(operatorContext, "operatorContext is null");
        this.statisticsWriter = requireNonNull(statisticsWriter, "statisticsWriter is null");
        this.descriptor = requireNonNull(descriptor, "descriptor is null");
        this.rowCountEnabled = rowCountEnabled;
        this.snapshotState = operatorContext.isSnapshotEnabled() ? SingleInputSnapshotState.forOperator(this, operatorContext) : null;
    }

    @Override
    public OperatorContext getOperatorContext()
    {
        return operatorContext;
    }

    @Override
    public boolean needsInput()
    {
        return state == State.RUNNING;
    }

    @Override
    public void addInput(Page page)
    {
        requireNonNull(page, "page is null");
        checkState(state == State.RUNNING, "Operator is %s", state);

        if (snapshotState != null) {
            if (snapshotState.processPage(page)) {
                return;
            }
        }

        for (int position = 0; position < page.getPositionCount(); position++) {
            computedStatisticsBuilder.add(getComputedStatistics(page, position));
        }
    }

    @Override
    public Page getOutput()
    {
        if (snapshotState != null) {
            Page marker = snapshotState.nextMarker();
            if (marker != null) {
                return marker;
            }
        }

        if (state != State.FINISHING) {
            return null;
        }
        state = State.FINISHED;

        Collection<ComputedStatistics> computedStatistics = computedStatisticsBuilder.build();
        statisticsWriter.writeStatistics(computedStatistics);

        // output page will only be constructed once,
        // so a new PageBuilder is constructed (instead of using PageBuilder.reset)
        PageBuilder page = new PageBuilder(1, TYPES);
        page.declarePosition();
        BlockBuilder rowsBuilder = page.getBlockBuilder(0);
        if (rowCountEnabled) {
            BIGINT.writeLong(rowsBuilder, getRowCount(computedStatistics));
        }
        else {
            rowsBuilder.appendNull();
        }

        return page.build();
    }

    @Override
    public Page pollMarker()
    {
        return snapshotState.nextMarker();
    }

    @Override
    public void finish()
    {
        if (state == State.RUNNING) {
            state = State.FINISHING;
        }
    }

    @Override
    public boolean isFinished()
    {
        if (snapshotState != null && snapshotState.hasMarker()) {
            // Snapshot: there are pending markers. Need to send them out before finishing this operator.
            return false;
        }

        return state == State.FINISHED;
    }

    private ComputedStatistics getComputedStatistics(Page page, int position)
    {
        ImmutableList.Builder<String> groupingColumns = ImmutableList.builder();
        ImmutableList.Builder<Block> groupingValues = ImmutableList.builder();
        descriptor.getGrouping().forEach((column, channel) -> {
            groupingColumns.add(column);
            groupingValues.add(page.getBlock(channel).getSingleValueBlock(position));
        });

        ComputedStatistics.Builder statistics = ComputedStatistics.builder(groupingColumns.build(), groupingValues.build());

        descriptor.getTableStatistics().forEach((type, channel) ->
                statistics.addTableStatistic(type, page.getBlock(channel).getSingleValueBlock(position)));

        descriptor.getColumnStatistics().forEach((metadata, channel) -> statistics.addColumnStatistic(metadata, page.getBlock(channel).getSingleValueBlock(position)));

        return statistics.build();
    }

    private static long getRowCount(Collection<ComputedStatistics> computedStatistics)
    {
        return computedStatistics.stream()
                .map(statistics -> statistics.getTableStatistics().get(ROW_COUNT))
                .filter(Objects::nonNull)
                .mapToLong(block -> BIGINT.getLong(block, 0))
                .reduce((first, second) -> first + second)
                .orElse(0L);
    }

    public interface StatisticsWriter
    {
        void writeStatistics(Collection<ComputedStatistics> computedStatistics);
    }

    @Override
    public void close()
    {
        if (snapshotState != null) {
            snapshotState.close();
        }
    }

    @Override
    public Object capture(BlockEncodingSerdeProvider serdeProvider)
    {
        StatisticsWriterOperatorState myState = new StatisticsWriterOperatorState();
        myState.operatorContext = operatorContext.capture(serdeProvider);
        List<ComputedStatistics> computedStatistics = computedStatisticsBuilder.build();
        myState.computedStatisticsBuilder = new Object[computedStatistics.size()];
        for (int i = 0; i < computedStatistics.size(); i++) {
            myState.computedStatisticsBuilder[i] = computedStatistics.get(i).capture(serdeProvider);
        }
        return myState;
    }

    @Override
    public void restore(Object state, BlockEncodingSerdeProvider serdeProvider)
    {
        StatisticsWriterOperatorState myState = (StatisticsWriterOperatorState) state;
        this.operatorContext.restore(myState.operatorContext, serdeProvider);
        List<ComputedStatistics> computedStatistics = new ArrayList<>();
        for (int i = 0; i < myState.computedStatisticsBuilder.length; i++) {
            computedStatistics.add(ComputedStatistics.restoreComputedStatistics(myState.computedStatisticsBuilder[i], serdeProvider));
        }
        this.computedStatisticsBuilder = ImmutableList.builder();
        this.computedStatisticsBuilder.addAll(computedStatistics);
    }

    private static class StatisticsWriterOperatorState
            implements Serializable
    {
        private Object operatorContext;
        private Object[] computedStatisticsBuilder;
    }
}
