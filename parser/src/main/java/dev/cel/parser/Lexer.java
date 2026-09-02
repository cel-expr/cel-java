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

import com.google.common.collect.ImmutableMap;
import dev.cel.common.internal.CelCodePointArray;
import java.util.function.IntPredicate;
import org.jspecify.annotations.Nullable;

/**
 * Fast lexer for CEL expressions.
 *
 * <p>Ported from {@code third_party/cel/cpp/parser/internal/lexer.h} and {@code lexer.cc}.
 */
final class Lexer {

  enum TokenType {
    ERROR("error"),
    END("end"),
    WHITESPACE("whitespace"),
    COMMENT("comment"),

    // Keywords
    NULL("null"),
    FALSE("false"),
    TRUE("true"),
    IN("in"),
    RESERVED_WORD("reserved_word"),

    // Literals
    INT("int"),
    UINT("uint"),
    FLOAT("float"),
    STRING("string"),
    BYTES("bytes"),

    // Identifiers
    IDENT("ident"),

    // Delimiters
    LEFT_BRACKET("["),
    RIGHT_BRACKET("]"),
    LEFT_BRACE("{"),
    RIGHT_BRACE("}"),
    LEFT_PAREN("("),
    RIGHT_PAREN(")"),

    // Operators
    DOT("."),
    COMMA(","),
    MINUS("-"),
    PLUS("+"),
    ASTERISK("*"),
    SLASH("/"),
    PERCENT("%"),
    QUESTION("?"),
    COLON(":"),
    EXCLAMATION("!"),
    EQUAL("="),
    EQUAL_EQUAL("=="),
    EXCLAMATION_EQUAL("!="),
    LESS("<"),
    LESS_EQUAL("<="),
    GREATER(">"),
    GREATER_EQUAL(">="),
    LOGICAL_AND("&&"),
    LOGICAL_OR("||");

    private final String symbol;

    TokenType(String symbol) {
      this.symbol = symbol;
    }

    public String getSymbol() {
      return symbol;
    }

    @Override
    public String toString() {
      return symbol;
    }
  }

  static final class Token {
    final TokenType type;
    final int start;
    final int end;

    Token(TokenType type, int start, int end) {
      this.type = type;
      this.start = start;
      this.end = end;
    }

    @Override
    public String toString() {
      return "Token(" + type + ", " + start + ", " + end + ")";
    }
  }

  static final class LexerError {
    final int start;
    final int end;
    final String message;

    LexerError(int start, int end, String message) {
      this.start = start;
      this.end = end;
      this.message = message;
    }
  }

  private static final ImmutableMap<String, TokenType> KEYWORDS =
      ImmutableMap.<String, TokenType>builder()
          .put("false", TokenType.FALSE)
          .put("true", TokenType.TRUE)
          .put("null", TokenType.NULL)
          .put("in", TokenType.IN)
          .put("as", TokenType.RESERVED_WORD)
          .put("break", TokenType.RESERVED_WORD)
          .put("const", TokenType.RESERVED_WORD)
          .put("continue", TokenType.RESERVED_WORD)
          .put("else", TokenType.RESERVED_WORD)
          .put("for", TokenType.RESERVED_WORD)
          .put("function", TokenType.RESERVED_WORD)
          .put("if", TokenType.RESERVED_WORD)
          .put("import", TokenType.RESERVED_WORD)
          .put("let", TokenType.RESERVED_WORD)
          .put("loop", TokenType.RESERVED_WORD)
          .put("package", TokenType.RESERVED_WORD)
          .put("namespace", TokenType.RESERVED_WORD)
          .put("return", TokenType.RESERVED_WORD)
          .put("var", TokenType.RESERVED_WORD)
          .put("void", TokenType.RESERVED_WORD)
          .put("while", TokenType.RESERVED_WORD)
          .buildOrThrow();

  private final CelCodePointArray content;
  private int position;
  private LexerError error;

  Lexer(CelCodePointArray content) {
    this.content = content;
    this.position = 0;
    this.error = null;
  }

