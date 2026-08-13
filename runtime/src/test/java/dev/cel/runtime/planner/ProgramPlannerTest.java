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

package dev.cel.runtime.planner;

import static com.google.common.truth.Truth.assertThat;
import static dev.cel.common.CelFunctionDecl.newFunctionDeclaration;
import static dev.cel.common.CelOverloadDecl.newGlobalOverload;
import static dev.cel.common.CelOverloadDecl.newMemberOverload;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.primitives.UnsignedLong;
import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import com.google.testing.junit.testparameterinjector.TestParameters;
import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.CelContainer;
import dev.cel.common.CelDescriptorUtil;
import dev.cel.common.CelErrorCode;
import dev.cel.common.CelOptions;
import dev.cel.common.CelSource;
import dev.cel.common.ast.CelConstant;
import dev.cel.common.ast.CelExpr;
import dev.cel.common.exceptions.CelDivideByZeroException;
import dev.cel.common.internal.CelDescriptorPool;
import dev.cel.common.internal.DefaultDescriptorPool;
import dev.cel.common.internal.DefaultMessageFactory;
import dev.cel.common.internal.DynamicProto;
import dev.cel.common.types.CelType;
import dev.cel.common.types.CelTypeProvider;
import dev.cel.common.types.CelTypeProvider.CombinedCelTypeProvider;
import dev.cel.common.types.DefaultTypeProvider;
import dev.cel.common.types.ListType;
import dev.cel.common.types.MapType;
import dev.cel.common.types.OptionalType;
import dev.cel.common.types.ProtoMessageTypeProvider;
import dev.cel.common.types.SimpleType;
import dev.cel.common.types.StructTypeReference;
import dev.cel.common.types.TypeType;
import dev.cel.common.values.CelByteString;
import dev.cel.common.values.CelValueConverter;
import dev.cel.common.values.CelValueProvider;
import dev.cel.common.values.NullValue;
import dev.cel.common.values.ProtoCelValueConverter;
import dev.cel.common.values.ProtoMessageValueProvider;
import dev.cel.compiler.CelCompiler;
import dev.cel.compiler.CelCompilerFactory;
import dev.cel.expr.conformance.proto3.GlobalEnum;
import dev.cel.expr.conformance.proto3.TestAllTypes;
import dev.cel.expr.conformance.proto3.TestAllTypes.NestedMessage;
import dev.cel.extensions.CelExtensions;
import dev.cel.parser.CelStandardMacro;
import dev.cel.runtime.CelAttribute;
import dev.cel.runtime.CelAttributePattern;
import dev.cel.runtime.CelEvaluationException;
import dev.cel.runtime.CelFunctionBinding;
import dev.cel.runtime.CelLateFunctionBindings;
import dev.cel.runtime.CelStandardFunctions;
import dev.cel.runtime.CelStandardFunctions.StandardFunction;
import dev.cel.runtime.CelUnknownSet;
import dev.cel.runtime.DefaultDispatcher;
import dev.cel.runtime.DescriptorTypeResolver;
import dev.cel.runtime.InternalCelFunctionBinding;
import dev.cel.runtime.PartialVars;
import dev.cel.runtime.Program;
import dev.cel.runtime.RuntimeEquality;
import dev.cel.runtime.RuntimeHelpers;
import dev.cel.runtime.standard.TypeFunction;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(TestParameterInjector.class)
public final class ProgramPlannerTest {
  // Note that the following deps will be built from top-level builder APIs
  private static final CelOptions CEL_OPTIONS = CelOptions.current().build();
  private static final CelTypeProvider TYPE_PROVIDER =
      new CombinedCelTypeProvider(
          DefaultTypeProvider.getInstance(),
          new ProtoMessageTypeProvider(ImmutableSet.of(TestAllTypes.getDescriptor())));
  private static final RuntimeEquality RUNTIME_EQUALITY =
      RuntimeEquality.create(RuntimeHelpers.create(), CEL_OPTIONS);
  private static final CelDescriptorPool DESCRIPTOR_POOL =
      DefaultDescriptorPool.create(
          CelDescriptorUtil.getAllDescriptorsFromFileDescriptor(
              TestAllTypes.getDescriptor().getFile()));
  private static final DynamicProto DYNAMIC_PROTO =
      DynamicProto.create(DefaultMessageFactory.create(DESCRIPTOR_POOL));
  private static final CelValueProvider VALUE_PROVIDER =
      ProtoMessageValueProvider.newInstance(CEL_OPTIONS, DYNAMIC_PROTO);
  private static final CelValueConverter CEL_VALUE_CONVERTER =
      ProtoCelValueConverter.newInstance(DESCRIPTOR_POOL, DYNAMIC_PROTO, CelOptions.DEFAULT);
  private static final CelContainer CEL_CONTAINER =
      CelContainer.newBuilder()
          .setName("cel.expr.conformance.proto3")
          .addAbbreviations("really.long.abbr")
          .build();

  private static final ProgramPlanner PLANNER =
      ProgramPlanner.newPlanner(
          TYPE_PROVIDER,
          VALUE_PROVIDER,
          newDispatcher(),
          CEL_VALUE_CONVERTER,
          CEL_CONTAINER,
          CEL_OPTIONS,
          ImmutableSet.of("late_bound_func"));

  private static final CelCompiler CEL_COMPILER =
      CelCompilerFactory.standardCelCompilerBuilder()
          .setStandardMacros(CelStandardMacro.STANDARD_MACROS)
          .addFunctionDeclarations(
              newFunctionDeclaration(
                  "late_bound_func",
                  newGlobalOverload(
                      "late_bound_func_overload", SimpleType.STRING, SimpleType.STRING)))
          .addVar("msg", StructTypeReference.create(TestAllTypes.getDescriptor().getFullName()))
          .addVar("map_var", MapType.create(SimpleType.STRING, SimpleType.DYN))
          .addVar("int_var", SimpleType.INT)
          .addVar("dyn_var", SimpleType.DYN)
          .addVar("really.long.abbr.ident", SimpleType.DYN)
          .addFunctionDeclarations(
              newFunctionDeclaration("zero", newGlobalOverload("zero_overload", SimpleType.INT)),
              newFunctionDeclaration("error", newGlobalOverload("error_overload", SimpleType.INT)),
              newFunctionDeclaration(
                  "neg",
                  newGlobalOverload("neg_int", SimpleType.INT, SimpleType.INT),
                  newGlobalOverload("neg_double", SimpleType.DOUBLE, SimpleType.DOUBLE)),
              newFunctionDeclaration(
                  "cel.expr.conformance.proto3.power",
                  newGlobalOverload(
                      "power_int_int", SimpleType.INT, SimpleType.INT, SimpleType.INT)),
              newFunctionDeclaration(
                  "concat",
                  newGlobalOverload(
                      "concat_bytes_bytes", SimpleType.BYTES, SimpleType.BYTES, SimpleType.BYTES),
                  newMemberOverload(
                      "bytes_concat_bytes", SimpleType.BYTES, SimpleType.BYTES, SimpleType.BYTES)))
          .addMessageTypes(TestAllTypes.getDescriptor())
          .addLibraries(CelExtensions.optional(), CelExtensions.comprehensions())
          .setContainer(CEL_CONTAINER)
          .build();

