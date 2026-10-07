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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import com.google.testing.junit.testparameterinjector.TestParameters;
import dev.cel.bundle.Cel;
import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.CelContainer;
import dev.cel.common.CelFunctionDecl;
import dev.cel.common.CelOptions;
import dev.cel.common.CelOverloadDecl;
import dev.cel.common.CelValidationException;
import dev.cel.common.CelValidationResult;
import dev.cel.common.types.ListType;
import dev.cel.common.types.SimpleType;
import dev.cel.compiler.CelCompiler;
import dev.cel.compiler.CelCompilerFactory;
import dev.cel.expr.conformance.proto3.TestAllTypes;
import dev.cel.extensions.CelSetsExtensions.Function;
import dev.cel.runtime.CelEvaluationException;
import dev.cel.runtime.CelFunctionBinding;
import dev.cel.runtime.CelLiteRuntime;
import dev.cel.runtime.CelLiteRuntimeBuilder;
import dev.cel.runtime.CelLiteRuntimeFactory;
import dev.cel.runtime.CelRuntime;
import dev.cel.runtime.CelRuntimeBuilder;
import dev.cel.runtime.CelRuntimeFactory;
import dev.cel.runtime.RuntimeEquality;
import dev.cel.runtime.RuntimeHelpers;
import dev.cel.testing.CelRuntimeFlavor;
import java.util.List;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(TestParameterInjector.class)
public final class CelSetsExtensionsTest extends CelExtensionTestBase {
  private static final CelOptions CEL_OPTIONS =
      CelOptions.current().enableHeterogeneousNumericComparisons(true).build();

  @Override
  protected Cel newCelEnv() {
    return runtimeFlavor
        .builder()
        .addMessageTypes(TestAllTypes.getDescriptor())
        .setOptions(CEL_OPTIONS)
        .setContainer(CelContainer.ofName("cel.expr.conformance.proto3"))
        .addCompilerLibraries(CelExtensions.sets(CEL_OPTIONS))
        .addRuntimeLibraries(CelExtensions.sets(CEL_OPTIONS))
        .addVar("list", ListType.create(SimpleType.INT))
        .addVar("subList", ListType.create(SimpleType.INT))
        .addFunctionDeclarations(
            CelFunctionDecl.newFunctionDeclaration(
                "new_int",
                CelOverloadDecl.newGlobalOverload("new_int_int64", SimpleType.INT, SimpleType.INT)))
        .addFunctionBindings(
            CelFunctionBinding.fromOverloads(
                "new_int",
                CelFunctionBinding.from(
                    "new_int_int64",
                    Long.class,
                    // Intentionally return java.lang.Integer to test primitive type adaptation
                    Math::toIntExact)))
        .build();
  }

  @Test
  public void library() {
    CelExtensionLibrary<?> library = CelExtensions.getExtensionLibrary("sets", CelOptions.DEFAULT);
    assertThat(library.name()).isEqualTo("sets");
    assertThat(library.latest().version()).isEqualTo(0);
    assertThat(library.version(0).functions().stream().map(CelFunctionDecl::name))
        .containsExactly("sets.contains", "sets.equivalent", "sets.intersects");
    assertThat(library.version(0).macros()).isEmpty();
  }

  @Test
  public void contains_integerListWithSameValue_succeeds() throws Exception {
    ImmutableList<Integer> list = ImmutableList.of(1, 2, 3, 4);
    ImmutableList<Integer> subList = ImmutableList.of(1, 2, 3, 4);
    assertThat(
            eval("sets.contains(list, subList)", ImmutableMap.of("list", list, "subList", subList)))
        .isEqualTo(true);
  }

  @Test
  public void contains_integerListAsExpression_succeeds() throws Exception {
    assertThat(eval("sets.contains([1, 1], [1])")).isEqualTo(true);
  }

