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

import static com.google.common.truth.Truth.assertThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import dev.cel.expr.CheckedExpr;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.Files;
import com.google.devtools.build.runfiles.Runfiles;
import com.google.protobuf.ExtensionRegistryLite;
import com.google.protobuf.TextFormat;
import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.CelProtoAbstractSyntaxTree;
import dev.cel.common.CelSource;
import dev.cel.expr.conformance.proto3.TestAllTypes;
import dev.cel.extensions.CelOptionalLibrary;
import dev.cel.runtime.CelRuntime;
import dev.cel.runtime.CelRuntimeFactory;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.util.Optional;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import picocli.CommandLine;

@RunWith(JUnit4.class)
public final class CelPolicyCompilerToolTest {

  @Rule public TemporaryFolder tempFolder = new TemporaryFolder();

  private Runfiles runfiles;
  private CelRuntime celRuntime;

  @Before
  public void setUp() throws Exception {
    runfiles = Runfiles.preload().unmapped();
    celRuntime =
        CelRuntimeFactory.standardCelRuntimeBuilder()
            .addLibraries(CelOptionalLibrary.INSTANCE)
            .addMessageTypes(TestAllTypes.getDescriptor())
            .build();
  }

  private String resolveRunfile(String rlocationPath) {
    String resolved = runfiles.rlocation(rlocationPath);
    if (resolved != null && new File(resolved).exists()) {
      return resolved;
    }
    String google3Prefix = "google3/third_party/java/cel/";
    if (rlocationPath.startsWith(google3Prefix)) {
      String stripped = rlocationPath.substring(google3Prefix.length());
      String ossPath = runfiles.rlocation("_main/" + stripped);
      if (ossPath != null && new File(ossPath).exists()) {
        return ossPath;
      }
      ossPath = runfiles.rlocation(stripped);
      if (ossPath != null && new File(ossPath).exists()) {
        return ossPath;
      }
    }
    return resolved != null ? resolved : rlocationPath;
  }

  @Test
  public void compile_basicPolicy_binarypb_success() throws Exception {
    File configFile = tempFolder.newFile("config.yaml");
    Files.asCharSink(configFile, UTF_8)
        .write("name: test-env\nvariables:\n  - name: age\n    type: int\n");

    File policyFile = tempFolder.newFile("policy.yaml");
    Files.asCharSink(policyFile, UTF_8)
        .write(
            "name: age-policy\n"
                + "rule:\n"
                + "  match:\n"
                + "    - condition: age >= 18\n"
                + "      output: '\"adult\"'\n");

    File outputFile = tempFolder.newFile("output.binarypb");

    CommandLine cmd = new CommandLine(new CelPolicyCompilerTool());
    int exitCode =
        cmd.execute(
            "--policy",
            policyFile.getAbsolutePath(),
            "--config",
            configFile.getAbsolutePath(),
            "--output",
            outputFile.getAbsolutePath(),
            "--output_format",
            "binarypb");

    assertThat(exitCode).isEqualTo(0);

    CheckedExpr checkedExpr =
        CheckedExpr.parseFrom(
            Files.toByteArray(outputFile), ExtensionRegistryLite.getEmptyRegistry());
    CelAbstractSyntaxTree ast = CelProtoAbstractSyntaxTree.fromCheckedExpr(checkedExpr).getAst();

    Object result = celRuntime.createProgram(ast).eval(ImmutableMap.of("age", 25L));
    assertThat(result).isEqualTo(Optional.of("adult"));
  }

  @Test
  public void compile_textpb_format_success() throws Exception {
    File configFile = tempFolder.newFile("config.yaml");
    Files.asCharSink(configFile, UTF_8)
        .write("name: test-env\nvariables:\n  - name: user\n    type: string\n");

    File policyFile = tempFolder.newFile("policy.yaml");
    Files.asCharSink(policyFile, UTF_8)
        .write(
            "name: user-policy\n"
                + "rule:\n"
                + "  match:\n"
                + "    - condition: user == \"alice\"\n"
                + "      output: 'true'\n");

    File outputFile = tempFolder.newFile("output.textpb");

    CommandLine cmd = new CommandLine(new CelPolicyCompilerTool());
    int exitCode =
        cmd.execute(
            "--policy",
            policyFile.getAbsolutePath(),
            "--config",
            configFile.getAbsolutePath(),
            "--output",
            outputFile.getAbsolutePath(),
            "--output_format",
            "textpb");

    assertThat(exitCode).isEqualTo(0);

    String content = Files.asCharSource(outputFile, UTF_8).read();
    assertThat(content).contains("call_expr");

    CheckedExpr checkedExpr = TextFormat.parse(content, CheckedExpr.class);
    CelAbstractSyntaxTree ast = CelProtoAbstractSyntaxTree.fromCheckedExpr(checkedExpr).getAst();
    Object result = celRuntime.createProgram(ast).eval(ImmutableMap.of("user", "alice"));
    assertThat(result).isEqualTo(Optional.of(true));
  }

