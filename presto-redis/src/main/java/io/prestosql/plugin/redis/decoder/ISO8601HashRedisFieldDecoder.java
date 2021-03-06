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
import io.prestosql.spi.type.Type;
import org.joda.time.DateTime;
import org.joda.time.chrono.ISOChronology;
import org.joda.time.format.DateTimeFormatter;
import org.joda.time.format.ISODateTimeFormat;

import java.util.Locale;

import static io.prestosql.spi.type.DateTimeEncoding.packDateTimeWithZone;
import static io.prestosql.spi.type.DateType.DATE;
import static io.prestosql.spi.type.TimeType.TIME;
import static io.prestosql.spi.type.TimeWithTimeZoneType.TIME_WITH_TIME_ZONE;
import static io.prestosql.spi.type.TimeZoneKey.getTimeZoneKey;
import static io.prestosql.spi.type.TimestampType.TIMESTAMP;
import static io.prestosql.spi.type.TimestampWithTimeZoneType.TIMESTAMP_WITH_TIME_ZONE;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

class ISO8601HashRedisFieldDecoder
        extends HashRedisFieldDecoder
{
    private static final DateTimeFormatter FORMATTER = ISODateTimeFormat.dateTimeParser()
            .withLocale(Locale.ENGLISH)
            .withChronology(ISOChronology.getInstanceUTC())
            .withOffsetParsed();

    @Override
    public FieldValueProvider decode(final String value, final DecoderColumnHandle columnHandle)
    {
        return new ISO8601HashRedisValueProvider(columnHandle, value);
    }

    private static class ISO8601HashRedisValueProvider
            extends HashRedisValueProvider
    {
        public ISO8601HashRedisValueProvider(final DecoderColumnHandle columnHandle, final String value)
        {
            super(columnHandle, value);
        }

        @Override
        public long getLong()
        {
            DateTime dateTime = FORMATTER.parseDateTime(getSlice().toStringAscii());
            long millis = dateTime.getMillis();

            Type type = columnHandle.getType();
            if (type.equals(DATE)) {
                return MILLISECONDS.toDays(millis);
            }
            if (type.equals(TIMESTAMP)) {
                return millis * 1_000;
            }
            if (type.equals(TIME)) {
                return millis * 1_000_000_000;
            }
            if (type.equals(TIMESTAMP_WITH_TIME_ZONE) || type.equals(TIME_WITH_TIME_ZONE)) {
                return packDateTimeWithZone(millis, getTimeZoneKey(dateTime.getZone().getID()));
            }

            return millis;
        }
    }
}
