// Copyright 2024 Google LLC
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

package dev.cel.policy;

import com.google.auto.value.AutoOneOf;
import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableList;
import dev.cel.bundle.Cel;
import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.CelVarDecl;
import dev.cel.common.ast.CelConstant;
import dev.cel.common.ast.CelExpr;
import dev.cel.common.formats.ValueString;
import dev.cel.policy.CelPolicy.EvaluationSemantic;
import java.util.Optional;

/**
 * Abstract representation of a compiled rule. This contains set of compiled variables and match
 * statements which defines an expression graph for a policy.
 */
@AutoValue
public abstract class CelCompiledRule {

  /** Source metadata identifier associated with the compiled rule. */
  public abstract long sourceId();

  public abstract Optional<ValueString> ruleId();

  public abstract ImmutableList<CelCompiledVariable> variables();

  public abstract ImmutableList<CelCompiledMatch> matches();

  public abstract Cel cel();

  public abstract EvaluationSemantic semantic();

  /**
   * HasOptionalOutput returns whether the rule returns a concrete or optional value. The rule may
   * return an optional value if all match expressions under the rule are conditional.
   */
  public boolean hasOptionalOutput() {
    // AGGREGATE rules always return a concrete list (falling back to an empty list rather than
    // optional.none()), meaning they are never optional structurally. This also prevents dead
    // code evasion inside parent FIRST_MATCH rules.
    if (semantic() == EvaluationSemantic.AGGREGATE) {
      return false;
    }

    boolean isOptionalOutput = false;
    for (CelCompiledMatch match : matches()) {
      if (match.result().kind().equals(CelCompiledMatch.Result.Kind.RULE)
          && match.result().rule().hasOptionalOutput()) {
        // If the nested rule is unconditional, the matching may fallthrough to the next match
        // in this context (unwrapping the optional value from the nested rule).
        if (!match.isConditionTriviallyTrue()) {
          return true;
        }
        isOptionalOutput = true;
      } else if (match.isConditionTriviallyTrue()) {
        return false;
      } else {
        isOptionalOutput = true;
      }
    }

    return isOptionalOutput;
  }

  /**
   * A compiled policy variable (ex: variables.foo). Note that this is not the same thing as the
   * variables declared in the config.
   */
  @AutoValue
  public abstract static class CelCompiledVariable {
    public abstract String name();

    /** Compiled variable in AST. */
    public abstract CelAbstractSyntaxTree ast();

    /** The variable declaration used to compile this variable in {@link #ast}. */
    public abstract CelVarDecl celVarDecl();

    static CelCompiledVariable create(
        String name, CelAbstractSyntaxTree ast, CelVarDecl celVarDecl) {
      return new AutoValue_CelCompiledRule_CelCompiledVariable(name, ast, celVarDecl);
    }
  }

  /** A compiled Match. */
  @AutoValue
  public abstract static class CelCompiledMatch {
    /** Source metadata identifier associated with the compiled match. */
    public abstract long sourceId();

    public abstract CelAbstractSyntaxTree condition();

    public abstract Result result();

    public boolean isConditionTriviallyTrue() {
      CelExpr celExpr = condition().getExpr();
      return celExpr.constantOrDefault().getKind().equals(CelConstant.Kind.BOOLEAN_VALUE)
          && celExpr.constant().booleanValue();
    }

    /** Encapsulates the result of this match when condition is met. (either an output or a rule) */
    @AutoOneOf(CelCompiledMatch.Result.Kind.class)
    public abstract static class Result {
      public abstract OutputValue output();

      public abstract CelCompiledRule rule();

      public abstract Kind kind();

      static Result ofOutput(long id, CelAbstractSyntaxTree ast) {
        return AutoOneOf_CelCompiledRule_CelCompiledMatch_Result.output(
            OutputValue.create(id, ast));
      }

      static Result ofRule(CelCompiledRule value) {
        return AutoOneOf_CelCompiledRule_CelCompiledMatch_Result.rule(value);
      }

      /** Kind for {@link Result}. */
      public enum Kind {
        OUTPUT,
        RULE
      }
    }

    /**
     * Encapsulates the output value of the match with its original ID that was used to compile
     * with.
     */
    @AutoValue
    public abstract static class OutputValue {

      /** Source metadata identifier associated with the output. */
      public abstract long sourceId();

      public abstract CelAbstractSyntaxTree ast();

      public static OutputValue create(long id, CelAbstractSyntaxTree ast) {
        return new AutoValue_CelCompiledRule_CelCompiledMatch_OutputValue(id, ast);
      }
    }

    static CelCompiledMatch create(
        long sourceId, CelAbstractSyntaxTree condition, CelCompiledMatch.Result result) {
      return new AutoValue_CelCompiledRule_CelCompiledMatch(sourceId, condition, result);
    }
  }

  static CelCompiledRule create(
      long sourceId,
      Optional<ValueString> ruleId,
      ImmutableList<CelCompiledVariable> variables,
      ImmutableList<CelCompiledMatch> matches,
      Cel cel,
      CelPolicy.EvaluationSemantic semantic) {
    return new AutoValue_CelCompiledRule(sourceId, ruleId, variables, matches, cel, semantic);
  }
}
