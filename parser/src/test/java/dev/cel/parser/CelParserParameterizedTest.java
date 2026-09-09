// Copyright 2022 Google LLC
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

package dev.cel.parser;


import dev.cel.expr.ParsedExpr;
import dev.cel.expr.SourceInfo;
import com.google.common.collect.ImmutableSet;
import com.google.protobuf.TextFormat;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.CelOptions;
import dev.cel.common.CelProtoAbstractSyntaxTree;
import dev.cel.common.CelSource;
import dev.cel.common.CelValidationException;
import dev.cel.common.CelValidationResult;
import dev.cel.common.ast.CelConstant;
import dev.cel.common.ast.CelExpr;
import dev.cel.extensions.CelOptionalLibrary;
import dev.cel.testing.BaselineTestCase;
import dev.cel.testing.CelDebug;
import dev.cel.testing.CelExprKindAndIdAdorner;
import dev.cel.testing.CelLocationAdorner;
import java.util.Optional;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Invokes parser tests and compares their output against baseline files. */
@RunWith(TestParameterInjector.class)
public final class CelParserParameterizedTest extends BaselineTestCase {
  private static final CelParser PARSER =
      CelParserFactory.standardCelParserBuilder()
          .setStandardMacros(
              ImmutableSet.<CelStandardMacro>builder()
                  .addAll(CelStandardMacro.STANDARD_MACROS)
                  .add(CelStandardMacro.EXISTS_ONE_NEW)
                  .build())
          .addLibraries(CelOptionalLibrary.INSTANCE)
          .addMacros(
              CelMacro.newGlobalVarArgMacro("noop_macro", (a, b, c) -> Optional.empty()),
              CelMacro.newGlobalMacro(
                  "get_constant_macro",
                  0,
                  (a, b, c) ->
                      Optional.of(
                          CelExpr.newBuilder()
                              .setId(1)
                              .setConstant(CelConstant.ofValue(10L))
                              .build())))
          .setOptions(
              CelOptions.current()
                  .populateMacroCalls(true)
                  .enableHiddenAccumulatorVar(true)
                  .build())
          .build();

  private static final CelParser PARSER_WITH_OLD_ACCU_VAR =
      PARSER
          .toParserBuilder()
          .setOptions(
              CelOptions.current()
                  .populateMacroCalls(true)
                  .enableHiddenAccumulatorVar(false)
                  .build())
          .build();

