// Copyright 2023 Google LLC
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
import com.google.common.primitives.UnsignedLong;
import com.google.protobuf.Any;
import com.google.protobuf.BoolValue;
import com.google.protobuf.ByteString;
import com.google.protobuf.BytesValue;
import com.google.protobuf.DoubleValue;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.FieldMask;
import com.google.protobuf.FloatValue;
import com.google.protobuf.Int32Value;
import com.google.protobuf.Int64Value;
import com.google.protobuf.ListValue;
import com.google.protobuf.StringValue;
import com.google.protobuf.Struct;
import com.google.protobuf.Timestamp;
import com.google.protobuf.UInt32Value;
import com.google.protobuf.UInt64Value;
import com.google.protobuf.Value;
import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import com.google.testing.junit.testparameterinjector.TestParameters;
import dev.cel.common.CelDescriptorUtil;
import dev.cel.common.CelOptions;
import dev.cel.common.internal.CelDescriptorPool;
import dev.cel.common.internal.DefaultDescriptorPool;
import dev.cel.common.internal.DefaultMessageFactory;
import dev.cel.common.internal.DynamicProto;
import dev.cel.common.types.StructTypeReference;
import dev.cel.expr.conformance.proto2.TestAllTypes;
import dev.cel.expr.conformance.proto2.TestAllTypes.NestedEnum;
import dev.cel.expr.conformance.proto2.TestAllTypes.NestedMessage;
import dev.cel.expr.conformance.proto2.TestAllTypesExtensions;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(TestParameterInjector.class)
public final class ProtoMessageValueTest {

  private static final ProtoCelValueConverter PROTO_CEL_VALUE_CONVERTER =
      ProtoCelValueConverter.newInstance(
          DefaultDescriptorPool.INSTANCE,
          DynamicProto.create(DefaultMessageFactory.INSTANCE),
          CelOptions.DEFAULT);

  @Test
  public void emptyProtoMessage() {
    ProtoMessageValue protoMessageValue =
        ProtoMessageValue.create(
            TestAllTypes.getDefaultInstance(),
            DefaultDescriptorPool.INSTANCE,
            PROTO_CEL_VALUE_CONVERTER,
            /* enableJsonFieldNames= */ false);

    assertThat(protoMessageValue.value()).isEqualTo(TestAllTypes.getDefaultInstance());
    assertThat(protoMessageValue.isZeroValue()).isTrue();
  }

  @Test
  public void constructProtoMessage() {
    TestAllTypes testAllTypes =
        TestAllTypes.newBuilder().setSingleBool(true).setSingleInt64(5L).build();
    ProtoMessageValue protoMessageValue =
        ProtoMessageValue.create(
            testAllTypes, DefaultDescriptorPool.INSTANCE, PROTO_CEL_VALUE_CONVERTER, false);

    assertThat(protoMessageValue.value()).isEqualTo(testAllTypes);
    assertThat(protoMessageValue.isZeroValue()).isFalse();
  }

  @Test
  public void findField_fieldIsSet_fieldExists() {
    TestAllTypes testAllTypes =
        TestAllTypes.newBuilder()
            .setSingleBool(true)
            .setSingleInt64(5L)
            .addRepeatedInt64(5L)
            .build();
    ProtoMessageValue protoMessageValue =
        ProtoMessageValue.create(
            testAllTypes, DefaultDescriptorPool.INSTANCE, PROTO_CEL_VALUE_CONVERTER, false);

    assertThat(protoMessageValue.find("single_bool")).isPresent();
    assertThat(protoMessageValue.find("single_int64")).isPresent();
    assertThat(protoMessageValue.find("repeated_int64")).isPresent();
  }

  @Test
  public void findField_fieldIsUnset_fieldDoesNotExist() {
    ProtoMessageValue protoMessageValue =
        ProtoMessageValue.create(
            TestAllTypes.getDefaultInstance(),
            DefaultDescriptorPool.INSTANCE,
            PROTO_CEL_VALUE_CONVERTER,
            /* enableJsonFieldNames= */ false);

    assertThat(protoMessageValue.find("single_int32")).isEmpty();
    assertThat(protoMessageValue.find("single_uint64")).isEmpty();
    assertThat(protoMessageValue.find("repeated_int32")).isEmpty();
  }

