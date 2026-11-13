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

package dev.cel.common.internal;

import static com.google.common.collect.ImmutableMap.toImmutableMap;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.primitives.Ints;
import com.google.common.primitives.UnsignedInts;
import com.google.common.primitives.UnsignedLong;
import com.google.errorprone.annotations.Immutable;
import com.google.protobuf.Any;
import com.google.protobuf.BoolValue;
import com.google.protobuf.ByteString;
import com.google.protobuf.BytesValue;
import com.google.protobuf.DoubleValue;
import com.google.protobuf.Duration;
import com.google.protobuf.FloatValue;
import com.google.protobuf.Int32Value;
import com.google.protobuf.Int64Value;
import com.google.protobuf.ListValue;
import com.google.protobuf.Message;
import com.google.protobuf.MessageLite;
import com.google.protobuf.MessageLiteOrBuilder;
import com.google.protobuf.NullValue;
import com.google.protobuf.StringValue;
import com.google.protobuf.Struct;
import com.google.protobuf.Timestamp;
import com.google.protobuf.UInt32Value;
import com.google.protobuf.UInt64Value;
import com.google.protobuf.Value;
import dev.cel.common.CelOptions;
import dev.cel.common.CelProtoJsonAdapter;
import dev.cel.common.annotations.Internal;
import dev.cel.common.exceptions.CelNumericOverflowException;
import dev.cel.common.values.CelByteString;
import java.time.Instant;
import java.util.Map;
import java.util.Map.Entry;

/**
 * {@code ProtoLiteAdapter} utilities handle conversion between native Java objects which represent
 * CEL values and well-known protobuf counterparts.
 *
 * <p>This adapter does not leverage descriptors, thus is compatible with lite-variants of protobuf
 * messages.
 *
 * <p>CEL Library Internals. Do Not Use.
 */
@Internal
@Immutable
public final class ProtoLiteAdapter {

  private final CelOptions celOptions;

  @SuppressWarnings("unchecked")
  public MessageLite adaptValueToWellKnownProto(Object value, WellKnownProto wellKnownProto) {
    switch (wellKnownProto) {
      case JSON_VALUE:
        return CelProtoJsonAdapter.adaptValueToJsonValue(value);
      case JSON_STRUCT_VALUE:
        return CelProtoJsonAdapter.adaptToJsonStructValue((Map<String, Object>) value);
      case JSON_LIST_VALUE:
        return CelProtoJsonAdapter.adaptToJsonListValue((Iterable<Object>) value);
      case BOOL_VALUE:
        return BoolValue.of((Boolean) value);
      case BYTES_VALUE:
        CelByteString byteString = (CelByteString) value;
        return BytesValue.of(ByteString.copyFrom(byteString.toByteArray()));
      case DOUBLE_VALUE:
        return adaptValueToDouble(value);
      case FLOAT_VALUE:
        return adaptValueToFloat(value);
      case INT32_VALUE:
        return adaptValueToInt32(value);
      case INT64_VALUE:
        return adaptValueToInt64(value);
      case STRING_VALUE:
        return StringValue.of((String) value);
      case UINT32_VALUE:
        return adaptValueToUint32(value);
      case UINT64_VALUE:
        return adaptValueToUint64(value);
      case DURATION:
        return adaptValueToProtoDuration(value);
      case TIMESTAMP:
        return adaptValueToProtoTimestamp(value);
      case EMPTY:
      case FIELD_MASK:
        // These two WKTs are typically used in context of JSON conversions, in which they are
        // automatically unwrapped into equivalent primitive types.
        // In other cases, just return the original message itself.
        return (MessageLite) value;
      default:
        throw new IllegalArgumentException(
            "Unexpected wellKnownProto kind: " + wellKnownProto + " for value: " + value);
    }
  }

