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

package dev.cel.policy;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import com.google.testing.junit.testparameterinjector.TestParameters;
import dev.cel.bundle.Cel;
import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.CelFunctionDecl;
import dev.cel.common.CelOptions;
import dev.cel.common.CelOverloadDecl;
import dev.cel.common.CelProtoAbstractSyntaxTree;
import dev.cel.common.types.SimpleType;
import dev.cel.common.types.StructTypeReference;
import dev.cel.expr.conformance.proto3.TestAllTypes;
import dev.cel.extensions.CelExtensions;
import dev.cel.extensions.CelOptionalLibrary;
import dev.cel.optimizer.optimizers.ConstantFoldingOptimizer;
import dev.cel.optimizer.optimizers.SelectOptimizer;
import dev.cel.optimizer.optimizers.SelectOptimizer.SelectOptimizerOptions;
import dev.cel.optimizer.optimizers.SubexpressionOptimizer;
import dev.cel.parser.CelStandardMacro;
import dev.cel.parser.CelUnparserFactory;
import dev.cel.runtime.CelFunctionBinding;
import dev.cel.testing.CelRuntimeFlavor;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(TestParameterInjector.class)
public final class CelPolicyDecisionsTest {
  private static final CelPolicyParser POLICY_PARSER =
      CelPolicyParserFactory.newYamlParserBuilder().build();

  // Cheap precondition over the message channel.
  private static final String CHANNEL_POLICY =
      "name: channel\n"
          + "rule:\n"
          + "  match:\n"
          + "  - condition: \"channel == 'sms'\"\n"
          + "    output: \"'SMS'\"\n"
          + "  - output: \"'UNKNOWN'\"\n";

  // Expensive sub-decision backed by an ML model.
  private static final String TEXT_RISK_POLICY =
      "name: text_risk\n"
          + "rule:\n"
          + "  variables:\n"
          + "  - name: score\n"
          + "    expression: \"model(text)\"\n"
          + "  match:\n"
          + "  - condition: \"variables.score > 5\"\n"
          + "    output: \"'HIGH'\"\n"
          + "  - output: \"'LOW'\"\n";

  private static final String WORKFLOW_POLICY =
      "name: workflow\n"
          + "rule:\n"
          + "  match:\n"
          + "  - condition: \"decisions.channel == 'UNKNOWN'\"\n"
          + "    output: \"'IGNORE'\"\n"
          + "  - condition: \"decisions.text_risk == 'HIGH'\"\n"
          + "    output: \"'BLOCK'\"\n"
          + "  - output: \"'ALLOW'\"\n";

  // Static so that the @Immutable function binding does not capture mutable instance state.
  private static final AtomicInteger modelInvocations = new AtomicInteger();

  @TestParameter private CelRuntimeFlavor runtimeFlavor;

  private Cel cel;

  @Before
  public void setUp() {
    modelInvocations.set(0);
    cel =
        runtimeFlavor
            .builder()
            .setStandardMacros(CelStandardMacro.STANDARD_MACROS)
            .addCompilerLibraries(CelOptionalLibrary.INSTANCE, CelExtensions.bindings())
            .addRuntimeLibraries(CelOptionalLibrary.INSTANCE)
            .addMessageTypes(TestAllTypes.getDescriptor())
            .setOptions(
                CelOptions.current()
                    .populateMacroCalls(true)
                    .enableHeterogeneousNumericComparisons(true)
                    .build())
            .addVar("channel", SimpleType.STRING)
            .addVar("text", SimpleType.STRING)
            .addVar("msg", StructTypeReference.create(TestAllTypes.getDescriptor().getFullName()))
            .addFunctionDeclarations(
                CelFunctionDecl.newFunctionDeclaration(
                    "model",
                    CelOverloadDecl.newGlobalOverload(
                        "model_string", SimpleType.INT, SimpleType.STRING)))
            .addFunctionBindings(
                CelFunctionBinding.fromOverloads(
                    "model",
                    CelFunctionBinding.from(
                        "model_string",
                        String.class,
                        text -> {
                          modelInvocations.incrementAndGet();
                          return (long) text.length();
                        })))
            .build();
  }

