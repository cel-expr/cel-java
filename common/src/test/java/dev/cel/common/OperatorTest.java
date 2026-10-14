// Copyright 2022 Google LLC
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

package dev.cel.common;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertEquals;

import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import com.google.testing.junit.testparameterinjector.TestParameters;
import java.util.Optional;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(TestParameterInjector.class)
public final class OperatorTest {

  @Test
  public void find_returnsEmptyWhenNotFound() {
    assertThat(Operator.find("has")).isEmpty();
  }

  @Test
  public void find_returnsCorrectOperator() {
    assertThat(Operator.find("+")).hasValue(Operator.ADD);
  }

  @Test
  public void findReverse_returnsEmptyWhenNotFound() {
    assertThat(Operator.findReverse("+")).isEmpty();
  }

  @Test
  public void findReverse_returnsCorrectOperator() {
    assertThat(Operator.findReverse("_+_")).hasValue(Operator.ADD);
  }

  @Test
  public void findReverse_allOperators(@TestParameter Operator operator) {
    assertThat(Operator.findReverse(operator.getFunction())).hasValue(operator);
  }

  @Test
  @TestParameters({
    "{operator: '_?_:_', value: 8}",
    "{operator: '_||_', value: 7}",
    "{operator: '_&&_', value: 6}",
    "{operator: '_==_', value: 5}",
    "{operator: '_>_', value: 5}",
    "{operator: '_>=_', value: 5}",
    "{operator: '@in', value: 5}",
    "{operator: '_<_', value: 5}",
    "{operator: '_<=_', value: 5}",
    "{operator: '_!=_', value: 5}",
    "{operator: '_+_', value: 4}",
    "{operator: '_-_', value: 4}",
    "{operator: '_/_', value: 3}",
    "{operator: '_%_', value: 3}",
    "{operator: '_*_', value: 3}",
    "{operator: '!_', value: 2}",
    "{operator: '-_', value: 2}",
    "{operator: '_[_]', value: 1}",
    "{operator: 'has', value: 0}",
  })
  public void lookupPrecedence(String operator, int value) {
    assertEquals(Operator.lookupPrecedence(operator), value);
  }

  @Test
  @TestParameters({
    "{operator: '-_', value: '-'}",
    "{operator: '!_', value: '!'}",
  })
  public void lookupUnaryOperator_nonEmpty(String operator, String value) {
    assertEquals(Operator.lookupUnaryOperator(operator), Optional.of(value));
  }

  @Test
  @TestParameters({
    "{operator: '_*_'}",
    "{operator: '_[_]'}",
    "{operator: '_&&_'}",
  })
  public void lookupUnaryOperator_empty(String operator) {
    assertEquals(Operator.lookupUnaryOperator(operator), Optional.empty());
  }

  @Test
  @TestParameters({
    "{operator: '_||_', value: '||'}",
    "{operator: '_&&_', value: '&&'}",
    "{operator: '_==_', value: '=='}",
    "{operator: '_>_', value: '>'}",
    "{operator: '_>=_', value: '>='}",
    "{operator: '@in', value: 'in'}",
    "{operator: '_<_', value: '<'}",
    "{operator: '_<=_', value: '<='}",
    "{operator: '_!=_', value: '!='}",
    "{operator: '_+_', value: '+'}",
    "{operator: '_-_', value: '-'}",
    "{operator: '_/_', value: '/'}",
    "{operator: '_%_', value: '%'}",
    "{operator: '_*_', value: '*'}",
  })
  public void lookupBinaryOperator_nonEmpty(String operator, String value) {
    assertEquals(Operator.lookupBinaryOperator(operator), Optional.of(value));
  }

  @Test
  @TestParameters({
    "{operator: '!_'}",
    "{operator: '-_'}",
    "{operator: '_[_]'}",
    "{operator: 'has'}",
  })
  public void lookupBinaryOperator_empty(String operator) {
    assertEquals(Operator.lookupBinaryOperator(operator), Optional.empty());
  }
}
