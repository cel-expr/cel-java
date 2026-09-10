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

package dev.cel.extensions;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.primitives.UnsignedLong;
import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import com.google.testing.junit.testparameterinjector.TestParameters;
import dev.cel.bundle.Cel;
import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.CelFunctionDecl;
import dev.cel.common.CelOptions;
import dev.cel.common.CelOverloadDecl;
import dev.cel.common.CelValidationException;
import dev.cel.common.types.ListType;
import dev.cel.common.types.SimpleType;
import dev.cel.compiler.CelCompiler;
import dev.cel.compiler.CelCompilerFactory;
import dev.cel.runtime.CelEvaluationException;
import dev.cel.runtime.CelFunctionBinding;
import dev.cel.runtime.CelRuntime;
import dev.cel.runtime.CelRuntimeFactory;
import dev.cel.testing.CelRuntimeFlavor;
import java.util.Map;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(TestParameterInjector.class)
public class CelMathExtensionsTest {
  @TestParameter private CelRuntimeFlavor runtimeFlavor;
  @TestParameter private boolean isParseOnly;

  private Cel cel;

  @Before
  public void setUp() {
    // Legacy runtime does not support parsed-only evaluation mode.
    Assume.assumeFalse(runtimeFlavor.equals(CelRuntimeFlavor.LEGACY) && isParseOnly);
    this.cel =
        runtimeFlavor
            .builder()
            .setOptions(
                CelOptions.current()
                    .enableHeterogeneousNumericComparisons(
                        runtimeFlavor.equals(CelRuntimeFlavor.PLANNER))
                    .build())
            .addCompilerLibraries(CelExtensions.math())
            .addRuntimeLibraries(CelExtensions.math())
            .build();
  }

  @Test
  @TestParameters("{expr: 'math.greatest(-5)', expectedResult: -5}")
  @TestParameters("{expr: 'math.greatest(5)', expectedResult: 5}")
  @TestParameters("{expr: 'math.greatest(1, 1)', expectedResult: 1}")
  @TestParameters("{expr: 'math.greatest(1, 1.0)', expectedResult: 1}")
  @TestParameters("{expr: 'math.greatest(1, 1u)', expectedResult: 1}")
  @TestParameters("{expr: 'math.greatest(-3, 3)', expectedResult: 3}")
  @TestParameters("{expr: 'math.greatest(9, 10)', expectedResult: 10}")
  @TestParameters("{expr: 'math.greatest(15, 14)', expectedResult: 15}")
  @TestParameters(
      "{expr: 'math.greatest(1, 9223372036854775807)', expectedResult: 9223372036854775807}")
  @TestParameters(
      "{expr: 'math.greatest(9223372036854775807, 1)', expectedResult: 9223372036854775807}")
  @TestParameters("{expr: 'math.greatest(-9223372036854775808, 1)', expectedResult: 1}")
  @TestParameters("{expr: 'math.greatest(1, -9223372036854775808)', expectedResult: 1}")
  @TestParameters("{expr: 'math.greatest(1, 1, 1)', expectedResult: 1}")
  @TestParameters("{expr: 'math.greatest(3, 1, 10)', expectedResult: 10}")
  @TestParameters("{expr: 'math.greatest(1, 5, 2)', expectedResult: 5}")
  @TestParameters("{expr: 'math.greatest(-1, 1, 0)', expectedResult: 1}")
  @TestParameters("{expr: 'math.greatest(dyn(1), 1, 1.0)', expectedResult: 1}")
  @TestParameters("{expr: 'math.greatest(5, 1.0, 3u)', expectedResult: 5}")
  @TestParameters("{expr: 'math.greatest(5.4, 10, 3u, -5.0, 3.5)', expectedResult: 10}")
  @TestParameters(
      "{expr: 'math.greatest(5.4, 10, 3u, -5.0, 9223372036854775807)', expectedResult:"
          + " 9223372036854775807}")
  @TestParameters(
      "{expr: 'math.greatest(9223372036854775807, 10, 3u, -5.0, 0)', expectedResult:"
          + " 9223372036854775807}")
  @TestParameters("{expr: 'math.greatest([5.4, 10, 3u, -5.0, 3.5])', expectedResult: 10}")
  @TestParameters(
      "{expr: 'math.greatest([dyn(5.4), dyn(10), dyn(3u), dyn(-5.0), dyn(3.5)])', expectedResult:"
          + " 10}")
  public void greatest_intResult_success(String expr, long expectedResult) throws Exception {
    Object result = eval(expr);

    assertThat(result).isEqualTo(expectedResult);
  }

  @Test
  @TestParameters("{expr: 'math.greatest(-5.0)', expectedResult: -5.0}")
  @TestParameters("{expr: 'math.greatest(5.0)', expectedResult: 5.0}")
  @TestParameters("{expr: 'math.greatest(1.0, 1.0)', expectedResult: 1.0}")
  @TestParameters("{expr: 'math.greatest(1.0, 1)', expectedResult: 1.0}")
  @TestParameters("{expr: 'math.greatest(1.0, 1u)', expectedResult: 1.0}")
  @TestParameters("{expr: 'math.greatest(-3, 3.0)', expectedResult: 3.0}")
  @TestParameters("{expr: 'math.greatest(9, 10.0)', expectedResult: 10.0}")
  @TestParameters("{expr: 'math.greatest(15.0, 14)', expectedResult: 15.0}")
  @TestParameters("{expr: 'math.greatest(14, 15.0)', expectedResult: 15.0}")
  @TestParameters("{expr: 'math.greatest(15.0, 14u)', expectedResult: 15.0}")
  @TestParameters("{expr: 'math.greatest(14u, 15.0)', expectedResult: 15.0}")
  @TestParameters("{expr: 'math.greatest(1, 1.797693e308)', expectedResult: 1.797693e308}")
  @TestParameters("{expr: 'math.greatest(1.797693e308, 1)', expectedResult: 1.797693e308}")
  @TestParameters("{expr: 'math.greatest(-1.797693e308, 1.0)', expectedResult: 1.0}")
  @TestParameters("{expr: 'math.greatest(1.0, -1.797693e308)', expectedResult: 1.0}")
  @TestParameters("{expr: 'math.greatest(1.0, 1.0, 1.0)', expectedResult: 1.0}")
  @TestParameters("{expr: 'math.greatest(3.0, 1.0, 10.0)', expectedResult: 10.0}")
  @TestParameters("{expr: 'math.greatest(1.0, 5.0, 2.0)', expectedResult: 5.0}")
  @TestParameters("{expr: 'math.greatest(-1.0, 1.0, 0)', expectedResult: 1.0}")
  @TestParameters("{expr: 'math.greatest(dyn(1.0), 1.0, 1.0)', expectedResult: 1}")
  @TestParameters("{expr: 'math.greatest(5.0, 1.0, 3u)', expectedResult: 5.0}")
  @TestParameters("{expr: 'math.greatest(5.4, 10.0, 3u, -5.0, 3.5)', expectedResult: 10}")
  @TestParameters(
      "{expr: 'math.greatest(5.4, 10, 3u, -5.0, 1.797693e308)', expectedResult:" + " 1.797693e308}")
  @TestParameters(
      "{expr: 'math.greatest(1.797693e308, 10, 3u, -5.0, 0)', expectedResult:" + " 1.797693e308}")
  @TestParameters("{expr: 'math.greatest([5.4, 10.0, 3u, -5.0, 3.5])', expectedResult: 10}")
  @TestParameters(
      "{expr: 'math.greatest([dyn(5.4), dyn(10.0), dyn(3u), dyn(-5.0), dyn(3.5)])', expectedResult:"
          + " 10.0}")
  public void greatest_doubleResult_success(String expr, double expectedResult) throws Exception {
    Object result = eval(expr);

    assertThat(result).isEqualTo(expectedResult);
  }

