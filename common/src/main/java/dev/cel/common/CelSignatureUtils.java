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

package dev.cel.common;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableList;
import com.google.errorprone.annotations.Immutable;
import dev.cel.common.annotations.Internal;
import dev.cel.common.types.CelKind;
import dev.cel.common.types.CelType;
import dev.cel.common.types.CelTypeProvider;
import dev.cel.common.types.CelTypes;
import dev.cel.common.types.EnumType;
import dev.cel.common.types.ListType;
import dev.cel.common.types.MapType;
import dev.cel.common.types.NullableType;
import dev.cel.common.types.OpaqueType;
import dev.cel.common.types.OptionalType;
import dev.cel.common.types.SimpleType;
import dev.cel.common.types.TypeParamType;
import dev.cel.common.types.TypeType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Internal utility methods for generating and parsing canonical CEL type and function overload
 * signatures.
 *
 * <p>CEL Library Internals. Do Not Use.
 */
@Internal
public final class CelSignatureUtils {

  /** Maximum recursion depth when parsing nested type signatures. */
  public static final int MAX_RECURSION_DEPTH = 64;

  /** Represents a parsed function overload signature. */
  @AutoValue
  @Immutable
  public abstract static class ParsedFunctionOverload {
    public abstract String functionName();

    public abstract boolean isMember();

    public abstract ImmutableList<CelType> argTypes();

    /**
     * An opaque {@code function} type with unknown return ({@code DYN}) and arguments matching
     * {@link #argTypes()}.
     */
    public abstract CelType signatureType();

    public static ParsedFunctionOverload create(
        String functionName, boolean isMember, Iterable<CelType> argTypes, CelType signatureType) {
      return new AutoValue_CelSignatureUtils_ParsedFunctionOverload(
          functionName, isMember, ImmutableList.copyOf(argTypes), signatureType);
    }
  }

  /**
   * Generates a canonical signature string for a {@link CelType}.
   *
   * <p>For example:
   *
   * <ul>
   *   <li>{@code SimpleType.INT} -> {@code "int"}
   *   <li>{@code ListType.create(SimpleType.STRING)} -> {@code "list<string>"}
   *   <li>{@code TypeParamType.create("T")} -> {@code "~T"}
   * </ul>
   *
   * @throws IllegalArgumentException if the type cannot be represented as a signature (e.g. {@code
   *     ERROR}).
   */
  public static String makeTypeSignature(CelType type) {
    checkNotNull(type);
    StringBuilder result = new StringBuilder();
    appendTypeSignature(result, type);
    return result.toString();
  }

  /**
   * Generates a canonical overload ID / signature string for a function overload.
   *
   * <p>For example:
   *
   * <ul>
   *   <li>{@code makeOverloadSignature("add", ImmutableList.of(INT, INT), false)} -> {@code
   *       "add(int,int)"}
   *   <li>{@code makeOverloadSignature("contains", ImmutableList.of(STRING, STRING), true)} ->
   *       {@code "string.contains(string)"}
   * </ul>
   *
   * @throws IllegalArgumentException if {@code isMember} is true and {@code args} is empty, or if
   *     any argument type is unsupported in signatures.
   */
  public static String makeOverloadSignature(
      String functionName, List<CelType> args, boolean isMember) {
    checkNotNull(functionName);
    checkNotNull(args);
    StringBuilder result = new StringBuilder();
    if (isMember) {
      if (args.isEmpty()) {
        throw new IllegalArgumentException("Member function with no receiver");
      }
      appendTypeSignature(result, args.get(0));
      result.append('.');
    }
    appendEscaped(result, functionName, /* escapeDot= */ true);
    result.append('(');
    int startIndex = isMember ? 1 : 0;
    for (int i = startIndex; i < args.size(); i++) {
      if (i > startIndex) {
        result.append(',');
      }
      appendTypeSignature(result, args.get(i));
    }
    result.append(')');
    return result.toString();
  }

  /**
   * Generates a canonical overload ID / signature string for a function overload.
   *
   * @see #makeOverloadSignature(String, List, boolean)
   */
  public static String makeOverloadSignature(
      String functionName, boolean isMember, CelType... args) {
    return makeOverloadSignature(functionName, Arrays.asList(args), isMember);
  }