  @Test
  public void compile_outputVersion_v1alpha1_success() throws Exception {
    File configFile = tempFolder.newFile("config.yaml");
    Files.asCharSink(configFile, UTF_8)
        .write("name: test-env\nvariables:\n  - name: x\n    type: int\n");

    File policyFile = tempFolder.newFile("policy.yaml");
    Files.asCharSink(policyFile, UTF_8)
        .write("name: p\nrule:\n  match:\n    - condition: x > 0\n      output: 'true'\n");

    File outputFile = tempFolder.newFile("output.v1alpha1.binarypb");

    CommandLine cmd = new CommandLine(new CelPolicyCompilerTool());
    int exitCode =
        cmd.execute(
            "--policy",
            policyFile.getAbsolutePath(),
            "--config",
            configFile.getAbsolutePath(),
            "--output",
            outputFile.getAbsolutePath(),
            "--output_version",
            "v1alpha1",
            "--output_format",
            "binarypb");

    assertThat(exitCode).isEqualTo(0);

    com.google.api.expr.v1alpha1.CheckedExpr v1alpha1Expr =
        com.google.api.expr.v1alpha1.CheckedExpr.parseFrom(
            Files.toByteArray(outputFile), ExtensionRegistryLite.getEmptyRegistry());
    assertThat(v1alpha1Expr.hasExpr()).isTrue();
  }

  @Test
  public void compile_withBaseConfig_success() throws Exception {
    File baseConfigFile = tempFolder.newFile("base_config.yaml");
    Files.asCharSink(baseConfigFile, UTF_8)
        .write("name: base-env\nvariables:\n  - name: base_var\n    type: string\n");

    File configFile = tempFolder.newFile("config.yaml");
    Files.asCharSink(configFile, UTF_8)
        .write("name: sub-env\nvariables:\n  - name: sub_var\n    type: string\n");

    File policyFile = tempFolder.newFile("policy.yaml");
    Files.asCharSink(policyFile, UTF_8)
        .write(
            "name: p\n"
                + "rule:\n"
                + "  match:\n"
                + "    - condition: base_var == \"hello\" && sub_var == \"world\"\n"
                + "      output: 'true'\n");

    File outputFile = tempFolder.newFile("output.binarypb");

    CommandLine cmd = new CommandLine(new CelPolicyCompilerTool());
    int exitCode =
        cmd.execute(
            "--policy",
            policyFile.getAbsolutePath(),
            "--base_config",
            baseConfigFile.getAbsolutePath(),
            "--config",
            configFile.getAbsolutePath(),
            "--output",
            outputFile.getAbsolutePath());

    assertThat(exitCode).isEqualTo(0);

    CheckedExpr checkedExpr =
        CheckedExpr.parseFrom(
            Files.toByteArray(outputFile), ExtensionRegistryLite.getEmptyRegistry());
    CelAbstractSyntaxTree ast = CelProtoAbstractSyntaxTree.fromCheckedExpr(checkedExpr).getAst();
    Object result =
        celRuntime
            .createProgram(ast)
            .eval(ImmutableMap.of("base_var", "hello", "sub_var", "world"));
    assertThat(result).isEqualTo(Optional.of(true));
  }

