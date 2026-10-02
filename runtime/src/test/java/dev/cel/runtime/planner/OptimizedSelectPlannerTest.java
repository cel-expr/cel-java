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
import dev.cel.bundle.Cel;
import dev.cel.bundle.CelFactory;
import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.CelContainer;
import dev.cel.common.CelDescriptorUtil;
import dev.cel.common.CelOptions;
import dev.cel.common.CelSource;
import dev.cel.common.ast.CelConstant;
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
import dev.cel.common.values.ProtoCelValueConverter;
import dev.cel.common.values.ProtoMessageValueProvider;
import dev.cel.expr.conformance.proto3.NestedTestAllTypes;
import dev.cel.expr.conformance.proto3.TestAllTypes;
import dev.cel.extensions.CelExtensions;
import dev.cel.optimizer.CelOptimizer;
import dev.cel.optimizer.CelOptimizerFactory;
import dev.cel.optimizer.optimizers.SelectOptimizer;
import dev.cel.optimizer.optimizers.SelectOptimizer.SelectOptimizerOptions;
import dev.cel.parser.CelStandardMacro;
import dev.cel.runtime.CelAsyncEvaluationOptions;
import dev.cel.runtime.CelAttribute;
import dev.cel.runtime.CelAttributePattern;
import dev.cel.runtime.CelEvaluationException;
import dev.cel.runtime.CelFunctionBinding;
import dev.cel.runtime.CelStandardFunctions;
import dev.cel.runtime.CelUnknownSet;
import dev.cel.runtime.DefaultDispatcher;
import dev.cel.runtime.InternalCelFunctionBinding;
import dev.cel.runtime.PartialVars;
import dev.cel.runtime.Program;
import dev.cel.runtime.RuntimeEquality;
import dev.cel.runtime.RuntimeHelpers;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(TestParameterInjector.class)
public final class OptimizedSelectPlannerTest {
  private static final CelOptions CEL_OPTIONS = CelOptions.current().build();
  private static final RuntimeEquality RUNTIME_EQUALITY =
      RuntimeEquality.create(RuntimeHelpers.create(), CEL_OPTIONS);
  private static final CelTypeProvider TYPE_PROVIDER =
      new CombinedCelTypeProvider(
          DefaultTypeProvider.getInstance(),
          ProtoMessageTypeProvider.newBuilder()
              .addDescriptors(
                  ImmutableSet.of(TestAllTypes.getDescriptor(), NestedTestAllTypes.getDescriptor()))
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
          RUNTIME_EQUALITY,
          CelAsyncEvaluationOptions.defaultOptions(),
          /* asyncExecutor= */ null);

  private static final Cel CEL =
      CelFactory.legacyCelBuilder()
          .setOptions(CEL_OPTIONS)
          .setStandardMacros(CelStandardMacro.STANDARD_MACROS)
          .addVar("msg", StructTypeReference.create(TestAllTypes.getDescriptor().getFullName()))
          .addVar("b", StructTypeReference.create(TestAllTypes.getDescriptor().getFullName()))
          .addVar("cond", SimpleType.BOOL)
          .addVar(
              "msg_a", StructTypeReference.create(NestedTestAllTypes.getDescriptor().getFullName()))
          .addVar(
              "msg_b", StructTypeReference.create(NestedTestAllTypes.getDescriptor().getFullName()))
          .addVar(
              "map_var",
              MapType.create(
                  SimpleType.STRING,
                  StructTypeReference.create(TestAllTypes.getDescriptor().getFullName())))
          .addFunctionDeclarations(
              newFunctionDeclaration(
                  "error",
                  newGlobalOverload(
                      "error",
                      StructTypeReference.create(
                          NestedTestAllTypes.getDescriptor().getFullName()))))
          .addMessageTypes(TestAllTypes.getDescriptor(), NestedTestAllTypes.getDescriptor())
          .addCompilerLibraries(CelExtensions.optional())
          .setContainer(CEL_CONTAINER)
          .build();

  private static final CelOptimizer SELECT_OPTIMIZER =
      CelOptimizerFactory.standardCelOptimizerBuilder(CEL)
          .addAstOptimizers(
              SelectOptimizer.newInstance(
                  SelectOptimizerOptions.newBuilder().build(),
                  TestAllTypes.getDescriptor().getFile(),
                  NestedTestAllTypes.getDescriptor().getFile()))
          .build();

  @Test
  public void plan_celAttribute_populatedField_returnsPopulatedValue() throws Exception {
    CelAbstractSyntaxTree ast = optimizeSelectAst("msg.single_int64");
    Program program = PLANNER.plan(ast);
    TestAllTypes msg = TestAllTypes.newBuilder().setSingleInt64(42L).build();

    Object result = program.eval(ImmutableMap.of("msg", msg));

    assertThat(result).isEqualTo(42L);
  }

  @Test
  public void plan_celAttribute_complexPlannedOperand_conditionTrue_evaluatesFirstBranch()
      throws Exception {
    CelAbstractSyntaxTree ast =
        optimizeSelectAst("(cond ? msg_a : msg_b).child.payload.single_int64");
    Program program = PLANNER.plan(ast);
    NestedTestAllTypes msgA = newNestedTestAllTypes(42L);
    NestedTestAllTypes msgB = newNestedTestAllTypes(99L);

    Object result = program.eval(ImmutableMap.of("cond", true, "msg_a", msgA, "msg_b", msgB));

    assertThat(result).isEqualTo(42L);
  }

  @Test
  public void plan_celAttribute_complexPlannedOperand_conditionFalse_evaluatesSecondBranch()
      throws Exception {
    CelAbstractSyntaxTree ast =
        optimizeSelectAst("(cond ? msg_a : msg_b).child.payload.single_int64");
    Program program = PLANNER.plan(ast);
    NestedTestAllTypes msgA = newNestedTestAllTypes(42L);
    NestedTestAllTypes msgB = newNestedTestAllTypes(99L);

    Object result = program.eval(ImmutableMap.of("cond", false, "msg_a", msgA, "msg_b", msgB));

    assertThat(result).isEqualTo(99L);
  }

