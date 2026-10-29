// Copyright 2023 Google LLC
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

package dev.cel.extensions;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;
import static org.junit.Assume.assumeFalse;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.primitives.UnsignedLong;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.protobuf.DoubleValue;
import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import com.google.testing.junit.testparameterinjector.TestParameters;
import dev.cel.bundle.Cel;
import dev.cel.bundle.CelBuilder;
import dev.cel.bundle.CelFactory;
import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.CelContainer;
import dev.cel.common.CelFunctionDecl;
import dev.cel.common.CelOptions;
import dev.cel.common.CelOverloadDecl;
import dev.cel.common.CelValidationException;
import dev.cel.common.CelVarDecl;
import dev.cel.common.ast.CelExpr;
import dev.cel.common.ast.CelExpr.CelComprehension;
import dev.cel.common.ast.CelExpr.CelList;
import dev.cel.common.types.CelKind;
import dev.cel.common.types.CelType;
import dev.cel.common.types.ListType;
import dev.cel.common.types.MapType;
import dev.cel.common.types.OptionalType;
import dev.cel.common.types.ProtoMessageTypeProvider;
import dev.cel.common.types.SimpleType;
import dev.cel.common.types.StructTypeReference;
import dev.cel.common.types.TypeType;
import dev.cel.common.values.CelByteString;
import dev.cel.common.values.NullValue;
import dev.cel.common.values.ProtoMessageLiteValueProvider;
import dev.cel.compiler.CelCompiler;
import dev.cel.compiler.CelCompilerBuilder;
import dev.cel.compiler.CelCompilerFactory;
import dev.cel.expr.conformance.proto3.NestedTestAllTypes;
import dev.cel.expr.conformance.proto3.NestedTestAllTypesCelDescriptor;
import dev.cel.expr.conformance.proto3.TestAllTypes;
import dev.cel.expr.conformance.proto3.TestAllTypes.NestedMessage;
import dev.cel.expr.conformance.proto3.TestAllTypesCelDescriptor;
import dev.cel.parser.CelMacro;
import dev.cel.parser.CelStandardMacro;
import dev.cel.runtime.CelAttributePattern;
import dev.cel.runtime.CelEvaluationException;
import dev.cel.runtime.CelFunctionBinding;
import dev.cel.runtime.CelLiteRuntime;
import dev.cel.runtime.CelLiteRuntimeBuilder;
import dev.cel.runtime.CelLiteRuntimeFactory;
import dev.cel.runtime.CelRuntime;
import dev.cel.runtime.CelRuntimeBuilder;
import dev.cel.runtime.CelRuntimeFactory;
import dev.cel.runtime.CelStandardFunctions;
import dev.cel.runtime.CelUnknownSet;
import dev.cel.runtime.PartialVars;
import dev.cel.runtime.Program;
import dev.cel.runtime.RuntimeEquality;
import dev.cel.runtime.RuntimeHelpers;
import dev.cel.runtime.standard.DynFunction;
import dev.cel.runtime.standard.IndexOperator;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(TestParameterInjector.class)
@SuppressWarnings({"unchecked", "SingleTestParameter"})
public class CelOptionalLibraryTest {

  private enum TestMode {
    PLANNER_PARSE_ONLY,
    PLANNER_CHECKED,
    LEGACY_CHECKED,
    LITE_PARSE_ONLY,
    LITE_CHECKED;

    private boolean isParseOnly() {
      return this.equals(PLANNER_PARSE_ONLY) || this.equals(LITE_PARSE_ONLY);
    }

    private boolean isLite() {
      return this.equals(LITE_PARSE_ONLY) || this.equals(LITE_CHECKED);
    }
  }

  @TestParameter TestMode testMode;

  @SuppressWarnings("ImmutableEnumChecker") // Test only
  private enum ConstantTestCases {
    INT("5", "0", SimpleType.INT, 5L),
    STRING("'hello'", "''", SimpleType.STRING, "hello"),
    DOUBLE("5.2", "0.0", SimpleType.DOUBLE, 5.2d),
    UINT("5u", "0u", SimpleType.UINT, UnsignedLong.valueOf(5)),
    BOOL("true", "false", SimpleType.BOOL, true),
    BYTES("b'abc'", "b''", SimpleType.BYTES, CelByteString.copyFromUtf8("abc")),
    DURATION("duration('180s')", "duration('0s')", SimpleType.DURATION, Duration.ofMinutes(3)),
    TIMESTAMP(
        "timestamp(1685552643)",
        "timestamp(0)",
        SimpleType.TIMESTAMP,
        Instant.ofEpochSecond(1685552643));

    private final String sourceWithNonZeroValue;
    private final String sourceWithZeroValue;
    private final CelType type;
    private final Object value;

    ConstantTestCases(
        String sourceWithNonZeroValue, String sourceWithZeroValue, CelType type, Object value) {
      this.sourceWithNonZeroValue = sourceWithNonZeroValue;
      this.sourceWithZeroValue = sourceWithZeroValue;
      this.type = type;
      this.value = value;
    }
  }

  private TestCelBuilder newCelBuilder() {
    return newCelBuilder(Integer.MAX_VALUE);
  }

  // Intentionally testing the deprecated legacy runtime in the test matrix.
  @SuppressWarnings("deprecation")
  private TestCelBuilder newCelBuilder(int version) {
    CelOptions options = CelOptions.current().enableHeterogeneousNumericComparisons(true).build();
    CelContainer container = CelContainer.ofName("cel.expr.conformance.proto3");
    if (testMode.isLite()) {
      CelCompilerBuilder compilerBuilder =
          CelCompilerFactory.standardCelCompilerBuilder()
              .setOptions(options)
              .setStandardMacros(CelStandardMacro.STANDARD_MACROS)
              .setContainer(container)
              .addMessageTypes(TestAllTypes.getDescriptor())
              .addLibraries(CelOptionalCompilerLibrary.optional(version));
      CelLiteRuntimeBuilder liteRuntimeBuilder =
          CelLiteRuntimeFactory.newLiteRuntimeBuilder()
              .setOptions(options)
              .setStandardFunctions(CelStandardFunctions.ALL_STANDARD_FUNCTIONS)
              .setContainer(container)
              .setValueProvider(
                  ProtoMessageLiteValueProvider.newInstance(
                      TestAllTypesCelDescriptor.getDescriptor(),
                      NestedTestAllTypesCelDescriptor.getDescriptor()))
              .setTypeProvider(
                  ProtoMessageTypeProvider.newBuilder()
                      .addFileDescriptors(TestAllTypes.getDescriptor().getFile())
                      .build())
              .addLibraries(CelOptionalRuntimeLibrary.optional(version));
      return new TestCelBuilder(compilerBuilder, liteRuntimeBuilder);
    }

    CelBuilder celBuilder =
        testMode.equals(TestMode.LEGACY_CHECKED)
            ? CelFactory.standardCelBuilder()
            : CelFactory.plannerCelBuilder();

    return new TestCelBuilder(
        celBuilder
            .setOptions(options)
            .setStandardMacros(CelStandardMacro.STANDARD_MACROS)
            .setContainer(container)
            .addMessageTypes(TestAllTypes.getDescriptor())
            .addRuntimeLibraries(CelExtensions.optional(version))
            .addCompilerLibraries(CelExtensions.optional(version)));
  }

  @Test
  public void library() {
    CelExtensionLibrary<?> library =
        CelExtensions.getExtensionLibrary("optional", CelOptions.DEFAULT);
    assertThat(library.name()).isEqualTo("optional");
    assertThat(library.latest().version()).isEqualTo(2);

    // Version 0
    assertThat(library.version(0).functions().stream().map(CelFunctionDecl::name))
        .containsExactly(
            "optional.of",
            "optional.ofNonZeroValue",
            "optional.none",
            "value",
            "hasValue",
            "optional.unwrap",
            "or",
            "orValue",
            "_?._",
            "_[?_]",
            "_[_]");
    assertThat(library.version(0).macros().stream().map(CelMacro::getFunction))
        .containsExactly("optMap");
    assertThat(library.version(0).variables().stream().map(CelVarDecl::name))
        .containsExactly("optional_type");

    // Version 1
    assertThat(library.version(1).functions().stream().map(CelFunctionDecl::name))
        .containsExactly(
            "optional.of",
            "optional.ofNonZeroValue",
            "optional.none",
            "value",
            "hasValue",
            "optional.unwrap",
            "or",
            "orValue",
            "_?._",
            "_[?_]",
            "_[_]");
    assertThat(library.version(1).macros().stream().map(CelMacro::getFunction))
        .containsExactly("optMap", "optFlatMap");
    assertThat(library.version(1).variables().stream().map(CelVarDecl::name))
        .containsExactly("optional_type");

    // Version 2
    assertThat(library.version(2).functions().stream().map(CelFunctionDecl::name))
        .containsExactly(
            "optional.of",
            "optional.ofNonZeroValue",
            "optional.none",
            "value",
            "hasValue",
            "optional.unwrap",
            "or",
            "orValue",
            "_?._",
            "_[?_]",
            "_[_]",
            "first",
            "last");
    assertThat(library.version(2).macros().stream().map(CelMacro::getFunction))
        .containsExactly("optMap", "optFlatMap");
    assertThat(library.version(2).variables().stream().map(CelVarDecl::name))
        .containsExactly("optional_type");
  }

  @Test
  public void optionalOf_constant_success(@TestParameter ConstantTestCases testCase)
      throws Exception {
    TestCel cel = newCelBuilder().setResultType(OptionalType.create(testCase.type)).build();
    String expression = String.format("optional.of(%s)", testCase.sourceWithNonZeroValue);
    CelAbstractSyntaxTree ast = compile(cel, expression);

    Object result = cel.createProgram(ast).eval();

    assertThat(result).isInstanceOf(Optional.class);
    assertThat(result).isEqualTo(Optional.of(testCase.value));
  }

  @Test
  public void optionalType_runtimeEquality(@TestParameter ConstantTestCases testCase)
      throws Exception {
    TestCel cel =
        newCelBuilder()
            .addVar("a", OptionalType.create(testCase.type))
            .addVar("b", OptionalType.create(testCase.type))
            .setResultType(SimpleType.BOOL)
            .build();
    CelAbstractSyntaxTree ast = compile(cel, "a == b");

    boolean result =
        (boolean)
            cel.createProgram(ast)
                .eval(
                    ImmutableMap.of(
                        "a",
                        testCase.sourceWithNonZeroValue,
                        "b",
                        testCase.sourceWithNonZeroValue));

    assertThat(result).isTrue();
  }

  @Test
  public void optionalType_adaptsIntegerToLong_success() throws Exception {
    TestCel cel = newCelBuilder().addVar("a", OptionalType.create(SimpleType.INT)).build();
    CelAbstractSyntaxTree ast = compile(cel, "a");

    Optional<Long> result =
        (Optional<Long>) cel.createProgram(ast).eval(ImmutableMap.of("a", Optional.of(5)));

    assertThat(result).hasValue(5L);
  }

  @Test
  public void optionalType_adaptsFloatToLong_success() throws Exception {
    TestCel cel = newCelBuilder().addVar("a", OptionalType.create(SimpleType.DOUBLE)).build();
    CelAbstractSyntaxTree ast = compile(cel, "a");

    Optional<Long> result =
        (Optional<Long>) cel.createProgram(ast).eval(ImmutableMap.of("a", Optional.of(5.5f)));

    assertThat(result).hasValue(5.5d);
  }

  @Test
  public void optionalOf_nullValue_success() throws Exception {
    TestCel cel = newCelBuilder().setResultType(SimpleType.DYN).build();
    String expression = "optional.of(TestAllTypes{}.single_value)";
    CelAbstractSyntaxTree ast = compile(cel, expression);

    Object result = cel.createProgram(ast).eval();

    assertThat(result).isInstanceOf(Optional.class);
    assertThat(result).isEqualTo(Optional.of(NullValue.NULL_VALUE));
  }

