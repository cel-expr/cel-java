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

package dev.cel.optimizer.optimizers;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static com.google.common.truth.extensions.proto.ProtoTruth.assertThat;
import static org.junit.Assert.assertThrows;

import dev.cel.expr.ParsedExpr;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.TextFormat;
import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import dev.cel.bundle.Cel;
import dev.cel.bundle.CelBuilder;
import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.CelFunctionDecl;
import dev.cel.common.CelMutableAst;
import dev.cel.common.CelOptions;
import dev.cel.common.CelOverloadDecl;
import dev.cel.common.CelProtoAbstractSyntaxTree;
import dev.cel.common.CelValidationException;
import dev.cel.common.ast.CelReference;
import dev.cel.common.navigation.CelNavigableMutableAst;
import dev.cel.common.types.ListType;
import dev.cel.common.types.MapType;
import dev.cel.common.types.SimpleType;
import dev.cel.common.types.StructTypeReference;
import dev.cel.expr.conformance.proto2.NestedTestAllTypes;
import dev.cel.expr.conformance.proto2.TestAllTypesProto;
import dev.cel.expr.conformance.proto3.TestAllTypes;
import dev.cel.optimizer.CelAstOptimizer.OptimizationResult;
import dev.cel.optimizer.CelOptimizer;
import dev.cel.optimizer.CelOptimizerFactory;
import dev.cel.optimizer.optimizers.SelectOptimizer.SelectOptimizerOptions;
import dev.cel.parser.CelStandardMacro;
import dev.cel.parser.CelUnparser;
import dev.cel.parser.CelUnparserFactory;
import dev.cel.runtime.CelEvaluationException;
import dev.cel.runtime.CelFunctionBinding;
import dev.cel.runtime.CelRuntime.Program;
import dev.cel.testing.CelRuntimeFlavor;
import java.util.List;
import java.util.stream.LongStream;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(TestParameterInjector.class)
public final class SelectOptimizerTest {

  private static final CelOptions CEL_OPTIONS =
      CelOptions.current()
          .populateMacroCalls(true)
          .enableHeterogeneousNumericComparisons(true)
          .build();

  private static final CelUnparser CEL_UNPARSER = CelUnparserFactory.newUnparser();

  private static final Descriptor PROTO2_TEST_ALL_TYPES_DESCRIPTOR =
      checkNotNull(TestAllTypesProto.getDescriptor().findMessageTypeByName("TestAllTypes"));

  @TestParameter CelRuntimeFlavor runtimeFlavor;

  private Cel cel;
  private CelOptimizer celOptimizer;

  @Before
  public void setUp() {
    cel = setupEnv(runtimeFlavor.builder());
    celOptimizer =
        CelOptimizerFactory.standardCelOptimizerBuilder(cel)
            .addAstOptimizers(
                SelectOptimizer.newInstance(
                    SelectOptimizerOptions.newBuilder().build(),
                    TestAllTypes.getDescriptor().getFile(),
                    PROTO2_TEST_ALL_TYPES_DESCRIPTOR.getFile(),
                    NestedTestAllTypes.getDescriptor().getFile()))
            .build();
  }

  private static Cel setupEnv(CelBuilder celBuilder) {
    return celBuilder
        .setOptions(CEL_OPTIONS)
        .setStandardMacros(CelStandardMacro.STANDARD_MACROS)
        .addMessageTypes(TestAllTypes.getDescriptor())
        .addMessageTypes(PROTO2_TEST_ALL_TYPES_DESCRIPTOR)
        .addMessageTypes(NestedTestAllTypes.getDescriptor())
        .addVar("msg", StructTypeReference.create(TestAllTypes.getDescriptor().getFullName()))
        .addVar(
            "proto2_msg",
            StructTypeReference.create(PROTO2_TEST_ALL_TYPES_DESCRIPTOR.getFullName()))
        .addVar(
            "nested_msg",
            StructTypeReference.create(NestedTestAllTypes.getDescriptor().getFullName()))
        .addVar("map_var", MapType.create(SimpleType.STRING, SimpleType.INT))
        .addVar(
            "map_var_msg",
            MapType.create(
                SimpleType.STRING,
                StructTypeReference.create(TestAllTypes.getDescriptor().getFullName())))
        .addVar("x", SimpleType.INT)
        .build();
  }

  private enum RewriteTestCase {
    // === Selection & Traversal ===
    PROTO3_SINGLE_FIELD_SELECT(
        "msg.single_int64", "cel.@attributeInt(msg, [[2, \"single_int64\", 3, 0]])"),
    PROTO3_SINGLE_MESSAGE_FIELD_SELECT(
        "msg.single_nested_message",
        "cel.@attributeMessage:cel.expr.conformance.proto3.TestAllTypes.NestedMessage(msg, [[21,"
            + " \"single_nested_message\", 11]])"),
    PROTO3_CHAINED_FIELD_SELECT(
        "msg.single_nested_message.bb",
        "cel.@attributeInt(msg, [[21, \"single_nested_message\", 11], [1, \"bb\", 5, 0]])"),
    PROTO2_SINGLE_MESSAGE_FIELD_SELECT(
        "proto2_msg.single_nested_message",
        "cel.@attributeMessage:cel.expr.conformance.proto2.TestAllTypes.NestedMessage(proto2_msg,"
            + " [[21, \"single_nested_message\", 11]])"),
    PROTO2_CHAINED_FIELD_SELECT(
        "proto2_msg.single_nested_message.bb",
        "cel.@attributeInt(proto2_msg, [[21, \"single_nested_message\", 11], [1, \"bb\", 5, 0]])"),
    PROTO2_TRIPLE_CHAINED_FIELD_SELECT(
        "nested_msg.child.payload.single_int64",
        "cel.@attributeInt(nested_msg, "
            + "[[1, \"child\", 11], "
            + "[2, \"payload\", 11], "
            + "[2, \"single_int64\", 3, -64]])"),
    PROTO2_CHAINED_MESSAGE_FIELD_SELECT(
        "nested_msg.child.payload",
        "cel.@attributeMessage:cel.expr.conformance.proto2.TestAllTypes(nested_msg, [[1,"
            + " \"child\", 11], [2, \"payload\", 11]])"),

