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

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import dev.cel.bundle.Cel;
import dev.cel.bundle.CelFactory;
import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.CelContainer;
import dev.cel.common.CelOptions;
import dev.cel.common.CelSource;
import dev.cel.common.ast.CelExpr;
import dev.cel.common.types.SimpleType;
import dev.cel.common.types.StructTypeReference;
import dev.cel.expr.conformance.proto3.TestAllTypes;
import dev.cel.extensions.CelExtensions;
import dev.cel.optimizer.CelOptimizationException;
import dev.cel.optimizer.CelOptimizer;
import dev.cel.optimizer.CelOptimizerFactory;
import dev.cel.optimizer.optimizers.InliningOptimizer.InlineVariable;
import dev.cel.optimizer.optimizers.InliningOptimizer.InliningOptions;
import dev.cel.optimizer.optimizers.SubexpressionOptimizer.SubexpressionOptimizerOptions;
import dev.cel.parser.CelStandardMacro;
import dev.cel.parser.CelUnparserFactory;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(TestParameterInjector.class)
public class InliningOptimizerTest {

  private static final Cel CEL =
      CelFactory.plannerCelBuilder()
          .setStandardMacros(CelStandardMacro.STANDARD_MACROS)
          .setContainer(CelContainer.ofName("google.expr.proto3.test"))
          .addFileTypes(TestAllTypes.getDescriptor().getFile())
          .addCompilerLibraries(CelExtensions.bindings())
          .addVar("int_var", SimpleType.INT)
          .addVar("dyn_var", SimpleType.DYN)
          .addVar("msg", StructTypeReference.create(TestAllTypes.getDescriptor().getFullName()))
          .addVar("wrapper_var", StructTypeReference.create("google.protobuf.Int64Value"))
          .addVar(
              "child",
              StructTypeReference.create(TestAllTypes.NestedMessage.getDescriptor().getFullName()))
          .addVar("shadowed_ident", SimpleType.INT)
          .setOptions(
              CelOptions.current()
                  .populateMacroCalls(true)
                  .build())
          .build();

  @Test
  public void inlining_success(@TestParameter SuccessTestCase testCase) throws Exception {
    CelAbstractSyntaxTree astToInline = CEL.compile(testCase.source).getAst();
    CelAbstractSyntaxTree replacementAst = CEL.compile(testCase.replacement).getAst();

    CelOptimizer optimizer =
        CelOptimizerFactory.standardCelOptimizerBuilder(CEL)
            .addAstOptimizers(
                InliningOptimizer.newInstance(
                    InlineVariable.of(testCase.inlineVarName, replacementAst)))
            .build();

    CelAbstractSyntaxTree optimized = optimizer.optimize(astToInline);

    String unparsed = CelUnparserFactory.newUnparser().unparse(optimized);
    assertThat(unparsed).isEqualTo(testCase.expected);
  }

  @Test
  public void inlining_noop(@TestParameter NoOpTestCase testCase) throws Exception {
    CelAbstractSyntaxTree astToInline = CEL.compile(testCase.source).getAst();
    CelAbstractSyntaxTree replacementAst = CEL.compile(testCase.replacement).getAst();

    CelOptimizer optimizer =
        CelOptimizerFactory.standardCelOptimizerBuilder(CEL)
            .addAstOptimizers(
                InliningOptimizer.newInstance(InlineVariable.of(testCase.varName, replacementAst)))
            .build();

    CelAbstractSyntaxTree optimized = optimizer.optimize(astToInline);

    String unparsed = CelUnparserFactory.newUnparser().unparse(optimized);
    assertThat(unparsed).isEqualTo(testCase.source);
  }

