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
import static org.junit.Assert.assertThrows;

import com.google.protobuf.Any;
import com.google.protobuf.ByteString;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.DoubleValue;
import com.google.protobuf.ExtensionRegistryLite;
import com.google.protobuf.FloatValue;
import com.google.protobuf.Int32Value;
import com.google.protobuf.Int64Value;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.ListValue;
import com.google.protobuf.Message;
import com.google.protobuf.NullValue;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import dev.cel.common.CelDescriptorUtil;
import dev.cel.common.internal.CelLiteDescriptorPool;
import dev.cel.common.internal.DefaultDescriptorPool;
import dev.cel.common.internal.DefaultLiteDescriptorPool;
import dev.cel.common.internal.DefaultMessageFactory;
import dev.cel.common.internal.DynamicProto;
import dev.cel.common.internal.ProtoEquality;
import dev.cel.common.internal.ProtoTimeUtils;
import dev.cel.expr.conformance.proto2.TestAllTypes.NestedGroup;
import dev.cel.expr.conformance.proto2.TestRequired;
import dev.cel.expr.conformance.proto2.TestRequiredCelDescriptor;
import dev.cel.expr.conformance.proto3.NestedTestAllTypes;
import dev.cel.expr.conformance.proto3.NestedTestAllTypesCelDescriptor;
import dev.cel.expr.conformance.proto3.TestAllTypes;
import dev.cel.expr.conformance.proto3.TestAllTypes.NestedEnum;
import dev.cel.expr.conformance.proto3.TestAllTypes.NestedMessage;
import dev.cel.expr.conformance.proto3.TestAllTypesCelDescriptor;
import dev.cel.protobuf.CelLiteDescriptor.MessageLiteDescriptor;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(TestParameterInjector.class)
public final class ProtoLiteEqualityTest {

  private static final CelLiteDescriptorPool DESCRIPTOR_POOL =
      DefaultLiteDescriptorPool.newInstance(
          TestAllTypesCelDescriptor.getDescriptor(),
          NestedTestAllTypesCelDescriptor.getDescriptor(),
          dev.cel.expr.conformance.proto2.TestAllTypesCelDescriptor.getDescriptor(),
          dev.cel.expr.conformance.proto2.NestedTestAllTypesCelDescriptor.getDescriptor(),
          TestRequiredCelDescriptor.getDescriptor());

  private static final ProtoLiteEquality PROTO_LITE_EQUALITY =
      new ProtoLiteEquality(DESCRIPTOR_POOL);

  private static final ProtoEquality PROTO_EQUALITY =
      new ProtoEquality(
          DynamicProto.create(
              DefaultMessageFactory.create(
                  DefaultDescriptorPool.create(
                      CelDescriptorUtil.getAllDescriptorsFromFileDescriptor(
                          TestAllTypes.getDescriptor().getFile(),
                          dev.cel.expr.conformance.proto2.TestAllTypes.getDescriptor()
                              .getFile())))));

  @Test
  public void equals_matchesProtoEqualityOracle(@TestParameter OracleTestCase testCase) {
    assertThat(PROTO_LITE_EQUALITY.equals(testCase.x, testCase.y)).isEqualTo(testCase.expected);
    assertThat(PROTO_LITE_EQUALITY.equals(testCase.y, testCase.x)).isEqualTo(testCase.expected);
    assertThat(PROTO_EQUALITY.equals(testCase.x, testCase.y)).isEqualTo(testCase.expected);
    assertThat(PROTO_EQUALITY.equals(testCase.y, testCase.x)).isEqualTo(testCase.expected);
  }

  @Test
  public void equals_anyDivergentCases(@TestParameter AnyDivergentTestCase testCase) {
    assertThat(PROTO_LITE_EQUALITY.equals(testCase.x, testCase.y)).isEqualTo(testCase.expected);
    assertThat(PROTO_LITE_EQUALITY.equals(testCase.y, testCase.x)).isEqualTo(testCase.expected);
  }

  @Test
  public void equals_closedEnumMapEntryOutOfRange_divergesFromFullProto() {
    dev.cel.expr.conformance.proto2.TestAllTypes withUnknownMapEnum =
        parseProto2(out ->
            out.writeBytes(
                83,
                encode(entry -> {
                  entry.writeInt32(1, 1);
                  entry.writeEnum(2, 999);
                })));
    dev.cel.expr.conformance.proto2.TestAllTypes empty =
        dev.cel.expr.conformance.proto2.TestAllTypes.getDefaultInstance();

    assertThat(PROTO_LITE_EQUALITY.equals(withUnknownMapEnum, empty)).isTrue();
    assertThat(PROTO_EQUALITY.equals(withUnknownMapEnum, empty)).isFalse();
  }

