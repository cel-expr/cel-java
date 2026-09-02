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
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Defaults;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Multimap;
import com.google.common.collect.Multimaps;
import com.google.common.primitives.UnsignedLong;
import com.google.errorprone.annotations.Immutable;
import com.google.protobuf.CodedInputStream;
import com.google.protobuf.ExtensionRegistryLite;
import com.google.protobuf.MessageLite;
import com.google.protobuf.MessageLiteOrBuilder;
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
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.TreeMap;

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
        return UnsignedLong.fromLongBits(inputStream.readUInt32());
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
      case SFIXED64:
        return inputStream.readRawLittleEndian64();
      default:
        throw new IllegalStateException(
            "Unexpected field type: " + fieldDescriptor.getProtoFieldType());
    }
  }

  private Object readLengthDelimitedField(
      CodedInputStream inputStream, FieldLiteDescriptor fieldDescriptor) throws IOException {
    FieldLiteDescriptor.Type fieldType = fieldDescriptor.getProtoFieldType();

    switch (fieldType) {
      case BYTES:
        return inputStream.readBytes();
      case MESSAGE:
        MessageLite.Builder builder =
            getDefaultMessageBuilder(fieldDescriptor.getFieldProtoTypeName());

        inputStream.readMessage(builder, ExtensionRegistryLite.getEmptyRegistry());
        return builder.build();
      case STRING:
        return inputStream.readStringRequireUtf8();
      default:
        throw new IllegalStateException("Unexpected field type: " + fieldType);
    }
  }

  private MessageLite.Builder getDefaultMessageBuilder(String protoTypeName) {
    return descriptorPool.getDescriptorOrThrow(protoTypeName).newMessageBuilder();
  }

  Object getDefaultCelValue(String protoTypeName, String fieldName) {
    MessageLiteDescriptor messageDescriptor = descriptorPool.getDescriptorOrThrow(protoTypeName);
    FieldLiteDescriptor fieldDescriptor = messageDescriptor.getByFieldNameOrThrow(fieldName);

    Object defaultValue = getDefaultValue(fieldDescriptor);

    return toRuntimeValue(defaultValue);
  }

  @Internal
  public Optional<FieldLiteDescriptor> findFieldDescriptor(String protoTypeName, String fieldName) {
    return descriptorPool
        .findDescriptor(protoTypeName)
        .flatMap(desc -> desc.findByFieldName(fieldName));
  }

  @Internal
  public Optional<Object> findDefaultCelValue(String protoTypeName, String fieldName) {
    return findFieldDescriptor(protoTypeName, fieldName)
        .map(fieldDescriptor -> toRuntimeValue(getDefaultValue(fieldDescriptor)));
  }

  @Override
  @SuppressWarnings("LiteProtoToString") // No alternative identifier to use. Debug only info is OK.
  public Object toRuntimeValue(Object value) {
    checkNotNull(value);
    if (value instanceof MessageLite) {
      MessageLite msg = (MessageLite) value;

      MessageLiteDescriptor descriptor =
          descriptorPool
              .findDescriptor(msg)
              .orElseThrow(
                  () -> new NoSuchElementException("Could not find a descriptor for: " + msg));
      WellKnownProto wellKnownProto =
          WellKnownProto.getByTypeName(descriptor.getProtoTypeName()).orElse(null);

      if (wellKnownProto == null) {
        return ProtoMessageLiteValue.create(msg, descriptor.getProtoTypeName(), this);
      }

      return fromWellKnownProto(msg, wellKnownProto);
    }

    return super.toRuntimeValue(value);
  }

  @Override
  protected Object fromWellKnownProto(MessageLiteOrBuilder msg, WellKnownProto wellKnownProto) {
    if (wellKnownProto == WellKnownProto.FIELD_MASK) {
      MessageLite message = (MessageLite) msg;
      MessageLiteDescriptor descriptor =
          descriptorPool
              .findDescriptor(message)
              .orElseThrow(
                  () ->
                      new NoSuchElementException(
                          "Could not find a descriptor for message of type: "
                              + message.getClass().getName()));
      return ProtoMessageLiteValue.create(message, descriptor.getProtoTypeName(), this);
    }

    return super.fromWellKnownProto(msg, wellKnownProto);
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
        return fieldDescriptor.getProtoFieldType().equals(FieldLiteDescriptor.Type.UINT32)
            ? UnsignedLong.ZERO
            : Defaults.defaultValue(long.class);
      case LONG:
        return fieldDescriptor.getProtoFieldType().equals(FieldLiteDescriptor.Type.UINT64)
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
        if (WellKnownProto.isWrapperType(fieldDescriptor.getFieldProtoTypeName())) {
          return NullValue.NULL_VALUE;
        }

        return getDefaultMessageBuilder(fieldDescriptor.getFieldProtoTypeName()).build();
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
    ImmutableMap<String, Object> singleMapEntry =
        readAllFields(inputStream.readByteArray(), fieldDescriptor.getFieldProtoTypeName())
            .values();
    Object key = checkNotNull(singleMapEntry.get("key"));
    Object value = checkNotNull(singleMapEntry.get("value"));

    return new AbstractMap.SimpleEntry<>(key, value);
  }

  @VisibleForTesting
  MessageFields readAllFields(byte[] bytes, String protoTypeName) throws IOException {
    MessageLiteDescriptor messageDescriptor = descriptorPool.getDescriptorOrThrow(protoTypeName);
    CodedInputStream inputStream = CodedInputStream.newInstance(bytes);

    Multimap<Integer, Object> unknownFields =
        Multimaps.newMultimap(new TreeMap<>(), ArrayList::new);
    ImmutableMap.Builder<String, Object> fieldValues = ImmutableMap.builder();
    Map<Integer, List<Object>> repeatedFieldValues = new LinkedHashMap<>();
    Map<Integer, Map<Object, Object>> mapFieldValues = new LinkedHashMap<>();
    for (int tag = inputStream.readTag(); tag != 0; tag = inputStream.readTag()) {
      int tagWireType = WireFormat.getTagWireType(tag);
      int fieldNumber = WireFormat.getTagFieldNumber(tag);
      FieldLiteDescriptor fieldDescriptor =
          messageDescriptor.findByFieldNumber(fieldNumber).orElse(null);
      if (fieldDescriptor == null) {
        unknownFields.put(fieldNumber, readUnknownField(tagWireType, inputStream));
        continue;
      }

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

                payload = readLengthDelimitedField(inputStream, fieldDescriptor);
              }
              break;
            case MAP:
              Map<Object, Object> fieldMap =
                  mapFieldValues.computeIfAbsent(fieldNumber, (unused) -> new LinkedHashMap<>());
              Map.Entry<Object, Object> mapEntry = readSingleMapEntry(inputStream, fieldDescriptor);
              fieldMap.put(mapEntry.getKey(), mapEntry.getValue());
              payload = fieldMap;
              break;
            default:
              payload = readLengthDelimitedField(inputStream, fieldDescriptor);
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
        String fieldName = fieldDescriptor.getFieldName();
        List<Object> repeatedValues =
            repeatedFieldValues.computeIfAbsent(
                fieldNumber,
                (unused) -> {
                  List<Object> newList = new ArrayList<>();
                  fieldValues.put(fieldName, newList);
                  return newList;
                });

        if (payload instanceof Collection) {
          repeatedValues.addAll((Collection<?>) payload);
        } else {
          repeatedValues.add(payload);
        }
      } else {
        fieldValues.put(fieldDescriptor.getFieldName(), payload);
      }
    }

    // Protobuf encoding follows a "last one wins" semantics. This means for duplicated fields,
    // we accept the last value encountered.
    return MessageFields.create(fieldValues.buildKeepingLast(), unknownFields);
  }

  ImmutableMap<String, Object> readAllFields(MessageLite msg, String protoTypeName)
      throws IOException {
    return readMessageFields(msg, protoTypeName).values();
  }

  MessageFields readMessageFields(MessageLite msg, String protoTypeName) throws IOException {
    return readAllFields(msg.toByteArray(), protoTypeName);
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

    abstract ImmutableMap<String, Object> values();

    abstract ImmutableListMultimap<Integer, Object> unknowns();

    static MessageFields create(
        ImmutableMap<String, Object> fieldValues, Multimap<Integer, Object> unknownFields) {
      return new AutoValue_ProtoLiteCelValueConverter_MessageFields(
          fieldValues, ImmutableListMultimap.copyOf(unknownFields));
    }
  }

  private ProtoLiteCelValueConverter(CelLiteDescriptorPool celLiteDescriptorPool) {
    this.descriptorPool = celLiteDescriptorPool;
  }
}
