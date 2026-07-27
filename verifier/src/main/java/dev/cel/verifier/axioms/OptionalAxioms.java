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

import static dev.cel.extensions.CelOptionalLibrary.OptionalDeclaration.OPTIONAL_HAS_VALUE;
import static dev.cel.extensions.CelOptionalLibrary.OptionalDeclaration.OPTIONAL_NONE;
import static dev.cel.extensions.CelOptionalLibrary.OptionalDeclaration.OPTIONAL_OF;
import static dev.cel.extensions.CelOptionalLibrary.OptionalDeclaration.OPTIONAL_OF_NON_ZERO_VALUE;
import static dev.cel.extensions.CelOptionalLibrary.OptionalDeclaration.OPTIONAL_OR;
import static dev.cel.extensions.CelOptionalLibrary.OptionalDeclaration.OPTIONAL_OR_VALUE;
import static dev.cel.extensions.CelOptionalLibrary.OptionalDeclaration.OPTIONAL_SELECT;
import static dev.cel.extensions.CelOptionalLibrary.OptionalDeclaration.OPTIONAL_VALUE;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.microsoft.z3.ArrayExpr;
import com.microsoft.z3.BoolExpr;
import com.microsoft.z3.Context;
import com.microsoft.z3.Expr;
import com.microsoft.z3.FPExpr;
import com.microsoft.z3.SeqExpr;
import dev.cel.common.CelFunctionDecl;
import dev.cel.common.CelOverloadDecl;
import dev.cel.verifier.CelZ3TypeSystem;
import java.util.Optional;

/** Axiomatization for CEL's optional library functions. */
@SuppressWarnings({"unchecked", "rawtypes"}) // Z3 Java API uses raw types.
final class OptionalAxioms {

  static final ImmutableList<CelZ3FunctionAxiom> ALL_AXIOMS =
      ImmutableList.of(
          createAxiom(
              OPTIONAL_NONE,
              (ctx, ts, sink, args, argApproximations) ->
                  Optional.of(CelZ3OverloadResult.create(ts.mkOptionalNone(), ctx.mkFalse()))),
          createUnaryAxiom(
              OPTIONAL_OF,
              (ctx, ts, sink, value) -> {
                Expr<?> optRef = ctx.mkApp(ts.optionalOfRefFunc(), value);
                sink.accept(ctx.mkEq(ts.getOptionalValue(optRef), value));
                sink.accept(ts.optHasValue(optRef));
                return Optional.of(ts.mkOptionalOf(optRef));
              }),
          createUnaryAxiom(
              OPTIONAL_OF_NON_ZERO_VALUE,
              (ctx, ts, sink, value) -> {
                Expr<?> optRef = ctx.mkApp(ts.optionalOfRefFunc(), value);
                BoolExpr isZero = isZeroValue(ctx, ts, value);
                sink.accept(
                    ctx.mkImplies(ctx.mkNot(isZero), ctx.mkEq(ts.getOptionalValue(optRef), value)));
                sink.accept(ctx.mkImplies(ctx.mkNot(isZero), ts.optHasValue(optRef)));
                return Optional.of(ctx.mkITE(isZero, ts.mkOptionalNone(), ts.mkOptionalOf(optRef)));
              }),
          createUnaryAxiom(
              OPTIONAL_HAS_VALUE,
              (ctx, ts, sink, val) ->
                  Optional.of(ts.wrapBool(ts.optHasValue(ts.getOptionalRef(val))))),
          createUnaryAxiom(
              OPTIONAL_VALUE,
              (ctx, ts, sink, val) -> {
                Expr<?> optRef = ts.getOptionalRef(val);
                return Optional.of(
                    ctx.mkITE(ts.optHasValue(optRef), ts.getOptionalValue(optRef), ts.mkError()));
              }),
          createBinaryAxiom(
              OPTIONAL_OR_VALUE,
              (ctx, ts, sink, val, other) -> {
                Expr<?> optRef = ts.getOptionalRef(val);
                return Optional.of(
                    ctx.mkITE(ts.optHasValue(optRef), ts.getOptionalValue(optRef), other));
              }),
          createBinaryAxiom(
              OPTIONAL_OR,
              (ctx, ts, sink, val, other) -> {
                Expr<?> optRef = ts.getOptionalRef(val);
                return Optional.of(ctx.mkITE(ts.optHasValue(optRef), val, other));
              }),
          createBinaryAxiom(
              OPTIONAL_SELECT,
              (ctx, ts, sink, operand, field) -> {
                Expr<?> optRef = ts.getOptionalRef(operand);
                BoolExpr isOpt = ts.isOptional(operand);
                BoolExpr hasValue = ts.optHasValue(optRef);
                Expr<?> actualOperand = ctx.mkITE(isOpt, ts.getOptionalValue(optRef), operand);

                BoolExpr isMap = ts.isMap(actualOperand);
                BoolExpr isMsg = ts.isMessage(actualOperand);
                BoolExpr isValidTarget = ctx.mkOr(isMap, isMsg);

                Expr<?> msgFieldZ3Str = ts.getString(field);
                Expr<?> mapFieldCelVal = field;

                Expr<?> msgRef = ts.getMessageRef(actualOperand);
                Expr<?> mapRef = ts.getMapRef(actualOperand);

                Expr<?> presence =
                    CelZ3TypeSystem.SwitchBuilder.newBuilder(ctx)
                        .addCase(
                            isMsg,
                            ctx.mkSelect((ArrayExpr) ts.getMsgPresence(msgRef), msgFieldZ3Str))
                        .addCase(
                            isMap,
                            ctx.mkSelect((ArrayExpr) ts.getMapPresence(mapRef), mapFieldCelVal))
                        .build(ctx.mkFalse());

                Expr<?> value =
                    CelZ3TypeSystem.SwitchBuilder.newBuilder(ctx)
                        .addCase(
                            isMsg, ctx.mkSelect((ArrayExpr) ts.getMsgValues(msgRef), msgFieldZ3Str))
                        .addCase(
                            isMap,
                            ctx.mkSelect((ArrayExpr) ts.getMapValues(mapRef), mapFieldCelVal))
                        .build(ts.mkError());

                BoolExpr valNotError = ctx.mkNot(ctx.mkEq(value, ts.mkError()));
                BoolExpr shouldEvaluate = (BoolExpr) ctx.mkITE(isOpt, hasValue, ctx.mkTrue());
                sink.accept(
                    ctx.mkImplies(
                        CelZ3TypeSystem.mkAndFlattened(
                            ctx, shouldEvaluate, isValidTarget, (BoolExpr) presence),
                        valNotError));

                Expr<?> resultOptRef = ctx.mkApp(ts.optionalOfRefFunc(), value);
                sink.accept(ctx.mkEq(ts.getOptionalValue(resultOptRef), value));
                sink.accept(ts.optHasValue(resultOptRef));

                Expr<?> optionalResult =
                    ctx.mkITE(
                        (BoolExpr) presence, ts.mkOptionalOf(resultOptRef), ts.mkOptionalNone());

                Expr<?> result = ctx.mkITE(isValidTarget, optionalResult, ts.mkError());
                return Optional.of(
                    ctx.mkITE(ctx.mkAnd(isOpt, ctx.mkNot(hasValue)), ts.mkOptionalNone(), result));
              }));

