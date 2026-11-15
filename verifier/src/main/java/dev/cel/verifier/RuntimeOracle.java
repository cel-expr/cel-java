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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.CelErrorCode;
import dev.cel.runtime.CelEvaluationException;
import dev.cel.runtime.CelFunctionResolver;
import dev.cel.runtime.CelResolvedOverload;
import dev.cel.runtime.CelRuntime;
import dev.cel.runtime.CelRuntime.Program;
import dev.cel.runtime.CelUnknownSet;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Evaluates expressions with the CEL runtime on a witness's inputs, to check the solver's
 * candidates.
 */
final class RuntimeOracle {

  // Stands for every evaluation error, matching the verifier's single error value.
  private enum EvaluationError {
    ERROR
  }

  /** The witness's inputs, if every value is exact. */
  static Optional<ImmutableMap<String, Object>> exactInputs(CelWitness witness) {
    return witness.variables().values().stream().allMatch(v -> v.value().isPresent())
        ? Optional.of(
            ImmutableMap.copyOf(Maps.transformValues(witness.variables(), v -> v.value().get())))
        : Optional.empty();
  }

  /**
   * Returns the values of {@code asts} for {@code inputs}, with errors as one value. Throws if the
   * runtime can't evaluate one, e.g. a function has no binding or its iteration limit is hit, or if
   * it fails with an internal error and {@code countInternalErrors} is false.
   */
  static ImmutableList<Object> evaluate(
      CelRuntime runtime,
      Map<String, ?> inputs,
      boolean countInternalErrors,
      CelAbstractSyntaxTree... asts)
      throws CelEvaluationException {
    ImmutableList.Builder<Object> results = ImmutableList.builder();
    for (CelAbstractSyntaxTree ast : asts) {
      Program program = runtime.createProgram(ast);
      // Notes calls to late-bound functions, which have no binding here. The legacy interpreter
      // also consults them after any failed dispatch, so its dispatch errors go unchecked.
      AtomicBoolean callsLateBound = new AtomicBoolean();
      CelFunctionResolver lateBoundFunctions =
          new CelFunctionResolver() {
            @Override
            public Optional<CelResolvedOverload> findOverloadMatchingArgs(
                String functionName, Collection<String> overloadIds, Object[] args) {
              return findOverloadMatchingArgs(functionName, args);
            }

            @Override
            public Optional<CelResolvedOverload> findOverloadMatchingArgs(
                String functionName, Object[] args) {
              callsLateBound.set(true);
              return Optional.empty();
            }
          };
      Object result;
      try {
        result = program.eval(inputs, lateBoundFunctions);
      } catch (CelEvaluationException e) {
        CelErrorCode code = e.getErrorCode();
        // The runtime reports some CEL errors as internal, but also misused bindings (e.g. async).
        if (callsLateBound.get()
            || code == CelErrorCode.ITERATION_BUDGET_EXCEEDED
            || (!countInternalErrors && code == CelErrorCode.INTERNAL_ERROR)) {
          throw e;
        }
        result = EvaluationError.ERROR;
      }
      // A binding may return null or unknowns.
      if (result == null || result instanceof CelUnknownSet) {
        throw new CelEvaluationException(
            result == null ? "a function returned null" : "it evaluated to unknowns");
      }
      results.add(result);
    }
    return results.build();
  }

  private RuntimeOracle() {}
}
