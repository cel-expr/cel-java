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
import com.google.devtools.build.runfiles.AutoBazelRepository;
import com.google.devtools.build.runfiles.Runfiles;
import com.google.protobuf.ExtensionRegistry;
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
import java.io.IOException;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Optional;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import picocli.CommandLine;

@RunWith(JUnit4.class)
@AutoBazelRepository
public final class CelPolicyCompilerToolTest {

  @Rule public TemporaryFolder tempFolder = new TemporaryFolder();

  private Runfiles runfiles;
  private CelRuntime celRuntime;

  @Before
  public void setUp() throws Exception {
    runfiles =
        Runfiles.preload().withSourceRepository(AutoBazelRepository_CelPolicyCompilerToolTest.NAME);
    celRuntime =
        CelRuntimeFactory.plannerRuntimeBuilder()
            .addLibraries(CelOptionalLibrary.INSTANCE)
            .addMessageTypes(TestAllTypes.getDescriptor())
            .build();
  }

  private String resolveRunfile(String rlocationPath) throws IOException {
    String resolvedPath = runfiles.rlocation(rlocationPath);
    if (resolvedPath == null) {
      throw new IOException("Unmapped runfile path: " + rlocationPath);
    }
    File file = new File(resolvedPath);
    if (!file.exists()) {
      throw new IOException(
          String.format(
              "Runfile not found on disk at '%s' (unresolved path: '%s')",
              resolvedPath, rlocationPath));
    }
    return resolvedPath;
  }

  @Test
  public void compile_basicPolicy_binarypb_success() throws Exception {
    String configPath =
        createFile("config.yaml", "name: test-env\nvariables:\n  - name: age\n    type: int\n");
    String policyPath =
        createFile(
            "policy.yaml",
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
            policyPath,
            "--config",
            configPath,
            "--output",
            outputFile.getAbsolutePath(),
            "--output_format",
            "binarypb");

    assertThat(exitCode).isEqualTo(0);

    CheckedExpr checkedExpr =
        CheckedExpr.parseFrom(Files.toByteArray(outputFile), ExtensionRegistry.getEmptyRegistry());
    CelAbstractSyntaxTree ast = CelProtoAbstractSyntaxTree.fromCheckedExpr(checkedExpr).getAst();

    Object result = celRuntime.createProgram(ast).eval(ImmutableMap.of("age", 25L));
    assertThat(result).isEqualTo(Optional.of("adult"));
  }

