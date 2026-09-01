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

package dev.cel.runtime.planner;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.testing.EqualsTester;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import dev.cel.common.CelOptions;
import dev.cel.runtime.RuntimeEquality;
import dev.cel.runtime.RuntimeHelpers;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(TestParameterInjector.class)
public final class AsyncCallKeyTest {

  private final RuntimeEquality runtimeEquality =
      RuntimeEquality.create(RuntimeHelpers.create(), CelOptions.DEFAULT);

  @Test
  public void equalsAndHashCode_identicalArgs_equal() {
    AsyncCallKey k1 = AsyncCallKey.create(10L, new Object[] {"foo", 42L}, runtimeEquality);
    AsyncCallKey k2 = AsyncCallKey.create(10L, new Object[] {"foo", 42L}, runtimeEquality);

    assertThat(k1).isEqualTo(k2);
    assertThat(k1.hashCode()).isEqualTo(k2.hashCode());
  }

  @Test
  public void equalsAndHashCode_differentExprId_notEqual() {
    AsyncCallKey k1 = AsyncCallKey.create(10L, new Object[] {"foo"}, runtimeEquality);
    AsyncCallKey k2 = AsyncCallKey.create(20L, new Object[] {"foo"}, runtimeEquality);

    assertThat(k1).isNotEqualTo(k2);
  }

  @Test
  public void equalsAndHashCode_differentArgLengths_notEqual() {
    AsyncCallKey k1 = AsyncCallKey.create(10L, new Object[] {"foo"}, runtimeEquality);
    AsyncCallKey k2 = AsyncCallKey.create(10L, new Object[] {"foo", "bar"}, runtimeEquality);

    assertThat(k1).isNotEqualTo(k2);
  }

  @Test
  public void equalsAndHashCode_differentArgs_differentHashCode() {
    AsyncCallKey k1 = AsyncCallKey.create(10L, new Object[] {"foo"}, runtimeEquality);
    AsyncCallKey k2 = AsyncCallKey.create(10L, new Object[] {"bar"}, runtimeEquality);

    assertThat(k1.hashCode()).isNotEqualTo(k2.hashCode());
  }

  @Test
  public void equalsAndHashCode_nullArgs_equal() {
    AsyncCallKey k1 = AsyncCallKey.create(10L, new Object[] {null}, runtimeEquality);
    AsyncCallKey k2 = AsyncCallKey.create(10L, new Object[] {null}, runtimeEquality);
    AsyncCallKey k3 = AsyncCallKey.create(10L, new Object[] {"notNull"}, runtimeEquality);

    assertThat(k1).isEqualTo(k2);
    assertThat(k1.hashCode()).isEqualTo(k2.hashCode());
    assertThat(k1).isNotEqualTo(k3);
  }

  @Test
  public void equalsAndHashCode_numberNormalization_zeroAndNegativeZero_equalHashCode() {
    AsyncCallKey kZero = AsyncCallKey.create(10L, new Object[] {0.0d}, runtimeEquality);
    AsyncCallKey kNegZero = AsyncCallKey.create(10L, new Object[] {-0.0d}, runtimeEquality);

    assertThat(kZero.hashCode()).isEqualTo(kNegZero.hashCode());
  }

  @Test
  public void equalsAndHashCode_numberNormalization_doubleNaN_equalAndEqualHashCode() {
    AsyncCallKey kNan1 = AsyncCallKey.create(10L, new Object[] {Double.NaN}, runtimeEquality);
    AsyncCallKey kNan2 = AsyncCallKey.create(10L, new Object[] {Double.NaN}, runtimeEquality);

    assertThat(kNan1).isEqualTo(kNan2);
    assertThat(kNan1.hashCode()).isEqualTo(kNan2.hashCode());
  }

  @Test
  public void equalsAndHashCode_numberNormalization_floatNaN_equal() {
    AsyncCallKey kFloatNan1 = AsyncCallKey.create(10L, new Object[] {Float.NaN}, runtimeEquality);
    AsyncCallKey kFloatNan2 = AsyncCallKey.create(10L, new Object[] {Float.NaN}, runtimeEquality);

    assertThat(kFloatNan1).isEqualTo(kFloatNan2);
  }

  @Test
  public void equalsAndHashCode_equalsTester() {
    new EqualsTester()
        .addEqualityGroup(
            AsyncCallKey.create(10L, new Object[] {1}, runtimeEquality),
            AsyncCallKey.create(10L, new Object[] {1}, runtimeEquality))
        .addEqualityGroup(AsyncCallKey.create(20L, new Object[] {1}, runtimeEquality))
        .addEqualityGroup(AsyncCallKey.create(10L, new Object[] {2}, runtimeEquality))
        .testEquals();
  }

  @Test
  public void hashMapLookup_success() {
    AsyncCallKey k1 = AsyncCallKey.create(10L, new Object[] {"key1"}, runtimeEquality);
    AsyncCallKey k2 = AsyncCallKey.create(10L, new Object[] {"key2"}, runtimeEquality);
    AsyncCallKey k1Lookup = AsyncCallKey.create(10L, new Object[] {"key1"}, runtimeEquality);

    Map<AsyncCallKey, String> map = new HashMap<>();
    map.put(k1, "val1");
    map.put(k2, "val2");

    assertThat(map).containsEntry(k1Lookup, "val1");
  }
}
