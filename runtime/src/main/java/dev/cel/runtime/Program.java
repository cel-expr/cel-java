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
import com.google.errorprone.annotations.Immutable;
import java.util.Map;

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

  /** Evaluate the expression asynchronously without any variables. */
  ListenableFuture<Object> evalAsync();

  /**
   * Evaluate the expression asynchronously using a {@code mapValue} as the source of input
   * variables.
   */
  ListenableFuture<Object> evalAsync(Map<String, ?> mapValue);

  /**
   * Evaluate the expression asynchronously using a {@code mapValue} as the source of input
   * variables and late-bound functions {@code lateBoundFunctionResolver}.
   */
  ListenableFuture<Object> evalAsync(
      Map<String, ?> mapValue, CelFunctionResolver lateBoundFunctionResolver);

  /** Evaluate the expression asynchronously with a custom variable {@code resolver}. */
  ListenableFuture<Object> evalAsync(CelVariableResolver resolver);

  /**
   * Evaluate the expression asynchronously with a custom variable {@code resolver} and late-bound
   * functions {@code lateBoundFunctionResolver}.
   */
  ListenableFuture<Object> evalAsync(
      CelVariableResolver resolver, CelFunctionResolver lateBoundFunctionResolver);

  /** Evaluate the expression asynchronously with unknown attribute patterns {@code partialVars}. */
  ListenableFuture<Object> evalAsync(PartialVars partialVars);
}
