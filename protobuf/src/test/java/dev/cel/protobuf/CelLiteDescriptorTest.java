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

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.protobuf.WireFormat;
import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import dev.cel.expr.conformance.proto3.TestAllTypes;
import dev.cel.expr.conformance.proto3.TestAllTypesCelLiteDescriptor;
import dev.cel.protobuf.CelLiteDescriptor.FieldLiteDescriptor;
import dev.cel.protobuf.CelLiteDescriptor.FieldLiteDescriptor.EncodingType;
import dev.cel.protobuf.CelLiteDescriptor.FieldLiteDescriptor.JavaType;
import dev.cel.protobuf.CelLiteDescriptor.MessageLiteDescriptor;
import dev.cel.testing.testdata.AccessorNames;
import dev.cel.testing.testdata.AccessorNamesCelLiteDescriptor;
import java.util.Map;
import java.util.Optional;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(TestParameterInjector.class)
public class CelLiteDescriptorTest {

  private static final TestAllTypesCelLiteDescriptor TEST_ALL_TYPES_CEL_LITE_DESCRIPTOR =
      TestAllTypesCelLiteDescriptor.getDescriptor();

  @Test
  public void getProtoTypeNamesToDescriptors_containsAllMessages() {
    Map<String, MessageLiteDescriptor> protoNamesToDescriptors =
        TEST_ALL_TYPES_CEL_LITE_DESCRIPTOR.getProtoTypeNamesToDescriptors();

    assertThat(protoNamesToDescriptors).containsKey("cel.expr.conformance.proto3.TestAllTypes");
    assertThat(protoNamesToDescriptors)
        .containsKey("cel.expr.conformance.proto3.TestAllTypes.NestedMessage");
  }

  @Test
  public void testAllTypesMessageLiteDescriptor_fullyQualifiedNames() {
    MessageLiteDescriptor testAllTypesDescriptor =
        TEST_ALL_TYPES_CEL_LITE_DESCRIPTOR
            .getProtoTypeNamesToDescriptors()
            .get("cel.expr.conformance.proto3.TestAllTypes");

    assertThat(testAllTypesDescriptor.getProtoTypeName())
        .isEqualTo("cel.expr.conformance.proto3.TestAllTypes");
    assertThat(testAllTypesDescriptor.toString())
        .isEqualTo("cel.expr.conformance.proto3.TestAllTypes");
    assertThat(testAllTypesDescriptor.hasGetters()).isTrue();
  }

  @Test
  public void fieldDescriptor_getByFieldNumber() {
    MessageLiteDescriptor testAllTypesDescriptor =
        TEST_ALL_TYPES_CEL_LITE_DESCRIPTOR
            .getProtoTypeNamesToDescriptors()
            .get("cel.expr.conformance.proto3.TestAllTypes");

    FieldLiteDescriptor fieldLiteDescriptor = testAllTypesDescriptor.getByFieldNumberOrThrow(14);

    assertThat(fieldLiteDescriptor.getFieldName()).isEqualTo("single_string");
  }

  @Test
  public void findByFieldName_declaredField_returnsDescriptor() {
    MessageLiteDescriptor testAllTypesDescriptor =
        TEST_ALL_TYPES_CEL_LITE_DESCRIPTOR
            .getProtoTypeNamesToDescriptors()
            .get("cel.expr.conformance.proto3.TestAllTypes");

    Optional<FieldLiteDescriptor> fieldLiteDescriptor =
        testAllTypesDescriptor.findByFieldName("single_string");

    assertThat(fieldLiteDescriptor.map(FieldLiteDescriptor::getFieldNumber)).hasValue(14);
  }

  @Test
  public void findByFieldName_undeclaredField_returnsEmpty() {
    MessageLiteDescriptor testAllTypesDescriptor =
        TEST_ALL_TYPES_CEL_LITE_DESCRIPTOR
            .getProtoTypeNamesToDescriptors()
            .get("cel.expr.conformance.proto3.TestAllTypes");

    Optional<FieldLiteDescriptor> fieldLiteDescriptor =
        testAllTypesDescriptor.findByFieldName("undeclared_field");

    assertThat(fieldLiteDescriptor).isEmpty();
  }

