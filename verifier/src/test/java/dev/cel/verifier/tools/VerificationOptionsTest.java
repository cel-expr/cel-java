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
import dev.cel.common.types.CelKind;
import dev.cel.common.types.CelType;
import dev.cel.common.types.ListType;
import dev.cel.common.types.MapType;
import dev.cel.common.types.NullableType;
import dev.cel.common.types.OptionalType;
import dev.cel.common.types.SimpleType;
import dev.cel.expr.conformance.proto3.TestAllTypes;
import dev.cel.verifier.tools.VerificationOptions.OutputFormat;
import java.time.Duration;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class VerificationOptionsTest {
  private static final VerificationOptions OPTIONS = VerificationOptions.builder().build();

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
        OPTIONS.parseVariables(
            ImmutableList.of(
                "x:int",
                "name:string",
                "flag:bool",
                "created_at:timestamp",
                "timeout:duration",
                "opt_user:optional_type<string>",
                "opt_list:optional_type<list<int>>",
                "opt_map:optional_type<map<string, int>>"));

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
  public void parseCelType_wellKnownTypes() {
    assertThat(OPTIONS.parseCelType("google.protobuf.Timestamp")).isEqualTo(SimpleType.TIMESTAMP);
    assertThat(OPTIONS.parseCelType("google.protobuf.Duration")).isEqualTo(SimpleType.DURATION);
    assertThat(OPTIONS.parseCelType("google.protobuf.Value")).isEqualTo(SimpleType.DYN);
    assertThat(OPTIONS.parseCelType("google.protobuf.Struct"))
        .isEqualTo(MapType.create(SimpleType.STRING, SimpleType.DYN));
    assertThat(OPTIONS.parseCelType("google.protobuf.ListValue"))
        .isEqualTo(ListType.create(SimpleType.DYN));
    assertThat(OPTIONS.parseCelType("google.protobuf.BoolValue"))
        .isEqualTo(NullableType.create(SimpleType.BOOL));
    assertThat(OPTIONS.parseCelType("google.protobuf.BytesValue"))
        .isEqualTo(NullableType.create(SimpleType.BYTES));
    assertThat(OPTIONS.parseCelType("google.protobuf.DoubleValue"))
        .isEqualTo(NullableType.create(SimpleType.DOUBLE));
    assertThat(OPTIONS.parseCelType("google.protobuf.FloatValue"))
        .isEqualTo(NullableType.create(SimpleType.DOUBLE));
    assertThat(OPTIONS.parseCelType("google.protobuf.Int32Value"))
        .isEqualTo(NullableType.create(SimpleType.INT));
    assertThat(OPTIONS.parseCelType("google.protobuf.Int64Value"))
        .isEqualTo(NullableType.create(SimpleType.INT));
    assertThat(OPTIONS.parseCelType("google.protobuf.UInt32Value"))
        .isEqualTo(NullableType.create(SimpleType.UINT));
    assertThat(OPTIONS.parseCelType("google.protobuf.UInt64Value"))
        .isEqualTo(NullableType.create(SimpleType.UINT));
    assertThat(OPTIONS.parseCelType("google.protobuf.StringValue"))
        .isEqualTo(NullableType.create(SimpleType.STRING));
    assertThat(OPTIONS.parseCelType("google.protobuf.Any")).isEqualTo(SimpleType.ANY);
  }

  @Test
  public void parseCelType_protoTypes() {
    VerificationOptions options =
        VerificationOptions.builder()
            .setFileDescriptors(ImmutableList.of(TestAllTypes.getDescriptor().getFile()))
            .build();

    CelType message = options.parseCelType("cel.expr.conformance.proto3.TestAllTypes");
    CelType optionalEnum =
        options.parseCelType("optional_type<cel.expr.conformance.proto3.TestAllTypes.NestedEnum>");

    assertThat(message.kind()).isEqualTo(CelKind.STRUCT);
    assertThat(message.name()).isEqualTo("cel.expr.conformance.proto3.TestAllTypes");
    assertThat(optionalEnum).isEqualTo(OptionalType.create(SimpleType.INT));
  }

  @Test
  public void parseCelType_undeclarableType_throwsException() {
    IllegalArgumentException e =
        assertThrows(IllegalArgumentException.class, () -> OPTIONS.parseCelType("map<string, ~T>"));

    assertThat(e).hasMessageThat().isEqualTo("Unsupported variable type: T");
    assertThrows(IllegalArgumentException.class, () -> OPTIONS.parseCelType("null"));
    assertThrows(IllegalArgumentException.class, () -> OPTIONS.parseCelType("type<int>"));
  }

  @Test
  public void parseCelType_parenthesesSyntax_throwsException() {
    assertThrows(IllegalArgumentException.class, () -> OPTIONS.parseCelType("optional(int)"));
    assertThrows(IllegalArgumentException.class, () -> OPTIONS.parseCelType("optional_type(bool)"));
    assertThrows(IllegalArgumentException.class, () -> OPTIONS.parseCelType("list(string)"));
    assertThrows(IllegalArgumentException.class, () -> OPTIONS.parseCelType("map(string, int)"));
  }

  @Test
  public void parseVariables_nullOrEmpty_returnsEmptyMap() {
    assertThat(OPTIONS.parseVariables(null)).isEmpty();
    assertThat(OPTIONS.parseVariables(ImmutableList.of())).isEmpty();
  }

  @Test
  public void parseVariables_invalidSpec_throwsException() {
    ImmutableList<String> specs = ImmutableList.of("invalid_spec_without_colon");

    assertThrows(IllegalArgumentException.class, () -> OPTIONS.parseVariables(specs));
  }

  @Test
  public void parseVariables_unsupportedType_throwsException() {
    ImmutableList<String> specs = ImmutableList.of("x:unknown_type");

    IllegalArgumentException ex =
        assertThrows(IllegalArgumentException.class, () -> OPTIONS.parseVariables(specs));
    assertThat(ex).hasMessageThat().contains("Undefined type name: unknown_type");
  }
}
