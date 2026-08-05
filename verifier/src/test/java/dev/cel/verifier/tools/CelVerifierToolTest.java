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

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import dev.cel.common.types.CelType;
import dev.cel.common.types.ListType;
import dev.cel.common.types.MapType;
import dev.cel.common.types.OptionalType;
import dev.cel.common.types.SimpleType;
import dev.cel.verifier.CelVerificationResult;
import dev.cel.verifier.CelVerificationResult.VerificationStatus;
import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Arrays;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import picocli.CommandLine;

@RunWith(JUnit4.class)
public final class CelVerifierToolTest {

  @Rule public TemporaryFolder tempFolder = new TemporaryFolder();

  @Before
  public void setUp() {
    System.setProperty("z3.skipLibraryLoad", "true");
  }

  private String executeToolWithOutput(String... args) {
    StringWriter out = new StringWriter();
    PrintWriter pw = new PrintWriter(out);
    CommandLine cmd = new CommandLine(new CelVerifierTool());
    cmd.setOut(pw);
    cmd.setErr(pw);
    cmd.execute(args);
    return out.toString();
  }

  @Test
  public void celVerifierTool_checkSat_jsonOutputFormat() {
    String output =
        executeToolWithOutput(
            "check-sat", "--expr", "x > 0", "--var", "x:int", "--output_format", "json");

    assertThat(output).startsWith("{\n");
    assertThat(output).contains("\"status\": \"VERIFIED\"");
    assertThat(output).contains("satisfiable");
    assertThat(output.trim()).endsWith("}");
  }

  @Test
  public void celVerifierTool_checkSat_textOutputFormat() {
    String output =
        executeToolWithOutput("check-sat", "--expr", "x > 0", "--var", "x:int", "-fmt", "text");

    assertThat(output).contains("[VERIFIED]");
    assertThat(output).contains("satisfiable");
  }

  @Test
  public void celVerifierTool_checkSat_withDynVariable() {
    String output =
        executeToolWithOutput(
            "check-sat", "--expr", "x == 'hello'", "--var", "x:dyn", "-fmt", "json");

    assertThat(output).contains("\"status\": \"VERIFIED\"");
  }

  @Test
  public void celVerifierTool_checkSat_withUnknownOption() {
    String output =
        executeToolWithOutput(
            "check-sat",
            "--expr",
            "request.headers != null",
            "--var",
            "request:map<string, dyn>",
            "-u",
            "request.headers",
            "-fmt",
            "json");

    assertThat(output).contains("\"status\": \"VERIFIED\"");
  }

  @Test
  public void celVerifierTool_checkSat_withTimeoutAndUnrollLimit() {
    String output =
        executeToolWithOutput(
            "check-sat",
            "--expr",
            "[1, 2, 3].all(x, x > 0)",
            "--timeout",
            "5",
            "--unroll-limit",
            "5",
            "-fmt",
            "json");

    assertThat(output).contains("\"status\": \"VERIFIED\"");
  }

  @Test
  public void celVerifierTool_checkSat_withTimestampDurationAndOptional() {
    String output =
        executeToolWithOutput(
            "check-sat",
            "--expr",
            "t + d > timestamp(1000) && opt.hasValue()",
            "--var",
            "t:timestamp",
            "--var",
            "d:duration",
            "--var",
            "opt:optional<int>",
            "-fmt",
            "json");

    assertThat(output).contains("\"status\": \"VERIFIED\"");
  }

  @Test
  public void celVerifierTool_verifyPolicy_fileNotFound() {
    String output = executeToolWithOutput("verify-policy", "--file", "non_existent_policy.yaml");

    assertThat(output).contains("File not found: non_existent_policy.yaml");
  }

