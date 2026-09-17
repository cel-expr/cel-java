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

package dev.cel.checker;

import static com.google.common.base.Preconditions.checkArgument;
import static dev.cel.common.types.CelProtoTypes.format;

import dev.cel.expr.CheckedExpr;
import dev.cel.expr.Decl;
import dev.cel.expr.Expr.CreateStruct.EntryOrBuilder;
import dev.cel.expr.ExprOrBuilder;
import dev.cel.expr.Reference;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.protobuf.DescriptorProtos.FileDescriptorSet;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
// import com.google.testing.testsize.MediumTest;
import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.CelContainer;
import dev.cel.common.CelMutableAst;
import dev.cel.common.CelProtoAbstractSyntaxTree;
import dev.cel.common.CelProtoDeclConverter;
import dev.cel.common.ast.CelConstant;
import dev.cel.common.internal.EnvVisitable;
import dev.cel.common.internal.EnvVisitor;
import dev.cel.common.internal.Errors;
import dev.cel.common.types.CelType;
import dev.cel.common.types.ListType;
import dev.cel.common.types.MapType;
import dev.cel.common.types.NullableType;
import dev.cel.common.types.OpaqueType;
import dev.cel.common.types.OptionalType;
import dev.cel.common.types.ProtoMessageTypeProvider;
import dev.cel.common.types.SimpleType;
import dev.cel.common.types.StructTypeReference;
import dev.cel.common.types.TypeParamType;
import dev.cel.common.types.TypeType;
import dev.cel.expr.conformance.proto3.TestAllTypes;
import dev.cel.parser.CelMacro;
import dev.cel.testing.CelAdorner;
import dev.cel.testing.CelBaselineTestCase;
import dev.cel.testing.CelDebug;
import dev.cel.testing.testdata.proto3.StandaloneGlobalEnum;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Tests for the CEL {@link ExprChecker}. */
// @MediumTest
@RunWith(TestParameterInjector.class)
public class ExprCheckerTest extends CelBaselineTestCase {

  /** Helper to run a test for configured instance variables. */
  private void runTest() throws Exception {
    CelAbstractSyntaxTree ast =
        prepareTest(
            Arrays.asList(
                StandaloneGlobalEnum.getDescriptor().getFile(),
                TestAllTypes.getDescriptor().getFile(),
                dev.cel.expr.conformance.proto2.TestAllTypes.getDescriptor().getFile()));
    if (ast != null) {
      testOutput()
          .println(
              CelDebug.toAdornedDebugString(
                  CelProtoAbstractSyntaxTree.fromCelAst(ast).getExpr(),
                  new CheckedExprAdorner(
                      CelProtoAbstractSyntaxTree.fromCelAst(ast).toCheckedExpr())));
    }
    testOutput().println();
  }

  @SuppressWarnings("CheckReturnValue")
  private void runErroneousTest(CelAbstractSyntaxTree parsedAst) {
    checkArgument(!parsedAst.isChecked());
    Errors errors = new Errors("<input>", source);
    Env env = Env.unconfigured(errors, TEST_OPTIONS);
    ExprChecker.typecheck(env, container, parsedAst, Optional.absent());
    testOutput().println(errors.getAllErrorsAsString());
    testOutput().println();
  }

  // Standard
  // =========

  @Test
  public void standardEnvDump() throws Exception {
    source = "'redundant expression so the env is constructed and can be printed'";
    runTest();
    testOutput().println();
    testOutput().println("Standard environment:");

    ((EnvVisitable) celCompiler)
        .accept(
            new EnvVisitor() {
              @Override
              public void visitDecl(String name, List<Decl> decls) {
                // TODO: Remove proto to native type adaptation after changing
                // interface
                for (Decl decl : decls) {
                  if (decl.hasFunction()) {
                    testOutput()
                        .println(
                            formatFunctionDecl(CelProtoDeclConverter.declToCelFunctionDecl(decl)));
                  } else if (decl.hasIdent()) {
                    testOutput()
                        .println(formatVarDecl(CelProtoDeclConverter.declToCelVarDecl(decl)));
                  } else {
                    throw new IllegalArgumentException("Invalid declaration: " + decl);
                  }
                }
              }

              @Override
              public void visitMacro(CelMacro macro) {}
            });
  }

  // Operators
  // =========

  @Test
  public void operatorsBool() throws Exception {
    source = "false && !true || false ? 2 : 3";
    runTest();
  }

  @Test
  public void operatorsInt64() throws Exception {
    source = "1 + 2 * 3 - 1 / 2 == 6 % 1";
    runTest();
  }

  @Test
  public void operatorsUInt64() throws Exception {
    source = "1u + 2u * 3u - 1u / 2u == 6u % 1u";
    runTest();
  }

  @Test
  public void operatorsDouble() throws Exception {
    source = "1.0 + 2.0 * 3.0 - 1.0 / 2.20202 != 66.6";
    runTest();
  }

