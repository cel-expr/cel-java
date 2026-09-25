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
import static com.google.common.truth.extensions.proto.ProtoTruth.assertThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertThrows;
import static org.junit.Assume.assumeTrue;

import dev.cel.expr.ParsedExpr;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.primitives.UnsignedLong;
import com.google.protobuf.Any;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.Int64Value;
import com.google.protobuf.Struct;
import com.google.protobuf.TextFormat;
import com.google.protobuf.Value;
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
import dev.cel.common.types.MapType;
import dev.cel.common.types.SimpleType;
import dev.cel.common.types.StructTypeReference;
import dev.cel.common.values.CelByteString;
import dev.cel.expr.conformance.proto2.NestedTestAllTypes;
import dev.cel.expr.conformance.proto2.TestAllTypesProto;
import dev.cel.expr.conformance.proto3.TestAllTypes;
import dev.cel.extensions.CelExtensions;
import dev.cel.optimizer.CelAstOptimizer;
import dev.cel.optimizer.CelOptimizer;
import dev.cel.optimizer.CelOptimizerFactory;
import dev.cel.optimizer.optimizers.SelectOptimizer.SelectOptimizerOptions;
import dev.cel.parser.CelStandardMacro;
import dev.cel.parser.CelUnparser;
import dev.cel.parser.CelUnparserFactory;
import dev.cel.runtime.CelEvaluationException;
import dev.cel.runtime.CelRuntime.Program;
import dev.cel.testing.CelRuntimeFlavor;
import java.time.Duration;
import java.time.Instant;
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
        "msg.single_int64", "cel.@attribute(msg, [[2, \"single_int64\", 3, 0]], int)"),
    PROTO3_SINGLE_MESSAGE_FIELD_SELECT(
        "msg.single_nested_message",
        "cel.@attribute(msg, [[21, \"single_nested_message\", 11]],"
            + " cel.expr.conformance.proto3.TestAllTypes.NestedMessage)"),
    PROTO3_CHAINED_FIELD_SELECT(
        "msg.single_nested_message.bb",
        "cel.@attribute(msg, [[21, \"single_nested_message\", 11], [1, \"bb\", 5, 0]], int)"),
    PROTO2_SINGLE_MESSAGE_FIELD_SELECT(
        "proto2_msg.single_nested_message",
        "cel.@attribute(proto2_msg, [[21, \"single_nested_message\", 11]],"
            + " cel.expr.conformance.proto2.TestAllTypes.NestedMessage)"),
    PROTO2_CHAINED_FIELD_SELECT(
        "proto2_msg.single_nested_message.bb",
        "cel.@attribute(proto2_msg, [[21, \"single_nested_message\", 11], [1, \"bb\", 5, 0]],"
            + " int)"),
    PROTO2_TRIPLE_CHAINED_FIELD_SELECT(
        "nested_msg.child.payload.single_int64",
        "cel.@attribute(nested_msg, "
            + "[[1, \"child\", 11], "
            + "[2, \"payload\", 11], "
            + "[2, \"single_int64\", 3, -64]], int)"),
    PROTO2_CHAINED_MESSAGE_FIELD_SELECT(
        "nested_msg.child.payload",
        "cel.@attribute(nested_msg, [[1, \"child\", 11], [2, \"payload\", 11]],"
            + " cel.expr.conformance.proto2.TestAllTypes)"),

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
        "cel.@attribute(proto2_msg, [[1, \"single_int32\", 5, -32]], int)"),
    PROTO3_ZERO_INT32(
        "msg.single_int32", "cel.@attribute(msg, [[1, \"single_int32\", 5, 0]], int)"),

    // Int64: proto2 has custom default -64, proto3 has 0
    PROTO2_CUSTOM_INT64(
        "proto2_msg.single_int64",
        "cel.@attribute(proto2_msg, [[2, \"single_int64\", 3, -64]], int)"),
    PROTO3_ZERO_INT64(
        "msg.single_int64", "cel.@attribute(msg, [[2, \"single_int64\", 3, 0]], int)"),

    // Uint32: proto2 has custom default 32, proto3 has 0
    PROTO2_CUSTOM_UINT32(
        "proto2_msg.single_uint32",
        "cel.@attribute(proto2_msg, [[3, \"single_uint32\", 13, 32u]], uint)"),
    PROTO3_ZERO_UINT32(
        "msg.single_uint32", "cel.@attribute(msg, [[3, \"single_uint32\", 13, 0u]], uint)"),

    // Uint64: proto2 has custom default 64, proto3 has 0
    PROTO2_CUSTOM_UINT64(
        "proto2_msg.single_uint64",
        "cel.@attribute(proto2_msg, [[4, \"single_uint64\", 4, 64u]], uint)"),
    PROTO3_ZERO_UINT64(
        "msg.single_uint64", "cel.@attribute(msg, [[4, \"single_uint64\", 4, 0u]], uint)"),

    // String: proto2 has custom default "empty", proto3 has ""
    PROTO2_CUSTOM_STRING(
        "proto2_msg.single_string",
        "cel.@attribute(proto2_msg, [[14, \"single_string\", 9, \"empty\"]], string)"),
    PROTO3_ZERO_STRING(
        "msg.single_string", "cel.@attribute(msg, [[14, \"single_string\", 9, \"\"]], string)"),

    // Bool: proto2 has custom default true, proto3 has false
    PROTO2_CUSTOM_BOOL(
        "proto2_msg.single_bool",
        "cel.@attribute(proto2_msg, [[13, \"single_bool\", 8, true]], bool)"),
    PROTO3_ZERO_BOOL(
        "msg.single_bool", "cel.@attribute(msg, [[13, \"single_bool\", 8, false]], bool)"),

    // Float: proto2 has custom default 3.0, proto3 has 0.0
    PROTO2_CUSTOM_FLOAT(
        "proto2_msg.single_float",
        "cel.@attribute(proto2_msg, [[11, \"single_float\", 2, 3.0]], double)"),
    PROTO3_ZERO_FLOAT(
        "msg.single_float", "cel.@attribute(msg, [[11, \"single_float\", 2, 0.0]], double)"),

    // Double: proto2 has custom default 6.4, proto3 has 0.0
    PROTO2_CUSTOM_DOUBLE(
        "proto2_msg.single_double",
        "cel.@attribute(proto2_msg, [[12, \"single_double\", 1, 6.4]], double)"),
    PROTO3_ZERO_DOUBLE(
        "msg.single_double", "cel.@attribute(msg, [[12, \"single_double\", 1, 0.0]], double)"),

    // Bytes: proto2 has custom default "none", proto3 has ""
    PROTO2_CUSTOM_BYTES(
        "proto2_msg.single_bytes",
        "cel.@attribute(proto2_msg, [[15, \"single_bytes\", 12, b\"\\156\\157\\156\\145\"]],"
            + " bytes)"),
    PROTO3_ZERO_BYTES(
        "msg.single_bytes", "cel.@attribute(msg, [[15, \"single_bytes\", 12, b\"\"]], bytes)"),

    // Enum: proto2 has custom default 1 (BAR), proto3 has 0 (FOO)
    PROTO2_CUSTOM_ENUM(
        "proto2_msg.single_nested_enum",
        "cel.@attribute(proto2_msg, [[22, \"single_nested_enum\", 14, 1]], int)"),
    PROTO3_ZERO_ENUM(
        "msg.single_nested_enum",
        "cel.@attribute(msg, [[22, \"single_nested_enum\", 14, 0]], int)"),

    // Fixed / sfixed / sint fields
    PROTO3_FIXED32(
        "msg.single_fixed32", "cel.@attribute(msg, [[7, \"single_fixed32\", 7, 0u]], uint)"),
    PROTO3_FIXED64(
        "msg.single_fixed64", "cel.@attribute(msg, [[8, \"single_fixed64\", 6, 0u]], uint)"),
    PROTO3_SFIXED32(
        "msg.single_sfixed32", "cel.@attribute(msg, [[9, \"single_sfixed32\", 15, 0]], int)"),
    PROTO3_SFIXED64(
        "msg.single_sfixed64", "cel.@attribute(msg, [[10, \"single_sfixed64\", 16, 0]], int)"),
    PROTO3_SINT32("msg.single_sint32", "cel.@attribute(msg, [[5, \"single_sint32\", 17, 0]], int)"),
    PROTO3_SINT64("msg.single_sint64", "cel.@attribute(msg, [[6, \"single_sint64\", 18, 0]], int)"),

    // Repeated fields: empty list default
    PROTO2_REPEATED_PRIMITIVE(
        "proto2_msg.repeated_int64",
        "cel.@attribute(proto2_msg, [[32, \"repeated_int64\", 3, []]], list)"),
    PROTO3_REPEATED_PRIMITIVE(
        "msg.repeated_int64", "cel.@attribute(msg, [[32, \"repeated_int64\", 3, []]], list)"),
    PROTO3_REPEATED_MESSAGE(
        "msg.repeated_nested_message",
        "cel.@attribute(msg, [[51, \"repeated_nested_message\", 11, []]], list)"),

    // Well-known types
    PROTO3_TIMESTAMP(
        "msg.single_timestamp",
        "cel.@attribute(msg, [[102, \"single_timestamp\", 11, timestamp(0)]],"
            + " google.protobuf.Timestamp)"),
    PROTO3_DURATION(
        "msg.single_duration",
        "cel.@attribute(msg, [[101, \"single_duration\", 11, duration(\"0s\")]],"
            + " google.protobuf.Duration)"),

    // Map selects
    MAP_FIELD_INDEXING(
        "msg.map_int64_message[1].bb",
        "cel.@attribute("
            + "cel.@attribute(msg, [[95, \"map_int64_message\", -1, {}]], map)[1], "
            + "[[1, \"bb\", 5, 0]], int)"),
    MAP_FIELD_SELECT_CHAIN_STOPS_AT_MAP_BOUNDARY(
        "map_var_msg.key.single_nested_message.bb",
        "cel.@attribute(map_var_msg.key, "
            + "[[21, \"single_nested_message\", 11], "
            + "[1, \"bb\", 5, 0]], int)"),
    MAP_FIELD_SELECT_STOPS_AT_MAP_BOUNDARY(
        "map_var_msg.key.single_int64",
        "cel.@attribute(map_var_msg.key, [[2, \"single_int64\", 3, 0]], int)"),
    MAP_FIELD_HAS_STOPS_AT_MAP_BOUNDARY(
        "has(map_var_msg.key.single_nested_message)",
        "cel.@hasField(map_var_msg.key, [[21, \"single_nested_message\"]])"),
    MAP_FIELD_HAS_CHAIN_STOPS_AT_MAP_BOUNDARY(
        "has(map_var_msg.key.single_nested_message.bb)",
        "cel.@hasField(map_var_msg.key, [[21, \"single_nested_message\"], [1, \"bb\"]])"),
    PROTO_MAP_FIELD_SELECT_STOPS_AT_MAP_BOUNDARY(
        "msg.map_string_message.key.bb",
        "cel.@attribute("
            + "cel.@attribute(msg, [[227, \"map_string_message\", -1, {}]], map).key, "
            + "[[1, \"bb\", 5, 0]], int)"),
    PROTO_MAP_FIELD_HAS_STOPS_AT_MAP_BOUNDARY(
        "has(msg.map_string_message.key.bb)",
        "cel.@hasField("
            + "cel.@attribute(msg, [[227, \"map_string_message\", -1, {}]], map).key, "
            + "[[1, \"bb\"]])"),

    MIXED_BOOLEAN_EXPRESSION(
        "msg.single_int64 > 0 && has(msg.single_nested_message)",
        "cel.@attribute(msg, [[2, \"single_int64\", 3, 0]], int) > 0 "
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
        .isEqualTo("cel.@attribute(msg, [[2, \"single_int64\", 3, 0]], int)");
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
        .isEqualTo("cel.@attribute(msg, [[2, \"single_int64\", 3, 0]], int)");
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
        .isEqualTo("cel.@attribute(proto2_msg, [[2, \"single_int64\", 3, -64]], int)");
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
  public void optimizeAndEvaluate_legacyRuntime_throwsEvaluationException(
      @TestParameter({"msg.single_int64", "has(msg.single_int64)"}) String expression)
      throws Exception {
    assumeTrue(runtimeFlavor == CelRuntimeFlavor.LEGACY);
    CelAbstractSyntaxTree ast = cel.compile(expression).getAst();
    CelAbstractSyntaxTree optimizedAst = celOptimizer.optimize(ast);
    Program optimizedProgram = cel.createProgram(optimizedAst);

    CelEvaluationException e =
        assertThrows(
            CelEvaluationException.class,
            () -> optimizedProgram.eval(ImmutableMap.of("msg", TestAllTypes.getDefaultInstance())));

    assertThat(e).hasMessageThat().contains("No matching overload for function");
  }

  @SuppressWarnings("ImmutableEnumChecker") // Test only
  private enum NativeSelectEvaluationTestCase {
    POPULATED_PROTO3_MESSAGE(
        "msg.single_nested_message.bb",
        ImmutableMap.of(
            "msg",
            TestAllTypes.newBuilder()
                .setSingleNestedMessage(TestAllTypes.NestedMessage.newBuilder().setBb(42))
                .build()),
        42L),
    UNSET_INTERMEDIATE_PROTO3_MESSAGE(
        "msg.single_nested_message.bb",
        ImmutableMap.of("msg", TestAllTypes.getDefaultInstance()),
        0L),
    PROTO2_CUSTOM_DEFAULT(
        "proto2_msg.single_int64",
        ImmutableMap.of(
            "proto2_msg", dev.cel.expr.conformance.proto2.TestAllTypes.getDefaultInstance()),
        -64L),
    PROTO2_CUSTOM_DEFAULT_INT32(
        "proto2_msg.single_int32",
        ImmutableMap.of(
            "proto2_msg", dev.cel.expr.conformance.proto2.TestAllTypes.getDefaultInstance()),
        -32L),
    PROTO2_CUSTOM_DEFAULT_UINT64(
        "proto2_msg.single_uint64",
        ImmutableMap.of(
            "proto2_msg", dev.cel.expr.conformance.proto2.TestAllTypes.getDefaultInstance()),
        UnsignedLong.valueOf(64)),
    PROTO2_CUSTOM_DEFAULT_DOUBLE(
        "proto2_msg.single_double",
        ImmutableMap.of(
            "proto2_msg", dev.cel.expr.conformance.proto2.TestAllTypes.getDefaultInstance()),
        6.4d),
    PROTO2_CUSTOM_DEFAULT_BOOL(
        "proto2_msg.single_bool",
        ImmutableMap.of(
            "proto2_msg", dev.cel.expr.conformance.proto2.TestAllTypes.getDefaultInstance()),
        true),
    PROTO2_CUSTOM_DEFAULT_STRING(
        "proto2_msg.single_string",
        ImmutableMap.of(
            "proto2_msg", dev.cel.expr.conformance.proto2.TestAllTypes.getDefaultInstance()),
        "empty"),
    PROTO2_CUSTOM_DEFAULT_BYTES(
        "proto2_msg.single_bytes",
        ImmutableMap.of(
            "proto2_msg", dev.cel.expr.conformance.proto2.TestAllTypes.getDefaultInstance()),
        CelByteString.of("none".getBytes(UTF_8))),
    PROTO3_DEFAULT_MAP(
        "msg.map_string_string",
        ImmutableMap.of("msg", TestAllTypes.getDefaultInstance()),
        ImmutableMap.of()),
    PROTO3_DEFAULT_LIST(
        "msg.repeated_int32",
        ImmutableMap.of("msg", TestAllTypes.getDefaultInstance()),
        ImmutableList.of()),
    PROTO3_DEFAULT_DURATION(
        "msg.single_duration",
        ImmutableMap.of("msg", TestAllTypes.getDefaultInstance()),
        Duration.ZERO),
    PROTO3_DEFAULT_TIMESTAMP(
        "msg.single_timestamp",
        ImmutableMap.of("msg", TestAllTypes.getDefaultInstance()),
        Instant.EPOCH),
    PROTO3_MAP_OF_ANY_UNPACKS_VALUE(
        "msg.map_string_any['k']",
        ImmutableMap.of(
            "msg",
            TestAllTypes.newBuilder().putMapStringAny("k", Any.pack(Int64Value.of(5))).build()),
        5L),
    PROTO3_MAP_OF_WRAPPER_UNWRAPS_MAP_VALUES(
        "msg.map_string_int64_wrapper",
        ImmutableMap.of(
            "msg",
            TestAllTypes.newBuilder().putMapStringInt64Wrapper("k", Int64Value.of(5)).build()),
        ImmutableMap.of("k", 5L)),
    PROTO3_MAP_OF_VALUE_CONVERTS_TO_JSON(
        "msg.map_string_value['k']",
        ImmutableMap.of(
            "msg",
            TestAllTypes.newBuilder()
                .putMapStringValue("k", Value.newBuilder().setNumberValue(1.5).build())
                .build()),
        1.5),
    PROTO3_MAP_OF_STRUCT_CONVERTS_TO_MAP(
        "msg.map_string_struct['k'].a",
        ImmutableMap.of(
            "msg",
            TestAllTypes.newBuilder()
                .putMapStringStruct(
                    "k",
                    Struct.newBuilder()
                        .putFields("a", Value.newBuilder().setNumberValue(1.5).build())
                        .build())
                .build()),
        1.5),
    DEEPLY_NESTED_PROTO2_MESSAGE_POPULATED(
        "nested_msg.child.payload.single_int64",
        ImmutableMap.of(
            "nested_msg",
            NestedTestAllTypes.newBuilder()
                .setChild(
                    NestedTestAllTypes.newBuilder()
                        .setPayload(
                            dev.cel.expr.conformance.proto2.TestAllTypes.newBuilder()
                                .setSingleInt64(999L)))
                .build()),
        999L),
    DEEPLY_NESTED_PROTO2_MESSAGE_UNSET(
        "nested_msg.child.payload.single_int64",
        ImmutableMap.of("nested_msg", NestedTestAllTypes.getDefaultInstance()),
        -64L),
    HAS_FIELD_INTERMEDIATE_UNSET(
        "has(msg.single_nested_message.bb)",
        ImmutableMap.of("msg", TestAllTypes.getDefaultInstance()),
        false),
    HAS_FIELD_PROTO3_IMPLICIT_PRESENCE_DEFAULT(
        "has(msg.single_nested_message.bb)",
        ImmutableMap.of(
            "msg",
            TestAllTypes.newBuilder()
                .setSingleNestedMessage(TestAllTypes.NestedMessage.newBuilder().setBb(0))
                .build()),
        false),
    HAS_FIELD_PROTO3_FIELD_PRESENT(
        "has(msg.single_nested_message.bb)",
        ImmutableMap.of(
            "msg",
            TestAllTypes.newBuilder()
                .setSingleNestedMessage(TestAllTypes.NestedMessage.newBuilder().setBb(42))
                .build()),
        true),
    HAS_FIELD_PROTO3_OPTIONAL_SCALAR_EXPLICIT_PRESENCE(
        "has(msg.optional_bool)",
        ImmutableMap.of("msg", TestAllTypes.newBuilder().setOptionalBool(false).build()),
        true),
    HAS_FIELD_PROTO3_OPTIONAL_SCALAR_UNSET(
        "has(msg.optional_bool)", ImmutableMap.of("msg", TestAllTypes.getDefaultInstance()), false),
    HAS_FIELD_PROTO2_SCALAR_SET_TO_DEFAULT(
        "has(proto2_msg.single_int32)",
        ImmutableMap.of(
            "proto2_msg",
            dev.cel.expr.conformance.proto2.TestAllTypes.newBuilder().setSingleInt32(-32).build()),
        true),
    HAS_FIELD_PROTO2_SCALAR_UNSET(
        "has(proto2_msg.single_int32)",
        ImmutableMap.of(
            "proto2_msg", dev.cel.expr.conformance.proto2.TestAllTypes.getDefaultInstance()),
        false),
    SELECT_ON_MAP_VALUE_POPULATED(
        "map_var_msg.key.single_nested_message.bb",
        ImmutableMap.of(
            "map_var_msg",
            ImmutableMap.of(
                "key",
                TestAllTypes.newBuilder()
                    .setSingleNestedMessage(TestAllTypes.NestedMessage.newBuilder().setBb(42))
                    .build())),
        42L),
    SELECT_ON_MAP_VALUE_UNSET(
        "map_var_msg.key.single_nested_message.bb",
        ImmutableMap.of("map_var_msg", ImmutableMap.of("key", TestAllTypes.getDefaultInstance())),
        0L),
    HAS_FIELD_ON_MAP_VALUE_PRESENT(
        "has(map_var_msg.key.single_nested_message)",
        ImmutableMap.of(
            "map_var_msg",
            ImmutableMap.of(
                "key",
                TestAllTypes.newBuilder()
                    .setSingleNestedMessage(TestAllTypes.NestedMessage.newBuilder().setBb(42))
                    .build())),
        true),
    HAS_FIELD_ON_MAP_VALUE_ABSENT(
        "has(map_var_msg.key.single_nested_message)",
        ImmutableMap.of("map_var_msg", ImmutableMap.of("key", TestAllTypes.getDefaultInstance())),
        false);

    private final String expression;
    private final ImmutableMap<String, Object> input;
    private final Object expectedResult;

    NativeSelectEvaluationTestCase(
        String expression, ImmutableMap<String, Object> input, Object expectedResult) {
      this.expression = expression;
      this.input = input;
      this.expectedResult = expectedResult;
    }
  }

  @Test
  public void optimizeAndEvaluate_nativeSelectAndHasField_matchesUnoptimized(
      @TestParameter NativeSelectEvaluationTestCase testCase) throws Exception {
    assumeTrue(runtimeFlavor == CelRuntimeFlavor.PLANNER);
    CelAbstractSyntaxTree ast = cel.compile(testCase.expression).getAst();
    CelAbstractSyntaxTree optimizedAst = celOptimizer.optimize(ast);
    Program unoptimizedProgram = cel.createProgram(ast);
    Program optimizedProgram = cel.createProgram(optimizedAst);

    Object unoptimizedResult = unoptimizedProgram.eval(testCase.input);
    Object optimizedResult = optimizedProgram.eval(testCase.input);

    assertThat(unoptimizedResult).isEqualTo(testCase.expectedResult);
    assertThat(optimizedResult).isEqualTo(testCase.expectedResult);
    assertThat(optimizedResult).isEqualTo(unoptimizedResult);
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
        .isEqualTo("cel.@attribute(proto2_msg, [[2, \"single_int64\", 3, -64]], int)");
  }

  @Test
  public void newInstance_fileDescriptorsIterable_defaultOptions_success() throws Exception {
    FileDescriptor fd = PROTO2_TEST_ALL_TYPES_DESCRIPTOR.getFile();
    SelectOptimizer optimizer = SelectOptimizer.newInstance(ImmutableList.of(fd));
    CelAbstractSyntaxTree ast = cel.compile("proto2_msg.single_int64").getAst();

    CelAbstractSyntaxTree optimizedAst = optimizer.optimize(ast, cel).optimizedAst();

    assertThat(CEL_UNPARSER.unparse(optimizedAst))
        .isEqualTo("cel.@attribute(proto2_msg, [[2, \"single_int64\", 3, -64]], int)");
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
        .isEqualTo("cel.@attribute(proto2_msg, [[2, \"single_int64\", 3, -64]], int)");
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
        .isEqualTo("cel.@attribute(proto2_msg, [[2, \"single_int64\", 3, -64]], int)");
    assertThat(CEL_UNPARSER.unparse(proto3Optimized)).isEqualTo("msg.single_int64");
  }

  private enum CompilerRejectionTestCase {
    ATTRIBUTE_AT_SIGN(
        SelectOptimizer.CEL_ATTRIBUTE_FUNCTION_DECL,
        "cel.@attribute(msg, [], int)",
        "token recognition error at: '@'"),
    ATTRIBUTE_OVERLOAD(
        SelectOptimizer.CEL_ATTRIBUTE_FUNCTION_DECL,
        "cel_attribute_list(msg, [], int)",
        "undeclared reference to 'cel_attribute_list'"),
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
  public void optimize_toParsedExpr_matchesExpectedSerializedProto() throws Exception {
    CelAbstractSyntaxTree ast = cel.compile("msg.single_nested_message.bb").getAst();
    ParsedExpr expectedParsedExpr =
        TextFormat.parse(
            "expr {\n"
                + "  id: 1\n"
                + "  call_expr {\n"
                + "    function: \"cel.@attribute\"\n"
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
                + "    args {\n"
                + "      id: 13\n"
                + "      ident_expr {\n"
                + "        name: \"int\"\n"
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

  @Test
  public void
      optimize_binaryOperationOnOptimizedSelect_resolvesOverloadAndPreservesConcreteResultType()
          throws Exception {
    CelAbstractSyntaxTree ast = cel.compile("msg.single_int64 + 1").getAst();

    CelAbstractSyntaxTree optimizedAst = celOptimizer.optimize(ast);

    assertThat(optimizedAst.getResultType()).isEqualTo(SimpleType.INT);
    assertThat(CEL_UNPARSER.unparse(optimizedAst))
        .isEqualTo("cel.@attribute(msg, [[2, \"single_int64\", 3, 0]], int) + 1");
  }

  @Test
  public void
      optimize_stringOperationOnOptimizedSelect_resolvesOverloadAndPreservesConcreteResultType()
          throws Exception {
    CelAbstractSyntaxTree ast = cel.compile("msg.single_string + 'suffix'").getAst();

    CelAbstractSyntaxTree optimizedAst = celOptimizer.optimize(ast);

    assertThat(optimizedAst.getResultType()).isEqualTo(SimpleType.STRING);
    assertThat(CEL_UNPARSER.unparse(optimizedAst))
        .isEqualTo("cel.@attribute(msg, [[14, \"single_string\", 9, \"\"]], string) + \"suffix\"");
  }

  @Test
  public void optimize_resultFunctionDeclarations_containsOnlySingularAttributeAndHasField()
      throws Exception {
    SelectOptimizer optimizer = SelectOptimizer.newInstance(TestAllTypes.getDescriptor().getFile());
    CelAbstractSyntaxTree ast = cel.compile("msg.single_int64").getAst();

    CelAstOptimizer.OptimizationResult result = optimizer.optimize(ast, cel);

    assertThat(result.newFunctionDecls())
        .containsExactly(
            SelectOptimizer.CEL_ATTRIBUTE_FUNCTION_DECL,
            SelectOptimizer.CEL_HAS_FIELD_FUNCTION_DECL);
    assertThat(
            SelectOptimizer.CEL_ATTRIBUTE_FUNCTION_DECL.overloads().stream()
                .map(CelOverloadDecl::overloadId))
        .containsExactly("cel_attribute_list");
  }

  @Test
  public void optimize_referenceMap_containsSingleOverloadIdForAttributeCall() throws Exception {
    CelAbstractSyntaxTree ast = cel.compile("msg.single_int64").getAst();

    CelAbstractSyntaxTree optimizedAst = celOptimizer.optimize(ast);

    CelReference reference = optimizedAst.getReferenceOrThrow(optimizedAst.getExpr().id());
    assertThat(reference.overloadIds()).containsExactly("cel_attribute_list");
  }

  @Test
  public void optimize_binaryOperationBetweenOptimizedSelects_resolvesSingleOverloadInReferenceMap()
      throws Exception {
    CelAbstractSyntaxTree ast = cel.compile("msg.single_int64 + msg.single_sint64").getAst();

    CelAbstractSyntaxTree optimizedAst = celOptimizer.optimize(ast);

    assertThat(optimizedAst.getResultType()).isEqualTo(SimpleType.INT);
    CelReference addReference = optimizedAst.getReferenceOrThrow(optimizedAst.getExpr().id());
    assertThat(addReference.overloadIds()).containsExactly("add_int64");
  }

  @Test
  public void optimize_optionalSelect_passesThroughUntouched() throws Exception {
    Cel celWithOptional =
        cel.toCelBuilder()
            .addCompilerLibraries(CelExtensions.optional())
            .addRuntimeLibraries(CelExtensions.optional())
            .build();
    CelOptimizer optimizer =
        CelOptimizerFactory.standardCelOptimizerBuilder(celWithOptional)
            .addAstOptimizers(
                SelectOptimizer.newInstance(
                    SelectOptimizerOptions.newBuilder().build(),
                    TestAllTypes.getDescriptor().getFile()))
            .build();
    CelAbstractSyntaxTree ast = celWithOptional.compile("msg.?single_nested_message.bb").getAst();

    CelAbstractSyntaxTree optimizedAst = optimizer.optimize(ast);

    assertThat(optimizedAst.getExpr()).isEqualTo(ast.getExpr());
  }
}
