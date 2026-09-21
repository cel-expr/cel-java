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

import com.google.errorprone.annotations.Immutable;
import dev.cel.common.annotations.Internal;
import java.util.Optional;

/**
 * Resolves an optimized field selection within a selection chain rewritten by the select optimizer.
 *
 * <p>Implementations resolve individual field selections against themselves by protobuf field
 * number. Walking the chain across multiple fields and heterogeneous values belongs to {@link
 * OptimizedSelectTraversal}.
 *
 * <p>CEL Library Internals. Do Not Use.
 */
@Internal
@Immutable
public interface OptimizedSelectable {

  /** Selects {@code field}, falling back to its default value or an empty submessage if absent. */
  Object selectByFieldNumber(SelectField field);

  /** Returns whether {@code field} is present. */
  boolean hasFieldByNumber(SelectField field);

  /**
   * Returns the value of the field at {@code field} (a scalar or submessage) for an intermediate
   * step of a presence test, or empty if absent.
   */
  Optional<Object> findByFieldNumber(SelectField field);
}
