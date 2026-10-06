// Copyright 2025 Google LLC
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
import dev.cel.common.CelOptions;
import dev.cel.compiler.CelCompilerLibrary;
import dev.cel.parser.CelMacro;
import dev.cel.parser.CelParserBuilder;
import dev.cel.runtime.CelInternalRuntimeLibrary;
import dev.cel.runtime.CelRuntimeBuilder;
import dev.cel.runtime.RuntimeEquality;
import java.util.Set;

/** Internal implementation of CEL two variable comprehensions extensions. */
@Immutable
public final class CelComprehensionsExtensions
    implements CelCompilerLibrary, CelInternalRuntimeLibrary, CelExtensionLibrary.FeatureSet {

  /** Enumeration of functions for Comprehensions extension. */
  public enum Function {
    MAP_INSERT(
        CelComprehensionsCompilerLibrary.Function.MAP_INSERT,
        CelComprehensionsRuntimeLibrary.Function.MAP_INSERT);

    private final CelComprehensionsCompilerLibrary.Function compilerFunction;
    private final CelComprehensionsRuntimeLibrary.Function runtimeFunction;

    public CelFunctionDecl functionDecl() {
      return compilerFunction.functionDecl();
    }

    public CelFunctionDecl getFunctionDecl() {
      return compilerFunction.getFunctionDecl();
    }

    public String getFunction() {
      return compilerFunction.getFunction();
    }

    Function(
        CelComprehensionsCompilerLibrary.Function compilerFunction,
        CelComprehensionsRuntimeLibrary.Function runtimeFunction) {
      this.compilerFunction = compilerFunction;
      this.runtimeFunction = runtimeFunction;
    }
  }

  private static final class Library implements CelExtensionLibrary<CelComprehensionsExtensions> {
    private final ImmutableSet<CelComprehensionsExtensions> versions;

    Library() {
      versions =
          CelComprehensionsCompilerLibrary.library().versions().stream()
              .map(CelComprehensionsExtensions::new)
              .collect(toImmutableSet());
    }

    @Override
    public String name() {
      return CelComprehensionsCompilerLibrary.library().name();
    }

    @Override
    public ImmutableSet<CelComprehensionsExtensions> versions() {
      return versions;
    }
  }

  private static final Library LIBRARY = new Library();

  static CelExtensionLibrary<CelComprehensionsExtensions> library() {
    return LIBRARY;
  }

  private final CelComprehensionsCompilerLibrary compilerLibrary;
  private final CelComprehensionsRuntimeLibrary runtimeLibrary;

  CelComprehensionsExtensions() {
    this(CelComprehensionsCompilerLibrary.comprehensions());
  }

  CelComprehensionsExtensions(Set<Function> functions) {
    this.compilerLibrary =
        new CelComprehensionsCompilerLibrary(
            functions.stream().map(f -> f.compilerFunction).collect(toImmutableSet()));
    this.runtimeLibrary =
        new CelComprehensionsRuntimeLibrary(
            functions.stream().map(f -> f.runtimeFunction).collect(toImmutableSet()));
  }

  private CelComprehensionsExtensions(CelComprehensionsCompilerLibrary compilerLibrary) {
    this.compilerLibrary = compilerLibrary;
    this.runtimeLibrary = CelComprehensionsRuntimeLibrary.comprehensions(compilerLibrary.version());
  }

  @Override
  public int version() {
    return compilerLibrary.version();
  }

  @Override
  public ImmutableSet<CelMacro> macros() {
    return compilerLibrary.macros();
  }

  @Override
  public void setParserOptions(CelParserBuilder parserBuilder) {
    compilerLibrary.setParserOptions(parserBuilder);
  }

  @Override
  public void setCheckerOptions(CelCheckerBuilder checkerBuilder) {
    compilerLibrary.setCheckerOptions(checkerBuilder);
  }

  @Override
  public void setRuntimeOptions(CelRuntimeBuilder runtimeBuilder) {
    throw new UnsupportedOperationException("Unsupported");
  }

  @Override
  public void setRuntimeOptions(
      CelRuntimeBuilder runtimeBuilder, RuntimeEquality runtimeEquality, CelOptions celOptions) {
    runtimeBuilder.addFunctionBindings(runtimeLibrary.newFunctionBindings(runtimeEquality));
  }
}
