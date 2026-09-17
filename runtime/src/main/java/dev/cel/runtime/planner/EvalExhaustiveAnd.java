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

import com.google.errorprone.annotations.Immutable;
import dev.cel.common.ast.CelExpr;
import dev.cel.common.values.ErrorValue;
import dev.cel.runtime.AccumulatedUnknowns;
import dev.cel.runtime.GlobalResolver;

/**
 * Implementation of logical AND with exhaustive evaluation (non-short-circuiting).
 *
 * <p>It evaluates all arguments, but prioritizes a false result over unknowns and errors to
 * maintain semantic consistency with short-circuiting evaluation.
 */
@Immutable
final class EvalExhaustiveAnd extends PlannedInterpretable {

  @SuppressWarnings("Immutable")
  private final PlannedInterpretable[] args;

  @Override
  Object evalInternal(GlobalResolver resolver, ExecutionFrame frame) {
    AccumulatedUnknowns accumulatedUnknowns = null;
    ErrorValue errorValue = null;
    boolean hasFalse = false;

    for (PlannedInterpretable arg : args) {
      Object argVal = evalBooleanNonstrictly(arg, resolver, frame);
      if (argVal instanceof Boolean && !(boolean) argVal) {
        hasFalse = true;
      }

      // If we already encountered a false, we do not need to accumulate unknowns or errors
      // from subsequent terms because the final result will be false anyway.
      if (hasFalse) {
        continue;
      }

      if (argVal instanceof AccumulatedUnknowns) {
        accumulatedUnknowns = AccumulatedUnknowns.maybeMerge(accumulatedUnknowns, argVal);
      } else if (argVal instanceof ErrorValue) {
        if (errorValue == null) {
          errorValue = (ErrorValue) argVal;
        }
      }
    }

    if (hasFalse) {
      return false;
    }

    if (accumulatedUnknowns != null) {
      return accumulatedUnknowns;
    }

    if (errorValue != null) {
      return errorValue;
    }

    return true;
  }

  static EvalExhaustiveAnd create(CelExpr expr, PlannedInterpretable[] args) {
    return new EvalExhaustiveAnd(expr, args);
  }

  private EvalExhaustiveAnd(CelExpr expr, PlannedInterpretable[] args) {
    super(expr);
    checkArgument(args.length == 2);
    this.args = args;
  }
}