  @Test
  public void optionalOfNonZeroValue_withZeroValue_returnsEmptyOptionalValue(
      @TestParameter ConstantTestCases testCase) throws Exception {
    TestCel cel = newCelBuilder().setResultType(OptionalType.create(testCase.type)).build();
    String expression = String.format("optional.ofNonZeroValue(%s)", testCase.sourceWithZeroValue);
    CelAbstractSyntaxTree ast = compile(cel, expression);

    Object result = cel.createProgram(ast).eval();

    assertThat(result).isInstanceOf(Optional.class);
    assertThat(result).isEqualTo(Optional.empty());
  }

  @Test
  public void optionalOfNonZeroValue_withNonZeroValue_returnsOptionalValue(
      @TestParameter ConstantTestCases testCase) throws Exception {
    TestCel cel = newCelBuilder().setResultType(OptionalType.create(testCase.type)).build();
    String expression =
        String.format("optional.ofNonZeroValue(%s)", testCase.sourceWithNonZeroValue);
    CelAbstractSyntaxTree ast = compile(cel, expression);

    Object result = cel.createProgram(ast).eval();

    assertThat(result).isInstanceOf(Optional.class);
    assertThat(result).isEqualTo(Optional.of(testCase.value));
  }

  @Test
  public void optionalOfNonZeroValue_withNullValue_returnsEmptyOptionalValue() throws Exception {
    TestCel cel = newCelBuilder().setResultType(SimpleType.DYN).build();
    CelAbstractSyntaxTree ast =
        compile(cel, "optional.ofNonZeroValue(TestAllTypes{}.single_value)");

    Object result = cel.createProgram(ast).eval();

    assertThat(result).isInstanceOf(Optional.class);
    assertThat(result).isEqualTo(Optional.empty());
  }

  @Test
  public void optionalOfNonZeroValue_withEmptyMessage_returnsEmptyOptionalValue() throws Exception {
    TestCel cel = newCelBuilder().setResultType(SimpleType.DYN).build();
    CelAbstractSyntaxTree ast = compile(cel, "optional.ofNonZeroValue(TestAllTypes{})");

    Object result = cel.createProgram(ast).eval();

    assertThat(result).isInstanceOf(Optional.class);
    assertThat(result).isEqualTo(Optional.empty());
  }

  @Test
  public void optionalNone_success() throws Exception {
    TestCel cel = newCelBuilder().setResultType(SimpleType.DYN).build();
    CelAbstractSyntaxTree ast = compile(cel, "optional.none()");

    Object result = cel.createProgram(ast).eval();

    assertThat(result).isInstanceOf(Optional.class);
    assertThat(result).isEqualTo(Optional.empty());
  }

  @Test
  public void optionalValue_success(@TestParameter ConstantTestCases testCase) throws Exception {
    TestCel cel = newCelBuilder().setResultType(testCase.type).build();
    String expression = String.format("optional.of(%s).value()", testCase.sourceWithNonZeroValue);
    CelAbstractSyntaxTree ast = compile(cel, expression);

    Object result = cel.createProgram(ast).eval();

    assertThat(result).isEqualTo(testCase.value);
  }

  @Test
  public void optionalValue_whenOptionalValueEmpty_throws() throws Exception {
    TestCel cel = newCelBuilder().build();
    CelAbstractSyntaxTree ast = compile(cel, "optional.none().value()");

    assertThrows(CelEvaluationException.class, () -> cel.createProgram(ast).eval());
  }

  @Test
  public void optionalHasValue_whenOptionalValuePresent_returnsTrue(
      @TestParameter ConstantTestCases testCase) throws Exception {
    TestCel cel = newCelBuilder().setResultType(SimpleType.BOOL).build();
    String expression =
        String.format("optional.of(%s).hasValue()", testCase.sourceWithNonZeroValue);
    CelAbstractSyntaxTree ast = compile(cel, expression);

    Object result = cel.createProgram(ast).eval();

    assertThat(result).isEqualTo(true);
  }

  @Test
  public void optionalHasValue_whenOptionalValueEmpty_returnsFalse(
      @TestParameter ConstantTestCases testCase) throws Exception {
    TestCel cel = newCelBuilder().setResultType(SimpleType.BOOL).build();
    String expression =
        String.format("optional.ofNonZeroValue(%s).hasValue()", testCase.sourceWithZeroValue);
    CelAbstractSyntaxTree ast = compile(cel, expression);

    Object result = cel.createProgram(ast).eval();

    assertThat(result).isEqualTo(false);
  }

  @Test
  public void optionalOr_success() throws Exception {
    TestCel cel =
        newCelBuilder()
            .addVar("x", OptionalType.create(SimpleType.INT))
            .addVar("y", OptionalType.create(SimpleType.INT))
            .setResultType(OptionalType.create(SimpleType.INT))
            .build();
    Program program = cel.createProgram(compile(cel, "x.or(y)"));

    Object resultLhs = program.eval(ImmutableMap.of("x", Optional.of(5), "y", Optional.empty()));
    Object resultRhs = program.eval(ImmutableMap.of("x", Optional.empty(), "y", Optional.of(10)));

    assertThat(resultLhs).isEqualTo(Optional.of(5L));
    assertThat(resultRhs).isEqualTo(Optional.of(10L));
  }

  @Test
  public void optionalOr_shortCircuits() throws Exception {
    TestCel cel =
        newCelBuilder()
            .addVar("x", OptionalType.create(SimpleType.INT))
            .addFunctionDeclarations(
                CelFunctionDecl.newFunctionDeclaration(
                    "errorFunc",
                    CelOverloadDecl.newGlobalOverload(
                        "error_overload", OptionalType.create(SimpleType.INT))))
            .addFunctionBindings(
                CelFunctionBinding.fromOverloads(
                    "errorFunc",
                    CelFunctionBinding.from(
                        "error_overload",
                        ImmutableList.of(),
                        val -> {
                          throw new IllegalStateException(
                              "This function should not have been called!");
                        })))
            .setResultType(OptionalType.create(SimpleType.INT))
            .build();
    Program program = cel.createProgram(compile(cel, "x.or(errorFunc())"));

    Object resultLhs = program.eval(ImmutableMap.of("x", Optional.of(5)));

    assertThat(resultLhs).isEqualTo(Optional.of(5L));
  }

  @Test
  public void optionalOr_producesNonOptionalValue_throws() throws Exception {
    TestCel cel = newCelBuilder().addVar("x", OptionalType.create(SimpleType.INT)).build();

    CelAbstractSyntaxTree ast = compile(cel, "x.or(optional.of(10))");

    CelEvaluationException e =
        assertThrows(
            CelEvaluationException.class,
            () -> cel.createProgram(ast).eval(ImmutableMap.of("x", 5L)));
    assertThat(e)
        .hasMessageThat()
        .contains("evaluation error at <input>:4: No matching overload for function 'or'.");
  }

  @Test
  public void optionalOrValue_lhsHasValue_success(@TestParameter ConstantTestCases testCase)
      throws Exception {
    TestCel cel = newCelBuilder().setResultType(testCase.type).build();
    String expression =
        String.format(
            "optional.of(%s).orValue(%s)",
            testCase.sourceWithNonZeroValue, testCase.sourceWithZeroValue);
    CelAbstractSyntaxTree ast = compile(cel, expression);

    Object result = cel.createProgram(ast).eval();

    assertThat(result).isEqualTo(testCase.value);
  }

  @Test
  public void optionalOrValue_shortCircuits() throws Exception {
    TestCel cel =
        newCelBuilder()
            .addVar("x", OptionalType.create(SimpleType.INT))
            .addFunctionDeclarations(
                CelFunctionDecl.newFunctionDeclaration(
                    "errorFunc",
                    CelOverloadDecl.newGlobalOverload("error_overload", SimpleType.INT)))
            .addFunctionBindings(
                CelFunctionBinding.fromOverloads(
                    "errorFunc",
                    CelFunctionBinding.from(
                        "error_overload",
                        ImmutableList.of(),
                        val -> {
                          throw new IllegalStateException(
                              "This function should not have been called!");
                        })))
            .setResultType(SimpleType.INT)
            .build();
    Program program = cel.createProgram(compile(cel, "x.orValue(errorFunc())"));

    Object resultLhs = program.eval(ImmutableMap.of("x", Optional.of(5)));

    assertThat(resultLhs).isEqualTo(5);
  }

  @Test
  public void optionalOrValue_rhsHasValue_success(@TestParameter ConstantTestCases testCase)
      throws Exception {
    TestCel cel = newCelBuilder().setResultType(testCase.type).build();
    String expression =
        String.format("optional.none().orValue(%s)", testCase.sourceWithNonZeroValue);
    CelAbstractSyntaxTree ast = compile(cel, expression);

    Object result = cel.createProgram(ast).eval();

    assertThat(result).isEqualTo(testCase.value);
  }

  @Test
  @TestParameters("{source: optional.of(5).orValue(optional.of(10))}")
  @TestParameters("{source: optional.of(5).orValue(optional.none())}")
  @TestParameters("{source: 5.orValue(optional.of(10))}")
  @TestParameters("{source: 5.orValue(optional.none())}")
  public void optionalOrValue_unmatchingTypes_throwsCompilationException(String source) {
    if (testMode.isParseOnly()) {
      return;
    }
    TestCel cel = newCelBuilder().build();

    CelValidationException e =
        assertThrows(CelValidationException.class, () -> compile(cel, source));
    assertThat(e).hasMessageThat().contains("found no matching overload for 'orValue'");
  }

  @Test
  public void optionalOrValue_producesNonOptionalValue_throws() throws Exception {
    TestCel cel = newCelBuilder().addVar("x", OptionalType.create(SimpleType.INT)).build();

    CelAbstractSyntaxTree ast = compile(cel, "x.orValue(10)");

    CelEvaluationException e =
        assertThrows(
            CelEvaluationException.class,
            () -> cel.createProgram(ast).eval(ImmutableMap.of("x", 5)));
    assertThat(e)
        .hasMessageThat()
        .contains("evaluation error at <input>:9: No matching overload for function 'orValue'.");
  }

  @Test
  @TestParameters("{source: optional.ofNonZeroValue(42).or(optional.of(10)).value() == 42}")
  @TestParameters("{source: optional.none().or(optional.none()).orValue(42) == 42}")
  public void optionalChainedFunctions_constants_success(String source) throws Exception {
    TestCel cel = newCelBuilder().setResultType(SimpleType.BOOL).build();
    CelAbstractSyntaxTree ast = compile(cel, source);

    boolean result = (boolean) cel.createProgram(ast).eval();

    assertThat(result).isTrue();
  }

  @Test
  public void optionalChainedFunctions_nestedMaps_success() throws Exception {
    TestCel cel =
        newCelBuilder()
            .addVar(
                "m",
                MapType.create(
                    SimpleType.STRING, MapType.create(SimpleType.STRING, SimpleType.STRING)))
            .setResultType(SimpleType.STRING)
            .build();
    String expression =
        "optional.ofNonZeroValue('').or(optional.of(m.c['dashed-index'])).orValue('default value')";
    CelAbstractSyntaxTree ast = compile(cel, expression);

    String result =
        (String)
            cel.createProgram(ast)
                .eval(
                    ImmutableMap.of(
                        "m", ImmutableMap.of("c", ImmutableMap.of("dashed-index", "goodbye"))));

    assertThat(result).isEqualTo("goodbye");
  }

