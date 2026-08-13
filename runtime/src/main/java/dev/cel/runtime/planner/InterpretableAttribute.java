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
abstract class InterpretableAttribute extends PlannedInterpretable {

  abstract InterpretableAttribute addQualifier(CelExpr expr, Qualifier qualifier);

  abstract AttributeResolution resolveWithAttribute(GlobalResolver resolver, ExecutionFrame frame);

  InterpretableAttribute(CelExpr expr) {
    super(expr);
  }
}