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
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.primitives.UnsignedLong;
import com.google.protobuf.ByteString;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.Int64Value;
import com.google.protobuf.MessageLite;
import com.google.protobuf.WireFormat;
import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import dev.cel.common.exceptions.CelAttributeNotFoundException;
import dev.cel.common.internal.CelLiteDescriptorPool;
import dev.cel.common.internal.DefaultLiteDescriptorPool;
import dev.cel.common.internal.ProtoTimeUtils;
import dev.cel.expr.conformance.proto3.TestAllTypes;
import dev.cel.expr.conformance.proto3.TestAllTypesCelDescriptor;
import dev.cel.protobuf.CelLiteDescriptor.FieldLiteDescriptor;
import dev.cel.protobuf.CelLiteDescriptor.MessageLiteDescriptor;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.NoSuchElementException;
import java.util.Optional;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(TestParameterInjector.class)
public final class RawProtoMessageLiteValueTest {

  private static final ProtoLiteCelValueConverter CONVERTER =
      ProtoLiteCelValueConverter.newInstance(
          DefaultLiteDescriptorPool.newInstance(
              ImmutableSet.of(TestAllTypesCelDescriptor.getDescriptor())));

  private static final ProtoLiteCelValueConverter EMPTY_CONVERTER =
      ProtoLiteCelValueConverter.newInstance(DefaultLiteDescriptorPool.newInstance());

  private static Object decodeWireEntries(
      ImmutableCollection<Object> entries, int typeCode, String protoTypeName, boolean isRepeated) {
    return RawProtoMessageLiteValue.decodeWireEntries(
        entries, typeCode, protoTypeName, isRepeated, EMPTY_CONVERTER);
  }

  private static Object decodeWireValue(
      Object raw, WireFormat.FieldType fieldType, String protoTypeName) {
    return RawProtoMessageLiteValue.decodeWireValue(raw, fieldType, protoTypeName, EMPTY_CONVERTER);
  }

  private static Object decodeWireValue(Object raw, int typeCode, String protoTypeName) {
    return RawProtoMessageLiteValue.decodeWireValue(raw, typeCode, protoTypeName, EMPTY_CONVERTER);
  }

  @Test
  public void create_accessorsAndType() {
    ByteString bytes = ByteString.copyFromUtf8("test");
    RawProtoMessageLiteValue value =
        RawProtoMessageLiteValue.create(bytes, "custom.Message", EMPTY_CONVERTER);

    assertThat(value.rawWireBytes()).isEqualTo(bytes);
    assertThat(value.value()).isSameInstanceAs(value);
    assertThat(value.celType().name()).isEqualTo("custom.Message");
  }

  @Test
  public void create_defaultsEmptyTypeName() {
    ByteString bytes = ByteString.copyFromUtf8("test");
    RawProtoMessageLiteValue value = RawProtoMessageLiteValue.create(bytes, EMPTY_CONVERTER);

    assertThat(value.rawWireBytes()).isEqualTo(bytes);
    assertThat(value.celType().name()).isEmpty();
  }

  @Test
  public void select_throwsCelAttributeNotFoundException() {
    RawProtoMessageLiteValue value =
        RawProtoMessageLiteValue.create(ByteString.EMPTY, "custom.Message", EMPTY_CONVERTER);

    assertThrows(CelAttributeNotFoundException.class, () -> value.select("field"));
  }

