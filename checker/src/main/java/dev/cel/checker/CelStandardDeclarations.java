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

package dev.cel.checker;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static java.util.Arrays.stream;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.errorprone.annotations.Immutable;
import dev.cel.common.CelFunctionDecl;
import dev.cel.common.CelOverloadDecl;
import dev.cel.common.Operator;
import dev.cel.common.types.CelType;
import dev.cel.common.types.CelTypes;
import dev.cel.common.types.ListType;
import dev.cel.common.types.MapType;
import dev.cel.common.types.SimpleType;
import dev.cel.common.types.TypeParamType;
import dev.cel.common.types.TypeType;
import java.util.Optional;

/**
 * Standard declarations for CEL.
 *
 * <p>Refer to <a
 * href="https://github.com/google/cel-spec/blob/master/doc/langdef.md#standard-definitions">CEL
 * Specification<a> for comprehensive listing of functions and identifiers included in the standard
 * environment.
 */
@Immutable
public final class CelStandardDeclarations {
  // Some shortcuts we use when building declarations.
  private static final TypeParamType TYPE_PARAM_A = TypeParamType.create("A");
  private static final ListType LIST_OF_A = ListType.create(TYPE_PARAM_A);
  private static final TypeParamType TYPE_PARAM_B = TypeParamType.create("B");
  private static final MapType MAP_OF_AB = MapType.create(TYPE_PARAM_A, TYPE_PARAM_B);

  private final ImmutableSet<CelFunctionDecl> celFunctionDecls;
  private final ImmutableSet<CelIdentDecl> celIdentDecls;

  /** Enumeration of Standard Functions. */
  public enum StandardFunction {
    // Deprecated - use {@link #IN}
    OLD_IN(
        true,
        Operator.OLD_IN.getFunction(),
        () ->
            CelOverloadDecl.newGlobalOverload(
                "in_list", "list membership", SimpleType.BOOL, TYPE_PARAM_A, LIST_OF_A),
        () ->
            CelOverloadDecl.newGlobalOverload(
                "in_map", "map key membership", SimpleType.BOOL, TYPE_PARAM_A, MAP_OF_AB)),

    // Deprecated - use {@link #NOT_STRICTLY_FALSE}
    OLD_NOT_STRICTLY_FALSE(
        true,
        Operator.OLD_NOT_STRICTLY_FALSE.getFunction(),
        () ->
            CelOverloadDecl.newGlobalOverload(
                "not_strictly_false",
                "false if argument is false, true otherwise (including errors and unknowns)",
                SimpleType.BOOL,
                SimpleType.BOOL)),

    // Internal (rewritten by macro)
    IN(Operator.IN, Overload.InternalOperator.IN_LIST, Overload.InternalOperator.IN_MAP),
    NOT_STRICTLY_FALSE(Operator.NOT_STRICTLY_FALSE, Overload.InternalOperator.NOT_STRICTLY_FALSE),
    TYPE("type", Overload.InternalOperator.TYPE),

    // Booleans
    CONDITIONAL(Operator.CONDITIONAL, Overload.BooleanOperator.CONDITIONAL),
    LOGICAL_NOT(Operator.LOGICAL_NOT, Overload.BooleanOperator.LOGICAL_NOT),
    LOGICAL_OR(Operator.LOGICAL_OR, Overload.BooleanOperator.LOGICAL_OR),
    LOGICAL_AND(Operator.LOGICAL_AND, Overload.BooleanOperator.LOGICAL_AND),

    // Relations
    EQUALS(Operator.EQUALS, Overload.Relation.EQUALS),
    NOT_EQUALS(Operator.NOT_EQUALS, Overload.Relation.NOT_EQUALS),

    // Arithmetic
    ADD(
        Operator.ADD,
        Overload.Arithmetic.ADD_INT64,
        Overload.Arithmetic.ADD_UINT64,
        Overload.Arithmetic.ADD_DOUBLE,
        Overload.Arithmetic.ADD_STRING,
        Overload.Arithmetic.ADD_BYTES,
        Overload.Arithmetic.ADD_LIST,
        Overload.Arithmetic.ADD_TIMESTAMP_DURATION,
        Overload.Arithmetic.ADD_DURATION_TIMESTAMP,
        Overload.Arithmetic.ADD_DURATION_DURATION),
    SUBTRACT(
        Operator.SUBTRACT,
        Overload.Arithmetic.SUBTRACT_INT64,
        Overload.Arithmetic.SUBTRACT_UINT64,
        Overload.Arithmetic.SUBTRACT_DOUBLE,
        Overload.Arithmetic.SUBTRACT_TIMESTAMP_TIMESTAMP,
        Overload.Arithmetic.SUBTRACT_TIMESTAMP_DURATION,
        Overload.Arithmetic.SUBTRACT_DURATION_DURATION),
    MULTIPLY(
        Operator.MULTIPLY,
        Overload.Arithmetic.MULTIPLY_INT64,
        Overload.Arithmetic.MULTIPLY_UINT64,
        Overload.Arithmetic.MULTIPLY_DOUBLE),
    DIVIDE(
        Operator.DIVIDE,
        Overload.Arithmetic.DIVIDE_INT64,
        Overload.Arithmetic.DIVIDE_UINT64,
        Overload.Arithmetic.DIVIDE_DOUBLE),
    MODULO(Operator.MODULO, Overload.Arithmetic.MODULO_INT64, Overload.Arithmetic.MODULO_UINT64),

    NEGATE(Operator.NEGATE, Overload.Arithmetic.NEGATE_INT64, Overload.Arithmetic.NEGATE_DOUBLE),

    // Index
    INDEX(Operator.INDEX, Overload.Index.INDEX_LIST, Overload.Index.INDEX_MAP),

    // Size
    SIZE(
        "size",
        Overload.Size.SIZE_STRING,
        Overload.Size.SIZE_BYTES,
        Overload.Size.SIZE_LIST,
        Overload.Size.SIZE_MAP,
        Overload.Size.STRING_SIZE,
        Overload.Size.BYTES_SIZE,
        Overload.Size.LIST_SIZE,
        Overload.Size.MAP_SIZE),

    // Conversions
    INT(
        "int",
        Overload.Conversions.INT64_TO_INT64,
        Overload.Conversions.UINT64_TO_INT64,
        Overload.Conversions.DOUBLE_TO_INT64,
        Overload.Conversions.STRING_TO_INT64,
        Overload.Conversions.TIMESTAMP_TO_INT64),
    UINT(
        "uint",
        Overload.Conversions.UINT64_TO_UINT64,
        Overload.Conversions.INT64_TO_UINT64,
        Overload.Conversions.DOUBLE_TO_UINT64,
        Overload.Conversions.STRING_TO_UINT64),
    DOUBLE(
        "double",
        Overload.Conversions.DOUBLE_TO_DOUBLE,
        Overload.Conversions.INT64_TO_DOUBLE,
        Overload.Conversions.UINT64_TO_DOUBLE,
        Overload.Conversions.STRING_TO_DOUBLE),
    STRING(
        "string",
        Overload.Conversions.STRING_TO_STRING,
        Overload.Conversions.INT64_TO_STRING,
        Overload.Conversions.UINT64_TO_STRING,
        Overload.Conversions.DOUBLE_TO_STRING,
        Overload.Conversions.BOOL_TO_STRING,
        Overload.Conversions.BYTES_TO_STRING,
        Overload.Conversions.TIMESTAMP_TO_STRING,
        Overload.Conversions.DURATION_TO_STRING),
    BYTES("bytes", Overload.Conversions.BYTES_TO_BYTES, Overload.Conversions.STRING_TO_BYTES),
    DYN("dyn", Overload.Conversions.TO_DYN),
    DURATION(
        "duration",
        Overload.Conversions.DURATION_TO_DURATION,
        Overload.Conversions.STRING_TO_DURATION),
    TIMESTAMP(
        "timestamp",
        Overload.Conversions.STRING_TO_TIMESTAMP,
        Overload.Conversions.TIMESTAMP_TO_TIMESTAMP,
        Overload.Conversions.INT64_TO_TIMESTAMP),
    BOOL("bool", Overload.Conversions.BOOL_TO_BOOL, Overload.Conversions.STRING_TO_BOOL),

    // String matchers
    MATCHES("matches", Overload.StringMatchers.MATCHES, Overload.StringMatchers.MATCHES_STRING),
    CONTAINS("contains", Overload.StringMatchers.CONTAINS_STRING),
    ENDS_WITH("endsWith", Overload.StringMatchers.ENDS_WITH_STRING),
    STARTS_WITH("startsWith", Overload.StringMatchers.STARTS_WITH_STRING),

