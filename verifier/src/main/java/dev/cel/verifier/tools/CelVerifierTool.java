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
import dev.cel.common.CelValidationException;
import dev.cel.common.types.CelType;
import dev.cel.policy.CelPolicyValidationException;
import dev.cel.verifier.CelVerificationResult;
import dev.cel.verifier.CelVerificationResult.VerificationStatus;
import dev.cel.verifier.tools.VerificationOptions.OutputFormat;
import java.io.File;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.IVersionProvider;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/** Main Picocli entrypoint for the CEL Formal Verification CLI. */
@Command(
    name = "cel-verifier",
    mixinStandardHelpOptions = true,
    versionProvider = CelVerifierTool.VersionProvider.class,
    description = "CEL-Java Formal Verification CLI Tool",
    subcommands = {
      CelVerifierTool.CheckSatCommand.class,
      CelVerifierTool.CheckValidCommand.class,
      CelVerifierTool.VerifyEquivCommand.class,
      CelVerifierTool.VerifyPolicyCommand.class
    })
public final class CelVerifierTool implements Runnable {

  static final int EXIT_CODE_VERIFIED = 0;
  static final int EXIT_CODE_VIOLATED = 1;
  static final int EXIT_CODE_INCONCLUSIVE = 2;
  static final int EXIT_CODE_ERROR = 3;

  static final class VersionProvider implements IVersionProvider {
    @Override
    public String[] getVersion() {
      return new String[] {"cel-verifier " + CelVersion.VERSION};
    }
  }

  @Spec private CommandSpec spec;

  @Override
  public void run() {
    spec.commandLine().usage(spec.commandLine().getOut());
  }

  /** Options shared across all verification commands. */
  abstract static class BaseVerificationCommand implements Callable<Integer> {

    @Spec private CommandSpec spec;

    PrintWriter out() {
      return spec != null
          ? spec.commandLine().getOut()
          : new PrintWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8), true);
    }

    PrintWriter err() {
      return spec != null
          ? spec.commandLine().getErr()
          : new PrintWriter(new OutputStreamWriter(System.err, StandardCharsets.UTF_8), true);
    }

    @Option(
        names = {"--var", "-v"},
        description =
            "Declared variable in 'name:type' format (e.g., --var role:string --var port:int)")
    List<String> variables = new ArrayList<>();

    @Option(
        names = {"--unknown", "-u"},
        description =
            "Identifier to permit evaluating to Unknown (e.g., --unknown request.headers)")
    List<String> unknownIdentifiers = new ArrayList<>();

    @Option(
        names = {"--timeout"},
        description = "Solver timeout in seconds (default: 10)")
    int timeoutSeconds = (int) VerificationOptions.DEFAULT_TIMEOUT.getSeconds();

    @Option(
        names = {"--unroll-limit"},
        description = "Comprehension unroll limit for BMC (default: 5)")
    int comprehensionUnrollLimit = VerificationOptions.DEFAULT_COMPREHENSION_UNROLL_LIMIT;

    @Option(
        names = {"--output_format", "-fmt"},
        description = "Output format: TEXT or JSON (default: TEXT)")
    String outputFormatStr = VerificationOptions.DEFAULT_OUTPUT_FORMAT.name();

    @FunctionalInterface
    protected interface CommandAction {
      int execute(VerificationOptions options, ImmutableMap<String, CelType> vars) throws Exception;
    }

    protected int executeCommand(CommandAction action) {
      return executeCommand("Verification error", action);
    }

    protected int executeCommand(String errorPrefix, CommandAction action) {
      try {
        VerificationOptions options = getOptions();
        ImmutableMap<String, CelType> vars = VerificationOptions.parseVariables(variables);
        return action.execute(options, vars);
      } catch (CelValidationException e) {
        err().println("Compilation error:\n" + e.getMessage());
        return EXIT_CODE_ERROR;
      } catch (CelPolicyValidationException e) {
        err().println("Policy compilation error:\n" + e.getMessage());
        return EXIT_CODE_ERROR;
      } catch (Exception e) {
        err().println(errorPrefix + ": " + e.getMessage());
        return EXIT_CODE_ERROR;
      }
    }

    protected VerificationOptions getOptions() {
      OutputFormat format = OutputFormat.TEXT;
      try {
        format = OutputFormat.valueOf(outputFormatStr.toUpperCase(Locale.US));
      } catch (IllegalArgumentException e) {
        err().println("Invalid output format '" + outputFormatStr + "'. Defaulting to TEXT.");
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
        out().println(FormatUtils.formatJsonResult(result));
      } else {
        out().println(FormatUtils.formatTextResult(result));
      }

      if (result.status() == VerificationStatus.VERIFIED) {
        return EXIT_CODE_VERIFIED;
      } else if (result.status() == VerificationStatus.VIOLATED) {
        return EXIT_CODE_VIOLATED;
      } else {
        return EXIT_CODE_INCONCLUSIVE;
      }
    }
  }

  /** Base command for commands operating on a single CEL expression. */
  abstract static class SingleExpressionCommand extends BaseVerificationCommand {
    @Option(
        names = {"--expr", "-e"},
        required = true,
        description = "CEL expression string to verify")
    String expression = "";
  }

  @Command(
      name = "check-sat",
      description = "Verify satisfiability of a CEL expression & generate witness model")
  static class CheckSatCommand extends SingleExpressionCommand {

    @Override
    public Integer call() {
      return executeCommand(
          (options, vars) ->
              handleSingleResult(
                  CelVerifierToolCore.checkSatisfiable(expression, vars, options),
                  options.getOutputFormat()));
    }
  }

  @Command(
      name = "check-valid",
      description = "Verify validity (isAlwaysTrue) of a CEL expression & generate counterexample")
  static class CheckValidCommand extends SingleExpressionCommand {

    @Override
    public Integer call() {
      return executeCommand(
          (options, vars) ->
              handleSingleResult(
                  CelVerifierToolCore.checkValid(expression, vars, options),
                  options.getOutputFormat()));
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
      return executeCommand(
          (options, vars) ->
              handleSingleResult(
                  CelVerifierToolCore.verifyEquivalence(expressionA, expressionB, vars, options),
                  options.getOutputFormat()));
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
      return executeCommand(
          "Policy verification error",
          (options, vars) -> {
            File file = new File(filePath);
            if (!file.exists()) {
              err().println("File not found: " + filePath);
              return EXIT_CODE_ERROR;
            }
            String yamlContent =
                new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);

            ImmutableMap<String, CelVerificationResult> results =
                CelVerifierToolCore.verifyPolicyInvariants(yamlContent, vars, options);

            if (options.getOutputFormat() == OutputFormat.JSON) {
              out().println(FormatUtils.formatJsonPolicyResults(file.getName(), results));
            } else {
              out().println(FormatUtils.formatTextPolicyResults(file.getName(), results));
            }

            return getPolicyExitCode(results);
          });
    }

    private static int getPolicyExitCode(ImmutableMap<String, CelVerificationResult> results) {
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
        return EXIT_CODE_VIOLATED;
      } else if (anyInconclusive) {
        return EXIT_CODE_INCONCLUSIVE;
      }
      return EXIT_CODE_VERIFIED;
    }
  }

  public static void main(String[] args) {
    int exitCode = new CommandLine(new CelVerifierTool()).execute(args);
    System.exit(exitCode);
  }
}