  /**
   * Configure dispatcher for testing purposes. This is done manually here, but this should be
   * driven by the top-level runtime APIs in the future
   */
  private static DefaultDispatcher newDispatcher() {
    DefaultDispatcher.Builder builder = DefaultDispatcher.newBuilder();

    // Subsetted StdLib
    CelStandardFunctions stdFunctions =
        CelStandardFunctions.newBuilder()
            .includeFunctions(
                StandardFunction.INDEX,
                StandardFunction.LOGICAL_NOT,
                StandardFunction.ADD,
                StandardFunction.GREATER,
                StandardFunction.GREATER_EQUALS,
                StandardFunction.LESS,
                StandardFunction.DIVIDE,
                StandardFunction.EQUALS,
                StandardFunction.NOT_STRICTLY_FALSE,
                StandardFunction.DYN)
            .build();
    addBindingsToDispatcher(
        builder, stdFunctions.newFunctionBindings(RUNTIME_EQUALITY, CEL_OPTIONS));

    TypeFunction typeFunction =
        TypeFunction.create(
            DescriptorTypeResolver.create(TYPE_PROVIDER, CelValueConverter.getDefaultInstance()));
    addBindingsToDispatcher(
        builder, typeFunction.newFunctionBindings(CEL_OPTIONS, RUNTIME_EQUALITY));

    // Custom functions
    addBindingsToDispatcher(
        builder,
        CelFunctionBinding.fromOverloads(
            "zero", CelFunctionBinding.from("zero_overload", ImmutableList.of(), (unused) -> 0L)));

    addBindingsToDispatcher(
        builder,
        CelFunctionBinding.fromOverloads(
            "error",
            CelFunctionBinding.from(
                "error_overload",
                ImmutableList.of(),
                (unused) -> {
                  throw new IllegalArgumentException("Intentional error");
                })));

    addBindingsToDispatcher(
        builder,
        CelFunctionBinding.fromOverloads(
            "neg",
            CelFunctionBinding.from("neg_int", Long.class, arg -> -arg),
            CelFunctionBinding.from("neg_double", Double.class, arg -> -arg)));

    addBindingsToDispatcher(
        builder,
        CelFunctionBinding.fromOverloads(
            "add", CelFunctionBinding.from("add_int", Long.class, Long.class, (a, b) -> a + b)));

    addBindingsToDispatcher(
        builder,
        CelFunctionBinding.fromOverloads(
            "func",
            CelFunctionBinding.from(
                "func_int",
                ImmutableList.of(Long.class, Long.class, Long.class),
                (args) -> (long) args.length)));

    addBindingsToDispatcher(
        builder,
        CelFunctionBinding.fromOverloads(
            "cel.expr.conformance.proto3.power",
            CelFunctionBinding.from(
                "power_int_int",
                Long.class,
                Long.class,
                (value, power) -> (long) Math.pow(value, power))));

    addBindingsToDispatcher(
        builder,
        CelFunctionBinding.fromOverloads(
            "concat",
            CelFunctionBinding.from(
                "concat_bytes_bytes",
                CelByteString.class,
                CelByteString.class,
                ProgramPlannerTest::concatenateByteArrays),
            CelFunctionBinding.from(
                "bytes_concat_bytes",
                CelByteString.class,
                CelByteString.class,
                ProgramPlannerTest::concatenateByteArrays)));

    return builder.build();
  }

  private static void addBindingsToDispatcher(
      DefaultDispatcher.Builder builder, ImmutableCollection<CelFunctionBinding> overloadBindings) {
    if (overloadBindings.isEmpty()) {
      throw new IllegalArgumentException("Invalid bindings");
    }

    overloadBindings.forEach(
        overload ->
            builder.addOverload(
                ((InternalCelFunctionBinding) overload).getFunctionName(),
                overload.getOverloadId(),
                overload.getArgTypes(),
                overload.isStrict(),
                overload.getDefinition()));
  }

  @TestParameter boolean isParseOnly;

  @Test
  public void plan_notSet_throws() {
    CelAbstractSyntaxTree invalidAst =
        CelAbstractSyntaxTree.newParsedAst(CelExpr.ofNotSet(0L), CelSource.newBuilder().build());

    CelEvaluationException e =
        assertThrows(CelEvaluationException.class, () -> PLANNER.plan(invalidAst));

    assertThat(e).hasMessageThat().contains("evaluation error: Unsupported kind: NOT_SET");
  }

  @Test
  public void plan_constant(@TestParameter ConstantTestCase testCase) throws Exception {
    CelAbstractSyntaxTree ast = compile(testCase.expression);
    Program program = PLANNER.plan(ast);

    Object result = program.eval();

    assertThat(result).isEqualTo(testCase.expected);
  }

  @Test
  public void plan_ident_enum() throws Exception {
    CelAbstractSyntaxTree ast =
        compile(GlobalEnum.getDescriptor().getFullName() + "." + GlobalEnum.GAR);
    Program program = PLANNER.plan(ast);

    Object result = program.eval();

    assertThat(result).isEqualTo(1L);
  }

  @Test
  public void plan_ident_enumContainer() throws Exception {
    CelContainer container = CelContainer.ofName(GlobalEnum.getDescriptor().getFullName());
    CelCompiler compiler =
        CelCompilerFactory.standardCelCompilerBuilder()
            .addMessageTypes(TestAllTypes.getDescriptor())
            .setContainer(container)
            .build();
    CelAbstractSyntaxTree ast = compile(compiler, GlobalEnum.GAR.name());
    ProgramPlanner planner =
        ProgramPlanner.newPlanner(
            TYPE_PROVIDER,
            VALUE_PROVIDER,
            newDispatcher(),
            CEL_VALUE_CONVERTER,
            container,
            CEL_OPTIONS,
            ImmutableSet.of());

    Program program = planner.plan(ast);

    Object result = program.eval();

    assertThat(result).isEqualTo(1L);
  }

  @Test
  public void plan_ident_variable() throws Exception {
    CelAbstractSyntaxTree ast = compile("int_var");
    Program program = PLANNER.plan(ast);

    Object result = program.eval(ImmutableMap.of("int_var", 1L));

    assertThat(result).isEqualTo(1);
  }

  @Test
  public void plan_ident_variableWithStructInList() throws Exception {
    CelAbstractSyntaxTree ast = compile("dyn_var");
    Program program = PLANNER.plan(ast);

    Object result =
        program.eval(
            ImmutableMap.of(
                "dyn_var", ImmutableList.of(TestAllTypes.newBuilder().setSingleInt32(42).build())));

    assertThat(result)
        .isEqualTo(ImmutableList.of(TestAllTypes.newBuilder().setSingleInt32(42).build()));
  }

  @Test
  public void plan_ident_variableWithStructInMap() throws Exception {
    CelAbstractSyntaxTree ast = compile("dyn_var");
    Program program = PLANNER.plan(ast);

    Object result =
        program.eval(
            ImmutableMap.of(
                "dyn_var",
                ImmutableMap.of("foo", TestAllTypes.newBuilder().setSingleInt32(42).build())));

    assertThat(result)
        .isEqualTo(ImmutableMap.of("foo", TestAllTypes.newBuilder().setSingleInt32(42).build()));
  }

  @Test
  public void planIdent_typeLiteral(@TestParameter TypeLiteralTestCase testCase) throws Exception {
    CelAbstractSyntaxTree ast = compile(testCase.expression);
    Program program = PLANNER.plan(ast);

    TypeType result = (TypeType) program.eval();

    assertThat(result).isEqualTo(testCase.type);
  }

