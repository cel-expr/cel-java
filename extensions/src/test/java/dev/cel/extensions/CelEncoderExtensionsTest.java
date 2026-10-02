// Copyright 2023 Google LLC
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

package dev.cel.extensions;

import static com.google.common.truth.Truth.assertThat;
import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.protobuf.ByteString;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import dev.cel.bundle.Cel;
import dev.cel.bundle.CelFactory;
import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.CelFunctionDecl;
import dev.cel.common.CelOptions;
import dev.cel.common.CelValidationException;
import dev.cel.common.types.SimpleType;
import dev.cel.common.values.CelByteString;
import dev.cel.compiler.CelCompiler;
import dev.cel.compiler.CelCompilerFactory;
import dev.cel.runtime.CelEvaluationException;
import dev.cel.runtime.CelLiteRuntime;
import dev.cel.runtime.CelLiteRuntimeFactory;
import dev.cel.runtime.CelRuntime;
import dev.cel.runtime.CelRuntimeFactory;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(TestParameterInjector.class)
public class CelEncoderExtensionsTest extends CelExtensionTestBase {
  private static final CelOptions CEL_OPTIONS =
      CelOptions.current().enableHeterogeneousNumericComparisons(true).build();

  @Override
  protected Cel newCelEnv() {
    return runtimeFlavor
        .builder()
        .setOptions(CEL_OPTIONS)
        .addCompilerLibraries(CelExtensions.encoders(CEL_OPTIONS))
        .addRuntimeLibraries(CelExtensions.encoders(CEL_OPTIONS))
        .addVar("stringVar", SimpleType.STRING)
        .build();
  }

  @Test
  public void library() {
    CelExtensionLibrary<?> library =
        CelExtensions.getExtensionLibrary("encoders", CelOptions.DEFAULT);
    assertThat(library.name()).isEqualTo("encoders");
    assertThat(library.latest().version()).isEqualTo(0);
    assertThat(library.version(0).functions().stream().map(CelFunctionDecl::name))
        .containsExactly("base64.decode", "base64.encode");
    assertThat(library.version(0).macros()).isEmpty();
  }

  @Test
  public void encode_success() throws Exception {
    String encodedBytes = (String) eval("base64.encode(b'hello')");

    assertThat(encodedBytes).isEqualTo("aGVsbG8=");
  }

  @Test
  public void decode_success() throws Exception {
    CelByteString decodedBytes = (CelByteString) eval("base64.decode('aGVsbG8=')");

    assertThat(decodedBytes.size()).isEqualTo(5);
    assertThat(new String(decodedBytes.toByteArray(), ISO_8859_1)).isEqualTo("hello");
  }

  @Test
  public void decode_withoutPadding_success() throws Exception {
    CelByteString decodedBytes = (CelByteString) eval("base64.decode('aGVsbG8')");

    assertThat(decodedBytes.size()).isEqualTo(5);
    assertThat(new String(decodedBytes.toByteArray(), ISO_8859_1)).isEqualTo("hello");
  }

  @Test
  public void roundTrip_success() throws Exception {
    String encodedString = (String) eval("base64.encode(b'Hello World!')");
    CelByteString decodedBytes =
        (CelByteString)
            eval("base64.decode(stringVar)", ImmutableMap.of("stringVar", encodedString));

    assertThat(new String(decodedBytes.toByteArray(), ISO_8859_1)).isEqualTo("Hello World!");
  }

  @Test
  public void encode_invalidParam_throwsCompilationException() {
    Assume.assumeFalse(isParseOnly);
    CelValidationException e =
        assertThrows(
            CelValidationException.class, () -> cel.compile("base64.encode('hello')").getAst());

    assertThat(e).hasMessageThat().contains("found no matching overload for 'base64.encode'");
  }

  @Test
  public void decode_invalidParam_throwsCompilationException() {
    Assume.assumeFalse(isParseOnly);
    CelValidationException e =
        assertThrows(
            CelValidationException.class, () -> cel.compile("base64.decode(b'aGVsbG8=')").getAst());

    assertThat(e).hasMessageThat().contains("found no matching overload for 'base64.decode'");
  }

