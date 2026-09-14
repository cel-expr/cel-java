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

package dev.cel.common.navigation;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.truth.Truth.assertThat;
import static dev.cel.common.CelFunctionDecl.newFunctionDeclaration;
import static dev.cel.common.CelOverloadDecl.newMemberOverload;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.common.primitives.UnsignedLong;
import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import com.google.testing.junit.testparameterinjector.TestParameters;
import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.CelContainer;
import dev.cel.common.CelOptions;
import dev.cel.common.Operator;
import dev.cel.common.ast.CelConstant;
import dev.cel.common.ast.CelExpr;
import dev.cel.common.ast.CelExpr.CelCall;
import dev.cel.common.ast.CelExpr.ExprKind.Kind;
import dev.cel.common.types.ListType;
import dev.cel.common.types.SimpleType;
import dev.cel.common.types.StructTypeReference;
import dev.cel.compiler.CelCompiler;
import dev.cel.compiler.CelCompilerFactory;
import dev.cel.expr.conformance.proto3.TestAllTypes;
import dev.cel.parser.CelStandardMacro;
import java.util.Optional;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(TestParameterInjector.class)
public class CelNavigableExprVisitorTest {

  @Test
  @TestParameters("{maxDepth: -1, expectedNodeCount: 0}")
  @TestParameters("{maxDepth: 0, expectedNodeCount: 1}")
  @TestParameters("{maxDepth: 1, expectedNodeCount: 3}")
  @TestParameters("{maxDepth: 2, expectedNodeCount: 5}")
  @TestParameters("{maxDepth: 3, expectedNodeCount: 5}")
  public void collectWithMaxDepth_expectedNodeCountReturned(int maxDepth, int expectedNodeCount)
      throws Exception {
    CelCompiler compiler =
        CelCompilerFactory.standardCelCompilerBuilder().addVar("a", SimpleType.INT).build();
    // Tree shape:
    //           +
    //      +         2
    //  1        a
    CelAbstractSyntaxTree ast = compiler.compile("1 + a + 2").getAst();
    CelNavigableAst navigableAst = CelNavigableAst.fromAst(ast);

    ImmutableList<CelNavigableExpr> allNodes =
        CelNavigableExprVisitor.collect(navigableAst.getRoot(), maxDepth, TraversalOrder.PRE_ORDER)
            .collect(toImmutableList());

    assertThat(allNodes).hasSize(expectedNodeCount);
  }

  @Test
  public void add_allNodes_allNodesReturned() throws Exception {
    CelCompiler compiler =
        CelCompilerFactory.standardCelCompilerBuilder().addVar("a", SimpleType.INT).build();
    // Tree shape:
    //           +
    //      +         2
    //  1        a
    CelAbstractSyntaxTree ast = compiler.compile("1 + a + 2").getAst();
    CelNavigableAst navigableAst = CelNavigableAst.fromAst(ast);

    ImmutableList<CelExpr> allNodes =
        navigableAst.getRoot().allNodes().map(CelNavigableExpr::expr).collect(toImmutableList());

    CelExpr childAddCall =
        CelExpr.ofCall(
            2,
            Optional.empty(),
            Operator.ADD.getFunction(),
            ImmutableList.of(
                CelExpr.ofConstant(1, CelConstant.ofValue(1)), // 1 + a
                CelExpr.ofIdent(3, "a")));
    CelExpr rootAddCall =
        CelExpr.ofCall(
            4,
            Optional.empty(),
            Operator.ADD.getFunction(),
            ImmutableList.of(childAddCall, CelExpr.ofConstant(5, CelConstant.ofValue(2))));
    assertThat(allNodes)
        .containsExactly(
            rootAddCall,
            childAddCall,
            CelExpr.ofConstant(1, CelConstant.ofValue(1)),
            CelExpr.ofIdent(3, "a"),
            CelExpr.ofConstant(5, CelConstant.ofValue(2)));
  }

  @Test
  public void add_preOrder_heightSet() throws Exception {
    CelCompiler compiler =
        CelCompilerFactory.standardCelCompilerBuilder().addVar("a", SimpleType.INT).build();
    // Tree shape:
    //           +
    //      +         2
    //  1        a
    CelAbstractSyntaxTree ast = compiler.compile("1 + a + 2").getAst();
    CelNavigableAst navigableAst = CelNavigableAst.fromAst(ast);

    ImmutableList<Integer> allNodeHeights =
        navigableAst
            .getRoot()
            .allNodes(TraversalOrder.PRE_ORDER)
            .map(CelNavigableExpr::height)
            .collect(toImmutableList());
    assertThat(allNodeHeights).containsExactly(2, 1, 0, 0, 0).inOrder(); // +, +, 1, a, 2
  }

  @Test
  public void add_preOrder_maxIdsSet() throws Exception {
    CelCompiler compiler =
        CelCompilerFactory.standardCelCompilerBuilder().addVar("a", SimpleType.INT).build();
    // Tree shape (brackets are IDs):
    //             + [4]
    //      + [2]          2 [5]
    //  1 [1]    a [3]
    CelAbstractSyntaxTree ast = compiler.compile("1 + a + 2").getAst();
    CelNavigableAst navigableAst = CelNavigableAst.fromAst(ast);

    ImmutableList<Long> allNodeMaxIds =
        navigableAst
            .getRoot()
            .allNodes(TraversalOrder.PRE_ORDER)
            .map(CelNavigableExpr::maxId)
            .collect(toImmutableList());
    assertThat(allNodeMaxIds).containsExactly(5L, 3L, 1L, 3L, 5L).inOrder(); // +, +, 1, a, 2
  }

  @Test
  public void add_postOrder_heightSet() throws Exception {
    CelCompiler compiler =
        CelCompilerFactory.standardCelCompilerBuilder().addVar("a", SimpleType.INT).build();
    // Tree shape:
    //           +
    //      +         2
    //  1        a
    CelAbstractSyntaxTree ast = compiler.compile("1 + a + 2").getAst();
    CelNavigableAst navigableAst = CelNavigableAst.fromAst(ast);

    ImmutableList<Integer> allNodeHeights =
        navigableAst
            .getRoot()
            .allNodes(TraversalOrder.POST_ORDER)
            .map(CelNavigableExpr::height)
            .collect(toImmutableList());
    assertThat(allNodeHeights).containsExactly(0, 0, 1, 0, 2).inOrder(); // 1, a, +, 2, +
  }

  @Test
  public void add_postOrder_maxIdsSet() throws Exception {
    CelCompiler compiler =
        CelCompilerFactory.standardCelCompilerBuilder().addVar("a", SimpleType.INT).build();
    // Tree shape (brackets are IDs):
    //             + [4]
    //      + [2]          2 [5]
    //  1 [1]    a [3]
    CelAbstractSyntaxTree ast = compiler.compile("1 + a + 2").getAst();
    CelNavigableAst navigableAst = CelNavigableAst.fromAst(ast);

    ImmutableList<Long> allNodeMaxIds =
        navigableAst
            .getRoot()
            .allNodes(TraversalOrder.POST_ORDER)
            .map(CelNavigableExpr::maxId)
            .collect(toImmutableList());
    assertThat(allNodeMaxIds).containsExactly(1L, 3L, 3L, 5L, 5L).inOrder(); // 1, a, +, 2, +
  }

