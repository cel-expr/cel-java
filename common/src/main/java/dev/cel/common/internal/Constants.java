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

package dev.cel.common.internal;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;

import com.google.common.primitives.UnsignedLong;
import com.google.protobuf.ByteString;
import dev.cel.common.annotations.Internal;
import dev.cel.common.ast.CelConstant;
import dev.cel.common.values.CelByteString;
import dev.cel.common.values.NullValue;
import java.text.ParseException;
import java.util.PrimitiveIterator;

/**
 * Internal utility class for working with {@link dev.cel.expr.Constant}.
 *
 * <p>CEL Library Internals. Do Not Use.
 */
@Internal
public final class Constants {

  private static final String DOUBLE_QUOTE = "\"";
  private static final String SINGLE_QUOTE = "'";
  private static final String TRIPLE_DOUBLE_QUOTE = "\"\"\"";
  private static final String TRIPLE_SINGLE_QUOTE = "'''";
  private static final int MAX_SCRATCH_CODE_POINTS = 8;
  private static final int MIN_CODE_POINT = 0;
  private static final int MAX_CODE_POINT = 0x10ffff;
  private static final int MIN_SURROGATE = 0xd800;
  private static final int MAX_SURROGATE = 0xdfff;

  public static final CelConstant NULL = CelConstant.ofValue(NullValue.NULL_VALUE);
  public static final CelConstant FALSE = CelConstant.ofValue(false);
  public static final CelConstant TRUE = CelConstant.ofValue(true);
  public static final CelConstant ERROR = CelConstant.ofValue("<<error>>");

  public static CelConstant parseInt(String text) throws ParseException {
    int base;
    if (text.startsWith("-0x") || text.startsWith("-0X")) {
      base = 16;
      // Strip off the sign and prefix.
      text = text.substring(3);
      // Add the sign back.
      text = "-" + text;
    } else if (text.startsWith("0x") || text.startsWith("0X")) {
      base = 16;
      text = text.substring(2);
      if (text.startsWith("-")) {
        // While the lexer/parser should never present such a literal, this is here for safety as
        // Long.parseLong would accept it.
        throw new ParseException("Integer literal is malformed", 0);
      }
    } else {
      base = 10;
    }
    long value;
    try {
      value = Long.parseLong(text, base);
    } catch (NumberFormatException e) {
      throw new ParseException(e.getMessage(), 0);
    }
    return CelConstant.ofValue(value);
  }

  public static CelConstant parseUint(String text) throws ParseException {
    int base;
    if (!text.endsWith("u") && !text.endsWith("U")) {
      throw new ParseException("Unsigned integer literal is missing trailing 'u' suffix", 0);
    }
    text = text.substring(0, text.length() - 1);
    if (text.startsWith("0x") || text.startsWith("0X")) {
      base = 16;
      text = text.substring(2);
    } else {
      base = 10;
    }
    long value;
    try {
      value = Long.parseUnsignedLong(text, base);
    } catch (NumberFormatException e) {
      throw new ParseException(e.getMessage(), 0);
    }
    return CelConstant.ofValue(UnsignedLong.fromLongBits(value));
  }

  public static CelConstant parseDouble(String text) throws ParseException {
    double value;
    try {
      value = Double.parseDouble(text);
    } catch (NumberFormatException e) {
      throw new ParseException(e.getMessage(), 0);
    }
    return CelConstant.ofValue(value);
  }

  public static CelConstant parseBytes(String text) throws ParseException {
    boolean isRawLiteral = false;
    int offset = 0;
    if (text.startsWith("r") || text.startsWith("R")) {
      isRawLiteral = true;
      text = text.substring(1);
      offset++;
      if (!text.startsWith("b") && !text.startsWith("B")) {
        throw new ParseException("Bytes literal is missing leading 'b' or 'B' prefix", 0);
      }
      text = text.substring(1);
      offset++;
    } else {
      if (!text.startsWith("b") && !text.startsWith("B")) {
        throw new ParseException("Bytes literal is missing leading 'b' or 'B' prefix", 0);
      }
      text = text.substring(1);
      offset++;
      if (text.startsWith("r") || text.startsWith("R")) {
        isRawLiteral = true;
        text = text.substring(1);
        offset++;
      }
    }
    String quote;
    if (text.startsWith(TRIPLE_DOUBLE_QUOTE)) {
      quote = TRIPLE_DOUBLE_QUOTE;
      text = text.substring(quote.length());
    } else if (text.startsWith(TRIPLE_SINGLE_QUOTE)) {
      quote = TRIPLE_SINGLE_QUOTE;
      text = text.substring(quote.length());
    } else if (text.startsWith(DOUBLE_QUOTE)) {
      quote = DOUBLE_QUOTE;
      text = text.substring(quote.length());
    } else if (text.startsWith(SINGLE_QUOTE)) {
      quote = SINGLE_QUOTE;
      text = text.substring(quote.length());
    } else {
      throw new ParseException("Bytes literal is missing surrounding single or double quotes", 0);
    }
    checkForClosingQuote(text, quote);
    offset += quote.length();
    checkState(text.endsWith(quote));
    text = text.substring(0, text.length() - quote.length());
    DecodeBuffer<ByteString> buffer = new DecodeByteStringBuffer(text.length());
    decodeString(offset, text, buffer, isRawLiteral, true);
    return CelConstant.ofValue(CelByteString.of(buffer.toDecodedValue().toByteArray()));
  }