  @Test
  public void plan_celAttribute_complexPlannedOperand_unsetField_returnsDefaultValue()
      throws Exception {
    CelAbstractSyntaxTree ast =
        optimizeSelectAst("(cond ? msg_a : msg_b).child.payload.single_int64");
    Program program = PLANNER.plan(ast);
    NestedTestAllTypes msgA = NestedTestAllTypes.getDefaultInstance();
    NestedTestAllTypes msgB = newNestedTestAllTypes(99L);

    Object result = program.eval(ImmutableMap.of("cond", true, "msg_a", msgA, "msg_b", msgB));

    assertThat(result).isEqualTo(0L);
  }

  @Test
  public void plan_celHasField_complexPlannedOperand_fieldPresent_returnsTrue() throws Exception {
    CelAbstractSyntaxTree ast =
        optimizeSelectAst("has((cond ? msg_a : msg_b).child.payload.single_int64)");
    Program program = PLANNER.plan(ast);
    NestedTestAllTypes msgA = newNestedTestAllTypes(42L);
    NestedTestAllTypes msgB = NestedTestAllTypes.getDefaultInstance();

    Object result = program.eval(ImmutableMap.of("cond", true, "msg_a", msgA, "msg_b", msgB));

    assertThat(result).isEqualTo(true);
  }

  @Test
  public void plan_celHasField_complexPlannedOperand_fieldUnset_returnsFalse() throws Exception {
    CelAbstractSyntaxTree ast =
        optimizeSelectAst("has((cond ? msg_a : msg_b).child.payload.single_int64)");
    Program program = PLANNER.plan(ast);
    NestedTestAllTypes msgA = newNestedTestAllTypes(42L);
    NestedTestAllTypes msgB = NestedTestAllTypes.getDefaultInstance();

    Object result = program.eval(ImmutableMap.of("cond", false, "msg_a", msgA, "msg_b", msgB));

    assertThat(result).isEqualTo(false);
  }

  @Test
  public void plan_celAttribute_complexPlannedOperand_shortCircuitsUnusedBranchWithoutThrowing()
      throws Exception {
    CelAbstractSyntaxTree ast =
        optimizeSelectAst("(cond ? msg_a : error()).child.payload.single_int64");
    Program program = PLANNER.plan(ast);
    NestedTestAllTypes msgA = newNestedTestAllTypes(42L);

    Object result = program.eval(ImmutableMap.of("cond", true, "msg_a", msgA));

    assertThat(result).isEqualTo(42L);
  }

  @Test
  public void plan_celHasField_complexPlannedOperand_shortCircuitsUnusedBranchWithoutThrowing()
      throws Exception {
    CelAbstractSyntaxTree ast =
        optimizeSelectAst("has((cond ? msg_a : error()).child.payload.single_int64)");
    Program program = PLANNER.plan(ast);
    NestedTestAllTypes msgA = newNestedTestAllTypes(42L);

    Object result = program.eval(ImmutableMap.of("cond", true, "msg_a", msgA));

    assertThat(result).isEqualTo(true);
  }

  @Test
  public void plan_celAttribute_complexPlannedOperand_withPartialVarsUnknownCond_returnsUnknown()
      throws Exception {
    CelAbstractSyntaxTree ast =
        optimizeSelectAst("(cond ? msg_a : msg_b).child.payload.single_int64");
    Program program = PLANNER.plan(ast);
    NestedTestAllTypes msgA = newNestedTestAllTypes(42L);
    NestedTestAllTypes msgB = newNestedTestAllTypes(99L);
    PartialVars partialVars =
        PartialVars.of(
            ImmutableMap.of("msg_a", msgA, "msg_b", msgB),
            CelAttributePattern.fromQualifiedIdentifier("cond"));

    Object result = program.eval(partialVars);

    assertThat(result).isInstanceOf(CelUnknownSet.class);
    assertThat(((CelUnknownSet) result).attributes())
        .containsExactly(CelAttribute.fromQualifiedIdentifier("cond"));
  }

  @Test
  public void plan_celAttribute_complexPlannedOperand_withPartialVarsUnknownBranch_returnsUnknown()
      throws Exception {
    CelAbstractSyntaxTree ast =
        optimizeSelectAst("(cond ? msg_a : msg_b).child.payload.single_int64");
    Program program = PLANNER.plan(ast);
    NestedTestAllTypes msgB = newNestedTestAllTypes(99L);
    PartialVars partialVars =
        PartialVars.of(
            ImmutableMap.of("cond", true, "msg_b", msgB),
            CelAttributePattern.fromQualifiedIdentifier("msg_a"));

    Object result = program.eval(partialVars);

    assertThat(result).isInstanceOf(CelUnknownSet.class);
    assertThat(((CelUnknownSet) result).attributes())
        .containsExactly(CelAttribute.fromQualifiedIdentifier("msg_a"));
  }

  @Test
  public void plan_celHasField_complexPlannedOperand_withPartialVarsUnknownCond_returnsUnknown()
      throws Exception {
    CelAbstractSyntaxTree ast =
        optimizeSelectAst("has((cond ? msg_a : msg_b).child.payload.single_int64)");
    Program program = PLANNER.plan(ast);
    NestedTestAllTypes msgA = newNestedTestAllTypes(42L);
    NestedTestAllTypes msgB = newNestedTestAllTypes(99L);
    PartialVars partialVars =
        PartialVars.of(
            ImmutableMap.of("msg_a", msgA, "msg_b", msgB),
            CelAttributePattern.fromQualifiedIdentifier("cond"));

    Object result = program.eval(partialVars);

    assertThat(result).isInstanceOf(CelUnknownSet.class);
    assertThat(((CelUnknownSet) result).attributes())
        .containsExactly(CelAttribute.fromQualifiedIdentifier("cond"));
  }