  private enum SuccessTestCase {
    CONSTANT(
        /* source= */ "int_var + 2 + int_var",
        /* inlineVarName= */ "int_var",
        /* replacementExpr= */ "1",
        /* expected= */ "1 + 2 + 1"),
    REPEATED(
        /* source= */ "dyn_var + [dyn_var]",
        /* inlineVarName= */ "dyn_var",
        /* replacementExpr= */ "dyn([1, 2])",
        /* expected= */ "dyn([1, 2]) + [dyn([1, 2])]"),
    SELECT_WITH_MACRO(
        /* source= */ "has(msg.single_any.processing_purpose) ?"
            + " msg.single_any.processing_purpose.map(i, i * 2)[0] : 42",
        /* inlineVarName= */ "msg.single_any.processing_purpose",
        /* replacementExpr= */ "[1,2,3].map(i, i * 2)",
        /* expected= */ "([1, 2, 3].map(i, i * 2).size() != 0) ? ([1, 2, 3].map(i, i * 2).map(i, i"
            + " * 2)[0]) : 42"),
    PRESENCE_WITH_SELECT_EXPR(
        /* source= */ "has(msg.single_any)",
        /* inlineVarName= */ "msg.single_any",
        /* replacementExpr= */ "msg.single_int64_wrapper",
        /* expected= */ "has(msg.single_int64_wrapper)"),
    PRESENCE_WITH_IDENT_NOT_NULL_REWRITE(
        /* source= */ "has(msg.single_int64_wrapper)",
        /* inlineVarName= */ "msg.single_int64_wrapper",
        /* replacementExpr= */ "wrapper_var",
        /* expected= */ "wrapper_var != null"),
    PRESENCE_WITH_LIST_SIZE_REWRITE(
        /* source= */ "has(msg.single_any.processing_purpose)",
        /* inlineVarName= */ "msg.single_any.processing_purpose",
        /* replacementExpr= */ "[1, 2, 3]",
        /* expected= */ "[1, 2, 3].size() != 0"),
    PRESENCE_WITH_INT_LITERAL_REWRITE(
        /* source= */ "has(msg.single_any.processing_purpose)",
        /* inlineVarName= */ "msg.single_any.processing_purpose",
        /* replacementExpr= */ "1",
        /* expected= */ "1 != 0"),
    PRESENCE_WITH_UINT_LITERAL_REWRITE(
        /* source= */ "has(msg.single_any.processing_purpose)",
        /* inlineVarName= */ "msg.single_any.processing_purpose",
        /* replacementExpr= */ "1u",
        /* expected= */ "1u != 0u"),
    PRESENCE_WITH_DOUBLE_LITERAL_REWRITE(
        /* source= */ "has(msg.single_any.processing_purpose)",
        /* inlineVarName= */ "msg.single_any.processing_purpose",
        /* replacementExpr= */ "1.5",
        /* expected= */ "1.5 != 0.0"),
    PRESENCE_WITH_BOOL_LITERAL_REWRITE(
        /* source= */ "has(msg.single_any.processing_purpose)",
        /* inlineVarName= */ "msg.single_any.processing_purpose",
        /* replacementExpr= */ "true",
        /* expected= */ "true != false"),
    PRESENCE_WITH_STRING_LITERAL_REWRITE(
        /* source= */ "has(msg.single_any.processing_purpose)",
        /* inlineVarName= */ "msg.single_any.processing_purpose",
        /* replacementExpr= */ "'foo'",
        /* expected= */ "\"foo\".size() != 0"),
    PRESENCE_WITH_BYTES_LITERAL_REWRITE(
        /* source= */ "has(msg.single_any.processing_purpose)",
        /* inlineVarName= */ "msg.single_any.processing_purpose",
        /* replacementExpr= */ "b'abc'",
        /* expected= */ "b\"\\141\\142\\143\".size() != 0"),
    PRESENCE_WITH_TIMESTAMP_LITERAL_REWRITE(
        /* source= */ "has(msg.single_any.processing_purpose)",
        /* inlineVarName= */ "msg.single_any.processing_purpose",
        /* replacementExpr= */ "timestamp(1)",
        /* expected= */ "timestamp(1) != timestamp(0)"),
    PRESENCE_WITH_DURATION_LITERAL_REWRITE(
        /* source= */ "has(msg.single_any.processing_purpose)",
        /* inlineVarName= */ "msg.single_any.processing_purpose",
        /* replacementExpr= */ "duration('1h')",
        /* expected= */ "duration(\"1h\") != duration(\"0\")"),
    PRESENCE_WITH_PROTOBUF_MESSAGE_REWRITE(
        /* source= */ "has(msg.single_any.processing_purpose)",
        /* inlineVarName= */ "msg.single_any.processing_purpose",
        /* replacementExpr= */ "cel.expr.conformance.proto3.TestAllTypes{single_int64: 1}",
        /* expected= */ "cel.expr.conformance.proto3.TestAllTypes{single_int64: 1} !="
            + " cel.expr.conformance.proto3.TestAllTypes{}"),
    NESTED_SELECT(
        /* source= */ "msg.standalone_message.bb",
        /* inlineVarName= */ "msg.standalone_message",
        /* replacementExpr= */ "child",
        /* expected= */ "child.bb"),
    ;

    private final String source;
    private final String inlineVarName;
    private final String replacement;
    private final String expected;

    SuccessTestCase(String source, String inlineVarName, String replacementExpr, String expected) {
      this.source = source;
      this.inlineVarName = inlineVarName;
      this.replacement = replacementExpr;
      this.expected = expected;
    }
  }

