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

package dev.cel.runtime;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.errorprone.annotations.Immutable;
import java.util.Collection;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Binding consisting of an overload id, a Java-native argument signature, and an overload
 * definition.
 *
 * <p>While the CEL function has a human-readable {@code camelCase} name, overload ids should use
 * the following convention where all {@code <type>} names should be ASCII lower-cased. For types
 * prefer the unparameterized simple name of time, or unqualified name of any proto-based type:
 *
 * <ul>
 *   <li>unary member function: <type>_<function>
 *   <li>binary member function: <type>_<function>_<arg_type>
 *   <li>unary global function: <function>_<type>
 *   <li>binary global function: <function>_<arg_type1>_<arg_type2>
 *   <li>global function: <function>_<arg_type1>_<arg_type2>_<arg_typeN>
 * </ul>
 *
 * <p>Examples: string_startsWith_string, mathMax_list, lessThan_money_money
 */
@Immutable
public interface CelFunctionBinding {
  String getOverloadId();

  ImmutableList<Class<?>> getArgTypes();

  CelFunctionOverload getDefinition();

  boolean isStrict();

  /** Create a unary function binding from the {@code overloadId}, {@code arg}, and {@code impl}. */
  @SuppressWarnings("unchecked") // Safe from CelFunctionOverload.canHandle check before invocation
  static <T> CelFunctionBinding from(
      String overloadId, Class<T> arg, CelFunctionOverload.Unary<T> impl) {
    return from(
        overloadId,
        ImmutableList.of(arg),
        new OptimizedFunctionOverload() {
          @Override
          public Object apply(Object[] args) throws CelEvaluationException {
            return impl.apply((T) args[0]);
          }

          @Override
          public Object apply(Object arg1) throws CelEvaluationException {
            return impl.apply((T) arg1);
          }
        });
  }

  /**
   * Create a binary function binding from the {@code overloadId}, {@code arg1}, {@code arg2}, and
   * {@code impl}.
   */
  @SuppressWarnings("unchecked") // Safe from CelFunctionOverload.canHandle check before invocation
  static <T1, T2> CelFunctionBinding from(
      String overloadId, Class<T1> arg1, Class<T2> arg2, CelFunctionOverload.Binary<T1, T2> impl) {
    return from(
        overloadId,
        ImmutableList.of(arg1, arg2),
        new OptimizedFunctionOverload() {
          @Override
          public Object apply(Object[] args) throws CelEvaluationException {
            return impl.apply((T1) args[0], (T2) args[1]);
          }

          @Override
          public Object apply(Object arg1, Object arg2) throws CelEvaluationException {
            return impl.apply((T1) arg1, (T2) arg2);
          }
        });
  }

  /** Create a function binding from the {@code overloadId}, {@code argTypes}, and {@code impl}. */
  static CelFunctionBinding from(
      String overloadId, Iterable<Class<?>> argTypes, CelFunctionOverload impl) {
    return new FunctionBindingImpl(
        overloadId, ImmutableList.copyOf(argTypes), impl, /* isStrict= */ true);
  }

  /**
   * Create an asynchronous unary function binding from the {@code overloadId}, {@code arg}, and
   * {@code impl}.
   */
  @SuppressWarnings("unchecked") // Safe from CelFunctionOverload.canHandle check before invocation
  static <T> CelFunctionBinding fromAsync(
      String overloadId, Class<T> arg, CelAsyncFunctionOverload.Unary<T> impl) {
    checkNotNull(overloadId);
    checkNotNull(arg);
    checkNotNull(impl);
    return from(
        overloadId,
        ImmutableList.of(arg),
        new CelAsyncFunctionOverload() {
          @Override
          public ListenableFuture<Object> applyAsync(Object[] args) throws CelEvaluationException {
            return impl.apply((T) args[0]);
          }

          @Override
          public ListenableFuture<Object> applyAsync(Object arg1) throws CelEvaluationException {
            return impl.apply((T) arg1);
          }
        });
  }