  /**
   * Parses a canonical type signature string into a {@link CelType}.
   *
   * <p>Unresolved custom type names are returned as {@link OpaqueType} instances.
   */
  public static CelType parseTypeSignature(String signature) {
    checkNotNull(signature);
    return parseTypeSignatureInternal(signature, /* typeProvider= */ null);
  }

  /**
   * Parses a canonical type signature string into a {@link CelType}, resolving custom type names
   * via {@code typeProvider}.
   *
   * <p>If a custom type name is not found in {@code typeProvider}, it falls back to an {@link
   * OpaqueType}.
   */
  public static CelType parseTypeSignature(String signature, CelTypeProvider typeProvider) {
    checkNotNull(signature);
    checkNotNull(typeProvider);
    return parseTypeSignatureInternal(signature, typeProvider);
  }

  /**
   * Parses a canonical function overload signature string into a {@link ParsedFunctionOverload}.
   *
   * <p>For example, {@code "string.contains(string)"} or {@code "add(int, int)"}.
   */
  public static ParsedFunctionOverload parseFunctionSignature(String signature) {
    checkNotNull(signature);
    return parseFunctionSignatureInternal(signature, /* typeProvider= */ null);
  }

  /**
   * Parses a canonical function overload signature string into a {@link ParsedFunctionOverload},
   * resolving custom argument and receiver types via {@code typeProvider}.
   */
  public static ParsedFunctionOverload parseFunctionSignature(
      String signature, CelTypeProvider typeProvider) {
    checkNotNull(signature);
    checkNotNull(typeProvider);
    return parseFunctionSignatureInternal(signature, typeProvider);
  }

  /** Returns true if {@code c} is an ASCII whitespace character (' ', '\t', '\n', '\r', etc.). */
  public static boolean isWhitespace(char c) {
    return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f' || c == '\u000b';
  }

  /** Returns true if {@code c} is an ASCII alphabetic character ('a'-'z' or 'A'-'Z'). */
  public static boolean isAlpha(char c) {
    return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
  }

  /** Returns true if {@code c} is an ASCII alphanumeric character ('a'-'z', 'A'-'Z', '0'-'9'). */
  public static boolean isAlphaNumeric(char c) {
    return isAlpha(c) || (c >= '0' && c <= '9');
  }

  private static void appendEscaped(StringBuilder result, String str, boolean escapeDot) {
    for (int i = 0; i < str.length(); i++) {
      char c = str.charAt(i);
      if (c == '\\'
          || c == '('
          || c == ')'
          || c == '<'
          || c == '>'
          || c == '"'
          || c == ','
          || c == '~'
          || (escapeDot && c == '.')) {
        result.append('\\');
      }
      result.append(c);
    }
  }

  private static String unescape(String str) {
    StringBuilder result = new StringBuilder(str.length());
    boolean escaped = false;
    for (int i = 0; i < str.length(); i++) {
      char c = str.charAt(i);
      if (escaped) {
        result.append(c);
        escaped = false;
      } else if (c == '\\') {
        escaped = true;
      } else {
        result.append(c);
      }
    }
    return result.toString();
  }

  private static String removeUnescapedWhitespace(String signature) {
    StringBuilder cleaned = new StringBuilder(signature.length());
    boolean escaped = false;
    for (int i = 0; i < signature.length(); i++) {
      char c = signature.charAt(i);
      if (escaped) {
        cleaned.append(c);
        escaped = false;
      } else if (c == '\\') {
        cleaned.append(c);
        escaped = true;
      } else if (!isWhitespace(c)) {
        cleaned.append(c);
      }
    }
    return cleaned.toString();
  }

