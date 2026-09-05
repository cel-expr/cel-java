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
import com.google.errorprone.annotations.Immutable;
import dev.cel.common.types.CelType;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** Encapsulates a structured variable assignment model produced by formal verification. */
@AutoValue
@AutoValue.CopyAnnotations
@Immutable
public abstract class CelCounterexample {

  /** Represents a single variable binding within a counterexample. */
  @AutoValue
  @AutoValue.CopyAnnotations
  @Immutable
  @SuppressWarnings("Immutable") // Values are deeply immutable.
  public abstract static class Binding {
    /** Returns the name of the variable. */
    public abstract String name();

    /** Returns the inferred CEL type of the variable. */
    public abstract CelType type();

    /**
     * Returns the native Java representation of the value (e.g., Long, Boolean, String, Instant,
     * Duration, ImmutableList, ImmutableMap, Message, etc.), or empty if unassigned or unavailable.
     */
    public abstract Optional<Object> nativeValue();

    /**
     * Returns the CEL literal representation of the value (e.g., "80", "\"admin\"", "true", "[1,
     * 2]").
     */
    public abstract String celString();

    public static Binding of(
        String name, CelType type, @Nullable Object nativeValue, String celString) {
      return new AutoValue_CelCounterexample_Binding(
          name, type, Optional.ofNullable(nativeValue), celString);
    }
  }

  /** Returns all variable bindings keyed by variable name. */
  public abstract ImmutableMap<String, Binding> bindings();

  /** Returns true if this counterexample was derived from an approximate solver model. */
  public abstract boolean isApproximate();

  /** Returns true if this model represents a satisfying assignment rather than a counterexample. */
  public abstract boolean isSatisfyingInput();

  /** Returns the formatted display string representation. */
  public abstract String toDisplayString();

  /** Looks up a variable binding by name. */
  public Optional<Binding> get(String variableName) {
    return Optional.ofNullable(bindings().get(variableName));
  }

  /**
   * Returns a native Java variable map suitable for evaluating expressions in CelRuntime (e.g.,
   * Cel.createProgram().eval(toEvaluationContext())).
   */
  public ImmutableMap<String, Object> toEvaluationContext() {
    ImmutableMap.Builder<String, Object> builder = ImmutableMap.builder();
    for (Binding binding : bindings().values()) {
      binding.nativeValue().ifPresent(value -> builder.put(binding.name(), value));
    }
    return builder.buildOrThrow();
  }

  public static CelCounterexample create(
      Map<String, Binding> bindings,
      boolean isApproximate,
      boolean isSatisfyingInput,
      String toDisplayString) {
    return new AutoValue_CelCounterexample(
        ImmutableMap.copyOf(bindings), isApproximate, isSatisfyingInput, toDisplayString);
  }
}
