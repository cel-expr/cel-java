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
import static com.google.common.truth.extensions.proto.ProtoTruth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.primitives.UnsignedLong;
import com.google.protobuf.Any;
import com.google.protobuf.ByteString;
import com.google.protobuf.Empty;
import com.google.protobuf.FieldMask;
import com.google.protobuf.Int64Value;
import com.google.protobuf.Struct;
import com.google.protobuf.UInt32Value;
import com.google.protobuf.UninitializedMessageException;
import com.google.protobuf.Value;
import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import dev.cel.common.exceptions.CelNumericOverflowException;
import dev.cel.common.internal.DefaultLiteDescriptorPool;
import dev.cel.common.internal.ProtoTimeUtils;
import dev.cel.common.types.StructTypeReference;
import dev.cel.expr.conformance.proto2.TestRequiredCelDescriptor;
import dev.cel.expr.conformance.proto3.NestedTestAllTypes;
import dev.cel.expr.conformance.proto3.TestAllTypes;
import dev.cel.expr.conformance.proto3.TestAllTypes.NestedEnum;
import dev.cel.expr.conformance.proto3.TestAllTypes.NestedMessage;
import dev.cel.expr.conformance.proto3.TestAllTypesCelDescriptor;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(TestParameterInjector.class)
public class ProtoMessageLiteValueProviderTest {
  private static final String TEST_ALL_TYPES = "cel.expr.conformance.proto3.TestAllTypes";
  private static final NestedMessage NESTED = NestedMessage.newBuilder().setBb(7).build();
  private static final Value JSON_NULL =
      Value.newBuilder().setNullValue(com.google.protobuf.NullValue.NULL_VALUE).build();
  private static final ProtoLiteCelValueConverter EMPTY_CONVERTER =
      ProtoLiteCelValueConverter.newInstance(DefaultLiteDescriptorPool.newInstance());
  private static final WireMessageLite WIRE_NESTED =
      RawProtoMessageLiteValue.create(
          NESTED.toByteString(),
          "cel.expr.conformance.proto3.TestAllTypes.NestedMessage",
          EMPTY_CONVERTER);
  private static final WireMessageLite WIRE_OF_UNKNOWN_TYPE =
      RawProtoMessageLiteValue.create(NESTED.toByteString(), EMPTY_CONVERTER);
  private static final ProtoMessageLiteValueProvider VALUE_PROVIDER =
      ProtoMessageLiteValueProvider.newInstance(
          TestAllTypesCelDescriptor.getDescriptor(), TestRequiredCelDescriptor.getDescriptor());

  @Test
  public void newValue_unknownOrMapEntryType_returnsEmpty(
      @TestParameter({
            "unknownType",
            "google.protobuf.Struct.FieldsEntry",
            "cel.expr.conformance.proto3.TestAllTypes.MapStringStringEntry"
          })
          String structType) {
    assertThat(VALUE_PROVIDER.newValue(structType, ImmutableMap.of())).isEmpty();
  }

  @Test
  public void newValue_emptyFields_success() {
    Optional<Object> value = VALUE_PROVIDER.newValue(TEST_ALL_TYPES, ImmutableMap.of());
    ProtoMessageLiteValue protoMessageLiteValue = (ProtoMessageLiteValue) value.get();

    assertThat(protoMessageLiteValue.value()).isEqualTo(TestAllTypes.getDefaultInstance());
    assertThat(protoMessageLiteValue.isZeroValue()).isTrue();
    assertThat(protoMessageLiteValue.celType())
        .isEqualTo(StructTypeReference.create(TEST_ALL_TYPES));
  }

