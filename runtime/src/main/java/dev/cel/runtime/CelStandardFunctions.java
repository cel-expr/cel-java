// Copyright 2024 Google LLC
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

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.collect.ImmutableSet.toImmutableSet;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.errorprone.annotations.Immutable;
import dev.cel.common.CelOptions;
import dev.cel.common.Operator;
import dev.cel.common.annotations.Internal;
import dev.cel.runtime.standard.AddOperator;
import dev.cel.runtime.standard.AddOperator.AddOverload;
import dev.cel.runtime.standard.BoolFunction;
import dev.cel.runtime.standard.BoolFunction.BoolOverload;
import dev.cel.runtime.standard.BytesFunction;
import dev.cel.runtime.standard.BytesFunction.BytesOverload;
import dev.cel.runtime.standard.CelStandardFunction;
import dev.cel.runtime.standard.CelStandardOverload;
import dev.cel.runtime.standard.ContainsFunction;
import dev.cel.runtime.standard.ContainsFunction.ContainsOverload;
import dev.cel.runtime.standard.DivideOperator;
import dev.cel.runtime.standard.DivideOperator.DivideOverload;
import dev.cel.runtime.standard.DoubleFunction;
import dev.cel.runtime.standard.DoubleFunction.DoubleOverload;
import dev.cel.runtime.standard.DurationFunction;
import dev.cel.runtime.standard.DurationFunction.DurationOverload;
import dev.cel.runtime.standard.DynFunction;
import dev.cel.runtime.standard.DynFunction.DynOverload;
import dev.cel.runtime.standard.EndsWithFunction;
import dev.cel.runtime.standard.EndsWithFunction.EndsWithOverload;
import dev.cel.runtime.standard.EqualsOperator;
import dev.cel.runtime.standard.EqualsOperator.EqualsOverload;
import dev.cel.runtime.standard.GetDateFunction;
import dev.cel.runtime.standard.GetDateFunction.GetDateOverload;
import dev.cel.runtime.standard.GetDayOfMonthFunction;
import dev.cel.runtime.standard.GetDayOfMonthFunction.GetDayOfMonthOverload;
import dev.cel.runtime.standard.GetDayOfWeekFunction;
import dev.cel.runtime.standard.GetDayOfWeekFunction.GetDayOfWeekOverload;
import dev.cel.runtime.standard.GetDayOfYearFunction;
import dev.cel.runtime.standard.GetDayOfYearFunction.GetDayOfYearOverload;
import dev.cel.runtime.standard.GetFullYearFunction;
import dev.cel.runtime.standard.GetFullYearFunction.GetFullYearOverload;
import dev.cel.runtime.standard.GetHoursFunction;
import dev.cel.runtime.standard.GetHoursFunction.GetHoursOverload;
import dev.cel.runtime.standard.GetMillisecondsFunction;
import dev.cel.runtime.standard.GetMillisecondsFunction.GetMillisecondsOverload;
import dev.cel.runtime.standard.GetMinutesFunction;
import dev.cel.runtime.standard.GetMinutesFunction.GetMinutesOverload;
import dev.cel.runtime.standard.GetMonthFunction;
import dev.cel.runtime.standard.GetMonthFunction.GetMonthOverload;
import dev.cel.runtime.standard.GetSecondsFunction;
import dev.cel.runtime.standard.GetSecondsFunction.GetSecondsOverload;
import dev.cel.runtime.standard.GreaterEqualsOperator;
import dev.cel.runtime.standard.GreaterEqualsOperator.GreaterEqualsOverload;
import dev.cel.runtime.standard.GreaterOperator;
import dev.cel.runtime.standard.GreaterOperator.GreaterOverload;
import dev.cel.runtime.standard.InOperator;
import dev.cel.runtime.standard.InOperator.InOverload;
import dev.cel.runtime.standard.IndexOperator;
import dev.cel.runtime.standard.IndexOperator.IndexOverload;
import dev.cel.runtime.standard.IntFunction;
import dev.cel.runtime.standard.IntFunction.IntOverload;
import dev.cel.runtime.standard.LessEqualsOperator;
import dev.cel.runtime.standard.LessEqualsOperator.LessEqualsOverload;
import dev.cel.runtime.standard.LessOperator;
import dev.cel.runtime.standard.LessOperator.LessOverload;
import dev.cel.runtime.standard.LogicalNotOperator;
import dev.cel.runtime.standard.LogicalNotOperator.LogicalNotOverload;
import dev.cel.runtime.standard.MatchesFunction;
import dev.cel.runtime.standard.MatchesFunction.MatchesOverload;
import dev.cel.runtime.standard.ModuloOperator;
import dev.cel.runtime.standard.ModuloOperator.ModuloOverload;
import dev.cel.runtime.standard.MultiplyOperator;
import dev.cel.runtime.standard.MultiplyOperator.MultiplyOverload;
import dev.cel.runtime.standard.NegateOperator;
import dev.cel.runtime.standard.NegateOperator.NegateOverload;
import dev.cel.runtime.standard.NotEqualsOperator;
import dev.cel.runtime.standard.NotEqualsOperator.NotEqualsOverload;
import dev.cel.runtime.standard.NotStrictlyFalseFunction;
import dev.cel.runtime.standard.NotStrictlyFalseFunction.NotStrictlyFalseOverload;
import dev.cel.runtime.standard.SizeFunction;
import dev.cel.runtime.standard.SizeFunction.SizeOverload;
import dev.cel.runtime.standard.StartsWithFunction;
import dev.cel.runtime.standard.StartsWithFunction.StartsWithOverload;
import dev.cel.runtime.standard.StringFunction;
import dev.cel.runtime.standard.StringFunction.StringOverload;
import dev.cel.runtime.standard.SubtractOperator;
import dev.cel.runtime.standard.SubtractOperator.SubtractOverload;
import dev.cel.runtime.standard.TimestampFunction;
import dev.cel.runtime.standard.TimestampFunction.TimestampOverload;
import dev.cel.runtime.standard.UintFunction;
import dev.cel.runtime.standard.UintFunction.UintOverload;
import java.util.Collection;
import java.util.Map;

