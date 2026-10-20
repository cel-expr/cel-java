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

package dev.cel.optimizer.optimizers;

import static com.google.common.truth.Truth.assertThat;
import static dev.cel.common.CelOverloadDecl.newGlobalOverload;

import com.google.common.collect.ImmutableMap;
import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
// import com.google.testing.testsize.MediumTest;
import dev.cel.bundle.Cel;
import dev.cel.bundle.CelBuilder;
import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.CelContainer;
import dev.cel.common.CelFunctionDecl;
import dev.cel.common.CelOptions;
import dev.cel.common.types.OptionalType;
import dev.cel.common.types.SimpleType;
import dev.cel.common.types.StructTypeReference;
import dev.cel.expr.conformance.proto3.NestedTestAllTypes;
import dev.cel.expr.conformance.proto3.TestAllTypes;
import dev.cel.extensions.CelExtensions;
import dev.cel.optimizer.CelOptimizer;
import dev.cel.optimizer.CelOptimizerFactory;
import dev.cel.optimizer.optimizers.SubexpressionOptimizer.SubexpressionOptimizerOptions;
import dev.cel.parser.CelStandardMacro;
import dev.cel.parser.CelUnparser;
import dev.cel.parser.CelUnparserFactory;
import dev.cel.runtime.CelFunctionBinding;
import dev.cel.testing.BaselineTestCase;
import dev.cel.testing.CelRuntimeFlavor;
import java.util.EnumSet;
import java.util.Optional;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

// @MediumTest
@RunWith(TestParameterInjector.class)
// Golden tests accumulate all cases into a single baseline file per test method.
@SuppressWarnings("LoopToTestParameter")
public class SubexpressionOptimizerBaselineTest extends BaselineTestCase {
  private static Cel setupCelEnv(CelBuilder celBuilder) {
    return celBuilder
        .addMessageTypes(TestAllTypes.getDescriptor())
        .setContainer(CelContainer.ofName("cel.expr.conformance.proto3"))
        .setStandardMacros(CelStandardMacro.STANDARD_MACROS)
        .setOptions(
            CelOptions.current()
                .populateMacroCalls(true)
                .enableHeterogeneousNumericComparisons(true)
                .build())
        .addCompilerLibraries(
            CelExtensions.optional(), CelExtensions.bindings(), CelExtensions.comprehensions())
        .addRuntimeLibraries(CelExtensions.optional(), CelExtensions.comprehensions())
        .addFunctionDeclarations(
            CelFunctionDecl.newFunctionDeclaration(
                "pure_custom_func",
                newGlobalOverload("pure_custom_func_overload", SimpleType.INT, SimpleType.INT)),
            CelFunctionDecl.newFunctionDeclaration(
                "non_pure_custom_func",
                newGlobalOverload("non_pure_custom_func_overload", SimpleType.INT, SimpleType.INT)))
        .addFunctionBindings(
            // This is pure, but for the purposes of excluding it as a CSE candidate, pretend that
            // it isn't.
            CelFunctionBinding.fromOverloads(
                "non_pure_custom_func",
                CelFunctionBinding.from("non_pure_custom_func_overload", Long.class, val -> val)))
        .addFunctionBindings(
            CelFunctionBinding.fromOverloads(
                "pure_custom_func",
                CelFunctionBinding.from("pure_custom_func_overload", Long.class, val -> val)))
        .addVar("x", SimpleType.DYN)
        .addVar("y", SimpleType.DYN)
        .addVar("opt_x", OptionalType.create(SimpleType.DYN))
        .addVar("msg", StructTypeReference.create(TestAllTypes.getDescriptor().getFullName()))
        .build();
  }

  private static final CelUnparser CEL_UNPARSER = CelUnparserFactory.newUnparser();
  private static final TestAllTypes TEST_ALL_TYPES_INPUT =
      TestAllTypes.newBuilder()
          .setSingleInt64(3L)
          .setSingleInt32(5)
          .setOneofType(
              NestedTestAllTypes.newBuilder()
                  .setPayload(
                      TestAllTypes.newBuilder()
                          .setSingleInt32(8)
                          .setSingleInt64(10L)
                          .putMapInt32Int64(0, 1)
                          .putMapInt32Int64(1, 5)
                          .putMapInt32Int64(2, 2)
                          .putMapStringString("key", "A")))
          .build();
  private static final ImmutableMap<String, Object> INPUT_MAP =
      ImmutableMap.of("msg", TEST_ALL_TYPES_INPUT, "x", 5L, "y", 6L, "opt_x", Optional.of(5L));