  @Test
  public void parseVariables_success() {
    ImmutableMap<String, CelType> vars =
        VerificationOptions.parseVariables(
            Arrays.asList(
                "x:int",
                "role:string",
                "is_admin:bool",
                "tags:list<string>",
                "scores:map<string, int>",
                "created_at:timestamp",
                "timeout:duration",
                "opt_user:optional<string>"));

    assertThat(vars).containsEntry("x", SimpleType.INT);
    assertThat(vars).containsEntry("role", SimpleType.STRING);
    assertThat(vars).containsEntry("is_admin", SimpleType.BOOL);
    assertThat(vars).containsEntry("tags", ListType.create(SimpleType.STRING));
    assertThat(vars).containsEntry("scores", MapType.create(SimpleType.STRING, SimpleType.INT));
    assertThat(vars).containsEntry("created_at", SimpleType.TIMESTAMP);
    assertThat(vars).containsEntry("timeout", SimpleType.DURATION);
    assertThat(vars).containsEntry("opt_user", OptionalType.create(SimpleType.STRING));
  }

  @Test
  public void parseVariables_allTypesIncludingDyn() {
    ImmutableMap<String, CelType> vars =
        VerificationOptions.parseVariables(
            Arrays.asList(
                "u:uint",
                "d:double",
                "fl:float",
                "b:bytes",
                "dyn_val:dyn",
                "flag:boolean",
                "t:google.protobuf.timestamp",
                "dur:google.protobuf.duration",
                "opt:optional<int>",
                "nested_list:list<dyn>",
                "nested_map:map<string, dyn>"));

    assertThat(vars).containsEntry("u", SimpleType.UINT);
    assertThat(vars).containsEntry("d", SimpleType.DOUBLE);
    assertThat(vars).containsEntry("fl", SimpleType.DOUBLE);
    assertThat(vars).containsEntry("b", SimpleType.BYTES);
    assertThat(vars).containsEntry("dyn_val", SimpleType.DYN);
    assertThat(vars).containsEntry("flag", SimpleType.BOOL);
    assertThat(vars).containsEntry("t", SimpleType.TIMESTAMP);
    assertThat(vars).containsEntry("dur", SimpleType.DURATION);
    assertThat(vars).containsEntry("opt", OptionalType.create(SimpleType.INT));
    assertThat(vars).containsEntry("nested_list", ListType.create(SimpleType.DYN));
    assertThat(vars).containsEntry("nested_map", MapType.create(SimpleType.STRING, SimpleType.DYN));
  }

  @Test
  public void parseVariables_invalidFormat_throws() {
    assertThrows(
        IllegalArgumentException.class,
        () -> VerificationOptions.parseVariables(Arrays.asList("x_no_colon")));
  }

  @Test
  public void parseVariables_unsupportedType_throws() {
    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> VerificationOptions.parseVariables(Arrays.asList("x:foo_bar")));