  @Test
  public void find_throwsCelAttributeNotFoundException() {
    RawProtoMessageLiteValue value =
        RawProtoMessageLiteValue.create(ByteString.EMPTY, "custom.Message", EMPTY_CONVERTER);

    assertThrows(CelAttributeNotFoundException.class, () -> value.find("field"));
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
  public void unknownFields_parsesWireTags() throws Exception {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    CodedOutputStream cos = CodedOutputStream.newInstance(baos);
    cos.writeInt64(1, 42L);
    cos.writeFixed32(2, 100);
    cos.writeFixed64(3, 200L);
    cos.writeString(4, "hello");
    cos.flush();

    RawProtoMessageLiteValue value =
        RawProtoMessageLiteValue.create(ByteString.copyFrom(baos.toByteArray()), EMPTY_CONVERTER);

    assertThat(value.unknownFields()).valuesForKey(1).containsExactly(42L);
    assertThat(value.unknownFields()).valuesForKey(2).containsExactly(100);
    assertThat(value.unknownFields()).valuesForKey(3).containsExactly(200L);
    assertThat(value.unknownFields())
        .valuesForKey(4)
        .containsExactly(ByteString.copyFromUtf8("hello"));
  }

  @Test
  public void decodeWireEntries_emptySingularEntries_returnsNull() {
    Object intResult =
        decodeWireEntries(
            ImmutableList.of(),
            FieldLiteDescriptor.Type.INT64.getNumber(),
            "custom.Message",
            /* isRepeated= */ false);
    Object messageResult =
        decodeWireEntries(
            ImmutableList.of(),
            FieldLiteDescriptor.Type.MESSAGE.getNumber(),
            "custom.Message",
            /* isRepeated= */ false);

    assertThat(intResult).isNull();
    assertThat(messageResult).isNull();
  }

  @Test
  public void decodeWireEntries_emptyRepeatedEntries_returnsEmptyList() {
    Object result =
        decodeWireEntries(
            ImmutableList.of(),
            FieldLiteDescriptor.Type.INT64.getNumber(),
            "custom.Message",
            /* isRepeated= */ true);

    assertThat((Iterable<?>) result).isEmpty();
  }

  @Test
  public void decodeWireEntries_nonRepeated_lastOneWins() {
    Object decoded =
        decodeWireEntries(
            ImmutableList.of(10L, 20L, 30L),
            FieldLiteDescriptor.Type.INT64.getNumber(),
            "custom.Message",
            /* isRepeated= */ false);

    assertThat(decoded).isEqualTo(30L);
  }

  @Test
  public void decodeWireEntries_repeatedUnpacked() {
    Object decoded =
        decodeWireEntries(
            ImmutableList.of(10L, 20L, 30L),
            FieldLiteDescriptor.Type.INT64.getNumber(),
            "custom.Message",
            /* isRepeated= */ true);

    assertThat(decoded).isEqualTo(ImmutableList.of(10L, 20L, 30L));
  }

  @Test
  public void decodeWireEntries_packedInt32() throws Exception {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    CodedOutputStream cos = CodedOutputStream.newInstance(baos);
    cos.writeInt32NoTag(1);
    cos.writeInt32NoTag(2);
    cos.writeInt32NoTag(3);
    cos.flush();

    Object decoded =
        decodeWireEntries(
            ImmutableList.of(ByteString.copyFrom(baos.toByteArray())),
            FieldLiteDescriptor.Type.INT32.getNumber(),
            "custom.Message",
            /* isRepeated= */ true);

    assertThat(decoded).isEqualTo(ImmutableList.of(1L, 2L, 3L));
  }

  @Test
  public void decodeWireEntries_packedInt64() throws Exception {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    CodedOutputStream cos = CodedOutputStream.newInstance(baos);
    cos.writeInt64NoTag(100L);
    cos.writeInt64NoTag(200L);
    cos.flush();

    Object decoded =
        decodeWireEntries(
            ImmutableList.of(ByteString.copyFrom(baos.toByteArray())),
            FieldLiteDescriptor.Type.INT64.getNumber(),
            "custom.Message",
            /* isRepeated= */ true);

    assertThat(decoded).isEqualTo(ImmutableList.of(100L, 200L));
  }

  @Test
  public void decodeWireEntries_packedUint32() throws Exception {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    CodedOutputStream cos = CodedOutputStream.newInstance(baos);
    cos.writeUInt32NoTag(50);
    cos.flush();

    Object decoded =
        decodeWireEntries(
            ImmutableList.of(ByteString.copyFrom(baos.toByteArray())),
            FieldLiteDescriptor.Type.UINT32.getNumber(),
            "custom.Message",
            /* isRepeated= */ true);

    assertThat(decoded).isEqualTo(ImmutableList.of(UnsignedLong.fromLongBits(50L)));
  }

  @Test
  public void decodeWireEntries_packedUint64() throws Exception {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    CodedOutputStream cos = CodedOutputStream.newInstance(baos);
    cos.writeUInt64NoTag(999L);
    cos.flush();

    Object decoded =
        decodeWireEntries(
            ImmutableList.of(ByteString.copyFrom(baos.toByteArray())),
            FieldLiteDescriptor.Type.UINT64.getNumber(),
            "custom.Message",
            /* isRepeated= */ true);

    assertThat(decoded).isEqualTo(ImmutableList.of(UnsignedLong.fromLongBits(999L)));
  }

  @Test
  public void decodeWireEntries_packedSint32AndSint64() throws Exception {
    ByteArrayOutputStream baos32 = new ByteArrayOutputStream();
    CodedOutputStream cos32 = CodedOutputStream.newInstance(baos32);
    cos32.writeSInt32NoTag(-10);
    cos32.writeSInt32NoTag(20);
    cos32.flush();

    Object decoded32 =
        decodeWireEntries(
            ImmutableList.of(ByteString.copyFrom(baos32.toByteArray())),
            FieldLiteDescriptor.Type.SINT32.getNumber(),
            "custom.Message",
            /* isRepeated= */ true);

    assertThat(decoded32).isEqualTo(ImmutableList.of(-10L, 20L));

    ByteArrayOutputStream baos64 = new ByteArrayOutputStream();
    CodedOutputStream cos64 = CodedOutputStream.newInstance(baos64);
    cos64.writeSInt64NoTag(-100L);
    cos64.writeSInt64NoTag(200L);
    cos64.flush();

    Object decoded64 =
        decodeWireEntries(
            ImmutableList.of(ByteString.copyFrom(baos64.toByteArray())),
            FieldLiteDescriptor.Type.SINT64.getNumber(),
            "custom.Message",
            /* isRepeated= */ true);

    assertThat(decoded64).isEqualTo(ImmutableList.of(-100L, 200L));
  }

  @Test
  public void decodeWireEntries_packedFixedAndSFixed() throws Exception {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    CodedOutputStream cos = CodedOutputStream.newInstance(baos);
    cos.writeFixed32NoTag(10);
    cos.writeFixed64NoTag(20L);
    cos.writeSFixed32NoTag(-30);
    cos.writeSFixed64NoTag(-40L);
    cos.flush();

    assertThat(
            decodeWireEntries(
                ImmutableList.of(ByteString.copyFrom(baos.toByteArray()).substring(0, 4)),
                FieldLiteDescriptor.Type.FIXED32.getNumber(),
                "custom.Message",
                /* isRepeated= */ true))
        .isEqualTo(ImmutableList.of(UnsignedLong.fromLongBits(10L)));

    assertThat(
            decodeWireEntries(
                ImmutableList.of(ByteString.copyFrom(baos.toByteArray()).substring(4, 12)),
                FieldLiteDescriptor.Type.FIXED64.getNumber(),
                "custom.Message",
                /* isRepeated= */ true))
        .isEqualTo(ImmutableList.of(UnsignedLong.fromLongBits(20L)));

    assertThat(
            decodeWireEntries(
                ImmutableList.of(ByteString.copyFrom(baos.toByteArray()).substring(12, 16)),
                FieldLiteDescriptor.Type.SFIXED32.getNumber(),
                "custom.Message",
                /* isRepeated= */ true))
        .isEqualTo(ImmutableList.of(-30L));

    assertThat(
            decodeWireEntries(
                ImmutableList.of(ByteString.copyFrom(baos.toByteArray()).substring(16, 24)),
                FieldLiteDescriptor.Type.SFIXED64.getNumber(),
                "custom.Message",
                /* isRepeated= */ true))
        .isEqualTo(ImmutableList.of(-40L));
  }

  @Test
  public void decodeWireEntries_packedBoolFloatDoubleEnum() throws Exception {
    ByteArrayOutputStream baosBool = new ByteArrayOutputStream();
    CodedOutputStream cosBool = CodedOutputStream.newInstance(baosBool);
    cosBool.writeBoolNoTag(true);
    cosBool.writeBoolNoTag(false);
    cosBool.flush();

    assertThat(
            decodeWireEntries(
                ImmutableList.of(ByteString.copyFrom(baosBool.toByteArray())),
                FieldLiteDescriptor.Type.BOOL.getNumber(),
                "custom.Message",
                /* isRepeated= */ true))
        .isEqualTo(ImmutableList.of(true, false));

    ByteArrayOutputStream baosFloat = new ByteArrayOutputStream();
    CodedOutputStream cosFloat = CodedOutputStream.newInstance(baosFloat);
    cosFloat.writeFloatNoTag(1.5f);
    cosFloat.flush();

    assertThat(
            decodeWireEntries(
                ImmutableList.of(ByteString.copyFrom(baosFloat.toByteArray())),
                FieldLiteDescriptor.Type.FLOAT.getNumber(),
                "custom.Message",
                /* isRepeated= */ true))
        .isEqualTo(ImmutableList.of(1.5d));

    ByteArrayOutputStream baosDouble = new ByteArrayOutputStream();
    CodedOutputStream cosDouble = CodedOutputStream.newInstance(baosDouble);
    cosDouble.writeDoubleNoTag(3.14d);
    cosDouble.flush();

    assertThat(
            decodeWireEntries(
                ImmutableList.of(ByteString.copyFrom(baosDouble.toByteArray())),
                FieldLiteDescriptor.Type.DOUBLE.getNumber(),
                "custom.Message",
                /* isRepeated= */ true))
        .isEqualTo(ImmutableList.of(3.14d));

    ByteArrayOutputStream baosEnum = new ByteArrayOutputStream();
    CodedOutputStream cosEnum = CodedOutputStream.newInstance(baosEnum);
    cosEnum.writeEnumNoTag(2);
    cosEnum.flush();

    assertThat(
            decodeWireEntries(
                ImmutableList.of(ByteString.copyFrom(baosEnum.toByteArray())),
                FieldLiteDescriptor.Type.ENUM.getNumber(),
                "custom.Message",
                /* isRepeated= */ true))
        .isEqualTo(ImmutableList.of(2L));
  }

  @Test
  public void decodeWireValue_allScalarWireTypes() {
    assertThat(
            decodeWireValue(
                Double.doubleToRawLongBits(2.5d), WireFormat.FieldType.DOUBLE, "custom.Message"))
        .isEqualTo(2.5d);

    assertThat(
            decodeWireValue(
                Float.floatToRawIntBits(1.5f), WireFormat.FieldType.FLOAT, "custom.Message"))
        .isEqualTo(1.5d);

    assertThat(decodeWireValue(42L, WireFormat.FieldType.INT64, "custom.Message")).isEqualTo(42L);

    assertThat(decodeWireValue(42L, WireFormat.FieldType.INT32, "custom.Message")).isEqualTo(42L);

    assertThat(decodeWireValue(42L, WireFormat.FieldType.UINT64, "custom.Message"))
        .isEqualTo(UnsignedLong.fromLongBits(42L));

    assertThat(decodeWireValue(42L, WireFormat.FieldType.UINT32, "custom.Message"))
        .isEqualTo(UnsignedLong.fromLongBits(42L));

    assertThat(decodeWireValue(100, WireFormat.FieldType.FIXED32, "custom.Message"))
        .isEqualTo(UnsignedLong.fromLongBits(100L));

    assertThat(decodeWireValue(100L, WireFormat.FieldType.FIXED64, "custom.Message"))
        .isEqualTo(UnsignedLong.fromLongBits(100L));

    assertThat(decodeWireValue(-50, WireFormat.FieldType.SFIXED32, "custom.Message"))
        .isEqualTo(-50L);

    assertThat(decodeWireValue(-50L, WireFormat.FieldType.SFIXED64, "custom.Message"))
        .isEqualTo(-50L);

    assertThat(decodeWireValue(1L, WireFormat.FieldType.BOOL, "custom.Message")).isEqualTo(true);

    assertThat(decodeWireValue(0L, WireFormat.FieldType.BOOL, "custom.Message")).isEqualTo(false);

    assertThat(
            decodeWireValue(
                ByteString.copyFromUtf8("hello"), WireFormat.FieldType.STRING, "custom.Message"))
        .isEqualTo("hello");

    assertThat(
            decodeWireValue(
                ByteString.copyFromUtf8("bytes"), WireFormat.FieldType.BYTES, "custom.Message"))
        .isEqualTo(CelByteString.of("bytes".getBytes(UTF_8)));

    assertThat(
            decodeWireValue(
                1L, // zigzag 1 -> -1
                WireFormat.FieldType.SINT32,
                "custom.Message"))
        .isEqualTo(-1L);

    assertThat(
            decodeWireValue(
                1L, // zigzag 1 -> -1
                WireFormat.FieldType.SINT64,
                "custom.Message"))
        .isEqualTo(-1L);

    assertThat(decodeWireValue(3L, WireFormat.FieldType.ENUM, "custom.Message")).isEqualTo(3L);
  }

  @Test
  public void decodeWireValue_messageType_returnsRawProtoMessageLiteValue() {
    Object submessage =
        decodeWireValue(
            ByteString.copyFromUtf8("raw"), WireFormat.FieldType.MESSAGE, "sub.Message");

    assertThat(submessage).isInstanceOf(RawProtoMessageLiteValue.class);
    assertThat(((RawProtoMessageLiteValue) submessage).celType().name()).isEqualTo("sub.Message");
  }

  @Test
  public void decodeWireValue_groupType_throwsUnsupportedOperationException() {
    ByteString rawBytes = ByteString.copyFromUtf8("raw");

    UnsupportedOperationException thrown =
        assertThrows(
            UnsupportedOperationException.class,
            () -> decodeWireValue(rawBytes, WireFormat.FieldType.GROUP, "group.Message"));

    assertThat(thrown).hasMessageThat().contains("Groups are not supported");
  }

  @Test
  public void decodeWireEntries_groupType_throwsUnsupportedOperationException() {
    ImmutableList<Object> rawEntries = ImmutableList.of();
    int groupTypeCode = FieldLiteDescriptor.Type.GROUP.getNumber();

    UnsupportedOperationException thrown =
        assertThrows(
            UnsupportedOperationException.class,
            () ->
                decodeWireEntries(
                    rawEntries, groupTypeCode, "group.Message", /* isRepeated= */ false));

    assertThat(thrown).hasMessageThat().contains("Groups are not supported");
  }

  @Test
  public void decodeWireEntries_invalidTypeCode_throwsIllegalArgumentException() {
    ImmutableList<Object> rawEntries = ImmutableList.of();

    assertThrows(
        IllegalArgumentException.class,
        () -> decodeWireEntries(rawEntries, 999, "custom.Message", /* isRepeated= */ false));
  }

  @Test
  public void decodeWireValue_invalidTypeCode_throws() {
    assertThrows(IllegalArgumentException.class, () -> decodeWireValue(42L, 0, "custom.Message"));

    assertThrows(IllegalArgumentException.class, () -> decodeWireValue(42L, 999, "custom.Message"));
  }

  @Test
  public void decodeWireValue_int32HighBits_truncatedToSigned32Bit() {
    Object decodedHigh =
        decodeWireValue(0x100000005L, WireFormat.FieldType.INT32, "custom.Message");
    Object decodedNegative =
        decodeWireValue(0xFFFFFFFF80000000L, WireFormat.FieldType.INT32, "custom.Message");

    assertThat(decodedHigh).isEqualTo(5L);
    assertThat(decodedNegative).isEqualTo(-2147483648L);
  }

  @Test
  public void decodeWireValue_enumHighBits_truncatedToSigned32Bit() {
    Object decodedHigh = decodeWireValue(0x100000005L, WireFormat.FieldType.ENUM, "custom.Message");

    assertThat(decodedHigh).isEqualTo(5L);
  }

  @Test
  public void decodeWireValue_typeMismatch_throwsIllegalArgumentException() {
    IllegalArgumentException thrownInt64 =
        assertThrows(
            IllegalArgumentException.class,
            () -> decodeWireValue("not a long", WireFormat.FieldType.INT64, "custom.Message"));
    assertThat(thrownInt64).hasMessageThat().contains("Expected Long for wire type INT64");

    IllegalArgumentException thrownString =
        assertThrows(
            IllegalArgumentException.class,
            () -> decodeWireValue(100L, WireFormat.FieldType.STRING, "custom.Message"));
    assertThat(thrownString).hasMessageThat().contains("Expected ByteString for wire type STRING");

    IllegalArgumentException thrownBytes =
        assertThrows(
            IllegalArgumentException.class,
            () -> decodeWireValue(100L, WireFormat.FieldType.BYTES, "custom.Message"));
    assertThat(thrownBytes).hasMessageThat().contains("Expected ByteString for wire type BYTES");

    IllegalArgumentException thrownMessage =
        assertThrows(
            IllegalArgumentException.class,
            () -> decodeWireValue(100L, WireFormat.FieldType.MESSAGE, "custom.Message"));
    assertThat(thrownMessage)
        .hasMessageThat()
        .contains("Expected ByteString for wire type MESSAGE");

    IllegalArgumentException thrownFloat =
        assertThrows(
            IllegalArgumentException.class,
            () -> decodeWireValue(100L, WireFormat.FieldType.FLOAT, "custom.Message"));
    assertThat(thrownFloat).hasMessageThat().contains("Expected Integer for wire type FLOAT");

    IllegalArgumentException thrownDouble =
        assertThrows(
            IllegalArgumentException.class,
            () -> decodeWireValue(100, WireFormat.FieldType.DOUBLE, "custom.Message"));
    assertThat(thrownDouble).hasMessageThat().contains("Expected Long for wire type DOUBLE");
  }

  @Test
  public void decodeWireValue_invalidUtf8String_throwsIllegalArgumentException() {
    ByteString invalidUtf8 = ByteString.copyFrom(new byte[] {(byte) 0xC0, (byte) 0xAF});

    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> decodeWireValue(invalidUtf8, WireFormat.FieldType.STRING, "custom.Message"));
    assertThat(thrown).hasMessageThat().contains("Invalid UTF-8 in string field");
  }

