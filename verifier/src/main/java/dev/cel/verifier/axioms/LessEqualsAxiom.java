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
import com.microsoft.z3.FPExpr;
import com.microsoft.z3.SeqExpr;
import dev.cel.checker.CelStandardDeclarations.StandardFunction;
import dev.cel.checker.CelStandardDeclarations.StandardFunction.Overload.Comparison;
import java.util.Optional;

/** Axiomatization for CEL's less_equals operator. */
@SuppressWarnings({"unchecked", "rawtypes"}) // Z3 Java API uses raw types.
final class LessEqualsAxiom {

  static final CelZ3FunctionAxiom INSTANCE =
      CelZ3FunctionAxiom.newBuilder(StandardFunction.LESS_EQUALS.functionDecl())
          .addBinaryOverloadTranslator(
              Comparison.LESS_EQUALS_INT64.celOverloadDecl(),
              (ctx, typeSystem, constraintSink, lhs, rhs) ->
                  Optional.of(
                      typeSystem.wrapBool(
                          ctx.mkLe(
                              (ArithExpr) typeSystem.getInt(lhs),
                              (ArithExpr) typeSystem.getInt(rhs)))))
          .addBinaryOverloadTranslator(
              Comparison.LESS_EQUALS_TIMESTAMP.celOverloadDecl(),
              (ctx, typeSystem, constraintSink, lhs, rhs) ->
                  Optional.of(
                      typeSystem.wrapBool(
                          ctx.mkLe(
                              (ArithExpr) typeSystem.getInt(lhs),
                              (ArithExpr) typeSystem.getInt(rhs)))))
          .addBinaryOverloadTranslator(
              Comparison.LESS_EQUALS_DURATION.celOverloadDecl(),
              (ctx, typeSystem, constraintSink, lhs, rhs) ->
                  Optional.of(
                      typeSystem.wrapBool(
                          ctx.mkLe(
                              (ArithExpr) typeSystem.getInt(lhs),
                              (ArithExpr) typeSystem.getInt(rhs)))))
          .addBinaryOverloadTranslator(
              Comparison.LESS_EQUALS_UINT64.celOverloadDecl(),
              (ctx, typeSystem, constraintSink, lhs, rhs) ->
                  Optional.of(
                      typeSystem.wrapBool(
                          ctx.mkLe(
                              (ArithExpr) typeSystem.getUint(lhs),
                              (ArithExpr) typeSystem.getUint(rhs)))))
          .addBinaryOverloadTranslator(
              Comparison.LESS_EQUALS_DOUBLE.celOverloadDecl(),
              (ctx, typeSystem, constraintSink, lhs, rhs) ->
                  Optional.of(
                      typeSystem.wrapBool(
                          ctx.mkFPLEq(
                              (FPExpr) typeSystem.getDouble(lhs),
                              (FPExpr) typeSystem.getDouble(rhs)))))
          .addBinaryOverloadTranslator(
              Comparison.LESS_EQUALS_STRING.celOverloadDecl(),
              (ctx, typeSystem, constraintSink, lhs, rhs) ->
                  Optional.of(
                      typeSystem.wrapBool(
                          ctx.MkStringLe(
                              (SeqExpr) typeSystem.getString(lhs),
                              (SeqExpr) typeSystem.getString(rhs)))))
          .addBinaryOverloadTranslator(
              Comparison.LESS_EQUALS_BYTES.celOverloadDecl(),
              (ctx, typeSystem, constraintSink, lhs, rhs) ->
                  Optional.of(
                      typeSystem.wrapBool(
                          ctx.MkStringLe(
                              (SeqExpr) typeSystem.getBytes(lhs),
                              (SeqExpr) typeSystem.getBytes(rhs)))))
          .addBinaryOverloadTranslator(
              Comparison.LESS_EQUALS_INT64_DOUBLE.celOverloadDecl(),
              (ctx, typeSystem, constraintSink, lhs, rhs) ->
                  Optional.of(
                      typeSystem.wrapBool(
                          ctx.mkLe(
                              ctx.mkInt2Real(typeSystem.getInt(lhs)),
                              ctx.mkFPToReal((FPExpr) typeSystem.getDouble(rhs))))))
          .addBinaryOverloadTranslator(
              Comparison.LESS_EQUALS_UINT64_DOUBLE.celOverloadDecl(),
              (ctx, typeSystem, constraintSink, lhs, rhs) ->
                  Optional.of(
                      typeSystem.wrapBool(
                          ctx.mkLe(
                              ctx.mkInt2Real(typeSystem.getUint(lhs)),
                              ctx.mkFPToReal((FPExpr) typeSystem.getDouble(rhs))))))
          .addBinaryOverloadTranslator(
              Comparison.LESS_EQUALS_DOUBLE_INT64.celOverloadDecl(),
              (ctx, typeSystem, constraintSink, lhs, rhs) ->
                  Optional.of(
                      typeSystem.wrapBool(
                          ctx.mkLe(
                              ctx.mkFPToReal((FPExpr) typeSystem.getDouble(lhs)),
                              ctx.mkInt2Real(typeSystem.getInt(rhs))))))
          .addBinaryOverloadTranslator(
              Comparison.LESS_EQUALS_DOUBLE_UINT64.celOverloadDecl(),
              (ctx, typeSystem, constraintSink, lhs, rhs) ->
                  Optional.of(
                      typeSystem.wrapBool(
                          ctx.mkLe(
                              ctx.mkFPToReal((FPExpr) typeSystem.getDouble(lhs)),
                              ctx.mkInt2Real(typeSystem.getUint(rhs))))))
          .addBinaryOverloadTranslator(
              Comparison.LESS_EQUALS_INT64_UINT64.celOverloadDecl(),
              (ctx, typeSystem, constraintSink, lhs, rhs) ->
                  Optional.of(
                      typeSystem.wrapBool(
                          ctx.mkLe(
                              (ArithExpr) typeSystem.getInt(lhs),
                              (ArithExpr) typeSystem.getUint(rhs)))))
          .addBinaryOverloadTranslator(
              Comparison.LESS_EQUALS_UINT64_INT64.celOverloadDecl(),
              (ctx, typeSystem, constraintSink, lhs, rhs) ->
                  Optional.of(
                      typeSystem.wrapBool(
                          ctx.mkLe(
                              (ArithExpr) typeSystem.getUint(lhs),
                              (ArithExpr) typeSystem.getInt(rhs)))))
          .build();

  private LessEqualsAxiom() {}
}
