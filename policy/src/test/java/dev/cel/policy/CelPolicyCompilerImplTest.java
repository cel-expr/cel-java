// Copyright 2024 Google LLC
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

package dev.cel.policy;

import static com.google.common.base.Strings.isNullOrEmpty;
import static com.google.common.truth.Truth.assertThat;
import static dev.cel.policy.PolicyTestHelper.readFromYaml;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.Resources;
import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import com.google.testing.junit.testparameterinjector.TestParameterValue;
import com.google.testing.junit.testparameterinjector.TestParameterValuesProvider;
import dev.cel.bundle.Cel;
import dev.cel.bundle.CelBuilder;
import dev.cel.bundle.CelEnvironment;
import dev.cel.bundle.CelEnvironmentYamlParser;
import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.CelOptions;
import dev.cel.common.formats.ValueString;
import dev.cel.common.types.ListType;
import dev.cel.common.types.MapType;
import dev.cel.common.types.OptionalType;
import dev.cel.common.types.SimpleType;
import dev.cel.expr.conformance.proto3.TestAllTypes;
import dev.cel.extensions.CelOptionalLibrary;
import dev.cel.parser.CelStandardMacro;
import dev.cel.parser.CelUnparserFactory;
import dev.cel.policy.PolicyTestHelper.PolicyTestSuite;
import dev.cel.policy.PolicyTestHelper.PolicyTestSuite.PolicyTestSection;
import dev.cel.policy.PolicyTestHelper.PolicyTestSuite.PolicyTestSection.PolicyTestCase;
import dev.cel.policy.PolicyTestHelper.PolicyTestSuite.PolicyTestSection.PolicyTestCase.PolicyTestInput;
import dev.cel.policy.PolicyTestHelper.TestYamlPolicy;
import dev.cel.policy.testing.K8sTagHandler;
import dev.cel.runtime.CelFunctionBinding;
import dev.cel.runtime.CelLateFunctionBindings;
import dev.cel.testing.CelRuntimeFlavor;
import dev.cel.testing.testdata.SingleFile;
import dev.cel.testing.testdata.proto3.StandaloneGlobalEnum;
import java.io.IOException;
import java.net.URL;
import java.util.Map;
import java.util.Optional;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(TestParameterInjector.class)
public final class CelPolicyCompilerImplTest {

  private static final CelPolicyParser POLICY_PARSER =
      CelPolicyParserFactory.newYamlParserBuilder().addTagVisitor(new K8sTagHandler()).build();
  private static final CelEnvironmentYamlParser ENVIRONMENT_PARSER =
      CelEnvironmentYamlParser.newInstance();
  private static final CelOptions CEL_OPTIONS =
      CelOptions.current()
          .populateMacroCalls(true)
          .enableHeterogeneousNumericComparisons(true)
          .build();

  @TestParameter private CelRuntimeFlavor runtimeFlavor;

  @Test
  public void compileYamlPolicy_success(@TestParameter TestYamlPolicy yamlPolicy) throws Exception {
    // Read config and produce an environment to compile policies
    String configSource = yamlPolicy.readConfigYamlContent();
    CelEnvironment celEnvironment = ENVIRONMENT_PARSER.parse(configSource);
    Cel cel = celEnvironment.extend(newCel(), CEL_OPTIONS);
    // Read the policy source
    String policySource = yamlPolicy.readPolicyYamlContent();
    CelPolicy policy = POLICY_PARSER.parse(policySource);

    CelAbstractSyntaxTree ast =
        CelPolicyCompilerFactory.newPolicyCompiler(cel).build().compile(policy);

    assertThat(CelUnparserFactory.newUnparser().unparse(ast)).isEqualTo(yamlPolicy.getUnparsed());
  }

  @Test
  public void compileYamlPolicy_withImportsOnNestedRules() throws Exception {
    String policySource =
        "imports:\n"
            + "  - name: cel.expr.conformance.proto3.TestAllTypes\n"
            + "  - name: dev.cel.testing.testdata.SingleFile\n"
            + "rule:\n"
            + "  match:\n"
            + "  - rule:\n"
            + "      id: 'nested rule with imports'\n"
            + "      match:\n"
            + "        - condition: 'TestAllTypes{}.single_string == SingleFile{}.name'\n"
            + "          output: 'true'\n";
    Cel cel = newCel();
    CelPolicy policy = POLICY_PARSER.parse(policySource);

    CelAbstractSyntaxTree ast =
        CelPolicyCompilerFactory.newPolicyCompiler(cel).build().compile(policy);

    assertThat(ast.getResultType()).isEqualTo(OptionalType.create(SimpleType.BOOL));
  }