  @Test
  @TestParameters(
      "{expression: 'sets.contains([TestAllTypes{}], [TestAllTypes{}])', expected: true}")
  @TestParameters(
      "{expression: 'sets.contains([TestAllTypes{single_int64: 1, single_uint64: 2u}],"
          + " [TestAllTypes{single_int64: 1, single_uint64: 2u}])', expected: true}")
  @TestParameters(
      "{expression: 'sets.contains([TestAllTypes{single_any: [1.0, 2u, 3]}],"
          + " [TestAllTypes{single_any: [1u, 2, 3.0]}])', expected: true}")
  @TestParameters(
      "{expression: 'sets.contains([TestAllTypes{single_int64: 1, single_uint64: 2u}],"
          + " [TestAllTypes{single_int64: 2, single_uint64: 3u}])', expected: false}")
  public void contains_withProtoMessage_succeeds(String expression, boolean expected)
      throws Exception {
    assertThat(eval(expression)).isEqualTo(expected);
  }

  @Test
  @TestParameters("{expression: 'sets.contains([new_int(1)], [1])', expected: true}")
  @TestParameters("{expression: 'sets.contains([new_int(1)], [1.0, 1u])', expected: true}")
  @TestParameters("{expression: 'sets.contains([new_int(2)], [1])', expected: false}")
  public void contains_withFunctionReturningInteger_succeeds(String expression, boolean expected)
      throws Exception {
    assertThat(eval(expression)).isEqualTo(expected);
  }

  @Test
  @TestParameters("{list: [1, 2, 3, 4], subList: [1, 2, 3, 4], expected: true}")
  @TestParameters("{list: [5, 4, 3, 2, 1], subList: [1, 2, 3], expected: true}")
  @TestParameters("{list: [5, 4, 3, 2, 1], subList: [1, 1, 1, 1, 1], expected: true}")
  @TestParameters("{list: [], subList: [], expected: true}")
  @TestParameters("{list: [1], subList: [], expected: true}")
  @TestParameters("{list: [], subList: [1], expected: false}")
  @TestParameters("{list: [1], subList: [1], expected: true}")
  @TestParameters("{list: [1], subList: [1, 1], expected: true}")
  @TestParameters("{list: [1, 1], subList: [1, 1], expected: true}")
  @TestParameters("{list: [2, 1], subList: [1], expected: true}")
  @TestParameters("{list: [1, 2, 3, 4], subList: [2, 3], expected: true}")
  @TestParameters("{list: [1], subList: [2], expected: false}")
  @TestParameters("{list: [1], subList: [1, 2], expected: false}")
  public void contains_withIntTypes_succeeds(
      List<Integer> list, List<Integer> subList, boolean expected) throws Exception {
    assertThat(
            eval("sets.contains(list, subList)", ImmutableMap.of("list", list, "subList", subList)))
        .isEqualTo(expected);
  }

  @Test
  @TestParameters("{list: [1.0], subList: [1.0, 1.0], expected: true}")
  @TestParameters("{list: [1.0, 1.00], subList: [1], expected: true}")
  @TestParameters("{list: [1.0], subList: [1.00], expected: true}")
  @TestParameters("{list: [1.414], subList: [], expected: true}")
  @TestParameters("{list: [], subList: [1.414], expected: false}")
  @TestParameters("{list: [3.14, 2.71], subList: [2.71], expected: true}")
  @TestParameters("{list: [3.9], subList: [3.1], expected: false}")
  @TestParameters("{list: [3.2], subList: [3.1], expected: false}")
  @TestParameters("{list: [2, 3.0], subList: [2, 3], expected: true}")
  public void contains_withDoubleTypes_succeeds(
      List<Double> list, List<Double> subList, boolean expected) throws Exception {
    assertThat(
            eval("sets.contains(list, subList)", ImmutableMap.of("list", list, "subList", subList)))
        .isEqualTo(expected);
  }

  @Test
  @TestParameters("{expression: 'sets.contains([[1], [2, 3]], [[2, 3]])', expected: true}")
  @TestParameters("{expression: 'sets.contains([[1], [2], [3]], [[2, 3]])', expected: false}")
  @TestParameters(
      "{expression: 'sets.contains([[1, 2], [2, 3]], [[1], [2, 3.0]])', expected: false}")
  @TestParameters("{expression: 'sets.contains([[1], [2, 3.0]], [[2, 3]])', expected: true}")
  public void contains_withNestedLists_succeeds(String expression, boolean expected)
      throws Exception {
    assertThat(eval(expression)).isEqualTo(expected);
  }

