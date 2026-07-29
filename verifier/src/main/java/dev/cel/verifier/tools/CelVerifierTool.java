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
import dev.cel.common.types.CelType;
import dev.cel.verifier.CelVerificationResult;
import dev.cel.verifier.CelVerificationResult.VerificationStatus;
import dev.cel.verifier.tools.VerificationOptions.OutputFormat;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/** Main Picocli entrypoint for the CEL Formal Verification CLI. */
@Command(
    name = "cel-verifier",
    mixinStandardHelpOptions = true,
    version = "cel-verifier 0.14.0",
    description = "CEL-Java Formal Verification CLI & REPL Tool",
    subcommands = {
      CelVerifierTool.CheckSatCommand.class,
      CelVerifierTool.CheckValidCommand.class,
      CelVerifierTool.VerifyEquivCommand.class,
      CelVerifierTool.VerifyPolicyCommand.class,
      CelVerifierTool.ReplCommand.class
    })
public final class CelVerifierTool implements Runnable {

  @Override
  public void run() {
    CommandLine.usage(this, System.out);
  }

  /** Options shared across all verification commands. */
  abstract static class BaseVerificationCommand implements Callable<Integer> {

    @Option(
        names = {"--var", "-v"},
        description = "Declared variable in 'name:type' format (e.g., --var role:string --var port:int)")
    List<String> variables = new ArrayList<>();

    @Option(
        names = {"--unknown", "-u"},
        description = "Identifier to permit evaluating to Unknown (e.g., --unknown request.headers)")
    List<String> unknownIdentifiers = new ArrayList<>();

    @Option(
        names = {"--timeout"},
        description = "Solver timeout in seconds (default: 10)")
    int timeoutSeconds = 10;

    @Option(
        names = {"--unroll-limit"},
        description = "Comprehension unroll limit for BMC (default: 5)")
    int comprehensionUnrollLimit = 5;

    @Option(
        names = {"--output_format", "-fmt"},
        description = "Output format: TEXT or JSON (default: TEXT)")
    String outputFormatStr = "TEXT";

    protected VerificationOptions getOptions() {
      OutputFormat format = OutputFormat.TEXT;
      try {
        format = OutputFormat.valueOf(outputFormatStr.toUpperCase());
      } catch (IllegalArgumentException e) {
        System.err.println("Invalid output format '" + outputFormatStr + "'. Defaulting to TEXT.");
      }
      return VerificationOptions.builder()
          .setTimeout(Duration.ofSeconds(timeoutSeconds))
          .setComprehensionUnrollLimit(comprehensionUnrollLimit)
          .setUnknownIdentifiers(unknownIdentifiers)
          .setOutputFormat(format)
          .build();
    }

    protected int handleSingleResult(CelVerificationResult result, OutputFormat format) {
      if (format == OutputFormat.JSON) {
        System.out.println(FormatUtils.formatJsonResult(result));
      } else {
        System.out.println(FormatUtils.formatTextResult(result));
      }

      if (result.status() == VerificationStatus.VERIFIED) {
        return 0;
      } else if (result.status() == VerificationStatus.VIOLATED) {
        return 1;
      } else {
        return 2;
      }
    }
  }

  @Command(
      name = "check-sat",
      description = "Verify satisfiability of a CEL expression & generate witness model")
  static class CheckSatCommand extends BaseVerificationCommand {

    @Option(
        names = {"--expr", "-e"},
        required = true,
        description = "CEL expression string to verify")
    String expression = "";

    @Override
    public Integer call() {
      try {
        VerificationOptions options = getOptions();
        ImmutableMap<String, CelType> vars = VerificationOptions.parseVariables(variables);
        CelVerificationResult result =
            CelVerifierToolCore.checkSatisfiable(expression, vars, options);
        return handleSingleResult(result, options.getOutputFormat());
      } catch (dev.cel.common.CelValidationException e) {
        System.err.println("Compilation error:\n" + e.getMessage());
        return 3;
      } catch (dev.cel.policy.CelPolicyValidationException e) {
        System.err.println("Policy compilation error:\n" + e.getMessage());
        return 3;
      } catch (Exception e) {
        System.err.println("Verification error: " + e.getMessage());
        return 3;
      }
    }
  }

  @Command(
      name = "check-valid",
      description = "Verify validity (isAlwaysTrue) of a CEL expression & generate counterexample")
  static class CheckValidCommand extends BaseVerificationCommand {

