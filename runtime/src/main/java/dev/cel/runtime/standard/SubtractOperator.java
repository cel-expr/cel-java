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

package dev.cel.runtime.standard;

import static dev.cel.common.Operator.SUBTRACT;

import com.google.common.collect.ImmutableSet;
import com.google.common.primitives.UnsignedLong;
import com.google.protobuf.Duration;
import com.google.protobuf.Timestamp;
import dev.cel.common.CelOptions;
import dev.cel.common.exceptions.CelNumericOverflowException;
import dev.cel.common.internal.DateTimeHelpers;
import dev.cel.common.internal.ProtoTimeUtils;
import dev.cel.runtime.CelFunctionBinding;
import dev.cel.runtime.RuntimeEquality;
import dev.cel.runtime.RuntimeHelpers;
import java.time.Instant;
import java.util.Arrays;

/** Standard function for the subtraction (-) operator. */
public final class SubtractOperator extends CelStandardFunction {
  private static final SubtractOperator ALL_OVERLOADS = create(SubtractOverload.values());

  public static SubtractOperator create() {
    return ALL_OVERLOADS;
  }

  public static SubtractOperator create(SubtractOverload... overloads) {
    return create(Arrays.asList(overloads));
  }

  public static SubtractOperator create(Iterable<SubtractOverload> overloads) {
    return new SubtractOperator(ImmutableSet.copyOf(overloads));
  }

  /** Overloads for the standard function. */
  public enum SubtractOverload implements CelStandardOverload {
    SUBTRACT_INT64(
        (celOptions, runtimeEquality) ->
            CelFunctionBinding.from(
                "subtract_int64",
                Long.class,
                Long.class,
                (Long x, Long y) -> {
                  try {
                    return RuntimeHelpers.int64Subtract(x, y, celOptions);
                  } catch (ArithmeticException e) {
                    throw new CelNumericOverflowException(e);
                  }
                })),
    SUBTRACT_TIMESTAMP_TIMESTAMP(
        (celOptions, runtimeEquality) -> {
          if (celOptions.evaluateCanonicalTypesToNativeValues()) {
            return CelFunctionBinding.from(
                "subtract_timestamp_timestamp",
                Instant.class,
                Instant.class,
                (Instant i1, Instant i2) -> {
                  java.time.Duration between = java.time.Duration.between(i2, i1);
                  if (celOptions.enableTimestampOverflowCheck()) {
                    try {
                      // Call toNanos() to validate 64-bit nanosecond overflow (throws
                      // ArithmeticException).
                      @SuppressWarnings("unused")
                      long unused = between.toNanos();
                    } catch (ArithmeticException e) {
                      throw new CelNumericOverflowException(e);
                    }
                  }
                  return between;
                });
          } else {
            return CelFunctionBinding.from(
                "subtract_timestamp_timestamp",
                Timestamp.class,
                Timestamp.class,
                (Timestamp t1, Timestamp t2) -> {
                  try {
                    return ProtoTimeUtils.between(
                        t2, t1, celOptions.enableTimestampOverflowCheck());
                  } catch (ArithmeticException e) {
                    throw new CelNumericOverflowException(e);
                  }
                });
          }
        }),
    SUBTRACT_TIMESTAMP_DURATION(
        (celOptions, runtimeEquality) -> {
          if (celOptions.evaluateCanonicalTypesToNativeValues()) {
            return CelFunctionBinding.from(
                "subtract_timestamp_duration",
                Instant.class,
                java.time.Duration.class,
                DateTimeHelpers::subtract);
          } else {
            return CelFunctionBinding.from(
                "subtract_timestamp_duration",
                Timestamp.class,
                Duration.class,
                ProtoTimeUtils::subtract);
          }
        }),
    SUBTRACT_UINT64(
        (celOptions, runtimeEquality) -> {
          if (celOptions.enableUnsignedLongs()) {
            return CelFunctionBinding.from(
                "subtract_uint64",
                UnsignedLong.class,
                UnsignedLong.class,
                (UnsignedLong x, UnsignedLong y) -> {
                  try {
                    return RuntimeHelpers.uint64Subtract(x, y);
                  } catch (ArithmeticException e) {
                    throw new CelNumericOverflowException(e);
                  }
                });
          } else {
            return CelFunctionBinding.from(
                "subtract_uint64",
                Long.class,
                Long.class,
                (Long x, Long y) -> {
                  try {
                    return RuntimeHelpers.uint64Subtract(x, y, celOptions);
                  } catch (ArithmeticException e) {
                    throw new CelNumericOverflowException(e);
                  }
                });
          }
        }),
    SUBTRACT_DOUBLE(
        (celOptions, runtimeEquality) ->
            CelFunctionBinding.from(
                "subtract_double", Double.class, Double.class, (Double x, Double y) -> x - y)),
    SUBTRACT_DURATION_DURATION(
        (celOptions, runtimeEquality) -> {
          if (celOptions.evaluateCanonicalTypesToNativeValues()) {
            return CelFunctionBinding.from(
                "subtract_duration_duration",
                java.time.Duration.class,
                java.time.Duration.class,
                DateTimeHelpers::subtract);
          } else {
            return CelFunctionBinding.from(
                "subtract_duration_duration",
                Duration.class,
                Duration.class,
                ProtoTimeUtils::subtract);
          }
        }),
    ;

    private final CelStandardOverload standardOverload;

    @Override
    public CelFunctionBinding newFunctionBinding(
        CelOptions celOptions, RuntimeEquality runtimeEquality) {
      return standardOverload.newFunctionBinding(celOptions, runtimeEquality);
    }

    SubtractOverload(CelStandardOverload standardOverload) {
      this.standardOverload = standardOverload;
    }
  }

  private SubtractOperator(ImmutableSet<CelStandardOverload> overloads) {
    super(SUBTRACT.getFunction(), overloads);
  }
}
