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

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import dev.cel.bundle.CelEnvironment.TypeDecl;
import dev.cel.common.types.CelKind;
import dev.cel.common.types.CelType;
import dev.cel.common.types.CelTypeProvider;
import dev.cel.common.types.ProtoMessageTypeProvider;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Configuration options for CEL verification CLI operations. */
final class VerificationOptions {

  /** Output format for verification CLI results. */
  enum OutputFormat {
    TEXT,
    JSON
  }

  static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);
  static final int DEFAULT_COMPREHENSION_UNROLL_LIMIT = 5;
  static final OutputFormat DEFAULT_OUTPUT_FORMAT = OutputFormat.TEXT;
  private static final CelTypeProvider EMPTY_TYPE_PROVIDER =
      ProtoMessageTypeProvider.newBuilder().build();
  private static final ImmutableSet<CelKind> UNDECLARABLE_KINDS =
      Sets.immutableEnumSet(CelKind.NULL_TYPE, CelKind.TYPE, CelKind.TYPE_PARAM);

  private final Duration timeout;
  private final int comprehensionUnrollLimit;
  private final ImmutableList<String> unknownIdentifiers;
  private final OutputFormat outputFormat;

  Duration getTimeout() {
    return timeout;
  }

  int getComprehensionUnrollLimit() {
    return comprehensionUnrollLimit;
  }

  ImmutableList<String> getUnknownIdentifiers() {
    return unknownIdentifiers;
  }

  OutputFormat getOutputFormat() {
    return outputFormat;
  }

  static Builder builder() {
    return new Builder();
  }

  /** A builder for {@link VerificationOptions}. */
  static final class Builder {
    private Duration timeout = DEFAULT_TIMEOUT;
    private int comprehensionUnrollLimit = DEFAULT_COMPREHENSION_UNROLL_LIMIT;
    private ImmutableList<String> unknownIdentifiers = ImmutableList.of();
    private OutputFormat outputFormat = DEFAULT_OUTPUT_FORMAT;

    @CanIgnoreReturnValue
    Builder setTimeout(Duration timeout) {
      this.timeout = Preconditions.checkNotNull(timeout);
      return this;
    }

    @CanIgnoreReturnValue
    Builder setComprehensionUnrollLimit(int unrollLimit) {
      Preconditions.checkArgument(unrollLimit >= 0, "unrollLimit must be non-negative");
      this.comprehensionUnrollLimit = unrollLimit;
      return this;
    }

    @CanIgnoreReturnValue
    Builder setUnknownIdentifiers(List<String> unknownIdentifiers) {
      this.unknownIdentifiers = ImmutableList.copyOf(unknownIdentifiers);
      return this;
    }

    @CanIgnoreReturnValue
    Builder setOutputFormat(OutputFormat outputFormat) {
      this.outputFormat = Preconditions.checkNotNull(outputFormat);
      return this;
    }

    VerificationOptions build() {
      return new VerificationOptions(
          timeout, comprehensionUnrollLimit, unknownIdentifiers, outputFormat);
    }
  }

  private VerificationOptions(
      Duration timeout,
      int comprehensionUnrollLimit,
      ImmutableList<String> unknownIdentifiers,
      OutputFormat outputFormat) {
    this.timeout = timeout;
    this.comprehensionUnrollLimit = comprehensionUnrollLimit;
    this.unknownIdentifiers = unknownIdentifiers;
    this.outputFormat = outputFormat;
  }

  /**
   * Helper utility to parse CLI variable definitions formatted as "name:type" (e.g. "x:int",
   * "role:string", "is_admin:bool").
   */
  static ImmutableMap<String, CelType> parseVariables(List<String> varSpecs) {
    if (varSpecs == null || varSpecs.isEmpty()) {
      return ImmutableMap.of();
    }
    Map<String, CelType> vars = new HashMap<>();
    for (String varSpec : varSpecs) {
      Preconditions.checkNotNull(varSpec, "Variable specification cannot be null.");
      String[] parts = varSpec.split(":", 2);
      if (parts.length != 2) {
        throw new IllegalArgumentException(
            "Invalid variable specification: '"
                + varSpec
                + "'. Expected format 'name:type' (e.g., 'x:int').");
      }
      String name = parts[0].trim();
      if (name.isEmpty()) {
        throw new IllegalArgumentException(
            "Invalid variable specification: '" + varSpec + "'. Variable name cannot be empty.");
      }
      vars.put(name, parseCelType(parts[1]));
    }
    return ImmutableMap.copyOf(vars);
  }

  static CelType parseCelType(String typeStr) {
    CelType type = TypeDecl.parse(typeStr).toCelType(EMPTY_TYPE_PROVIDER);
    checkDeclarable(type);
    return type;
  }

  private static void checkDeclarable(CelType type) {
    Preconditions.checkArgument(
        !UNDECLARABLE_KINDS.contains(type.kind()), "Unsupported variable type: %s", type.name());
    type.parameters().forEach(VerificationOptions::checkDeclarable);
  }
}