  @Test
  @TestParameters("{expression: 'sets.contains([1, \"1\"], [1])', expected: true}")
  @TestParameters("{expression: 'sets.contains([1], [1, \"1\"])', expected: false}")
  public void contains_withMixingIntAndString_succeeds(String expression, boolean expected)
      throws Exception {
    assertThat(eval(expression)).isEqualTo(expected);
  }

  @Test
  public void contains_withMixingIntAndString_throwsException(
      @TestParameter({"sets.contains([1], [\"1\"])", "sets.contains([\"1\"], [1])"})
          String expression)
      throws Exception {
    Assume.assumeFalse(isParseOnly);
    CelValidationResult invalidData = cel.compile(expression);

    assertThat(invalidData.getErrors()).hasSize(1);
    assertThat(invalidData.getErrors().get(0).getMessage())
        .contains("found no matching overload for 'sets.contains'");
  }

  @Test
  public void contains_withMixedValues_succeeds() throws Exception {
    assertThat(eval("sets.contains([1, 2], [2u, 2.0])")).isEqualTo(true);
  }

  @Test
  @TestParameters("{expression: 'sets.contains([[1], [2, 3.0]], [[2, 3]])', expected: true}")
  @TestParameters(
      "{expression: 'sets.contains([[1], [2, 3.0], [[[[[5]]]]]], [[[[[[5]]]]]])', expected: true}")
  @TestParameters(
      "{expression: 'sets.contains([[1], [2, 3.0], [[[[[5]]]]]], [[[[[[5, 1]]]]]])', expected:"
          + " false}")
  @TestParameters(
      "{expression: 'sets.contains([[1], [2, 3.0], [[[[[5, 1]]]]]], [[[[[[5]]]]]])', expected:"
          + " false}")
  @TestParameters(
      "{expression: 'sets.contains([[[[[[5]]]]]], [[1], [2, 3.0], [[[[[5]]]]]])', expected: false}")
  public void contains_withMultiLevelNestedList_succeeds(String expression, boolean expected)
      throws Exception {
    assertThat(eval(expression)).isEqualTo(expected);
  }

  @Test
  @TestParameters("{expression: 'sets.contains([{1: 1}], [{1: 1}])', expected: true}")
  @TestParameters("{expression: 'sets.contains([{1: 1}], [{1u: 1}, {1: 1.0}])', expected: true}")
  @TestParameters(
      "{expression: 'sets.contains([{\"a\": \"b\"}, {\"c\": \"d\"}], [{\"a\": \"b\"}])', expected:"
          + " true}")
  @TestParameters("{expression: 'sets.contains([{2: 1}], [{1: 1}])', expected: false}")
  @TestParameters(
      "{expression: 'sets.contains([{\"a\": \"b\"}], [{\"a\": \"b\"}, {\"c\": \"d\"}])', expected:"
          + " false}")
  public void contains_withMapValues_succeeds(String expression, boolean expected)
      throws Exception {
    assertThat(eval(expression)).isEqualTo(expected);
  }

  @Test
  @TestParameters("{expression: 'sets.equivalent([], [])', expected: true}")
  @TestParameters("{expression: 'sets.equivalent([1], [1])', expected: true}")
  @TestParameters("{expression: 'sets.equivalent([1], [1, 1])', expected: true}")
  @TestParameters("{expression: 'sets.equivalent([1, 1], [1])', expected: true}")
  @TestParameters("{expression: 'sets.equivalent([[1], [2, 3]], [[1], [2, 3]])', expected: true}")
  @TestParameters("{expression: 'sets.equivalent([2, 1], [1])', expected: false}")
  @TestParameters("{expression: 'sets.equivalent([1], [1, 2])', expected: false}")
  @TestParameters("{expression: 'sets.equivalent([1, 2], [1, 2, 3])', expected: false}")
  @TestParameters("{expression: 'sets.equivalent([1, 2], [2, 2, 2])', expected: false}")
  public void equivalent_withIntTypes_succeeds(String expression, boolean expected)
      throws Exception {
    assertThat(eval(expression)).isEqualTo(expected);
  }

