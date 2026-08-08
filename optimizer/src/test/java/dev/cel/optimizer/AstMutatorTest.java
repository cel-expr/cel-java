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

package dev.cel.optimizer;

import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableMap;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import com.google.testing.junit.testparameterinjector.TestParameters;
import dev.cel.bundle.Cel;
import dev.cel.bundle.CelFactory;
import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.CelContainer;
import dev.cel.common.CelFunctionDecl;
import dev.cel.common.CelMutableAst;
import dev.cel.common.CelOptions;
import dev.cel.common.CelOverloadDecl;
import dev.cel.common.CelSource;
import dev.cel.common.CelSource.Extension;
import dev.cel.common.CelSource.Extension.Version;
import dev.cel.common.ast.CelConstant;
import dev.cel.common.ast.CelExpr;
import dev.cel.common.ast.CelExpr.ExprKind.Kind;
import dev.cel.common.ast.CelMutableExpr;
import dev.cel.common.ast.CelMutableExpr.CelMutableList;
import dev.cel.common.ast.CelMutableExpr.CelMutableSelect;
import dev.cel.common.ast.CelMutableExprConverter;
import dev.cel.common.navigation.CelNavigableAst;
import dev.cel.common.navigation.CelNavigableExpr;
import dev.cel.common.navigation.TraversalOrder;
import dev.cel.common.types.SimpleType;
import dev.cel.common.types.StructTypeReference;
import dev.cel.expr.conformance.proto3.TestAllTypes;
import dev.cel.extensions.CelExtensions;
import dev.cel.extensions.CelOptionalLibrary;
import dev.cel.optimizer.AstMutator.SubtreeReplacement;
import dev.cel.parser.CelStandardMacro;
import dev.cel.parser.CelUnparser;
import dev.cel.parser.CelUnparserFactory;
import java.util.List;
import java.util.Optional;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(TestParameterInjector.class)
public class AstMutatorTest {
  private static final Cel CEL =
      CelFactory.plannerCelBuilder()
          .setStandardMacros(CelStandardMacro.STANDARD_MACROS)
          .setOptions(
              CelOptions.current()
                  .populateMacroCalls(true)
                  .enableHeterogeneousNumericComparisons(true)
                  .build())
          .addMessageTypes(TestAllTypes.getDescriptor())
          .addCompilerLibraries(
              CelOptionalLibrary.INSTANCE, CelExtensions.bindings(), CelExtensions.comprehensions())
          .addRuntimeLibraries(CelOptionalLibrary.INSTANCE, CelExtensions.comprehensions())
          .setContainer(CelContainer.ofName("cel.expr.conformance.proto3"))
          .addVar("msg", StructTypeReference.create(TestAllTypes.getDescriptor().getFullName()))
          .addVar("x", SimpleType.INT)
          .addVar("b", SimpleType.BOOL)
          .build();

  private static final CelUnparser CEL_UNPARSER = CelUnparserFactory.newUnparser();
  private static final AstMutator AST_MUTATOR = AstMutator.newInstance(1000);

  @Test
  public void replaceSubtree_replaceConst() throws Exception {
    CelAbstractSyntaxTree ast = CEL.compile("10").getAst();
    CelMutableAst mutableAst = CelMutableAst.fromCelAst(ast);
    CelMutableExpr newBooleanConst = CelMutableExpr.ofConstant(1, CelConstant.ofValue(true));

    CelMutableAst result =
        AST_MUTATOR.replaceSubtree(mutableAst, newBooleanConst, mutableAst.expr().id());

    assertThat(result.toParsedAst().getExpr())
        .isEqualTo(CelExpr.ofConstant(3, CelConstant.ofValue(true)));
  }

  @Test
  public void astMutator_returnsParsedAst() throws Exception {
    CelAbstractSyntaxTree ast = CEL.compile("10").getAst();
    CelMutableAst mutableAst = CelMutableAst.fromCelAst(ast);
    CelMutableExpr newBooleanConst = CelMutableExpr.ofConstant(1, CelConstant.ofValue(true));

    CelMutableAst result =
        AST_MUTATOR.replaceSubtree(mutableAst, newBooleanConst, mutableAst.expr().id());

    assertThat(ast.isChecked()).isTrue();
    assertThat(result.toParsedAst().isChecked()).isFalse();
  }

  @Test
  public void astMutator_nonMacro_sourceCleared() throws Exception {
    CelAbstractSyntaxTree ast = CEL.compile("10").getAst();
    CelMutableAst mutableAst = CelMutableAst.fromCelAst(ast);
    CelMutableExpr newBooleanConst = CelMutableExpr.ofConstant(1, CelConstant.ofValue(true));

    CelAbstractSyntaxTree mutatedAst =
        AST_MUTATOR
            .replaceSubtree(mutableAst, newBooleanConst, mutableAst.expr().id())
            .toParsedAst();

    assertThat(mutatedAst.getSource().getLineOffsets()).isEmpty();
    assertThat(mutatedAst.getSource().getPositionsMap()).isEmpty();
    assertThat(mutatedAst.getSource().getExtensions()).isEmpty();
    assertThat(mutatedAst.getSource().getMacroCalls()).isEmpty();
    assertThat(mutatedAst.getSource().getDescription()).isEqualTo(ast.getSource().getDescription());
  }

  @Test
  public void astMutator_macro_sourceMacroCallsPopulated() throws Exception {
    CelAbstractSyntaxTree ast = CEL.compile("has(TestAllTypes{}.single_int32)").getAst();
    CelMutableAst mutableAst = CelMutableAst.fromCelAst(ast);
    CelMutableExpr newBooleanConst = CelMutableExpr.ofConstant(1, CelConstant.ofValue(true));

    CelAbstractSyntaxTree mutatedAst =
        AST_MUTATOR.replaceSubtree(mutableAst, newBooleanConst, 1).toParsedAst(); // no_op

    assertThat(mutatedAst.getSource().getLineOffsets()).isEmpty();
    assertThat(mutatedAst.getSource().getPositionsMap()).isEmpty();
    assertThat(mutatedAst.getSource().getExtensions()).isEmpty();
    assertThat(mutatedAst.getSource().getMacroCalls()).isNotEmpty();
    assertThat(mutatedAst.getSource().getDescription()).isEqualTo(ast.getSource().getDescription());
  }

  @Test
  public void replaceSubtree_astContainsTaggedExtension_retained() throws Exception {
    CelAbstractSyntaxTree ast = CEL.compile("has(TestAllTypes{}.single_int32)").getAst();
    Extension extension = Extension.create("test", Version.of(1, 1));
    CelSource celSource = ast.getSource().toBuilder().addAllExtensions(extension).build();
    ast =
        CelAbstractSyntaxTree.newCheckedAst(
            ast.getExpr(), celSource, ast.getReferenceMap(), ast.getTypeMap());
    CelMutableAst mutableAst = CelMutableAst.fromCelAst(ast);

    CelAbstractSyntaxTree mutatedAst =
        AST_MUTATOR
            .replaceSubtree(mutableAst, CelMutableExpr.ofConstant(CelConstant.ofValue(true)), 1)
            .toParsedAst();

    assertThat(mutatedAst.getSource().getExtensions()).containsExactly(extension);
  }

