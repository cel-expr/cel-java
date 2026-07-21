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

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.Iterables;
import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import dev.cel.common.formats.ValueString;
import dev.cel.policy.CelPolicy.Import;
import dev.cel.policy.CelPolicy.Invariant;
import dev.cel.policy.CelPolicy.Variable;
import dev.cel.policy.PolicyTestHelper.TestYamlPolicy;
import dev.cel.policy.testing.K8sTagHandler;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(TestParameterInjector.class)
public final class CelPolicyYamlParserTest {

  private static final CelPolicyParser POLICY_PARSER =
      CelPolicyParserFactory.newYamlParserBuilder().addTagVisitor(new K8sTagHandler()).build();

  @Test
  public void parseYamlPolicy_success(@TestParameter TestYamlPolicy yamlPolicy) throws Exception {
    String policySource = yamlPolicy.readPolicyYamlContent();
    String description = yamlPolicy.getPolicyName();

    CelPolicy policy = POLICY_PARSER.parse(policySource, description);

    assertThat(policy.name().value()).isEqualTo(yamlPolicy.getPolicyName());
    assertThat(policy.policySource().getContent().toString()).isEqualTo(policySource);
    assertThat(policy.policySource().getDescription()).isEqualTo(description);
  }

  @Test
  public void parser_setEmpty() throws Exception {
    assertThrows(CelPolicyValidationException.class, () -> POLICY_PARSER.parse("", ""));
  }

  @Test
  public void parseYamlPolicy_withDescription_atPolicyLevel() throws Exception {
    String policySource =
        "name: 'policy_with_description'\n"
            + "description: 'this is a description of the policy'\n"
            + "rule:\n"
            + "   variables:\n"
            + "   - name: 'variable_with_description'\n"
            + "     description: 'this is a description of the variable'\n"
            + "     expression: 'true'";

    CelPolicy policy = POLICY_PARSER.parse(policySource);

    assertThat(policy.description())
        .hasValue(ValueString.of(5, "this is a description of the policy"));
  }

  @Test
  public void parseYamlPolicy_withDisplayName_atPolicyLevel() throws Exception {
    String policySource =
        "name: 'policy_with_description'\n"
            + "display_name: 'display name of the policy'\n"
            + "rule:\n"
            + "   variables:\n"
            + "   - name: 'variable_with_description'\n"
            + "     description: 'this is a description of the variable'\n"
            + "     expression: 'true'";

    CelPolicy policy = POLICY_PARSER.parse(policySource);

    assertThat(policy.displayName()).hasValue(ValueString.of(5, "display name of the policy"));
  }

  @Test
  public void parseYamlPolicy_withDescription() throws Exception {
    String policySource =
        "rule:\n"
            + "  variables:\n"
            + "  - name: 'variable_with_description'\n"
            + "    description: 'this is a description of the variable'\n"
            + "    expression: 'true'";

    CelPolicy policy = POLICY_PARSER.parse(policySource);

    assertThat(policy.rule().variables()).hasSize(1);
    assertThat(Iterables.getOnlyElement(policy.rule().variables()).description())
        .hasValue(ValueString.of(10, "this is a description of the variable"));
  }

  @Test
  public void parseYamlPolicy_withDescription_foldedStyle() throws Exception {
    String policySource =
        "name: 'policy_name'\n"
            + "description: >-\n"
            + "  this is a multiline string\n"
            + "  that gets folded into a single line";

    CelPolicy policy = POLICY_PARSER.parse(policySource);

    assertThat(policy.description().map(ValueString::value))
        .hasValue("this is a multiline string that gets folded into a single line");
  }

  @Test
  public void parseYamlPolicy_withDisplayName() throws Exception {
    String policySource =
        "rule:\n"
            + "  variables:\n"
            + "  - name: 'variable_with_description'\n"
            + "    display_name: 'Display Name'\n"
            + "    expression: 'true'";

    CelPolicy policy = POLICY_PARSER.parse(policySource);

    assertThat(policy.rule().variables()).hasSize(1);
    assertThat(Iterables.getOnlyElement(policy.rule().variables()).displayName())
        .hasValue(ValueString.of(10, "Display Name"));
  }