  @Test
  public void parser() {
    runTest(PARSER, "x * 2");
    runTest(PARSER, "x * 2u");
    runTest(PARSER, "x * 2.0");
    runTest(PARSER, "\"\\u2764\"");
    runTest(PARSER, "\"\u2764\"");
    runTest(PARSER, "! false");
    runTest(PARSER, "-a");
    runTest(PARSER, "a.b(5)");
    runTest(PARSER, "a[3]");
    runTest(PARSER, "SomeMessage{foo: 5, bar: \"xyz\"}");
    runTest(PARSER, "[3, 4, 5]");
    runTest(PARSER, "{foo: 5, bar: \"xyz\"}");
    runTest(PARSER, "a > 5 && a < 10");
    runTest(PARSER, "a < 5 || a > 10");
    runTest(PARSER, "\"abc\" + \"def\"");
    runTest(PARSER, "\"A\"");
    runTest(PARSER, "true");
    runTest(PARSER, "false");
    runTest(PARSER, "0");
    runTest(PARSER, "42");
    runTest(PARSER, "0u");
    runTest(PARSER, "23u");
    runTest(PARSER, "24u");
    runTest(PARSER, "0xAu");
    runTest(PARSER, "-0xA");
    runTest(PARSER, "0xA");
    runTest(PARSER, "-1");
    runTest(PARSER, "4--4");
    runTest(PARSER, "4--4.1");
    runTest(PARSER, "b\"abc\"");
    runTest(PARSER, "23.39");
    runTest(PARSER, "!a");
    runTest(PARSER, "null");
    runTest(PARSER, "a");
    runTest(PARSER, "a?b:c");
    runTest(PARSER, "a || b");
    runTest(PARSER, "a || b || c || d || e || f");
    runTest(PARSER, "a && b");
    runTest(PARSER, "a && b && c && d && e && f && g");
    runTest(PARSER, "a && b && c && d || e && f && g && h");
    runTest(PARSER, "a + b");
    runTest(PARSER, "a - b");
    runTest(PARSER, "a * b");
    runTest(PARSER, "a / b");
    runTest(PARSER, "a % b");
    runTest(PARSER, "a in b");
    runTest(PARSER, "a == b");
    runTest(PARSER, "a != b");
    runTest(PARSER, "a > b");
    runTest(PARSER, "a >= b");
    runTest(PARSER, "a < b");
    runTest(PARSER, "a <= b");
    runTest(PARSER, "a.b");
    runTest(PARSER, "a.b.c");
    runTest(PARSER, "a[b]");
    runTest(PARSER, "foo{ }");
    runTest(PARSER, "foo{ a:b }");
    runTest(PARSER, "foo{ a:b, c:d }");
    runTest(PARSER, "{}");
    runTest(PARSER, "{a:b, c:d}");
    runTest(PARSER, "[]");
    runTest(PARSER, "[a]");
    runTest(PARSER, "[a, b, c]");
    runTest(PARSER, "(a)");
    runTest(PARSER, "((a))");
    runTest(PARSER, "a()");
    runTest(PARSER, "a(b)");
    runTest(PARSER, "a(b, c)");
    runTest(PARSER, "a.b()");
    runTest(PARSER, "a.b(c)");
    runTest(PARSER, "aaa.bbb(ccc)");
    runTest(PARSER, "has(m.f)");
    runTest(PARSER, "m.exists_one(v, f)");
    runTest(PARSER, "m.existsOne(v, f)");
    runTest(PARSER, "m.map(v, f)");
    runTest(PARSER, "m.map(v, p, f)");
    runTest(PARSER, "m.filter(v, p)");
    runTest(PARSER, "[] + [1,2,3,] + [4]");
    runTest(PARSER, "{1:2u, 2:3u}");
    runTest(PARSER, "TestAllTypes{single_int32: 1, single_int64: 2}");
    runTest(PARSER, "size(x) == x.size()");
    runTest(PARSER, "\"\\\"\"");
    runTest(PARSER, "[1,3,4][0]");
    runTest(PARSER, "x[\"a\"].single_int32 == 23");
    runTest(PARSER, "x.single_nested_message != null");
    runTest(PARSER, "false && !true || false ? 2 : 3");
    runTest(PARSER, "b\"abc\" + B\"def\"");
    runTest(PARSER, "1 + 2 * 3 - 1 / 2 == 6 % 1");
    runTest(PARSER, "---a");
    runTest(PARSER, "\"\\xC3\\XBF\"");
    runTest(PARSER, "\"\\303\\277\"");
    runTest(PARSER, "\"hi\\u263A \\u263Athere\"");
    runTest(PARSER, "\"\\U000003A8\\?\"");
    runTest(PARSER, "\"\\a\\b\\f\\n\\r\\t\\v'\\\"\\\\\\? Legal escapes\"");
    runTest(PARSER, "'😁' in ['😁', '😑', '😦']");
    runTest(
        PARSER,
        // Note, the ANTLR parse stack may recurse much more deeply and permit
        // more detailed expressions than the visitor can recurse over in
        // practice.
        "[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[['just fine'],[1],[2],[3],[4],[5]]]]]]]"
            + "]]]]]]]]]]]]]]]]]]]]]]]]",
        false); // parse output not validated as it is too large.
    runTest(PARSER, "x.filter(y, y.filter(z, z > 0))");
    runTest(PARSER, "has(a.b).filter(c, c)");
    runTest(PARSER, "x.filter(y, y.exists(z, has(z.a)) && y.exists(z, has(z.b)))");
    runTest(PARSER, "noop_macro(123)");
    runTest(PARSER, "get_constant_macro()");
    runTest(PARSER, "a.?b[?0] && a[?c]");
    runTest(PARSER, "{?'key': value}");
    runTest(PARSER, "Msg{?field: value}");
    runTest(PARSER, "[?a, ?b]");
    runTest(PARSER, "[?a[?b]]");
    runTest(
        CelParserImpl.newBuilder()
            .setOptions(CelOptions.current().enableReservedIds(false).build())
            .build(),
        "while");
    CelParser parserWithQuotedFields =
        CelParserImpl.newBuilder()
            .setOptions(CelOptions.current().enableQuotedIdentifierSyntax(true).build())
            .build();
    runTest(parserWithQuotedFields, "foo.`bar`");
    runTest(parserWithQuotedFields, "foo.`bar-baz`");
    runTest(parserWithQuotedFields, "foo.`bar baz`");
    runTest(parserWithQuotedFields, "foo.`bar.baz`");
    runTest(parserWithQuotedFields, "foo.`bar/baz`");
    runTest(parserWithQuotedFields, "foo.`bar_baz`");
    runTest(parserWithQuotedFields, "foo.`in`");
    runTest(parserWithQuotedFields, "Struct{`in`: false}");
  }

