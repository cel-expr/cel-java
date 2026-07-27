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
import static com.google.common.truth.Truth.assertWithMessage;
import static java.util.stream.Collectors.joining;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
// import com.google.testing.testsize.MediumTest;
import com.microsoft.z3.BoolExpr;
import com.microsoft.z3.Context;
import com.microsoft.z3.Expr;
import com.microsoft.z3.IntExpr;
import dev.cel.bundle.Cel;
import dev.cel.bundle.CelFactory;
import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.CelContainer;
import dev.cel.common.CelFunctionDecl;
import dev.cel.common.CelOptions;
import dev.cel.common.CelOverloadDecl;
import dev.cel.common.Operator;
import dev.cel.common.ast.CelConstant;
import dev.cel.common.ast.CelExpr;
import dev.cel.common.ast.CelExpr.CelCall;
import dev.cel.common.types.ListType;
import dev.cel.common.types.MapType;
import dev.cel.common.types.NullableType;
import dev.cel.common.types.OptionalType;
import dev.cel.common.types.ProtoMessageTypeProvider;
import dev.cel.common.types.SimpleType;
import dev.cel.common.types.StructTypeReference;
import dev.cel.compiler.CelCompiler;
import dev.cel.expr.conformance.proto3.TestAllTypes;
import dev.cel.extensions.CelExtensions;
import dev.cel.extensions.CelOptionalLibrary;
import dev.cel.optimizer.CelOptimizer;
import dev.cel.optimizer.CelOptimizerFactory;
import dev.cel.optimizer.optimizers.ConstantFoldingOptimizer;
import dev.cel.parser.CelMacro;
import dev.cel.parser.CelStandardMacro;
import dev.cel.verifier.CelVerificationResult.VerificationStatus;
import dev.cel.verifier.axioms.CelZ3FunctionAxiom;
import dev.cel.verifier.axioms.CelZ3OverloadResult;
import dev.cel.verifier.axioms.CelZ3OverloadTranslator;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.IntStream;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

// @MediumTest
@RunWith(TestParameterInjector.class)
public final class CelVerifierZ3ImplTest {

  private static final Cel CEL =
      CelFactory.plannerCelBuilder()
          .setOptions(CelOptions.current().enableHeterogeneousNumericComparisons(true).build())
          .setStandardMacros(CelStandardMacro.STANDARD_MACROS)
          .setContainer(CelContainer.ofName("cel.expr.conformance.proto3"))
          .addCompilerLibraries(
              CelExtensions.bindings(), CelOptionalLibrary.INSTANCE, CelExtensions.comprehensions())
          .addMessageTypes(TestAllTypes.getDescriptor(), TestAllTypes.NestedMessage.getDescriptor())
          .addVar("x", SimpleType.INT)
          .addVar("u", SimpleType.UINT)
          .addVar("u1", SimpleType.UINT)
          .addVar("u2", SimpleType.UINT)
          .addVar("d", SimpleType.DOUBLE)
          .addVar("by", SimpleType.BYTES)
          .addVar("y", SimpleType.INT)
          .addVar("a", SimpleType.BOOL)
          .addVar("b", SimpleType.BOOL)
          .addVar("role", SimpleType.STRING)
          .addVar("country", SimpleType.STRING)
          .addVar("port", SimpleType.INT)
          .addVar("request", SimpleType.DYN)
          .addVar("unknown_var", SimpleType.DYN)
          .addVar("int_list", ListType.create(SimpleType.INT))
          .addVar("int_list_2", ListType.create(SimpleType.INT))
          .addVar("nested_list", ListType.create(ListType.create(SimpleType.INT)))
          .addVar("nested_list_2", ListType.create(ListType.create(SimpleType.INT)))
          .addVar("dyn_list", ListType.create(SimpleType.DYN))
          .addVar("dyn_map", MapType.create(SimpleType.DYN, SimpleType.DYN))
          .addVar("dyn_var", SimpleType.DYN)
          .addVar("dyn_var2", SimpleType.DYN)
          .addVar("opt_var", OptionalType.create(SimpleType.INT))
          .addVar("opt_dyn_var", OptionalType.create(SimpleType.DYN))
          .addVar("nullable_int", NullableType.create(SimpleType.INT))
          .addVar("string_int_map", MapType.create(SimpleType.STRING, SimpleType.INT))
          .addVar("bytes_val", SimpleType.BYTES)
          .addVar(
              "string_int_list_map",
              MapType.create(SimpleType.STRING, ListType.create(SimpleType.INT)))
          .addVar(
              "test_all_types",
              StructTypeReference.create("cel.expr.conformance.proto3.TestAllTypes"))
          .build();

  private static final CelVerifier VERIFIER =
      CelVerifierFactory.newVerifier()
          .setTypeProvider(
              ProtoMessageTypeProvider.newBuilder()
                  .addDescriptors(
                      ImmutableList.of(
                          TestAllTypes.getDescriptor(), TestAllTypes.NestedMessage.getDescriptor()))
                  .build())
          .build();

  @Before
  public void setUp() {
    System.setProperty("z3.skipLibraryLoad", "true");
  }

  private enum IsSatisfiableTestCase {
    SATISFIABLE("x > 5"),
    DYNAMIC_ARITHMETIC("request == 1 && request + 2 == 3"),
    DYNAMIC_ARITHMETIC_UNARY("request == 1 && -request == -1"),
    GREATER_DOUBLE("d > 1.5"),
    LESS_EQUALS_UINT64("u <= 5u"),
    LESS_EQUALS_DOUBLE("d <= 5.5"),
    LESS_EQUALS_STRING("role <= 'admin'"),
    LESS_EQUALS_BYTES("by <= b'bytes'"),
    GREATER_STRING("role > 'admin'"),
    GREATER_BYTES("by > b'bytes'"),
    DYNAMIC_LIST_COMPREHENSION_EXISTS("int_list.exists(x, x > 5)"),
    DYNAMIC_MAP_COMPREHENSION_EXISTS("string_int_map.exists(k, k == 'test')"),
    NULL_SATISFIABLE("unknown_var == null"),
    DYNAMIC_VAR_NUMERIC_EQUALITY("dyn_var == 1 && dyn_var == 1.0"),
    DYNAMIC_VAR_NOT_IN_LIST("dyn_var == 1.5 && !(dyn_var in dyn_list) && size(dyn_list) > 5"),
    TIMESTAMP_EQUALITY_TAUTOLOGY(
        "timestamp('2023-01-01T00:00:00Z') == timestamp('2023-01-01T00:00:00Z')"),
    CROSS_NUMERIC_EQUALITY_INT_DYN_EXACT("1 == request"),
    MACRO_LIMIT("dyn_list.all(x, x == 1)"),
    STRUCT_FIELD_MISSING_APPROXIMATE_SATISFIABLE("dyn_var.unknown_field"),
    NULLABLE_INT_SATISFIABLE("nullable_int == 123");

    final String expr;

    IsSatisfiableTestCase(String expr) {
      this.expr = expr;
    }
  }

  @Test
  public void isSatisfiable_typeConversionApproximation() throws Exception {
    CelAbstractSyntaxTree astInt = CEL.compile("type(int('1')) == int").getAst();
    assertThat(VERIFIER.isSatisfiable(astInt).status()).isEqualTo(VerificationStatus.VERIFIED);

    CelAbstractSyntaxTree astDouble = CEL.compile("type(double('1.5')) == double").getAst();
    assertThat(VERIFIER.isSatisfiable(astDouble).status()).isEqualTo(VerificationStatus.VERIFIED);
  }

  @Test
  public void isSatisfiable_success(@TestParameter IsSatisfiableTestCase testCase)
      throws Exception {
    CelAbstractSyntaxTree ast = CEL.compile(testCase.expr).getAst();

    CelVerificationResult result = VERIFIER.isSatisfiable(ast);

    assertThat(result.status()).isEqualTo(VerificationStatus.VERIFIED);
  }

  @Test
  public void isSatisfiable_withVariable_returnsSatisfyingModel() throws Exception {
    CelAbstractSyntaxTree ast = CEL.compile("x > 5").getAst();

    CelVerificationResult result = VERIFIER.isSatisfiable(ast);

    assertThat(result.status()).isEqualTo(VerificationStatus.VERIFIED);
    assertThat(result.message()).contains("Condition is satisfiable.");
    assertThat(result.message()).contains("Satisfying input:");
    assertThat(result.message()).containsMatch("x = (?:[6-9]|[1-9]\\d+)");
  }

  @Test
  public void isSatisfiable_unconditional_returnsUnconditionalMessage() throws Exception {
    CelAbstractSyntaxTree ast = CEL.compile("1 + 1 == 2").getAst();

    CelVerificationResult result = VERIFIER.isSatisfiable(ast);

    assertThat(result.status()).isEqualTo(VerificationStatus.VERIFIED);
    assertThat(result.message())
        .isEqualTo(
            "Condition is satisfiable. (The expression is satisfiable unconditionally, regardless"
                + " of input state)");
  }

  @Test
  public void isSatisfiable_approximate_returnsPotentialSatisfyingInput() throws Exception {
    CelAbstractSyntaxTree ast = CEL.compile("int('123') == 123 ? x > 5 : false").getAst();

    CelVerificationResult result = VERIFIER.isSatisfiable(ast);

    assertThat(result.status()).isEqualTo(VerificationStatus.INCONCLUSIVE);
    assertThat(result.message()).contains("Inconclusive: a satisfying model may exist");
    assertThat(result.message()).contains("Potential satisfying input:");
    assertThat(result.message()).containsMatch("x = (?:[6-9]|[1-9]\\d+)");
  }

  private enum IsSatisfiableInconclusiveTestCase {
    MASKED_BY_BMC("int_list == [1, 2, 3, 4, 5, 6] ? int_list.exists(x, x == 42) : false"),
    MASKED_BY_BMC_ALL("int_list == [1, 2, 3, 4, 5, 6] ? int_list.all(x, x > 0) : false"),
    MASKED_BY_BMC_FILTER(
        "int_list == [1, 2, 3, 4, 5, 6] ? size(int_list.filter(x, x > 2)) == 3 : false"),
    MASKED_BY_BMC_MAP(
        "string_int_map == {'a':1, 'b':2, 'c':3, 'd':4, 'e':5, 'f':6} ? string_int_map.exists(k,"
            + " k == 'g') : false"),
    MASKED_BY_BMC_NESTED(
        "nested_list == [[1, 2, 3, 4, 5, 6]] ? nested_list.exists(row, row.exists(x, x == 42)) :"
            + " false"),
    APPROXIMATED_STRING_TO_INT("int('123') == 123"),
    APPROXIMATED_DOUBLE_TO_INT("int(1.5) == 1"),
    APPROXIMATED_INT_TO_STRING("string(123) == '123'"),
    APPROXIMATED_RANGE("int('123') > 100 && int('123') < 200"),
    APPROXIMATED_BRANCHING("int('123') == 123 ? x > 5 : false");

    final String expr;

    IsSatisfiableInconclusiveTestCase(String expr) {
      this.expr = expr;
    }
  }

  @Test
  public void isSatisfiable_inconclusive(@TestParameter IsSatisfiableInconclusiveTestCase testCase)
      throws Exception {
    CelAbstractSyntaxTree ast = CEL.compile(testCase.expr).getAst();

    CelVerificationResult result = VERIFIER.isSatisfiable(ast);

    assertThat(result.status()).isEqualTo(VerificationStatus.INCONCLUSIVE);
  }

  @Test
  public void isSatisfiable_comprehensionZeroUnrollLimit_inconclusive() throws Exception {
    String expr = "int_list == [1] ? int_list.exists(x, x == 1) : false";
    CelAbstractSyntaxTree ast = CEL.compile(expr).getAst();

    CelVerifier verifier = CelVerifierFactory.newVerifier().setComprehensionUnrollLimit(0).build();
    CelVerificationResult result = verifier.isSatisfiable(ast);

    assertThat(result.status()).isEqualTo(VerificationStatus.INCONCLUSIVE);
  }

  private enum IsUnsatisfiableTestCase {
    UNSATISFIABLE("x > 5 && x < 3"),
    CONTRADICTORY_TYPES_FOR_DYNAMIC_VARIABLE("unknown_var + 1 == 2 && unknown_var[0] == 1"),
    TYPE_CONVERSION_UNSATISFIABLE_STRING_TO_INT("type(int('1')) == string"),
    TYPE_CONVERSION_UNSATISFIABLE_DOUBLE_TO_STRING("type(string(1.5)) == int"),
    EMPTY_MAP_SIZE_NOT_ZERO("size({}) != 0"),
    TIMESTAMP_INEQUALITY_CONTRADICTION(
        "timestamp('2023-01-01T00:00:00Z') != timestamp('2023-01-01T00:00:00Z')");

    final String expr;

    IsUnsatisfiableTestCase(String expr) {
      this.expr = expr;
    }
  }

  @Test
  public void isSatisfiable_failure(@TestParameter IsUnsatisfiableTestCase testCase)
      throws Exception {
    CelAbstractSyntaxTree ast = CEL.compile(testCase.expr).getAst();

    CelVerificationResult result = VERIFIER.isSatisfiable(ast);

    assertThat(result.status()).isEqualTo(VerificationStatus.VIOLATED);
    assertThat(result.message()).isEqualTo("Condition is not satisfiable.");
  }

  @Test
  public void isSatisfiable_timeout_throwsException() throws Exception {
    CelVerifier verifier = CelVerifierZ3Impl.newBuilder().setTimeout(Duration.ofMillis(1)).build();
    String expr = "int_list.all(x, int_list.all(y, int_list.all(z, x + y + z > 0)))";
    CelAbstractSyntaxTree ast = CEL.compile(expr).getAst();

    CelVerificationException e =
        assertThrows(CelVerificationException.class, () -> verifier.isSatisfiable(ast));
    assertThat(e).hasMessageThat().contains("timed out");
  }

