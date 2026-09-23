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

import com.google.common.base.Ascii;
import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.errorprone.annotations.Immutable;
import dev.cel.checker.CelCheckerBuilder;
import dev.cel.common.CelFunctionDecl;
import dev.cel.common.CelOverloadDecl;
import dev.cel.common.internal.CelCodePointArray;
import dev.cel.common.types.ListType;
import dev.cel.common.types.SimpleType;
import dev.cel.compiler.CelCompilerLibrary;
import dev.cel.runtime.CelEvaluationException;
import dev.cel.runtime.CelEvaluationExceptionBuilder;
import dev.cel.runtime.CelFunctionBinding;
import dev.cel.runtime.CelRuntimeBuilder;
import dev.cel.runtime.CelRuntimeLibrary;
import java.util.List;
import java.util.Set;

/** Internal implementation of CEL string extensions. */
@Immutable
public final class CelStringExtensions
    implements CelCompilerLibrary, CelRuntimeLibrary, CelExtensionLibrary.FeatureSet {

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
