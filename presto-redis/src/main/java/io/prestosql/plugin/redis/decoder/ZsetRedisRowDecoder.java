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
package io.prestosql.plugin.redis.decoder;

import io.prestosql.decoder.DecoderColumnHandle;
import io.prestosql.decoder.FieldValueProvider;
import io.prestosql.decoder.RowDecoder;

import java.util.Map;
import java.util.Optional;

public class ZsetRedisRowDecoder
        implements RowDecoder
{
    public static final String NAME = "zset";

    @Override
    public Optional<Map<DecoderColumnHandle, FieldValueProvider>> decodeRow(
            final byte[] data, final Map<String, String> dataMap)
    {
        return Optional.empty();
    }
}