  @Test
  public void add_fromLeaf_heightSetForParents() throws Exception {
    CelCompiler compiler =
        CelCompilerFactory.standardCelCompilerBuilder().addVar("a", SimpleType.INT).build();
    // Tree shape:
    //              +
    //         +         3
    //     +        2
    //  a     1
    CelAbstractSyntaxTree ast = compiler.compile("1 + a + 2 + 3").getAst();
    CelNavigableAst navigableAst = CelNavigableAst.fromAst(ast);

    ImmutableList.Builder<Integer> heights = ImmutableList.builder();
    CelNavigableExpr navigableExpr =
        navigableAst
            .getRoot()
            .allNodes()
            .filter(node -> node.expr().identOrDefault().name().equals("a"))
            .findAny()
            .get();
    heights.add(navigableExpr.height());
    while (navigableExpr.parent().isPresent()) {
      navigableExpr = navigableExpr.parent().get();
      heights.add(navigableExpr.height());
    }

    assertThat(heights.build()).containsExactly(3, 2, 1, 0);
  }

  @Test
  public void add_fromLeaf_maxIdsSetForParents() throws Exception {
    CelCompiler compiler =
        CelCompilerFactory.standardCelCompilerBuilder().addVar("a", SimpleType.INT).build();
    // Tree shape:
    //              +
    //         +         3
    //     +        2
    //  a     1
    CelAbstractSyntaxTree ast = compiler.compile("1 + a + 2 + 3").getAst();
    CelNavigableAst navigableAst = CelNavigableAst.fromAst(ast);

    ImmutableList.Builder<Long> heights = ImmutableList.builder();
    CelNavigableExpr navigableExpr =
        navigableAst
            .getRoot()
            .allNodes()
            .filter(node -> node.expr().identOrDefault().name().equals("a"))
            .findAny()
            .get();
    heights.add(navigableExpr.maxId());
    while (navigableExpr.parent().isPresent()) {
      navigableExpr = navigableExpr.parent().get();
      heights.add(navigableExpr.maxId());
    }

    assertThat(heights.build()).containsExactly(3L, 3L, 5L, 7L).inOrder();
  }

  @Test
  public void add_children_heightSet(@TestParameter TraversalOrder traversalOrder)
      throws Exception {
    CelCompiler compiler =
        CelCompilerFactory.standardCelCompilerBuilder().addVar("a", SimpleType.INT).build();
    // Tree shape:
    //              +
    //         +         3
    //     +        2
    //  a     1
    CelAbstractSyntaxTree ast = compiler.compile("1 + a + 2 + 3").getAst();
    CelNavigableAst navigableAst = CelNavigableAst.fromAst(ast);

    ImmutableList<Integer> allNodeHeights =
        navigableAst
            .getRoot()
            .children(traversalOrder)
            .map(CelNavigableExpr::height)
            .collect(toImmutableList());
    assertThat(allNodeHeights).containsExactly(2, 0).inOrder(); // + (2), 2 (0) regardless of order
  }

  @Test
  public void add_children_maxIdsSet(@TestParameter TraversalOrder traversalOrder)
      throws Exception {
    CelCompiler compiler =
        CelCompilerFactory.standardCelCompilerBuilder().addVar("a", SimpleType.INT).build();
    // Tree shape:
    //              +
    //         +         3
    //     +        2
    //  a     1
    CelAbstractSyntaxTree ast = compiler.compile("1 + a + 2 + 3").getAst();
    CelNavigableAst navigableAst = CelNavigableAst.fromAst(ast);

    ImmutableList<Long> allNodeHeights =
        navigableAst
            .getRoot()
            .children(traversalOrder)
            .map(CelNavigableExpr::maxId)
            .collect(toImmutableList());
    assertThat(allNodeHeights)
        .containsExactly(5L, 7L)
        .inOrder(); // + (5), 3 (7) regardless of order
  }

  @Test
  public void add_filterConstants_allNodesReturned() throws Exception {
    CelCompiler compiler =
        CelCompilerFactory.standardCelCompilerBuilder().addVar("a", SimpleType.INT).build();
    // Tree shape:
    //           +
    //      +         2
    //  1        a
    CelAbstractSyntaxTree ast = compiler.compile("1 + a + 2").getAst();
    CelNavigableAst navigableAst = CelNavigableAst.fromAst(ast);

    ImmutableList<CelNavigableExpr> allConstants =
        navigableAst
            .getRoot()
            .allNodes()
            .filter(x -> x.getKind().equals(Kind.CONSTANT))
            .collect(toImmutableList());

    assertThat(allConstants).hasSize(2);
    assertThat(allConstants.get(0).expr()).isEqualTo(CelExpr.ofConstant(1, CelConstant.ofValue(1)));
    assertThat(allConstants.get(1).expr()).isEqualTo(CelExpr.ofConstant(5, CelConstant.ofValue(2)));
  }

  @Test
  public void add_filterConstants_parentsPopulated() throws Exception {
    CelCompiler compiler =
        CelCompilerFactory.standardCelCompilerBuilder().addVar("a", SimpleType.INT).build();
    // Tree shape:
    //           +
    //      +         2
    //  1        a
    CelAbstractSyntaxTree ast = compiler.compile("1 + a + 2").getAst();
    CelNavigableAst navigableAst = CelNavigableAst.fromAst(ast);

    ImmutableList<CelNavigableExpr> allConstants =
        navigableAst
            .getRoot()
            .allNodes()
            .filter(x -> x.getKind().equals(Kind.CONSTANT))
            .collect(toImmutableList());

    assertThat(allConstants).hasSize(2);
    CelExpr childAddCall = allConstants.get(0).parent().get().expr(); // 1 + a
    CelExpr rootAddCall = allConstants.get(1).parent().get().expr(); // childAddCall + 2
    assertThat(childAddCall)
        .isEqualTo(
            CelExpr.ofCall(
                2,
                Optional.empty(),
                Operator.ADD.getFunction(),
                ImmutableList.of(
                    CelExpr.ofConstant(1, CelConstant.ofValue(1)), CelExpr.ofIdent(3, "a"))));
    assertThat(rootAddCall)
        .isEqualTo(
            CelExpr.ofCall(
                4,
                Optional.empty(),
                Operator.ADD.getFunction(),
                ImmutableList.of(childAddCall, CelExpr.ofConstant(5, CelConstant.ofValue(2)))));
  }

  @Test
  public void add_filterConstants_singleChildReturned() throws Exception {
    CelCompiler compiler =
        CelCompilerFactory.standardCelCompilerBuilder().addVar("a", SimpleType.INT).build();
    // Tree shape:
    //           +
    //      +         2
    //  1        a
    CelAbstractSyntaxTree ast = compiler.compile("1 + a + 2").getAst();
    CelNavigableAst navigableAst = CelNavigableAst.fromAst(ast);

    ImmutableList<CelNavigableExpr> allConstants =
        navigableAst
            .getRoot()
            .children()
            .filter(x -> x.getKind().equals(Kind.CONSTANT))
            .collect(toImmutableList());

    assertThat(allConstants).hasSize(1);
    assertThat(allConstants.get(0).expr()).isEqualTo(CelExpr.ofConstant(5, CelConstant.ofValue(2)));
  }