  @Test
  public void linkedDecision_decisionsBecomeBlockSlots() throws Exception {
    CelAbstractSyntaxTree ast = compileWorkflow();

    assertThat(CelUnparserFactory.newUnparser().unparse(ast))
        .isEqualTo(
            "cel.@block([(channel == \"sms\") ? \"SMS\" : \"UNKNOWN\", (model(text) > 5) ?"
                + " \"HIGH\" : \"LOW\"], (@index0 == \"UNKNOWN\") ? \"IGNORE\" : ((@index1 =="
                + " \"HIGH\") ? \"BLOCK\" : \"ALLOW\"))");
  }

  @Test
  @TestParameters("{channel: email, expected: IGNORE, expectedInvocations: 0}")
  @TestParameters("{channel: sms, expected: BLOCK, expectedInvocations: 1}")
  public void linkedDecision_expensiveDecisionEvaluatedOnlyWhenReached(
      String channel, String expected, int expectedInvocations) throws Exception {
    CelAbstractSyntaxTree ast = compileWorkflow();

    Object result = eval(ast, ImmutableMap.of("channel", channel, "text", "long message"));

    assertThat(result).isEqualTo(expected);
    assertThat(modelInvocations.get()).isEqualTo(expectedInvocations);
  }

  @Test
  public void linkedDecision_diamond_sharedDecisionEvaluatedOnce() throws Exception {
    String smsAbuse =
        "name: sms_abuse\n"
            + "rule:\n"
            + "  match:\n"
            + "  - condition: \"decisions.text_risk == 'HIGH' && channel == 'sms'\"\n"
            + "    output: \"true\"\n"
            + "  - output: \"false\"\n";
    String emailAbuse =
        "name: email_abuse\n"
            + "rule:\n"
            + "  match:\n"
            + "  - condition: \"decisions.text_risk == 'HIGH' && channel == 'email'\"\n"
            + "    output: \"true\"\n"
            + "  - output: \"false\"\n";
    String workflow =
        "name: diamond\n"
            + "rule:\n"
            + "  match:\n"
            + "  - condition: \"decisions.sms_abuse || decisions.email_abuse\"\n"
            + "    output: \"'BLOCK'\"\n"
            + "  - output: \"'ALLOW'\"\n";
    ImmutableMap<String, CelAbstractSyntaxTree> textRisk =
        ImmutableMap.of("decisions.text_risk", compile(TEXT_RISK_POLICY));
    CelAbstractSyntaxTree ast =
        compile(
            workflow,
            ImmutableMap.of(
                "decisions.sms_abuse", compile(smsAbuse, textRisk),
                "decisions.email_abuse", compile(emailAbuse, textRisk)));

    Object result = eval(ast, ImmutableMap.of("channel", "email", "text", "long message"));

    // Both sms_abuse and email_abuse evaluate text_risk, which would run the model twice if the
    // shared decision were linked twice.
    assertThat(result).isEqualTo("BLOCK");
    assertThat(modelInvocations.get()).isEqualTo(1);
  }

  @Test
  public void linkedDecision_optionalAndListOutputs() throws Exception {
    String partial =
        "name: partial\n"
            + "rule:\n"
            + "  match:\n"
            + "  - condition: \"channel == 'sms'\"\n"
            + "    output: \"'SMS'\"\n";
    String aggregate =
        "name: aggregate\n"
            + "rule:\n"
            + "  aggregate:\n"
            + "  - condition: \"channel == 'sms'\"\n"
            + "    output: \"'sms_signal'\"\n"
            + "  - condition: \"size(text) > 3\"\n"
            + "    output: \"'long_text_signal'\"\n";
    String workflow =
        "name: shapes\n"
            + "rule:\n"
            + "  match:\n"
            + "  - condition: \"size(decisions.aggregate) == 2\"\n"
            + "    output: \"decisions.partial.orValue('NONE') + ':' + decisions.aggregate[1]\"\n"
            + "  - output: \"decisions.partial.orValue('NONE')\"\n";
    CelAbstractSyntaxTree ast =
        compile(
            workflow,
            ImmutableMap.of(
                "decisions.partial", compile(partial),
                "decisions.aggregate", compile(aggregate)));

    Object result = eval(ast, ImmutableMap.of("channel", "sms", "text", "long message"));

    assertThat(ast.getResultType()).isEqualTo(SimpleType.STRING);
    assertThat(result).isEqualTo("SMS:long_text_signal");
  }

