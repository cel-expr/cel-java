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

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.auto.value.AutoValue;
import com.google.errorprone.annotations.Immutable;
import com.google.protobuf.ByteString;
import com.google.protobuf.CodedInputStream;
import dev.cel.common.exceptions.CelAttributeNotFoundException;
import dev.cel.common.types.CelType;
import dev.cel.common.types.StructTypeReference;
import java.io.IOException;
import java.util.Locale;
import java.util.Optional;

/**
 * RawProtoMessageLiteValue enables descriptorless evaluation of protobuf messages to address
 * client-server version skew issues where newer fields or submessages lack generated classes and
 * descriptors in the evaluation environment.
 *
 * <p>Rather than requiring compiled {@code MessageLite} classes or runtime schema descriptors, this
 * value encapsulates the raw wire-format {@link ByteString} payload and performs classless,
 * reflection-free field traversal directly over wire tags via {@link CodedInputStream}.
 */
@AutoValue
@Immutable
abstract class RawProtoMessageLiteValue extends StructValue<String, WireMessageLite>
    implements OptimizedSelectable, WireMessageLite {

  private static final String UNKNOWN_MESSAGE_TYPE_NAME = "cel.@unknownMessage";

  @Override
  public abstract ByteString toByteString();

  @Override
  public abstract CelType celType();

  abstract ProtoLiteCelValueConverter protoLiteCelValueConverter();

  @Override
  public String protoTypeName() {
    return celType().name();
  }

  @Override
  public WireMessageLite value() {
    return this;
  }

  @Override
  public final boolean equals(Object other) {
    // TODO: Support message equality
    throw new UnsupportedOperationException("Message equality is not supported");
  }

  @Override
  public final int hashCode() {
    throw new UnsupportedOperationException("Message equality is not supported");
  }

  @Override
  public final String toString() {
    return String.format(
        Locale.US,
        "WireMessageLite{protoTypeName=%s, size=%d}",
        protoTypeName(),
        toByteString().size());
  }

  @Override
  public boolean isZeroValue() {
    return toByteString().isEmpty();
  }

  /**
   * Direct field selection by name is unsupported on {@link RawProtoMessageLiteValue} because raw
   * wire bytes lack message descriptors, and field names are not preserved on the protobuf wire.
   */
  @Override
  public Object select(String field) {
    throw newUnoptimizedFieldResolutionException(field);
  }

  /**
   * Direct field presence testing by name is unsupported on {@link RawProtoMessageLiteValue}
   * because raw wire bytes lack message descriptors and field names.
   */
  @Override
  public Optional<Object> find(String field) {
    throw newUnoptimizedFieldResolutionException(field);
  }

  private CelAttributeNotFoundException newUnoptimizedFieldResolutionException(String field) {
    return CelAttributeNotFoundException.of(
        String.format(
            "Error resolving field '%s' on '%s'. Field selection by name is not supported on raw"
                + " proto wire bytes; register its CelLiteDescriptor or enable SelectOptimizer.",
            field, celType().name()));
  }

  @Override
  public Object selectByFieldNumber(SelectField field) {
    try {
      return protoLiteCelValueConverter().selectByFieldNumber(toByteString(), field);
    } catch (IOException e) {
      throw new IllegalArgumentException(
          "Failed to decode proto message of type: " + celType().name(), e);
    }
  }

  @Override
  public boolean hasFieldByNumber(SelectField field) {
    try {
      return protoLiteCelValueConverter().hasFieldByNumber(toByteString(), field);
    } catch (IOException e) {
      throw new IllegalArgumentException(
          "Failed to decode proto message of type: " + celType().name(), e);
    }
  }

  @Override
  public Optional<Object> findByFieldNumber(SelectField field) {
    try {
      return protoLiteCelValueConverter().findByFieldNumber(toByteString(), field);
    } catch (IOException e) {
      throw new IllegalArgumentException(
          "Failed to decode proto message of type: " + celType().name(), e);
    }
  }

  static RawProtoMessageLiteValue create(
      ByteString rawWireBytes, ProtoLiteCelValueConverter protoLiteCelValueConverter) {
    return create(rawWireBytes, "", protoLiteCelValueConverter);
  }

  static RawProtoMessageLiteValue create(
      ByteString rawWireBytes,
      String protoTypeName,
      ProtoLiteCelValueConverter protoLiteCelValueConverter) {
    checkNotNull(rawWireBytes);
    checkNotNull(protoTypeName);
    checkNotNull(protoLiteCelValueConverter);
    return new AutoValue_RawProtoMessageLiteValue(
        rawWireBytes,
        StructTypeReference.create(
            protoTypeName.isEmpty() ? UNKNOWN_MESSAGE_TYPE_NAME : protoTypeName),
        protoLiteCelValueConverter);
  }

  RawProtoMessageLiteValue() {}
}