  @Test
  public void compile_withSimpleVariables_success() throws Exception {
    File configFile = tempFolder.newFile("config.yaml");
    Files.asCharSink(configFile, UTF_8).write("name: test-env\n");

    File policyFile = tempFolder.newFile("policy.yaml");
    Files.asCharSink(policyFile, UTF_8)
        .write(
            "name: p\n"
                + "rule:\n"
                + "  variables:\n"
                + "    - my_sum: 10 + 20\n"
                + "  match:\n"
                + "    - condition: variables.my_sum == 30\n"
                + "      output: 'true'\n");

    File outputFile = tempFolder.newFile("output.binarypb");

    CommandLine cmd = new CommandLine(new CelPolicyCompilerTool());
    int exitCode =
        cmd.execute(
            "--policy",
            policyFile.getAbsolutePath(),
            "--config",
            configFile.getAbsolutePath(),
            "--output",
            outputFile.getAbsolutePath(),
            "--simple_variables");

    assertThat(exitCode).isEqualTo(0);

    CheckedExpr checkedExpr =
        CheckedExpr.parseFrom(
            Files.toByteArray(outputFile), ExtensionRegistryLite.getEmptyRegistry());
    CelAbstractSyntaxTree ast = CelProtoAbstractSyntaxTree.fromCheckedExpr(checkedExpr).getAst();
    Object result = celRuntime.createProgram(ast).eval();
    assertThat(result).isEqualTo(Optional.of(true));
  }

  @Test
  public void compile_withOptimizeFieldSelection_rewritesSelectAndAddsExtension() throws Exception {
    String configRlocation =
        "google3/third_party/java/cel/testing/src/test/resources/environment/proto3_message_variables.yaml";
    String fdsRlocation =
        "google3/third_party/java/cel/policy/src/test/java/dev/cel/policy/tools/test_all_types_fds.pb";

    String configPath = resolveRunfile(configRlocation);
    String fdsPath = resolveRunfile(fdsRlocation);

    File policyFile = tempFolder.newFile("proto_policy.yaml");
    Files.asCharSink(policyFile, UTF_8)
        .write(
            "name: proto-policy\n"
                + "rule:\n"
                + "  match:\n"
                + "    - condition: proto3.single_int32 == 1\n"
                + "      output: '\"OK\"'\n");

    File outputFile = tempFolder.newFile("output_optimized.binarypb");

    CommandLine cmd = new CommandLine(new CelPolicyCompilerTool());
    int exitCode =
        cmd.execute(
            "--policy",
            policyFile.getAbsolutePath(),
            "--config",
            configPath,
            "--transitive_descriptor_set",
            fdsPath,
            "--output",
            outputFile.getAbsolutePath(),
            "--optimize_field_selection");

    assertThat(exitCode).isEqualTo(0);

    CheckedExpr checkedExpr =
        CheckedExpr.parseFrom(
            Files.toByteArray(outputFile), ExtensionRegistryLite.getEmptyRegistry());
    CelAbstractSyntaxTree ast = CelProtoAbstractSyntaxTree.fromCheckedExpr(checkedExpr).getAst();

    // Verify Extension tag "select_optimization" is attached to source info
    assertThat(ast.getSource().getExtensions())
        .contains(
            CelSource.Extension.create(
                "select_optimization",
                CelSource.Extension.Version.of(1L, 0L),
                CelSource.Extension.Component.COMPONENT_RUNTIME));

    // Verify AST was rewritten to call cel.@attribute
    String unparsedText = checkedExpr.toString();
    assertThat(unparsedText).contains("cel.@attribute");
  }

  @Test
  public void compile_presenceTest_withOptimizeFieldSelection_rewritesHasField() throws Exception {
    String configRlocation =
        "google3/third_party/java/cel/testing/src/test/resources/environment/proto3_message_variables.yaml";
    String fdsRlocation =
        "google3/third_party/java/cel/policy/src/test/java/dev/cel/policy/tools/test_all_types_fds.pb";

    String configPath = resolveRunfile(configRlocation);
    String fdsPath = resolveRunfile(fdsRlocation);

    File policyFile = tempFolder.newFile("presence_policy.yaml");
    Files.asCharSink(policyFile, UTF_8)
        .write(
            "name: presence-policy\n"
                + "rule:\n"
                + "  match:\n"
                + "    - condition: has(proto3.single_int32)\n"
                + "      output: '\"EXISTS\"'\n");

    File outputFile = tempFolder.newFile("output_presence.binarypb");

    CommandLine cmd = new CommandLine(new CelPolicyCompilerTool());
    int exitCode =
        cmd.execute(
            "--policy",
            policyFile.getAbsolutePath(),
            "--config",
            configPath,
            "--transitive_descriptor_set",
            fdsPath,
            "--output",
            outputFile.getAbsolutePath(),
            "--optimize_field_selection");

    assertThat(exitCode).isEqualTo(0);

    CheckedExpr checkedExpr =
        CheckedExpr.parseFrom(
            Files.toByteArray(outputFile), ExtensionRegistryLite.getEmptyRegistry());
    CelAbstractSyntaxTree ast = CelProtoAbstractSyntaxTree.fromCheckedExpr(checkedExpr).getAst();

    assertThat(ast.getSource().getExtensions())
        .contains(
            CelSource.Extension.create(
                "select_optimization",
                CelSource.Extension.Version.of(1L, 0L),
                CelSource.Extension.Component.COMPONENT_RUNTIME));

    assertThat(checkedExpr.toString()).contains("cel.@hasField");
  }