  @Test
  public void operatorsString() throws Exception {
    source = "\"abc\" + \"def\"";
    runTest();
  }

  @Test
  public void operatorsBytes() throws Exception {
    source = "b\"abc\" + b\"def\"";
    runTest();
  }

  @Test
  public void operatorsConditional() throws Exception {
    declareVariable("x", StructTypeReference.create("cel.expr.conformance.proto3.TestAllTypes"));
    source = "false ? x.single_timestamp : null";
    runTest();
  }

  // Name References
  // ===============

  @Test
  public void referenceTypeRelative() throws Exception {
    source = "proto3.TestAllTypes";
    container = CelContainer.ofName("cel.expr.conformance.TestAllTypes");
    runTest();
  }

  @Test
  public void referenceTypeAbsolute() throws Exception {
    source = ".cel.expr.conformance.proto3.TestAllTypes";
    runTest();

    declareVariable("app.config", SimpleType.INT);
    source = "[0].exists(app, .app.config == 1)";
    runTest();
  }

  @Test
  public void referenceValue() throws Exception {
    declareVariable(
        "container.x", StructTypeReference.create("cel.expr.conformance.proto3.TestAllTypes"));
    source = "x";
    container = CelContainer.ofName("container");
    runTest();
  }

  @Test
  public void referenceUndefinedError() throws Exception {
    source = "1 + x";
    runTest();
  }

  // Messages
  // ========

  @Test
  public void anyMessage() throws Exception {
    declareVariable("x", SimpleType.ANY);
    declareVariable("y", NullableType.create(SimpleType.INT));
    source =
        "x == google.protobuf.Any{"
            + "type_url:'types.googleapis.com/cel.expr.conformance.proto3.TestAllTypes'}"
            + " && x.single_nested_message.bb == 43 || x =="
            + " cel.expr.conformance.proto3.TestAllTypes{} || y < x|| x >= x";
    runTest();
  }

  @Test
  public void messageFieldSelect() throws Exception {
    declareVariable("x", StructTypeReference.create("cel.expr.conformance.proto3.TestAllTypes"));
    source =
        "x.single_nested_message.bb == 43 && has(x.single_nested_message)  && has(x.single_int32)"
            + " && has(x.repeated_int32) && has(x.map_int64_nested_type)";
    runTest();
  }

  @Test
  public void containers() throws Exception {
    container =
        CelContainer.newBuilder()
            .setName("dev.cel.testing.testdata.proto3.StandaloneGlobalEnum")
            .addAlias("p3_alias", "cel.expr.conformance.proto3")
            .addAlias("foo_bar_alias", "foo.bar")
            .addAlias("foo_bar_baz_alias", "foo.bar.baz")
            .addAbbreviations("cel.expr.conformance.proto2", "cel.expr.conformance.proto3")
            .build();
    source = "p3_alias.TestAllTypes{}";
    runTest();

    source = "proto2.TestAllTypes{}";
    runTest();

    source = "proto3.TestAllTypes{}";
    runTest();

    source = "SGAR"; // From StandaloneGlobalEnum
    runTest();

    declareVariable("foo.bar", SimpleType.STRING);
    declareFunction(
        "baz",
        memberOverload(
            "foo_bar_baz_overload", ImmutableList.of(SimpleType.STRING), SimpleType.DYN));
    // Member call of "baz()" on "foo.bar" identifier
    source = "foo_bar_alias.baz()";
    runTest();

    declareFunction(
        "foo.bar.baz.qux",
        globalOverload("foo_bar_baz_qux_overload", ImmutableList.of(), SimpleType.DYN));
    // Global call of "foo.bar.baz.qux" as a fully qualified name
    source = "foo_bar_baz_alias.qux()";
    runTest();
  }

  @Test
  public void messageCreationError() throws Exception {
    declareVariable("x", SimpleType.INT);
    source = "x{foo: 1}";
    runTest();

    declareVariable("y", TypeType.create(SimpleType.INT));
    source = "y{foo: 1}";
    runTest();

    declareVariable("z", TypeType.create(StructTypeReference.create("msg_without_descriptor")));
    source = "z{foo: 1}";
    runTest();
  }

  @Test
  public void messageFieldSelectError() throws Exception {
    declareVariable("x", StructTypeReference.create("cel.expr.conformance.proto3.TestAllTypes"));
    source = "x.single_nested_message.undefined == x.undefined";
    runTest();
  }

  // Lists
  // =====

  @Test
  public void listOperators() throws Exception {
    declareVariable(
        "x",
        ListType.create(StructTypeReference.create("cel.expr.conformance.proto3.TestAllTypes")));
    source = "(x + x)[1].single_int32 == size(x)";
    runTest();

    source = "x.size() == size(x)";
    runTest();
  }

  @Test
  public void listRepeatedOperators() throws Exception {
    declareVariable("x", StructTypeReference.create("cel.expr.conformance.proto3.TestAllTypes"));
    source = "x.repeated_int64[x.single_int32] == 23";
    runTest();
  }

