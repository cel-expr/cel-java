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

package dev.cel.common.ast;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.annotations.Internal;
import dev.cel.common.ast.CelExpr.CelCall;
import dev.cel.common.ast.CelExpr.CelIdent;
import dev.cel.common.ast.CelExpr.ExprKind.Kind;
import java.util.Optional;

/**
 * Represents a {@code cel.@block} expression.
 *
 * <p>CEL Block is used by the CSE (Common Subexpression Elimination) optimizer to hoist common
 * subexpressions into an evaluated block.
 */
@Internal
public final class CelBlock {
  public static final String FUNCTION_NAME = "cel.@block";
  public static final String INDEX_PREFIX = "@index";

  private final CelExpr blockExpr;

  private CelBlock(CelExpr blockExpr) {
    this.blockExpr = blockExpr;
  }

  public ImmutableList<CelExpr> indices() {
    return blockExpr.call().args().get(0).list().elements();
  }

  public CelExpr result() {
    return blockExpr.call().args().get(1);
  }

  public CelExpr expr() {
    return blockExpr;
  }

  /**
   * Extracts a {@link CelBlock} from the given AST.
   *
   * <p>Enforces the contract that {@code cel.@block} must only appear exactly once and at the root
   * of the AST.
   *
   * @throws IllegalArgumentException if the block is malformed or its indices are invalid.
   */
  public static Optional<CelBlock> extract(CelAbstractSyntaxTree ast) {
    CelExpr root = ast.getExpr();
    if (!isBlockCall(root)) {
      new BlockValidator(root).validate(root, 0);
      return Optional.empty();
    }

    return Optional.of(fromExpr(root));
  }

  private static boolean isBlockCall(CelExpr expr) {
    return expr.getKind().equals(Kind.CALL) && expr.call().function().equals(FUNCTION_NAME);
  }

  /**
   * Constructs a {@link CelBlock} from a {@link CelExpr}.
   *
   * @throws IllegalArgumentException if the expression is not a valid block.
   */
  private static CelBlock fromExpr(CelExpr expr) {
    Preconditions.checkArgument(
        expr.exprKind().getKind() == CelExpr.ExprKind.Kind.CALL,
        "Expected cel.@block to be a call expression");
    Preconditions.checkArgument(
        expr.call().function().equals(FUNCTION_NAME), "Expected function to be cel.@block");
    Preconditions.checkArgument(
        expr.call().args().size() == 2, "Expected exactly 2 arguments for cel.@block");
    Preconditions.checkArgument(
        expr.call().args().get(0).exprKind().getKind() == CelExpr.ExprKind.Kind.LIST,
        "Expected first argument of cel.@block to be a list");
    Preconditions.checkArgument(
        !expr.call().target().isPresent(), "Expected cel.@block to be a global call");

    CelBlock block = new CelBlock(expr);
    BlockValidator validator = new BlockValidator(expr);

    // Assert correctness on block indices used in subexpressions
    ImmutableList<CelExpr> subexprs = block.indices();
    for (int i = 0; i < subexprs.size(); i++) {
      validator.validate(subexprs.get(i), i);
    }

    // Assert correctness on block indices used in block result
    Preconditions.checkArgument(
        validator.validate(block.result(), subexprs.size()),
        "Expected at least one reference of index in cel.block result");

    return block;
  }

  private static final class BlockValidator extends CelExprVisitor {
    private final CelExpr root;
    private int maxIndexValue;
    private boolean hasBlockIndex;

    private boolean validate(CelExpr expr, int maxIndexValue) {
      this.maxIndexValue = maxIndexValue;
      this.hasBlockIndex = false;
      visit(expr);
      return hasBlockIndex;
    }

    @Override
    public void visit(CelExpr expr) {
      if (!expr.getKind().equals(Kind.NOT_SET)) {
        super.visit(expr);
      }
    }

    @Override
    protected void visit(CelExpr expr, CelCall call) {
      if (call.function().equals(FUNCTION_NAME)) {
        throw new IllegalArgumentException(
            isBlockCall(root)
                ? "Expected 1 cel.block function to be present but found 2"
                : "Expected cel.block to be present at root");
      }
      super.visit(expr, call);
    }

    @Override
    protected void visit(CelExpr expr, CelIdent ident) {
      if (ident.name().startsWith(INDEX_PREFIX)) {
        hasBlockIndex = true;
        int indexValue = Integer.parseInt(ident.name().substring(INDEX_PREFIX.length()));
        Preconditions.checkArgument(
            indexValue >= 0 && indexValue < maxIndexValue,
            "Illegal block index found. The index value must be less than %s. Expr: %s",
            maxIndexValue,
            root);
      }
    }

    private BlockValidator(CelExpr root) {
      this.root = root;
    }
  }
}