  @Test
  public void decodeWireEntries_multiChunkPackedRepeated() throws Exception {
    ByteArrayOutputStream baos1 = new ByteArrayOutputStream();
    CodedOutputStream cos1 = CodedOutputStream.newInstance(baos1);
    cos1.writeInt32NoTag(1);
    cos1.writeInt32NoTag(2);
    cos1.flush();

    ByteArrayOutputStream baos2 = new ByteArrayOutputStream();
    CodedOutputStream cos2 = CodedOutputStream.newInstance(baos2);
    cos2.writeInt32NoTag(3);
    cos2.writeInt32NoTag(4);
    cos2.flush();

    Object decoded =
        decodeWireEntries(
            ImmutableList.of(
                ByteString.copyFrom(baos1.toByteArray()), ByteString.copyFrom(baos2.toByteArray())),
            FieldLiteDescriptor.Type.INT32.getNumber(),
            "custom.Message",
            /* isRepeated= */ true);

    assertThat((Iterable<?>) decoded).containsExactly(1L, 2L, 3L, 4L).inOrder();
  }

  @Test
  public void decodeWireEntries_mixedPackedAndUnpackedRepeated() throws Exception {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    CodedOutputStream cos = CodedOutputStream.newInstance(baos);
    cos.writeInt32NoTag(2);
    cos.writeInt32NoTag(3);
    cos.flush();

    Object decoded =
        decodeWireEntries(
            ImmutableList.of(1L, ByteString.copyFrom(baos.toByteArray()), 4L),
            FieldLiteDescriptor.Type.INT32.getNumber(),
            "custom.Message",
            /* isRepeated= */ true);

    assertThat((Iterable<?>) decoded).containsExactly(1L, 2L, 3L, 4L).inOrder();
  }

