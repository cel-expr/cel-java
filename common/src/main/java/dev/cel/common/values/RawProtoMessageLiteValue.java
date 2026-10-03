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

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.auto.value.AutoValue;
import com.google.auto.value.extension.memoized.Memoized;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Multimap;
import com.google.common.collect.Multimaps;
import com.google.common.primitives.UnsignedLong;
import com.google.errorprone.annotations.Immutable;
import com.google.protobuf.ByteString;
import com.google.protobuf.CodedInputStream;
import com.google.protobuf.WireFormat;
import dev.cel.common.exceptions.CelAttributeNotFoundException;
import dev.cel.common.types.CelType;
import dev.cel.common.types.StructTypeReference;
import dev.cel.protobuf.CelLiteDescriptor.FieldLiteDescriptor;
import java.io.IOException;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

/**
 * RawProtoMessageLiteValue enables descriptorless evaluation of protobuf messages to address
 * client-server version skew issues where newer fields or submessages lack generated classes and
 * descriptors in the evaluation environment.
 *
 * <p>Rather than requiring compiled {@code MessageLite} classes or runtime schema descriptors, this
 * value encapsulates the raw wire-format {@link ByteString} payload and performs classless,
 * reflection-free field traversal directly over wire tags via {@link CodedInputStream}.
 */