  @Test
  public void add_filterConstants_parentOfChildPopulated() throws Exception {
    CelCompiler compiler =
        CelCompilerFactory.standardCelCompilerBuilder().addVar("a", SimpleType.INT).build();
    // Tree shape:
    //           +
    //      +         2
    //  1        a
    CelAbstractSyntaxTree ast = compiler.compile("1 + a + 2").getAst();
    CelNavigableAst navigableAst = CelNavigableAst.fromAst(ast);

    ImmutableList<CelNavigableExpr> allConstants =
        navigableAst
            .getRoot()
            .children()
            .filter(x -> x.getKind().equals(Kind.CONSTANT))
            .collect(toImmutableList());

    assertThat(allConstants).hasSize(1);
    CelExpr parentExpr = allConstants.get(0).parent().get().expr();
    assertThat(parentExpr.exprKind().getKind()).isEqualTo(Kind.CALL);
    assertThat(parentExpr.call().function()).isEqualTo(Operator.ADD.getFunction());
    assertThat(parentExpr.call().args().get(0).exprKind().getKind()).isEqualTo(Kind.CALL);
    assertThat(parentExpr.call().args().get(1).constant()).isEqualTo(CelConstant.ofValue(2));
  }

  @Test
  public void add_childrenOfMiddleBranch_success() throws Exception {
    CelCompiler compiler =
        CelCompilerFactory.standardCelCompilerBuilder().addVar("a", SimpleType.INT).build();
    // Tree shape:
    //           +
    //      +         2
    //  1        a
    CelAbstractSyntaxTree ast = compiler.compile("1 + a + 2").getAst();
    CelNavigableAst navigableAst = CelNavigableAst.fromAst(ast);
    CelNavigableExpr ident =
        navigableAst
            .getRoot()
            .allNodes()
            .filter(node -> node.getKind().equals(Kind.IDENT)) // Find "a"
            .findAny()
            .get();

    ImmutableList<CelNavigableExpr> children =
        ident.parent().get().children().collect(toImmutableList());

    // Assert that the children of add call in the middle branch are const(1) and ident("a")
    assertThat(children).hasSize(2);
    assertThat(children.get(0).expr()).isEqualTo(CelExpr.ofConstant(1, CelConstant.ofValue(1)));
    assertThat(children.get(1).expr()).isEqualTo(ident.expr());
  }

  @Test
  public void stringFormatCall_filterList_success() throws Exception {
    CelCompiler compiler =
        CelCompilerFactory.standardCelCompilerBuilder()
            .addFunctionDeclarations(
                newFunctionDeclaration(
                    "format",
                    newMemberOverload(
                        "string_format_overload",
                        SimpleType.STRING,
                        ImmutableList.of(SimpleType.STRING, ListType.create(SimpleType.DYN)))))
            .build();
    CelAbstractSyntaxTree ast = compiler.compile("'%f %s'.format([3.14, 'test'])").getAst();
    CelNavigableAst navigableAst = CelNavigableAst.fromAst(ast);

    ImmutableList<CelNavigableExpr> allConstants =
        navigableAst
            .getRoot()
            .allNodes()
            .filter(x -> x.getKind().equals(Kind.LIST))
            .collect(toImmutableList());

    assertThat(allConstants).hasSize(1);
    CelNavigableExpr listExpr = allConstants.get(0);
    assertThat(listExpr.getKind()).isEqualTo(Kind.LIST);
    assertThat(listExpr.parent()).isPresent();
    CelNavigableExpr stringFormatExpr = listExpr.parent().get();
    assertThat(stringFormatExpr.getKind()).isEqualTo(Kind.CALL);
    CelCall call = listExpr.parent().get().expr().exprKind().call();
    assertThat(call.function()).isEqualTo("format");
    assertThat(call.target().get().exprKind().constant().stringValue()).isEqualTo("%f %s");
  }

  @Test
  public void stringFormatCall_allNodesReturned() throws Exception {
    CelCompiler compiler =
        CelCompilerFactory.standardCelCompilerBuilder()
            .addFunctionDeclarations(
                newFunctionDeclaration(
                    "format",
                    newMemberOverload(
                        "string_format_overload",
                        SimpleType.STRING,
                        ImmutableList.of(SimpleType.STRING, ListType.create(SimpleType.DYN)))))
            .build();
    CelAbstractSyntaxTree ast = compiler.compile("'%f %s'.format([3.14, 'test'])").getAst();
    CelNavigableAst navigableAst = CelNavigableAst.fromAst(ast);

    ImmutableList<CelNavigableExpr> targetExprs =
        navigableAst.getRoot().allNodes().collect(toImmutableList());

    assertThat(targetExprs).hasSize(5);
  }

  @Test
  public void message_allNodesReturned() throws Exception {
    CelCompiler compiler =
        CelCompilerFactory.standardCelCompilerBuilder()
            .addMessageTypes(TestAllTypes.getDescriptor())
            .addVar("msg", StructTypeReference.create(TestAllTypes.getDescriptor().getFullName()))
            .build();
    CelAbstractSyntaxTree ast = compiler.compile("msg.single_int64").getAst();
    CelNavigableAst navigableAst = CelNavigableAst.fromAst(ast);

    ImmutableList<CelExpr> allNodes =
        navigableAst.getRoot().allNodes().map(CelNavigableExpr::expr).collect(toImmutableList());

    CelExpr operand = CelExpr.ofIdent(1, "msg");
    assertThat(allNodes)
        .containsExactly(operand, CelExpr.ofSelect(2, operand, "single_int64", false));
  }

  @Test
  public void nestedMessage_filterSelect_allNodesReturned() throws Exception {
    CelCompiler compiler =
        CelCompilerFactory.standardCelCompilerBuilder()
            .addMessageTypes(TestAllTypes.getDescriptor())
            .addVar("msg", StructTypeReference.create(TestAllTypes.getDescriptor().getFullName()))
            .build();
    CelAbstractSyntaxTree ast = compiler.compile("msg.standalone_message.bb").getAst();
    CelNavigableAst navigableAst = CelNavigableAst.fromAst(ast);

    ImmutableList<CelExpr> allSelects =
        navigableAst
            .getRoot()
            .allNodes()
            .filter(x -> x.getKind().equals(Kind.SELECT))
            .map(CelNavigableExpr::expr)
            .collect(toImmutableList());

    CelExpr innerSelect =
        CelExpr.ofSelect(
            2, CelExpr.ofIdent(1, "msg"), "standalone_message", false); // msg.standalone
    CelExpr outerSelect = CelExpr.ofSelect(3, innerSelect, "bb", false); // innerSelect.bb
    assertThat(allSelects).containsExactly(innerSelect, outerSelect);
  }

