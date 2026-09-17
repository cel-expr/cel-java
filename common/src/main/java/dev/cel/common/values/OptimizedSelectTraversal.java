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
import java.util.Map;
import java.util.Optional;

/**
 * Walks a sequence of {@link SelectField} selections, dispatching each field over {@link
 * OptimizedSelectable}, {@link SelectableValue}, or {@link Map}.
 *
 * <p>CEL Library Internals. Do Not Use.
 */
@Internal
public final class OptimizedSelectTraversal {

  /**
   * Qualifies {@code target} through every field in {@code fields} and returns the terminal value.
   *
   * @param celValueConverter Converter for unadapted entries encountered in a root {@link Map}.
   */
  public static Object qualify(
      Object target, ImmutableList<SelectField> fields, CelValueConverter celValueConverter) {
    Object current = target;
    for (int i = 0; i < fields.size(); i++) {
      current = qualifyField(current, fields.get(i), celValueConverter);
    }
    return current;
  }

  /**
   * Presence tests the terminal field of {@code fields}, navigating through all preceding fields.
   *
   * <p>Absence of any intermediate field short-circuits to {@code false}.
   */
  public static boolean hasField(
      Object target, ImmutableList<SelectField> fields, CelValueConverter celValueConverter) {
    if (fields.isEmpty()) {
      return false;
    }
    Object current = target;
    int terminalIndex = fields.size() - 1;
    for (int i = 0; i < terminalIndex; i++) {
      Optional<Object> next = navigateField(current, fields.get(i), celValueConverter);
      if (!next.isPresent()) {
        return false;
      }
      current = next.get();
    }
    return hasTerminalField(current, fields.get(terminalIndex));
  }

  // SelectableValue is only ever instantiated with String keys in the select path.
  @SuppressWarnings("unchecked")
  private static Object qualifyField(
      Object target, SelectField field, CelValueConverter celValueConverter) {
    if (target instanceof ErrorValue) {
      return target;
    }
    if (target instanceof OptimizedSelectable) {
      return ((OptimizedSelectable) target).selectByFieldNumber(field);
    }
    if (target instanceof SelectableValue) {
      SelectableValue<String> selectable = (SelectableValue<String>) target;
      if (field.defaultValue() != null) {
        return selectable
            .find(field.fieldName())
            .map(Object.class::cast)
            .orElse(field.defaultValue());
      }
      return selectable.select(field.fieldName());
    }
    if (target instanceof Map) {
      return getMapEntry((Map<?, ?>) target, field.fieldName(), celValueConverter);
    }
    throw CelAttributeNotFoundException.forFieldResolution(field.fieldName());
  }

  // SelectableValue is only ever instantiated with String keys in the select path.
  @SuppressWarnings("unchecked")
  private static Optional<Object> navigateField(
      Object target, SelectField field, CelValueConverter celValueConverter) {
    if (target instanceof ErrorValue) {
      return Optional.of(target);
    }
    if (target instanceof OptimizedSelectable) {
      return ((OptimizedSelectable) target).findByFieldNumber(field);
    }
    if (target instanceof SelectableValue) {
      return ((SelectableValue<String>) target).find(field.fieldName()).map(Object.class::cast);
    }
    if (target instanceof Map) {
      return findMapEntry((Map<?, ?>) target, field.fieldName(), celValueConverter);
    }
    throw CelAttributeNotFoundException.forFieldResolution(field.fieldName());
  }

  // SelectableValue is only ever instantiated with String keys in the select path.
  @SuppressWarnings("unchecked")
  private static boolean hasTerminalField(Object target, SelectField field) {
    if (target instanceof ErrorValue) {
      return false;
    }
    if (target instanceof OptimizedSelectable) {
      return ((OptimizedSelectable) target).hasFieldByNumber(field);
    }
    if (target instanceof SelectableValue) {
      return ((SelectableValue<String>) target).find(field.fieldName()).isPresent();
    }
    if (target instanceof Map) {
      return ((Map<?, ?>) target).containsKey(field.fieldName());
    }
    throw CelAttributeNotFoundException.forFieldResolution(field.fieldName());
  }

  private static Object getMapEntry(
      Map<?, ?> map, String key, CelValueConverter celValueConverter) {
    return findMapEntry(map, key, celValueConverter)
        .orElseThrow(() -> CelAttributeNotFoundException.forMissingMapKey(key));
  }

  private static Optional<Object> findMapEntry(
      Map<?, ?> map, String key, CelValueConverter celValueConverter) {
    Object mapValue = map.get(key);
    if (mapValue != null) {
      return Optional.of(toStepTarget(mapValue, celValueConverter));
    }
    if (!map.containsKey(key)) {
      return Optional.empty();
    }
    throw CelAttributeNotFoundException.of(
        String.format("Map value cannot be null for key: %s", key));
  }

  static Object toStepTarget(Object value, CelValueConverter celValueConverter) {
    if (value instanceof Map) {
      return value;
    }
    return celValueConverter.toRuntimeValue(value);
  }

  private OptimizedSelectTraversal() {}
}