  @Test
  public void decodeWireEntries_singularMessage_mergesChunks() throws Exception {
    ByteArrayOutputStream baos1 = new ByteArrayOutputStream();
    CodedOutputStream cos1 = CodedOutputStream.newInstance(baos1);
    cos1.writeInt64(1, 100L);
    cos1.flush();

    ByteArrayOutputStream baos2 = new ByteArrayOutputStream();
    CodedOutputStream cos2 = CodedOutputStream.newInstance(baos2);
    cos2.writeInt64(2, 200L);
    cos2.flush();

    Object decoded =
        decodeWireEntries(
            ImmutableList.of(
                ByteString.copyFrom(baos1.toByteArray()), ByteString.copyFrom(baos2.toByteArray())),
            FieldLiteDescriptor.Type.MESSAGE.getNumber(),
            "sub.Message",
            /* isRepeated= */ false);

    assertThat(decoded).isInstanceOf(RawProtoMessageLiteValue.class);
    RawProtoMessageLiteValue rawMessage = (RawProtoMessageLiteValue) decoded;
    assertThat(rawMessage.unknownFields()).valuesForKey(1).containsExactly(100L);
    assertThat(rawMessage.unknownFields()).valuesForKey(2).containsExactly(200L);
  }

  @Test
  public void decodeWireValue_uint32HighBit_correctUnsignedLong() {
    Object decoded = decodeWireValue(0xFFFFFFFFL, WireFormat.FieldType.UINT32, "custom.Message");

    assertThat(decoded).isEqualTo(UnsignedLong.valueOf(4294967295L));
  }