  @Test
  public void planIdent_typeLiteral_equality(@TestParameter TypeLiteralTestCase testCase)
      throws Exception {
    // ex: type(bool) == type, type(TestAllTypes) == type
    CelAbstractSyntaxTree ast = compile(String.format("type(%s) == type", testCase.expression));
    Program program = PLANNER.plan(ast);

    boolean result = (boolean) program.eval();

    assertThat(result).isTrue();
  }

  @Test
  public void plan_ident_missingAttribute_throws() throws Exception {
    CelAbstractSyntaxTree ast = compile("int_var");
    Program program = PLANNER.plan(ast);

    CelEvaluationException e = assertThrows(CelEvaluationException.class, program::eval);
    assertThat(e).hasMessageThat().contains("evaluation error at <input>:0: No such attribute(s)");
  }

  @Test
  public void plan_ident_withContainer() throws Exception {
    CelAbstractSyntaxTree ast = compile("abbr.ident");
    Program program = PLANNER.plan(ast);

    Object result = program.eval(ImmutableMap.of("really.long.abbr.ident", 1L));

    assertThat(result).isEqualTo(1);
  }

  @Test
  @SuppressWarnings("unchecked") // test only
  public void plan_createList() throws Exception {
    CelAbstractSyntaxTree ast = compile("[1, 'foo', true, [2, false]]");
    Program program = PLANNER.plan(ast);

    ImmutableList<Object> result = (ImmutableList<Object>) program.eval();

    assertThat(result).containsExactly(1L, "foo", true, ImmutableList.of(2L, false)).inOrder();
  }

  @Test
  @SuppressWarnings("unchecked") // test only
  public void plan_createMap() throws Exception {
    CelAbstractSyntaxTree ast = compile("{'foo': 1, true: 'bar'}");
    Program program = PLANNER.plan(ast);

    ImmutableMap<Object, Object> result = (ImmutableMap<Object, Object>) program.eval();

    assertThat(result).containsExactly("foo", 1L, true, "bar").inOrder();
  }

  @Test
  public void plan_createMap_containsDuplicateKey_throws() throws Exception {
    CelAbstractSyntaxTree ast = compile("{true: 1, false: 2, true: 3}");
    Program program = PLANNER.plan(ast);

    CelEvaluationException e = assertThrows(CelEvaluationException.class, program::eval);
    assertThat(e)
        .hasMessageThat()
        .contains("evaluation error at <input>:20: duplicate map key [true]");
  }

  @Test
  public void plan_createMap_unsupportedKeyType_throws() throws Exception {
    CelAbstractSyntaxTree ast = compile("{1.0: 'foo'}");
    Program program = PLANNER.plan(ast);

    CelEvaluationException e = assertThrows(CelEvaluationException.class, program::eval);
    assertThat(e)
        .hasMessageThat()
        .contains("evaluation error at <input>:1: Unsupported key type: 1.0");
  }

  @Test
  public void plan_createStruct() throws Exception {
    CelAbstractSyntaxTree ast = compile("cel.expr.conformance.proto3.TestAllTypes{}");
    Program program = PLANNER.plan(ast);

    TestAllTypes result = (TestAllTypes) program.eval();

    assertThat(result).isEqualTo(TestAllTypes.getDefaultInstance());
  }

  @Test
  public void plan_createStruct_wrapper() throws Exception {
    CelAbstractSyntaxTree ast = compile("google.protobuf.StringValue { value: 'foo' }");
    Program program = PLANNER.plan(ast);

    String result = (String) program.eval();

    assertThat(result).isEqualTo("foo");
  }

  @Test
  public void planCreateStruct_withFields() throws Exception {
    CelAbstractSyntaxTree ast =
        compile(
            "cel.expr.conformance.proto3.TestAllTypes{"
                + "single_string: 'foo',"
                + "single_bool: true"
                + "}");
    Program program = PLANNER.plan(ast);

    TestAllTypes result = (TestAllTypes) program.eval();

    assertThat(result)
        .isEqualTo(TestAllTypes.newBuilder().setSingleString("foo").setSingleBool(true).build());
  }

  @Test
  public void plan_createStruct_withContainer() throws Exception {
    CelAbstractSyntaxTree ast = compile("TestAllTypes{}");
    Program program = PLANNER.plan(ast);

    TestAllTypes result = (TestAllTypes) program.eval();

    assertThat(result).isEqualTo(TestAllTypes.getDefaultInstance());
  }

  @Test
  public void plan_call_zeroArgs() throws Exception {
    CelAbstractSyntaxTree ast = compile("zero()");
    Program program = PLANNER.plan(ast);

    Long result = (Long) program.eval();

    assertThat(result).isEqualTo(0L);
  }

  @Test
  public void plan_call_throws() throws Exception {
    CelAbstractSyntaxTree ast = compile("error()");
    Program program = PLANNER.plan(ast);

    CelEvaluationException e = assertThrows(CelEvaluationException.class, program::eval);
    assertThat(e)
        .hasMessageThat()
        .contains("evaluation error at <input>:5: Function 'error' failed with arg(s) ''");
    assertThat(e).hasCauseThat().isInstanceOf(IllegalArgumentException.class);
    assertThat(e.getCause()).hasMessageThat().contains("Intentional error");
  }

  @Test
  public void plan_call_oneArg_int() throws Exception {
    CelAbstractSyntaxTree ast = compile("neg(1)");
    Program program = PLANNER.plan(ast);

    Long result = (Long) program.eval();

    assertThat(result).isEqualTo(-1L);
  }

  @Test
  public void plan_call_oneArg_double() throws Exception {
    CelAbstractSyntaxTree ast = compile("neg(2.5)");
    Program program = PLANNER.plan(ast);

    Double result = (Double) program.eval();

    assertThat(result).isEqualTo(-2.5d);
  }

  @Test
  public void plan_call_twoArgs_global() throws Exception {
    CelAbstractSyntaxTree ast = compile("concat(b'abc', b'def')");
    Program program = PLANNER.plan(ast);

    CelByteString result = (CelByteString) program.eval();

    assertThat(result).isEqualTo(CelByteString.of("abcdef".getBytes(UTF_8)));
  }

  @Test
  public void plan_call_twoArgs_receiver() throws Exception {
    CelAbstractSyntaxTree ast = compile("b'abc'.concat(b'def')");
    Program program = PLANNER.plan(ast);

    CelByteString result = (CelByteString) program.eval();

    assertThat(result).isEqualTo(CelByteString.of("abcdef".getBytes(UTF_8)));
  }

  @Test
  public void plan_call_mapIndex() throws Exception {
    CelAbstractSyntaxTree ast = compile("map_var['key'][1]");
    Program program = PLANNER.plan(ast);
    ImmutableMap<Object, Object> mapVarPayload = ImmutableMap.of("key", ImmutableList.of(1L, 2L));

    Long result = (Long) program.eval(ImmutableMap.of("map_var", mapVarPayload));

    assertThat(result).isEqualTo(2L);
  }

  @Test
  public void plan_call_noMatchingOverload_throws() throws Exception {
    CelAbstractSyntaxTree ast = compile("concat(b'abc', dyn_var)");
    Program program = PLANNER.plan(ast);
    String errorMsg =
        "No matching overload for function 'concat'. Overload candidates: concat_bytes_bytes";
    if (isParseOnly) {
      // Parsed-only evaluation includes both overloads as candidates due to dynamic dispatch
      errorMsg += ", bytes_concat_bytes";
    }

    CelEvaluationException e =
        assertThrows(
            CelEvaluationException.class,
            () -> program.eval(ImmutableMap.of("dyn_var", "Impossible Overload")));

    assertThat(e).hasMessageThat().contains(errorMsg);
  }

