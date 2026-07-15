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

import com.google.common.collect.ImmutableMap;
import com.google.errorprone.annotations.Immutable;
import dev.cel.common.CelFunctionDecl;
import dev.cel.verifier.axioms.CelZ3FunctionAxiom;
import dev.cel.verifier.axioms.CelZ3OverloadTranslator;
import java.util.Optional;

/** Internal registry managing SMT translations for CEL functions. */
@Immutable
final class CelZ3FunctionRegistry {
  private final ImmutableMap<String, CelFunctionDecl> declarations;
  private final ImmutableMap<String, CelZ3OverloadTranslator> translators;

  private CelZ3FunctionRegistry(
      ImmutableMap<String, CelFunctionDecl> declarations,
      ImmutableMap<String, CelZ3OverloadTranslator> translators) {
    this.declarations = declarations;
    this.translators = translators;
  }

  /** Retrieves the canonical declaration for a given function name. */
  Optional<CelFunctionDecl> getDeclaration(String functionName) {
    return Optional.ofNullable(declarations.get(functionName));
  }

  /** Retrieves the Z3 translator for a specific overload ID. */
  Optional<CelZ3OverloadTranslator> getTranslator(String overloadId) {
    return Optional.ofNullable(translators.get(overloadId));
  }

  /** Builds a validated registry from a collection of axioms. */
  static CelZ3FunctionRegistry create(Iterable<CelZ3FunctionAxiom> axioms) {
    ImmutableMap.Builder<String, CelFunctionDecl> declsBuilder = ImmutableMap.builder();
    ImmutableMap.Builder<String, CelZ3OverloadTranslator> translatorsBuilder =
        ImmutableMap.builder();

    for (CelZ3FunctionAxiom axiom : axioms) {
      CelFunctionDecl decl = axiom.declaration();
      declsBuilder.put(decl.name(), decl);

      translatorsBuilder.putAll(axiom.overloadTranslators());
    }

    return new CelZ3FunctionRegistry(
        declsBuilder.buildOrThrow(), translatorsBuilder.buildOrThrow());
  }
}
