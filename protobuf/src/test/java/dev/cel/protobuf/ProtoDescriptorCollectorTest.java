// Copyright 2025 Google LLC
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package dev.cel.protobuf;

import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import dev.cel.expr.conformance.proto3.NestedTestAllTypes;
import dev.cel.expr.conformance.proto3.TestAllTypes;
import dev.cel.protobuf.LiteDescriptorCodegenMetadata.FieldLiteDescriptorMetadata;
import dev.cel.testing.testdata.AccessorNames;
import dev.cel.testing.testdata.MultiFile;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(TestParameterInjector.class)
public class ProtoDescriptorCollectorTest {

  @Test
  public void collectCodegenMetadata_containsAllDescriptors() {
    ProtoDescriptorCollector collector =
        ProtoDescriptorCollector.newInstance(DebugPrinter.newInstance(false));

    ImmutableList<LiteDescriptorCodegenMetadata> testAllTypesDescriptors =
        collector.collectCodegenMetadata(TestAllTypes.getDescriptor());
    ImmutableList<LiteDescriptorCodegenMetadata> nestedTestAllTypesDescriptors =
        collector.collectCodegenMetadata(NestedTestAllTypes.getDescriptor());

    // All proto messages, including transitive ones + maps
    assertThat(testAllTypesDescriptors).hasSize(165);
    assertThat(nestedTestAllTypesDescriptors).hasSize(1);
  }

  @SuppressWarnings("ImmutableEnumChecker") // Test only
  private enum GetterExpressionTestCase {
    REPEATED_CLASHING_WITH_COUNT(AccessorNames.getDescriptor(), "foo", "m.getFoo1List()"),
    CLASHING_WITH_REPEATED_COUNT(
        AccessorNames.getDescriptor(), "foo_count", "nonDefault(m.getFooCount2())"),
    REPEATED_CLASHING_WITH_LIST(AccessorNames.getDescriptor(), "bar", "m.getBar3List()"),
    CLASHING_WITH_REPEATED_LIST(
        AccessorNames.getDescriptor(), "bar_list", "nonDefault(m.getBarList4())"),
    OPEN_ENUM_CLASHING_WITH_VALUE(
        AccessorNames.getDescriptor(), "kind", "nonDefault(m.getKind5Value())"),
    CLASHING_WITH_OPEN_ENUM_VALUE(
        AccessorNames.getDescriptor(), "kind_value", "nonDefault(m.getKindValue6())"),
    MAP_CLASHING_WITH_COUNT(AccessorNames.getDescriptor(), "baz", "m.getBaz7Map()"),
    CLASHING_WITH_MAP_COUNT(
        AccessorNames.getDescriptor(), "baz_count", "nonDefault(m.getBazCount8())"),
    FORBIDDEN_NAME(AccessorNames.getDescriptor(), "class", "nonDefault(m.getClass_())"),
    SAME_CAPITALIZED_NAME(
        AccessorNames.getDescriptor(), "field1name", "nonDefault(m.getField1Name10())"),
    OTHER_SAME_CAPITALIZED_NAME(
        AccessorNames.getDescriptor(), "field1_name", "nonDefault(m.getField1Name11())"),
    SINGULAR_NEAR_MISS(AccessorNames.getDescriptor(), "qux", "nonDefault(m.getQux())"),
    SINGULAR_COUNT_NEAR_MISS(
        AccessorNames.getDescriptor(), "qux_count", "nonDefault(m.getQuxCount())"),
    NON_ENUM_VALUE_NEAR_MISS(
        AccessorNames.getDescriptor(), "qux_value", "nonDefault(m.getQuxValue())"),
    REPEATED_NEAR_MISS(AccessorNames.getDescriptor(), "quux", "m.getQuuxList()"),
    REPEATED_COUNT_NEAR_MISS(AccessorNames.getDescriptor(), "quux_count", "m.getQuuxCountList()"),
    FORBIDDEN_CASE_NEAR_MISS(
        AccessorNames.getDescriptor(), "serializedsize", "nonDefault(m.getSerializedsize())"),
    MESSAGE_WITH_PRESENCE(
        AccessorNames.getDescriptor(), "nested", "m.hasNested() ? m.getNested() : null"),
    OPEN_ENUM_REPEATED(
        TestAllTypes.getDescriptor(), "repeated_nested_enum", "m.getRepeatedNestedEnumValueList()"),
    OPEN_ENUM_MAP_VALUE(
        TestAllTypes.getDescriptor(), "map_int32_enum", "m.getMapInt32EnumValueMap()"),
    CLOSED_ENUM_SINGULAR(
        dev.cel.expr.conformance.proto2.TestAllTypes.getDescriptor(),
        "standalone_enum",
        "m.hasStandaloneEnum() ? m.getStandaloneEnum() : null"),
    CLOSED_ENUM_REPEATED(
        dev.cel.expr.conformance.proto2.TestAllTypes.getDescriptor(),
        "repeated_nested_enum",
        "m.getRepeatedNestedEnumList()"),
    CLOSED_ENUM_MAP_VALUE(
        dev.cel.expr.conformance.proto2.TestAllTypes.getDescriptor(),
        "map_int32_enum",
        "m.getMapInt32EnumMap()"),
    GROUP(dev.cel.expr.conformance.proto2.TestAllTypes.getDescriptor(), "nestedgroup", "");

    private final Descriptor descriptor;
    private final String fieldName;
    private final String expectedExpression;

    GetterExpressionTestCase(Descriptor descriptor, String fieldName, String expectedExpression) {
      this.descriptor = descriptor;
      this.fieldName = fieldName;
      this.expectedExpression = expectedExpression;
    }
  }

  @Test
  public void collectCodegenMetadata_getterExpression(
      @TestParameter GetterExpressionTestCase testCase) {
    ProtoDescriptorCollector collector =
        ProtoDescriptorCollector.newInstance(DebugPrinter.newInstance(false));

    ImmutableList<LiteDescriptorCodegenMetadata> descriptors =
        collector.collectCodegenMetadata(testCase.descriptor);

    ImmutableMap<String, String> expressionsByFieldName =
        descriptors.get(0).getFieldDescriptors().stream()
            .collect(
                toImmutableMap(
                    FieldLiteDescriptorMetadata::getFieldName,
                    FieldLiteDescriptorMetadata::getGetterExpression));
    assertThat(expressionsByFieldName)
        .containsEntry(testCase.fieldName, testCase.expectedExpression);
  }

  @Test
  public void collectCodegenMetadata_withProtoDependencies_containsAllDescriptors() {
    ProtoDescriptorCollector collector =
        ProtoDescriptorCollector.newInstance(DebugPrinter.newInstance(false));

    ImmutableList<LiteDescriptorCodegenMetadata> descriptors =
        collector.collectCodegenMetadata(MultiFile.getDescriptor());

    assertThat(descriptors).hasSize(3);
    assertThat(
            descriptors.stream()
                .filter(d -> d.getProtoTypeName().equals("dev.cel.testing.testdata.MultiFile"))
                .findAny())
        .isPresent();
  }

  @Test
  public void collectCodegenMetadata_withProtoDependencies_doesNotContainImportedProto() {
    ProtoDescriptorCollector collector =
        ProtoDescriptorCollector.newInstance(DebugPrinter.newInstance(false));

    ImmutableList<LiteDescriptorCodegenMetadata> descriptors =
        collector.collectCodegenMetadata(MultiFile.getDescriptor());

    assertThat(
            descriptors.stream()
                .filter(d -> d.getProtoTypeName().equals("dev.cel.testing.testdata.SingleFile"))
                .findAny())
        .isEmpty();
  }
}
