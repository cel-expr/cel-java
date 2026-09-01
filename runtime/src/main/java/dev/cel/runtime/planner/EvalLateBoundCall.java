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

import static dev.cel.runtime.planner.EvalHelpers.evalStrictly;

import com.google.common.collect.ImmutableList;
import com.google.errorprone.annotations.Immutable;
import dev.cel.common.ast.CelExpr;
import dev.cel.common.exceptions.CelOverloadNotFoundException;
import dev.cel.common.values.CelValueConverter;
import dev.cel.runtime.AccumulatedUnknowns;
import dev.cel.runtime.CelAsyncFunctionOverload;
import dev.cel.runtime.CelEvaluationException;
import dev.cel.runtime.CelResolvedOverload;
import dev.cel.runtime.GlobalResolver;

@Immutable
final class EvalLateBoundCall extends PlannedInterpretable {

  private final String functionName;
  private final ImmutableList<String> overloadIds;

  @SuppressWarnings("Immutable") // Array not mutated
  private final PlannedInterpretable[] args;

  private final CelValueConverter celValueConverter;

  @Override
  Object evalInternal(GlobalResolver resolver, ExecutionFrame frame) throws CelEvaluationException {
    Object[] argVals = new Object[args.length];
    AccumulatedUnknowns unknowns = null;
    for (int i = 0; i < args.length; i++) {
      PlannedInterpretable arg = args[i];
      // Late bound functions are assumed to be strict.
      argVals[i] = evalStrictly(arg, resolver, frame);

      unknowns = AccumulatedUnknowns.maybeMerge(unknowns, argVals[i]);
    }

    if (unknowns != null) {
      return unknowns;
    }

    CelResolvedOverload resolvedOverload =
        frame
            .findOverload(functionName, overloadIds, argVals)
            .orElseThrow(() -> new CelOverloadNotFoundException(functionName, overloadIds));

    if (resolvedOverload.getDefinition() instanceof CelAsyncFunctionOverload) {
      if (!frame.isAsync()) {
        throw new CelEvaluationException(
            String.format(
                "Async function '%s' evaluated in synchronous mode. Asynchronous functions are only"
                    + " supported via evalAsync.",
                functionName));
      }
      return frame
          .asyncTracker()
          .recordOrGet(
              expr().id(),
              functionName,
              resolvedOverload.getOverloadId(),
              argVals,
              (CelAsyncFunctionOverload) resolvedOverload.getDefinition(),
              celValueConverter,
              frame.asyncExecutor(),
              frame.asyncGate(),
              frame.asyncCoordinator(),
              frame.asyncObserver().orElse(null));
    }

    return EvalHelpers.dispatch(functionName, resolvedOverload, celValueConverter, argVals);
  }

  static EvalLateBoundCall create(
      CelExpr expr,
      String functionName,
      ImmutableList<String> overloadIds,
      PlannedInterpretable[] args,
      CelValueConverter celValueConverter) {
    return new EvalLateBoundCall(expr, functionName, overloadIds, args, celValueConverter);
  }

  private EvalLateBoundCall(
      CelExpr expr,
      String functionName,
      ImmutableList<String> overloadIds,
      PlannedInterpretable[] args,
      CelValueConverter celValueConverter) {
    super(expr);
    this.functionName = functionName;
    this.overloadIds = overloadIds;
    this.args = args;
    this.celValueConverter = celValueConverter;
  }
}
