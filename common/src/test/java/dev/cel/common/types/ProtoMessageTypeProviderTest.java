// Copyright 2022 Google LLC
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

package dev.cel.common.types;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import dev.cel.common.types.CelTypeProvider.CombinedCelTypeProvider;
import dev.cel.common.types.StructType.Field;
import dev.cel.expr.conformance.proto2.TestAllTypes;
import dev.cel.expr.conformance.proto2.TestAllTypesExtensions;
import dev.cel.testing.testdata.SingleFile;
import java.util.Optional;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class ProtoMessageTypeProviderTest {

  private final ProtoMessageTypeProvider emptyProvider = new ProtoMessageTypeProvider();

  private final ProtoMessageTypeProvider proto3Provider =
      new ProtoMessageTypeProvider(
          ImmutableList.of(dev.cel.expr.conformance.proto3.TestAllTypes.getDescriptor()));

  private final ProtoMessageTypeProvider proto2Provider =
      new ProtoMessageTypeProvider(
          ImmutableSet.of(
              dev.cel.expr.conformance.proto3.TestAllTypes.getDescriptor().getFile(),
              TestAllTypes.getDescriptor().getFile(),
              TestAllTypesExtensions.getDescriptor()));

  @Test
  public void types_emptyTypeSet() {
    assertThat(emptyProvider.types()).isEmpty();
  }

  @Test
  public void findType_emptyTypeSet() {
    assertThat(emptyProvider.findType("any")).isEmpty();
  }

  @Test
  public void types_allGlobalAndNestedDeclarations() {
    assertThat(proto3Provider.types().stream().map(CelType::name).collect(toImmutableList()))
        .containsAtLeast(
            "cel.expr.conformance.proto3.GlobalEnum",
            "cel.expr.conformance.proto3.TestAllTypes",
            "cel.expr.conformance.proto3.TestAllTypes.NestedMessage",
            "cel.expr.conformance.proto3.TestAllTypes.NestedEnum",
            "cel.expr.conformance.proto3.NestedTestAllTypes");
  }

  @Test
  public void findType_globalEnumWithAllNamesAndNumbers() {
    Optional<CelType> celType = proto3Provider.findType("cel.expr.conformance.proto3.GlobalEnum");
    assertThat(celType).isPresent();
    assertThat(celType.get()).isInstanceOf(EnumType.class);
    EnumType enumType = (EnumType) celType.get();
    assertThat(enumType.name()).isEqualTo("cel.expr.conformance.proto3.GlobalEnum");
    assertThat(enumType.findNameByNumber(0)).hasValue("GOO");
    assertThat(enumType.findNameByNumber(1)).hasValue("GAR");
    assertThat(enumType.findNameByNumber(2)).hasValue("GAZ");
    assertThat(enumType.findNameByNumber(3)).isEmpty();
  }

  @Test
  public void findType_nestedEnumWithAllNamesAndNumbers() {
    Optional<CelType> celType =
        proto3Provider.findType("cel.expr.conformance.proto3.TestAllTypes.NestedEnum");
    assertThat(celType).isPresent();
    assertThat(celType.get()).isInstanceOf(EnumType.class);
    EnumType enumType = (EnumType) celType.get();
    assertThat(enumType.name()).isEqualTo("cel.expr.conformance.proto3.TestAllTypes.NestedEnum");
    assertThat(enumType.findNumberByName("FOO")).hasValue(0);
    assertThat(enumType.findNumberByName("BAR")).hasValue(1);
    assertThat(enumType.findNumberByName("BAZ")).hasValue(2);
    assertThat(enumType.findNumberByName("MISSING")).isEmpty();
  }

  @Test
  public void findType_globalMessageTypeNoExtensions() {
    Optional<CelType> celType =
        proto3Provider.findType("cel.expr.conformance.proto3.NestedTestAllTypes");
    assertThat(celType).isPresent();
    assertThat(celType.get()).isInstanceOf(ProtoMessageType.class);
    ProtoMessageType protoType = (ProtoMessageType) celType.get();
    assertThat(protoType.name()).isEqualTo("cel.expr.conformance.proto3.NestedTestAllTypes");
    assertThat(protoType.findField("payload")).isPresent();
    assertThat(protoType.findField("child")).isPresent();
    assertThat(protoType.findField("missing")).isEmpty();
    assertThat(protoType.fields()).hasSize(2);
    assertThat(protoType.findExtension("dev.cel.testing.testdata.proto3.any")).isEmpty();
  }

  @Test
  public void findType_globalMessageWithExtensions() {
    Optional<CelType> celType = proto2Provider.findType("cel.expr.conformance.proto2.TestAllTypes");
    assertThat(celType).isPresent();
    assertThat(celType.get()).isInstanceOf(ProtoMessageType.class);
    ProtoMessageType protoType = (ProtoMessageType) celType.get();
    assertThat(protoType.name()).isEqualTo("cel.expr.conformance.proto2.TestAllTypes");
    assertThat(protoType.findField("single_int32")).isPresent();
    assertThat(protoType.findField("single_uint64")).isPresent();
    assertThat(protoType.findField("oneof_type")).isPresent();
    assertThat(protoType.findField("nestedgroup")).isPresent();
    assertThat(protoType.findField("nested_enum_ext")).isEmpty();

    assertThat(protoType.findExtension("cel.expr.conformance.proto2.nested_ext")).isPresent();
    assertThat(protoType.findExtension("cel.expr.conformance.proto2.int32_ext")).isPresent();
    assertThat(protoType.findExtension("cel.expr.conformance.proto2.test_all_types_ext"))
        .isPresent();
    assertThat(protoType.findExtension("cel.expr.conformance.proto2.nested_enum_ext")).isPresent();
    assertThat(protoType.findExtension("cel.expr.conformance.proto2.repeated_test_all_types"))
        .isPresent();

    assertThat(protoType.findExtension("cel.expr.conformance.proto2.TestAllTypes.int32_ext"))
        .isEmpty();

    Optional<CelType> holderType =
        proto2Provider.findType("cel.expr.conformance.proto2.TestRequired");
    assertThat(holderType).isPresent();
    ProtoMessageType stringHolderType = (ProtoMessageType) holderType.get();
    assertThat(stringHolderType.findExtension("cel.expr.conformance.proto2.nested_enum_ext"))
        .isEmpty();
  }

  @Test
  public void findType_scopedMessageWithExtensions() {
    Optional<CelType> celType = proto2Provider.findType("cel.expr.conformance.proto2.TestAllTypes");
    assertThat(celType).isPresent();
    assertThat(celType.get()).isInstanceOf(ProtoMessageType.class);
    ProtoMessageType protoType = (ProtoMessageType) celType.get();

    assertThat(
            protoType.findExtension(
                "cel.expr.conformance.proto2.Proto2ExtensionScopedMessage.message_scoped_nested_ext"))
        .isPresent();
    assertThat(
            protoType.findExtension(
                "cel.expr.conformance.proto2.Proto2ExtensionScopedMessage.int64_ext"))
        .isPresent();
    assertThat(
            protoType.findExtension(
                "cel.expr.conformance.proto2.Proto2ExtensionScopedMessage.message_scoped_repeated_test_all_types"))
        .isPresent();

    assertThat(
            protoType.findExtension(
                "cel.expr.conformance.proto2.Proto2ExtensionScopedMessage.message_scoped_nested_ext"))
        .isPresent();
  }

  @Test
  public void findType_withRepeatedEnumField() {
    Optional<CelType> celType = proto3Provider.findType("cel.expr.conformance.proto3.TestAllTypes");
    assertThat(celType).isPresent();
    assertThat(celType.get()).isInstanceOf(ProtoMessageType.class);
    ProtoMessageType protoType = (ProtoMessageType) celType.get();
    assertThat(protoType.name()).isEqualTo("cel.expr.conformance.proto3.TestAllTypes");
    assertThat(protoType.findField("repeated_nested_enum")).isPresent();

    CelType fieldType = protoType.findField("repeated_nested_enum").get().type();
    assertThat(fieldType.kind()).isEqualTo(CelKind.LIST);
    assertThat(fieldType.parameters()).hasSize(1);
    CelType elemType = fieldType.parameters().get(0);
    assertThat(elemType.name()).isEqualTo("cel.expr.conformance.proto3.TestAllTypes.NestedEnum");
    assertThat(elemType.kind()).isEqualTo(CelKind.INT);
    assertThat(elemType).isInstanceOf(EnumType.class);
    assertThat(proto3Provider.findType("cel.expr.conformance.proto3.TestAllTypes.NestedEnum"))
        .hasValue(elemType);
  }

  @Test
  public void findType_withOneofField() {
    Optional<CelType> celType = proto3Provider.findType("cel.expr.conformance.proto3.TestAllTypes");
    ProtoMessageType protoType = (ProtoMessageType) celType.get();
    assertThat(protoType.name()).isEqualTo("cel.expr.conformance.proto3.TestAllTypes");
    assertThat(protoType.findField("single_nested_message").map(f -> f.type().name()))
        .hasValue("cel.expr.conformance.proto3.TestAllTypes.NestedMessage");
  }

  @Test
  public void findType_withMapField() {
    Optional<CelType> celType = proto3Provider.findType("cel.expr.conformance.proto3.TestAllTypes");
    ProtoMessageType protoType = (ProtoMessageType) celType.get();
    CelType fieldType = protoType.findField("map_int64_nested_type").get().type();

    assertThat(fieldType.kind()).isEqualTo(CelKind.MAP);
    assertThat(fieldType.parameters()).hasSize(2);
    CelType keyType = fieldType.parameters().get(0);
    CelType valueType = fieldType.parameters().get(1);
    assertThat(keyType.name()).isEqualTo("int");
    assertThat(keyType.kind()).isEqualTo(CelKind.INT);
    assertThat(valueType.name()).isEqualTo("cel.expr.conformance.proto3.NestedTestAllTypes");
    assertThat(valueType.kind()).isEqualTo(CelKind.STRUCT);
  }

  @Test
  public void findType_withWellKnownTypes() {
    Optional<CelType> celType = proto3Provider.findType("cel.expr.conformance.proto3.TestAllTypes");
    ProtoMessageType protoType = (ProtoMessageType) celType.get();
    assertThat(protoType.findField("single_any").map(f -> f.type())).hasValue(SimpleType.ANY);
    assertThat(protoType.findField("single_duration").map(f -> f.type()))
        .hasValue(SimpleType.DURATION);
    assertThat(protoType.findField("single_timestamp").map(f -> f.type()))
        .hasValue(SimpleType.TIMESTAMP);
    assertThat(protoType.findField("single_value").map(f -> f.type())).hasValue(SimpleType.DYN);
    assertThat(protoType.findField("list_value").map(f -> f.type()))
        .hasValue(ListType.create(SimpleType.DYN));
    assertThat(protoType.findField("single_struct").map(f -> f.type()))
        .hasValue(MapType.create(SimpleType.STRING, SimpleType.DYN));
    assertThat(protoType.findField("single_bool_wrapper").map(f -> f.type()))
        .hasValue(NullableType.create(SimpleType.BOOL));
    assertThat(protoType.findField("single_bytes_wrapper").map(f -> f.type()))
        .hasValue(NullableType.create(SimpleType.BYTES));
    assertThat(protoType.findField("single_int32_wrapper").map(f -> f.type()))
        .hasValue(NullableType.create(SimpleType.INT));
    assertThat(protoType.findField("single_int64_wrapper").map(f -> f.type()))
        .hasValue(NullableType.create(SimpleType.INT));
    assertThat(protoType.findField("single_double_wrapper").map(f -> f.type()))
        .hasValue(NullableType.create(SimpleType.DOUBLE));
    assertThat(protoType.findField("single_float_wrapper").map(f -> f.type()))
        .hasValue(NullableType.create(SimpleType.DOUBLE));
    assertThat(protoType.findField("single_string_wrapper").map(f -> f.type()))
        .hasValue(NullableType.create(SimpleType.STRING));
    assertThat(protoType.findField("single_uint32_wrapper").map(f -> f.type()))
        .hasValue(NullableType.create(SimpleType.UINT));
    assertThat(protoType.findField("single_uint64_wrapper").map(f -> f.type()))
        .hasValue(NullableType.create(SimpleType.UINT));
  }

  @Test
  public void types_combinedEmptyProviderIsEmpty() {
    CombinedCelTypeProvider combined = new CombinedCelTypeProvider(emptyProvider, emptyProvider);
    assertThat(combined.types()).isEmpty();
  }

  @Test
  public void types_combinedDuplicateProviderIsSameAsFirst() {
    CombinedCelTypeProvider combined = new CombinedCelTypeProvider(proto3Provider, proto3Provider);
    assertThat(combined.types()).hasSize(proto3Provider.types().size());
  }

  @Test
  public void findType_combinedWithDynamicProvider_resolvesFromDelegateWithFirstPrecedence() {
    CelType dynamicType = StructTypeReference.create("custom.DynamicType");
    CelType overriddenTestAllTypes =
        StructTypeReference.create("cel.expr.conformance.proto3.TestAllTypes");
    CelTypeProvider dynamicProvider =
        new CelTypeProvider() {
          @Override
          public ImmutableList<CelType> types() {
            return ImmutableList.of();
          }

          @Override
          public Optional<CelType> findType(String typeName) {
            if (typeName.equals("custom.DynamicType")) {
              return Optional.of(dynamicType);
            }
            if (typeName.equals("cel.expr.conformance.proto3.TestAllTypes")) {
              return Optional.of(overriddenTestAllTypes);
            }
            return Optional.empty();
          }
        };
    CombinedCelTypeProvider dynamicFirst =
        new CombinedCelTypeProvider(dynamicProvider, proto3Provider);
    CombinedCelTypeProvider staticFirst =
        new CombinedCelTypeProvider(proto3Provider, dynamicProvider);

    assertThat(dynamicFirst.findType("cel.expr.conformance.proto3.TestAllTypes"))
        .hasValue(overriddenTestAllTypes);
    assertThat(dynamicFirst.findType("cel.expr.conformance.proto3.TestAllTypes.NestedMessage"))
        .isPresent();
    assertThat(dynamicFirst.findType("custom.DynamicType")).hasValue(dynamicType);
    assertThat(dynamicFirst.findType("custom.UndefinedType")).isEmpty();
    assertThat(staticFirst.findType("cel.expr.conformance.proto3.TestAllTypes").get())
        .isInstanceOf(ProtoMessageType.class);
    assertThat(staticFirst.findType("custom.DynamicType")).hasValue(dynamicType);
  }

  @Test
  public void findField_withJsonNameOption() {
    ProtoMessageTypeProvider typeProvider =
        ProtoMessageTypeProvider.newBuilder()
            .addFileDescriptors(SingleFile.getDescriptor().getFile())
            .setAllowJsonFieldNames(true)
            .build();

    ProtoMessageType msgType =
        (ProtoMessageType) typeProvider.findType(SingleFile.getDescriptor().getFullName()).get();

    // Note that these are the same fields, with json_name option set
    Optional<Field> snakeCasedField = msgType.findField("int64_camel_case_json_name");
    Optional<Field> jsonNameField = msgType.findField("int64CamelCaseJsonName");

    assertThat(snakeCasedField).isEmpty();
    assertThat(jsonNameField).isPresent();
  }
}
