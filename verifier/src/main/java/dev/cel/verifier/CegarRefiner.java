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

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.errorprone.annotations.Immutable;
import dev.cel.bundle.Cel;
import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.runtime.CelEvaluationException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Evaluates candidate counterexample models against the concrete CEL runtime to confirm or refute
 * potential violations (CEGAR refinement loop).
 */
@Immutable
final class CegarRefiner {

  @Immutable
  static final class CegarOutcome {
    private final boolean isViolation;
    private final Optional<String> evaluationErrorMessage;

    static CegarOutcome violation() {
      return new CegarOutcome(true, Optional.empty());
    }

    static CegarOutcome spurious() {
      return new CegarOutcome(false, Optional.empty());
    }

    static CegarOutcome evaluationError(String errorMessage) {
      return new CegarOutcome(false, Optional.of(errorMessage));
    }

    private CegarOutcome(boolean isViolation, Optional<String> evaluationErrorMessage) {
      this.isViolation = isViolation;
      this.evaluationErrorMessage = evaluationErrorMessage;
    }

    boolean isViolation() {
      return isViolation;
    }

    Optional<String> evaluationErrorMessage() {
      return evaluationErrorMessage;
    }
  }

  private final Cel cel;

  CegarRefiner(Cel cel) {
    this.cel = Preconditions.checkNotNull(cel);
  }

  CegarOutcome refineEquivalence(
      CelAbstractSyntaxTree astA, CelAbstractSyntaxTree astB, CelCounterexample model) {
    if (model.isSatisfyingInput()) {
      return CegarOutcome.spurious();
    }
    try {
      ImmutableMap<String, Object> evalContext = model.toEvaluationContext();
      Object resA = cel.createProgram(astA).eval(evalContext);
      Object resB = cel.createProgram(astB).eval(evalContext);
      // If concrete evaluation produces identical results, the candidate SMT divergence was an
      // artifact of abstraction (spurious). Otherwise, concrete outputs diverge (violation).
      return Objects.equals(resA, resB) ? CegarOutcome.spurious() : CegarOutcome.violation();
    } catch (CelEvaluationException e) {
      return CegarOutcome.evaluationError(e.getMessage());
    }
  }

  CegarOutcome refineSatisfiability(
      CelAbstractSyntaxTree ast, boolean searchForCounterexample, CelCounterexample model) {
    if (searchForCounterexample ? model.isSatisfyingInput() : !model.isSatisfyingInput()) {
      return CegarOutcome.spurious();
    }
    try {
      ImmutableMap<String, Object> evalContext = model.toEvaluationContext();
      Object res = cel.createProgram(ast).eval(evalContext);
      boolean isEvaluationTrue = Objects.equals(res, true);
      // For universal truth (searchForCounterexample=true), evaluating to true refutes the
      // candidate counterexample (spurious). For satisfiability search
      // (searchForCounterexample=false),
      // evaluating to true confirms the candidate satisfying model (violation).
      boolean isSpurious = searchForCounterexample == isEvaluationTrue;
      return isSpurious ? CegarOutcome.spurious() : CegarOutcome.violation();
    } catch (CelEvaluationException e) {
      return CegarOutcome.evaluationError(e.getMessage());
    }
  }

  CegarOutcome refineImplication(
      CelAbstractSyntaxTree assumeAst,
      CelAbstractSyntaxTree assertAst,
      Map<String, CelAbstractSyntaxTree> boundSymbols,
      CelCounterexample model) {
    if (model.isSatisfyingInput()) {
      return CegarOutcome.spurious();
    }
    try {
      Map<String, Object> evalContext = new HashMap<>(model.toEvaluationContext());
      for (Map.Entry<String, CelAbstractSyntaxTree> entry : boundSymbols.entrySet()) {
        Object boundVal = cel.createProgram(entry.getValue()).eval(evalContext);
        evalContext.put(entry.getKey(), boundVal);
      }
      Object assumeVal = cel.createProgram(assumeAst).eval(evalContext);
      if (Objects.equals(assumeVal, true)) {
        Object assertVal = cel.createProgram(assertAst).eval(evalContext);
        // If the premise holds and the conclusion evaluates to true, the candidate counterexample
        // is refuted (spurious). If the conclusion fails under true premise, implication is
        // violated.
        return Objects.equals(assertVal, true) ? CegarOutcome.spurious() : CegarOutcome.violation();
      }
      // The candidate input did not satisfy the premise, so it cannot serve as a counterexample.
      return CegarOutcome.spurious();
    } catch (CelEvaluationException e) {
      return CegarOutcome.evaluationError(e.getMessage());
    }
  }
}