  @Test
  @TestParameters("{expression: 'sets.equivalent([1, 2, 3], [3u, 2.0, 1])', expected: true}")
  @TestParameters("{expression: 'sets.equivalent([1], [1u, 1.0])', expected: true}")
  @TestParameters(
      "{expression: 'sets.equivalent([[1.0], [2, 3]], [[1], [2, 3.0]])', expected: true}")
  @TestParameters("{expression: 'sets.equivalent([1, 2.0, 3], [1, 2])', expected: false}")
  @TestParameters("{expression: 'sets.equivalent([1, 2], [2u, 2, 2.0])', expected: false}")
  @TestParameters("{expression: 'sets.equivalent([1, 2], [1u, 2, 2.3])', expected: false}")
  public void equivalent_withMixedTypes_succeeds(String expression, boolean expected)
      throws Exception {
    assertThat(eval(expression)).isEqualTo(expected);
  }

  @Test
  @TestParameters(
      "{expression: 'sets.equivalent([TestAllTypes{}, TestAllTypes{}], [TestAllTypes{}])',"
          + " expected: true}")
  @TestParameters(
      "{expression: 'sets.equivalent([TestAllTypes{}], [TestAllTypes{}, TestAllTypes{}])',"
          + " expected: true}")
  @TestParameters(
      "{expression: 'sets.equivalent([TestAllTypes{single_int64: 1, single_uint64: 2u}],"
          + " [TestAllTypes{single_int64: 1, single_uint64: 2u}])', expected: true}")
  @TestParameters(
      "{expression: 'sets.equivalent([TestAllTypes{single_any: [1.0, 2u, 3]}],"
          + " [TestAllTypes{single_any: [1u, 2, 3.0]}])', expected: true}")
  @TestParameters(
      "{expression: 'sets.equivalent([TestAllTypes{single_any: [1.0, 2u, 3]},"
          + " TestAllTypes{single_any: [2,3,4]}], [TestAllTypes{single_any: [1u, 2, 3.0]}])',"
          + " expected: false}")
  @TestParameters(
      "{expression: 'sets.equivalent([TestAllTypes{single_int64: 1, single_uint64: 2u}],"
          + " [TestAllTypes{single_int64: 2, single_uint64: 3u}])', expected: false}")
  public void equivalent_withProtoMessage_succeeds(String expression, boolean expected)
      throws Exception {
    assertThat(eval(expression)).isEqualTo(expected);
  }

  @Test
  @TestParameters("{expression: 'sets.equivalent([{1: 1}], [{1: 1}, {1: 1}])', expected: true}")
  @TestParameters("{expression: 'sets.equivalent([{1: 1}, {1: 1}], [{1: 1}])', expected: true}")
  @TestParameters("{expression: 'sets.equivalent([{1: 1}], [{1: 1u}, {1: 1.0}])', expected: true}")
  @TestParameters(
      "{expression: 'sets.equivalent([{1: 1}, {1u: 1}], [{1u: 1}, {1: 1.0}])', expected: true}")
  @TestParameters(
      "{expression: 'sets.equivalent([{\"a\": \"b\"}, {\"a\": \"b\"}], [{\"a\": \"b\"}])',"
          + " expected: true}")
  @TestParameters("{expression: 'sets.equivalent([{2: 1}], [{1: 1}])', expected: false}")
  @TestParameters(
      "{expression: 'sets.equivalent([{\"a\": \"b\"}], [{\"a\": \"b\"}, {\"c\": \"d\"}])',"
          + " expected: false}")
  public void equivalent_withMapValues_succeeds(String expression, boolean expected)
      throws Exception {
    assertThat(eval(expression)).isEqualTo(expected);
  }

