// Copyright 2022 Google LLC
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

package dev.cel.runtime;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.api.expr.v1alpha1.CheckedExpr;
import com.google.api.expr.v1alpha1.Constant;
import com.google.api.expr.v1alpha1.Expr;
import com.google.api.expr.v1alpha1.Type.PrimitiveType;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.protobuf.Any;
import com.google.protobuf.BoolValue;
import com.google.protobuf.ByteString;
import com.google.protobuf.DescriptorProtos.FileDescriptorSet;
import com.google.protobuf.DynamicMessage;
import com.google.rpc.context.AttributeContext;
import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import com.google.testing.junit.testparameterinjector.TestParameters;
import dev.cel.bundle.Cel;
import dev.cel.bundle.CelFactory;
import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.CelContainer;
import dev.cel.common.CelErrorCode;
import dev.cel.common.CelFunctionDecl;
import dev.cel.common.CelOptions;
import dev.cel.common.CelOverloadDecl;
import dev.cel.common.CelProtoV1Alpha1AbstractSyntaxTree;
import dev.cel.common.CelSource;
import dev.cel.common.ast.CelConstant;
import dev.cel.common.ast.CelExpr;
import dev.cel.common.ast.CelExpr.ExprKind.Kind;
import dev.cel.common.types.CelV1AlphaTypes;
import dev.cel.common.types.ListType;
import dev.cel.common.types.SimpleType;
import dev.cel.common.types.StructTypeReference;
import dev.cel.compiler.CelCompiler;
import dev.cel.compiler.CelCompilerFactory;
import dev.cel.expr.conformance.proto3.TestAllTypes;
import dev.cel.extensions.CelExtensions;
import dev.cel.parser.CelStandardMacro;
import dev.cel.parser.CelUnparserFactory;
import dev.cel.testing.CelRuntimeFlavor;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(TestParameterInjector.class)
public class CelRuntimeTest {

  @TestParameter private CelRuntimeFlavor runtimeFlavor;

  @Test
  public void evaluate_anyPackedEqualityUsingProtoDifferencer_success() throws Exception {
    Cel cel =
        CelFactory.standardCelBuilder()
            .setOptions(CelOptions.current().enableProtoDifferencerEquality(true).build())
            .addVar("a", StructTypeReference.create(AttributeContext.getDescriptor().getFullName()))
            .addVar("b", StructTypeReference.create(AttributeContext.getDescriptor().getFullName()))
            .addMessageTypes(AttributeContext.getDescriptor())
            .build();
    CelAbstractSyntaxTree ast = cel.compile("a == b").getAst();
    CelRuntime.Program program = cel.createProgram(ast);

    Object evaluatedResult =
        program.eval(
            ImmutableMap.of(
                "a",
                AttributeContext.newBuilder()
                    .addExtensions(
                        Any.newBuilder()
                            .setTypeUrl("type.googleapis.com/google.rpc.context.AttributeContext")
                            .setValue(ByteString.copyFromUtf8("\032\000:\000"))
                            .build())
                    .build(),
                "b",
                AttributeContext.newBuilder()
                    .addExtensions(
                        Any.newBuilder()
                            .setTypeUrl("type.googleapis.com/google.rpc.context.AttributeContext")
                            .setValue(ByteString.copyFromUtf8(":\000\032\000"))
                            .build())
                    .build()));

    assertThat(evaluatedResult).isEqualTo(true);
  }

  @Test
  public void evaluate_v1alpha1CheckedExpr() throws Exception {
    // Note: v1alpha1 proto support exists only to help migrate existing consumers.
    // New users of CEL should use the canonical protos instead (I.E: dev.cel.expr)
    CheckedExpr checkedExpr =
        CheckedExpr.newBuilder()
            .setExpr(
                Expr.newBuilder()
                    .setId(1)
                    .setConstExpr(Constant.newBuilder().setStringValue("Hello world!").build())
                    .build())
            .putTypeMap(1, CelV1AlphaTypes.create(PrimitiveType.STRING))
            .build();
    CelRuntime celRuntime = CelRuntimeFactory.standardCelRuntimeBuilder().build();
    CelRuntime.Program program =
        celRuntime.createProgram(
            CelProtoV1Alpha1AbstractSyntaxTree.fromCheckedExpr(checkedExpr).getAst());

    String evaluatedResult = (String) program.eval();

    assertThat(evaluatedResult).isEqualTo("Hello world!");
  }

