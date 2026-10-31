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
import com.google.common.collect.ImmutableMap;
import com.google.protobuf.Any;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.DoubleValue;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.Message;
import com.google.protobuf.NullValue;
import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import com.google.testing.junit.testparameterinjector.TestParameters;
import dev.cel.common.CelDescriptorUtil;
import dev.cel.common.CelDescriptors;
import dev.cel.common.CelErrorCode;
import dev.cel.common.CelOptions;
import dev.cel.common.exceptions.CelRuntimeException;
import dev.cel.common.internal.CelDescriptorPool;
import dev.cel.common.internal.DefaultDescriptorPool;
import dev.cel.common.internal.DefaultMessageFactory;
// CEL-Internal-1
import dev.cel.common.internal.ProtoMessageFactory;
import dev.cel.common.internal.WellKnownProto;
import dev.cel.expr.conformance.proto2.TestAllTypes;
import dev.cel.expr.conformance.proto2.TestAllTypes.NestedGroup;
import dev.cel.expr.conformance.proto2.TestAllTypesExtensions;
import dev.cel.expr.conformance.proto2.TestAllTypesProto;
import java.time.Duration;
import java.time.Instant;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(TestParameterInjector.class)
// Tests construct Duration/Instant boundaries and parameterized bounds from raw seconds and nanos.
@SuppressWarnings("GoodTime-ApiWithNumericTimeUnit")
public final class DescriptorMessageProviderTest {

  private RuntimeTypeProvider provider;

  @Before
  public void setUp() {
    CelOptions options = CelOptions.current().build();
    CelDescriptors celDescriptors =
        CelDescriptorUtil.getAllDescriptorsFromFileDescriptor(
            dev.cel.expr.conformance.proto3.TestAllTypes.getDescriptor().getFile());
    ProtoMessageFactory dynamicMessageFactory =
        DefaultMessageFactory.create(DefaultDescriptorPool.create(celDescriptors));
    provider = new DescriptorMessageProvider(dynamicMessageFactory, options);
  }

  @Test
  public void createMessage_success() {
    dev.cel.expr.conformance.proto3.TestAllTypes message =
        (dev.cel.expr.conformance.proto3.TestAllTypes)
            provider.createMessage(
                dev.cel.expr.conformance.proto3.TestAllTypes.getDescriptor().getFullName(),
                ImmutableMap.of("single_int32", 1));
    assertThat(message)
        .isEqualTo(
            dev.cel.expr.conformance.proto3.TestAllTypes.newBuilder().setSingleInt32(1).build());
  }

  @Test
  public void createMessageDynamic_success() {
    ImmutableList<Descriptor> descriptors =
        ImmutableList.of(dev.cel.expr.conformance.proto3.TestAllTypes.getDescriptor());
    provider = DynamicMessageFactory.typeProvider(descriptors);
    Message message =
        (Message)
            provider.createMessage(
                dev.cel.expr.conformance.proto3.TestAllTypes.getDescriptor().getFullName(),
                ImmutableMap.of("single_int32", 1));
    assertThat(message).isInstanceOf(dev.cel.expr.conformance.proto3.TestAllTypes.class);
    assertThat(message)
        .isEqualTo(
            dev.cel.expr.conformance.proto3.TestAllTypes.newBuilder().setSingleInt32(1).build());
  }

  @Test
  public void createNestedGroup_success() throws Exception {
    String groupType = "cel.expr.conformance.proto2.TestAllTypes.NestedGroup";
    provider =
        DynamicMessageFactory.typeProvider(
            ImmutableList.of(TestAllTypes.NestedGroup.getDescriptor()));
    Message message =
        (Message)
            provider.createMessage(
                groupType, ImmutableMap.of("single_id", 1, "single_name", "cert"));
    assertThat(message).isInstanceOf(NestedGroup.class);
    assertThat(message)
        .isEqualTo(NestedGroup.newBuilder().setSingleId(1).setSingleName("cert").build());
  }

  @Test
  public void createMessage_missingDescriptorError() {
    CelRuntimeException e =
        Assert.assertThrows(
            CelRuntimeException.class,
            () ->
                provider.createMessage(
                    "google.api.tools.contract.test.MissingMessageTypes", ImmutableMap.of()));
    assertThat(e.getErrorCode()).isEqualTo(CelErrorCode.ATTRIBUTE_NOT_FOUND);
    assertThat(e).hasCauseThat().isNull();
  }

  @Test
  public void createMessage_unsetWrapperField() {
    dev.cel.expr.conformance.proto3.TestAllTypes message =
        (dev.cel.expr.conformance.proto3.TestAllTypes)
            provider.createMessage(
                dev.cel.expr.conformance.proto3.TestAllTypes.getDescriptor().getFullName(),
                ImmutableMap.of(
                    "single_int64_wrapper", dev.cel.common.values.NullValue.NULL_VALUE));
    assertThat(message).isEqualToDefaultInstance();
  }

  @Test
  public void createMessage_badFieldError() {
    Assert.assertThrows(
        IllegalArgumentException.class,
        () ->
            provider.createMessage(
                dev.cel.expr.conformance.proto3.TestAllTypes.getDescriptor().getFullName(),
                ImmutableMap.of("bad_field", dev.cel.common.values.NullValue.NULL_VALUE)));
  }

  @Test
  public void hasField_mapKeyFound() {
    assertThat(provider.hasField(ImmutableMap.of("hello", "world"), "hello")).isEqualTo(true);
  }