    // Date/time operations
    GET_FULL_YEAR(
        "getFullYear",
        Overload.DateTime.TIMESTAMP_TO_YEAR,
        Overload.DateTime.TIMESTAMP_TO_YEAR_WITH_TZ),
    GET_MONTH(
        "getMonth",
        Overload.DateTime.TIMESTAMP_TO_MONTH,
        Overload.DateTime.TIMESTAMP_TO_MONTH_WITH_TZ),
    GET_DAY_OF_YEAR(
        "getDayOfYear",
        Overload.DateTime.TIMESTAMP_TO_DAY_OF_YEAR,
        Overload.DateTime.TIMESTAMP_TO_DAY_OF_YEAR_WITH_TZ),
    GET_DAY_OF_MONTH(
        "getDayOfMonth",
        Overload.DateTime.TIMESTAMP_TO_DAY_OF_MONTH,
        Overload.DateTime.TIMESTAMP_TO_DAY_OF_MONTH_WITH_TZ),
    GET_DATE(
        "getDate",
        Overload.DateTime.TIMESTAMP_TO_DAY_OF_MONTH_1_BASED,
        Overload.DateTime.TIMESTAMP_TO_DAY_OF_MONTH_1_BASED_WITH_TZ),
    GET_DAY_OF_WEEK(
        "getDayOfWeek",
        Overload.DateTime.TIMESTAMP_TO_DAY_OF_WEEK,
        Overload.DateTime.TIMESTAMP_TO_DAY_OF_WEEK_WITH_TZ),
    GET_HOURS(
        "getHours",
        Overload.DateTime.TIMESTAMP_TO_HOURS,
        Overload.DateTime.TIMESTAMP_TO_HOURS_WITH_TZ,
        Overload.DateTime.DURATION_TO_HOURS),
    GET_MINUTES(
        "getMinutes",
        Overload.DateTime.TIMESTAMP_TO_MINUTES,
        Overload.DateTime.TIMESTAMP_TO_MINUTES_WITH_TZ,
        Overload.DateTime.DURATION_TO_MINUTES),
    GET_SECONDS(
        "getSeconds",
        Overload.DateTime.TIMESTAMP_TO_SECONDS,
        Overload.DateTime.TIMESTAMP_TO_SECONDS_WITH_TZ,
        Overload.DateTime.DURATION_TO_SECONDS),
    GET_MILLISECONDS(
        "getMilliseconds",
        Overload.DateTime.TIMESTAMP_TO_MILLISECONDS,
        Overload.DateTime.TIMESTAMP_TO_MILLISECONDS_WITH_TZ,
        Overload.DateTime.DURATION_TO_MILLISECONDS),

    // Comparisons
    LESS(
        Operator.LESS,
        Overload.Comparison.LESS_BOOL,
        Overload.Comparison.LESS_INT64,
        Overload.Comparison.LESS_UINT64,
        Overload.Comparison.LESS_DOUBLE,
        Overload.Comparison.LESS_STRING,
        Overload.Comparison.LESS_BYTES,
        Overload.Comparison.LESS_TIMESTAMP,
        Overload.Comparison.LESS_DURATION,
        Overload.Comparison.LESS_INT64_UINT64,
        Overload.Comparison.LESS_UINT64_INT64,
        Overload.Comparison.LESS_INT64_DOUBLE,
        Overload.Comparison.LESS_DOUBLE_INT64,
        Overload.Comparison.LESS_UINT64_DOUBLE,
        Overload.Comparison.LESS_DOUBLE_UINT64),
    LESS_EQUALS(
        Operator.LESS_EQUALS,
        Overload.Comparison.LESS_EQUALS_BOOL,
        Overload.Comparison.LESS_EQUALS_INT64,
        Overload.Comparison.LESS_EQUALS_UINT64,
        Overload.Comparison.LESS_EQUALS_DOUBLE,
        Overload.Comparison.LESS_EQUALS_STRING,
        Overload.Comparison.LESS_EQUALS_BYTES,
        Overload.Comparison.LESS_EQUALS_TIMESTAMP,
        Overload.Comparison.LESS_EQUALS_DURATION,
        Overload.Comparison.LESS_EQUALS_INT64_UINT64,
        Overload.Comparison.LESS_EQUALS_UINT64_INT64,
        Overload.Comparison.LESS_EQUALS_INT64_DOUBLE,
        Overload.Comparison.LESS_EQUALS_DOUBLE_INT64,
        Overload.Comparison.LESS_EQUALS_UINT64_DOUBLE,
        Overload.Comparison.LESS_EQUALS_DOUBLE_UINT64),
    GREATER(
        Operator.GREATER,
        Overload.Comparison.GREATER_BOOL,
        Overload.Comparison.GREATER_INT64,
        Overload.Comparison.GREATER_UINT64,
        Overload.Comparison.GREATER_DOUBLE,
        Overload.Comparison.GREATER_STRING,
        Overload.Comparison.GREATER_BYTES,
        Overload.Comparison.GREATER_TIMESTAMP,
        Overload.Comparison.GREATER_DURATION,
        Overload.Comparison.GREATER_INT64_UINT64,
        Overload.Comparison.GREATER_UINT64_INT64,
        Overload.Comparison.GREATER_INT64_DOUBLE,
        Overload.Comparison.GREATER_DOUBLE_INT64,
        Overload.Comparison.GREATER_UINT64_DOUBLE,
        Overload.Comparison.GREATER_DOUBLE_UINT64),
    GREATER_EQUALS(
        Operator.GREATER_EQUALS,
        Overload.Comparison.GREATER_EQUALS_BOOL,
        Overload.Comparison.GREATER_EQUALS_INT64,
        Overload.Comparison.GREATER_EQUALS_UINT64,
        Overload.Comparison.GREATER_EQUALS_DOUBLE,
        Overload.Comparison.GREATER_EQUALS_STRING,
        Overload.Comparison.GREATER_EQUALS_BYTES,
        Overload.Comparison.GREATER_EQUALS_TIMESTAMP,
        Overload.Comparison.GREATER_EQUALS_DURATION,
        Overload.Comparison.GREATER_EQUALS_INT64_UINT64,
        Overload.Comparison.GREATER_EQUALS_UINT64_INT64,
        Overload.Comparison.GREATER_EQUALS_INT64_DOUBLE,
        Overload.Comparison.GREATER_EQUALS_DOUBLE_INT64,
        Overload.Comparison.GREATER_EQUALS_UINT64_DOUBLE,
        Overload.Comparison.GREATER_EQUALS_DOUBLE_UINT64),
    ;

    private final String functionName;
    private final CelFunctionDecl celFunctionDecl;
    private final ImmutableSet<StandardOverload> standardOverloads;
    private final boolean isDeprecated;

    /** Container class for CEL standard function overloads. */
    public static final class Overload {

      /**
       * Overloads for internal functions that may have been rewritten by macros (ex: @in), or those
       * used for special purposes (comprehensions, type denotations etc).
       */
      public enum InternalOperator implements StandardOverload {
        IN_LIST(
            CelOverloadDecl.newGlobalOverload(
                "in_list", "list membership", SimpleType.BOOL, TYPE_PARAM_A, LIST_OF_A)),
        IN_MAP(
            CelOverloadDecl.newGlobalOverload(
                "in_map", "map key membership", SimpleType.BOOL, TYPE_PARAM_A, MAP_OF_AB)),
        NOT_STRICTLY_FALSE(
            CelOverloadDecl.newGlobalOverload(
                "not_strictly_false",
                "false if argument is false, true otherwise (including errors and unknowns)",
                SimpleType.BOOL,
                SimpleType.BOOL)),
        TYPE(
            CelOverloadDecl.newGlobalOverload(
                "type", "returns type of value", TypeType.create(TYPE_PARAM_A), TYPE_PARAM_A)),
        ;

        private final CelOverloadDecl celOverloadDecl;

        InternalOperator(CelOverloadDecl overloadDecl) {
          this.celOverloadDecl = overloadDecl;
        }

        @Override
        public CelOverloadDecl celOverloadDecl() {
          return this.celOverloadDecl;
        }
      }

      /** Overloads for logical operators that return a bool as a result. */
      public enum BooleanOperator implements StandardOverload {
        CONDITIONAL(
            CelOverloadDecl.newGlobalOverload(
                "conditional",
                "conditional",
                TYPE_PARAM_A,
                SimpleType.BOOL,
                TYPE_PARAM_A,
                TYPE_PARAM_A)),
        LOGICAL_NOT(
            CelOverloadDecl.newGlobalOverload(
                "logical_not", "logical not", SimpleType.BOOL, SimpleType.BOOL)),
        LOGICAL_OR(
            CelOverloadDecl.newGlobalOverload(
                "logical_or", "logical or", SimpleType.BOOL, SimpleType.BOOL, SimpleType.BOOL)),
        LOGICAL_AND(
            CelOverloadDecl.newGlobalOverload(
                "logical_and", "logical_and", SimpleType.BOOL, SimpleType.BOOL, SimpleType.BOOL)),
        ;