  private enum IsAlwaysTrueTestCase {
    LOGICAL_OR_CONSTANTS("true || false"),
    CYCLIC_MACRO_SHADOWING_SAFETY("[1].all(x, [x].all(x, x == 1))"),
    TAUTOLOGY("x > 5 || x <= 5"),
    LIST_VARIABLE_CONSTRAINED("1 in int_list || !(1 in int_list)"),
    LIST_CONCATENATION("[1, 2, 3] == [1] + [2] + [3]"),
    LIST_CONTAINS_LIST("[1] in [[1]]"),
    UINT_MIN_RANGE("u >= 0u"),
    UINT_ARITHMETIC_ZERO("0u + 0u == 0u"),
    MAP_COMPREHENSION("{1: 2, 3: 4}.all(k, k > 0)"),
    NESTED_COMPREHENSIONS("[1, 2].all(x, [3, 4].all(y, x < y || y <= x))"),
    COMPREHENSION_EXISTS_UNKNOWN_INITIAL_STEP("[1, 2].exists(x, x == 1 ? unknown_var > 0 : true)"),
    CYCLIC_BIND_DOES_NOT_HANG("cel.bind(x, x, x) == x"),
    CEL_BIND_SHADOWING("cel.bind(x, 1, cel.bind(x, 2, x) + x) == 3"),
    CEL_BIND_TO_TRUE("cel.bind(x, true, !x) == false"),
    CEL_BIND_TO_FALSE("cel.bind(x, false, !x) == true"),
    STRING_TAUTOLOGY("role == role"),
    BYTES_TAUTOLOGY("by == by"),
    STRING_CONCATENATION("'a' + 'b' == 'ab'"),
    BYTES_CONCATENATION("b'a' + b'b' == b'ab'"),
    STRING_COMPARISON("'a' < 'b' || 'a' >= 'b'"),
    LIST_SIZE("size([1, 2]) == 2"),
    STRING_SIZE("size('abc') == 3"),
    BYTES_SIZE("size(b'abc') == 3"),
    CROSS_NUMERIC_EQUALITY_INT_DYN_TAUTOLOGY("1 == request || true"),
    MAP_SIZE("size({'a': 1, 'b': 2}) == 2"),
    EMPTY_LIST_SIZE("size([]) == 0"),
    EMPTY_MAP_SIZE("size({}) == 0"),
    MAP_LITERAL_DUPLICATE_KEYS("size({'a': 1, 'a': 2}) == 1"),
    LIST_CONCATENATION_SIZE("size(int_list + [1]) == size(int_list) + 1"),
    STRING_CONCATENATION_SIZE("size(role + 'a') == size(role) + 1"),
    BYTES_CONCATENATION_SIZE("size(by + b'a') == size(by) + 1"),
    LIST_SIZE_NON_NEGATIVE("size(int_list) >= 0"),
    STRING_SIZE_NON_NEGATIVE("size(role) >= 0"),
    MAP_SIZE_NON_NEGATIVE("size(string_int_map) >= 0"),
    DYNAMIC_MAP_DUPLICATE_KEYS("!(x == y) || size({x: 1, y: 2}) == 1"),
    MEMBER_LIST_SIZE("[1, 2].size() == 2"),
    MEMBER_STRING_SIZE("'abc'.size() == 3"),
    MEMBER_BYTES_SIZE("b'abc'.size() == 3"),
    MEMBER_MAP_SIZE("{'a': 1, 'b': 2}.size() == 2"),
    MAP_LOOKUP_EQUALITY("!(x == y) || {x: 1, 'b': 2}[x] == {y: 1, 'b': 2}[y]"),
    MAP_INSERT_SIZE(
        "dyn_map == {'a': 1, 'b': 2} ? size(dyn_map.transformMap(k, v, v > 1, v * 2)) == 1 : true"),
    UNSET_MAP_FIELD_SIZE("TestAllTypes{}.map_int64_int64.size() == 0"),
    STRING_CONTAINS_SELF("role.contains(role)"),
    STRING_STARTS_WITH_SELF("role.startsWith(role)"),
    STRING_ENDS_WITH_SELF("role.endsWith(role)"),
    STRING_CONTAINS_SUBSTRING("'abcdef'.contains('bcd')"),
    STRING_STARTS_WITH_PREFIX("'abcdef'.startsWith('abc')"),
    STRING_ENDS_WITH_SUFFIX("'abcdef'.endsWith('def')"),
    STRING_CONTAINS_FALSE("!'abcdef'.contains('xyz')"),
    STRING_STARTS_WITH_FALSE("!'abcdef'.startsWith('xyz')"),
    STRING_ENDS_WITH_FALSE("!'abcdef'.endsWith('xyz')"),
    BYTES_COMPARISON("b'a' < b'b' || b'a' >= b'b'"),
    UINT_MUL_ZERO("0u * 0u == 0u"),
    UINT_DIV("1u / 1u == 1u"),
    UINT_MOD("1u % 1u == 0u"),
    INT_MOD("1 % 1 == 0"),
    EXISTS_TRUE_COMPREHENSION("[1].exists(x, true) == true"),
    COMPREHENSION_EXACT_LIMIT("[1, 2, 3].all(x, x > 0) == true"),
    MAP_COMPREHENSION_EXACT_LIMIT("{1: 1, 2: 2, 3: 3}.all(k, k > 0) == true"),
    EMPTY_LIST_ALL("[].all(x, false)"),
    EMPTY_LIST_EXISTS("[].exists(x, true) == false"),
    EMPTY_LIST_MAP("[].map(x, true) == []"),
    EMPTY_LIST_FILTER("[].filter(x, true) == []"),
    COMPREHENSION_NON_STRICT_SHORT_CIRCUIT_ALL("[1, 0].all(x, 1 / x < 0) == false"),
    COMPREHENSION_NON_STRICT_SHORT_CIRCUIT_EXISTS("[1, 0].exists(x, 1 / x > 0) == true"),
    COMPREHENSION_SHADOWING_NON_STRICT_EXISTS(
        "cel.bind(x, 5, [2, 0].exists(x, x == 2 || 1 / x == 10) && x == 5)"),
    COMPREHENSION_SHADOWING_NON_STRICT_ALL(
        "cel.bind(x, 5, [0, 2].all(x, x != 0 && 1 / x == 0) == false && x == 5)"),
    MAP_NESTED_MACROS("{'a': x, 'b': y}.all(k, {'a': x, 'b': y}.exists(k2, k == k2))"),
    MAP_MACRO_ITER_VAR_NOT_REFERENCED(
        "{'a': x, 'b': y}.all(i, {'a': x, 'b': y}.exists(i, i == 'a' || i == 'b'))"),
    MAP_MACRO_SHADOWED_VARIABLE(
        "{'a': x, 'b': y}.all(z, {'a': x, 'b': y}.exists(z, z == 'a' || z == 'b'))"),
    MAP_LITERAL_VARIABLE_VALUE("{'a': x}['a'] == x"),
    HETEROGENEOUS_LARGE_UINT_INT_VARIABLE_NEQ(
        "unknown_var == " + CelZ3TypeSystem.MAX_UINT64 + "u ? unknown_var != -1 : true"),
    MAP_LITERAL_VARIABLE_KEY("x != y ? {x: 1, y: 2}[x] == 1 : true"),
    MAP_MACRO_LIST_RETURN("{'a': 1, 'b': 2}.map(x, x + 'a') == ['aa', 'ba']"),
    MAP_LITERAL_NESTED_LIST("{'a': [1, 2]} == {'a': [1, 2]}"),
    LIST_NESTED_LIST("[[1]] == [[1]]"),
    LIST_DEEPLY_NESTED_LIST("[[[1]]] == [[[1]]]"),
    MAP_DEEPLY_NESTED_MAP("{'a': {'b': {'c': 1}}} == {'a': {'b': {'c': 1}}}"),
    MAP_CONTAINS_LIST_OF_MAPS("{'a': [{'b': 1}]} == {'a': [{'b': 1}]}"),
    LIST_CONTAINS_MAP_OF_LISTS("[{'a': [1]}] == [{'a': [1]}]"),
    MAP_COMPREHENSION_NESTED_STRUCTURE("[1, 2].map(x, {'a': x}) == [{'a': 1}, {'a': 2}]"),
    HETEROGENEOUS_LIST_LITERAL("['string', 1, true] == ['string', 1, true]"),
    HETEROGENEOUS_NUMERIC_LIST_LITERAL("[1, 1.5] == [1.0, 1.5]"),
    HETEROGENEOUS_NUMERIC_LIST_LITERAL_NEQ("[1, 1.5] != [2.0, 1.5]"),
    CROSS_TYPE_NUMERIC_EQUALITY_INT_UINT("[x, u] == [u, x] || [x, u] != [u, x]"),
    CROSS_TYPE_NUMERIC_EQUALITY_INT_DOUBLE("[x, d] == [d, x] || [x, d] != [d, x]"),
    DYNAMIC_LIST_EQUALITY_CROSS_TYPE("dyn_var == [1] ? dyn_var == [1u] : true"),
    HETEROGENEOUS_MAP_LITERAL("{'key': 1, 'key2': 'string'} == {'key': 1, 'key2': 'string'}"),
    DYNAMIC_ELEMENT_LIST_EQUALITY("[port] == [port]"),
    DYNAMIC_ELEMENT_NESTED_LIST_EQUALITY("[[port]] == [[port]]"),
    STATIC_LIST_STATIC_INDEX("[1, 2][0] == 1"),
    ALL_MACRO_SHORT_CIRCUIT_EARLY("[1, 0].all(x, 1 / x == 0) == false"),
    TYPED_LIST_HOMOGENEOUS("(int_list + [1])[0] >= 0 || (int_list + [1])[0] < 0"),
    STATIC_NUMERIC_EQUALITY_INT("x == x"),
    STATIC_NUMERIC_EQUALITY_UINT("u == u"),
    DYNAMIC_MAP_EQUALITY("string_int_map == {'a': 1} || string_int_map != {'a': 1}"),
    DYNAMIC_LIST_VARIABLE_EQUALITY("dyn_list == dyn_list"),
    DYNAMIC_LIST_COMPREHENSION_ALL_SHORT_CIRCUITS_ERROR(
        "dyn_list == [false, 1] ? (dyn_list.all(x, x) == false) : true"),
    DYNAMIC_LIST_COMPREHENSION_EXISTS_SHORT_CIRCUITS_ERROR(
        "dyn_list == [true, 1] ? (dyn_list.exists(x, x) == true) : true"),
    DYNAMIC_LIST_COMPREHENSION_EMPTY_ALL_IS_TRUE(
        "dyn_list == [] ? dyn_list.all(x, false) == true : true"),
    DYNAMIC_LIST_COMPREHENSION_EMPTY_EXISTS_IS_FALSE(
        "dyn_list == [] ? dyn_list.exists(x, true) == false : true"),
    DYNAMIC_MAP_COMPREHENSION_HETEROGENEOUS_KEYS(
        "dyn_map == {'a': 1, 'b': 2} ? dyn_map.all(k, k != 1) : true"),
    DYNAMIC_LIST_VACUOUS_TRUTH_SWALLOWS_ERROR("dyn_list == [] ? dyn_list.all(x, 1/0 == 1) : true"),
    LARGE_NUMBER_OF_LIST_EQUALITIES(
        IntStream.range(0, 1000)
            .mapToObj(i -> String.format("[%d] == [%d]", i, i))
            .collect(joining(" && "))),
    DYNAMIC_MAP_KEY_EXTENSIONALITY(
        "dyn_map == {'a': 1, 'b': 2} ? dyn_map.all(k, k in {'a': 1, 'b': 2}) : true"),
    DYNAMIC_LIST_MAP_STANDARD("int_list == [1, 2, 3] ? int_list.map(x, x * 2) == [2, 4, 6] : true"),
    DYNAMIC_LIST_FILTER_STANDARD(
        "int_list == [1, 2, 3] ? int_list.filter(x, x % 2 != 0) == [1, 3] : true"),
    DYNAMIC_LIST_MACRO_CHAINING(
        "int_list == [1, 2, 3] ? int_list.filter(x, x > 1).map(y, y * 10) == [20, 30] : true"),
    DYNAMIC_LIST_MAP_NESTED_LISTS(
        "int_list == [1, 2] ? int_list.map(x, [x, x]) == [[1, 1], [2, 2]] : true"),
    DYNAMIC_LIST_MACRO_EMPTY_IDENTITY(
        "int_list == [] ? int_list.map(x, x * 2) == [] && int_list.filter(x, true) == [] : true"),
    DYNAMIC_LIST_NESTED_SHADOWING_COLLISION(
        "int_list == [1, 2] ? int_list.map(x, int_list.filter(x, x > 1)) == [[2], [2]] : true"),
    DYNAMIC_LIST_FILTER_TO_EMPTY("int_list == [1, 2, 3] ? int_list.filter(x, x > 10) == [] : true"),
    DYNAMIC_LIST_MAP_PRESERVES_ACCUMULATOR_OUT_OF_BOUNDS(
        "int_list == [99] ? int_list.map(x, x + 1) == [100] : true"),
    DYNAMIC_LIST_MATRIX_GENERATION(
        "int_list == [1, 2] ? int_list.map(x, [x * 2]) == [[2], [4]] : true"),
    DIVIDE_TRUNCATED("-5 / 3 == -1"),
    MODULO_TRUNCATED("-5 % 3 == -2"),
    EXPLICIT_DEFAULT_READ("TestAllTypes{single_int32: 0}.single_int32 == 0"),
    STRUCT_EMPTY_MESSAGE_FIELD_INEQUALITY(
        "TestAllTypes{standalone_message: TestAllTypes.NestedMessage{}} != TestAllTypes{}"),
    IEEE_754_NAN_NEQ_ITSELF("(0.0 / 0.0) != (0.0 / 0.0)"),
    IEEE_754_NAN_RELATIONAL_OPS_FALSE("!((0.0 / 0.0) < 1.0 || (0.0 / 0.0) >= 1.0)"),
    IEEE_754_INFINITY_EQ("(1.0 / 0.0) == (2.0 / 0.0)"),
    IEEE_754_INFINITY_ADD_FINITE_EQ_INFINITY("(1.0 / 0.0) + 9999999.0 == (1.0 / 0.0)"),
    IEEE_754_INFINITY_GT_NEG_INFINITY("(1.0 / 0.0) > (-1.0 / 0.0)"),
    IEEE_754_INFINITY_NEQ_NEG_INFINITY("(1.0 / 0.0) != (1.0 / -0.0)"),
    IEEE_754_NEG_ZERO_EQ("-0.0 == 0.0"),
    OPTIONAL_NONE_EQ_NONE("optional.none() == optional.none()"),
    OPTIONAL_OF_EQ_OF("optional.of(1) == optional.of(1)"),
    OPTIONAL_OF_NEQ_NONE("optional.of(1) != optional.none()"),
    OPTIONAL_VALUE("optional.of('test').value() == 'test'"),
    OPTIONAL_HAS_VALUE_TRUE("optional.of(true).hasValue()"),
    OPTIONAL_HAS_VALUE_FALSE("!optional.none().hasValue()"),
    OPTIONAL_OR_VALUE_FALLBACK("optional.none().orValue(5) == 5"),
    OPTIONAL_OR_VALUE_PRIMARY("optional.of(5).orValue(10) == 5"),
    OPTIONAL_OR_FALLBACK("optional.none().or(optional.of(5)) == optional.of(5)"),
    OPTIONAL_OR_PRIMARY("optional.of(5).or(optional.none()) == optional.of(5)"),
    OPTIONAL_OF_EQ_OF_VAR("optional.of(x) == optional.of(x)"),
    OPTIONAL_OF_NEQ_OF_VAR("x != y ? optional.of(x) != optional.of(y) : true"),
    OPTIONAL_OR_VALUE_VAR("optional.of(x).orValue(y) == x"),
    OPTIONAL_OR_VALUE_FALLBACK_VAR("optional.none().orValue(x) == x"),
    OPTIONAL_OR_VAR("optional.of(x).or(optional.of(y)) == optional.of(x)"),
    OPTIONAL_OR_FALLBACK_VAR("optional.none().or(optional.of(x)) == optional.of(x)"),
    OPTIONAL_OR_NONE_IS_NONE("optional.none().or(optional.none()) == optional.none()"),
    OPTIONAL_VALUE_VAR("optional.of(x).value() == x"),
    OPTIONAL_HAS_VALUE_VAR("optional.of(x).hasValue()"),
    OPTIONAL_VAR_HAS_VALUE_IMPLIES_INT("opt_var.hasValue() ? type(opt_var.value()) == int : true"),

    IEEE_754_PROTO_NEG_ZERO_NEQ(
        "TestAllTypes{single_double: -0.0} != TestAllTypes{single_double: 0.0}"),
    IEEE_754_ROUND_NEAREST_TIES_TO_EVEN_DOWN("1.0 + 1.1102230246251565e-16 == 1.0"),
    IEEE_754_ROUND_NEAREST_TIES_TO_EVEN_UP(
        "1.0 + 2.220446049250313e-16 + 1.1102230246251565e-16 == 1.0 + 2.0 *"
            + " 2.220446049250313e-16"),
    IEEE_754_NEG_ZERO_IN_LIST("-0.0 in [0.0]"),
    IEEE_754_POS_ZERO_IN_LIST("0.0 in [-0.0]"),
    IEEE_754_NAN_IN_LIST_FALSE("!((0.0/0.0) in [1.0, (0.0/0.0)])"),
    STRING_IN_LIST("'b' in ['a', 'b', 'c']"),
    INT_IN_MAP("1 in {1: 2}"),
    MAP_MISSING_KEY("!(3 in {1: 'a', 2: 'b'})"),
    MAP_VARIABLE_CONSTRAINED("'a' in string_int_map || !('a' in string_int_map)"),
    NESTED_MAP_CONTAINS("1 in {'a': {1: 'b'}}['a']"),
    HETEROGENEOUS_INT_DOUBLE_LT("1 < 2.0"),
    HETEROGENEOUS_DOUBLE_INT_LT("1.0 < 2"),
    HETEROGENEOUS_UINT_DOUBLE_LT("1u < 2.0"),
    HETEROGENEOUS_DOUBLE_UINT_LT("1.0 < 2u"),
    HETEROGENEOUS_INT_UINT_LT("1 < 2u"),
    HETEROGENEOUS_UINT_INT_LT("1u < 2"),
    HETEROGENEOUS_INT_DOUBLE_GT("2 > 1.0"),
    HETEROGENEOUS_DOUBLE_INT_GT("2.0 > 1"),
    HETEROGENEOUS_UINT_DOUBLE_GT("2u > 1.0"),
    HETEROGENEOUS_DOUBLE_UINT_GT("2.0 > 1u"),
    HETEROGENEOUS_INT_UINT_GT("2 > 1u"),
    HETEROGENEOUS_UINT_INT_GT("2u > 1"),
    HETEROGENEOUS_INT_DOUBLE_LE("1 <= 2.0"),
    HETEROGENEOUS_DOUBLE_INT_LE("1.0 <= 2"),
    HETEROGENEOUS_UINT_DOUBLE_LE("1u <= 2.0"),
    HETEROGENEOUS_DOUBLE_UINT_LE("1.0 <= 2u"),
    HETEROGENEOUS_INT_UINT_LE("1 <= 2u"),
    HETEROGENEOUS_UINT_INT_LE("1u <= 2"),
    HETEROGENEOUS_INT_DOUBLE_GE("2 >= 1.0"),
    HETEROGENEOUS_DOUBLE_INT_GE("2.0 >= 1"),
    HETEROGENEOUS_UINT_DOUBLE_GE("2u >= 1.0"),
    HETEROGENEOUS_DOUBLE_UINT_GE("2.0 >= 1u"),
    HETEROGENEOUS_INT_UINT_GE("2 >= 1u"),
    HETEROGENEOUS_UINT_INT_GE("2u >= 1"),
    HETEROGENEOUS_INT_DOUBLE_PRECISION("9007199254740993 > 9007199254740992.0"),
    HETEROGENEOUS_INT_EQ_DOUBLE(
        "type(dyn_var) == int && type(dyn_var2) == double && dyn_var == 1 && dyn_var2 == 1.0 ?"
            + " dyn_var == dyn_var2 : true"),
    HETEROGENEOUS_INT_NEQ_DOUBLE(
        "type(dyn_var) == int && type(dyn_var2) == double && dyn_var == 1 && dyn_var2 == 1.5 ?"
            + " dyn_var != dyn_var2 : true"),
    HETEROGENEOUS_INF_VS_INT("dyn_var == 9223372036854775807 ? dyn_var != 1.0 / 0.0 : true"),
    HETEROGENEOUS_NAN_VS_INT("dyn_var == 1 ? dyn_var != 0.0 / 0.0 : true"),
    HETEROGENEOUS_INT_UINT_VARIABLE_EQ("unknown_var == 1u ? unknown_var == 1 : true"),
    HETEROGENEOUS_INT_UINT_VARIABLE_VARIABLE_EQ(
        "unknown_var == u && x == 1 && u == 1u ? unknown_var == x : true"),
    HETEROGENEOUS_UINT_INT_VARIABLE_VARIABLE_EQ(
        "unknown_var == x && u == 1u && x == 1 ? unknown_var == u : true"),
    HETEROGENEOUS_INT_UINT_VARIABLE_NEQ("unknown_var == 2u ? unknown_var != 1 : true"),
    HETEROGENEOUS_DOUBLE_INT_OVERFLOW(
        "unknown_var == 9223372036854775807 ? unknown_var != 1e100 : true"),
    HETEROGENEOUS_MAX_EXACT_INT("dyn(9007199254740992) == 9007199254740992.0"),
    HETEROGENEOUS_MIN_EXACT_INT("dyn(-9007199254740992) == -9007199254740992.0"),
    HETEROGENEOUS_INT_PRECISION_LOSS_POS("dyn(9007199254740993) != 9007199254740992.0"),
    HETEROGENEOUS_INT_PRECISION_LOSS_NEG("dyn(-9007199254740993) != -9007199254740992.0"),
    HETEROGENEOUS_UINT_PRECISION_LOSS("dyn(9007199254740993u) != 9007199254740992.0"),
    HETEROGENEOUS_LONG_MAX_VS_DOUBLE("dyn(9223372036854775807) == 9223372036854775808.0"),
    HETEROGENEOUS_LONG_MIN_VS_DOUBLE("dyn(-9223372036854775808) == -9223372036854775808.0"),
    HETEROGENEOUS_UINT_MAX_VS_DOUBLE("dyn(18446744073709551615u) != 18446744073709551616.0"),
    HETEROGENEOUS_DYNAMIC_PRECISION(
        "type(dyn_var) == int && type(dyn_var2) == double && dyn_var == 9007199254740993 &&"
            + " dyn_var2 == 9007199254740992.0 ? dyn_var != dyn_var2 : true"),
    HETEROGENEOUS_DYNAMIC_TRANSITIVITY(
        "type(dyn_var) == int && type(dyn_var2) == double && dyn_var == 1 && dyn_var2 == 1.0 ?"
            + " dyn_var == dyn_var2 : true"),
    HETEROGENEOUS_DYNAMIC_ZERO("type(dyn_var) == double && dyn_var == -0.0 ? dyn_var == 0 : true"),
    HETEROGENEOUS_MAP_INT_KEY_DOUBLE_LOOKUP("{1: 'a'}[dyn(1.0)] == 'a'"),
    HETEROGENEOUS_MAP_DOUBLE_KEY_INT_LOOKUP("{1.0: 'a'}[dyn(1)] == 'a'"),
    HETEROGENEOUS_MAP_DOUBLE_KEY_UINT_LOOKUP("{1.0: 'a'}[dyn(1u)] == 'a'"),
    HETEROGENEOUS_MAP_UINT_KEY_DOUBLE_LOOKUP("{1u: 'a'}[dyn(1.0)] == 'a'"),
    HETEROGENEOUS_MAP_UINT_KEY_INT_LOOKUP_ZERO("{0u: 'a'}[dyn(0)] == 'a'"),
    HETEROGENEOUS_MAP_UINT_KEY_DOUBLE_LOOKUP_ZERO("{0u: 'a'}[dyn(0.0)] == 'a'"),
    HETEROGENEOUS_MAP_DOUBLE_KEY_UINT_LOOKUP_ZERO("{0.0: 'a'}[dyn(0u)] == 'a'"),
    HETEROGENEOUS_MAP_INT_KEY_UINT_LOOKUP_ZERO("{0: 'a'}[dyn(0u)] == 'a'"),
    HETEROGENEOUS_MAP_PRECISION_MISS(
        "{9007199254740993: 'exact', 9007199254740992: 'rounded'}[dyn(9007199254740992.0)] =="
            + " 'rounded'"),
    DYNAMIC_LIST_RESOLVES_QUANTIFIER_LOOPS(
        "int_list == [1] ? !(int_list.all(x, int_list.exists(y, y == x + 1))) : true"),
    DYNAMIC_LIST_RESOLVES_PIGEONHOLE(
        "int_list == [x, y, port] && int_list.all(v, v == 1) ? (x == 1 && y == 1 && port == 1)"
            + " : true"),
    DYNAMIC_LIST_RESOLVES_CORRELATED_NESTING(
        "int_list == [1, 2] && int_list_2 == [2, 3] ? int_list.exists(x, int_list_2.exists(y, x"
            + " == y)) : true"),
    DYNAMIC_MAP_EXISTS(
        "string_int_map == {'a': 1, 'b': 2} ? string_int_map.exists(k, string_int_map[k] == 2)"
            + " : true"),
    DYNAMIC_MAP_ALL("string_int_map == {'a': 1, 'b': 2} ? string_int_map.all(k, k != 'c') : true"),
    DYNAMIC_MAP_MAP("string_int_map == {'a': 1} ? string_int_map.map(k, k) == ['a'] : true"),
    DYNAMIC_MAP_FILTER(
        "string_int_map == {'a': 1, 'b': 2} ? string_int_map.filter(k, k == 'a') == ['a'] : true"),
    DYNAMIC_LIST_EXISTS_ONE("int_list == [1, 2, 3] ? int_list.exists_one(x, x == 2) : true"),
    DYNAMIC_MAP_EXISTS_ONE(
        "string_int_map == {'a': 1, 'b': 2} ? string_int_map.exists_one(k, k == 'a') : true"),
    DYNAMIC_LIST_COMPREHENSION_ITE_SHORT_CIRCUIT_ERROR(
        "dyn_list == [1, 2] ? !(dyn_list.all(x, x == 1 ? (1/0 == 0) : false)) : true"),
    DYNAMIC_MAP_KEYS_EQUIVALENCE(
        "string_int_map == {'a': 1} ? string_int_map.map(k, k) == ['a'] : true"),
    UINT_SUBTRACT("3u - 2u == 1u"),
    DOUBLE_SUBTRACT("3.0 - 2.0 == 1.0"),
    DYNAMIC_LIST_V2_ALL("int_list == [1, 2] ? int_list.all(i, v, v > 0 && i >= 0) : true"),
    DYNAMIC_LIST_V2_EXISTS("int_list == [1, 2] ? int_list.exists(i, v, i == 0 && v == 1) : true"),
    DYNAMIC_MAP_V2_ALL(
        "string_int_map == {'a': 1} ? string_int_map.all(k, v, k == 'a' && v == 1) : true"),
    DYNAMIC_MAP_V2_EXISTS(
        "string_int_map == {'a': 1, 'b': 2} ? string_int_map.exists(k, v, k == 'b' && v == 2) :"
            + " true"),
    LITERAL_LIST_V2_ALL("[1, 2].all(i, v, i >= 0 && v > 0)"),
    LITERAL_MAP_V2_EXISTS("{'a': 1, 'b': 2}.exists(k, v, k == 'b' && v == 2)"),
    LITERAL_LIST_TRANSFORM_LIST_V2_FILTER("[1, 2, 3].transformList(i, v, i < 2, v * 2) == [2, 4]"),
    LITERAL_MAP_TRANSFORM_MAP_V2_FILTER(
        "{'a': 1, 'b': 2}.transformMap(k, v, v > 1, v * 2) == {'b': 4}"),
    TWO_VAR_HETEROGENEOUS_MAP(
        "dyn_map == {'a': 1, 1: 'b'} ? dyn_map.exists(k, v, type(k) == string && type(v) == int &&"
            + " k == 'a' && v == 1) : true"),
    TYPE_AXIOM_BOOL("type(true) == bool"),
    NULLABLE_INT_IS_NULL_OR_INT("type(nullable_int) == int || type(nullable_int) == null_type"),
    TYPE_AXIOM_INT("type(1) == int"),
    TYPE_AXIOM_UINT("type(1u) == uint"),
    TYPE_AXIOM_DOUBLE("type(1.0) == double"),
    TYPE_AXIOM_STRING("type('a') == string"),
    TYPE_AXIOM_BYTES("type(b'a') == bytes"),
    TYPE_AXIOM_LIST("type([1]) == list"),
    TYPE_AXIOM_MAP("type({'a': 1}) == map"),
    TYPE_CONVERSION_INT_IDENTITY("int(1) == 1"),
    TYPE_CONVERSION_UINT_IDENTITY("uint(1u) == 1u"),
    TYPE_CONVERSION_DOUBLE_IDENTITY("double(1.0) == 1.0"),
    TYPE_CONVERSION_STRING_IDENTITY("string('a') == 'a'"),
    TYPE_CONVERSION_BYTES_IDENTITY("bytes(b'a') == b'a'"),
    TYPE_CONVERSION_BOOL_IDENTITY("bool(true) == true"),
    TYPE_CONVERSION_DYN_IDENTITY("dyn(1) == 1"),
    TYPE_CONVERSION_UINT_TO_INT("int(1u) == 1"),
    TYPE_CONVERSION_INT_TO_UINT("uint(1) == 1u"),
    TYPE_CONVERSION_INT_FROM_DOUBLE("int(1.0) == int(1.0)"),
    TYPE_CONVERSION_INT_FROM_STRING("int('1') == int('1')"),
    TYPE_CONVERSION_INT_FROM_TIMESTAMP(
        "int(timestamp('1970-01-01T00:00:00Z')) == int(timestamp('1970-01-01T00:00:00Z'))"),
    TYPE_CONVERSION_UINT_FROM_DOUBLE("uint(1.0) == uint(1.0)"),
    TYPE_CONVERSION_UINT_FROM_STRING("uint('1') == uint('1')"),
    TYPE_CONVERSION_DOUBLE_FROM_INT("double(1) == double(1)"),
    TYPE_CONVERSION_DOUBLE_FROM_UINT("double(1u) == double(1u)"),
    TYPE_CONVERSION_DOUBLE_FROM_STRING("double('1.0') == double('1.0')"),
    TYPE_CONVERSION_STRING_FROM_INT("string(1) == string(1)"),
    TYPE_CONVERSION_STRING_FROM_UINT("string(1u) == string(1u)"),
    TYPE_CONVERSION_STRING_FROM_DOUBLE("string(1.0) == string(1.0)"),
    TYPE_CONVERSION_STRING_FROM_BOOL("string(true) == string(true)"),
    TYPE_CONVERSION_STRING_FROM_BYTES("string(b'foo') == string(b'foo')"),
    TYPE_CONVERSION_STRING_FROM_TIMESTAMP(
        "string(timestamp('1970-01-01T00:00:00Z')) == string(timestamp('1970-01-01T00:00:00Z'))"),
    TYPE_CONVERSION_STRING_FROM_DURATION("string(duration('1s')) == string(duration('1s'))"),
    TYPE_CONVERSION_BYTES_FROM_STRING("bytes('foo') == bytes('foo')"),
    TYPE_CONVERSION_DURATION_FROM_STRING("duration('1s') == duration('1s')"),
    TYPE_CONVERSION_TIMESTAMP_FROM_STRING(
        "timestamp('1970-01-01T00:00:00Z') == timestamp('1970-01-01T00:00:00Z')"),
    TYPE_CONVERSION_TIMESTAMP_FROM_INT("timestamp(1) == timestamp(1)"),
    TYPE_CONVERSION_BOOL_FROM_STRING("bool('true') == bool('true')"),
    TYPE_CONVERSION_INT_TO_UINT_ZERO("uint(0) == 0u"),