  @Test
  public void compileYamlPolicy_nestedRuleOptionalFallbackDivergence() throws Exception {
    Cel cel = newCel().toCelBuilder().addVar("input_val", SimpleType.INT).build();
    String policySource =
        "name: grandparent_policy\n"
            + "rule:\n"
            + "  match:\n"
            + "    - condition: 'input_val == 2'\n" // conditional grandparent match
            + "      rule:\n"
            + "        id: parent_rule\n"
            + "        match:\n"
            + "          - condition: 'input_val == 1'\n" // conditional parent match
            + "            rule:\n"
            + "              id: nested_rule\n"
            + "              match:\n"
            + "                - condition: 'input_val == 3'\n"
            + "                  output: 'true'\n"
            + "          - output: 'true'\n" // fallback (optional)
            + "    - output: 'true'\n";
    CelPolicy policy = POLICY_PARSER.parse(policySource);
    CelAbstractSyntaxTree ast =
        CelPolicyCompilerFactory.newPolicyCompiler(cel).build().compile(policy);

    assertThat(ast.getResultType()).isEqualTo(OptionalType.create(SimpleType.BOOL));
  }

  @Test
  public void evalYamlPolicy_aggregate() throws Exception {
    String policySource =
        "name: \"aggregate_policy\"\n"
            + "rule:\n"
            + "  aggregate:\n"
            + "    - condition: 'true'\n"
            + "      output: '\"PII\"'\n"
            + "    - condition: 'true'\n"
            + "      output: '\"CONFIDENTIAL\"'\n";
    Cel cel = newCel();
    CelPolicy policy = POLICY_PARSER.parse(policySource);

    CelAbstractSyntaxTree ast =
        CelPolicyCompilerFactory.newPolicyCompiler(cel).build().compile(policy);

    Object evalResult = cel.createProgram(ast).eval();
    assertThat(evalResult).isEqualTo(ImmutableList.of("PII", "CONFIDENTIAL"));
  }

  @Test
  public void evaluateYamlPolicy_aggregate_cseApplied() throws Exception {
    String policySource =
        "name: \"cse_policy\"\n"
            + "rule:\n"
            + "  aggregate:\n"
            + "    - condition: \"size(resource.payload) > 5\"\n"
            + "      output: '\"CSE1\"'\n"
            + "    - condition: \"size(resource.payload) > 5\"\n"
            + "      output: '\"CSE2\"'\n"
            + "    - condition: 'true'\n"
            + "      output: '\"ALWAYS\"'\n";
    Cel cel =
        newCel()
            .toCelBuilder()
            .addVar("resource", MapType.create(SimpleType.STRING, ListType.create(SimpleType.INT)))
            .build();
    CelPolicy policy = POLICY_PARSER.parse(policySource);

    CelAbstractSyntaxTree ast =
        CelPolicyCompilerFactory.newPolicyCompiler(cel).build().compile(policy);

    String unparsed = CelUnparserFactory.newUnparser().unparse(ast);
    assertThat(unparsed)
        .isEqualTo(
            "cel.@block("
                + "[size(resource.payload) > 5], "
                + "(@index0 ? [\"CSE1\"] : []) "
                + "+ ((@index0 ? [\"CSE2\"] : []) + [\"ALWAYS\"]))");

    // Evaluate under true condition (size of payload is 6 > 5)
    ImmutableMap<String, Object> inputTrue =
        ImmutableMap.of("resource", ImmutableMap.of("payload", ImmutableList.of(1, 2, 3, 4, 5, 6)));
    Object evalResultTrue = cel.createProgram(ast).eval(inputTrue);
    assertThat(evalResultTrue).isEqualTo(ImmutableList.of("CSE1", "CSE2", "ALWAYS"));
    // Evaluate under false condition (size of payload is 3 <= 5)
    ImmutableMap<String, Object> inputFalse =
        ImmutableMap.of("resource", ImmutableMap.of("payload", ImmutableList.of(1, 2, 3)));
    Object evalResultFalse = cel.createProgram(ast).eval(inputFalse);
    assertThat(evalResultFalse).isEqualTo(ImmutableList.of("ALWAYS"));
  }