  @Test
  @TestParameters("{expr: 'math.greatest(1.0, 1u)', expectedResult: '1.0'}")
  @TestParameters("{expr: 'math.greatest(15.0, 14u)', expectedResult: '15.0'}")
  @TestParameters("{expr: 'math.greatest(14u, 15.0)', expectedResult: '15.0'}")
  @TestParameters("{expr: 'math.greatest(1u, 1.797693e308)', expectedResult: '1.797693e308'}")
  @TestParameters("{expr: 'math.greatest(1.797693e308, 1u)', expectedResult: '1.797693e308'}")
  @TestParameters("{expr: 'math.greatest(-1.797693e308, 1.0)', expectedResult: '1.0'}")
  @TestParameters("{expr: 'math.greatest(5.0, 1.0, 3u)', expectedResult: '5.0'}")
  @TestParameters("{expr: 'math.greatest(5.0, 1.0, dyn(3u))', expectedResult: '5.0'}")
  @TestParameters("{expr: 'math.greatest(5.4, 10.0, 3u, -5.0, 3.5)', expectedResult: '10'}")
  @TestParameters(
      "{expr: 'math.greatest(5.4, 10, 3u, -5.0, 1.797693e308)', expectedResult: '1.797693e308'}")
  @TestParameters(
      "{expr: 'math.greatest(1.797693e308, 10, 3u, -5.0, 0)', expectedResult: '1.797693e308'}")
  @TestParameters("{expr: 'math.greatest([5.4, 10.0, 3u, -5.0, 3.5])', expectedResult: '10'}")
  @TestParameters(
      "{expr: 'math.greatest([dyn(5.4), dyn(10.0), dyn(3u), dyn(-5.0), dyn(3.5)])', expectedResult:"
          + " '10.0'}")
  public void greatest_doubleResult_withUnsignedLongsEnabled_success(
      String expr, double expectedResult) throws Exception {
    CelOptions celOptions = CelOptions.DEFAULT;
    CelCompiler celCompiler =
        CelCompilerFactory.standardCelCompilerBuilder()
            .setOptions(celOptions)
            .addLibraries(CelExtensions.math())
            .build();
    CelRuntime celRuntime =
        CelRuntimeFactory.standardCelRuntimeBuilder()
            .setOptions(celOptions)
            .addLibraries(CelExtensions.math())
            .build();

    CelAbstractSyntaxTree ast = celCompiler.compile(expr).getAst();
    double result = (double) celRuntime.createProgram(ast).eval();

    assertThat(result).isEqualTo(expectedResult);
  }

  @Test
  @TestParameters("{expr: 'math.greatest(5u)', expectedResult: '5'}")
  @TestParameters(
      "{expr: 'math.greatest(18446744073709551615u)', expectedResult: '18446744073709551615'}")
  @TestParameters("{expr: 'math.greatest(1u, 1.0)', expectedResult: '1'}")
  @TestParameters("{expr: 'math.greatest(1u, 1)', expectedResult: '1'}")
  @TestParameters("{expr: 'math.greatest(1u, 1u)', expectedResult: '1'}")
  @TestParameters("{expr: 'math.greatest(3u, 3.0)', expectedResult: '3'}")
  @TestParameters("{expr: 'math.greatest(9u, 10u)', expectedResult: '10'}")
  @TestParameters("{expr: 'math.greatest(15u, 14u)', expectedResult: '15'}")
  @TestParameters(
      "{expr: 'math.greatest(1, 18446744073709551615u)', expectedResult: '18446744073709551615'}")
  @TestParameters(
      "{expr: 'math.greatest(18446744073709551615u, 1)', expectedResult: '18446744073709551615'}")
  @TestParameters("{expr: 'math.greatest(1u, 1, 1)', expectedResult: '1'}")
  @TestParameters("{expr: 'math.greatest(3u, 1u, 10u)', expectedResult: '10'}")
  @TestParameters("{expr: 'math.greatest(1u, 5u, 2u)', expectedResult: '5'}")
  @TestParameters("{expr: 'math.greatest(-1, 1u, 0u)', expectedResult: '1'}")
  @TestParameters("{expr: 'math.greatest(dyn(1u), 1, 1.0)', expectedResult: '1'}")
  @TestParameters("{expr: 'math.greatest(5u, 1.0, 3u)', expectedResult: '5'}")
  @TestParameters("{expr: 'math.greatest(5.4, 10u, 3u, -5.0, 3.5)', expectedResult: '10'}")
  @TestParameters(
      "{expr: 'math.greatest(5.4, 10, 3u, -5.0, 18446744073709551615u)', expectedResult:"
          + " '18446744073709551615'}")
  @TestParameters(
      "{expr: 'math.greatest(18446744073709551615u, 10, 3u, -5.0, 0)', expectedResult:"
          + " '18446744073709551615'}")
  @TestParameters("{expr: 'math.greatest([5.4, 10u, 3, -5.0, 3.5])', expectedResult: '10'}")
  @TestParameters(
      "{expr: 'math.greatest([dyn(5.4), dyn(10u), dyn(3u), dyn(-5.0), dyn(3.5)])', expectedResult:"
          + " '10'}")
  public void greatest_unsignedLongResult_withUnsignedLongType_success(
      String expr, String expectedResult) throws Exception {
    CelOptions celOptions = CelOptions.DEFAULT;
    CelCompiler celCompiler =
        CelCompilerFactory.standardCelCompilerBuilder()
            .setOptions(celOptions)
            .addLibraries(CelExtensions.math())
            .build();
    CelRuntime celRuntime =
        CelRuntimeFactory.standardCelRuntimeBuilder()
            .setOptions(celOptions)
            .addLibraries(CelExtensions.math())
            .build();

    CelAbstractSyntaxTree ast = celCompiler.compile(expr).getAst();
    UnsignedLong result = (UnsignedLong) celRuntime.createProgram(ast).eval();

    assertThat(result).isEqualTo(UnsignedLong.valueOf(expectedResult));
  }

  @Test
  public void greatest_noArgs_throwsCompilationException() {
    Assume.assumeFalse(isParseOnly);
    CelValidationException e =
        assertThrows(CelValidationException.class, () -> cel.compile("math.greatest()").getAst());

    assertThat(e).hasMessageThat().contains("math.greatest() requires at least one argument");
  }

  @Test
  @TestParameters("{expr: 'math.greatest(''test'')'}")
  @TestParameters("{expr: 'math.greatest({})'}")
  @TestParameters("{expr: 'math.greatest([])'}")
  public void greatest_invalidSingleArg_throwsCompilationException(String expr) {
    Assume.assumeFalse(isParseOnly);
    CelValidationException e =
        assertThrows(CelValidationException.class, () -> cel.compile(expr).getAst());

    assertThat(e).hasMessageThat().contains("math.greatest() invalid single argument value");
  }

  @Test
  @TestParameters("{expr: 'math.greatest(1, true)'}")
  @TestParameters("{expr: 'math.greatest(1, 2, ''test'')'}")
  @TestParameters("{expr: 'math.greatest([1, false])'}")
  @TestParameters("{expr: 'math.greatest([1, 2, true])'}")
  @TestParameters("{expr: 'math.greatest([1, {}, 2])'}")
  @TestParameters("{expr: 'math.greatest([1, [], 2])'}")
  public void greatest_invalidArgs_throwsCompilationException(String expr) {
    Assume.assumeFalse(isParseOnly);
    CelValidationException e =
        assertThrows(CelValidationException.class, () -> cel.compile(expr).getAst());

    assertThat(e)
        .hasMessageThat()
        .contains("math.greatest() simple literal arguments must be numeric");
  }

