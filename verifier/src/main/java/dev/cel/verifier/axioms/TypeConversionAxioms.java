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

import static dev.cel.verifier.CelZ3TypeSystem.MAX_INT64;

import com.google.common.collect.ImmutableList;
import com.microsoft.z3.BoolExpr;
import com.microsoft.z3.Expr;
import com.microsoft.z3.FPExpr;
import com.microsoft.z3.FuncDecl;
import com.microsoft.z3.IntExpr;
import com.microsoft.z3.Sort;
import dev.cel.checker.CelStandardDeclarations.StandardFunction;
import dev.cel.checker.CelStandardDeclarations.StandardFunction.Overload.Conversions;
import java.util.Optional;

/** Axiomatization for CEL's type conversion functions. */
final class TypeConversionAxioms {

  private static final CelZ3FunctionAxiom INT_AXIOM =
      CelZ3FunctionAxiom.newBuilder(StandardFunction.INT.functionDecl())
          .addUnaryOverloadTranslator(
              Conversions.INT64_TO_INT64.celOverloadDecl(),
              (ctx, typeSystem, sink, arg) -> Optional.of(arg))
          .addUnaryOverloadTranslator(
              Conversions.UINT64_TO_INT64.celOverloadDecl(),
              (ctx, typeSystem, sink, arg) -> {
                IntExpr uintVal = typeSystem.getUint(arg);
                BoolExpr outOfBounds = ctx.mkGt(uintVal, ctx.mkInt(MAX_INT64));
                return Optional.of(
                    typeSystem.withRuntimeError(typeSystem.wrapInt(uintVal), outOfBounds));
              })
          .addUnaryOverloadTranslator(
              Conversions.DOUBLE_TO_INT64.celOverloadDecl(),
              createUninterpretedConversion(Conversions.DOUBLE_TO_INT64),
              true)
          .addUnaryOverloadTranslator(
              Conversions.STRING_TO_INT64.celOverloadDecl(),
              createUninterpretedConversion(Conversions.STRING_TO_INT64),
              true)
          .addUnaryOverloadTranslator(
              Conversions.TIMESTAMP_TO_INT64.celOverloadDecl(),
              createUninterpretedConversion(Conversions.TIMESTAMP_TO_INT64),
              true)
          .build();

  private static final CelZ3FunctionAxiom UINT_AXIOM =
      CelZ3FunctionAxiom.newBuilder(StandardFunction.UINT.functionDecl())
          .addUnaryOverloadTranslator(
              Conversions.UINT64_TO_UINT64.celOverloadDecl(),
              (ctx, typeSystem, sink, arg) -> Optional.of(arg))
          .addUnaryOverloadTranslator(
              Conversions.INT64_TO_UINT64.celOverloadDecl(),
              (ctx, typeSystem, sink, arg) -> {
                IntExpr intVal = typeSystem.getInt(arg);
                BoolExpr outOfBounds = ctx.mkLt(intVal, ctx.mkInt(0));
                return Optional.of(
                    typeSystem.withRuntimeError(typeSystem.wrapUint(intVal), outOfBounds));
              })
          .addUnaryOverloadTranslator(
              Conversions.DOUBLE_TO_UINT64.celOverloadDecl(),
              createUninterpretedConversion(Conversions.DOUBLE_TO_UINT64),
              true)
          .addUnaryOverloadTranslator(
              Conversions.STRING_TO_UINT64.celOverloadDecl(),
              createUninterpretedConversion(Conversions.STRING_TO_UINT64),
              true)
          .build();

  private static final CelZ3FunctionAxiom DOUBLE_AXIOM =
      CelZ3FunctionAxiom.newBuilder(StandardFunction.DOUBLE.functionDecl())
          .addUnaryOverloadTranslator(
              Conversions.DOUBLE_TO_DOUBLE.celOverloadDecl(),
              (ctx, typeSystem, sink, arg) -> Optional.of(arg))
          .addUnaryOverloadTranslator(
              Conversions.INT64_TO_DOUBLE.celOverloadDecl(),
              createUninterpretedConversion(Conversions.INT64_TO_DOUBLE),
              true)
          .addUnaryOverloadTranslator(
              Conversions.UINT64_TO_DOUBLE.celOverloadDecl(),
              createUninterpretedConversion(Conversions.UINT64_TO_DOUBLE),
              true)
          .addUnaryOverloadTranslator(
              Conversions.STRING_TO_DOUBLE.celOverloadDecl(),
              createUninterpretedConversion(Conversions.STRING_TO_DOUBLE))
          .build();

  private static final CelZ3FunctionAxiom STRING_AXIOM =
      CelZ3FunctionAxiom.newBuilder(StandardFunction.STRING.functionDecl())
          .addUnaryOverloadTranslator(
              Conversions.STRING_TO_STRING.celOverloadDecl(),
              (ctx, typeSystem, sink, arg) -> Optional.of(arg))
          .addUnaryOverloadTranslator(
              Conversions.INT64_TO_STRING.celOverloadDecl(),
              createUninterpretedConversion(Conversions.INT64_TO_STRING),
              true)
          .addUnaryOverloadTranslator(
              Conversions.UINT64_TO_STRING.celOverloadDecl(),
              createUninterpretedConversion(Conversions.UINT64_TO_STRING),
              true)
          .addUnaryOverloadTranslator(
              Conversions.DOUBLE_TO_STRING.celOverloadDecl(),
              createUninterpretedConversion(Conversions.DOUBLE_TO_STRING),
              true)
          .addUnaryOverloadTranslator(
              Conversions.BOOL_TO_STRING.celOverloadDecl(),
              createUninterpretedConversion(Conversions.BOOL_TO_STRING),
              true)
          .addUnaryOverloadTranslator(
              Conversions.BYTES_TO_STRING.celOverloadDecl(),
              createUninterpretedConversion(Conversions.BYTES_TO_STRING),
              true)
          .addUnaryOverloadTranslator(
              Conversions.TIMESTAMP_TO_STRING.celOverloadDecl(),
              createUninterpretedConversion(Conversions.TIMESTAMP_TO_STRING),
              true)
          .addUnaryOverloadTranslator(
              Conversions.DURATION_TO_STRING.celOverloadDecl(),
              createUninterpretedConversion(Conversions.DURATION_TO_STRING),
              true)
          .build();