  @Test
  public void decode_malformedBase64Char_throwsEvaluationException() throws Exception {
    CelEvaluationException e =
        assertThrows(CelEvaluationException.class, () -> eval("base64.decode('z!')"));

    assertThat(e).hasMessageThat().contains("failed with arg(s) 'z!'");
    assertThat(e).hasCauseThat().hasMessageThat().contains("Illegal base64 character");
  }

  @Test
  public void separateLibraryAndRuntime_allFunctions_success() throws Exception {
    CelCompiler celCompiler =
        CelCompilerFactory.standardCelCompilerBuilder()
            .addLibraries(CelEncoderCompilerLibrary.encoders())
            .build();
    CelLiteRuntime celLiteRuntime =
        CelLiteRuntimeFactory.newLiteRuntimeBuilder()
            .addLibraries(CelEncoderRuntimeLibrary.encoders())
            .build();

    CelAbstractSyntaxTree ast =
        celCompiler.compile("base64.decode(base64.encode(b'hello'))").getAst();
    CelByteString result = (CelByteString) celLiteRuntime.createProgram(ast).eval();

    assertThat(result).isEqualTo(CelByteString.copyFromUtf8("hello"));
  }

  @Test
  public void separateLibraryAndRuntime_versioned_success() throws Exception {
    CelCompiler celCompiler =
        CelCompilerFactory.standardCelCompilerBuilder()
            .addLibraries(CelEncoderCompilerLibrary.encoders(0))
            .build();
    CelRuntime celRuntime =
        CelRuntimeFactory.standardCelRuntimeBuilder()
            .addFunctionBindings(CelEncoderRuntimeLibrary.encoders(0).newFunctionBindings())
            .build();

    CelAbstractSyntaxTree ast = celCompiler.compile("base64.encode(b'hello')").getAst();
    String result = (String) celRuntime.createProgram(ast).eval();

    assertThat(result).isEqualTo("aGVsbG8=");
  }

  @Test
  public void separateLibraryAndRuntime_subsetOfFunctions_success() throws Exception {
    CelCompiler celCompiler =
        CelCompilerFactory.standardCelCompilerBuilder()
            .addLibraries(
                CelEncoderCompilerLibrary.encoders(CelEncoderCompilerLibrary.Function.ENCODE))
            .build();
    CelRuntime celRuntime =
        CelRuntimeFactory.standardCelRuntimeBuilder()
            .addFunctionBindings(
                CelEncoderRuntimeLibrary.encoders(CelEncoderRuntimeLibrary.Function.ENCODE)
                    .newFunctionBindings())
            .build();

    CelAbstractSyntaxTree ast = celCompiler.compile("base64.encode(b'hello')").getAst();
    String result = (String) celRuntime.createProgram(ast).eval();

    assertThat(result).isEqualTo("aGVsbG8=");
    assertThrows(
        CelValidationException.class,
        () -> celCompiler.compile("base64.decode('aGVsbG8=')").getAst());
  }

  @Test
  public void separateLibraryAndRuntime_setOfFunctions_success() throws Exception {
    CelEncoderCompilerLibrary compilerLibrary =
        CelEncoderCompilerLibrary.encoders(
            ImmutableSet.of(CelEncoderCompilerLibrary.Function.ENCODE));
    assertThat(compilerLibrary.version()).isEqualTo(-1);

    CelCompiler celCompiler =
        CelCompilerFactory.standardCelCompilerBuilder()
            .addLibraries(compilerLibrary)
            .build();
    CelRuntime celRuntime =
        CelRuntimeFactory.standardCelRuntimeBuilder()
            .addFunctionBindings(
                CelEncoderRuntimeLibrary.encoders(
                        ImmutableSet.of(CelEncoderRuntimeLibrary.Function.ENCODE))
                    .newFunctionBindings())
            .build();

    CelAbstractSyntaxTree ast = celCompiler.compile("base64.encode(b'hello')").getAst();
    String result = (String) celRuntime.createProgram(ast).eval();

    assertThat(result).isEqualTo("aGVsbG8=");
  }

  @Test
  public void separateLibraryAndRuntime_unsupportedVersion_throws() {
    assertThrows(IllegalArgumentException.class, () -> CelEncoderRuntimeLibrary.encoders(99));
    assertThrows(IllegalArgumentException.class, () -> CelEncoderCompilerLibrary.encoders(99));
  }