  @Test
  @TestParameters("{expr: 'math.greatest(1, 2, dyn(''test''))'}")
  @TestParameters("{expr: 'math.greatest([1, dyn(false)])'}")
  @TestParameters("{expr: 'math.greatest([1, 2, dyn(true)])'}")
  @TestParameters("{expr: 'math.greatest([1, dyn({}), 2])'}")
  @TestParameters("{expr: 'math.greatest([1, dyn([]), 2])'}")
  public void greatest_invalidDynArgs_throwsRuntimeException(String expr) throws Exception {
    CelEvaluationException e = assertThrows(CelEvaluationException.class, () -> eval(expr));

    assertThat(e).hasMessageThat().contains("failed with arg(s)");
  }

  @Test
  public void greatest_listVariableIsEmpty_throwsRuntimeException() throws Exception {
    CelCompiler celCompiler =
        CelCompilerFactory.standardCelCompilerBuilder()
            .addLibraries(CelExtensions.math())
            .addVar("listVar", ListType.create(SimpleType.INT))
            .build();
    CelAbstractSyntaxTree ast = celCompiler.compile("math.greatest(listVar)").getAst();

    CelEvaluationException e =
        assertThrows(
            CelEvaluationException.class,
            () -> cel.createProgram(ast).eval(ImmutableMap.of("listVar", ImmutableList.of())));

    assertThat(e).hasMessageThat().contains("failed with arg(s)");
    assertThat(e)
        .hasCauseThat()
        .hasMessageThat()
        .contains("math.@max(list) argument must not be empty");
  }

  @Test
  @TestParameters("{expr: '100.greatest(1) == 1'}")
  @TestParameters("{expr: 'dyn(100).greatest(1) == 1'}")
  public void greatest_nonMathNamespace_success(String expr) throws Exception {
    Cel cel =
        runtimeFlavor
            .builder()
            .addCompilerLibraries(CelExtensions.math())
            .addRuntimeLibraries(CelExtensions.math())
            .addFunctionDeclarations(
                CelFunctionDecl.newFunctionDeclaration(
                    "greatest",
                    CelOverloadDecl.newMemberOverload(
                        "int_greatest_int", SimpleType.INT, SimpleType.INT, SimpleType.INT)))
            .addFunctionBindings(
                CelFunctionBinding.fromOverloads(
                    "greatest",
                    CelFunctionBinding.from(
                        "int_greatest_int", Long.class, Long.class, (arg1, arg2) -> arg2)))
            .build();

    boolean result = (boolean) eval(cel, expr);

    assertThat(result).isTrue();
  }

  @Test
  @TestParameters("{expr: 'math.least(-5)', expectedResult: -5}")
  @TestParameters("{expr: 'math.least(5)', expectedResult: 5}")
  @TestParameters("{expr: 'math.least(1, 1)', expectedResult: 1}")
  @TestParameters("{expr: 'math.least(1, 1.0)', expectedResult: 1}")
  @TestParameters("{expr: 'math.least(1, 1u)', expectedResult: 1}")
  @TestParameters("{expr: 'math.least(-3, 3)', expectedResult: -3}")
  @TestParameters("{expr: 'math.least(3, -3)', expectedResult: -3}")
  @TestParameters("{expr: 'math.least(9, 10)', expectedResult: 9}")
  @TestParameters("{expr: 'math.least(15, 14)', expectedResult: 14}")
  @TestParameters(
      "{expr: 'math.least(1, -9223372036854775808)', expectedResult: -9223372036854775808}")
  @TestParameters(
      "{expr: 'math.least(-9223372036854775808, 1)', expectedResult: -9223372036854775808}")
  @TestParameters("{expr: 'math.least(9223372036854775807, 1)', expectedResult: 1}")
  @TestParameters("{expr: 'math.least(1, 9223372036854775807)', expectedResult: 1}")
  @TestParameters("{expr: 'math.least(1, 1, 1)', expectedResult: 1}")
  @TestParameters("{expr: 'math.least(3, -1, 10)', expectedResult: -1}")
  @TestParameters("{expr: 'math.least(1, 5, 2)', expectedResult: 1}")
  @TestParameters("{expr: 'math.least(-1, 1, 0)', expectedResult: -1}")
  @TestParameters("{expr: 'math.least(dyn(1), 1, 1.0)', expectedResult: 1}")
  @TestParameters("{expr: 'math.least(-5, 1.0, 3u)', expectedResult: -5}")
  @TestParameters("{expr: 'math.least(5.4, -10, 3u, -5.0, 3.5)', expectedResult: -10}")
  @TestParameters(
      "{expr: 'math.least(5.4, 10, 3u, -5.0, -9223372036854775808)', expectedResult:"
          + " -9223372036854775808}")
  @TestParameters(
      "{expr: 'math.least(-9223372036854775808, 10, 3u, -5.0, 0)', expectedResult:"
          + " -9223372036854775808}")
  @TestParameters("{expr: 'math.least([5.4, -10, 3u, -5.0, 3.5])', expectedResult: -10}")
  @TestParameters("{expr: 'math.least(1, 9223372036854775807u)', expectedResult: 1}")
  @TestParameters("{expr: 'math.least(9223372036854775807u, 1)', expectedResult: 1}")
  @TestParameters("{expr: 'math.least(9223372036854775807, 10, 3u, 5.0, 0)', expectedResult: 0}")
  @TestParameters(
      "{expr: 'math.least([dyn(5.4), dyn(-10), dyn(3u), dyn(-5.0), dyn(3.5)])', expectedResult:"
          + " -10}")
  public void least_intResult_success(String expr, long expectedResult) throws Exception {
    Object result = eval(expr);

    assertThat(result).isEqualTo(expectedResult);
  }

  @Test
  @TestParameters("{expr: 'math.least(-5.0)', expectedResult: -5.0}")
  @TestParameters("{expr: 'math.least(5.0)', expectedResult: 5.0}")
  @TestParameters("{expr: 'math.least(1.0, 1.0)', expectedResult: 1.0}")
  @TestParameters("{expr: 'math.least(1.0, 1)', expectedResult: 1.0}")
  @TestParameters("{expr: 'math.least(1.0, 1u)', expectedResult: 1.0}")
  @TestParameters("{expr: 'math.least(3, -3.0)', expectedResult: -3.0}")
  @TestParameters("{expr: 'math.least(9, -10.0)', expectedResult: -10.0}")
  @TestParameters("{expr: 'math.least(15, 14.0)', expectedResult: 14.0}")
  @TestParameters("{expr: 'math.least(15, 14.0)', expectedResult: 14.0}")
  @TestParameters("{expr: 'math.least(13.0, 14u)', expectedResult: 13.0}")
  @TestParameters("{expr: 'math.least(14u, 13.0)', expectedResult: 13.0}")
  @TestParameters("{expr: 'math.least(1, -1.797693e308)', expectedResult: -1.797693e308}")
  @TestParameters("{expr: 'math.least(-1.797693e308, 1)', expectedResult: -1.797693e308}")
  @TestParameters("{expr: 'math.least(1.797693e308, 1.0)', expectedResult: 1.0}")
  @TestParameters("{expr: 'math.least(1.0, 1.797693e308)', expectedResult: 1.0}")
  @TestParameters("{expr: 'math.least(1.0, 1.0, 1.0)', expectedResult: 1.0}")
  @TestParameters("{expr: 'math.least(3.0, 1.0, 10.0)', expectedResult: 1.0}")
  @TestParameters("{expr: 'math.least(1.0, 5.0, 2.0)', expectedResult: 1.0}")
  @TestParameters("{expr: 'math.least(-1.0, 1.0, 0)', expectedResult: -1.0}")
  @TestParameters("{expr: 'math.least(dyn(1.0), 1.0, 1.0)', expectedResult: 1.0}")
  @TestParameters("{expr: 'math.least(5.0, 1.0, 3u)', expectedResult: 1.0}")
  @TestParameters("{expr: 'math.least(5.4, 10.0, 3u, -5.0, 3.5)', expectedResult: -5.0}")
  @TestParameters(
      "{expr: 'math.least(5.4, 10, 3u, -5.0, -1.797693e308)', expectedResult: -1.797693e308}")
  @TestParameters(
      "{expr: 'math.least(-1.797693e308, 10, 3u, -5.0, 0)', expectedResult: -1.797693e308}")
  @TestParameters("{expr: 'math.least([5.4, 10.0, 3u, -5.0, 3.5])', expectedResult: -5.0}")
  @TestParameters(
      "{expr: 'math.least([dyn(5.4), dyn(10.0), dyn(3u), dyn(-5.0), dyn(3.5)])', expectedResult:"
          + " -5.0}")
  public void least_doubleResult_success(String expr, double expectedResult) throws Exception {
    Object result = eval(expr);

    assertThat(result).isEqualTo(expectedResult);
  }