  @Test
  public void linkedDecision_mangledComprehensionsDoNotCollide() throws Exception {
    String ints =
        "name: ints\n"
            + "rule:\n"
            + "  match:\n"
            + "  - condition: \"[size(text)].exists(x, x > 1) && [size(text)].exists(x, x > 1)\"\n"
            + "    output: \"true\"\n"
            + "  - output: \"false\"\n";
    String strings =
        "name: strings\n"
            + "rule:\n"
            + "  match:\n"
            + "  - condition: \"[channel].exists(x, x == 'sms') && [channel].exists(x, x =="
            + " 'sms')\"\n"
            + "    output: \"true\"\n"
            + "  - output: \"false\"\n";
    String workflow =
        "name: comprehensions\n"
            + "rule:\n"
            + "  match:\n"
            + "  - condition: \"decisions.ints && decisions.strings\"\n"
            + "    output: \"'BOTH'\"\n"
            + "  - output: \"'NOT_BOTH'\"\n";
    CelAbstractSyntaxTree ast =
        compile(
            workflow,
            ImmutableMap.of(
                "decisions.ints", compile(ints), "decisions.strings", compile(strings)));

    Object result = eval(ast, ImmutableMap.of("channel", "sms", "text", "long message"));

    assertThat(result).isEqualTo("BOTH");
  }

  @Test
  public void linkedDecision_referencedWithinShadowingComprehension(
      @TestParameter({
            "['SMS'].exists(channel, channel == decisions.channel)",
            "cel.bind(channel, 'SMS', channel == decisions.channel)"
          })
          String condition)
      throws Exception {
    String workflow =
        "name: shadowing\n"
            + "rule:\n"
            + "  match:\n"
            + "  - condition: \""
            + condition
            + "\"\n"
            + "    output: \"'MATCH'\"\n"
            + "  - output: \"'NO_MATCH'\"\n";
    CelAbstractSyntaxTree ast =
        compile(workflow, ImmutableMap.of("decisions.channel", compile(CHANNEL_POLICY)));

    Object result = eval(ast, ImmutableMap.of("channel", "sms", "text", ""));

    assertThat(result).isEqualTo("MATCH");
  }

  @Test
  public void linkedDecision_unreferencedDecisionIsPruned() throws Exception {
    String policy =
        "name: unreferenced\n"
            + "rule:\n"
            + "  match:\n"
            + "  - condition: \"channel == 'sms'\"\n"
            + "    output: \"'SMS'\"\n"
            + "  - output: \"'OTHER'\"\n";

    CelAbstractSyntaxTree ast =
        compile(policy, ImmutableMap.of("decisions.text_risk", compile(TEXT_RISK_POLICY)));

    assertThat(CelUnparserFactory.newUnparser().unparse(ast))
        .isEqualTo(CelUnparserFactory.newUnparser().unparse(compile(policy)));
  }

  @Test
  public void linkedDecision_mergedSizeExceedsIterationLimit_succeeds() throws Exception {
    String workflow =
        "name: workflow\n"
            + "rule:\n"
            + "  match:\n"
            + "  - output: \"decisions.a || decisions.b || decisions.c\"\n";
    ImmutableMap.Builder<String, CelAbstractSyntaxTree> decisions = ImmutableMap.builder();
    for (String name : ImmutableList.of("a", "b", "c")) {
      decisions.put(
          "decisions." + name,
          compile(
              String.format(
                  "name: %s\nrule:\n  match:\n  - output: \"[text, text, text, text, text, text]"
                      + " == ['%s']\"\n",
                  name, name)));
    }

    // Each decision fits within the limit, but the linked AST does not.
    CelAbstractSyntaxTree ast =
        CelPolicyCompilerFactory.newPolicyCompiler(cel)
            .setIterationLimit(10)
            .addDecisions(decisions.buildOrThrow())
            .build()
            .compile(POLICY_PARSER.parse(workflow));

    assertThat(eval(ast, ImmutableMap.of("channel", "sms", "text", "a"))).isEqualTo(false);
  }