  @Test
  public void listIndexTypeError() throws Exception {
    declareVariable(
        "x",
        ListType.create(StructTypeReference.create("cel.expr.conformance.proto3.TestAllTypes")));
    source = "x[1u]";
    runTest();
  }

  @Test
  public void identError() throws Exception {
    source = "undeclared_ident";
    runTest();
  }

  @Test
  public void listElemTypeError() throws Exception {
    declareVariable(
        "x",
        ListType.create(StructTypeReference.create("cel.expr.conformance.proto3.TestAllTypes")));
    declareVariable("y", ListType.create(SimpleType.INT));
    source = "x + y";
    runTest();
  }

  // Maps
  // ====

  @Test
  public void mapOperators() throws Exception {
    declareVariable(
        "x",
        MapType.create(
            SimpleType.STRING,
            StructTypeReference.create("cel.expr.conformance.proto3.TestAllTypes")));
    source = "x[\"a\"].single_int32 == 23";
    runTest();

    source = "x.size() == size(x)";
    runTest();
  }

  @Test
  public void mapIndexTypeError() throws Exception {
    declareVariable(
        "x",
        MapType.create(
            SimpleType.STRING,
            StructTypeReference.create("cel.expr.conformance.proto3.TestAllTypes")));
    source = "x[2].single_int32 == 23";
    runTest();
  }

  @Test
  public void mapEmpty() throws Exception {
    declareVariable("x", StructTypeReference.create("cel.expr.conformance.proto3.TestAllTypes"));
    source = "size(x.map_int64_nested_type) == 0";
    runTest();
  }

  // Wrappers
  // ========

  @Test
  public void wrapper() throws Exception {
    declareVariable("x", StructTypeReference.create("cel.expr.conformance.proto3.TestAllTypes"));
    source = "x.single_int64_wrapper + 1 != 23";
    runTest();
  }

  @Test
  public void equalsWrapper() throws Exception {
    declareVariable("x", StructTypeReference.create("cel.expr.conformance.proto3.TestAllTypes"));
    source =
        "x.single_int64_wrapper == 1 && "
            + "x.single_int32_wrapper != 2 && "
            + "x.single_double_wrapper != 2.0 && "
            + "x.single_float_wrapper == 1.0 && "
            + "x.single_uint32_wrapper == 1u && "
            + "x.single_uint64_wrapper != 42u";
    runTest();
  }

  // Nullable
  // ========

  @Test
  public void nullableWrapper() throws Exception {
    declareVariable("x", StructTypeReference.create("cel.expr.conformance.proto3.TestAllTypes"));
    source = "x.single_int64_wrapper == null";
    runTest();
  }

  @Test
  public void nullableMessage() throws Exception {
    declareVariable("x", StructTypeReference.create("cel.expr.conformance.proto3.TestAllTypes"));
    source = "x.single_nested_message != null";
    runTest();

    container = CelContainer.ofName("cel.expr.conformance.proto3.TestAllTypesProto");
    source = "null == TestAllTypes{} || TestAllTypes{} == null";
    runTest();
  }

  @Test
  public void nullNull() throws Exception {
    source = "null == null && null != null";
    runTest();
  }

  @Test
  public void nullablePrimitiveError() throws Exception {
    declareVariable("x", StructTypeReference.create("cel.expr.conformance.proto3.TestAllTypes"));
    source = "x.single_int64 != null";
    runTest();
  }

  // Dynamic Types
  // =============

  @Test
  public void dynOperators() throws Exception {
    declareVariable("x", StructTypeReference.create("cel.expr.conformance.proto3.TestAllTypes"));
    source = "x.single_value + 1 / x.single_struct.y == 23";
    runTest();
  }

  @Test
  public void dynOperatorsAtRuntime() throws Exception {
    declareVariable("x", StructTypeReference.create("cel.expr.conformance.proto3.TestAllTypes"));
    source = "x.single_value[23] + x.single_struct['y']";
    runTest();
  }

  @Test
  public void flexibleTypeAdaption() throws Exception {
    source = "size([] + [1])";
    runTest();

    source = "[1] + [dyn('string')]";
    runTest();

    source = "[dyn('string')] + [1]";
    runTest();

    source = "([[[1]], [[2]], [[3]]][0][0] + [2, 3, {'four': {'five': 'six'}}])[3]";
    runTest();

    declareVariable("a", TypeParamType.create("T"));
    source = "a.b + 1 == a[0]";
    runTest();

    CelType keyParam = TypeParamType.create("A");
    CelType valParam = TypeParamType.create("B");
    CelType mapType = MapType.create(keyParam, valParam);
    declareFunction(
        "merge", globalOverload("merge_maps", ImmutableList.of(mapType, mapType), mapType));
    source = "merge({'hello': dyn(1)}, {'world': 2.0})";
    runTest();

    source = "1 in dyn([1, 2, 3])";
    runTest();
  }

