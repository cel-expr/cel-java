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

import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.common.truth.Truth.assertThat;

import dev.cel.expr.ParsedExpr;
import dev.cel.expr.SourceInfo;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;
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
import dev.cel.testing.BaselineTestCase;
import dev.cel.testing.CelDebug;
import dev.cel.testing.CelExprKindAndIdAdorner;
import dev.cel.testing.CelLocationAdorner;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Invokes parser tests and compares their output against baseline files. */
@RunWith(TestParameterInjector.class)
public final class CelParserParameterizedTest extends BaselineTestCase {

  private static final CelOptions OPTIONS =
      CelOptions.current()
          .populateMacroCalls(true)
          .enableOptionalSyntax(true)
          .enableQuotedIdentifierSyntax(true)
          .enableHiddenAccumulatorVar(true)
          .build();

  private static final CelOptions OPTIONS_MAX_RECURSION_DEPTH_32 =
      OPTIONS.toBuilder().maxParseRecursionDepth(32).build();

  private static final CelOptions OPTIONS_NO_OPTIONAL_SYNTAX =
      OPTIONS.toBuilder().enableOptionalSyntax(false).build();

  private static final CelOptions OPTIONS_QUOTED_IDENTIFIER_SYNTAX =
      OPTIONS.toBuilder().enableQuotedIdentifierSyntax(true).build();

  private static final CelOptions OPTIONS_NO_QUOTED_IDENTIFIER_SYNTAX =
      OPTIONS.toBuilder().enableQuotedIdentifierSyntax(false).build();

  private static final CelOptions OPTIONS_MAX_CODE_POINT_SIZE_5 =
      OPTIONS.toBuilder().maxExpressionCodePointSize(5).build();

  private static final CelOptions OPTIONS_MAX_NODE_COUNT_2 =
      OPTIONS.toBuilder().maxParseExpressionNodeCount(2).build();

  private static final CelOptions OPTIONS_MAX_ERROR_RECOVERY_LIMIT_2 =
      OPTIONS.toBuilder().maxParseErrorRecoveryLimit(2).build();

  private static final CelOptions OPTIONS_OLD_ACCU_VAR =
      OPTIONS.toBuilder().enableHiddenAccumulatorVar(false).build();

  private static final ImmutableMap<String, CelMacro> MACROS =
      ImmutableMap.<String, CelMacro>builder()
          .putAll(
              CelStandardMacro.STANDARD_MACROS.stream()
                  .map(CelStandardMacro::getDefinition)
                  .collect(toImmutableMap(CelMacro::getKey, Function.identity())))
          .put(
              CelStandardMacro.EXISTS_ONE_NEW.getDefinition().getKey(),
              CelStandardMacro.EXISTS_ONE_NEW.getDefinition())
          .put(
              "noop_macro",
              CelMacro.newGlobalVarArgMacro("noop_macro", (a, b, c) -> Optional.empty()))
          .put(
              "get_constant_macro",
              CelMacro.newGlobalMacro(
                  "get_constant_macro",
                  0,
                  (a, b, c) ->
                      Optional.of(
                          CelExpr.newBuilder()
                              .setId(1)
                              .setConstant(CelConstant.ofValue(10L))
                              .build())))
          .buildOrThrow();

  private static final class ParseOutput {
    final String pOutput;
    final String lOutput;
    final String mOutput;
    final String errorMessage;

    ParseOutput(String pOutput, String lOutput, String mOutput, String errorMessage) {
      this.pOutput = pOutput;
      this.lOutput = lOutput;
      this.mOutput = mOutput;
      this.errorMessage = errorMessage;
    }

    boolean isError() {
      return errorMessage != null;
    }
  }

  private ParseOutput parse(
      CelOptions options,
      Map<String, CelMacro> macros,
      String expression,
      boolean validateParseOutput) {
    CelParser parser =
        CelParserImpl.newBuilder().setOptions(options).addMacros(macros.values()).build();
    CelSource source = CelSource.newBuilder(expression).setDescription("<input>").build();
    CelValidationResult parseResult = parser.parse(source);

    try {
      CelProtoAbstractSyntaxTree protoAst =
          CelProtoAbstractSyntaxTree.fromCelAst(parseResult.getAst());
      ParsedExpr parsedExpr = protoAst.toParsedExpr();
      String pOutput = null;
      String lOutput = null;
      if (validateParseOutput) {
        pOutput =
            CelDebug.toAdornedDebugString(parsedExpr.getExpr(), new CelExprKindAndIdAdorner());
        lOutput =
            CelDebug.toAdornedDebugString(
                parsedExpr.getExpr(), new CelLocationAdorner(parsedExpr.getSourceInfo()));
      }
      String mOutput =
          CelExprKindAndIdAdorner.convertMacroCallsToString(parsedExpr.getSourceInfo());
      return new ParseOutput(pOutput, lOutput, mOutput, null);
    } catch (CelValidationException e) {
      return new ParseOutput(null, null, null, e.getMessage());
    }
  }

