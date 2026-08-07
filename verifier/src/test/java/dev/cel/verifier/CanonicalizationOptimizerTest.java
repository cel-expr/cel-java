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

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import dev.cel.bundle.Cel;
import dev.cel.bundle.CelFactory;
import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.CelContainer;
import dev.cel.common.CelMutableAst;
import dev.cel.common.CelOptions;
import dev.cel.common.ast.CelMutableExpr;
import dev.cel.common.ast.CelMutableExpr.CelMutableCall;
import dev.cel.common.types.ListType;
import dev.cel.common.types.MapType;
import dev.cel.common.types.OptionalType;
import dev.cel.common.types.SimpleType;
import dev.cel.common.types.StructTypeReference;
import dev.cel.expr.conformance.proto3.TestAllTypes;
import dev.cel.extensions.CelExtensions;
import dev.cel.extensions.CelOptionalLibrary;
import dev.cel.optimizer.CelOptimizer;
import dev.cel.optimizer.CelOptimizerFactory;
import dev.cel.parser.CelStandardMacro;
import dev.cel.parser.CelUnparser;
import dev.cel.parser.CelUnparserFactory;
import dev.cel.verifier.CanonicalizationOptimizer.CanonicalizationOptions;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(TestParameterInjector.class)
public class CanonicalizationOptimizerTest {

  private static final Cel CEL =
      CelFactory.plannerCelBuilder()
          .setContainer(CelContainer.ofName("cel.expr.conformance.proto3"))
          .setStandardMacros(CelStandardMacro.STANDARD_MACROS)
          .setOptions(
              CelOptions.current()
                  .populateMacroCalls(true)
                  .enableHeterogeneousNumericComparisons(true)
                  .build())
          .addMessageTypes(TestAllTypes.getDescriptor())
          .addCompilerLibraries(
              CelExtensions.comprehensions(), CelExtensions.bindings(), CelOptionalLibrary.INSTANCE)
          .addRuntimeLibraries(CelExtensions.comprehensions(), CelOptionalLibrary.INSTANCE)
          // Abstract DYN variables for alphabetical ordering and precedence tests
          .addVar("dyn_a", SimpleType.DYN)
          .addVar("dyn_b", SimpleType.DYN)
          .addVar("dyn_c", SimpleType.DYN)
          .addVar("dyn_d", SimpleType.DYN)
          // Explicit Primitive typed variables
          .addVar("bool_var", SimpleType.BOOL)
          .addVar("bool_var2", SimpleType.BOOL)
          .addVar("int_var", SimpleType.INT)
          .addVar("int_var2", SimpleType.INT)
          .addVar("uint_var", SimpleType.UINT)
          .addVar("uint_var2", SimpleType.UINT)
          .addVar("double_var", SimpleType.DOUBLE)
          .addVar("double_var2", SimpleType.DOUBLE)
          .addVar("string_var", SimpleType.STRING)
          .addVar("string_var2", SimpleType.STRING)
          .addVar("bytes_var", SimpleType.BYTES)
          .addVar("bytes_var2", SimpleType.BYTES)
          .addVar("duration_var", SimpleType.DURATION)
          .addVar("timestamp_var", SimpleType.TIMESTAMP)
          .addVar("null_var", SimpleType.NULL_TYPE)
          // Collection variables
          .addVar("int_list", ListType.create(SimpleType.INT))
          .addVar("string_list", ListType.create(SimpleType.STRING))
          .addVar("bool_list", ListType.create(SimpleType.BOOL))
          .addVar("nested_list", ListType.create(ListType.create(SimpleType.INT)))
          .addVar("opt_list", ListType.create(OptionalType.create(SimpleType.INT)))
          .addVar("string_int_map", MapType.create(SimpleType.STRING, SimpleType.INT))
          .addVar("int_string_map", MapType.create(SimpleType.INT, SimpleType.STRING))
          .addVar(
              "nested_map",
              MapType.create(SimpleType.STRING, MapType.create(SimpleType.STRING, SimpleType.INT)))
          .addVar("list_map", ListType.create(MapType.create(SimpleType.STRING, SimpleType.INT)))
          .addVar("int_list_map", MapType.create(SimpleType.INT, ListType.create(SimpleType.INT)))
          // Struct / proto message variables
          .addVar("msg", StructTypeReference.create(TestAllTypes.getDescriptor().getFullName()))
          .addVar("msg2", StructTypeReference.create(TestAllTypes.getDescriptor().getFullName()))
          .build();

  private static final CelOptimizer OPTIMIZER =
      CelOptimizerFactory.standardCelOptimizerBuilder(CEL)
          .addAstOptimizers(
              CanonicalizationOptimizer.newInstance(CanonicalizationOptions.newBuilder().build()))
          .build();

  private static final CelUnparser UNPARSER = CelUnparserFactory.newUnparser();

