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

import static dev.cel.runtime.planner.EvalHelpers.evalNonstrictly;
import static dev.cel.runtime.planner.EvalHelpers.evalStrictly;

import dev.cel.common.ast.CelExpr;
import dev.cel.common.values.CelValueConverter;
import dev.cel.runtime.AccumulatedUnknowns;
import dev.cel.runtime.CelEvaluationException;
import dev.cel.runtime.CelResolvedOverload;
import dev.cel.runtime.GlobalResolver;

final class EvalVarArgsCall extends PlannedInterpretable {

  private final String functionName;
  private final CelResolvedOverload resolvedOverload;

  @SuppressWarnings("Immutable")
  private final PlannedInterpretable[] args;

  private final CelValueConverter celValueConverter;

  @Override
  Object evalInternal(GlobalResolver resolver, ExecutionFrame frame) throws CelEvaluationException {
    boolean isStrict = resolvedOverload.isStrict();
    Object[] argVals = new Object[args.length];
    AccumulatedUnknowns unknowns = null;
    for (int i = 0; i < args.length; i++) {
      PlannedInterpretable arg = args[i];
      argVals[i] =
          isStrict ? evalStrictly(arg, resolver, frame) : evalNonstrictly(arg, resolver, frame);
      if (isStrict) {
        unknowns = AccumulatedUnknowns.maybeMerge(unknowns, argVals[i]);
      }
    }
    if (unknowns != null) {
      return unknowns;
    }

    return EvalHelpers.dispatch(functionName, resolvedOverload, celValueConverter, argVals);
  }

  static EvalVarArgsCall create(
      CelExpr expr,
      String functionName,
      CelResolvedOverload resolvedOverload,
      PlannedInterpretable[] args,
      CelValueConverter celValueConverter) {
    return new EvalVarArgsCall(expr, functionName, resolvedOverload, args, celValueConverter);
  }

  private EvalVarArgsCall(
      CelExpr expr,
      String functionName,
      CelResolvedOverload resolvedOverload,
      PlannedInterpretable[] args,
      CelValueConverter celValueConverter) {
    super(expr);
    this.functionName = functionName;
    this.resolvedOverload = resolvedOverload;
    this.args = args;
    this.celValueConverter = celValueConverter;
  }
}