  @SuppressWarnings("ImmutableEnumChecker") // Test only
  private enum FieldTestCase {
    INT32("single_int32", -1L, TestAllTypes.newBuilder().setSingleInt32(-1)),
    INT64("single_int64", Long.MIN_VALUE, TestAllTypes.newBuilder().setSingleInt64(Long.MIN_VALUE)),
    UINT32(
        "single_uint32",
        UnsignedLong.valueOf(4294967295L),
        TestAllTypes.newBuilder().setSingleUint32(-1)),
    UINT64("single_uint64", UnsignedLong.MAX_VALUE, TestAllTypes.newBuilder().setSingleUint64(-1L)),
    SINT32("single_sint32", -2L, TestAllTypes.newBuilder().setSingleSint32(-2)),
    SINT64("single_sint64", -3L, TestAllTypes.newBuilder().setSingleSint64(-3L)),
    FIXED32(
        "single_fixed32",
        UnsignedLong.valueOf(4294967295L),
        TestAllTypes.newBuilder().setSingleFixed32(-1)),
    FIXED64(
        "single_fixed64", UnsignedLong.MAX_VALUE, TestAllTypes.newBuilder().setSingleFixed64(-1L)),
    SFIXED32("single_sfixed32", -4L, TestAllTypes.newBuilder().setSingleSfixed32(-4)),
    SFIXED64("single_sfixed64", -5L, TestAllTypes.newBuilder().setSingleSfixed64(-5L)),
    FLOAT("single_float", 1.5d, TestAllTypes.newBuilder().setSingleFloat(1.5f)),
    FLOAT_OVERFLOW(
        "single_float", 1.4e55d, TestAllTypes.newBuilder().setSingleFloat(Float.POSITIVE_INFINITY)),
    DOUBLE("single_double", -2.5d, TestAllTypes.newBuilder().setSingleDouble(-2.5d)),
    BOOL("single_bool", true, TestAllTypes.newBuilder().setSingleBool(true)),
    STRING("single_string", "héllo", TestAllTypes.newBuilder().setSingleString("héllo")),
    BYTES(
        "single_bytes",
        CelByteString.copyFromUtf8("abc"),
        TestAllTypes.newBuilder().setSingleBytes(ByteString.copyFromUtf8("abc"))),
    ENUM("standalone_enum", 2L, TestAllTypes.newBuilder().setStandaloneEnum(NestedEnum.BAZ)),
    OPEN_ENUM_UNKNOWN_VALUE(
        "standalone_enum", 99L, TestAllTypes.newBuilder().setStandaloneEnumValue(99)),
    IMPLICIT_PRESENCE_DEFAULT("single_int32", 0L, TestAllTypes.newBuilder()),
    EXPLICIT_PRESENCE_DEFAULT(
        "optional_bool", false, TestAllTypes.newBuilder().setOptionalBool(false)),
    MESSAGE(
        "single_nested_message", NESTED, TestAllTypes.newBuilder().setSingleNestedMessage(NESTED)),
    WIRE_MESSAGE(
        "single_nested_message",
        WIRE_NESTED,
        TestAllTypes.newBuilder().setSingleNestedMessage(NESTED)),
    NULL_MESSAGE("single_nested_message", NullValue.NULL_VALUE, TestAllTypes.newBuilder()),
    NULL_VALUE(
        "single_value", NullValue.NULL_VALUE, TestAllTypes.newBuilder().setSingleValue(JSON_NULL)),
    NULL_ANY(
        "single_any",
        NullValue.NULL_VALUE,
        TestAllTypes.newBuilder().setSingleAny(Any.pack(JSON_NULL))),
    WRAPPER(
        "single_int64_wrapper",
        5L,
        TestAllTypes.newBuilder().setSingleInt64Wrapper(Int64Value.of(5))),
    UINT32_WRAPPER(
        "single_uint32_wrapper",
        UnsignedLong.valueOf(4294967295L),
        TestAllTypes.newBuilder().setSingleUint32Wrapper(UInt32Value.of(-1))),
    ANY_MESSAGE("single_any", NESTED, TestAllTypes.newBuilder().setSingleAny(Any.pack(NESTED))),
    ANY_WIRE_MESSAGE(
        "single_any", WIRE_NESTED, TestAllTypes.newBuilder().setSingleAny(Any.pack(NESTED))),
    ANY_PRIMITIVE(
        "single_any", 1L, TestAllTypes.newBuilder().setSingleAny(Any.pack(Int64Value.of(1)))),
    JSON_STRUCT(
        "single_struct",
        ImmutableMap.of("a", true),
        TestAllTypes.newBuilder()
            .setSingleStruct(
                Struct.newBuilder().putFields("a", Value.newBuilder().setBoolValue(true).build()))),
    FIELD_MASK(
        "field_mask",
        FieldMask.newBuilder().addPaths("a.b").build(),
        TestAllTypes.newBuilder().setFieldMask(FieldMask.newBuilder().addPaths("a.b"))),
    EMPTY(
        "empty", ImmutableMap.of(), TestAllTypes.newBuilder().setEmpty(Empty.getDefaultInstance())),
    REPEATED_SCALAR(
        "repeated_int32",
        ImmutableList.of(1L, -2L),
        TestAllTypes.newBuilder().addRepeatedInt32(1).addRepeatedInt32(-2)),
    REPEATED_MESSAGE_DROPS_NULLS(
        "repeated_nested_message",
        ImmutableList.of(NESTED, NullValue.NULL_VALUE),
        TestAllTypes.newBuilder().addRepeatedNestedMessage(NESTED)),
    REPEATED_ANY_KEEPS_NULLS(
        "repeated_any",
        ImmutableList.of(1L, NullValue.NULL_VALUE),
        TestAllTypes.newBuilder()
            .addRepeatedAny(Any.pack(Int64Value.of(1)))
            .addRepeatedAny(Any.pack(JSON_NULL))),
    MAP(
        "map_string_message",
        ImmutableMap.of("a", NESTED, "b", NestedMessage.getDefaultInstance()),
        TestAllTypes.newBuilder()
            .putMapStringMessage("a", NESTED)
            .putMapStringMessage("b", NestedMessage.getDefaultInstance())),
    MAP_DROPS_NULLS(
        "map_bool_timestamp",
        ImmutableMap.of(true, NullValue.NULL_VALUE, false, Instant.EPOCH),
        TestAllTypes.newBuilder().putMapBoolTimestamp(false, ProtoTimeUtils.TIMESTAMP_EPOCH)),
    MAP_VALUE_KEEPS_NULLS(
        "map_string_value",
        ImmutableMap.of("k", NullValue.NULL_VALUE),
        TestAllTypes.newBuilder().putMapStringValue("k", JSON_NULL)),
    ONEOF_MESSAGE_LAST(
        ImmutableMap.of("oneof_bool", true, "oneof_msg", NESTED),
        TestAllTypes.newBuilder().setOneofMsg(NESTED)),
    ONEOF_BOOL_LAST(
        ImmutableMap.of("oneof_msg", NESTED, "oneof_bool", true),
        TestAllTypes.newBuilder().setOneofBool(true));

