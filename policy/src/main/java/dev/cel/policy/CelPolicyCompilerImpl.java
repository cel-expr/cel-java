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

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.collect.ImmutableList.toImmutableList;

import com.google.common.collect.ImmutableList;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import dev.cel.bundle.Cel;
import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.CelContainer;
import dev.cel.common.CelIssue;
import dev.cel.common.CelSource;
import dev.cel.common.CelSourceLocation;
import dev.cel.common.CelValidationException;
import dev.cel.common.CelValidationResult;
import dev.cel.common.CelVarDecl;
import dev.cel.common.ast.CelConstant;
import dev.cel.common.ast.CelExpr;
import dev.cel.common.formats.ValueString;
import dev.cel.common.types.CelType;
import dev.cel.common.types.SimpleType;
import dev.cel.optimizer.CelAstOptimizer;
import dev.cel.optimizer.CelOptimizationException;
import dev.cel.optimizer.CelOptimizer;
import dev.cel.optimizer.CelOptimizerFactory;
import dev.cel.optimizer.optimizers.ConstantFoldingOptimizer;
import dev.cel.optimizer.optimizers.SubexpressionOptimizer;
import dev.cel.optimizer.optimizers.SubexpressionOptimizer.SubexpressionOptimizerOptions;
import dev.cel.policy.CelCompiledRule.CelCompiledMatch;
import dev.cel.policy.CelCompiledRule.CelCompiledMatch.Result;
import dev.cel.policy.CelCompiledRule.CelCompiledMatch.Result.Kind;
import dev.cel.policy.CelCompiledRule.CelCompiledVariable;
import dev.cel.policy.CelPolicy.EvaluationSemantic;
import dev.cel.policy.CelPolicy.Import;
import dev.cel.policy.CelPolicy.Match;
import dev.cel.policy.CelPolicy.Variable;
import dev.cel.policy.RuleComposer.RuleCompositionException;
import dev.cel.validator.CelAstValidator;
import dev.cel.validator.CelValidator;
import dev.cel.validator.CelValidatorFactory;
import dev.cel.validator.validators.AstDepthLimitValidator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/** Package-private implementation for policy compiler. */
final class CelPolicyCompilerImpl implements CelPolicyCompiler {
  private static final String DEFAULT_VARIABLE_PREFIX = "variables.";
  private static final int DEFAULT_ITERATION_LIMIT = 1000;
  private final Cel cel;
  private final String variablesPrefix;
  private final int iterationLimit;
  private final ImmutableList<CelAstOptimizer> optimizers;
  private final Optional<CelAstValidator> astDepthValidator;

  @Override
  public CelCompiledRule compileRule(CelPolicy policy) throws CelPolicyValidationException {
    CompilerContext compilerContext = new CompilerContext(policy.policySource());

    Cel extendedCel = this.cel;

    if (!policy.imports().isEmpty()) {
      CelContainer.Builder containerBuilder =
          extendedCel.toCheckerBuilder().container().toBuilder();

      for (Import imp : policy.imports()) {
        try {
          containerBuilder.addAbbreviations(imp.name().value());
        } catch (IllegalArgumentException e) {
          compilerContext.addIssue(
              imp.id(),
              CelIssue.formatError(
                  1, 0, String.format("Error configuring import: %s", e.getMessage())));
        }
      }

      extendedCel = extendedCel.toCelBuilder().setContainer(containerBuilder.build()).build();
    }

    CelCompiledRule compiledRule =
        compileRuleImpl(policy.rule(), extendedCel, compilerContext, false);
    if (compilerContext.hasError()) {
      throw new CelPolicyValidationException(compilerContext.getIssueString());
    }

    return compiledRule;
  }