  @Test
  // Lazy evaluation result cache doesn't allow references to mutate the cached instance.
  @TestParameters(
      "{expression: 'cel.bind(x, unknown_attr, (unknown_attr > 0) || [0, 1, 2, 3, 4, 5, 6, 7, 8, 9,"
          + " 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20].exists(i, x + x > 0))'}")
  @TestParameters(
      "{expression: 'cel.bind(x, unknown_attr, x + x + x + x + x + x + x + x + x + x + x + x + x +"
          + " x + x + x + x + x + x + x + x + x + x + x + x + x + x + x + x + x + x + x + x)'}")
  // A new unknown is created per 'x' reference.
  @TestParameters(
      "{expression: '(my_list.exists(x, (x + x + x + x + x + x + x + x + x + x + x + x + x + x + x"
          + " + x + x + x + x + x + x + x + x + x + x + x + x + x + x + x + x + x + x) > 100) &&"
          + " false) || unknown_attr > 0'}")
  public void advanceEvaluation_withUnknownTracking_noSelfReferenceInMerge(String expression)
      throws Exception {
    Cel cel =
        CelFactory.standardCelBuilder()
            .setStandardMacros(CelStandardMacro.STANDARD_MACROS)
            .addCompilerLibraries(CelExtensions.bindings())
            .setContainer(CelContainer.ofName("cel.expr.conformance.test"))
            .addVar("unknown_attr", SimpleType.INT)
            .addVar("my_list", ListType.create(SimpleType.INT))
            .setOptions(CelOptions.current().enableUnknownTracking(true).build())
            .build();

    CelUnknownSet result =
        (CelUnknownSet)
            cel.createProgram(cel.compile(expression).getAst())
                .advanceEvaluation(
                    UnknownContext.create(
                        (String name) -> {
                          if (name.equals("my_list")) {
                            return Optional.of(ImmutableList.of(1));
                          }
                          return Optional.empty();
                        },
                        ImmutableList.of(
                            CelAttributePattern.create("unknown_attr"),
                            CelAttributePattern.create("my_list")
                                .qualify(CelAttribute.Qualifier.ofInt(0)))));

    assertThat(result.attributes()).containsExactly(CelAttribute.create("unknown_attr"));
  }

  @Test
  public void newWellKnownTypeMessage_withDifferentDescriptorInstance() throws Exception {
    CelCompiler celCompiler =
        CelCompilerFactory.standardCelCompilerBuilder()
            .addMessageTypes(BoolValue.getDescriptor())
            .build();
    CelRuntime celRuntime =
        CelRuntimeFactory.standardCelRuntimeBuilder()
            // CEL-Internal-2
            .addFileTypes(
                FileDescriptorSet.newBuilder()
                    .addFile(
                        BoolValue.getDescriptor().getFile().toProto()) // Copy the WKT descriptor
                    .build())
            .build();

    CelAbstractSyntaxTree ast =
        celCompiler.compile("google.protobuf.BoolValue{value: false}").getAst();

    assertThat(celRuntime.createProgram(ast).eval()).isEqualTo(false);
  }

  @Test
  public void newWellKnownTypeMessage_inDynamicMessage_withSetTypeFactory() throws Exception {
    CelCompiler celCompiler =
        CelCompilerFactory.standardCelCompilerBuilder()
            .addMessageTypes(BoolValue.getDescriptor())
            .build();
    CelRuntime celRuntime =
        CelRuntimeFactory.standardCelRuntimeBuilder()
            // CEL-Internal-2
            .setTypeFactory(
                (typeName) ->
                    typeName.equals("google.protobuf.BoolValue")
                        ? DynamicMessage.newBuilder(BoolValue.getDescriptor())
                        : null)
            .build();

    CelAbstractSyntaxTree ast =
        celCompiler.compile("google.protobuf.BoolValue{value: false}").getAst();

    assertThat(celRuntime.createProgram(ast).eval()).isEqualTo(false);
  }

  @Test
  public void newWellKnownTypeMessage_inAnyMessage_withDifferentDescriptorInstance()
      throws Exception {
    FileDescriptorSet fds =
        FileDescriptorSet.newBuilder()
            // Copy the WKT descriptors
            .addFile(Any.getDescriptor().getFile().toProto())
            .addFile(BoolValue.getDescriptor().getFile().toProto())
            .build();
    CelCompiler celCompiler =
        CelCompilerFactory.standardCelCompilerBuilder().addFileTypes(fds).build();
    CelRuntime celRuntime =
        CelRuntimeFactory.standardCelRuntimeBuilder()
            // CEL-Internal-2
            .addFileTypes(fds)
            .build();

    CelAbstractSyntaxTree ast =
        celCompiler
            .compile(
                "google.protobuf.Any{type_url: 'types.googleapis.com/google.protobuf.DoubleValue'}")
            .getAst();

    assertThat(celRuntime.createProgram(ast).eval()).isEqualTo(0.0d);
  }

  @Test
  public void newWellKnownTypeMessage_inAnyMessage_withSetTypeFactory() throws Exception {
    FileDescriptorSet fds =
        FileDescriptorSet.newBuilder()
            // Copy the WKT descriptors
            .addFile(Any.getDescriptor().getFile().toProto())
            .addFile(BoolValue.getDescriptor().getFile().toProto())
            .build();
    CelCompiler celCompiler =
        CelCompilerFactory.standardCelCompilerBuilder().addFileTypes(fds).build();
    CelRuntime celRuntime =
        CelRuntimeFactory.standardCelRuntimeBuilder()
            // CEL-Internal-2
            .addFileTypes(fds)
            .setTypeFactory(
                (typeName) ->
                    typeName.equals("google.protobuf.Any")
                        ? Any.newBuilder().setTypeUrl("google.protobuf.DoubleValue")
                        : null)
            .build();

    CelAbstractSyntaxTree ast =
        celCompiler
            .compile(
                "google.protobuf.Any{type_url: 'types.googleapis.com/google.protobuf.DoubleValue'}")
            .getAst();

    assertThat(celRuntime.createProgram(ast).eval()).isEqualTo(0.0d);
  }