  @Test
  public void parser_literals() {
    // Null
    runTest("null");

    // Boolean
    runTest("true");
    runTest("false");

    // Int
    runTest("0");
    runTest("42");
    runTest("0xF");
    runTest("0x2A");
    runTest("-1");
    runTest("-42");
    runTest("0xFFFFFFFFFFFFFFFFF");
    runTest("9223372036854775807"); // Long.MAX_VALUE
    runTest("-9223372036854775808"); // Long.MIN_VALUE
    runTest("-(9223372036854775808)"); // error
    runTest("123a");

    // Uint
    runTest("0u");
    runTest("23u");
    runTest("24u");
    runTest("0xAu");
    runTest("-0xA");
    runTest("0xA");
    runTest("0xFu");
    runTest("0xFFFFFFFFFFFFFFFFFu");
    runTest("123u_");

    // Double
    runTest("3.14");
    runTest("23.39");
    runTest("1.");
    runTest("1e+5");
    runTest("1e-5");
    runTest("2.5e+10");
    runTest("2.5e-10");
    runTest("1.99e90000009");
    runTest("1e");
    runTest("1e+");
    runTest("1e-");
    runTest("2.5e");
    runTest("2.5e+");
    runTest("2.5e-");
    runTest("((1e))");
    runTest("0x123z");

    // String
    runTest("'hello'");
    runTest("\"A\"");
    runTest("'''hello\nworld'''");
    runTest("\"\\u2764\"");
    runTest("\"\u2764\"");
    runTest("\"\\\"\"");
    runTest("\"\\xC3\\XBF\"");
    runTest("\"\\303\\277\"");
    runTest("\"hi\\u263A \\u263Athere\"");
    runTest("\"\\U000003A8\\?\"");
    runTest("\"\\a\\b\\f\\n\\r\\t\\v'\\\"\\\\\\? Legal escapes\"");
    runTest("\"\"\"hello\nworld\"\"\"");
    runTest("r\"\"\"hello\nworld\"\"\"");
    runTest("\"\"\"\"\"\"");
    runTest("''''''");
    runTest("\"\"\"hello\\\"\"\"world\"\"\"");
    runTest("'''hello\\'''world'''");
    runTest("\"\\xFh\"");
    runTest("\"\\a\\b\\f\\n\\r\\t\\v\\'\\\"\\\\\\? Illegal escape \\>\"");
    runTest(
        "      '\ud83d\ude01' in ['\ud83d\ude01', '\ud83d\ude11', '\ud83d\ude26']\n"
            + "\t\t\t&& in.\ud83d\ude01");
    runTest("\"\"\"hello\nworld");
    runTest("'''hello\nworld");
    runTest("r\"\"\"hello\nworld");
    runTest("\"hello\nworld\"");
    runTest("'hello\nworld'");
    runTest("r\"hello\nworld\"");
    runTest("`hello\nworld`");
    runTest("\"hello\rworld\"");
    runTest("'unterminated");

    // Bytes
    runTest("b'abc'");
    runTest("b\"abc\"");
    runTest("b\"\"\"hello\nworld");
    runTest("b\"hello\nworld\"");
    runTest("rb\"hello\nworld\"");
    runTest("br'abc'");
    runTest("bR'abc'");
    runTest("Br'abc'");
    runTest("BR'abc'");
    runAntlrTest(OPTIONS, "rb'abc'");
    runAntlrTest(OPTIONS, "rB'abc'");
    runAntlrTest(OPTIONS, "Rb'abc'");
    runAntlrTest(OPTIONS, "RB'abc'");
    runTest("br'a\\'b'");
    runAntlrTest(OPTIONS, "rb'a\\'b'");
  }

