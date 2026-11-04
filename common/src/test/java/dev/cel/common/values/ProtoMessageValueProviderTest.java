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
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import com.google.testing.junit.testparameterinjector.TestParameters;
import dev.cel.common.CelDescriptorUtil;
import dev.cel.common.CelOptions;
import dev.cel.common.internal.CelDescriptorPool;
import dev.cel.common.internal.DefaultDescriptorPool;
import dev.cel.common.internal.DefaultMessageFactory;
import dev.cel.common.internal.DynamicProto;
import dev.cel.common.internal.ProtoMessageFactory;
import dev.cel.expr.conformance.proto2.TestAllTypes;
import dev.cel.expr.conformance.proto2.TestAllTypesExtensions;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(TestParameterInjector.class)
@SuppressWarnings("GoodTime-ApiWithNumericTimeUnit") // Raw seconds/nanos boundary tests
public class ProtoMessageValueProviderTest {

  private static final CelDescriptorPool DESCRIPTOR_POOL =
      DefaultDescriptorPool.create(
          CelDescriptorUtil.getAllDescriptorsFromFileDescriptor(
              ImmutableList.of(
                  TestAllTypes.getDescriptor().getFile(), TestAllTypesExtensions.getDescriptor())));
  private static final ProtoMessageFactory MESSAGE_FACTORY =
      DefaultMessageFactory.create(DESCRIPTOR_POOL);
  private static final DynamicProto DYNAMIC_PROTO = DynamicProto.create(MESSAGE_FACTORY);
  private static final ProtoMessageValueProvider VALUE_PROVIDER =
      ProtoMessageValueProvider.newInstance(CelOptions.DEFAULT, DYNAMIC_PROTO);

  @Test
  public void newValue_createEmptyProtoMessage() {
    ProtoMessageValue protoMessageValue =
        (ProtoMessageValue)
            VALUE_PROVIDER
                .newValue(TestAllTypes.getDescriptor().getFullName(), ImmutableMap.of())
                .get();

    assertThat(protoMessageValue.isZeroValue()).isTrue();
  }

  @Test
  public void newValue_createProtoMessage_fieldsPopulated() {
    ProtoMessageValueProvider protoMessageValueProvider =
        ProtoMessageValueProvider.newInstance(CelOptions.current().build(), DYNAMIC_PROTO);

    ProtoMessageValue protoMessageValue =
        (ProtoMessageValue)
            protoMessageValueProvider
                .newValue(
                    TestAllTypes.getDescriptor().getFullName(),
                    ImmutableMap.of(
                        "single_int32",
                        1,
                        "single_int64",
                        2,
                        "single_uint32",
                        3,
                        "single_uint64",
                        4,
                        "single_double",
                        5.5d,
                        "single_bool",
                        true,
                        "single_string",
                        "hello",
                        "single_timestamp",
                        Instant.ofEpochSecond(50),
                        "single_duration",
                        Duration.ofSeconds(100)))
                .get();

    assertThat(protoMessageValue.isZeroValue()).isFalse();
    assertThat(protoMessageValue.select("single_int32")).isEqualTo(1L);
    assertThat(protoMessageValue.select("single_int64")).isEqualTo(2L);
    assertThat(protoMessageValue.select("single_uint32")).isEqualTo(UnsignedLong.valueOf(3L));
    assertThat(protoMessageValue.select("single_uint64")).isEqualTo(UnsignedLong.valueOf(4L));
    assertThat(protoMessageValue.select("single_double")).isEqualTo(5.5d);
    assertThat(protoMessageValue.select("single_bool")).isEqualTo(true);
    assertThat(protoMessageValue.select("single_string")).isEqualTo("hello");
    assertThat(protoMessageValue.select("single_timestamp")).isEqualTo(Instant.ofEpochSecond(50));
    assertThat(protoMessageValue.select("single_duration")).isEqualTo(Duration.ofSeconds(100));
  }

  @Test
  public void newValue_createProtoMessage_unsignedLongFieldsPopulated() {
    ProtoMessageValue protoMessageValue =
        (ProtoMessageValue)
            VALUE_PROVIDER
                .newValue(
                    TestAllTypes.getDescriptor().getFullName(),
                    ImmutableMap.of(
                        "single_uint32", 3, "single_uint64", UnsignedLong.MAX_VALUE.longValue()))
                .get();

    assertThat(protoMessageValue.isZeroValue()).isFalse();
    assertThat(protoMessageValue.select("single_uint32")).isEqualTo(UnsignedLong.valueOf(3L));
    assertThat(protoMessageValue.select("single_uint64")).isEqualTo(UnsignedLong.MAX_VALUE);
  }

  @Test
  public void newValue_createProtoMessage_wrappersPopulated() {
    ProtoMessageValue protoMessageValue =
        (ProtoMessageValue)
            VALUE_PROVIDER
                .newValue(
                    TestAllTypes.getDescriptor().getFullName(),
                    ImmutableMap.of(
                        "single_int32_wrapper",
                        1,
                        "single_int64_wrapper",
                        2,
                        "single_uint32_wrapper",
                        3,
                        "single_uint64_wrapper",
                        4,
                        "single_double_wrapper",
                        5.5d,
                        "single_bool_wrapper",
                        true,
                        "single_string_wrapper",
                        "hello"))
                .get();

    assertThat(protoMessageValue.isZeroValue()).isFalse();
    assertThat(protoMessageValue.select("single_int32_wrapper")).isEqualTo(1L);
    assertThat(protoMessageValue.select("single_int32_wrapper")).isEqualTo(1L);
    assertThat(protoMessageValue.select("single_int64_wrapper")).isEqualTo(2L);
    assertThat(protoMessageValue.select("single_uint32_wrapper"))
        .isEqualTo(UnsignedLong.valueOf(3L));
    assertThat(protoMessageValue.select("single_uint64_wrapper"))
        .isEqualTo(UnsignedLong.valueOf(4L));
    assertThat(protoMessageValue.select("single_double_wrapper")).isEqualTo(5.5d);
    assertThat(protoMessageValue.select("single_bool_wrapper")).isEqualTo(true);
    assertThat(protoMessageValue.select("single_string_wrapper")).isEqualTo("hello");
  }

