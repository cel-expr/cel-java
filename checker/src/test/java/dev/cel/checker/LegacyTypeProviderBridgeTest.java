// Copyright 2026 Google LLC
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

package dev.cel.checker;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import dev.cel.expr.Type;
import com.google.common.collect.ImmutableList;
import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import dev.cel.common.types.CelType;
import dev.cel.common.types.EnumType;
import dev.cel.common.types.ListType;
import dev.cel.common.types.ProtoMessageType;
import dev.cel.common.types.SimpleType;
import dev.cel.common.types.StructType;
import dev.cel.common.types.StructTypeReference;
import dev.cel.expr.conformance.proto2.TestAllTypesExtensions;
import dev.cel.expr.conformance.proto2.TestAllTypesProto;
import dev.cel.expr.conformance.proto3.TestAllTypes;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(TestParameterInjector.class)
public final class LegacyTypeProviderBridgeTest {

  private static final String TEST_ALL_TYPES = "cel.expr.conformance.proto3.TestAllTypes";
  private static final String PROTO2_TEST_ALL_TYPES = "cel.expr.conformance.proto2.TestAllTypes";
  private static final String INT32_EXT = "cel.expr.conformance.proto2.int32_ext";

  private final LegacyTypeProviderBridge bridge =
      new LegacyTypeProviderBridge(
          new DescriptorTypeProvider(ImmutableList.of(TestAllTypes.getDescriptor())));

  // DescriptorTypeProvider only walks the file descriptors it is given, so the file declaring the
  // extended message must be registered alongside the file declaring the extensions.
  private final LegacyTypeProviderBridge proto2Bridge =
      new LegacyTypeProviderBridge(
          new DescriptorTypeProvider(
              ImmutableList.of(
                  TestAllTypesExtensions.getDescriptor(), TestAllTypesProto.getDescriptor())));

  @Test
  public void types_isEmpty() {
    assertThat(bridge.types()).isEmpty();
  }

  @Test
  public void findType_messageType_returnsStructTypeOfSameName() {
    Optional<CelType> type = bridge.findType(TEST_ALL_TYPES);

    assertThat(type.map(Object::getClass)).hasValue(ProtoMessageType.class);
    assertThat(type.map(CelType::name)).hasValue(TEST_ALL_TYPES);
  }

  @Test
  public void findType_absentName_returnsEmpty() {
    LazyTypeProvider lazyTypeProvider = new LazyTypeProvider();
    LegacyTypeProviderBridge lazyBridge = new LegacyTypeProviderBridge(lazyTypeProvider);

    assertThat(lazyBridge.findType("foo.Bar")).isEmpty();
  }

  @Test
  public void findType_absentNameNotCached_resolvesWhenPopulated() {
    // Legacy providers may be backed by lazily populated descriptor pools, so a miss must not be
    // cached as a permanent negative.
    LazyTypeProvider lazyTypeProvider = new LazyTypeProvider();
    LegacyTypeProviderBridge lazyBridge = new LegacyTypeProviderBridge(lazyTypeProvider);
    lazyBridge.findType("foo.Bar");

    lazyTypeProvider.declaredType = StructTypeReference.create("foo.Bar");

    assertThat(lazyBridge.findType("foo.Bar").map(CelType::name)).hasValue("foo.Bar");
  }

  @Test
  public void findType_unresolvableName_returnsEmpty(
      @TestParameter({
            // Not declared by the provider.
            "cel.expr.conformance.proto3.Undefined",
            // A legacy provider can only resolve enums one value at a time, so the enum type on
            // its own is not resolvable.
            "cel.expr.conformance.proto3.TestAllTypes.NestedEnum",
            "cel.expr.conformance.proto3.TestAllTypes.NestedEnum.UNDEFINED",
            // Unqualified, so there is no enum type name to split off.
            "TestAllTypes",
            // Degenerate qualifications.
            "cel.expr.conformance.proto3.TestAllTypes.NestedEnum.",
            ".BAZ"
          })
          String typeName) {
    assertThat(bridge.findType(typeName)).isEmpty();
  }

  @Test
  public void findField_declaredField_returnsFieldType() {
    StructType structType = getStructType(TEST_ALL_TYPES);

    Optional<StructType.Field> field = structType.findField("single_int64");

    assertThat(field.map(StructType.Field::type)).hasValue(SimpleType.INT);
  }

  @Test
  public void findField_messageField_returnsStructTypeReference() {
    StructType structType = getStructType(TEST_ALL_TYPES);

    Optional<StructType.Field> field = structType.findField("single_nested_message");

    assertThat(field.map(StructType.Field::type))
        .hasValue(StructTypeReference.create(TEST_ALL_TYPES + ".NestedMessage"));
  }