  @Test
  public void hasField_mapKeyNotFound() {
    assertThat(provider.hasField(ImmutableMap.of(), "hello")).isEqualTo(false);
  }

  @Test
  public void selectField_mapKeyFound() {
    assertThat(provider.selectField(ImmutableMap.of("hello", "world"), "hello")).isEqualTo("world");
  }

  @Test
  public void selectField_mapKeyNotFound() {
    CelRuntimeException e =
        Assert.assertThrows(
            CelRuntimeException.class, () -> provider.selectField(ImmutableMap.of(), "hello"));
    assertThat(e.getErrorCode()).isEqualTo(CelErrorCode.ATTRIBUTE_NOT_FOUND);
    assertThat(e).hasCauseThat().isNull();
  }

  @Test
  public void selectField_unsetWrapperField() {
    assertThat(
            provider.selectField(
                dev.cel.expr.conformance.proto3.TestAllTypes.getDefaultInstance(),
                "single_int64_wrapper"))
        .isEqualTo(NullValue.NULL_VALUE);
  }

  @Test
  public void selectField_nonProtoObjectError() {
    CelRuntimeException e =
        Assert.assertThrows(
            CelRuntimeException.class, () -> provider.selectField("hello", "not_a_field"));
    assertThat(e.getErrorCode()).isEqualTo(CelErrorCode.ATTRIBUTE_NOT_FOUND);
    assertThat(e).hasCauseThat().isNull();
  }

  @Test
  public void selectField_extensionUsingDynamicTypes() {
    CelDescriptors celDescriptors =
        CelDescriptorUtil.getAllDescriptorsFromFileDescriptor(
            ImmutableList.of(TestAllTypesExtensions.getDescriptor()));
    CelDescriptorPool pool = DefaultDescriptorPool.create(celDescriptors);

    provider =
        new DescriptorMessageProvider(
            DefaultMessageFactory.create(pool), CelOptions.current().build());

    long result =
        (long)
            provider.selectField(
                TestAllTypes.newBuilder().setExtension(TestAllTypesExtensions.int32Ext, 10).build(),
                TestAllTypesProto.getDescriptor().getPackage() + ".int32_ext");

    assertThat(result).isEqualTo(10);
  }

  @Test
  public void createMessage_wellKnownType_withCustomMessageProvider(
      @TestParameter WellKnownProto wellKnownProto) {
    if (wellKnownProto.equals(WellKnownProto.ANY_VALUE)) {
      return;
    }

    Descriptor wellKnownDescriptor =
        DefaultDescriptorPool.INSTANCE.findDescriptor(wellKnownProto.typeName()).get();
    DescriptorMessageProvider messageProvider =
        new DescriptorMessageProvider(
            msgName ->
                msgName.equals(wellKnownDescriptor.getFullName())
                    ? DynamicMessage.newBuilder(wellKnownDescriptor)
                    : null);

    Object createdMessage =
        messageProvider.createMessage(wellKnownDescriptor.getFullName(), ImmutableMap.of());

    assertThat(createdMessage).isNotNull();
  }

  @Test
  public void createMessage_anyType_withCustomMessageProvider() {
    DescriptorMessageProvider messageProvider =
        new DescriptorMessageProvider(
            msgName -> msgName.equals(Any.getDescriptor().getFullName()) ? Any.newBuilder() : null);

    double createdMessage =
        (double)
            messageProvider.createMessage(
                Any.getDescriptor().getFullName(),
                ImmutableMap.of("type_url", "types.googleapis.com/google.protobuf.DoubleValue"));

    assertThat(createdMessage).isEqualTo(0.0d);
  }

  @Test
  public void createMessage_doubleValue_withCustomMessageProvider() {
    DescriptorMessageProvider messageProvider =
        new DescriptorMessageProvider(
            msgName ->
                msgName.equals("google.protobuf.DoubleValue")
                    ? DynamicMessage.newBuilder(DoubleValue.getDescriptor())
                    : null);

    double value =
        (double) messageProvider.createMessage("google.protobuf.DoubleValue", ImmutableMap.of());

    assertThat(value).isEqualTo(0.0d);
  }

  @Test
  public void createMessage_durationAndTimestamp_atBounds() {
    assertThat(
            provider.createMessage(
                WellKnownProto.DURATION.typeName(),
                ImmutableMap.of("seconds", 315576000000L, "nanos", 999999999L)))
        .isEqualTo(Duration.ofSeconds(315576000000L, 999999999L));
    assertThat(
            provider.createMessage(
                WellKnownProto.TIMESTAMP.typeName(),
                ImmutableMap.of("seconds", 253402300799L, "nanos", 999999999L)))
        .isEqualTo(Instant.ofEpochSecond(253402300799L, 999999999L));
  }

  @Test
  @TestParameters("{typeName: 'google.protobuf.Duration', seconds: 315576000001, nanos: 0}")
  @TestParameters("{typeName: 'google.protobuf.Duration', seconds: 1, nanos: -1}")
  @TestParameters("{typeName: 'google.protobuf.Timestamp', seconds: 253402300800, nanos: 0}")
  @TestParameters("{typeName: 'google.protobuf.Timestamp', seconds: 0, nanos: -1}")
  public void createMessage_durationAndTimestamp_outOfRangeThrows(
      String typeName, long seconds, long nanos) {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            provider.createMessage(typeName, ImmutableMap.of("seconds", seconds, "nanos", nanos)));
  }
}