  private enum CanonicalizationTestCase {
    // Commutative Logical Operators (&&, ||) across Simple Types
    COMMUTATIVE_AND_BOOL(
        "bool_var == true && bool_var2 == false", "bool_var == true && bool_var2 == false"),
    COMMUTATIVE_AND_INT("int_var == 2 && int_var2 == 1", "int_var == 2 && int_var2 == 1"),
    COMMUTATIVE_AND_UINT(
        "uint_var == 20u && uint_var2 == 10u", "uint_var == 20u && uint_var2 == 10u"),
    COMMUTATIVE_AND_DOUBLE(
        "double_var == 3.14 && double_var2 == 1.41", "double_var == 3.14 && double_var2 == 1.41"),
    COMMUTATIVE_AND_STRING(
        "string_var == 'foo' && string_var2 == 'bar'",
        "string_var == \"foo\" && string_var2 == \"bar\""),
    COMMUTATIVE_AND_BYTES(
        "bytes_var == b'foo' && bytes_var2 == b'bar'",
        "bytes_var == b\"\\146\\157\\157\" && bytes_var2 == b\"\\142\\141\\162\""),
    COMMUTATIVE_AND_NULL("null_var == null && dyn_a == null", "dyn_a == null && null_var == null"),
    COMMUTATIVE_AND_MULTI_OPERAND(
        "string_var == 'c' && string_var == 'a' && string_var == 'b'",
        "string_var == \"a\" && string_var == \"b\" && string_var == \"c\""),
    COMMUTATIVE_OR_MULTI_OPERAND(
        "int_var == 30 || int_var == 10 || int_var == 20",
        "int_var == 10 || int_var == 20 || int_var == 30"),
    COMMUTATIVE_AND_DEDUPLICATION("int_var == 1 && int_var == 1", "int_var == 1"),
    COMMUTATIVE_OR_DEDUPLICATION("string_var == 'a' || string_var == 'a'", "string_var == \"a\""),
    COMMUTATIVE_AND_MIXED_TYPES(
        "string_var == 'foo' && int_var == 1", "int_var == 1 && string_var == \"foo\""),
    COMMUTATIVE_OR_MIXED_TYPES(
        "bool_var == true || int_var == 1", "bool_var == true || int_var == 1"),
    LIST_DIFFERENT_SIZES_EQUALITY("[1, 2] == [1]", "[1] == [1, 2]"),
    ONE_ARG_CALL_WITH_LOGICAL_OPERANDS(
        "type(bool_var == true && bool_var2 == false)",
        "type(bool_var == true && bool_var2 == false)"),
    COMMUTATIVE_AND_DURATION_TIMESTAMP(
        "timestamp_var == timestamp('2026-01-01T00:00:00Z') && duration_var == duration('10s')",
        "duration_var == duration(\"10s\") && timestamp_var =="
            + " timestamp(\"2026-01-01T00:00:00Z\")"),
    COMMUTATIVE_AND_NESTED_LOGIC(
        "(dyn_b || dyn_a) && (dyn_d || dyn_c)", "(dyn_a || dyn_b) && (dyn_c || dyn_d)"),

    // Symmetric Equality (==) and Inequality (!=) across Types
    SYMMETRIC_EQUALS_BOOL("true == bool_var", "bool_var == true"),
    SYMMETRIC_NOT_EQUALS_BOOL("false != bool_var", "bool_var != false"),
    SYMMETRIC_EQUALS_INT("42 == int_var", "int_var == 42"),
    SYMMETRIC_NOT_EQUALS_INT("0 != int_var", "int_var != 0"),
    SYMMETRIC_EQUALS_UINT("100u == uint_var", "uint_var == 100u"),
    SYMMETRIC_NOT_EQUALS_UINT("0u != uint_var", "uint_var != 0u"),
    SYMMETRIC_EQUALS_DOUBLE("3.14159 == double_var", "double_var == 3.14159"),
    SYMMETRIC_NOT_EQUALS_DOUBLE("0.0 != double_var", "double_var != 0.0"),
    SYMMETRIC_EQUALS_STRING("'hello' == string_var", "string_var == \"hello\""),
    SYMMETRIC_NOT_EQUALS_STRING("'' != string_var", "string_var != \"\""),
    SYMMETRIC_EQUALS_BYTES("b'abc' == bytes_var", "bytes_var == b\"\\141\\142\\143\""),
    SYMMETRIC_NOT_EQUALS_BYTES("b'' != bytes_var", "bytes_var != b\"\""),
    SYMMETRIC_EQUALS_IDENT_ORDERING("dyn_c == dyn_a", "dyn_a == dyn_c"),
    SYMMETRIC_EQUALS_CALL_VS_IDENT("size(int_list) == int_var", "int_var == size(int_list)"),
    SYMMETRIC_EQUALS_SELECT_VS_IDENT("msg.single_int64 == dyn_a", "dyn_a == msg.single_int64"),
    SYMMETRIC_EQUALS_GLOBAL_VS_MEMBER_CALL(
        "int_list.size() == size(int_list)", "size(int_list) == int_list.size()"),
    COMMUTATIVE_AND_GLOBAL_VS_MEMBER_CALL(
        "int_list.size() == 1 && size(int_list) == 1",
        "size(int_list) == 1 && int_list.size() == 1"),

