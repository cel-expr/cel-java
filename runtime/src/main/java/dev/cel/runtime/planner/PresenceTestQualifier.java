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

  @Override
  @SuppressWarnings("unchecked") // SelectableValue cast is safe
  public Object qualify(Object obj) {
    if (obj instanceof SelectableValue) {
      return ((SelectableValue<Object>) obj).find(value).isPresent();
    } else if (obj instanceof Map) {
      Map<?, ?> map = (Map<?, ?>) obj;
      return map.containsKey(value);
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