  @Test
  public void decodeWireValue_fixed32HighBit_correctUnsignedLong() {
    Object decoded = decodeWireValue(-1, WireFormat.FieldType.FIXED32, "custom.Message");

    assertThat(decoded).isEqualTo(UnsignedLong.valueOf(4294967295L));
  }

  @Test
  public void decodeWireEntries_repeatedString() {
    Object decoded =
        decodeWireEntries(
            ImmutableList.of(ByteString.copyFromUtf8("foo"), ByteString.copyFromUtf8("bar")),
            FieldLiteDescriptor.Type.STRING.getNumber(),
            "custom.Message",
            /* isRepeated= */ true);

    assertThat((Iterable<?>) decoded).containsExactly("foo", "bar").inOrder();
  }

  @Test
  public void decodeWireEntries_repeatedBytes() {
    Object decoded =
        decodeWireEntries(
            ImmutableList.of(ByteString.copyFromUtf8("foo"), ByteString.copyFromUtf8("bar")),
            FieldLiteDescriptor.Type.BYTES.getNumber(),
            "custom.Message",
            /* isRepeated= */ true);

    assertThat((Iterable<?>) decoded)
        .containsExactly(
            CelByteString.of("foo".getBytes(UTF_8)), CelByteString.of("bar".getBytes(UTF_8)))
        .inOrder();
  }

