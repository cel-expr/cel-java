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

package dev.cel.runtime.planner;

import static com.google.common.truth.Truth.assertThat;
import static dev.cel.common.CelFunctionDecl.newFunctionDeclaration;
import static dev.cel.common.CelOverloadDecl.newGlobalOverload;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.primitives.UnsignedLong;
import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.CelContainer;
import dev.cel.common.CelDescriptorUtil;
import dev.cel.common.CelOptions;
import dev.cel.common.ast.CelExpr;
import dev.cel.common.exceptions.CelAttributeNotFoundException;
import dev.cel.common.exceptions.CelInvalidArgumentException;
import dev.cel.common.internal.CelDescriptorPool;
import dev.cel.common.internal.DefaultDescriptorPool;
import dev.cel.common.internal.DefaultMessageFactory;
import dev.cel.common.internal.DynamicProto;
import dev.cel.common.types.CelTypeProvider;
import dev.cel.common.types.CelTypeProvider.CombinedCelTypeProvider;
import dev.cel.common.types.DefaultTypeProvider;
import dev.cel.common.types.MapType;
import dev.cel.common.types.ProtoMessageTypeProvider;
import dev.cel.common.types.SimpleType;
import dev.cel.common.types.StructTypeReference;
import dev.cel.common.values.CelByteString;
import dev.cel.common.values.CelValueConverter;
import dev.cel.common.values.CelValueProvider;
import dev.cel.common.values.OptionalValue;
import dev.cel.common.values.ProtoCelValueConverter;
import dev.cel.common.values.ProtoMessageValueProvider;
import dev.cel.compiler.CelCompiler;
import dev.cel.compiler.CelCompilerFactory;
import dev.cel.expr.conformance.proto3.TestAllTypes;
import dev.cel.extensions.CelExtensions;
import dev.cel.parser.CelStandardMacro;
import dev.cel.runtime.CelAsyncEvaluationOptions;
import dev.cel.runtime.CelAttribute;
import dev.cel.runtime.CelAttributePattern;
import dev.cel.runtime.CelEvaluationException;
import dev.cel.runtime.CelUnknownSet;
import dev.cel.runtime.DefaultDispatcher;
import dev.cel.runtime.PartialVars;
import dev.cel.runtime.Program;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(TestParameterInjector.class)
public final class OptimizedSelectPlannerTest {
  private static final CelOptions CEL_OPTIONS = CelOptions.current().build();
  private static final CelTypeProvider TYPE_PROVIDER =
      new CombinedCelTypeProvider(
          DefaultTypeProvider.getInstance(),
          ProtoMessageTypeProvider.newBuilder()
              .addDescriptors(ImmutableSet.of(TestAllTypes.getDescriptor()))
              .build());
  private static final CelDescriptorPool DESCRIPTOR_POOL =
      DefaultDescriptorPool.create(
          CelDescriptorUtil.getAllDescriptorsFromFileDescriptor(
              TestAllTypes.getDescriptor().getFile()));
  private static final DynamicProto DYNAMIC_PROTO =
      DynamicProto.create(DefaultMessageFactory.create(DESCRIPTOR_POOL));
  private static final CelValueProvider VALUE_PROVIDER =
      ProtoMessageValueProvider.newInstance(CEL_OPTIONS, DYNAMIC_PROTO);
  private static final CelValueConverter CEL_VALUE_CONVERTER =
      ProtoCelValueConverter.newInstance(DESCRIPTOR_POOL, DYNAMIC_PROTO, CelOptions.DEFAULT);
  private static final CelContainer CEL_CONTAINER =
      CelContainer.newBuilder().setName("cel.expr.conformance.proto3").build();

  private static final ProgramPlanner PLANNER =
      ProgramPlanner.newPlanner(
          TYPE_PROVIDER,
          VALUE_PROVIDER,
          newDispatcher(),
          CEL_VALUE_CONVERTER,
          CEL_CONTAINER,
          CEL_OPTIONS,
          ImmutableSet.of(),
          CelAsyncEvaluationOptions.defaultOptions(),
          /* asyncExecutor= */ null);

  private static final CelCompiler CEL_COMPILER =
      CelCompilerFactory.standardCelCompilerBuilder()
          .setStandardMacros(CelStandardMacro.STANDARD_MACROS)
          .addVar("msg", StructTypeReference.create(TestAllTypes.getDescriptor().getFullName()))
          .addVar("map_var", MapType.create(SimpleType.STRING, SimpleType.DYN))
          .addFunctionDeclarations(
              newFunctionDeclaration("error", newGlobalOverload("error_overload", SimpleType.INT)))
          .addMessageTypes(TestAllTypes.getDescriptor())
          .addLibraries(CelExtensions.optional())
          .setContainer(CEL_CONTAINER)
          .build();