  @Test
  public void compile_textpb_format_success() throws Exception {
    String configPath =
        createFile("config.yaml", "name: test-env\nvariables:\n  - name: user\n    type: string\n");
    String policyPath =
        createFile(
            "policy.yaml",
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
            policyPath,
            "--config",
            configPath,
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
    String configPath =
        createFile("config.yaml", "name: test-env\nvariables:\n  - name: x\n    type: int\n");
    String policyPath =
        createFile(
            "policy.yaml",
            "name: p\nrule:\n  match:\n    - condition: x > 0\n      output: 'true'\n");

    File outputFile = tempFolder.newFile("output.v1alpha1.binarypb");

    CommandLine cmd = new CommandLine(new CelPolicyCompilerTool());
    int exitCode =
        cmd.execute(
            "--policy",
            policyPath,
            "--config",
            configPath,
            "--output",
            outputFile.getAbsolutePath(),
            "--output_version",
            "v1alpha1",
            "--output_format",
            "binarypb");

    assertThat(exitCode).isEqualTo(0);

    com.google.api.expr.v1alpha1.CheckedExpr v1alpha1Expr =
        com.google.api.expr.v1alpha1.CheckedExpr.parseFrom(
            Files.toByteArray(outputFile), ExtensionRegistry.getEmptyRegistry());
    assertThat(v1alpha1Expr.hasExpr()).isTrue();
  }

  @Test
  public void compile_withBaseConfig_success() throws Exception {
    String baseConfigPath =
        createFile(
            "base_config.yaml",
            "name: base-env\nvariables:\n  - name: base_var\n    type: string\n");
    String configPath =
        createFile(
            "config.yaml", "name: sub-env\nvariables:\n  - name: sub_var\n    type: string\n");
    String policyPath =
        createFile(
            "policy.yaml",
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
            policyPath,
            "--base_config",
            baseConfigPath,
            "--config",
            configPath,
            "--output",
            outputFile.getAbsolutePath());

    assertThat(exitCode).isEqualTo(0);

    CheckedExpr checkedExpr =
        CheckedExpr.parseFrom(Files.toByteArray(outputFile), ExtensionRegistry.getEmptyRegistry());
    CelAbstractSyntaxTree ast = CelProtoAbstractSyntaxTree.fromCheckedExpr(checkedExpr).getAst();
    Object result =
        celRuntime
            .createProgram(ast)
            .eval(ImmutableMap.of("base_var", "hello", "sub_var", "world"));
    assertThat(result).isEqualTo(Optional.of(true));
  }

  @Test
  public void compile_withVariables_success() throws Exception {
    String configPath = createFile("config.yaml", "name: test-env\n");
    String policyPath =
        createFile(
            "policy.yaml",
            "name: p\n"
                + "rule:\n"
                + "  variables:\n"
                + "    - name: my_sum\n"
                + "      expression: 10 + 20\n"
                + "  match:\n"
                + "    - condition: variables.my_sum == 30\n"
                + "      output: 'true'\n");
    File outputFile = tempFolder.newFile("output.binarypb");
    CommandLine cmd = new CommandLine(new CelPolicyCompilerTool());

    int exitCode =
        cmd.execute(
            "--policy",
            policyPath,
            "--config",
            configPath,
            "--output",
            outputFile.getAbsolutePath());

    assertThat(exitCode).isEqualTo(0);
    CheckedExpr checkedExpr =
        CheckedExpr.parseFrom(Files.toByteArray(outputFile), ExtensionRegistry.getEmptyRegistry());
    CelAbstractSyntaxTree ast = CelProtoAbstractSyntaxTree.fromCheckedExpr(checkedExpr).getAst();
    assertThat(celRuntime.createProgram(ast).eval()).isEqualTo(Optional.of(true));
  }

  @Test
  public void compile_withSimpleVariables_success() throws Exception {
    String configPath = createFile("config.yaml", "name: test-env\n");
    String policyPath =
        createFile(
            "policy.yaml",
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
            policyPath,
            "--config",
            configPath,
            "--simple_variables",
            "--output",
            outputFile.getAbsolutePath());

    assertThat(exitCode).isEqualTo(0);
    CheckedExpr checkedExpr =
        CheckedExpr.parseFrom(Files.toByteArray(outputFile), ExtensionRegistry.getEmptyRegistry());
    CelAbstractSyntaxTree ast = CelProtoAbstractSyntaxTree.fromCheckedExpr(checkedExpr).getAst();
    assertThat(celRuntime.createProgram(ast).eval()).isEqualTo(Optional.of(true));
  }

  @Test
  public void compile_withIterationLimitReached_returnsError() throws Exception {
    String configPath = createFile("config.yaml", "name: test-env\n");
    String policyPath =
        createFile(
            "policy.yaml",
            "name: p\n"
                + "rule:\n"
                + "  variables:\n"
                + "    - a: 1 + 2\n"
                + "    - b: variables.a + 3\n"
                + "  match:\n"
                + "    - condition: variables.b == 6\n"
                + "      output: 'true'\n");

    String stdErr =
        executeExpectingError(
            "--policy",
            policyPath,
            "--config",
            configPath,
            "--simple_variables",
            "--iteration_limit",
            "1");

    assertThat(stdErr).contains("Reason: Unexpected error while composing rules.");
  }

  @Test
  public void compile_withOptimizeFieldSelection_rewritesSelectAndEvaluates() throws Exception {
    String configRlocation =
        "cel_java/testing/src/test/resources/environment/proto3_message_variables.yaml";
    String fdsRlocation =
        "cel_java/policy/src/test/java/dev/cel/policy/tools/test_all_types_fds.pb";

    String configPath = resolveRunfile(configRlocation);
    String fdsPath = resolveRunfile(fdsRlocation);

    String policyPath =
        createFile(
            "proto_policy.yaml",
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
            policyPath,
            "--config",
            configPath,
            "--transitive_descriptor_set",
            fdsPath,
            "--output",
            outputFile.getAbsolutePath(),
            "--optimize_field_selection");

    assertThat(exitCode).isEqualTo(0);

    CheckedExpr checkedExpr =
        CheckedExpr.parseFrom(Files.toByteArray(outputFile), ExtensionRegistry.getEmptyRegistry());
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

    // Verify end-to-end evaluation with the planner runtime
    Object matched =
        celRuntime
            .createProgram(ast)
            .eval(ImmutableMap.of("proto3", TestAllTypes.newBuilder().setSingleInt32(1).build()));
    assertThat(matched).isEqualTo(Optional.of("OK"));

    Object unmatched =
        celRuntime
            .createProgram(ast)
            .eval(ImmutableMap.of("proto3", TestAllTypes.newBuilder().setSingleInt32(2).build()));
    assertThat(unmatched).isEqualTo(Optional.empty());
  }

  @Test
  public void compile_presenceTest_withOptimizeFieldSelection_rewritesHasFieldAndEvaluates()
      throws Exception {
    String configRlocation =
        "cel_java/testing/src/test/resources/environment/proto3_message_variables.yaml";
    String fdsRlocation =
        "cel_java/policy/src/test/java/dev/cel/policy/tools/test_all_types_fds.pb";

    String configPath = resolveRunfile(configRlocation);
    String fdsPath = resolveRunfile(fdsRlocation);

    String policyPath =
        createFile(
            "presence_policy.yaml",
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
            policyPath,
            "--config",
            configPath,
            "--transitive_descriptor_set",
            fdsPath,
            "--output",
            outputFile.getAbsolutePath(),
            "--optimize_field_selection");

    assertThat(exitCode).isEqualTo(0);

    CheckedExpr checkedExpr =
        CheckedExpr.parseFrom(Files.toByteArray(outputFile), ExtensionRegistry.getEmptyRegistry());
    CelAbstractSyntaxTree ast = CelProtoAbstractSyntaxTree.fromCheckedExpr(checkedExpr).getAst();

    assertThat(ast.getSource().getExtensions())
        .contains(
            CelSource.Extension.create(
                "select_optimization",
                CelSource.Extension.Version.of(1L, 0L),
                CelSource.Extension.Component.COMPONENT_RUNTIME));

    assertThat(checkedExpr.toString()).contains("cel.@hasField");

    Object present =
        celRuntime
            .createProgram(ast)
            .eval(ImmutableMap.of("proto3", TestAllTypes.newBuilder().setSingleInt32(42).build()));
    assertThat(present).isEqualTo(Optional.of("EXISTS"));

    Object absent =
        celRuntime
            .createProgram(ast)
            .eval(ImmutableMap.of("proto3", TestAllTypes.getDefaultInstance()));
    assertThat(absent).isEqualTo(Optional.empty());
  }

  @Test
  public void compile_policyWithCelBind_success() throws Exception {
    String configRlocation =
        "cel_java/testing/src/test/resources/environment/proto3_message_variables.yaml";
    String fdsRlocation =
        "cel_java/policy/src/test/java/dev/cel/policy/tools/test_all_types_fds.pb";

    String configPath = resolveRunfile(configRlocation);
    String fdsPath = resolveRunfile(fdsRlocation);

    String policyPath =
        createFile(
            "bind_policy.yaml",
            "name: bind-policy\n"
                + "rule:\n"
                + "  match:\n"
                + "    - condition: >\n"
                + "        cel.bind(\n"
                + "          val,\n"
                + "          proto3.single_int32,\n"
                + "          val == 1 || val == 2\n"
                + "        )\n"
                + "      output: '\"MATCH\"'\n");

    File outputFile = tempFolder.newFile("output_bind.binarypb");

    CommandLine cmd = new CommandLine(new CelPolicyCompilerTool());
    int exitCode =
        cmd.execute(
            "--policy",
            policyPath,
            "--config",
            configPath,
            "--transitive_descriptor_set",
            fdsPath,
            "--output",
            outputFile.getAbsolutePath(),
            "--optimize_field_selection");

    assertThat(exitCode).isEqualTo(0);

    CheckedExpr checkedExpr =
        CheckedExpr.parseFrom(Files.toByteArray(outputFile), ExtensionRegistry.getEmptyRegistry());
    CelAbstractSyntaxTree ast = CelProtoAbstractSyntaxTree.fromCheckedExpr(checkedExpr).getAst();

    assertThat(ast.getSource().getExtensions())
        .contains(
            CelSource.Extension.create(
                "select_optimization",
                CelSource.Extension.Version.of(1L, 0L),
                CelSource.Extension.Component.COMPONENT_RUNTIME));

    Object result =
        celRuntime
            .createProgram(ast)
            .eval(ImmutableMap.of("proto3", TestAllTypes.newBuilder().setSingleInt32(2).build()));
    assertThat(result).isEqualTo(Optional.of("MATCH"));
  }

  @Test
  public void compile_macroTarget_verifiedAndEvaluated() throws Exception {
    String macroArtifactRlocation =
        "cel_java/policy/src/test/java/dev/cel/policy/tools/compiled_test_policy.binarypb";
    File compiledFile = new File(resolveRunfile(macroArtifactRlocation));
    assertThat(compiledFile.exists()).isTrue();

    CheckedExpr checkedExpr =
        CheckedExpr.parseFrom(
            Files.toByteArray(compiledFile), ExtensionRegistry.getEmptyRegistry());
    CelAbstractSyntaxTree ast = CelProtoAbstractSyntaxTree.fromCheckedExpr(checkedExpr).getAst();

    assertThat(ast.getSource().getExtensions())
        .contains(
            CelSource.Extension.create(
                "select_optimization",
                CelSource.Extension.Version.of(1L, 0L),
                CelSource.Extension.Component.COMPONENT_RUNTIME));

    assertThat(checkedExpr.toString()).contains("cel.@attribute");
    assertThat(checkedExpr.toString()).contains("cel.@hasField");

    TestAllTypes matchingProto =
        TestAllTypes.newBuilder()
            .setSingleInt32(100)
            .setSingleNestedMessage(TestAllTypes.NestedMessage.newBuilder().setBb(1))
            .build();
    assertThat(celRuntime.createProgram(ast).eval(ImmutableMap.of("proto3", matchingProto)))
        .isEqualTo("ALLOW");

    TestAllTypes nonMatchingProto = TestAllTypes.newBuilder().setSingleInt32(100).build();
    assertThat(celRuntime.createProgram(ast).eval(ImmutableMap.of("proto3", nonMatchingProto)))
        .isEqualTo("DENY");
  }

  @Test
  public void compile_error_missingPolicy() throws Exception {
    String stdErr = executeExpectingError("--config", "foo.yaml", "--output", "out.binarypb");

    assertThat(stdErr)
        .contains(
            "Error: Policy file path must be specified via --policy or as a positional argument.");
  }

  @Test
  public void compile_error_invalidPolicyYaml() throws Exception {
    String configPath = createFile("config.yaml", "name: test-env\n");
    String policyPath = createFile("bad_policy.yaml", "not a valid yaml: [unclosed list\n");
    File outputFile = tempFolder.newFile("output.binarypb");

    String stdErr =
        executeExpectingError(
            "--policy",
            policyPath,
            "--config",
            configPath,
            "--output",
            outputFile.getAbsolutePath());

    assertThat(stdErr).contains("Failed to parse CEL policy: [" + policyPath);
  }

  @Test
  public void compile_stdout_textpb_format_success() throws Exception {
    String configPath =
        createFile("config.yaml", "name: test-env\nvariables:\n  - name: user\n    type: string\n");
    String policyPath =
        createFile(
            "policy.yaml",
            "name: user-policy\n"
                + "rule:\n"
                + "  match:\n"
                + "    - condition: user == \"alice\"\n"
                + "      output: 'true'\n");

    StringWriter out = new StringWriter();
    CommandLine cmd = new CommandLine(new CelPolicyCompilerTool());
    cmd.setOut(new PrintWriter(out));
    int exitCode =
        cmd.execute("--policy", policyPath, "--config", configPath, "--output_format", "textpb");
    assertThat(exitCode).isEqualTo(0);
    assertThat(out.toString()).contains("call_expr");
  }

  @Test
  public void compile_stdout_textproto_withDashOutput_success() throws Exception {
    String configPath =
        createFile("config.yaml", "name: test-env\nvariables:\n  - name: user\n    type: string\n");
    String policyPath =
        createFile(
            "policy.yaml",
            "name: user-policy\n"
                + "rule:\n"
                + "  match:\n"
                + "    - condition: user == \"alice\"\n"
                + "      output: 'true'\n");

    StringWriter out = new StringWriter();
    CommandLine cmd = new CommandLine(new CelPolicyCompilerTool());
    cmd.setOut(new PrintWriter(out));
    int exitCode =
        cmd.execute(
            "--policy",
            policyPath,
            "--config",
            configPath,
            "--output",
            "-",
            "--output_format",
            "textproto");
    assertThat(exitCode).isEqualTo(0);
    assertThat(out.toString()).contains("call_expr");
  }

  @Test
  public void compile_stdout_binarypb_withDashOutput_success() throws Exception {
    String configPath =
        createFile("config.yaml", "name: test-env\nvariables:\n  - name: age\n    type: int\n");
    String policyPath =
        createFile(
            "policy.yaml",
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
              policyPath,
              "--config",
              configPath,
              "--output",
              "-",
              "--output_format",
              "binarypb");
      assertThat(exitCode).isEqualTo(0);
    } finally {
      System.setOut(originalOut);
    }

    CheckedExpr checkedExpr =
        CheckedExpr.parseFrom(outContent.toByteArray(), ExtensionRegistry.getEmptyRegistry());
    assertThat(checkedExpr.hasExpr()).isTrue();
  }

