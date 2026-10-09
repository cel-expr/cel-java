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

import com.google.common.base.Defaults;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.primitives.UnsignedLong;
import com.google.errorprone.annotations.Immutable;
import com.google.protobuf.ByteString;
import com.google.protobuf.CodedInputStream;
import com.google.protobuf.ExtensionRegistryLite;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.MessageLite;
import com.google.protobuf.WireFormat;
import dev.cel.common.annotations.Internal;
import dev.cel.common.exceptions.CelAttributeNotFoundException;
import dev.cel.common.internal.CelLiteDescriptorPool;
import dev.cel.common.internal.WellKnownProto;
import dev.cel.protobuf.CelLiteDescriptor.FieldLiteDescriptor;
import dev.cel.protobuf.CelLiteDescriptor.FieldLiteDescriptor.EncodingType;
import dev.cel.protobuf.CelLiteDescriptor.FieldLiteDescriptor.JavaType;
import dev.cel.protobuf.CelLiteDescriptor.MessageLiteDescriptor;
import java.io.IOException;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
  private static final int MAP_KEY_FIELD_NUMBER = 1;
  private static final int MAP_VALUE_FIELD_NUMBER = 2;

  private final CelLiteDescriptorPool descriptorPool;

  public static ProtoLiteCelValueConverter newInstance(
      CelLiteDescriptorPool celLiteDescriptorPool) {
    return new ProtoLiteCelValueConverter(celLiteDescriptorPool);
  }

  private static Object readPrimitiveField(
      CodedInputStream inputStream, FieldLiteDescriptor fieldDescriptor) throws IOException {
    switch (fieldDescriptor.getProtoFieldType()) {
      case SINT32:
        return (long) inputStream.readSInt32();
      case SINT64:
        return inputStream.readSInt64();
      case INT32:
      case ENUM:
        return (long) inputStream.readInt32();
      case INT64:
        return inputStream.readInt64();
      case UINT32:
        return UnsignedLong.fromLongBits(Integer.toUnsignedLong(inputStream.readUInt32()));
      case UINT64:
        return UnsignedLong.fromLongBits(inputStream.readUInt64());
      case BOOL:
        return inputStream.readBool();
      case FLOAT:
        return (double) inputStream.readFloat();
      case FIXED32:
        return UnsignedLong.fromLongBits(
            Integer.toUnsignedLong(inputStream.readRawLittleEndian32()));
      case SFIXED32:
        return (long) inputStream.readRawLittleEndian32();
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
        return CelByteString.of(inputStream.readByteArray());
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

  Object getDefaultCelValue(FieldLiteDescriptor fieldDescriptor) {
    return toRuntimeValue(getDefaultValue(fieldDescriptor));
  }

  Optional<FieldLiteDescriptor> findFieldDescriptor(String protoTypeName, int fieldNumber) {
    return descriptorPool
        .findDescriptor(protoTypeName)
        .flatMap(desc -> desc.findByFieldNumber(fieldNumber));
  }

  Optional<FieldLiteDescriptor> findFieldDescriptor(String protoTypeName, String fieldName) {
    return descriptorPool
        .findDescriptor(protoTypeName)
        .flatMap(desc -> desc.findByFieldName(fieldName));
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

  private Map.Entry<Object, Object> readSingleMapEntry(
      CodedInputStream inputStream,
      FieldLiteDescriptor keyDescriptor,
      FieldLiteDescriptor valueDescriptor)
      throws IOException {
    int length = inputStream.readInt32();
    int oldLimit = inputStream.pushLimit(length);
    Object key = null;
    Object value = null;
    while (inputStream.getBytesUntilLimit() > 0) {
      int tag = inputStream.readTag();
      int tagWireType = WireFormat.getTagWireType(tag);
      int fieldNumber = WireFormat.getTagFieldNumber(tag);
      if (fieldNumber == keyDescriptor.getFieldNumber()) {
        key = readSingularField(tagWireType, inputStream, keyDescriptor, key);
      } else if (fieldNumber == valueDescriptor.getFieldNumber()) {
        value = readSingularField(tagWireType, inputStream, valueDescriptor, value);
      } else {
        skipWireField(tag, inputStream);
      }
    }
    inputStream.popLimit(oldLimit);
    if (key == null) {
      key = getDefaultCelValue(keyDescriptor);
    }
    if (value == null) {
      // Map values have no presence, so a missing message (even a wrapper) is empty, not null.
      value =
          valueDescriptor.getJavaType().equals(JavaType.MESSAGE)
              ? readMessageField(ByteString.EMPTY, valueDescriptor.getFieldProtoTypeName())
              : getDefaultCelValue(valueDescriptor);
    }

    return new AbstractMap.SimpleImmutableEntry<>(key, value);
  }

  /**
   * Decodes only {@code fieldDescriptor}'s occurrences in {@code bytes} into its CEL value,
   * skipping every other field. Returns null if the field is absent (or a repeated field has only
   * empty packed records on the wire).
   */
  @Nullable Object readSingleField(ByteString bytes, FieldLiteDescriptor fieldDescriptor)
      throws IOException {
    return scanField(bytes, fieldDescriptor, null, null);
  }

  /** Scans for {@code fieldDescriptor}; null map entry descriptors are looked up in the pool. */
  private @Nullable Object scanField(
      ByteString bytes,
      FieldLiteDescriptor fieldDescriptor,
      @Nullable FieldLiteDescriptor keyDescriptor,
      @Nullable FieldLiteDescriptor valueDescriptor)
      throws IOException {
    CodedInputStream inputStream = bytes.newCodedInput();
    int targetFieldNumber = fieldDescriptor.getFieldNumber();
    Object fieldValue = null;
    for (int tag = inputStream.readTag(); tag != 0; tag = inputStream.readTag()) {
      if (WireFormat.getTagFieldNumber(tag) != targetFieldNumber) {
        skipWireField(tag, inputStream);
        continue;
      }
      int tagWireType = WireFormat.getTagWireType(tag);
      switch (fieldDescriptor.getEncodingType()) {
        case SINGULAR:
          fieldValue = readSingularField(tagWireType, inputStream, fieldDescriptor, fieldValue);
          break;
        case LIST:
          fieldValue = readRepeatedField(tagWireType, inputStream, fieldDescriptor, fieldValue);
          break;
        case MAP:
          if (keyDescriptor == null) {
            // Looked up at the first entry since an absent map needs no entry descriptor.
            MessageLiteDescriptor entryDescriptor =
                descriptorPool.getDescriptorOrThrow(fieldDescriptor.getFieldProtoTypeName());
            keyDescriptor = entryDescriptor.getByFieldNumberOrThrow(MAP_KEY_FIELD_NUMBER);
            valueDescriptor = entryDescriptor.getByFieldNumberOrThrow(MAP_VALUE_FIELD_NUMBER);
          }
          fieldValue =
              readMapField(
                  tagWireType,
                  inputStream,
                  fieldDescriptor,
                  keyDescriptor,
                  valueDescriptor,
                  fieldValue);
          break;
      }
    }
    return fieldValue == null ? null : finalizeFieldValue(fieldValue);
  }

  boolean hasSingleField(ByteString bytes, FieldLiteDescriptor fieldDescriptor) throws IOException {
    int targetFieldNumber = fieldDescriptor.getFieldNumber();
    boolean isPackableList =
        fieldDescriptor.getEncodingType().equals(EncodingType.LIST) && isPackable(fieldDescriptor);
    CodedInputStream inputStream = bytes.newCodedInput();
    for (int tag = inputStream.readTag(); tag != 0; tag = inputStream.readTag()) {
      int fieldNumber = WireFormat.getTagFieldNumber(tag);
      if (fieldNumber != targetFieldNumber) {
        skipWireField(tag, inputStream);
        continue;
      }
      int tagWireType = WireFormat.getTagWireType(tag);
      // In protobuf wire format, a zero-length entry for a singular field (e.g. empty string,
      // bytes, or empty submessage) represents explicit presence on the wire. Only packed
      // repeated fields with empty payload represent an empty/absent collection.
      if (isPackableList && tagWireType == WireFormat.WIRETYPE_LENGTH_DELIMITED) {
        int length = inputStream.readInt32();
        inputStream.skipRawBytes(length);
        if (length > 0) {
          return true;
        }
        continue;
      }
      skipWireField(tag, inputStream);
      return true;
    }
    return false;
  }

  /**
   * Selects {@code field} from {@code bytes}, decoding it by the type information in {@code field}
   * for fields missing from the descriptor pool. Returns the field's default value if it's absent.
   * Throws {@link CelAttributeNotFoundException} if {@code field} has no type code.
   */
  Object selectByFieldNumber(ByteString bytes, SelectField field) throws IOException {
    if (field.typeCode() == SelectField.NO_TYPE_CODE) {
      throw CelAttributeNotFoundException.forFieldResolution(field.fieldName());
    }
    Object fieldValue = readFieldByNumber(bytes, field);
    if (fieldValue != null) {
      return fieldValue;
    }
    if (field.defaultValue() != null) {
      return field.defaultValue();
    }
    return getDefaultCelValue(newFieldDescriptor(field));
  }

  /**
   * Finds {@code field} in {@code bytes}, decoding it by the type information in {@code field} for
   * fields missing from the descriptor pool. A field without a type code is decoded as a message.
   */
  Optional<Object> findByFieldNumber(ByteString bytes, SelectField field) throws IOException {
    return Optional.ofNullable(readFieldByNumber(bytes, field));
  }

  /** Returns whether {@code field} is present in {@code bytes}. */
  boolean hasFieldByNumber(ByteString bytes, SelectField field) throws IOException {
    return hasSingleField(bytes, newFieldDescriptor(field));
  }

  private @Nullable Object readFieldByNumber(ByteString bytes, SelectField field)
      throws IOException {
    SelectField.MapEntrySpec mapEntrySpec = field.mapEntrySpec();
    if (mapEntrySpec == null) {
      return readSingleField(bytes, newFieldDescriptor(field));
    }
    // The map entry type has no descriptor either, so its key and value are described by the spec.
    return scanField(
        bytes,
        newFieldDescriptor(field),
        newFieldDescriptor(
            MAP_KEY_FIELD_NUMBER,
            MAP_KEY_FIELD_NAME,
            EncodingType.SINGULAR,
            mapEntrySpec.keyTypeCode(),
            /* protoTypeName= */ ""),
        newFieldDescriptor(
            MAP_VALUE_FIELD_NUMBER,
            MAP_VALUE_FIELD_NAME,
            EncodingType.SINGULAR,
            mapEntrySpec.valueTypeCode(),
            field.protoTypeName()));
  }

  /** Describes {@code field} by the type information it carries. */
  private static FieldLiteDescriptor newFieldDescriptor(SelectField field) {
    // Only presence tests omit the type code: their presence check doesn't depend on the type, and
    // the fields they navigate through are messages, as are map entries.
    boolean isMap = field.mapEntrySpec() != null;
    boolean isMessage = isMap || field.typeCode() == SelectField.NO_TYPE_CODE;
    return newFieldDescriptor(
        field.fieldNumber(),
        field.fieldName(),
        isMap
            ? EncodingType.MAP
            : (field.defaultValue() instanceof List ? EncodingType.LIST : EncodingType.SINGULAR),
        isMessage ? SelectField.MESSAGE_TYPE_CODE : field.typeCode(),
        field.protoTypeName());
  }

  private static FieldLiteDescriptor newFieldDescriptor(
      int fieldNumber,
      String fieldName,
      EncodingType encodingType,
      int typeCode,
      String protoTypeName) {
    FieldLiteDescriptor.Type protoFieldType = FieldLiteDescriptor.Type.forNumber(typeCode);
    return new FieldLiteDescriptor(
        fieldNumber,
        fieldName,
        // FieldLiteDescriptor.JavaType's constants match WireFormat.JavaType's by name.
        JavaType.valueOf(protoFieldType.toWireFormatFieldType().getJavaType().name()),
        encodingType,
        protoFieldType,
        /* isPacked= */ false,
        protoTypeName);
  }

  /**
   * Converts a value accumulated while scanning a field into its final immutable form.
   *
   * <p>Repeated and map fields accumulate into mutable containers, which are copied into immutable
   * ones. Well-known types other than FieldMask (see {@link #isStructLike}) are kept as parsed
   * {@link MessageLite}s until the scan completes, so that split occurrences can be merged and map
   * values replaced by a repeated key are never converted. All other messages are wrapped as CEL
   * values as soon as they are read.
   */
  private Object finalizeFieldValue(Object accumulatedValue) {
    if (accumulatedValue instanceof List) {
      ImmutableList<?> list = ImmutableList.copyOf((List<?>) accumulatedValue);
      return Iterables.any(list, MessageLite.class::isInstance) ? toRuntimeValue(list) : list;
    }
    if (accumulatedValue instanceof Map) {
      ImmutableMap<?, ?> map = ImmutableMap.copyOf((Map<?, ?>) accumulatedValue);
      return Iterables.any(map.values(), MessageLite.class::isInstance) ? toRuntimeValue(map) : map;
    }
    return accumulatedValue instanceof MessageLite
        ? toRuntimeValue(accumulatedValue)
        : accumulatedValue;
  }

  private Object readSingularField(
      int tagWireType,
      CodedInputStream inputStream,
      FieldLiteDescriptor fieldDescriptor,
      @Nullable Object existingValue)
      throws IOException {
    checkWireType(tagWireType, fieldDescriptor);
    if (tagWireType == WireFormat.WIRETYPE_LENGTH_DELIMITED) {
      return readLengthDelimitedField(inputStream, fieldDescriptor, existingValue);
    }
    return readPrimitiveField(inputStream, fieldDescriptor);
  }

  // Safe because LIST fields only ever store an ArrayList as their accumulated value.
  @SuppressWarnings("unchecked")
  private @Nullable List<Object> readRepeatedField(
      int tagWireType,
      CodedInputStream inputStream,
      FieldLiteDescriptor fieldDescriptor,
      @Nullable Object existingValue)
      throws IOException {
    List<Object> repeatedValues = (List<Object>) existingValue;
    // Parsers must accept both packed and unpacked encodings of a packable repeated field,
    // regardless of whether the field is declared as packed.
    if (tagWireType == WireFormat.WIRETYPE_LENGTH_DELIMITED && isPackable(fieldDescriptor)) {
      return readPackedRepeatedFields(inputStream, fieldDescriptor, repeatedValues);
    }
    Object element =
        readSingularField(tagWireType, inputStream, fieldDescriptor, /* existingValue= */ null);
    if (repeatedValues == null) {
      repeatedValues = new ArrayList<>();
    }
    repeatedValues.add(element);
    return repeatedValues;
  }

  private static @Nullable List<Object> readPackedRepeatedFields(
      CodedInputStream inputStream,
      FieldLiteDescriptor fieldDescriptor,
      @Nullable List<Object> repeatedValues)
      throws IOException {
    int length = inputStream.readInt32();
    if (length == 0) {
      return repeatedValues;
    }
    int oldLimit = inputStream.pushLimit(length);
    if (repeatedValues == null) {
      repeatedValues = new ArrayList<>();
    }
    while (inputStream.getBytesUntilLimit() > 0) {
      repeatedValues.add(readPrimitiveField(inputStream, fieldDescriptor));
    }
    inputStream.popLimit(oldLimit);
    return repeatedValues;
  }

  // Safe because MAP fields only ever store a LinkedHashMap as their accumulated value.
  @SuppressWarnings("unchecked")
  private Map<Object, Object> readMapField(
      int tagWireType,
      CodedInputStream inputStream,
      FieldLiteDescriptor fieldDescriptor,
      FieldLiteDescriptor keyDescriptor,
      FieldLiteDescriptor valueDescriptor,
      @Nullable Object existingValue)
      throws IOException {
    checkWireType(tagWireType, fieldDescriptor);
    Map<Object, Object> mapValues =
        existingValue != null ? (Map<Object, Object>) existingValue : new LinkedHashMap<>();
    Map.Entry<Object, Object> mapEntry =
        readSingleMapEntry(inputStream, keyDescriptor, valueDescriptor);
    mapValues.put(mapEntry.getKey(), mapEntry.getValue());
    return mapValues;
  }

  /**
   * Throws if a known field was encoded with a wire type that doesn't match its declared type.
   *
   * <p>This is deliberately stricter than protobuf-java, which parses such a field as an unknown
   * field. A mismatch indicates an incompatible schema change or corrupt bytes, so decoding fails
   * rather than silently dropping or misreading the value.
   */
  private static void checkWireType(int tagWireType, FieldLiteDescriptor fieldDescriptor)
      throws InvalidProtocolBufferException {
    if (tagWireType == WireFormat.WIRETYPE_START_GROUP
        || tagWireType == WireFormat.WIRETYPE_END_GROUP) {
      throw new UnsupportedOperationException("Groups are not supported");
    }
    FieldLiteDescriptor.Type fieldType = fieldDescriptor.getProtoFieldType();
    if (tagWireType != fieldType.toWireFormatFieldType().getWireType()) {
      throw new InvalidProtocolBufferException(
          String.format(
              "Field '%s' (number %d) of type %s has unexpected wire type %d",
              fieldDescriptor.getFieldName(),
              fieldDescriptor.getFieldNumber(),
              fieldType,
              tagWireType));
    }
  }

  private static boolean isPackable(FieldLiteDescriptor fieldDescriptor) {
    return fieldDescriptor.getProtoFieldType().toWireFormatFieldType().isPackable();
  }

  private static void skipWireField(int tag, CodedInputStream inputStream) throws IOException {
    int tagWireType = WireFormat.getTagWireType(tag);
    if (tagWireType == WireFormat.WIRETYPE_START_GROUP
        || tagWireType == WireFormat.WIRETYPE_END_GROUP) {
      throw new UnsupportedOperationException("Groups are not supported");
    }
    inputStream.skipField(tag);
  }

  private ProtoLiteCelValueConverter(CelLiteDescriptorPool celLiteDescriptorPool) {
    this.descriptorPool = checkNotNull(celLiteDescriptorPool);
  }
}