  public static CelConstant parseString(String text) throws ParseException {
    int offset = 0;
    boolean isRawLiteral = false;
    if (text.startsWith("r") || text.startsWith("R")) {
      isRawLiteral = true;
      text = text.substring(1);
      offset++;
    }
    String quote;
    if (text.startsWith(TRIPLE_DOUBLE_QUOTE)) {
      quote = TRIPLE_DOUBLE_QUOTE;
      text = text.substring(quote.length());
    } else if (text.startsWith(TRIPLE_SINGLE_QUOTE)) {
      quote = TRIPLE_SINGLE_QUOTE;
      text = text.substring(quote.length());
    } else if (text.startsWith(DOUBLE_QUOTE)) {
      quote = DOUBLE_QUOTE;
      text = text.substring(quote.length());
    } else if (text.startsWith(SINGLE_QUOTE)) {
      quote = SINGLE_QUOTE;
      text = text.substring(quote.length());
    } else {
      throw new ParseException("String literal is missing surrounding single or double quotes", 0);
    }
    checkForClosingQuote(text, quote);
    offset += quote.length();
    checkState(text.endsWith(quote));
    text = text.substring(0, text.length() - quote.length());
    DecodeBuffer<String> buffer = new DecodeStringBuffer(text.length());
    decodeString(offset, text, buffer, isRawLiteral, false);
    return CelConstant.ofValue(buffer.toDecodedValue());
  }

