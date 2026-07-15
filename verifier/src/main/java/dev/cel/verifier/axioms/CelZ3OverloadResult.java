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

package dev.cel.verifier.axioms;

import com.microsoft.z3.BoolExpr;
import com.microsoft.z3.Expr;

/**
 * Result of translating a CEL overload to Z3, carrying both the Z3 expression and its
 * approximation.
 */
public final class CelZ3OverloadResult {
  private final Expr<?> z3Expr;
  private final BoolExpr isApproximate;

  private CelZ3OverloadResult(Expr<?> z3Expr, BoolExpr isApproximate) {
    this.z3Expr = z3Expr;
    this.isApproximate = isApproximate;
  }

  public Expr<?> z3Expr() {
    return z3Expr;
  }

  public BoolExpr isApproximate() {
    return isApproximate;
  }

  public static CelZ3OverloadResult create(Expr<?> z3Expr, BoolExpr isApproximate) {
    return new CelZ3OverloadResult(z3Expr, isApproximate);
  }
}
