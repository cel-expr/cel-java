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

package dev.cel.common.values;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.primitives.UnsignedLong;
import com.google.protobuf.BoolValue;
import com.google.protobuf.ByteString;
import com.google.protobuf.BytesValue;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.DoubleValue;
import com.google.protobuf.Duration;
import com.google.protobuf.ExtensionRegistryLite;
import com.google.protobuf.FieldMask;
import com.google.protobuf.FloatValue;
import com.google.protobuf.Int32Value;
import com.google.protobuf.Int64Value;
import com.google.protobuf.MessageLite;
import com.google.protobuf.StringValue;
import com.google.protobuf.TextFormat;
import com.google.protobuf.Timestamp;
import com.google.protobuf.UInt32Value;
import com.google.protobuf.UInt64Value;
import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import dev.cel.common.internal.CelLiteDescriptorPool;
import dev.cel.common.internal.DefaultLiteDescriptorPool;
import dev.cel.expr.conformance.proto3.NestedTestAllTypes;
import dev.cel.expr.conformance.proto3.TestAllTypes;
import dev.cel.expr.conformance.proto3.TestAllTypes.NestedMessage;
import dev.cel.expr.conformance.proto3.TestAllTypesCelDescriptor;
import dev.cel.protobuf.CelLiteDescriptor.FieldLiteDescriptor;
import dev.cel.protobuf.CelLiteDescriptor.MessageLiteDescriptor;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(TestParameterInjector.class)
public final class ProtoLiteCelValueConverterTest {
  private static final CelLiteDescriptorPool EMPTY_DESCRIPTOR_POOL =
      new CelLiteDescriptorPool() {
        @Override
        public Optional<MessageLiteDescriptor> findDescriptor(String protoTypeName) {
          return Optional.empty();
        }

        @Override
        public Optional<MessageLiteDescriptor> findDescriptor(MessageLite messageLite) {
          return Optional.empty();
        }

        @Override
        public MessageLiteDescriptor getDescriptorOrThrow(String protoTypeName) {
          throw new NoSuchElementException(protoTypeName);
        }
      };

  private static final CelLiteDescriptorPool DESCRIPTOR_POOL =
      DefaultLiteDescriptorPool.newInstance(
          ImmutableSet.of(
              TestAllTypesCelDescriptor.getDescriptor(),
              dev.cel.expr.conformance.proto2.TestAllTypesCelDescriptor.getDescriptor()));

  private static final ProtoLiteCelValueConverter PROTO_LITE_CEL_VALUE_CONVERTER =
      ProtoLiteCelValueConverter.newInstance(DESCRIPTOR_POOL);

  @Test
  public void
      fromProtoMessageToCelValue_withTestMessage_convertsToProtoMessageLiteValueFromProtoMessage() {
    ProtoMessageLiteValue protoMessageLiteValue =
        (ProtoMessageLiteValue)
            PROTO_LITE_CEL_VALUE_CONVERTER.toRuntimeValue(TestAllTypes.getDefaultInstance());

    assertThat(protoMessageLiteValue.value()).isEqualTo(TestAllTypes.getDefaultInstance());
  }

  @Test
  public void fromProtoMessageToCelValue_withoutDescriptor_returnsRawProtoMessageLiteValue() {
    ProtoLiteCelValueConverter converterWithoutDescriptors =
        ProtoLiteCelValueConverter.newInstance(EMPTY_DESCRIPTOR_POOL);
    TestAllTypes msg = TestAllTypes.newBuilder().setSingleInt64(42L).build();

    Object adaptedValue = converterWithoutDescriptors.toRuntimeValue(msg);

    assertThat(adaptedValue).isInstanceOf(RawProtoMessageLiteValue.class);
    RawProtoMessageLiteValue rawValue = (RawProtoMessageLiteValue) adaptedValue;
    assertThat(rawValue.toByteString()).isEqualTo(msg.toByteString());
    assertThat(rawValue.protoTypeName()).isEqualTo("cel.@unknownMessage");
  }

  @SuppressWarnings("ImmutableEnumChecker") // Test only
  private enum WellKnownProtoTestCase {
    BOOL(BoolValue.of(true), true),
    BYTES(BytesValue.of(ByteString.copyFromUtf8("test")), CelByteString.copyFromUtf8("test")),
    FLOAT(FloatValue.of(1.0f), 1.0d),
    DOUBLE(DoubleValue.of(1.0), 1.0d),
    INT32(Int32Value.of(1), 1L),
    INT64(Int64Value.of(1L), 1L),
    STRING(StringValue.of("test"), "test"),