@AutoValue
@AutoValue.CopyAnnotations
@Immutable
@SuppressWarnings("Immutable") // Immutable wire fields
abstract class RawProtoMessageLiteValue extends StructValue<String, WireMessageLite>
    implements OptimizedSelectable, WireMessageLite {

  private static final String UNKNOWN_MESSAGE_TYPE_NAME = "cel.@unknownMessage";
  private static final int MAP_KEY_FIELD_NUMBER = 1;
  private static final int MAP_VALUE_FIELD_NUMBER = 2;

  @Override
  public abstract ByteString toByteString();

  @Override
  public abstract CelType celType();

  abstract ProtoLiteCelValueConverter protoLiteCelValueConverter();

  @Override
  public String protoTypeName() {
    return celType().name();
  }

  @Override
  public WireMessageLite value() {
    return this;
  }

  @Override
  public final boolean equals(Object other) {
    // TODO: Support message equality
    throw new UnsupportedOperationException("Message equality is not supported");
  }

  @Override
  public final int hashCode() {
    throw new UnsupportedOperationException("Message equality is not supported");
  }

  @Override
  public final String toString() {
    return String.format(
        Locale.US,
        "WireMessageLite{protoTypeName=%s, size=%d}",
        protoTypeName(),
        toByteString().size());
  }

  @Memoized
  ImmutableListMultimap<Integer, Object> unknownFields() {
    try {
      CodedInputStream inputStream = toByteString().newCodedInput();
      Multimap<Integer, Object> fields = Multimaps.newMultimap(new TreeMap<>(), ArrayList::new);
      for (int tag = inputStream.readTag(); tag != 0; tag = inputStream.readTag()) {
        int tagWireType = WireFormat.getTagWireType(tag);
        int fieldNumber = WireFormat.getTagFieldNumber(tag);
        fields.put(
            fieldNumber, ProtoLiteCelValueConverter.readUnknownField(tagWireType, inputStream));
      }
      return ImmutableListMultimap.copyOf(fields);
    } catch (IOException e) {
      throw new IllegalStateException("Failed to parse raw proto message wire bytes", e);
    }
  }

  @Override
  public boolean isZeroValue() {
    return toByteString().isEmpty();
  }

  /**
   * Direct field selection by name is unsupported on {@link RawProtoMessageLiteValue} because raw
   * wire bytes lack message descriptors, and field names are not preserved on the protobuf wire.
   */
  @Override
  public Object select(String field) {
    throw newUnoptimizedFieldResolutionException(field);
  }

  /**
   * Direct field presence testing by name is unsupported on {@link RawProtoMessageLiteValue}
   * because raw wire bytes lack message descriptors and field names.
   */
  @Override
  public Optional<Object> find(String field) {
    throw newUnoptimizedFieldResolutionException(field);
  }

  private CelAttributeNotFoundException newUnoptimizedFieldResolutionException(String field) {
    return CelAttributeNotFoundException.of(
        String.format(
            "Error resolving field '%s' on '%s'. Field selection by name is not supported on raw"
                + " proto wire bytes; register its CelLiteDescriptor or enable SelectOptimizer.",
            field, celType().name()));
  }

  @Override
  public Object selectByFieldNumber(SelectField field) {
    return selectWireOrDefault(
        field, unknownFields().get(field.fieldNumber()), protoLiteCelValueConverter());
  }

  @Override
  public boolean hasFieldByNumber(SelectField field) {
    return isPresentInWire(field, unknownFields().get(field.fieldNumber()));
  }

  @Override
  public Optional<Object> findByFieldNumber(SelectField field) {
    return navigateWire(
        field, unknownFields().get(field.fieldNumber()), protoLiteCelValueConverter());
  }

  /**
   * Decodes a field value from preserved wire bytes, falling back to default values.
   *
   * <p>Package-private: shared with {@code ProtoMessageLiteValue} for unknown field resolution.
   */
  static Object selectWireOrDefault(
      SelectField field, ImmutableList<Object> unknowns, ProtoLiteCelValueConverter converter) {
    if (unknowns.isEmpty()) {
      return resolveDefault(field, converter);
    }
    return decodeWireField(field, unknowns, converter);
  }

  private static Object decodeWireField(
      SelectField field, ImmutableList<Object> unknowns, ProtoLiteCelValueConverter converter) {
    SelectField.MapEntrySpec mapEntrySpec = field.mapEntrySpec();
    if (mapEntrySpec != null) {
      return decodeMapEntries(
          unknowns, mapEntrySpec, field.protoTypeName(), field.fieldName(), converter);
    }

    int typeCode = field.typeCode();
    if (typeCode == SelectField.NO_TYPE_CODE) {
      throw CelAttributeNotFoundException.forFieldResolution(field.fieldName());
    }

    boolean isRepeated = field.defaultValue() instanceof List;

    return decodeWireEntries(unknowns, typeCode, field.protoTypeName(), isRepeated, converter);
  }

  private static Object resolveDefault(SelectField field, ProtoLiteCelValueConverter converter) {
    if (field.defaultValue() != null) {
      return field.defaultValue();
    }
    return decodeMessageValue(ByteString.EMPTY, field.protoTypeName(), converter);
  }

  /**
   * Returns whether a field has presence in preserved wire bytes.
   *
   * <p>Package-private: shared with {@code ProtoMessageLiteValue} for unknown field resolution.
   */
  static boolean isPresentInWire(SelectField field, ImmutableList<Object> unknowns) {
    if (unknowns.isEmpty()) {
      return false;
    }

    boolean isRepeated = field.defaultValue() instanceof List;
    int typeCode = field.typeCode();

    // In protobuf wire format, a zero-length entry for a singular field (e.g. empty string,
    // bytes, or empty submessage) represents explicit presence on the wire. Only packed repeated
    // fields with empty payload represent an empty/absent collection.
    if (!isRepeated) {
      return true;
    }

    boolean isPackable =
        typeCode != FieldLiteDescriptor.Type.STRING.getNumber()
            && typeCode != FieldLiteDescriptor.Type.BYTES.getNumber()
            && typeCode != FieldLiteDescriptor.Type.MESSAGE.getNumber()
            && typeCode != FieldLiteDescriptor.Type.GROUP.getNumber();
    if (!isPackable) {
      return true;
    }

    for (Object raw : unknowns) {
      if (!(raw instanceof ByteString) || !((ByteString) raw).isEmpty()) {
        return true;
      }
    }
    return false;
  }

  /**
   * Navigates a field on preserved wire bytes, returning empty if absent.
   *
   * <p>Package-private: shared with {@code ProtoMessageLiteValue} for unknown field resolution.
   */
  static Optional<Object> navigateWire(
      SelectField field, ImmutableList<Object> unknowns, ProtoLiteCelValueConverter converter) {
    if (!isPresentInWire(field, unknowns)) {
      return Optional.empty();
    }
    if (field.typeCode() != SelectField.NO_TYPE_CODE) {
      return Optional.of(selectWireOrDefault(field, unknowns, converter));
    }
    Object lastEntry = unknowns.get(unknowns.size() - 1);
    if (lastEntry instanceof ByteString) {
      return Optional.of(
          decodeWireEntries(
              unknowns,
              FieldLiteDescriptor.Type.MESSAGE.getNumber(),
              UNKNOWN_MESSAGE_TYPE_NAME,
              /* isRepeated= */ false,
              converter));
    }
    return Optional.of(lastEntry);
  }

  /**
   * Decodes map entries from wire bytes without descriptors (version skew), using the
   * optimizer-provided {@link SelectField.MapEntrySpec}. Per protobuf semantics, repeated key or
   * value tags within an entry are last-one-wins for scalars and merged for messages, and absent
   * keys or values take their type's default.
   */
  private static ImmutableMap<Object, Object> decodeMapEntries(
      ImmutableList<Object> unknowns,
      SelectField.MapEntrySpec spec,
      String valueProtoTypeName,
      String fieldName,
      ProtoLiteCelValueConverter converter) {
    ImmutableMap.Builder<Object, Object> mapBuilder = ImmutableMap.builder();
    try {
      for (Object raw : unknowns) {
        ByteString bytes = requireType(raw, ByteString.class, WireFormat.FieldType.MESSAGE);
        mapBuilder.put(
            decodeSingleMapEntry(bytes.newCodedInput(), spec, valueProtoTypeName, converter));
      }
    } catch (IOException e) {
      throw new IllegalArgumentException("Failed to decode map entry for field: " + fieldName, e);
    }
    return mapBuilder.buildKeepingLast();
  }

  private static Map.Entry<Object, Object> decodeSingleMapEntry(
      CodedInputStream in,
      SelectField.MapEntrySpec spec,
      String valueProtoTypeName,
      ProtoLiteCelValueConverter converter)
      throws IOException {
    WireFormat.FieldType keyWireType =
        FieldLiteDescriptor.Type.forNumber(spec.keyTypeCode()).toWireFormatFieldType();
    WireFormat.FieldType valWireType =
        FieldLiteDescriptor.Type.forNumber(spec.valueTypeCode()).toWireFormatFieldType();
    boolean isMessageValue = valWireType == WireFormat.FieldType.MESSAGE;
    Object key = resolveDefaultMapScalarValue(spec.keyTypeCode());
    Object value =
        isMessageValue ? ByteString.EMPTY : resolveDefaultMapScalarValue(spec.valueTypeCode());
    for (int tag = in.readTag(); tag != 0; tag = in.readTag()) {
      int tagWireType = WireFormat.getTagWireType(tag);
      int fieldNumber = WireFormat.getTagFieldNumber(tag);
      Object parsedValue = ProtoLiteCelValueConverter.readUnknownField(tagWireType, in);
      switch (fieldNumber) {
        case MAP_KEY_FIELD_NUMBER:
          key = decodeWireValue(parsedValue, keyWireType, /* protoTypeName= */ "", converter);
          break;
        case MAP_VALUE_FIELD_NUMBER:
          value =
              isMessageValue
                  ? ((ByteString) value)
                      .concat(requireType(parsedValue, ByteString.class, valWireType))
                  : decodeWireValue(parsedValue, valWireType, /* protoTypeName= */ "", converter);
          break;
        default:
          throw new IllegalStateException("Unexpected field number in map entry: " + fieldNumber);
      }
    }
    if (isMessageValue) {
      value = decodeWireValue(value, valWireType, valueProtoTypeName, converter);
    }
    return new AbstractMap.SimpleImmutableEntry<>(key, value);
  }

  private static Object resolveDefaultMapScalarValue(int typeCode) {
    FieldLiteDescriptor.Type protoType = FieldLiteDescriptor.Type.forNumber(typeCode);
    switch (protoType) {
      case BOOL:
        return false;
      case INT32:
      case INT64:
      case SINT32:
      case SINT64:
      case SFIXED32:
      case SFIXED64:
      case ENUM:
        return 0L;
      case UINT32:
      case UINT64:
      case FIXED32:
      case FIXED64:
        return UnsignedLong.ZERO;
      case FLOAT:
      case DOUBLE:
        return 0.0d;
      case STRING:
        return "";
      case BYTES:
        return CelByteString.EMPTY;
      default:
        throw new IllegalArgumentException("Unsupported map scalar type code: " + typeCode);
    }
  }

  static @Nullable Object decodeWireEntries(
      ImmutableCollection<Object> entries,
      int typeCode,
      String protoTypeName,
      boolean isRepeated,
      ProtoLiteCelValueConverter converter) {
    WireFormat.FieldType fieldType =
        FieldLiteDescriptor.Type.forNumber(typeCode).toWireFormatFieldType();
    if (fieldType == WireFormat.FieldType.GROUP) {
      throw new UnsupportedOperationException("Groups are not supported");
    }
    if (entries.isEmpty()) {
      return isRepeated ? ImmutableList.of() : null;
    }
    if (isRepeated) {
      ImmutableList.Builder<Object> listBuilder = ImmutableList.builder();
      for (Object raw : entries) {
        if (fieldType.isPackable() && (raw instanceof ByteString)) {
          listBuilder.addAll(decodePacked((ByteString) raw, fieldType));
        } else {
          listBuilder.add(decodeWireValue(raw, fieldType, protoTypeName, converter));
        }
      }
      return listBuilder.build();
    }
    if (fieldType == WireFormat.FieldType.MESSAGE) {
      ByteString mergedBytes = ByteString.EMPTY;
      for (Object item : entries) {
        mergedBytes = mergedBytes.concat(requireType(item, ByteString.class, fieldType));
      }
      return decodeWireValue(mergedBytes, fieldType, protoTypeName, converter);
    }
    // Protobuf "last one wins" semantics for non-repeated scalar fields
    return decodeWireValue(Iterables.getLast(entries), fieldType, protoTypeName, converter);
  }

  static Object decodeWireValue(
      Object raw,
      WireFormat.FieldType fieldType,
      String protoTypeName,
      ProtoLiteCelValueConverter converter) {
    switch (fieldType) {
      case DOUBLE:
        return Double.longBitsToDouble(requireType(raw, Long.class, fieldType));
      case FLOAT:
        return (double) Float.intBitsToFloat(requireType(raw, Integer.class, fieldType));
      case INT64:
      case SFIXED64:
        return requireType(raw, Long.class, fieldType);
      case INT32:
      case ENUM:
        return (long) requireType(raw, Long.class, fieldType).intValue();
      case UINT64:
      case FIXED64:
        return UnsignedLong.fromLongBits(requireType(raw, Long.class, fieldType));
      case FIXED32:
        return UnsignedLong.fromLongBits(
            Integer.toUnsignedLong(requireType(raw, Integer.class, fieldType)));
      case BOOL:
        return requireType(raw, Long.class, fieldType) != 0L;
      case STRING:
        ByteString stringBytes = requireType(raw, ByteString.class, fieldType);
        if (!stringBytes.isValidUtf8()) {
          throw new IllegalArgumentException("Invalid UTF-8 in string field");
        }
        return stringBytes.toStringUtf8();
      case GROUP:
        throw new UnsupportedOperationException("Groups are not supported");
      case MESSAGE:
        ByteString msgBytes = requireType(raw, ByteString.class, fieldType);
        return decodeMessageValue(msgBytes, protoTypeName, converter);
      case BYTES:
        return CelByteString.of(requireType(raw, ByteString.class, fieldType).toByteArray());
      case UINT32:
        return UnsignedLong.fromLongBits(requireType(raw, Long.class, fieldType) & 0xFFFFFFFFL);
      case SFIXED32:
        return (long) requireType(raw, Integer.class, fieldType);
      case SINT32:
        return (long)
            CodedInputStream.decodeZigZag32(requireType(raw, Long.class, fieldType).intValue());
      case SINT64:
        return CodedInputStream.decodeZigZag64(requireType(raw, Long.class, fieldType));
    }
    throw new IllegalArgumentException("Unsupported proto field type: " + fieldType);
  }

  private static Object decodeMessageValue(
      ByteString msgBytes, String protoTypeName, ProtoLiteCelValueConverter converter) {
    return converter
        .tryDecodeProtoMessage(msgBytes, protoTypeName)
        .orElseGet(() -> create(msgBytes, protoTypeName, converter));
  }

  private static <T> T requireType(
      Object raw, Class<T> expectedType, WireFormat.FieldType fieldType) {
    if (!expectedType.isInstance(raw)) {
      throw new IllegalArgumentException(
          String.format(
              "Expected %s for wire type %s, but got: %s",
              expectedType.getSimpleName(),
              fieldType,
              raw != null ? raw.getClass().getName() : "null"));
    }
    return expectedType.cast(raw);
  }

  private static ImmutableList<Object> decodePacked(
      ByteString bytes, WireFormat.FieldType fieldType) {
    try {
      CodedInputStream in = bytes.newCodedInput();
      ImmutableList.Builder<Object> builder = ImmutableList.builder();
      while (!in.isAtEnd()) {
        switch (fieldType) {
          case DOUBLE:
            builder.add(Double.longBitsToDouble(in.readFixed64()));
            break;
          case FLOAT:
            builder.add((double) Float.intBitsToFloat(in.readFixed32()));
            break;
          case INT64:
            builder.add(in.readInt64());
            break;
          case UINT64:
            builder.add(UnsignedLong.fromLongBits(in.readUInt64()));
            break;
          case INT32:
            builder.add((long) in.readInt32());
            break;
          case FIXED64:
            builder.add(UnsignedLong.fromLongBits(in.readFixed64()));
            break;
          case FIXED32:
            builder.add(UnsignedLong.fromLongBits(Integer.toUnsignedLong(in.readFixed32())));
            break;
          case BOOL:
            builder.add(in.readBool());
            break;
          case UINT32:
            builder.add(UnsignedLong.fromLongBits(Integer.toUnsignedLong(in.readUInt32())));
            break;
          case ENUM:
            builder.add((long) in.readEnum());
            break;
          case SFIXED32:
            builder.add((long) in.readSFixed32());
            break;
          case SFIXED64:
            builder.add(in.readSFixed64());
            break;
          case SINT32:
            builder.add((long) in.readSInt32());
            break;
          case SINT64:
            builder.add(in.readSInt64());
            break;
          default:
            throw new IllegalArgumentException("Unsupported packed proto field type: " + fieldType);
        }
      }
      return builder.build();
    } catch (IOException e) {
      throw new IllegalStateException("Failed to parse packed repeated field", e);
    }
  }

  static RawProtoMessageLiteValue create(
      ByteString rawWireBytes, ProtoLiteCelValueConverter protoLiteCelValueConverter) {
    return create(rawWireBytes, "", protoLiteCelValueConverter);
  }

  static RawProtoMessageLiteValue create(
      ByteString rawWireBytes,
      String protoTypeName,
      ProtoLiteCelValueConverter protoLiteCelValueConverter) {
    checkNotNull(rawWireBytes);
    checkNotNull(protoTypeName);
    checkNotNull(protoLiteCelValueConverter);
    return new AutoValue_RawProtoMessageLiteValue(
        rawWireBytes,
        StructTypeReference.create(
            protoTypeName.isEmpty() ? UNKNOWN_MESSAGE_TYPE_NAME : protoTypeName),
        protoLiteCelValueConverter);
  }

  RawProtoMessageLiteValue() {}
}