  @Test
  public void decodeWireEntries_repeatedMessage() {
    Object decoded =
        decodeWireEntries(
            ImmutableList.of(ByteString.copyFromUtf8("msg1"), ByteString.copyFromUtf8("msg2")),
            FieldLiteDescriptor.Type.MESSAGE.getNumber(),
            "sub.Message",
            /* isRepeated= */ true);

    assertThat((Iterable<?>) decoded)
        .containsExactly(
            RawProtoMessageLiteValue.create(
                ByteString.copyFromUtf8("msg1"), "sub.Message", EMPTY_CONVERTER),
            RawProtoMessageLiteValue.create(
                ByteString.copyFromUtf8("msg2"), "sub.Message", EMPTY_CONVERTER))
        .inOrder();
  }

  @Test
  public void decodeWireEntries_packedTruncated_throwsIllegalStateException() {
    // Varint with MSB set (0x80) indicates continuation, but stream ends prematurely.
    ByteString truncated = ByteString.copyFrom(new byte[] {(byte) 0x80});

    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class,
            () ->
                decodeWireEntries(
                    ImmutableList.of(truncated),
                    FieldLiteDescriptor.Type.INT32.getNumber(),
                    "custom.Message",
                    /* isRepeated= */ true));

    assertThat(thrown).hasMessageThat().contains("Failed to parse packed repeated field");
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
  public void selectByFieldNumber_withConverter_resolvesDescriptor() {
    RawProtoMessageLiteValue raw =
        RawProtoMessageLiteValue.create(
            ByteString.EMPTY, "cel.expr.conformance.proto3.TestAllTypes", CONVERTER);

    Object val = raw.selectByFieldNumber(SelectField.create(14L, "single_string"));

    assertThat(val).isEqualTo("");
  }

  @Test
  public void
      selectByFieldNumber_absentSubmessageWithMissingChildDescriptor_returnsEmptyRawProtoMessageLiteValue() {
    CelLiteDescriptorPool poolWithoutNested =
        new CelLiteDescriptorPool() {
          @Override
          public Optional<MessageLiteDescriptor> findDescriptor(String protoTypeName) {
            if (protoTypeName.equals("cel.expr.conformance.proto3.TestAllTypes")) {
              return DefaultLiteDescriptorPool.newInstance(
                      ImmutableSet.of(TestAllTypesCelDescriptor.getDescriptor()))
                  .findDescriptor(protoTypeName);
            }
            return Optional.empty();
          }

          @Override
          public Optional<MessageLiteDescriptor> findDescriptor(MessageLite messageLite) {
            return Optional.empty();
          }

          @Override
          public MessageLiteDescriptor getDescriptorOrThrow(String protoTypeName) {
            return findDescriptor(protoTypeName)
                .orElseThrow(() -> new NoSuchElementException(protoTypeName));
          }
        };
    ProtoLiteCelValueConverter converter =
        ProtoLiteCelValueConverter.newInstance(poolWithoutNested);
    RawProtoMessageLiteValue raw =
        RawProtoMessageLiteValue.create(
            ByteString.EMPTY, "cel.expr.conformance.proto3.TestAllTypes", converter);

    Object val = raw.selectByFieldNumber(SelectField.create(21L, "single_nested_message"));

    assertThat(val).isInstanceOf(RawProtoMessageLiteValue.class);
    RawProtoMessageLiteValue rawChild = (RawProtoMessageLiteValue) val;
    assertThat(rawChild.rawWireBytes()).isEqualTo(ByteString.EMPTY);
    assertThat(rawChild.celType().name())
        .isEqualTo("cel.expr.conformance.proto3.TestAllTypes.NestedMessage");
  }