  @Test
  public void parser_legacyAccuVar() {
    runTest(PARSER_WITH_OLD_ACCU_VAR, "x * 2");
    runTest(PARSER_WITH_OLD_ACCU_VAR, "has(m.f)");
    runTest(PARSER_WITH_OLD_ACCU_VAR, "m.exists_one(v, f)");
    runTest(PARSER_WITH_OLD_ACCU_VAR, "m.all(v, f)");
    runTest(PARSER_WITH_OLD_ACCU_VAR, "m.map(v, f)");
    runTest(PARSER_WITH_OLD_ACCU_VAR, "m.map(v, p, f)");
    runTest(PARSER_WITH_OLD_ACCU_VAR, "m.filter(v, p)");
  }

  @Test
  public void parser_errors() {
    runTest(PARSER, "*@a | b");
    runTest(PARSER, "a | b");
    runTest(PARSER, "?");
    runTest(PARSER, "1 + $");
    runTest(PARSER, "1.all(2, 3)");
    runTest(PARSER, "1.exists(2, 3)");
    runTest(PARSER, "[].all(__result__, x)");
    runTest(PARSER, "[].exists(__result__, x)");
    runTest(PARSER, "[].exists_one(__result__, x)");
    runTest(PARSER, "[].map(__result__, x, x)");
    runTest(PARSER, "[].filter(__result__, x)");
    runTest(PARSER, "[].all(.x, x)");
    runTest(PARSER, "[].exists(.x, x)");
    runTest(PARSER, "[].exists_one(.x, x)");
    runTest(PARSER, "[].map(.x, x, x)");
    runTest(PARSER, "[].filter(.x, x)");
    runTest(PARSER, "1 + +");
    runTest(PARSER, "\"\\xFh\"");
    runTest(PARSER, "\"\\a\\b\\f\\n\\r\\t\\v\\'\\\"\\\\\\? Illegal escape \\>\"");
    runTest(PARSER, "'\uD800'");
    runTest(PARSER, "'\uDFFF'");
    runTest(PARSER, "r\"\\\uD800\"");

    runTest(PARSER, "as");
    runTest(PARSER, "break");
    runTest(PARSER, "const");
    runTest(PARSER, "continue");
    runTest(PARSER, "else");
    runTest(PARSER, "for");
    runTest(PARSER, "function");
    runTest(PARSER, "if");
    runTest(PARSER, "import");
    runTest(PARSER, "in");
    runTest(PARSER, "let");
    runTest(PARSER, "loop");
    runTest(PARSER, "package");
    runTest(PARSER, "namespace");
    runTest(PARSER, "return");
    runTest(PARSER, "var");
    runTest(PARSER, "void");
    runTest(PARSER, "while");
    runTest(PARSER, "[1, 2, 3].map(var, var * var)");
    runTest(PARSER, "'😁' in ['😁', '😑', '😦']\n" + "   && in.😁");
    runTest(
        PARSER,
        "[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[["
            + "[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[["
            + "[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[["
            + "[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[['too many']]]]]]]]]]]]]]]]]]]]]]]]]]]]"
            + "]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]"
            + "]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]"
            + "]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]"
            + "]]]]]]");
    runTest(PARSER, "{\"a\": 1}.\"a\"");
    runTest(PARSER, "1 + 2\n3 +");
    runTest(PARSER, "TestAllTypes(){single_int32: 1, single_int64: 2}");
    runTest(PARSER, "{");
    runTest(PARSER, "t{>C}");
    runTest(PARSER, "has([(has((");

    CelParser parserWithoutOptionalSupport =
        CelParserImpl.newBuilder()
            .setOptions(CelOptions.current().enableOptionalSyntax(false).build())
            .build();
    runTest(parserWithoutOptionalSupport, "a.?b && a[?b]");
    runTest(parserWithoutOptionalSupport, "Msg{?field: value} && {?'key': value}");
    runTest(parserWithoutOptionalSupport, "[?a, ?b]");

    CelParser parserWithQuotedFields =
        CelParserImpl.newBuilder()
            .setOptions(CelOptions.current().enableQuotedIdentifierSyntax(true).build())
            .build();
    runTest(parserWithQuotedFields, "`bar`");
    runTest(parserWithQuotedFields, "foo.``");
    runTest(parserWithQuotedFields, "foo.`$bar`");

    CelParser parserWithoutQuotedFields =
        CelParserImpl.newBuilder()
            .setStandardMacros(CelStandardMacro.HAS)
            .setOptions(CelOptions.current().enableQuotedIdentifierSyntax(false).build())
            .build();
    runTest(parserWithoutQuotedFields, "foo.`bar`");
    runTest(parserWithoutQuotedFields, "Struct{`bar`: false}");
    runTest(parserWithoutQuotedFields, "has(.`.`");
  }