/** Runtime function bindings for the standard functions in CEL. */
@Immutable
public final class CelStandardFunctions {
  private static final ImmutableSet<CelStandardOverload> HETEROGENEOUS_COMPARISON_OPERATORS =
      ImmutableSet.of(
          LessOverload.LESS_DOUBLE_UINT64,
          LessOverload.LESS_INT64_UINT64,
          LessOverload.LESS_UINT64_INT64,
          LessOverload.LESS_INT64_DOUBLE,
          LessOverload.LESS_DOUBLE_INT64,
          LessOverload.LESS_UINT64_DOUBLE,
          LessEqualsOverload.LESS_EQUALS_INT64_UINT64,
          LessEqualsOverload.LESS_EQUALS_UINT64_INT64,
          LessEqualsOverload.LESS_EQUALS_INT64_DOUBLE,
          LessEqualsOverload.LESS_EQUALS_DOUBLE_INT64,
          LessEqualsOverload.LESS_EQUALS_UINT64_DOUBLE,
          LessEqualsOverload.LESS_EQUALS_DOUBLE_UINT64,
          GreaterOverload.GREATER_INT64_UINT64,
          GreaterOverload.GREATER_UINT64_INT64,
          GreaterOverload.GREATER_INT64_DOUBLE,
          GreaterOverload.GREATER_DOUBLE_INT64,
          GreaterOverload.GREATER_UINT64_DOUBLE,
          GreaterOverload.GREATER_DOUBLE_UINT64,
          GreaterEqualsOverload.GREATER_EQUALS_INT64_UINT64,
          GreaterEqualsOverload.GREATER_EQUALS_UINT64_INT64,
          GreaterEqualsOverload.GREATER_EQUALS_INT64_DOUBLE,
          GreaterEqualsOverload.GREATER_EQUALS_DOUBLE_INT64,
          GreaterEqualsOverload.GREATER_EQUALS_UINT64_DOUBLE,
          GreaterEqualsOverload.GREATER_EQUALS_DOUBLE_UINT64);

