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
import com.google.common.base.Defaults;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Multimap;
import com.google.common.collect.Multimaps;
import com.google.common.primitives.UnsignedLong;
import com.google.errorprone.annotations.Immutable;
import com.google.protobuf.ByteString;
import com.google.protobuf.CodedInputStream;
import com.google.protobuf.ExtensionRegistryLite;
import com.google.protobuf.MessageLite;
import com.google.protobuf.WireFormat;
import dev.cel.common.annotations.Internal;
import dev.cel.common.internal.CelLiteDescriptorPool;
import dev.cel.common.internal.WellKnownProto;
import dev.cel.protobuf.CelLiteDescriptor.FieldLiteDescriptor;
import dev.cel.protobuf.CelLiteDescriptor.FieldLiteDescriptor.EncodingType;
import dev.cel.protobuf.CelLiteDescriptor.FieldLiteDescriptor.JavaType;
import dev.cel.protobuf.CelLiteDescriptor.MessageLiteDescriptor;
import java.io.IOException;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

/**
 * {@code ProtoLiteCelValueConverter} handles bidirectional conversion between native Java and
 * protobuf objects to {@link CelValue}. This converter is specifically designed for use with
 * lite-variants of protobuf messages.
 *
 * <p>Protobuf semantics take precedence for conversion. For example, CEL's TimestampValue will be
 * converted into Protobuf's Timestamp instead of java.time.Instant.
 *
 * <p>CEL Library Internals. Do Not Use.
 */
@Immutable
@Internal
public final class ProtoLiteCelValueConverter extends BaseProtoCelValueConverter {
  private static final String MAP_KEY_FIELD_NAME = "key";
  private static final String MAP_VALUE_FIELD_NAME = "value";

  private final CelLiteDescriptorPool descriptorPool;

  public static ProtoLiteCelValueConverter newInstance(
      CelLiteDescriptorPool celLiteDescriptorPool) {
    return new ProtoLiteCelValueConverter(celLiteDescriptorPool);
  }

  private static Object readPrimitiveField(
      CodedInputStream inputStream, FieldLiteDescriptor fieldDescriptor) throws IOException {
    switch (fieldDescriptor.getProtoFieldType()) {
      case SINT32:
        return inputStream.readSInt32();
      case SINT64:
        return inputStream.readSInt64();
      case INT32:
      case ENUM:
        return inputStream.readInt32();
      case INT64:
        return inputStream.readInt64();
      case UINT32:
        return UnsignedLong.fromLongBits(Integer.toUnsignedLong(inputStream.readUInt32()));
      case UINT64:
        return UnsignedLong.fromLongBits(inputStream.readUInt64());
      case BOOL:
        return inputStream.readBool();
      case FLOAT:
      case FIXED32:
      case SFIXED32:
        return readFixed32BitField(inputStream, fieldDescriptor);
      case DOUBLE:
      case FIXED64:
      case SFIXED64:
        return readFixed64BitField(inputStream, fieldDescriptor);
      default:
        throw new IllegalStateException(
            "Unexpected field type: " + fieldDescriptor.getProtoFieldType());
    }
  }

  private static Object readFixed32BitField(
      CodedInputStream inputStream, FieldLiteDescriptor fieldDescriptor) throws IOException {
    switch (fieldDescriptor.getProtoFieldType()) {
      case FLOAT:
        return inputStream.readFloat();
      case FIXED32:
        return UnsignedLong.fromLongBits(
            Integer.toUnsignedLong(inputStream.readRawLittleEndian32()));
      case SFIXED32:
        return inputStream.readRawLittleEndian32();
      default:
        throw new IllegalStateException(
            "Unexpected field type: " + fieldDescriptor.getProtoFieldType());
    }
  }

  private static Object readFixed64BitField(
      CodedInputStream inputStream, FieldLiteDescriptor fieldDescriptor) throws IOException {
    switch (fieldDescriptor.getProtoFieldType()) {
      case DOUBLE:
        return inputStream.readDouble();
      case FIXED64:
        return UnsignedLong.fromLongBits(inputStream.readRawLittleEndian64());
      case SFIXED64:
        return inputStream.readRawLittleEndian64();
      default:
        throw new IllegalStateException(
            "Unexpected field type: " + fieldDescriptor.getProtoFieldType());
    }
  }