  @Test
  public void replaceSubtreeWithNewAst_astsContainTaggedExtension_retained() throws Exception {
    // Setup first AST with a test extension
    CelAbstractSyntaxTree ast = CEL.compile("has(TestAllTypes{}.single_int32)").getAst();
    Extension extension = Extension.create("test", Version.of(1, 1));
    ast =
        CelAbstractSyntaxTree.newCheckedAst(
            ast.getExpr(),
            ast.getSource().toBuilder().addAllExtensions(extension).build(),
            ast.getReferenceMap(),
            ast.getTypeMap());
    CelMutableAst mutableAst = CelMutableAst.fromCelAst(ast);
    // Setup second AST with another test extension
    CelAbstractSyntaxTree astToReplaceWith = CEL.compile("cel.bind(a, true, a)").getAst();
    Extension extension2 = Extension.create("test2", Version.of(2, 2));
    astToReplaceWith =
        CelAbstractSyntaxTree.newCheckedAst(
            astToReplaceWith.getExpr(),
            astToReplaceWith.getSource().toBuilder().addAllExtensions(extension2).build(),
            astToReplaceWith.getReferenceMap(),
            astToReplaceWith.getTypeMap());
    CelMutableAst mutableAst2 = CelMutableAst.fromCelAst(astToReplaceWith);

    // Mutate the original AST with the new AST at the root
    CelAbstractSyntaxTree mutatedAst =
        AST_MUTATOR.replaceSubtree(mutableAst, mutableAst2, mutableAst.expr().id()).toParsedAst();

    // Expect that both the extensions are merged
    assertThat(mutatedAst.getSource().getExtensions()).containsExactly(extension, extension2);
  }

  @Test
  public void replaceSubtreeWithNewAst_astsContainSameExtensions_deduped() throws Exception {
    // Setup first AST with a test extension
    CelAbstractSyntaxTree ast = CEL.compile("has(TestAllTypes{}.single_int32)").getAst();
    Extension extension = Extension.create("test", Version.of(1, 1));
    ast =
        CelAbstractSyntaxTree.newCheckedAst(
            ast.getExpr(),
            ast.getSource().toBuilder().addAllExtensions(extension).build(),
            ast.getReferenceMap(),
            ast.getTypeMap());
    CelMutableAst mutableAst = CelMutableAst.fromCelAst(ast);
    // Setup second AST with the same test extension as above
    CelAbstractSyntaxTree astToReplaceWith = CEL.compile("cel.bind(a, true, a)").getAst();
    Extension extension2 = Extension.create("test", Version.of(1, 1));
    astToReplaceWith =
        CelAbstractSyntaxTree.newCheckedAst(
            astToReplaceWith.getExpr(),
            astToReplaceWith.getSource().toBuilder().addAllExtensions(extension2).build(),
            astToReplaceWith.getReferenceMap(),
            astToReplaceWith.getTypeMap());
    CelMutableAst mutableAst2 = CelMutableAst.fromCelAst(astToReplaceWith);

    // Mutate the original AST with the new AST at the root
    CelAbstractSyntaxTree mutatedAst =
        AST_MUTATOR.replaceSubtree(mutableAst, mutableAst2, mutableAst.expr().id()).toParsedAst();

    // Expect that the extension is deduped
    assertThat(mutatedAst.getSource().getExtensions()).containsExactly(extension);
  }

  @Test
  @TestParameters("{source: '[1].exists(x, x > 0)', expectedMacroCallSize: 1}")
  @TestParameters(
      "{source: '[1].exists(x, x > 0) && [2].exists(x, x > 0)', expectedMacroCallSize: 2}")
  @TestParameters(
      "{source: '[1].exists(x, [2].exists(y, x > 0 && y > x))', expectedMacroCallSize: 2}")
  public void replaceSubtree_rootReplacedWithMacro_macroCallPopulated(
      String source, int expectedMacroCallSize) throws Exception {
    CelAbstractSyntaxTree ast = CEL.compile("1").getAst();
    CelAbstractSyntaxTree ast2 = CEL.compile(source).getAst();
    CelMutableAst mutableAst = CelMutableAst.fromCelAst(ast);
    CelMutableAst mutableAst2 = CelMutableAst.fromCelAst(ast2);

    CelAbstractSyntaxTree mutatedAst =
        AST_MUTATOR.replaceSubtree(mutableAst, mutableAst2, mutableAst.expr().id()).toParsedAst();

    assertThat(mutatedAst.getSource().getMacroCalls()).hasSize(expectedMacroCallSize);
    assertThat(CEL_UNPARSER.unparse(mutatedAst)).isEqualTo(source);
    assertThat(CEL.createProgram(CEL.check(mutatedAst).getAst()).eval()).isEqualTo(true);
  }

  @Test
  public void replaceSubtree_leftBranchReplacedWithMacro_macroCallPopulated() throws Exception {
    CelAbstractSyntaxTree ast = CEL.compile("true && false").getAst();
    CelAbstractSyntaxTree ast2 = CEL.compile("[1].exists(x, x > 0)").getAst();
    CelMutableAst mutableAst = CelMutableAst.fromCelAst(ast);
    CelMutableAst mutableAst2 = CelMutableAst.fromCelAst(ast2);

    CelAbstractSyntaxTree mutatedAst =
        AST_MUTATOR
            .replaceSubtree(mutableAst, mutableAst2, 3)
            .toParsedAst(); // Replace false with the macro expr

    assertThat(mutatedAst.getSource().getMacroCalls()).hasSize(1);
    assertThat(CEL_UNPARSER.unparse(mutatedAst)).isEqualTo("true && [1].exists(x, x > 0)");
    assertThat(CEL.createProgram(CEL.check(mutatedAst).getAst()).eval()).isEqualTo(true);
  }

  @Test
  public void replaceSubtree_rightBranchReplacedWithMacro_macroCallPopulated() throws Exception {
    CelAbstractSyntaxTree ast = CEL.compile("true && false").getAst();
    CelAbstractSyntaxTree ast2 = CEL.compile("[1].exists(x, x > 0)").getAst();
    CelMutableAst mutableAst = CelMutableAst.fromCelAst(ast);
    CelMutableAst mutableAst2 = CelMutableAst.fromCelAst(ast2);

    CelAbstractSyntaxTree mutatedAst =
        AST_MUTATOR
            .replaceSubtree(mutableAst, mutableAst2, 1)
            .toParsedAst(); // Replace true with the macro expr

    assertThat(mutatedAst.getSource().getMacroCalls()).hasSize(1);
    assertThat(CEL_UNPARSER.unparse(mutatedAst)).isEqualTo("[1].exists(x, x > 0) && false");
    assertThat(CEL.createProgram(CEL.check(mutatedAst).getAst()).eval()).isEqualTo(false);
  }

  @Test
  public void replaceSubtree_macroInsertedIntoExistingMacro_macroCallPopulated() throws Exception {
    CelAbstractSyntaxTree ast = CEL.compile("[1].exists(x, x > 0 && true)").getAst();
    CelAbstractSyntaxTree ast2 = CEL.compile("[2].exists(y, y > 0)").getAst();
    CelMutableAst mutableAst = CelMutableAst.fromCelAst(ast);
    CelMutableAst mutableAst2 = CelMutableAst.fromCelAst(ast2);

    CelAbstractSyntaxTree mutatedAst =
        AST_MUTATOR
            .replaceSubtree(mutableAst, mutableAst2, 9)
            .toParsedAst(); // Replace true with the ast2 maro expr

    assertThat(mutatedAst.getSource().getMacroCalls()).hasSize(2);
    assertThat(CEL_UNPARSER.unparse(mutatedAst))
        .isEqualTo("[1].exists(x, x > 0 && [2].exists(y, y > 0))");
    assertThat(CEL.createProgram(CEL.check(mutatedAst).getAst()).eval()).isEqualTo(true);
  }