    DURATION(
        Duration.newBuilder().setSeconds(10).setNanos(50).build(),
        java.time.Duration.ofSeconds(10, 50)),
    TIMESTAMP(
        Timestamp.newBuilder().setSeconds(1678886400L).setNanos(123000000).build(),
        Instant.ofEpochSecond(1678886400L, 123000000)),
    UINT32(UInt32Value.of(1), UnsignedLong.valueOf(1)),
    UINT64(UInt64Value.of(1L), UnsignedLong.valueOf(1L)),
    ;

    private final MessageLite msg;
    private final Object value;

    WellKnownProtoTestCase(MessageLite msg, Object value) {
      this.msg = msg;
      this.value = value;
    }
  }

  @Test
  public void fromProtoMessageToCelValue_withWellKnownProto_convertsToPrimitivesFromProtoMessage(
      @TestParameter WellKnownProtoTestCase testCase) {
    Object adaptedValue = PROTO_LITE_CEL_VALUE_CONVERTER.toRuntimeValue(testCase.msg);

    assertThat(adaptedValue).isEqualTo(testCase.value);
  }

  @Test
  public void fromProtoMessageToCelValue_fieldMask_returnsProtoMessageLiteValue() {
    FieldMask fieldMask = FieldMask.newBuilder().addPaths("foo").addPaths("bar").build();

    Object adaptedValue = PROTO_LITE_CEL_VALUE_CONVERTER.toRuntimeValue(fieldMask);

    assertThat(adaptedValue).isInstanceOf(ProtoMessageLiteValue.class);
    assertThat(((ProtoMessageLiteValue) adaptedValue).select("paths"))
        .isEqualTo(ImmutableList.of("foo", "bar"));
  }

  /** Test cases for repeated_int64: 1L,2L,3L */
  @SuppressWarnings("ImmutableEnumChecker") // Test only
  private enum RepeatedFieldBytesTestCase {
    PACKED(new byte[] {(byte) 0x82, 0x2, 0x3, 0x1, 0x2, 0x3}),
    NON_PACKED(new byte[] {(byte) 0x80, 0x2, 0x1, (byte) 0x80, 0x2, 0x2, (byte) 0x80, 0x2, 0x3}),
    // 1L is not packed, but 2L and 3L are
    MIXED(new byte[] {(byte) 0x80, 0x2, 0x1, (byte) 0x82, 0x2, 0x2, 0x2, 0x3});

    private final byte[] bytes;

    RepeatedFieldBytesTestCase(byte[] bytes) {
      this.bytes = bytes;
    }
  }

  // repeated_int64 is declared unpacked in proto2 and packed in proto3.
  @Test
  public void readSingleField_repeatedFields_packedBytesCombinations(
      @TestParameter RepeatedFieldBytesTestCase testCase,
      @TestParameter({
            "cel.expr.conformance.proto2.TestAllTypes",
            "cel.expr.conformance.proto3.TestAllTypes"
          })
          String messageName)
      throws Exception {
    FieldLiteDescriptor fd = fieldDescriptor(messageName, TestAllTypes.REPEATED_INT64_FIELD_NUMBER);

    Object result =
        PROTO_LITE_CEL_VALUE_CONVERTER.readSingleField(ByteString.copyFrom(testCase.bytes), fd);

    assertThat(result).isEqualTo(ImmutableList.of(1L, 2L, 3L));
  }

