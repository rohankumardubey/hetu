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

import com.google.common.collect.Iterators;
import com.google.common.util.concurrent.ListenableFuture;
import io.prestosql.spi.snapshot.BlockEncodingSerdeProvider;
import io.prestosql.spi.snapshot.Restorable;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;

import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/**
 * Snapshot: work processors are restorable, so are all its inner objects (processes, functions, and transformations).
 * In addition, functions and transformations are also responsible for capturing/restoring their input/output types,
 * because the generic WorkProcessor and Process objects don't know those types.
 */
public interface WorkProcessor<T>
        extends Restorable
{
    /**
     * Call the method to progress the work.
     * When this method returns true then the processor is either finished
     * or has a result available via {@link WorkProcessor#getResult()}.
     * When this method returns false then the processor is either
     * blocked or has yielded.
     */
    boolean process();

    boolean isBlocked();

    /**
     * @return a blocked future when {@link WorkProcessor#isBlocked()} returned true.
     */
    ListenableFuture<?> getBlockedFuture();

    /**
     * @return true if the processor is finished. No more results are expected.
     */
    boolean isFinished();

    /**
     * Get the result once the unit of work is done and the processor hasn't finished.
     */
    T getResult();

    /**
     * Makes {@link WorkProcessor} yield when given {@code yieldSignal} is set. The processor is
     * guaranteed to progress computations on subsequent {@link WorkProcessor#process()} calls
     * even if {@code yieldSignal} is permanently on.
     */
    default WorkProcessor<T> yielding(BooleanSupplier yieldSignal)
    {
        return WorkProcessorUtils.yielding(this, yieldSignal);
    }

    default WorkProcessor<T> withProcessEntryMonitor(Runnable monitor)
    {
        return WorkProcessorUtils.processEntryMonitor(this, monitor);
    }

    default WorkProcessor<T> withProcessStateMonitor(Consumer<ProcessState<T>> monitor)
    {
        return WorkProcessorUtils.processStateMonitor(this, monitor);
    }

    default WorkProcessor<T> finishWhen(BooleanSupplier finishSignal)
    {
        return WorkProcessorUtils.finishWhen(this, finishSignal);
    }

    default <R> WorkProcessor<R> flatMap(RestorableFunction<T, WorkProcessor<R>> mapper)
    {
        return WorkProcessorUtils.flatMap(this, mapper);
    }

    default <R> WorkProcessor<R> map(RestorableFunction<T, R> mapper)
    {
        return WorkProcessorUtils.map(this, mapper);
    }

    /**
     * Flattens {@link WorkProcessor}s returned by transformation. Each {@link WorkProcessor} produced
     * by transformation will be fully consumed before transformation is called again to produce more processors.
     */
    default <R> WorkProcessor<R> flatTransform(Transformation<T, WorkProcessor<R>> transformation)
    {
        return WorkProcessorUtils.flatTransform(this, transformation);
    }

    /**
     * Transforms {@link WorkProcessor} using {@link Transformation}. {@link Transformation} instance will be dereferenced immediately after
     * {@link WorkProcessor} is exhausted.
     */
    default <R> WorkProcessor<R> transform(Transformation<T, R> transformation)
    {
        return WorkProcessorUtils.transform(this, transformation);
    }

    default <R> WorkProcessor<R> transformProcessor(Function<WorkProcessor<T>, WorkProcessor<R>> transformation)
    {
        return transformation.apply(this);
    }

    /**
     * Converts {@link WorkProcessor} into an {@link Iterator}. The iterator will throw {@link IllegalStateException} when underlying {@link WorkProcessor}
     * yields or becomes blocked. {@link WorkProcessor} instance will be dereferenced immediately after iterator is finished.
     */
    default Iterator<T> iterator()
    {
        return WorkProcessorUtils.iteratorFrom(this);
    }

    /**
     * Converts {@link WorkProcessor} into an yielding {@link Iterator}. The iterator will throw {@link IllegalStateException} when underlying {@link WorkProcessor}
     * becomes blocked. {@link WorkProcessor} instance will be dereferenced immediately after iterator is exhausted.
     */
    default Iterator<Optional<T>> yieldingIterator()
    {
        return WorkProcessorUtils.yieldingIteratorFrom(this);
    }

    @SafeVarargs
    static <T> WorkProcessor<T> of(T... elements)
    {
        // Snapshot: This is only used by
        // TopNOperator: work processor is not involved until operator is finished, so no need for snapshot
        // PageProcess: work processor is only used for intermediate result and don't need to be captured
        checkArgument(elements.length <= 1);
        return fromIterator(Iterators.forArray(elements));
    }

    // Snapshot: This is only used by PagesSortBenchmark, where snapshot is not relevant.
    // When snapshot is required, this needs to be updated so the iterable can capture its content, e.g using RestorableIterator.
    static <T> WorkProcessor<T> fromIterable(Iterable<T> iterable)
    {
        return fromIterator(iterable.iterator());
    }

    // Snapshot: this is used by:
    // OrderByOperator: only used during "finish", so no need to take snapshot
    // WindowOperator: RestorableIterator is used, so it can capture its content
    // SpillableHashAggregationBuilder: to-do is added to that class to worry about iterator
    // WorkProcessor.of: its caller should not involve snapshot
    static <T> WorkProcessor<T> fromIterator(Iterator<T> iterator)
    {
        return WorkProcessorUtils.fromIterator(iterator);
    }

    /**
     * Creates {@link WorkProcessor} from {@link Process}. {@link Process} instance will be dereferenced immediately after {@link WorkProcessor} is finished.
     */
    static <T> WorkProcessor<T> create(Process<T> process)
    {
        return WorkProcessorUtils.create(process);
    }

    static <T> WorkProcessor<T> mergeSorted(List<WorkProcessor<T>> processorList, Comparator<T> comparator)
    {
        return WorkProcessorUtils.mergeSorted(processorList, comparator);
    }

    interface Transformation<T, R>
            extends Restorable
    {
        /**
         * Processes input elements and returns current transformation state.
         *
         * @param element an element to be transformed. Will be null
         * when there are no more elements. In such case transformation should
         * finish processing and flush any remaining data.
         * @return the current transformation state, optionally bearing a result
         * @see TransformationState#needsMoreData()
         * @see TransformationState#blocked(ListenableFuture)
         * @see TransformationState#yield()
         * @see TransformationState#ofResult(Object)
         * @see TransformationState#ofResult(Object, boolean)
         * @see TransformationState#finished()
         */
        TransformationState<R> process(@Nullable T element);

        // The next 4 methods are used to capture/restore input and output of this transformation
        default Object captureResult(R result, BlockEncodingSerdeProvider serdeProvider)
        {
            throw new UnsupportedOperationException();
        }

        default R restoreResult(Object resultState, BlockEncodingSerdeProvider serdeProvider)
        {
            throw new UnsupportedOperationException();
        }

        default Object captureInput(T input, BlockEncodingSerdeProvider serdeProvider)
        {
            throw new UnsupportedOperationException();
        }

        default T restoreInput(Object inputState, T input, BlockEncodingSerdeProvider serdeProvider)
        {
            throw new UnsupportedOperationException();
        }
    }

    interface RestorableFunction<T, R>
            extends Function<T, R>, Restorable
    {
        // In addition to the Function that performs the conversion,
        // the next 4 methods are used to capture/restore input and output of this function
        default Object captureInput(@Nullable T input, BlockEncodingSerdeProvider serdeProvider)
        {
            throw new UnsupportedOperationException();
        }

        default T restoreInput(Object inputState, BlockEncodingSerdeProvider serdeProvider)
        {
            throw new UnsupportedOperationException();
        }

        default Object captureResult(@Nullable R result, BlockEncodingSerdeProvider serdeProvider)
        {
            throw new UnsupportedOperationException();
        }

        default R restoreResult(Object resultState, BlockEncodingSerdeProvider serdeProvider)
        {
            throw new UnsupportedOperationException();
        }
    }

    interface RestorableIterator<T>
            extends Iterator<T>
    {
        default Object captureResult(@Nonnull T result, BlockEncodingSerdeProvider serdeProvider)
        {
            throw new UnsupportedOperationException();
        }

        default T restoreResult(@Nonnull Object resultState, BlockEncodingSerdeProvider serdeProvider)
        {
            throw new UnsupportedOperationException();
        }
    }

    interface Process<T>
            extends Restorable
    {
        /**
         * Does some work and returns current state.
         *
         * @return the current state, optionally bearing a result
         * @see ProcessState#blocked(ListenableFuture)
         * @see ProcessState#yield()
         * @see ProcessState#ofResult(Object)
         * @see ProcessState#finished()
         */
        ProcessState<T> process();

        @Override
        default Object capture(BlockEncodingSerdeProvider serdeProvider)
        {
            return 0;
        }

        @Override
        default void restore(Object state, BlockEncodingSerdeProvider serdeProvider)
        {
        }

        default Object captureResult(@Nonnull T result, BlockEncodingSerdeProvider serdeProvider)
        {
            throw new UnsupportedOperationException();
        }

        default T restoreResult(Object resultState, BlockEncodingSerdeProvider serdeProvider)
        {
            throw new UnsupportedOperationException();
        }
    }

    @Immutable
    final class TransformationState<T>
    {
        private static final TransformationState<?> NEEDS_MORE_DATE_STATE = new TransformationState<>(Type.NEEDS_MORE_DATA, true, null, null);
        private static final TransformationState<?> YIELD_STATE = new TransformationState<>(Type.YIELD, false, null, null);
        private static final TransformationState<?> FINISHED_STATE = new TransformationState<>(Type.FINISHED, false, null, null);

        enum Type
        {
            NEEDS_MORE_DATA,
            BLOCKED,
            YIELD,
            RESULT,
            FINISHED
        }

        private final Type type;
        private final boolean needsMoreData;
        @Nullable
        private final T result;
        @Nullable
        private final ListenableFuture<?> blocked;

        private TransformationState(Type type, boolean needsMoreData, @Nullable T result, @Nullable ListenableFuture<?> blocked)
        {
            this.type = requireNonNull(type, "type is null");
            this.needsMoreData = needsMoreData;
            this.result = result;
            this.blocked = blocked;
        }

        /**
         * Signals that transformation requires more data in order to continue and no result has been produced.
         * {@link #process()} will be called with a new input element or with {@link Optional#empty()} if there
         * are no more elements.
         */
        @SuppressWarnings("unchecked")
        public static <T> TransformationState<T> needsMoreData()
        {
            return (TransformationState<T>) NEEDS_MORE_DATE_STATE;
        }

        /**
         * Signals that transformation is blocked. {@link #process()} will be called again with the same input
         * element after {@code blocked} future is done.
         */
        public static <T> TransformationState<T> blocked(ListenableFuture<?> blocked)
        {
            return new TransformationState<>(Type.BLOCKED, false, null, requireNonNull(blocked, "blocked is null"));
        }

        /**
         * Signals that transformation has yielded. {@link #process()} will be called again with the same input element.
         */
        @SuppressWarnings("unchecked")
        public static <T> TransformationState<T> yield()
        {
            return (TransformationState<T>) YIELD_STATE;
        }

        /**
         * Signals that transformation has produced a result from its input. {@link #process()} will be called again with
         * a new element or with {@link Optional#empty()} if there are no more elements.
         */
        public static <T> TransformationState<T> ofResult(T result)
        {
            return ofResult(result, true);
        }

        /**
         * Signals that transformation has produced a result. If {@code needsMoreData}, {@link #process()} will be called again
         * with a new element (or with {@link Optional#empty()} if there are no more elements). If not @{code needsMoreData},
         * {@link #process()} will be called again with the same element.
         */
        public static <T> TransformationState<T> ofResult(T result, boolean needsMoreData)
        {
            return new TransformationState<>(Type.RESULT, needsMoreData, requireNonNull(result, "result is null"), null);
        }

        /**
         * Signals that transformation has finished. {@link #process()} method will not be called again.
         */
        @SuppressWarnings("unchecked")
        public static <T> TransformationState<T> finished()
        {
            return (TransformationState<T>) FINISHED_STATE;
        }

        Type getType()
        {
            return type;
        }

        boolean isNeedsMoreData()
        {
            return needsMoreData;
        }

        @Nullable
        T getResult()
        {
            return result;
        }

        @Nullable
        ListenableFuture<?> getBlocked()
        {
            return blocked;
        }
    }

    @Immutable
    final class ProcessState<T>
    {
        private static final ProcessState<?> YIELD_STATE = new ProcessState<>(Type.YIELD, null, null);
        private static final ProcessState<?> FINISHED_STATE = new ProcessState<>(Type.FINISHED, null, null);

        public enum Type
        {
            BLOCKED,
            YIELD,
            RESULT,
            FINISHED
        }

        private final Type type;
        @Nullable
        private final T result;
        @Nullable
        private final ListenableFuture<?> blocked;

        private ProcessState(Type type, @Nullable T result, @Nullable ListenableFuture<?> blocked)
        {
            this.type = requireNonNull(type, "type is null");
            this.result = result;
            this.blocked = blocked;
        }

        /**
         * Signals that process is blocked. {@link #process()} will be called again after {@code blocked} future is done.
         */
        public static <T> ProcessState<T> blocked(ListenableFuture<?> blocked)
        {
            return new ProcessState<>(Type.BLOCKED, null, requireNonNull(blocked, "blocked is null"));
        }

        /**
         * Signals that process has yielded. {@link #process()} will be called again later.
         */
        @SuppressWarnings("unchecked")
        public static <T> ProcessState<T> yield()
        {
            return (ProcessState<T>) YIELD_STATE;
        }

        /**
         * Signals that process has produced a result. {@link #process()} will be called again.
         */
        public static <T> ProcessState<T> ofResult(T result)
        {
            return new ProcessState<>(Type.RESULT, requireNonNull(result, "result is null"), null);
        }

        /**
         * Signals that process has finished. {@link #process()} method will not be called again.
         */
        @SuppressWarnings("unchecked")
        public static <T> ProcessState<T> finished()
        {
            return (ProcessState<T>) FINISHED_STATE;
        }

        public Type getType()
        {
            return type;
        }

        @Nullable
        public T getResult()
        {
            return result;
        }

        @Nullable
        public ListenableFuture<?> getBlocked()
        {
            return blocked;
        }
    }
}
