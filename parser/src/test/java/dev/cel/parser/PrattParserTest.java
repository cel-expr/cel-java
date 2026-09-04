// Copyright 2026 Google LLC
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package dev.cel.parser;

import static com.google.common.collect.ImmutableMap.toImmutableMap;

import dev.cel.expr.ParsedExpr;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import dev.cel.common.CelOptions;
import dev.cel.common.CelProtoAbstractSyntaxTree;
import dev.cel.common.CelSource;
import dev.cel.common.CelValidationException;
import dev.cel.common.CelValidationResult;
import dev.cel.testing.BaselineTestCase;
import dev.cel.testing.CelDebug;
import dev.cel.testing.CelExprKindAndIdAdorner;
import dev.cel.testing.CelLocationAdorner;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(TestParameterInjector.class)
public final class PrattParserTest extends BaselineTestCase {

  private static final CelOptions OPTIONS =
      CelOptions.current()
          .populateMacroCalls(true)
          .enableOptionalSyntax(true)
          .enableQuotedIdentifierSyntax(true)
          .build();

  private static final CelOptions OPTIONS_MAX_RECURSION_DEPTH_32 =
      OPTIONS.toBuilder().maxParseRecursionDepth(32).build();

  private static final CelOptions OPTIONS_NO_OPTIONAL_SYNTAX =
      OPTIONS.toBuilder().enableOptionalSyntax(false).build();

  private static final CelOptions OPTIONS_QUOTED_IDENTIFIER_SYNTAX =
      OPTIONS.toBuilder().enableQuotedIdentifierSyntax(true).build();

  private static final CelOptions OPTIONS_NO_QUOTED_IDENTIFIER_SYNTAX =
      OPTIONS.toBuilder().enableQuotedIdentifierSyntax(false).build();

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
          .buildOrThrow();

  @Test
  public void pratt_parser_literals() {
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

    // Uint
    runTest("0u");
    runTest("23u");
    runTest("0xFu");
    runTest("0xFFFFFFFFFFFFFFFFFu");

    // Double
    runTest("3.14");
    runTest("23.39");
    runTest("1.99e90000009");

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

    // Bytes
    runTest("b'abc'");
    runTest("b\"abc\"");
    runTest("b\"\"\"hello\nworld");
    runTest("b\"hello\nworld\"");
    runTest("rb\"hello\nworld\"");
  }

  @Test
  @SuppressWarnings("InlineMeInliner") // String.repeat is unavailable under Java 8
  public void pratt_parser_core_syntax() {
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
    runTest("a.foo(1, 2)");

    // Unary operators
    runTest("!a");
    runTest("!x");
    runTest("! false");
    runTest("-a");

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
    runTest(OPTIONS_MAX_RECURSION_DEPTH_32, Strings.repeat("!-", 15) + "x", false);

    // Complex expressions
    runTest("1 + 2 * 3 - 1 / 2 == 6 % 1");
    runTest("x[\"a\"].single_int32 == 23");
    runTest("a.?b[?0] && a[?c]");
  }

  @Test
  public void pratt_parser_macros() {
    runTest("has(m.f)");
    runTest("has(a.b)");
    runTest("has(m)");

    runTest("m.all(v, f)");
    runTest("[1, 2].all(x, x > 0)");

    runTest("m.exists(v, f)");

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
  }

  @Test
  @SuppressWarnings("InlineMeInliner") // String.repeat is unavailable under Java 8
  public void pratt_parser_errors() {
    // Lexical errors
    runTest("*@a | b");
    runTest("1 + $");
    runTest(
        "\u00f3\u00a0\u00a2\n"
            + "\t\t\u00f3\u00a00\u00a0\n"
            + "\t\t\u007f0\"\"\"\\\"\"\\\"\"\"\\\"\"\\\"\"\"\\\"\"\\\"\"\"\\\"\"\\\"\"\"\\\"\\\"\"\"\\\"\"\\\"\"\"\\\"\"\\\"\"\"\\\"\"\\\"\"\"\\\"!\\\"\"\"\\\"\"\\\"\"\"\\\"\"\\\"");
    runTest("'\\udead' == '\\ufffd'");
    runTest("a | b");
    runTest("'3# < 10\" '& tru ^^");

    // Unexpected tokens
    runTest("1 + +");
    runTest("?");
    runTest("a ? b ((?))");
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
            + "\t\t--1--1---1--1--1--0--1--1--1--1--0--3--1--1--0--1--1--1\n"
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
    runTest("[1, 2, 3].map(var, var * var)");

    // Incomplete expressions
    runTest("1 +");
    runTest("--");
    runTest("{");

    // Unexpected token after expression
    runTest("TestAllTypes(){}");
    runTest("TestAllTypes{}()");
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

    // Macro errors
    runTest("1.all(2, 3)");

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

    // Unsupported quoted identifier location
    runTest(OPTIONS_QUOTED_IDENTIFIER_SYNTAX, "`b-c`");
    runTest(OPTIONS_QUOTED_IDENTIFIER_SYNTAX, "`b-c`()");
    runTest(OPTIONS_QUOTED_IDENTIFIER_SYNTAX, "a.`$b`");
    runTest(OPTIONS_QUOTED_IDENTIFIER_SYNTAX, "a.`b.c`()");

    // Recursion limit exceeded
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
    testOutput().println("I: " + expression.replace("\t", "»"));
    testOutput().println("=====>");

    CelSource source = CelSource.newBuilder(expression).setDescription("<input>").build();
    CelValidationResult parseResult = PrattParser.parse(source, options, macros);

    try {
      CelProtoAbstractSyntaxTree protoAst =
          CelProtoAbstractSyntaxTree.fromCelAst(parseResult.getAst());
      ParsedExpr parsedExpr = protoAst.toParsedExpr();
      if (validateParseOutput) {
        testOutput()
            .println(
                "P: "
                    + CelDebug.toAdornedDebugString(parsedExpr.getExpr(), new CelExprKindAndIdAdorner()));
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
}
