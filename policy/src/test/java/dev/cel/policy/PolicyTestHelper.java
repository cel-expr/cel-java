// Copyright 2024 Google LLC
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

package dev.cel.policy;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Ascii;
import com.google.common.io.Files;
import com.google.devtools.build.runfiles.AutoBazelRepository;
import com.google.devtools.build.runfiles.Runfiles;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;

/** Package-private class to assist with policy testing. */
@AutoBazelRepository
final class PolicyTestHelper {

  private static final Runfiles runfiles = createRunfiles();

  enum TestYamlPolicy {
    NESTED_RULE(
        "nested_rule",
        false,
        "cel.@block([resource.origin, @index0 in [\"us\", \"uk\", \"es\"], {\"banned\": true}],"
            + " ((@index0 in {\"us\": false, \"ru\": false, \"ir\": false} && !@index1) ?"
            + " optional.of(@index2) : optional.none()).orValue(@index1 ? {\"banned\":"
            + " false} : @index2))"),
    NESTED_RULE2(
        "nested_rule2",
        false,
        "cel.@block([resource.origin, !(@index0 in [\"us\", \"uk\", \"es\"])],"
            + " resource.?user.orValue(\"\").startsWith(\"bad\") ? ((@index0 in {\"us\": false,"
            + " \"ru\": false, \"ir\": false} && @index1) ? {\"banned\": \"restricted_region\"} :"
            + " {\"banned\": \"bad_actor\"}) : (@index1 ? {\"banned\": \"unconfigured_region\"} :"
            + " {}))"),
    NESTED_RULE3(
        "nested_rule3",
        true,
        "cel.@block([resource.origin, !(@index0 in [\"us\", \"uk\", \"es\"])],"
            + " resource.?user.orValue(\"\").startsWith(\"bad\") ? optional.of((@index0 in {\"us\":"
            + " false, \"ru\": false, \"ir\": false} && @index1) ? {\"banned\":"
            + " \"restricted_region\"} : {\"banned\": \"bad_actor\"}) : (@index1 ?"
            + " optional.of({\"banned\": \"unconfigured_region\"}) : optional.none()))"),
    NESTED_RULE4("nested_rule4", false, "(x > 0) ? true : false"),
    NESTED_RULE5(
        "nested_rule5",
        true,
        "cel.@block([optional.of(true), optional.none()], (x > 0) ? ((x > 2) ? @index0 : @index1) :"
            + " ((x > 1) ? ((x >= 2) ? @index0 : @index1) : optional.of(false)))"),
    NESTED_RULE6(
        "nested_rule6",
        false,
        "cel.@block([optional.of(true), optional.none()], ((x > 2) ? @index0 : @index1).orValue(((x"
            + " > 3) ? @index0 : @index1).orValue(false)))"),
    NESTED_RULE7(
        "nested_rule7",
        true,
        "cel.@block([optional.of(true), optional.none()], ((x > 2) ? @index0 : @index1).or(((x > 3)"
            + " ? @index0 : @index1).or((x > 1) ? optional.of(false) : @index1)))"),
    REQUIRED_LABELS(
        "required_labels",
        true,
        "cel.@block([resource.labels, spec.labels.filter(@it:0:0, !(@it:0:0 in @index0)),"
            + " spec.labels, @index0.transformList(@it:0:1, @it2:0:1, @it:0:1 in @index2 &&"
            + " @it2:0:1 != @index2[@it:0:1], @it:0:1)], (@index1.size() > 0) ?"
            + " optional.of(\"missing one or more required labels: [\"\" + @index1.join(\"\", \"\")"
            + " + \"\"]\") : ((@index3.size() > 0) ? optional.of(\"invalid values provided on one"
            + " or more labels: [\"\" + @index3.join(\"\", \"\") + \"\"]\") : optional.none()))"),
    RESTRICTED_DESTINATIONS(
        "restricted_destinations",
        false,
        "cel.@block([request.auth.claims, has(@index0.nationality), resource.labels.location in"
            + " spec.restricted_destinations], (@index1 && @index0.nationality == spec.origin &&"
            + " (locationCode(destination.ip) in spec.restricted_destinations || @index2)) ? true :"
            + " ((!@index1 && locationCode(origin.ip) == spec.origin &&"
            + " (locationCode(destination.ip) in spec.restricted_destinations || @index2)) ? true :"
            + " false))"),
    K8S(
        "k8s",
        true,
        "cel.@block([resource.labels.?environment.orValue(\"prod\"), @index0 + \".\"],"
            + " !(resource.labels.?break_glass.orValue(\"false\") == \"true\" ||"
            + " resource.containers.all(@it:0:0, @it:0:0.startsWith(@index1))) ?"
            + " optional.of(\"only \" + @index0 + \" containers are allowed in namespace \" +"
            + " resource.namespace) : optional.none())"),
    PB(
        "pb",
        true,
        "cel.@block([spec.single_int32], (@index0 > 10) ? optional.of(\"invalid spec, got"
            + " single_int32=\" + string(@index0) + \", wanted <= 10\") : ((spec.standalone_enum =="
            + " cel.expr.conformance.proto3.TestAllTypes.NestedEnum.BAR ||"
            + " cel.expr.conformance.proto3.TestAllTypes.NestedEnum.BAZ in"
            + " spec.repeated_nested_enum || cel.expr.conformance.proto3.GlobalEnum.GAR =="
            + " cel.expr.conformance.proto3.GlobalEnum.GOO) ? optional.of(\"invalid spec, neither"
            + " nested nor repeated enums may refer to BAR or BAZ\") : optional.none()))"),
    LIMITS(
        "limits",
        true,
        "cel.@block([now.getHours()], (@index0 >= 20) ? ((@index0 < 21) ? optional.of(\"goodbye,"
            + " me!\") : ((@index0 < 22) ? optional.of(\"goodbye, me!!\") : ((@index0 < 24) ?"
            + " optional.of(\"goodbye, me!!!\") : optional.none()))) : optional.of(\"hello,"
            + " me\"))");