    TYPE_AXIOM_OPTIONAL("type(optional.of(1)) == optional_type"),
    TYPE_AXIOM_STRUCT("type(TestAllTypes{}) == type(TestAllTypes{})"),
    TWO_VAR_TRANSFORM_MAP_DYNAMIC(
        "dyn_map == {'a': 1, 'b': 2} ? dyn_map.transformMap(k, v, v > 1, v * 2) == {'b': 4} :"
            + " true"),
    NULL_EQ_NULL("null == null"),
    NULL_NEQ_DYN("request == 1 ? request != null : true"),
    TYPE_AXIOM_NULL("type(null) == null_type"),
    WRAPPER_UNSET_IS_NULL("TestAllTypes{}.single_int64_wrapper == null"),
    WRAPPER_SET_NULL_IS_NULL(
        "TestAllTypes{single_int64_wrapper: null}.single_int64_wrapper == null"),
    WRAPPER_SET_NULL_EQ_UNSET("TestAllTypes{single_int64_wrapper: null} == TestAllTypes{}"),
    WRAPPER_SET_NON_NULL_EQ("TestAllTypes{single_int64_wrapper: 123}.single_int64_wrapper == 123"),
    STRING_CONTAINS_EMPTY("role.contains('')"),
    STRING_STARTS_WITH_EMPTY("role.startsWith('')"),
    STRING_ENDS_WITH_EMPTY("role.endsWith('')"),
    STRING_CONTAINS_CONCAT_LEFT("('prefix_' + role).contains(role)"),
    STRING_CONTAINS_CONCAT_RIGHT("(role + '_suffix').contains(role)"),
    STRING_CONTAINS_CONCAT_BOTH("('prefix_' + role + '_suffix').contains(role)"),
    STRING_STARTS_WITH_CONCAT("(role + '_suffix').startsWith(role)"),
    STRING_ENDS_WITH_CONCAT("('prefix_' + role).endsWith(role)"),
    STRING_CONTAINS_IMPLIES_SIZE("role.contains('abc') ? size(role) >= 3 : true"),
    STRING_STARTS_WITH_IMPLIES_SIZE("role.startsWith('abc') ? size(role) >= 3 : true"),
    STRING_ENDS_WITH_IMPLIES_SIZE("role.endsWith('abc') ? size(role) >= 3 : true"),
    STRING_CANNOT_CONTAIN_LONGER("!role.contains(role + 'a')"),
    STRING_CANNOT_START_WITH_LONGER("!role.startsWith(role + 'a')"),
    STRING_CANNOT_END_WITH_LONGER("!role.endsWith('a' + role)"),
    STRING_PREFIX_TRANSITIVITY(
        "role.startsWith(country) && country.startsWith('US') ? role.startsWith('US') : true"),
    IN_LIST_IEEE_754_POS_NEG_ZERO("dyn_list == [-0.0] ? 0.0 in dyn_list : true"),
    IN_LIST_IEEE_754_NEG_POS_ZERO("dyn_list == [0.0] ? -0.0 in dyn_list : true"),
    IN_LIST_IEEE_754_NAN_NEQ("dyn_list == [0.0/0.0] ? !(0.0/0.0 in dyn_list) : true"),
    DYNAMIC_MAP_LOOKUP_IDENTITY(
        "1 in dyn_map && type(dyn_map[1]) == int ? dyn_map[1] == dyn_map[1] : true"),
    DYNAMIC_LIST_LOOKUP_IDENTITY(
        "size(dyn_list) > 1 && type(dyn_list[1]) == int ? dyn_list[1] == dyn_list[1] : true"),
    DYNAMIC_NESTED_LIST_EQUALITY("dyn_var == [1, 2] ? [dyn_var] == [[1, 2]] : true"),
    DYNAMIC_NESTED_MAP_EQUALITY("dyn_var == {'a': 1} ? [dyn_var] == [{'a': 1}] : true"),
    DYNAMIC_INCLUSION_ENFORCES_EXTENSIONALITY(
        "type(dyn_var) == list && type(dyn_var2) == list && dyn_var == dyn_var2 "
            + "? dyn_var in [dyn_var2] "
            + ": true"),
    DYNAMIC_NESTED_LIST_EQUALITY_NEEDS_EXTENSIONALITY(
        "int_list == [x] && int_list_2 == [y] && x == y ? int_list == int_list_2 : true"),
    LIST_INDEX_TYPE_CONSTRAINT("size(int_list) > 15 ? type(int_list[15]) == int : true"),
    MAP_INDEX_TYPE_CONSTRAINT(
        "'key' in string_int_map ? type(string_int_map['key']) == int : true"),
    LITERAL_LIST_INDEX("[1, 2][0] == 1"),
    NESTED_LIST_VARIABLES_EQUALITY(
        "nested_list == [[1]] && nested_list_2 == [[1]] ? nested_list == nested_list_2 : true"),
    DYNAMIC_NUMERIC_EQUALITY_CROSS_TYPE_INT_DOUBLE(
        "type(dyn_var) == int && type(dyn_var2) == double && dyn_var == 1 && dyn_var2 == 1.0 ?"
            + " dyn_var == dyn_var2 : true"),
    DYNAMIC_NUMERIC_EQUALITY_CROSS_TYPE_DOUBLE_INT(
        "type(dyn_var) == double && type(dyn_var2) == int && dyn_var == 1.0 && dyn_var2 == 1 ?"
            + " dyn_var == dyn_var2 : true"),
    DYNAMIC_NUMERIC_EQUALITY_CROSS_TYPE_UINT_DOUBLE(
        "type(dyn_var) == uint && type(dyn_var2) == double && dyn_var == 1u && dyn_var2 == 1.0 ?"
            + " dyn_var == dyn_var2 : true"),
    DYNAMIC_EQUALITY_NON_NUMERIC_WITH_DYN(
        "role == 'admin' && dyn_var == 'admin' ? role == dyn_var : true"),
    DYNAMIC_EQUALITY_DYN_WITH_NON_NUMERIC(
        "dyn_var == 'admin' && role == 'admin' ? dyn_var == role : true"),
    DYNAMIC_NUMERIC_EQUALITY_CROSS_TYPE_DOUBLE_UINT(
        "type(dyn_var) == double && type(dyn_var2) == uint && dyn_var == 1.0 && dyn_var2 == 1u ?"
            + " dyn_var == dyn_var2 : true"),
    DYNAMIC_NUMERIC_EQUALITY_CROSS_TYPE_DYN_INT(
        "type(dyn_var) == int && dyn_var == 5 && x == 5 ? dyn_var == x : true"),
    DYNAMIC_NUMERIC_EQUALITY_CROSS_TYPE_DYN_UINT(
        "type(dyn_var) == uint && dyn_var == 5u && u == 5u ? dyn_var == u : true"),
    DYNAMIC_NUMERIC_EQUALITY_CROSS_TYPE_DYN_DOUBLE(
        "type(dyn_var) == double && dyn_var == 5.0 && dyn_var2 == 5.0 && type(dyn_var2) == double ?"
            + " dyn_var == dyn_var2 : true"),
    INT64_BOUNDS_ALWAYS_TRUE("x <= 9223372036854775807 && x >= -9223372036854775808"),
    UINT64_BOUNDS_ALWAYS_TRUE("u <= 18446744073709551615u && u >= 0u"),
    MODULO_INT64_MIN_INT_BY_NEG_ONE_ALWAYS_ZERO(
        "x == -9223372036854775808 && y == -1 ? x % y == 0 : true"),
    ;

    final String expr;

    IsAlwaysTrueTestCase(String expr) {
      this.expr = expr;
    }
  }

  @Test
  public void isAlwaysTrue_success(@TestParameter IsAlwaysTrueTestCase testCase) throws Exception {
    CelAbstractSyntaxTree ast = CEL.compile(testCase.expr).getAst();
    CelVerificationResult result = VERIFIER.isAlwaysTrue(ast);

    assertWithMessage(result.message())
        .that(result.status())
        .isEqualTo(VerificationStatus.VERIFIED);
  }

  @Test
  public void isAlwaysTrue_withUnknownIdentifier_evaluatesToUnknown(
      @TestParameter({
            "x == x",
            "[x] == [x]",
            "{1: x} == {1: x}",
          })
          String expression)
      throws Exception {
    CelAbstractSyntaxTree ast = CEL.compile(expression).getAst();

    CelVerifier verifierWithUnknown =
        CelVerifierFactory.newVerifier().addUnknownIdentifier("x").build();

    // 'x == x' is not a tautology if it can be unknown
    // i.e: CelUnknown == CelUnknown is unknown.
    CelVerificationResult result = verifierWithUnknown.isAlwaysTrue(ast);

    assertThat(result.status()).isEqualTo(VerificationStatus.VIOLATED);
    assertThat(result.message()).contains("x = Unknown");
  }

  @Test
  public void isAlwaysTrue_dynamicComprehensionNonBoolYieldsError() throws Exception {
    // Testing the path where a comprehension step successfully evaluates but yields a
    // non-boolean result. To bypass standard field access CEL errors, we constrain dyn_list to a
    // map containing an integer value.
    // 'x.not_a_bool' successfully evaluates to 123 (int). Since 'all' strictly expects a bool,
    // this yields a CEL Error. The entire ternary evaluates to Error, so isAlwaysTrue is false.
    String expr =
        "dyn_list == [{'not_a_bool': 123}] ? !(dyn_list.all(x, x.not_a_bool) == true ||"
            + " dyn_list.all(x, x.not_a_bool) == false) : true";
    CelAbstractSyntaxTree ast = CEL.compile(expr).getAst();
    CelVerificationResult result =
        CelVerifierFactory.newVerifier().setComprehensionUnrollLimit(1).build().isAlwaysTrue(ast);

    assertThat(result.status()).isEqualTo(VerificationStatus.VIOLATED);
    assertThat(result.message())
        .isEqualTo(
            "Condition is not always true. Counterexample input:\n"
                + "  dyn_list = [{\"not_a_bool\": 123}]");
  }

  @Test
  public void isAlwaysTrue_comprehensionExceedsMaxIterations_returnsUnknown() throws Exception {
    String expr = "int_list == [1, 2, 3] ? int_list.all(x, x > 0) : true";
    CelAbstractSyntaxTree ast = CEL.compile(expr).getAst();

    CelVerifier verifier = CelVerifierFactory.newVerifier().setComprehensionUnrollLimit(2).build();
    CelVerificationResult verifiedValue = verifier.isAlwaysTrue(ast);

    // Truncated loops return Unknown, which negates to Unknown.
    // Since it's not strictly TRUE, isAlwaysTrue returns false.
    assertThat(verifiedValue.status()).isEqualTo(VerificationStatus.INCONCLUSIVE);
  }

  @Test
  public void isAlwaysTrue_comprehensionZeroUnrollLimit_emptyList() throws Exception {
    // Tests that mkOrFlattened and mkAndFlattened gracefully handle empty iteration loops.
    // A limit of 0 means the loop body is never built.
    // For an empty list, length is 0, so isTruncated (0 > 0) is false.
    // Thus an empty list correctly evaluates to true for 'all' even with a 0 unroll limit.
    String expr = "int_list == [] ? int_list.all(x, x > 0) : true";
    CelAbstractSyntaxTree ast = CEL.compile(expr).getAst();

    CelVerifier verifier = CelVerifierFactory.newVerifier().setComprehensionUnrollLimit(0).build();
    CelVerificationResult verifiedValue = verifier.isAlwaysTrue(ast);

    assertThat(verifiedValue.status()).isEqualTo(VerificationStatus.VERIFIED);
  }

