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

package dev.cel.optimizer.optimizers;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import com.google.testing.junit.testparameterinjector.TestParameters;
import dev.cel.bundle.Cel;
import dev.cel.bundle.CelBuilder;
import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.CelContainer;
import dev.cel.common.CelFunctionDecl;
import dev.cel.common.CelOptions;
import dev.cel.common.CelOverloadDecl;
import dev.cel.common.types.ListType;
import dev.cel.common.types.MapType;
import dev.cel.common.types.SimpleType;
import dev.cel.expr.conformance.proto3.TestAllTypes;
import dev.cel.extensions.CelExtensions;
import dev.cel.extensions.CelOptionalLibrary;
import dev.cel.optimizer.CelOptimizationException;
import dev.cel.optimizer.CelOptimizer;
import dev.cel.optimizer.CelOptimizerFactory;
import dev.cel.optimizer.optimizers.ConstantFoldingOptimizer.ConstantFoldingOptions;
import dev.cel.parser.CelStandardMacro;
import dev.cel.parser.CelUnparser;
import dev.cel.parser.CelUnparserFactory;
import dev.cel.runtime.CelFunctionBinding;
import dev.cel.testing.CelRuntimeFlavor;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(TestParameterInjector.class)
public class ConstantFoldingOptimizerTest {
  private static final CelOptions CEL_OPTIONS =
      CelOptions.current()
          .populateMacroCalls(true)
          .enableHeterogeneousNumericComparisons(true)
          .build();

  private static final CelUnparser CEL_UNPARSER = CelUnparserFactory.newUnparser();

  @TestParameter CelRuntimeFlavor runtimeFlavor;

  private Cel cel;
  private CelOptimizer celOptimizer;

  @Before
  public void setUp() {
    this.cel = setupEnv(runtimeFlavor.builder());
    this.celOptimizer =
        CelOptimizerFactory.standardCelOptimizerBuilder(this.cel)
            .addAstOptimizers(ConstantFoldingOptimizer.getInstance())
            .build();
  }

  private static Cel setupEnv(CelBuilder celBuilder) {
    return celBuilder
        .addVar("x", SimpleType.DYN)
        .addVar("y", SimpleType.DYN)
        .addVar("list_var", ListType.create(SimpleType.STRING))
        .addVar("map_var", MapType.create(SimpleType.STRING, SimpleType.STRING))
        .setStandardMacros(CelStandardMacro.STANDARD_MACROS)
        .addFunctionDeclarations(
            CelFunctionDecl.newFunctionDeclaration(
                "get_true",
                CelOverloadDecl.newGlobalOverload("get_true_overload", SimpleType.BOOL)),
            CelFunctionDecl.newFunctionDeclaration(
                "get_list",
                CelOverloadDecl.newGlobalOverload(
                    "get_list_overload",
                    ListType.create(SimpleType.INT),
                    ListType.create(SimpleType.INT))))
        .addFunctionBindings(
            CelFunctionBinding.from("get_true_overload", ImmutableList.of(), unused -> true))
        .addMessageTypes(TestAllTypes.getDescriptor())
        .setContainer(CelContainer.ofName("cel.expr.conformance.proto3"))
        .setOptions(CEL_OPTIONS)
        .addCompilerLibraries(
            CelExtensions.bindings(),
            CelOptionalLibrary.INSTANCE,
            CelExtensions.math(CEL_OPTIONS),
            CelExtensions.strings(),
            CelExtensions.sets(CEL_OPTIONS),
            CelExtensions.encoders(CEL_OPTIONS))
        .addRuntimeLibraries(
            CelOptionalLibrary.INSTANCE,
            CelExtensions.math(CEL_OPTIONS),
            CelExtensions.strings(),
            CelExtensions.sets(CEL_OPTIONS),
            CelExtensions.encoders(CEL_OPTIONS))
        .build();
  }

