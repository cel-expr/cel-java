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

package dev.cel.common.ast;

import static com.google.common.truth.Truth.assertThat;

import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.CelContainer;
import dev.cel.common.CelFunctionDecl;
import dev.cel.common.CelOverloadDecl;
import dev.cel.common.types.SimpleType;
import dev.cel.common.types.StructTypeReference;
import dev.cel.compiler.CelCompiler;
import dev.cel.compiler.CelCompilerFactory;
import dev.cel.expr.conformance.proto3.TestAllTypes;
import dev.cel.extensions.CelOptionalLibrary;
import dev.cel.parser.CelStandardMacro;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(TestParameterInjector.class)
public class CelExprFormatterTest {

  private enum ConstantTestCase {
    BOOL("true", "CONSTANT [1] { value: true }"),
    INT("123", "CONSTANT [1] { value: 123 }"),
    UINT("123u", "CONSTANT [1] { value: 123u }"),
    DOUBLE("3.5", "CONSTANT [1] { value: 3.5 }"),
    BYTES("b'abc'", "CONSTANT [1] { value: b\"abc\" }"),
    STRING("'hello world'", "CONSTANT [1] { value: \"hello world\" }");

    private final String expression;
    private final String formatted;

    ConstantTestCase(String expression, String formatted) {
      this.expression = expression;
      this.formatted = formatted;
    }
  }

  @Test
  public void constant(@TestParameter ConstantTestCase constantTestCase) throws Exception {
    CelCompiler celCompiler = CelCompilerFactory.standardCelCompilerBuilder().build();
    CelAbstractSyntaxTree ast = celCompiler.compile(constantTestCase.expression).getAst();

    String formattedExpr = CelExprFormatter.format(ast.getExpr());

    assertThat(formattedExpr).isEqualTo(constantTestCase.formatted);
  }

  @Test
  public void notSet() {
    String formattedExpr = CelExprFormatter.format(CelExpr.ofNotSet(1));

    assertThat(formattedExpr).isEqualTo("NOT_SET [1] {}");
  }

  @Test
  public void select() throws Exception {
    CelCompiler celCompiler =
        CelCompilerFactory.standardCelCompilerBuilder()
            .addMessageTypes(TestAllTypes.getDescriptor())
            .addVar("msg", StructTypeReference.create(TestAllTypes.getDescriptor().getFullName()))
            .build();
    CelAbstractSyntaxTree ast = celCompiler.compile("msg.single_int32").getAst();

    String formattedExpr = CelExprFormatter.format(ast.getExpr());

    assertThat(formattedExpr)
        .isEqualTo("SELECT [2] {\n  IDENT [1] {\n    name: msg\n  }.single_int32\n}");
  }

  @Test
  public void call_global() throws Exception {
    CelCompiler celCompiler =
        CelCompilerFactory.standardCelCompilerBuilder()
            .addFunctionDeclarations(
                CelFunctionDecl.newFunctionDeclaration(
                    "test",
                    CelOverloadDecl.newGlobalOverload(
                        "test_overload", SimpleType.INT, SimpleType.INT, SimpleType.INT)))
            .build();
    CelAbstractSyntaxTree ast = celCompiler.compile("test(1, 5)").getAst();

    String formattedExpr = CelExprFormatter.format(ast.getExpr());

    assertThat(formattedExpr)
        .isEqualTo(
            "CALL [1] {\n"
                + "  function: test\n"
                + "  args: {\n"
                + "    CONSTANT [2] { value: 1 }\n"
                + "    CONSTANT [3] { value: 5 }\n"
                + "  }\n"
                + "}");
  }

  @Test
  public void call_member() throws Exception {
    CelCompiler celCompiler =
        CelCompilerFactory.standardCelCompilerBuilder()
            .addFunctionDeclarations(
                CelFunctionDecl.newFunctionDeclaration(
                    "test",
                    CelOverloadDecl.newMemberOverload(
                        "test_overload", SimpleType.INT, SimpleType.INT, SimpleType.INT)))
            .build();
    CelAbstractSyntaxTree ast = celCompiler.compile("5.test(2)").getAst();

    String formattedExpr = CelExprFormatter.format(ast.getExpr());

    assertThat(formattedExpr)
        .isEqualTo(
            "CALL [2] {\n"
                + "  function: test\n"
                + "  target: {\n"
                + "    CONSTANT [1] { value: 5 }\n"
                + "  }\n"
                + "  args: {\n"
                + "    CONSTANT [3] { value: 2 }\n"
                + "  }\n"
                + "}");
  }