    assertThat(ex)
        .hasMessageThat()
        .contains(
            "Supported types: int, uint, string, bool, double, bytes, dyn, timestamp,"
                + " duration, list<T>, map<K, V>, optional<T>.");
  }

  @Test
  public void parseVariables_invalidMapFormat_throws() {
    assertThrows(
        IllegalArgumentException.class,
        () -> VerificationOptions.parseVariables(Arrays.asList("x:map<string>")));
  }

  @Test
  public void parseVariables_emptyOrNull_returnsEmptyMap() {
    assertThat(VerificationOptions.parseVariables(null)).isEmpty();
    assertThat(VerificationOptions.parseVariables(ImmutableList.of())).isEmpty();
  }

  @Test
  public void parseVariables_nestedTypes() {
    ImmutableMap<String, CelType> vars =
        VerificationOptions.parseVariables(
            Arrays.asList(
                "nested_map:map<string, map<string, int>>",
                "nested_list_map:map<string, list<int>>"));

    assertThat(vars)
        .containsEntry(
            "nested_map",
            MapType.create(SimpleType.STRING, MapType.create(SimpleType.STRING, SimpleType.INT)));
    assertThat(vars)
        .containsEntry(
            "nested_list_map", MapType.create(SimpleType.STRING, ListType.create(SimpleType.INT)));
  }

  @Test
  public void parseVariables_emptyString_throws() {
    assertThrows(
        IllegalArgumentException.class,
        () -> VerificationOptions.parseVariables(Arrays.asList("")));
    assertThrows(
        IllegalArgumentException.class,
        () -> VerificationOptions.parseVariables(Arrays.asList("   ")));
  }

  @Test
  public void parseVariables_nullElement_throws() {
    assertThrows(
        NullPointerException.class,
        () -> VerificationOptions.parseVariables(Arrays.asList((String) null)));
  }

  @Test
  public void checkSatisfiable_satisfiable() throws Exception {
    VerificationOptions options =
        VerificationOptions.builder().setTimeout(Duration.ofSeconds(5)).build();
    ImmutableMap<String, CelType> vars =
        ImmutableMap.of("role", SimpleType.STRING, "port", SimpleType.INT);

    CelVerificationResult result =
        CelVerifierToolCore.checkSatisfiable("role == 'editor' && port > 1024", vars, options);

    assertThat(result.status()).isEqualTo(VerificationStatus.VERIFIED);
    assertThat(result.message()).contains("satisfiable");
  }

  @Test
  public void checkValid_valid() throws Exception {
    VerificationOptions options =
        VerificationOptions.builder().setTimeout(Duration.ofSeconds(5)).build();
    ImmutableMap<String, CelType> vars = ImmutableMap.of("x", SimpleType.INT);

    CelVerificationResult result =
        CelVerifierToolCore.checkValid("x > 10 || x <= 10", vars, options);

    assertThat(result.status()).isEqualTo(VerificationStatus.VERIFIED);
  }

  @Test
  public void verifyEquivalence_equivalent() throws Exception {
    VerificationOptions options =
        VerificationOptions.builder().setTimeout(Duration.ofSeconds(5)).build();
    ImmutableMap<String, CelType> vars = ImmutableMap.of("x", SimpleType.INT);

    CelVerificationResult result =
        CelVerifierToolCore.verifyEquivalence("x > 10", "10 < x", vars, options);

    assertThat(result.status()).isEqualTo(VerificationStatus.VERIFIED);
  }

  @Test
  public void verifyEquivalence_canonicalizedMapComprehension() throws Exception {
    VerificationOptions options =
        VerificationOptions.builder().setTimeout(Duration.ofSeconds(5)).build();
    ImmutableMap<String, CelType> vars =
        ImmutableMap.of("map_string_int", MapType.create(SimpleType.STRING, SimpleType.INT));

    CelVerificationResult result =
        CelVerifierToolCore.verifyEquivalence(
            "map_string_int.exists(k, v, k == 'foo' && v == 1)",
            "map_string_int.exists(k, v, v == 1 && k == 'foo')",
            vars,
            options);

    assertThat(result.status()).isEqualTo(VerificationStatus.VERIFIED);
  }

  @Test
  public void verifyPolicyInvariants_success() throws Exception {
    String yamlPolicy =
        "name: secure_access_policy\n"
            + "rule:\n"
            + "  match:\n"
            + "    - condition: port == 80\n"
            + "      output: 'true'\n"
            + "    - output: 'false'\n"
            + "verification:\n"
            + "  invariants:\n"
            + "    - id: port_check\n"
            + "      assert:\n"
            + "        - port == 80 || port != 80\n";
    VerificationOptions options =
        VerificationOptions.builder().setTimeout(Duration.ofSeconds(5)).build();
    ImmutableMap<String, CelType> vars = ImmutableMap.of("port", SimpleType.INT);

    ImmutableMap<String, CelVerificationResult> results =
        CelVerifierToolCore.verifyPolicyInvariants(yamlPolicy, vars, options);

    assertThat(results).containsKey("port_check");
    assertThat(results.get("port_check").status()).isEqualTo(VerificationStatus.VERIFIED);
  }

  @Test
  public void verifyPolicyEquivalence_equivalent() throws Exception {
    String policyA =
        "name: policy_a\n"
            + "rule:\n"
            + "  match:\n"
            + "    - condition: port == 80\n"
            + "      output: 'true'\n"
            + "    - output: 'false'\n";
    String policyB =
        "name: policy_b\n"
            + "rule:\n"
            + "  match:\n"
            + "    - condition: 80 == port\n"
            + "      output: 'true'\n"
            + "    - output: 'false'\n";
    VerificationOptions options =
        VerificationOptions.builder().setTimeout(Duration.ofSeconds(5)).build();
    ImmutableMap<String, CelType> vars = ImmutableMap.of("port", SimpleType.INT);

    CelVerificationResult result =
        CelVerifierToolCore.verifyPolicyEquivalence(policyA, policyB, vars, options);

    assertThat(result.status()).isEqualTo(VerificationStatus.VERIFIED);
  }

  @Test
  public void formatTextPolicyResults_verifiedAndViolated() throws Exception {
    VerificationOptions options =
        VerificationOptions.builder().setTimeout(Duration.ofSeconds(5)).build();
    CelVerificationResult verifiedRes =
        CelVerifierToolCore.checkSatisfiable("true", ImmutableMap.of(), options);
    CelVerificationResult violatedRes =
        CelVerifierToolCore.checkValid("x > 0", ImmutableMap.of("x", SimpleType.INT), options);
    ImmutableMap<String, CelVerificationResult> results =
        ImmutableMap.of("inv_1", verifiedRes, "inv_2", violatedRes);

    String text = FormatUtils.formatTextPolicyResults("test_policy", results);

    assertThat(text).contains("Policy Invariant Verification for 'test_policy':");
    assertThat(text).contains("✓ Invariant 'inv_1': VERIFIED");
    assertThat(text).contains("✗ Invariant 'inv_2': VIOLATED");
  }

  @Test
  public void formatJsonPolicyResults_structuredJson() throws Exception {
    VerificationOptions options =
        VerificationOptions.builder().setTimeout(Duration.ofSeconds(5)).build();
    CelVerificationResult result =
        CelVerifierToolCore.checkSatisfiable("true", ImmutableMap.of(), options);
    ImmutableMap<String, CelVerificationResult> results = ImmutableMap.of("inv_1", result);

    String json = FormatUtils.formatJsonPolicyResults("my_policy", results);

    assertThat(json).startsWith("{\n");
    assertThat(json).contains("\"policyName\": \"my_policy\"");
    assertThat(json).contains("\"id\": \"inv_1\"");
    assertThat(json).contains("\"status\": \"VERIFIED\"");
    assertThat(json).endsWith("}");
  }

  @Test
  public void celVerifierTool_verifyPolicy_success() throws Exception {
    File policyFile = tempFolder.newFile("test_policy.yaml");
    String yamlContent =
        "name: test_policy\n"
            + "rule:\n"
            + "  match:\n"
            + "    - condition: port == 80\n"
            + "      output: 'true'\n"
            + "    - output: 'false'\n"
            + "verification:\n"
            + "  invariants:\n"
            + "    - id: port_check\n"
            + "      assert:\n"
            + "        - port == 80 || port != 80\n";
    Files.write(policyFile.toPath(), yamlContent.getBytes(StandardCharsets.UTF_8));

    String output =
        executeToolWithOutput(
            "verify-policy",
            "--file",
            policyFile.getAbsolutePath(),
            "--var",
            "port:int",
            "-fmt",
            "json");

    assertThat(output).contains("\"policyName\": \"test_policy.yaml\"");
    assertThat(output).contains("\"status\": \"VERIFIED\"");
  }

  @Test
  public void celVerifierTool_verifyPolicy_violated() throws Exception {
    File policyFile = tempFolder.newFile("violated_policy.yaml");
    String yamlContent =
        "name: violated_policy\n"
            + "rule:\n"
            + "  match:\n"
            + "    - condition: port == 80\n"
            + "      output: 'true'\n"
            + "    - output: 'false'\n"
            + "verification:\n"
            + "  invariants:\n"
            + "    - id: invalid_check\n"
            + "      assert:\n"
            + "        - port > 1024\n";
    Files.write(policyFile.toPath(), yamlContent.getBytes(StandardCharsets.UTF_8));

    int exitCode =
        new CommandLine(new CelVerifierTool())
            .execute("verify-policy", "--file", policyFile.getAbsolutePath(), "--var", "port:int");

    assertThat(exitCode).isEqualTo(CelVerifierTool.EXIT_CODE_VIOLATED);
  }

  @Test
  public void celVerifierTool_verifyPolicy_multipleInvariants_oneViolated() throws Exception {
    File policyFile = tempFolder.newFile("multi_invariant_policy.yaml");
    String yamlContent =
        "name: multi_invariant_policy\n"
            + "rule:\n"
            + "  match:\n"
            + "    - condition: port == 80\n"
            + "      output: 'true'\n"
            + "    - output: 'false'\n"
            + "verification:\n"
            + "  invariants:\n"
            + "    - id: valid_check\n"
            + "      assert:\n"
            + "        - port == 80 || port != 80\n"
            + "    - id: invalid_check\n"
            + "      assert:\n"
            + "        - port > 1024\n";
    Files.write(policyFile.toPath(), yamlContent.getBytes(StandardCharsets.UTF_8));

    int exitCode =
        new CommandLine(new CelVerifierTool())
            .execute("verify-policy", "--file", policyFile.getAbsolutePath(), "--var", "port:int");

    assertThat(exitCode).isEqualTo(CelVerifierTool.EXIT_CODE_VIOLATED);
  }

  @Test
  public void celVerifierTool_verifyPolicy_multipleInvariants_allVerified() throws Exception {
    File policyFile = tempFolder.newFile("multi_verified_policy.yaml");
    String yamlContent =
        "name: multi_verified_policy\n"
            + "rule:\n"
            + "  match:\n"
            + "    - condition: port == 80\n"
            + "      output: 'true'\n"
            + "    - output: 'false'\n"
            + "verification:\n"
            + "  invariants:\n"
            + "    - id: check_1\n"
            + "      assert:\n"
            + "        - port == 80 || port != 80\n"
            + "    - id: check_2\n"
            + "      assert:\n"
            + "        - port > 0 || port <= 0\n";
    Files.write(policyFile.toPath(), yamlContent.getBytes(StandardCharsets.UTF_8));

    int exitCode =
        new CommandLine(new CelVerifierTool())
            .execute("verify-policy", "--file", policyFile.getAbsolutePath(), "--var", "port:int");

    assertThat(exitCode).isEqualTo(CelVerifierTool.EXIT_CODE_VERIFIED);
  }

  @Test
  public void formatUtils_jsonResult() throws Exception {
    VerificationOptions options =
        VerificationOptions.builder().setTimeout(Duration.ofSeconds(5)).build();
    CelVerificationResult result =
        CelVerifierToolCore.checkSatisfiable("true", ImmutableMap.of(), options);

    String json = FormatUtils.formatJsonResult(result);

    assertThat(json).contains("\"status\": \"VERIFIED\"");
    assertThat(json).contains("satisfiable");
  }

  @Test
  public void celVerifierTool_checkSat_verified() {
    int exitCode =
        new CommandLine(new CelVerifierTool())
            .execute("check-sat", "--expr", "x > 0", "--var", "x:int");

    assertThat(exitCode).isEqualTo(CelVerifierTool.EXIT_CODE_VERIFIED);
  }

  @Test
  public void celVerifierTool_checkValid_violated() {
    int exitCode =
        new CommandLine(new CelVerifierTool())
            .execute("check-valid", "--expr", "x > 0", "--var", "x:int");

    assertThat(exitCode).isEqualTo(CelVerifierTool.EXIT_CODE_VIOLATED);
  }

  @Test
  public void celVerifierTool_verifyEquiv_verified() {
    int exitCode =
        new CommandLine(new CelVerifierTool())
            .execute("verify-equiv", "--expr1", "x > 10", "--expr2", "10 < x", "--var", "x:int");

    assertThat(exitCode).isEqualTo(CelVerifierTool.EXIT_CODE_VERIFIED);
  }

  @Test
  public void celVerifierTool_checkSat_compilationError() {
    String output =
        executeToolWithOutput("check-sat", "--expr", "invalid + + syntax", "--var", "x:int");

    assertThat(output).contains("Compilation error");
  }

  @Test
  public void celVerifierTool_checkValid_withUnknownOption_violated() {
    int exitCode =
        new CommandLine(new CelVerifierTool())
            .execute("check-valid", "--expr", "x == x", "--var", "x:int", "-u", "x");

    assertThat(exitCode).isEqualTo(CelVerifierTool.EXIT_CODE_VIOLATED);
  }

  @Test
  public void celVerifierTool_checkValid_inconclusive() {
    int exitCode =
        new CommandLine(new CelVerifierTool())
            .execute("check-valid", "--expr", "int('123') == 123");

    assertThat(exitCode).isEqualTo(CelVerifierTool.EXIT_CODE_INCONCLUSIVE);
  }

  @Test
  public void celVerifierTool_verifyPolicy_inconclusive() throws Exception {
    File policyFile = tempFolder.newFile("inconclusive_policy.yaml");
    String yamlContent =
        "name: inconclusive_policy\n"
            + "rule:\n"
            + "  match:\n"
            + "    - condition: port == 80\n"
            + "      output: 'true'\n"
            + "    - output: 'false'\n"
            + "verification:\n"
            + "  invariants:\n"
            + "    - id: approx_check\n"
            + "      assert:\n"
            + "        - int('123') == 123\n";
    Files.write(policyFile.toPath(), yamlContent.getBytes(StandardCharsets.UTF_8));

    int exitCode =
        new CommandLine(new CelVerifierTool())
            .execute("verify-policy", "--file", policyFile.getAbsolutePath(), "--var", "port:int");

    assertThat(exitCode).isEqualTo(CelVerifierTool.EXIT_CODE_INCONCLUSIVE);
  }

  @Test
  public void celVerifierTool_invalidOutputFormat_defaultsToText() {
    String output =
        executeToolWithOutput(
            "check-sat", "--expr", "x > 0", "--var", "x:int", "-fmt", "invalid_fmt");

    assertThat(output).contains("[VERIFIED]");
  }

  @Test
  public void formatTextPolicyResults_inconclusive() throws Exception {
    VerificationOptions options =
        VerificationOptions.builder().setTimeout(Duration.ofSeconds(5)).build();
    CelVerificationResult res =
        CelVerifierToolCore.checkValid("int('123') == 123", ImmutableMap.of(), options);

    String text = FormatUtils.formatTextPolicyResults("test_policy", ImmutableMap.of("inv_1", res));

    assertThat(text).contains("Invariant 'inv_1': INCONCLUSIVE");
  }

  @Test
  public void formatJson_escapesSpecialCharacters() throws Exception {
    VerificationOptions options =
        VerificationOptions.builder().setTimeout(Duration.ofSeconds(5)).build();
    CelVerificationResult res =
        CelVerifierToolCore.checkValid("int('123') == 123", ImmutableMap.of(), options);

    String json =
        FormatUtils.formatJsonPolicyResults(
            "policy_with_\"quote\"\nand_newline", ImmutableMap.of("inv\ttab", res));

    assertThat(json).contains("policy_with_\\\"quote\\\"\\nand_newline");
    assertThat(json).contains("inv\\ttab");
  }

  @Test
  public void celVerifierTool_version() {
    int exitCode = new CommandLine(new CelVerifierTool()).execute("--version");

    assertThat(exitCode).isEqualTo(0);
  }
}
