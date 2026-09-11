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

import com.google.protobuf.WireFormat;
import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import dev.cel.expr.conformance.proto3.TestAllTypesCelLiteDescriptor;
import dev.cel.protobuf.CelLiteDescriptor.FieldLiteDescriptor;
import dev.cel.protobuf.CelLiteDescriptor.FieldLiteDescriptor.EncodingType;
import dev.cel.protobuf.CelLiteDescriptor.FieldLiteDescriptor.JavaType;
import dev.cel.protobuf.CelLiteDescriptor.MessageLiteDescriptor;
import java.util.Map;
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
  public void protoFieldType_numbersAndWireTypes(
      @TestParameter ProtoFieldTypeTestCase testCase) {
    assertThat(testCase.type.getNumber()).isEqualTo(testCase.expectedNumber);
    assertThat(testCase.type.toWireFormatFieldType()).isEqualTo(testCase.expectedWireType);
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