  @Test
  @TestParameters("{expr: 'math.least(1.0, 1u)', expectedResult: '1.0'}")
  @TestParameters("{expr: 'math.least(13.0, 14u)', expectedResult: '13.0'}")
  @TestParameters("{expr: 'math.least(14u, 13.0)', expectedResult: '13.0'}")
  @TestParameters("{expr: 'math.least(1u, -1.797693e308)', expectedResult: '-1.797693e308'}")
  @TestParameters("{expr: 'math.least(-1.797693e308, 1u)', expectedResult: '-1.797693e308'}")
  @TestParameters("{expr: 'math.least(1.797693e308, 1.0)', expectedResult: '1.0'}")
  @TestParameters("{expr: 'math.least(5.0, 1.0, 3u)', expectedResult: '1.0'}")
  @TestParameters("{expr: 'math.least(5.0, 1.0, dyn(3u))', expectedResult: '1.0'}")
  @TestParameters("{expr: 'math.least(5.4, 10.0, 3u, -5.0, 3.5)', expectedResult: '-5.0'}")
  @TestParameters(
      "{expr: 'math.least(5.4, 10, 3u, -5.0, -1.797693e308)', expectedResult: '-1.797693e308'}")
  @TestParameters(
      "{expr: 'math.least(-1.797693e308, 10, 3u, -5.0, 0)', expectedResult: '-1.797693e308'}")
  @TestParameters("{expr: 'math.least([5.4, 10.0, 3u, -5.0, 3.5])', expectedResult: '-5.0'}")
  @TestParameters(
      "{expr: 'math.least([dyn(5.4), dyn(10.0), dyn(3u), dyn(-5.0), dyn(3.5)])', expectedResult:"
          + " '-5.0'}")
  public void least_doubleResult_withUnsignedLongsEnabled_success(
      String expr, double expectedResult) throws Exception {
    CelOptions celOptions = CelOptions.current().enableUnsignedLongs(true).build();
    CelCompiler celCompiler =
        CelCompilerFactory.standardCelCompilerBuilder()
            .setOptions(celOptions)
            .addLibraries(CelExtensions.math())
            .build();
    CelRuntime celRuntime =
        CelRuntimeFactory.standardCelRuntimeBuilder()
            .setOptions(celOptions)
            .addLibraries(CelExtensions.math())
            .build();

    CelAbstractSyntaxTree ast = celCompiler.compile(expr).getAst();
    double result = (double) celRuntime.createProgram(ast).eval();

    assertThat(result).isEqualTo(expectedResult);
  }

  @Test
  @TestParameters("{expr: 'math.least(9, 1u, 0u)', expectedResult: '0'}")
  @TestParameters("{expr: 'math.least(dyn(1u), 1, 1.0)', expectedResult: '1'}")
  @TestParameters("{expr: 'math.least(5.0, 1u, 3u)', expectedResult: '1'}")
  @TestParameters("{expr: 'math.least(5.4, 1u, 3u, 9, 3.5)', expectedResult: '1'}")
  @TestParameters(
      "{expr: 'math.least(5.4, 10, 3u, 5.0, 9223372036854775807)', expectedResult: '3'}")
  @TestParameters("{expr: 'math.least([5.4, 10, 3u, 5.0, 3.5])', expectedResult: '3'}")
  @TestParameters(
      "{expr: 'math.least([dyn(5.4), dyn(10), dyn(3u), dyn(5.0), dyn(3.5)])', expectedResult: '3'}")
  @TestParameters(
      "{expr: 'math.least(18446744073709551615u)', expectedResult: '18446744073709551615'}")
  @TestParameters("{expr: 'math.least(1u, 1.0)', expectedResult: '1'}")
  @TestParameters("{expr: 'math.least(1u, 1u)', expectedResult: '1'}")
  @TestParameters("{expr: 'math.least(1u, 1u)', expectedResult: '1'}")
  @TestParameters("{expr: 'math.least(3u, 3.0)', expectedResult: '3'}")
  @TestParameters("{expr: 'math.least(9u, 10u)', expectedResult: '9'}")
  @TestParameters("{expr: 'math.least(15u, 14u)', expectedResult: '14'}")
  @TestParameters("{expr: 'math.least(15, 14u)', expectedResult: '14'}")
  @TestParameters("{expr: 'math.least(14u, 15)', expectedResult: '14'}")
  @TestParameters("{expr: 'math.least(1u, 18446744073709551615u)', expectedResult: '1'}")
  @TestParameters("{expr: 'math.least(18446744073709551615u, 1u)', expectedResult: '1'}")
  @TestParameters("{expr: 'math.least(1u, 1, 1)', expectedResult: '1'}")
  @TestParameters("{expr: 'math.least(3u, 1u, 10u)', expectedResult: '1'}")
  @TestParameters("{expr: 'math.least(1u, 5u, 2u)', expectedResult: '1'}")
  @TestParameters("{expr: 'math.least(4, 1u, 0u)', expectedResult: '0'}")
  @TestParameters("{expr: 'math.least(dyn(1u), 1u, 1.0)', expectedResult: '1'}")
  @TestParameters("{expr: 'math.least(5u, 1.0, 0u)', expectedResult: '0'}")
  @TestParameters("{expr: 'math.least(5.4, 10u, 3u, 5.0, 3.5)', expectedResult: '3'}")
  @TestParameters(
      "{expr: 'math.least(5.4, 10, 3u, 0u, 18446744073709551615u)', expectedResult: '0'}")
  @TestParameters(
      "{expr: 'math.least(18446744073709551615u, 10, 3u, 5.0, 0u)', expectedResult: '0'}")
  @TestParameters("{expr: 'math.least([5.4, 10u, 3u, 5.0, 3.5])', expectedResult: '3'}")
  @TestParameters(
      "{expr: 'math.least([dyn(5.4), dyn(10u), dyn(3u), dyn(5.0), dyn(3.5)])', expectedResult:"
          + " '3'}")
  public void least_unsignedLongResult_withUnsignedLongType_success(
      String expr, String expectedResult) throws Exception {
    CelOptions celOptions = CelOptions.current().build();
    CelCompiler celCompiler =
        CelCompilerFactory.standardCelCompilerBuilder()
            .setOptions(celOptions)
            .addLibraries(CelExtensions.math())
            .build();
    CelRuntime celRuntime =
        CelRuntimeFactory.standardCelRuntimeBuilder()
            .setOptions(celOptions)
            .addLibraries(CelExtensions.math())
            .build();

    CelAbstractSyntaxTree ast = celCompiler.compile(expr).getAst();
    UnsignedLong result = (UnsignedLong) celRuntime.createProgram(ast).eval();

    assertThat(result).isEqualTo(UnsignedLong.valueOf(expectedResult));
  }