  @Test
  @SuppressWarnings("InlineMeInliner") // String.repeat is unavailable under Java 8
  public void parser_core_syntax() {
    // Identifiers
    runTest("a");
    runTest("foo");

    // Parentheses
    runTest("(a)");
    runTest("((a))");
    runTest("(((1 + 2))) * 3");

    // Lists
    runTest("[]");
    runTest("[a]");
    runTest("[a, b, c]");
    runTest("[1, 2, 3]");
    runTest("[3, 4, 5]");
    runTest("[3, 4, 5,]");
    runTest("[?a, b]");
    runTest("[?a, ?b]");
    runTest("[?a[?b]]");

    // Maps
    runTest("{}");
    runTest("{a:b, c:d}");
    runTest("{foo: 5, bar: \"xyz\"}");
    runTest("{foo: 5, bar: \"xyz\", }");
    runTest("{\"a\": 1, \"b\": 2}");
    runTest("{1:2u, 2:3u}");
    runTest("{?a: b}");
    runTest("{?'key': value}");

    // Messages
    runTest("foo{ }");
    runTest("foo{ a:b }");
    runTest("foo{ a:b, c:d }");
    runTest("SomeMessage{foo: 5, bar: \"xyz\"}");
    runTest("TestAllTypes{single_int32: 1, single_int64: 2}");
    runTest("MyType{foo: 1, bar: 'baz'}");
    runTest("Message{`in`: true}");
    runTest("Msg{?field: value}");
    runTest("foo.bar.MyType{ }");
    runTest("foo.bar.MyType{ a:b }");
    runTest(".foo.bar.MyType{ a:b }");
    runTest("a.b.c.d.Message{ foo: 1, bar: 'baz' }");

    // Field selection
    runTest("a.b");
    runTest("a.b.c");
    runTest("a.?b");
    runTest("a.`b-c`");
    runTest("a.`b c`");
    runTest("a.`b.c`");
    runTest("a.`in`");
    runTest("a.`/foo`");
    runTest("a.`my-var`");

    // Indexing
    runTest("a[b]");
    runTest("a[0]");
    runTest("a[3]");
    runTest("[1,3,4][0]");
    runTest("a[?0]");

    // Function calls
    runTest("a()");
    runTest("a(b)");
    runTest("a(b, c)");
    runTest("a.b()");
    runTest("a.b(c)");
    runTest("a.b(5)");
    runTest("aaa.bbb(ccc)");
    runTest("a.foo(1, 2)");

    // Unary operators
    runTest("!a");
    runTest("!x");
    runTest("! false");
    runTest("-a");
    runTest("---a");

    // Arithmetic operators
    runTest("x * 2");
    runTest("x * 2u");
    runTest("x * 2.0");
    runTest("a * b");
    runTest("a / b");
    runTest("a % b");
    runTest("a + b");
    runTest("a - b");
    runTest("4--4");
    runTest("4--4.1");
    runTest("\"abc\" + \"def\"");
    runTest("b\"abc\" + B\"def\"");
    runTest("[] + [1,2,3,] + [4]");
    runTest("1 + 2 * 3");

    // Comparison operators
    runTest("a == b");
    runTest("a != b");
    runTest("a < b");
    runTest("a <= b");
    runTest("a > b");
    runTest("a >= b");
    runTest("a in b");
    runTest("\"\ud83d\ude01\" in [\"\ud83d\ude01\", \"\ud83d\ude11\", \"\ud83d\ude26\"]");
    runTest("size(x) == x.size()");
    runTest("x.single_nested_message != null");

    // Logical operators
    runTest("a && b");
    runTest("a && b && c");
    runTest("a && b && c && d && e && f && g");
    runTest("a > 5 && a < 10");
    runTest("a || b");
    runTest("a || b || c || d || e || f");
    runTest("a < 5 || a > 10");
    runTest("a && b && c && d || e && f && g && h");

    // Conditional operator
    runTest("a?b:c");
    runTest("cond ? 1 : 2");
    runTest("false && !true || false ? 2 : 3");
    runTest(OPTIONS_MAX_RECURSION_DEPTH_32, Strings.repeat("true ? 1 : ", 31) + "1", false);
    runAntlrTest(OPTIONS_MAX_RECURSION_DEPTH_32, Strings.repeat("!-", 15) + "x");

    // Complex expressions
    runTest("1 + 2 * 3 - 1 / 2 == 6 % 1");
    runTest("x[\"a\"].single_int32 == 23");
    runTest("a.?b[?0] && a[?c]");
    runTest(
        OPTIONS,
        "[[[[[[[[[[[[[[[[[[[[[[[[[[[[[['just"
            + " fine'],[1],[2],[3],[4],[5]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]",
        false);

    // Whitespace and comments
    runTest("// comment\na");
    runTest("a // comment");
    runTest("a\n// comment\n+ b");
    runTest("a / // comment\n  b");
    runTest("[\n  1, // comment\n  2,\n]");

    // Reserved IDs disabled
    runTest(OPTIONS.toBuilder().enableReservedIds(false).build(), "while");

    // Quoted field specifiers
    runTest(OPTIONS_QUOTED_IDENTIFIER_SYNTAX, "foo.`bar`");
    runTest(OPTIONS_QUOTED_IDENTIFIER_SYNTAX, "foo.`bar-baz`");
    runTest(OPTIONS_QUOTED_IDENTIFIER_SYNTAX, "foo.`bar baz`");
    runTest(OPTIONS_QUOTED_IDENTIFIER_SYNTAX, "foo.`bar.baz`");
    runTest(OPTIONS_QUOTED_IDENTIFIER_SYNTAX, "foo.`bar/baz`");
    runTest(OPTIONS_QUOTED_IDENTIFIER_SYNTAX, "foo.`bar_baz`");
    runTest(OPTIONS_QUOTED_IDENTIFIER_SYNTAX, "foo.`in`");
    runTest(OPTIONS_QUOTED_IDENTIFIER_SYNTAX, "Struct{`in`: false}");
  }

