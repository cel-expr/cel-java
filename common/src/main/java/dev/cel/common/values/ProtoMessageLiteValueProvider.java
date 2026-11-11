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

import static com.google.common.base.Preconditions.checkArgument;

import com.google.common.collect.ImmutableSet;
import com.google.common.primitives.UnsignedLong;
import com.google.errorprone.annotations.Immutable;
import com.google.protobuf.ByteString;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.MessageLite;
import dev.cel.common.CelOptions;
import dev.cel.common.annotations.Beta;
import dev.cel.common.internal.CelLiteDescriptorPool;
import dev.cel.common.internal.DefaultLiteDescriptorPool;
import dev.cel.common.internal.ProtoLiteAdapter;
import dev.cel.common.internal.ProtoTimeUtils;
import dev.cel.common.internal.WellKnownProto;
import dev.cel.protobuf.CelLiteDescriptor;
import dev.cel.protobuf.CelLiteDescriptor.FieldLiteDescriptor;
import dev.cel.protobuf.CelLiteDescriptor.FieldLiteDescriptor.EncodingType;
import dev.cel.protobuf.CelLiteDescriptor.MessageLiteDescriptor;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * {@code ProtoMessageLiteValueProvider} constructs new instances of protobuf lite-message given its
 * fully qualified name and its fields to populate.
 *
 * <p>A number outside a closed (proto2) enum's values is kept as an unknown field, so the field
 * reads back as its default.
 */
@Immutable
@Beta
public class ProtoMessageLiteValueProvider extends BaseProtoMessageValueProvider {
  // Smaller than CodedOutputStream's default of 4KB, as most messages and map entries are small.
  private static final int OUTPUT_BUFFER_SIZE = 256;

  private final CelLiteDescriptorPool descriptorPool;
  private final ProtoLiteCelValueConverter protoLiteCelValueConverter;
  private final ProtoLiteAdapter protoLiteAdapter;

  @Override
  public BaseProtoCelValueConverter protoCelValueConverter() {
    return protoLiteCelValueConverter;
  }

  @Override
  public Optional<Object> newValue(String structType, Map<String, Object> fields) {
    MessageLiteDescriptor descriptor = descriptorPool.findDescriptor(structType).orElse(null);
    // Map entries aren't messages in CEL, so they can't be created.
    if (descriptor == null || descriptor.isMapEntry()) {
      return Optional.empty();
    }

    // Encoded for the generated message's parser to read, so CelLiteDescriptor needs no setters.
    ByteString.Output bytes = ByteString.newOutput();
    CodedOutputStream output = CodedOutputStream.newInstance(bytes, OUTPUT_BUFFER_SIZE);
    try {
      for (Map.Entry<String, Object> field : fields.entrySet()) {
        FieldLiteDescriptor fd = descriptor.findByFieldName(field.getKey());
        if (fd == null) {
          throw new IllegalArgumentException(
              String.format(
                  "field '%s' is not declared in message '%s'", field.getKey(), structType));
        }
        try {
          writeField(output, fd, field.getValue());
        } catch (ClassCastException e) {
          throw new IllegalArgumentException(
              String.format(
                  "unexpected value type for field '%s' in message '%s'",
                  field.getKey(), structType),
              e);
        }
      }
      output.flush();
    } catch (IOException e) {
      // Unreachable, as the output is in memory.
      throw new AssertionError(e);
    }
    // Building, unlike parsing, fails on a missing proto2 required field.
    MessageLite message =
        ProtoLiteCelValueConverter.mergeMessageLite(
            descriptor.newMessageBuilder(), bytes.toByteString(), structType);
    ProtoTimeUtils.validateWellKnownProto(message);
    return Optional.of(protoLiteCelValueConverter.toRuntimeValue(message));
  }

