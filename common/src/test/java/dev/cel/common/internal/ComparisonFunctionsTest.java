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

package dev.cel.common.internal;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.primitives.UnsignedLong;
import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import com.google.testing.junit.testparameterinjector.TestParameters;
import java.math.BigInteger;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(TestParameterInjector.class)
public final class ComparisonFunctionsTest {

  @Test
  @TestParameters("{x: 1.0, y: 1, expect: 0}")
  @TestParameters("{x: -0.0, y: 0, expect: 0}")
  @TestParameters("{x: -2.1, y: -2, expect: -1}")
  @TestParameters("{x: 2.1, y: -3, expect: 1}")
  @TestParameters("{x: -9223372036854778809.0, y: 1, expect: -1}")
  @TestParameters("{x: 9223372036854778809.0, y: 1, expect: 1}")
  @TestParameters("{x: NaN, y: 0, expect: 1}")
  public void compareDoubleInt(double x, long y, int expect) {
    assertThat(ComparisonFunctions.compareDoubleInt(x, y)).isEqualTo(expect);
    assertThat(ComparisonFunctions.compareIntDouble(y, x)).isEqualTo(-1 * expect);
  }

  @Test
  @TestParameters("{x: 1.0, y: 1, expect: 0}")
  @TestParameters("{x: -0.0, y: 0, expect: 0}")
  @TestParameters("{x: -2.1, y: 0, expect: -1}")
  @TestParameters("{x: 2.1, y: 1, expect: 1}")
  @TestParameters("{x: 18446744073709653620.1, y: 1, expect: 1}")
  @TestParameters("{x: NaN, y: 0, expect: 1}")
  public void compareDoubleUint(double x, long y, int expect) {
    UnsignedLong uy = UnsignedLong.valueOf(y);
    assertThat(ComparisonFunctions.compareDoubleUint(x, uy)).isEqualTo(expect);
    assertThat(ComparisonFunctions.compareUintDouble(uy, x)).isEqualTo(-1 * expect);
  }

  @Test
  @TestParameters("{x: 1, y: 1, expect: 0}")
  @TestParameters("{x: 1, y: 2, expect: -1}")
  @TestParameters("{x: 2, y: -1, expect: 1}")
  public void compareUintInt(long x, long y, int expect) {
    UnsignedLong ux = UnsignedLong.valueOf(x);
    assertThat(ComparisonFunctions.compareUintInt(ux, y)).isEqualTo(expect);
    assertThat(ComparisonFunctions.compareIntUint(y, ux)).isEqualTo(-1 * expect);
  }

  @Test
  public void compareUintIntEdgeCases() {
    UnsignedLong ux = UnsignedLong.valueOf(BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE));
    assertThat(ComparisonFunctions.compareUintInt(ux, 1)).isEqualTo(1);
    assertThat(ComparisonFunctions.compareIntUint(1, ux)).isEqualTo(-1);
  }

  @Test
  @TestParameters("{x: 1, y: 1, expect: 0}")
  @TestParameters("{x: 1, y: 2, expect: -1}")
  @TestParameters("{x: 2, y: -1, expect: 1}")
  public void numericCompareDoubleInt(double x, long y, int expect) {
    assertThat(ComparisonFunctions.numericCompare(x, y)).isEqualTo(expect);
    assertThat(ComparisonFunctions.numericCompare(y, x)).isEqualTo(-1 * expect);
  }

  @Test
  @TestParameters("{x: 1, y: 1, expect: 0}")
  @TestParameters("{x: 1, y: 2, expect: -1}")
  @TestParameters("{x: 2, y: 1, expect: 1}")
  public void numericCompareDoubleUint(double x, long y, int expect) {
    UnsignedLong uy = UnsignedLong.valueOf(y);
    assertThat(ComparisonFunctions.numericCompare(x, uy)).isEqualTo(expect);
    assertThat(ComparisonFunctions.numericCompare(uy, x)).isEqualTo(-1 * expect);
  }

  @Test
  @TestParameters("{x: 1, y: 1, expect: 0}")
  @TestParameters("{x: 1, y: 2, expect: -1}")
  @TestParameters("{x: 2, y: -1, expect: 1}")
  public void numericCompareUintInt(long x, long y, int expect) {
    UnsignedLong ux = UnsignedLong.valueOf(x);
    assertThat(ComparisonFunctions.numericCompare(ux, y)).isEqualTo(expect);
    assertThat(ComparisonFunctions.numericCompare(y, ux)).isEqualTo(-1 * expect);
  }

  @Test
  @TestParameters("{x: -0.0, y: 0.0, expect: 0}")
  @TestParameters("{x: 1.0, y: 2.0, expect: -1}")
  @TestParameters("{x: NaN, y: 1.0, expect: 1}")
  @TestParameters("{x: NaN, y: NaN, expect: 0}")
  public void numericCompare_doubles(double x, double y, int expect) {
    assertThat(ComparisonFunctions.numericCompare(x, y)).isEqualTo(expect);
    assertThat(ComparisonFunctions.numericCompare(y, x)).isEqualTo(-1 * expect);
  }

  @SuppressWarnings("ImmutableEnumChecker") // Test only
  private enum NumericEqualsTestCase {
    NEGATIVE_ZERO_AND_POSITIVE_ZERO(-0.0, 0.0, true),
    NEGATIVE_ZERO_AND_LONG_ZERO(-0.0, 0L, true),
    NEGATIVE_ZERO_AND_ULONG_ZERO(-0.0, UnsignedLong.ZERO, true),
    DISTINCT_DOUBLES(1.0, 2.0, false),
    NAN_AND_NAN(Double.NaN, Double.NaN, false),
    NAN_AND_LONG_ZERO(Double.NaN, 0L, false),
    NAN_AND_ULONG_ZERO(Double.NaN, UnsignedLong.ZERO, false),
    SHORT_AND_LONG((short) 1, 1L, false);

    private final Number x;
    private final Number y;
    private final boolean expected;

    NumericEqualsTestCase(Number x, Number y, boolean expected) {
      this.x = x;
      this.y = y;
      this.expected = expected;
    }
  }

  @Test
  public void numericEquals_isSymmetric(@TestParameter NumericEqualsTestCase testCase) {
    assertThat(ComparisonFunctions.numericEquals(testCase.x, testCase.y))
        .isEqualTo(testCase.expected);
    assertThat(ComparisonFunctions.numericEquals(testCase.y, testCase.x))
        .isEqualTo(testCase.expected);
  }
}
