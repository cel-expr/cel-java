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


/** Factory class for producing AST verifiers using Z3. */
public final class CelVerifierFactory {

  /** Create a builder for configuring a {@link CelVerifier}. */
  public static CelVerifierBuilder newVerifier() {
    return CelVerifierZ3Impl.newBuilder();
  }

  /** Create a builder for configuring a Z3-based {@link CelVerifier} with Z3-specific settings. */
  public static CelVerifierZ3Impl.Builder newZ3Verifier() {
    return CelVerifierZ3Impl.newBuilder();
  }

  private CelVerifierFactory() {}
}