    // De Morgan Transformations on Logical NOT (!)
    DE_MORGAN_DOUBLE_NEGATION("!!bool_var", "bool_var"),
    DE_MORGAN_QUADRUPLE_NEGATION("!!!!(int_var == 1)", "int_var == 1"),
    DE_MORGAN_AND_TYPED(
        "!(int_var == 1 && string_var == 'foo')", "int_var != 1 || string_var != \"foo\""),
    DE_MORGAN_OR_TYPED(
        "!(int_var == 1 || string_var == 'foo')", "int_var != 1 && string_var != \"foo\""),
    DE_MORGAN_EQUALS_TYPED("!(int_var == 1)", "int_var != 1"),
    DE_MORGAN_NOT_EQUALS_TYPED("!(int_var != 1)", "int_var == 1"),
    DE_MORGAN_NESTED_AND_OR(
        "!((dyn_a && dyn_b) || (dyn_c && dyn_d))", "(!dyn_a || !dyn_b) && (!dyn_c || !dyn_d)"),
    DE_MORGAN_NESTED_OR_AND(
        "!((dyn_a || dyn_b) && (dyn_c || dyn_d))", "!dyn_a && !dyn_b || !dyn_c && !dyn_d"),
    DE_MORGAN_MIXED_TYPES(
        "!(bool_var == true && double_var == 1.0)", "bool_var != true || double_var != 1.0"),
    DE_MORGAN_ALL_NEGATED_PREDICATE("!int_list.all(e, !(e == 1))", "e == 1"),
    DE_MORGAN_EXISTS_NEGATED_PREDICATE("!int_list.exists(e, !(e == 1))", "e == 1"),
    DE_MORGAN_ALL_NEGATED_VAR_PREDICATE(
        "!int_list.all(e, !bool_var)", "int_list.exists(e, bool_var)"),
    DE_MORGAN_EXISTS_NEGATED_VAR_PREDICATE(
        "!int_list.exists(e, !bool_var)", "int_list.all(e, bool_var)"),
    DE_MORGAN_RELATIONAL_UNCHANGED("!(int_var > 5)", "!(int_var > 5)"),
    DE_MORGAN_EXISTS_TYPED("!int_list.exists(e, e == 1)", "e != 1"),
    DE_MORGAN_ALL_TYPED("!int_list.all(e, e == 1)", "e != 1"),
    DE_MORGAN_EXISTS_COMPLEX_PREDICATE(
        "!int_list.exists(e, !(e == 1 && e == 2))", "e == 1 && e == 2"),
    DE_MORGAN_ALL_COMPLEX_PREDICATE("!int_list.all(e, !(e == 1 || e == 2))", "e == 1 || e == 2"),
    DE_MORGAN_BOOL_VARIABLES("!(bool_var && bool_var2)", "!bool_var || !bool_var2"),