  private enum NoOpTestCase {
    NO_INLINE_ITER_VAR("[0].exists(shadowed_ident, shadowed_ident == 0)", "shadowed_ident", "1"),
    NO_INLINE_BIND_VAR("cel.bind(shadowed_ident, 2, shadowed_ident + 1)", "shadowed_ident", "1"),
    ;

    private final String source;
    private final String varName;
    private final String replacement;

    NoOpTestCase(String source, String varName, String replacement) {
      this.source = source;
      this.varName = varName;
      this.replacement = replacement;
    }
  }

  @Test
  public void inline_exceededIterationLimit_throws() throws Exception {
    String expression = "int_var + int_var + int_var";
    CelAbstractSyntaxTree astToInline = CEL.compile(expression).getAst();
    CelOptimizer optimizer =
        CelOptimizerFactory.standardCelOptimizerBuilder(CEL)
            .addAstOptimizers(
                InliningOptimizer.newInstance(
                    InliningOptions.newBuilder().maxIterationLimit(2).build(),
                    InlineVariable.of("int_var", CEL.compile("1").getAst())))
            .build();

    CelOptimizationException e =
        assertThrows(CelOptimizationException.class, () -> optimizer.optimize(astToInline));
    assertThat(e).hasMessageThat().contains("Max iteration count reached.");
  }

  @Test
  public void inlineVariableDecl_internalVar_throws() throws Exception {
    IllegalArgumentException e =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                InlineVariable.of(
                    "@internal_var",
                    CelAbstractSyntaxTree.newParsedAst(
                        CelExpr.ofNotSet(0L), CelSource.newBuilder().build())));
    assertThat(e).hasMessageThat().contains("Internal variables cannot be inlined: @internal_var");
  }

  @Test
  public void inline_then_cse() throws Exception {
    String source =
        "has(msg.single_any.processing_purpose) ? "
            + "msg.single_any.processing_purpose.map(i, i * 2)[0] : 42";
    CelAbstractSyntaxTree astToInline = CEL.compile(source).getAst();

    CelOptimizer optimizer =
        CelOptimizerFactory.standardCelOptimizerBuilder(CEL)
            .addAstOptimizers(
                InliningOptimizer.newInstance(
                    InlineVariable.of(
                        "msg.single_any.processing_purpose",
                        CEL.compile("[1,2,3].map(i, i * 2)").getAst())),
                SubexpressionOptimizer.newInstance(
                    SubexpressionOptimizerOptions.newBuilder().populateMacroCalls(true).build()))
            .build();

    CelAbstractSyntaxTree optimized = optimizer.optimize(astToInline);

    String unparsed = CelUnparserFactory.newUnparser().unparse(optimized);
    assertThat(unparsed)
        .isEqualTo(
            "cel.@block([[1, 2, 3].map(@it:0:0, @it:0:0 * 2)], "
                + "(@index0.size() != 0) ? (@index0.map(@it:1:0, @it:1:0 * 2)[0]) : 42)");
  }

  @Test
  public void allowInliningDependentVariables_inOrder_dependentVariablesInlined() throws Exception {
    String source = "int_var + dyn_var";
    CelAbstractSyntaxTree astToInline = CEL.compile(source).getAst();

    CelOptimizer optimizer =
        CelOptimizerFactory.standardCelOptimizerBuilder(CEL)
            .addAstOptimizers(
                InliningOptimizer.newInstance(
                    InlineVariable.of("int_var", CEL.compile("dyn_var").getAst()),
                    InlineVariable.of("dyn_var", CEL.compile("2").getAst())))
            .build();

    CelAbstractSyntaxTree optimized = optimizer.optimize(astToInline);

    String unparsed = CelUnparserFactory.newUnparser().unparse(optimized);
    assertThat(unparsed).isEqualTo("2 + 2");
  }

  @Test
  public void allowInliningDependentVariables_reverseOrder_inliningIsIndependent()
      throws Exception {
    String source = "int_var + dyn_var";
    CelAbstractSyntaxTree astToInline = CEL.compile(source).getAst();

    CelOptimizer optimizer =
        CelOptimizerFactory.standardCelOptimizerBuilder(CEL)
            .addAstOptimizers(
                InliningOptimizer.newInstance(
                    InlineVariable.of("dyn_var", CEL.compile("2").getAst()),
                    InlineVariable.of("int_var", CEL.compile("dyn_var").getAst())))
            .build();

    CelAbstractSyntaxTree optimized = optimizer.optimize(astToInline);

    String unparsed = CelUnparserFactory.newUnparser().unparse(optimized);
    assertThat(unparsed).isEqualTo("dyn_var + 2");
  }
}