  @Test
  public void compile_macroTarget_verified() throws Exception {
    String macroArtifactRlocation =
        "google3/third_party/java/cel/policy/src/test/java/dev/cel/policy/tools/macro_compiled_policy.binarypb";
    File compiledFile = new File(resolveRunfile(macroArtifactRlocation));
    assertThat(compiledFile.exists()).isTrue();

    CheckedExpr checkedExpr =
        CheckedExpr.parseFrom(
            Files.toByteArray(compiledFile), ExtensionRegistryLite.getEmptyRegistry());
    CelAbstractSyntaxTree ast = CelProtoAbstractSyntaxTree.fromCheckedExpr(checkedExpr).getAst();

    assertThat(ast.getSource().getExtensions())
        .contains(
            CelSource.Extension.create(
                "select_optimization",
                CelSource.Extension.Version.of(1L, 0L),
                CelSource.Extension.Component.COMPONENT_RUNTIME));

    assertThat(checkedExpr.toString()).contains("cel.@attribute");
  }

  @Test
  public void compile_error_missingPolicy_returnsErrorCode() {
    CommandLine cmd = new CommandLine(new CelPolicyCompilerTool());
    int exitCode = cmd.execute("--config", "foo.yaml", "--output", "out.binarypb");
    assertThat(exitCode).isEqualTo(-1);
  }

  @Test
  public void compile_error_invalidPolicyYaml_returnsErrorCode() throws Exception {
    File configFile = tempFolder.newFile("config.yaml");
    Files.asCharSink(configFile, UTF_8).write("name: test-env\n");

    File policyFile = tempFolder.newFile("bad_policy.yaml");
    Files.asCharSink(policyFile, UTF_8).write("not a valid yaml: [unclosed list\n");

    File outputFile = tempFolder.newFile("output.binarypb");

    CommandLine cmd = new CommandLine(new CelPolicyCompilerTool());
    int exitCode =
        cmd.execute(
            "--policy",
            policyFile.getAbsolutePath(),
            "--config",
            configFile.getAbsolutePath(),
            "--output",
            outputFile.getAbsolutePath());

    assertThat(exitCode).isEqualTo(-1);
  }

  @Test
  public void compile_stdout_textpb_format_success() throws Exception {
    File configFile = tempFolder.newFile("config.yaml");
    Files.asCharSink(configFile, UTF_8)
        .write("name: test-env\nvariables:\n  - name: user\n    type: string\n");

    File policyFile = tempFolder.newFile("policy.yaml");
    Files.asCharSink(policyFile, UTF_8)
        .write(
            "name: user-policy\n"
                + "rule:\n"
                + "  match:\n"
                + "    - condition: user == \"alice\"\n"
                + "      output: 'true'\n");

    PrintStream originalOut = System.out;
    ByteArrayOutputStream outContent = new ByteArrayOutputStream();
    try {
      System.setOut(new PrintStream(outContent, true, UTF_8.name()));
      CommandLine cmd = new CommandLine(new CelPolicyCompilerTool());
      int exitCode =
          cmd.execute(
              "--policy",
              policyFile.getAbsolutePath(),
              "--config",
              configFile.getAbsolutePath(),
              "--output_format",
              "textpb");
      assertThat(exitCode).isEqualTo(0);
    } finally {
      System.setOut(originalOut);
    }

    String outputText = new String(outContent.toByteArray(), UTF_8);
    assertThat(outputText).contains("call_expr");
  }

