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
import io.prestosql.spi.Page;
import io.prestosql.spi.connector.ConnectorPageSource;
import io.prestosql.spi.snapshot.BlockEncodingSerdeProvider;
import io.prestosql.spi.snapshot.RestorableConfig;

import java.io.Closeable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.concurrent.CompletableFuture;

import static io.airlift.concurrent.MoreFutures.toListenableFuture;
import static java.util.Objects.requireNonNull;

@RestorableConfig(unsupported = true)
public class PageSourceOperator
        implements Operator, Closeable
{
    private final ConnectorPageSource pageSource;
    private final OperatorContext operatorContext;
    private long completedBytes;
    private long readTimeNanos;

    public PageSourceOperator(ConnectorPageSource pageSource, OperatorContext operatorContext)
    {
        this.pageSource = requireNonNull(pageSource, "pageSource is null");
        this.operatorContext = requireNonNull(operatorContext, "operatorContext is null");
    }

    @Override
    public OperatorContext getOperatorContext()
    {
        return operatorContext;
    }

    @Override
    public void finish()
    {
        try {
            pageSource.close();
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public boolean isFinished()
    {
        return pageSource.isFinished();
    }

    @Override
    public ListenableFuture<?> isBlocked()
    {
        CompletableFuture<?> pageSourceBlocked = pageSource.isBlocked();
        return pageSourceBlocked.isDone() ? NOT_BLOCKED : toListenableFuture(pageSourceBlocked);
    }

    @Override
    public boolean needsInput()
    {
        return false;
    }

    @Override
    public void addInput(Page page)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public Page getOutput()
    {
        Page page = pageSource.getNextPage();
        if (page == null) {
            return null;
        }

        // update operator stats
        long endCompletedBytes = pageSource.getCompletedBytes();
        long endReadTimeNanos = pageSource.getReadTimeNanos();
        operatorContext.recordPhysicalInputWithTiming(endCompletedBytes - completedBytes, page.getPositionCount(), endReadTimeNanos - readTimeNanos);
        operatorContext.recordProcessedInput(page.getSizeInBytes(), page.getPositionCount());
        completedBytes = endCompletedBytes;
        readTimeNanos = endReadTimeNanos;

        // assure the page is in memory before handing to another operator
        page = page.getLoadedPage();

        return page;
    }

    @Override
    public Page pollMarker()
    {
        // TODO-cp-I38S9O: Operator currently not supported for Snapshot
        return null;
    }

    @Override
    public void close()
            throws IOException
    {
        pageSource.close();
    }

    @Override
    public Object capture(BlockEncodingSerdeProvider serdeProvider)
    {
        // TODO-cp-I38S9O: this is used by index join (not fully implemented) and benchmark (don't need snapshotting)
        return 0;
    }

    @Override
    public void restore(Object state, BlockEncodingSerdeProvider serdeProvider)
    {
    }
}
