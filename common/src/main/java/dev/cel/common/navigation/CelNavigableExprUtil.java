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

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.errorprone.annotations.CheckReturnValue;
import dev.cel.common.ast.CelExpr.ExprKind.Kind;
import dev.cel.common.ast.Expression;
import java.util.Collection;
import java.util.Collections;
import java.util.Optional;

/** Utility class for common AST navigation and scoping inspections on {@link BaseNavigableExpr}. */
@CheckReturnValue
public final class CelNavigableExprUtil {

  /**
   * Returns true if {@code variableName} is in scope and shadowed by an enclosing comprehension
   * above {@code expr}.
   *
   * <p>A variable is shadowed at {@code expr} if an ancestor comprehension declares it as an
   * iteration variable ({@code iterVar}, {@code iterVar2}) or accumulator variable ({@code
   * accuVar}) and {@code expr} resides within a branch where that variable is active:
   *
   * <ul>
   *   <li>In {@code loopCondition} and {@code loopStep}: {@code iterVar}, {@code iterVar2}, and
   *       {@code accuVar} are in scope.
   *   <li>In {@code result}: only {@code accuVar} is in scope ({@code iterVar} and {@code iterVar2}
   *       have fallen out of scope).
   *   <li>In {@code iterRange} and {@code accuInit}: none of the comprehension variables are in
   *       scope.
   * </ul>
   *
   * <p>For example, in the expression:
   *
   * <pre>{@code
   * [1, 2].all(x, x > 0)
   * }</pre>
   *
   * <ul>
   *   <li>At {@code x} in {@code x > 0}: {@code isVariableShadowed(x, "x")} is {@code true}.
   *   <li>At the list {@code [1, 2]}: {@code isVariableShadowed(list, "x")} is {@code false}.
   * </ul>
   */
  public static boolean isVariableShadowed(BaseNavigableExpr<?> expr, String variableName) {
    return areVariablesShadowed(expr, Collections.singleton(variableName));
  }

  /**
   * Returns true if any of {@code variableNames} is in scope and shadowed by an enclosing
   * comprehension above {@code expr}.
   *
   * <p>For example, in the nested comprehension expression:
   *
   * <pre>{@code
   * [1, 2].all(x, [3, 4].all(y, x > 0 && y > 0))
   * }</pre>
   *
   * At {@code y > 0}, {@code areVariablesShadowed(node, ImmutableSet.of("x", "z"))} is {@code true}
   * because {@code x} is in scope from the outer comprehension.
   */
  @SuppressWarnings("ReferenceEquality") // Required to disambiguate child branches
  public static boolean areVariablesShadowed(
      BaseNavigableExpr<?> expr, Collection<String> variableNames) {
    checkNotNull(expr);
    checkNotNull(variableNames);
    if (variableNames.isEmpty()) {
      return false;
    }
    BaseNavigableExpr<?> curr = expr;
    Optional<? extends BaseNavigableExpr<?>> maybeParent = curr.parent();
    while (maybeParent.isPresent()) {
      BaseNavigableExpr<?> parent = maybeParent.get();
      if (parent.getKind() == Kind.COMPREHENSION) {
        Expression.Comprehension<?> comp = parent.expr().comprehension();
        Expression currExpr = curr.expr();

        if (currExpr != comp.iterRange() && currExpr != comp.accuInit()) {
          if (currExpr == comp.result()) {
            if (variableNames.contains(comp.accuVar())) {
              return true;
            }
          } else {
            if (variableNames.contains(comp.iterVar())
                || variableNames.contains(comp.iterVar2())
                || variableNames.contains(comp.accuVar())) {
              return true;
            }
          }
        }
      }
      curr = parent;
      maybeParent = parent.parent();
    }
    return false;
  }

  /**
   * Returns true if {@code expr} is an {@code IDENT} node that references a variable declared by an
   * enclosing comprehension.
   *
   * <p>For example, in the expression:
   *
   * <pre>{@code
   * [a].all(x, x > a)
   * }</pre>
   *
   * <ul>
   *   <li>At identifier {@code x}: {@code isComprehensionVariable(x)} is {@code true}.
   *   <li>At identifier {@code a}: {@code isComprehensionVariable(a)} is {@code false}.
   * </ul>
   */
  public static boolean isComprehensionVariable(BaseNavigableExpr<?> expr) {
    checkNotNull(expr);
    return expr.getKind() == Kind.IDENT
        && areVariablesShadowed(expr, Collections.singleton(expr.expr().ident().name()));
  }

  /**
   * Returns true if {@code expr} or any identifier within {@code expr} references a variable
   * declared by an enclosing comprehension.
   *
   * <p>For example, in the expression:
   *
   * <pre>{@code
   * [a].all(x, x > a)
   * }</pre>
   *
   * <ul>
   *   <li>At the subtree {@code x > a}: {@code hasComprehensionVariable(subtree)} is {@code true}
   *       because {@code x} is a comprehension variable.
   *   <li>At the subtree {@code [a]}: {@code hasComprehensionVariable(iterRange)} is {@code false}.
   * </ul>
   */
  public static boolean hasComprehensionVariable(BaseNavigableExpr<?> expr) {
    checkNotNull(expr);
    return expr.allNodes()
        .filter(node -> node.getKind() == Kind.IDENT)
        .anyMatch(CelNavigableExprUtil::isComprehensionVariable);
  }

  private CelNavigableExprUtil() {}
}
