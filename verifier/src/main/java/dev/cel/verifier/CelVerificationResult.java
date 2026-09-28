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
import com.google.errorprone.annotations.Immutable;
import java.util.Optional;

/** Result object containing the outcome of a CEL AST verification check. */
@AutoValue
@AutoValue.CopyAnnotations
@Immutable
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

  /** Returns the primary reason for the verification outcome. */
  public abstract String reason();

  /** Returns a detailed counterexample or satisfying model assignment string, if one was found. */
  public abstract String counterexample();

  /** Returns the structured counterexample or satisfying model assignment, if one was found. */
  public abstract Optional<CelCounterexample> counterexampleModel();

  /** Returns the source-located policy invariant diagnostic, if applicable. */
  public abstract Optional<CelPolicyDiagnostic> policyDiagnostic();

  /** Returns the dual-source policy equivalence diagnostic, if applicable. */
  public abstract Optional<CelPolicyEquivalenceDiagnostic> policyEquivalenceDiagnostic();

  /**
   * Returns a message detailing the outcome of the verification check, such as a counterexample
   * input, satisfying model assignments, or truncation reason. May be empty if status is VERIFIED
   * and no model inputs apply (e.g., when verifying isAlwaysTrue without counterexamples).
   */
  public String message() {
    return reason() + counterexample();
  }

  abstract Builder toBuilder();

  static Builder builder() {
    return new AutoValue_CelVerificationResult.Builder()
        .setReason("")
        .setCounterexample("")
        .setCounterexampleModel(Optional.empty())
        .setPolicyDiagnostic(Optional.empty())
        .setPolicyEquivalenceDiagnostic(Optional.empty());
  }

  /** Builder for {@link CelVerificationResult}. */
  @AutoValue.Builder
  abstract static class Builder {
    abstract Builder setStatus(VerificationStatus status);

    abstract Builder setReason(String reason);

    abstract Builder setCounterexample(String counterexample);

    abstract Builder setCounterexampleModel(Optional<CelCounterexample> counterexampleModel);

    Builder setCounterexampleModel(CelCounterexample counterexampleModel) {
      return setCounterexampleModel(Optional.of(counterexampleModel));
    }

    abstract Builder setPolicyDiagnostic(Optional<CelPolicyDiagnostic> diagnostic);

    Builder setPolicyDiagnostic(CelPolicyDiagnostic diagnostic) {
      return setPolicyDiagnostic(Optional.of(diagnostic));
    }

    abstract Builder setPolicyEquivalenceDiagnostic(
        Optional<CelPolicyEquivalenceDiagnostic> diagnostic);

    Builder setPolicyEquivalenceDiagnostic(CelPolicyEquivalenceDiagnostic diagnostic) {
      return setPolicyEquivalenceDiagnostic(Optional.of(diagnostic));
    }

    abstract CelVerificationResult build();
  }

  static CelVerificationResult verified() {
    return builder().setStatus(VerificationStatus.VERIFIED).build();
  }

  static CelVerificationResult verified(String reason, CelCounterexample counterexample) {
    return builder()
        .setStatus(VerificationStatus.VERIFIED)
        .setReason(reason)
        .setCounterexample(counterexample.toDisplayString())
        .setCounterexampleModel(counterexample)
        .build();
  }

  static CelVerificationResult failed(String reason) {
    return builder().setStatus(VerificationStatus.VIOLATED).setReason(reason).build();
  }

  static CelVerificationResult failed(String reason, CelCounterexample counterexample) {
    return builder()
        .setStatus(VerificationStatus.VIOLATED)
        .setReason(reason)
        .setCounterexample(counterexample.toDisplayString())
        .setCounterexampleModel(counterexample)
        .build();
  }

  static CelVerificationResult inconclusive(String reason) {
    return builder().setStatus(VerificationStatus.INCONCLUSIVE).setReason(reason).build();
  }

  static CelVerificationResult inconclusive(String reason, CelCounterexample counterexample) {
    return builder()
        .setStatus(VerificationStatus.INCONCLUSIVE)
        .setReason(reason)
        .setCounterexample(counterexample.toDisplayString())
        .setCounterexampleModel(counterexample)
        .build();
  }
}
