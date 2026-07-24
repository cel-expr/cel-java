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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.errorprone.annotations.Immutable;
import com.microsoft.z3.BoolExpr;
import com.microsoft.z3.Context;
import com.microsoft.z3.Model;
import com.microsoft.z3.Params;
import com.microsoft.z3.Solver;
import com.microsoft.z3.Status;
import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.types.CelType;
import dev.cel.common.types.CelTypeProvider;
import dev.cel.verifier.axioms.CelZ3FunctionAxiom;
import dev.cel.verifier.axioms.CelZ3StandardAxioms;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** Z3 implementation of the CelVerifier. */
@Immutable
public final class CelVerifierZ3Impl implements CelVerifier {

  @VisibleForTesting
  static final CelTypeProvider EMPTY_TYPE_PROVIDER =
      new CelTypeProvider() {
        @Override
        public ImmutableList<CelType> types() {
          return ImmutableList.of();
        }

        @Override
        public Optional<CelType> findType(String typeName) {
          return Optional.empty();
        }
      };

  private final Duration timeout;
  private final int comprehensionUnrollLimit;
  private final ImmutableSet<String> unknownIdentifiers;
  private final CelZ3FunctionRegistry functionRegistry;
  private final CelTypeProvider typeProvider;

  static Builder newBuilder() {
    return new Builder();
  }

  public static final class Builder implements CelVerifierBuilder {
    private Duration timeout;
    private int comprehensionUnrollLimit;
    private final ImmutableSet.Builder<String> unknownIdentifiers;
    private final ImmutableList.Builder<CelZ3FunctionAxiom> functionAxioms;
    private CelTypeProvider typeProvider;

    private Builder() {
      this.timeout = Duration.ofSeconds(10);
      this.comprehensionUnrollLimit = 5;
      this.unknownIdentifiers = ImmutableSet.builder();
      this.functionAxioms = ImmutableList.builder();
      this.typeProvider = EMPTY_TYPE_PROVIDER;
    }

    @Override
    @CanIgnoreReturnValue
    public Builder setTimeout(Duration timeout) {
      if (timeout.isZero() || timeout.isNegative()) {
        throw new IllegalArgumentException("Timeout must be strictly positive.");
      }
      this.timeout = timeout;
      return this;
    }

    @Override
    @CanIgnoreReturnValue
    public CelVerifierBuilder addUnknownIdentifier(String identifier) {
      unknownIdentifiers.add(identifier);
      return this;
    }

    @Override
    @CanIgnoreReturnValue
    public CelVerifierBuilder setTypeProvider(CelTypeProvider typeProvider) {
      this.typeProvider = Preconditions.checkNotNull(typeProvider);
      return this;
    }

    @Override
    @CanIgnoreReturnValue
    public CelVerifierBuilder setComprehensionUnrollLimit(int unrollLimit) {
      Preconditions.checkArgument(unrollLimit >= 0, "unrollLimit must be non-negative");
      this.comprehensionUnrollLimit = unrollLimit;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder addFunctionAxioms(CelZ3FunctionAxiom... axioms) {
      return addFunctionAxioms(Arrays.asList(axioms));
    }

    @CanIgnoreReturnValue
    public Builder addFunctionAxioms(Iterable<CelZ3FunctionAxiom> axioms) {
      functionAxioms.addAll(axioms);
      return this;
    }

    @Override
    public CelVerifier build() {
      ImmutableList<CelZ3FunctionAxiom> allFunctionAxioms =
          ImmutableList.<CelZ3FunctionAxiom>builder()
              .addAll(CelZ3StandardAxioms.functionAxioms())
              .addAll(functionAxioms.build())
              .build();

      CelZ3FunctionRegistry registry = CelZ3FunctionRegistry.create(allFunctionAxioms);
      return new CelVerifierZ3Impl(
          timeout, comprehensionUnrollLimit, unknownIdentifiers.build(), registry, typeProvider);
    }
  }

  @Override
  public CelVerificationResult isSatisfiable(CelAbstractSyntaxTree ast)
      throws CelVerificationException {
    Preconditions.checkArgument(ast.isChecked(), "AST must be type-checked.");
    return checkSatisfiability(ast, /* searchForCounterexample= */ false);
  }

  @Override
  public CelVerificationResult isAlwaysTrue(CelAbstractSyntaxTree ast)
      throws CelVerificationException {
    Preconditions.checkArgument(ast.isChecked(), "AST must be type-checked.");
    return checkSatisfiability(ast, /* searchForCounterexample= */ true);
  }