  @Test
  public void least_noArgs_throwsCompilationException() {
    Assume.assumeFalse(isParseOnly);
    CelValidationException e =
        assertThrows(CelValidationException.class, () -> cel.compile("math.least()").getAst());

    assertThat(e).hasMessageThat().contains("math.least() requires at least one argument");
  }

  @Test
  @TestParameters("{expr: 'math.least(''test'')'}")
  @TestParameters("{expr: 'math.least({})'}")
  @TestParameters("{expr: 'math.least([])'}")
  public void least_invalidSingleArg_throwsCompilationException(String expr) {
    Assume.assumeFalse(isParseOnly);
    CelValidationException e =
        assertThrows(CelValidationException.class, () -> cel.compile(expr).getAst());

    assertThat(e).hasMessageThat().contains("math.least() invalid single argument value");
  }

  @Test
  @TestParameters("{expr: 'math.least(1, true)'}")
  @TestParameters("{expr: 'math.least(1, 2, ''test'')'}")
  @TestParameters("{expr: 'math.least([1, false])'}")
  @TestParameters("{expr: 'math.least([1, 2, true])'}")
  @TestParameters("{expr: 'math.least([1, {}, 2])'}")
  @TestParameters("{expr: 'math.least([1, [], 2])'}")
  public void least_invalidArgs_throwsCompilationException(String expr) {
    Assume.assumeFalse(isParseOnly);
    CelValidationException e =
        assertThrows(CelValidationException.class, () -> cel.compile(expr).getAst());

    assertThat(e)
        .hasMessageThat()
        .contains("math.least() simple literal arguments must be numeric");
  }

  @Test
  @TestParameters("{expr: 'math.least(1, 2, dyn(''test''))'}")
  @TestParameters("{expr: 'math.least([1, dyn(false)])'}")
  @TestParameters("{expr: 'math.least([1, 2, dyn(true)])'}")
  @TestParameters("{expr: 'math.least([1, dyn({}), 2])'}")
  @TestParameters("{expr: 'math.least([1, dyn([]), 2])'}")
  public void least_invalidDynArgs_throwsRuntimeException(String expr) throws Exception {
    CelEvaluationException e = assertThrows(CelEvaluationException.class, () -> eval(expr));

    assertThat(e).hasMessageThat().contains("failed with arg(s)");
  }

  @Test
  public void least_listVariableIsEmpty_throwsRuntimeException() throws Exception {
    CelCompiler celCompiler =
        CelCompilerFactory.standardCelCompilerBuilder()
            .addLibraries(CelExtensions.math())
            .addVar("listVar", ListType.create(SimpleType.INT))
            .build();
    CelAbstractSyntaxTree ast = celCompiler.compile("math.least(listVar)").getAst();

    CelEvaluationException e =
        assertThrows(
            CelEvaluationException.class,
            () -> cel.createProgram(ast).eval(ImmutableMap.of("listVar", ImmutableList.of())));

    assertThat(e).hasMessageThat().contains("failed with arg(s)");
    assertThat(e)
        .hasCauseThat()
        .hasMessageThat()
        .contains("math.@min(list) argument must not be empty");
  }

  @Test
  @TestParameters("{expr: '100.least(1) == 1'}")
  @TestParameters("{expr: 'dyn(100).least(1) == 1'}")
  public void least_nonMathNamespace_success(String expr) throws Exception {
    Cel cel =
        runtimeFlavor
            .builder()
            .addCompilerLibraries(CelExtensions.math())
            .addRuntimeLibraries(CelExtensions.math())
            .addFunctionDeclarations(
                CelFunctionDecl.newFunctionDeclaration(
                    "least",
                    CelOverloadDecl.newMemberOverload(
                        "int_least", SimpleType.INT, SimpleType.INT, SimpleType.INT)))
            .addFunctionBindings(
                CelFunctionBinding.fromOverloads(
                    "least",
                    CelFunctionBinding.from(
                        "int_least", Long.class, Long.class, (arg1, arg2) -> arg2)))
            .build();

    boolean result = (boolean) eval(cel, expr);

    assertThat(result).isTrue();
  }

  @Test
  @TestParameters("{expr: 'math.isNaN(0.0/0.0)', expectedResult: true}")
  @TestParameters("{expr: 'math.isNaN(1.0/1.0)', expectedResult: false}")
  @TestParameters("{expr: 'math.isNaN(12.031)', expectedResult: false}")
  @TestParameters("{expr: 'math.isNaN(-1.0/0.0)', expectedResult: false}")
  @TestParameters("{expr: 'math.isNaN(math.round(0.0/0.0))', expectedResult: true}")
  @TestParameters("{expr: 'math.isNaN(math.sign(0.0/0.0))', expectedResult: true}")
  @TestParameters("{expr: 'math.isNaN(math.sqrt(-4))', expectedResult: true}")
  public void isNaN_success(String expr, boolean expectedResult) throws Exception {
    CelAbstractSyntaxTree ast = cel.compile(expr).getAst();

    Object result = cel.createProgram(ast).eval();

    assertThat(result).isEqualTo(expectedResult);
  }

  @Test
  @TestParameters("{expr: 'math.isNaN()'}")
  @TestParameters("{expr: 'math.isNaN(1)'}")
  @TestParameters("{expr: 'math.isNaN(9223372036854775807)'}")
  @TestParameters("{expr: 'math.isNaN(1u)'}")
  public void isNaN_invalidArgs_throwsException(String expr) {
    CelValidationException e =
        assertThrows(CelValidationException.class, () -> cel.compile(expr).getAst());

    assertThat(e).hasMessageThat().contains("found no matching overload for 'math.isNaN'");
  }

  @Test
  @TestParameters("{expr: 'math.isFinite(1.0/1.5)', expectedResult: true}")
  @TestParameters("{expr: 'math.isFinite(15312.2121)', expectedResult: true}")
  @TestParameters("{expr: 'math.isFinite(1.0/0.0)', expectedResult: false}")
  @TestParameters("{expr: 'math.isFinite(0.0/0.0)', expectedResult: false}")
  public void isFinite_success(String expr, boolean expectedResult) throws Exception {
    CelAbstractSyntaxTree ast = cel.compile(expr).getAst();

    Object result = cel.createProgram(ast).eval();

    assertThat(result).isEqualTo(expectedResult);
  }

  @Test
  @TestParameters("{expr: 'math.isFinite()'}")
  @TestParameters("{expr: 'math.isFinite(1)'}")
  @TestParameters("{expr: 'math.isFinite(9223372036854775807)'}")
  @TestParameters("{expr: 'math.isFinite(1u)'}")
  public void isFinite_invalidArgs_throwsException(String expr) {
    CelValidationException e =
        assertThrows(CelValidationException.class, () -> cel.compile(expr).getAst());

    assertThat(e).hasMessageThat().contains("found no matching overload for 'math.isFinite'");
  }

