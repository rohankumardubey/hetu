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
package io.hetu.core.plugin.carbondata.readers;

import io.prestosql.spi.block.Block;
import io.prestosql.spi.block.BlockBuilder;
import io.prestosql.spi.type.BooleanType;
import io.prestosql.spi.type.Type;
import org.apache.carbondata.core.metadata.datatype.DataType;
import org.apache.carbondata.core.scan.result.vector.impl.CarbonColumnVectorImpl;

public class BooleanStreamReader
        extends CarbonColumnVectorImpl
        implements HetuCoreVectorBlockBuilder
{
    protected int batchSize;

    protected Type type = BooleanType.BOOLEAN;

    protected BlockBuilder builder;

    public BooleanStreamReader(int batchSize, DataType dataType)
    {
        super(batchSize, dataType);
        this.batchSize = batchSize;
        this.builder = type.createBlockBuilder(null, batchSize);
    }

    @Override
    public Block buildBlock()
    {
        return builder.build();
    }

    @Override
    public void setBatchSize(int batchSize)
    {
        this.batchSize = batchSize;
    }

    @Override
    public void putByte(int rowId, byte value)
    {
        type.writeBoolean(builder, value == 1);
    }

    @Override
    public void putBytes(int rowId, int count, byte[] src, int srcIndex)
    {
        int srcIdx = srcIndex;
        for (int i = 0; i < count; i++) {
            type.writeBoolean(builder, src[srcIdx++] == 1);
        }
    }

    @Override
    public void putBoolean(int rowId, boolean value)
    {
        type.writeBoolean(builder, value);
    }

    @Override
    public void putNull(int rowId)
    {
        builder.appendNull();
    }

    @Override
    public void putNulls(int rowId, int count)
    {
        for (int i = 0; i < count; i++) {
            builder.appendNull();
        }
    }

    @Override
    public void reset()
    {
        builder = type.createBlockBuilder(null, batchSize);
    }

    @Override
    public void putObject(int rowId, Object value)
    {
        if (value == null) {
            putNull(rowId);
        }
        else {
            putBoolean(rowId, (boolean) value);
        }
    }
}