  @Test
  public void replaceSubtree_macroReplacedWithConstExpr_macroCallCleared() throws Exception {
    CelAbstractSyntaxTree ast =
        CEL.compile("[1].exists(x, x > 0) && [2].exists(x, x > 0)").getAst();
    CelAbstractSyntaxTree ast2 = CEL.compile("1").getAst();
    CelMutableAst mutableAst = CelMutableAst.fromCelAst(ast);
    CelMutableAst mutableAst2 = CelMutableAst.fromCelAst(ast2);

    CelAbstractSyntaxTree mutatedAst =
        AST_MUTATOR.replaceSubtree(mutableAst, mutableAst2, mutableAst.expr().id()).toParsedAst();

    assertThat(mutatedAst.getSource().getMacroCalls()).isEmpty();
    assertThat(CEL_UNPARSER.unparse(mutatedAst)).isEqualTo("1");
    assertThat(CEL.createProgram(CEL.check(mutatedAst).getAst()).eval()).isEqualTo(1);
  }

  @Test
  @SuppressWarnings("unchecked") // Test only
  public void replaceSubtree_replaceExtraneousListCreatedByMacro_unparseSuccess() throws Exception {
    // Certain macros such as `map` or `filter` generates an extraneous list_expr in the loop step's
    // argument that does not exist in the original expression.
    // For example, the loop step of this expression looks like:
    // CALL [10] {
    //    function: _+_
    //    args: {
    //      IDENT [8] {
    //        name: __result__
    //      }
    //      LIST [9] {
    //        elements: {
    //          CONSTANT [5] { value: 1 }
    //        }
    //      }
    //    }
    //  }
    CelAbstractSyntaxTree ast = CEL.compile("[1].map(x, 1)").getAst();
    CelMutableAst mutableAst = CelMutableAst.fromCelAst(ast);
    CelMutableAst mutableAst2 = CelMutableAst.fromCelAst(ast);

    // These two mutation are equivalent.
    CelAbstractSyntaxTree mutatedAstWithList =
        AST_MUTATOR
            .replaceSubtree(
                mutableAst,
                CelMutableExpr.ofList(
                    CelMutableList.create(CelMutableExpr.ofConstant(CelConstant.ofValue(2L)))),
                9L)
            .toParsedAst();
    CelAbstractSyntaxTree mutatedAstWithConstant =
        AST_MUTATOR
            .replaceSubtree(mutableAst2, CelMutableExpr.ofConstant(CelConstant.ofValue(2L)), 5L)
            .toParsedAst();

    assertThat(CEL_UNPARSER.unparse(mutatedAstWithList)).isEqualTo("[1].map(x, 2)");
    assertThat(CEL_UNPARSER.unparse(mutatedAstWithConstant)).isEqualTo("[1].map(x, 2)");
    assertThat((List<Long>) CEL.createProgram(CEL.check(mutatedAstWithList).getAst()).eval())
        .containsExactly(2L);
  }

  @Test
  @SuppressWarnings("unchecked") // Test only
  public void replaceSubtree_replaceExtraneousListCreatedByThreeArgMacro_unparseSuccess()
      throws Exception {
    CelAbstractSyntaxTree ast = CEL.compile("[1].map(x, true, 1)").getAst();
    CelMutableAst mutableAst = CelMutableAst.fromCelAst(ast);
    CelMutableAst mutableAst2 = CelMutableAst.fromCelAst(ast);

    // These two mutation are equivalent.
    CelAbstractSyntaxTree mutatedAstWithList =
        AST_MUTATOR
            .replaceSubtree(
                mutableAst,
                CelMutableExpr.ofList(
                    CelMutableList.create(CelMutableExpr.ofConstant(CelConstant.ofValue(2L)))),
                10L)
            .toParsedAst();
    CelAbstractSyntaxTree mutatedAstWithConstant =
        AST_MUTATOR
            .replaceSubtree(mutableAst2, CelMutableExpr.ofConstant(CelConstant.ofValue(2L)), 6L)
            .toParsedAst();

    assertThat(CEL_UNPARSER.unparse(mutatedAstWithList)).isEqualTo("[1].map(x, true, 2)");
    assertThat(CEL_UNPARSER.unparse(mutatedAstWithConstant)).isEqualTo("[1].map(x, true, 2)");
    assertThat((List<Long>) CEL.createProgram(CEL.check(mutatedAstWithList).getAst()).eval())
        .containsExactly(2L);
  }

  @Test
  public void globalCallExpr_replaceRoot() throws Exception {
    // Tree shape (brackets are expr IDs):
    //           + [4]
    //      + [2]       x [5]
    //  1 [1]     2 [3]
    CelAbstractSyntaxTree ast = CEL.compile("1 + 2 + x").getAst();
    CelMutableAst mutableAst = CelMutableAst.fromCelAst(ast);
    CelMutableExpr newExpr = CelMutableExpr.ofConstant(CelConstant.ofValue(10));

    CelMutableAst result = AST_MUTATOR.replaceSubtree(mutableAst, newExpr, mutableAst.expr().id());

    assertThat(result.toParsedAst().getExpr())
        .isEqualTo(CelExpr.ofConstant(7, CelConstant.ofValue(10)));
  }

  @Test
  public void globalCallExpr_replaceLeaf() throws Exception {
    // Tree shape (brackets are expr IDs):
    //           + [4]
    //      + [2]       x [5]
    //  1 [1]     2 [3]
    CelAbstractSyntaxTree ast = CEL.compile("1 + 2 + x").getAst();
    CelMutableAst mutableAst = CelMutableAst.fromCelAst(ast);
    CelMutableExpr newExpr = CelMutableExpr.ofConstant(CelConstant.ofValue(10));

    CelMutableAst result = AST_MUTATOR.replaceSubtree(mutableAst, newExpr, 1);

    assertThat(CEL_UNPARSER.unparse(result.toParsedAst())).isEqualTo("10 + 2 + x");
  }

  @Test
  public void globalCallExpr_replaceMiddleBranch() throws Exception {
    // Tree shape (brackets are expr IDs):
    //           + [4]
    //      + [2]       x [5]
    //  1 [1]     2 [3]
    CelAbstractSyntaxTree ast = CEL.compile("1 + 2 + x").getAst();
    CelMutableAst mutableAst = CelMutableAst.fromCelAst(ast);
    CelMutableExpr newExpr = CelMutableExpr.ofConstant(CelConstant.ofValue(10));

    CelMutableAst result = AST_MUTATOR.replaceSubtree(mutableAst, newExpr, 2);

    assertThat(CEL_UNPARSER.unparse(result.toParsedAst())).isEqualTo("10 + x");
  }