  @Test
  @TestParameters("{expression: 'true || true', expectedResult: true}")
  @TestParameters("{expression: 'true || false', expectedResult: true}")
  @TestParameters("{expression: 'false || true', expectedResult: true}")
  @TestParameters("{expression: 'false || false', expectedResult: false}")
  @TestParameters("{expression: 'true || (1 / 0 > 2)', expectedResult: true}")
  @TestParameters("{expression: '(1 / 0 > 2) || true', expectedResult: true}")
  public void plan_call_logicalOr_shortCircuit(String expression, boolean expectedResult)
      throws Exception {
    CelAbstractSyntaxTree ast = compile(expression);
    Program program = PLANNER.plan(ast);

    boolean result = (boolean) program.eval();

    assertThat(result).isEqualTo(expectedResult);
  }

  @Test
  @TestParameters("{expression: '(1 / 0 > 2) || (1 / 0 > 2)'}")
  @TestParameters("{expression: 'false || (1 / 0 > 2)'}")
  @TestParameters("{expression: '(1 / 0 > 2) || false'}")
  public void plan_call_logicalOr_throws(String expression) throws Exception {
    CelAbstractSyntaxTree ast = compile(expression);
    Program program = PLANNER.plan(ast);

    CelEvaluationException e = assertThrows(CelEvaluationException.class, program::eval);
    assertThat(e).hasMessageThat().startsWith("evaluation error at <input>:");
    assertThat(e).hasMessageThat().endsWith("/ by zero");
    assertThat(e).hasCauseThat().isInstanceOf(CelDivideByZeroException.class);
    assertThat(e.getErrorCode()).isEqualTo(CelErrorCode.DIVIDE_BY_ZERO);
  }

  @Test
  @TestParameters("{expression: 'true && true', expectedResult: true}")
  @TestParameters("{expression: 'true && false', expectedResult: false}")
  @TestParameters("{expression: 'false && true', expectedResult: false}")
  @TestParameters("{expression: 'false && false', expectedResult: false}")
  @TestParameters("{expression: 'false && (1 / 0 > 2)', expectedResult: false}")
  @TestParameters("{expression: '(1 / 0 > 2) && false', expectedResult: false}")
  public void plan_call_logicalAnd_shortCircuit(String expression, boolean expectedResult)
      throws Exception {
    CelAbstractSyntaxTree ast = compile(expression);
    Program program = PLANNER.plan(ast);

    boolean result = (boolean) program.eval();

    assertThat(result).isEqualTo(expectedResult);
  }

  @Test
  @TestParameters("{expression: '(1 / 0 > 2) && (1 / 0 > 2)'}")
  @TestParameters("{expression: 'true && (1 / 0 > 2)'}")
  @TestParameters("{expression: '(1 / 0 > 2) && true'}")
  public void plan_call_logicalAnd_throws(String expression) throws Exception {
    CelAbstractSyntaxTree ast = compile(expression);
    Program program = PLANNER.plan(ast);

    CelEvaluationException e = assertThrows(CelEvaluationException.class, program::eval);
    assertThat(e).hasMessageThat().startsWith("evaluation error at <input>:");
    assertThat(e).hasMessageThat().endsWith("/ by zero");
    assertThat(e).hasCauseThat().isInstanceOf(CelDivideByZeroException.class);
    assertThat(e.getErrorCode()).isEqualTo(CelErrorCode.DIVIDE_BY_ZERO);
  }

  @Test
  @TestParameters("{expression: 'false ? (1 / 0) > 2 : false', expectedResult: false}")
  @TestParameters("{expression: 'false ? (1 / 0) > 2 : true', expectedResult: true}")
  @TestParameters("{expression: 'true ? false : (1 / 0) > 2', expectedResult: false}")
  @TestParameters("{expression: 'true ? true : (1 / 0) > 2', expectedResult: true}")
  public void plan_call_conditional_shortCircuit(String expression, boolean expectedResult)
      throws Exception {
    CelAbstractSyntaxTree ast = compile(expression);
    Program program = PLANNER.plan(ast);

    boolean result = (boolean) program.eval();

    assertThat(result).isEqualTo(expectedResult);
  }

  @Test
  @TestParameters("{expression: '(1 / 0) > 2 ? true : true'}")
  @TestParameters("{expression: 'true ? (1 / 0) > 2 : true'}")
  @TestParameters("{expression: 'false ? true : (1 / 0) > 2'}")
  public void plan_call_conditional_throws(String expression) throws Exception {
    CelAbstractSyntaxTree ast = compile(expression);
    Program program = PLANNER.plan(ast);

    CelEvaluationException e = assertThrows(CelEvaluationException.class, program::eval);
    assertThat(e).hasMessageThat().startsWith("evaluation error at <input>:");
    assertThat(e).hasMessageThat().endsWith("/ by zero");
    assertThat(e).hasCauseThat().isInstanceOf(CelDivideByZeroException.class);
    assertThat(e.getErrorCode()).isEqualTo(CelErrorCode.DIVIDE_BY_ZERO);
  }

  @Test
  @TestParameters("{expression: 'power(2,3)'}")
  @TestParameters("{expression: 'proto3.power(2,3)'}")
  @TestParameters("{expression: 'conformance.proto3.power(2,3)'}")
  @TestParameters("{expression: 'expr.conformance.proto3.power(2,3)'}")
  @TestParameters("{expression: 'cel.expr.conformance.proto3.power(2,3)'}")
  public void plan_call_withContainer(String expression) throws Exception {
    CelAbstractSyntaxTree ast = compile(expression); // invokes cel.expr.conformance.proto3.power
    Program program = PLANNER.plan(ast);

    Long result = (Long) program.eval();

    assertThat(result).isEqualTo(8);
  }

  @Test
  public void plan_call_lateBoundFunction() throws Exception {
    CelAbstractSyntaxTree ast = compile("late_bound_func('test')");

    Program program = PLANNER.plan(ast);

    String result =
        (String)
            program.eval(
                ImmutableMap.of(),
                CelLateFunctionBindings.from(
                    CelFunctionBinding.from(
                        "late_bound_func_overload", String.class, (arg) -> arg + "_resolved")));

    assertThat(result).isEqualTo("test_resolved");
  }

  @Test
  public void plan_call_typeResolution(@TestParameter TypeObjectTestCase testCase)
      throws Exception {
    CelAbstractSyntaxTree ast = compile(testCase.expression);
    Program program = PLANNER.plan(ast);

    TypeType result = (TypeType) program.eval();

    assertThat(result).isEqualTo(testCase.type);
  }

  @Test
  public void plan_select_protoMessageField() throws Exception {
    CelAbstractSyntaxTree ast = compile("msg.single_string");
    Program program = PLANNER.plan(ast);

    String result =
        (String)
            program.eval(
                ImmutableMap.of("msg", TestAllTypes.newBuilder().setSingleString("foo").build()));

    assertThat(result).isEqualTo("foo");
  }

  @Test
  public void plan_select_nestedProtoMessage() throws Exception {
    CelAbstractSyntaxTree ast = compile("msg.single_nested_message");
    NestedMessage nestedMessage = NestedMessage.newBuilder().setBb(42).build();
    Program program = PLANNER.plan(ast);

    Object result =
        program.eval(
            ImmutableMap.of(
                "msg", TestAllTypes.newBuilder().setSingleNestedMessage(nestedMessage).build()));

    assertThat(result).isEqualTo(nestedMessage);
  }