  /** An empty instance of {@link CelStandardFunctions} with no functions. */
  public static final CelStandardFunctions EMPTY =
      new CelStandardFunctions(ImmutableMultimap.of());

  private final ImmutableMultimap<String, CelStandardOverload> standardOverloads;

  public static final ImmutableSet<CelStandardFunction> ALL_STANDARD_FUNCTIONS =
      ImmutableSet.of(
          AddOperator.create(),
          BoolFunction.create(),
          BytesFunction.create(),
          ContainsFunction.create(),
          DivideOperator.create(),
          DoubleFunction.create(),
          DurationFunction.create(),
          DynFunction.create(),
          EndsWithFunction.create(),
          EqualsOperator.create(),
          GetDateFunction.create(),
          GetDayOfMonthFunction.create(),
          GetDayOfWeekFunction.create(),
          GetDayOfYearFunction.create(),
          GetFullYearFunction.create(),
          GetHoursFunction.create(),
          GetMillisecondsFunction.create(),
          GetMinutesFunction.create(),
          GetMonthFunction.create(),
          GetSecondsFunction.create(),
          GreaterEqualsOperator.create(),
          GreaterOperator.create(),
          IndexOperator.create(),
          InOperator.create(),
          IntFunction.create(),
          LessEqualsOperator.create(),
          LessOperator.create(),
          LogicalNotOperator.create(),
          MatchesFunction.create(),
          ModuloOperator.create(),
          MultiplyOperator.create(),
          NegateOperator.create(),
          NotEqualsOperator.create(),
          SizeFunction.create(),
          StartsWithFunction.create(),
          StringFunction.create(),
          SubtractOperator.create(),
          TimestampFunction.create(),
          UintFunction.create(),
          NotStrictlyFalseFunction.create());