  @Test
  public void trace_callExpr_identifyFalseBranch() throws Exception {
    AtomicReference<CelExpr> capturedExpr = new AtomicReference<>();
    CelEvaluationListener listener =
        (expr, res) -> {
          if (res instanceof Boolean && !(boolean) res && capturedExpr.get() == null) {
            capturedExpr.set(expr);
          }
        };
    Cel cel =
        runtimeFlavor
            .builder()
            .addVar("a", SimpleType.INT)
            .addVar("b", SimpleType.INT)
            .addVar("c", SimpleType.INT)
            .build();
    CelAbstractSyntaxTree ast = cel.compile("a < 0 && b < 0 && c < 0").getAst();

    boolean result =
        (boolean) cel.createProgram(ast).trace(ImmutableMap.of("a", -1, "b", 1, "c", -4), listener);

    assertThat(result).isFalse();
    // Demonstrate that "b < 0" is what caused the expression to be false
    CelAbstractSyntaxTree subtree =
        CelAbstractSyntaxTree.newParsedAst(capturedExpr.get(), CelSource.newBuilder().build());
    assertThat(CelUnparserFactory.newUnparser().unparse(subtree)).isEqualTo("b < 0");
  }

  @Test
  public void trace_constant() throws Exception {
    CelEvaluationListener listener =
        (expr, res) -> {
          assertThat(res).isEqualTo("hello world");
          assertThat(expr.constant().getKind()).isEqualTo(CelConstant.Kind.STRING_VALUE);
        };
    Cel cel = runtimeFlavor.builder().build();
    CelAbstractSyntaxTree ast = cel.compile("'hello world'").getAst();

    String result = (String) cel.createProgram(ast).trace(listener);

    assertThat(result).isEqualTo("hello world");
  }

  @Test
  public void trace_ident() throws Exception {
    CelEvaluationListener listener =
        (expr, res) -> {
          assertThat(res).isEqualTo("test");
          assertThat(expr.ident().name()).isEqualTo("a");
        };
    Cel cel = runtimeFlavor.builder().addVar("a", SimpleType.STRING).build();
    CelAbstractSyntaxTree ast = cel.compile("a").getAst();

    String result = (String) cel.createProgram(ast).trace(ImmutableMap.of("a", "test"), listener);

    assertThat(result).isEqualTo("test");
  }

  @Test
  public void trace_select() throws Exception {
    CelEvaluationListener listener =
        (expr, res) -> {
          if (expr.exprKind().getKind().equals(Kind.SELECT)) {
            assertThat(res).isEqualTo(3L);
            assertThat(expr.select().field()).isEqualTo("single_int64");
          }
        };
    Cel cel =
        runtimeFlavor
            .builder()
            .addMessageTypes(TestAllTypes.getDescriptor())
            .setContainer(CelContainer.ofName("cel.expr.conformance.proto3"))
            .build();
    CelAbstractSyntaxTree ast = cel.compile("TestAllTypes{single_int64: 3}.single_int64").getAst();

    Long result = (Long) cel.createProgram(ast).trace(listener);

    assertThat(result).isEqualTo(3L);
  }

  @Test
  public void trace_struct() throws Exception {
    CelEvaluationListener listener =
        (expr, res) -> {
          assertThat(res).isEqualTo(TestAllTypes.getDefaultInstance());
          assertThat(expr.struct().messageName())
              .isEqualTo("cel.expr.conformance.proto3.TestAllTypes");
        };
    Cel cel =
        runtimeFlavor
            .builder()
            .addMessageTypes(TestAllTypes.getDescriptor())
            .setContainer(CelContainer.ofName("cel.expr.conformance.proto3"))
            .build();
    CelAbstractSyntaxTree ast = cel.compile("TestAllTypes{}").getAst();

    TestAllTypes result = (TestAllTypes) cel.createProgram(ast).trace(listener);

    assertThat(result).isEqualTo(TestAllTypes.getDefaultInstance());
  }

  @Test
  @SuppressWarnings("unchecked") // Test only
  public void trace_list() throws Exception {
    CelEvaluationListener listener =
        (expr, res) -> {
          if (expr.exprKind().getKind().equals(Kind.LIST)) {
            assertThat((List<Long>) res).containsExactly(1L, 2L, 3L);
            assertThat(expr.list().elements()).hasSize(3);
          }
        };
    Cel cel = runtimeFlavor.builder().build();
    CelAbstractSyntaxTree ast = cel.compile("[1, 2, 3]").getAst();

    List<Long> result = (List<Long>) cel.createProgram(ast).trace(listener);

    assertThat(result).containsExactly(1L, 2L, 3L);
  }

  @Test
  @SuppressWarnings("unchecked") // Test only
  public void trace_map() throws Exception {
    CelEvaluationListener listener =
        (expr, res) -> {
          if (expr.exprKind().getKind().equals(Kind.MAP)) {
            assertThat((Map<Long, String>) res).containsExactly(1L, "a");
            assertThat(expr.map().entries()).hasSize(1);
          }
        };
    Cel cel = runtimeFlavor.builder().build();
    CelAbstractSyntaxTree ast = cel.compile("{1: 'a'}").getAst();

    Map<Long, String> result = (Map<Long, String>) cel.createProgram(ast).trace(listener);

    assertThat(result).containsExactly(1L, "a");
  }

  @Test
  public void trace_comprehension() throws Exception {
    CelEvaluationListener listener =
        (expr, res) -> {
          if (expr.exprKind().getKind().equals(Kind.COMPREHENSION)) {
            assertThat(expr.comprehension().iterVar()).isEqualTo("i");
          }
        };
    Cel cel = runtimeFlavor.builder().setStandardMacros(CelStandardMacro.STANDARD_MACROS).build();
    CelAbstractSyntaxTree ast = cel.compile("[true].exists(i, i)").getAst();

    boolean result = (boolean) cel.createProgram(ast).trace(listener);

    assertThat(result).isTrue();
  }

