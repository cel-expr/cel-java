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

import com.google.common.truth.Truth.assertThat
import com.google.protobuf.Struct
import com.google.protobuf.Value
import com.google.rpc.context.AttributeContext
import com.google.testing.junit.testparameterinjector.TestParameterInjector
import com.google.testing.junit.testparameterinjector.TestParameters
import dev.cel.common.types.SimpleType
import dev.cel.common.types.StructTypeReference
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(TestParameterInjector::class)
class Exercise2Test {
  private val exercise2 = Exercise2()

  @Test
  @TestParameters("{value: 5, expectedResult: false}")
  @TestParameters("{value: -5, expectedResult: true}")
  fun evaluate_negativeExpression(value: Long, expectedResult: Boolean) {
    val ast = exercise2.compile("value < 0", "value", SimpleType.INT)

    val evaluatedResult = exercise2.eval(ast, mapOf("value" to value))
    assertThat(evaluatedResult).isEqualTo(expectedResult)
  }

  @Test
  @TestParameters("{group: 'admin', expectedResult: true}")
  @TestParameters("{group: 'users', expectedResult: false}")
  fun evaluate_requestAuthorization(group: String, expectedResult: Boolean) {
    val ast =
      exercise2.compile(
        "request.auth.claims.group == 'admin'",
        "request",
        StructTypeReference.create("google.rpc.context.AttributeContext.Request"),
      )

    val auth =
      AttributeContext.Auth.newBuilder()
        .setPrincipal("user:me@acme.co")
        .setClaims(
          Struct.newBuilder().putFields("group", Value.newBuilder().setStringValue(group).build())
        )
        .build()
    val request = AttributeContext.Request.newBuilder().setAuth(auth).build()

    val evaluatedResult = exercise2.eval(ast, mapOf("request" to request))
    assertThat(evaluatedResult).isEqualTo(expectedResult)
  }
}