  private static void appendTypeSignature(StringBuilder result, CelType type) {
    checkNotNull(type);
    if (type instanceof NullableType) {
      CelType targetType = ((NullableType) type).targetType();
      switch (targetType.kind()) {
        case BOOL:
          result.append("bool_wrapper");
          return;
        case INT:
          result.append("int_wrapper");
          return;
        case UINT:
          result.append("uint_wrapper");
          return;
        case DOUBLE:
          result.append("double_wrapper");
          return;
        case STRING:
          result.append("string_wrapper");
          return;
        case BYTES:
          result.append("bytes_wrapper");
          return;
        default:
          throw new IllegalArgumentException("Unsupported wrapper type: " + type.name());
      }
    }
    if (type instanceof EnumType) {
      appendEscaped(result, type.name(), /* escapeDot= */ false);
      return;
    }

    switch (type.kind()) {
      case NULL_TYPE:
        result.append("null");
        break;
      case DYN:
        result.append("dyn");
        break;
      case BOOL:
        result.append("bool");
        break;
      case INT:
        result.append("int");
        break;
      case UINT:
        result.append("uint");
        break;
      case DOUBLE:
        result.append("double");
        break;
      case STRING:
        result.append("string");
        break;
      case BYTES:
        result.append("bytes");
        break;
      case ANY:
        result.append("any");
        break;
      case TIMESTAMP:
        result.append("timestamp");
        break;
      case DURATION:
        result.append("duration");
        break;
      case LIST:
        ListType listType = (ListType) type;
        result.append("list<");
        if (listType.hasElemType()) {
          appendTypeSignature(result, listType.elemType());
        } else {
          result.append("dyn");
        }
        result.append('>');
        break;
      case MAP:
        MapType mapType = (MapType) type;
        result.append("map<");
        appendTypeSignature(result, mapType.keyType());
        result.append(',');
        appendTypeSignature(result, mapType.valueType());
        result.append('>');
        break;
      case TYPE:
        TypeType typeType = (TypeType) type;
        result.append("type<");
        appendTypeSignature(result, typeType.type());
        result.append('>');
        break;
      case TYPE_PARAM:
        result.append('~');
        appendEscaped(result, type.name(), /* escapeDot= */ true);
        break;
      case OPAQUE:
        if (type.name().equals("function")) {
          result.append("function<");
          ImmutableList<CelType> params = type.parameters();
          if (params.isEmpty()) {
            result.append("dyn");
          } else {
            for (int i = 0; i < params.size(); i++) {
              if (i > 0) {
                result.append(',');
              }
              appendTypeSignature(result, params.get(i));
            }
          }
          result.append('>');
        } else {
          appendEscaped(result, type.name(), /* escapeDot= */ false);
          ImmutableList<CelType> params = type.parameters();
          if (!params.isEmpty()) {
            result.append('<');
            for (int i = 0; i < params.size(); i++) {
              if (i > 0) {
                result.append(',');
              }
              appendTypeSignature(result, params.get(i));
            }
            result.append('>');
          }
        }
        break;
      case STRUCT:
        appendEscaped(result, type.name(), /* escapeDot= */ false);
        break;
      case ERROR:
      case UNSPECIFIED:
      default:
        throw new IllegalArgumentException("Unsupported type in signature: " + type.name());
    }
  }

  private static int findTopLevelChar(
      String str, char target, boolean findFirst, String errorPrefix) {
    int bracketDepth = 0;
    boolean escaped = false;
    int foundPos = -1;
    for (int i = 0; i < str.length(); i++) {
      char c = str.charAt(i);
      if (escaped) {
        escaped = false;
        continue;
      }
      if (c == '\\') {
        escaped = true;
        continue;
      }
      if (c == target && bracketDepth == 0) {
        if (findFirst) {
          return i;
        }
        foundPos = i;
      }
      if (c == '<') {
        bracketDepth++;
      } else if (c == '>') {
        bracketDepth--;
        if (bracketDepth < 0) {
          throw new IllegalArgumentException(errorPrefix + ": mismatched brackets");
        }
      }
    }
    if (bracketDepth != 0) {
      throw new IllegalArgumentException(errorPrefix + ": mismatched brackets");
    }
    return foundPos;
  }

  private static List<String> splitTopLevel(String str, char delimiter, String errorPrefix) {
    List<String> slices = new ArrayList<>();
    int bracketDepth = 0;
    boolean escaped = false;
    int start = 0;
    for (int i = 0; i < str.length(); i++) {
      char c = str.charAt(i);
      if (escaped) {
        escaped = false;
        continue;
      }
      if (c == '\\') {
        escaped = true;
        continue;
      }
      if (c == '<') {
        bracketDepth++;
      } else if (c == '>') {
        bracketDepth--;
        if (bracketDepth < 0) {
          throw new IllegalArgumentException(errorPrefix + ": mismatched brackets");
        }
      } else if (c == delimiter && bracketDepth == 0) {
        slices.add(str.substring(start, i));
        start = i + 1;
      }
    }
    if (bracketDepth != 0) {
      throw new IllegalArgumentException(errorPrefix + ": mismatched brackets");
    }
    slices.add(str.substring(start));
    return slices;
  }

