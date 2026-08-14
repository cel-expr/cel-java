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

package dev.cel.bundle;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import dev.cel.bundle.CelEnvironment.TypeDecl;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(TestParameterInjector.class)
public final class TypeSpecifierParserTest {

  @Test
  public void parse_concreteSimpleType() {
    assertThat(TypeDecl.parse("int")).isEqualTo(TypeDecl.create("int"));
    assertThat(TypeDecl.parse("string")).isEqualTo(TypeDecl.create("string"));
    assertThat(TypeDecl.parse("bool")).isEqualTo(TypeDecl.create("bool"));
    assertThat(TypeDecl.parse("double")).isEqualTo(TypeDecl.create("double"));
    assertThat(TypeDecl.parse("uint")).isEqualTo(TypeDecl.create("uint"));
    assertThat(TypeDecl.parse("bytes")).isEqualTo(TypeDecl.create("bytes"));
    assertThat(TypeDecl.parse("duration")).isEqualTo(TypeDecl.create("duration"));
    assertThat(TypeDecl.parse("timestamp")).isEqualTo(TypeDecl.create("timestamp"));
    assertThat(TypeDecl.parse("dyn")).isEqualTo(TypeDecl.create("dyn"));
    assertThat(TypeDecl.parse("any")).isEqualTo(TypeDecl.create("any"));
    assertThat(TypeDecl.parse("null_type")).isEqualTo(TypeDecl.create("null_type"));
  }

  @Test
  public void parse_qualifiedMessageType() {
    assertThat(TypeDecl.parse("google.protobuf.StringValue"))
        .isEqualTo(TypeDecl.create("google.protobuf.StringValue"));
    assertThat(TypeDecl.parse("google.rpc.context.AttributeContext.Request"))
        .isEqualTo(TypeDecl.create("google.rpc.context.AttributeContext.Request"));
    assertThat(TypeDecl.parse(".com.example.Message"))
        .isEqualTo(TypeDecl.create(".com.example.Message"));
  }

  @Test
  public void parse_parameterizedTypes() {
    assertThat(TypeDecl.parse("list<int>"))
        .isEqualTo(TypeDecl.newBuilder().setName("list").addParams(TypeDecl.create("int")).build());
    assertThat(TypeDecl.parse("map<string, dyn>"))
        .isEqualTo(
            TypeDecl.newBuilder()
                .setName("map")
                .addParams(TypeDecl.create("string"), TypeDecl.create("dyn"))
                .build());
    assertThat(TypeDecl.parse("optional_type<string>"))
        .isEqualTo(
            TypeDecl.newBuilder()
                .setName("optional_type")
                .addParams(TypeDecl.create("string"))
                .build());
    assertThat(TypeDecl.parse("type<int>"))
        .isEqualTo(TypeDecl.newBuilder().setName("type").addParams(TypeDecl.create("int")).build());
  }

  @Test
  public void parse_nestedParameterizedTypes() {
    assertThat(TypeDecl.parse("map<int, list<string>>"))
        .isEqualTo(
            TypeDecl.newBuilder()
                .setName("map")
                .addParams(
                    TypeDecl.create("int"),
                    TypeDecl.newBuilder()
                        .setName("list")
                        .addParams(TypeDecl.create("string"))
                        .build())
                .build());

    assertThat(TypeDecl.parse("list<map<string, optional_type<int>>>"))
        .isEqualTo(
            TypeDecl.newBuilder()
                .setName("list")
                .addParams(
                    TypeDecl.newBuilder()
                        .setName("map")
                        .addParams(
                            TypeDecl.create("string"),
                            TypeDecl.newBuilder()
                                .setName("optional_type")
                                .addParams(TypeDecl.create("int"))
                                .build())
                        .build())
                .build());
  }

  @Test
  public void parse_whitespaceTolerance() {
    assertThat(TypeDecl.parse(" list < int > "))
        .isEqualTo(TypeDecl.newBuilder().setName("list").addParams(TypeDecl.create("int")).build());
    assertThat(TypeDecl.parse(" map < string ,  list < int > > "))
        .isEqualTo(
            TypeDecl.newBuilder()
                .setName("map")
                .addParams(
                    TypeDecl.create("string"),
                    TypeDecl.newBuilder().setName("list").addParams(TypeDecl.create("int")).build())
                .build());
  }

  @Test
  public void parse_whitespaceWithTabsAndNewlines() {
    assertThat(TypeDecl.parse("list<\tstring\n>"))
        .isEqualTo(
            TypeDecl.newBuilder().setName("list").addParams(TypeDecl.create("string")).build());
    assertThat(TypeDecl.parse(" map < string ,\t int > "))
        .isEqualTo(
            TypeDecl.newBuilder()
                .setName("map")
                .addParams(TypeDecl.create("string"), TypeDecl.create("int"))
                .build());
    assertThat(TypeDecl.parse("map\t<\nint\r,\tstring\n>\r"))
        .isEqualTo(
            TypeDecl.newBuilder()
                .setName("map")
                .addParams(TypeDecl.create("int"), TypeDecl.create("string"))
                .build());
    assertThat(TypeDecl.parse("\tlist\n<\r~T\t>\n"))
        .isEqualTo(
            TypeDecl.newBuilder().setName("list").addParams(TypeDecl.ofTypeParam("T")).build());
  }

