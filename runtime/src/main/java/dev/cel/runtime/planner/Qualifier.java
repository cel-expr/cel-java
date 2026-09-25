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
import dev.cel.common.values.CelValueConverter;

/**
 * Represents a qualification step (such as a field selection or map key lookup) applied to an
 * intermediate value during attribute resolution.
 */
@Immutable
interface Qualifier {
  /**
   * Returns the qualifier value (such as a field name or map key) identifying this qualification
   * step.
   */
  Object value();

  /**
   * Applies this qualification step to {@code operand} and returns the qualified value.
   *
   * <p>Both the input {@code operand} and the returned value must be traversal targets (see {@link
   * CelValueConverter#toTraversalTarget}).
   */
  Object qualify(Object operand);
}