  @Test
  public void userFunctionOverlappingOverloadsError() throws Exception {
    declareFunction(
        "func",
        memberOverload("overlapping_overload_1", ImmutableList.of(SimpleType.INT), SimpleType.INT),
        memberOverload("overlapping_overload_2", ImmutableList.of(SimpleType.INT), SimpleType.INT));
    source = "func(1)";
    runTest();
  }

  // Json Types
  // ==========

  @Test
  public void jsonType() throws Exception {
    declareVariable("x", StructTypeReference.create("google.protobuf.Struct"));
    declareVariable("y", StructTypeReference.create("google.protobuf.ListValue"));
    declareVariable("z", StructTypeReference.create("google.protobuf.Value"));
    source =
        "x[\"claims\"][\"groups\"][0].name == \"dummy\" "
            + "&& x.claims[\"exp\"] == y[1].time "
            + "&& x.claims.structured == {'key': z} "
            + "&& z == 1.0";
    runTest();
  }

  @Test
  public void jsonTypeNullConstruction() throws Exception {
    // Ok
    source = "google.protobuf.Value{null_value: google.protobuf.NullValue.NULL_VALUE}";
    runTest();

    // Error
    source = "google.protobuf.Value{null_value: null}";
    runTest();

    // Ok
    source = "cel.expr.conformance.proto3.TestAllTypes{single_value: null}";
    runTest();

    // Ok but not expected (int coerced to double/json number 0.0)
    source =
        "cel.expr.conformance.proto3.TestAllTypes{single_value:"
            + " google.protobuf.NullValue.NULL_VALUE}";
    runTest();

    // Error
    source = "cel.expr.conformance.proto3.TestAllTypes{null_value: null}";
    runTest();

    // Ok
    source =
        "cel.expr.conformance.proto3.TestAllTypes{null_value:"
            + " google.protobuf.NullValue.NULL_VALUE}";
    runTest();
  }

  @Test
  public void jsonTypeNullAccess() throws Exception {
    source = "google.protobuf.Value{null_value: google.protobuf.NullValue.NULL_VALUE} == null";
    runTest();

    source = "cel.expr.conformance.proto3.TestAllTypes{single_value: null}.single_value == null";
    runTest();

    source =
        "cel.expr.conformance.proto3.TestAllTypes{single_value:"
            + " google.protobuf.NullValue.NULL_VALUE}.single_value == null";
    runTest();

    // Error
    source =
        "cel.expr.conformance.proto3.TestAllTypes{null_value:"
            + " google.protobuf.NullValue.NULL_VALUE}.null_value == null";
    runTest();

    // Ok
    source =
        "cel.expr.conformance.proto3.TestAllTypes{null_value:"
            + " google.protobuf.NullValue.NULL_VALUE}.null_value == 0";
    runTest();

    // Error
    source = "google.protobuf.NullValue.NULL_VALUE == null";
    runTest();

    // Ok
    source = "google.protobuf.NullValue.NULL_VALUE == 0";
    runTest();
  }

  // Call Style and User Functions
  // =============================

  @Test
  public void callStyle() throws Exception {
    CelType param = TypeParamType.create("A");
    // Note, the size() function here is added in a separate scope from the standard declaration
    // set, but the environment ensures that the standard and custom overloads are returned together
    // during function resolution time.
    declareFunction(
        "size",
        memberOverload("my_size", ImmutableList.of(ListType.create(param)), SimpleType.INT));
    declareVariable("x", ListType.create(SimpleType.INT));
    source = "size(x) == x.size()";
    runTest();
  }

  @Test
  public void userFunction() throws Exception {
    declareFunction(
        "myfun",
        memberOverload(
            "myfun_instance",
            ImmutableList.of(SimpleType.INT, SimpleType.BOOL, SimpleType.UINT),
            SimpleType.INT),
        globalOverload(
            "myfun_static",
            ImmutableList.of(SimpleType.INT, SimpleType.BOOL, SimpleType.UINT),
            SimpleType.INT));
    source = "myfun(1, true, 3u) + 1.myfun(false, 3u).myfun(true, 42u)";
    runTest();
  }

  @Test
  public void namespacedFunctions() throws Exception {
    declareFunction(
        "ns.func",
        globalOverload("ns_func_overload", ImmutableList.of(SimpleType.STRING), SimpleType.INT));
    source = "ns.func('hello')";
    runTest();

    declareFunction(
        "member",
        memberOverload(
            "ns_member_overload",
            ImmutableList.of(SimpleType.INT, SimpleType.INT),
            SimpleType.INT));

    source = "ns.func('hello').member(ns.func('test'))";
    runTest();

    source = "{ns.func('test'): 2}";
    runTest();

    source = "{2: ns.func('test')}";
    runTest();

    source = "[ns.func('test'), 2]";
    runTest();

    source = "[ns.func('test')].map(x, x * 2)";
    runTest();

    source = "[1, 2].map(x, x * ns.func('test'))";
    runTest();

    container = CelContainer.ofName("ns");
    source = "func('hello')";
    runTest();

    source = "func('hello').member(func('test'))";
    runTest();
  }