  @Test
  @TestParameters("{source: 'null', expected: 'null'}")
  @TestParameters("{source: '1 + 2', expected: '3'}")
  @TestParameters("{source: '1 + 2 + 3', expected: '6'}")
  @TestParameters("{source: '1 + 2 + x', expected: '3 + x'}")
  @TestParameters("{source: 'true && true', expected: 'true'}")
  @TestParameters("{source: 'true && false', expected: 'false'}")
  @TestParameters("{source: 'true || false', expected: 'true'}")
  @TestParameters("{source: 'false || false', expected: 'false'}")
  @TestParameters("{source: 'true && false || true', expected: 'true'}")
  @TestParameters("{source: 'false && true || false', expected: 'false'}")
  @TestParameters("{source: 'true && x', expected: 'x'}")
  @TestParameters("{source: 'x && true', expected: 'x'}")
  @TestParameters("{source: 'false && x', expected: 'false'}")
  @TestParameters("{source: 'x && false', expected: 'false'}")
  @TestParameters("{source: 'true || x', expected: 'true'}")
  @TestParameters("{source: 'x || true', expected: 'true'}")
  @TestParameters("{source: 'false || x', expected: 'x'}")
  @TestParameters("{source: 'x || false', expected: 'x'}")
  @TestParameters("{source: 'true && x && true && x', expected: 'x && x'}")
  @TestParameters("{source: 'false || x || false || x', expected: 'x || x'}")
  @TestParameters("{source: 'false || x || false || y', expected: 'x || y'}")
  @TestParameters("{source: 'true ? x + 1 : x + 2', expected: 'x + 1'}")
  @TestParameters("{source: 'false ? x + 1 : x + 2', expected: 'x + 2'}")
  @TestParameters(
      "{source: 'false ? x + ''world'' : ''hello'' + ''world''', expected: '\"helloworld\"'}")
  @TestParameters("{source: 'true ? (false ? x + 1 : x + 2) : x', expected: 'x + 2'}")
  @TestParameters("{source: 'false ? x : (true ? x + 1 : x + 2)', expected: 'x + 1'}")
  @TestParameters("{source: '[1, 1 + 2, 1 + (2 + 3)]', expected: '[1, 3, 6]'}")
  @TestParameters("{source: '1 in []', expected: 'false'}")
  @TestParameters("{source: 'x in []', expected: 'false'}")
  @TestParameters("{source: '6 in [1, 1 + 2, 1 + (2 + 3)]', expected: 'true'}")
  @TestParameters("{source: '5 in [1, 1 + 2, 1 + (2 + 3)]', expected: 'false'}")
  @TestParameters("{source: '5 in [1, x, y, 5]', expected: 'true'}")
  @TestParameters("{source: '!(5 in [1, x, y, 5])', expected: 'false'}")
  @TestParameters("{source: 'x in [1, x, y, 5]', expected: 'true'}")
  @TestParameters("{source: 'x in [1, 1 + 2, 1 + (2 + 3)]', expected: 'x in [1, 3, 6]'}")
  @TestParameters("{source: 'duration(string(7 * 24) + ''h'')', expected: 'duration(\"168h\")'}")
  @TestParameters("{source: '[1, ?optional.of(3)]', expected: '[1, 3]'}")
  @TestParameters("{source: '[1, ?optional.ofNonZeroValue(0)]', expected: '[1]'}")
  @TestParameters("{source: '[1, optional.of(3)]', expected: '[1, optional.of(3)]'}")
  @TestParameters("{source: '[?optional.none(), ?x]', expected: '[?x]'}")
  @TestParameters("{source: '[?optional.of(1 + 2 + 3)]', expected: '[6]'}")
  @TestParameters("{source: '[?optional.of(3)]', expected: '[3]'}")
  @TestParameters("{source: '[?optional.of(x)]', expected: '[?optional.of(x)]'}")
  @TestParameters("{source: '[?optional.ofNonZeroValue(0)]', expected: '[]'}")
  @TestParameters("{source: '[?optional.ofNonZeroValue(3)]', expected: '[3]'}")
  @TestParameters("{source: '[optional.none(), ?x]', expected: '[optional.none(), ?x]'}")
  @TestParameters("{source: '[optional.of(1 + 2 + 3)]', expected: '[optional.of(6)]'}")
  @TestParameters("{source: '[optional.of(3)]', expected: '[optional.of(3)]'}")
  @TestParameters("{source: '[optional.of(x)]', expected: '[optional.of(x)]'}")
  @TestParameters("{source: '[optional.ofNonZeroValue(1 + 2 + 3)]', expected: '[optional.of(6)]'}")
  @TestParameters("{source: '[optional.ofNonZeroValue(3)]', expected: '[optional.of(3)]'}")
  @TestParameters("{source: 'optional.none()', expected: 'optional.none()'}")
  @TestParameters(
      "{source: '[1, x, optional.of(1), ?optional.of(1), optional.ofNonZeroValue(3),"
          + " ?optional.ofNonZeroValue(3), ?optional.ofNonZeroValue(0), ?y, ?x.?y]', "
          + "expected: '[1, x, optional.of(1), 1, optional.of(3), 3, ?y, ?x.?y]'}")
  @TestParameters(
      "{source: '[1, x, ?optional.ofNonZeroValue(3), ?x.?y].size() > 3',"
          + " expected: '[1, x, 3, ?x.?y].size() > 3'}")
  @TestParameters("{source: '{?1: optional.none()}', expected: '{}'}")
  @TestParameters(
      "{source: '{?1: optional.of(\"hello\"), ?2: optional.ofNonZeroValue(0), 3:"
          + " optional.ofNonZeroValue(0), ?4: optional.of(x)}', expected: '{1: \"hello\", 3:"
          + " optional.none(), ?4: optional.of(x)}'}")
  @TestParameters(
      "{source: '{?x: optional.of(1), ?y: optional.ofNonZeroValue(0)}', expected: '{?x:"
          + " optional.of(1), ?y: optional.none()}'}")
  @TestParameters(
      "{source: 'TestAllTypes{single_int64: 1 + 2 + 3 + x}', "
          + " expected: 'cel.expr.conformance.proto3.TestAllTypes{single_int64: 6 + x}'}")
  @TestParameters(
      "{source: 'TestAllTypes{?single_int64: optional.ofNonZeroValue(1)}', "
          + " expected: 'cel.expr.conformance.proto3.TestAllTypes{single_int64: 1}'}")
  @TestParameters(
      "{source: 'TestAllTypes{?single_int64: optional.ofNonZeroValue(0)}', "
          + " expected: 'cel.expr.conformance.proto3.TestAllTypes{}'}")
  @TestParameters(
      "{source: 'TestAllTypes{?single_int64: optional.ofNonZeroValue(1), ?single_int32:"
          + " optional.of(4), ?single_uint64: optional.ofNonZeroValue(x)}', expected:"
          + " 'cel.expr.conformance.proto3.TestAllTypes{single_int64: 1, single_int32: 4,"
          + " ?single_uint64: optional.ofNonZeroValue(x)}'}")
  @TestParameters(
      "{source: 'TestAllTypes{single_nested_message: TestAllTypes.NestedMessage{bb:"
          + " 42}}.single_nested_message.bb', expected: '42'}")
  @TestParameters("{source: '{\"a\": 1}[\"a\"]', expected: '1'}")
  @TestParameters("{source: '{\"a\": {\"b\": 2}}[\"a\"][\"b\"]', expected: '2'}")
  @TestParameters("{source: '{\"hello\": \"world\"}.hello == x', expected: '\"world\" == x'}")
  @TestParameters("{source: '{\"hello\": \"world\"}[\"hello\"] == x', expected: '\"world\" == x'}")
  @TestParameters("{source: '{\"hello\": \"world\"}.?hello', expected: 'optional.of(\"world\")'}")
  @TestParameters(
      "{source: '{\"hello\": \"world\"}.?hello.orValue(\"default\") == x', "
          + "expected: '\"world\" == x'}")
  @TestParameters(
      "{source: '{?\"hello\": optional.of(\"world\")}[\"hello\"] == x', expected: '\"world\" =="
          + " x'}")
  @TestParameters("{source: '[1] + [2] + [3]', expected: '[1, 2, 3]'}")
  @TestParameters("{source: '[1] + [?optional.of(2)] + [3]', expected: '[1, 2, 3]'}")
  @TestParameters("{source: '[1] + [x]', expected: '[1] + [x]'}")
  @TestParameters("{source: 'x + dyn([1, 2] + [3, 4])', expected: 'x + [1, 2, 3, 4]'}")
  @TestParameters(
      "{source: '{\"a\": dyn([1, 2]), \"b\": x}', expected: '{\"a\": [1, 2], \"b\": x}'}")
  @TestParameters("{source: 'map_var[?\"key\"]', expected: 'map_var[?\"key\"]'}")
  @TestParameters("{source: '\"abc\" in list_var', expected: '\"abc\" in list_var'}")
  @TestParameters("{source: '[?optional.none(), [?optional.none()]]', expected: '[[]]'}")
  @TestParameters("{source: 'math.greatest(1.0, 2, 3.0)', expected: '3.0'}")
  @TestParameters("{source: '\"world\".charAt(1)', expected: '\"o\"'}")
  @TestParameters("{source: 'base64.encode(b\"hello\")', expected: '\"aGVsbG8=\"'}")
  @TestParameters("{source: 'sets.contains([1], [1])', expected: 'true'}")
  @TestParameters(
      "{source: 'cel.bind(r0, [1, 2, 3], cel.bind(r1, 1 in r0, r1))', expected: 'true'}")
  @TestParameters("{source: 'x == true', expected: 'x'}")
  @TestParameters("{source: 'true == x', expected: 'x'}")
  @TestParameters("{source: 'x == false', expected: '!x'}")
  @TestParameters("{source: 'false == x', expected: '!x'}")
  @TestParameters("{source: 'true == false', expected: 'false'}")
  @TestParameters("{source: 'true == true', expected: 'true'}")
  @TestParameters("{source: 'false == true', expected: 'false'}")
  @TestParameters("{source: 'false == false', expected: 'true'}")
  @TestParameters("{source: '10 == 42', expected: 'false'}")
  @TestParameters("{source: '42 == 42', expected: 'true'}")
  @TestParameters("{source: 'x != true', expected: '!x'}")
  @TestParameters("{source: 'true != x', expected: '!x'}")
  @TestParameters("{source: 'x != false', expected: 'x'}")
  @TestParameters("{source: 'false != x', expected: 'x'}")
  @TestParameters("{source: 'true != false', expected: 'true'}")
  @TestParameters("{source: 'true != true', expected: 'false'}")
  @TestParameters("{source: 'false != true', expected: 'true'}")
  @TestParameters("{source: 'false != false', expected: 'false'}")
  @TestParameters("{source: '10 != 42', expected: 'true'}")
  @TestParameters("{source: '42 != 42', expected: 'false'}")
  @TestParameters("{source: '[\"foo\",\"bar\"] == [\"foo\",\"bar\"]', expected: 'true'}")
  @TestParameters("{source: '[\"bar\",\"foo\"] == [\"foo\",\"bar\"]', expected: 'false'}")
  @TestParameters("{source: 'duration(\"1h\") - duration(\"60m\")', expected: 'duration(\"0s\")'}")
  @TestParameters(
      "{source: 'duration(\"2h23m42s12ms42us92ns\") + duration(\"129481231298125ns\")', expected:"
          + " 'duration(\"138103.243340217s\")'}")
  @TestParameters(
      "{source: 'timestamp(900000) - timestamp(100)', expected: 'duration(\"899900s\")'}")
  @TestParameters(
      "{source: 'timestamp(\"2000-01-01T00:02:03.2123Z\") + duration(\"25h2m32s42ms53us29ns\")',"
          + " expected: 'timestamp(\"2000-01-02T01:04:35.254353029Z\")'}")
  @TestParameters(
      "{source: 'has({\"req\": \"Avail\"}.opt) ? ({\"req\": \"Avail\"}.req + \" \" +"
          + " {\"req\": \"Avail\"}.opt) : {\"req\": \"Avail\"}.req', expected: '\"Avail\"'}")
  // TODO: Support folding lists with mixed types. This requires mutable lists.
  // @TestParameters("{source: 'dyn([1]) + [1.0]'}")
  public void constantFold_success(String source, String expected) throws Exception {
    CelAbstractSyntaxTree ast = cel.compile(source).getAst();

    CelAbstractSyntaxTree optimizedAst = celOptimizer.optimize(ast);

    assertThat(CEL_UNPARSER.unparse(optimizedAst)).isEqualTo(expected);
  }