        private final CelOverloadDecl celOverloadDecl;

        BooleanOperator(CelOverloadDecl overloadDecl) {
          this.celOverloadDecl = overloadDecl;
        }

        @Override
        public CelOverloadDecl celOverloadDecl() {
          return this.celOverloadDecl;
        }
      }

      /** Overloads for functions that test relations. */
      public enum Relation implements StandardOverload {
        EQUALS(
            CelOverloadDecl.newGlobalOverload(
                "equals", "equality", SimpleType.BOOL, TYPE_PARAM_A, TYPE_PARAM_A)),
        NOT_EQUALS(
            CelOverloadDecl.newGlobalOverload(
                "not_equals", "inequality", SimpleType.BOOL, TYPE_PARAM_A, TYPE_PARAM_A)),
        ;
        private final CelOverloadDecl celOverloadDecl;

        Relation(CelOverloadDecl overloadDecl) {
          this.celOverloadDecl = overloadDecl;
        }

        @Override
        public CelOverloadDecl celOverloadDecl() {
          return this.celOverloadDecl;
        }
      }

      /** Overloads for performing arithmetic operations. */
      public enum Arithmetic implements StandardOverload {

        // Add
        ADD_STRING(
            CelOverloadDecl.newGlobalOverload(
                "add_string",
                "string concatenation",
                SimpleType.STRING,
                SimpleType.STRING,
                SimpleType.STRING)),
        ADD_BYTES(
            CelOverloadDecl.newGlobalOverload(
                "add_bytes",
                "bytes concatenation",
                SimpleType.BYTES,
                SimpleType.BYTES,
                SimpleType.BYTES)),
        ADD_LIST(
            CelOverloadDecl.newGlobalOverload(
                "add_list", "list concatenation", LIST_OF_A, LIST_OF_A, LIST_OF_A)),
        ADD_TIMESTAMP_DURATION(
            CelOverloadDecl.newGlobalOverload(
                "add_timestamp_duration",
                "arithmetic",
                SimpleType.TIMESTAMP,
                SimpleType.TIMESTAMP,
                SimpleType.DURATION)),
        ADD_DURATION_TIMESTAMP(
            CelOverloadDecl.newGlobalOverload(
                "add_duration_timestamp",
                "arithmetic",
                SimpleType.TIMESTAMP,
                SimpleType.DURATION,
                SimpleType.TIMESTAMP)),
        ADD_DURATION_DURATION(
            CelOverloadDecl.newGlobalOverload(
                "add_duration_duration",
                "arithmetic",
                SimpleType.DURATION,
                SimpleType.DURATION,
                SimpleType.DURATION)),
        ADD_INT64(
            CelOverloadDecl.newGlobalOverload(
                "add_int64", "arithmetic", SimpleType.INT, SimpleType.INT, SimpleType.INT)),
        ADD_UINT64(
            CelOverloadDecl.newGlobalOverload(
                "add_uint64", "arithmetic", SimpleType.UINT, SimpleType.UINT, SimpleType.UINT)),
        ADD_DOUBLE(
            CelOverloadDecl.newGlobalOverload(
                "add_double",
                "arithmetic",
                SimpleType.DOUBLE,
                SimpleType.DOUBLE,
                SimpleType.DOUBLE)),

        // Subtract
        SUBTRACT_INT64(
            CelOverloadDecl.newGlobalOverload(
                "subtract_int64", "arithmetic", SimpleType.INT, SimpleType.INT, SimpleType.INT)),
        SUBTRACT_UINT64(
            CelOverloadDecl.newGlobalOverload(
                "subtract_uint64",
                "arithmetic",
                SimpleType.UINT,
                SimpleType.UINT,
                SimpleType.UINT)),
        SUBTRACT_DOUBLE(
            CelOverloadDecl.newGlobalOverload(
                "subtract_double",
                "arithmetic",
                SimpleType.DOUBLE,
                SimpleType.DOUBLE,
                SimpleType.DOUBLE)),
        SUBTRACT_TIMESTAMP_TIMESTAMP(
            CelOverloadDecl.newGlobalOverload(
                "subtract_timestamp_timestamp",
                "arithmetic",
                SimpleType.DURATION,
                SimpleType.TIMESTAMP,
                SimpleType.TIMESTAMP)),
        SUBTRACT_TIMESTAMP_DURATION(
            CelOverloadDecl.newGlobalOverload(
                "subtract_timestamp_duration",
                "arithmetic",
                SimpleType.TIMESTAMP,
                SimpleType.TIMESTAMP,
                SimpleType.DURATION)),
        SUBTRACT_DURATION_DURATION(
            CelOverloadDecl.newGlobalOverload(
                "subtract_duration_duration",
                "arithmetic",
                SimpleType.DURATION,
                SimpleType.DURATION,
                SimpleType.DURATION)),

        // Multiply
        MULTIPLY_INT64(
            CelOverloadDecl.newGlobalOverload(
                "multiply_int64", "arithmetic", SimpleType.INT, SimpleType.INT, SimpleType.INT)),
        MULTIPLY_UINT64(
            CelOverloadDecl.newGlobalOverload(
                "multiply_uint64",
                "arithmetic",
                SimpleType.UINT,
                SimpleType.UINT,
                SimpleType.UINT)),
        MULTIPLY_DOUBLE(
            CelOverloadDecl.newGlobalOverload(
                "multiply_double",
                "arithmetic",
                SimpleType.DOUBLE,
                SimpleType.DOUBLE,
                SimpleType.DOUBLE)),

        // Divide
        DIVIDE_INT64(
            CelOverloadDecl.newGlobalOverload(
                "divide_int64", "arithmetic", SimpleType.INT, SimpleType.INT, SimpleType.INT)),
        DIVIDE_UINT64(
            CelOverloadDecl.newGlobalOverload(
                "divide_uint64", "arithmetic", SimpleType.UINT, SimpleType.UINT, SimpleType.UINT)),
        DIVIDE_DOUBLE(
            CelOverloadDecl.newGlobalOverload(
                "divide_double",
                "arithmetic",
                SimpleType.DOUBLE,
                SimpleType.DOUBLE,
                SimpleType.DOUBLE)),

        // Modulo
        MODULO_INT64(
            CelOverloadDecl.newGlobalOverload(
                "modulo_int64", "arithmetic", SimpleType.INT, SimpleType.INT, SimpleType.INT)),
        MODULO_UINT64(
            CelOverloadDecl.newGlobalOverload(
                "modulo_uint64", "arithmetic", SimpleType.UINT, SimpleType.UINT, SimpleType.UINT)),
        NEGATE_INT64(
            CelOverloadDecl.newGlobalOverload(
                "negate_int64", "negation", SimpleType.INT, SimpleType.INT)),
        NEGATE_DOUBLE(
            CelOverloadDecl.newGlobalOverload(
                "negate_double", "negation", SimpleType.DOUBLE, SimpleType.DOUBLE)),
        ;
        private final CelOverloadDecl celOverloadDecl;

        Arithmetic(CelOverloadDecl overloadDecl) {
          this.celOverloadDecl = overloadDecl;
        }

        @Override
        public CelOverloadDecl celOverloadDecl() {
          return this.celOverloadDecl;
        }
      }

      /** Overloads for indexing a list or a map. */
      public enum Index implements StandardOverload {
        INDEX_LIST(
            CelOverloadDecl.newGlobalOverload(
                "index_list", "list indexing", TYPE_PARAM_A, LIST_OF_A, SimpleType.INT)),
        INDEX_MAP(
            CelOverloadDecl.newGlobalOverload(
                "index_map", "map indexing", TYPE_PARAM_B, MAP_OF_AB, TYPE_PARAM_A)),
        ;
        private final CelOverloadDecl celOverloadDecl;

        Index(CelOverloadDecl overloadDecl) {
          this.celOverloadDecl = overloadDecl;
        }

        @Override
        public CelOverloadDecl celOverloadDecl() {
          return this.celOverloadDecl;
        }
      }