  @Test
  public void namespacedVariables() throws Exception {
    container = CelContainer.ofName("ns");
    declareVariable("ns.x", SimpleType.INT);
    source = "x";
    runTest();

    container = CelContainer.ofName("cel.expr.conformance.proto3");
    CelType messageType = StructTypeReference.create("cel.expr.conformance.proto3.TestAllTypes");
    declareVariable("cel.expr.conformance.proto3.msgVar", messageType);
    source = "msgVar.single_int32";
    runTest();
  }

  @Test
  public void userFunctionMultipleOverloadsWithSanitization() throws Exception {
    CelType structType = StructTypeReference.create("google.protobuf.Struct");
    declareVariable("s", structType);
    declareFunction(
        "myfun",
        globalOverload("myfun_int", ImmutableList.of(SimpleType.INT), SimpleType.INT),
        globalOverload("myfun_struct", ImmutableList.of(structType), SimpleType.INT));
    source = "myfun(1) + myfun(s)";
    runTest();
  }

  @Test
  public void userFunctionOverlaps() throws Exception {
    CelType param = TypeParamType.create("TEST");
    // Note, the size() function here shadows the definition of the size() function in the standard
    // declaration set. The type param name is chosen as 'TEST' to make sure not to conflict with
    // the standard environment type param name for the same overload signature.
    declareFunction(
        "size",
        globalOverload("my_size", ImmutableList.of(ListType.create(param)), SimpleType.UINT));
    declareVariable("x", ListType.create(SimpleType.INT));
    source = "size(x) == 1u";
    runTest();
  }

  @Test
  public void userFunctionAddsOverload() throws Exception {
    CelType messageType = StructTypeReference.create("cel.expr.conformance.proto3.TestAllTypes");
    declareVariable("x", messageType);
    declareFunction(
        "size", globalOverload("size_message", ImmutableList.of(messageType), SimpleType.INT));
    source = "size(x) > 4";
    runTest();
  }

  @Test
  public void userFunctionAddsMacroError() throws Exception {
    declareFunction(
        "has", globalOverload("has_id", ImmutableList.of(SimpleType.DYN), SimpleType.DYN));
    source = "false";
    runTest();
  }

  // Proto2
  // ======

  @Test
  public void proto2PrimitiveField() throws Exception {
    declareVariable("x", StructTypeReference.create("cel.expr.conformance.proto2.TestAllTypes"));
    source = "x.single_fixed32 != 0u && x.single_fixed64 > 1u && x.single_int32 != null";
    runTest();
    source = "x.nestedgroup.single_name == ''";
    runTest();
  }

  // Aggregates
  // ==========

  @Test
  public void aggregateMessage() throws Exception {
    container = CelContainer.ofName("cel.expr.conformance.proto3");
    source = "TestAllTypes{single_int32: 1, single_int64: 2}";
    runTest();
  }

  @Test
  public void aggregateMessageFieldUndefinedError() throws Exception {
    container = CelContainer.ofName("cel.expr.conformance.proto3");
    source = "TestAllTypes{single_int32: 1, undefined: 2}";
    runTest();
  }

  @Test
  public void aggregateMessageFieldTypeError() throws Exception {
    container = CelContainer.ofName("cel.expr.conformance.proto3");
    source = "TestAllTypes{single_int32: 1u}";
    runTest();
  }

  @Test
  public void aggregateList() throws Exception {
    source = "[] + [1,2,3,] + [4]";
    runTest();
  }

  @Test
  public void aggregateListDyn() throws Exception {
    source = "[1, 2u]";
    expectedType = ListType.create(SimpleType.DYN);
    runTest();
  }

  @Test
  public void aggregateMap() throws Exception {
    source = "{1:2u, 2:3u}";
    runTest();
  }

  @Test
  public void aggregateMapDyn() throws Exception {
    source = "{1:2u, 2u:3}";
    expectedType = MapType.create(SimpleType.DYN, SimpleType.DYN);
    runTest();
  }

  @Test
  public void aggregateMapDynValue() throws Exception {
    source = "{1:2u, 2:3}";
    expectedType = MapType.create(SimpleType.INT, SimpleType.DYN);
    runTest();
  }

  @Test
  public void aggregateMapDynKey() throws Exception {
    source = "{1:2, 2u:3}";
    expectedType = MapType.create(SimpleType.DYN, SimpleType.INT);
    runTest();
  }

  @Test
  public void aggregateMapFieldSelection() throws Exception {
    source = "{\"a\":1, \"b\":2}.a";
    runTest();
  }

  // Expected and Unexpected Types
  // =============================

  @Test
  public void expectedAggregateList() throws Exception {
    source = "[] + [1,2,3,] + [4]";
    expectedType = ListType.create(SimpleType.INT);
    runTest();
  }

