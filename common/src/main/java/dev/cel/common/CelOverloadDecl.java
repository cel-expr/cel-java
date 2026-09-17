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

package dev.cel.common;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.errorprone.annotations.CheckReturnValue;
import com.google.errorprone.annotations.Immutable;
import dev.cel.common.types.CelKind;
import dev.cel.common.types.CelType;
import java.util.Arrays;
import java.util.List;

/**
 * Abstract representation of a CEL function overload declaration.
 *
 * <p>An overload indicates a function's parameter types and return type, where types are specified
 * via CEL native type representations (See: {@link CelType}.
 *
 * <p>An overload is declared in either a global function `Ex: f(x, ...)` or a method call style
 * `Ex: x.f(...)`.
 */
@AutoValue
@Immutable
public abstract class CelOverloadDecl {
  /** Required. Globally unique overload name. */
  public abstract String overloadId();

  /**
   * List of function parameter type values.
   *
   * <p>Param types are disjoint after generic type parameters have been replaced with the type
   * `DYN`. Since the `DYN` type is compatible with any other type, this means that if `A` is a type
   * parameter, the function types `int<A>` and `int<int>` are not disjoint. Likewise, `map<string,
   * string>` is not disjoint from `map<K, V>`.
   *
   * <p>When the {@link #resultType} of a function is a generic type param, the type param name also
   * appears as the `type` of on at least one params.
   */
  public abstract ImmutableList<CelType> parameterTypes();

  /** The type param names associated with the function declaration. */
  public abstract ImmutableSet<String> typeParameterNames();

  /**
   * Required. The result type of the function. For example, the operator `string.isEmpty()` would
   * have `result_type` of `CelKind.BOOL`.
   */
  public abstract CelType resultType();

  /**
   * Denotes whether the function is declared in a global function `Ex: f(x, ...)` or a method call
   * style `Ex: x.f(...)`.
   */
  public abstract boolean isInstanceFunction();

  /** Documentation string for the overload. */
  public abstract String doc();

  public abstract Builder toBuilder();

  /** Create a new builder to construct a {@link CelOverloadDecl} instance */
  public static Builder newBuilder() {
    return new AutoValue_CelOverloadDecl.Builder().setDoc("");
  }

  /** Builder for configuring the {@link CelOverloadDecl}. */
  @AutoValue.Builder
  public abstract static class Builder {
    /** Sets the value for {@link #overloadId()} */
    public abstract Builder setOverloadId(String overloadId);

    /**
     * Sets the parameter types {@link #parameterTypes()}. Note that this will override any
     * parameter types added via the accumulator methods {@link #addParameterTypes}.
     */
    public abstract Builder setParameterTypes(ImmutableList<CelType> value);

    public abstract CelType resultType();

    /** Sets the result type {@link #resultType()} */
    public abstract Builder setResultType(CelType value);

    /**
     * Sets the function declaration style {@link #isInstanceFunction()}. False for global function
     * style, true for member call style
     */
    public abstract Builder setIsInstanceFunction(boolean value);

    /** Sets the documentation for the overload */
    public abstract Builder setDoc(String value);

    public abstract boolean isInstanceFunction();

    public abstract ImmutableList<CelType> parameterTypes();

    /**
     * Not public. This is collected in {@link #build()} by visiting all the parameter types and the
     * expected result type.
     */
    abstract Builder setTypeParameterNames(ImmutableSet<String> value);

    abstract ImmutableList.Builder<CelType> parameterTypesBuilder();

    /** Accumulates parameter types into {@link #parameterTypesBuilder()} */
    @CanIgnoreReturnValue
    public final Builder addParameterTypes(Iterable<CelType> parameterTypes) {
      checkNotNull(parameterTypes);
      parameterTypesBuilder().addAll(parameterTypes);
      return this;
    }