    // === Presence Tests: Proto2 (Explicit Presence) vs Proto3 (Implicit/Explicit Presence) ===
    // In proto2, scalar fields have explicit presence (has-bit).
    PROTO2_HAS_SCALAR_INT32(
        "has(proto2_msg.single_int32)", "cel.@hasField(proto2_msg, [[1, \"single_int32\"]])"),
    PROTO2_HAS_SCALAR_INT64(
        "has(proto2_msg.single_int64)", "cel.@hasField(proto2_msg, [[2, \"single_int64\"]])"),
    // In proto3, non-optional scalar fields have implicit presence (evaluated as != default).
    PROTO3_HAS_SCALAR_INT32("has(msg.single_int32)", "cel.@hasField(msg, [[1, \"single_int32\"]])"),
    PROTO3_HAS_SCALAR_INT64("has(msg.single_int64)", "cel.@hasField(msg, [[2, \"single_int64\"]])"),
    // In proto3, explicit optional scalars have presence (has-bit).
    PROTO3_HAS_OPTIONAL_BOOL(
        "has(msg.optional_bool)", "cel.@hasField(msg, [[16, \"optional_bool\"]])"),
    PROTO3_HAS_OPTIONAL_STRING(
        "has(msg.optional_string)", "cel.@hasField(msg, [[17, \"optional_string\"]])"),
    // Messages in both proto2 and proto3 have explicit presence.
    PROTO2_HAS_MESSAGE(
        "has(proto2_msg.single_nested_message)",
        "cel.@hasField(proto2_msg, [[21, \"single_nested_message\"]])"),
    PROTO3_HAS_MESSAGE(
        "has(msg.single_nested_message)", "cel.@hasField(msg, [[21, \"single_nested_message\"]])"),
    PROTO3_HAS_STANDALONE_MESSAGE(
        "has(msg.standalone_message)", "cel.@hasField(msg, [[23, \"standalone_message\"]])"),
    PROTO3_HAS_ONEOF_ENUM(
        "has(msg.single_nested_enum)", "cel.@hasField(msg, [[22, \"single_nested_enum\"]])"),
    PROTO2_HAS_CHAINED_MESSAGE(
        "has(proto2_msg.single_nested_message.bb)",
        "cel.@hasField(proto2_msg, [[21, \"single_nested_message\"], [1, \"bb\"]])"),
    PROTO3_HAS_CHAINED_MESSAGE(
        "has(msg.single_nested_message.bb)",
        "cel.@hasField(msg, [[21, \"single_nested_message\"], [1, \"bb\"]])"),
    PROTO2_HAS_TRIPLE_CHAINED_MESSAGE(
        "has(nested_msg.child.payload.single_int64)",
        "cel.@hasField(nested_msg, [[1, \"child\"], [2, \"payload\"], [2, \"single_int64\"]])"),

    // === Default Value Divergence: Proto2 Custom Defaults vs Proto3 Zero Defaults ===
    // Int32: proto2 has custom default -32, proto3 has 0
    PROTO2_CUSTOM_INT32(
        "proto2_msg.single_int32",
        "cel.@attributeInt(proto2_msg, [[1, \"single_int32\", 5, -32]])"),
    PROTO3_ZERO_INT32("msg.single_int32", "cel.@attributeInt(msg, [[1, \"single_int32\", 5, 0]])"),

    // Int64: proto2 has custom default -64, proto3 has 0
    PROTO2_CUSTOM_INT64(
        "proto2_msg.single_int64",
        "cel.@attributeInt(proto2_msg, [[2, \"single_int64\", 3, -64]])"),
    PROTO3_ZERO_INT64("msg.single_int64", "cel.@attributeInt(msg, [[2, \"single_int64\", 3, 0]])"),

    // Uint32: proto2 has custom default 32, proto3 has 0
    PROTO2_CUSTOM_UINT32(
        "proto2_msg.single_uint32",
        "cel.@attributeUint(proto2_msg, [[3, \"single_uint32\", 13, 32u]])"),
    PROTO3_ZERO_UINT32(
        "msg.single_uint32", "cel.@attributeUint(msg, [[3, \"single_uint32\", 13, 0u]])"),

    // Uint64: proto2 has custom default 64, proto3 has 0
    PROTO2_CUSTOM_UINT64(
        "proto2_msg.single_uint64",
        "cel.@attributeUint(proto2_msg, [[4, \"single_uint64\", 4, 64u]])"),
    PROTO3_ZERO_UINT64(
        "msg.single_uint64", "cel.@attributeUint(msg, [[4, \"single_uint64\", 4, 0u]])"),

    // String: proto2 has custom default "empty", proto3 has ""
    PROTO2_CUSTOM_STRING(
        "proto2_msg.single_string",
        "cel.@attributeString(proto2_msg, [[14, \"single_string\", 9, \"empty\"]])"),
    PROTO3_ZERO_STRING(
        "msg.single_string", "cel.@attributeString(msg, [[14, \"single_string\", 9, \"\"]])"),

    // Bool: proto2 has custom default true, proto3 has false
    PROTO2_CUSTOM_BOOL(
        "proto2_msg.single_bool",
        "cel.@attributeBool(proto2_msg, [[13, \"single_bool\", 8, true]])"),
    PROTO3_ZERO_BOOL(
        "msg.single_bool", "cel.@attributeBool(msg, [[13, \"single_bool\", 8, false]])"),

    // Float: proto2 has custom default 3.0, proto3 has 0.0
    PROTO2_CUSTOM_FLOAT(
        "proto2_msg.single_float",
        "cel.@attributeDouble(proto2_msg, [[11, \"single_float\", 2, 3.0]])"),
    PROTO3_ZERO_FLOAT(
        "msg.single_float", "cel.@attributeDouble(msg, [[11, \"single_float\", 2, 0.0]])"),

    // Double: proto2 has custom default 6.4, proto3 has 0.0
    PROTO2_CUSTOM_DOUBLE(
        "proto2_msg.single_double",
        "cel.@attributeDouble(proto2_msg, [[12, \"single_double\", 1, 6.4]])"),
    PROTO3_ZERO_DOUBLE(
        "msg.single_double", "cel.@attributeDouble(msg, [[12, \"single_double\", 1, 0.0]])"),

    // Bytes: proto2 has custom default "none", proto3 has ""
    PROTO2_CUSTOM_BYTES(
        "proto2_msg.single_bytes",
        "cel.@attributeBytes(proto2_msg, [[15, \"single_bytes\", 12, b\"\\156\\157\\156\\145\"]])"),
    PROTO3_ZERO_BYTES(
        "msg.single_bytes", "cel.@attributeBytes(msg, [[15, \"single_bytes\", 12, b\"\"]])"),

    // Enum: proto2 has custom default 1 (BAR), proto3 has 0 (FOO)
    PROTO2_CUSTOM_ENUM(
        "proto2_msg.single_nested_enum",
        "cel.@attributeInt(proto2_msg, [[22, \"single_nested_enum\", 14, 1]])"),
    PROTO3_ZERO_ENUM(
        "msg.single_nested_enum", "cel.@attributeInt(msg, [[22, \"single_nested_enum\", 14, 0]])"),

