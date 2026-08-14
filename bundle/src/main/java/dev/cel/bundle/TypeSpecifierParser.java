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

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.collect.ImmutableList;
import dev.cel.bundle.CelEnvironment.TypeDecl;
import dev.cel.common.formats.ParserContext;

/**
 * Parses a type specifier shorthand string (e.g. {@code "map<string, int>"}, {@code "list<~T>"},
 * {@code "int"}) into a {@link TypeDecl}.
 */
final class TypeSpecifierParser {
  private static final int MAX_RECURSION_DEPTH = 64;
  static final TypeDecl ERROR_TYPE_DECL = TypeDecl.create("*error*");

  private final String text;
  private final int length;
  private int pos;

  static TypeDecl parse(String text) {
    checkNotNull(text);
    TypeSpecifierParser parser = new TypeSpecifierParser(text);
    return parser.parse();
  }

  static TypeDecl parse(ParserContext<?> ctx, long nodeId, String text) {
    checkNotNull(ctx);
    checkNotNull(text);
    try {
      return parse(text);
    } catch (IllegalArgumentException e) {
      ctx.reportError(nodeId, e.getMessage());
      return ERROR_TYPE_DECL;
    }
  }

  private TypeDecl parse() {
    TypeDecl res = parseTypeElem(0);
    skipWhitespace();
    if (pos < length) {
      throw new IllegalArgumentException(
          String.format(
              "unexpected character '%c' at position %d in %s",
              text.charAt(pos), pos, formatQuoted(text)));
    }
    return res;
  }

  private TypeSpecifierParser(String text) {
    this.text = text;
    this.length = text.length();
    this.pos = 0;
  }

  private TypeDecl parseTypeElem(int depth) {
    if (depth > MAX_RECURSION_DEPTH) {
      throw new IllegalArgumentException(
          String.format("exceeded maximum type specifier recursion depth at position %d", pos));
    }
    skipWhitespace();
    if (pos < length && text.charAt(pos) == '~') {
      pos++; // consume '~'
      String id = parseTypeParamIdent();
      return TypeDecl.ofTypeParam(id);
    }
    return parseConcreteType(depth);
  }

  private TypeDecl parseConcreteType(int depth) {
    String id = parseNamespaceIdentifier();
    skipWhitespace();
    if (pos < length && text.charAt(pos) == '<') {
      pos++; // consume '<'
      ImmutableList.Builder<TypeDecl> params = ImmutableList.builder();
      while (true) {
        TypeDecl param = parseTypeElem(depth + 1);
        params.add(param);
        skipWhitespace();
        if (pos < length && text.charAt(pos) == ',') {
          pos++; // consume ','
          continue;
        }
        if (pos < length && text.charAt(pos) == '>') {
          pos++; // consume '>'
          break;
        }
        throw new IllegalArgumentException(
            String.format("expected ',' or '>' at position %d", pos));
      }
      return TypeDecl.newBuilder().setName(id).addParams(params.build()).build();
    }
    return TypeDecl.create(id);
  }

  private String parseNamespaceIdentifier() {
    StringBuilder id = new StringBuilder();
    while (pos < length && text.charAt(pos) != '<') {
      char c = text.charAt(pos);
      if (c == '.') {
        id.append('.');
        pos++; // consume '.'
      }
      String ident = parseIdentifier();
      id.append(ident);
      if (pos < length && text.charAt(pos) != '.') {
        break;
      }
    }
    String identifier = id.toString();
    if (identifier.isEmpty()) {
      throw new IllegalArgumentException(String.format("missing identifier at position %d", pos));
    }
    return identifier;
  }

  private String parseIdentifier() {
    if (pos >= length) {
      throw new IllegalArgumentException("unexpected end of input");
    }
    int start = pos;
    while (pos < length) {
      char c = text.charAt(pos);
      boolean isValid = (pos == start) ? (isAlpha(c) || c == '_') : (isAlphaNumeric(c) || c == '_');
      if (isValid) {
        pos++;
        continue;
      }
      if (pos == start) {
        throw new IllegalArgumentException(
            String.format("identifier is expected, but '%c' was found at position %d", c, pos));
      }
      break;
    }
    return text.substring(start, pos);
  }

  private String parseTypeParamIdent() {
    if (pos >= length) {
      throw new IllegalArgumentException("unexpected end of input");
    }
    char c = text.charAt(pos);
    if (c < 'A' || c > 'Z') {
      throw new IllegalArgumentException(
          String.format(
              "invalid type parameter identifier '%c' at position %d, must be a single character"
                  + " from A-Z",
              c, pos));
    }
    pos++;
    if (pos < length) {
      char next = text.charAt(pos);
      if (isAlphaNumeric(next) || next == '_') {
        throw new IllegalArgumentException(
            String.format(
                "invalid type parameter identifier '%c' at position %d, must be a single character"
                    + " from A-Z",
                next, pos));
      }
    }
    return String.valueOf(c);
  }

  private void skipWhitespace() {
    while (pos < length) {
      char c = text.charAt(pos);
      if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
        pos++;
      } else {
        break;
      }
    }
  }

  private static boolean isAlpha(char c) {
    return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
  }

  private static boolean isAlphaNumeric(char c) {
    return isAlpha(c) || (c >= '0' && c <= '9');
  }

  private static String formatQuoted(String s) {
    return "\"" + s + "\"";
  }
}