  @Test
  public void findField_fieldIsUndeclared_throwsException() {
    ProtoMessageValue protoMessageValue =
        ProtoMessageValue.create(
            TestAllTypes.getDefaultInstance(),
            DefaultDescriptorPool.INSTANCE,
            PROTO_CEL_VALUE_CONVERTER,
            /* enableJsonFieldNames= */ false);

    IllegalArgumentException exception =
        assertThrows(IllegalArgumentException.class, () -> protoMessageValue.select("bogus"));
    assertThat(exception)
        .hasMessageThat()
        .isEqualTo(
            "field 'bogus' is not declared in message"
                + " 'cel.expr.conformance.proto2.TestAllTypes'");
  }

  @Test
  public void findField_extensionField_success() {
    CelDescriptorPool descriptorPool =
        DefaultDescriptorPool.create(
            CelDescriptorUtil.getAllDescriptorsFromFileDescriptor(
                ImmutableList.of(TestAllTypesExtensions.getDescriptor())));
    TestAllTypes proto2Message =
        TestAllTypes.newBuilder().setExtension(TestAllTypesExtensions.int32Ext, 1).build();

    ProtoMessageValue protoMessageValue =
        ProtoMessageValue.create(proto2Message, descriptorPool, PROTO_CEL_VALUE_CONVERTER, false);

    assertThat(protoMessageValue.find("cel.expr.conformance.proto2.int32_ext")).isPresent();
  }

  @Test
  public void findField_extensionField_throwsWhenDescriptorMissing() {
    TestAllTypes proto2Message =
        TestAllTypes.newBuilder().setExtension(TestAllTypesExtensions.int32Ext, 1).build();

    ProtoMessageValue protoMessageValue =
        ProtoMessageValue.create(
            proto2Message, DefaultDescriptorPool.INSTANCE, PROTO_CEL_VALUE_CONVERTER, false);

    IllegalArgumentException exception =
        assertThrows(
            IllegalArgumentException.class,
            () -> protoMessageValue.select("cel.expr.conformance.proto2.int32_ext"));
    assertThat(exception)
        .hasMessageThat()
        .isEqualTo(
            "field 'cel.expr.conformance.proto2.int32_ext' is not declared in message"
                + " 'cel.expr.conformance.proto2.TestAllTypes'");
  }

  @SuppressWarnings("ImmutableEnumChecker") // Test only
  private enum SelectFieldTestCase {
    // Primitives
    BOOL("single_bool", true),
    INT32("single_int32", 4L),
    INT64("single_int64", 5L),
    UINT32("single_uint32", UnsignedLong.valueOf(1L)),
    UINT64("single_uint64", UnsignedLong.MAX_VALUE),
    FLOAT("single_float", 1.5d),
    DOUBLE("single_double", 2.5d),
    STRING("single_string", "test"),
    BYTES("single_bytes", CelByteString.of(new byte[] {0x01})),
    // Well known types
    ANY("single_any", true),
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
    REPEATED_INT64("repeated_int64", ImmutableList.of(5L)),
    MAP_STRING_STRING("map_string_string", ImmutableMap.of("a", "b")),
    NESTED_MESSAGE(
        "standalone_message",
        ProtoMessageValue.create(
            NestedMessage.getDefaultInstance(),
            DefaultDescriptorPool.INSTANCE,
            PROTO_CEL_VALUE_CONVERTER,
            /* enableJsonFieldNames= */ false)),
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
    ProtoMessageValue protoMessageValue =
        ProtoMessageValue.create(
            createPopulatedTestAllTypes(),
            DefaultDescriptorPool.INSTANCE,
            PROTO_CEL_VALUE_CONVERTER,
            /* enableJsonFieldNames= */ false);

    assertThat(protoMessageValue.select(testCase.fieldName)).isEqualTo(testCase.value);
  }

  @Test
  public void selectField_dynamicMessage_success() {
    TestAllTypes testAllTypes =
        TestAllTypes.newBuilder().setSingleInt32Wrapper(Int32Value.of(5)).build();

    ProtoMessageValue protoMessageValue =
        ProtoMessageValue.create(
            DynamicMessage.newBuilder(testAllTypes).build(),
            DefaultDescriptorPool.INSTANCE,
            PROTO_CEL_VALUE_CONVERTER,
            /* enableJsonFieldNames= */ false);

    assertThat(protoMessageValue.select("single_int32_wrapper")).isEqualTo(5);
  }