  @Test
  public void unexpectedAggregateMapError() throws Exception {
    source = "{1:2u, 2:3u}";
    expectedType = MapType.create(SimpleType.INT, SimpleType.BOOL);
    runTest();
  }

  // Type Denotations
  // ================

  @Test
  public void types() throws Exception {
    source = "list == type([1]) && map == type({1:2u})";
    runTest();
    source = "{}.map(c,[c,type(c)])";
    runTest();
  }

  // Enum Values
  // ===========

  @Test
  public void enumValues() throws Exception {
    container = CelContainer.ofName("cel.expr.conformance.proto3");
    source = "TestAllTypes.NestedEnum.BAR != 99";
    runTest();
  }

  @Test
  public void nestedEnums() throws Exception {
    declareVariable("x", StructTypeReference.create(TestAllTypes.getDescriptor().getFullName()));
    container = CelContainer.ofName(TestAllTypes.getDescriptor().getFile().getPackage());
    source = "x.single_nested_enum == TestAllTypes.NestedEnum.BAR";
    runTest();

    declareVariable("single_nested_enum", SimpleType.INT);
    source = "single_nested_enum == TestAllTypes.NestedEnum.BAR";
    runTest();

    source =
        "TestAllTypes{single_nested_enum : TestAllTypes.NestedEnum.BAR}.single_nested_enum == 1";
    runTest();
  }

  @Test
  public void globalEnumValues() throws Exception {
    container = CelContainer.ofName("cel.expr.conformance.proto3");
    source = "GlobalEnum.GAZ == 2";
    runTest();
  }

  // Global Enum Values in separate file.
  // ===========

  @Test
  public void globalStandaloneEnumValues() throws Exception {
    container = CelContainer.ofName("dev.cel.testing.testdata.proto3");
    source = "StandaloneGlobalEnum.SGAZ == 2";

    FileDescriptorSet.Builder descriptorBuilder = FileDescriptorSet.newBuilder();
    descriptorBuilder.addFile(StandaloneGlobalEnum.getDescriptor().getFile().toProto());
    CelAbstractSyntaxTree ast = prepareTest(descriptorBuilder.build());
    if (ast != null) {
      testOutput()
          .println(
              CelDebug.toAdornedDebugString(
                  CelProtoAbstractSyntaxTree.fromCelAst(ast).getExpr(),
                  new CheckedExprAdorner(
                      CelProtoAbstractSyntaxTree.fromCelAst(ast).toCheckedExpr())));
    }
  }

  // Conversions
  // ===========

  @Test
  public void conversions() throws Exception {
    source = "int(1u) + int(uint(\"1\"))";
    runTest();
  }

  // Comprehensions
  // ==============

  @Test
  public void quantifiers() throws Exception {
    CelType messageType = StructTypeReference.create("cel.expr.conformance.proto3.TestAllTypes");
    declareVariable("x", messageType);
    source =
        "x.repeated_int64.all(e, e > 0) "
            + "&& x.repeated_int64.exists(e, e < 0) "
            + "&& x.repeated_int64.exists_one(e, e == 0)";
    runTest();
  }

  @Test
  public void twoVarComprehensions_allMacro() throws Exception {
    CelType messageType = StructTypeReference.create("cel.expr.conformance.proto3.TestAllTypes");
    declareVariable("x", messageType);
    source =
        "x.map_string_string.all(i, v, i < v) "
            + "&& x.repeated_int64.all(i, v, i < v) "
            + "&& [1, 2, 3, 4].all(i, v, i < 5 && v > 0) "
            + "&& {'a': 1, 'b': 2}.all(k, v, k.startsWith('a') && v == 1)";
    runTest();
  }

  @Test
  public void twoVarComprehensions_existsMacro() throws Exception {
    CelType messageType = StructTypeReference.create("cel.expr.conformance.proto3.TestAllTypes");
    declareVariable("x", messageType);
    source =
        "x.map_string_string.exists(i, v, i < v) "
            + "&& x.repeated_int64.exists(i, v, i < v) "
            + "&& [1, 2, 3, 4].exists(i, v, i < 5 && v > 0) "
            + "&& {'a': 1, 'b': 2}.exists(k, v, k.startsWith('a') && v == 1)";
    runTest();
  }

  @Test
  public void twoVarComprehensions_existsOneMacro() throws Exception {
    CelType messageType = StructTypeReference.create("cel.expr.conformance.proto3.TestAllTypes");
    declareVariable("x", messageType);
    source =
        "x.map_string_string.exists_one(i, v, i < v) "
            + "&& x.repeated_int64.exists_one(i, v, i < v) "
            + "&& [1, 2, 3, 4].exists_one(i, v, i < 5 && v > 0) "
            + "&& {'a': 1, 'b': 2}.exists_one(k, v, k.startsWith('a') && v == 1)";
    runTest();
  }

