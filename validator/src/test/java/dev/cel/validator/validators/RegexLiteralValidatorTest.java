// Copyright 2023 Google LLC
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

package dev.cel.validator.validators;

import static com.google.common.truth.Truth.assertThat;
import static dev.cel.common.CelFunctionDecl.newFunctionDeclaration;
import static dev.cel.common.CelOverloadDecl.newGlobalOverload;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import com.google.testing.junit.testparameterinjector.TestParameters;
import dev.cel.bundle.Cel;
import dev.cel.bundle.CelFactory;
import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.CelIssue.Severity;
import dev.cel.common.CelOptions;
import dev.cel.common.CelValidationResult;
import dev.cel.common.types.SimpleType;
import dev.cel.runtime.CelEvaluationException;
import dev.cel.runtime.CelFunctionBinding;
import dev.cel.validator.CelValidator;
import dev.cel.validator.CelValidatorFactory;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(TestParameterInjector.class)
public class RegexLiteralValidatorTest {
  private static final CelOptions CEL_OPTIONS = CelOptions.DEFAULT;

  private static final Cel CEL = CelFactory.plannerCelBuilder().setOptions(CEL_OPTIONS).build();

  private static final CelValidator CEL_VALIDATOR =
      CelValidatorFactory.standardCelValidatorBuilder(CEL)
          .addAstValidators(RegexLiteralValidator.INSTANCE)
          .build();

  @Test
  @TestParameters("{source: 'matches(''hello'', ''h'')'}")
  @TestParameters("{source: 'matches(''hello'', ''nomatch'')'}")
  @TestParameters("{source: 'matches(''hello'', ''el*'')'}")
  @TestParameters("{source: 'matches(''**'', ''el*'')'}")
  public void regex_global_validFormat(String source) throws Exception {
    CelAbstractSyntaxTree ast = CEL.compile(source).getAst();

    CelValidationResult result = CEL_VALIDATOR.validate(ast);

    assertThat(result.hasError()).isFalse();
    assertThat(result.getAllIssues()).isEmpty();
    assertThat(CEL.createProgram(ast).eval()).isInstanceOf(Boolean.class);
  }

  @Test
  @TestParameters("{source: '\"hello\".matches(\"h\")'}")
  @TestParameters("{source: '\"hello\".matches(\"nomatch\")'}")
  @TestParameters("{source: '\"hello\".matches(\"el*\")'}")
  @TestParameters("{source: '\"**\".matches(\"el*\")'}")
  public void regex_receiver_validFormat(String source) throws Exception {
    CelAbstractSyntaxTree ast = CEL.compile(source).getAst();

    CelValidationResult result = CEL_VALIDATOR.validate(ast);

    assertThat(result.hasError()).isFalse();
    assertThat(result.getAllIssues()).isEmpty();
    assertThat(CEL.createProgram(ast).eval()).isInstanceOf(Boolean.class);
  }

  @Test
  public void regex_globalWithVariable_noOp() throws Exception {
    Cel cel = CelFactory.plannerCelBuilder().addVar("str_var", SimpleType.STRING).build();
    CelAbstractSyntaxTree ast = cel.compile("matches('test', str_var)").getAst();

    CelValidationResult result = CEL_VALIDATOR.validate(ast);

    // Static AST validation cannot handle variables. This expectedly contains no errors
    assertThat(result.hasError()).isFalse();
    assertThat(result.getAllIssues()).isEmpty();
    // However, the same AST fails on evaluation when a bad variable is passed in.
    CelEvaluationException e =
        assertThrows(
            CelEvaluationException.class,
            () -> CEL.createProgram(ast).eval(ImmutableMap.of("str_var", "**")));
    assertThat(e)
        .hasMessageThat()
        .contains(
            "evaluation error at <input>:7: error parsing regexp: missing argument to repetition"
                + " operator: `*`");
  }

  @Test
  public void regex_receiverWithVariable_noOp() throws Exception {
    Cel cel = CelFactory.plannerCelBuilder().addVar("str_var", SimpleType.STRING).build();
    CelAbstractSyntaxTree ast = cel.compile("'test'.matches(str_var)").getAst();

    CelValidationResult result = CEL_VALIDATOR.validate(ast);

    // Static AST validation cannot handle variables. This expectedly contains no errors
    assertThat(result.hasError()).isFalse();
    assertThat(result.getAllIssues()).isEmpty();
    // However, the same AST fails on evaluation when a bad variable is passed in.
    CelEvaluationException e =
        assertThrows(
            CelEvaluationException.class,
            () -> CEL.createProgram(ast).eval(ImmutableMap.of("str_var", "**")));
    assertThat(e)
        .hasMessageThat()
        .contains(
            "evaluation error at <input>:14: error parsing regexp: missing argument to repetition"
                + " operator: `*`");
  }