  @Test
  public void globalCallExpr_replaceMiddleBranch_withCallExpr() throws Exception {
    // Tree shape (brackets are expr IDs):
    //           + [4]
    //      + [2]       x [5]
    //  1 [1]     2 [3]
    CelAbstractSyntaxTree ast = CEL.compile("1 + 2 + x").getAst();
    CelAbstractSyntaxTree ast2 = CEL.compile("4 + 5 + 6").getAst();
    CelMutableAst mutableAst = CelMutableAst.fromCelAst(ast);
    CelMutableExpr newExpr = CelMutableExprConverter.fromCelExpr(ast2.getExpr());

    CelMutableAst result = AST_MUTATOR.replaceSubtree(mutableAst, newExpr, 2);

    assertThat(CEL_UNPARSER.unparse(result.toParsedAst())).isEqualTo("4 + 5 + 6 + x");
  }

  @Test
  public void memberCallExpr_replaceLeafTarget() throws Exception {
    // Tree shape (brackets are expr IDs):
    //           func [2]
    //      10 [1]       func [4]
    //                4 [3]       5 [5]
    Cel cel =
        CelFactory.plannerCelBuilder()
            .addFunctionDeclarations(
                CelFunctionDecl.newFunctionDeclaration(
                    "func",
                    CelOverloadDecl.newMemberOverload(
                        "func_overload", SimpleType.INT, SimpleType.INT, SimpleType.INT)))
            .build();
    CelAbstractSyntaxTree ast = cel.compile("10.func(4.func(5))").getAst();
    CelMutableAst mutableAst = CelMutableAst.fromCelAst(ast);
    CelMutableExpr newExpr = CelMutableExpr.ofConstant(CelConstant.ofValue(20));

    CelMutableAst result = AST_MUTATOR.replaceSubtree(mutableAst, newExpr, 3);

    assertThat(CEL_UNPARSER.unparse(result.toParsedAst())).isEqualTo("10.func(20.func(5))");
  }

  @Test
  public void memberCallExpr_replaceLeafArgument() throws Exception {
    // Tree shape (brackets are expr IDs):
    //           func [2]
    //      10 [1]       func [4]
    //                4 [3]       5 [5]
    Cel cel =
        CelFactory.plannerCelBuilder()
            .addFunctionDeclarations(
                CelFunctionDecl.newFunctionDeclaration(
                    "func",
                    CelOverloadDecl.newMemberOverload(
                        "func_overload", SimpleType.INT, SimpleType.INT, SimpleType.INT)))
            .build();
    CelAbstractSyntaxTree ast = cel.compile("10.func(4.func(5))").getAst();
    CelMutableAst mutableAst = CelMutableAst.fromCelAst(ast);
    CelMutableExpr newExpr = CelMutableExpr.ofConstant(CelConstant.ofValue(20));

    CelMutableAst result = AST_MUTATOR.replaceSubtree(mutableAst, newExpr, 5);

    assertThat(CEL_UNPARSER.unparse(result.toParsedAst())).isEqualTo("10.func(4.func(20))");
  }

  @Test
  public void memberCallExpr_replaceMiddleBranchTarget() throws Exception {
    // Tree shape (brackets are expr IDs):
    //           func [2]
    //      10 [1]       func [4]
    //                4 [3]       5 [5]
    Cel cel =
        CelFactory.plannerCelBuilder()
            .addFunctionDeclarations(
                CelFunctionDecl.newFunctionDeclaration(
                    "func",
                    CelOverloadDecl.newMemberOverload(
                        "func_overload", SimpleType.INT, SimpleType.INT, SimpleType.INT)))
            .build();
    CelAbstractSyntaxTree ast = cel.compile("10.func(4.func(5))").getAst();
    CelMutableAst mutableAst = CelMutableAst.fromCelAst(ast);
    CelMutableExpr newExpr = CelMutableExpr.ofConstant(CelConstant.ofValue(20));

    CelMutableAst result = AST_MUTATOR.replaceSubtree(mutableAst, newExpr, 1);

    assertThat(CEL_UNPARSER.unparse(result.toParsedAst())).isEqualTo("20.func(4.func(5))");
  }

  @Test
  public void memberCallExpr_replaceMiddleBranchArgument() throws Exception {
    // Tree shape (brackets are expr IDs):
    //           func [2]
    //      10 [1]       func [4]
    //                4 [3]       5 [5]
    Cel cel =
        CelFactory.plannerCelBuilder()
            .addFunctionDeclarations(
                CelFunctionDecl.newFunctionDeclaration(
                    "func",
                    CelOverloadDecl.newMemberOverload(
                        "func_overload", SimpleType.INT, SimpleType.INT, SimpleType.INT)))
            .build();
    CelAbstractSyntaxTree ast = cel.compile("10.func(4.func(5))").getAst();
    CelMutableAst mutableAst = CelMutableAst.fromCelAst(ast);
    CelMutableExpr newExpr = CelMutableExpr.ofConstant(CelConstant.ofValue(20));

    CelMutableAst result = AST_MUTATOR.replaceSubtree(mutableAst, newExpr, 4);

    assertThat(CEL_UNPARSER.unparse(result.toParsedAst())).isEqualTo("10.func(20)");
  }

  @Test
  public void select_replaceField() throws Exception {
    // Tree shape (brackets are expr IDs):
    //              + [2]
    //         5 [1]        select [4]
    //                msg [3]
    CelAbstractSyntaxTree ast = CEL.compile("5 + msg.single_int64").getAst();
    CelMutableAst mutableAst = CelMutableAst.fromCelAst(ast);
    CelMutableExpr newExpr =
        CelMutableExpr.ofSelect(
            CelMutableSelect.create(CelMutableExpr.ofIdent("test"), "single_sint32"));

    CelMutableAst result = AST_MUTATOR.replaceSubtree(mutableAst, newExpr, 4);

    assertThat(CEL_UNPARSER.unparse(result.toParsedAst())).isEqualTo("5 + test.single_sint32");
  }

  @Test
  public void select_replaceOperand() throws Exception {
    // Tree shape (brackets are expr IDs):
    //              + [2]
    //         5 [1]        select [4]
    //                msg [3]
    CelAbstractSyntaxTree ast = CEL.compile("5 + msg.single_int64").getAst();
    CelMutableAst mutableAst = CelMutableAst.fromCelAst(ast);
    CelMutableExpr newExpr = CelMutableExpr.ofIdent("test");

    CelMutableAst result = AST_MUTATOR.replaceSubtree(mutableAst, newExpr, 3);

    assertThat(CEL_UNPARSER.unparse(result.toParsedAst())).isEqualTo("5 + test.single_int64");
  }

  @Test
  public void list_replaceElement() throws Exception {
    // Tree shape (brackets are expr IDs):
    //        list [1]
    //  2 [2]  3 [3]  4 [4]
    CelAbstractSyntaxTree ast = CEL.compile("[2, 3, 4]").getAst();
    CelMutableAst mutableAst = CelMutableAst.fromCelAst(ast);
    CelMutableExpr newExpr = CelMutableExpr.ofConstant(CelConstant.ofValue(5));

    CelMutableAst result = AST_MUTATOR.replaceSubtree(mutableAst, newExpr, 4);

    assertThat(CEL_UNPARSER.unparse(result.toParsedAst())).isEqualTo("[2, 3, 5]");
  }

