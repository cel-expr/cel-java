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

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.collect.ImmutableSet;
import dev.cel.bundle.Cel;
import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.CelValidationException;
import dev.cel.common.ast.CelExpr;
import dev.cel.common.ast.CelExpr.ExprKind.Kind;
import dev.cel.common.navigation.CelNavigableAst;
import dev.cel.common.navigation.CelNavigableExpr;
import dev.cel.optimizer.CelAstOptimizer.OptimizationResult;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

final class CelOptimizerImpl implements CelOptimizer {
  private final Cel cel;
  private final ImmutableSet<CelAstOptimizer> astOptimizers;

  CelOptimizerImpl(Cel cel, ImmutableSet<CelAstOptimizer> astOptimizers) {
    this.cel = cel;
    this.astOptimizers = astOptimizers;
  }

  @Override
  @SuppressWarnings("ReferenceEquality")
  public CelAbstractSyntaxTree optimize(CelAbstractSyntaxTree ast) throws CelOptimizationException {
    if (!ast.isChecked()) {
      throw new IllegalArgumentException("AST must be type-checked.");
    }

    Cel celOptimizerEnv = cel;
    CelAbstractSyntaxTree optimizedAst = ast;
    try {
      for (CelAstOptimizer optimizer : astOptimizers) {
        OptimizationResult result = optimizer.optimize(optimizedAst, celOptimizerEnv);
        if (result.optimizedAst() != optimizedAst) {
          if (!result.newFunctionDecls().isEmpty() || !result.newVarDecls().isEmpty()) {
            celOptimizerEnv =
                celOptimizerEnv
                    .toCelBuilder()
                    .addVarDeclarations(result.newVarDecls())
                    .addFunctionDeclarations(result.newFunctionDecls())
                    .build();
          }
          optimizedAst = celOptimizerEnv.check(result.optimizedAst()).getAst();
          assertAstIdCorrectness(optimizedAst);
        }
      }
    } catch (CelValidationException e) {
      throw new CelOptimizationException(
          "Optimized AST failed to type-check: " + e.getMessage(), e);
    } catch (RuntimeException e) {
      throw new CelOptimizationException("Optimization failure: " + e.getMessage(), e);
    }

    return optimizedAst;
  }

  private static void assertAstIdCorrectness(CelAbstractSyntaxTree ast) {
    Map<Long, CelExpr> allExprs = new HashMap<>();
    CelNavigableAst.fromAst(ast)
        .getRoot()
        .allNodes()
        .forEach(
            navExpr -> {
              CelExpr expr = navExpr.expr();
              CelExpr existing = allExprs.put(expr.id(), expr);
              if (existing != null) {
                throw new IllegalStateException(
                    String.format("Duplicate expr ID %d detected in the AST.", expr.id()));
              }
            });

    for (CelExpr macroCall : ast.getSource().getMacroCalls().values()) {
      if (macroCall.id() != 0) {
        throw new IllegalStateException(
            String.format("Expected macro call root ID to be 0, but was %d.", macroCall.id()));
      }
      CelNavigableExpr.fromExpr(macroCall)
          .descendants()
          .forEach(
              navExpr -> {
                CelExpr macroExpr = navExpr.expr();
                CelExpr astExpr = allExprs.get(macroExpr.id());
                // A node may not exist in the AST if it is a synthetic macro node or was eliminated
                // during optimization passes.
                if (astExpr == null) {
                  return;
                }

                if (astExpr.exprKind().getKind().equals(Kind.COMPREHENSION)) {
                  if (!macroExpr.exprKind().getKind().equals(Kind.NOT_SET)) {
                    throw new IllegalStateException(
                        String.format(
                            "Expected macro call node %d to be NOT_SET for comprehension, but"
                                + " was %s.",
                            macroExpr.id(), macroExpr.exprKind().getKind()));
                  }
                } else if (!macroExpr.exprKind().getKind().equals(astExpr.exprKind().getKind())) {
                  throw new IllegalStateException(
                      String.format(
                          "Macro call node %d kind mismatch: expected %s (from AST), but was %s"
                              + " (in macro call).",
                          macroExpr.id(),
                          astExpr.exprKind().getKind(),
                          macroExpr.exprKind().getKind()));
                }
              });
    }
  }

  /** Create a new builder for constructing a {@link CelOptimizer} instance. */
  static CelOptimizerImpl.Builder newBuilder(Cel cel) {
    return new CelOptimizerImpl.Builder(cel);
  }

  /** Builder class for {@link CelOptimizerImpl}. */
  static final class Builder implements CelOptimizerBuilder {
    private final Cel cel;
    private final ImmutableSet.Builder<CelAstOptimizer> astOptimizers;

    private Builder(Cel cel) {
      this.cel = cel;
      this.astOptimizers = ImmutableSet.builder();
    }

    @Override
    public CelOptimizerBuilder addAstOptimizers(CelAstOptimizer... astOptimizers) {
      checkNotNull(astOptimizers);
      return addAstOptimizers(Arrays.asList(astOptimizers));
    }

    @Override
    public CelOptimizerBuilder addAstOptimizers(Iterable<CelAstOptimizer> astOptimizers) {
      checkNotNull(astOptimizers);
      this.astOptimizers.addAll(astOptimizers);
      return this;
    }

    @Override
    public CelOptimizer build() {
      return new CelOptimizerImpl(cel, astOptimizers.build());
    }
  }
}
