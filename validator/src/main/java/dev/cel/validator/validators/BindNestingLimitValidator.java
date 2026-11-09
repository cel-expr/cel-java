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

package dev.cel.validator.validators;

import static com.google.common.base.Preconditions.checkArgument;

import dev.cel.bundle.Cel;
import dev.cel.common.ast.CelConstant;
import dev.cel.common.ast.CelExpr.CelComprehension;
import dev.cel.common.ast.CelExpr.ExprKind;
import dev.cel.common.navigation.CelNavigableAst;
import dev.cel.common.navigation.CelNavigableExpr;
import dev.cel.validator.CelAstValidator;

/** Enforces that {@code cel.bind} macro nesting does not exceed the configured limit. */
public final class BindNestingLimitValidator implements CelAstValidator {
  private static final String UNUSED_ITER_VAR = "#unused";
  private static final CelConstant FALSE_CONSTANT = CelConstant.ofValue(false);

  private final int nestingLimit;

  /**
   * Constructs a new instance of {@link BindNestingLimitValidator} with {@code maxNesting} as its
   * limit. A limit of 0 disallows all {@code cel.bind} calls.
   */
  public static BindNestingLimitValidator newInstance(int maxNesting) {
    checkArgument(maxNesting >= 0);
    return new BindNestingLimitValidator(maxNesting);
  }

  @Override
  public void validate(CelNavigableAst navigableAst, Cel cel, IssuesFactory issuesFactory) {
    navigableAst
        .getRoot()
        .allNodes()
        .filter(BindNestingLimitValidator::isCelBind)
        .filter(node -> nestingLevel(node) > nestingLimit)
        .forEach(
            node ->
                issuesFactory.addError(
                    node.id(),
                    String.format(
                        "cel.bind nesting exceeds the configured limit: %d.", nestingLimit)));
  }

  private static boolean isCelBind(CelNavigableExpr node) {
    if (!node.getKind().equals(ExprKind.Kind.COMPREHENSION)) {
      return false;
    }
    CelComprehension compre = node.expr().comprehension();
    // listOrDefault() returns an empty CelList for non-LIST nodes, so check Kind.LIST explicitly.
    return compre.iterRange().getKind().equals(ExprKind.Kind.LIST)
        && compre.iterRange().list().elements().isEmpty()
        && compre.iterVar().equals(UNUSED_ITER_VAR)
        && compre.loopCondition().constantOrDefault().equals(FALSE_CONSTANT)
        && compre.loopStep().identOrDefault().name().equals(compre.accuVar());
  }

  private static int nestingLevel(CelNavigableExpr node) {
    int count = 1;
    while (node.parent().isPresent()) {
      node = node.parent().get();
      if (isCelBind(node)) {
        count++;
      }
    }
    return count;
  }

  private BindNestingLimitValidator(int maxNesting) {
    this.nestingLimit = maxNesting;
  }
}
