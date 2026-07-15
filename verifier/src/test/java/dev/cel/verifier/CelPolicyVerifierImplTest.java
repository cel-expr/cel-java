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

import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import dev.cel.bundle.Cel;
import dev.cel.bundle.CelFactory;
import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.CelOptions;
import dev.cel.common.types.SimpleType;
import dev.cel.common.types.StructTypeReference;
import dev.cel.expr.conformance.proto3.TestAllTypes;
import dev.cel.extensions.CelExtensions;
import dev.cel.optimizer.CelOptimizer;
import dev.cel.optimizer.CelOptimizerFactory;
import dev.cel.optimizer.optimizers.SubexpressionOptimizer;
import dev.cel.optimizer.optimizers.SubexpressionOptimizer.SubexpressionOptimizerOptions;
import dev.cel.parser.CelStandardMacro;
import dev.cel.parser.CelUnparserFactory;
import dev.cel.policy.CelPolicy;
import dev.cel.policy.CelPolicyCompiler;
import dev.cel.policy.CelPolicyCompilerFactory;
import dev.cel.policy.CelPolicyParser;
import dev.cel.policy.CelPolicyParserFactory;
import dev.cel.verifier.CelVerificationResult.VerificationStatus;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(TestParameterInjector.class)
public final class CelPolicyVerifierImplTest {

  private static final CelPolicyParser PARSER =
      CelPolicyParserFactory.newYamlParserBuilder().enableSimpleVariables(true).build();

  private static final Cel CEL =
      CelFactory.standardCelBuilder()
          .setOptions(CelOptions.current().populateMacroCalls(true).build())
          .setStandardMacros(CelStandardMacro.STANDARD_MACROS)
          .addCompilerLibraries(CelExtensions.bindings())
          .addMessageTypes(TestAllTypes.getDescriptor())
          .addVar("x", SimpleType.INT)
          .addVar("y", SimpleType.INT)
          .addVar("a", SimpleType.BOOL)
          .addVar("b", SimpleType.BOOL)
          .addVar("role", SimpleType.STRING)
          .addVar("country", SimpleType.STRING)
          .addVar("port", SimpleType.INT)
          .addVar("request", SimpleType.DYN)
          .addVar(
              "test_all_types",
              StructTypeReference.create("cel.expr.conformance.proto3.TestAllTypes"))
          .build();

  private static final CelPolicyCompiler POLICY_COMPILER =
      CelPolicyCompilerFactory.newPolicyCompiler(CEL).build();

  private static final CelVerifier AST_VERIFIER = CelVerifierFactory.newVerifier().build();
  private static final CelPolicyVerifier VERIFIER =
      CelPolicyVerifierFactory.newVerifier(POLICY_COMPILER, AST_VERIFIER).build();

  @Before
  public void setUp() {
    System.setProperty("z3.skipLibraryLoad", "true");
  }

