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
package io.prestosql.plugin.resourcegroups;

import com.google.common.collect.ImmutableMap;
import io.airlift.configuration.testing.ConfigAssertions;
import org.testng.annotations.Test;

import java.util.HashMap;
import java.util.Map;

import static com.google.common.io.Resources.getResource;
import static io.airlift.configuration.ConfigurationLoader.loadPropertiesFrom;
import static io.airlift.configuration.testing.ConfigAssertions.assertFullMapping;
import static io.airlift.configuration.testing.ConfigAssertions.assertRecordedDefaults;
import static org.testng.Assert.assertEquals;

public class TestFileResourceGroupConfig
{
    @Test
    public void testDefaults()
    {
        assertRecordedDefaults(ConfigAssertions.recordDefaults(FileResourceGroupConfig.class)
                .setConfigFile(null));
    }

    @Test
    public void testExplicitPropertyMappings()
    {
        Map<String, String> properties = new ImmutableMap.Builder<String, String>()
                .put("resource-groups.config-file", "/test.json")
                .build();

        FileResourceGroupConfig expected = new FileResourceGroupConfig()
                .setConfigFile("/test.json");

        assertFullMapping(properties, expected);
    }

    @Test
    public void testMarginParameters() throws Exception
    {
        Map<String, String> properties = new HashMap<>(loadPropertiesFrom(getResource("resource-groups-margin.properties").getPath()));
        String memoryMarginPercent = properties.get("resource-groups.memory-margin-percent");
        String queryProgressMarginPercent = properties.get("resource-groups.query-progress-margin-percent");
        assertEquals(memoryMarginPercent, "10");
        assertEquals(queryProgressMarginPercent, "5");
    }
}
