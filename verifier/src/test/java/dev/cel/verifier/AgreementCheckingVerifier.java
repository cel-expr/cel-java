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

import static java.util.Arrays.stream;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import dev.cel.bundle.Cel;
import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.Operator;
import dev.cel.common.ast.CelExpr;
import dev.cel.common.navigation.CelNavigableAst;
import dev.cel.common.types.MapType;
import dev.cel.common.types.OptionalType;
import dev.cel.common.types.SimpleType;
import dev.cel.runtime.CelEvaluationException;
import dev.cel.runtime.CelRuntime.Program;
import dev.cel.verifier.CelVerificationResult.VerificationStatus;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Evaluates every exact witness a verifier returns with the CEL runtime, and fails if the runtime
 * disagrees with the verdict.
 */
final class AgreementCheckingVerifier implements CelVerifier {

  // All errors compare equal, matching the verifier's single error value.
  private enum EvaluationError {
    ERROR
  }

  @SuppressWarnings("Immutable") // Cel environment is immutable, just not marked as such
  private final Cel cel;

  private final CelVerifier delegate;

  static CelVerifier wrap(Cel cel, CelVerifier delegate) {
    return new AgreementCheckingVerifier(cel, delegate);
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

  private CelVerificationResult check(
      CelVerificationResult result,
      VerificationStatus checkedStatus,
      Predicate<List<Object>> agrees,
      CelAbstractSyntaxTree... asts) {
    Optional<ImmutableMap<String, Object>> inputs = exactInputs(result, asts);
    if (result.status() != checkedStatus || !inputs.isPresent()) {
      return result;
    }
    ImmutableList.Builder<Object> builder = ImmutableList.builder();
    for (CelAbstractSyntaxTree ast : asts) {
      builder.add(evaluate(ast, inputs.get()));
    }
    ImmutableList<Object> results = builder.build();
    if (!agrees.test(results)) {
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
    return result;
  }

  // The witness's inputs, if every value is exact and evaluating them avoids known runtime bugs.
  private static Optional<ImmutableMap<String, Object>> exactInputs(
      CelVerificationResult result, CelAbstractSyntaxTree... asts) {
    if (!result.witness().isPresent()
        || stream(asts).anyMatch(AgreementCheckingVerifier::hitsKnownRuntimeBug)) {
      return Optional.empty();
    }
    ImmutableMap.Builder<String, Object> inputs = ImmutableMap.builder();
    for (Map.Entry<String, CelWitness.Variable> variable :
        result.witness().get().variables().entrySet()) {
      Optional<Object> value = variable.getValue().value();
      // TODO: Remove the NaN and -0.0 check once the runtime compares them correctly.
      if (!value.isPresent() || hasNaNOrNegativeZero(value.get())) {
        return Optional.empty();
      }
      inputs.put(variable.getKey(), value.get());
    }
    return Optional.of(inputs.buildOrThrow());
  }

  private static boolean hasNaNOrNegativeZero(Object value) {
    if (value instanceof Collection) {
      return ((Collection<?>) value)
          .stream().anyMatch(AgreementCheckingVerifier::hasNaNOrNegativeZero);
    }
    return value instanceof Double && (((Double) value).isNaN() || value.equals(-0.0));
  }

  private static boolean hitsKnownRuntimeBug(CelAbstractSyntaxTree ast) {
    return CelNavigableAst.fromAst(ast)
        .getRoot()
        .allNodes()
        .anyMatch(node -> hitsKnownRuntimeBug(ast, node.expr()));
  }

  private static boolean hitsKnownRuntimeBug(CelAbstractSyntaxTree ast, CelExpr expr) {
    switch (expr.getKind()) {
      case STRUCT:
        // TODO: Remove once the runtime rejects invalid Timestamp and
        // Duration messages (out-of-range fields, nanos with the opposite sign of seconds).
        return ast.getType(expr.id())
            .map(t -> t.equals(SimpleType.TIMESTAMP) || t.equals(SimpleType.DURATION))
            .orElse(false);
      case CALL:
        // TODO: Remove once the runtime stops flattening an optional selected from
        // a map, e.g. `{'a': optional.none()}.?a` is `optional.of(optional.none())`.
        return expr.call().function().equals(Operator.OPTIONAL_SELECT.getFunction())
            && ast.getType(expr.call().args().get(0).id())
                .map(t -> t instanceof MapType && ((MapType) t).valueType() instanceof OptionalType)
                .orElse(false);
      default:
        return false;
    }
  }

  private Object evaluate(CelAbstractSyntaxTree ast, ImmutableMap<String, Object> inputs) {
    Program program;
    try {
      program = cel.createProgram(ast);
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
      return EvaluationError.ERROR;
    }
  }

  private AgreementCheckingVerifier(Cel cel, CelVerifier delegate) {
    this.cel = cel;
    this.delegate = delegate;
  }
}