  private void writeField(CodedOutputStream output, FieldLiteDescriptor fd, Object value)
      throws IOException {
    if (value instanceof NullValue) {
      String typeName = fd.getFieldProtoTypeName();
      if (fd.getEncodingType() != EncodingType.SINGULAR
          || fd.getProtoFieldType() != FieldLiteDescriptor.Type.MESSAGE
          || typeName.equals(WellKnownProto.JSON_STRUCT_VALUE.typeName())
          || typeName.equals(WellKnownProto.JSON_LIST_VALUE.typeName())) {
        throw new IllegalArgumentException(
            String.format("null cannot be assigned to field '%s'", fd.getFieldName()));
      }
      if (!holdsJsonNull(typeName)) {
        return;
      }
    }
    switch (fd.getEncodingType()) {
      case SINGULAR:
        writeValue(output, fd, value);
        break;
      case LIST:
        // Unpacked, which parsers accept for packed fields too.
        boolean keepsNulls = holdsJsonNull(fd.getFieldProtoTypeName());
        for (Object element : (List<?>) value) {
          if (keepsNulls || !(element instanceof NullValue)) {
            writeValue(output, fd, element);
          }
        }
        break;
      case MAP:
        writeMap(output, fd, (Map<?, ?>) value);
        break;
    }
  }

  private void writeMap(CodedOutputStream output, FieldLiteDescriptor fd, Map<?, ?> map)
      throws IOException {
    MessageLiteDescriptor entry = descriptorPool.getDescriptorOrThrow(fd.getFieldProtoTypeName());
    FieldLiteDescriptor keyFd =
        entry.getByFieldNumberOrThrow(ProtoLiteCelValueConverter.MAP_KEY_FIELD_NUMBER);
    FieldLiteDescriptor valueFd =
        entry.getByFieldNumberOrThrow(ProtoLiteCelValueConverter.MAP_VALUE_FIELD_NUMBER);
    boolean keepsNulls = holdsJsonNull(valueFd.getFieldProtoTypeName());
    // One stream encodes every entry, as each new one allocates a buffer.
    ByteString.Output entryBytes = ByteString.newOutput();
    CodedOutputStream entryOutput = CodedOutputStream.newInstance(entryBytes, OUTPUT_BUFFER_SIZE);
    for (Map.Entry<?, ?> mapEntry : map.entrySet()) {
      if (keepsNulls || !(mapEntry.getValue() instanceof NullValue)) {
        writeValue(entryOutput, keyFd, mapEntry.getKey());
        writeValue(entryOutput, valueFd, mapEntry.getValue());
        entryOutput.flush();
        output.writeBytes(fd.getFieldNumber(), entryBytes.toByteString());
        entryBytes.reset();
      }
    }
  }

  private void writeValue(CodedOutputStream output, FieldLiteDescriptor fd, Object value)
      throws IOException {
    int number = fd.getFieldNumber();
    switch (fd.getProtoFieldType()) {
      case MESSAGE:
        writeMessage(output, number, fd.getFieldProtoTypeName(), value);
        break;
      case DOUBLE:
        output.writeDouble(number, (Double) value);
        break;
      case FLOAT:
        output.writeFloat(number, ((Double) value).floatValue());
        break;
      case INT64:
        output.writeInt64(number, (Long) value);
        break;
      case SINT64:
        output.writeSInt64(number, (Long) value);
        break;
      case SFIXED64:
        output.writeSFixed64(number, (Long) value);
        break;
      case INT32:
      case ENUM:
        output.writeInt32(number, ProtoLiteAdapter.intCheckedCast((Long) value));
        break;
      case SINT32:
        output.writeSInt32(number, ProtoLiteAdapter.intCheckedCast((Long) value));
        break;
      case SFIXED32:
        output.writeSFixed32(number, ProtoLiteAdapter.intCheckedCast((Long) value));
        break;
      case UINT64:
        output.writeUInt64(number, ((UnsignedLong) value).longValue());
        break;
      case FIXED64:
        output.writeFixed64(number, ((UnsignedLong) value).longValue());
        break;
      case UINT32:
        output.writeUInt32(
            number, ProtoLiteAdapter.unsignedIntCheckedCast(((UnsignedLong) value).longValue()));
        break;
      case FIXED32:
        output.writeFixed32(
            number, ProtoLiteAdapter.unsignedIntCheckedCast(((UnsignedLong) value).longValue()));
        break;
      case BOOL:
        output.writeBool(number, (Boolean) value);
        break;
      case STRING:
        output.writeString(number, (String) value);
        break;
      case BYTES:
        output.writeByteArray(number, ((CelByteString) value).toByteArray());
        break;
      default:
        throw new UnsupportedOperationException("Groups are not supported");
    }
  }

