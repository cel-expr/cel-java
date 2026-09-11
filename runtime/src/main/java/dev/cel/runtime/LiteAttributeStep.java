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

package dev.cel.runtime;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.collect.ImmutableList;
import dev.cel.common.exceptions.CelAttributeNotFoundException;
import dev.cel.common.values.CelValueConverter;
import dev.cel.common.values.ErrorValue;
import dev.cel.common.values.NullValue;
import dev.cel.common.values.OptimizedSelectable;
import dev.cel.common.values.OptionalValue;
import dev.cel.common.values.SelectField;
import dev.cel.common.values.SelectableValue;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * LiteAttributeStep evaluates the optimized {@code cel.@attribute} and {@code cel.@hasField}
 * expressions emitted by {@code SelectOptimizer}.
 *
 * <p>Qualification and presence testing dispatch polymorphically over {@link OptimizedSelectable}
 * (for proto messages with descriptor-based or classless wire traversal), {@link SelectableValue},
 * and {@link Map}.
 *
 * <p>CEL Library Internals. Do Not Use.
 */
final class LiteAttributeStep {

  /**
   * Qualifies an attribute by applying each qualifier in {@code qualifierLists} in order.
   *
   * <p>Each qualifier is a {@code [field_number, field_name, type_code]} 3-tuple (for submessages),
   * or a {@code [field_number, field_name, type_code, default_value]} 4-tuple (for leaf fields with
   * pre-resolved default values).
   */
  static @Nullable Object qualifyAttribute(
      @Nullable Object target, List<?> qualifierLists, CelValueConverter celValueConverter) {
    checkNotNull(qualifierLists);
    checkNotNull(celValueConverter);

    ImmutableList<SelectField> fields = toSelectFields(qualifierLists);

    if (target instanceof CelUnknownSet
        || target instanceof ErrorValue
        || target instanceof Exception) {
      return target;
    }

    if (target == null || target instanceof NullValue) {
      if (fields.isEmpty()) {
        return target;
      }
      throw CelAttributeNotFoundException.forFieldResolution(fields.get(0).fieldName());
    }

    if (fields.isEmpty()) {
      return celValueConverter.maybeUnwrap(target);
    }

    Object runtimeTarget = toStepTarget(target, celValueConverter);
    if (runtimeTarget instanceof OptionalValue) {
      throw new UnsupportedOperationException(
          "Optional operands are not yet supported by the lite select-optimized runtime");
    }

    if (runtimeTarget instanceof OptimizedSelectable) {
      Object result = ((OptimizedSelectable) runtimeTarget).qualify(fields);
      return celValueConverter.maybeUnwrap(result);
    }

    Object obj = runtimeTarget;
    for (SelectField field : fields) {
      if (obj instanceof OptimizedSelectable) {
        obj = ((OptimizedSelectable) obj).selectField(field);
      } else if (obj instanceof SelectableValue) {
        Optional<Object> found = find((SelectableValue<?>) obj, field.fieldName());
        if (found.isPresent()) {
          obj = toStepTarget(found.get(), celValueConverter);
        } else if (field.defaultValue() != null) {
          obj = field.defaultValue();
        } else {
          throw CelAttributeNotFoundException.forFieldResolution(field.fieldName());
        }
      } else if (obj instanceof Map) {
        Map<?, ?> map = (Map<?, ?>) obj;
        Object mapValue = map.get(field.fieldName());
        if (mapValue != null) {
          obj = toStepTarget(mapValue, celValueConverter);
        } else if (map.containsKey(field.fieldName())) {
          obj = NullValue.NULL_VALUE;
        } else {
          throw CelAttributeNotFoundException.forMissingMapKey(field.fieldName());
        }
      } else {
        throw CelAttributeNotFoundException.forFieldResolution(field.fieldName());
      }
    }

    if (obj instanceof Map) {
      obj = celValueConverter.toRuntimeValue(obj);
    }
    return celValueConverter.maybeUnwrap(obj);
  }

  /**
   * Tests presence of an attribute by navigating the leading qualifiers in {@code qualifierLists}
   * and presence testing the last one.
   *
   * <p>Each qualifier is a {@code [field_number, field_name]} pair.
   */
  static Object hasField(
      @Nullable Object target, List<?> qualifierLists, CelValueConverter celValueConverter) {
    checkNotNull(qualifierLists);
    checkNotNull(celValueConverter);

    ImmutableList<SelectField> fields = toPresenceFields(qualifierLists);

    if (target instanceof CelUnknownSet
        || target instanceof ErrorValue
        || target instanceof Exception) {
      return target;
    }

    if (fields.isEmpty()) {
      return false;
    }

    if (target == null || target instanceof NullValue) {
      throw CelAttributeNotFoundException.forFieldResolution(fields.get(0).fieldName());
    }

    Object runtimeTarget = toStepTarget(target, celValueConverter);
    if (runtimeTarget instanceof OptionalValue) {
      throw new UnsupportedOperationException(
          "Optional operands are not yet supported by the lite select-optimized runtime");
    }

    if (runtimeTarget instanceof OptimizedSelectable) {
      return ((OptimizedSelectable) runtimeTarget).hasField(fields);
    }

    int lastIndex = fields.size() - 1;
    Object obj = runtimeTarget;
    for (int i = 0; i < lastIndex; i++) {
      SelectField field = fields.get(i);
      if (obj instanceof OptimizedSelectable) {
        obj = ((OptimizedSelectable) obj).navigateField(field);
        if (obj == null) {
          return false;
        }
      } else if (obj instanceof SelectableValue) {
        Optional<Object> found = find((SelectableValue<?>) obj, field.fieldName());
        if (!found.isPresent()) {
          return false;
        }
        obj = toStepTarget(found.get(), celValueConverter);
      } else if (obj instanceof Map) {
        Map<?, ?> map = (Map<?, ?>) obj;
        if (!map.containsKey(field.fieldName())) {
          return false;
        }
        Object mapValue = map.get(field.fieldName());
        obj = (mapValue != null) ? toStepTarget(mapValue, celValueConverter) : NullValue.NULL_VALUE;
      } else {
        throw CelAttributeNotFoundException.forFieldResolution(field.fieldName());
      }
    }

    SelectField terminalField = fields.get(lastIndex);
    if (obj instanceof OptimizedSelectable) {
      return ((OptimizedSelectable) obj).hasField(terminalField);
    } else if (obj instanceof SelectableValue) {
      return find((SelectableValue<?>) obj, terminalField.fieldName()).isPresent();
    } else if (obj instanceof Map) {
      return ((Map<?, ?>) obj).containsKey(terminalField.fieldName());
    } else {
      throw CelAttributeNotFoundException.forFieldResolution(terminalField.fieldName());
    }
  }

