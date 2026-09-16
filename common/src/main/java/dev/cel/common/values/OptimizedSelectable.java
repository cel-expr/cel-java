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
import com.google.errorprone.annotations.Immutable;
import dev.cel.common.annotations.Internal;
import dev.cel.common.exceptions.CelAttributeNotFoundException;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Represents a value capable of evaluating optimized field selection and presence testing for field
 * selection optimization.
 *
 * <p>CEL Library Internals. Do Not Use.
 */
@Internal
@Immutable
public interface OptimizedSelectable {

  /**
   * Evaluates a multi-hop qualification chain across the provided fields.
   *
   * <p>Implementations may override this method to provide an allocation-free traversal over
   * underlying schema or message descriptors. The default implementation iterates sequentially over
   * {@link #selectField} for intermediate submessages and the leaf.
   */
  default Object qualify(ImmutableList<SelectField> fields) {
    Object current = this;
    for (SelectField field : fields) {
      if (current instanceof OptimizedSelectable) {
        current = ((OptimizedSelectable) current).selectField(field);
      } else if (current instanceof SelectableValue) {
        Optional<Object> found =
            SelectField.findField((SelectableValue<?>) current, field.fieldName());
        if (found.isPresent()) {
          current = found.get();
        } else if (field.defaultValue() != null) {
          current = field.defaultValue();
        } else {
          throw CelAttributeNotFoundException.forFieldResolution(field.fieldName());
        }
      } else if (current instanceof Map) {
        Map<?, ?> map = (Map<?, ?>) current;
        Object mapValue = map.get(field.fieldName());
        if (mapValue != null) {
          current = mapValue;
        } else if (map.containsKey(field.fieldName())) {
          current = NullValue.NULL_VALUE;
        } else {
          throw CelAttributeNotFoundException.forMissingMapKey(field.fieldName());
        }
      } else {
        throw CelAttributeNotFoundException.forFieldResolution(field.fieldName());
      }
    }
    return current;
  }

  /**
   * Performs field selection for a single hop in an optimized selection chain.
   *
   * @param field The field hop descriptor containing field number, name, type code, and default
   *     value.
   * @return The selected field value, or default value / empty submessage if absent.
   */
  Object selectField(SelectField field);

  /**
   * Evaluates presence testing across the provided fields.
   *
   * <p>Implementations may override this method to provide an allocation-free traversal over
   * underlying schema or message descriptors. The default implementation iterates sequentially over
   * {@link #navigateField} for intermediate submessages and {@link #hasField} for the leaf.
   */
  default boolean hasField(ImmutableList<SelectField> fields) {
    if (fields.isEmpty()) {
      return false;
    }
    int lastIndex = fields.size() - 1;
    Object current = this;
    for (int i = 0; i < lastIndex; i++) {
      SelectField field = fields.get(i);
      if (current instanceof OptimizedSelectable) {
        current = ((OptimizedSelectable) current).navigateField(field);
        if (current == null) {
          return false;
        }
      } else if (current instanceof SelectableValue) {
        Optional<Object> found =
            SelectField.findField((SelectableValue<?>) current, field.fieldName());
        if (!found.isPresent()) {
          return false;
        }
        current = found.get();
      } else if (current instanceof Map) {
        Map<?, ?> map = (Map<?, ?>) current;
        if (!map.containsKey(field.fieldName())) {
          return false;
        }
        Object mapValue = map.get(field.fieldName());
        current = (mapValue != null) ? mapValue : NullValue.NULL_VALUE;
      } else {
        throw CelAttributeNotFoundException.forFieldResolution(field.fieldName());
      }
    }
    SelectField terminalField = fields.get(lastIndex);
    if (current instanceof OptimizedSelectable) {
      return ((OptimizedSelectable) current).hasField(terminalField);
    } else if (current instanceof SelectableValue) {
      return SelectField.findField((SelectableValue<?>) current, terminalField.fieldName())
          .isPresent();
    } else if (current instanceof Map) {
      return ((Map<?, ?>) current).containsKey(terminalField.fieldName());
    } else {
      throw CelAttributeNotFoundException.forFieldResolution(terminalField.fieldName());
    }
  }

  /**
   * Evaluates presence testing for a single hop in an optimized selection chain.
   *
   * @param field The field hop descriptor.
   * @return True if the field is present, false otherwise.
   */
  boolean hasField(SelectField field);

  /**
   * Navigates into an intermediate submessage for presence testing.
   *
   * <p>Returns {@code null} instead of an {@link Optional} to avoid object allocation overhead on
   * hot-path intermediate navigation hops.
   *
   * @param field The field hop descriptor.
   * @return The submessage if present, or {@code null} if absent.
   */
  @Nullable Object navigateField(SelectField field);
}
