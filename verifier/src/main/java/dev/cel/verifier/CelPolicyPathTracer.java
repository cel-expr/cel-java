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

import dev.cel.common.CelSourceLocation;
import dev.cel.common.formats.ValueString;
import dev.cel.policy.CelCompiledRule;
import dev.cel.policy.CelCompiledRule.CelCompiledMatch;
import dev.cel.policy.CelCompiledRule.CelCompiledVariable;
import dev.cel.policy.CelPolicy;
import dev.cel.policy.CelPolicySource;
import dev.cel.runtime.CelEvaluationException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Traces which rule branches matched and evaluated under a concrete counterexample assignment. */
final class CelPolicyPathTracer {

  /** Encapsulates the trace outcome of a single firing rule match. */
  static final class MatchTrace {
    final int ruleIndex;
    final Optional<String> ruleName;
    final long sourceId;
    final CelSourceLocation location;
    final Object evaluatedOutput;

    MatchTrace(
        int ruleIndex,
        Optional<String> ruleName,
        long sourceId,
        CelSourceLocation location,
        Object evaluatedOutput) {
      this.ruleIndex = ruleIndex;
      this.ruleName = ruleName;
      this.sourceId = sourceId;
      this.location = location;
      this.evaluatedOutput = evaluatedOutput;
    }
  }

  /** Traces the policy execution graph to determine the firing rule match under the given model. */
  static Optional<MatchTrace> traceMatchingBranch(
      CelPolicy policy, CelCompiledRule compiledRule, CelCounterexample counterexample) {
    CelPolicySource policySource = policy.policySource();
    Map<String, Object> evalContext = new HashMap<>(counterexample.toEvaluationContext());

    // Populate compiled variables in the evaluation context
    for (CelCompiledVariable var : compiledRule.variables()) {
      try {
        Object val = compiledRule.cel().createProgram(var.ast()).eval(evalContext);
        evalContext.put(var.name(), val);
        evalContext.put(var.celVarDecl().name(), val);
      } catch (CelEvaluationException e) {
        // Variable evaluation failed under this counterexample model.
        // Safe and necessary to ignore.
      }
    }

    return traceRule(compiledRule, evalContext, policySource);
  }

  private static Optional<MatchTrace> traceRule(
      CelCompiledRule compiledRule, Map<String, Object> evalContext, CelPolicySource policySource) {
    if (compiledRule.semantic() == CelPolicy.EvaluationSemantic.AGGREGATE) {
      return traceAggregateRule(compiledRule, evalContext, policySource);
    }

    Optional<String> ruleName = compiledRule.ruleId().map(ValueString::value);
    MatchTrace firstConditionErrorTrace = null;

    for (int i = 0; i < compiledRule.matches().size(); i++) {
      CelCompiledMatch match = compiledRule.matches().get(i);
      boolean conditionMatched;
      try {
        Object condVal = compiledRule.cel().createProgram(match.condition()).eval(evalContext);
        conditionMatched = Objects.equals(condVal, true);
      } catch (CelEvaluationException e) {
        // A condition that encounters an evaluation error does not evaluate to boolean true.
        // Record as fallback trace in case no subsequent branch matches.
        conditionMatched = false;
        if (firstConditionErrorTrace == null) {
          long sourceId = match.sourceId();
          CelSourceLocation location = computeLocation(sourceId, policySource);
          Object outputVal = "<condition error: " + e.getMessage() + ">";
          firstConditionErrorTrace = new MatchTrace(i, ruleName, sourceId, location, outputVal);
        }
      }

      if (conditionMatched) {
        if (match.result().kind() == CelCompiledMatch.Result.Kind.OUTPUT) {
          Object outputVal;
          try {
            outputVal =
                compiledRule.cel().createProgram(match.result().output().ast()).eval(evalContext);
          } catch (CelEvaluationException e) {
            outputVal = "<error: " + e.getMessage() + ">";
          }
          long sourceId =
              match.sourceId() != 0 ? match.sourceId() : match.result().output().sourceId();
          CelSourceLocation location = computeLocation(sourceId, policySource);
          return Optional.of(new MatchTrace(i, ruleName, sourceId, location, outputVal));
        } else if (match.result().kind() == CelCompiledMatch.Result.Kind.RULE) {
          Optional<MatchTrace> nestedTrace =
              traceRule(match.result().rule(), evalContext, policySource);
          if (nestedTrace.isPresent()) {
            return nestedTrace;
          }
        }
      }
    }

    return Optional.ofNullable(firstConditionErrorTrace);
  }

  private static Optional<MatchTrace> traceAggregateRule(
      CelCompiledRule compiledRule, Map<String, Object> evalContext, CelPolicySource policySource) {
    List<Object> outputs = new ArrayList<>();
    MatchTrace firstTrace = null;
    MatchTrace firstConditionErrorTrace = null;
    Optional<String> ruleName = compiledRule.ruleId().map(ValueString::value);

    for (int i = 0; i < compiledRule.matches().size(); i++) {
      CelCompiledMatch match = compiledRule.matches().get(i);
      boolean conditionMatched;
      try {
        Object condVal = compiledRule.cel().createProgram(match.condition()).eval(evalContext);
        conditionMatched = Objects.equals(condVal, true);
      } catch (CelEvaluationException e) {
        // An evaluation error does not evaluate to boolean true, contributing nothing to the list.
        conditionMatched = false;
        if (firstConditionErrorTrace == null) {
          long sourceId = match.sourceId();
          CelSourceLocation location = computeLocation(sourceId, policySource);
          Object outputVal = "<condition error: " + e.getMessage() + ">";
          firstConditionErrorTrace = new MatchTrace(i, ruleName, sourceId, location, outputVal);
        }
      }

      if (conditionMatched && match.result().kind() == CelCompiledMatch.Result.Kind.OUTPUT) {
        if (firstTrace == null) {
          long sourceId =
              match.sourceId() != 0 ? match.sourceId() : match.result().output().sourceId();
          CelSourceLocation location = computeLocation(sourceId, policySource);
          firstTrace = new MatchTrace(i, ruleName, sourceId, location, outputs);
        }
        try {
          Object outputVal =
              compiledRule.cel().createProgram(match.result().output().ast()).eval(evalContext);
          outputs.add(outputVal);
        } catch (CelEvaluationException e) {
          outputs.add("<error: " + e.getMessage() + ">");
        }
      }
    }

    if (firstTrace != null) {
      return Optional.of(firstTrace);
    }

    return Optional.ofNullable(firstConditionErrorTrace);
  }

  static CelSourceLocation computeLocation(long sourceId, CelPolicySource policySource) {
    if (sourceId == 0) {
      return CelSourceLocation.NONE;
    }
    int offset = Optional.ofNullable(policySource.getPositionsMap().get(sourceId)).orElse(-1);
    if (offset == -1) {
      return CelSourceLocation.NONE;
    }
    return policySource.getOffsetLocation(offset).orElse(CelSourceLocation.NONE);
  }

  private CelPolicyPathTracer() {}
}
