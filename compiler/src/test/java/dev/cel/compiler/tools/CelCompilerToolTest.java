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

import static com.google.common.truth.Truth.assertThat;
import static dev.cel.testing.compiled.CompiledExprUtils.readCheckedExpr;

import dev.cel.expr.CheckedExpr;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.Files;
import com.google.protobuf.ExtensionRegistryLite;
import com.google.protobuf.StringValue;
import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.CelOptions;
import dev.cel.common.CelProtoAbstractSyntaxTree;
import dev.cel.common.CelSource;
import dev.cel.expr.conformance.proto3.TestAllTypes;
import dev.cel.extensions.CelExtensions;
import dev.cel.extensions.CelOptionalLibrary;
import dev.cel.runtime.CelFunctionBinding;
import dev.cel.runtime.CelRuntime;
import dev.cel.runtime.CelRuntimeFactory;
import java.io.File;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import picocli.CommandLine;

@RunWith(JUnit4.class)
public class CelCompilerToolTest {

  @Rule public TemporaryFolder tempFolder = new TemporaryFolder();

  private static final CelRuntime CEL_RUNTIME =
      CelRuntimeFactory.standardCelRuntimeBuilder()
          .addFunctionBindings(
              CelFunctionBinding.from("wrapper_string_isEmpty", String.class, String::isEmpty))
          .addLibraries(
              CelExtensions.encoders(CelOptions.DEFAULT),
              CelExtensions.math(),
              CelExtensions.lists(),
              CelExtensions.strings(),
              CelOptionalLibrary.INSTANCE)
          .addMessageTypes(TestAllTypes.getDescriptor())
          .build();

  @Test
  public void compiledCheckedExpr_string() throws Exception {
    CelAbstractSyntaxTree ast = readCheckedExpr("compiled_hello_world");

    String result = (String) CEL_RUNTIME.createProgram(ast).eval();
    assertThat(result).isEqualTo("hello world");
  }

  @Test
  // Evaluated comprehension returns an unparameterized List
  @SuppressWarnings("unchecked")
  public void compiledCheckedExpr_comprehension() throws Exception {
    CelAbstractSyntaxTree ast = readCheckedExpr("compiled_comprehension");

    List<Long> result = (List<Long>) CEL_RUNTIME.createProgram(ast).eval();
    assertThat(result).containsExactly(2L, 3L, 4L).inOrder();
  }

  @Test
  public void compiledCheckedExpr_protoMessage() throws Exception {
    CelAbstractSyntaxTree ast = readCheckedExpr("compiled_proto_message");

    TestAllTypes result = (TestAllTypes) CEL_RUNTIME.createProgram(ast).eval();
    assertThat(result).isEqualTo(TestAllTypes.newBuilder().setSingleInt32(1).build());
  }

  @Test
  public void compiledCheckedExpr_extensions() throws Exception {
    CelAbstractSyntaxTree ast = readCheckedExpr("compiled_extensions");

    assertThat(CEL_RUNTIME.createProgram(ast).eval()).isEqualTo(true);
  }

  @Test
  public void compiledCheckedExpr_extended_env() throws Exception {
    CelAbstractSyntaxTree ast = readCheckedExpr("compiled_extended_env");

    boolean result =
        (boolean)
            CEL_RUNTIME
                .createProgram(ast)
                .eval(
                    ImmutableMap.of(
                        "msg",
                        TestAllTypes.newBuilder()
                            .setSingleStringWrapper(StringValue.of("foo"))
                            .build()));

    assertThat(result).isTrue();
  }

  @Test
  public void compiledCheckedExpr_withSelectOptimization() throws Exception {
    CelAbstractSyntaxTree ast = readCheckedExpr("compiled_proto3_select_primitives_optimized");

    assertThat(ast.getSource().getExtensions())
        .contains(
            CelSource.Extension.create(
                "select_optimization",
                CelSource.Extension.Version.of(1L, 0L),
                CelSource.Extension.Component.COMPONENT_RUNTIME));
  }

  @Test
  public void compiledCheckedExpr_withConstantFolding() throws Exception {
    CelAbstractSyntaxTree ast = readCheckedExpr("compiled_constant_folding");

    assertThat(ast.getExpr().constantOrDefault().int64Value()).isEqualTo(6L);
  }

  @Test
  public void compiledCheckedExpr_withSubexpressionElimination() throws Exception {
    CelAbstractSyntaxTree ast = readCheckedExpr("compiled_subexpression_elimination");

    assertThat(ast.getExpr().call().function()).isEqualTo("cel.@block");
    assertThat(CEL_RUNTIME.createProgram(ast).eval()).isEqualTo(true);
  }