  @Test
  @TestParameters("{source: '[1 + 1, 1 + 2].exists(i, i < 10)', expected: 'true'}")
  @TestParameters("{source: '[1, 1 + 1, 1 + 2, 2 + 3].exists(i, i < 10)', expected: 'true'}")
  @TestParameters("{source: '[1, 1 + 1, 1 + 2, 2 + 3].exists(i, i < 1 % 2)', expected: 'false'}")
  @TestParameters("{source: '[1, 2, 3].map(i, i * 2)', expected: '[2, 4, 6]'}")
  @TestParameters(
      "{source: '[1, 2, 3].map(i, [1, 2, 3].map(j, i * j))', "
          + "expected: '[[1, 2, 3], [2, 4, 6], [3, 6, 9]]'}")
  @TestParameters(
      "{source: '[1, 2, 3].map(i, [1, 2, 3].map(j, i * j).filter(k, k % 2 == 0))', "
          + "expected: '[[2], [2, 4, 6], [6]]'}")
  @TestParameters(
      "{source: '[1, 2, 3].map(i, [1, 2, 3].map(j, i * j).filter(k, k % 2 == x))', "
          + "expected: '[1, 2, 3].map(i, [1, 2, 3].map(j, i * j).filter(k, k % 2 == x))'}")
  @TestParameters("{source: '[1, 2, 3, 4].all(i, v, i < v)', expected: 'true'}")
  @TestParameters(
      "{source: '[1 + 1, 2 + 2, 3 + 3].all(i, v, i < 5 && v < x)', "
          + "expected: '[2, 4, 6].all(i, v, i < 5 && v < x)'}")
  @TestParameters(
      "{source: '[{}, {\"a\": 1}, {\"b\": 2}].filter(m, has(m.a))', expected: '[{\"a\": 1}]'}")
  @TestParameters(
      "{source: '[{}, {?\"a\": optional.of(1)}, {\"b\": optional.of(2)}].filter(m, has(m.a))',"
          + " expected: '[{\"a\": 1}]'}")
  @TestParameters(
      "{source: '[{}, {?\"a\": optional.of(1)}, {\"b\": optional.of(2)}].map(i, i)',"
          + " expected: '[{}, {\"a\": 1}, {\"b\": optional.of(2)}].map(i, i)'}")
  @TestParameters(
      "{source: '[{}, {\"a\": 1}, {\"b\": 2}].filter(m, has({\"a\": true}.a))',"
          + " expected: '[{}, {\"a\": 1}, {\"b\": 2}]'}")
  @TestParameters("{source: 'has(x.single_int32)', expected: 'has(x.single_int32)'}")
  @TestParameters(
      "{source: '[{}, {\"a\": 1}, {\"b\": 2}].filter(m, has(x.a))', expected:"
          + " '[{}, {\"a\": 1}, {\"b\": 2}].filter(m, has(x.a))'}")
  @TestParameters(
      "{source: 'cel.bind(r0, [1, 2, 3], cel.bind(r1, 1 in r0 && 2 in x, r1))', expected:"
          + " 'cel.bind(r0, [1, 2, 3], cel.bind(r1, 1 in r0 && 2 in x, r1))'}")
  @TestParameters("{source: 'false ? false : cel.bind(a, x, a)', expected: 'cel.bind(a, x, a)'}")
  @TestParameters(
      "{source: 'cel.bind(myMap, {\"foo\": \"bar\"}, myMap[?\"foo\"].optMap(x, x + \"baz\"))', "
          + "expected: 'optional.of(\"barbaz\")'}")
  public void constantFold_macros_macroCallMetadataPopulated(String source, String expected)
      throws Exception {
    Cel cel =
        runtimeFlavor
            .builder()
            .addVar("x", SimpleType.DYN)
            .addVar("y", SimpleType.DYN)
            .addMessageTypes(TestAllTypes.getDescriptor())
            .setStandardMacros(CelStandardMacro.STANDARD_MACROS)
            .setOptions(CEL_OPTIONS)
            .addCompilerLibraries(
                CelExtensions.bindings(), CelExtensions.optional(), CelExtensions.comprehensions())
            .addRuntimeLibraries(CelExtensions.optional(), CelExtensions.comprehensions())
            .build();
    CelOptimizer celOptimizer =
        CelOptimizerFactory.standardCelOptimizerBuilder(cel)
            .addAstOptimizers(ConstantFoldingOptimizer.getInstance())
            .build();
    CelAbstractSyntaxTree ast = cel.compile(source).getAst();

    CelAbstractSyntaxTree optimizedAst = celOptimizer.optimize(ast);

    assertThat(CEL_UNPARSER.unparse(optimizedAst)).isEqualTo(expected);
  }

