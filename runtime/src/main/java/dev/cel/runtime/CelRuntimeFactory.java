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

import dev.cel.common.CelOptions;

/** Helper class to construct new {@code CelRuntime} instances. */
public final class CelRuntimeFactory {

  /**
   * Create a new builder for constructing a {@code CelRuntime} instance.
   *
   * <p>Note, the {@link CelOptions#current}, standard CEL function libraries, and linked message
   * evaluation are enabled by default.
   *
   * @deprecated Migrate to the planner ({@link #plannerRuntimeBuilder()}).
   */
  @Deprecated
  public static CelRuntimeBuilder standardCelRuntimeBuilder() {
    return legacyCelRuntimeBuilder();
  }

  /**
   * Create a new builder for constructing a legacy {@code CelRuntime} instance.
   *
   * @deprecated Migrate to the planner ({@link #plannerRuntimeBuilder()}).
   */
  @Deprecated
  public static CelRuntimeBuilder legacyCelRuntimeBuilder() {
    return CelRuntimeLegacyImpl.newBuilder()
        .setOptions(CelOptions.current().build())
        // CEL-Internal-2
        .setStandardEnvironmentEnabled(true);
  }

  /**
   * Create a new builder for constructing a {@code CelRuntime} instance.
   *
   * <p>The {@code ProgramPlanner} architecture provides key benefits over the {@link
   * #standardCelRuntimeBuilder()}:
   *
   * <ul>
   *   <li><b>Performance:</b> Programs can be cached for improving evaluation speed.
   *   <li><b>Parsed-only expression evaluation:</b> Unlike the runtime returned by {@link
   *       #standardCelRuntimeBuilder()}, which only supported evaluating type-checked expressions,
   *       this architecture handles both parsed-only and type-checked expressions.
   * </ul>
   */
  public static CelRuntimeBuilder plannerRuntimeBuilder() {
    return CelRuntimeImpl.newBuilder()
        // CEL-Internal-2
        .setOptions(CelOptions.current().enableHeterogeneousNumericComparisons(true).build());
  }

  private CelRuntimeFactory() {}
}