  @Test
  public void plan_select_nestedProtoMessageField() throws Exception {
    CelAbstractSyntaxTree ast = compile("msg.single_nested_message.bb");
    Program program = PLANNER.plan(ast);

    Object result =
        program.eval(
            ImmutableMap.of(
                "msg",
                TestAllTypes.newBuilder()
                    .setSingleNestedMessage(NestedMessage.newBuilder().setBb(42))
                    .build()));

    assertThat(result).isEqualTo(42);
  }

  @Test
  public void plan_select_safeTraversal() throws Exception {
    CelAbstractSyntaxTree ast = compile("msg.single_nested_message.bb");
    Program program = PLANNER.plan(ast);

    Object result = program.eval(ImmutableMap.of("msg", TestAllTypes.getDefaultInstance()));

    assertThat(result).isEqualTo(0L);
  }

  @Test
  public void plan_select_onCreateStruct() throws Exception {
    CelAbstractSyntaxTree ast =
        compile("cel.expr.conformance.proto3.TestAllTypes{ single_string: 'foo'}.single_string");
    Program program = PLANNER.plan(ast);

    Object result = program.eval();

    assertThat(result).isEqualTo("foo");
  }

  @Test
  public void plan_select_onCreateMap() throws Exception {
    CelAbstractSyntaxTree ast = compile("{'foo':'bar'}.foo");
    Program program = PLANNER.plan(ast);

    Object result = program.eval();

    assertThat(result).isEqualTo("bar");
  }

  @Test
  public void plan_select_onMapVariable() throws Exception {
    CelAbstractSyntaxTree ast = compile("map_var.foo");
    Program program = PLANNER.plan(ast);

    Object result = program.eval(ImmutableMap.of("map_var", ImmutableMap.of("foo", 42L)));

    assertThat(result).isEqualTo(42L);
  }

  @Test
  public void plan_select_mapVarInputMissing_throws() throws Exception {
    CelAbstractSyntaxTree ast = compile("map_var.foo");
    Program program = PLANNER.plan(ast);
    String errorMessage = "evaluation error at <input>:7: No such attribute(s): ";
    if (isParseOnly) {
      errorMessage +=
          "cel.expr.conformance.proto3.map_var, cel.expr.conformance.map_var, cel.expr.map_var,"
              + " cel.map_var, ";
    }
    errorMessage += "map_var";

    CelEvaluationException e =
        assertThrows(CelEvaluationException.class, () -> program.eval(ImmutableMap.of()));

    assertThat(e).hasMessageThat().contains(errorMessage);
  }

  @Test
  public void plan_select_mapVarKeyMissing_throws() throws Exception {
    CelAbstractSyntaxTree ast = compile("map_var.foo");
    Program program = PLANNER.plan(ast);

    CelEvaluationException e =
        assertThrows(
            CelEvaluationException.class,
            () -> program.eval(ImmutableMap.of("map_var", ImmutableMap.of())));
    assertThat(e)
        .hasMessageThat()
        .contains("evaluation error at <input>:7: key 'foo' is not present in map");
  }

  @Test
  public void plan_select_stringQualificationFail_throws() throws Exception {
    CelAbstractSyntaxTree ast = compile("map_var.foo");
    Program program = PLANNER.plan(ast);

    CelEvaluationException e =
        assertThrows(
            CelEvaluationException.class,
            () -> program.eval(ImmutableMap.of("map_var", "bogus string")));

    assertThat(e)
        .hasMessageThat()
        .isEqualTo(
            "evaluation error at <input>:7: Error resolving field 'foo'. Field selections must be"
                + " performed on messages or maps.");
  }

  @Test
  public void plan_select_presenceTest(@TestParameter PresenceTestCase testCase) throws Exception {
    CelAbstractSyntaxTree ast = compile(testCase.expression);
    Program program = PLANNER.plan(ast);

    boolean result =
        (boolean)
            program.eval(
                ImmutableMap.of("msg", testCase.inputParam, "map_var", testCase.inputParam));

    assertThat(result).isEqualTo(testCase.expected);
  }

  @Test
  public void plan_select_badPresenceTest_throws() throws Exception {
    CelAbstractSyntaxTree ast = compile("has(dyn([]).invalid)");
    Program program = PLANNER.plan(ast);

    CelEvaluationException e = assertThrows(CelEvaluationException.class, program::eval);
    assertThat(e)
        .hasMessageThat()
        .contains(
            "Error resolving field 'invalid'. Field selections must be performed on messages or"
                + " maps.");
  }

  @Test
  @TestParameters("{expression: '[1,2,3].exists(x, x > 0) == true'}")
  @TestParameters("{expression: '[1,2,3].exists(x, x < 0) == false'}")
  @TestParameters("{expression: '[1,2,3].exists(i, v, i >= 0 && v > 0) == true'}")
  @TestParameters("{expression: '[1,2,3].exists(i, v, i < 0 || v < 0) == false'}")
  @TestParameters("{expression: '[1,2,3].map(x, x + 1) == [2,3,4]'}")
  public void plan_comprehension_lists(String expression) throws Exception {
    CelAbstractSyntaxTree ast = compile(expression);
    Program program = PLANNER.plan(ast);

    boolean result = (boolean) program.eval();

    assertThat(result).isTrue();
  }

  @Test
  @TestParameters("{expression: '{\"a\": 1, \"b\": 2}.exists(k, k == \"a\")'}")
  @TestParameters("{expression: '{\"a\": 1, \"b\": 2}.exists(k, k == \"c\") == false'}")
  @TestParameters("{expression: '{\"a\": \"b\", \"c\": \"c\"}.exists(k, v, k == v)'}")
  @TestParameters("{expression: '{\"a\": 1, \"b\": 2}.exists(k, v, v == 3) == false'}")
  public void plan_comprehension_maps(String expression) throws Exception {
    CelAbstractSyntaxTree ast = compile(expression);
    Program program = PLANNER.plan(ast);

    boolean result = (boolean) program.eval();

    assertThat(result).isTrue();
  }

  @Test
  @TestParameters("{expression: '[1, 2, 3, 4, 5, 6].map(x, x)'}")
  @TestParameters("{expression: '[1, 2, 3].map(x, [1, 2].map(y, x + y))'}")
  public void plan_comprehension_iterationLimit_throws(String expression) throws Exception {
    CelOptions options = CelOptions.current().comprehensionMaxIterations(5).build();
    ProgramPlanner planner =
        ProgramPlanner.newPlanner(
            TYPE_PROVIDER,
            ProtoMessageValueProvider.newInstance(options, DYNAMIC_PROTO),
            newDispatcher(),
            CEL_VALUE_CONVERTER,
            CEL_CONTAINER,
            options,
            ImmutableSet.of());
    CelAbstractSyntaxTree ast = compile(expression);

    Program program = planner.plan(ast);

    CelEvaluationException e = assertThrows(CelEvaluationException.class, program::eval);
    assertThat(e).hasMessageThat().contains("Iteration budget exceeded: 5");
    assertThat(e.getErrorCode()).isEqualTo(CelErrorCode.ITERATION_BUDGET_EXCEEDED);
  }