  @Test
  @TestParameters("{source: '[1 + 1, 1 + 2].exists(i, i < 10) == true'}")
  @TestParameters("{source: '[1, 1 + 1, 1 + 2, 2 + 3].exists(i, i < 10) == true'}")
  @TestParameters("{source: '[1, 1 + 1, 1 + 2, 2 + 3].exists(i, i < 1 % 2) == false'}")
  @TestParameters("{source: '[1, 2, 3].map(i, i * 2) == [2, 4, 6]'}")
  @TestParameters(
      "{source: '[1, 2, 3].map(i, [1, 2, 3].map(j, i * j)) == [[1, 2, 3], [2, 4, 6], [3, 6, 9]]'}")
  @TestParameters(
      "{source: '[1, 2, 3].map(i, [1, 2, 3].map(j, i * j).filter(k, k % 2 == 0)) == "
          + "[[2], [2, 4, 6], [6]]'}")
  @TestParameters("{source: '[{}, {\"a\": 1}, {\"b\": 2}].filter(m, has(m.a)) == [{\"a\": 1}]'}")
  @TestParameters(
      "{source: '[{}, {?\"a\": optional.of(1)}, {\"b\": optional.of(2)}].filter(m, has(m.a)) == "
          + " [{\"a\": 1}]'}")
  @TestParameters(
      "{source: '[{}, {?\"a\": optional.of(1)}, {\"b\": optional.of(2)}].map(i, i) == "
          + " [{}, {\"a\": 1}, {\"b\": optional.of(2)}].map(i, i)'}")
  @TestParameters(
      "{source: '[{}, {\"a\": 1}, {\"b\": 2}].filter(m, has({\"a\": true}.a)) == "
          + " [{}, {\"a\": 1}, {\"b\": 2}]'}")
  @TestParameters("{source: 'cel.bind(r0, [1, 2, 3], cel.bind(r1, 1 in r0 && 2 in r0, r1))'}")
  @TestParameters("{source: 'false ? false : cel.bind(a, true, a)'}")
  public void constantFold_macros_withoutMacroCallMetadata(String source) throws Exception {
    Cel cel =
        runtimeFlavor
            .builder()
            .addVar("x", SimpleType.DYN)
            .addVar("y", SimpleType.DYN)
            .addMessageTypes(TestAllTypes.getDescriptor())
            .setStandardMacros(CelStandardMacro.STANDARD_MACROS)
            .setOptions(
                CelOptions.current()
                    .enableHeterogeneousNumericComparisons(true)
                    .populateMacroCalls(false)
                    .build())
            .addCompilerLibraries(CelExtensions.bindings(), CelOptionalLibrary.INSTANCE)
            .addRuntimeLibraries(CelOptionalLibrary.INSTANCE)
            .build();
    CelOptimizer celOptimizer =
        CelOptimizerFactory.standardCelOptimizerBuilder(cel)
            .addAstOptimizers(ConstantFoldingOptimizer.getInstance())
            .build();
    CelAbstractSyntaxTree ast = cel.compile(source).getAst();

    CelAbstractSyntaxTree optimizedAst = celOptimizer.optimize(ast);

    assertThat(optimizedAst.getSource().getMacroCalls()).isEmpty();
    assertThat(cel.createProgram(optimizedAst).eval()).isEqualTo(true);
  }

