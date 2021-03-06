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

package io.prestosql.type.setdigest;

import io.airlift.slice.Slices;
import io.prestosql.array.ObjectBigArray;
import io.prestosql.spi.function.AccumulatorStateFactory;
import io.prestosql.spi.function.GroupedAccumulatorState;
import io.prestosql.spi.snapshot.BlockEncodingSerdeProvider;
import io.prestosql.spi.snapshot.Restorable;
import org.openjdk.jol.info.ClassLayout;

import java.io.Serializable;
import java.util.function.Function;

public class SetDigestStateFactory
        implements AccumulatorStateFactory<SetDigestState>
{
    private static final int SIZE_OF_SINGLE = ClassLayout.parseClass(SingleSetDigestState.class).instanceSize();
    private static final int SIZE_OF_GROUPED = ClassLayout.parseClass(GroupedSetDigestState.class).instanceSize();

    @Override
    public SetDigestState createSingleState()
    {
        return new SingleSetDigestState();
    }

    @Override
    public Class<? extends SetDigestState> getSingleStateClass()
    {
        return SingleSetDigestState.class;
    }

    @Override
    public SetDigestState createGroupedState()
    {
        return new GroupedSetDigestState();
    }

    @Override
    public Class<? extends SetDigestState> getGroupedStateClass()
    {
        return GroupedSetDigestState.class;
    }

    public static class GroupedSetDigestState
            implements GroupedAccumulatorState, SetDigestState, Restorable
    {
        private final ObjectBigArray<SetDigest> digests = new ObjectBigArray<>();
        private long groupId;
        private long size;

        @Override
        public void setGroupId(long groupId)
        {
            this.groupId = groupId;
        }

        @Override
        public void ensureCapacity(long size)
        {
            digests.ensureCapacity(size);
        }

        @Override
        public SetDigest getDigest()
        {
            return digests.get(groupId);
        }

        @Override
        public void setDigest(SetDigest value)
        {
            if (getDigest() != null) {
                size -= getDigest().estimatedInMemorySize();
            }
            size += value.estimatedInMemorySize();
            digests.set(groupId, value);
        }

        @Override
        public long getEstimatedSize()
        {
            return SIZE_OF_GROUPED + size + digests.sizeOf();
        }

        @Override
        public Object capture(BlockEncodingSerdeProvider serdeProvider)
        {
            GroupedSetDigestStateState myState = new GroupedSetDigestStateState();
            myState.size = size;
            myState.groupId = groupId;
            Function<Object, Object> digestsCapture = content -> ((SetDigest) content).serialize().getBytes();
            myState.digests = digests.capture(digestsCapture);
            return myState;
        }

        @Override
        public void restore(Object state, BlockEncodingSerdeProvider serdeProvider)
        {
            GroupedSetDigestStateState myState = (GroupedSetDigestStateState) state;
            this.size = myState.size;
            this.groupId = myState.groupId;
            Function<Object, Object> digestsRestore = content -> SetDigest.newInstance(Slices.wrappedBuffer((byte[]) content));
            this.digests.restore(digestsRestore, myState.digests);
        }

        private static class GroupedSetDigestStateState
                implements Serializable
        {
            private Object digests;
            private long groupId;
            private long size;
        }
    }

    public static class SingleSetDigestState
            implements SetDigestState, Restorable
    {
        private SetDigest digest;

        @Override
        public SetDigest getDigest()
        {
            return digest;
        }

        @Override
        public void setDigest(SetDigest value)
        {
            this.digest = value;
        }

        @Override
        public long getEstimatedSize()
        {
            if (digest == null) {
                return SIZE_OF_SINGLE;
            }
            return SIZE_OF_SINGLE + digest.estimatedInMemorySize();
        }

        @Override
        public Object capture(BlockEncodingSerdeProvider serdeProvider)
        {
            if (this.digest != null) {
                return digest.serialize().getBytes();
            }
            return null;
        }

        @Override
        public void restore(Object state, BlockEncodingSerdeProvider serdeProvider)
        {
            if (state != null) {
                this.digest = SetDigest.newInstance(Slices.wrappedBuffer((byte[]) state));
            }
            else {
                this.digest = null;
            }
        }
    }
}