  @Test
  public void optionalChainedFunctions_nestedMapsInvalidAccess_throws() throws Exception {
    TestCel cel =
        newCelBuilder()
            .addVar(
                "m",
                MapType.create(
                    SimpleType.STRING, MapType.create(SimpleType.STRING, SimpleType.STRING)))
            .setResultType(SimpleType.STRING)
            .build();
    String expression = "optional.ofNonZeroValue(m.a.z).orValue(m.c['dashed-index'])";
    CelAbstractSyntaxTree ast = compile(cel, expression);

    CelEvaluationException e =
        assertThrows(
            CelEvaluationException.class,
            () ->
                cel.createProgram(ast)
                    .eval(
                        ImmutableMap.of(
                            "m",
                            ImmutableMap.of("c", ImmutableMap.of("dashed-index", "goodbye")))));
    assertThat(e).hasMessageThat().contains("key 'a' is not present in map.");
  }

  @Test
  public void optionalFieldSelection_onMap_returnsOptionalEmpty() throws Exception {
    TestCel cel = newCelBuilder().setResultType(OptionalType.create(SimpleType.INT)).build();
    CelAbstractSyntaxTree ast = compile(cel, "{'a': 2}.?x");

    Object result = cel.createProgram(ast).eval();

    assertThat(result).isEqualTo(Optional.empty());
  }

  @Test
  public void optionalFieldSelection_onMap_returnsOptionalValue() throws Exception {
    TestCel cel = newCelBuilder().setResultType(OptionalType.create(SimpleType.INT)).build();
    CelAbstractSyntaxTree ast = compile(cel, "{'a': 2}.?a");

    Optional<Long> result = (Optional<Long>) cel.createProgram(ast).eval();

    assertThat(result).hasValue(2L);
  }

  @Test
  public void optionalFieldSelection_onMap_chained_returnsSinglyWrappedOptional() throws Exception {
    TestCel cel = newCelBuilder().setResultType(OptionalType.create(SimpleType.STRING)).build();
    CelAbstractSyntaxTree ast = compile(cel, "{'foo': {'bar': 'baz'}}.?foo.?bar");

    Optional<String> result = (Optional<String>) cel.createProgram(ast).eval();

    assertThat(result).hasValue("baz");
  }

  @Test
  @TestParameters("{source: \"{'a': optional.none()}.?a\"}")
  @TestParameters("{source: \"optional.of({'a': optional.none()}).?a\"}")
  @TestParameters("{source: \"optional.of({'a': optional.none()}).a\"}")
  public void optionalFieldSelection_onMapWithEmptyOptionalValue_returnsNestedOptionalEmpty(
      String source) throws Exception {
    TestCel cel = newCelBuilder().build();
    CelAbstractSyntaxTree ast = compile(cel, source);

    Optional<Optional<?>> result = (Optional<Optional<?>>) cel.createProgram(ast).eval();

    assertThat(result).hasValue(Optional.empty());
  }

  @Test
  @TestParameters("{source: \"{'a': optional.of(1)}.?a\"}")
  @TestParameters("{source: \"optional.of({'a': optional.of(1)}).?a\"}")
  @TestParameters("{source: \"optional.of({'a': optional.of(1)}).a\"}")
  public void optionalFieldSelection_onMapWithPresentOptionalValue_returnsNestedOptionalValue(
      String source) throws Exception {
    TestCel cel = newCelBuilder().build();
    CelAbstractSyntaxTree ast = compile(cel, source);

    Optional<Optional<Long>> result = (Optional<Optional<Long>>) cel.createProgram(ast).eval();

    assertThat(result).hasValue(Optional.of(1L));
  }

  @Test
  public void optionalFieldSelection_onProtoMessage_returnsOptionalEmpty() throws Exception {
    TestCel cel =
        newCelBuilder()
            .setResultType(OptionalType.create(SimpleType.INT))
            .addVar("msg", StructTypeReference.create(TestAllTypes.getDescriptor().getFullName()))
            .build();
    CelAbstractSyntaxTree ast = compile(cel, "msg.?single_int32");

    Optional<Long> result =
        (Optional<Long>)
            cel.createProgram(ast).eval(ImmutableMap.of("msg", TestAllTypes.getDefaultInstance()));

    assertThat(result).isEmpty();
  }

  @Test
  public void optionalFieldSelection_onProtoMessage_chained_returnsSinglyWrappedOptional()
      throws Exception {
    TestCel cel =
        newCelBuilder()
            .setResultType(OptionalType.create(SimpleType.INT))
            .addVar(
                "msg", StructTypeReference.create(NestedTestAllTypes.getDescriptor().getFullName()))
            .build();
    CelAbstractSyntaxTree ast = compile(cel, "msg.?payload.?single_int32");

    Optional<Long> result =
        (Optional<Long>)
            cel.createProgram(ast)
                .eval(
                    ImmutableMap.of(
                        "msg",
                        NestedTestAllTypes.newBuilder()
                            .setPayload(TestAllTypes.newBuilder().setSingleInt32(5).build())
                            .build()));

    assertThat(result).hasValue(5L);
  }

  @Test
  public void optionalFieldSelection_onProtoMessage_returnsOptionalValue() throws Exception {
    TestCel cel =
        newCelBuilder()
            .setResultType(OptionalType.create(SimpleType.INT))
            .addVar("msg", StructTypeReference.create(TestAllTypes.getDescriptor().getFullName()))
            .build();
    CelAbstractSyntaxTree ast = compile(cel, "msg.?single_int32");

    Optional<Long> result =
        (Optional<Long>)
            cel.createProgram(ast)
                .eval(ImmutableMap.of("msg", TestAllTypes.newBuilder().setSingleInt32(5).build()));

    assertThat(result).hasValue(5L);
  }

  @Test
  public void optionalFieldSelection_onProtoMessage_listValue() throws Exception {
    // TODO: Message creation with prepopulated fields is not yet supported in
    // CelLiteRuntime.
    assumeFalse(testMode.isLite());
    TestCel cel = newCelBuilder().build();
    CelAbstractSyntaxTree ast =
        compile(
            cel, "optional.of(TestAllTypes{repeated_string: ['foo']}).?repeated_string.value()");

    List<String> result = (List<String>) cel.createProgram(ast).eval();

    assertThat(result).containsExactly("foo");
  }

  @Test
  public void optionalFieldSelection_onProtoMessage_indexValue() throws Exception {
    // TODO: Message creation with prepopulated fields is not yet supported in
    // CelLiteRuntime.
    assumeFalse(testMode.isLite());
    TestCel cel = newCelBuilder().build();
    CelAbstractSyntaxTree ast =
        compile(
            cel, "optional.of(TestAllTypes{repeated_string: ['foo']}).?repeated_string[0].value()");

    String result = (String) cel.createProgram(ast).eval();

    assertThat(result).isEqualTo("foo");
  }

  @Test
  public void optionalFieldSelection_onProtoMessage_chainedSuccess() throws Exception {
    TestCel cel =
        newCelBuilder()
            .setResultType(OptionalType.create(SimpleType.INT))
            .addVar(
                "m",
                MapType.create(
                    SimpleType.STRING,
                    MapType.create(
                        SimpleType.STRING,
                        StructTypeReference.create(TestAllTypes.getDescriptor().getFullName()))))
            .build();
    CelAbstractSyntaxTree ast =
        compile(cel, "m.?c.missing.or(m.?c['dashed-index']).value().?single_int32");

    Optional<Long> result =
        (Optional<Long>)
            cel.createProgram(ast)
                .eval(
                    ImmutableMap.of(
                        "m",
                        ImmutableMap.of(
                            "c",
                            ImmutableMap.of(
                                "dashed-index",
                                TestAllTypes.newBuilder().setSingleInt32(5).build()))));

    assertThat(result).hasValue(5L);
  }

  @Test
  public void optionalFieldSelection_indexerOnProtoMessage_typeCheck_throwsException() {
    if (testMode.isParseOnly()) {
      return;
    }
    TestCel cel =
        newCelBuilder()
            .setResultType(OptionalType.create(SimpleType.INT))
            .addVar("msg", StructTypeReference.create(TestAllTypes.getDescriptor().getFullName()))
            .build();

    CelValidationException e =
        assertThrows(CelValidationException.class, () -> compile(cel, "msg[?single_int32]"));

    assertThat(e).hasMessageThat().contains("undeclared reference to 'single_int32'");
  }

  @Test
  public void optionalFieldSelection_onProtoMessage_presenceTest() throws Exception {
    TestCel cel =
        newCelBuilder()
            .addVar("msg", StructTypeReference.create(TestAllTypes.getDescriptor().getFullName()))
            .setResultType(SimpleType.BOOL)
            .build();
    CelAbstractSyntaxTree ast =
        compile(cel, "!has(msg.?single_nested_message.bb) && has(msg.?standalone_message.bb)");

    boolean result =
        (boolean)
            cel.createProgram(ast)
                .eval(
                    ImmutableMap.of(
                        "msg",
                        TestAllTypes.newBuilder()
                            .setStandaloneMessage(NestedMessage.newBuilder().setBb(5).build())
                            .build()));

    assertThat(result).isTrue();
  }

  @Test
  @TestParameters("{source: \"{'a': 2}.?a.hasValue()\", expectedResult: true}")
  @TestParameters("{source: \"{'a': 2}.?x.hasValue()\", expectedResult: false}")
  public void optionalFieldSelection_onMap_hasValueReturnsBoolean(
      String source, boolean expectedResult) throws Exception {
    TestCel cel = newCelBuilder().setResultType(SimpleType.BOOL).build();
    CelAbstractSyntaxTree ast = compile(cel, source);

    boolean result = (boolean) cel.createProgram(ast).eval();

    assertThat(result).isEqualTo(expectedResult);
  }

  @Test
  public void optionalFieldSelection_onMap_hasMacroReturnsTrue() throws Exception {
    TestCel cel =
        newCelBuilder()
            .addVar(
                "m",
                MapType.create(
                    SimpleType.STRING, MapType.create(SimpleType.STRING, SimpleType.STRING)))
            .setResultType(SimpleType.BOOL)
            .build();
    CelAbstractSyntaxTree ast = compile(cel, "has(m.?x.y)");

    boolean result =
        (boolean)
            cel.createProgram(ast)
                .eval(ImmutableMap.of("m", ImmutableMap.of("x", ImmutableMap.of("y", "z"))));

    assertThat(result).isTrue();
  }

  @Test
  public void optionalFieldSelection_onMap_hasMacroReturnsFalse() throws Exception {
    TestCel cel =
        newCelBuilder()
            .addVar(
                "m",
                MapType.create(
                    SimpleType.STRING, MapType.create(SimpleType.STRING, SimpleType.STRING)))
            .setResultType(SimpleType.BOOL)
            .build();
    CelAbstractSyntaxTree ast = compile(cel, "has(m.?x.y)");

    boolean result = (boolean) cel.createProgram(ast).eval(ImmutableMap.of("m", ImmutableMap.of()));

    assertThat(result).isFalse();
  }

  @Test
  public void optionalFieldSelection_onOptionalMap_presenceTest() throws Exception {
    TestCel cel =
        newCelBuilder()
            .addVar(
                "optm",
                OptionalType.create(
                    MapType.create(
                        SimpleType.STRING, MapType.create(SimpleType.STRING, SimpleType.STRING))))
            .setResultType(SimpleType.BOOL)
            .build();
    CelAbstractSyntaxTree ast = compile(cel, "has(optm.c) && !has(optm.c.missing)");

    boolean result =
        (boolean)
            cel.createProgram(ast)
                .eval(
                    ImmutableMap.of(
                        "optm",
                        Optional.of(
                            ImmutableMap.of("c", ImmutableMap.of("dashed-index", "goodbye")))));

    assertThat(result).isTrue();
  }

  @Test
  public void optionalIndex_onOptionalMap_returnsOptionalValue() throws Exception {
    TestCel cel =
        newCelBuilder()
            .addVar(
                "optm",
                OptionalType.create(
                    MapType.create(
                        SimpleType.STRING, MapType.create(SimpleType.STRING, SimpleType.STRING))))
            .setResultType(OptionalType.create(SimpleType.STRING))
            .build();
    CelAbstractSyntaxTree ast = compile(cel, "optm.c[?'dashed-index']");

    Object result =
        cel.createProgram(ast)
            .eval(
                ImmutableMap.of(
                    "optm",
                    Optional.of(ImmutableMap.of("c", ImmutableMap.of("dashed-index", "goodbye")))));

    assertThat(result).isEqualTo(Optional.of("goodbye"));
  }

