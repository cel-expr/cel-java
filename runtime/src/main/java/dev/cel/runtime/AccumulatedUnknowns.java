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

package dev.cel.runtime;

import com.google.errorprone.annotations.CanIgnoreReturnValue;
import dev.cel.common.annotations.Internal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * An internal representation used for fast accumulation of unknown expr IDs and attributes. For
 * safety, this object should never be returned as an evaluated result and instead be adapted into
 * an immutable CelUnknownSet.
 *
 * <p>CEL Library Internals. Do Not Use.
 */
@Internal
public final class AccumulatedUnknowns {
  private static final int MAX_UNKNOWN_ATTRIBUTE_SIZE = 500_000;
  private final Set<Long> exprIds;
  private final Set<CelAttribute> attributes;

  public Set<Long> exprIds() {
    return Collections.unmodifiableSet(exprIds);
  }

  public Set<CelAttribute> attributes() {
    return Collections.unmodifiableSet(attributes);
  }

  /**
   * Evaluates if the right hand side is an accumulated unknown, and if so, merges it into the
   * accumulator.
   */
  public static @Nullable AccumulatedUnknowns maybeMerge(
      @Nullable AccumulatedUnknowns accumulator, Object newValue) {
    if (newValue instanceof AccumulatedUnknowns) {
      AccumulatedUnknowns newUnknowns = (AccumulatedUnknowns) newValue;
      return accumulator == null ? newUnknowns : accumulator.merge(newUnknowns);
    }
    return accumulator;
  }

  @CanIgnoreReturnValue
  public AccumulatedUnknowns merge(AccumulatedUnknowns arg) {
    enforceMaxAttributeSize(this.attributes, arg.attributes);
    this.exprIds.addAll(arg.exprIds);
    this.attributes.addAll(arg.attributes);
    return this;
  }

  static AccumulatedUnknowns create(Long... ids) {
    return create(Arrays.asList(ids));
  }

  static AccumulatedUnknowns create(Collection<Long> ids) {
    return create(ids, new ArrayList<>());
  }

  public static AccumulatedUnknowns create(
      Collection<Long> exprIds, Collection<CelAttribute> attributes) {
    return new AccumulatedUnknowns(new HashSet<>(exprIds), new HashSet<>(attributes));
  }

  private static void enforceMaxAttributeSize(
      Set<CelAttribute> lhsAttributes, Set<CelAttribute> rhsAttributes) {
    if (lhsAttributes.size() + rhsAttributes.size() > MAX_UNKNOWN_ATTRIBUTE_SIZE) {
      throw new IllegalArgumentException(
          String.format(
              "Exceeded maximum allowed unknown attributes when merging: %s, %s",
              lhsAttributes.size(), rhsAttributes.size()));
    }
  }

  private AccumulatedUnknowns(Set<Long> exprIds, Set<CelAttribute> attributes) {
    this.exprIds = exprIds;
    this.attributes = attributes;
  }
}
