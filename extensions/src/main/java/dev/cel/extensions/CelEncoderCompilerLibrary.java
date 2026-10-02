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
import dev.cel.checker.CelCheckerBuilder;
import dev.cel.common.CelFunctionDecl;
import dev.cel.common.CelOverloadDecl;
import dev.cel.common.types.SimpleType;
import dev.cel.compiler.CelCompilerLibrary;
import java.util.Set;

/** Internal implementation of CEL Encoder compile-time extensions. */
@Immutable
public final class CelEncoderCompilerLibrary
    implements CelCompilerLibrary, CelExtensionLibrary.FeatureSet {

  /** Enumeration of functions for Encoder compile-time extension. */
  public enum Function {
    DECODE(
        CelFunctionDecl.newFunctionDeclaration(
            "base64.decode",
            CelOverloadDecl.newGlobalOverload(
                "base64_decode_string", SimpleType.BYTES, SimpleType.STRING))),
    ENCODE(
        CelFunctionDecl.newFunctionDeclaration(
            "base64.encode",
            CelOverloadDecl.newGlobalOverload(
                "base64_encode_bytes", SimpleType.STRING, SimpleType.BYTES)));

    private final CelFunctionDecl functionDecl;

    public String getFunction() {
      return functionDecl.name();
    }

    public CelFunctionDecl getFunctionDecl() {
      return functionDecl;
    }

    Function(CelFunctionDecl functionDecl) {
      this.functionDecl = functionDecl;
    }
  }

  private static final class Library implements CelExtensionLibrary<CelEncoderCompilerLibrary> {
    private final CelEncoderCompilerLibrary version0;

    Library() {
      version0 = new CelEncoderCompilerLibrary(0, ImmutableSet.copyOf(Function.values()));
    }

    @Override
    public String name() {
      return "encoders";
    }

    @Override
    public ImmutableSet<CelEncoderCompilerLibrary> versions() {
      return ImmutableSet.of(version0);
    }
  }

  private static final Library LIBRARY = new Library();

  public static CelExtensionLibrary<CelEncoderCompilerLibrary> library() {
    return LIBRARY;
  }

  /** Returns the latest version of the 'encoders' compiler extension. */
  public static CelEncoderCompilerLibrary encoders() {
    return library().latest();
  }

  /** Returns the specified version of the 'encoders' compiler extension. */
  public static CelEncoderCompilerLibrary encoders(int version) {
    return library().version(version);
  }

  /** Returns the 'encoders' compiler extension with only the specified functions. */
  public static CelEncoderCompilerLibrary encoders(Function... functions) {
    return encoders(ImmutableSet.copyOf(functions));
  }

  /** Returns the 'encoders' compiler extension with only the specified functions. */
  public static CelEncoderCompilerLibrary encoders(Set<Function> functions) {
    return new CelEncoderCompilerLibrary(functions);
  }

  private final ImmutableSet<Function> functions;
  private final int version;

  CelEncoderCompilerLibrary(Set<Function> functions) {
    this(-1, functions);
  }

  private CelEncoderCompilerLibrary(int version, Set<Function> functions) {
    this.version = version;
    this.functions = ImmutableSet.copyOf(functions);
  }

  @Override
  public int version() {
    return version;
  }

  @Override
  public ImmutableSet<CelFunctionDecl> functions() {
    return functions.stream().map(Function::getFunctionDecl).collect(toImmutableSet());
  }

  @Override
  public void setCheckerOptions(CelCheckerBuilder checkerBuilder) {
    functions.forEach(function -> checkerBuilder.addFunctionDeclarations(function.functionDecl));
  }
}
