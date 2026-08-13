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

import static dev.cel.runtime.planner.EvalHelpers.enforceStrictnessAndAdaptUnknowns;
import static dev.cel.runtime.planner.EvalHelpers.evalNonstrictly;
import static dev.cel.runtime.planner.EvalHelpers.evalStrictly;

import com.google.common.collect.ImmutableList;
import com.google.errorprone.annotations.Immutable;
import dev.cel.common.ast.CelExpr;
import dev.cel.common.values.CelValueConverter;
import dev.cel.runtime.AccumulatedUnknowns;
import dev.cel.runtime.CelAttribute;
import dev.cel.runtime.CelAttributePattern;
import dev.cel.runtime.CelAttributeResolver;
import dev.cel.runtime.CelEvaluationException;
import dev.cel.runtime.CelEvaluationListener;
import dev.cel.runtime.CelResolvedOverload;
import dev.cel.runtime.CelUnknownSet;
import dev.cel.runtime.GlobalResolver;
import dev.cel.runtime.InterpreterUtil;
import dev.cel.runtime.PartialVars;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

@Immutable
final class EvalIndex extends PlannedInterpretable {

  private final String functionName;
  private final CelResolvedOverload resolvedOverload;
  private final PlannedInterpretable target;
  private final PlannedInterpretable index;
  private final CelValueConverter celValueConverter;

  @Override
  Object evalInternal(GlobalResolver resolver, ExecutionFrame frame) throws CelEvaluationException {
    boolean isStrict = resolvedOverload.isStrict();
    Object targetVal;
    CelAttribute targetAttr = null;

    if (target instanceof InterpretableAttribute) {
      AttributeResolution res =
          ((InterpretableAttribute) target).resolveWithAttribute(resolver, frame);
      targetVal = res.value();
      targetAttr = res.attribute();
      CelEvaluationListener listener = frame.getListener();
      if (listener != null && !(targetVal instanceof MissingAttribute)) {
        listener.callback(target.expr(), InterpreterUtil.maybeAdaptToCelUnknownSet(targetVal));
      }
    } else {
      targetVal =
          isStrict
              ? evalStrictly(target, resolver, frame)
              : evalNonstrictly(target, resolver, frame);
    }

    Object indexVal =
        isStrict ? evalStrictly(index, resolver, frame) : evalNonstrictly(index, resolver, frame);

    if (targetVal instanceof AccumulatedUnknowns) {
      Object indexUnknownResult =
          maybeEvaluateIndexUnknown((AccumulatedUnknowns) targetVal, indexVal, frame);
      if (indexUnknownResult != null) {
        return indexUnknownResult;
      }
    }

    if (targetAttr != null) {
      Object attrUnknownResult = maybeEvaluateAttributeIndexUnknown(targetAttr, indexVal, frame);
      if (attrUnknownResult != null) {
        return attrUnknownResult;
      }
    }

    if (targetVal instanceof MissingAttribute) {
      ((MissingAttribute) targetVal).resolve(target.expr().id(), resolver, frame);
    }

    if (isStrict) {
      AccumulatedUnknowns unknowns = AccumulatedUnknowns.maybeMerge(null, targetVal);
      unknowns = AccumulatedUnknowns.maybeMerge(unknowns, indexVal);
      if (unknowns != null) {
        return unknowns;
      }
    }

    return EvalHelpers.dispatch(
        functionName, resolvedOverload, celValueConverter, targetVal, indexVal);
  }

  private @Nullable Object maybeEvaluateAttributeIndexUnknown(
      CelAttribute targetAttr, Object indexVal, ExecutionFrame frame) {
    if (!frame.hasUnknownResolvers()) {
      return null;
    }
    CelAttribute.Qualifier qualifier = CelAttribute.Qualifier.fromGenericOrNull(indexVal);
    if (qualifier == null) {
      return null;
    }

    CelAttribute indexedAttr = targetAttr.qualify(qualifier);

    CelAttributeResolver attributeResolver = frame.getAttributeResolver();
    if (attributeResolver != null) {
      Optional<Object> resolved = attributeResolver.resolve(indexedAttr);
      if (resolved.isPresent()) {
        return enforceStrictnessAndAdaptUnknowns(resolved.get());
      }

      Optional<CelUnknownSet> partialUnknown = attributeResolver.maybePartialUnknown(indexedAttr);
      if (partialUnknown.isPresent()) {
        return AccumulatedUnknowns.create(
            ImmutableList.of(expr().id()), partialUnknown.get().attributes());
      }
    }

    PartialVars partialVars = frame.getPartialVars();
    if (partialVars != null) {
      for (CelAttributePattern pattern : partialVars.unknowns()) {
        if (pattern.isPartialMatch(indexedAttr)) {
          return AccumulatedUnknowns.create(
              ImmutableList.of(expr().id()), ImmutableList.of(pattern.simplify(indexedAttr)));
        }
      }
    }

    return null;
  }