  private Object readLengthDelimitedField(
      CodedInputStream inputStream,
      FieldLiteDescriptor fieldDescriptor,
      @Nullable Object existingValue)
      throws IOException {
    FieldLiteDescriptor.Type fieldType = fieldDescriptor.getProtoFieldType();

    switch (fieldType) {
      case BYTES:
        return inputStream.readBytes();
      case MESSAGE:
        return mergeOrReadMessageField(
            inputStream.readBytes(), fieldDescriptor.getFieldProtoTypeName(), existingValue);
      case STRING:
        return inputStream.readStringRequireUtf8();
      default:
        throw new IllegalStateException("Unexpected field type: " + fieldType);
    }
  }

  private Object readMessageField(ByteString bytes, String fieldProtoTypeName) {
    return mergeOrReadMessageField(bytes, fieldProtoTypeName, /* existingValue= */ null);
  }

  private Object mergeOrReadMessageField(
      ByteString bytes, String fieldProtoTypeName, @Nullable Object existingValue) {
    MessageLiteDescriptor descriptor =
        descriptorPool.findDescriptor(fieldProtoTypeName).orElse(null);
    if (descriptor == null) {
      if (existingValue instanceof RawProtoMessageLiteValue) {
        bytes = ((RawProtoMessageLiteValue) existingValue).toByteString().concat(bytes);
      }
      return RawProtoMessageLiteValue.create(bytes, fieldProtoTypeName, this);
    }
    WellKnownProto wellKnownProto = WellKnownProto.getByTypeName(fieldProtoTypeName).orElse(null);
    if (isStructLike(wellKnownProto)) {
      if (existingValue instanceof ProtoMessageLiteValue) {
        bytes = ((ProtoMessageLiteValue) existingValue).toByteString().concat(bytes);
      }
      return ProtoMessageLiteValue.create(bytes, fieldProtoTypeName, this);
    }
    if (existingValue instanceof MessageLite) {
      return mergeMessageLite(((MessageLite) existingValue).toBuilder(), bytes, fieldProtoTypeName);
    }
    return parseMessageLite(bytes, descriptor);
  }

  // Unlike other WellKnownProtos (which unbox to CEL scalars/containers), FieldMask is
  // represented as a standard struct message so field selection (e.g., mask.paths) works.
  private static boolean isStructLike(@Nullable WellKnownProto wellKnownProto) {
    return wellKnownProto == null || wellKnownProto == WellKnownProto.FIELD_MASK;
  }

  Object getDefaultCelValue(String protoTypeName, String fieldName) {
    MessageLiteDescriptor messageDescriptor = descriptorPool.getDescriptorOrThrow(protoTypeName);
    return getDefaultCelValue(messageDescriptor.getByFieldNameOrThrow(fieldName));
  }

  Object getDefaultCelValue(FieldLiteDescriptor fieldDescriptor) {
    return toRuntimeValue(getDefaultValue(fieldDescriptor));
  }

  Optional<FieldLiteDescriptor> findFieldDescriptor(String protoTypeName, int fieldNumber) {
    return descriptorPool
        .findDescriptor(protoTypeName)
        .flatMap(desc -> desc.findByFieldNumber(fieldNumber));
  }

  MessageLite parseMessageLite(ByteString bytes, String protoTypeName) {
    MessageLiteDescriptor descriptor = descriptorPool.getDescriptorOrThrow(protoTypeName);
    return parseMessageLite(bytes, descriptor);
  }

  private static MessageLite parseMessageLite(ByteString bytes, MessageLiteDescriptor descriptor) {
    if (bytes.isEmpty()) {
      return descriptor.newMessageBuilder().getDefaultInstanceForType();
    }
    return mergeMessageLite(descriptor.newMessageBuilder(), bytes, descriptor.getProtoTypeName());
  }

