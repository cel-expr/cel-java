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

import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.policy.CelPolicy;
import dev.cel.policy.CelPolicyCompiler;
import dev.cel.policy.CelPolicyValidationException;

/** Implementation of CelPolicyVerifier using a CelVerifier. */
final class CelPolicyVerifierImpl implements CelPolicyVerifier {

  private final CelPolicyCompiler compiler;
  private final CelVerifier astVerifier;

  static CelPolicyVerifierBuilder newBuilder(CelPolicyCompiler compiler, CelVerifier verifier) {
    return new Builder(compiler, verifier);
  }

  static final class Builder implements CelPolicyVerifierBuilder {
    private final CelPolicyCompiler compiler;
    private final CelVerifier astVerifier;

    private Builder(CelPolicyCompiler compiler, CelVerifier astVerifier) {
      this.compiler = compiler;
      this.astVerifier = astVerifier;
    }

    @Override
    public CelPolicyVerifier build() {
      return new CelPolicyVerifierImpl(compiler, astVerifier);
    }
  }

  private CelPolicyVerifierImpl(CelPolicyCompiler compiler, CelVerifier verifier) {
    this.compiler = compiler;
    this.astVerifier = verifier;
  }

  @Override
  public CelVerificationResult verifyEquivalence(CelPolicy policyA, CelPolicy policyB)
      throws CelPolicyValidationException, CelVerificationException {
    CelAbstractSyntaxTree astA = compiler.compile(policyA);
    CelAbstractSyntaxTree astB = compiler.compile(policyB);
    return astVerifier.verifyEquivalence(astA, astB);
  }
}