  @Test
  public void parser_macros() {
    runTest("has(m.f)");
    runTest("has(a.b)");
    runTest("has(m)");

    runTest("m.all(v, f)");
    runTest("[1, 2].all(x, x > 0)");

    runTest("m.exists(v, f)");

    runTest("m.exists_one(v, f)");
    runTest("m.existsOne(v, f)");
    runTest("[].existsOne(__result__, __result__)");

    runTest("m.map(v, f)");
    runTest("m.map(v, p, f)");
    runTest("m.map(__result__, __result__)");

    runTest("m.filter(v, p)");
    runTest("m.filter(__result__, false)");
    runTest("m.filter(a.b, false)");

    // Nested / Chained macros
    runTest("x.filter(y, y.filter(z, z > 0))");
    runTest("has(a.b).filter(c, c)");
    runTest("x.filter(y, y.exists(z, has(z.a)) && y.exists(z, has(z.b)))");
    runTest("(has(a.b) || has(c.d)).string()");
    runTest("has(a.b).asList().exists(c, c)");
    runTest("[has(a.b), has(c.d)].exists(e, e)");

    // Custom macros
    runTest("noop_macro(123)");
    runTest("get_constant_macro()");
  }

  @Test
  @SuppressWarnings("InlineMeInliner") // String.repeat is unavailable under Java 8
  public void parser_errors() {
    // Lexical errors
    runTest("*@a | b");
    runTest("((@))");
    runTest("1 + $");
    runTest(
        "\u00f3\u00a0\u00a2\n"
            + "\t\t\u00f3\u00a00\u00a0\n"
            + "\t\t\u007f0\"\"\"\\\"\"\\\"\"\"\\\"\"\\\"\"\"\\\"\"\\\"\"\"\\\"\"\\\"\"\"\\\"\\\"\"\"\\\"\"\\\"\"\"\\\"\"\\\"\"\"\\\"\"\\\"\"\"\\\"!\\\"\"\"\\\"\"\\\"\"\"\\\"\"\\\"");
    runTest("'\\udead' == '\\ufffd'");
    runTest("a | b");
    runTest("'3# < 10\" '& tru ^^");
    runTest("'\uD800'");
    runTest("'\uDFFF'");
    runTest("r\"\\\uD800\"");

    // Unexpected tokens
    runTest("1 + +");
    runTest("?");
    runTest("a ? b ((?))");
    runTest("a ? b @");
    runTest(
        "-[-1--1--1--1---1--1--1--0--1--1--1--1--0--2--1--1--0--1--1--1--1--0--1--1--1\n"
            + "\t\t--3-[-1--1--1--1---1--1--1--0--1--1--1--1--0--3--1--1--0--1--1--1--1--0--1--1--1\n"
            + "\t\t--3-[-1--1--1--1---1--1--1--0-/1--1--1--1--0--2--1--1--0--1--1--1--1--0--1--1--1\n"
            + "\t\t--3-[-1--1--1--1---1--1--1--0--1--1--1--1--0--2--1--1--0--1--1--1--1--0--1--1--1\n"
            + "\t\t--3-[-1--1--1--1---1--1--1--0--1--1--1--1--0--2--1--1--0--1--1--1--1--0--1--1--1\n"
            + "\t\t--3-[-1--1--1--1---1--1--1--0--1--1--1--1--0--2--1--1--0--1--1--1--1--0--1--1--1\n"
            + "\t\t--3-[-1--1--1--1---1--1--1--0--1--1--1--1--0--3--1--1--0--1--1--1--1--0--1--1--1\n"
            + "\t\t--3-[-1--1--1--1---1--1--1--0-/1--1--1--1--0--2--1--1--0--1--1--1--1--0--1--1--1\n"
            + "\t\t--3-[-1--1--1--1---1-1--1--1--1--0--2--1--1--0--1--1--1--1--0--1--1--1--3-[-1--1\n"
            + "\t\t--1--1---1--1--1--0--1--1--1--1--0--2--1--1--0--1--1--1--1--0--1--1--1--3-[-1--1\n"
            + "\t\t--1--1---1--1-\u00c01--0--1--1--1--1--0--2--1--1--0--1--1--1--1--0--1--1--1--3-[-1--1\n"
            + "\t\t--1--1---1--1--1--0--1--1--1--1--0--2--1--1--0--1--1--1--1--0--1--1--1--3-[-1--1\n"
            + "\t\t--1--1---1--1--1--0--1--1--1--1--0--3--1--1--0--1--1--1--1--0--1--1--1--3-[-1--1\n"
            + "\t\t--1--1---1--1--1--0-/1--1--1--1--0--2--1--1--0--1--1--1--1--0--1--1--1--3-[-1--1\n"
            + "\t\t--1--1---1--1--1--0--1--1--1--1--0--2--1--1--0--1--1--1--1--0--1--1--1--3-[-1--1\n"
            + "\t\t--1--1---1--1--1--0--1--1--1--1--0--2--1--1--0--1--1--1--1--0--1--1--1--3-[-1--1\n"
            + "\t\t--1--1---1--1--1--0--1--1--1--1--0--2--1--1--0--1--1--1--1--0--1--1--1--3-[-1--1\n"
            + "\t\t--1--1---1--1--1--0--1--1--1--1--0--3--1--1--0--1\n"
            + "\t\t--1--0--1--1--1--3-[-1--1--1--1---1--1--1--0-/1--1--1--1--0--2--1--1--0--1--1--1\n"
            + "\t\t--1--0--1--1--1--3-[-1--1--1--1---1--1--1--0--1--1--1--1--0--2--1--1--0--1--1--1\n"
            + "\t\t--1--0--1--1--1--3-[-1--1--1--1---1--1--1--0--1--1--1--1--0--2--1--1--0--1--1--1\n"
            + "\t\t--1--0--1--1--1--3-[-1--1--1--1---1--1--1--0--1--1--1--1--0--2--1--1--0--1--1--1\n"
            + "\t\t--1--0--1--1--1--3-[-1--1--1--1---1--1--1--0--1--1--1--1--0--3--1--1--0--1--1--1\n"
            + "\t\t--1--0--1--1--1--3-[-1--1--1--1---1--1--1--0-/1--1--1--1--0--2--1--1--0--1--1--1\n"
            + "\t\t--1--0--1--1--1--3-[-1--1--1--1---1--1--1--0--1--1--1--1--0--1--1--1--3-[-1--1--1\n"
            + "\t\t--1---1--1--1--0--1--1--1--1--0--2--1--1--0--1--1--1--1--0--1--1--1--3-[-1--1--1\n"
            + "\t\t--1---1--1--1--0--1--1--1--1--0--2--1--1--0--1--1--1--1--0--1--1--1--3-[-1--1--1\n"
            + "\t\t--1---1--1--1--0--1--1--1--1--0--2--1--1--0--1--1--1--1--0--1--1--1--3-[-1--1--1\n"
            + "\t\t--1---1--1--1--0--1--1--1--1--0--3--1--1--0--1--1--1--1--0--1--1--1--3-[-1--1--1\n"
            + "\t\t--1---1--1--1--0-/1--1--1--1--0--2--1--1--0--1--1--1--1--0--1--1--1--3-[-1--1--1\n"
            + "\t\t--1---1--1--1--0--1--1--1--1--0--2--1--1--0--1--1--1--1--0--1--1--1--3-[-1--1--1\n"
            + "\t\t--1---1--1--1--0--1--1--1--1--0--2--1--1--0--1--1--1--1--0--1--1--1--3-[-1--1--1\n"
            + "\t\t--1---1--1--1--0--1--1--1--1--0--2--1--1--0--1--1--1--1--0--1--1--1--3-[-1--1--1\n"
            + "\t\t--1---1--1--1--0--1--1--1--1--0--3--1--1--0--1");

    // Reserved identifiers
    runTest(
        "as break const continue else for function if import in let loop package namespace"
            + " return var void while");
    runTest("as");
    runTest("break");
    runTest("const");
    runTest("continue");
    runTest("else");
    runTest("for");
    runTest("function");
    runTest("if");
    runTest("import");
    runTest("in");
    runTest("let");
    runTest("loop");
    runTest("package");
    runTest("namespace");
    runTest("return");
    runTest("var");
    runTest("void");
    runTest("while");
    runTest("[1, 2, 3].map(var, var * var)");
    runTest("'😁' in ['😁', '😑', '😦']\n   && in.😁");

    // Incomplete expressions
    runTest("1 +");
    runTest("--");
    runTest("{");
    runTest("0x");

    // Unexpected token after expression
    runTest("TestAllTypes(){}");
    runTest("TestAllTypes{}()");
    runTest("TestAllTypes(){single_int32: 1, single_int64: 2}");
    runTest("1 + 2\n3 +");

    // Member selection errors
    runTest("{\"a\": 1}.\"a\"");
    runTest("self.true == 1");

    // Map syntax errors
    runTest("{a}");
    runTest("{:a}");

    // Message syntax errors
    runTest("func{{a}}");
    runTest("msg{:a}");
    runTest("ind[a{b}]");
    runTest("x{?.");
    runTest("x{.");
    runTest("t{>C}");
    runTest("has([(has((");

    // Macro errors
    runTest("1.all(2, 3)");
    runTest("1.exists(2, 3)");
    runTest("[].all(__result__, x)");
    runTest("[].exists(__result__, x)");
    runTest("[].exists_one(__result__, x)");
    runTest("[].map(__result__, x, x)");
    runTest("[].filter(__result__, x)");
    runTest("[].all(.x, x)");
    runTest("[].exists(.x, x)");
    runTest("[].exists_one(.x, x)");
    runTest("[].map(.x, x, x)");
    runTest("[].filter(.x, x)");

    // Unsupported optional syntax
    runTest(OPTIONS_NO_OPTIONAL_SYNTAX, "a.?b && a[?b]");
    runTest(OPTIONS_NO_OPTIONAL_SYNTAX, "[?a, ?b]");
    runTest(OPTIONS_NO_OPTIONAL_SYNTAX, "Msg{?field: value} && {?'key': value}");

    // Unsupported quoted identifier syntax
    runTest(OPTIONS_NO_QUOTED_IDENTIFIER_SYNTAX, "a.`b-c`");
    runTest(OPTIONS_NO_QUOTED_IDENTIFIER_SYNTAX, "a.`b.c`");
    runTest(OPTIONS_NO_QUOTED_IDENTIFIER_SYNTAX, "a.`in`");
    runTest(OPTIONS_NO_QUOTED_IDENTIFIER_SYNTAX, "a.`/foo`");
    runTest(OPTIONS_NO_QUOTED_IDENTIFIER_SYNTAX, "Message{`in`: true}");
    runTest(OPTIONS_NO_QUOTED_IDENTIFIER_SYNTAX, "foo.`bar`");
    runTest(OPTIONS_NO_QUOTED_IDENTIFIER_SYNTAX, "Struct{`bar`: false}");
    runTest(OPTIONS_NO_QUOTED_IDENTIFIER_SYNTAX, "has(.`.`");

    // Unsupported quoted identifier location
    runTest(OPTIONS_QUOTED_IDENTIFIER_SYNTAX, "`b-c`");
    runTest(OPTIONS_QUOTED_IDENTIFIER_SYNTAX, "`b-c`()");
    runTest(OPTIONS_QUOTED_IDENTIFIER_SYNTAX, "a.`$b`");
    runTest(OPTIONS_QUOTED_IDENTIFIER_SYNTAX, "a.`b.c`()");
    runTest(OPTIONS_QUOTED_IDENTIFIER_SYNTAX, "`bar`");
    runTest(OPTIONS_QUOTED_IDENTIFIER_SYNTAX, "foo.``");
    runTest(OPTIONS_QUOTED_IDENTIFIER_SYNTAX, "foo.`$bar`");

    // Recursion limit exceeded
    runTest(
        OPTIONS,
        "[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[["
            + "[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[["
            + "[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[["
            + "[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[['too many']]]]]]]]]]]]]]]]]]]]]]]]]]]]"
            + "]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]"
            + "]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]"
            + "]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]"
            + "]]]]]]");
    runTest(
        OPTIONS_MAX_RECURSION_DEPTH_32,
        "[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[\n"
            + "\t\t\t[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[['too many']]]]]]]]]]]]]]]]]]]]]]]]]]]]\n"
            + "\t\t\t]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]");
    runTest(
        OPTIONS_MAX_RECURSION_DEPTH_32,
        "[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[['not fine']]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]");
    runTest(
        OPTIONS_MAX_RECURSION_DEPTH_32,
        "a.b.c.d.e.f.g.h.i.j.k.l.m.n.o.p.q.r.s.t.u.v.w.x.y.z.A.B.C.D.E.F.G.H");
    runTest(
        OPTIONS_MAX_RECURSION_DEPTH_32,
        "a[1][2][3][4][5][6][7][8][9][10][11][12][13][14][15][16][17][18][19][20]\n"
            + "\t\t     [21][22][23][24][25][26][27][28][29][30][31][32][33]");
    runTest(
        OPTIONS_MAX_RECURSION_DEPTH_32,
        "1 + 2 + 3 + 4 + 5 + 6 + 7 + 8 + 9 + 10\n"
            + "\t\t+ 11 + 12 + 13 + 14 + 15 + 16 + 17 + 18 + 19 + 20\n"
            + "\t\t+ 21 + 22 + 23 + 24 + 25 + 26 + 27 + 28 + 29 + 30\n"
            + "\t\t+ 31 + 32 + 33 + 34");
    runTest(
        OPTIONS_MAX_RECURSION_DEPTH_32,
        "a < 1 < 2 < 3 < 4 < 5 < 6 < 7 < 8 < 9 < 10 < 11\n"
            + "\t\t      < 12 < 13 < 14 < 15 < 16 < 17 < 18 < 19 < 20 < 21\n"
            + "\t\t\t  < 22 < 23 < 24 < 25 < 26 < 27 < 28 < 29 < 30 < 31\n"
            + "\t\t\t  < 32 < 33");
    runTest(
        OPTIONS_MAX_RECURSION_DEPTH_32,
        "y!=y!=y!=y!=y!=y!=y!=y!=y!=-y!=-y!=-y!=-y-y!=-y!=-y!=-y-y!=-y!=-y!=-y-y!=-y\n"
            + "\t\t!=-y!=-y-y!=-y!=-y!=-y-y!=-y!=-y!=-y-y!=-y!=-y!=-y-y!=-y!=-y!=-y-y!=-y!=-y!=-y-y\n"
            + "\t\t!=-y!=-y!=-y-y!=-y!=-y!=-y-y!=-y!=-y!=-y-y!=-y!=-y!=-y-y!=-y!=-y!=-y-y!=-y!=-y\n"
            + "\t\t!=-y-y!=-y!=-y!=-y-y!=-y!=-y!=-y-y!=-y!=-y!=-y-y!=-y!=-y!=-y-y!=-y!=-y!=-y-y!=-y\n"
            + "\t\t!=-y!=-y-y!=-y!=-y!=-y-y!=-y!=-y!=-y-y!=-y!=-y!=-y-y!=-y!=-y!=-y-y!=-y!=-y!=-y-y\n"
            + "\t\t!=-y!=-y!=-y-y!=-y!=-y!=-y-y!=-y!=-y!=-y-y!=-y");
    runTest(
        OPTIONS_MAX_RECURSION_DEPTH_32,
        "a[1][2][3][4][5][6][7][8][9][10][11][12][13][14][15][16][17][18][19][20] !=\n"
            + "\t\ta[1][2][3][4][5][6][7][8][9][10][11][12][13][14][15][16][17][18][19][20]"
            + " !=\n"
            + "\t\ta[1][2][3][4][5][6][7][8][9][10][11][12][13][14][15][16][17][18][19][20]"
            + " !=\n"
            + "\t\ta[1][2][3][4][5][6][7][8][9][10][11][12][13][14][15][16][17][18][19][20]"
            + " !=\n"
            + "\t\ta[1][2][3][4][5][6][7][8][9][10][11][12][13][14][15][16][17][18][19][20]"
            + " !=\n"
            + "\t\ta[1][2][3][4][5][6][7][8][9][10][11][12][13][14][15][16][17][18][19][20]"
            + " !=\n"
            + "\t\ta[1][2][3][4][5][6][7][8][9][10][11][12][13][14][15][16][17][18][19][20]"
            + " !=\n"
            + "\t\ta[1][2][3][4][5][6][7][8][9][10][11][12][13][14][15][16][17][18][19][20]"
            + " !=\n"
            + "\t\ta[1][2][3][4][5][6][7][8][9][10][11][12][13][14][15][16][17][18][19][20]"
            + " !=\n"
            + "\t\ta[1][2][3][4][5][6][7][8][9][10][11][12][13][14][15][16][17][18][19][20]"
            + " !=\n"
            + "\t\ta[1][2][3][4][5][6][7][8][9][10][11][12][13][14][15][16][17][18][19][20]"
            + " !=\n"
            + "\t\ta[1][2][3][4][5][6][7][8][9][10][11][12][13][14][15][16][17][18][19][20]"
            + " !=\n"
            + "\t\ta[1][2][3][4][5][6][7][8][9][10][11][12][13][14][15][16][17][18][19][20]"
            + " !=\n"
            + "\t\ta[1][2][3][4][5][6][7][8][9][10][11][12][13][14][15][16][17][18][19][20]");
    runTest(OPTIONS_MAX_RECURSION_DEPTH_32, Strings.repeat("true ? 1 : ", 33) + "1");
    runTest(OPTIONS_MAX_RECURSION_DEPTH_32, Strings.repeat("!-", 16) + "!x");
    runTest(OPTIONS_MAX_CODE_POINT_SIZE_5, "123456");
    runTest(OPTIONS_MAX_NODE_COUNT_2, "1 + 2 + 3");
    runTest(OPTIONS_MAX_ERROR_RECOVERY_LIMIT_2, "[?, ?, ?]");
    runTest(OPTIONS_MAX_ERROR_RECOVERY_LIMIT_2, "[1 2 3 a b c]");
  }

