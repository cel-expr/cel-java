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
package dev.cel.extensions;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMultiset;
import com.google.common.collect.ImmutableSortedSet;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import com.google.testing.junit.testparameterinjector.TestParameters;
import dev.cel.bundle.Cel;
import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.CelContainer;
import dev.cel.common.CelValidationException;
import dev.cel.common.CelValidationResult;
import dev.cel.common.types.SimpleType;
import dev.cel.expr.conformance.test.SimpleTest;
import dev.cel.parser.CelStandardMacro;
import dev.cel.runtime.CelEvaluationException;
import dev.cel.testing.CelRuntimeFlavor;
import dev.cel.validator.CelValidator;
import dev.cel.validator.CelValidatorFactory;
import dev.cel.validator.validators.HomogeneousLiteralValidator;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(TestParameterInjector.class)
public class CelListsExtensionsTest extends CelExtensionTestBase {

  @Override
  protected Cel newCelEnv() {
    return runtimeFlavor
        .builder()
        .setStandardMacros(CelStandardMacro.STANDARD_MACROS)
        .addCompilerLibraries(CelExtensions.lists())
        .addRuntimeLibraries(CelExtensions.lists())
        .setContainer(CelContainer.ofName("cel.expr.conformance.test"))
        .addMessageTypes(SimpleTest.getDescriptor())
        .addVar("non_list", SimpleType.DYN)
        .build();
  }

  @Test
  public void functionList_byVersion() {
    assertThat(CelExtensions.lists(0).functions().stream().map(f -> f.name()))
        .containsExactly("slice");
    assertThat(CelExtensions.lists(1).functions().stream().map(f -> f.name()))
        .containsExactly("slice", "flatten");
    assertThat(CelExtensions.lists(2).functions().stream().map(f -> f.name()))
        .containsExactly(
            "slice",
            "flatten",
            "lists.range",
            "distinct",
            "reverse",
            "sort",
            "@sortByAssociatedKeys");
  }

  @Test
  public void macroList_byVersion() {
    assertThat(CelExtensions.lists(0).macros().stream().map(f -> f.getFunction())).isEmpty();
    assertThat(CelExtensions.lists(1).macros().stream().map(f -> f.getFunction())).isEmpty();
    assertThat(CelExtensions.lists(2).macros().stream().map(f -> f.getFunction()))
        .containsExactly("sortBy");
  }

  @Test
  @TestParameters("{expression: '[1,2,3,4].slice(0, 4)', expected: '[1,2,3,4]'}")
  @TestParameters("{expression: '[1,2,3,4].slice(0, 0)', expected: '[]'}")
  @TestParameters("{expression: '[1,2,3,4].slice(1, 1)', expected: '[]'}")
  @TestParameters("{expression: '[1,2,3,4].slice(4, 4)', expected: '[]'}")
  @TestParameters("{expression: '[1,2,3,4].slice(1, 3)', expected: '[2, 3]'}")
  @TestParameters("{expression: 'non_list.slice(1, 3)', expected: '[2, 3]'}")
  public void slice_success(String expression, String expected) throws Exception {
    Object result =
        eval(cel, expression, ImmutableMap.of("non_list", ImmutableSortedSet.of(4L, 1L, 3L, 2L)));

    assertThat(result).isEqualTo(eval(cel, expected));
  }

  @Test
  @TestParameters(
      "{expression: '[1,2,3,4].slice(3, 0)', "
          + "expectedError: 'Start index must be less than or equal to end index'}")
  @TestParameters("{expression: '[1,2,3,4].slice(0, 10)', expectedError: 'List is length 4'}")
  @TestParameters(
      "{expression: '[1,2,3,4].slice(-5, 10)', "
          + "expectedError: 'Negative indexes not supported'}")
  @TestParameters(
      "{expression: '[1,2,3,4].slice(-5, -3)', "
          + "expectedError: 'Negative indexes not supported'}")
  public void slice_throws(String expression, String expectedError) throws Exception {
    assertThat(assertThrows(CelEvaluationException.class, () -> eval(cel, expression)))
        .hasCauseThat()
        .hasMessageThat()
        .contains(expectedError);
  }

