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

package dev.cel.common.values;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.primitives.UnsignedLong;
import com.google.protobuf.ByteString;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.WireFormat;
import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import dev.cel.common.exceptions.CelAttributeNotFoundException;
import dev.cel.common.internal.DefaultLiteDescriptorPool;
import dev.cel.common.internal.ProtoTimeUtils;
import dev.cel.expr.conformance.proto3.NestedTestAllTypes;
import dev.cel.expr.conformance.proto3.TestAllTypes;
import dev.cel.expr.conformance.proto3.TestAllTypes.NestedMessage;
import dev.cel.expr.conformance.proto3.TestAllTypesCelDescriptor;
import dev.cel.protobuf.CelLiteDescriptor.FieldLiteDescriptor;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(TestParameterInjector.class)
public final class RawProtoMessageLiteValueTest {

  private static final ProtoLiteCelValueConverter EMPTY_CONVERTER =
      ProtoLiteCelValueConverter.newInstance(DefaultLiteDescriptorPool.newInstance());

  @Test
  public void create_accessorsAndType() {
    ByteString bytes = ByteString.copyFromUtf8("test");

    RawProtoMessageLiteValue value =
        RawProtoMessageLiteValue.create(bytes, "custom.Message", EMPTY_CONVERTER);

    assertThat(value.toByteString()).isEqualTo(bytes);
    assertThat(value.protoTypeName()).isEqualTo("custom.Message");
    assertThat(value.value()).isSameInstanceAs(value);
    assertThat(value.celType().name()).isEqualTo("custom.Message");
  }

  @Test
  public void create_defaultsUnknownMessageTypeName() {
    ByteString bytes = ByteString.copyFromUtf8("test");

    RawProtoMessageLiteValue value = RawProtoMessageLiteValue.create(bytes, EMPTY_CONVERTER);

    assertThat(value.toByteString()).isEqualTo(bytes);
    assertThat(value.protoTypeName()).isEqualTo("cel.@unknownMessage");
    assertThat(value.celType().name()).isEqualTo("cel.@unknownMessage");
  }

  @Test
  public void create_emptyTypeName_normalizesToUnknownMessageTypeName() {
    ByteString bytes = ByteString.copyFromUtf8("test");

    RawProtoMessageLiteValue value = RawProtoMessageLiteValue.create(bytes, "", EMPTY_CONVERTER);

    assertThat(value.toByteString()).isEqualTo(bytes);
    assertThat(value.protoTypeName()).isEqualTo("cel.@unknownMessage");
    assertThat(value.celType().name()).isEqualTo("cel.@unknownMessage");
  }

  @Test
  @SuppressWarnings("SelfEquals") // Testing that equals throws even on self-comparison
  public void equals_throwsUnsupportedOperationException() {
    RawProtoMessageLiteValue value1 =
        RawProtoMessageLiteValue.create(ByteString.EMPTY, "custom.Message", EMPTY_CONVERTER);
    RawProtoMessageLiteValue value2 =
        RawProtoMessageLiteValue.create(ByteString.EMPTY, "custom.Message", EMPTY_CONVERTER);

    UnsupportedOperationException selfThrown =
        assertThrows(UnsupportedOperationException.class, () -> value1.equals(value1));
    UnsupportedOperationException otherThrown =
        assertThrows(UnsupportedOperationException.class, () -> value1.equals(value2));

    assertThat(selfThrown).hasMessageThat().isEqualTo("Message equality is not supported");
    assertThat(otherThrown).hasMessageThat().isEqualTo("Message equality is not supported");
  }

  @Test
  public void hashCode_throwsUnsupportedOperationException() {
    RawProtoMessageLiteValue value =
        RawProtoMessageLiteValue.create(ByteString.EMPTY, "custom.Message", EMPTY_CONVERTER);

    UnsupportedOperationException thrown =
        assertThrows(UnsupportedOperationException.class, value::hashCode);

    assertThat(thrown).hasMessageThat().isEqualTo("Message equality is not supported");
  }

  @Test
  public void toString_returnsTypeNameAndByteSize() {
    RawProtoMessageLiteValue value =
        RawProtoMessageLiteValue.create(
            ByteString.copyFromUtf8("secret"), "custom.Message", EMPTY_CONVERTER);

    assertThat(value.toString()).isEqualTo("WireMessageLite{protoTypeName=custom.Message, size=6}");
  }

  @Test
  public void select_throwsCelAttributeNotFoundException() {
    RawProtoMessageLiteValue value =
        RawProtoMessageLiteValue.create(ByteString.EMPTY, "custom.Message", EMPTY_CONVERTER);

    CelAttributeNotFoundException e =
        assertThrows(CelAttributeNotFoundException.class, () -> value.select("field"));

    assertThat(e)
        .hasMessageThat()
        .isEqualTo(
            "Error resolving field 'field' on 'custom.Message'. Field selection by name is not"
                + " supported on raw proto wire bytes; register its CelLiteDescriptor or enable"
                + " SelectOptimizer.");
  }

  @Test
  public void find_throwsCelAttributeNotFoundException() {
    RawProtoMessageLiteValue value =
        RawProtoMessageLiteValue.create(ByteString.EMPTY, "custom.Message", EMPTY_CONVERTER);

    CelAttributeNotFoundException e =
        assertThrows(CelAttributeNotFoundException.class, () -> value.find("field"));

    assertThat(e)
        .hasMessageThat()
        .isEqualTo(
            "Error resolving field 'field' on 'custom.Message'. Field selection by name is not"
                + " supported on raw proto wire bytes; register its CelLiteDescriptor or enable"
                + " SelectOptimizer.");
  }

  @Test
  public void isZeroValue_emptyBytes_returnsTrue() {
    RawProtoMessageLiteValue value =
        RawProtoMessageLiteValue.create(ByteString.EMPTY, EMPTY_CONVERTER);

    assertThat(value.isZeroValue()).isTrue();
  }

  @Test
  public void isZeroValue_nonEmptyBytes_returnsFalse() {
    RawProtoMessageLiteValue value =
        RawProtoMessageLiteValue.create(ByteString.copyFromUtf8("data"), EMPTY_CONVERTER);

    assertThat(value.isZeroValue()).isFalse();
  }

  @Test
  public void hasFieldByNumber_scalarField_returnsExpectedPresence() throws Exception {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    CodedOutputStream cos = CodedOutputStream.newInstance(baos);
    cos.writeInt64(1, 42L);
    cos.flush();
    RawProtoMessageLiteValue value =
        RawProtoMessageLiteValue.create(ByteString.copyFrom(baos.toByteArray()), EMPTY_CONVERTER);

    SelectField field1 =
        SelectField.create(1L, "single_int64", FieldLiteDescriptor.Type.INT64.getNumber(), 0L);
    SelectField field2 =
        SelectField.create(2L, "single_int64", FieldLiteDescriptor.Type.INT64.getNumber(), 0L);

    assertThat(value.hasFieldByNumber(field1)).isTrue();
    assertThat(value.hasFieldByNumber(field2)).isFalse();
  }

