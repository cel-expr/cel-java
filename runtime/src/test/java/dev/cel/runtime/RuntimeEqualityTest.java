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

package dev.cel.runtime;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.primitives.UnsignedLong;
import com.google.protobuf.MessageLite;
import com.google.protobuf.MessageLiteOrBuilder;
import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import dev.cel.common.CelOptions;
import dev.cel.common.internal.DefaultDescriptorPool;
import dev.cel.common.internal.DefaultMessageFactory;
import dev.cel.common.internal.DynamicProto;
import dev.cel.common.values.ProtoMessageLiteValueProvider;
import dev.cel.expr.conformance.proto2.TestAllTypes;
import java.util.Collections;
import java.util.LinkedList;
import java.util.Map;
import java.util.Optional;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(TestParameterInjector.class)
public final class RuntimeEqualityTest {

  private static final RuntimeEquality RUNTIME_EQUALITY =
      RuntimeEquality.create(RuntimeHelpers.create(), CelOptions.DEFAULT);

  @SuppressWarnings("ImmutableEnumChecker") // Test only
  private enum EqualPairTestCase {
    INTS(1, 1),
    INT_AND_LONG(2, 2L),
    INT_AND_DOUBLE(3, 3.0),
    INT_AND_ULONG(4, UnsignedLong.valueOf(4)),
    POSITIVE_AND_NEGATIVE_ZERO(0.0d, -0.0d),
    NEGATIVE_ZERO_AND_LONG_ZERO(-0.0d, 0L),
    NEGATIVE_ZERO_AND_ULONG_ZERO(-0.0d, UnsignedLong.ZERO),
    HETEROGENEOUS_LISTS(
        ImmutableList.of(1, 2, 3), ImmutableList.of(1.0, 2L, UnsignedLong.valueOf(3))),
    HETEROGENEOUS_LINKED_LISTS(
        new LinkedList<>(ImmutableList.of(1, 2, 3)),
        new LinkedList<>(ImmutableList.of(1.0, 2L, UnsignedLong.valueOf(3)))),
    HETEROGENEOUS_MAPS(
        ImmutableMap.of("a", 1, "b", 2), ImmutableMap.of("a", 1L, "b", UnsignedLong.valueOf(2))),
    EMPTY_OPTIONALS(Optional.empty(), Optional.empty()),
    PRESENT_HETEROGENEOUS_OPTIONALS(Optional.of(1), Optional.of(1.0)),
    SHORTS((short) 200, (short) 200);

    private final Object x;
    private final Object y;

    EqualPairTestCase(Object x, Object y) {
      this.x = x;
      this.y = y;
    }
  }

  @SuppressWarnings("ImmutableEnumChecker") // Test only
  private enum IdenticalNanReferenceTestCase {
    DOUBLE(Double.NaN),
    FLOAT(Float.NaN),
    OPTIONAL(Optional.of(Double.NaN)),
    LIST(ImmutableList.of(Double.NaN)),
    LINKED_LIST(new LinkedList<>(ImmutableList.of(Double.NaN))),
    MAP_VALUE(ImmutableMap.of("k", Double.NaN)),
    MAP_DOUBLE_NAN_KEY(ImmutableMap.of(Double.NaN, "v")),
    MAP_FLOAT_NAN_KEY(ImmutableMap.of(Float.NaN, "v"));

    private final Object value;

    IdenticalNanReferenceTestCase(Object value) {
      this.value = value;
    }
  }

  @SuppressWarnings("ImmutableEnumChecker") // Test only
  private enum UnequalContainerPairTestCase {
    LIST_ELEMENTS(ImmutableList.of(1L), ImmutableList.of(2L)),
    LIST_SIZES(ImmutableList.of(1L), ImmutableList.of(1L, 2L)),
    LINKED_LIST_ELEMENTS(
        new LinkedList<>(ImmutableList.of(1L)), new LinkedList<>(ImmutableList.of(2L))),
    LINKED_LIST_SIZES(
        new LinkedList<>(ImmutableList.of(1L)), new LinkedList<>(ImmutableList.of(1L, 2L))),
    MAP_VALUES(ImmutableMap.of("a", 1L), ImmutableMap.of("a", 2L)),
    MAP_SIZES(ImmutableMap.of("a", 1L), ImmutableMap.of("a", 1L, "b", 2L));

