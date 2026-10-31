// Copyright 2022 Google LLC
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

package dev.cel.runtime;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.extensions.proto.ProtoTruth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.common.primitives.UnsignedLong;
import com.google.protobuf.Any;
import com.google.protobuf.BoolValue;
import com.google.protobuf.ByteString;
import com.google.protobuf.BytesValue;
import com.google.protobuf.DoubleValue;
import com.google.protobuf.Duration;
import com.google.protobuf.FloatValue;
import com.google.protobuf.Int32Value;
import com.google.protobuf.Int64Value;
import com.google.protobuf.ListValue;
import com.google.protobuf.NullValue;
import com.google.protobuf.StringValue;
import com.google.protobuf.Struct;
import com.google.protobuf.UInt32Value;
import com.google.protobuf.UInt64Value;
import com.google.protobuf.Value;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import com.google.testing.junit.testparameterinjector.TestParameters;
import dev.cel.common.CelOptions;
import dev.cel.common.exceptions.CelNumericOverflowException;
import dev.cel.common.exceptions.CelRuntimeException;
import dev.cel.common.internal.DefaultMessageFactory;
import dev.cel.common.internal.DynamicProto;
import dev.cel.common.values.CelByteString;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(TestParameterInjector.class)
public final class ProtoMessageRuntimeHelpersTest {
  private static final DynamicProto DYNAMIC_PROTO =
      DynamicProto.create(DefaultMessageFactory.INSTANCE);
  private static final RuntimeHelpers RUNTIME_HELPER =
      ProtoMessageRuntimeHelpers.create(DYNAMIC_PROTO, CelOptions.DEFAULT);

  @Test
  @TestParameters("{text: '15.11s', expectedSeconds: 15, expectedNanos: 110000000}")
  @TestParameters(
      "{text: '315576000000s999999999ns',"
          + " expectedSeconds: 315576000000, expectedNanos: 999999999}")
  @TestParameters(
      "{text: '-315576000000s999999999ns',"
          + " expectedSeconds: -315576000000, expectedNanos: -999999999}")
  // Testing protobuf Duration seconds and nanos fields.
  @SuppressWarnings("GoodTime-ApiWithNumericTimeUnit")
  public void createDurationFromString_valid(String text, long expectedSeconds, int expectedNanos) {
    assertThat(ProtoMessageRuntimeHelpers.createDurationFromString(text))
        .isEqualTo(
            Duration.newBuilder().setSeconds(expectedSeconds).setNanos(expectedNanos).build());
  }

  @Test
  @TestParameters("{text: '-315576000000s1000000000ns'}")
  @TestParameters("{text: '315576000000s1000000000ns'}")
  @TestParameters("{text: 'bad'}")
  public void createDurationFromString_invalidOrOutOfRangeThrows(String text) {
    assertThrows(
        IllegalArgumentException.class,
        () -> ProtoMessageRuntimeHelpers.createDurationFromString(text));
  }

  @Test
  public void int64Add() throws Exception {
    assertThat(ProtoMessageRuntimeHelpers.int64Add(1, 1, CelOptions.LEGACY)).isEqualTo(2);
    assertThat(ProtoMessageRuntimeHelpers.int64Add(2, 2, CelOptions.DEFAULT)).isEqualTo(4);
    assertThat(ProtoMessageRuntimeHelpers.int64Add(1, Long.MAX_VALUE, CelOptions.LEGACY))
        .isEqualTo(Long.MIN_VALUE);
    assertThat(ProtoMessageRuntimeHelpers.int64Add(-1, Long.MIN_VALUE, CelOptions.LEGACY))
        .isEqualTo(Long.MAX_VALUE);
    assertThrows(
        ArithmeticException.class,
        () -> ProtoMessageRuntimeHelpers.int64Add(1, Long.MAX_VALUE, CelOptions.DEFAULT));
    assertThrows(
        ArithmeticException.class,
        () -> ProtoMessageRuntimeHelpers.int64Add(-1, Long.MIN_VALUE, CelOptions.DEFAULT));
  }