  @Test
  public void plan_comprehension_iterationLimit_success() throws Exception {
    CelOptions options = CelOptions.current().comprehensionMaxIterations(10).build();
    ProgramPlanner planner =
        ProgramPlanner.newPlanner(
            TYPE_PROVIDER,
            ProtoMessageValueProvider.newInstance(options, DYNAMIC_PROTO),
            newDispatcher(),
            CEL_VALUE_CONVERTER,
            CEL_CONTAINER,
            options,
            /* lateBoundFunctionNames= */ ImmutableSet.of());
    CelAbstractSyntaxTree ast = compile("[1, 2, 3].map(x, [1, 2].map(y, x + y))");

    Program program = planner.plan(ast);

    Object result = program.eval();
    assertThat(result)
        .isEqualTo(
            ImmutableList.of(
                ImmutableList.of(2L, 3L), ImmutableList.of(3L, 4L), ImmutableList.of(4L, 5L)));
  }

  @Test
  public void plan_partialEval_withWildcardQualification() throws Exception {
    CelCompiler compiler =
        CelCompilerFactory.standardCelCompilerBuilder()
            .addVar("unk", MapType.create(SimpleType.STRING, SimpleType.BOOL))
            .addVar("unk.a", SimpleType.BOOL)
            .addVar("unk.b", SimpleType.BOOL)
            .build();
    CelAbstractSyntaxTree ast = compile(compiler, "unk.a && unk.b && unk['c']");

    Program program = PLANNER.plan(ast);

    CelUnknownSet result =
        (CelUnknownSet)
            program.eval(
                PartialVars.of(
                    CelAttributePattern.create("unk")
                        .qualify(CelAttribute.Qualifier.ofWildCard())));

    assertThat(result)
        .isEqualTo(
            CelUnknownSet.create(
                ImmutableSet.of(
                    CelAttribute.create("unk"),
                    CelAttribute.create("unk").qualify(CelAttribute.Qualifier.ofString("a")),
                    CelAttribute.create("unk").qualify(CelAttribute.Qualifier.ofString("b"))),
                ImmutableSet.of(2L, 5L, 7L)));
  }

  @Test
  public void plan_unaryFunction_withUnknownArg() throws Exception {
    CelCompiler compiler =
        CelCompilerFactory.standardCelCompilerBuilder()
            .addVar("unk", SimpleType.INT)
            .addFunctionDeclarations(
                newFunctionDeclaration(
                    "neg", newGlobalOverload("neg_int", SimpleType.INT, SimpleType.INT)))
            .build();
    CelAbstractSyntaxTree ast = compile(compiler, "neg(unk)");

    Program program = PLANNER.plan(ast);

    CelUnknownSet result =
        (CelUnknownSet) program.eval(PartialVars.of(CelAttributePattern.create("unk")));
    assertThat(result)
        .isEqualTo(
            CelUnknownSet.create(ImmutableSet.of(CelAttribute.create("unk")), ImmutableSet.of(2L)));
  }

  @Test
  public void plan_fold_withUnknownCondition() throws Exception {
    CelCompiler compiler =
        CelCompilerFactory.standardCelCompilerBuilder()
            .setStandardMacros(CelStandardMacro.STANDARD_MACROS)
            .addVar("unk", SimpleType.BOOL)
            .build();
    CelAbstractSyntaxTree ast = compile(compiler, "[1, 2].all(x, unk)");

    Program program = PLANNER.plan(ast);

    CelUnknownSet result =
        (CelUnknownSet) program.eval(PartialVars.of(CelAttributePattern.create("unk")));
    assertThat(result)
        .isEqualTo(
            CelUnknownSet.create(ImmutableSet.of(CelAttribute.create("unk")), ImmutableSet.of(6L)));
  }

  @Test
  public void plan_foldMap_withUnknownCondition() throws Exception {
    CelCompiler compiler =
        CelCompilerFactory.standardCelCompilerBuilder()
            .setStandardMacros(CelStandardMacro.STANDARD_MACROS)
            .addVar("unk", SimpleType.BOOL)
            .build();
    CelAbstractSyntaxTree ast = compile(compiler, "{\"a\": 1, \"b\": 2}.exists(k, unk)");

    Program program = PLANNER.plan(ast);

    CelUnknownSet result =
        (CelUnknownSet) program.eval(PartialVars.of(CelAttributePattern.create("unk")));
    assertThat(result)
        .isEqualTo(
            CelUnknownSet.create(
                ImmutableSet.of(CelAttribute.create("unk")), ImmutableSet.of(10L)));
  }

  @Test
  public void plan_foldList_withUnknownLoopCondition_earlyReturn() throws Exception {
    CelExpr comprehensionExpr =
        CelExpr.ofComprehension(
            1L,
            "x",
            "",
            CelExpr.ofList(
                2L,
                ImmutableList.of(CelExpr.ofConstant(3L, CelConstant.ofValue(1L))),
                ImmutableList.of()),
            "acc",
            CelExpr.ofConstant(4L, CelConstant.ofValue(true)),
            CelExpr.ofIdent(5L, "unk"),
            CelExpr.ofIdent(6L, "acc"),
            CelExpr.ofIdent(7L, "acc"));
    CelAbstractSyntaxTree ast =
        CelAbstractSyntaxTree.newParsedAst(comprehensionExpr, CelSource.newBuilder().build());

    Program program = PLANNER.plan(ast);

    CelUnknownSet result =
        (CelUnknownSet) program.eval(PartialVars.of(CelAttributePattern.create("unk")));
    assertThat(result)
        .isEqualTo(
            CelUnknownSet.create(ImmutableSet.of(CelAttribute.create("unk")), ImmutableSet.of(5L)));
  }

  @Test
  public void plan_foldMap_withUnknownLoopCondition_earlyReturn() throws Exception {
    CelExpr comprehensionExpr =
        CelExpr.ofComprehension(
            1L,
            "k",
            "",
            CelExpr.ofMap(
                2L,
                ImmutableList.of(
                    CelExpr.ofMapEntry(
                        3L,
                        CelExpr.ofConstant(4L, CelConstant.ofValue("a")),
                        CelExpr.ofConstant(5L, CelConstant.ofValue(1L)),
                        false))),
            "acc",
            CelExpr.ofConstant(6L, CelConstant.ofValue(true)),
            CelExpr.ofIdent(7L, "unk"),
            CelExpr.ofIdent(8L, "acc"),
            CelExpr.ofIdent(9L, "acc"));
    CelAbstractSyntaxTree ast =
        CelAbstractSyntaxTree.newParsedAst(comprehensionExpr, CelSource.newBuilder().build());

    Program program = PLANNER.plan(ast);

    CelUnknownSet result =
        (CelUnknownSet) program.eval(PartialVars.of(CelAttributePattern.create("unk")));
    assertThat(result)
        .isEqualTo(
            CelUnknownSet.create(ImmutableSet.of(CelAttribute.create("unk")), ImmutableSet.of(7L)));
  }

  @Test
  public void plan_binaryFunction_withUnknownArg() throws Exception {
    CelCompiler compiler =
        CelCompilerFactory.standardCelCompilerBuilder()
            .addVar("unk", SimpleType.INT)
            .addFunctionDeclarations(
                newFunctionDeclaration(
                    "add",
                    newGlobalOverload("add_int", SimpleType.INT, SimpleType.INT, SimpleType.INT)))
            .build();
    CelAbstractSyntaxTree ast = compile(compiler, "add(1, unk)");

    Program program = PLANNER.plan(ast);

    CelUnknownSet result =
        (CelUnknownSet) program.eval(PartialVars.of(CelAttributePattern.create("unk")));
    assertThat(result)
        .isEqualTo(
            CelUnknownSet.create(ImmutableSet.of(CelAttribute.create("unk")), ImmutableSet.of(3L)));
  }

