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

import com.microsoft.z3.ArithExpr;
import com.microsoft.z3.BoolExpr;
import com.microsoft.z3.Expr;
import com.microsoft.z3.IntExpr;
import dev.cel.checker.CelStandardDeclarations.StandardFunction;
import dev.cel.verifier.CelZ3TypeSystem;
import java.util.Optional;
import java.util.function.BiFunction;

/** Axiomatization for CEL's subtraction operator (-). */
final class SubtractAxiom {

  @SuppressWarnings("Immutable") // Actually immutable -- BiFunction just isn't annotated as such.
  private static CelZ3FunctionAxiom.BinaryTranslator createSubtractTranslator(
      BiFunction<CelZ3TypeSystem, Expr<?>, IntExpr> getLeft,
      BiFunction<CelZ3TypeSystem, Expr<?>, IntExpr> getRight,
      BiFunction<CelZ3TypeSystem, IntExpr, Expr<?>> wrapResult,
      BiFunction<CelZ3TypeSystem, ArithExpr<?>, BoolExpr> overflowChecker) {
    return (ctx, ts, sink, l, r) -> {
      IntExpr a1 = getLeft.apply(ts, l);
      IntExpr a2 = getRight.apply(ts, r);
      ArithExpr<?> subtraction = ctx.mkSub(a1, a2);
      Expr<?> result = wrapResult.apply(ts, (IntExpr) subtraction);
      BoolExpr overflow = overflowChecker.apply(ts, subtraction);
      return Optional.of(ts.withRuntimeError(result, overflow));
    };
  }

  static final CelZ3FunctionAxiom INSTANCE =
      CelZ3FunctionAxiom.newBuilder(StandardFunction.SUBTRACT.functionDecl())
          .addBinaryOverloadTranslator(
              StandardFunction.Overload.Arithmetic.SUBTRACT_INT64.celOverloadDecl(),
              createSubtractTranslator(
                  CelZ3TypeSystem::getInt,
                  CelZ3TypeSystem::getInt,
                  CelZ3TypeSystem::wrapInt,
                  CelZ3TypeSystem::checkIntOverflow))
          .addBinaryOverloadTranslator(
              StandardFunction.Overload.Arithmetic.SUBTRACT_TIMESTAMP_TIMESTAMP.celOverloadDecl(),
              createSubtractTranslator(
                  CelZ3TypeSystem::getTimestamp,
                  CelZ3TypeSystem::getTimestamp,
                  CelZ3TypeSystem::wrapDuration,
                  CelZ3TypeSystem::checkDurationOverflow))
          .addBinaryOverloadTranslator(
              StandardFunction.Overload.Arithmetic.SUBTRACT_TIMESTAMP_DURATION.celOverloadDecl(),
              createSubtractTranslator(
                  CelZ3TypeSystem::getTimestamp,
                  CelZ3TypeSystem::getDuration,
                  CelZ3TypeSystem::wrapTimestamp,
                  CelZ3TypeSystem::checkTimestampOverflow))
          .addBinaryOverloadTranslator(
              StandardFunction.Overload.Arithmetic.SUBTRACT_DURATION_DURATION.celOverloadDecl(),
              createSubtractTranslator(
                  CelZ3TypeSystem::getDuration,
                  CelZ3TypeSystem::getDuration,
                  CelZ3TypeSystem::wrapDuration,
                  CelZ3TypeSystem::checkDurationOverflow))
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
                              ctx.mkFPRoundNearestTiesToEven(), ts.getDouble(l), ts.getDouble(r)))))
          .build();

  private SubtractAxiom() {}
}