  @Test
  @TestParameters("{expression: '[].flatten() == []'}")
  @TestParameters("{expression: '[[1, 2]].flatten().exists(i, i == 1)'}")
  @TestParameters("{expression: '[[], [[]], [[[]]]].flatten() == [[], [[]]]'}")
  @TestParameters("{expression: '[1,[2,[3,4]]].flatten() == [1,2,[3,4]]'}")
  @TestParameters("{expression: '[1,2,[],[],[3,4]].flatten() == [1,2,3,4]'}")
  @TestParameters("{expression: '[1,[2,3],[[4,5]], [[[6,7]]]].flatten() == [1,2,3,[4,5],[[6,7]]]'}")
  @TestParameters("{expression: 'dyn([1]).flatten() == [1]'}")
  @TestParameters("{expression: 'dyn([{1: 2}]).flatten() == [{1: 2}]'}")
  @TestParameters("{expression: 'dyn([1,2,3,4]).flatten() == [1,2,3,4]'}")
  public void flattenSingleLevel_success(String expression) throws Exception {
    boolean result = (boolean) eval(cel, expression);

    assertThat(result).isTrue();
  }

  @Test
  @TestParameters("{expression: '[1,2,3,4].flatten(1) == [1,2,3,4]'}")
  @TestParameters("{expression: '[1,[2,[3,[4]]]].flatten(0) == [1,[2,[3,[4]]]]'}")
  @TestParameters("{expression: '[1,[2,[3,[4]]]].flatten(2) == [1,2,3,[4]]'}")
  @TestParameters("{expression: '[1,[2,[3,4]]].flatten(2) == [1,2,3,4]'}")
  @TestParameters("{expression: '[[], [[]], [[[]]]].flatten(2) == [[]]'}")
  @TestParameters("{expression: '[[], [[]], [[[]]]].flatten(3) == []'}")
  @TestParameters("{expression: '[[], [[]], [[[]]]].flatten(4) == []'}")
  // The overload with the depth accepts and returns a List(dyn), so the following is permitted.
  @TestParameters("{expression: '[1].flatten(1) == [1]'}")
  public void flatten_withDepthValue_success(String expression) throws Exception {
    boolean result = (boolean) eval(cel, expression);

    assertThat(result).isTrue();
  }

  @Test
  public void flatten_negativeDepth_throws() {
    CelEvaluationException e =
        assertThrows(CelEvaluationException.class, () -> eval(cel, "[1,2,3,4].flatten(-1)"));

    if (runtimeFlavor.equals(CelRuntimeFlavor.PLANNER)) {
      assertThat(e)
          .hasMessageThat()
          .contains("evaluation error at <input>:17: Function 'flatten' failed");
    } else {
      assertThat(e)
          .hasMessageThat()
          .contains("evaluation error at <input>:17: Function 'list_flatten_list_int' failed");
    }
    assertThat(e).hasCauseThat().hasMessageThat().isEqualTo("Level must be non-negative");
  }

  @Test
  @TestParameters("{expression: '[1].flatten()'}")
  @TestParameters("{expression: '[{1: 2}].flatten()'}")
  @TestParameters("{expression: '[1,2,3,4].flatten()'}")
  public void flattenSingleLevel_listIsSingleLevel_throws(String expression) {
    // This is a type-checking failure.
    Assume.assumeFalse(isParseOnly);
    // Note: Java lacks the capability of conditionally disabling type guards
    // due to the lack of full-fledged dynamic dispatch.
    assertThrows(CelValidationException.class, () -> cel.compile(expression).getAst());
  }

  @Test
  @TestParameters("{expression: 'lists.range(9) == [0,1,2,3,4,5,6,7,8]'}")
  @TestParameters("{expression: 'lists.range(0) == []'}")
  @TestParameters("{expression: 'lists.range(-1) == []'}")
  public void range_success(String expression) throws Exception {
    boolean result = (boolean) eval(cel, expression);

    assertThat(result).isTrue();
  }