  Token lex() {
    int start = position;
    if (position >= content.size()) {
      return makeToken(TokenType.END, start, start);
    }
    int c = content.get(position);
    switch (c) {
      case '\f':
      case '\n':
      case ' ':
      case '\r':
      case 0x0B: // \v (vertical tab)
      case '\t':
        {
          consumeWhitespace();
          return makeToken(TokenType.WHITESPACE, start, position);
        }
      case '.':
        {
          if (position + 1 < content.size() && isDigit(content.get(position + 1))) {
            return consumeNumericLiteral();
          }
          advance(1);
          return makeToken(TokenType.DOT, start, position);
        }
      case ',':
        {
          advance(1);
          return makeToken(TokenType.COMMA, start, position);
        }
      case '!':
        {
          advance(1);
          if (consume('=')) {
            return makeToken(TokenType.EXCLAMATION_EQUAL, start, position);
          }
          return makeToken(TokenType.EXCLAMATION, start, position);
        }
      case '?':
        {
          advance(1);
          return makeToken(TokenType.QUESTION, start, position);
        }
      case '(':
        {
          advance(1);
          return makeToken(TokenType.LEFT_PAREN, start, position);
        }
      case ')':
        {
          advance(1);
          return makeToken(TokenType.RIGHT_PAREN, start, position);
        }
      case '{':
        {
          advance(1);
          return makeToken(TokenType.LEFT_BRACE, start, position);
        }
      case '}':
        {
          advance(1);
          return makeToken(TokenType.RIGHT_BRACE, start, position);
        }
      case '[':
        {
          advance(1);
          return makeToken(TokenType.LEFT_BRACKET, start, position);
        }
      case ']':
        {
          advance(1);
          return makeToken(TokenType.RIGHT_BRACKET, start, position);
        }
      case '=':
        {
          advance(1);
          if (consume('=')) {
            return makeToken(TokenType.EQUAL_EQUAL, start, position);
          }
          return makeToken(TokenType.EQUAL, start, position);
        }
      case '<':
        {
          advance(1);
          if (consume('=')) {
            return makeToken(TokenType.LESS_EQUAL, start, position);
          }
          return makeToken(TokenType.LESS, start, position);
        }
      case '>':
        {
          advance(1);
          if (consume('=')) {
            return makeToken(TokenType.GREATER_EQUAL, start, position);
          }
          return makeToken(TokenType.GREATER, start, position);
        }
      case ':':
        {
          advance(1);
          return makeToken(TokenType.COLON, start, position);
        }
      case '%':
        {
          advance(1);
          return makeToken(TokenType.PERCENT, start, position);
        }
      case '+':
        {
          advance(1);
          return makeToken(TokenType.PLUS, start, position);
        }
      case '-':
        {
          advance(1);
          return makeToken(TokenType.MINUS, start, position);
        }
      case '*':
        {
          advance(1);
          return makeToken(TokenType.ASTERISK, start, position);
        }
      case '/':
        {
          advance(1);
          if (consume('/')) {
            consumeLine();
            return makeToken(TokenType.COMMENT, start, position);
          }
          return makeToken(TokenType.SLASH, start, position);
        }
      case '&':
        {
          advance(1);
          if (consume('&')) {
            return makeToken(TokenType.LOGICAL_AND, start, position);
          }
          return setError(start, position, "unexpected single '&', expected '&&'");
        }
      case '|':
        {
          advance(1);
          if (consume('|')) {
            return makeToken(TokenType.LOGICAL_OR, start, position);
          }
          return setError(start, position, "unexpected single '|', expected '||'");
        }
      case '_':
        {
          return consumeIdent();
        }
      case '`':
        {
          return consumeQuotedIdent();
        }
      case '\'':
        {
          return consumeStringLiteral(start, '\'', false, false);
        }
      case '"':
        {
          return consumeStringLiteral(start, '"', false, false);
        }
      case 'r':
      case 'R':
      case 'b':
      case 'B':
        {
          Token token = consumePrefixedStringLiteral();
          if (token != null) {
            return token;
          }
          break;
        }
      default:
        break;
    }
    if (isDigit(c)) {
      return consumeNumericLiteral();
    }
    if (isAlpha(c)) {
      return consumeIdent();
    }
    advance(1);
    return setError(start, position, "unexpected character");
  }

