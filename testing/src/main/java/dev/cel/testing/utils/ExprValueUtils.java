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
package dev.cel.testing.utils;

import dev.cel.expr.ExprValue;
import dev.cel.expr.ListValue;
import dev.cel.expr.MapValue;
import dev.cel.expr.UnknownSet;
import dev.cel.expr.Value;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.primitives.UnsignedLong;
import com.google.protobuf.Any;
import com.google.protobuf.ByteString;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.ExtensionRegistry;
import com.google.protobuf.Message;
import com.google.protobuf.NullValue;
import com.google.protobuf.TypeRegistry;
import dev.cel.common.CelDescriptors;
import dev.cel.common.internal.DefaultInstanceMessageFactory;
import dev.cel.common.internal.ProtoTimeUtils;
import dev.cel.common.types.CelType;
import dev.cel.common.types.ListType;
import dev.cel.common.types.MapType;
import dev.cel.common.types.OptionalType;
import dev.cel.common.types.SimpleType;
import dev.cel.common.types.TypeType;
import dev.cel.common.values.CelByteString;
import dev.cel.runtime.CelUnknownSet;
import dev.cel.testing.testrunner.RegistryUtils;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Utility class for ExprValue and Value type conversions during test execution. */
@SuppressWarnings({"UnnecessarilyFullyQualified"})
public final class ExprValueUtils {

  private ExprValueUtils() {}

  /**
   * Converts a {@link Value} to a Java native object using the given file descriptor set to parse
   * `Any` messages.
   *
   * @param value The {@link Value} to convert.
   * @return The converted Java object.
   * @throws IOException If there's an error during conversion.
   */
  public static Object fromValue(Value value, CelDescriptors descriptors) throws IOException {
    TypeRegistry typeRegistry = RegistryUtils.getTypeRegistry(descriptors);
    ExtensionRegistry extensionRegistry = RegistryUtils.getExtensionRegistry(descriptors);
    return fromValue(value, typeRegistry, extensionRegistry);
  }

  public static Object fromValue(Value value, String fileDescriptorSetPath) throws IOException {
    return fromValue(value, ProtoDescriptorUtils.getDescriptorsFromFile(fileDescriptorSetPath));
  }

  /**
   * Converts a {@link Value} to a Java native object using custom registries.
   *
   * @param value The {@link Value} to convert.
   * @param typeRegistry The type registry to use for object resolution.
   * @param extensionRegistry The extension registry to use for object resolution.
   * @return The converted Java object.
   * @throws IOException If there's an error during conversion.
   */
  public static Object fromValue(
      Value value, TypeRegistry typeRegistry, ExtensionRegistry extensionRegistry)
      throws IOException {
    if (value.getKindCase().equals(Value.KindCase.OBJECT_VALUE)) {
      Descriptor descriptor =
          typeRegistry.getDescriptorForTypeUrl(value.getObjectValue().getTypeUrl());
      if (descriptor == null) {
        throw new IOException(
            "Unknown type, descriptor was not found in registry: "
                + value.getObjectValue().getTypeUrl());
      }
      Message prototype =
          DefaultInstanceMessageFactory.getInstance()
              .getPrototype(descriptor)
              .orElseGet(() -> DynamicMessage.getDefaultInstance(descriptor));
      return prototype
          .getParserForType()
          .parseFrom(value.getObjectValue().getValue(), extensionRegistry);
    }
    return toNativeObject(value, typeRegistry, extensionRegistry);
  }

  private static Object toNativeObject(
      Value value, TypeRegistry typeRegistry, ExtensionRegistry extensionRegistry)
      throws IOException {
    switch (value.getKindCase()) {
      case NULL_VALUE:
        return dev.cel.common.values.NullValue.NULL_VALUE;
      case BOOL_VALUE:
        return value.getBoolValue();
      case INT64_VALUE:
        return value.getInt64Value();
      case UINT64_VALUE:
        return UnsignedLong.fromLongBits(value.getUint64Value());
      case DOUBLE_VALUE:
        return value.getDoubleValue();
      case STRING_VALUE:
        return value.getStringValue();
      case BYTES_VALUE:
        ByteString byteString = value.getBytesValue();
        return CelByteString.of(byteString.toByteArray());
      case ENUM_VALUE:
        return value.getEnumValue();
      case MAP_VALUE:
        {
          MapValue map = value.getMapValue();
          ImmutableMap.Builder<Object, Object> builder =
              ImmutableMap.builderWithExpectedSize(map.getEntriesCount());
          for (MapValue.Entry entry : map.getEntriesList()) {
            builder.put(
                fromValue(entry.getKey(), typeRegistry, extensionRegistry),
                fromValue(entry.getValue(), typeRegistry, extensionRegistry));
          }
          return builder.buildOrThrow();
        }
      case LIST_VALUE:
        {
          ListValue list = value.getListValue();
          ImmutableList.Builder<Object> builder =
              ImmutableList.builderWithExpectedSize(list.getValuesCount());
          for (Value element : list.getValuesList()) {
            builder.add(fromValue(element, typeRegistry, extensionRegistry));
          }
          return builder.build();
        }
      case TYPE_VALUE:
        return value.getTypeValue();
      default:
        throw new IllegalArgumentException(
            String.format("Unexpected binding value kind: %s", value.getKindCase()));
    }
  }