  @Test
  @TestParameters("{expression: 'sets.intersects([], [])', expected: false}")
  @TestParameters("{expression: 'sets.intersects([1], [])', expected: false}")
  @TestParameters("{expression: 'sets.intersects([], [1])', expected: false}")
  @TestParameters("{expression: 'sets.intersects([1], [1])', expected: true}")
  @TestParameters("{expression: 'sets.intersects([1], [2])', expected: false}")
  @TestParameters("{expression: 'sets.intersects([1], [1, 1])', expected: true}")
  @TestParameters("{expression: 'sets.intersects([1, 1], [1])', expected: true}")
  @TestParameters("{expression: 'sets.intersects([2, 1], [1])', expected: true}")
  @TestParameters("{expression: 'sets.intersects([1], [1, 2])', expected: true}")
  @TestParameters("{expression: 'sets.intersects([1], [1.0, 2])', expected: true}")
  @TestParameters("{expression: 'sets.intersects([1, 2], [2u, 2, 2.0])', expected: true}")
  @TestParameters("{expression: 'sets.intersects([1, 2], [1, 2, 2.3])', expected: true}")
  @TestParameters("{expression: 'sets.intersects([0, 1, 2], [1, 2, 2.3])', expected: true}")
  @TestParameters("{expression: 'sets.intersects([1, 2], [1u, 2, 2.3])', expected: true}")
  @TestParameters(
      "{expression: 'sets.intersects([[1], [2, 3]], [[1, 2], [2, 3.0]])', expected: true}")
  @TestParameters("{expression: 'sets.intersects([1], [\"1\", 2])', expected: false}")
  @TestParameters("{expression: 'sets.intersects([1], [1.1, 2])', expected: false}")
  @TestParameters("{expression: 'sets.intersects([1], [1.1, 2u])', expected: false}")
  public void intersects_withMixedTypes_succeeds(String expression, boolean expected)
      throws Exception {
    assertThat(eval(expression)).isEqualTo(expected);
  }

  @Test
  @TestParameters("{expression: 'sets.intersects([{1: 1}], [{1: 1}, {1: 1}])', expected: true}")
  @TestParameters("{expression: 'sets.intersects([{1: 1}, {1: 1}], [{1: 1}])', expected: true}")
  @TestParameters("{expression: 'sets.intersects([{1: 1}], [{1: 1u}, {1: 1.0}])', expected: true}")
  @TestParameters(
      "{expression: 'sets.intersects([{1: 1}, {1u: 1}], [{1.0: 1u}, {1u: 1.0}])', expected: true}")
  @TestParameters("{expression: 'sets.intersects([{1:2}], [{1:2}, {2:3}])', expected: true}")
  @TestParameters(
      "{expression: 'sets.intersects([{\"a\": \"b\"}, {\"a\": \"b\"}], [{\"a\": \"b\"}])',"
          + " expected: true}")
  @TestParameters(
      "{expression: 'sets.intersects([{\"a\": \"b\"}], [{\"c\": \"d\"}])', expected: false}")
  @TestParameters("{expression: 'sets.intersects([{2: 1}], [{1: 1}])', expected: false}")
  public void intersects_withMapValues_succeeds(String expression, boolean expected)
      throws Exception {
    // The LEGACY runtime is not spec compliant, because decimal keys are not allowed for maps.
    Assume.assumeFalse(
        runtimeFlavor.equals(CelRuntimeFlavor.PLANNER) && expression.contains("1.0:"));

    assertThat(eval(expression)).isEqualTo(expected);
  }

  @Test
  @TestParameters(
      "{expression: 'sets.intersects([TestAllTypes{}, TestAllTypes{}], [TestAllTypes{}])',"
          + " expected: true}")
  @TestParameters(
      "{expression: 'sets.intersects([TestAllTypes{}], [TestAllTypes{}, TestAllTypes{}])',"
          + " expected: true}")
  @TestParameters(
      "{expression: 'sets.intersects([TestAllTypes{single_int64: 1, single_uint64: 2u}],"
          + " [TestAllTypes{single_int64: 1, single_uint64: 2u}])', expected: true}")
  @TestParameters(
      "{expression: 'sets.intersects([TestAllTypes{single_any: [1.0, 2u, 3]}],"
          + " [TestAllTypes{single_any: [1u, 2, 3.0]}])', expected: true}")
  @TestParameters(
      "{expression: 'sets.intersects([TestAllTypes{single_any: [1, 2, 3.5]},"
          + " TestAllTypes{single_any: [2,3,4]}], [TestAllTypes{single_any: [1u, 2, 3.0]}])',"
          + " expected: false}")
  @TestParameters(
      "{expression: 'sets.intersects([TestAllTypes{single_int64: 1, single_uint64: 2u}],"
          + " [TestAllTypes{single_int64: 2, single_uint64: 3u}])', expected: false}")
  public void intersects_withProtoMessage_succeeds(String expression, boolean expected)
      throws Exception {
    assertThat(eval(expression)).isEqualTo(expected);
  }