  @Override
  public CelAbstractSyntaxTree compose(CelPolicy policy, CelCompiledRule compiledRule)
      throws CelPolicyValidationException {
    Cel cel = compiledRule.cel();
    CelOptimizer composingOptimizer =
        CelOptimizerFactory.standardCelOptimizerBuilder(cel)
            .addAstOptimizers(
                RuleComposer.newInstance(compiledRule, variablesPrefix, iterationLimit))
            .build();

    CelAbstractSyntaxTree ast;

    try {
      // This is a minimal expression used as a basis of stitching together all the rules into a
      // single graph.
      ast = cel.compile("true").getAst();
      ast = composingOptimizer.optimize(ast);
    } catch (CelValidationException | CelOptimizationException e) {
      if (e.getCause() instanceof RuleCompositionException) {
        RuleCompositionException re = (RuleCompositionException) e.getCause();
        CompilerContext compilerContext = new CompilerContext(policy.policySource());
        // The exact CEL error message produced from composition failure isn't too useful for users.
        // Ex: ERROR: :1:1: found no matching overload for '_?_:_' applied to '(bool, map(int, int),
        // bool)' (candidates: (bool, %A0, %A0))
        // Transform the error messages in a user-friendly way while retaining the original
        // CelValidationException as its originating cause.

        ImmutableList<CelIssue> transformedIssues =
            re.compileException.getErrors().stream()
                .map(x -> CelIssue.formatError(x.getSourceLocation(), re.failureReason))
                .collect(toImmutableList());
        for (long id : re.errorIds) {
          compilerContext.addIssue(id, transformedIssues);
        }

        throw new CelPolicyValidationException(compilerContext.getIssueString(), re.getCause());
      }

      // Something has gone seriously wrong.
      throw new CelPolicyValidationException("Unexpected error while composing rules.", e);
    }

    CelOptimizer astOptimizer =
        CelOptimizerFactory.standardCelOptimizerBuilder(cel).addAstOptimizers(optimizers).build();
    try {
      // Optimize the composed graph using const fold and CSE
      ast = astOptimizer.optimize(ast);
    } catch (CelOptimizationException e) {
      throw new CelPolicyValidationException(
          "Failed to optimize the composed policy. Reason: " + e.getMessage(), e);
    }

    assertAstDepthIsSafe(ast, cel);

    return ast;
  }

  private void assertAstDepthIsSafe(CelAbstractSyntaxTree ast, Cel cel)
      throws CelPolicyValidationException {
    if (!astDepthValidator.isPresent()) {
      return;
    }
    CelValidator celValidator =
        CelValidatorFactory.standardCelValidatorBuilder(cel)
            .addAstValidators(astDepthValidator.get())
            .build();
    CelValidationResult result = celValidator.validate(ast);
    if (result.hasError()) {
      throw new CelPolicyValidationException(result.getErrorString());
    }
  }

  private CelCompiledRule compileRuleImpl(
      CelPolicy.Rule rule,
      Cel ruleCel,
      CompilerContext compilerContext,
      boolean hasAggregateAncestor) {
    if (hasAggregateAncestor && rule.semantic().equals(EvaluationSemantic.AGGREGATE)) {
      compilerContext.addIssue(
          rule.id(), CelIssue.formatError(1, 0, "nested aggregate rules are not allowed"));
    }
    // A local CEL environment used to compile a single rule. This temporary environment
    // is used to declare policy variables iteratively in a given policy, ensuring proper scoping
    // across a single / nested rule.
    Cel localCel = ruleCel;
    ImmutableList.Builder<CelCompiledVariable> variableBuilder = ImmutableList.builder();
    for (Variable variable : rule.variables()) {
      ValueString expression = variable.expression();
      CelAbstractSyntaxTree varAst;
      CelType outputType = SimpleType.DYN;
      try {
        varAst = localCel.compile(expression.value()).getAst();
        outputType = varAst.getResultType();
      } catch (CelValidationException e) {
        compilerContext.addIssue(expression.id(), e.getErrors());
        // A sentinel AST representing an error is created to allow compiler checks to continue
        varAst = newErrorAst();
      }
      String variableName = variable.name().value();
      CelVarDecl newVariable =
          CelVarDecl.newVarDeclaration(variablesPrefix + variableName, outputType);
      localCel = localCel.toCelBuilder().addVarDeclarations(newVariable).build();
      variableBuilder.add(CelCompiledVariable.create(variableName, varAst, newVariable));
    }

    ImmutableList.Builder<CelCompiledMatch> matchBuilder = ImmutableList.builder();
    for (Match match : rule.matches()) {
      CelAbstractSyntaxTree conditionAst;
      try {
        conditionAst = localCel.compile(match.condition().value()).getAst();
        if (!conditionAst.getResultType().equals(SimpleType.BOOL)) {
          compilerContext.addIssue(
              match.condition().id(),
              CelIssue.formatError(1, 0, "condition must produce a boolean output."));
        }
      } catch (CelValidationException e) {
        compilerContext.addIssue(match.condition().id(), e.getErrors());
        continue;
      }

      Result matchResult;
      switch (match.result().kind()) {
        case OUTPUT:
          CelAbstractSyntaxTree outputAst;
          ValueString output = match.result().output();
          try {
            outputAst = localCel.compile(output.value()).getAst();
          } catch (CelValidationException e) {
            compilerContext.addIssue(output.id(), e.getErrors());
            continue;
          }

          matchResult = Result.ofOutput(output.id(), outputAst);
          break;
        case RULE:
          boolean nextHasAggregateAncestor =
              hasAggregateAncestor || rule.semantic().equals(EvaluationSemantic.AGGREGATE);
          CelCompiledRule nestedRule =
              compileRuleImpl(
                  match.result().rule(), localCel, compilerContext, nextHasAggregateAncestor);
          matchResult = Result.ofRule(nestedRule);
          break;
        default:
          throw new IllegalArgumentException("Unexpected kind: " + match.result().kind());
      }

      matchBuilder.add(CelCompiledMatch.create(match.id(), conditionAst, matchResult));
    }

    CelCompiledRule compiledRule =
        CelCompiledRule.create(
            rule.id(),
            rule.ruleId(),
            variableBuilder.build(),
            matchBuilder.build(),
            ruleCel,
            rule.semantic());

    // Validate that all branches in the policy are reachable
    checkUnreachableCode(compiledRule, compilerContext);

    return compiledRule;
  }

