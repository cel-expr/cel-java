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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static dev.cel.common.values.ProtoLiteCelValueConverter.MAX_ANY_NESTING_DEPTH;

import com.google.common.collect.ImmutableListMultimap;
import com.google.errorprone.annotations.Immutable;
import com.google.protobuf.Any;
import com.google.protobuf.ByteString;
import com.google.protobuf.CodedInputStream;
import com.google.protobuf.DoubleValue;
import com.google.protobuf.ExtensionRegistryLite;
import com.google.protobuf.FloatValue;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.ListValue;
import com.google.protobuf.MessageLite;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import com.google.protobuf.WireFormat;
import dev.cel.common.internal.CelLiteDescriptorPool;
import dev.cel.protobuf.CelLiteDescriptor.FieldLiteDescriptor;
import dev.cel.protobuf.CelLiteDescriptor.FieldLiteDescriptor.EncodingType;
import dev.cel.protobuf.CelLiteDescriptor.MessageLiteDescriptor;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** CEL protobuf message equality for {@link MessageLite} instances. */
@Immutable
final class ProtoLiteEquality {

  private final CelLiteDescriptorPool descriptorPool;

  boolean equals(MessageLite x, MessageLite y) {
    return equals(x, y, /* depth= */ 0);
  }

  private boolean equals(MessageLite x, MessageLite y, int depth) {
    if (x.getClass() != y.getClass()) {
      return false;
    }
    // Each Any payload is parsed afresh, outside protobuf's own recursion limit.
    checkArgument(
        depth <= MAX_ANY_NESTING_DEPTH, "Message nesting exceeds %s", MAX_ANY_NESTING_DEPTH);
    MessageLiteDescriptor descriptor = descriptorPool.findDescriptor(x).orElse(null);
    if (descriptor != null && descriptor.hasGetters()) {
      for (FieldLiteDescriptor field : descriptor.getFieldDescriptors()) {
        int number = field.getFieldNumber();
        if (isSemantic(field)
            && !valueEquals(
                descriptor.getFieldValue(x, number), descriptor.getFieldValue(y, number), depth)) {
          return false;
        }
      }
    } else if (x instanceof Any) {
      MessageLite unpackedX = unpack((Any) x);
      MessageLite unpackedY = unpack((Any) y);
      if (unpackedX != null && unpackedY != null) {
        return valueEquals(unpackedX, unpackedY, depth);
      }
    } else if (x instanceof Value) {
      Value vx = (Value) x;
      Value vy = (Value) y;
      if (vx.getKindCase() != vy.getKindCase()
          || !valueEquals(semanticValue(vx), semanticValue(vy), depth)) {
        return false;
      }
    } else if (x instanceof Struct) {
      if (!valueEquals(((Struct) x).getFieldsMap(), ((Struct) y).getFieldsMap(), depth)) {
        return false;
      }
    } else if (x instanceof ListValue) {
      if (!valueEquals(((ListValue) x).getValuesList(), ((ListValue) y).getValuesList(), depth)) {
        return false;
      }
    } else {
      // A wrapper's -0.0 is set and its 0.0 isn't, so of its doubles only NaN differs from bytes.
      if (isNan(x)) {
        return false;
      }
      // None of this message's fields was compared above, so all of them are compared by bytes.
      descriptor = null;
    }
    // NOMUTANTS -- Messages equal in protobuf's own sense have equal bytes; this saves serializing.
    return x.equals(y)
        || rawFields(x.toByteString(), descriptor).equals(rawFields(y.toByteString(), descriptor));
  }