    // Sint / fixed / sfixed fields
    PROTO3_SINT32("msg.single_sint32", "cel.@attributeInt(msg, [[5, \"single_sint32\", 17, 0]])"),
    PROTO3_SINT64("msg.single_sint64", "cel.@attributeInt(msg, [[6, \"single_sint64\", 18, 0]])"),
    PROTO3_FIXED32(
        "msg.single_fixed32", "cel.@attributeUint(msg, [[7, \"single_fixed32\", 7, 0u]])"),
    PROTO3_FIXED64(
        "msg.single_fixed64", "cel.@attributeUint(msg, [[8, \"single_fixed64\", 6, 0u]])"),
    PROTO3_SFIXED32(
        "msg.single_sfixed32", "cel.@attributeInt(msg, [[9, \"single_sfixed32\", 15, 0]])"),
    PROTO3_SFIXED64(
        "msg.single_sfixed64", "cel.@attributeInt(msg, [[10, \"single_sfixed64\", 16, 0]])"),
    PROTO2_SINT32(
        "proto2_msg.single_sint32",
        "cel.@attributeInt(proto2_msg, [[5, \"single_sint32\", 17, 0]])"),
    PROTO2_SINT64(
        "proto2_msg.single_sint64",
        "cel.@attributeInt(proto2_msg, [[6, \"single_sint64\", 18, 0]])"),
    PROTO2_FIXED32(
        "proto2_msg.single_fixed32",
        "cel.@attributeUint(proto2_msg, [[7, \"single_fixed32\", 7, 0u]])"),
    PROTO2_FIXED64(
        "proto2_msg.single_fixed64",
        "cel.@attributeUint(proto2_msg, [[8, \"single_fixed64\", 6, 0u]])"),
    PROTO2_SFIXED32(
        "proto2_msg.single_sfixed32",
        "cel.@attributeInt(proto2_msg, [[9, \"single_sfixed32\", 15, 0]])"),
    PROTO2_SFIXED64(
        "proto2_msg.single_sfixed64",
        "cel.@attributeInt(proto2_msg, [[10, \"single_sfixed64\", 16, 0]])"),

    // Repeated fields: empty list default
    PROTO2_REPEATED_PRIMITIVE(
        "proto2_msg.repeated_int64",
        "cel.@attributeList(proto2_msg, [[32, \"repeated_int64\", 3, []]])"),
    PROTO3_REPEATED_PRIMITIVE(
        "msg.repeated_int64", "cel.@attributeList(msg, [[32, \"repeated_int64\", 3, []]])"),
    PROTO3_REPEATED_MESSAGE(
        "msg.repeated_nested_message",
        "cel.@attributeList(msg, [[51, \"repeated_nested_message\", 11, []]])"),

    // Well-known types
    PROTO3_TIMESTAMP(
        "msg.single_timestamp",
        "cel.@attributeTimestamp(msg, [[102, \"single_timestamp\", 11, timestamp(0)]])"),
    PROTO3_DURATION(
        "msg.single_duration",
        "cel.@attributeDuration(msg, [[101, \"single_duration\", 11, duration(\"0s\")]])"),

    // Map selects
    MAP_FIELD_INDEXING(
        "msg.map_int64_message[1].bb",
        "cel.@attributeInt("
            + "cel.@attributeMap(msg, [[95, \"map_int64_message\", 20, {}]])[1], "
            + "[[1, \"bb\", 5, 0]])"),
    MAP_FIELD_SELECT_CHAIN_STOPS_AT_MAP_BOUNDARY(
        "map_var_msg.key.single_nested_message.bb",
        "cel.@attributeInt(map_var_msg.key, "
            + "[[21, \"single_nested_message\", 11], "
            + "[1, \"bb\", 5, 0]])"),
    MAP_FIELD_SELECT_STOPS_AT_MAP_BOUNDARY(
        "map_var_msg.key.single_int64",
        "cel.@attributeInt(map_var_msg.key, [[2, \"single_int64\", 3, 0]])"),
    MAP_FIELD_HAS_STOPS_AT_MAP_BOUNDARY(
        "has(map_var_msg.key.single_nested_message)",
        "cel.@hasField(map_var_msg.key, [[21, \"single_nested_message\"]])"),
    MAP_FIELD_HAS_CHAIN_STOPS_AT_MAP_BOUNDARY(
        "has(map_var_msg.key.single_nested_message.bb)",
        "cel.@hasField(map_var_msg.key, [[21, \"single_nested_message\"], [1, \"bb\"]])"),
    PROTO_MAP_FIELD_SELECT_STOPS_AT_MAP_BOUNDARY(
        "msg.map_string_message.key.bb",
        "cel.@attributeInt("
            + "cel.@attributeMap(msg, [[227, \"map_string_message\", 20, {}]]).key, "
            + "[[1, \"bb\", 5, 0]])"),
    PROTO_MAP_FIELD_HAS_STOPS_AT_MAP_BOUNDARY(
        "has(msg.map_string_message.key.bb)",
        "cel.@hasField("
            + "cel.@attributeMap(msg, [[227, \"map_string_message\", 20, {}]]).key, "
            + "[[1, \"bb\"]])"),

    MIXED_BOOLEAN_EXPRESSION(
        "msg.single_int64 > 0 && has(msg.single_nested_message)",
        "cel.@attributeInt(msg, [[2, \"single_int64\", 3, 0]]) > 0 "
            + "&& cel.@hasField(msg, [[21, \"single_nested_message\"]])");

    private final String expression;
    private final String expectedUnparsed;

    RewriteTestCase(String expression, String expectedUnparsed) {
      this.expression = expression;
      this.expectedUnparsed = expectedUnparsed;
    }
  }

  @Test
  public void optimize_rewritesSelectExpressions(@TestParameter RewriteTestCase testCase)
      throws Exception {
    CelAbstractSyntaxTree ast = cel.compile(testCase.expression).getAst();

    CelAbstractSyntaxTree optimizedAst = celOptimizer.optimize(ast);

    assertThat(CEL_UNPARSER.unparse(optimizedAst)).isEqualTo(testCase.expectedUnparsed);
    assertThat(optimizedAst.getSource().getExtensions())
        .contains(SelectOptimizer.SELECT_OPTIMIZATION_AST_EXTENSION_TAG);
  }

  @Test
  public void optimize_unoptimizableMapFieldSelect_leavesAstUntouched() throws Exception {
    CelAbstractSyntaxTree ast = cel.compile("map_var.key").getAst();

    CelAbstractSyntaxTree optimizedAst = celOptimizer.optimize(ast);

    assertThat(CEL_UNPARSER.unparse(optimizedAst)).isEqualTo("map_var.key");
    assertThat(optimizedAst.getSource().getExtensions())
        .doesNotContain(SelectOptimizer.SELECT_OPTIMIZATION_AST_EXTENSION_TAG);
  }

  @Test
  public void optimize_unoptimizableMapHasField_leavesAstUntouched() throws Exception {
    CelAbstractSyntaxTree ast = cel.compile("has(map_var.key)").getAst();

    CelAbstractSyntaxTree optimizedAst = celOptimizer.optimize(ast);

    assertThat(CEL_UNPARSER.unparse(optimizedAst)).isEqualTo("has(map_var.key)");
    assertThat(optimizedAst.getSource().getExtensions())
        .doesNotContain(SelectOptimizer.SELECT_OPTIMIZATION_AST_EXTENSION_TAG);
  }

  @Test
  public void optimize_noSelects_returnsOriginalAst() throws Exception {
    CelAbstractSyntaxTree ast = cel.compile("1 + 2 == 3").getAst();

    CelAbstractSyntaxTree optimizedAst = celOptimizer.optimize(ast);

    assertThat(optimizedAst).isEqualTo(ast);
    assertThat(optimizedAst.getSource().getExtensions())
        .doesNotContain(SelectOptimizer.SELECT_OPTIMIZATION_AST_EXTENSION_TAG);
  }

