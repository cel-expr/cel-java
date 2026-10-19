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
import com.google.common.collect.ImmutableList;
import com.google.errorprone.annotations.Immutable;
import com.google.protobuf.ByteString;
import com.google.protobuf.CodedInputStream;
import dev.cel.common.exceptions.CelAttributeNotFoundException;
import dev.cel.common.types.CelType;
import dev.cel.common.types.StructTypeReference;
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
  public abstract String protoTypeName();

  abstract ProtoLiteCelValueConverter protoLiteCelValueConverter();

  // Set when toByteString() may alias a parent message's buffer.
  abstract boolean isView();

  @Override
  public CelType celType() {
    // Built on demand, since one of these values is created on every hop but rarely asked its type.
    return StructTypeReference.create(protoTypeName());
  }

  @Override
  public WireMessageLite value() {
    // A view escaping evaluation would pin the parent's whole buffer, so it leaves as a copy.
    return isView()
        ? create(
            ByteString.copyFrom(toByteString().asReadOnlyByteBuffer()),
            protoTypeName(),
            protoLiteCelValueConverter())
        : this;
  }

  @Override
  public final boolean equals(Object other) {
    if (other instanceof RawProtoMessageLiteValue) {
      RawProtoMessageLiteValue that = (RawProtoMessageLiteValue) other;
      return protoTypeName().equals(that.protoTypeName())
          && this.toByteString().equals(that.toByteString());
    }
    return false;
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
            field, protoTypeName()));
  }

  @Override
  public Object selectByFieldNumber(SelectField field) {
    return protoLiteCelValueConverter().selectByFieldNumber(toByteString(), protoTypeName(), field);
  }

  @Override
  public boolean hasFieldByNumber(SelectField field) {
    return protoLiteCelValueConverter().hasFieldByNumber(toByteString(), protoTypeName(), field);
  }

  @Override
  public Optional<Object> findByFieldNumber(SelectField field) {
    return protoLiteCelValueConverter().findByFieldNumber(toByteString(), protoTypeName(), field);
  }

  @Override
  public Object selectPath(ImmutableList<SelectField> fields, int from) {
    // Wire bytes have no submessages in memory to walk. Consecutive hops through wire bytes are
    // selected here, rather than each going back through the traversal.
    Object current = this;
    int i = from;
    while (i < fields.size() - 1 && current instanceof RawProtoMessageLiteValue) {
      current = ((RawProtoMessageLiteValue) current).selectByFieldNumber(fields.get(i));
      i++;
    }
    return OptimizedSelectTraversal.qualify(current, fields, i);
  }

  static RawProtoMessageLiteValue create(
      ByteString rawWireBytes, ProtoLiteCelValueConverter protoLiteCelValueConverter) {
    return create(rawWireBytes, "", protoLiteCelValueConverter);
  }

  static RawProtoMessageLiteValue create(
      ByteString rawWireBytes,
      String protoTypeName,
      ProtoLiteCelValueConverter protoLiteCelValueConverter) {
    return create(rawWireBytes, protoTypeName, protoLiteCelValueConverter, /* isView= */ false);
  }

  static RawProtoMessageLiteValue create(
      ByteString rawWireBytes,
      String protoTypeName,
      ProtoLiteCelValueConverter protoLiteCelValueConverter,
      boolean isView) {
    checkNotNull(rawWireBytes);
    checkNotNull(protoTypeName);
    checkNotNull(protoLiteCelValueConverter);
    return new AutoValue_RawProtoMessageLiteValue(
        rawWireBytes,
        protoTypeName.isEmpty() ? UNKNOWN_MESSAGE_TYPE_NAME : protoTypeName,
        protoLiteCelValueConverter,
        isView);
  }

  RawProtoMessageLiteValue() {}
}