  @Test
  public void compileYamlPolicy_aggregate_macrosPreserved() throws Exception {
    String policySource =
        "name: aggregate_macros_preserved\n"
            + "rule:\n"
            + "  aggregate:\n"
            + "    - condition: \"cond\"\n"
            + "      rule:\n"
            + "        match:\n"
            + "          - condition: \"true\"\n"
            + "            output: \"payload.filter(x, x > 10).exists(y, y % 2 == 0)\"\n"
            + "    - condition: \"true\"\n"
            + "      output: \"payload.all(x, x > 0)\"\n";
    Cel cel =
        newCel()
            .toCelBuilder()
            .addVar("cond", SimpleType.BOOL)
            .addVar("payload", ListType.create(SimpleType.INT))
            .build();

    CelPolicy policy = POLICY_PARSER.parse(policySource);

    CelAbstractSyntaxTree ast =
        CelPolicyCompilerFactory.newPolicyCompiler(cel).build().compile(policy);

    String unparsed = CelUnparserFactory.newUnparser().unparse(ast);
    assertThat(unparsed)
        .isEqualTo(
            "(cond ? [payload.filter(x, x > 10, x).exists(y, y % 2 == 0)] : []) "
                + "+ ([payload.all(x, x > 0)] + [])");
  }

  @Test
  public void compileYamlPolicy_nestedAggregate_throws() throws Exception {
    String policySource =
        "name: nested_aggregate\n"
            + "rule:\n"
            + "  aggregate:\n"
            + "    - condition: 'true'\n"
            + "      rule:\n"
            + "        aggregate:\n"
            + "          - condition: 'true'\n"
            + "            output: \"'foo'\"\n";
    CelPolicy policy = POLICY_PARSER.parse(policySource);

    CelPolicyValidationException e =
        assertThrows(
            CelPolicyValidationException.class,
            () -> CelPolicyCompilerFactory.newPolicyCompiler(newCel()).build().compile(policy));

    assertThat(e)
        .hasMessageThat()
        .contains("ERROR: <input>:6:9: nested aggregate rules are not allowed");
  }

  @Test
  public void compileYamlPolicy_nestedAggregate_withInterveningMatch_throws() throws Exception {
    String policySource =
        "name: nested_aggregate_with_match\n"
            + "rule:\n"
            + "  aggregate:\n"
            + "    - condition: 'true'\n"
            + "      rule:\n"
            + "        match:\n"
            + "          - condition: 'true'\n"
            + "            rule:\n"
            + "              aggregate:\n"
            + "                - condition: 'true'\n"
            + "                  output: \"'foo'\"\n";
    CelPolicy policy = POLICY_PARSER.parse(policySource);

    CelPolicyValidationException e =
        assertThrows(
            CelPolicyValidationException.class,
            () -> CelPolicyCompilerFactory.newPolicyCompiler(newCel()).build().compile(policy));

    assertThat(e)
        .hasMessageThat()
        .contains("ERROR: <input>:9:15: nested aggregate rules are not allowed");
  }

  @Test
  public void compileYamlPolicy_aggregateUnderMatch_success() throws Exception {
    String policySource =
        "name: aggregate_under_match\n"
            + "rule:\n"
            + "  match:\n"
            + "    - condition: 'true'\n"
            + "      rule:\n"
            + "        aggregate:\n"
            + "          - condition: 'true'\n"
            + "            output: \"'foo'\"\n";
    CelPolicy policy = POLICY_PARSER.parse(policySource);

    CelAbstractSyntaxTree ast =
        CelPolicyCompilerFactory.newPolicyCompiler(newCel()).build().compile(policy);

    assertThat(ast).isNotNull();
  }

  @Test
  public void compileYamlPolicy_containsCompilationError_throws(
      @TestParameter TestErrorYamlPolicy testCase) throws Exception {
    // Read config and produce an environment to compile policies
    Optional<String> configSource = testCase.readConfigYamlContent();
    Cel baseCel = newCel();
    Cel cel =
        configSource.isPresent()
            ? ENVIRONMENT_PARSER.parse(configSource.get()).extend(baseCel, CEL_OPTIONS)
            : baseCel;
    // Read the policy source
    String policySource = testCase.readPolicyYamlContent();
    CelPolicy policy = POLICY_PARSER.parse(policySource, testCase.getPolicyFilePath());

    CelPolicyValidationException e =
        assertThrows(
            CelPolicyValidationException.class,
            () -> CelPolicyCompilerFactory.newPolicyCompiler(cel).build().compile(policy));

    assertThat(e).hasMessageThat().isEqualTo(testCase.readExpectedErrorsBaseline());
  }