  @Test
  public void breakingFieldChange_varintWireReadAsString_hasFieldReturnsTrueButSelectThrows()
      throws Exception {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    CodedOutputStream cos = CodedOutputStream.newInstance(baos);
    cos.writeInt64(1, 42L);
    cos.flush();
    RawProtoMessageLiteValue value =
        RawProtoMessageLiteValue.create(ByteString.copyFrom(baos.toByteArray()), EMPTY_CONVERTER);

    // Reusing field 1 with an incompatible type (string instead of int64) simulates a breaking
    // proto definition change.
    SelectField breakingField =
        SelectField.create(1L, "reused_as_string", FieldLiteDescriptor.Type.STRING.getNumber(), "");

    // Presence check succeeds based on wire tag presence alone.
    assertThat(value.hasFieldByNumber(breakingField)).isTrue();

    // Field access fails at runtime when attempting to decode the incompatible wire type.
    IllegalArgumentException thrownSelect =
        assertThrows(
            IllegalArgumentException.class, () -> value.selectByFieldNumber(breakingField));
    assertThat(thrownSelect).hasCauseThat().hasMessageThat().contains("unexpected wire type");
    IllegalArgumentException thrownFind =
        assertThrows(IllegalArgumentException.class, () -> value.findByFieldNumber(breakingField));
    assertThat(thrownFind).hasCauseThat().hasMessageThat().contains("unexpected wire type");
  }

  @Test
  public void
      breakingFieldChange_lengthDelimitedWireReadAsInt64_hasFieldReturnsTrueButSelectThrows()
          throws Exception {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    CodedOutputStream cos = CodedOutputStream.newInstance(baos);
    cos.writeString(1, "hello");
    cos.flush();
    RawProtoMessageLiteValue value =
        RawProtoMessageLiteValue.create(ByteString.copyFrom(baos.toByteArray()), EMPTY_CONVERTER);

    // Reusing field 1 with an incompatible type (int64 instead of string) simulates a breaking
    // proto definition change.
    SelectField breakingField =
        SelectField.create(1L, "reused_as_int64", FieldLiteDescriptor.Type.INT64.getNumber(), 0L);

    // Presence check succeeds based on wire tag presence alone.
    assertThat(value.hasFieldByNumber(breakingField)).isTrue();

    // Field access fails at runtime when attempting to decode the incompatible wire type.
    IllegalArgumentException thrownSelect =
        assertThrows(
            IllegalArgumentException.class, () -> value.selectByFieldNumber(breakingField));
    assertThat(thrownSelect).hasCauseThat().hasMessageThat().contains("unexpected wire type");
    IllegalArgumentException thrownFind =
        assertThrows(IllegalArgumentException.class, () -> value.findByFieldNumber(breakingField));
    assertThat(thrownFind).hasCauseThat().hasMessageThat().contains("unexpected wire type");
  }

  @Test
  public void selectByFieldNumber_presentOnWire_decoded() throws Exception {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    CodedOutputStream cos = CodedOutputStream.newInstance(baos);
    cos.writeString(14, "hello");
    cos.flush();
    RawProtoMessageLiteValue raw =
        RawProtoMessageLiteValue.create(
            ByteString.copyFrom(baos.toByteArray()),
            "cel.expr.conformance.proto3.TestAllTypes",
            EMPTY_CONVERTER);

    Object val = raw.selectByFieldNumber(SelectField.create(14L, "single_string", 9, ""));

    assertThat(val).isEqualTo("hello");
  }

  @Test
  public void selectByFieldNumber_absentWithDefaultValue_returnsDefault() {
    RawProtoMessageLiteValue raw =
        RawProtoMessageLiteValue.create(
            ByteString.EMPTY, "cel.expr.conformance.proto3.TestAllTypes", EMPTY_CONVERTER);

    Object val = raw.selectByFieldNumber(SelectField.create(14L, "single_string", 9, "default"));

    assertThat(val).isEqualTo("default");
  }

  @Test
  public void hasFieldByNumber_wirePresent_returnsTrue() throws Exception {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    CodedOutputStream cos = CodedOutputStream.newInstance(baos);
    cos.writeString(TestAllTypes.SINGLE_STRING_FIELD_NUMBER, "present");
    cos.flush();
    RawProtoMessageLiteValue raw =
        RawProtoMessageLiteValue.create(
            ByteString.copyFrom(baos.toByteArray()),
            "cel.expr.conformance.proto3.TestAllTypes",
            EMPTY_CONVERTER);

    assertThat(
            raw.hasFieldByNumber(
                SelectField.create(TestAllTypes.SINGLE_STRING_FIELD_NUMBER, "single_string")))
        .isTrue();
  }

  @Test
  public void hasFieldByNumber_wireAbsent_returnsFalse() {
    RawProtoMessageLiteValue raw =
        RawProtoMessageLiteValue.create(
            ByteString.EMPTY, "cel.expr.conformance.proto3.TestAllTypes", EMPTY_CONVERTER);

    assertThat(
            raw.hasFieldByNumber(
                SelectField.create(TestAllTypes.SINGLE_STRING_FIELD_NUMBER, "single_string")))
        .isFalse();
  }

  @Test
  public void hasFieldByNumber_emptyPackedRepeated_returnsFalse() throws Exception {
    ByteString wire =
        encode(out -> out.writeBytes(TestAllTypes.REPEATED_INT32_FIELD_NUMBER, ByteString.EMPTY));
    RawProtoMessageLiteValue raw =
        RawProtoMessageLiteValue.create(
            wire, "cel.expr.conformance.proto3.TestAllTypes", EMPTY_CONVERTER);
    SelectField selectField =
        SelectField.create(
            TestAllTypes.REPEATED_INT32_FIELD_NUMBER,
            "repeated_int32",
            FieldLiteDescriptor.Type.INT32.getNumber(),
            ImmutableList.of());

    assertThat(raw.hasFieldByNumber(selectField)).isFalse();
  }

  @Test
  public void hasFieldByNumber_nonEmptyPackedRepeated_returnsTrue() throws Exception {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    CodedOutputStream cos = CodedOutputStream.newInstance(baos);
    ByteArrayOutputStream packed = new ByteArrayOutputStream();
    CodedOutputStream packedCos = CodedOutputStream.newInstance(packed);
    packedCos.writeInt32NoTag(42);
    packedCos.flush();
    cos.writeBytes(
        TestAllTypes.REPEATED_INT32_FIELD_NUMBER, ByteString.copyFrom(packed.toByteArray()));
    cos.flush();
    RawProtoMessageLiteValue raw =
        RawProtoMessageLiteValue.create(
            ByteString.copyFrom(baos.toByteArray()),
            "cel.expr.conformance.proto3.TestAllTypes",
            EMPTY_CONVERTER);

    assertThat(
            raw.hasFieldByNumber(
                SelectField.create(TestAllTypes.REPEATED_INT32_FIELD_NUMBER, "repeated_int32")))
        .isTrue();
  }