  @Test
  public void list() throws Exception {
    CelCompiler celCompiler =
        CelCompilerFactory.standardCelCompilerBuilder()
            .addLibraries(CelOptionalLibrary.INSTANCE)
            .build();
    CelAbstractSyntaxTree ast =
        celCompiler.compile("[1, 2, ?optional.of(3), ?optional.of(4)]").getAst();

    String formattedExpr = CelExprFormatter.format(ast.getExpr());

    assertThat(formattedExpr)
        .isEqualTo(
            "LIST [1] {\n"
                + "  elements: {\n"
                + "    CONSTANT [2] { value: 1 }\n"
                + "    CONSTANT [3] { value: 2 }\n"
                + "    CALL [5] {\n"
                + "      function: optional.of\n"
                + "      args: {\n"
                + "        CONSTANT [6] { value: 3 }\n"
                + "      }\n"
                + "    }\n"
                + "    CALL [8] {\n"
                + "      function: optional.of\n"
                + "      args: {\n"
                + "        CONSTANT [9] { value: 4 }\n"
                + "      }\n"
                + "    }\n"
                + "  }\n"
                + "  optional_indices: [0, 1]\n"
                + "}");
  }

  @Test
  public void struct() throws Exception {
    CelCompiler celCompiler =
        CelCompilerFactory.standardCelCompilerBuilder()
            .setContainer(CelContainer.ofName("cel.expr.conformance.proto3"))
            .addMessageTypes(TestAllTypes.getDescriptor())
            .addLibraries(CelOptionalLibrary.INSTANCE)
            .build();
    CelAbstractSyntaxTree ast =
        celCompiler
            .compile(
                "TestAllTypes{single_int64: 1, single_string: 'test', ?single_double:"
                    + " optional.of(5.0)}")
            .getAst();

    String formattedExpr = CelExprFormatter.format(ast.getExpr());

    assertThat(formattedExpr)
        .isEqualTo(
            "STRUCT [1] {\n"
                + "  name: cel.expr.conformance.proto3.TestAllTypes\n"
                + "  entries: {\n"
                + "    ENTRY [2] {\n"
                + "      field_key: single_int64\n"
                + "      value: {\n"
                + "        CONSTANT [3] { value: 1 }\n"
                + "      }\n"
                + "    }\n"
                + "    ENTRY [4] {\n"
                + "      field_key: single_string\n"
                + "      value: {\n"
                + "        CONSTANT [5] { value: \"test\" }\n"
                + "      }\n"
                + "    }\n"
                + "    ENTRY [6] {\n"
                + "      field_key: single_double\n"
                + "      optional_entry: true\n"
                + "      value: {\n"
                + "        CALL [8] {\n"
                + "          function: optional.of\n"
                + "          args: {\n"
                + "            CONSTANT [9] { value: 5.0 }\n"
                + "          }\n"
                + "        }\n"
                + "      }\n"
                + "    }\n"
                + "  }\n"
                + "}");
  }

  @Test
  public void map() throws Exception {
    CelCompiler celCompiler =
        CelCompilerFactory.standardCelCompilerBuilder()
            .setContainer(CelContainer.ofName("cel.expr.conformance.proto3"))
            .addMessageTypes(TestAllTypes.getDescriptor())
            .addLibraries(CelOptionalLibrary.INSTANCE)
            .build();
    CelAbstractSyntaxTree ast =
        celCompiler.compile("{1: 'a', 'test': true, ?false: optional.of(3.0)}").getAst();

    String formattedExpr = CelExprFormatter.format(ast.getExpr());

    assertThat(formattedExpr)
        .isEqualTo(
            "MAP [1] {\n"
                + "  MAP_ENTRY [2] {\n"
                + "    key: {\n"
                + "      CONSTANT [3] { value: 1 }\n"
                + "    }\n"
                + "    value: {\n"
                + "      CONSTANT [4] { value: \"a\" }\n"
                + "    }\n"
                + "  }\n"
                + "  MAP_ENTRY [5] {\n"
                + "    key: {\n"
                + "      CONSTANT [6] { value: \"test\" }\n"
                + "    }\n"
                + "    value: {\n"
                + "      CONSTANT [7] { value: true }\n"
                + "    }\n"
                + "  }\n"
                + "  MAP_ENTRY [8] {\n"
                + "    key: {\n"
                + "      CONSTANT [9] { value: false }\n"
                + "    }\n"
                + "    optional_entry: true\n"
                + "    value: {\n"
                + "      CALL [11] {\n"
                + "        function: optional.of\n"
                + "        args: {\n"
                + "          CONSTANT [12] { value: 3.0 }\n"
                + "        }\n"
                + "      }\n"
                + "    }\n"
                + "  }\n"
                + "}");
  }

