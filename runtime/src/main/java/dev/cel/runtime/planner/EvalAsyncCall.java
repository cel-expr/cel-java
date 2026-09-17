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
import static dev.cel.runtime.planner.EvalHelpers.evalStrictly;

import com.google.common.collect.ImmutableList;
import com.google.errorprone.annotations.Immutable;
import dev.cel.common.CelErrorCode;
import dev.cel.common.ast.CelExpr;
import dev.cel.common.exceptions.CelOverloadNotFoundException;
import dev.cel.common.exceptions.CelRuntimeException;
import dev.cel.common.values.CelValueConverter;
import dev.cel.common.values.ErrorValue;
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

    Object[] evaluatedArgs = new Object[args.length];
    AccumulatedUnknowns accumulatedUnknowns = null;
    ErrorValue firstError = null;

    for (int i = 0; i < args.length; i++) {
      Object argVal = evalStrictly(args[i], resolver, frame);
      if (argVal instanceof ErrorValue) {
        if (firstError == null) {
          firstError = (ErrorValue) argVal;
        }
      } else {
        accumulatedUnknowns = AccumulatedUnknowns.maybeMerge(accumulatedUnknowns, argVal);
      }
      evaluatedArgs[i] = argVal;
    }

    if (accumulatedUnknowns != null) {
      return accumulatedUnknowns;
    }

    if (firstError != null) {
      Throwable cause = firstError.value();
      if (cause instanceof LocalizedEvaluationException) {
        throw (LocalizedEvaluationException) cause;
      }
      throw new LocalizedEvaluationException(
          cause, CelErrorCode.INTERNAL_ERROR, firstError.exprId());
    }

    if (!CelFunctionOverload.canHandle(
        evaluatedArgs, resolvedOverload.getParameterTypes(), resolvedOverload.isStrict())) {
      throw new LocalizedEvaluationException(
          new CelOverloadNotFoundException(
              functionName, ImmutableList.of(resolvedOverload.getOverloadId())),
          expr().id());
    }

    try {
      return frame
          .asyncTracker()
          .recordOrGet(
              expr().id(),
              functionName,
              resolvedOverload.getOverloadId(),
              evaluatedArgs,
              overload,
              celValueConverter);
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