  @Test
  public void int64Divide() throws Exception {
    assertThat(ProtoMessageRuntimeHelpers.int64Divide(-44, 11, CelOptions.LEGACY)).isEqualTo(-4);
    assertThat(ProtoMessageRuntimeHelpers.int64Divide(-44, 11, CelOptions.DEFAULT)).isEqualTo(-4);
    assertThat(ProtoMessageRuntimeHelpers.int64Divide(Long.MIN_VALUE, -1, CelOptions.LEGACY))
        .isEqualTo(Long.MIN_VALUE);
    assertThrows(
        CelNumericOverflowException.class,
        () -> ProtoMessageRuntimeHelpers.int64Divide(Long.MIN_VALUE, -1, CelOptions.DEFAULT));
  }

  @Test
  public void int64Multiply() throws Exception {
    assertThat(ProtoMessageRuntimeHelpers.int64Multiply(2, 3, CelOptions.LEGACY)).isEqualTo(6);
    assertThat(ProtoMessageRuntimeHelpers.int64Multiply(2, 3, CelOptions.DEFAULT)).isEqualTo(6);
    assertThat(ProtoMessageRuntimeHelpers.int64Multiply(Long.MIN_VALUE, -1, CelOptions.LEGACY))
        .isEqualTo(Long.MIN_VALUE);
    assertThrows(
        ArithmeticException.class,
        () -> ProtoMessageRuntimeHelpers.int64Multiply(Long.MIN_VALUE, -1, CelOptions.DEFAULT));
  }

  @Test
  public void int64Negate() throws Exception {
    assertThat(ProtoMessageRuntimeHelpers.int64Negate(7, CelOptions.LEGACY)).isEqualTo(-7);
    assertThat(ProtoMessageRuntimeHelpers.int64Negate(7, CelOptions.DEFAULT)).isEqualTo(-7);
    assertThat(ProtoMessageRuntimeHelpers.int64Negate(Long.MIN_VALUE, CelOptions.LEGACY))
        .isEqualTo(Long.MIN_VALUE);
    assertThrows(
        ArithmeticException.class,
        () -> ProtoMessageRuntimeHelpers.int64Negate(Long.MIN_VALUE, CelOptions.DEFAULT));
  }

  @Test
  public void int64Subtract() throws Exception {
    assertThat(ProtoMessageRuntimeHelpers.int64Subtract(50, 100, CelOptions.LEGACY)).isEqualTo(-50);
    assertThat(ProtoMessageRuntimeHelpers.int64Subtract(50, 100, CelOptions.DEFAULT))
        .isEqualTo(-50);
    assertThat(ProtoMessageRuntimeHelpers.int64Subtract(Long.MIN_VALUE, 1, CelOptions.LEGACY))
        .isEqualTo(Long.MAX_VALUE);
    assertThat(ProtoMessageRuntimeHelpers.int64Subtract(Long.MAX_VALUE, -1, CelOptions.LEGACY))
        .isEqualTo(Long.MIN_VALUE);
    assertThrows(
        ArithmeticException.class,
        () -> ProtoMessageRuntimeHelpers.int64Subtract(Long.MIN_VALUE, 1, CelOptions.DEFAULT));
    assertThrows(
        ArithmeticException.class,
        () -> ProtoMessageRuntimeHelpers.int64Subtract(Long.MAX_VALUE, -1, CelOptions.DEFAULT));
  }

  @Test
  public void uint64CompareTo_unsignedLongs() {
    assertThat(ProtoMessageRuntimeHelpers.uint64CompareTo(UnsignedLong.ONE, UnsignedLong.ZERO))
        .isEqualTo(1);
    assertThat(ProtoMessageRuntimeHelpers.uint64CompareTo(UnsignedLong.ZERO, UnsignedLong.ONE))
        .isEqualTo(-1);
    assertThat(ProtoMessageRuntimeHelpers.uint64CompareTo(UnsignedLong.ONE, UnsignedLong.ONE))
        .isEqualTo(0);
    assertThat(
            ProtoMessageRuntimeHelpers.uint64CompareTo(
                UnsignedLong.valueOf(Long.MAX_VALUE), UnsignedLong.MAX_VALUE))
        .isEqualTo(-1);
  }

