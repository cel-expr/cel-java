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

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import com.google.testing.junit.testparameterinjector.TestParameters;
import dev.cel.common.CelIssue;
import dev.cel.common.CelOptions;
import dev.cel.common.CelSource;
import dev.cel.common.CelValidationException;
import dev.cel.common.CelValidationResult;
import dev.cel.common.ast.CelConstant;
import dev.cel.common.ast.CelExpr;
import java.util.Collections;
import java.util.Optional;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(TestParameterInjector.class)
public final class CelParserImplTest {

  // This file exercises non-parsing related methods in CelParser. See CelParserParameterizedTest
  // for parsing related tests.

  @TestParameter private boolean enablePrattParser;

  private CelParserBuilder newParserBuilder() {
    return CelParserImpl.newBuilder()
        .setOptions(CelOptions.newBuilder().enablePrattParser(enablePrattParser).build());
  }

  @Test
  public void build_withMacros_containsAllMacros() {
    CelParserImpl parser =
        (CelParserImpl)
            newParserBuilder().setStandardMacros(CelStandardMacro.STANDARD_MACROS).build();

    assertThat(parser.findMacro("has:1:false")).hasValue(CelStandardMacro.HAS.getDefinition());
    assertThat(parser.findMacro("all:2:true")).hasValue(CelStandardMacro.ALL.getDefinition());
    assertThat(parser.findMacro("exists:2:true")).hasValue(CelStandardMacro.EXISTS.getDefinition());
    assertThat(parser.findMacro("exists_one:2:true"))
        .hasValue(CelStandardMacro.EXISTS_ONE.getDefinition());
    assertThat(parser.findMacro("map:2:true")).hasValue(CelStandardMacro.MAP.getDefinition());
    assertThat(parser.findMacro("map:3:true"))
        .hasValue(CelStandardMacro.MAP_FILTER.getDefinition());
    assertThat(parser.findMacro("filter:2:true")).hasValue(CelStandardMacro.FILTER.getDefinition());
  }

  @Test
  public void build_withStandardMacros_containsAllMacros() {
    CelParserImpl parser =
        (CelParserImpl)
            newParserBuilder().setStandardMacros(CelStandardMacro.STANDARD_MACROS).build();

    assertThat(parser.findMacro("has:1:false")).hasValue(CelStandardMacro.HAS.getDefinition());
    assertThat(parser.findMacro("all:2:true")).hasValue(CelStandardMacro.ALL.getDefinition());
    assertThat(parser.findMacro("exists:2:true")).hasValue(CelStandardMacro.EXISTS.getDefinition());
    assertThat(parser.findMacro("exists_one:2:true"))
        .hasValue(CelStandardMacro.EXISTS_ONE.getDefinition());
    assertThat(parser.findMacro("map:2:true")).hasValue(CelStandardMacro.MAP.getDefinition());
    assertThat(parser.findMacro("map:3:true"))
        .hasValue(CelStandardMacro.MAP_FILTER.getDefinition());
    assertThat(parser.findMacro("filter:2:true")).hasValue(CelStandardMacro.FILTER.getDefinition());
  }

  @Test
  public void build_withStandardMacrosAndCustomMacros_containsAllMacros() {
    CelMacro customMacro =
        CelMacro.newReceiverMacro(
            "customMacro", 1, (a, b, c) -> Optional.of(CelExpr.newBuilder().build()));
    CelParserImpl parser =
        (CelParserImpl)
            newParserBuilder()
                .setStandardMacros(CelStandardMacro.STANDARD_MACROS)
                .addMacros(customMacro)
                .build();

    assertThat(parser.findMacro("has:1:false")).hasValue(CelStandardMacro.HAS.getDefinition());
    assertThat(parser.findMacro("all:2:true")).hasValue(CelStandardMacro.ALL.getDefinition());
    assertThat(parser.findMacro("exists:2:true")).hasValue(CelStandardMacro.EXISTS.getDefinition());
    assertThat(parser.findMacro("exists_one:2:true"))
        .hasValue(CelStandardMacro.EXISTS_ONE.getDefinition());
    assertThat(parser.findMacro("map:2:true")).hasValue(CelStandardMacro.MAP.getDefinition());
    assertThat(parser.findMacro("map:3:true"))
        .hasValue(CelStandardMacro.MAP_FILTER.getDefinition());
    assertThat(parser.findMacro("filter:2:true")).hasValue(CelStandardMacro.FILTER.getDefinition());
    assertThat(parser.findMacro("customMacro:1:true")).hasValue(customMacro);
  }

