// Copyright 2024 Google LLC
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

import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.errorprone.annotations.CheckReturnValue;
import com.google.errorprone.annotations.DoNotMock;
import dev.cel.common.ast.CelExpr;
import dev.cel.common.ast.CelExpr.ExprKind;
import dev.cel.common.ast.Expression;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * BaseNavigableExpr represents the base navigable expression value with methods to inspect the
 * parent and child expressions.
 *
 * <p>This class is intentionally non-extensible outside of the {@code dev.cel.common.navigation}
 * package.
 */
@DoNotMock("Use CelNavigableExpr or CelNavigableMutableExpr")
@SuppressWarnings("unchecked") // Generic types are properly bound to Expression
public abstract class BaseNavigableExpr<E extends Expression> {

  BaseNavigableExpr() {}

  public abstract E expr();

  public long id() {
    return expr().id();
  }

  public abstract <T extends BaseNavigableExpr<E>> Optional<T> parent();

  /** Represents the count of transitive parents. Depth of an AST's root is 0. */
  public abstract int depth();

  /**
   * Represents the maximum ID of the tree. Note that if the underlying expression tree held by this
   * navigable expression is mutated, its max ID becomes stale and must be recomputed.
   */
  public abstract long maxId();

  /**
   * Represents the maximum count of children from any of its branches. Height of a leaf node is 0.
   * For example, the height of the call node 'func' in expression `(1 + 2 + 3).func(4 + 5)` is 3.
   */
  public abstract int height();

  /**
   * Returns a stream of {@link BaseNavigableExpr} collected from the current node down to the last
   * leaf-level member using post-order traversal.
   */
  public <T extends BaseNavigableExpr<E>> Stream<T> allNodes() {
    return allNodes(TraversalOrder.POST_ORDER);
  }

  /**
   * Returns a stream of {@link BaseNavigableExpr} collected from the current node down to the last
   * leaf-level member using the specified traversal order.
   */
  public <T extends BaseNavigableExpr<E>> Stream<T> allNodes(TraversalOrder traversalOrder) {
    return CelNavigableExprVisitor.collect((T) this, traversalOrder);
  }

  /**
   * Returns a stream of {@link BaseNavigableExpr} collected down to the last leaf-level member
   * using post-order traversal.
   */
  public <T extends BaseNavigableExpr<E>> Stream<T> descendants() {
    return descendants(TraversalOrder.POST_ORDER);
  }

  /**
   * Returns a stream of {@link BaseNavigableExpr} collected down to the last leaf-level member
   * using the specified traversal order.
   */
  public <T extends BaseNavigableExpr<E>> Stream<T> descendants(TraversalOrder traversalOrder) {
    return CelNavigableExprVisitor.collect((T) this, traversalOrder)
        .filter(node -> node.depth() > this.depth());
  }

  /**
   * Returns a stream of {@link BaseNavigableExpr} collected from its immediate children using
   * post-order traversal.
   */
  public <T extends BaseNavigableExpr<E>> Stream<T> children() {
    return children(TraversalOrder.POST_ORDER);
  }

  /**
   * Returns a stream of {@link BaseNavigableExpr} collected from its immediate children using the
   * specified traversal order.
   */
  public <T extends BaseNavigableExpr<E>> Stream<T> children(TraversalOrder traversalOrder) {
    return CelNavigableExprVisitor.collect((T) this, this.depth() + 1, traversalOrder)
        .filter(node -> node.depth() > this.depth());
  }

  /** Returns the underlying kind of the {@link CelExpr}. */
  public ExprKind.Kind getKind() {
    return expr().getKind();
  }

  public abstract <T extends BaseNavigableExpr<E>> Builder<E, T> builderFromInstance();

  interface Builder<E extends Expression, T extends BaseNavigableExpr<E>> {

    E expr();

    int depth();

    default ExprKind.Kind getKind() {
      return expr().getKind();
    }

    @CanIgnoreReturnValue
    Builder<E, T> setExpr(E value);

    @CanIgnoreReturnValue
    Builder<E, T> setParent(T value);

    @CanIgnoreReturnValue
    Builder<E, T> setDepth(int value);

    @CanIgnoreReturnValue
    Builder<E, T> setHeight(int value);

    @CanIgnoreReturnValue
    Builder<E, T> setMaxId(long value);

    @CheckReturnValue
    T build();
  }
}
