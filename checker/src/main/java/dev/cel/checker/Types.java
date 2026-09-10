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
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.Empty;
import com.google.protobuf.NullValue;
import dev.cel.common.annotations.Internal;
import dev.cel.common.types.CelKind;
import dev.cel.common.types.CelProtoTypes;
import dev.cel.common.types.CelType;
import dev.cel.common.types.ListType;
import dev.cel.common.types.MapType;
import dev.cel.common.types.NullableType;
import dev.cel.common.types.OpaqueType;
import dev.cel.common.types.OptionalType;
import dev.cel.common.types.SimpleType;
import dev.cel.common.types.TypeType;
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
    switch (type.kind()) {
      case ERROR:
        return true;
      default:
        return isDyn(type);
    }
  }

  public static boolean isDyn(CelType type) {
    switch (type.kind()) {
      case DYN:
      case ANY:
        return true;
      default:
        return false;
    }
  }

  /** Tests whether the {@code type} is a type param. */
  private static boolean isTypeParam(CelType type) {
    return type.kind().equals(CelKind.TYPE_PARAM);
  }

  /** Tests whether the {@code type} contains any type params directly or transitively. */
  private static boolean hasTypeParam(CelType type) {
    if (isTypeParam(type)) {
      return true;
    }
    for (CelType param : type.parameters()) {
      if (hasTypeParam(param)) {
        return true;
      }
    }
    return false;
  }

  /** Returns the more general of two types which are known to unify. */
  public static CelType mostGeneral(CelType type1, CelType type2) {
    return isEqualOrLessSpecific(type1, type2) ? type1 : type2;
  }

  /**
   * Checks whether type1 is assignable to type2, based on the given substitution for type
   * parameters. A new substitution is returned, or null if the check fails. The given substitution
   * is not modified.
   */
  public static @Nullable Map<CelType, CelType> isAssignable(
      Map<CelType, CelType> subs, CelType type1, CelType type2) {
    Map<CelType, CelType> subsCopy = new HashMap<>(subs);
    if (internalIsAssignable(subsCopy, type1, type2)) {
      return subsCopy;
    }
    return null;
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

    if (internalIsAssignable(
        subsCopy, CelProtoTypes.typeToCelType(type1), CelProtoTypes.typeToCelType(type2))) {
      return subsCopy.entrySet().stream()
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
    Map<CelType, CelType> subsCopy = new HashMap<>(subs);
    if (internalIsAssignable(subsCopy, list1, list2)) {
      return subsCopy;
    }
    return null;
  }

  private static boolean internalIsAssignable(
      Map<CelType, CelType> subs, CelType type1, CelType type2) {
    // A type is always assignable to itself.
    // Early terminate the call to avoid cases of infinite recursion.
    if (type1.equals(type2)) {
      return true;
    }
    // Process type parameters.
    if (isTypeParam(type2)) {
      if (subs.containsKey(type2)) {
        CelType t2Sub = subs.get(type2);
        // Continue regular process with the assignment for type2.
        if (!internalIsAssignable(subs, type1, t2Sub)) {
          return false;
        }
        CelType t2New = mostGeneral(type1, t2Sub);
        if (notReferencedIn(subs, type2, t2New)) {
          subs.put(type2, t2New);
        }
        return true;
      }
      if (notReferencedIn(subs, type2, type1)) {
        subs.put(type2, type1);
        return true;
      }
    }
    if (isTypeParam(type1)) {
      if (subs.containsKey(type1)) {
        CelType t1Sub = subs.get(type1);
        // Continue regular process with the assignment for type1.
        if (!internalIsAssignable(subs, t1Sub, type2)) {
          return false;
        }
        CelType t1New = mostGeneral(t1Sub, type2);
        if (notReferencedIn(subs, type1, t1New)) {
          subs.put(type1, t1New);
        }
        return true;
      }
      if (notReferencedIn(subs, type1, type2)) {
        subs.put(type1, type2);
        return true;
      }
    }
    // Next check for wildcard types.
    if (isDynOrError(type1) || isDynOrError(type2)) {
      return true;
    }

    // Preserve the nullness checks of the legacy type-checker.
    if (type1.kind() == CelKind.NULL_TYPE) {
      return isAssignableFromNull(type2);
    }
    if (type2.kind() == CelKind.NULL_TYPE) {
      return isAssignableFromNull(type1);
    }

    if (type1.kind() != type2.kind()) {
      return false;
    }

    switch (type1.kind()) {
      case TYPE:
        if (!(type1 instanceof TypeType) || !(type2 instanceof TypeType)) {
          return type2.isAssignableFrom(type1);
        }
        TypeType fromType = (TypeType) type1;
        TypeType toType = (TypeType) type2;
        // If either type contains a type parameter (e.g., type(T) in foo(data, type(T)) -> T),
        // delegate to inner type unification to bind or validate type parameter substitutions.
        // Returns true if the inner types structurally match, unify with an unbound type param,
        // or conform to an existing binding in 'subs'. Returns false on structural/kind mismatches
        // (e.g., int vs list(T)), occurs-check cycles, or conflicting type param bindings.

        if (hasTypeParam(fromType.type()) || hasTypeParam(toType.type())) {
          return internalIsAssignable(subs, fromType.type(), toType.type());
        }
        // Concrete types are coassignable in CEL (e.g., type(1) == type("a"), type([1]) == list).
        return true;
      case OPAQUE:
      case LIST:
      case MAP:
        return internalIsCandidateAssignableToTarget(subs, type1, type2);
      default:
        return type2.isAssignableFrom(type1);
    }
  }

  private static boolean internalIsAssignable(
      Map<CelType, CelType> subs, List<CelType> list1, List<CelType> list2) {
    if (list1.size() != list2.size()) {
      return false;
    }
    int i = 0;
    for (CelType type : list1) {
      if (!internalIsAssignable(subs, type, list2.get(i++))) {
        return false;
      }
    }
    return true;
  }

  private static boolean internalIsCandidateAssignableToTarget(
      Map<CelType, CelType> subs, CelType candidate, CelType target) {
    return candidate.name().equals(target.name())
        && internalIsAssignable(subs, candidate.parameters(), target.parameters());
  }

  private static boolean isAssignableFromNull(CelType targetType) {
    switch (targetType.kind()) {
      case OPAQUE:
      case STRUCT:
      case DURATION:
      case TIMESTAMP:
        return true;
      default:
        return targetType.isAssignableFrom(SimpleType.NULL_TYPE);
    }
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
    // The first type is less specific.
    if (isDyn(type1) || isTypeParam(type1)) {
      return true;
    }
    // The first type is not less specific.
    if (isDyn(type2) || isTypeParam(type2)) {
      return false;
    }
    // Types must be of the same kind to be equal.
    if (type1.kind() != type2.kind()) {
      return false;
    }

    // With limited exceptions for ANY and JSON values, the types must agree and be equivalent in
    // order to return true.
    switch (type1.kind()) {
      case OPAQUE:
      case LIST:
      case MAP:
        // Both types must have the same kind and have the same name in order to be equal or less
        // specific.
        if (!type1.kind().equals(type2.kind())) {
          return false;
        }
        if (!type1.name().equals(type2.name())) {
          return false;
        }
        return isEqualOrLessSpecific(type1.parameters(), type2.parameters());
      case TYPE:
        // Type values must have equal or less specific internal types.
        TypeType typeType1 = (TypeType) type1;
        TypeType typeType2 = (TypeType) type2;
        return isEqualOrLessSpecific(typeType1.type(), typeType2.type());

      // Message, primitive, well-known, and wrapper type names must be equal to be equivalent.
      default:
        return type1.equals(type2);
    }
  }

  private static boolean isEqualOrLessSpecific(List<CelType> types1, List<CelType> types2) {
    if (types1.size() != types2.size()) {
      return false;
    }
    for (int i = 0; i < types1.size(); i++) {
      if (!isEqualOrLessSpecific(types1.get(i), types2.get(i))) {
        return false;
      }
    }
    return true;
  }

  /**
   * Check whether the type doesn't appear directly or transitively within other type. This is a
   * standard requirement for type unification, commonly referred to as the "occurs check".
   */
  private static boolean notReferencedIn(
      Map<CelType, CelType> subs, CelType type, CelType withinType) {
    if (type.equals(withinType)) {
      return false;
    }

    if (withinType instanceof NullableType) {
      return notReferencedIn(subs, type, ((NullableType) withinType).targetType());
    }

    switch (withinType.kind()) {
      case TYPE_PARAM:
        return !subs.containsKey(withinType) || notReferencedIn(subs, type, subs.get(withinType));
      case OPAQUE:
        for (CelType typeArg : withinType.parameters()) {
          if (!notReferencedIn(subs, type, typeArg)) {
            return false;
          }
        }
        return true;
      case LIST:
        ListType listType = (ListType) withinType;
        return notReferencedIn(subs, type, listType.elemType());
      case MAP:
        MapType mapType = (MapType) withinType;
        return notReferencedIn(subs, type, mapType.keyType())
            && notReferencedIn(subs, type, mapType.valueType());
      case TYPE:
        TypeType typeType = (TypeType) withinType;
        return notReferencedIn(subs, type, typeType.type());
      default:
        return true;
    }
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
    if (subs.containsKey(type)) {
      return substitute(subs, subs.get(type), typeParamToDyn);
    }
    if (typeParamToDyn && isTypeParam(type)) {
      return SimpleType.DYN;
    }
    switch (type.kind()) {
      case OPAQUE:
        ImmutableList.Builder<CelType> parameterTypes = new ImmutableList.Builder<>();
        for (int i = 0; i < type.parameters().size(); i++) {
          parameterTypes.add(substitute(subs, type.parameters().get(i), typeParamToDyn));
        }

        if (type instanceof OptionalType) {
          return OptionalType.create(parameterTypes.build().get(0));
        }
        return OpaqueType.create(type.name()).withParameters(parameterTypes.build());
      case LIST:
        ListType listType = (ListType) type;
        return ListType.create(substitute(subs, listType.elemType(), typeParamToDyn));
      case MAP:
        MapType mapType = (MapType) type;
        return MapType.create(
            substitute(subs, mapType.keyType(), typeParamToDyn),
            substitute(subs, mapType.valueType(), typeParamToDyn));
      case TYPE:
        TypeType newType = (TypeType) type;
        return TypeType.create(substitute(subs, newType.type(), typeParamToDyn));
      default:
        return type;
    }
  }

  private Types() {}
}