  @Test
  @TestParameters("{expr: 'math.isInf(1.0/0.0)', expectedResult: true}")
  @TestParameters("{expr: 'math.isInf(-1.0/0.0)', expectedResult: true}")
  @TestParameters("{expr: 'math.isInf(0.0/0.0)', expectedResult: false}")
  @TestParameters("{expr: 'math.isInf(10.0)', expectedResult: false}")
  public void isInf_success(String expr, boolean expectedResult) throws Exception {
    CelAbstractSyntaxTree ast = cel.compile(expr).getAst();

    Object result = cel.createProgram(ast).eval();

    assertThat(result).isEqualTo(expectedResult);
  }

  @Test
  @TestParameters("{expr: 'math.isInf()'}")
  @TestParameters("{expr: 'math.isInf(1)'}")
  @TestParameters("{expr: 'math.isInf(9223372036854775807)'}")
  @TestParameters("{expr: 'math.isInf(1u)'}")
  public void isInf_invalidArgs_throwsException(String expr) {
    CelValidationException e =
        assertThrows(CelValidationException.class, () -> cel.compile(expr).getAst());

    assertThat(e).hasMessageThat().contains("found no matching overload for 'math.isInf'");
  }

  @Test
  @TestParameters("{expr: 'math.ceil(1.2)' , expectedResult: 2.0}")
  @TestParameters("{expr: 'math.ceil(54.78)' , expectedResult: 55.0}")
  @TestParameters("{expr: 'math.ceil(-2.2)' , expectedResult: -2.0}")
  @TestParameters("{expr: 'math.ceil(20.0)' , expectedResult: 20.0}")
  @TestParameters("{expr: 'math.ceil(0.0/0.0)' , expectedResult: NaN}")
  public void ceil_success(String expr, double expectedResult) throws Exception {
    CelAbstractSyntaxTree ast = cel.compile(expr).getAst();

    Object result = cel.createProgram(ast).eval();

    assertThat(result).isEqualTo(expectedResult);
  }

  @Test
  @TestParameters("{expr: 'math.ceil()'}")
  @TestParameters("{expr: 'math.ceil(1)'}")
  @TestParameters("{expr: 'math.ceil(9223372036854775807)'}")
  @TestParameters("{expr: 'math.ceil(1u)'}")
  public void ceil_invalidArgs_throwsException(String expr) {
    CelValidationException e =
        assertThrows(CelValidationException.class, () -> cel.compile(expr).getAst());

    assertThat(e).hasMessageThat().contains("found no matching overload for 'math.ceil'");
  }

  @Test
  @TestParameters("{expr: 'math.floor(1.2)' , expectedResult: 1.0}")
  @TestParameters("{expr: 'math.floor(-5.2)' , expectedResult: -6.0}")
  @TestParameters("{expr: 'math.floor(0.0/0.0)' , expectedResult: NaN}")
  @TestParameters("{expr: 'math.floor(50.0)' , expectedResult: 50.0}")
  public void floor_success(String expr, double expectedResult) throws Exception {
    CelAbstractSyntaxTree ast = cel.compile(expr).getAst();

    Object result = cel.createProgram(ast).eval();

    assertThat(result).isEqualTo(expectedResult);
  }

  @Test
  @TestParameters("{expr: 'math.floor()'}")
  @TestParameters("{expr: 'math.floor(1)'}")
  @TestParameters("{expr: 'math.floor(9223372036854775807)'}")
  @TestParameters("{expr: 'math.floor(1u)'}")
  public void floor_invalidArgs_throwsException(String expr) {
    CelValidationException e =
        assertThrows(CelValidationException.class, () -> cel.compile(expr).getAst());

    assertThat(e).hasMessageThat().contains("found no matching overload for 'math.floor'");
  }

  @Test
  @TestParameters("{expr: 'math.round(1.2)' , expectedResult: 1.0}")
  @TestParameters("{expr: 'math.round(1.5)' , expectedResult: 2.0}")
  @TestParameters("{expr: 'math.round(-1.5)' , expectedResult: -2.0}")
  @TestParameters("{expr: 'math.round(-1.2)' , expectedResult: -1.0}")
  @TestParameters("{expr: 'math.round(-1.6)' , expectedResult: -2.0}")
  // Discriminating tie cases: confirm "ties round away from zero" (HALF_UP), not
  // banker's rounding (HALF_EVEN).  1.5/-1.5 above don't distingish the two because
  // their nearest-even neighbor (2/-2) is also the away-from-zero neighbor.
  @TestParameters("{expr: 'math.round(0.5)' , expectedResult: 1.0}")
  @TestParameters("{expr: 'math.round(2.5)' , expectedResult: 3.0}")
  @TestParameters("{expr: 'math.round(-0.5)' , expectedResult: -1.0}")
  @TestParameters("{expr: 'math.round(-2.5)' , expectedResult: -3.0}")
  @TestParameters("{expr: 'math.round(0.0/0.0)' , expectedResult: NaN}")
  @TestParameters("{expr: 'math.round(1.0/0.0)' , expectedResult: Infinity}")
  @TestParameters("{expr: 'math.round(-1.0/0.0)' , expectedResult: -Infinity}")
  public void round_success(String expr, double expectedResult) throws Exception {
    CelAbstractSyntaxTree ast = cel.compile(expr).getAst();

    Object result = cel.createProgram(ast).eval();

    assertThat(result).isEqualTo(expectedResult);
  }

  @Test
  @TestParameters("{expr: 'math.round()'}")
  @TestParameters("{expr: 'math.round(1)'}")
  @TestParameters("{expr: 'math.round(9223372036854775807)'}")
  @TestParameters("{expr: 'math.round(1u)'}")
  public void round_invalidArgs_throwsException(String expr) {
    CelValidationException e =
        assertThrows(CelValidationException.class, () -> cel.compile(expr).getAst());

    assertThat(e).hasMessageThat().contains("found no matching overload for 'math.round'");
  }

  @Test
  @TestParameters("{expr: 'math.trunc(-1.3)' , expectedResult: -1.0}")
  @TestParameters("{expr: 'math.trunc(1.3)' , expectedResult: 1.0}")
  @TestParameters("{expr: 'math.trunc(0.0/0.0)' , expectedResult: NaN}")
  @TestParameters("{expr: 'math.trunc(1.0/0.0)' , expectedResult: Infinity}")
  @TestParameters("{expr: 'math.trunc(-1.0/0.0)' , expectedResult: -Infinity}")
  public void trunc_success(String expr, double expectedResult) throws Exception {
    CelAbstractSyntaxTree ast = cel.compile(expr).getAst();

    Object result = cel.createProgram(ast).eval();

    assertThat(result).isEqualTo(expectedResult);
  }

  @Test
  @TestParameters("{expr: 'math.trunc()'}")
  @TestParameters("{expr: 'math.trunc(1)'}")
  @TestParameters("{expr: 'math.trunc()'}")
  @TestParameters("{expr: 'math.trunc(1u)'}")
  public void trunc_invalidArgs_throwsException(String expr) {
    CelValidationException e =
        assertThrows(CelValidationException.class, () -> cel.compile(expr).getAst());

    assertThat(e).hasMessageThat().contains("found no matching overload for 'math.trunc'");
  }

  @Test
  @TestParameters("{expr: 'math.abs(1)',  expectedResult: 1}")
  @TestParameters("{expr: 'math.abs(-1657643)', expectedResult: 1657643}")
  @TestParameters("{expr: 'math.abs(-2147483648)', expectedResult: 2147483648}")
  public void abs_intResult_success(String expr, long expectedResult) throws Exception {
    CelAbstractSyntaxTree ast = cel.compile(expr).getAst();

    Object result = cel.createProgram(ast).eval();

    assertThat(result).isEqualTo(expectedResult);
  }

