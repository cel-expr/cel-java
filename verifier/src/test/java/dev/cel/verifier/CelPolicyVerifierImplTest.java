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
import static com.google.common.truth.Truth.assertWithMessage;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableMap;
import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import dev.cel.bundle.Cel;
import dev.cel.bundle.CelFactory;
import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.CelFunctionDecl;
import dev.cel.common.CelOptions;
import dev.cel.common.CelOverloadDecl;
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
import dev.cel.policy.CelPolicyValidationException;
import dev.cel.verifier.CelVerificationResult.VerificationStatus;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(TestParameterInjector.class)
public final class CelPolicyVerifierImplTest {

  private static final CelPolicyParser PARSER =
      CelPolicyParserFactory.newYamlParserBuilder().enableSimpleVariables(true).build();

  private static final Cel CEL =
      CelFactory.plannerCelBuilder()
          .setOptions(
              CelOptions.current()
                  .populateMacroCalls(true)
                  .enableHeterogeneousNumericComparisons(true)
                  .build())
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
            "Equivalence violation detected\\. Counterexample input:\\n {2}x = (6|7|8|9|10)");
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

  @Test
  public void verifyInvariants_emptyInvariants_returnsEmptyMap() throws Exception {
    String policySource =
        "name: empty_policy\n" //
            + "rule:\n" //
            + "  match:\n" //
            + "    - output: 'true'";
    CelPolicy policy = PARSER.parse(policySource);

    ImmutableMap<String, CelVerificationResult> results = VERIFIER.verifyInvariants(policy);

    assertThat(results).isEmpty();
  }

  @Test
  public void verifyInvariants_flawedPolicy_violationDetected() throws Exception {
    CelPolicy policy =
        PARSER.parse(VerifierTestHelper.loadVerificationPolicyYaml("flawed_policy.yaml"));

    ImmutableMap<String, CelVerificationResult> results = VERIFIER.verifyInvariants(policy);

    assertThat(results).containsKey("always_secure");
    CelVerificationResult result = results.get("always_secure");
    assertThat(result.status()).isEqualTo(VerificationStatus.VIOLATED);
    assertThat(result.message()).containsMatch("port = 80");
  }

  @Test
  public void verifyInvariants_secureResourceAccess_verified() throws Exception {
    Cel extendedCel =
        CEL.toCelBuilder()
            .addVar("resource", SimpleType.DYN)
            .build();
    CelPolicyVerifier verifier =
        CelPolicyVerifierFactory.newVerifier(
                CelPolicyCompilerFactory.newPolicyCompiler(extendedCel).build(), AST_VERIFIER)
            .build();

    CelPolicy policy =
        PARSER.parse(VerifierTestHelper.loadVerificationPolicyYaml("secure_resource_access.yaml"));

    ImmutableMap<String, CelVerificationResult> results = verifier.verifyInvariants(policy);

    assertThat(results).containsKey("no_unprivileged_break_glass");
    assertThat(results.get("no_unprivileged_break_glass").status())
        .isEqualTo(VerificationStatus.VERIFIED);
  }

  @Test
  public void verifyInvariants_multipleInvariants_mixedResults() throws Exception {
    CelPolicy policy =
        PARSER.parse(VerifierTestHelper.loadVerificationPolicyYaml("multi_invariant_policy.yaml"));

    ImmutableMap<String, CelVerificationResult> results = VERIFIER.verifyInvariants(policy);

    assertThat(results).hasSize(2);
    assertThat(results.get("admin_granted").status()).isEqualTo(VerificationStatus.VERIFIED);
    assertThat(results.get("viewer_granted").status()).isEqualTo(VerificationStatus.VIOLATED);
    assertThat(results.get("viewer_granted").message()).containsMatch("role = \"viewer\"");
  }

  @Test
  public void verifyInvariants_invalidAssumeClause_throwsValidationException() throws Exception {
    String policySource =
        "name: invalid_assume\n" //
            + "rule:\n" //
            + "  match:\n" //
            + "    - output: 'true'\n" //
            + "verification:\n" //
            + "  invariants:\n" //
            + "    - id: bad_assume\n" //
            + "      assume: non_existent_var == 123\n" //
            + "      assert: rule.result == true";
    CelPolicy policy = PARSER.parse(policySource);

    assertThrows(CelPolicyValidationException.class, () -> VERIFIER.verifyInvariants(policy));
  }

  @Test
  public void verifyInvariants_invalidAssertClause_throwsValidationException() throws Exception {
    String policySource =
        "name: invalid_assert\n" //
            + "rule:\n" //
            + "  match:\n" //
            + "    - output: 'true'\n" //
            + "verification:\n" //
            + "  invariants:\n" //
            + "    - id: bad_assert\n" //
            + "      assert: rule.result + non_existent_var == true";
    CelPolicy policy = PARSER.parse(policySource);

    assertThrows(CelPolicyValidationException.class, () -> VERIFIER.verifyInvariants(policy));
  }

  @Test
  public void verifyInvariants_restrictedDestinationsPolicy_verified() throws Exception {
    Cel extendedCel =
        CEL.toCelBuilder()
            .addVar("origin", SimpleType.DYN)
            .addVar("destination", SimpleType.DYN)
            .addVar("spec", SimpleType.DYN)
            .addVar("resource", SimpleType.DYN)
            .addFunctionDeclarations(
                CelFunctionDecl.newFunctionDeclaration(
                    "locationCode",
                    CelOverloadDecl.newGlobalOverload(
                        "locationCode_string", SimpleType.STRING, SimpleType.STRING)))
            .build();
    CelPolicyVerifier verifier =
        CelPolicyVerifierFactory.newVerifier(
                CelPolicyCompilerFactory.newPolicyCompiler(extendedCel).build(), AST_VERIFIER)
            .build();

    CelPolicy policy =
        PARSER.parse(
            VerifierTestHelper.loadVerificationPolicyYaml("restricted_destinations_policy.yaml"));

    ImmutableMap<String, CelVerificationResult> results = verifier.verifyInvariants(policy);

    assertThat(results)
        .containsExactly(
            "restricted_by_nationality_prohibited", CelVerificationResult.verified(),
            "restricted_by_origin_ip_prohibited", CelVerificationResult.verified(),
            "unrestricted_destination_allowed", CelVerificationResult.verified());
  }

  @Test
  public void verifyInvariants_invariantReferencesPolicyVariable_verified() throws Exception {
    String policySource =
        "name: variable_reference_policy\n" //
            + "rule:\n" //
            + "  variables:\n" //
            + "    - is_admin: role == 'admin'\n" //
            + "  match:\n" //
            + "    - condition: variables.is_admin\n" //
            + "      output: 'true'\n" //
            + "    - output: 'false'\n" //
            + "verification:\n" //
            + "  invariants:\n" //
            + "    - id: admin_always_true\n" //
            + "      assume: variables.is_admin\n" //
            + "      assert: rule.result == true";
    CelPolicy policy = PARSER.parse(policySource);

    ImmutableMap<String, CelVerificationResult> results = VERIFIER.verifyInvariants(policy);

    assertThat(results).containsKey("admin_always_true");
    assertThat(results.get("admin_always_true").status()).isEqualTo(VerificationStatus.VERIFIED);
  }

  @Test
  public void verifyInvariants_verificationVariablesAndMultiClauseLists_verified()
      throws Exception {
    String policySource =
        "name: verification_ergonomics_policy\n" //
            + "rule:\n" //
            + "  variables:\n" //
            + "    - rule_admin: role == 'admin'\n" //
            + "  match:\n" //
            + "    - condition: variables.rule_admin\n" //
            + "      output: 'true'\n" //
            + "    - output: 'false'\n" //
            + "verification:\n" //
            + "  variables:\n" //
            + "    - ver_admin: variables.rule_admin && true\n" //
            + "  invariants:\n" //
            + "    - id: ergonomic_inv\n" //
            + "      assume:\n" //
            + "        - variables.ver_admin\n" //
            + "        - role != 'editor'\n" //
            + "      assert:\n" //
            + "        - rule.result == true\n" //
            + "        - role == 'admin'";
    CelPolicy policy = PARSER.parse(policySource);

    ImmutableMap<String, CelVerificationResult> results = VERIFIER.verifyInvariants(policy);

    assertThat(results).containsExactly("ergonomic_inv", CelVerificationResult.verified());
  }

  @Test
  public void verifyInvariants_workloadAdmissionFlawed_violationsDetected() throws Exception {
    Cel extendedCel =
        CEL.toCelBuilder()
            .addVar("is_admin", SimpleType.BOOL)
            .addVar("is_owner", SimpleType.BOOL)
            .addVar("is_privileged", SimpleType.BOOL)
            .addVar("is_prod", SimpleType.BOOL)
            .addVar("has_approval", SimpleType.BOOL)
            .addVar("spec", SimpleType.DYN)
            .build();
    CelPolicyVerifier verifier =
        CelPolicyVerifierFactory.newVerifier(
                CelPolicyCompilerFactory.newPolicyCompiler(extendedCel).build(), AST_VERIFIER)
            .build();

    CelPolicy policy =
        PARSER.parse(
            VerifierTestHelper.loadVerificationPolicyYaml("workload_admission_flawed.yaml"));

    ImmutableMap<String, CelVerificationResult> results = verifier.verifyInvariants(policy);

    assertThat(results).containsKey("universal_no_unapproved_privileged_prod");
    assertThat(results.get("universal_no_unapproved_privileged_prod").status())
        .isEqualTo(VerificationStatus.VIOLATED);
    assertThat(results.get("universal_no_unapproved_privileged_prod").message())
        .isEqualTo(
            "Implication violation detected. Counterexample input:\n"
                + "  is_owner = false\n"
                + "  is_privileged = true\n"
                + "  is_prod = true\n"
                + "  has_approval = false\n"
                + "  is_admin = true");
  }

  @Test
  public void verifyInvariants_workloadAdmissionFixed_verified() throws Exception {
    Cel extendedCel =
        CEL.toCelBuilder()
            .addVar("is_admin", SimpleType.BOOL)
            .addVar("is_owner", SimpleType.BOOL)
            .addVar("is_privileged", SimpleType.BOOL)
            .addVar("is_prod", SimpleType.BOOL)
            .addVar("has_approval", SimpleType.BOOL)
            .addVar("spec", SimpleType.DYN)
            .build();
    CelPolicyVerifier verifier =
        CelPolicyVerifierFactory.newVerifier(
                CelPolicyCompilerFactory.newPolicyCompiler(extendedCel).build(), AST_VERIFIER)
            .build();

    CelPolicy policy =
        PARSER.parse(
            VerifierTestHelper.loadVerificationPolicyYaml("workload_admission_fixed.yaml"));

    ImmutableMap<String, CelVerificationResult> results = verifier.verifyInvariants(policy);

    assertThat(results).containsKey("universal_no_unapproved_privileged_prod");
    assertWithMessage(results.get("universal_no_unapproved_privileged_prod").message())
        .that(results.get("universal_no_unapproved_privileged_prod").status())
        .isEqualTo(VerificationStatus.VERIFIED);
  }

  @Test
  public void verifyInvariants_invariantSyntaxError_formatsSnippetAndDescription() throws Exception {
    String yamlPolicy =
        "name: syntax_error_policy\n"
            + "rule:\n"
            + "  match:\n"
            + "    - output: 'true'\n"
            + "verification:\n"
            + "  invariants:\n"
            + "    - id: bad_invariant\n"
            + "      assume:\n"
            + "        - 'port == 80 &&+ port == 90'\n"
            + "      assert:\n"
            + "        - 'rule.result == false'\n";
    CelPolicy policy = PARSER.parse(yamlPolicy);

    CelPolicyValidationException e =
        assertThrows(CelPolicyValidationException.class, () -> VERIFIER.verifyInvariants(policy));
    assertThat(e)
        .hasMessageThat()
        .isEqualTo(
            "ERROR: invariant.bad_invariant.assume:1:14: extraneous input '+' expecting {'[',"
                + " '{', '(', '.', '-', '!', 'true', 'false', 'null', NUM_FLOAT, NUM_INT,"
                + " NUM_UINT, STRING, BYTES, IDENTIFIER}\n"
                + " | port == 80 &&+ port == 90\n"
                + " | .............^");
  }

  @Test
  public void verifyInvariants_verificationVariableSyntaxError_formatsSnippetAndDescription()
      throws Exception {
    String yamlPolicy =
        "name: syntax_error_policy\n"
            + "rule:\n"
            + "  match:\n"
            + "    - output: 'true'\n"
            + "verification:\n"
            + "  variables:\n"
            + "    - bad_var: 'port == 80 &&+ port == 90'\n"
            + "  invariants:\n"
            + "    - id: always_secure\n"
            + "      assert:\n"
            + "        - 'rule.result == false'\n";
    CelPolicy policy = PARSER.parse(yamlPolicy);

    CelPolicyValidationException e =
        assertThrows(CelPolicyValidationException.class, () -> VERIFIER.verifyInvariants(policy));
    assertThat(e)
        .hasMessageThat()
        .isEqualTo(
            "ERROR: verification.variables.bad_var:1:14: extraneous input '+' expecting {'[',"
                + " '{', '(', '.', '-', '!', 'true', 'false', 'null', NUM_FLOAT, NUM_INT,"
                + " NUM_UINT, STRING, BYTES, IDENTIFIER}\n"
                + " | port == 80 &&+ port == 90\n"
                + " | .............^");
  }

  @Test
  public void verifyInvariants_unrelatedBoundedSymbolApproximate_doesNotForceInconclusive()
      throws Exception {
    String yamlPolicy =
        "name: unrelated_approx_policy\n"
            + "rule:\n"
            + "  variables:\n"
            + "    - unrelated_approx: 'request.matches(\"a\") == true'\n"
            + "  match:\n"
            + "    - condition: 'port == 80'\n"
            + "      output: 'true'\n"
            + "    - output: 'false'\n"
            + "verification:\n"
            + "  invariants:\n"
            + "    - id: port_check\n"
            + "      assume:\n"
            + "        - 'port == 80'\n"
            + "      assert:\n"
            + "        - 'rule.result == true'\n";
    CelPolicy policy = PARSER.parse(yamlPolicy);

    ImmutableMap<String, CelVerificationResult> results = VERIFIER.verifyInvariants(policy);

    assertThat(results).containsKey("port_check");
    assertThat(results.get("port_check").status()).isEqualTo(VerificationStatus.VERIFIED);
  }

  @Test
  public void verifyInvariants_verificationVariablesCanReferenceRuleResult() throws Exception {
    String yamlPolicy =
        "name: rule_result_in_ver_var_policy\n"
            + "rule:\n"
            + "  match:\n"
            + "    - condition: 'port == 80'\n"
            + "      output: 'true'\n"
            + "    - output: 'false'\n"
            + "verification:\n"
            + "  variables:\n"
            + "    - is_denied: 'rule.result == false'\n"
            + "  invariants:\n"
            + "    - id: port_check\n"
            + "      assume:\n"
            + "        - 'port == 80'\n"
            + "      assert:\n"
            + "        - 'variables.is_denied == false'\n";
    CelPolicy policy = PARSER.parse(yamlPolicy);

    ImmutableMap<String, CelVerificationResult> results = VERIFIER.verifyInvariants(policy);

    assertThat(results).containsKey("port_check");
    assertThat(results.get("port_check").status()).isEqualTo(VerificationStatus.VERIFIED);
  }

  @Test
  public void verifyInvariants_boundedSymbolWithApproximation_returnsInconclusive()
      throws Exception {
    String yamlPolicy =
        "name: approx_symbol_policy\n"
            + "rule:\n"
            + "  variables:\n"
            + "    - approx_var: 'request.matches(\"^[a-z]+$\") == true'\n"
            + "  match:\n"
            + "    - condition: 'variables.approx_var'\n"
            + "      output: 'true'\n"
            + "    - output: 'false'\n"
            + "verification:\n"
            + "  invariants:\n"
            + "    - id: check_approx\n"
            + "      assert:\n"
            + "        - 'variables.approx_var == true'\n";
    CelPolicy policy = PARSER.parse(yamlPolicy);

    ImmutableMap<String, CelVerificationResult> results = VERIFIER.verifyInvariants(policy);

    assertThat(results).containsKey("check_approx");
    assertThat(results.get("check_approx").status()).isEqualTo(VerificationStatus.INCONCLUSIVE);
  }
}