  @Test
  public void hasFieldByNumber_emptyByteStringOnScalarPackableField_returnsTrue() throws Exception {
    ByteString wire =
        encode(out -> out.writeByteArray(TestAllTypes.SINGLE_INT32_FIELD_NUMBER, new byte[0]));
    RawProtoMessageLiteValue raw =
        RawProtoMessageLiteValue.create(
            wire, "cel.expr.conformance.proto3.TestAllTypes", EMPTY_CONVERTER);
    SelectField selectField =
        SelectField.create(
            TestAllTypes.SINGLE_INT32_FIELD_NUMBER,
            "single_int32",
            FieldLiteDescriptor.Type.INT32.getNumber(),
            0);

    assertThat(raw.hasFieldByNumber(selectField)).isTrue();
  }

  @Test
  public void findByFieldNumber_intermediatePresent_returnsSubmessage() throws Exception {
    ByteArrayOutputStream subBaos1 = new ByteArrayOutputStream();
    CodedOutputStream subCos1 = CodedOutputStream.newInstance(subBaos1);
    subCos1.writeInt32(1, 42);
    subCos1.flush();

    ByteArrayOutputStream subBaos2 = new ByteArrayOutputStream();
    CodedOutputStream subCos2 = CodedOutputStream.newInstance(subBaos2);
    subCos2.writeInt32(2, 84);
    subCos2.flush();

    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    CodedOutputStream cos = CodedOutputStream.newInstance(baos);
    cos.writeBytes(21, ByteString.copyFrom(subBaos1.toByteArray()));
    cos.writeBytes(21, ByteString.copyFrom(subBaos2.toByteArray()));
    cos.flush();
    RawProtoMessageLiteValue raw =
        RawProtoMessageLiteValue.create(
            ByteString.copyFrom(baos.toByteArray()),
            "cel.expr.conformance.proto3.TestAllTypes",
            EMPTY_CONVERTER);

    Optional<Object> nav = raw.findByFieldNumber(SelectField.create(21L, "single_nested_message"));

    Optional<RawProtoMessageLiteValue> submessage = nav.map(RawProtoMessageLiteValue.class::cast);
    assertThat(submessage.map(RawProtoMessageLiteValue::toByteString))
        .hasValue(
            ByteString.copyFrom(subBaos1.toByteArray())
                .concat(ByteString.copyFrom(subBaos2.toByteArray())));
    assertThat(submessage.map(RawProtoMessageLiteValue::protoTypeName))
        .hasValue("cel.@unknownMessage");
  }

  @Test
  public void findByFieldNumber_intermediateAbsent_returnsEmpty() {
    RawProtoMessageLiteValue raw =
        RawProtoMessageLiteValue.create(
            ByteString.EMPTY, "cel.expr.conformance.proto3.TestAllTypes", EMPTY_CONVERTER);

    Optional<Object> nav = raw.findByFieldNumber(SelectField.create(999L, "absent"));

    assertThat(nav).isEmpty();
  }

  @SuppressWarnings("ImmutableEnumChecker") // Test only
  private enum SelectByFieldNumberTestCase {
    INT64(
        SelectField.create(
            TestAllTypes.SINGLE_INT64_FIELD_NUMBER,
            "single_int64",
            FieldLiteDescriptor.Type.INT64.getNumber(),
            0L),
        99L),
    STRING(
        SelectField.create(
            TestAllTypes.SINGLE_STRING_FIELD_NUMBER,
            "single_string",
            FieldLiteDescriptor.Type.STRING.getNumber(),
            ""),
        "hello"),
    MAP_STRING_STRING(
        SelectField.createMap(
            TestAllTypes.MAP_STRING_STRING_FIELD_NUMBER,
            "map_string_string",
            SelectField.MapEntrySpec.create(9, 9)),
        ImmutableMap.of("k1", "v1", "k2", "v2")),
    MAP_INT32_BYTES(
        SelectField.createMap(
            TestAllTypes.MAP_INT32_BYTES_FIELD_NUMBER,
            "map_int32_bytes",
            SelectField.MapEntrySpec.create(5, 12)),
        ImmutableMap.of(
            0L, CelByteString.copyFromUtf8("val_for_default_key"), 42L, CelByteString.EMPTY)),
    DURATION(
        SelectField.create(
            TestAllTypes.SINGLE_DURATION_FIELD_NUMBER,
            "single_duration",
            FieldLiteDescriptor.Type.MESSAGE.getNumber(),
            Duration.ZERO,
            "google.protobuf.Duration"),
        Duration.ofSeconds(10L, 500L));

    private final SelectField selectField;
    private final Object expectedValue;

    private SelectByFieldNumberTestCase(SelectField selectField, Object expectedValue) {
      this.selectField = selectField;
      this.expectedValue = expectedValue;
    }
  }

  @Test
  public void selectByFieldNumber_decodesExpectedValue(
      @TestParameter SelectByFieldNumberTestCase testCase) {
    TestAllTypes proto =
        TestAllTypes.newBuilder()
            .setSingleInt64(99L)
            .setSingleString("hello")
            .putMapStringString("k1", "v1")
            .putMapStringString("k2", "v2")
            .putMapInt32Bytes(0, ByteString.copyFromUtf8("val_for_default_key"))
            .putMapInt32Bytes(42, ByteString.EMPTY)
            .setSingleDuration(ProtoTimeUtils.toProtoDuration(Duration.ofSeconds(10L, 500L)))
            .build();
    RawProtoMessageLiteValue raw =
        RawProtoMessageLiteValue.create(
            proto.toByteString(), "cel.expr.conformance.proto3.TestAllTypes", EMPTY_CONVERTER);

    Object selected = raw.selectByFieldNumber(testCase.selectField);

    assertThat(selected).isEqualTo(testCase.expectedValue);
  }

  @Test
  public void findByFieldNumber_scalarFieldWithoutTypeCode_throwsIllegalArgumentException() {
    TestAllTypes proto = TestAllTypes.newBuilder().setSingleInt64(99L).build();
    RawProtoMessageLiteValue raw =
        RawProtoMessageLiteValue.create(
            proto.toByteString(), "cel.expr.conformance.proto3.TestAllTypes", EMPTY_CONVERTER);
    SelectField field = SelectField.create(TestAllTypes.SINGLE_INT64_FIELD_NUMBER, "single_int64");

    IllegalArgumentException thrown =
        assertThrows(IllegalArgumentException.class, () -> raw.findByFieldNumber(field));

    assertThat(thrown).hasCauseThat().hasMessageThat().contains("MESSAGE has unexpected wire type");
  }

  @Test
  public void findByFieldNumber_typedFieldWithoutDescriptor_returnsSelectedValue() {
    TestAllTypes proto = TestAllTypes.newBuilder().setSingleUint32(123).build();
    RawProtoMessageLiteValue raw =
        RawProtoMessageLiteValue.create(
            proto.toByteString(), "cel.expr.conformance.proto3.TestAllTypes", EMPTY_CONVERTER);

    Optional<Object> nav =
        raw.findByFieldNumber(
            SelectField.create(
                TestAllTypes.SINGLE_UINT32_FIELD_NUMBER,
                "single_uint32",
                FieldLiteDescriptor.Type.UINT32.getNumber(),
                0L));

    assertThat(nav).hasValue(UnsignedLong.fromLongBits(123L));
  }