  @Test
  public void struct_replaceValue() throws Exception {
    // Tree shape (brackets are expr IDs):
    //        TestAllTypes [1]
    //             single_int64 [2]
    //                  2 [3]
    CelAbstractSyntaxTree ast = CEL.compile("TestAllTypes{single_int64: 2}").getAst();
    CelMutableAst mutableAst = CelMutableAst.fromCelAst(ast);
    CelMutableExpr newExpr = CelMutableExpr.ofConstant(CelConstant.ofValue(5));

    CelMutableAst result = AST_MUTATOR.replaceSubtree(mutableAst, newExpr, 3);

    assertThat(CEL_UNPARSER.unparse(result.toParsedAst()))
        .isEqualTo("cel.expr.conformance.proto3.TestAllTypes{single_int64: 5}");
  }

  @Test
  public void map_replaceKey() throws Exception {
    // Tree shape (brackets are expr IDs):
    //        map [1]
    //       map_entry [2]
    //     'a' [3] : 1 [4]
    CelAbstractSyntaxTree ast = CEL.compile("{'a': 1}").getAst();
    CelMutableAst mutableAst = CelMutableAst.fromCelAst(ast);
    CelMutableExpr newExpr = CelMutableExpr.ofConstant(CelConstant.ofValue(5));

    CelMutableAst result = AST_MUTATOR.replaceSubtree(mutableAst, newExpr, 3);

    assertThat(CEL_UNPARSER.unparse(result.toParsedAst())).isEqualTo("{5: 1}");
  }

  @Test
  public void map_replaceValue() throws Exception {
    // Tree shape (brackets are expr IDs):
    //        map [1]
    //       map_entry [2]
    //     'a' [3] : 1 [4]
    CelAbstractSyntaxTree ast = CEL.compile("{'a': 1}").getAst();
    CelMutableAst mutableAst = CelMutableAst.fromCelAst(ast);
    CelMutableExpr newExpr = CelMutableExpr.ofConstant(CelConstant.ofValue(5));

    CelMutableAst result = AST_MUTATOR.replaceSubtree(mutableAst, newExpr, 4);

    assertThat(CEL_UNPARSER.unparse(result.toParsedAst())).isEqualTo("{\"a\": 5}");
  }

  @Test
  public void comprehension_replaceIterRange() throws Exception {
    CelAbstractSyntaxTree ast = CEL.compile("[true].exists(i, i)").getAst();
    CelMutableAst mutableAst = CelMutableAst.fromCelAst(ast);
    CelMutableExpr newExpr = CelMutableExpr.ofConstant(CelConstant.ofValue(false));

    CelAbstractSyntaxTree replacedAst =
        AST_MUTATOR.replaceSubtree(mutableAst, newExpr, 2).toParsedAst();

    assertThat(CEL_UNPARSER.unparse(replacedAst)).isEqualTo("[false].exists(i, i)");
    assertThat(CEL.createProgram(CEL.check(replacedAst).getAst()).eval()).isEqualTo(false);
    assertConsistentMacroCalls(replacedAst);
  }

  @Test
  public void comprehension_replaceAccuInit() throws Exception {
    CelAbstractSyntaxTree ast = CEL.compile("[false].exists(i, i)").getAst();
    CelMutableAst mutableAst = CelMutableAst.fromCelAst(ast);
    CelMutableExpr newExpr = CelMutableExpr.ofConstant(CelConstant.ofValue(true));

    CelAbstractSyntaxTree replacedAst =
        AST_MUTATOR.replaceSubtree(mutableAst, newExpr, 6).toParsedAst();

    assertThat(CEL_UNPARSER.unparse(replacedAst)).isEqualTo("[false].exists(i, i)");
    assertThat(CEL.createProgram(CEL.check(replacedAst).getAst()).eval()).isEqualTo(true);
    // Check that the init value of accumulator has actually been replaced.
    assertThat(ast.getExpr().comprehension().accuInit().constant().booleanValue()).isFalse();
    assertThat(replacedAst.getExpr().comprehension().accuInit().constant().booleanValue()).isTrue();
    assertConsistentMacroCalls(ast);
  }

  @Test
  public void comprehension_replaceLoopStep() throws Exception {
    CelAbstractSyntaxTree ast = CEL.compile("[false].exists(i, i)").getAst();
    CelMutableAst mutableAst = CelMutableAst.fromCelAst(ast);
    CelMutableExpr newExpr = CelMutableExpr.ofIdent("test");

    CelAbstractSyntaxTree replacedAst =
        AST_MUTATOR.replaceSubtree(mutableAst, newExpr, 5).toParsedAst();

    assertThat(CEL_UNPARSER.unparse(replacedAst)).isEqualTo("[false].exists(i, test)");
    assertConsistentMacroCalls(ast);
  }

  @Test
  public void mangleComprehensionVariable_singleMacro() throws Exception {
    CelAbstractSyntaxTree ast = CEL.compile("[false].exists(i, i)").getAst();

    CelAbstractSyntaxTree mangledAst =
        AST_MUTATOR
            .mangleComprehensionIdentifierNames(CelMutableAst.fromCelAst(ast), "@it", "@it2", "@ac")
            .mutableAst()
            .toParsedAst();

    assertThat(mangledAst.getExpr().toString())
        .isEqualTo(
            "COMPREHENSION [13] {\n"
                + "  iter_var: @it:0:0\n"
                + "  iter_range: {\n"
                + "    LIST [1] {\n"
                + "      elements: {\n"
                + "        CONSTANT [2] { value: false }\n"
                + "      }\n"
                + "    }\n"
                + "  }\n"
                + "  accu_var: @ac:0:0\n"
                + "  accu_init: {\n"
                + "    CONSTANT [6] { value: false }\n"
                + "  }\n"
                + "  loop_condition: {\n"
                + "    CALL [9] {\n"
                + "      function: @not_strictly_false\n"
                + "      args: {\n"
                + "        CALL [8] {\n"
                + "          function: !_\n"
                + "          args: {\n"
                + "            IDENT [7] {\n"
                + "              name: @ac:0:0\n"
                + "            }\n"
                + "          }\n"
                + "        }\n"
                + "      }\n"
                + "    }\n"
                + "  }\n"
                + "  loop_step: {\n"
                + "    CALL [11] {\n"
                + "      function: _||_\n"
                + "      args: {\n"
                + "        IDENT [10] {\n"
                + "          name: @ac:0:0\n"
                + "        }\n"
                + "        IDENT [5] {\n"
                + "          name: @it:0:0\n"
                + "        }\n"
                + "      }\n"
                + "    }\n"
                + "  }\n"
                + "  result: {\n"
                + "    IDENT [12] {\n"
                + "      name: @ac:0:0\n"
                + "    }\n"
                + "  }\n"
                + "}");
    assertThat(CEL_UNPARSER.unparse(mangledAst)).isEqualTo("[false].exists(@it:0:0, @it:0:0)");
    assertThat(CEL.createProgram(CEL.check(mangledAst).getAst()).eval()).isEqualTo(false);
    assertConsistentMacroCalls(ast);
  }