  @Test
  public void nestedMessage_filterSelect_singleChildReturned() throws Exception {
    CelCompiler compiler =
        CelCompilerFactory.standardCelCompilerBuilder()
            .addMessageTypes(TestAllTypes.getDescriptor())
            .addVar("msg", StructTypeReference.create(TestAllTypes.getDescriptor().getFullName()))
            .build();
    CelAbstractSyntaxTree ast =
        compiler
            .compile(
                "1 + msg.standalone_message.bb") // "1 +" added to make something other than select
            // the root.
            .getAst();
    CelNavigableAst navigableAst = CelNavigableAst.fromAst(ast);

    ImmutableList<CelNavigableExpr> allSelects =
        navigableAst
            .getRoot()
            .children()
            .filter(x -> x.getKind().equals(Kind.SELECT))
            .collect(toImmutableList());

    assertThat(allSelects).hasSize(1);
    CelExpr innerSelect =
        CelExpr.ofSelect(
            4, CelExpr.ofIdent(3, "msg"), "standalone_message", false); // msg.standalone
    CelExpr outerSelect = CelExpr.ofSelect(5, innerSelect, "bb", false); // innerSelect.bb
    assertThat(allSelects.get(0).expr()).isEqualTo(outerSelect);
  }

  @Test
  public void presenceTest_allNodesReturned() throws Exception {
    CelCompiler compiler =
        CelCompilerFactory.standardCelCompilerBuilder()
            .addMessageTypes(TestAllTypes.getDescriptor())
            .addVar("msg", StructTypeReference.create(TestAllTypes.getDescriptor().getFullName()))
            .setStandardMacros(CelStandardMacro.STANDARD_MACROS)
            .build();
    CelAbstractSyntaxTree ast = compiler.compile("has(msg.standalone_message)").getAst();
    CelNavigableAst navigableAst = CelNavigableAst.fromAst(ast);

    ImmutableList<CelExpr> allNodes =
        navigableAst.getRoot().allNodes().map(CelNavigableExpr::expr).collect(toImmutableList());

    assertThat(allNodes).hasSize(2);
    assertThat(allNodes)
        .containsExactly(
            CelExpr.ofIdent(2, "msg"),
            CelExpr.ofSelect(4, CelExpr.ofIdent(2, "msg"), "standalone_message", true));
  }

  @Test
  public void messageConstruction_allNodesReturned() throws Exception {
    CelCompiler compiler =
        CelCompilerFactory.standardCelCompilerBuilder()
            .addMessageTypes(TestAllTypes.getDescriptor())
            .setContainer(CelContainer.ofName("cel.expr.conformance.proto3"))
            .build();
    CelAbstractSyntaxTree ast = compiler.compile("TestAllTypes{single_int64: 1}").getAst();
    CelNavigableAst navigableAst = CelNavigableAst.fromAst(ast);

    ImmutableList<CelExpr> allNodes =
        navigableAst.getRoot().allNodes().map(CelNavigableExpr::expr).collect(toImmutableList());

    CelExpr constExpr = CelExpr.ofConstant(3, CelConstant.ofValue(1));
    assertThat(allNodes)
        .containsExactly(
            constExpr,
            CelExpr.ofStruct(
                1,
                "cel.expr.conformance.proto3.TestAllTypes",
                ImmutableList.of(CelExpr.ofStructEntry(2, "single_int64", constExpr, false))));
  }

  @Test
  public void messageConstruction_filterStruct_allNodesReturned() throws Exception {
    CelCompiler compiler =
        CelCompilerFactory.standardCelCompilerBuilder()
            .addMessageTypes(TestAllTypes.getDescriptor())
            .setContainer(CelContainer.ofName("cel.expr.conformance.proto3"))
            .build();
    CelAbstractSyntaxTree ast = compiler.compile("TestAllTypes{single_int64: 1}").getAst();
    CelNavigableAst navigableAst = CelNavigableAst.fromAst(ast);

    ImmutableList<CelNavigableExpr> allNodes =
        navigableAst
            .getRoot()
            .allNodes()
            .filter(x -> x.getKind().equals(Kind.STRUCT))
            .collect(toImmutableList());

    assertThat(allNodes).hasSize(1);
    assertThat(allNodes.get(0).expr())
        .isEqualTo(
            CelExpr.ofStruct(
                1,
                "cel.expr.conformance.proto3.TestAllTypes",
                ImmutableList.of(
                    CelExpr.ofStructEntry(
                        2, "single_int64", CelExpr.ofConstant(3, CelConstant.ofValue(1)), false))));
  }

  @Test
  public void messageConstruction_preOrder_heightSet() throws Exception {
    CelCompiler compiler =
        CelCompilerFactory.standardCelCompilerBuilder()
            .addMessageTypes(TestAllTypes.getDescriptor())
            .setContainer(CelContainer.ofName("cel.expr.conformance.proto3"))
            .build();
    CelAbstractSyntaxTree ast = compiler.compile("TestAllTypes{single_int64: 1}").getAst();
    CelNavigableAst navigableAst = CelNavigableAst.fromAst(ast);

    ImmutableList<Integer> allNodes =
        navigableAst
            .getRoot()
            .allNodes(TraversalOrder.PRE_ORDER)
            .map(CelNavigableExpr::height)
            .collect(toImmutableList());

    assertThat(allNodes).containsExactly(1, 0).inOrder();
  }

  @Test
  public void messageConstruction_postOrder_heightSet() throws Exception {
    CelCompiler compiler =
        CelCompilerFactory.standardCelCompilerBuilder()
            .addMessageTypes(TestAllTypes.getDescriptor())
            .setContainer(CelContainer.ofName("cel.expr.conformance.proto3"))
            .build();
    CelAbstractSyntaxTree ast = compiler.compile("TestAllTypes{single_int64: 1}").getAst();
    CelNavigableAst navigableAst = CelNavigableAst.fromAst(ast);

    ImmutableList<Integer> allNodes =
        navigableAst
            .getRoot()
            .allNodes(TraversalOrder.POST_ORDER)
            .map(CelNavigableExpr::height)
            .collect(toImmutableList());

    assertThat(allNodes).containsExactly(0, 1).inOrder();
  }

  @Test
  public void messageConstruction_maxIdsSet(@TestParameter TraversalOrder traversalOrder)
      throws Exception {
    CelCompiler compiler =
        CelCompilerFactory.standardCelCompilerBuilder()
            .addMessageTypes(TestAllTypes.getDescriptor())
            .setContainer(CelContainer.ofName("cel.expr.conformance.proto3"))
            .build();
    CelAbstractSyntaxTree ast = compiler.compile("TestAllTypes{single_int64: 1}").getAst();
    CelNavigableAst navigableAst = CelNavigableAst.fromAst(ast);

    ImmutableList<Long> allNodes =
        navigableAst
            .getRoot()
            .allNodes(traversalOrder)
            .map(CelNavigableExpr::maxId)
            .collect(toImmutableList());

    assertThat(allNodes).containsExactly(3L, 3L).inOrder();
  }