  @Test
  public void trace_withMessageInput() throws Exception {
    CelEvaluationListener listener =
        (expr, res) -> {
          assertThat(res).isEqualTo(6L);
          assertThat(expr.ident().name()).isEqualTo("single_int64");
        };
    Cel cel =
        runtimeFlavor
            .builder()
            .addMessageTypes(TestAllTypes.getDescriptor())
            .addVar("single_int64", SimpleType.INT)
            .build();
    CelAbstractSyntaxTree ast = cel.compile("single_int64").getAst();

    Long result =
        (Long)
            cel.createProgram(ast)
                .trace(TestAllTypes.newBuilder().setSingleInt64(6L).build(), listener);

    assertThat(result).isEqualTo(6L);
  }

  @Test
  public void trace_withVariableResolver() throws Exception {
    CelEvaluationListener listener =
        (expr, res) -> {
          assertThat(res).isEqualTo("hello");
          assertThat(expr.ident().name()).isEqualTo("variable");
        };
    Cel cel =
        runtimeFlavor
            .builder()
            .addMessageTypes(TestAllTypes.getDescriptor())
            .addVar("variable", SimpleType.STRING)
            .build();
    CelAbstractSyntaxTree ast = cel.compile("variable").getAst();
    CelVariableResolver resolver =
        (name) -> name.equals("variable") ? Optional.of("hello") : Optional.empty();

    String result = (String) cel.createProgram(ast).trace(resolver, listener);

    assertThat(result).isEqualTo("hello");
  }

  @Test
  public void trace_shortCircuitingDisabled_logicalAndAllBranchesVisited(
      @TestParameter boolean first, @TestParameter boolean second, @TestParameter boolean third)
      throws Exception {
    String expression = String.format("%s && %s && %s", first, second, third);
    ImmutableList.Builder<Boolean> branchResults = ImmutableList.builder();
    CelEvaluationListener listener =
        (expr, res) -> {
          if (expr.constantOrDefault().getKind().equals(CelConstant.Kind.BOOLEAN_VALUE)) {
            branchResults.add((Boolean) res);
          }
        };
    Cel celWithShortCircuit =
        runtimeFlavor
            .builder()
            .setOptions(
                CelOptions.current()
                    .enableShortCircuiting(true)
                    .enableHeterogeneousNumericComparisons(true)
                    .build())
            .build();
    Cel cel =
        runtimeFlavor
            .builder()
            .setOptions(
                CelOptions.current()
                    .enableShortCircuiting(false)
                    .enableHeterogeneousNumericComparisons(true)
                    .build())
            .build();
    CelAbstractSyntaxTree ast = cel.compile(expression).getAst();

    boolean result = (boolean) cel.createProgram(ast).trace(listener);
    boolean shortCircuitedResult =
        (boolean)
            celWithShortCircuit
                .createProgram(celWithShortCircuit.compile(expression).getAst())
                .eval();

    assertThat(result).isEqualTo(shortCircuitedResult);
    assertThat(branchResults.build()).containsExactly(first, second, third).inOrder();
  }

  @Test
  @TestParameters("{source: 'false && false && x'}")
  @TestParameters("{source: 'false && x && false'}")
  @TestParameters("{source: 'x && false && false'}")
  public void trace_shortCircuitingDisabledWithUnknownsAndedToFalse_returnsFalse(String source)
      throws Exception {
    ImmutableList.Builder<Object> branchResults = ImmutableList.builder();
    CelEvaluationListener listener =
        (expr, res) -> {
          if (expr.constantOrDefault().getKind().equals(CelConstant.Kind.BOOLEAN_VALUE)
              || expr.identOrDefault().name().equals("x")) {
            if (res instanceof CelUnknownSet) {
              branchResults.add("x"); // Swap unknown result with a sentinel value for testing
            } else {
              branchResults.add(res);
            }
          }
        };
    Cel cel =
        runtimeFlavor
            .builder()
            .addVar("x", SimpleType.BOOL)
            .setOptions(
                CelOptions.current()
                    .enableShortCircuiting(false)
                    .enableHeterogeneousNumericComparisons(true)
                    .build())
            .build();
    CelAbstractSyntaxTree ast = cel.compile(source).getAst();

    PartialVars partialVars = PartialVars.of(CelAttributePattern.create("x"));
    boolean result = (boolean) cel.createProgram(ast).trace(partialVars, listener);

    assertThat(result).isFalse();
    assertThat(branchResults.build()).containsExactly(false, false, "x");
  }

  @Test
  @TestParameters("{source: 'true && true && x'}")
  @TestParameters("{source: 'true && x && true'}")
  @TestParameters("{source: 'x && true && true'}")
  public void trace_shortCircuitingDisabledWithUnknownAndedToTrue_returnsUnknown(String source)
      throws Exception {
    ImmutableList.Builder<Object> branchResults = ImmutableList.builder();
    CelEvaluationListener listener =
        (expr, res) -> {
          if (expr.constantOrDefault().getKind().equals(CelConstant.Kind.BOOLEAN_VALUE)
              || expr.identOrDefault().name().equals("x")) {
            branchResults.add(res);
          }
        };
    Cel cel =
        runtimeFlavor
            .builder()
            .addVar("x", SimpleType.BOOL)
            .setOptions(
                CelOptions.current()
                    .enableShortCircuiting(false)
                    .enableHeterogeneousNumericComparisons(true)
                    .build())
            .build();
    CelAbstractSyntaxTree ast = cel.compile(source).getAst();

    PartialVars partialVars = PartialVars.of(CelAttributePattern.create("x"));
    Object unknownResult = cel.createProgram(ast).trace(partialVars, listener);

    assertThat(unknownResult).isInstanceOf(CelUnknownSet.class);
    assertThat(branchResults.build()).containsExactly(true, true, unknownResult);
  }

