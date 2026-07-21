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

import com.google.common.collect.ImmutableMap;
import dev.cel.bundle.Cel;
import dev.cel.bundle.CelBuilder;
import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.CelIssue;
import dev.cel.common.CelSource;
import dev.cel.common.CelValidationException;
import dev.cel.common.CelVarDecl;
import dev.cel.common.formats.ValueString;
import dev.cel.policy.CelCompiledRule;
import dev.cel.policy.CelCompiledRule.CelCompiledVariable;
import dev.cel.policy.CelPolicy;
import dev.cel.policy.CelPolicyCompiler;
import dev.cel.policy.CelPolicyValidationException;

/** Implementation of CelPolicyVerifier using a CelVerifier. */
final class CelPolicyVerifierImpl implements CelPolicyVerifier {

  private static final String INVARIANTS_RESULT_IDENTIFIER = "rule.result";

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

  @Override
  public ImmutableMap<String, CelVerificationResult> verifyInvariants(CelPolicy policy)
      throws CelPolicyValidationException, CelVerificationException {
    if (policy.invariants().isEmpty()) {
      return ImmutableMap.of();
    }

    CelCompiledRule compiledRule = compiler.compileRule(policy);
    CelAbstractSyntaxTree composedPolicyAst = compiler.compose(policy, compiledRule);

    CelBuilder celBuilder = compiledRule.cel().toCelBuilder();
    ImmutableMap.Builder<String, CelAbstractSyntaxTree> boundSymbolsBuilder =
        ImmutableMap.builder();
    for (CelCompiledVariable var : compiledRule.variables()) {
      celBuilder.addVarDeclarations(var.celVarDecl());
      boundSymbolsBuilder.put(var.celVarDecl().name(), var.ast());
    }
    boundSymbolsBuilder.put(INVARIANTS_RESULT_IDENTIFIER, composedPolicyAst);
    celBuilder.addVarDeclarations(
        CelVarDecl.newVarDeclaration(
            INVARIANTS_RESULT_IDENTIFIER, composedPolicyAst.getResultType()));

    Cel localCel = celBuilder.build();
    for (CelPolicy.Variable variable : policy.verificationVariables()) {
      ValueString expression = variable.expression();
      CelAbstractSyntaxTree varAst;
      try {
        varAst = localCel.compile(expression.value()).getAst();
      } catch (CelValidationException e) {
        throw new CelPolicyValidationException(
            CelIssue.toDisplayString(
                e.getErrors(),
                CelSource.newBuilder(expression.value())
                    .setDescription("verification.variables." + variable.name().value())
                    .build()));
      }
      String variableName = variable.name().value();
      CelVarDecl newVariable =
          CelVarDecl.newVarDeclaration("variables." + variableName, varAst.getResultType());
      celBuilder.addVarDeclarations(newVariable);
      boundSymbolsBuilder.put("variables." + variableName, varAst);
      localCel = localCel.toCelBuilder().addVarDeclarations(newVariable).build();
    }
    Cel enrichedCel = celBuilder.build();
    ImmutableMap<String, CelAbstractSyntaxTree> boundSymbols = boundSymbolsBuilder.buildOrThrow();

    ImmutableMap.Builder<String, CelVerificationResult> resultsBuilder = ImmutableMap.builder();
    for (CelPolicy.Invariant invariant : policy.invariants()) {
      CelAbstractSyntaxTree assumeAst;
      try {
        assumeAst = enrichedCel.compile(invariant.assumeSourceString()).getAst();
      } catch (CelValidationException e) {
        throw new CelPolicyValidationException(
            CelIssue.toDisplayString(
                e.getErrors(),
                CelSource.newBuilder(invariant.assumeSourceString())
                    .setDescription("invariant." + invariant.invariantId().value() + ".assume")
                    .build()));
      }

      CelAbstractSyntaxTree assertAst;
      try {
        assertAst = enrichedCel.compile(invariant.assertSourceString()).getAst();
      } catch (CelValidationException e) {
        throw new CelPolicyValidationException(
            CelIssue.toDisplayString(
                e.getErrors(),
                CelSource.newBuilder(invariant.assertSourceString())
                    .setDescription("invariant." + invariant.invariantId().value() + ".assert")
                    .build()));
      }

      if (!(astVerifier instanceof CelVerifierZ3Impl)) {
        throw new UnsupportedOperationException(
            "Invariants verification requires Z3 verifier implementation.");
      }
      CelVerificationResult result =
          ((CelVerifierZ3Impl) astVerifier)
              .verifyImplication(assumeAst, assertAst, boundSymbols);
      resultsBuilder.put(invariant.invariantId().value(), result);
    }

    return resultsBuilder.buildOrThrow();
  }
}
