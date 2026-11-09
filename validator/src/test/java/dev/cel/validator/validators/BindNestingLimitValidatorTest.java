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

package dev.cel.validator.validators;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import dev.cel.bundle.Cel;
import dev.cel.bundle.CelFactory;
import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.CelValidationException;
import dev.cel.common.CelValidationResult;
import dev.cel.common.ast.CelExpr;
import dev.cel.common.types.OptionalType;
import dev.cel.common.types.SimpleType;
import dev.cel.extensions.CelExtensions;
import dev.cel.parser.CelMacro;
import dev.cel.parser.CelMacroExpander;
import dev.cel.parser.CelMacroExprFactory;
import dev.cel.parser.CelStandardMacro;
import dev.cel.validator.CelValidator;
import dev.cel.validator.CelValidatorFactory;
import java.util.Optional;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(TestParameterInjector.class)
public final class BindNestingLimitValidatorTest {

  private static final Cel CEL =
      CelFactory.plannerCelBuilder()
          .addCompilerLibraries(CelExtensions.optional(), CelExtensions.bindings())
          .setStandardMacros(CelStandardMacro.STANDARD_MACROS)
          .addMacros(
              newCustomMacro(
                  "non_list_iter_range",
                  (f, t, a) -> newComprehension(f, "#unused", f.newMap(), f.newIdentifier("a"))),
              newCustomMacro(
                  "non_empty_iter_range",
                  (f, t, a) ->
                      newComprehension(
                          f, "#unused", f.newList(f.newIntLiteral(1)), f.newIdentifier("a"))),
              newCustomMacro(
                  "custom_iter_var",
                  (f, t, a) -> newComprehension(f, "iter", f.newList(), f.newIdentifier("a"))),
              newCustomMacro(
                  "non_ident_loop_step",
                  (f, t, a) -> newComprehension(f, "#unused", f.newList(), f.newIntLiteral(2))),
              newCustomMacro(
                  "distinct_ident_loop_step",
                  (f, t, a) -> newComprehension(f, "#unused", f.newList(), f.newIdentifier("b"))))
          .addVar("b", SimpleType.INT)
          .addVar("x", OptionalType.create(SimpleType.INT))
          .build();

  private static final CelValidator VALIDATOR_LIMIT_0 = newValidator(0);
  private static final CelValidator VALIDATOR_LIMIT_2 = newValidator(2);

  @Test
  public void newInstance_negativeLimit_throws() {
    assertThrows(IllegalArgumentException.class, () -> BindNestingLimitValidator.newInstance(-1));
  }

  @Test
  public void validate_withinLimit_succeeds(
      @TestParameter({
            "1 + 1",
            "cel.bind(a, 1, a + 1)",
            "cel.bind(a, 1, cel.bind(b, 2, a + b))",
            "cel.bind(a, 1, a) + cel.bind(b, 2, b)",
            "cel.bind(a, 1, cel.bind(b, 2, a + b)) + cel.bind(c, 3, cel.bind(d, 4, c + d))",
            "[1, 2, 3].exists(i, cel.bind(a, i, cel.bind(b, a, a + b) > 0))",
            "cel.bind(a, [1, 2], a.map(i, cel.bind(b, i + 1, [b, b].map(j, j))))",
            "optional.of(1).optMap(a, optional.of(a + 1).optMap(b, b + 1))",
          })
          String expr)
      throws Exception {
    CelAbstractSyntaxTree ast = CEL.compile(expr).getAst();

    CelValidationResult result = VALIDATOR_LIMIT_2.validate(ast);

    assertThat(result.getAllIssues()).isEmpty();
  }