  /**
   * Enumeration of Standard Function bindings.
   *
   * <p>Note: The conditional, logical_or, logical_and, and type functions are currently
   * special-cased, and does not appear in this enum.
   */
  public enum StandardFunction {
    LOGICAL_NOT(Operator.LOGICAL_NOT.getFunction(), LogicalNotOverload.LOGICAL_NOT),
    IN(Operator.IN.getFunction(), InOverload.IN_LIST, InOverload.IN_MAP),
    NOT_STRICTLY_FALSE(
        Operator.NOT_STRICTLY_FALSE.getFunction(), NotStrictlyFalseOverload.NOT_STRICTLY_FALSE),
    EQUALS(Operator.EQUALS.getFunction(), EqualsOverload.EQUALS),
    NOT_EQUALS(Operator.NOT_EQUALS.getFunction(), NotEqualsOverload.NOT_EQUALS),
    BOOL("bool", BoolOverload.BOOL_TO_BOOL, BoolOverload.STRING_TO_BOOL),
    ADD(
        Operator.ADD.getFunction(),
        AddOverload.ADD_INT64,
        AddOverload.ADD_UINT64,
        AddOverload.ADD_DOUBLE,
        AddOverload.ADD_STRING,
        AddOverload.ADD_BYTES,
        AddOverload.ADD_LIST,
        AddOverload.ADD_TIMESTAMP_DURATION,
        AddOverload.ADD_DURATION_TIMESTAMP,
        AddOverload.ADD_DURATION_DURATION),
    SUBTRACT(
        Operator.SUBTRACT.getFunction(),
        SubtractOverload.SUBTRACT_INT64,
        SubtractOverload.SUBTRACT_TIMESTAMP_TIMESTAMP,
        SubtractOverload.SUBTRACT_TIMESTAMP_DURATION,
        SubtractOverload.SUBTRACT_UINT64,
        SubtractOverload.SUBTRACT_DOUBLE,
        SubtractOverload.SUBTRACT_DURATION_DURATION),
    MULTIPLY(
        Operator.MULTIPLY.getFunction(),
        MultiplyOverload.MULTIPLY_INT64,
        MultiplyOverload.MULTIPLY_DOUBLE,
        MultiplyOverload.MULTIPLY_UINT64),
    DIVIDE(
        Operator.DIVIDE.getFunction(),
        DivideOverload.DIVIDE_DOUBLE,
        DivideOverload.DIVIDE_INT64,
        DivideOverload.DIVIDE_UINT64),
    MODULO(
        Operator.MODULO.getFunction(), ModuloOverload.MODULO_INT64, ModuloOverload.MODULO_UINT64),
    NEGATE(
        Operator.NEGATE.getFunction(), NegateOverload.NEGATE_INT64, NegateOverload.NEGATE_DOUBLE),
    INDEX(Operator.INDEX.getFunction(), IndexOverload.INDEX_LIST, IndexOverload.INDEX_MAP),
    SIZE(
        "size",
        SizeOverload.SIZE_STRING,
        SizeOverload.SIZE_BYTES,
        SizeOverload.SIZE_LIST,
        SizeOverload.SIZE_MAP,
        SizeOverload.STRING_SIZE,
        SizeOverload.BYTES_SIZE,
        SizeOverload.LIST_SIZE,
        SizeOverload.MAP_SIZE),
    INT(
        "int",
        IntOverload.INT64_TO_INT64,
        IntOverload.UINT64_TO_INT64,
        IntOverload.DOUBLE_TO_INT64,
        IntOverload.STRING_TO_INT64,
        IntOverload.TIMESTAMP_TO_INT64),
    UINT(
        "uint",
        UintOverload.UINT64_TO_UINT64,
        UintOverload.INT64_TO_UINT64,
        UintOverload.DOUBLE_TO_UINT64,
        UintOverload.STRING_TO_UINT64),
    DOUBLE(
        "double",
        DoubleOverload.DOUBLE_TO_DOUBLE,
        DoubleOverload.INT64_TO_DOUBLE,
        DoubleOverload.STRING_TO_DOUBLE,
        DoubleOverload.UINT64_TO_DOUBLE),
    STRING(
        "string",
        StringOverload.STRING_TO_STRING,
        StringOverload.INT64_TO_STRING,
        StringOverload.DOUBLE_TO_STRING,
        StringOverload.BOOL_TO_STRING,
        StringOverload.BYTES_TO_STRING,
        StringOverload.TIMESTAMP_TO_STRING,
        StringOverload.DURATION_TO_STRING,
        StringOverload.UINT64_TO_STRING),
    BYTES("bytes", BytesOverload.BYTES_TO_BYTES, BytesOverload.STRING_TO_BYTES),
    DURATION(
        "duration", DurationOverload.DURATION_TO_DURATION, DurationOverload.STRING_TO_DURATION),
    TIMESTAMP(
        "timestamp",
        TimestampOverload.STRING_TO_TIMESTAMP,
        TimestampOverload.TIMESTAMP_TO_TIMESTAMP,
        TimestampOverload.INT64_TO_TIMESTAMP),
    DYN("dyn", DynOverload.TO_DYN),
    MATCHES("matches", MatchesOverload.MATCHES, MatchesOverload.MATCHES_STRING),
    CONTAINS("contains", ContainsOverload.CONTAINS_STRING),
    ENDS_WITH("endsWith", EndsWithOverload.ENDS_WITH_STRING),
    STARTS_WITH("startsWith", StartsWithOverload.STARTS_WITH_STRING),
    // Date/time Functions
    GET_FULL_YEAR(
        "getFullYear",
        GetFullYearOverload.TIMESTAMP_TO_YEAR,
        GetFullYearOverload.TIMESTAMP_TO_YEAR_WITH_TZ),
    GET_MONTH(
        "getMonth",
        GetMonthOverload.TIMESTAMP_TO_MONTH,
        GetMonthOverload.TIMESTAMP_TO_MONTH_WITH_TZ),
    GET_DAY_OF_YEAR(
        "getDayOfYear",
        GetDayOfYearOverload.TIMESTAMP_TO_DAY_OF_YEAR,
        GetDayOfYearOverload.TIMESTAMP_TO_DAY_OF_YEAR_WITH_TZ),
    GET_DAY_OF_MONTH(
        "getDayOfMonth",
        GetDayOfMonthOverload.TIMESTAMP_TO_DAY_OF_MONTH,
        GetDayOfMonthOverload.TIMESTAMP_TO_DAY_OF_MONTH_WITH_TZ),
    GET_DATE(
        "getDate",
        GetDateOverload.TIMESTAMP_TO_DAY_OF_MONTH_1_BASED,
        GetDateOverload.TIMESTAMP_TO_DAY_OF_MONTH_1_BASED_WITH_TZ),
    GET_DAY_OF_WEEK(
        "getDayOfWeek",
        GetDayOfWeekOverload.TIMESTAMP_TO_DAY_OF_WEEK,
        GetDayOfWeekOverload.TIMESTAMP_TO_DAY_OF_WEEK_WITH_TZ),

