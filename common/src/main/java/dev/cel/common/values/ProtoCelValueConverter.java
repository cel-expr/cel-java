// Copyright 2023 Google LLC
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

import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.google.common.primitives.UnsignedLong;
import com.google.errorprone.annotations.Immutable;
import com.google.protobuf.Any;
import com.google.protobuf.Descriptors.EnumValueDescriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.MapEntry;
import com.google.protobuf.Message;
import com.google.protobuf.MessageLiteOrBuilder;
import com.google.protobuf.MessageOrBuilder;
import dev.cel.common.CelOptions;
import dev.cel.common.annotations.Internal;
import dev.cel.common.internal.CelDescriptorPool;
import dev.cel.common.internal.DynamicProto;
import dev.cel.common.internal.WellKnownProto;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code ProtoCelValueConverter} handles bidirectional conversion between native Java and protobuf
 * objects to {@link CelValue}. This converter leverages descriptors, thus requires the full version
 * of protobuf implementation.
 *
 * <p>Protobuf semantics take precedence for conversion. For example, CEL's TimestampValue will be
 * converted into Protobuf's Timestamp instead of java.time.Instant.
 *
 * <p>CEL Library Internals. Do Not Use.
 */
@Immutable
@Internal
public final class ProtoCelValueConverter extends BaseProtoCelValueConverter {
  private final CelDescriptorPool celDescriptorPool;
  private final DynamicProto dynamicProto;
  private final CelOptions celOptions;

  /** Constructs a new instance of ProtoCelValueConverter. */
  public static ProtoCelValueConverter newInstance(
      CelDescriptorPool celDescriptorPool, DynamicProto dynamicProto, CelOptions celOptions) {
    return new ProtoCelValueConverter(celDescriptorPool, dynamicProto, celOptions);
  }

  @Override
  protected Object fromWellKnownProto(MessageLiteOrBuilder msg, WellKnownProto wellKnownProto) {
    MessageOrBuilder message = (MessageOrBuilder) msg;
    switch (wellKnownProto) {
      case ANY_VALUE:
        Message unpackedMessage;
        try {
          unpackedMessage = dynamicProto.unpack((Any) message);
        } catch (InvalidProtocolBufferException e) {
          throw new IllegalArgumentException(
              "Unpacking failed for message: " + message.getDescriptorForType().getFullName(), e);
        }
        return toRuntimeValue(unpackedMessage);
      case FIELD_MASK:
        return ProtoMessageValue.create(
            (Message) message, celDescriptorPool, this, celOptions.enableJsonFieldNames());
      default:
        return super.fromWellKnownProto(message, wellKnownProto);
    }
  }

  @Override
  public Object toRuntimeValue(Object value) {
    if (value instanceof EnumValueDescriptor) {
      // (b/178627883) Strongly typed enum is not supported yet
      return Long.valueOf(((EnumValueDescriptor) value).getNumber());
    }

    if (value instanceof MessageOrBuilder) {
      Message message;
      if (value instanceof Message.Builder) {
        message = ((Message.Builder) value).build();
      } else {
        message = (Message) value;
      }

      // Attempt to convert the proto from a dynamic message into a concrete message if possible.
      if (message instanceof DynamicMessage) {
        message = dynamicProto.maybeAdaptDynamicMessage((DynamicMessage) message);
      }

      WellKnownProto wellKnownProto =
          WellKnownProto.getByTypeName(message.getDescriptorForType().getFullName()).orElse(null);
      if (wellKnownProto == null) {
        return ProtoMessageValue.create(
            message, celDescriptorPool, this, celOptions.enableJsonFieldNames());
      }

      return fromWellKnownProto(message, wellKnownProto);
    }

    return super.toRuntimeValue(value);
  }

  /** Adapts the protobuf message field. */
  @SuppressWarnings({"unchecked", "rawtypes"})
  public Object fromProtoMessageFieldToCelValue(Message message, FieldDescriptor fieldDescriptor) {
    Preconditions.checkNotNull(message);
    Preconditions.checkNotNull(fieldDescriptor);

    Object result = message.getField(fieldDescriptor);
    switch (fieldDescriptor.getType()) {
      case MESSAGE:
        if (WellKnownProto.isWrapperType(fieldDescriptor.getMessageType().getFullName())
            && !fieldDescriptor.isRepeated()
            && !message.hasField(fieldDescriptor)) {
          // Special semantics for wrapper types per CEL specification. These all convert into null
          // instead of the default value.
          return NullValue.NULL_VALUE;
        }

        if (fieldDescriptor.isMapField()) {
          FieldDescriptor keyFieldDescriptor =
              fieldDescriptor.getMessageType().findFieldByNumber(1);
          FieldDescriptor valueFieldDescriptor =
              fieldDescriptor.getMessageType().findFieldByNumber(2);
          Map<Object, Object> map = new HashMap<>();
          Object mapKey;
          Object mapValue;
          for (Object entry : ((List<Object>) result)) {
            if (entry instanceof MapEntry) {
              MapEntry mapEntry = (MapEntry) entry;
              mapKey = mapEntry.getKey();
              mapValue = mapEntry.getValue();
            } else if (entry instanceof DynamicMessage) {
              DynamicMessage dynamicMessage = (DynamicMessage) entry;
              mapKey = dynamicMessage.getField(keyFieldDescriptor);
              mapValue = dynamicMessage.getField(valueFieldDescriptor);
            } else {
              throw new IllegalStateException("Unexpected map field type: " + entry);
            }

            map.put(
                toUnsigned(keyFieldDescriptor, mapKey), toUnsigned(valueFieldDescriptor, mapValue));
          }
          return toRuntimeValue(map);
        }

        return toRuntimeValue(result);
      case UINT32:
      case FIXED32:
      case UINT64:
      case FIXED64:
        // Converts elements as they're read, so selecting one doesn't copy the whole list.
        return fieldDescriptor.isRepeated()
            ? CelPreAdaptedList.wrap(
                Lists.transform((List<?>) result, element -> toUnsigned(fieldDescriptor, element)))
            : toUnsigned(fieldDescriptor, result);
      default:
        break;
    }

    if (fieldDescriptor.isRepeated()) {
      switch (fieldDescriptor.getType()) {
        case INT64:
        case BOOL:
        case STRING:
        case DOUBLE:
          return CelPreAdaptedList.wrap((List<?>) result);
        default:
          break;
      }
    }

    return toRuntimeValue(result);
  }

  // Protobuf returns uint32 and uint64 values as signed ints and longs.
  private static Object toUnsigned(FieldDescriptor fieldDescriptor, Object value) {
    switch (fieldDescriptor.getType()) {
      case UINT32:
      case FIXED32:
        return UnsignedLong.fromLongBits(Integer.toUnsignedLong((Integer) value));
      case UINT64:
      case FIXED64:
        return UnsignedLong.fromLongBits((Long) value);
      default:
        return value;
    }
  }

  private ProtoCelValueConverter(
      CelDescriptorPool celDescriptorPool, DynamicProto dynamicProto, CelOptions celOptions) {
    Preconditions.checkNotNull(celDescriptorPool);
    Preconditions.checkNotNull(dynamicProto);
    Preconditions.checkNotNull(celOptions);
    this.celDescriptorPool = celDescriptorPool;
    this.dynamicProto = dynamicProto;
    this.celOptions = celOptions;
  }
}