  public Any adaptValueToAny(Object value, String typeName) {
    if (value instanceof MessageLite) {
      return packAnyMessage((MessageLite) value, typeName);
    }

    if (value instanceof dev.cel.common.values.NullValue) {
      return packAnyMessage(
          Value.newBuilder().setNullValue(NullValue.NULL_VALUE).build(), WellKnownProto.JSON_VALUE);
    }

    WellKnownProto wellKnownProto;

    if (value instanceof Boolean) {
      wellKnownProto = WellKnownProto.BOOL_VALUE;
    } else if (value instanceof CelByteString) {
      wellKnownProto = WellKnownProto.BYTES_VALUE;
    } else if (value instanceof String) {
      wellKnownProto = WellKnownProto.STRING_VALUE;
    } else if (value instanceof Float) {
      wellKnownProto = WellKnownProto.FLOAT_VALUE;
    } else if (value instanceof Double) {
      wellKnownProto = WellKnownProto.DOUBLE_VALUE;
    } else if (value instanceof Long) {
      wellKnownProto = WellKnownProto.INT64_VALUE;
    } else if (value instanceof UnsignedLong) {
      wellKnownProto = WellKnownProto.UINT64_VALUE;
    } else if (value instanceof Iterable) {
      wellKnownProto = WellKnownProto.JSON_LIST_VALUE;
    } else if (value instanceof Map) {
      wellKnownProto = WellKnownProto.JSON_STRUCT_VALUE;
    } else if (value instanceof Instant) {
      wellKnownProto = WellKnownProto.TIMESTAMP;
    } else if (value instanceof java.time.Duration) {
      wellKnownProto = WellKnownProto.DURATION;
    } else {
      throw new IllegalArgumentException("Unsupported value conversion to any: " + value);
    }

    MessageLite wellKnownProtoMsg = adaptValueToWellKnownProto(value, wellKnownProto);
    return packAnyMessage(wellKnownProtoMsg, wellKnownProto);
  }

  public Object adaptWellKnownProtoToValue(
      MessageLiteOrBuilder proto, WellKnownProto wellKnownProto) {
    // Exhaustive switch over the conversion and adaptation of well-known protobuf types to Java
    // values.
    switch (wellKnownProto) {
      case JSON_VALUE:
        return adaptJsonToValue((Value) proto);
      case JSON_STRUCT_VALUE:
        return adaptJsonStructToValue((Struct) proto);
      case JSON_LIST_VALUE:
        return adaptJsonListToValue((ListValue) proto);
      case BOOL_VALUE:
        return ((BoolValue) proto).getValue();
      case BYTES_VALUE:
        ByteString byteString = ((BytesValue) proto).getValue();
        return CelByteString.of(byteString.toByteArray());
      case DOUBLE_VALUE:
        return ((DoubleValue) proto).getValue();
      case FLOAT_VALUE:
        return (double) ((FloatValue) proto).getValue();
      case INT32_VALUE:
        return (long) ((Int32Value) proto).getValue();
      case INT64_VALUE:
        return ((Int64Value) proto).getValue();
      case STRING_VALUE:
        return ((StringValue) proto).getValue();
      case UINT32_VALUE:
        if (celOptions.enableUnsignedLongs()) {
          return UnsignedLong.fromLongBits(
              Integer.toUnsignedLong(((UInt32Value) proto).getValue()));
        }
        return (long) ((UInt32Value) proto).getValue();
      case UINT64_VALUE:
        if (celOptions.enableUnsignedLongs()) {
          return UnsignedLong.fromLongBits(((UInt64Value) proto).getValue());
        }
        return ((UInt64Value) proto).getValue();
      case TIMESTAMP:
        if (celOptions.evaluateCanonicalTypesToNativeValues()) {
          return ProtoTimeUtils.toJavaInstant((Timestamp) proto);
        }
        return proto;
      case DURATION:
        if (celOptions.evaluateCanonicalTypesToNativeValues()) {
          return ProtoTimeUtils.toJavaDuration((Duration) proto);
        }
        return proto;
      default:
        return proto;
    }
  }

  private Object adaptJsonToValue(Value value) {
    switch (value.getKindCase()) {
      case BOOL_VALUE:
        return value.getBoolValue();
      case NULL_VALUE:
      case KIND_NOT_SET:
        return dev.cel.common.values.NullValue.NULL_VALUE;
      case NUMBER_VALUE:
        return value.getNumberValue();
      case STRING_VALUE:
        return value.getStringValue();
      case LIST_VALUE:
        return adaptJsonListToValue(value.getListValue());
      case STRUCT_VALUE:
        return adaptJsonStructToValue(value.getStructValue());
    }
    throw new IllegalArgumentException("unexpected value kind: " + value.getKindCase());
  }

  private ImmutableList<Object> adaptJsonListToValue(ListValue listValue) {
    return listValue.getValuesList().stream()
        .map(this::adaptJsonToValue)
        .collect(ImmutableList.<Object>toImmutableList());
  }