  @Override
  public CelVerificationResult verifyEquivalence(
      CelAbstractSyntaxTree astA, CelAbstractSyntaxTree astB) throws CelVerificationException {
    Preconditions.checkArgument(astA.isChecked(), "astA must be type-checked.");
    Preconditions.checkArgument(astB.isChecked(), "astB must be type-checked.");
    try (Context ctx = new Context(ImmutableMap.of("model", "true"))) {
      CelAstToZ3Translator translator =
          new CelAstToZ3Translator(
              ctx, comprehensionUnrollLimit, unknownIdentifiers, functionRegistry, typeProvider);

      TranslatedValue tvA = translator.translate(astA);
      TranslatedValue tvB = translator.translate(astB);

      BoolExpr divergenceCondition = ctx.mkNot(ctx.mkEq(tvA.z3Expr(), tvB.z3Expr()));
      BoolExpr combinedTaint = ctx.mkOr(tvA.isApproximate(), tvB.isApproximate());

      Solver solver = newSolver(ctx);
      for (BoolExpr constraint : translator.getTypeConstraints()) {
        solver.add(constraint);
      }

      // Divergent parameterized Unknowns are naturally caught by Pass 2 (A != B is SAT).
      // Identical Unknowns mean the ASTs are alpha-equivalent, so we WANT them to pass.
      // We no longer need Pass 3 to conservatively bail out of equivalence checks.
      BoolExpr unknownCondition = ctx.mkFalse();

      SolverRunResult result =
          runThreePassVerification(
              ctx,
              solver,
              divergenceCondition,
              combinedTaint,
              unknownCondition,
              translator,
              /* checkTruncation= */ false);

      switch (result.outcome) {
        case EXACT_MATCH:
          return CelVerificationResult.failed(
              "Equivalence violation detected."
                  + getCounterexampleString(
                      ctx,
                      translator.getTypeSystem(),
                      result.model,
                      /* isApproximate= */ false,
                      /* isCounterexample= */ true));
        case APPROXIMATE_MATCH:
          return CelVerificationResult.inconclusive(
              "Inconclusive: a divergence may exist, but it depends on approximations, missing"
                  + " theories, or loop bounds."
                  + getCounterexampleString(
                      ctx,
                      translator.getTypeSystem(),
                      result.model,
                      /* isApproximate= */ true,
                      /* isCounterexample= */ true));
        case TRUNCATED:
          return CelVerificationResult.inconclusive(
              "Inconclusive: expressions are equivalent within the current loop unroll limit, but"
                  + " may diverge for larger collections.");
        case NO_MATCH:
          return CelVerificationResult.verified();
        case SOLVER_UNKNOWN:
          return CelVerificationResult.inconclusive(
              "Inconclusive: the solver returned unknown status (" + result.reason + ").");
      }
      throw new AssertionError("Unknown verification outcome: " + result.outcome);
    }
  }