    // Extension Coverage - Optionals & Optional Indexing/Fields
    OPTIONAL_OF_EQUALITY_SYMMETRY(
        "optional.of(dyn_b) == optional.of(dyn_a)", "optional.of(dyn_a) == optional.of(dyn_b)"),
    OPTIONAL_NONE_EQUALITY_SYMMETRY(
        "optional.of(dyn_a) == optional.none()", "optional.none() == optional.of(dyn_a)"),
    OPTIONAL_OF_NON_ZERO_VALUE_SYMMETRY(
        "optional.ofNonZeroValue(dyn_b) == optional.ofNonZeroValue(dyn_a)",
        "optional.ofNonZeroValue(dyn_a) == optional.ofNonZeroValue(dyn_b)"),
    OPTIONAL_FIELD_SELECT_EQUALITY(
        "msg.?single_int64 == optional.of(1)", "msg.?single_int64 == optional.of(1)"),
    OPTIONAL_FIELD_SELECT_INEQUALITY(
        "msg.?single_string != optional.none()", "msg.?single_string != optional.none()"),
    OPTIONAL_FIELD_SELECT_OR_VALUE_EQUALITY(
        "msg.?single_int64.orValue(0) == int_var", "int_var == msg.?single_int64.orValue(0)"),
    OPTIONAL_LIST_ELEMENT_EQUALITY(
        "[?optional.of(1)] == [?optional.of(int_var)]",
        "[?optional.of(int_var)] == [?optional.of(1)]"),
    OPTIONAL_MAP_ENTRY_EQUALITY(
        "{?'key': optional.of(1)} == {?'key': optional.of(int_var)}",
        "{?\"key\": optional.of(int_var)} == {?\"key\": optional.of(1)}"),
    DE_MORGAN_OPTIONAL_EQUALITY(
        "!(optional.of(dyn_a) == optional.of(dyn_b))", "optional.of(dyn_a) != optional.of(dyn_b)"),
    DE_MORGAN_OPTIONAL_INEQUALITY(
        "!(optional.of(dyn_a) != optional.none())", "optional.none() == optional.of(dyn_a)"),
    COMMUTATIVE_AND_OPTIONAL_HAS_VALUE(
        "optional.of(dyn_b).hasValue() && optional.of(dyn_a).hasValue()",
        "optional.of(dyn_a).hasValue() && optional.of(dyn_b).hasValue()"),
    COMMUTATIVE_OR_OPTIONAL_HAS_VALUE(
        "optional.of(dyn_b).hasValue() || optional.of(dyn_a).hasValue()",
        "optional.of(dyn_a).hasValue() || optional.of(dyn_b).hasValue()"),
    COMMUTATIVE_AND_OPTIONAL_FIELD_SELECT(
        "msg.?single_string.hasValue() && msg.?single_int64.hasValue()",
        "msg.?single_int64.hasValue() && msg.?single_string.hasValue()"),
    COMMUTATIVE_OR_OPTIONAL_FIELD_SELECT(
        "msg.?single_string.hasValue() || msg.?single_int64.hasValue()",
        "msg.?single_int64.hasValue() || msg.?single_string.hasValue()"),
    DE_MORGAN_OPTIONAL_HAS_VALUE_AND(
        "!(optional.of(dyn_a).hasValue() && optional.of(dyn_b).hasValue())",
        "!optional.of(dyn_a).hasValue() || !optional.of(dyn_b).hasValue()"),
    DE_MORGAN_OPTIONAL_HAS_VALUE_OR(
        "!(optional.of(dyn_a).hasValue() || optional.of(dyn_b).hasValue())",
        "!optional.of(dyn_a).hasValue() && !optional.of(dyn_b).hasValue()"),
    OPTIONAL_IN_EXISTS_COMPREHENSION(
        "!opt_list.exists(x, !(x.hasValue() && x.value() == 1))", "x.value() == 1 && x.hasValue()"),
    OPTIONAL_IN_ALL_COMPREHENSION(
        "!opt_list.all(x, !(x.hasValue() || x.value() == 1))", "x.value() == 1 || x.hasValue()"),
    OPTIONAL_FIELD_CHAINING_EQUALITY(
        "msg.?single_nested_message.?bb == optional.of(42)",
        "msg.?single_nested_message.?bb == optional.of(42)"),
    OPTIONAL_MAP_INDEXING_EQUALITY(
        "string_int_map.?foo == optional.of(1)", "string_int_map.?foo == optional.of(1)"),

    // Extension Coverage - Two-Variable Comprehensions
    DE_MORGAN_2VAR_EXISTS_MAP(
        "!string_int_map.exists(k, v, k == 'foo' && v == 1)", "k != \"foo\" || v != 1"),
    DE_MORGAN_2VAR_ALL_MAP(
        "!string_int_map.all(k, v, !(k == 'foo' || v == 1))", "k == \"foo\" || v == 1"),
    DE_MORGAN_2VAR_EXISTS_NEGATED_PREDICATE(
        "!string_int_map.exists(k, v, !(v > 0 && k == 'foo'))", "k == \"foo\" && v > 0"),
    DE_MORGAN_2VAR_ALL_NEGATED_PREDICATE(
        "!string_int_map.all(k, v, !(v > 0 || k == 'foo'))", "k == \"foo\" || v > 0"),
    TWO_VAR_EXISTS_COMMUTATIVE_AND(
        "string_int_map.exists(k, v, v == 1 && k == 'foo')",
        "string_int_map.exists(k, v, k == \"foo\" && v == 1)"),
    TWO_VAR_ALL_COMMUTATIVE_OR(
        "string_int_map.all(k, v, v == 1 || k == 'foo')",
        "string_int_map.all(k, v, k == \"foo\" || v == 1)"),
    TWO_VAR_EXISTS_SYMMETRIC_EQUALITY(
        "string_int_map.exists(k, v, v == 1)", "string_int_map.exists(k, v, v == 1)"),
    TWO_VAR_ALL_SYMMETRIC_INEQUALITY(
        "string_int_map.all(k, v, v != 0)", "string_int_map.all(k, v, v != 0)"),
    TWO_VAR_EXISTS_INT_STRING_MAP(
        "int_string_map.exists(k, v, v == 'bar' && k == 1)",
        "int_string_map.exists(k, v, k == 1 && v == \"bar\")"),
    TWO_VAR_ALL_INT_STRING_MAP(
        "!int_string_map.all(k, v, k == 1 || v == 'bar')", "k != 1 && v != \"bar\""),
    TWO_VAR_EXISTS_LIST_INDEX_VALUE(
        "!int_list.exists(i, v, i == 0 && v == 100)", "i != 0 || v != 100"),
    TWO_VAR_ALL_LIST_INDEX_VALUE(
        "!int_list.all(i, v, !(i == 0 || v == 100))", "i == 0 || v == 100"),
    TWO_VAR_EXISTS_LIST_COMMUTATIVE_AND(
        "int_list.exists(i, v, v == 100 && i == 0)", "int_list.exists(i, v, i == 0 && v == 100)"),
    TWO_VAR_ALL_LIST_COMMUTATIVE_OR(
        "int_list.all(i, v, v == 100 || i == 0)", "int_list.all(i, v, i == 0 || v == 100)"),
    TWO_VAR_NESTED_COMPREHENSIONS(
        "string_int_map.exists(k, v, k == 'foo' && int_list.all(i, e, e == v && i == 0))",
        "string_int_map.exists(k, v, k == \"foo\" && int_list.all(i, e, e == v && i == 0))"),
    DE_MORGAN_2VAR_NESTED_COMPREHENSIONS(
        "string_int_map.exists(k, v, k == 'foo' && !int_list.exists(i, e, e == v))",
        "string_int_map.exists(k, v, k == \"foo\" && e != v)"),
    TWO_VAR_COMPREHENSION_WITH_OPTIONALS(
        "!string_int_map.exists(k, v, optional.of(v).hasValue() && k == 'foo')",
        "!optional.of(v).hasValue() || k != \"foo\""),
    TWO_VAR_COMPREHENSION_STRUCT_FIELDS(
        "!string_int_map.exists(k, v, !(k == msg.single_string && v == msg.single_int64))",
        "k == msg.single_string && v == msg.single_int64"),
    TWO_VAR_COMPREHENSION_DEDUPLICATION(
        "string_int_map.exists(k, v, k == 'foo' && k == 'foo')",
        "string_int_map.exists(k, v, k == \"foo\")"),
    TWO_VAR_COMPREHENSION_DE_MORGAN_INEQUALITY(
        "!string_int_map.exists(k, v, !(k != 'foo' && v != 1))", "k != \"foo\" && v != 1"),

