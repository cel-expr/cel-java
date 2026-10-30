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

import static dev.cel.common.Operator.LESS_EQUALS;

import com.google.common.collect.ImmutableSet;
import com.google.common.primitives.UnsignedLong;
import com.google.protobuf.ByteString;
import com.google.protobuf.Duration;
import com.google.protobuf.Timestamp;
import dev.cel.common.CelOptions;
import dev.cel.common.internal.ComparisonFunctions;
import dev.cel.common.internal.ProtoTimeUtils;
import dev.cel.common.values.CelByteString;
import dev.cel.runtime.CelFunctionBinding;
import dev.cel.runtime.RuntimeEquality;
import dev.cel.runtime.RuntimeHelpers;
import java.time.Instant;
import java.util.Arrays;

/** Standard function for the less equals (<=) operator. */
public final class LessEqualsOperator extends CelStandardFunction {
  private static final LessEqualsOperator ALL_OVERLOADS = create(LessEqualsOverload.values());

  public static LessEqualsOperator create() {
    return ALL_OVERLOADS;
  }

  public static LessEqualsOperator create(LessEqualsOperator.LessEqualsOverload... overloads) {
    return create(Arrays.asList(overloads));
  }

  public static LessEqualsOperator create(
      Iterable<LessEqualsOperator.LessEqualsOverload> overloads) {
    return new LessEqualsOperator(ImmutableSet.copyOf(overloads));
  }

  /** Overloads for the standard function. */
  public enum LessEqualsOverload implements CelStandardOverload {
    LESS_EQUALS_BOOL(
        (celOptions, runtimeEquality) ->
            CelFunctionBinding.from(
                "less_equals_bool",
                Boolean.class,
                Boolean.class,
                (Boolean x, Boolean y) -> !x || y)),
    LESS_EQUALS_BYTES(
        (celOptions, runtimeEquality) -> {
          if (celOptions.evaluateCanonicalTypesToNativeValues()) {
            return CelFunctionBinding.from(
                "less_equals_bytes",
                CelByteString.class,
                CelByteString.class,
                (CelByteString x, CelByteString y) ->
                    CelByteString.unsignedLexicographicalComparator().compare(x, y) <= 0);
          } else {
            return CelFunctionBinding.from(
                "less_equals_bytes",
                ByteString.class,
                ByteString.class,
                (ByteString x, ByteString y) ->
                    ByteString.unsignedLexicographicalComparator().compare(x, y) <= 0);
          }
        }),
    LESS_EQUALS_DOUBLE(
        (celOptions, runtimeEquality) ->
            CelFunctionBinding.from(
                "less_equals_double", Double.class, Double.class, (Double x, Double y) -> x <= y)),
    LESS_EQUALS_DURATION(
        (celOptions, runtimeEquality) -> {
          if (celOptions.evaluateCanonicalTypesToNativeValues()) {
            return CelFunctionBinding.from(
                "less_equals_duration",
                java.time.Duration.class,
                java.time.Duration.class,
                (java.time.Duration d1, java.time.Duration d2) -> d1.compareTo(d2) <= 0);
          } else {
            return CelFunctionBinding.from(
                "less_equals_duration",
                Duration.class,
                Duration.class,
                (Duration d1, Duration d2) -> ProtoTimeUtils.compare(d1, d2) <= 0);
          }
        }),
    LESS_EQUALS_INT64(
        (celOptions, runtimeEquality) ->
            CelFunctionBinding.from(
                "less_equals_int64", Long.class, Long.class, (Long x, Long y) -> x <= y)),
    LESS_EQUALS_STRING(
        (celOptions, runtimeEquality) ->
            CelFunctionBinding.from(
                "less_equals_string",
                String.class,
                String.class,
                (String x, String y) -> x.compareTo(y) <= 0)),
    LESS_EQUALS_TIMESTAMP(
        (celOptions, runtimeEquality) -> {
          if (celOptions.evaluateCanonicalTypesToNativeValues()) {
            return CelFunctionBinding.from(
                "less_equals_timestamp",
                Instant.class,
                Instant.class,
                (Instant i1, Instant i2) -> i1.compareTo(i2) <= 0);
          } else {
            return CelFunctionBinding.from(
                "less_equals_timestamp",
                Timestamp.class,
                Timestamp.class,
                (Timestamp t1, Timestamp t2) -> ProtoTimeUtils.compare(t1, t2) <= 0);
          }
        }),
    LESS_EQUALS_UINT64(
        (celOptions, runtimeEquality) -> {
          if (celOptions.enableUnsignedLongs()) {
            return CelFunctionBinding.from(
                "less_equals_uint64",
                UnsignedLong.class,
                UnsignedLong.class,
                (UnsignedLong x, UnsignedLong y) -> RuntimeHelpers.uint64CompareTo(x, y) <= 0);
          } else {
            return CelFunctionBinding.from(
                "less_equals_uint64",
                Long.class,
                Long.class,
                (Long x, Long y) -> RuntimeHelpers.uint64CompareTo(x, y, celOptions) <= 0);
          }
        }),
    LESS_EQUALS_INT64_UINT64(
        (celOptions, runtimeEquality) ->
            CelFunctionBinding.from(
                "less_equals_int64_uint64",
                Long.class,
                UnsignedLong.class,
                (Long x, UnsignedLong y) -> ComparisonFunctions.compareIntUint(x, y) <= 0)),
    LESS_EQUALS_UINT64_INT64(
        (celOptions, runtimeEquality) ->
            CelFunctionBinding.from(
                "less_equals_uint64_int64",
                UnsignedLong.class,
                Long.class,
                (UnsignedLong x, Long y) -> ComparisonFunctions.compareUintInt(x, y) <= 0)),
    // Use IEEE 754 primitive comparison so NaN yields false (ComparisonFunctions orders NaN last).
    LESS_EQUALS_INT64_DOUBLE(
        (celOptions, runtimeEquality) ->
            CelFunctionBinding.from(
                "less_equals_int64_double",
                Long.class,
                Double.class,
                (Long x, Double y) -> (double) x <= y)),
    LESS_EQUALS_DOUBLE_INT64(
        (celOptions, runtimeEquality) ->
            CelFunctionBinding.from(
                "less_equals_double_int64",
                Double.class,
                Long.class,
                (Double x, Long y) -> x <= (double) y)),
    LESS_EQUALS_UINT64_DOUBLE(
        (celOptions, runtimeEquality) ->
            CelFunctionBinding.from(
                "less_equals_uint64_double",
                UnsignedLong.class,
                Double.class,
                (UnsignedLong x, Double y) -> x.doubleValue() <= y)),
    LESS_EQUALS_DOUBLE_UINT64(
        (celOptions, runtimeEquality) ->
            CelFunctionBinding.from(
                "less_equals_double_uint64",
                Double.class,
                UnsignedLong.class,
                (Double x, UnsignedLong y) -> x <= y.doubleValue())),
    ;

    private final CelStandardOverload standardOverload;

    @Override
    public CelFunctionBinding newFunctionBinding(
        CelOptions celOptions, RuntimeEquality runtimeEquality) {
      return standardOverload.newFunctionBinding(celOptions, runtimeEquality);
    }

    LessEqualsOverload(CelStandardOverload standardOverload) {
      this.standardOverload = standardOverload;
    }
  }

  private LessEqualsOperator(ImmutableSet<CelStandardOverload> overloads) {
    super(LESS_EQUALS.getFunction(), overloads);
  }
}