  private static final SubexpressionOptimizerOptions OPTIMIZER_COMMON_OPTIONS =
      SubexpressionOptimizerOptions.newBuilder()
          .populateMacroCalls(true)
          .addEliminableFunctions("pure_custom_func")
          .build();

  @Before
  public void setUp() {
    this.cel = setupCelEnv(runtimeFlavor.builder());
  }

  @TestParameter CelRuntimeFlavor runtimeFlavor;

  private Cel cel;

  @Test
  public void cse_producesSameEvaluationResult(@TestParameter CseTestCase cseTestCase)
      throws Exception {
    skipBaselineVerification();
    CelAbstractSyntaxTree ast = cel.compile(cseTestCase.source).getAst();
    Object expectedEvalResult = cel.createProgram(ast).eval(INPUT_MAP);

    CelAbstractSyntaxTree optimizedAst = newCseOptimizer().optimize(ast);

    Object optimizedEvalResult = cel.createProgram(optimizedAst).eval(INPUT_MAP);
    assertThat(optimizedEvalResult).isEqualTo(expectedEvalResult);
  }

  @Test
  public void cse_producesSameEvaluationResult_parsedOnly(@TestParameter CseTestCase cseTestCase)
      throws Exception {
    skipBaselineVerification();
    if (runtimeFlavor.equals(CelRuntimeFlavor.LEGACY)) {
      return;
    }
    CelAbstractSyntaxTree ast = cel.compile(cseTestCase.source).getAst();
    Object expectedEvalResult = cel.createProgram(ast).eval(INPUT_MAP);

    CelAbstractSyntaxTree optimizedAst = newCseOptimizer().optimize(ast);
    CelAbstractSyntaxTree parsedOnlyOptimizedAst =
        CelAbstractSyntaxTree.newParsedAst(optimizedAst.getExpr(), optimizedAst.getSource());

    Object optimizedEvalResult = cel.createProgram(parsedOnlyOptimizedAst).eval(INPUT_MAP);
    assertThat(optimizedEvalResult).isEqualTo(expectedEvalResult);
  }

  @Test
  public void subexpression_unparsed() throws Exception {
    printUnparsedTestCases(newCseOptimizer());
  }

  @Test
  public void constfold_before_subexpression_unparsed() throws Exception {
    printUnparsedTestCases(
        CelOptimizerFactory.standardCelOptimizerBuilder(cel)
            .addAstOptimizers(
                ConstantFoldingOptimizer.getInstance(),
                SubexpressionOptimizer.newInstance(OPTIMIZER_COMMON_OPTIONS))
            .build());
  }

  @Test
  public void subexpression_ast_block_common_subexpr_only() throws Exception {
    for (CseTestCase cseTestCase : EnumSet.allOf(CseTestCase.class)) {
      testOutput().println("Test case: " + cseTestCase.name());
      testOutput().println("Source: " + cseTestCase.source);
      testOutput().println("=====>");
      CelAbstractSyntaxTree ast = cel.compile(cseTestCase.source).getAst();
      CelAbstractSyntaxTree optimizedAst = newCseOptimizer().optimize(ast);
      testOutput().println(optimizedAst.getExpr());
    }
  }