  @Test
  public void isAlwaysTrue_unpopulatedTypes_throwsIllegalArgumentException() throws Exception {
    CelAbstractSyntaxTree parsedAst = CEL.parse("1 == 1").getAst();

    IllegalArgumentException e =
        assertThrows(IllegalArgumentException.class, () -> VERIFIER.isAlwaysTrue(parsedAst));
    assertThat(e).hasMessageThat().contains("AST must be type-checked");
  }

  @Test
  public void isAlwaysTrue_strictCustomFunction_errorPropagates() throws Exception {
    Cel celWithCustomFunc =
        CelFactory.plannerCelBuilder()
            .setStandardMacros(CelStandardMacro.STANDARD_MACROS)
            .setContainer(CelContainer.ofName("cel.expr.conformance.proto3"))
            .addFunctionDeclarations(
                CelFunctionDecl.newFunctionDeclaration(
                    "custom_func",
                    CelOverloadDecl.newGlobalOverload(
                        "custom_func_overload", SimpleType.INT, SimpleType.INT)))
            .build();
    CelAbstractSyntaxTree ast =
        celWithCustomFunc.compile("custom_func(1 / 0) == 5 || !(custom_func(1 / 0) == 5)").getAst();

    CelVerificationResult result = VERIFIER.isAlwaysTrue(ast);

    // Should fail verification because the error propagates through custom_func,
    // so the whole expression evaluates to an error, not true.
    assertThat(result.status()).isEqualTo(VerificationStatus.VIOLATED);
    assertThat(result.message())
        .isEqualTo(
            "Condition is not always true. (The expression fails unconditionally, regardless of"
                + " input state)");
  }

  @Test
  public void verifyEquivalence_unknownPrecedenceOverError() throws Exception {
    Cel celWithCustomFunc =
        CelFactory.plannerCelBuilder()
            .setStandardMacros(CelStandardMacro.STANDARD_MACROS)
            .addVar("unknown_var", SimpleType.DYN)
            .addFunctionDeclarations(
                CelFunctionDecl.newFunctionDeclaration(
                    "custom_func",
                    CelOverloadDecl.newGlobalOverload(
                        "custom_func_overload", SimpleType.INT, SimpleType.INT, SimpleType.DYN)))
            .build();
    CelAbstractSyntaxTree astA =
        celWithCustomFunc.compile("custom_func(1 / 0, unknown_var)").getAst();
    CelAbstractSyntaxTree astB = celWithCustomFunc.compile("1 / 0").getAst();

    CelVerifier verifier =
        CelVerifierFactory.newVerifier().addUnknownIdentifier("unknown_var").build();

    CelVerificationResult result = verifier.verifyEquivalence(astA, astB);

    // Because Unknown has higher precedence than Error, custom_func evaluates to Unknown
    // when unknown_var is Unknown, whereas `1 / 0` evaluates to Error. Thus they are not
    // equivalent.
    assertThat(result.status()).isEqualTo(VerificationStatus.VIOLATED);
  }

  @Test
  public void isSatisfiable_approximateIterRangeInMap_inconclusive() throws Exception {
    Cel celWithCustomFunc =
        CelFactory.plannerCelBuilder()
            .setStandardMacros(CelStandardMacro.STANDARD_MACROS)
            .setContainer(CelContainer.ofName("cel.expr.conformance.proto3"))
            .addFunctionDeclarations(
                CelFunctionDecl.newFunctionDeclaration(
                    "approx_list",
                    CelOverloadDecl.newGlobalOverload(
                        "approx_list_overload", ListType.create(SimpleType.INT), SimpleType.INT)))
            .build();
    CelAbstractSyntaxTree ast =
        celWithCustomFunc.compile("approx_list(1).map(x, x * 2) == []").getAst();

    CelVerificationResult result = VERIFIER.isSatisfiable(ast);

    assertThat(result.status()).isEqualTo(VerificationStatus.INCONCLUSIVE);
  }

  @Test
  public void isSatisfiable_mapWithUnusedApproximateIteration_verified() throws Exception {
    Cel celWithCustomFunc =
        CelFactory.plannerCelBuilder()
            .setStandardMacros(CelStandardMacro.STANDARD_MACROS)
            .addVar("y", ListType.create(SimpleType.INT))
            .addFunctionDeclarations(
                CelFunctionDecl.newFunctionDeclaration(
                    "approx_func",
                    CelOverloadDecl.newGlobalOverload(
                        "approx_func_overload", SimpleType.INT, SimpleType.INT)))
            .build();
    CelAbstractSyntaxTree ast =
        celWithCustomFunc.compile("y == [] && y.map(x, approx_func(1)) == y").getAst();

    CelVerificationResult result = VERIFIER.isSatisfiable(ast);

    assertThat(result.status()).isEqualTo(VerificationStatus.VERIFIED);
  }

  private enum UnconditionalErrorTestCase {
    MAP_MISSING_KEY("{'a': 1}['b'] == 0 || !({'a': 1}['b'] == 0)"),
    COLLECTION_ERROR("{'a': 1 / 0} == {'a': 1 / 0}"),
    LIST_ERROR("[1 / 0] == [1 / 0]"),
    STRICT_LITERAL_ERROR("{'a': 1/0}.exists(k, k == 'a') == {'a': 1/0}.exists(k, k == 'a')"),
    STRICT_LITERAL_ERROR_KEY("{1/0: 'a'}.exists(k, k == 1) == {1/0: 'a'}.exists(k, k == 1)");

    final String expr;

    UnconditionalErrorTestCase(String expr) {
      this.expr = expr;
    }
  }

  @Test
  public void isAlwaysTrue_unconditionalError_failsVerification(
      @TestParameter UnconditionalErrorTestCase testCase) throws Exception {
    CelAbstractSyntaxTree ast = CEL.compile(testCase.expr).getAst();

    CelVerificationResult result = VERIFIER.isAlwaysTrue(ast);

    assertThat(result.status()).isEqualTo(VerificationStatus.VIOLATED);
    assertThat(result.message())
        .isEqualTo(
            "Condition is not always true. (The expression fails unconditionally, regardless of"
                + " input state)");
  }

  @Test
  public void verifyEquivalence_unconditionalError_failsVerification(
      @TestParameter UnconditionalErrorTestCase testCase) throws Exception {
    CelAbstractSyntaxTree astA = CEL.compile(testCase.expr).getAst();
    CelAbstractSyntaxTree astB = CEL.compile("true").getAst();

    CelVerificationResult result = VERIFIER.verifyEquivalence(astA, astB);

    assertThat(result.status()).isEqualTo(VerificationStatus.VIOLATED);
    assertThat(result.message())
        .isEqualTo(
            "Equivalence violation detected. (The expression fails unconditionally, regardless of"
                + " input state)");
  }

  @Test
  public void verifyEquivalence_dynamicMapEquality_enforcesExtensionalityOnPresentKeys()
      throws Exception {
    CelAbstractSyntaxTree astA = CEL.compile("string_int_map == {'a': 1}").getAst();
    CelAbstractSyntaxTree astB = CEL.compile("true").getAst();

    CelVerificationResult result = VERIFIER.verifyEquivalence(astA, astB);

    assertThat(result.status()).isEqualTo(VerificationStatus.VIOLATED);
    assertThat(result.message()).contains("string_int_map =");
    assertThat(result.message()).doesNotContain("string_int_map = {'a': 1}");
  }

  private enum MalformedAstTestCase {
    LOGICAL_AND(
        Operator.LOGICAL_AND,
        ImmutableList.of(
            CelExpr.newBuilder().setId(2).setConstant(CelConstant.ofValue(true)).build()),
        "expects at least 2 arguments, got 1"),
    NEGATE(Operator.NEGATE, ImmutableList.of(), "expects 1 argument, got 0"),
    CONDITIONAL(
        Operator.CONDITIONAL,
        ImmutableList.of(
            CelExpr.newBuilder().setId(2).setConstant(CelConstant.ofValue(true)).build(),
            CelExpr.newBuilder().setId(3).setConstant(CelConstant.ofValue(1L)).build()),
        "expects 3 arguments, got 2"),
    LESS(
        Operator.LESS,
        ImmutableList.of(
            CelExpr.newBuilder().setId(2).setConstant(CelConstant.ofValue(1L)).build()),
        "expects 2 arguments, got 1");

    final Operator operator;
    final ImmutableList<CelExpr> args;
    final String expectedError;

    MalformedAstTestCase(Operator operator, ImmutableList<CelExpr> args, String expectedError) {
      this.operator = operator;
      this.args = args;
      this.expectedError = expectedError;
    }
  }

  @Test
  public void isAlwaysTrue_malformedAst_throwsIllegalArgumentException(
      @TestParameter MalformedAstTestCase testCase) throws Exception {
    CelAbstractSyntaxTree validAst = CEL.compile("1 < 2").getAst();

    CelCall.Builder callBuilder = CelCall.newBuilder().setFunction(testCase.operator.getFunction());
    for (CelExpr arg : testCase.args) {
      callBuilder.addArgs(arg);
    }

    CelExpr malformedExpr = CelExpr.newBuilder().setId(1).setCall(callBuilder.build()).build();

    CelAbstractSyntaxTree malformedAst =
        CelAbstractSyntaxTree.newCheckedAst(
            malformedExpr, validAst.getSource(), validAst.getReferenceMap(), validAst.getTypeMap());

    IllegalArgumentException e =
        assertThrows(IllegalArgumentException.class, () -> VERIFIER.isAlwaysTrue(malformedAst));
    assertThat(e).hasMessageThat().contains(testCase.expectedError);
  }

  @Test
  public void verifyEquivalence_infinityConstants_notEquivalent() throws Exception {
    CelOptimizer optimizer =
        CelOptimizerFactory.standardCelOptimizerBuilder(CEL)
            .addAstOptimizers(ConstantFoldingOptimizer.getInstance())
            .build();
    CelAbstractSyntaxTree astA =
        optimizer.optimize(CEL.compile("d == double('Infinity')").getAst());
    CelAbstractSyntaxTree astB =
        optimizer.optimize(CEL.compile("d == double('-Infinity')").getAst());

    CelVerificationResult result = VERIFIER.verifyEquivalence(astA, astB);

    assertThat(result.status()).isEqualTo(VerificationStatus.VIOLATED);
  }

  private enum IsAlwaysTrueViolationTestCase {
    NOT_ALWAYS_TRUE(
        "x > 5", "Condition is not always true\\.", "Counterexample input:", "x = -?\\d+"),
    LAW_OF_EXCLUDED_MIDDLE_FAILS_WITH_ERRORS(
        "(1 / 0 == 5) || !(1 / 0 == 5)", "Condition is not always true\\."),
    INTEGER_OVERFLOW_FAILS_WITH_ERRORS(
        "(x + 1) - 1 == x",
        "Condition is not always true\\.",
        "Counterexample input:",
        "x = -?\\d+"),
    UINT_SUBTRACT_UNDERFLOW_FAILS_WITH_ERRORS(
        "(u - 1u) + 1u == u",
        "Condition is not always true\\.",
        "Counterexample input:",
        "u = \\d+u?"),
    NEGATE_MIN_INT_FAILS_WITH_ERRORS(
        "-(-x) == x", "Condition is not always true\\.", "Counterexample input:", "x = -?\\d+"),
    HETEROGENEOUS_ARITHMETIC_FAILS("dyn(1) + 1u == 2u", "Condition is not always true\\."),
    CROSS_TYPE_SYMBOLIC_EQUALITY_NOT_ALWAYS_UNEQUAL_INT_UINT(
        "dyn(x) != dyn(u)",
        "Condition is not always true\\.",
        "Counterexample input:",
        "x = -?\\d+",
        "u = \\d+u?"),
    CROSS_TYPE_SYMBOLIC_EQUALITY_NOT_ALWAYS_UNEQUAL_UINT_INT(
        "dyn(u) != dyn(x)",
        "Condition is not always true\\.",
        "Counterexample input:",
        "x = -?\\d+",
        "u = \\d+u?"),
    CROSS_TYPE_DYNAMIC_EQUALITY_NOT_ALWAYS_UNEQUAL_INT_DOUBLE(
        "!(request == unknown_var && type(request) == int && type(unknown_var) == double)",
        "Condition is not always true\\.",
        "Counterexample input:",
        "unknown_var = -?\\d+\\.\\d+",
        "request = -?\\d+"),
    OPTIONAL_DYN_VAR_HAS_VALUE_NOT_IMPLIES_INT(
        "opt_dyn_var.hasValue() ? type(opt_dyn_var.value()) == int : true",
        "Condition is not always true\\.",
        "Counterexample input:",
        "opt_dyn_var = optional\\(Unknown\\)"),
    OPTIONAL_ENTRY_DYN_VAR_TYPE_MISMATCH(
        "[?dyn_var] == [?dyn_var] ? true : true",
        "Condition is not always true\\.",
        "Counterexample input:",
        "dyn_var = b\"![01]!\""),
    OPTIONAL_MAP_ENTRY_DYN_VAR_TYPE_MISMATCH(
        "{?1: dyn_var} == {?1: dyn_var} ? true : true",
        "Condition is not always true\\.",
        "Counterexample input:",
        "dyn_var = b\"![01]!\""),
    OPTIONAL_STRUCT_ENTRY_DYN_VAR_TYPE_MISMATCH(
        "cel.expr.conformance.proto3.TestAllTypes{?single_int32: dyn_var} =="
            + " cel.expr.conformance.proto3.TestAllTypes{?single_int32: dyn_var} ? true : true",
        "Condition is not always true\\.",
        "Counterexample input:",
        "dyn_var = b\"(!0!|i)\""),
    OPTIONAL_NONE_COUNTEREXAMPLE(
        "opt_dyn_var.hasValue()",
        "Condition is not always true\\.",
        "Counterexample input:",
        "opt_dyn_var = optional\\.none\\(\\)"),
    DYNAMIC_MAP_ALL_VIOLATION(
        "string_int_map == {'a': 1, 'b': 2} ? string_int_map.all(k, k == 'a') : true",
        "Condition is not always true\\.",
        "Counterexample input:",
        "string_int_map = \\{",
        "\"a\": 1",
        "\"b\": 2"),
    DYNAMIC_MAP_EXISTS_VIOLATION(
        "string_int_map == {'a': 1, 'b': 2} ? string_int_map.exists(k, k == 'c') : true",
        "Condition is not always true\\.",
        "Counterexample input:",
        "string_int_map = \\{",
        "\"a\": 1",
        "\"b\": 2"),
    LITERAL_MAP_KEY_ERROR_PROPAGATES(
        "{1/0: 1}.all(k, true) == true",
        "Condition is not always true\\.",
        "\\(The expression fails unconditionally, regardless of input state\\)"),
    DYNAMIC_LIST_EXISTS_ONE_UNKNOWN_MATH(
        "int_list == [1, 2] ? int_list.exists_one(x, x == 1 || unknown_var) : true",
        "Condition is not always true\\.",
        "Counterexample input:",
        "unknown_var = (true|false|\\d+)",
        "int_list = \\[1, 2\\]"),
    DYNAMIC_LIST_EXISTS_ONE_UNKNOWN_POISONING(
        "int_list == [1, 2] ? !(int_list.exists_one(x, x == 1 || unknown_var) == true ||"
            + " int_list.exists_one(x, x == 1 || unknown_var) == false) : true",
        "Condition is not always true\\.",
        "Counterexample input:",
        "unknown_var = (true|false)",
        "int_list = \\[1, 2\\]"),
    DYNAMIC_ITERATION_OVER_SCALAR_RETURNS_UNKNOWN(
        "unknown_var == 1 ? !(unknown_var.all(x, false) == true || unknown_var.all(x, false) =="
            + " false) : true",
        "Condition is not always true\\.",
        "Counterexample input:",
        "unknown_var = 1"),
    ERROR_UNKNOWN_PRECEDENCE(
        "[1, 2].all(x, x == 1 ? unknown_var : 1/0 == 0) == true",
        "Condition is not always true\\.",
        "Counterexample input:",
        "unknown_var = (true|false)"),
    LIST_LITERAL_ELEMENT_ERROR_PROPAGATES(
        "[true, 1/0].exists(x, x) == true",
        "Condition is not always true\\.",
        "\\(The expression fails unconditionally, regardless of input state\\)"),
    DYNAMIC_STRING_MACRO_TYPE_MISMATCH(
        "unknown_var == 1 ? !(unknown_var.contains('a') == true || unknown_var.contains('a') =="
            + " false) : true",
        "Condition is not always true\\.",
        "Counterexample input:",
        "unknown_var = \\d+u?"),
    STRING_CONTAINS_IS_NOT_EQUALITY(
        "role.contains('admin') ? role == 'admin' : true",
        "Condition is not always true\\.",
        "Counterexample input:",
        "role = \".*admin.*\""),
    STRING_OVERLAP_FALLACY(
        "role.startsWith('A') && role.endsWith('B') ? role == 'AB' : true",
        "Condition is not always true\\.",
        "Counterexample input:",
        "role = \"A.*B\""),
    TYPE_CONVERSION_INT_TO_UINT_UNDERFLOW_ERROR(
        "uint(-1) == 1u",
        "Condition is not always true\\.",
        "\\(The expression fails unconditionally, regardless of input state\\)"),
    TYPE_CONVERSION_UINT_TO_INT_OVERFLOW_ERROR(
        "int(9223372036854775808u) == 1",
        "Condition is not always true\\.",
        "\\(The expression fails unconditionally, regardless of input state\\)"),
    STRING_STARTS_VS_ENDS_WITH(
        "role.startsWith('admin') == role.endsWith('admin')",
        "Condition is not always true\\.",
        "Counterexample input:",
        "role = \"(admin.*|.*admin)\""),
    STRING_CONTAINS_VS_STARTS_WITH(
        "role.contains('admin') == role.startsWith('admin')",
        "Condition is not always true\\.",
        "Counterexample input:",
        "role = \".*admin.*\""),
    DYNAMIC_MAP_INDEX_COMPUTATION_VIOLATION(
        "type(dyn_map[1 + 1]) == list && size(dyn_map[1 + 1]) == 0 "
            + "? dyn_map[1 + 1] == [] : true",
        "Condition is not always true\\.",
        "Counterexample input:",
        "dyn_map = \\{\\}"),
    DYNAMIC_MAP_COMPREHENSION_NESTED_EQUALITY_VIOLATION(
        "cel.bind(r, request, r.l == [[1], [2], [3], [4], [5]] && r.m == {1: [1], 2: [2],"
            + " 3: [3]} ? r.l.all(x, r.m.exists(k, r.m[k] == x)) : true)",
        "Condition is not always true\\.",
        "Counterexample input:",
        "request = .*"),
    UNINTERPRETED_EQUALITY_VIOLATION(
        "request == request",
        "Condition is not always true\\.",
        "Counterexample input:",
        "request = NaN"),
    CROSS_TYPE_NUMERIC_EQUALITY_APPROXIMATION_VIOLATION(
        "dyn_var == 1.0",
        "Condition is not always true\\.",
        "Counterexample input:",
        "dyn_var = b\"![01]!\""),
    DYNAMIC_NOT_TYPE_MISMATCH(
        "!dyn_var",
        "Condition is not always true\\.",
        "Counterexample input:",
        "dyn_var = (true|false)"),
    DYNAMIC_CONDITIONAL_TYPE_MISMATCH(
        "dyn_var ? true : false",
        "Condition is not always true\\.",
        "Counterexample input:",
        "dyn_var = b\"![01]!\""),
    DYNAMIC_NOT_TYPE_MISMATCH_SURVIVOR(
        "type(dyn_var) == int ? (!dyn_var == !dyn_var) : true",
        "Condition is not always true\\.",
        "Counterexample input:",
        "dyn_var = int\\{\\}"),
    DYNAMIC_CONDITIONAL_TYPE_MISMATCH_SURVIVOR(
        "type(dyn_var) == int ? (dyn_var ? true : false) == (dyn_var ? true : false) : true",
        "Condition is not always true\\.",
        "Counterexample input:",
        "dyn_var = int\\{\\}"),
    ADD_INT64_OVERFLOW_FAILS_WITH_ERRORS(
        "x > 0 && y > 0 ? x + y > x : true",
        "Condition is not always true\\.",
        "Counterexample input:",
        "x = (1|9223372036854775807)",
        "y = (1|9223372036854775807)"),
    ADD_UINT64_OVERFLOW_FAILS_WITH_ERRORS(
        "u1 > 0u && u2 > 0u ? u1 + u2 >= u1 : true",
        "Condition is not always true\\.",
        "Counterexample input:",
        "u1 = (1u|18446744073709551615u)",
        "u2 = (1u|18446744073709551615u)"),
    SUBTRACT_INT64_UNDERFLOW_FAILS_WITH_ERRORS(
        "x < 0 && y > 0 ? x - y < x : true",
        "Condition is not always true\\.",
        "Counterexample input:",
        "x = (-2|9223372036854775807)",
        "y = (-2|9223372036854775807)"),
    MULTIPLY_INT64_OVERFLOW_FAILS_WITH_ERRORS(
        "x > 1000000000 && y > 1000000000 ? x * y > 0 : true",
        "Condition is not always true\\.",
        "Counterexample input:",
        "x = [0-9]+",
        "y = [0-9]+"),
    MULTIPLY_UINT64_OVERFLOW_FAILS_WITH_ERRORS(
        "u1 > 1000000000u && u2 > 1000000000u ? u1 * u2 > 0u : true",
        "Condition is not always true\\.",
        "Counterexample input:",
        "u1 = [0-9]+u",
        "u2 = [0-9]+u"),
    DIVIDE_INT64_OVERFLOW_MIN_INT_BY_NEG_ONE_FAILS_WITH_ERRORS(
        "x == -9223372036854775808 && y == -1 ? x / y == -x : true",
        "Condition is not always true\\.",
        "Counterexample input:",
        "x = -9223372036854775808",
        "y = -1"),
    ;

