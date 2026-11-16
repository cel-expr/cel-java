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

import com.google.rpc.context.AttributeContext
import dev.cel.common.CelAbstractSyntaxTree
import dev.cel.common.CelValidationException
import dev.cel.common.types.CelType
import dev.cel.common.types.SimpleType
import dev.cel.compiler.CelCompilerFactory
import dev.cel.runtime.CelEvaluationException
import dev.cel.runtime.CelRuntimeFactory

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
    // Compile (parse + type-check) the expression.
    // CelCompiler is immutable and when statically configured can be moved to a companion object
    // or top-level property.
    val celCompiler =
      CelCompilerFactory.standardCelCompilerBuilder()
        .addVar(variableName, variableType)
        .addMessageTypes(AttributeContext.Request.getDescriptor())
        .setResultType(SimpleType.BOOL)
        .build()
    return try {
      celCompiler.compile(expression).ast
    } catch (e: CelValidationException) {
      throw IllegalArgumentException("Failed to compile expression. Reason: ${e.message}", e)
    }
  }

  /**
   * Evaluates the compiled AST with the user provided parameter values.
   *
   * @throws IllegalArgumentException If the compiled expression in AST fails to evaluate.
   */
  fun eval(ast: CelAbstractSyntaxTree, parameterValues: Map<String, *>): Any? {
    val celRuntime = CelRuntimeFactory.plannerRuntimeBuilder().build()
    return try {
      val program = celRuntime.createProgram(ast)

      // Evaluate using the provided map as input variables (key: variableName, value:
      // variableValue).
      program.eval(parameterValues)
    } catch (e: CelEvaluationException) {
      // Report any evaluation errors, if present.
      throw IllegalArgumentException("Evaluation error has occurred. Reason: ${e.message}", e)
    }
  }
}