  private static <T> void decodeString(
      int offset, String text, DecodeBuffer<T> buffer, boolean isRawLiteral, boolean isBytesLiteral)
      throws ParseException {
    boolean skipNewline = false;
    PrimitiveIterator.OfInt iterator = text.codePoints().iterator();
    int[] scratchCodePoints = null;
    while (iterator.hasNext()) {
      int seqOffset = offset; // Save offset for the start of this sequence.
      int codePoint = iterator.nextInt();
      offset++;
      if (codePoint != '\\') {
        if (codePoint != '\r') {
          if (codePoint == '\n' && skipNewline) {
            skipNewline = false;
            continue;
          }
          skipNewline = false;
          if (codePoint >= MIN_SURROGATE && codePoint <= MAX_SURROGATE) {
            throw new ParseException("Invalid unicode code point", seqOffset);
          }
          buffer.appendCodePoint(codePoint);
        } else {
          // Normalize '\r' and '\r\n' to '\n'.
          buffer.appendCodePoint('\n');
          skipNewline = true;
        }
      } else {
        // An escape sequence.
        skipNewline = false;
        if (!iterator.hasNext()) {
          throw new ParseException(
              isRawLiteral
                  ? "Raw literals cannot end with an odd number of \\"
                  : isBytesLiteral
                      ? "Bytes literal cannot end with \\"
                      : "String literal cannot end with \\",
              seqOffset);
        }
        codePoint = iterator.nextInt();
        offset++;
        if (isRawLiteral) {
          // For raw literals, all escapes are valid and those characters come through literally in
          // the string.
          buffer.appendCodePoint('\\');
          if (codePoint >= MIN_SURROGATE && codePoint <= MAX_SURROGATE) {
            throw new ParseException("Invalid unicode code point", seqOffset);
          }
          buffer.appendCodePoint(codePoint);
          continue;
        }
        switch (codePoint) {
          case 'a':
            buffer.appendByte((byte) 7);
            break;
          case 'b':
            buffer.appendByte((byte) '\b');
            break;
          case 'f':
            buffer.appendByte((byte) '\f');
            break;
          case 'n':
            buffer.appendByte((byte) '\n');
            break;
          case 'r':
            buffer.appendByte((byte) '\r');
            break;
          case 't':
            buffer.appendByte((byte) '\t');
            break;
          case 'v':
            buffer.appendByte((byte) 11);
            break;
          case '"':
            buffer.appendByte((byte) '"');
            break;
          case '\'':
            buffer.appendByte((byte) '\'');
            break;
          case '\\':
            buffer.appendByte((byte) '\\');
            break;
          case '?':
            buffer.appendByte((byte) '?');
            break;
          case '`':
            buffer.appendByte((byte) '`');
            break;
          case '0':
          case '1':
          case '2':
          case '3':
            {
              if (scratchCodePoints == null) {
                scratchCodePoints = new int[MAX_SCRATCH_CODE_POINTS];
              }
              // There needs to be 2 octal digits.
              if (!nextInts(iterator, 2, scratchCodePoints)
                  || !areOctalDigits(scratchCodePoints, 2)) {
                throw new ParseException("Invalid octal escape sequence", seqOffset);
              }
              int octalValue = codePoint - '0';
              octalValue = (octalValue * 8) + (scratchCodePoints[0] - '0');
              octalValue = (octalValue * 8) + (scratchCodePoints[1] - '0');
              buffer.appendByte((byte) octalValue);
              offset += 2;
            }
            break;
          case 'x':
          case 'X':
            {
              if (scratchCodePoints == null) {
                scratchCodePoints = new int[MAX_SCRATCH_CODE_POINTS];
              }
              // There needs to be 2 hex digits.
              if (!nextInts(iterator, 2, scratchCodePoints)
                  || !areHexDigits(scratchCodePoints, 2)) {
                throw new ParseException("Invalid hex escape sequence", seqOffset);
              }
              int value = unhex(scratchCodePoints, 2);
              buffer.appendByte((byte) value);
              offset += 2;
            }
            break;
          case 'u':
            {
              if (isBytesLiteral) {
                throw new ParseException(
                    "Illegal escape sequence: Unicode escape sequences cannot be used in bytes"
                        + " literal",
                    seqOffset);
              }
              if (scratchCodePoints == null) {
                scratchCodePoints = new int[MAX_SCRATCH_CODE_POINTS];
              }
              // There needs to be 4 hex digits.
              if (!nextInts(iterator, 4, scratchCodePoints)
                  || !areHexDigits(scratchCodePoints, 4)) {
                throw new ParseException("Invalid unicode escape sequence", seqOffset);
              }
              int value = unhex(scratchCodePoints, 4);
              if (value < MIN_CODE_POINT
                  || value > MAX_CODE_POINT
                  || (value >= MIN_SURROGATE && value <= MAX_SURROGATE)) {
                throw new ParseException("Invalid unicode code point", seqOffset);
              }
              buffer.appendCodePoint(value);
              offset += 4;
            }
            break;
          case 'U':
            {
              if (isBytesLiteral) {
                throw new ParseException(
                    "Illegal escape sequence: Unicode escape sequences cannot be used in bytes"
                        + " literal",
                    offset);
              }
              if (scratchCodePoints == null) {
                scratchCodePoints = new int[MAX_SCRATCH_CODE_POINTS];
              }
              // There needs to be 8 hex digits.
              if (!nextInts(iterator, 8, scratchCodePoints)
                  || !areHexDigits(scratchCodePoints, 8)) {
                throw new ParseException("Invalid unicode escape sequence", seqOffset);
              }
              int value = unhex(scratchCodePoints, 8);
              if (value < MIN_CODE_POINT
                  || value > MAX_CODE_POINT
                  || (value >= MIN_SURROGATE && value <= MAX_SURROGATE)) {
                throw new ParseException("Invalid unicode code point", seqOffset);
              }
              buffer.appendCodePoint(value);
              offset += 8;
            }
            break;
          default:
            throw new ParseException("Illegal escape sequence", seqOffset);
        }
      }
    }
  }

  private static boolean nextInts(PrimitiveIterator.OfInt iterator, int count, int[] scratch) {
    checkArgument(count <= scratch.length);
    int index = 0;
    while (iterator.hasNext() && index < count) {
      scratch[index++] = iterator.nextInt();
    }
    return index == count;
  }

  private static boolean isOctalDigit(int codePoint) {
    return codePoint >= '0' && codePoint <= '7';
  }

  private static boolean isHexDigit(int codePoint) {
    return (codePoint >= 'a' && codePoint <= 'f')
        || (codePoint >= 'A' && codePoint <= 'F')
        || (codePoint >= '0' && codePoint <= '9');
  }