  @Test
  @TestParameters("{source: '1'}")
  @TestParameters("{source: '5.0'}")
  @TestParameters("{source: 'true'}")
  @TestParameters("{source: 'x'}")
  @TestParameters("{source: 'x + 1 + 2'}")
  @TestParameters("{source: 'duration(\"16800s\")'}")
  @TestParameters("{source: 'timestamp(\"1970-01-01T00:00:00Z\")'}")
  @TestParameters(
      "{source: 'type(1)'}") // This folds in cel-go implementation but Java does not yet have a
  // literal representation for type values
  @TestParameters("{source: '[1, 2, 3]'}")
  @TestParameters("{source: 'optional.of(\"hello\")'}")
  @TestParameters("{source: 'optional.none()'}")
  @TestParameters("{source: '[optional.none()]'}")
  @TestParameters("{source: '[?x.?y]'}")
  @TestParameters(
      "{source: 'cel.expr.conformance.proto3.TestAllTypes{"
          + "single_int32: x, repeated_int32: [1, 2, 3]}'}")
  @TestParameters("{source: 'get_true() == get_true()'}")
  @TestParameters("{source: 'get_true() == true'}")
  @TestParameters("{source: 'x == x'}")
  @TestParameters("{source: 'x == 42'}")
  @TestParameters("{source: 'timestamp(100)'}")
  @TestParameters("{source: 'duration(\"1h\")'}")
  @TestParameters("{source: '[true].exists(x, x == get_true())'}")
  @TestParameters("{source: 'get_list([1, 2]).map(x, x * 2)'}")
  @TestParameters("{source: '[(x - 1 > 3) ? (x - 1) : 5].exists(x, x - 1 > 3)'}")
  public void constantFold_noOp(String source) throws Exception {
    CelAbstractSyntaxTree ast = cel.compile(source).getAst();

    CelAbstractSyntaxTree optimizedAst = celOptimizer.optimize(ast);

    assertThat(CEL_UNPARSER.unparse(optimizedAst)).isEqualTo(source);
  }