  @Test
  public void optimize_notCheckedAst_throwsIllegalArgumentException() throws Exception {
    CelAbstractSyntaxTree parsedAst = cel.parse("msg.single_int64").getAst();
    SelectOptimizer optimizer =
        SelectOptimizer.newInstance(SelectOptimizerOptions.newBuilder().build());

    IllegalArgumentException exception =
        assertThrows(IllegalArgumentException.class, () -> optimizer.optimize(parsedAst, cel));

    assertThat(exception).hasMessageThat().contains("AST must be type-checked.");
  }

  @Test
  public void optimize_withFileDescriptors_success() throws Exception {
    FileDescriptor fd = TestAllTypes.getDescriptor().getFile();
    SelectOptimizer customOptimizer =
        SelectOptimizer.newInstance(SelectOptimizerOptions.newBuilder().build(), fd);
    CelOptimizer optimizer =
        CelOptimizerFactory.standardCelOptimizerBuilder(cel)
            .addAstOptimizers(customOptimizer)
            .build();
    CelAbstractSyntaxTree ast = cel.compile("msg.single_int64").getAst();

    CelAbstractSyntaxTree optimizedAst = optimizer.optimize(ast);

    assertThat(CEL_UNPARSER.unparse(optimizedAst))
        .isEqualTo("cel.@attributeInt(msg, [[2, \"single_int64\", 3, 0]])");
  }

  @Test
  public void optimize_withFileDescriptorsIterable_success() throws Exception {
    FileDescriptor fd = TestAllTypes.getDescriptor().getFile();
    SelectOptimizer customOptimizer =
        SelectOptimizer.newInstance(
            SelectOptimizerOptions.newBuilder().build(), ImmutableList.of(fd));
    CelOptimizer optimizer =
        CelOptimizerFactory.standardCelOptimizerBuilder(cel)
            .addAstOptimizers(customOptimizer)
            .build();
    CelAbstractSyntaxTree ast = cel.compile("msg.single_int64").getAst();

    CelAbstractSyntaxTree optimizedAst = optimizer.optimize(ast);

    assertThat(CEL_UNPARSER.unparse(optimizedAst))
        .isEqualTo("cel.@attributeInt(msg, [[2, \"single_int64\", 3, 0]])");
  }

  @Test
  public void newInstance_withOptionsAndFileDescriptors_preservesAddedDescriptors()
      throws Exception {
    FileDescriptor fd = PROTO2_TEST_ALL_TYPES_DESCRIPTOR.getFile();
    SelectOptimizerOptions baseOptions =
        SelectOptimizerOptions.newBuilder().enableLinkedMessageTypes(false).build();
    SelectOptimizer optimizer = SelectOptimizer.newInstance(baseOptions, fd);
    CelAbstractSyntaxTree ast = cel.compile("proto2_msg.single_int64").getAst();

    CelAbstractSyntaxTree optimizedAst = optimizer.optimize(ast, cel).optimizedAst();

    assertThat(CEL_UNPARSER.unparse(optimizedAst))
        .isEqualTo("cel.@attributeInt(proto2_msg, [[2, \"single_int64\", 3, -64]])");
  }

  @Test
  public void optimize_defaultOptions_populatesMacroCalls() throws Exception {
    CelAbstractSyntaxTree ast =
        cel.compile("[1].exists(x, x > 0) && msg.single_int64 > 0").getAst();

    CelAbstractSyntaxTree optimizedAst = celOptimizer.optimize(ast);

    assertThat(optimizedAst.getSource().getMacroCalls()).isNotEmpty();
  }

  @Test
  public void optimize_hasFieldMacroCall_removesHasMacroCallFromSource() throws Exception {
    CelAbstractSyntaxTree ast = cel.compile("has(msg.single_int64)").getAst();

    CelAbstractSyntaxTree optimizedAst = celOptimizer.optimize(ast);

    assertThat(optimizedAst.getSource().getMacroCalls()).isEmpty();
  }

  @Test
  public void optimize_renumbersIdsConsecutively() throws Exception {
    CelAbstractSyntaxTree ast = cel.compile("msg.single_nested_message.bb").getAst();

    CelAbstractSyntaxTree optimizedAst = celOptimizer.optimize(ast);

    CelNavigableMutableAst navAst =
        CelNavigableMutableAst.fromAst(CelMutableAst.fromCelAst(optimizedAst));
    ImmutableList<Long> ids =
        navAst
            .getRoot()
            .allNodes()
            .map(node -> node.expr().id())
            .sorted()
            .collect(toImmutableList());
    ImmutableList<Long> expectedIds =
        LongStream.rangeClosed(1, ids.size()).boxed().collect(toImmutableList());
    assertThat(ids).containsExactlyElementsIn(expectedIds).inOrder();
  }

  @Test
  public void optimizeAndEvaluate_withAttributeFunctionBinding_evaluatesSuccessfully()
      throws Exception {
    Cel celWithBinding =
        cel.toCelBuilder()
            .addFunctionDeclarations(SelectOptimizer.CEL_ATTRIBUTE_INT_FUNCTION_DECL)
            .addFunctionBindings(
                CelFunctionBinding.from(
                    "cel_attribute_int_list", Object.class, List.class, (target, path) -> 42L))
            .build();
    CelOptimizer optimizer =
        CelOptimizerFactory.standardCelOptimizerBuilder(celWithBinding)
            .addAstOptimizers(
                SelectOptimizer.newInstance(
                    SelectOptimizerOptions.newBuilder().build(),
                    TestAllTypes.getDescriptor().getFile()))
            .build();
    CelAbstractSyntaxTree ast = celWithBinding.compile("msg.single_int64").getAst();

    CelAbstractSyntaxTree optimizedAst = optimizer.optimize(ast);
    Object result =
        celWithBinding
            .createProgram(optimizedAst)
            .eval(ImmutableMap.of("msg", TestAllTypes.getDefaultInstance()));

    assertThat(result).isEqualTo(42L);
  }

  @Test
  public void optimizeAndEvaluate_withChainedMessageSelect_unpacksTuplesSuccessfully()
      throws Exception {
    Cel celWithBinding =
        cel.toCelBuilder()
            .addFunctionDeclarations(SelectOptimizer.CEL_ATTRIBUTE_INT_FUNCTION_DECL)
            .addFunctionBindings(
                CelFunctionBinding.from(
                    "cel_attribute_int_list", Object.class, List.class, (target, path) -> path))
            .build();
    CelOptimizer optimizer =
        CelOptimizerFactory.standardCelOptimizerBuilder(celWithBinding)
            .addAstOptimizers(
                SelectOptimizer.newInstance(
                    SelectOptimizerOptions.newBuilder().build(),
                    TestAllTypes.getDescriptor().getFile()))
            .build();
    CelAbstractSyntaxTree ast = celWithBinding.compile("msg.single_nested_message.bb").getAst();

    CelAbstractSyntaxTree optimizedAst = optimizer.optimize(ast);
    Object result =
        celWithBinding
            .createProgram(optimizedAst)
            .eval(ImmutableMap.of("msg", TestAllTypes.getDefaultInstance()));

    assertThat((List<?>) result)
        .containsExactly(
            ImmutableList.of(21L, "single_nested_message", 11L), ImmutableList.of(1L, "bb", 5L, 0L))
        .inOrder();
  }