  @Test
  public void build_withMacro_containsMacro() {
    CelParserImpl parser =
        (CelParserImpl) newParserBuilder().setStandardMacros(CelStandardMacro.HAS).build();

    assertThat(parser.findMacro("has:1:false")).hasValue(CelStandardMacro.HAS.getDefinition());
  }

  @Test
  public void build_withStandardMacro_containsMacro() {
    CelParserImpl parser =
        (CelParserImpl) newParserBuilder().setStandardMacros(CelStandardMacro.HAS).build();

    assertThat(parser.findMacro("has:1:false")).hasValue(CelStandardMacro.HAS.getDefinition());
  }

  @Test
  public void build_withStandardMacro_secondCallReplaces() {
    CelParserImpl parser =
        (CelParserImpl)
            newParserBuilder()
                .setStandardMacros(CelStandardMacro.HAS, CelStandardMacro.ALL)
                .setStandardMacros(CelStandardMacro.HAS)
                .build();

    assertThat(parser.findMacro("has:1:false")).hasValue(CelStandardMacro.HAS.getDefinition());
    assertThat(parser.findMacro("all:2:true")).isEmpty();
  }

  @Test
  public void build_standardMacroKeyConflictsWithCustomMacro_throws() {
    CelMacro customMacro =
        CelMacro.newGlobalMacro("has", 1, (a, b, c) -> Optional.of(CelExpr.newBuilder().build()));

    assertThrows(
        IllegalArgumentException.class,
        () ->
            newParserBuilder()
                .setStandardMacros(CelStandardMacro.HAS)
                .addMacros(customMacro)
                .build());
  }

  @Test
  public void build_containsNoMacros() {
    CelParserImpl parser = (CelParserImpl) newParserBuilder().build();

    assertThat(parser.findMacro("has:1:false")).isEmpty();
  }

  @Test
  public void setParserLibrary_success() {
    CelParserImpl parser =
        (CelParserImpl)
            newParserBuilder()
                .addLibraries(
                    new CelParserLibrary() {
                      @Override
                      public void setParserOptions(CelParserBuilder parserBuilder) {
                        parserBuilder.addMacros(
                            CelMacro.newReceiverVarArgMacro(
                                "dummyMacro",
                                (a, b, c) -> Optional.of(CelExpr.newBuilder().build())));
                      }
                    })
                .build();

    assertThat(parser.findMacro("dummyMacro:*:true")).isPresent();
  }

  @Test
  public void parse_throwsWhenExpressionSizeCodePointLimitExceeded() {
    CelParserImpl parser =
        (CelParserImpl)
            newParserBuilder()
                .setOptions(
                    CelOptions.newBuilder()
                        .enablePrattParser(enablePrattParser)
                        .maxExpressionCodePointSize(2)
                        .build())
                .build();

    CelValidationResult parseResult = parser.parse(CelSource.newBuilder("foo").build());

    CelValidationException exception =
        assertThrows(CelValidationException.class, parseResult::getAst);
    assertThat(exception.getErrors()).hasSize(1);
    assertThat(exception.getErrors().get(0).getMessage())
        .isEqualTo("expression code point size exceeds limit: size: 3, limit 2");
  }

