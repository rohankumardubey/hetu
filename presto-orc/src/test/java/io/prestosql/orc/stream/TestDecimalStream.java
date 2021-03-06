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
package io.prestosql.orc.stream;

import com.google.common.collect.ImmutableList;
import io.airlift.slice.DynamicSliceOutput;
import io.airlift.slice.Slice;
import io.airlift.slice.Slices;
import io.prestosql.orc.OrcCorruptionException;
import io.prestosql.orc.OrcDataSourceId;
import org.testng.annotations.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;

import static io.prestosql.memory.context.AggregatedMemoryContext.newSimpleAggregatedMemoryContext;
import static io.prestosql.spi.type.Decimals.MAX_DECIMAL_UNSCALED_VALUE;
import static io.prestosql.spi.type.Decimals.MIN_DECIMAL_UNSCALED_VALUE;
import static io.prestosql.spi.type.UnscaledDecimal128Arithmetic.unscaledDecimalToBigInteger;
import static java.math.BigInteger.ONE;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertThrows;

public class TestDecimalStream
{
    private static final BigInteger BIG_INTEGER_127_BIT_SET;

    static {
        BigInteger b = BigInteger.ZERO;
        for (int i = 0; i < 127; ++i) {
            b = b.setBit(i);
        }
        BIG_INTEGER_127_BIT_SET = b;
    }

    @Test
    public void testShortDecimals()
            throws IOException
    {
        assertReadsShortValue(0L);
        assertReadsShortValue(1L);
        assertReadsShortValue(-1L);
        assertReadsShortValue(256L);
        assertReadsShortValue(-256L);
        assertReadsShortValue(Long.MAX_VALUE);
        assertReadsShortValue(Long.MIN_VALUE);
    }

    @Test
    public void testShouldFailWhenShortDecimalDoesNotFit()
    {
        assertShortValueReadFails(BigInteger.valueOf(Long.MAX_VALUE).add(ONE));
    }

    @Test
    public void testShouldFailWhenExceeds128Bits()
    {
        assertLongValueReadFails(BigInteger.valueOf(1).shiftLeft(127));
        assertLongValueReadFails(BigInteger.valueOf(-2).shiftLeft(127));
    }

    @Test
    public void testLongDecimals()
            throws IOException
    {
        assertReadsLongValue(BigInteger.valueOf(0L));
        assertReadsLongValue(BigInteger.valueOf(1L));
        assertReadsLongValue(BigInteger.valueOf(-1L));
        assertReadsLongValue(BigInteger.valueOf(-1).shiftLeft(126));
        assertReadsLongValue(BigInteger.valueOf(1).shiftLeft(126));
        assertReadsLongValue(BIG_INTEGER_127_BIT_SET);
        assertReadsLongValue(BIG_INTEGER_127_BIT_SET.negate());
        assertReadsLongValue(MAX_DECIMAL_UNSCALED_VALUE);
        assertReadsLongValue(MIN_DECIMAL_UNSCALED_VALUE);
    }

    @Test
    public void testSkipsValue()
            throws IOException
    {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        writeBigInteger(baos, BigInteger.valueOf(Long.MAX_VALUE));
        writeBigInteger(baos, BigInteger.valueOf(Long.MIN_VALUE));

        OrcChunkLoader chunkLoader = orcChunkLoaderFor("skip test", baos.toByteArray());
        DecimalInputStream stream = new DecimalInputStream(chunkLoader);
        stream.skip(1);

        assertEquals(nextShortDecimalValue(stream), Long.MIN_VALUE);
    }

    @Test
    public void testSkipToEdgeOfChunkShort()
            throws IOException
    {
        OrcChunkLoader loader = new TestingChunkLoader(
                new OrcDataSourceId("skip to edge of chunk short"),
                ImmutableList.of(
                        encodeValues(ImmutableList.of(BigInteger.valueOf(Long.MAX_VALUE))),
                        encodeValues(ImmutableList.of(BigInteger.valueOf(Long.MAX_VALUE)))));

        DecimalInputStream stream = new DecimalInputStream(loader);

        stream.skip(1);
        assertEquals(nextShortDecimalValue(stream), Long.MAX_VALUE);
    }

    @Test
    public void testReadToEdgeOfChunkShort()
            throws IOException
    {
        OrcChunkLoader loader = new TestingChunkLoader(
                new OrcDataSourceId("read to edge of chunk short"),
                ImmutableList.of(
                        encodeValues(ImmutableList.of(BigInteger.valueOf(Long.MAX_VALUE))),
                        encodeValues(ImmutableList.of(BigInteger.valueOf(Long.MAX_VALUE)))));

        DecimalInputStream stream = new DecimalInputStream(loader);

        assertEquals(nextShortDecimalValue(stream), Long.MAX_VALUE);
        assertEquals(nextShortDecimalValue(stream), Long.MAX_VALUE);
    }

    @Test
    public void testSkipToEdgeOfChunkLong()
            throws IOException
    {
        OrcChunkLoader loader = new TestingChunkLoader(
                new OrcDataSourceId("skip to edge of chunk long"),
                ImmutableList.of(
                        encodeValues(ImmutableList.of(BigInteger.valueOf(Long.MAX_VALUE))),
                        encodeValues(ImmutableList.of(BigInteger.valueOf(Long.MAX_VALUE)))));

        DecimalInputStream stream = new DecimalInputStream(loader);

        stream.skip(1);
        assertEquals(nextLongDecimalValue(stream), BigInteger.valueOf(Long.MAX_VALUE));
    }