  @Test
  public void fieldDescriptor_scalarField() {
    MessageLiteDescriptor testAllTypesDescriptor =
        TEST_ALL_TYPES_CEL_LITE_DESCRIPTOR
            .getProtoTypeNamesToDescriptors()
            .get("cel.expr.conformance.proto3.TestAllTypes");
    FieldLiteDescriptor fieldLiteDescriptor =
        testAllTypesDescriptor.getByFieldNameOrThrow("single_string");

    assertThat(fieldLiteDescriptor.getEncodingType()).isEqualTo(EncodingType.SINGULAR);
    assertThat(fieldLiteDescriptor.getJavaType()).isEqualTo(JavaType.STRING);
    assertThat(fieldLiteDescriptor.getProtoFieldType()).isEqualTo(FieldLiteDescriptor.Type.STRING);
  }

  @Test
  public void fieldDescriptor_primitiveField_fullyQualifiedNames() {
    MessageLiteDescriptor testAllTypesDescriptor =
        TEST_ALL_TYPES_CEL_LITE_DESCRIPTOR
            .getProtoTypeNamesToDescriptors()
            .get("cel.expr.conformance.proto3.TestAllTypes");
    FieldLiteDescriptor fieldLiteDescriptor =
        testAllTypesDescriptor.getByFieldNameOrThrow("single_string");

    assertThat(fieldLiteDescriptor.getFieldProtoTypeName()).isEmpty();
  }

  @Test
  public void fieldDescriptor_mapField() {
    MessageLiteDescriptor testAllTypesDescriptor =
        TEST_ALL_TYPES_CEL_LITE_DESCRIPTOR
            .getProtoTypeNamesToDescriptors()
            .get("cel.expr.conformance.proto3.TestAllTypes");
    FieldLiteDescriptor fieldLiteDescriptor =
        testAllTypesDescriptor.getByFieldNameOrThrow("map_bool_string");

    assertThat(fieldLiteDescriptor.getEncodingType()).isEqualTo(EncodingType.MAP);
    assertThat(fieldLiteDescriptor.getJavaType()).isEqualTo(JavaType.MESSAGE);
    assertThat(fieldLiteDescriptor.getProtoFieldType()).isEqualTo(FieldLiteDescriptor.Type.MESSAGE);
  }

  @Test
  public void fieldDescriptor_repeatedField() {
    MessageLiteDescriptor testAllTypesDescriptor =
        TEST_ALL_TYPES_CEL_LITE_DESCRIPTOR
            .getProtoTypeNamesToDescriptors()
            .get("cel.expr.conformance.proto3.TestAllTypes");
    FieldLiteDescriptor fieldLiteDescriptor =
        testAllTypesDescriptor.getByFieldNameOrThrow("repeated_int64");

    assertThat(fieldLiteDescriptor.getEncodingType()).isEqualTo(EncodingType.LIST);
    assertThat(fieldLiteDescriptor.getJavaType()).isEqualTo(JavaType.LONG);
    assertThat(fieldLiteDescriptor.getIsPacked()).isTrue();
    assertThat(fieldLiteDescriptor.getProtoFieldType()).isEqualTo(FieldLiteDescriptor.Type.INT64);
  }

  @Test
  public void fieldDescriptor_nestedMessage() {
    MessageLiteDescriptor testAllTypesDescriptor =
        TEST_ALL_TYPES_CEL_LITE_DESCRIPTOR
            .getProtoTypeNamesToDescriptors()
            .get("cel.expr.conformance.proto3.TestAllTypes");
    FieldLiteDescriptor fieldLiteDescriptor =
        testAllTypesDescriptor.getByFieldNameOrThrow("standalone_message");

    assertThat(fieldLiteDescriptor.getEncodingType()).isEqualTo(EncodingType.SINGULAR);
    assertThat(fieldLiteDescriptor.getJavaType()).isEqualTo(JavaType.MESSAGE);
    assertThat(fieldLiteDescriptor.getProtoFieldType()).isEqualTo(FieldLiteDescriptor.Type.MESSAGE);
  }

  @Test
  public void fieldDescriptor_nestedMessage_fullyQualifiedNames() {
    MessageLiteDescriptor testAllTypesDescriptor =
        TEST_ALL_TYPES_CEL_LITE_DESCRIPTOR
            .getProtoTypeNamesToDescriptors()
            .get("cel.expr.conformance.proto3.TestAllTypes");
    FieldLiteDescriptor fieldLiteDescriptor =
        testAllTypesDescriptor.getByFieldNameOrThrow("standalone_message");

    assertThat(fieldLiteDescriptor.getFieldProtoTypeName())
        .isEqualTo("cel.expr.conformance.proto3.TestAllTypes.NestedMessage");
  }