  private enum EquivalenceTestCase {
    DE_MORGANS_LAW(
        "name: pA\n" //
            + "rule:\n" //
            + "  match:\n" //
            + "    - output: '!(a && b)'",
        "name: pB\n" //
            + "rule:\n" //
            + "  match:\n" //
            + "    - output: '!a || !b'"),
    CONSTANT_FOLDING(
        "name: pA\n" //
            + "rule:\n" //
            + "  match:\n" //
            + "    - output: 'x > 5 + 5'",
        "name: pB\n" //
            + "rule:\n" //
            + "  match:\n" //
            + "    - output: 'x > 10'"),
    STRING_COMPARISON(
        "name: pA\n" //
            + "rule:\n" //
            + "  match:\n" //
            + "    - output: 'role == \"admin\"'",
        "name: pB\n" //
            + "rule:\n" //
            + "  match:\n" //
            + "    - output: '\"admin\" == role'"),
    FLATTENED_SELECT(
        "name: pA\n" //
            + "rule:\n" //
            + "  match:\n" //
            + "    - output: 'test_all_types.single_string == \"admin\"'",
        "name: pB\n" //
            + "rule:\n" //
            + "  match:\n" //
            + "    - output: '\"admin\" == test_all_types.single_string'"),
    REALISTIC_REFACTORING(
        "name: legacy_authz\n"
            + "rule:\n"
            + "  match:\n"
            + "    - output: '(role == \"admin\" || (role == \"editor\" && country == \"US\")) &&"
            + " port == 443'",
        "name: refactored_authz\n"
            + "rule:\n"
            + "  variables:\n"
            + "    - is_admin: 'role == \"admin\"'\n"
            + "    - is_us_editor: 'role == \"editor\" && country == \"US\"'\n"
            + "    - is_secure: 'port == 443'\n"
            + "  match:\n"
            + "    - output: '(variables.is_admin || variables.is_us_editor) &&"
            + " variables.is_secure'"),
    MACRO_EXISTS_EQUIVALENT(
        "name: pA\n" //
            + "rule:\n" //
            + "  match:\n" //
            + "    - output: '[1, 2, 3].exists(x, x > 0)'",
        "name: pB\n" //
            + "rule:\n" //
            + "  match:\n" //
            + "    - output: '[1, 2, 3].exists(y, y > 0)'"),
    IN_LIST_EQUIVALENT(
        "name: pA\n" //
            + "rule:\n" //
            + "  match:\n" //
            + "    - output: '2 in [1, 2, 3]'",
        "name: pB\n" //
            + "rule:\n" //
            + "  match:\n" //
            + "    - output: '2 in [1, 2, 3] || false'"),
    MACRO_ALL_EQUIVALENT(
        "name: pA\n" //
            + "rule:\n" //
            + "  match:\n" //
            + "    - output: '[1, 2, 3].all(x, x > 0)'",
        "name: pB\n" //
            + "rule:\n" //
            + "  match:\n" //
            + "    - output: '1 > 0 && 2 > 0 && 3 > 0'"),
    MACRO_EXISTS_ONE_EQUIVALENT(
        "name: pA\n" //
            + "rule:\n" //
            + "  match:\n" //
            + "    - output: '[1, 2, 3].exists_one(x, x == 2)'",
        "name: pB\n" //
            + "rule:\n" //
            + "  match:\n" //
            + "    - output: '(1 == 2 ? 1 : 0) + (2 == 2 ? 1 : 0) + (3 == 2 ? 1 : 0) == 1'"),
    MACRO_MAP_EQUIVALENT(
        "name: pA\n" //
            + "rule:\n" //
            + "  match:\n" //
            + "    - output: '{1: true, 2: true, 3: true}.all(k, k > 0)'",
        "name: pB\n" //
            + "rule:\n" //
            + "  match:\n" //
            + "    - output: '1 > 0 && 2 > 0 && 3 > 0'"),
    MACRO_SHADOWING_EQUIVALENT(
        "name: pA\n" //
            + "rule:\n" //
            + "  variables:\n" //
            + "    - x: '10'\n" //
            + "  match:\n" //
            + "    - output: '[1, 2, 3].all(x, x > 0) && variables.x == 10'",
        "name: pB\n" //
            + "rule:\n" //
            + "  match:\n" //
            + "    - output: '(1 > 0 && 2 > 0 && 3 > 0) && 10 == 10'"),
    MACRO_BIND_EQUIVALENT(
        "name: pA\n" //
            + "rule:\n" //
            + "  match:\n" //
            + "    - output: 'cel.bind(x, 10, x > 0)'",
        "name: pB\n" //
            + "rule:\n" //
            + "  match:\n" //
            + "    - output: '10 > 0'"),
    COMPREHENSION_SHADOWING_DOES_NOT_LEAK(
        "name: pA\n" //
            + "rule:\n" //
            + "  match:\n" //
            + "    - output: '[1, 2].all(x, x > 0) && x == 0'",
        "name: pB\n" //
            + "rule:\n" //
            + "  match:\n" //
            + "    - output: 'x == 0'"),
    MODULO_BY_ONE_EQUIVALENT(
        "name: pA\n" //
            + "rule:\n" //
            + "  match:\n" //
            + "    - output: '5 % 1 == 0'\n",
        "name: pB\n" //
            + "rule:\n" //
            + "  match:\n" //
            + "    - output: 'true'\n"),
    EMPTY_VARIABLES(
        "name: pA\n" //
            + "rule:\n" //
            + "  variables: []\n" //
            + "  match:\n" //
            + "    - output: 'true'",
        "name: pB\n" //
            + "rule:\n" //
            + "  match:\n" //
            + "    - output: 'true'"),
    SHADOWED_VARIABLES(
        "name: pA\n" //
            + "rule:\n" //
            + "  variables:\n" //
            + "    - a: '1'\n" //
            + "    - a: '2'\n" //
            + "  match:\n" //
            + "    - output: 'variables.a == 2'",
        "name: pB\n" //
            + "rule:\n" //
            + "  match:\n" //
            + "    - output: 'true'");