  @Test
  public void
      selectByFieldNumber_absentRepeatedMessageWithMissingChildDescriptor_returnsEmptyList() {
    CelLiteDescriptorPool poolWithoutNested =
        new CelLiteDescriptorPool() {
          @Override
          public Optional<MessageLiteDescriptor> findDescriptor(String protoTypeName) {
            if (protoTypeName.equals("cel.expr.conformance.proto3.TestAllTypes")) {
              return DefaultLiteDescriptorPool.newInstance(
                      ImmutableSet.of(TestAllTypesCelDescriptor.getDescriptor()))
                  .findDescriptor(protoTypeName);
            }
            return Optional.empty();
          }

          @Override
          public Optional<MessageLiteDescriptor> findDescriptor(MessageLite messageLite) {
            return Optional.empty();
          }

          @Override
          public MessageLiteDescriptor getDescriptorOrThrow(String protoTypeName) {
            return findDescriptor(protoTypeName)
                .orElseThrow(() -> new NoSuchElementException(protoTypeName));
          }
        };
    ProtoLiteCelValueConverter converter =
        ProtoLiteCelValueConverter.newInstance(poolWithoutNested);
    RawProtoMessageLiteValue raw =
        RawProtoMessageLiteValue.create(
            ByteString.EMPTY, "cel.expr.conformance.proto3.TestAllTypes", converter);

    Object val =
        raw.selectByFieldNumber(
            SelectField.create(
                TestAllTypes.REPEATED_NESTED_MESSAGE_FIELD_NUMBER, "repeated_nested_message"));

    assertThat(val).isEqualTo(ImmutableList.of());
  }

