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

import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.errorprone.annotations.Immutable;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Creates an evaluable {@code Program} instance which is thread-safe and immutable. */
@Immutable
public interface Program {

  /** Evaluate the expression without any variables. */
  Object eval() throws CelEvaluationException;

  /** Evaluate the expression using a {@code mapValue} as the source of input variables. */
  Object eval(Map<String, ?> mapValue) throws CelEvaluationException;

  /**
   * Evaluate a compiled program with {@code mapValue} and late-bound functions {@code
   * lateBoundFunctionResolver}.
   */
  Object eval(Map<String, ?> mapValue, CelFunctionResolver lateBoundFunctionResolver)
      throws CelEvaluationException;

  /** Evaluate a compiled program with a custom variable {@code resolver}. */
  Object eval(CelVariableResolver resolver) throws CelEvaluationException;

  /**
   * Evaluate a compiled program with a custom variable {@code resolver} and late-bound functions
   * {@code lateBoundFunctionResolver}.
   */
  Object eval(CelVariableResolver resolver, CelFunctionResolver lateBoundFunctionResolver)
      throws CelEvaluationException;

  /** Evaluate a compiled program with unknown attribute patterns {@code partialVars}. */
  Object eval(PartialVars partialVars) throws CelEvaluationException;

  /** Evaluate the expression asynchronously without any variables on the given executor. */
  default ListenableFuture<Object> evalAsync(ListeningExecutorService executor) {
    return evalAsync(
        GlobalResolver.EMPTY,
        CelFunctionResolver.EMPTY,
        /* partialVars= */ null,
        executor,
        CelAsyncEvaluationOptions.defaultOptions());
  }

  /**
   * Evaluate the expression asynchronously without any variables on the given executor with custom
   * async options.
   */
  default ListenableFuture<Object> evalAsync(
      ListeningExecutorService executor, CelAsyncEvaluationOptions asyncOptions) {
    return evalAsync(
        GlobalResolver.EMPTY,
        CelFunctionResolver.EMPTY,
        /* partialVars= */ null,
        executor,
        asyncOptions);
  }

  /**
   * Evaluate the expression asynchronously using a {@code mapValue} as the source of input
   * variables.
   */
  default ListenableFuture<Object> evalAsync(
      Map<String, ?> mapValue, ListeningExecutorService executor) {
    return evalAsync(
        Activation.copyOf(mapValue),
        CelFunctionResolver.EMPTY,
        /* partialVars= */ null,
        executor,
        CelAsyncEvaluationOptions.defaultOptions());
  }

  /**
   * Evaluate the expression asynchronously using a {@code mapValue} as the source of input
   * variables with custom async options.
   */
  default ListenableFuture<Object> evalAsync(
      Map<String, ?> mapValue,
      ListeningExecutorService executor,
      CelAsyncEvaluationOptions asyncOptions) {
    return evalAsync(
        Activation.copyOf(mapValue),
        CelFunctionResolver.EMPTY,
        /* partialVars= */ null,
        executor,
        asyncOptions);
  }

  /** Evaluate the expression asynchronously using a {@code mapValue} and late-bound functions. */
  default ListenableFuture<Object> evalAsync(
      Map<String, ?> mapValue,
      CelFunctionResolver lateBoundFunctionResolver,
      ListeningExecutorService executor) {
    return evalAsync(
        mapValue, lateBoundFunctionResolver, executor, CelAsyncEvaluationOptions.defaultOptions());
  }

  /**
   * Evaluate the expression asynchronously using a {@code mapValue}, late-bound functions, and
   * custom async options.
   */
  default ListenableFuture<Object> evalAsync(
      Map<String, ?> mapValue,
      CelFunctionResolver lateBoundFunctionResolver,
      ListeningExecutorService executor,
      CelAsyncEvaluationOptions asyncOptions) {
    return evalAsync(
        Activation.copyOf(mapValue),
        lateBoundFunctionResolver,
        /* partialVars= */ null,
        executor,
        asyncOptions);
  }

  /** Evaluate the expression asynchronously with a custom variable {@code resolver}. */
  default ListenableFuture<Object> evalAsync(
      CelVariableResolver resolver, ListeningExecutorService executor) {
    return evalAsync(resolver, executor, CelAsyncEvaluationOptions.defaultOptions());
  }

  /**
   * Evaluate the expression asynchronously with a custom variable {@code resolver} and custom async
   * options.
   */
  default ListenableFuture<Object> evalAsync(
      CelVariableResolver resolver,
      ListeningExecutorService executor,
      CelAsyncEvaluationOptions asyncOptions) {
    return evalAsync(
        (name) -> resolver.find(name).orElse(null),
        CelFunctionResolver.EMPTY,
        /* partialVars= */ null,
        executor,
        asyncOptions);
  }

  /**
   * Evaluate the expression asynchronously with a custom variable {@code resolver} and late-bound
   * functions.
   */
  default ListenableFuture<Object> evalAsync(
      CelVariableResolver resolver,
      CelFunctionResolver lateBoundFunctionResolver,
      ListeningExecutorService executor) {
    return evalAsync(
        resolver, lateBoundFunctionResolver, executor, CelAsyncEvaluationOptions.defaultOptions());
  }

  /**
   * Evaluate the expression asynchronously with a custom variable {@code resolver}, late-bound
   * functions, and custom async options.
   */
  default ListenableFuture<Object> evalAsync(
      CelVariableResolver resolver,
      CelFunctionResolver lateBoundFunctionResolver,
      ListeningExecutorService executor,
      CelAsyncEvaluationOptions asyncOptions) {
    return evalAsync(
        (name) -> resolver.find(name).orElse(null),
        lateBoundFunctionResolver,
        /* partialVars= */ null,
        executor,
        asyncOptions);
  }

  /** Evaluate the expression asynchronously with unknown attribute patterns {@code partialVars}. */
  default ListenableFuture<Object> evalAsync(
      PartialVars partialVars, ListeningExecutorService executor) {
    return evalAsync(partialVars, executor, CelAsyncEvaluationOptions.defaultOptions());
  }

  /**
   * Evaluate the expression asynchronously with unknown attribute patterns {@code partialVars} and
   * custom async options.
   */
  default ListenableFuture<Object> evalAsync(
      PartialVars partialVars,
      ListeningExecutorService executor,
      CelAsyncEvaluationOptions asyncOptions) {
    return evalAsync(
        (name) -> partialVars.resolver().find(name).orElse(null),
        CelFunctionResolver.EMPTY,
        partialVars,
        executor,
        asyncOptions);
  }

  /**
   * Advanced asynchronous evaluation entry point supporting custom global resolvers, late-bound
   * function resolvers, partial variables, and execution options.
   */
  default ListenableFuture<Object> evalAsync(
      GlobalResolver resolver,
      CelFunctionResolver lateBoundResolver,
      @Nullable PartialVars partialVars,
      ListeningExecutorService executor,
      CelAsyncEvaluationOptions asyncOptions) {
    throw new UnsupportedOperationException(
        "evalAsync is not supported by this Program implementation.");
  }
}
