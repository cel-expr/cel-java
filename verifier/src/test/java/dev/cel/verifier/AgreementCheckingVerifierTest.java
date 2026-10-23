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
import dev.cel.common.types.SimpleType;
import java.util.Optional;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class AgreementCheckingVerifierTest {

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

  private static CelWitness witness(long x) {
    return CelWitness.create(
        ImmutableMap.of("x", CelWitness.Variable.create(Optional.of(x), Long.toString(x))));
  }

  private static CelVerifier checking(CelVerificationResult result) {
    return AgreementCheckingVerifier.wrap(CEL, new FixedResultVerifier(result));
  }

  @SuppressWarnings("Immutable") // Test fake; the result is never mutated.
  private static final class FixedResultVerifier implements CelVerifier {
    private final CelVerificationResult result;

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

    private FixedResultVerifier(CelVerificationResult result) {
      this.result = result;
    }
  }
}
