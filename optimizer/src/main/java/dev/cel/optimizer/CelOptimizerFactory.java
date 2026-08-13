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

package dev.cel.optimizer;

import dev.cel.bundle.Cel;
import dev.cel.bundle.CelFactory;
import dev.cel.checker.CelChecker;
import dev.cel.compiler.CelCompiler;
import dev.cel.compiler.CelCompilerFactory;
import dev.cel.parser.CelParser;
import dev.cel.runtime.CelRuntime;

/** Factory class for constructing an {@link CelOptimizer} instance. */
public final class CelOptimizerFactory {

  /** Create a new builder for constructing a {@link CelOptimizer} instance. */
  public static CelOptimizerBuilder standardCelOptimizerBuilder(Cel cel) {
    return CelOptimizerImpl.newBuilder(cel);
  }

  /** Create a new builder for constructing a {@link CelOptimizer} instance. */
  public static CelOptimizerBuilder standardCelOptimizerBuilder(
      CelCompiler celCompiler, CelRuntime celRuntime) {
    return standardCelOptimizerBuilder(CelFactory.combine(celCompiler, celRuntime));
  }

  /** Create a new builder for constructing a {@link CelOptimizer} instance. */
  public static CelOptimizerBuilder standardCelOptimizerBuilder(
      CelParser celParser, CelChecker celChecker, CelRuntime celRuntime) {
    return standardCelOptimizerBuilder(
        CelCompilerFactory.combine(celParser, celChecker), celRuntime);
  }

  private CelOptimizerFactory() {}
}