  @Test
  public void optimizeAndEvaluate_withHasFieldFunctionBinding_evaluatesSuccessfully()
      throws Exception {
    Cel celWithBinding =
        cel.toCelBuilder()
            .addFunctionDeclarations(SelectOptimizer.CEL_HAS_FIELD_FUNCTION_DECL)
            .addFunctionBindings(
                CelFunctionBinding.from(
                    "cel_has_field_list", Object.class, List.class, (target, path) -> true))
            .build();
    CelOptimizer optimizer =
        CelOptimizerFactory.standardCelOptimizerBuilder(celWithBinding)
            .addAstOptimizers(
                SelectOptimizer.newInstance(
                    SelectOptimizerOptions.newBuilder().build(),
                    TestAllTypes.getDescriptor().getFile()))
            .build();
    CelAbstractSyntaxTree ast = celWithBinding.compile("has(msg.single_int64)").getAst();

    CelAbstractSyntaxTree optimizedAst = optimizer.optimize(ast);
    Object result =
        celWithBinding
            .createProgram(optimizedAst)
            .eval(ImmutableMap.of("msg", TestAllTypes.getDefaultInstance()));

    assertThat(result).isEqualTo(true);
  }

  @Test
  public void optimizeAndEvaluate_withSelectOnMapValue_evaluatesSuccessfully() throws Exception {
    Cel celWithBinding =
        cel.toCelBuilder()
            .addFunctionDeclarations(SelectOptimizer.CEL_ATTRIBUTE_INT_FUNCTION_DECL)
            .addFunctionBindings(
                CelFunctionBinding.from(
                    "cel_attribute_int_list", Object.class, List.class, (target, path) -> 42L))
            .build();
    CelOptimizer optimizer =
        CelOptimizerFactory.standardCelOptimizerBuilder(celWithBinding)
            .addAstOptimizers(
                SelectOptimizer.newInstance(
                    SelectOptimizerOptions.newBuilder().build(),
                    TestAllTypes.getDescriptor().getFile()))
            .build();
    CelAbstractSyntaxTree ast = celWithBinding.compile("map_var_msg.key.single_int64").getAst();

    CelAbstractSyntaxTree optimizedAst = optimizer.optimize(ast);
    Object result =
        celWithBinding
            .createProgram(optimizedAst)
            .eval(
                ImmutableMap.of(
                    "map_var_msg", ImmutableMap.of("key", TestAllTypes.getDefaultInstance())));

    assertThat(result).isEqualTo(42L);
    assertThat(optimizedAst.getSource().getExtensions())
        .contains(SelectOptimizer.SELECT_OPTIMIZATION_AST_EXTENSION_TAG);
  }

  @Test
  public void optimizeAndEvaluate_withHasOnMapValue_evaluatesSuccessfully() throws Exception {
    Cel celWithBinding =
        cel.toCelBuilder()
            .addFunctionDeclarations(SelectOptimizer.CEL_HAS_FIELD_FUNCTION_DECL)
            .addFunctionBindings(
                CelFunctionBinding.from(
                    "cel_has_field_list", Object.class, List.class, (target, path) -> true))
            .build();
    CelOptimizer optimizer =
        CelOptimizerFactory.standardCelOptimizerBuilder(celWithBinding)
            .addAstOptimizers(
                SelectOptimizer.newInstance(
                    SelectOptimizerOptions.newBuilder().build(),
                    TestAllTypes.getDescriptor().getFile()))
            .build();
    CelAbstractSyntaxTree ast =
        celWithBinding.compile("has(map_var_msg.key.single_nested_message)").getAst();

    CelAbstractSyntaxTree optimizedAst = optimizer.optimize(ast);
    Object result =
        celWithBinding
            .createProgram(optimizedAst)
            .eval(
                ImmutableMap.of(
                    "map_var_msg", ImmutableMap.of("key", TestAllTypes.getDefaultInstance())));

    assertThat(result).isEqualTo(true);
    assertThat(optimizedAst.getSource().getExtensions())
        .contains(SelectOptimizer.SELECT_OPTIMIZATION_AST_EXTENSION_TAG);
  }

  @Test
  public void optimizeAndEvaluate_withMissingMapKey_throwsEvaluationException() throws Exception {
    Cel celWithBinding =
        cel.toCelBuilder()
            .addFunctionDeclarations(SelectOptimizer.CEL_ATTRIBUTE_INT_FUNCTION_DECL)
            .addFunctionBindings(
                CelFunctionBinding.from(
                    "cel_attribute_int_list", Object.class, List.class, (target, path) -> 42L))
            .build();
    CelOptimizer optimizer =
        CelOptimizerFactory.standardCelOptimizerBuilder(celWithBinding)
            .addAstOptimizers(
                SelectOptimizer.newInstance(
                    SelectOptimizerOptions.newBuilder().build(),
                    TestAllTypes.getDescriptor().getFile()))
            .build();
    CelAbstractSyntaxTree ast = celWithBinding.compile("map_var_msg.key.single_int64").getAst();

    CelAbstractSyntaxTree optimizedAst = optimizer.optimize(ast);
    Program program = celWithBinding.createProgram(optimizedAst);
    ImmutableMap<String, Object> input = ImmutableMap.of("map_var_msg", ImmutableMap.of());

    CelEvaluationException exception =
        assertThrows(CelEvaluationException.class, () -> program.eval(input));

    assertThat(exception).hasMessageThat().contains("key 'key' is not present in map");
  }

  @Test
  public void options_toBuilder_preservesValues() {
    SelectOptimizerOptions options =
        SelectOptimizerOptions.newBuilder()
            .iterationLimit(100)
            .enableLinkedMessageTypes(false)
            .build();

    SelectOptimizerOptions copiedOptions = options.toBuilder().iterationLimit(200).build();

    assertThat(copiedOptions.iterationLimit()).isEqualTo(200);
    assertThat(copiedOptions.enableLinkedMessageTypes()).isFalse();
  }

  @Test
  public void optimize_iterationLimitReached_throws() throws Exception {
    CelAbstractSyntaxTree ast =
        cel.compile("msg.single_int64 + msg.single_int64 + msg.single_int64").getAst();
    SelectOptimizer optimizer =
        SelectOptimizer.newInstance(
            SelectOptimizerOptions.newBuilder().iterationLimit(2).build(),
            TestAllTypes.getDescriptor().getFile());

    IllegalStateException e =
        assertThrows(IllegalStateException.class, () -> optimizer.optimize(ast, cel));

    assertThat(e).hasMessageThat().isEqualTo("Max iteration count reached.");
  }

  @Test
  public void optimize_structField_throwsUnsupportedOperationException() throws Exception {
    CelAbstractSyntaxTree ast = cel.compile("msg.single_struct").getAst();
    SelectOptimizer optimizer = SelectOptimizer.newInstance(TestAllTypes.getDescriptor().getFile());

    UnsupportedOperationException e =
        assertThrows(UnsupportedOperationException.class, () -> optimizer.optimize(ast, cel));

    assertThat(e)
        .hasMessageThat()
        .contains("Optimization of Struct fields is currently unimplemented");
  }

