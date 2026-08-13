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

import com.google.common.base.Joiner;
import dev.cel.common.CelErrorCode;
import dev.cel.common.exceptions.CelRuntimeException;
import dev.cel.common.values.CelValueConverter;
import dev.cel.common.values.ErrorValue;
import dev.cel.runtime.AccumulatedUnknowns;
import dev.cel.runtime.CelEvaluationException;
import dev.cel.runtime.CelResolvedOverload;
import dev.cel.runtime.CelUnknownSet;
import dev.cel.runtime.GlobalResolver;
import dev.cel.runtime.InterpreterUtil;

final class EvalHelpers {

  static Object evalNonstrictly(
      PlannedInterpretable interpretable, GlobalResolver resolver, ExecutionFrame frame) {
    try {
      return interpretable.eval(resolver, frame);
    } catch (LocalizedEvaluationException e) {
      // Intercept the localized exception to get a more specific expr ID for error reporting
      // Example: foo [1] && strict_err [2] -> ID 2 is propagated.
      return ErrorValue.create(e.exprId(), e);
    } catch (Exception e) {
      return ErrorValue.create(interpretable.expr().id(), e);
    }
  }

  static Object evalStrictly(
      PlannedInterpretable interpretable, GlobalResolver resolver, ExecutionFrame frame) {
    try {
      return interpretable.eval(resolver, frame);
    } catch (LocalizedEvaluationException e) {
      // Already localized - propagate as-is to preserve inner expression ID
      throw e;
    } catch (CelRuntimeException e) {
      // Wrap with current interpretable's location
      throw new LocalizedEvaluationException(e, interpretable.expr().id());
    } catch (Exception e) {
      // Wrap generic exceptions with location
      throw new LocalizedEvaluationException(
          e, CelErrorCode.INTERNAL_ERROR, interpretable.expr().id());
    }
  }

  static Object dispatch(
      String functionName,
      CelResolvedOverload overload,
      CelValueConverter valueConverter,
      Object[] args)
      throws CelEvaluationException {
    try {
      Object result = overload.invoke(args);
      return convertAndAdaptResult(valueConverter, result);
    } catch (RuntimeException e) {
      throw handleDispatchException(e, overload, args);
    }
  }

  static Object dispatch(
      String functionName,
      CelResolvedOverload overload,
      CelValueConverter valueConverter,
      Object arg)
      throws CelEvaluationException {
    try {
      Object result = overload.invoke(arg);
      return convertAndAdaptResult(valueConverter, result);
    } catch (RuntimeException e) {
      throw handleDispatchException(e, overload, arg);
    }
  }

  static Object dispatch(
      String functionName,
      CelResolvedOverload overload,
      CelValueConverter valueConverter,
      Object arg1,
      Object arg2)
      throws CelEvaluationException {
    try {
      Object result = overload.invoke(arg1, arg2);
      return convertAndAdaptResult(valueConverter, result);
    } catch (RuntimeException e) {
      throw handleDispatchException(e, overload, arg1, arg2);
    }
  }

  /**
   * Converts the raw invocation result into a CEL runtime value, unwraps it if necessary, and
   * adapts any public {@link CelUnknownSet} instances into internal {@link AccumulatedUnknowns} for
   * AST evaluation.
   */
  private static Object convertAndAdaptResult(CelValueConverter valueConverter, Object result) {
    return InterpreterUtil.maybeAdaptToAccumulatedUnknowns(
        valueConverter.maybeUnwrap(valueConverter.toRuntimeValue(result)));
  }

  private static RuntimeException handleDispatchException(
      RuntimeException e, CelResolvedOverload overload, Object... args) {
    if (e instanceof CelRuntimeException) {
      // Function dispatch failure that's already been handled -- just propagate.
      return e;
    }
    // Unexpected function dispatch failure.
    return new IllegalArgumentException(
        String.format(
            "Function '%s' failed with arg(s) '%s'",
            overload.getFunctionName(), Joiner.on(", ").join(args)),
        e);
  }

  private EvalHelpers() {}
}
