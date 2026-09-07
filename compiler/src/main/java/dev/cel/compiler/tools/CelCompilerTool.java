// Copyright 2025 Google LLC
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

package dev.cel.compiler.tools;

import static java.nio.charset.StandardCharsets.UTF_8;

import dev.cel.expr.CheckedExpr;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.io.Files;
import com.google.protobuf.DescriptorProtos.FileDescriptorSet;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.ExtensionRegistry;
import dev.cel.bundle.CelEnvironment;
import dev.cel.bundle.CelEnvironmentException;
import dev.cel.bundle.CelEnvironmentYamlParser;
import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.CelDescriptorUtil;
import dev.cel.common.CelOptions;
import dev.cel.common.CelProtoAbstractSyntaxTree;
import dev.cel.compiler.CelCompiler;
import dev.cel.compiler.CelCompilerBuilder;
import dev.cel.compiler.CelCompilerFactory;
import dev.cel.optimizer.CelAstOptimizer;
import dev.cel.optimizer.CelOptimizer;
import dev.cel.optimizer.CelOptimizerFactory;
import dev.cel.optimizer.optimizers.ConstantFoldingOptimizer;
import dev.cel.optimizer.optimizers.SelectOptimizer;
import dev.cel.optimizer.optimizers.SelectOptimizer.SelectOptimizerOptions;
import dev.cel.optimizer.optimizers.SubexpressionOptimizer;
import dev.cel.optimizer.optimizers.SubexpressionOptimizer.SubexpressionOptimizerOptions;
import dev.cel.parser.CelStandardMacro;
import dev.cel.runtime.CelRuntimeFactory;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Option;

/**
 * CelCompilerTool is a binary that takes a CEL expression in string, compiles it into a
 * dev.cel.expr.CheckedExpr protobuf message, then writes the content to a .binary pb file.
 */
final class CelCompilerTool implements Callable<Integer> {

  @Option(
      names = {"--cel_expression"},
      description = "CEL expression")
  private String celExpression = "";

  @Option(
      names = {"--environment_path"},
      description = "Path to the CEL environment (in YAML)")
  private String celEnvironmentPath = "";

  @Option(
      names = {"--transitive_descriptor_set"},
      description = "Path to the transitive set of descriptors")
  private String transitiveDescriptorSetPath = "";

  @Option(
      names = {"--output"},
      description = "Output path for the compiled binarypb")
  private String output = "";

  @Option(
      names = {"--constant_folding"},
      description = "Enable constant folding optimization on the compiled AST")
  private boolean constantFolding = false;

  @Option(
      names = {"--subexpression_elimination"},
      description = "Enable common subexpression elimination on the compiled AST")
  private boolean subexpressionElimination = false;

  @Option(
      names = {"--optimize_field_selection"},
      description = "Optimize field selection for version skew mitigation")
  private boolean optimizeFieldSelection = false;

  private static final CelOptions CEL_OPTIONS = CelOptions.DEFAULT;

  private static CelCompiler prepareCompiler(
      String celEnvironmentPath, String transitiveDescriptorSetPath)
      throws CelEnvironmentException, IOException {
    CelCompilerBuilder celCompilerBuilder =
        CelCompilerFactory.standardCelCompilerBuilder()
            // TODO: Configure the below through YAML
            .setOptions(CEL_OPTIONS)
            .setStandardMacros(CelStandardMacro.STANDARD_MACROS);

    if (!transitiveDescriptorSetPath.isEmpty()) {
      ImmutableSet<FileDescriptor> transitiveFileDescriptors =
          CelDescriptorUtil.getFileDescriptorsFromFileDescriptorSet(
              load(transitiveDescriptorSetPath));
      celCompilerBuilder.addFileTypes(transitiveFileDescriptors);
    }
    if (celEnvironmentPath.isEmpty()) {
      return celCompilerBuilder.build();
    }

    if (!celEnvironmentPath.toLowerCase(Locale.getDefault()).trim().endsWith(".yaml")) {
      throw new IllegalArgumentException(
          "Only YAML extension is supported for CEL environments. Got: " + celEnvironmentPath);
    }

    CelEnvironmentYamlParser environmentYamlParser = CelEnvironmentYamlParser.newInstance();
    String yamlContent = new String(readFileBytes(celEnvironmentPath), UTF_8);
    CelEnvironment environment = environmentYamlParser.parse(yamlContent);

    return environment.extend(celCompilerBuilder.build(), CEL_OPTIONS);
  }

  private static void writeCheckedExpr(CelAbstractSyntaxTree ast, String filePath)
      throws IOException {
    CheckedExpr checkedExpr = CelProtoAbstractSyntaxTree.fromCelAst(ast).toCheckedExpr();
    Path path = Paths.get(filePath);
    try (FileOutputStream output = new FileOutputStream(path.toFile())) {
      checkedExpr.writeTo(output);
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
    return Files.toByteArray(new File(path));
  }

  @Override
  public Integer call() {
    CelCompiler celCompiler;
    try {
      celCompiler = prepareCompiler(celEnvironmentPath, transitiveDescriptorSetPath);
    } catch (Exception e) {
      String errorMessage =
          String.format(
              "Failed to create a CEL compilation environment. Reason: %s", e.getMessage());
      System.err.print(errorMessage);
      return -1;
    }

    try {
      CelAbstractSyntaxTree ast = celCompiler.compile(celExpression).getAst();
      ImmutableList.Builder<CelAstOptimizer> optimizers = ImmutableList.builder();
      if (constantFolding) {
        optimizers.add(ConstantFoldingOptimizer.getInstance());
      }
      if (subexpressionElimination) {
        optimizers.add(
            SubexpressionOptimizer.newInstance(
                SubexpressionOptimizerOptions.newBuilder().populateMacroCalls(true).build()));
      }
      if (optimizeFieldSelection) {
        SelectOptimizerOptions.Builder optionsBuilder = SelectOptimizerOptions.newBuilder();
        if (!transitiveDescriptorSetPath.isEmpty()) {
          ImmutableSet<FileDescriptor> transitiveFileDescriptors =
              CelDescriptorUtil.getFileDescriptorsFromFileDescriptorSet(
                  load(transitiveDescriptorSetPath));
          optionsBuilder.addFileDescriptors(transitiveFileDescriptors);
        }
        optimizers.add(SelectOptimizer.newInstance(optionsBuilder.build()));
      }

      ImmutableList<CelAstOptimizer> astOptimizers = optimizers.build();
      if (!astOptimizers.isEmpty()) {
        CelOptimizer celOptimizer =
            CelOptimizerFactory.standardCelOptimizerBuilder(
                    celCompiler, CelRuntimeFactory.standardCelRuntimeBuilder().build())
                .addAstOptimizers(astOptimizers)
                .build();
        ast = celOptimizer.optimize(ast);
      }
      writeCheckedExpr(ast, output);
    } catch (Exception e) {
      String errorMessage =
          String.format(
              "\nFailed to compile CEL Expression: [%s].\nReason: %s\n\n",
              celExpression, e.getMessage());
      System.err.print(errorMessage);
      return -1;
    }

    return 0;
  }

  public static void main(String[] args) {
    CelCompilerTool compilerTool = new CelCompilerTool();
    CommandLine cmd = new CommandLine(compilerTool);
    cmd.setTrimQuotes(false);
    cmd.parseArgs(args);

    int exitCode = cmd.execute(args);
    System.exit(exitCode);
  }

  CelCompilerTool() {}
}