  private enum MaxParseRecursionDepthTestCase {
    LARGE_CALC(
        "1 + 2 + 3 + 4 + 5 + 6 + 7 + 8 + 9 + 10 + 11 + 12 + 13 + 14 + 15 + 16 + 17 + 18 + 19 + 20 +"
            + " 21 + 22 + 23 + 24 + 25 + 26 + 27 + 28 + 29 + 30 + 31 + 32 + 33 + 34",
        32),
    NESTED_PARENS("((((((((((((((((((((((((((((((((7))))))))))))))))))))))))))))))))", 32),
    NESTED_PARENS_WITH_CALC(
        "((((((((((((((((((((((((((((((((7)))))))))))))))))))))))))))))))) +"
            + "(((((((((((((((((((((((((((((((7)))))))))))))))))))))))))))))))",
        32),
    FIELD_SELECTIONS("a.b.c.d.e.f.g.h.i.j.k.l.m.n.o.p.q.r.s.t.u.v.w.x.y.z.A.B.C.D.E.F.G.H", 32),
    INDEX_OPERATIONS(
        "a[1][2][3][4][5][6][7][8][9][10][11][12][13][14][15][16][17][18][19][20][21][22][23][24][25][26][27][28][29][30][31][32][33]",
        32),
    RELATION_OPERATORS(
        "a < 1 < 2 < 3 < 4 < 5 < 6 < 7 < 8 < 9 < 10 < 11 < 12 < 13 < 14 < 15 < 16 < 17 < 18 < 19 <"
            + " 20 < 21 < 22 < 23 < 24 < 25 < 26 < 27 < 28 < 29 < 30 < 31 < 32 < 33",
        32),
    // More than 32 index / relation operators. Note, the recursion count is the
    // maximum recursion level on the left or right side index expression (20) plus
    // the number of relation operators (13)
    INDEX_RELATION_OPERATORS(
        "a[1][2][3][4][5][6][7][8][9][10][11][12][13][14][15][16][17][18][19][20] !="
            + " a[1][2][3][4][5][6][7][8][9][10][11][12][13][14][15][16][17][18][19][20] !="
            + " a[1][2][3][4][5][6][7][8][9][10][11][12][13][14][15][16][17][18][19][20] !="
            + " a[1][2][3][4][5][6][7][8][9][10][11][12][13][14][15][16][17][18][19][20] !="
            + " a[1][2][3][4][5][6][7][8][9][10][11][12][13][14][15][16][17][18][19][20] !="
            + " a[1][2][3][4][5][6][7][8][9][10][11][12][13][14][15][16][17][18][19][20] !="
            + " a[1][2][3][4][5][6][7][8][9][10][11][12][13][14][15][16][17][18][19][20] !="
            + " a[1][2][3][4][5][6][7][8][9][10][11][12][13][14][15][16][17][18][19][20] !="
            + " a[1][2][3][4][5][6][7][8][9][10][11][12][13][14][15][16][17][18][19][20] !="
            + " a[1][2][3][4][5][6][7][8][9][10][11][12][13][14][15][16][17][18][19][20] !="
            + " a[1][2][3][4][5][6][7][8][9][10][11][12][13][14][15][16][17][18][19][20] !="
            + " a[1][2][3][4][5][6][7][8][9][10][11][12][13][14][15][16][17][18][19][20] !="
            + " a[1][2][3][4][5][6][7][8][9][10][11][12][13][14][15][16][17][18][19][20] !="
            + " a[1][2][3][4][5][6][7][8][9][10][11][12][13][14][15][16][17][18][19][20]",
        32),
    TERNARY(
        "a ? b : a ? b : a ? b : a ? b : a ? b : a ? b : a ? b : a ? b :"
            + " a ? b : a ? b : a ? b : a ? b : a ? b : a ? b : a ? b : a ? b :"
            + " a ? b : a ? b : a ? b : a ? b : a ? b : a ? b : a ? b : a ? b :"
            + " a ? b : a ? b : a ? b : a ? b : a ? b : a ? b : a ? b : a ? b : c",
        32),
    TERNARY_TRUE_BRANCH_PARENS(
        "a ? ((((((((((((((((((((((((((((((((b)))))))))))))))))))))))))))))))) : c", 32),
    NESTED_LEFT_PARENS_WITH_CALC(
        "((((((((((((((((((((((((((((((((7) + 1) + 1) + 1) + 1) + 1) + 1) + 1) + 1) + 1) + 1) + 1)"
            + " + 1) + 1) + 1) + 1) + 1) + 1) + 1) + 1) + 1) + 1) + 1) + 1) + 1) + 1) + 1) + 1) +"
            + " 1) + 1) + 1) + 1)",
        32),
    NESTED_RIGHT_PARENS_WITH_LOGICAL_OR(
        "(true) || (true || (true || (true || (true || (true || (true || (true || (true || (true ||"
            + " (true || (true || (true || (true || (true || (true || (true || (true || (true ||"
            + " (true || (true || (true || (true || (true || (true || (true || (true || (true ||"
            + " (true || (true || (true || (true || (true || false))))))))))))))))))))))))))))))))",
        32),
    // Parens nested inside an operand are charged against the depth reached so far rather than
    // accumulating on top of the enclosing parens, so the Pratt parser allows more depth here.
    GROUPING_PARENS_AROUND_CALC(
        "((1 + ((7))))", /* antlrMaxRecursionLimit= */ 5, /* prattMaxRecursionLimit= */ 3),
    PARENTHESIZED_LHS_CALC("(1 + 1 + 1) + 1 + 1 + 1", 4),
    NESTED_LEFT_PARENS_LHS_CALC("((1 + 1) + 1) + 1 + 1 + 1", 4),
    GROUPING_PARENS_LHS_CALC("(((1 + 1 + 1))) + 1 + 1 + 1", 4),
    PARENTHESIZED_RHS_CALC("1 + (1 + 1 + 1 + 1 + 1)", 4),
    PARENTHESIZED_FIELD_SELECTIONS("(a.b.c).d.e.f", 4),
    // Depth accumulated inside a call argument carries into the enclosing selector and operator
    // chains that wrap the call.
    CALL_ARGUMENT_FIELD_SELECTIONS("f(a.b.c.d).e", 3),
    CALL_ARGUMENT_FIELD_SELECTIONS_WITH_CALC("x + f(a.b.c.d) + y", 4),
    CALL_ARGUMENT_CALC_WITH_CALC("x + f(a + b + c + d) + y", 4),
    INDEX_FIELD_SELECTIONS_WITH_CALC("x + a[b.c.d.e] + y", 5),
    MEMBER_CALL_ARGUMENT_FIELD_SELECTIONS_WITH_CALC("x + a.f(b.c.d.e) + y", 5),
    STRUCT_FIELD_SELECTIONS_WITH_CALC("x + Msg{f: a.b.c.d} + y", 4),
    // A delimited construct contributes its deepest element, not its last one.
    CALL_ARGUMENT_DEEPEST_NOT_LAST("x + f(a.b.c.d, 1) + y", 4),
    MEMBER_CALL_ARGUMENT_DEEPEST_NOT_LAST("x + a.f(b.c.d.e, 1) + y", 5),
    LIST_ELEMENT_DEEPEST_NOT_LAST("x + [a.b.c.d, 1][0] + y", 5),
    MAP_VALUE_DEEPEST_NOT_LAST("x + {'k': a.b.c.d, 'j': 1}['k'] + y", 5),
    MAP_KEY_DEEPEST_NOT_LAST("x + {a.b.c.d.e: 1, 'j': 2}['j'] + y", 6),
    STRUCT_FIELD_DEEPEST_NOT_LAST("x + Msg{f: a.b.c.d, g: 1} + y", 4),
    NESTED_LIST_ELEMENT_DEEPEST_NOT_LAST("x + [[a.b.c.d, 1], 1][0] + y", 5),
    PARENTHESIZED_LOGICAL_AND_FIELD_SELECTION("((a && b.c.d).e)", 2),
    PARENTHESIZED_LOGICAL_AND_CHAIN_FIELD_SELECTION("((a && b && c.d.e).f)", 2),
    FIELD_SELECTIONS_WITH_CALC("a.b.c.d.e + f.g", 4);