  @Test
  public void plan_celAttribute_populatedField_returnsPopulatedValue() throws Exception {
    CelAbstractSyntaxTree ast =
        parseSelectAst("cel.@attribute(msg, [[2, 'single_int64', 3, 0]], int)");
    Program program = PLANNER.plan(ast);
    TestAllTypes msg = TestAllTypes.newBuilder().setSingleInt64(42L).build();

    Object result = program.eval(ImmutableMap.of("msg", msg));

    assertThat(result).isEqualTo(42L);
  }

  @Test
  public void plan_celAttribute_defaultValues_returnsExpectedDefault(
      @TestParameter DefaultValueTestCase testCase) throws Exception {
    CelAbstractSyntaxTree ast = parseSelectAst(testCase.expression);
    Program program = PLANNER.plan(ast);

    Object result = program.eval(ImmutableMap.of("msg", TestAllTypes.getDefaultInstance()));

    assertThat(result).isEqualTo(testCase.expected);
  }

  @Test
  public void plan_partialVars_evaluatesExpectedResult(@TestParameter PartialVarsTestCase testCase)
      throws Exception {
    CelAbstractSyntaxTree ast = parseSelectAst(testCase.expression);
    Program program = PLANNER.plan(ast);

    Object result = program.eval(testCase.partialVars);

    assertThat(result).isEqualTo(testCase.expected);
  }

  @Test
  public void plan_evaluationError_throwsExpectedException(
      @TestParameter EvaluationErrorTestCase testCase) throws Exception {
    CelAbstractSyntaxTree ast = parseSelectAst(testCase.expression);
    Program program = PLANNER.plan(ast);

    CelEvaluationException e =
        assertThrows(CelEvaluationException.class, () -> program.eval(testCase.input));

    assertThat(e).hasCauseThat().isInstanceOf(testCase.expectedCause);
    assertThat(e).hasMessageThat().contains(testCase.expectedMessageSubstring);
  }

  @Test
  public void plan_optionalSelect_onOptimizedPrefix_withPartialVarsUnknown_returnsUnknown()
      throws Exception {
    CelAbstractSyntaxTree prefixAst =
        parseSelectAst(
            "cel.@attribute(msg, [[21, 'single_nested_message', 11]],"
                + " cel.expr.conformance.proto3.TestAllTypes.NestedMessage)");
    CelAbstractSyntaxTree wrapperAst = CEL_COMPILER.parse("dummy.?bb").getAst();
    CelExpr selectCall = wrapperAst.getExpr();
    CelExpr combinedExpr =
        selectCall.toBuilder()
            .setCall(selectCall.call().toBuilder().setArg(0, prefixAst.getExpr()).build())
            .build();
    CelAbstractSyntaxTree combinedAst =
        CelAbstractSyntaxTree.newParsedAst(combinedExpr, wrapperAst.getSource());
    Program program = PLANNER.plan(combinedAst);
    TestAllTypes msg =
        TestAllTypes.newBuilder()
            .setSingleNestedMessage(TestAllTypes.NestedMessage.newBuilder().setBb(42))
            .build();
    PartialVars partialVars =
        PartialVars.of(
            ImmutableMap.of("msg", msg),
            CelAttributePattern.fromQualifiedIdentifier("msg.single_nested_message.bb"));

    Object result = program.eval(partialVars);

    assertThat(result).isInstanceOf(CelUnknownSet.class);
    assertThat(((CelUnknownSet) result).attributes())
        .containsExactly(CelAttribute.fromQualifiedIdentifier("msg.single_nested_message.bb"));
  }

  @Test
  public void plan_invalidAstIntegrity_throwsEvaluationException(
      @TestParameter InvalidSelectAstTestCase testCase) throws Exception {
    CelAbstractSyntaxTree ast = parseSelectAst(testCase.expression);

    CelEvaluationException e = assertThrows(CelEvaluationException.class, () -> PLANNER.plan(ast));

    assertThat(e).hasCauseThat().isInstanceOf(IllegalArgumentException.class);
    assertThat(e).hasMessageThat().contains(testCase.expectedErrorSubstring);
  }

  private static DefaultDispatcher newDispatcher() {
    DefaultDispatcher.Builder builder = DefaultDispatcher.newBuilder();
    builder.addOverload(
        "error",
        "error",
        ImmutableList.of(),
        /* isStrict= */ true,
        unused -> {
          throw new IllegalArgumentException("Intentional error");
        });
    return builder.build();
  }

