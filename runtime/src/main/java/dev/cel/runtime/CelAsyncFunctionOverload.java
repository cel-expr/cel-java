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

package dev.cel.runtime;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.errorprone.annotations.Immutable;

/** Represents a CEL custom function overload that executes asynchronously. */
@Immutable
public interface CelAsyncFunctionOverload extends CelFunctionOverload {

  /** Invokes the overload asynchronously with evaluated arguments. */
  ListenableFuture<Object> applyAsync(Object[] args) throws CelEvaluationException;

  /** Optimized overload for single-argument async functions to avoid array allocation. */
  default ListenableFuture<Object> applyAsync(Object arg) throws CelEvaluationException {
    return applyAsync(new Object[] {arg});
  }

  /** Optimized overload for two-argument async functions to avoid array allocation. */
  default ListenableFuture<Object> applyAsync(Object arg1, Object arg2)
      throws CelEvaluationException {
    return applyAsync(new Object[] {arg1, arg2});
  }

  @Override
  default Object apply(Object[] args) throws CelEvaluationException {
    throw new UnsupportedOperationException(
        "Async overload cannot be evaluated synchronously. Use evalAsync instead.");
  }

  /** Helper interface for describing unary async functions. */
  @Immutable
  @FunctionalInterface
  interface Unary<T> {
    ListenableFuture<Object> apply(T arg) throws CelEvaluationException;
  }

  /** Helper interface for describing binary async functions. */
  @Immutable
  @FunctionalInterface
  interface Binary<T1, T2> {
    ListenableFuture<Object> apply(T1 arg1, T2 arg2) throws CelEvaluationException;
  }
}
