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

import com.google.common.collect.ImmutableMap;
import dev.cel.common.types.CelType;
import dev.cel.common.types.SimpleType;
import dev.cel.verifier.CelVerificationResult;
import dev.cel.verifier.CelVerificationResult.VerificationStatus;
import java.time.Duration;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class CelVerifierToolTest {

  @Test
  public void parseVariables_success() {
    ImmutableMap<String, CelType> vars =
        VerificationOptions.parseVariables(
            java.util.Arrays.asList(
                "x:int",
                "role:string",
                "is_admin:bool",
                "tags:list<string>",
                "scores:map<string, int>"));
    assertThat(vars).containsEntry("x", SimpleType.INT);
    assertThat(vars).containsEntry("role", SimpleType.STRING);
    assertThat(vars).containsEntry("is_admin", SimpleType.BOOL);
    assertThat(vars).containsEntry("tags", dev.cel.common.types.ListType.create(SimpleType.STRING));
    assertThat(vars).containsEntry("scores", dev.cel.common.types.MapType.create(SimpleType.STRING, SimpleType.INT));
  }

  @Test
  public void checkSatisfiable_satisfiable() throws Exception {
    VerificationOptions options = VerificationOptions.builder().setTimeout(Duration.ofSeconds(5)).build();
    ImmutableMap<String, CelType> vars = ImmutableMap.of("role", SimpleType.STRING, "port", SimpleType.INT);

    CelVerificationResult result =
        CelVerifierToolCore.checkSatisfiable("role == 'editor' && port > 1024", vars, options);

    assertThat(result.status()).isEqualTo(VerificationStatus.VERIFIED);
    assertThat(result.message()).contains("satisfiable");
  }

  @Test
  public void checkValid_valid() throws Exception {
    VerificationOptions options = VerificationOptions.builder().setTimeout(Duration.ofSeconds(5)).build();
    ImmutableMap<String, CelType> vars = ImmutableMap.of("x", SimpleType.INT);

    CelVerificationResult result =
        CelVerifierToolCore.checkValid("x > 10 || x <= 10", vars, options);

    assertThat(result.status()).isEqualTo(VerificationStatus.VERIFIED);
  }

  @Test
  public void verifyEquivalence_equivalent() throws Exception {
    VerificationOptions options = VerificationOptions.builder().setTimeout(Duration.ofSeconds(5)).build();
    ImmutableMap<String, CelType> vars = ImmutableMap.of("x", SimpleType.INT);

    CelVerificationResult result =
        CelVerifierToolCore.verifyEquivalence("x > 10", "10 < x", vars, options);

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

    VerificationOptions options = VerificationOptions.builder().setTimeout(Duration.ofSeconds(5)).build();
    ImmutableMap<String, CelType> vars = ImmutableMap.of("port", SimpleType.INT);

    ImmutableMap<String, CelVerificationResult> results =
        CelVerifierToolCore.verifyPolicyInvariants(yamlPolicy, vars, options);

    assertThat(results).containsKey("port_check");
    assertThat(results.get("port_check").status()).isEqualTo(VerificationStatus.VERIFIED);
  }

  @Test
  public void formatUtils_jsonResult() throws Exception {
    VerificationOptions options = VerificationOptions.builder().setTimeout(Duration.ofSeconds(5)).build();
    CelVerificationResult result = CelVerifierToolCore.checkSatisfiable("true", ImmutableMap.of(), options);
    String json = FormatUtils.formatJsonResult(result);
    assertThat(json).contains("\"status\": \"VERIFIED\"");
    assertThat(json).contains("satisfiable");
  }
}