  private static BoolExpr isZeroValue(Context ctx, CelZ3TypeSystem ts, Expr<?> val) {
    return ctx.mkOr(
        ts.isNull(val),
        ctx.mkAnd(ts.isBool(val), ctx.mkEq(ts.unwrapBool(val), ctx.mkFalse())),
        ctx.mkAnd(ts.isInt(val), ctx.mkEq(ts.getInt(val), ctx.mkInt(0))),
        ctx.mkAnd(ts.isUint(val), ctx.mkEq(ts.getUint(val), ctx.mkInt(0))),
        ctx.mkAnd(ts.isDouble(val), ctx.mkFPIsZero((FPExpr) ts.getDouble(val))),
        ctx.mkAnd(ts.isString(val), ctx.mkEq(ts.getString(val), ctx.mkString(""))),
        ctx.mkAnd(
            ts.isBytes(val), ctx.mkEq(ctx.mkLength((SeqExpr) ts.getBytes(val)), ctx.mkInt(0))),
        ctx.mkAnd(
            ts.isList(val), ctx.mkEq(ctx.mkLength(ts.getSeq(ts.getListRef(val))), ctx.mkInt(0))),
        ctx.mkAnd(
            ts.isMap(val), ctx.mkEq(ctx.mkLength(ts.getMapKeys(ts.getMapRef(val))), ctx.mkInt(0))),
        ctx.mkAnd(
            ts.isMessage(val),
            ctx.mkEq(
                ts.getMsgPresence(ts.getMessageRef(val)),
                ctx.mkConstArray(ctx.getStringSort(), ctx.mkFalse()))));
  }

  private static CelZ3FunctionAxiom createAxiom(
      CelFunctionDecl.Declarer declarer, CelZ3OverloadTranslator translator) {
    CelFunctionDecl functionDecl = declarer.functionDecl();
    CelZ3FunctionAxiom.Builder builder = CelZ3FunctionAxiom.newBuilder(functionDecl);
    builder.addOverloadTranslator(getSingleOverloadOrThrow(functionDecl), translator);
    return builder.build();
  }

  private static CelZ3FunctionAxiom createUnaryAxiom(
      CelFunctionDecl.Declarer declarer, CelZ3FunctionAxiom.UnaryTranslator translator) {
    CelFunctionDecl functionDecl = declarer.functionDecl();
    CelZ3FunctionAxiom.Builder builder = CelZ3FunctionAxiom.newBuilder(functionDecl);
    builder.addUnaryOverloadTranslator(getSingleOverloadOrThrow(functionDecl), translator);
    return builder.build();
  }

  private static CelZ3FunctionAxiom createBinaryAxiom(
      CelFunctionDecl.Declarer declarer, CelZ3FunctionAxiom.BinaryTranslator translator) {
    CelFunctionDecl functionDecl = declarer.functionDecl();
    CelZ3FunctionAxiom.Builder builder = CelZ3FunctionAxiom.newBuilder(functionDecl);
    builder.addBinaryOverloadTranslator(getSingleOverloadOrThrow(functionDecl), translator);
    return builder.build();
  }

  private static CelOverloadDecl getSingleOverloadOrThrow(CelFunctionDecl functionDecl) {
    Preconditions.checkArgument(
        functionDecl.overloads().size() == 1,
        "Expected 1 overload for function %s, but found %s.",
        functionDecl.name(),
        functionDecl.overloads().size());
    return functionDecl.overloads().iterator().next();
  }

  private OptionalAxioms() {}
}
