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

import dev.cel.bundle.Cel;
import dev.cel.bundle.CelFactory;
import dev.cel.checker.CelChecker;
import dev.cel.compiler.CelCompiler;
import dev.cel.compiler.CelCompilerFactory;
import dev.cel.parser.CelParser;
import dev.cel.runtime.CelRuntime;

/** Factory class for producing AST verifiers using Z3. */
public final class CelVerifierFactory {

  /**
   * Create a builder for configuring a {@link CelVerifier}.
   *
   * @deprecated Prefer passing a {@link Cel} environment using {@link #newVerifier(Cel)} to enable
   *     canonicalization and expression re-typechecking during verification.
   */
  @Deprecated
  public static CelVerifierBuilder newVerifier() {
    return CelVerifierZ3Impl.newBuilder();
  }

  /** Create a builder for configuring a {@link CelVerifier} with a CEL environment. */
  public static CelVerifierBuilder newVerifier(Cel cel) {
    return CelVerifierZ3Impl.newBuilder(cel);
  }

  /** Create a builder for configuring a {@link CelVerifier} with a CEL environment. */
  public static CelVerifierBuilder newVerifier(CelCompiler celCompiler, CelRuntime celRuntime) {
    return newVerifier(CelFactory.combine(celCompiler, celRuntime));
  }

  /** Create a builder for configuring a {@link CelVerifier} with a CEL environment. */
  public static CelVerifierBuilder newVerifier(
      CelParser celParser, CelChecker celChecker, CelRuntime celRuntime) {
    return newVerifier(CelCompilerFactory.combine(celParser, celChecker), celRuntime);
  }

  private CelVerifierFactory() {}
}