    final String source;
    final int antlrMaxRecursionLimit;
    final int prattMaxRecursionLimit;

    MaxParseRecursionDepthTestCase(String source, int maxRecursionLimit) {
      this(source, maxRecursionLimit, maxRecursionLimit);
    }

    MaxParseRecursionDepthTestCase(
        String source, int antlrMaxRecursionLimit, int prattMaxRecursionLimit) {
      this.source = source;
      this.antlrMaxRecursionLimit = antlrMaxRecursionLimit;
      this.prattMaxRecursionLimit = prattMaxRecursionLimit;
    }
  }

  @Test
  public void parse_nestedParenthesesWithTernaryAndSelectors_succeeds() throws Exception {
    CelParser parser = newParserBuilder().build();

    CelValidationResult parseResult =
        parser.parse("((a ? b : c).d[0] ? (e ? f : g) : h) + ((x).y)");

    assertThat(parseResult.hasError()).isFalse();
    assertThat(parseResult.getAst()).isNotNull();
  }

  @Test
  public void parse_largeExprHitsMaxRecursionLimit_throws(
      @TestParameter MaxParseRecursionDepthTestCase testCase) {
    int maxParseRecursionLimit =
        enablePrattParser ? testCase.prattMaxRecursionLimit : testCase.antlrMaxRecursionLimit;
    CelParser parser =
        newParserBuilder()
            .setOptions(
                CelOptions.newBuilder()
                    .enablePrattParser(enablePrattParser)
                    .maxParseRecursionDepth(maxParseRecursionLimit)
                    .build())
            .build();

    CelValidationResult parseResult = parser.parse(CelSource.newBuilder(testCase.source).build());

    CelValidationException exception =
        assertThrows(CelValidationException.class, parseResult::getAst);
    assertThat(exception)
        .hasMessageThat()
        .contains("Expression recursion limit exceeded. limit: " + maxParseRecursionLimit);
    assertThat(exception.getErrors()).hasSize(1);
    CelIssue issue = exception.getErrors().get(0);
    assertThat(issue.getMessage())
        .contains("Expression recursion limit exceeded. limit: " + maxParseRecursionLimit);
    assertThat(issue.getSourceLocation().getLine()).isEqualTo(1);
    assertThat(issue.getSourceLocation().getColumn()).isAtLeast(0);
  }