  @Test
  public void large_expressions_block_common_subexpr() throws Exception {
    CelOptimizer celOptimizer =
        CelOptimizerFactory.standardCelOptimizerBuilder(cel)
            .addAstOptimizers(
                SubexpressionOptimizer.newInstance(
                    SubexpressionOptimizerOptions.newBuilder().populateMacroCalls(true).build()))
            .build();
    for (CseLargeTestCase cseTestCase : EnumSet.allOf(CseLargeTestCase.class)) {
      testOutput().println("Test case: " + cseTestCase.name());
      testOutput().println("Source: " + cseTestCase.source);
      testOutput().println("=====>");
      CelAbstractSyntaxTree ast = cel.compile(cseTestCase.source).getAst();
      CelAbstractSyntaxTree optimizedAst = celOptimizer.optimize(ast);
      Object optimizedEvalResult =
          cel.createProgram(optimizedAst)
              .eval(
                  ImmutableMap.of("msg", TEST_ALL_TYPES_INPUT, "x", 5L, "opt_x", Optional.of(5L)));
      testOutput().println("Result: " + optimizedEvalResult);
      try {
        testOutput().printf("Unparsed: %s", CEL_UNPARSER.unparse(optimizedAst));
      } catch (RuntimeException e) {
        testOutput().printf("Unparse Error: %s", e);
      }
      testOutput().println();
      testOutput().println();
    }
  }

  private void printUnparsedTestCases(CelOptimizer celOptimizer) throws Exception {
    for (CseTestCase cseTestCase : EnumSet.allOf(CseTestCase.class)) {
      testOutput().println("Test case: " + cseTestCase.name());
      testOutput().println("Source: " + cseTestCase.source);
      testOutput().println("=====>");
      CelAbstractSyntaxTree optimizedAst =
          celOptimizer.optimize(cel.compile(cseTestCase.source).getAst());
      testOutput().println("Result: " + cel.createProgram(optimizedAst).eval(INPUT_MAP));
      testOutput().println("Unparsed: " + CEL_UNPARSER.unparse(optimizedAst));
      testOutput().println();
    }
  }

  private CelOptimizer newCseOptimizer() {
    return CelOptimizerFactory.standardCelOptimizerBuilder(cel)
        .addAstOptimizers(SubexpressionOptimizer.newInstance(OPTIMIZER_COMMON_OPTIONS))
        .build();
  }