  @Test
  public void optimize_listValueField_throwsUnsupportedOperationException() throws Exception {
    CelAbstractSyntaxTree ast = cel.compile("msg.list_value").getAst();
    SelectOptimizer optimizer = SelectOptimizer.newInstance(TestAllTypes.getDescriptor().getFile());

    UnsupportedOperationException e =
        assertThrows(UnsupportedOperationException.class, () -> optimizer.optimize(ast, cel));

    assertThat(e)
        .hasMessageThat()
        .contains("Optimization of ListValue fields is currently unimplemented");
  }

  @Test
  public void optimize_valueField_throwsUnsupportedOperationException() throws Exception {
    CelAbstractSyntaxTree ast = cel.compile("msg.single_value").getAst();
    SelectOptimizer optimizer = SelectOptimizer.newInstance(TestAllTypes.getDescriptor().getFile());

    UnsupportedOperationException e =
        assertThrows(UnsupportedOperationException.class, () -> optimizer.optimize(ast, cel));

    assertThat(e)
        .hasMessageThat()
        .contains("Optimization of Value fields is currently unimplemented");
  }

  @Test
  public void optimize_anyField_throwsUnsupportedOperationException() throws Exception {
    CelAbstractSyntaxTree ast = cel.compile("msg.single_any").getAst();
    SelectOptimizer optimizer = SelectOptimizer.newInstance(TestAllTypes.getDescriptor().getFile());

    UnsupportedOperationException e =
        assertThrows(UnsupportedOperationException.class, () -> optimizer.optimize(ast, cel));

    assertThat(e)
        .hasMessageThat()
        .contains("Optimization of Any fields is currently unimplemented");
  }

  @Test
  public void optimize_wrapperField_throwsUnsupportedOperationException() throws Exception {
    CelAbstractSyntaxTree ast = cel.compile("msg.single_int64_wrapper").getAst();
    SelectOptimizer optimizer = SelectOptimizer.newInstance(TestAllTypes.getDescriptor().getFile());

    UnsupportedOperationException e =
        assertThrows(UnsupportedOperationException.class, () -> optimizer.optimize(ast, cel));

    assertThat(e)
        .hasMessageThat()
        .contains("Optimization of wrapper fields is currently unimplemented");
  }

  @Test
  public void optimize_groupField_throwsUnsupportedOperationException() throws Exception {
    CelAbstractSyntaxTree ast = cel.compile("proto2_msg.nestedgroup.single_id").getAst();
    SelectOptimizer optimizer =
        SelectOptimizer.newInstance(PROTO2_TEST_ALL_TYPES_DESCRIPTOR.getFile());

    UnsupportedOperationException e =
        assertThrows(UnsupportedOperationException.class, () -> optimizer.optimize(ast, cel));

    assertThat(e).hasMessageThat().contains("Optimization of Group fields is unsupported");
  }

  @Test
  public void newInstance_fileDescriptorsVarargs_defaultOptions_success() throws Exception {
    FileDescriptor fd = PROTO2_TEST_ALL_TYPES_DESCRIPTOR.getFile();
    SelectOptimizer optimizer = SelectOptimizer.newInstance(fd);
    CelAbstractSyntaxTree ast = cel.compile("proto2_msg.single_int64").getAst();

    CelAbstractSyntaxTree optimizedAst = optimizer.optimize(ast, cel).optimizedAst();

    assertThat(CEL_UNPARSER.unparse(optimizedAst))
        .isEqualTo("cel.@attributeInt(proto2_msg, [[2, \"single_int64\", 3, -64]])");
  }

  @Test
  public void newInstance_fileDescriptorsIterable_defaultOptions_success() throws Exception {
    FileDescriptor fd = PROTO2_TEST_ALL_TYPES_DESCRIPTOR.getFile();
    SelectOptimizer optimizer = SelectOptimizer.newInstance(ImmutableList.of(fd));
    CelAbstractSyntaxTree ast = cel.compile("proto2_msg.single_int64").getAst();

    CelAbstractSyntaxTree optimizedAst = optimizer.optimize(ast, cel).optimizedAst();

    assertThat(CEL_UNPARSER.unparse(optimizedAst))
        .isEqualTo("cel.@attributeInt(proto2_msg, [[2, \"single_int64\", 3, -64]])");
  }

  @Test
  public void
      newInstance_addFileDescriptorsVarargs_withLinkedDescriptorsDisabled_doesNotOptimizeUnprovidedDescriptors()
          throws Exception {
    FileDescriptor fd = PROTO2_TEST_ALL_TYPES_DESCRIPTOR.getFile();
    SelectOptimizer optimizer =
        SelectOptimizer.newInstance(
            SelectOptimizerOptions.newBuilder().enableLinkedMessageTypes(false).build(), fd);
    CelAbstractSyntaxTree proto2Ast = cel.compile("proto2_msg.single_int64").getAst();
    CelAbstractSyntaxTree proto3Ast = cel.compile("msg.single_int64").getAst();

    CelAbstractSyntaxTree proto2Optimized = optimizer.optimize(proto2Ast, cel).optimizedAst();
    CelAbstractSyntaxTree proto3Optimized = optimizer.optimize(proto3Ast, cel).optimizedAst();

    assertThat(CEL_UNPARSER.unparse(proto2Optimized))
        .isEqualTo("cel.@attributeInt(proto2_msg, [[2, \"single_int64\", 3, -64]])");
    assertThat(CEL_UNPARSER.unparse(proto3Optimized)).isEqualTo("msg.single_int64");
  }

  @Test
  public void
      newInstance_addFileDescriptorsIterable_withLinkedDescriptorsDisabled_doesNotOptimizeUnprovidedDescriptors()
          throws Exception {
    FileDescriptor fd = PROTO2_TEST_ALL_TYPES_DESCRIPTOR.getFile();
    SelectOptimizer optimizer =
        SelectOptimizer.newInstance(
            SelectOptimizerOptions.newBuilder().enableLinkedMessageTypes(false).build(),
            ImmutableList.of(fd));
    CelAbstractSyntaxTree proto2Ast = cel.compile("proto2_msg.single_int64").getAst();
    CelAbstractSyntaxTree proto3Ast = cel.compile("msg.single_int64").getAst();

    CelAbstractSyntaxTree proto2Optimized = optimizer.optimize(proto2Ast, cel).optimizedAst();
    CelAbstractSyntaxTree proto3Optimized = optimizer.optimize(proto3Ast, cel).optimizedAst();

    assertThat(CEL_UNPARSER.unparse(proto2Optimized))
        .isEqualTo("cel.@attributeInt(proto2_msg, [[2, \"single_int64\", 3, -64]])");
    assertThat(CEL_UNPARSER.unparse(proto3Optimized)).isEqualTo("msg.single_int64");
  }

  private enum CompilerRejectionTestCase {
    ATTRIBUTE_INT_AT_SIGN(
        SelectOptimizer.CEL_ATTRIBUTE_INT_FUNCTION_DECL,
        "cel.@attributeInt(msg, [])",
        "token recognition error at: '@'"),
    ATTRIBUTE_INT_OVERLOAD(
        SelectOptimizer.CEL_ATTRIBUTE_INT_FUNCTION_DECL,
        "cel_attribute_int_list(msg, [])",
        "undeclared reference to 'cel_attribute_int_list'"),
    HAS_FIELD_AT_SIGN(
        SelectOptimizer.CEL_HAS_FIELD_FUNCTION_DECL,
        "cel.@hasField(msg, [])",
        "token recognition error at: '@'"),
    HAS_FIELD_OVERLOAD(
        SelectOptimizer.CEL_HAS_FIELD_FUNCTION_DECL,
        "cel_has_field_list(msg, [])",
        "undeclared reference to 'cel_has_field_list'");