  /**
   * Unknown test with the following hypothetical fields:
   *
   * <pre>{@code
   * message TestAllTypes {
   *   int64 single_int64_unknown = 2500;
   *   fixed32 single_fixed32_unknown = 2501;
   *   fixed64 single_fixed64_unknown = 2502;
   *   string single_string_unknown = 2503;
   *   repeated int64 repeated_int64_unknown = 2504;
   *   map<string, int64> map_string_int64_unknown = 2505;
   * }
   * }</pre>
   */
  @SuppressWarnings("ImmutableEnumChecker") // Test only
  private enum UnknownFieldsTestCase {
    INT64(new byte[] {-96, -100, 1, 1}, "2500: 1"),
    FIXED32(new byte[] {-83, -100, 1, 2, 0, 0, 0}, "2501: 0x00000002"),
    FIXED64(new byte[] {-79, -100, 1, 3, 0, 0, 0, 0, 0, 0, 0}, "2502: 0x0000000000000003"),
    STRING(
        new byte[] {-70, -100, 1, 11, 72, 101, 108, 108, 111, 32, 119, 111, 114, 108, 100},
        "2503: \"Hello world\""),
    REPEATED_INT64(new byte[] {-62, -100, 1, 2, 4, 5}, "2504: \"\\004\\005\""),
    MAP_STRING_INT64(
        new byte[] {
          -54, -100, 1, 7, 10, 3, 102, 111, 111, 16, 4, -54, -100, 1, 7, 10, 3, 98, 97, 114, 16, 5
        },
        "2505: {\n"
            + "  1: \"foo\"\n"
            + "  2: 4\n"
            + "}\n"
            + "2505: {\n"
            + "  1: \"bar\"\n"
            + "  2: 5\n"
            + "}");

    private final byte[] bytes;
    private final String formattedOutput;

    UnknownFieldsTestCase(byte[] bytes, String formattedOutput) {
      this.bytes = bytes;
      this.formattedOutput = formattedOutput;
    }
  }

  @Test
  public void readSingleField_unknownFields_skipped(@TestParameter UnknownFieldsTestCase testCase)
      throws Exception {
    TestAllTypes parsedMsg =
        TestAllTypes.parseFrom(testCase.bytes, ExtensionRegistryLite.getEmptyRegistry());
    FieldLiteDescriptor fd =
        fieldDescriptor(
            "cel.expr.conformance.proto3.TestAllTypes", TestAllTypes.SINGLE_INT64_FIELD_NUMBER);

    Object result =
        PROTO_LITE_CEL_VALUE_CONVERTER.readSingleField(ByteString.copyFrom(testCase.bytes), fd);

    assertThat(result).isNull();
    assertThat(TextFormat.printer().printToString(parsedMsg).trim())
        .isEqualTo(testCase.formattedOutput);
  }

  /**
   * Tests the following message:
   *
   * <pre>{@code
   * TestAllTypes.newBuilder()
   *     // Unknowns
   *     .setSingleInt64Unknown(1L)
   *     .setSingleFixed32Unknown(2)
   *     .setSingleFixed64Unknown(3L)
   *     .setSingleStringUnknown("Hello world")
   *     .addAllRepeatedInt64Unknown(ImmutableList.of(4L, 5L))
   *     .putMapStringInt64Unknown("foo", 4L)
   *     .putMapStringInt64Unknown("bar", 5L)
   *     // Known values
   *     .putMapBoolDouble(true, 1.5d)
   *     .putMapBoolDouble(false, 2.5d)
   *     .build();
   * }</pre>
   */
  @Test
  public void readSingleField_unknownFieldsPrecedeTarget_decodesTarget() throws Exception {
    // Unknown fields precede the known map entries so that decoding must continue past them.
    byte[] unknownMessageBytes = {
      -96, -100, 1, 1, -83, -100, 1, 2, 0, 0, 0, -79, -100, 1, 3, 0, 0, 0, 0, 0, 0, 0, -70, -100, 1,
      11, 72, 101, 108, 108, 111, 32, 119, 111, 114, 108, 100, -62, -100, 1, 2, 4, 5, -54, -100, 1,
      7, 10, 3, 102, 111, 111, 16, 4, -54, -100, 1, 7, 10, 3, 98, 97, 114, 16, 5, -70, 4, 11, 8, 1,
      17, 0, 0, 0, 0, 0, 0, -8, 63, -70, 4, 11, 8, 0, 17, 0, 0, 0, 0, 0, 0, 4, 64
    };
    TestAllTypes parsedMsg =
        TestAllTypes.parseFrom(unknownMessageBytes, ExtensionRegistryLite.getEmptyRegistry());
    FieldLiteDescriptor fd =
        fieldDescriptor(
            "cel.expr.conformance.proto3.TestAllTypes", TestAllTypes.MAP_BOOL_DOUBLE_FIELD_NUMBER);

    Object result =
        PROTO_LITE_CEL_VALUE_CONVERTER.readSingleField(
            ByteString.copyFrom(unknownMessageBytes), fd);

    assertThat(TextFormat.printer().printToString(parsedMsg))
        .isEqualTo(
            "map_bool_double {\n"
                + "  key: false\n"
                + "  value: 2.5\n"
                + "}\n"
                + "map_bool_double {\n"
                + "  key: true\n"
                + "  value: 1.5\n"
                + "}\n"
                + "2500: 1\n"
                + "2501: 0x00000002\n"
                + "2502: 0x0000000000000003\n"
                + "2503: \"Hello world\"\n"
                + "2504: \"\\004\\005\"\n"
                + "2505: {\n"
                + "  1: \"foo\"\n"
                + "  2: 4\n"
                + "}\n"
                + "2505: {\n"
                + "  1: \"bar\"\n"
                + "  2: 5\n"
                + "}\n");
    assertThat((Map<?, ?>) result).containsExactly(true, 1.5d, false, 2.5d).inOrder();
  }

