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

package dev.cel.verifier;

import com.google.errorprone.annotations.CanIgnoreReturnValue;
import dev.cel.common.types.CelTypeProvider;
import java.time.Duration;

/** Interface for building an instance of CelVerifier. */
public interface CelVerifierBuilder {

  /**
   * Sets the timeout duration for the verifier. The timeout must be strictly positive.
   *
   * <p>Note that this is a soft timeout and is evaluated on a best-effort basis by the underlying
   * SMT solver. The solver checks for interruptions periodically during its search phase. This
   * means it may overrun the requested timeout, and in pathological cases, could hang indefinitely.
   *
   * <p>If the verification takes longer than the specified timeout, an {@link
   * IllegalStateException} is thrown.
   */
  @CanIgnoreReturnValue
  CelVerifierBuilder setTimeout(Duration timeout);

  /**
   * Registers a variable name that should be permitted to evaluate to `Unknown` during
   * verification, mirroring partial evaluation semantics.
   */
  @CanIgnoreReturnValue
  CelVerifierBuilder addUnknownIdentifier(String identifier);

  /**
   * Sets the type provider for looking up {@code CelType} definitions by name.
   *
   * <p>The verifier uses this to resolve structural type definitions (such as protocol buffer
   * messages) during verification. If not set, the verifier will fall back to type information
   * present in the AST, which may lose details such as wrapper types.
   */
  @CanIgnoreReturnValue
  CelVerifierBuilder setTypeProvider(CelTypeProvider typeProvider);

  /**
   * Sets the unroll limit for comprehensions (such as {@code map}, {@code filter}, {@code all},
   * {@code exists}).
   *
   * <p>In CEL, lists and maps can be dynamically sized (e.g., passed in as variables). The verifier
   * cannot simulate loops of infinite or unknown size. To safely verify comprehensions, it
   * translates them using Bounded Model Checking, which simulates the loop by statically unrolling
   * it up to this fixed limit.
   *
   * <p>What this means for CEL users:
   *
   * <ul>
   *   <li>If a comprehension iterates over a sequence (list or map), the verifier will assert that
   *       the sequence's size is less than or equal to this limit.
   *   <li>For statically sized sequences (e.g., {@code [1, 2, 3]}), the verifier simply checks if
   *       its exact length is within the limit.
   *   <li>For dynamically sized sequences (e.g., {@code my_list.all(...)}), the verifier must be
   *       able to mathematically prove that the sequence's size is within the limit. If it cannot
   *       prove this (e.g., because the size is completely unconstrained), the entire comprehension
   *       will safely evaluate to {@code Unknown} during verification.
   *   <li>To successfully verify comprehensions over dynamic variables, the CEL expression itself
   *       must logically constrain the size (e.g., {@code size(my_list) <= limit ? my_list.all(...)
   *       : true}).
   *   <li>Setting this limit too high will exponentially increase verification time and memory
   *       usage. Setting it too low will prevent the verifier from proving properties about
   *       moderately-sized sequences.
   * </ul>
   */
  @CanIgnoreReturnValue
  CelVerifierBuilder setComprehensionUnrollLimit(int unrollLimit);

  /** Builds the {@link CelVerifier} instance. */
  CelVerifier build();
}