  @Test
  public void parseYamlPolicy_withExplanation() throws Exception {
    String policySource =
        "rule:\n"
            + "  match:\n"
            + "  - output: 'true'\n"
            + "    explanation: \"'custom explanation'\"";

    CelPolicy policy = POLICY_PARSER.parse(policySource);

    assertThat(policy.rule().matches()).hasSize(1);
    assertThat(Iterables.getOnlyElement(policy.rule().matches()).explanation())
        .hasValue(ValueString.of(11, "'custom explanation'"));
  }

  @Test
  public void parseYamlPolicy_withImports() throws Exception {
    String policySource =
        "name: 'policy_with_imports'\n"
            + "imports:\n"
            + "- name: foo\n"
            + "- name: >\n"
            + "    bar";

    CelPolicy policy = POLICY_PARSER.parse(policySource);

    assertThat(policy.imports())
        .containsExactly(
            Import.create(8L, ValueString.of(9L, "foo")),
            Import.create(12L, ValueString.of(13L, "bar")))
        .inOrder();
  }

  @Test
  public void parseYamlPolicy_withSimpleVariable_multipleInlinedVariables() {
    String policySource =
        "name: shorthand_variables_policy\n"
            + "rule:\n"
            + "  variables:\n"
            + "    - first: 'true'\n"
            + "      second: 'false'\n"
            + "  match:\n"
            + "    - condition: 'variables.my_var'\n"
            + "      output: 'true'\n";
    CelPolicyParser parser =
        CelPolicyParserFactory.newYamlParserBuilder().enableSimpleVariables(true).build();

    CelPolicyValidationException e =
        assertThrows(CelPolicyValidationException.class, () -> parser.parse(policySource));
    assertThat(e)
        .hasMessageThat()
        .contains(
            "ERROR: <input>:5:7: Only one variable may be defined inline\n"
                + " |       second: 'false'\n"
                + " | ......^");
  }

  @Test
  public void parseYamlPolicy_errors(@TestParameter PolicyParseErrorTestCase testCase) {
    CelPolicyValidationException e =
        assertThrows(
            CelPolicyValidationException.class, () -> POLICY_PARSER.parse(testCase.yamlPolicy));
    assertThat(e).hasMessageThat().isEqualTo(testCase.expectedErrorMessage);
  }

  @Test
  public void policyBuilder_addInvariant() {
    Invariant invariant =
        Invariant.newBuilder(1L)
            .setInvariantId(ValueString.newBuilder().setValue("id").build())
            .setAssertClause(ValueString.newBuilder().setValue("true").build())
            .build();
    CelPolicy policy =
        CelPolicy.newBuilder()
            .setName(ValueString.of(0, "test"))
            .setPolicySource(CelPolicySource.newBuilder("").build())
            .addInvariant(invariant)
            .build();
    assertThat(policy.invariants()).containsExactly(invariant);
  }

  @Test
  public void parseYamlPolicy_invariants_success() throws Exception {
    String policySource =
        "name: 'policy_with_invariants'\n"
            + "verification:\n"
            + "  invariants:\n"
            + "    - id: 'inv_1'\n"
            + "      description: 'invariant description'\n"
            + "      assume: 'true'\n"
            + "      assert: 'rule.result == true'";

    CelPolicy policy = POLICY_PARSER.parse(policySource);

    assertThat(policy.invariants()).hasSize(1);
    Invariant invariant = Iterables.getOnlyElement(policy.invariants());
    assertThat(invariant.invariantId().value()).isEqualTo("inv_1");
    assertThat(invariant.description().get().value()).isEqualTo("invariant description");
    assertThat(invariant.assume().get(0).value()).isEqualTo("true");
    assertThat(invariant.assertClause().get(0).value()).isEqualTo("rule.result == true");
  }