    // Extension Coverage - cel.bind Macro
    CEL_BIND_COMMUTATIVE_AND(
        "cel.bind(x, int_var + 10, 1 == x && 2 == int_var2)",
        "cel.bind(x, int_var + 10, int_var2 == 2 && x == 1)"),
    CEL_BIND_COMMUTATIVE_OR(
        "cel.bind(x, int_var + 10, 1 == x || 2 == int_var2)",
        "cel.bind(x, int_var + 10, int_var2 == 2 || x == 1)"),
    CEL_BIND_SYMMETRIC_EQUALITY(
        "cel.bind(x, int_var + 10, 20 == x)", "cel.bind(x, int_var + 10, x == 20)"),
    CEL_BIND_NESTED(
        "cel.bind(x, int_var + 10, cel.bind(y, int_var2 + 20, 2 == y && 1 == x))",
        "cel.bind(x, int_var + 10, cel.bind(y, int_var2 + 20, x == 1 && y == 2))"),
    CEL_BIND_DE_MORGAN(
        "cel.bind(x, int_var == 1, !(2 == int_var2 && x == true))",
        "cel.bind(x, int_var == 1, int_var2 != 2 || x != true)"),

    // Nested Lists, Maps, and Structs
    NESTED_LIST_EQUALITY_SYMMETRY(
        "[[2, 1], [4, 3]] == [[1, 2], [3, 4]]", "[[1, 2], [3, 4]] == [[2, 1], [4, 3]]"),
    NESTED_LIST_INEQUALITY_SYMMETRY("[[2, 1]] != [[1, 2]]", "[[1, 2]] != [[2, 1]]"),
    LIST_ELEMENT_ORDERING_EQUALITY("int_list == [3, 2, 1]", "int_list == [3, 2, 1]"),
    MAP_EQUALITY_ORDERING(
        "string_int_map == {'b': 2, 'a': 1}", "string_int_map == {\"b\": 2, \"a\": 1}"),
    MAP_DIFFERENT_SIZES_EQUALITY(
        "{'b': 2, 'a': 1} == {'a': 1}", "{\"a\": 1} == {\"b\": 2, \"a\": 1}"),
    COMMUTATIVE_AND_MAP_DIFFERENT_SIZES(
        "string_int_map == {'a': 1, 'b': 2} && string_int_map == {'a': 1}",
        "string_int_map == {\"a\": 1} && string_int_map == {\"a\": 1, \"b\": 2}"),
    NESTED_MAP_EQUALITY(
        "nested_map == {'b': {'d': 4, 'c': 3}, 'a': {'y': 2, 'x': 1}}",
        "nested_map == {\"b\": {\"d\": 4, \"c\": 3}, \"a\": {\"y\": 2, \"x\": 1}}"),
    LIST_OF_MAPS_EQUALITY(
        "list_map == [{'y': 2, 'x': 1}, {'d': 4, 'c': 3}]",
        "list_map == [{\"y\": 2, \"x\": 1}, {\"d\": 4, \"c\": 3}]"),
    MAP_OF_LISTS_EQUALITY(
        "int_list_map == {1: [2, 1], 2: [4, 3]}", "int_list_map == {1: [2, 1], 2: [4, 3]}"),
    STRUCT_EQUALITY_ORDERING(
        "msg == TestAllTypes{single_int64: 10, single_string: 'foo'}",
        "msg == cel.expr.conformance.proto3.TestAllTypes{single_int64: 10, single_string:"
            + " \"foo\"}"),
    NESTED_STRUCT_EQUALITY(
        "msg == TestAllTypes{single_nested_message: TestAllTypes.NestedMessage{bb: 42}}",
        "msg == cel.expr.conformance.proto3.TestAllTypes{single_nested_message:"
            + " cel.expr.conformance.proto3.TestAllTypes.NestedMessage{bb: 42}}"),
    STRUCT_INEQUALITY(
        "msg != TestAllTypes{single_int64: 0}",
        "msg != cel.expr.conformance.proto3.TestAllTypes{single_int64: 0}"),
    STRUCT_DIFFERENT_ENTRY_COUNTS_ORDERING(
        "TestAllTypes{single_int64: 10, single_string: 'foo'} == TestAllTypes{single_int64: 10}",
        "cel.expr.conformance.proto3.TestAllTypes{single_int64: 10} =="
            + " cel.expr.conformance.proto3.TestAllTypes{single_int64: 10, single_string:"
            + " \"foo\"}"),
    STRUCT_DIFFERENT_FIELD_VALUES_ORDERING(
        "TestAllTypes{single_int64: 20} == TestAllTypes{single_int64: 10}",
        "cel.expr.conformance.proto3.TestAllTypes{single_int64: 10} =="
            + " cel.expr.conformance.proto3.TestAllTypes{single_int64: 20}"),
    DE_MORGAN_STRUCT_EQUALITY(
        "!(msg == TestAllTypes{single_int64: 10})",
        "msg != cel.expr.conformance.proto3.TestAllTypes{single_int64: 10}"),
    DE_MORGAN_STRUCT_INEQUALITY(
        "!(msg != TestAllTypes{single_int64: 10})",
        "msg == cel.expr.conformance.proto3.TestAllTypes{single_int64: 10}"),
    COMMUTATIVE_AND_STRUCT_FIELDS(
        "msg.single_string == 'foo' && msg.single_int64 == 10",
        "msg.single_int64 == 10 && msg.single_string == \"foo\""),
    COMMUTATIVE_OR_STRUCT_FIELDS(
        "msg.single_int64 == 20 || msg.single_int64 == 10",
        "msg.single_int64 == 10 || msg.single_int64 == 20"),
    DE_MORGAN_STRUCT_FIELDS_AND(
        "!(msg.single_int64 == 10 && msg.single_string == 'foo')",
        "msg.single_int64 != 10 || msg.single_string != \"foo\""),
    DE_MORGAN_STRUCT_FIELDS_OR(
        "!(msg.single_int64 == 10 || msg.single_int64 == 20)",
        "msg.single_int64 != 10 && msg.single_int64 != 20"),
    STRUCT_SELECT_ORDERING("msg.single_int64 == int_var", "int_var == msg.single_int64"),
    NESTED_STRUCT_SELECT_ORDERING(
        "msg.single_nested_message.bb == int_var", "int_var == msg.single_nested_message.bb"),
    MAP_LOOKUP_IN_LOGICAL_EXPR(
        "string_int_map['foo'] == 1 && string_int_map['bar'] == 2",
        "string_int_map[\"bar\"] == 2 && string_int_map[\"foo\"] == 1"),
    LIST_INDEX_IN_LOGICAL_EXPR(
        "int_list[1] == 20 && int_list[0] == 10", "int_list[0] == 10 && int_list[1] == 20"),
    COMPREHENSIONS_IN_LIST_LITERALS(
        "[int_list.exists(e, e == 2), int_list.exists(e, e == 1)] == [true, false]",
        "[int_list.exists(e, e == 2), int_list.exists(e, e == 1)] == [true, false]"),
    COMPREHENSIONS_IN_MAP_LITERALS(
        "{'b': int_list.all(e, e > 0), 'a': int_list.exists(e, e == 1)} == {'a': true, 'b': false}",
        "{\"a\": true, \"b\": false} == {\"b\": int_list.all(e, e > 0), \"a\": int_list.exists(e, e"
            + " == 1)}"),
    COMPREHENSIONS_IN_STRUCT_FIELDS(
        "TestAllTypes{single_int64: int_list[0]} == msg",
        "msg == cel.expr.conformance.proto3.TestAllTypes{single_int64: int_list[0]}"),
    NESTED_COMPREHENSIONS_IN_STRUCT_FIELDS(
        "!int_list.exists(x, TestAllTypes{single_int64: x} == msg)",
        "msg != cel.expr.conformance.proto3.TestAllTypes{single_int64: x}"),
    DE_MORGAN_COLLECTION_LITERAL_EQUALITY("!([2, 1] == [1, 2])", "[1, 2] != [2, 1]"),

