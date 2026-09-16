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
import com.google.common.annotations.VisibleForTesting;
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
import com.google.protobuf.MessageLite;
import com.google.protobuf.WireFormat;
import dev.cel.common.annotations.Internal;
import dev.cel.common.exceptions.CelAttributeNotFoundException;
import dev.cel.common.types.CelType;
import dev.cel.common.types.StructTypeReference;
import dev.cel.protobuf.CelLiteDescriptor.FieldLiteDescriptor;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

/**
 * RawProtoMessageLiteValue enables descriptorless evaluation of protobuf messages to address
 * client-server version skew issues where newer fields or submessages lack generated classes and
 * descriptors in the evaluation environment.
 *
 * <p>Rather than requiring compiled {@link MessageLite} classes or runtime schema descriptors, this
 * value encapsulates the raw wire-format {@link ByteString} payload and performs classless,
 * reflection-free field traversal directly over wire tags via {@link CodedInputStream}.
 */
@AutoValue
@AutoValue.CopyAnnotations
@Immutable
@SuppressWarnings("Immutable") // Immutable wire fields
@Internal
public abstract class RawProtoMessageLiteValue extends StructValue<String, RawProtoMessageLiteValue>
    implements OptimizedSelectable {

  static final String UNKNOWN_MESSAGE_TYPE_NAME = "cel.@unknownMessage";
  static final int CEL_MAP_TYPE_CODE = -1;

  abstract ByteString rawWireBytes();

  @Override
  public abstract CelType celType();

  abstract ProtoLiteCelValueConverter protoLiteCelValueConverter();

  @Override
  public RawProtoMessageLiteValue value() {
    return this;
  }

  @Memoized
  ImmutableListMultimap<Integer, Object> unknownFields() {
    try {
      CodedInputStream inputStream = rawWireBytes().newCodedInput();
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

  boolean hasField(int fieldNumber) {
    return unknownFields().containsKey(fieldNumber);
  }

  @Override
  public boolean isZeroValue() {
    return rawWireBytes().isEmpty();
  }

  /**
   * Direct field selection by name is unsupported on {@link RawProtoMessageLiteValue} because raw
   * wire bytes lack message descriptors, and field names are not preserved on the protobuf wire.
   *
   * <p>Field traversal on classless messages must be performed via optimized attribute steps
   * ({@code cel.@attribute} and {@code cel.@hasField}), where the AST optimizer supplies the
   * pre-resolved protobuf field numbers.
   *
   * @throws CelAttributeNotFoundException always, indicating the field cannot be resolved by name.
   */
  @Override
  public Object select(String field) {
    throw CelAttributeNotFoundException.forFieldResolution(field);
  }

  @Override
  public Optional<Object> find(String field) {
    return Optional.empty();
  }

  @Override
  public Object selectField(SelectField field) {
    int fieldNumber = field.fieldNumber();
    Optional<FieldLiteDescriptor> fieldDescriptor =
        protoLiteCelValueConverter().findFieldDescriptor(celType().name(), fieldNumber);
    return selectUnknownOrDefault(
        field, fieldDescriptor, unknownFields().get(fieldNumber), protoLiteCelValueConverter());
  }

  @Override
  public boolean hasField(SelectField field) {
    int fieldNumber = field.fieldNumber();
    Optional<FieldLiteDescriptor> fieldDescriptor =
        protoLiteCelValueConverter().findFieldDescriptor(celType().name(), fieldNumber);
    return isPresentInUnknowns(
        field, fieldDescriptor, unknownFields().get(fieldNumber), protoLiteCelValueConverter());
  }

  @Override
  public @Nullable Object navigateField(SelectField field) {
    int fieldNumber = field.fieldNumber();
    Optional<FieldLiteDescriptor> fieldDescriptor =
        protoLiteCelValueConverter().findFieldDescriptor(celType().name(), fieldNumber);
    return navigateUnknown(
        field, fieldDescriptor, unknownFields().get(fieldNumber), protoLiteCelValueConverter());
  }

  static Object selectUnknownOrDefault(
      SelectField field,
      Optional<FieldLiteDescriptor> fieldDescriptor,
      ImmutableList<Object> unknowns,
      ProtoLiteCelValueConverter converter) {
    if (!unknowns.isEmpty()) {
      if (fieldDescriptor.isPresent()
          && fieldDescriptor.get().getEncodingType() == FieldLiteDescriptor.EncodingType.MAP) {
        return decodeMapEntries(unknowns, fieldDescriptor.get(), converter);
      }
      int typeCode;
      boolean isRepeated;
      String protoTypeName;
      if (fieldDescriptor.isPresent()) {
        FieldLiteDescriptor fd = fieldDescriptor.get();
        typeCode = fd.getProtoFieldType().getNumber();
        isRepeated = fd.getEncodingType() == FieldLiteDescriptor.EncodingType.LIST;
        protoTypeName = fd.getFieldProtoTypeName();
      } else {
        typeCode = field.typeCode();
        isRepeated = field.defaultValue() instanceof List;
        protoTypeName = UNKNOWN_MESSAGE_TYPE_NAME;
      }
      if (typeCode == SelectField.CEL_MAP_TYPE_CODE) {
        throw new UnsupportedOperationException(
            "Decoding unknown map field from wire bytes is unsupported: " + field.fieldName());
      }
      return decodeWireEntries(unknowns, typeCode, protoTypeName, isRepeated, converter);
    }

    if (field.defaultValue() != null) {
      return field.defaultValue();
    }

    Optional<Object> typeDefault = fieldDescriptor.flatMap(converter::findDefaultCelValue);
    if (typeDefault.isPresent()) {
      return typeDefault.get();
    }

    int typeCode =
        fieldDescriptor.map(d -> d.getProtoFieldType().getNumber()).orElse(field.typeCode());
    String protoTypeName =
        fieldDescriptor
            .map(FieldLiteDescriptor::getFieldProtoTypeName)
            .orElse(UNKNOWN_MESSAGE_TYPE_NAME);

    if (typeCode == FieldLiteDescriptor.Type.MESSAGE.getNumber()) {
      Optional<Object> wellKnown =
          converter.tryDecodeWellKnownProto(ByteString.EMPTY, protoTypeName);
      if (wellKnown.isPresent()) {
        return wellKnown.get();
      }
      return create(ByteString.EMPTY, protoTypeName, converter);
    }

    throw CelAttributeNotFoundException.forFieldResolution(field.fieldName());
  }

  static boolean isPresentInUnknowns(
      SelectField field,
      Optional<FieldLiteDescriptor> fieldDescriptor,
      ImmutableList<Object> unknowns,
      ProtoLiteCelValueConverter converter) {
    if (unknowns.isEmpty()) {
      return false;
    }
    boolean isRepeated;
    int typeCode;
    if (fieldDescriptor.isPresent()) {
      FieldLiteDescriptor fd = fieldDescriptor.get();
      isRepeated = fd.getEncodingType() == FieldLiteDescriptor.EncodingType.LIST;
      typeCode = fd.getProtoFieldType().getNumber();
    } else {
      isRepeated = field.defaultValue() instanceof List;
      typeCode = field.typeCode();
    }

    if (isRepeated) {
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
    return true;
  }

  static @Nullable Object navigateUnknown(
      SelectField field,
      Optional<FieldLiteDescriptor> fieldDescriptor,
      ImmutableList<Object> unknowns,
      ProtoLiteCelValueConverter converter) {
    if (!isPresentInUnknowns(field, fieldDescriptor, unknowns, converter)) {
      return null;
    }
    if (fieldDescriptor.isPresent() || field.typeCode() != SelectField.NO_TYPE_CODE) {
      return selectUnknownOrDefault(field, fieldDescriptor, unknowns, converter);
    }
    Object lastEntry = unknowns.get(unknowns.size() - 1);
    if (lastEntry instanceof ByteString) {
      return decodeWireEntries(
          unknowns,
          FieldLiteDescriptor.Type.MESSAGE.getNumber(),
          UNKNOWN_MESSAGE_TYPE_NAME,
          /* isRepeated= */ false,
          converter);
    }
    return lastEntry;
  }

  private static ImmutableMap<Object, Object> decodeMapEntries(
      ImmutableList<Object> unknowns,
      FieldLiteDescriptor mapFieldDescriptor,
      ProtoLiteCelValueConverter converter) {
    String entryTypeName = mapFieldDescriptor.getFieldProtoTypeName();
    Map<Object, Object> resultMap = new LinkedHashMap<>();
    for (Object raw : unknowns) {
      ByteString bytes = requireType(raw, ByteString.class, WireFormat.FieldType.MESSAGE);
      try {
        ImmutableMap<String, Object> entryFields =
            converter.readAllFields(bytes.toByteArray(), entryTypeName).values();
        Object key = entryFields.get("key");
        if (key == null) {
          key = converter.getDefaultCelValue(entryTypeName, "key");
        } else {
          key = converter.toRuntimeValue(key);
        }
        Object value = entryFields.get("value");
        if (value == null) {
          value = converter.getDefaultCelValue(entryTypeName, "value");
        } else {
          value = converter.toRuntimeValue(value);
        }
        resultMap.put(key, value);
      } catch (IOException e) {
        throw new IllegalArgumentException(
            "Failed to decode map entry for field: " + mapFieldDescriptor.getFieldName(), e);
      }
    }
    return ImmutableMap.copyOf(resultMap);
  }

  @VisibleForTesting
  static @Nullable Object decodeWireEntries(
      ImmutableCollection<Object> entries, int typeCode, String protoTypeName, boolean isRepeated) {
    return decodeWireEntries(
        entries, typeCode, protoTypeName, isRepeated, ProtoLiteCelValueConverter.newInstance());
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

  @VisibleForTesting
  static Object decodeWireValue(Object raw, int typeCode, String protoTypeName) {
    return decodeWireValue(
        raw, FieldLiteDescriptor.Type.forNumber(typeCode).toWireFormatFieldType(), protoTypeName);
  }

  @VisibleForTesting
  static Object decodeWireValue(Object raw, WireFormat.FieldType fieldType, String protoTypeName) {
    return decodeWireValue(raw, fieldType, protoTypeName, ProtoLiteCelValueConverter.newInstance());
  }

  private static Object decodeWireValue(
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
        Optional<Object> wellKnown = converter.tryDecodeWellKnownProto(msgBytes, protoTypeName);
        if (wellKnown.isPresent()) {
          return wellKnown.get();
        }
        return RawProtoMessageLiteValue.create(msgBytes, protoTypeName, converter);
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

  public static RawProtoMessageLiteValue create(ByteString rawWireBytes) {
    return create(rawWireBytes, "", ProtoLiteCelValueConverter.newInstance());
  }

  public static RawProtoMessageLiteValue create(ByteString rawWireBytes, String protoTypeName) {
    return create(rawWireBytes, protoTypeName, ProtoLiteCelValueConverter.newInstance());
  }

  public static RawProtoMessageLiteValue create(
      ByteString rawWireBytes,
      String protoTypeName,
      ProtoLiteCelValueConverter protoLiteCelValueConverter) {
    checkNotNull(rawWireBytes);
    checkNotNull(protoTypeName);
    checkNotNull(protoLiteCelValueConverter);
    return new AutoValue_RawProtoMessageLiteValue(
        rawWireBytes, StructTypeReference.create(protoTypeName), protoLiteCelValueConverter);
  }

  RawProtoMessageLiteValue() {}
}