  private static CelType parseTypeSignatureInternal(
      String signature, CelTypeProvider typeProvider) {
    String cleaned = removeUnescapedWhitespace(signature);
    return parseTypeSignatureFromCleaned(cleaned, typeProvider, /* depth= */ 0);
  }

  private static CelType parseTypeSignatureFromCleaned(
      String cleaned, CelTypeProvider typeProvider, int depth) {
    if (depth > MAX_RECURSION_DEPTH) {
      throw new IllegalArgumentException("Exceeded maximum recursion depth");
    }
    if (cleaned.isEmpty()) {
      throw new IllegalArgumentException("Empty type signature");
    }
    if (cleaned.charAt(0) == '~') {
      String paramName = cleaned.substring(1);
      if (paramName.isEmpty()) {
        throw new IllegalArgumentException("Empty type parameter name");
      }
      if (findTopLevelChar(paramName, '<', /* findFirst= */ true, "Invalid type parameter") != -1
          || findTopLevelChar(paramName, ',', /* findFirst= */ true, "Invalid type parameter")
              != -1) {
        throw new IllegalArgumentException("Invalid type parameter name: " + paramName);
      }
      return TypeParamType.create(unescape(paramName));
    }

    int openBracket =
        findTopLevelChar(cleaned, '<', /* findFirst= */ true, "Invalid type signature");
    String baseName = cleaned;
    List<CelType> params = new ArrayList<>();
    if (openBracket != -1) {
      if (cleaned.charAt(cleaned.length() - 1) != '>') {
        throw new IllegalArgumentException(
            "Invalid type signature, missing closing '>': " + cleaned);
      }
      baseName = cleaned.substring(0, openBracket);
      String paramsStr = cleaned.substring(openBracket + 1, cleaned.length() - 1);
      if (paramsStr.isEmpty()) {
        throw new IllegalArgumentException("Empty type parameters in signature: " + cleaned);
      }
      List<String> paramSlices = splitTopLevel(paramsStr, ',', "Invalid type parameters");
      for (String paramSlice : paramSlices) {
        params.add(parseTypeSignatureFromCleaned(paramSlice, typeProvider, depth + 1));
      }
    }

    String nameStr = unescape(baseName);
    CelType builtinOrWrapper = resolveBuiltinOrWrapperType(nameStr);
    if (builtinOrWrapper != null) {
      if (!params.isEmpty()) {
        throw new IllegalArgumentException(
            "Invalid type signature: " + nameStr + " cannot have type parameters");
      }
      return builtinOrWrapper;
    }

    switch (nameStr) {
      case "type":
        if (params.size() > 1) {
          throw new IllegalArgumentException(
              "Invalid type signature: type expected at most 1 parameter");
        }
        return TypeType.create(params.isEmpty() ? SimpleType.DYN : params.get(0));
      case "list":
        if (params.size() > 1) {
          throw new IllegalArgumentException(
              "Invalid type signature: list expected at most 1 parameter");
        }
        return ListType.create(params.isEmpty() ? SimpleType.DYN : params.get(0));
      case "map":
        if (!params.isEmpty() && params.size() != 2) {
          throw new IllegalArgumentException(
              "Invalid type signature: map expected 0 or 2 parameters");
        }
        if (params.isEmpty()) {
          return MapType.create(SimpleType.DYN, SimpleType.DYN);
        }
        return MapType.create(params.get(0), params.get(1));
      case "function":
        CelType resultType = params.isEmpty() ? SimpleType.DYN : params.get(0);
        List<CelType> argTypes =
            params.size() <= 1 ? ImmutableList.of() : params.subList(1, params.size());
        return CelTypes.createFunctionType(resultType, argTypes);
      case OptionalType.NAME:
        if (params.size() != 1) {
          throw new IllegalArgumentException(
              "Invalid type signature: optional_type expected 1 parameter");
        }
        return OptionalType.create(params.get(0));
      default:
        break;
    }

    if (nameStr.isEmpty() || nameStr.contains("..")) {
      throw new IllegalArgumentException("Invalid type name: " + nameStr);
    }

    if (typeProvider != null) {
      Optional<CelType> resolved = typeProvider.findType(nameStr);
      if (resolved.isPresent()) {
        CelType type = resolved.get();
        if (!params.isEmpty()) {
          if (type.kind() != CelKind.OPAQUE
              && type.kind() != CelKind.LIST
              && type.kind() != CelKind.MAP
              && type.kind() != CelKind.TYPE) {
            throw new IllegalArgumentException(
                "Type '" + nameStr + "' cannot have type parameters");
          }
          return type.withParameters(ImmutableList.copyOf(params));
        }
        return type;
      }
    }

    return OpaqueType.create(nameStr, ImmutableList.copyOf(params));
  }

