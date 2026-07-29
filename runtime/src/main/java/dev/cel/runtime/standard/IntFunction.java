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

import com.google.common.collect.ImmutableSet;
import com.google.common.primitives.UnsignedLong;
import com.google.protobuf.Timestamp;
import dev.cel.common.CelOptions;
import dev.cel.common.exceptions.CelBadFormatException;
import dev.cel.common.exceptions.CelNumericOverflowException;
import dev.cel.common.internal.ProtoTimeUtils;
import dev.cel.runtime.CelFunctionBinding;
import dev.cel.runtime.RuntimeEquality;
import dev.cel.runtime.RuntimeHelpers;
import java.time.Instant;
import java.util.Arrays;

/** Standard function for {@code int} conversion function. */
public final class IntFunction extends CelStandardFunction {
  private static final IntFunction ALL_OVERLOADS = create(IntOverload.values());

  public static IntFunction create() {
    return ALL_OVERLOADS;
  }

  public static IntFunction create(IntFunction.IntOverload... overloads) {
    return create(Arrays.asList(overloads));
  }

  public static IntFunction create(Iterable<IntFunction.IntOverload> overloads) {
    return new IntFunction(ImmutableSet.copyOf(overloads));
  }

  /** Overloads for the standard function. */
  public enum IntOverload implements CelStandardOverload {
    INT64_TO_INT64(
        (celOptions, runtimeEquality) ->
            CelFunctionBinding.from("int64_to_int64", Long.class, (Long x) -> x)),
    UINT64_TO_INT64(
        (celOptions, runtimeEquality) -> {
          if (celOptions.enableUnsignedLongs()) {
            return CelFunctionBinding.from(
                "uint64_to_int64",
                UnsignedLong.class,
                (UnsignedLong arg) -> {
                  if (arg.compareTo(UnsignedLong.valueOf(Long.MAX_VALUE)) > 0) {
                    throw new CelNumericOverflowException("unsigned out of int range");
                  }
                  return arg.longValue();
                });
          } else {
            return CelFunctionBinding.from(
                "uint64_to_int64",
                Long.class,
                (Long arg) -> {
                  if (celOptions.errorOnIntWrap() && arg < 0) {
                    throw new CelNumericOverflowException("unsigned out of int range");
                  }
                  return arg;
                });
          }
        }),
    DOUBLE_TO_INT64(
        (celOptions, runtimeEquality) ->
            CelFunctionBinding.from(
                "double_to_int64",
                Double.class,
                (Double arg) -> {
                  if (celOptions.errorOnIntWrap()) {
                    return RuntimeHelpers.doubleToLongChecked(arg)
                        .orElseThrow(
                            () ->
                                new CelNumericOverflowException("double is out of range for int"));
                  }
                  return arg.longValue();
                })),
    STRING_TO_INT64(
        (celOptions, runtimeEquality) ->
            CelFunctionBinding.from(
                "string_to_int64",
                String.class,
                (String arg) -> {
                  try {
                    return Long.parseLong(arg);
                  } catch (NumberFormatException e) {
                    throw new CelBadFormatException(e);
                  }
                })),
    TIMESTAMP_TO_INT64(
        (celOptions, runtimeEquality) -> {
          if (celOptions.evaluateCanonicalTypesToNativeValues()) {
            return CelFunctionBinding.from(
                "timestamp_to_int64", Instant.class, Instant::getEpochSecond);
          } else {
            return CelFunctionBinding.from(
                "timestamp_to_int64", Timestamp.class, ProtoTimeUtils::toSeconds);
          }
        }),
    ;

    private final CelStandardOverload standardOverload;

    @Override
    public CelFunctionBinding newFunctionBinding(
        CelOptions celOptions, RuntimeEquality runtimeEquality) {
      return standardOverload.newFunctionBinding(celOptions, runtimeEquality);
    }

    IntOverload(CelStandardOverload standardOverload) {
      this.standardOverload = standardOverload;
    }
  }

  private IntFunction(ImmutableSet<CelStandardOverload> overloads) {
    super("int", overloads);
  }
}
