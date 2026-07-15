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

import com.google.auto.value.AutoValue;

/** Result object containing the outcome of a CEL AST verification check. */
@AutoValue
public abstract class CelVerificationResult {

  /** Represents the outcome of the verification process. */
  public enum VerificationStatus {
    /** The property was mathematically proven to hold for all possible inputs. */
    VERIFIED,
    /** The property was disproven. A concrete counterexample was found. */
    VIOLATED,
    /** The property could not be verified due to loop truncation or timeout. */
    INCONCLUSIVE
  }

  /** Returns the status of the verification check. */
  public abstract VerificationStatus status();

  /**
   * Returns a message detailing why the verification failed or was inconclusive (e.g., the
   * counterexample input or truncation reason). Empty if status is VERIFIED.
   */
  public abstract String message();

  static CelVerificationResult verified() {
    return new AutoValue_CelVerificationResult(VerificationStatus.VERIFIED, "");
  }

  static CelVerificationResult failed(String message) {
    return new AutoValue_CelVerificationResult(VerificationStatus.VIOLATED, message);
  }

  static CelVerificationResult inconclusive(String message) {
    return new AutoValue_CelVerificationResult(VerificationStatus.INCONCLUSIVE, message);
  }
}
