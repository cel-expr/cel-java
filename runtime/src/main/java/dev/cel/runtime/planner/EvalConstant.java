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

import com.google.errorprone.annotations.Immutable;
import dev.cel.common.ast.CelExpr;
import dev.cel.runtime.GlobalResolver;

@Immutable
final class EvalConstant extends PlannedInterpretable {

  @SuppressWarnings("Immutable") // Known CEL constants that aren't mutated are stored
  private final Object constant;

  @Override
  Object evalInternal(GlobalResolver resolver, ExecutionFrame frame) {
    return constant;
  }

  Object constantValue() {
    return constant;
  }

  static EvalConstant create(CelExpr expr, Object value) {
    return new EvalConstant(expr, value);
  }

  private EvalConstant(CelExpr expr, Object constant) {
    super(expr);
    this.constant = constant;
  }
}
