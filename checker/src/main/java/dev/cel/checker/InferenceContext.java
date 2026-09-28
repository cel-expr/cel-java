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

import com.google.errorprone.annotations.CanIgnoreReturnValue;
import dev.cel.common.annotations.Internal;
import dev.cel.common.types.CelType;
import dev.cel.common.types.TypeParamType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * An object holding a context for type inference.
 *
 * <p>Consists of a type substitution and a generator for free type variables in the context, as
 * well as methods to work with the context.
 *
 * <p>CEL Library Internals. Do Not Use.
 */
@Internal
public class InferenceContext {

  private Map<CelType, CelType> substitution = new HashMap<>();
  private int freeTypeVarCounter;

  /** Creates a fresh type variable in the given context. */
  public CelType newTypeVar(String prefix) {
    // If the prefix ends with a digit, then add an extra % as separator between
    // it and the counter to prevent the two from blending into each other since
    // that can cause accidental name clashes.
    String separator =
        (!prefix.isEmpty() && Character.isDigit(prefix.charAt(prefix.length() - 1))) ? "%" : "";
    return TypeParamType.create("%" + prefix + separator + freeTypeVarCounter++);
  }

  /**
   * Returns an instance of the given type where all given type parameters are replaced by fresh
   * type variables.
   */
  public CelType newInstance(Iterable<String> typeParams, CelType type) {
    Map<CelType, CelType> subs = new HashMap<>();
    for (String typeParam : typeParams) {
      subs.put(TypeParamType.create(typeParam), newTypeVar(typeParam));
    }
    return TypeInference.substitute(subs, type, false);
  }

  /**
   * Checks whether type1 is assignable to type2 under refinement of the type substitution. Returns
   * true on success, and false on failure. The substitution in the type context will not be
   * modified on failure.
   */
  @CanIgnoreReturnValue
  public boolean isAssignable(CelType type1, CelType type2) {
    Map<CelType, CelType> newSubs = TypeInference.isAssignable(substitution, type1, type2);
    if (newSubs != null) {
      substitution = newSubs;
      return true;
    } else {
      return false;
    }
  }

  /** Same as {@link #isAssignable(CelType, CelType)} for lists of types. */
  public boolean isAssignable(List<CelType> list1, List<CelType> list2) {
    Map<CelType, CelType> newSubs = TypeInference.isAssignable(substitution, list1, list2);
    if (newSubs != null) {
      substitution = newSubs;
      return true;
    } else {
      return false;
    }
  }

  /** Specializes the given type using the substitution of this context. */
  public CelType specialize(CelType type) {
    return TypeInference.substitute(substitution, type, false);
  }

  /** Specializes using given type list of types using the substitution of this context. */
  public List<CelType> specialize(List<CelType> types) {
    List<CelType> result = new ArrayList<>();
    for (CelType type : types) {
      result.add(specialize(type));
    }
    return result;
  }

  /**
   * Finalizes the given type by applying the current type substitution and mapping all remaining
   * type parameters to DYN.
   */
  public CelType finalize(CelType type) {
    return TypeInference.substitute(substitution, type, true);
  }
}
