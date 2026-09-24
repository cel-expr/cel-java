// Copyright 2023 Google LLC
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

package dev.cel.checker;

import dev.cel.expr.Type;
import dev.cel.expr.Type.PrimitiveType;
import dev.cel.expr.Type.TypeKindCase;
import dev.cel.expr.Type.WellKnownType;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.Empty;
import com.google.protobuf.NullValue;
import dev.cel.common.annotations.Internal;
import dev.cel.common.types.CelProtoTypes;
import dev.cel.common.types.CelType;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/**
 * Utilities for dealing with the {@link Type} proto.
 *
 * <p>CEL Library Internals. Do Not Use.
 */
@Internal
public final class Types {

  // Message type names with well-known type equivalents or special handling.
  public static final String ANY_MESSAGE = "google.protobuf.Any";
  public static final String DURATION_MESSAGE = "google.protobuf.Duration";
  public static final String LIST_VALUE_MESSAGE = "google.protobuf.ListValue";
  public static final String STRUCT_MESSAGE = "google.protobuf.Struct";
  public static final String TIMESTAMP_MESSAGE = "google.protobuf.Timestamp";
  public static final String VALUE_MESSAGE = "google.protobuf.Value";

  // Message names for wrapper types.
  public static final String BOOL_WRAPPER_MESSAGE = "google.protobuf.BoolValue";
  public static final String BYTES_WRAPPER_MESSAGE = "google.protobuf.BytesValue";
  public static final String DOUBLE_WRAPPER_MESSAGE = "google.protobuf.DoubleValue";
  public static final String FLOAT_WRAPPER_MESSAGE = "google.protobuf.FloatValue";
  public static final String INT32_WRAPPER_MESSAGE = "google.protobuf.Int32Value";
  public static final String INT64_WRAPPER_MESSAGE = "google.protobuf.Int64Value";
  public static final String STRING_WRAPPER_MESSAGE = "google.protobuf.StringValue";
  public static final String UINT32_WRAPPER_MESSAGE = "google.protobuf.UInt32Value";
  public static final String UINT64_WRAPPER_MESSAGE = "google.protobuf.UInt64Value";

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

  /** Map of well-known proto messages and their CEL {@code Type} equivalents. */
  public static final ImmutableMap<String, Type> WELL_KNOWN_TYPE_MAP =
      ImmutableMap.<String, Type>builder()
          .put(DOUBLE_WRAPPER_MESSAGE, Types.createWrapper(Types.DOUBLE))
          .put(FLOAT_WRAPPER_MESSAGE, Types.createWrapper(Types.DOUBLE))
          .put(INT64_WRAPPER_MESSAGE, Types.createWrapper(Types.INT64))
          .put(INT32_WRAPPER_MESSAGE, Types.createWrapper(Types.INT64))
          .put(UINT64_WRAPPER_MESSAGE, Types.createWrapper(Types.UINT64))
          .put(UINT32_WRAPPER_MESSAGE, Types.createWrapper(Types.UINT64))
          .put(BOOL_WRAPPER_MESSAGE, Types.createWrapper(Types.BOOL))
          .put(STRING_WRAPPER_MESSAGE, Types.createWrapper(Types.STRING))
          .put(BYTES_WRAPPER_MESSAGE, Types.createWrapper(Types.BYTES))
          .put(TIMESTAMP_MESSAGE, Types.TIMESTAMP)
          .put(DURATION_MESSAGE, Types.DURATION)
          .put(STRUCT_MESSAGE, Types.createMap(Types.STRING, Types.DYN))
          .put(VALUE_MESSAGE, Types.DYN)
          .put(LIST_VALUE_MESSAGE, Types.createList(Types.DYN))
          .put(ANY_MESSAGE, Types.ANY)
          .buildOrThrow();

