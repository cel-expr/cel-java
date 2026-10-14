// Copyright 2022 Google LLC
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

package dev.cel.common.types;

import static com.google.common.collect.ImmutableList.toImmutableList;

import com.google.api.expr.v1alpha1.Type;
import com.google.api.expr.v1alpha1.Type.PrimitiveType;
import com.google.api.expr.v1alpha1.Type.WellKnownType;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.protobuf.Empty;
import com.google.protobuf.NullValue;
import dev.cel.common.annotations.Internal;
import java.util.Optional;

/** Utility class for working with {@link Type}. */
public final class CelV1AlphaTypes {

  // Static types.
  public static final Type ERROR = Type.newBuilder().setError(Empty.getDefaultInstance()).build();
  public static final Type DYN = Type.newBuilder().setDyn(Empty.getDefaultInstance()).build();
  public static final Type NULL_TYPE = Type.newBuilder().setNull(NullValue.NULL_VALUE).build();
  public static final Type BOOL = create(PrimitiveType.BOOL);
  public static final Type BYTES = create(PrimitiveType.BYTES);
  public static final Type STRING = create(PrimitiveType.STRING);
  public static final Type DOUBLE = create(PrimitiveType.DOUBLE);
  public static final Type UINT64 = create(PrimitiveType.UINT64);
  public static final Type INT64 = create(PrimitiveType.INT64);
  public static final Type ANY = create(WellKnownType.ANY);
  public static final Type TIMESTAMP = create(WellKnownType.TIMESTAMP);
  public static final Type DURATION = create(WellKnownType.DURATION);

  static final ImmutableMap<CelKind, Type> SIMPLE_CEL_KIND_TO_TYPE =
      ImmutableMap.<CelKind, Type>builder()
          .put(CelKind.ERROR, CelV1AlphaTypes.ERROR)
          .put(CelKind.DYN, CelV1AlphaTypes.DYN)
          .put(CelKind.ANY, CelV1AlphaTypes.ANY)
          .put(CelKind.BOOL, CelV1AlphaTypes.BOOL)
          .put(CelKind.BYTES, CelV1AlphaTypes.BYTES)
          .put(CelKind.DOUBLE, CelV1AlphaTypes.DOUBLE)
          .put(CelKind.DURATION, CelV1AlphaTypes.DURATION)
          .put(CelKind.INT, CelV1AlphaTypes.INT64)
          .put(CelKind.NULL_TYPE, CelV1AlphaTypes.NULL_TYPE)
          .put(CelKind.STRING, CelV1AlphaTypes.STRING)
          .put(CelKind.TIMESTAMP, CelV1AlphaTypes.TIMESTAMP)
          .put(CelKind.UINT, CelV1AlphaTypes.UINT64)
          .buildOrThrow();

  private static final ImmutableMap<Type, CelType> PROTOBUF_TYPE_TO_CEL_TYPE_MAP =
      ImmutableMap.<Type, CelType>builder()
          .put(CelV1AlphaTypes.BOOL, SimpleType.BOOL)
          .put(CelV1AlphaTypes.BYTES, SimpleType.BYTES)
          .put(CelV1AlphaTypes.DOUBLE, SimpleType.DOUBLE)
          .put(CelV1AlphaTypes.INT64, SimpleType.INT)
          .put(CelV1AlphaTypes.STRING, SimpleType.STRING)
          .put(CelV1AlphaTypes.UINT64, SimpleType.UINT)
          .put(CelV1AlphaTypes.ANY, SimpleType.ANY)
          .put(CelV1AlphaTypes.DURATION, SimpleType.DURATION)
          .put(CelV1AlphaTypes.TIMESTAMP, SimpleType.TIMESTAMP)
          .put(CelV1AlphaTypes.DYN, SimpleType.DYN)
          .put(CelV1AlphaTypes.NULL_TYPE, SimpleType.NULL_TYPE)
          .put(CelV1AlphaTypes.ERROR, SimpleType.ERROR)
          .buildOrThrow();

  private CelV1AlphaTypes() {}

  /** Create a primitive {@code Type}. */
  public static Type create(PrimitiveType type) {
    return Type.newBuilder().setPrimitive(type).build();
  }

  /** Create a well-known {@code Type}. */
  public static Type create(WellKnownType type) {
    return Type.newBuilder().setWellKnown(type).build();
  }

  /** Create a type {@code Type}. */
  public static Type create(Type target) {
    return Type.newBuilder().setType(target).build();
  }