  /**
   * Parses an expression whose root call is {@code cel.@attribute} or {@code cel.@hasField} (which
   * are not valid CEL parser identifiers) by temporarily substituting a parseable function name.
   * Assumes the sentinel function name appears only at the root call and not inside string
   * literals.
   */
  private static CelAbstractSyntaxTree parseSelectAst(String expression) throws Exception {
    String parseable =
        expression
            .replace("cel.@attribute", "cel_attribute")
            .replace("cel.@hasField", "cel_has_field");
    CelAbstractSyntaxTree parsed = CEL_COMPILER.parse(parseable).getAst();
    CelExpr root = parsed.getExpr();
    String targetFn =
        root.call().function().equals("cel_attribute") ? "cel.@attribute" : "cel.@hasField";
    CelExpr rewrittenRoot =
        root.toBuilder().setCall(root.call().toBuilder().setFunction(targetFn).build()).build();
    return CelAbstractSyntaxTree.newParsedAst(rewrittenRoot, parsed.getSource());
  }

  private static ImmutableMap<String, Object> newMapWithNullValue() {
    Map<String, Object> mapWithNull = new HashMap<>();
    mapWithNull.put("null_key", null);
    return ImmutableMap.of("map_var", mapWithNull);
  }

  @SuppressWarnings("ImmutableEnumChecker") // Test only
  private enum PartialVarsTestCase {
    CANDIDATE_PRECEDENCE_KNOWN_HIGHER_PRIORITY_WINS(
        "cel.@hasField(b, [[1, 'single_int32']])",
        PartialVars.of(
            ImmutableMap.of(
                "cel.expr.conformance.proto3.b",
                TestAllTypes.newBuilder().setSingleInt32(99).build()),
            CelAttributePattern.create("b")
                .qualify(CelAttribute.Qualifier.ofString("single_int32"))),
        true),
    CANDIDATE_PRECEDENCE_UNKNOWN_HIGHER_PRIORITY_WINS_OVER_KNOWN_FALLBACK(
        "cel.@hasField(b, [[1, 'single_int32']])",
        PartialVars.of(
            ImmutableMap.of("b", TestAllTypes.newBuilder().setSingleInt32(99).build()),
            CelAttributePattern.fromQualifiedIdentifier(
                "cel.expr.conformance.proto3.b.single_int32")),
        CelUnknownSet.create(
            ImmutableSet.of(
                CelAttribute.fromQualifiedIdentifier("cel.expr.conformance.proto3.b.single_int32")),
            ImmutableSet.of(1L))),
    CANDIDATE_PRECEDENCE_UNKNOWN_RETURNED_WHEN_HIGHER_ABSENT(
        "cel.@hasField(b, [[1, 'single_int32']])",
        PartialVars.of(
            CelAttributePattern.create("b")
                .qualify(CelAttribute.Qualifier.ofString("single_int32"))),
        CelUnknownSet.create(
            ImmutableSet.of(CelAttribute.fromQualifiedIdentifier("b.single_int32")),
            ImmutableSet.of(1L))),
    ATTRIBUTE_TARGET_UNKNOWN_RETURNS_UNKNOWN(
        "cel.@attribute(msg, [[21, 'single_nested_message', 11], [1, 'bb', 5, 0]], int)",
        PartialVars.of(
            ImmutableMap.of(
                "msg",
                TestAllTypes.newBuilder()
                    .setSingleNestedMessage(TestAllTypes.NestedMessage.newBuilder().setBb(42))
                    .build()),
            CelAttributePattern.fromQualifiedIdentifier("msg.single_nested_message.bb")),
        CelUnknownSet.create(
            ImmutableSet.of(CelAttribute.fromQualifiedIdentifier("msg.single_nested_message.bb")),
            ImmutableSet.of(1L))),
    ATTRIBUTE_SIBLING_UNKNOWN_EVALUATES_SUCCESSFULLY(
        "cel.@attribute(msg, [[21, 'single_nested_message', 11], [1, 'bb', 5, 0]], int)",
        PartialVars.of(
            ImmutableMap.of(
                "msg",
                TestAllTypes.newBuilder()
                    .setSingleInt64(10L)
                    .setSingleNestedMessage(TestAllTypes.NestedMessage.newBuilder().setBb(42))
                    .build()),
            CelAttributePattern.fromQualifiedIdentifier("msg.single_int64")),
        42L),
    HAS_FIELD_TARGET_UNKNOWN_RETURNS_UNKNOWN(
        "cel.@hasField(msg, [[21, 'single_nested_message'], [1, 'bb']])",
        PartialVars.of(
            ImmutableMap.of(
                "msg",
                TestAllTypes.newBuilder()
                    .setSingleNestedMessage(TestAllTypes.NestedMessage.newBuilder().setBb(42))
                    .build()),
            CelAttributePattern.fromQualifiedIdentifier("msg.single_nested_message.bb")),
        CelUnknownSet.create(
            ImmutableSet.of(CelAttribute.fromQualifiedIdentifier("msg.single_nested_message.bb")),
            ImmutableSet.of(1L))),
    HAS_FIELD_SIBLING_UNKNOWN_EVALUATES_SUCCESSFULLY(
        "cel.@hasField(msg, [[21, 'single_nested_message'], [1, 'bb']])",
        PartialVars.of(
            ImmutableMap.of(
                "msg",
                TestAllTypes.newBuilder()
                    .setSingleInt64(10L)
                    .setSingleNestedMessage(TestAllTypes.NestedMessage.newBuilder().setBb(42))
                    .build()),
            CelAttributePattern.fromQualifiedIdentifier("msg.single_int64")),
        true);

