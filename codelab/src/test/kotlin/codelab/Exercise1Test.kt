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

import com.google.common.truth.Truth.assertThat
import dev.cel.compiler.CelCompilerFactory
import kotlin.test.assertFailsWith
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class Exercise1Test {
  private val exercise1 = Exercise1()

  @Test
  fun compile_stringExpression_parseSuccess() {
    val ast = exercise1.compile(TEST_EXPRESSION)
    assertThat(ast).isNotNull()
  }

  @Test
  fun compile_stringExpression_typecheckSuccess() {
    val ast = exercise1.compile(TEST_EXPRESSION)
    assertThat(ast.isChecked).isTrue()
  }

  @Test
  fun compile_booleanExpression_throwsValidationException() {
    val exception = assertFailsWith<IllegalArgumentException> { exercise1.compile("true == true") }

    assertThat(exception).hasMessageThat().contains("Failed to type-check expression.")
    assertThat(exception).hasMessageThat().contains("expected type 'string' but found 'bool'")
  }

  @Test
  fun evaluate_stringExpression_success() {
    val ast = exercise1.compile(TEST_EXPRESSION)

    val evaluatedResult = exercise1.eval(ast)

    assertThat(evaluatedResult).isEqualTo("Hello, World!")
  }

  @Test
  fun evaluate_divideByZeroExpression_throwsEvaluationException() {
    val compiler = CelCompilerFactory.standardCelCompilerBuilder().build()
    val ast = compiler.compile("1/0").ast

    val exception = assertFailsWith<IllegalArgumentException> { exercise1.eval(ast) }

    assertThat(exception).hasMessageThat().contains("evaluation error at <input>:1: / by zero")
  }

  private companion object {
    const val TEST_EXPRESSION = "'Hello, World!'"
  }
}