  @Test
  public void uint64CompareTo_throwsWhenNegativeOrGreaterThanSignedLongMax() throws Exception {
    assertThrows(
        IllegalArgumentException.class, () -> ProtoMessageRuntimeHelpers.uint64CompareTo(-1, 0));
    assertThrows(
        IllegalArgumentException.class, () -> ProtoMessageRuntimeHelpers.uint64CompareTo(0, -1));
  }

  @Test
  public void uint64CompareTo_unsignedComparisonAndArithmeticIsUnsigned() throws Exception {
    // In twos complement, -1 is represented by all bits being set. This is equivalent to the
    // maximum value when unsigned.
    assertThat(ProtoMessageRuntimeHelpers.uint64CompareTo(-1, 0, CelOptions.DEFAULT))
        .isGreaterThan(0);
    assertThat(ProtoMessageRuntimeHelpers.uint64CompareTo(0, -1, CelOptions.DEFAULT)).isLessThan(0);
  }

  @Test
  public void uint64Add_signedLongs() throws Exception {
    assertThat(ProtoMessageRuntimeHelpers.uint64Add(4, 4, CelOptions.LEGACY)).isEqualTo(8);
    assertThat(ProtoMessageRuntimeHelpers.uint64Add(4, 4, CelOptions.DEFAULT)).isEqualTo(8);
    assertThat(ProtoMessageRuntimeHelpers.uint64Add(-1, 1, CelOptions.LEGACY)).isEqualTo(0);
    assertThrows(
        CelNumericOverflowException.class,
        () -> ProtoMessageRuntimeHelpers.uint64Add(-1, 1, CelOptions.DEFAULT));
  }

  @Test
  public void uint64Add_unsignedLongs() throws Exception {
    assertThat(
            ProtoMessageRuntimeHelpers.uint64Add(UnsignedLong.valueOf(4), UnsignedLong.valueOf(4)))
        .isEqualTo(UnsignedLong.valueOf(8));
    assertThat(
            ProtoMessageRuntimeHelpers.uint64Add(
                UnsignedLong.MAX_VALUE.minus(UnsignedLong.ONE), UnsignedLong.ONE))
        .isEqualTo(UnsignedLong.MAX_VALUE);
    assertThrows(
        CelNumericOverflowException.class,
        () -> ProtoMessageRuntimeHelpers.uint64Add(UnsignedLong.MAX_VALUE, UnsignedLong.ONE));
  }

  @Test
  public void uint64Multiply_signedLongs() throws Exception {
    assertThat(ProtoMessageRuntimeHelpers.uint64Multiply(32, 2, CelOptions.LEGACY)).isEqualTo(64);
    assertThat(ProtoMessageRuntimeHelpers.uint64Multiply(32, 2, CelOptions.DEFAULT)).isEqualTo(64);
    assertThat(
            ProtoMessageRuntimeHelpers.uint64Multiply(
                Long.MIN_VALUE,
                2,
                CelOptions.newBuilder()
                    .enableUnsignedComparisonAndArithmeticIsUnsigned(true)
                    .build()))
        .isEqualTo(0);
    assertThrows(
        CelNumericOverflowException.class,
        () -> ProtoMessageRuntimeHelpers.uint64Multiply(Long.MIN_VALUE, 2, CelOptions.DEFAULT));
  }

  @Test
  public void uint64Multiply_unsignedLongs() throws Exception {
    assertThat(
            ProtoMessageRuntimeHelpers.uint64Multiply(
                UnsignedLong.valueOf(32), UnsignedLong.valueOf(2)))
        .isEqualTo(UnsignedLong.valueOf(64));
    assertThrows(
        CelNumericOverflowException.class,
        () ->
            ProtoMessageRuntimeHelpers.uint64Multiply(
                UnsignedLong.MAX_VALUE, UnsignedLong.valueOf(2)));
  }