  CelVerificationResult verifyImplication(
      CelAbstractSyntaxTree assumeAst,
      CelAbstractSyntaxTree assertAst,
      Map<String, CelAbstractSyntaxTree> boundSymbols)
      throws CelVerificationException {
    Preconditions.checkArgument(assumeAst.isChecked(), "assumeAst must be type-checked.");
    Preconditions.checkArgument(assertAst.isChecked(), "assertAst must be type-checked.");
    for (Map.Entry<String, CelAbstractSyntaxTree> entry : boundSymbols.entrySet()) {
      Preconditions.checkArgument(
          entry.getValue().isChecked(),
          "boundSymbol AST for '%s' must be type-checked.",
          entry.getKey());
    }

    try (Context ctx = new Context(ImmutableMap.of("model", "true"))) {
      CelAstToZ3Translator translator =
          new CelAstToZ3Translator(
              ctx, comprehensionUnrollLimit, unknownIdentifiers, functionRegistry, typeProvider);

      List<BoolExpr> taints = new ArrayList<>();
      for (Map.Entry<String, CelAbstractSyntaxTree> entry : boundSymbols.entrySet()) {
        TranslatedValue tv = translator.translate(entry.getValue());
        translator.bindSymbol(entry.getKey(), tv);
      }

      TranslatedValue assumeTv = translator.translate(assumeAst);
      TranslatedValue assertTv = translator.translate(assertAst);
      taints.add(assumeTv.isApproximate());
      taints.add(assertTv.isApproximate());

      BoolExpr assumeCondition = translator.isTrue(assumeTv.z3Expr());
      BoolExpr assertCondition = translator.isTrue(assertTv.z3Expr());
      BoolExpr violationCondition = ctx.mkAnd(assumeCondition, ctx.mkNot(assertCondition));

      BoolExpr combinedTaint = CelZ3TypeSystem.mkOrFlattened(ctx, taints);
      BoolExpr unknownCondition =
          ctx.mkOr(
              translator.getTypeSystem().isUnknown(assumeTv.z3Expr()),
              translator.getTypeSystem().isUnknown(assertTv.z3Expr()));

      Solver solver = newSolver(ctx);
      for (BoolExpr constraint : translator.getTypeConstraints()) {
        solver.add(constraint);
      }

      SolverRunResult result =
          runThreePassVerification(
              ctx,
              solver,
              violationCondition,
              combinedTaint,
              unknownCondition,
              translator,
              /* checkTruncation= */ true);

      switch (result.outcome) {
        case EXACT_MATCH:
          return CelVerificationResult.failed(
              "Implication violation detected."
                  + getCounterexampleString(
                      ctx,
                      translator.getTypeSystem(),
                      result.model,
                      /* isApproximate= */ false,
                      /* isCounterexample= */ true));
        case APPROXIMATE_MATCH:
          return CelVerificationResult.inconclusive(
              "Inconclusive: a counterexample may exist, but it depends on approximations, missing"
                  + " theories, or loop bounds."
                  + getCounterexampleString(
                      ctx,
                      translator.getTypeSystem(),
                      result.model,
                      /* isApproximate= */ true,
                      /* isCounterexample= */ true));
        case TRUNCATED:
          return CelVerificationResult.inconclusive(
              "Inconclusive: implication holds within the current loop unroll limit, but"
                  + " may be violated for larger collections.");
        case NO_MATCH:
          return CelVerificationResult.verified();
        case SOLVER_UNKNOWN:
          return CelVerificationResult.inconclusive(
              "Inconclusive: the solver returned unknown status (" + result.reason + ").");
      }
      throw new AssertionError("Unknown verification outcome: " + result.outcome);
    }
  }

  private CelVerificationResult checkSatisfiability(
      CelAbstractSyntaxTree ast, boolean searchForCounterexample) throws CelVerificationException {
    try (Context ctx = new Context(ImmutableMap.of("model", "true"))) {
      CelAstToZ3Translator translator =
          new CelAstToZ3Translator(
              ctx, comprehensionUnrollLimit, unknownIdentifiers, functionRegistry, typeProvider);

      TranslatedValue tv = translator.translate(ast);
      BoolExpr condition = translator.isTrue(tv.z3Expr());
      if (searchForCounterexample) {
        condition = ctx.mkNot(condition);
      }

      Solver solver = newSolver(ctx);
      for (BoolExpr constraint : translator.getTypeConstraints()) {
        solver.add(constraint);
      }

      SolverRunResult result =
          runThreePassVerification(
              ctx,
              solver,
              condition,
              tv.isApproximate(),
              translator.getTypeSystem().isUnknown(tv.z3Expr()),
              translator,
              /* checkTruncation= */ true);

      switch (result.outcome) {
        case EXACT_MATCH:
          return searchForCounterexample
              ? CelVerificationResult.failed(
                  "Condition is not always true."
                      + getCounterexampleString(
                          ctx,
                          translator.getTypeSystem(),
                          result.model,
                          /* isApproximate= */ false,
                          /* isCounterexample= */ true))
              : CelVerificationResult.verified(
                  "Condition is satisfiable."
                      + getCounterexampleString(
                          ctx,
                          translator.getTypeSystem(),
                          result.model,
                          /* isApproximate= */ false,
                          /* isCounterexample= */ false));

        case APPROXIMATE_MATCH:
          String prefix =
              searchForCounterexample
                  ? "Inconclusive: a counterexample may exist, but it depends on approximations,"
                      + " missing theories, or loop bounds."
                  : "Inconclusive: a satisfying model may exist, but it depends on"
                      + " approximations, missing theories, or loop bounds.";
          return CelVerificationResult.inconclusive(
              prefix
                  + getCounterexampleString(
                      ctx,
                      translator.getTypeSystem(),
                      result.model,
                      /* isApproximate= */ true,
                      /* isCounterexample= */ searchForCounterexample));

        case TRUNCATED:
          return CelVerificationResult.inconclusive(
              searchForCounterexample
                  ? "Inconclusive: a counterexample may exist beyond the loop unroll limit."
                  : "Inconclusive: expression is not satisfiable within the current loop unroll"
                      + " limit, but may be satisfiable for larger collections.");

        case NO_MATCH:
          return searchForCounterexample
              ? CelVerificationResult.verified()
              : CelVerificationResult.failed("Condition is not satisfiable.");

        case SOLVER_UNKNOWN:
          return CelVerificationResult.inconclusive(
              "Inconclusive: the solver returned unknown status (" + result.reason + ").");
      }
      throw new AssertionError("Unknown verification outcome: " + result.outcome);
    }
  }

