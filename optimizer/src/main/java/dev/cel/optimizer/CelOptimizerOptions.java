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

package dev.cel.optimizer;

import com.google.auto.value.AutoValue;

/** Options to configure how {@link CelOptimizer} behaves. */
@AutoValue
public abstract class CelOptimizerOptions {

  /**
   * Returns true if AST validation is enabled. When enabled, each optimizer pass verifies AST
   * invariants (such as expression ID uniqueness and macro source consistency) after type-checking.
   */
  public abstract boolean enableAstValidation();

  /** Builder for configuring the {@link CelOptimizerOptions}. */
  @AutoValue.Builder
  public abstract static class Builder {

    /**
     * Enables or disables post-pass AST validation. When enabled, each optimizer pass verifies that
     * expression IDs are unique and macro calls in the AST source are consistent with the
     * expression nodes.
     */
    public abstract Builder enableAstValidation(boolean value);

    public abstract CelOptimizerOptions build();

    Builder() {}
  }

  /** Returns a new options builder with recommended defaults pre-configured. */
  public static Builder newBuilder() {
    return new AutoValue_CelOptimizerOptions.Builder().enableAstValidation(true);
  }

  CelOptimizerOptions() {}
}
