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
package io.prestosql.plugin.ml;

import com.google.common.collect.ImmutableMap;
import io.airlift.slice.BasicSliceInput;
import io.airlift.slice.DynamicSliceOutput;
import io.airlift.slice.Slice;
import io.airlift.slice.Slices;
import io.prestosql.plugin.ml.type.ModelType;

import java.util.HashMap;
import java.util.Map;

import static com.google.common.base.Preconditions.checkState;
import static io.prestosql.plugin.ml.ModelUtils.serialize;
import static io.prestosql.plugin.ml.type.ClassifierType.VARCHAR_CLASSIFIER;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;

public class StringClassifierAdapter
        implements Classifier<String>
{
    private final Classifier<Integer> classifier;
    private final Map<Integer, String> labelEnumeration;

    public StringClassifierAdapter(Classifier<Integer> classifier)
    {
        this(classifier, new HashMap<>());
    }

    public StringClassifierAdapter(Classifier<Integer> classifier, Map<Integer, String> labelEnumeration)
    {
        this.classifier = requireNonNull(classifier, "classifier is is null");
        this.labelEnumeration = requireNonNull(labelEnumeration, "labelEnumeration is null");
    }

    @Override
    public ModelType getType()
    {
        return VARCHAR_CLASSIFIER;
    }

    @Override
    public byte[] getSerializedData()
    {
        byte[] classifierBytes = serialize(classifier).getBytes();
        DynamicSliceOutput output = new DynamicSliceOutput(classifierBytes.length + 64 * labelEnumeration.size());
        output.appendInt(classifierBytes.length);
        output.appendBytes(classifierBytes);
        output.appendInt(labelEnumeration.size());
        // Write the enumeration keys
        for (Map.Entry<Integer, String> entry : labelEnumeration.entrySet()) {
            output.appendInt(entry.getKey());
            byte[] bytes = entry.getValue().getBytes(UTF_8);
            output.appendInt(bytes.length);
            output.appendBytes(bytes);
        }

        return output.slice().getBytes();
    }

    public static StringClassifierAdapter deserialize(byte[] data)
    {
        Slice slice = Slices.wrappedBuffer(data);
        BasicSliceInput input = slice.getInput();
        int classifierLength = input.readInt();

        Model classifier1 = ModelUtils.deserialize(input.readSlice(classifierLength));
        int numEnumerations = input.readInt();

        ImmutableMap.Builder<Integer, String> builder = ImmutableMap.builder();

        for (int i = 0; i < numEnumerations; i++) {
            int key = input.readInt();
            int valueLength = input.readInt();
            String value = input.readSlice(valueLength).toStringUtf8();
            builder.put(key, value);
        }

        return new StringClassifierAdapter((Classifier<Integer>) classifier1, builder.build());
    }

    @Override
    public String classify(FeatureVector features)
    {
        int prediction = classifier.classify(features);
        checkState(labelEnumeration.containsKey(prediction), "classifier predicted an unknown class %s", prediction);
        return labelEnumeration.get(prediction);
    }

    @Override
    public void train(Dataset dataset)
    {
        labelEnumeration.putAll(dataset.getLabelEnumeration());
        classifier.train(dataset);
    }
}