    private final Object x;
    private final Object y;

    UnequalContainerPairTestCase(Object x, Object y) {
      this.x = x;
      this.y = y;
    }
  }

  @Test
  public void objectEquals_and_hashCode(@TestParameter EqualPairTestCase testCase) {
    assertThat(RUNTIME_EQUALITY.objectEquals(testCase.x, testCase.y)).isTrue();
    assertThat(RUNTIME_EQUALITY.objectEquals(testCase.y, testCase.x)).isTrue();
    assertThat(RUNTIME_EQUALITY.hashCode(testCase.x))
        .isEqualTo(RUNTIME_EQUALITY.hashCode(testCase.y));
  }

  @Test
  public void objectEquals_unequalContainers_returnsFalse(
      @TestParameter UnequalContainerPairTestCase testCase) {
    assertThat(RUNTIME_EQUALITY.objectEquals(testCase.x, testCase.y)).isFalse();
    assertThat(RUNTIME_EQUALITY.objectEquals(testCase.y, testCase.x)).isFalse();
  }

  @Test
  public void objectEquals_unsupportedNumberSubtypes_returnsFalse() {
    assertThat(RUNTIME_EQUALITY.objectEquals((short) 1, 1L)).isFalse();
    assertThat(RUNTIME_EQUALITY.objectEquals(1L, (short) 1)).isFalse();
  }

  @Test
  public void inList_nan_returnsFalse() {
    assertThat(RUNTIME_EQUALITY.inList(ImmutableList.of(Double.NaN), Double.NaN)).isFalse();
    assertThat(
            RUNTIME_EQUALITY.inList(
                ImmutableList.of(ImmutableList.of(Double.NaN)), ImmutableList.of(Double.NaN)))
        .isFalse();
  }

  @Test
  public void inList_nonRandomAccessList() {
    LinkedList<Object> linkedList = new LinkedList<>(ImmutableList.of(1L, "a", 0L));

    assertThat(RUNTIME_EQUALITY.inList(linkedList, -0.0d)).isTrue();
    assertThat(RUNTIME_EQUALITY.inList(linkedList, Double.NaN)).isFalse();
  }

  @Test
  public void inMap_nanKey_returnsFalse() {
    ImmutableMap<Double, String> doubleNanMap = ImmutableMap.of(Double.NaN, "v");
    ImmutableMap<Float, String> floatNanMap = ImmutableMap.of(Float.NaN, "v");

    assertThat(RUNTIME_EQUALITY.inMap(doubleNanMap, Double.NaN)).isFalse();
    assertThat(RUNTIME_EQUALITY.inMap(floatNanMap, Float.NaN)).isFalse();
    assertThat(RUNTIME_EQUALITY.objectEquals(doubleNanMap, ImmutableMap.of(Double.NaN, "v")))
        .isFalse();
  }

  @Test
  public void objectEquals_nullMapValue_returnsFalse() {
    Map<String, Object> nullValMap = Collections.singletonMap("k", null);

    assertThat(RUNTIME_EQUALITY.objectEquals(nullValMap, nullValMap)).isFalse();
  }

