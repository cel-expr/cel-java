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
// See the License for the existing language governing permissions and
// limitations under the License.

package dev.cel.verifier.axioms;

import com.microsoft.z3.BoolExpr;
import com.microsoft.z3.Expr;
import com.microsoft.z3.FPExpr;
import com.microsoft.z3.IntExpr;
import dev.cel.checker.CelStandardDeclarations.StandardFunction;
import java.util.Optional;

/** Axiomatization for CEL's unary negation operator (-). */
final class NegateAxiom {

  static final CelZ3FunctionAxiom INSTANCE =
      CelZ3FunctionAxiom.newBuilder(StandardFunction.NEGATE.functionDecl())
          // Int Negate
          .addUnaryOverloadTranslator(
              StandardFunction.Overload.Arithmetic.NEGATE_INT64.celOverloadDecl(),
              (ctx, ts, sink, arg) -> {
                IntExpr a = ts.getInt(arg);
                Expr<?> result = ts.wrapInt((IntExpr) ctx.mkUnaryMinus(a));
                BoolExpr overflow = ts.checkIntOverflow(ctx.mkUnaryMinus(a));
                return Optional.of(ts.withRuntimeError(result, overflow));
              })
          // Double Negate
          .addUnaryOverloadTranslator(
              StandardFunction.Overload.Arithmetic.NEGATE_DOUBLE.celOverloadDecl(),
              (ctx, ts, sink, arg) ->
                  Optional.of(ts.wrapDouble(ctx.mkFPNeg((FPExpr) ts.getDouble(arg)))))
          .build();

  private NegateAxiom() {}
}
