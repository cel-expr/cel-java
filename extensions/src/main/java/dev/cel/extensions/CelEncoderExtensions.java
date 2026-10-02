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

import static com.google.common.collect.ImmutableSet.toImmutableSet;

import com.google.common.collect.ImmutableSet;
import com.google.errorprone.annotations.Immutable;
import com.google.protobuf.ByteString;
import dev.cel.checker.CelCheckerBuilder;
import dev.cel.common.CelFunctionDecl;
import dev.cel.common.CelOptions;
import dev.cel.compiler.CelCompilerLibrary;
import dev.cel.runtime.CelFunctionBinding;
import dev.cel.runtime.CelRuntimeBuilder;
import dev.cel.runtime.CelRuntimeLibrary;
import java.util.Base64;
import java.util.Base64.Decoder;
import java.util.Base64.Encoder;

/** Internal implementation of Encoder Extensions. */
@Immutable
public final class CelEncoderExtensions
    implements CelCompilerLibrary, CelRuntimeLibrary, CelExtensionLibrary.FeatureSet {

  private static final Encoder BASE64_ENCODER = Base64.getEncoder();

  private static final Decoder BASE64_DECODER = Base64.getDecoder();

  /** Denotes the encoder extension function. */
  enum Function {
    DECODE(
        CelEncoderCompilerLibrary.Function.DECODE,
        CelFunctionBinding.from(
            "base64_decode_string",
            String.class,
            str -> ByteString.copyFrom(BASE64_DECODER.decode(str)))),
    ENCODE(
        CelEncoderCompilerLibrary.Function.ENCODE,
        CelFunctionBinding.from(
            "base64_encode_bytes",
            ByteString.class,
            bytes -> BASE64_ENCODER.encodeToString(bytes.toByteArray())));

    private final CelEncoderCompilerLibrary.Function compilerFunction;
    private final CelFunctionBinding protoBytesFunctionBinding;

    String getFunction() {
      return compilerFunction.getFunction();
    }

    Function(
        CelEncoderCompilerLibrary.Function compilerFunction,
        CelFunctionBinding protoBytesFunctionBinding) {
      this.compilerFunction = compilerFunction;
      this.protoBytesFunctionBinding = protoBytesFunctionBinding;
    }
  }

  private static final class Library implements CelExtensionLibrary<CelEncoderExtensions> {
    private final ImmutableSet<CelEncoderExtensions> versions;

    private Library(CelOptions celOptions) {
      versions =
          CelEncoderCompilerLibrary.library().versions().stream()
              .map(compilerLibrary -> new CelEncoderExtensions(celOptions, compilerLibrary))
              .collect(toImmutableSet());
    }

    @Override
    public String name() {
      return CelEncoderCompilerLibrary.library().name();
    }

    @Override
    public ImmutableSet<CelEncoderExtensions> versions() {
      return versions;
    }
  }

  static CelExtensionLibrary<CelEncoderExtensions> library(CelOptions celOptions) {
    return new Library(celOptions);
  }

  private final CelEncoderCompilerLibrary compilerLibrary;
  private final CelEncoderRuntimeLibrary encoderRuntime;
  private final ImmutableSet<Function> functions;
  private final CelOptions celOptions;

  CelEncoderExtensions(CelOptions celOptions) {
    this(celOptions, CelEncoderCompilerLibrary.encoders());
  }

  private CelEncoderExtensions(CelOptions celOptions, CelEncoderCompilerLibrary compilerLibrary) {
    this.compilerLibrary = compilerLibrary;
    this.encoderRuntime = CelEncoderRuntimeLibrary.encoders(compilerLibrary.version());
    this.celOptions = celOptions;
    this.functions = ImmutableSet.copyOf(Function.values());
  }

  @Override
  public int version() {
    return compilerLibrary.version();
  }

  @Override
  public ImmutableSet<CelFunctionDecl> functions() {
    return compilerLibrary.functions();
  }

  @Override
  public void setCheckerOptions(CelCheckerBuilder checkerBuilder) {
    compilerLibrary.setCheckerOptions(checkerBuilder);
  }

  @Override
  public void setRuntimeOptions(CelRuntimeBuilder runtimeBuilder) {
    if (celOptions.evaluateCanonicalTypesToNativeValues()) {
      runtimeBuilder.addFunctionBindings(encoderRuntime.newFunctionBindings());
    } else {
      functions.forEach(
          function ->
              runtimeBuilder.addFunctionBindings(
                  CelFunctionBinding.fromOverloads(
                      function.getFunction(), function.protoBytesFunctionBinding)));
    }
  }
}