  private ImmutableMap<String, Object> adaptJsonStructToValue(Struct struct) {
    return struct.getFieldsMap().entrySet().stream()
        .collect(toImmutableMap(Entry::getKey, e -> adaptJsonToValue(e.getValue())));
  }

  private Message adaptValueToDouble(Object value) {
    if (value instanceof Double) {
      return DoubleValue.of((Double) value);
    }
    if (value instanceof Float) {
      return DoubleValue.of(((Float) value).doubleValue());
    }
    throw new IllegalArgumentException("Unexpected value type: " + value);
  }

  private Message adaptValueToFloat(Object value) {
    if (value instanceof Double) {
      return FloatValue.of(((Double) value).floatValue());
    }
    if (value instanceof Float) {
      return FloatValue.of((Float) value);
    }
    throw new IllegalArgumentException("Unexpected value type: " + value);
  }

  private Message adaptValueToInt32(Object value) {
    if (value instanceof Integer) {
      return Int32Value.of((Integer) value);
    }
    if (value instanceof Long) {
      return Int32Value.of(intCheckedCast((Long) value));
    }
    throw new IllegalArgumentException("Unexpected value type: " + value);
  }

  private Message adaptValueToInt64(Object value) {
    if (value instanceof Integer) {
      return Int64Value.of(((Integer) value).longValue());
    }
    if (value instanceof Long) {
      return Int64Value.of((Long) value);
    }

    throw new IllegalArgumentException("Unexpected value type: " + value);
  }

  private Message adaptValueToUint32(Object value) {
    if (value instanceof Integer) {
      return UInt32Value.of((Integer) value);
    }
    // Per the spec, an int isn't a uint, unless unsigned longs are disabled.
    if (!celOptions.enableUnsignedLongs() && value instanceof Long) {
      try {
        return UInt32Value.of(unsignedIntCheckedCast((Long) value));
      } catch (IllegalArgumentException e) {
        throw new CelNumericOverflowException(e);
      }
    }
    if (value instanceof UnsignedLong) {
      try {
        return UInt32Value.of(unsignedIntCheckedCast(((UnsignedLong) value).longValue()));
      } catch (IllegalArgumentException e) {
        throw new CelNumericOverflowException(e);
      }
    }

    throw new IllegalArgumentException("Unexpected value type: " + value);
  }

  private Message adaptValueToUint64(Object value) {
    if (value instanceof Integer) {
      return UInt64Value.of(UnsignedInts.toLong((Integer) value));
    }
    if (!celOptions.enableUnsignedLongs() && value instanceof Long) {
      return UInt64Value.of((Long) value);
    }
    if (value instanceof UnsignedLong) {
      return UInt64Value.of(((UnsignedLong) value).longValue());
    }

    throw new IllegalArgumentException("Unexpected value type: " + value);
  }

  private static int intCheckedCast(long value) {
    try {
      return Ints.checkedCast(value);
    } catch (IllegalArgumentException e) {
      throw new CelNumericOverflowException(e);
    }
  }

  private static int unsignedIntCheckedCast(long value) {
    try {
      return UnsignedInts.checkedCast(value);
    } catch (IllegalArgumentException e) {
      throw new CelNumericOverflowException(e);
    }
  }

  private static Any packAnyMessage(MessageLite msg, WellKnownProto wellKnownProto) {
    return packAnyMessage(msg, wellKnownProto.typeName());
  }

  private static Any packAnyMessage(MessageLite msg, String typeUrl) {
    return Any.newBuilder()
        .setValue(msg.toByteString())
        .setTypeUrl("type.googleapis.com/" + typeUrl)
        .build();
  }

  private Timestamp adaptValueToProtoTimestamp(Object value) {
    if (!celOptions.evaluateCanonicalTypesToNativeValues()) {
      return (Timestamp) value;
    }

    return ProtoTimeUtils.toProtoTimestamp((Instant) value);
  }

  private Duration adaptValueToProtoDuration(Object value) {
    if (!celOptions.evaluateCanonicalTypesToNativeValues()) {
      return (Duration) value;
    }

    return ProtoTimeUtils.toProtoDuration((java.time.Duration) value);
  }

  public ProtoLiteAdapter(CelOptions celOptions) {
    this.celOptions = celOptions;
  }
}
