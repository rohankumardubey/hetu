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
package io.prestosql.spiller;

import io.prestosql.memory.context.LocalMemoryContext;
import io.prestosql.operator.SpillContext;
import io.prestosql.spi.type.Type;

import java.util.List;

public interface SingleStreamSpillerFactory
{
    SingleStreamSpiller create(List<Type> types, SpillContext spillContext, LocalMemoryContext memoryContext, boolean isSingleSessionSpiller, boolean isSnapshotEnabled, String queryId, boolean isSpillToHdfs);

    static SingleStreamSpillerFactory unsupportedSingleStreamSpillerFactory()
    {
        return (types, spillContext, memoryContext, isSingleSessionSpiller, isSnapshotEnabled, queryId, isSpillToHdfs) -> {
            throw new UnsupportedOperationException();
        };
    }
}
