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

package dev.cel.checker;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.collect.ImmutableList;
import com.google.errorprone.annotations.CheckReturnValue;
import com.google.errorprone.annotations.Immutable;
import dev.cel.common.annotations.Internal;
import dev.cel.common.types.CelKind;
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
import org.jspecify.annotations.Nullable;

/**
 * Type inference, unification, and assignability algorithms for {@link CelType}.
 *
 * <p>CEL Library Internals. Do Not Use.
 */
@Internal
@Immutable
@CheckReturnValue
final class TypeInference {

  static boolean isDynOrError(CelType type) {
    checkNotNull(type, "type");
    switch (type.kind()) {
      case ERROR:
        return true;
      default:
        return isDyn(type);
    }
  }

  static boolean isDyn(CelType type) {
    checkNotNull(type, "type");
    switch (type.kind()) {
      case DYN:
      case ANY:
        return true;
      default:
        return false;
    }
  }

  private static boolean isTypeParam(CelType type) {
    return type.kind().equals(CelKind.TYPE_PARAM);
  }

  private static boolean hasTypeParam(CelType type) {
    if (isTypeParam(type)) {
      return true;
    }
    if (type instanceof NullableType) {
      return hasTypeParam(((NullableType) type).targetType());
    }
    for (CelType param : type.parameters()) {
      if (hasTypeParam(param)) {
        return true;
      }
    }
    return false;
  }

  static CelType mostGeneral(CelType type1, CelType type2) {
    checkNotNull(type1, "type1");
    checkNotNull(type2, "type2");
    return isEqualOrLessSpecific(type1, type2) ? type1 : type2;
  }

  static @Nullable Map<CelType, CelType> isAssignable(
      Map<CelType, CelType> subs, CelType type1, CelType type2) {
    checkNotNull(subs, "subs");
    checkNotNull(type1, "type1");
    checkNotNull(type2, "type2");
    Map<CelType, CelType> subsCopy = new HashMap<>(subs);
    if (internalIsAssignable(subsCopy, type1, type2)) {
      return subsCopy;
    }
    return null;
  }

  static @Nullable Map<CelType, CelType> isAssignable(
      Map<CelType, CelType> subs, List<CelType> list1, List<CelType> list2) {
    checkNotNull(subs, "subs");
    checkNotNull(list1, "list1");
    checkNotNull(list2, "list2");
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

  static boolean isEqualOrLessSpecific(CelType type1, CelType type2) {
    checkNotNull(type1, "type1");
    checkNotNull(type2, "type2");
    // The first type is less specific.
    if (isDyn(type1) || isTypeParam(type1)) {
      return true;
    }
    // The first type is not less specific.
    if (isDyn(type2) || isTypeParam(type2)) {
      return false;
    }
    if (type1 instanceof NullableType && type2 instanceof NullableType) {
      return isEqualOrLessSpecific(
          ((NullableType) type1).targetType(), ((NullableType) type2).targetType());
    }
    if (type1 instanceof NullableType || type2 instanceof NullableType) {
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
        if (!(type1 instanceof TypeType) || !(type2 instanceof TypeType)) {
          return type1.equals(type2);
        }
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

  static CelType substitute(Map<CelType, CelType> subs, CelType type, boolean typeParamToDyn) {
    checkNotNull(subs, "subs");
    checkNotNull(type, "type");
    if (subs.containsKey(type)) {
      return substitute(subs, subs.get(type), typeParamToDyn);
    }
    if (type instanceof NullableType) {
      return NullableType.create(
          substitute(subs, ((NullableType) type).targetType(), typeParamToDyn));
    }
    if (typeParamToDyn && isTypeParam(type)) {
      return SimpleType.DYN;
    }
    switch (type.kind()) {
      case OPAQUE:
        ImmutableList.Builder<CelType> parameterTypes = ImmutableList.builder();
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

  private TypeInference() {}
}