  @Test
  public void setsExtension_containsFunctionSubset_succeeds() throws Exception {
    CelSetsExtensions setsExtensions = CelExtensions.sets(CelOptions.DEFAULT, Function.CONTAINS);
    Cel cel =
        runtimeFlavor
            .builder()
            .addCompilerLibraries(setsExtensions)
            .addRuntimeLibraries(setsExtensions)
            .build();

    Object evaluatedResult = eval(cel, "sets.contains([1, 2], [2])");

    assertThat(evaluatedResult).isEqualTo(true);
  }

  @Test
  public void setsExtension_equivalentFunctionSubset_succeeds() throws Exception {
    CelSetsExtensions setsExtensions = CelExtensions.sets(CelOptions.DEFAULT, Function.EQUIVALENT);
    Cel cel =
        runtimeFlavor
            .builder()
            .addCompilerLibraries(setsExtensions)
            .addRuntimeLibraries(setsExtensions)
            .build();

    Object evaluatedResult = eval(cel, "sets.equivalent([1, 1], [1])");

    assertThat(evaluatedResult).isEqualTo(true);
  }

  @Test
  public void setsExtension_intersectsFunctionSubset_succeeds() throws Exception {
    CelSetsExtensions setsExtensions = CelExtensions.sets(CelOptions.DEFAULT, Function.INTERSECTS);
    Cel cel =
        runtimeFlavor
            .builder()
            .addCompilerLibraries(setsExtensions)
            .addRuntimeLibraries(setsExtensions)
            .build();

    Object evaluatedResult = eval(cel, "sets.intersects([1, 1], [1])");

    assertThat(evaluatedResult).isEqualTo(true);
  }

  @Test
  public void setsExtension_compileUnallowedFunction_throws() {
    Assume.assumeFalse(isParseOnly);
    CelSetsExtensions setsExtensions = CelExtensions.sets(CelOptions.DEFAULT, Function.EQUIVALENT);
    Cel cel = runtimeFlavor.builder().addCompilerLibraries(setsExtensions).build();

    assertThrows(
        CelValidationException.class, () -> cel.compile("sets.contains([1, 2], [2])").getAst());
  }

  @Test
  public void setsExtension_evaluateUnallowedFunction_throws() throws Exception {
    CelSetsExtensions setsExtensions =
        CelExtensions.sets(CelOptions.DEFAULT, Function.CONTAINS, Function.EQUIVALENT);
    CelSetsExtensions runtimeLibrary = CelExtensions.sets(CelOptions.DEFAULT, Function.EQUIVALENT);
    Cel cel =
        runtimeFlavor
            .builder()
            .addCompilerLibraries(setsExtensions)
            .addRuntimeLibraries(runtimeLibrary)
            .build();

    CelAbstractSyntaxTree ast =
        isParseOnly
            ? cel.parse("sets.contains([1, 2], [2])").getAst()
            : cel.compile("sets.contains([1, 2], [2])").getAst();

    if (runtimeFlavor.equals(CelRuntimeFlavor.PLANNER) && !isParseOnly) {
      // Fails at plan time
      assertThrows(CelEvaluationException.class, () -> cel.createProgram(ast));
    } else {
      CelRuntime.Program program = cel.createProgram(ast);
      assertThrows(CelEvaluationException.class, () -> program.eval());
    }
  }

  @Test
  public void separateLibraryAndRuntime_allFunctions_success() throws Exception {
    CelCompiler celCompiler =
        CelCompilerFactory.standardCelCompilerBuilder()
            .addLibraries(CelSetsCompilerLibrary.sets())
            .build();
    CelLiteRuntime celLiteRuntime =
        CelLiteRuntimeFactory.newLiteRuntimeBuilder()
            .addLibraries(CelSetsRuntimeLibrary.sets())
            .build();

    CelAbstractSyntaxTree ast = celCompiler.compile("sets.contains([1, 2], [2])").getAst();
    boolean result = (boolean) celLiteRuntime.createProgram(ast).eval();

    assertThat(result).isTrue();
  }

