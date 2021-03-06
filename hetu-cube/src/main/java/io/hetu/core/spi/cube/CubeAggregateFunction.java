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

package io.hetu.core.spi.cube;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public enum CubeAggregateFunction
{
    SUM("sum"),
    COUNT("count"),
    AVG("avg"),
    MIN("min"),
    MAX("max");

    private final String name;

    public static final Set<String> SUPPORTED_FUNCTIONS = Stream.of(CubeAggregateFunction.values()).map(CubeAggregateFunction::getName).collect(Collectors.toSet());

    CubeAggregateFunction(String name)
    {
        this.name = name;
    }

    public String getName()
    {
        return this.name;
    }

    public String toString()
    {
        return this.name;
    }
}