  // MessageLite values are serialized straight into the output, without an intermediate ByteString.
  private void writeMessage(
      CodedOutputStream output, int number, String protoTypeName, Object value) throws IOException {
    WellKnownProto wellKnownProto = WellKnownProto.getByTypeName(protoTypeName).orElse(null);
    if (ProtoLiteCelValueConverter.isStructLike(wellKnownProto)) {
      // Fails closed on a message of another type, which only dyn or an unchecked AST can assign.
      String valueTypeName = protoTypeNameOf(value);
      checkArgument(
          valueTypeName.equals(protoTypeName),
          "expected a message of type '%s', got '%s'",
          protoTypeName,
          valueTypeName);
      if (value instanceof WireMessageLite) {
        output.writeBytes(number, ((WireMessageLite) value).toByteString());
      } else {
        output.writeMessage(number, (MessageLite) value);
      }
      return;
    }
    switch (wellKnownProto) {
      case ANY_VALUE:
        if (!(value instanceof MessageLite || value instanceof WireMessageLite)) {
          output.writeMessage(number, protoLiteAdapter.adaptValueToAny(value));
          return;
        }
        String valueTypeName = protoTypeNameOf(value);
        checkArgument(
            !valueTypeName.equals(RawProtoMessageLiteValue.UNKNOWN_MESSAGE_TYPE_NAME),
            "cannot pack a message of unknown type into google.protobuf.Any");
        output.writeMessage(number, ProtoLiteAdapter.packAny(toByteString(value), valueTypeName));
        return;
      case EMPTY:
        // Values of google.protobuf.Empty are empty maps in CEL.
        checkArgument(
            ((Map<?, ?>) value).isEmpty(), "expected an empty map for google.protobuf.Empty");
        output.writeBytes(number, ByteString.EMPTY);
        return;
      default:
        output.writeMessage(
            number, protoLiteAdapter.adaptValueToWellKnownProto(value, wellKnownProto));
    }
  }

  private String protoTypeNameOf(Object message) {
    if (message instanceof WireMessageLite) {
      return ((WireMessageLite) message).protoTypeName();
    }
    return descriptorPool
        .findDescriptor((MessageLite) message)
        .map(MessageLiteDescriptor::getProtoTypeName)
        .orElse(RawProtoMessageLiteValue.UNKNOWN_MESSAGE_TYPE_NAME);
  }

  private static ByteString toByteString(Object message) {
    return message instanceof WireMessageLite
        ? ((WireMessageLite) message).toByteString()
        : ((MessageLite) message).toByteString();
  }

  // Whether null is a JSON value of this type, rather than unsetting a field of it.
  private static boolean holdsJsonNull(String protoTypeName) {
    return protoTypeName.equals(WellKnownProto.ANY_VALUE.typeName())
        || protoTypeName.equals(WellKnownProto.JSON_VALUE.typeName());
  }

  public static ProtoMessageLiteValueProvider newInstance(CelLiteDescriptor... descriptors) {
    return newInstance(ImmutableSet.copyOf(descriptors));
  }

  public static ProtoMessageLiteValueProvider newInstance(Set<CelLiteDescriptor> descriptors) {
    return newInstance(CelOptions.DEFAULT, descriptors);
  }

  public static ProtoMessageLiteValueProvider newInstance(
      CelOptions celOptions, Set<CelLiteDescriptor> descriptors) {
    DefaultLiteDescriptorPool descriptorPool =
        DefaultLiteDescriptorPool.newInstance(ImmutableSet.copyOf(descriptors));
    ProtoLiteCelValueConverter protoLiteCelValueConverter =
        ProtoLiteCelValueConverter.newInstance(descriptorPool);
    return new ProtoMessageLiteValueProvider(
        protoLiteCelValueConverter, descriptorPool, celOptions);
  }

  private ProtoMessageLiteValueProvider(
      ProtoLiteCelValueConverter protoLiteCelValueConverter,
      CelLiteDescriptorPool descriptorPool,
      CelOptions celOptions) {
    this.protoLiteCelValueConverter = protoLiteCelValueConverter;
    this.descriptorPool = descriptorPool;
    this.protoLiteAdapter = new ProtoLiteAdapter(celOptions);
  }
}
