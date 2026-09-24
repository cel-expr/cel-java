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

import static dev.cel.runtime.planner.EvalHelpers.evalBooleanNonstrictly;

import com.google.common.base.Preconditions;
import dev.cel.common.ast.CelExpr;
import dev.cel.common.values.ErrorValue;
import dev.cel.runtime.AccumulatedUnknowns;
import dev.cel.runtime.GlobalResolver;

final class EvalOr extends PlannedInterpretable {

  @SuppressWarnings("Immutable")
  private final PlannedInterpretable[] args;

  @Override
  Object evalInternal(GlobalResolver resolver, ExecutionFrame frame) {
    ErrorValue errorValue = null;
    AccumulatedUnknowns unknowns = null;
    for (PlannedInterpretable arg : args) {
      Object argVal = evalBooleanNonstrictly(arg, resolver, frame);
      if (argVal instanceof Boolean) {
        // Short-circuit on true
        if (((boolean) argVal)) {
          return true;
        }
      } else if (argVal instanceof ErrorValue) {
        // Preserve the first encountered error instead of overwriting it with subsequent errors.
        if (errorValue == null) {
          errorValue = (ErrorValue) argVal;
        }
      } else if (argVal instanceof AccumulatedUnknowns) {
        unknowns = AccumulatedUnknowns.maybeMerge(unknowns, argVal);
      }
    }

    if (unknowns != null) {
      return unknowns;
    }

    if (errorValue != null) {
      return errorValue;
    }

    return false;
  }

  static EvalOr create(CelExpr expr, PlannedInterpretable[] args) {
    return new EvalOr(expr, args);
  }

  private EvalOr(CelExpr expr, PlannedInterpretable[] args) {
    super(expr);
    Preconditions.checkArgument(args.length == 2);
    this.args = args;
  }
}