    GET_HOURS(
        "getHours",
        GetHoursOverload.TIMESTAMP_TO_HOURS,
        GetHoursOverload.TIMESTAMP_TO_HOURS_WITH_TZ,
        GetHoursOverload.DURATION_TO_HOURS),
    GET_MINUTES(
        "getMinutes",
        GetMinutesOverload.TIMESTAMP_TO_MINUTES,
        GetMinutesOverload.TIMESTAMP_TO_MINUTES_WITH_TZ,
        GetMinutesOverload.DURATION_TO_MINUTES),
    GET_SECONDS(
        "getSeconds",
        GetSecondsOverload.TIMESTAMP_TO_SECONDS,
        GetSecondsOverload.TIMESTAMP_TO_SECONDS_WITH_TZ,
        GetSecondsOverload.DURATION_TO_SECONDS),
    GET_MILLISECONDS(
        "getMilliseconds",
        GetMillisecondsOverload.TIMESTAMP_TO_MILLISECONDS,
        GetMillisecondsOverload.TIMESTAMP_TO_MILLISECONDS_WITH_TZ,
        GetMillisecondsOverload.DURATION_TO_MILLISECONDS),
    LESS(
        Operator.LESS.getFunction(),
        LessOverload.LESS_BOOL,
        LessOverload.LESS_INT64,
        LessOverload.LESS_UINT64,
        LessOverload.LESS_DOUBLE,
        LessOverload.LESS_STRING,
        LessOverload.LESS_BYTES,
        LessOverload.LESS_TIMESTAMP,
        LessOverload.LESS_DURATION,
        LessOverload.LESS_INT64_UINT64,
        LessOverload.LESS_UINT64_INT64,
        LessOverload.LESS_INT64_DOUBLE,
        LessOverload.LESS_DOUBLE_INT64,
        LessOverload.LESS_UINT64_DOUBLE,
        LessOverload.LESS_DOUBLE_UINT64),
    LESS_EQUALS(
        Operator.LESS_EQUALS.getFunction(),
        LessEqualsOverload.LESS_EQUALS_BOOL,
        LessEqualsOverload.LESS_EQUALS_INT64,
        LessEqualsOverload.LESS_EQUALS_UINT64,
        LessEqualsOverload.LESS_EQUALS_DOUBLE,
        LessEqualsOverload.LESS_EQUALS_STRING,
        LessEqualsOverload.LESS_EQUALS_BYTES,
        LessEqualsOverload.LESS_EQUALS_TIMESTAMP,
        LessEqualsOverload.LESS_EQUALS_DURATION,
        LessEqualsOverload.LESS_EQUALS_INT64_UINT64,
        LessEqualsOverload.LESS_EQUALS_UINT64_INT64,
        LessEqualsOverload.LESS_EQUALS_INT64_DOUBLE,
        LessEqualsOverload.LESS_EQUALS_DOUBLE_INT64,
        LessEqualsOverload.LESS_EQUALS_UINT64_DOUBLE,
        LessEqualsOverload.LESS_EQUALS_DOUBLE_UINT64),
    GREATER(
        Operator.GREATER.getFunction(),
        GreaterOverload.GREATER_BOOL,
        GreaterOverload.GREATER_INT64,
        GreaterOverload.GREATER_UINT64,
        GreaterOverload.GREATER_DOUBLE,
        GreaterOverload.GREATER_STRING,
        GreaterOverload.GREATER_BYTES,
        GreaterOverload.GREATER_TIMESTAMP,
        GreaterOverload.GREATER_DURATION,
        GreaterOverload.GREATER_INT64_UINT64,
        GreaterOverload.GREATER_UINT64_INT64,
        GreaterOverload.GREATER_INT64_DOUBLE,
        GreaterOverload.GREATER_DOUBLE_INT64,
        GreaterOverload.GREATER_UINT64_DOUBLE,
        GreaterOverload.GREATER_DOUBLE_UINT64),
    GREATER_EQUALS(
        Operator.GREATER_EQUALS.getFunction(),
        GreaterEqualsOverload.GREATER_EQUALS_BOOL,
        GreaterEqualsOverload.GREATER_EQUALS_BYTES,
        GreaterEqualsOverload.GREATER_EQUALS_DOUBLE,
        GreaterEqualsOverload.GREATER_EQUALS_DURATION,
        GreaterEqualsOverload.GREATER_EQUALS_INT64,
        GreaterEqualsOverload.GREATER_EQUALS_STRING,
        GreaterEqualsOverload.GREATER_EQUALS_TIMESTAMP,
        GreaterEqualsOverload.GREATER_EQUALS_UINT64,
        GreaterEqualsOverload.GREATER_EQUALS_INT64_UINT64,
        GreaterEqualsOverload.GREATER_EQUALS_UINT64_INT64,
        GreaterEqualsOverload.GREATER_EQUALS_INT64_DOUBLE,
        GreaterEqualsOverload.GREATER_EQUALS_DOUBLE_INT64,
        GreaterEqualsOverload.GREATER_EQUALS_UINT64_DOUBLE,
        GreaterEqualsOverload.GREATER_EQUALS_DOUBLE_UINT64);

