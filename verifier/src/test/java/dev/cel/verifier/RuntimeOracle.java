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

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Arrays.stream;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.runtime.CelEvaluationException;
import dev.cel.runtime.CelRuntime;
import dev.cel.runtime.CelRuntime.Program;
import dev.cel.verifier.CelVerificationResult.VerificationStatus;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Evaluates every exact witness a verifier returns with the CEL runtime, and fails if the runtime
 * disagrees with the verdict.
 */
final class RuntimeOracle implements CelVerifier {

  // All errors compare equal, matching the verifier's single error value.
  private enum EvaluationError {
    ERROR
  }

  @SuppressWarnings("Immutable") // CelRuntime is immutable, just not marked as such
  private final CelRuntime runtime;

  private final CelVerifier delegate;

  static CelVerifier wrap(CelRuntime runtime, CelVerifier delegate) {
    return new RuntimeOracle(runtime, delegate);
  }

  @Override
  public CelVerificationResult isSatisfiable(CelAbstractSyntaxTree ast)
      throws CelVerificationException {
    return check(
        delegate.isSatisfiable(ast), VerificationStatus.VERIFIED, r -> r.get(0).equals(true), ast);
  }

  @Override
  public CelVerificationResult isAlwaysTrue(CelAbstractSyntaxTree ast)
      throws CelVerificationException {
    return check(
        delegate.isAlwaysTrue(ast), VerificationStatus.VIOLATED, r -> !r.get(0).equals(true), ast);
  }

  @Override
  public CelVerificationResult verifyEquivalence(
      CelAbstractSyntaxTree astA, CelAbstractSyntaxTree astB) throws CelVerificationException {
    return check(
        delegate.verifyEquivalence(astA, astB),
        VerificationStatus.VIOLATED,
        r -> !r.get(0).equals(r.get(1)),
        astA,
        astB);
  }

  @CanIgnoreReturnValue
  private CelVerificationResult check(
      CelVerificationResult result,
      VerificationStatus checkedStatus,
      Predicate<List<Object>> agrees,
      CelAbstractSyntaxTree... asts) {
    Optional<ImmutableMap<String, Object>> inputs = exactInputs(result);
    if (result.status() != checkedStatus || !inputs.isPresent()) {
      return result;
    }
    ImmutableList<Object> results =
        stream(asts).map(ast -> evaluate(ast, inputs.get())).collect(toImmutableList());
    if (agrees.test(results)) {
      return result;
    }
    StringBuilder message =
        new StringBuilder("The CEL runtime disagrees with ")
            .append(result.status())
            .append(" for inputs ")
            .append(inputs.get())
            .append(':');
    for (int i = 0; i < asts.length; i++) {
      message
          .append("\n  ")
          .append(asts[i].getSource().getContent())
          .append(" evaluates to ")
          .append(results.get(i));
    }
    throw new AssertionError(message.append('\n').append(result.message()).toString());
  }

  // The witness's inputs, if every value is exact.
  private static Optional<ImmutableMap<String, Object>> exactInputs(CelVerificationResult result) {
    return result
        .witness()
        .map(CelWitness::variables)
        .filter(vars -> vars.values().stream().allMatch(v -> v.value().isPresent()))
        .map(vars -> ImmutableMap.copyOf(Maps.transformValues(vars, v -> v.value().get())));
  }

  private Object evaluate(CelAbstractSyntaxTree ast, ImmutableMap<String, Object> inputs) {
    Program program;
    try {
      program = runtime.createProgram(ast);
    } catch (CelEvaluationException e) {
      throw new AssertionError(
          "Can't plan "
              + ast.getSource().getContent()
              + "; verify it with a verifier for its own environment",
          e);
    }
    try {
      return program.eval(inputs);
    } catch (CelEvaluationException e) {
      // A witness missing a variable would otherwise pass as an error result.
      if (e.getMessage().contains("No such attribute(s): ")) {
        throw new AssertionError(
            "Incomplete witness " + inputs + " for " + ast.getSource().getContent(), e);
      }
      return EvaluationError.ERROR;
    }
  }

  private RuntimeOracle(CelRuntime runtime, CelVerifier delegate) {
    this.runtime = runtime;
    this.delegate = delegate;
  }
}