    private final String expression;
    private final PartialVars partialVars;
    private final Object expected;

    PartialVarsTestCase(String expression, PartialVars partialVars, Object expected) {
      this.expression = expression;
      this.partialVars = partialVars;
      this.expected = expected;
    }
  }

  @SuppressWarnings("ImmutableEnumChecker") // Test only
  private enum EvaluationErrorTestCase {
    HAS_FIELD_OPTIONAL_ROOT_OPERAND(
        "cel.@hasField(optional_var, [[1, 'single_int32']])",
        ImmutableMap.of("optional_var", OptionalValue.create(TestAllTypes.getDefaultInstance())),
        UnsupportedOperationException.class,
        "Optional operands are not yet supported by the select-optimized runtime"),
    HAS_FIELD_NON_SELECTABLE_INTERMEDIATE(
        "cel.@hasField(msg, [[1, 'single_int32'], [2, 'nested_field']])",
        ImmutableMap.of("msg", TestAllTypes.newBuilder().setSingleInt32(42).build()),
        CelAttributeNotFoundException.class,
        "nested_field"),
    HAS_FIELD_ERROR_IN_OPERAND(
        "cel.@hasField(error(), [[1, 'single_int64']])",
        ImmutableMap.of(),
        IllegalArgumentException.class,
        "Function 'error' failed"),
    ATTRIBUTE_MISSING_MAP_KEY(
        "cel.@attribute(map_var.key, [[2, 'single_int64', 3, 0]], int)",
        ImmutableMap.of("map_var", ImmutableMap.of()),
        CelAttributeNotFoundException.class,
        "key 'key' is not present in map"),
    ATTRIBUTE_NULL_BOUND_MAP_KEY(
        "cel.@attribute(map_var.null_key, [[2, 'single_int64', 3, 0]], int)",
        newMapWithNullValue(),
        CelInvalidArgumentException.class,
        "Map value cannot be null for key: null_key"),
    ATTRIBUTE_UNBOUND_ROOT_VARIABLE(
        "cel.@attribute(msg, [[2, 'single_int64', 3, 0]], int)",
        ImmutableMap.of(),
        CelAttributeNotFoundException.class,
        "msg");

    private final String expression;
    private final ImmutableMap<String, Object> input;
    private final Class<? extends Throwable> expectedCause;
    private final String expectedMessageSubstring;

    EvaluationErrorTestCase(
        String expression,
        ImmutableMap<String, Object> input,
        Class<? extends Throwable> expectedCause,
        String expectedMessageSubstring) {
      this.expression = expression;
      this.input = input;
      this.expectedCause = expectedCause;
      this.expectedMessageSubstring = expectedMessageSubstring;
    }
  }

