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
import dev.cel.common.CelIssue;
import dev.cel.common.CelSourceLocation;
import dev.cel.common.Source;
import java.util.Optional;

/** Source-located explanation of a policy invariant violation. */
@AutoValue
@AutoValue.CopyAnnotations
@Immutable
public abstract class CelPolicyDiagnostic {

  /** Returns the ID of the invariant that failed verification. */
  public abstract String invariantId();

  /** Returns the name or ID of the offending rule if defined in the policy, or empty. */
  public abstract Optional<String> offendingRuleName();

  /** Returns the 0-based index of the policy match rule that fired. */
  public abstract int offendingRuleIndex();

  /**
   * Returns the underlying {@link CelIssue} containing location and formatted error explanation.
   */
  public abstract CelIssue issue();

  /** Formats a visual source code snippet highlighting the offending YAML rule. */
  public String toDisplayString(Source source) {
    return issue().toDisplayString(source);
  }

  static CelPolicyDiagnostic create(
      String invariantId,
      Optional<String> ruleName,
      int ruleIndex,
      long yamlNodeId,
      CelSourceLocation location,
      String explanation) {
    return new AutoValue_CelPolicyDiagnostic(
        invariantId, ruleName, ruleIndex, CelIssue.formatError(yamlNodeId, location, explanation));
  }

  static CelPolicyDiagnostic create(
      String invariantId,
      int ruleIndex,
      long yamlNodeId,
      CelSourceLocation location,
      String explanation) {
    return create(invariantId, Optional.empty(), ruleIndex, yamlNodeId, location, explanation);
  }
}