  @Test
  public void plan_varargsFunction_withUnknownArg() throws Exception {
    CelCompiler compiler =
        CelCompilerFactory.standardCelCompilerBuilder()
            .addVar("unk", SimpleType.INT)
            .addFunctionDeclarations(
                newFunctionDeclaration(
                    "func",
                    newGlobalOverload(
                        "func_int",
                        SimpleType.INT,
                        SimpleType.INT,
                        SimpleType.INT,
                        SimpleType.INT)))
            .build();
    CelAbstractSyntaxTree ast = compile(compiler, "func(1, 2, unk)");

    Program program = PLANNER.plan(ast);

    CelUnknownSet result =
        (CelUnknownSet) program.eval(PartialVars.of(CelAttributePattern.create("unk")));
    assertThat(result)
        .isEqualTo(
            CelUnknownSet.create(ImmutableSet.of(CelAttribute.create("unk")), ImmutableSet.of(4L)));
  }

  @Test
  public void localShadowIdentifier_inSelect() throws Exception {
    CelCompiler celCompiler =
        CelCompilerFactory.standardCelCompilerBuilder()
            .setStandardMacros(CelStandardMacro.STANDARD_MACROS)
            .addVar("cel.example.y", SimpleType.INT)
            .build();
    ProgramPlanner planner =
        ProgramPlanner.newPlanner(
            TYPE_PROVIDER,
            ProtoMessageValueProvider.newInstance(CEL_OPTIONS, DYNAMIC_PROTO),
            newDispatcher(),
            CEL_VALUE_CONVERTER,
            CelContainer.ofName("cel.example"),
            CEL_OPTIONS,
            /* lateBoundFunctionNames= */ ImmutableSet.of());
    CelAbstractSyntaxTree ast = compile(celCompiler, "[{'z': 0}].exists(y, y.z == 0)");

    Program program = planner.plan(ast);

    boolean result =
        (boolean) program.eval(ImmutableMap.of("cel.example.y", ImmutableMap.of("z", 1)));
    assertThat(result).isTrue();
  }

  @Test
  public void localShadowIdentifier_inSelect_globalDisambiguation() throws Exception {
    CelCompiler celCompiler =
        CelCompilerFactory.standardCelCompilerBuilder()
            .setStandardMacros(CelStandardMacro.STANDARD_MACROS)
            .addVar("y.z", SimpleType.INT)
            .build();
    ProgramPlanner planner =
        ProgramPlanner.newPlanner(
            TYPE_PROVIDER,
            ProtoMessageValueProvider.newInstance(CEL_OPTIONS, DYNAMIC_PROTO),
            newDispatcher(),
            CEL_VALUE_CONVERTER,
            CelContainer.ofName("y"),
            CEL_OPTIONS,
            /* lateBoundFunctionNames= */ ImmutableSet.of());
    CelAbstractSyntaxTree ast = compile(celCompiler, "[{'z': 0}].exists(y, y.z == 0 && .y.z == 1)");

    Program program = planner.plan(ast);

    boolean result = (boolean) program.eval(ImmutableMap.of("y.z", 1));
    assertThat(result).isTrue();
  }

  @Test
  public void localShadowIdentifier_withGlobalDisambiguation() throws Exception {
    CelCompiler celCompiler =
        CelCompilerFactory.standardCelCompilerBuilder()
            .setStandardMacros(CelStandardMacro.STANDARD_MACROS)
            .addVar("x", SimpleType.INT)
            .build();
    ProgramPlanner planner =
        ProgramPlanner.newPlanner(
            TYPE_PROVIDER,
            ProtoMessageValueProvider.newInstance(CEL_OPTIONS, DYNAMIC_PROTO),
            newDispatcher(),
            CEL_VALUE_CONVERTER,
            CelContainer.newBuilder().build(),
            CEL_OPTIONS,
            /* lateBoundFunctionNames= */ ImmutableSet.of());
    CelAbstractSyntaxTree ast = compile(celCompiler, "[0].exists(x, x == 0 && .x == 1)");

    Program program = planner.plan(ast);

    boolean result = (boolean) program.eval(ImmutableMap.of("x", 1));
    assertThat(result).isTrue();
  }

  @Test
  public void localDoubleShadowIdentifier_withGlobalDisambiguation() throws Exception {
    CelCompiler celCompiler =
        CelCompilerFactory.standardCelCompilerBuilder()
            .setStandardMacros(CelStandardMacro.STANDARD_MACROS)
            .addVar("x", SimpleType.INT)
            .build();
    ProgramPlanner planner =
        ProgramPlanner.newPlanner(
            TYPE_PROVIDER,
            ProtoMessageValueProvider.newInstance(CEL_OPTIONS, DYNAMIC_PROTO),
            newDispatcher(),
            CEL_VALUE_CONVERTER,
            CelContainer.newBuilder().build(),
            CEL_OPTIONS,
            /* lateBoundFunctionNames= */ ImmutableSet.of());
    CelAbstractSyntaxTree ast = compile(celCompiler, "[0].exists(x, [x+1].exists(x, x == .x))");

    Program program = planner.plan(ast);

    boolean result = (boolean) program.eval(ImmutableMap.of("x", 1));
    assertThat(result).isTrue();
  }

  @Test
  public void plan_customFunctionReturningUnknown_fieldSelection() throws Exception {
    CelCompiler compiler =
        CelCompilerFactory.standardCelCompilerBuilder()
            .addMessageTypes(TestAllTypes.getDescriptor())
            .addFunctionDeclarations(
                newFunctionDeclaration(
                    "getMsg",
                    newGlobalOverload(
                        "getMsg_overload",
                        StructTypeReference.create(TestAllTypes.getDescriptor().getFullName()))))
            .build();
    CelAbstractSyntaxTree ast = compile(compiler, "getMsg().single_int32");

    DefaultDispatcher.Builder dispatcherBuilder = DefaultDispatcher.newBuilder();
    addBindingsToDispatcher(
        dispatcherBuilder,
        CelFunctionBinding.fromOverloads(
            "getMsg",
            CelFunctionBinding.from(
                "getMsg_overload",
                ImmutableList.of(),
                (unused) -> CelUnknownSet.create(CelAttribute.create("custom_msg")))));
    ProgramPlanner planner =
        ProgramPlanner.newPlanner(
            TYPE_PROVIDER,
            VALUE_PROVIDER,
            dispatcherBuilder.build(),
            CEL_VALUE_CONVERTER,
            CEL_CONTAINER,
            CEL_OPTIONS,
            ImmutableSet.of());

    Program program = planner.plan(ast);

    Object result = program.eval();
    assertThat(((CelUnknownSet) result).attributes())
        .containsExactly(CelAttribute.create("custom_msg"));
  }

