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

import com.google.errorprone.annotations.Immutable;
import dev.cel.common.exceptions.CelAttributeNotFoundException;
import dev.cel.common.values.CelValueConverter;
import dev.cel.common.values.OptionalValue;
import dev.cel.common.values.SelectableValue;
import java.util.Map;

/** A qualifier that accesses fields or map keys using a string identifier. */
@Immutable
final class StringQualifier implements Qualifier {

  private final String value;
  private final CelValueConverter celValueConverter;

  @Override
  public String value() {
    return value;
  }

  @Override
  public Object qualify(Object operand) {
    // Single exit point: the map branch below surfaces a raw entry, so the result is adapted here
    // rather than in each branch, which keeps the traversal-target contract impossible to miss.
    return celValueConverter.toTraversalTarget(select(operand));
  }

  @SuppressWarnings("unchecked") // Qualifications on maps/structs must be a string
  private Object select(Object obj) {
    if (obj instanceof OptionalValue) {
      OptionalValue<?, ?> opt = (OptionalValue<?, ?>) obj;
      if (!opt.isZeroValue()) {
        Object inner = opt.value();
        if (!(inner instanceof SelectableValue) && !(inner instanceof Map)) {
          throw CelAttributeNotFoundException.forFieldResolution(value);
        }
      }
    }

    if (obj instanceof SelectableValue) {
      return ((SelectableValue<String>) obj).select(value);
    }

    if (obj instanceof Map) {
      return CelValueConverter.findMapValue((Map<?, ?>) obj, value)
          .orElseThrow(() -> CelAttributeNotFoundException.forMissingMapKey(value));
    }

    throw CelAttributeNotFoundException.forFieldResolution(value);
  }

  static StringQualifier create(String value, CelValueConverter celValueConverter) {
    return new StringQualifier(value, celValueConverter);
  }

  private StringQualifier(String value, CelValueConverter celValueConverter) {
    this.value = value;
    this.celValueConverter = celValueConverter;
  }
}