  @Test
  public void compile_stdout_defaultOmittedOutput_binarypb_success() throws Exception {
    String configPath =
        createFile("config.yaml", "name: test-env\nvariables:\n  - name: age\n    type: int\n");
    String policyPath =
        createFile(
            "policy.yaml",
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
      int exitCode = cmd.execute("--policy", policyPath, "--config", configPath);
      assertThat(exitCode).isEqualTo(0);
    } finally {
      System.setOut(originalOut);
    }

    CheckedExpr checkedExpr =
        CheckedExpr.parseFrom(outContent.toByteArray(), ExtensionRegistry.getEmptyRegistry());
    assertThat(checkedExpr.hasExpr()).isTrue();
  }

  @Test
  public void compile_withPositionalPolicyPath_success() throws Exception {
    String configPath =
        createFile("config.yaml", "name: test-env\nvariables:\n  - name: age\n    type: int\n");
    String policyPath =
        createFile(
            "policy.yaml",
            "name: age-policy\n"
                + "rule:\n"
                + "  match:\n"
                + "    - condition: age >= 18\n"
                + "      output: '\"adult\"'\n");

    File outputFile = tempFolder.newFile("output.binarypb");

    CommandLine cmd = new CommandLine(new CelPolicyCompilerTool());
    int exitCode =
        cmd.execute(policyPath, "--config", configPath, "--output", outputFile.getAbsolutePath());

    assertThat(exitCode).isEqualTo(0);

    CheckedExpr checkedExpr =
        CheckedExpr.parseFrom(Files.toByteArray(outputFile), ExtensionRegistry.getEmptyRegistry());
    assertThat(checkedExpr.hasExpr()).isTrue();
  }

