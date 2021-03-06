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
import org.apache.carbondata.core.scan.scanner.LazyPageLoader;

import java.math.BigDecimal;

public class CarbondataColumnVectorWrapper
        implements CarbonColumnVector
{
    private CarbonColumnVectorImpl columnVector;
    private CarbondataColumnVectorWrapper dictionaryVectorWrapper;

    private boolean[] filteredRows;

    private int counter;

    private boolean filteredRowsExist;

    private DataType blockDataType;

    public CarbondataColumnVectorWrapper(CarbonColumnVectorImpl columnVector, boolean[] filteredRows)
    {
        this.columnVector = columnVector;
        this.filteredRows = filteredRows;

        if (columnVector.getDictionaryVector() != null) {
            dictionaryVectorWrapper = new CarbondataColumnVectorWrapper(
                    (CarbonColumnVectorImpl) columnVector.getDictionaryVector(), filteredRows);
        }
    }

    @Override
    public void putBoolean(int rowId, boolean value)
    {
        if (!filteredRows[rowId]) {
            columnVector.putBoolean(counter++, value);
        }
    }

    @Override
    public void putFloat(int rowId, float value)
    {
        if (!filteredRows[rowId]) {
            columnVector.putFloat(counter++, value);
        }
    }

    @Override
    public void putShort(int rowId, short value)
    {
        if (!filteredRows[rowId]) {
            columnVector.putShort(counter++, value);
        }
    }

    @Override
    public void putShorts(int rowId, int count, short value)
    {
        int inputRowId = rowId;
        if (filteredRowsExist) {
            for (int i = 0; i < count; i++) {
                if (!filteredRows[inputRowId]) {
                    columnVector.putShort(counter++, value);
                }
                inputRowId++;
            }
        }
        else {
            columnVector.putShorts(inputRowId, count, value);
        }
    }

    @Override
    public void putInt(int rowId, int value)
    {
        if (!filteredRows[rowId]) {
            columnVector.putInt(counter++, value);
        }
    }

    @Override
    public void putInts(int rowId, int count, int value)
    {
        int inputRowId = rowId;
        if (filteredRowsExist) {
            for (int i = 0; i < count; i++) {
                if (!filteredRows[inputRowId]) {
                    columnVector.putInt(counter++, value);
                }
                inputRowId++;
            }
        }
        else {
            columnVector.putInts(inputRowId, count, value);
        }
    }

    @Override
    public void putLong(int rowId, long value)
    {
        if (!filteredRows[rowId]) {
            columnVector.putLong(counter++, value);
        }
    }

    @Override
    public void putLongs(int rowId, int count, long value)
    {
        int inputRowId = rowId;
        if (filteredRowsExist) {
            for (int i = 0; i < count; i++) {
                if (!filteredRows[inputRowId]) {
                    columnVector.putLong(counter++, value);
                }
                inputRowId++;
            }
        }
        else {
            columnVector.putLongs(inputRowId, count, value);
        }
    }

    @Override
    public void putDecimal(int rowId, BigDecimal value, int precision)
    {
        if (!filteredRows[rowId]) {
            columnVector.putDecimal(counter++, value, precision);
        }
    }

    @Override
    public void putDecimals(int rowId, int count, BigDecimal value, int precision)
    {
        int inputRowId = rowId;
        for (int i = 0; i < count; i++) {
            if (!filteredRows[inputRowId]) {
                columnVector.putDecimal(counter++, value, precision);
            }
            inputRowId++;
        }
    }

    @Override
    public void putDouble(int rowId, double value)
    {
        if (!filteredRows[rowId]) {
            columnVector.putDouble(counter++, value);
        }
    }

    @Override
    public void putDoubles(int rowId, int count, double value)
    {
        int inputRowId = rowId;
        if (filteredRowsExist) {
            for (int i = 0; i < count; i++) {
                if (!filteredRows[inputRowId]) {
                    columnVector.putDouble(counter++, value);
                }
                inputRowId++;
            }
        }
        else {
            columnVector.putDoubles(inputRowId, count, value);
        }
    }

    @Override
    public void putByte(int rowId, byte value)
    {
        if (!filteredRows[rowId]) {
            columnVector.putByte(counter++, value);
        }
    }

    @Override
    public void putByteArray(int rowId, byte[] value)
    {
        if (!filteredRows[rowId]) {
            columnVector.putByteArray(counter++, value);
        }
    }

    @Override
    public void putByteArray(int rowId, int count, byte[] value)
    {
        int inputRowId = rowId;
        for (int i = 0; i < count; i++) {
            if (!filteredRows[inputRowId]) {
                columnVector.putByteArray(counter++, value);
            }
            inputRowId++;
        }
    }

    @Override
    public void putByteArray(int rowId, int offset, int length, byte[] value)
    {
        if (!filteredRows[rowId]) {
            columnVector.putByteArray(counter++, offset, length, value);
        }
    }

    @Override
    public void putNull(int rowId)
    {
        if (!filteredRows[rowId]) {
            columnVector.putNull(counter++);
        }
    }

    @Override
    public void putNulls(int rowId, int count)
    {
        int inputRowId = rowId;
        if (filteredRowsExist) {
            for (int i = 0; i < count; i++) {
                if (!filteredRows[inputRowId]) {
                    columnVector.putNull(counter++);
                }
                inputRowId++;
            }
        }
        else {
            columnVector.putNulls(inputRowId, count);
        }
    }

    @Override
    public void putNotNull(int rowId)
    {
        //TODO handle null types
    }

    @Override
    public void putNotNull(int rowId, int count)
    {
        //TODO handle null types
    }

    @Override
    public boolean isNull(int rowId)
    {
        return columnVector.isNullAt(rowId);
    }

    @Override
    public void putObject(int rowId, Object obj)
    {
        //TODO handle complex types
    }

    @Override
    public Object getData(int rowId)
    {
        //TODO handle complex types
        return null;
    }

    @Override
    public void reset()
    {
        counter = 0;
        filteredRowsExist = false;
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
    public void setFilteredRowsExist(boolean filteredRowsExist)
    {
        this.filteredRowsExist = filteredRowsExist;
    }

    @Override
    public void setDictionary(CarbonDictionary dictionary)
    {
        this.columnVector.setDictionary(dictionary);
    }

    @Override
    public boolean hasDictionary()
    {
        return this.columnVector.hasDictionary();
    }

    @Override
    public CarbonColumnVector getDictionaryVector()
    {
        return this.dictionaryVectorWrapper;
    }

    @Override
    public void putFloats(int rowId, int count, float[] src, int srcIndex)
    {
        int inputRowId = rowId;
        for (int i = srcIndex; i < count; i++) {
            if (!filteredRows[inputRowId]) {
                columnVector.putFloat(counter++, src[i]);
            }
            inputRowId++;
        }
    }

    @Override
    public void putShorts(int rowId, int count, short[] src, int srcIndex)
    {
        int inputRowId = rowId;
        for (int i = srcIndex; i < count; i++) {
            if (!filteredRows[inputRowId]) {
                columnVector.putShort(counter++, src[i]);
            }
            inputRowId++;
        }
    }

    @Override
    public void putInts(int rowId, int count, int[] src, int srcIndex)
    {
        int inputRowId = rowId;
        for (int i = srcIndex; i < count; i++) {
            if (!filteredRows[inputRowId]) {
                columnVector.putInt(counter++, src[i]);
            }
            inputRowId++;
        }
    }

    @Override
    public void putLongs(int rowId, int count, long[] src, int srcIndex)
    {
        int inputRowId = rowId;
        for (int i = srcIndex; i < count; i++) {
            if (!filteredRows[inputRowId]) {
                columnVector.putLong(counter++, src[i]);
            }
            inputRowId++;
        }
    }

    @Override
    public void putDoubles(int rowId, int count, double[] src, int srcIndex)
    {
        int inputRowId = rowId;
        for (int i = srcIndex; i < count; i++) {
            if (!filteredRows[inputRowId]) {
                columnVector.putDouble(counter++, src[i]);
            }
            inputRowId++;
        }
    }

    @Override
    public void putBytes(int rowId, int count, byte[] src, int srcIndex)
    {
        int inputRowId = rowId;
        for (int i = srcIndex; i < count; i++) {
            if (!filteredRows[inputRowId]) {
                columnVector.putByte(counter++, src[i]);
            }
            inputRowId++;
        }
    }

    @Override
    public void setLazyPage(LazyPageLoader lazyPage)
    {
        lazyPage.loadPage();
    }

    @Override
    public void putArray(int rowId, int offset, int length)
    {
        if (!filteredRows[rowId]) {
            columnVector.putArray(counter++, offset, length);
        }
    }

    @Override
    public void putAllByteArray(byte[] data, int offset, int length)
    {
        columnVector.putAllByteArray(data, offset, length);
    }
}
