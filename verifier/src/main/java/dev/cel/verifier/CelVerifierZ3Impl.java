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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.collect.ImmutableList.toImmutableList;

import com.google.auto.value.AutoValue;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.errorprone.annotations.Immutable;
import com.google.protobuf.Descriptors.Descriptor;
import com.microsoft.z3.BoolExpr;
import com.microsoft.z3.Context;
import com.microsoft.z3.Model;
import com.microsoft.z3.Params;
import com.microsoft.z3.Solver;
import com.microsoft.z3.Status;
import dev.cel.bundle.Cel;
import dev.cel.bundle.CelFactory;
import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.internal.DefaultDescriptorPool;
import dev.cel.common.internal.WellKnownProto;
import dev.cel.common.types.CelType;
import dev.cel.common.types.CelTypeProvider;
import dev.cel.optimizer.CelOptimizationException;
import dev.cel.optimizer.CelOptimizer;
import dev.cel.optimizer.CelOptimizerFactory;
import dev.cel.runtime.CelEvaluationException;
import dev.cel.verifier.axioms.CelZ3FunctionAxiom;
import dev.cel.verifier.axioms.CelZ3StandardAxioms;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

/** Z3 implementation of the CelVerifier. */
@Immutable
final class CelVerifierZ3Impl implements CelVerifier {

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

  private static final ImmutableList<Descriptor> WELL_KNOWN_DESCRIPTORS =
      ImmutableList.of(
              WellKnownProto.DURATION,
              WellKnownProto.JSON_LIST_VALUE,
              WellKnownProto.JSON_STRUCT_VALUE,
              WellKnownProto.JSON_VALUE,
              WellKnownProto.TIMESTAMP,
              WellKnownProto.FLOAT_VALUE,
              WellKnownProto.INT32_VALUE,
              WellKnownProto.INT64_VALUE,
              WellKnownProto.STRING_VALUE,
              WellKnownProto.BOOL_VALUE,
              WellKnownProto.BYTES_VALUE,
              WellKnownProto.DOUBLE_VALUE,
              WellKnownProto.UINT32_VALUE,
              WellKnownProto.UINT64_VALUE)
          .stream()
          .map(DefaultDescriptorPool::getWellKnownProtoDescriptor)
          .collect(toImmutableList());

  private final Duration timeout;
  private final int comprehensionUnrollLimit;
  private final ImmutableSet<String> unknownIdentifiers;
  private final CelZ3FunctionRegistry functionRegistry;
  private final CelTypeProvider typeProvider;
  private final boolean enableCegar;

  @SuppressWarnings("Immutable") // Cel environment is immutable, just not marked as such
  private final Cel cel;

  static Builder newBuilder() {
    return new Builder(
        CelFactory.plannerCelBuilder().addMessageTypes(WELL_KNOWN_DESCRIPTORS).build());
  }

  static Builder newBuilder(Cel cel) {
    return new Builder(checkNotNull(cel));
  }

  static final class Builder implements CelVerifierBuilder {
    private Duration timeout;
    private int comprehensionUnrollLimit;
    private final ImmutableSet.Builder<String> unknownIdentifiers;
    private final ImmutableList.Builder<CelZ3FunctionAxiom> functionAxioms;
    private final Cel cel;
    private CelTypeProvider typeProvider;
    private boolean enableCegar;

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
      this.typeProvider = checkNotNull(typeProvider);
      return this;
    }

    @Override
    @CanIgnoreReturnValue
    public CelVerifierBuilder setComprehensionUnrollLimit(int unrollLimit) {
      checkArgument(unrollLimit >= 0, "unrollLimit must be non-negative");
      this.comprehensionUnrollLimit = unrollLimit;
      return this;
    }

    @Override
    @CanIgnoreReturnValue
    public CelVerifierBuilder enableCegar(boolean value) {
      this.enableCegar = value;
      return this;
    }

    @CanIgnoreReturnValue
    Builder addFunctionAxioms(CelZ3FunctionAxiom... axioms) {
      return addFunctionAxioms(Arrays.asList(axioms));
    }

