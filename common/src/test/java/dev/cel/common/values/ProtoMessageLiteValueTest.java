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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.primitives.UnsignedLong;
import com.google.protobuf.Any;
import com.google.protobuf.BoolValue;
import com.google.protobuf.ByteString;
import com.google.protobuf.BytesValue;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.DoubleValue;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.ExtensionRegistryLite;
import com.google.protobuf.FloatValue;
import com.google.protobuf.Int32Value;
import com.google.protobuf.Int64Value;
import com.google.protobuf.StringValue;
import com.google.protobuf.Timestamp;
import com.google.protobuf.UInt32Value;
import com.google.protobuf.UInt64Value;
import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import dev.cel.common.internal.CelLiteDescriptorPool;
import dev.cel.common.internal.DefaultLiteDescriptorPool;
import dev.cel.expr.conformance.proto3.TestAllTypes;
import dev.cel.expr.conformance.proto3.TestAllTypes.NestedEnum;
import dev.cel.expr.conformance.proto3.TestAllTypes.NestedMessage;
import dev.cel.expr.conformance.proto3.TestAllTypesCelDescriptor;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.time.Instant;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(TestParameterInjector.class)
public class ProtoMessageLiteValueTest {
  private static final CelLiteDescriptorPool DESCRIPTOR_POOL =
      DefaultLiteDescriptorPool.newInstance(
          ImmutableSet.of(TestAllTypesCelDescriptor.getDescriptor()));

  private static final ProtoLiteCelValueConverter PROTO_LITE_CEL_VALUE_CONVERTER =
      ProtoLiteCelValueConverter.newInstance(DESCRIPTOR_POOL);

  @Test
  public void create_withEmptyMessage() {
    ProtoMessageLiteValue messageLiteValue =
        ProtoMessageLiteValue.create(
            TestAllTypes.getDefaultInstance(),
            "cel.expr.conformance.proto3.TestAllTypes",
            PROTO_LITE_CEL_VALUE_CONVERTER);

    assertThat(messageLiteValue.value()).isEqualTo(TestAllTypes.getDefaultInstance());
    assertThat(messageLiteValue.isZeroValue()).isTrue();
  }

  @Test
  public void create_withPopulatedMessage() {
    ProtoMessageLiteValue messageLiteValue =
        ProtoMessageLiteValue.create(
            TestAllTypes.newBuilder().setSingleInt64(1L).build(),
            "cel.expr.conformance.proto3.TestAllTypes",
            PROTO_LITE_CEL_VALUE_CONVERTER);

    assertThat(messageLiteValue.value())
        .isEqualTo(TestAllTypes.newBuilder().setSingleInt64(1L).build());
    assertThat(messageLiteValue.isZeroValue()).isFalse();
  }

  @SuppressWarnings("ImmutableEnumChecker") // Test only
  private enum SelectFieldTestCase {
    BOOL("single_bool", true),
    INT32("single_int32", 4L),
    INT64("single_int64", 5L),
    SINT32("single_sint32", 1L),
    SINT64("single_sint64", 2L),
    UINT32("single_uint32", UnsignedLong.valueOf(1L)),
    UINT64("single_uint64", UnsignedLong.MAX_VALUE),
    FLOAT("single_float", 1.5d),
    DOUBLE("single_double", 2.5d),
    FIXED32("single_fixed32", 20),
    SFIXED32("single_sfixed32", 30),
    FIXED64("single_fixed64", 40),
    SFIXED64("single_sfixed64", 50),
    STRING("single_string", "test"),
    BYTES("single_bytes", CelByteString.of(new byte[] {0x01})),
    DURATION("single_duration", Duration.ofSeconds(100)),
    TIMESTAMP("single_timestamp", Instant.ofEpochSecond(100)),
    INT32_WRAPPER("single_int32_wrapper", 5L),
    INT64_WRAPPER("single_int64_wrapper", 10L),
    UINT32_WRAPPER("single_uint32_wrapper", UnsignedLong.valueOf(1L)),
    UINT64_WRAPPER("single_uint64_wrapper", UnsignedLong.MAX_VALUE),
    FLOAT_WRAPPER("single_float_wrapper", 7.5d),
    DOUBLE_WRAPPER("single_double_wrapper", 8.5d),
    STRING_WRAPPER("single_string_wrapper", "hello"),
    BYTES_WRAPPER("single_bytes_wrapper", CelByteString.of(new byte[] {0x02})),
    REPEATED_INT64("repeated_int64", ImmutableList.of(5L, 6L)),
    REPEATED_UINT64(
        "repeated_uint64", ImmutableList.of(UnsignedLong.valueOf(7L), UnsignedLong.valueOf(8L))),
    REPEATED_FLOAT("repeated_float", ImmutableList.of(1.5d, 2.5d)),
    REPEATED_DOUBLE("repeated_double", ImmutableList.of(3.5d, 4.5d)),