  private enum ProtoFieldTypeTestCase {
    DOUBLE(FieldLiteDescriptor.Type.DOUBLE, 1, WireFormat.FieldType.DOUBLE),
    FLOAT(FieldLiteDescriptor.Type.FLOAT, 2, WireFormat.FieldType.FLOAT),
    INT64(FieldLiteDescriptor.Type.INT64, 3, WireFormat.FieldType.INT64),
    UINT64(FieldLiteDescriptor.Type.UINT64, 4, WireFormat.FieldType.UINT64),
    INT32(FieldLiteDescriptor.Type.INT32, 5, WireFormat.FieldType.INT32),
    FIXED64(FieldLiteDescriptor.Type.FIXED64, 6, WireFormat.FieldType.FIXED64),
    FIXED32(FieldLiteDescriptor.Type.FIXED32, 7, WireFormat.FieldType.FIXED32),
    BOOL(FieldLiteDescriptor.Type.BOOL, 8, WireFormat.FieldType.BOOL),
    STRING(FieldLiteDescriptor.Type.STRING, 9, WireFormat.FieldType.STRING),
    GROUP(FieldLiteDescriptor.Type.GROUP, 10, WireFormat.FieldType.GROUP),
    MESSAGE(FieldLiteDescriptor.Type.MESSAGE, 11, WireFormat.FieldType.MESSAGE),
    BYTES(FieldLiteDescriptor.Type.BYTES, 12, WireFormat.FieldType.BYTES),
    UINT32(FieldLiteDescriptor.Type.UINT32, 13, WireFormat.FieldType.UINT32),
    ENUM(FieldLiteDescriptor.Type.ENUM, 14, WireFormat.FieldType.ENUM),
    SFIXED32(FieldLiteDescriptor.Type.SFIXED32, 15, WireFormat.FieldType.SFIXED32),
    SFIXED64(FieldLiteDescriptor.Type.SFIXED64, 16, WireFormat.FieldType.SFIXED64),
    SINT32(FieldLiteDescriptor.Type.SINT32, 17, WireFormat.FieldType.SINT32),
    SINT64(FieldLiteDescriptor.Type.SINT64, 18, WireFormat.FieldType.SINT64);

    private final FieldLiteDescriptor.Type type;
    private final int expectedNumber;
    private final WireFormat.FieldType expectedWireType;

    ProtoFieldTypeTestCase(
        FieldLiteDescriptor.Type type, int expectedNumber, WireFormat.FieldType expectedWireType) {
      this.type = type;
      this.expectedNumber = expectedNumber;
      this.expectedWireType = expectedWireType;
    }
  }

  @Test
  public void protoFieldType_numbersAndWireTypes(@TestParameter ProtoFieldTypeTestCase testCase) {
    assertThat(testCase.type.getNumber()).isEqualTo(testCase.expectedNumber);
    assertThat(testCase.type.toWireFormatFieldType()).isEqualTo(testCase.expectedWireType);
  }

  @SuppressWarnings("ImmutableEnumChecker") // Test only
  private enum AccessorNameTestCase {
    REPEATED_CLASHING_WITH_COUNT(AccessorNames.FOO_FIELD_NUMBER, ImmutableList.of(1)),
    CLASHING_WITH_REPEATED_COUNT(AccessorNames.FOO_COUNT_FIELD_NUMBER, 2L),
    REPEATED_CLASHING_WITH_LIST(AccessorNames.BAR_FIELD_NUMBER, ImmutableList.of("b")),
    CLASHING_WITH_REPEATED_LIST(AccessorNames.BAR_LIST_FIELD_NUMBER, "c"),
    OPEN_ENUM_CLASHING_WITH_VALUE(AccessorNames.KIND_FIELD_NUMBER, 1),
    CLASHING_WITH_OPEN_ENUM_VALUE(AccessorNames.KIND_VALUE_FIELD_NUMBER, 3),
    MAP_CLASHING_WITH_COUNT(AccessorNames.BAZ_FIELD_NUMBER, ImmutableMap.of("k", 5)),
    CLASHING_WITH_MAP_COUNT(AccessorNames.BAZ_COUNT_FIELD_NUMBER, 6),
    FORBIDDEN_NAME(AccessorNames.CLASS_FIELD_NUMBER, 4),
    SAME_CAPITALIZED_NAME(AccessorNames.FIELD1NAME_FIELD_NUMBER, "d"),
    OTHER_SAME_CAPITALIZED_NAME(AccessorNames.FIELD1_NAME_FIELD_NUMBER, "e");