  private enum CseTestCase {
    SIZE_1("size([1,2]) + size([1,2]) + 1 == 5"),
    SIZE_2("2 + size([1,2]) + size([1,2]) + 1 == 7"),
    SIZE_3("size([0]) + size([0]) + size([1,2]) + size([1,2]) == 6"),
    SIZE_4(
        "5 + size([0]) + size([0]) + size([1,2]) + size([1,2]) + "
            + "size([1,2,3]) + size([1,2,3]) == 17"),
    TIMESTAMP(
        "timestamp(int(timestamp(1000000000))).getFullYear() +"
            + " timestamp(int(timestamp(75))).getFullYear() + "
            + " timestamp(int(timestamp(50))).getFullYear() + "
            + " timestamp(int(timestamp(1000000000))).getFullYear() + "
            + " timestamp(int(timestamp(50))).getSeconds() + "
            + " timestamp(int(timestamp(200))).getFullYear() + "
            + " timestamp(int(timestamp(200))).getFullYear() + "
            + " timestamp(int(timestamp(75))).getMinutes() + "
            + " timestamp(int(timestamp(1000000000))).getFullYear() == 13934"),
    MAP_INDEX("{\"a\": 2}[\"a\"] + {\"a\": 2}[\"a\"] * {\"a\": 2}[\"a\"] == 6"),
    /**
     * Input map is:
     *
     * <pre>{@code
     * {
     *    "a": { "b": 1 },
     *    "c": { "b": 1 },
     *    "d": {
     *       "e": { "b": 1 }
     *    },
     *    "e":{
     *       "e": { "b": 1 }
     *    }
     * }
     * }</pre>
     */
    NESTED_MAP_CONSTRUCTION(
        "{'a': {'b': 1}, 'c': {'b': 1}, 'd': {'e': {'b': 1}}, 'e': {'e': {'b': 1}}}"),
    NESTED_LIST_CONSTRUCTION(
        "[1, [1,2,3,4], 2, [1,2,3,4], 5, [1,2,3,4], 7, [[1,2], [1,2,3,4]], [1,2]]"),
    SELECT("msg.single_int64 + msg.single_int64 == 6"),
    SELECT_NESTED_1(
        "msg.oneof_type.payload.single_int64 + msg.oneof_type.payload.single_int32 + "
            + "msg.oneof_type.payload.single_int64 + "
            + "msg.single_int64 + msg.oneof_type.payload.oneof_type.payload.single_int64 == 31"),
    SELECT_NESTED_2(
        "true ||"
            + " msg.oneof_type.payload.oneof_type.payload.oneof_type.payload.oneof_type.payload.single_bool"
            + " || msg.oneof_type.payload.oneof_type.payload.oneof_type.child.child.payload.single_bool"),
    SELECT_NESTED_MESSAGE_MAP_INDEX_1(
        "msg.oneof_type.payload.map_int32_int64[1] + "
            + "msg.oneof_type.payload.map_int32_int64[1] + "
            + "msg.oneof_type.payload.map_int32_int64[1] == 15"),
    SELECT_NESTED_MESSAGE_MAP_INDEX_2(
        "msg.oneof_type.payload.map_int32_int64[0] + "
            + "msg.oneof_type.payload.map_int32_int64[1] + "
            + "msg.oneof_type.payload.map_int32_int64[2] == 8"),
    SELECT_NESTED_NO_COMMON_SUBEXPR(
        "msg.oneof_type.payload.oneof_type.payload.oneof_type.payload.oneof_type.payload.single_int64"),
    TERNARY("(msg.single_int64 > 0 ? msg.single_int64 : 0) == 3"),
    TERNARY_BIND_RHS_ONLY(
        "false ? false : (msg.single_int64) + ((msg.single_int64 + 1) * 2) == 11"),
    NESTED_TERNARY(
        "(msg.single_int64 > 0 ? (msg.single_int32 > 0 ? "
            + "msg.single_int64 + msg.single_int32 : 0) : 0) == 8"),
    MULTIPLE_MACROS_1(
        // Note that all of these have different iteration variables, but they are still logically
        // the same.
        "size([[1].exists(i, i > 0)]) + size([[1].exists(j, j > 0)]) + "
            + "size([[2].exists(k, k > 1)]) + size([[2].exists(l, l > 1)]) == 4"),
    MULTIPLE_MACROS_2(
        "[[1].exists(i, i > 0)] + [[1].exists(j, j > 0)] + [['a'].exists(k, k == 'a')] +"
            + " [['a'].exists(l, l == 'a')] == [true, true, true, true]"),
    MULTIPLE_MACROS_3(
        "[1].exists(i, i > 0) && [1].exists(j, j > 0) && [1].exists(k, k > 1) && [2].exists(l, l >"
            + " 1)"),
    MULTIPLE_MACROS_COMP_V2_1(
        // Note that all of these have different iteration variables, but they are still logically
        // the same.
        "size([[1].exists(i,x,i > 0 && x >= 0)]) + size([[1].exists(j, y, j > 0 && y >= 0)]) + "
            + "size([[2].exists(k,z,k > 1 && z >= 0)]) + size([[2].exists(l, w, l > 1 && w >= 0)]) "
            + " == 4"),
    MULTIPLE_MACROS_COMP_V2_2(
        "[1].exists(i, x, i > 0 && x > 0) && [1].exists(j, y, j > 0 && y > 0) && "
            + "[1].exists(k, z, k > 1 && z > 0) && [2].exists(l, w, l > 1 && w > 0)"),
    NESTED_MACROS("[1,2,3].map(i, [1, 2, 3].map(i, i + 1)) == [[2, 3, 4], [2, 3, 4], [2, 3, 4]]"),
    NESTED_MACROS_2("[1, 2].map(y, [1, 2, 3].filter(x, x == y)) == [[1], [2]]"),
    NESTED_MACROS_3("[1,2,3].map(i, i * 2) + [1,2,3].map(i, i * 2).map(i, i * 2)"),
    NESTED_MACROS_COMP_V2_1(
        "[1,2,3].transformList(i, v, [1, 2, 3].transformList(i, v, i + v + 1)) == "
            + "[[2, 4, 6], [2, 4, 6], [2, 4, 6]]"),
    NESTED_MACROS_COMP_V2_2(
        "[1, 2].transformList(i, y, [1, 2, 3].filter(x, x == y && i < y)) == [[1], [2]]"),
    ADJACENT_NESTED_MACROS(
        "[1,2,3].map(i, [1, 2, 3].map(i, i + 1)) == [1,2,3].map(j, [1, 2, 3].map(j, j + 1))"),
    ADJACENT_NESTED_MACROS_COMP_V2(
        "[1,2,3].transformMap(i, x, [1, 2, 3].transformMap(i, x, i + x + 1)) =="
            + " [1,2,3].transformMap(j, y, [1, 2, 3].transformMap(j, y,  j + y + 1))"),
    INCLUSION_LIST("1 in [1,2,3] && 2 in [1,2,3] && 3 in [3, [1,2,3]] && 1 in [1,2,3]"),
    INCLUSION_MAP("2 in {'a': 1, 2: {true: false}, 3: {true: false}}"),
    MACRO_ITER_VAR_NOT_REFERENCED(
        "[1,2].map(i, [1, 2].map(i, [3,4])) == [[[3, 4], [3, 4]], [[3, 4], [3, 4]]]"),
    MACRO_ITER_VAR2_NOT_REFERENCED(
        "[1,2].transformList(i, v, [1, 2].transformList(i, v, [3,4])) == "
            + "[[[3, 4], [3, 4]], [[3, 4], [3, 4]]]"),
    MACRO_SHADOWED_VARIABLE("[x - 1 > 3 ? x - 1 : 5].exists(x, x - 1 > 3) || x - 1 > 3"),
    MACRO_SHADOWED_VARIABLE_2("[\"foo\", \"bar\"].map(x, [x + x, x + x]).map(x, [x + x, x + x])"),
    MACRO_SHADOWED_VARIABLE_COMP_V2_1(
        "[x - y - 1 > 3 ? x - y - 1 : 5].exists(x, y, x - y - 1 > 3) || x - y - 1 > 3"),
    MACRO_SHADOWED_VARIABLE_COMP_V2_2(
        "[\"foo\", \"bar\"].transformMap(x, y, [x + x, y + y]).transformMap(x, y, [x + x, y + y])"),
    PRESENCE_TEST("has({'a': true}.a) && {'a':true}['a']"),
    PRESENCE_TEST_2("has({'a': true}.a) && has({'a': true}.a)"),
    PRESENCE_TEST_WITH_TERNARY(
        "(has(msg.oneof_type.payload) ? msg.oneof_type.payload.single_int64 : 0) == 10"),
    PRESENCE_TEST_WITH_TERNARY_2(
        "(has(msg.oneof_type.payload) ? msg.oneof_type.payload.single_int64 :"
            + " msg.oneof_type.payload.single_int64 * 0) == 10"),
    PRESENCE_TEST_WITH_TERNARY_3(
        "(has(msg.oneof_type.payload.single_int64) ? msg.oneof_type.payload.single_int64 :"
            + " msg.oneof_type.payload.single_int64 * 0) == 10"),
    /**
     * Input:
     *
     * <pre>{@code
     * (
     *   has(msg.oneof_type) &&
     *   has(msg.oneof_type.payload) &&
     *   has(msg.oneof_type.payload.single_int64)
     * ) ?
     *   (
     *     (
     *       has(msg.oneof_type.payload.map_string_string) &&
     *       has(msg.oneof_type.payload.map_string_string.key)
     *     ) ?
     *       msg.oneof_type.payload.map_string_string.key == "A"
     *     : false
     *   )
     * : false
     * }</pre>
     */
    PRESENCE_TEST_WITH_TERNARY_NESTED(
        "(has(msg.oneof_type) && has(msg.oneof_type.payload) &&"
            + " has(msg.oneof_type.payload.single_int64)) ?"
            + " ((has(msg.oneof_type.payload.map_string_string) &&"
            + " has(msg.oneof_type.payload.map_string_string.key)) ?"
            + " msg.oneof_type.payload.map_string_string.key == 'A' : false) : false"),
    OPTIONAL_LIST(
        "[10, ?optional.none(), [?optional.none(), ?opt_x], [?optional.none(), ?opt_x]] == [10,"
            + " [5], [5]]"),
    OPTIONAL_MAP(
        "{?'hello': optional.of('hello')}['hello'] + {?'hello': optional.of('hello')}['hello'] =="
            + " 'hellohello'"),
    OPTIONAL_MAP_CHAINED(
        "{?'key': optional.of('test')}[?'bogus'].or({'key': 'test'}[?'bogus']).orValue({'key':"
            + " 'test'}['key']) == 'test'"),
    OPTIONAL_MESSAGE(
        "TestAllTypes{?single_int64: optional.ofNonZeroValue(1), ?single_int32:"
            + " optional.of(4)}.single_int32 + TestAllTypes{?single_int64:"
            + " optional.ofNonZeroValue(1), ?single_int32: optional.of(4)}.single_int64 == 5"),
    CALL("('h' + 'e' + 'l' + 'l' + 'o' + ' world').matches('h' + 'e' + 'l' + 'l' + 'o')"),
    CALL_ARGUMENT_NESTED_NO_COMMON_SUBEXPR("'hello world'.matches('h' + 'e' + 'l' + 'l' + 'o')"),
    CALL_TARGET_NESTED_NO_COMMON_SUBEXPR(
        "('h' + 'e' + 'l' + 'l' + 'o' + ' world').matches('hello')"),
    CALL_BOTH_ARGUMENT_TARGET_NESTED_NO_COMMON_SUBEXPR(
        "('h' + 'e' + 'l' + 'l' + 'o' + ' world').matches('w' + 'o' + 'r' + 'l' + 'd')"),
    CUSTOM_FUNCTION_INELIMINABLE(
        "non_pure_custom_func(msg.oneof_type.payload.single_int64) +"
            + " non_pure_custom_func(msg.oneof_type.payload.single_int32) +"
            + " non_pure_custom_func(msg.oneof_type.payload.single_int64) +"
            + " non_pure_custom_func(msg.single_int64)"),
    CUSTOM_FUNCTION_ELIMINABLE(
        "pure_custom_func(msg.oneof_type.payload.single_int64) +"
            + " pure_custom_func(msg.oneof_type.payload.single_int32) +"
            + " pure_custom_func(msg.oneof_type.payload.single_int64) +"
            + " pure_custom_func(msg.single_int64)"),
    VALUE_NUMBERING_COMMUTATIVE_ADD_CONVERSE_RELATION_LOGICAL_AND(
        "(msg.single_int64 + msg.single_int32 > 5 && msg.oneof_type.payload.single_int64 <"
            + " msg.single_int64 + msg.single_int32) == (msg.single_int32 + msg.single_int64 >"
            + " msg.oneof_type.payload.single_int64 && 5 < msg.single_int32 + msg.single_int64)"),
    VALUE_NUMBERING_COMMUTATIVE_MULTIPLY_EQUALITY_LOGICAL_OR(
        "(msg.oneof_type.payload.single_int32 * msg.single_int64 == 24 || msg.single_int32 !="
            + " msg.oneof_type.payload.single_int64) && (msg.oneof_type.payload.single_int64 !="
            + " msg.single_int32 || 24 == msg.single_int64 * msg.oneof_type.payload.single_int32)"),
    ;

