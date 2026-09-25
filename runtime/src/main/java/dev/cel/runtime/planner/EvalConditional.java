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

package dev.cel.runtime.planner;

import static com.google.common.base.Preconditions.checkArgument;
import static dev.cel.runtime.planner.EvalHelpers.evalStrictly;

import dev.cel.common.ast.CelExpr;
import dev.cel.runtime.AccumulatedUnknowns;
import dev.cel.runtime.CelEvaluationException;
import dev.cel.runtime.GlobalResolver;

final class EvalConditional extends PlannedInterpretable {

  @SuppressWarnings("Immutable")
  private final PlannedInterpretable[] args;

  @Override
  Object evalInternal(GlobalResolver resolver, ExecutionFrame frame) throws CelEvaluationException {
    PlannedInterpretable condition = args[0];
    PlannedInterpretable truthy = args[1];
    PlannedInterpretable falsy = args[2];
    Object condResult = evalStrictly(condition, resolver, frame);
    if (condResult instanceof AccumulatedUnknowns) {
      return condResult;
    }
    if (!(condResult instanceof Boolean)) {
      throw new IllegalArgumentException(
          String.format("Expected boolean value, found: %s", condResult));
    }

    if ((boolean) condResult) {
      return truthy.eval(resolver, frame);
    }

    return falsy.eval(resolver, frame);
  }

  static EvalConditional create(CelExpr expr, PlannedInterpretable[] args) {
    return new EvalConditional(expr, args);
  }

  private EvalConditional(CelExpr expr, PlannedInterpretable[] args) {
    super(expr);
    checkArgument(args.length == 3);
    this.args = args;
  }
}