  @Test
  public void regex_globalWithFunction_noOp() throws Exception {
    Cel cel =
        CelFactory.plannerCelBuilder()
            .addFunctionDeclarations(
                newFunctionDeclaration(
                    "testFunc", newGlobalOverload("testFuncOverloadId", SimpleType.STRING)))
            .addFunctionBindings(
                CelFunctionBinding.from("testFuncOverloadId", ImmutableList.of(), (args) -> "**"))
            .build();
    CelAbstractSyntaxTree ast = cel.compile("matches('test', testFunc())").getAst();

    CelValidationResult result = CEL_VALIDATOR.validate(ast);

    // Static AST validation cannot handle functions. This expectedly contains no errors
    assertThat(result.hasError()).isFalse();
    assertThat(result.getAllIssues()).isEmpty();
    CelEvaluationException e =
        assertThrows(CelEvaluationException.class, () -> cel.createProgram(ast).eval());
    // However, the same AST fails on evaluation when the function dispatch fails.
    assertThat(e)
        .hasMessageThat()
        .contains(
            "evaluation error at <input>:7: error parsing regexp: missing argument to repetition"
                + " operator: `*`");
  }

  @Test
  public void regex_receiverWithFunction_noOp() throws Exception {
    Cel cel =
        CelFactory.plannerCelBuilder()
            .addFunctionDeclarations(
                newFunctionDeclaration(
                    "testFunc", newGlobalOverload("testFuncOverloadId", SimpleType.STRING)))
            .addFunctionBindings(
                CelFunctionBinding.from("testFuncOverloadId", ImmutableList.of(), (args) -> "**"))
            .build();
    CelAbstractSyntaxTree ast = cel.compile("'test'.matches(testFunc())").getAst();

    CelValidationResult result = CEL_VALIDATOR.validate(ast);

    // Static AST validation cannot handle functions. This expectedly contains no errors
    assertThat(result.hasError()).isFalse();
    assertThat(result.getAllIssues()).isEmpty();
    CelEvaluationException e =
        assertThrows(CelEvaluationException.class, () -> cel.createProgram(ast).eval());
    // However, the same AST fails on evaluation when the function dispatch fails.
    assertThat(e)
        .hasMessageThat()
        .contains(
            "evaluation error at <input>:14: error parsing regexp: missing argument to repetition"
                + " operator: `*`");
  }

  @Test
  public void regex_global_invalidFormat() throws Exception {
    CelAbstractSyntaxTree ast = CEL.compile("matches('test', '**')").getAst();

    CelValidationResult result = CEL_VALIDATOR.validate(ast);

    assertThat(result.hasError()).isTrue();
    assertThat(result.getAllIssues()).hasSize(1);
    assertThat(result.getAllIssues().get(0).getSeverity()).isEqualTo(Severity.ERROR);
    assertThat(result.getAllIssues().get(0).toDisplayString(ast.getSource()))
        .isEqualTo(
            "ERROR: <input>:1:17: Regex validation failed. Reason: Dangling meta character '*' near"
                + " index 0\n"
                + "**\n"
                + "^\n"
                + " | matches('test', '**')\n"
                + " | ................^");
  }

  @Test
  public void regex_receiver_invalidFormat() throws Exception {
    CelAbstractSyntaxTree ast = CEL.compile("'**'.matches('**')").getAst();

    CelValidationResult result = CEL_VALIDATOR.validate(ast);

    assertThat(result.hasError()).isTrue();
    assertThat(result.getAllIssues()).hasSize(1);
    assertThat(result.getAllIssues().get(0).getSeverity()).isEqualTo(Severity.ERROR);
    assertThat(result.getAllIssues().get(0).toDisplayString(ast.getSource()))
        .isEqualTo(
            "ERROR: <input>:1:14: Regex validation failed. Reason: Dangling meta character '*' near"
                + " index 0\n"
                + "**\n"
                + "^\n"
                + " | '**'.matches('**')\n"
                + " | .............^");
  }

  @Test
  @TestParameters("{source: '{1: 2}'}")
  @TestParameters("{source: '[1,2,3]'}")
  public void parentIsNotCallExpr_doesNotThrow(String source) throws Exception {
    CelAbstractSyntaxTree ast = CEL.compile(source).getAst();

    CelValidationResult result = CEL_VALIDATOR.validate(ast);

    assertThat(result.hasError()).isFalse();
    assertThat(result.getAllIssues()).isEmpty();
  }
}
