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
import java.util.Optional;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

/**
 * RawProtoMessageLiteValue enables descriptorless evaluation of protobuf messages to address
 * client-server version skew issues where newer fields or submessages lack generated classes
 * and descriptors in the evaluation environment.
 *
 * <p>Rather than requiring compiled {@link MessageLite} classes or runtime schema descriptors,
 * this value encapsulates the raw wire-format {@link ByteString} payload and performs classless,
 * reflection-free field traversal directly over wire tags via {@link CodedInputStream}.
 */
@AutoValue
@AutoValue.CopyAnnotations
@Immutable
@SuppressWarnings("Immutable") // Immutable wire fields
@Internal
public abstract class RawProtoMessageLiteValue
    extends StructValue<String, RawProtoMessageLiteValue> {

  abstract ByteString rawWireBytes();

  @Override
  public RawProtoMessageLiteValue value() {
    return this;
  }

  @Override
  public abstract CelType celType();

  @Memoized
  public ImmutableListMultimap<Integer, Object> unknownFields() {
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

  public boolean hasField(int fieldNumber) {
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

  public static @Nullable Object decodeWireEntries(
      ImmutableCollection<Object> entries, int typeCode, String protoTypeName, boolean isRepeated) {
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
          listBuilder.add(decodeWireValue(raw, fieldType, protoTypeName));
        }
      }
      return listBuilder.build();
    }
    if (fieldType == WireFormat.FieldType.MESSAGE) {
      ByteString mergedBytes = ByteString.EMPTY;
      for (Object item : entries) {
        mergedBytes = mergedBytes.concat(requireType(item, ByteString.class, fieldType));
      }
      return decodeWireValue(mergedBytes, fieldType, protoTypeName);
    }
    // Protobuf "last one wins" semantics for non-repeated scalar fields
    return decodeWireValue(Iterables.getLast(entries), fieldType, protoTypeName);
  }

  static Object decodeWireValue(Object raw, int typeCode, String protoTypeName) {
    return decodeWireValue(
        raw, FieldLiteDescriptor.Type.forNumber(typeCode).toWireFormatFieldType(), protoTypeName);
  }

  static Object decodeWireValue(Object raw, WireFormat.FieldType fieldType, String protoTypeName) {
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
        return RawProtoMessageLiteValue.create(
            requireType(raw, ByteString.class, fieldType), protoTypeName);
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
    return create(rawWireBytes, "");
  }

  public static RawProtoMessageLiteValue create(ByteString rawWireBytes, String protoTypeName) {
    checkNotNull(rawWireBytes);
    checkNotNull(protoTypeName);
    return new AutoValue_RawProtoMessageLiteValue(
        rawWireBytes, StructTypeReference.create(protoTypeName));
  }

  RawProtoMessageLiteValue() {}
}