    private final ImmutableMap<String, Object> fields;
    private final TestAllTypes expected;

    FieldTestCase(String fieldName, Object value, TestAllTypes.Builder expected) {
      this(ImmutableMap.of(fieldName, value), expected);
    }

    FieldTestCase(ImmutableMap<String, Object> fields, TestAllTypes.Builder expected) {
      this.fields = fields;
      this.expected = expected.build();
    }
  }

  @Test
  public void newValue_fields_success(@TestParameter FieldTestCase testCase) {
    TestAllTypes message = newTestAllTypes(testCase.fields);

    assertThat(message).isEqualTo(testCase.expected);
  }

  @Test
  public void newValue_structLiteral_returnsMap() {
    ImmutableMap<String, Object> fields = ImmutableMap.of("fields", ImmutableMap.of("a", 1.0d));

    assertThat(VALUE_PROVIDER.newValue("google.protobuf.Struct", fields))
        .hasValue(ImmutableMap.of("a", 1.0d));
  }

  @SuppressWarnings("ImmutableEnumChecker") // Test only
  private enum InvalidFieldTestCase {
    UNDECLARED_FIELD("undeclared", 1L, IllegalArgumentException.class, "is not declared"),
    INT32_OVERFLOW("single_int32", 1L << 31, CelNumericOverflowException.class, "Out of range"),
    SINT32_UNDERFLOW(
        "single_sint32", -(1L << 31) - 1, CelNumericOverflowException.class, "Out of range"),
    SFIXED32_OVERFLOW(
        "single_sfixed32", 1L << 31, CelNumericOverflowException.class, "Out of range"),
    UINT32_OVERFLOW(
        "single_uint32",
        UnsignedLong.valueOf(1L << 32),
        CelNumericOverflowException.class,
        "out of range"),
    FIXED32_OVERFLOW(
        "single_fixed32",
        UnsignedLong.valueOf(1L << 32),
        CelNumericOverflowException.class,
        "out of range"),
    NULL_SCALAR(
        "single_int32",
        NullValue.NULL_VALUE,
        IllegalArgumentException.class,
        "null cannot be assigned"),
    NULL_MAP(
        "map_string_string",
        NullValue.NULL_VALUE,
        IllegalArgumentException.class,
        "null cannot be assigned"),
    NULL_LIST(
        "repeated_nested_message",
        NullValue.NULL_VALUE,
        IllegalArgumentException.class,
        "null cannot be assigned"),
    NULL_STRUCT(
        "single_struct",
        NullValue.NULL_VALUE,
        IllegalArgumentException.class,
        "null cannot be assigned"),
    NULL_LIST_VALUE(
        "list_value",
        NullValue.NULL_VALUE,
        IllegalArgumentException.class,
        "null cannot be assigned"),
    MESSAGE_OF_ANOTHER_TYPE(
        "single_nested_message",
        TestAllTypes.getDefaultInstance(),
        IllegalArgumentException.class,
        "expected a message of type"),
    UNREGISTERED_MESSAGE_CLASS(
        "single_nested_message",
        NestedTestAllTypes.getDefaultInstance(),
        IllegalArgumentException.class,
        "expected a message of type"),
    WIRE_MESSAGE_OF_UNKNOWN_TYPE(
        "single_nested_message",
        WIRE_OF_UNKNOWN_TYPE,
        IllegalArgumentException.class,
        "expected a message of type"),
    FIELD_MASK_OF_ANOTHER_TYPE(
        "field_mask", NESTED, IllegalArgumentException.class, "expected a message of type"),
    ANY_OF_UNKNOWN_TYPE(
        "single_any", WIRE_OF_UNKNOWN_TYPE, IllegalArgumentException.class, "unknown type"),
    NON_EMPTY_MAP_AS_EMPTY(
        "empty", ImmutableMap.of("a", 1L), IllegalArgumentException.class, "expected an empty map"),
    SCALAR_OF_ANOTHER_TYPE(
        "single_int64", "a", IllegalArgumentException.class, "unexpected value type");

