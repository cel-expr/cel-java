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

package dev.cel.policy.tools;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.base.Ascii;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.protobuf.DescriptorProtos.FileDescriptorSet;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.ExtensionRegistry;
import com.google.protobuf.Message;
import com.google.protobuf.TextFormat;
import dev.cel.bundle.Cel;
import dev.cel.bundle.CelBuilder;
import dev.cel.bundle.CelEnvironment;
import dev.cel.bundle.CelEnvironmentYamlParser;
import dev.cel.bundle.CelFactory;
import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.CelDescriptorUtil;
import dev.cel.common.CelOptions;
import dev.cel.common.CelProtoAbstractSyntaxTree;
import dev.cel.common.CelProtoV1Alpha1AbstractSyntaxTree;
import dev.cel.extensions.CelOptionalLibrary;
import dev.cel.optimizer.optimizers.ConstantFoldingOptimizer;
import dev.cel.optimizer.optimizers.SelectOptimizer;
import dev.cel.optimizer.optimizers.SelectOptimizer.SelectOptimizerOptions;
import dev.cel.optimizer.optimizers.SubexpressionOptimizer;
import dev.cel.optimizer.optimizers.SubexpressionOptimizer.SubexpressionOptimizerOptions;
import dev.cel.parser.CelStandardMacro;
import dev.cel.policy.CelPolicy;
import dev.cel.policy.CelPolicyCompiler;
import dev.cel.policy.CelPolicyCompilerBuilder;
import dev.cel.policy.CelPolicyCompilerFactory;
import dev.cel.policy.CelPolicyParser;
import dev.cel.policy.CelPolicyParserBuilder;
import dev.cel.policy.CelPolicyParserFactory;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.concurrent.Callable;
import org.yaml.snakeyaml.nodes.Node;
import picocli.CommandLine;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * CelPolicyCompilerTool is a binary tool that compiles a CEL policy (.celpolicy or YAML) into a
 * CheckedExpr protobuf message and writes it to a file or stdout.
 */
public final class CelPolicyCompilerTool implements Callable<Integer> {

  @Option(
      names = {"--policy"},
      description = "Path to the CEL policy file")
  private String policyPath = "";

  @Parameters(
      index = "0",
      arity = "0..1",
      description = "Positional path to the CEL policy file if --policy is not specified")
  private String positionalPolicyPath = "";

  @Option(
      names = {"--config", "--environment_path"},
      description = "Path to the CEL environment (in YAML)")
  private String configPath = "";

  @Option(
      names = {"--base_config"},
      description = "Path to the base CEL environment (in YAML)")
  private String baseConfigPath = "";

  @Option(
      names = {"--transitive_descriptor_set", "--file_descriptor_set"},
      description = "Path to the transitive set of descriptors")
  private String transitiveDescriptorSetPath = "";

  @Option(
      names = {"--output"},
      description = "Output path for the compiled binarypb/textpb")
  private String output = "";

  @Option(
      names = {"--output_format"},
      defaultValue = "binarypb",
      description = "Output format: binarypb, textpb, or textproto")
  private String outputFormat = "binarypb";

  @Option(
      names = {"--output_version"},
      defaultValue = "canonical",
      description = "Output version: canonical or v1alpha1")
  private String outputVersion = "canonical";

  @Option(
      names = {"--optimize_field_selection"},
      description = "Optimize field selection for version skew mitigation")
  private boolean optimizeFieldSelection = false;

  @Option(
      names = {"--simple_variables"},
      description = "Enable simple variables parsing in policy")
  private boolean simpleVariables = false;

  private static final CelOptions CEL_OPTIONS = CelOptions.DEFAULT;