  @Test
  public void selectByFieldNumber_absentMessageFieldWithoutDescriptor_returnsUnknownMessage() {
    RawProtoMessageLiteValue raw =
        RawProtoMessageLiteValue.create(ByteString.EMPTY, EMPTY_CONVERTER);

    Object selected =
        raw.selectByFieldNumber(
            SelectField.create(
                21L, "single_nested_message", FieldLiteDescriptor.Type.MESSAGE.getNumber()));

    assertThat(selected).isInstanceOf(RawProtoMessageLiteValue.class);
    RawProtoMessageLiteValue message = (RawProtoMessageLiteValue) selected;
    assertThat(message.toByteString()).isEqualTo(ByteString.EMPTY);
    assertThat(message.protoTypeName()).isEqualTo("cel.@unknownMessage");
  }

  @Test
  public void selectByFieldNumber_unknownMapFieldWithMapEntrySpec_success(
      @TestParameter({"", "cel.@unknownMessage", "cel.expr.conformance.proto3.TestAllTypes"})
          String receiverTypeName) {
    TestAllTypes proto =
        TestAllTypes.newBuilder()
            .putMapStringString("key1", "val1")
            .putMapStringString("key2", "val2")
            .build();
    RawProtoMessageLiteValue raw =
        RawProtoMessageLiteValue.create(proto.toByteString(), receiverTypeName, EMPTY_CONVERTER);
    SelectField field =
        SelectField.createMap(
            TestAllTypes.MAP_STRING_STRING_FIELD_NUMBER,
            "map_string_string",
            SelectField.MapEntrySpec.create(9, 9));

    Object result = raw.selectByFieldNumber(field);

    assertThat(result).isEqualTo(ImmutableMap.of("key1", "val1", "key2", "val2"));
  }

  @Test
  public void
      selectByFieldNumber_mapFieldWithMessageValueWithoutDescriptor_returnsMapOfRawMessage() {
    TestAllTypes proto =
        TestAllTypes.newBuilder()
            .putMapInt64NestedType(
                42L,
                NestedTestAllTypes.newBuilder()
                    .setPayload(TestAllTypes.newBuilder().setSingleInt64(100L))
                    .build())
            .build();
    RawProtoMessageLiteValue raw =
        RawProtoMessageLiteValue.create(
            proto.toByteString(), "cel.expr.conformance.proto3.TestAllTypes", EMPTY_CONVERTER);
    SelectField field = newMapInt64NestedTypeField();

    Object result = raw.selectByFieldNumber(field);

    assertThat(result).isInstanceOf(Map.class);
    Map<?, ?> map = (Map<?, ?>) result;
    assertThat(map).containsKey(42L);
    Object val = map.get(42L);
    assertThat(val).isInstanceOf(RawProtoMessageLiteValue.class);
    RawProtoMessageLiteValue nested = (RawProtoMessageLiteValue) val;
    assertThat(nested.celType().name()).isEqualTo("cel.expr.conformance.proto3.NestedTestAllTypes");
  }

  @Test
  public void selectByFieldNumber_unknownMapFieldWithMessageValue_multiHopTraversalSuccess() {
    TestAllTypes proto =
        TestAllTypes.newBuilder()
            .putMapInt64NestedType(
                42L,
                NestedTestAllTypes.newBuilder()
                    .setPayload(TestAllTypes.newBuilder().setSingleInt64(100L))
                    .build())
            .build();
    RawProtoMessageLiteValue raw =
        RawProtoMessageLiteValue.create(
            proto.toByteString(), "cel.expr.conformance.proto3.TestAllTypes", EMPTY_CONVERTER);
    SelectField mapField =
        SelectField.createMap(
            TestAllTypes.MAP_INT64_NESTED_TYPE_FIELD_NUMBER,
            "map_int64_nested_type",
            SelectField.MapEntrySpec.create(
                FieldLiteDescriptor.Type.INT64.getNumber(), SelectField.MESSAGE_TYPE_CODE),
            "cel.expr.conformance.proto3.NestedTestAllTypes");
    SelectField payloadField =
        SelectField.create(
            NestedTestAllTypes.PAYLOAD_FIELD_NUMBER,
            "payload",
            SelectField.MESSAGE_TYPE_CODE,
            null,
            "cel.expr.conformance.proto3.TestAllTypes");
    SelectField int64Field =
        SelectField.create(
            TestAllTypes.SINGLE_INT64_FIELD_NUMBER,
            "single_int64",
            FieldLiteDescriptor.Type.INT64.getNumber(),
            0L);

    Map<?, ?> map = (Map<?, ?>) raw.selectByFieldNumber(mapField);
    RawProtoMessageLiteValue nested = (RawProtoMessageLiteValue) map.get(42L);
    RawProtoMessageLiteValue payload =
        (RawProtoMessageLiteValue) nested.selectByFieldNumber(payloadField);
    Object selectedInt64 = payload.selectByFieldNumber(int64Field);

    assertThat(selectedInt64).isEqualTo(100L);
  }

  @Test
  public void selectByFieldNumber_absentMapFieldWithMapEntrySpec_returnsEmptyMap() {
    RawProtoMessageLiteValue raw =
        RawProtoMessageLiteValue.create(
            ByteString.EMPTY, "cel.expr.conformance.proto3.TestAllTypes", EMPTY_CONVERTER);
    SelectField field =
        SelectField.createMap(
            TestAllTypes.MAP_STRING_STRING_FIELD_NUMBER,
            "map_string_string",
            SelectField.MapEntrySpec.create(9, 9));

    Object result = raw.selectByFieldNumber(field);

    assertThat(result).isEqualTo(ImmutableMap.of());
  }

  @Test
  public void selectByFieldNumber_mapEntrySpecMissingKeyOrValue_usesTypedZeroDefaults()
      throws Exception {
    ByteString valueOnlyEntry = encode(out -> out.writeDouble(2, 1.5d));
    ByteString keyOnlyEntry = encode(out -> out.writeUInt32(1, 7));
    ByteString wire =
        encode(
            out -> {
              out.writeBytes(TestAllTypes.MAP_UINT32_DOUBLE_FIELD_NUMBER, valueOnlyEntry);
              out.writeBytes(TestAllTypes.MAP_UINT32_DOUBLE_FIELD_NUMBER, keyOnlyEntry);
            });
    RawProtoMessageLiteValue raw =
        RawProtoMessageLiteValue.create(
            wire, "cel.expr.conformance.proto3.TestAllTypes", EMPTY_CONVERTER);
    SelectField field =
        SelectField.createMap(
            TestAllTypes.MAP_UINT32_DOUBLE_FIELD_NUMBER,
            "map_uint32_double",
            SelectField.MapEntrySpec.create(
                FieldLiteDescriptor.Type.UINT32.getNumber(),
                FieldLiteDescriptor.Type.DOUBLE.getNumber()));

    Object result = raw.selectByFieldNumber(field);

    assertThat(result)
        .isEqualTo(ImmutableMap.of(UnsignedLong.ZERO, 1.5d, UnsignedLong.valueOf(7), 0.0d));
  }

