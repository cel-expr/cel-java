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

import com.google.common.collect.ImmutableMap;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import com.google.testing.junit.testparameterinjector.TestParameters;
import dev.cel.bundle.Cel;
import dev.cel.bundle.CelFactory;
import dev.cel.checker.CelStandardDeclarations;
import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.CelIssue.Severity;
import dev.cel.common.CelOptions;
import dev.cel.common.CelValidationResult;
import dev.cel.common.internal.ProtoTimeUtils;
import dev.cel.common.types.SimpleType;
import dev.cel.runtime.CelEvaluationException;
import dev.cel.runtime.CelFunctionBinding;
import dev.cel.runtime.CelStandardFunctions;
import dev.cel.validator.CelValidator;
import dev.cel.validator.CelValidatorFactory;
import java.text.ParseException;
import java.time.Instant;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(TestParameterInjector.class)
public class TimestampLiteralValidatorTest {
  private static final CelOptions CEL_OPTIONS = CelOptions.DEFAULT;

  private static final Cel CEL = CelFactory.plannerCelBuilder().setOptions(CEL_OPTIONS).build();

  private static final CelValidator CEL_VALIDATOR =
      CelValidatorFactory.standardCelValidatorBuilder(CEL)
          .addAstValidators(TimestampLiteralValidator.INSTANCE)
          .build();

  @Test
  @TestParameters("{source: timestamp(0)}")
  @TestParameters("{source: timestamp(1624124124)}")
  @TestParameters("{source: timestamp('2021-06-19T17:35:24Z')}")
  @TestParameters("{source: timestamp('1972-01-01T10:00:20.021-05:00')}")
  public void timestamp_validFormat(String source) throws Exception {
    CelAbstractSyntaxTree ast = CEL.compile(source).getAst();

    CelValidationResult result = CEL_VALIDATOR.validate(ast);

    assertThat(result.hasError()).isFalse();
    assertThat(result.getAllIssues()).isEmpty();
    assertThat(CEL.createProgram(ast).eval()).isInstanceOf(Instant.class);
  }

  @Test
  public void timestampsInCallArgument_validFormat() throws Exception {
    String source =
        "string(timestamp(1524124124)) + ':' + string(timestamp('2021-06-19T17:35:24Z'))";
    CelAbstractSyntaxTree ast = CEL.compile(source).getAst();

    CelValidationResult result = CEL_VALIDATOR.validate(ast);

    assertThat(result.hasError()).isFalse();
    assertThat(result.getAllIssues()).isEmpty();
    assertThat(CEL.createProgram(ast).eval()).isInstanceOf(String.class);
  }

  @Test
  public void timestamp_withVariable_noOp() throws Exception {
    Cel cel =
        CelFactory.plannerCelBuilder()
            .addVar("str_var", SimpleType.STRING)
            .setOptions(CEL_OPTIONS)
            .build();
    CelAbstractSyntaxTree ast = cel.compile("timestamp(str_var)").getAst();

    CelValidationResult result = CEL_VALIDATOR.validate(ast);

    // Static AST validation cannot handle variables. This expectedly contains no errors
    assertThat(result.hasError()).isFalse();
    assertThat(result.getAllIssues()).isEmpty();
    // However, the same AST fails on evaluation when a bad variable is passed in.
    CelEvaluationException e =
        assertThrows(
            CelEvaluationException.class,
            () -> CEL.createProgram(ast).eval(ImmutableMap.of("str_var", "bad")));
    assertThat(e)
        .hasMessageThat()
        .contains("evaluation error at <input>:9: Text 'bad' could not be parsed at index 0");
  }

  @Test
  public void timestamp_withFunction_noOp() throws Exception {
    Cel cel =
        CelFactory.plannerCelBuilder()
            .addFunctionDeclarations(
                newFunctionDeclaration(
                    "testFunc",
                    newGlobalOverload("testFuncOverloadId", SimpleType.INT, SimpleType.STRING)))
            .addFunctionBindings(
                CelFunctionBinding.from(
                    "testFuncOverloadId",
                    String.class,
                    stringArg -> {
                      try {
                        return ProtoTimeUtils.parse(stringArg).getSeconds();
                      } catch (ParseException e) {
                        throw new RuntimeException(e);
                      }
                    }))
            .setOptions(CEL_OPTIONS)
            .build();
    CelAbstractSyntaxTree ast = cel.compile("timestamp(testFunc('bad'))").getAst();

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
            "evaluation error at <input>:18: Function 'testFuncOverloadId' failed with arg(s)"
                + " 'bad'");
  }

  @Test
  public void timestamp_invalidFormat() throws Exception {
    CelAbstractSyntaxTree ast = CEL.compile("timestamp('bad')").getAst();

    CelValidationResult result = CEL_VALIDATOR.validate(ast);

    assertThat(result.hasError()).isTrue();
    assertThat(result.getAllIssues()).hasSize(1);
    assertThat(result.getAllIssues().get(0).getSeverity()).isEqualTo(Severity.ERROR);
    assertThat(result.getAllIssues().get(0).toDisplayString(ast.getSource()))
        .isEqualTo(
            "ERROR: <input>:1:11: timestamp validation failed. Reason: evaluation error: Text 'bad'"
                + " could not be parsed at index 0\n"
                + " | timestamp('bad')\n"
                + " | ..........^");
  }

  @Test
  public void timestamp_unexpectedResultType_throws() throws Exception {
    Cel cel =
        CelFactory.plannerCelBuilder()
            .setStandardDeclarations(
                CelStandardDeclarations.newBuilder().includeFunctions().build())
            .setStandardFunctions(CelStandardFunctions.newBuilder().includeFunctions().build())
            .addFunctionDeclarations(
                newFunctionDeclaration(
                    "timestamp",
                    newGlobalOverload("timestamp_overload", SimpleType.TIMESTAMP, SimpleType.INT)))
            .addFunctionBindings(
                CelFunctionBinding.from("timestamp_overload", Long.class, (arg) -> 1))
            .build();
    CelAbstractSyntaxTree ast = cel.compile("timestamp(0)").getAst();
    CelValidator celValidator =
        CelValidatorFactory.standardCelValidatorBuilder(cel)
            .addAstValidators(TimestampLiteralValidator.INSTANCE)
            .build();

    CelValidationResult result = celValidator.validate(ast);

    assertThat(result.hasError()).isTrue();
    assertThat(result.getAllIssues()).hasSize(1);
    assertThat(result.getAllIssues().get(0).getSeverity()).isEqualTo(Severity.ERROR);
    assertThat(result.getAllIssues().get(0).toDisplayString(ast.getSource()))
        .isEqualTo(
            "ERROR: <input>:1:11: timestamp validation failed. Reason: Expected"
                + " java.time.Instant type but got java.lang.Long instead\n"
                + " | timestamp(0)\n"
                + " | ..........^");
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

  @Test
  public void env_withSetResultType_success() throws Exception {
    Cel cel =
        CelFactory.plannerCelBuilder()
            .setOptions(CEL_OPTIONS)
            .setResultType(SimpleType.BOOL)
            .build();
    CelValidator validator =
        CelValidatorFactory.standardCelValidatorBuilder(cel)
            .addAstValidators(TimestampLiteralValidator.INSTANCE)
            .build();
    CelAbstractSyntaxTree ast = cel.compile("timestamp(123) == timestamp(123)").getAst();

    CelValidationResult result = validator.validate(ast);

    assertThat(result.hasError()).isFalse();
    assertThat(result.getAllIssues()).isEmpty();
  }
}