  @Test
  public void separateLibraryAndRuntime_maxVersion_success() {
    assertThat(CelEncoderCompilerLibrary.encoders(Integer.MAX_VALUE).version()).isEqualTo(0);
    assertThat(CelEncoderRuntimeLibrary.encoders(Integer.MAX_VALUE)).isNotNull();
  }

  @Test
  public void compilerLibrary() {
    CelExtensionLibrary<CelEncoderCompilerLibrary> library = CelEncoderCompilerLibrary.library();
    assertThat(library.name()).isEqualTo("encoders");
    assertThat(library.versions()).isNotEmpty();
    assertThat(library.latest().version()).isEqualTo(0);
    assertThat(CelEncoderCompilerLibrary.encoders().version()).isEqualTo(0);
    assertThat(CelEncoderCompilerLibrary.encoders(0).version()).isEqualTo(0);
    assertThat(
            CelEncoderCompilerLibrary.encoders().functions().stream().map(CelFunctionDecl::name))
        .containsExactly("base64.decode", "base64.encode");
  }

  @Test
  public void runtimeLibrary_functionEnum() {
    assertThat(CelEncoderRuntimeLibrary.Function.DECODE.getFunction()).isEqualTo("base64.decode");
    assertThat(CelEncoderRuntimeLibrary.Function.DECODE.getFunctionBindings()).isNotEmpty();
    assertThat(CelEncoderRuntimeLibrary.Function.ENCODE.getFunction()).isEqualTo("base64.encode");
    assertThat(CelEncoderRuntimeLibrary.Function.ENCODE.getFunctionBindings()).isNotEmpty();
  }

  @Test
  public void compilerLibrary_functionEnum() {
    assertThat(CelEncoderCompilerLibrary.Function.DECODE.getFunction()).isEqualTo("base64.decode");
    assertThat(CelEncoderCompilerLibrary.Function.DECODE.getFunctionDecl().name())
        .isEqualTo("base64.decode");
    assertThat(CelEncoderCompilerLibrary.Function.ENCODE.getFunction()).isEqualTo("base64.encode");
    assertThat(CelEncoderCompilerLibrary.Function.ENCODE.getFunctionDecl().name())
        .isEqualTo("base64.encode");
  }

  @Test
  public void separateLibraryAndRuntime_emptyFunctions() {
    assertThat(CelEncoderCompilerLibrary.encoders(ImmutableSet.of()).functions()).isEmpty();
    assertThat(CelEncoderRuntimeLibrary.encoders(ImmutableSet.of()).newFunctionBindings()).isEmpty();
  }

  @Test
  @SuppressWarnings("deprecation") // Test only - verifying deprecated no-arg factory method
  public void encoders_deprecatedNoArg() throws Exception {
    Cel cel =
        CelFactory.standardCelBuilder()
            .addCompilerLibraries(CelExtensions.encoders())
            .addRuntimeLibraries(CelExtensions.encoders())
            .build();
    assertThat(cel.createProgram(cel.compile("base64.encode(b'hello')").getAst()).eval())
        .isEqualTo("aGVsbG8=");
  }

  @Test
  @SuppressWarnings("deprecation") // Test only - verifying deprecated evaluateCanonicalTypesToNativeValues option
  public void encoders_evaluateCanonicalTypesToNativeValuesDisabled_producesAndConsumesByteString()
      throws Exception {
    CelOptions options = CelOptions.current().evaluateCanonicalTypesToNativeValues(false).build();
    Cel cel =
        CelFactory.standardCelBuilder()
            .setOptions(options)
            .addCompilerLibraries(CelExtensions.encoders(options))
            .addRuntimeLibraries(CelExtensions.encoders(options))
            .build();

    ByteString decodedBytes =
        (ByteString) cel.createProgram(cel.compile("base64.decode('aGVsbG8=')").getAst()).eval();
    assertThat(decodedBytes).isEqualTo(ByteString.copyFromUtf8("hello"));

    String encodedString =
        (String) cel.createProgram(cel.compile("base64.encode(b'hello')").getAst()).eval();
    assertThat(encodedString).isEqualTo("aGVsbG8=");
  }
}
