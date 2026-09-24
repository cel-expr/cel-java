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

import com.google.errorprone.annotations.Immutable;
import dev.cel.common.ast.CelExpr;
import dev.cel.common.values.CelValueConverter;
import dev.cel.common.values.SelectableValue;
import dev.cel.runtime.AccumulatedUnknowns;
import dev.cel.runtime.GlobalResolver;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@Immutable
final class EvalOptionalSelectField extends PlannedInterpretable {
  private final PlannedInterpretable operand;
  private final PlannedInterpretable selectAttribute;
  private final PlannedInterpretable presenceAttribute;
  private final String field;
  private final CelValueConverter celValueConverter;

  @Override
  Object evalInternal(GlobalResolver resolver, ExecutionFrame frame) {
    Object operandValue = EvalHelpers.evalStrictly(operand, resolver, frame);

    if (operandValue instanceof Optional) {
      Optional<?> opt = (Optional<?>) operandValue;
      if (!opt.isPresent()) {
        return Optional.empty();
      }
      operandValue = opt.get();
    }

    // The operand arrives already adapted, so re-materializing it would re-scan every entry of a
    // map for nothing. Traversing keeps that O(1); the selected value is materialized by the
    // attribute below.
    Object runtimeOperandValue = celValueConverter.toTraversalTarget(operandValue);
    boolean hasField = false;
    if (runtimeOperandValue instanceof AccumulatedUnknowns) {
      Object hasFieldResult = EvalHelpers.evalStrictly(presenceAttribute, resolver, frame);
      if (hasFieldResult instanceof AccumulatedUnknowns) {
        return hasFieldResult;
      }
      hasField = Objects.equals(hasFieldResult, true);
    } else if (runtimeOperandValue instanceof SelectableValue<?>) {
      // Guaranteed to be a string. Anything other than string is an error.
      @SuppressWarnings("unchecked")
      SelectableValue<String> selectableValue = (SelectableValue<String>) runtimeOperandValue;
      hasField = selectableValue.find(field).isPresent();
    } else if (runtimeOperandValue instanceof Map) {
      hasField = ((Map<?, ?>) runtimeOperandValue).containsKey(field);
    }
    if (!hasField) {
      return Optional.empty();
    }

    Object resultValue = EvalHelpers.evalStrictly(selectAttribute, resolver, frame);

    if (resultValue instanceof Optional) {
      return resultValue;
    }

    if (resultValue instanceof AccumulatedUnknowns) {
      return resultValue;
    }

    return Optional.of(resultValue);
  }

  static EvalOptionalSelectField create(
      CelExpr expr,
      PlannedInterpretable operand,
      String field,
      PlannedInterpretable selectAttribute,
      PlannedInterpretable presenceAttribute,
      CelValueConverter celValueConverter) {
    return new EvalOptionalSelectField(
        expr, operand, field, selectAttribute, presenceAttribute, celValueConverter);
  }

  private EvalOptionalSelectField(
      CelExpr expr,
      PlannedInterpretable operand,
      String field,
      PlannedInterpretable selectAttribute,
      PlannedInterpretable presenceAttribute,
      CelValueConverter celValueConverter) {
    super(expr);
    this.operand = checkNotNull(operand);
    this.field = checkNotNull(field);
    this.selectAttribute = checkNotNull(selectAttribute);
    this.presenceAttribute = checkNotNull(presenceAttribute);
    this.celValueConverter = checkNotNull(celValueConverter);
  }
}