    @Test
    public void testReadToEdgeOfChunkLong()
            throws IOException
    {
        OrcChunkLoader loader = new TestingChunkLoader(
                new OrcDataSourceId("skip to edge of chunk long"),
                ImmutableList.of(
                        encodeValues(ImmutableList.of(BigInteger.valueOf(Long.MAX_VALUE))),
                        encodeValues(ImmutableList.of(BigInteger.valueOf(Long.MAX_VALUE)))));

        DecimalInputStream stream = new DecimalInputStream(loader);

        assertEquals(nextLongDecimalValue(stream), BigInteger.valueOf(Long.MAX_VALUE));
        assertEquals(nextLongDecimalValue(stream), BigInteger.valueOf(Long.MAX_VALUE));
    }

    private static Slice encodeValues(List<BigInteger> values)
            throws IOException
    {
        DynamicSliceOutput output = new DynamicSliceOutput(1);
        for (BigInteger value : values) {
            writeBigInteger(output, value);
        }

        return output.slice();
    }

    private static void assertReadsShortValue(long value)
            throws IOException
    {
        DecimalInputStream stream = new DecimalInputStream(decimalChunkLoader(BigInteger.valueOf(value)));
        assertEquals(nextShortDecimalValue(stream), value);
    }

    private static void assertReadsLongValue(BigInteger value)
            throws IOException
    {
        DecimalInputStream stream = new DecimalInputStream(decimalChunkLoader(value));
        assertEquals(nextLongDecimalValue(stream), value);
    }

    private static void assertShortValueReadFails(BigInteger value)
    {
        assertThrows(OrcCorruptionException.class, () -> {
            DecimalInputStream stream = new DecimalInputStream(decimalChunkLoader(value));
            nextShortDecimalValue(stream);
        });
    }

    private static void assertLongValueReadFails(BigInteger value)
    {
        assertThrows(OrcCorruptionException.class, () -> {
            DecimalInputStream stream = new DecimalInputStream(decimalChunkLoader(value));
            nextLongDecimalValue(stream);
        });
    }

    private static long nextShortDecimalValue(DecimalInputStream stream)
            throws IOException
    {
        long[] values = new long[1];
        stream.nextShortDecimal(values, 1);
        return values[0];
    }

    private static BigInteger nextLongDecimalValue(DecimalInputStream stream)
            throws IOException
    {
        long[] decimal = new long[2];
        stream.nextLongDecimal(decimal, 1);
        return unscaledDecimalToBigInteger(Slices.wrappedLongArray(decimal));
    }

    private static OrcChunkLoader decimalChunkLoader(BigInteger value)
            throws IOException
    {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        writeBigInteger(baos, value);
        return orcChunkLoaderFor(value.toString(), baos.toByteArray());
    }

    private static OrcChunkLoader orcChunkLoaderFor(String source, byte[] bytes)
    {
        return OrcChunkLoader.create(new OrcDataSourceId(source), Slices.wrappedBuffer(bytes), Optional.empty(), newSimpleAggregatedMemoryContext());
    }

    // copied from org.apache.hadoop.hive.ql.io.orc.SerializationUtils.java
    private static void writeBigInteger(OutputStream output, BigInteger value)
            throws IOException
    {
        // encode the signed number as a positive integer
        BigInteger tmpValue = value.shiftLeft(1);
        int sign = tmpValue.signum();
        if (sign < 0) {
            tmpValue = tmpValue.negate();
            tmpValue = tmpValue.subtract(ONE);
        }
        int length = tmpValue.bitLength();
        while (true) {
            long lowBits = tmpValue.longValue() & 0x7fffffffffffffffL;
            length -= 63;
            // write out the next 63 bits worth of data
            for (int i = 0; i < 9; ++i) {
                // if this is the last byte, leave the high bit off
                if (length <= 0 && (lowBits & ~0x7f) == 0) {
                    output.write((byte) lowBits);
                    return;
                }
                else {
                    output.write((byte) (0x80 | (lowBits & 0x7f)));
                    lowBits >>>= 7;
                }
            }
            tmpValue = tmpValue.shiftRight(63);
        }
    }

    private static class TestingChunkLoader
            implements OrcChunkLoader
    {
        private final OrcDataSourceId dataSourceId;
        private final Iterator<Slice> chunks;

        public TestingChunkLoader(OrcDataSourceId dataSourceId, List<Slice> chunks)
        {
            this.dataSourceId = dataSourceId;
            this.chunks = chunks.iterator();
        }

        @Override
        public OrcDataSourceId getOrcDataSourceId()
        {
            return dataSourceId;
        }

        @Override
        public boolean hasNextChunk()
        {
            return chunks.hasNext();
        }

        @Override
        public Slice nextChunk()
        {
            return chunks.next();
        }

        @Override
        public long getLastCheckpoint()
        {
            return 0;
        }

        @Override
        public void seekToCheckpoint(long checkpoint)
        {
        }
    }
}
