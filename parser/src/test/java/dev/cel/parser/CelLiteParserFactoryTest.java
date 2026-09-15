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

package dev.cel.parser;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import dev.cel.common.CelOptions;
import dev.cel.common.CelSource;
import dev.cel.common.CelValidationResult;
import dev.cel.common.ast.CelExpr.ExprKind.Kind;
import java.util.Optional;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class CelLiteParserFactoryTest {

  @Test
  public void newLiteParserBuilder_build_isNotNull() {
    CelParserBuilder builder = CelLiteParserFactory.newLiteParserBuilder();

    CelParser parser = builder.build();

    assertThat(parser).isNotNull();
  }

  @Test
  public void newLiteParserBuilder_defaultOptions_matchesCurrentWithPrattParserEnabled() {
    CelParserBuilder builder = CelLiteParserFactory.newLiteParserBuilder();

    assertThat(builder.getOptions())
        .isEqualTo(CelOptions.current().enablePrattParser(true).build());
  }

  @Test
  public void newLiteParserBuilder_parseSmokeTest() {
    CelParser parser = CelLiteParserFactory.newLiteParserBuilder().build();

    CelValidationResult result = parser.parse("1 + 1");

    assertThat(result.hasError()).isFalse();
  }

  @Test
  public void parse_nullSource_throwsNullPointerException() {
    CelParser parser = CelLiteParserFactory.newLiteParserBuilder().build();

    assertThrows(NullPointerException.class, () -> parser.parse((CelSource) null));
  }

  @Test
  public void setStandardMacros_secondCallReplacesPreviousStandardMacros() throws Exception {
    CelParser parser =
        CelLiteParserFactory.newLiteParserBuilder()
            .setStandardMacros(CelStandardMacro.HAS, CelStandardMacro.ALL)
            .setStandardMacros(CelStandardMacro.HAS)
            .build();

    CelValidationResult hasResult = parser.parse("has(a.b)");
    CelValidationResult allResult = parser.parse("[1].all(x, x > 0)");

    assertThat(hasResult.getAst().getExpr().getKind()).isEqualTo(Kind.SELECT);
    assertThat(allResult.getAst().getExpr().getKind()).isEqualTo(Kind.CALL);
  }

  @Test
  public void setStandardMacros_allStandardMacrosSupported() throws Exception {
    CelParser parser =
        CelLiteParserFactory.newLiteParserBuilder()
            .setStandardMacros(CelStandardMacro.STANDARD_MACROS)
            .build();

    assertThat(parser.parse("has(a.b)").getAst().getExpr().getKind()).isEqualTo(Kind.SELECT);
    assertThat(parser.parse("[1].all(x, x > 0)").getAst().getExpr().getKind())
        .isEqualTo(Kind.COMPREHENSION);
    assertThat(parser.parse("[1].exists(x, x > 0)").getAst().getExpr().getKind())
        .isEqualTo(Kind.COMPREHENSION);
    assertThat(parser.parse("[1].exists_one(x, x > 0)").getAst().getExpr().getKind())
        .isEqualTo(Kind.COMPREHENSION);
    assertThat(parser.parse("[1].map(x, x > 0)").getAst().getExpr().getKind())
        .isEqualTo(Kind.COMPREHENSION);
    assertThat(parser.parse("[1].map(x, x > 0, x)").getAst().getExpr().getKind())
        .isEqualTo(Kind.COMPREHENSION);
    assertThat(parser.parse("[1].filter(x, x > 0)").getAst().getExpr().getKind())
        .isEqualTo(Kind.COMPREHENSION);
  }

  @Test
  public void addMacros_registersCustomMacro() throws Exception {
    CelMacro customMacro =
        CelMacro.newGlobalMacro(
            "customMacro",
            0,
            (exprFactory, target, args) -> Optional.of(exprFactory.newBoolLiteral(true)));
    CelParser parser = CelLiteParserFactory.newLiteParserBuilder().addMacros(customMacro).build();

    CelValidationResult result = parser.parse("customMacro()");

    assertThat(result.getAst().getExpr().getKind()).isEqualTo(Kind.CONSTANT);
  }

  @Test
  public void addLibraries_configuresParserBuilder() throws Exception {
    CelParserLibrary library =
        new CelParserLibrary() {
          @Override
          public void setParserOptions(CelParserBuilder parserBuilder) {
            parserBuilder.addMacros(
                CelMacro.newGlobalMacro(
                    "libMacro",
                    0,
                    (exprFactory, target, args) -> Optional.of(exprFactory.newBoolLiteral(true))));
          }
        };
    CelParser parser = CelLiteParserFactory.newLiteParserBuilder().addLibraries(library).build();

    CelValidationResult result = parser.parse("libMacro()");

    assertThat(result.getAst().getExpr().getKind()).isEqualTo(Kind.CONSTANT);
  }

  @Test
  public void toParserBuilder_roundtripPreservesCustomAndStandardMacros() throws Exception {
    CelMacro customMacro =
        CelMacro.newGlobalMacro(
            "customMacro",
            0,
            (exprFactory, target, args) -> Optional.of(exprFactory.newBoolLiteral(true)));
    CelParser parser =
        CelLiteParserFactory.newLiteParserBuilder()
            .setStandardMacros(CelStandardMacro.HAS)
            .addMacros(customMacro)
            .build();

    CelParser roundtripParser = parser.toParserBuilder().build();
    CelValidationResult hasResult = roundtripParser.parse("has(a.b)");
    CelValidationResult customMacroResult = roundtripParser.parse("customMacro()");

    assertThat(hasResult.getAst().getExpr().getKind()).isEqualTo(Kind.SELECT);
    assertThat(customMacroResult.getAst().getExpr().getKind()).isEqualTo(Kind.CONSTANT);
  }

  @Test
  public void toParserBuilder_roundtripPreservesOptionsAndLibraries() throws Exception {
    CelParserLibrary library =
        new CelParserLibrary() {
          @Override
          public void setParserOptions(CelParserBuilder parserBuilder) {
            parserBuilder.addMacros(
                CelMacro.newGlobalMacro(
                    "libMacro",
                    0,
                    (exprFactory, target, args) -> Optional.of(exprFactory.newBoolLiteral(true))));
          }
        };
    CelOptions customOptions =
        CelOptions.current().enablePrattParser(true).maxExpressionCodePointSize(100).build();
    CelParser parser =
        CelLiteParserFactory.newLiteParserBuilder()
            .setOptions(customOptions)
            .addLibraries(library)
            .build();

    CelParser roundtripParser = parser.toParserBuilder().build();

    assertThat(roundtripParser.parse("libMacro()").getAst().getExpr().getKind())
        .isEqualTo(Kind.CONSTANT);
  }

  @Test
  public void toParserBuilder_createsNewBuilder() {
    CelParserBuilder originalBuilder = CelLiteParserFactory.newLiteParserBuilder();
    CelParser parser = originalBuilder.build();

    CelParserBuilder roundtripBuilder = parser.toParserBuilder();

    assertThat(roundtripBuilder).isNotSameInstanceAs(originalBuilder);
    assertThat(roundtripBuilder.build()).isNotNull();
  }

  @Test
  public void build_withPrattParserDisabled_throwsIllegalArgumentException() {
    CelParserBuilder builder =
        CelLiteParserFactory.newLiteParserBuilder()
            .setOptions(CelOptions.current().enablePrattParser(false).build());

    IllegalArgumentException exception =
        assertThrows(IllegalArgumentException.class, builder::build);

    assertThat(exception)
        .hasMessageThat()
        .isEqualTo("Misconfigured CelOptions: enablePrattParser cannot be disabled.");
  }

  @Test
  public void build_withLibraryDisablingPrattParser_throwsIllegalArgumentException() {
    CelParserLibrary disablingLibrary =
        new CelParserLibrary() {
          @Override
          public void setParserOptions(CelParserBuilder parserBuilder) {
            parserBuilder.setOptions(
                parserBuilder.getOptions().toBuilder().enablePrattParser(false).build());
          }
        };
    CelParserBuilder builder =
        CelLiteParserFactory.newLiteParserBuilder().addLibraries(disablingLibrary);

    IllegalArgumentException exception =
        assertThrows(IllegalArgumentException.class, builder::build);

    assertThat(exception)
        .hasMessageThat()
        .isEqualTo("Misconfigured CelOptions: enablePrattParser cannot be disabled.");
  }
}