  @Test
  public void uint64Multiply_throwsWhenNegativeOrGreaterThanSignedLongMax() throws Exception {
    assertThrows(
        IllegalArgumentException.class, () -> ProtoMessageRuntimeHelpers.uint64Multiply(-1, 0));
    assertThrows(
        IllegalArgumentException.class, () -> ProtoMessageRuntimeHelpers.uint64Multiply(0, -1));
  }

  @Test
  public void uint64Multiply_unsignedComparisonAndArithmeticIsUnsigned() throws Exception {
    // In twos complement, -1 is represented by all bits being set. This is equivalent to the
    // maximum value when unsigned.
    assertThat(ProtoMessageRuntimeHelpers.uint64Multiply(-1, 0, CelOptions.DEFAULT)).isEqualTo(0);
    assertThat(ProtoMessageRuntimeHelpers.uint64Multiply(0, -1, CelOptions.DEFAULT)).isEqualTo(0);
  }

  @Test
  public void uint64Divide_unsignedLongs() {
    assertThat(ProtoMessageRuntimeHelpers.uint64Divide(UnsignedLong.ZERO, UnsignedLong.ONE))
        .isEqualTo(UnsignedLong.ZERO);
    assertThat(
            ProtoMessageRuntimeHelpers.uint64Divide(UnsignedLong.MAX_VALUE, UnsignedLong.MAX_VALUE))
        .isEqualTo(UnsignedLong.ONE);
    assertThrows(
        CelRuntimeException.class,
        () -> ProtoMessageRuntimeHelpers.uint64Divide(UnsignedLong.MAX_VALUE, UnsignedLong.ZERO));
  }

  @Test
  public void uint64Divide_throwsWhenNegativeOrGreaterThanSignedLongMax() throws Exception {
    assertThrows(
        IllegalArgumentException.class, () -> ProtoMessageRuntimeHelpers.uint64Divide(0, -1));
    assertThrows(
        IllegalArgumentException.class, () -> ProtoMessageRuntimeHelpers.uint64Divide(-1, -1));
  }

  @Test
  public void uint64Divide_unsignedComparisonAndArithmeticIsUnsigned() throws Exception {
    // In twos complement, -1 is represented by all bits being set. This is equivalent to the
    // maximum value when unsigned.
    assertThat(ProtoMessageRuntimeHelpers.uint64Divide(0, -1, CelOptions.DEFAULT)).isEqualTo(0);
    assertThat(ProtoMessageRuntimeHelpers.uint64Divide(-1, -1, CelOptions.DEFAULT)).isEqualTo(1);
  }

  @Test
  public void uint64Mod_unsignedLongs() throws Exception {
    assertThat(ProtoMessageRuntimeHelpers.uint64Mod(UnsignedLong.ONE, UnsignedLong.valueOf(2)))
        .isEqualTo(UnsignedLong.ONE);
    assertThat(ProtoMessageRuntimeHelpers.uint64Mod(UnsignedLong.ONE, UnsignedLong.ONE))
        .isEqualTo(UnsignedLong.ZERO);
    assertThrows(
        CelRuntimeException.class,
        () -> ProtoMessageRuntimeHelpers.uint64Mod(UnsignedLong.ONE, UnsignedLong.ZERO));
  }

  @Test
  public void uint64Mod_throwsWhenNegativeOrGreaterThanSignedLongMax() throws Exception {
    assertThrows(IllegalArgumentException.class, () -> ProtoMessageRuntimeHelpers.uint64Mod(0, -1));
    assertThrows(
        IllegalArgumentException.class, () -> ProtoMessageRuntimeHelpers.uint64Mod(-1, -1));
  }

  @Test
  public void uint64Mod_unsignedComparisonAndArithmeticIsUnsigned() throws Exception {
    // In twos complement, -1 is represented by all bits being set. This is equivalent to the
    // maximum value when unsigned.
    assertThat(ProtoMessageRuntimeHelpers.uint64Mod(0, -1, CelOptions.DEFAULT)).isEqualTo(0);
    assertThat(ProtoMessageRuntimeHelpers.uint64Mod(-1, -1, CelOptions.DEFAULT)).isEqualTo(0);
  }