  @Test
  public void parse_typeParameters() {
    assertThat(TypeDecl.parse("~T")).isEqualTo(TypeDecl.ofTypeParam("T"));
    assertThat(TypeDecl.parse(" ~T ")).isEqualTo(TypeDecl.ofTypeParam("T"));
    assertThat(TypeDecl.parse("list<~T>"))
        .isEqualTo(
            TypeDecl.newBuilder().setName("list").addParams(TypeDecl.ofTypeParam("T")).build());
    assertThat(TypeDecl.parse("list< ~T >"))
        .isEqualTo(
            TypeDecl.newBuilder().setName("list").addParams(TypeDecl.ofTypeParam("T")).build());
    assertThat(TypeDecl.parse("map<~K, ~V>"))
        .isEqualTo(
            TypeDecl.newBuilder()
                .setName("map")
                .addParams(TypeDecl.ofTypeParam("K"), TypeDecl.ofTypeParam("V"))
                .build());
    assertThat(TypeDecl.parse("map< ~K , ~V >"))
        .isEqualTo(
            TypeDecl.newBuilder()
                .setName("map")
                .addParams(TypeDecl.ofTypeParam("K"), TypeDecl.ofTypeParam("V"))
                .build());
  }

  @Test
  public void parse_maxRecursionDepth_succeedsAtBoundary() {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < 64; i++) {
      sb.append("list<");
    }
    sb.append("int");
    for (int i = 0; i < 64; i++) {
      sb.append(">");
    }
    String input = sb.toString();
    TypeDecl result = TypeDecl.parse(input);
    assertThat(result).isNotNull();
  }

  @Test
  public void parse_exceedsMaxRecursionDepth_throws() {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < 65; i++) {
      sb.append("list<");
    }
    sb.append("int");
    for (int i = 0; i < 65; i++) {
      sb.append(">");
    }
    String input = sb.toString();
    IllegalArgumentException e =
        assertThrows(IllegalArgumentException.class, () -> TypeDecl.parse(input));
    assertThat(e).hasMessageThat().contains("exceeded maximum type specifier recursion depth");
  }

  @Test
  public void parse_errors(@TestParameter ParseErrorTestCase testCase) {
    IllegalArgumentException e =
        assertThrows(IllegalArgumentException.class, () -> TypeDecl.parse(testCase.input));
    assertThat(e).hasMessageThat().contains(testCase.expectedMessageSubstring);
  }

  private enum ParseErrorTestCase {
    EMPTY("", "missing identifier at position 0"),
    TRAILING_CHARACTERS("int int", "unexpected character 'i' at position 4 in \"int int\""),
    UNEXPECTED_CLOSING_BRACKET("int>", "unexpected character '>' at position 3 in \"int>\""),
    TRAILING_DOT(".foo.", "unexpected end of input"),
    CONSECUTIVE_DOTS("..foo", "identifier is expected, but '.' was found at position 1"),
    EMPTY_TYPE_PARAM("~", "unexpected end of input"),
    DIGIT_TYPE_PARAM(
        "~1",
        "invalid type parameter identifier '1' at position 1, must be a single character from A-Z"),
    LOWERCASE_TYPE_PARAM(
        "~t",
        "invalid type parameter identifier 't' at position 1, must be a single character from A-Z"),
    TYPE_PARAM_FOLLOWED_BY_NUMERIC(
        "~T1",
        "invalid type parameter identifier '1' at position 2, must be a single character from A-Z"),
    TYPE_PARAM_FOLLOWED_BY_UNDERSCORE(
        "~T_",
        "invalid type parameter identifier '_' at position 2, must be a single character from A-Z"),
    TYPE_PARAM_FOLLOWED_BY_LOWERCASE(
        "~Telem",
        "invalid type parameter identifier 'e' at position 2, must be a single character from A-Z"),
    MULTI_CHAR_TYPE_PARAM(
        "~elem",
        "invalid type parameter identifier 'e' at position 1, must be a single character from A-Z"),
    WHITESPACE_IN_IDENTIFIER(
        "google. protobuf.StringValue", "identifier is expected, but ' ' was found at position 7"),
    WHITESPACE_BEFORE_DOT(
        "google .protobuf.StringValue",
        "unexpected character '.' at position 7 in \"google .protobuf.StringValue\""),
    EXTRA_CLOSING_BRACKET("list<int>>", "unexpected character '>' at position 9 in \"list<int>>\""),
    CONSECUTIVE_OPENING_BRACKETS("list<<int>", "missing identifier at position 5"),
    TRAILING_COMMA("map<string,>", "identifier is expected, but '>' was found at position 11"),
    EMPTY_GENERIC_PARAM("map<, int>", "identifier is expected, but ',' was found at position 4"),
    UNFINISHED_GENERIC("list<", "missing identifier at position 5"),
    TRAILING_COMMA_GENERIC("map<int, >", "identifier is expected, but '>' was found at position 9"),
    UNCLOSED_GENERIC("map<int, string", "expected ',' or '>' at position 15"),
    ;

    private final String input;
    private final String expectedMessageSubstring;

    ParseErrorTestCase(String input, String expectedMessageSubstring) {
      this.input = input;
      this.expectedMessageSubstring = expectedMessageSubstring;
    }
  }
}
