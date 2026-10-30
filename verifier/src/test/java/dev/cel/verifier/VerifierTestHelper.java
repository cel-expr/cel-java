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

package dev.cel.verifier;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.base.Ascii;
import com.google.common.io.Files;
import com.google.devtools.build.runfiles.AutoBazelRepository;
import com.google.devtools.build.runfiles.Runfiles;
import java.io.File;
import java.io.IOException;
import java.util.Optional;

/** Package-private class to assist with verifier testing and runfiles resolution. */
@AutoBazelRepository
final class VerifierTestHelper {

  private static final Runfiles RUNFILES = createRunfiles();

  static String loadVerificationPolicyYaml(String filename) throws IOException {
    return loadRunfile(
        "cel_java/testing/src/test/resources/policy/verification/" + filename);
  }

  static String loadConformancePolicyYaml(String policyDir) throws IOException {
    return loadRunfile(
        String.format(
            "cel_policy/conformance/testdata/%s/policy.yaml", policyDir));
  }

  static Optional<String> maybeLoadConformanceConfigYaml(String policyDir) throws IOException {
    return maybeLoadRunfile(
        String.format(
            "cel_policy/conformance/testdata/%s/config.yaml", policyDir));
  }

  private static String loadRunfile(String rlocationPath) throws IOException {
    return maybeLoadRunfile(rlocationPath)
        .orElseThrow(() -> new IOException("Runfile not found: " + rlocationPath));
  }

  private static Optional<String> maybeLoadRunfile(String rlocationPath) throws IOException {
    String resolvedPath = RUNFILES.rlocation(Ascii.toLowerCase(rlocationPath));
    if (resolvedPath == null || !new File(resolvedPath).exists()) {
      return Optional.empty();
    }
    return Optional.of(Files.asCharSource(new File(resolvedPath), UTF_8).read());
  }

  private static Runfiles createRunfiles() {
    try {
      return Runfiles.preload().withSourceRepository(AutoBazelRepository_VerifierTestHelper.NAME);
    } catch (IOException e) {
      throw new RuntimeException("Failed to initialize Runfiles", e);
    }
  }

  private VerifierTestHelper() {}
}
