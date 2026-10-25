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
import com.google.protobuf.Duration;
import com.google.protobuf.Timestamp;
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
import dev.cel.common.types.NullableType;
import dev.cel.common.types.OptionalType;
import dev.cel.common.types.SimpleType;
import dev.cel.common.types.StructTypeReference;
import dev.cel.expr.conformance.proto2.TestAllTypes.NestedMessage;
import dev.cel.expr.conformance.proto2.TestAllTypesExtensions;
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
import dev.cel.verifier.CelVerificationResult;
import dev.cel.verifier.CelVerificationResult.VerificationStatus;
import dev.cel.verifier.CelVerifier;
import dev.cel.verifier.CelVerifierFactory;
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
    System.setProperty("z3.skipLibraryLoad", "true");
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
        .addVar("dyn_x", SimpleType.DYN)
        .addVar("int_x", SimpleType.INT)
        .addVar("int_y", SimpleType.INT)
        .addVar("double_x", SimpleType.DOUBLE)
        .addVar("bool_x", SimpleType.BOOL)
        .addVar("string_x", SimpleType.STRING)
        .addVar("int_list_x", ListType.create(SimpleType.INT))
        .addVar("double_list_x", ListType.create(SimpleType.DOUBLE))
        .addVar("dyn_list_x", ListType.create(SimpleType.DYN))
        .addVar("map_string_int_x", MapType.create(SimpleType.STRING, SimpleType.INT))
        .addVar("map_string_double_x", MapType.create(SimpleType.STRING, SimpleType.DOUBLE))
        .addVar("optional_int_x", OptionalType.create(SimpleType.INT))
        .addVar("optional_double_x", OptionalType.create(SimpleType.DOUBLE))
        .addVar("nested_list_int_x", ListType.create(ListType.create(SimpleType.INT)))
        .addVar("nested_list_double_x", ListType.create(ListType.create(SimpleType.DOUBLE)))
        .addVar(
            "nested_map_list_int_x",
            MapType.create(SimpleType.STRING, ListType.create(SimpleType.INT)))
        .addVar(
            "nested_map_list_double_x",
            MapType.create(SimpleType.STRING, ListType.create(SimpleType.DOUBLE)))
        .addVar("nullable_int_x", NullableType.create(SimpleType.INT))
        .addVar("nullable_double_x", NullableType.create(SimpleType.DOUBLE))
        .addVar("bool_var", SimpleType.BOOL)
        .addVar("list_var", ListType.create(SimpleType.STRING))
        .addVar("map_var", MapType.create(SimpleType.STRING, SimpleType.STRING))
        .addVar("msg", StructTypeReference.create(TestAllTypes.getDescriptor().getFullName()))
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
        .addMessageTypes(dev.cel.expr.conformance.proto2.TestAllTypes.getDescriptor())
        .setContainer(CelContainer.ofName("cel.expr.conformance.proto3"))
        .setOptions(CEL_OPTIONS)
        .addCompilerLibraries(
            CelExtensions.comprehensions(),
            CelExtensions.bindings(),
            CelOptionalLibrary.INSTANCE,
            CelExtensions.lists(),
            CelExtensions.math(),
            CelExtensions.strings(),
            CelExtensions.sets(CEL_OPTIONS),
            CelExtensions.encoders(CEL_OPTIONS))
        .addRuntimeLibraries(
            CelOptionalLibrary.INSTANCE,
            CelExtensions.lists(),
            CelExtensions.math(),
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
  @TestParameters("{source: 'true && bool_var', expected: 'bool_var'}")
  @TestParameters("{source: 'bool_var && false', expected: 'false'}")
  @TestParameters("{source: 'bool_var && true', expected: 'bool_var'}")
  @TestParameters("{source: 'false || [1 + 2, x][0]', expected: 'false || [3, x][0]'}")
  @TestParameters("{source: 'false && x', expected: 'false'}")
  @TestParameters("{source: 'x && false', expected: 'false'}")
  @TestParameters("{source: 'true || x', expected: 'true'}")
  @TestParameters("{source: 'x || true', expected: 'true'}")
  @TestParameters("{source: 'false || bool_var', expected: 'bool_var'}")
  @TestParameters("{source: 'bool_var || false', expected: 'bool_var'}")
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
  @TestParameters("{source: 'x in [1, x, y, 5]', expected: 'x in [1, x, y, 5]'}")
  @TestParameters("{source: 'dyn_x in [1, 2, dyn_x]', expected: 'dyn_x in [1, 2, dyn_x]'}")
  @TestParameters("{source: 'int_x in [1, 2, int_x]', expected: 'true'}")
  @TestParameters("{source: 'bool_x in [true, false, bool_x]', expected: 'true'}")
  @TestParameters("{source: 'string_x in [\"a\", \"b\", string_x]', expected: 'true'}")
  @TestParameters(
      "{source: 'double_x in [1.0, 2.0, double_x]', expected: 'double_x in [1.0, 2.0, double_x]'}")
  @TestParameters("{source: 'int_list_x in [[1], [2], int_list_x]', expected: 'true'}")
  @TestParameters(
      "{source: 'double_list_x in [[1.0], double_list_x]', expected: 'double_list_x in [[1.0],"
          + " double_list_x]'}")
  @TestParameters(
      "{source: 'dyn_list_x in [[1], dyn_list_x]', expected: 'dyn_list_x in [[1], dyn_list_x]'}")
  @TestParameters(
      "{source: 'map_string_int_x in [{\"a\": 1}, map_string_int_x]', expected: 'true'}")
  @TestParameters(
      "{source: 'map_string_double_x in [{\"a\": 1.0}, map_string_double_x]', expected:"
          + " 'map_string_double_x in [{\"a\": 1.0}, map_string_double_x]'}")
  @TestParameters(
      "{source: 'optional_int_x in [optional.of(1), optional_int_x]', expected: 'true'}")
  @TestParameters(
      "{source: 'optional_double_x in [optional.of(1.0), optional_double_x]', expected:"
          + " 'optional_double_x in [optional.of(1.0), optional_double_x]'}")
  @TestParameters("{source: 'nullable_int_x in [1, 2, nullable_int_x]', expected: 'true'}")
  @TestParameters(
      "{source: 'nullable_double_x in [1.0, 2.0, nullable_double_x]', expected:"
          + " 'nullable_double_x in [1.0, 2.0, nullable_double_x]'}")
  @TestParameters(
      "{source: 'double(\"NaN\") in [double(\"NaN\"), double_x]', expected: 'NaN in"
          + " [NaN, double_x]'}")
  @TestParameters(
      "{source: 'nested_list_int_x in [[[1]], [[2]], nested_list_int_x]', expected: 'true'}")
  @TestParameters(
      "{source: 'nested_list_double_x in [[[1.0]], [[2.0]], nested_list_double_x]',"
          + " expected: 'nested_list_double_x in [[[1.0]], [[2.0]], nested_list_double_x]'}")
  @TestParameters(
      "{source: 'nested_map_list_int_x in [{\"a\": [1]}, nested_map_list_int_x]', expected:"
          + " 'true'}")
  @TestParameters(
      "{source: 'nested_map_list_double_x in [{\"a\": [1.0]}, nested_map_list_double_x]',"
          + " expected: 'nested_map_list_double_x in [{\"a\": [1.0]},"
          + " nested_map_list_double_x]'}")
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
  @TestParameters("{source: 'TestAllTypes{single_int64: 1 + 2 + 3}.single_int64', expected: '6'}")
  @TestParameters("{source: 'TestAllTypes{single_int64: 3}.single_int64', expected: '3'}")
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
  @TestParameters("{source: 'bool_var == true', expected: 'bool_var'}")
  @TestParameters("{source: 'true == bool_var', expected: 'bool_var'}")
  @TestParameters("{source: 'bool_var == false', expected: '!bool_var'}")
  @TestParameters("{source: 'false == bool_var', expected: '!bool_var'}")
  @TestParameters(
      "{source: 'msg.?single_bool.orValue(false) == true', expected:"
          + " 'msg.?single_bool.orValue(false)'}")
  @TestParameters(
      "{source: 'msg.?single_bool.orValue(false) == false', expected:"
          + " '!msg.?single_bool.orValue(false)'}")
  @TestParameters("{source: 'true == false', expected: 'false'}")
  @TestParameters("{source: 'true == true', expected: 'true'}")
  @TestParameters("{source: 'false == true', expected: 'false'}")
  @TestParameters("{source: '[1, 2, 3].map(item, item * 2)', expected: '[2, 4, 6]'}")
  @TestParameters("{source: '[1, 2, 3].filter(item, item > 1)', expected: '[2, 3]'}")
  @TestParameters("{source: '[1, 2, 3].exists(item, item > 1)', expected: 'true'}")
  @TestParameters("{source: '[1, 2, 3].all(item, item > 1)', expected: 'false'}")
  @TestParameters("{source: '{\"a\": 1}.all(k, v, k == \"a\" || v > 0)', expected: 'true'}")
  @TestParameters(
      "{source: '{\"a\": 1, \"b\": x}.all(k, v, k == \"a\" || v > 0)', expected: '{\"a\": 1, \"b\":"
          + " x}.all(k, v, k == \"a\" || v > 0)'}")
  @TestParameters("{source: '[1, 2].map(x, [3, 4].map(y, x + y))', expected: '[[4, 5], [5, 6]]'}")
  @TestParameters(
      "{source: '[1, 2, x].map(item, item * 2)', expected: '[1, 2, x].map(item, item * 2)'}")
  @TestParameters("{source: '[[1, 2], [3, 4]].map(x, x[0] == 1)', expected: '[true, false]'}")
  @TestParameters("{source: '[{\"a\": 1}].map(item, item.a) == [1]', expected: 'true'}")
  @TestParameters("{source: '[1].map(item, [1].exists(x, item == x))', expected: '[true]'}")
  @TestParameters("{source: '[{\"a\": 1}].map(x, x.a == 1)', expected: '[true]'}")
  @TestParameters("{source: 'false == false', expected: 'true'}")
  @TestParameters("{source: '10 == 42', expected: 'false'}")
  @TestParameters("{source: '42 == 42', expected: 'true'}")
  @TestParameters("{source: 'bool_var != true', expected: '!bool_var'}")
  @TestParameters("{source: 'true != bool_var', expected: '!bool_var'}")
  @TestParameters("{source: 'bool_var != false', expected: 'bool_var'}")
  @TestParameters("{source: 'false != bool_var', expected: 'bool_var'}")
  @TestParameters(
      "{source: 'msg.?single_bool.orValue(false) != true', expected:"
          + " '!msg.?single_bool.orValue(false)'}")
  @TestParameters(
      "{source: 'msg.?single_bool.orValue(false) != false', expected:"
          + " 'msg.?single_bool.orValue(false)'}")
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
  @TestParameters("{source: 'true || optional.none().hasValue()', expected: 'true'}")
  @TestParameters("{source: 'false && map_var[?\"missing\"].hasValue()', expected: 'false'}")
  @TestParameters("{source: '{\"hello\": [1, 2]}.?hello', expected: 'optional.of([1, 2])'}")
  @TestParameters(
      "{source: '{?\"key\": optional.of({\"a\": 1})}', expected: '{\"key\": {\"a\": 1}}'}")
  @TestParameters(
      "{source: 'TestAllTypes{?repeated_int32: optional.of([1, 2])}',"
          + " expected: 'cel.expr.conformance.proto3.TestAllTypes{repeated_int32: [1, 2]}'}")
  @TestParameters("{source: '[?optional.of([1, x])]', expected: '[?optional.of([1, x])]'}")
  @TestParameters("{source: '[?optional.of({\"a\": x})]', expected: '[?optional.of({\"a\": x})]'}")
  @TestParameters("{source: '[?optional.of({x: 1})]', expected: '[?optional.of({x: 1})]'}")
  @TestParameters(
      "{source: '[?optional.of(TestAllTypes{single_int32: x})]', expected:"
          + " '[?optional.of(cel.expr.conformance.proto3.TestAllTypes{single_int32: x})]'}")
  @TestParameters(
      "{source: '[?optional.of(TestAllTypes{single_int32: 1})]', expected:"
          + " '[cel.expr.conformance.proto3.TestAllTypes{single_int32: 1}]'}")
  // TODO: Support folding lists with mixed types. This requires mutable lists.
  // @TestParameters("{source: 'dyn([1]) + [1.0]'}")
  public void constantFold_success(String source, String expected) throws Exception {
    CelAbstractSyntaxTree ast = cel.compile(source).getAst();

    CelAbstractSyntaxTree optimizedAst = celOptimizer.optimize(ast);

    assertThat(CEL_UNPARSER.unparse(optimizedAst)).isEqualTo(expected);
  }

  @Test
  @TestParameters(
      "{source: 'TestAllTypes{single_int32: 3}', "
          + " expected: 'cel.expr.conformance.proto3.TestAllTypes{single_int32: 3}'}")
  @TestParameters(
      "{source: 'TestAllTypes{single_float: 1.5}', "
          + " expected: 'cel.expr.conformance.proto3.TestAllTypes{single_float: 1.5}'}")
  @TestParameters(
      "{source: 'TestAllTypes{single_nested_message: TestAllTypes.NestedMessage{bb: 42}}', "
          + " expected: 'cel.expr.conformance.proto3.TestAllTypes{single_nested_message:"
          + " cel.expr.conformance.proto3.TestAllTypes.NestedMessage{bb: 42}}'}")
  @TestParameters(
      "{source: 'TestAllTypes{repeated_int32: [1, 2, 3]}', "
          + " expected: 'cel.expr.conformance.proto3.TestAllTypes{repeated_int32: [1, 2, 3]}'}")
  @TestParameters(
      "{source: 'TestAllTypes{map_int32_int64: {1: 2}}', "
          + " expected: 'cel.expr.conformance.proto3.TestAllTypes{map_int32_int64: {1: 2}}'}")
  @TestParameters(
      "{source: 'TestAllTypes{single_any: google.protobuf.Any{type_url:"
          + " \"type.googleapis.com/google.protobuf.Int32Value\", value: b\"\\010\\001\"}}', "
          + " expected: 'cel.expr.conformance.proto3.TestAllTypes{single_any:"
          + " google.protobuf.Any{type_url:"
          + " \"type.googleapis.com/google.protobuf.Int32Value\", value: b\"\\010\\001\"}}'}")
  @TestParameters(
      "{source: '[TestAllTypes{single_int32: 42}][0]', "
          + " expected: 'cel.expr.conformance.proto3.TestAllTypes{single_int32: 42}'}")
  @TestParameters(
      "{source: 'TestAllTypes{single_bytes: b\"\\010\\001\"}', "
          + " expected: 'cel.expr.conformance.proto3.TestAllTypes{single_bytes: b\"\\010\\001\"}'}")
  @TestParameters(
      "{source: 'TestAllTypes{standalone_enum: 1}', "
          + " expected: 'cel.expr.conformance.proto3.TestAllTypes{standalone_enum: 1}'}")
  public void constantFold_protoMessageLiteral_success(String source, String expected)
      throws Exception {
    // Legacy runtime does not support adapting protobuf messages into CelValue (via
    // CelValueProvider).
    if (runtimeFlavor.equals(CelRuntimeFlavor.LEGACY)) {
      return;
    }
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
  @TestParameters(
      "{source: '(1 + 2 + 3 == x) && (x in [1, 2, x])', expected: '6 == x && x in [1, 2, x]'}")
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
  @TestParameters("{source: 'true && x'}")
  @TestParameters("{source: 'x && true'}")
  @TestParameters("{source: 'false || x'}")
  @TestParameters("{source: 'x || false'}")
  @TestParameters("{source: 'true && x && true && x'}")
  @TestParameters("{source: 'false || x || false || x'}")
  @TestParameters("{source: 'false || x || false || y'}")
  @TestParameters("{source: 'x == true'}")
  @TestParameters("{source: 'true == x'}")
  @TestParameters("{source: 'x == false'}")
  @TestParameters("{source: 'false == x'}")
  @TestParameters("{source: 'x != true'}")
  @TestParameters("{source: 'true != x'}")
  @TestParameters("{source: 'x != false'}")
  @TestParameters("{source: 'false != x'}")
  @TestParameters("{source: '[x].exists(item, item == true)'}")
  @TestParameters("{source: '[1, 2, 3, ?optional_int_x][0]'}")
  @TestParameters("{source: '[1, 2, 3, int_x][4]'}")
  @TestParameters("{source: '[1, 2, 3, int_x][-1]'}")
  @TestParameters("{source: '[1, 2, 3, x].slice(0, 4)'}")
  @TestParameters("{source: '[1, 2, 3, 1 / int_x].slice(0, 1)'}")
  @TestParameters("{source: '[1, 2, 3, int_x + 1].slice(0, 1)'}")
  @TestParameters("{source: '[1, 2, 3, ?optional_int_x].slice(0, 1)'}")
  @TestParameters("{source: '{\"a\": 1, \"a\": 2, \"b\": x}[\"b\"]'}")
  @TestParameters("{source: '{\"a\": 1, \"b\": int_x}[string_x]'}")
  @TestParameters("{source: '{1: 10, 1u: 20, 2: x}[2]'}")
  @TestParameters("{source: '{\"a\": 1, ?string_x: optional_int_x}[\"a\"]'}")
  @TestParameters("{source: '{\"a\": 2, string_x: 1}.a'}")
  @TestParameters("{source: '\"%s %s\".format([\"hello\", x])'}")
  @TestParameters("{source: 'sets.contains([1, 2, x], [3])'}")
  @TestParameters("{source: 'sets.contains([1, 2], [int_x])'}")
  @TestParameters("{source: 'sets.contains([1, 2, int_x], [int_y])'}")
  @TestParameters("{source: 'sets.intersects([1, 2], [int_x])'}")
  @TestParameters("{source: 'sets.intersects([1, 2, int_x], [int_y])'}")
  @TestParameters("{source: 'sets.equivalent([int_x], [int_y])'}")
  @TestParameters("{source: 'sets.contains([optional.of(1)], [optional.of(int_x)])'}")
  @TestParameters("{source: '[1] == [int_x]'}")
  @TestParameters("{source: '{\"a\": 1} == {\"a\": int_x}'}")
  @TestParameters("{source: '[1, 2, int_x].exists(i, optional.of(99) == [i].first())'}")
  @TestParameters("{source: '{1: 10, 1u: 20, 2: int_x}.exists(k, k == 2)'}")
  @TestParameters("{source: 'cel.bind(r, {1: 10, 1u: 20, 2: int_x}, r.size())'}")
  @TestParameters("{source: 'cel.bind(r, int_x, 99 in {1: \"a\", 1u: \"b\", 2: r})'}")
  @TestParameters("{source: '[int_list_x, [1, 2]].flatten().slice(1, 3)'}")
  @TestParameters("{source: 'cel.bind(r, [int_list_x], r.flatten().size())'}")
  @TestParameters("{source: '[2, 1, int_x].sort()'}")
  @TestParameters("{source: '[1, 2, int_x].distinct()'}")
  @TestParameters("{source: 'cel.bind(r, [1, x], type(r[1]) == int)'}")
  @TestParameters("{source: 'optional.ofNonZeroValue(x)'}")
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
  public void constantFold_protoMessage_success() throws Exception {
    // Legacy runtime does not support adapting protobuf messages into CelValue (via
    // CelValueProvider).
    if (runtimeFlavor.equals(CelRuntimeFlavor.LEGACY)) {
      return;
    }
    Cel customCel =
        cel.toCelBuilder()
            .addFunctionDeclarations(
                CelFunctionDecl.newFunctionDeclaration(
                    "get_test_all_types",
                    CelOverloadDecl.newGlobalOverload(
                        "get_test_all_types_overload",
                        StructTypeReference.create("cel.expr.conformance.proto3.TestAllTypes"))))
            .addFunctionBindings(
                CelFunctionBinding.from(
                    "get_test_all_types_overload",
                    ImmutableList.of(),
                    unused ->
                        TestAllTypes.newBuilder()
                            .setSingleInt32(1)
                            .setSingleString("hello")
                            .build()))
            .build();
    CelAbstractSyntaxTree ast = customCel.compile("get_test_all_types()").getAst();
    ConstantFoldingOptions options =
        ConstantFoldingOptions.newBuilder().addFoldableFunctions("get_test_all_types").build();
    CelOptimizer optimizer =
        CelOptimizerFactory.standardCelOptimizerBuilder(customCel)
            .addAstOptimizers(ConstantFoldingOptimizer.newInstance(options))
            .build();

    CelAbstractSyntaxTree optimizedAst = optimizer.optimize(ast);

    assertThat(CEL_UNPARSER.unparse(optimizedAst))
        .isEqualTo(
            "cel.expr.conformance.proto3.TestAllTypes{single_int32: 1, single_string: \"hello\"}");
  }

  @Test
  public void constantFold_protoMessage_complexFields_success() throws Exception {
    // Legacy runtime does not support adapting protobuf messages into CelValue (via
    // CelValueProvider).
    if (runtimeFlavor.equals(CelRuntimeFlavor.LEGACY)) {
      return;
    }
    Cel customCel =
        cel.toCelBuilder()
            .addFunctionDeclarations(
                CelFunctionDecl.newFunctionDeclaration(
                    "get_test_all_types_complex",
                    CelOverloadDecl.newGlobalOverload(
                        "get_test_all_types_complex_overload",
                        StructTypeReference.create("cel.expr.conformance.proto3.TestAllTypes"))))
            .addFunctionBindings(
                CelFunctionBinding.from(
                    "get_test_all_types_complex_overload",
                    ImmutableList.of(),
                    unused ->
                        TestAllTypes.newBuilder()
                            .setSingleUint32(123)
                            .setSingleUint64(456L)
                            .setSingleDuration(Duration.newBuilder().setSeconds(10).build())
                            .setSingleTimestamp(Timestamp.newBuilder().setSeconds(10).build())
                            .addRepeatedNestedMessage(
                                TestAllTypes.NestedMessage.newBuilder().setBb(99).build())
                            .build()))
            .build();
    CelAbstractSyntaxTree ast = customCel.compile("get_test_all_types_complex()").getAst();
    ConstantFoldingOptions options =
        ConstantFoldingOptions.newBuilder()
            .addFoldableFunctions("get_test_all_types_complex")
            .build();
    CelOptimizer optimizer =
        CelOptimizerFactory.standardCelOptimizerBuilder(customCel)
            .addAstOptimizers(ConstantFoldingOptimizer.newInstance(options))
            .build();

    CelAbstractSyntaxTree optimizedAst = optimizer.optimize(ast);

    assertThat(CEL_UNPARSER.unparse(optimizedAst)).contains("123u");
  }

  @Test
  public void constantFold_proto2Message_success() throws Exception {
    // Legacy runtime does not support adapting protobuf messages into CelValue (via
    // CelValueProvider).
    if (runtimeFlavor.equals(CelRuntimeFlavor.LEGACY)) {
      return;
    }
    Cel customCel =
        cel.toCelBuilder()
            .addFunctionDeclarations(
                CelFunctionDecl.newFunctionDeclaration(
                    "get_test_all_types_proto2",
                    CelOverloadDecl.newGlobalOverload(
                        "get_test_all_types_proto2_overload",
                        StructTypeReference.create("cel.expr.conformance.proto2.TestAllTypes"))))
            .addFunctionBindings(
                CelFunctionBinding.from(
                    "get_test_all_types_proto2_overload",
                    ImmutableList.of(),
                    unused ->
                        dev.cel.expr.conformance.proto2.TestAllTypes.newBuilder()
                            .setSingleInt32(2)
                            .setExtension(TestAllTypesExtensions.int32Ext, 3)
                            .build()))
            .build();
    CelAbstractSyntaxTree ast = customCel.compile("get_test_all_types_proto2()").getAst();
    ConstantFoldingOptions options =
        ConstantFoldingOptions.newBuilder()
            .addFoldableFunctions("get_test_all_types_proto2")
            .build();
    CelOptimizer optimizer =
        CelOptimizerFactory.standardCelOptimizerBuilder(customCel)
            .addAstOptimizers(ConstantFoldingOptimizer.newInstance(options))
            .build();

    CelAbstractSyntaxTree optimizedAst = optimizer.optimize(ast);

    assertThat(CEL_UNPARSER.unparse(optimizedAst))
        .isEqualTo("cel.expr.conformance.proto2.TestAllTypes{single_int32: 2}");
  }

  @Test
  public void constantFold_proto2Message_complexFields_success() throws Exception {
    // Legacy runtime does not support adapting protobuf messages into CelValue (via
    // CelValueProvider).
    if (runtimeFlavor.equals(CelRuntimeFlavor.LEGACY)) {
      return;
    }
    Cel customCel =
        cel.toCelBuilder()
            .addFunctionDeclarations(
                CelFunctionDecl.newFunctionDeclaration(
                    "get_test_all_types_proto2_complex",
                    CelOverloadDecl.newGlobalOverload(
                        "get_test_all_types_proto2_complex_overload",
                        StructTypeReference.create("cel.expr.conformance.proto2.TestAllTypes"))))
            .addFunctionBindings(
                CelFunctionBinding.from(
                    "get_test_all_types_proto2_complex_overload",
                    ImmutableList.of(),
                    unused ->
                        dev.cel.expr.conformance.proto2.TestAllTypes.newBuilder()
                            .setSingleUint32(123)
                            .setSingleUint64(456L)
                            .addRepeatedNestedMessage(NestedMessage.newBuilder().setBb(99).build())
                            .build()))
            .build();
    CelAbstractSyntaxTree ast = customCel.compile("get_test_all_types_proto2_complex()").getAst();
    ConstantFoldingOptions options =
        ConstantFoldingOptions.newBuilder()
            .addFoldableFunctions("get_test_all_types_proto2_complex")
            .build();
    CelOptimizer optimizer =
        CelOptimizerFactory.standardCelOptimizerBuilder(customCel)
            .addAstOptimizers(ConstantFoldingOptimizer.newInstance(options))
            .build();

    CelAbstractSyntaxTree optimizedAst = optimizer.optimize(ast);

    assertThat(CEL_UNPARSER.unparse(optimizedAst)).contains("123u");
  }

  @Test
  public void constantFold_functionReturningUnregisteredMessage_doesNotFold() throws Exception {
    Cel customCel =
        runtimeFlavor
            .builder()
            .addVar("x", SimpleType.DYN)
            .addFunctionDeclarations(
                CelFunctionDecl.newFunctionDeclaration(
                    "get_unregistered_message",
                    CelOverloadDecl.newGlobalOverload(
                        "get_unregistered_message_overload", SimpleType.ANY)))
            .addFunctionBindings(
                CelFunctionBinding.from(
                    "get_unregistered_message_overload",
                    ImmutableList.of(),
                    unused -> TestAllTypes.getDefaultInstance()))
            .build();
    ConstantFoldingOptions options =
        ConstantFoldingOptions.newBuilder()
            .addFoldableFunctions("get_unregistered_message")
            .build();
    CelOptimizer optimizer =
        CelOptimizerFactory.standardCelOptimizerBuilder(customCel)
            .addAstOptimizers(ConstantFoldingOptimizer.newInstance(options))
            .build();
    CelAbstractSyntaxTree ast = customCel.compile("get_unregistered_message()").getAst();

    CelAbstractSyntaxTree optimizedAst = optimizer.optimize(ast);

    assertThat(CEL_UNPARSER.unparse(optimizedAst)).isEqualTo("get_unregistered_message()");
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
            .setOptions(CelOptions.current().enableHeterogeneousNumericComparisons(true).build())
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

  @Test
  public void constantFold_inOperator_withoutMacros_skipsDoubleNan() throws Exception {
    Cel celWithoutMacros =
        setupEnv(runtimeFlavor.builder()).toCelBuilder().setStandardMacros().build();
    CelOptimizer optimizer =
        CelOptimizerFactory.standardCelOptimizerBuilder(celWithoutMacros)
            .addAstOptimizers(ConstantFoldingOptimizer.getInstance())
            .build();
    CelAbstractSyntaxTree ast =
        celWithoutMacros.compile("double('NaN') in [double('NaN'), double_x]").getAst();

    CelAbstractSyntaxTree optimizedAst = optimizer.optimize(ast);

    assertThat(CEL_UNPARSER.unparse(optimizedAst)).isEqualTo("NaN in [NaN, double_x]");
  }

  @Test
  @TestParameters("{source: '[1, 2, 3, x].slice(0, 1)', expected: '[1]'}")
  @TestParameters("{source: '[1, 2, 3, x].slice(0, 3)', expected: '[1, 2, 3]'}")
  @TestParameters("{source: '[x, 2, 3].slice(1, 3)', expected: '[2, 3]'}")
  @TestParameters("{source: '[x, 2, 3].slice(1, 1)', expected: '[]'}")
  @TestParameters("{source: '[[1, 2], [3, x]].slice(0, 1).flatten(1)', expected: '[1, 2]'}")
  @TestParameters("{source: '[1, x].first()', expected: 'optional.of(1)'}")
  @TestParameters("{source: '[1, x].first().value()', expected: '1'}")
  @TestParameters("{source: '[x, 2].last()', expected: 'optional.of(2)'}")
  @TestParameters("{source: '[x, 2].last().orValue(0)', expected: '2'}")
  @TestParameters("{source: '{0: x, 2u: y}[0u]', expected: 'x'}")
  @TestParameters("{source: '{-1: 10, 18446744073709551615u: int_x}[-1]', expected: '10'}")
  @TestParameters("{source: '[{\"a\": 1, \"b\": int_x}][0].a', expected: '1'}")
  @TestParameters(
      "{source: 'optional.none().orValue(int_x in [1, 2])', expected: 'int_x in [1, 2]'}")
  @TestParameters("{source: 'sets.contains([1, 2, x], [1, 2])', expected: 'true'}")
  @TestParameters("{source: 'sets.contains([1, 2], [3, x])', expected: 'false'}")
  @TestParameters("{source: 'sets.intersects([1, 2, x], [2, y])', expected: 'true'}")
  @TestParameters("{source: '\"%s\".format([\"hello\", x])', expected: '\"hello\"'}")
  @TestParameters("{source: '\"%d-%s\".format([42, \"ok\", x])', expected: '\"42-ok\"'}")
  @TestParameters(
      "{source: 'cel.bind(r, {\"a\": 1, \"b\": int_x}, r.?a.orValue(0))', expected: '1'}")
  public void constantFold_partialEvaluation_success(String source, String expected)
      throws Exception {
    CelAbstractSyntaxTree ast = cel.compile(source).getAst();

    CelAbstractSyntaxTree optimizedAst = celOptimizer.optimize(ast);

    assertThat(CEL_UNPARSER.unparse(optimizedAst)).isEqualTo(expected);
  }

  @Test
  public void constantFold_partialEvaluation_rootResultTypeMismatch_skipsFold() throws Exception {
    Cel stringResultCel =
        setupEnv(runtimeFlavor.builder()).toCelBuilder().setResultType(SimpleType.STRING).build();
    CelOptimizer optimizer =
        CelOptimizerFactory.standardCelOptimizerBuilder(stringResultCel)
            .addAstOptimizers(ConstantFoldingOptimizer.getInstance())
            .build();
    CelAbstractSyntaxTree ast = stringResultCel.compile("[1, x][0]").getAst();

    CelAbstractSyntaxTree optimizedAst = optimizer.optimize(ast);

    assertThat(CEL_UNPARSER.unparse(optimizedAst)).isEqualTo("[1, x][0]");
  }

  @Test
  public void constantFold_partialEvaluation_disableCelStandardEquality_preservesEquality()
      throws Exception {
    Cel nonStandardEqCel =
        setupEnv(runtimeFlavor.builder())
            .toCelBuilder()
            .setOptions(CEL_OPTIONS.toBuilder().disableCelStandardEquality(true).build())
            .build();
    CelOptimizer optimizer =
        CelOptimizerFactory.standardCelOptimizerBuilder(nonStandardEqCel)
            .addAstOptimizers(ConstantFoldingOptimizer.getInstance())
            .build();
    CelAbstractSyntaxTree ast = nonStandardEqCel.compile("[1, int_x].all(i, 1 == i)").getAst();

    CelAbstractSyntaxTree optimizedAst = optimizer.optimize(ast);

    assertThat(CEL_UNPARSER.unparse(optimizedAst)).isEqualTo("[1, int_x].all(i, 1 == i)");
  }

  @Test
  @TestParameters("{source: '[1, 2, 3, int_x][0]', expected: '1'}")
  @TestParameters("{source: '[1, 2, 3, int_x][3]', expected: 'int_x'}")
  @TestParameters("{source: '[1, 2, 3, int_x][?-1]', expected: 'optional.none()'}")
  @TestParameters("{source: '[1, 2, 3, int_x][?0]', expected: 'optional.of(1)'}")
  @TestParameters("{source: '[1, 2, 3, int_x][?3]', expected: 'optional.of(int_x)'}")
  @TestParameters("{source: '[1, 2, 3, int_x][?4]', expected: 'optional.none()'}")
  @TestParameters("{source: '[1, 2, 3, int_x][?0].orValue(99)', expected: '1'}")
  @TestParameters("{source: '[1, 2, 3, int_x][?3].orValue(99)', expected: 'int_x'}")
  @TestParameters("{source: '[1, 2, 3, int_x][?4].orValue(int_x)', expected: 'int_x'}")
  @TestParameters("{source: '[1, 2, 3, int_x].size()', expected: '4'}")
  @TestParameters("{source: '{\"a\": 1, \"b\": int_x}[\"a\"]', expected: '1'}")
  @TestParameters("{source: '{\"a\": 1, \"b\": int_x}[\"b\"]', expected: 'int_x'}")
  @TestParameters("{source: '{\"a\": 1, \"b\": int_x}.a', expected: '1'}")
  @TestParameters("{source: '{\"a\": 1, \"b\": int_x}.b', expected: 'int_x'}")
  @TestParameters("{source: 'has({\"a\": 1, \"b\": int_x}.a)', expected: 'true'}")
  @TestParameters("{source: 'has({\"a\": 1, \"b\": int_x}.b)', expected: 'true'}")
  @TestParameters("{source: 'has({\"a\": 1, \"b\": int_x}.c)', expected: 'false'}")
  @TestParameters("{source: '{\"a\": 1, \"b\": int_x}[?\"a\"]', expected: 'optional.of(1)'}")
  @TestParameters("{source: '{\"a\": 1, \"b\": int_x}[?\"b\"]', expected: 'optional.of(int_x)'}")
  @TestParameters("{source: '{\"a\": 1, \"b\": int_x}[?\"c\"]', expected: 'optional.none()'}")
  @TestParameters("{source: '{\"a\": 1, \"b\": int_x}.?a', expected: 'optional.of(1)'}")
  @TestParameters("{source: '{\"a\": 1, \"b\": int_x}.?b', expected: 'optional.of(int_x)'}")
  @TestParameters("{source: '{\"a\": 1, \"b\": int_x}.?c', expected: 'optional.none()'}")
  @TestParameters("{source: '{\"a\": 1, \"b\": int_x}.?a.orValue(0)', expected: '1'}")
  @TestParameters("{source: '{\"a\": 1, \"b\": int_x}.?b.orValue(0)', expected: 'int_x'}")
  @TestParameters("{source: '{\"a\": 1, \"b\": int_x}.?c.orValue(int_x)', expected: 'int_x'}")
  @TestParameters("{source: '\"a\" in {\"a\": 1, \"b\": int_x}', expected: 'true'}")
  @TestParameters("{source: '\"b\" in {\"a\": 1, \"b\": int_x}', expected: 'true'}")
  @TestParameters("{source: '\"c\" in {\"a\": 1, \"b\": int_x}', expected: 'false'}")
  @TestParameters("{source: '{\"a\": 1, \"b\": int_x}.size()', expected: '2'}")
  @TestParameters("{source: 'optional.of(1).or(optional_int_x)', expected: 'optional.of(1)'}")
  @TestParameters(
      "{source: 'optional.of(int_x).or(optional.of(1))', expected: 'optional.of(int_x)'}")
  @TestParameters(
      "{source: 'optional.of(int_x).or(optional.none())', expected: 'optional.of(int_x)'}")
  @TestParameters("{source: 'optional.none().or(optional_int_x)', expected: 'optional_int_x'}")
  @TestParameters("{source: 'optional.of(1).orValue(int_x)', expected: '1'}")
  @TestParameters("{source: 'optional.of(int_x).orValue(1)', expected: 'int_x'}")
  @TestParameters("{source: 'optional.none().orValue(int_x)', expected: 'int_x'}")
  @TestParameters("{source: 'optional.of(int_x).value()', expected: 'int_x'}")
  @TestParameters("{source: 'optional.of(int_x).hasValue()', expected: 'true'}")
  @TestParameters("{source: 'optional.none().hasValue()', expected: 'false'}")
  @TestParameters("{source: '[1, 2, int_x].exists(i, i == 2)', expected: 'true'}")
  @TestParameters("{source: '[1, 2, int_x].all(i, i > 1)', expected: 'false'}")
  @TestParameters("{source: '{\"a\": 1, \"b\": int_x}.exists(k, k == \"b\")', expected: 'true'}")
  @TestParameters("{source: '{\"a\": 1, \"b\": int_x}.all(k, k != \"b\")', expected: 'false'}")
  @TestParameters("{source: 'cel.bind(r, [1, 2, int_x], r[0] + r[1])', expected: '3'}")
  @TestParameters("{source: 'cel.bind(r, {\"a\": 10, \"b\": int_x}, r.a)', expected: '10'}")
  public void verifyEquivalence_partialConstantFolding_isVerified(String source, String expected)
      throws Exception {
    CelAbstractSyntaxTree ast = cel.compile(source).getAst();

    CelAbstractSyntaxTree optimizedAst = celOptimizer.optimize(ast);

    assertThat(CEL_UNPARSER.unparse(optimizedAst)).isEqualTo(expected);
    if (runtimeFlavor != CelRuntimeFlavor.PLANNER) {
      return;
    }
    CelVerifier verifier = CelVerifierFactory.newVerifier(cel).build();
    CelVerificationResult verificationResult = verifier.verifyEquivalence(ast, optimizedAst);
    assertThat(verificationResult.status()).isEqualTo(VerificationStatus.VERIFIED);
  }

  @Test
  @TestParameters("{source: '[1, 1 / int_x][0]', unsoundFold: '1'}")
  @TestParameters("{source: '[1, int_x + 1][0]', unsoundFold: '1'}")
  @TestParameters("{source: '{\"a\": 2, string_x: 1}[\"a\"]', unsoundFold: '2'}")
  @TestParameters("{source: 'optional.of(1).orValue(1 / int_x)', unsoundFold: '1'}")
  @TestParameters("{source: 'optional.of(1 / int_x).orValue(0)', unsoundFold: '0'}")
  @TestParameters("{source: '[int_x] == [int_y]', unsoundFold: 'true'}")
  @TestParameters("{source: '[double_x] == [double_x]', unsoundFold: 'true'}")
  @TestParameters("{source: '{\"a\": int_x} == {\"a\": int_y}', unsoundFold: 'true'}")
  @TestParameters("{source: 'optional.of(1) == optional.of(int_x)', unsoundFold: 'false'}")
  @TestParameters("{source: 'optional.of(int_x) == optional.of(int_y)', unsoundFold: 'true'}")
  @TestParameters(
      "{source: '[1, 2, int_x].exists(i, optional.of(99) == optional.of(i))',"
          + " unsoundFold: 'false'}")
  @TestParameters("{source: '[1, 2, int_x].exists(i, i in {99: \"a\"})', unsoundFold: 'false'}")
  @TestParameters("{source: 'cel.bind(r, [int_x, int_y], r[0] == r[1])', unsoundFold: 'true'}")
  @TestParameters("{source: 'cel.bind(r, [double_x], r[0] == r[0])', unsoundFold: 'true'}")
  @TestParameters("{source: 'cel.bind(r, [1, int_x], r[1] in {99: \"a\"})', unsoundFold: 'false'}")
  @TestParameters(
      "{source: 'cel.bind(r, int_x, optional.of({99: 10})[r])', unsoundFold: 'optional.none()'}")
  @TestParameters(
      "{source: '[2, 3, int_x].exists(i, optional.of({1: 99})[i].hasValue())',"
          + " unsoundFold: 'false'}")
  public void verifyEquivalence_unsoundPartialFold_isViolated(String source, String unsoundFold)
      throws Exception {
    CelAbstractSyntaxTree ast = cel.compile(source).getAst();

    CelAbstractSyntaxTree optimizedAst = celOptimizer.optimize(ast);

    assertThat(CEL_UNPARSER.unparse(optimizedAst)).isEqualTo(source);
    if (runtimeFlavor != CelRuntimeFlavor.PLANNER) {
      return;
    }
    CelVerifier verifier = CelVerifierFactory.newVerifier(cel).build();
    CelAbstractSyntaxTree unsoundAst = cel.compile(unsoundFold).getAst();
    assertThat(verifier.verifyEquivalence(ast, unsoundAst).status())
        .isEqualTo(VerificationStatus.VIOLATED);
  }
}
