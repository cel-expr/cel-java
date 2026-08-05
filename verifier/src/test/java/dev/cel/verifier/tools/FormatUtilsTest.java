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
import dev.cel.common.types.SimpleType;
import dev.cel.verifier.CelVerificationResult;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class FormatUtilsTest {

  @Before
  public void setUp() {
    System.setProperty("z3.skipLibraryLoad", "true");
  }

  @Test
  public void formatJsonResult_verified() throws Exception {
    VerificationOptions options = VerificationOptions.builder().build();
    CelVerificationResult result =
        CelVerifierToolCore.checkSatisfiable("true", ImmutableMap.of(), options);

    String json = FormatUtils.formatJsonResult(result);

    assertThat(json)
        .isEqualTo(
            "{\n"
                + "  \"status\": \"VERIFIED\",\n"
                + "  \"message\": \"Condition is satisfiable. (The expression is satisfiable"
                + " unconditionally, regardless of input state)\"\n"
                + "}");
  }

  @Test
  public void formatJsonResult_violated() throws Exception {
    VerificationOptions options = VerificationOptions.builder().build();
    CelVerificationResult result =
        CelVerifierToolCore.checkValid("x > 0", ImmutableMap.of("x", SimpleType.INT), options);

    String json = FormatUtils.formatJsonResult(result);

    assertThat(json)
        .startsWith(
            "{\n  \"status\": \"VIOLATED\",\n  \"message\": \"Condition is not always true.");
    assertThat(json).endsWith("\"\n}");
  }

  @Test
  public void formatJsonPolicyResults_multipleInvariants() throws Exception {
    VerificationOptions options = VerificationOptions.builder().build();
    CelVerificationResult verified =
        CelVerifierToolCore.checkSatisfiable("true", ImmutableMap.of(), options);
    CelVerificationResult inconclusive =
        CelVerifierToolCore.checkValid("int('123') == 123", ImmutableMap.of(), options);
    ImmutableMap<String, CelVerificationResult> results =
        ImmutableMap.of("inv_1", verified, "inv_2", inconclusive);

    String json = FormatUtils.formatJsonPolicyResults("my_policy", results);

    assertThat(json).startsWith("{\n  \"policyName\": \"my_policy\",\n  \"invariants\": [\n");
    assertThat(json).contains("    {\n      \"id\": \"inv_1\",\n      \"status\": \"VERIFIED\"");
    assertThat(json).contains("    },\n    {\n      \"id\": \"inv_2\",");
    assertThat(json)
        .contains("    {\n      \"id\": \"inv_2\",\n      \"status\": \"INCONCLUSIVE\"");
    assertThat(json).endsWith("    }\n  ]\n}");
  }

  @Test
  public void formatTextResults_verified() throws Exception {
    VerificationOptions options = VerificationOptions.builder().build();
    CelVerificationResult result =
        CelVerifierToolCore.checkSatisfiable("true", ImmutableMap.of(), options);

    String text = FormatUtils.formatTextResult(result);

    assertThat(text).contains("[VERIFIED]");
  }

  @Test
  public void formatTextPolicyResults_inconclusive() throws Exception {
    VerificationOptions options = VerificationOptions.builder().build();
    CelVerificationResult result =
        CelVerifierToolCore.checkValid("int('123') == 123", ImmutableMap.of(), options);
    ImmutableMap<String, CelVerificationResult> results = ImmutableMap.of("inv_1", result);

    String text = FormatUtils.formatTextPolicyResults("test_policy", results);

    assertThat(text).contains("Policy Invariant Verification for 'test_policy':");
    assertThat(text).contains("Invariant 'inv_1': INCONCLUSIVE");
  }

  @Test
  public void escapeJson_escapesControlCharactersAndQuotes() {
    String input = "Hello \"world\"\nLine 2\t\u0000\u001b";

    String escaped = FormatUtils.escapeJson(input);

    assertThat(escaped).isEqualTo("Hello \\\"world\\\"\\nLine 2\\t\\u0000\\u001b");
  }
}