  @Test
  public void compileYamlPolicy_multilineContainsError_throws(
      @TestParameter MultilineErrorTest testCase) throws Exception {
    String policyContent = testCase.yaml;
    CelPolicy policy = POLICY_PARSER.parse(policyContent);

    CelPolicyValidationException e =
        assertThrows(
            CelPolicyValidationException.class,
            () -> CelPolicyCompilerFactory.newPolicyCompiler(newCel()).build().compile(policy));

    assertThat(e).hasMessageThat().isEqualTo(testCase.expected);
  }

  @Test
  public void compileYamlPolicy_exceedsDefaultAstDepthLimit_throws() throws Exception {
    Cel cel = newCel().toCelBuilder().addVar("msg", SimpleType.DYN).build();
    String longExpr = "msg.b.c.d.e.f";
    String policyContent =
        String.format(
            "name: deeply_nested_ast\n" + "rule:\n" + "  match:\n" + "    - output: %s", longExpr);
    CelPolicy policy = POLICY_PARSER.parse(policyContent);

    CelPolicyValidationException e =
        assertThrows(
            CelPolicyValidationException.class,
            () ->
                CelPolicyCompilerFactory.newPolicyCompiler(cel)
                    .setAstDepthLimit(5)
                    .build()
                    .compile(policy));

    assertThat(e)
        .hasMessageThat()
        .isEqualTo("ERROR: <input>:-1:0: AST's depth exceeds the configured limit: 5.");
  }

  @Test
  public void compileYamlPolicy_constantFoldingFailure_throwsDuringComposition() throws Exception {
    String policyContent =
        "name: ast_with_div_by_zero\n" //
            + "rule:\n" //
            + "  match:\n" //
            + "    - output: 1 / 0";
    CelPolicy policy = POLICY_PARSER.parse(policyContent);

    CelPolicyValidationException e =
        assertThrows(
            CelPolicyValidationException.class,
            () -> CelPolicyCompilerFactory.newPolicyCompiler(newCel()).build().compile(policy));
    assertThat(e)
        .hasMessageThat()
        .isEqualTo(
            "Failed to optimize the composed policy. Reason: Constant folding failure. Failed to"
                + " evaluate subtree due to: evaluation error: / by zero");
  }

  @Test
  public void compileYamlPolicy_astDepthLimitCheckDisabled_doesNotThrow() throws Exception {
    String longExpr =
        "0+1+2+3+4+5+6+7+8+9+10+11+12+13+14+15+16+17+18+19+20+21+22+23+24+25+26+27+28+29+30+31+32+33+34+35+36+37+38+39+40+41+42+43+44+45+46+47+48+49+50";
    String policyContent =
        String.format(
            "name: deeply_nested_ast\n" + "rule:\n" + "  match:\n" + "    - output: %s", longExpr);
    CelPolicy policy = POLICY_PARSER.parse(policyContent);

    CelAbstractSyntaxTree ast =
        CelPolicyCompilerFactory.newPolicyCompiler(newCel())
            .setAstDepthLimit(-1)
            .build()
            .compile(policy);
    assertThat(ast).isNotNull();
  }