  @Test
  public void mangleComprehensionVariable_withTwoIterVars_singleMacro() throws Exception {
    CelAbstractSyntaxTree ast = CEL.compile("[false].exists(i, v, v)").getAst();

    CelAbstractSyntaxTree mangledAst =
        AST_MUTATOR
            .mangleComprehensionIdentifierNames(CelMutableAst.fromCelAst(ast), "@it", "@it2", "@ac")
            .mutableAst()
            .toParsedAst();

    assertThat(mangledAst.getExpr().toString())
        .isEqualTo(
            "COMPREHENSION [14] {\n"
                + "  iter_var: @it:0:0\n"
                + "  iter_var2: @it2:0:0\n"
                + "  iter_range: {\n"
                + "    LIST [1] {\n"
                + "      elements: {\n"
                + "        CONSTANT [2] { value: false }\n"
                + "      }\n"
                + "    }\n"
                + "  }\n"
                + "  accu_var: @ac:0:0\n"
                + "  accu_init: {\n"
                + "    CONSTANT [7] { value: false }\n"
                + "  }\n"
                + "  loop_condition: {\n"
                + "    CALL [10] {\n"
                + "      function: @not_strictly_false\n"
                + "      args: {\n"
                + "        CALL [9] {\n"
                + "          function: !_\n"
                + "          args: {\n"
                + "            IDENT [8] {\n"
                + "              name: @ac:0:0\n"
                + "            }\n"
                + "          }\n"
                + "        }\n"
                + "      }\n"
                + "    }\n"
                + "  }\n"
                + "  loop_step: {\n"
                + "    CALL [12] {\n"
                + "      function: _||_\n"
                + "      args: {\n"
                + "        IDENT [11] {\n"
                + "          name: @ac:0:0\n"
                + "        }\n"
                + "        IDENT [6] {\n"
                + "          name: @it2:0:0\n"
                + "        }\n"
                + "      }\n"
                + "    }\n"
                + "  }\n"
                + "  result: {\n"
                + "    IDENT [13] {\n"
                + "      name: @ac:0:0\n"
                + "    }\n"
                + "  }\n"
                + "}");
    assertThat(CEL_UNPARSER.unparse(mangledAst))
        .isEqualTo("[false].exists(@it:0:0, @it2:0:0, @it2:0:0)");
    assertThat(CEL.createProgram(CEL.check(mangledAst).getAst()).eval()).isEqualTo(false);
    assertConsistentMacroCalls(ast);
  }

  @Test
  public void mangleComprehensionVariable_adjacentMacros_sameIterVarTypes() throws Exception {
    CelAbstractSyntaxTree ast =
        CEL.compile(
                "[1,2,3].map(i, [1, 2, 3].map(i, i + 1)) == [1,2,3].map(j, [1, 2, 3].map(j, j +"
                    + " 1))")
            .getAst();

    CelAbstractSyntaxTree mangledAst =
        AST_MUTATOR
            .mangleComprehensionIdentifierNames(CelMutableAst.fromCelAst(ast), "@it", "@it2", "@ac")
            .mutableAst()
            .toParsedAst();

    assertThat(CEL_UNPARSER.unparse(mangledAst))
        .isEqualTo(
            "[1, 2, 3].map(@it:1:0, [1, 2, 3].map(@it:0:0, @it:0:0 + 1)) == "
                + "[1, 2, 3].map(@it:1:0, [1, 2, 3].map(@it:0:0, @it:0:0 + 1))");
    assertThat(CEL.createProgram(CEL.check(mangledAst).getAst()).eval()).isEqualTo(true);
    assertConsistentMacroCalls(ast);
  }

  @Test
  public void mangleComprehensionVariable_withTwoIterVars_adjacentMacros_sameIterVarTypes()
      throws Exception {
    CelAbstractSyntaxTree ast =
        CEL.compile(
                // LHS & RHS are same expressions, just different var names
                "[1, 2].transformMap(i, v, [1,2].transformMap(i, v, i)) == "
                    + "[1, 2].transformMap(x, y, [1,2].transformMap(x, y, x))")
            .getAst();

    CelAbstractSyntaxTree mangledAst =
        AST_MUTATOR
            .mangleComprehensionIdentifierNames(CelMutableAst.fromCelAst(ast), "@it", "@it2", "@ac")
            .mutableAst()
            .toParsedAst();

    assertThat(CEL_UNPARSER.unparse(mangledAst))
        .isEqualTo(
            "[1, 2].transformMap(@it:1:0, @it2:1:0, [1, 2].transformMap(@it:0:0, @it2:0:0,"
                + " @it:0:0)) == [1, 2].transformMap(@it:1:0, @it2:1:0, [1,"
                + " 2].transformMap(@it:0:0, @it2:0:0, @it:0:0))");
    assertThat(CEL.createProgram(CEL.check(mangledAst).getAst()).eval()).isEqualTo(true);
    assertConsistentMacroCalls(ast);
  }

  @Test
  public void mangleComprehensionVariable_adjacentMacros_differentIterVarTypes() throws Exception {
    CelAbstractSyntaxTree ast =
        CEL.compile(
                "[1,2,3].map(i, [1, 2, 3].map(i, i)) == dyn([1u,2u,3u].map(j, [1u, 2u, 3u].map(j,"
                    + " j)))")
            .getAst();

    CelAbstractSyntaxTree mangledAst =
        AST_MUTATOR
            .mangleComprehensionIdentifierNames(CelMutableAst.fromCelAst(ast), "@it", "@it2", "@ac")
            .mutableAst()
            .toParsedAst();

    assertThat(CEL_UNPARSER.unparse(mangledAst))
        .isEqualTo(
            "[1, 2, 3].map(@it:1:0, [1, 2, 3].map(@it:0:0, @it:0:0)) == "
                + "dyn([1u, 2u, 3u].map(@it:1:1, [1u, 2u, 3u].map(@it:0:1, @it:0:1)))");
    assertThat(CEL.createProgram(CEL.check(mangledAst).getAst()).eval()).isEqualTo(true);
    assertConsistentMacroCalls(ast);
  }

  @Test
  public void mangleComprehensionVariable_macroSourceDisabled_macroCallMapIsEmpty()
      throws Exception {
    Cel cel =
        CelFactory.plannerCelBuilder()
            .setStandardMacros(CelStandardMacro.STANDARD_MACROS)
            .setOptions(
                CelOptions.current()
                    .populateMacroCalls(false)
                    .enableHeterogeneousNumericComparisons(true)
                    .build())
            .build();
    CelAbstractSyntaxTree ast = cel.compile("[false].exists(i, i)").getAst();

    CelAbstractSyntaxTree mangledAst =
        AST_MUTATOR
            .mangleComprehensionIdentifierNames(CelMutableAst.fromCelAst(ast), "@it", "@it2", "@ac")
            .mutableAst()
            .toParsedAst();

    assertThat(mangledAst.getSource().getMacroCalls()).isEmpty();
  }