  @Test
  public void parse_exprUnderMaxRecursionLimit_doesNotThrow(
      @TestParameter MaxParseRecursionDepthTestCase testCase) throws CelValidationException {
    int maxParseRecursionLimit =
        (enablePrattParser ? testCase.prattMaxRecursionLimit : testCase.antlrMaxRecursionLimit) + 1;
    CelParser parser =
        newParserBuilder()
            .setOptions(
                CelOptions.newBuilder()
                    .enablePrattParser(enablePrattParser)
                    .maxParseRecursionDepth(maxParseRecursionLimit)
                    .build())
            .build();

    CelValidationResult parseResult = parser.parse(CelSource.newBuilder(testCase.source).build());

    assertThat(parseResult.hasError()).isFalse();
    assertThat(parseResult.getAst()).isNotNull();
  }

  @Test
  public void parse_nodeLimitExceeded_throws() {
    CelParser parser =
        newParserBuilder()
            .setOptions(
                CelOptions.newBuilder()
                    .enablePrattParser(enablePrattParser)
                    .maxParseExpressionNodeCount(2)
                    .build())
            .build();

    CelValidationResult parseResult = parser.parse("a + b + c");

    CelValidationException exception =
        assertThrows(CelValidationException.class, parseResult::getAst);
    assertThat(exception).hasMessageThat().contains("expression node limit (2) exceeded");
    assertThat(exception.getErrors()).hasSize(1);
  }

  @Test
  public void parse_macroExpansionNodeLimitExceeded_throws() {
    CelParser parser =
        newParserBuilder()
            .setStandardMacros(CelStandardMacro.STANDARD_MACROS)
            .setOptions(
                CelOptions.newBuilder()
                    .enablePrattParser(enablePrattParser)
                    .maxParseExpressionNodeCount(5)
                    .build())
            .build();

    CelValidationResult parseResult = parser.parse("[1, 2, 3, 4, 5].map(x, x * 2)");

    CelValidationException exception =
        assertThrows(CelValidationException.class, parseResult::getAst);
    assertThat(exception).hasMessageThat().contains("expression node limit (5) exceeded");
    assertThat(
            exception.getErrors().stream()
                .anyMatch(
                    issue ->
                        issue
                            .getMessage()
                            .contains("could not expand macro: expression node limit exceeded")))
        .isTrue();
  }

  @Test
  public void parse_macroExpansionNodeLimitNotExceeded_success() throws CelValidationException {
    CelParser parser =
        newParserBuilder()
            .setStandardMacros(CelStandardMacro.STANDARD_MACROS)
            .setOptions(
                CelOptions.newBuilder()
                    .enablePrattParser(enablePrattParser)
                    .maxParseExpressionNodeCount(100)
                    .build())
            .build();

    CelValidationResult parseResult = parser.parse("[1, 2, 3, 4, 5].map(x, x * 2)");

    assertThat(parseResult.hasError()).isFalse();
    assertThat(parseResult.getAst()).isNotNull();
  }

  @Test
  @TestParameters("{expression: 'A.map(a?b, c)'}")
  @TestParameters("{expression: 'A.all(a?b, c)'}")
  @TestParameters("{expression: 'A.exists(a?b, c)'}")
  @TestParameters("{expression: 'A.exists_one(a?b, c)'}")
  @TestParameters("{expression: 'A.existsOne(a?b, c)'}")
  @TestParameters("{expression: 'A.filter(a?b, c)'}")
  public void parse_macroArgumentContainsSyntaxError_throws(String expression) {
    CelParser parser =
        newParserBuilder()
            .setStandardMacros(
                ImmutableSet.<CelStandardMacro>builder()
                    .addAll(CelStandardMacro.STANDARD_MACROS)
                    .add(CelStandardMacro.EXISTS_ONE_NEW)
                    .build())
            .build();

    CelValidationResult parseResult = parser.parse(expression);

    assertThat(parseResult.hasError()).isTrue();
    assertThat(parseResult.getErrorString()).contains("ERROR: <input>");
    assertThrows(CelValidationException.class, parseResult::getAst);
  }