  private static MessageLite mergeMessageLite(
      MessageLite.Builder builder, ByteString bytes, String protoTypeName) {
    try {
      return builder.mergeFrom(bytes, ExtensionRegistryLite.getEmptyRegistry()).build();
    } catch (IOException e) {
      throw new IllegalArgumentException(
          "Failed to decode proto message of type: " + protoTypeName, e);
    }
  }

  Optional<Object> tryDecodeProtoMessage(ByteString bytes, String protoTypeName) {
    return descriptorPool
        .findDescriptor(protoTypeName)
        .map(descriptor -> decodeProtoMessage(bytes, protoTypeName, descriptor));
  }

  private Object decodeProtoMessage(
      ByteString bytes, String protoTypeName, MessageLiteDescriptor descriptor) {
    WellKnownProto wellKnownProto = WellKnownProto.getByTypeName(protoTypeName).orElse(null);
    if (isStructLike(wellKnownProto)) {
      return ProtoMessageLiteValue.create(bytes, protoTypeName, this);
    }
    return fromWellKnownProto(parseMessageLite(bytes, descriptor), checkNotNull(wellKnownProto));
  }

  @Override
  public Object toRuntimeValue(Object value) {
    checkNotNull(value);
    if (value instanceof MessageLite) {
      MessageLite msg = (MessageLite) value;

      MessageLiteDescriptor descriptor = descriptorPool.findDescriptor(msg).orElse(null);
      if (descriptor == null) {
        return RawProtoMessageLiteValue.create(msg.toByteString(), this);
      }
      return toRuntimeValue(msg, descriptor);
    }

    return super.toRuntimeValue(value);
  }

  private Object toRuntimeValue(MessageLite msg, MessageLiteDescriptor descriptor) {
    WellKnownProto wellKnownProto =
        WellKnownProto.getByTypeName(descriptor.getProtoTypeName()).orElse(null);
    if (isStructLike(wellKnownProto)) {
      return ProtoMessageLiteValue.create(msg, descriptor.getProtoTypeName(), this);
    }

    return fromWellKnownProto(msg, checkNotNull(wellKnownProto));
  }

  private Object getDefaultValue(FieldLiteDescriptor fieldDescriptor) {
    EncodingType encodingType = fieldDescriptor.getEncodingType();
    switch (encodingType) {
      case LIST:
        return ImmutableList.of();
      case MAP:
        return ImmutableMap.of();
      case SINGULAR:
        return getScalarDefaultValue(fieldDescriptor);
    }
    throw new IllegalStateException("Unexpected encoding type: " + encodingType);
  }

  private Object getScalarDefaultValue(FieldLiteDescriptor fieldDescriptor) {
    JavaType type = fieldDescriptor.getJavaType();
    switch (type) {
      case INT:
        return (fieldDescriptor.getProtoFieldType().equals(FieldLiteDescriptor.Type.UINT32)
                || fieldDescriptor.getProtoFieldType().equals(FieldLiteDescriptor.Type.FIXED32))
            ? UnsignedLong.ZERO
            : Defaults.defaultValue(long.class);
      case LONG:
        return (fieldDescriptor.getProtoFieldType().equals(FieldLiteDescriptor.Type.UINT64)
                || fieldDescriptor.getProtoFieldType().equals(FieldLiteDescriptor.Type.FIXED64))
            ? UnsignedLong.ZERO
            : Defaults.defaultValue(long.class);
      case ENUM:
        return Defaults.defaultValue(long.class);
      case FLOAT:
        return Defaults.defaultValue(float.class);
      case DOUBLE:
        return Defaults.defaultValue(double.class);
      case BOOLEAN:
        return Defaults.defaultValue(boolean.class);
      case STRING:
        return "";
      case BYTE_STRING:
        return CelByteString.EMPTY;
      case MESSAGE:
        String fieldProtoTypeName = fieldDescriptor.getFieldProtoTypeName();
        if (WellKnownProto.isWrapperType(fieldProtoTypeName)) {
          return NullValue.NULL_VALUE;
        }
        return readMessageField(ByteString.EMPTY, fieldProtoTypeName);
    }
    throw new IllegalStateException("Unexpected java type: " + type);
  }