  @Test
  public void comprehension() throws Exception {
    CelCompiler celCompiler =
        CelCompilerFactory.standardCelCompilerBuilder()
            .setStandardMacros(CelStandardMacro.STANDARD_MACROS)
            .build();
    CelAbstractSyntaxTree ast = celCompiler.compile("[1, 2, 3].exists(x, x > 0)").getAst();

    String formattedExpr = CelExprFormatter.format(ast.getExpr());

    assertThat(formattedExpr)
        .isEqualTo(
            "COMPREHENSION [17] {\n"
                + "  iter_var: x\n"
                + "  iter_range: {\n"
                + "    LIST [1] {\n"
                + "      elements: {\n"
                + "        CONSTANT [2] { value: 1 }\n"
                + "        CONSTANT [3] { value: 2 }\n"
                + "        CONSTANT [4] { value: 3 }\n"
                + "      }\n"
                + "    }\n"
                + "  }\n"
                + "  accu_var: @result\n"
                + "  accu_init: {\n"
                + "    CONSTANT [10] { value: false }\n"
                + "  }\n"
                + "  loop_condition: {\n"
                + "    CALL [13] {\n"
                + "      function: @not_strictly_false\n"
                + "      args: {\n"
                + "        CALL [12] {\n"
                + "          function: !_\n"
                + "          args: {\n"
                + "            IDENT [11] {\n"
                + "              name: @result\n"
                + "            }\n"
                + "          }\n"
                + "        }\n"
                + "      }\n"
                + "    }\n"
                + "  }\n"
                + "  loop_step: {\n"
                + "    CALL [15] {\n"
                + "      function: _||_\n"
                + "      args: {\n"
                + "        IDENT [14] {\n"
                + "          name: @result\n"
                + "        }\n"
                + "        CALL [8] {\n"
                + "          function: _>_\n"
                + "          args: {\n"
                + "            IDENT [7] {\n"
                + "              name: x\n"
                + "            }\n"
                + "            CONSTANT [9] { value: 0 }\n"
                + "          }\n"
                + "        }\n"
                + "      }\n"
                + "    }\n"
                + "  }\n"
                + "  result: {\n"
                + "    IDENT [16] {\n"
                + "      name: @result\n"
                + "    }\n"
                + "  }\n"
                + "}");
  }

  @Test
  public void ternaryWithPresenceTest() throws Exception {
    CelCompiler celCompiler =
        CelCompilerFactory.standardCelCompilerBuilder()
            .addMessageTypes(TestAllTypes.getDescriptor())
            .addVar("msg", StructTypeReference.create(TestAllTypes.getDescriptor().getFullName()))
            .setStandardMacros(CelStandardMacro.STANDARD_MACROS)
            .build();
    CelAbstractSyntaxTree ast =
        celCompiler.compile("has(msg.single_any) ? msg.single_any : 10").getAst();

    String formattedExpr = CelExprFormatter.format(ast.getExpr());

    assertThat(formattedExpr)
        .isEqualTo(
            "CALL [5] {\n"
                + "  function: _?_:_\n"
                + "  args: {\n"
                + "    SELECT [4] {\n"
                + "      IDENT [2] {\n"
                + "        name: msg\n"
                + "      }.single_any~presence_test\n"
                + "    }\n"
                + "    SELECT [7] {\n"
                + "      IDENT [6] {\n"
                + "        name: msg\n"
                + "      }.single_any\n"
                + "    }\n"
                + "    CONSTANT [8] { value: 10 }\n"
                + "  }\n"
                + "}");
  }
}
