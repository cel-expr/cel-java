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

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableMap;
import dev.cel.bundle.Cel;
import dev.cel.bundle.CelFactory;
import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.CelFunctionDecl;
import dev.cel.common.CelOverloadDecl;
import dev.cel.common.types.SimpleType;
import dev.cel.runtime.CelEvaluationException;
import java.util.Optional;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class RuntimeOracleTest {

  private static final Cel CEL = CelFactory.plannerCelBuilder().addVar("x", SimpleType.INT).build();

  @Test
  public void isSatisfiable_runtimeRefutesSatisfyingInput_throws() throws Exception {
    CelAbstractSyntaxTree ast = CEL.compile("x == 1").getAst();
    CelVerifier verifier =
        checking(CelVerificationResult.verified("Condition is satisfiable.", witness(2L)));

    AssertionError e = assertThrows(AssertionError.class, () -> verifier.isSatisfiable(ast));

    assertThat(e).hasMessageThat().contains("x == 1 evaluates to false");
  }

  @Test
  public void isAlwaysTrue_runtimeRefutesCounterexample_throws() throws Exception {
    CelAbstractSyntaxTree ast = CEL.compile("x == 1").getAst();
    CelVerifier verifier = checking(CelVerificationResult.failed("Violated.", witness(1L)));

    AssertionError e = assertThrows(AssertionError.class, () -> verifier.isAlwaysTrue(ast));

    assertThat(e).hasMessageThat().contains("x == 1 evaluates to true");
  }

  @Test
  public void verifyEquivalence_runtimeRefutesDivergence_throws() throws Exception {
    CelAbstractSyntaxTree astA = CEL.compile("x + 0").getAst();
    CelAbstractSyntaxTree astB = CEL.compile("x").getAst();
    CelVerifier verifier = checking(CelVerificationResult.failed("Violated.", witness(1L)));

    AssertionError e =
        assertThrows(AssertionError.class, () -> verifier.verifyEquivalence(astA, astB));

    assertThat(e).hasMessageThat().contains("x + 0 evaluates to 1");
    assertThat(e).hasMessageThat().contains("\n  x evaluates to 1");
  }

  @Test
  public void isSatisfiable_inexactSatisfyingInput_notChecked() throws Exception {
    CelAbstractSyntaxTree ast = CEL.compile("x == 1").getAst();
    CelVerificationResult result =
        CelVerificationResult.verified(
            "Condition is satisfiable.",
            CelWitness.create(
                ImmutableMap.of("x", CelWitness.Variable.create(Optional.empty(), "Unknown"))));

    assertThat(checking(result).isSatisfiable(ast)).isSameInstanceAs(result);
  }

  @Test
  public void verifyEquivalence_bothExpressionsError_throws() throws Exception {
    CelAbstractSyntaxTree astA = CEL.compile("1 / x").getAst();
    CelAbstractSyntaxTree astB = CEL.compile("2 / x").getAst();
    CelVerifier verifier = checking(CelVerificationResult.failed("Violated.", witness(0L)));

    AssertionError e =
        assertThrows(AssertionError.class, () -> verifier.verifyEquivalence(astA, astB));

    assertThat(e).hasMessageThat().contains("1 / x evaluates to ERROR");
    assertThat(e).hasMessageThat().contains("2 / x evaluates to ERROR");
  }

  @Test
  public void isSatisfiable_inconclusiveResult_notChecked() throws Exception {
    CelAbstractSyntaxTree ast = CEL.compile("x == 1").getAst();
    CelVerificationResult result =
        CelVerificationResult.inconclusive(
            "Inconclusive.", witness(2L), /* isCounterexample= */ false);

    assertThat(checking(result).isSatisfiable(ast)).isSameInstanceAs(result);
  }

  @Test
  public void isSatisfiable_unplannableExpression_throws() throws Exception {
    Cel celWithFunction =
        CEL.toCelBuilder()
            .addFunctionDeclarations(
                CelFunctionDecl.newFunctionDeclaration(
                    "f",
                    CelOverloadDecl.newGlobalOverload("f_int", SimpleType.BOOL, SimpleType.INT)))
            .build();
    CelAbstractSyntaxTree ast = celWithFunction.compile("f(x)").getAst();
    CelVerifier verifier =
        checking(CelVerificationResult.verified("Condition is satisfiable.", witness(1L)));

    AssertionError e = assertThrows(AssertionError.class, () -> verifier.isSatisfiable(ast));

    assertThat(e).hasMessageThat().contains("Can't plan f(x)");
    assertThat(e).hasCauseThat().isInstanceOf(CelEvaluationException.class);
  }

  @Test
  public void isAlwaysTrue_missingVariableInWitness_throws() throws Exception {
    CelAbstractSyntaxTree ast = CEL.compile("x == 1").getAst();
    CelVerifier verifier =
        checking(CelVerificationResult.failed("Violated.", CelWitness.create(ImmutableMap.of())));

    AssertionError e = assertThrows(AssertionError.class, () -> verifier.isAlwaysTrue(ast));

    assertThat(e).hasMessageThat().contains("Incomplete witness {} for x == 1");
    assertThat(e).hasCauseThat().isInstanceOf(CelEvaluationException.class);
  }

  private static CelWitness witness(long x) {
    return CelWitness.create(
        ImmutableMap.of("x", CelWitness.Variable.create(Optional.of(x), Long.toString(x))));
  }

  @SuppressWarnings("Immutable") // Test fake; the result is never mutated.
  private static CelVerifier checking(CelVerificationResult result) {
    return RuntimeOracle.wrap(
        CEL,
        new CelVerifier() {
          @Override
          public CelVerificationResult isSatisfiable(CelAbstractSyntaxTree ast) {
            return result;
          }

          @Override
          public CelVerificationResult isAlwaysTrue(CelAbstractSyntaxTree ast) {
            return result;
          }

          @Override
          public CelVerificationResult verifyEquivalence(
              CelAbstractSyntaxTree astA, CelAbstractSyntaxTree astB) {
            return result;
          }
        });
  }
}