  @Test
  public void compile_tool_direct_default_success() throws Exception {
    File outputFile = tempFolder.newFile("direct_output.binarypb");

    CommandLine cmd = new CommandLine(new CelCompilerTool());
    int exitCode =
        cmd.execute(
            "--cel_expression", "\"hello world\"", "--output", outputFile.getAbsolutePath());

    assertThat(exitCode).isEqualTo(0);

    CheckedExpr checkedExpr =
        CheckedExpr.parseFrom(
            Files.toByteArray(outputFile), ExtensionRegistryLite.getEmptyRegistry());
    CelAbstractSyntaxTree ast = CelProtoAbstractSyntaxTree.fromCheckedExpr(checkedExpr).getAst();
    assertThat(CEL_RUNTIME.createProgram(ast).eval()).isEqualTo("hello world");
  }

  @Test
  public void compile_tool_direct_constantFolding_success() throws Exception {
    File outputFile = tempFolder.newFile("direct_cf.binarypb");

    CommandLine cmd = new CommandLine(new CelCompilerTool());
    int exitCode =
        cmd.execute(
            "--cel_expression",
            "1 + 2 + 3",
            "--constant_folding",
            "--output",
            outputFile.getAbsolutePath());

    assertThat(exitCode).isEqualTo(0);

    CheckedExpr checkedExpr =
        CheckedExpr.parseFrom(
            Files.toByteArray(outputFile), ExtensionRegistryLite.getEmptyRegistry());
    CelAbstractSyntaxTree ast = CelProtoAbstractSyntaxTree.fromCheckedExpr(checkedExpr).getAst();
    assertThat(ast.getExpr().constantOrDefault().int64Value()).isEqualTo(6L);
  }

  @Test
  public void compile_tool_direct_subexpressionElimination_success() throws Exception {
    File outputFile = tempFolder.newFile("direct_cse.binarypb");

    CommandLine cmd = new CommandLine(new CelCompilerTool());
    int exitCode =
        cmd.execute(
            "--cel_expression",
            "size('a') + size('a') == 2",
            "--subexpression_elimination",
            "--output",
            outputFile.getAbsolutePath());

    assertThat(exitCode).isEqualTo(0);

    CheckedExpr checkedExpr =
        CheckedExpr.parseFrom(
            Files.toByteArray(outputFile), ExtensionRegistryLite.getEmptyRegistry());
    CelAbstractSyntaxTree ast = CelProtoAbstractSyntaxTree.fromCheckedExpr(checkedExpr).getAst();
    assertThat(ast.getExpr().call().function()).isEqualTo("cel.@block");
  }

  @Test
  public void compile_tool_direct_optimizeFieldSelection_withoutDescriptors_success()
      throws Exception {
    File outputFile = tempFolder.newFile("direct_opt.binarypb");

    CommandLine cmd = new CommandLine(new CelCompilerTool());
    int exitCode =
        cmd.execute(
            "--cel_expression",
            "true",
            "--optimize_field_selection",
            "--output",
            outputFile.getAbsolutePath());

    assertThat(exitCode).isEqualTo(0);
  }

  @Test
  public void compile_tool_direct_error_invalidExpression_returnsErrorCode() throws Exception {
    File outputFile = tempFolder.newFile("direct_err.binarypb");

    CommandLine cmd = new CommandLine(new CelCompilerTool());
    int exitCode = cmd.execute("--cel_expression", "1 +", "--output", outputFile.getAbsolutePath());

    assertThat(exitCode).isEqualTo(-1);
  }

  @Test
  public void compile_tool_direct_error_invalidEnvironmentPath_returnsErrorCode() throws Exception {
    File outputFile = tempFolder.newFile("direct_err_env.binarypb");

    CommandLine cmd = new CommandLine(new CelCompilerTool());
    int exitCode =
        cmd.execute(
            "--cel_expression",
            "true",
            "--environment_path",
            "non_existent_env.yaml",
            "--output",
            outputFile.getAbsolutePath());

    assertThat(exitCode).isEqualTo(-1);
  }

  @Test
  public void compile_tool_direct_error_invalidDescriptorPath_returnsErrorCode() throws Exception {
    File outputFile = tempFolder.newFile("direct_err_desc.binarypb");

    CommandLine cmd = new CommandLine(new CelCompilerTool());
    int exitCode =
        cmd.execute(
            "--cel_expression",
            "true",
            "--transitive_descriptor_set",
            "non_existent_desc.pb",
            "--output",
            outputFile.getAbsolutePath());

    assertThat(exitCode).isEqualTo(-1);
  }
}