  @Test
  public void trace_shortCircuitingDisabled_logicalOrAllBranchesVisited(
      @TestParameter boolean first, @TestParameter boolean second, @TestParameter boolean third)
      throws Exception {
    String expression = String.format("%s || %s || %s", first, second, third);
    ImmutableList.Builder<Boolean> branchResults = ImmutableList.builder();
    CelEvaluationListener listener =
        (expr, res) -> {
          if (expr.constantOrDefault().getKind().equals(CelConstant.Kind.BOOLEAN_VALUE)) {
            branchResults.add((Boolean) res);
          }
        };
    Cel celWithShortCircuit =
        runtimeFlavor
            .builder()
            .setOptions(
                CelOptions.current()
                    .enableShortCircuiting(true)
                    .enableHeterogeneousNumericComparisons(true)
                    .build())
            .build();
    Cel cel =
        runtimeFlavor
            .builder()
            .setOptions(
                CelOptions.current()
                    .enableShortCircuiting(false)
                    .enableHeterogeneousNumericComparisons(true)
                    .build())
            .build();
    CelAbstractSyntaxTree ast = cel.compile(expression).getAst();

    boolean result = (boolean) cel.createProgram(ast).trace(listener);
    boolean shortCircuitedResult =
        (boolean)
            celWithShortCircuit
                .createProgram(celWithShortCircuit.compile(expression).getAst())
                .eval();

    assertThat(result).isEqualTo(shortCircuitedResult);
    assertThat(branchResults.build()).containsExactly(first, second, third).inOrder();
  }

  @Test
  @TestParameters("{source: 'false || false || x'}")
  @TestParameters("{source: 'false || x || false'}")
  @TestParameters("{source: 'x || false || false'}")
  public void trace_shortCircuitingDisabledWithUnknownsOredToFalse_returnsUnknown(String source)
      throws Exception {
    ImmutableList.Builder<Object> branchResults = ImmutableList.builder();
    CelEvaluationListener listener =
        (expr, res) -> {
          if (expr.constantOrDefault().getKind().equals(CelConstant.Kind.BOOLEAN_VALUE)
              || expr.identOrDefault().name().equals("x")) {
            branchResults.add(res);
          }
        };
    Cel cel =
        runtimeFlavor
            .builder()
            .addVar("x", SimpleType.BOOL)
            .setOptions(
                CelOptions.current()
                    .enableShortCircuiting(false)
                    .enableHeterogeneousNumericComparisons(true)
                    .build())
            .build();
    CelAbstractSyntaxTree ast = cel.compile(source).getAst();

    PartialVars partialVars = PartialVars.of(CelAttributePattern.create("x"));
    Object unknownResult = cel.createProgram(ast).trace(partialVars, listener);

    assertThat(unknownResult).isInstanceOf(CelUnknownSet.class);
    assertThat(branchResults.build()).containsExactly(false, false, unknownResult);
  }

  @Test
  @TestParameters("{source: 'true || true || x'}")
  @TestParameters("{source: 'true || x || true'}")
  @TestParameters("{source: 'x || true || true'}")
  public void trace_shortCircuitingDisabledWithUnknownOredToTrue_returnsTrue(String source)
      throws Exception {
    ImmutableList.Builder<Object> branchResults = ImmutableList.builder();
    CelEvaluationListener listener =
        (expr, res) -> {
          if (expr.constantOrDefault().getKind().equals(CelConstant.Kind.BOOLEAN_VALUE)
              || expr.identOrDefault().name().equals("x")) {
            if (res instanceof CelUnknownSet) {
              branchResults.add("x"); // Swap unknown result with a sentinel value for testing
            } else {
              branchResults.add(res);
            }
          }
        };
    Cel cel =
        runtimeFlavor
            .builder()
            .addVar("x", SimpleType.BOOL)
            .setOptions(
                CelOptions.current()
                    .enableShortCircuiting(false)
                    .enableHeterogeneousNumericComparisons(true)
                    .build())
            .build();
    CelAbstractSyntaxTree ast = cel.compile(source).getAst();

    PartialVars partialVars = PartialVars.of(CelAttributePattern.create("x"));
    boolean result = (boolean) cel.createProgram(ast).trace(partialVars, listener);

    assertThat(result).isTrue();
    assertThat(branchResults.build()).containsExactly(true, true, "x");
  }

  @Test
  public void trace_shortCircuitingDisabled_ternaryAllBranchesVisited() throws Exception {
    ImmutableList.Builder<Boolean> branchResults = ImmutableList.builder();
    CelEvaluationListener listener =
        (expr, res) -> {
          if (expr.constantOrDefault().getKind().equals(CelConstant.Kind.BOOLEAN_VALUE)) {
            branchResults.add((Boolean) res);
          }
        };
    Cel cel =
        runtimeFlavor
            .builder()
            .setOptions(
                CelOptions.current()
                    .enableShortCircuiting(false)
                    .enableHeterogeneousNumericComparisons(true)
                    .build())
            .build();
    CelAbstractSyntaxTree ast = cel.compile("true ? false : true").getAst();

    boolean result = (boolean) cel.createProgram(ast).trace(listener);

    assertThat(result).isFalse();
    assertThat(branchResults.build()).containsExactly(true, false, true);
  }

