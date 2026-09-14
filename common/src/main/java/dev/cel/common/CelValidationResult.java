// Copyright 2022 Google LLC
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

package dev.cel.common;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Comparator.comparing;

import com.google.common.collect.ImmutableList;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.errorprone.annotations.Immutable;
import com.google.errorprone.annotations.InlineMe;
import dev.cel.common.annotations.Internal;
import java.util.Comparator;
import org.jspecify.annotations.Nullable;

/**
 * CelValidationResult encapsulates the {@code CelAbstractSyntaxTree} and {@code CelIssue} set which
 * may be generated during the parse and check phases.
 */
@Immutable
public final class CelValidationResult {

  private static final Comparator<CelIssue> BY_SOURCE_LOCATION =
      comparing(CelIssue::getSourceLocation);

  @SuppressWarnings("Immutable")
  private final @Nullable Throwable failure;

  private final @Nullable CelAbstractSyntaxTree ast;
  private final CelSource source;
  private final ImmutableList<CelIssue> issues;
  private final boolean hasError;

  /** Internal: Consumers should not be creating an instance of this class directly. */
  @Internal
  public CelValidationResult(CelSource source, ImmutableList<CelIssue> issues) {
    this(/* ast= */ null, source, issues, /* failure= */ null);
  }

  /** Internal: Consumers should not be creating an instance of this class directly. */
  @Internal
  public CelValidationResult(CelSource source, Throwable failure, ImmutableList<CelIssue> issues) {
    this(/* ast= */ null, source, issues, failure);
  }

  /** Internal: Consumers should not be creating an instance of this class on their own */
  @Internal
  public CelValidationResult(CelAbstractSyntaxTree ast, ImmutableList<CelIssue> issues) {
    this(ast, ast.getSource(), issues, /* failure= */ null);
  }

  private CelValidationResult(
      @Nullable CelAbstractSyntaxTree ast,
      CelSource source,
      ImmutableList<CelIssue> issues,
      @Nullable Throwable failure) {
    this.ast = ast;
    this.source = source;
    this.issues = ImmutableList.sortedCopyOf(BY_SOURCE_LOCATION, issues);
    this.hasError = failure != null || containsError(issues);
    this.failure = failure;
  }

  private static boolean containsError(ImmutableList<CelIssue> issues) {
    for (int i = 0; i < issues.size(); i++) {
      if (issueIsError(issues.get(i))) {
        return true;
      }
    }
    return false;
  }

  /**
   * Returns the validated {@code CelAbstractSyntaxTree} if one exists.
   *
   * <p>When {@link #hasError} returns {@code true}, this accessor will throw a {@link
   * CelValidationException} containing the error set which prevented AST generation.
   */
  @CanIgnoreReturnValue
  public CelAbstractSyntaxTree getAst() throws CelValidationException {
    if (hasError) {
      if (failure != null) {
        throw new CelValidationException(source, getErrors(), failure.getMessage(), failure);
      }
      throw new CelValidationException(source, getErrors());
    }
    return ast;
  }

  /** Return the {@code CelSource} associated with the result. */
  public CelSource getSource() {
    return source;
  }

  /**
   * Whether a {@code CelIssue} with an {@link CelIssue.Severity#ERROR} severity was encountered
   * during validation.
   */
  public boolean hasError() {
    return hasError;
  }

  /** Return the set of {@code CelIssue}s with an {@code ERROR} severity. */
  public ImmutableList<CelIssue> getErrors() {
    return issues.stream().filter(CelValidationResult::issueIsError).collect(toImmutableList());
  }

  /** Return all {@code CelIssue}s encountered durint validation. */
  public ImmutableList<CelIssue> getAllIssues() {
    return issues;
  }

  /** Convert all issues to a human-readable string. */
  public String getIssueString() {
    return CelIssue.toDisplayString(issues, source);
  }

  /**
   * Convert the {@code CelIssue} set to a debug string.
   *
   * @deprecated Use {@link #getIssueString()} instead.
   */
  @Deprecated
  @InlineMe(replacement = "this.getIssueString()")
  public String getDebugString() {
    return getIssueString();
  }

  /** Convert the {@code CelIssue}s with {@code ERROR} severity to an error string. */
  public String getErrorString() {
    return CelIssue.toDisplayString(getErrors(), source);
  }

  private static boolean issueIsError(CelIssue iss) {
    return iss.getSeverity() == CelIssue.Severity.ERROR;
  }
}