  LexerError getError() {
    return error;
  }

  int savePosition() {
    return position;
  }

  void restorePosition(int pos) {
    this.position = pos;
    this.error = null;
  }

  private static boolean isDigit(int c) {
    return c >= '0' && c <= '9';
  }

  private static boolean isHexDigit(int c) {
    return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
  }

  private static boolean isAlpha(int c) {
    return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
  }

  private static boolean isIdentTrailing(int c) {
    return isDigit(c) || isAlpha(c) || c == '_';
  }

  private static boolean isPlusOrMinus(int c) {
    return c == '+' || c == '-';
  }

  private Token makeToken(TokenType type, int start, int end) {
    return new Token(type, start, end);
  }

  private Token setError(int start, int end, String message) {
    this.error = new LexerError(start, end, message);
    return new Token(TokenType.ERROR, start, end);
  }

  private void advance(int n) {
    position += n;
  }

  private boolean match(int c) {
    return position < content.size() && content.get(position) == c;
  }

  private boolean matchIgnoreCase(int c) {
    if (position >= content.size()) {
      return false;
    }
    int cp = content.get(position);
    return cp <= 0x7f && c <= 0x7f && Character.toLowerCase(cp) == Character.toLowerCase(c);
  }

  private boolean consume(int c) {
    if (match(c)) {
      advance(1);
      return true;
    }
    return false;
  }

  private boolean consumeIgnoreCase(int c) {
    if (matchIgnoreCase(c)) {
      advance(1);
      return true;
    }
    return false;
  }

  private boolean consumeIf(IntPredicate predicate) {
    if (position < content.size()) {
      int cp = content.get(position);
      if (predicate.test(cp)) {
        advance(1);
        return true;
      }
    }
    return false;
  }

  private void consumeLine() {
    while (position < content.size()) {
      if (content.get(position) == '\n') {
        advance(1);
        return;
      }
      advance(1);
    }
  }

  private void consumeWhitespace() {
    while (position < content.size()) {
      int c = content.get(position);
      switch (c) {
        case '\f':
        case '\n':
        case ' ':
        case '\r':
        case 11: // \v
        case '\t':
          advance(1);
          break;
        default:
          return;
      }
    }
  }

  private boolean consumeDigits() {
    boolean advanced = false;
    while (position < content.size()) {
      int c = content.get(position);
      if (!isDigit(c)) {
        break;
      }
      advance(1);
      advanced = true;
    }
    return advanced;
  }

  private boolean consumeHexDigits() {
    boolean advanced = false;
    while (position < content.size()) {
      int c = content.get(position);
      if (!isHexDigit(c)) {
        break;
      }
      advance(1);
      advanced = true;
    }
    return advanced;
  }

  private TokenType consumeIntegralSuffix() {
    if (consumeIgnoreCase('u')) {
      return TokenType.UINT;
    }
    return TokenType.INT;
  }

  private Token consumeQuotedIdent() {
    int start = position;
    advance(1);
    if (!consumeUntilAfter('`', /* isRaw= */ true)) {
      return setError(start, position, "unterminated quoted identifier");
    }
    return makeToken(TokenType.IDENT, start, position);
  }

  private boolean consumeUntilAfter(int c, boolean isRaw) {
    int pos = position;
    boolean escaped = false;
    while (pos < content.size()) {
      int cc = content.get(pos);
      if (cc == '\n' || cc == '\r') {
        position = pos;
        return false;
      }
      if (!isRaw && cc == '\\') {
        escaped = !escaped;
      } else {
        if (cc == c && (isRaw || !escaped)) {
          position = pos + 1;
          return true;
        }
        escaped = false;
      }
      pos++;
    }
    position = content.size();
    return false;
  }

  private boolean consumeUntilAfterTripleQuote(int quote, boolean isRaw) {
    int pos = position;
    boolean escaped = false;
    while (pos < content.size()) {
      int cc = content.get(pos);
      if (!isRaw && cc == '\\') {
        escaped = !escaped;
      } else {
        if ((isRaw || !escaped)
            && pos + 2 < content.size()
            && cc == quote
            && content.get(pos + 1) == quote
            && content.get(pos + 2) == quote) {
          position = pos + 3;
          return true;
        }
        escaped = false;
      }
      pos++;
    }
    position = content.size();
    return false;
  }