  @SuppressWarnings("ImmutableEnumChecker") // Test only
  private enum MapScalarDefaultTestCase {
    BOOL(FieldLiteDescriptor.Type.BOOL, false),
    INT32(FieldLiteDescriptor.Type.INT32, 0L),
    SINT64(FieldLiteDescriptor.Type.SINT64, 0L),
    ENUM(FieldLiteDescriptor.Type.ENUM, 0L),
    FIXED32(FieldLiteDescriptor.Type.FIXED32, UnsignedLong.ZERO),
    UINT64(FieldLiteDescriptor.Type.UINT64, UnsignedLong.ZERO),
    FLOAT(FieldLiteDescriptor.Type.FLOAT, 0.0d),
    STRING(FieldLiteDescriptor.Type.STRING, ""),
    BYTES(FieldLiteDescriptor.Type.BYTES, CelByteString.EMPTY);

    private final FieldLiteDescriptor.Type valueType;
    private final Object expectedDefault;

    MapScalarDefaultTestCase(FieldLiteDescriptor.Type valueType, Object expectedDefault) {
      this.valueType = valueType;
      this.expectedDefault = expectedDefault;
    }
  }

  @Test
  public void selectByFieldNumber_mapEntrySpecMissingScalarValue_usesTypedZeroDefault(
      @TestParameter MapScalarDefaultTestCase testCase) throws Exception {
    ByteString keyOnlyEntry = encode(out -> out.writeString(1, "k"));
    ByteString wire = encode(out -> out.writeBytes(1, keyOnlyEntry));
    RawProtoMessageLiteValue raw =
        RawProtoMessageLiteValue.create(
            wire, "cel.expr.conformance.proto3.TestAllTypes", EMPTY_CONVERTER);
    SelectField field =
        SelectField.createMap(
            1L,
            "unknown_map",
            SelectField.MapEntrySpec.create(
                FieldLiteDescriptor.Type.STRING.getNumber(), testCase.valueType.getNumber()));

    Object result = raw.selectByFieldNumber(field);

    assertThat(result).isEqualTo(ImmutableMap.of("k", testCase.expectedDefault));
  }

  @Test
  public void selectByFieldNumber_mapEntrySpecMissingMessageValue_returnsEmptyMessage()
      throws Exception {
    ByteString keyOnlyEntry = encode(out -> out.writeInt64(1, 42L));
    ByteString wire =
        encode(
            out -> out.writeBytes(TestAllTypes.MAP_INT64_NESTED_TYPE_FIELD_NUMBER, keyOnlyEntry));
    RawProtoMessageLiteValue raw =
        RawProtoMessageLiteValue.create(
            wire, "cel.expr.conformance.proto3.TestAllTypes", EMPTY_CONVERTER);

    Object result = raw.selectByFieldNumber(newMapInt64NestedTypeField());

    ImmutableMap<?, ?> map = (ImmutableMap<?, ?>) result;
    assertThat(map.keySet()).containsExactly(42L);
    RawProtoMessageLiteValue messageValue = (RawProtoMessageLiteValue) map.get(42L);
    assertThat(messageValue.toByteString()).isEqualTo(ByteString.EMPTY);
    assertThat(messageValue.protoTypeName())
        .isEqualTo("cel.expr.conformance.proto3.NestedTestAllTypes");
  }

  @Test
  public void selectByFieldNumber_mapEntrySpecRepeatedMessageValue_mergesFragments()
      throws Exception {
    ByteString fragment1 =
        NestedTestAllTypes.newBuilder()
            .setPayload(TestAllTypes.newBuilder().setSingleInt64(100L))
            .build()
            .toByteString();
    ByteString fragment2 =
        NestedTestAllTypes.newBuilder()
            .setPayload(TestAllTypes.newBuilder().setSingleBool(true))
            .build()
            .toByteString();
    ByteString entry =
        encode(
            out -> {
              out.writeInt64(1, 42L);
              out.writeBytes(2, fragment1);
              out.writeBytes(2, fragment2);
            });
    ByteString wire =
        encode(out -> out.writeBytes(TestAllTypes.MAP_INT64_NESTED_TYPE_FIELD_NUMBER, entry));
    RawProtoMessageLiteValue raw =
        RawProtoMessageLiteValue.create(
            wire, "cel.expr.conformance.proto3.TestAllTypes", EMPTY_CONVERTER);

    Object result = raw.selectByFieldNumber(newMapInt64NestedTypeField());

    ImmutableMap<?, ?> map = (ImmutableMap<?, ?>) result;
    assertThat(map.keySet()).containsExactly(42L);
    RawProtoMessageLiteValue messageValue = (RawProtoMessageLiteValue) map.get(42L);
    assertThat(messageValue.toByteString()).isEqualTo(fragment1.concat(fragment2));
    assertThat(messageValue.protoTypeName())
        .isEqualTo("cel.expr.conformance.proto3.NestedTestAllTypes");
  }

  @Test
  public void selectByFieldNumber_mapEntrySpecRepeatedScalarValue_lastOneWins() throws Exception {
    ByteString entry =
        encode(
            out -> {
              out.writeString(1, "k");
              out.writeString(2, "first");
              out.writeString(2, "last");
            });
    ByteString wire =
        encode(out -> out.writeBytes(TestAllTypes.MAP_STRING_STRING_FIELD_NUMBER, entry));
    RawProtoMessageLiteValue raw =
        RawProtoMessageLiteValue.create(
            wire, "cel.expr.conformance.proto3.TestAllTypes", EMPTY_CONVERTER);
    SelectField field =
        SelectField.createMap(
            TestAllTypes.MAP_STRING_STRING_FIELD_NUMBER,
            "map_string_string",
            SelectField.MapEntrySpec.create(9, 9));

    Object result = raw.selectByFieldNumber(field);

    assertThat(result).isEqualTo(ImmutableMap.of("k", "last"));
  }

  @Test
  public void selectByFieldNumber_mapEntrySpecDuplicateKeysAcrossEntries_lastEntryWins()
      throws Exception {
    ByteString entry1 =
        encode(
            out -> {
              out.writeString(1, "dup");
              out.writeString(2, "first");
            });
    ByteString entry2 =
        encode(
            out -> {
              out.writeString(1, "dup");
              out.writeString(2, "second");
            });
    ByteString wire =
        encode(
            out -> {
              out.writeBytes(TestAllTypes.MAP_STRING_STRING_FIELD_NUMBER, entry1);
              out.writeBytes(TestAllTypes.MAP_STRING_STRING_FIELD_NUMBER, entry2);
            });
    RawProtoMessageLiteValue raw =
        RawProtoMessageLiteValue.create(
            wire, "cel.expr.conformance.proto3.TestAllTypes", EMPTY_CONVERTER);
    SelectField field =
        SelectField.createMap(
            TestAllTypes.MAP_STRING_STRING_FIELD_NUMBER,
            "map_string_string",
            SelectField.MapEntrySpec.create(9, 9));

    Object result = raw.selectByFieldNumber(field);

    assertThat(result).isEqualTo(ImmutableMap.of("dup", "second"));
  }