  @Test
  public void equals_nestingDepthAtLimit_succeeds() {
    NestedTestAllTypes left = nestedProto3Message(/* childCount= */ 99, /* leafInt= */ 1);
    NestedTestAllTypes right = nestedProto3Message(/* childCount= */ 99, /* leafInt= */ 1);

    assertThat(PROTO_LITE_EQUALITY.equals(left, right)).isTrue();
  }

  @Test
  public void equals_nestingDepthExceedsLimit_throwsIllegalArgumentException() {
    NestedTestAllTypes left = nestedProto3Message(/* childCount= */ 100, /* leafInt= */ 1);
    NestedTestAllTypes right = nestedProto3Message(/* childCount= */ 100, /* leafInt= */ 2);

    IllegalArgumentException thrown =
        assertThrows(IllegalArgumentException.class, () -> PROTO_LITE_EQUALITY.equals(left, right));
    assertThat(thrown).hasMessageThat().isEqualTo("Message nesting exceeds 100");
  }

  @Test
  public void equals_listValueDepthExceedsLimit_throwsIllegalArgumentException() {
    Value left = nestedListValue(/* listDepth= */ 51, /* leaf= */ -0.0);
    Value right = nestedListValue(/* listDepth= */ 51, /* leaf= */ 0.0);

    IllegalArgumentException thrown =
        assertThrows(IllegalArgumentException.class, () -> PROTO_LITE_EQUALITY.equals(left, right));
    assertThat(thrown).hasMessageThat().isEqualTo("Message nesting exceeds 100");
  }

  @Test
  public void equals_anyDepthExceedsLimit_throwsIllegalArgumentException() {
    Any left = nestedAny(/* anyDepth= */ 101, /* leafInt= */ 1);
    Any right = nestedAny(/* anyDepth= */ 101, /* leafInt= */ 2);

    IllegalArgumentException thrown =
        assertThrows(IllegalArgumentException.class, () -> PROTO_LITE_EQUALITY.equals(left, right));
    assertThat(thrown).hasMessageThat().isEqualTo("Message nesting exceeds 100");
  }

  @Test
  public void equals_unregisteredMessage_comparesByRawFields() {
    ProtoLiteEquality emptyPoolEquality =
        new ProtoLiteEquality(DefaultLiteDescriptorPool.newInstance());
    TestAllTypes nan1 = TestAllTypes.newBuilder().setSingleDouble(Double.NaN).build();
    TestAllTypes nan2 = TestAllTypes.newBuilder().setSingleDouble(Double.NaN).build();
    dev.cel.expr.conformance.proto2.TestAllTypes minusZero =
        dev.cel.expr.conformance.proto2.TestAllTypes.newBuilder().setSingleDouble(-0.0).build();
    dev.cel.expr.conformance.proto2.TestAllTypes plusZero =
        dev.cel.expr.conformance.proto2.TestAllTypes.newBuilder().setSingleDouble(0.0).build();

    assertThat(emptyPoolEquality.equals(nan1, nan2)).isTrue();
    assertThat(emptyPoolEquality.equals(minusZero, plusZero)).isFalse();
  }

  @Test
  public void rawFields_reorderedTagsAndSemanticFiltering() {
    MessageLiteDescriptor descriptor =
        DESCRIPTOR_POOL.findDescriptor(TestAllTypes.getDefaultInstance()).get();
    ByteString wireOrder1 =
        encode(out -> {
          out.writeInt32(1, 42);
          out.writeDouble(12, -0.0);
          out.writeInt32(998, 1);
          out.writeInt32(999, 2);
        });
    ByteString wireOrder2 =
        encode(out -> {
          out.writeInt32(999, 2);
          out.writeDouble(12, 99.5);
          out.writeInt32(998, 1);
          out.writeInt32(1, 42);
        });

    assertThat(ProtoLiteEquality.rawFields(wireOrder1, descriptor))
        .isEqualTo(ProtoLiteEquality.rawFields(wireOrder2, descriptor));
  }

