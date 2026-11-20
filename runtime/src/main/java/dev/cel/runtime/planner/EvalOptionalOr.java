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
import dev.cel.common.exceptions.CelOverloadNotFoundException;
import dev.cel.runtime.AccumulatedUnknowns;
import dev.cel.runtime.GlobalResolver;
import java.util.Optional;

@Immutable
final class EvalOptionalOr extends PlannedInterpretable {
  private final PlannedInterpretable lhs;
  private final PlannedInterpretable rhs;

  @Override
  Object evalInternal(GlobalResolver resolver, ExecutionFrame frame) {
    Object lhsValue = EvalHelpers.evalStrictly(lhs, resolver, frame);

    if (lhsValue instanceof AccumulatedUnknowns) {
      return lhsValue;
    }

    if (!(lhsValue instanceof Optional)) {
      throw new CelOverloadNotFoundException("or");
    }

    Optional<?> optionalLhs = (Optional<?>) lhsValue;
    if (optionalLhs.isPresent()) {
      return optionalLhs;
    }

    Object rhsValue = EvalHelpers.evalStrictly(rhs, resolver, frame);
    if (rhsValue instanceof AccumulatedUnknowns || rhsValue instanceof Optional) {
      return rhsValue;
    }

    throw new CelOverloadNotFoundException("or");
  }

  static EvalOptionalOr create(CelExpr expr, PlannedInterpretable lhs, PlannedInterpretable rhs) {
    return new EvalOptionalOr(expr, lhs, rhs);
  }

  private EvalOptionalOr(CelExpr expr, PlannedInterpretable lhs, PlannedInterpretable rhs) {
    super(expr);
    this.lhs = Preconditions.checkNotNull(lhs);
    this.rhs = Preconditions.checkNotNull(rhs);
  }
}
