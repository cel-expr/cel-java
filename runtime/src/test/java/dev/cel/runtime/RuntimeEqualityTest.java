// Copyright 2025 Google LLC
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

package dev.cel.runtime;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.primitives.UnsignedLong;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import dev.cel.common.CelOptions;
import dev.cel.expr.conformance.proto2.TestAllTypes;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(TestParameterInjector.class)
public final class RuntimeEqualityTest {

  @Test
  public void objectEquals_and_hashCode() {
    RuntimeEquality runtimeEquality =
        RuntimeEquality.create(RuntimeHelpers.create(), CelOptions.DEFAULT);
    assertEqualityAndHashCode(runtimeEquality, 1, 1);
    assertEqualityAndHashCode(runtimeEquality, 2, 2L);
    assertEqualityAndHashCode(runtimeEquality, 3, 3.0);
    assertEqualityAndHashCode(runtimeEquality, 4, UnsignedLong.valueOf(4));
    assertEqualityAndHashCode(runtimeEquality, 0.0d, -0.0d);
    assertEqualityAndHashCode(
        runtimeEquality,
        ImmutableList.of(1, 2, 3),
        ImmutableList.of(1.0, 2L, UnsignedLong.valueOf(3)));
    assertEqualityAndHashCode(
        runtimeEquality,
        ImmutableMap.of("a", 1, "b", 2),
        ImmutableMap.of("a", 1L, "b", UnsignedLong.valueOf(2)));
  }

  private void assertEqualityAndHashCode(RuntimeEquality runtimeEquality, Object obj1, Object obj2) {
    assertThat(runtimeEquality.objectEquals(obj1, obj2)).isTrue();
    assertThat(runtimeEquality.hashCode(obj1)).isEqualTo(runtimeEquality.hashCode(obj2));
  }

  @Test
  public void objectEquals_messageLite_throws() {
    RuntimeEquality runtimeEquality =
        RuntimeEquality.create(RuntimeHelpers.create(), CelOptions.DEFAULT);

    // Unimplemented until CelLiteDescriptor is available.
    assertThrows(
        UnsupportedOperationException.class,
        () ->
            runtimeEquality.objectEquals(
                TestAllTypes.newBuilder(), TestAllTypes.getDefaultInstance()));
  }
}
