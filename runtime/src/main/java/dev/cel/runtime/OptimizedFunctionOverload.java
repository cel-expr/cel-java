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

import com.google.errorprone.annotations.Immutable;

/**
 * Internal interface to support fast-path Unary and Binary evaluations, avoiding Object[]
 * allocation.
 */
@Immutable
interface OptimizedFunctionOverload extends CelFunctionOverload {

  /** Fast-path for unary function execution to avoid Object[] allocation. */
  default Object apply(Object arg) throws CelEvaluationException {
    return apply(new Object[] {arg});
  }

  /** Fast-path for binary function execution to avoid Object[] allocation. */
  default Object apply(Object arg1, Object arg2) throws CelEvaluationException {
    return apply(new Object[] {arg1, arg2});
  }

  static OptimizedFunctionOverload of(CelFunctionOverload overload) {
    return (overload instanceof OptimizedFunctionOverload)
        ? (OptimizedFunctionOverload) overload
        : overload::apply;
  }
}