    private final String source;

    CseTestCase(String source) {
      this.source = source;
    }
  }

  private enum CseLargeTestCase {
    CALC_FOUR_COMMON_SUBEXPR(
        "[1] + [1,2] + [1,2,3] + [1,2,3,4] + [1] + [1,2] + [1,2,3] + [1,2,3,4] + [1] + [1,2] +"
            + " [1,2,3] + [1,2,3,4] + [1] + [1,2] + [1,2,3] + [1,2,3,4] + [1] + [1,2] + [1,2,3] +"
            + " [1,2,3,4] + [1] + [1,2] + [1,2,3] + [1,2,3,4] + [1] + [1,2] + [1,2,3] + [1,2,3,4] +"
            + " [1] + [1,2] + [1,2,3] + [1,2,3,4] + [1] + [1,2] + [1,2,3] + [1,2,3,4] + [1] + [1,2]"
            + " + [1,2,3] + [1,2,3,4] + [1] + [1,2] + [1,2,3] + [1,2,3,4] + [1] + [1,2] + [1,2,3] +"
            + " [1,2,3,4] + [1] + [1,2] + [1,2,3] + [1,2,3,4] + [1] + [1,2] + [1,2,3] + [1,2,3,4] +"
            + " [1] + [1,2] + [1,2,3] + [1,2,3,4] + [1] + [1,2] + [1,2,3] + [1,2,3,4] + [1] + [1,2]"
            + " + [1,2,3] + [1,2,3,4] + [1] + [1,2] + [1,2,3] + [1,2,3,4] + [1] + [1,2] + [1,2,3] +"
            + " [1,2,3,4] + [1] + [1,2] + [1,2,3] + [1,2,3,4] + [1] + [1,2] + [1,2,3] + [1,2,3,4] +"
            + " [1] + [1,2] + [1,2,3] + [1,2,3,4] + [1] + [1,2] + [1,2,3] + [1,2,3,4] + [1] + [1,2]"
            + " + [1,2,3] + [1,2,3,4] + [1] + [1,2] + [1,2,3] + [1,2,3,4] + [1] + [1,2] + [1,2,3] +"
            + " [1,2,3,4] + [1] + [1,2] + [1,2,3] + [1,2,3,4] + [1] + [1,2] + [1,2,3] + [1,2,3,4] +"
            + " [1] + [1,2] + [1,2,3] + [1,2,3,4] + [1] + [1,2] + [1,2,3] + [1,2,3,4] + [1] + [1,2]"
            + " + [1,2,3] + [1,2,3,4] + [1] + [1,2] + [1,2,3] + [1,2,3,4] + [1] + [1,2] + [1,2,3] +"
            + " [1,2,3,4] + [1] + [1,2] + [1,2,3] + [1,2,3,4] + [1] + [1,2] + [1,2,3] + [1,2,3,4] +"
            + " [1] + [1,2] + [1,2,3] + [1,2,3,4] + [1] + [1,2] + [1,2,3] + [1,2,3,4] + [1] + [1,2]"
            + " + [1,2,3] + [1,2,3,4] + [1] + [1,2] + [1,2,3] + [1,2,3,4] + [1] + [1,2] + [1,2,3] +"
            + " [1,2,3,4]"),
    CALC_ALL_COMMON_SUBEXPR(
        "[0, 1] + [0, 1] + [1, 2] + [1, 2] + [2, 3] + [2, 3] + [3, 4] + [3, 4] + [4, 5] + [4, 5] +"
            + " [5, 6] + [5, 6] + [6, 7] + [6, 7] + [7, 8] + [7, 8] + [8, 9] + [8, 9] + [9, 10] +"
            + " [9, 10] + [10, 11] + [10, 11] + [11, 12] + [11, 12] + [12, 13] + [12, 13] + [13,"
            + " 14] + [13, 14] + [14, 15] + [14, 15] + [15, 16] + [15, 16] + [16, 17] + [16, 17] +"
            + " [17, 18] + [17, 18] + [18, 19] + [18, 19] + [19, 20] + [19, 20] + [20, 21] + [20,"
            + " 21] + [21, 22] + [21, 22] + [22, 23] + [22, 23] + [23, 24] + [23, 24] + [24, 25] +"
            + " [24, 25] + [25, 26] + [25, 26] + [26, 27] + [26, 27] + [27, 28] + [27, 28] + [28,"
            + " 29] + [28, 29] + [29, 30] + [29, 30] + [30, 31] + [30, 31] + [31, 32] + [31, 32] +"
            + " [32, 33] + [32, 33] + [33, 34] + [33, 34] + [34, 35] + [34, 35] + [35, 36] + [35,"
            + " 36] + [36, 37] + [36, 37] + [37, 38] + [37, 38] + [38, 39] + [38, 39] + [39, 40] +"
            + " [39, 40] + [40, 41] + [40, 41] + [41, 42] + [41, 42] + [42, 43] + [42, 43] + [43,"
            + " 44] + [43, 44] + [44, 45] + [44, 45] + [45, 46] + [45, 46] + [46, 47] + [46, 47] +"
            + " [47, 48] + [47, 48] + [48, 49] + [48, 49] + [49, 50] + [49, 50]"),
    NESTED_MACROS(
        "[1,2,3].map(i, [1, 2, 3].map(i, [1, 2, 3].map(i, [1, 2, 3].map(i, [1, 2, 3].map(i,"
            + " [1, 2, 3].map(i, [1, 2, 3].map(i, [1, 2, 3].map(i, [1, 2, 3]))))))))");
    ;

    private final String source;

    CseLargeTestCase(String source) {
      this.source = source;
    }
  }
}