  @Test
  public void plan_celHasField_complexPlannedOperand_withPartialVarsUnknownBranch_returnsUnknown()
      throws Exception {
    CelAbstractSyntaxTree ast =
        optimizeSelectAst("has((cond ? msg_a : msg_b).child.payload.single_int64)");
    Program program = PLANNER.plan(ast);
    NestedTestAllTypes msgB = newNestedTestAllTypes(99L);
    PartialVars partialVars =
        PartialVars.of(
            ImmutableMap.of("cond", true, "msg_b", msgB),
            CelAttributePattern.fromQualifiedIdentifier("msg_a"));

    Object result = program.eval(partialVars);

    assertThat(result).isInstanceOf(CelUnknownSet.class);
    assertThat(((CelUnknownSet) result).attributes())
        .containsExactly(CelAttribute.fromQualifiedIdentifier("msg_a"));
  }

  @Test
  public void plan_celAttribute_listIndexOperand_evaluatesExpectedValue() throws Exception {
    CelAbstractSyntaxTree ast = optimizeSelectAst("[msg_a, msg_b][1].child.payload.single_int64");
    Program program = PLANNER.plan(ast);
    NestedTestAllTypes msgA = newNestedTestAllTypes(42L);
    NestedTestAllTypes msgB = newNestedTestAllTypes(99L);

    Object result = program.eval(ImmutableMap.of("msg_a", msgA, "msg_b", msgB));

    assertThat(result).isEqualTo(99L);
  }

  @Test
  public void plan_celHasField_listIndexOperand_evaluatesExpectedValue() throws Exception {
    CelAbstractSyntaxTree ast =
        optimizeSelectAst("has([msg_a, msg_b][1].child.payload.single_int64)");
    Program program = PLANNER.plan(ast);
    NestedTestAllTypes msgA = newNestedTestAllTypes(42L);
    NestedTestAllTypes msgB = newNestedTestAllTypes(99L);

    Object result = program.eval(ImmutableMap.of("msg_a", msgA, "msg_b", msgB));

    assertThat(result).isEqualTo(true);
  }

  @Test
  public void plan_celAttribute_mapEntryOperand_evaluatesPopulatedValue() throws Exception {
    CelAbstractSyntaxTree ast = optimizeSelectAst("map_var.key.single_int64");
    Program program = PLANNER.plan(ast);
    TestAllTypes msg = TestAllTypes.newBuilder().setSingleInt64(42L).build();

    Object result = program.eval(ImmutableMap.of("map_var", ImmutableMap.of("key", msg)));

    assertThat(result).isEqualTo(42L);
  }

  @Test
  public void plan_comprehension_evaluatesExpectedResult(
      @TestParameter ComprehensionTestCase testCase) throws Exception {
    CelAbstractSyntaxTree ast = optimizeSelectAst(testCase.expression);
    Program program = PLANNER.plan(ast);

    Object result = program.eval(testCase.input);

    assertThat(result).isEqualTo(testCase.expectedResult);
  }

  @Test
  public void plan_celAttribute_defaultValues_returnsExpectedDefault(
      @TestParameter DefaultValueTestCase testCase) throws Exception {
    CelAbstractSyntaxTree ast = optimizeSelectAst(testCase.expression);
    Program program = PLANNER.plan(ast);

    Object result = program.eval(ImmutableMap.of("msg", TestAllTypes.getDefaultInstance()));

    assertThat(result).isEqualTo(testCase.expected);
  }

  @Test
  public void plan_partialVars_evaluatesResolvedValue(
      @TestParameter ResolvedPartialVarsTestCase testCase) throws Exception {
    CelAbstractSyntaxTree ast = optimizeSelectAst(testCase.expression);
    Program program = PLANNER.plan(ast);

    Object result = program.eval(testCase.partialVars);

    assertThat(result).isEqualTo(testCase.expectedResult);
  }

  @Test
  public void plan_partialVars_returnsUnknownSet(@TestParameter UnknownPartialVarsTestCase testCase)
      throws Exception {
    CelAbstractSyntaxTree ast = optimizeSelectAst(testCase.expression);
    Program program = PLANNER.plan(ast);

    Object result = program.eval(testCase.partialVars);

    assertThat(result).isInstanceOf(CelUnknownSet.class);
    assertThat(((CelUnknownSet) result).attributes()).isEqualTo(testCase.expectedAttributes);
  }

  @Test
  public void plan_evaluationError_throwsExpectedException(
      @TestParameter EvaluationErrorTestCase testCase) throws Exception {
    CelAbstractSyntaxTree ast = optimizeSelectAst(testCase.expression);
    Program program = PLANNER.plan(ast);

    CelEvaluationException e =
        assertThrows(CelEvaluationException.class, () -> program.eval(testCase.input));

    assertThat(e).hasCauseThat().isInstanceOf(testCase.expectedCause);
    assertThat(e).hasMessageThat().contains(testCase.expectedMessageSubstring);
  }