  @Test
  public void compile_withNestedOutputDirectory_createsDirectories_success() throws Exception {
    String configPath =
        createFile("config.yaml", "name: test-env\nvariables:\n  - name: age\n    type: int\n");
    String policyPath =
        createFile(
            "policy.yaml",
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
            policyPath,
            "--config",
            configPath,
            "--output",
            nestedOutputFile.getAbsolutePath());

    assertThat(exitCode).isEqualTo(0);
    assertThat(nestedOutputFile.exists()).isTrue();
  }

  @Test
  public void compile_withYmlConfigExtension_success() throws Exception {
    String configPath =
        createFile("config.yml", "name: test-env\nvariables:\n  - name: age\n    type: int\n");
    String policyPath =
        createFile(
            "policy.yaml",
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
            policyPath,
            "--config",
            configPath,
            "--output",
            outputFile.getAbsolutePath());

    assertThat(exitCode).isEqualTo(0);
  }

  @Test
  public void compile_error_unsupportedOutputVersion() throws Exception {
    String configPath = createFile("config.yaml", "name: test-env\n");
    String policyPath =
        createFile(
            "policy.yaml",
            "name: p\nrule:\n  match:\n    - condition: 'true'\n      output: 'true'\n");

    String stdErr =
        executeExpectingError(
            "--policy",
            policyPath,
            "--config",
            configPath,
            "--output_version",
            "unsupported_version");

    assertThat(stdErr)
        .contains(
            "Unsupported output version: unsupported_version. Supported versions: canonical,"
                + " v1alpha1");
  }

