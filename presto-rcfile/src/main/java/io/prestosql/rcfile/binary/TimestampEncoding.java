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
package io.prestosql.rcfile.binary;

import io.airlift.slice.Slice;
import io.airlift.slice.SliceOutput;
import io.prestosql.rcfile.ColumnData;
import io.prestosql.rcfile.EncodeOutput;
import io.prestosql.spi.block.Block;
import io.prestosql.spi.block.BlockBuilder;
import io.prestosql.spi.type.Type;
import org.joda.time.DateTimeZone;

import static io.airlift.slice.SizeOf.SIZE_OF_INT;
import static io.prestosql.rcfile.RcFileDecoderUtils.decodeVIntSize;
import static io.prestosql.rcfile.RcFileDecoderUtils.isNegativeVInt;
import static io.prestosql.rcfile.RcFileDecoderUtils.readVInt;
import static io.prestosql.rcfile.RcFileDecoderUtils.writeVInt;
import static java.lang.Math.floorDiv;
import static java.lang.Math.floorMod;
import static java.lang.Math.toIntExact;
import static java.util.Objects.requireNonNull;

public class TimestampEncoding
        implements BinaryColumnEncoding
{
    private final Type type;
    private final DateTimeZone timeZone;

    public TimestampEncoding(Type type, DateTimeZone timeZone)
    {
        this.type = requireNonNull(type, "type is null");
        this.timeZone = requireNonNull(timeZone, "timeZone is null");
    }

    @Override
    public void encodeColumn(Block block, SliceOutput output, EncodeOutput encodeOutput)
    {
        for (int position = 0; position < block.getPositionCount(); position++) {
            if (!block.isNull(position)) {
                writeTimestamp(output, type.getLong(block, position));
            }
            encodeOutput.closeEntry();
        }
    }

    @Override
    public void encodeValueInto(Block block, int position, SliceOutput output)
    {
        writeTimestamp(output, type.getLong(block, position));
    }

    @Override
    public Block decodeColumn(ColumnData columnData)
    {
        int size = columnData.rowCount();
        BlockBuilder builder = type.createBlockBuilder(null, size);

        Slice slice = columnData.getSlice();
        for (int i = 0; i < size; i++) {
            int length = columnData.getLength(i);
            if (length != 0) {
                int offset = columnData.getOffset(i);
                long millis = getTimestamp(slice, offset);
                type.writeLong(builder, millis);
            }
            else {
                builder.appendNull();
            }
        }
        return builder.build();
    }

    @Override
    public int getValueOffset(Slice slice, int offset)
    {
        return 0;
    }

    @Override
    public int getValueLength(Slice slice, int offset)
    {
        int length = 4;
        if (hasNanosVInt(slice.getByte(offset))) {
            int nanosVintLength = decodeVIntSize(slice, offset + 4);
            length += nanosVintLength;

            // is there extra data for "seconds"
            if (isNegativeVInt(slice, offset + 4)) {
                length += decodeVIntSize(slice, offset + 4 + nanosVintLength);
            }
        }
        return length;
    }

    @Override
    public void decodeValueInto(BlockBuilder builder, Slice slice, int offset, int length)
    {
        long millis = getTimestamp(slice, offset);
        type.writeLong(builder, millis);
    }

    private static boolean hasNanosVInt(byte b)
    {
        return (b >> 7) != 0;
    }

    private long getTimestamp(Slice slice, int offset)
    {
        int newOffset = offset;
        // read seconds (low 32 bits)
        int lowest31BitsOfSecondsAndFlag = Integer.reverseBytes(slice.getInt(newOffset));
        long seconds = lowest31BitsOfSecondsAndFlag & 0x7FFF_FFFF;
        newOffset += SIZE_OF_INT;

        int nanos = 0;
        if (lowest31BitsOfSecondsAndFlag < 0) {
            // read nanos
            // this is an inline version of readVint so it can be stitched together
            // the the code to read the seconds high bits below
            byte nanosFirstByte = slice.getByte(newOffset);
            int nanosLength = decodeVIntSize(nanosFirstByte);
            nanos = (int) readVInt(slice, newOffset, nanosLength);
            nanos = decodeNanos(nanos);

            // read seconds (high 32 bits)
            if (isNegativeVInt(nanosFirstByte)) {
                // We compose the seconds field from two parts. The lowest 31 bits come from the first four
                // bytes. The higher-order bits come from the second VInt that follows the nanos field.
                long highBits = readVInt(slice, newOffset + nanosLength);
                seconds |= (highBits << 31);
            }
        }

        long millis = (seconds * 1000) + (nanos / 1_000_000);

        return timeZone.convertUTCToLocal(millis);
    }

    @SuppressWarnings("NonReproducibleMathCall")
    private static int decodeNanos(int nanos)
    {
        int nan = nanos;
        if (nan < 0) {
            // This means there is a second VInt present that specifies additional bits of the timestamp.
            // The reversed nanoseconds value is still encoded in this VInt.
            nan = -nan - 1;
        }
        int nanosDigits = (int) Math.floor(Math.log10(nan)) + 1;

        // Reverse the nanos digits (base 10)
        int temp = 0;
        while (nan != 0) {
            temp *= 10;
            temp += nan % 10;
            nan /= 10;
        }
        nan = temp;

        if (nanosDigits < 9) {
            nan *= Math.pow(10, 9 - nanosDigits);
        }
        return nan;
    }

    private void writeTimestamp(SliceOutput output, long millis)
    {
        long ms = millis;
        ms = timeZone.convertLocalToUTC(ms, false);
        long seconds = floorDiv(ms, 1000);
        int nanos = toIntExact(floorMod(ms, 1000) * 1_000_000);
        writeTimestamp(seconds, nanos, output);
    }

    private static void writeTimestamp(long seconds, int nanos, SliceOutput output)
    {
        // <seconds-low-32><nanos>[<seconds-high-32>]
        //     seconds-low-32 is vint encoded
        //     nanos is reversed
        //     seconds-high-32 is vint encoded
        //     seconds-low-32 and nanos have the top bit set when second-high is present

        boolean hasSecondsHigh32 = seconds < 0 || seconds > Integer.MAX_VALUE;
        int nanosReversed = reverseDecimal(nanos);

        int secondsLow32 = (int) seconds;
        if (nanosReversed == 0 && !hasSecondsHigh32) {
            secondsLow32 &= 0X7FFF_FFFF;
        }
        else {
            secondsLow32 |= 0x8000_0000;
        }
        output.writeInt(Integer.reverseBytes(secondsLow32));

        if (hasSecondsHigh32 || nanosReversed != 0) {
            // The sign of the reversed-nanoseconds field indicates that there is a second VInt present
            int value = hasSecondsHigh32 ? ~nanosReversed : nanosReversed;
            writeVInt(output, value);
        }

        if (hasSecondsHigh32) {
            int secondsHigh32 = (int) (seconds >> 31);
            writeVInt(output, secondsHigh32);
        }
    }

    private static int reverseDecimal(int nanos)
    {
        int nan = nanos;
        int decimal = 0;
        if (nan != 0) {
            int counter = 0;
            while (counter < 9) {
                decimal *= 10;
                decimal += nan % 10;
                nan /= 10;
                counter++;
            }
        }
        return decimal;
    }
}