    // Cross-Type & Heterogeneous Comparisons
    HETEROGENEOUS_INT_UINT_AND("int_var == 1 && uint_var == 1u", "int_var == 1 && uint_var == 1u"),
    HETEROGENEOUS_INT_DOUBLE_OR(
        "int_var == 1 || double_var == 1.0", "double_var == 1.0 || int_var == 1"),
    HETEROGENEOUS_UINT_DOUBLE_EQUALITY(
        "uint_var == 10u && double_var == 10.0", "double_var == 10.0 && uint_var == 10u"),
    CROSS_TYPE_DURATION_TIMESTAMP_AND(
        "timestamp_var == timestamp('2026-01-01T00:00:00Z') && duration_var == duration('10s')",
        "duration_var == duration(\"10s\") && timestamp_var =="
            + " timestamp(\"2026-01-01T00:00:00Z\")"),
    CROSS_TYPE_STRING_BYTES_OR(
        "string_var == 'foo' || bytes_var == b'foo'",
        "bytes_var == b\"\\146\\157\\157\" || string_var == \"foo\""),
    NULL_VS_PRIMITIVE_EQUALITY("dyn_a == null", "dyn_a == null"),
    NULL_VS_MESSAGE_EQUALITY("msg == null", "msg == null"),
    NULL_VS_OPTIONAL_EQUALITY("optional.of(int_var) == null", "optional.of(int_var) == null"),
    CROSS_TYPE_COMMUTATIVE_CHAIN(
        "string_var == 'a' && double_var == 1.0 && int_var == 1 && bool_var == true",
        "bool_var == true && double_var == 1.0 && int_var == 1 && string_var == \"a\""),
    DE_MORGAN_CROSS_TYPE_CHAIN(
        "!(string_var == 'a' && double_var == 1.0 && int_var == 1)",
        "double_var != 1.0 || int_var != 1 || string_var != \"a\""),
    HETEROGENEOUS_NUMERIC_DE_MORGAN(
        "!(int_var != 1 || uint_var != 1u || double_var != 1.0)",
        "double_var == 1.0 && int_var == 1 && uint_var == 1u"),
    CROSS_TYPE_IN_2VAR_COMPREHENSION(
        "!string_int_map.exists(k, v, !(int_var == 1 && double_var == 1.0))",
        "double_var == 1.0 && int_var == 1"),
    CROSS_TYPE_IN_LIST_COMPREHENSION(
        "!int_list.exists(e, !(uint_var == 1u || double_var == 1.0))",
        "double_var == 1.0 || uint_var == 1u"),
    MIXED_SELECT_AND_CALLS_ACROSS_TYPES(
        "msg.single_int64 == size(int_list) && msg.single_string == string(int_var)",
        "msg.single_int64 == size(int_list) && msg.single_string == string(int_var)"),
    DE_MORGAN_MIXED_SELECT_AND_CALLS(
        "!(msg.single_int64 == size(int_list) && msg.single_string == 'foo')",
        "msg.single_int64 != size(int_list) || msg.single_string != \"foo\""),