  @Test
  public void optionalIndex_onOptionalMap_returnsOptionalEmpty() throws Exception {
    TestCel cel =
        newCelBuilder()
            .addVar(
                "optm",
                OptionalType.create(
                    MapType.create(
                        SimpleType.STRING, MapType.create(SimpleType.STRING, SimpleType.STRING))))
            .setResultType(OptionalType.create(SimpleType.STRING))
            .build();
    CelAbstractSyntaxTree ast = compile(cel, "optm.c[?'dashed-index']");
    Program program = cel.createProgram(ast);

    assertThat(program.eval(ImmutableMap.of("optm", Optional.empty()))).isEqualTo(Optional.empty());
    assertThat(
            program.eval(
                ImmutableMap.of("optm", Optional.of(ImmutableMap.of("c", ImmutableMap.of())))))
        .isEqualTo(Optional.empty());
  }

  @Test
  public void traditionalIndex_onOptionalMap_returnsOptionalEmpty() throws Exception {
    TestCel cel =
        newCelBuilder()
            .addVar(
                "optm", OptionalType.create(MapType.create(SimpleType.STRING, SimpleType.STRING)))
            .setResultType(OptionalType.create(SimpleType.STRING))
            .build();
    CelAbstractSyntaxTree ast = compile(cel, "optm['dashed-index']");
    Program program = cel.createProgram(ast);

    assertThat(program.eval(ImmutableMap.of("optm", Optional.empty()))).isEqualTo(Optional.empty());
    assertThat(program.eval(ImmutableMap.of("optm", Optional.of(ImmutableMap.of()))))
        .isEqualTo(Optional.empty());
  }

  @Test
  public void optionalIndex_onMap_returnsOptionalEmpty() throws Exception {
    TestCel cel =
        newCelBuilder()
            .addVar(
                "m",
                MapType.create(
                    SimpleType.STRING, MapType.create(SimpleType.STRING, SimpleType.STRING)))
            .setResultType(OptionalType.create(SimpleType.STRING))
            .build();
    CelAbstractSyntaxTree ast = compile(cel, "m.c[?'dashed-index']");

    Object result =
        cel.createProgram(ast).eval(ImmutableMap.of("m", ImmutableMap.of("c", ImmutableMap.of())));

    assertThat(result).isEqualTo(Optional.empty());
  }

  @Test
  public void optionalIndex_onMap_returnsOptionalValue() throws Exception {
    TestCel cel =
        newCelBuilder()
            .addVar(
                "m",
                MapType.create(
                    SimpleType.STRING, MapType.create(SimpleType.STRING, SimpleType.STRING)))
            .setResultType(OptionalType.create(SimpleType.STRING))
            .build();
    CelAbstractSyntaxTree ast = compile(cel, "m.c[?'dashed-index']");

    Object result =
        cel.createProgram(ast)
            .eval(
                ImmutableMap.of(
                    "m", ImmutableMap.of("c", ImmutableMap.of("dashed-index", "goodbye"))));

    assertThat(result).isEqualTo(Optional.of("goodbye"));
  }

  @Test
  @TestParameters("{source: '{?x: optional.of(1)}'}")
  @TestParameters("{source: '{?1: x}'}")
  @TestParameters("{source: '{?x: x}'}")
  public void optionalIndex_onMapWithUnknownInput_returnsUnknownResult(String source)
      throws Exception {
    TestCel cel = newCelBuilder().addVar("x", OptionalType.create(SimpleType.INT)).build();
    CelAbstractSyntaxTree ast = compile(cel, source);

    Object result =
        cel.createProgram(ast)
            .eval(PartialVars.of(CelAttributePattern.fromQualifiedIdentifier("x")));

    assertThat(result).isInstanceOf(CelUnknownSet.class);
  }

  @Test
  public void optionalIndex_onOptionalMapUsingFieldSelection_returnsOptionalValue()
      throws Exception {
    TestCel cel =
        newCelBuilder()
            .addVar(
                "m",
                MapType.create(
                    SimpleType.STRING, MapType.create(SimpleType.STRING, SimpleType.STRING)))
            .setResultType(OptionalType.create(SimpleType.STRING))
            .build();
    CelAbstractSyntaxTree ast = compile(cel, "{?'key': optional.of('test')}.?key");

    Object result = cel.createProgram(ast).eval();

    assertThat(result).isEqualTo(Optional.of("test"));
  }

  @Test
  public void optionalIndex_onList_returnsOptionalEmpty() throws Exception {
    TestCel cel =
        newCelBuilder()
            .addVar("l", ListType.create(SimpleType.STRING))
            .setResultType(OptionalType.create(SimpleType.STRING))
            .build();
    CelAbstractSyntaxTree ast = compile(cel, "l[?0]");
    Program program = cel.createProgram(ast);

    assertThat(program.eval(ImmutableMap.of("l", ImmutableList.of()))).isEqualTo(Optional.empty());
  }

  @Test
  public void optionalIndex_onList_returnsOptionalValue() throws Exception {
    TestCel cel =
        newCelBuilder()
            .addVar("l", ListType.create(SimpleType.STRING))
            .setResultType(OptionalType.create(SimpleType.STRING))
            .build();
    CelAbstractSyntaxTree ast = compile(cel, "l[?0]");

    Object result = cel.createProgram(ast).eval(ImmutableMap.of("l", ImmutableList.of("hello")));

    assertThat(result).isEqualTo(Optional.of("hello"));
  }

  @Test
  public void optionalIndex_onOptionalList_returnsOptionalEmpty() throws Exception {
    TestCel cel =
        newCelBuilder()
            .addVar("optl", OptionalType.create(ListType.create(SimpleType.STRING)))
            .setResultType(OptionalType.create(SimpleType.STRING))
            .build();
    CelAbstractSyntaxTree ast = compile(cel, "optl[?0]");
    Program program = cel.createProgram(ast);

    assertThat(program.eval(ImmutableMap.of("optl", Optional.empty()))).isEqualTo(Optional.empty());
    assertThat(program.eval(ImmutableMap.of("optl", Optional.of(ImmutableList.of()))))
        .isEqualTo(Optional.empty());
  }

  @Test
  public void optionalIndex_onOptionalList_returnsOptionalValue() throws Exception {
    TestCel cel =
        newCelBuilder()
            .addVar("optl", OptionalType.create(ListType.create(SimpleType.STRING)))
            .setResultType(OptionalType.create(SimpleType.STRING))
            .build();
    CelAbstractSyntaxTree ast = compile(cel, "optl[?0]");

    Object result =
        cel.createProgram(ast)
            .eval(ImmutableMap.of("optl", Optional.of(ImmutableList.of("hello"))));

    assertThat(result).isEqualTo(Optional.of("hello"));
  }

  @Test
  public void optionalIndex_outOfBounds_returnsOptionalEmpty(
      @TestParameter({
            "l[?-1]",
            "l[?1]",
            "l[?4294967296]",
            "l[?-4294967296]",
            "optl[?4294967296]",
            "optl[4294967296]"
          })
          String expression)
      throws Exception {
    TestCel cel =
        newCelBuilder()
            .addVar("l", ListType.create(SimpleType.STRING))
            .addVar("optl", OptionalType.create(ListType.create(SimpleType.STRING)))
            .setResultType(OptionalType.create(SimpleType.STRING))
            .build();
    CelAbstractSyntaxTree ast = compile(cel, expression);

    Object result =
        cel.createProgram(ast)
            .eval(
                ImmutableMap.of(
                    "l",
                    ImmutableList.of("hello"),
                    "optl",
                    Optional.of(ImmutableList.of("hello"))));

    assertThat(result).isEqualTo(Optional.empty());
  }

  @Test
  public void optionalIndex_onListWithUnknownInput_returnsUnknownResult() throws Exception {
    TestCel cel =
        newCelBuilder()
            .addVar("x", OptionalType.create(SimpleType.INT))
            .setResultType(ListType.create(SimpleType.INT))
            .build();
    CelAbstractSyntaxTree ast = compile(cel, "[?x]");

    Object result =
        cel.createProgram(ast)
            .eval(PartialVars.of(CelAttributePattern.fromQualifiedIdentifier("x")));

    assertThat(result).isInstanceOf(CelUnknownSet.class);
  }

  @Test
  public void traditionalIndex_onOptionalList_returnsOptionalEmpty() throws Exception {
    TestCel cel =
        newCelBuilder()
            .addVar("optl", OptionalType.create(ListType.create(SimpleType.STRING)))
            .setResultType(OptionalType.create(SimpleType.STRING))
            .build();
    CelAbstractSyntaxTree ast = compile(cel, "optl[0]");

    Object result = cel.createProgram(ast).eval(ImmutableMap.of("optl", Optional.empty()));

    assertThat(result).isEqualTo(Optional.empty());
  }

  @Test
  public void optionalFieldSelect_fieldMarkedUnknown_returnsUnknownSet() throws Exception {
    if (testMode.equals(TestMode.LEGACY_CHECKED)) {
      // This case is not possible to setup for legacy runtime
      return;
    }

    TestCel cel =
        newCelBuilder()
            .addVar("msg", StructTypeReference.create(TestAllTypes.getDescriptor().getFullName()))
            .build();
    CelAbstractSyntaxTree ast = compile(cel, "msg.?single_int32");

    Object result =
        cel.createProgram(ast)
            .eval(
                PartialVars.of(
                    ImmutableMap.of("msg", TestAllTypes.newBuilder().setSingleInt32(42).build()),
                    CelAttributePattern.fromQualifiedIdentifier("msg.single_int32")));

    assertThat(result).isInstanceOf(CelUnknownSet.class);
  }

  @Test
  // LHS
  @TestParameters("{expression: 'optx.or(optional.of(1))'}")
  @TestParameters("{expression: 'optx.orValue(1)'}")
  // RHS
  @TestParameters("{expression: 'optional.none().or(optx)'}")
  @TestParameters("{expression: 'optional.none().orValue(optx)'}")
  public void optionalChainedFunctions_lhsIsUnknown_returnsUnknown(String expression)
      throws Exception {
    TestCel cel =
        newCelBuilder()
            .addVar("optx", OptionalType.create(SimpleType.INT))
            .addVar("x", SimpleType.INT)
            .build();
    CelAbstractSyntaxTree ast = compile(cel, expression);

    Object result =
        cel.createProgram(ast)
            .eval(PartialVars.of(CelAttributePattern.fromQualifiedIdentifier("optx")));

    assertThat(result).isInstanceOf(CelUnknownSet.class);
  }

  @Test
  // LHS
  @TestParameters("{expression: 'optional.of(1/0).or(optional.of(1))'}")
  @TestParameters("{expression: 'optional.of(1/0).orValue(1)'}")
  // RHS
  @TestParameters("{expression: 'optional.none().or(optional.of(1/0))'}")
  @TestParameters("{expression: 'optional.none().orValue(1/0)'}")
  public void optionalChainedFunctions_lhsIsError_returnsError(String expression) throws Exception {
    TestCel cel =
        newCelBuilder()
            .addVar("optx", OptionalType.create(SimpleType.INT))
            .addVar("x", SimpleType.INT)
            .build();
    CelAbstractSyntaxTree ast = compile(cel, expression);

    assertThrows(CelEvaluationException.class, () -> cel.createProgram(ast).eval());
  }

