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

import com.google.common.primitives.UnsignedLong;
import com.google.common.primitives.UnsignedLongs;
import com.google.errorprone.annotations.CheckReturnValue;
import dev.cel.common.annotations.Internal;

/**
 * The {@code ComparisonFunctions} methods provide safe cross-type comparisons between {@code long},
 * {@code double}, and {@link UnsignedLong} values.
 *
 * <p>CEL Library Internals. Do Not Use.
 */
@CheckReturnValue
@Internal
public final class ComparisonFunctions {

  private static final UnsignedLong UINT_TO_INT_MAX = UnsignedLong.valueOf(Long.MAX_VALUE);

  public static int compareDoubleInt(double d, long l) {
    return compareDouble(d, (double) l);
  }

  public static int compareIntDouble(long l, double d) {
    return -compareDoubleInt(d, l);
  }

  public static int compareDoubleUint(double d, UnsignedLong ul) {
    return compareDouble(d, ul.doubleValue());
  }

  public static int compareUintDouble(UnsignedLong ul, double d) {
    return -compareDoubleUint(d, ul);
  }

  public static int compareIntUint(long l, UnsignedLong ul) {
    if (l < 0 || ul.compareTo(UINT_TO_INT_MAX) >= 0) {
      return -1;
    }
    return Long.compare(l, ul.longValue());
  }

  public static int compareUintInt(UnsignedLong ul, long l) {
    return -compareIntUint(l, ul);
  }

  /** Returns true if {@code value} is one of CEL's canonical runtime numeric types. */
  public static boolean isNumeric(Object value) {
    return value instanceof Long || value instanceof Double || value instanceof UnsignedLong;
  }

  /**
   * Compare two numeric values of any type (double, int, uint) for equality.
   *
   * <p>Floating point values follow IEEE 754 standard for NaN and negative zero comparisons.
   */
  public static boolean numericEquals(Number x, Number y) {
    // Only x needs a NaN check: if y is NaN and x is not, numericCompare returns -1 != 0.
    return isNumeric(x)
        && isNumeric(y)
        && !(x instanceof Double && ((Double) x).isNaN())
        && numericCompare(x, y) == 0;
  }

  /**
   * Compares two numeric values of any type (double, int, uint) for sorting (ordering {@code NaN}
   * last and equal to itself, with {@code -0.0 == 0.0}); use {@link #numericEquals} for CEL {@code
   * ==} semantics.
   */
  public static int numericCompare(Number x, Number y) {
    if (x instanceof Double) {
      if (y instanceof Double) {
        return compareDouble((Double) x, (Double) y);
      }
      if (y instanceof Long) {
        return compareDoubleInt((Double) x, (Long) y);
      }
      if (y instanceof UnsignedLong) {
        return compareDoubleUint((Double) x, (UnsignedLong) y);
      }
    }
    if (x instanceof Long) {
      if (y instanceof Long) {
        return Long.compare((Long) x, (Long) y);
      }
      if (y instanceof Double) {
        return compareIntDouble((Long) x, (Double) y);
      }
      if (y instanceof UnsignedLong) {
        return compareIntUint((Long) x, (UnsignedLong) y);
      }
    }
    if (x instanceof UnsignedLong) {
      if (y instanceof UnsignedLong) {
        return UnsignedLongs.compare(x.longValue(), y.longValue());
      }
      if (y instanceof Double) {
        return compareUintDouble((UnsignedLong) x, (Double) y);
      }
      if (y instanceof Long) {
        return compareUintInt((UnsignedLong) x, (Long) y);
      }
    }
    throw new UnsupportedOperationException(
        "Unsupported argument types: " + x.getClass() + ", " + y.getClass());
  }

  private static int compareDouble(double a, double b) {
    // Treat -0.0 and 0.0 as equal via ==; Double.compare orders NaN after all non-NaN numbers.
    return a == b ? 0 : Double.compare(a, b);
  }

  private ComparisonFunctions() {}
}