  @Test
  public void newValue_createProtoMessage_extensionFieldsPopulated() {
    ProtoMessageValue protoMessageValue =
        (ProtoMessageValue)
            VALUE_PROVIDER
                .newValue(
                    TestAllTypes.getDescriptor().getFullName(),
                    ImmutableMap.of("cel.expr.conformance.proto2.int32_ext", 1))
                .get();

    assertThat(protoMessageValue.isZeroValue()).isFalse();
    assertThat(protoMessageValue.select("cel.expr.conformance.proto2.int32_ext")).isEqualTo(1);
  }

  @Test
  public void newValue_invalidMessageName_returnsEmpty() {
    assertThat(VALUE_PROVIDER.newValue("bogus", ImmutableMap.of())).isEmpty();
  }

  @Test
  public void newValue_invalidField_throws() {
    IllegalArgumentException e =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                VALUE_PROVIDER.newValue(
                    TestAllTypes.getDescriptor().getFullName(), ImmutableMap.of("bogus", 1)));

    assertThat(e)
        .hasMessageThat()
        .isEqualTo(
            "field 'bogus' is not declared in message"
                + " 'cel.expr.conformance.proto2.TestAllTypes'");
  }

  @Test
  public void newValue_onCombinedProvider() {
    CelValueProvider celValueProvider = (structType, fields) -> Optional.empty();
    CelValueProvider combinedProvider =
        CombinedCelValueProvider.combine(celValueProvider, VALUE_PROVIDER);

    ProtoMessageValue protoMessageValue =
        (ProtoMessageValue)
            combinedProvider
                .newValue(
                    TestAllTypes.getDescriptor().getFullName(), ImmutableMap.of("single_int32", 1))
                .get();

    assertThat(protoMessageValue.isZeroValue()).isFalse();
    assertThat(protoMessageValue.select("single_int32")).isEqualTo(1L);
  }

  @Test
  @TestParameters("{seconds: 315576000000, nanos: 999999999}")
  @TestParameters("{seconds: -315576000000, nanos: -999999999}")
  @TestParameters("{seconds: -315576000000, nanos: 0}")
  @TestParameters("{seconds: 0, nanos: -999999999}")
  public void newValue_durationAtBounds(long seconds, long nanos) {
    ImmutableMap<String, Object> fields = ImmutableMap.of("seconds", seconds, "nanos", nanos);

    Optional<Object> result = VALUE_PROVIDER.newValue("google.protobuf.Duration", fields);

    assertThat(result).hasValue(Duration.ofSeconds(seconds, nanos));
  }

  @Test
  @TestParameters("{seconds: 253402300799, nanos: 999999999}")
  @TestParameters("{seconds: -62135596800, nanos: 0}")
  @TestParameters("{seconds: -62135596800, nanos: 999999999}")
  @TestParameters("{seconds: -1, nanos: 999999999}")
  public void newValue_timestampAtBounds(long seconds, long nanos) {
    ImmutableMap<String, Object> fields = ImmutableMap.of("seconds", seconds, "nanos", nanos);

    Optional<Object> result = VALUE_PROVIDER.newValue("google.protobuf.Timestamp", fields);

    assertThat(result).hasValue(Instant.ofEpochSecond(seconds, nanos));
  }

  @Test
  @TestParameters("{typeName: 'google.protobuf.Duration', seconds: 315576000001, nanos: 0}")
  @TestParameters("{typeName: 'google.protobuf.Duration', seconds: -315576000001, nanos: 0}")
  @TestParameters("{typeName: 'google.protobuf.Duration', seconds: 0, nanos: 1000000000}")
  @TestParameters("{typeName: 'google.protobuf.Duration', seconds: 0, nanos: -1000000000}")
  @TestParameters("{typeName: 'google.protobuf.Duration', seconds: 1, nanos: -1}")
  @TestParameters("{typeName: 'google.protobuf.Duration', seconds: -1, nanos: 1}")
  @TestParameters("{typeName: 'google.protobuf.Timestamp', seconds: 253402300800, nanos: 0}")
  @TestParameters("{typeName: 'google.protobuf.Timestamp', seconds: -62135596801, nanos: 0}")
  @TestParameters("{typeName: 'google.protobuf.Timestamp', seconds: 0, nanos: 1000000000}")
  @TestParameters("{typeName: 'google.protobuf.Timestamp', seconds: 0, nanos: -1}")
  @TestParameters("{typeName: 'google.protobuf.Timestamp', seconds: -1, nanos: -1}")
  public void newValue_durationAndTimestamp_outOfRangeThrows(
      String typeName, long seconds, long nanos) {
    ImmutableMap<String, Object> fields = ImmutableMap.of("seconds", seconds, "nanos", nanos);

    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class, () -> VALUE_PROVIDER.newValue(typeName, fields));

    assertThat(thrown).hasMessageThat().contains("is not valid");
  }
}