  @Test
  public void getDefaultCelValue_fieldDescriptor_returnsDefault() {
    FieldLiteDescriptor fieldDescriptor =
        DESCRIPTOR_POOL
            .getDescriptorOrThrow("cel.expr.conformance.proto3.TestAllTypes")
            .getByFieldNameOrThrow("single_string");

    Object defaultValue = PROTO_LITE_CEL_VALUE_CONVERTER.getDefaultCelValue(fieldDescriptor);

    assertThat(defaultValue).isEqualTo("");
  }

  @Test
  public void getDefaultCelValue_nestedMessageWithoutDescriptor_returnsRawProtoMessageLiteValue() {
    FieldLiteDescriptor nestedMsgField =
        DESCRIPTOR_POOL
            .getDescriptorOrThrow("cel.expr.conformance.proto3.TestAllTypes")
            .getByFieldNameOrThrow("single_nested_message");
    ProtoLiteCelValueConverter converterWithoutNested =
        ProtoLiteCelValueConverter.newInstance(EMPTY_DESCRIPTOR_POOL);

    Object defaultValue = converterWithoutNested.getDefaultCelValue(nestedMsgField);

    assertThat(defaultValue).isInstanceOf(RawProtoMessageLiteValue.class);
    RawProtoMessageLiteValue rawValue = (RawProtoMessageLiteValue) defaultValue;
    assertThat(rawValue.toByteString()).isEqualTo(ByteString.EMPTY);
    assertThat(rawValue.protoTypeName())
        .isEqualTo("cel.expr.conformance.proto3.TestAllTypes.NestedMessage");
  }

  @Test
  public void readSingleField_nestedMessageWithoutDescriptor_returnsRawProtoMessageLiteValue()
      throws Exception {
    // DESCRIPTOR_POOL only registers TestAllTypesCelDescriptor, not NestedTestAllTypesCelDescriptor
    TestAllTypes msg =
        TestAllTypes.newBuilder()
            .setOneofType(
                NestedTestAllTypes.newBuilder()
                    .setPayload(TestAllTypes.newBuilder().setSingleInt64(42L)))
            .build();
    FieldLiteDescriptor fd =
        fieldDescriptor(
            "cel.expr.conformance.proto3.TestAllTypes", TestAllTypes.ONEOF_TYPE_FIELD_NUMBER);

    Object result = PROTO_LITE_CEL_VALUE_CONVERTER.readSingleField(msg.toByteString(), fd);

    assertThat(result).isInstanceOf(RawProtoMessageLiteValue.class);
    RawProtoMessageLiteValue rawValue = (RawProtoMessageLiteValue) result;
    assertThat(rawValue.toByteString()).isEqualTo(msg.getOneofType().toByteString());
    assertThat(rawValue.protoTypeName())
        .isEqualTo("cel.expr.conformance.proto3.NestedTestAllTypes");
  }

