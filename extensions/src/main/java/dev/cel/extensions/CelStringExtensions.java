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

package dev.cel.extensions;

import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static java.lang.Math.max;
import static java.lang.Math.min;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.base.Ascii;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.io.BaseEncoding;
import com.google.common.primitives.UnsignedLong;
import com.google.errorprone.annotations.Immutable;
import dev.cel.checker.CelCheckerBuilder;
import dev.cel.common.CelFunctionDecl;
import dev.cel.common.CelOverloadDecl;
import dev.cel.common.exceptions.CelBadFormatException;
import dev.cel.common.exceptions.CelInvalidArgumentException;
import dev.cel.common.internal.CelCodePointArray;
import dev.cel.common.internal.DateTimeHelpers;
import dev.cel.common.types.CelType;
import dev.cel.common.types.ListType;
import dev.cel.common.types.SimpleType;
import dev.cel.common.types.TypeType;
import dev.cel.common.values.CelByteString;
import dev.cel.common.values.NullValue;
import dev.cel.compiler.CelCompilerLibrary;
import dev.cel.runtime.CelEvaluationException;
import dev.cel.runtime.CelEvaluationExceptionBuilder;
import dev.cel.runtime.CelFunctionBinding;
import dev.cel.runtime.CelRuntimeBuilder;
import dev.cel.runtime.CelRuntimeLibrary;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Internal implementation of CEL string extensions. */
@Immutable
public final class CelStringExtensions
    implements CelCompilerLibrary, CelRuntimeLibrary, CelExtensionLibrary.FeatureSet {

  // ROOT is equivalent to US locale. Formatting should be machine-oriented, not UI-oriented.
  private static final Locale LOCALE_ROOT = Locale.ROOT;

  // TODO: Make max precision limit configurable via CelStringExtensions options.
  private static final int MAX_PRECISION = 100;

  // Constants for Long.MIN_VALUE because negating it to find its absolute value overflows in signed
  // 64-bit arithmetic.
  private static final String MIN_LONG_BINARY =
      "-100000000000000000000000000000000000000000000000000000000000000";
  private static final String MIN_LONG_HEX = "-8000000000000000";
  private static final String MIN_LONG_OCTAL = "-1000000000000000000000";

  private static final BaseEncoding BASE16_LOWER = BaseEncoding.base16().lowerCase();

  /** Denotes the string extension function */
  @SuppressWarnings({"unchecked"}) // Unchecked: Type-checker guarantees casting safety.
  public enum Function {
    CHAR_AT(
        CelFunctionDecl.newFunctionDeclaration(
            "charAt",
            CelOverloadDecl.newMemberOverload(
                "string_char_at_int",
                "Returns the character at the given position. If the position is negative, or"
                    + " greater than the length of the string, the function will produce an error.",
                SimpleType.STRING,
                ImmutableList.of(SimpleType.STRING, SimpleType.INT))),
        CelFunctionBinding.from(
            "string_char_at_int", String.class, Long.class, CelStringExtensions::charAt)),
    FORMAT(
        CelFunctionDecl.newFunctionDeclaration(
            "format",
            CelOverloadDecl.newMemberOverload(
                "string_format",
                "Formats the string using the provided arguments.",
                SimpleType.STRING,
                ImmutableList.of(SimpleType.STRING, ListType.create(SimpleType.DYN)))),
        CelFunctionBinding.from(
            "string_format", String.class, List.class, CelStringExtensions::format)),
    INDEX_OF(
        CelFunctionDecl.newFunctionDeclaration(
            "indexOf",
            CelOverloadDecl.newMemberOverload(
                "string_index_of_string",
                "Returns the integer index of the first occurrence of the search string. If the"
                    + " search string is not found the function returns -1.",
                SimpleType.INT,
                ImmutableList.of(SimpleType.STRING, SimpleType.STRING)),
            CelOverloadDecl.newMemberOverload(
                "string_index_of_string_int",
                "Returns the integer index of the first occurrence of the search string from the"
                    + " given offset. If the search string is not found the function returns"
                    + " -1. If the substring is the empty string, the index where the search starts"
                    + " is returned (zero or custom).",
                SimpleType.INT,
                ImmutableList.of(SimpleType.STRING, SimpleType.STRING, SimpleType.INT))),
        CelFunctionBinding.from(
            "string_index_of_string", String.class, String.class, CelStringExtensions::indexOf),
        CelFunctionBinding.from(
            "string_index_of_string_int",
            ImmutableList.of(String.class, String.class, Long.class),
            CelStringExtensions::indexOf)),
    JOIN(
        CelFunctionDecl.newFunctionDeclaration(
            "join",
            CelOverloadDecl.newMemberOverload(
                "list_join",
                "Returns a new string where the elements of string list are concatenated.",
                SimpleType.STRING,
                ListType.create(SimpleType.STRING)),
            CelOverloadDecl.newMemberOverload(
                "list_join_string",
                "Returns a new string where the elements of string list are concatenated using the"
                    + " separator.",
                SimpleType.STRING,
                ImmutableList.of(ListType.create(SimpleType.STRING), SimpleType.STRING))),
        CelFunctionBinding.from("list_join", List.class, CelStringExtensions::join),
        CelFunctionBinding.from(
            "list_join_string", List.class, String.class, CelStringExtensions::join)),
    LAST_INDEX_OF(
        CelFunctionDecl.newFunctionDeclaration(
            "lastIndexOf",
            CelOverloadDecl.newMemberOverload(
                "string_last_index_of_string",
                "Returns the integer index of the last occurrence of the search string. If the"
                    + " search string is not found the function returns -1.",
                SimpleType.INT,
                ImmutableList.of(SimpleType.STRING, SimpleType.STRING)),
            CelOverloadDecl.newMemberOverload(
                "string_last_index_of_string_int",
                "Returns the integer index of the last occurrence of the search string from the"
                    + " given offset. If the search string is not found the function returns -1. If"
                    + " the substring is the empty string, the index where the search starts is"
                    + " returned (string length or custom).",
                SimpleType.INT,
                ImmutableList.of(SimpleType.STRING, SimpleType.STRING, SimpleType.INT))),
        CelFunctionBinding.from(
            "string_last_index_of_string",
            String.class,
            String.class,
            CelStringExtensions::lastIndexOf),
        CelFunctionBinding.from(
            "string_last_index_of_string_int",
            ImmutableList.of(String.class, String.class, Long.class),
            CelStringExtensions::lastIndexOf)),
    LOWER_ASCII(
        CelFunctionDecl.newFunctionDeclaration(
            "lowerAscii",
            CelOverloadDecl.newMemberOverload(
                "string_lower_ascii",
                "Returns a new string where all ASCII characters are lower-cased. This function"
                    + " does not perform Unicode case-mapping for characters outside the ASCII"
                    + " range.",
                SimpleType.STRING,
                SimpleType.STRING)),
        CelFunctionBinding.from("string_lower_ascii", String.class, Ascii::toLowerCase)),
    QUOTE(
        CelFunctionDecl.newFunctionDeclaration(
            "strings.quote",
            CelOverloadDecl.newGlobalOverload(
                "strings_quote",
                "Takes the given string and makes it safe to print (without any formatting"
                    + " due to escape sequences). If any invalid UTF-8 characters are"
                    + " encountered, they are replaced with \\uFFFD.",
                SimpleType.STRING,
                ImmutableList.of(SimpleType.STRING))),
        CelFunctionBinding.from("strings_quote", String.class, CelStringExtensions::quote)),
    REPLACE(
        CelFunctionDecl.newFunctionDeclaration(
            "replace",
            CelOverloadDecl.newMemberOverload(
                "string_replace_string_string",
                "Returns a new string based on the target, which replaces the occurrences of a"
                    + " search string with a replacement string if present.",
                SimpleType.STRING,
                ImmutableList.of(SimpleType.STRING, SimpleType.STRING, SimpleType.STRING)),
            CelOverloadDecl.newMemberOverload(
                "string_replace_string_string_int",
                "Returns a new string based on the target, which replaces the occurrences of a"
                    + " search string with a replacement string if present. The function accepts a"
                    + " limit on the number of substring replacements to be made. When the"
                    + " replacement limit is 0, the result is the original string. When the limit"
                    + " is a negative number, the function behaves the same as replace all.",
                SimpleType.STRING,
                ImmutableList.of(
                    SimpleType.STRING, SimpleType.STRING, SimpleType.STRING, SimpleType.INT))),
        CelFunctionBinding.from(
            "string_replace_string_string",
            ImmutableList.of(String.class, String.class, String.class),
            CelStringExtensions::replaceAll),
        CelFunctionBinding.from(
            "string_replace_string_string_int",
            ImmutableList.of(String.class, String.class, String.class, Long.class),
            CelStringExtensions::replace)),
    REVERSE(
        CelFunctionDecl.newFunctionDeclaration(
            "reverse",
            CelOverloadDecl.newMemberOverload(
                "string_reverse",
                "Returns a new string whose characters are the same as the target string,"
                    + " only formatted in reverse order.",
                SimpleType.STRING,
                SimpleType.STRING)),
        CelFunctionBinding.from("string_reverse", String.class, CelStringExtensions::reverse)),
    SPLIT(
        CelFunctionDecl.newFunctionDeclaration(
            "split",
            CelOverloadDecl.newMemberOverload(
                "string_split_string",
                "Returns a mutable list of strings split from the input by the given separator.",
                ListType.create(SimpleType.STRING),
                ImmutableList.of(SimpleType.STRING, SimpleType.STRING)),
            CelOverloadDecl.newMemberOverload(
                "string_split_string_int",
                "Returns a mutable list of strings split from the input by the given separator with"
                    + " the specified limit on the number of substrings produced by the split.",
                ListType.create(SimpleType.STRING),
                ImmutableList.of(SimpleType.STRING, SimpleType.STRING, SimpleType.INT))),
        CelFunctionBinding.from(
            "string_split_string", String.class, String.class, CelStringExtensions::split),
        CelFunctionBinding.from(
            "string_split_string_int",
            ImmutableList.of(String.class, String.class, Long.class),
            CelStringExtensions::split)),
    SUBSTRING(
        CelFunctionDecl.newFunctionDeclaration(
            "substring",
            CelOverloadDecl.newMemberOverload(
                "string_substring_int",
                "returns a string that is a substring of this string. The substring begins with the"
                    + " character at the specified index and extends to the end of this string.",
                SimpleType.STRING,
                ImmutableList.of(SimpleType.STRING, SimpleType.INT)),
            CelOverloadDecl.newMemberOverload(
                "string_substring_int_int",
                "returns a string that is a substring of this string. The substring begins at the"
                    + " specified beginIndex and extends to the character at index endIndex - 1."
                    + " Thus the length of the substring is {@code endIndex-beginIndex}.",
                SimpleType.STRING,
                ImmutableList.of(SimpleType.STRING, SimpleType.INT, SimpleType.INT))),
        CelFunctionBinding.from(
            "string_substring_int", String.class, Long.class, CelStringExtensions::substring),
        CelFunctionBinding.from(
            "string_substring_int_int",
            ImmutableList.of(String.class, Long.class, Long.class),
            CelStringExtensions::substring)),
    TRIM(
        CelFunctionDecl.newFunctionDeclaration(
            "trim",
            CelOverloadDecl.newMemberOverload(
                "string_trim",
                "Returns a new string which removes the leading and trailing whitespace in the"
                    + " target string. The trim function uses the Unicode definition of whitespace"
                    + " which does not include the zero-width spaces. ",
                SimpleType.STRING,
                SimpleType.STRING)),
        CelFunctionBinding.from("string_trim", String.class, CelStringExtensions::trim)),
    UPPER_ASCII(
        CelFunctionDecl.newFunctionDeclaration(
            "upperAscii",
            CelOverloadDecl.newMemberOverload(
                "string_upper_ascii",
                "Returns a new string where all ASCII characters are upper-cased. This function"
                    + " does not perform Unicode case-mapping for characters outside the ASCII"
                    + " range.",
                SimpleType.STRING,
                SimpleType.STRING)),
        CelFunctionBinding.from("string_upper_ascii", String.class, Ascii::toUpperCase));

    private final CelFunctionDecl functionDecl;
    private final ImmutableSet<CelFunctionBinding> functionBindings;

    String getFunction() {
      return functionDecl.name();
    }

    Function(CelFunctionDecl functionDecl, CelFunctionBinding... functionBindings) {
      this.functionDecl = functionDecl;
      this.functionBindings =
          CelFunctionBinding.fromOverloads(functionDecl.name(), functionBindings);
    }
  }

  private final ImmutableSet<Function> functions;

  CelStringExtensions() {
    this(ImmutableSet.copyOf(Function.values()));
  }

  CelStringExtensions(Set<Function> functions) {
    this.functions = ImmutableSet.copyOf(functions);
  }

  private static final CelExtensionLibrary<CelStringExtensions> LIBRARY =
      new CelExtensionLibrary<CelStringExtensions>() {
        private final CelStringExtensions version0 = new CelStringExtensions();

        @Override
        public String name() {
          return "strings";
        }

        @Override
        public ImmutableSet<CelStringExtensions> versions() {
          return ImmutableSet.of(version0);
        }
      };

  static CelExtensionLibrary<CelStringExtensions> library() {
    return LIBRARY;
  }

  @Override
  public int version() {
    return 0;
  }

  @Override
  public ImmutableSet<CelFunctionDecl> functions() {
    return functions.stream().map(f -> f.functionDecl).collect(toImmutableSet());
  }

  @Override
  public void setCheckerOptions(CelCheckerBuilder checkerBuilder) {
    functions.forEach(function -> checkerBuilder.addFunctionDeclarations(function.functionDecl));
  }

  @Override
  public void setRuntimeOptions(CelRuntimeBuilder runtimeBuilder) {
    functions.forEach(function -> runtimeBuilder.addFunctionBindings(function.functionBindings));
  }

  private static String charAt(String s, long i) throws CelEvaluationException {
    int index;
    try {
      index = Math.toIntExact(i);
    } catch (ArithmeticException e) {
      throw CelEvaluationExceptionBuilder.newBuilder(
              "charAt failure: Index must not exceed the int32 range: %d", i)
          .setCause(e)
          .build();
    }

    CelCodePointArray codePointArray = CelCodePointArray.fromString(s);
    if (index == codePointArray.length()) {
      return "";
    }
    if (index < 0 || index > codePointArray.length()) {
      throw CelEvaluationExceptionBuilder.newBuilder(
              "charAt failure: Index out of range: %d", index)
          .build();
    }

    return codePointArray.slice(index, index + 1).toString();
  }

  private static Long indexOf(String str, String substr) throws CelEvaluationException {
    Object[] params = {str, substr, 0L};
    return indexOf(params);
  }

  /**
   * @param args Object array with indices of: [0: string], [1: substring], [2: offset]
   */
  private static Long indexOf(Object[] args) throws CelEvaluationException {
    String str = (String) args[0];
    String substr = (String) args[1];
    long offsetInLong = (Long) args[2];
    int offset;
    try {
      offset = Math.toIntExact(offsetInLong);
    } catch (ArithmeticException e) {
      throw CelEvaluationExceptionBuilder.newBuilder(
              "indexOf failure: Offset must not exceed the int32 range: %d", offsetInLong)
          .setCause(e)
          .build();
    }

    return indexOf(str, substr, offset);
  }

  private static Long indexOf(String str, String substr, int offset) throws CelEvaluationException {
    if (substr.isEmpty()) {
      return (long) offset;
    }

    CelCodePointArray strCpa = CelCodePointArray.fromString(str);
    CelCodePointArray substrCpa = CelCodePointArray.fromString(substr);

    if (offset < 0 || offset >= strCpa.length()) {
      throw CelEvaluationExceptionBuilder.newBuilder(
              "indexOf failure: Offset out of range: %d", offset)
          .build();
    }

    return safeIndexOf(strCpa, substrCpa, offset);
  }

  /** Retrieves the index of the substring in a given string without throwing. */
  private static Long safeIndexOf(CelCodePointArray str, CelCodePointArray substr, int offset) {
    for (int i = offset; i < str.length() - (substr.length() - 1); i++) {
      int j;
      for (j = 0; j < substr.length(); j++) {
        if (str.get(i + j) != substr.get(j)) {
          break;
        }
      }

      if (j == substr.length()) {
        return (long) i;
      }
    }

    // Offset is out of bound.
    return -1L;
  }

  private static String join(List<String> stringList) {
    return join(stringList, "");
  }

  private static String join(List<String> stringList, String separator) {
    return Joiner.on(separator).join(stringList);
  }

  private static String format(String formatSpecifier, List<?> args) {
    StringBuilder builtStr = new StringBuilder(formatSpecifier.length());
    int i = 0;
    int argIndex = 0;
    while (i < formatSpecifier.length()) {
      if (formatSpecifier.charAt(i) != '%') {
        builtStr.append(formatSpecifier.charAt(i++));
        continue;
      }

      if (i + 1 < formatSpecifier.length() && formatSpecifier.charAt(i + 1) == '%') {
        builtStr.append('%');
        i += 2;
        continue;
      }

      if (argIndex >= args.size()) {
        throw new CelBadFormatException("index " + argIndex + " out of range");
      }

      Object arg = args.get(argIndex++);
      i = parseAndFormatClause(formatSpecifier, i + 1, arg, builtStr);
    }
    return builtStr.toString();
  }

  /**
   * Parses and formats a single format clause after '%', starting at index {@code offset}. Returns
   * the new index in {@code formatSpecifier} after consuming the clause.
   */
  private static int parseAndFormatClause(
      String formatSpecifier, int offset, Object arg, StringBuilder builtStr) {
    int i = offset;
    int precision = -1;
    if (i < formatSpecifier.length() && formatSpecifier.charAt(i) == '.') {
      i++;
      int start = i;
      while (i < formatSpecifier.length() && Character.isDigit(formatSpecifier.charAt(i))) {
        i++;
      }
      if (i >= formatSpecifier.length()) {
        throw new CelBadFormatException("unexpected end of string");
      }
      if (i == start) {
        throw new CelBadFormatException("empty precision is not allowed");
      } else {
        try {
          precision = Integer.parseInt(formatSpecifier.substring(start, i));
        } catch (NumberFormatException e) {
          throw new CelBadFormatException(
              "invalid precision format: " + formatSpecifier.substring(start, i));
        }
        // TODO: Make max precision limit configurable via CelStringExtensions options.
        if (precision > MAX_PRECISION) {
          throw new CelInvalidArgumentException(
              "precision " + precision + " exceeds maximum allowed (" + MAX_PRECISION + ")");
        }
      }
    }
    if (i >= formatSpecifier.length()) {
      throw new CelBadFormatException("unexpected end of string");
    }
    char verb = formatSpecifier.charAt(i++);
    switch (verb) {
      case 's':
        builtStr.append(formatString(arg));
        break;
      case 'd':
        builtStr.append(formatDecimal(arg));
        break;
      case 'f':
        builtStr.append(formatFixed(arg, precision));
        break;
      case 'e':
        builtStr.append(formatScientific(arg, precision));
        break;
      case 'b':
        builtStr.append(formatBinary(arg));
        break;
      case 'x':
      case 'X':
        builtStr.append(formatHex(arg, verb == 'X'));
        break;
      case 'o':
        builtStr.append(formatOctal(arg));
        break;
      default:
        throw new CelBadFormatException("unrecognized formatting clause \"" + verb + "\"");
    }
    return i;
  }

  private static String formatString(Object val) {
    Preconditions.checkNotNull(val);
    if (val instanceof String) {
      return (String) val;
    }
    if (val instanceof CelByteString) {
      return formatByteString((CelByteString) val);
    }
    if (val instanceof Duration) {
      return DateTimeHelpers.toString((Duration) val);
    }
    if (val instanceof Double) {
      return formatDouble((Double) val);
    }
    if (val instanceof Instant
        || val instanceof Boolean
        || val instanceof Long
        || val instanceof UnsignedLong) {
      return val.toString();
    }
    if (val instanceof List) {
      return formatList((List<?>) val);
    }
    if (val instanceof Map) {
      return formatMap((Map<?, ?>) val);
    }
    if (val instanceof NullValue) {
      return "null";
    }
    if (val instanceof TypeType) {
      return ((TypeType) val).containingTypeName();
    }
    if (val instanceof CelType) {
      return ((CelType) val).name();
    }
    throw new CelInvalidArgumentException(
        "could not convert argument " + val.getClass().getName() + " to string");
  }

  private static String formatByteString(CelByteString byteString) {
    if (byteString.isValidUtf8()) {
      return byteString.toStringUtf8();
    }
    return decodeUtf8Lossy(byteString.toByteArray());
  }

  private static String decodeUtf8Lossy(byte[] bytes) {
    if (bytes.length == 0) {
      return "";
    }
    StringBuilder sb = new StringBuilder(bytes.length);
    boolean inInvalidSequence = false;
    int i = 0;
    int len = bytes.length;

    while (i < len) {
      int b0 = bytes[i] & 0xFF;
      if (b0 < 0x80) {
        // 1-byte ASCII (0x00 - 0x7F)
        if (inInvalidSequence) {
          sb.append('\uFFFD');
          inInvalidSequence = false;
        }
        sb.append((char) b0);
        i++;
      } else if (b0 >= 0xC2 && b0 <= 0xDF) {
        // 2-byte sequence
        if (i + 1 < len) {
          int b1 = bytes[i + 1] & 0xFF;
          if (b1 >= 0x80 && b1 <= 0xBF) {
            if (inInvalidSequence) {
              sb.append('\uFFFD');
              inInvalidSequence = false;
            }
            int codePoint = ((b0 & 0x1F) << 6) | (b1 & 0x3F);
            sb.append((char) codePoint);
            i += 2;
            continue;
          }
        }
        inInvalidSequence = true;
        i++;
      } else if (b0 >= 0xE0 && b0 <= 0xEF) {
        // 3-byte sequence
        if (i + 2 < len) {
          int b1 = bytes[i + 1] & 0xFF;
          int b2 = bytes[i + 2] & 0xFF;
          boolean valid =
              (b2 >= 0x80 && b2 <= 0xBF)
                  && ((b0 == 0xE0 && b1 >= 0xA0 && b1 <= 0xBF)
                      || (b0 >= 0xE1 && b0 <= 0xEC && b1 >= 0x80 && b1 <= 0xBF)
                      || (b0 == 0xED && b1 >= 0x80 && b1 <= 0x9F)
                      || (b0 >= 0xEE && b0 <= 0xEF && b1 >= 0x80 && b1 <= 0xBF));
          if (valid) {
            if (inInvalidSequence) {
              sb.append('\uFFFD');
              inInvalidSequence = false;
            }
            int codePoint = ((b0 & 0x0F) << 12) | ((b1 & 0x3F) << 6) | (b2 & 0x3F);
            sb.append((char) codePoint);
            i += 3;
            continue;
          }
        }
        inInvalidSequence = true;
        i++;
      } else if (b0 >= 0xF0 && b0 <= 0xF4) {
        // 4-byte sequence
        if (i + 3 < len) {
          int b1 = bytes[i + 1] & 0xFF;
          int b2 = bytes[i + 2] & 0xFF;
          int b3 = bytes[i + 3] & 0xFF;
          boolean valid =
              (b2 >= 0x80 && b2 <= 0xBF)
                  && (b3 >= 0x80 && b3 <= 0xBF)
                  && ((b0 == 0xF0 && b1 >= 0x90 && b1 <= 0xBF)
                      || (b0 >= 0xF1 && b0 <= 0xF3 && b1 >= 0x80 && b1 <= 0xBF)
                      || (b0 == 0xF4 && b1 >= 0x80 && b1 <= 0x8F));
          if (valid) {
            if (inInvalidSequence) {
              sb.append('\uFFFD');
              inInvalidSequence = false;
            }
            int codePoint =
                ((b0 & 0x07) << 18) | ((b1 & 0x3F) << 12) | ((b2 & 0x3F) << 6) | (b3 & 0x3F);
            sb.append(Character.toChars(codePoint));
            i += 4;
            continue;
          }
        }
        inInvalidSequence = true;
        i++;
      } else {
        // Invalid leading byte (0x80-0xC1, 0xF5-0xFF)
        inInvalidSequence = true;
        i++;
      }
    }
    if (inInvalidSequence) {
      sb.append('\uFFFD');
    }
    return sb.toString();
  }

  private static String formatList(List<?> list) {
    StringBuilder sb = new StringBuilder("[");
    for (int i = 0; i < list.size(); i++) {
      sb.append(formatString(list.get(i)));
      if (i < list.size() - 1) {
        sb.append(", ");
      }
    }
    sb.append("]");
    return sb.toString();
  }

  private static class MapEntry {
    final String keyStr;
    final String valStr;

    MapEntry(String keyStr, String valStr) {
      this.keyStr = keyStr;
      this.valStr = valStr;
    }
  }

  private static String formatMap(Map<?, ?> map) {
    List<MapEntry> entries = new ArrayList<>(map.size());
    for (Map.Entry<?, ?> entry : map.entrySet()) {
      entries.add(new MapEntry(formatString(entry.getKey()), formatString(entry.getValue())));
    }
    entries.sort(Comparator.comparing(e -> e.keyStr));

    StringBuilder sb = new StringBuilder("{");
    for (int i = 0; i < entries.size(); i++) {
      MapEntry entry = entries.get(i);
      sb.append(entry.keyStr).append(": ").append(entry.valStr);
      if (i < entries.size() - 1) {
        sb.append(", ");
      }
    }
    sb.append("}");
    return sb.toString();
  }

  private static String formatDecimal(Object arg) {
    if (arg instanceof Double) {
      return formatDouble((Double) arg);
    }
    if (arg instanceof Long || arg instanceof UnsignedLong) {
      return arg.toString();
    }
    throw new CelInvalidArgumentException(
        "decimal clause can only be used on numbers, was given " + arg.getClass().getName());
  }

  private static String formatDouble(double val) {
    if (Double.isNaN(val)) {
      return "NaN";
    }
    if (Double.isInfinite(val)) {
      return val > 0 ? "Infinity" : "-Infinity";
    }
    if (val == 0.0) {
      return Double.doubleToRawLongBits(val) < 0 ? "-0" : "0";
    }
    String str = Double.toString(val);
    int expIdx = str.indexOf('E');
    if (expIdx == -1) {
      if (str.endsWith(".0")) {
        return str.substring(0, str.length() - 2);
      }
      return str;
    }
    return expandScientificNotation(str, expIdx);
  }

  private static String expandScientificNotation(String str, int expIdx) {
    boolean negative = str.startsWith("-");
    int mantissaStart = negative ? 1 : 0;
    String mantissa = str.substring(mantissaStart, expIdx);
    int exponent = Integer.parseInt(str.substring(expIdx + 1));

    int dotIdx = mantissa.indexOf('.');
    String digits;
    int decimalPos;
    if (dotIdx == -1) {
      digits = mantissa;
      decimalPos = digits.length();
    } else {
      digits = mantissa.substring(0, dotIdx) + mantissa.substring(dotIdx + 1);
      decimalPos = dotIdx;
    }

    int newDecimalPos = decimalPos + exponent;
    StringBuilder sb = new StringBuilder();
    boolean hasDot = false;
    if (negative) {
      sb.append('-');
    }

    if (newDecimalPos <= 0) {
      sb.append("0.");
      hasDot = true;
      for (int i = 0; i < -newDecimalPos; i++) {
        sb.append('0');
      }
      sb.append(digits);
    } else if (newDecimalPos >= digits.length()) {
      sb.append(digits);
      for (int i = 0; i < newDecimalPos - digits.length(); i++) {
        sb.append('0');
      }
    } else {
      sb.append(digits, 0, newDecimalPos);
      sb.append('.');
      hasDot = true;
      sb.append(digits, newDecimalPos, digits.length());
    }

    if (hasDot) {
      int end = sb.length() - 1;
      while (end >= 0 && sb.charAt(end) == '0') {
        end--;
      }
      if (end >= 0 && sb.charAt(end) == '.') {
        end--;
      }
      sb.setLength(end + 1);
    }
    return sb.toString();
  }

  private static double getDoubleValue(Object arg, String clauseName) {
    if (arg instanceof Double) {
      return (Double) arg;
    }
    if (arg instanceof Long) {
      return ((Long) arg).doubleValue();
    }
    if (arg instanceof UnsignedLong) {
      return ((UnsignedLong) arg).doubleValue();
    }
    throw new CelInvalidArgumentException(
        clauseName
            + " clause can only be used on doubles, integers, and unsigned integers, was given "
            + arg.getClass().getName());
  }

  private static String formatFixed(Object arg, int precision) {
    double val = getDoubleValue(arg, "fixed point");
    if (Double.isNaN(val)) {
      return "NaN";
    }
    if (Double.isInfinite(val)) {
      return val > 0 ? "Infinity" : "-Infinity";
    }
    // Math.rint is strictly required for cross-stack parity.
    // Go and C++ formatters natively use IEEE 754 HALF_EVEN (Banker's) rounding for %f.
    // Java's String.format deviates by using HALF_UP rounding so HALF_EVEN pre-rounding is applied
    // to match Go and C++ outputs on boundary ties.
    int p = precision >= 0 ? precision : 6;
    if (p <= 15 && Math.abs(val) < 1e14) {
      double factor = Math.pow(10, p);
      val = Math.rint(val * factor) / factor;
    }
    String fmtStr = "%." + p + "f";
    return String.format(LOCALE_ROOT, fmtStr, val);
  }

  private static String formatScientific(Object arg, int precision) {
    double val = getDoubleValue(arg, "scientific");
    if (Double.isNaN(val)) {
      return "NaN";
    }
    if (Double.isInfinite(val)) {
      return val > 0 ? "Infinity" : "-Infinity";
    }
    String fmtStr = precision >= 0 ? "%." + precision + "e" : "%.6e";
    return String.format(LOCALE_ROOT, fmtStr, val);
  }

  private static String formatBinary(Object arg) {
    if (arg instanceof Long) {
      long val = (Long) arg;
      if (val < 0) {
        if (val == Long.MIN_VALUE) {
          return MIN_LONG_BINARY;
        }
        return "-" + Long.toBinaryString(-val);
      }
      return Long.toBinaryString(val);
    }
    if (arg instanceof UnsignedLong) {
      UnsignedLong ulong = (UnsignedLong) arg;
      return ulong.toString(2);
    }
    if (arg instanceof Boolean) {
      Boolean b = (Boolean) arg;
      return b ? "1" : "0";
    }
    throw new CelInvalidArgumentException(
        "binary clause can only be used on integers and bools, was given "
            + arg.getClass().getName());
  }

  private static String formatHex(Object arg, boolean upper) {
    String result;
    if (arg instanceof Long) {
      long val = (Long) arg;
      if (val < 0) {
        if (val == Long.MIN_VALUE) {
          result = MIN_LONG_HEX;
        } else {
          result = "-" + Long.toHexString(-val);
        }
      } else {
        result = Long.toHexString(val);
      }
    } else if (arg instanceof UnsignedLong) {
      UnsignedLong unsignedLong = (UnsignedLong) arg;
      result = unsignedLong.toString(16);
    } else if (arg instanceof CelByteString) {
      CelByteString byteString = (CelByteString) arg;
      result = BASE16_LOWER.encode(byteString.toByteArray());
    } else if (arg instanceof String) {
      String str = (String) arg;
      result = BASE16_LOWER.encode(str.getBytes(UTF_8));
    } else {
      throw new CelInvalidArgumentException(
          "hex clause can only be used on integers, byte buffers, and strings, was given "
              + arg.getClass().getName());
    }
    return upper ? result.toUpperCase(LOCALE_ROOT) : result;
  }

  private static String formatOctal(Object arg) {
    if (arg instanceof Long) {
      long val = (Long) arg;
      if (val < 0) {
        if (val == Long.MIN_VALUE) {
          return MIN_LONG_OCTAL;
        }
        return "-" + Long.toOctalString(-val);
      }
      return Long.toOctalString(val);
    }
    if (arg instanceof UnsignedLong) {
      UnsignedLong ulong = (UnsignedLong) arg;
      return ulong.toString(8);
    }
    throw new CelInvalidArgumentException(
        "octal clause can only be used on integers, was given " + arg.getClass().getName());
  }

  private static Long lastIndexOf(String str, String substr) throws CelEvaluationException {
    CelCodePointArray strCpa = CelCodePointArray.fromString(str);
    CelCodePointArray substrCpa = CelCodePointArray.fromString(substr);
    if (substrCpa.isEmpty()) {
      return (long) strCpa.length();
    }

    if (strCpa.length() < substrCpa.length()) {
      return -1L;
    }

    return lastIndexOf(strCpa, substrCpa, (long) strCpa.length() - 1);
  }

  private static Long lastIndexOf(Object[] args) throws CelEvaluationException {
    CelCodePointArray strCpa = CelCodePointArray.fromString((String) args[0]);
    CelCodePointArray substrCpa = CelCodePointArray.fromString((String) args[1]);
    long offset = (long) args[2];

    return lastIndexOf(strCpa, substrCpa, offset);
  }

  private static Long lastIndexOf(CelCodePointArray str, CelCodePointArray substr, long offset)
      throws CelEvaluationException {
    if (substr.isEmpty()) {
      return offset;
    }

    int off;
    try {
      off = Math.toIntExact(offset);
    } catch (ArithmeticException e) {
      throw CelEvaluationExceptionBuilder.newBuilder(
              "lastIndexOf failure: Offset must not exceed the int32 range: %d", offset)
          .setCause(e)
          .build();
    }

    if (off < 0 || off >= str.length()) {
      throw CelEvaluationExceptionBuilder.newBuilder(
              "lastIndexOf failure: Offset out of range: %d", offset)
          .build();
    }

    if (off > str.length() - substr.length()) {
      off = str.length() - substr.length();
    }

    for (int i = off; i >= 0; i--) {
      int j;
      for (j = 0; j < substr.length(); j++) {
        if (str.get(i + j) != substr.get(j)) {
          break;
        }
      }

      if (j == substr.length()) {
        return (long) i;
      }
    }

    return -1L;
  }

  private static String quote(String s) {
    StringBuilder sb = new StringBuilder(s.length() + 2);
    sb.append('"');
    for (int i = 0; i < s.length(); ) {
      int codePoint = s.codePointAt(i);
      if (isMalformedUtf16(s, i)) {
        sb.append('\uFFFD');
        i++;
        continue;
      }
      switch (codePoint) {
        case '\u0007':
          sb.append("\\a");
          break;
        case '\b':
          sb.append("\\b");
          break;
        case '\f':
          sb.append("\\f");
          break;
        case '\n':
          sb.append("\\n");
          break;
        case '\r':
          sb.append("\\r");
          break;
        case '\t':
          sb.append("\\t");
          break;
        case '\u000B':
          sb.append("\\v");
          break;
        case '\\':
          sb.append("\\\\");
          break;
        case '"':
          sb.append("\\\"");
          break;
        default:
          sb.appendCodePoint(codePoint);
          break;
      }
      i += Character.charCount(codePoint);
    }
    sb.append('"');
    return sb.toString();
  }

  private static boolean isMalformedUtf16(String s, int index) {
    char currentChar = s.charAt(index);
    if (Character.isLowSurrogate(currentChar)) {
      return true;
    }
    // Check for unpaired high surrogate
    return Character.isHighSurrogate(currentChar)
        && (index + 1 >= s.length() || !Character.isLowSurrogate(s.charAt(index + 1)));
  }

  private static String replaceAll(Object[] objects) {
    return replace((String) objects[0], (String) objects[1], (String) objects[2], -1);
  }

  private static String replace(Object[] objects) throws CelEvaluationException {
    Long indexInLong = (Long) objects[3];
    int index;
    try {
      index = Math.toIntExact(indexInLong);
    } catch (ArithmeticException e) {
      throw CelEvaluationExceptionBuilder.newBuilder(
              "replace failure: Index must not exceed the int32 range: %d", indexInLong)
          .setCause(e)
          .build();
    }

    return replace((String) objects[0], (String) objects[1], (String) objects[2], index);
  }

  private static String replace(String text, String searchString, String replacement, int limit) {
    if (searchString.equals(replacement) || limit == 0) {
      return text;
    }

    if (text.isEmpty()) {
      return searchString.isEmpty() ? replacement : "";
    }

    CelCodePointArray textCpa = CelCodePointArray.fromString(text);
    CelCodePointArray searchCpa = CelCodePointArray.fromString(searchString);
    CelCodePointArray replaceCpa = CelCodePointArray.fromString(replacement);

    int start = 0;
    int end = Math.toIntExact(safeIndexOf(textCpa, searchCpa, 0));
    if (end < 0) {
      return text;
    }

    // The minimum length of 1 handles the case of searchString being empty, where every character
    // would be matched. This ensures the window is always moved forward to continue the search.
    int minSearchLength = max(searchCpa.length(), 1);
    StringBuilder sb =
        new StringBuilder(textCpa.length() - searchCpa.length() + replaceCpa.length());

    do {
      CelCodePointArray sliced = textCpa.slice(start, end);
      sb.append(sliced).append(replaceCpa);
      start = end + searchCpa.length();
      limit--;
    } while (limit != 0
        && (end = Math.toIntExact(safeIndexOf(textCpa, searchCpa, end + minSearchLength))) > 0);

    return sb.append(textCpa.slice(start, textCpa.length())).toString();
  }

  private static String reverse(String s) {
    return new StringBuilder(s).reverse().toString();
  }

  private static ImmutableList<String> split(String str, String separator) {
    return split(str, separator, Integer.MAX_VALUE);
  }

  /**
   * @param args Object array with indices of: [0: string], [1: separator], [2: limit]
   */
  private static ImmutableList<String> split(Object[] args) throws CelEvaluationException {
    long limitInLong = (Long) args[2];
    int limit;
    try {
      limit = Math.toIntExact(limitInLong);
    } catch (ArithmeticException e) {
      throw CelEvaluationExceptionBuilder.newBuilder(
              "split failure: Limit must not exceed the int32 range: %d", limitInLong)
          .setCause(e)
          .build();
    }

    return split((String) args[0], (String) args[1], limit);
  }

  /** Returns an immutable list of strings split on the separator */
  private static ImmutableList<String> split(String str, String separator, int limit) {
    if (limit == 0) {
      return ImmutableList.of();
    }

    if (limit == 1) {
      return ImmutableList.of(str);
    }

    if (limit < 0) {
      limit = str.length();
    }

    if (separator.isEmpty()) {
      return explode(str, limit);
    }

    Iterable<String> splitString = Splitter.on(separator).limit(limit).split(str);
    return ImmutableList.copyOf(splitString);
  }

  /**
   * Explodes a given string up to a limit
   *
   * <p>Example 1: "a가b😁" (no limit or negative limit) -> ["a", "가", "b", "😁"]
   *
   * <p>Example 2: "a가b😁" (limit 2) -> ["a", "가", "b😁"]
   *
   * <p>This exists because neither the built-in String.split nor Guava's splitter is able to deal
   * with separating single printable characters.
   */
  private static ImmutableList<String> explode(String str, int limit) {
    ImmutableList.Builder<String> exploded = ImmutableList.builder();
    CelCodePointArray codePointArray = CelCodePointArray.fromString(str);
    if (limit > 0) {
      limit -= 1;
    }
    int charCount = min(codePointArray.length(), limit);
    for (int i = 0; i < charCount; i++) {
      exploded.add(codePointArray.slice(i, i + 1).toString());
    }
    if (codePointArray.length() > limit) {
      exploded.add(codePointArray.slice(limit, codePointArray.length()).toString());
    }
    return exploded.build();
  }

  private static Object substring(String s, long i) throws CelEvaluationException {
    int beginIndex;
    try {
      beginIndex = Math.toIntExact(i);
    } catch (ArithmeticException e) {
      throw CelEvaluationExceptionBuilder.newBuilder(
              "substring failure: Index must not exceed the int32 range: %d", i)
          .setCause(e)
          .build();
    }

    CelCodePointArray codePointArray = CelCodePointArray.fromString(s);

    boolean indexIsInRange = beginIndex <= codePointArray.length() && beginIndex >= 0;
    if (!indexIsInRange) {
      throw CelEvaluationExceptionBuilder.newBuilder(
              "substring failure: Range [%d, %d) out of bounds",
              beginIndex, codePointArray.length())
          .build();
    }

    if (beginIndex == codePointArray.length()) {
      return "";
    }

    return codePointArray.slice(beginIndex, codePointArray.length()).toString();
  }

  /**
   * @param args Object array with indices of [0: string], [1: beginIndex], [2: endIndex]
   */
  private static String substring(Object[] args) throws CelEvaluationException {
    Long beginIndexInLong = (Long) args[1];
    Long endIndexInLong = (Long) args[2];
    int beginIndex;
    int endIndex;
    try {
      beginIndex = Math.toIntExact(beginIndexInLong);
      endIndex = Math.toIntExact(endIndexInLong);
    } catch (ArithmeticException e) {
      throw CelEvaluationExceptionBuilder.newBuilder(
              "substring failure: Indices must not exceed the int32 range: [%d, %d)",
              beginIndexInLong, endIndexInLong)
          .setCause(e)
          .build();
    }

    String s = (String) args[0];
    CelCodePointArray codePointArray = CelCodePointArray.fromString(s);

    boolean indicesIsInRange =
        beginIndex <= endIndex
            && beginIndex >= 0
            && beginIndex <= codePointArray.length()
            && endIndex <= codePointArray.length();
    if (!indicesIsInRange) {
      throw CelEvaluationExceptionBuilder.newBuilder(
              "substring failure: Range [%d, %d) out of bounds", beginIndex, endIndex)
          .build();
    }

    if (beginIndex == endIndex) {
      return "";
    }

    return codePointArray.slice(beginIndex, endIndex).toString();
  }

  private static String trim(String text) {
    CelCodePointArray textCpa = CelCodePointArray.fromString(text);
    int left = indexOfNonWhitespace(textCpa);
    if (left == textCpa.length()) {
      return "";
    }
    int right = lastIndexOfNonWhitespace(textCpa);
    return textCpa.slice(left, right + 1).toString();
  }

  /**
   * Finds the first index of the non-whitespace character found in the string. See {@link
   * #isWhitespace} for definition of a whitespace char.
   *
   * @return index of first non-whitespace character found (ex: " test " -> 0). Length of the string
   *     is returned instead if a non-whitespace character is not found.
   */
  private static int indexOfNonWhitespace(CelCodePointArray textCpa) {
    for (int i = 0; i < textCpa.length(); i++) {
      if (!isWhitespace(textCpa.get(i))) {
        return i;
      }
    }
    return textCpa.length();
  }

  /**
   * Finds the last index of the non-whitespace character found in the string. See {@link
   * #isWhitespace} for definition of a whitespace char.
   *
   * @return index of last non-whitespace character found. (ex: " test " -> 5). 0 is returned
   *     instead if a non-whitespace char is not found. -1 is returned for an empty string ("").
   */
  private static int lastIndexOfNonWhitespace(CelCodePointArray textCpa) {
    if (textCpa.isEmpty()) {
      return -1;
    }

    for (int i = textCpa.length() - 1; i >= 0; i--) {
      if (!isWhitespace(textCpa.get(i))) {
        return i;
      }
    }

    return 0;
  }

  /**
   * Checks if a provided codepoint is a whitespace according to Unicode's standard
   * (White_Space=yes).
   *
   * <p>This exists because Java's native Character.isWhitespace does not follow the Unicode's
   * standard of whitespace definition.
   *
   * <p>See <a href="https://en.wikipedia.org/wiki/Whitespace_character">link<a> for the full list.
   */
  private static boolean isWhitespace(int codePoint) {
    return (codePoint >= 0x0009 && codePoint <= 0x000D)
        || codePoint == 0x0020
        || codePoint == 0x0085
        || codePoint == 0x00A0
        || codePoint == 0x1680
        || (codePoint >= 0x2000 && codePoint <= 0x200A)
        || codePoint == 0x2028
        || codePoint == 0x2029
        || codePoint == 0x202F
        || codePoint == 0x205F
        || codePoint == 0x3000;
  }
}