      /** Overloads for retrieving the size of a literal or a collection. */
      public enum Size implements StandardOverload {
        SIZE_STRING(
            CelOverloadDecl.newGlobalOverload(
                "size_string", "string length", SimpleType.INT, SimpleType.STRING)),
        SIZE_BYTES(
            CelOverloadDecl.newGlobalOverload(
                "size_bytes", "bytes length", SimpleType.INT, SimpleType.BYTES)),
        SIZE_LIST(
            CelOverloadDecl.newGlobalOverload("size_list", "list size", SimpleType.INT, LIST_OF_A)),
        SIZE_MAP(
            CelOverloadDecl.newGlobalOverload("size_map", "map size", SimpleType.INT, MAP_OF_AB)),
        STRING_SIZE(
            CelOverloadDecl.newMemberOverload(
                "string_size", "string length", SimpleType.INT, SimpleType.STRING)),
        BYTES_SIZE(
            CelOverloadDecl.newMemberOverload(
                "bytes_size", "bytes length", SimpleType.INT, SimpleType.BYTES)),
        LIST_SIZE(
            CelOverloadDecl.newMemberOverload("list_size", "list size", SimpleType.INT, LIST_OF_A)),
        MAP_SIZE(
            CelOverloadDecl.newMemberOverload("map_size", "map size", SimpleType.INT, MAP_OF_AB)),
        ;

        private static final ImmutableMap<String, Size> ID_TO_ENUM =
            stream(values()).collect(toImmutableMap(e -> e.celOverloadDecl().overloadId(), e -> e));

        public static Optional<Size> fromOverloadId(String overloadId) {
          return Optional.ofNullable(ID_TO_ENUM.get(overloadId));
        }

        private final CelOverloadDecl celOverloadDecl;

        Size(CelOverloadDecl overloadDecl) {
          this.celOverloadDecl = overloadDecl;
        }

        @Override
        public CelOverloadDecl celOverloadDecl() {
          return this.celOverloadDecl;
        }
      }

      /** Overloads for performing type conversions. */
      public enum Conversions implements StandardOverload {
        // Bool conversions
        BOOL_TO_BOOL(
            CelOverloadDecl.newGlobalOverload(
                "bool_to_bool", "type conversion (identity)", SimpleType.BOOL, SimpleType.BOOL)),
        STRING_TO_BOOL(
            CelOverloadDecl.newGlobalOverload(
                "string_to_bool", "type conversion", SimpleType.BOOL, SimpleType.STRING)),

        // Int conversions
        INT64_TO_INT64(
            CelOverloadDecl.newGlobalOverload(
                "int64_to_int64", "type conversion (identity)", SimpleType.INT, SimpleType.INT)),
        UINT64_TO_INT64(
            CelOverloadDecl.newGlobalOverload(
                "uint64_to_int64", "type conversion", SimpleType.INT, SimpleType.UINT)),
        DOUBLE_TO_INT64(
            CelOverloadDecl.newGlobalOverload(
                "double_to_int64", "type conversion", SimpleType.INT, SimpleType.DOUBLE)),
        STRING_TO_INT64(
            CelOverloadDecl.newGlobalOverload(
                "string_to_int64", "type conversion", SimpleType.INT, SimpleType.STRING)),
        TIMESTAMP_TO_INT64(
            CelOverloadDecl.newGlobalOverload(
                "timestamp_to_int64",
                "Convert timestamp to int64 in seconds since Unix epoch.",
                SimpleType.INT,
                SimpleType.TIMESTAMP)),
        // Uint conversions
        UINT64_TO_UINT64(
            CelOverloadDecl.newGlobalOverload(
                "uint64_to_uint64",
                "type conversion (identity)",
                SimpleType.UINT,
                SimpleType.UINT)),
        INT64_TO_UINT64(
            CelOverloadDecl.newGlobalOverload(
                "int64_to_uint64", "type conversion", SimpleType.UINT, SimpleType.INT)),
        DOUBLE_TO_UINT64(
            CelOverloadDecl.newGlobalOverload(
                "double_to_uint64", "type conversion", SimpleType.UINT, SimpleType.DOUBLE)),
        STRING_TO_UINT64(
            CelOverloadDecl.newGlobalOverload(
                "string_to_uint64", "type conversion", SimpleType.UINT, SimpleType.STRING)),
        // Double conversions
        DOUBLE_TO_DOUBLE(
            CelOverloadDecl.newGlobalOverload(
                "double_to_double",
                "type conversion (identity)",
                SimpleType.DOUBLE,
                SimpleType.DOUBLE)),
        INT64_TO_DOUBLE(
            CelOverloadDecl.newGlobalOverload(
                "int64_to_double", "type conversion", SimpleType.DOUBLE, SimpleType.INT)),
        UINT64_TO_DOUBLE(
            CelOverloadDecl.newGlobalOverload(
                "uint64_to_double", "type conversion", SimpleType.DOUBLE, SimpleType.UINT)),
        STRING_TO_DOUBLE(
            CelOverloadDecl.newGlobalOverload(
                "string_to_double", "type conversion", SimpleType.DOUBLE, SimpleType.STRING)),
        // String conversions
        STRING_TO_STRING(
            CelOverloadDecl.newGlobalOverload(
                "string_to_string",
                "type conversion (identity)",
                SimpleType.STRING,
                SimpleType.STRING)),
        INT64_TO_STRING(
            CelOverloadDecl.newGlobalOverload(
                "int64_to_string", "type conversion", SimpleType.STRING, SimpleType.INT)),
        UINT64_TO_STRING(
            CelOverloadDecl.newGlobalOverload(
                "uint64_to_string", "type conversion", SimpleType.STRING, SimpleType.UINT)),
        DOUBLE_TO_STRING(
            CelOverloadDecl.newGlobalOverload(
                "double_to_string", "type conversion", SimpleType.STRING, SimpleType.DOUBLE)),
        BOOL_TO_STRING(
            CelOverloadDecl.newGlobalOverload(
                "bool_to_string", "type conversion", SimpleType.STRING, SimpleType.BOOL)),
        BYTES_TO_STRING(
            CelOverloadDecl.newGlobalOverload(
                "bytes_to_string", "type conversion", SimpleType.STRING, SimpleType.BYTES)),
        TIMESTAMP_TO_STRING(
            CelOverloadDecl.newGlobalOverload(
                "timestamp_to_string", "type_conversion", SimpleType.STRING, SimpleType.TIMESTAMP)),
        DURATION_TO_STRING(
            CelOverloadDecl.newGlobalOverload(
                "duration_to_string", "type_conversion", SimpleType.STRING, SimpleType.DURATION)),
        // Bytes conversions
        BYTES_TO_BYTES(
            CelOverloadDecl.newGlobalOverload(
                "bytes_to_bytes",
                "type conversion (identity)",
                SimpleType.BYTES,
                SimpleType.BYTES)),
        STRING_TO_BYTES(
            CelOverloadDecl.newGlobalOverload(
                "string_to_bytes", "type conversion", SimpleType.BYTES, SimpleType.STRING)),
        // Duration conversions
        DURATION_TO_DURATION(
            CelOverloadDecl.newGlobalOverload(
                "duration_to_duration",
                "type conversion (identity)",
                SimpleType.DURATION,
                SimpleType.DURATION)),
        STRING_TO_DURATION(
            CelOverloadDecl.newGlobalOverload(
                "string_to_duration",
                "type conversion, duration should be end with \"s\", which stands for seconds",
                SimpleType.DURATION,
                SimpleType.STRING)),
        // Timestamp conversions
        STRING_TO_TIMESTAMP(
            CelOverloadDecl.newGlobalOverload(
                "string_to_timestamp",
                "Type conversion of strings to timestamps according to RFC3339. Example:"
                    + " \"1972-01-01T10:00:20.021-05:00\".",
                SimpleType.TIMESTAMP,
                SimpleType.STRING)),
        TIMESTAMP_TO_TIMESTAMP(
            CelOverloadDecl.newGlobalOverload(
                "timestamp_to_timestamp",
                "type conversion (identity)",
                SimpleType.TIMESTAMP,
                SimpleType.TIMESTAMP)),
        INT64_TO_TIMESTAMP(
            CelOverloadDecl.newGlobalOverload(
                "int64_to_timestamp",
                "Type conversion of integers as Unix epoch seconds to timestamps.",
                SimpleType.TIMESTAMP,
                SimpleType.INT)),

        // Dyn conversions
        TO_DYN(
            CelOverloadDecl.newGlobalOverload(
                "to_dyn", "type conversion", SimpleType.DYN, TYPE_PARAM_A)),
        ;
        private final CelOverloadDecl celOverloadDecl;

        Conversions(CelOverloadDecl overloadDecl) {
          this.celOverloadDecl = overloadDecl;
        }

        @Override
        public CelOverloadDecl celOverloadDecl() {
          return this.celOverloadDecl;
        }
      }

      /**
       * Overloads for functions performing string matching, such as regular expressions or contains
       * check.
       */
      public enum StringMatchers implements StandardOverload {
        MATCHES(
            CelOverloadDecl.newGlobalOverload(
                "matches",
                "matches first argument against regular expression in second argument",
                SimpleType.BOOL,
                SimpleType.STRING,
                SimpleType.STRING)),

