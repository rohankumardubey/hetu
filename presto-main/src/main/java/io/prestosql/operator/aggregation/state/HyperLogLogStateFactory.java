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
package io.prestosql.operator.aggregation.state;

import io.airlift.slice.Slices;
import io.airlift.stats.cardinality.HyperLogLog;
import io.prestosql.array.ObjectBigArray;
import io.prestosql.spi.function.AccumulatorStateFactory;
import io.prestosql.spi.snapshot.BlockEncodingSerdeProvider;
import io.prestosql.spi.snapshot.Restorable;
import org.openjdk.jol.info.ClassLayout;

import java.io.Serializable;
import java.util.function.Function;

import static java.util.Objects.requireNonNull;

public class HyperLogLogStateFactory
        implements AccumulatorStateFactory<HyperLogLogState>
{
    @Override
    public HyperLogLogState createSingleState()
    {
        return new SingleHyperLogLogState();
    }

    @Override
    public Class<? extends HyperLogLogState> getSingleStateClass()
    {
        return SingleHyperLogLogState.class;
    }

    @Override
    public HyperLogLogState createGroupedState()
    {
        return new GroupedHyperLogLogState();
    }

    @Override
    public Class<? extends HyperLogLogState> getGroupedStateClass()
    {
        return GroupedHyperLogLogState.class;
    }

    public static class GroupedHyperLogLogState
            extends AbstractGroupedAccumulatorState
            implements HyperLogLogState
    {
        private static final int INSTANCE_SIZE = ClassLayout.parseClass(GroupedHyperLogLogState.class).instanceSize();
        private final ObjectBigArray<HyperLogLog> hlls = new ObjectBigArray<>();
        private long size;

        @Override
        public void ensureCapacity(long size)
        {
            hlls.ensureCapacity(size);
        }

        @Override
        public HyperLogLog getHyperLogLog()
        {
            return hlls.get(getGroupId());
        }

        @Override
        public void setHyperLogLog(HyperLogLog value)
        {
            requireNonNull(value, "value is null");
            hlls.set(getGroupId(), value);
        }

        @Override
        public void addMemoryUsage(int value)
        {
            size += value;
        }

        @Override
        public long getEstimatedSize()
        {
            return INSTANCE_SIZE + size + hlls.sizeOf();
        }

        @Override
        public Object capture(BlockEncodingSerdeProvider serdeProvider)
        {
            GroupedHyperLogLogStateState myState = new GroupedHyperLogLogStateState();
            Function<Object, Object> hllsCapture = content -> ((HyperLogLog) content).serialize().getBytes();
            myState.hlls = hlls.capture(hllsCapture);
            myState.baseState = super.capture(serdeProvider);
            myState.size = size;
            return myState;
        }

        @Override
        public void restore(Object state, BlockEncodingSerdeProvider serdeProvider)
        {
            GroupedHyperLogLogStateState myState = (GroupedHyperLogLogStateState) state;
            Function<Object, Object> hllsRestore = content -> HyperLogLog.newInstance(Slices.wrappedBuffer((byte[]) content));
            this.hlls.restore(hllsRestore, myState.hlls);
            this.size = myState.size;
            super.restore(myState.baseState, serdeProvider);
        }

        private static class GroupedHyperLogLogStateState
                implements Serializable
        {
            private Object hlls;
            private long size;
            private Object baseState;
        }
    }

    public static class SingleHyperLogLogState
            implements HyperLogLogState, Restorable
    {
        private static final int INSTANCE_SIZE = ClassLayout.parseClass(SingleHyperLogLogState.class).instanceSize();
        private HyperLogLog hll;

        @Override
        public HyperLogLog getHyperLogLog()
        {
            return hll;
        }

        @Override
        public void setHyperLogLog(HyperLogLog value)
        {
            hll = value;
        }

        @Override
        public void addMemoryUsage(int value)
        {
            // noop
        }

        @Override
        public long getEstimatedSize()
        {
            long estimatedSize = INSTANCE_SIZE;
            if (hll != null) {
                estimatedSize += hll.estimatedInMemorySize();
            }
            return estimatedSize;
        }

        @Override
        public Object capture(BlockEncodingSerdeProvider serdeProvider)
        {
            if (this.hll != null) {
                return this.hll.serialize().getBytes();
            }
            return null;
        }

        @Override
        public void restore(Object state, BlockEncodingSerdeProvider serdeProvider)
        {
            if (state != null) {
                this.hll = HyperLogLog.newInstance(Slices.wrappedBuffer((byte[]) state));
            }
            else {
                this.hll = null;
            }
        }
    }
}