  private static boolean isDigit(int codePoint) {
    return codePoint >= '0' && codePoint <= '9';
  }

  private static int toLowerCase(int codePoint) {
    return codePoint >= 'A' && codePoint <= 'Z' ? (codePoint - 'A') + 'a' : codePoint;
  }

  private static boolean areOctalDigits(int[] codePoints, int count) {
    checkArgument(count <= codePoints.length);
    for (int index = 0; index < count; index++) {
      if (!isOctalDigit(codePoints[index])) {
        return false;
      }
    }
    return true;
  }

  private static boolean areHexDigits(int[] codePoints, int count) {
    checkArgument(count <= codePoints.length);
    for (int index = 0; index < count; index++) {
      if (!isHexDigit(codePoints[index])) {
        return false;
      }
    }
    return true;
  }

  private interface DecodeBuffer<T> {
    void appendByte(byte b);

    void appendCodePoint(int codePoint);

    T toDecodedValue();
  }

  private static final class DecodeByteStringBuffer implements DecodeBuffer<ByteString> {

    private final ByteString.Output output;

    private DecodeByteStringBuffer(int initialCapacity) {
      output = ByteString.newOutput(initialCapacity);
    }

    @Override
    public void appendByte(byte b) {
      output.write(b);
    }

    @Override
    public void appendCodePoint(int codePoint) {
      checkArgument(codePoint >= MIN_CODE_POINT && codePoint <= MAX_CODE_POINT);
      if (codePoint < 0x80) {
        output.write((byte) codePoint);
      } else if (codePoint < 0x800) {
        output.write((byte) ((0xF << 6) | (codePoint >>> 6)));
        output.write((byte) (0x80 | (0x3F & codePoint)));
      } else if (codePoint < 0x10000) {
        output.write((byte) ((0xF << 5) | (codePoint >>> 12)));
        output.write((byte) (0x80 | (0x3F & (codePoint >>> 6))));
        output.write((byte) (0x80 | (0x3F & codePoint)));
      } else {
        output.write((byte) ((0xF << 4) | (codePoint >>> 18)));
        output.write((byte) (0x80 | (0x3F & (codePoint >>> 12))));
        output.write((byte) (0x80 | (0x3F & (codePoint >>> 6))));
        output.write((byte) (0x80 | (0x3F & codePoint)));
      }
    }

    @Override
    public ByteString toDecodedValue() {
      return output.toByteString();
    }
  }

  private static final class DecodeStringBuffer implements DecodeBuffer<String> {

    private final StringBuilder builder;

    private DecodeStringBuffer(int initialCapacity) {
      builder = new StringBuilder(initialCapacity);
    }

    @Override
    public void appendByte(byte b) {
      builder.appendCodePoint(b & 0xff);
    }

    @Override
    public void appendCodePoint(int codePoint) {
      checkArgument(codePoint >= MIN_CODE_POINT && codePoint <= MAX_CODE_POINT);
      builder.appendCodePoint(codePoint);
    }

    @Override
    public String toDecodedValue() {
      return builder.toString();
    }
  }

  private static void checkForClosingQuote(String text, String quote) throws ParseException {
    if (quote.isEmpty()) {
      return;
    }
    if (text.length() < quote.length()) {
      throw new ParseException(
          String.format("String literal missing terminating quote %s", quote), 0);
    }
    int position = 0;
    boolean isClosed = false;
    while (position + quote.length() <= text.length()) {
      char codeUnit = text.charAt(position);
      if (codeUnit != '\\') {
        boolean quoteMatches = true;
        for (int i = 0; i < quote.length(); i++) {
          if (text.charAt(position + i) != quote.charAt(i)) {
            quoteMatches = false;
            break;
          }
        }

        if (quoteMatches) {
          isClosed = position + quote.length() == text.length();
          break;
        }
      } else {
        position++;
      }
      position++;
    }
    if (!isClosed) {
      throw new ParseException(
          String.format("String literal contains unescaped terminating quote %s", quote), 0);
    }
  }

  private static int unhex(int value, int nextValue) {
    if (isDigit(nextValue)) {
      return (value * 16) + (nextValue - '0');
    } else {
      return (value * 16) + ((toLowerCase(nextValue) - 'a') + 10);
    }
  }

  private static int unhex(int[] codePoints, int length) {
    int value = 0;
    for (int index = 0; index < length; index++) {
      value = unhex(value, codePoints[index]);
    }
    return value;
  }

  private Constants() {}
}