  private @Nullable Object maybeEvaluateIndexUnknown(
      AccumulatedUnknowns targetUnknowns, Object indexVal, ExecutionFrame frame) {
    if (targetUnknowns.attributes().isEmpty()) {
      return targetUnknowns;
    }

    CelAttribute.Qualifier qualifier = CelAttribute.Qualifier.fromGenericOrNull(indexVal);
    if (qualifier == null) {
      return null;
    }

    CelAttributeResolver attributeResolver = frame.getAttributeResolver();
    PartialVars partialVars = frame.getPartialVars();

    if (targetUnknowns.attributes().size() == 1) {
      CelAttribute singleAttr = targetUnknowns.attributes().iterator().next();
      CelAttribute qualifiedAttr = singleAttr.qualify(qualifier);
      if (attributeResolver != null) {
        Optional<Object> resolved = attributeResolver.resolve(qualifiedAttr);
        if (resolved.isPresent()) {
          return enforceStrictnessAndAdaptUnknowns(resolved.get());
        }
      }
      return AccumulatedUnknowns.create(
          targetUnknowns.exprIds(),
          ImmutableList.of(simplifyAttribute(qualifiedAttr, partialVars)));
    }

    ImmutableList.Builder<CelAttribute> remainingUnknowns = ImmutableList.builder();
    for (CelAttribute attr : targetUnknowns.attributes()) {
      CelAttribute qualifiedAttr = attr.qualify(qualifier);
      if (attributeResolver != null && attributeResolver.resolve(qualifiedAttr).isPresent()) {
        continue;
      }
      remainingUnknowns.add(simplifyAttribute(qualifiedAttr, partialVars));
    }
    ImmutableList<CelAttribute> remaining = remainingUnknowns.build();
    if (remaining.isEmpty()) {
      if (attributeResolver == null) {
        return targetUnknowns;
      }
      Object firstVal = null;
      boolean first = true;
      for (CelAttribute attr : targetUnknowns.attributes()) {
        CelAttribute qualifiedAttr = attr.qualify(qualifier);
        Optional<Object> resolved = attributeResolver.resolve(qualifiedAttr);
        if (!resolved.isPresent()) {
          return targetUnknowns;
        }
        if (first) {
          firstVal = resolved.get();
          first = false;
        } else if (!Objects.equals(firstVal, resolved.get())) {
          return targetUnknowns;
        }
      }
      return firstVal != null ? enforceStrictnessAndAdaptUnknowns(firstVal) : targetUnknowns;
    }

    return AccumulatedUnknowns.create(targetUnknowns.exprIds(), remaining);
  }

  private static CelAttribute simplifyAttribute(
      CelAttribute qualifiedAttr, @Nullable PartialVars partialVars) {
    if (partialVars == null) {
      return qualifiedAttr;
    }
    for (CelAttributePattern pattern : partialVars.unknowns()) {
      if (pattern.isPartialMatch(qualifiedAttr)) {
        return pattern.simplify(qualifiedAttr);
      }
    }
    return qualifiedAttr;
  }

  static EvalIndex create(
      CelExpr expr,
      String functionName,
      CelResolvedOverload resolvedOverload,
      PlannedInterpretable target,
      PlannedInterpretable index,
      CelValueConverter celValueConverter) {
    return new EvalIndex(expr, functionName, resolvedOverload, target, index, celValueConverter);
  }

  private EvalIndex(
      CelExpr expr,
      String functionName,
      CelResolvedOverload resolvedOverload,
      PlannedInterpretable target,
      PlannedInterpretable index,
      CelValueConverter celValueConverter) {
    super(expr);
    this.functionName = functionName;
    this.resolvedOverload = resolvedOverload;
    this.target = target;
    this.index = index;
    this.celValueConverter = celValueConverter;
  }
}