  private static Object toStepTarget(Object value, CelValueConverter celValueConverter) {
    if (value instanceof Map) {
      return value;
    }
    return celValueConverter.toRuntimeValue(value);
  }

  @SuppressWarnings("unchecked") // Structs and maps qualified by a select chain are String keyed.
  private static Optional<Object> find(SelectableValue<?> selectable, String fieldName) {
    return (Optional<Object>) ((SelectableValue<String>) selectable).find(fieldName);
  }

  private static SelectField toSelectField(Object item) {
    List<?> qualifier = asQualifier(item, /* minSize= */ 3);
    long fieldNumber = fieldNumberOf(qualifier);
    String fieldName = fieldNameOf(qualifier);
    int typeCode = typeCodeOf(qualifier);
    Object defaultValue = defaultValueOf(qualifier);
    return SelectField.create(fieldNumber, fieldName, typeCode, defaultValue);
  }

  private static SelectField toPresenceField(Object item) {
    List<?> qualifier = asQualifier(item, /* minSize= */ 2);
    long fieldNumber = fieldNumberOf(qualifier);
    String fieldName = fieldNameOf(qualifier);
    if (qualifier.size() >= 3) {
      int typeCode = typeCodeOf(qualifier);
      Object defaultValue = defaultValueOf(qualifier);
      return SelectField.create(fieldNumber, fieldName, typeCode, defaultValue);
    }
    return SelectField.create(fieldNumber, fieldName);
  }

  private static ImmutableList<SelectField> toSelectFields(List<?> qualifierLists) {
    ImmutableList.Builder<SelectField> builder =
        ImmutableList.builderWithExpectedSize(qualifierLists.size());
    for (Object item : qualifierLists) {
      builder.add(toSelectField(item));
    }
    return builder.build();
  }

  private static ImmutableList<SelectField> toPresenceFields(List<?> qualifierLists) {
    ImmutableList.Builder<SelectField> builder =
        ImmutableList.builderWithExpectedSize(qualifierLists.size());
    for (Object item : qualifierLists) {
      builder.add(toPresenceField(item));
    }
    return builder.build();
  }

  /** Validates and returns a single {@code [field_number, field_name, ...]} qualifier tuple. */
  private static List<?> asQualifier(Object item, int minSize) {
    if (!(item instanceof List)) {
      throw new IllegalArgumentException("Expected qualifier list, got: " + item);
    }
    List<?> qualifier = (List<?>) item;
    if (qualifier.size() < minSize
        || !isInteger(qualifier.get(0))
        || !(qualifier.get(1) instanceof String)
        || (minSize > 2 && !isInteger(qualifier.get(2)))) {
      throw new IllegalArgumentException("Invalid qualifier format: " + qualifier);
    }
    return qualifier;
  }

  private static boolean isInteger(Object raw) {
    return raw instanceof Long
        || raw instanceof Integer
        || raw instanceof Short
        || raw instanceof Byte;
  }

  private static long fieldNumberOf(List<?> qualifier) {
    Object raw = qualifier.get(0);
    if (isInteger(raw)) {
      long fieldNumber = ((Number) raw).longValue();
      if (fieldNumber <= 0 || fieldNumber > SelectField.MAX_FIELD_NUMBER) {
        throw new IllegalArgumentException(
            "Invalid protobuf field number: "
                + fieldNumber
                + " (must be between 1 and "
                + SelectField.MAX_FIELD_NUMBER
                + ")");
      }
      return fieldNumber;
    }
    throw new IllegalArgumentException(
        "Expected integer field number in qualifier[0], got: " + raw);
  }

  private static String fieldNameOf(List<?> qualifier) {
    return (String) qualifier.get(1);
  }

  private static int typeCodeOf(List<?> qualifier) {
    Object raw = qualifier.get(2);
    if (isInteger(raw)) {
      long typeCode = ((Number) raw).longValue();
      if (typeCode != SelectField.CEL_MAP_TYPE_CODE && (typeCode < 1 || typeCode > 18)) {
        throw new IllegalArgumentException("Invalid protobuf type code: " + typeCode);
      }
      return (int) typeCode;
    }
    throw new IllegalArgumentException("Expected integer type code in qualifier[2], got: " + raw);
  }

  /**
   * Returns the pre-resolved default value from a 4-tuple qualifier {@code [field_number,
   * field_name, type_code, default_value]}, or {@code null} for 3-tuple qualifiers (submessages
   * without an inlined default).
   */
  private static @Nullable Object defaultValueOf(List<?> qualifier) {
    if (qualifier.size() > 3) {
      return qualifier.get(3);
    }
    return null;
  }

  private LiteAttributeStep() {}
}