  private static CelType resolveBuiltinOrWrapperType(String nameStr) {
    switch (nameStr) {
      case "null":
      case "null_type":
        return SimpleType.NULL_TYPE;
      case "bool":
        return SimpleType.BOOL;
      case "int":
        return SimpleType.INT;
      case "uint":
        return SimpleType.UINT;
      case "double":
        return SimpleType.DOUBLE;
      case "string":
        return SimpleType.STRING;
      case "bytes":
        return SimpleType.BYTES;
      case "any":
      case "google.protobuf.Any":
        return SimpleType.ANY;
      case "timestamp":
      case "google.protobuf.Timestamp":
        return SimpleType.TIMESTAMP;
      case "duration":
      case "google.protobuf.Duration":
        return SimpleType.DURATION;
      case "dyn":
      case "google.protobuf.Value":
        return SimpleType.DYN;
      case "bool_wrapper":
      case "google.protobuf.BoolValue":
        return NullableType.create(SimpleType.BOOL);
      case "int_wrapper":
      case "google.protobuf.Int64Value":
      case "google.protobuf.Int32Value":
        return NullableType.create(SimpleType.INT);
      case "uint_wrapper":
      case "google.protobuf.UInt64Value":
      case "google.protobuf.UInt32Value":
        return NullableType.create(SimpleType.UINT);
      case "double_wrapper":
      case "google.protobuf.DoubleValue":
      case "google.protobuf.FloatValue":
        return NullableType.create(SimpleType.DOUBLE);
      case "string_wrapper":
      case "google.protobuf.StringValue":
        return NullableType.create(SimpleType.STRING);
      case "bytes_wrapper":
      case "google.protobuf.BytesValue":
        return NullableType.create(SimpleType.BYTES);
      case "google.protobuf.ListValue":
        return ListType.create(SimpleType.DYN);
      case "google.protobuf.Struct":
        return MapType.create(SimpleType.STRING, SimpleType.DYN);
      default:
        return null;
    }
  }

  private static ParsedFunctionOverload parseFunctionSignatureInternal(
      String signature, CelTypeProvider typeProvider) {
    String cleaned = removeUnescapedWhitespace(signature);
    if (cleaned.isEmpty()) {
      throw new IllegalArgumentException("Empty function signature");
    }

    int openParen =
        findTopLevelChar(cleaned, '(', /* findFirst= */ true, "Invalid function signature");
    if (openParen == -1) {
      throw new IllegalArgumentException("Invalid function signature, missing '(': " + cleaned);
    }
    if (cleaned.charAt(cleaned.length() - 1) != ')') {
      throw new IllegalArgumentException(
          "Invalid function signature, missing closing ')': " + cleaned);
    }

    String prefix = cleaned.substring(0, openParen);
    String argsStr = cleaned.substring(openParen + 1, cleaned.length() - 1);

    List<CelType> argTypes = new ArrayList<>();
    boolean isMember = false;
    String functionName;

    int dotPos =
        findTopLevelChar(prefix, '.', /* findFirst= */ false, "Invalid function signature");
    if (dotPos != -1) {
      isMember = true;
      String receiverStr = prefix.substring(0, dotPos);
      functionName = unescape(prefix.substring(dotPos + 1));
      argTypes.add(parseTypeSignatureFromCleaned(receiverStr, typeProvider, /* depth= */ 0));
    } else {
      functionName = unescape(prefix);
    }

    if (functionName.isEmpty()) {
      throw new IllegalArgumentException("Invalid function signature: empty function name");
    }

    if (!argsStr.isEmpty()) {
      List<String> argSlices = splitTopLevel(argsStr, ',', "Invalid function arguments");
      for (String argSlice : argSlices) {
        argTypes.add(parseTypeSignatureFromCleaned(argSlice, typeProvider, /* depth= */ 0));
      }
    }

    CelType signatureType = CelTypes.createFunctionType(SimpleType.DYN, argTypes);
    return ParsedFunctionOverload.create(functionName, isMember, argTypes, signatureType);
  }

  private CelSignatureUtils() {}
}