  @Test
  @SuppressWarnings("unchecked")
  public void evaluateYamlPolicy_withCanonicalTestData(
      @TestParameter(valuesProvider = EvaluablePolicyTestDataProvider.class)
          EvaluablePolicyTestData testData)
      throws Exception {
    // Setup
    // Read config and produce an environment to compile policies
    String configSource = testData.yamlPolicy.readConfigYamlContent();
    CelEnvironment celEnvironment = ENVIRONMENT_PARSER.parse(configSource);
    Cel cel = celEnvironment.extend(newCel(), CEL_OPTIONS);
    // Read the policy source
    String policySource = testData.yamlPolicy.readPolicyYamlContent();
    CelPolicy policy = POLICY_PARSER.parse(policySource);
    Object outputObj = testData.testCase.getOutput();
    String exprToCompile;
    if (outputObj instanceof String) {
      exprToCompile = (String) outputObj;
    } else if (outputObj instanceof Map) {
      @SuppressWarnings("unchecked") // Test only
      Map<String, Object> outputMap = (Map<String, Object>) outputObj;
      if (outputMap.containsKey("value")) {
        Object value = outputMap.get("value");
        if (value instanceof String) {
          String escapedValue = ((String) value).replace("\"", "\\\"");
          exprToCompile = "\"" + escapedValue + "\""; // Quote string literals
        } else {
          exprToCompile = String.valueOf(value);
        }
      } else if (outputMap.containsKey("expr")) {
        exprToCompile = (String) outputMap.get("expr");
      } else {
        throw new IllegalArgumentException("Invalid output format: " + outputObj);
      }
    } else {
      throw new IllegalArgumentException("Invalid output format: " + outputObj);
    }
    CelAbstractSyntaxTree expectedOutputAst = cel.compile(exprToCompile).getAst();
    Object expectedOutput = cel.createProgram(expectedOutputAst).eval();

    // Act
    // Compile then evaluate the policy
    CelAbstractSyntaxTree compiledPolicyAst =
        CelPolicyCompilerFactory.newPolicyCompiler(cel).build().compile(policy);
    ImmutableMap.Builder<String, Object> inputBuilder = ImmutableMap.builder();
    for (Map.Entry<String, PolicyTestInput> entry : testData.testCase.getInput().entrySet()) {
      String exprInput = entry.getValue().getExpr();
      if (isNullOrEmpty(exprInput)) {
        inputBuilder.put(entry.getKey(), entry.getValue().getValue());
      } else {
        CelAbstractSyntaxTree exprInputAst = cel.compile(exprInput).getAst();
        inputBuilder.put(entry.getKey(), cel.createProgram(exprInputAst).eval());
      }
    }
    Object evalResult = cel.createProgram(compiledPolicyAst).eval(inputBuilder.buildOrThrow());

    // Assert
    // Note that policies may either produce an optional or a non-optional result,
    // if all the rules included nested ones can always produce a default result when none of the
    // condition matches
    if (testData.yamlPolicy.producesOptionalResult()) {
      Optional<Object> policyOutput = (Optional<Object>) evalResult;
      if (policyOutput.isPresent()) {
        assertThat(policyOutput).hasValue(expectedOutput);
      } else {
        assertThat(policyOutput).isEmpty();
      }
    } else {
      assertThat(evalResult).isEqualTo(expectedOutput);
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  public void evaluateYamlPolicy_nestedRuleProducesOptionalOutput() throws Exception {
    Cel cel = newCel();
    String policySource =
        "name: nested_rule_with_optional_result\n"
            + "rule:\n"
            + "  match:\n"
            + "    - rule:\n"
            + "        match:\n"
            + "          - condition: 'true'\n"
            + "            output: 'optional.of(true)'\n";
    CelPolicy policy = POLICY_PARSER.parse(policySource);
    CelAbstractSyntaxTree compiledPolicyAst =
        CelPolicyCompilerFactory.newPolicyCompiler(cel).build().compile(policy);
    Optional<Object> evalResult = (Optional<Object>) cel.createProgram(compiledPolicyAst).eval();

    // Result is Optional<Object> containing true
    assertThat(evalResult).hasValue(true);
  }

  @Test
  public void evaluateYamlPolicy_lateBoundFunction() throws Exception {
    String configSource =
        "name: late_bound_function_config\n"
            + "functions:\n"
            + "  - name: 'lateBoundFunc'\n"
            + "    overloads:\n"
            + "      - id: 'lateBoundFunc_string'\n"
            + "        args:\n"
            + "          - type_name: 'string'\n"
            + "        return:\n"
            + "          type_name: 'string'\n";
    CelEnvironment celEnvironment = ENVIRONMENT_PARSER.parse(configSource);
    CelBuilder celBuilder = newCel().toCelBuilder();
    if (runtimeFlavor == CelRuntimeFlavor.PLANNER) {
      celBuilder.addLateBoundFunctions("lateBoundFunc");
    }
    Cel cel = celEnvironment.extend(celBuilder.build(), CEL_OPTIONS);

    String policySource =
        "name: late_bound_function_policy\n"
            + "rule:\n"
            + "  match:\n"
            + "   - output: |\n"
            + "       lateBoundFunc('foo')\n";
    CelPolicy policy = POLICY_PARSER.parse(policySource);
    CelAbstractSyntaxTree compiledPolicyAst =
        CelPolicyCompilerFactory.newPolicyCompiler(cel).build().compile(policy);
    String exampleValue = "bar";
    CelLateFunctionBindings lateFunctionBindings =
        CelLateFunctionBindings.from(
            CelFunctionBinding.from(
                "lateBoundFunc_string", String.class, arg -> arg + exampleValue));

    String evalResult =
        (String)
            cel.createProgram(compiledPolicyAst)
                .eval((unused) -> Optional.empty(), lateFunctionBindings);
    assertThat(evalResult).isEqualTo("foo" + exampleValue);
  }

  @Test
  public void evaluateYamlPolicy_withSimpleVariable() throws Exception {
    Cel cel = newCel();
    String policySource =
        "name: shorthand_variables_policy\n"
            + "rule:\n"
            + "  variables:\n"
            + "    - first: 'true'\n"
            + "    - second: 'false'\n"
            + "  match:\n"
            + "    - output: 'variables.first && variables.second'";
    CelPolicyParser parser =
        CelPolicyParserFactory.newYamlParserBuilder().enableSimpleVariables(true).build();
    CelPolicy policy = parser.parse(policySource);

    CelAbstractSyntaxTree compiledPolicyAst =
        CelPolicyCompilerFactory.newPolicyCompiler(cel).build().compile(policy);
    boolean evalResult = (boolean) cel.createProgram(compiledPolicyAst).eval();

    assertThat(evalResult).isFalse();
  }

  @Test
  public void compose_ruleWithNoOutputs_throws() throws Exception {
    Cel cel = newCel();
    CelCompiledRule emptyRule =
        CelCompiledRule.create(
            1L,
            Optional.of(ValueString.of(2L, "empty_rule")),
            ImmutableList.of(),
            ImmutableList.of(),
            cel,
            CelPolicy.EvaluationSemantic.FIRST_MATCH);
    RuleComposer composer = RuleComposer.newInstance(emptyRule, "variables.", 1000);
    CelAbstractSyntaxTree ast = cel.compile("true").getAst();

    IllegalStateException e =
        assertThrows(IllegalStateException.class, () -> composer.optimize(ast, cel));
    assertThat(e).hasMessageThat().isEqualTo("Policy contains no outputs.");
  }

  private static final class EvaluablePolicyTestData {
    private final TestYamlPolicy yamlPolicy;
    private final PolicyTestCase testCase;

    private EvaluablePolicyTestData(TestYamlPolicy yamlPolicy, PolicyTestCase testCase) {
      this.yamlPolicy = yamlPolicy;
      this.testCase = testCase;
    }
  }

  private static final class EvaluablePolicyTestDataProvider extends TestParameterValuesProvider {

    @Override
    protected ImmutableList<TestParameterValue> provideValues(Context context) throws Exception {
      ImmutableList.Builder<TestParameterValue> builder = ImmutableList.builder();
      for (TestYamlPolicy yamlPolicy : TestYamlPolicy.values()) {
        PolicyTestSuite testSuite = yamlPolicy.readTestYamlContent();
        for (PolicyTestSection testSection : testSuite.getSection()) {
          for (PolicyTestCase testCase : testSection.getTests()) {
            String testName =
                String.format(
                    "%s %s %s",
                    yamlPolicy.getPolicyName(), testSection.getName(), testCase.getName());
            builder.add(
                value(new EvaluablePolicyTestData(yamlPolicy, testCase)).withName(testName));
          }
        }
      }

      return builder.build();
    }
  }

  private Cel newCel() {
    return runtimeFlavor
        .builder()
        .setStandardMacros(CelStandardMacro.STANDARD_MACROS)
        .addCompilerLibraries(CelOptionalLibrary.INSTANCE)
        .addRuntimeLibraries(CelOptionalLibrary.INSTANCE)
        .addFileTypes(StandaloneGlobalEnum.getDescriptor().getFile())
        .addMessageTypes(TestAllTypes.getDescriptor(), SingleFile.getDescriptor())
        .setOptions(CEL_OPTIONS)
        .addFunctionBindings(
            CelFunctionBinding.fromOverloads(
                "locationCode",
                CelFunctionBinding.from(
                    "locationCode_string",
                    String.class,
                    (ip) -> {
                      switch (ip) {
                        case "10.0.0.1":
                          return "us";
                        case "10.0.0.2":
                          return "de";
                        default:
                          return "ir";
                      }
                    })))
        .build();
  }

  private enum MultilineErrorTest {
    SINGLE_FOLDED(
        "name: \"errors\"\n"
            + "rule:\n"
            + "  match:\n"
            + "    - output: >\n"
            + "        'test'.format(variables.missing])",
        "ERROR: <input>:5:40: extraneous input ']' expecting ')'\n"
            + " |         'test'.format(variables.missing])\n"
            + " | .......................................^"),
    DOUBLE_FOLDED(
        "name: \"errors\"\n"
            + "rule:\n"
            + "  match:\n"
            + "    - output: >\n"
            + "        'test'.format(\n"
            + "        variables.missing])",
        "ERROR: <input>:6:26: extraneous input ']' expecting ')'\n"
            + " |         variables.missing])\n"
            + " | .........................^"),
    TRIPLE_FOLDED(
        "name: \"errors\"\n"
            + "rule:\n"
            + "  match:\n"
            + "    - output: >\n"
            + "        'test'.\n"
            + "        format(\n"
            + "        variables.missing])",
        "ERROR: <input>:7:26: extraneous input ']' expecting ')'\n"
            + " |         variables.missing])\n"
            + " | .........................^"),
    SINGLE_LITERAL(
        "name: \"errors\"\n"
            + "rule:\n"
            + "  match:\n"
            + "    - output: |\n"
            + "        'test'.format(variables.missing])",
        "ERROR: <input>:5:40: extraneous input ']' expecting ')'\n"
            + " |         'test'.format(variables.missing])\n"
            + " | .......................................^"),
    DOUBLE_LITERAL(
        "name: \"errors\"\n"
            + "rule:\n"
            + "  match:\n"
            + "    - output: |\n"
            + "        'test'.format(\n"
            + "        variables.missing])",
        "ERROR: <input>:6:26: extraneous input ']' expecting ')'\n"
            + " |         variables.missing])\n"
            + " | .........................^"),
    TRIPLE_LITERAL(
        "name: \"errors\"\n"
            + "rule:\n"
            + "  match:\n"
            + "    - output: |\n"
            + "        'test'.\n"
            + "        format(\n"
            + "        variables.missing])",
        "ERROR: <input>:7:26: extraneous input ']' expecting ')'\n"
            + " |         variables.missing])\n"
            + " | .........................^"),
    ;

    private final String yaml;
    private final String expected;

    MultilineErrorTest(String yaml, String expected) {
      this.yaml = yaml;
      this.expected = expected;
    }
  }

  private enum TestErrorYamlPolicy {
    COMPOSE_ERRORS_CONFLICTING_OUTPUT("compose_conflicting_output"),
    COMPOSE_ERRORS_CONFLICTING_SUBRULE("compose_conflicting_subrule"),
    ERRORS_UNREACHABLE("unreachable"),
    DUPLICATE_VARIABLE("duplicate_variable"),
    IMPORT("import"),
    INCOMPATIBLE_OUTPUTS("incompatible_outputs"),
    UNDECLARED_REFERENCE("undeclared_reference");
    // TODO: Re-enable once cel-policy OSS dependency is updated with aggregate
    // testdata.
    // AGGREGATE_ERRORS("aggregate_errors"),
    // AGGREGATE_LIST_ERRORS("aggregate_list_errors"),
    // AGGREGATE_NESTED_MIXED_SEMANTICS("aggregate_nested_mixed_semantics");

    private final String name;
    private final String policyFilePath;

    private String getPolicyFilePath() {
      return policyFilePath;
    }

    private String readPolicyYamlContent() throws IOException {
      return readFromYaml(
          String.format(
              "cel_policy/conformance/testdata/compile_errors/%s/policy.yaml",
              name));
    }

    private Optional<String> readConfigYamlContent() throws IOException {
      String rlocationPath =
          String.format(
              "cel_policy/conformance/testdata/compile_errors/%s/config.yaml",
              name);
      if (PolicyTestHelper.hasRunfile(rlocationPath)) {
        return Optional.of(readFromYaml(rlocationPath));
      }
      return Optional.empty();
    }

    private String readExpectedErrorsBaseline() throws IOException {
      URL url = Resources.getResource(String.format("policy/%s/expected_errors.baseline", name));
      return Resources.toString(url, UTF_8).trim();
    }

    TestErrorYamlPolicy(String name) {
      this.name = name;
      this.policyFilePath = String.format("%s/policy.yaml", name);
    }
  }
}
