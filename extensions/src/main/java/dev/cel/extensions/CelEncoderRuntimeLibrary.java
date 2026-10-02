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

import com.google.common.collect.ImmutableSet;
import com.google.errorprone.annotations.Immutable;
import dev.cel.common.values.CelByteString;
import dev.cel.runtime.CelFunctionBinding;
import dev.cel.runtime.CelLiteRuntimeBuilder;
import dev.cel.runtime.CelLiteRuntimeLibrary;
import java.util.Base64;
import java.util.Base64.Decoder;
import java.util.Base64.Encoder;
import java.util.Set;

/** Runtime implementation of CEL Encoder extension functions. */
@Immutable
public final class CelEncoderRuntimeLibrary implements CelLiteRuntimeLibrary {

  private static final Encoder BASE64_ENCODER = Base64.getEncoder();

  private static final Decoder BASE64_DECODER = Base64.getDecoder();

  /** Enumeration of runtime function bindings for the Encoder extension. */
  public enum Function {
    DECODE(
        "base64.decode",
        ImmutableSet.of(
            CelFunctionBinding.from(
                "base64_decode_string", String.class, CelEncoderRuntimeLibrary::decode))),
    ENCODE(
        "base64.encode",
        ImmutableSet.of(
            CelFunctionBinding.from(
                "base64_encode_bytes", CelByteString.class, CelEncoderRuntimeLibrary::encode)));

    private final String functionName;
    private final ImmutableSet<CelFunctionBinding> functionBindings;

    public String getFunction() {
      return functionName;
    }

    public ImmutableSet<CelFunctionBinding> getFunctionBindings() {
      return functionBindings;
    }

    Function(String functionName, ImmutableSet<CelFunctionBinding> bindings) {
      this.functionName = functionName;
      this.functionBindings = bindings;
    }
  }

  private static final CelEncoderRuntimeLibrary VERSION_0 =
      new CelEncoderRuntimeLibrary(ImmutableSet.copyOf(Function.values()));

  /** Returns the latest version of the 'encoders' runtime functions. */
  public static CelEncoderRuntimeLibrary encoders() {
    return VERSION_0;
  }

  /** Returns the specified version of the 'encoders' runtime functions. */
  public static CelEncoderRuntimeLibrary encoders(int version) {
    switch (version) {
      case 0:
      case Integer.MAX_VALUE:
        return VERSION_0;
      default:
        throw new IllegalArgumentException("Unsupported 'encoders' extension version " + version);
    }
  }

  /** Returns the 'encoders' runtime functions with only the specified functions. */
  public static CelEncoderRuntimeLibrary encoders(Function... functions) {
    return encoders(ImmutableSet.copyOf(functions));
  }

  /** Returns the 'encoders' runtime functions with only the specified functions. */
  public static CelEncoderRuntimeLibrary encoders(Set<Function> functions) {
    return new CelEncoderRuntimeLibrary(functions);
  }

  private final ImmutableSet<Function> functions;

  CelEncoderRuntimeLibrary(Set<Function> functions) {
    this.functions = ImmutableSet.copyOf(functions);
  }

  @Override
  public void setRuntimeOptions(CelLiteRuntimeBuilder runtimeBuilder) {
    runtimeBuilder.addFunctionBindings(newFunctionBindings());
  }

  /** Creates the {@link CelFunctionBinding}s for the configured encoder functions. */
  public ImmutableSet<CelFunctionBinding> newFunctionBindings() {
    ImmutableSet.Builder<CelFunctionBinding> builder = ImmutableSet.builder();
    for (Function function : functions) {
      if (!function.functionBindings.isEmpty()) {
        builder.addAll(
            CelFunctionBinding.fromOverloads(function.functionName, function.functionBindings));
      }
    }
    return builder.build();
  }

  private static CelByteString decode(String str) {
    return CelByteString.of(BASE64_DECODER.decode(str));
  }

  private static String encode(CelByteString bytes) {
    return BASE64_ENCODER.encodeToString(bytes.toByteArray());
  }
}