  @Test
  public void findField_undefinedField_returnsEmpty() {
    StructType structType = getStructType(TEST_ALL_TYPES);

    assertThat(structType.findField("undefined_field")).isEmpty();
  }

  @Test
  public void fieldNames_bridgedType_throws() {
    StructType structType = getStructType(TEST_ALL_TYPES);

    // The bridge cannot enumerate field names, so enumeration must fail loudly rather than
    // silently report that the message has no fields.
    assertThrows(IllegalStateException.class, structType::fieldNames);
  }

  @Test
  public void fields_bridgedType_throws() {
    StructType structType = getStructType(TEST_ALL_TYPES);

    assertThrows(IllegalStateException.class, structType::fields);
  }

  @Test
  public void findExtension_declaredOnType_returnsFieldType() {
    ProtoMessageType structType = getProtoMessageType(PROTO2_TEST_ALL_TYPES);

    Optional<ProtoMessageType.Extension> extension = structType.findExtension(INT32_EXT);

    assertThat(extension.map(ProtoMessageType.Extension::type)).hasValue(SimpleType.INT);
  }

  @Test
  public void findExtension_declaredOnOtherType_returnsEmpty() {
    ProtoMessageType structType =
        getProtoMessageType("cel.expr.conformance.proto2.Proto2ExtensionScopedMessage");

    assertThat(structType.findExtension(INT32_EXT)).isEmpty();
  }

  @Test
  public void findType_enumValue_returnsEnumTypeNamedAfterEnum() {
    Optional<CelType> type = bridge.findType(TEST_ALL_TYPES + ".NestedEnum.BAZ");

    assertThat(type.map(Object::getClass)).hasValue(EnumType.class);
    // Naming the type rather than the value is what lets Env distinguish an enum constant from a
    // type reference.
    assertThat(type.map(CelType::name)).hasValue(TEST_ALL_TYPES + ".NestedEnum");
    assertThat(
            type.filter(t -> t instanceof EnumType)
                .flatMap(t -> ((EnumType) t).findNumberByName("BAZ")))
        .hasValue(2);
  }

  @Test
  public void findType_nonStructDeclaredType_returnedVerbatim() {
    // Not every legacy provider declares message types. Anything that is not a struct is handed
    // back unchanged so that Env observes the type the provider intended to declare.
    LazyTypeProvider lazyTypeProvider = new LazyTypeProvider();
    lazyTypeProvider.declaredType = ListType.create(SimpleType.STRING);

    assertThat(new LegacyTypeProviderBridge(lazyTypeProvider).findType("some.declared.Name"))
        .hasValue(lazyTypeProvider.declaredType);
  }

  @Test
  public void findType_enumValueWithTrailingDot_returnsEmpty() {
    LazyTypeProvider lazyTypeProvider = new LazyTypeProvider();
    lazyTypeProvider.enumValue = 1;

    assertThat(new LegacyTypeProviderBridge(lazyTypeProvider).findType("foo.Bar.")).isEmpty();
  }

  @Test
  public void findType_enumValueWithLeadingDot_returnsEmpty() {
    LazyTypeProvider lazyTypeProvider = new LazyTypeProvider();
    lazyTypeProvider.enumValue = 1;

    assertThat(new LegacyTypeProviderBridge(lazyTypeProvider).findType(".Bar")).isEmpty();
  }

  private StructType getStructType(String typeName) {
    Optional<CelType> type = bridge.findType(typeName);
    assertThat(type.map(CelType::name)).hasValue(typeName);
    return (StructType) type.get();
  }

  private ProtoMessageType getProtoMessageType(String typeName) {
    Optional<CelType> type = proto2Bridge.findType(typeName);
    assertThat(type.map(CelType::name)).hasValue(typeName);
    return (ProtoMessageType) type.get();
  }

  /** A {@link TypeProvider} whose single declared type can be populated after construction. */
  private static final class LazyTypeProvider implements TypeProvider {

    private @Nullable CelType declaredType;
    private @Nullable Integer enumValue;

    @Override
    public @Nullable Type lookupType(String typeName) {
      throw new UnsupportedOperationException("lookupType is not implemented");
    }

    @Override
    public Optional<CelType> lookupCelType(String typeName) {
      return Optional.ofNullable(declaredType);
    }

    @Override
    public @Nullable Integer lookupEnumValue(String enumName) {
      return enumValue;
    }

    @Override
    public @Nullable FieldType lookupFieldType(Type type, String fieldName) {
      return null;
    }
  }
}