    private final String name;
    private final boolean producesOptionalResult;
    private final String unparsed;

    TestYamlPolicy(String name, boolean producesOptionalResult, String unparsed) {
      this.name = name;
      this.producesOptionalResult = producesOptionalResult;
      this.unparsed = unparsed;
    }

    String getPolicyName() {
      return name;
    }

    boolean producesOptionalResult() {
      return this.producesOptionalResult;
    }

    String getUnparsed() {
      return unparsed;
    }

    String readPolicyYamlContent() throws IOException {
      return readFromYaml(
          String.format(
              "cel_policy/conformance/testdata/%s/policy.yaml", name));
    }

    String readConfigYamlContent() throws IOException {
      return readFromYaml(
          String.format(
              "cel_policy/conformance/testdata/%s/config.yaml", name));
    }

    PolicyTestSuite readTestYamlContent() throws IOException {
      Yaml yaml = new Yaml(new Constructor(PolicyTestSuite.class, new LoaderOptions()));
      String testContent =
          readFile(
              String.format(
                  "cel_policy/conformance/testdata/%s/tests.yaml", name));

      return yaml.load(testContent);
    }
  }

  static String readFromYaml(String yamlPath) throws IOException {
    return readFile(yamlPath);
  }

  /**
   * TestSuite describes a set of tests divided by section.
   *
   * <p>Visibility must be public for YAML deserialization to work. This is effectively
   * package-private since the outer class is.
   */
  @VisibleForTesting
  public static final class PolicyTestSuite {
    private String name;
    private String description;
    private List<PolicyTestSection> section;

    public void setName(String name) {
      this.name = name;
    }

    public String getName() {
      return name;
    }

    public void setDescription(String description) {
      this.description = description;
    }

    public void setSection(List<PolicyTestSection> section) {
      this.section = section;
    }

    public String getDescription() {
      return description;
    }

    public List<PolicyTestSection> getSection() {
      return section;
    }

    @VisibleForTesting
    public static final class PolicyTestSection {
      private String name;
      private List<PolicyTestCase> tests;

      public void setName(String name) {
        this.name = name;
      }

      public void setTests(List<PolicyTestCase> tests) {
        this.tests = tests;
      }

      public String getName() {
        return name;
      }

      public List<PolicyTestCase> getTests() {
        return tests;
      }

      @VisibleForTesting
      public static final class PolicyTestCase {
        private String name;
        private Map<String, PolicyTestInput> input;
        private Object output;

        public void setName(String name) {
          this.name = name;
        }

        public void setInput(Map<String, PolicyTestInput> input) {
          this.input = input;
        }

        public void setOutput(Object output) {
          this.output = output;
        }

        public String getName() {
          return name;
        }

        public Map<String, PolicyTestInput> getInput() {
          return input;
        }

        public Object getOutput() {
          return output;
        }

        @VisibleForTesting
        public static final class PolicyTestInput {
          private Object value;
          private String expr;

          public Object getValue() {
            return value;
          }

          public void setValue(Object value) {
            this.value = value;
          }

          public String getExpr() {
            return expr;
          }

          public void setExpr(String expr) {
            this.expr = expr;
          }
        }
      }
    }
  }

  private static String readFile(String rlocationPath) throws IOException {
    String resolvedPath = runfiles.rlocation(Ascii.toLowerCase(rlocationPath));
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
    return Files.asCharSource(file, UTF_8).read();
  }

  static boolean hasRunfile(String rlocationPath) {
    String resolvedPath = runfiles.rlocation(Ascii.toLowerCase(rlocationPath));
    return resolvedPath != null && new File(resolvedPath).exists();
  }

  private static Runfiles createRunfiles() {
    try {
      return Runfiles.preload().withSourceRepository(AutoBazelRepository_PolicyTestHelper.NAME);
    } catch (IOException e) {
      throw new RuntimeException("Failed to initialize Runfiles", e);
    }
  }

  private PolicyTestHelper() {}
}