  @Test
  public void uint64Subtract_signedLongs() throws Exception {
    assertThat(ProtoMessageRuntimeHelpers.uint64Subtract(-1, 2, CelOptions.LEGACY)).isEqualTo(-3);
    assertThat(ProtoMessageRuntimeHelpers.uint64Subtract(-1, 2, CelOptions.DEFAULT)).isEqualTo(-3);
    assertThat(ProtoMessageRuntimeHelpers.uint64Subtract(0, 1, CelOptions.LEGACY)).isEqualTo(-1);
    assertThrows(
        CelNumericOverflowException.class,
        () -> ProtoMessageRuntimeHelpers.uint64Subtract(0, 1, CelOptions.DEFAULT));
    assertThrows(
        CelNumericOverflowException.class,
        () -> ProtoMessageRuntimeHelpers.uint64Subtract(-3, -1, CelOptions.DEFAULT));
    assertThrows(
        CelNumericOverflowException.class,
        () -> ProtoMessageRuntimeHelpers.uint64Subtract(55, -40, CelOptions.DEFAULT));
  }

  @Test
  public void uint64Subtract_unsignedLongs() throws Exception {
    assertThat(ProtoMessageRuntimeHelpers.uint64Subtract(UnsignedLong.ONE, UnsignedLong.ONE))
        .isEqualTo(UnsignedLong.ZERO);
    assertThat(
            ProtoMessageRuntimeHelpers.uint64Subtract(
                UnsignedLong.valueOf(3), UnsignedLong.valueOf(2)))
        .isEqualTo(UnsignedLong.ONE);
    assertThrows(
        CelNumericOverflowException.class,
        () -> ProtoMessageRuntimeHelpers.uint64Subtract(UnsignedLong.ONE, UnsignedLong.valueOf(2)));
  }

  @Test
  public void maybeAdaptPrimitive_optionalValues() {
    // Optional values containing int/float are adapted into long/double
    assertThat(RuntimeHelpers.maybeAdaptPrimitive(Optional.of(5))).isEqualTo(Optional.of(5L));
    assertThat(RuntimeHelpers.maybeAdaptPrimitive(Optional.of(5.5f))).isEqualTo(Optional.of(5.5d));
    // Optional values for the rest of the primitive types are not adapted
    assertThat(RuntimeHelpers.maybeAdaptPrimitive(Optional.of(5L))).isEqualTo(Optional.of(5L));
    assertThat(RuntimeHelpers.maybeAdaptPrimitive(Optional.of(5.5d))).isEqualTo(Optional.of(5.5d));
    assertThat(RuntimeHelpers.maybeAdaptPrimitive(Optional.of(true))).isEqualTo(Optional.of(true));
    assertThat(RuntimeHelpers.maybeAdaptPrimitive(Optional.of(false)))
        .isEqualTo(Optional.of(false));
    assertThat(RuntimeHelpers.maybeAdaptPrimitive(Optional.of("test")))
        .isEqualTo(Optional.of("test"));
    assertThat(RuntimeHelpers.maybeAdaptPrimitive(Optional.of(UnsignedLong.valueOf(5))))
        .isEqualTo(Optional.of(UnsignedLong.valueOf(5)));
    assertThat(RuntimeHelpers.maybeAdaptPrimitive(BytesValue.of(ByteString.copyFromUtf8("test"))))
        .isEqualTo(BytesValue.of(ByteString.copyFromUtf8("test")));
  }