    REPEATED_STRING("repeated_string", ImmutableList.of("foo", "bar")),

    MAP_INT64_INT64("map_int64_int64", ImmutableMap.of(1L, 2L, 3L, 4L)),

    MAP_UINT32_UINT64(
        "map_uint32_uint64",
        ImmutableMap.of(
            UnsignedLong.valueOf(5L),
            UnsignedLong.valueOf(6L),
            UnsignedLong.valueOf(7L),
            UnsignedLong.valueOf(8L))),

    MAP_STRING_STRING("map_string_string", ImmutableMap.of("a", "b")),

    NESTED_ENUM("standalone_enum", 1L);

    private final String fieldName;
    private final Object value;

    SelectFieldTestCase(String fieldName, Object value) {
      this.fieldName = fieldName;
      this.value = value;
    }
  }

  @Test
  public void selectField_success(@TestParameter SelectFieldTestCase testCase) {
    TestAllTypes testAllTypes =
        TestAllTypes.newBuilder()
            .setSingleBool(true)
            .setSingleInt32(4)
            .setSingleInt64(5L)
            .setSingleSint32(1)
            .setSingleSint64(2L)
            .setSingleUint32(1)
            .setSingleUint64(UnsignedLong.MAX_VALUE.longValue())
            .setSingleFixed32(20)
            .setSingleSfixed32(30)
            .setSingleFixed64(40)
            .setSingleSfixed64(50)
            .setSingleFloat(1.5f)
            .setSingleDouble(2.5d)
            .setSingleString("test")
            .setSingleBytes(ByteString.copyFrom(new byte[] {0x01}))
            .setSingleAny(Any.pack(DynamicMessage.newBuilder(BoolValue.of(true)).build()))
            .setSingleDuration(com.google.protobuf.Duration.newBuilder().setSeconds(100))
            .setSingleTimestamp(Timestamp.newBuilder().setSeconds(100))
            .setSingleInt32Wrapper(Int32Value.of(5))
            .setSingleInt64Wrapper(Int64Value.of(10L))
            .setSingleUint32Wrapper(UInt32Value.of(1))
            .setSingleUint64Wrapper(UInt64Value.of(UnsignedLong.MAX_VALUE.longValue()))
            .setSingleStringWrapper(StringValue.of("hello"))
            .setSingleFloatWrapper(FloatValue.of(7.5f))
            .setSingleDoubleWrapper(DoubleValue.of(8.5d))
            .setSingleBytesWrapper(BytesValue.of(ByteString.copyFrom(new byte[] {0x02})))
            .addRepeatedInt64(5L)
            .addRepeatedInt64(6L)
            .addRepeatedUint64(7L)
            .addRepeatedUint64(8L)
            .addRepeatedFloat(1.5f)
            .addRepeatedFloat(2.5f)
            .addRepeatedDouble(3.5d)
            .addRepeatedDouble(4.5d)
            .addRepeatedString("foo")
            .addRepeatedString("bar")
            .putMapStringString("a", "b")
            .putMapInt64Int64(1L, 2L)
            .putMapInt64Int64(3L, 4L)
            .putMapUint32Uint64(5, 6L)
            .putMapUint32Uint64(7, 8L)
            .setStandaloneMessage(NestedMessage.getDefaultInstance())
            .setStandaloneEnum(NestedEnum.BAR)
            .build();
    ProtoMessageLiteValue protoMessageValue =
        ProtoMessageLiteValue.create(
            testAllTypes,
            "cel.expr.conformance.proto3.TestAllTypes",
            PROTO_LITE_CEL_VALUE_CONVERTER);

    Object selectedValue = protoMessageValue.select(testCase.fieldName);

    assertThat(selectedValue).isEqualTo(testCase.value);
  }