  @Test
  public void parser_legacyAccuVar() {
    runAntlrTest(OPTIONS_OLD_ACCU_VAR, "x * 2");
    runAntlrTest(OPTIONS_OLD_ACCU_VAR, "has(m.f)");
    runAntlrTest(OPTIONS_OLD_ACCU_VAR, "m.exists_one(v, f)");
    runAntlrTest(OPTIONS_OLD_ACCU_VAR, "m.all(v, f)");
    runAntlrTest(OPTIONS_OLD_ACCU_VAR, "m.map(v, f)");
    runAntlrTest(OPTIONS_OLD_ACCU_VAR, "m.map(v, p, f)");
    runAntlrTest(OPTIONS_OLD_ACCU_VAR, "m.filter(v, p)");
  }

  private void runAntlrTest(CelOptions options, String expression) {
    testOutput().println("I: " + sanitizeForBaseline(expression));
    testOutput().println("=====>");

    CelOptions antlrOptions = options.toBuilder().enablePrattParser(false).build();
    ParseOutput antlrResult =
        parse(antlrOptions, MACROS, expression, /* validateParseOutput= */ true);
    if (!antlrResult.isError()) {
      testOutput().println("P: " + antlrResult.pOutput);
      if (!Strings.isNullOrEmpty(antlrResult.lOutput)) {
        testOutput().println("L: " + antlrResult.lOutput);
      }
      if (!Strings.isNullOrEmpty(antlrResult.mOutput)) {
        testOutput().println("M: " + antlrResult.mOutput);
      }
    } else {
      testOutput().println("E/A: " + sanitizeForBaseline(antlrResult.errorMessage));
    }

    testOutput().println();
  }