  @Test
  public void traditionalIndex_onOptionalList_returnsOptionalValue() throws Exception {
    TestCel cel =
        newCelBuilder()
            .addVar("optl", OptionalType.create(ListType.create(SimpleType.STRING)))
            .setResultType(OptionalType.create(SimpleType.STRING))
            .build();
    CelAbstractSyntaxTree ast = compile(cel, "optl[0]");

    Object result =
        cel.createProgram(ast)
            .eval(ImmutableMap.of("optl", Optional.of(ImmutableList.of("list-value"))));

    assertThat(result).isEqualTo(Optional.of("list-value"));
  }

  @Test
  @TestParameters("{source: \"m.?c.missing.or(m.?c['dashed-index']).orValue('').size() == 7\"}")
  @TestParameters("{source: \"m.c[?'dashed-index'].orValue('default value') == 'goodbye'\"}")
  @TestParameters("{source: \"m.c[?'missing-index'].orValue('default value') == 'default value'\"}")
  public void optionalFieldSelection_onMap_chainedWithSelectorAndIndexer(String source)
      throws Exception {
    TestCel cel =
        newCelBuilder()
            .addVar(
                "m",
                MapType.create(
                    SimpleType.STRING, MapType.create(SimpleType.STRING, SimpleType.STRING)))
            .setResultType(SimpleType.BOOL)
            .build();
    CelAbstractSyntaxTree ast = compile(cel, source);

    boolean result =
        (boolean)
            cel.createProgram(ast)
                .eval(
                    ImmutableMap.of(
                        "m", ImmutableMap.of("c", ImmutableMap.of("dashed-index", "goodbye"))));

    assertThat(result).isTrue();
  }

  @Test
  @TestParameters("{source: \"optm.c.index.orValue('default value') == 'goodbye'\"}")
  @TestParameters("{source: \"optm.c['index'].orValue('default value') == 'goodbye'\"}")
  @TestParameters("{source: \"optm.c.missing.orValue('default value') == 'default value'\"}")
  @TestParameters("{source: \"optm.c['missing'].orValue('default value') == 'default value'\"}")
  public void traditionalIndexSelection_onOptionalMap_chainedOperatorSuccess(String source)
      throws Exception {
    TestCel cel =
        newCelBuilder()
            .addVar(
                "optm",
                OptionalType.create(
                    MapType.create(
                        SimpleType.STRING, MapType.create(SimpleType.STRING, SimpleType.STRING))))
            .setResultType(SimpleType.BOOL)
            .build();
    CelAbstractSyntaxTree ast = compile(cel, source);

    boolean result =
        (boolean)
            cel.createProgram(ast)
                .eval(
                    ImmutableMap.of(
                        "optm",
                        Optional.of(ImmutableMap.of("c", ImmutableMap.of("index", "goodbye")))));

    assertThat(result).isTrue();
  }

  @Test
  public void traditionalIndexSelection_onOptionalMap_orChainedList() throws Exception {
    TestCel cel =
        newCelBuilder()
            .addVar(
                "optm",
                OptionalType.create(
                    MapType.create(
                        SimpleType.STRING, MapType.create(SimpleType.STRING, SimpleType.STRING))))
            .addVar("optl", OptionalType.create(ListType.create(SimpleType.STRING)))
            .setResultType(SimpleType.STRING)
            .build();

    CelAbstractSyntaxTree ast = compile(cel, "optm.c.missing.or(optl[0]).orValue('default value')");

    String result =
        (String)
            cel.createProgram(ast)
                .eval(
                    ImmutableMap.of(
                        "optm",
                        Optional.of(ImmutableMap.of("c", ImmutableMap.of("index", "goodbye"))),
                        "optl",
                        Optional.of(ImmutableList.of("list-value"))));

    assertThat(result).isEqualTo("list-value");
  }

  @Test
  public void optionalMapCreation_valueIsEmpty_returnsEmptyMap() throws Exception {
    TestCel cel = newCelBuilder().build();
    CelAbstractSyntaxTree ast = compile(cel, "{?'key': optional.none()}");

    Map<String, Object> result = (Map<String, Object>) cel.createProgram(ast).eval();

    assertThat(result).isEmpty();
  }

  @Test
  public void optionalMapCreation_valueIsPresent_returnsMap() throws Exception {
    TestCel cel = newCelBuilder().build();
    CelAbstractSyntaxTree ast = compile(cel, "{?'key': optional.of(5)}");

    Map<String, Object> result = (Map<String, Object>) cel.createProgram(ast).eval();

    assertThat(result).containsExactly("key", 5L);
  }

  @Test
  public void optionalMapCreation_withNestedMap_returnsNestedMap() throws Exception {
    TestCel cel =
        newCelBuilder()
            .setResultType(
                MapType.create(
                    SimpleType.STRING,
                    MapType.create(
                        SimpleType.STRING, MapType.create(SimpleType.STRING, SimpleType.STRING))))
            .addVar(
                "m",
                MapType.create(
                    SimpleType.STRING, MapType.create(SimpleType.STRING, SimpleType.STRING)))
            .build();
    CelAbstractSyntaxTree ast =
        compile(cel, "{?'nested_map': optional.ofNonZeroValue({?'map': m.?c})}");

    Map<String, Map<String, Map<String, String>>> result =
        (Map<String, Map<String, Map<String, String>>>)
            cel.createProgram(ast)
                .eval(
                    ImmutableMap.of(
                        "m", ImmutableMap.of("c", ImmutableMap.of("dashed-index", "goodbye"))));

    assertThat(result)
        .containsExactly(
            "nested_map", ImmutableMap.of("map", ImmutableMap.of("dashed-index", "goodbye")));
  }

  @Test
  public void optionalMapCreation_withNestedMapContainingEmptyValue_emptyValueStripped()
      throws Exception {
    TestCel cel =
        newCelBuilder()
            .setResultType(
                MapType.create(
                    SimpleType.STRING, MapType.create(SimpleType.STRING, SimpleType.STRING)))
            .addVar(
                "m",
                MapType.create(
                    SimpleType.STRING, MapType.create(SimpleType.STRING, SimpleType.STRING)))
            .build();

    CelAbstractSyntaxTree ast =
        compile(cel, "{?'nested_map': optional.ofNonZeroValue({?'map': m.?c}), 'singleton': true}");

    Object result = cel.createProgram(ast).eval(ImmutableMap.of("m", ImmutableMap.of()));

    assertThat(result).isEqualTo(ImmutableMap.of("singleton", true));
  }

  @Test
  public void optionalMapCreation_valueIsNonOptional_typeCheck_throws() {
    if (testMode.isParseOnly()) {
      return;
    }
    TestCel cel = newCelBuilder().build();

    CelValidationException e =
        assertThrows(CelValidationException.class, () -> compile(cel, "{?'hi': 'world'}"));

    assertThat(e)
        .hasMessageThat()
        .contains("expected type 'optional_type(string)' but found 'string'");
  }

  @Test
  public void optionalMessageCreation_fieldValueIsEmpty_returnsEmptyMessage() throws Exception {
    TestCel cel =
        newCelBuilder()
            .setResultType(StructTypeReference.create(TestAllTypes.getDescriptor().getFullName()))
            .build();
    CelAbstractSyntaxTree ast =
        compile(cel, "TestAllTypes{?single_double_wrapper: optional.ofNonZeroValue(0.0)}");

    TestAllTypes result = (TestAllTypes) cel.createProgram(ast).eval();

    assertThat(result).isEqualTo(TestAllTypes.getDefaultInstance());
  }

  @Test
  public void optionalMessageCreation_fieldValueIsPresent_returnsMessage() throws Exception {
    // TODO: Message creation with prepopulated fields is not yet supported in
    // CelLiteRuntime.
    assumeFalse(testMode.isLite());
    TestCel cel = newCelBuilder().build();
    CelAbstractSyntaxTree ast =
        compile(cel, "TestAllTypes{?single_double_wrapper: optional.ofNonZeroValue(5.0)}");

    TestAllTypes result = (TestAllTypes) cel.createProgram(ast).eval();

    assertThat(result)
        .isEqualTo(
            TestAllTypes.newBuilder()
                .setSingleDoubleWrapper(DoubleValue.newBuilder().setValue(5.0))
                .build());
  }

  @Test
  @TestParameters("{source: \"TestAllTypes{?map_string_string: m[?'nested']}\"}")
  @TestParameters(
      "{source: \"TestAllTypes{?map_string_string:"
          + " optional.ofNonZeroValue(m[?'nested'].orValue({}))}\"}")
  public void optionalMessageCreation_fieldValueContainsEmptyMap_returnsEmptyMessage(String source)
      throws Exception {
    TestCel cel =
        newCelBuilder()
            .addVar(
                "m",
                MapType.create(
                    SimpleType.STRING, MapType.create(SimpleType.STRING, SimpleType.STRING)))
            .build();
    CelAbstractSyntaxTree ast = compile(cel, source);

    TestAllTypes result =
        (TestAllTypes) cel.createProgram(ast).eval(ImmutableMap.of("m", ImmutableMap.of()));

    assertThat(result).isEqualTo(TestAllTypes.getDefaultInstance());
  }

  @Test
  public void optionalMessageCreation_fieldValueContainsMap_returnsEmptyMessage() throws Exception {
    // TODO: Message creation with prepopulated fields is not yet supported in
    // CelLiteRuntime.
    assumeFalse(testMode.isLite());
    TestCel cel =
        newCelBuilder()
            .addVar(
                "m",
                MapType.create(
                    SimpleType.STRING, MapType.create(SimpleType.STRING, SimpleType.STRING)))
            .build();
    CelAbstractSyntaxTree ast = compile(cel, "TestAllTypes{?map_string_string: m[?'nested']}");

    TestAllTypes result =
        (TestAllTypes)
            cel.createProgram(ast)
                .eval(
                    ImmutableMap.of(
                        "m", ImmutableMap.of("nested", ImmutableMap.of("hello", "world"))));

    assertThat(result)
        .isEqualTo(TestAllTypes.newBuilder().putMapStringString("hello", "world").build());
  }

  @Test
  public void optionalListCreation_allElementsAreEmpty_returnsEmptyList() throws Exception {
    TestCel cel =
        newCelBuilder()
            .setResultType(ListType.create(SimpleType.DYN))
            .addVar("m", MapType.create(SimpleType.STRING, SimpleType.DYN))
            .addVar("x", OptionalType.create(SimpleType.INT))
            .addVar("y", OptionalType.create(SimpleType.DYN))
            .build();
    CelAbstractSyntaxTree ast = compile(cel, "[?m.?c, ?x, ?y]");

    List<?> result =
        (List<?>)
            cel.createProgram(ast)
                .eval(
                    ImmutableMap.of(
                        "m", ImmutableMap.of(), "x", Optional.empty(), "y", Optional.empty()));

    assertThat(result).isEmpty();
  }

  @Test
  public void optionalListCreation_containsEmptyElements_emptyElementsAreStripped()
      throws Exception {
    TestCel cel =
        newCelBuilder()
            .setResultType(ListType.create(SimpleType.DYN))
            .addVar("m", MapType.create(SimpleType.STRING, SimpleType.DYN))
            .addVar("x", OptionalType.create(SimpleType.INT))
            .addVar("y", OptionalType.create(SimpleType.DYN))
            .build();
    CelAbstractSyntaxTree ast = compile(cel, "[?m.?c, ?x, ?y]");

    List<?> result =
        (List<?>)
            cel.createProgram(ast)
                .eval(
                    ImmutableMap.of(
                        "m", ImmutableMap.of(), "x", Optional.of(42), "y", Optional.empty()));

    assertThat(result).containsExactly(42L);
  }