  @Test
  public void rawFields_malformedWireBytes_throwsIllegalArgumentException() {
    ByteString malformed = ByteString.copyFromUtf8("test");

    assertThrows(
        IllegalArgumentException.class,
        () -> ProtoLiteEquality.rawFields(malformed, /* descriptor= */ null));
  }

  private interface WireEncoder {
    void encode(CodedOutputStream out) throws IOException;
  }

  private static ByteString encode(WireEncoder encoder) {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    CodedOutputStream out = CodedOutputStream.newInstance(bytes);
    try {
      encoder.encode(out);
      out.flush();
    } catch (IOException e) {
      throw new AssertionError(e);
    }
    return ByteString.copyFrom(bytes.toByteArray());
  }

  private static TestAllTypes parseProto3(WireEncoder encoder) {
    try {
      return TestAllTypes.parseFrom(encode(encoder), ExtensionRegistryLite.getEmptyRegistry());
    } catch (InvalidProtocolBufferException e) {
      throw new AssertionError(e);
    }
  }

  private static dev.cel.expr.conformance.proto2.TestAllTypes parseProto2(WireEncoder encoder) {
    try {
      return dev.cel.expr.conformance.proto2.TestAllTypes.parseFrom(
          encode(encoder), ExtensionRegistryLite.getEmptyRegistry());
    } catch (InvalidProtocolBufferException e) {
      throw new AssertionError(e);
    }
  }

  private static DoubleValue parseDoubleValue(WireEncoder encoder) {
    try {
      return DoubleValue.parseFrom(encode(encoder), ExtensionRegistryLite.getEmptyRegistry());
    } catch (InvalidProtocolBufferException e) {
      throw new AssertionError(e);
    }
  }

  private static ByteString mapInt32StringEntry(int key, String value) {
    return encode(out -> {
      out.writeInt32(1, key);
      out.writeString(2, value);
    });
  }

  private static Value nestedListValue(int listDepth, double leaf) {
    Value current = Value.newBuilder().setNumberValue(leaf).build();
    for (int i = 0; i < listDepth; i++) {
      current = Value.newBuilder().setListValue(ListValue.newBuilder().addValues(current)).build();
    }
    return current;
  }

  private static NestedTestAllTypes nestedProto3Message(int childCount, int leafInt) {
    NestedTestAllTypes current =
        NestedTestAllTypes.newBuilder()
            .setPayload(TestAllTypes.newBuilder().setSingleInt32(leafInt))
            .build();
    for (int i = 0; i < childCount; i++) {
      current = NestedTestAllTypes.newBuilder().setChild(current).build();
    }
    return current;
  }

  private static Any nestedAny(int anyDepth, int leafInt) {
    Any current = Any.pack(TestAllTypes.newBuilder().setSingleInt32(leafInt).build());
    for (int i = 1; i < anyDepth; i++) {
      current = Any.pack(current);
    }
    return current;
  }

  private static dev.cel.expr.conformance.proto2.TestAllTypes populatedProto2Scalars(
      double singleDouble) {
    return dev.cel.expr.conformance.proto2.TestAllTypes.newBuilder()
        .setSingleInt32(-10)
        .setSingleInt64(-20L)
        .setSingleUint32(30)
        .setSingleUint64(40L)
        .setSingleSint32(-50)
        .setSingleSint64(-60L)
        .setSingleFixed32(70)
        .setSingleFixed64(80L)
        .setSingleSfixed32(-90)
        .setSingleSfixed64(-100L)
        .setSingleFloat(1.25f)
        .setSingleDouble(singleDouble)
        .setSingleBool(false)
        .setSingleString("hello")
        .setSingleBytes(ByteString.copyFromUtf8("bytes"))
        .setStandaloneEnum(dev.cel.expr.conformance.proto2.TestAllTypes.NestedEnum.BAZ)
        .setSingleDuration(ProtoTimeUtils.toProtoDuration(Duration.ofSeconds(12, 345)))
        .setSingleTimestamp(ProtoTimeUtils.toProtoTimestamp(Instant.ofEpochSecond(1000L, 500)))
        .build();
  }

