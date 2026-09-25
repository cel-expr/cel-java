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

package dev.cel.common.values;

import com.google.common.collect.ImmutableList;
import dev.cel.common.annotations.Internal;
import dev.cel.common.exceptions.CelAttributeNotFoundException;
import java.util.Optional;

/**
 * Walks a sequence of {@link SelectField} selections over a struct target.
 *
 * <p>Each hop resolves by field number through {@link OptimizedSelectable} when the target
 * implements it, and otherwise by field name through {@link StructValue}. Any other target,
 * including maps and optional values, raises {@link CelAttributeNotFoundException}.
 *
 * <p>CEL Library Internals. Do Not Use.
 */
@Internal
public final class OptimizedSelectTraversal {

  /**
   * Qualifies {@code target} through every field in {@code fields} and returns the terminal value.
   */
  public static Object qualify(Object target, ImmutableList<SelectField> fields) {
    Object current = target;
    for (int i = 0; i < fields.size(); i++) {
      current = qualifyField(current, fields.get(i));
    }
    return current;
  }

  /**
   * Presence tests the terminal field of {@code fields}, navigating through all preceding fields.
   */
  public static boolean hasField(Object target, ImmutableList<SelectField> fields) {
    if (fields.isEmpty()) {
      return false;
    }
    Object current = target;
    int terminalIndex = fields.size() - 1;
    for (int i = 0; i < terminalIndex; i++) {
      // Invariant: Select optimization is only applied to structs (proto messages). Maps and
      // repeated fields are excluded from optimized field selection, so an absent intermediate
      // field is guaranteed to be an unset message field rather than a missing map key or
      // invalid field access, safely short-circuiting to false.
      Optional<Object> next = navigateField(current, fields.get(i));
      if (!next.isPresent()) {
        return false;
      }
      current = next.get();
    }
    return hasTerminalField(current, fields.get(terminalIndex));
  }

  // StructValue is only ever instantiated with String keys in the select path.
  @SuppressWarnings("unchecked")
  private static Object qualifyField(Object target, SelectField field) {
    if (target instanceof ErrorValue) {
      return target;
    }
    if (target instanceof OptimizedSelectable) {
      return ((OptimizedSelectable) target).selectByFieldNumber(field);
    }
    if (target instanceof StructValue) {
      StructValue<String, ?> selectable = (StructValue<String, ?>) target;
      if (field.defaultValue() != null) {
        return selectable
            .find(field.fieldName())
            .map(Object.class::cast)
            .orElse(field.defaultValue());
      }
      return selectable.select(field.fieldName());
    }
    throw CelAttributeNotFoundException.forFieldResolution(field.fieldName());
  }

  // StructValue is only ever instantiated with String keys in the select path.
  @SuppressWarnings("unchecked")
  private static Optional<Object> navigateField(Object target, SelectField field) {
    if (target instanceof ErrorValue) {
      return Optional.of(target);
    }
    if (target instanceof OptimizedSelectable) {
      return ((OptimizedSelectable) target).findByFieldNumber(field);
    }
    if (target instanceof StructValue) {
      return ((StructValue<String, ?>) target).find(field.fieldName()).map(Object.class::cast);
    }
    throw CelAttributeNotFoundException.forFieldResolution(field.fieldName());
  }

  // StructValue is only ever instantiated with String keys in the select path.
  @SuppressWarnings("unchecked")
  private static boolean hasTerminalField(Object target, SelectField field) {
    if (target instanceof ErrorValue) {
      return false;
    }
    if (target instanceof OptimizedSelectable) {
      return ((OptimizedSelectable) target).hasFieldByNumber(field);
    }
    if (target instanceof StructValue) {
      return ((StructValue<String, ?>) target).find(field.fieldName()).isPresent();
    }
    throw CelAttributeNotFoundException.forFieldResolution(field.fieldName());
  }

  private OptimizedSelectTraversal() {}
}