    private final int fieldNumber;
    private final Object expectedValue;

    AccessorNameTestCase(int fieldNumber, Object expectedValue) {
      this.fieldNumber = fieldNumber;
      this.expectedValue = expectedValue;
    }
  }

  @Test
  public void getFieldValue_protobufAccessorName_readsField(
      @TestParameter AccessorNameTestCase testCase) {
    AccessorNames message =
        AccessorNames.newBuilder()
            .addFoo1(1)
            .setFooCount2(2L)
            .addBar3("b")
            .setBarList4("c")
            .setKind5(AccessorNames.Kind.KIND_ON)
            .setKindValue6(3)
            .putBaz7("k", 5)
            .setBazCount8(6)
            .setClass_(4)
            .setField1Name10("d")
            .setField1Name11("e")
            .build();

    Object value =
        accessorNamesDescriptor("AccessorNames").getFieldValue(message, testCase.fieldNumber);

    assertThat(value).isEqualTo(testCase.expectedValue);
  }

  @Test
  public void getFieldValue_messageAfterRoot_readsField() {
    AccessorNames.Nested message = AccessorNames.Nested.newBuilder().setX(7).build();

    Object value =
        accessorNamesDescriptor("AccessorNames.Nested")
            .getFieldValue(message, AccessorNames.Nested.X_FIELD_NUMBER);

    assertThat(value).isEqualTo(7);
  }

  @Test
  public void getFieldValue_unownedDescriptor_returnsNoGetter() {
    MessageLiteDescriptor descriptor =
        new MessageLiteDescriptor("unowned.Message", ImmutableList.of(), TestAllTypes::newBuilder);

    Object value =
        descriptor.getFieldValue(
            TestAllTypes.newBuilder().setSingleInt64(1L).build(),
            TestAllTypes.SINGLE_INT64_FIELD_NUMBER);

    assertThat(descriptor.hasGetters()).isFalse();
    assertThat(value).isSameInstanceAs(CelLiteDescriptor.NO_GETTER);
  }

  private static MessageLiteDescriptor accessorNamesDescriptor(String name) {
    return AccessorNamesCelLiteDescriptor.getDescriptor()
        .getProtoTypeNamesToDescriptors()
        .get("dev.cel.testing.testdata." + name);
  }

  @Test
  public void protoFieldType_forNumber_roundTripAllTypes(
      @TestParameter FieldLiteDescriptor.Type type) {
    assertThat(FieldLiteDescriptor.Type.forNumber(type.getNumber())).isEqualTo(type);
  }

  @Test
  public void protoFieldType_forNumber_outOfRange_throws(
      @TestParameter({"-2147483648", "-1", "0", "19", "100", "2147483647"}) int invalidNumber) {
    IllegalArgumentException e =
        assertThrows(
            IllegalArgumentException.class,
            () -> FieldLiteDescriptor.Type.forNumber(invalidNumber));

    assertThat(e).hasMessageThat().isEqualTo("Unsupported proto type code: " + invalidNumber);
  }

  @Test
  public void fieldLiteDescriptor_nullParameters_throws() {
    assertThrows(
        NullPointerException.class,
        () ->
            new FieldLiteDescriptor(
                1,
                "field",
                null,
                EncodingType.SINGULAR,
                FieldLiteDescriptor.Type.INT32,
                false,
                ""));
    assertThrows(
        NullPointerException.class,
        () ->
            new FieldLiteDescriptor(
                1,
                "field",
                FieldLiteDescriptor.JavaType.INT,
                null,
                FieldLiteDescriptor.Type.INT32,
                false,
                ""));
    assertThrows(
        NullPointerException.class,
        () ->
            new FieldLiteDescriptor(
                1,
                "field",
                FieldLiteDescriptor.JavaType.INT,
                EncodingType.SINGULAR,
                null,
                false,
                ""));
  }
}