  @Test
  public void plan_optionalSelect_onOptimizedPrefix_withPartialVarsUnknown_returnsUnknown()
      throws Exception {
    CelAbstractSyntaxTree prefixAst = optimizeSelectAst("msg.single_nested_message");
    CelAbstractSyntaxTree wrapperAst = CEL.parse("dummy.?bb").getAst();
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
  public void plan_invalidAst_wrongArgCount_throwsEvaluationException() {
    CelAbstractSyntaxTree ast =
        CelAbstractSyntaxTree.newParsedAst(
            CelExpr.ofCall(
                1L,
                OptimizedSelectPlanner.CEL_ATTRIBUTE_FUNCTION_NAME,
                ImmutableList.of(CelExpr.ofIdent(2L, "msg"))),
            CelSource.newBuilder().build());

    CelEvaluationException e = assertThrows(CelEvaluationException.class, () -> PLANNER.plan(ast));

    assertThat(e).hasCauseThat().isInstanceOf(IllegalArgumentException.class);
    assertThat(e).hasMessageThat().contains("Expected 3 arguments for cel.@attribute, found 1");
  }

  @Test
  public void plan_invalidAst_malformedHop_throwsEvaluationException() {
    CelAbstractSyntaxTree ast =
        CelAbstractSyntaxTree.newParsedAst(
            CelExpr.ofCall(
                1L,
                OptimizedSelectPlanner.CEL_ATTRIBUTE_FUNCTION_NAME,
                ImmutableList.of(
                    CelExpr.ofIdent(2L, "msg"),
                    CelExpr.ofList(
                        3L,
                        ImmutableList.of(
                            CelExpr.ofList(
                                4L,
                                ImmutableList.of(CelExpr.ofConstant(5L, CelConstant.ofValue(1L))),
                                ImmutableList.of())),
                        ImmutableList.of()),
                    CelExpr.ofConstant(6L, CelConstant.ofValue(0L)))),
            CelSource.newBuilder().build());

    CelEvaluationException e = assertThrows(CelEvaluationException.class, () -> PLANNER.plan(ast));

    assertThat(e).hasCauseThat().isInstanceOf(IllegalArgumentException.class);
    assertThat(e)
        .hasMessageThat()
        .contains("Expected qualifier hop for cel.@attribute to contain 3 or 4 elements");
  }

  @Test
  public void plan_invalidAst_messageTypeCodeWithScalarDummy_throwsEvaluationException() {
    CelAbstractSyntaxTree ast =
        CelAbstractSyntaxTree.newParsedAst(
            CelExpr.ofCall(
                1L,
                OptimizedSelectPlanner.CEL_ATTRIBUTE_FUNCTION_NAME,
                ImmutableList.of(
                    CelExpr.ofIdent(2L, "msg"),
                    CelExpr.ofList(
                        3L,
                        ImmutableList.of(
                            CelExpr.ofList(
                                4L,
                                ImmutableList.of(
                                    CelExpr.ofConstant(5L, CelConstant.ofValue(1L)),
                                    CelExpr.ofConstant(
                                        6L, CelConstant.ofValue("single_nested_message")),
                                    CelExpr.ofConstant(7L, CelConstant.ofValue(11L))),
                                ImmutableList.of())),
                        ImmutableList.of()),
                    CelExpr.ofConstant(8L, CelConstant.ofValue(0L)))),
            CelSource.newBuilder().build());

    CelEvaluationException e = assertThrows(CelEvaluationException.class, () -> PLANNER.plan(ast));

    assertThat(e).hasCauseThat().isInstanceOf(IllegalArgumentException.class);
    assertThat(e).hasMessageThat().contains("Expected struct for message type, found:");
  }

  @Test
  public void plan_invalidAst_unsupportedWellKnownType_throwsEvaluationException() {
    CelAbstractSyntaxTree ast =
        CelAbstractSyntaxTree.newParsedAst(
            CelExpr.ofCall(
                1L,
                OptimizedSelectPlanner.CEL_ATTRIBUTE_FUNCTION_NAME,
                ImmutableList.of(
                    CelExpr.ofIdent(2L, "msg"),
                    CelExpr.ofList(
                        3L,
                        ImmutableList.of(
                            CelExpr.ofList(
                                4L,
                                ImmutableList.of(
                                    CelExpr.ofConstant(5L, CelConstant.ofValue(105L)),
                                    CelExpr.ofConstant(
                                        6L, CelConstant.ofValue("single_int64_wrapper")),
                                    CelExpr.ofConstant(7L, CelConstant.ofValue(11L))),
                                ImmutableList.of())),
                        ImmutableList.of()),
                    CelExpr.ofStruct(8L, "google.protobuf.Int64Value", ImmutableList.of()))),
            CelSource.newBuilder().build());

    CelEvaluationException e = assertThrows(CelEvaluationException.class, () -> PLANNER.plan(ast));

    assertThat(e).hasCauseThat().isInstanceOf(IllegalArgumentException.class);
    assertThat(e)
        .hasMessageThat()
        .contains("Leaf well-known type 'google.protobuf.Int64Value' is not supported");
  }

  @Test
  public void plan_invalidAst_nonMapHopWithFourElements_throwsEvaluationException() {
    CelAbstractSyntaxTree ast =
        CelAbstractSyntaxTree.newParsedAst(
            CelExpr.ofCall(
                1L,
                OptimizedSelectPlanner.CEL_ATTRIBUTE_FUNCTION_NAME,
                ImmutableList.of(
                    CelExpr.ofIdent(2L, "msg"),
                    CelExpr.ofList(
                        3L,
                        ImmutableList.of(
                            CelExpr.ofList(
                                4L,
                                ImmutableList.of(
                                    CelExpr.ofConstant(5L, CelConstant.ofValue(32L)),
                                    CelExpr.ofConstant(6L, CelConstant.ofValue("repeated_int64")),
                                    CelExpr.ofConstant(7L, CelConstant.ofValue(3L)),
                                    CelExpr.ofConstant(8L, CelConstant.ofValue(0L))),
                                ImmutableList.of())),
                        ImmutableList.of()),
                    CelExpr.ofList(
                        9L,
                        ImmutableList.of(CelExpr.ofConstant(10L, CelConstant.ofValue(0L))),
                        ImmutableList.of()))),
            CelSource.newBuilder().build());

    CelEvaluationException e = assertThrows(CelEvaluationException.class, () -> PLANNER.plan(ast));

    assertThat(e).hasCauseThat().isInstanceOf(IllegalArgumentException.class);
    assertThat(e).hasMessageThat().contains("Leaf qualifier hop must contain exactly 3 elements");
  }

  @Test
  public void plan_invalidAst_mapHopWithMissingEntrySpec_throwsEvaluationException() {
    CelAbstractSyntaxTree ast =
        CelAbstractSyntaxTree.newParsedAst(
            CelExpr.ofCall(
                1L,
                OptimizedSelectPlanner.CEL_ATTRIBUTE_FUNCTION_NAME,
                ImmutableList.of(
                    CelExpr.ofIdent(2L, "msg"),
                    CelExpr.ofList(
                        3L,
                        ImmutableList.of(
                            CelExpr.ofList(
                                4L,
                                ImmutableList.of(
                                    CelExpr.ofConstant(5L, CelConstant.ofValue(1L)),
                                    CelExpr.ofConstant(
                                        6L, CelConstant.ofValue("map_string_string")),
                                    CelExpr.ofConstant(7L, CelConstant.ofValue(-1L))),
                                ImmutableList.of())),
                        ImmutableList.of()),
                    CelExpr.ofMap(
                        8L,
                        ImmutableList.of(
                            CelExpr.ofMapEntry(
                                9L,
                                CelExpr.ofConstant(10L, CelConstant.ofValue("")),
                                CelExpr.ofConstant(11L, CelConstant.ofValue("")),
                                /* isOptionalEntry= */ false))))),
            CelSource.newBuilder().build());

    CelEvaluationException e = assertThrows(CelEvaluationException.class, () -> PLANNER.plan(ast));

    assertThat(e).hasCauseThat().isInstanceOf(IllegalArgumentException.class);
    assertThat(e).hasMessageThat().contains("Map qualifier hop must contain exactly 4 elements");
  }

  @Test
  public void plan_invalidAst_mapEntrySpecMalformed_throwsEvaluationException() {
    CelAbstractSyntaxTree ast =
        CelAbstractSyntaxTree.newParsedAst(
            CelExpr.ofCall(
                1L,
                OptimizedSelectPlanner.CEL_ATTRIBUTE_FUNCTION_NAME,
                ImmutableList.of(
                    CelExpr.ofIdent(2L, "msg"),
                    CelExpr.ofList(
                        3L,
                        ImmutableList.of(
                            CelExpr.ofList(
                                4L,
                                ImmutableList.of(
                                    CelExpr.ofConstant(5L, CelConstant.ofValue(1L)),
                                    CelExpr.ofConstant(
                                        6L, CelConstant.ofValue("map_string_string")),
                                    CelExpr.ofConstant(7L, CelConstant.ofValue(-1L)),
                                    CelExpr.ofList(
                                        8L,
                                        ImmutableList.of(
                                            CelExpr.ofConstant(9L, CelConstant.ofValue(9L)),
                                            CelExpr.ofConstant(10L, CelConstant.ofValue(9L)),
                                            CelExpr.ofConstant(11L, CelConstant.ofValue(9L))),
                                        ImmutableList.of())),
                                ImmutableList.of())),
                        ImmutableList.of()),
                    CelExpr.ofMap(
                        12L,
                        ImmutableList.of(
                            CelExpr.ofMapEntry(
                                13L,
                                CelExpr.ofConstant(14L, CelConstant.ofValue("")),
                                CelExpr.ofConstant(15L, CelConstant.ofValue("")),
                                /* isOptionalEntry= */ false))))),
            CelSource.newBuilder().build());

    CelEvaluationException e = assertThrows(CelEvaluationException.class, () -> PLANNER.plan(ast));

    assertThat(e).hasCauseThat().isInstanceOf(IllegalArgumentException.class);
    assertThat(e)
        .hasMessageThat()
        .contains(
            "Expected map entry spec list to contain exactly 2 elements (key_type_code,"
                + " val_type_code), found: 3");
  }

  @Test
  public void plan_invalidAst_repeatedFieldWithEmptyListDummy_throwsEvaluationException() {
    CelAbstractSyntaxTree ast =
        CelAbstractSyntaxTree.newParsedAst(
            CelExpr.ofCall(
                1L,
                OptimizedSelectPlanner.CEL_ATTRIBUTE_FUNCTION_NAME,
                ImmutableList.of(
                    CelExpr.ofIdent(2L, "msg"),
                    CelExpr.ofList(
                        3L,
                        ImmutableList.of(
                            CelExpr.ofList(
                                4L,
                                ImmutableList.of(
                                    CelExpr.ofConstant(5L, CelConstant.ofValue(51L)),
                                    CelExpr.ofConstant(
                                        6L, CelConstant.ofValue("repeated_nested_message")),
                                    CelExpr.ofConstant(7L, CelConstant.ofValue(11L))),
                                ImmutableList.of())),
                        ImmutableList.of()),
                    CelExpr.ofList(8L, ImmutableList.of(), ImmutableList.of()))),
            CelSource.newBuilder().build());

    CelEvaluationException e = assertThrows(CelEvaluationException.class, () -> PLANNER.plan(ast));

    assertThat(e).hasCauseThat().isInstanceOf(IllegalArgumentException.class);
    assertThat(e)
        .hasMessageThat()
        .contains("Expected repeated dummy/default value with a single element, found:");
  }

  private enum ScalarDummyMismatchTestCase {
    MAP_KEY(
        ImmutableList.of(
            CelExpr.ofConstant(5L, CelConstant.ofValue(1L)),
            CelExpr.ofConstant(6L, CelConstant.ofValue("map_string_string")),
            CelExpr.ofConstant(7L, CelConstant.ofValue(-1L)),
            mapEntrySpec(9L, 9L)),
        CelExpr.ofMap(
            20L,
            ImmutableList.of(
                CelExpr.ofMapEntry(
                    21L,
                    CelExpr.ofConstant(22L, CelConstant.ofValue(0L)),
                    CelExpr.ofConstant(23L, CelConstant.ofValue("")),
                    /* isOptionalEntry= */ false)))),
    MAP_VALUE(
        ImmutableList.of(
            CelExpr.ofConstant(5L, CelConstant.ofValue(1L)),
            CelExpr.ofConstant(6L, CelConstant.ofValue("map_string_string")),
            CelExpr.ofConstant(7L, CelConstant.ofValue(-1L)),
            mapEntrySpec(9L, 9L)),
        CelExpr.ofMap(
            20L,
            ImmutableList.of(
                CelExpr.ofMapEntry(
                    21L,
                    CelExpr.ofConstant(22L, CelConstant.ofValue("")),
                    CelExpr.ofConstant(23L, CelConstant.ofValue(0L)),
                    /* isOptionalEntry= */ false)))),
    REPEATED_ELEMENT(
        ImmutableList.of(
            CelExpr.ofConstant(5L, CelConstant.ofValue(32L)),
            CelExpr.ofConstant(6L, CelConstant.ofValue("repeated_int64")),
            CelExpr.ofConstant(7L, CelConstant.ofValue(3L))),
        CelExpr.ofList(
            20L,
            ImmutableList.of(CelExpr.ofConstant(21L, CelConstant.ofValue(""))),
            ImmutableList.of()));

    private final ImmutableList<CelExpr> hopElements;
    private final CelExpr dummy;

    private static CelExpr mapEntrySpec(long keyTypeCode, long valueTypeCode) {
      return CelExpr.ofList(
          8L,
          ImmutableList.of(
              CelExpr.ofConstant(9L, CelConstant.ofValue(keyTypeCode)),
              CelExpr.ofConstant(10L, CelConstant.ofValue(valueTypeCode))),
          ImmutableList.of());
    }

    ScalarDummyMismatchTestCase(ImmutableList<CelExpr> hopElements, CelExpr dummy) {
      this.hopElements = hopElements;
      this.dummy = dummy;
    }
  }

  @Test
  public void plan_invalidAst_scalarDummyIncompatibleWithTypeCode_throwsEvaluationException(
      @TestParameter ScalarDummyMismatchTestCase testCase) {
    CelAbstractSyntaxTree ast =
        CelAbstractSyntaxTree.newParsedAst(
            CelExpr.ofCall(
                1L,
                OptimizedSelectPlanner.CEL_ATTRIBUTE_FUNCTION_NAME,
                ImmutableList.of(
                    CelExpr.ofIdent(2L, "msg"),
                    CelExpr.ofList(
                        3L,
                        ImmutableList.of(
                            CelExpr.ofList(4L, testCase.hopElements, ImmutableList.of())),
                        ImmutableList.of()),
                    testCase.dummy)),
            CelSource.newBuilder().build());

    CelEvaluationException e = assertThrows(CelEvaluationException.class, () -> PLANNER.plan(ast));

    assertThat(e).hasCauseThat().isInstanceOf(IllegalArgumentException.class);
    assertThat(e).hasMessageThat().contains("is incompatible with type code");
  }

  @Test
  public void plan_invalidAst_mapFieldWithEmptyMapDummy_throwsEvaluationException() {
    CelAbstractSyntaxTree ast =
        CelAbstractSyntaxTree.newParsedAst(
            CelExpr.ofCall(
                1L,
                OptimizedSelectPlanner.CEL_ATTRIBUTE_FUNCTION_NAME,
                ImmutableList.of(
                    CelExpr.ofIdent(2L, "msg"),
                    CelExpr.ofList(
                        3L,
                        ImmutableList.of(
                            CelExpr.ofList(
                                4L,
                                ImmutableList.of(
                                    CelExpr.ofConstant(5L, CelConstant.ofValue(1L)),
                                    CelExpr.ofConstant(
                                        6L, CelConstant.ofValue("map_string_string")),
                                    CelExpr.ofConstant(7L, CelConstant.ofValue(-1L)),
                                    CelExpr.ofList(
                                        8L,
                                        ImmutableList.of(
                                            CelExpr.ofConstant(9L, CelConstant.ofValue(9L)),
                                            CelExpr.ofConstant(10L, CelConstant.ofValue(9L))),
                                        ImmutableList.of())),
                                ImmutableList.of())),
                        ImmutableList.of()),
                    CelExpr.ofMap(11L, ImmutableList.of()))),
            CelSource.newBuilder().build());

    CelEvaluationException e = assertThrows(CelEvaluationException.class, () -> PLANNER.plan(ast));

    assertThat(e).hasCauseThat().isInstanceOf(IllegalArgumentException.class);
    assertThat(e)
        .hasMessageThat()
        .contains("Expected map dummy/default value with a single entry, found:");
  }

  @Test
  public void plan_invalidAst_repeatedMessageWithUnsupportedWellKnownType_throwsEvaluationException(
      @TestParameter({"google.protobuf.Int64Value", "google.protobuf.Any"})
          String unsupportedProtoTypeName) {
    CelAbstractSyntaxTree ast =
        CelAbstractSyntaxTree.newParsedAst(
            CelExpr.ofCall(
                1L,
                OptimizedSelectPlanner.CEL_ATTRIBUTE_FUNCTION_NAME,
                ImmutableList.of(
                    CelExpr.ofIdent(2L, "msg"),
                    CelExpr.ofList(
                        3L,
                        ImmutableList.of(
                            CelExpr.ofList(
                                4L,
                                ImmutableList.of(
                                    CelExpr.ofConstant(5L, CelConstant.ofValue(51L)),
                                    CelExpr.ofConstant(
                                        6L, CelConstant.ofValue("repeated_nested_message")),
                                    CelExpr.ofConstant(7L, CelConstant.ofValue(11L))),
                                ImmutableList.of())),
                        ImmutableList.of()),
                    CelExpr.ofList(
                        8L,
                        ImmutableList.of(
                            CelExpr.ofStruct(9L, unsupportedProtoTypeName, ImmutableList.of())),
                        ImmutableList.of()))),
            CelSource.newBuilder().build());

    CelEvaluationException e = assertThrows(CelEvaluationException.class, () -> PLANNER.plan(ast));

    assertThat(e).hasCauseThat().isInstanceOf(IllegalArgumentException.class);
    assertThat(e)
        .hasMessageThat()
        .contains("Leaf well-known type '" + unsupportedProtoTypeName + "' is not supported");
  }

  @Test
  public void plan_invalidAst_messageStructWithEmptyMessageName_throwsEvaluationException() {
    CelAbstractSyntaxTree ast =
        CelAbstractSyntaxTree.newParsedAst(
            CelExpr.ofCall(
                1L,
                OptimizedSelectPlanner.CEL_ATTRIBUTE_FUNCTION_NAME,
                ImmutableList.of(
                    CelExpr.ofIdent(2L, "msg"),
                    CelExpr.ofList(
                        3L,
                        ImmutableList.of(
                            CelExpr.ofList(
                                4L,
                                ImmutableList.of(
                                    CelExpr.ofConstant(5L, CelConstant.ofValue(51L)),
                                    CelExpr.ofConstant(
                                        6L, CelConstant.ofValue("repeated_nested_message")),
                                    CelExpr.ofConstant(7L, CelConstant.ofValue(11L))),
                                ImmutableList.of())),
                        ImmutableList.of()),
                    CelExpr.ofList(
                        8L,
                        ImmutableList.of(CelExpr.ofStruct(9L, "", ImmutableList.of())),
                        ImmutableList.of()))),
            CelSource.newBuilder().build());

    CelEvaluationException e = assertThrows(CelEvaluationException.class, () -> PLANNER.plan(ast));

    assertThat(e).hasCauseThat().isInstanceOf(IllegalArgumentException.class);
    assertThat(e).hasMessageThat().contains("Protobuf message type name must not be empty");
  }

  private static CelAbstractSyntaxTree optimizeSelectAst(String expression) throws Exception {
    CelAbstractSyntaxTree ast = CEL.compile(expression).getAst();
    CelAbstractSyntaxTree optimizedAst = SELECT_OPTIMIZER.optimize(ast);
    return CelAbstractSyntaxTree.newParsedAst(optimizedAst.getExpr(), optimizedAst.getSource());
  }

  private static DefaultDispatcher newDispatcher() {
    DefaultDispatcher.Builder builder = DefaultDispatcher.newBuilder();
    CelStandardFunctions stdFunctions = CelStandardFunctions.newBuilder().build();
    for (CelFunctionBinding binding :
        stdFunctions.newFunctionBindings(RUNTIME_EQUALITY, CEL_OPTIONS)) {
      builder.addOverload(
          ((InternalCelFunctionBinding) binding).getFunctionName(),
          binding.getOverloadId(),
          binding.getArgTypes(),
          binding.isStrict(),
          binding.getDefinition());
    }
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

  private static NestedTestAllTypes newNestedTestAllTypes(long singleInt64) {
    return NestedTestAllTypes.newBuilder()
        .setChild(
            NestedTestAllTypes.newBuilder()
                .setPayload(TestAllTypes.newBuilder().setSingleInt64(singleInt64)))
        .build();
  }

  private static ImmutableMap<String, Object> newMapWithNullValue() {
    Map<String, Object> mapWithNull = new HashMap<>();
    mapWithNull.put("null_key", null);
    return ImmutableMap.of("map_var", mapWithNull);
  }

  @SuppressWarnings("ImmutableEnumChecker") // Test only
  private enum ResolvedPartialVarsTestCase {
    CANDIDATE_PRECEDENCE_KNOWN_HIGHER_PRIORITY_WINS(
        "has(b.single_int32)",
        PartialVars.of(
            ImmutableMap.of(
                "cel.expr.conformance.proto3.b",
                TestAllTypes.newBuilder().setSingleInt32(99).build()),
            CelAttributePattern.create("b")
                .qualify(CelAttribute.Qualifier.ofString("single_int32"))),
        true),
    ATTRIBUTE_SIBLING_UNKNOWN_EVALUATES_SUCCESSFULLY(
        "msg.single_nested_message.bb",
        PartialVars.of(
            ImmutableMap.of(
                "msg",
                TestAllTypes.newBuilder()
                    .setSingleInt64(10L)
                    .setSingleNestedMessage(TestAllTypes.NestedMessage.newBuilder().setBb(42))
                    .build()),
            CelAttributePattern.fromQualifiedIdentifier("msg.single_int64")),
        42L),
    HAS_FIELD_SIBLING_UNKNOWN_EVALUATES_SUCCESSFULLY(
        "has(msg.single_nested_message.bb)",
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
    private final Object expectedResult;

    ResolvedPartialVarsTestCase(String expression, PartialVars partialVars, Object expectedResult) {
      this.expression = expression;
      this.partialVars = partialVars;
      this.expectedResult = expectedResult;
    }
  }

  @SuppressWarnings("ImmutableEnumChecker") // Test only
  private enum UnknownPartialVarsTestCase {
    CANDIDATE_PRECEDENCE_UNKNOWN_HIGHER_PRIORITY_WINS_OVER_KNOWN_FALLBACK(
        "has(b.single_int32)",
        PartialVars.of(
            ImmutableMap.of("b", TestAllTypes.newBuilder().setSingleInt32(99).build()),
            CelAttributePattern.fromQualifiedIdentifier(
                "cel.expr.conformance.proto3.b.single_int32")),
        ImmutableSet.of(
            CelAttribute.fromQualifiedIdentifier("cel.expr.conformance.proto3.b.single_int32"))),
    CANDIDATE_PRECEDENCE_UNKNOWN_RETURNED_WHEN_HIGHER_ABSENT(
        "has(b.single_int32)",
        PartialVars.of(
            CelAttributePattern.create("b")
                .qualify(CelAttribute.Qualifier.ofString("single_int32"))),
        ImmutableSet.of(CelAttribute.fromQualifiedIdentifier("b.single_int32"))),
    ATTRIBUTE_TARGET_UNKNOWN_RETURNS_UNKNOWN(
        "msg.single_nested_message.bb",
        PartialVars.of(
            ImmutableMap.of(
                "msg",
                TestAllTypes.newBuilder()
                    .setSingleNestedMessage(TestAllTypes.NestedMessage.newBuilder().setBb(42))
                    .build()),
            CelAttributePattern.fromQualifiedIdentifier("msg.single_nested_message.bb")),
        ImmutableSet.of(CelAttribute.fromQualifiedIdentifier("msg.single_nested_message.bb"))),
    HAS_FIELD_TARGET_UNKNOWN_RETURNS_UNKNOWN(
        "has(msg.single_nested_message.bb)",
        PartialVars.of(
            ImmutableMap.of(
                "msg",
                TestAllTypes.newBuilder()
                    .setSingleNestedMessage(TestAllTypes.NestedMessage.newBuilder().setBb(42))
                    .build()),
            CelAttributePattern.fromQualifiedIdentifier("msg.single_nested_message.bb")),
        ImmutableSet.of(CelAttribute.fromQualifiedIdentifier("msg.single_nested_message.bb"))),
    ATTRIBUTE_INTERMEDIATE_HOP_UNKNOWN_RETURNS_UNKNOWN(
        "msg.single_nested_message.bb",
        PartialVars.of(
            ImmutableMap.of(
                "msg",
                TestAllTypes.newBuilder()
                    .setSingleNestedMessage(TestAllTypes.NestedMessage.newBuilder().setBb(42))
                    .build()),
            CelAttributePattern.fromQualifiedIdentifier("msg.single_nested_message")),
        ImmutableSet.of(CelAttribute.fromQualifiedIdentifier("msg.single_nested_message")));

    private final String expression;
    private final PartialVars partialVars;
    private final ImmutableSet<CelAttribute> expectedAttributes;

    UnknownPartialVarsTestCase(
        String expression, PartialVars partialVars, ImmutableSet<CelAttribute> expectedAttributes) {
      this.expression = expression;
      this.partialVars = partialVars;
      this.expectedAttributes = expectedAttributes;
    }
  }

  @SuppressWarnings("ImmutableEnumChecker") // Test only
  private enum EvaluationErrorTestCase {
    HAS_FIELD_ERROR_IN_OPERAND(
        "has(error().child.payload.single_int64)",
        ImmutableMap.of(),
        IllegalArgumentException.class,
        "Function 'error' failed"),
    ATTRIBUTE_MISSING_MAP_KEY(
        "map_var.key.single_int64",
        ImmutableMap.of("map_var", ImmutableMap.of()),
        CelAttributeNotFoundException.class,
        "key 'key' is not present in map"),
    ATTRIBUTE_NULL_BOUND_MAP_KEY(
        "map_var.null_key.single_int64",
        newMapWithNullValue(),
        CelInvalidArgumentException.class,
        "Map value cannot be null for key: null_key"),
    ATTRIBUTE_UNBOUND_ROOT_VARIABLE(
        "msg.single_int64", ImmutableMap.of(), CelAttributeNotFoundException.class, "msg");

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

  @SuppressWarnings("ImmutableEnumChecker") // Test only
  private enum DefaultValueTestCase {
    BOOL("msg.single_bool", false),
    STRING("msg.single_string", ""),
    BYTES("msg.single_bytes", CelByteString.EMPTY),
    INT64("msg.single_int64", 0L),
    UINT64("msg.single_uint64", UnsignedLong.ZERO),
    DOUBLE("msg.single_double", 0.0d),
    LIST("msg.repeated_int64", ImmutableList.of()),
    MAP("msg.map_string_string", ImmutableMap.of()),
    ENUM("msg.single_nested_enum", 0L),
    DURATION("msg.single_duration", Duration.ZERO),
    TIMESTAMP("msg.single_timestamp", Instant.EPOCH);

    private final String expression;
    private final Object expected;

    DefaultValueTestCase(String expression, Object expected) {
      this.expression = expression;
      this.expected = expected;
    }
  }

  @SuppressWarnings("ImmutableEnumChecker") // Test only
  private enum ComprehensionTestCase {
    MAP(
        "[msg_a, msg_b].map(x, x.child.payload.single_int64)",
        ImmutableMap.of("msg_a", newNestedTestAllTypes(42L), "msg_b", newNestedTestAllTypes(99L)),
        ImmutableList.of(42L, 99L)),
    MAP_WITH_UNSET_MESSAGE(
        "[msg_a, msg_b].map(x, x.child.payload.single_int64)",
        ImmutableMap.of(
            "msg_a", newNestedTestAllTypes(42L),
            "msg_b", NestedTestAllTypes.getDefaultInstance()),
        ImmutableList.of(42L, 0L)),
    FILTER(
        "[msg_a, msg_b].filter(x, has(x.child.payload.single_int64))",
        ImmutableMap.of(
            "msg_a", newNestedTestAllTypes(42L),
            "msg_b", NestedTestAllTypes.getDefaultInstance()),
        ImmutableList.of(newNestedTestAllTypes(42L))),
    ALL_TRUE(
        "[msg_a, msg_b].all(x, x.child.payload.single_int64 > 0)",
        ImmutableMap.of("msg_a", newNestedTestAllTypes(42L), "msg_b", newNestedTestAllTypes(99L)),
        true),
    ALL_FALSE(
        "[msg].all(m, m.single_int64 > 100)",
        ImmutableMap.of("msg", TestAllTypes.newBuilder().setSingleInt64(42L).build()),
        false),
    EXISTS_TRUE(
        "[msg_a, msg_b].exists(x, x.child.payload.single_int64 == 42)",
        ImmutableMap.of("msg_a", newNestedTestAllTypes(42L), "msg_b", newNestedTestAllTypes(99L)),
        true),
    EXISTS_FALSE(
        "[msg].exists(m, m.single_int64 == 999)",
        ImmutableMap.of("msg", TestAllTypes.newBuilder().setSingleInt64(42L).build()),
        false),
    OVER_REPEATED_FIELD(
        "msg.repeated_nested_message.map(x, x.bb)",
        ImmutableMap.of(
            "msg",
            TestAllTypes.newBuilder()
                .addRepeatedNestedMessage(TestAllTypes.NestedMessage.newBuilder().setBb(10))
                .addRepeatedNestedMessage(TestAllTypes.NestedMessage.newBuilder().setBb(20))
                .build()),
        ImmutableList.of(10L, 20L)),
    OVER_EMPTY_REPEATED_FIELD(
        "msg.repeated_nested_message.map(n, n.bb)",
        ImmutableMap.of("msg", TestAllTypes.getDefaultInstance()),
        ImmutableList.of()),
    COMPLEX_OPERAND_IN_LOOP(
        "[true, false].map(c, (c ? msg_a : msg_b).child.payload.single_int64)",
        ImmutableMap.of("msg_a", newNestedTestAllTypes(42L), "msg_b", newNestedTestAllTypes(99L)),
        ImmutableList.of(42L, 99L)),
    NESTED(
        "[msg, msg].map(m, m.repeated_nested_message.map(n, n.bb))",
        ImmutableMap.of(
            "msg",
            TestAllTypes.newBuilder()
                .addRepeatedNestedMessage(TestAllTypes.NestedMessage.newBuilder().setBb(10))
                .addRepeatedNestedMessage(TestAllTypes.NestedMessage.newBuilder().setBb(20))
                .build()),
        ImmutableList.of(ImmutableList.of(10L, 20L), ImmutableList.of(10L, 20L)));

    private final String expression;
    private final ImmutableMap<String, Object> input;
    private final Object expectedResult;

    ComprehensionTestCase(
        String expression, ImmutableMap<String, Object> input, Object expectedResult) {
      this.expression = expression;
      this.input = input;
      this.expectedResult = expectedResult;
    }
  }
}
