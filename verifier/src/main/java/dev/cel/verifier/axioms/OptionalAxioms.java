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

import com.google.common.collect.ImmutableList;
import com.microsoft.z3.BoolExpr;
import com.microsoft.z3.Context;
import com.microsoft.z3.Expr;
import com.microsoft.z3.FPExpr;
import com.microsoft.z3.SeqExpr;
import dev.cel.common.CelFunctionDecl;
import dev.cel.extensions.CelOptionalLibrary;
import dev.cel.extensions.CelOptionalLibrary.Function;
import dev.cel.verifier.CelZ3TypeSystem;
import java.util.Optional;

/** Axiomatization for CEL's optional library functions. */
@SuppressWarnings({"unchecked", "rawtypes"}) // Z3 Java API uses raw types.
final class OptionalAxioms {

  static final ImmutableList<CelZ3FunctionAxiom> ALL_AXIOMS =
      ImmutableList.of(
          createAxiom(
              Function.OPTIONAL_NONE,
              "optional_none",
              (ctx, ts, sink, args, argApproximations) ->
                  Optional.of(CelZ3OverloadResult.create(ts.mkOptionalNone(), ctx.mkFalse()))),
          createUnaryAxiom(
              Function.OPTIONAL_OF,
              "optional_of",
              (ctx, ts, sink, value) -> {
                Expr<?> optRef = ctx.mkApp(ts.optionalOfRefFunc(), value);
                sink.accept(ctx.mkEq(ts.getOptionalValue(optRef), value));
                sink.accept(ts.optHasValue(optRef));
                return Optional.of(ts.mkOptionalOf(optRef));
              }),
          createUnaryAxiom(
              Function.OPTIONAL_OF_NON_ZERO_VALUE,
              "optional_ofNonZeroValue",
              (ctx, ts, sink, value) -> {
                Expr<?> optRef = ctx.mkApp(ts.optionalOfRefFunc(), value);
                BoolExpr isZero = isZeroValue(ctx, ts, value);
                sink.accept(
                    ctx.mkImplies(ctx.mkNot(isZero), ctx.mkEq(ts.getOptionalValue(optRef), value)));
                sink.accept(ctx.mkImplies(ctx.mkNot(isZero), ts.optHasValue(optRef)));
                return Optional.of(ctx.mkITE(isZero, ts.mkOptionalNone(), ts.mkOptionalOf(optRef)));
              }),
          createUnaryAxiom(
              Function.HAS_VALUE,
              "optional_hasValue",
              (ctx, ts, sink, val) ->
                  Optional.of(ts.wrapBool(ts.optHasValue(ts.getOptionalRef(val))))),
          createUnaryAxiom(
              Function.VALUE,
              "optional_value",
              (ctx, ts, sink, val) -> {
                Expr<?> optRef = ts.getOptionalRef(val);
                return Optional.of(
                    ctx.mkITE(ts.optHasValue(optRef), ts.getOptionalValue(optRef), ts.mkError()));
              }),
          createBinaryAxiom(
              Function.OR_VALUE,
              "optional_orValue_value",
              (ctx, ts, sink, val, other) -> {
                Expr<?> optRef = ts.getOptionalRef(val);
                return Optional.of(
                    ctx.mkITE(ts.optHasValue(optRef), ts.getOptionalValue(optRef), other));
              }),
          createBinaryAxiom(
              Function.OR,
              "optional_or_optional",
              (ctx, ts, sink, val, other) -> {
                Expr<?> optRef = ts.getOptionalRef(val);
                return Optional.of(ctx.mkITE(ts.optHasValue(optRef), val, other));
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

  private static CelFunctionDecl getDecl(Function funcEnum) {
    return CelOptionalLibrary.INSTANCE.functions().stream()
        .filter(d -> d.name().equals(funcEnum.getFunction()))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("Unknown function: " + funcEnum));
  }

  private static CelZ3FunctionAxiom createAxiom(
      Function funcEnum, String overloadId, CelZ3OverloadTranslator translator) {
    return CelZ3FunctionAxiom.newBuilder(getDecl(funcEnum))
        .addOverloadTranslator(overloadId, translator)
        .build();
  }

  private static CelZ3FunctionAxiom createUnaryAxiom(
      Function funcEnum, String overloadId, CelZ3FunctionAxiom.UnaryTranslator translator) {
    return CelZ3FunctionAxiom.newBuilder(getDecl(funcEnum))
        .addUnaryOverloadTranslator(overloadId, translator)
        .build();
  }

  private static CelZ3FunctionAxiom createBinaryAxiom(
      Function funcEnum, String overloadId, CelZ3FunctionAxiom.BinaryTranslator translator) {
    return CelZ3FunctionAxiom.newBuilder(getDecl(funcEnum))
        .addBinaryOverloadTranslator(overloadId, translator)
        .build();
  }

  private OptionalAxioms() {}
}