  @Test
  public void optionalListCreation_containsMixedTypeElements_success() throws Exception {
    TestCel cel =
        newCelBuilder()
            .setResultType(ListType.create(SimpleType.DYN))
            .addVar(
                "m",
                MapType.create(
                    SimpleType.STRING, MapType.create(SimpleType.STRING, SimpleType.STRING)))
            .addVar("x", OptionalType.create(SimpleType.INT))
            .addVar("y", OptionalType.create(SimpleType.DYN))
            .addVar("z", SimpleType.STRING)
            .build();
    CelAbstractSyntaxTree ast = compile(cel, "[?m.?c, ?x, ?y, z]");

    List<?> result =
        (List<?>)
            cel.createProgram(ast)
                .eval(
                    ImmutableMap.of(
                        "m",
                        ImmutableMap.of("c", ImmutableMap.of("dashed-value", "goodbye")),
                        "x",
                        Optional.of(42),
                        "y",
                        Optional.of(10),
                        "z",
                        "hello world"));

    assertThat(result)
        .containsExactly(ImmutableMap.of("dashed-value", "goodbye"), 42L, 10L, "hello world");
  }

  @Test
  public void
      optionalListCreation_containsMixedTypeElements_typeCheck_throwsWhenHomogeneousLiteralsEnabled() {
    if (testMode.isParseOnly()) {
      return;
    }
    TestCel cel =
        newCelBuilder()
            .setOptions(CelOptions.current().enableHomogeneousLiterals(true).build())
            .addVar(
                "m",
                MapType.create(
                    SimpleType.STRING, MapType.create(SimpleType.STRING, SimpleType.STRING)))
            .addVar("x", OptionalType.create(SimpleType.INT))
            .addVar("y", OptionalType.create(SimpleType.DYN))
            .build();

    CelValidationException e =
        assertThrows(CelValidationException.class, () -> compile(cel, "[?m.?c, ?x, ?y]"));

    assertThat(e).hasMessageThat().contains("expected type 'map(string, string)' but found 'int'");
  }

  @Test
  public void optionalListCreation_withinProtoMessage_success() throws Exception {
    // TODO: Message creation with prepopulated fields is not yet supported in
    // CelLiteRuntime.
    assumeFalse(testMode.isLite());
    TestCel cel =
        newCelBuilder()
            .addVar("m", MapType.create(SimpleType.STRING, SimpleType.DYN))
            .addVar("x", OptionalType.create(SimpleType.DYN))
            .addVar("y", OptionalType.create(SimpleType.DYN))
            .setResultType(StructTypeReference.create(TestAllTypes.getDescriptor().getFullName()))
            .build();
    String expression =
        "TestAllTypes{repeated_string: ['greetings', ?m.nested.?hello], ?repeated_int32:"
            + " optional.ofNonZeroValue([?x, ?y])}";
    CelAbstractSyntaxTree ast = compile(cel, expression);

    TestAllTypes result =
        (TestAllTypes)
            cel.createProgram(ast)
                .eval(
                    ImmutableMap.of(
                        "m", ImmutableMap.of("nested", ImmutableMap.of("hello", "world")),
                        "x", Optional.empty(),
                        "y", Optional.empty()));

    assertThat(result)
        .isEqualTo(
            TestAllTypes.newBuilder()
                .addRepeatedString("greetings")
                .addRepeatedString("world")
                .build());
  }

  @Test
  public void optionalMapMacro_receiverIsEmpty_returnsOptionalEmpty() throws Exception {
    TestCel cel =
        newCelBuilder()
            .addVar("x", OptionalType.create(SimpleType.INT))
            .setResultType(OptionalType.create(SimpleType.INT))
            .build();
    CelAbstractSyntaxTree ast = compile(cel, "x.optMap(y, y + 1)");

    Optional<?> result =
        (Optional<?>) cel.createProgram(ast).eval(ImmutableMap.of("x", Optional.empty()));

    assertThat(result).isEmpty();
  }

  @Test
  public void optionalMapMacro_receiverHasValue_returnsOptionalValue() throws Exception {
    TestCel cel =
        newCelBuilder()
            .addVar("x", OptionalType.create(SimpleType.INT))
            .setResultType(OptionalType.create(SimpleType.INT))
            .build();
    CelAbstractSyntaxTree ast = compile(cel, "x.optMap(y, y + 1)");

    Optional<?> result =
        (Optional<?>) cel.createProgram(ast).eval(ImmutableMap.of("x", Optional.of(42L)));

    assertThat(result).hasValue(43L);
  }

  @Test
  public void optionalMapMacro_withNonIdent_throws() {
    TestCel cel =
        newCelBuilder()
            .addVar("x", OptionalType.create(SimpleType.INT))
            .setResultType(OptionalType.create(SimpleType.INT))
            .build();

    CelValidationException e =
        assertThrows(CelValidationException.class, () -> compile(cel, "x.optMap(y.z, y.z + 1)"));

    assertThat(e).hasMessageThat().contains("optMap() variable name must be a simple identifier");
  }

  @Test
  public void optionalFlatMapMacro_receiverIsEmpty_returnsOptionalEmpty() throws Exception {
    TestCel cel =
        newCelBuilder()
            .addVar("x", OptionalType.create(SimpleType.INT))
            .setResultType(OptionalType.create(SimpleType.INT))
            .build();
    CelAbstractSyntaxTree ast = compile(cel, "x.optFlatMap(y, optional.of(y + 1))");

    Optional<?> result =
        (Optional<?>) cel.createProgram(ast).eval(ImmutableMap.of("x", Optional.empty()));

    assertThat(result).isEmpty();
  }

  @Test
  public void optionalFlatMapMacro_receiverHasValue_returnsOptionalValue() throws Exception {
    TestCel cel =
        newCelBuilder()
            .addVar("x", OptionalType.create(SimpleType.INT))
            .setResultType(OptionalType.create(SimpleType.INT))
            .build();
    CelAbstractSyntaxTree ast = compile(cel, "x.optFlatMap(y, optional.of(y + 1))");

    Optional<?> result =
        (Optional<?>) cel.createProgram(ast).eval(ImmutableMap.of("x", Optional.of(42L)));

    assertThat(result).hasValue(43L);
  }

  @Test
  public void optionalMapMacro_simpleTarget_notWrappedInComprehension() throws Exception {
    TestCel cel =
        newCelBuilder()
            .addVar("x", OptionalType.create(SimpleType.INT))
            .setResultType(OptionalType.create(SimpleType.INT))
            .build();
    CelAbstractSyntaxTree ast = compile(cel, "x.optMap(y, y + 1)");

    assertThat(ast.getExpr().exprKind().getKind()).isEqualTo(CelExpr.ExprKind.Kind.CALL);
    CelComprehension innerComprehension =
        ast.getExpr().call().args().get(1).call().args().get(0).comprehension();
    assertThat(innerComprehension.accuVar()).isEqualTo("y");
    assertThat(innerComprehension.loopCondition().constant().booleanValue()).isTrue();
  }

  @Test
  public void optionalMapMacro_complexTarget_astWrappedInComprehension() throws Exception {
    TestCel cel =
        newCelBuilder()
            .setResultType(OptionalType.create(SimpleType.INT))
            .addVar("msg", StructTypeReference.create(TestAllTypes.getDescriptor().getFullName()))
            .build();
    CelAbstractSyntaxTree ast = compile(cel, "msg.?single_int32.optMap(y, y + 1)");

    assertThat(ast.getExpr().exprKind().getKind()).isEqualTo(CelExpr.ExprKind.Kind.COMPREHENSION);
    CelComprehension outerComprehension = ast.getExpr().comprehension();
    assertThat(outerComprehension.accuVar()).isEqualTo("@target");
    assertThat(outerComprehension.loopCondition().constant().booleanValue()).isFalse();
    CelComprehension innerComprehension =
        outerComprehension.result().call().args().get(1).call().args().get(0).comprehension();
    assertThat(innerComprehension.accuVar()).isEqualTo("y");
    assertThat(innerComprehension.loopCondition().constant().booleanValue()).isTrue();

    Optional<Long> result =
        (Optional<Long>)
            cel.createProgram(ast)
                .eval(ImmutableMap.of("msg", TestAllTypes.newBuilder().setSingleInt32(42).build()));
    assertThat(result).hasValue(43L);
  }

  @Test
  public void optionalFlatMapMacro_simpleTarget_notWrappedInComprehension() throws Exception {
    TestCel cel =
        newCelBuilder()
            .addVar("x", OptionalType.create(SimpleType.INT))
            .setResultType(OptionalType.create(SimpleType.INT))
            .build();
    CelAbstractSyntaxTree ast = compile(cel, "x.optFlatMap(y, optional.of(y + 1))");

    assertThat(ast.getExpr().exprKind().getKind()).isEqualTo(CelExpr.ExprKind.Kind.CALL);
    CelComprehension innerComprehension = ast.getExpr().call().args().get(1).comprehension();
    assertThat(innerComprehension.accuVar()).isEqualTo("y");
    assertThat(innerComprehension.loopCondition().constant().booleanValue()).isTrue();
  }

  @Test
  public void optionalFlatMapMacro_complexTarget_astWrappedInComprehension() throws Exception {
    TestCel cel =
        newCelBuilder()
            .setResultType(OptionalType.create(SimpleType.INT))
            .addVar("msg", StructTypeReference.create(TestAllTypes.getDescriptor().getFullName()))
            .build();
    CelAbstractSyntaxTree ast = compile(cel, "msg.?single_int32.optFlatMap(y, optional.of(y + 1))");

    assertThat(ast.getExpr().exprKind().getKind()).isEqualTo(CelExpr.ExprKind.Kind.COMPREHENSION);
    CelComprehension outerComprehension = ast.getExpr().comprehension();
    assertThat(outerComprehension.accuVar()).isEqualTo("@target");
    assertThat(outerComprehension.loopCondition().constant().booleanValue()).isFalse();
    CelComprehension innerComprehension =
        outerComprehension.result().call().args().get(1).comprehension();
    assertThat(innerComprehension.accuVar()).isEqualTo("y");
    assertThat(innerComprehension.loopCondition().constant().booleanValue()).isTrue();

    Optional<Long> result =
        (Optional<Long>)
            cel.createProgram(ast)
                .eval(ImmutableMap.of("msg", TestAllTypes.newBuilder().setSingleInt32(42).build()));
    assertThat(result).hasValue(43L);
  }

  @Test
  public void optionalFlatMapMacro_withOptionalOfNonZeroValue_optionalEmptyWhenValueIsZero()
      throws Exception {
    TestCel cel =
        newCelBuilder()
            .addVar("x", OptionalType.create(SimpleType.INT))
            .setResultType(OptionalType.create(SimpleType.INT))
            .build();
    CelAbstractSyntaxTree ast = compile(cel, "x.optFlatMap(y, optional.ofNonZeroValue(y - 1))");

    Optional<?> result =
        (Optional<?>) cel.createProgram(ast).eval(ImmutableMap.of("x", Optional.of(1L)));

    assertThat(result).isEmpty();
  }

  @Test
  public void optionalFlatMapMacro_withOptionalOfNonZeroValue_optionalValueWhenValueExists()
      throws Exception {
    TestCel cel =
        newCelBuilder()
            .addVar("x", OptionalType.create(SimpleType.INT))
            .setResultType(OptionalType.create(SimpleType.INT))
            .build();
    CelAbstractSyntaxTree ast = compile(cel, "x.optFlatMap(y, optional.ofNonZeroValue(y + 1))");

    Optional<?> result =
        (Optional<?>) cel.createProgram(ast).eval(ImmutableMap.of("x", Optional.of(1L)));

    assertThat(result).hasValue(2L);
  }

  @Test
  public void optionalFlatMapMacro_mappingExprIsNonOptional_typeCheck_throws() {
    if (testMode.isParseOnly()) {
      return;
    }

    TestCel cel =
        newCelBuilder()
            .addVar("x", OptionalType.create(SimpleType.INT))
            .setResultType(OptionalType.create(SimpleType.INT))
            .build();
    CelValidationException e =
        assertThrows(CelValidationException.class, () -> compile(cel, "x.optFlatMap(y, y + 1)"));

    assertThat(e).hasMessageThat().contains("found no matching overload for '_?_:_'");
  }

