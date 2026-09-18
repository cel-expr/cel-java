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

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.auto.value.AutoValue;
import com.google.auto.value.extension.memoized.Memoized;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.errorprone.annotations.Immutable;
import com.google.protobuf.MessageLite;
import dev.cel.common.types.CelType;
import dev.cel.common.types.StructTypeReference;
import dev.cel.common.values.ProtoLiteCelValueConverter.MessageFields;
import dev.cel.protobuf.CelLiteDescriptor.FieldLiteDescriptor;
import java.io.IOException;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * ProtoMessageLiteValue is a struct value with protobuf support for {@link MessageLite}.
 * Specifically, it does not rely on full message descriptors, thus field selections can be
 * performed without the reliance of proto-reflection.
 *
 * <p>If the codebase has access to full protobuf messages with descriptors, use {@code
 * ProtoMessageValue} instead.
 *
 * <p>Implements {@link OptimizedSelectable} so that select chains can address fields by number:
 *
 * <ul>
 *   <li><b>Field renames:</b> If a protobuf field is renamed in schema after an AST was compiled,
 *       resolving by {@link SelectField#fieldNumber()} maps the number to the runtime descriptor's
 *       current field name, preventing {@code CelAttributeNotFoundException}.
 *   <li><b>Version skew / unknown fields:</b> When evaluating payloads serialized by a newer binary
 *       containing fields absent from the local {@code CelLiteDescriptor}, the unknown wire bytes
 *       are preserved in {@link #unknownFields()} and decoded on demand using the compile-time wire
 *       type and default metadata in {@link SelectField}.
 * </ul>
 */
@AutoValue
@Immutable
public abstract class ProtoMessageLiteValue extends StructValue<String, MessageLite>
    implements OptimizedSelectable {

  @Override
  public abstract MessageLite value();

  @Override
  public abstract CelType celType();

  abstract ProtoLiteCelValueConverter protoLiteCelValueConverter();

  @Memoized
  MessageFields messageFields() {
    try {
      return protoLiteCelValueConverter().readMessageFields(value(), celType().name());
    } catch (IOException e) {
      throw new IllegalStateException("Unable to read message fields for " + celType().name(), e);
    }
  }

  ImmutableMap<String, Object> fieldValues() {
    return messageFields().values();
  }

  ImmutableListMultimap<Integer, Object> unknownFields() {
    return messageFields().unknowns();
  }

  @Override
  public boolean isZeroValue() {
    return value().getDefaultInstanceForType().equals(value());
  }

  @Override
  public Object select(String field) {
    return find(field)
        .orElseGet(() -> protoLiteCelValueConverter().getDefaultCelValue(celType().name(), field));
  }

  @Override
  public Optional<Object> find(String field) {
    Object fieldValue = fieldValues().get(field);
    return Optional.ofNullable(fieldValue)
        .map(value -> protoLiteCelValueConverter().toRuntimeValue(fieldValue));
  }

  @Override
  public Object selectByFieldNumber(SelectField field) {
    FieldLiteDescriptor fd = findFieldDescriptor(field);
    Object known = findKnownFieldValue(fd);
    if (known != null) {
      return protoLiteCelValueConverter().toRuntimeValue(known);
    }
    return RawProtoMessageLiteValue.selectWireOrDefault(
        field, fd, unknownFields().get(field.fieldNumber()), protoLiteCelValueConverter());
  }

  @Override
  public boolean hasFieldByNumber(SelectField field) {
    FieldLiteDescriptor fd = findFieldDescriptor(field);
    if (findKnownFieldValue(fd) != null) {
      return true;
    }
    return RawProtoMessageLiteValue.isPresentInWire(
        field, fd, unknownFields().get(field.fieldNumber()));
  }

  @Override
  public Optional<Object> findByFieldNumber(SelectField field) {
    FieldLiteDescriptor fd = findFieldDescriptor(field);
    Object known = findKnownFieldValue(fd);
    if (known != null) {
      return Optional.of(protoLiteCelValueConverter().toRuntimeValue(known));
    }
    return RawProtoMessageLiteValue.navigateWire(
        field, fd, unknownFields().get(field.fieldNumber()), protoLiteCelValueConverter());
  }

  private @Nullable FieldLiteDescriptor findFieldDescriptor(SelectField field) {
    return protoLiteCelValueConverter()
        .findFieldDescriptor(celType().name(), field.fieldNumber())
        .orElse(null);
  }

  private @Nullable Object findKnownFieldValue(@Nullable FieldLiteDescriptor fieldDescriptor) {
    if (fieldDescriptor == null) {
      return null;
    }
    return fieldValues().get(fieldDescriptor.getFieldName());
  }

  public static ProtoMessageLiteValue create(
      MessageLite value, String typeName, ProtoLiteCelValueConverter protoLiteCelValueConverter) {
    checkNotNull(value);
    checkNotNull(typeName);
    checkNotNull(protoLiteCelValueConverter);
    return new AutoValue_ProtoMessageLiteValue(
        value, StructTypeReference.create(typeName), protoLiteCelValueConverter);
  }

  ProtoMessageLiteValue() {}
}