  @Test
  @TestParameters("{expression: '[].distinct()', expected: '[]'}")
  @TestParameters("{expression: '[].distinct()', expected: '[]'}")
  @TestParameters("{expression: '[1].distinct()', expected: '[1]'}")
  @TestParameters("{expression: '[-2, 5, -2, 1, 1, 5, -2, 1].distinct()', expected: '[-2, 5, 1]'}")
  @TestParameters(
      "{expression: '[-2, 5, -2, 1, 1, 5, -2, 1, 5, -2, -2, 1].distinct()', "
          + "expected: '[-2, 5, 1]'}")
  @TestParameters(
      "{expression: '[\"c\", \"a\", \"a\", \"b\", \"a\", \"b\", \"c\", \"c\"].distinct()',"
          + " expected: '[\"c\", \"a\", \"b\"]'}")
  @TestParameters(
      "{expression: '[1, 2.0, \"c\", 3, \"c\", 1].distinct()', "
          + "expected: '[1, 2.0, \"c\", 3]'}")
  @TestParameters("{expression: '[1, 1.0, 2, 2u].distinct()', expected: '[1, 2]'}")
  @TestParameters("{expression: '[[1], [1], [2]].distinct()', expected: '[[1], [2]]'}")
  @TestParameters(
      "{expression: '[SimpleTest{name: \"a\"}, SimpleTest{name: \"b\"}, SimpleTest{name: \"a\"}]"
          + ".distinct()', "
          + "expected: '[SimpleTest{name: \"a\"}, SimpleTest{name: \"b\"}]'}")
  @TestParameters("{expression: 'non_list.distinct()', expected: '[1, 2, 3, 4]'}")
  public void distinct_success(String expression, String expected) throws Exception {
    Object result =
        eval(
            cel,
            expression,
            ImmutableMap.of(
                "non_list", ImmutableSortedMultiset.of(1L, 2L, 3L, 4L, 4L, 1L, 3L, 2L)));

    assertThat(result).isEqualTo(eval(cel, expected));
  }

  @Test
  @TestParameters("{expression: '[5,1,2,3].reverse()', expected: '[3,2,1,5]'}")
  @TestParameters("{expression: '[].reverse()', expected: '[]'}")
  @TestParameters("{expression: '[1].reverse()', expected: '[1]'}")
  @TestParameters(
      "{expression: '[\"are\", \"you\", \"as\", \"bored\", \"as\", \"I\", \"am\"].reverse()', "
          + "expected: '[\"am\", \"I\", \"as\", \"bored\", \"as\", \"you\", \"are\"]'}")
  @TestParameters(
      "{expression: '[false, true, true].reverse().reverse()', expected: '[false, true, true]'}")
  @TestParameters("{expression: 'non_list.reverse()', expected: '[4, 3, 2, 1]'}")
  public void reverse_success(String expression, String expected) throws Exception {
    Object result =
        eval(cel, expression, ImmutableMap.of("non_list", ImmutableSortedSet.of(4L, 1L, 3L, 2L)));

    assertThat(result).isEqualTo(eval(cel, expected));
  }

  @Test
  @TestParameters("{expression: '[].sort()', expected: '[]'}")
  @TestParameters("{expression: '[1].sort()', expected: '[1]'}")
  @TestParameters("{expression: '[4, 3, 2, 1].sort()', expected: '[1, 2, 3, 4]'}")
  @TestParameters(
      "{expression: '[\"d\", \"a\", \"b\", \"c\"].sort()', "
          + "expected: '[\"a\", \"b\", \"c\", \"d\"]'}")
  public void sort_success(String expression, String expected) throws Exception {
    Object result = eval(cel, expression);

    assertThat(result).isEqualTo(eval(cel, expected));
  }

  @Test
  @TestParameters("{expression: '[3.0, 2, 1u].sort()', expected: '[1u, 2, 3.0]'}")
  @TestParameters("{expression: '[4, 3, 2, 1].sort()', expected: '[1, 2, 3, 4]'}")
  public void sort_success_heterogeneousNumbers(String expression, String expected)
      throws Exception {
    Object result = eval(cel, expression);

    assertThat(result).isEqualTo(eval(cel, expected));
  }

  @Test
  @TestParameters(
      "{expression: '[\"d\", 3, 2, \"c\"].sort()', "
          + "expectedError: 'List elements must have the same type'}")
  @TestParameters(
      "{expression: '[SimpleTest{name: \"a\"}, SimpleTest{name: \"b\"}].sort()', "
          + "expectedError: 'List elements must be comparable'}")
  @TestParameters(
      "{expression: '[SimpleTest{name: \"a\"}].sort()', "
          + "expectedError: 'List elements must be comparable'}")
  public void sort_throws(String expression, String expectedError) throws Exception {
    assertThat(assertThrows(CelEvaluationException.class, () -> eval(cel, expression)))
        .hasCauseThat()
        .hasMessageThat()
        .contains(expectedError);
  }

