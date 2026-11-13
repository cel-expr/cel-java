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

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableMap.toImmutableMap;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.primitives.UnsignedLong;
import com.google.errorprone.annotations.Immutable;
import com.google.protobuf.BoolValue;
import com.google.protobuf.ByteString;
import com.google.protobuf.BytesValue;
import com.google.protobuf.DoubleValue;
import com.google.protobuf.Duration;
import com.google.protobuf.FloatValue;
import com.google.protobuf.Int32Value;
import com.google.protobuf.Int64Value;
import com.google.protobuf.ListValue;
import com.google.protobuf.MessageLiteOrBuilder;
import com.google.protobuf.StringValue;
import com.google.protobuf.Struct;
import com.google.protobuf.Timestamp;
import com.google.protobuf.UInt32Value;
import com.google.protobuf.UInt64Value;
import com.google.protobuf.Value;
import dev.cel.common.annotations.Internal;
import dev.cel.common.internal.ProtoTimeUtils;
import dev.cel.common.internal.WellKnownProto;

/**
 * {@code BaseProtoCelValueConverter} contains the common logic for converting between native Java
 * and protobuf objects to {@link CelValue}. This base class is inherited by {@code
 * ProtoCelValueConverter} and {@code ProtoLiteCelValueConverter} to perform the conversion using
 * full and lite variants of protobuf messages respectively.
 *
 * <p>CEL Library Internals. Do Not Use.
 */
@Immutable
@Internal
public abstract class BaseProtoCelValueConverter extends CelValueConverter {

  /** {@inheritDoc} Protobuf semantics take precedence for conversion. */
  @Override
  public Object toRuntimeValue(Object value) {
    Preconditions.checkNotNull(value);

    if (value instanceof ByteString) {
      return CelByteString.of(((ByteString) value).toByteArray());
    } else if (value instanceof com.google.protobuf.NullValue) {
      return NullValue.NULL_VALUE;
    }

    return super.toRuntimeValue(value);
  }

  protected Object fromWellKnownProto(MessageLiteOrBuilder message, WellKnownProto wellKnownProto) {
    switch (wellKnownProto) {
      case JSON_VALUE:
        return adaptJsonValue((Value) message);
      case JSON_STRUCT_VALUE:
        return adaptJsonStruct((Struct) message);
      case JSON_LIST_VALUE:
        return adaptJsonList((ListValue) message);
      case DURATION:
        return ProtoTimeUtils.toJavaDuration((Duration) message);
      case TIMESTAMP:
        return ProtoTimeUtils.toJavaInstant((Timestamp) message);
      case BOOL_VALUE:
        return normalizePrimitive(((BoolValue) message).getValue());
      case BYTES_VALUE:
        return normalizePrimitive(((BytesValue) message).getValue().toByteArray());
      case DOUBLE_VALUE:
        return normalizePrimitive(((DoubleValue) message).getValue());
      case FLOAT_VALUE:
        return normalizePrimitive(((FloatValue) message).getValue());
      case INT32_VALUE:
        return normalizePrimitive(((Int32Value) message).getValue());
      case INT64_VALUE:
        return normalizePrimitive(((Int64Value) message).getValue());
      case STRING_VALUE:
        return normalizePrimitive(((StringValue) message).getValue());
      case UINT32_VALUE:
        return UnsignedLong.fromLongBits(
            Integer.toUnsignedLong(((UInt32Value) message).getValue()));
      case UINT64_VALUE:
        return UnsignedLong.fromLongBits(((UInt64Value) message).getValue());
      case EMPTY:
        return ImmutableMap.of();
      default:
        throw new UnsupportedOperationException(
            "Unsupported well known proto conversion - " + wellKnownProto);
    }
  }

  private Object adaptJsonValue(Value value) {
    switch (value.getKindCase()) {
      case BOOL_VALUE:
        return normalizePrimitive(value.getBoolValue());
      case NUMBER_VALUE:
        return normalizePrimitive(value.getNumberValue());
      case STRING_VALUE:
        return normalizePrimitive(value.getStringValue());
      case LIST_VALUE:
        return adaptJsonList(value.getListValue());
      case STRUCT_VALUE:
        return adaptJsonStruct(value.getStructValue());
      case NULL_VALUE:
      case KIND_NOT_SET: // Fall-through is intended
        return NullValue.NULL_VALUE;
    }
    throw new UnsupportedOperationException(
        "Unsupported Json to CelValue conversion: " + value.getKindCase());
  }

  private ImmutableList<Object> adaptJsonList(ListValue listValue) {
    return listValue.getValuesList().stream().map(this::adaptJsonValue).collect(toImmutableList());
  }

  private ImmutableMap<String, Object> adaptJsonStruct(Struct struct) {
    return struct.getFieldsMap().entrySet().stream()
        .collect(
            toImmutableMap(
                e -> {
                  Object key = toRuntimeValue(e.getKey());
                  if (!(key instanceof String)) {
                    throw new IllegalStateException(
                        "Expected a string type for the key, but instead got: " + key);
                  }
                  return (String) key;
                },
                e -> adaptJsonValue(e.getValue())));
  }

  protected BaseProtoCelValueConverter() {}
}
