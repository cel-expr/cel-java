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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.primitives.UnsignedLong;
import com.google.common.testing.EqualsTester;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import dev.cel.common.CelOptions;
import dev.cel.common.values.NullValue;
import dev.cel.runtime.RuntimeEquality;
import dev.cel.runtime.RuntimeHelpers;
import java.util.Collections;
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
    AsyncCallKey k1 =
        AsyncCallKey.create(
            10L, "myFunc", "myFunc_overload", new Object[] {"foo", 42L}, runtimeEquality);
    AsyncCallKey k2 =
        AsyncCallKey.create(
            10L, "myFunc", "myFunc_overload", new Object[] {"foo", 42L}, runtimeEquality);

    assertThat(k1).isEqualTo(k2);
    assertThat(k1.hashCode()).isEqualTo(k2.hashCode());
  }

  @Test
  public void equalsAndHashCode_differentExprId_notEqual() {
    AsyncCallKey k1 =
        AsyncCallKey.create(
            10L, "myFunc", "myFunc_overload", new Object[] {"foo"}, runtimeEquality);
    AsyncCallKey k2 =
        AsyncCallKey.create(
            20L, "myFunc", "myFunc_overload", new Object[] {"foo"}, runtimeEquality);

    assertThat(k1).isNotEqualTo(k2);
  }

  @Test
  public void equalsAndHashCode_differentFunctionName_notEqual() {
    AsyncCallKey k1 =
        AsyncCallKey.create(10L, "funcA", "overload_1", new Object[] {"foo"}, runtimeEquality);
    AsyncCallKey k2 =
        AsyncCallKey.create(10L, "funcB", "overload_1", new Object[] {"foo"}, runtimeEquality);

    assertThat(k1).isNotEqualTo(k2);
    assertThat(k1.hashCode()).isNotEqualTo(k2.hashCode());
  }

  @Test
  public void equalsAndHashCode_differentOverloadId_notEqual() {
    AsyncCallKey k1 =
        AsyncCallKey.create(10L, "myFunc", "overload_1", new Object[] {"foo"}, runtimeEquality);
    AsyncCallKey k2 =
        AsyncCallKey.create(10L, "myFunc", "overload_2", new Object[] {"foo"}, runtimeEquality);

    assertThat(k1).isNotEqualTo(k2);
    assertThat(k1.hashCode()).isNotEqualTo(k2.hashCode());
  }

  @Test
  public void hashCode_matchesExpectedFormula() {
    long exprId = 10L;
    String functionName = "myFunc";
    String overloadId = "overload_1";
    Object[] args = new Object[] {"foo"};
    AsyncCallKey key = AsyncCallKey.create(exprId, functionName, overloadId, args, runtimeEquality);
    int expected = (int) (exprId ^ (exprId >>> 32));
    expected = 31 * expected + functionName.hashCode();
    expected = 31 * expected + overloadId.hashCode();
    expected = 31 * expected + "foo".hashCode();

    assertThat(key.hashCode()).isEqualTo(expected);
  }

  @Test
  public void
      equalsAndHashCode_comprehensionCrossTypeNumericEqualityWithDifferentOverloads_notEqual() {
    // In comprehensions over heterogeneous lists (e.g. [1, 1.0]), the AST node ID is identical.
    // In CEL, 1L == 1.0d is true under RuntimeEquality, but different overloads are dispatched.
    // The key must not collide across distinct overloads.
    AsyncCallKey kInt = AsyncCallKey.create(10L, "f", "f_int", new Object[] {1L}, runtimeEquality);
    AsyncCallKey kDouble =
        AsyncCallKey.create(10L, "f", "f_double", new Object[] {1.0d}, runtimeEquality);

    assertThat(kInt).isNotEqualTo(kDouble);
    assertThat(kInt.hashCode()).isNotEqualTo(kDouble.hashCode());
  }

  @Test
  public void equalsAndHashCode_differentArgLengths_notEqual() {
    AsyncCallKey k1 =
        AsyncCallKey.create(
            10L, "myFunc", "myFunc_overload", new Object[] {"foo"}, runtimeEquality);
    AsyncCallKey k2 =
        AsyncCallKey.create(
            10L, "myFunc", "myFunc_overload", new Object[] {"foo", "bar"}, runtimeEquality);

    assertThat(k1).isNotEqualTo(k2);
    assertThat(k1.hashCode()).isNotEqualTo(k2.hashCode());
  }

  @Test
  public void equalsAndHashCode_differentArgs_differentHashCode() {
    AsyncCallKey k1 =
        AsyncCallKey.create(
            10L, "myFunc", "myFunc_overload", new Object[] {"foo"}, runtimeEquality);
    AsyncCallKey k2 =
        AsyncCallKey.create(
            10L, "myFunc", "myFunc_overload", new Object[] {"bar"}, runtimeEquality);

    assertThat(k1).isNotEqualTo(k2);
    assertThat(k1.hashCode()).isNotEqualTo(k2.hashCode());
  }

  @Test
  public void equalsAndHashCode_nullArgs_equal() {
    AsyncCallKey k1 =
        AsyncCallKey.create(10L, "myFunc", "myFunc_overload", new Object[] {null}, runtimeEquality);
    AsyncCallKey k2 =
        AsyncCallKey.create(10L, "myFunc", "myFunc_overload", new Object[] {null}, runtimeEquality);
    AsyncCallKey k3 =
        AsyncCallKey.create(
            10L, "myFunc", "myFunc_overload", new Object[] {"notNull"}, runtimeEquality);

    assertThat(k1).isEqualTo(k2);
    assertThat(k1.hashCode()).isEqualTo(k2.hashCode());
    assertThat(k1).isNotEqualTo(k3);
  }

  @Test
  public void equalsAndHashCode_numberNormalization_zeroAndNegativeZero_equalHashCode() {
    AsyncCallKey kZero =
        AsyncCallKey.create(10L, "myFunc", "myFunc_overload", new Object[] {0.0d}, runtimeEquality);
    AsyncCallKey kNegZero =
        AsyncCallKey.create(
            10L, "myFunc", "myFunc_overload", new Object[] {-0.0d}, runtimeEquality);

    assertThat(kZero.hashCode()).isEqualTo(kNegZero.hashCode());
  }

  @Test
  public void equalsAndHashCode_numberNormalization_doubleNaN_equalAndEqualHashCode() {
    AsyncCallKey kNan1 =
        AsyncCallKey.create(
            10L, "myFunc", "myFunc_overload", new Object[] {Double.NaN}, runtimeEquality);
    AsyncCallKey kNan2 =
        AsyncCallKey.create(
            10L, "myFunc", "myFunc_overload", new Object[] {Double.NaN}, runtimeEquality);

    assertThat(kNan1).isEqualTo(kNan2);
    assertThat(kNan1.hashCode()).isEqualTo(kNan2.hashCode());
  }

  @Test
  public void equalsAndHashCode_numberNormalization_floatNaN_equal() {
    AsyncCallKey kFloatNan1 =
        AsyncCallKey.create(
            10L, "myFunc", "myFunc_overload", new Object[] {Float.NaN}, runtimeEquality);
    AsyncCallKey kFloatNan2 =
        AsyncCallKey.create(
            10L, "myFunc", "myFunc_overload", new Object[] {Float.NaN}, runtimeEquality);

    assertThat(kFloatNan1).isEqualTo(kFloatNan2);
    assertThat(kFloatNan1.hashCode()).isEqualTo(kFloatNan2.hashCode());
  }

  @Test
  public void equalsAndHashCode_equalsTester() {
    new EqualsTester()
        .addEqualityGroup(
            AsyncCallKey.create(10L, "myFunc", "overload_1", new Object[] {1}, runtimeEquality),
            AsyncCallKey.create(10L, "myFunc", "overload_1", new Object[] {1}, runtimeEquality))
        .addEqualityGroup(
            AsyncCallKey.create(20L, "myFunc", "overload_1", new Object[] {1}, runtimeEquality))
        .addEqualityGroup(
            AsyncCallKey.create(10L, "myFunc", "overload_2", new Object[] {1}, runtimeEquality))
        .addEqualityGroup(
            AsyncCallKey.create(10L, "otherFunc", "overload_1", new Object[] {1}, runtimeEquality))
        .addEqualityGroup(
            AsyncCallKey.create(10L, "myFunc", "overload_1", new Object[] {2}, runtimeEquality))
        .testEquals();
  }

  @Test
  public void equalsAndHashCode_numberNormalization_doubleAndFloatNaN_equal() {
    AsyncCallKey k1 =
        AsyncCallKey.create(
            10L, "myFunc", "myFunc_overload", new Object[] {Double.NaN}, runtimeEquality);
    AsyncCallKey k2 =
        AsyncCallKey.create(
            10L, "myFunc", "myFunc_overload", new Object[] {Float.NaN}, runtimeEquality);

    assertThat(k1).isEqualTo(k2);
    assertThat(k1.hashCode()).isEqualTo(k2.hashCode());
  }

  @Test
  public void equalsAndHashCode_collectionsWithSignedZero_equalHashCodeAndEquals() {
    AsyncCallKey k1 =
        AsyncCallKey.create(
            10L,
            "myFunc",
            "myFunc_overload",
            new Object[] {ImmutableList.of(0.0d)},
            runtimeEquality);
    AsyncCallKey k2 =
        AsyncCallKey.create(
            10L,
            "myFunc",
            "myFunc_overload",
            new Object[] {ImmutableList.of(-0.0d)},
            runtimeEquality);

    assertThat(k1).isEqualTo(k2);
    assertThat(k1.hashCode()).isEqualTo(k2.hashCode());
  }

  @Test
  public void equalsAndHashCode_collectionsWithNaN_equalHashCodeAndEquals() {
    AsyncCallKey k1 =
        AsyncCallKey.create(
            10L,
            "myFunc",
            "myFunc_overload",
            new Object[] {ImmutableList.of(Double.NaN)},
            runtimeEquality);
    AsyncCallKey k2 =
        AsyncCallKey.create(
            10L,
            "myFunc",
            "myFunc_overload",
            new Object[] {ImmutableList.of(Float.NaN)},
            runtimeEquality);

    assertThat(k1).isEqualTo(k2);
    assertThat(k1.hashCode()).isEqualTo(k2.hashCode());
  }

  @Test
  public void equalsAndHashCode_mapWithCrossTypeIntegerKeys_equal() {
    // In CEL, 1L == 1u so a map keyed by Long and a map keyed by UnsignedLong must match.
    ImmutableMap<Object, String> mapLong = ImmutableMap.of(1L, "value");
    ImmutableMap<Object, String> mapUint = ImmutableMap.of(UnsignedLong.ONE, "value");
    AsyncCallKey k1 =
        AsyncCallKey.create(
            10L, "myFunc", "myFunc_overload", new Object[] {mapLong}, runtimeEquality);
    AsyncCallKey k2 =
        AsyncCallKey.create(
            10L, "myFunc", "myFunc_overload", new Object[] {mapUint}, runtimeEquality);

    assertThat(k1).isEqualTo(k2);
    assertThat(k1.hashCode()).isEqualTo(k2.hashCode());
  }

  @Test
  public void equalsAndHashCode_byteArrays_equalHashCodeAndEquals() {
    AsyncCallKey k1 =
        AsyncCallKey.create(
            10L, "myFunc", "myFunc_overload", new Object[] {new byte[] {1, 2, 3}}, runtimeEquality);
    AsyncCallKey k2 =
        AsyncCallKey.create(
            10L, "myFunc", "myFunc_overload", new Object[] {new byte[] {1, 2, 3}}, runtimeEquality);

    assertThat(k1).isEqualTo(k2);
    assertThat(k1.hashCode()).isEqualTo(k2.hashCode());
  }

  @Test
  public void hashMapLookup_success() {
    AsyncCallKey k1 =
        AsyncCallKey.create(
            10L, "myFunc", "myFunc_overload", new Object[] {"key1"}, runtimeEquality);
    AsyncCallKey k2 =
        AsyncCallKey.create(
            10L, "myFunc", "myFunc_overload", new Object[] {"key2"}, runtimeEquality);
    AsyncCallKey k1Lookup =
        AsyncCallKey.create(
            10L, "myFunc", "myFunc_overload", new Object[] {"key1"}, runtimeEquality);
    Map<AsyncCallKey, String> map = new HashMap<>();
    map.put(k1, "val1");
    map.put(k2, "val2");

    assertThat(map).containsEntry(k1Lookup, "val1");
  }

  @Test
  public void equalsAndHashCode_objectArrays_equalHashCodeAndEquals() {
    AsyncCallKey k1 =
        AsyncCallKey.create(
            10L,
            "myFunc",
            "myFunc_overload",
            new Object[] {new Object[] {"nested", 123L}},
            runtimeEquality);
    AsyncCallKey k2 =
        AsyncCallKey.create(
            10L,
            "myFunc",
            "myFunc_overload",
            new Object[] {new Object[] {"nested", 123L}},
            runtimeEquality);
    AsyncCallKey k3 =
        AsyncCallKey.create(
            10L,
            "myFunc",
            "myFunc_overload",
            new Object[] {new Object[] {"other", 123L}},
            runtimeEquality);

    assertThat(k1).isEqualTo(k2);
    assertThat(k1.hashCode()).isEqualTo(k2.hashCode());
    assertThat(k1).isNotEqualTo(k3);
    assertThat(k1.hashCode()).isNotEqualTo(k3.hashCode());
  }

  @Test
  public void equals_objectArrayVersusNonArray_notEqual() {
    AsyncCallKey kArray =
        AsyncCallKey.create(
            10L,
            "myFunc",
            "myFunc_overload",
            new Object[] {new Object[] {"a"}},
            runtimeEquality);
    AsyncCallKey kNonArray =
        AsyncCallKey.create(
            10L, "myFunc", "myFunc_overload", new Object[] {"a"}, runtimeEquality);

    assertThat(kArray).isNotEqualTo(kNonArray);
    assertThat(kNonArray).isNotEqualTo(kArray);
  }

  @Test
  public void equals_byteArrayVersusNonArray_notEqual() {
    AsyncCallKey kBytes =
        AsyncCallKey.create(
            10L,
            "myFunc",
            "myFunc_overload",
            new Object[] {new byte[] {1, 2}},
            runtimeEquality);
    AsyncCallKey kNonBytes =
        AsyncCallKey.create(
            10L, "myFunc", "myFunc_overload", new Object[] {"12"}, runtimeEquality);

    assertThat(kBytes).isNotEqualTo(kNonBytes);
    assertThat(kNonBytes).isNotEqualTo(kBytes);
  }

  @Test
  public void equalsAndHashCode_mapWithNullValues_equal() {
    Map<String, Object> map1 = Collections.singletonMap("k", null);
    Map<String, Object> map2 = Collections.singletonMap("k", null);
    AsyncCallKey k1 =
        AsyncCallKey.create(10L, "myFunc", "myFunc_overload", new Object[] {map1}, runtimeEquality);
    AsyncCallKey k2 =
        AsyncCallKey.create(10L, "myFunc", "myFunc_overload", new Object[] {map2}, runtimeEquality);

    assertThat(k1).isEqualTo(k2);
    assertThat(k1.hashCode()).isEqualTo(k2.hashCode());
  }

  @Test
  public void equalsAndHashCode_mapsWithNullValuesDifferentKeys_notEqual() {
    Map<String, Object> map1 = Collections.singletonMap("k1", null);
    Map<String, Object> map2 = Collections.singletonMap("k2", null);
    AsyncCallKey k1 =
        AsyncCallKey.create(10L, "myFunc", "myFunc_overload", new Object[] {map1}, runtimeEquality);
    AsyncCallKey k2 =
        AsyncCallKey.create(10L, "myFunc", "myFunc_overload", new Object[] {map2}, runtimeEquality);

    assertThat(k1).isNotEqualTo(k2);
  }

  @Test
  public void equals_listVersusNonList_notEqual() {
    AsyncCallKey kList =
        AsyncCallKey.create(
            10L,
            "myFunc",
            "myFunc_overload",
            new Object[] {Collections.singletonList("a")},
            runtimeEquality);
    AsyncCallKey kNonList =
        AsyncCallKey.create(
            10L, "myFunc", "myFunc_overload", new Object[] {"a"}, runtimeEquality);

    assertThat(kList).isNotEqualTo(kNonList);
    assertThat(kNonList).isNotEqualTo(kList);
  }

  @Test
  public void equals_mapVersusNonMap_notEqual() {
    AsyncCallKey kMap =
        AsyncCallKey.create(
            10L,
            "myFunc",
            "myFunc_overload",
            new Object[] {Collections.singletonMap("k", "v")},
            runtimeEquality);
    AsyncCallKey kNonMap =
        AsyncCallKey.create(
            10L, "myFunc", "myFunc_overload", new Object[] {"k"}, runtimeEquality);

    assertThat(kMap).isNotEqualTo(kNonMap);
    assertThat(kNonMap).isNotEqualTo(kMap);
  }

  @Test
  public void equals_numberVersusNonNumber_notEqual() {
    AsyncCallKey kNumber =
        AsyncCallKey.create(
            10L, "myFunc", "myFunc_overload", new Object[] {10L}, runtimeEquality);
    AsyncCallKey kNonNumber =
        AsyncCallKey.create(
            10L, "myFunc", "myFunc_overload", new Object[] {"10"}, runtimeEquality);

    assertThat(kNumber).isNotEqualTo(kNonNumber);
    assertThat(kNonNumber).isNotEqualTo(kNumber);
  }

  @Test
  public void equalsAndHashCode_crossTypeZeroLongAndDouble_equal() {
    AsyncCallKey kLong =
        AsyncCallKey.create(10L, "myFunc", "myFunc_overload", new Object[] {0L}, runtimeEquality);
    AsyncCallKey kDoublePos =
        AsyncCallKey.create(10L, "myFunc", "myFunc_overload", new Object[] {0.0d}, runtimeEquality);
    AsyncCallKey kDoubleNeg =
        AsyncCallKey.create(
            10L, "myFunc", "myFunc_overload", new Object[] {-0.0d}, runtimeEquality);

    assertThat(kLong).isEqualTo(kDoublePos);
    assertThat(kLong).isEqualTo(kDoubleNeg);
    assertThat(kLong.hashCode()).isEqualTo(kDoublePos.hashCode());
    assertThat(kLong.hashCode()).isEqualTo(kDoubleNeg.hashCode());
  }

  @Test
  public void equalsAndHashCode_nonInternedStrings_equalAndNotEqual() {
    AsyncCallKey k1 =
        AsyncCallKey.create(
            10L,
            "myFunc",
            "myFunc_overload",
            new Object[] {new String("customString".toCharArray())},
            runtimeEquality);
    AsyncCallKey k2 =
        AsyncCallKey.create(
            10L,
            "myFunc",
            "myFunc_overload",
            new Object[] {new String("customString".toCharArray())},
            runtimeEquality);
    AsyncCallKey k3 =
        AsyncCallKey.create(
            10L,
            "myFunc",
            "myFunc_overload",
            new Object[] {new String("otherString".toCharArray())},
            runtimeEquality);

    assertThat(k1).isEqualTo(k2);
    assertThat(k1.hashCode()).isEqualTo(k2.hashCode());
    assertThat(k1).isNotEqualTo(k3);
    assertThat(k1.hashCode()).isNotEqualTo(k3.hashCode());
  }

  @Test
  public void equalsAndHashCode_nullValueVsJavaNull_notEqual() {
    AsyncCallKey kJavaNull =
        AsyncCallKey.create(10L, "myFunc", "myFunc_overload", new Object[] {null}, runtimeEquality);
    AsyncCallKey kCelNull =
        AsyncCallKey.create(
            10L, "myFunc", "myFunc_overload", new Object[] {NullValue.NULL_VALUE}, runtimeEquality);

    assertThat(kJavaNull).isNotEqualTo(kCelNull);
    assertThat(kCelNull).isNotEqualTo(kJavaNull);
    assertThat(kJavaNull.hashCode()).isNotEqualTo(kCelNull.hashCode());
  }

  @Test
  public void equalsAndHashCode_differentListSizes_notEqual() {
    AsyncCallKey k1 =
        AsyncCallKey.create(
            10L,
            "myFunc",
            "myFunc_overload",
            new Object[] {ImmutableList.of(1, 2)},
            runtimeEquality);
    AsyncCallKey k2 =
        AsyncCallKey.create(
            10L,
            "myFunc",
            "myFunc_overload",
            new Object[] {ImmutableList.of(1, 2, 3)},
            runtimeEquality);

    assertThat(k1).isNotEqualTo(k2);
    assertThat(k1.hashCode()).isNotEqualTo(k2.hashCode());
  }

  @Test
  public void equalsAndHashCode_differentMapSizes_notEqual() {
    AsyncCallKey k1 =
        AsyncCallKey.create(
            10L,
            "myFunc",
            "myFunc_overload",
            new Object[] {ImmutableMap.of("a", 1)},
            runtimeEquality);
    AsyncCallKey k2 =
        AsyncCallKey.create(
            10L,
            "myFunc",
            "myFunc_overload",
            new Object[] {ImmutableMap.of("a", 1, "b", 2)},
            runtimeEquality);

    assertThat(k1).isNotEqualTo(k2);
    assertThat(k1.hashCode()).isNotEqualTo(k2.hashCode());
  }

  @Test
  public void equalsAndHashCode_multidimensionalObjectArrays_equalHashCodeAndEquals() {
    AsyncCallKey k1 =
        AsyncCallKey.create(
            10L,
            "myFunc",
            "myFunc_overload",
            new Object[] {new Object[][] {{"deep1", "deep2"}}},
            runtimeEquality);
    AsyncCallKey k2 =
        AsyncCallKey.create(
            10L,
            "myFunc",
            "myFunc_overload",
            new Object[] {new Object[][] {{"deep1", "deep2"}}},
            runtimeEquality);
    AsyncCallKey k3 =
        AsyncCallKey.create(
            10L,
            "myFunc",
            "myFunc_overload",
            new Object[] {new Object[][] {{"other1", "other2"}}},
            runtimeEquality);

    assertThat(k1).isEqualTo(k2);
    assertThat(k1.hashCode()).isEqualTo(k2.hashCode());
    assertThat(k1).isNotEqualTo(k3);
    assertThat(k1.hashCode()).isNotEqualTo(k3.hashCode());
  }
}