        MATCHES_STRING(
            CelOverloadDecl.newMemberOverload(
                "matches_string",
                "matches the self argument against regular expression in first argument",
                SimpleType.BOOL,
                SimpleType.STRING,
                SimpleType.STRING)),

        CONTAINS_STRING(
            CelOverloadDecl.newMemberOverload(
                "contains_string",
                "tests whether the string operand contains the substring",
                SimpleType.BOOL,
                SimpleType.STRING,
                SimpleType.STRING)),

        ENDS_WITH_STRING(
            CelOverloadDecl.newMemberOverload(
                "ends_with_string",
                "tests whether the string operand ends with the suffix argument",
                SimpleType.BOOL,
                SimpleType.STRING,
                SimpleType.STRING)),

        STARTS_WITH_STRING(
            CelOverloadDecl.newMemberOverload(
                "starts_with_string",
                "tests whether the string operand starts with the prefix argument",
                SimpleType.BOOL,
                SimpleType.STRING,
                SimpleType.STRING)),
        ;
        private final CelOverloadDecl celOverloadDecl;

        StringMatchers(CelOverloadDecl overloadDecl) {
          this.celOverloadDecl = overloadDecl;
        }

        @Override
        public CelOverloadDecl celOverloadDecl() {
          return this.celOverloadDecl;
        }
      }

      /** Overloads for functions performing date/time operations. */
      public enum DateTime implements StandardOverload {
        TIMESTAMP_TO_YEAR(
            CelOverloadDecl.newMemberOverload(
                "timestamp_to_year",
                "get year from the date in UTC",
                SimpleType.INT,
                SimpleType.TIMESTAMP)),
        TIMESTAMP_TO_YEAR_WITH_TZ(
            CelOverloadDecl.newMemberOverload(
                "timestamp_to_year_with_tz",
                "get year from the date with timezone",
                SimpleType.INT,
                SimpleType.TIMESTAMP,
                SimpleType.STRING)),

        TIMESTAMP_TO_MONTH(
            CelOverloadDecl.newMemberOverload(
                "timestamp_to_month",
                "get month from the date in UTC, 0-11",
                SimpleType.INT,
                SimpleType.TIMESTAMP)),

        TIMESTAMP_TO_MONTH_WITH_TZ(
            CelOverloadDecl.newMemberOverload(
                "timestamp_to_month_with_tz",
                "get month from the date with timezone, 0-11",
                SimpleType.INT,
                SimpleType.TIMESTAMP,
                SimpleType.STRING)),

        TIMESTAMP_TO_DAY_OF_YEAR(
            CelOverloadDecl.newMemberOverload(
                "timestamp_to_day_of_year",
                "get day of year from the date in UTC, zero-based indexing",
                SimpleType.INT,
                SimpleType.TIMESTAMP)),

        TIMESTAMP_TO_DAY_OF_YEAR_WITH_TZ(
            CelOverloadDecl.newMemberOverload(
                "timestamp_to_day_of_year_with_tz",
                "get day of year from the date with timezone, zero-based indexing",
                SimpleType.INT,
                SimpleType.TIMESTAMP,
                SimpleType.STRING)),

        TIMESTAMP_TO_DAY_OF_MONTH(
            CelOverloadDecl.newMemberOverload(
                "timestamp_to_day_of_month",
                "get day of month from the date in UTC, zero-based indexing",
                SimpleType.INT,
                SimpleType.TIMESTAMP)),

        TIMESTAMP_TO_DAY_OF_MONTH_WITH_TZ(
            CelOverloadDecl.newMemberOverload(
                "timestamp_to_day_of_month_with_tz",
                "get day of month from the date with timezone, zero-based indexing",
                SimpleType.INT,
                SimpleType.TIMESTAMP,
                SimpleType.STRING)),

        TIMESTAMP_TO_DAY_OF_MONTH_1_BASED(
            CelOverloadDecl.newMemberOverload(
                "timestamp_to_day_of_month_1_based",
                "get day of month from the date in UTC, one-based indexing",
                SimpleType.INT,
                SimpleType.TIMESTAMP)),
        TIMESTAMP_TO_DAY_OF_MONTH_1_BASED_WITH_TZ(
            CelOverloadDecl.newMemberOverload(
                "timestamp_to_day_of_month_1_based_with_tz",
                "get day of month from the date with timezone, one-based indexing",
                SimpleType.INT,
                SimpleType.TIMESTAMP,
                SimpleType.STRING)),

        TIMESTAMP_TO_DAY_OF_WEEK(
            CelOverloadDecl.newMemberOverload(
                "timestamp_to_day_of_week",
                "get day of week from the date in UTC, zero-based, zero for Sunday",
                SimpleType.INT,
                SimpleType.TIMESTAMP)),

        TIMESTAMP_TO_DAY_OF_WEEK_WITH_TZ(
            CelOverloadDecl.newMemberOverload(
                "timestamp_to_day_of_week_with_tz",
                "get day of week from the date with timezone, zero-based, zero for Sunday",
                SimpleType.INT,
                SimpleType.TIMESTAMP,
                SimpleType.STRING)),

        TIMESTAMP_TO_HOURS(
            CelOverloadDecl.newMemberOverload(
                "timestamp_to_hours",
                "get hours from the date in UTC, 0-23",
                SimpleType.INT,
                SimpleType.TIMESTAMP)),

        TIMESTAMP_TO_HOURS_WITH_TZ(
            CelOverloadDecl.newMemberOverload(
                "timestamp_to_hours_with_tz",
                "get hours from the date with timezone, 0-23",
                SimpleType.INT,
                SimpleType.TIMESTAMP,
                SimpleType.STRING)),

        DURATION_TO_HOURS(
            CelOverloadDecl.newMemberOverload(
                "duration_to_hours",
                "get hours from duration",
                SimpleType.INT,
                SimpleType.DURATION)),
        TIMESTAMP_TO_MINUTES(
            CelOverloadDecl.newMemberOverload(
                "timestamp_to_minutes",
                "get minutes from the date in UTC, 0-59",
                SimpleType.INT,
                SimpleType.TIMESTAMP)),

        TIMESTAMP_TO_MINUTES_WITH_TZ(
            CelOverloadDecl.newMemberOverload(
                "timestamp_to_minutes_with_tz",
                "get minutes from the date with timezone, 0-59",
                SimpleType.INT,
                SimpleType.TIMESTAMP,
                SimpleType.STRING)),

        DURATION_TO_MINUTES(
            CelOverloadDecl.newMemberOverload(
                "duration_to_minutes",
                "get minutes from duration",
                SimpleType.INT,
                SimpleType.DURATION)),
        TIMESTAMP_TO_SECONDS(
            CelOverloadDecl.newMemberOverload(
                "timestamp_to_seconds",
                "get seconds from the date in UTC, 0-59",
                SimpleType.INT,
                SimpleType.TIMESTAMP)),

        TIMESTAMP_TO_SECONDS_WITH_TZ(
            CelOverloadDecl.newMemberOverload(
                "timestamp_to_seconds_with_tz",
                "get seconds from the date with timezone, 0-59",
                SimpleType.INT,
                SimpleType.TIMESTAMP,
                SimpleType.STRING)),

        DURATION_TO_SECONDS(
            CelOverloadDecl.newMemberOverload(
                "duration_to_seconds",
                "get seconds from duration",
                SimpleType.INT,
                SimpleType.DURATION)),
        TIMESTAMP_TO_MILLISECONDS(
            CelOverloadDecl.newMemberOverload(
                "timestamp_to_milliseconds",
                "get milliseconds from the date in UTC, 0-999",
                SimpleType.INT,
                SimpleType.TIMESTAMP)),

        TIMESTAMP_TO_MILLISECONDS_WITH_TZ(
            CelOverloadDecl.newMemberOverload(
                "timestamp_to_milliseconds_with_tz",
                "get milliseconds from the date with timezone, 0-999",
                SimpleType.INT,
                SimpleType.TIMESTAMP,
                SimpleType.STRING)),

        DURATION_TO_MILLISECONDS(
            CelOverloadDecl.newMemberOverload(
                "duration_to_milliseconds",
                "milliseconds from duration, 0-999",
                SimpleType.INT,
                SimpleType.DURATION)),
        ;
        private final CelOverloadDecl celOverloadDecl;

        DateTime(CelOverloadDecl overloadDecl) {
          this.celOverloadDecl = overloadDecl;
        }

        @Override
        public CelOverloadDecl celOverloadDecl() {
          return this.celOverloadDecl;
        }
      }