  /** Map of primitive proto types and their CEL {@code Type} equivalents. */
  public static final ImmutableMap<FieldDescriptorProto.Type, Type> PRIMITIVE_TYPE_MAP =
      ImmutableMap.<FieldDescriptorProto.Type, Type>builder()
          .put(FieldDescriptorProto.Type.TYPE_DOUBLE, Types.DOUBLE)
          .put(FieldDescriptorProto.Type.TYPE_FLOAT, Types.DOUBLE)
          .put(FieldDescriptorProto.Type.TYPE_INT32, Types.INT64)
          .put(FieldDescriptorProto.Type.TYPE_INT64, Types.INT64)
          .put(FieldDescriptorProto.Type.TYPE_SINT32, Types.INT64)
          .put(FieldDescriptorProto.Type.TYPE_SINT64, Types.INT64)
          .put(FieldDescriptorProto.Type.TYPE_SFIXED32, Types.INT64)
          .put(FieldDescriptorProto.Type.TYPE_SFIXED64, Types.INT64)
          .put(FieldDescriptorProto.Type.TYPE_UINT32, Types.UINT64)
          .put(FieldDescriptorProto.Type.TYPE_UINT64, Types.UINT64)
          .put(FieldDescriptorProto.Type.TYPE_FIXED32, Types.UINT64)
          .put(FieldDescriptorProto.Type.TYPE_FIXED64, Types.UINT64)
          .put(FieldDescriptorProto.Type.TYPE_BOOL, Types.BOOL)
          .put(FieldDescriptorProto.Type.TYPE_STRING, Types.STRING)
          .put(FieldDescriptorProto.Type.TYPE_BYTES, Types.BYTES)
          .buildOrThrow();

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
    Preconditions.checkArgument(type.getTypeKindCase() == TypeKindCase.PRIMITIVE);
    return createWrapper(type.getPrimitive());
  }

  /**
   * Tests whether the type has error or dyn kind. Both have the property to match any type.
   *
   * @deprecated Use {{@link #isDynOrError(CelType)}} instead.
   */
  @Deprecated
  public static boolean isDynOrError(Type type) {
    return isDynOrError(CelProtoTypes.typeToCelType(type));
  }

  /** Tests whether the type has error or dyn kind. Both have the property to match any type. */
  public static boolean isDynOrError(CelType type) {
    return TypeInference.isDynOrError(type);
  }

  public static boolean isDyn(CelType type) {
    return TypeInference.isDyn(type);
  }

  /** Returns the more general of two types which are known to unify. */
  public static CelType mostGeneral(CelType type1, CelType type2) {
    return TypeInference.mostGeneral(type1, type2);
  }

  /**
   * Checks whether type1 is assignable to type2, based on the given substitution for type
   * parameters. A new substitution is returned, or null if the check fails. The given substitution
   * is not modified.
   */
  public static @Nullable Map<CelType, CelType> isAssignable(
      Map<CelType, CelType> subs, CelType type1, CelType type2) {
    return TypeInference.isAssignable(subs, type1, type2);
  }

  /**
   * Checks whether type1 is assignable to type2, based on the given substitution for type
   * parameters. A new substitution is returned, or null if the check fails. The given substitution
   * is not modified.
   *
   * @deprecated Use {@link #isAssignable(Map, CelType, CelType)} instead.
   */
  @Deprecated
  public static @Nullable Map<Type, Type> isAssignable(
      Map<Type, Type> subs, Type type1, Type type2) {
    HashMap<CelType, CelType> subsCopy =
        subs.entrySet().stream()
            .collect(
                Collectors.toMap(
                    k -> CelProtoTypes.typeToCelType(k.getKey()),
                    v -> CelProtoTypes.typeToCelType(v.getValue()),
                    (prev, next) -> next,
                    HashMap::new));

    Map<CelType, CelType> result =
        TypeInference.isAssignable(
            subsCopy, CelProtoTypes.typeToCelType(type1), CelProtoTypes.typeToCelType(type2));
    if (result != null) {
      return result.entrySet().stream()
          .collect(
              Collectors.toMap(
                  k -> CelProtoTypes.celTypeToType(k.getKey()),
                  v -> CelProtoTypes.celTypeToType(v.getValue()),
                  (prev, next) -> next,
                  HashMap::new));
    }

    return null;
  }

  /**
   * Same as {@link #isAssignable(Map, Type, Type)} but performs pairwise check on lists of types.
   */
  public static @Nullable Map<CelType, CelType> isAssignable(
      Map<CelType, CelType> subs, List<CelType> list1, List<CelType> list2) {
    return TypeInference.isAssignable(subs, list1, list2);
  }

  /**
   * Check whether one type is equal or less specific than the other one. A type is less specific if
   * it matches the other type using the DYN type.
   *
   * @deprecated Use {@link #isEqualOrLessSpecific(CelType, CelType)} instead.
   */
  @Deprecated
  public static boolean isEqualOrLessSpecific(Type type1, Type type2) {
    return isEqualOrLessSpecific(
        CelProtoTypes.typeToCelType(type1), CelProtoTypes.typeToCelType(type2));
  }

  /**
   * Check whether one type is equal or less specific than the other one. A type is less specific if
   * it matches the other type using the DYN type.
   */
  public static boolean isEqualOrLessSpecific(CelType type1, CelType type2) {
    return TypeInference.isEqualOrLessSpecific(type1, type2);
  }

  /**
   * Apply substitution to given type, replacing all direct and indirect occurrences of bound type
   * parameters. Unbound type parameters are replaced by DYN if typeParamToDyn is true.
   *
   * @deprecated Use {@link #substitute(Map, CelType, boolean)} instead.
   */
  @Deprecated
  public static Type substitute(Map<Type, Type> subs, Type type, boolean typeParamToDyn) {
    ImmutableMap.Builder<CelType, CelType> subsMap = ImmutableMap.builder();
    for (Map.Entry<Type, Type> sub : subs.entrySet()) {
      subsMap.put(
          CelProtoTypes.typeToCelType(sub.getKey()), CelProtoTypes.typeToCelType(sub.getValue()));
    }
    return CelProtoTypes.celTypeToType(
        substitute(subsMap.buildOrThrow(), CelProtoTypes.typeToCelType(type), typeParamToDyn));
  }

  /**
   * Apply substitution to given type, replacing all direct and indirect occurrences of bound type
   * parameters. Unbound type parameters are replaced by DYN if typeParamToDyn is true.
   */
  public static CelType substitute(
      Map<CelType, CelType> subs, CelType type, boolean typeParamToDyn) {
    return TypeInference.substitute(subs, type, typeParamToDyn);
  }

  private Types() {}
}