    final String expr;
    final ImmutableList<String> expectedFragments;

    IsAlwaysTrueViolationTestCase(String expr, String... expectedFragments) {
      this.expr = expr;
      this.expectedFragments = ImmutableList.copyOf(expectedFragments);
    }
  }

  @Test
  public void isAlwaysTrue_violation_returnsFalse(
      @TestParameter IsAlwaysTrueViolationTestCase testCase) throws Exception {
    CelAbstractSyntaxTree ast = CEL.compile(testCase.expr).getAst();

    CelVerificationResult result = VERIFIER.isAlwaysTrue(ast);

    assertThat(result.status()).isEqualTo(VerificationStatus.VIOLATED);
    for (String fragment : testCase.expectedFragments) {
      assertThat(result.message()).containsMatch(fragment);
    }
  }

  private enum IsInconclusiveTestCase {
    UNINTERPRETED_FUNCTION("request.matches('^[a-z]+$')"),
    INT_STRING_UNINTERPRETED("int('123') == 123"),
    LIST_WITH_APPROXIMATE_ELEMENT("[request.matches('a')]"),
    MAP_WITH_APPROXIMATE_KEY("{request.matches('a'): 1}"),
    MAP_WITH_APPROXIMATE_VALUE("{1: request.matches('a')}"),
    COMPREHENSION_APPROXIMATE_CONDITION(
        "size([1, 2, 3].filter(x, request.matches(string(x)))) == 1"),
    ABSTRACT_COMPREHENSION_APPROXIMATE_CONDITION(
        "int_list.all(x, request.matches(string(x)) ? x > 0 : x < 0) == true"),
    ABSTRACT_COMPREHENSION_APPROXIMATE_MAP(
        "int_list.map(x, request.matches(string(x))).size() == 0"),
    COMPREHENSION_APPROXIMATE_STEP("[1, 2, 3].all(x, request.matches(string(x))) == true"),
    STRUCT_FIELD_MISSING_APPROXIMATE_SURVIVOR(
        "TestAllTypes{single_bool: request.matches('a')} == TestAllTypes{}"),
    COMPREHENSION_LITERAL_LIST_APPROXIMATE_SURVIVOR("[request.matches('a')].all(x, x == true)"),
    MAP_INSERT_APPROXIMATE_SURVIVOR("{'a': request.matches('a')} == {'a': true}"),
    MAP_COMPREHENSION_APPROXIMATE_KEY("{request.matches('a') ? 1 : 2 : 'val'}.all(x, x == 1)"),
    MAP_COMPREHENSION_APPROXIMATE_VALUE(
        "{\"key\": request.matches('a') ? 1 : 2}.all(k, v, v == 1)"),
    BIND_APPROXIMATE_ACCU("cel.bind(x, request.matches('a') ? 1 : 2, x == 1)"),
    BIND_APPROXIMATE_BODY("cel.bind(x, 1, x == 1 && request.matches('a'))"),
    COMPREHENSION_OPTIONAL_MAP_ENTRY(
        "size(int_list) == 6 ? size(int_list.map(x, {? x: optional.of(1)})) == 6 : true"),
    COMPREHENSION_OPTIONAL_STRUCT_ENTRY(
        "size(int_list) == 6 ? size(int_list.map(x, TestAllTypes{?single_int32: optional.of(x)}))"
            + " == 6 : true"),
    COMPREHENSION_NULL_CONSTANT("size(int_list) == 6 ? size(int_list.map(x, null)) == 6 : true"),
    COMPREHENSION_UINT_CONSTANT("size(int_list) == 6 ? size(int_list.map(x, 1u)) == 6 : true"),
    COMPREHENSION_DOUBLE_CONSTANT("size(int_list) == 6 ? size(int_list.map(x, 1.0)) == 6 : true"),
    COMPREHENSION_BYTES_CONSTANT("size(int_list) == 6 ? size(int_list.map(x, b'abc')) == 6 : true");

    final String expr;

    IsInconclusiveTestCase(String expr) {
      this.expr = expr;
    }
  }

  @Test
  public void isAlwaysTrue_inconclusive(@TestParameter IsInconclusiveTestCase testCase)
      throws Exception {
    CelAbstractSyntaxTree ast = CEL.compile(testCase.expr).getAst();

    CelVerificationResult result = VERIFIER.isAlwaysTrue(ast);

    assertThat(result.status()).isEqualTo(VerificationStatus.INCONCLUSIVE);
  }

  @Test
  public void isAlwaysTrue_inconclusive_containsPotentialCounterexample() throws Exception {
    CelAbstractSyntaxTree ast = CEL.compile("request.matches('^[a-z]+$') == true").getAst();

    CelVerificationResult result = VERIFIER.isAlwaysTrue(ast);

    assertThat(result.status()).isEqualTo(VerificationStatus.INCONCLUSIVE);
    assertThat(result.message())
        .containsMatch("Potential counterexample input:\\n\\s*request = .*");
  }

  private enum EquivalenceInconclusiveTestCase {
    MASKED_BY_BMC(
        "int_list == [1, 2, 3, 4, 5, 6] ? int_list.all(x, x > 0) : true",
        "int_list == [1, 2, 3, 4, 5, 6] ? (int_list.all(x, x > 0) || size(int_list) == 6) : true"),
    APPROXIMATION_DIVERGENCE("request.matches('a') == true", "request.matches('a') == false"),
    IDENTITY_ERASURE_INCONGRUENCE(
        "size(int_list) == 6 && size(int_list_2) == 6 ? (int_list.map(x, x + 1) + [1]) : [1]",
        "size(int_list) == 6 && size(int_list_2) == 6 ? (int_list_2.map(x, x + 1) + [1]) : [1]"),
    TRUNCATION_DIVERGENCE_DIFFERENT_CONSTANTS(
        "size(int_list) == 6 ? int_list.map(x, x + 1) : [1]",
        "size(int_list) == 6 ? int_list.map(x, x + 2) : [1]"),
    TRUNCATION_DIVERGENCE_DIFFERENT_VARIABLES(
        "size(int_list) == 6 && size(int_list_2) == 6 ? size(int_list.filter(x, x > 2)) : 0",
        "size(int_list) == 6 && size(int_list_2) == 6 ? size(int_list_2.filter(y, y > 2)) : 0"),
    TRUNCATION_DIVERGENCE_DIFFERENT_UINTS(
        "size(int_list) == 6 ? int_list.map(x, 1u) : [1u]",
        "size(int_list) == 6 ? int_list.map(x, 2u) : [1u]"),
    TRUNCATION_DIVERGENCE_DIFFERENT_DOUBLES(
        "size(int_list) == 6 ? int_list.map(x, 1.0) : [1.0]",
        "size(int_list) == 6 ? int_list.map(x, 2.0) : [1.0]"),
    TRUNCATION_DIVERGENCE_DIFFERENT_BYTES(
        "size(int_list) == 6 ? int_list.map(x, b'a') : [b'a']",
        "size(int_list) == 6 ? int_list.map(x, b'b') : [b'a']");

    final String exprA;
    final String exprB;

    EquivalenceInconclusiveTestCase(String exprA, String exprB) {
      this.exprA = exprA;
      this.exprB = exprB;
    }
  }

  @Test
  public void verifyEquivalence_inconclusive(
      @TestParameter EquivalenceInconclusiveTestCase testCase) throws Exception {
    CelAbstractSyntaxTree astA = CEL.compile(testCase.exprA).getAst();
    CelAbstractSyntaxTree astB = CEL.compile(testCase.exprB).getAst();

    CelVerificationResult result = VERIFIER.verifyEquivalence(astA, astB);

    assertThat(result.status()).isEqualTo(VerificationStatus.INCONCLUSIVE);
  }

  private enum EquivalenceTestCase {
    TRUNCATION_STRICT_PROPAGATION_EQUIVALENT(
        "size(int_list) == 6 ? size(int_list.filter(x, x > 2)) : 0",
        "size(int_list) == 6 ? size(int_list.filter(y, y > 2)) : 0"),
    TRUNCATION_EQUIVALENT(
        "int_list == [1, 2, 3, 4, 5, 6] ? int_list.all(x, x > 0) : true",
        "int_list == [1, 2, 3, 4, 5, 6] ? int_list.all(y, y > 0) : true"),
    DE_MORGANS_LAW("!(a && b)", "!a || !b"),
    CONSTANT_FOLDING("x > 5 + 5", "x > 10"),
    STRING_COMPARISON("role == \"admin\"", "\"admin\" == role"),
    FLATTENED_SELECT(
        "test_all_types.single_string == \"admin\"", "\"admin\" == test_all_types.single_string"),
    MACRO_EXISTS_EQUIVALENT("[1, 2, 3].exists(x, x > 0)", "[1, 2, 3].exists(y, y > 0)"),
    IN_LIST_EQUIVALENT("2 in [1, 2, 3]", "2 in [1, 2, 3] || false"),
    MACRO_ALL_EQUIVALENT("[1, 2, 3].all(x, x > 0)", "1 > 0 && 2 > 0 && 3 > 0"),
    MACRO_EXISTS_ONE_EQUIVALENT(
        "[1, 2, 3].exists_one(x, x == 2)",
        "(1 == 2 ? 1 : 0) + (2 == 2 ? 1 : 0) + (3 == 2 ? 1 : 0) == 1"),
    MACRO_MAP_EQUIVALENT("{1: true, 2: true, 3: true}.all(k, k > 0)", "1 > 0 && 2 > 0 && 3 > 0"),
    MACRO_BIND_EQUIVALENT("cel.bind(x, 10, x > 0)", "10 > 0"),
    NESTED_MACRO(
        "[1, 2].all(x, [3, 4].exists(y, x < y))", "[1, 2].all(a, [3, 4].exists(b, a < b))"),
    NESTED_MACRO_SHADOWING(
        "[1, 2].all(x, [3, 4].exists(x, x > 0))", "[1, 2].all(y, [3, 4].exists(z, z > 0))"),
    CONSTANTS_UINT("u > 5u + 5u", "u > 10u"),
    CONSTANTS_DOUBLE("d > 5.5 + 5.5", "d > 11.0"),
    IEEE_754_ZERO_EQUALITY("0.0 == -0.0", "true"),
    IEEE_754_NAN_INEQUALITY("0.0/0.0 == 0.0/0.0", "false"),
    IEEE_754_INFINITY_EQUALITY("1.0/0.0 == 1.0/0.0", "true"),
    IEEE_754_INFINITY_INEQUALITY("-1.0/0.0 == 1.0/0.0", "false"),
    IEEE_754_OVERFLOW_INFINITY("1e300 * 1e300 == 1.0/0.0", "true"),
    CROSS_TYPE_NUMERIC_EQUALITY_INT_DOUBLE("request == 1.0", "request == 1"),
    CROSS_TYPE_NUMERIC_EQUALITY_UINT_DOUBLE("request == 1u", "request == 1.0"),
    CROSS_TYPE_NUMERIC_EQUALITY_INT_UINT("request == 1", "request == 1u"),
    STATIC_DOUBLE_EQUALITY("d + 1.0 == d + 1.0", "d == d"),
    MAP_FIELD_SELECT("string_int_map.my_field > 0", "string_int_map['my_field'] > 0"),
    HETEROGENEOUS_LIST_SIZES_SAFE_FALSE("!([1, 2] == [1, 2, 3])", "true"),
    HETEROGENEOUS_LIST_SIZES_SAFE_FALSE_REVERSE("!([1, 2, 3] == [1, 2])", "true"),
    TRANSITIVE_AST_BINDING_LIST_UNROLLING("cel.bind(x, [1], cel.bind(y, x, y == [1]))", "true"),
    DYNAMIC_VS_STRING_SAFE_BYPASS("request == 'admin' || request != 'admin'", "true"),
    DYNAMIC_MAP_VS_HETEROGENEOUS_LITERAL(
        "request == {'a': 1.0, 'b': 'string'} || request != {'a': 1.0, 'b': 'string'}", "true"),
    DEEP_TRANSITIVE_BIND_NUMERIC("cel.bind(x, 1, cel.bind(y, x, cel.bind(z, y, z))) == 1", "true"),
    DEEP_TRANSITIVE_BIND_COLLECTION("cel.bind(x, [1], cel.bind(y, x, y == [1]))", "true"),
    MACRO_ITER_VAR_SHADOWING_SAFETY("cel.bind(i, 1.0, [1, 2, 3].all(i, i > 0))", "true"),
    MACRO_STRUCTURAL_EQUIVALENCE_PRESERVED(
        "request.auth.claims.groups.all(g, g == 'admin')",
        "request.auth.claims.groups.all(x, x == 'admin')"),