    private final CelFunctionDecl functionDecl;
    private final String expression;
    private final String expectedErrorMessage;

    CompilerRejectionTestCase(
        CelFunctionDecl functionDecl, String expression, String expectedErrorMessage) {
      this.functionDecl = functionDecl;
      this.expression = expression;
      this.expectedErrorMessage = expectedErrorMessage;
    }
  }

  @Test
  public void compile_sourceWithInternalFunctionCall_failsCompilation(
      @TestParameter CompilerRejectionTestCase testCase) {
    Cel celWithDecl = cel.toCelBuilder().addFunctionDeclarations(testCase.functionDecl).build();

    CelValidationException e =
        assertThrows(
            CelValidationException.class, () -> celWithDecl.compile(testCase.expression).getAst());

    assertThat(e).hasMessageThat().contains(testCase.expectedErrorMessage);
  }

  @Test
  public void optimize_binaryAddition_resolvesSingleOverload() throws Exception {
    CelAbstractSyntaxTree ast = cel.compile("msg.single_int64 + msg.single_sfixed64").getAst();

    CelAbstractSyntaxTree optimizedAst = celOptimizer.optimize(ast);

    assertThat(CEL_UNPARSER.unparse(optimizedAst))
        .isEqualTo(
            "cel.@attributeInt(msg, [[2, \"single_int64\", 3, 0]]) + "
                + "cel.@attributeInt(msg, [[10, \"single_sfixed64\", 16, 0]])");
    assertThat(optimizedAst.isChecked()).isTrue();
  }

  @Test
  public void optimize_stringConcatenation_resolvesSingleOverload() throws Exception {
    CelAbstractSyntaxTree ast = cel.compile("msg.single_string + 'suffix'").getAst();

    CelAbstractSyntaxTree optimizedAst = celOptimizer.optimize(ast);

    assertThat(CEL_UNPARSER.unparse(optimizedAst))
        .isEqualTo("cel.@attributeString(msg, [[14, \"single_string\", 9, \"\"]]) + \"suffix\"");
    assertThat(optimizedAst.isChecked()).isTrue();
  }

  @Test
  public void optimize_chainedSelectInBinaryExpression_resolvesSingleOverload() throws Exception {
    CelAbstractSyntaxTree ast = cel.compile("msg.single_nested_message.bb + 1").getAst();

    CelAbstractSyntaxTree optimizedAst = celOptimizer.optimize(ast);

    assertThat(CEL_UNPARSER.unparse(optimizedAst))
        .isEqualTo(
            "cel.@attributeInt(msg, [[21, \"single_nested_message\", 11], [1, \"bb\", 5, 0]])"
                + " + 1");
    assertThat(optimizedAst.isChecked()).isTrue();
  }

  private enum OverloadResolutionTestCase {
    INT_ADDITION("msg.single_int64 + msg.single_sfixed64", "add_int64"),
    UINT_ADDITION("msg.single_uint64 + msg.single_fixed64", "add_uint64"),
    DOUBLE_ADDITION("msg.single_double + msg.single_float", "add_double"),
    STRING_ADDITION("msg.single_string + 'suffix'", "add_string"),
    BYTES_ADDITION("msg.single_bytes + b'suffix'", "add_bytes"),
    TIMESTAMP_DURATION_ADDITION(
        "msg.single_timestamp + msg.single_duration", "add_timestamp_duration"),
    LOGICAL_AND("msg.single_bool && true", "logical_and");

    private final String expression;
    private final String expectedOverloadId;

    OverloadResolutionTestCase(String expression, String expectedOverloadId) {
      this.expression = expression;
      this.expectedOverloadId = expectedOverloadId;
    }
  }

  @Test
  public void optimize_binaryOperation_hasUnambiguousOverloadReference(
      @TestParameter OverloadResolutionTestCase testCase) throws Exception {
    CelAbstractSyntaxTree ast = cel.compile(testCase.expression).getAst();

    CelAbstractSyntaxTree optimizedAst = celOptimizer.optimize(ast);

    // Root node is the binary call.
    assertThat(
            optimizedAst.getReference(optimizedAst.getExpr().id()).map(CelReference::overloadIds))
        .hasValue(ImmutableList.of(testCase.expectedOverloadId));
  }

  @Test
  public void optimize_binaryOperation_referenceMapContainsOnlySingularOverload(
      @TestParameter OverloadResolutionTestCase testCase) throws Exception {
    CelAbstractSyntaxTree ast = cel.compile(testCase.expression).getAst();

    CelAbstractSyntaxTree optimizedAst = celOptimizer.optimize(ast);

    // Verify all call references in the AST have exactly one resolved overload, never a list of
    // candidates.
    ImmutableList<CelReference> callReferences =
        optimizedAst.getReferenceMap().values().stream()
            .filter(ref -> !ref.overloadIds().isEmpty())
            .collect(toImmutableList());
    assertThat(callReferences).isNotEmpty();
    for (CelReference ref : callReferences) {
      assertWithMessage("Call reference %s must have exactly 1 resolved overload", ref)
          .that(ref.overloadIds())
          .hasSize(1);
    }
  }

  @Test
  public void optimize_pipelineWithDownstreamOptimizer_generatesSingularOverload()
      throws Exception {
    CelOptimizer chainedOptimizer =
        CelOptimizerFactory.standardCelOptimizerBuilder(cel)
            .addAstOptimizers(
                SelectOptimizer.newInstance(TestAllTypes.getDescriptor().getFile()),
                ConstantFoldingOptimizer.getInstance())
            .build();
    CelAbstractSyntaxTree ast = cel.compile("msg.single_int64 + (1 + 2)").getAst();

    CelAbstractSyntaxTree optimizedAst = chainedOptimizer.optimize(ast);

    assertThat(CEL_UNPARSER.unparse(optimizedAst))
        .isEqualTo("cel.@attributeInt(msg, [[2, \"single_int64\", 3, 0]]) + 3");
    assertThat(
            optimizedAst.getReference(optimizedAst.getExpr().id()).map(CelReference::overloadIds))
        .hasValue(ImmutableList.of("add_int64"));
  }

  @Test
  public void optimize_messageEquality_typeChecksSuccessfully() throws Exception {
    CelAbstractSyntaxTree ast =
        cel.compile("msg.single_nested_message == msg.single_nested_message").getAst();

    CelAbstractSyntaxTree optimizedAst = celOptimizer.optimize(ast);

    assertThat(CEL_UNPARSER.unparse(optimizedAst))
        .isEqualTo(
            "cel.@attributeMessage:cel.expr.conformance.proto3.TestAllTypes.NestedMessage(msg,"
                + " [[21, \"single_nested_message\", 11]]) == "
                + "cel.@attributeMessage:cel.expr.conformance.proto3.TestAllTypes.NestedMessage(msg,"
                + " [[21, \"single_nested_message\", 11]])");
    assertThat(optimizedAst.isChecked()).isTrue();
  }

