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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.errorprone.annotations.Immutable;
import dev.cel.common.ast.CelExpr;
import dev.cel.common.exceptions.CelRuntimeException;
import dev.cel.common.values.MutableMapValue;
import dev.cel.runtime.AccumulatedUnknowns;
import dev.cel.runtime.CelEvaluationException;
import dev.cel.runtime.ConcatenatedListView;
import dev.cel.runtime.GlobalResolver;
import java.util.Collection;
import java.util.Map;
import org.jspecify.annotations.Nullable;

@Immutable
final class EvalFold extends PlannedInterpretable {

  private final String accuVar;
  private final PlannedInterpretable accuInit;
  private final String iterVar;
  private final String iterVar2;
  private final PlannedInterpretable iterRange;
  private final PlannedInterpretable condition;
  private final PlannedInterpretable loopStep;
  private final PlannedInterpretable result;

  static EvalFold create(
      CelExpr expr,
      String accuVar,
      PlannedInterpretable accuInit,
      String iterVar,
      String iterVar2,
      PlannedInterpretable iterRange,
      PlannedInterpretable loopCondition,
      PlannedInterpretable loopStep,
      PlannedInterpretable result) {
    return new EvalFold(
        expr, accuVar, accuInit, iterVar, iterVar2, iterRange, loopCondition, loopStep, result);
  }

  private EvalFold(
      CelExpr expr,
      String accuVar,
      PlannedInterpretable accuInit,
      String iterVar,
      String iterVar2,
      PlannedInterpretable iterRange,
      PlannedInterpretable condition,
      PlannedInterpretable loopStep,
      PlannedInterpretable result) {
    super(expr);
    this.accuVar = accuVar;
    this.accuInit = accuInit;
    this.iterVar = iterVar;
    this.iterVar2 = iterVar2;
    this.iterRange = iterRange;
    this.condition = condition;
    this.loopStep = loopStep;
    this.result = result;
  }

  @Override
  Object evalInternal(GlobalResolver resolver, ExecutionFrame frame) throws CelEvaluationException {
    Object iterRangeRaw = iterRange.eval(resolver, frame);
    if (iterRangeRaw instanceof AccumulatedUnknowns) {
      return iterRangeRaw;
    }
    Folder folder = new Folder(resolver, frame, accuInit, accuVar, iterVar, iterVar2);

    Object result;
    if (iterRangeRaw instanceof Map) {
      result = evalMap((Map<?, ?>) iterRangeRaw, folder, frame);
    } else if (iterRangeRaw instanceof Collection) {
      result = evalList((Collection<?>) iterRangeRaw, folder, frame);
    } else {
      throw new IllegalArgumentException("Unexpected iter_range type: " + iterRangeRaw.getClass());
    }

    return maybeUnwrapAccumulator(result);
  }

  private Object evalMap(Map<?, ?> iterRange, Folder folder, ExecutionFrame frame)
      throws CelEvaluationException {
    for (Map.Entry<?, ?> entry : iterRange.entrySet()) {
      frame.incrementIterations();

      folder.iterVarVal = entry.getKey();
      if (!iterVar2.isEmpty()) {
        folder.iterVar2Val = entry.getValue();
      }

      Object condResult = condition.eval(folder, frame);
      if (condResult instanceof AccumulatedUnknowns) {
        return condResult;
      }
      if (!(condResult instanceof Boolean)) {
        throw new IllegalArgumentException(
            String.format("Expected boolean value, found :%s", condResult));
      }
      boolean cond = (boolean) condResult;
      if (!cond) {
        folder.computeResult = true;
        return result.eval(folder, frame);
      }

      folder.accuVal = loopStep.eval(folder, frame);
      folder.initialized = true;
    }
    folder.computeResult = true;
    return result.eval(folder, frame);
  }

  private Object evalList(Collection<?> iterRange, Folder folder, ExecutionFrame frame)
      throws CelEvaluationException {
    int index = 0;
    for (Object item : iterRange) {
      frame.incrementIterations();

      if (iterVar2.isEmpty()) {
        folder.iterVarVal = item;
      } else {
        folder.iterVarVal = (long) index;
        folder.iterVar2Val = item;
      }

      Object condResult = condition.eval(folder, frame);
      if (condResult instanceof AccumulatedUnknowns) {
        return condResult;
      }
      if (!(condResult instanceof Boolean)) {
        throw new IllegalArgumentException(
            String.format("Expected boolean value, found :%s", condResult));
      }
      boolean cond = (boolean) condResult;
      if (!cond) {
        folder.computeResult = true;
        return maybeUnwrapAccumulator(result.eval(folder, frame));
      }

      folder.accuVal = loopStep.eval(folder, frame);
      folder.initialized = true;
      index++;
    }
    folder.computeResult = true;
    return maybeUnwrapAccumulator(result.eval(folder, frame));
  }

  private static Object maybeWrapAccumulator(Object val) {
    if (val instanceof Collection) {
      return new ConcatenatedListView<>((Collection<?>) val);
    }
    if (val instanceof Map) {
      return MutableMapValue.create((Map<?, ?>) val);
    }
    return val;
  }

  private static Object maybeUnwrapAccumulator(Object val) {
    if (val instanceof ConcatenatedListView) {
      return ImmutableList.copyOf((ConcatenatedListView<?>) val);
    }
    if (val instanceof MutableMapValue) {
      return ImmutableMap.copyOf((MutableMapValue) val);
    }
    return val;
  }

  private static class Folder implements ActivationWrapper {
    private final GlobalResolver resolver;
    private final ExecutionFrame frame;
    private final PlannedInterpretable accuInit;
    private final String accuVar;
    private final String iterVar;
    private final String iterVar2;

    private Object iterVarVal;
    private Object iterVar2Val;
    private Object accuVal;
    private boolean initialized = false;
    private boolean computeResult = false;

    private Folder(
        GlobalResolver resolver,
        ExecutionFrame frame,
        PlannedInterpretable accuInit,
        String accuVar,
        String iterVar,
        String iterVar2) {
      this.resolver = resolver;
      this.frame = frame;
      this.accuInit = accuInit;
      this.accuVar = accuVar;
      this.iterVar = iterVar;
      this.iterVar2 = iterVar2;
    }

    @Override
    public GlobalResolver unwrap() {
      return resolver;
    }

    @Override
    public boolean isLocallyBound(String name) {
      return name.equals(accuVar) || name.equals(iterVar) || name.equals(iterVar2);
    }

    @Override
    public @Nullable Object resolve(String name) {
      if (name.equals(accuVar)) {
        if (!initialized) {
          initialized = true;
          try {
            accuVal = maybeWrapAccumulator(accuInit.eval(resolver, frame));
          } catch (CelEvaluationException e) {
            throw new LazyEvaluationRuntimeException(e);
          }
        }
        return accuVal;
      }

      if (!computeResult) {
        if (name.equals(iterVar)) {
          return this.iterVarVal;
        }

        if (name.equals(iterVar2)) {
          return this.iterVar2Val;
        }
      }

      return resolver.resolve(name);
    }
  }

  private static class LazyEvaluationRuntimeException extends CelRuntimeException {
    private LazyEvaluationRuntimeException(CelEvaluationException cause) {
      super(cause, cause.getErrorCode());
    }
  }
}
