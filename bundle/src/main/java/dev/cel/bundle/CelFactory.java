// Copyright 2023 Google LLC
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

package dev.cel.bundle;

import dev.cel.checker.CelCheckerLegacyImpl;
import dev.cel.common.CelOptions;
import dev.cel.compiler.CelCompiler;
import dev.cel.compiler.CelCompilerImpl;
import dev.cel.parser.CelParserImpl;
import dev.cel.runtime.CelRuntime;
import dev.cel.runtime.CelRuntimeImpl;
import dev.cel.runtime.CelRuntimeLegacyImpl;

/** Helper class to configure the entire CEL stack in a common interface. */
public final class CelFactory {

  /**
   * Creates a builder for configuring CEL using current parser for the parse, type-check, and eval
   * of expressions.
   *
   * <p>Note, the {@link CelOptions#current}, standard CEL function libraries, and linked message
   * evaluation are enabled by default.
   *
   * @deprecated Migrate to the planner ({@link #plannerCelBuilder()}).
   */
  @Deprecated
  public static CelBuilder standardCelBuilder() {
    return legacyCelBuilder();
  }

  /**
   * Creates a builder for configuring a legacy CEL using current parser for the parse, type-check,
   * and eval of expressions.
   *
   * @deprecated Migrate to the planner ({@link #plannerCelBuilder()}).
   */
  @Deprecated
  public static CelBuilder legacyCelBuilder() {
    return CelImpl.newBuilder(
            CelCompilerImpl.newBuilder(
                CelParserImpl.newBuilder(), CelCheckerLegacyImpl.newBuilder()),
            CelRuntimeLegacyImpl.newBuilder())
        .setOptions(CelOptions.current().build())
        // CEL-Internal-2
        .setStandardEnvironmentEnabled(true);
  }

  /**
   * Creates a builder for configuring CEL for the parsing, optional type-checking, and evaluation
   * of expressions using the Program Planner.
   *
   * <p>The {@code ProgramPlanner} architecture provides key benefits over the {@link
   * #standardCelBuilder()}:
   *
   * <ul>
   *   <li><b>Performance:</b> Programs can be cached for improving evaluation speed.
   *   <li><b>Parsed-only expression evaluation:</b> Unlike the traditional stack which required
   *       supplying type-checked expressions, this architecture handles both parsed-only and
   *       type-checked expressions.
   * </ul>
   */
  public static CelBuilder plannerCelBuilder() {
    return CelImpl.newBuilder(
            CelCompilerImpl.newBuilder(
                CelParserImpl.newBuilder(),
                CelCheckerLegacyImpl.newBuilder().setStandardEnvironmentEnabled(true)),
            CelRuntimeImpl.newBuilder())
        // CEL-Internal-2
        .setOptions(CelOptions.current().enableHeterogeneousNumericComparisons(true).build());
  }

  /** Combines a prebuilt {@link CelCompiler} and {@link CelRuntime} into {@link Cel}. */
  public static Cel combine(CelCompiler celCompiler, CelRuntime celRuntime) {
    return CelImpl.combine(celCompiler, celRuntime);
  }

  private CelFactory() {}
}