  @Test
  public void selectByFieldNumber_mapEntrySpecUnknownFieldNumber_isSkipped() throws Exception {
    ByteString entry =
        encode(
            out -> {
              out.writeString(1, "k");
              out.writeString(2, "v");
              out.writeString(3, "unexpected");
            });
    ByteString wire =
        encode(out -> out.writeBytes(TestAllTypes.MAP_STRING_STRING_FIELD_NUMBER, entry));
    RawProtoMessageLiteValue raw =
        RawProtoMessageLiteValue.create(
            wire, "cel.expr.conformance.proto3.TestAllTypes", EMPTY_CONVERTER);
    SelectField field =
        SelectField.createMap(
            TestAllTypes.MAP_STRING_STRING_FIELD_NUMBER,
            "map_string_string",
            SelectField.MapEntrySpec.create(9, 9));

    Object result = raw.selectByFieldNumber(field);

    assertThat(result).isEqualTo(ImmutableMap.of("k", "v"));
  }

  private static SelectField newMapInt64NestedTypeField() {
    return SelectField.createMap(
        TestAllTypes.MAP_INT64_NESTED_TYPE_FIELD_NUMBER,
        "map_int64_nested_type",
        SelectField.MapEntrySpec.create(
            FieldLiteDescriptor.Type.INT64.getNumber(), SelectField.MESSAGE_TYPE_CODE),
        "cel.expr.conformance.proto3.NestedTestAllTypes");
  }

  private interface WireWriter {
    void write(CodedOutputStream out) throws IOException;
  }