  private Token consumeStringLiteral(int start, int quote, boolean isBytes, boolean isRaw) {
    advance(1);
    boolean isTripleQuote =
        position + 1 < content.size()
            && content.get(position) == quote
            && content.get(position + 1) == quote;
    if (isTripleQuote) {
      advance(2);
      if (!consumeUntilAfterTripleQuote(quote, isRaw)) {
        return setError(
            start,
            position,
            isBytes ? "unterminated bytes literal" : "unterminated string literal");
      }
      return makeToken(isBytes ? TokenType.BYTES : TokenType.STRING, start, position);
    }
    if (!consumeUntilAfter(quote, isRaw)) {
      return setError(
          start, position, isBytes ? "unterminated bytes literal" : "unterminated string literal");
    }
    return makeToken(isBytes ? TokenType.BYTES : TokenType.STRING, start, position);
  }

  private @Nullable Token consumePrefixedStringLiteral() {
    int start = position;
    if (position >= content.size()) {
      return null;
    }
    int c = content.get(position);
    boolean isBytes = (c == 'b' || c == 'B');
    boolean isRaw = (c == 'r' || c == 'R');
    if (!isBytes && !isRaw) {
      return null;
    }
    int lookahead = 1;
    if (position + 1 < content.size()) {
      int c2 = content.get(position + 1);
      if ((isBytes && (c2 == 'r' || c2 == 'R')) || (!isBytes && (c2 == 'b' || c2 == 'B'))) {
        isBytes = true;
        isRaw = true;
        lookahead = 2;
      }
    }
    if (position + lookahead < content.size()) {
      int quote = content.get(position + lookahead);
      if (quote == '"' || quote == '\'') {
        advance(lookahead);
        return consumeStringLiteral(start, quote, isBytes, isRaw);
      }
    }
    return null;
  }

  private Token consumeNumericLiteral() {
    int start = position;
    int c = content.get(position);
    boolean floatingPoint = false;
    if (c == '.') {
      floatingPoint = true;
      advance(1);
      if (!consumeDigits()) {
        return setError(
            start, position, "floating point literal missing digits after decimal separator");
      }
    } else {
      advance(1);
      if (c == '0') {
        if (consumeIgnoreCase('x')) {
          if (!consumeHexDigits()) {
            return setError(
                start, position, "integral literal missing digits after hexadecimal separator");
          }
          TokenType tokenType = consumeIntegralSuffix();
          if (consumeIf(Lexer::isIdentTrailing)) {
            return setError(
                start,
                position,
                tokenType.getSymbol() + " literal has unexpected trailing characters");
          }
          return makeToken(tokenType, start, position);
        }
      }
      consumeDigits();
      if (position < content.size()
          && content.get(position) == '.'
          && position + 1 < content.size()
          && isDigit(content.get(position + 1))) {
        floatingPoint = true;
        advance(1);
        consumeDigits();
      }
    }
    if (consumeIgnoreCase('e')) {
      floatingPoint = true;
      consumeIf(Lexer::isPlusOrMinus);
      if (!consumeDigits()) {
        return setError(
            start, position, "floating point literal missing digits after exponent separator");
      }
    }
    TokenType tokenType = floatingPoint ? TokenType.FLOAT : consumeIntegralSuffix();
    if (consumeIf(Lexer::isIdentTrailing)) {
      return setError(
          start, position, tokenType.getSymbol() + " literal has unexpected trailing characters");
    }
    return makeToken(tokenType, start, position);
  }

  private Token consumeIdent() {
    int start = position;
    while (position < content.size()) {
      int c = content.get(position);
      if (!isIdentTrailing(c)) {
        break;
      }
      advance(1);
    }
    int end = position;
    String word = content.slice(start, end).toString();
    TokenType keywordType = KEYWORDS.get(word);
    if (keywordType != null) {
      return makeToken(keywordType, start, end);
    }
    return makeToken(TokenType.IDENT, start, end);
  }
}