  @Test
  public void compile_stdout_textproto_withDashOutput_success() throws Exception {
    File configFile = tempFolder.newFile("config.yaml");
    Files.asCharSink(configFile, UTF_8)
        .write("name: test-env\nvariables:\n  - name: user\n    type: string\n");

    File policyFile = tempFolder.newFile("policy.yaml");
    Files.asCharSink(policyFile, UTF_8)
        .write(
            "name: user-policy\n"
                + "rule:\n"
                + "  match:\n"
                + "    - condition: user == \"alice\"\n"
                + "      output: 'true'\n");

    PrintStream originalOut = System.out;
    ByteArrayOutputStream outContent = new ByteArrayOutputStream();
    try {
      System.setOut(new PrintStream(outContent, true, UTF_8.name()));
      CommandLine cmd = new CommandLine(new CelPolicyCompilerTool());
      int exitCode =
          cmd.execute(
              "--policy",
              policyFile.getAbsolutePath(),
              "--config",
              configFile.getAbsolutePath(),
              "--output",
              "-",
              "--output_format",
              "textproto");
      assertThat(exitCode).isEqualTo(0);
    } finally {
      System.setOut(originalOut);
    }

    String outputText = new String(outContent.toByteArray(), UTF_8);
    assertThat(outputText).contains("call_expr");
  }

  @Test
  public void compile_stdout_binarypb_withDashOutput_success() throws Exception {
    File configFile = tempFolder.newFile("config.yaml");
    Files.asCharSink(configFile, UTF_8)
        .write("name: test-env\nvariables:\n  - name: age\n    type: int\n");

    File policyFile = tempFolder.newFile("policy.yaml");
    Files.asCharSink(policyFile, UTF_8)
        .write(
            "name: age-policy\n"
                + "rule:\n"
                + "  match:\n"
                + "    - condition: age >= 18\n"
                + "      output: '\"adult\"'\n");

    PrintStream originalOut = System.out;
    ByteArrayOutputStream outContent = new ByteArrayOutputStream();
    try {
      System.setOut(new PrintStream(outContent, true, UTF_8.name()));
      CommandLine cmd = new CommandLine(new CelPolicyCompilerTool());
      int exitCode =
          cmd.execute(
              "--policy",
              policyFile.getAbsolutePath(),
              "--config",
              configFile.getAbsolutePath(),
              "--output",
              "-",
              "--output_format",
              "binarypb");
      assertThat(exitCode).isEqualTo(0);
    } finally {
      System.setOut(originalOut);
    }

    CheckedExpr checkedExpr =
        CheckedExpr.parseFrom(outContent.toByteArray(), ExtensionRegistryLite.getEmptyRegistry());
    assertThat(checkedExpr.hasExpr()).isTrue();
  }

  @Test
  public void compile_stdout_defaultOmittedOutput_binarypb_success() throws Exception {
    File configFile = tempFolder.newFile("config.yaml");
    Files.asCharSink(configFile, UTF_8)
        .write("name: test-env\nvariables:\n  - name: age\n    type: int\n");

    File policyFile = tempFolder.newFile("policy.yaml");
    Files.asCharSink(policyFile, UTF_8)
        .write(
            "name: age-policy\n"
                + "rule:\n"
                + "  match:\n"
                + "    - condition: age >= 18\n"
                + "      output: '\"adult\"'\n");

    PrintStream originalOut = System.out;
    ByteArrayOutputStream outContent = new ByteArrayOutputStream();
    try {
      System.setOut(new PrintStream(outContent, true, UTF_8.name()));
      CommandLine cmd = new CommandLine(new CelPolicyCompilerTool());
      int exitCode =
          cmd.execute(
              "--policy", policyFile.getAbsolutePath(), "--config", configFile.getAbsolutePath());
      assertThat(exitCode).isEqualTo(0);
    } finally {
      System.setOut(originalOut);
    }

    CheckedExpr checkedExpr =
        CheckedExpr.parseFrom(outContent.toByteArray(), ExtensionRegistryLite.getEmptyRegistry());
    assertThat(checkedExpr.hasExpr()).isTrue();
  }

  @Test
  public void compile_withPositionalPolicyPath_success() throws Exception {
    File configFile = tempFolder.newFile("config.yaml");
    Files.asCharSink(configFile, UTF_8)
        .write("name: test-env\nvariables:\n  - name: age\n    type: int\n");

    File policyFile = tempFolder.newFile("policy.yaml");
    Files.asCharSink(policyFile, UTF_8)
        .write(
            "name: age-policy\n"
                + "rule:\n"
                + "  match:\n"
                + "    - condition: age >= 18\n"
                + "      output: '\"adult\"'\n");

    File outputFile = tempFolder.newFile("output.binarypb");

    CommandLine cmd = new CommandLine(new CelPolicyCompilerTool());
    int exitCode =
        cmd.execute(
            policyFile.getAbsolutePath(),
            "--config",
            configFile.getAbsolutePath(),
            "--output",
            outputFile.getAbsolutePath());

    assertThat(exitCode).isEqualTo(0);

    CheckedExpr checkedExpr =
        CheckedExpr.parseFrom(
            Files.toByteArray(outputFile), ExtensionRegistryLite.getEmptyRegistry());
    assertThat(checkedExpr.hasExpr()).isTrue();
  }