  /** Create a list with {@code elemType}. */
  public static Type createList(Type elemType) {
    return Type.newBuilder().setListType(Type.ListType.newBuilder().setElemType(elemType)).build();
  }

  /** Create a map with {@code keyType} and {@code valueType}. */
  public static Type createMap(Type keyType, Type valueType) {
    return Type.newBuilder()
        .setMapType(Type.MapType.newBuilder().setKeyType(keyType).setValueType(valueType))
        .build();
  }

  /** Create a message {@code Type} for {@code messageName}. */
  public static Type createMessage(String messageName) {
    return Type.newBuilder().setMessageType(messageName).build();
  }

  /** Create a type param {@code Type}. */
  public static Type createTypeParam(String name) {
    return Type.newBuilder().setTypeParam(name).build();
  }

  /** Create a wrapper type for the {@code primitive}. */
  public static Type createWrapper(PrimitiveType primitive) {
    return Type.newBuilder().setWrapper(primitive).build();
  }

  /** Create a wrapper type where the input is a {@code Type} of primitive types. */
  public static Type createWrapper(Type type) {
    Preconditions.checkArgument(type.getTypeKindCase() == Type.TypeKindCase.PRIMITIVE);
    return createWrapper(type.getPrimitive());
  }

  /** Converts a Protobuf type into CEL native type. */
  @Internal
  public static Type celTypeToType(CelType celType) {
    Type type = SIMPLE_CEL_KIND_TO_TYPE.get(celType.kind());
    if (type != null) {
      if (celType instanceof NullableType) {
        return CelV1AlphaTypes.createWrapper(type);
      }
      return type;
    }

    switch (celType.kind()) {
      case LIST:
        ListType listType = (ListType) celType;
        return CelV1AlphaTypes.createList(celTypeToType(listType.elemType()));
      case MAP:
        MapType mapType = (MapType) celType;
        return CelV1AlphaTypes.createMap(
            celTypeToType(mapType.keyType()), celTypeToType(mapType.valueType()));
      case OPAQUE:
        return Type.newBuilder()
            .setAbstractType(
                Type.AbstractType.newBuilder()
                    .setName(celType.name())
                    .addAllParameterTypes(
                        celType.parameters().stream()
                            .map(CelV1AlphaTypes::celTypeToType)
                            .collect(toImmutableList())))
            .build();
      case STRUCT:
        return CelV1AlphaTypes.createMessage(celType.name());
      case TYPE:
        TypeType typeType = (TypeType) celType;
        return CelV1AlphaTypes.create(celTypeToType(typeType.type()));
      case TYPE_PARAM:
        return CelV1AlphaTypes.createTypeParam(celType.name());
      default:
        throw new IllegalArgumentException(String.format("Unsupported type: %s", celType));
    }
  }

  /** Converts a Protobuf type to CEL native type. */
  @Internal
  public static CelType typeToCelType(Type type) {
    CelType celType = PROTOBUF_TYPE_TO_CEL_TYPE_MAP.get(type);
    if (celType != null) {
      return celType;
    }

    switch (type.getTypeKindCase()) {
      case WRAPPER:
        return NullableType.create(typeToCelType(CelV1AlphaTypes.create(type.getWrapper())));
      case MESSAGE_TYPE:
        return ProtoMessageType.create(
            type.getMessageType(), ImmutableSet.of(), (fieldName) -> Optional.empty());
      case LIST_TYPE:
        Type.ListType listType = type.getListType();
        return ListType.create(typeToCelType(listType.getElemType()));
      case MAP_TYPE:
        Type.MapType mapType = type.getMapType();
        return MapType.create(
            typeToCelType(mapType.getKeyType()), typeToCelType(mapType.getValueType()));
      case TYPE_PARAM:
        return TypeParamType.create(type.getTypeParam());
      case ABSTRACT_TYPE:
        Type.AbstractType abstractType = type.getAbstractType();
        ImmutableList<CelType> params =
            abstractType.getParameterTypesList().stream()
                .map(CelV1AlphaTypes::typeToCelType)
                .collect(toImmutableList());
        return OpaqueType.create(abstractType.getName(), params);
      case TYPE:
        return TypeType.create(typeToCelType(type.getType()));
      default:
        // Add more cases as needed.
        throw new IllegalArgumentException(String.format("Unsupported type: %s", type));
    }
  }
}
