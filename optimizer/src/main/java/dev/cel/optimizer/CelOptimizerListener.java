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

package dev.cel.optimizer;

import javax.annotation.concurrent.ThreadSafe;
import dev.cel.common.CelAbstractSyntaxTree;

/**
 * Listener interface for observing the execution lifecycle of {@link CelOptimizer}.
 *
 * <p>Implementations must be thread-safe.
 */
@ThreadSafe
public interface CelOptimizerListener {
  /**
   * Invoked before the optimization pipeline begins.
   *
   * @param ast the initial AST to be optimized.
   */
  default void onOptimizationStart(CelAbstractSyntaxTree ast) {}

  /**
   * Invoked before a specific {@link CelAstOptimizer} pass executes.
   *
   * @param optimizer the optimizer pass that is about to execute.
   * @param ast the initial AST that is about to be optimized.
   */
  default void onPassStart(CelAstOptimizer optimizer, CelAbstractSyntaxTree ast) {}

  /**
   * Invoked after a specific {@link CelAstOptimizer} pass completes successfully.
   *
   * @param optimizer the optimizer pass that just completed.
   * @param preAst the initial AST that was passed to the optimizer pass.
   * @param optimizedAst the AST after the optimizer pass completed.
   */
  default void onPassEnd(
      CelAstOptimizer optimizer,
      CelAbstractSyntaxTree preAst,
      CelAbstractSyntaxTree optimizedAst) {}

  /**
   * Invoked if an optimizer pass throws an unhandled exception.
   *
   * @param optimizer the optimizer pass that threw the exception.
   * @param ast the initial AST that was passed to the optimizer pass.
   * @param failure the exception that was thrown by the optimizer pass.
   */
  default void onPassFailure(
      CelAstOptimizer optimizer, CelAbstractSyntaxTree ast, Exception failure) {}

  /**
   * Invoked after all optimization passes and final type-checks complete.
   *
   * @param initialAst the initial AST that was passed to the optimizer.
   * @param finalAst the final AST after all optimization passes.
   */
  default void onOptimizationEnd(
      CelAbstractSyntaxTree initialAst, CelAbstractSyntaxTree finalAst) {}
}