  @Test
  public void constantFold_addFoldableFunction_success() throws Exception {
    CelAbstractSyntaxTree ast = cel.compile("get_true() == get_true()").getAst();
    ConstantFoldingOptions options =
        ConstantFoldingOptions.newBuilder().addFoldableFunctions("get_true").build();
    CelOptimizer optimizer =
        CelOptimizerFactory.standardCelOptimizerBuilder(cel)
            .addAstOptimizers(ConstantFoldingOptimizer.newInstance(options))
            .build();

    CelAbstractSyntaxTree optimizedAst = optimizer.optimize(ast);

    assertThat(CEL_UNPARSER.unparse(optimizedAst)).isEqualTo("true");
  }

  @Test
  public void constantFold_withExpectedResultTypeSet_success() throws Exception {
    Cel cel = runtimeFlavor.builder().setResultType(SimpleType.STRING).build();
    CelOptimizer optimizer =
        CelOptimizerFactory.standardCelOptimizerBuilder(cel)
            .addAstOptimizers(ConstantFoldingOptimizer.getInstance())
            .build();
    CelAbstractSyntaxTree ast = cel.compile("string(!true)").getAst();

    CelAbstractSyntaxTree optimizedAst = optimizer.optimize(ast);

    assertThat(CEL_UNPARSER.unparse(optimizedAst)).isEqualTo("\"false\"");
  }

