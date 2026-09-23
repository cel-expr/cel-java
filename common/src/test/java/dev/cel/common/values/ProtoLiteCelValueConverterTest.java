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
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Multimap;
import com.google.common.primitives.UnsignedLong;
import com.google.protobuf.BoolValue;
import com.google.protobuf.ByteString;
import com.google.protobuf.BytesValue;
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
import dev.cel.common.values.ProtoLiteCelValueConverter.MessageFields;
import dev.cel.expr.conformance.proto3.TestAllTypes;
import dev.cel.expr.conformance.proto3.TestAllTypesCelDescriptor;
import dev.cel.protobuf.CelLiteDescriptor.FieldLiteDescriptor;
import dev.cel.protobuf.CelLiteDescriptor.MessageLiteDescriptor;
import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.NoSuchElementException;
import java.util.Optional;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(TestParameterInjector.class)
public class ProtoLiteCelValueConverterTest {
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
          ImmutableSet.of(TestAllTypesCelDescriptor.getDescriptor()));

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

  @Test
  public void readAllFields_repeatedFields_packedBytesCombinations(
      @TestParameter RepeatedFieldBytesTestCase testCase) throws Exception {
    MessageFields fields =
        PROTO_LITE_CEL_VALUE_CONVERTER.readAllFields(
            testCase.bytes, "cel.expr.conformance.proto3.TestAllTypes");

    assertThat(fields.values()).containsExactly("repeated_int64", ImmutableList.of(1L, 2L, 3L));
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
    INT64(new byte[] {-96, -100, 1, 1}, "2500: 1", ImmutableListMultimap.of(2500, 1L)),
    FIXED32(
        new byte[] {-83, -100, 1, 2, 0, 0, 0},
        "2501: 0x00000002",
        ImmutableListMultimap.of(2501, 2)),
    FIXED64(
        new byte[] {-79, -100, 1, 3, 0, 0, 0, 0, 0, 0, 0},
        "2502: 0x0000000000000003",
        ImmutableListMultimap.of(2502, 3L)),
    STRING(
        new byte[] {-70, -100, 1, 11, 72, 101, 108, 108, 111, 32, 119, 111, 114, 108, 100},
        "2503: \"Hello world\"",
        ImmutableListMultimap.of(2503, ByteString.copyFromUtf8("Hello world"))),
    REPEATED_INT64(
        new byte[] {-62, -100, 1, 2, 4, 5},
        "2504: \"\\004\\005\"",
        ImmutableListMultimap.of(2504, ByteString.copyFrom(new byte[] {4, 5}))),
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
            + "}",
        ImmutableListMultimap.of(
            2505,
            ByteString.copyFromUtf8("\n\003foo\020\004"),
            2505,
            ByteString.copyFromUtf8("\n\003bar\020\005")));

    private final byte[] bytes;
    private final String formattedOutput;
    private final Multimap<Integer, Object> unknownMap;

    UnknownFieldsTestCase(
        byte[] bytes, String formattedOutput, Multimap<Integer, Object> unknownMap) {
      this.bytes = bytes;
      this.formattedOutput = formattedOutput;
      this.unknownMap = unknownMap;
    }
  }

  @Test
  public void unknowns_repeatedEncodedBytes_allRecordsKeptWithKeysSorted() throws Exception {
    // 2500: 2
    // 2504: \"\\004\\005\""
    // 2501: 0x00000002
    // 2500: 1
    byte[] bytes =
        new byte[] {
          -96, -100, 1, 2, // keep
          -62, -100, 1, 2, 4, 5, // keep
          -83, -100, 1, 2, 0, 0, 0, // keep
          -96, -100, 1, 1 // keep
        };

    MessageFields messageFields =
        PROTO_LITE_CEL_VALUE_CONVERTER.readAllFields(
            bytes, "cel.expr.conformance.proto3.TestAllTypes");

    assertThat(messageFields.values()).isEmpty();
    assertThat(messageFields.unknowns())
        .containsExactly(
            2500, 2L, 2500, 1L, 2501, 2, 2504, ByteString.copyFrom(new byte[] {0x04, 0x05}))
        .inOrder();
  }

  @Test
  public void readAllFields_unknownFields(@TestParameter UnknownFieldsTestCase testCase)
      throws Exception {
    TestAllTypes parsedMsg =
        TestAllTypes.parseFrom(testCase.bytes, ExtensionRegistryLite.getEmptyRegistry());

    MessageFields messageFields =
        PROTO_LITE_CEL_VALUE_CONVERTER.readAllFields(
            testCase.bytes, "cel.expr.conformance.proto3.TestAllTypes");

    assertThat(messageFields.values()).isEmpty();
    assertThat(messageFields.unknowns()).containsExactlyEntriesIn(testCase.unknownMap).inOrder();
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
  @SuppressWarnings("unchecked")
  public void readAllFields_unknownFieldsWithValues() throws Exception {
    byte[] unknownMessageBytes = {
      -70, 4, 11, 8, 1, 17, 0, 0, 0, 0, 0, 0, -8, 63, -70, 4, 11, 8, 0, 17, 0, 0, 0, 0, 0, 0, 4, 64,
      -96, -100, 1, 1, -83, -100, 1, 2, 0, 0, 0, -79, -100, 1, 3, 0, 0, 0, 0, 0, 0, 0, -70, -100, 1,
      11, 72, 101, 108, 108, 111, 32, 119, 111, 114, 108, 100, -62, -100, 1, 2, 4, 5, -54, -100, 1,
      7, 10, 3, 102, 111, 111, 16, 4, -54, -100, 1, 7, 10, 3, 98, 97, 114, 16, 5
    };
    TestAllTypes parsedMsg =
        TestAllTypes.parseFrom(unknownMessageBytes, ExtensionRegistryLite.getEmptyRegistry());

    MessageFields fields =
        PROTO_LITE_CEL_VALUE_CONVERTER.readAllFields(
            unknownMessageBytes, "cel.expr.conformance.proto3.TestAllTypes");

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
    assertThat(fields.values()).containsKey("map_bool_double");
    LinkedHashMap<Boolean, Double> mapBoolDoubleValues =
        (LinkedHashMap<Boolean, Double>) fields.values().get("map_bool_double");
    assertThat(mapBoolDoubleValues).containsExactly(true, 1.5d, false, 2.5d).inOrder();
    ImmutableListMultimap<Integer, Object> unknownValues = fields.unknowns();
    assertThat(unknownValues)
        .containsExactly(
            2500,
            1L,
            2501,
            2,
            2502,
            3L,
            2503,
            ByteString.copyFromUtf8("Hello world"),
            2504,
            ByteString.copyFrom(new byte[] {0x04, 0x05}),
            2505,
            ByteString.copyFromUtf8("\n\003foo\020\004"),
            2505,
            ByteString.copyFromUtf8("\n\003bar\020\005"))
        .inOrder();
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
  public void getDefaultCelValue_nestedMessageWithoutDescriptor_throwsNoSuchElementException() {
    FieldLiteDescriptor nestedMsgField =
        DESCRIPTOR_POOL
            .getDescriptorOrThrow("cel.expr.conformance.proto3.TestAllTypes")
            .getByFieldNameOrThrow("single_nested_message");
    ProtoLiteCelValueConverter converterWithoutNested =
        ProtoLiteCelValueConverter.newInstance(EMPTY_DESCRIPTOR_POOL);

    assertThrows(
        NoSuchElementException.class,
        () -> converterWithoutNested.getDefaultCelValue(nestedMsgField));
  }

  @Test
  public void tryDecodeWellKnownProto_validBytes_returnsDecodedValue() {
    Int32Value int32Value = Int32Value.of(42);

    Optional<Object> decoded =
        PROTO_LITE_CEL_VALUE_CONVERTER.tryDecodeWellKnownProto(
            int32Value.toByteString(), "google.protobuf.Int32Value");

    assertThat(decoded).hasValue(42L);
  }

  @Test
  public void tryDecodeWellKnownProto_notWellKnownType_returnsEmpty() {
    Optional<Object> decoded =
        PROTO_LITE_CEL_VALUE_CONVERTER.tryDecodeWellKnownProto(
            ByteString.EMPTY, "cel.expr.conformance.proto3.TestAllTypes");

    assertThat(decoded).isEmpty();
  }

  @Test
  public void tryDecodeWellKnownProto_missingDescriptor_returnsEmpty() {
    ProtoLiteCelValueConverter converter =
        ProtoLiteCelValueConverter.newInstance(EMPTY_DESCRIPTOR_POOL);

    Optional<Object> decoded =
        converter.tryDecodeWellKnownProto(ByteString.EMPTY, "google.protobuf.Int32Value");

    assertThat(decoded).isEmpty();
  }

  @Test
  public void tryDecodeWellKnownProto_invalidBytes_throwsIllegalArgumentException() {
    ByteString corruptBytes = ByteString.copyFrom(new byte[] {(byte) 0xFF, (byte) 0xFF});

    IllegalArgumentException exception =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                PROTO_LITE_CEL_VALUE_CONVERTER.tryDecodeWellKnownProto(
                    corruptBytes, "google.protobuf.Int32Value"));

    assertThat(exception)
        .hasMessageThat()
        .contains("Failed to decode well-known proto of type: google.protobuf.Int32Value");
    assertThat(exception).hasCauseThat().isInstanceOf(IOException.class);
  }

  @Test
  public void tryDecodeWellKnownProto_anyType_throwsUnsupportedOperationException() {
    UnsupportedOperationException exception =
        assertThrows(
            UnsupportedOperationException.class,
            () ->
                PROTO_LITE_CEL_VALUE_CONVERTER.tryDecodeWellKnownProto(
                    ByteString.EMPTY, "google.protobuf.Any"));

    assertThat(exception).hasMessageThat().contains("ANY_VALUE");
  }

  @Test
  public void hasDescriptor_returnsExpectedResult() {
    assertThat(
            PROTO_LITE_CEL_VALUE_CONVERTER.hasDescriptor(
                "cel.expr.conformance.proto3.TestAllTypes"))
        .isTrue();
    assertThat(PROTO_LITE_CEL_VALUE_CONVERTER.hasDescriptor("unknown.Type")).isFalse();
  }
}