  /**
   * Malformed {@code cel.@attribute} / {@code cel.@hasField} ASTs.
   *
   * <p>{@code SelectOptimizer} is the only in-tree producer of these calls and cannot emit a
   * malformed hop, so these cases are unreachable from any top-level API. The plan-time validation
   * exists because the call is a serialized contract carried in a checked AST, which may be
   * persisted and re-planned by a binary that did not produce it.
   *
   * <p>This list is deliberately one case per validation category rather than exhaustive:
   * enumerating every rejection would pin the exact error copy of an internal contract without
   * covering any additional behavior.
   */
  private enum InvalidSelectAstTestCase {
    WRONG_ARG_COUNT(
        "cel.@attribute(msg, [[1, 'single_int32', 5]])",
        "Expected 3 arguments for cel.@attribute, found 2"),
    MALFORMED_HOP(
        "cel.@attribute(msg, [[1, 'single_int32']], int)",
        "Expected qualifier hop for cel.@attribute to contain 3 or 4 elements"),
    TYPE_CODE_IDENT_MISMATCH(
        "cel.@attribute(msg, [[1, 'single_int32', 5, 0]], string)",
        "Leaf type code 5 (expected 'int') is incompatible with typeIdent 'string'"),
    MESSAGE_WITH_SCALAR_TYPE_IDENT(
        "cel.@attribute(msg, [[1, 'single_int32', 11]], int)",
        "Leaf MESSAGE type code (11) is incompatible with scalar typeIdent 'int'"),
    NON_LEAF_HOP_WITH_DEFAULT_VALUE(
        "cel.@attribute(msg, [[21, 'single_nested_message', 11, 0], [1, 'bb', 5, 0]], int)",
        "Non-leaf qualifier hop must not contain a default value"),
    NON_LEAF_HOP_NOT_MESSAGE(
        "cel.@attribute(msg, [[21, 'single_nested_message', 5], [1, 'bb', 5, 0]], int)",
        "Non-leaf qualifier hop must have MESSAGE type code (11)"),
    SCALAR_DEFAULT_VALUE_TYPE_MISMATCH(
        "cel.@attribute(msg, [[1, 'single_int32', 5, 'bogus']], int)",
        "Leaf default value bogus is incompatible with typeIdent 'int'"),
    DURATION_MISSING_DEFAULT_VALUE(
        "cel.@attribute(msg, [[18, 'single_duration', 11]], google.protobuf.Duration)",
        "Leaf default value for message type 'google.protobuf.Duration' is invalid or missing:"
            + " null"),
    TIMESTAMP_MISSING_DEFAULT_VALUE(
        "cel.@attribute(msg, [[19, 'single_timestamp', 11]], google.protobuf.Timestamp)",
        "Leaf default value for message type 'google.protobuf.Timestamp' is invalid or missing:"
            + " null"),
    MESSAGE_UNEXPECTED_DEFAULT_VALUE(
        "cel.@attribute(msg, [[21, 'single_nested_message', 11, 0]],"
            + " cel.expr.conformance.proto3.TestAllTypes.NestedMessage)",
        "Leaf default value for message type"
            + " 'cel.expr.conformance.proto3.TestAllTypes.NestedMessage' is invalid or missing: 0"),
    HAS_FIELD_WRONG_ARG_COUNT(
        "cel.@hasField(msg)", "Expected 2 arguments for cel.@hasField, found 1"),
    HAS_FIELD_MALFORMED_HOP(
        "cel.@hasField(msg, [[1]])",
        "Expected qualifier hop for cel.@hasField to contain 2 elements");

    private final String expression;
    private final String expectedErrorSubstring;

    InvalidSelectAstTestCase(String expression, String expectedErrorSubstring) {
      this.expression = expression;
      this.expectedErrorSubstring = expectedErrorSubstring;
    }
  }

  @SuppressWarnings("ImmutableEnumChecker") // Test only
  private enum DefaultValueTestCase {
    BOOL("cel.@attribute(msg, [[13, 'single_bool', 8, false]], bool)", false),
    STRING("cel.@attribute(msg, [[14, 'single_string', 9, '']], string)", ""),
    BYTES("cel.@attribute(msg, [[15, 'single_bytes', 12, b'']], bytes)", CelByteString.EMPTY),
    INT64("cel.@attribute(msg, [[2, 'single_int64', 3, 0]], int)", 0L),
    UINT64("cel.@attribute(msg, [[4, 'single_uint64', 4, 0u]], uint)", UnsignedLong.ZERO),
    DOUBLE("cel.@attribute(msg, [[1, 'single_double', 1, 0.0]], double)", 0.0d),
    LIST("cel.@attribute(msg, [[32, 'repeated_int64', 3, []]], list)", ImmutableList.of()),
    MAP("cel.@attribute(msg, [[61, 'map_string_string', -1, {}]], map)", ImmutableMap.of()),
    DURATION(
        "cel.@attribute(msg, [[18, 'single_duration', 11, duration('0s')]],"
            + " google.protobuf.Duration)",
        Duration.ZERO),
    TIMESTAMP(
        "cel.@attribute(msg, [[19, 'single_timestamp', 11, timestamp(0)]],"
            + " google.protobuf.Timestamp)",
        Instant.EPOCH);

    private final String expression;
    private final Object expected;

    DefaultValueTestCase(String expression, Object expected) {
      this.expression = expression;
      this.expected = expected;
    }
  }
}
