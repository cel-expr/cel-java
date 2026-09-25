// Copyright 2025 Google LLC
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

package dev.cel.runtime.planner;

import static dev.cel.runtime.planner.MissingAttribute.newMissingField;

import com.google.errorprone.annotations.Immutable;
import dev.cel.common.values.CelValueConverter;
import dev.cel.common.values.SelectableValue;
import java.util.Map;

/** A qualifier for presence testing a field or a map key. */
@Immutable
final class PresenceTestQualifier implements Qualifier {

  @SuppressWarnings("Immutable")
  private final Object value;

  @Override
  public Object value() {
    return value;
  }

  /**
   * Returns a boolean, or a {@link MissingAttribute} sentinel when the operand cannot be presence
   * tested. Both are already traversal targets, so no adaptation is required.
   *
   * <p>Throws {@code CelInvalidArgumentException} when the key is bound to an illegal Java {@code
   * null}. The type is named in prose rather than an {@code @throws} tag so that this package need
   * not depend on the exception target purely for documentation.
   */
  @Override
  @SuppressWarnings("unchecked") // SelectableValue cast is safe
  public Object qualify(Object operand) {
    if (operand instanceof SelectableValue) {
      return ((SelectableValue<Object>) operand).find(value).isPresent();
    } else if (operand instanceof Map) {
      return CelValueConverter.containsMapKey((Map<?, ?>) operand, value);
    }

    return newMissingField(value.toString());
  }

  static PresenceTestQualifier create(Object value) {
    return new PresenceTestQualifier(value);
  }

  private PresenceTestQualifier(Object value) {
    this.value = value;
  }
}