  @Test
  @TestParameters("{expr: 'math.abs(-234.5)' , expectedResult: 234.5}")
  @TestParameters("{expr: 'math.abs(234.5)' , expectedResult: 234.5}")
  @TestParameters("{expr: 'math.abs(0.0/0.0)' , expectedResult: NaN}")
  @TestParameters("{expr: 'math.abs(-0.0/0.0)' , expectedResult: NaN}")
  @TestParameters("{expr: 'math.abs(1.0/0.0)' , expectedResult: Infinity}")
  @TestParameters("{expr: 'math.abs(-1.0/0.0)' , expectedResult: Infinity}")
  public void abs_doubleResult_success(String expr, double expectedResult) throws Exception {
    CelAbstractSyntaxTree ast = cel.compile(expr).getAst();

    Object result = cel.createProgram(ast).eval();

    assertThat(result).isEqualTo(expectedResult);
  }

  @Test
  public void abs_overflow_throwsException() {
    CelValidationException e =
        assertThrows(
            CelValidationException.class,
            () -> cel.compile("math.abs(-9223372036854775809)").getAst());

    assertThat(e)
        .hasMessageThat()
        .contains("ERROR: <input>:1:10: invalid int literal: -9223372036854775809");
  }

  @Test
  @TestParameters("{expr: 'math.sign(-100)', expectedResult: -1}")
  @TestParameters("{expr: 'math.sign(0)', expectedResult: 0}")
  @TestParameters("{expr: 'math.sign(-0)', expectedResult: 0}")
  @TestParameters("{expr: 'math.sign(11213)', expectedResult: 1}")
  public void sign_intResult_success(String expr, int expectedResult) throws Exception {
    CelAbstractSyntaxTree ast = cel.compile(expr).getAst();

    Object result = cel.createProgram(ast).eval();

    assertThat(result).isEqualTo(expectedResult);
  }

  @Test
  @TestParameters("{expr: 'math.sign(-234.5)' , expectedResult: -1.0}")
  @TestParameters("{expr: 'math.sign(234.5)' , expectedResult: 1.0}")
  @TestParameters("{expr: 'math.sign(0.2321)' , expectedResult: 1.0}")
  @TestParameters("{expr: 'math.sign(0.0)' , expectedResult: 0.0}")
  @TestParameters("{expr: 'math.sign(-0.0)' , expectedResult: 0.0}")
  @TestParameters("{expr: 'math.sign(0.0/0.0)' , expectedResult: NaN}")
  @TestParameters("{expr: 'math.sign(-0.0/0.0)' , expectedResult: NaN}")
  @TestParameters("{expr: 'math.sign(1.0/0.0)' , expectedResult: 1.0}")
  @TestParameters("{expr: 'math.sign(-1.0/0.0)' , expectedResult: -1.0}")
  public void sign_doubleResult_success(String expr, double expectedResult) throws Exception {
    CelAbstractSyntaxTree ast = cel.compile(expr).getAst();

    Object result = cel.createProgram(ast).eval();

    assertThat(result).isEqualTo(expectedResult);
  }

  @Test
  @TestParameters("{expr: 'math.sign()'}")
  @TestParameters("{expr: 'math.sign(\"\")'}")
  public void sign_invalidArgs_throwsException(String expr) {
    CelValidationException e =
        assertThrows(CelValidationException.class, () -> cel.compile(expr).getAst());

    assertThat(e).hasMessageThat().contains("found no matching overload for 'math.sign'");
  }

  @Test
  @TestParameters("{expr: 'math.bitAnd(1,2)' , expectedResult: 0}")
  @TestParameters("{expr: 'math.bitAnd(1,-1)' , expectedResult: 1}")
  @TestParameters(
      "{expr: 'math.bitAnd(9223372036854775807,9223372036854775807)' , expectedResult:"
          + " 9223372036854775807}")
  public void bitAnd_signedInt_success(String expr, long expectedResult) throws Exception {
    CelAbstractSyntaxTree ast = cel.compile(expr).getAst();

    Object result = cel.createProgram(ast).eval();

    assertThat(result).isEqualTo(expectedResult);
  }

  @Test
  @TestParameters("{expr: 'math.bitAnd(1u,2u)' , expectedResult: 0}")
  @TestParameters("{expr: 'math.bitAnd(1u,3u)' , expectedResult: 1}")
  public void bitAnd_unSignedInt_success(String expr, UnsignedLong expectedResult)
      throws Exception {
    CelAbstractSyntaxTree ast = cel.compile(expr).getAst();

    Object result = cel.createProgram(ast).eval();

    assertThat(result).isEqualTo(expectedResult);
  }

  @Test
  @TestParameters("{expr: 'math.bitAnd()'}")
  @TestParameters("{expr: 'math.bitAnd(1u, 1)'}")
  @TestParameters("{expr: 'math.bitAnd(1)'}")
  public void bitAnd_invalidArgs_throwsException(String expr) {
    CelValidationException e =
        assertThrows(CelValidationException.class, () -> cel.compile(expr).getAst());

    assertThat(e).hasMessageThat().contains("found no matching overload for 'math.bitAnd'");
  }

  @Test
  public void bitAnd_maxValArg_throwsException() {
    CelValidationException e =
        assertThrows(
            CelValidationException.class,
            () -> cel.compile("math.bitAnd(9223372036854775807,9223372036854775809)").getAst());

    assertThat(e)
        .hasMessageThat()
        .contains("ERROR: <input>:1:33: invalid int literal: 9223372036854775809");
  }

  @Test
  @TestParameters("{expr: 'math.bitOr(1,2)' , expectedResult: 3}")
  @TestParameters("{expr: 'math.bitOr(1,-1)' , expectedResult: -1}")
  public void bitOr_signedInt_success(String expr, long expectedResult) throws Exception {
    CelAbstractSyntaxTree ast = cel.compile(expr).getAst();

    Object result = cel.createProgram(ast).eval();

    assertThat(result).isEqualTo(expectedResult);
  }

  @Test
  @TestParameters("{expr: 'math.bitOr(1u,2u)' , expectedResult: 3}")
  @TestParameters("{expr: 'math.bitOr(1090u,3u)' , expectedResult: 1091}")
  public void bitOr_unSignedInt_success(String expr, UnsignedLong expectedResult) throws Exception {
    CelAbstractSyntaxTree ast = cel.compile(expr).getAst();

    Object result = cel.createProgram(ast).eval();

    assertThat(result).isEqualTo(expectedResult);
  }

  @Test
  @TestParameters("{expr: 'math.bitOr()'}")
  @TestParameters("{expr: 'math.bitOr(1u, 1)'}")
  @TestParameters("{expr: 'math.bitOr(1)'}")
  public void bitOr_invalidArgs_throwsException(String expr) {
    CelValidationException e =
        assertThrows(CelValidationException.class, () -> cel.compile(expr).getAst());

    assertThat(e).hasMessageThat().contains("found no matching overload for 'math.bitOr'");
  }

  @Test
  @TestParameters("{expr: 'math.bitXor(1,2)' , expectedResult: 3}")
  @TestParameters("{expr: 'math.bitXor(3,5)' , expectedResult: 6}")
  public void bitXor_signedInt_success(String expr, long expectedResult) throws Exception {
    CelAbstractSyntaxTree ast = cel.compile(expr).getAst();

    Object result = cel.createProgram(ast).eval();

    assertThat(result).isEqualTo(expectedResult);
  }

