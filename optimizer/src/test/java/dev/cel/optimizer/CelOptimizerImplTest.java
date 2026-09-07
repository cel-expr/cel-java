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

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableList;
import dev.cel.bundle.Cel;
import dev.cel.bundle.CelFactory;
import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.CelOptions;
import dev.cel.common.CelSource;
import dev.cel.common.CelValidationException;
import dev.cel.common.ast.CelConstant;
import dev.cel.common.ast.CelExpr;
import dev.cel.optimizer.CelAstOptimizer.OptimizationResult;
import dev.cel.parser.CelStandardMacro;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class CelOptimizerImplTest {

  private static final Cel CEL =
      CelFactory.standardCelBuilder()
          .setOptions(CelOptions.current().populateMacroCalls(true).build())
          .setStandardMacros(CelStandardMacro.STANDARD_MACROS)
          .build();

  private final List<String> events = new ArrayList<>();

  private final CelOptimizerListener listener =
      new CelOptimizerListener() {
        @Override
        public void onOptimizationStart(CelAbstractSyntaxTree ast) {
          events.add("start");
        }

        @Override
        public void onPassStart(CelAstOptimizer optimizer, CelAbstractSyntaxTree ast) {
          events.add("pass_start");
        }

        @Override
        public void onPassEnd(
            CelAstOptimizer optimizer,
            CelAbstractSyntaxTree preAst,
            CelAbstractSyntaxTree optimizedAst) {
          events.add("pass_end");
        }

        @Override
        public void onOptimizationEnd(
            CelAbstractSyntaxTree initialAst, CelAbstractSyntaxTree finalAst) {
          events.add("end");
        }

        @Override
        public void onPassFailure(
            CelAstOptimizer optimizer, CelAbstractSyntaxTree ast, Exception failure) {
          events.add("pass_failure");
        }
      };

  @Test
  public void constructCelOptimizer_success() {
    CelOptimizer celOptimizer =
        CelOptimizerImpl.newBuilder(CEL)
            .addAstOptimizers(
                (ast, cel) ->
                    // no-op
                    OptimizationResult.create(ast))
            .build();

    assertThat(celOptimizer).isNotNull();
    assertThat(celOptimizer).isInstanceOf(CelOptimizerImpl.class);
  }

  @Test
  public void astOptimizers_invokedInOrder() throws Exception {
    List<Integer> list = new ArrayList<>();

    CelOptimizer celOptimizer =
        CelOptimizerImpl.newBuilder(CEL)
            .addAstOptimizers(
                (ast, cel) -> {
                  list.add(1);
                  return OptimizationResult.create(ast);
                })
            .addAstOptimizers(
                (ast, cel) -> {
                  list.add(2);
                  return OptimizationResult.create(ast);
                })
            .addAstOptimizers(
                (ast, cel) -> {
                  list.add(3);
                  return OptimizationResult.create(ast);
                })
            .build();

    CelAbstractSyntaxTree ast = celOptimizer.optimize(CEL.compile("'hello world'").getAst());

    assertThat(ast).isNotNull();
    assertThat(list).containsExactly(1, 2, 3).inOrder();
  }

  @Test
  public void optimizer_whenAstOptimizerThrows_throwsException() {
    CelOptimizer celOptimizer =
        CelOptimizerImpl.newBuilder(CEL)
            .addAstOptimizers(
                (navigableAst, cel) -> {
                  throw new IllegalArgumentException("Test exception");
                })
            .build();

    CelOptimizationException e =
        assertThrows(
            CelOptimizationException.class,
            () -> celOptimizer.optimize(CEL.compile("'hello world'").getAst()));
    assertThat(e).hasMessageThat().isEqualTo("Optimization failure: Test exception");
    assertThat(e).hasCauseThat().isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  public void parsedAst_throwsException() {
    CelOptimizer celOptimizer = CelOptimizerImpl.newBuilder(CEL).build();

    IllegalArgumentException e =
        assertThrows(
            IllegalArgumentException.class,
            () -> celOptimizer.optimize(CEL.parse("'test'").getAst()));
    assertThat(e).hasMessageThat().contains("AST must be type-checked.");
  }

  @Test
  public void optimizedAst_failsToTypeCheck_throwsException() {
    CelOptimizer celOptimizer =
        CelOptimizerImpl.newBuilder(CEL)
            .addAstOptimizers(
                (navigableAst, cel) ->
                    OptimizationResult.create(
                        CelAbstractSyntaxTree.newParsedAst(
                            CelExpr.ofIdent(1, "undeclared_ident"),
                            CelSource.newBuilder().build())))
            .build();

    CelOptimizationException e =
        assertThrows(
            CelOptimizationException.class,
            () -> celOptimizer.optimize(CEL.compile("'hello world'").getAst()));

    assertThat(e)
        .hasMessageThat()
        .contains(
            "Optimized AST failed to type-check: ERROR: :1:1: undeclared reference to"
                + " 'undeclared_ident' (in container '')");
    assertThat(e).hasCauseThat().isInstanceOf(CelValidationException.class);
  }

  @Test
  public void optimize_duplicateExprId_throwsException() {
    CelOptimizer celOptimizer =
        CelOptimizerImpl.newBuilder(CEL)
            .addAstOptimizers(
                (navigableAst, cel) ->
                    OptimizationResult.create(
                        CelAbstractSyntaxTree.newParsedAst(
                            CelExpr.ofCall(
                                1,
                                Optional.empty(),
                                "_+_",
                                ImmutableList.of(
                                    CelExpr.ofConstant(1, CelConstant.ofValue(1L)),
                                    CelExpr.ofConstant(2, CelConstant.ofValue(2L)))),
                            CelSource.newBuilder().build())))
            .build();

    CelOptimizationException e =
        assertThrows(
            CelOptimizationException.class,
            () -> celOptimizer.optimize(CEL.compile("1 + 2").getAst()));

    assertThat(e)
        .hasMessageThat()
        .isEqualTo("Optimization failure: Duplicate expr ID 1 detected in the AST.");
    assertThat(e).hasCauseThat().isInstanceOf(IllegalStateException.class);
  }

  @Test
  public void optimize_macroCallRootIdNonZero_throwsException() {
    CelOptimizer celOptimizer =
        CelOptimizerImpl.newBuilder(CEL)
            .addAstOptimizers(
                (navigableAst, cel) ->
                    OptimizationResult.create(
                        CelAbstractSyntaxTree.newParsedAst(
                            CelExpr.ofConstant(1, CelConstant.ofValue(1L)),
                            CelSource.newBuilder()
                                .addMacroCalls(
                                    1L,
                                    CelExpr.ofCall(
                                        10L,
                                        Optional.empty(),
                                        "has",
                                        ImmutableList.of(
                                            CelExpr.ofConstant(1L, CelConstant.ofValue(1L)))))
                                .build())))
            .build();

    CelOptimizationException e =
        assertThrows(
            CelOptimizationException.class, () -> celOptimizer.optimize(CEL.compile("1").getAst()));

    assertThat(e)
        .hasMessageThat()
        .isEqualTo("Optimization failure: Expected macro call root ID to be 0, but was 10.");
    assertThat(e).hasCauseThat().isInstanceOf(IllegalStateException.class);
  }

  @Test
  public void optimize_macroCallKindMismatch_throwsException() {
    CelOptimizer celOptimizer =
        CelOptimizerImpl.newBuilder(CEL)
            .addAstOptimizers(
                (navigableAst, cel) ->
                    OptimizationResult.create(
                        CelAbstractSyntaxTree.newParsedAst(
                            CelExpr.ofConstant(1, CelConstant.ofValue(1L)),
                            CelSource.newBuilder()
                                .addMacroCalls(
                                    1L,
                                    CelExpr.ofCall(
                                        0L,
                                        Optional.empty(),
                                        "has",
                                        ImmutableList.of(CelExpr.ofIdent(1L, "x"))))
                                .build())))
            .build();

    CelOptimizationException e =
        assertThrows(
            CelOptimizationException.class, () -> celOptimizer.optimize(CEL.compile("1").getAst()));

    assertThat(e)
        .hasMessageThat()
        .isEqualTo(
            "Optimization failure: Macro call node 1 kind mismatch: expected CONSTANT (from AST),"
                + " but was IDENT (in macro call).");
    assertThat(e).hasCauseThat().isInstanceOf(IllegalStateException.class);
  }

  @Test
  public void optimize_macroCallComprehensionKindNotSetMismatch_throwsException() throws Exception {
    CelAbstractSyntaxTree astWithComprehension = CEL.compile("[1].all(x, x > 0)").getAst();
    long compId = astWithComprehension.getExpr().id();

    CelOptimizer celOptimizer =
        CelOptimizerImpl.newBuilder(CEL)
            .addAstOptimizers(
                (navigableAst, cel) ->
                    OptimizationResult.create(
                        CelAbstractSyntaxTree.newParsedAst(
                            astWithComprehension.getExpr(),
                            CelSource.newBuilder()
                                .addMacroCalls(
                                    compId,
                                    CelExpr.ofCall(
                                        0L,
                                        Optional.empty(),
                                        "all",
                                        ImmutableList.of(
                                            CelExpr.ofIdent(compId, "not_set_expected"))))
                                .build())))
            .build();

    CelOptimizationException e =
        assertThrows(
            CelOptimizationException.class, () -> celOptimizer.optimize(astWithComprehension));

    assertThat(e)
        .hasMessageThat()
        .isEqualTo(
            String.format(
                "Optimization failure: Expected macro call node %d to be NOT_SET for comprehension,"
                    + " but was IDENT.",
                compId));
    assertThat(e).hasCauseThat().isInstanceOf(IllegalStateException.class);
  }

  @Test
  public void optimize_macroCallComprehensionKindNotSet_success() throws Exception {
    CelAbstractSyntaxTree astWithComprehension = CEL.compile("[1].all(x, x > 0)").getAst();
    long compId = astWithComprehension.getExpr().id();

    CelOptimizer celOptimizer =
        CelOptimizerImpl.newBuilder(CEL)
            .addAstOptimizers(
                (navigableAst, cel) ->
                    OptimizationResult.create(
                        CelAbstractSyntaxTree.newParsedAst(
                            astWithComprehension.getExpr(),
                            CelSource.newBuilder()
                                .addMacroCalls(
                                    compId,
                                    CelExpr.ofCall(
                                        0L,
                                        Optional.empty(),
                                        "all",
                                        ImmutableList.of(CelExpr.ofNotSet(compId))))
                                .build())))
            .build();

    CelAbstractSyntaxTree optimizedAst = celOptimizer.optimize(astWithComprehension);

    assertThat(optimizedAst).isNotNull();
  }

  @Test
  public void optimize_validMacroCalls_success() throws Exception {
    CelAbstractSyntaxTree ast = CEL.compile("[1, 2, 3].all(x, x > 0)").getAst();

    CelOptimizer celOptimizer =
        CelOptimizerImpl.newBuilder(CEL)
            .addAstOptimizers((navigableAst, cel) -> OptimizationResult.create(navigableAst))
            .build();

    CelAbstractSyntaxTree optimizedAst = celOptimizer.optimize(ast);

    assertThat(optimizedAst).isNotNull();
    assertThat(optimizedAst.getSource().getMacroCalls()).hasSize(1);
  }

  @Test
  public void optimize_withListener_invokesListenerMethods() throws Exception {
    CelOptimizer celOptimizer =
        CelOptimizerImpl.newBuilder(CEL)
            .addAstOptimizers((navigableAst, cel) -> OptimizationResult.create(navigableAst))
            .addOptimizerListeners(listener)
            .build();

    CelAbstractSyntaxTree ast = CEL.compile("'hello world'").getAst();
    CelAbstractSyntaxTree unused = celOptimizer.optimize(ast);

    assertThat(events).containsExactly("start", "pass_start", "pass_end", "end").inOrder();
  }

  @Test
  public void optimize_withListener_onPassFailure_invokesListenerMethods() throws Exception {
    CelOptimizer celOptimizer =
        CelOptimizerImpl.newBuilder(CEL)
            .addAstOptimizers(
                (navigableAst, cel) -> {
                  throw new RuntimeException("Test failure");
                })
            .addOptimizerListeners(listener)
            .build();

    CelAbstractSyntaxTree ast = CEL.compile("'hello world'").getAst();
    assertThrows(CelOptimizationException.class, () -> celOptimizer.optimize(ast));

    assertThat(events).containsExactly("start", "pass_start", "pass_failure", "end").inOrder();
  }
}
