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

import com.google.common.base.Preconditions;
import com.google.errorprone.annotations.Immutable;
import dev.cel.common.ast.CelExpr;
import dev.cel.common.values.CelValueConverter;
import dev.cel.common.values.SelectableValue;
import dev.cel.runtime.AccumulatedUnknowns;
import dev.cel.runtime.GlobalResolver;
import java.util.Map;
import java.util.Optional;

@Immutable
final class EvalOptionalSelectField extends PlannedInterpretable {
  private final PlannedInterpretable operand;
  private final PlannedInterpretable selectAttribute;
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

    Object runtimeOperandValue = celValueConverter.toRuntimeValue(operandValue);
    if (runtimeOperandValue instanceof AccumulatedUnknowns) {
      return runtimeOperandValue;
    }

    boolean hasField = false;

    if (runtimeOperandValue instanceof SelectableValue<?>) {
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

  PlannedInterpretable selectAttribute() {
    return selectAttribute;
  }

  static EvalOptionalSelectField create(
      CelExpr expr,
      PlannedInterpretable operand,
      String field,
      PlannedInterpretable selectAttribute,
      CelValueConverter celValueConverter) {
    return new EvalOptionalSelectField(expr, operand, field, selectAttribute, celValueConverter);
  }

  private EvalOptionalSelectField(
      CelExpr expr,
      PlannedInterpretable operand,
      String field,
      PlannedInterpretable selectAttribute,
      CelValueConverter celValueConverter) {
    super(expr);
    this.operand = Preconditions.checkNotNull(operand);
    this.field = Preconditions.checkNotNull(field);
    this.selectAttribute = Preconditions.checkNotNull(selectAttribute);
    this.celValueConverter = Preconditions.checkNotNull(celValueConverter);
  }
}
