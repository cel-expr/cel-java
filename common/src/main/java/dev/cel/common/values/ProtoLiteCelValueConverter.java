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

import com.google.common.annotations.VisibleForTesting;
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
      value = getDefaultCelValue(valueDescriptor);
    }

    return new AbstractMap.SimpleImmutableEntry<>(key, value);
  }

  @Nullable Object readSingleField(ByteString bytes, FieldLiteDescriptor fieldDescriptor)
      throws IOException {
    CodedInputStream inputStream = bytes.newCodedInput();
    int targetFieldNumber = fieldDescriptor.getFieldNumber();
    Object fieldValue = null;
    for (int tag = inputStream.readTag(); tag != 0; tag = inputStream.readTag()) {
      int fieldNumber = WireFormat.getTagFieldNumber(tag);
      if (fieldNumber != targetFieldNumber) {
        skipWireField(tag, inputStream);
        continue;
      }
      int tagWireType = WireFormat.getTagWireType(tag);
      fieldValue = readFieldValue(tagWireType, inputStream, fieldDescriptor, fieldValue);
    }
    // Only this field is decoded, so unlike readAllFields, a failed conversion can't affect access
    // to any other field.
    return fieldValue == null ? null : resolveFieldValue(finalizeFieldValue(fieldValue));
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
    FieldLiteDescriptor fieldDescriptor = newFieldDescriptor(field);
    SelectField.MapEntrySpec mapEntrySpec = field.mapEntrySpec();
    if (mapEntrySpec == null) {
      return readSingleField(bytes, fieldDescriptor);
    }
    // The map entry type has no descriptor either, so its key and value are described by the spec.
    FieldLiteDescriptor keyDescriptor =
        newFieldDescriptor(
            MAP_KEY_FIELD_NUMBER,
            MAP_KEY_FIELD_NAME,
            EncodingType.SINGULAR,
            mapEntrySpec.keyTypeCode(),
            /* protoTypeName= */ "");
    FieldLiteDescriptor valueDescriptor =
        newFieldDescriptor(
            MAP_VALUE_FIELD_NUMBER,
            MAP_VALUE_FIELD_NAME,
            EncodingType.SINGULAR,
            mapEntrySpec.valueTypeCode(),
            field.protoTypeName());
    CodedInputStream inputStream = bytes.newCodedInput();
    Map<Object, Object> mapValues = null;
    for (int tag = inputStream.readTag(); tag != 0; tag = inputStream.readTag()) {
      if (WireFormat.getTagFieldNumber(tag) != field.fieldNumber()) {
        skipWireField(tag, inputStream);
        continue;
      }
      mapValues =
          readMapField(
              WireFormat.getTagWireType(tag),
              inputStream,
              fieldDescriptor,
              keyDescriptor,
              valueDescriptor,
              mapValues);
    }
    return mapValues == null ? null : resolveFieldValue(finalizeFieldValue(mapValues));
  }

  /** Describes {@code field} by the type information it carries. */
  private static FieldLiteDescriptor newFieldDescriptor(SelectField field) {
    if (field.mapEntrySpec() != null) {
      // SelectField doesn't name the map entry type; its protoTypeName() is the map's value type.
      return newFieldDescriptor(
          field.fieldNumber(),
          field.fieldName(),
          EncodingType.MAP,
          SelectField.MESSAGE_TYPE_CODE,
          /* protoTypeName= */ "");
    }
    if (field.typeCode() == SelectField.NO_TYPE_CODE) {
      // Only presence tests omit the type code. Their presence check doesn't depend on the type,
      // and the fields they navigate through are always messages.
      return newFieldDescriptor(
          field.fieldNumber(),
          field.fieldName(),
          EncodingType.SINGULAR,
          SelectField.MESSAGE_TYPE_CODE,
          /* protoTypeName= */ "");
    }
    return newFieldDescriptor(
        field.fieldNumber(),
        field.fieldName(),
        field.defaultValue() instanceof List ? EncodingType.LIST : EncodingType.SINGULAR,
        field.typeCode(),
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
   * Decodes every known field in {@code bytes}, keyed by field name. Each value must be passed to
   * {@link #resolveFieldValue} to obtain its CEL value.
   */
  ImmutableMap<String, Object> readAllFields(ByteString bytes, String protoTypeName)
      throws IOException {
    MessageLiteDescriptor messageDescriptor = descriptorPool.getDescriptorOrThrow(protoTypeName);
    if (bytes.isEmpty()) {
      return ImmutableMap.of();
    }
    return readAllFields(bytes.newCodedInput(), messageDescriptor);
  }

  private ImmutableMap<String, Object> readAllFields(
      CodedInputStream inputStream, MessageLiteDescriptor messageDescriptor) throws IOException {
    Map<String, Object> fieldValues = new LinkedHashMap<>();
    for (int tag = inputStream.readTag(); tag != 0; tag = inputStream.readTag()) {
      int tagWireType = WireFormat.getTagWireType(tag);
      int fieldNumber = WireFormat.getTagFieldNumber(tag);
      FieldLiteDescriptor fieldDescriptor =
          messageDescriptor.findByFieldNumber(fieldNumber).orElse(null);
      if (fieldDescriptor == null) {
        skipWireField(tag, inputStream);
        continue;
      }

      String fieldName = fieldDescriptor.getFieldName();
      Object fieldValue =
          readFieldValue(tagWireType, inputStream, fieldDescriptor, fieldValues.get(fieldName));
      if (fieldValue != null) {
        fieldValues.put(fieldName, fieldValue);
      }
    }

    fieldValues.replaceAll((fieldName, fieldValue) -> finalizeFieldValue(fieldValue));
    return ImmutableMap.copyOf(fieldValues);
  }

  /**
   * Returns the CEL value of a field decoded by {@link #readAllFields}, completing any conversion
   * that was deferred.
   */
  Object resolveFieldValue(Object fieldValue) {
    if (fieldValue instanceof DeferredConversion) {
      return toRuntimeValue(((DeferredConversion) fieldValue).value);
    }
    return fieldValue;
  }

  /**
   * Converts a value accumulated while scanning a field into its final immutable form.
   *
   * <p>Repeated and map fields accumulate into mutable containers, which are copied into immutable
   * ones. Well-known types other than FieldMask (see {@link #isStructLike}) are kept as parsed
   * {@link MessageLite}s until the scan completes so that split occurrences can be merged, and
   * values holding them are wrapped in a {@link DeferredConversion}. All other messages are wrapped
   * as CEL values as soon as they are read.
   */
  private static Object finalizeFieldValue(Object accumulatedValue) {
    if (accumulatedValue instanceof List) {
      ImmutableList<?> list = ImmutableList.copyOf((List<?>) accumulatedValue);
      return Iterables.any(list, MessageLite.class::isInstance)
          ? new DeferredConversion(list)
          : list;
    }
    if (accumulatedValue instanceof Map) {
      ImmutableMap<?, ?> map = ImmutableMap.copyOf((Map<?, ?>) accumulatedValue);
      return Iterables.any(map.values(), MessageLite.class::isInstance)
          ? new DeferredConversion(map)
          : map;
    }
    return accumulatedValue instanceof MessageLite
        ? new DeferredConversion(accumulatedValue)
        : accumulatedValue;
  }

  private @Nullable Object readFieldValue(
      int tagWireType,
      CodedInputStream inputStream,
      FieldLiteDescriptor fieldDescriptor,
      @Nullable Object existingValue)
      throws IOException {
    EncodingType encodingType = fieldDescriptor.getEncodingType();
    switch (encodingType) {
      case SINGULAR:
        return readSingularField(tagWireType, inputStream, fieldDescriptor, existingValue);
      case LIST:
        return readRepeatedField(tagWireType, inputStream, fieldDescriptor, existingValue);
      case MAP:
        return readMapField(tagWireType, inputStream, fieldDescriptor, existingValue);
    }
    throw new IllegalStateException("Unexpected encoding type: " + encodingType);
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

  private Map<Object, Object> readMapField(
      int tagWireType,
      CodedInputStream inputStream,
      FieldLiteDescriptor fieldDescriptor,
      @Nullable Object existingValue)
      throws IOException {
    MessageLiteDescriptor entryDescriptor =
        descriptorPool.getDescriptorOrThrow(fieldDescriptor.getFieldProtoTypeName());
    return readMapField(
        tagWireType,
        inputStream,
        fieldDescriptor,
        entryDescriptor.getByFieldNameOrThrow(MAP_KEY_FIELD_NAME),
        entryDescriptor.getByFieldNameOrThrow(MAP_VALUE_FIELD_NAME),
        existingValue);
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

  @VisibleForTesting
  static void skipWireField(int tag, CodedInputStream inputStream) throws IOException {
    int tagWireType = WireFormat.getTagWireType(tag);
    switch (tagWireType) {
      case WireFormat.WIRETYPE_VARINT:
      case WireFormat.WIRETYPE_FIXED64:
      case WireFormat.WIRETYPE_LENGTH_DELIMITED:
      case WireFormat.WIRETYPE_FIXED32:
        inputStream.skipField(tag);
        return;
      case WireFormat.WIRETYPE_START_GROUP:
      case WireFormat.WIRETYPE_END_GROUP:
        throw new UnsupportedOperationException("Groups are not supported");
      default:
        throw new IllegalArgumentException("Unknown wire type: " + tagWireType);
    }
  }

  /**
   * A field value holding well-known type messages, whose conversion to CEL values is deferred
   * until {@link #resolveFieldValue}.
   */
  private static final class DeferredConversion {
    private final Object value;

    private DeferredConversion(Object value) {
      this.value = checkNotNull(value);
    }
  }

  private ProtoLiteCelValueConverter(CelLiteDescriptorPool celLiteDescriptorPool) {
    this.descriptorPool = checkNotNull(celLiteDescriptorPool);
  }
}