  @Test
  public void twoVarComprehensions_transformListMacro() throws Exception {
    CelType messageType = StructTypeReference.create("cel.expr.conformance.proto3.TestAllTypes");
    declareVariable("x", messageType);
    source =
        "[1, 2, 3].transformList(i, v, i > 0 && v < 3, (i * v) + v) == [4] "
            + "&& [1, 2, 3].transformList(i, v, i % 2 == 0, (i * v) + v) == [1,9] "
            + "&& [1, 2, 3].transformList(i, v, (i * v) + v) == [1,4,9]";
    runTest();
  }

  @Test
  public void twoVarComprehensions_incorrectIterVars() throws Exception {
    CelType messageType = StructTypeReference.create("cel.expr.conformance.proto3.TestAllTypes");
    declareVariable("x", messageType);
    source = "x.map_string_string.all(i + 1, v, i < v) && x.repeated_int64.all(i, v + 1, i < v)";
    runTest();
  }

  @Test
  public void twoVarComprehensions_duplicateIterVars() throws Exception {
    CelType messageType = StructTypeReference.create("cel.expr.conformance.proto3.TestAllTypes");
    declareVariable("x", messageType);
    source = "x.repeated_int64.exists(i, i, i < v)";
    runTest();
  }

  @Test
  public void twoVarComprehensions_incorrectNumberOfArgs() throws Exception {
    CelType messageType = StructTypeReference.create("cel.expr.conformance.proto3.TestAllTypes");
    declareVariable("x", messageType);
    source =
        "[1, 2, 3, 4].exists_one(i, v, i < v, v)"
            + "&& x.map_string_string.transformList(i, i < v) "
            + "&& [1, 2, 3].transformList(i, v, i > 0 && x < 3, (i * v) + v) == [4]";
    runTest();
  }

  @Test
  public void quantifiersErrors() throws Exception {
    CelType messageType = StructTypeReference.create("cel.expr.conformance.proto3.TestAllTypes");
    declareVariable("x", messageType);
    source = "x.all(e, 0)";
    runTest();
  }

  @Test
  public void mapExpr() throws Exception {
    CelType messageType = StructTypeReference.create("cel.expr.conformance.proto3.TestAllTypes");
    declareVariable("x", messageType);
    source = "x.repeated_int64.map(x, double(x))";
    runTest();

    source = "[].map(x, [].map(y, x in y && y in x))";
    runTest();

    source = "[{}.map(c,c,c)]+[{}.map(c,c,c)]";
    runTest();
  }

  @Test
  public void mapFilterExpr() throws Exception {
    CelType messageType = StructTypeReference.create("cel.expr.conformance.proto3.TestAllTypes");
    declareVariable("x", messageType);
    source = "x.repeated_int64.map(x, x > 0, double(x))";
    runTest();

    declareVariable("lists", SimpleType.DYN);
    source = "lists.filter(x, x > 1.5)";
    runTest();

    declareVariable("args", MapType.create(SimpleType.STRING, SimpleType.DYN));
    source = "args.user[\"myextension\"].customAttributes.filter(x, x.name == \"hobbies\")";
    runTest();
  }

  // Abstract Types
  // ==============

  @Test
  public void abstractTypeParameterLess() throws Exception {
    CelType abstractType = OpaqueType.create("abs");
    // Declare the identifier 'abs' to bind to the abstract type.
    declareVariable("abs", TypeType.create(abstractType));
    // Declare a function to create a new value of abstract type.
    declareFunction("make_abs", globalOverload("make_abs", ImmutableList.of(), abstractType));
    // Declare a function to consume value of abstract type.
    declareFunction(
        "as_bool", memberOverload("as_bool", ImmutableList.of(abstractType), SimpleType.BOOL));

    source = "type(make_abs()) == abs && make_abs().as_bool()";
    runTest();
  }

  @Test
  public void abstractTypeParameterized() throws Exception {
    CelType typeParam = TypeParamType.create("T");
    CelType abstractType = OpaqueType.create("vector", typeParam);
    TypeType typeOfTypeParam = TypeType.create(typeParam);
    TypeType typeOfAbstractType = TypeType.create(abstractType);

    declareFunction(
        "vector",
        // Declare the function 'vector' to create the abstract type.
        globalOverload("vector_type", ImmutableList.of(typeOfTypeParam), typeOfAbstractType),
        // Declare a function to create a new value of abstract type based on a list.
        globalOverload("vector_list", ImmutableList.of(ListType.create(typeParam)), abstractType));

    // Declare a function to consume value of abstract type.
    declareFunction(
        "at",
        memberOverload("vector_at_int", ImmutableList.of(abstractType, SimpleType.INT), typeParam));

    // The parameterization of 'vector(dyn)' is erased at runtime and so is checked as a 'vector',
    // but no further.
    source = "type(vector([1])) == vector(dyn) && vector([1]).at(0) == 1";
    runTest();
  }

