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
import dev.cel.protobuf.CelLiteDescriptor;
import dev.cel.protobuf.CelLiteDescriptor.FieldLiteDescriptor;
import dev.cel.protobuf.CelLiteDescriptor.MessageLiteDescriptor;
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

  abstract MessageLiteDescriptor descriptor();

  abstract ProtoLiteCelValueConverter protoLiteCelValueConverter();

  // The last submessage read through a getter, so that select chains sharing this message don't
  // convert (or, without a descriptor, serialize) it again: a ProtoMessageLiteValue wrapping it, or
  // a SerializedSubmessage. Like this wrapper, it lasts one evaluation, as values leave it through
  // value(). Entries are reached through final fields and checked by identity, so a race at most
  // repeats a conversion.
  @SuppressWarnings("Immutable")
  private @Nullable Object lastSubmessage;

  @Nullable Object lastSubmessage() {
    return lastSubmessage;
  }

  void setLastSubmessage(Object lastSubmessage) {
    this.lastSubmessage = lastSubmessage;
  }

  @Override
  public CelType celType() {
    // Built on demand, since one of these values is created on every hop but rarely asked its type.
    return StructTypeReference.create(descriptor().getProtoTypeName());
  }

  @Memoized
  @Override
  public MessageLite value() {
    MessageLite msg = rawValue();
    if (msg != null) {
      return msg;
    }
    return ProtoLiteCelValueConverter.parseMessageLite(checkNotNull(wireBytes()), descriptor());
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
    return this.descriptor().getProtoTypeName().equals(that.descriptor().getProtoTypeName())
        && this.value().equals(that.value());
  }

  @Override
  public final int hashCode() {
    return Objects.hash(value(), descriptor().getProtoTypeName());
  }

  @Override
  public Object select(String field) {
    FieldLiteDescriptor fd = descriptor().findByFieldName(field).orElse(null);
    if (fd == null) {
      throw CelAttributeNotFoundException.of(
          String.format(
              "field '%s' is not declared in message '%s'",
              field, descriptor().getProtoTypeName()));
    }
    Object fieldValue = readField(fd);
    return fieldValue != null ? fieldValue : protoLiteCelValueConverter().getDefaultCelValue(fd);
  }

  @Override
  public Optional<Object> find(String field) {
    // Per SelectableValue#find, a field that doesn't exist is reported as absent.
    return descriptor().findByFieldName(field).map(this::readField);
  }

  // An in-memory message is read through its descriptor's generated getters, falling back to its
  // wire bytes (which serializes it) only for fields without one, such as those unknown to its
  // descriptor. Other messages are read from their wire bytes.

  @Override
  public Object selectByFieldNumber(SelectField field) {
    FieldLiteDescriptor fd = descriptor().findByFieldNumber(field.fieldNumber()).orElse(null);
    if (fd == null) {
      return protoLiteCelValueConverter()
          .selectByFieldNumber(toByteString(), descriptor().getProtoTypeName(), field);
    }
    Object fieldValue = readField(fd);
    if (fieldValue != null) {
      return fieldValue;
    }
    return field.defaultValue() != null
        ? field.defaultValue()
        : protoLiteCelValueConverter().getDefaultCelValue(fd);
  }

  @Override
  @SuppressWarnings("ReferenceEquality") // NO_GETTER is a sentinel instance.
  public boolean hasFieldByNumber(SelectField field) {
    Object present = protoLiteCelValueConverter().hasByGetter(this, field);
    return present != CelLiteDescriptor.NO_GETTER
        ? (Boolean) present
        : protoLiteCelValueConverter()
            .hasFieldByNumber(toByteString(), descriptor().getProtoTypeName(), field);
  }

  @Override
  public Optional<Object> findByFieldNumber(SelectField field) {
    FieldLiteDescriptor fd = descriptor().findByFieldNumber(field.fieldNumber()).orElse(null);
    return fd != null
        ? Optional.ofNullable(readField(fd))
        : protoLiteCelValueConverter()
            .findByFieldNumber(toByteString(), descriptor().getProtoTypeName(), field);
  }

  @SuppressWarnings("ReferenceEquality") // NO_GETTER is a sentinel instance.
  private @Nullable Object readField(FieldLiteDescriptor fd) {
    Object value = protoLiteCelValueConverter().readFieldByGetter(this, fd);
    return value != CelLiteDescriptor.NO_GETTER
        ? value
        : protoLiteCelValueConverter()
            .readField(toByteString(), descriptor().getProtoTypeName(), fd);
  }

  static ProtoMessageLiteValue create(
      MessageLite value,
      MessageLiteDescriptor descriptor,
      ProtoLiteCelValueConverter protoLiteCelValueConverter) {
    checkNotNull(value);
    checkNotNull(descriptor);
    checkNotNull(protoLiteCelValueConverter);
    return new AutoValue_ProtoMessageLiteValue(
        value, /* wireBytes= */ null, descriptor, protoLiteCelValueConverter);
  }

  static ProtoMessageLiteValue create(
      ByteString wireBytes,
      MessageLiteDescriptor descriptor,
      ProtoLiteCelValueConverter protoLiteCelValueConverter) {
    checkNotNull(wireBytes);
    checkNotNull(descriptor);
    checkNotNull(protoLiteCelValueConverter);
    return new AutoValue_ProtoMessageLiteValue(
        /* rawValue= */ null, wireBytes, descriptor, protoLiteCelValueConverter);
  }

  ProtoMessageLiteValue() {}
}