  @Test
  public void linkedDecision_selectOptimizerRunsAfterLinking() throws Exception {
    String fieldPolicy =
        "name: field\n"
            + "rule:\n"
            + "  match:\n"
            + "  - condition: \"msg.single_int64 > 10\"\n"
            + "    output: \"'BIG'\"\n"
            + "  - output: \"'SMALL'\"\n";
    String workflow =
        "name: workflow\n"
            + "rule:\n"
            + "  match:\n"
            + "  - condition: \"decisions.field == 'BIG' && msg.single_string == 'x'\"\n"
            + "    output: \"'BLOCK'\"\n"
            + "  - output: \"'ALLOW'\"\n";

    CelAbstractSyntaxTree ast =
        CelPolicyCompilerFactory.newPolicyCompiler(cel)
            .setOptimizers(
                ImmutableList.of(
                    ConstantFoldingOptimizer.getInstance(),
                    SubexpressionOptimizer.getInstance(),
                    newSelectOptimizer()))
            .addDecisions(ImmutableMap.of("decisions.field", compile(fieldPolicy)))
            .build()
            .compile(POLICY_PARSER.parse(workflow));

    String unparsed = CelUnparserFactory.newUnparser().unparse(ast);
    assertThat(unparsed).contains("\"single_int64\"");
    assertThat(unparsed).contains("\"single_string\"");
    assertThat(unparsed).doesNotContain("msg.single_");
  }

  @Test
  public void linkedDecision_selectOptimizedDecision_throws() throws Exception {
    CelAbstractSyntaxTree selectOptimized =
        CelPolicyCompilerFactory.newPolicyCompiler(cel)
            .setOptimizers(ImmutableList.of(newSelectOptimizer()))
            .build()
            .compile(
                POLICY_PARSER.parse(
                    "name: field\n"
                        + "rule:\n"
                        + "  match:\n"
                        + "  - output: \"msg.single_int64 > 10\"\n"));

    CelPolicyCompiler compiler = newCompiler(ImmutableMap.of("decisions.field", selectOptimized));
    CelPolicy policy =
        POLICY_PARSER.parse("name: workflow\nrule:\n  match:\n  - output: \"decisions.field\"\n");

    CelPolicyValidationException e =
        assertThrows(CelPolicyValidationException.class, () -> compiler.compile(policy));

    assertThat(e).hasMessageThat().contains("undeclared reference to 'cel.@attribute'");
  }

  @Test
  public void linkedDecision_unlinkedTransitiveDecision_throws() throws Exception {
    CelAbstractSyntaxTree referencesB =
        CelPolicyCompilerFactory.newPolicyCompiler(
                cel.toCelBuilder().addVar("decisions.b", SimpleType.BOOL).build())
            .build()
            .compile(
                POLICY_PARSER.parse(
                    "name: a\nrule:\n  match:\n  - output: \"decisions.b && channel == 'sms'\"\n"));

    CelPolicyCompiler compiler =
        newCompiler(
            ImmutableMap.of(
                "decisions.a",
                referencesB,
                "decisions.b",
                compile("name: b\nrule:\n  match:\n  - output: \"true\"\n")));
    CelPolicy policy =
        POLICY_PARSER.parse(
            "name: workflow\nrule:\n  match:\n  - output: \"decisions.a && decisions.b\"\n");

    CelPolicyValidationException e =
        assertThrows(CelPolicyValidationException.class, () -> compiler.compile(policy));

    assertThat(e)
        .hasMessageThat()
        .contains("Decision 'decisions.a' references unlinked decisions [decisions.b]");
  }

  @Test
  public void linkedDecision_environmentMismatch_throws() throws Exception {
    CelAbstractSyntaxTree extraDecision =
        CelPolicyCompilerFactory.newPolicyCompiler(
                cel.toCelBuilder().addVar("extra", SimpleType.INT).build())
            .build()
            .compile(
                POLICY_PARSER.parse("name: extra\nrule:\n  match:\n  - output: \"extra > 1\"\n"));

    CelPolicyCompiler compiler = newCompiler(ImmutableMap.of("decisions.extra", extraDecision));
    CelPolicy policy =
        POLICY_PARSER.parse("name: workflow\nrule:\n  match:\n  - output: \"decisions.extra\"\n");

    CelPolicyValidationException e =
        assertThrows(CelPolicyValidationException.class, () -> compiler.compile(policy));

    assertThat(e).hasMessageThat().contains("undeclared reference to 'extra'");
  }

