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

import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableMap;
import java.util.Optional;

/** An input assignment found by the solver: a counterexample or a satisfying input. */
@AutoValue
public abstract class CelWitness {

  /** The value the solver assigned to one input variable. */
  @AutoValue
  public abstract static class Variable {

    /**
     * Returns the value in its CEL runtime representation ({@code Long}, {@code UnsignedLong},
     * {@code Double}, {@code Boolean}, {@code String}, {@code CelByteString}, {@code Instant},
     * {@code Duration}, {@code NullValue}, {@code ImmutableList}, {@code ImmutableMap} or {@code
     * Optional}, nested accordingly), or empty if it has no exact runtime equivalent (e.g. an
     * unknown).
     */
    public abstract Optional<Object> value();

    // The value as a CEL literal where one exists, for display.
    abstract String displayValue();

    static Variable create(Optional<Object> value, String displayValue) {
      return new AutoValue_CelWitness_Variable(value, displayValue);
    }
  }

  /** Returns the variables keyed by name, in order of first appearance. */
  public abstract ImmutableMap<String, Variable> variables();

  static CelWitness create(ImmutableMap<String, Variable> variables) {
    return new AutoValue_CelWitness(variables);
  }
}
