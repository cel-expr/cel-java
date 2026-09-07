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

  @Test
  public void protoFieldType_numbersAndWireTypes() {
    assertThat(FieldLiteDescriptor.Type.DOUBLE.getNumber()).isEqualTo(1);
    assertThat(FieldLiteDescriptor.Type.DOUBLE.toWireFormatFieldType())
        .isEqualTo(WireFormat.FieldType.DOUBLE);

    assertThat(FieldLiteDescriptor.Type.FLOAT.getNumber()).isEqualTo(2);
    assertThat(FieldLiteDescriptor.Type.FLOAT.toWireFormatFieldType())
        .isEqualTo(WireFormat.FieldType.FLOAT);

    assertThat(FieldLiteDescriptor.Type.INT64.getNumber()).isEqualTo(3);
    assertThat(FieldLiteDescriptor.Type.INT64.toWireFormatFieldType())
        .isEqualTo(WireFormat.FieldType.INT64);

    assertThat(FieldLiteDescriptor.Type.UINT64.getNumber()).isEqualTo(4);
    assertThat(FieldLiteDescriptor.Type.UINT64.toWireFormatFieldType())
        .isEqualTo(WireFormat.FieldType.UINT64);

    assertThat(FieldLiteDescriptor.Type.INT32.getNumber()).isEqualTo(5);
    assertThat(FieldLiteDescriptor.Type.INT32.toWireFormatFieldType())
        .isEqualTo(WireFormat.FieldType.INT32);

    assertThat(FieldLiteDescriptor.Type.FIXED64.getNumber()).isEqualTo(6);
    assertThat(FieldLiteDescriptor.Type.FIXED64.toWireFormatFieldType())
        .isEqualTo(WireFormat.FieldType.FIXED64);

    assertThat(FieldLiteDescriptor.Type.FIXED32.getNumber()).isEqualTo(7);
    assertThat(FieldLiteDescriptor.Type.FIXED32.toWireFormatFieldType())
        .isEqualTo(WireFormat.FieldType.FIXED32);

    assertThat(FieldLiteDescriptor.Type.BOOL.getNumber()).isEqualTo(8);
    assertThat(FieldLiteDescriptor.Type.BOOL.toWireFormatFieldType())
        .isEqualTo(WireFormat.FieldType.BOOL);

    assertThat(FieldLiteDescriptor.Type.STRING.getNumber()).isEqualTo(9);
    assertThat(FieldLiteDescriptor.Type.STRING.toWireFormatFieldType())
        .isEqualTo(WireFormat.FieldType.STRING);

    assertThat(FieldLiteDescriptor.Type.GROUP.getNumber()).isEqualTo(10);
    assertThat(FieldLiteDescriptor.Type.GROUP.toWireFormatFieldType())
        .isEqualTo(WireFormat.FieldType.GROUP);

    assertThat(FieldLiteDescriptor.Type.MESSAGE.getNumber()).isEqualTo(11);
    assertThat(FieldLiteDescriptor.Type.MESSAGE.toWireFormatFieldType())
        .isEqualTo(WireFormat.FieldType.MESSAGE);

    assertThat(FieldLiteDescriptor.Type.BYTES.getNumber()).isEqualTo(12);
    assertThat(FieldLiteDescriptor.Type.BYTES.toWireFormatFieldType())
        .isEqualTo(WireFormat.FieldType.BYTES);

    assertThat(FieldLiteDescriptor.Type.UINT32.getNumber()).isEqualTo(13);
    assertThat(FieldLiteDescriptor.Type.UINT32.toWireFormatFieldType())
        .isEqualTo(WireFormat.FieldType.UINT32);

    assertThat(FieldLiteDescriptor.Type.ENUM.getNumber()).isEqualTo(14);
    assertThat(FieldLiteDescriptor.Type.ENUM.toWireFormatFieldType())
        .isEqualTo(WireFormat.FieldType.ENUM);

    assertThat(FieldLiteDescriptor.Type.SFIXED32.getNumber()).isEqualTo(15);
    assertThat(FieldLiteDescriptor.Type.SFIXED32.toWireFormatFieldType())
        .isEqualTo(WireFormat.FieldType.SFIXED32);

    assertThat(FieldLiteDescriptor.Type.SFIXED64.getNumber()).isEqualTo(16);
    assertThat(FieldLiteDescriptor.Type.SFIXED64.toWireFormatFieldType())
        .isEqualTo(WireFormat.FieldType.SFIXED64);

    assertThat(FieldLiteDescriptor.Type.SINT32.getNumber()).isEqualTo(17);
    assertThat(FieldLiteDescriptor.Type.SINT32.toWireFormatFieldType())
        .isEqualTo(WireFormat.FieldType.SINT32);

    assertThat(FieldLiteDescriptor.Type.SINT64.getNumber()).isEqualTo(18);
    assertThat(FieldLiteDescriptor.Type.SINT64.toWireFormatFieldType())
        .isEqualTo(WireFormat.FieldType.SINT64);
  }

  @Test
  public void protoFieldType_forNumber_roundTripAllTypes(
      @TestParameter FieldLiteDescriptor.Type type) {
    assertThat(FieldLiteDescriptor.Type.forNumber(type.getNumber())).isEqualTo(type);
  }

  @Test
  public void protoFieldType_forNumber_outOfRange_throws() {
    assertThrows(IllegalArgumentException.class, () -> FieldLiteDescriptor.Type.forNumber(0));
    assertThrows(IllegalArgumentException.class, () -> FieldLiteDescriptor.Type.forNumber(19));
  }

  @Test
  public void protoFieldType_isPackable() {
    assertThat(FieldLiteDescriptor.Type.INT32.isPackable()).isTrue();
    assertThat(FieldLiteDescriptor.Type.STRING.isPackable()).isFalse();
    assertThat(FieldLiteDescriptor.Type.MESSAGE.isPackable()).isFalse();
    assertThat(FieldLiteDescriptor.Type.BYTES.isPackable()).isFalse();
  }
}
