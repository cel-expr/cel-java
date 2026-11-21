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
import java.util.Optional;

/** Result object containing the outcome of a CEL AST verification check. */
@AutoValue
public abstract class CelVerificationResult {

  /** Represents the outcome of the verification process. */
  public enum VerificationStatus {
    /** The property was mathematically proven to hold for all possible inputs. */
    VERIFIED,
    /** The property was disproven. A concrete counterexample was found. */
    VIOLATED,
    /** The property could not be verified because it depends on what the verifier doesn't model. */
    INCONCLUSIVE
  }

  /** Returns the status of the verification check. */
  public abstract VerificationStatus status();

  /**
   * Returns the primary reason for the verification outcome.
   */
  public abstract String reason();

  /**
   * Returns a detailed counterexample or satisfying model assignment, if one was found, or the
   * input the CEL runtime refuted.
   */
  public abstract String counterexample();

  /**
   * Returns the counterexample or satisfying input found by the solver, if any, or the input the
   * CEL runtime refuted. Its variables are empty when the outcome doesn't depend on any input.
   */
  public abstract Optional<CelWitness> witness();

  /**
   * Returns a message detailing the outcome of the verification check, such as a counterexample
   * input, satisfying model assignments, or truncation reason. May be empty if status is VERIFIED
   * and no model inputs apply (e.g., when verifying isAlwaysTrue without counterexamples).
   */
  public String message() {
    return reason() + counterexample();
  }

  static CelVerificationResult verified() {
    return create(VerificationStatus.VERIFIED, "");
  }

  static CelVerificationResult verified(String reason, CelWitness satisfyingInput) {
    return create(
        VerificationStatus.VERIFIED, reason, satisfyingInput, /* isCounterexample= */ false);
  }

  static CelVerificationResult failed(String reason) {
    return create(VerificationStatus.VIOLATED, reason);
  }

  static CelVerificationResult failed(String reason, CelWitness counterexample) {
    return create(
        VerificationStatus.VIOLATED, reason, counterexample, /* isCounterexample= */ true);
  }

  static CelVerificationResult inconclusive(String reason) {
    return create(VerificationStatus.INCONCLUSIVE, reason);
  }

  static CelVerificationResult inconclusive(
      String reason, CelWitness witness, boolean isCounterexample) {
    return create(VerificationStatus.INCONCLUSIVE, reason, witness, isCounterexample);
  }

  static CelVerificationResult refuted(String reason, CelWitness witness) {
    return new AutoValue_CelVerificationResult(
        VerificationStatus.INCONCLUSIVE,
        reason,
        witness.variables().isEmpty() ? "" : listInputs(" Input found:", witness),
        Optional.of(witness));
  }

  private static CelVerificationResult create(VerificationStatus status, String reason) {
    return new AutoValue_CelVerificationResult(status, reason, "", Optional.empty());
  }

  private static CelVerificationResult create(
      VerificationStatus status, String reason, CelWitness witness, boolean isCounterexample) {
    return new AutoValue_CelVerificationResult(
        status,
        reason,
        describe(witness, status == VerificationStatus.INCONCLUSIVE, isCounterexample),
        Optional.of(witness));
  }

  private static String describe(
      CelWitness witness, boolean isApproximate, boolean isCounterexample) {
    if (witness.variables().isEmpty()) {
      return isCounterexample
          ? " (The expression fails unconditionally, regardless of input state)"
          : " (The expression is satisfiable unconditionally, regardless of input state)";
    }

    return listInputs(
        isCounterexample
            ? (isApproximate ? " Potential counterexample input:" : " Counterexample input:")
            : (isApproximate ? " Potential satisfying input:" : " Satisfying input:"),
        witness);
  }

  private static String listInputs(String label, CelWitness witness) {
    StringBuilder sb = new StringBuilder(label);
    witness
        .variables()
        .forEach((name, variable) ->
            sb.append("\n  ").append(name).append(" = ").append(variable.displayValue()));
    return sb.toString();
  }
}
