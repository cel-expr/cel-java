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

import com.google.common.collect.ImmutableList;
import com.google.common.primitives.UnsignedLong;
import com.google.protobuf.ByteString;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.WireFormat;
import dev.cel.common.exceptions.CelAttributeNotFoundException;
import dev.cel.protobuf.CelLiteDescriptor.FieldLiteDescriptor;
import java.io.ByteArrayOutputStream;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class RawProtoMessageLiteValueTest {

  @Test
  public void create_accessorsAndType() {
    ByteString bytes = ByteString.copyFromUtf8("test");
    RawProtoMessageLiteValue value = RawProtoMessageLiteValue.create(bytes, "custom.Message");

    assertThat(value.rawWireBytes()).isEqualTo(bytes);
    assertThat(value.value()).isSameInstanceAs(value);
    assertThat(value.celType().name()).isEqualTo("custom.Message");
  }

  @Test
  public void create_singleArgDefaultsEmptyTypeName() {
    ByteString bytes = ByteString.copyFromUtf8("test");
    RawProtoMessageLiteValue value = RawProtoMessageLiteValue.create(bytes);

    assertThat(value.rawWireBytes()).isEqualTo(bytes);
    assertThat(value.celType().name()).isEmpty();
  }

  @Test
  public void select_throwsCelAttributeNotFoundException() {
    RawProtoMessageLiteValue value =
        RawProtoMessageLiteValue.create(ByteString.EMPTY, "custom.Message");

    assertThrows(CelAttributeNotFoundException.class, () -> value.select("field"));
  }

  @Test
  public void find_returnsEmptyOptional() {
    RawProtoMessageLiteValue value =
        RawProtoMessageLiteValue.create(ByteString.EMPTY, "custom.Message");

    assertThat(value.find("field")).isEmpty();
  }

  @Test
  public void isZeroValue_emptyBytes_returnsTrue() {
    RawProtoMessageLiteValue value = RawProtoMessageLiteValue.create(ByteString.EMPTY);

    assertThat(value.isZeroValue()).isTrue();
  }

  @Test
  public void isZeroValue_nonEmptyBytes_returnsFalse() {
    RawProtoMessageLiteValue value =
        RawProtoMessageLiteValue.create(ByteString.copyFromUtf8("data"));

    assertThat(value.isZeroValue()).isFalse();
  }

  @Test
  public void hasField_returnsExpectedPresence() throws Exception {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    CodedOutputStream cos = CodedOutputStream.newInstance(baos);
    cos.writeInt64(1, 42L);
    cos.flush();
    RawProtoMessageLiteValue value =
        RawProtoMessageLiteValue.create(ByteString.copyFrom(baos.toByteArray()));

    assertThat(value.hasField(1)).isTrue();
    assertThat(value.hasField(2)).isFalse();
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
        RawProtoMessageLiteValue.create(ByteString.copyFrom(baos.toByteArray()));

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
        RawProtoMessageLiteValue.decodeWireEntries(
            ImmutableList.of(),
            FieldLiteDescriptor.Type.INT64.getNumber(),
            "custom.Message",
            /* isRepeated= */ false);
    Object messageResult =
        RawProtoMessageLiteValue.decodeWireEntries(
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
        RawProtoMessageLiteValue.decodeWireEntries(
            ImmutableList.of(),
            FieldLiteDescriptor.Type.INT64.getNumber(),
            "custom.Message",
            /* isRepeated= */ true);

    assertThat((Iterable<?>) result).isEmpty();
  }

  @Test
  public void decodeWireEntries_nonRepeated_lastOneWins() {
    Object decoded =
        RawProtoMessageLiteValue.decodeWireEntries(
            ImmutableList.of(10L, 20L, 30L),
            FieldLiteDescriptor.Type.INT64.getNumber(),
            "custom.Message",
            /* isRepeated= */ false);

    assertThat(decoded).isEqualTo(30L);
  }

  @Test
  public void decodeWireEntries_repeatedUnpacked() {
    Object decoded =
        RawProtoMessageLiteValue.decodeWireEntries(
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
        RawProtoMessageLiteValue.decodeWireEntries(
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
        RawProtoMessageLiteValue.decodeWireEntries(
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
        RawProtoMessageLiteValue.decodeWireEntries(
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
        RawProtoMessageLiteValue.decodeWireEntries(
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
        RawProtoMessageLiteValue.decodeWireEntries(
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
        RawProtoMessageLiteValue.decodeWireEntries(
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
            RawProtoMessageLiteValue.decodeWireEntries(
                ImmutableList.of(ByteString.copyFrom(baos.toByteArray()).substring(0, 4)),
                FieldLiteDescriptor.Type.FIXED32.getNumber(),
                "custom.Message",
                /* isRepeated= */ true))
        .isEqualTo(ImmutableList.of(UnsignedLong.fromLongBits(10L)));

    assertThat(
            RawProtoMessageLiteValue.decodeWireEntries(
                ImmutableList.of(ByteString.copyFrom(baos.toByteArray()).substring(4, 12)),
                FieldLiteDescriptor.Type.FIXED64.getNumber(),
                "custom.Message",
                /* isRepeated= */ true))
        .isEqualTo(ImmutableList.of(UnsignedLong.fromLongBits(20L)));

    assertThat(
            RawProtoMessageLiteValue.decodeWireEntries(
                ImmutableList.of(ByteString.copyFrom(baos.toByteArray()).substring(12, 16)),
                FieldLiteDescriptor.Type.SFIXED32.getNumber(),
                "custom.Message",
                /* isRepeated= */ true))
        .isEqualTo(ImmutableList.of(-30L));

    assertThat(
            RawProtoMessageLiteValue.decodeWireEntries(
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
            RawProtoMessageLiteValue.decodeWireEntries(
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
            RawProtoMessageLiteValue.decodeWireEntries(
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
            RawProtoMessageLiteValue.decodeWireEntries(
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
            RawProtoMessageLiteValue.decodeWireEntries(
                ImmutableList.of(ByteString.copyFrom(baosEnum.toByteArray())),
                FieldLiteDescriptor.Type.ENUM.getNumber(),
                "custom.Message",
                /* isRepeated= */ true))
        .isEqualTo(ImmutableList.of(2L));
  }

  @Test
  public void decodeWireValue_allScalarWireTypes() {
    assertThat(
            RawProtoMessageLiteValue.decodeWireValue(
                Double.doubleToRawLongBits(2.5d), WireFormat.FieldType.DOUBLE, "custom.Message"))
        .isEqualTo(2.5d);

    assertThat(
            RawProtoMessageLiteValue.decodeWireValue(
                Float.floatToRawIntBits(1.5f), WireFormat.FieldType.FLOAT, "custom.Message"))
        .isEqualTo(1.5d);

    assertThat(
            RawProtoMessageLiteValue.decodeWireValue(
                42L, WireFormat.FieldType.INT64, "custom.Message"))
        .isEqualTo(42L);

    assertThat(
            RawProtoMessageLiteValue.decodeWireValue(
                42L, WireFormat.FieldType.INT32, "custom.Message"))
        .isEqualTo(42L);

    assertThat(
            RawProtoMessageLiteValue.decodeWireValue(
                42L, WireFormat.FieldType.UINT64, "custom.Message"))
        .isEqualTo(UnsignedLong.fromLongBits(42L));

    assertThat(
            RawProtoMessageLiteValue.decodeWireValue(
                42L, WireFormat.FieldType.UINT32, "custom.Message"))
        .isEqualTo(UnsignedLong.fromLongBits(42L));

    assertThat(
            RawProtoMessageLiteValue.decodeWireValue(
                100, WireFormat.FieldType.FIXED32, "custom.Message"))
        .isEqualTo(UnsignedLong.fromLongBits(100L));

    assertThat(
            RawProtoMessageLiteValue.decodeWireValue(
                100L, WireFormat.FieldType.FIXED64, "custom.Message"))
        .isEqualTo(UnsignedLong.fromLongBits(100L));

    assertThat(
            RawProtoMessageLiteValue.decodeWireValue(
                -50, WireFormat.FieldType.SFIXED32, "custom.Message"))
        .isEqualTo(-50L);

    assertThat(
            RawProtoMessageLiteValue.decodeWireValue(
                -50L, WireFormat.FieldType.SFIXED64, "custom.Message"))
        .isEqualTo(-50L);

    assertThat(
            RawProtoMessageLiteValue.decodeWireValue(
                1L, WireFormat.FieldType.BOOL, "custom.Message"))
        .isEqualTo(true);

    assertThat(
            RawProtoMessageLiteValue.decodeWireValue(
                0L, WireFormat.FieldType.BOOL, "custom.Message"))
        .isEqualTo(false);

    assertThat(
            RawProtoMessageLiteValue.decodeWireValue(
                ByteString.copyFromUtf8("hello"), WireFormat.FieldType.STRING, "custom.Message"))
        .isEqualTo("hello");

    assertThat(
            RawProtoMessageLiteValue.decodeWireValue(
                ByteString.copyFromUtf8("bytes"), WireFormat.FieldType.BYTES, "custom.Message"))
        .isEqualTo(CelByteString.of("bytes".getBytes(UTF_8)));

    assertThat(
            RawProtoMessageLiteValue.decodeWireValue(
                1L, // zigzag 1 -> -1
                WireFormat.FieldType.SINT32,
                "custom.Message"))
        .isEqualTo(-1L);

    assertThat(
            RawProtoMessageLiteValue.decodeWireValue(
                1L, // zigzag 1 -> -1
                WireFormat.FieldType.SINT64,
                "custom.Message"))
        .isEqualTo(-1L);

    assertThat(
            RawProtoMessageLiteValue.decodeWireValue(
                3L, WireFormat.FieldType.ENUM, "custom.Message"))
        .isEqualTo(3L);
  }

  @Test
  public void decodeWireValue_messageType_returnsRawProtoMessageLiteValue() {
    Object submessage =
        RawProtoMessageLiteValue.decodeWireValue(
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
            () ->
                RawProtoMessageLiteValue.decodeWireValue(
                    rawBytes, WireFormat.FieldType.GROUP, "group.Message"));

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
                RawProtoMessageLiteValue.decodeWireEntries(
                    rawEntries, groupTypeCode, "group.Message", /* isRepeated= */ false));

    assertThat(thrown).hasMessageThat().contains("Groups are not supported");
  }

  @Test
  public void decodeWireEntries_invalidTypeCode_throwsIllegalArgumentException() {
    ImmutableList<Object> rawEntries = ImmutableList.of();

    assertThrows(
        IllegalArgumentException.class,
        () ->
            RawProtoMessageLiteValue.decodeWireEntries(
                rawEntries, 999, "custom.Message", /* isRepeated= */ false));
  }

  @Test
  public void decodeWireValue_invalidTypeCode_throws() {
    assertThrows(
        IllegalArgumentException.class,
        () -> RawProtoMessageLiteValue.decodeWireValue(42L, 0, "custom.Message"));

    assertThrows(
        IllegalArgumentException.class,
        () -> RawProtoMessageLiteValue.decodeWireValue(42L, 999, "custom.Message"));
  }

  @Test
  public void decodeWireValue_int32HighBits_truncatedToSigned32Bit() {
    Object decodedHigh =
        RawProtoMessageLiteValue.decodeWireValue(
            0x100000005L, WireFormat.FieldType.INT32, "custom.Message");
    Object decodedNegative =
        RawProtoMessageLiteValue.decodeWireValue(
            0xFFFFFFFF80000000L, WireFormat.FieldType.INT32, "custom.Message");

    assertThat(decodedHigh).isEqualTo(5L);
    assertThat(decodedNegative).isEqualTo(-2147483648L);
  }

  @Test
  public void decodeWireValue_enumHighBits_truncatedToSigned32Bit() {
    Object decodedHigh =
        RawProtoMessageLiteValue.decodeWireValue(
            0x100000005L, WireFormat.FieldType.ENUM, "custom.Message");

    assertThat(decodedHigh).isEqualTo(5L);
  }

  @Test
  public void decodeWireValue_typeMismatch_throwsIllegalArgumentException() {
    IllegalArgumentException thrownInt64 =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                RawProtoMessageLiteValue.decodeWireValue(
                    "not a long", WireFormat.FieldType.INT64, "custom.Message"));
    assertThat(thrownInt64).hasMessageThat().contains("Expected Long for wire type INT64");

    IllegalArgumentException thrownString =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                RawProtoMessageLiteValue.decodeWireValue(
                    100L, WireFormat.FieldType.STRING, "custom.Message"));
    assertThat(thrownString).hasMessageThat().contains("Expected ByteString for wire type STRING");

    IllegalArgumentException thrownBytes =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                RawProtoMessageLiteValue.decodeWireValue(
                    100L, WireFormat.FieldType.BYTES, "custom.Message"));
    assertThat(thrownBytes).hasMessageThat().contains("Expected ByteString for wire type BYTES");

    IllegalArgumentException thrownMessage =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                RawProtoMessageLiteValue.decodeWireValue(
                    100L, WireFormat.FieldType.MESSAGE, "custom.Message"));
    assertThat(thrownMessage)
        .hasMessageThat()
        .contains("Expected ByteString for wire type MESSAGE");

    IllegalArgumentException thrownFloat =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                RawProtoMessageLiteValue.decodeWireValue(
                    100L, WireFormat.FieldType.FLOAT, "custom.Message"));
    assertThat(thrownFloat).hasMessageThat().contains("Expected Integer for wire type FLOAT");

    IllegalArgumentException thrownDouble =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                RawProtoMessageLiteValue.decodeWireValue(
                    100, WireFormat.FieldType.DOUBLE, "custom.Message"));
    assertThat(thrownDouble).hasMessageThat().contains("Expected Long for wire type DOUBLE");
  }

  @Test
  public void decodeWireValue_invalidUtf8String_throwsIllegalArgumentException() {
    ByteString invalidUtf8 = ByteString.copyFrom(new byte[] {(byte) 0xC0, (byte) 0xAF});

    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                RawProtoMessageLiteValue.decodeWireValue(
                    invalidUtf8, WireFormat.FieldType.STRING, "custom.Message"));
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
        RawProtoMessageLiteValue.decodeWireEntries(
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
        RawProtoMessageLiteValue.decodeWireEntries(
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
        RawProtoMessageLiteValue.decodeWireEntries(
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
    Object decoded =
        RawProtoMessageLiteValue.decodeWireValue(
            0xFFFFFFFFL, WireFormat.FieldType.UINT32, "custom.Message");

    assertThat(decoded).isEqualTo(UnsignedLong.valueOf(4294967295L));
  }

  @Test
  public void decodeWireValue_fixed32HighBit_correctUnsignedLong() {
    Object decoded =
        RawProtoMessageLiteValue.decodeWireValue(
            -1, WireFormat.FieldType.FIXED32, "custom.Message");

    assertThat(decoded).isEqualTo(UnsignedLong.valueOf(4294967295L));
  }

  @Test
  public void decodeWireEntries_repeatedString() {
    Object decoded =
        RawProtoMessageLiteValue.decodeWireEntries(
            ImmutableList.of(ByteString.copyFromUtf8("foo"), ByteString.copyFromUtf8("bar")),
            FieldLiteDescriptor.Type.STRING.getNumber(),
            "custom.Message",
            /* isRepeated= */ true);

    assertThat((Iterable<?>) decoded).containsExactly("foo", "bar").inOrder();
  }

  @Test
  public void decodeWireEntries_repeatedBytes() {
    Object decoded =
        RawProtoMessageLiteValue.decodeWireEntries(
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
        RawProtoMessageLiteValue.decodeWireEntries(
            ImmutableList.of(ByteString.copyFromUtf8("msg1"), ByteString.copyFromUtf8("msg2")),
            FieldLiteDescriptor.Type.MESSAGE.getNumber(),
            "sub.Message",
            /* isRepeated= */ true);

    assertThat((Iterable<?>) decoded)
        .containsExactly(
            RawProtoMessageLiteValue.create(ByteString.copyFromUtf8("msg1"), "sub.Message"),
            RawProtoMessageLiteValue.create(ByteString.copyFromUtf8("msg2"), "sub.Message"))
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
                RawProtoMessageLiteValue.decodeWireEntries(
                    ImmutableList.of(truncated),
                    FieldLiteDescriptor.Type.INT32.getNumber(),
                    "custom.Message",
                    /* isRepeated= */ true));

    assertThat(thrown).hasMessageThat().contains("Failed to parse packed repeated field");
  }
}
