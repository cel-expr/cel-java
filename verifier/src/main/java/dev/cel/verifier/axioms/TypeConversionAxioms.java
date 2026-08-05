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

import static dev.cel.verifier.CelNumericBounds.MAX_INT64;

import com.google.common.collect.ImmutableList;
import com.microsoft.z3.BoolExpr;
import com.microsoft.z3.Expr;
import com.microsoft.z3.FuncDecl;
import com.microsoft.z3.IntExpr;
import com.microsoft.z3.Sort;
import dev.cel.checker.CelStandardDeclarations.StandardFunction;
import dev.cel.checker.CelStandardDeclarations.StandardFunction.Overload.Conversions;
import dev.cel.common.types.SimpleType;
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
          .addOverloadTranslator(
              Conversions.DOUBLE_TO_INT64.celOverloadDecl(),
              createUninterpretedConversion(Conversions.DOUBLE_TO_INT64))
          .addOverloadTranslator(
              Conversions.STRING_TO_INT64.celOverloadDecl(),
              createUninterpretedConversion(Conversions.STRING_TO_INT64))
          .addUnaryOverloadTranslator(
              Conversions.TIMESTAMP_TO_INT64.celOverloadDecl(),
              (ctx, typeSystem, sink, arg) ->
                  Optional.of(typeSystem.wrapInt(typeSystem.getTimestamp(arg))))
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
          .addOverloadTranslator(
              Conversions.DOUBLE_TO_UINT64.celOverloadDecl(),
              createUninterpretedConversion(Conversions.DOUBLE_TO_UINT64))
          .addOverloadTranslator(
              Conversions.STRING_TO_UINT64.celOverloadDecl(),
              createUninterpretedConversion(Conversions.STRING_TO_UINT64))
          .build();

  private static final CelZ3FunctionAxiom DOUBLE_AXIOM =
      CelZ3FunctionAxiom.newBuilder(StandardFunction.DOUBLE.functionDecl())
          .addUnaryOverloadTranslator(
              Conversions.DOUBLE_TO_DOUBLE.celOverloadDecl(),
              (ctx, typeSystem, sink, arg) -> Optional.of(arg))
          .addOverloadTranslator(
              Conversions.INT64_TO_DOUBLE.celOverloadDecl(),
              createUninterpretedConversion(Conversions.INT64_TO_DOUBLE))
          .addOverloadTranslator(
              Conversions.UINT64_TO_DOUBLE.celOverloadDecl(),
              createUninterpretedConversion(Conversions.UINT64_TO_DOUBLE))
          .addOverloadTranslator(
              Conversions.STRING_TO_DOUBLE.celOverloadDecl(),
              createUninterpretedConversion(Conversions.STRING_TO_DOUBLE))
          .build();

  private static final CelZ3FunctionAxiom STRING_AXIOM =
      CelZ3FunctionAxiom.newBuilder(StandardFunction.STRING.functionDecl())
          .addUnaryOverloadTranslator(
              Conversions.STRING_TO_STRING.celOverloadDecl(),
              (ctx, typeSystem, sink, arg) -> Optional.of(arg))
          .addOverloadTranslator(
              Conversions.INT64_TO_STRING.celOverloadDecl(),
              createUninterpretedConversion(Conversions.INT64_TO_STRING))
          .addOverloadTranslator(
              Conversions.UINT64_TO_STRING.celOverloadDecl(),
              createUninterpretedConversion(Conversions.UINT64_TO_STRING))
          .addOverloadTranslator(
              Conversions.DOUBLE_TO_STRING.celOverloadDecl(),
              createUninterpretedConversion(Conversions.DOUBLE_TO_STRING))
          .addOverloadTranslator(
              Conversions.BOOL_TO_STRING.celOverloadDecl(),
              createUninterpretedConversion(Conversions.BOOL_TO_STRING))
          .addOverloadTranslator(
              Conversions.BYTES_TO_STRING.celOverloadDecl(),
              createUninterpretedConversion(Conversions.BYTES_TO_STRING))
          .addOverloadTranslator(
              Conversions.TIMESTAMP_TO_STRING.celOverloadDecl(),
              createUninterpretedConversion(Conversions.TIMESTAMP_TO_STRING))
          .addOverloadTranslator(
              Conversions.DURATION_TO_STRING.celOverloadDecl(),
              createUninterpretedConversion(Conversions.DURATION_TO_STRING))
          .build();

  private static final CelZ3FunctionAxiom BYTES_AXIOM =
      CelZ3FunctionAxiom.newBuilder(StandardFunction.BYTES.functionDecl())
          .addUnaryOverloadTranslator(
              Conversions.BYTES_TO_BYTES.celOverloadDecl(),
              (ctx, typeSystem, sink, arg) -> Optional.of(arg))
          .addOverloadTranslator(
              Conversions.STRING_TO_BYTES.celOverloadDecl(),
              createUninterpretedConversion(Conversions.STRING_TO_BYTES))
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
          .addOverloadTranslator(
              Conversions.STRING_TO_DURATION.celOverloadDecl(),
              createUninterpretedConversion(Conversions.STRING_TO_DURATION))
          .build();

  private static final CelZ3FunctionAxiom TIMESTAMP_AXIOM =
      CelZ3FunctionAxiom.newBuilder(StandardFunction.TIMESTAMP.functionDecl())
          .addUnaryOverloadTranslator(
              Conversions.TIMESTAMP_TO_TIMESTAMP.celOverloadDecl(),
              (ctx, typeSystem, sink, arg) -> Optional.of(arg))
          .addOverloadTranslator(
              Conversions.STRING_TO_TIMESTAMP.celOverloadDecl(),
              createUninterpretedConversion(Conversions.STRING_TO_TIMESTAMP))
          .addUnaryOverloadTranslator(
              Conversions.INT64_TO_TIMESTAMP.celOverloadDecl(),
              (ctx, typeSystem, sink, arg) -> {
                IntExpr intVal = typeSystem.getInt(arg);
                BoolExpr overflow = typeSystem.checkTimestampOverflow(intVal);
                return Optional.of(
                    typeSystem.withRuntimeError(typeSystem.wrapTimestamp(intVal), overflow));
              })
          .build();

  private static final CelZ3FunctionAxiom BOOL_AXIOM =
      CelZ3FunctionAxiom.newBuilder(StandardFunction.BOOL.functionDecl())
          .addUnaryOverloadTranslator(
              Conversions.BOOL_TO_BOOL.celOverloadDecl(),
              (ctx, typeSystem, sink, arg) -> Optional.of(arg))
          .addOverloadTranslator(
              Conversions.STRING_TO_BOOL.celOverloadDecl(),
              createUninterpretedConversion(Conversions.STRING_TO_BOOL))
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

  private static CelZ3OverloadTranslator createUninterpretedConversion(Conversions conversion) {
    return (ctx, typeSystem, sink, unwrappedArgs, argApproximations) -> {
      Expr<?> arg = unwrappedArgs.get(0);
      BoolExpr baseApprox = argApproximations.get(0);

      FuncDecl<?> funcDecl =
          typeSystem.internFuncDecl(
              conversion.celOverloadDecl().overloadId(),
              new Sort[] {typeSystem.celValueSort()},
              typeSystem.celValueSort());
      Expr<?> res = ctx.mkApp(funcDecl, arg);

      switch (conversion.celOverloadDecl().resultType().kind()) {
        case INT:
          sink.accept(ctx.mkOr(typeSystem.isInt(res), typeSystem.isError(res)));
          sink.accept(
              ctx.mkImplies(
                  typeSystem.isInt(res),
                  ctx.mkNot(typeSystem.checkIntOverflow(typeSystem.getInt(res)))));
          break;
        case TIMESTAMP:
          sink.accept(ctx.mkOr(typeSystem.isTimestamp(res), typeSystem.isError(res)));
          sink.accept(
              ctx.mkImplies(
                  typeSystem.isTimestamp(res),
                  ctx.mkNot(typeSystem.checkTimestampOverflow(typeSystem.getTimestamp(res)))));
          break;
        case DURATION:
          sink.accept(ctx.mkOr(typeSystem.isDuration(res), typeSystem.isError(res)));
          sink.accept(
              ctx.mkImplies(
                  typeSystem.isDuration(res),
                  ctx.mkNot(typeSystem.checkDurationOverflow(typeSystem.getDuration(res)))));
          break;
        case UINT:
          sink.accept(ctx.mkOr(typeSystem.isUint(res), typeSystem.isError(res)));
          sink.accept(
              ctx.mkImplies(
                  typeSystem.isUint(res),
                  ctx.mkNot(typeSystem.checkUintOverflow(typeSystem.getUint(res)))));
          break;
        case DOUBLE:
          sink.accept(ctx.mkOr(typeSystem.isDouble(res), typeSystem.isError(res)));
          sink.accept(
              ctx.mkImplies(
                  typeSystem.isDouble(res), ctx.mkNot(ctx.mkFPIsNaN(typeSystem.getDouble(res)))));
          break;
        case STRING:
          sink.accept(ctx.mkOr(typeSystem.isString(res), typeSystem.isError(res)));
          break;
        case BYTES:
          sink.accept(ctx.mkOr(typeSystem.isBytes(res), typeSystem.isError(res)));
          break;
        case BOOL:
          sink.accept(ctx.mkOr(typeSystem.isBool(res), typeSystem.isError(res)));
          break;
        default:
          throw new IllegalArgumentException(
              "Unsupported uninterpreted conversion result type: "
                  + conversion.celOverloadDecl().resultType());
      }

      boolean isArgConstant = typeSystem.isPrimitiveConstant(arg);
      boolean isStringParseConversion =
          conversion.celOverloadDecl().parameterTypes().get(0).equals(SimpleType.STRING);

      BoolExpr finalApprox =
          (!isArgConstant && isStringParseConversion) ? baseApprox : ctx.mkTrue();

      return Optional.of(CelZ3OverloadResult.create(res, finalApprox));
    };
  }

  private TypeConversionAxioms() {}
}
