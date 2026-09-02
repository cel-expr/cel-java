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
 * RawProtoMessageLiteValue represents a submessage whose concrete Java class is missing from the
 * runtime environment (such as when client-server version skew introduces a new submessage).
 *
 * <p>Rather than generating or reflecting on a {@link com.google.protobuf.MessageLite} class, this
 * value wraps the raw wire-format {@link ByteString} payload and exposes classless, reflection-free
 * field traversal using {@link CodedInputStream}.
 */
@AutoValue
@AutoValue.CopyAnnotations
@Immutable
@SuppressWarnings("Immutable") // Immutable wire fields
@Internal
public abstract class RawProtoMessageLiteValue
    extends StructValue<String, RawProtoMessageLiteValue> {

  public abstract ByteString rawWireBytes();

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
    if (entries.isEmpty()) {
      return null;
    }
    WireFormat.FieldType fieldType =
        FieldLiteDescriptor.Type.forNumber(typeCode).toWireFormatFieldType();
    if (isRepeated) {
      // Packed repeated scalar check: single ByteString containing packed varints/fixed numbers
      if (entries.size() == 1
          && (entries.iterator().next() instanceof ByteString)
          && fieldType.isPackable()) {
        return decodePacked((ByteString) entries.iterator().next(), fieldType);
      }
      ImmutableList.Builder<Object> listBuilder = ImmutableList.builder();
      for (Object raw : entries) {
        listBuilder.add(decodeWireValue(raw, fieldType, protoTypeName));
      }
      return listBuilder.build();
    }
    // Protobuf "last one wins" semantics for non-repeated fields
    Object last = Iterables.getLast(entries, null);
    return decodeWireValue(last, fieldType, protoTypeName);
  }

  public static Object decodeWireValue(Object raw, int typeCode, String protoTypeName) {
    return decodeWireValue(
        raw, FieldLiteDescriptor.Type.forNumber(typeCode).toWireFormatFieldType(), protoTypeName);
  }

  public static Object decodeWireValue(
      Object raw, WireFormat.FieldType fieldType, String protoTypeName) {
    switch (fieldType) {
      case DOUBLE:
        return Double.longBitsToDouble((Long) raw);
      case FLOAT:
        return (double) Float.intBitsToFloat((Integer) raw);
      case INT64:
      case INT32:
      case SFIXED64:
      case ENUM:
        return raw;
      case UINT64:
      case FIXED64:
        return UnsignedLong.fromLongBits((Long) raw);
      case FIXED32:
        return UnsignedLong.fromLongBits(Integer.toUnsignedLong((Integer) raw));
      case BOOL:
        return ((Long) raw) != 0L;
      case STRING:
        return ((ByteString) raw).toStringUtf8();
      case GROUP:
      case MESSAGE:
        return RawProtoMessageLiteValue.create((ByteString) raw, protoTypeName);
      case BYTES:
        return CelByteString.of(((ByteString) raw).toByteArray());
      case UINT32:
        return UnsignedLong.fromLongBits(((Long) raw) & 0xFFFFFFFFL);
      case SFIXED32:
        return ((Integer) raw).longValue();
      case SINT32:
        return (long) CodedInputStream.decodeZigZag32((int) (long) (Long) raw);
      case SINT64:
        return CodedInputStream.decodeZigZag64((Long) raw);
    }
    throw new IllegalArgumentException("Unsupported proto field type: " + fieldType);
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
}