  @Test
  public void optionalFlatMapMacro_withNonIdent_throws() {
    TestCel cel =
        newCelBuilder()
            .addVar("x", OptionalType.create(SimpleType.INT))
            .setResultType(OptionalType.create(SimpleType.INT))
            .build();

    CelValidationException e =
        assertThrows(
            CelValidationException.class, () -> compile(cel, "x.optFlatMap(y.z, y.z + 1)"));

    assertThat(e)
        .hasMessageThat()
        .contains("optFlatMap() variable name must be a simple identifier");
  }

  @Test
  public void optionalType_typeResolution() throws Exception {
    TestCel cel = newCelBuilder().build();
    CelAbstractSyntaxTree ast = compile(cel, "optional_type");

    TypeType optionalRuntimeType = (TypeType) cel.createProgram(ast).eval();

    assertThat(optionalRuntimeType.name()).isEqualTo("type");
    assertThat(optionalRuntimeType.containingTypeName()).isEqualTo("optional_type");
  }

  @Test
  public void optionalType_typeComparison() throws Exception {
    TestCel cel = newCelBuilder().build();

    CelAbstractSyntaxTree ast = compile(cel, "type(optional.none()) == optional_type");

    assertThat(cel.createProgram(ast).eval()).isEqualTo(true);
  }

  @Test
  @TestParameters("{expression: '[].first().hasValue() == false'}")
  @TestParameters("{expression: '[\"a\",\"b\",\"c\"].first().value() == \"a\"'}")
  public void listFirst_success(String expression) throws Exception {
    TestCel cel = newCelBuilder().build();
    boolean result = (boolean) cel.createProgram(compile(cel, expression)).eval();
    assertThat(result).isTrue();
  }

  @Test
  @TestParameters("{expression: '[].last().hasValue() == false'}")
  @TestParameters("{expression: '[1, 2, 3].last().value() == 3'}")
  public void listLast_success(String expression) throws Exception {
    TestCel cel = newCelBuilder().build();
    boolean result = (boolean) cel.createProgram(compile(cel, expression)).eval();
    assertThat(result).isTrue();
  }

  @Test
  @TestParameters("{expression: '[1].first()', expectedError: 'undeclared reference to ''first'''}")
  @TestParameters("{expression: '[2].last()', expectedError: 'undeclared reference to ''last'''}")
  public void listFirstAndLast_typeCheck_throws_earlyVersion(
      String expression, String expectedError) throws Exception {
    if (testMode.isParseOnly()) {
      return;
    }
    // Configure Cel with an earlier version of the 'optional' library, which did not support
    // 'first' and 'last'
    TestCel cel = newCelBuilder(1).build();
    assertThat(
            assertThrows(
                CelValidationException.class,
                () -> {
                  cel.createProgram(compile(cel, expression)).eval();
                }))
        .hasMessageThat()
        .contains(expectedError);
  }

  @Test
  public void optionalMapCreation_mapKeySetOnNonOptional_throws() {
    String expression = "{?1: dyn(\"one\")}";
    TestCel cel = newCelBuilder().build();

    CelEvaluationException e =
        assertThrows(
            CelEvaluationException.class, () -> cel.createProgram(compile(cel, expression)).eval());
    assertThat(e)
        .hasMessageThat()
        .contains("Cannot initialize optional entry '1' from non-optional value one");
  }

  @Test
  public void optionalListCreation_listKeySetOnNonOptional_throws() {
    String expression = "[?dyn(1)]";
    TestCel cel = newCelBuilder().build();

    CelEvaluationException e =
        assertThrows(
            CelEvaluationException.class, () -> cel.createProgram(compile(cel, expression)).eval());
    assertThat(e)
        .hasMessageThat()
        .contains("Cannot initialize optional list element from non-optional value 1");
  }

  @Test
  public void optionalMessageCreation_fieldKeySetOnNonOptional_throws() {
    String expression = "TestAllTypes{?single_double_wrapper: dyn('foo')}";
    TestCel cel = newCelBuilder().build();

    CelEvaluationException e =
        assertThrows(
            CelEvaluationException.class, () -> cel.createProgram(compile(cel, expression)).eval());
    assertThat(e)
        .hasMessageThat()
        .contains(
            "Cannot initialize optional entry 'single_double_wrapper' from non-optional value foo");
  }

  @Test
  @TestParameters("{expression: '[type([]), int, type(optional.none())]'}")
  @TestParameters("{expression: '[type([]), type(optional.none()), int]'}")
  @TestParameters("{expression: '[int, type([]), type(optional.none())]'}")
  @TestParameters("{expression: '[int, type(optional.none()), type([])]'}")
  @TestParameters("{expression: '[type(optional.none()), type([]), int]'}")
  @TestParameters("{expression: '[type(optional.none()), int, type([])]'}")
  public void listType_heterogeneousTypePermutations_resolvesToListDyn(String expression)
      throws Exception {
    assumeFalse(testMode.isParseOnly());

    TestCel cel = newCelBuilder().build();

    CelAbstractSyntaxTree ast = compile(cel, expression);
    CelList list = ast.getExpr().listOrDefault();

    assertThat(ast.getResultType()).isEqualTo(ListType.create(SimpleType.DYN));
    assertThat(
            list.elements().stream()
                .map(elem -> ast.getType(elem.id()).map(CelType::kind))
                .collect(toImmutableList()))
        .containsExactly(
            Optional.of(CelKind.TYPE), Optional.of(CelKind.TYPE), Optional.of(CelKind.TYPE));
    assertThat((List<?>) cel.createProgram(ast).eval()).hasSize(3);
  }

  private CelAbstractSyntaxTree compile(TestCel cel, String expression)
      throws CelValidationException {
    return compile(cel.compiler, expression);
  }

  private CelAbstractSyntaxTree compile(CelCompiler compiler, String expression)
      throws CelValidationException {
    CelAbstractSyntaxTree ast = compiler.parse(expression).getAst();
    if (testMode.isParseOnly()) {
      return ast;
    }

    return compiler.check(ast).getAst();
  }

  // Intentionally testing the deprecated legacy runtime in the test matrix.
  @SuppressWarnings("deprecation")
  private Object eval(
      CelCompiler celCompiler, CelOptionalRuntimeLibrary runtimeLibrary, String expression)
      throws Exception {
    CelAbstractSyntaxTree ast = compile(celCompiler, expression);
    switch (testMode) {
      case LITE_PARSE_ONLY:
      case LITE_CHECKED:
        CelLiteRuntime celLiteRuntime =
            CelLiteRuntimeFactory.newLiteRuntimeBuilder().addLibraries(runtimeLibrary).build();
        return celLiteRuntime.createProgram(ast).eval();
      case PLANNER_PARSE_ONLY:
      case PLANNER_CHECKED:
        RuntimeEquality plannerEquality =
            RuntimeEquality.create(RuntimeHelpers.create(), CelOptions.DEFAULT);
        CelRuntime plannerRuntime =
            CelRuntimeFactory.plannerRuntimeBuilder()
                .addFunctionBindings(runtimeLibrary.newFunctionBindings(plannerEquality))
                .build();
        return plannerRuntime.createProgram(ast).eval();
      case LEGACY_CHECKED:
        RuntimeEquality legacyEquality =
            RuntimeEquality.create(RuntimeHelpers.create(), CelOptions.DEFAULT);
        CelRuntime legacyRuntime =
            CelRuntimeFactory.standardCelRuntimeBuilder()
                .addFunctionBindings(runtimeLibrary.newFunctionBindings(legacyEquality))
                .build();
        return legacyRuntime.createProgram(ast).eval();
    }
    throw new AssertionError("Unexpected testMode: " + testMode);
  }

  @Test
  public void separateLibraryAndRuntime_allFunctions_success() throws Exception {
    CelCompiler celCompiler =
        CelCompilerFactory.standardCelCompilerBuilder()
            .addLibraries(CelOptionalCompilerLibrary.optional())
            .build();

    Object result =
        eval(
            celCompiler,
            CelOptionalRuntimeLibrary.optional(),
            "optional.of(1).or(optional.none()).value()");

    assertThat(result).isEqualTo(1L);
  }

  @Test
  public void separateLibraryAndRuntime_versioned_success() throws Exception {
    CelCompiler celCompiler =
        CelCompilerFactory.standardCelCompilerBuilder()
            .addLibraries(CelOptionalCompilerLibrary.optional(0))
            .build();

    Object result =
        eval(celCompiler, CelOptionalRuntimeLibrary.optional(0), "optional.of(1).value()");

    assertThat(result).isEqualTo(1L);
  }

  @Test
  public void separateLibraryAndRuntime_subsetOfFunctions_success() throws Exception {
    CelCompiler celCompiler =
        CelCompilerFactory.standardCelCompilerBuilder()
            .addLibraries(
                CelOptionalCompilerLibrary.optional(
                    CelOptionalCompilerLibrary.Function.OPTIONAL_OF,
                    CelOptionalCompilerLibrary.Function.VALUE))
            .build();
    CelOptionalRuntimeLibrary runtimeLibrary =
        CelOptionalRuntimeLibrary.optional(
            CelOptionalRuntimeLibrary.Function.OPTIONAL_OF,
            CelOptionalRuntimeLibrary.Function.VALUE);

    Object result = eval(celCompiler, runtimeLibrary, "optional.of(1).value()");

    assertThat(result).isEqualTo(1L);
    if (testMode.isParseOnly()) {
      assertThrows(
          CelEvaluationException.class, () -> eval(celCompiler, runtimeLibrary, "[1].first()"));
    } else {
      assertThrows(CelValidationException.class, () -> compile(celCompiler, "[1].first()"));
    }
  }

  @Test
  public void separateLibraryAndRuntime_setOfFunctions_success() throws Exception {
    CelCompiler celCompiler =
        CelCompilerFactory.standardCelCompilerBuilder()
            .addLibraries(
                CelOptionalCompilerLibrary.optional(
                    ImmutableSet.of(
                        CelOptionalCompilerLibrary.Function.OPTIONAL_OF,
                        CelOptionalCompilerLibrary.Function.VALUE)))
            .build();
    CelOptionalRuntimeLibrary runtimeLibrary =
        CelOptionalRuntimeLibrary.optional(
            ImmutableSet.of(
                CelOptionalRuntimeLibrary.Function.OPTIONAL_OF,
                CelOptionalRuntimeLibrary.Function.VALUE));

    Object result = eval(celCompiler, runtimeLibrary, "optional.of(1).value()");

    assertThat(result).isEqualTo(1L);
  }

  @Test
  public void separateLibraryAndRuntime_unsupportedVersion_throws() {
    assertThrows(IllegalArgumentException.class, () -> CelOptionalCompilerLibrary.optional(99));
    assertThrows(IllegalArgumentException.class, () -> CelOptionalRuntimeLibrary.optional(99));
  }

  // Intentionally testing the deprecated legacy runtime in the test matrix.
  @SuppressWarnings("deprecation")
  private CelBuilder newBaseCelBuilder() {
    return testMode.equals(TestMode.LEGACY_CHECKED)
        ? CelFactory.standardCelBuilder()
        : CelFactory.plannerCelBuilder();
  }

  @Test
  public void optional_subsetOfFunctions_success() throws Exception {
    assumeFalse(testMode.isLite());
    Cel cel =
        newBaseCelBuilder()
            .addCompilerLibraries(
                CelExtensions.optional(
                    CelOptionalLibrary.Function.OPTIONAL_OF, CelOptionalLibrary.Function.VALUE))
            .addRuntimeLibraries(
                CelExtensions.optional(
                    CelOptionalLibrary.Function.OPTIONAL_OF, CelOptionalLibrary.Function.VALUE))
            .build();

    CelAbstractSyntaxTree ast = compile(cel, "optional.of(1).value()");
    Object result = cel.createProgram(ast).eval();

    assertThat(result).isEqualTo(1L);
    if (testMode.isParseOnly()) {
      CelAbstractSyntaxTree firstAst = compile(cel, "[1].first()");
      CelRuntime.Program firstProgram = cel.createProgram(firstAst);
      assertThrows(CelEvaluationException.class, firstProgram::eval);
    } else {
      assertThrows(CelValidationException.class, () -> compile(cel, "[1].first()"));
    }
  }

