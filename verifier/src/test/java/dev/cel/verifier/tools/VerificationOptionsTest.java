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
import dev.cel.verifier.tools.VerificationOptions.OutputFormat;
import java.time.Duration;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class VerificationOptionsTest {

  @Test
  public void defaultOptions() {
    VerificationOptions options = VerificationOptions.builder().build();

    assertThat(options.getTimeout()).isEqualTo(VerificationOptions.DEFAULT_TIMEOUT);
    assertThat(options.getComprehensionUnrollLimit())
        .isEqualTo(VerificationOptions.DEFAULT_COMPREHENSION_UNROLL_LIMIT);
    assertThat(options.getUnknownIdentifiers()).isEmpty();
    assertThat(options.getOutputFormat()).isEqualTo(VerificationOptions.DEFAULT_OUTPUT_FORMAT);
  }

  @Test
  public void customOptions_allFieldsSet() {
    VerificationOptions options =
        VerificationOptions.builder()
            .setTimeout(Duration.ofSeconds(25))
            .setComprehensionUnrollLimit(12)
            .setUnknownIdentifiers(ImmutableList.of("req.auth", "req.headers"))
            .setOutputFormat(OutputFormat.JSON)
            .build();

    assertThat(options.getTimeout()).isEqualTo(Duration.ofSeconds(25));
    assertThat(options.getComprehensionUnrollLimit()).isEqualTo(12);
    assertThat(options.getUnknownIdentifiers())
        .containsExactly("req.auth", "req.headers")
        .inOrder();
    assertThat(options.getOutputFormat()).isEqualTo(OutputFormat.JSON);
  }

  @Test
  public void setTimeout_null_throwsException() {
    VerificationOptions.Builder builder = VerificationOptions.builder();

    assertThrows(NullPointerException.class, () -> builder.setTimeout(null));
  }

  @Test
  public void setComprehensionUnrollLimit_negative_throwsException() {
    VerificationOptions.Builder builder = VerificationOptions.builder();

    assertThrows(IllegalArgumentException.class, () -> builder.setComprehensionUnrollLimit(-1));
  }

  @Test
  public void setOutputFormat_null_throwsException() {
    VerificationOptions.Builder builder = VerificationOptions.builder();

    assertThrows(NullPointerException.class, () -> builder.setOutputFormat(null));
  }

  @Test
  public void parseVariables_validSpecs() {
    ImmutableMap<String, CelType> vars =
        VerificationOptions.parseVariables(
            ImmutableList.of(
                "x:int",
                "name:string",
                "flag:bool",
                "created_at:timestamp",
                "timeout:duration",
                "opt_user:optional<string>",
                "opt_list:optional<list<int>>",
                "opt_map:optional<map<string, int>>"));

    assertThat(vars)
        .containsExactly(
            "x", SimpleType.INT,
            "name", SimpleType.STRING,
            "flag", SimpleType.BOOL,
            "created_at", SimpleType.TIMESTAMP,
            "timeout", SimpleType.DURATION,
            "opt_user", OptionalType.create(SimpleType.STRING),
            "opt_list", OptionalType.create(ListType.create(SimpleType.INT)),
            "opt_map", OptionalType.create(MapType.create(SimpleType.STRING, SimpleType.INT)));
  }

  @Test
  public void parseCelType_timestampAndDuration() {
    assertThat(VerificationOptions.parseCelType("timestamp")).isEqualTo(SimpleType.TIMESTAMP);
    assertThat(VerificationOptions.parseCelType("google.protobuf.timestamp"))
        .isEqualTo(SimpleType.TIMESTAMP);
    assertThat(VerificationOptions.parseCelType("google.protobuf.Timestamp"))
        .isEqualTo(SimpleType.TIMESTAMP);
    assertThat(VerificationOptions.parseCelType("duration")).isEqualTo(SimpleType.DURATION);
    assertThat(VerificationOptions.parseCelType("google.protobuf.duration"))
        .isEqualTo(SimpleType.DURATION);
    assertThat(VerificationOptions.parseCelType("google.protobuf.Duration"))
        .isEqualTo(SimpleType.DURATION);
  }

  @Test
  public void parseCelType_optionalTypes() {
    assertThat(VerificationOptions.parseCelType("optional<int>"))
        .isEqualTo(OptionalType.create(SimpleType.INT));
    assertThat(VerificationOptions.parseCelType("optional<string>"))
        .isEqualTo(OptionalType.create(SimpleType.STRING));
    assertThat(VerificationOptions.parseCelType("optional_type<bool>"))
        .isEqualTo(OptionalType.create(SimpleType.BOOL));
    assertThat(VerificationOptions.parseCelType("optional<optional<int>>"))
        .isEqualTo(OptionalType.create(OptionalType.create(SimpleType.INT)));
    assertThat(VerificationOptions.parseCelType("map<string, optional<int>>"))
        .isEqualTo(MapType.create(SimpleType.STRING, OptionalType.create(SimpleType.INT)));
  }

  @Test
  public void parseCelType_parenthesesSyntax_throwsException() {
    assertThrows(
        IllegalArgumentException.class, () -> VerificationOptions.parseCelType("optional(int)"));
    assertThrows(
        IllegalArgumentException.class,
        () -> VerificationOptions.parseCelType("optional_type(bool)"));
    assertThrows(
        IllegalArgumentException.class, () -> VerificationOptions.parseCelType("list(string)"));
    assertThrows(
        IllegalArgumentException.class, () -> VerificationOptions.parseCelType("map(string, int)"));
  }

  @Test
  public void parseVariables_nullOrEmpty_returnsEmptyMap() {
    assertThat(VerificationOptions.parseVariables(null)).isEmpty();
    assertThat(VerificationOptions.parseVariables(ImmutableList.of())).isEmpty();
  }

  @Test
  public void parseVariables_invalidSpec_throwsException() {
    ImmutableList<String> specs = ImmutableList.of("invalid_spec_without_colon");

    assertThrows(IllegalArgumentException.class, () -> VerificationOptions.parseVariables(specs));
  }

  @Test
  public void parseVariables_unsupportedType_throwsException() {
    ImmutableList<String> specs = ImmutableList.of("x:unknown_type");

    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class, () -> VerificationOptions.parseVariables(specs));
    assertThat(ex)
        .hasMessageThat()
        .contains(
            "Supported types: int, uint, string, bool, double, bytes, dyn, timestamp,"
                + " duration, list<T>, map<K, V>, optional<T>.");
  }
}