    CONSTANTS_BYTES("by == b\"abc\"", "b\"abc\" == by"),
    STRING_CONCATENATION("\"abc\" + \"def\"", "\"abcdef\""),
    COMPLEX_MESSAGE(
        "test_all_types.single_int32 == 10 && test_all_types.single_int64 > 5",
        "10 == test_all_types.single_int32 && 5 < test_all_types.single_int64"),
    OPTIONAL("optional.of(x).hasValue()", "optional.of(x).hasValue() && true"),
    OPTIONAL_OR_VALUE_EQUIVALENCE("optional.of(x).orValue(y)", "x"),
    OPTIONAL_OR_VALUE_FALLBACK_EQUIVALENCE("optional.none().orValue(y)", "y"),
    OPTIONAL_OR_EQUIVALENCE("optional.of(x).or(optional.of(y))", "optional.of(x)"),
    OPTIONAL_OR_FALLBACK_EQUIVALENCE("optional.none().or(optional.of(y))", "optional.of(y)"),
    OPTIONAL_VALUE_EQUIVALENCE("optional.of(x).value()", "x"),
    OPTIONAL_HAS_VALUE_EQUIVALENCE("optional.of(x).hasValue()", "true"),
    OPTIONAL_NONE_HAS_VALUE_EQUIVALENCE("optional.none().hasValue()", "false"),
    OPTIONAL_OF_NON_ZERO_VALUE_ARITHMETIC_EQUIVALENCE(
        "[optional.ofNonZeroValue(1 + 2 + 3)]", "[optional.of(6)]"),
    OPTIONAL_OF_NON_ZERO_VALUE_INT_ZERO_EQUIVALENCE(
        "optional.ofNonZeroValue(0)", "optional.none()"),
    OPTIONAL_OF_NON_ZERO_VALUE_INT_NON_ZERO_EQUIVALENCE(
        "optional.ofNonZeroValue(5)", "optional.of(5)"),
    OPTIONAL_OF_NON_ZERO_VALUE_STRING_EMPTY_EQUIVALENCE(
        "optional.ofNonZeroValue('')", "optional.none()"),
    OPTIONAL_OF_NON_ZERO_VALUE_STRING_NON_EMPTY_EQUIVALENCE(
        "optional.ofNonZeroValue('hi')", "optional.of('hi')"),
    OPTIONAL_OF_NON_ZERO_VALUE_BOOL_FALSE_EQUIVALENCE(
        "optional.ofNonZeroValue(false)", "optional.none()"),
    OPTIONAL_OF_NON_ZERO_VALUE_BOOL_TRUE_EQUIVALENCE(
        "optional.ofNonZeroValue(true)", "optional.of(true)"),
    OPTIONAL_OF_NON_ZERO_VALUE_DOUBLE_ZERO_EQUIVALENCE(
        "optional.ofNonZeroValue(0.0)", "optional.none()"),
    OPTIONAL_OF_NON_ZERO_VALUE_UINT_ZERO_EQUIVALENCE(
        "optional.ofNonZeroValue(0u)", "optional.none()"),
    OPTIONAL_OF_NON_ZERO_VALUE_LIST_EMPTY_EQUIVALENCE(
        "optional.ofNonZeroValue([])", "optional.none()"),
    OPTIONAL_OF_NON_ZERO_VALUE_MAP_EMPTY_EQUIVALENCE(
        "optional.ofNonZeroValue({})", "optional.none()"),
    OPTIONAL_OF_NON_ZERO_VALUE_BYTES_EMPTY_EQUIVALENCE(
        "optional.ofNonZeroValue(b'')", "optional.none()"),
    OPTIONAL_OF_NON_ZERO_VALUE_NULL_EQUIVALENCE("optional.ofNonZeroValue(null)", "optional.none()"),
    FUNCTIONS("size(\"abc\") == size(role)", "size(role) == size(\"abc\")"),
    NOT_EQUALS("x != y", "!(x == y)"),
    LESS("x < y", "y > x"),
    LESS_EQUALS("x <= y", "y >= x"),
    GREATER("x > y", "y < x"),
    GREATER_EQUALS("x >= y", "y <= x"),
    ADD("x + y", "y + x"),
    SUBTRACT("x - y == 5", "5 == x - y"),
    NESTED_LIST_VARIABLE_EQUALITY(
        "[int_list] == [int_list_2] ? int_list == int_list_2 : true", "true"),
    NESTED_SYMBOLIC_LIST_EXTENSIONALITY(
        "size(nested_list) >= 2 && nested_list[0] == nested_list[1] ? [nested_list[0]] =="
            + " [nested_list[1]] : true",
        "true"),
    NESTED_DYNAMIC_MAP_LOOKUP_EXTENSIONALITY(
        "has(dyn_map.a) && has(dyn_map.a.b) && has(dyn_map.c) && has(dyn_map.c.d) && dyn_map.a.b =="
            + " dyn_map.c.d ? [dyn_map.a.b] == [dyn_map.c.d] : true",
        "true"),
    MULTIPLY("x * y", "y * x"),
    DIVIDE("x / y == 5", "5 == x / y"),
    MODULO("x % y == 5", "5 == x % y"),
    NEGATE("-x == 5", "5 == -x"),
    NEGATE_DOUBLE("-d == 5.0", "5.0 == -d"),
    PRESENCE_TEST("has(test_all_types.single_int32)", "has(test_all_types.single_int32) && true"),
    PRESENCE_TEST_MAP("has(request.headers)", "has(request.headers) && true"),
    PRESENCE_TEST_LIST(
        "has(test_all_types.repeated_int32)", "has(test_all_types.repeated_int32) && true"),
    INVARIANT_LIST_CONTAINS("x in [x, y]", "[x, y][0] == x || [x, y][1] == x"),
    PRESENCE_TEST_TERNARY(
        "has(test_all_types.single_int32) ? test_all_types.single_int32 : 0",
        "has(test_all_types.single_int32) ? test_all_types.single_int32 : 0 * 1"),
    STRUCT_EQUALITY(
        "TestAllTypes{single_int32: 1, single_string: \"abc\"} == TestAllTypes{single_string:"
            + " \"abc\", single_int32: 1}",
        "true"),
    STRUCT_DEEPLY_NESTED_CONTAINER_EQUALITY(
        "TestAllTypes{  single_struct: {'nested_struct': {'map': [1, 2]}}} == TestAllTypes{ "
            + " single_struct: {'nested_struct': {'map': [1, 2]}}}",
        "true"),
    STRUCT_EMPTY_MESSAGE_EQUALITY(
        "TestAllTypes{standalone_message: TestAllTypes.NestedMessage{bb: 1}} =="
            + " TestAllTypes{standalone_message: TestAllTypes.NestedMessage{bb: 1}}",
        "true"),
    STRUCT_INEQUALITY("TestAllTypes{single_int32: 1} != TestAllTypes{single_int32: 2}", "true"),
    STRUCT_FIELD_DEFAULT_PRIMITIVE_INT(
        "test_all_types.single_int32",
        "has(test_all_types.single_int32) ? test_all_types.single_int32 : 0"),
    STRUCT_FIELD_DEFAULT_PRIMITIVE_STRING(
        "test_all_types.single_string",
        "has(test_all_types.single_string) ? test_all_types.single_string : \"\""),
    STRUCT_FIELD_DEFAULT_PRIMITIVE_BOOL(
        "test_all_types.single_bool",
        "has(test_all_types.single_bool) ? test_all_types.single_bool : false"),
    STRUCT_FIELD_DEFAULT_PRIMITIVE_BYTES(
        "test_all_types.single_bytes",
        "has(test_all_types.single_bytes) ? test_all_types.single_bytes : b\"\""),
    STRUCT_FIELD_DEFAULT_PRIMITIVE_DOUBLE(
        "test_all_types.single_double",
        "has(test_all_types.single_double) ? test_all_types.single_double : 0.0"),
    STRUCT_FIELD_DEFAULT_PRIMITIVE_UINT(
        "test_all_types.single_uint32",
        "has(test_all_types.single_uint32) ? test_all_types.single_uint32 : 0u"),
    STRUCT_FIELD_DEFAULT_LIST(
        "test_all_types.repeated_int32",
        "has(test_all_types.repeated_int32) ? test_all_types.repeated_int32 : []"),
    STRUCT_FIELD_DEFAULT_MAP(
        "test_all_types.map_int32_int32",
        "has(test_all_types.map_int32_int32) ? test_all_types.map_int32_int32 : {}"),
    STRUCT_FIELD_DEFAULT_NESTED_PRIMITIVE("TestAllTypes{}.standalone_message.bb", "0"),
    MAP_LITERALS("{'a': 1, 'b': 2} == {'b': 2, 'a': 1}", "true"),
    MAP_INDEX_AND_IN("{'a': 1}['a'] == 1 && 'a' in {'a': 1} && !('b' in {'a': 1})", "true"),
    MACRO_MAP_VARIABLE_EQUIVALENCE(
        "{x: y, a: b}.all(i, i == x || i == a)", "{x: y, a: b}.all(j, j == x || j == a)"),
    STRUCT_PROTO3_DEFAULT_EQUALITY("TestAllTypes{single_int32: 0} == TestAllTypes{}", "true"),
    STRUCT_DEFAULT_FALLBACK_EQUALITY(
        "TestAllTypes{}.standalone_message == TestAllTypes.NestedMessage{}", "true"),
    DYNAMIC_OPERATOR_OVERLOAD("dyn(1) < 2 || dyn(1) >= 2", "dyn(1) < 2 || dyn(1) >= 2"),
    DYNAMIC_MAP_SELECT("dyn({'a': 1}).a == 1", "dyn({'a': 1}).a == 1"),
    DYNAMIC_MESSAGE_SELECT(
        "dyn(TestAllTypes{single_int32: 1}).single_int32 == 1",
        "dyn(TestAllTypes{single_int32: 1}).single_int32 == 1"),
    DYNAMIC_SELECT_TEST_ONLY(
        "has(dyn({'a': 1}).a) && has(dyn(TestAllTypes{single_int32: 1}).single_int32)",
        "has(dyn({'a': 1}).a) && has(dyn(TestAllTypes{single_int32: 1}).single_int32)"),
    DYNAMIC_INDEXING_TYPE_MISMATCH(
        "type(request) == type(1) && request[1] == 1 && request[2] == 2",
        "type(request) == type(1) && 1 / 0 == 1 && request[2] == 2"),
    OPTIONAL_PRUNE_LIST_LITERAL("[1, ?optional.of(3)]", "[1,3]"),
    OPTIONAL_PRUNE_LIST_NONE("[?optional.none(), ?opt_var]", "[?opt_var]"),
    OPTIONAL_PRUNE_MAP_NONE("{?1: optional.none()}", "{}"),
    OPTIONAL_PRUNE_STRUCT_LIST(
        "TestAllTypes{?repeated_int32: optional.of([1, 2])}",
        "cel.expr.conformance.proto3.TestAllTypes{repeated_int32: [1, 2]}"),
    OPTIONAL_PRUNE_LIST_EQUALITY("[?optional.none(), 1] == [1]", "true"),
    OPTIONAL_PRUNE_LIST_COMPREHENSION("[1, ?optional.none()].all(x, x > 0)", "true"),
    MAP_COMPREHENSION(
        "{'a': 1, 'b': 2}.exists(k, k == 'a')", "{'a': 1, 'b': 2}.exists(k, k == 'a')");
    private final String exprA;
    private final String exprB;

    EquivalenceTestCase(String exprA, String exprB) {
      this.exprA = exprA;
      this.exprB = exprB;
    }
  }

  @Test
  public void verifyEquivalence_success(@TestParameter EquivalenceTestCase testCase)
      throws Exception {
    CelAbstractSyntaxTree astA = CEL.compile(testCase.exprA).getAst();
    CelAbstractSyntaxTree astB = CEL.compile(testCase.exprB).getAst();

    CelVerificationResult result = VERIFIER.verifyEquivalence(astA, astB);

    assertWithMessage(result.message())
        .that(result.status())
        .isEqualTo(VerificationStatus.VERIFIED);
  }

  private enum EquivalenceViolationTestCase {
    INT("x > 10", "x > 5"),
    UINT("u > 10u", "u > 5u"),
    DOUBLE("d > 10.0", "d > 5.0"),
    STRING("role == \"admin\"", "role == \"user\""),
    BYTES("by == b\"abc\"", "by == b\"def\""),
    STRING_CONCATENATION_NOT_COMMUTATIVE("\"def\" + \"abc\"", "\"abcdef\""),
    PRESENCE("has(test_all_types.single_int32)", "test_all_types.single_int32 == 1"),
    MAP_INDEX_MISSING("{'a': 1}['b'] == 1", "true"),
    MESSAGE_TYPE_MISMATCH(
        "dyn(TestAllTypes{single_int32: 1}) == dyn(TestAllTypes.NestedMessage{bb: 1})", "true"),
    HETEROGENEOUS_FIELD_SELECTION(
        "test_all_types.single_int32 == 10", "test_all_types.single_int64 == 10"),
    STRUCT_VARIABLE_NOT_EQUIVALENT_TO_DEFAULT("test_all_types == TestAllTypes{}", "true"),
    OPTIONAL_INVALID_PRUNE_OPT_VAR("[1, ?opt_var]", "[1]"),
    CROSS_TYPE_NUMERIC_INEQUALITY_INT_DOUBLE("request == 1.0", "request == 2.0 || request == 1"),
    CROSS_TYPE_SYMBOLIC_INEQUALITY_INT_UINT("dyn(x) == dyn(u)", "false"),
    CROSS_TYPE_SYMBOLIC_INEQUALITY_UINT_INT("dyn(u) == dyn(x)", "false"),
    OPTIONAL_OR_VALUE_VIOLATION("optional.of(x).orValue(y)", "y"),
    OPTIONAL_VALUE_VIOLATION("optional.of(x).value()", "y"),
    LIST_OPTIONAL_ELEMENTS_COLLISION("[1, ?opt_var]", "[1, opt_var]"),
    CROSS_NUMERIC_EQUALITY_INT_DYN_VIOLATION("1 == request", "false");

    final String exprA;
    final String exprB;

    EquivalenceViolationTestCase(String exprA, String exprB) {
      this.exprA = exprA;
      this.exprB = exprB;
    }
  }

  @Test
  public void verifyEquivalence_violation_returnsFalse(
      @TestParameter EquivalenceViolationTestCase testCase) throws Exception {
    CelAbstractSyntaxTree astA = CEL.compile(testCase.exprA).getAst();
    CelAbstractSyntaxTree astB = CEL.compile(testCase.exprB).getAst();

    CelVerificationResult result = VERIFIER.verifyEquivalence(astA, astB);

    assertThat(result.status()).isEqualTo(VerificationStatus.VIOLATED);
    assertThat(result.message()).contains("Equivalence violation detected.");
  }

  @Test
  public void verifyEquivalence_violation_hasCounterexampleMessage() throws Exception {
    CelAbstractSyntaxTree astA = CEL.compile("x > y").getAst();
    CelAbstractSyntaxTree astB = CEL.compile("x > 5").getAst();

    CelVerificationResult result = VERIFIER.verifyEquivalence(astA, astB);

    assertThat(result.status()).isEqualTo(VerificationStatus.VIOLATED);
    String message = result.message();
    assertThat(message).contains("Equivalence violation detected. Counterexample input:");
    assertThat(message).containsMatch(" {2}x = -?\\d+");
    assertThat(message).containsMatch(" {2}y = -?\\d+");
  }

  @Test
  public void verifyEquivalence_divergesOnTernaryErrorSemantics() throws Exception {
    // astA: Always True
    // astB: Evaluates to Error
    CelAbstractSyntaxTree astA = CEL.compile("true").getAst();
    CelAbstractSyntaxTree astB = CEL.compile("(1 / 0 == 1) ? true : true").getAst();

    CelVerificationResult result = VERIFIER.verifyEquivalence(astA, astB);

    assertThat(result.status()).isEqualTo(VerificationStatus.VIOLATED);
  }

  @Test
  public void verifyEquivalence_dynamicIndexingWithExplicitMap_hydratesMap() throws Exception {
    CelAbstractSyntaxTree astA =
        CEL.compile("string_int_list_map == {\"a\": [1, 2], \"b\": [3, 4]}").getAst();
    CelAbstractSyntaxTree astB =
        CEL.compile("string_int_list_map == {\"a\": [1, 2], \"b\": [3, 5]}").getAst();

    CelVerificationResult result = VERIFIER.verifyEquivalence(astA, astB);

    assertThat(result.status()).isEqualTo(VerificationStatus.VIOLATED);
    assertThat(result.message()).contains("string_int_list_map = {");
    assertThat(result.message()).contains("\"a\": [1, 2]");
    assertThat(result.message()).containsMatch("\"b\": \\[3, [45]]");
  }

  @Test
  public void verifyEquivalence_logicalAndShortCircuitError_isVerified() throws Exception {
    CelAbstractSyntaxTree astA = CEL.compile("false && (1 / 0 == 0)").getAst();
    CelAbstractSyntaxTree astB = CEL.compile("false").getAst();
    CelVerificationResult result = VERIFIER.verifyEquivalence(astA, astB);
    assertThat(result.status()).isEqualTo(VerificationStatus.VERIFIED);
  }

  @Test
  public void verifyEquivalence_logicalOrShortCircuitUnknown_isVerified() throws Exception {
    CelAbstractSyntaxTree astA = CEL.compile("true || unknown_var").getAst();
    CelAbstractSyntaxTree astB = CEL.compile("true").getAst();
    CelVerificationResult result = VERIFIER.verifyEquivalence(astA, astB);
    assertThat(result.status()).isEqualTo(VerificationStatus.VERIFIED);
  }

  @Test
  public void verifyEquivalence_deepListExtensionality_isVerified() throws Exception {
    CelAbstractSyntaxTree astA = CEL.compile("[[[[1]]]] == [[[[1]]]]").getAst();
    CelAbstractSyntaxTree astB = CEL.compile("true").getAst();
    CelVerificationResult result = VERIFIER.verifyEquivalence(astA, astB);
    assertThat(result.status()).isEqualTo(VerificationStatus.VERIFIED);
  }

  @Test
  public void verifyEquivalence_stringConcatenationExplosion_isVerified() throws Exception {
    CelAbstractSyntaxTree astA =
        CEL.compile("(\"a\" + \"b\" + \"c\" + \"d\" + \"e\") == \"abcde\"").getAst();
    CelAbstractSyntaxTree astB = CEL.compile("true").getAst();
    CelVerificationResult result = VERIFIER.verifyEquivalence(astA, astB);
    assertThat(result.status()).isEqualTo(VerificationStatus.VERIFIED);
  }

  @Test
  public void verifyEquivalence_divergesOnShortCircuitingErrors() throws Exception {
    // astA: Short-circuits to True (Error is ignored)
    // astB: A naive restructuring that might trigger an Error depending on evaluation order.
    CelAbstractSyntaxTree astA = CEL.compile("true || (role == \"admin\" && 1 / 0 == 1)").getAst();
    CelAbstractSyntaxTree astB = CEL.compile("(role == \"admin\" && 1 / 0 == 1) || true").getAst();

    CelVerificationResult result = VERIFIER.verifyEquivalence(astA, astB);

    assertThat(result.status()).isEqualTo(VerificationStatus.VERIFIED);
  }

  @Test
  public void verifyEquivalence_distinctErrorEquivalence_failsVerification() throws Exception {
    // If error-collapsing is present, this will falsely pass because both sides yield CelError.
    // It MUST fail verification.
    CelAbstractSyntaxTree astA = CEL.compile("5u + 5u == 10u").getAst();
    CelAbstractSyntaxTree astB = CEL.compile("role + role == \"abc\"").getAst();
    CelVerificationResult result = VERIFIER.verifyEquivalence(astA, astB);

    assertThat(result.status()).isEqualTo(VerificationStatus.VIOLATED);
  }

  private enum CounterexampleFormatTestCase {
    STRING(
        "role == \"admin\"",
        "role == \"user\"",
        ImmutableList.of("role = \"admin\"", "role = \"user\"")),
    DOUBLE("d == 1.5", "d == 2.5", ImmutableList.of("d = 1\\.5", "d = 2\\.5")),
    DOUBLE_NAN("d != d", "false", ImmutableList.of("d = NaN")),
    DOUBLE_INF("d == (1.0 / 0.0)", "false", ImmutableList.of("d = Infinity")),
    DOUBLE_NEG_INF("d == (-1.0 / 0.0)", "false", ImmutableList.of("d = -Infinity")),
    DOUBLE_NEG_ZERO("d == 0.0 && 1.0 / d == (-1.0 / 0.0)", "false", ImmutableList.of("d = -0\\.0")),
    DOUBLE_POS_ZERO("d == 0.0 && 1.0 / d == (1.0 / 0.0)", "false", ImmutableList.of("d = 0\\.0")),
    UINT("u == 443u", "u == 80u", ImmutableList.of("u = 443u", "u = 80u")),
    BOOL("a == true", "a == false", ImmutableList.of("a = true", "a = false")),
    EMPTY_MAP(
        "string_int_map == {}",
        "string_int_map == {\"a\": 1}",
        ImmutableList.of("string_int_map = \\{\\}", "string_int_map = \\{\"a\": 1\\}")),
    LIST(
        "int_list == [1, 2]",
        "int_list == [3, 4]",
        ImmutableList.of("int_list = \\[1, 2\\]", "int_list = \\[3, 4\\]")),
    MAP(
        "string_int_map == {'a': 1}",
        "string_int_map == {'b': 2}",
        ImmutableList.of("string_int_map = \\{\"a\": 1\\}", "string_int_map = \\{\"b\": 2\\}")),
    BYTES(
        "bytes_val == b'foo'",
        "bytes_val == b'bar'",
        ImmutableList.of("bytes_val = b\"foo\"", "bytes_val = b\"bar\"")),
    NESTED_COLLECTION(
        "string_int_list_map == {\"a\": [1, 2]}",
        "string_int_list_map == {\"a\": [3, 4]}",
        ImmutableList.of(
            "string_int_list_map = \\{\"a\": \\[1, 2\\]\\}",
            "string_int_list_map = \\{\"a\": \\[3, 4\\]\\}")),
    LIST_UNCONSTRAINED(
        "int_list == int_list", "false", ImmutableList.of("int_list = \\[-?\\d*\\]")),
    MAP_UNCONSTRAINED(
        "string_int_map == string_int_map", "false", ImmutableList.of("string_int_map = \\{\\}")),
    STRUCT_FIELD_MISSING_INT_NOT_EQUAL_TO_NONDEFAULT(
        "test_all_types.single_int32",
        "has(test_all_types.single_int32) ? test_all_types.single_int32 : 1",
        ImmutableList.of(
            "test_all_types = cel\\.expr\\.conformance\\.proto3\\.TestAllTypes\\{\\}")),
    STRUCT_FIELD_MISSING_STRING_NOT_EQUAL_TO_NONDEFAULT(
        "test_all_types.single_string",
        "has(test_all_types.single_string) ? test_all_types.single_string : \"foo\"",
        ImmutableList.of(
            "test_all_types = cel\\.expr\\.conformance\\.proto3\\.TestAllTypes\\{\\}")),
    STRUCT_FIELD_MISSING_BOOL_NOT_EQUAL_TO_NONDEFAULT(
        "test_all_types.single_bool",
        "has(test_all_types.single_bool) ? test_all_types.single_bool : true",
        ImmutableList.of(
            "test_all_types = cel\\.expr\\.conformance\\.proto3\\.TestAllTypes\\{\\}")),
    STRUCT_FIELD_MISSING_UINT_NOT_EQUAL_TO_NONDEFAULT(
        "test_all_types.single_uint32",
        "has(test_all_types.single_uint32) ? test_all_types.single_uint32 : 1u",
        ImmutableList.of(
            "test_all_types = cel\\.expr\\.conformance\\.proto3\\.TestAllTypes\\{\\}")),
    STRUCT_FIELD_MISSING_DOUBLE_NOT_EQUAL_TO_NONDEFAULT(
        "test_all_types.single_double",
        "has(test_all_types.single_double) ? test_all_types.single_double : 1.0",
        ImmutableList.of(
            "test_all_types = cel\\.expr\\.conformance\\.proto3\\.TestAllTypes\\{\\}")),
    STRUCT_FIELD_MISSING_BYTES_NOT_EQUAL_TO_NONDEFAULT(
        "test_all_types.single_bytes",
        "has(test_all_types.single_bytes) ? test_all_types.single_bytes : b\"foo\"",
        ImmutableList.of(
            "test_all_types = cel\\.expr\\.conformance\\.proto3\\.TestAllTypes\\{\\}")),
    STRUCT_FIELD_MISSING_LIST_NOT_EQUAL_TO_NONDEFAULT(
        "test_all_types.repeated_int32",
        "has(test_all_types.repeated_int32) ? test_all_types.repeated_int32 : [1]",
        ImmutableList.of(
            "test_all_types = cel\\.expr\\.conformance\\.proto3\\.TestAllTypes\\{\\}")),
    STRUCT_FIELD_MISSING_MAP_NOT_EQUAL_TO_NONDEFAULT(
        "test_all_types.map_int32_int32",
        "has(test_all_types.map_int32_int32) ? test_all_types.map_int32_int32 : {1: 2}",
        ImmutableList.of(
            "test_all_types = cel\\.expr\\.conformance\\.proto3\\.TestAllTypes\\{\\}")),
    STRUCT_FIELD_MISSING_MESSAGE_NOT_EQUAL_TO_NONDEFAULT(
        "test_all_types.standalone_message",
        "has(test_all_types.standalone_message) ? test_all_types.standalone_message :"
            + " TestAllTypes.NestedMessage{bb: 1}",
        ImmutableList.of(
            "test_all_types = cel\\.expr\\.conformance\\.proto3\\.TestAllTypes\\{.*\\}"));

