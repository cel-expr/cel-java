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

package dev.cel.verifier;

import com.google.auto.value.AutoValue;
import com.google.common.primitives.UnsignedLong;
import dev.cel.common.annotations.Internal;
import java.util.Optional;

/**
 * Utility for computing matching integer and unsigned integer ranges for IEEE-754 double-precision
 * floating-point constants in Z3 verification.
 */
@Internal
public final class CelNumericBounds {

  /** Minimum representable signed 64-bit integer string. */
  public static final String MIN_INT64 = "-9223372036854775808";

  /** Maximum representable signed 64-bit integer string. */
  public static final String MAX_INT64 = "9223372036854775807";

  /** Maximum representable unsigned 64-bit integer string. */
  public static final String MAX_UINT64 = "18446744073709551615";

  private static final double TWO_TO_63 = Math.scalb(1.0, 63);
  private static final double TWO_TO_64 = Math.scalb(1.0, 64);

  @AutoValue
  abstract static class IntRange {
    abstract long min();

    abstract long max();

    static IntRange of(long min, long max) {
      return new AutoValue_CelNumericBounds_IntRange(min, max);
    }
  }

  @AutoValue
  abstract static class UintRange {
    abstract String min();

    abstract String max();

    static UintRange of(String min, String max) {
      return new AutoValue_CelNumericBounds_UintRange(min, max);
    }
  }

  private static boolean isMathematicalInteger(double vDouble) {
    return Double.isFinite(vDouble) && vDouble == Math.rint(vDouble);
  }

  static Optional<IntRange> getMatchingIntRange(double vDouble) {
    if (!isMathematicalInteger(vDouble) || vDouble < -TWO_TO_63 || vDouble > TWO_TO_63) {
      return Optional.empty();
    }
    long minL = (long) vDouble;
    while (minL > Long.MIN_VALUE && (double) (minL - 1) == vDouble) {
      minL--;
    }
    long maxL = (long) vDouble;
    while (maxL < Long.MAX_VALUE && (double) (maxL + 1) == vDouble) {
      maxL++;
    }
    return Optional.of(IntRange.of(minL, maxL));
  }

  static Optional<UintRange> getMatchingUintRange(double vDouble) {
    if (!isMathematicalInteger(vDouble) || vDouble < 0 || vDouble > TWO_TO_64) {
      return Optional.empty();
    }
    // XOR with Long.MIN_VALUE (0x8000000000000000L) flips bit 63 to 1, encoding unsigned values
    // >= 2^63 into Java's two's-complement signed long representation.
    long uBits =
        vDouble < TWO_TO_63 ? (long) vDouble : (long) (vDouble - TWO_TO_63) ^ Long.MIN_VALUE;
    UnsignedLong uVal = UnsignedLong.fromLongBits(uBits);
    UnsignedLong minU = uVal;
    while (!minU.equals(UnsignedLong.ZERO)
        && minU.minus(UnsignedLong.ONE).doubleValue() == vDouble) {
      minU = minU.minus(UnsignedLong.ONE);
    }
    UnsignedLong maxU = uVal;
    while (!maxU.equals(UnsignedLong.MAX_VALUE)
        && maxU.plus(UnsignedLong.ONE).doubleValue() == vDouble) {
      maxU = maxU.plus(UnsignedLong.ONE);
    }
    return Optional.of(UintRange.of(minU.toString(), maxU.toString()));
  }

  private CelNumericBounds() {}
}