  @Test
  public void plan_customFunctionReturningUnknown_binaryOperation() throws Exception {
    CelCompiler compiler =
        CelCompilerFactory.standardCelCompilerBuilder()
            .addFunctionDeclarations(
                newFunctionDeclaration(
                    "getUnknownInt", newGlobalOverload("getUnknownInt_overload", SimpleType.INT)))
            .build();
    CelAbstractSyntaxTree ast = compile(compiler, "getUnknownInt() == 100");

    DefaultDispatcher.Builder dispatcherBuilder = DefaultDispatcher.newBuilder();
    CelStandardFunctions stdFunctions =
        CelStandardFunctions.newBuilder().includeFunctions(StandardFunction.EQUALS).build();
    addBindingsToDispatcher(
        dispatcherBuilder, stdFunctions.newFunctionBindings(RUNTIME_EQUALITY, CEL_OPTIONS));
    addBindingsToDispatcher(
        dispatcherBuilder,
        CelFunctionBinding.fromOverloads(
            "getUnknownInt",
            CelFunctionBinding.from(
                "getUnknownInt_overload",
                ImmutableList.of(),
                (unused) -> CelUnknownSet.create(CelAttribute.create("custom_int")))));
    ProgramPlanner planner =
        ProgramPlanner.newPlanner(
            TYPE_PROVIDER,
            VALUE_PROVIDER,
            dispatcherBuilder.build(),
            CEL_VALUE_CONVERTER,
            CEL_CONTAINER,
            CEL_OPTIONS,
            ImmutableSet.of());

    Program program = planner.plan(ast);

    Object result = program.eval();
    assertThat(((CelUnknownSet) result).attributes())
        .containsExactly(CelAttribute.create("custom_int"));
  }

  @Test
  public void plan_variableAsCelUnknownSet_propagatesUnknown() throws Exception {
    CelCompiler compiler =
        CelCompilerFactory.standardCelCompilerBuilder()
            .addMessageTypes(TestAllTypes.getDescriptor())
            .addVar("msg", StructTypeReference.create(TestAllTypes.getDescriptor().getFullName()))
            .addVar("x", SimpleType.INT)
            .build();
    CelAbstractSyntaxTree ast1 = compile(compiler, "x + 1");
    CelAbstractSyntaxTree ast2 = compile(compiler, "msg.single_int32");

    DefaultDispatcher.Builder dispatcherBuilder = DefaultDispatcher.newBuilder();
    CelStandardFunctions stdFunctions =
        CelStandardFunctions.newBuilder().includeFunctions(StandardFunction.ADD).build();
    addBindingsToDispatcher(
        dispatcherBuilder, stdFunctions.newFunctionBindings(RUNTIME_EQUALITY, CEL_OPTIONS));
    ProgramPlanner planner =
        ProgramPlanner.newPlanner(
            TYPE_PROVIDER,
            VALUE_PROVIDER,
            dispatcherBuilder.build(),
            CEL_VALUE_CONVERTER,
            CEL_CONTAINER,
            CEL_OPTIONS,
            ImmutableSet.of());

    ImmutableMap<String, Object> vars =
        ImmutableMap.of(
            "msg", CelUnknownSet.create(CelAttribute.create("custom_msg")),
            "x", CelUnknownSet.create(CelAttribute.create("custom_x")));

    assertThat(planner.plan(ast1).eval(vars))
        .isEqualTo(CelUnknownSet.create(CelAttribute.create("custom_x")));
    assertThat(planner.plan(ast2).eval(vars))
        .isEqualTo(CelUnknownSet.create(CelAttribute.create("custom_msg")));
  }

  private CelAbstractSyntaxTree compile(String expression) throws Exception {
    return compile(CEL_COMPILER, expression);
  }

  private CelAbstractSyntaxTree compile(CelCompiler compiler, String expression) throws Exception {
    CelAbstractSyntaxTree ast = compiler.parse(expression).getAst();
    if (isParseOnly) {
      return ast;
    }

    return compiler.check(ast).getAst();
  }

  private static CelByteString concatenateByteArrays(CelByteString bytes1, CelByteString bytes2) {
    if (bytes1.isEmpty()) {
      return bytes2;
    }

    if (bytes2.isEmpty()) {
      return bytes1;
    }

    return bytes1.concat(bytes2);
  }

  @SuppressWarnings("ImmutableEnumChecker") // Test only
  private enum ConstantTestCase {
    NULL("null", NullValue.NULL_VALUE),
    BOOLEAN("true", true),
    INT64("42", 42L),
    UINT64("42u", UnsignedLong.valueOf(42)),
    DOUBLE("1.5", 1.5d),
    STRING("'hello world'", "hello world"),
    BYTES("b'abc'", CelByteString.of("abc".getBytes(UTF_8)));

    private final String expression;
    private final Object expected;

    ConstantTestCase(String expression, Object expected) {
      this.expression = expression;
      this.expected = expected;
    }
  }

  @SuppressWarnings("ImmutableEnumChecker") // Test only
  private enum TypeLiteralTestCase {
    BOOL("bool", SimpleType.BOOL),
    BYTES("bytes", SimpleType.BYTES),
    DOUBLE("double", SimpleType.DOUBLE),
    INT("int", SimpleType.INT),
    UINT("uint", SimpleType.UINT),
    STRING("string", SimpleType.STRING),
    LIST("list", ListType.create(SimpleType.DYN)),
    MAP("map", MapType.create(SimpleType.DYN, SimpleType.DYN)),
    NULL("null_type", SimpleType.NULL_TYPE),
    DURATION("google.protobuf.Duration", SimpleType.DURATION),
    TIMESTAMP("google.protobuf.Timestamp", SimpleType.TIMESTAMP),
    OPTIONAL("optional_type", OptionalType.create(SimpleType.DYN)),
    PROTO_MESSAGE_TYPE(
        "cel.expr.conformance.proto3.TestAllTypes",
        TYPE_PROVIDER.findType(TestAllTypes.getDescriptor().getFullName()).get());

    private final String expression;
    private final TypeType type;

    TypeLiteralTestCase(String expression, CelType type) {
      this.expression = expression;
      this.type = TypeType.create(type);
    }
  }

  private enum TypeObjectTestCase {
    BOOL("type(true)", SimpleType.BOOL),
    INT("type(1)", SimpleType.INT),
    DOUBLE("type(1.5)", SimpleType.DOUBLE),
    PROTO_MESSAGE_TYPE(
        "type(cel.expr.conformance.proto3.TestAllTypes{})",
        TYPE_PROVIDER.findType("cel.expr.conformance.proto3.TestAllTypes").get());

    private final String expression;
    private final TypeType type;

    TypeObjectTestCase(String expression, CelType type) {
      this.expression = expression;
      this.type = TypeType.create(type);
    }
  }

  @SuppressWarnings("Immutable") // Test only
  private enum PresenceTestCase {
    PROTO_FIELD_PRESENT(
        "has(msg.single_string)", TestAllTypes.newBuilder().setSingleString("foo").build(), true),
    PROTO_FIELD_ABSENT("has(msg.single_string)", TestAllTypes.getDefaultInstance(), false),
    PROTO_NESTED_FIELD_PRESENT(
        "has(msg.single_nested_message.bb)",
        TestAllTypes.newBuilder()
            .setSingleNestedMessage(NestedMessage.newBuilder().setBb(42).build())
            .build(),
        true),
    PROTO_NESTED_FIELD_ABSENT(
        "has(msg.single_nested_message.bb)", TestAllTypes.getDefaultInstance(), false),
    PROTO_MAP_KEY_PRESENT("has(map_var.foo)", ImmutableMap.of("foo", "1"), true),
    PROTO_MAP_KEY_ABSENT("has(map_var.bar)", ImmutableMap.of(), false);

    private final String expression;
    private final Object inputParam;
    private final Object expected;

    PresenceTestCase(String expression, Object inputParam, Object expected) {
      this.expression = expression;
      this.inputParam = inputParam;
      this.expected = expected;
    }
  }
}