    @CanIgnoreReturnValue
    Builder addFunctionAxioms(Iterable<CelZ3FunctionAxiom> axioms) {
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
          timeout,
          comprehensionUnrollLimit,
          unknownIdentifiers.build(),
          registry,
          typeProvider,
          enableCegar,
          cel);
    }

    private Builder(Cel cel) {
      this.timeout = Duration.ofSeconds(10);
      this.comprehensionUnrollLimit = 5;
      this.unknownIdentifiers = ImmutableSet.builder();
      this.functionAxioms = ImmutableList.builder();
      this.typeProvider = cel.getTypeProvider();
      this.cel = cel;
    }
  }

  @Override
  public CelVerificationResult isSatisfiable(CelAbstractSyntaxTree ast)
      throws CelVerificationException {
    checkArgument(ast.isChecked(), "AST must be type-checked.");
    return checkSatisfiability(ast, /* searchForCounterexample= */ false);
  }

  @Override
  public CelVerificationResult isAlwaysTrue(CelAbstractSyntaxTree ast)
      throws CelVerificationException {
    checkArgument(ast.isChecked(), "AST must be type-checked.");
    return checkSatisfiability(ast, /* searchForCounterexample= */ true);
  }

  @Override
  public CelVerificationResult verifyEquivalence(
      CelAbstractSyntaxTree astA, CelAbstractSyntaxTree astB) throws CelVerificationException {
    checkArgument(astA.isChecked(), "astA must be type-checked.");
    checkArgument(astB.isChecked(), "astB must be type-checked.");
    CelOptimizer optimizer =
        CelOptimizerFactory.standardCelOptimizerBuilder(cel)
            .addAstOptimizers(
                CanonicalizationOptimizer.newInstance(
                    CanonicalizationOptimizer.CanonicalizationOptions.newBuilder().build()))
            .build();
    CelAbstractSyntaxTree canonicalA = astA;
    CelAbstractSyntaxTree canonicalB = astB;
    try {
      canonicalA = optimizer.optimize(astA);
      canonicalB = optimizer.optimize(astB);
    } catch (CelOptimizationException e) {
      // Fall back to original ASTs if canonicalization or re-typechecking fails
    }
    try (Context ctx = new Context(ImmutableMap.of("model", "true"))) {
      CelAstToZ3Translator translator = newTranslator(ctx, /* parameterizeUnknowns= */ true);

      TranslatedValue tvA = translator.translate(canonicalA);
      TranslatedValue tvB = translator.translate(canonicalB);

      BoolExpr divergenceCondition = ctx.mkNot(ctx.mkEq(tvA.z3Expr(), tvB.z3Expr()));
      // Parameterized unknowns are keyed on the operation producing them, whereas CEL identifies
      // an unknown only by its attributes (e.g. `a && true` and `a` both yield unknown {a}). Two
      // distinct unknowns may thus still be equivalent.
      BoolExpr combinedTaint =
          CelZ3Exprs.mkOrFlattened(
              ctx,
              tvA.isApproximate(),
              tvB.isApproximate(),
              translator.recordApproximation(
                  "unknown results, which are compared only by their origin",
                  ctx.mkAnd(tvA.isZ3Unknown(), tvB.isZ3Unknown())));

      // Divergent parameterized unknowns are caught by Pass 2; identical ones mean the ASTs are
      // alpha-equivalent, so there's no unmodeled result to look for.
      return search(
          ctx,
          translator,
          divergenceCondition,
          combinedTaint,
          /* unknownCondition= */ ctx.mkFalse(),
          /* isCounterexample= */ true,
          /* candidate= */ "a divergence",
          /* exactMatchMessage= */ "Equivalence violation detected.",
          runtimeCheck(results -> !results.get(0).equals(results.get(1)), astA, astB));
    }
  }

  CelVerificationResult verifyImplication(
      CelAbstractSyntaxTree assumeAst,
      CelAbstractSyntaxTree assertAst,
      Map<String, CelAbstractSyntaxTree> boundSymbols,
      String subjectName)
      throws CelVerificationException {
    checkArgument(assumeAst.isChecked(), "assumeAst must be type-checked.");
    checkArgument(assertAst.isChecked(), "assertAst must be type-checked.");
    for (Map.Entry<String, CelAbstractSyntaxTree> entry : boundSymbols.entrySet()) {
      checkArgument(
          entry.getValue().isChecked(),
          "boundSymbol AST for '%s' must be type-checked.",
          entry.getKey());
    }

    try (Context ctx = new Context(ImmutableMap.of("model", "true"))) {
      CelAstToZ3Translator translator = newTranslator(ctx, /* parameterizeUnknowns= */ false);

      for (Map.Entry<String, CelAbstractSyntaxTree> entry : boundSymbols.entrySet()) {
        TranslatedValue tv = translator.translate(entry.getValue());
        translator.bindSymbol(entry.getKey(), tv, entry.getValue());
      }

      TranslatedValue assumeTv = translator.translate(assumeAst);
      TranslatedValue assertTv = translator.translate(assertAst);

      BoolExpr assumeCondition = translator.getTypeSystem().isTrue(assumeTv.z3Expr());
      BoolExpr assertCondition = translator.getTypeSystem().isTrue(assertTv.z3Expr());
      BoolExpr violationCondition = ctx.mkAnd(assumeCondition, ctx.mkNot(assertCondition));

      BoolExpr combinedTaint =
          CelZ3Exprs.mkOrFlattened(ctx, assumeTv.isApproximate(), assertTv.isApproximate());
      // An unknown assertion under a true assumption is already a Pass 2 candidate; an unknown
      // assumption may hide a violation unless the assertion holds exactly.
      BoolExpr unknownCondition =
          ctx.mkAnd(
              assumeTv.isZ3Unknown(),
              assumeTv.isApproximate(),
              ctx.mkNot(ctx.mkAnd(assertCondition, ctx.mkNot(assertTv.isApproximate()))));

      return search(
          ctx,
          translator,
          violationCondition,
          combinedTaint,
          unknownCondition,
          /* isCounterexample= */ true,
          /* candidate= */ "a counterexample",
          /* exactMatchMessage= */ String.format("%s violation detected.", subjectName),
          runtimeCheck(
              boundSymbols,
              results -> results.get(0).equals(true) && !results.get(1).equals(true),
              assumeAst,
              assertAst));
    }
  }

  private CelVerificationResult checkSatisfiability(
      CelAbstractSyntaxTree ast, boolean searchForCounterexample) throws CelVerificationException {
    try (Context ctx = new Context(ImmutableMap.of("model", "true"))) {
      CelAstToZ3Translator translator = newTranslator(ctx, /* parameterizeUnknowns= */ false);

      TranslatedValue tv = translator.translate(ast);
      BoolExpr isTrue = translator.getTypeSystem().isTrue(tv.z3Expr());
      if (searchForCounterexample) {
        return search(
            ctx,
            translator,
            ctx.mkNot(isTrue),
            tv.isApproximate(),
            // An unknown result is never true, so it's already a Pass 2 counterexample.
            /* unknownCondition= */ ctx.mkFalse(),
            /* isCounterexample= */ true,
            /* candidate= */ "a counterexample",
            /* exactMatchMessage= */ "Condition is not always true.",
            runtimeCheck(results -> !results.get(0).equals(true), ast));
      }
      BoolExpr isUnmodeledUnknown = ctx.mkAnd(tv.isZ3Unknown(), tv.isApproximate());
      return search(
          ctx,
          translator,
          isTrue,
          tv.isApproximate(),
          isUnmodeledUnknown,
          /* isCounterexample= */ false,
          /* candidate= */ "a satisfying input",
          /* exactMatchMessage= */ "Condition is satisfiable.",
          runtimeCheck(results -> results.get(0).equals(true), ast));
    }
  }

  /**
   * Searches for a {@code condition} match: first an exact one, then an approximate one, then an
   * unmodeled result satisfying {@code unknownCondition} that may stand in for one. {@code
   * runtimeCheck} checks a witness with the runtime, noting why if it couldn't. Confirming an
   * approximate match doesn't count internal runtime errors.
   */
  private CelVerificationResult search(
      Context ctx,
      CelAstToZ3Translator translator,
      BoolExpr condition,
      BoolExpr taint,
      BoolExpr unknownCondition,
      boolean isCounterexample,
      String candidate,
      String exactMatchMessage,
      RuntimeChecker runtimeCheck)
      throws CelVerificationException {
    Solver solver = newSolver(ctx);
    solver.add(translator.getTypeConstraints().toArray(new BoolExpr[0]));

    // Pass 1: Search for an exact match.
    CelZ3TypeSystem typeSystem = translator.getTypeSystem();
    Status status = checkSat(solver, typeSystem, condition, ctx.mkNot(taint));
    if (status == Status.SATISFIABLE) {
      CelWitness witness = extractWitness(ctx, translator, solver.getModel());
      RuntimeCheck check = runtimeCheck.check(witness, /* countInternalErrors= */ true);
      if (!check.isMatch().orElse(true)) {
        return CelVerificationResult.inconclusive(
            "Inconclusive: the CEL runtime disagrees with " + candidate + " the verifier found.",
            witness,
            isCounterexample);
      }
      return match(isCounterexample, exactMatchMessage + check.note(), witness);
    } else if (status == Status.UNKNOWN) {
      // Depends on solver internals; not deterministically testable.
      return solverGaveUp(solver);
    }

    // Pass 2: Search for an approximate match.
    status = checkSat(solver, typeSystem, condition);
    if (status == Status.SATISFIABLE) {
      Model model = solver.getModel();
      CelWitness witness = extractWitness(ctx, translator, model);
      RuntimeCheck check = runtimeCheck.check(witness, /* countInternalErrors= */ false);
      if (check.isMatch().orElse(false)) {
        return match(
            isCounterexample,
            String.format(
                "%s The CEL runtime confirmed %s the verifier approximated.",
                exactMatchMessage, candidate),
            witness);
      }
      return CelVerificationResult.inconclusive(
          inconclusiveReason(candidate, translator, model, taint) + check.note(),
          witness,
          isCounterexample);
    } else if (status == Status.UNKNOWN) {
      // Depends on solver internals; not deterministically testable.
      return solverGaveUp(solver);
    }

    // Pass 3: Search for an unmodeled result.
    status = checkSat(solver, typeSystem, unknownCondition, translator.hasUnmodeledResult());
    if (status == Status.SATISFIABLE) {
      return CelVerificationResult.inconclusive(
          inconclusiveReason(candidate, translator, solver.getModel(), taint));
    } else if (status == Status.UNKNOWN) {
      // Depends on solver internals; not deterministically testable.
      return solverGaveUp(solver);
    }

    return isCounterexample
        ? CelVerificationResult.verified()
        : CelVerificationResult.failed("Condition is not satisfiable.");
  }

  private static CelVerificationResult match(
      boolean isCounterexample, String reason, CelWitness witness) {
    return isCounterexample
        ? CelVerificationResult.failed(reason, witness)
        : CelVerificationResult.verified(reason, witness);
  }

  private interface RuntimeChecker {
    RuntimeChecker UNCHECKED = (witness, countInternalErrors) -> RuntimeCheck.UNCHECKED;

    RuntimeCheck check(CelWitness witness, boolean countInternalErrors);
  }

  // Whether the runtime agrees a witness is a match, if it checked it, and a note for the message.
  @AutoValue
  abstract static class RuntimeCheck {
    static final RuntimeCheck UNCHECKED =
        new AutoValue_CelVerifierZ3Impl_RuntimeCheck(Optional.empty(), "");

    abstract Optional<Boolean> isMatch();

    abstract String note();

    static RuntimeCheck of(boolean isMatch) {
      return new AutoValue_CelVerifierZ3Impl_RuntimeCheck(
          Optional.of(isMatch), isMatch ? "" : " The CEL runtime disagrees with the one found.");
    }

    static RuntimeCheck failed(String reason) {
      return new AutoValue_CelVerifierZ3Impl_RuntimeCheck(
          Optional.empty(),
          " The CEL runtime couldn't check it: " + reason + (reason.endsWith(".") ? "" : "."));
    }
  }

  private RuntimeChecker runtimeCheck(
      Predicate<List<Object>> isMatch, CelAbstractSyntaxTree... asts) {
    return runtimeCheck(ImmutableMap.of(), isMatch, asts);
  }

  // Checks the witness's inputs with the runtime, if refinement is on and they're exact. Unknowns
  // aren't inputs the runtime can take.
  private RuntimeChecker runtimeCheck(
      Map<String, CelAbstractSyntaxTree> boundSymbols,
      Predicate<List<Object>> isMatch,
      CelAbstractSyntaxTree... asts) {
    if (!enableCegar || !unknownIdentifiers.isEmpty()) {
      return RuntimeChecker.UNCHECKED;
    }
    return (witness, countInternalErrors) -> {
      Optional<ImmutableMap<String, Object>> inputs = RuntimeOracle.exactInputs(witness);
      if (!inputs.isPresent()) {
        return RuntimeCheck.UNCHECKED;
      }
      try {
        return RuntimeCheck.of(
            isMatch.test(
                RuntimeOracle.evaluate(
                    cel, inputs.get(), boundSymbols, countInternalErrors, asts)));
      } catch (CelEvaluationException e) {
        return RuntimeCheck.failed(e.getMessage());
      }
    };
  }

  // Throws on a timeout. Leaves the assertions in place unless they're unsatisfiable, so the model
  // or the reason the solver gave up can be read.
  private static Status checkSat(Solver solver, CelZ3TypeSystem typeSystem, BoolExpr... assertions)
      throws CelVerificationException {
    solver.push();
    solver.add(assertions);
    Status status = solver.check();
    // Each round defines at least one more double, so this terminates. Each round gets the full
    // timeout; the rounds are bounded by the doubles in int/double comparisons.
    while (status == Status.SATISFIABLE) {
      ImmutableList<BoolExpr> definitions =
          typeSystem.defineDoublesContradictedBy(solver.getModel());
      if (definitions.isEmpty()) {
        break;
      }
      solver.pop();
      solver.add(definitions.toArray(new BoolExpr[0]));
      solver.push();
      solver.add(assertions);
      status = solver.check();
    }
    if (status == Status.UNSATISFIABLE) {
      solver.pop();
    } else if (status == Status.UNKNOWN) {
      String reason = solver.getReasonUnknown();
      if (reason.equals("timeout") || reason.equals("canceled")) {
        throw new CelVerificationException("Verification timed out: " + reason);
      }
    }
    return status;
  }

  private static CelVerificationResult solverGaveUp(Solver solver) {
    return CelVerificationResult.inconclusive(
        "Inconclusive: the solver returned unknown status (" + solver.getReasonUnknown() + ").");
  }

  private Solver newSolver(Context ctx) {
    Solver solver = ctx.mkSolver();
    Params params = ctx.mkParams();
    params.add("timeout", (int) timeout.toMillis());
    solver.setParameters(params);
    return solver;
  }

  private CelAstToZ3Translator newTranslator(Context ctx, boolean parameterizeUnknowns) {
    return new CelAstToZ3Translator(
        ctx,
        comprehensionUnrollLimit,
        unknownIdentifiers,
        functionRegistry,
        typeProvider,
        parameterizeUnknowns);
  }

  private static String inconclusiveReason(
      String candidate, CelAstToZ3Translator translator, Model model, BoolExpr taint) {
    ImmutableList<String> sources = translator.approximationSources(model, taint);
    return "Inconclusive: "
        + candidate
        + " may exist, but it depends on what the verifier doesn't model exactly"
        + (sources.isEmpty() ? "." : ": " + String.join(", ", sources) + ".");
  }

  private CelWitness extractWitness(Context ctx, CelAstToZ3Translator translator, Model model) {
    return CelZ3CounterexampleGenerator.generate(
        ctx,
        translator.getTypeSystem(),
        model,
        comprehensionUnrollLimit,
        translator.getInputVariables());
  }

  private CelVerifierZ3Impl(
      Duration timeout,
      int comprehensionUnrollLimit,
      ImmutableSet<String> unknownIdentifiers,
      CelZ3FunctionRegistry functionRegistry,
      CelTypeProvider typeProvider,
      boolean enableCegar,
      Cel cel) {
    this.timeout = timeout;
    this.comprehensionUnrollLimit = comprehensionUnrollLimit;
    this.unknownIdentifiers = unknownIdentifiers;
    this.functionRegistry = functionRegistry;
    this.typeProvider = typeProvider;
    this.enableCegar = enableCegar;
    this.cel = cel;
  }
}
