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
import com.google.errorprone.annotations.Immutable;
import com.google.protobuf.ByteString;
import com.google.protobuf.MessageLite;
import dev.cel.common.exceptions.CelAttributeNotFoundException;
import dev.cel.common.types.CelType;
import dev.cel.common.types.StructTypeReference;
import dev.cel.protobuf.CelLiteDescriptor.FieldLiteDescriptor;
import java.io.IOException;
import java.util.Objects;
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
 * <p>An instance is backed by either a materialized {@link #rawValue()} or unparsed {@link
 * #wireBytes()} (exactly one is non-null). Wire-backed instances decode selected fields directly
 * from {@link #wireBytes()} and lazily parse the full {@link MessageLite} only if {@link #value()}
 * is invoked.
 *
 * <p>Implements {@link OptimizedSelectable} so that select chains can address fields by number:
 *
 * <ul>
 *   <li><b>Field renames:</b> If a protobuf field is renamed in schema after an AST was compiled,
 *       resolving by {@link SelectField#fieldNumber()} maps the number to the runtime descriptor's
 *       current field name, preventing {@code CelAttributeNotFoundException}.
 *   <li><b>Version skew / unknown fields:</b> When evaluating payloads serialized by a newer binary
 *       containing fields absent from the local {@code CelLiteDescriptor}, the unknown fields are
 *       decoded on demand directly from the message's wire bytes using the compile-time wire type
 *       and default metadata in {@link SelectField}.
 * </ul>
 */
@AutoValue
@Immutable
abstract class ProtoMessageLiteValue extends StructValue<String, MessageLite>
    implements OptimizedSelectable {

  // Populated when wrapping an already-materialized root MessageLite (e.g., from activation).
  abstract @Nullable MessageLite rawValue();

  // Populated when slicing a nested submessage from parent wire bytes to avoid deserializing and
  // re-serializing intermediate hops; lazily parsed into a MessageLite only if value() is called.
  abstract @Nullable ByteString wireBytes();

  @Override
  public abstract CelType celType();

  abstract ProtoLiteCelValueConverter protoLiteCelValueConverter();

  @Memoized
  @Override
  public MessageLite value() {
    MessageLite msg = rawValue();
    if (msg != null) {
      return msg;
    }
    return protoLiteCelValueConverter()
        .parseMessageLite(checkNotNull(wireBytes()), celType().name());
  }

  @Memoized
  ByteString serializedRawValue() {
    return checkNotNull(rawValue()).toByteString();
  }

  ByteString toByteString() {
    ByteString bytes = wireBytes();
    return bytes != null ? bytes : serializedRawValue();
  }

  @Override
  public boolean isZeroValue() {
    ByteString bytes = wireBytes();
    if (bytes != null && bytes.isEmpty()) {
      return true;
    }
    return value().getDefaultInstanceForType().equals(value());
  }

  @Override
  public final boolean equals(Object other) {
    if (other == this) {
      return true;
    }
    if (!(other instanceof ProtoMessageLiteValue)) {
      return false;
    }
    ProtoMessageLiteValue that = (ProtoMessageLiteValue) other;
    return this.celType().equals(that.celType()) && this.value().equals(that.value());
  }

  @Override
  public final int hashCode() {
    return Objects.hash(value(), celType());
  }

  @Override
  public Object select(String field) {
    Optional<FieldLiteDescriptor> fd = findFieldDescriptor(field);
    if (!fd.isPresent()) {
      throw CelAttributeNotFoundException.of(
          String.format("field '%s' is not declared in message '%s'", field, celType().name()));
    }
    Object fieldValue = readField(fd.get());
    return fieldValue != null
        ? fieldValue
        : protoLiteCelValueConverter().getDefaultCelValue(fd.get());
  }

  @Override
  public Optional<Object> find(String field) {
    Optional<FieldLiteDescriptor> fd = findFieldDescriptor(field);
    if (!fd.isPresent()) {
      // Per SelectableValue#find, a field that doesn't exist is reported as absent.
      return Optional.empty();
    }
    return Optional.ofNullable(readField(fd.get()));
  }

  @Override
  public Object selectByFieldNumber(SelectField field) {
    Optional<FieldLiteDescriptor> fd = findFieldDescriptor(field);
    if (fd.isPresent()) {
      Object known = readField(fd.get());
      if (known != null) {
        return known;
      }
      if (field.defaultValue() != null) {
        return field.defaultValue();
      }
      return protoLiteCelValueConverter().getDefaultCelValue(fd.get());
    }
    try {
      return protoLiteCelValueConverter().selectByFieldNumber(toByteString(), field);
    } catch (IOException e) {
      throw new IllegalArgumentException(
          "Failed to decode proto message of type: " + celType().name(), e);
    }
  }

  @Override
  public boolean hasFieldByNumber(SelectField field) {
    Optional<FieldLiteDescriptor> fd = findFieldDescriptor(field);
    if (fd.isPresent()) {
      return hasField(fd.get());
    }
    try {
      return protoLiteCelValueConverter().hasFieldByNumber(toByteString(), field);
    } catch (IOException e) {
      throw new IllegalArgumentException(
          "Failed to decode proto message of type: " + celType().name(), e);
    }
  }

  @Override
  public Optional<Object> findByFieldNumber(SelectField field) {
    Optional<FieldLiteDescriptor> fd = findFieldDescriptor(field);
    if (fd.isPresent()) {
      return Optional.ofNullable(readField(fd.get()));
    }
    try {
      return protoLiteCelValueConverter().findByFieldNumber(toByteString(), field);
    } catch (IOException e) {
      throw new IllegalArgumentException(
          "Failed to decode proto message of type: " + celType().name(), e);
    }
  }

  private @Nullable Object readField(FieldLiteDescriptor fd) {
    try {
      return protoLiteCelValueConverter().readSingleField(toByteString(), fd);
    } catch (IOException e) {
      throw new IllegalArgumentException(
          "Failed to decode proto message of type: " + celType().name(), e);
    }
  }

  private boolean hasField(FieldLiteDescriptor fd) {
    try {
      return protoLiteCelValueConverter().hasSingleField(toByteString(), fd);
    } catch (IOException e) {
      throw new IllegalArgumentException(
          "Failed to decode proto message of type: " + celType().name(), e);
    }
  }

  private Optional<FieldLiteDescriptor> findFieldDescriptor(SelectField field) {
    return protoLiteCelValueConverter().findFieldDescriptor(celType().name(), field.fieldNumber());
  }

  private Optional<FieldLiteDescriptor> findFieldDescriptor(String fieldName) {
    return protoLiteCelValueConverter().findFieldDescriptor(celType().name(), fieldName);
  }

  static ProtoMessageLiteValue create(
      MessageLite value, String typeName, ProtoLiteCelValueConverter protoLiteCelValueConverter) {
    checkNotNull(value);
    checkNotNull(typeName);
    checkNotNull(protoLiteCelValueConverter);
    return new AutoValue_ProtoMessageLiteValue(
        value,
        /* wireBytes= */ null,
        StructTypeReference.create(typeName),
        protoLiteCelValueConverter);
  }

  static ProtoMessageLiteValue create(
      ByteString wireBytes,
      String typeName,
      ProtoLiteCelValueConverter protoLiteCelValueConverter) {
    checkNotNull(wireBytes);
    checkNotNull(typeName);
    checkNotNull(protoLiteCelValueConverter);
    return new AutoValue_ProtoMessageLiteValue(
        /* rawValue= */ null,
        wireBytes,
        StructTypeReference.create(typeName),
        protoLiteCelValueConverter);
  }

  ProtoMessageLiteValue() {}
}
