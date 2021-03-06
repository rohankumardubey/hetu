/*
 * Copyright (C) 2018-2021. Huawei Technologies Co., Ltd. All rights reserved.
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
package io.prestosql.failuredetector;

import io.airlift.units.Duration;
import io.prestosql.spi.failuredetector.FailureRetryPolicy;

import java.util.Properties;

public class TimeoutFailureRetryConfig
{
    private final String maxTimeoutDuration;

    public TimeoutFailureRetryConfig(Properties properties)
    {
        this.maxTimeoutDuration = properties.getProperty(FailureRetryPolicy.MAX_TIMEOUT_DURATION);
    }

    public Duration getMaxTimeoutDuration()
    {
        if (maxTimeoutDuration == null) {
            return Duration.valueOf(FailureRetryPolicy.DEFAULT_TIMEOUT_DURATION);
        }
        return Duration.valueOf(maxTimeoutDuration);
    }
}