  @Test
  public void mangleComprehensionVariable_nestedMacroWithShadowedVariables() throws Exception {
    CelAbstractSyntaxTree ast = CEL.compile("[x].exists(x, [x].exists(x, x == 1))").getAst();

    CelAbstractSyntaxTree mangledAst =
        AST_MUTATOR
            .mangleComprehensionIdentifierNames(CelMutableAst.fromCelAst(ast), "@it", "@it2", "@ac")
            .mutableAst()
            .toParsedAst();

    assertThat(mangledAst.getExpr().toString())
        .isEqualTo(
            "COMPREHENSION [27] {\n"
                + "  iter_var: @it:1:0\n"
                + "  iter_range: {\n"
                + "    LIST [1] {\n"
                + "      elements: {\n"
                + "        IDENT [2] {\n"
                + "          name: x\n"
                + "        }\n"
                + "      }\n"
                + "    }\n"
                + "  }\n"
                + "  accu_var: @ac:1:0\n"
                + "  accu_init: {\n"
                + "    CONSTANT [20] { value: false }\n"
                + "  }\n"
                + "  loop_condition: {\n"
                + "    CALL [23] {\n"
                + "      function: @not_strictly_false\n"
                + "      args: {\n"
                + "        CALL [22] {\n"
                + "          function: !_\n"
                + "          args: {\n"
                + "            IDENT [21] {\n"
                + "              name: @ac:1:0\n"
                + "            }\n"
                + "          }\n"
                + "        }\n"
                + "      }\n"
                + "    }\n"
                + "  }\n"
                + "  loop_step: {\n"
                + "    CALL [25] {\n"
                + "      function: _||_\n"
                + "      args: {\n"
                + "        IDENT [24] {\n"
                + "          name: @ac:1:0\n"
                + "        }\n"
                + "        COMPREHENSION [19] {\n"
                + "          iter_var: @it:0:0\n"
                + "          iter_range: {\n"
                + "            LIST [5] {\n"
                + "              elements: {\n"
                + "                IDENT [6] {\n"
                + "                  name: @it:1:0\n"
                + "                }\n"
                + "              }\n"
                + "            }\n"
                + "          }\n"
                + "          accu_var: @ac:0:0\n"
                + "          accu_init: {\n"
                + "            CONSTANT [12] { value: false }\n"
                + "          }\n"
                + "          loop_condition: {\n"
                + "            CALL [15] {\n"
                + "              function: @not_strictly_false\n"
                + "              args: {\n"
                + "                CALL [14] {\n"
                + "                  function: !_\n"
                + "                  args: {\n"
                + "                    IDENT [13] {\n"
                + "                      name: @ac:0:0\n"
                + "                    }\n"
                + "                  }\n"
                + "                }\n"
                + "              }\n"
                + "            }\n"
                + "          }\n"
                + "          loop_step: {\n"
                + "            CALL [17] {\n"
                + "              function: _||_\n"
                + "              args: {\n"
                + "                IDENT [16] {\n"
                + "                  name: @ac:0:0\n"
                + "                }\n"
                + "                CALL [10] {\n"
                + "                  function: _==_\n"
                + "                  args: {\n"
                + "                    IDENT [9] {\n"
                + "                      name: @it:0:0\n"
                + "                    }\n"
                + "                    CONSTANT [11] { value: 1 }\n"
                + "                  }\n"
                + "                }\n"
                + "              }\n"
                + "            }\n"
                + "          }\n"
                + "          result: {\n"
                + "            IDENT [18] {\n"
                + "              name: @ac:0:0\n"
                + "            }\n"
                + "          }\n"
                + "        }\n"
                + "      }\n"
                + "    }\n"
                + "  }\n"
                + "  result: {\n"
                + "    IDENT [26] {\n"
                + "      name: @ac:1:0\n"
                + "    }\n"
                + "  }\n"
                + "}");
    assertThat(CEL_UNPARSER.unparse(mangledAst))
        .isEqualTo("[x].exists(@it:1:0, [@it:1:0].exists(@it:0:0, @it:0:0 == 1))");
    assertThat(CEL.createProgram(CEL.check(mangledAst).getAst()).eval(ImmutableMap.of("x", 1)))
        .isEqualTo(true);
    assertConsistentMacroCalls(ast);
  }

  @Test
  public void mangleComprehensionVariable_hasMacro_noOp() throws Exception {
    CelAbstractSyntaxTree ast = CEL.compile("has(msg.single_int64)").getAst();

    CelAbstractSyntaxTree mangledAst =
        AST_MUTATOR
            .mangleComprehensionIdentifierNames(CelMutableAst.fromCelAst(ast), "@it", "@it2", "@ac")
            .mutableAst()
            .toParsedAst();

    assertThat(CEL_UNPARSER.unparse(mangledAst)).isEqualTo("has(msg.single_int64)");
    assertThat(
            CEL.createProgram(CEL.check(mangledAst).getAst())
                .eval(ImmutableMap.of("msg", TestAllTypes.getDefaultInstance())))
        .isEqualTo(false);
    assertConsistentMacroCalls(ast);
  }

  @Test
  public void replaceSubtree_iterationLimitReached_throws() throws Exception {
    CelAbstractSyntaxTree ast = CEL.compile("true && false").getAst();
    CelMutableAst mutableAst = CelMutableAst.fromCelAst(ast);
    AstMutator astMutator = AstMutator.newInstance(1);

    IllegalStateException e =
        assertThrows(
            IllegalStateException.class,
            () ->
                astMutator.replaceSubtree(
                    mutableAst, CelMutableExpr.ofConstant(CelConstant.ofValue(false)), 1));

    assertThat(e).hasMessageThat().isEqualTo("Max iteration count reached.");
  }

  @Test
  public void newGlobalCallAst_success() throws Exception {
    CelMutableAst argAst1 = CelMutableAst.fromCelAst(CEL.compile("[1].exists(x, x >= 1)").getAst());
    CelMutableAst argAst2 = CelMutableAst.fromCelAst(CEL.compile("'hello'").getAst());

    CelMutableAst callAst = AST_MUTATOR.newGlobalCall("func", argAst1, argAst2);

    assertThat(CEL_UNPARSER.unparse(callAst.toParsedAst()))
        .isEqualTo("func([1].exists(x, x >= 1), \"hello\")");
  }

  @Test
  public void replaceSubtree_withSubtreeReplacement_expr() throws Exception {
    CelAbstractSyntaxTree ast = CEL.compile("1 + 2").getAst();
    CelMutableAst mutableAst = CelMutableAst.fromCelAst(ast);
    SubtreeReplacement replacement =
        SubtreeReplacement.of(1, CelMutableExpr.ofConstant(CelConstant.ofValue(10)));

    CelMutableAst result = AST_MUTATOR.replaceSubtree(mutableAst, replacement);

    assertThat(CEL_UNPARSER.unparse(result.toParsedAst())).isEqualTo("10 + 2");
  }

  @Test
  public void replaceSubtree_withSubtreeReplacement_ast() throws Exception {
    CelAbstractSyntaxTree ast = CEL.compile("true && false").getAst();
    CelAbstractSyntaxTree macroAst = CEL.compile("[1].exists(x, x > 0)").getAst();
    CelMutableAst mutableAst = CelMutableAst.fromCelAst(ast);
    SubtreeReplacement replacement = SubtreeReplacement.of(3, CelMutableAst.fromCelAst(macroAst));

    CelMutableAst result = AST_MUTATOR.replaceSubtree(mutableAst, replacement);

    assertThat(CEL_UNPARSER.unparse(result.toParsedAst()))
        .isEqualTo("true && [1].exists(x, x > 0)");
    assertThat(result.source().getMacroCalls()).hasSize(1);
  }