    final String exprA;
    final String exprB;
    final ImmutableList<String> expectedFragments;

    CounterexampleFormatTestCase(
        String exprA, String exprB, ImmutableList<String> expectedFragments) {
      this.exprA = exprA;
      this.exprB = exprB;
      this.expectedFragments = expectedFragments;
    }
  }

  @Test
  public void verifyEquivalence_counterexampleFormat(
      @TestParameter CounterexampleFormatTestCase testCase) throws Exception {
    CelAbstractSyntaxTree astA = CEL.compile(testCase.exprA).getAst();
    CelAbstractSyntaxTree astB = CEL.compile(testCase.exprB).getAst();
    CelVerificationResult result = VERIFIER.verifyEquivalence(astA, astB);

    assertThat(result.status()).isEqualTo(VerificationStatus.VIOLATED);
    boolean matched =
        testCase.expectedFragments.stream()
            .anyMatch(
                f ->
                    result
                        .message()
                        .matches(
                            "(?s).*Equivalence violation detected\\. Counterexample input:\n"
                                + " {2}"
                                + f
                                + ".*"));
    assertWithMessage(result.message()).that(matched).isTrue();
  }

  @Test
  public void verifyEquivalence_structCounterexampleFormat() throws Exception {
    CelAbstractSyntaxTree astA =
        CEL.compile("test_all_types == TestAllTypes{single_int32: 1}").getAst();
    CelAbstractSyntaxTree astB =
        CEL.compile("test_all_types == TestAllTypes{single_int32: 2}").getAst();
    CelVerificationResult result = VERIFIER.verifyEquivalence(astA, astB);

    assertThat(result.status()).isEqualTo(VerificationStatus.VIOLATED);
    assertThat(result.message())
        .isAnyOf(
            "Equivalence violation detected. Counterexample input:\n"
                + "  test_all_types = cel.expr.conformance.proto3.TestAllTypes{single_int32: 1}",
            "Equivalence violation detected. Counterexample input:\n"
                + "  test_all_types = cel.expr.conformance.proto3.TestAllTypes{single_int32: 2}");
  }

  @Test
  public void isAlwaysTrue_operationError_counterexampleFormat() throws Exception {
    // x / x == x / x looks like a tautology, but if x = 0, 0 / 0 throws an Error.
    // Error == Error evaluates to Error, which is not true.
    // Thus, x = 0 is the only valid counterexample.
    CelAbstractSyntaxTree ast = CEL.compile("x / x == x / x").getAst();
    CelVerificationResult result = VERIFIER.isAlwaysTrue(ast);

    assertThat(result.status()).isEqualTo(VerificationStatus.VIOLATED);
    assertThat(result.message()).contains("x = 0");
  }

  @Test
  public void isAlwaysTrue_dynamicFunction_nan_violated() throws Exception {
    // request() == request() is a tautology only if request() does not evaluate to NaN.
    // Since request() returns DYN, Z3 can assign NaN to its return value, and NaN == NaN is false.
    CelCompiler celCompiler =
        CEL.toCompilerBuilder()
            .setContainer(CelContainer.ofName("cel.expr.conformance.proto3"))
            .addFunctionDeclarations(
                CelFunctionDecl.newFunctionDeclaration(
                    "request",
                    CelOverloadDecl.newGlobalOverload("request_overload", SimpleType.DYN)))
            .build();
    CelAbstractSyntaxTree ast = celCompiler.compile("request() == request()").getAst();
    CelVerificationResult result = VERIFIER.isAlwaysTrue(ast);

    assertThat(result.status()).isEqualTo(VerificationStatus.VIOLATED);
    assertThat(result.message()).contains("request = NaN");
  }

  @Test
  public void isAlwaysTrue_boolFunction_verified() throws Exception {
    CelCompiler celCompiler =
        CEL.toCompilerBuilder()
            .setContainer(CelContainer.ofName("cel.expr.conformance.proto3"))
            .addFunctionDeclarations(
                CelFunctionDecl.newFunctionDeclaration(
                    "bool_func",
                    CelOverloadDecl.newGlobalOverload("bool_func_overload", SimpleType.BOOL)))
            .build();
    CelAbstractSyntaxTree ast = celCompiler.compile("bool_func() == bool_func()").getAst();
    CelVerificationResult result = VERIFIER.isAlwaysTrue(ast);

    assertThat(result.status()).isEqualTo(VerificationStatus.VERIFIED);
  }

  @Test
  public void verifyEquivalence_functionError_equivalent() throws Exception {
    // request() and request() are equivalent, even if request() throws an Error,
    // because Error == Error structurally in Z3 equivalence.
    CelCompiler celCompiler =
        CEL.toCompilerBuilder()
            .setContainer(CelContainer.ofName("cel.expr.conformance.proto3"))
            .addFunctionDeclarations(
                CelFunctionDecl.newFunctionDeclaration(
                    "request",
                    CelOverloadDecl.newGlobalOverload("request_overload", SimpleType.DYN)))
            .build();
    CelAbstractSyntaxTree astA = celCompiler.compile("request()").getAst();
    CelAbstractSyntaxTree astB = celCompiler.compile("request()").getAst();
    CelVerificationResult result = VERIFIER.verifyEquivalence(astA, astB);

    assertThat(result.status()).isEqualTo(VerificationStatus.VERIFIED);
  }

  @Test
  @SuppressWarnings("GoodTime-ApiWithNumericTimeUnit") // Test only
  public void setTimeout_invalidDuration_throws(@TestParameter({"0", "-1"}) long timeoutSeconds) {
    CelVerifierBuilder builder = CelVerifierFactory.newVerifier();
    IllegalArgumentException exception =
        assertThrows(
            IllegalArgumentException.class,
            () -> builder.setTimeout(Duration.ofSeconds(timeoutSeconds)));
    assertThat(exception).hasMessageThat().contains("Timeout must be strictly positive");
  }

  @Test
  public void isSatisfiable_divisionByZero_failsInCelWithErrors() throws Exception {
    CelAbstractSyntaxTree ast = CEL.compile("1 / 0 == 5").getAst();

    CelVerificationResult result = VERIFIER.isSatisfiable(ast);

    assertThat(result.status()).isEqualTo(VerificationStatus.VIOLATED);
  }

  @Test
  public void isSatisfiable_timeoutReached_throwsCelVerificationException() throws Exception {
    CelVerifier timeoutVerifier =
        CelVerifierFactory.newVerifier().setTimeout(Duration.ofMillis(1)).build();

    Cel customCel =
        CelFactory.plannerCelBuilder()
            .addVar("d1", SimpleType.DOUBLE)
            .addVar("d2", SimpleType.DOUBLE)
            .addVar("d3", SimpleType.DOUBLE)
            .addVar("d4", SimpleType.DOUBLE)
            .build();

    // An overly complex double multiplication to guarantee Z3 FPA theory solver timeouts.
    CelAbstractSyntaxTree ast =
        customCel
            .compile(
                "d1 * d2 * d3 * d4 * d1 * d2 * d3 * d4 == 9429185123491285.0 && d1 > 100000.0 &&"
                    + " d2 > 100000.0 && d3 > 100000.0 && d4 > 100000.0")
            .getAst();

    CelVerificationException e =
        assertThrows(CelVerificationException.class, () -> timeoutVerifier.isSatisfiable(ast));
    assertThat(e).hasMessageThat().containsMatch("timeout|canceled");
  }

  @Test
  public void addFunctionAxioms_iterable_addsToAxioms() throws Exception {
    CelFunctionDecl dummyFunctionDecl =
        CelFunctionDecl.newFunctionDeclaration(
            "dummy", CelOverloadDecl.newGlobalOverload("dummy_overload", SimpleType.INT));
    CelCompiler celCompiler =
        CEL.toCompilerBuilder()
            .setContainer(CelContainer.ofName("cel.expr.conformance.proto3"))
            .addFunctionDeclarations(dummyFunctionDecl)
            .build();
    CelZ3FunctionAxiom dummyAxiom =
        CelZ3FunctionAxiom.newBuilder(dummyFunctionDecl)
            .addOverloadTranslator(
                "dummy_overload",
                (ctx, typeSystem, constraintSink, unwrappedArgs, argApproximations) ->
                    Optional.of(
                        CelZ3OverloadResult.create(
                            typeSystem.wrap(typeSystem.intCons(), ctx.mkInt(42)), ctx.mkFalse())))
            .build();
    CelVerifier verifier =
        CelVerifierZ3Impl.newBuilder().addFunctionAxioms(ImmutableList.of(dummyAxiom)).build();
    CelAbstractSyntaxTree ast = celCompiler.compile("dummy() == 42").getAst();
    CelVerificationResult result = verifier.isAlwaysTrue(ast);

    assertThat(result.status()).isEqualTo(VerificationStatus.VERIFIED);
  }

  @Test
  public void isAlwaysTrue_functionAxiomWithErrorArgument_bubblesUpError() throws Exception {
    CelFunctionDecl dummyFunctionDecl =
        CelFunctionDecl.newFunctionDeclaration(
            "dummy",
            CelOverloadDecl.newGlobalOverload("dummy_overload", SimpleType.DYN, SimpleType.DYN));
    CelZ3FunctionAxiom dummyAxiom =
        CelZ3FunctionAxiom.newBuilder(dummyFunctionDecl)
            .addUnaryOverloadTranslator(
                "dummy_overload",
                (ctx, typeSystem, constraintSink, arg) -> Optional.of(typeSystem.mkInt(42)))
            .build();
    CelVerifier verifier =
        CelVerifierZ3Impl.newBuilder().addFunctionAxioms(ImmutableList.of(dummyAxiom)).build();
    CelCompiler celCompiler =
        CEL.toCompilerBuilder()
            .setContainer(CelContainer.ofName("cel.expr.conformance.proto3"))
            .addFunctionDeclarations(dummyFunctionDecl)
            .build();

    // dummy(x / x) == 42. If x == 0, x / x is Error.
    // If the axiom bubbling works, dummy(Error) evaluates to Error, so the whole expression is
    // Error (not always true).
    // If the axiom bubbling is missing, dummy(Error) evaluates to 42, so 42 == 42 is always true.
    CelAbstractSyntaxTree ast = celCompiler.compile("dummy(x / x) == 42").getAst();
    CelVerificationResult result = verifier.isAlwaysTrue(ast);

    assertThat(result.status()).isEqualTo(VerificationStatus.VIOLATED);
    assertThat(result.message()).contains("x = 0");
  }

  @Test
  public void addFunctionAxioms_axiomHandlesNothing_fallsBackToUninterpretedFunction()
      throws Exception {
    CelFunctionDecl dummyAddDecl =
        CelFunctionDecl.newFunctionDeclaration(
            "dummy_add",
            CelOverloadDecl.newGlobalOverload(
                "dummy_add_overload", SimpleType.INT, SimpleType.INT, SimpleType.INT));
    CelZ3FunctionAxiom dummyAxiom =
        CelZ3FunctionAxiom.newBuilder(dummyAddDecl)
            .addBinaryOverloadTranslator(
                "dummy_add_overload",
                (ctx, typeSystem, constraintSink, arg1, arg2) -> Optional.empty())
            .build();
    CelVerifier verifier =
        CelVerifierZ3Impl.newBuilder().addFunctionAxioms(ImmutableList.of(dummyAxiom)).build();
    Cel cel = CelFactory.plannerCelBuilder().addFunctionDeclarations(dummyAddDecl).build();

    CelAbstractSyntaxTree ast = cel.compile("dummy_add(1, 1) == 2").getAst();
    CelVerificationResult result = verifier.isAlwaysTrue(ast);

    // Falls back to an uninterpreted function since it was not translated
    // (thus comes back as inconclusive due to being an approximation)
    assertThat(result.status()).isEqualTo(VerificationStatus.INCONCLUSIVE);
  }

  private enum TernaryApproxTestCase {
    APPROX_FIRST("dummy_ternary(uninterpreted_int(), 0, 0) == 99"),
    APPROX_SECOND("dummy_ternary(0, uninterpreted_int(), 0) == 99"),
    APPROX_THIRD("dummy_ternary(0, 0, uninterpreted_int()) == 99");

    final String expr;

    TernaryApproxTestCase(String expr) {
      this.expr = expr;
    }
  }

  @Test
  public void addFunctionAxioms_ternaryOverload_approximation_inconclusive(
      @TestParameter TernaryApproxTestCase testCase) throws Exception {
    CelFunctionDecl dummyDecl =
        CelFunctionDecl.newFunctionDeclaration(
            "dummy_ternary",
            CelOverloadDecl.newGlobalOverload(
                "dummy_ternary_overload",
                SimpleType.INT,
                SimpleType.INT,
                SimpleType.INT,
                SimpleType.INT));
    CelZ3FunctionAxiom dummyAxiom =
        CelZ3FunctionAxiom.newBuilder(dummyDecl)
            .addTernaryOverloadTranslator(
                "dummy_ternary_overload",
                (ctx, ts, sink, arg1, arg2, arg3) ->
                    Optional.of(
                        ts.wrapInt(
                            (IntExpr)
                                ctx.mkAdd(ts.getInt(arg1), ts.getInt(arg2), ts.getInt(arg3)))))
            .build();
    CelVerifier verifier =
        CelVerifierZ3Impl.newBuilder().addFunctionAxioms(ImmutableList.of(dummyAxiom)).build();

    CelFunctionDecl uninterpretedIntDecl =
        CelFunctionDecl.newFunctionDeclaration(
            "uninterpreted_int",
            CelOverloadDecl.newGlobalOverload("uninterpreted_int_overload", SimpleType.INT));
    Cel cel =
        CelFactory.plannerCelBuilder()
            .addFunctionDeclarations(dummyDecl)
            .addFunctionDeclarations(uninterpretedIntDecl)
            .build();

    CelAbstractSyntaxTree ast = cel.compile(testCase.expr).getAst();
    CelVerificationResult result = verifier.isAlwaysTrue(ast);

    assertThat(result.status()).isEqualTo(VerificationStatus.INCONCLUSIVE);
  }

  @Test
  public void addFunctionAxioms_heterogeneousArguments_checksAllTypes() throws Exception {
    // Tests that the typeGuard is applied correctly to all arguments (including index 0).
    CelFunctionDecl dummyDecl =
        CelFunctionDecl.newFunctionDeclaration(
            "dummy_func",
            CelOverloadDecl.newGlobalOverload(
                "dummy_func_list",
                SimpleType.INT,
                ListType.create(SimpleType.DYN),
                ListType.create(SimpleType.DYN)),
            CelOverloadDecl.newGlobalOverload(
                "dummy_func_int", SimpleType.INT, SimpleType.INT, ListType.create(SimpleType.DYN)));
    CelZ3FunctionAxiom dummyAxiom =
        CelZ3FunctionAxiom.newBuilder(dummyDecl)
            .addBinaryOverloadTranslator(
                "dummy_func_list",
                (ctx, typeSystem, constraintSink, arg1, arg2) ->
                    Optional.of(typeSystem.wrap(typeSystem.intCons(), ctx.mkInt(1))))
            .addBinaryOverloadTranslator(
                "dummy_func_int",
                (ctx, typeSystem, constraintSink, arg1, arg2) ->
                    Optional.of(typeSystem.wrap(typeSystem.intCons(), ctx.mkInt(2))))
            .build();
    CelVerifier verifier =
        CelVerifierZ3Impl.newBuilder().addFunctionAxioms(ImmutableList.of(dummyAxiom)).build();
    Cel cel =
        CelFactory.plannerCelBuilder()
            .addVar("int_var", SimpleType.INT)
            .addFunctionDeclarations(dummyDecl)
            .build();
    // The call dummy_func(int_var, [1]) should match dummy_func_int, returning 2.
    // If index 0 type check is skipped, it will match dummy_func_list, returning 1.
    CelAbstractSyntaxTree ast =
        cel.compile("int_var == 1 ? dummy_func(int_var, [1]) == 2 : true").getAst();

    CelVerificationResult result = verifier.isAlwaysTrue(ast);

    assertWithMessage(result.message())
        .that(result.status())
        .isEqualTo(VerificationStatus.VERIFIED);
  }

