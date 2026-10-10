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

import dev.cel.common.ast.CelConstant;
import dev.cel.common.ast.CelExpr;
import dev.cel.common.values.CelValueConverter;
import dev.cel.runtime.CelResolvedOverload;
import dev.cel.runtime.standard.MatchesFunction;
import java.util.Optional;

final class PlannerHelpers {

  static Attribute resolveBaseAttribute(
      PlannedInterpretable operand, AttributeFactory attributeFactory) {
    if (operand instanceof EvalAttribute) {
      return ((EvalAttribute) operand).attribute();
    }
    return attributeFactory.newRelativeAttribute(operand);
  }

  static Object resolveConstant(CelConstant celConstant) {
    switch (celConstant.getKind()) {
      case NULL_VALUE:
        return celConstant.nullValue();
      case BOOLEAN_VALUE:
        return celConstant.booleanValue();
      case INT64_VALUE:
        return celConstant.int64Value();
      case UINT64_VALUE:
        return celConstant.uint64Value();
      case DOUBLE_VALUE:
        return celConstant.doubleValue();
      case STRING_VALUE:
        return celConstant.stringValue();
      case BYTES_VALUE:
        return celConstant.bytesValue();
      default:
        throw new IllegalStateException("Unsupported kind: " + celConstant.getKind());
    }
  }

  static Optional<PlannedInterpretable> maybePlanRegexMatches(
      CelExpr expr,
      CelResolvedOverload resolvedOverload,
      PlannedInterpretable[] evaluatedArgs,
      CelValueConverter celValueConverter) {
    PlannedInterpretable regexArg = evaluatedArgs[1];
    if (!MatchesFunction.isMatchesOverload(resolvedOverload)
        || !(regexArg instanceof EvalConstant)) {
      return Optional.empty();
    }
    Object rawConstant = ((EvalConstant) regexArg).constantValue();
    if (!(rawConstant instanceof String)) {
      return Optional.empty();
    }
    CelResolvedOverload unaryOverload =
        MatchesFunction.newPrecompiledOverload(resolvedOverload, (String) rawConstant);
    return Optional.of(
        EvalUnary.create(
            expr,
            unaryOverload.getFunctionName(),
            unaryOverload,
            evaluatedArgs[0],
            celValueConverter));
  }

  private PlannerHelpers() {}
}