  /**
   * Create an asynchronous binary function binding from the {@code overloadId}, {@code arg1},
   * {@code arg2}, and {@code impl}.
   */
  @SuppressWarnings("unchecked") // Safe from CelFunctionOverload.canHandle check before invocation
  static <T1, T2> CelFunctionBinding fromAsync(
      String overloadId,
      Class<T1> arg1,
      Class<T2> arg2,
      CelAsyncFunctionOverload.Binary<T1, T2> impl) {
    checkNotNull(overloadId);
    checkNotNull(arg1);
    checkNotNull(arg2);
    checkNotNull(impl);
    return from(
        overloadId,
        ImmutableList.of(arg1, arg2),
        new CelAsyncFunctionOverload() {
          @Override
          public ListenableFuture<Object> applyAsync(Object[] args) throws CelEvaluationException {
            return impl.apply((T1) args[0], (T2) args[1]);
          }

          @Override
          public ListenableFuture<Object> applyAsync(Object a1, Object a2)
              throws CelEvaluationException {
            return impl.apply((T1) a1, (T2) a2);
          }
        });
  }

  /**
   * Create an asynchronous function binding from the {@code overloadId}, {@code argTypes}, and
   * {@code impl}.
   */
  static CelFunctionBinding fromAsync(
      String overloadId, Iterable<Class<?>> argTypes, CelAsyncFunctionOverload impl) {
    checkNotNull(overloadId);
    checkNotNull(argTypes);
    checkNotNull(impl);
    return from(overloadId, argTypes, impl);
  }

  /**
   * Create an asynchronous unary function binding adapting a {@link CompletableFuture} returning
   * implementation.
   */
  @SuppressWarnings("Immutable") // The lambda closes over caller-provided impl
  static <T> CelFunctionBinding fromCompletableFuture(
      String overloadId, Class<T> arg, Function<T, ? extends CompletableFuture<?>> impl) {
    checkNotNull(overloadId);
    checkNotNull(arg);
    checkNotNull(impl);
    return fromAsync(
        overloadId, arg, (T a) -> FunctionBindingImpl.toListenableFuture(impl.apply(a)));
  }

  /**
   * Create an asynchronous binary function binding adapting a {@link CompletableFuture} returning
   * implementation.
   */
  @SuppressWarnings("Immutable") // The lambda closes over caller-provided impl
  static <T1, T2> CelFunctionBinding fromCompletableFuture(
      String overloadId,
      Class<T1> arg1,
      Class<T2> arg2,
      BiFunction<T1, T2, ? extends CompletableFuture<?>> impl) {
    checkNotNull(overloadId);
    checkNotNull(arg1);
    checkNotNull(arg2);
    checkNotNull(impl);
    return fromAsync(
        overloadId,
        arg1,
        arg2,
        (T1 a1, T2 a2) -> FunctionBindingImpl.toListenableFuture(impl.apply(a1, a2)));
  }

  /** See {@link #fromOverloads(String, Collection)}. */
  static ImmutableSet<CelFunctionBinding> fromOverloads(
      String functionName, CelFunctionBinding... overloadBindings) {
    return fromOverloads(functionName, ImmutableList.copyOf(overloadBindings));
  }

  /**
   * Creates a set of bindings for a function, enabling dynamic dispatch logic to select the correct
   * overload at runtime based on argument types.
   */
  static ImmutableSet<CelFunctionBinding> fromOverloads(
      String functionName, Collection<CelFunctionBinding> overloadBindings) {
    checkArgument(!Strings.isNullOrEmpty(functionName), "Function name cannot be null or empty");
    checkArgument(!overloadBindings.isEmpty(), "You must provide at least one binding.");
    for (CelFunctionBinding binding : overloadBindings) {
      checkArgument(
          !(binding.getDefinition() instanceof CelAsyncFunctionOverload),
          "Asynchronous function overloads cannot be grouped using fromOverloads.");
    }

    return FunctionBindingImpl.groupOverloadsToFunction(
        functionName, ImmutableSet.copyOf(overloadBindings));
  }
}