  @Test
  public void validate_exceedsLimit_populatesError(
      @TestParameter({
            "cel.bind(a, 1, cel.bind(b, 2, cel.bind(c, 3, a + b + c)))",
            "cel.bind(a, 1, cel.bind(b, 2, 1 + cel.bind(c, 3, a + b + c)))",
            "cel.bind(c, cel.bind(b, cel.bind(a, 1, a + 1), b + 1), c + 1)",
            "optional.of(1).optMap(a, optional.of(a + 1).optMap(b, optional.of(b + 1).optMap(c, c"
                + " + 1)))",
          })
          String expr)
      throws Exception {
    CelAbstractSyntaxTree ast = CEL.compile(expr).getAst();

    CelValidationResult result = VALIDATOR_LIMIT_2.validate(ast);

    assertThat(result.getAllIssues()).hasSize(1);
    assertThat(result.getErrorString())
        .contains("cel.bind nesting exceeds the configured limit: 2.");
    assertThat(result.getErrorString()).contains("ERROR: <input>:1:");
  }

  @Test
  public void validate_multipleViolations_accumulatesErrors() throws Exception {
    CelAbstractSyntaxTree ast =
        CEL.compile("cel.bind(a, 1, cel.bind(b, 2, cel.bind(c, 3, a + b + c)))").getAst();
    CelValidator validator = newValidator(1);

    CelValidationResult result = validator.validate(ast);

    assertThat(result.getAllIssues()).hasSize(2);
    assertThat(result.getErrorString())
        .isEqualTo(
            "ERROR: <input>:1:24: cel.bind nesting exceeds the configured limit: 1.\n"
                + " | cel.bind(a, 1, cel.bind(b, 2, cel.bind(c, 3, a + b + c)))\n"
                + " | .......................^\n"
                + "ERROR: <input>:1:39: cel.bind nesting exceeds the configured limit: 1.\n"
                + " | cel.bind(a, 1, cel.bind(b, 2, cel.bind(c, 3, a + b + c)))\n"
                + " | ......................................^");
  }

  @Test
  public void validate_zeroLimit_disallowsAllBinds() throws Exception {
    CelAbstractSyntaxTree ast = CEL.compile("cel.bind(a, 1, a + 1)").getAst();

    CelValidationResult result = VALIDATOR_LIMIT_0.validate(ast);

    CelValidationException e = assertThrows(CelValidationException.class, result::getAst);
    assertThat(e).hasMessageThat().contains("cel.bind nesting exceeds the configured limit: 0.");
  }

  @Test
  public void validate_zeroLimit_ignoresNonBindComprehensions(
      @TestParameter({
            "[1, 2].map(y, y + 1)",
            "[].map(y, y + 1)",
            "{}.all(k, k != 0)",
            "{1: 2}.all(k, k != 0)",
            "x.optMap(y, y + 1)",
            "non_list_iter_range()",
            "non_empty_iter_range()",
            "custom_iter_var()",
            "non_ident_loop_step()",
            "distinct_ident_loop_step()",
          })
          String expr)
      throws Exception {
    CelAbstractSyntaxTree ast = CEL.compile(expr).getAst();

    CelValidationResult result = VALIDATOR_LIMIT_0.validate(ast);

    assertThat(result.getAllIssues()).isEmpty();
  }

  private static CelValidator newValidator(int maxNesting) {
    return CelValidatorFactory.standardCelValidatorBuilder(CEL)
        .addAstValidators(BindNestingLimitValidator.newInstance(maxNesting))
        .build();
  }

  private static CelMacro newCustomMacro(String name, CelMacroExpander expander) {
    return CelMacro.newGlobalMacro(name, /* argCount= */ 0, expander);
  }

  private static Optional<CelExpr> newComprehension(
      CelMacroExprFactory f, String iterVar, CelExpr iterRange, CelExpr step) {
    return Optional.of(
        f.fold(
            iterVar,
            iterRange,
            /* accuVar= */ "a",
            /* accuInit= */ f.newIntLiteral(1),
            /* condition= */ f.newBoolLiteral(false),
            step,
            /* result= */ f.newIdentifier("a")));
  }
}