      /** Overloads for performing numeric comparisons. */
      public enum Comparison implements StandardOverload {
        // Less
        LESS_BOOL(
            CelOverloadDecl.newGlobalOverload(
                "less_bool", "ordering", SimpleType.BOOL, SimpleType.BOOL, SimpleType.BOOL),
            false),
        LESS_INT64(
            CelOverloadDecl.newGlobalOverload(
                "less_int64", "ordering", SimpleType.BOOL, SimpleType.INT, SimpleType.INT),
            false),
        LESS_UINT64(
            CelOverloadDecl.newGlobalOverload(
                "less_uint64", "ordering", SimpleType.BOOL, SimpleType.UINT, SimpleType.UINT),
            false),
        LESS_DOUBLE(
            CelOverloadDecl.newGlobalOverload(
                "less_double", "ordering", SimpleType.BOOL, SimpleType.DOUBLE, SimpleType.DOUBLE),
            false),
        LESS_STRING(
            CelOverloadDecl.newGlobalOverload(
                "less_string", "ordering", SimpleType.BOOL, SimpleType.STRING, SimpleType.STRING),
            false),
        LESS_BYTES(
            CelOverloadDecl.newGlobalOverload(
                "less_bytes", "ordering", SimpleType.BOOL, SimpleType.BYTES, SimpleType.BYTES),
            false),
        LESS_TIMESTAMP(
            CelOverloadDecl.newGlobalOverload(
                "less_timestamp",
                "ordering",
                SimpleType.BOOL,
                SimpleType.TIMESTAMP,
                SimpleType.TIMESTAMP),
            false),
        LESS_DURATION(
            CelOverloadDecl.newGlobalOverload(
                "less_duration",
                "ordering",
                SimpleType.BOOL,
                SimpleType.DURATION,
                SimpleType.DURATION),
            false),
        LESS_INT64_UINT64(
            CelOverloadDecl.newGlobalOverload(
                "less_int64_uint64",
                "Compare a signed integer value to an unsigned integer value",
                SimpleType.BOOL,
                SimpleType.INT,
                SimpleType.UINT),
            true),
        LESS_UINT64_INT64(
            CelOverloadDecl.newGlobalOverload(
                "less_uint64_int64",
                "Compare an unsigned integer value to a signed integer value",
                SimpleType.BOOL,
                SimpleType.UINT,
                SimpleType.INT),
            true),
        LESS_INT64_DOUBLE(
            CelOverloadDecl.newGlobalOverload(
                "less_int64_double",
                "Compare a signed integer value to a double value, coalesces the integer to a"
                    + " double",
                SimpleType.BOOL,
                SimpleType.INT,
                SimpleType.DOUBLE),
            true),
        LESS_DOUBLE_INT64(
            CelOverloadDecl.newGlobalOverload(
                "less_double_int64",
                "Compare a double value to a signed integer value, coalesces the integer to a"
                    + " double",
                SimpleType.BOOL,
                SimpleType.DOUBLE,
                SimpleType.INT),
            true),
        LESS_UINT64_DOUBLE(
            CelOverloadDecl.newGlobalOverload(
                "less_uint64_double",
                "Compare an unsigned integer value to a double value, coalesces the unsigned"
                    + " integer to a double",
                SimpleType.BOOL,
                SimpleType.UINT,
                SimpleType.DOUBLE),
            true),
        LESS_DOUBLE_UINT64(
            CelOverloadDecl.newGlobalOverload(
                "less_double_uint64",
                "Compare a double value to an unsigned integer value, coalesces the unsigned"
                    + " integer to a double",
                SimpleType.BOOL,
                SimpleType.DOUBLE,
                SimpleType.UINT),
            true),
        // Less Equals
        LESS_EQUALS_BOOL(
            Comparison.LESS_BOOL.celOverloadDecl.toBuilder()
                .setOverloadId(
                    Comparison.LESS_BOOL
                        .celOverloadDecl
                        .overloadId()
                        .replace("less", "less_equals"))
                .build(),
            false),
        LESS_EQUALS_INT64(
            Comparison.LESS_INT64.celOverloadDecl.toBuilder()
                .setOverloadId(
                    Comparison.LESS_INT64
                        .celOverloadDecl
                        .overloadId()
                        .replace("less", "less_equals"))
                .build(),
            false),
        LESS_EQUALS_UINT64(
            Comparison.LESS_UINT64.celOverloadDecl.toBuilder()
                .setOverloadId(
                    Comparison.LESS_UINT64
                        .celOverloadDecl
                        .overloadId()
                        .replace("less", "less_equals"))
                .build(),
            false),
        LESS_EQUALS_DOUBLE(
            Comparison.LESS_DOUBLE.celOverloadDecl.toBuilder()
                .setOverloadId(
                    Comparison.LESS_DOUBLE
                        .celOverloadDecl
                        .overloadId()
                        .replace("less", "less_equals"))
                .build(),
            false),
        LESS_EQUALS_STRING(
            Comparison.LESS_STRING.celOverloadDecl.toBuilder()
                .setOverloadId(
                    Comparison.LESS_STRING
                        .celOverloadDecl
                        .overloadId()
                        .replace("less", "less_equals"))
                .build(),
            false),
        LESS_EQUALS_BYTES(
            Comparison.LESS_BYTES.celOverloadDecl.toBuilder()
                .setOverloadId(
                    Comparison.LESS_BYTES
                        .celOverloadDecl
                        .overloadId()
                        .replace("less", "less_equals"))
                .build(),
            false),
        LESS_EQUALS_TIMESTAMP(
            Comparison.LESS_TIMESTAMP.celOverloadDecl.toBuilder()
                .setOverloadId(
                    Comparison.LESS_TIMESTAMP
                        .celOverloadDecl
                        .overloadId()
                        .replace("less", "less_equals"))
                .build(),
            false),
        LESS_EQUALS_DURATION(
            Comparison.LESS_DURATION.celOverloadDecl.toBuilder()
                .setOverloadId(
                    Comparison.LESS_DURATION
                        .celOverloadDecl
                        .overloadId()
                        .replace("less", "less_equals"))
                .build(),
            false),
        LESS_EQUALS_INT64_UINT64(
            Comparison.LESS_INT64_UINT64.celOverloadDecl.toBuilder()
                .setOverloadId(
                    Comparison.LESS_INT64_UINT64
                        .celOverloadDecl
                        .overloadId()
                        .replace("less", "less_equals"))
                .build(),
            true),
        LESS_EQUALS_UINT64_INT64(
            Comparison.LESS_UINT64_INT64.celOverloadDecl.toBuilder()
                .setOverloadId(
                    Comparison.LESS_UINT64_INT64
                        .celOverloadDecl
                        .overloadId()
                        .replace("less", "less_equals"))
                .build(),
            true),
        LESS_EQUALS_INT64_DOUBLE(
            Comparison.LESS_INT64_DOUBLE.celOverloadDecl.toBuilder()
                .setOverloadId(
                    Comparison.LESS_INT64_DOUBLE
                        .celOverloadDecl
                        .overloadId()
                        .replace("less", "less_equals"))
                .build(),
            true),
        LESS_EQUALS_DOUBLE_INT64(
            Comparison.LESS_DOUBLE_INT64.celOverloadDecl.toBuilder()
                .setOverloadId(
                    Comparison.LESS_DOUBLE_INT64
                        .celOverloadDecl
                        .overloadId()
                        .replace("less", "less_equals"))
                .build(),
            true),
        LESS_EQUALS_UINT64_DOUBLE(
            Comparison.LESS_UINT64_DOUBLE.celOverloadDecl.toBuilder()
                .setOverloadId(
                    Comparison.LESS_UINT64_DOUBLE
                        .celOverloadDecl
                        .overloadId()
                        .replace("less", "less_equals"))
                .build(),
            true),
        LESS_EQUALS_DOUBLE_UINT64(
            Comparison.LESS_DOUBLE_UINT64.celOverloadDecl.toBuilder()
                .setOverloadId(
                    Comparison.LESS_DOUBLE_UINT64
                        .celOverloadDecl
                        .overloadId()
                        .replace("less", "less_equals"))
                .build(),
            true),

