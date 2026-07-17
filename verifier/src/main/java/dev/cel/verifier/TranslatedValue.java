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

package dev.cel.verifier;

import com.google.auto.value.AutoValue;
import com.microsoft.z3.BoolExpr;
import com.microsoft.z3.Context;
import com.microsoft.z3.Expr;
import dev.cel.common.ast.CelConstant;
import dev.cel.common.ast.CelExpr;
import dev.cel.common.ast.CelExpr.ExprKind;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** Encapsulates a Z3 expression and its corresponding CEL AST node. */
@AutoValue
abstract class TranslatedValue {
  abstract Expr<?> z3Expr();

  abstract Optional<CelExpr> celExpr();

  abstract CelZ3TypeSystem typeSystem();

  abstract BoolExpr isApproximate();

  /** Safely checks if this is a specific literal type */
  boolean isLiteral(ExprKind.Kind kind) {
    return celExpr().map(node -> node.exprKind().getKind() == kind).orElse(false);
  }

  /** Safely extracts a list element AST if it exists */
  Optional<CelExpr> listElementAt(int index) {
    return celExpr()
        .filter(node -> node.exprKind().getKind() == ExprKind.Kind.LIST)
        .filter(node -> index < node.list().elements().size())
        .map(node -> node.list().elements().get(index));
  }

  boolean isNumericConstant() {
    return celExpr()
        .filter(node -> node.exprKind().getKind() == ExprKind.Kind.CONSTANT)
        .map(node -> node.constant().getKind())
        .map(
            kind ->
                kind == CelConstant.Kind.INT64_VALUE
                    || kind == CelConstant.Kind.UINT64_VALUE
                    || kind == CelConstant.Kind.DOUBLE_VALUE)
        .orElse(false);
  }

  BoolExpr isZ3Bool() {
    return typeSystem().isBool(z3Expr());
  }

  Expr<?> unwrapZ3Bool() {
    return typeSystem().unwrapBool(z3Expr());
  }

  BoolExpr isZ3Error() {
    return typeSystem().isError(z3Expr());
  }

  BoolExpr isZ3Unknown() {
    return typeSystem().isUnknown(z3Expr());
  }

  static TranslatedValue create(
      Expr<?> z3Expr, CelExpr celExpr, CelZ3TypeSystem typeSystem, BoolExpr isApproximate) {
    return new AutoValue_TranslatedValue(z3Expr, Optional.of(celExpr), typeSystem, isApproximate);
  }

  static TranslatedValue create(
      Expr<?> z3Expr,
      Optional<CelExpr> celExpr,
      CelZ3TypeSystem typeSystem,
      BoolExpr isApproximate) {
    return new AutoValue_TranslatedValue(z3Expr, celExpr, typeSystem, isApproximate);
  }

  static TranslatedValue create(
      Expr<?> z3Expr, CelZ3TypeSystem typeSystem, BoolExpr isApproximate) {
    return new AutoValue_TranslatedValue(z3Expr, Optional.empty(), typeSystem, isApproximate);
  }

  /**
   * Applies strict CEL evaluation semantics.
   *
   * <p>If any argument is an Exact Error, the result safely short-circuits to Error.
   *
   * <p>If any argument is an Exact Unknown (and no Errors exist), it safely short-circuits to
   * Unknown.
   *
   * <p>Otherwise, it computes whether the final result is tainted by any approximate values.
   */
  static TranslatedValue propagateStrict(
      Context ctx, CelZ3TypeSystem ts, Expr<?> baseResult, Collection<TranslatedValue> args) {
    return propagateStrict(ctx, ts, baseResult, Optional.empty(), args);
  }

  static TranslatedValue propagateStrict(
      Context ctx, CelZ3TypeSystem ts, Expr<?> baseResult, TranslatedValue... args) {
    return propagateStrict(ctx, ts, baseResult, Optional.empty(), Arrays.asList(args));
  }

  static TranslatedValue propagateStrict(
      Context ctx,
      CelZ3TypeSystem ts,
      Expr<?> baseResult,
      CelExpr celExpr,
      Collection<TranslatedValue> args) {
    return propagateStrict(ctx, ts, baseResult, Optional.of(celExpr), args);
  }