  @Test
  public void constantFold_withMacroCallPopulated_comprehensionsAreReplacedWithNotSet()
      throws Exception {
    Cel cel =
        runtimeFlavor
            .builder()
            .addVar("x", SimpleType.DYN)
            .setStandardMacros(CelStandardMacro.STANDARD_MACROS)
            .setOptions(CEL_OPTIONS)
            .build();
    CelOptimizer celOptimizer =
        CelOptimizerFactory.standardCelOptimizerBuilder(cel)
            .addAstOptimizers(ConstantFoldingOptimizer.getInstance())
            .build();
    CelAbstractSyntaxTree ast =
        cel.compile("[1, 1 + 1, 1 + 1+ 1].map(i, i).filter(j, j % 2 == x)").getAst();

    CelAbstractSyntaxTree optimizedAst = celOptimizer.optimize(ast);

    assertThat(CEL_UNPARSER.unparse(optimizedAst))
        .isEqualTo("[1, 2, 3].map(i, i).filter(j, j % 2 == x)");
    assertThat(optimizedAst.getSource().getMacroCalls()).hasSize(2);
    assertThat(optimizedAst.getSource().getMacroCalls().get(1L).toString())
        .isEqualTo(
            "CALL [0] {\n"
                + "  function: filter\n"
                + "  target: {\n"
                + "    NOT_SET [2] {}\n"
                + "  }\n"
                + "  args: {\n"
                + "    IDENT [25] {\n"
                + "      name: j\n"
                + "    }\n"
                + "    CALL [17] {\n"
                + "      function: _==_\n"
                + "      args: {\n"
                + "        CALL [18] {\n"
                + "          function: _%_\n"
                + "          args: {\n"
                + "            IDENT [19] {\n"
                + "              name: j\n"
                + "            }\n"
                + "            CONSTANT [20] { value: 2 }\n"
                + "          }\n"
                + "        }\n"
                + "        IDENT [21] {\n"
                + "          name: x\n"
                + "        }\n"
                + "      }\n"
                + "    }\n"
                + "  }\n"
                + "}");
    assertThat(optimizedAst.getSource().getMacroCalls().get(2L).toString())
        .isEqualTo(
            "CALL [0] {\n"
                + "  function: map\n"
                + "  target: {\n"
                + "    LIST [3] {\n"
                + "      elements: {\n"
                + "        CONSTANT [4] { value: 1 }\n"
                + "        CONSTANT [5] { value: 2 }\n"
                + "        CONSTANT [6] { value: 3 }\n"
                + "      }\n"
                + "    }\n"
                + "  }\n"
                + "  args: {\n"
                + "    IDENT [28] {\n"
                + "      name: i\n"
                + "    }\n"
                + "    IDENT [12] {\n"
                + "      name: i\n"
                + "    }\n"
                + "  }\n"
                + "}");
  }