  @Test
  public void compile_withNestedOutputDirectory_createsDirectories_success() throws Exception {
    File configFile = tempFolder.newFile("config.yaml");
    Files.asCharSink(configFile, UTF_8)
        .write("name: test-env\nvariables:\n  - name: age\n    type: int\n");

    File policyFile = tempFolder.newFile("policy.yaml");
    Files.asCharSink(policyFile, UTF_8)
        .write(
            "name: age-policy\n"
                + "rule:\n"
                + "  match:\n"
                + "    - condition: age >= 18\n"
                + "      output: '\"adult\"'\n");

    File nestedOutputFile = new File(tempFolder.getRoot(), "sub/nested/dir/output.binarypb");

    CommandLine cmd = new CommandLine(new CelPolicyCompilerTool());
    int exitCode =
        cmd.execute(
            "--policy",
            policyFile.getAbsolutePath(),
            "--config",
            configFile.getAbsolutePath(),
            "--output",
            nestedOutputFile.getAbsolutePath());

    assertThat(exitCode).isEqualTo(0);
    assertThat(nestedOutputFile.exists()).isTrue();
  }

  @Test
  public void compile_withYmlConfigExtension_success() throws Exception {
    File configFile = tempFolder.newFile("config.yml");
    Files.asCharSink(configFile, UTF_8)
        .write("name: test-env\nvariables:\n  - name: age\n    type: int\n");

    File policyFile = tempFolder.newFile("policy.yaml");
    Files.asCharSink(policyFile, UTF_8)
        .write(
            "name: age-policy\n"
                + "rule:\n"
                + "  match:\n"
                + "    - condition: age >= 18\n"
                + "      output: '\"adult\"'\n");

    File outputFile = tempFolder.newFile("output.binarypb");

    CommandLine cmd = new CommandLine(new CelPolicyCompilerTool());
    int exitCode =
        cmd.execute(
            "--policy",
            policyFile.getAbsolutePath(),
            "--config",
            configFile.getAbsolutePath(),
            "--output",
            outputFile.getAbsolutePath());

    assertThat(exitCode).isEqualTo(0);
  }

  @Test
  public void compile_error_unsupportedOutputVersion_returnsErrorCode() throws Exception {
    File configFile = tempFolder.newFile("config.yaml");
    Files.asCharSink(configFile, UTF_8).write("name: test-env\n");

    File policyFile = tempFolder.newFile("policy.yaml");
    Files.asCharSink(policyFile, UTF_8)
        .write("name: p\nrule:\n  match:\n    - condition: 'true'\n      output: 'true'\n");

    CommandLine cmd = new CommandLine(new CelPolicyCompilerTool());
    int exitCode =
        cmd.execute(
            "--policy",
            policyFile.getAbsolutePath(),
            "--config",
            configFile.getAbsolutePath(),
            "--output_version",
            "unsupported_version");

    assertThat(exitCode).isEqualTo(-1);
  }

  @Test
  public void compile_error_unsupportedOutputFormat_returnsErrorCode() throws Exception {
    File configFile = tempFolder.newFile("config.yaml");
    Files.asCharSink(configFile, UTF_8).write("name: test-env\n");

    File policyFile = tempFolder.newFile("policy.yaml");
    Files.asCharSink(policyFile, UTF_8)
        .write("name: p\nrule:\n  match:\n    - condition: 'true'\n      output: 'true'\n");

    CommandLine cmd = new CommandLine(new CelPolicyCompilerTool());
    int exitCode =
        cmd.execute(
            "--policy",
            policyFile.getAbsolutePath(),
            "--config",
            configFile.getAbsolutePath(),
            "--output_format",
            "json");

    assertThat(exitCode).isEqualTo(-1);
  }