  private boolean valueEquals(@Nullable Object x, @Nullable Object y, int depth) {
    if (x == null || y == null) {
      return x == null && y == null;
    }
    if (x instanceof MessageLite) {
      return equals((MessageLite) x, (MessageLite) y, depth + 1);
    }
    if (x instanceof Float || x instanceof Double) {
      return ((Number) x).doubleValue() == ((Number) y).doubleValue();
    }
    if (x instanceof List) {
      List<?> xList = (List<?>) x;
      List<?> yList = (List<?>) y;
      if (xList.size() != yList.size()) {
        return false;
      }
      for (int i = 0; i < xList.size(); i++) {
        if (!valueEquals(xList.get(i), yList.get(i), depth)) {
          return false;
        }
      }
      return true;
    }
    if (x instanceof Map) {
      Map<?, ?> xMap = (Map<?, ?>) x;
      Map<?, ?> yMap = (Map<?, ?>) y;
      if (xMap.size() != yMap.size()) {
        return false;
      }
      for (Map.Entry<?, ?> entry : xMap.entrySet()) {
        if (!valueEquals(entry.getValue(), yMap.get(entry.getKey()), depth)) {
          return false;
        }
      }
      return true;
    }
    return x.equals(y);
  }

  private static @Nullable Object semanticValue(Value v) {
    switch (v.getKindCase()) {
      case NUMBER_VALUE:
        return v.getNumberValue();
      case STRUCT_VALUE:
        return v.getStructValue();
      case LIST_VALUE:
        return v.getListValue();
      default:
        return null;
    }
  }

  private @Nullable MessageLite unpack(Any any) {
    String typeUrl = any.getTypeUrl();
    int slash = typeUrl.lastIndexOf('/');
    MessageLiteDescriptor descriptor =
        slash < 0 ? null : descriptorPool.findDescriptor(typeUrl.substring(slash + 1)).orElse(null);
    if (descriptor == null || descriptor.isMapEntry()) {
      return null;
    }
    try {
      // Partial, as in C++: a missing required field is no reason not to compare the rest.
      return descriptor
          .newMessageBuilder()
          .mergeFrom(any.getValue(), ExtensionRegistryLite.getEmptyRegistry())
          .buildPartial();
    } catch (InvalidProtocolBufferException e) {
      return null;
    }
  }

  private static boolean isNan(MessageLite message) {
    return (message instanceof DoubleValue && Double.isNaN(((DoubleValue) message).getValue()))
        || (message instanceof FloatValue && Float.isNaN(((FloatValue) message).getValue()));
  }

  private static boolean isSemantic(FieldLiteDescriptor field) {
    FieldLiteDescriptor.Type type = field.getProtoFieldType();
    // Not getJavaType(), which is MESSAGE for groups too, and their getters throw.
    return type == FieldLiteDescriptor.Type.FLOAT
        || type == FieldLiteDescriptor.Type.DOUBLE
        || type == FieldLiteDescriptor.Type.MESSAGE;
  }

  private static boolean isSemantic(MessageLiteDescriptor descriptor, int tag) {
    FieldLiteDescriptor field = descriptor.findByFieldNumber(WireFormat.getTagFieldNumber(tag));
    if (field == null || !isSemantic(field)) {
      return false;
    }
    int wireType = WireFormat.getTagWireType(tag);
    // The parser keeps other wire types as unknown fields, and reads repeated floats and doubles
    // whether packed or not.
    return wireType == field.getProtoFieldType().toWireFormatFieldType().getWireType()
        || (wireType == WireFormat.WIRETYPE_LENGTH_DELIMITED
            && field.getEncodingType() == EncodingType.LIST);
  }

  /** Groups a serialized message's fields by tag, less {@code descriptor}'s semantic ones. */
  static ImmutableListMultimap<Integer, ByteString> rawFields(
      ByteString bytes, @Nullable MessageLiteDescriptor descriptor) {
    ImmutableListMultimap.Builder<Integer, ByteString> fields = ImmutableListMultimap.builder();
    CodedInputStream input = bytes.newCodedInput();
    try {
      for (int tag = input.readTag(); tag != 0; tag = input.readTag()) {
        int start = input.getTotalBytesRead();
        input.skipField(tag);
        if (descriptor == null || !isSemantic(descriptor, tag)) {
          fields.put(tag, bytes.substring(start, input.getTotalBytesRead()));
        }
      }
    } catch (IOException e) {
      throw new IllegalArgumentException("Failed to decode proto message", e);
    }
    return fields.build();
  }

  ProtoLiteEquality(CelLiteDescriptorPool descriptorPool) {
    this.descriptorPool = checkNotNull(descriptorPool);
  }
}
