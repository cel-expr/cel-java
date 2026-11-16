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

package codelab.solutions

import dev.cel.common.CelAbstractSyntaxTree
import dev.cel.common.CelValidationException
import dev.cel.common.types.SimpleType
import dev.cel.compiler.CelCompilerFactory
import dev.cel.runtime.CelEvaluationException
import dev.cel.runtime.CelRuntimeFactory

/**
 * Exercise1 evaluates a simple literal expression: "Hello, World!"
 *
 * Compile, eval, profit!
 */
internal class Exercise1 {
  /**
   * Compile the input [expression] and produce an AST. This method parses and type-checks the given
   * expression to validate the syntax and type-agreement of the expression.
   *
   * @throws IllegalArgumentException If the expression is malformed due to syntactic or semantic
   *   errors.
   */
  fun compile(expression: String): CelAbstractSyntaxTree {
    // Construct a CelCompiler instance.
    // "String" is the expected output type for the type-checked expression.
    // CelCompiler is immutable and when statically configured can be moved to a companion object
    // or top-level property.
    val celCompiler =
      CelCompilerFactory.standardCelCompilerBuilder().setResultType(SimpleType.STRING).build()

    val parsedAst =
      try {
        // Parse the expression.
        celCompiler.parse(expression).ast
      } catch (e: CelValidationException) {
        // Report syntactic errors, if present.
        throw IllegalArgumentException("Failed to parse expression. Reason: ${e.message}", e)
      }

    return try {
      // Type-check the expression for correctness.
      celCompiler.check(parsedAst).ast
    } catch (e: CelValidationException) {
      // Report semantic errors, if present.
      throw IllegalArgumentException("Failed to type-check expression. Reason: ${e.message}", e)
    }
  }

  /**
   * Evaluates the compiled AST.
   *
   * @throws IllegalArgumentException If the compiled expression in AST fails to evaluate.
   */
  fun eval(ast: CelAbstractSyntaxTree): Any? {
    // Construct a CelRuntime instance.
    // CelRuntime is immutable just like the compiler and can be moved to a companion object or
    // top-level property.
    val celRuntime = CelRuntimeFactory.plannerRuntimeBuilder().build()

    return try {
      // Plan the program.
      val program = celRuntime.createProgram(ast)

      // Evaluate the program without any additional arguments.
      program.eval()
    } catch (e: CelEvaluationException) {
      // Report any evaluation errors, if present.
      throw IllegalArgumentException("Evaluation error has occurred. Reason: ${e.message}", e)
    }
  }
}