    // Edge Cases, Invariants, Non-Canonicalizable Expressions, and Precedence
    RELATIONAL_OPERATORS_UNCHANGED("int_var < 10 && int_var > 5", "int_var < 10 && int_var > 5"),
    DE_MORGAN_RELATIONAL_OPERATORS("!(int_var < 10)", "!(int_var < 10)"),
    TERNARY_OPERATOR_UNCHANGED_COND(
        "bool_var ? int_var == 1 : int_var == 2", "bool_var ? (int_var == 1) : (int_var == 2)"),
    DE_MORGAN_TERNARY_OPERATOR(
        "!(bool_var ? int_var == 1 : int_var == 2)",
        "!(bool_var ? (int_var == 1) : (int_var == 2))"),
    IN_OPERATOR_WITH_COMMUTATIVE_AND(
        "int_var in int_list && bool_var == true", "int_var in int_list && bool_var == true"),
    DE_MORGAN_IN_OPERATOR("!(int_var in int_list)", "!(int_var in int_list)"),
    COMPREHENSION_ACCU_VAR_NOT_REORDERED(
        "int_list.exists(e, e == 1 && e == 2)", "int_list.exists(e, e == 1 && e == 2)"),
    COMPLEX_NESTED_DE_MORGAN_PRECEDENCE(
        "!(dyn_a && dyn_b || dyn_c && dyn_d)", "(!dyn_a || !dyn_b) && (!dyn_c || !dyn_d)"),
    COMPLEX_NESTED_DE_MORGAN_OR_AND(
        "!((dyn_a || dyn_b) && (dyn_c || dyn_d))", "!dyn_a && !dyn_b || !dyn_c && !dyn_d"),
    TRIPLE_AND_DEDUPLICATION("int_var == 1 && int_var == 1 && int_var == 1", "int_var == 1"),
    TRIPLE_OR_DEDUPLICATION(
        "string_var == 'x' || string_var == 'x' || string_var == 'x'", "string_var == \"x\""),
    EMPTY_STRING_ZERO_CONSTANT_COMPARISONS(
        "string_var == '' && int_var == 0 && bool_var == false",
        "bool_var == false && int_var == 0 && string_var == \"\""),
    IDENT_COMPARISON_SYMMETRY(
        "dyn_b == dyn_a && dyn_d == dyn_c", "dyn_a == dyn_b && dyn_c == dyn_d"),
    IDENT_INEQUALITY_SYMMETRY(
        "dyn_b != dyn_a || dyn_d != dyn_c", "dyn_a != dyn_b || dyn_c != dyn_d"),
    IDENT_SAME_NAME_DIFFERENT_OPERATORS(
        "dyn_a != dyn_b && dyn_a == dyn_b", "dyn_a != dyn_b && dyn_a == dyn_b");