  @Test
  public void readSingleField_splitSingularSubmessages_mergesAllOccurrences() throws Exception {
    ByteArrayOutputStream unknownFieldBaos = new ByteArrayOutputStream();
    CodedOutputStream cos = CodedOutputStream.newInstance(unknownFieldBaos);
    cos.writeInt64(999, 42L);
    cos.flush();
    NestedMessage nestedWithUnknown =
        NestedMessage.parseFrom(
            unknownFieldBaos.toByteArray(), ExtensionRegistryLite.getEmptyRegistry());
    TestAllTypes part1 =
        TestAllTypes.newBuilder()
            .setOneofType(
                NestedTestAllTypes.newBuilder()
                    .setPayload(TestAllTypes.newBuilder().setSingleInt32(10)))
            .setSingleNestedMessage(NestedMessage.newBuilder().setBb(99))
            .build();
    TestAllTypes part2 =
        TestAllTypes.newBuilder()
            .setOneofType(
                NestedTestAllTypes.newBuilder()
                    .setPayload(TestAllTypes.newBuilder().setSingleString("merged")))
            .setSingleNestedMessage(nestedWithUnknown)
            .build();
    ByteString splitWireBytes = part1.toByteString().concat(part2.toByteString());
    FieldLiteDescriptor nestedMessageFd =
        fieldDescriptor(
            "cel.expr.conformance.proto3.TestAllTypes",
            TestAllTypes.SINGLE_NESTED_MESSAGE_FIELD_NUMBER);
    FieldLiteDescriptor oneofTypeFd =
        fieldDescriptor(
            "cel.expr.conformance.proto3.TestAllTypes", TestAllTypes.ONEOF_TYPE_FIELD_NUMBER);

    ProtoMessageLiteValue nestedMsg =
        (ProtoMessageLiteValue)
            PROTO_LITE_CEL_VALUE_CONVERTER.readSingleField(splitWireBytes, nestedMessageFd);
    RawProtoMessageLiteValue rawSubmessage =
        (RawProtoMessageLiteValue)
            PROTO_LITE_CEL_VALUE_CONVERTER.readSingleField(splitWireBytes, oneofTypeFd);

    assertThat(nestedMsg.rawValue()).isNull();
    assertThat(nestedMsg.select("bb")).isEqualTo(99L);
    assertThat(nestedMsg.value())
        .isEqualTo(NestedMessage.newBuilder().setBb(99).mergeFrom(nestedWithUnknown).build());
    assertThat(
            NestedTestAllTypes.parseFrom(
                rawSubmessage.toByteString(), ExtensionRegistryLite.getEmptyRegistry()))
        .isEqualTo(
            NestedTestAllTypes.newBuilder()
                .setPayload(TestAllTypes.newBuilder().setSingleInt32(10).setSingleString("merged"))
                .build());
  }

  @Test
  public void parseMessageLite_emptyBytes_returnsDefaultInstanceSingleton() {
    MessageLite parsed =
        PROTO_LITE_CEL_VALUE_CONVERTER.parseMessageLite(
            ByteString.EMPTY, "cel.expr.conformance.proto3.TestAllTypes");

    assertThat(parsed).isSameInstanceAs(TestAllTypes.getDefaultInstance());
  }

  @Test
  public void readSingleField_emptyBytes_returnsNull() throws Exception {
    FieldLiteDescriptor fd =
        fieldDescriptor(
            "cel.expr.conformance.proto3.TestAllTypes", TestAllTypes.SINGLE_INT64_FIELD_NUMBER);

    Object result = PROTO_LITE_CEL_VALUE_CONVERTER.readSingleField(ByteString.EMPTY, fd);

    assertThat(result).isNull();
  }

  @Test
  public void readSingleField_absentField_returnsNull() throws Exception {
    TestAllTypes proto = TestAllTypes.newBuilder().setSingleInt64(42L).build();
    FieldLiteDescriptor fd =
        fieldDescriptor(
            "cel.expr.conformance.proto3.TestAllTypes", TestAllTypes.SINGLE_BOOL_FIELD_NUMBER);

    Object result = PROTO_LITE_CEL_VALUE_CONVERTER.readSingleField(proto.toByteString(), fd);

    assertThat(result).isNull();
  }

  @Test
  public void readSingleField_emptyPackedRepeated_returnsNull() throws Exception {
    ByteArrayOutputStream emptyPackedOut = new ByteArrayOutputStream();
    CodedOutputStream emptyPackedCos = CodedOutputStream.newInstance(emptyPackedOut);
    emptyPackedCos.writeByteArray(TestAllTypes.REPEATED_INT32_FIELD_NUMBER, new byte[0]);
    emptyPackedCos.flush();
    ByteString emptyPackedBytes = ByteString.copyFrom(emptyPackedOut.toByteArray());
    FieldLiteDescriptor repeatedInt32Fd =
        fieldDescriptor(
            "cel.expr.conformance.proto3.TestAllTypes", TestAllTypes.REPEATED_INT32_FIELD_NUMBER);

    Object result =
        PROTO_LITE_CEL_VALUE_CONVERTER.readSingleField(emptyPackedBytes, repeatedInt32Fd);

    assertThat(result).isNull();
  }