  @Test
  public void mapConstruction_allNodesReturned() throws Exception {
    CelCompiler compiler = CelCompilerFactory.standardCelCompilerBuilder().build();
    CelAbstractSyntaxTree ast = compiler.compile("{'key': 2}").getAst();
    CelNavigableAst navigableAst = CelNavigableAst.fromAst(ast);

    ImmutableList<CelExpr> allNodes =
        navigableAst.getRoot().allNodes().map(CelNavigableExpr::expr).collect(toImmutableList());

    assertThat(allNodes).hasSize(3);
    CelExpr mapKeyExpr = CelExpr.ofConstant(3, CelConstant.ofValue("key"));
    CelExpr mapValueExpr = CelExpr.ofConstant(4, CelConstant.ofValue(2));
    assertThat(allNodes)
        .containsExactly(
            mapKeyExpr,
            mapValueExpr,
            CelExpr.ofMap(
                1, ImmutableList.of(CelExpr.ofMapEntry(2, mapKeyExpr, mapValueExpr, false))));
  }

  @Test
  public void mapConstruction_filterMap_allNodesReturned() throws Exception {
    CelCompiler compiler = CelCompilerFactory.standardCelCompilerBuilder().build();
    CelAbstractSyntaxTree ast = compiler.compile("{'key': 2}").getAst();
    CelNavigableAst navigableAst = CelNavigableAst.fromAst(ast);

    ImmutableList<CelNavigableExpr> allNodes =
        navigableAst
            .getRoot()
            .allNodes()
            .filter(x -> x.getKind().equals(Kind.MAP))
            .collect(toImmutableList());

    assertThat(allNodes).hasSize(1);
    CelExpr mapKeyExpr = CelExpr.ofConstant(3, CelConstant.ofValue("key"));
    CelExpr mapValueExpr = CelExpr.ofConstant(4, CelConstant.ofValue(2));
    assertThat(allNodes.get(0).expr())
        .isEqualTo(
            CelExpr.ofMap(
                1, ImmutableList.of(CelExpr.ofMapEntry(2, mapKeyExpr, mapValueExpr, false))));
  }

  @Test
  public void mapConstruction_preOrder_heightSet() throws Exception {
    CelCompiler compiler = CelCompilerFactory.standardCelCompilerBuilder().build();
    CelAbstractSyntaxTree ast = compiler.compile("{'key': 2}").getAst();
    CelNavigableAst navigableAst = CelNavigableAst.fromAst(ast);

    ImmutableList<Integer> allNodes =
        navigableAst
            .getRoot()
            .allNodes(TraversalOrder.PRE_ORDER)
            .map(CelNavigableExpr::height)
            .collect(toImmutableList());

    assertThat(allNodes).containsExactly(1, 0, 0).inOrder();
  }

  @Test
  public void mapConstruction_postOrder_heightSet() throws Exception {
    CelCompiler compiler = CelCompilerFactory.standardCelCompilerBuilder().build();
    CelAbstractSyntaxTree ast = compiler.compile("{'key': 2}").getAst();
    CelNavigableAst navigableAst = CelNavigableAst.fromAst(ast);

    ImmutableList<Integer> allNodes =
        navigableAst
            .getRoot()
            .allNodes(TraversalOrder.POST_ORDER)
            .map(CelNavigableExpr::height)
            .collect(toImmutableList());

    assertThat(allNodes).containsExactly(0, 0, 1).inOrder();
  }

  @Test
  public void mapConstruction_preOrder_maxIdsSet() throws Exception {
    CelCompiler compiler = CelCompilerFactory.standardCelCompilerBuilder().build();
    CelAbstractSyntaxTree ast = compiler.compile("{'key': 2}").getAst();
    CelNavigableAst navigableAst = CelNavigableAst.fromAst(ast);

    ImmutableList<Long> allNodes =
        navigableAst
            .getRoot()
            .allNodes(TraversalOrder.PRE_ORDER)
            .map(CelNavigableExpr::maxId)
            .collect(toImmutableList());

    assertThat(allNodes).containsExactly(4L, 3L, 4L).inOrder();
  }

  @Test
  public void mapConstruction_postOrder_maxIdsSet() throws Exception {
    CelCompiler compiler = CelCompilerFactory.standardCelCompilerBuilder().build();
    CelAbstractSyntaxTree ast = compiler.compile("{'key': 2}").getAst();
    CelNavigableAst navigableAst = CelNavigableAst.fromAst(ast);

    ImmutableList<Long> allNodes =
        navigableAst
            .getRoot()
            .allNodes(TraversalOrder.POST_ORDER)
            .map(CelNavigableExpr::maxId)
            .collect(toImmutableList());

    assertThat(allNodes).containsExactly(3L, 4L, 4L).inOrder();
  }

  @Test
  public void emptyMapConstruction_allNodesReturned() throws Exception {
    CelCompiler compiler = CelCompilerFactory.standardCelCompilerBuilder().build();
    CelAbstractSyntaxTree ast = compiler.compile("{}").getAst();
    CelNavigableAst navigableAst = CelNavigableAst.fromAst(ast);

    ImmutableList<CelNavigableExpr> allNodes =
        navigableAst.getRoot().allNodes().collect(toImmutableList());

    assertThat(allNodes).hasSize(1);
    assertThat(allNodes.get(0).expr()).isEqualTo(CelExpr.ofMap(1, ImmutableList.of()));
  }

  @Test
  public void comprehension_preOrder_allNodesReturned() throws Exception {
    CelCompiler compiler =
        CelCompilerFactory.standardCelCompilerBuilder()
            .setStandardMacros(CelStandardMacro.EXISTS)
            .build();
    CelAbstractSyntaxTree ast = compiler.compile("[true].exists(i, i)").getAst();
    CelNavigableAst navigableAst = CelNavigableAst.fromAst(ast);

    ImmutableList<CelExpr> allNodes =
        navigableAst
            .getRoot()
            .allNodes(TraversalOrder.PRE_ORDER)
            .map(CelNavigableExpr::expr)
            .collect(toImmutableList());

    CelExpr iterRangeConstExpr = CelExpr.ofConstant(2, CelConstant.ofValue(true));
    CelExpr iterRange = CelExpr.ofList(1, ImmutableList.of(iterRangeConstExpr), ImmutableList.of());
    CelExpr accuInit = CelExpr.ofConstant(6, CelConstant.ofValue(false));
    CelExpr loopConditionIdentExpr = CelExpr.ofIdent(7, "@result");
    CelExpr loopConditionCallExpr =
        CelExpr.ofCall(
            8,
            Optional.empty(),
            Operator.LOGICAL_NOT.getFunction(),
            ImmutableList.of(loopConditionIdentExpr));
    CelExpr loopCondition =
        CelExpr.ofCall(
            9,
            Optional.empty(),
            Operator.NOT_STRICTLY_FALSE.getFunction(),
            ImmutableList.of(loopConditionCallExpr));
    CelExpr loopStepResultExpr = CelExpr.ofIdent(10, "@result");
    CelExpr loopStepVarExpr = CelExpr.ofIdent(5, "i");
    CelExpr loopStep =
        CelExpr.ofCall(
            11,
            Optional.empty(),
            Operator.LOGICAL_OR.getFunction(),
            ImmutableList.of(loopStepResultExpr, loopStepVarExpr));
    CelExpr result = CelExpr.ofIdent(12, "@result");
    CelExpr comprehension =
        CelExpr.ofComprehension(
            13, "i", iterRange, "@result", accuInit, loopCondition, loopStep, result);
    assertThat(allNodes).hasSize(11);
    assertThat(allNodes)
        .containsExactly(
            comprehension,
            iterRange,
            iterRangeConstExpr,
            accuInit,
            loopCondition,
            loopConditionCallExpr,
            loopConditionIdentExpr,
            loopStep,
            loopStepResultExpr,
            loopStepVarExpr,
            result)
        .inOrder();
  }

