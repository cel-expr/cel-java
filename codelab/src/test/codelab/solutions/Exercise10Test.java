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

package codelab.solutions;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableMap;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import com.google.testing.junit.testparameterinjector.TestParameters;
import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.runtime.CelEvaluationException;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(TestParameterInjector.class)
public final class Exercise10Test {
  private final Exercise10 exercise10 = new Exercise10();

  @Test
  @TestParameters("{ip: '8.8.4.4', expectedResult: true}")
  @TestParameters("{ip: '8.8.8.8', expectedResult: false}")
  public void evaluate_ipInRange(String ip, boolean expectedResult) throws Exception {
    CelAbstractSyntaxTree ast =
        exercise10.compile(
            "net.parseAddressMatcher('8.8.4.0-8.8.4.255').containsAddress(net.parseAddress(ip))");

    Object result = exercise10.eval(ast, ImmutableMap.of("ip", ip));

    assertThat(result).isEqualTo(expectedResult);
  }

  @Test
  @TestParameters("{ip: '8.8.4.4', expectedResult: true}")
  @TestParameters("{ip: '8.8.8.8', expectedResult: false}")
  public void evaluate_ipEquality(String ip, boolean expectedResult) throws Exception {
    CelAbstractSyntaxTree ast =
        exercise10.compile("net.parseAddress('8.8.4.4') == net.parseAddress(ip)");

    Object result = exercise10.eval(ast, ImmutableMap.of("ip", ip));

    assertThat(result).isEqualTo(expectedResult);
  }

  @Test
  public void evaluate_opaqueTypeIdentity() throws Exception {
    CelAbstractSyntaxTree ast = exercise10.compile("type(net.parseAddress(ip)) == net.Address");

    Object result = exercise10.eval(ast, ImmutableMap.of("ip", "8.8.4.4"));

    assertThat(result).isEqualTo(true);
  }

  @Test
  public void evaluate_invalidIp_throwsEvaluationException() throws Exception {
    CelAbstractSyntaxTree ast =
        exercise10.compile("net.parseAddress('8.8.4.4') == net.parseAddress(ip)");

    CelEvaluationException exception =
        assertThrows(
            CelEvaluationException.class, () -> exercise10.eval(ast, ImmutableMap.of("ip", "8.8")));

    assertThat(exception).hasCauseThat().hasMessageThat().contains("invalid address");
  }
}