  @SuppressWarnings("ImmutableEnumChecker") // Test only
  private enum ReadSingleFieldTestCase {
    SINGLE_STRING(TestAllTypes.SINGLE_STRING_FIELD_NUMBER, "target_str"),
    REPEATED_STRING(TestAllTypes.REPEATED_STRING_FIELD_NUMBER, ImmutableList.of("a", "b")),
    REPEATED_INT32(TestAllTypes.REPEATED_INT32_FIELD_NUMBER, ImmutableList.of(1L, 2L)),
    MAP_STRING_STRING(
        TestAllTypes.MAP_STRING_STRING_FIELD_NUMBER, ImmutableMap.of("k", "v", "k2", "v2", "", "")),
    MAP_STRING_INT64_WRAPPER(
        TestAllTypes.MAP_STRING_INT64_WRAPPER_FIELD_NUMBER, ImmutableMap.of("", 0L)),
    SPLIT_DURATION(
        TestAllTypes.SINGLE_DURATION_FIELD_NUMBER, java.time.Duration.ofSeconds(10, 500));

    private final int fieldNumber;
    private final Object expected;

    ReadSingleFieldTestCase(int fieldNumber, Object expected) {
      this.fieldNumber = fieldNumber;
      this.expected = expected;
    }
  }

  @Test
  public void readSingleField_skipsOtherFieldsAndDecodesTarget(
      @TestParameter ReadSingleFieldTestCase testCase) throws Exception {
    TestAllTypes part1 =
        TestAllTypes.newBuilder()
            .setSingleInt64(42L)
            .setSingleFixed32(10)
            .setSingleFixed64(20L)
            .setSingleString("target_str")
            .addRepeatedString("a")
            .addRepeatedInt32(1)
            .putMapStringString("k", "v")
            .setSingleDuration(Duration.newBuilder().setSeconds(10))
            .build();
    TestAllTypes part2 =
        TestAllTypes.newBuilder()
            .addRepeatedString("b")
            .addRepeatedInt32(2)
            .putMapStringString("k2", "v2")
            .setSingleDuration(Duration.newBuilder().setNanos(500))
            .build();
    ByteArrayOutputStream mapEntryWithUnknownOut = new ByteArrayOutputStream();
    CodedOutputStream mapEntryWithUnknownCos =
        CodedOutputStream.newInstance(mapEntryWithUnknownOut);
    mapEntryWithUnknownCos.writeInt64(3, 99L);
    mapEntryWithUnknownCos.flush();
    ByteArrayOutputStream extraWireOut = new ByteArrayOutputStream();
    CodedOutputStream extraWireCos = CodedOutputStream.newInstance(extraWireOut);
    extraWireCos.writeByteArray(TestAllTypes.REPEATED_INT32_FIELD_NUMBER, new byte[0]);
    extraWireCos.writeByteArray(
        TestAllTypes.MAP_STRING_STRING_FIELD_NUMBER, mapEntryWithUnknownOut.toByteArray());
    extraWireCos.writeByteArray(
        TestAllTypes.MAP_STRING_INT64_WRAPPER_FIELD_NUMBER, mapEntryWithUnknownOut.toByteArray());
    extraWireCos.flush();
    ByteString bytes =
        part1
            .toByteString()
            .concat(part2.toByteString())
            .concat(ByteString.copyFrom(extraWireOut.toByteArray()));
    FieldLiteDescriptor fd =
        fieldDescriptor("cel.expr.conformance.proto3.TestAllTypes", testCase.fieldNumber);

    Object result = PROTO_LITE_CEL_VALUE_CONVERTER.readSingleField(bytes, fd);

    assertThat(result).isEqualTo(testCase.expected);
  }

  @Test
  public void readSingleField_repeatedMapKey_convertsOnlyFinalValue() throws Exception {
    // The first value is out of Timestamp's range, so converting it would throw.
    ByteString bytes =
        TestAllTypes.newBuilder()
            .putMapStringTimestamp("k", Timestamp.newBuilder().setSeconds(1L << 60).build())
            .build()
            .toByteString()
            .concat(
                TestAllTypes.newBuilder()
                    .putMapStringTimestamp("k", Timestamp.newBuilder().setSeconds(100).build())
                    .build()
                    .toByteString());
    FieldLiteDescriptor fd =
        fieldDescriptor(
            "cel.expr.conformance.proto3.TestAllTypes",
            TestAllTypes.MAP_STRING_TIMESTAMP_FIELD_NUMBER);

    Object result = PROTO_LITE_CEL_VALUE_CONVERTER.readSingleField(bytes, fd);

    assertThat(result).isEqualTo(ImmutableMap.of("k", Instant.ofEpochSecond(100)));
  }

