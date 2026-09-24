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
import static dev.cel.runtime.planner.EvalHelpers.evalNonstrictly;
import static dev.cel.runtime.planner.EvalHelpers.evalStrictly;
import static dev.cel.runtime.planner.EvalHelpers.maybeAdaptNonStrictArg;

import com.google.common.collect.ImmutableList;
import com.google.errorprone.annotations.Immutable;
import dev.cel.common.CelErrorCode;
import dev.cel.common.ast.CelExpr;
import dev.cel.common.exceptions.CelOverloadNotFoundException;
import dev.cel.common.exceptions.CelRuntimeException;
import dev.cel.common.values.CelValueConverter;
import dev.cel.runtime.AccumulatedUnknowns;
import dev.cel.runtime.CelAsyncFunctionOverload;
import dev.cel.runtime.CelEvaluationException;
import dev.cel.runtime.CelFunctionOverload;
import dev.cel.runtime.CelResolvedOverload;
import dev.cel.runtime.GlobalResolver;

/** Evaluates an asynchronous function call within a planned program. */
@Immutable
final class EvalAsyncCall extends PlannedInterpretable {

  private final String functionName;
  private final CelResolvedOverload resolvedOverload;
  private final CelAsyncFunctionOverload overload;

  @SuppressWarnings("Immutable") // Array not mutated
  private final PlannedInterpretable[] args;

  private final CelValueConverter celValueConverter;

  static EvalAsyncCall create(
      CelExpr expr,
      String functionName,
      CelResolvedOverload resolvedOverload,
      CelAsyncFunctionOverload overload,
      PlannedInterpretable[] args,
      CelValueConverter celValueConverter) {
    return new EvalAsyncCall(
        expr, functionName, resolvedOverload, overload, args, celValueConverter);
  }

  @Override
  Object evalInternal(GlobalResolver resolver, ExecutionFrame frame) throws CelEvaluationException {
    if (!frame.isAsync()) {
      throw new CelEvaluationException(
          String.format(
              "Async function '%s' evaluated in synchronous mode. Asynchronous functions are only"
                  + " supported via evalAsync.",
              functionName));
    }

    boolean isStrict = resolvedOverload.isStrict();
    Object[] evaluatedArgs = new Object[args.length];
    AccumulatedUnknowns accumulatedUnknowns = null;

    for (int i = 0; i < args.length; i++) {
      Object argVal =
          isStrict
              ? evalStrictly(args[i], resolver, frame)
              : evalNonstrictly(args[i], resolver, frame);
      evaluatedArgs[i] = isStrict ? argVal : maybeAdaptNonStrictArg(argVal);
      accumulatedUnknowns = AccumulatedUnknowns.maybeMerge(accumulatedUnknowns, argVal);
    }

    if (isStrict && accumulatedUnknowns != null) {
      return accumulatedUnknowns;
    }

    if (!CelFunctionOverload.canHandle(
        evaluatedArgs, resolvedOverload.getParameterTypes(), resolvedOverload.isStrict())) {
      throw new LocalizedEvaluationException(
          new CelOverloadNotFoundException(
              functionName, ImmutableList.of(resolvedOverload.getOverloadId())),
          expr().id());
    }

    try {
      Object result =
          frame
              .asyncTracker()
              .recordOrGet(
                  expr().id(),
                  functionName,
                  resolvedOverload.getOverloadId(),
                  evaluatedArgs,
                  overload,
                  celValueConverter);
      if (!isStrict && result instanceof AccumulatedUnknowns) {
        return AccumulatedUnknowns.maybeMerge(accumulatedUnknowns, result);
      }
      return result;
    } catch (CelRuntimeException e) {
      throw new LocalizedEvaluationException(e, expr().id());
    } catch (RuntimeException e) {
      throw new LocalizedEvaluationException(e, CelErrorCode.INTERNAL_ERROR, expr().id());
    }
  }

  private EvalAsyncCall(
      CelExpr expr,
      String functionName,
      CelResolvedOverload resolvedOverload,
      CelAsyncFunctionOverload overload,
      PlannedInterpretable[] args,
      CelValueConverter celValueConverter) {
    super(expr);
    this.functionName = checkNotNull(functionName);
    this.resolvedOverload = checkNotNull(resolvedOverload);
    this.overload = checkNotNull(overload);
    this.args = checkNotNull(args);
    this.celValueConverter = checkNotNull(celValueConverter);
  }
}