  @SuppressWarnings("ImmutableEnumChecker") // Test only
  private enum AnyDivergentTestCase {
    UNKNOWN_TYPE_URLS_DIFFER(
        Any.newBuilder()
            .setTypeUrl("type.googleapis.com/unknown.TypeA")
            .setValue(encode(out -> out.writeInt32(1, 7)))
            .build(),
        Any.newBuilder()
            .setTypeUrl("type.googleapis.com/unknown.TypeB")
            .setValue(encode(out -> out.writeInt32(1, 7)))
            .build(),
        false),
    MALFORMED_TYPE_URLS_WITHOUT_SLASH_DIFFER(
        Any.newBuilder().setTypeUrl("no_slash_a").setValue(ByteString.EMPTY).build(),
        Any.newBuilder().setTypeUrl("no_slash_b").setValue(ByteString.EMPTY).build(),
        false),
    MALFORMED_TYPE_URL_WITHOUT_SLASH_DOES_NOT_UNPACK(
        Any.newBuilder()
            .setTypeUrl("cel.expr.conformance.proto2.TestAllTypes")
            .setValue(
                dev.cel.expr.conformance.proto2.TestAllTypes.newBuilder()
                    .setSingleDouble(-0.0)
                    .build()
                    .toByteString())
            .build(),
        Any.newBuilder()
            .setTypeUrl("cel.expr.conformance.proto2.TestAllTypes")
            .setValue(
                dev.cel.expr.conformance.proto2.TestAllTypes.newBuilder()
                    .setSingleDouble(0.0)
                    .build()
                    .toByteString())
            .build(),
        false),
    UNPACKABLE_VS_UNKNOWN_TYPE_URL(
        Any.pack(TestAllTypes.newBuilder().setSingleInt32(7).build()),
        Any.newBuilder()
            .setTypeUrl("type.googleapis.com/unknown.TypeA")
            .setValue(encode(out -> out.writeInt32(1, 7)))
            .build(),
        false),
    CORRUPT_PAYLOAD_SAME_BYTES(
        Any.newBuilder()
            .setTypeUrl("type.googleapis.com/cel.expr.conformance.proto3.TestAllTypes")
            .setValue(ByteString.copyFromUtf8("test"))
            .build(),
        Any.newBuilder()
            .setTypeUrl("type.googleapis.com/cel.expr.conformance.proto3.TestAllTypes")
            .setValue(ByteString.copyFromUtf8("test"))
            .build(),
        true),
    CORRUPT_PAYLOAD_DIFFERENT_BYTES(
        Any.newBuilder()
            .setTypeUrl("type.googleapis.com/cel.expr.conformance.proto3.TestAllTypes")
            .setValue(ByteString.copyFromUtf8("test"))
            .build(),
        Any.newBuilder()
            .setTypeUrl("type.googleapis.com/cel.expr.conformance.proto3.TestAllTypes")
            .setValue(ByteString.copyFromUtf8("other"))
            .build(),
        false),
    UNINITIALIZED_REQUIRED_FIELD_PARTIAL_UNPACK(
        Any.newBuilder()
            .setTypeUrl("type.googleapis.com/" + TestRequired.getDescriptor().getFullName())
            .setValue(
                encode(out -> {
                  out.writeInt32(998, 1);
                  out.writeInt32(999, 2);
                }))
            .build(),
        Any.newBuilder()
            .setTypeUrl("type.googleapis.com/" + TestRequired.getDescriptor().getFullName())
            .setValue(
                encode(out -> {
                  out.writeInt32(999, 2);
                  out.writeInt32(998, 1);
                }))
            .build(),
        true),
    MAP_ENTRY_TYPE_URL_FALLS_BACK_TO_BYTES(
        Any.newBuilder()
            .setTypeUrl("type.googleapis.com/google.protobuf.Struct.FieldsEntry")
            .setValue(
                encode(out -> {
                  out.writeString(1, "k");
                  out.writeBytes(2, Value.newBuilder().setNumberValue(-0.0).build().toByteString());
                }))
            .build(),
        Any.newBuilder()
            .setTypeUrl("type.googleapis.com/google.protobuf.Struct.FieldsEntry")
            .setValue(
                encode(out -> {
                  out.writeString(1, "k");
                  out.writeBytes(2, Value.newBuilder().setNumberValue(0.0).build().toByteString());
                }))
            .build(),
        false),
    ;

    private final Any x;
    private final Any y;
    private final boolean expected;

    AnyDivergentTestCase(Any x, Any y, boolean expected) {
      this.x = x;
      this.y = y;
      this.expected = expected;
    }
  }