  @Test
  public void hasSingleField_emptyBytes_returnsFalse() throws Exception {
    FieldLiteDescriptor singleInt64Fd =
        fieldDescriptor(
            "cel.expr.conformance.proto3.TestAllTypes", TestAllTypes.SINGLE_INT64_FIELD_NUMBER);

    boolean result = PROTO_LITE_CEL_VALUE_CONVERTER.hasSingleField(ByteString.EMPTY, singleInt64Fd);

    assertThat(result).isFalse();
  }

  // repeated_int32 is declared unpacked in proto2 and packed in proto3.
  @Test
  public void hasSingleField_emptyPackedRepeatedField_returnsFalse(
      @TestParameter({
            "cel.expr.conformance.proto2.TestAllTypes",
            "cel.expr.conformance.proto3.TestAllTypes"
          })
          String messageName)
      throws Exception {
    FieldLiteDescriptor repeatedInt32Fd =
        fieldDescriptor(messageName, TestAllTypes.REPEATED_INT32_FIELD_NUMBER);
    ByteArrayOutputStream emptyPackedOut = new ByteArrayOutputStream();
    CodedOutputStream emptyPackedCos = CodedOutputStream.newInstance(emptyPackedOut);
    emptyPackedCos.writeInt64(TestAllTypes.SINGLE_INT64_FIELD_NUMBER, 42L);
    emptyPackedCos.writeByteArray(TestAllTypes.REPEATED_INT32_FIELD_NUMBER, new byte[0]);
    emptyPackedCos.flush();
    ByteString emptyPackedBytes = ByteString.copyFrom(emptyPackedOut.toByteArray());

    boolean result =
        PROTO_LITE_CEL_VALUE_CONVERTER.hasSingleField(emptyPackedBytes, repeatedInt32Fd);

    assertThat(result).isFalse();
  }

  @Test
  public void hasSingleField_emptyPackedFollowedByPopulatedPacked_returnsTrue() throws Exception {
    FieldLiteDescriptor repeatedInt32Fd =
        fieldDescriptor(
            "cel.expr.conformance.proto3.TestAllTypes", TestAllTypes.REPEATED_INT32_FIELD_NUMBER);
    ByteArrayOutputStream emptyThenPopulatedOut = new ByteArrayOutputStream();
    CodedOutputStream emptyThenPopulatedCos = CodedOutputStream.newInstance(emptyThenPopulatedOut);
    emptyThenPopulatedCos.writeByteArray(TestAllTypes.REPEATED_INT32_FIELD_NUMBER, new byte[0]);
    emptyThenPopulatedCos.writeByteArray(
        TestAllTypes.REPEATED_INT32_FIELD_NUMBER, new byte[] {1, 2});
    emptyThenPopulatedCos.flush();
    ByteString emptyThenPopulatedBytes = ByteString.copyFrom(emptyThenPopulatedOut.toByteArray());

    boolean result =
        PROTO_LITE_CEL_VALUE_CONVERTER.hasSingleField(emptyThenPopulatedBytes, repeatedInt32Fd);

    assertThat(result).isTrue();
  }

  // Wire types 3 and 4 are START_GROUP and END_GROUP.
  @Test
  public void readSingleField_groupInSkippedField_throwsUnsupportedOperationException(
      @TestParameter({"3", "4"}) int groupWireType) {
    ByteString bytes = ByteString.copyFrom(new byte[] {(byte) ((1 << 3) | groupWireType)});
    FieldLiteDescriptor fd =
        fieldDescriptor(
            "cel.expr.conformance.proto3.TestAllTypes", TestAllTypes.SINGLE_STRING_FIELD_NUMBER);

    UnsupportedOperationException e =
        assertThrows(
            UnsupportedOperationException.class,
            () -> PROTO_LITE_CEL_VALUE_CONVERTER.readSingleField(bytes, fd));

    assertThat(e).hasMessageThat().isEqualTo("Groups are not supported");
  }

  private static FieldLiteDescriptor fieldDescriptor(String messageName, int fieldNumber) {
    return PROTO_LITE_CEL_VALUE_CONVERTER.findFieldDescriptor(messageName, fieldNumber).get();
  }
}