  @Test
  public void comprehension_postOrder_allNodesReturned() throws Exception {
    CelCompiler compiler =
        CelCompilerFactory.standardCelCompilerBuilder()
            .setStandardMacros(CelStandardMacro.EXISTS)
            .build();
    CelAbstractSyntaxTree ast = compiler.compile("[true].exists(i, i)").getAst();
    CelNavigableAst navigableAst = CelNavigableAst.fromAst(ast);

    ImmutableList<CelExpr> allNodes =
        navigableAst
            .getRoot()
            .allNodes(TraversalOrder.POST_ORDER)
            .map(CelNavigableExpr::expr)
            .collect(toImmutableList());

    CelExpr iterRangeConstExpr = CelExpr.ofConstant(2, CelConstant.ofValue(true));
    CelExpr iterRange = CelExpr.ofList(1, ImmutableList.of(iterRangeConstExpr), ImmutableList.of());
    CelExpr accuInit = CelExpr.ofConstant(6, CelConstant.ofValue(false));
    CelExpr loopConditionIdentExpr = CelExpr.ofIdent(7, "@result");
    CelExpr loopConditionCallExpr =
        CelExpr.ofCall(
            8,
            Optional.empty(),
            Operator.LOGICAL_NOT.getFunction(),
            ImmutableList.of(loopConditionIdentExpr));
    CelExpr loopCondition =
        CelExpr.ofCall(
            9,
            Optional.empty(),
            Operator.NOT_STRICTLY_FALSE.getFunction(),
            ImmutableList.of(loopConditionCallExpr));
    CelExpr loopStepResultExpr = CelExpr.ofIdent(10, "@result");
    CelExpr loopStepVarExpr = CelExpr.ofIdent(5, "i");
    CelExpr loopStep =
        CelExpr.ofCall(
            11,
            Optional.empty(),
            Operator.LOGICAL_OR.getFunction(),
            ImmutableList.of(loopStepResultExpr, loopStepVarExpr));
    CelExpr result = CelExpr.ofIdent(12, "@result");
    CelExpr comprehension =
        CelExpr.ofComprehension(
            13, "i", iterRange, "@result", accuInit, loopCondition, loopStep, result);
    assertThat(allNodes).hasSize(11);
    assertThat(allNodes)
        .containsExactly(
            iterRangeConstExpr,
            iterRange,
            accuInit,
            loopConditionIdentExpr,
            loopConditionCallExpr,
            loopCondition,
            loopStepResultExpr,
            loopStepVarExpr,
            loopStep,
            result,
            comprehension)
        .inOrder();
  }

  @Test
  public void comprehension_preOrder_heightSet() throws Exception {
    CelCompiler compiler =
        CelCompilerFactory.standardCelCompilerBuilder()
            .setStandardMacros(CelStandardMacro.EXISTS)
            .build();
    CelAbstractSyntaxTree ast = compiler.compile("[true].exists(i, i)").getAst();
    CelNavigableAst navigableAst = CelNavigableAst.fromAst(ast);

    ImmutableList<Integer> allNodes =
        navigableAst
            .getRoot()
            .allNodes(TraversalOrder.PRE_ORDER)
            .map(CelNavigableExpr::height)
            .collect(toImmutableList());

    assertThat(allNodes).containsExactly(3, 1, 0, 0, 2, 1, 0, 1, 0, 0, 0).inOrder();
  }

  @Test
  public void comprehension_postOrder_heightSet() throws Exception {
    CelCompiler compiler =
        CelCompilerFactory.standardCelCompilerBuilder()
            .setStandardMacros(CelStandardMacro.EXISTS)
            .build();
    CelAbstractSyntaxTree ast = compiler.compile("[true].exists(i, i)").getAst();
    CelNavigableAst navigableAst = CelNavigableAst.fromAst(ast);

    ImmutableList<Integer> allNodes =
        navigableAst
            .getRoot()
            .allNodes(TraversalOrder.POST_ORDER)
            .map(CelNavigableExpr::height)
            .collect(toImmutableList());

    assertThat(allNodes).containsExactly(0, 1, 0, 0, 1, 2, 0, 0, 1, 0, 3).inOrder();
  }

  @Test
  public void comprehension_preOrder_maxIdsSet() throws Exception {
    CelCompiler compiler =
        CelCompilerFactory.standardCelCompilerBuilder()
            .setStandardMacros(CelStandardMacro.EXISTS)
            .build();
    CelAbstractSyntaxTree ast = compiler.compile("[true].exists(i, i)").getAst();
    CelNavigableAst navigableAst = CelNavigableAst.fromAst(ast);

    ImmutableList<Long> allNodes =
        navigableAst
            .getRoot()
            .allNodes(TraversalOrder.PRE_ORDER)
            .map(CelNavigableExpr::maxId)
            .collect(toImmutableList());

    assertThat(allNodes).containsExactly(13L, 2L, 2L, 6L, 9L, 8L, 7L, 11L, 10L, 5L, 12L).inOrder();
  }

  @Test
  public void comprehension_postOrder_maxIdsSet() throws Exception {
    CelCompiler compiler =
        CelCompilerFactory.standardCelCompilerBuilder()
            .setStandardMacros(CelStandardMacro.EXISTS)
            .build();
    CelAbstractSyntaxTree ast = compiler.compile("[true].exists(i, i)").getAst();
    CelNavigableAst navigableAst = CelNavigableAst.fromAst(ast);

    ImmutableList<Long> allNodes =
        navigableAst
            .getRoot()
            .allNodes(TraversalOrder.POST_ORDER)
            .map(CelNavigableExpr::maxId)
            .collect(toImmutableList());

    assertThat(allNodes).containsExactly(2L, 2L, 6L, 7L, 8L, 9L, 10L, 5L, 11L, 12L, 13L).inOrder();
  }