  @Test
  @TestParameters("{source: 'false ? true : x'}")
  @TestParameters("{source: 'true ? x : false'}")
  @TestParameters("{source: 'x ? true : false'}")
  public void trace_shortCircuitingDisabled_ternaryWithUnknowns(String source) throws Exception {
    ImmutableList.Builder<Object> branchResults = ImmutableList.builder();
    CelEvaluationListener listener =
        (expr, res) -> {
          if (expr.constantOrDefault().getKind().equals(CelConstant.Kind.BOOLEAN_VALUE)
              || expr.identOrDefault().name().equals("x")) {
            branchResults.add(res);
          }
        };
    Cel cel =
        runtimeFlavor
            .builder()
            .addVar("x", SimpleType.BOOL)
            .setOptions(
                CelOptions.current()
                    .enableShortCircuiting(false)
                    .enableHeterogeneousNumericComparisons(true)
                    .build())
            .build();
    CelAbstractSyntaxTree ast = cel.compile(source).getAst();

    PartialVars partialVars = PartialVars.of(CelAttributePattern.create("x"));
    Object unknownResult = cel.createProgram(ast).trace(partialVars, listener);

    assertThat(unknownResult).isInstanceOf(CelUnknownSet.class);
    assertThat(branchResults.build()).containsExactly(false, unknownResult, true);
  }

  @Test
  @TestParameters(
      "{expression: 'false ? (1 / 0) > 2 : false', firstVisited: false, secondVisited: false}")
  @TestParameters(
      "{expression: 'false ? (1 / 0) > 2 : true', firstVisited: false, secondVisited: true}")
  @TestParameters(
      "{expression: 'true ? false : (1 / 0) > 2', firstVisited: true, secondVisited: false}")
  @TestParameters(
      "{expression: 'true ? true : (1 / 0) > 2', firstVisited: true, secondVisited: true}")
  public void trace_shortCircuitingDisabled_ternaryWithError(
      String expression, boolean firstVisited, boolean secondVisited) throws Exception {
    ImmutableList.Builder<Object> branchResults = ImmutableList.builder();
    CelEvaluationListener listener =
        (expr, res) -> {
          if (expr.constantOrDefault().getKind().equals(CelConstant.Kind.BOOLEAN_VALUE)) {
            branchResults.add(res);
          }
        };
    Cel celWithShortCircuit =
        runtimeFlavor
            .builder()
            .setOptions(
                CelOptions.current()
                    .enableShortCircuiting(true)
                    .enableHeterogeneousNumericComparisons(true)
                    .build())
            .build();
    Cel cel =
        runtimeFlavor
            .builder()
            .setOptions(
                CelOptions.current()
                    .enableShortCircuiting(false)
                    .enableHeterogeneousNumericComparisons(true)
                    .build())
            .build();
    CelAbstractSyntaxTree ast = cel.compile(expression).getAst();

    boolean result = (boolean) cel.createProgram(ast).trace(listener);
    boolean shortCircuitedResult =
        (boolean)
            celWithShortCircuit
                .createProgram(celWithShortCircuit.compile(expression).getAst())
                .eval();

    assertThat(result).isEqualTo(shortCircuitedResult);
    assertThat(branchResults.build()).containsExactly(firstVisited, secondVisited).inOrder();
  }

  @Test
  public void trace_shortCircuitingDisabled_ternaryWithSelectedError() throws Exception {
    Cel cel =
        runtimeFlavor
            .builder()
            .setOptions(
                CelOptions.current()
                    .enableShortCircuiting(false)
                    .enableHeterogeneousNumericComparisons(true)
                    .build())
            .build();
    CelAbstractSyntaxTree ast = cel.compile("true ? (1 / 0) : 2").getAst();
    CelRuntime.Program program = cel.createProgram(ast);

    CelEvaluationException e = assertThrows(CelEvaluationException.class, () -> program.eval());
    assertThat(e).hasMessageThat().contains("evaluation error at <input>:10: / by zero");
    assertThat(e.getErrorCode()).isEqualTo(CelErrorCode.DIVIDE_BY_ZERO);
  }

  @Test
  public void trace_shortCircuitingDisabled_ternaryWithCustomError() throws Exception {
    Cel cel =
        runtimeFlavor
            .builder()
            .addFunctionDeclarations(
                CelFunctionDecl.newFunctionDeclaration(
                    "error_func",
                    CelOverloadDecl.newGlobalOverload(
                        "error_func_overload", SimpleType.BOOL, ImmutableList.of())))
            .addFunctionBindings(
                CelFunctionBinding.from(
                    "error_func_overload",
                    ImmutableList.of(),
                    args -> {
                      throw new IllegalArgumentException("custom error");
                    }))
            .setOptions(
                CelOptions.current()
                    .enableShortCircuiting(false)
                    .enableHeterogeneousNumericComparisons(true)
                    .build())
            .build();
    CelAbstractSyntaxTree ast = cel.compile("true ? error_func() : false").getAst();
    CelRuntime.Program program = cel.createProgram(ast);

    CelEvaluationException e = assertThrows(CelEvaluationException.class, () -> program.eval());
    assertThat(e).hasCauseThat().hasMessageThat().contains("custom error");
  }

