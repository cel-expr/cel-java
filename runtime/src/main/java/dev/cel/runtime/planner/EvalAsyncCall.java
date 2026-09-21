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

package dev.cel.runtime.planner;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.errorprone.annotations.Immutable;
import dev.cel.common.ast.CelExpr;
import dev.cel.runtime.CelEvaluationException;
import dev.cel.runtime.GlobalResolver;

/** Evaluates an asynchronous function call within a planned program. */
@Immutable
final class EvalAsyncCall extends PlannedInterpretable {

  private final String functionName;

  static EvalAsyncCall create(CelExpr expr, String functionName) {
    return new EvalAsyncCall(expr, functionName);
  }

  @Override
  Object evalInternal(GlobalResolver resolver, ExecutionFrame frame) throws CelEvaluationException {
    throw new CelEvaluationException(
        String.format(
            "Async function '%s' evaluated in synchronous mode. Asynchronous functions are only"
                + " supported via evalAsync.",
            functionName));
  }

  private EvalAsyncCall(CelExpr expr, String functionName) {
    super(expr);
    this.functionName = checkNotNull(functionName);
  }
}