  /**
   * Converts a Java object to an {@link ExprValue}.
   *
   * @param object The Java object to convert.
   * @param type The {@link CelType} of the object.
   * @return The converted {@link ExprValue}.
   * @throws Exception If there's an error during conversion.
   */
  public static ExprValue toExprValue(Object object, CelType type) throws Exception {
    if (object instanceof ExprValue) {
      return (ExprValue) object;
    }
    if (object instanceof CelUnknownSet) {
      return ExprValue.newBuilder().setUnknown(toUnknownSet((CelUnknownSet) object)).build();
    }
    return ExprValue.newBuilder().setValue(toValue(object, type)).build();
  }

  public static UnknownSet toUnknownSet(CelUnknownSet unknownSet) {
    return UnknownSet.newBuilder().addAllExprs(unknownSet.unknownExprIds()).build();
  }

  /**
   * Converts a Java object to an {@link Value}.
   *
   * @param object The Java object to convert.
   * @param type The {@link CelType} of the object.
   * @return The converted {@link Value}.
   * @throws Exception If there's an error during conversion.
   */
  @SuppressWarnings("unchecked")
  public static Value toValue(Object object, CelType type) throws Exception {
    if (!(object instanceof Optional) && type instanceof OptionalType) {
      return toValue(object, type.parameters().get(0));
    }
    if (object == null || object.equals(NullValue.NULL_VALUE)) {
      object = dev.cel.common.values.NullValue.NULL_VALUE;
    }
    if (object instanceof dev.cel.expr.Value) {
      object =
          Value.parseFrom(
              ((dev.cel.expr.Value) object).toByteArray(),
              ExtensionRegistry.getEmptyRegistry());
    }
    if (object instanceof Value) {
      return (Value) object;
    }
    if (object instanceof dev.cel.common.values.NullValue) {
      return Value.newBuilder().setNullValue(NullValue.NULL_VALUE).build();
    }
    if (object instanceof Boolean) {
      return Value.newBuilder().setBoolValue((Boolean) object).build();
    }
    if (object instanceof UnsignedLong) {
      switch (type.kind()) {
        case UINT:
        case DYN:
        case ANY:
          return Value.newBuilder().setUint64Value(((UnsignedLong) object).longValue()).build();
        default:
          throw new IllegalArgumentException(String.format("Unexpected result type: %s", type));
      }
    }
    if (object instanceof Long) {
      switch (type.kind()) {
        case INT:
        case DYN:
        case ANY:
          return Value.newBuilder().setInt64Value((Long) object).build();
        case UINT:
          return Value.newBuilder().setUint64Value((Long) object).build();
        default:
          throw new IllegalArgumentException(String.format("Unexpected result type: %s", type));
      }
    }
    if (object instanceof Double) {
      return Value.newBuilder().setDoubleValue((Double) object).build();
    }
    if (object instanceof String) {
      switch (type.kind()) {
        case TYPE:
          return Value.newBuilder().setTypeValue((String) object).build();
        case STRING:
        case DYN:
        case ANY:
          return Value.newBuilder().setStringValue((String) object).build();
        default:
          throw new IllegalArgumentException(String.format("Unexpected result type: %s", type));
      }
    }
    if (object instanceof CelByteString) {
      return Value.newBuilder()
          .setBytesValue(ByteString.copyFrom(((CelByteString) object).toByteArray()))
          .build();
    }
    if (object instanceof List) {
      CelType elemType = type instanceof ListType ? ((ListType) type).elemType() : SimpleType.DYN;
      ListValue.Builder builder = ListValue.newBuilder();
      for (Object element : ((List<Object>) object)) {
        builder.addValues(toValue(element, elemType));
      }
      return Value.newBuilder().setListValue(builder.build()).build();
    }
    if (object instanceof Map) {
      CelType keyType = type instanceof MapType ? ((MapType) type).keyType() : SimpleType.DYN;
      CelType valueType = type instanceof MapType ? ((MapType) type).valueType() : SimpleType.DYN;
      MapValue.Builder builder = MapValue.newBuilder();
      for (Map.Entry<Object, Object> entry : ((Map<Object, Object>) object).entrySet()) {
        builder.addEntries(
            MapValue.Entry.newBuilder()
                .setKey(toValue(entry.getKey(), keyType))
                .setValue(toValue(entry.getValue(), valueType))
                .build());
      }
      return Value.newBuilder().setMapValue(builder.build()).build();
    }

    if (object instanceof Instant) {
      return Value.newBuilder()
          .setObjectValue(Any.pack(ProtoTimeUtils.toProtoTimestamp((Instant) object)))
          .build();
    }

    if (object instanceof Duration) {
      return Value.newBuilder()
          .setObjectValue(Any.pack(ProtoTimeUtils.toProtoDuration((Duration) object)))
          .build();
    }

    if (object instanceof Message) {
      return Value.newBuilder().setObjectValue(Any.pack((Message) object)).build();
    }
    if (object instanceof TypeType) {
      return Value.newBuilder().setTypeValue(((TypeType) object).containingTypeName()).build();
    }

    if (object instanceof Optional) {
      // TODO: Remove this once the ExprValue Native representation is added.
      if (!((Optional<?>) object).isPresent()) {
        return Value.getDefaultInstance();
      }
      return toValue(((Optional<?>) object).get(), type.parameters().get(0));
    }

    throw new IllegalArgumentException(
        String.format("Unexpected result type: %s", object.getClass()));
  }
}
