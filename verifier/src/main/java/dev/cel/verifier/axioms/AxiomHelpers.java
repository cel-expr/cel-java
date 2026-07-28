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
import com.microsoft.z3.Context;
import com.microsoft.z3.FPExpr;
import com.microsoft.z3.IntExpr;
import com.microsoft.z3.RealExpr;

/** Helper methods for Z3 axioms operations. */
final class AxiomHelpers {

  /**
   * Translates CEL's truncated integer division semantics into Z3.
   *
   * <p>Z3's `mkDiv` uses Euclidean division (floor division for positive divisors), while CEL
   * requires truncation towards zero.
   */
  static IntExpr mkTruncatedDiv(Context ctx, IntExpr a, IntExpr b) {
    IntExpr zero = ctx.mkInt(0);
    BoolExpr aIsNeg = ctx.mkLt(a, zero);
    BoolExpr bIsNeg = ctx.mkLt(b, zero);
    IntExpr absA = (IntExpr) ctx.mkITE(aIsNeg, ctx.mkUnaryMinus(a), a);
    IntExpr absB = (IntExpr) ctx.mkITE(bIsNeg, ctx.mkUnaryMinus(b), b);
    IntExpr divAbs = (IntExpr) ctx.mkDiv(absA, absB);
    BoolExpr diffSign = ctx.mkXor(aIsNeg, bIsNeg);
    return (IntExpr) ctx.mkITE(diffSign, ctx.mkUnaryMinus(divAbs), divAbs);
  }

  /**
   * Translates CEL's truncated integer modulo semantics into Z3.
   *
   * <p>Defined as `a - (a / b) * b` using truncated division.
   */
  static IntExpr mkTruncatedMod(Context ctx, IntExpr a, IntExpr b) {
    return (IntExpr) ctx.mkSub(a, ctx.mkMul(mkTruncatedDiv(ctx, a, b), b));
  }

  /**
   * Safe comparison between a Z3 Real (from int/uint) and a Z3 FloatingPoint (double) for {@code
   * <}.
   */
  static BoolExpr mkRealLtFp(Context ctx, RealExpr real, FPExpr fp) {
    return mkSafeFpComparison(ctx, fp, isPosInf(ctx, fp), ctx.mkLt(real, ctx.mkFPToReal(fp)));
  }

  /**
   * Safe comparison between a Z3 FloatingPoint (double) and a Z3 Real (from int/uint) for {@code
   * <}.
   */
  static BoolExpr mkFpLtReal(Context ctx, FPExpr fp, RealExpr real) {
    return mkSafeFpComparison(ctx, fp, isNegInf(ctx, fp), ctx.mkLt(ctx.mkFPToReal(fp), real));
  }

  /**
   * Safe comparison between a Z3 Real (from int/uint) and a Z3 FloatingPoint (double) for {@code
   * <=}.
   */
  static BoolExpr mkRealLeFp(Context ctx, RealExpr real, FPExpr fp) {
    return mkSafeFpComparison(ctx, fp, isPosInf(ctx, fp), ctx.mkLe(real, ctx.mkFPToReal(fp)));
  }

  /**
   * Safe comparison between a Z3 FloatingPoint (double) and a Z3 Real (from int/uint) for {@code
   * <=}.
   */
  static BoolExpr mkFpLeReal(Context ctx, FPExpr fp, RealExpr real) {
    return mkSafeFpComparison(ctx, fp, isNegInf(ctx, fp), ctx.mkLe(ctx.mkFPToReal(fp), real));
  }

  private static BoolExpr isPosInf(Context ctx, FPExpr fp) {
    return ctx.mkAnd(ctx.mkFPIsInfinite(fp), ctx.mkFPIsPositive(fp));
  }

  private static BoolExpr isNegInf(Context ctx, FPExpr fp) {
    return ctx.mkAnd(ctx.mkFPIsInfinite(fp), ctx.mkFPIsNegative(fp));
  }

  private static BoolExpr mkSafeFpComparison(
      Context ctx, FPExpr fp, BoolExpr infCondition, BoolExpr finiteComparison) {
    BoolExpr isFinite = ctx.mkNot(ctx.mkOr(ctx.mkFPIsNaN(fp), ctx.mkFPIsInfinite(fp)));
    return ctx.mkOr(infCondition, ctx.mkAnd(isFinite, finiteComparison));
  }

  private AxiomHelpers() {}
}