  @Test
  @TestParameters("{expression: '[].sortBy(e, e)', expected: '[]'}")
  @TestParameters("{expression: '[\"a\"].sortBy(e, e)', expected: '[\"a\"]'}")
  @TestParameters(
      "{expression: '[-3, 1, -5, -2, 4].sortBy(e, -(e * e))', " + "expected: '[-5, 4, -3, -2, 1]'}")
  @TestParameters(
      "{expression: '[-3, 1, -5, -2, 4].map(e, e * 2).sortBy(e, -(e * e)) ', "
          + "expected: '[-10, 8, -6, -4, 2]'}")
  @TestParameters("{expression: 'lists.range(3).sortBy(e, -e) ', " + "expected: '[2, 1, 0]'}")
  @TestParameters(
      "{expression: '[\"a\", \"c\", \"b\", \"first\"].sortBy(e, e == \"first\" ? \"\" : e)', "
          + "expected: '[\"first\", \"a\", \"b\", \"c\"]'}")
  @TestParameters(
      "{expression: '[SimpleTest{name: \"baz\"},"
          + " SimpleTest{name: \"foo\"},"
          + " SimpleTest{name: \"bar\"}].sortBy(e, e.name)', "
          + "expected: '[SimpleTest{name: \"bar\"},"
          + " SimpleTest{name: \"baz\"},"
          + " SimpleTest{name: \"foo\"}]'}")
  @TestParameters(
      "{expression: '[SimpleTest{name: \"baz\"},"
          + " SimpleTest{name: \"foo\"},"
          + " SimpleTest{name: \"bar\"}].sortBy(e, e.name)[0].name', "
          + "expected: '\"bar\"'}")
  public void sortBy_success(String expression, String expected) throws Exception {
    Object result = eval(cel, expression);

    assertThat(result).isEqualTo(eval(cel, expected));
  }

  @Test
  @TestParameters(
      "{expression: 'lists.range(3).sortBy(-e, e)', "
          + "expectedError: 'variable name must be a simple identifier'}")
  @TestParameters(
      "{expression: 'lists.range(3).sortBy(e.foo, e)', "
          + "expectedError: 'variable name must be a simple identifier'}")
  public void sortBy_throws_validationException(String expression, String expectedError)
      throws Exception {
    CelValidationResult result = cel.compile(expression);
    assertThat(assertThrows(CelValidationException.class, () -> result.getAst()))
        .hasMessageThat()
        .contains(expectedError);
  }

  @Test
  @TestParameters(
      "{expression: '[[1, 2], [\"a\", \"b\"]].sortBy(e, e[0])', "
          + "expectedError: 'List elements must have the same type'}")
  @TestParameters(
      "{expression: '[SimpleTest{name: \"a\"}, SimpleTest{name: \"b\"}].sortBy(e, e)', "
          + "expectedError: 'List elements must be comparable'}")
  @TestParameters(
      "{expression: '[SimpleTest{name: \"a\"}].sortBy(e, e)', "
          + "expectedError: 'List elements must be comparable'}")
  public void sortBy_throws_evaluationException(String expression, String expectedError)
      throws Exception {
    assertThat(assertThrows(CelEvaluationException.class, () -> eval(cel, expression)))
        .hasCauseThat()
        .hasMessageThat()
        .contains(expectedError);
  }

  @Test
  public void sortBy_withHomogeneousLiteralValidator_success() throws Exception {
    CelValidator validator =
        CelValidatorFactory.standardCelValidatorBuilder(cel)
            .addAstValidators(HomogeneousLiteralValidator.newInstance())
            .build();

    CelAbstractSyntaxTree ast =
        cel.compile(
                "[SimpleTest{name: 'baz'}, SimpleTest{name: 'foo'}, SimpleTest{name: 'bar'}]"
                    + ".sortBy(e, e.name)[0].name")
            .getAst();
    CelValidationResult result = validator.validate(ast);

    assertThat(result.hasError()).isFalse();
    assertThat(cel.createProgram(ast).eval()).isEqualTo("bar");
  }
}