  @Test
  public void compile_error_unsupportedOutputFormat() throws Exception {
    String configPath = createFile("config.yaml", "name: test-env\n");
    String policyPath =
        createFile(
            "policy.yaml",
            "name: p\nrule:\n  match:\n    - condition: 'true'\n      output: 'true'\n");

    String stdErr =
        executeExpectingError(
            "--policy", policyPath, "--config", configPath, "--output_format", "json");

    assertThat(stdErr)
        .contains(
            "Unsupported output format: json. Supported formats: binarypb, textpb, textproto");
  }

  @Test
  public void compile_error_invalidConfigExtension() throws Exception {
    String configPath = createFile("config.json", "{\"name\": \"test-env\"}");
    String policyPath =
        createFile(
            "policy.yaml",
            "name: p\nrule:\n  match:\n    - condition: true\n      output: 'true'\n");

    String stdErr = executeExpectingError("--policy", policyPath, "--config", configPath);

    assertThat(stdErr)
        .contains(
            "Failed to create a CEL compilation environment. Reason: Only YAML format is"
                + " supported for CEL environment.");
  }

  @Test
  public void compile_error_invalidBaseConfigExtension() throws Exception {
    String baseConfigPath = createFile("base_config.json", "{\"name\": \"base-env\"}");
    String configPath = createFile("config.yaml", "name: test-env\n");
    String policyPath =
        createFile(
            "policy.yaml",
            "name: p\nrule:\n  match:\n    - condition: true\n      output: 'true'\n");

    String stdErr =
        executeExpectingError(
            "--policy", policyPath, "--base_config", baseConfigPath, "--config", configPath);

    assertThat(stdErr)
        .contains(
            "Failed to create a CEL compilation environment. Reason: Only YAML format is"
                + " supported for base CEL environment.");
  }