        // Greater
        GREATER_BOOL(
            CelOverloadDecl.newGlobalOverload(
                "greater_bool", "ordering", SimpleType.BOOL, SimpleType.BOOL, SimpleType.BOOL),
            false),
        GREATER_INT64(
            CelOverloadDecl.newGlobalOverload(
                "greater_int64", "ordering", SimpleType.BOOL, SimpleType.INT, SimpleType.INT),
            false),
        GREATER_UINT64(
            CelOverloadDecl.newGlobalOverload(
                "greater_uint64", "ordering", SimpleType.BOOL, SimpleType.UINT, SimpleType.UINT),
            false),
        GREATER_DOUBLE(
            CelOverloadDecl.newGlobalOverload(
                "greater_double",
                "ordering",
                SimpleType.BOOL,
                SimpleType.DOUBLE,
                SimpleType.DOUBLE),
            false),
        GREATER_STRING(
            CelOverloadDecl.newGlobalOverload(
                "greater_string",
                "ordering",
                SimpleType.BOOL,
                SimpleType.STRING,
                SimpleType.STRING),
            false),
        GREATER_BYTES(
            CelOverloadDecl.newGlobalOverload(
                "greater_bytes", "ordering", SimpleType.BOOL, SimpleType.BYTES, SimpleType.BYTES),
            false),
        GREATER_TIMESTAMP(
            CelOverloadDecl.newGlobalOverload(
                "greater_timestamp",
                "ordering",
                SimpleType.BOOL,
                SimpleType.TIMESTAMP,
                SimpleType.TIMESTAMP),
            false),
        GREATER_DURATION(
            CelOverloadDecl.newGlobalOverload(
                "greater_duration",
                "ordering",
                SimpleType.BOOL,
                SimpleType.DURATION,
                SimpleType.DURATION),
            false),
        GREATER_INT64_UINT64(
            CelOverloadDecl.newGlobalOverload(
                "greater_int64_uint64",
                "Compare a signed integer value to an unsigned integer value",
                SimpleType.BOOL,
                SimpleType.INT,
                SimpleType.UINT),
            true),
        GREATER_UINT64_INT64(
            CelOverloadDecl.newGlobalOverload(
                "greater_uint64_int64",
                "Compare an unsigned integer value to a signed integer value",
                SimpleType.BOOL,
                SimpleType.UINT,
                SimpleType.INT),
            true),
        GREATER_INT64_DOUBLE(
            CelOverloadDecl.newGlobalOverload(
                "greater_int64_double",
                "Compare a signed integer value to a double value, coalesces the integer to a"
                    + " double",
                SimpleType.BOOL,
                SimpleType.INT,
                SimpleType.DOUBLE),
            true),
        GREATER_DOUBLE_INT64(
            CelOverloadDecl.newGlobalOverload(
                "greater_double_int64",
                "Compare a double value to a signed integer value, coalesces the integer to a"
                    + " double",
                SimpleType.BOOL,
                SimpleType.DOUBLE,
                SimpleType.INT),
            true),
        GREATER_UINT64_DOUBLE(
            CelOverloadDecl.newGlobalOverload(
                "greater_uint64_double",
                "Compare an unsigned integer value to a double value, coalesces the unsigned"
                    + " integer to a double",
                SimpleType.BOOL,
                SimpleType.UINT,
                SimpleType.DOUBLE),
            true),
        GREATER_DOUBLE_UINT64(
            CelOverloadDecl.newGlobalOverload(
                "greater_double_uint64",
                "Compare a double value to an unsigned integer value, coalesces the unsigned"
                    + " integer to a double",
                SimpleType.BOOL,
                SimpleType.DOUBLE,
                SimpleType.UINT),
            true),

        // Greater Equals
        GREATER_EQUALS_BOOL(
            Comparison.LESS_BOOL.celOverloadDecl.toBuilder()
                .setOverloadId(
                    Comparison.LESS_BOOL
                        .celOverloadDecl
                        .overloadId()
                        .replace("less", "greater_equals"))
                .build(),
            false),
        GREATER_EQUALS_INT64(
            Comparison.LESS_INT64.celOverloadDecl.toBuilder()
                .setOverloadId(
                    Comparison.LESS_INT64
                        .celOverloadDecl
                        .overloadId()
                        .replace("less", "greater_equals"))
                .build(),
            false),
        GREATER_EQUALS_UINT64(
            Comparison.LESS_UINT64.celOverloadDecl.toBuilder()
                .setOverloadId(
                    Comparison.LESS_UINT64
                        .celOverloadDecl
                        .overloadId()
                        .replace("less", "greater_equals"))
                .build(),
            false),
        GREATER_EQUALS_DOUBLE(
            Comparison.LESS_DOUBLE.celOverloadDecl.toBuilder()
                .setOverloadId(
                    Comparison.LESS_DOUBLE
                        .celOverloadDecl
                        .overloadId()
                        .replace("less", "greater_equals"))
                .build(),
            false),
        GREATER_EQUALS_STRING(
            Comparison.LESS_STRING.celOverloadDecl.toBuilder()
                .setOverloadId(
                    Comparison.LESS_STRING
                        .celOverloadDecl
                        .overloadId()
                        .replace("less", "greater_equals"))
                .build(),
            false),
        GREATER_EQUALS_BYTES(
            Comparison.LESS_BYTES.celOverloadDecl.toBuilder()
                .setOverloadId(
                    Comparison.LESS_BYTES
                        .celOverloadDecl
                        .overloadId()
                        .replace("less", "greater_equals"))
                .build(),
            false),
        GREATER_EQUALS_TIMESTAMP(
            Comparison.LESS_TIMESTAMP.celOverloadDecl.toBuilder()
                .setOverloadId(
                    Comparison.LESS_TIMESTAMP
                        .celOverloadDecl
                        .overloadId()
                        .replace("less", "greater_equals"))
                .build(),
            false),
        GREATER_EQUALS_DURATION(
            Comparison.LESS_DURATION.celOverloadDecl.toBuilder()
                .setOverloadId(
                    Comparison.LESS_DURATION
                        .celOverloadDecl
                        .overloadId()
                        .replace("less", "greater_equals"))
                .build(),
            false),
        GREATER_EQUALS_INT64_UINT64(
            Comparison.LESS_INT64_UINT64.celOverloadDecl.toBuilder()
                .setOverloadId(
                    Comparison.LESS_INT64_UINT64
                        .celOverloadDecl
                        .overloadId()
                        .replace("less", "greater_equals"))
                .build(),
            true),
        GREATER_EQUALS_UINT64_INT64(
            Comparison.LESS_UINT64_INT64.celOverloadDecl.toBuilder()
                .setOverloadId(
                    Comparison.LESS_UINT64_INT64
                        .celOverloadDecl
                        .overloadId()
                        .replace("less", "greater_equals"))
                .build(),
            true),
        GREATER_EQUALS_INT64_DOUBLE(
            Comparison.LESS_INT64_DOUBLE.celOverloadDecl.toBuilder()
                .setOverloadId(
                    Comparison.LESS_INT64_DOUBLE
                        .celOverloadDecl
                        .overloadId()
                        .replace("less", "greater_equals"))
                .build(),
            true),
        GREATER_EQUALS_DOUBLE_INT64(
            Comparison.LESS_DOUBLE_INT64.celOverloadDecl.toBuilder()
                .setOverloadId(
                    Comparison.LESS_DOUBLE_INT64
                        .celOverloadDecl
                        .overloadId()
                        .replace("less", "greater_equals"))
                .build(),
            true),
        GREATER_EQUALS_UINT64_DOUBLE(
            Comparison.LESS_UINT64_DOUBLE.celOverloadDecl.toBuilder()
                .setOverloadId(
                    Comparison.LESS_UINT64_DOUBLE
                        .celOverloadDecl
                        .overloadId()
                        .replace("less", "greater_equals"))
                .build(),
            true),
        GREATER_EQUALS_DOUBLE_UINT64(
            Comparison.LESS_DOUBLE_UINT64.celOverloadDecl.toBuilder()
                .setOverloadId(
                    Comparison.LESS_DOUBLE_UINT64
                        .celOverloadDecl
                        .overloadId()
                        .replace("less", "greater_equals"))
                .build(),
            true),
        ;

        private final CelOverloadDecl celOverloadDecl;
        private final boolean isHeterogeneousComparison;

        Comparison(CelOverloadDecl overloadDecl, boolean isHeterogeneousComparison) {
          this.celOverloadDecl = overloadDecl;
          this.isHeterogeneousComparison = isHeterogeneousComparison;
        }

        public boolean isHeterogeneousComparison() {
          return isHeterogeneousComparison;
        }

        @Override
        public CelOverloadDecl celOverloadDecl() {
          return this.celOverloadDecl;
        }

        /** Finds a Comparison by its overload ID. */
        public static Optional<Comparison> fromOverloadId(String overloadId) {
          for (Comparison c : values()) {
            if (c.celOverloadDecl().overloadId().equals(overloadId)) {
              return Optional.of(c);
            }
          }
          return Optional.empty();
        }
      }

      private Overload() {}
    }

    /** Gets the declaration for this standard function. */
    private CelFunctionDecl withOverloads(Iterable<StandardOverload> overloads) {
      return newCelFunctionDecl(functionName, ImmutableSet.copyOf(overloads));
    }

    public CelFunctionDecl functionDecl() {
      return celFunctionDecl;
    }

    public String functionName() {
      return functionName;
    }

    boolean isDeprecated() {
      return isDeprecated;
    }

    StandardFunction(Operator operator, StandardOverload... overloads) {
      this(false, operator.getFunction(), overloads);
    }