  @Test
  public void comprehension_allNodes_parentsPopulated() throws Exception {
    CelCompiler compiler =
        CelCompilerFactory.standardCelCompilerBuilder()
            .setStandardMacros(CelStandardMacro.EXISTS)
            .build();
    CelAbstractSyntaxTree ast = compiler.compile("[true].exists(i, i)").getAst();
    CelNavigableAst navigableAst = CelNavigableAst.fromAst(ast);

    ImmutableList<CelNavigableExpr> allNodes =
        navigableAst.getRoot().allNodes(TraversalOrder.PRE_ORDER).collect(toImmutableList());
    CelExpr iterRangeConstExpr = CelExpr.ofConstant(2, CelConstant.ofValue(true));
    CelExpr iterRange = CelExpr.ofList(1, ImmutableList.of(iterRangeConstExpr), ImmutableList.of());
    CelExpr accuInit = CelExpr.ofConstant(6, CelConstant.ofValue(false));
    CelExpr loopConditionIdentExpr = CelExpr.ofIdent(7, "@result");
    CelExpr loopConditionCallExpr =
        CelExpr.ofCall(
            8,
            Optional.empty(),
            Operator.LOGICAL_NOT.getFunction(),
            ImmutableList.of(loopConditionIdentExpr));
    CelExpr loopCondition =
        CelExpr.ofCall(
            9,
            Optional.empty(),
            Operator.NOT_STRICTLY_FALSE.getFunction(),
            ImmutableList.of(loopConditionCallExpr));
    CelExpr loopStepResultExpr = CelExpr.ofIdent(10, "@result");
    CelExpr loopStepVarExpr = CelExpr.ofIdent(5, "i");
    CelExpr loopStep =
        CelExpr.ofCall(
            11,
            Optional.empty(),
            Operator.LOGICAL_OR.getFunction(),
            ImmutableList.of(loopStepResultExpr, loopStepVarExpr));
    CelExpr result = CelExpr.ofIdent(12, "@result");
    CelExpr comprehension =
        CelExpr.ofComprehension(
            13, "i", iterRange, "@result", accuInit, loopCondition, loopStep, result);
    assertThat(allNodes).hasSize(11);
    assertThat(allNodes.get(0).parent()).isEmpty(); // comprehension
    assertThat(allNodes.get(1).parent().get().expr()).isEqualTo(comprehension); // iter_range
    assertThat(allNodes.get(2).parent().get().expr())
        .isEqualTo(iterRange); // const_expr within iter_range
    assertThat(allNodes.get(3).parent().get().expr()).isEqualTo(comprehension); // accu_init
    assertThat(allNodes.get(4).parent().get().expr()).isEqualTo(comprehension); // loop_condition
    assertThat(allNodes.get(5).parent().get().expr())
        .isEqualTo(loopCondition); // call_expr within loop_condition
    assertThat(allNodes.get(6).parent().get().expr())
        .isEqualTo(loopConditionCallExpr); // ident_expr within call_expr within loop_condition
    assertThat(allNodes.get(7).parent().get().expr()).isEqualTo(comprehension); // loop_step
    assertThat(allNodes.get(8).parent().get().expr())
        .isEqualTo(loopStep); // ident_expr (result) argument within loop_step
    assertThat(allNodes.get(9).parent().get().expr())
        .isEqualTo(loopStep); // ident_expr (i) argument within loop_step
    assertThat(allNodes.get(10).parent().get().expr()).isEqualTo(comprehension); // result
  }

  @Test
  public void comprehension_filterComprehension_allNodesReturned() throws Exception {
    CelCompiler compiler =
        CelCompilerFactory.standardCelCompilerBuilder()
            .setStandardMacros(CelStandardMacro.EXISTS)
            .build();
    CelAbstractSyntaxTree ast = compiler.compile("[true].exists(i, i)").getAst();
    CelNavigableAst navigableAst = CelNavigableAst.fromAst(ast);

    ImmutableList<CelNavigableExpr> allNodes =
        navigableAst
            .getRoot()
            .allNodes()
            .filter(x -> x.getKind().equals(Kind.COMPREHENSION))
            .collect(toImmutableList());

    CelExpr iterRangeConstExpr = CelExpr.ofConstant(2, CelConstant.ofValue(true));
    CelExpr iterRange = CelExpr.ofList(1, ImmutableList.of(iterRangeConstExpr), ImmutableList.of());
    CelExpr accuInit = CelExpr.ofConstant(6, CelConstant.ofValue(false));
    CelExpr loopConditionIdentExpr = CelExpr.ofIdent(7, "@result");
    CelExpr loopConditionCallExpr =
        CelExpr.ofCall(
            8,
            Optional.empty(),
            Operator.LOGICAL_NOT.getFunction(),
            ImmutableList.of(loopConditionIdentExpr));
    CelExpr loopCondition =
        CelExpr.ofCall(
            9,
            Optional.empty(),
            Operator.NOT_STRICTLY_FALSE.getFunction(),
            ImmutableList.of(loopConditionCallExpr));
    CelExpr loopStepResultExpr = CelExpr.ofIdent(10, "@result");
    CelExpr loopStepVarExpr = CelExpr.ofIdent(5, "i");
    CelExpr loopStep =
        CelExpr.ofCall(
            11,
            Optional.empty(),
            Operator.LOGICAL_OR.getFunction(),
            ImmutableList.of(loopStepResultExpr, loopStepVarExpr));
    CelExpr result = CelExpr.ofIdent(12, "@result");
    CelExpr comprehension =
        CelExpr.ofComprehension(
            13, "i", iterRange, "@result", accuInit, loopCondition, loopStep, result);
    assertThat(allNodes).hasSize(1);
    assertThat(allNodes.get(0).expr()).isEqualTo(comprehension);
  }

  @Test
  public void callExpr_preOrder() throws Exception {
    CelCompiler compiler =
        CelCompilerFactory.standardCelCompilerBuilder()
            .addFunctionDeclarations(
                newFunctionDeclaration(
                    "test",
                    newMemberOverload(
                        "test_overload",
                        SimpleType.STRING,
                        SimpleType.STRING,
                        SimpleType.INT,
                        SimpleType.UINT)))
            .build();
    CelAbstractSyntaxTree ast = compiler.compile("'hello'.test(5, 6u)").getAst();
    CelNavigableAst navigableAst = CelNavigableAst.fromAst(ast);

    ImmutableList<CelExpr> allNodes =
        navigableAst
            .getRoot()
            .allNodes(TraversalOrder.PRE_ORDER)
            .map(CelNavigableExpr::expr)
            .collect(toImmutableList());

    CelExpr targetExpr = CelExpr.ofConstant(1, CelConstant.ofValue("hello"));
    CelExpr intArgExpr = CelExpr.ofConstant(3, CelConstant.ofValue(5));
    CelExpr uintArgExpr = CelExpr.ofConstant(4, CelConstant.ofValue(UnsignedLong.valueOf(6)));
    assertThat(allNodes)
        .containsExactly(
            CelExpr.ofCall(
                2, Optional.of(targetExpr), "test", ImmutableList.of(intArgExpr, uintArgExpr)),
            targetExpr,
            intArgExpr,
            uintArgExpr)
        .inOrder();
  }

  @Test
  public void callExpr_postOrder() throws Exception {
    CelCompiler compiler =
        CelCompilerFactory.standardCelCompilerBuilder()
            .addFunctionDeclarations(
                newFunctionDeclaration(
                    "test",
                    newMemberOverload(
                        "test_overload",
                        SimpleType.STRING,
                        SimpleType.STRING,
                        SimpleType.INT,
                        SimpleType.UINT)))
            .build();
    CelAbstractSyntaxTree ast = compiler.compile("'hello'.test(5, 6u)").getAst();
    CelNavigableAst navigableAst = CelNavigableAst.fromAst(ast);

    ImmutableList<CelExpr> allNodes =
        navigableAst
            .getRoot()
            .allNodes(TraversalOrder.POST_ORDER)
            .map(CelNavigableExpr::expr)
            .collect(toImmutableList());

    CelExpr targetExpr = CelExpr.ofConstant(1, CelConstant.ofValue("hello"));
    CelExpr intArgExpr = CelExpr.ofConstant(3, CelConstant.ofValue(5));
    CelExpr uintArgExpr = CelExpr.ofConstant(4, CelConstant.ofValue(UnsignedLong.valueOf(6)));
    assertThat(allNodes)
        .containsExactly(
            targetExpr,
            intArgExpr,
            uintArgExpr,
            CelExpr.ofCall(
                2, Optional.of(targetExpr), "test", ImmutableList.of(intArgExpr, uintArgExpr)))
        .inOrder();
  }