  @Test
  public void adaptProtoToValue_wrapperValues() throws Exception {
    assertThat(RUNTIME_HELPER.adaptProtoToValue(BoolValue.of(true))).isEqualTo(true);
    assertThat(RUNTIME_HELPER.adaptProtoToValue(BytesValue.of(ByteString.EMPTY)))
        .isEqualTo(CelByteString.EMPTY);
    assertThat(RUNTIME_HELPER.adaptProtoToValue(DoubleValue.of(1.5d))).isEqualTo(1.5d);
    assertThat(RUNTIME_HELPER.adaptProtoToValue(FloatValue.of(1.5f))).isEqualTo(1.5d);
    assertThat(RUNTIME_HELPER.adaptProtoToValue(Int32Value.of(12))).isEqualTo(12L);
    assertThat(RUNTIME_HELPER.adaptProtoToValue(Int64Value.of(-12L))).isEqualTo(-12L);
    assertThat(RUNTIME_HELPER.adaptProtoToValue(UInt32Value.of(123)))
        .isEqualTo(UnsignedLong.valueOf(123L));
    assertThat(RUNTIME_HELPER.adaptProtoToValue(UInt64Value.of(1234L)))
        .isEqualTo(UnsignedLong.valueOf(1234L));
    assertThat(RUNTIME_HELPER.adaptProtoToValue(StringValue.of("hello"))).isEqualTo("hello");

    assertThat(RUNTIME_HELPER.adaptProtoToValue(UInt32Value.of(123)))
        .isEqualTo(UnsignedLong.valueOf(123L));
    assertThat(RUNTIME_HELPER.adaptProtoToValue(UInt64Value.of(1234L)))
        .isEqualTo(UnsignedLong.valueOf(1234L));
  }

  @Test
  public void adaptProtoToValue_jsonValues() throws Exception {
    assertThat(RUNTIME_HELPER.adaptProtoToValue(Value.newBuilder().setStringValue("json").build()))
        .isEqualTo("json");

    assertThat(
            RUNTIME_HELPER.adaptProtoToValue(
                Value.newBuilder()
                    .setListValue(
                        ListValue.newBuilder()
                            .addValues(Value.newBuilder().setNumberValue(1.2d).build()))
                    .build()))
        .isEqualTo(ImmutableList.of(1.2d));

    Map<String, Object> mp = new HashMap<>();
    mp.put("list_value", ImmutableList.of(false, dev.cel.common.values.NullValue.NULL_VALUE));
    assertThat(
            RUNTIME_HELPER.adaptProtoToValue(
                Struct.newBuilder()
                    .putFields(
                        "list_value",
                        Value.newBuilder()
                            .setListValue(
                                ListValue.newBuilder()
                                    .addValues(Value.newBuilder().setBoolValue(false))
                                    .addValues(
                                        Value.newBuilder().setNullValue(NullValue.NULL_VALUE)))
                            .build())
                    .build()))
        .isEqualTo(mp);
  }

  @Test
  public void adaptProtoToValue_anyValues() throws Exception {
    Map<String, Object> mp = new HashMap<>();
    Struct jsonValue =
        Struct.newBuilder()
            .putFields(
                "list_value",
                Value.newBuilder()
                    .setListValue(
                        ListValue.newBuilder()
                            .addValues(Value.newBuilder().setBoolValue(false))
                            .addValues(Value.newBuilder().setNullValue(NullValue.NULL_VALUE)))
                    .build())
            .build();
    Any anyJsonValue = Any.pack(jsonValue);
    mp.put("list_value", ImmutableList.of(false, dev.cel.common.values.NullValue.NULL_VALUE));
    assertThat(RUNTIME_HELPER.adaptProtoToValue(anyJsonValue)).isEqualTo(mp);
  }

  @Test
  public void adaptProtoToValue_builderValue() throws Exception {
    assertThat(RUNTIME_HELPER.adaptProtoToValue(BoolValue.newBuilder().setValue(true)))
        .isEqualTo(true);
  }

  @Test
  public void indexList() throws Exception {
    ImmutableList<String> list = ImmutableList.of("value", "value2");
    assertThat(RuntimeHelpers.indexList(list, 0.0)).isEqualTo("value");
    assertThat(RuntimeHelpers.indexList(list, UnsignedLong.valueOf(1L))).isEqualTo("value2");
    Assert.assertThrows(CelRuntimeException.class, () -> RuntimeHelpers.indexList(list, 1.1));
  }
}
