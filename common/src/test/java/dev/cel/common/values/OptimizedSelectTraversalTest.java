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

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import dev.cel.common.exceptions.CelAttributeNotFoundException;
import java.util.Map;
import java.util.Optional;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(TestParameterInjector.class)
public final class OptimizedSelectTraversalTest {

  private enum TargetType {
    OPTIMIZED_SELECTABLE {
      @Override
      Object createTarget(Map<String, Object> data) {
        return new FakeOptimizedSelectable(data);
      }

      @Override
      Object createNestedTarget(Map<String, Object> innerData) {
        return new FakeOptimizedSelectable(
            ImmutableMap.of("outer_key", new FakeOptimizedSelectable(innerData)));
      }
    },
    SELECTABLE_VALUE {
      @Override
      Object createTarget(Map<String, Object> data) {
        return new FakeSelectableValue(data);
      }

      @Override
      Object createNestedTarget(Map<String, Object> innerData) {
        return new FakeSelectableValue(
            ImmutableMap.of("outer_key", new FakeSelectableValue(innerData)));
      }
    };

    abstract Object createTarget(Map<String, Object> data);

    abstract Object createNestedTarget(Map<String, Object> innerData);
  }

  @SuppressWarnings("Immutable")
  private enum NestedPresenceTestCase {
    ALL_PRESENT(
        ImmutableMap.of("inner_key", "nested_val"), "outer_key", "inner_key", /* expected= */ true),
    INTERMEDIATE_MISSING(
        ImmutableMap.of("inner_key", "nested_val"),
        "missing_outer",
        "inner_key",
        /* expected= */ false),
    TERMINAL_MISSING(
        ImmutableMap.of("other_key", "nested_val"),
        "outer_key",
        "missing_terminal",
        /* expected= */ false);

    final ImmutableMap<String, Object> innerData;
    final String outerField;
    final String innerField;
    final boolean expected;

    NestedPresenceTestCase(
        ImmutableMap<String, Object> innerData,
        String outerField,
        String innerField,
        boolean expected) {
      this.innerData = innerData;
      this.outerField = outerField;
      this.innerField = innerField;
      this.expected = expected;
    }
  }

  @Test
  public void qualify_emptyFields_returnsTargetInstance() {
    Object target = new Object();

    Object result = OptimizedSelectTraversal.qualify(target, ImmutableList.of());

    assertThat(result).isSameInstanceAs(target);
  }

  @Test
  public void qualify_singleField_success(@TestParameter TargetType targetType) {
    Object target = targetType.createTarget(ImmutableMap.of("key", "value"));
    ImmutableList<SelectField> fields = ImmutableList.of(SelectField.create(1L, "key", 9, ""));

    Object result = OptimizedSelectTraversal.qualify(target, fields);

    assertThat(result).isEqualTo("value");
  }

  @Test
  public void qualify_nested_success(@TestParameter TargetType targetType) {
    Object target = targetType.createNestedTarget(ImmutableMap.of("inner_key", "nested_value"));
    ImmutableList<SelectField> fields =
        ImmutableList.of(
            SelectField.create(1L, "outer_key", 11, null),
            SelectField.create(2L, "inner_key", 9, ""));

    Object result = OptimizedSelectTraversal.qualify(target, fields);

    assertThat(result).isEqualTo("nested_value");
  }

  @Test
  public void qualify_singleField_missingThrowsException(@TestParameter TargetType targetType) {
    Object target = targetType.createTarget(ImmutableMap.of("present", "value"));
    ImmutableList<SelectField> fields =
        ImmutableList.of(SelectField.create(1L, "missing", 9, null));

    CelAttributeNotFoundException thrown =
        assertThrows(
            CelAttributeNotFoundException.class,
            () -> OptimizedSelectTraversal.qualify(target, fields));

    assertThat(thrown).hasMessageThat().contains("missing");
  }

  @Test
  public void qualify_optimizedSelectable_absentWithDefaultValue_returnsDefault() {
    FakeOptimizedSelectable selectable = new FakeOptimizedSelectable(ImmutableMap.of());
    ImmutableList<SelectField> fields =
        ImmutableList.of(SelectField.create(1L, "absent", 9, "default_fallback"));

    Object result = OptimizedSelectTraversal.qualify(selectable, fields);

    assertThat(result).isEqualTo("default_fallback");
  }

  @Test
  public void qualify_selectableValue_absentWithDefaultValue_returnsDefault() {
    FakeSelectableValue selectable = new FakeSelectableValue(ImmutableMap.of());
    ImmutableList<SelectField> fields =
        ImmutableList.of(SelectField.create(1L, "absent", 9, "default_fallback"));

    Object result = OptimizedSelectTraversal.qualify(selectable, fields);

    assertThat(result).isEqualTo("default_fallback");
  }

  @Test
  public void qualify_unsupportedTarget_throwsException() {
    ImmutableList<SelectField> fields = ImmutableList.of(SelectField.create(1L, "invalid_field"));

    CelAttributeNotFoundException thrown =
        assertThrows(
            CelAttributeNotFoundException.class,
            () -> OptimizedSelectTraversal.qualify(12345L, fields));

    assertThat(thrown).hasMessageThat().contains("invalid_field");
  }