  private static final CelZ3FunctionAxiom BYTES_AXIOM =
      CelZ3FunctionAxiom.newBuilder(StandardFunction.BYTES.functionDecl())
          .addUnaryOverloadTranslator(
              Conversions.BYTES_TO_BYTES.celOverloadDecl(),
              (ctx, typeSystem, sink, arg) -> Optional.of(arg))
          .addUnaryOverloadTranslator(
              Conversions.STRING_TO_BYTES.celOverloadDecl(),
              createUninterpretedConversion(Conversions.STRING_TO_BYTES),
              true)
          .build();

  private static final CelZ3FunctionAxiom DYN_AXIOM =
      CelZ3FunctionAxiom.newBuilder(StandardFunction.DYN.functionDecl())
          .addUnaryOverloadTranslator(
              Conversions.TO_DYN.celOverloadDecl(),
              (ctx, typeSystem, sink, arg) -> Optional.of(arg))
          .build();

  private static final CelZ3FunctionAxiom DURATION_AXIOM =
      CelZ3FunctionAxiom.newBuilder(StandardFunction.DURATION.functionDecl())
          .addUnaryOverloadTranslator(
              Conversions.DURATION_TO_DURATION.celOverloadDecl(),
              (ctx, typeSystem, sink, arg) -> Optional.of(arg))
          .addUnaryOverloadTranslator(
              Conversions.STRING_TO_DURATION.celOverloadDecl(),
              createUninterpretedConversion(Conversions.STRING_TO_DURATION),
              true)
          .build();

  private static final CelZ3FunctionAxiom TIMESTAMP_AXIOM =
      CelZ3FunctionAxiom.newBuilder(StandardFunction.TIMESTAMP.functionDecl())
          .addUnaryOverloadTranslator(
              Conversions.TIMESTAMP_TO_TIMESTAMP.celOverloadDecl(),
              (ctx, typeSystem, sink, arg) -> Optional.of(arg))
          .addUnaryOverloadTranslator(
              Conversions.STRING_TO_TIMESTAMP.celOverloadDecl(),
              createUninterpretedConversion(Conversions.STRING_TO_TIMESTAMP),
              true)
          .addUnaryOverloadTranslator(
              Conversions.INT64_TO_TIMESTAMP.celOverloadDecl(),
              createUninterpretedConversion(Conversions.INT64_TO_TIMESTAMP),
              true)
          .build();

  private static final CelZ3FunctionAxiom BOOL_AXIOM =
      CelZ3FunctionAxiom.newBuilder(StandardFunction.BOOL.functionDecl())
          .addUnaryOverloadTranslator(
              Conversions.BOOL_TO_BOOL.celOverloadDecl(),
              (ctx, typeSystem, sink, arg) -> Optional.of(arg))
          .addUnaryOverloadTranslator(
              Conversions.STRING_TO_BOOL.celOverloadDecl(),
              createUninterpretedConversion(Conversions.STRING_TO_BOOL),
              true)
          .build();

  static final ImmutableList<CelZ3FunctionAxiom> ALL_AXIOMS =
      ImmutableList.of(
          INT_AXIOM,
          UINT_AXIOM,
          DOUBLE_AXIOM,
          STRING_AXIOM,
          BYTES_AXIOM,
          DYN_AXIOM,
          DURATION_AXIOM,
          TIMESTAMP_AXIOM,
          BOOL_AXIOM);

  private static CelZ3FunctionAxiom.UnaryTranslator createUninterpretedConversion(
      Conversions conversion) {
    return (ctx, typeSystem, sink, arg) -> {
      FuncDecl<?> funcDecl =
          typeSystem.internFuncDecl(
              conversion.celOverloadDecl().overloadId(),
              new Sort[] {typeSystem.celValueSort()},
              typeSystem.celValueSort());
      Expr<?> res = ctx.mkApp(funcDecl, arg);

      switch (conversion.celOverloadDecl().resultType().kind()) {
        case INT:
        case TIMESTAMP:
        case DURATION:
          sink.accept(typeSystem.isInt(res));
          sink.accept(ctx.mkNot(typeSystem.checkIntOverflow(typeSystem.getInt(res))));
          break;
        case UINT:
          sink.accept(typeSystem.isUint(res));
          sink.accept(ctx.mkNot(typeSystem.checkUintOverflow(typeSystem.getUint(res))));
          break;
        case DOUBLE:
          sink.accept(typeSystem.isDouble(res));
          sink.accept(ctx.mkNot(ctx.mkFPIsNaN((FPExpr) typeSystem.getDouble(res))));
          break;
        case STRING:
          sink.accept(typeSystem.isString(res));
          break;
        case BYTES:
          sink.accept(typeSystem.isBytes(res));
          break;
        case BOOL:
          sink.accept(typeSystem.isBool(res));
          break;
        default:
          break;
      }
      return Optional.of(res);
    };
  }

  private TypeConversionAxioms() {}
}
