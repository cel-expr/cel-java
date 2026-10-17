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

package dev.cel.bundle;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import com.google.auto.value.AutoValue;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.errorprone.annotations.CheckReturnValue;
import com.google.errorprone.annotations.Immutable;
import dev.cel.common.CelFunctionDecl;
import dev.cel.common.CelOverloadDecl;
import dev.cel.common.types.CelType;
import dev.cel.runtime.CelFunctionBinding;
import dev.cel.runtime.CelFunctionOverload;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * Bundles a {@link CelFunctionDecl} and its corresponding runtime {@link CelFunctionBinding}s into
 * a single unit that can be registered on {@link CelBuilder#addFunctions}.
 *
 * <p>Overload IDs are automatically generated from the function name and parameter types.
 */
@AutoValue
@Immutable
public abstract class CelFunction {

  CelFunction() {}

  /** The compile-time declaration for this function. */
  public abstract CelFunctionDecl functionDecl();

  /** The runtime bindings for this function's overloads. */
  public abstract ImmutableSet<CelFunctionBinding> functionBindings();

  /**
   * Creates a new builder to construct a {@link CelFunction} with the given {@code functionName}.
   */
  public static Builder newBuilder(String functionName) {
    return new Builder(functionName);
  }

  /** Builder for configuring a {@link CelFunction}. */
  public static final class Builder {
    private final String functionName;
    private final List<CelOverloadDecl> overloadDecls = new ArrayList<>();
    // List of functions that take an overload ID and create a CelFunctionBinding. Since the
    // overload ID is not always known until build time, this list is applied to the overload IDs
    // in `build()`
    private final List<Function<String, CelFunctionBinding>> bindings = new ArrayList<>();

    private Builder(String functionName) {
      checkArgument(!Strings.isNullOrEmpty(functionName), "Function name cannot be null or empty");
      this.functionName = functionName;
    }

    /**
     * Adds a unary member overload (e.g. {@code x.f()}) with an automatically generated overload ID
     * (e.g. {@code "targetType.f()"}).
     */
    @CanIgnoreReturnValue
    public <T> Builder addMemberOverload(
        CelType resultType,
        CelType targetType,
        Class<T> targetClass,
        CelFunctionOverload.Unary<T> impl) {
      checkNotNull(resultType);
      checkNotNull(targetType);
      checkNotNull(targetClass);
      checkNotNull(impl);
      overloadDecls.add(CelOverloadDecl.newMemberOverload(resultType, targetType));
      bindings.add(overloadId -> CelFunctionBinding.from(overloadId, targetClass, impl));
      return this;
    }

    /**
     * Adds a binary member overload (e.g. {@code x.f(y)}) with an automatically generated overload
     * ID (e.g. {@code "targetType.f(argType)"}).
     */
    @CanIgnoreReturnValue
    public <T1, T2> Builder addMemberOverload(
        CelType resultType,
        CelType targetType,
        CelType argType,
        Class<T1> targetClass,
        Class<T2> argClass,
        CelFunctionOverload.Binary<T1, T2> impl) {
      checkNotNull(resultType);
      checkNotNull(targetType);
      checkNotNull(argType);
      checkNotNull(targetClass);
      checkNotNull(argClass);
      checkNotNull(impl);
      overloadDecls.add(CelOverloadDecl.newMemberOverload(resultType, targetType, argType));
      bindings.add(overloadId -> CelFunctionBinding.from(overloadId, targetClass, argClass, impl));
      return this;
    }

    /**
     * Adds an N-ary member overload (e.g. {@code x.f(y, z, ...)}) with an automatically generated
     * overload ID (e.g. {@code "targetType.f(arg1Type,arg2Type,...)"}), where the first element of
     * {@code argTypes} and {@code argClasses} is the receiver {@code x}.
     */
    @CanIgnoreReturnValue
    public Builder addMemberOverload(
        CelType resultType,
        List<CelType> argTypes,
        List<Class<?>> argClasses,
        CelFunctionOverload impl) {
      return addNaryOverload(
          resultType, argTypes, argClasses, impl, /* isInstanceFunction= */ true);
    }

    /**
     * Adds a unary global overload (e.g. {@code f(x)}) with an automatically generated overload ID
     * (e.g. {@code "f(argType)"}).
     */
    @CanIgnoreReturnValue
    public <T> Builder addGlobalOverload(
        CelType resultType, CelType argType, Class<T> argClass, CelFunctionOverload.Unary<T> impl) {
      checkNotNull(resultType);
      checkNotNull(argType);
      checkNotNull(argClass);
      checkNotNull(impl);
      overloadDecls.add(CelOverloadDecl.newGlobalOverload(resultType, argType));
      bindings.add(overloadId -> CelFunctionBinding.from(overloadId, argClass, impl));
      return this;
    }

    /**
     * Adds a binary global overload (e.g. {@code f(x, y)}) with an automatically generated overload
     * ID (e.g. {@code "f(arg1Type,arg2Type)"}).
     */
    @CanIgnoreReturnValue
    public <T1, T2> Builder addGlobalOverload(
        CelType resultType,
        CelType arg1Type,
        CelType arg2Type,
        Class<T1> arg1Class,
        Class<T2> arg2Class,
        CelFunctionOverload.Binary<T1, T2> impl) {
      checkNotNull(resultType);
      checkNotNull(arg1Type);
      checkNotNull(arg2Type);
      checkNotNull(arg1Class);
      checkNotNull(arg2Class);
      checkNotNull(impl);
      overloadDecls.add(CelOverloadDecl.newGlobalOverload(resultType, arg1Type, arg2Type));
      bindings.add(overloadId -> CelFunctionBinding.from(overloadId, arg1Class, arg2Class, impl));
      return this;
    }

    /**
     * Adds an N-ary global overload (including zero-argument {@code f()}) with an automatically
     * generated overload ID (e.g. {@code "f(arg1Type,arg2Type,...)"}).
     */
    @CanIgnoreReturnValue
    public Builder addGlobalOverload(
        CelType resultType,
        List<CelType> argTypes,
        List<Class<?>> argClasses,
        CelFunctionOverload impl) {
      return addNaryOverload(
          resultType, argTypes, argClasses, impl, /* isInstanceFunction= */ false);
    }

    @CanIgnoreReturnValue
    private Builder addNaryOverload(
        CelType resultType,
        List<CelType> argTypes,
        List<Class<?>> argClasses,
        CelFunctionOverload impl,
        boolean isInstanceFunction) {
      checkNotNull(resultType);
      checkNotNull(argTypes);
      checkNotNull(argClasses);
      checkNotNull(impl);
      checkArgument(!isInstanceFunction || !argTypes.isEmpty(), "Member function with no receiver");
      checkArgument(
          argTypes.size() == argClasses.size(),
          "Number of CelTypes (%s) must match number of Java classes (%s)",
          argTypes.size(),
          argClasses.size());
      overloadDecls.add(
          isInstanceFunction
              ? CelOverloadDecl.newMemberOverload(resultType, ImmutableList.copyOf(argTypes))
              : CelOverloadDecl.newGlobalOverload(resultType, ImmutableList.copyOf(argTypes)));
      bindings.add(overloadId -> CelFunctionBinding.from(overloadId, argClasses, impl));
      return this;
    }

    /**
     * Builds a new {@link CelFunction} instance.
     *
     * @throws IllegalArgumentException if no overloads were added or if multiple overloads have the
     *     same canonical signature
     */
    @CheckReturnValue
    public CelFunction build() {
      checkArgument(
          !overloadDecls.isEmpty(), "Function '%s' must have at least one overload", functionName);
      CelFunctionDecl functionDecl =
          CelFunctionDecl.newFunctionDeclaration(functionName, overloadDecls);
      ImmutableList<CelOverloadDecl> populatedOverloads = functionDecl.overloads().asList();
      Set<CelOverloadDecl> seen = new HashSet<>();
      ImmutableList.Builder<CelFunctionBinding> bindingsBuilder =
          ImmutableList.builderWithExpectedSize(populatedOverloads.size());
      for (int i = 0; i < overloadDecls.size(); i++) {
        CelOverloadDecl decl = overloadDecls.get(i);
        if (!seen.add(decl)) {
          int firstIndex = overloadDecls.indexOf(decl);
          throw new IllegalArgumentException(
              "Duplicate overload signature: " + populatedOverloads.get(firstIndex).overloadId());
        }
        bindingsBuilder.add(this.bindings.get(i).apply(populatedOverloads.get(i).overloadId()));
      }
      ImmutableSet<CelFunctionBinding> bindings =
          CelFunctionBinding.fromOverloads(functionName, bindingsBuilder.build());
      return new AutoValue_CelFunction(functionDecl, bindings);
    }
  }
}
