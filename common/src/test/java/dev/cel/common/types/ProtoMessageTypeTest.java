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

package dev.cel.common.types;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import java.util.Optional;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(TestParameterInjector.class)
public final class ProtoMessageTypeTest {

  private static final ImmutableMap<String, CelType> FIELD_MAP =
      ImmutableMap.of(
          "bool_value", NullableType.create(SimpleType.BOOL),
          "int_value", NullableType.create(SimpleType.INT),
          "string_value", NullableType.create(SimpleType.STRING),
          "map_value", MapType.create(SimpleType.UINT, TypeType.create(SimpleType.DYN)));

  private static final ImmutableMap<String, CelType> EXTENSION_MAP =
      ImmutableMap.of("my.package.int_extension", SimpleType.INT);

  private ProtoMessageType testMessage;

  @Before
  public void setUp() {
    testMessage =
        ProtoMessageType.create(
            "my.package.TestMessage",
            FIELD_MAP.keySet(),
            (field) -> Optional.ofNullable(FIELD_MAP.get(field)),
            (extension) -> Optional.ofNullable(EXTENSION_MAP.get(extension)),
            (unused) -> false);
  }

  @Test
  public void findField() {
    for (String fieldName : FIELD_MAP.keySet()) {
      assertThat(testMessage.findField(fieldName))
          .hasValue(StructType.Field.of(fieldName, FIELD_MAP.get(fieldName)));
    }
  }

  @Test
  public void withVisibleFields() {
    ProtoMessageType maskedMessage = testMessage.withVisibleFields(ImmutableSet.of("bool_value"));
    assertThat(maskedMessage.findField("int_value")).isEmpty();
    assertThat(maskedMessage.findField("bool_value")).isPresent();
    assertThat(maskedMessage.fields()).hasSize(1);
    assertThat(testMessage.fields()).hasSize(4);
  }

  @Test
  public void findExtension() {
    for (String extName : EXTENSION_MAP.keySet()) {
      assertThat(testMessage.findExtension(extName))
          .hasValue(
              ProtoMessageType.Extension.of(extName, EXTENSION_MAP.get(extName), testMessage));
    }
  }

  @Test
  public void createWithUnenumerableFields_findField_delegatesToResolver(
      @TestParameter({"bool_value", "int_value", "string_value", "map_value"}) String fieldName) {
    ProtoMessageType unenumerableMessage = newUnenumerableTestMessage();

    assertThat(unenumerableMessage.findField(fieldName))
        .hasValue(StructType.Field.of(fieldName, FIELD_MAP.get(fieldName)));
  }

  @Test
  public void createWithUnenumerableFields_findField_undefinedField_returnsEmpty() {
    ProtoMessageType unenumerableMessage = newUnenumerableTestMessage();

    assertThat(unenumerableMessage.findField("undefined_field")).isEmpty();
  }

  @Test
  public void createWithUnenumerableFields_findExtension_delegatesToResolver() {
    ProtoMessageType unenumerableMessage = newUnenumerableTestMessage();

    assertThat(
            unenumerableMessage
                .findExtension("my.package.int_extension")
                .map(ProtoMessageType.Extension::type))
        .hasValue(SimpleType.INT);
  }

  @Test
  public void createWithUnenumerableFields_findExtension_undefinedExtension_returnsEmpty() {
    ProtoMessageType unenumerableMessage = newUnenumerableTestMessage();

    assertThat(unenumerableMessage.findExtension("my.package.undefined_extension")).isEmpty();
  }

  @Test
  public void createWithUnenumerableFields_fieldNames_throws() {
    ProtoMessageType unenumerableMessage = newUnenumerableTestMessage();

    assertThrows(IllegalStateException.class, unenumerableMessage::fieldNames);
  }

  @Test
  public void createWithUnenumerableFields_fields_throws() {
    ProtoMessageType unenumerableMessage = newUnenumerableTestMessage();

    assertThrows(IllegalStateException.class, unenumerableMessage::fields);
  }

  @Test
  public void createWithUnenumerableFields_withVisibleFields_becomesEnumerable() {
    ProtoMessageType maskedMessage =
        newUnenumerableTestMessage().withVisibleFields(ImmutableSet.of("bool_value"));

    assertThat(maskedMessage.fieldNames()).containsExactly("bool_value");
    assertThat(maskedMessage.findField("int_value")).isEmpty();
  }

  @Test
  public void createWithUnenumerableFields_withJsonNameResolver_resolvesJsonName() {
    ProtoMessageType message =
        ProtoMessageType.createWithUnenumerableFields(
            "my.package.TestMessage",
            (field) -> Optional.ofNullable(FIELD_MAP.get(field)),
            (extension) -> Optional.ofNullable(EXTENSION_MAP.get(extension)),
            "boolValue"::equals);

    assertThat(message.isJsonName("boolValue")).isTrue();
    assertThat(message.isJsonName("bool_value")).isFalse();
  }

  @Test
  public void createWithUnenumerableFields_defaultJsonNameResolver_returnsFalse() {
    ProtoMessageType message = newUnenumerableTestMessage();

    assertThat(message.isJsonName("boolValue")).isFalse();
    assertThat(message.isJsonName("bool_value")).isFalse();
  }

  private static ProtoMessageType newUnenumerableTestMessage() {
    return ProtoMessageType.createWithUnenumerableFields(
        "my.package.TestMessage",
        (field) -> Optional.ofNullable(FIELD_MAP.get(field)),
        (extension) -> Optional.ofNullable(EXTENSION_MAP.get(extension)));
  }
}
