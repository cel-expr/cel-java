// Copyright 2022 Google LLC
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

import dev.cel.common.annotations.Internal;
import java.util.ArrayList;
import java.util.Optional;

/**
 * Package private utility class for collecting CelUnknownSets from an argument list in a call-like
 * expression.
 *
 * <p>Accumulates arguments via {@link #checkArg(DefaultInterpreter.IntermediateResult)}. After all
 * args are checked, the result is provided by {@link #maybeUnknowns()}
 *
 * <p>CEL Library Internals. Do Not Use.
 */
@Internal
class CallArgumentChecker {
  private final ArrayList<Long> exprIds;
  private final RuntimeUnknownResolver resolver;
  private final boolean acceptPartial;
  private Optional<AccumulatedUnknowns> unknowns;

  private CallArgumentChecker(RuntimeUnknownResolver resolver, boolean acceptPartial) {
    this.exprIds = new ArrayList<>();
    this.unknowns = Optional.empty();
    this.resolver = resolver;
    this.acceptPartial = acceptPartial;
  }

  /**
   * Creates a CallArgumentChecker that only permits 'completeData'.
   *
   * <p>Arguments that are determined as partially unknown (have a subfield that is unknown) are
   * treated as unknown and added to the accumulated UnknownSet.
   */
  static CallArgumentChecker create(RuntimeUnknownResolver resolver) {
    return new CallArgumentChecker(resolver, false);
  }

  /**
   * Creates a CallArgumentChecker that permits partial data (e.g. container accesses).
   *
   * <p>Arguments that are determined as unknown (match an unknown type) are treated as unknown and
   * added to the accumulated UnknownSet.
   */
  static CallArgumentChecker createAcceptingPartial(RuntimeUnknownResolver resolver) {
    return new CallArgumentChecker(resolver, true);
  }

  private static Optional<AccumulatedUnknowns> mergeOptionalUnknowns(
      Optional<AccumulatedUnknowns> lhs, Optional<AccumulatedUnknowns> rhs) {
    return lhs.isPresent() ? rhs.isPresent() ? Optional.of(lhs.get().merge(rhs.get())) : lhs : rhs;
  }

  /** Determine if the call argument is unknown and accumulate if so. */
  void checkArg(DefaultInterpreter.IntermediateResult arg) {
    // Handle attribute tracked unknowns.
    Optional<AccumulatedUnknowns> argUnknowns = maybeUnknownFromArg(arg);
    unknowns = mergeOptionalUnknowns(unknowns, argUnknowns);

    // support for ExprValue unknowns.
    if (arg.value() instanceof AccumulatedUnknowns) {
      AccumulatedUnknowns unknownSet = (AccumulatedUnknowns) arg.value();
      exprIds.addAll(unknownSet.exprIds());
    }
  }

  private Optional<AccumulatedUnknowns> maybeUnknownFromArg(
      DefaultInterpreter.IntermediateResult arg) {
    if (arg.value() instanceof AccumulatedUnknowns) {
      AccumulatedUnknowns celUnknownSet = (AccumulatedUnknowns) arg.value();
      if (!celUnknownSet.attributes().isEmpty()) {
        return Optional.of((AccumulatedUnknowns) arg.value());
      }
    }
    if (!acceptPartial) {
      return resolver.maybePartialUnknown(arg.attribute());
    }
    return Optional.empty();
  }

  /** Returns the accumulated unknown if any. */
  Optional<Object> maybeUnknowns() {
    if (unknowns.isPresent()) {
      return Optional.of(unknowns.get());
    }

    if (!exprIds.isEmpty()) {
      return Optional.of(AccumulatedUnknowns.create(exprIds));
    }

    return Optional.empty();
  }
}