  @Test
  public void objectEquals_protoMessageLite_throwsSymmetrically() {
    ProtoMessageRuntimeEquality protoEquality =
        ProtoMessageRuntimeEquality.create(
            DynamicProto.create(DefaultMessageFactory.create(DefaultDescriptorPool.INSTANCE)),
            CelOptions.current().enableProtoDifferencerEquality(true).build());
    MessageLiteOrBuilder liteOnly =
        new MessageLiteOrBuilder() {
          @Override
          public MessageLite getDefaultInstanceForType() {
            return null;
          }

          @Override
          public boolean isInitialized() {
            return true;
          }
        };
    TestAllTypes fullMessage = TestAllTypes.getDefaultInstance();

    assertThrows(
        UnsupportedOperationException.class,
        () -> protoEquality.objectEquals(liteOnly, fullMessage));
    assertThrows(
        UnsupportedOperationException.class,
        () -> protoEquality.objectEquals(fullMessage, liteOnly));
    assertThrows(
        UnsupportedOperationException.class, () -> protoEquality.objectEquals(liteOnly, liteOnly));
  }

  @Test
  public void inList_negativeZero_returnsTrue() {
    assertThat(
            RUNTIME_EQUALITY.inList(
                ImmutableList.<Object>of(ImmutableList.of(0L)), ImmutableList.of(-0.0)))
        .isTrue();
  }

  @Test
  public void objectEquals_messageLite_throws() {
    TestAllTypes.Builder builder = TestAllTypes.newBuilder();
    TestAllTypes defaultInstance = TestAllTypes.getDefaultInstance();

    UnsupportedOperationException builderThrown =
        assertThrows(
            UnsupportedOperationException.class,
            () -> RUNTIME_EQUALITY.objectEquals(builder, defaultInstance));
    UnsupportedOperationException stringThrown =
        assertThrows(
            UnsupportedOperationException.class,
            () -> RUNTIME_EQUALITY.objectEquals("not_a_message", defaultInstance));

    assertThat(builderThrown).hasMessageThat().isEqualTo("Not implemented yet");
    assertThat(stringThrown).hasMessageThat().isEqualTo("Not implemented yet");
  }

  @Test
  public void objectEquals_wireMessageLite_throws() {
    Object wireMsg1 =
        ProtoMessageLiteValueProvider.newInstance()
            .protoCelValueConverter()
            .toRuntimeValue(TestAllTypes.getDefaultInstance());
    Object wireMsg2 =
        ProtoMessageLiteValueProvider.newInstance()
            .protoCelValueConverter()
            .toRuntimeValue(TestAllTypes.newBuilder().setSingleInt32(1).build());

    UnsupportedOperationException messagesThrown =
        assertThrows(
            UnsupportedOperationException.class,
            () -> RUNTIME_EQUALITY.objectEquals(wireMsg1, wireMsg2));
    UnsupportedOperationException stringThrown =
        assertThrows(
            UnsupportedOperationException.class,
            () -> RUNTIME_EQUALITY.objectEquals("not_a_message", wireMsg1));

    assertThat(messagesThrown).hasMessageThat().isEqualTo("Message equality is not supported");
    assertThat(stringThrown).hasMessageThat().isEqualTo("Message equality is not supported");
  }

  @Test
  public void objectEquals_nanWithIdenticalReference_returnsFalse(
      @TestParameter IdenticalNanReferenceTestCase testCase) {
    assertThat(RUNTIME_EQUALITY.objectEquals(testCase.value, testCase.value)).isFalse();
  }

  @Test
  public void objectEquals_ieee754ContainerEqualityDisabled_shortCircuitsIdenticalContainers() {
    RuntimeEquality optOutEquality =
        RuntimeEquality.create(
            RuntimeHelpers.create(),
            CelOptions.current().enableIeee754ContainerEquality(false).build());
    ImmutableList<Double> nanList = ImmutableList.of(Double.NaN);
    ImmutableMap<String, Double> nanMap = ImmutableMap.of("k", Double.NaN);
    Double nan = Double.NaN;

    assertThat(optOutEquality.objectEquals(nanList, nanList)).isTrue();
    assertThat(optOutEquality.objectEquals(nanMap, nanMap)).isTrue();
    assertThat(optOutEquality.objectEquals(nanList, ImmutableList.of(1.0d))).isFalse();
    assertThat(optOutEquality.objectEquals(nan, nan)).isFalse();
  }
}
