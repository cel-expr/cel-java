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

import com.google.errorprone.annotations.Immutable;
import dev.cel.common.CelAbstractSyntaxTree;

/** Public interface for formal verification of CEL ASTs. */
@Immutable
public interface CelVerifier {

  /**
   * Returns verified if there is at least one input combination where the AST evaluates to true.
   *
   * @param ast The input expression to verify. Must be a type-checked AST.
   */
  CelVerificationResult isSatisfiable(CelAbstractSyntaxTree ast) throws CelVerificationException;

  /**
   * Returns verified if the AST evaluates to true for ALL possible inputs.
   *
   * @param ast The input expression to verify. Must be a type-checked AST.
   */
  CelVerificationResult isAlwaysTrue(CelAbstractSyntaxTree ast) throws CelVerificationException;

  /**
   * Returns verified if astA and astB are logically equivalent for all inputs.
   *
   * @param astA The first input expression to verify. Must be a type-checked AST.
   * @param astB The second input expression to verify. Must be a type-checked AST.
   */
  CelVerificationResult verifyEquivalence(CelAbstractSyntaxTree astA, CelAbstractSyntaxTree astB)
      throws CelVerificationException;
}