  @Test
  public void addFunctionAxioms_duplicateFunctionNames_throwsException() throws Exception {
    CelFunctionDecl dummyDecl1 =
        CelFunctionDecl.newFunctionDeclaration(
            "dummy_func",
            CelOverloadDecl.newGlobalOverload("dummy_func_int", SimpleType.INT, SimpleType.INT));
    CelFunctionDecl dummyDecl2 =
        CelFunctionDecl.newFunctionDeclaration(
            "dummy_func",
            CelOverloadDecl.newGlobalOverload(
                "dummy_func_list", SimpleType.INT, ListType.create(SimpleType.DYN)));

    CelZ3FunctionAxiom dummyAxiom1 =
        CelZ3FunctionAxiom.newBuilder(dummyDecl1)
            .addUnaryOverloadTranslator(
                "dummy_func_int",
                (ctx, typeSystem, constraintSink, arg) ->
                    Optional.of(typeSystem.wrap(typeSystem.intCons(), ctx.mkInt(1))))
            .build();
    CelZ3FunctionAxiom dummyAxiom2 =
        CelZ3FunctionAxiom.newBuilder(dummyDecl2)
            .addUnaryOverloadTranslator(
                "dummy_func_list",
                (ctx, typeSystem, constraintSink, arg) ->
                    Optional.of(typeSystem.wrap(typeSystem.intCons(), ctx.mkInt(2))))
            .build();

    CelVerifierZ3Impl.Builder builder =
        CelVerifierZ3Impl.newBuilder()
            .addFunctionAxioms(ImmutableList.of(dummyAxiom1, dummyAxiom2));

    IllegalArgumentException exception =
        assertThrows(IllegalArgumentException.class, builder::build);

    assertThat(exception).hasMessageThat().contains("dummy_func");
  }

  @Test
  public void addFunctionAxioms_allPrimitiveTypes_checksAllTypes() throws Exception {
    CelFunctionDecl dummyDecl =
        CelFunctionDecl.newFunctionDeclaration(
            "dummy_all_types",
            CelOverloadDecl.newGlobalOverload(
                "dummy_all_types_overload",
                SimpleType.BOOL,
                SimpleType.UINT,
                SimpleType.DOUBLE,
                SimpleType.BOOL,
                SimpleType.STRING,
                SimpleType.BYTES,
                StructTypeReference.create("test.Message")));
    CelZ3FunctionAxiom dummyAxiom =
        CelZ3FunctionAxiom.newBuilder(dummyDecl)
            .addOverloadTranslator(
                "dummy_all_types_overload",
                (ctx, typeSystem, constraintSink, unwrappedArgs, argApproximations) ->
                    Optional.of(
                        CelZ3OverloadResult.create(
                            typeSystem.wrap(typeSystem.boolCons(), ctx.mkTrue()), ctx.mkFalse())))
            .build();
    CelVerifier verifier =
        CelVerifierZ3Impl.newBuilder().addFunctionAxioms(ImmutableList.of(dummyAxiom)).build();
    Cel cel =
        CelFactory.plannerCelBuilder()
            .addVar("uint_var", SimpleType.UINT)
            .addVar("double_var", SimpleType.DOUBLE)
            .addVar("bool_var", SimpleType.BOOL)
            .addVar("string_var", SimpleType.STRING)
            .addVar("bytes_var", SimpleType.BYTES)
            .addVar("msg_var", StructTypeReference.create("test.Message"))
            .addFunctionDeclarations(dummyDecl)
            .build();
    CelAbstractSyntaxTree ast =
        cel.compile(
                "dummy_all_types(uint_var, double_var, bool_var, string_var, bytes_var, msg_var) =="
                    + " true")
            .getAst();

    CelVerificationResult result = verifier.isAlwaysTrue(ast);
    assertWithMessage(result.message())
        .that(result.status())
        .isEqualTo(VerificationStatus.VERIFIED);
  }

  @Test
  public void isAlwaysTrue_nanAndInfinityConstants_isFalse(
      @TestParameter({
            "request.single_double == double('NaN')",
            "request.single_double == double('Infinity')",
            "request.single_double == double('-Infinity')"
          })
          String expr)
      throws Exception {
    // There are no string literals for NaN or Infinity in CEL. We constant fold these expressions
    // to produce the constant values in the optimized AST.
    CelOptimizer optimizer =
        CelOptimizerFactory.standardCelOptimizerBuilder(CEL)
            .addAstOptimizers(ConstantFoldingOptimizer.getInstance())
            .build();
    CelAbstractSyntaxTree ast = CEL.compile(expr).getAst();
    CelAbstractSyntaxTree optimizedAst = optimizer.optimize(ast);

    assertThat(VERIFIER.isAlwaysTrue(optimizedAst).status()).isEqualTo(VerificationStatus.VIOLATED);
  }

  @Test
  public void isAlwaysTrue_nanAndInfinityConstants_tautology(
      @TestParameter({
            "double('Infinity') == double('Infinity')",
            "double('-Infinity') == double('-Infinity')",
            "double('Infinity') > double('-Infinity')",
            "double('NaN') != double('NaN')"
          })
          String expr)
      throws Exception {
    CelOptimizer optimizer =
        CelOptimizerFactory.standardCelOptimizerBuilder(CEL)
            .addAstOptimizers(ConstantFoldingOptimizer.getInstance())
            .build();
    CelAbstractSyntaxTree ast = CEL.compile(expr).getAst();
    CelAbstractSyntaxTree optimizedAst = optimizer.optimize(ast);

    assertThat(VERIFIER.isAlwaysTrue(optimizedAst).status()).isEqualTo(VerificationStatus.VERIFIED);
  }

  @Test
  public void translateFunctionCall_staticallyResolved_skipsDeadOverloads() throws Exception {
    CelFunctionDecl spyFuncDecl =
        CelFunctionDecl.newFunctionDeclaration(
            "spy_func",
            CelOverloadDecl.newGlobalOverload("spy_func_int", SimpleType.BOOL, SimpleType.INT),
            CelOverloadDecl.newGlobalOverload(
                "spy_func_string", SimpleType.BOOL, SimpleType.STRING));

    List<String> requestedTranslations = new ArrayList<>();
    @SuppressWarnings("Immutable") // test only
    class SpyTranslator implements CelZ3OverloadTranslator {
      private final String overloadId;

      SpyTranslator(String overloadId) {
        this.overloadId = overloadId;
      }

      @Override
      public Optional<CelZ3OverloadResult> translate(
          Context ctx,
          CelZ3TypeSystem typeSystem,
          Consumer<BoolExpr> constraintSink,
          List<Expr<?>> unwrappedArgs,
          List<BoolExpr> argApproximations) {
        requestedTranslations.add(overloadId);
        return Optional.of(
            CelZ3OverloadResult.create(typeSystem.wrapBool(ctx.mkTrue()), ctx.mkFalse()));
      }
    }

    CelZ3FunctionAxiom spyAxiom =
        CelZ3FunctionAxiom.newBuilder(spyFuncDecl)
            .addOverloadTranslator("spy_func_int", new SpyTranslator("spy_func_int"))
            .addOverloadTranslator("spy_func_string", new SpyTranslator("spy_func_string"))
            .build();
    CelVerifier verifier = CelVerifierZ3Impl.newBuilder().addFunctionAxioms(spyAxiom).build();

    Cel cel = CelFactory.plannerCelBuilder().addFunctionDeclarations(spyFuncDecl).build();

    CelAbstractSyntaxTree ast = cel.compile("spy_func(42) == true").getAst();

    verifier.isSatisfiable(ast);

    assertThat(requestedTranslations).containsExactly("spy_func_int");
  }

  @Test
  public void getNumericEqualityWithConstant_skipsIntForDouble() throws Exception {
    CelAbstractSyntaxTree ast = CEL.compile("d == 5.0").getAst();
    try (Context ctx = new Context()) {
      CelAstToZ3Translator translator =
          new CelAstToZ3Translator(
              ctx,
              /* comprehensionUnrollLimit= */ 3,
              /* unknownIdentifiers= */ ImmutableSet.of(),
              /* functionRegistry= */ CelZ3FunctionRegistry.create(ImmutableList.of()),
              /* typeProvider= */ CelVerifierZ3Impl.EMPTY_TYPE_PROVIDER);

      Expr<?> result = translator.translate(ast).z3Expr();
      String resultString = result.toString();

      assertThat(resultString).doesNotContain("getInt");
      assertThat(resultString).doesNotContain("getUint");
      assertThat(resultString).contains("getDouble");
    }
  }

  @Test
  public void getNumericEqualityWithConstant_skipsDoubleForInt() throws Exception {
    CelAbstractSyntaxTree ast = CEL.compile("x == 5").getAst();
    try (Context ctx = new Context()) {
      CelAstToZ3Translator translator =
          new CelAstToZ3Translator(
              ctx,
              /* comprehensionUnrollLimit= */ 3,
              /* unknownIdentifiers= */ ImmutableSet.of(),
              /* functionRegistry= */ CelZ3FunctionRegistry.create(ImmutableList.of()),
              /* typeProvider= */ CelVerifierZ3Impl.EMPTY_TYPE_PROVIDER);

      Expr<?> result = translator.translate(ast).z3Expr();
      String resultString = result.toString();

      assertThat(resultString).contains("getInt");
    }
  }

  @Test
  public void isAlwaysTrue_largeListCounterexample_truncatesOutput() throws Exception {
    Cel cel =
        CelFactory.plannerCelBuilder()
            .addVar("large_list", ListType.create(SimpleType.INT))
            .build();
    StringBuilder listLiteral = new StringBuilder("[");
    for (int i = 0; i < 20; i++) {
      listLiteral.append("1");
      if (i < 19) {
        listLiteral.append(", ");
      }
    }
    listLiteral.append("]");

    CelAbstractSyntaxTree ast = cel.compile("!(large_list == " + listLiteral + ")").getAst();
    CelVerifier verifier =
        CelVerifierFactory.newVerifier().setTimeout(Duration.ofSeconds(10)).build();

    CelVerificationResult result = verifier.isAlwaysTrue(ast);

    assertThat(result.status()).isEqualTo(VerificationStatus.VIOLATED);
    assertThat(result.message()).contains("... (5 more elements)");
  }

  @Test
  public void isAlwaysTrue_customComprehensionWithTrueAccuInit() throws Exception {
    Cel cel =
        CelFactory.plannerCelBuilder()
            .addVar("dyn_list", ListType.create(SimpleType.DYN))
            .addMacros(
                CelMacro.newReceiverMacro(
                    "custom_fold",
                    1,
                    (exprFactory, target, arguments) ->
                        Optional.of(
                            exprFactory.fold(
                                "x",
                                target,
                                "accu",
                                exprFactory.newBoolLiteral(true),
                                exprFactory.newBoolLiteral(true),
                                exprFactory.newGlobalCall(
                                    Operator.LOGICAL_NOT.getFunction(),
                                    exprFactory.newIdentifier("accu")),
                                exprFactory.newIdentifier("accu")))))
            .build();
    CelAbstractSyntaxTree ast =
        cel.compile("dyn_list == [1, 2] ? dyn_list.custom_fold(x) == true : true").getAst();
    CelVerifier verifier = CelVerifierFactory.newVerifier().build();

    CelVerificationResult result = verifier.isAlwaysTrue(ast);

    assertThat(result.status()).isEqualTo(VerificationStatus.VERIFIED);
  }

  @Test
  public void isSatisfiable_maskedByBmcButAlwaysFalse_returnsFailed() throws Exception {
    // The expression is false regardless of what the comprehension evaluates to.
    // The verifier should not be pessimistic and should return FAILED (not INCONCLUSIVE)
    // even though a loop is truncated.
    String expr = "int_list == [1, 2, 3, 4] ? int_list.exists(x, x == 42) && false : false";
    CelAbstractSyntaxTree ast = CEL.compile(expr).getAst();

    CelVerifier verifier = CelVerifierFactory.newVerifier().setComprehensionUnrollLimit(3).build();
    CelVerificationResult result = verifier.isSatisfiable(ast);

    assertThat(result.status()).isEqualTo(VerificationStatus.VIOLATED);
  }

  @Test
  public void verifyEquivalence_maskedByBmcButAlwaysEqual_returnsVerified() throws Exception {
    // Both expressions are always equal (false) regardless of the comprehensions.
    // The verifier should return VERIFIED (not INCONCLUSIVE) even with truncation.
    String exprA = "int_list == [1, 2, 3, 4] ? int_list.all(x, x > 0) && false : false";
    String exprB = "int_list == [1, 2, 3, 4] ? int_list.all(x, x > 1) && false : false";
    CelAbstractSyntaxTree astA = CEL.compile(exprA).getAst();
    CelAbstractSyntaxTree astB = CEL.compile(exprB).getAst();

    CelVerifier verifier = CelVerifierFactory.newVerifier().setComprehensionUnrollLimit(3).build();
    CelVerificationResult result = verifier.verifyEquivalence(astA, astB);

    assertThat(result.status()).isEqualTo(VerificationStatus.VERIFIED);
  }

  private enum EquivalenceZeroUnrollLimitTestCase {
    DIFFERENT_CONSTANT_KINDS("dyn_list.exists(i, i == 0)", "dyn_list.exists(i, i == 0.0)"),
    DIFFERENT_COLLECTION_KINDS("dyn_list.exists(i, i == [])", "dyn_list.exists(i, i == {})"),
    DIFFERENT_CONSTANTS("dyn_list.exists(i, i == 1)", "dyn_list.exists(i, i == 2)"),
    DIFFERENT_VARIABLES("dyn_list.exists(i, i == x)", "dyn_list.exists(i, i == y)");

    final String exprA;
    final String exprB;

    EquivalenceZeroUnrollLimitTestCase(String exprA, String exprB) {
      this.exprA = exprA;
      this.exprB = exprB;
    }
  }

  @Test
  public void verifyEquivalence_zeroUnrollLimit_returnsInconclusive(
      @TestParameter EquivalenceZeroUnrollLimitTestCase testCase) throws Exception {
    CelAbstractSyntaxTree astA = CEL.compile(testCase.exprA).getAst();
    CelAbstractSyntaxTree astB = CEL.compile(testCase.exprB).getAst();

    CelVerifier verifier = CelVerifierFactory.newVerifier().setComprehensionUnrollLimit(0).build();
    CelVerificationResult result = verifier.verifyEquivalence(astA, astB);

    assertThat(result.status()).isEqualTo(VerificationStatus.INCONCLUSIVE);
  }

  @Test
  public void verifyEquivalence_comprehensionScopeShadowing_returnsInconclusive() throws Exception {
    CelMacro macro1 =
        CelMacro.newReceiverMacro(
            "my_macro_1",
            1,
            (exprFactory, target, arguments) ->
                Optional.of(
                    exprFactory.fold(
                        "unused",
                        "x",
                        target,
                        "x",
                        arguments.get(0),
                        exprFactory.newBoolLiteral(true),
                        /* step= */ exprFactory.newIdentifier("x"),
                        /* result= */ exprFactory.newIdentifier("x"))));

    CelMacro macro2 =
        CelMacro.newReceiverMacro(
            "my_macro_2",
            1,
            (exprFactory, target, arguments) ->
                Optional.of(
                    exprFactory.fold(
                        "unused",
                        "y",
                        target,
                        "x",
                        arguments.get(0),
                        exprFactory.newBoolLiteral(true),
                        /* step= */ exprFactory.newIdentifier("y"),
                        /* result= */ exprFactory.newIdentifier("x"))));

    Cel customCel =
        CelFactory.plannerCelBuilder()
            .addVar("dyn_list", ListType.create(SimpleType.DYN))
            .addMacros(macro1, macro2)
            .build();

    CelAbstractSyntaxTree astA = customCel.compile("dyn_list.my_macro_1(true)").getAst();
    CelAbstractSyntaxTree astB = customCel.compile("dyn_list.my_macro_2(true)").getAst();

    CelVerifier verifier = CelVerifierFactory.newVerifier().setComprehensionUnrollLimit(0).build();
    CelVerificationResult result = verifier.verifyEquivalence(astA, astB);

    assertThat(result.status()).isEqualTo(VerificationStatus.INCONCLUSIVE);
  }

  @Test
  public void verifyEquivalence_comprehensionResultScopeIsolation_returnsInconclusive()
      throws Exception {
    CelMacro macro =
        CelMacro.newReceiverMacro(
            "my_macro",
            1,
            (exprFactory, target, arguments) ->
                Optional.of(
                    exprFactory.fold(
                        /* iterVar= */ "x",
                        /* iterRange= */ target,
                        /* accuVar= */ "accu",
                        /* accuInit= */ arguments.get(0),
                        /* condition= */ exprFactory.newBoolLiteral(true),
                        /* step= */ exprFactory.newIdentifier("accu"),
                        /* result= */ exprFactory.newGlobalCall(
                            Operator.CONDITIONAL.getFunction(),
                            exprFactory.newGlobalCall(
                                Operator.EQUALS.getFunction(),
                                exprFactory.newGlobalCall("size", target),
                                exprFactory.newIntLiteral(0L)),
                            exprFactory.newIntLiteral(0L),
                            exprFactory.newIdentifier("x")))));

    Cel customCel =
        CelFactory.plannerCelBuilder()
            .addVar("dyn_list", ListType.create(SimpleType.DYN))
            .addVar("x", SimpleType.INT)
            .addMacros(macro)
            .addCompilerLibraries(CelExtensions.bindings())
            .build();

    CelAbstractSyntaxTree astA =
        customCel.compile("cel.bind(x, 10, dyn_list.my_macro(1))").getAst();
    CelAbstractSyntaxTree astB =
        customCel.compile("cel.bind(x, 20, dyn_list.my_macro(1))").getAst();

    CelVerifier verifier = CelVerifierFactory.newVerifier().setComprehensionUnrollLimit(0).build();
    CelVerificationResult result = verifier.verifyEquivalence(astA, astB);

    assertThat(result.status()).isEqualTo(VerificationStatus.INCONCLUSIVE);
  }

  @Test
  public void verifyEquivalence_timeoutReached_throwsCelVerificationException() throws Exception {
    CelVerifier timeoutVerifier =
        CelVerifierFactory.newVerifier().setTimeout(Duration.ofMillis(1)).build();

    Cel customCel =
        CelFactory.plannerCelBuilder()
            .addVar("d1", SimpleType.DOUBLE)
            .addVar("d2", SimpleType.DOUBLE)
            .addVar("d3", SimpleType.DOUBLE)
            .addVar("d4", SimpleType.DOUBLE)
            .build();

    CelAbstractSyntaxTree astA =
        customCel
            .compile(
                "d1 * d2 * d3 * d4 * d1 * d2 * d3 * d4 == 9429185123491285.0 && d1 > 100000.0 &&"
                    + " d2 > 100000.0 && d3 > 100000.0 && d4 > 100000.0")
            .getAst();
    CelAbstractSyntaxTree astB = customCel.compile("false").getAst();

    CelVerificationException e =
        assertThrows(
            CelVerificationException.class, () -> timeoutVerifier.verifyEquivalence(astA, astB));
    assertThat(e).hasMessageThat().containsMatch("timeout|canceled");
  }

  @Test
  public void verifyImplication_loopExceedsLimit_returnsTruncatedInconclusive() throws Exception {
    CelAbstractSyntaxTree assumeAst =
        CEL.compile("size(int_list) <= 2 && int_list[0] > 0 && int_list[1] > 0").getAst();
    CelAbstractSyntaxTree assertAst = CEL.compile("int_list.all(x, x > 0)").getAst();

    CelVerifier verifier =
        CelVerifierFactory.newVerifier().setComprehensionUnrollLimit(2).build();
    CelVerificationResult result =
        ((CelVerifierZ3Impl) verifier)
            .verifyImplication(assumeAst, assertAst, ImmutableMap.of(), "Implication");

    assertThat(result.status()).isEqualTo(VerificationStatus.INCONCLUSIVE);
    assertThat(result.message())
        .contains("implication holds within the current loop unroll limit");
  }
}