  @Test
  public void optional_setOfFunctions_success() throws Exception {
    assumeFalse(testMode.isLite());
    Cel cel =
        newBaseCelBuilder()
            .addCompilerLibraries(
                CelExtensions.optional(
                    ImmutableSet.of(
                        CelOptionalLibrary.Function.OPTIONAL_OF,
                        CelOptionalLibrary.Function.VALUE)))
            .addRuntimeLibraries(
                CelExtensions.optional(
                    ImmutableSet.of(
                        CelOptionalLibrary.Function.OPTIONAL_OF,
                        CelOptionalLibrary.Function.VALUE)))
            .build();

    CelAbstractSyntaxTree ast = compile(cel, "optional.of(1).value()");
    Object result = cel.createProgram(ast).eval();

    assertThat(result).isEqualTo(1L);
  }

  @Test
  public void liteRuntime_toRuntimeBuilder_updatesRuntimeEquality() throws Exception {
    CelCompiler celCompiler =
        CelCompilerFactory.standardCelCompilerBuilder()
            .addLibraries(CelOptionalCompilerLibrary.optional())
            .build();
    CelAbstractSyntaxTree ast = compile(celCompiler, "{1: 'a'}[?dyn(1.0)]");
    CelLiteRuntime defaultRuntime =
        CelLiteRuntimeFactory.newLiteRuntimeBuilder()
            .setStandardFunctions(DynFunction.create())
            .addLibraries(CelOptionalRuntimeLibrary.optional())
            .build();
    CelLiteRuntime strictMapKeyRuntime =
        defaultRuntime
            .toRuntimeBuilder()
            .setOptions(CelOptions.current().disableCelStandardEquality(true).build())
            .build();

    assertThat(defaultRuntime.createProgram(ast).eval()).isEqualTo(Optional.of("a"));
    assertThat(strictMapKeyRuntime.createProgram(ast).eval()).isEqualTo(Optional.empty());
  }

  @Test
  public void liteRuntime_mergesIndexOverloadsWithStandardFunction() throws Exception {
    CelCompiler celCompiler =
        CelCompilerFactory.standardCelCompilerBuilder()
            .addLibraries(CelOptionalCompilerLibrary.optional())
            .build();
    CelLiteRuntime runtime =
        CelLiteRuntimeFactory.newLiteRuntimeBuilder()
            .setStandardFunctions(IndexOperator.create())
            .addLibraries(CelOptionalRuntimeLibrary.optional())
            .build();
    CelAbstractSyntaxTree stdListIndexAst = compile(celCompiler, "[10, 20][0]");
    CelAbstractSyntaxTree optListIndexAst = compile(celCompiler, "optional.of([10, 20])[0]");
    CelAbstractSyntaxTree stdMapIndexAst = compile(celCompiler, "{'a': 10}['a']");
    CelAbstractSyntaxTree optMapIndexAst = compile(celCompiler, "optional.of({'a': 10})['a']");

    assertThat(runtime.createProgram(stdListIndexAst).eval()).isEqualTo(10L);
    assertThat(runtime.createProgram(optListIndexAst).eval()).isEqualTo(Optional.of(10L));
    assertThat(runtime.createProgram(stdMapIndexAst).eval()).isEqualTo(10L);
    assertThat(runtime.createProgram(optMapIndexAst).eval()).isEqualTo(Optional.of(10L));
  }

  @Test
  public void optional_noArgConstructor_success() {
    CelOptionalLibrary extensions = new CelOptionalLibrary();

    assertThat(extensions.version()).isEqualTo(CelOptionalCompilerLibrary.optional().version());
    assertThat(extensions.functions()).isNotEmpty();
    assertThat(extensions.macros()).isNotEmpty();
    assertThat(extensions.variables()).isNotEmpty();
  }

  @Test
  public void runtimeLibrary_constructorsAndFactories() {
    CelOptionalRuntimeLibrary lib1 =
        new CelOptionalRuntimeLibrary(
            ImmutableSet.of(CelOptionalRuntimeLibrary.Function.OPTIONAL_OF));
    assertThat(lib1.functions()).containsExactly(CelOptionalRuntimeLibrary.Function.OPTIONAL_OF);

    CelOptionalRuntimeLibrary lib2 = CelOptionalRuntimeLibrary.optional();
    assertThat(lib2.functions())
        .containsExactlyElementsIn(CelOptionalRuntimeLibrary.Function.values());
    assertThat(CelOptionalRuntimeLibrary.optional(2)).isSameInstanceAs(lib2);
    assertThat(CelOptionalRuntimeLibrary.optional(Integer.MAX_VALUE)).isSameInstanceAs(lib2);

    CelOptionalRuntimeLibrary lib3 = CelOptionalRuntimeLibrary.optional(0);
    assertThat(lib3.functions())
        .containsExactly(
            CelOptionalRuntimeLibrary.Function.OPTIONAL_OF,
            CelOptionalRuntimeLibrary.Function.OPTIONAL_OF_NON_ZERO_VALUE,
            CelOptionalRuntimeLibrary.Function.OPTIONAL_NONE,
            CelOptionalRuntimeLibrary.Function.VALUE,
            CelOptionalRuntimeLibrary.Function.HAS_VALUE,
            CelOptionalRuntimeLibrary.Function.OPTIONAL_UNWRAP,
            CelOptionalRuntimeLibrary.Function.OR,
            CelOptionalRuntimeLibrary.Function.OR_VALUE);
    assertThat(CelOptionalRuntimeLibrary.optional(1)).isSameInstanceAs(lib3);

    CelOptionalRuntimeLibrary lib4 =
        CelOptionalRuntimeLibrary.optional(CelOptionalRuntimeLibrary.Function.OPTIONAL_OF);
    assertThat(lib4.functions()).containsExactly(CelOptionalRuntimeLibrary.Function.OPTIONAL_OF);

    CelOptionalRuntimeLibrary lib5 =
        CelOptionalRuntimeLibrary.optional(
            ImmutableSet.of(CelOptionalRuntimeLibrary.Function.OPTIONAL_OF));
    assertThat(lib5.functions()).containsExactly(CelOptionalRuntimeLibrary.Function.OPTIONAL_OF);
  }

  @Test
  public void runtimeOptions_unsupported_throws() {
    CelOptionalLibrary extensions = new CelOptionalLibrary();
    CelRuntimeBuilder runtimeBuilder = CelRuntimeFactory.plannerRuntimeBuilder();
    assertThrows(
        UnsupportedOperationException.class, () -> extensions.setRuntimeOptions(runtimeBuilder));

    CelOptionalRuntimeLibrary runtimeLibrary = CelOptionalRuntimeLibrary.optional();
    CelLiteRuntimeBuilder liteRuntimeBuilder = CelLiteRuntimeFactory.newLiteRuntimeBuilder();
    assertThrows(
        UnsupportedOperationException.class,
        () -> runtimeLibrary.setRuntimeOptions(liteRuntimeBuilder));
  }

  @Test
  public void optionalMacros_invalidExpanderArguments_throws() throws Exception {
    ImmutableSet<CelMacro> macros = CelOptionalCompilerLibrary.optional().macros();
    assertThat(macros).hasSize(2);

    CelMacro verifyPreconditionsMacro =
        CelMacro.newGlobalMacro(
            "verifyPreconditions",
            0,
            (exprFactory, unusedTarget, unusedArgs) -> {
              CelExpr validTarget = exprFactory.newIdentifier("x");
              for (CelMacro macro : macros) {
                assertThrows(
                    NullPointerException.class,
                    () ->
                        macro
                            .getExpander()
                            .expandMacro(null, validTarget, ImmutableList.of(validTarget)));
                assertThrows(
                    NullPointerException.class,
                    () -> macro.getExpander().expandMacro(exprFactory, null, ImmutableList.of()));
                assertThrows(
                    IllegalArgumentException.class,
                    () ->
                        macro
                            .getExpander()
                            .expandMacro(exprFactory, validTarget, ImmutableList.of()));
              }
              return Optional.of(exprFactory.newBoolLiteral(true));
            });

    CelCompiler compiler =
        CelCompilerFactory.standardCelCompilerBuilder().addMacros(verifyPreconditionsMacro).build();
    assertThat(compiler.parse("verifyPreconditions()").getAst().getExpr().constant().booleanValue())
        .isTrue();
  }

  /**
   * A wrapper around {@link CelBuilder} (which uses {@link CelRuntimeBuilder}) and {@link
   * CelLiteRuntimeBuilder} to allow for testing of both runtimes.
   */
  private static final class TestCelBuilder {
    private final CelBuilder celBuilder;
    private final CelCompilerBuilder compilerBuilder;
    private final CelLiteRuntimeBuilder liteRuntimeBuilder;

    private TestCelBuilder(CelBuilder celBuilder) {
      this.celBuilder = celBuilder;
      this.compilerBuilder = null;
      this.liteRuntimeBuilder = null;
    }

    private TestCelBuilder(
        CelCompilerBuilder compilerBuilder, CelLiteRuntimeBuilder liteRuntimeBuilder) {
      this.celBuilder = null;
      this.compilerBuilder = compilerBuilder;
      this.liteRuntimeBuilder = liteRuntimeBuilder;
    }

    @CanIgnoreReturnValue
    private TestCelBuilder setOptions(CelOptions options) {
      if (celBuilder != null) {
        celBuilder.setOptions(options);
      } else {
        compilerBuilder.setOptions(options);
        liteRuntimeBuilder.setOptions(options);
      }
      return this;
    }

    @CanIgnoreReturnValue
    private TestCelBuilder setResultType(CelType resultType) {
      if (celBuilder != null) {
        celBuilder.setResultType(resultType);
      } else {
        compilerBuilder.setResultType(resultType);
      }
      return this;
    }

    @CanIgnoreReturnValue
    private TestCelBuilder addVar(String name, CelType type) {
      if (celBuilder != null) {
        celBuilder.addVar(name, type);
      } else {
        compilerBuilder.addVar(name, type);
      }
      return this;
    }

    @CanIgnoreReturnValue
    private TestCelBuilder addFunctionDeclarations(CelFunctionDecl... decls) {
      if (celBuilder != null) {
        celBuilder.addFunctionDeclarations(decls);
      } else {
        compilerBuilder.addFunctionDeclarations(decls);
      }
      return this;
    }

    @CanIgnoreReturnValue
    private TestCelBuilder addFunctionBindings(Iterable<CelFunctionBinding> bindings) {
      if (celBuilder != null) {
        celBuilder.addFunctionBindings(bindings);
      } else {
        liteRuntimeBuilder.addFunctionBindings(bindings);
      }
      return this;
    }

    private TestCel build() {
      if (celBuilder != null) {
        Cel cel = celBuilder.build();
        return new TestCel(cel, cel, null);
      }
      return new TestCel(compilerBuilder.build(), null, liteRuntimeBuilder.build());
    }
  }

  private static final class TestCel {
    private final CelCompiler compiler;
    private final CelRuntime celRuntime;
    private final CelLiteRuntime celLiteRuntime;

    private TestCel(CelCompiler compiler, CelRuntime celRuntime, CelLiteRuntime celLiteRuntime) {
      this.compiler = compiler;
      this.celRuntime = celRuntime;
      this.celLiteRuntime = celLiteRuntime;
    }

    private Program createProgram(CelAbstractSyntaxTree ast) throws CelEvaluationException {
      if (celRuntime != null) {
        return celRuntime.createProgram(ast);
      }
      return celLiteRuntime.createProgram(ast);
    }
  }
}