  @Test
  public void source_info() throws Exception {
    runSourceInfoTest("[{}, {'field': true}].exists(i, has(i.field))");
  }

  private void runTest(String expression) {
    runTest(OPTIONS, expression);
  }

  private void runTest(CelOptions options, String expression) {
    runTest(options, expression, true);
  }

  private void runTest(CelOptions options, String expression, boolean validateParseOutput) {
    runTest(options, MACROS, expression, validateParseOutput);
  }

  private void runTest(
      CelOptions options,
      Map<String, CelMacro> macros,
      String expression,
      boolean validateParseOutput) {
    testOutput().println("I: " + sanitizeForBaseline(expression));
    testOutput().println("=====>");

    ParseOutput antlrResult =
        parse(
            options.toBuilder().enablePrattParser(false).build(),
            macros,
            expression,
            validateParseOutput);
    ParseOutput prattResult =
        parse(
            options.toBuilder().enablePrattParser(true).build(),
            macros,
            expression,
            validateParseOutput);

    assertThat(prattResult.isError()).isEqualTo(antlrResult.isError());
    if (!antlrResult.isError()) {
      if (validateParseOutput) {
        assertThat(prattResult.pOutput).isEqualTo(antlrResult.pOutput);
        testOutput().println("P: " + antlrResult.pOutput);

        assertThat(prattResult.lOutput).isEqualTo(antlrResult.lOutput);
        if (!Strings.isNullOrEmpty(antlrResult.lOutput)) {
          testOutput().println("L: " + antlrResult.lOutput);
        }
      }

      assertThat(prattResult.mOutput).isEqualTo(antlrResult.mOutput);
      if (!Strings.isNullOrEmpty(antlrResult.mOutput)) {
        testOutput().println("M: " + antlrResult.mOutput);
      }
    } else {
      testOutput().println("E/A: " + sanitizeForBaseline(antlrResult.errorMessage));
      testOutput().println("E/P: " + sanitizeForBaseline(prattResult.errorMessage));
    }

    testOutput().println();
  }