  static TranslatedValue propagateStrict(
      Context ctx,
      CelZ3TypeSystem ts,
      Expr<?> baseResult,
      Optional<CelExpr> celExpr,
      Collection<TranslatedValue> args) {
    return propagateStrict(ctx, ts, baseResult, celExpr, ctx.mkFalse(), args);
  }

  static TranslatedValue propagateStrict(
      Context ctx,
      CelZ3TypeSystem ts,
      Expr<?> baseResult,
      Optional<CelExpr> celExpr,
      BoolExpr baseTaint,
      Collection<TranslatedValue> args) {
    List<BoolExpr> exactErrors = new ArrayList<>();
    List<BoolExpr> exactUnknowns = new ArrayList<>();
    List<BoolExpr> errors = new ArrayList<>();
    List<BoolExpr> unknowns = new ArrayList<>();
    List<BoolExpr> taints = new ArrayList<>();
    taints.add(baseTaint);

    boolean hasNonConstantArgs = false;
    List<TranslatedValue> argsList = new ArrayList<>(args);
    for (int i = argsList.size() - 1; i >= 0; i--) {
      TranslatedValue arg = argsList.get(i);
      taints.add(arg.isApproximate());
      if (arg.isLiteral(ExprKind.Kind.CONSTANT)) {
        continue;
      }
      hasNonConstantArgs = true;

      Expr<?> z3Expr = arg.z3Expr();
      BoolExpr isApprox = arg.isApproximate();
      BoolExpr isError = ts.isError(z3Expr);
      BoolExpr isUnknown = ts.isUnknown(z3Expr);

      errors.add(isError);
      unknowns.add(isUnknown);

      exactErrors.add(
          CelZ3TypeSystem.mkAndFlattened(
              ctx, Arrays.asList(isError, CelZ3TypeSystem.mkNotFlattened(ctx, isApprox))));
      exactUnknowns.add(
          CelZ3TypeSystem.mkAndFlattened(
              ctx, Arrays.asList(isUnknown, CelZ3TypeSystem.mkNotFlattened(ctx, isApprox))));
    }

    BoolExpr anyTaint = CelZ3TypeSystem.mkOrFlattened(ctx, taints);
    if (!hasNonConstantArgs) {
      return create(baseResult, celExpr, ts, anyTaint);
    }

    BoolExpr hasExactError = CelZ3TypeSystem.mkOrFlattened(ctx, exactErrors);
    BoolExpr hasExactUnknown = CelZ3TypeSystem.mkOrFlattened(ctx, exactUnknowns);
    BoolExpr hasError = CelZ3TypeSystem.mkOrFlattened(ctx, errors);
    BoolExpr hasUnknown = CelZ3TypeSystem.mkOrFlattened(ctx, unknowns);

    Expr<?> finalResult =
        CelZ3TypeSystem.SwitchBuilder.newBuilder(ctx)
            .addCase(hasUnknown, ts.mkUnknown())
            .addCase(hasError, ts.mkError())
            .build(baseResult);

    BoolExpr isSafe =
        CelZ3TypeSystem.mkOrFlattened(
            ctx,
            Arrays.asList(
                hasExactUnknown,
                CelZ3TypeSystem.mkAndFlattened(
                    ctx,
                    Arrays.asList(hasExactError, CelZ3TypeSystem.mkNotFlattened(ctx, hasUnknown))),
                CelZ3TypeSystem.mkNotFlattened(ctx, anyTaint)));

    return create(finalResult, celExpr, ts, CelZ3TypeSystem.mkNotFlattened(ctx, isSafe));
  }

  /**
   * Returns a new TranslatedValue with an additional approximation condition OR'd into the
   * approximation flag.
   */
  TranslatedValue withApproximation(BoolExpr approxCondition) {
    return create(
        z3Expr(),
        celExpr(),
        typeSystem(),
        typeSystem().ctx().mkOr(isApproximate(), approxCondition));
  }
}

