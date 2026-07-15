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

import com.microsoft.z3.ArrayExpr;
import com.microsoft.z3.BoolExpr;
import com.microsoft.z3.Expr;
import com.microsoft.z3.FPExpr;
import com.microsoft.z3.IntExpr;
import com.microsoft.z3.SeqExpr;
import dev.cel.checker.CelStandardDeclarations.StandardFunction;
import java.util.Optional;

/** Axiomatization for CEL's in operator/function (list and map overloads). */
@SuppressWarnings({"unchecked", "rawtypes"}) // Z3 Java API uses raw types.
public final class InAxiom {

  static final CelZ3FunctionAxiom INSTANCE =
      CelZ3FunctionAxiom.newBuilder(StandardFunction.IN.functionDecl())
          .addBinaryOverloadTranslator(
              StandardFunction.Overload.InternalOperator.IN_LIST.celOverloadDecl(),
              (ctx, typeSystem, constraintSink, lhsTrans, rhsTrans) -> {
                Expr<?> listRef = typeSystem.getListRef(rhsTrans);
                SeqExpr<?> seq = typeSystem.getSeq(listRef);

                BoolExpr structContains = ctx.mkContains((Expr) seq, (Expr) ctx.mkUnit(lhsTrans));

                BoolExpr isDouble = typeSystem.isDouble(lhsTrans);
                FPExpr fpVal = (FPExpr) typeSystem.getDouble(lhsTrans);

                BoolExpr isNaN = ctx.mkFPIsNaN(fpVal);
                BoolExpr isDoubleZero = ctx.mkFPIsZero(fpVal);

                Expr<?> posZeroCel = typeSystem.wrapDouble(typeSystem.mkFpDouble(0.0));
                Expr<?> negZeroCel = typeSystem.wrapDouble(typeSystem.mkFpDouble(-0.0));
                Expr<?> intZeroCel = typeSystem.wrapInt(ctx.mkInt(0));
                Expr<?> uintZeroCel = typeSystem.wrapUint(ctx.mkInt(0));

                // CEL follows IEEE-754 where 0.0 == -0.0, and cross-type equality means they also
                // equal int 0 and uint 0.
                BoolExpr containsAnyZero =
                    ctx.mkOr(
                        ctx.mkContains((Expr) seq, (Expr) ctx.mkUnit(posZeroCel)),
                        ctx.mkContains((Expr) seq, (Expr) ctx.mkUnit(negZeroCel)),
                        ctx.mkContains((Expr) seq, (Expr) ctx.mkUnit(intZeroCel)),
                        ctx.mkContains((Expr) seq, (Expr) ctx.mkUnit(uintZeroCel)));

                // CEL follows IEEE-754 where NaN != NaN.
                BoolExpr doubleInList =
                    (BoolExpr)
                        ctx.mkITE(
                            isNaN,
                            ctx.mkFalse(),
                            ctx.mkITE(isDoubleZero, containsAnyZero, structContains));

                BoolExpr isInt = typeSystem.isInt(lhsTrans);
                BoolExpr isUint = typeSystem.isUint(lhsTrans);
                BoolExpr isIntOrUint = ctx.mkOr(isInt, isUint);

                IntExpr intVal =
                    (IntExpr)
                        ctx.mkITE(isInt, typeSystem.getInt(lhsTrans), typeSystem.getUint(lhsTrans));
                BoolExpr isIntOrUintZero = ctx.mkEq(intVal, ctx.mkInt(0));

                BoolExpr containsIntOrUint =
                    ctx.mkOr(
                        ctx.mkContains((Expr) seq, (Expr) ctx.mkUnit(typeSystem.wrapInt(intVal))),
                        ctx.mkContains((Expr) seq, (Expr) ctx.mkUnit(typeSystem.wrapUint(intVal))));

                BoolExpr intOrUintInList =
                    (BoolExpr) ctx.mkITE(isIntOrUintZero, containsAnyZero, containsIntOrUint);

                BoolExpr isMatch =
                    (BoolExpr)
                        ctx.mkITE(
                            isDouble,
                            doubleInList,
                            ctx.mkITE(isIntOrUint, intOrUintInList, structContains));

                return Optional.of(typeSystem.wrapBool(isMatch));
              })
          .addBinaryOverloadTranslator(
              StandardFunction.Overload.InternalOperator.IN_MAP.celOverloadDecl(),
              (ctx, typeSystem, constraintSink, lhsTrans, rhsTrans) -> {
                Expr<?> mapRef = typeSystem.getMapRef(rhsTrans);
                ArrayExpr mapPresence = (ArrayExpr) typeSystem.getMapPresence(mapRef);

                BoolExpr inMap = (BoolExpr) ctx.mkSelect(mapPresence, lhsTrans);

                return Optional.of(typeSystem.wrapBool(inMap));
              })
          .build();

  private InAxiom() {}
}