  @Test
  public void linkedDecision_typeMismatch_throws() throws Exception {
    CelPolicyCompiler compiler =
        newCompiler(ImmutableMap.of("decisions.channel", compile(CHANNEL_POLICY)));
    CelPolicy policy =
        POLICY_PARSER.parse(
            "name: workflow\nrule:\n  match:\n  - output: \"decisions.channel + 1\"\n");

    CelPolicyValidationException e =
        assertThrows(CelPolicyValidationException.class, () -> compiler.compile(policy));

    assertThat(e).hasMessageThat().contains("found no matching overload for '_+_'");
  }

  @Test
  public void addDecisions_invalidName_throws(
      @TestParameter({"channel", "@index0.x", "variables.foo"}) String name) throws Exception {
    CelPolicyCompilerBuilder builder =
        CelPolicyCompilerFactory.newPolicyCompiler(cel)
            .addDecisions(ImmutableMap.of(name, compile(CHANNEL_POLICY)));

    IllegalArgumentException e = assertThrows(IllegalArgumentException.class, builder::build);

    assertThat(e).hasMessageThat().contains("Invalid decision name");
  }

  @Test
  public void addDecisions_uncheckedAst_throws() throws Exception {
    CelPolicyCompilerBuilder builder =
        CelPolicyCompilerFactory.newPolicyCompiler(cel)
            .addDecisions(
                ImmutableMap.of("decisions.parsed", cel.parse("channel == 'sms'").getAst()));

    assertThrows(IllegalArgumentException.class, builder::build);
  }

  @Test
  @SuppressWarnings("deprecation") // Exercises the fail-closed guard for a deprecated option.
  public void addDecisions_namespacedDeclarationsDisabled_throws() throws Exception {
    CelAbstractSyntaxTree decision = compile(CHANNEL_POLICY);
    Cel nonNamespacedCel =
        cel.toCelBuilder()
            .setOptions(
                CelOptions.current()
                    .enableHeterogeneousNumericComparisons(true)
                    .enableNamespacedDeclarations(false)
                    .build())
            .build();

    CelPolicyCompilerBuilder builder =
        CelPolicyCompilerFactory.newPolicyCompiler(nonNamespacedCel)
            .addDecisions(ImmutableMap.of("decisions.channel", decision));

    assertThrows(IllegalArgumentException.class, builder::build);
  }

  private static SelectOptimizer newSelectOptimizer() {
    return SelectOptimizer.newInstance(
        SelectOptimizerOptions.newBuilder().build(),
        ImmutableSet.of(TestAllTypes.getDescriptor().getFile()));
  }

  private CelPolicyCompiler newCompiler(Map<String, CelAbstractSyntaxTree> decisions) {
    return CelPolicyCompilerFactory.newPolicyCompiler(cel).addDecisions(decisions).build();
  }

  private CelAbstractSyntaxTree compileWorkflow() throws Exception {
    return compile(
        WORKFLOW_POLICY,
        ImmutableMap.of(
            "decisions.channel", compile(CHANNEL_POLICY),
            "decisions.text_risk", compile(TEXT_RISK_POLICY)));
  }

  private CelAbstractSyntaxTree compile(String policy) throws Exception {
    return compile(policy, ImmutableMap.of());
  }

  // Round-trips through CheckedExpr to mirror decisions loaded from separately built artifacts.
  private CelAbstractSyntaxTree compile(String policy, Map<String, CelAbstractSyntaxTree> decisions)
      throws Exception {
    CelAbstractSyntaxTree ast = newCompiler(decisions).compile(POLICY_PARSER.parse(policy));
    return CelProtoAbstractSyntaxTree.fromCheckedExpr(
            CelProtoAbstractSyntaxTree.fromCelAst(ast).toCheckedExpr())
        .getAst();
  }

  private Object eval(CelAbstractSyntaxTree ast, Map<String, ?> input) throws Exception {
    return cel.createProgram(ast).eval(input);
  }
}
