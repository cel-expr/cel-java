// Copyright 2023 Google LLC
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

package dev.cel.checker;

import dev.cel.common.annotations.Internal;
import dev.cel.common.types.CelType;
import dev.cel.common.types.CelTypes;
import org.jspecify.annotations.Nullable;

/**
 * Class to format {@link CelType} objects into {@code String} values.
 *
 * <p>CEL Library Internals. Do Not Use.
 */
@Internal
final class TypeFormatter {

  /** Format a function string from the {@code argTypes} and {@code isInstance} information */
  static String formatFunction(Iterable<CelType> argTypes, boolean isInstance) {
    return formatFunction(null, argTypes, isInstance);
  }

  /**
   * Format a function signature string from the input {@code argTypes} and {@code resultType}.
   *
   * <p>When {@code isInstance} is {@code true}, the {@code argTypes[0]} type is used as the
   * receiver type.
   *
   * <p>When {@code resultType} is {@code null}, the function signature omits the result type. This
   * is useful for computing overload signatures.
   */
  static String formatFunction(
      @Nullable CelType resultType, Iterable<CelType> argTypes, boolean isInstance) {
    return formatFunction(resultType, argTypes, isInstance, /* typeParamToDyn= */ false);
  }

  /**
   * Format a function signature string from the input {@code argTypes} and {@code resultType}.
   *
   * <p>When {@code isInstance} is {@code true}, the {@code argTypes[0]} type is used as the
   * receiver type.
   *
   * <p>When {@code resultType} is {@code null}, the function signature omits the result type. This
   * is useful for computing overload signatures.
   *
   * <p>When {@code typeParamToDyn} is {@code true}, parameterized type argument are represented as
   * {@code SimpleType.DYN} values.
   */
  static String formatFunction(
      @Nullable CelType resultType,
      Iterable<CelType> argTypes,
      boolean isInstance,
      boolean typeParamToDyn) {
    return CelTypes.formatFunction(resultType, argTypes, isInstance, typeParamToDyn);
  }

  private TypeFormatter() {}
}
