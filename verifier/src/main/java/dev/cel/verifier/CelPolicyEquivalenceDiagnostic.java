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
import com.google.common.collect.ImmutableList;
import com.google.errorprone.annotations.Immutable;
import dev.cel.common.CelIssue;
import dev.cel.common.CelSourceLocation;
import dev.cel.common.Source;
import java.util.Optional;

/** Dual-source diagnostic pinpointing why two policies produced conflicting outputs. */
@AutoValue
@AutoValue.CopyAnnotations
@Immutable
public abstract class CelPolicyEquivalenceDiagnostic {

  /** Represents the firing branch attribution for a single policy in an equivalence comparison. */
  @AutoValue
  @AutoValue.CopyAnnotations
  @Immutable
  public abstract static class PolicyBranchAttribution {
    /** Returns the name of the policy. */
    public abstract String policyName();

    /** Returns the name or ID of the matching rule if defined in the policy, or empty. */
    public abstract Optional<String> ruleName();

    /** Returns the 0-based index of the matching rule that fired. */
    public abstract int ruleIndex();

    /** Returns the evaluated output value string produced by this rule branch. */
    public abstract String evaluatedOutput();

    /** Returns the underlying {@link CelIssue} containing location and match attribution. */
    public abstract CelIssue issue();

    /** Formats a visual source code snippet highlighting the matching YAML rule. */
    public String toDisplayString(Source source) {
      return issue().toDisplayString(source);
    }

    static PolicyBranchAttribution create(
        String policyName,
        Optional<String> ruleName,
        int ruleIndex,
        long matchNodeId,
        CelSourceLocation location,
        String evaluatedOutput) {
      String ruleIdentifier =
          ruleName.isPresent()
              ? String.format("rule '%s'", ruleName.get())
              : String.format("match[%d]", ruleIndex);
      String message =
          String.format(
              "Policy '%s' %s matched and evaluated to output: %s",
              policyName, ruleIdentifier, evaluatedOutput);
      CelIssue issue = CelIssue.formatError(matchNodeId, location, message);
      return new AutoValue_CelPolicyEquivalenceDiagnostic_PolicyBranchAttribution(
          policyName, ruleName, ruleIndex, evaluatedOutput, issue);
    }

    static PolicyBranchAttribution create(
        String policyName,
        int ruleIndex,
        long matchNodeId,
        CelSourceLocation location,
        String evaluatedOutput) {
      return create(
          policyName, Optional.empty(), ruleIndex, matchNodeId, location, evaluatedOutput);
    }
  }

  /** Returns the firing branch attribution for Policy A. */
  public abstract PolicyBranchAttribution policyABranch();

  /** Returns the firing branch attribution for Policy B. */
  public abstract PolicyBranchAttribution policyBBranch();

  /** Returns both issues for diagnostic inspection. */
  public ImmutableList<CelIssue> toCelIssues() {
    return ImmutableList.of(policyABranch().issue(), policyBBranch().issue());
  }

  /**
   * Formats a side-by-side snippet display highlighting the diverging branches in both policies.
   */
  public String toDisplayString(Source sourceA, Source sourceB) {
    return String.format(
        "Equivalence Divergence Detected:\n  [Policy A: %s]\n%s\n\n  [Policy B: %s]\n%s",
        policyABranch().policyName(),
        policyABranch().toDisplayString(sourceA),
        policyBBranch().policyName(),
        policyBBranch().toDisplayString(sourceB));
  }

  static CelPolicyEquivalenceDiagnostic of(
      PolicyBranchAttribution branchA, PolicyBranchAttribution branchB) {
    return new AutoValue_CelPolicyEquivalenceDiagnostic(branchA, branchB);
  }
}