  @Test
  public void compile_error_invalidConfigExtension_returnsErrorCode() throws Exception {
    File configFile = tempFolder.newFile("config.json");
    Files.asCharSink(configFile, UTF_8).write("{\"name\": \"test-env\"}");

    File policyFile = tempFolder.newFile("policy.yaml");
    Files.asCharSink(policyFile, UTF_8)
        .write("name: p\nrule:\n  match:\n    - condition: true\n      output: 'true'\n");

    CommandLine cmd = new CommandLine(new CelPolicyCompilerTool());
    int exitCode =
        cmd.execute(
            "--policy", policyFile.getAbsolutePath(), "--config", configFile.getAbsolutePath());

    assertThat(exitCode).isEqualTo(-1);
  }

  @Test
  public void compile_error_invalidBaseConfigExtension_returnsErrorCode() throws Exception {
    File baseConfigFile = tempFolder.newFile("base_config.json");
    Files.asCharSink(baseConfigFile, UTF_8).write("{\"name\": \"base-env\"}");

    File configFile = tempFolder.newFile("config.yaml");
    Files.asCharSink(configFile, UTF_8).write("name: test-env\n");

    File policyFile = tempFolder.newFile("policy.yaml");
    Files.asCharSink(policyFile, UTF_8)
        .write("name: p\nrule:\n  match:\n    - condition: true\n      output: 'true'\n");

    CommandLine cmd = new CommandLine(new CelPolicyCompilerTool());
    int exitCode =
        cmd.execute(
            "--policy",
            policyFile.getAbsolutePath(),
            "--base_config",
            baseConfigFile.getAbsolutePath(),
            "--config",
            configFile.getAbsolutePath());

    assertThat(exitCode).isEqualTo(-1);
  }

  @Test
  public void compile_error_policyCompilationFailure_returnsErrorCode() throws Exception {
    File configFile = tempFolder.newFile("config.yaml");
    Files.asCharSink(configFile, UTF_8).write("name: test-env\n");

    File policyFile = tempFolder.newFile("undeclared_policy.yaml");
    Files.asCharSink(policyFile, UTF_8)
        .write(
            "name: undeclared-policy\n"
                + "rule:\n"
                + "  match:\n"
                + "    - condition: undeclared_identifier == 42\n"
                + "      output: 'true'\n");

    CommandLine cmd = new CommandLine(new CelPolicyCompilerTool());
    int exitCode =
        cmd.execute(
            "--policy", policyFile.getAbsolutePath(), "--config", configFile.getAbsolutePath());

    assertThat(exitCode).isEqualTo(-1);
  }

  @Test
  public void compile_error_nonExistentPolicyFile_returnsErrorCode() throws Exception {
    File configFile = tempFolder.newFile("config.yaml");
    Files.asCharSink(configFile, UTF_8).write("name: test-env\n");

    CommandLine cmd = new CommandLine(new CelPolicyCompilerTool());
    int exitCode =
        cmd.execute(
            "--policy", "non_existent_policy.yaml", "--config", configFile.getAbsolutePath());

    assertThat(exitCode).isEqualTo(-1);
  }

  @Test
  public void compile_error_nonExistentConfigFile_returnsErrorCode() throws Exception {
    File policyFile = tempFolder.newFile("policy.yaml");
    Files.asCharSink(policyFile, UTF_8)
        .write("name: p\nrule:\n  match:\n    - condition: true\n      output: 'true'\n");

    CommandLine cmd = new CommandLine(new CelPolicyCompilerTool());
    int exitCode =
        cmd.execute(
            "--policy", policyFile.getAbsolutePath(), "--config", "non_existent_config.yaml");

    assertThat(exitCode).isEqualTo(-1);
  }

  @Test
  public void compile_error_nonExistentDescriptorSet_returnsErrorCode() throws Exception {
    File configFile = tempFolder.newFile("config.yaml");
    Files.asCharSink(configFile, UTF_8).write("name: test-env\n");

    File policyFile = tempFolder.newFile("policy.yaml");
    Files.asCharSink(policyFile, UTF_8)
        .write("name: p\nrule:\n  match:\n    - condition: true\n      output: 'true'\n");

    CommandLine cmd = new CommandLine(new CelPolicyCompilerTool());
    int exitCode =
        cmd.execute(
            "--policy",
            policyFile.getAbsolutePath(),
            "--config",
            configFile.getAbsolutePath(),
            "--transitive_descriptor_set",
            "non_existent_descriptors.pb");

    assertThat(exitCode).isEqualTo(-1);
  }
}