    @Option(
        names = {"--expr", "-e"},
        required = true,
        description = "CEL expression string to verify")
    String expression = "";

    @Override
    public Integer call() {
      try {
        VerificationOptions options = getOptions();
        ImmutableMap<String, CelType> vars = VerificationOptions.parseVariables(variables);
        CelVerificationResult result =
            CelVerifierToolCore.checkValid(expression, vars, options);
        return handleSingleResult(result, options.getOutputFormat());
      } catch (dev.cel.common.CelValidationException e) {
        System.err.println("Compilation error:\n" + e.getMessage());
        return 3;
      } catch (dev.cel.policy.CelPolicyValidationException e) {
        System.err.println("Policy compilation error:\n" + e.getMessage());
        return 3;
      } catch (Exception e) {
        System.err.println("Verification error: " + e.getMessage());
        return 3;
      }
    }
  }

  @Command(
      name = "verify-equiv",
      description = "Prove logical equivalence between two CEL expressions")
  static class VerifyEquivCommand extends BaseVerificationCommand {

    @Option(
        names = {"--expr1"},
        required = true,
        description = "First CEL expression")
    String expressionA = "";

    @Option(
        names = {"--expr2"},
        required = true,
        description = "Second CEL expression")
    String expressionB = "";

    @Override
    public Integer call() {
      try {
        VerificationOptions options = getOptions();
        ImmutableMap<String, CelType> vars = VerificationOptions.parseVariables(variables);
        CelVerificationResult result =
            CelVerifierToolCore.verifyEquivalence(expressionA, expressionB, vars, options);
        return handleSingleResult(result, options.getOutputFormat());
      } catch (dev.cel.common.CelValidationException e) {
        System.err.println("Compilation error:\n" + e.getMessage());
        return 3;
      } catch (dev.cel.policy.CelPolicyValidationException e) {
        System.err.println("Policy compilation error:\n" + e.getMessage());
        return 3;
      } catch (Exception e) {
        System.err.println("Verification error: " + e.getMessage());
        return 3;
      }
    }
  }

  @Command(
      name = "verify-policy",
      description = "Verify policy invariants defined in a YAML policy file")
  static class VerifyPolicyCommand extends BaseVerificationCommand {

    @Option(
        names = {"--file", "-f"},
        required = true,
        description = "Path to policy YAML file")
    String filePath = "";

    @Override
    public Integer call() {
      try {
        VerificationOptions options = getOptions();
        ImmutableMap<String, CelType> vars = VerificationOptions.parseVariables(variables);
        File file = new File(filePath);
        if (!file.exists()) {
          System.err.println("File not found: " + filePath);
          return 3;
        }
        String yamlContent = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);

        ImmutableMap<String, CelVerificationResult> results =
            CelVerifierToolCore.verifyPolicyInvariants(yamlContent, vars, options);

        if (options.getOutputFormat() == OutputFormat.JSON) {
          System.out.println(FormatUtils.formatJsonPolicyResults(file.getName(), results));
        } else {
          System.out.println(FormatUtils.formatTextPolicyResults(file.getName(), results));
        }

        boolean anyViolated = false;
        boolean anyInconclusive = false;
        for (CelVerificationResult res : results.values()) {
          if (res.status() == VerificationStatus.VIOLATED) {
            anyViolated = true;
          } else if (res.status() == VerificationStatus.INCONCLUSIVE) {
            anyInconclusive = true;
          }
        }

        if (anyViolated) {
          return 1;
        } else if (anyInconclusive) {
          return 2;
        }
        return 0;
      } catch (dev.cel.common.CelValidationException e) {
        System.err.println("Compilation error:\n" + e.getMessage());
        return 3;
      } catch (dev.cel.policy.CelPolicyValidationException e) {
        System.err.println("Policy compilation error:\n" + e.getMessage());
        return 3;
      } catch (Exception e) {
        System.err.println("Policy verification error: " + e.getMessage());
        return 3;
      }
    }
  }

  @Command(
      name = "repl",
      description = "Launch interactive CEL Formal Verification REPL shell")
  static class ReplCommand implements Callable<Integer> {

    @Override
    public Integer call() {
      return CelVerifierRepl.runInteractiveRepl();
    }
  }

  public static void main(String[] args) {
    int exitCode = new CommandLine(new CelVerifierTool()).execute(args);
    System.exit(exitCode);
  }
}
