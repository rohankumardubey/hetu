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
package io.prestosql.orc;

import com.google.common.collect.ImmutableList;
import io.airlift.units.DataSize;
import io.prestosql.spi.Page;
import io.prestosql.spi.block.Block;
import io.prestosql.spi.type.DecimalType;
import io.prestosql.spi.type.SqlDecimal;
import org.joda.time.DateTimeZone;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.VerboseMode;
import org.testng.annotations.Test;

import java.io.File;
import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import static com.google.common.io.MoreFiles.deleteRecursively;
import static com.google.common.io.RecursiveDeleteOption.ALLOW_INSECURE;
import static io.airlift.units.DataSize.Unit.MEGABYTE;
import static io.prestosql.memory.context.AggregatedMemoryContext.newSimpleAggregatedMemoryContext;
import static io.prestosql.orc.OrcReader.INITIAL_BATCH_SIZE;
import static io.prestosql.orc.OrcTester.Format.ORC_12;
import static io.prestosql.orc.OrcTester.writeOrcColumnHive;
import static io.prestosql.orc.metadata.CompressionKind.NONE;
import static io.prestosql.spi.type.DecimalType.createDecimalType;
import static java.nio.file.Files.createTempDirectory;
import static java.util.UUID.randomUUID;

@SuppressWarnings("MethodMayBeStatic")
@State(Scope.Thread)
@OutputTimeUnit(TimeUnit.SECONDS)
@Fork(3)
@Warmup(iterations = 20, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 20, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@BenchmarkMode(Mode.AverageTime)
public class BenchmarkOrcDecimalReader
{
    private static final DecimalType DECIMAL_TYPE = createDecimalType(30, 10);

    @Benchmark
    public Object readDecimal(BenchmarkData data)
            throws Throwable
    {
        OrcRecordReader recordReader = data.createRecordReader();
        List<Block> blocks = new ArrayList<>();
        for (Page page = recordReader.nextPage(); page != null; page = recordReader.nextPage()) {
            blocks.add(page.getBlock(0).getLoadedBlock());
        }
        return blocks;
    }

    @Test
    public void testReadDecimal()
            throws Throwable
    {
        BenchmarkData data = new BenchmarkData();
        data.setup();
        readDecimal(data);
    }

    @State(Scope.Thread)
    public static class BenchmarkData
    {
        private File temporary;
        private File dataPath;

        @Setup
        public void setup()
                throws Exception
        {
            temporary = createTempDirectory(getClass().getName()).toFile();
            dataPath = new File(temporary, randomUUID().toString());

            writeOrcColumnHive(dataPath, ORC_12, NONE, DECIMAL_TYPE, createDecimalValues().iterator());
        }

        @TearDown
        public void tearDown()
                throws IOException
        {
            deleteRecursively(temporary.toPath(), ALLOW_INSECURE);
        }

        private OrcRecordReader createRecordReader()
                throws IOException
        {
            OrcDataSource dataSource = new FileOrcDataSource(dataPath, new DataSize(1, MEGABYTE), new DataSize(1, MEGABYTE), new DataSize(1, MEGABYTE), true, dataPath.lastModified());
            OrcReader orcReader = new OrcReader(dataSource, new DataSize(1, MEGABYTE), new DataSize(1, MEGABYTE), new DataSize(1, MEGABYTE));
            return orcReader.createRecordReader(
                    orcReader.getRootColumn().getNestedColumns(),
                    ImmutableList.of(DECIMAL_TYPE),
                    OrcPredicate.TRUE,
                    DateTimeZone.UTC, // arbitrary
                    newSimpleAggregatedMemoryContext(),
                    INITIAL_BATCH_SIZE,
                    RuntimeException::new);
        }

        private List<SqlDecimal> createDecimalValues()
        {
            Random random = new Random();
            List<SqlDecimal> values = new ArrayList<>();
            for (int i = 0; i < 1000000; ++i) {
                values.add(new SqlDecimal(BigInteger.valueOf(random.nextLong() % 10000000000L), 10, 5));
            }
            return values;
        }
    }

    public static void main(String[] args)
            throws Throwable
    {
        // assure the benchmarks are valid before running
        BenchmarkData data = new BenchmarkData();
        data.setup();
        new BenchmarkOrcDecimalReader().readDecimal(data);

        Options options = new OptionsBuilder()
                .verbosity(VerboseMode.NORMAL)
                .include(".*" + BenchmarkOrcDecimalReader.class.getSimpleName() + ".*")
                .build();
        new Runner(options).run();
    }
}