    /** Accumulates parameter types into {@link #parameterTypesBuilder()} */
    @CanIgnoreReturnValue
    public Builder addParameterTypes(CelType... parameterTypes) {
      checkNotNull(parameterTypes);
      parameterTypesBuilder().add(parameterTypes);
      return this;
    }

    @CheckReturnValue
    abstract CelOverloadDecl autoBuild();

    /** Build a new instance of the {@link CelOverloadDecl} */
    @CheckReturnValue
    public CelOverloadDecl build() {
      ImmutableSet.Builder<String> typeParamNameBuilder = new ImmutableSet.Builder<>();
      for (CelType type : parameterTypes()) {
        collectParamNames(typeParamNameBuilder, type);
      }
      collectParamNames(typeParamNameBuilder, resultType());

      setTypeParameterNames(typeParamNameBuilder.build());
      return autoBuild();
    }
  }

  /** Helper method for declaring a member function overload */
  @CheckReturnValue
  public static CelOverloadDecl newMemberOverload(
      String overloadId, CelType resultType, CelType... paramTypes) {
    return newMemberOverload(overloadId, resultType, Arrays.asList(paramTypes));
  }

  /** Helper method for declaring a member function overload */
  @CheckReturnValue
  public static CelOverloadDecl newMemberOverload(
      String overloadId, CelType resultType, List<CelType> paramTypes) {
    return newMemberOverload(overloadId, /* doc= */ "", resultType, paramTypes);
  }

  /** Helper method for declaring a member function overload */
  @CheckReturnValue
  public static CelOverloadDecl newMemberOverload(
      String overloadId, String doc, CelType resultType, CelType... paramTypes) {
    return newMemberOverload(overloadId, doc, resultType, Arrays.asList(paramTypes));
  }

  /** Helper method for declaring a member function overload */
  @CheckReturnValue
  public static CelOverloadDecl newMemberOverload(
      String overloadId, String doc, CelType resultType, List<CelType> paramTypes) {
    return newOverload(overloadId, doc, resultType, paramTypes, /* isInstanceFunction= */ true);
  }

  /** Helper method for declaring a global function overload */
  @CheckReturnValue
  public static CelOverloadDecl newGlobalOverload(
      String overloadId, CelType resultType, CelType... paramTypes) {
    return newGlobalOverload(overloadId, resultType, Arrays.asList(paramTypes));
  }

  /** Helper method for declaring a global function overload */
  @CheckReturnValue
  public static CelOverloadDecl newGlobalOverload(
      String overloadId, CelType resultType, List<CelType> paramTypes) {
    return newGlobalOverload(overloadId, /* doc= */ "", resultType, paramTypes);
  }

  /** Helper method for declaring a global function overload */
  @CheckReturnValue
  public static CelOverloadDecl newGlobalOverload(
      String overloadId, String doc, CelType resultType, CelType... paramTypes) {
    return newGlobalOverload(overloadId, doc, resultType, Arrays.asList(paramTypes));
  }

  /** Helper method for declaring a global function overload */
  @CheckReturnValue
  public static CelOverloadDecl newGlobalOverload(
      String overloadId, String doc, CelType resultType, List<CelType> paramTypes) {
    return newOverload(overloadId, doc, resultType, paramTypes, /* isInstanceFunction= */ false);
  }

  private static CelOverloadDecl newOverload(
      String overloadId,
      String doc,
      CelType resultType,
      List<CelType> paramTypes,
      boolean isInstanceFunction) {
    return CelOverloadDecl.newBuilder()
        .setOverloadId(overloadId)
        .setIsInstanceFunction(isInstanceFunction)
        .setResultType(resultType)
        .addParameterTypes(paramTypes)
        .setDoc(doc)
        .build();
  }

  private static void collectParamNames(ImmutableSet.Builder<String> typeParamNames, CelType type) {
    if (type.kind().equals(CelKind.TYPE_PARAM)) {
      typeParamNames.add(type.name());
    }

    for (CelType param : type.parameters()) {
      collectParamNames(typeParamNames, param);
    }
  }
}