  @Test
  public void selectByFieldNumber_unknownFieldWithoutTypeCode_throwsCelAttributeNotFoundException()
      throws Exception {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    CodedOutputStream cos = CodedOutputStream.newInstance(baos);
    cos.writeString(999, "unknown");
    cos.flush();
    RawProtoMessageLiteValue raw =
        RawProtoMessageLiteValue.create(
            ByteString.copyFrom(baos.toByteArray()),
            "cel.expr.conformance.proto3.TestAllTypes",
            CONVERTER);
    SelectField selectField = SelectField.create(999L, "unknown_field");

    assertThrows(CelAttributeNotFoundException.class, () -> raw.selectByFieldNumber(selectField));
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
  public void hasFieldByNumber_emptyPackedRepeated_returnsFalse(
      @TestParameter boolean withDescriptor) throws Exception {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    CodedOutputStream cos = CodedOutputStream.newInstance(baos);
    cos.writeBytes(TestAllTypes.REPEATED_INT32_FIELD_NUMBER, ByteString.EMPTY);
    cos.flush();
    RawProtoMessageLiteValue raw =
        RawProtoMessageLiteValue.create(
            ByteString.copyFrom(baos.toByteArray()),
            "cel.expr.conformance.proto3.TestAllTypes",
            withDescriptor ? CONVERTER : EMPTY_CONVERTER);
    SelectField selectField =
        withDescriptor
            ? SelectField.create(TestAllTypes.REPEATED_INT32_FIELD_NUMBER, "repeated_int32")
            : SelectField.create(
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
            CONVERTER);

    assertThat(
            raw.hasFieldByNumber(
                SelectField.create(TestAllTypes.REPEATED_INT32_FIELD_NUMBER, "repeated_int32")))
        .isTrue();
  }

  @Test
  public void hasFieldByNumber_emptyByteStringOnScalarPackableField_returnsTrue(
      @TestParameter boolean withDescriptor) throws Exception {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    CodedOutputStream cos = CodedOutputStream.newInstance(baos);
    cos.writeByteArray(TestAllTypes.SINGLE_INT32_FIELD_NUMBER, new byte[0]);
    cos.flush();
    RawProtoMessageLiteValue raw =
        RawProtoMessageLiteValue.create(
            ByteString.copyFrom(baos.toByteArray()),
            "cel.expr.conformance.proto3.TestAllTypes",
            withDescriptor ? CONVERTER : EMPTY_CONVERTER);
    SelectField selectField =
        withDescriptor
            ? SelectField.create(TestAllTypes.SINGLE_INT32_FIELD_NUMBER, "single_int32")
            : SelectField.create(
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

    RawProtoMessageLiteValue expected =
        RawProtoMessageLiteValue.create(
            ByteString.copyFrom(subBaos1.toByteArray())
                .concat(ByteString.copyFrom(subBaos2.toByteArray())),
            "cel.@unknownMessage",
            EMPTY_CONVERTER);
    assertThat(nav).hasValue(expected);
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
    INT64(SelectField.create(TestAllTypes.SINGLE_INT64_FIELD_NUMBER, "single_int64"), 99L),
    STRING(SelectField.create(TestAllTypes.SINGLE_STRING_FIELD_NUMBER, "single_string"), "hello"),
    MAP_STRING_STRING(
        SelectField.create(TestAllTypes.MAP_STRING_STRING_FIELD_NUMBER, "map_string_string"),
        ImmutableMap.of("k1", "v1", "k2", "v2")),
    MAP_INT32_BYTES(
        SelectField.create(TestAllTypes.MAP_INT32_BYTES_FIELD_NUMBER, "map_int32_bytes"),
        ImmutableMap.of(
            0L, CelByteString.copyFromUtf8("val_for_default_key"), 42L, CelByteString.EMPTY)),
    DURATION(
        SelectField.create(TestAllTypes.SINGLE_DURATION_FIELD_NUMBER, "single_duration"),
        Duration.ofSeconds(10L, 500L)),
    INT64_WRAPPER(
        SelectField.create(TestAllTypes.SINGLE_INT64_WRAPPER_FIELD_NUMBER, "single_int64_wrapper"),
        12345L);

    private final SelectField selectField;
    private final Object expectedValue;

    private SelectByFieldNumberTestCase(SelectField selectField, Object expectedValue) {
      this.selectField = selectField;
      this.expectedValue = expectedValue;
    }
  }

  @Test
  public void selectByFieldNumber_withDescriptor_decodesExpectedValue(
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
            .setSingleInt64Wrapper(Int64Value.of(12345L))
            .build();
    RawProtoMessageLiteValue raw =
        RawProtoMessageLiteValue.create(
            proto.toByteString(), "cel.expr.conformance.proto3.TestAllTypes", CONVERTER);

    Object selected = raw.selectByFieldNumber(testCase.selectField);

    assertThat(selected).isEqualTo(testCase.expectedValue);
  }

  @Test
  public void findByFieldNumber_scalarField_returnsScalar(@TestParameter boolean withDescriptor) {
    TestAllTypes proto = TestAllTypes.newBuilder().setSingleInt64(99L).build();
    RawProtoMessageLiteValue raw =
        RawProtoMessageLiteValue.create(
            proto.toByteString(),
            "cel.expr.conformance.proto3.TestAllTypes",
            withDescriptor ? CONVERTER : EMPTY_CONVERTER);

    Optional<Object> nav =
        raw.findByFieldNumber(
            SelectField.create(TestAllTypes.SINGLE_INT64_FIELD_NUMBER, "single_int64"));

    assertThat(nav).hasValue(99L);
  }

  @Test
  public void selectByFieldNumber_unsetWrapperFieldWithoutWrapperDescriptor_returnsNullValue() {
    MessageLiteDescriptor testAllTypesDesc =
        TestAllTypesCelDescriptor.getDescriptor()
            .getProtoTypeNamesToDescriptors()
            .get("cel.expr.conformance.proto3.TestAllTypes");
    CelLiteDescriptorPool poolWithoutWrappers =
        new CelLiteDescriptorPool() {
          @Override
          public Optional<MessageLiteDescriptor> findDescriptor(String protoTypeName) {
            if (protoTypeName.equals(testAllTypesDesc.getProtoTypeName())) {
              return Optional.of(testAllTypesDesc);
            }
            return Optional.empty();
          }

          @Override
          public Optional<MessageLiteDescriptor> findDescriptor(MessageLite messageLite) {
            return findDescriptor(messageLite.getClass().getName());
          }

          @Override
          public MessageLiteDescriptor getDescriptorOrThrow(String protoTypeName) {
            return findDescriptor(protoTypeName)
                .orElseThrow(() -> new NoSuchElementException(protoTypeName));
          }
        };
    ProtoLiteCelValueConverter converter =
        ProtoLiteCelValueConverter.newInstance(poolWithoutWrappers);
    RawProtoMessageLiteValue raw =
        RawProtoMessageLiteValue.create(
            ByteString.EMPTY, "cel.expr.conformance.proto3.TestAllTypes", converter);

    Object result =
        raw.selectByFieldNumber(
            SelectField.create(
                TestAllTypes.SINGLE_INT64_WRAPPER_FIELD_NUMBER, "single_int64_wrapper"));

    assertThat(result).isEqualTo(NullValue.NULL_VALUE);
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
                21L, "single_nested_message", FieldLiteDescriptor.Type.MESSAGE.getNumber(), null));

    assertThat(selected).isInstanceOf(RawProtoMessageLiteValue.class);
    RawProtoMessageLiteValue message = (RawProtoMessageLiteValue) selected;
    assertThat(message.rawWireBytes()).isEqualTo(ByteString.EMPTY);
    assertThat(message.celType().name()).isEqualTo("cel.@unknownMessage");
  }

  @Test
  public void
      selectByFieldNumber_unknownMapFieldWithWireEntries_throwsUnsupportedOperationException() {
    TestAllTypes proto = TestAllTypes.newBuilder().putMapStringString("key", "val").build();
    RawProtoMessageLiteValue raw =
        RawProtoMessageLiteValue.create(
            proto.toByteString(), "cel.expr.conformance.proto3.TestAllTypes", EMPTY_CONVERTER);
    SelectField field =
        SelectField.create(
            TestAllTypes.MAP_STRING_STRING_FIELD_NUMBER,
            "map_string_string",
            SelectField.CEL_MAP_TYPE_CODE,
            null);

    UnsupportedOperationException e =
        assertThrows(UnsupportedOperationException.class, () -> raw.selectByFieldNumber(field));

    assertThat(e)
        .hasMessageThat()
        .contains("Decoding unknown map field from wire bytes is unsupported");
  }
}