  @Test
  public void qualify_intermediateUnsupportedTarget_throwsException() {
    FakeOptimizedSelectable target = new FakeOptimizedSelectable(ImmutableMap.of("scalar", 999L));
    ImmutableList<SelectField> fields =
        ImmutableList.of(
            SelectField.create(1L, "scalar", 3, 0L), SelectField.create(2L, "unreachable", 9, ""));

    CelAttributeNotFoundException thrown =
        assertThrows(
            CelAttributeNotFoundException.class,
            () -> OptimizedSelectTraversal.qualify(target, fields));

    assertThat(thrown).hasMessageThat().contains("unreachable");
  }

  @Test
  public void hasField_emptyFields_returnsFalse() {
    Object target = new Object();

    boolean hasField = OptimizedSelectTraversal.hasField(target, ImmutableList.of());

    assertThat(hasField).isFalse();
  }

  @Test
  public void hasField_singleField(
      @TestParameter TargetType targetType,
      @TestParameter({"present_key", "missing_key"}) String queryKey) {
    Object target = targetType.createTarget(ImmutableMap.of("present_key", "val"));
    ImmutableList<SelectField> fields = ImmutableList.of(SelectField.create(1L, queryKey));

    boolean hasField = OptimizedSelectTraversal.hasField(target, fields);

    assertThat(hasField).isEqualTo(queryKey.equals("present_key"));
  }

  @Test
  public void hasField_nestedFields(
      @TestParameter TargetType targetType, @TestParameter NestedPresenceTestCase testCase) {
    Object target = targetType.createNestedTarget(testCase.innerData);
    ImmutableList<SelectField> fields =
        ImmutableList.of(
            SelectField.create(1L, testCase.outerField),
            SelectField.create(2L, testCase.innerField));

    boolean hasField = OptimizedSelectTraversal.hasField(target, fields);

    assertThat(hasField).isEqualTo(testCase.expected);
  }

  @Test
  public void hasField_intermediateUnsupportedTarget_throwsException() {
    FakeOptimizedSelectable target =
        new FakeOptimizedSelectable(ImmutableMap.of("scalar_key", 100L));
    ImmutableList<SelectField> fields =
        ImmutableList.of(SelectField.create(1L, "scalar_key"), SelectField.create(2L, "child_key"));

    CelAttributeNotFoundException thrown =
        assertThrows(
            CelAttributeNotFoundException.class,
            () -> OptimizedSelectTraversal.hasField(target, fields));

    assertThat(thrown).hasMessageThat().contains("child_key");
  }

  @Test
  public void hasField_unsupportedTarget_throwsException() {
    ImmutableList<SelectField> fields = ImmutableList.of(SelectField.create(1L, "invalid_field"));

    CelAttributeNotFoundException thrown =
        assertThrows(
            CelAttributeNotFoundException.class,
            () -> OptimizedSelectTraversal.hasField(12345L, fields));

    assertThat(thrown).hasMessageThat().contains("invalid_field");
  }

  @Test
  public void qualify_errorValue_propagatesError() {
    ErrorValue error = ErrorValue.create(1L, new RuntimeException("test error"));
    ImmutableList<SelectField> fields =
        ImmutableList.of(SelectField.create(1L, "field1"), SelectField.create(2L, "field2"));

    Object result = OptimizedSelectTraversal.qualify(error, fields);

    assertThat(result).isSameInstanceAs(error);
  }

  @Test
  public void hasField_errorValue_returnsFalse() {
    ErrorValue error = ErrorValue.create(1L, new RuntimeException("test error"));
    ImmutableList<SelectField> fields =
        ImmutableList.of(SelectField.create(1L, "field1"), SelectField.create(2L, "field2"));

    boolean result = OptimizedSelectTraversal.hasField(error, fields);

    assertThat(result).isFalse();
  }

  @SuppressWarnings("Immutable")
  private static final class FakeOptimizedSelectable implements OptimizedSelectable {
    private final ImmutableMap<String, Object> values;

    @Override
    public Object selectByFieldNumber(SelectField field) {
      Object value = values.get(field.fieldName());
      if (value != null) {
        return value;
      }
      if (field.defaultValue() != null) {
        return field.defaultValue();
      }
      throw CelAttributeNotFoundException.forFieldResolution(field.fieldName());
    }

    @Override
    public boolean hasFieldByNumber(SelectField field) {
      return values.containsKey(field.fieldName());
    }

    @Override
    public Optional<Object> findByFieldNumber(SelectField field) {
      return Optional.ofNullable(values.get(field.fieldName()));
    }

    FakeOptimizedSelectable(Map<String, Object> values) {
      this.values = ImmutableMap.copyOf(values);
    }
  }

  @SuppressWarnings("Immutable")
  private static final class FakeSelectableValue implements SelectableValue<String> {
    private final ImmutableMap<String, Object> values;

    @Override
    public Object select(String field) {
      Object value = values.get(field);
      if (value != null) {
        return value;
      }
      throw CelAttributeNotFoundException.forFieldResolution(field);
    }

    @Override
    public Optional<Object> find(String field) {
      return Optional.ofNullable(values.get(field));
    }

    FakeSelectableValue(Map<String, Object> values) {
      this.values = ImmutableMap.copyOf(values);
    }
  }
}
