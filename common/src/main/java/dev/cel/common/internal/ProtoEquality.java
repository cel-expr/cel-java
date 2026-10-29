// Copyright 2022 Google LLC
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

import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.errorprone.annotations.CheckReturnValue;
import com.google.errorprone.annotations.Immutable;
import com.google.protobuf.Any;
import com.google.protobuf.ByteString;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Message;
import dev.cel.common.annotations.Internal;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The {@code ProtoEquality} implementation is an alternative to the {@link Message#equals}
 * implementation that is consistent with the C++ {@code MessageDifferencer::Equals} definition.
 *
 * <p>These are the key differences between CEL's proto equality and Java's proto equality:
 *
 * <ul>
 *   <li>NaN is not equal to itself.
 *   <li>Positive and negative zero (+0.0 and -0.0) compare as equal when both fields are present
 *       (under proto3 implicit presence, -0.0 is serialized as present while +0.0 is unset).
 *   <li>Any values are unpacked before comparison.
 *   <li>If two Any values cannot be unpacked, they are compared by bytes.
 * </ul>
 *
 * <p>CEL Library Internals. Do Not Use.
 */
@CheckReturnValue
@Immutable
@Internal
public final class ProtoEquality {

  private static final String ANY_FULL_NAME = Any.getDescriptor().getFullName();

  private final DynamicProto dynamicProto;

  public ProtoEquality(DynamicProto dynamicProto) {
    this.dynamicProto = dynamicProto;
  }

  public boolean equals(Message message1, Message message2) {
    if (!message1.getDescriptorForType().equals(message2.getDescriptorForType())) {
      return false;
    }
    // both messages must share the same descriptor, so if one is an Any, then both are.
    if (isAny(message1)) {
      // Convert from Any. The value here could be either Any or DynamicMessage
      // Test whether the typeUrl values are the same, if not return false.
      // Use the dynamicProto.unpack(message1), dynamicProto.unpack(message2)
      // and assign the results to message1 and message2.
      Optional<Message> unpackedAny1 = dynamicProto.maybeUnpackAny(message1);
      Optional<Message> unpackedAny2 = dynamicProto.maybeUnpackAny(message2);
      if (unpackedAny1.isPresent() && unpackedAny2.isPresent()) {
        return equals(unpackedAny1.get(), unpackedAny2.get());
      }
      return anyValue(message1).equals(anyValue(message2));
    }
    if (!message1.getUnknownFields().equals(message2.getUnknownFields())) {
      return false;
    }
    Map<FieldDescriptor, Object> message1Fields = message1.getAllFields();
    Map<FieldDescriptor, Object> message2Fields = message2.getAllFields();
    if (message1Fields.size() != message2Fields.size()) {
      return false;
    }
    // No need to do a generic for-each, can use an iterator more efficiently.
    for (Map.Entry<FieldDescriptor, Object> entry : message1Fields.entrySet()) {
      FieldDescriptor field = entry.getKey();
      Object value1 = entry.getValue();
      Object value2 = message2Fields.get(entry.getKey());
      if (value2 == null) {
        return false;
      }
      if (field.isMapField()) {
        List<?> mapEntries1 = (List<?>) value1;
        List<?> mapEntries2 = (List<?>) value2;
        if (mapEntries1.size() != mapEntries2.size()) {
          return false;
        }
        ProtoMap protoMap1 = protoMap(mapEntries1);
        ProtoMap protoMap2 = protoMap(mapEntries2);
        if (!protoMap1.valueField().equals(protoMap2.valueField())) {
          return false;
        }
        for (Map.Entry<Object, Object> mapEntry1 : protoMap1.map().entrySet()) {
          Object mapKey = mapEntry1.getKey();
          Object mapValue1 = mapEntry1.getValue();
          if (!protoMap2.map().containsKey(mapKey)) {
            return false;
          }
          Object mapValue2 = protoMap2.map().get(mapKey);
          if (!equalsFieldValues(protoMap1.valueField(), mapValue1, mapValue2)) {
            return false;
          }
        }
      } else if (field.isRepeated()) {
        if (!equalsRepeatedFieldValues(field, (List<?>) value1, (List<?>) value2)) {
          return false;
        }
      } else if (!equalsFieldValues(field, value1, value2)) {
        return false;
      }
    }
    return true;
  }

  /** Returns a hash code consistent with {@link #equals(Message, Message)}. */
  public int hashCode(Message message) {
    if (isAny(message)) {
      // Constant hash: unpacked Any equality ignores wire bytes, while fallback ignores type_url.
      return ANY_FULL_NAME.hashCode();
    }
    return Objects.hash(
        message.getDescriptorForType(),
        message.getUnknownFields(),
        Maps.transformEntries(message.getAllFields(), this::hashFieldEntry));
  }

  private int hashFieldEntry(FieldDescriptor field, Object value) {
    if (field.isMapField()) {
      ProtoMap protoMap = protoMap((List<?>) value);
      return Maps.transformValues(protoMap.map(), v -> hashFieldValue(protoMap.valueField(), v))
          .hashCode();
    }
    if (field.isRepeated()) {
      return Lists.transform((List<?>) value, elem -> hashFieldValue(field, elem)).hashCode();
    }
    return hashFieldValue(field, value);
  }

  private int hashFieldValue(FieldDescriptor field, Object value) {
    switch (field.getJavaType()) {
      case MESSAGE:
        return hashCode((Message) value);
      case DOUBLE:
      case FLOAT:
        double v = ((Number) value).doubleValue();
        // Normalize -0.0 to 0.0 so equal zeros hash alike.
        return Double.hashCode(v == 0.0d ? 0.0d : v);
      default:
        return value.hashCode();
    }
  }

  private boolean equalsFieldValues(FieldDescriptor field, Object value1, Object value2) {
    switch (field.getJavaType()) {
      case MESSAGE:
        return equals((Message) value1, (Message) value2);
      case DOUBLE:
      case FLOAT:
        return ((Number) value1).doubleValue() == ((Number) value2).doubleValue();
      default:
        return value1.equals(value2);
    }
  }

  private boolean equalsRepeatedFieldValues(FieldDescriptor field, List<?> value1, List<?> value2) {
    if (value1.size() != value2.size()) {
      return false;
    }
    for (int i = 0; i < value1.size(); i++) {
      Object elem1 = value1.get(i);
      Object elem2 = value2.get(i);
      if (!equalsFieldValues(field, elem1, elem2)) {
        return false;
      }
    }
    return true;
  }

  private ByteString anyValue(Message msg) {
    FieldDescriptor value = msg.getDescriptorForType().findFieldByName("value");
    return (ByteString) msg.getField(value);
  }

  private static ProtoMap protoMap(List<?> entries) {
    ImmutableMap.Builder<Object, Object> protoMap = ImmutableMap.builder();
    FieldDescriptor keyField = null;
    FieldDescriptor valueField = null;
    for (Object entry : entries) {
      Message mapEntry = (Message) entry;
      if (keyField == null && valueField == null) {
        Descriptor descriptor = mapEntry.getDescriptorForType();
        keyField = descriptor.findFieldByNumber(1);
        valueField = descriptor.findFieldByNumber(2);
      }
      protoMap.put(mapEntry.getField(keyField), mapEntry.getField(valueField));
    }
    return ProtoMap.create(valueField, protoMap.buildOrThrow());
  }

  private static boolean isAny(Message msg) {
    return msg instanceof Any || msg.getDescriptorForType().getFullName().equals(ANY_FULL_NAME);
  }

  @AutoValue
  abstract static class ProtoMap {

    abstract FieldDescriptor valueField();

    abstract ImmutableMap<Object, Object> map();

    static ProtoMap create(FieldDescriptor valueField, ImmutableMap<Object, Object> map) {
      return new AutoValue_ProtoEquality_ProtoMap(valueField, map);
    }
  }
}