  @Test
  public void constantFold_astProducesConsistentlyNumberedIds() throws Exception {
    CelAbstractSyntaxTree ast = cel.compile("[1] + [2] + [3]").getAst();

    CelAbstractSyntaxTree optimizedAst = celOptimizer.optimize(ast);

    assertThat(optimizedAst.getExpr().toString())
        .isEqualTo(
            "LIST [1] {\n"
                + "  elements: {\n"
                + "    CONSTANT [2] { value: 1 }\n"
                + "    CONSTANT [3] { value: 2 }\n"
                + "    CONSTANT [4] { value: 3 }\n"
                + "  }\n"
                + "}");
  }

  @Test
  public void iterationLimitReached_throws() throws Exception {
    Cel cel =
        runtimeFlavor
            .builder()
            .setOptions(
                CelOptions.current()
                    .enableHeterogeneousNumericComparisons(true)
                    .build())
            .build();
    CelAbstractSyntaxTree ast = cel.compile("1 + 1").getAst();
    CelOptimizer optimizer =
        CelOptimizerFactory.standardCelOptimizerBuilder(cel)
            .addAstOptimizers(
                ConstantFoldingOptimizer.newInstance(
                    ConstantFoldingOptions.newBuilder().maxIterationLimit(1).build()))
            .build();

    CelOptimizationException e =
        assertThrows(CelOptimizationException.class, () -> optimizer.optimize(ast));
    assertThat(e).hasMessageThat().contains("Optimization failure: Max iteration count reached.");
  }


}
