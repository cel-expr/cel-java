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

package dev.cel.verifier.tools;

import com.google.common.collect.ImmutableMap;
import dev.cel.bundle.Cel;
import dev.cel.bundle.CelBuilder;
import dev.cel.bundle.CelFactory;
import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.CelOptions;
import dev.cel.common.types.CelType;
import dev.cel.compiler.CelCompiler;
import dev.cel.compiler.CelCompilerBuilder;
import dev.cel.compiler.CelCompilerFactory;
import dev.cel.extensions.CelExtensions;
import dev.cel.parser.CelStandardMacro;
import dev.cel.policy.CelPolicy;
import dev.cel.policy.CelPolicyCompiler;
import dev.cel.policy.CelPolicyCompilerFactory;
import dev.cel.policy.CelPolicyParser;
import dev.cel.policy.CelPolicyParserFactory;
import dev.cel.verifier.CelPolicyVerifier;
import dev.cel.verifier.CelPolicyVerifierFactory;
import dev.cel.verifier.CelVerificationResult;
import dev.cel.verifier.CelVerifier;
import dev.cel.verifier.CelVerifierBuilder;
import dev.cel.verifier.CelVerifierFactory;
import java.util.Map;

/** Core decoupled engine that executes formal verification operations. */
final class CelVerifierToolCore {

  private CelVerifierToolCore() {}

  /** Checks if a single CEL expression is satisfiable. */
  static CelVerificationResult checkSatisfiable(
      String expression, Map<String, CelType> variables, VerificationOptions options)
      throws Exception {
    CelCompiler compiler = buildCompiler(variables);
    CelAbstractSyntaxTree ast = compiler.compile(expression).getAst();
    CelVerifier verifier = buildVerifier(options);
    return verifier.isSatisfiable(ast);
  }

  /** Checks if a single CEL expression is valid (always true). */
  static CelVerificationResult checkValid(
      String expression, Map<String, CelType> variables, VerificationOptions options)
      throws Exception {
    CelCompiler compiler = buildCompiler(variables);
    CelAbstractSyntaxTree ast = compiler.compile(expression).getAst();
    CelVerifier verifier = buildVerifier(options);
    return verifier.isAlwaysTrue(ast);
  }

  /** Proves logical equivalence between two CEL expressions. */
  static CelVerificationResult verifyEquivalence(
      String expressionA,
      String expressionB,
      Map<String, CelType> variables,
      VerificationOptions options)
      throws Exception {
    CelCompiler compiler = buildCompiler(variables);
    CelAbstractSyntaxTree astA = compiler.compile(expressionA).getAst();
    CelAbstractSyntaxTree astB = compiler.compile(expressionB).getAst();
    CelVerifier verifier = buildVerifier(options);
    return verifier.verifyEquivalence(astA, astB);
  }

  /** Verifies custom invariants in a YAML policy content string. */
  static ImmutableMap<String, CelVerificationResult> verifyPolicyInvariants(
      String yamlContent, Map<String, CelType> variables, VerificationOptions options)
      throws Exception {
    CelPolicyParser parser = CelPolicyParserFactory.newYamlParserBuilder().build();
    CelPolicy policy = parser.parse(yamlContent);

    CelPolicyVerifier policyVerifier = buildPolicyVerifier(variables, options);
    return policyVerifier.verifyInvariants(policy);
  }

  /** Verifies equivalence between two YAML policy content strings. */
  static CelVerificationResult verifyPolicyEquivalence(
      String yamlContentA,
      String yamlContentB,
      Map<String, CelType> variables,
      VerificationOptions options)
      throws Exception {
    CelPolicyParser parser = CelPolicyParserFactory.newYamlParserBuilder().build();
    CelPolicy policyA = parser.parse(yamlContentA);
    CelPolicy policyB = parser.parse(yamlContentB);

    CelPolicyVerifier policyVerifier = buildPolicyVerifier(variables, options);
    return policyVerifier.verifyEquivalence(policyA, policyB);
  }

  static CelCompiler buildCompiler(Map<String, CelType> variables) {
    CelCompilerBuilder builder =
        CelCompilerFactory.standardCelCompilerBuilder()
            .setStandardMacros(CelStandardMacro.STANDARD_MACROS)
            .addLibraries(
                CelExtensions.bindings(),
                CelExtensions.comprehensions(),
                CelExtensions.encoders(CelOptions.DEFAULT),
                CelExtensions.lists(),
                CelExtensions.math(),
                CelExtensions.optional(),
                CelExtensions.protos(),
                CelExtensions.regex(),
                CelExtensions.sets(CelOptions.DEFAULT),
                CelExtensions.strings());
    for (Map.Entry<String, CelType> entry : variables.entrySet()) {
      builder.addVar(entry.getKey(), entry.getValue());
    }
    return builder.build();
  }

  static CelVerifier buildVerifier(VerificationOptions options) {
    CelVerifierBuilder builder =
        CelVerifierFactory.newVerifier()
            .setTimeout(options.getTimeout())
            .setComprehensionUnrollLimit(options.getComprehensionUnrollLimit());

    for (String unknown : options.getUnknownIdentifiers()) {
      builder.addUnknownIdentifier(unknown);
    }
    return builder.build();
  }

  private static CelPolicyVerifier buildPolicyVerifier(
      Map<String, CelType> variables, VerificationOptions options) {
    CelBuilder celBuilder =
        CelFactory.plannerCelBuilder()
            .setStandardMacros(CelStandardMacro.STANDARD_MACROS)
            .addCompilerLibraries(
                CelExtensions.optional(),
                CelExtensions.bindings(),
                CelExtensions.encoders(CelOptions.DEFAULT),
                CelExtensions.math(),
                CelExtensions.strings());
    for (Map.Entry<String, CelType> entry : variables.entrySet()) {
      celBuilder.addVar(entry.getKey(), entry.getValue());
    }
    Cel celBundle = celBuilder.build();
    CelPolicyCompiler policyCompiler =
        CelPolicyCompilerFactory.newPolicyCompiler(celBundle).build();
    CelVerifier astVerifier = buildVerifier(options);

    return CelPolicyVerifierFactory.newVerifier(policyCompiler, astVerifier).build();
  }
}