    private final String functionName;
    private final ImmutableSet<CelStandardOverload> standardOverloads;

    StandardFunction(String functionName, CelStandardOverload... overloads) {
      this.functionName = functionName;
      this.standardOverloads = ImmutableSet.copyOf(overloads);
    }

    @VisibleForTesting
    ImmutableSet<CelStandardOverload> getOverloads() {
      return standardOverloads;
    }
  }

  @VisibleForTesting
  ImmutableSet<CelStandardOverload> getOverloads() {
    return ImmutableSet.copyOf(standardOverloads.values());
  }

  @Internal
  public ImmutableSet<CelFunctionBinding> newFunctionBindings(
      RuntimeEquality runtimeEquality, CelOptions celOptions) {
    ImmutableSet.Builder<CelFunctionBinding> builder = ImmutableSet.builder();

    for (Map.Entry<String, Collection<CelStandardOverload>> entry :
        standardOverloads.asMap().entrySet()) {
      String functionName = entry.getKey();
      Collection<CelStandardOverload> overloads = entry.getValue();

      ImmutableSet<CelFunctionBinding> bindings =
          overloads.stream()
              .map(o -> o.newFunctionBinding(celOptions, runtimeEquality))
              .collect(toImmutableSet());

      builder.addAll(CelFunctionBinding.fromOverloads(functionName, bindings));
    }

    return builder.build();
  }

  /** Builder for constructing the set of standard function/identifiers. */
  public static final class Builder {
    private ImmutableSet<StandardFunction> includeFunctions;
    private ImmutableSet<StandardFunction> excludeFunctions;