  @SuppressWarnings("ImmutableEnumChecker") // Test only
  private enum OracleTestCase {
    NAN_DOUBLE(
        TestAllTypes.newBuilder().setSingleDouble(Double.NaN).build(),
        TestAllTypes.newBuilder().setSingleDouble(Double.NaN).build(),
        false),
    NAN_FLOAT(
        TestAllTypes.newBuilder().setSingleFloat(Float.NaN).build(),
        TestAllTypes.newBuilder().setSingleFloat(Float.NaN).build(),
        false),
    NAN_REPEATED_DOUBLE(
        TestAllTypes.newBuilder().addRepeatedDouble(1.0).addRepeatedDouble(Double.NaN).build(),
        TestAllTypes.newBuilder().addRepeatedDouble(1.0).addRepeatedDouble(Double.NaN).build(),
        false),
    NAN_MAP_DOUBLE(
        TestAllTypes.newBuilder().putMapStringDouble("k", Double.NaN).build(),
        TestAllTypes.newBuilder().putMapStringDouble("k", Double.NaN).build(),
        false),
    PROTO2_EXPLICIT_SIGNED_ZERO_DOUBLE(
        dev.cel.expr.conformance.proto2.TestAllTypes.newBuilder().setSingleDouble(-0.0).build(),
        dev.cel.expr.conformance.proto2.TestAllTypes.newBuilder().setSingleDouble(0.0).build(),
        true),
    PROTO2_EXPLICIT_SIGNED_ZERO_FLOAT(
        dev.cel.expr.conformance.proto2.TestAllTypes.newBuilder().setSingleFloat(-0.0f).build(),
        dev.cel.expr.conformance.proto2.TestAllTypes.newBuilder().setSingleFloat(0.0f).build(),
        true),
    PROTO3_IMPLICIT_NEGATIVE_ZERO_VS_UNSET_DOUBLE(
        TestAllTypes.newBuilder().setSingleDouble(-0.0).build(),
        TestAllTypes.newBuilder().setSingleDouble(0.0).build(),
        false),
    PROTO3_IMPLICIT_NEGATIVE_ZERO_VS_UNSET_FLOAT(
        TestAllTypes.newBuilder().setSingleFloat(-0.0f).build(),
        TestAllTypes.newBuilder().setSingleFloat(0.0f).build(),
        false),
    ALL_SCALAR_KINDS_EQUAL(populatedProto2Scalars(-0.0), populatedProto2Scalars(0.0), true),
    SCALAR_INT32_DIFFERS(
        TestAllTypes.newBuilder().setSingleInt32(1).build(),
        TestAllTypes.newBuilder().setSingleInt32(2).build(),
        false),
    SCALAR_ENUM_DIFFERS(
        TestAllTypes.newBuilder().setStandaloneEnum(NestedEnum.BAR).build(),
        TestAllTypes.newBuilder().setStandaloneEnum(NestedEnum.BAZ).build(),
        false),
    PROTO2_EXPLICIT_DEFAULT_INT32_VS_UNSET(
        dev.cel.expr.conformance.proto2.TestAllTypes.newBuilder().setSingleInt32(-32).build(),
        dev.cel.expr.conformance.proto2.TestAllTypes.getDefaultInstance(),
        false),
    PROTO2_EXPLICIT_DEFAULT_DOUBLE_VS_UNSET(
        dev.cel.expr.conformance.proto2.TestAllTypes.newBuilder().setSingleDouble(6.4).build(),
        dev.cel.expr.conformance.proto2.TestAllTypes.getDefaultInstance(),
        false),
    PROTO3_OPTIONAL_BOOL_FALSE_VS_UNSET(
        TestAllTypes.newBuilder().setOptionalBool(false).build(),
        TestAllTypes.getDefaultInstance(),
        false),
    PROTO3_PACKED_REPEATED_SIGNED_ZERO_DOUBLES(
        TestAllTypes.newBuilder().addRepeatedDouble(-0.0).addRepeatedDouble(1.5).build(),
        TestAllTypes.newBuilder().addRepeatedDouble(0.0).addRepeatedDouble(1.5).build(),
        true),
    PROTO2_UNPACKED_REPEATED_SIGNED_ZERO_DOUBLES(
        dev.cel.expr.conformance.proto2.TestAllTypes.newBuilder()
            .addRepeatedDouble(-0.0)
            .addRepeatedDouble(1.5)
            .build(),
        dev.cel.expr.conformance.proto2.TestAllTypes.newBuilder()
            .addRepeatedDouble(0.0)
            .addRepeatedDouble(1.5)
            .build(),
        true),
    PROTO3_UNPACKED_WIRE_VS_PACKED_REPEATED_DOUBLE(
        parseProto3(out -> out.writeDouble(42, -0.0)),
        TestAllTypes.newBuilder().addRepeatedDouble(0.0).build(),
        true),
    REPEATED_ORDER_DIFFERS(
        TestAllTypes.newBuilder().addRepeatedInt32(1).addRepeatedInt32(2).build(),
        TestAllTypes.newBuilder().addRepeatedInt32(2).addRepeatedInt32(1).build(),
        false),
    REPEATED_SIZE_DIFFERS(
        TestAllTypes.newBuilder().addRepeatedDouble(1.0).build(),
        TestAllTypes.newBuilder().addRepeatedDouble(1.0).addRepeatedDouble(2.0).build(),
        false),
    MAP_REORDERED_ENTRIES_EQUAL(
        parseProto3(out -> {
          out.writeBytes(75, mapInt32StringEntry(1, "a"));
          out.writeBytes(75, mapInt32StringEntry(2, "b"));
        }),
        parseProto3(out -> {
          out.writeBytes(75, mapInt32StringEntry(2, "b"));
          out.writeBytes(75, mapInt32StringEntry(1, "a"));
        }),
        true),
    MAP_SIGNED_ZERO_DOUBLE_VALUES(
        TestAllTypes.newBuilder().putMapStringDouble("k", -0.0).build(),
        TestAllTypes.newBuilder().putMapStringDouble("k", 0.0).build(),
        true),
    MAP_SIGNED_ZERO_FLOAT_VALUES(
        TestAllTypes.newBuilder().putMapStringFloat("k", -0.0f).build(),
        TestAllTypes.newBuilder().putMapStringFloat("k", 0.0f).build(),
        true),
    MAP_KEY_DIFFERS(
        TestAllTypes.newBuilder().putMapStringDouble("k1", 0.0).build(),
        TestAllTypes.newBuilder().putMapStringDouble("k2", 0.0).build(),
        false),
    MAP_VALUE_DIFFERS(
        TestAllTypes.newBuilder().putMapStringInt32("k", 1).build(),
        TestAllTypes.newBuilder().putMapStringInt32("k", 2).build(),
        false),
    MAP_SIZE_DIFFERS(
        TestAllTypes.newBuilder().putMapStringInt32("k1", 1).build(),
        TestAllTypes.newBuilder().putMapStringInt32("k1", 1).putMapStringInt32("k2", 2).build(),
        false),
    NESTED_MESSAGE_SIGNED_ZERO_EQUAL(
        dev.cel.expr.conformance.proto2.NestedTestAllTypes.newBuilder()
            .setChild(
                dev.cel.expr.conformance.proto2.NestedTestAllTypes.newBuilder()
                    .setPayload(
                        dev.cel.expr.conformance.proto2.TestAllTypes.newBuilder()
                            .setSingleDouble(-0.0)))
            .build(),
        dev.cel.expr.conformance.proto2.NestedTestAllTypes.newBuilder()
            .setChild(
                dev.cel.expr.conformance.proto2.NestedTestAllTypes.newBuilder()
                    .setPayload(
                        dev.cel.expr.conformance.proto2.TestAllTypes.newBuilder()
                            .setSingleDouble(0.0)))
            .build(),
        true),
    NESTED_MESSAGE_PRESENT_VS_ABSENT(
        TestAllTypes.newBuilder()
            .setSingleNestedMessage(NestedMessage.getDefaultInstance())
            .build(),
        TestAllTypes.getDefaultInstance(),
        false),
    DOUBLE_VALUE_SIGNED_ZERO_UNEQUAL(DoubleValue.of(-0.0), DoubleValue.of(0.0), false),
    FLOAT_VALUE_EQUAL(FloatValue.of(1.5f), FloatValue.of(1.5f), true),
    FLOAT_VALUE_SIGNED_ZERO_UNEQUAL(FloatValue.of(-0.0f), FloatValue.of(0.0f), false),
    DOUBLE_VALUE_NAN_UNEQUAL(DoubleValue.of(Double.NaN), DoubleValue.of(Double.NaN), false),
    FLOAT_VALUE_NAN_UNEQUAL(FloatValue.of(Float.NaN), FloatValue.of(Float.NaN), false),
    DOUBLE_VALUE_DIFFERENT_UNKNOWN_FIELDS_UNEQUAL(
        parseDoubleValue(out -> {
          out.writeDouble(1, 1.5);
          out.writeInt32(999, 1);
        }),
        parseDoubleValue(out -> {
          out.writeDouble(1, 1.5);
          out.writeInt32(999, 2);
        }),
        false),
    VALUE_UNSET_VS_NULL_VALUE_UNEQUAL(
        Value.getDefaultInstance(),
        Value.newBuilder().setNullValue(NullValue.NULL_VALUE).build(),
        false),
    VALUE_ZERO_NUMBER_VS_UNSET(
        Value.newBuilder().setNumberValue(0.0).build(), Value.getDefaultInstance(), false),
    VALUE_STRING_VS_BOOL_UNEQUAL(
        Value.newBuilder().setStringValue("true").build(),
        Value.newBuilder().setBoolValue(true).build(),
        false),
    VALUE_STRING_DIFFERS(
        Value.newBuilder().setStringValue("a").build(),
        Value.newBuilder().setStringValue("b").build(),
        false),
    VALUE_SIGNED_ZERO_EQUAL(
        Value.newBuilder().setNumberValue(-0.0).build(),
        Value.newBuilder().setNumberValue(0.0).build(),
        true),
    VALUE_NAN_UNEQUAL(
        Value.newBuilder().setNumberValue(Double.NaN).build(),
        Value.newBuilder().setNumberValue(Double.NaN).build(),
        false),
    VALUE_STRUCT_SIGNED_ZERO_EQUAL(
        Value.newBuilder()
            .setStructValue(
                Struct.newBuilder().putFields("a", Value.newBuilder().setNumberValue(-0.0).build()))
            .build(),
        Value.newBuilder()
            .setStructValue(
                Struct.newBuilder().putFields("a", Value.newBuilder().setNumberValue(0.0).build()))
            .build(),
        true),
    VALUE_STRUCT_UNEQUAL(
        Value.newBuilder()
            .setStructValue(
                Struct.newBuilder().putFields("a", Value.newBuilder().setNumberValue(1.0).build()))
            .build(),
        Value.newBuilder()
            .setStructValue(
                Struct.newBuilder().putFields("a", Value.newBuilder().setNumberValue(2.0).build()))
            .build(),
        false),
    VALUE_LIST_UNEQUAL(
        Value.newBuilder()
            .setListValue(ListValue.newBuilder().addValues(Value.newBuilder().setNumberValue(1.0)))
            .build(),
        Value.newBuilder()
            .setListValue(ListValue.newBuilder().addValues(Value.newBuilder().setNumberValue(2.0)))
            .build(),
        false),
    STRUCT_SIGNED_ZERO_EQUAL(
        Struct.newBuilder().putFields("a", Value.newBuilder().setNumberValue(-0.0).build()).build(),
        Struct.newBuilder().putFields("a", Value.newBuilder().setNumberValue(0.0).build()).build(),
        true),
    STRUCT_NAN_UNEQUAL(
        Struct.newBuilder()
            .putFields("a", Value.newBuilder().setNumberValue(Double.NaN).build())
            .build(),
        Struct.newBuilder()
            .putFields("a", Value.newBuilder().setNumberValue(Double.NaN).build())
            .build(),
        false),
    LIST_VALUE_SIGNED_ZERO_EQUAL(
        ListValue.newBuilder().addValues(Value.newBuilder().setNumberValue(-0.0)).build(),
        ListValue.newBuilder().addValues(Value.newBuilder().setNumberValue(0.0)).build(),
        true),
    LIST_VALUE_NAN_UNEQUAL(
        ListValue.newBuilder().addValues(Value.newBuilder().setNumberValue(Double.NaN)).build(),
        ListValue.newBuilder().addValues(Value.newBuilder().setNumberValue(Double.NaN)).build(),
        false),
    SCALAR_VALUE_AT_NESTING_LIMIT_EQUAL(
        nestedListValue(/* listDepth= */ 50, /* leaf= */ -0.0),
        nestedListValue(/* listDepth= */ 50, /* leaf= */ 0.0),
        true),
    ANY_REENCODED_PAYLOAD_EQUAL(
        Any.pack(
            dev.cel.expr.conformance.proto2.TestAllTypes.newBuilder()
                .setSingleDouble(-0.0)
                .build()),
        Any.pack(
            dev.cel.expr.conformance.proto2.TestAllTypes.newBuilder().setSingleDouble(0.0).build()),
        true),
    ANY_EMPTY_URL_PREFIX_REENCODED_EQUAL(
        Any.newBuilder()
            .setTypeUrl("/cel.expr.conformance.proto2.TestAllTypes")
            .setValue(
                dev.cel.expr.conformance.proto2.TestAllTypes.newBuilder()
                    .setSingleDouble(-0.0)
                    .build()
                    .toByteString())
            .build(),
        Any.newBuilder()
            .setTypeUrl("/cel.expr.conformance.proto2.TestAllTypes")
            .setValue(
                dev.cel.expr.conformance.proto2.TestAllTypes.newBuilder()
                    .setSingleDouble(0.0)
                    .build()
                    .toByteString())
            .build(),
        true),
    ANY_NAN_PAYLOAD_UNEQUAL(
        Any.pack(TestAllTypes.newBuilder().setSingleDouble(Double.NaN).build()),
        Any.pack(TestAllTypes.newBuilder().setSingleDouble(Double.NaN).build()),
        false),
    ANY_DIFFERENT_PACKED_TYPES_UNEQUAL(
        Any.pack(Int32Value.of(1)), Any.pack(Int64Value.of(1)), false),
    UNKNOWN_FIELDS_EQUAL(
        parseProto3(out -> out.writeInt32(999, 42)),
        parseProto3(out -> out.writeInt32(999, 42)),
        true),
    UNKNOWN_FIELDS_DIFFERENT_VALUES(
        parseProto3(out -> out.writeInt32(999, 42)),
        parseProto3(out -> out.writeInt32(999, 43)),
        false),
    UNKNOWN_FIELDS_REORDERED_WITHIN_SAME_TAG(
        parseProto3(out -> {
          out.writeInt32(999, 1);
          out.writeInt32(999, 2);
        }),
        parseProto3(out -> {
          out.writeInt32(999, 2);
          out.writeInt32(999, 1);
        }),
        false),
    CLOSED_ENUM_UNKNOWN_SINGULAR_EQUAL(
        parseProto2(out -> out.writeEnum(24, 999)),
        parseProto2(out -> out.writeEnum(24, 999)),
        true),
    CLOSED_ENUM_UNKNOWN_SINGULAR_VS_UNSET(
        parseProto2(out -> out.writeEnum(24, 999)),
        dev.cel.expr.conformance.proto2.TestAllTypes.getDefaultInstance(),
        false),
    CLOSED_ENUM_UNKNOWN_REPEATED_REORDERED_UNEQUAL(
        parseProto2(out -> {
          out.writeEnum(52, 998);
          out.writeEnum(52, 999);
        }),
        parseProto2(out -> {
          out.writeEnum(52, 999);
          out.writeEnum(52, 998);
        }),
        false),
    LENGTH_DELIMITED_WIRE_ON_SINGULAR_DOUBLE_VS_EMPTY(
        parseProto3(out -> out.writeString(12, "x")), TestAllTypes.getDefaultInstance(), false),
    VARINT_WIRE_ON_REPEATED_DOUBLE_VS_EMPTY(
        parseProto3(out -> out.writeInt32(42, 7)), TestAllTypes.getDefaultInstance(), false),
    PROTO2_GROUP_EQUAL(
        dev.cel.expr.conformance.proto2.TestAllTypes.newBuilder()
            .setNestedGroup(NestedGroup.newBuilder().setSingleId(1).setSingleName("a"))
            .build(),
        dev.cel.expr.conformance.proto2.TestAllTypes.newBuilder()
            .setNestedGroup(NestedGroup.newBuilder().setSingleId(1).setSingleName("a"))
            .build(),
        true),
    PROTO2_GROUP_UNEQUAL(
        dev.cel.expr.conformance.proto2.TestAllTypes.newBuilder()
            .setNestedGroup(NestedGroup.newBuilder().setSingleId(1))
            .build(),
        dev.cel.expr.conformance.proto2.TestAllTypes.newBuilder()
            .setNestedGroup(NestedGroup.newBuilder().setSingleId(2))
            .build(),
        false),
    DIFFERENT_MESSAGE_CLASSES(
        dev.cel.expr.conformance.proto2.TestAllTypes.getDefaultInstance(),
        TestAllTypes.getDefaultInstance(),
        false),
    ;

    private final Message x;
    private final Message y;
    private final boolean expected;

    OracleTestCase(Message x, Message y, boolean expected) {
      this.x = x;
      this.y = y;
      this.expected = expected;
    }
  }
}