  private void runSourceInfoTest(String expression) throws Exception {
    testOutput().println("I: " + expression);
    testOutput().println("=====>");
    CelParser antlrParser =
        CelParserImpl.newBuilder()
            .setOptions(OPTIONS.toBuilder().enablePrattParser(false).build())
            .addMacros(MACROS.values())
            .build();
    CelParser prattParser =
        CelParserImpl.newBuilder()
            .setOptions(OPTIONS.toBuilder().enablePrattParser(true).build())
            .addMacros(MACROS.values())
            .build();

    CelAbstractSyntaxTree antlrAst = antlrParser.parse(expression).getAst();
    CelAbstractSyntaxTree prattAst = prattParser.parse(expression).getAst();

    SourceInfo antlrSourceInfo =
        CelProtoAbstractSyntaxTree.fromCelAst(antlrAst).toParsedExpr().getSourceInfo();
    SourceInfo prattSourceInfo =
        CelProtoAbstractSyntaxTree.fromCelAst(prattAst).toParsedExpr().getSourceInfo();

    assertThat(prattSourceInfo).isEqualTo(antlrSourceInfo);
    testOutput().println("S: " + TextFormat.printer().printToString(antlrSourceInfo));
  }

  private static String sanitizeForBaseline(String text) {
    if (text == null) {
      return null;
    }
    return text.replace("\t", "»").replace("\u007f", "\\u007f");
  }
}