  @Test
  @TestParameters("{expr: 'math.bitXor(1u, 3u)' , expectedResult: 2}")
  @TestParameters("{expr: 'math.bitXor(3u, 5u)' , expectedResult: 6}")
  public void bitXor_unSignedInt_success(String expr, UnsignedLong expectedResult)
      throws Exception {
    CelAbstractSyntaxTree ast = cel.compile(expr).getAst();

    Object result = cel.createProgram(ast).eval();

    assertThat(result).isEqualTo(expectedResult);
  }

  @Test
  @TestParameters("{expr: 'math.bitXor()'}")
  @TestParameters("{expr: 'math.bitXor(1u, 1)'}")
  @TestParameters("{expr: 'math.bitXor(1)'}")
  public void bitXor_invalidArgs_throwsException(String expr) {
    CelValidationException e =
        assertThrows(CelValidationException.class, () -> cel.compile(expr).getAst());

    assertThat(e).hasMessageThat().contains("found no matching overload for 'math.bitXor'");
  }

  @Test
  @TestParameters("{expr: 'math.bitNot(1)' , expectedResult: -2}")
  @TestParameters("{expr: 'math.bitNot(0)' , expectedResult: -1}")
  @TestParameters("{expr: 'math.bitNot(-1)' , expectedResult: 0}")
  public void bitNot_signedInt_success(String expr, long expectedResult) throws Exception {
    CelAbstractSyntaxTree ast = cel.compile(expr).getAst();

    Object result = cel.createProgram(ast).eval();

    assertThat(result).isEqualTo(expectedResult);
  }

  @Test
  @TestParameters("{expr: 'math.bitNot(1u)' , expectedResult: 18446744073709551614}")
  @TestParameters("{expr: 'math.bitNot(12310u)' , expectedResult: 18446744073709539305}")
  public void bitNot_unSignedInt_success(String expr, UnsignedLong expectedResult)
      throws Exception {
    CelAbstractSyntaxTree ast = cel.compile(expr).getAst();

    Object result = cel.createProgram(ast).eval();

    assertThat(result).isEqualTo(expectedResult);
  }

  @Test
  @TestParameters("{expr: 'math.bitNot()'}")
  @TestParameters("{expr: 'math.bitNot(1u, 1)'}")
  @TestParameters("{expr: 'math.bitNot(\"\")'}")
  public void bitNot_invalidArgs_throwsException(String expr) {
    CelValidationException e =
        assertThrows(CelValidationException.class, () -> cel.compile(expr).getAst());

    assertThat(e).hasMessageThat().contains("found no matching overload for 'math.bitNot'");
  }

  @Test
  @TestParameters("{expr: 'math.bitShiftLeft(1, 2)' , expectedResult: 4}")
  @TestParameters("{expr: 'math.bitShiftLeft(12121, 11)' , expectedResult: 24823808}")
  @TestParameters("{expr: 'math.bitShiftLeft(-1, 64)' , expectedResult: 0}")
  public void bitShiftLeft_signedInt_success(String expr, long expectedResult) throws Exception {
    CelAbstractSyntaxTree ast = cel.compile(expr).getAst();

    Object result = cel.createProgram(ast).eval();

    assertThat(result).isEqualTo(expectedResult);
  }

  @Test
  @TestParameters("{expr: 'math.bitShiftLeft(1u, 2)' , expectedResult: 4}")
  @TestParameters("{expr: 'math.bitShiftLeft(2147483648u, 22)' , expectedResult: 9007199254740992}")
  @TestParameters("{expr: 'math.bitShiftLeft(1u, 65)' , expectedResult: 0}")
  public void bitShiftLeft_unSignedInt_success(String expr, UnsignedLong expectedResult)
      throws Exception {
    CelAbstractSyntaxTree ast = cel.compile(expr).getAst();

    Object result = cel.createProgram(ast).eval();

    assertThat(result).isEqualTo(expectedResult);
  }

  @Test
  @TestParameters("{expr: 'math.bitShiftLeft(1, -2)'}")
  @TestParameters("{expr: 'math.bitShiftLeft(1u, -2)'}")
  public void bitShiftLeft_invalidArgs_throwsException(String expr) throws Exception {
    CelAbstractSyntaxTree ast = cel.compile(expr).getAst();

    CelEvaluationException e =
        assertThrows(CelEvaluationException.class, () -> cel.createProgram(ast).eval());

    assertThat(e).hasMessageThat().contains("evaluation error");
    assertThat(e).hasCauseThat().isInstanceOf(IllegalArgumentException.class);
    assertThat(e).hasCauseThat().hasMessageThat().contains("math.bitShiftLeft() negative offset");
  }

  @Test
  @TestParameters(
      "{expr: 'math.bitShiftRight(9223372036854775807, 12)' , expectedResult: 2251799813685247}")
  @TestParameters("{expr: 'math.bitShiftRight(12121, 11)' , expectedResult: 5}")
  @TestParameters("{expr: 'math.bitShiftRight(-1, 64)' , expectedResult: 0}")
  public void bitShiftRight_signedInt_success(String expr, long expectedResult) throws Exception {
    CelAbstractSyntaxTree ast = cel.compile(expr).getAst();

    Object result = cel.createProgram(ast).eval();

    assertThat(result).isEqualTo(expectedResult);
  }

  @Test
  @TestParameters("{expr: 'math.bitShiftRight(23111u, 12)' , expectedResult: 5}")
  @TestParameters("{expr: 'math.bitShiftRight(2147483648u, 22)' , expectedResult: 512}")
  @TestParameters("{expr: 'math.bitShiftRight(1u, 65)' , expectedResult: 0}")
  public void bitShiftRight_unSignedInt_success(String expr, UnsignedLong expectedResult)
      throws Exception {
    Object result = eval(expr);

    assertThat(result).isEqualTo(expectedResult);
  }

  @Test
  @TestParameters("{expr: 'math.bitShiftRight(23111u, -212)'}")
  @TestParameters("{expr: 'math.bitShiftRight(23, -212)'}")
  public void bitShiftRight_invalidArgs_throwsException(String expr) throws Exception {
    CelEvaluationException e = assertThrows(CelEvaluationException.class, () -> eval(expr));

    assertThat(e).hasMessageThat().contains("evaluation error");
    assertThat(e).hasCauseThat().isInstanceOf(IllegalArgumentException.class);
    assertThat(e).hasCauseThat().hasMessageThat().contains("math.bitShiftRight() negative offset");
  }

  @Test
  @TestParameters("{expr: 'math.sqrt(49.0)', expectedResult: 7.0}")
  @TestParameters("{expr: 'math.sqrt(82)', expectedResult: 9.055385138137417}")
  @TestParameters("{expr: 'math.sqrt(25u)', expectedResult: 5.0}")
  @TestParameters("{expr: 'math.sqrt(0.0/0.0)', expectedResult: NaN}")
  @TestParameters("{expr: 'math.sqrt(1.0/0.0)', expectedResult: Infinity}")
  @TestParameters("{expr: 'math.sqrt(-1)', expectedResult: NaN}")
  public void sqrt_success(String expr, double expectedResult) throws Exception {
    Object result = eval(expr);

    assertThat(result).isEqualTo(expectedResult);
  }

  private Object eval(Cel cel, String expression, Map<String, ?> variables) throws Exception {
    CelAbstractSyntaxTree ast;
    if (isParseOnly) {
      ast = cel.parse(expression).getAst();
    } else {
      ast = cel.compile(expression).getAst();
    }
    return cel.createProgram(ast).eval(variables);
  }

  private Object eval(Cel celInstance, String expression) throws Exception {
    return eval(celInstance, expression, ImmutableMap.of());
  }

  private Object eval(String expression) throws Exception {
    return eval(this.cel, expression, ImmutableMap.of());
  }
}