  @Test
  public void separateLibraryAndRuntime_versioned_success() throws Exception {
    CelCompiler celCompiler =
        CelCompilerFactory.standardCelCompilerBuilder()
            .addLibraries(CelSetsCompilerLibrary.sets(0))
            .build();
    RuntimeEquality runtimeEquality =
        RuntimeEquality.create(RuntimeHelpers.create(), CelOptions.DEFAULT);
    CelRuntime celRuntime =
        CelRuntimeFactory.plannerRuntimeBuilder()
            .addFunctionBindings(CelSetsRuntimeLibrary.sets(0).newFunctionBindings(runtimeEquality))
            .build();

    CelAbstractSyntaxTree ast = celCompiler.compile("sets.contains([1, 2], [2])").getAst();
    boolean result = (boolean) celRuntime.createProgram(ast).eval();

    assertThat(result).isTrue();
  }

  @Test
  public void separateLibraryAndRuntime_subsetOfFunctions_success() throws Exception {
    CelCompiler celCompiler =
        CelCompilerFactory.standardCelCompilerBuilder()
            .addLibraries(CelSetsCompilerLibrary.sets(CelSetsCompilerLibrary.Function.CONTAINS))
            .build();
    RuntimeEquality runtimeEquality =
        RuntimeEquality.create(RuntimeHelpers.create(), CelOptions.DEFAULT);
    CelRuntime celRuntime =
        CelRuntimeFactory.plannerRuntimeBuilder()
            .addFunctionBindings(
                CelSetsRuntimeLibrary.sets(CelSetsRuntimeLibrary.Function.CONTAINS)
                    .newFunctionBindings(runtimeEquality))
            .build();

    CelAbstractSyntaxTree ast = celCompiler.compile("sets.contains([1, 2], [2])").getAst();
    boolean result = (boolean) celRuntime.createProgram(ast).eval();

    assertThat(result).isTrue();
    assertThrows(
        CelValidationException.class,
        () -> celCompiler.compile("sets.equivalent([1, 2], [1, 2])").getAst());
  }

  @Test
  public void runtimeLibrary_constructorsAndFactories() {
    CelSetsRuntimeLibrary lib1 =
        new CelSetsRuntimeLibrary(ImmutableSet.of(CelSetsRuntimeLibrary.Function.CONTAINS));
    assertThat(lib1.functions()).containsExactly(CelSetsRuntimeLibrary.Function.CONTAINS);

    CelSetsRuntimeLibrary lib2 = CelSetsRuntimeLibrary.sets();
    assertThat(lib2.functions()).containsExactlyElementsIn(CelSetsRuntimeLibrary.Function.values());

    CelSetsRuntimeLibrary lib3 = CelSetsRuntimeLibrary.sets(0);
    assertThat(lib3.functions()).containsExactlyElementsIn(CelSetsRuntimeLibrary.Function.values());

    CelSetsRuntimeLibrary lib4 =
        CelSetsRuntimeLibrary.sets(CelSetsRuntimeLibrary.Function.CONTAINS);
    assertThat(lib4.functions()).containsExactly(CelSetsRuntimeLibrary.Function.CONTAINS);

    CelSetsRuntimeLibrary lib5 =
        CelSetsRuntimeLibrary.sets(ImmutableSet.of(CelSetsRuntimeLibrary.Function.CONTAINS));
    assertThat(lib5.functions()).containsExactly(CelSetsRuntimeLibrary.Function.CONTAINS);
  }

  @Test
  public void runtimeOptions_unsupported_throws() {
    CelSetsExtensions extensions = new CelSetsExtensions();
    CelRuntimeBuilder runtimeBuilder = CelRuntimeFactory.standardCelRuntimeBuilder();
    assertThrows(
        UnsupportedOperationException.class,
        () -> extensions.setRuntimeOptions(runtimeBuilder));

    CelSetsRuntimeLibrary runtimeLibrary = CelSetsRuntimeLibrary.sets();
    CelLiteRuntimeBuilder liteRuntimeBuilder = CelLiteRuntimeFactory.newLiteRuntimeBuilder();
    assertThrows(
        UnsupportedOperationException.class,
        () -> runtimeLibrary.setRuntimeOptions(liteRuntimeBuilder));
  }
}
