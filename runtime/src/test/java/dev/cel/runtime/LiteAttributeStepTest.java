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

package dev.cel.runtime;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.protobuf.ByteString;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.ExtensionRegistryLite;
import dev.cel.common.exceptions.CelAttributeNotFoundException;
import dev.cel.common.values.NullValue;
import dev.cel.common.values.OptionalValue;
import dev.cel.common.values.ProtoLiteCelValueConverter;
import dev.cel.common.values.ProtoMessageLiteValue;
import dev.cel.common.values.ProtoMessageLiteValueProvider;
import dev.cel.common.values.RawProtoMessageLiteValue;
import dev.cel.common.values.SelectableValue;
import dev.cel.expr.conformance.proto3.TestAllTypes;
import dev.cel.expr.conformance.proto3.TestAllTypesCelDescriptor;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.NoSuchElementException;
import java.util.Optional;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class LiteAttributeStepTest {

  private static final ProtoLiteCelValueConverter CONVERTER =
      (ProtoLiteCelValueConverter)
          ProtoMessageLiteValueProvider.newInstance(TestAllTypesCelDescriptor.getDescriptor())
              .protoCelValueConverter();

  private static final class TestSelectableValue implements SelectableValue<String> {
    private final ImmutableMap<String, Object> values;

    TestSelectableValue(ImmutableMap<String, Object> values) {
      this.values = values;
    }

    @Override
    public Object select(String field) {
      if (values.containsKey(field)) {
        return values.get(field);
      }
      throw new NoSuchElementException("Field not found: " + field);
    }

    @Override
    public Optional<Object> find(String field) {
      return Optional.ofNullable(values.get(field));
    }
  }

  private static ProtoMessageLiteValue createProtoMessageWithUnknowns(
      TestAllTypes knownMessage, byte[] unknownBytes) throws IOException {
    ByteArrayOutputStream combined = new ByteArrayOutputStream();
    knownMessage.writeTo(combined);
    combined.write(unknownBytes);
    TestAllTypes parsed =
        TestAllTypes.parseFrom(combined.toByteArray(), ExtensionRegistryLite.getEmptyRegistry());
    return ProtoMessageLiteValue.create(
        parsed, "cel.expr.conformance.proto3.TestAllTypes", CONVERTER);
  }

  @Test
  public void qualifyAttribute_nullTarget_returnsDefaultValue() {
    Object result =
        LiteAttributeStep.qualifyAttribute(
            null, ImmutableList.of(ImmutableList.of(1, "field", 9, "default_val")), CONVERTER);

    assertThat(result).isEqualTo("default_val");
  }

  @Test
  public void qualifyAttribute_nullValueTarget_returnsDefaultValue() {
    Object result =
        LiteAttributeStep.qualifyAttribute(
            NullValue.NULL_VALUE,
            ImmutableList.of(ImmutableList.of(1, "field", 9, "default_val")),
            CONVERTER);

    assertThat(result).isEqualTo("default_val");
  }

  @Test
  public void qualifyAttribute_nullDefaultValue_defaultsToNullValue() {
    Object result =
        LiteAttributeStep.qualifyAttribute(
            null, ImmutableList.of(Arrays.asList(1, "missing", 9, null)), CONVERTER);

    assertThat(result).isEqualTo(NullValue.NULL_VALUE);
  }

  @Test
  public void qualifyAttribute_emptyOptional_returnsEmptyOptional() {
    Object result =
        LiteAttributeStep.qualifyAttribute(
            OptionalValue.EMPTY,
            ImmutableList.of(ImmutableList.of(1, "field", 9, "default_val")),
            CONVERTER);

    assertThat(result).isEqualTo(Optional.empty());
  }

  @Test
  public void qualifyAttribute_optionalContainingNullValue_returnsDefaultValue() {
    Object result =
        LiteAttributeStep.qualifyAttribute(
            OptionalValue.create(NullValue.NULL_VALUE),
            ImmutableList.of(ImmutableList.of(1, "field", 9, "default_val")),
            CONVERTER);

    assertThat(result).isEqualTo("default_val");
  }

  @Test
  public void qualifyAttribute_optionalPresent_unwrapsAndQualifies() {
    Object result =
        LiteAttributeStep.qualifyAttribute(
            OptionalValue.create(ImmutableMap.of("field", "present_val")),
            ImmutableList.of(ImmutableList.of(1, "field", 9, "default_val")),
            CONVERTER);

    assertThat(result).isEqualTo("present_val");
  }

  @Test
  public void qualifyAttribute_protoMessageLite_knownFieldValue() {
    TestAllTypes proto = TestAllTypes.newBuilder().setSingleString("known_val").build();
    ProtoMessageLiteValue message =
        ProtoMessageLiteValue.create(proto, "cel.expr.conformance.proto3.TestAllTypes", CONVERTER);

    Object result =
        LiteAttributeStep.qualifyAttribute(
            message,
            ImmutableList.of(ImmutableList.of(14, "single_string", 9, "default_val")),
            CONVERTER);

    assertThat(result).isEqualTo("known_val");
  }

  @Test
  public void qualifyAttribute_protoMessageLite_unknownFieldValue() throws Exception {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    CodedOutputStream cos = CodedOutputStream.newInstance(baos);
    cos.writeString(999, "unknown_val");
    cos.flush();

    ProtoMessageLiteValue message =
        createProtoMessageWithUnknowns(TestAllTypes.getDefaultInstance(), baos.toByteArray());

    Object result =
        LiteAttributeStep.qualifyAttribute(
            message,
            ImmutableList.of(ImmutableList.of(999, "unknown_field", 9, "default_val")),
            CONVERTER);

    assertThat(result).isEqualTo("unknown_val");
  }

  @Test
  public void qualifyAttribute_protoMessageLite_missingFieldReturnsDefault() {
    ProtoMessageLiteValue message =
        ProtoMessageLiteValue.create(
            TestAllTypes.getDefaultInstance(),
            "cel.expr.conformance.proto3.TestAllTypes",
            CONVERTER);

    Object result =
        LiteAttributeStep.qualifyAttribute(
            message,
            ImmutableList.of(ImmutableList.of(9999, "missing_field", 9, "default_val")),
            CONVERTER);

    assertThat(result).isEqualTo("default_val");
  }

  @Test
  public void qualifyAttribute_rawProtoMessageLite_unknownFieldValue() throws Exception {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    CodedOutputStream cos = CodedOutputStream.newInstance(baos);
    cos.writeString(10, "raw_val");
    cos.flush();

    RawProtoMessageLiteValue rawMessage =
        RawProtoMessageLiteValue.create(ByteString.copyFrom(baos.toByteArray()));

    Object result =
        LiteAttributeStep.qualifyAttribute(
            rawMessage,
            ImmutableList.of(ImmutableList.of(10, "raw_field", 9, "default_val")),
            CONVERTER);

    assertThat(result).isEqualTo("raw_val");
  }

  @Test
  public void qualifyAttribute_rawProtoMessageLite_missingFieldReturnsDefault() {
    RawProtoMessageLiteValue rawMessage = RawProtoMessageLiteValue.create(ByteString.EMPTY);

    Object result =
        LiteAttributeStep.qualifyAttribute(
            rawMessage,
            ImmutableList.of(ImmutableList.of(99, "missing_field", 9, "default_val")),
            CONVERTER);

    assertThat(result).isEqualTo("default_val");
  }

  @Test
  public void qualifyAttribute_selectableValue_present() {
    TestSelectableValue selectable =
        new TestSelectableValue(ImmutableMap.of("field", "selectable_val"));

    Object result =
        LiteAttributeStep.qualifyAttribute(
            selectable,
            ImmutableList.of(ImmutableList.of(1, "field", 9, "default_val")),
            CONVERTER);

    assertThat(result).isEqualTo("selectable_val");
  }

  @Test
  public void qualifyAttribute_selectableValue_absentReturnsDefault() {
    TestSelectableValue selectable = new TestSelectableValue(ImmutableMap.of());

    Object result =
        LiteAttributeStep.qualifyAttribute(
            selectable,
            ImmutableList.of(ImmutableList.of(1, "missing", 9, "default_val")),
            CONVERTER);

    assertThat(result).isEqualTo("default_val");
  }

  @Test
  public void qualifyAttribute_map_present() {
    ImmutableMap<String, Object> map = ImmutableMap.of("key", "map_val");

    Object result =
        LiteAttributeStep.qualifyAttribute(
            map, ImmutableList.of(ImmutableList.of(1, "key", 9, "default_val")), CONVERTER);

    assertThat(result).isEqualTo("map_val");
  }

  @Test
  public void qualifyAttribute_map_nullValueReturnsNullValue() {
    ImmutableMap<String, Object> map = ImmutableMap.of("key", NullValue.NULL_VALUE);

    Object result =
        LiteAttributeStep.qualifyAttribute(
            map, ImmutableList.of(ImmutableList.of(1, "key", 9, "default_val")), CONVERTER);

    assertThat(result).isEqualTo(NullValue.NULL_VALUE);
  }

  @Test
  public void qualifyAttribute_map_missingKeyThrowsException() {
    ImmutableMap<String, Object> map = ImmutableMap.of();

    assertThrows(
        CelAttributeNotFoundException.class,
        () ->
            LiteAttributeStep.qualifyAttribute(
                map,
                ImmutableList.of(ImmutableList.of(1, "missing", 9, "default_val")),
                CONVERTER));
  }

  @Test
  public void qualifyAttribute_unsupportedTargetThrowsException() {
    assertThrows(
        CelAttributeNotFoundException.class,
        () ->
            LiteAttributeStep.qualifyAttribute(
                12345,
                ImmutableList.of(ImmutableList.of(1, "field", 9, "default_val")),
                CONVERTER));
  }

  @Test
  public void qualifyAttribute_invalidQualifierElementThrowsException() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            LiteAttributeStep.qualifyAttribute(
                ImmutableMap.of("field", "val"),
                ImmutableList.of("invalid_non_list_qualifier"),
                CONVERTER));
  }

  @Test
  public void qualifyAttribute_multiStepChaining() throws Exception {
    ByteArrayOutputStream subBaos = new ByteArrayOutputStream();
    CodedOutputStream subCos = CodedOutputStream.newInstance(subBaos);
    subCos.writeString(20, "nested_val");
    subCos.flush();
    ByteString subBytes = ByteString.copyFrom(subBaos.toByteArray());

    ByteArrayOutputStream rootBaos = new ByteArrayOutputStream();
    CodedOutputStream rootCos = CodedOutputStream.newInstance(rootBaos);
    rootCos.writeBytes(999, subBytes);
    rootCos.flush();

    ProtoMessageLiteValue rootMessage =
        createProtoMessageWithUnknowns(TestAllTypes.getDefaultInstance(), rootBaos.toByteArray());

    Object result =
        LiteAttributeStep.qualifyAttribute(
            rootMessage,
            ImmutableList.of(
                ImmutableList.of(999, "unknown_submessage", 11, NullValue.NULL_VALUE),
                ImmutableList.of(20, "nested_field", 9, "default")),
            CONVERTER);

    assertThat(result).isEqualTo("nested_val");
  }

  @Test
  public void hasField_nullTarget_returnsFalse() {
    boolean result =
        LiteAttributeStep.hasField(null, ImmutableList.of(ImmutableList.of(1, "field")), CONVERTER);

    assertThat(result).isFalse();
  }

  @Test
  public void hasField_nullValueTarget_returnsFalse() {
    boolean result =
        LiteAttributeStep.hasField(
            NullValue.NULL_VALUE, ImmutableList.of(ImmutableList.of(1, "field")), CONVERTER);

    assertThat(result).isFalse();
  }

  @Test
  public void hasField_emptyOptional_returnsFalse() {
    boolean result =
        LiteAttributeStep.hasField(
            OptionalValue.EMPTY, ImmutableList.of(ImmutableList.of(1, "field")), CONVERTER);

    assertThat(result).isFalse();
  }

  @Test
  public void hasField_optionalContainingNullValue_returnsFalse() {
    boolean result =
        LiteAttributeStep.hasField(
            OptionalValue.create(NullValue.NULL_VALUE),
            ImmutableList.of(ImmutableList.of(1, "field")),
            CONVERTER);

    assertThat(result).isFalse();
  }

  @Test
  public void hasField_optionalPresent_unwrapsAndTestsPresence() {
    boolean result =
        LiteAttributeStep.hasField(
            OptionalValue.create(ImmutableMap.of("field", "val")),
            ImmutableList.of(ImmutableList.of(1, "field")),
            CONVERTER);

    assertThat(result).isTrue();
  }

  @Test
  public void hasField_optionalContainingProtoWithUnknownField_returnsTrue() throws Exception {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    CodedOutputStream cos = CodedOutputStream.newInstance(baos);
    cos.writeInt64(999, 12345L);
    cos.flush();

    ProtoMessageLiteValue message =
        createProtoMessageWithUnknowns(TestAllTypes.getDefaultInstance(), baos.toByteArray());

    boolean result =
        LiteAttributeStep.hasField(
            OptionalValue.create(message),
            ImmutableList.of(ImmutableList.of(999, "unknown_field")),
            CONVERTER);

    assertThat(result).isTrue();
  }

  @Test
  public void hasField_optionalContainingRawProto_returnsTrue() throws Exception {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    CodedOutputStream cos = CodedOutputStream.newInstance(baos);
    cos.writeInt64(10, 42L);
    cos.flush();

    RawProtoMessageLiteValue rawMessage =
        RawProtoMessageLiteValue.create(ByteString.copyFrom(baos.toByteArray()));

    boolean result =
        LiteAttributeStep.hasField(
            OptionalValue.create(rawMessage),
            ImmutableList.of(ImmutableList.of(10, "raw_field")),
            CONVERTER);

    assertThat(result).isTrue();
  }

  @Test
  public void hasField_protoMessageLite_knownFieldReturnsTrue() {
    TestAllTypes proto = TestAllTypes.newBuilder().setSingleString("val").build();
    ProtoMessageLiteValue message =
        ProtoMessageLiteValue.create(proto, "cel.expr.conformance.proto3.TestAllTypes", CONVERTER);

    boolean result =
        LiteAttributeStep.hasField(
            message, ImmutableList.of(ImmutableList.of(14, "single_string")), CONVERTER);

    assertThat(result).isTrue();
  }

  @Test
  public void hasField_protoMessageLite_unknownFieldReturnsTrue() throws Exception {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    CodedOutputStream cos = CodedOutputStream.newInstance(baos);
    cos.writeInt64(999, 12345L);
    cos.flush();

    ProtoMessageLiteValue message =
        createProtoMessageWithUnknowns(TestAllTypes.getDefaultInstance(), baos.toByteArray());

    boolean result =
        LiteAttributeStep.hasField(
            message, ImmutableList.of(ImmutableList.of(999, "unknown_field")), CONVERTER);

    assertThat(result).isTrue();
  }

  @Test
  public void hasField_protoMessageLite_absentReturnsFalse() {
    ProtoMessageLiteValue message =
        ProtoMessageLiteValue.create(
            TestAllTypes.getDefaultInstance(),
            "cel.expr.conformance.proto3.TestAllTypes",
            CONVERTER);

    boolean result =
        LiteAttributeStep.hasField(
            message, ImmutableList.of(ImmutableList.of(9999, "missing_field")), CONVERTER);

    assertThat(result).isFalse();
  }

  @Test
  public void hasField_rawProtoMessageLite_unknownFieldReturnsTrue() throws Exception {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    CodedOutputStream cos = CodedOutputStream.newInstance(baos);
    cos.writeInt64(10, 42L);
    cos.flush();

    RawProtoMessageLiteValue rawMessage =
        RawProtoMessageLiteValue.create(ByteString.copyFrom(baos.toByteArray()));

    boolean result =
        LiteAttributeStep.hasField(
            rawMessage, ImmutableList.of(ImmutableList.of(10, "field")), CONVERTER);

    assertThat(result).isTrue();
  }

  @Test
  public void hasField_rawProtoMessageLite_absentReturnsFalse() {
    RawProtoMessageLiteValue rawMessage = RawProtoMessageLiteValue.create(ByteString.EMPTY);

    boolean result =
        LiteAttributeStep.hasField(
            rawMessage, ImmutableList.of(ImmutableList.of(99, "missing_field")), CONVERTER);

    assertThat(result).isFalse();
  }

  @Test
  public void hasField_selectableValue_presentReturnsTrue() {
    TestSelectableValue selectable = new TestSelectableValue(ImmutableMap.of("field", "val"));

    boolean result =
        LiteAttributeStep.hasField(
            selectable, ImmutableList.of(ImmutableList.of(1, "field")), CONVERTER);

    assertThat(result).isTrue();
  }

  @Test
  public void hasField_selectableValue_absentReturnsFalse() {
    TestSelectableValue selectable = new TestSelectableValue(ImmutableMap.of());

    boolean result =
        LiteAttributeStep.hasField(
            selectable, ImmutableList.of(ImmutableList.of(1, "field")), CONVERTER);

    assertThat(result).isFalse();
  }

  @Test
  public void hasField_map_presentReturnsTrue() {
    ImmutableMap<String, Object> map = ImmutableMap.of("key", "val");

    boolean result =
        LiteAttributeStep.hasField(map, ImmutableList.of(ImmutableList.of(1, "key")), CONVERTER);

    assertThat(result).isTrue();
  }

  @Test
  public void hasField_map_absentReturnsFalse() {
    ImmutableMap<String, Object> map = ImmutableMap.of();

    boolean result =
        LiteAttributeStep.hasField(
            map, ImmutableList.of(ImmutableList.of(1, "missing")), CONVERTER);

    assertThat(result).isFalse();
  }

  @Test
  public void hasField_unsupportedTarget_returnsFalse() {
    boolean result =
        LiteAttributeStep.hasField(
            "unsupported_string", ImmutableList.of(ImmutableList.of(1, "field")), CONVERTER);

    assertThat(result).isFalse();
  }

  @Test
  public void hasField_invalidQualifierThrowsException() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            LiteAttributeStep.hasField(
                ImmutableMap.of("field", "val"),
                ImmutableList.of("invalid_non_list_qualifier"),
                CONVERTER));
  }

  @Test
  public void hasField_emptyQualifiersReturnsFalse() {
    boolean result =
        LiteAttributeStep.hasField(ImmutableMap.of("field", "val"), ImmutableList.of(), CONVERTER);

    assertThat(result).isFalse();
  }

  @Test
  public void hasField_multiStepChaining_intermediateRawProto_present() throws Exception {
    ByteArrayOutputStream leafBaos = new ByteArrayOutputStream();
    CodedOutputStream leafCos = CodedOutputStream.newInstance(leafBaos);
    leafCos.writeInt64(20, 100L);
    leafCos.flush();
    ByteString leafBytes = ByteString.copyFrom(leafBaos.toByteArray());

    ByteArrayOutputStream childBaos = new ByteArrayOutputStream();
    CodedOutputStream childCos = CodedOutputStream.newInstance(childBaos);
    childCos.writeBytes(15, leafBytes);
    childCos.flush();
    ByteString childBytes = ByteString.copyFrom(childBaos.toByteArray());

    ByteArrayOutputStream parentBaos = new ByteArrayOutputStream();
    CodedOutputStream parentCos = CodedOutputStream.newInstance(parentBaos);
    parentCos.writeBytes(10, childBytes);
    parentCos.flush();

    RawProtoMessageLiteValue parent =
        RawProtoMessageLiteValue.create(ByteString.copyFrom(parentBaos.toByteArray()));

    boolean result =
        LiteAttributeStep.hasField(
            parent,
            ImmutableList.of(
                ImmutableList.of(10, "child"),
                ImmutableList.of(15, "leaf"),
                ImmutableList.of(20, "val_field")),
            CONVERTER);

    assertThat(result).isTrue();
  }

  @Test
  public void hasField_multiStepChaining_intermediateRawProto_absent() throws Exception {
    ByteArrayOutputStream childBaos = new ByteArrayOutputStream();
    CodedOutputStream childCos = CodedOutputStream.newInstance(childBaos);
    childCos.writeString(99, "other");
    childCos.flush();
    ByteString childBytes = ByteString.copyFrom(childBaos.toByteArray());

    ByteArrayOutputStream parentBaos = new ByteArrayOutputStream();
    CodedOutputStream parentCos = CodedOutputStream.newInstance(parentBaos);
    parentCos.writeBytes(10, childBytes);
    parentCos.flush();

    RawProtoMessageLiteValue parent =
        RawProtoMessageLiteValue.create(ByteString.copyFrom(parentBaos.toByteArray()));

    boolean result =
        LiteAttributeStep.hasField(
            parent,
            ImmutableList.of(
                ImmutableList.of(10, "child"),
                ImmutableList.of(15, "missing_leaf"),
                ImmutableList.of(20, "val_field")),
            CONVERTER);

    assertThat(result).isFalse();
  }

  @Test
  public void hasField_multiStepChaining_intermediateProtoMessageLite_absent() {
    ProtoMessageLiteValue rootMessage =
        ProtoMessageLiteValue.create(
            TestAllTypes.getDefaultInstance(),
            "cel.expr.conformance.proto3.TestAllTypes",
            CONVERTER);

    boolean result =
        LiteAttributeStep.hasField(
            rootMessage,
            ImmutableList.of(
                ImmutableList.of(9999, "missing_sub_message"), ImmutableList.of(20, "field")),
            CONVERTER);

    assertThat(result).isFalse();
  }

  @Test
  public void hasField_multiStepChaining_intermediateSelectableValue_present() {
    TestSelectableValue child = new TestSelectableValue(ImmutableMap.of("leaf", "val"));
    TestSelectableValue parent = new TestSelectableValue(ImmutableMap.of("child", child));

    boolean result =
        LiteAttributeStep.hasField(
            parent,
            ImmutableList.of(ImmutableList.of(1, "child"), ImmutableList.of(2, "leaf")),
            CONVERTER);

    assertThat(result).isTrue();
  }

  @Test
  public void hasField_multiStepChaining_intermediateSelectableValue_absent() {
    TestSelectableValue parent = new TestSelectableValue(ImmutableMap.of());

    boolean result =
        LiteAttributeStep.hasField(
            parent,
            ImmutableList.of(ImmutableList.of(1, "missing_child"), ImmutableList.of(2, "leaf")),
            CONVERTER);

    assertThat(result).isFalse();
  }

  @Test
  public void hasField_multiStepChaining_intermediateMap_present() {
    ImmutableMap<String, Object> child = ImmutableMap.of("leaf", "val");
    ImmutableMap<String, Object> parent = ImmutableMap.of("child", child);

    boolean result =
        LiteAttributeStep.hasField(
            parent,
            ImmutableList.of(ImmutableList.of(1, "child"), ImmutableList.of(2, "leaf")),
            CONVERTER);

    assertThat(result).isTrue();
  }

  @Test
  public void hasField_multiStepChaining_intermediateMap_absent() {
    ImmutableMap<String, Object> parent = ImmutableMap.of();

    boolean result =
        LiteAttributeStep.hasField(
            parent,
            ImmutableList.of(ImmutableList.of(1, "missing_child"), ImmutableList.of(2, "leaf")),
            CONVERTER);

    assertThat(result).isFalse();
  }
}