  @Test
  public void standardEnvironmentDisabledForRuntime_throws() throws Exception {
    CelCompiler celCompiler =
        CelCompilerFactory.standardCelCompilerBuilder().setStandardEnvironmentEnabled(true).build();
    CelRuntime celRuntime =
        CelRuntimeFactory.standardCelRuntimeBuilder().setStandardEnvironmentEnabled(false).build();
    CelAbstractSyntaxTree ast = celCompiler.compile("size('hello')").getAst();
    CelRuntime.Program program = celRuntime.createProgram(ast);

    CelEvaluationException e = assertThrows(CelEvaluationException.class, () -> program.eval());
    assertThat(e)
        .hasMessageThat()
        .contains("No matching overload for function 'size'. Overload candidates: size_string");
  }

  @Test
  public void trace_shortCircuitingDisabled_logicalAndPrefersFirstError() throws Exception {
    Cel cel =
        runtimeFlavor
            .builder()
            .addFunctionDeclarations(
                CelFunctionDecl.newFunctionDeclaration(
                    "error_1",
                    CelOverloadDecl.newGlobalOverload(
                        "error_1_overload", SimpleType.BOOL, ImmutableList.of())),
                CelFunctionDecl.newFunctionDeclaration(
                    "error_2",
                    CelOverloadDecl.newGlobalOverload(
                        "error_2_overload", SimpleType.BOOL, ImmutableList.of())))
            .addFunctionBindings(
                CelFunctionBinding.from(
                    "error_1_overload",
                    ImmutableList.of(),
                    args -> {
                      throw new IllegalArgumentException("error 1");
                    }),
                CelFunctionBinding.from(
                    "error_2_overload",
                    ImmutableList.of(),
                    args -> {
                      throw new IllegalArgumentException("error 2");
                    }))
            .setOptions(
                CelOptions.current()
                    .enableShortCircuiting(false)
                    .enableHeterogeneousNumericComparisons(true)
                    .build())
            .build();
    CelAbstractSyntaxTree ast = cel.compile("error_1() && error_2()").getAst();
    CelRuntime.Program program = cel.createProgram(ast);

    CelEvaluationException e = assertThrows(CelEvaluationException.class, () -> program.eval());
    assertThat(e).hasCauseThat().hasMessageThat().contains("error 1");
  }

  @Test
  public void trace_shortCircuitingDisabled_logicalOrPrefersFirstError() throws Exception {
    Cel cel =
        runtimeFlavor
            .builder()
            .addFunctionDeclarations(
                CelFunctionDecl.newFunctionDeclaration(
                    "error_1",
                    CelOverloadDecl.newGlobalOverload(
                        "error_1_overload", SimpleType.BOOL, ImmutableList.of())),
                CelFunctionDecl.newFunctionDeclaration(
                    "error_2",
                    CelOverloadDecl.newGlobalOverload(
                        "error_2_overload", SimpleType.BOOL, ImmutableList.of())))
            .addFunctionBindings(
                CelFunctionBinding.from(
                    "error_1_overload",
                    ImmutableList.of(),
                    args -> {
                      throw new IllegalArgumentException("error 1");
                    }),
                CelFunctionBinding.from(
                    "error_2_overload",
                    ImmutableList.of(),
                    args -> {
                      throw new IllegalArgumentException("error 2");
                    }))
            .setOptions(
                CelOptions.current()
                    .enableShortCircuiting(false)
                    .enableHeterogeneousNumericComparisons(true)
                    .build())
            .build();
    CelAbstractSyntaxTree ast = cel.compile("error_1() || error_2()").getAst();
    CelRuntime.Program program = cel.createProgram(ast);

    CelEvaluationException e = assertThrows(CelEvaluationException.class, () -> program.eval());
    assertThat(e).hasCauseThat().hasMessageThat().contains("error 1");
  }

  @Test
  public void evaluate_customFunctionReturningCelUnknownSet_propagatesUnknown(
      @TestParameter({
            // Field selection
            "getMsg().single_int32",
            "getMsg().single_nested_message.bb",
            // Binary & unary operators
            "getMsg().single_int32 == 100",
            "getMsg().single_int32 + 5 == 10",
            "-getMsg().single_int32 == -10",
            // Boolean operators & ternary
            "true && (getMsg().single_int32 == 100)",
            "false || (getMsg().single_int32 == 100)",
            "(getMsg().single_int32 == 100) ? 'match' : 'no-match'",
            // Comprehensions
            "[1, 2, 3].exists(x, x == getMsg().single_int32)",
            "[1, 2, 3].all(x, x > 0 && getMsg().single_int32 > 0)",
            "[1, 2, 3].map(x, x + getMsg().single_int32)",
            "[1, 2, 3].filter(x, x == getMsg().single_int32)",
          })
          String expression)
      throws Exception {
    Cel cel =
        runtimeFlavor
            .builder()
            .setStandardMacros(CelStandardMacro.STANDARD_MACROS)
            .addMessageTypes(TestAllTypes.getDescriptor())
            .addFunctionDeclarations(
                CelFunctionDecl.newFunctionDeclaration(
                    "getMsg",
                    CelOverloadDecl.newGlobalOverload(
                        "getMsg_overload",
                        StructTypeReference.create(TestAllTypes.getDescriptor().getFullName()),
                        ImmutableList.of())))
            .addFunctionBindings(
                CelFunctionBinding.from(
                    "getMsg_overload",
                    ImmutableList.of(),
                    args -> CelUnknownSet.create(CelAttribute.create("custom_msg"))))
            .build();

    Object result = cel.createProgram(cel.compile(expression).getAst()).eval();

    assertThat(result).isInstanceOf(CelUnknownSet.class);
  }