  @Test
  public void mutateUntilFixedPoint_astRewriter_success() throws Exception {
    // Repeatedly simplifies addition with 0: "1 + 0 + 0" -> "1"
    CelAbstractSyntaxTree ast = CEL.compile("1 + 0 + 0").getAst();
    CelMutableAst mutableAst = CelMutableAst.fromCelAst(ast);

    CelMutableAst result =
        AST_MUTATOR.mutateUntilFixedPoint(
            mutableAst,
            navAst ->
                navAst
                    .getRoot()
                    .allNodes()
                    .filter(
                        node ->
                            node.getKind().equals(Kind.CALL)
                                && node.expr().call().function().equals("_+_"))
                    .filter(
                        node -> {
                          List<CelMutableExpr> args = node.expr().call().args();
                          return args.get(1).getKind().equals(Kind.CONSTANT)
                              && args.get(1).constant().int64Value() == 0;
                        })
                    .map(node -> SubtreeReplacement.of(node.id(), node.expr().call().args().get(0)))
                    .findFirst());

    assertThat(CEL_UNPARSER.unparse(result.toParsedAst())).isEqualTo("1");
  }

  @Test
  public void mutateUntilFixedPoint_nodeRewriter_success() throws Exception {
    // Rewrites nested calls: "func(func(1))" -> "1"
    Cel cel =
        CelFactory.plannerCelBuilder()
            .addFunctionDeclarations(
                CelFunctionDecl.newFunctionDeclaration(
                    "func",
                    CelOverloadDecl.newGlobalOverload(
                        "func_overload", SimpleType.INT, SimpleType.INT)))
            .build();
    CelAbstractSyntaxTree ast = cel.compile("func(func(10))").getAst();
    CelMutableAst mutableAst = CelMutableAst.fromCelAst(ast);

    CelMutableAst result =
        AST_MUTATOR.mutateUntilFixedPoint(
            mutableAst,
            TraversalOrder.POST_ORDER,
            node -> {
              if (node.getKind().equals(Kind.CALL)
                  && node.expr().call().function().equals("func")) {
                return Optional.of(
                    SubtreeReplacement.of(node.id(), node.expr().call().args().get(0)));
              }
              return Optional.empty();
            });

    assertThat(CEL_UNPARSER.unparse(result.toParsedAst())).isEqualTo("10");
  }

  @Test
  public void mutateUntilFixedPoint_matcherAndNodeRewriter_success() throws Exception {
    // Replaces all variables named 'x' with constant 5 in "x + x + x" -> "5 + 5 + 5"
    CelAbstractSyntaxTree ast = CEL.compile("x + x + x").getAst();
    CelMutableAst mutableAst = CelMutableAst.fromCelAst(ast);

    CelMutableAst result =
        AST_MUTATOR.mutateUntilFixedPoint(
            mutableAst,
            TraversalOrder.POST_ORDER,
            node -> node.getKind().equals(Kind.IDENT) && node.expr().ident().name().equals("x"),
            node -> Optional.of(CelMutableExpr.ofConstant(CelConstant.ofValue(5))));

    assertThat(CEL_UNPARSER.unparse(result.toParsedAst())).isEqualTo("5 + 5 + 5");
  }

  @Test
  public void mutateUntilFixedPoint_withReplacementAst_preservesMacroSource() throws Exception {
    // Replaces identifier 'b' with macro AST in "b && true"
    CelAbstractSyntaxTree ast = CEL.compile("b && true").getAst();
    CelAbstractSyntaxTree macroAst = CEL.compile("[1].exists(i, i > 0)").getAst();
    CelMutableAst mutableAst = CelMutableAst.fromCelAst(ast);

    CelMutableAst result =
        AST_MUTATOR.mutateUntilFixedPoint(
            mutableAst,
            navAst ->
                navAst
                    .getRoot()
                    .allNodes()
                    .filter(
                        node ->
                            node.getKind().equals(Kind.IDENT)
                                && node.expr().ident().name().equals("b"))
                    .map(
                        node ->
                            SubtreeReplacement.of(node.id(), CelMutableAst.fromCelAst(macroAst)))
                    .findFirst());

    assertThat(CEL_UNPARSER.unparse(result.toParsedAst()))
        .isEqualTo("[1].exists(i, i > 0) && true");
    assertThat(result.source().getMacroCalls()).hasSize(1);
    assertThat(CEL.createProgram(CEL.check(result.toParsedAst()).getAst()).eval()).isEqualTo(true);
  }

  @Test
  public void mutateUntilFixedPoint_exceedsIterationLimit_throws() throws Exception {
    // Circular rewrite rule that alternates between 1 and 2
    CelAbstractSyntaxTree ast = CEL.compile("1").getAst();
    CelMutableAst mutableAst = CelMutableAst.fromCelAst(ast);

    IllegalStateException e =
        assertThrows(
            IllegalStateException.class,
            () ->
                AST_MUTATOR.mutateUntilFixedPoint(
                    mutableAst,
                    TraversalOrder.POST_ORDER,
                    node -> node.getKind().equals(Kind.CONSTANT),
                    node -> {
                      long currentVal = node.expr().constant().int64Value();
                      long nextVal = currentVal == 1L ? 2L : 1L;
                      return Optional.of(CelMutableExpr.ofConstant(CelConstant.ofValue(nextVal)));
                    }));

    assertThat(e).hasMessageThat().isEqualTo("Max iteration count reached.");
  }

  @Test
  public void newMemberCallAst_success() throws Exception {
    CelMutableAst targetAst = CelMutableAst.fromCelAst(CEL.compile("'hello'").getAst());
    CelMutableAst argAst1 = CelMutableAst.fromCelAst(CEL.compile("[1].exists(x, x >= 1)").getAst());
    CelMutableAst argAst2 = CelMutableAst.fromCelAst(CEL.compile("'world'").getAst());

    CelMutableAst callAst = AST_MUTATOR.newMemberCall(targetAst, "func", argAst1, argAst2);

    assertThat(CEL_UNPARSER.unparse(callAst.toParsedAst()))
        .isEqualTo("\"hello\".func([1].exists(x, x >= 1), \"world\")");
  }

  /**
   * Asserts that the expressions that appears in source_info's macro calls are consistent with the
   * actual expr nodes in the AST.
   */
  private void assertConsistentMacroCalls(CelAbstractSyntaxTree ast) {
    assertThat(ast.getSource().getMacroCalls()).isNotEmpty();
    ImmutableMap<Long, CelExpr> allExprs =
        CelNavigableAst.fromAst(ast)
            .getRoot()
            .allNodes()
            .map(CelNavigableExpr::expr)
            .collect(toImmutableMap(CelExpr::id, node -> node, (expr1, expr2) -> expr1));
    for (CelExpr macroCall : ast.getSource().getMacroCalls().values()) {
      assertThat(macroCall.id()).isEqualTo(0);
      CelNavigableExpr.fromExpr(macroCall)
          .descendants()
          .map(CelNavigableExpr::expr)
          .forEach(
              node -> {
                CelExpr e = allExprs.get(node.id());
                if (e != null) {
                  assertThat(node.id()).isEqualTo(e.id());
                  if (e.exprKind().getKind().equals(Kind.COMPREHENSION)) {
                    assertThat(node.exprKind().getKind()).isEqualTo(Kind.NOT_SET);
                  } else {
                    assertThat(node.exprKind().getKind()).isEqualTo(e.exprKind().getKind());
                  }
                }
              });
    }
  }
}