  @Test
  @TestParameters("{nanos: 1000000001}")
  @TestParameters("{nanos: -1000000001}")
  public void selectField_timestampNanosOutOfRange_success(int nanos) {
    TestAllTypes testAllTypes =
        TestAllTypes.newBuilder()
            .setSingleTimestamp(Timestamp.newBuilder().setNanos(nanos))
            .build();

    ProtoMessageValue protoMessageValue =
        ProtoMessageValue.create(
            testAllTypes, DefaultDescriptorPool.INSTANCE, PROTO_CEL_VALUE_CONVERTER, false);

    assertThat(protoMessageValue.select("single_timestamp"))
        .isEqualTo(Instant.ofEpochSecond(0, nanos));
  }

  @Test
  @TestParameters("{seconds: 0, nanos: 1000000001}")
  @TestParameters("{seconds: 0, nanos: -1000000001}")
  @TestParameters("{seconds: -10, nanos: 1000000001}")
  @TestParameters("{seconds: 10, nanos: -1000000001}")
  public void selectField_durationOutOfRange_success(int seconds, int nanos) {
    TestAllTypes testAllTypes =
        TestAllTypes.newBuilder()
            .setSingleDuration(
                com.google.protobuf.Duration.newBuilder().setSeconds(seconds).setNanos(nanos))
            .build();

    ProtoMessageValue protoMessageValue =
        ProtoMessageValue.create(
            testAllTypes, DefaultDescriptorPool.INSTANCE, PROTO_CEL_VALUE_CONVERTER, false);

    assertThat(protoMessageValue.select("single_duration"))
        .isEqualTo(Duration.ofSeconds(seconds, nanos));
  }

  @Test
  public void selectField_fieldMask_returnsProtoMessageValue() {
    TestAllTypes testAllTypes =
        TestAllTypes.newBuilder()
            .setFieldMask(FieldMask.newBuilder().addPaths("foo").addPaths("bar"))
            .build();

    ProtoMessageValue protoMessageValue =
        ProtoMessageValue.create(
            testAllTypes, DefaultDescriptorPool.INSTANCE, PROTO_CEL_VALUE_CONVERTER, false);

    Object selected = protoMessageValue.select("field_mask");
    assertThat(selected).isInstanceOf(ProtoMessageValue.class);
    assertThat(((ProtoMessageValue) selected).select("paths"))
        .isEqualTo(ImmutableList.of("foo", "bar"));
  }

  @Test
  public void selectField_fixed32_returnsUnsignedLong() {
    TestAllTypes testAllTypes = TestAllTypes.newBuilder().setSingleFixed32(1).build();

    ProtoMessageValue protoMessageValue =
        ProtoMessageValue.create(
            testAllTypes, DefaultDescriptorPool.INSTANCE, PROTO_CEL_VALUE_CONVERTER, false);

    assertThat(protoMessageValue.select("single_fixed32")).isEqualTo(UnsignedLong.valueOf(1L));
  }

  @Test
  public void selectField_fixed64_returnsUnsignedLong() {
    TestAllTypes testAllTypes =
        TestAllTypes.newBuilder().setSingleFixed64(UnsignedLong.MAX_VALUE.longValue()).build();

    ProtoMessageValue protoMessageValue =
        ProtoMessageValue.create(
            testAllTypes, DefaultDescriptorPool.INSTANCE, PROTO_CEL_VALUE_CONVERTER, false);

    assertThat(protoMessageValue.select("single_fixed64")).isEqualTo(UnsignedLong.MAX_VALUE);
  }

