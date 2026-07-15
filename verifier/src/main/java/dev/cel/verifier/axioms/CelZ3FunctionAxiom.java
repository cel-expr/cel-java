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

import com.google.auto.value.AutoValue;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.errorprone.annotations.Immutable;
import com.microsoft.z3.BoolExpr;
import com.microsoft.z3.Context;
import com.microsoft.z3.Expr;
import dev.cel.common.CelFunctionDecl;
import dev.cel.common.CelOverloadDecl;
import dev.cel.verifier.CelZ3TypeSystem;
import java.util.Optional;
import java.util.function.Consumer;

/** Defines a self-contained translation and axiomatization for a specific CEL function. */
@AutoValue
@Immutable
public abstract class CelZ3FunctionAxiom {

  /** Functional interface for translating unary overloads. */
  @FunctionalInterface
  @Immutable
  public interface UnaryTranslator {
    Optional<Expr<?>> translate(
        Context ctx, CelZ3TypeSystem typeSystem, Consumer<BoolExpr> constraintSink, Expr<?> arg);
  }

  /** Functional interface for translating binary overloads. */
  @FunctionalInterface
  @Immutable
  public interface BinaryTranslator {
    Optional<Expr<?>> translate(
        Context ctx,
        CelZ3TypeSystem typeSystem,
        Consumer<BoolExpr> constraintSink,
        Expr<?> arg1,
        Expr<?> arg2);
  }

  /** Functional interface for translating ternary overloads. */
  @FunctionalInterface
  @Immutable
  public interface TernaryTranslator {
    Optional<Expr<?>> translate(
        Context ctx,
        CelZ3TypeSystem typeSystem,
        Consumer<BoolExpr> constraintSink,
        Expr<?> arg1,
        Expr<?> arg2,
        Expr<?> arg3);
  }

  /** The canonical CEL function declaration handled by this axiom. */
  public abstract CelFunctionDecl declaration();

  /** Mapping from overloadId to its SMT translation strategy. */
  public abstract ImmutableMap<String, CelZ3OverloadTranslator> overloadTranslators();

  public static Builder newBuilder(CelFunctionDecl declaration) {
    return new AutoValue_CelZ3FunctionAxiom.Builder().setDeclaration(declaration);
  }

  /** Builder for {@link CelZ3FunctionAxiom}. */
  @AutoValue.Builder
  public abstract static class Builder {
    @CanIgnoreReturnValue
    public abstract Builder setDeclaration(CelFunctionDecl value);

    abstract ImmutableMap.Builder<String, CelZ3OverloadTranslator> overloadTranslatorsBuilder();

    @CanIgnoreReturnValue
    public Builder addOverloadTranslator(String overloadId, CelZ3OverloadTranslator translator) {
      overloadTranslatorsBuilder().put(overloadId, translator);
      return this;
    }

    @CanIgnoreReturnValue
    public Builder addOverloadTranslator(
        CelOverloadDecl overloadDecl, CelZ3OverloadTranslator translator) {
      return addOverloadTranslator(overloadDecl.overloadId(), translator);
    }

    @CanIgnoreReturnValue
    public Builder addUnaryOverloadTranslator(
        String overloadId, UnaryTranslator translator, boolean isApproximated) {
      CelZ3OverloadTranslator overloadTranslator =
          (ctx, ts, sink, args, argApproximations) -> {
            Preconditions.checkArgument(
                args.size() == 1, "%s overload requires exactly 1 argument", overloadId);
            Optional<Expr<?>> res = translator.translate(ctx, ts, sink, args.get(0));
            if (!res.isPresent()) {
              return Optional.empty();
            }
            Expr<?> val = res.get();
            BoolExpr approx = argApproximations.get(0);
            if (isApproximated) {
              BoolExpr isErrorOrUnknown = ctx.mkOr(ts.isError(val), ts.isUnknown(val));
              approx = (BoolExpr) ctx.mkITE(isErrorOrUnknown, approx, ctx.mkTrue());
            }
            return Optional.of(CelZ3OverloadResult.create(val, approx));
          };
      return addOverloadTranslator(overloadId, overloadTranslator);
    }

    @CanIgnoreReturnValue
    public Builder addUnaryOverloadTranslator(
        CelOverloadDecl overloadDecl, UnaryTranslator translator, boolean isApproximated) {
      return addUnaryOverloadTranslator(overloadDecl.overloadId(), translator, isApproximated);
    }

    @CanIgnoreReturnValue
    public Builder addUnaryOverloadTranslator(String overloadId, UnaryTranslator translator) {
      return addUnaryOverloadTranslator(overloadId, translator, false);
    }

    @CanIgnoreReturnValue
    public Builder addUnaryOverloadTranslator(
        CelOverloadDecl overloadDecl, UnaryTranslator translator) {
      return addUnaryOverloadTranslator(overloadDecl.overloadId(), translator, false);
    }

    @CanIgnoreReturnValue
    public Builder addBinaryOverloadTranslator(String overloadId, BinaryTranslator translator) {
      return addOverloadTranslator(
          overloadId,
          (ctx, ts, sink, args, argApproximations) -> {
            Preconditions.checkArgument(
                args.size() == 2, "%s overload requires exactly 2 arguments", overloadId);
            Optional<Expr<?>> res = translator.translate(ctx, ts, sink, args.get(0), args.get(1));
            if (!res.isPresent()) {
              return Optional.empty();
            }
            Expr<?> val = res.get();
            BoolExpr approx = ctx.mkOr(argApproximations.get(0), argApproximations.get(1));
            return Optional.of(CelZ3OverloadResult.create(val, approx));
          });
    }

    @CanIgnoreReturnValue
    public Builder addBinaryOverloadTranslator(
        CelOverloadDecl overloadDecl, BinaryTranslator translator) {
      return addBinaryOverloadTranslator(overloadDecl.overloadId(), translator);
    }

    @CanIgnoreReturnValue
    public Builder addTernaryOverloadTranslator(String overloadId, TernaryTranslator translator) {
      return addOverloadTranslator(
          overloadId,
          (ctx, ts, sink, args, argApproximations) -> {
            Preconditions.checkArgument(
                args.size() == 3, "%s overload requires exactly 3 arguments", overloadId);
            Optional<Expr<?>> res =
                translator.translate(ctx, ts, sink, args.get(0), args.get(1), args.get(2));
            if (!res.isPresent()) {
              return Optional.empty();
            }
            Expr<?> val = res.get();
            BoolExpr approx =
                ctx.mkOr(
                    argApproximations.get(0),
                    ctx.mkOr(argApproximations.get(1), argApproximations.get(2)));
            return Optional.of(CelZ3OverloadResult.create(val, approx));
          });
    }

    @CanIgnoreReturnValue
    public Builder addTernaryOverloadTranslator(
        CelOverloadDecl overloadDecl, TernaryTranslator translator) {
      return addTernaryOverloadTranslator(overloadDecl.overloadId(), translator);
    }

    public abstract CelZ3FunctionAxiom build();
  }
}