  @Test
  // Short-circuited boolean operators
  @TestParameters("{expression: 'false && (getMsg().single_int32 == 100)', expected: false}")
  @TestParameters("{expression: 'true || (getMsg().single_int32 == 100)', expected: true}")
  // Short-circuited comprehensions
  @TestParameters(
      "{expression: '[1, 2, 3].exists(x, x == 1 || x == getMsg().single_int32)', expected: true}")
  @TestParameters(
      "{expression: '[1, 2, 3].all(x, x == 0 && getMsg().single_int32 > 0)', expected: false}")
  public void evaluate_customFunctionReturningCelUnknownSet_shortCircuits(
      String expression, boolean expected) throws Exception {
    Cel cel =
        runtimeFlavor
            .builder()
            .setStandardMacros(CelStandardMacro.STANDARD_MACROS)
            .addMessageTypes(TestAllTypes.getDescriptor())
            .addFunctionDeclarations(
                CelFunctionDecl.newFunctionDeclaration(
                    "getMsg",
                    CelOverloadDecl.newGlobalOverload(
                        "getMsg_overload",
                        StructTypeReference.create(TestAllTypes.getDescriptor().getFullName()),
                        ImmutableList.of())))
            .addFunctionBindings(
                CelFunctionBinding.from(
                    "getMsg_overload",
                    ImmutableList.of(),
                    args -> CelUnknownSet.create(CelAttribute.create("custom_msg"))))
            .build();

    Object result = cel.createProgram(cel.compile(expression).getAst()).eval();

    assertThat(result).isEqualTo(expected);
  }

  @Test
  public void evaluate_customFunctionReturningCelUnknownSet_differentArities() throws Exception {
    Cel cel =
        runtimeFlavor
            .builder()
            .addFunctionDeclarations(
                CelFunctionDecl.newFunctionDeclaration(
                    "unkZero",
                    CelOverloadDecl.newGlobalOverload(
                        "unk_zero", SimpleType.INT, ImmutableList.of())),
                CelFunctionDecl.newFunctionDeclaration(
                    "unkUnary",
                    CelOverloadDecl.newGlobalOverload("unk_unary", SimpleType.INT, SimpleType.INT)),
                CelFunctionDecl.newFunctionDeclaration(
                    "unkBinary",
                    CelOverloadDecl.newGlobalOverload(
                        "unk_binary", SimpleType.INT, SimpleType.INT, SimpleType.INT)),
                CelFunctionDecl.newFunctionDeclaration(
                    "unkMember",
                    CelOverloadDecl.newMemberOverload(
                        "unk_member", SimpleType.INT, SimpleType.STRING, SimpleType.INT)),
                CelFunctionDecl.newFunctionDeclaration(
                    "unkVarargs",
                    CelOverloadDecl.newGlobalOverload(
                        "unk_varargs",
                        SimpleType.INT,
                        SimpleType.INT,
                        SimpleType.INT,
                        SimpleType.INT)))
            .addFunctionBindings(
                CelFunctionBinding.from(
                    "unk_zero",
                    ImmutableList.of(),
                    args -> CelUnknownSet.create(CelAttribute.create("attr_zero"))),
                CelFunctionBinding.from(
                    "unk_unary",
                    Long.class,
                    arg -> CelUnknownSet.create(CelAttribute.create("attr_unary"))),
                CelFunctionBinding.from(
                    "unk_binary",
                    Long.class,
                    Long.class,
                    (a, b) -> CelUnknownSet.create(CelAttribute.create("attr_binary"))),
                CelFunctionBinding.from(
                    "unk_member",
                    String.class,
                    Long.class,
                    (target, arg) -> CelUnknownSet.create(CelAttribute.create("attr_member"))),
                CelFunctionBinding.from(
                    "unk_varargs",
                    ImmutableList.of(Long.class, Long.class, Long.class),
                    args -> CelUnknownSet.create(CelAttribute.create("attr_varargs"))))
            .build();

    assertThat(cel.createProgram(cel.compile("unkZero() + 1").getAst()).eval())
        .isEqualTo(CelUnknownSet.create(CelAttribute.create("attr_zero")));
    assertThat(cel.createProgram(cel.compile("unkUnary(1) + 1").getAst()).eval())
        .isEqualTo(CelUnknownSet.create(CelAttribute.create("attr_unary")));
    assertThat(cel.createProgram(cel.compile("unkBinary(1, 2) + 1").getAst()).eval())
        .isEqualTo(CelUnknownSet.create(CelAttribute.create("attr_binary")));
    assertThat(cel.createProgram(cel.compile("'target'.unkMember(1) + 1").getAst()).eval())
        .isEqualTo(CelUnknownSet.create(CelAttribute.create("attr_member")));
    assertThat(cel.createProgram(cel.compile("unkVarargs(1, 2, 3) + 1").getAst()).eval())
        .isEqualTo(CelUnknownSet.create(CelAttribute.create("attr_varargs")));
  }
}
