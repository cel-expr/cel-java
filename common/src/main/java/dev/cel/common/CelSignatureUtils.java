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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.collect.ImmutableList;
import dev.cel.common.annotations.Internal;
import dev.cel.common.types.CelType;
import dev.cel.common.types.EnumType;
import dev.cel.common.types.ListType;
import dev.cel.common.types.MapType;
import dev.cel.common.types.NullableType;
import dev.cel.common.types.TypeType;
import java.util.List;

/**
 * Internal utility methods for generating canonical CEL type and function overload signatures.
 *
 * <p>CEL Library Internals. Do Not Use.
 */
@Internal
public final class CelSignatureUtils {

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
      checkArgument(!args.isEmpty(), "Member function with no receiver");
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
        throw new IllegalArgumentException("Unsupported type in signature: " + type.name());
    }
  }

  private CelSignatureUtils() {}
}