  private static ByteString encode(WireWriter writer) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    CodedOutputStream out = CodedOutputStream.newInstance(bytes);
    writer.write(out);
    out.flush();
    return ByteString.copyFrom(bytes.toByteArray());
  }

  @SuppressWarnings("ImmutableEnumChecker") // Test only
  private enum WellKnownFieldWithoutDescriptorTestCase {
    DURATION(
        TestAllTypes.newBuilder()
            .setSingleDuration(ProtoTimeUtils.toProtoDuration(Duration.ofSeconds(120L, 500L)))
            .build(),
        SelectField.create(
            TestAllTypes.SINGLE_DURATION_FIELD_NUMBER,
            "single_duration",
            FieldLiteDescriptor.Type.MESSAGE.getNumber(),
            Duration.ZERO,
            "google.protobuf.Duration"),
        Duration.ofSeconds(120L, 500L),
        Duration.ZERO),
    TIMESTAMP(
        TestAllTypes.newBuilder()
            .setSingleTimestamp(
                ProtoTimeUtils.toProtoTimestamp(Instant.ofEpochSecond(1700000000L, 123456789L)))
            .build(),
        SelectField.create(
            TestAllTypes.SINGLE_TIMESTAMP_FIELD_NUMBER,
            "single_timestamp",
            FieldLiteDescriptor.Type.MESSAGE.getNumber(),
            Instant.EPOCH,
            "google.protobuf.Timestamp"),
        Instant.ofEpochSecond(1700000000L, 123456789L),
        Instant.EPOCH);

    private final TestAllTypes populatedProto;
    private final SelectField selectField;
    private final Object expectedPopulatedValue;
    private final Object expectedDefaultValue;

    WellKnownFieldWithoutDescriptorTestCase(
        TestAllTypes populatedProto,
        SelectField selectField,
        Object expectedPopulatedValue,
        Object expectedDefaultValue) {
      this.populatedProto = populatedProto;
      this.selectField = selectField;
      this.expectedPopulatedValue = expectedPopulatedValue;
      this.expectedDefaultValue = expectedDefaultValue;
    }
  }

  @Test
  public void selectByFieldNumber_populatedWellKnownFieldWithoutDescriptor_decodesValue(
      @TestParameter WellKnownFieldWithoutDescriptorTestCase testCase) {
    RawProtoMessageLiteValue populatedRaw =
        RawProtoMessageLiteValue.create(
            testCase.populatedProto.toByteString(),
            "cel.expr.conformance.proto3.TestAllTypes",
            EMPTY_CONVERTER);

    Object populatedSelected = populatedRaw.selectByFieldNumber(testCase.selectField);

    assertThat(populatedSelected).isEqualTo(testCase.expectedPopulatedValue);
  }

  @Test
  public void selectByFieldNumber_absentWellKnownFieldWithoutDescriptor_returnsDefault(
      @TestParameter WellKnownFieldWithoutDescriptorTestCase testCase) {
    RawProtoMessageLiteValue emptyRaw =
        RawProtoMessageLiteValue.create(
            TestAllTypes.getDefaultInstance().toByteString(),
            "cel.expr.conformance.proto3.TestAllTypes",
            EMPTY_CONVERTER);

    Object emptySelected = emptyRaw.selectByFieldNumber(testCase.selectField);

    assertThat(emptySelected).isEqualTo(testCase.expectedDefaultValue);
  }

  @Test
  public void findByFieldNumber_populatedWellKnownFieldWithoutDescriptor_returnsPresentOptional(
      @TestParameter WellKnownFieldWithoutDescriptorTestCase testCase) {
    RawProtoMessageLiteValue populatedRaw =
        RawProtoMessageLiteValue.create(
            testCase.populatedProto.toByteString(),
            "cel.expr.conformance.proto3.TestAllTypes",
            EMPTY_CONVERTER);

    Optional<Object> populatedFound = populatedRaw.findByFieldNumber(testCase.selectField);

    assertThat(populatedFound).hasValue(testCase.expectedPopulatedValue);
  }

  @Test
  public void findByFieldNumber_absentWellKnownFieldWithoutDescriptor_returnsEmptyOptional(
      @TestParameter WellKnownFieldWithoutDescriptorTestCase testCase) {
    RawProtoMessageLiteValue emptyRaw =
        RawProtoMessageLiteValue.create(
            TestAllTypes.getDefaultInstance().toByteString(),
            "cel.expr.conformance.proto3.TestAllTypes",
            EMPTY_CONVERTER);

    Optional<Object> emptyFound = emptyRaw.findByFieldNumber(testCase.selectField);

    assertThat(emptyFound).isEmpty();
  }

  @Test
  public void selectByFieldNumber_negativeInt32Varint_decodesSignedIntCorrectly() {
    TestAllTypes proto = TestAllTypes.newBuilder().setSingleInt32(-42).build();
    RawProtoMessageLiteValue raw =
        RawProtoMessageLiteValue.create(
            proto.toByteString(), "cel.expr.conformance.proto3.TestAllTypes", EMPTY_CONVERTER);
    SelectField field =
        SelectField.create(
            TestAllTypes.SINGLE_INT32_FIELD_NUMBER,
            "single_int32",
            FieldLiteDescriptor.Type.INT32.getNumber(),
            0L);

    Object result = raw.selectByFieldNumber(field);

    assertThat(result).isEqualTo(-42L);
  }

  @Test
  public void selectByFieldNumber_negativeInt32MinValue_decodesSignedIntCorrectly() {
    TestAllTypes proto = TestAllTypes.newBuilder().setSingleInt32(Integer.MIN_VALUE).build();
    RawProtoMessageLiteValue raw =
        RawProtoMessageLiteValue.create(
            proto.toByteString(), "cel.expr.conformance.proto3.TestAllTypes", EMPTY_CONVERTER);
    SelectField field =
        SelectField.create(
            TestAllTypes.SINGLE_INT32_FIELD_NUMBER,
            "single_int32",
            FieldLiteDescriptor.Type.INT32.getNumber(),
            0L);

    Object result = raw.selectByFieldNumber(field);

    assertThat(result).isEqualTo((long) Integer.MIN_VALUE);
  }

  @Test
  public void selectByFieldNumber_unpackedAndPackedRepeatedInt64_decodeIdentically()
      throws Exception {
    ByteArrayOutputStream unpackedBaos = new ByteArrayOutputStream();
    CodedOutputStream unpackedCos = CodedOutputStream.newInstance(unpackedBaos);
    unpackedCos.writeInt64(TestAllTypes.REPEATED_INT64_FIELD_NUMBER, 10L);
    unpackedCos.writeInt64(TestAllTypes.REPEATED_INT64_FIELD_NUMBER, 20L);
    unpackedCos.writeInt64(TestAllTypes.REPEATED_INT64_FIELD_NUMBER, 30L);
    unpackedCos.flush();
    RawProtoMessageLiteValue unpackedRaw =
        RawProtoMessageLiteValue.create(
            ByteString.copyFrom(unpackedBaos.toByteArray()),
            "cel.expr.conformance.proto3.TestAllTypes",
            EMPTY_CONVERTER);
    TestAllTypes packedProto =
        TestAllTypes.newBuilder()
            .addRepeatedInt64(10L)
            .addRepeatedInt64(20L)
            .addRepeatedInt64(30L)
            .build();
    RawProtoMessageLiteValue packedRaw =
        RawProtoMessageLiteValue.create(
            packedProto.toByteString(),
            "cel.expr.conformance.proto3.TestAllTypes",
            EMPTY_CONVERTER);
    SelectField field =
        SelectField.create(
            TestAllTypes.REPEATED_INT64_FIELD_NUMBER,
            "repeated_int64",
            FieldLiteDescriptor.Type.INT64.getNumber(),
            ImmutableList.of());

    Object unpackedResult = unpackedRaw.selectByFieldNumber(field);
    Object packedResult = packedRaw.selectByFieldNumber(field);

    assertThat(unpackedResult).isEqualTo(ImmutableList.of(10L, 20L, 30L));
    assertThat(packedResult).isEqualTo(ImmutableList.of(10L, 20L, 30L));
  }

  @Test
  public void hasFieldByNumber_unpackedAndPackedRepeatedInt64_returnsTrue() throws Exception {
    ByteArrayOutputStream unpackedBaos = new ByteArrayOutputStream();
    CodedOutputStream unpackedCos = CodedOutputStream.newInstance(unpackedBaos);
    unpackedCos.writeInt64(TestAllTypes.REPEATED_INT64_FIELD_NUMBER, 10L);
    unpackedCos.flush();
    RawProtoMessageLiteValue unpackedRaw =
        RawProtoMessageLiteValue.create(
            ByteString.copyFrom(unpackedBaos.toByteArray()),
            "cel.expr.conformance.proto3.TestAllTypes",
            EMPTY_CONVERTER);
    TestAllTypes packedProto = TestAllTypes.newBuilder().addRepeatedInt64(10L).build();
    RawProtoMessageLiteValue packedRaw =
        RawProtoMessageLiteValue.create(
            packedProto.toByteString(),
            "cel.expr.conformance.proto3.TestAllTypes",
            EMPTY_CONVERTER);
    SelectField hasField =
        SelectField.create(TestAllTypes.REPEATED_INT64_FIELD_NUMBER, "repeated_int64");

    boolean unpackedPresent = unpackedRaw.hasFieldByNumber(hasField);
    boolean packedPresent = packedRaw.hasFieldByNumber(hasField);

    assertThat(unpackedPresent).isTrue();
    assertThat(packedPresent).isTrue();
  }

  @Test
  public void findByFieldNumber_unpackedAndPackedRepeatedInt64_returnsExpectedValue()
      throws Exception {
    ByteArrayOutputStream unpackedBaos = new ByteArrayOutputStream();
    CodedOutputStream unpackedCos = CodedOutputStream.newInstance(unpackedBaos);
    unpackedCos.writeInt64(TestAllTypes.REPEATED_INT64_FIELD_NUMBER, 10L);
    unpackedCos.flush();
    RawProtoMessageLiteValue unpackedRaw =
        RawProtoMessageLiteValue.create(
            ByteString.copyFrom(unpackedBaos.toByteArray()),
            "cel.expr.conformance.proto3.TestAllTypes",
            EMPTY_CONVERTER);
    TestAllTypes packedProto = TestAllTypes.newBuilder().addRepeatedInt64(10L).build();
    RawProtoMessageLiteValue packedRaw =
        RawProtoMessageLiteValue.create(
            packedProto.toByteString(),
            "cel.expr.conformance.proto3.TestAllTypes",
            EMPTY_CONVERTER);
    SelectField field =
        SelectField.create(
            TestAllTypes.REPEATED_INT64_FIELD_NUMBER,
            "repeated_int64",
            FieldLiteDescriptor.Type.INT64.getNumber(),
            ImmutableList.of());

    Optional<Object> unpackedFound = unpackedRaw.findByFieldNumber(field);
    Optional<Object> packedFound = packedRaw.findByFieldNumber(field);

    assertThat(unpackedFound).hasValue(ImmutableList.of(10L));
    assertThat(packedFound).hasValue(ImmutableList.of(10L));
  }

  @Test
  public void selectByFieldNumber_mixedUnpackedAndPackedRepeatedInt64_concatenatesInOrder()
      throws Exception {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    CodedOutputStream cos = CodedOutputStream.newInstance(baos);
    cos.writeInt64(TestAllTypes.REPEATED_INT64_FIELD_NUMBER, 10L);
    ByteArrayOutputStream packedChunk = new ByteArrayOutputStream();
    CodedOutputStream packedCos = CodedOutputStream.newInstance(packedChunk);
    packedCos.writeInt64NoTag(20L);
    packedCos.writeInt64NoTag(30L);
    packedCos.flush();
    cos.writeByteArray(TestAllTypes.REPEATED_INT64_FIELD_NUMBER, packedChunk.toByteArray());
    cos.writeInt64(TestAllTypes.REPEATED_INT64_FIELD_NUMBER, 40L);
    cos.flush();
    RawProtoMessageLiteValue raw =
        RawProtoMessageLiteValue.create(
            ByteString.copyFrom(baos.toByteArray()),
            "cel.expr.conformance.proto3.TestAllTypes",
            EMPTY_CONVERTER);
    SelectField field =
        SelectField.create(
            TestAllTypes.REPEATED_INT64_FIELD_NUMBER,
            "repeated_int64",
            FieldLiteDescriptor.Type.INT64.getNumber(),
            ImmutableList.of());

    Object result = raw.selectByFieldNumber(field);

    assertThat(result).isEqualTo(ImmutableList.of(10L, 20L, 30L, 40L));
  }

  @Test
  public void selectByFieldNumber_fiveByteUnsignedInt32Varint_signExtendsCorrectly() {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    baos.write((TestAllTypes.SINGLE_INT32_FIELD_NUMBER << 3));
    // 5-byte varint encoding of 0xFFFFFFD6 (-42 in 32-bit two's complement).
    // Verifies that 32-bit sign extension correctly yields -42L rather than +4294967254L.
    baos.write(0xD6);
    baos.write(0xFF);
    baos.write(0xFF);
    baos.write(0xFF);
    baos.write(0x0F);
    RawProtoMessageLiteValue raw =
        RawProtoMessageLiteValue.create(
            ByteString.copyFrom(baos.toByteArray()),
            "cel.expr.conformance.proto3.TestAllTypes",
            EMPTY_CONVERTER);
    SelectField field =
        SelectField.create(
            TestAllTypes.SINGLE_INT32_FIELD_NUMBER,
            "single_int32",
            FieldLiteDescriptor.Type.INT32.getNumber(),
            0L);

    Object result = raw.selectByFieldNumber(field);

    assertThat(result).isEqualTo(-42L);
  }

  @Test
  public void selectByFieldNumber_enumHighBits_truncatedToSigned32Bit() throws Exception {
    ByteString wire =
        encode(out -> out.writeUInt64(TestAllTypes.STANDALONE_ENUM_FIELD_NUMBER, 0x1FFFFFFFBL));
    RawProtoMessageLiteValue raw =
        RawProtoMessageLiteValue.create(
            wire, "cel.expr.conformance.proto3.TestAllTypes", EMPTY_CONVERTER);
    SelectField field =
        SelectField.create(
            TestAllTypes.STANDALONE_ENUM_FIELD_NUMBER,
            "standalone_enum",
            FieldLiteDescriptor.Type.ENUM.getNumber(),
            0L);

    Object result = raw.selectByFieldNumber(field);

    assertThat(result).isEqualTo(-5L);
  }

  @Test
  public void selectByFieldNumber_invalidUtf8String_throwsIllegalArgumentException()
      throws Exception {
    byte[] invalidUtf8 = {(byte) 0xC0, (byte) 0xAF};
    ByteString wire =
        encode(out -> out.writeByteArray(TestAllTypes.SINGLE_STRING_FIELD_NUMBER, invalidUtf8));
    RawProtoMessageLiteValue raw =
        RawProtoMessageLiteValue.create(
            wire, "cel.expr.conformance.proto3.TestAllTypes", EMPTY_CONVERTER);
    SelectField field =
        SelectField.create(
            TestAllTypes.SINGLE_STRING_FIELD_NUMBER,
            "single_string",
            FieldLiteDescriptor.Type.STRING.getNumber(),
            "");

    IllegalArgumentException thrown =
        assertThrows(IllegalArgumentException.class, () -> raw.selectByFieldNumber(field));

    assertThat(thrown).hasCauseThat().hasMessageThat().contains("invalid UTF-8");
  }

  @Test
  public void selectByFieldNumber_unsetSubmessageWithProtoTypeName_returnsDefaultWithTypeName() {
    RawProtoMessageLiteValue raw =
        RawProtoMessageLiteValue.create(
            ByteString.EMPTY, "cel.expr.conformance.proto3.TestAllTypes", EMPTY_CONVERTER);
    SelectField field =
        SelectField.create(
            999L,
            "custom_msg",
            FieldLiteDescriptor.Type.MESSAGE.getNumber(),
            null,
            "test.CustomMessage");

    Object result = raw.selectByFieldNumber(field);

    assertThat(result).isInstanceOf(RawProtoMessageLiteValue.class);
    assertThat(((RawProtoMessageLiteValue) result).celType().name())
        .isEqualTo("test.CustomMessage");
  }

  @Test
  public void selectByFieldNumber_wireSubmessageWithProtoTypeName_decodesWithTypeName()
      throws Exception {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    CodedOutputStream cos = CodedOutputStream.newInstance(baos);
    cos.writeTag(999, WireFormat.WIRETYPE_LENGTH_DELIMITED);
    cos.writeByteArrayNoTag(new byte[] {0x08, 0x2A});
    cos.flush();
    RawProtoMessageLiteValue raw =
        RawProtoMessageLiteValue.create(
            ByteString.copyFrom(baos.toByteArray()),
            "cel.expr.conformance.proto3.TestAllTypes",
            EMPTY_CONVERTER);
    SelectField field =
        SelectField.create(
            999L,
            "custom_msg",
            FieldLiteDescriptor.Type.MESSAGE.getNumber(),
            null,
            "test.CustomMessage");

    Object result = raw.selectByFieldNumber(field);

    assertThat(result).isInstanceOf(RawProtoMessageLiteValue.class);
    assertThat(((RawProtoMessageLiteValue) result).celType().name())
        .isEqualTo("test.CustomMessage");
  }

  @Test
  public void selectByFieldNumber_unsetRegisteredSubmessage_returnsProtoMessageLiteValue() {
    ProtoLiteCelValueConverter registeredConverter =
        ProtoLiteCelValueConverter.newInstance(
            DefaultLiteDescriptorPool.newInstance(
                ImmutableSet.of(TestAllTypesCelDescriptor.getDescriptor())));
    RawProtoMessageLiteValue raw =
        RawProtoMessageLiteValue.create(
            ByteString.EMPTY, "test.UnknownParent", registeredConverter);
    SelectField field =
        SelectField.create(
            999L,
            "nested_msg",
            FieldLiteDescriptor.Type.MESSAGE.getNumber(),
            null,
            "cel.expr.conformance.proto3.TestAllTypes.NestedMessage");

    Object result = raw.selectByFieldNumber(field);

    assertThat(result).isInstanceOf(ProtoMessageLiteValue.class);
    assertThat(((ProtoMessageLiteValue) result).value())
        .isEqualTo(NestedMessage.getDefaultInstance());
  }

  @Test
  public void selectByFieldNumber_wireRegisteredSubmessage_decodesToProtoMessageLiteValue()
      throws Exception {
    ProtoLiteCelValueConverter registeredConverter =
        ProtoLiteCelValueConverter.newInstance(
            DefaultLiteDescriptorPool.newInstance(
                ImmutableSet.of(TestAllTypesCelDescriptor.getDescriptor())));
    NestedMessage expectedNested = NestedMessage.newBuilder().setBb(42).build();
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    CodedOutputStream cos = CodedOutputStream.newInstance(baos);
    cos.writeMessage(999, expectedNested);
    cos.flush();
    RawProtoMessageLiteValue raw =
        RawProtoMessageLiteValue.create(
            ByteString.copyFrom(baos.toByteArray()), "test.UnknownParent", registeredConverter);
    SelectField field =
        SelectField.create(
            999L,
            "nested_msg",
            FieldLiteDescriptor.Type.MESSAGE.getNumber(),
            null,
            "cel.expr.conformance.proto3.TestAllTypes.NestedMessage");

    Object result = raw.selectByFieldNumber(field);

    assertThat(result).isInstanceOf(ProtoMessageLiteValue.class);
    assertThat(((ProtoMessageLiteValue) result).value()).isEqualTo(expectedNested);
  }
}