  @Test
  public void parseYamlPolicy_invariants_multiClauseLists_success() throws Exception {
    String policySource =
        "name: 'policy_with_list_invariants'\n"
            + "verification:\n"
            + "  invariants:\n"
            + "    - id: 'inv_multi'\n"
            + "      assume:\n"
            + "        - 'x > 0'\n"
            + "        - 'y > 0'\n"
            + "      assert:\n"
            + "        - 'rule.result == true'\n"
            + "        - 'x + y > 0'";

    CelPolicy policy = POLICY_PARSER.parse(policySource);

    Invariant invariant = Iterables.getOnlyElement(policy.invariants());
    assertThat(invariant.assumeSourceString()).isEqualTo("(x > 0) && (y > 0)");
    assertThat(invariant.assertSourceString()).isEqualTo("(rule.result == true) && (x + y > 0)");
  }

  @Test
  public void parseYamlPolicy_verificationVariables_success() throws Exception {
    String policySource =
        "name: 'policy_with_verification_vars'\n"
            + "rule:\n"
            + "  variables:\n"
            + "    - name: rule_var\n"
            + "      expression: 'true'\n"
            + "verification:\n"
            + "  variables:\n"
            + "    - name: ver_var\n"
            + "      expression: 'rule_var && true'\n"
            + "  invariants:\n"
            + "    - id: 'inv_var'\n"
            + "      assume: 'variables.ver_var'\n"
            + "      assert: 'rule.result == true'";

    CelPolicy policy = POLICY_PARSER.parse(policySource);

    assertThat(policy.verificationVariables()).hasSize(1);
    Variable var = Iterables.getOnlyElement(policy.verificationVariables());
    assertThat(var.name().value()).isEqualTo("ver_var");
    assertThat(var.expression().value()).isEqualTo("rule_var && true");
  }

  @Test
  public void parseYamlPolicy_verificationVariables_duplicateWithRuleVariables_throwsError() {
    String policySource =
        "name: 'policy_with_dup_vars'\n"
            + "rule:\n"
            + "  variables:\n"
            + "    - name: dup_var\n"
            + "      expression: 'true'\n"
            + "verification:\n"
            + "  variables:\n"
            + "    - name: dup_var\n"
            + "      expression: 'false'\n"
            + "  invariants:\n"
            + "    - id: 'inv_dup'\n"
            + "      assume: 'variables.dup_var'\n"
            + "      assert: 'rule.result == true'";

    CelPolicyValidationException e =
        assertThrows(CelPolicyValidationException.class, () -> POLICY_PARSER.parse(policySource));
    assertThat(e)
        .hasMessageThat()
        .contains(
            "Duplicate variable name 'dup_var' in verification.variables; already defined in"
                + " rule.variables");
  }