  private SolverRunResult runThreePassVerification(
      Context ctx,
      Solver solver,
      BoolExpr condition,
      BoolExpr taint,
      BoolExpr unknownCondition,
      CelAstToZ3Translator translator,
      boolean checkTruncation)
      throws CelVerificationException {

    // Pass 1: Search for an exact match/counterexample
    solver.push();
    solver.add(condition);
    solver.add(ctx.mkNot(taint));
    Status status = solver.check();

    if (status == Status.SATISFIABLE) {
      return SolverRunResult.exactMatch(solver.getModel());
    } else if (status == Status.UNKNOWN) {
      return SolverRunResult.solverUnknown(checkTimeoutOrGetReason(solver));
    }

    // Pass 2: Search for any tainted match/counterexample
    solver.pop();
    solver.push();
    solver.add(condition);
    Status approxStatus = solver.check();

    if (approxStatus == Status.SATISFIABLE) {
      return SolverRunResult.approximateMatch(solver.getModel());
    } else if (approxStatus == Status.UNKNOWN) {
      return SolverRunResult.solverUnknown(checkTimeoutOrGetReason(solver));
    }

    // If we don't need to check truncation (e.g. for equivalence checks),
    // we can pop the solver and return noMatch immediately.
    if (!checkTruncation) {
      solver.pop();
      return SolverRunResult.noMatch();
    }

    // Pass 3: Check BMC truncation
    solver.pop();
    solver.add(unknownCondition);
    solver.add(translator.hasTruncation());
    Status truncationStatus = solver.check();
    if (truncationStatus == Status.SATISFIABLE) {
      return SolverRunResult.truncated();
    } else if (truncationStatus == Status.UNKNOWN) {
      return SolverRunResult.solverUnknown(checkTimeoutOrGetReason(solver));
    }

    return SolverRunResult.noMatch();
  }

  private static String checkTimeoutOrGetReason(Solver solver) throws CelVerificationException {
    String reason = solver.getReasonUnknown();
    if (reason.equals("timeout") || reason.equals("canceled")) {
      throw new CelVerificationException("Verification timed out: " + reason);
    }
    return reason;
  }

  private Solver newSolver(Context ctx) {
    Solver solver = ctx.mkSolver();
    Params params = ctx.mkParams();
    params.add("timeout", (int) timeout.toMillis());
    solver.setParameters(params);
    return solver;
  }

  private static String getCounterexampleString(
      Context ctx,
      CelZ3TypeSystem typeSystem,
      Model model,
      boolean isApproximate,
      boolean isCounterexample) {
    return CelZ3CounterexampleGenerator.generate(
        ctx, typeSystem, model, isApproximate, isCounterexample);
  }

  CelVerifierZ3Impl(
      Duration timeout,
      int comprehensionUnrollLimit,
      ImmutableSet<String> unknownIdentifiers,
      CelZ3FunctionRegistry functionRegistry,
      CelTypeProvider typeProvider) {
    this.timeout = timeout;
    this.comprehensionUnrollLimit = comprehensionUnrollLimit;
    this.unknownIdentifiers = unknownIdentifiers;
    this.functionRegistry = functionRegistry;
    this.typeProvider = typeProvider;
  }

  private enum SolverOutcome {
    EXACT_MATCH,
    APPROXIMATE_MATCH,
    TRUNCATED,
    NO_MATCH,
    SOLVER_UNKNOWN
  }

  private static final class SolverRunResult {
    final SolverOutcome outcome;
    final @Nullable Model model;
    final @Nullable String reason;

    static SolverRunResult exactMatch(Model model) {
      return new SolverRunResult(SolverOutcome.EXACT_MATCH, model, null);
    }

    static SolverRunResult approximateMatch(Model model) {
      return new SolverRunResult(SolverOutcome.APPROXIMATE_MATCH, model, null);
    }

    static SolverRunResult truncated() {
      return new SolverRunResult(SolverOutcome.TRUNCATED, null, null);
    }

    static SolverRunResult noMatch() {
      return new SolverRunResult(SolverOutcome.NO_MATCH, null, null);
    }

    static SolverRunResult solverUnknown(String reason) {
      return new SolverRunResult(SolverOutcome.SOLVER_UNKNOWN, null, reason);
    }

    private SolverRunResult(SolverOutcome outcome, @Nullable Model model, @Nullable String reason) {
      this.outcome = outcome;
      this.model = model;
      this.reason = reason;
    }
  }
}
