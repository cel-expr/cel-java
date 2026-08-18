// Copyright 2026 Google LLC
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package dev.cel.conformance.policy;

import static com.google.common.truth.Truth.assertThat;

import com.google.protobuf.Struct;
import dev.cel.bundle.Cel;
import dev.cel.bundle.CelFactory;
import dev.cel.expr.conformance.proto3.TestAllTypes;
import dev.cel.policy.CelPolicyParserFactory;
import dev.cel.policy.CelPolicyValidationException;
import dev.cel.policy.testing.K8sTagHandler;
import dev.cel.runtime.CelFunctionBinding;
import dev.cel.testing.testrunner.CelExpressionSource;
import dev.cel.testing.testrunner.CelTestContext;
import dev.cel.testing.testrunner.CelTestSuite.CelTestSection.CelTestCase;
import dev.cel.testing.testrunner.TestRunnerLibrary;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Map;
import org.junit.runners.model.Statement;

/** Statement representing a single CEL policy conformance test case. */
public final class PolicyConformanceTest extends Statement {

  private static final Cel CEL =
      CelFactory.standardCelBuilder()
          .addFunctionBindings(
              CelFunctionBinding.from(
                  "locationCode_string",
                  String.class,
                  (ip) -> {
                    switch (ip) {
                      case "10.0.0.1":
                        return "us";
                      case "10.0.0.2":
                        return "de";
                      default:
                        return "ir";
                    }
                  }),
              CelFunctionBinding.from(
                  "hasCreditCard",
                  Object.class,
                  (arg) -> arg instanceof Map && ((Map<?, ?>) arg).containsKey("cc")),
              CelFunctionBinding.from(
                  "hasEmailOrPhone",
                  Object.class,
                  (arg) ->
                      arg instanceof Map
                          && (((Map<?, ?>) arg).containsKey("email")
                              || ((Map<?, ?>) arg).containsKey("phone"))))
          .build();

  private final String name;
  private final CelTestCase testCase;
  private final String dirPath;

  public PolicyConformanceTest(String name, CelTestCase testCase, String dirPath) {
    this.name = name;
    this.testCase = testCase;
    this.dirPath = dirPath;
  }

  public String getName() {
    return name;
  }

  @Override
  public void evaluate() throws Throwable {
    String policyFile = Paths.get(dirPath, "policy.yaml").toString();

    CelTestContext.Builder contextBuilder =
        CelTestContext.newBuilder()
            .setCelExpression(CelExpressionSource.fromSource(policyFile))
            .setCel(CEL)
            .addFileTypes(
                TestAllTypes.getDescriptor().getFile(),
                Struct.getDescriptor().getFile());

    // Scopes the custom Kubernetes tag visitor exclusively to k8s tests to prevent non-standard
    // grammar leakage.
    if (name.startsWith("k8s/")) {
      contextBuilder.setCelPolicyParser(
          CelPolicyParserFactory.newYamlParserBuilder().addTagVisitor(new K8sTagHandler()).build());
    }

    Path yamlConfigPath = Paths.get(dirPath, "config.yaml");
    Path textprotoConfigPath = Paths.get(dirPath, "config.textproto");

    if (Files.exists(yamlConfigPath)) {
      contextBuilder.setConfigFile(yamlConfigPath.toString());
    } else if (Files.exists(textprotoConfigPath)) {
      contextBuilder.setConfigFile(textprotoConfigPath.toString());
    }

    try {
      TestRunnerLibrary.runTest(testCase, contextBuilder.build());
    } catch (CelPolicyValidationException e) {
      if (testCase.output().kind() == CelTestCase.Output.Kind.EVAL_ERROR) {
        String expectedError = testCase.output().evalError().get(0).toString();
        assertThat(e.getMessage().toLowerCase(Locale.US))
            .contains(expectedError.toLowerCase(Locale.US));
      } else {
        throw e;
      }
    }
  }
}
