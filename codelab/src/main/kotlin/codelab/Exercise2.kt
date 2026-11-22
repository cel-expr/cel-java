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

package codelab

import dev.cel.common.CelAbstractSyntaxTree
import dev.cel.common.types.CelType

/**
 * Exercise2 shows how to declare and use variables in expressions through two examples:
 * - Given a user supplied integer value, test whether the value is negative
 * - Given a request of type [com.google.rpc.context.AttributeContext.Request] determine whether a
 *   specific auth claim is set.
 */
internal class Exercise2 {

  /**
   * Compiles the input expression with provided variable information.
   *
   * @throws IllegalArgumentException If the expression is malformed due to syntactic or semantic
   *   errors.
   */
  fun compile(
    expression: String,
    variableName: String,
    variableType: CelType,
  ): CelAbstractSyntaxTree {
    throw UnsupportedOperationException("To be implemented")
  }

  /**
   * Evaluates the compiled AST with the user provided parameter values.
   *
   * @throws IllegalArgumentException If the compiled expression in AST fails to evaluate.
   */
  fun eval(ast: CelAbstractSyntaxTree, parameterValues: Map<String, *>): Any? {
    throw UnsupportedOperationException("To be implemented")
  }
}