    private final String input;
    private final String expected;

    CanonicalizationTestCase(String input, String expected) {
      this.input = input;
      this.expected = expected;
    }
  }

  @Test
  public void optimize_success(@TestParameter CanonicalizationTestCase testCase) throws Exception {
    CelAbstractSyntaxTree ast = CEL.compile(testCase.input).getAst();
    CelAbstractSyntaxTree optimizedAst = OPTIMIZER.optimize(ast);

    String unparsed = UNPARSER.unparse(optimizedAst);
    assertThat(unparsed).isEqualTo(testCase.expected);
  }

  @Test
  public void optimize_maxIterationLimitReached_throwsException() throws Exception {
    CelAbstractSyntaxTree ast = CEL.compile("dyn_b == dyn_a && dyn_d == dyn_c").getAst();
    CanonicalizationOptimizer optimizer =
        CanonicalizationOptimizer.newInstance(
            CanonicalizationOptions.newBuilder().maxIterationLimit(1).build());

    IllegalStateException e =
        assertThrows(IllegalStateException.class, () -> optimizer.optimize(ast, CEL));
    assertThat(e).hasMessageThat().contains("Max iteration count reached.");
  }

  @Test
  public void optimize_deMorganAll_evaluatesCorrectly() throws Exception {
    CelAbstractSyntaxTree ast = CEL.compile("!int_list.all(e, e == 1)").getAst();
    CelAbstractSyntaxTree optimizedAst = OPTIMIZER.optimize(ast);

    boolean result =
        (boolean)
            CEL.createProgram(optimizedAst)
                .eval(ImmutableMap.of("int_list", ImmutableList.of(1, 2)));
    assertThat(result).isTrue();
  }

  @Test
  public void optimize_deMorganExists_evaluatesCorrectly() throws Exception {
    CelAbstractSyntaxTree ast = CEL.compile("!int_list.exists(e, e == 1)").getAst();
    CelAbstractSyntaxTree optimizedAst = OPTIMIZER.optimize(ast);

    boolean result =
        (boolean)
            CEL.createProgram(optimizedAst)
                .eval(ImmutableMap.of("int_list", ImmutableList.of(1, 2)));
    assertThat(result).isFalse();
  }

  @Test
  public void optimize_deMorganAll_negatedPredicate_evaluatesCorrectly() throws Exception {
    CelAbstractSyntaxTree ast = CEL.compile("!int_list.all(e, !(e == 1))").getAst();
    CelAbstractSyntaxTree optimizedAst = OPTIMIZER.optimize(ast);

    boolean result =
        (boolean)
            CEL.createProgram(optimizedAst)
                .eval(ImmutableMap.of("int_list", ImmutableList.of(1, 2)));
    assertThat(result).isTrue();
  }

  @Test
  public void optimize_deMorganExists_negatedPredicate_evaluatesCorrectly() throws Exception {
    CelAbstractSyntaxTree ast = CEL.compile("!int_list.exists(e, !(e == 1))").getAst();
    CelAbstractSyntaxTree optimizedAst = OPTIMIZER.optimize(ast);

    boolean result =
        (boolean)
            CEL.createProgram(optimizedAst)
                .eval(ImmutableMap.of("int_list", ImmutableList.of(1, 2)));
    assertThat(result).isFalse();
  }

  @Test
  public void optimize_customMacroWithExistsStructure_notCanonicalized() throws Exception {
    CelAbstractSyntaxTree ast = CEL.compile("!int_list.exists(e, e == 1)").getAst();
    CelMutableAst mutableAst = CelMutableAst.fromCelAst(ast);
    long macroKey = mutableAst.source().getMacroCalls().keySet().iterator().next();
    CelMutableExpr existingMacro = mutableAst.source().getMacroCalls().get(macroKey);
    CelMutableCall customCall =
        CelMutableCall.create(
            existingMacro.call().target().get(), "my_custom_exists", existingMacro.call().args());
    mutableAst
        .source()
        .addMacroCalls(macroKey, CelMutableExpr.ofCall(existingMacro.id(), customCall));

    CelAbstractSyntaxTree optimizedAst =
        CanonicalizationOptimizer.newInstance(CanonicalizationOptions.newBuilder().build())
            .optimize(mutableAst.toParsedAst(), CEL)
            .optimizedAst();
    assertThat(UNPARSER.unparse(optimizedAst)).isEqualTo("!int_list.my_custom_exists(e, e == 1)");
  }
}