    private final String policySourceA;
    private final String policySourceB;

    EquivalenceTestCase(String policySourceA, String policySourceB) {
      this.policySourceA = policySourceA;
      this.policySourceB = policySourceB;
    }
  }

  @Test
  public void verifyEquivalence_success(@TestParameter EquivalenceTestCase testCase)
      throws Exception {
    CelPolicy policyA = PARSER.parse(testCase.policySourceA);
    CelPolicy policyB = PARSER.parse(testCase.policySourceB);

    CelVerificationResult result = VERIFIER.verifyEquivalence(policyA, policyB);

    assertThat(result.status()).isEqualTo(VerificationStatus.VERIFIED);
  }

  @Test
  public void verifyEquivalence_violation_throws() throws Exception {
    String policySourceA =
        "name: pA\n" //
            + "rule:\n" //
            + "  match:\n" //
            + "    - output: 'x > 10'";
    String policySourceB =
        "name: pB\n" //
            + "rule:\n" //
            + "  match:\n" //
            + "    - output: 'x > 5'";
    CelPolicy policyA = PARSER.parse(policySourceA);
    CelPolicy policyB = PARSER.parse(policySourceB);

    CelVerificationResult result = VERIFIER.verifyEquivalence(policyA, policyB);

    assertThat(result.status()).isEqualTo(VerificationStatus.VIOLATED);
    assertThat(result.message())
        .containsMatch(
            "Equivalence violation detected\\. Counterexample input:\\n  x = (6|7|8|9|10)");
  }

  @Test
  public void verifyEquivalence_celBlockSupport_cseOptimizer(
      @TestParameter({
            "request.a + request.b == request.a + request.b",
            "size([1, 2, 3]) + size([1, 2, 3]) == 6",
            "size('hello') > 0 && size('hello') > 0",
            "test_all_types.single_int32 + test_all_types.single_int32 == 10",
            "test_all_types.single_int32 + test_all_types.single_int64 =="
                + " test_all_types.single_int32 + test_all_types.single_int64",
            "true || ((1/0 == 1) || (1/0 == 1))",
            "(size('hello') + 1) + (size('hello') + 1) * 2"
          })
          String expression)
      throws Exception {
    CelOptimizer celOptimizer =
        CelOptimizerFactory.standardCelOptimizerBuilder(CEL)
            .addAstOptimizers(
                SubexpressionOptimizer.newInstance(
                    SubexpressionOptimizerOptions.newBuilder().populateMacroCalls(true).build()))
            .build();
    CelAbstractSyntaxTree unoptimizedAst = CEL.compile(expression).getAst();
    CelAbstractSyntaxTree optimizedAst = celOptimizer.optimize(unoptimizedAst);
    CelVerificationResult result = AST_VERIFIER.verifyEquivalence(unoptimizedAst, optimizedAst);

    String unparsed = CelUnparserFactory.newUnparser().unparse(optimizedAst);
    assertThat(unparsed).startsWith("cel.@block");
    assertThat(result.status()).isEqualTo(VerificationStatus.VERIFIED);
  }
}
