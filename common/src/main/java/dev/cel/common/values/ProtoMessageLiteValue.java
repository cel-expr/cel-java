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

import com.google.auto.value.AutoValue;
import com.google.auto.value.extension.memoized.Memoized;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.errorprone.annotations.Immutable;
import com.google.protobuf.MessageLite;
import dev.cel.common.annotations.Internal;
import dev.cel.common.types.CelType;
import dev.cel.common.types.StructTypeReference;
import dev.cel.common.values.ProtoLiteCelValueConverter.MessageFields;
import java.io.IOException;
import java.util.Optional;

/**
 * ProtoMessageLiteValue is a struct value with protobuf support for {@link MessageLite}.
 * Specifically, it does not rely on full message descriptors, thus field selections can be
 * performed without the reliance of proto-reflection.
 *
 * <p>If the codebase has access to full protobuf messages with descriptors, use {@code
 * ProtoMessageValue} instead.
 */
@AutoValue
@Immutable
public abstract class ProtoMessageLiteValue extends StructValue<String, MessageLite> {

  @Override
  public abstract MessageLite value();

  @Override
  public abstract CelType celType();

  @Internal
  public abstract ProtoLiteCelValueConverter protoLiteCelValueConverter();

  @Memoized
  MessageFields messageFields() {
    try {
      return protoLiteCelValueConverter().readMessageFields(value(), celType().name());
    } catch (IOException e) {
      throw new IllegalStateException("Unable to read message fields for " + celType().name(), e);
    }
  }

  @Internal
  public ImmutableMap<String, Object> fieldValues() {
    return messageFields().values();
  }

  public ImmutableListMultimap<Integer, Object> unknownFields() {
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

  public static ProtoMessageLiteValue create(
      MessageLite value, String typeName, ProtoLiteCelValueConverter protoLiteCelValueConverter) {
    Preconditions.checkNotNull(value);
    Preconditions.checkNotNull(typeName);
    return new AutoValue_ProtoMessageLiteValue(
        value, StructTypeReference.create(typeName), protoLiteCelValueConverter);
  }
}