  private ImmutableList<Object> readPackedRepeatedFields(
      CodedInputStream inputStream, FieldLiteDescriptor fieldDescriptor) throws IOException {
    int length = inputStream.readInt32();
    int oldLimit = inputStream.pushLimit(length);
    ImmutableList.Builder<Object> builder = ImmutableList.builder();
    while (inputStream.getBytesUntilLimit() > 0) {
      builder.add(readPrimitiveField(inputStream, fieldDescriptor));
    }
    inputStream.popLimit(oldLimit);
    return builder.build();
  }

  private Map.Entry<Object, Object> readSingleMapEntry(
      CodedInputStream inputStream, FieldLiteDescriptor fieldDescriptor) throws IOException {
    String entryTypeName = fieldDescriptor.getFieldProtoTypeName();
    ImmutableMap<String, Object> singleMapEntry =
        readAllFields(inputStream.readBytes(), entryTypeName).values();
    Object key = singleMapEntry.get(MAP_KEY_FIELD_NAME);
    if (key == null) {
      key = getDefaultCelValue(entryTypeName, MAP_KEY_FIELD_NAME);
    }
    Object value = singleMapEntry.get(MAP_VALUE_FIELD_NAME);
    if (value == null) {
      value = getDefaultCelValue(entryTypeName, MAP_VALUE_FIELD_NAME);
    }

    return new AbstractMap.SimpleEntry<>(key, value);
  }

  MessageFields readAllFields(ByteString bytes, String protoTypeName) throws IOException {
    MessageLiteDescriptor messageDescriptor = descriptorPool.getDescriptorOrThrow(protoTypeName);
    if (bytes.isEmpty()) {
      return MessageFields.EMPTY;
    }
    return readAllFields(bytes.newCodedInput(), messageDescriptor);
  }

  private MessageFields readAllFields(
      CodedInputStream inputStream, MessageLiteDescriptor messageDescriptor) throws IOException {
    Multimap<Integer, Object> unknownFields = null;
    Map<String, Object> fieldValues = new LinkedHashMap<>();
    for (int tag = inputStream.readTag(); tag != 0; tag = inputStream.readTag()) {
      int tagWireType = WireFormat.getTagWireType(tag);
      int fieldNumber = WireFormat.getTagFieldNumber(tag);
      FieldLiteDescriptor fieldDescriptor =
          messageDescriptor.findByFieldNumber(fieldNumber).orElse(null);
      if (fieldDescriptor == null) {
        if (unknownFields == null) {
          unknownFields = Multimaps.newMultimap(new TreeMap<>(), ArrayList::new);
        }
        unknownFields.put(fieldNumber, readUnknownField(tagWireType, inputStream));
        continue;
      }

      String fieldName = fieldDescriptor.getFieldName();
      Object payload;
      switch (tagWireType) {
        case WireFormat.WIRETYPE_VARINT:
          payload = readPrimitiveField(inputStream, fieldDescriptor);
          break;
        case WireFormat.WIRETYPE_FIXED32:
          payload = readFixed32BitField(inputStream, fieldDescriptor);
          break;
        case WireFormat.WIRETYPE_FIXED64:
          payload = readFixed64BitField(inputStream, fieldDescriptor);
          break;
        case WireFormat.WIRETYPE_LENGTH_DELIMITED:
          EncodingType encodingType = fieldDescriptor.getEncodingType();
          switch (encodingType) {
            case LIST:
              if (fieldDescriptor.getIsPacked()) {
                payload = readPackedRepeatedFields(inputStream, fieldDescriptor);
              } else {
                FieldLiteDescriptor.Type protoFieldType = fieldDescriptor.getProtoFieldType();
                boolean isLenDelimited =
                    protoFieldType.equals(FieldLiteDescriptor.Type.MESSAGE)
                        || protoFieldType.equals(FieldLiteDescriptor.Type.STRING)
                        || protoFieldType.equals(FieldLiteDescriptor.Type.BYTES);
                if (!isLenDelimited) {
                  throw new IllegalStateException(
                      "Unexpected field type encountered for LEN-Delimited record: "
                          + protoFieldType);
                }

                payload =
                    readLengthDelimitedField(
                        inputStream, fieldDescriptor, /* existingValue= */ null);
              }
              break;
            case MAP:
              // Safe because MAP fields only ever store a LinkedHashMap in fieldValues.
              @SuppressWarnings("unchecked")
              Map<Object, Object> fieldMap =
                  (Map<Object, Object>)
                      fieldValues.computeIfAbsent(fieldName, (unused) -> new LinkedHashMap<>());
              Map.Entry<Object, Object> mapEntry = readSingleMapEntry(inputStream, fieldDescriptor);
              fieldMap.put(mapEntry.getKey(), mapEntry.getValue());
              continue;
            default:
              payload =
                  readLengthDelimitedField(
                      inputStream, fieldDescriptor, fieldValues.get(fieldName));
              break;
          }
          break;
        case WireFormat.WIRETYPE_START_GROUP:
        case WireFormat.WIRETYPE_END_GROUP:
          // TODO: Support groups
          throw new UnsupportedOperationException("Groups are not supported");
        default:
          throw new IllegalArgumentException("Unexpected wire type: " + tagWireType);
      }

      if (fieldDescriptor.getEncodingType().equals(EncodingType.LIST)) {
        if (payload instanceof Collection) {
          Collection<?> elements = (Collection<?>) payload;
          if (!elements.isEmpty()) {
            getOrCreateRepeatedList(fieldValues, fieldName).addAll(elements);
          }
        } else {
          getOrCreateRepeatedList(fieldValues, fieldName).add(payload);
        }
      } else {
        fieldValues.put(fieldName, payload);
      }
    }

    return MessageFields.create(ImmutableMap.copyOf(fieldValues), unknownFields);
  }