    private FunctionFilter functionFilter;

    private Builder() {
      this.includeFunctions = ImmutableSet.of();
      this.excludeFunctions = ImmutableSet.of();
    }

    @CanIgnoreReturnValue
    public Builder excludeFunctions(StandardFunction... functions) {
      return excludeFunctions(ImmutableSet.copyOf(functions));
    }

    @CanIgnoreReturnValue
    public Builder excludeFunctions(Iterable<StandardFunction> functions) {
      this.excludeFunctions = checkNotNull(ImmutableSet.copyOf(functions));
      return this;
    }

    @CanIgnoreReturnValue
    public Builder includeFunctions(StandardFunction... functions) {
      return includeFunctions(ImmutableSet.copyOf(functions));
    }

    @CanIgnoreReturnValue
    public Builder includeFunctions(Iterable<StandardFunction> functions) {
      this.includeFunctions = checkNotNull(ImmutableSet.copyOf(functions));
      return this;
    }

    @CanIgnoreReturnValue
    public Builder filterFunctions(FunctionFilter functionFilter) {
      this.functionFilter = functionFilter;
      return this;
    }

    private static void assertOneSettingIsSet(
        boolean a, boolean b, boolean c, String errorMessage) {
      int count = 0;
      if (a) {
        count++;
      }
      if (b) {
        count++;
      }
      if (c) {
        count++;
      }

      if (count > 1) {
        throw new IllegalArgumentException(errorMessage);
      }
    }

    public CelStandardFunctions build() {
      boolean hasIncludeFunctions = !this.includeFunctions.isEmpty();
      boolean hasExcludeFunctions = !this.excludeFunctions.isEmpty();
      boolean hasFilterFunction = this.functionFilter != null;
      assertOneSettingIsSet(
          hasIncludeFunctions,
          hasExcludeFunctions,
          hasFilterFunction,
          "You may only populate one of the following builder methods: includeFunctions,"
              + " excludeFunctions or filterFunctions");

      ImmutableMultimap.Builder<String, CelStandardOverload> standardOverloadBuilder =
          ImmutableMultimap.builder();
      for (StandardFunction standardFunction : StandardFunction.values()) {
        if (hasIncludeFunctions) {
          if (this.includeFunctions.contains(standardFunction)) {
            standardOverloadBuilder.putAll(
                standardFunction.functionName, standardFunction.standardOverloads);
          }
          continue;
        }
        if (hasExcludeFunctions) {
          if (!this.excludeFunctions.contains(standardFunction)) {
            standardOverloadBuilder.putAll(
                standardFunction.functionName, standardFunction.standardOverloads);
          }
          continue;
        }
        if (hasFilterFunction) {
          for (CelStandardOverload standardOverload : standardFunction.standardOverloads) {
            boolean includeOverload = functionFilter.include(standardFunction, standardOverload);
            if (includeOverload) {
              standardOverloadBuilder.put(standardFunction.functionName, standardOverload);
            }
          }

          continue;
        }

        standardOverloadBuilder.putAll(
            standardFunction.functionName, standardFunction.standardOverloads);
      }

      return new CelStandardFunctions(standardOverloadBuilder.build());
    }

    /**
     * Functional interface for filtering standard functions. Returning true in the callback will
     * include the function in the environment.
     */
    @FunctionalInterface
    public interface FunctionFilter {
      boolean include(StandardFunction standardFunction, CelStandardOverload standardOverload);
    }
  }

  /** Creates a new builder to configure CelStandardFunctions. */
  public static Builder newBuilder() {
    return new Builder();
  }

  static boolean isHeterogeneousComparison(CelStandardOverload overload) {
    return HETEROGENEOUS_COMPARISON_OPERATORS.contains(overload);
  }

  private CelStandardFunctions(ImmutableMultimap<String, CelStandardOverload> standardOverloads) {
    this.standardOverloads = standardOverloads;
  }
}
