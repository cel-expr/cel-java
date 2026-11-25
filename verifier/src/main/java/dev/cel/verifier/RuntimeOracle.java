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

import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.CelErrorCode;
import dev.cel.common.exceptions.CelRuntimeException;
import dev.cel.runtime.CelEvaluationException;
import dev.cel.runtime.CelFunctionResolver;
import dev.cel.runtime.CelResolvedOverload;
import dev.cel.runtime.CelRuntime;
import dev.cel.runtime.CelRuntime.Program;
import dev.cel.runtime.CelUnknownSet;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Evaluates expressions with the CEL runtime on a witness's inputs, to check the solver's
 * candidates.
 */
final class RuntimeOracle {

  // Stands for every evaluation error, matching the verifier's single error value.
  private enum EvaluationError {
    ERROR
  }

  private final CelRuntime runtime;
  private final Map<String, ?> inputs;
  private final ImmutableMap<String, Supplier<Object>> boundSymbols;
  private final boolean countInternalErrors;
  // Why the runtime can't evaluate the expression, even if `||` or `&&` keeps another error.
  private final AtomicReference<CelEvaluationException> uncheckableCause;
  private final CelFunctionResolver lateBoundFunctions;

  /** The witness's inputs, if every value is exact. */
  static Optional<ImmutableMap<String, Object>> exactInputs(CelWitness witness) {
    return witness.variables().values().stream().allMatch(v -> v.value().isPresent())
        ? Optional.of(
            ImmutableMap.copyOf(Maps.transformValues(witness.variables(), v -> v.value().get())))
        : Optional.empty();
  }

  /**
   * Returns the values of {@code asts} for {@code inputs} and {@code boundSymbols}, with errors as
   * one value. Throws if the runtime can't evaluate one, e.g. a function has no binding or its
   * iteration limit is hit, or if it fails with an internal error and {@code countInternalErrors}
   * is false.
   */
  static ImmutableList<Object> evaluate(
      CelRuntime runtime,
      Map<String, ?> inputs,
      ImmutableMap<String, CelAbstractSyntaxTree> boundSymbols,
      boolean countInternalErrors,
      CelAbstractSyntaxTree... asts)
      throws CelEvaluationException {
    RuntimeOracle oracle = new RuntimeOracle(runtime, inputs, boundSymbols, countInternalErrors);
    ImmutableList.Builder<Object> results = ImmutableList.builder();
    for (CelAbstractSyntaxTree ast : asts) {
      try {
        results.add(oracle.eval(ast, oracle.boundSymbols));
      } catch (CelEvaluationException unused) {
        CelEvaluationException cause = oracle.uncheckableCause.get();
        if (cause != null) {
          throw cause;
        }
        results.add(EvaluationError.ERROR);
      }
    }
    return results.build();
  }

  private Object eval(CelAbstractSyntaxTree ast, ImmutableMap<String, Supplier<Object>> symbols)
      throws CelEvaluationException {
    Program program;
    try {
      program = runtime.createProgram(ast);
    } catch (CelEvaluationException e) {
      uncheckableCause.compareAndSet(null, e);
      throw e;
    }
    CelEvaluationException outer = uncheckableCause.get();
    Object result;
    try {
      result =
          program.eval(
              name -> {
                Supplier<Object> symbol = symbols.get(name);
                return symbol == null
                    ? Optional.ofNullable(inputs.get(name))
                    : Optional.of(symbol.get());
              },
              lateBoundFunctions);
    } catch (CelEvaluationException e) {
      CelErrorCode code = e.getErrorCode();
      // The runtime reports some CEL errors as internal, but also misused bindings (e.g. async).
      if (code == CelErrorCode.ITERATION_BUDGET_EXCEEDED
          || (!countInternalErrors && code == CelErrorCode.INTERNAL_ERROR)) {
        uncheckableCause.compareAndSet(null, e);
      }
      throw e;
    }
    if (result == null || result instanceof CelUnknownSet) {
      CelEvaluationException e =
          new CelEvaluationException(
              result == null ? "a function returned null" : "it evaluated to unknowns");
      uncheckableCause.compareAndSet(null, e);
      throw e;
    }
    // An expression with a value doesn't pass on errors it absorbed.
    uncheckableCause.set(outer);
    return result;
  }

  // Keeps the error code, so a symbol's CEL errors aren't taken for internal ones.
  private static final class SymbolError extends CelRuntimeException {
    private SymbolError(CelEvaluationException e) {
      super(e, e.getErrorCode());
    }
  }

  private RuntimeOracle(
      CelRuntime runtime,
      Map<String, ?> inputs,
      ImmutableMap<String, CelAbstractSyntaxTree> boundSymbols,
      boolean countInternalErrors) {
    this.runtime = runtime;
    this.inputs = inputs;
    this.countInternalErrors = countInternalErrors;
    this.uncheckableCause = new AtomicReference<>();
    // Like the verifier, a symbol reads only the symbols before it.
    ImmutableMap.Builder<String, Supplier<Object>> symbols = ImmutableMap.builder();
    for (Map.Entry<String, CelAbstractSyntaxTree> symbol : boundSymbols.entrySet()) {
      ImmutableMap<String, Supplier<Object>> visible = symbols.buildOrThrow();
      symbols.put(
          symbol.getKey(),
          Suppliers.memoize(() -> {
            CelEvaluationException outer = uncheckableCause.get();
            try {
              return eval(symbol.getValue(), visible);
            } catch (CelEvaluationException e) {
              CelEvaluationException cause = uncheckableCause.get();
              if (cause != outer) {
                uncheckableCause.set(
                    new CelEvaluationException(
                        String.format("reading `%s`: %s", symbol.getKey(), cause.getMessage()),
                        cause));
              }
              throw new SymbolError(e);
            }
          }));
    }
    this.boundSymbols = symbols.buildOrThrow();
    // Late-bound functions have no binding here.
    this.lateBoundFunctions =
        new CelFunctionResolver() {
          @Override
          public Optional<CelResolvedOverload> findOverloadMatchingArgs(
              String functionName, Collection<String> overloadIds, Object[] args) {
            return findOverloadMatchingArgs(functionName, args);
          }

          @Override
          public Optional<CelResolvedOverload> findOverloadMatchingArgs(
              String functionName, Object[] args) {
            uncheckableCause.compareAndSet(
                null,
                new CelEvaluationException(
                    "No matching overload for function '" + functionName + "'"));
            return Optional.empty();
          }
        };
  }
}