  @SuppressWarnings("ImmutableEnumChecker") // Test only
  private enum SelectFieldJsonValueTestCase {
    NULL(Value.newBuilder().build(), NullValue.NULL_VALUE),
    BOOL(Value.newBuilder().setBoolValue(true).build(), true),
    DOUBLE(Value.newBuilder().setNumberValue(4.5d).build(), 4.5d),
    STRING(Value.newBuilder().setStringValue("test").build(), "test"),
    STRUCT(
        Value.newBuilder()
            .setStructValue(
                Struct.newBuilder()
                    .putFields("a", Value.newBuilder().setBoolValue(false).build())
                    .build())
            .build(),
        ImmutableMap.of("a", false)),
    LIST(
        Value.newBuilder()
            .setListValue(
                ListValue.newBuilder()
                    .addValues(Value.newBuilder().setStringValue("test").build())
                    .build())
            .build(),
        ImmutableList.of("test"));

    private final Value jsonValue;
    private final Object value;

    SelectFieldJsonValueTestCase(Value jsonValue, Object value) {
      this.jsonValue = jsonValue;
      this.value = value;
    }
  }

  @Test
  public void selectField_jsonValue(@TestParameter SelectFieldJsonValueTestCase testCase) {
    TestAllTypes testAllTypes =
        TestAllTypes.newBuilder().setSingleValue(testCase.jsonValue).build();

    ProtoMessageValue protoMessageValue =
        ProtoMessageValue.create(
            testAllTypes, DefaultDescriptorPool.INSTANCE, PROTO_CEL_VALUE_CONVERTER, false);

    assertThat(protoMessageValue.select("single_value")).isEqualTo(testCase.value);
  }

  @Test
  public void selectField_jsonStruct() {
    TestAllTypes testAllTypes =
        TestAllTypes.newBuilder()
            .setSingleStruct(
                Struct.newBuilder()
                    .putFields("a", Value.newBuilder().setBoolValue(false).build())
                    .build())
            .build();

    ProtoMessageValue protoMessageValue =
        ProtoMessageValue.create(
            testAllTypes, DefaultDescriptorPool.INSTANCE, PROTO_CEL_VALUE_CONVERTER, false);

    assertThat(protoMessageValue.select("single_struct")).isEqualTo(ImmutableMap.of("a", false));
  }

  @Test
  public void selectField_jsonList() {
    TestAllTypes testAllTypes =
        TestAllTypes.newBuilder()
            .setListValue(
                ListValue.newBuilder()
                    .addValues(Value.newBuilder().setBoolValue(false).build())
                    .build())
            .build();

    ProtoMessageValue protoMessageValue =
        ProtoMessageValue.create(
            testAllTypes, DefaultDescriptorPool.INSTANCE, PROTO_CEL_VALUE_CONVERTER, false);

    assertThat(protoMessageValue.select("list_value")).isEqualTo(ImmutableList.of(false));
  }

  @Test
  public void selectField_wrapperFieldUnset_returnsNull() {
    ProtoMessageValue protoMessageValue =
        ProtoMessageValue.create(
            TestAllTypes.getDefaultInstance(),
            DefaultDescriptorPool.INSTANCE,
            PROTO_CEL_VALUE_CONVERTER,
            /* enableJsonFieldNames= */ false);

    assertThat(protoMessageValue.select("single_int64_wrapper")).isEqualTo(NullValue.NULL_VALUE);
  }

  @Test
  public void celTypeTest() {
    ProtoMessageValue protoMessageValue =
        ProtoMessageValue.create(
            TestAllTypes.getDefaultInstance(),
            DefaultDescriptorPool.INSTANCE,
            PROTO_CEL_VALUE_CONVERTER,
            /* enableJsonFieldNames= */ false);

    assertThat(protoMessageValue.celType())
        .isEqualTo(StructTypeReference.create(TestAllTypes.getDescriptor().getFullName()));
  }

  @Test
  public void findField_jsonName_success() {
    TestAllTypes testAllTypes = TestAllTypes.newBuilder().setSingleInt32(42).build();

    ProtoMessageValue protoMessageValue =
        ProtoMessageValue.create(
            testAllTypes, DefaultDescriptorPool.INSTANCE, PROTO_CEL_VALUE_CONVERTER, true);

    assertThat(protoMessageValue.find("singleInt32")).isPresent();
  }

