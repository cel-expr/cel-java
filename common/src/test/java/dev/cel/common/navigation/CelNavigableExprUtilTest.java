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

package dev.cel.common.navigation;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.CelMutableAst;
import dev.cel.common.ast.CelConstant;
import dev.cel.common.ast.CelExpr.ExprKind.Kind;
import dev.cel.common.ast.CelMutableExpr;
import dev.cel.common.ast.CelMutableExpr.CelMutableCall;
import dev.cel.common.ast.CelMutableExpr.CelMutableComprehension;
import dev.cel.common.ast.CelMutableExpr.CelMutableList;
import dev.cel.common.types.SimpleType;
import dev.cel.compiler.CelCompiler;
import dev.cel.compiler.CelCompilerFactory;
import dev.cel.extensions.CelExtensions;
import dev.cel.parser.CelStandardMacro;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class CelNavigableExprUtilTest {

  private static final CelCompiler COMPILER =
      CelCompilerFactory.standardCelCompilerBuilder()
          .setStandardMacros(CelStandardMacro.STANDARD_MACROS)
          .addLibraries(CelExtensions.comprehensions())
          .addVar("a", SimpleType.INT)
          .addVar("b", SimpleType.INT)
          .build();

  @Test
  public void isVariableShadowed_singleVarComprehension_loopStep() throws Exception {
    CelAbstractSyntaxTree ast = COMPILER.compile("[1, 2].all(x, x > 0)").getAst();
    CelNavigableAst navigableAst = CelNavigableAst.fromAst(ast);

    CelNavigableExpr identX =
        navigableAst
            .getRoot()
            .allNodes()
            .filter(node -> node.expr().identOrDefault().name().equals("x"))
            .findFirst()
            .get();

    assertThat(CelNavigableExprUtil.isVariableShadowed(identX, "x")).isTrue();
    assertThat(CelNavigableExprUtil.isVariableShadowed(identX, "y")).isFalse();
  }

  @Test
  public void isVariableShadowed_twoVarComprehension_loopStep() throws Exception {
    CelAbstractSyntaxTree ast =
        COMPILER.compile("{'k1': 1, 'k2': 2}.all(k, v, k != '' && v > 0)").getAst();
    CelNavigableAst navigableAst = CelNavigableAst.fromAst(ast);

    CelNavigableExpr identK =
        navigableAst
            .getRoot()
            .allNodes()
            .filter(node -> node.expr().identOrDefault().name().equals("k"))
            .findFirst()
            .get();
    CelNavigableExpr identV =
        navigableAst
            .getRoot()
            .allNodes()
            .filter(node -> node.expr().identOrDefault().name().equals("v"))
            .findFirst()
            .get();
    CelNavigableExpr iterRangeMap =
        navigableAst
            .getRoot()
            .allNodes()
            .filter(node -> node.getKind() == Kind.MAP)
            .findFirst()
            .get();

    assertThat(CelNavigableExprUtil.isVariableShadowed(identK, "k")).isTrue();
    assertThat(CelNavigableExprUtil.isVariableShadowed(identK, "v")).isTrue();
    assertThat(CelNavigableExprUtil.isVariableShadowed(identV, "k")).isTrue();
    assertThat(CelNavigableExprUtil.isVariableShadowed(identV, "v")).isTrue();
    assertThat(CelNavigableExprUtil.isVariableShadowed(identK, "other")).isFalse();

    assertThat(CelNavigableExprUtil.isVariableShadowed(iterRangeMap, "k")).isFalse();
    assertThat(CelNavigableExprUtil.isVariableShadowed(iterRangeMap, "v")).isFalse();
  }

  @Test
  public void isVariableShadowed_iterRange_notShadowed() throws Exception {
    CelAbstractSyntaxTree ast = COMPILER.compile("[a].all(x, x > 0)").getAst();
    CelNavigableAst navigableAst = CelNavigableAst.fromAst(ast);

    CelNavigableExpr identA =
        navigableAst
            .getRoot()
            .allNodes()
            .filter(node -> node.expr().identOrDefault().name().equals("a"))
            .findFirst()
            .get();

    assertThat(CelNavigableExprUtil.isVariableShadowed(identA, "x")).isFalse();
    assertThat(CelNavigableExprUtil.isVariableShadowed(identA, "a")).isFalse();
  }

  @Test
  public void isVariableShadowed_nestedComprehension_scopedCorrectly() throws Exception {
    CelAbstractSyntaxTree ast =
        COMPILER.compile("[1, 2].all(x, [3, 4].all(y, x > 0 && y > 0))").getAst();
    CelNavigableAst navigableAst = CelNavigableAst.fromAst(ast);

    CelNavigableExpr innerIdentX =
        navigableAst
            .getRoot()
            .allNodes()
            .filter(node -> node.expr().identOrDefault().name().equals("x"))
            .findFirst()
            .get();
    CelNavigableExpr innerIdentY =
        navigableAst
            .getRoot()
            .allNodes()
            .filter(node -> node.expr().identOrDefault().name().equals("y"))
            .findFirst()
            .get();

    assertThat(CelNavigableExprUtil.isVariableShadowed(innerIdentX, "x")).isTrue();
    assertThat(CelNavigableExprUtil.isVariableShadowed(innerIdentX, "y")).isTrue();
    assertThat(CelNavigableExprUtil.isVariableShadowed(innerIdentY, "x")).isTrue();
    assertThat(CelNavigableExprUtil.isVariableShadowed(innerIdentY, "y")).isTrue();
  }

  @Test
  public void isVariableShadowed_nestedComprehension_innerIterRangeShadowsOuterOnly()
      throws Exception {
    CelAbstractSyntaxTree ast = COMPILER.compile("[1, 2].all(x, [x].all(y, y > 0))").getAst();
    CelNavigableAst navigableAst = CelNavigableAst.fromAst(ast);

    CelNavigableExpr innerIterRangeIdentX =
        navigableAst
            .getRoot()
            .allNodes()
            .filter(
                node ->
                    node.expr().identOrDefault().name().equals("x")
                        && node.parent().isPresent()
                        && node.parent().get().getKind() == Kind.LIST)
            .findFirst()
            .get();

    // In the inner comprehension's iterRange, outer 'x' IS in scope, but inner 'y' is NOT in scope
    assertThat(CelNavigableExprUtil.isVariableShadowed(innerIterRangeIdentX, "x")).isTrue();
    assertThat(CelNavigableExprUtil.isVariableShadowed(innerIterRangeIdentX, "y")).isFalse();
  }

  @Test
  public void isVariableShadowed_comprehensionResultBranch() throws Exception {
    CelAbstractSyntaxTree ast = COMPILER.compile("[1, 2].all(x, x > 0)").getAst();
    CelNavigableMutableAst navigableAst =
        CelNavigableMutableAst.fromAst(CelMutableAst.fromCelAst(ast));

    CelNavigableMutableExpr comprehensionNode =
        navigableAst
            .getRoot()
            .allNodes()
            .filter(node -> node.getKind() == Kind.COMPREHENSION)
            .findFirst()
            .get();

    CelMutableComprehension comprehension = comprehensionNode.expr().comprehension();
    long resultId = comprehension.result().id();

    CelNavigableMutableExpr resultNode =
        comprehensionNode.allNodes().filter(node -> node.id() == resultId).findFirst().get();

    // In result branch, accuVar is in scope, but iterVar is not
    assertThat(CelNavigableExprUtil.isVariableShadowed(resultNode, comprehension.accuVar()))
        .isTrue();
    assertThat(CelNavigableExprUtil.isVariableShadowed(resultNode, comprehension.iterVar()))
        .isFalse();
  }

  @Test
  public void isVariableShadowed_twoVarComprehension_resultBranch() throws Exception {
    CelAbstractSyntaxTree ast =
        COMPILER.compile("{'k1': 1, 'k2': 2}.all(k, v, k != '' && v > 0)").getAst();
    CelNavigableMutableAst navigableAst =
        CelNavigableMutableAst.fromAst(CelMutableAst.fromCelAst(ast));

    CelNavigableMutableExpr comprehensionNode =
        navigableAst
            .getRoot()
            .allNodes()
            .filter(node -> node.getKind() == Kind.COMPREHENSION)
            .findFirst()
            .get();

    CelMutableComprehension comprehension = comprehensionNode.expr().comprehension();
    long resultId = comprehension.result().id();

    CelNavigableMutableExpr resultNode =
        comprehensionNode.allNodes().filter(node -> node.id() == resultId).findFirst().get();

    // In result branch of two-var comprehension: accuVar is in scope, but iterVar and iterVar2 are
    // not
    assertThat(CelNavigableExprUtil.isVariableShadowed(resultNode, comprehension.accuVar()))
        .isTrue();
    assertThat(CelNavigableExprUtil.isVariableShadowed(resultNode, comprehension.iterVar()))
        .isFalse();
    assertThat(CelNavigableExprUtil.isVariableShadowed(resultNode, comprehension.iterVar2()))
        .isFalse();
  }

  @Test
  public void isVariableShadowed_accuInit_notShadowed() {
    CelMutableExpr iterRange = CelMutableExpr.ofList(0, CelMutableList.create());
    CelMutableExpr accuInitIdent = CelMutableExpr.ofIdent(1, "x");
    CelMutableExpr loopCond = CelMutableExpr.ofConstant(2, CelConstant.ofValue(true));
    CelMutableExpr loopStep = CelMutableExpr.ofConstant(3, CelConstant.ofValue(true));
    CelMutableExpr result = CelMutableExpr.ofIdent(4, "accu");

    CelMutableExpr comp =
        CelMutableExpr.ofComprehension(
            5,
            CelMutableComprehension.create(
                "x", iterRange, "accu", accuInitIdent, loopCond, loopStep, result));

    CelNavigableMutableExpr root = CelNavigableMutableExpr.fromExpr(comp);
    CelNavigableMutableExpr navAccuInit =
        root.allNodes().filter(node -> node.id() == 1).findFirst().get();

    assertThat(CelNavigableExprUtil.isVariableShadowed(navAccuInit, "x")).isFalse();
    assertThat(CelNavigableExprUtil.isVariableShadowed(navAccuInit, "accu")).isFalse();
  }

  @Test
  public void findDeclaringComprehension_emptyVariableName_returnsEmpty() throws Exception {
    CelAbstractSyntaxTree ast = COMPILER.compile("[1, 2].all(x, x > 0)").getAst();
    CelNavigableAst navigableAst = CelNavigableAst.fromAst(ast);

    CelNavigableExpr identX =
        navigableAst
            .getRoot()
            .allNodes()
            .filter(node -> node.expr().identOrDefault().name().equals("x"))
            .findFirst()
            .get();

    assertThat(CelNavigableExprUtil.findDeclaringComprehension(identX, "")).isEmpty();
    assertThat(CelNavigableExprUtil.isVariableShadowed(identX, "")).isFalse();
  }

  @Test
  public void areVariablesShadowed_multipleVariables() throws Exception {
    CelAbstractSyntaxTree ast = COMPILER.compile("[1, 2].all(x, x > 0)").getAst();
    CelNavigableAst navigableAst = CelNavigableAst.fromAst(ast);

    CelNavigableExpr identX =
        navigableAst
            .getRoot()
            .allNodes()
            .filter(node -> node.expr().identOrDefault().name().equals("x"))
            .findFirst()
            .get();

    assertThat(CelNavigableExprUtil.areVariablesShadowed(identX, ImmutableSet.of("y", "z", "x")))
        .isTrue();
    assertThat(CelNavigableExprUtil.areVariablesShadowed(identX, ImmutableSet.of("y", "z")))
        .isFalse();
    assertThat(CelNavigableExprUtil.areVariablesShadowed(identX, ImmutableList.of())).isFalse();
  }

  @Test
  public void isComprehensionVariable_identNode() throws Exception {
    CelAbstractSyntaxTree ast = COMPILER.compile("[a].all(x, x > a)").getAst();
    CelNavigableAst navigableAst = CelNavigableAst.fromAst(ast);

    CelNavigableExpr identX =
        navigableAst
            .getRoot()
            .allNodes()
            .filter(node -> node.expr().identOrDefault().name().equals("x"))
            .findFirst()
            .get();
    CelNavigableExpr identAInLoop =
        navigableAst
            .getRoot()
            .allNodes()
            .filter(
                node ->
                    node.expr().identOrDefault().name().equals("a")
                        && node.parent().isPresent()
                        && node.parent().get().getKind() == Kind.CALL)
            .findFirst()
            .get();
    CelNavigableExpr constNode =
        navigableAst
            .getRoot()
            .allNodes()
            .filter(node -> node.getKind() == Kind.CONSTANT)
            .findFirst()
            .get();

    assertThat(CelNavigableExprUtil.isComprehensionVariable(identX)).isTrue();
    assertThat(CelNavigableExprUtil.isComprehensionVariable(identAInLoop)).isFalse();
    assertThat(CelNavigableExprUtil.isComprehensionVariable(constNode)).isFalse();
  }

  @Test
  public void hasComprehensionVariable_subtreeCheck() throws Exception {
    CelAbstractSyntaxTree ast = COMPILER.compile("[a].all(x, x > a)").getAst();
    CelNavigableAst navigableAst = CelNavigableAst.fromAst(ast);

    CelNavigableExpr root = navigableAst.getRoot();
    CelNavigableExpr iterRange =
        navigableAst
            .getRoot()
            .allNodes()
            .filter(node -> node.getKind() == Kind.LIST)
            .findFirst()
            .get();
    CelNavigableExpr loopStepCall =
        navigableAst
            .getRoot()
            .allNodes()
            .filter(node -> node.expr().callOrDefault().function().equals("@not_strictly_false"))
            .findFirst()
            .get();

    assertThat(CelNavigableExprUtil.hasComprehensionVariable(root)).isTrue();
    assertThat(CelNavigableExprUtil.hasComprehensionVariable(loopStepCall)).isTrue();
    assertThat(CelNavigableExprUtil.hasComprehensionVariable(iterRange)).isFalse();
  }

  @Test
  public void mutableAst_parityWithImmutableAst() throws Exception {
    CelAbstractSyntaxTree ast = COMPILER.compile("[1, 2].all(x, x > 0)").getAst();
    CelNavigableAst immutableNavAst = CelNavigableAst.fromAst(ast);
    CelNavigableMutableAst mutableNavAst =
        CelNavigableMutableAst.fromAst(CelMutableAst.fromCelAst(ast));

    CelNavigableExpr immutableIdentX =
        immutableNavAst
            .getRoot()
            .allNodes()
            .filter(node -> node.expr().identOrDefault().name().equals("x"))
            .findFirst()
            .get();
    CelNavigableMutableExpr mutableIdentX =
        mutableNavAst
            .getRoot()
            .allNodes()
            .filter(node -> node.getKind() == Kind.IDENT && node.expr().ident().name().equals("x"))
            .findFirst()
            .get();

    assertThat(CelNavigableExprUtil.isVariableShadowed(mutableIdentX, "x"))
        .isEqualTo(CelNavigableExprUtil.isVariableShadowed(immutableIdentX, "x"));
    assertThat(CelNavigableExprUtil.isComprehensionVariable(mutableIdentX))
        .isEqualTo(CelNavigableExprUtil.isComprehensionVariable(immutableIdentX));
    assertThat(CelNavigableExprUtil.hasComprehensionVariable(mutableNavAst.getRoot()))
        .isEqualTo(CelNavigableExprUtil.hasComprehensionVariable(immutableNavAst.getRoot()));
  }

  @Test
  public void isVariableShadowed_zeroedOutIds_scopedCorrectly() {
    // Construct a mutable comprehension where ALL expression IDs are 0 (e.g. freshly minted AST)
    CelMutableExpr iterRange = CelMutableExpr.ofList(0, CelMutableList.create());
    CelMutableExpr accuInit = CelMutableExpr.ofConstant(0, CelConstant.ofValue(true));
    CelMutableExpr loopCond = CelMutableExpr.ofConstant(0, CelConstant.ofValue(true));
    CelMutableExpr identX = CelMutableExpr.ofIdent(0, "x");
    CelMutableExpr loopStep = CelMutableExpr.ofCall(0, CelMutableCall.create("!_", identX));
    CelMutableExpr result = CelMutableExpr.ofIdent(0, "accu");

    CelMutableExpr comp =
        CelMutableExpr.ofComprehension(
            0,
            CelMutableComprehension.create(
                "x", iterRange, "accu", accuInit, loopCond, loopStep, result));

    CelNavigableMutableExpr root = CelNavigableMutableExpr.fromExpr(comp);

    CelNavigableMutableExpr navIdentX =
        root.allNodes()
            .filter(node -> node.getKind() == Kind.IDENT && node.expr().ident().name().equals("x"))
            .findFirst()
            .get();
    CelNavigableMutableExpr navIterRange =
        root.allNodes().filter(node -> node.getKind() == Kind.LIST).findFirst().get();
    CelNavigableMutableExpr navResult =
        root.allNodes()
            .filter(
                node -> node.getKind() == Kind.IDENT && node.expr().ident().name().equals("accu"))
            .findFirst()
            .get();

    assertThat(CelNavigableExprUtil.isVariableShadowed(navIdentX, "x")).isTrue();
    assertThat(CelNavigableExprUtil.isVariableShadowed(navIdentX, "accu")).isTrue();
    assertThat(CelNavigableExprUtil.isVariableShadowed(navIterRange, "x")).isFalse();
    assertThat(CelNavigableExprUtil.isVariableShadowed(navResult, "x")).isFalse();
    assertThat(CelNavigableExprUtil.isVariableShadowed(navResult, "accu")).isTrue();
  }

  @Test
  public void
      findDeclaringComprehension_nestedComprehensions_resolvesToInnermostDeclaringComprehension()
          throws Exception {
    CelAbstractSyntaxTree ast =
        COMPILER
            .compile("[1, 2].all(x, {'k': 1}.exists(k, v, x > 0 && k != '' && v > 0))")
            .getAst();
    CelNavigableAst navigableAst = CelNavigableAst.fromAst(ast);

    CelNavigableExpr outerComp =
        navigableAst
            .getRoot()
            .allNodes()
            .filter(
                node ->
                    node.getKind() == Kind.COMPREHENSION
                        && node.expr().comprehension().iterVar().equals("x"))
            .findFirst()
            .get();

    CelNavigableExpr innerComp =
        navigableAst
            .getRoot()
            .allNodes()
            .filter(
                node ->
                    node.getKind() == Kind.COMPREHENSION
                        && node.expr().comprehension().iterVar().equals("k"))
            .findFirst()
            .get();

    CelNavigableExpr identX =
        navigableAst
            .getRoot()
            .allNodes()
            .filter(node -> node.expr().identOrDefault().name().equals("x"))
            .findFirst()
            .get();
    CelNavigableExpr identK =
        navigableAst
            .getRoot()
            .allNodes()
            .filter(node -> node.expr().identOrDefault().name().equals("k"))
            .findFirst()
            .get();
    CelNavigableExpr identV =
        navigableAst
            .getRoot()
            .allNodes()
            .filter(node -> node.expr().identOrDefault().name().equals("v"))
            .findFirst()
            .get();

    assertThat(CelNavigableExprUtil.findDeclaringComprehension(identX, "x")).hasValue(outerComp);
    assertThat(CelNavigableExprUtil.findDeclaringComprehension(identK, "k")).hasValue(innerComp);
    assertThat(CelNavigableExprUtil.findDeclaringComprehension(identV, "v")).hasValue(innerComp);
    assertThat(CelNavigableExprUtil.findDeclaringComprehension(identX, "unknown")).isEmpty();
  }
}