  @Test
  public void source_info() throws Exception {
    runSourceInfoTest("[{}, {'field': true}].exists(i, has(i.field))");
  }

  private void runTest(CelParser parser, String expression) {
    runTest(parser, expression, true);
  }

  private void runTest(CelParser parser, String expression, boolean validateParseOutput) {
    testOutput().println("I: " + expression);
    testOutput().println("=====>");

    CelSource source = CelSource.newBuilder(expression).setDescription("<input>").build();
    CelValidationResult parseResult = parser.parse(source);

    try {
      CelProtoAbstractSyntaxTree protoAst =
          CelProtoAbstractSyntaxTree.fromCelAst(parseResult.getAst());
      ParsedExpr parsedExpr = protoAst.toParsedExpr();
      if (validateParseOutput) {
        testOutput()
            .println(
                "P: "
                    + CelDebug.toAdornedDebugString(
                        parsedExpr.getExpr(), new CelExprKindAndIdAdorner()));
        String locationOutput =
            CelDebug.toAdornedDebugString(
                parsedExpr.getExpr(), new CelLocationAdorner(parsedExpr.getSourceInfo()));
        if (!locationOutput.isEmpty()) {
          testOutput().println("L: " + locationOutput);
        }
      }

      String macroOutput =
          CelExprKindAndIdAdorner.convertMacroCallsToString(parsedExpr.getSourceInfo());
      if (!macroOutput.isEmpty()) {
        testOutput().println("M: " + macroOutput);
      }
    } catch (CelValidationException e) {
      testOutput().println("E: " + e.getMessage());
    }

    testOutput().println();
  }

  private void runSourceInfoTest(String expression) throws Exception {
    CelAbstractSyntaxTree ast = PARSER.parse(expression).getAst();
    SourceInfo sourceInfo =
        CelProtoAbstractSyntaxTree.fromCelAst(ast).toParsedExpr().getSourceInfo();
    testOutput().println("I: " + expression);
    testOutput().println("=====>");
    testOutput().println("S: " + TextFormat.printer().printToString(sourceInfo));
  }
}
