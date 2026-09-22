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
  private final Set<Long> callIds;

  Set<Long> exprIds() {
    return exprIds;
  }

  Set<CelAttribute> attributes() {
    return attributes;
  }

  /**
   * Returns the in-flight asynchronous call IDs this unknown is waiting on.
   *
   * <p>The returned set is an unmodifiable <em>view</em> over this mutable accumulator, not a
   * snapshot: a subsequent {@link #merge} on this instance is visible through it. Callers that
   * retain the set beyond the current evaluation step must copy it.
   */
  public Set<Long> callIds() {
    return Collections.unmodifiableSet(callIds);
  }

  /**
   * Evaluates if either value is an accumulated unknown, and if so, merges them into a single
   * accumulator.
   */
  public static @Nullable AccumulatedUnknowns maybeMerge(@Nullable Object val1, Object val2) {
    AccumulatedUnknowns accumulator =
        val1 instanceof AccumulatedUnknowns ? (AccumulatedUnknowns) val1 : null;
    if (val2 instanceof AccumulatedUnknowns) {
      AccumulatedUnknowns newUnknowns = (AccumulatedUnknowns) val2;
      return accumulator == null ? newUnknowns : accumulator.merge(newUnknowns);
    }
    return accumulator;
  }

  @CanIgnoreReturnValue
  public AccumulatedUnknowns merge(AccumulatedUnknowns arg) {
    if (this == arg) {
      return this;
    }
    enforceMaxAttributeSize(this.attributes, arg.attributes);
    this.exprIds.addAll(arg.exprIds);
    this.attributes.addAll(arg.attributes);
    this.callIds.addAll(arg.callIds);
    return this;
  }

  static AccumulatedUnknowns create(long exprId) {
    return new AccumulatedUnknowns(
        Collections.singletonList(exprId), Collections.emptyList(), Collections.emptyList());
  }

  public static AccumulatedUnknowns create(
      Collection<Long> exprIds, Collection<CelAttribute> attributes) {
    return new AccumulatedUnknowns(exprIds, attributes, Collections.emptyList());
  }

  /**
   * Creates an accumulated unknown for a pending asynchronous call, recording {@code exprId} so the
   * unknown retains its origin when adapted into a {@link CelUnknownSet}.
   */
  public static AccumulatedUnknowns createForAsyncCall(long exprId, long callId) {
    return new AccumulatedUnknowns(
        Collections.singletonList(exprId),
        Collections.emptyList(),
        Collections.singletonList(callId));
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

  private AccumulatedUnknowns(
      Collection<Long> exprIds, Collection<CelAttribute> attributes, Collection<Long> callIds) {
    this.exprIds = new HashSet<>(exprIds);
    this.attributes = new HashSet<>(attributes);
    this.callIds = new HashSet<>(callIds);
  }
}