  @Override
  public Integer call() {
    String effectivePolicyPath = policyPath.isEmpty() ? positionalPolicyPath : policyPath;
    if (effectivePolicyPath.isEmpty()) {
      System.err.println(
          "Error: Policy file path must be specified via --policy or as a positional argument.");
      return -1;
    }

    Cel cel;
    ImmutableSet<FileDescriptor> transitiveFileDescriptors;
    try {
      CelBuilder celBuilder =
          CelFactory.standardCelBuilder()
              .setOptions(CEL_OPTIONS)
              .setStandardMacros(CelStandardMacro.STANDARD_MACROS)
              .addCompilerLibraries(CelOptionalLibrary.INSTANCE)
              .addRuntimeLibraries(CelOptionalLibrary.INSTANCE);

      if (!transitiveDescriptorSetPath.isEmpty()) {
        transitiveFileDescriptors =
            CelDescriptorUtil.getFileDescriptorsFromFileDescriptorSet(
                load(transitiveDescriptorSetPath));
        celBuilder.addFileTypes(transitiveFileDescriptors);
      } else {
        transitiveFileDescriptors = ImmutableSet.of();
      }

      cel = celBuilder.build();

      CelEnvironmentYamlParser environmentYamlParser = CelEnvironmentYamlParser.newInstance();
      if (!baseConfigPath.isEmpty()) {
        validateYamlExtension(baseConfigPath, "base CEL environment");
        String baseYaml = new String(readFileBytes(baseConfigPath), UTF_8);
        CelEnvironment baseEnv = environmentYamlParser.parse(baseYaml, baseConfigPath);
        cel = baseEnv.extend(cel, CEL_OPTIONS);
      }

      if (!configPath.isEmpty()) {
        validateYamlExtension(configPath, "CEL environment");
        String envYaml = new String(readFileBytes(configPath), UTF_8);
        CelEnvironment env = environmentYamlParser.parse(envYaml, configPath);
        cel = env.extend(cel, CEL_OPTIONS);
      }
    } catch (Exception e) {
      System.err.printf(
          "Failed to create a CEL compilation environment. Reason: %s%n", e.getMessage());
      return -1;
    }

    CelPolicy policy;
    try {
      CelPolicyParserBuilder<Node> parserBuilder = CelPolicyParserFactory.newYamlParserBuilder();
      if (simpleVariables) {
        parserBuilder.enableSimpleVariables(true);
      }
      CelPolicyParser policyParser = parserBuilder.build();
      String policyYaml = new String(readFileBytes(effectivePolicyPath), UTF_8);
      policy = policyParser.parse(policyYaml, effectivePolicyPath);
    } catch (Exception e) {
      System.err.printf(
          "Failed to parse CEL policy: [%s]. Reason: %s%n", effectivePolicyPath, e.getMessage());
      return -1;
    }

    try {
      CelPolicyCompilerBuilder policyCompilerBuilder =
          CelPolicyCompilerFactory.newPolicyCompiler(cel);

      if (optimizeFieldSelection) {
        SelectOptimizerOptions.Builder optionsBuilder = SelectOptimizerOptions.newBuilder();
        if (!transitiveFileDescriptors.isEmpty()) {
          optionsBuilder.addFileDescriptors(transitiveFileDescriptors);
        }
        policyCompilerBuilder.setOptimizers(
            ImmutableList.of(
                ConstantFoldingOptimizer.getInstance(),
                SubexpressionOptimizer.newInstance(
                    SubexpressionOptimizerOptions.newBuilder().populateMacroCalls(true).build()),
                SelectOptimizer.newInstance(optionsBuilder.build())));
      }

      CelPolicyCompiler policyCompiler = policyCompilerBuilder.build();
      CelAbstractSyntaxTree ast = policyCompiler.compile(policy);

      writeOutput(ast, output, outputFormat, outputVersion);
    } catch (Exception e) {
      System.err.printf(
          "%nFailed to compile CEL policy: [%s].%nReason: %s%n%n",
          effectivePolicyPath, e.getMessage());
      return -1;
    }

    return 0;
  }

  private static void writeOutput(
      CelAbstractSyntaxTree ast, String filePath, String format, String version)
      throws IOException {
    Message checkedExpr;
    if (Ascii.equalsIgnoreCase("v1alpha1", version)) {
      checkedExpr = CelProtoV1Alpha1AbstractSyntaxTree.fromCelAst(ast).toCheckedExpr();
    } else if (Ascii.equalsIgnoreCase("canonical", version)) {
      checkedExpr = CelProtoAbstractSyntaxTree.fromCelAst(ast).toCheckedExpr();
    } else {
      throw new IllegalArgumentException(
          "Unsupported output version: " + version + ". Supported versions: canonical, v1alpha1");
    }

    boolean isText =
        Ascii.equalsIgnoreCase("textpb", format) || Ascii.equalsIgnoreCase("textproto", format);
    if (!isText && !Ascii.equalsIgnoreCase("binarypb", format)) {
      throw new IllegalArgumentException(
          "Unsupported output format: "
              + format
              + ". Supported formats: binarypb, textpb, textproto");
    }

    if (filePath.isEmpty() || filePath.equals("-")) {
      if (isText) {
        OutputStreamWriter writer = new OutputStreamWriter(System.out, UTF_8);
        TextFormat.printer().print(checkedExpr, writer);
        writer.flush();
      } else {
        checkedExpr.writeTo(System.out);
        System.out.flush();
      }
    } else {
      Path path = Paths.get(filePath);
      if (path.getParent() != null) {
        Files.createDirectories(path.getParent());
      }
      if (isText) {
        String text = TextFormat.printer().printToString(checkedExpr);
        Files.write(path, text.getBytes(UTF_8));
      } else {
        try (FileOutputStream outputStream = new FileOutputStream(path.toFile())) {
          checkedExpr.writeTo(outputStream);
        }
      }
    }
  }

  private static void validateYamlExtension(String path, String description) {
    String lower = path.toLowerCase(Locale.getDefault()).trim();
    if (!lower.endsWith(".yaml") && !lower.endsWith(".yml")) {
      throw new IllegalArgumentException(
          String.format("Only YAML extension is supported for %s. Got: %s", description, path));
    }
  }

  private static FileDescriptorSet load(String descriptorSetPath) {
    try {
      byte[] descriptorBytes = readFileBytes(descriptorSetPath);
      return FileDescriptorSet.parseFrom(descriptorBytes, ExtensionRegistry.getEmptyRegistry());
    } catch (IOException e) {
      throw new IllegalArgumentException(
          "Failed to load FileDescriptorSet from path: " + descriptorSetPath, e);
    }
  }

  private static byte[] readFileBytes(String path) throws IOException {
    return Files.readAllBytes(Paths.get(path));
  }

  public static void main(String[] args) {
    CelPolicyCompilerTool compilerTool = new CelPolicyCompilerTool();
    CommandLine cmd = new CommandLine(compilerTool);
    cmd.setTrimQuotes(false);
    int exitCode = cmd.execute(args);
    System.exit(exitCode);
  }

  public CelPolicyCompilerTool() {}
}