  @Test
  public void abstractTypeParameterizedInListLiteral() throws Exception {
    CelType typeParam = TypeParamType.create("T");
    CelType abstractType = OpaqueType.create("vector", typeParam);
    TypeType typeOfAbstractType = TypeType.create(abstractType);
    TypeType typeOfTypeParam = TypeType.create(typeParam);

    declareFunction(
        "vector",
        // Declare the function 'vector' to create the abstract type.
        globalOverload("vector_type", ImmutableList.of(typeOfTypeParam), typeOfAbstractType),
        // Declare a function to create a new value of abstract type based on a list.
        globalOverload("vector_list", ImmutableList.of(ListType.create(typeParam)), abstractType));

    source = "size([vector([1, 2]), vector([2u, -1])]) == 2";
    runTest();
  }

  @Test
  public void abstractTypeParameterizedError() throws Exception {
    CelType typeParam = TypeParamType.create("T");
    CelType abstractType = OpaqueType.create("vector", typeParam);
    TypeType typeOfAbstractType = TypeType.create(abstractType);
    TypeType typeOfTypeParam = TypeType.create(typeParam);

    declareFunction(
        "vector",
        // Declare the function 'vector' to create the abstract type.
        globalOverload("vector_type", ImmutableList.of(typeOfTypeParam), typeOfAbstractType),
        // Declare a function to create a new value of abstract type based on a list.
        globalOverload("vector_list", ImmutableList.of(ListType.create(typeParam)), abstractType));
    declareFunction(
        "add",
        globalOverload(
            "add_vector_type",
            ImmutableList.of(typeOfAbstractType, typeOfAbstractType),
            typeOfAbstractType));
    source = "add(vector([1, 2]), vector([2u, -1])) == vector([1, 2, 2u, -1])";
    runTest();
  }

  // Optionals
  @Test
  public void optionals() throws Exception {
    declareVariable("a", MapType.create(SimpleType.STRING, SimpleType.STRING));
    source = "a.?b";
    runTest();

    clearAllDeclarations();
    declareVariable("x", OptionalType.create(MapType.create(SimpleType.STRING, SimpleType.STRING)));
    source = "x.y";
    runTest();

    source = "{?'nested': x.b}";
    runTest();

    clearAllDeclarations();
    declareVariable("d", OptionalType.create(SimpleType.DYN));
    source = "d.dynamic";
    runTest();

    source = "has(d.dynamic)";
    runTest();

    clearAllDeclarations();
    declareVariable("e", OptionalType.create(MapType.create(SimpleType.STRING, SimpleType.DYN)));
    source = "has(e.?b.c)";
    runTest();

    clearAllDeclarations();
    source = "{?'key': {'a': 'b'}.?value}";
    runTest();

    source = "{?'key': {'a': 'b'}.?value}.key";
    runTest();

    container = CelContainer.ofName("cel.expr.conformance.proto3");
    source = "TestAllTypes{?single_int32: {}.?i}";
    runTest();

    container = CelContainer.ofName("");
    declareVariable("a", OptionalType.create(SimpleType.STRING));
    declareVariable("b", OptionalType.create(SimpleType.STRING));
    source = "[?a, ?b, 'world']";
    runTest();

    source = "[?a, ?b, 2]";
    runTest();

    source = "{?'str':a, 2:3}";
    runTest();
  }

  @Test
  public void optionalErrors() throws Exception {
    source = "{?'key': 'hi'}";
    runTest();

    source = "[?'value']";
    runTest();

    container = CelContainer.ofName("cel.expr.conformance.proto3");
    source = "TestAllTypes{?single_int32: 1}";
    runTest();

    source = "a.?b";
    declareVariable("a", MapType.create(SimpleType.STRING, SimpleType.STRING));
    prepareCompiler(new ProtoMessageTypeProvider());
    CelAbstractSyntaxTree parsedAst = celCompiler.parse(source).getAst();
    CelMutableAst mutableAst = CelMutableAst.fromCelAst(parsedAst);
    mutableAst.expr().call().args().get(1).setConstant(CelConstant.ofValue(true));

    runErroneousTest(mutableAst.toParsedAst());
  }

  private static class CheckedExprAdorner implements CelAdorner {

    private final CheckedExpr checkedExpr;

    private CheckedExprAdorner(CheckedExpr checkedExpr) {
      this.checkedExpr = checkedExpr;
    }

    @Override
    public String adorn(ExprOrBuilder expr) {
      return adorn(expr.getId());
    }

    @Override
    public String adorn(EntryOrBuilder entry) {
      return adorn(entry.getId());
    }

    private String adorn(long exprId) {
      String adorned = "";
      if (checkedExpr.containsTypeMap(exprId)) {
        adorned = String.format("~%s", format(checkedExpr.getTypeMapOrThrow(exprId)));
      }
      if (checkedExpr.containsReferenceMap(exprId)) {
        adorned =
            String.format("%s^%s", adorned, print(checkedExpr.getReferenceMapOrThrow(exprId)));
      }
      return adorned;
    }

    private String print(Reference reference) {
      if (reference.getOverloadIdCount() > 0) {
        return Joiner.on("|").join(reference.getOverloadIdList());
      }
      return reference.getName();
    }
  }
}