  private void checkUnreachableCode(CelCompiledRule compiledRule, CompilerContext compilerContext) {
    ImmutableList<CelCompiledMatch> compiledMatches = compiledRule.matches();
    int matchCount = compiledMatches.size();
    for (int i = matchCount - 1; i >= 0; i--) {
      CelCompiledMatch compiledMatch = compiledMatches.get(i);
      boolean isTriviallyTrue = compiledMatch.isConditionTriviallyTrue();

      // Flag literally false conditions as dead code regardless of semantic
      if (isConditionLiterallyFalse(compiledMatch.condition())) {
        compilerContext.addIssue(
            compiledMatch.sourceId(), CelIssue.formatError(1, 0, "Condition is always false"));
      }

      // If the match is a single output or a nested rule that always returns a value, it is
      // exhaustive. If the condition is trivially true, then all subsequent branches are
      // unreachable.
      boolean isExhaustive =
          isTriviallyTrue
              && (compiledMatch.result().kind().equals(Kind.OUTPUT)
                  || !compiledMatch.result().rule().hasOptionalOutput());

      if (compiledRule.semantic() == EvaluationSemantic.FIRST_MATCH
          && isExhaustive
          && i != matchCount - 1) {
        if (compiledMatch.result().kind().equals(Kind.OUTPUT)) {
          compilerContext.addIssue(
              compiledMatch.sourceId(),
              CelIssue.formatError(1, 0, "Match creates unreachable outputs"));
        } else {
          compilerContext.addIssue(
              compiledMatch.result().rule().sourceId(),
              CelIssue.formatError(1, 0, "Rule creates unreachable outputs"));
        }
      }
    }
  }

  private static boolean isConditionLiterallyFalse(CelAbstractSyntaxTree condition) {
    CelExpr celExpr = condition.getExpr();
    return celExpr.constantOrDefault().getKind().equals(CelConstant.Kind.BOOLEAN_VALUE)
        && !celExpr.constant().booleanValue();
  }

  private static CelAbstractSyntaxTree newErrorAst() {
    return CelAbstractSyntaxTree.newParsedAst(
        CelExpr.ofConstant(0, CelConstant.ofValue("*error*")), CelSource.newBuilder().build());
  }

  private static final class CompilerContext {
    private final ArrayList<CelIssue> issues;
    private final CelPolicySource celPolicySource;

    private void addIssue(long id, CelIssue... issues) {
      addIssue(id, Arrays.asList(issues));
    }

