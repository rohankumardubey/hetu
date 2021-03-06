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
package io.hetu.core.plugin.carbondata;

import org.apache.carbondata.core.metadata.datatype.DataType;
import org.apache.carbondata.core.scan.result.vector.CarbonColumnVector;
import org.apache.carbondata.core.scan.result.vector.CarbonDictionary;
import org.apache.carbondata.core.scan.result.vector.impl.CarbonColumnVectorImpl;
import org.apache.carbondata.core.scan.result.vector.impl.directread.SequentialFill;
import org.apache.carbondata.core.scan.scanner.LazyPageLoader;

import java.math.BigDecimal;
import java.util.BitSet;

/**
 * Fills the vector directly with out considering any deleted rows.
 */
class ColumnarVectorWrapperDirect
        implements CarbonColumnVector, SequentialFill
{
    /**
     * It is adapter class of complete ColumnarBatch.
     */
    protected CarbonColumnVectorImpl columnVector;

    /**
     * It is current block file datatype used for alter table scenarios when datatype changes.
     */
    private DataType blockDataType;

    private CarbonColumnVector dictionaryVector;

    private BitSet nullBitSet;

    ColumnarVectorWrapperDirect(CarbonColumnVectorImpl columnVector)
    {
        this.columnVector = columnVector;
        this.dictionaryVector = columnVector.getDictionaryVector();
        this.nullBitSet = new BitSet();
    }

    @Override
    public void setNullBits(BitSet nullBits)
    {
        this.nullBitSet = nullBits;
    }

    @Override
    public void putBoolean(int rowId, boolean value)
    {
        if (nullBitSet.get(rowId)) {
            columnVector.putNull(rowId);
        }
        else {
            columnVector.putBoolean(rowId, value);
        }
    }

    @Override
    public void putFloat(int rowId, float value)
    {
        if (nullBitSet.get(rowId)) {
            columnVector.putNull(rowId);
        }
        else {
            columnVector.putFloat(rowId, value);
        }
    }

    @Override
    public void putShort(int rowId, short value)
    {
        if (nullBitSet.get(rowId)) {
            columnVector.putNull(rowId);
        }
        else {
            columnVector.putShort(rowId, value);
        }
    }

    @Override
    public void putShorts(int rowId, int count, short value)
    {
        columnVector.putShorts(rowId, count, value);
    }

    @Override
    public void putInt(int rowId, int value)
    {
        if (nullBitSet.get(rowId)) {
            columnVector.putNull(rowId);
        }
        else {
            columnVector.putInt(rowId, value);
        }
    }

    @Override
    public void putInts(int rowId, int count, int value)
    {
        columnVector.putInts(rowId, count, value);
    }

    @Override
    public void putLong(int rowId, long value)
    {
        if (nullBitSet.get(rowId)) {
            columnVector.putNull(rowId);
        }
        else {
            columnVector.putLong(rowId, value);
        }
    }

    @Override
    public void putLongs(int rowId, int count, long value)
    {
        columnVector.putLongs(rowId, count, value);
    }

    @Override
    public void putDecimal(int rowId, BigDecimal value, int precision)
    {
        if (nullBitSet.get(rowId)) {
            columnVector.putNull(rowId);
        }
        else {
            columnVector.putDecimal(rowId, value, precision);
        }
    }

    @Override
    public void putDecimals(int rowId, int count, BigDecimal value, int precision)
    {
        int inputRowId = rowId;
        for (int i = 0; i < count; i++) {
            if (nullBitSet.get(inputRowId)) {
                columnVector.putNull(inputRowId);
            }
            else {
                columnVector.putDecimal(inputRowId, value, precision);
            }
            inputRowId++;
        }
    }

    @Override
    public void putDouble(int rowId, double value)
    {
        if (nullBitSet.get(rowId)) {
            columnVector.putNull(rowId);
        }
        else {
            columnVector.putDouble(rowId, value);
        }
    }

    @Override
    public void putDoubles(int rowId, int count, double value)
    {
        columnVector.putDoubles(rowId, count, value);
    }

    @Override
    public void putByteArray(int rowId, byte[] value)
    {
        if (nullBitSet.get(rowId)) {
            columnVector.putNull(rowId);
        }
        else {
            columnVector.putByteArray(rowId, value);
        }
    }

    @Override
    public void putByteArray(int rowId, int count, byte[] value)
    {
        int inputRowId = rowId;
        for (int i = 0; i < count; i++) {
            columnVector.putByteArray(inputRowId++, value);
        }
    }

    @Override
    public void putByteArray(int rowId, int offset, int length, byte[] value)
    {
        if (nullBitSet.get(rowId)) {
            columnVector.putNull(rowId);
        }
        else {
            columnVector.putByteArray(rowId, offset, length, value);
        }
    }

    @Override
    public void putNull(int rowId)
    {
        columnVector.putNull(rowId);
    }

    @Override
    public void putNulls(int rowId, int count)
    {
        columnVector.putNulls(rowId, count);
    }

    @Override
    public void putNotNull(int rowId)
    {
        columnVector.putNotNull(rowId);
    }

    @Override
    public void putNotNull(int rowId, int count)
    {
    }

    @Override
    public boolean isNull(int rowId)
    {
        return columnVector.isNullAt(rowId);
    }

    @Override
    public void putObject(int rowId, Object obj)
    {
        throw new UnsupportedOperationException(
                "Not supported this opeartion from " + this.getClass().getName());
    }

    @Override
    public Object getData(int rowId)
    {
        throw new UnsupportedOperationException(
                "Not supported this opeartion from " + this.getClass().getName());
    }

    @Override
    public void reset()
    {
        if (null != dictionaryVector) {
            dictionaryVector.reset();
        }
    }

    @Override
    public DataType getType()
    {
        return columnVector.getType();
    }

    @Override
    public DataType getBlockDataType()
    {
        return blockDataType;
    }

    @Override
    public void setBlockDataType(DataType blockDataType)
    {
        this.blockDataType = blockDataType;
    }

    @Override
    public void setDictionary(CarbonDictionary dictionary)
    {
        columnVector.setDictionary(dictionary);
    }

    @Override
    public boolean hasDictionary()
    {
        return columnVector.hasDictionary();
    }

    @Override
    public CarbonColumnVector getDictionaryVector()
    {
        return dictionaryVector;
    }

    @Override
    public void putByte(int rowId, byte value)
    {
        columnVector.putByte(rowId, value);
    }

    @Override
    public void setFilteredRowsExist(boolean filteredRowsExist)
    {
        // Leave it, as it does not need to do anything here.
    }

    @Override
    public void putFloats(int rowId, int count, float[] src, int srcIndex)
    {
        int inputRowId = rowId;
        for (int i = 0; i < count; i++) {
            if (nullBitSet.get(inputRowId)) {
                columnVector.putNull(inputRowId);
            }
            else {
                columnVector.putFloat(inputRowId, src[i]);
            }
            inputRowId++;
        }
    }

    @Override
    public void putShorts(int rowId, int count, short[] src, int srcIndex)
    {
        int inputRowId = rowId;
        for (int i = 0; i < count; i++) {
            if (nullBitSet.get(inputRowId)) {
                columnVector.putNull(inputRowId);
            }
            else {
                columnVector.putShort(inputRowId, src[i]);
            }
            inputRowId++;
        }
    }

    @Override
    public void putInts(int rowId, int count, int[] src, int srcIndex)
    {
        int inputRowId = rowId;
        for (int i = 0; i < count; i++) {
            if (nullBitSet.get(inputRowId)) {
                columnVector.putNull(inputRowId);
            }
            else {
                columnVector.putInt(inputRowId, src[i]);
            }
            inputRowId++;
        }
    }

    @Override
    public void putLongs(int rowId, int count, long[] src, int srcIndex)
    {
        int inputRowId = rowId;
        for (int i = 0; i < count; i++) {
            if (nullBitSet.get(inputRowId)) {
                columnVector.putNull(inputRowId);
            }
            else {
                columnVector.putLong(inputRowId, src[i]);
            }
            inputRowId++;
        }
    }

    @Override
    public void putDoubles(int rowId, int count, double[] src, int srcIndex)
    {
        int inputRowId = rowId;
        for (int i = 0; i < count; i++) {
            if (nullBitSet.get(inputRowId)) {
                columnVector.putNull(inputRowId);
            }
            else {
                columnVector.putDouble(inputRowId, src[i]);
            }
            inputRowId++;
        }
    }

    @Override
    public void putBytes(int rowId, int count, byte[] src, int srcIndex)
    {
        int inputRowId = rowId;
        for (int i = 0; i < count; i++) {
            if (nullBitSet.get(inputRowId)) {
                columnVector.putNull(inputRowId);
            }
            else {
                columnVector.putByte(inputRowId, src[i]);
            }
            inputRowId++;
        }
    }

    @Override
    public void setLazyPage(LazyPageLoader lazyPage)
    {
        columnVector.setLazyPage(lazyPage);
    }

    @Override
    public void putArray(int rowId, int offset, int length)
    {
        columnVector.putArray(rowId, offset, length);
    }

    @Override
    public void putAllByteArray(byte[] data, int offset, int length)
    {
        columnVector.putAllByteArray(data, offset, length);
    }
}
