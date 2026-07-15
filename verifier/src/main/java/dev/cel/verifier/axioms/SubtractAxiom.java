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
import com.microsoft.z3.FPExpr;
import com.microsoft.z3.IntExpr;
import dev.cel.checker.CelStandardDeclarations.StandardFunction;
import java.util.Optional;

/** Axiomatization for CEL's subtraction operator (-). */
final class SubtractAxiom {

  static final CelZ3FunctionAxiom INSTANCE =
      CelZ3FunctionAxiom.newBuilder(StandardFunction.SUBTRACT.functionDecl())
          .addBinaryOverloadTranslator(
              StandardFunction.Overload.Arithmetic.SUBTRACT_INT64.celOverloadDecl(),
              (ctx, ts, sink, l, r) -> {
                IntExpr a1 = ts.getInt(l);
                IntExpr a2 = ts.getInt(r);
                Expr<?> result = ts.wrapInt((IntExpr) ctx.mkSub(a1, a2));
                BoolExpr overflow = ts.checkIntOverflow(ctx.mkSub(a1, a2));
                return Optional.of(ts.withRuntimeError(result, overflow));
              })
          .addBinaryOverloadTranslator(
              StandardFunction.Overload.Arithmetic.SUBTRACT_UINT64.celOverloadDecl(),
              (ctx, ts, sink, l, r) -> {
                IntExpr a1 = ts.getUint(l);
                IntExpr a2 = ts.getUint(r);
                Expr<?> result = ts.wrapUint((IntExpr) ctx.mkSub(a1, a2));
                BoolExpr overflow = ts.checkUintOverflow(ctx.mkSub(a1, a2));
                return Optional.of(ts.withRuntimeError(result, overflow));
              })
          .addBinaryOverloadTranslator(
              StandardFunction.Overload.Arithmetic.SUBTRACT_DOUBLE.celOverloadDecl(),
              (ctx, ts, sink, l, r) ->
                  Optional.of(
                      ts.wrapDouble(
                          ctx.mkFPSub(
                              ctx.mkFPRoundNearestTiesToEven(),
                              (FPExpr) ts.getDouble(l),
                              (FPExpr) ts.getDouble(r)))))
          .build();

  private SubtractAxiom() {}
}