    private void addIssue(long id, List<CelIssue> issues) {
      for (CelIssue issue : issues) {
        CelSourceLocation absoluteLocation = computeAbsoluteLocation(id, issue);
        this.issues.add(CelIssue.formatError(absoluteLocation, issue.getMessage()));
      }
    }

    private CelSourceLocation computeAbsoluteLocation(long id, CelIssue issue) {
      int policySourceOffset =
          Optional.ofNullable(celPolicySource.getPositionsMap().get(id)).orElse(-1);
      if (policySourceOffset == -1) {
        return CelSourceLocation.NONE;
      }
      CelSourceLocation policySourceLocation =
          celPolicySource.getOffsetLocation(policySourceOffset).orElse(null);
      if (policySourceLocation == null) {
        return CelSourceLocation.NONE;
      }

      int absoluteLine = issue.getSourceLocation().getLine() + policySourceLocation.getLine() - 1;
      int absoluteColumn = issue.getSourceLocation().getColumn() + policySourceLocation.getColumn();
      int absoluteOffset = celPolicySource.getContent().lineOffsets().get(absoluteLine - 2);

      return celPolicySource
          .getOffsetLocation(absoluteOffset + absoluteColumn)
          .orElse(CelSourceLocation.NONE);
    }

    private boolean hasError() {
      return !issues.isEmpty();
    }

    private String getIssueString() {
      return CelIssue.toDisplayString(issues, celPolicySource);
    }

    private CompilerContext(CelPolicySource celPolicySource) {
      this.issues = new ArrayList<>();
      this.celPolicySource = celPolicySource;
    }
  }

  static final class Builder implements CelPolicyCompilerBuilder {
    private final Cel cel;
    private String variablesPrefix;
    private int iterationLimit;
    private ImmutableList<CelAstOptimizer> optimizers;
    private Optional<CelAstValidator> astDepthLimitValidator;

    private Builder(Cel cel) {
      this.cel = cel;
      this.astDepthLimitValidator = Optional.of(AstDepthLimitValidator.DEFAULT);
    }

    @Override
    @CanIgnoreReturnValue
    public Builder setVariablesPrefix(String prefix) {
      this.variablesPrefix = checkNotNull(prefix);
      return this;
    }

    @Override
    @CanIgnoreReturnValue
    public Builder setIterationLimit(int iterationLimit) {
      this.iterationLimit = iterationLimit;
      return this;
    }

    @Override
    @CanIgnoreReturnValue
    public Builder setAstDepthLimit(int astDepthLimit) {
      if (astDepthLimit < 0) {
        astDepthLimitValidator = Optional.empty();
      } else {
        astDepthLimitValidator = Optional.of(AstDepthLimitValidator.newInstance(astDepthLimit));
      }
      return this;
    }

    @Override
    public Builder setOptimizers(List<CelAstOptimizer> optimizers) {
      this.optimizers = ImmutableList.copyOf(optimizers);
      return this;
    }

    @Override
    public CelPolicyCompiler build() {
      return new CelPolicyCompilerImpl(
          cel, this.variablesPrefix, this.iterationLimit, this.optimizers, astDepthLimitValidator);
    }
  }

  static Builder newBuilder(Cel cel) {
    return new Builder(cel)
        .setVariablesPrefix(DEFAULT_VARIABLE_PREFIX)
        .setIterationLimit(DEFAULT_ITERATION_LIMIT)
        .setOptimizers(
            ImmutableList.of(
                ConstantFoldingOptimizer.getInstance(),
                SubexpressionOptimizer.newInstance(
                    SubexpressionOptimizerOptions.newBuilder().populateMacroCalls(true).build())));
  }

  private CelPolicyCompilerImpl(
      Cel cel,
      String variablesPrefix,
      int iterationLimit,
      ImmutableList<CelAstOptimizer> optimizers,
      Optional<CelAstValidator> astDepthValidator) {
    this.cel = checkNotNull(cel);
    this.variablesPrefix = checkNotNull(variablesPrefix);
    this.iterationLimit = iterationLimit;
    this.optimizers = optimizers;
    this.astDepthValidator = astDepthValidator;
  }
}
