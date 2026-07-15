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

package dev.cel.verifier.axioms;

import com.google.errorprone.annotations.Immutable;
import com.microsoft.z3.BoolExpr;
import com.microsoft.z3.Context;
import com.microsoft.z3.Expr;
import dev.cel.verifier.CelZ3TypeSystem;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/** Translates a specific CEL overload into a Z3 expression. */
@FunctionalInterface
@Immutable
public interface CelZ3OverloadTranslator {

  /**
   * Translates a specific CEL overload into a Z3 expression along with its approximation flag.
   *
   * <p><b>Warning:</b> The provided {@link Context} and {@link CelZ3TypeSystem} are strictly scoped
   * to the current verification run. Do <b>not</b> store references to them as fields or attempt to
   * share them across threads, as doing so will lead to undefined behavior or native memory
   * violations in Z3.
   *
   * @param ctx The Z3 Context strictly bound to the current verification run.
   * @param typeSystem The CEL-to-Z3 type system for wrapping/unwrapping values.
   * @param constraintSink A callback to inject global constraints (axioms) into the Z3 solver.
   *     <b>Warning:</b> You MUST guard any injected constraints with an {@code ctx.mkImplies} type
   *     guard (e.g., checking that the arguments are actually the correct types). Failure to do so
   *     will pollute the solver with invalid constraints for other overloads!
   * @param unwrappedArgs The function arguments, already unwrapped by the framework's dynamic
   *     type-checking ITE chain.
   * @param argApproximations The approximation flags for each of the arguments.
   * @return Optional.empty() if the overload is not handled by this translator.
   */
  Optional<CelZ3OverloadResult> translate(
      Context ctx,
      CelZ3TypeSystem typeSystem,
      Consumer<BoolExpr> constraintSink,
      List<Expr<?>> unwrappedArgs,
      List<BoolExpr> argApproximations);
}