    private final String fieldName;
    private final Object value;
    private final Class<? extends Exception> expectedException;
    private final String expectedMessage;

    InvalidFieldTestCase(
        String fieldName,
        Object value,
        Class<? extends Exception> expectedException,
        String expectedMessage) {
      this.fieldName = fieldName;
      this.value = value;
      this.expectedException = expectedException;
      this.expectedMessage = expectedMessage;
    }
  }

  @Test
  public void newValue_invalidField_throws(@TestParameter InvalidFieldTestCase testCase) {
    ImmutableMap<String, Object> fields = ImmutableMap.of(testCase.fieldName, testCase.value);

    Exception e =
        assertThrows(
            testCase.expectedException, () -> VALUE_PROVIDER.newValue(TEST_ALL_TYPES, fields));
    assertThat(e).hasMessageThat().contains(testCase.expectedMessage);
  }

  @Test
  public void newValue_missingRequiredField_throws() {
    assertThrows(
        UninitializedMessageException.class,
        () ->
            VALUE_PROVIDER.newValue("cel.expr.conformance.proto2.TestRequired", ImmutableMap.of()));
  }

  @Test
  public void newValue_outOfRangeDurationLiteral_throws() {
    ImmutableMap<String, Object> fields = ImmutableMap.of("seconds", 315576000001L);

    IllegalArgumentException e =
        assertThrows(
            IllegalArgumentException.class,
            () -> VALUE_PROVIDER.newValue("google.protobuf.Duration", fields));
    assertThat(e).hasMessageThat().contains("is not valid");
  }

  @Test
  public void getProtoLiteCelValueConverter() {
    assertThat(VALUE_PROVIDER.protoCelValueConverter()).isNotNull();
  }

  private static TestAllTypes newTestAllTypes(Map<String, Object> fields) {
    Object value = VALUE_PROVIDER.newValue(TEST_ALL_TYPES, fields).get();
    return (TestAllTypes) ((ProtoMessageLiteValue) value).value();
  }
}
