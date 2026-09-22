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

import static com.google.common.base.Preconditions.checkArgument;
import static dev.cel.runtime.planner.EvalHelpers.evalBooleanNonstrictly;
import static dev.cel.runtime.planner.EvalHelpers.evalNonstrictly;

import com.google.errorprone.annotations.Immutable;
import dev.cel.common.ast.CelExpr;
import dev.cel.common.values.ErrorValue;
import dev.cel.runtime.AccumulatedUnknowns;
import dev.cel.runtime.GlobalResolver;

/**
 * Implementation of conditional operator (ternary) with exhaustive evaluation
 * (non-short-circuiting).
 *
 * <p>It evaluates all three arguments (condition, truthy, and falsy branches) but returns the
 * result based on the condition, maintaining semantic consistency with short-circuiting evaluation.
 */
@Immutable
final class EvalExhaustiveConditional extends PlannedInterpretable {

  @SuppressWarnings("Immutable")
  private final PlannedInterpretable[] args;

  @Override
  Object evalInternal(GlobalResolver resolver, ExecutionFrame frame) {
    PlannedInterpretable condition = args[0];
    PlannedInterpretable truthy = args[1];
    PlannedInterpretable falsy = args[2];

    Object condResult = evalBooleanNonstrictly(condition, resolver, frame);
    Object truthyVal = evalNonstrictly(truthy, resolver, frame);
    Object falsyVal = evalNonstrictly(falsy, resolver, frame);

    if (condResult instanceof AccumulatedUnknowns || condResult instanceof ErrorValue) {
      return condResult;
    }

    return (boolean) condResult ? truthyVal : falsyVal;
  }

  static EvalExhaustiveConditional create(CelExpr expr, PlannedInterpretable[] args) {
    return new EvalExhaustiveConditional(expr, args);
  }

  private EvalExhaustiveConditional(CelExpr expr, PlannedInterpretable[] args) {
    super(expr);
    checkArgument(args.length == 3);
    this.args = args;
  }
}