  @Test
  public void toParserBuilder_isNewInstance() {
    CelParserBuilder celParserBuilder = newParserBuilder();
    CelParserImpl celParser = (CelParserImpl) celParserBuilder.build();

    CelParserImpl.Builder newParserBuilder = (CelParserImpl.Builder) celParser.toParserBuilder();

    assertThat(newParserBuilder).isNotEqualTo(celParserBuilder);
  }

  @Test
  public void toParserBuilder_isImmutable() {
    CelParserBuilder originalParserBuilder = newParserBuilder();
    CelParserImpl celParser = (CelParserImpl) originalParserBuilder.build();
    originalParserBuilder.addLibraries(new CelParserLibrary() {});

    CelParserImpl.Builder newParserBuilder = (CelParserImpl.Builder) celParser.toParserBuilder();

    assertThat(newParserBuilder.getParserLibraries().build()).isEmpty();
  }

  @Test
  public void toParserBuilder_collectionProperties_copied() {
    CelParserBuilder celParserBuilder =
        newParserBuilder()
            .setStandardMacros(CelStandardMacro.STANDARD_MACROS)
            .addMacros(
                CelMacro.newGlobalMacro(
                    "test", 1, (a, b, c) -> Optional.of(CelExpr.newBuilder().build())))
            .addLibraries(new CelParserLibrary() {});
    CelParserImpl celParser = (CelParserImpl) celParserBuilder.build();

    CelParserImpl.Builder newParserBuilder = (CelParserImpl.Builder) celParser.toParserBuilder();

    assertThat(newParserBuilder.getStandardMacros())
        .hasSize(CelStandardMacro.STANDARD_MACROS.size());
    assertThat(newParserBuilder.getMacros()).hasSize(1);
    assertThat(newParserBuilder.getParserLibraries().build()).hasSize(1);
  }

  @Test
  public void parse_logicalChainLongerThanInitialCapacity_succeeds() {
    CelParser parser = newParserBuilder().build();

    for (int operands = 2; operands <= 64; operands++) {
      String expr = Joiner.on(" || ").join(Collections.nCopies(operands, "true"));

      CelValidationResult result = parser.parse(expr);

      assertThat(result.hasError()).isFalse();
    }
  }

  @Test
  public void parse_lexerErrorExceedsRecoveryLimit_stopsParsing() {
    Assume.assumeTrue(enablePrattParser);
    CelParser parser =
        newParserBuilder()
            .setOptions(
                CelOptions.newBuilder()
                    .enablePrattParser(enablePrattParser)
                    .maxParseErrorRecoveryLimit(2)
                    .build())
            .build();

    CelValidationResult result = parser.parse("[ @, @, @ ]");

    assertThat(result.hasError()).isTrue();
    assertThat(result.getErrors()).hasSize(3);
  }

  @Test
  public void parse_largeExpression_expandsPositionsArray() throws Exception {
    CelParser parser = newParserBuilder().build();
    String expr = "[" + Joiner.on(", ").join(Collections.nCopies(1025, "1")) + "]";
    ImmutableMap.Builder<Long, Integer> expectedPositions =
        ImmutableMap.builderWithExpectedSize(1026);
    expectedPositions.put(1L, 0);
    for (int i = 0; i < 1025; i++) {
      expectedPositions.put((long) (i + 2), 1 + 3 * i);
    }

    CelValidationResult result = parser.parse(expr);

    assertThat(result.hasError()).isFalse();
    assertThat(result.getAst().getSource().getPositionsMap())
        .containsExactlyEntriesIn(expectedPositions.buildOrThrow());
  }

  @Test
  public void parse_macroCopiesNodeWithoutPosition_noSourcePositionRecorded() throws Exception {
    CelMacro macro =
        CelMacro.newGlobalMacro(
            "copy_macro",
            0,
            (exprFactory, target, args) -> {
              CelExpr nodeWithoutPosition =
                  CelExpr.newBuilder().setId(5L).setConstant(CelConstant.ofValue(10L)).build();
              return Optional.of(exprFactory.copy(nodeWithoutPosition));
            });
    CelParser parser = newParserBuilder().addMacros(macro).build();

    CelValidationResult result = parser.parse("copy_macro()");

    assertThat(result.hasError()).isFalse();
    // The contract for nodes without a source position is -1 (NO_POSITION). Unpositioned
    // nodes must not be assigned position 0 (which is a valid source offset) and thus should
    // not have an entry recorded in the positions map.
    assertThat(result.getAst().getSource().getPositionsMap()).isEmpty();
  }
}