  @Test
  public void compile_error_policyCompilationFailure() throws Exception {
    String configPath = createFile("config.yaml", "name: test-env\n");
    String policyPath =
        createFile(
            "undeclared_policy.yaml",
            "name: undeclared-policy\n"
                + "rule:\n"
                + "  match:\n"
                + "    - condition: undeclared_identifier == 42\n"
                + "      output: 'true'\n");

    String stdErr = executeExpectingError("--policy", policyPath, "--config", configPath);

    assertThat(stdErr).contains("Failed to compile CEL policy: [" + policyPath);
    assertThat(stdErr).contains("undeclared reference to 'undeclared_identifier'");
  }

  @Test
  public void compile_error_nonExistentPolicyFile() throws Exception {
    String configPath = createFile("config.yaml", "name: test-env\n");

    String stdErr =
        executeExpectingError("--policy", "non_existent_policy.yaml", "--config", configPath);

    assertThat(stdErr).contains("Failed to parse CEL policy: [non_existent_policy.yaml]");
  }

  @Test
  public void compile_error_nonExistentConfigFile() throws Exception {
    String policyPath =
        createFile(
            "policy.yaml",
            "name: p\nrule:\n  match:\n    - condition: true\n      output: 'true'\n");

    String stdErr =
        executeExpectingError("--policy", policyPath, "--config", "non_existent_config.yaml");

    assertThat(stdErr).contains("Failed to create a CEL compilation environment.");
    assertThat(stdErr).contains("non_existent_config.yaml");
  }

  @Test
  public void compile_error_nonExistentDescriptorSet() throws Exception {
    String configPath = createFile("config.yaml", "name: test-env\n");
    String policyPath =
        createFile(
            "policy.yaml",
            "name: p\nrule:\n  match:\n    - condition: true\n      output: 'true'\n");

    String stdErr =
        executeExpectingError(
            "--policy",
            policyPath,
            "--config",
            configPath,
            "--transitive_descriptor_set",
            "non_existent_descriptors.pb");

    assertThat(stdErr)
        .contains("Failed to load FileDescriptorSet from path: non_existent_descriptors.pb");
  }

  private String createFile(String fileName, String content) throws IOException {
    File file = tempFolder.newFile(fileName);
    Files.asCharSink(file, UTF_8).write(content);
    return file.getAbsolutePath();
  }

  private static String executeExpectingError(String... args) {
    StringWriter out = new StringWriter();
    PrintWriter pw = new PrintWriter(out);
    CommandLine cmd = new CommandLine(new CelPolicyCompilerTool());
    cmd.setOut(pw);
    cmd.setErr(pw);
    int exitCode = cmd.execute(args);
    assertThat(exitCode).isEqualTo(-1);
    return out.toString();
  }
}