  // Safe because LIST fields only ever store an ArrayList in fieldValues.
  @SuppressWarnings("unchecked")
  private static List<Object> getOrCreateRepeatedList(
      Map<String, Object> fieldValues, String fieldName) {
    return (List<Object>) fieldValues.computeIfAbsent(fieldName, (unused) -> new ArrayList<>());
  }

  static Object readUnknownField(int tagWireType, CodedInputStream inputStream) throws IOException {
    switch (tagWireType) {
      case WireFormat.WIRETYPE_VARINT:
        return inputStream.readInt64();
      case WireFormat.WIRETYPE_FIXED64:
        return inputStream.readFixed64();
      case WireFormat.WIRETYPE_LENGTH_DELIMITED:
        return inputStream.readBytes();
      case WireFormat.WIRETYPE_FIXED32:
        return inputStream.readFixed32();
      case WireFormat.WIRETYPE_START_GROUP:
      case WireFormat.WIRETYPE_END_GROUP:
        // TODO: Support groups
        throw new UnsupportedOperationException("Groups are not supported");
      default:
        throw new IllegalArgumentException("Unknown wire type: " + tagWireType);
    }
  }

  @AutoValue
  @AutoValue.CopyAnnotations
  @Immutable
  @SuppressWarnings("Immutable") // Safe immutable fields
  abstract static class MessageFields {
    static final MessageFields EMPTY =
        new AutoValue_ProtoLiteCelValueConverter_MessageFields(
            ImmutableMap.of(), ImmutableListMultimap.of());

    abstract ImmutableMap<String, Object> values();

    abstract ImmutableListMultimap<Integer, Object> unknowns();

    private static MessageFields create(
        ImmutableMap<String, Object> fieldValues,
        @Nullable Multimap<Integer, Object> unknownFields) {
      return new AutoValue_ProtoLiteCelValueConverter_MessageFields(
          fieldValues,
          unknownFields == null
              ? ImmutableListMultimap.of()
              : ImmutableListMultimap.copyOf(unknownFields));
    }
  }

  private ProtoLiteCelValueConverter(CelLiteDescriptorPool celLiteDescriptorPool) {
    this.descriptorPool = checkNotNull(celLiteDescriptorPool);
  }
}