  @Test
  public void selectByFieldNumber_success(@TestParameter SelectFieldTestCase testCase) {
    ProtoMessageValue protoMessageValue =
        ProtoMessageValue.create(
            createPopulatedTestAllTypes(),
            DefaultDescriptorPool.INSTANCE,
            PROTO_CEL_VALUE_CONVERTER,
            /* enableJsonFieldNames= */ false);
    int fieldNumber = TestAllTypes.getDescriptor().findFieldByName(testCase.fieldName).getNumber();
    SelectField selectField = SelectField.create(fieldNumber, "renamed_" + testCase.fieldName);

    Object result = protoMessageValue.selectByFieldNumber(selectField);

    assertThat(result).isEqualTo(testCase.value);
  }

  @SuppressWarnings("ImmutableEnumChecker") // Test only
  private enum UnsetFieldDefaultTestCase {
    PROTO2_CUSTOM_INT32(TestAllTypes.SINGLE_INT32_FIELD_NUMBER, "single_int32", -32L),
    PROTO2_CUSTOM_STRING(TestAllTypes.SINGLE_STRING_FIELD_NUMBER, "single_string", "empty"),
    WRAPPER_UNSET(
        TestAllTypes.SINGLE_INT64_WRAPPER_FIELD_NUMBER,
        "single_int64_wrapper",
        NullValue.NULL_VALUE),
    REPEATED_EMPTY(TestAllTypes.REPEATED_INT64_FIELD_NUMBER, "repeated_int64", ImmutableList.of()),
    MAP_EMPTY(TestAllTypes.MAP_STRING_STRING_FIELD_NUMBER, "map_string_string", ImmutableMap.of());

    private final int fieldNumber;
    private final String fieldName;
    private final Object expectedDefault;

    UnsetFieldDefaultTestCase(int fieldNumber, String fieldName, Object expectedDefault) {
      this.fieldNumber = fieldNumber;
      this.fieldName = fieldName;
      this.expectedDefault = expectedDefault;
    }
  }

  @Test
  public void selectByFieldNumber_unsetField_returnsDefault(
      @TestParameter UnsetFieldDefaultTestCase testCase) {
    ProtoMessageValue protoMessageValue =
        ProtoMessageValue.create(
            TestAllTypes.getDefaultInstance(),
            DefaultDescriptorPool.INSTANCE,
            PROTO_CEL_VALUE_CONVERTER,
            /* enableJsonFieldNames= */ false);
    SelectField selectField =
        SelectField.create(testCase.fieldNumber, "renamed_" + testCase.fieldName);

    Object result = protoMessageValue.selectByFieldNumber(selectField);

    assertThat(result).isEqualTo(testCase.expectedDefault);
  }

  @Test
  public void selectByFieldNumber_undeclaredField_throwsException() {
    ProtoMessageValue protoMessageValue =
        ProtoMessageValue.create(
            TestAllTypes.getDefaultInstance(),
            DefaultDescriptorPool.INSTANCE,
            PROTO_CEL_VALUE_CONVERTER,
            /* enableJsonFieldNames= */ false);
    SelectField undeclaredField = SelectField.create(99999L, "bogus");

    IllegalArgumentException exception =
        assertThrows(
            IllegalArgumentException.class,
            () -> protoMessageValue.selectByFieldNumber(undeclaredField));

    assertThat(exception)
        .hasMessageThat()
        .isEqualTo(
            "field 'bogus' (number 99999) is not declared in message"
                + " 'cel.expr.conformance.proto2.TestAllTypes'");
  }

  @Test
  public void hasFieldByNumber_fieldIsSet_returnsTrue(@TestParameter SelectFieldTestCase testCase) {
    ProtoMessageValue protoMessageValue =
        ProtoMessageValue.create(
            createPopulatedTestAllTypes(),
            DefaultDescriptorPool.INSTANCE,
            PROTO_CEL_VALUE_CONVERTER,
            /* enableJsonFieldNames= */ false);
    int fieldNumber = TestAllTypes.getDescriptor().findFieldByName(testCase.fieldName).getNumber();
    SelectField selectField = SelectField.create(fieldNumber, "renamed_" + testCase.fieldName);

    boolean result = protoMessageValue.hasFieldByNumber(selectField);

    assertThat(result).isTrue();
  }