    StandardFunction(String functionName, StandardOverload... overloads) {
      this(false, functionName, overloads);
    }

    StandardFunction(boolean isDeprecated, String functionName, StandardOverload... overloads) {
      this.isDeprecated = isDeprecated;
      this.functionName = functionName;
      this.standardOverloads = ImmutableSet.copyOf(overloads);
      this.celFunctionDecl = newCelFunctionDecl(functionName, this.standardOverloads);
    }

    private static CelFunctionDecl newCelFunctionDecl(
        String functionName, ImmutableSet<StandardOverload> overloads) {
      return CelFunctionDecl.newFunctionDeclaration(
          functionName,
          overloads.stream().map(StandardOverload::celOverloadDecl).collect(toImmutableSet()));
    }
  }

  /** Standard CEL identifiers. */
  public enum StandardIdentifier {
    INT(newStandardIdentDecl(SimpleType.INT)),
    UINT(newStandardIdentDecl(SimpleType.UINT)),
    BOOL(newStandardIdentDecl(SimpleType.BOOL)),
    DOUBLE(newStandardIdentDecl(SimpleType.DOUBLE)),
    BYTES(newStandardIdentDecl(SimpleType.BYTES)),
    STRING(newStandardIdentDecl(SimpleType.STRING)),
    DYN(newStandardIdentDecl(SimpleType.DYN)),
    TYPE(newStandardIdentDecl("type", SimpleType.DYN)),
    NULL_TYPE(newStandardIdentDecl("null_type", SimpleType.NULL_TYPE)),
    LIST(newStandardIdentDecl("list", ListType.create(SimpleType.DYN))),
    MAP(newStandardIdentDecl("map", MapType.create(SimpleType.DYN, SimpleType.DYN))),
    ;

    private static CelIdentDecl newStandardIdentDecl(CelType celType) {
      return newStandardIdentDecl(CelTypes.format(celType), celType);
    }

    private static CelIdentDecl newStandardIdentDecl(String identName, CelType celType) {
      return CelIdentDecl.newBuilder()
          .setName(identName)
          .setType(TypeType.create(celType))
          .setDoc("type denotation")
          .build();
    }

    private final CelIdentDecl identDecl;

    public CelIdentDecl identDecl() {
      return identDecl;
    }

    StandardIdentifier(CelIdentDecl identDecl) {
      this.identDecl = identDecl;
    }
  }

  /** General interface for defining a standard function overload. */
  @Immutable
  public interface StandardOverload {
    CelOverloadDecl celOverloadDecl();
  }

  /** Set of all standard function names. */
  public static ImmutableSet<String> getAllFunctionNames() {
    return stream(StandardFunction.values())
        .filter(f -> !f.isDeprecated)
        .map(f -> f.functionName)
        .collect(toImmutableSet());
  }

  /** Builder for constructing the set of standard function/identifiers. */
  public static final class Builder {

    private ImmutableSet<StandardFunction> includeFunctions;
    private ImmutableSet<StandardFunction> excludeFunctions;
    private FunctionFilter functionFilter;

    private ImmutableSet<StandardIdentifier> includeIdentifiers;
    private ImmutableSet<StandardIdentifier> excludeIdentifiers;
    private IdentifierFilter identifierFilter;

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

    @CanIgnoreReturnValue
    public Builder excludeIdentifiers(StandardIdentifier... identifiers) {
      return excludeIdentifiers(ImmutableSet.copyOf(identifiers));
    }

    @CanIgnoreReturnValue
    public Builder excludeIdentifiers(Iterable<StandardIdentifier> identifiers) {
      this.excludeIdentifiers = checkNotNull(ImmutableSet.copyOf(identifiers));
      return this;
    }

    @CanIgnoreReturnValue
    public Builder includeIdentifiers(StandardIdentifier... identifiers) {
      return includeIdentifiers(ImmutableSet.copyOf(identifiers));
    }

    @CanIgnoreReturnValue
    public Builder includeIdentifiers(Iterable<StandardIdentifier> identifiers) {
      this.includeIdentifiers = checkNotNull(ImmutableSet.copyOf(identifiers));
      return this;
    }

    @CanIgnoreReturnValue
    public Builder filterIdentifiers(IdentifierFilter identifierFilter) {
      this.identifierFilter = identifierFilter;
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

    public CelStandardDeclarations build() {
      boolean hasIncludeFunctions = !this.includeFunctions.isEmpty();
      boolean hasExcludeFunctions = !this.excludeFunctions.isEmpty();
      boolean hasFilterFunction = this.functionFilter != null;
      assertOneSettingIsSet(
          hasIncludeFunctions,
          hasExcludeFunctions,
          hasFilterFunction,
          "You may only populate one of the following builder methods: includeFunctions,"
              + " excludeFunctions or filterFunctions");
      boolean hasIncludeIdentifiers = !this.includeIdentifiers.isEmpty();
      boolean hasExcludeIdentifiers = !this.excludeIdentifiers.isEmpty();
      boolean hasIdentifierFilter = this.identifierFilter != null;
      assertOneSettingIsSet(
          hasIncludeIdentifiers,
          hasExcludeIdentifiers,
          hasIdentifierFilter,
          "You may only populate one of the following builder methods: includeIdentifiers,"
              + " excludeIdentifiers or filterIdentifiers");

      ImmutableSet.Builder<CelFunctionDecl> functionDeclBuilder = ImmutableSet.builder();
      for (StandardFunction standardFunction : StandardFunction.values()) {
        if (hasIncludeFunctions) {
          if (this.includeFunctions.contains(standardFunction)) {
            functionDeclBuilder.add(standardFunction.celFunctionDecl);
          }
          continue;
        }
        if (hasExcludeFunctions) {
          if (!this.excludeFunctions.contains(standardFunction)) {
            functionDeclBuilder.add(standardFunction.celFunctionDecl);
          }
          continue;
        }
        if (hasFilterFunction) {
          ImmutableSet.Builder<StandardOverload> overloadBuilder = ImmutableSet.builder();
          for (StandardOverload standardOverload : standardFunction.standardOverloads) {
            boolean includeOverload = functionFilter.include(standardFunction, standardOverload);
            if (includeOverload) {
              overloadBuilder.add(standardOverload);
            }
          }

          ImmutableSet<StandardOverload> overloads = overloadBuilder.build();
          if (!overloads.isEmpty()) {
            functionDeclBuilder.add(standardFunction.withOverloads(overloadBuilder.build()));
          }
          continue;
        }

        functionDeclBuilder.add(standardFunction.celFunctionDecl);
      }

      ImmutableSet.Builder<CelIdentDecl> identBuilder = ImmutableSet.builder();
      for (StandardIdentifier standardIdentifier : StandardIdentifier.values()) {
        if (hasIncludeIdentifiers) {
          if (this.includeIdentifiers.contains(standardIdentifier)) {
            identBuilder.add(standardIdentifier.identDecl);
          }
          continue;
        }

        if (hasExcludeIdentifiers) {
          if (!this.excludeIdentifiers.contains(standardIdentifier)) {
            identBuilder.add(standardIdentifier.identDecl);
          }
          continue;
        }

        if (hasIdentifierFilter) {
          boolean includeIdent = identifierFilter.include(standardIdentifier);
          if (includeIdent) {
            identBuilder.add(standardIdentifier.identDecl);
          }
          continue;
        }

        identBuilder.add(standardIdentifier.identDecl);
      }

      return new CelStandardDeclarations(functionDeclBuilder.build(), identBuilder.build());
    }

    private Builder() {
      this.includeFunctions = ImmutableSet.of();
      this.excludeFunctions = ImmutableSet.of();
      this.includeIdentifiers = ImmutableSet.of();
      this.excludeIdentifiers = ImmutableSet.of();
    }

    /**
     * Functional interface for filtering standard functions. Returning true in the callback will
     * include the function in the environment.
     */
    @FunctionalInterface
    public interface FunctionFilter {
      boolean include(StandardFunction standardFunction, StandardOverload standardOverload);
    }

    /**
     * Functional interface for filtering standard identifiers. Returning true in the callback will
     * include the identifier in the environment.
     */
    @FunctionalInterface
    public interface IdentifierFilter {
      boolean include(StandardIdentifier standardIdentifier);
    }
  }

  public static Builder newBuilder() {
    return new Builder();
  }

  ImmutableSet<CelFunctionDecl> functionDecls() {
    return celFunctionDecls;
  }

  ImmutableSet<CelIdentDecl> identifierDecls() {
    return celIdentDecls;
  }

  private CelStandardDeclarations(
      ImmutableSet<CelFunctionDecl> celFunctionDecls, ImmutableSet<CelIdentDecl> celIdentDecls) {
    this.celFunctionDecls = celFunctionDecls;
    this.celIdentDecls = celIdentDecls;
  }
}