  private enum PolicyParseErrorTestCase {
    MALFORMED_YAML_DOCUMENT(
        "a:\na",
        "YAML document is malformed: while scanning a simple key\n"
            + " in 'reader', line 2, column 1:\n"
            + "    a\n"
            + "    ^\n"
            + "could not find expected ':'\n"
            + " in 'reader', line 2, column 2:\n"
            + "    a\n"
            + "     ^\n"),
    ILLEGAL_YAML_TYPE_POLICY_KEY(
        "1: test",
        "ERROR: <input>:1:1: Got yaml node type tag:yaml.org,2002:int, wanted type(s)"
            + " [tag:yaml.org,2002:str !txt]\n"
            + " | 1: test\n"
            + " | ^"),
    ILLEGAL_YAML_TYPE_ON_NAME_VALUE(
        "name: \n" + "  illegal: yaml-type",
        "ERROR: <input>:2:3: Got yaml node type tag:yaml.org,2002:map, wanted type(s)"
            + " [tag:yaml.org,2002:str !txt]\n"
            + " |   illegal: yaml-type\n"
            + " | ..^"),
    ILLEGAL_YAML_TYPE_ON_RULE_VALUE(
        "rule: illegal",
        "ERROR: <input>:1:7: Got yaml node type tag:yaml.org,2002:str, wanted type(s)"
            + " [tag:yaml.org,2002:map]\n"
            + " | rule: illegal\n"
            + " | ......^"),
    ILLEGAL_YAML_TYPE_ON_RULE_MAP_KEY(
        "rule: \n" + "  1: foo",
        "ERROR: <input>:2:3: Got yaml node type tag:yaml.org,2002:int, wanted type(s)"
            + " [tag:yaml.org,2002:str !txt]\n"
            + " |   1: foo\n"
            + " | ..^"),
    ILLEGAL_YAML_TYPE_ON_MATCHES_VALUE(
        "rule:\n" + "  match: illegal\n",
        "ERROR: <input>:2:10: Got yaml node type tag:yaml.org,2002:str, wanted type(s)"
            + " [tag:yaml.org,2002:seq]\n"
            + " |   match: illegal\n"
            + " | .........^"),
    ILLEGAL_YAML_TYPE_ON_MATCHES_LIST(
        "rule:\n" + "  match:\n" + "    - illegal",
        "ERROR: <input>:3:7: Got yaml node type tag:yaml.org,2002:str, wanted type(s)"
            + " [tag:yaml.org,2002:map]\n"
            + " |     - illegal\n"
            + " | ......^"),
    ILLEGAL_YAML_TYPE_ON_MATCH_MAP_KEY(
        "rule:\n" + "  match:\n" + "    - 1 : foo\n" + "      output: 'hi'",
        "ERROR: <input>:3:7: Got yaml node type tag:yaml.org,2002:int, wanted type(s)"
            + " [tag:yaml.org,2002:str !txt]\n"
            + " |     - 1 : foo\n"
            + " | ......^"),
    ILLEGAL_YAML_TYPE_ON_VARIABLE_VALUE(
        "rule:\n" + "  variables: illegal\n",
        "ERROR: <input>:2:14: Got yaml node type tag:yaml.org,2002:str, wanted type(s)"
            + " [tag:yaml.org,2002:seq]\n"
            + " |   variables: illegal\n"
            + " | .............^"),
    ILLEGAL_YAML_TYPE_ON_VARIABLE_MAP_KEY(
        "rule:\n" + "  variables:\n" + "    - illegal",
        "ERROR: <input>:3:7: Got yaml node type tag:yaml.org,2002:str, wanted type(s)"
            + " [tag:yaml.org,2002:map]\n"
            + " |     - illegal\n"
            + " | ......^"),
    MULTIPLE_YAML_DOCS(
        "name: foo\n" + "---\n" + "name: bar",
        "YAML document is malformed: expected a single document in the stream\n"
            + " in 'reader', line 1, column 1:\n"
            + "    name: foo\n"
            + "    ^\n"
            + "but found another document\n"
            + " in 'reader', line 2, column 1:\n"
            + "    ---\n"
            + "    ^\n"),
    UNSUPPORTED_RULE_TAG(
        "rule:\n" + "  custom: yaml-type",
        "ERROR: <input>:2:3: Unsupported rule tag: custom\n"
            + " |   custom: yaml-type\n"
            + " | ..^"),
    UNSUPPORTED_POLICY_TAG(
        "inputs:\n" + "  - name: a\n" + "  - name: b",
        "ERROR: <input>:1:1: Unsupported policy tag: inputs\n" + " | inputs:\n" + " | ^"),
    UNSUPPORTED_VARIABLE_TAG(
        "rule:\n" //
            + "  variables:\n" //
            + "    - name: 'hello'\n" //
            + "      expression: 'true'\n" //
            + "      alt_name: 'bool_true'",
        "ERROR: <input>:5:7: Unsupported variable tag: alt_name\n"
            + " |       alt_name: 'bool_true'\n"
            + " | ......^"),
    MISSING_VARIABLE_NAME(
        "rule:\n" //
            + "  variables:\n" //
            + "    - expression: 'true'", //
        "ERROR: <input>:3:7: Missing required attribute(s): name\n"
            + " |     - expression: 'true'\n"
            + " | ......^"),
    MISSING_VARIABLE_EXPRESSION(
        "rule:\n" //
            + "  variables:\n" //
            + "    - name: 'hello'", //
        "ERROR: <input>:3:7: Missing required attribute(s): expression\n"
            + " |     - name: 'hello'\n"
            + " | ......^"),
    UNSUPPORTED_MATCH_TAG(
        "rule:\n"
            + "  match:\n"
            + "    - name: 'true'\n"
            + "      output: 'hi'\n"
            + "      alt_name: 'bool_true'",
        "ERROR: <input>:3:7: Unsupported match tag: name\n"
            + " |     - name: 'true'\n"
            + " | ......^\n"
            + "ERROR: <input>:5:7: Unsupported match tag: alt_name\n"
            + " |       alt_name: 'bool_true'\n"
            + " | ......^"),
    MATCH_MISSING_OUTPUT_AND_RULE(
        "rule:\n" //
            + "  match:\n" //
            + "    - condition: 'true'",
        "ERROR: <input>:3:7: Missing required attribute(s): output or a rule\n"
            + " |     - condition: 'true'\n"
            + " | ......^"),
    MATCH_OUTPUT_SET_THEN_RULE(
        "rule:\n"
            + "  match:\n"
            + "    - condition: \"true\"\n"
            + "      output: \"world\"\n"
            + "      rule:\n"
            + "        match:\n"
            + "          - output: \"hello\"",
        "ERROR: <input>:5:7: Only the rule or the output may be set\n"
            + " |       rule:\n"
            + " | ......^"),
    MATCH_RULE_SET_THEN_OUTPUT(
        "rule:\n"
            + "  match:\n"
            + "    - condition: \"true\"\n"
            + "      rule:\n"
            + "        match:\n"
            + "          - output: \"hello\"\n"
            + "      output: \"world\"",
        "ERROR: <input>:7:7: Only the rule or the output may be set\n"
            + " |       output: \"world\"\n"
            + " | ......^"),
    MATCH_NESTED_RULE_SET_THEN_EXPLANATION(
        "rule:\n"
            + "  match:\n"
            + "    - condition: \"true\"\n"
            + "      rule:\n"
            + "        match:\n"
            + "          - output: \"hello\"\n"
            + "      explanation: \"foo\"",
        "ERROR: <input>:7:7: Explanation can only be set on output match cases, not nested rules\n"
            + " |       explanation: \"foo\"\n"
            + " | ......^"),
    MATCH_EXPLANATION_SET_THEN_NESTED_RULE(
        "rule:\n"
            + "  match:\n"
            + "    - condition: \"true\"\n"
            + "      explanation: \"foo\"\n"
            + "      rule:\n"
            + "        match:\n"
            + "          - output: \"hello\"\n",
        "ERROR: <input>:4:21: Explanation can only be set on output match cases, not nested rules\n"
            + " |       explanation: \"foo\"\n"
            + " | ....................^"),
    INVALID_ROOT_NODE_TYPE(
        "- rule:\n" + "    id: a",
        "ERROR: <input>:1:1: Got yaml node type tag:yaml.org,2002:seq, wanted type(s)"
            + " [tag:yaml.org,2002:map]\n"
            + " | - rule:\n"
            + " | ^"),
    ILLEGAL_RULE_DESCRIPTION_TYPE(
        "rule:\n" + "  description: 1",
        "ERROR: <input>:2:16: Got yaml node type tag:yaml.org,2002:int, wanted type(s)"
            + " [tag:yaml.org,2002:str !txt]\n"
            + " |   description: 1\n"
            + " | ...............^"),
    ILLEGAL_YAML_TYPE_IMPORT_EXPECTED_LIST(
        "imports: foo",
        "ERROR: <input>:1:10: Got yaml node type tag:yaml.org,2002:str, wanted type(s)"
            + " [tag:yaml.org,2002:seq]\n"
            + " | imports: foo\n"
            + " | .........^"),
    ILLEGAL_YAML_TYPE_IMPORT_ELEMENT_EXPECTED_MAP(
        "imports:\n" //
            + "- foo",
        "ERROR: <input>:2:3: Got yaml node type tag:yaml.org,2002:str, wanted type(s)"
            + " [tag:yaml.org,2002:map]\n"
            + " | - foo\n"
            + " | ..^"),
    ILLEGAL_YAML_TYPE_IMPORT_ELEMENT_MAP_INVALID_KEY(
        "imports:\n" //
            + "- 1: 2",
        "ERROR: <input>:2:3: Got yaml node type tag:yaml.org,2002:int, wanted type(s)"
            + " [tag:yaml.org,2002:str !txt]\n"
            + " | - 1: 2\n"
            + " | ..^"),
    ILLEGAL_YAML_TYPE_IMPORT_ELEMENT_MAP_INVALID_VALUE_NAME(
        "imports:\n" //
            + "- foo: bar",
        "ERROR: <input>:2:3: Invalid import key: foo, expected 'name'\n"
            + " | - foo: bar\n"
            + " | ..^"),
    UNSUPPORTED_VERIFICATION_TAG(
        "verification:\n" //
            + "  bad_key: true",
        "ERROR: <input>:2:3: Unexpected key in verification block: bad_key\n"
            + " |   bad_key: true\n"
            + " | ..^"),
    UNSUPPORTED_INVARIANT_TAG(
        "verification:\n" //
            + "  invariants:\n" //
            + "    - id: foo\n" //
            + "      bad_inv_key: true\n" //
            + "      assert: 'true'",
        "ERROR: <input>:4:7: Unexpected key in invariant block: bad_inv_key\n"
            + " |       bad_inv_key: true\n"
            + " | ......^"),
    MISSING_INVARIANT_ID(
        "verification:\n" //
            + "  invariants:\n" //
            + "    - assert: 'true'",
        "ERROR: <input>:3:7: Missing required attribute(s): id\n"
            + " |     - assert: 'true'\n"
            + " | ......^"),
    MISSING_INVARIANT_ASSERT(
        "verification:\n" //
            + "  invariants:\n" //
            + "    - id: foo",
        "ERROR: <input>:3:7: Missing required attribute(s): assert\n"
            + " |     - id: foo\n"
            + " | ......^"),
    ILLEGAL_YAML_TYPE_ON_VERIFICATION_VALUE(
        "verification: illegal\n",
        "ERROR: <input>:1:15: Got yaml node type tag:yaml.org,2002:str, wanted type(s)"
            + " [tag:yaml.org,2002:map]\n"
            + " | verification: illegal\n"
            + " | ..............^"),
    ILLEGAL_YAML_TYPE_ON_VERIFICATION_MAP_KEY(
        "verification:\n" + "  1: foo",
        "ERROR: <input>:2:3: Got yaml node type tag:yaml.org,2002:int, wanted type(s)"
            + " [tag:yaml.org,2002:str !txt]\n"
            + " |   1: foo\n"
            + " | ..^"),
    ILLEGAL_YAML_TYPE_ON_INVARIANTS_VALUE(
        "verification:\n" + "  invariants: illegal\n",
        "ERROR: <input>:2:15: Got yaml node type tag:yaml.org,2002:str, wanted type(s)"
            + " [tag:yaml.org,2002:seq]\n"
            + " |   invariants: illegal\n"
            + " | ..............^"),
    ILLEGAL_YAML_TYPE_ON_INVARIANTS_LIST(
        "verification:\n" + "  invariants:\n" + "    - illegal",
        "ERROR: <input>:3:7: Got yaml node type tag:yaml.org,2002:str, wanted type(s)"
            + " [tag:yaml.org,2002:map]\n"
            + " |     - illegal\n"
            + " | ......^"),
    ILLEGAL_YAML_TYPE_ON_INVARIANT_MAP_KEY(
        "verification:\n"
            + "  invariants:\n"
            + "    - 1: foo\n"
            + "      id: 'hi'\n"
            + "      assert: 'true'",
        "ERROR: <input>:3:7: Got yaml node type tag:yaml.org,2002:int, wanted type(s)"
            + " [tag:yaml.org,2002:str !txt]\n"
            + " |     - 1: foo\n"
            + " | ......^");

    private final String yamlPolicy;
    private final String expectedErrorMessage;

    PolicyParseErrorTestCase(String yamlPolicy, String expectedErrorMessage) {
      this.yamlPolicy = yamlPolicy;
      this.expectedErrorMessage = expectedErrorMessage;
    }
  }
}