  @Test
  public void hasFieldByNumber_fieldIsUnset_returnsFalse(
      @TestParameter SelectFieldTestCase testCase) {
    ProtoMessageValue protoMessageValue =
        ProtoMessageValue.create(
            TestAllTypes.getDefaultInstance(),
            DefaultDescriptorPool.INSTANCE,
            PROTO_CEL_VALUE_CONVERTER,
            /* enableJsonFieldNames= */ false);
    int fieldNumber = TestAllTypes.getDescriptor().findFieldByName(testCase.fieldName).getNumber();
    SelectField selectField = SelectField.create(fieldNumber, "renamed_" + testCase.fieldName);

    boolean result = protoMessageValue.hasFieldByNumber(selectField);

    assertThat(result).isFalse();
  }

  @Test
  public void hasFieldByNumber_undeclaredField_throwsException() {
    ProtoMessageValue protoMessageValue =
        ProtoMessageValue.create(
            TestAllTypes.getDefaultInstance(),
            DefaultDescriptorPool.INSTANCE,
            PROTO_CEL_VALUE_CONVERTER,
            /* enableJsonFieldNames= */ false);
    SelectField undeclaredField = SelectField.create(99999L, "bogus");

    IllegalArgumentException exception =
        assertThrows(
            IllegalArgumentException.class,
            () -> protoMessageValue.hasFieldByNumber(undeclaredField));

    assertThat(exception)
        .hasMessageThat()
        .isEqualTo(
            "field 'bogus' (number 99999) is not declared in message"
                + " 'cel.expr.conformance.proto2.TestAllTypes'");
  }

  @Test
  public void findByFieldNumber_fieldIsSet_returnsValue(
      @TestParameter SelectFieldTestCase testCase) {
    ProtoMessageValue protoMessageValue =
        ProtoMessageValue.create(
            createPopulatedTestAllTypes(),
            DefaultDescriptorPool.INSTANCE,
            PROTO_CEL_VALUE_CONVERTER,
            /* enableJsonFieldNames= */ false);
    int fieldNumber = TestAllTypes.getDescriptor().findFieldByName(testCase.fieldName).getNumber();
    SelectField selectField = SelectField.create(fieldNumber, "renamed_" + testCase.fieldName);

    Optional<Object> result = protoMessageValue.findByFieldNumber(selectField);

    assertThat(result).hasValue(testCase.value);
  }

  @Test
  public void findByFieldNumber_fieldIsUnset_returnsEmpty(
      @TestParameter SelectFieldTestCase testCase) {
    ProtoMessageValue protoMessageValue =
        ProtoMessageValue.create(
            TestAllTypes.getDefaultInstance(),
            DefaultDescriptorPool.INSTANCE,
            PROTO_CEL_VALUE_CONVERTER,
            /* enableJsonFieldNames= */ false);
    int fieldNumber = TestAllTypes.getDescriptor().findFieldByName(testCase.fieldName).getNumber();
    SelectField selectField = SelectField.create(fieldNumber, "renamed_" + testCase.fieldName);

    Optional<Object> result = protoMessageValue.findByFieldNumber(selectField);

    assertThat(result).isEmpty();
  }

  @Test
  public void findByFieldNumber_undeclaredField_throwsException() {
    ProtoMessageValue protoMessageValue =
        ProtoMessageValue.create(
            TestAllTypes.getDefaultInstance(),
            DefaultDescriptorPool.INSTANCE,
            PROTO_CEL_VALUE_CONVERTER,
            /* enableJsonFieldNames= */ false);
    SelectField undeclaredField = SelectField.create(99999L, "bogus");

    IllegalArgumentException exception =
        assertThrows(
            IllegalArgumentException.class,
            () -> protoMessageValue.findByFieldNumber(undeclaredField));

    assertThat(exception)
        .hasMessageThat()
        .isEqualTo(
            "field 'bogus' (number 99999) is not declared in message"
                + " 'cel.expr.conformance.proto2.TestAllTypes'");
  }

  private static TestAllTypes createPopulatedTestAllTypes() {
    return TestAllTypes.newBuilder()
        .setSingleBool(true)
        .setSingleInt32(4)
        .setSingleInt64(5L)
        .setSingleUint32(1)
        .setSingleUint64(UnsignedLong.MAX_VALUE.longValue())
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
        .putMapStringString("a", "b")
        .setStandaloneMessage(NestedMessage.getDefaultInstance())
        .setStandaloneEnum(NestedEnum.BAR)
        .build();
  }
}