  @Test
  public void callExpr_preOrder_heightSet() throws Exception {
    CelCompiler compiler =
        CelCompilerFactory.standardCelCompilerBuilder()
            .addFunctionDeclarations(
                newFunctionDeclaration(
                    "test",
                    newMemberOverload(
                        "test_overload",
                        SimpleType.STRING,
                        SimpleType.STRING,
                        SimpleType.INT,
                        SimpleType.UINT)))
            .build();
    CelAbstractSyntaxTree ast =
        compiler.compile("('a' + 'b' + 'c' + 'd').test((1 + 2 + 3), 6u)").getAst();
    CelNavigableAst navigableAst = CelNavigableAst.fromAst(ast);

    ImmutableList<Integer> allNodes =
        navigableAst
            .getRoot()
            .allNodes(TraversalOrder.PRE_ORDER)
            .map(CelNavigableExpr::height)
            .collect(toImmutableList());

    assertThat(allNodes).containsExactly(4, 3, 2, 1, 0, 0, 0, 0, 2, 1, 0, 0, 0, 0).inOrder();
  }

  @Test
  public void callExpr_postOrder_heightSet() throws Exception {
    CelCompiler compiler =
        CelCompilerFactory.standardCelCompilerBuilder()
            .addFunctionDeclarations(
                newFunctionDeclaration(
                    "test",
                    newMemberOverload(
                        "test_overload",
                        SimpleType.STRING,
                        SimpleType.STRING,
                        SimpleType.INT,
                        SimpleType.UINT)))
            .build();
    CelAbstractSyntaxTree ast =
        compiler.compile("('a' + 'b' + 'c' + 'd').test((1 + 2 + 3), 6u)").getAst();
    CelNavigableAst navigableAst = CelNavigableAst.fromAst(ast);

    ImmutableList<Integer> allNodes =
        navigableAst
            .getRoot()
            .allNodes(TraversalOrder.POST_ORDER)
            .map(CelNavigableExpr::height)
            .collect(toImmutableList());

    assertThat(allNodes).containsExactly(0, 0, 1, 0, 2, 0, 3, 0, 0, 1, 0, 2, 0, 4).inOrder();
  }

  @Test
  public void callExpr_preOrder_maxIdsSet() throws Exception {
    CelCompiler compiler =
        CelCompilerFactory.standardCelCompilerBuilder()
            .addFunctionDeclarations(
                newFunctionDeclaration(
                    "test",
                    newMemberOverload(
                        "test_overload",
                        SimpleType.STRING,
                        SimpleType.STRING,
                        SimpleType.INT,
                        SimpleType.UINT)))
            .build();
    CelAbstractSyntaxTree ast =
        compiler.compile("('a' + 'b' + 'c' + 'd').test((1 + 2 + 3), 6u)").getAst();
    CelNavigableAst navigableAst = CelNavigableAst.fromAst(ast);

    ImmutableList<Long> allNodes =
        navigableAst
            .getRoot()
            .allNodes(TraversalOrder.PRE_ORDER)
            .map(CelNavigableExpr::maxId)
            .collect(toImmutableList());

    assertThat(allNodes)
        .containsExactly(14L, 7L, 5L, 3L, 1L, 3L, 5L, 7L, 13L, 11L, 9L, 11L, 13L, 14L)
        .inOrder();
  }

  @Test
  public void callExpr_postOrder_maxIdsSet() throws Exception {
    CelCompiler compiler =
        CelCompilerFactory.standardCelCompilerBuilder()
            .addFunctionDeclarations(
                newFunctionDeclaration(
                    "test",
                    newMemberOverload(
                        "test_overload",
                        SimpleType.STRING,
                        SimpleType.STRING,
                        SimpleType.INT,
                        SimpleType.UINT)))
            .build();
    CelAbstractSyntaxTree ast =
        compiler.compile("('a' + 'b' + 'c' + 'd').test((1 + 2 + 3), 6u)").getAst();
    CelNavigableAst navigableAst = CelNavigableAst.fromAst(ast);

    ImmutableList<Long> allNodes =
        navigableAst
            .getRoot()
            .allNodes(TraversalOrder.POST_ORDER)
            .map(CelNavigableExpr::maxId)
            .collect(toImmutableList());

    assertThat(allNodes)
        .containsExactly(1L, 3L, 3L, 5L, 5L, 7L, 7L, 9L, 11L, 11L, 13L, 13L, 14L, 14L)
        .inOrder();
  }

  @Test
  public void list_children_heightSet(@TestParameter TraversalOrder traversalOrder)
      throws Exception {
    CelCompiler compiler =
        CelCompilerFactory.standardCelCompilerBuilder().addVar("a", SimpleType.INT).build();
    CelAbstractSyntaxTree ast = compiler.compile("[1, a, (2 + 2), (3 + 4 + 5)]").getAst();

    CelNavigableAst navigableAst = CelNavigableAst.fromAst(ast);

    ImmutableList<Integer> allNodeHeights =
        navigableAst
            .getRoot()
            .children(traversalOrder)
            .map(CelNavigableExpr::height)
            .collect(toImmutableList());
    assertThat(allNodeHeights).containsExactly(0, 0, 1, 2).inOrder();
  }

  @Test
  public void list_children_maxIdsSet(@TestParameter TraversalOrder traversalOrder)
      throws Exception {
    CelCompiler compiler =
        CelCompilerFactory.standardCelCompilerBuilder().addVar("a", SimpleType.INT).build();
    CelAbstractSyntaxTree ast = compiler.compile("[1, a, (2 + 2), (3 + 4 + 5)]").getAst();

    CelNavigableAst navigableAst = CelNavigableAst.fromAst(ast);

    ImmutableList<Long> allNodeHeights =
        navigableAst
            .getRoot()
            .children(traversalOrder)
            .map(CelNavigableExpr::maxId)
            .collect(toImmutableList());
    assertThat(allNodeHeights).containsExactly(2L, 3L, 6L, 11L).inOrder();
  }

  @Test
  public void maxRecursionLimitReached_throws() throws Exception {
    StringBuilder sb = new StringBuilder();
    sb.append("0");
    for (int i = 1; i < 501; i++) {
      sb.append(" + ").append(i);
    } // 0 + 1 + 2 + 3 + ... 500
    CelCompiler compiler =
        CelCompilerFactory.standardCelCompilerBuilder()
            .setOptions(CelOptions.current().maxParseRecursionDepth(500).build())
            .build();
    CelAbstractSyntaxTree ast = compiler.compile(sb.toString()).getAst();
    CelNavigableAst navigableAst = CelNavigableAst.fromAst(ast);

    IllegalStateException e =
        assertThrows(IllegalStateException.class, () -> navigableAst.getRoot().allNodes());
    assertThat(e).hasMessageThat().contains("Max recursion depth reached.");
  }
}