  @Test
  public void optimize_messagePassedToCustomFunction_typeChecksSuccessfully() throws Exception {
    Cel celWithFn =
        cel.toCelBuilder()
            .addFunctionDeclarations(
                CelFunctionDecl.newFunctionDeclaration(
                    "takeNested",
                    CelOverloadDecl.newGlobalOverload(
                        "take_nested",
                        SimpleType.BOOL,
                        StructTypeReference.create(
                            TestAllTypes.NestedMessage.getDescriptor().getFullName()))))
            .build();
    CelOptimizer optimizer =
        CelOptimizerFactory.standardCelOptimizerBuilder(celWithFn)
            .addAstOptimizers(SelectOptimizer.newInstance(TestAllTypes.getDescriptor().getFile()))
            .build();
    CelAbstractSyntaxTree ast = celWithFn.compile("takeNested(msg.single_nested_message)").getAst();

    CelAbstractSyntaxTree optimizedAst = optimizer.optimize(ast);

    assertThat(CEL_UNPARSER.unparse(optimizedAst))
        .isEqualTo(
            "takeNested(cel.@attributeMessage:cel.expr.conformance.proto3.TestAllTypes.NestedMessage(msg,"
                + " [[21, \"single_nested_message\", 11]]))");
    assertThat(optimizedAst.isChecked()).isTrue();
  }

  @Test
  public void optimize_overloadedMessageFunction_resolvesSingleOverload() throws Exception {
    Cel celWithFn =
        cel.toCelBuilder()
            .addFunctionDeclarations(
                CelFunctionDecl.newFunctionDeclaration(
                    "processMsg",
                    CelOverloadDecl.newGlobalOverload(
                        "process_nested",
                        SimpleType.BOOL,
                        StructTypeReference.create(
                            TestAllTypes.NestedMessage.getDescriptor().getFullName())),
                    CelOverloadDecl.newGlobalOverload(
                        "process_all_types",
                        SimpleType.BOOL,
                        StructTypeReference.create(TestAllTypes.getDescriptor().getFullName()))))
            .build();
    CelOptimizer optimizer =
        CelOptimizerFactory.standardCelOptimizerBuilder(celWithFn)
            .addAstOptimizers(SelectOptimizer.newInstance(TestAllTypes.getDescriptor().getFile()))
            .build();
    CelAbstractSyntaxTree ast = celWithFn.compile("processMsg(msg.single_nested_message)").getAst();

    CelAbstractSyntaxTree optimizedAst = optimizer.optimize(ast);

    assertThat(
            optimizedAst.getReference(optimizedAst.getExpr().id()).map(CelReference::overloadIds))
        .hasValue(ImmutableList.of("process_nested"));
    assertThat(optimizedAst.getResultType()).isEqualTo(SimpleType.BOOL);
  }

  @Test
  public void optimize_newFunctionDecls_containsDynamicMessageFunctionDeclAndDeduplicates()
      throws Exception {
    SelectOptimizer optimizer = SelectOptimizer.newInstance(TestAllTypes.getDescriptor().getFile());
    CelAbstractSyntaxTree ast =
        cel.compile("msg.single_nested_message == msg.single_nested_message").getAst();

    OptimizationResult result = optimizer.optimize(ast, cel);

    String expectedFunctionName =
        "cel.@attributeMessage:" + TestAllTypes.NestedMessage.getDescriptor().getFullName();
    ImmutableList<CelFunctionDecl> dynamicDecls =
        result.newFunctionDecls().stream()
            .filter(decl -> decl.name().equals(expectedFunctionName))
            .collect(toImmutableList());
    CelFunctionDecl expectedDecl =
        CelFunctionDecl.newFunctionDeclaration(
            expectedFunctionName,
            CelOverloadDecl.newGlobalOverload(
                "cel_attribute_message_"
                    + TestAllTypes.NestedMessage.getDescriptor().getFullName().replace('.', '_'),
                StructTypeReference.create(
                    TestAllTypes.NestedMessage.getDescriptor().getFullName()),
                SimpleType.DYN,
                ListType.create(SimpleType.DYN)));

    assertThat(dynamicDecls).containsExactly(expectedDecl);
  }

  @Test
  public void optimize_toParsedExpr_matchesExpectedSerializedProto() throws Exception {
    CelAbstractSyntaxTree ast = cel.compile("msg.single_nested_message.bb").getAst();
    ParsedExpr expectedParsedExpr =
        TextFormat.parse(
            "expr {\n"
                + "  id: 1\n"
                + "  call_expr {\n"
                + "    function: \"cel.@attributeInt\"\n"
                + "    args {\n"
                + "      id: 2\n"
                + "      ident_expr {\n"
                + "        name: \"msg\"\n"
                + "      }\n"
                + "    }\n"
                + "    args {\n"
                + "      id: 3\n"
                + "      list_expr {\n"
                + "        elements {\n"
                + "          id: 4\n"
                + "          list_expr {\n"
                + "            elements {\n"
                + "              id: 5\n"
                + "              const_expr {\n"
                + "                int64_value: 21\n"
                + "              }\n"
                + "            }\n"
                + "            elements {\n"
                + "              id: 6\n"
                + "              const_expr {\n"
                + "                string_value: \"single_nested_message\"\n"
                + "              }\n"
                + "            }\n"
                + "            elements {\n"
                + "              id: 7\n"
                + "              const_expr {\n"
                + "                int64_value: 11\n"
                + "              }\n"
                + "            }\n"
                + "          }\n"
                + "        }\n"
                + "        elements {\n"
                + "          id: 8\n"
                + "          list_expr {\n"
                + "            elements {\n"
                + "              id: 9\n"
                + "              const_expr {\n"
                + "                int64_value: 1\n"
                + "              }\n"
                + "            }\n"
                + "            elements {\n"
                + "              id: 10\n"
                + "              const_expr {\n"
                + "                string_value: \"bb\"\n"
                + "              }\n"
                + "            }\n"
                + "            elements {\n"
                + "              id: 11\n"
                + "              const_expr {\n"
                + "                int64_value: 5\n"
                + "              }\n"
                + "            }\n"
                + "            elements {\n"
                + "              id: 12\n"
                + "              const_expr {\n"
                + "                int64_value: 0\n"
                + "              }\n"
                + "            }\n"
                + "          }\n"
                + "        }\n"
                + "      }\n"
                + "    }\n"
                + "  }\n"
                + "}\n"
                + "source_info {\n"
                + "  location: \"<input>\"\n"
                + "  extensions {\n"
                + "    id: \"select_optimization\"\n"
                + "    affected_components: COMPONENT_RUNTIME\n"
                + "    version {\n"
                + "      major: 1\n"
                + "    }\n"
                + "  }\n"
                + "}\n",
            ParsedExpr.class);

    CelAbstractSyntaxTree optimizedAst = celOptimizer.optimize(ast);
    ParsedExpr parsedExpr = CelProtoAbstractSyntaxTree.fromCelAst(optimizedAst).toParsedExpr();

    assertThat(parsedExpr).isEqualTo(expectedParsedExpr);
  }
}