  @SuppressWarnings("ImmutableEnumChecker") // Test only
  private enum DefaultValueTestCase {
    BOOL("single_bool", false),
    INT32("single_int32", 0L),
    INT64("single_int64", 0L),
    SINT32("single_sint32", 0L),
    SINT64("single_sint64", 0L),
    UINT32("single_uint32", UnsignedLong.ZERO),
    UINT64("single_uint64", UnsignedLong.ZERO),
    FIXED32("single_fixed32", 0),
    SFIXED32("single_sfixed32", 0),
    FIXED64("single_fixed64", 0),
    SFIXED64("single_sfixed64", 0),
    FLOAT("single_float", 0d),
    DOUBLE("single_double", 0d),
    STRING("single_string", ""),
    BYTES("single_bytes", CelByteString.EMPTY),
    DURATION("single_duration", Duration.ZERO),
    TIMESTAMP("single_timestamp", Instant.EPOCH),
    INT32_WRAPPER("single_int32_wrapper", NullValue.NULL_VALUE),
    INT64_WRAPPER("single_int64_wrapper", NullValue.NULL_VALUE),
    UINT32_WRAPPER("single_uint32_wrapper", NullValue.NULL_VALUE),
    UINT64_WRAPPER("single_uint64_wrapper", NullValue.NULL_VALUE),
    FLOAT_WRAPPER("single_float_wrapper", NullValue.NULL_VALUE),
    DOUBLE_WRAPPER("single_double_wrapper", NullValue.NULL_VALUE),
    STRING_WRAPPER("single_string_wrapper", NullValue.NULL_VALUE),
    BYTES_WRAPPER("single_bytes_wrapper", NullValue.NULL_VALUE),
    REPEATED_INT64("repeated_int64", ImmutableList.of()),
    MAP_INT64_INT64("map_int64_int64", ImmutableMap.of()),
    NESTED_ENUM("standalone_enum", 0L),
    NESTED_MESSAGE(
        "single_nested_message",
        ProtoMessageLiteValue.create(
            NestedMessage.getDefaultInstance(),
            "cel.expr.conformance.proto3.TestAllTypes.NestedMessage",
            PROTO_LITE_CEL_VALUE_CONVERTER));

    private final String fieldName;
    private final Object value;

    DefaultValueTestCase(String fieldName, Object value) {
      this.fieldName = fieldName;
      this.value = value;
    }
  }

  @Test
  public void selectField_defaultValue(@TestParameter DefaultValueTestCase testCase) {
    ProtoMessageLiteValue protoMessageValue =
        ProtoMessageLiteValue.create(
            TestAllTypes.getDefaultInstance(),
            "cel.expr.conformance.proto3.TestAllTypes",
            PROTO_LITE_CEL_VALUE_CONVERTER);

    Object selectedValue = protoMessageValue.select(testCase.fieldName);

    assertThat(selectedValue).isEqualTo(testCase.value);
  }

  @Test
  public void unknownFields_retainsUnknownWireFields() throws Exception {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    CodedOutputStream cos = CodedOutputStream.newInstance(baos);
    cos.writeInt64(999, 12345L);
    cos.writeString(1000, "hello unknown");
    cos.flush();

    TestAllTypes msgWithUnknown =
        TestAllTypes.parseFrom(baos.toByteArray(), ExtensionRegistryLite.getEmptyRegistry());
    ProtoMessageLiteValue messageLiteValue =
        ProtoMessageLiteValue.create(
            msgWithUnknown,
            "cel.expr.conformance.proto3.TestAllTypes",
            PROTO_LITE_CEL_VALUE_CONVERTER);

    assertThat(messageLiteValue.unknownFields()).valuesForKey(999).containsExactly(12345L);
    assertThat(messageLiteValue.unknownFields())
        .valuesForKey(1000)
        .containsExactly(ByteString.copyFromUtf8("hello unknown"));
  }
}
