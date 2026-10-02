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
import dev.cel.compiler.CelCompilerLibrary;
import dev.cel.runtime.CelRuntimeBuilder;
import dev.cel.runtime.CelRuntimeLibrary;
import java.util.Set;

/** Internal implementation of CEL regex extensions. */
@Immutable
public final class CelRegexExtensions
    implements CelCompilerLibrary, CelRuntimeLibrary, CelExtensionLibrary.FeatureSet {

  /** Denotes the regex extension function. */
  public enum Function {
    REPLACE(CelRegexCompilerLibrary.Function.REPLACE, CelRegexRuntimeLibrary.Function.REPLACE),
    EXTRACT(CelRegexCompilerLibrary.Function.EXTRACT, CelRegexRuntimeLibrary.Function.EXTRACT),
    EXTRACTALL(
        CelRegexCompilerLibrary.Function.EXTRACTALL, CelRegexRuntimeLibrary.Function.EXTRACTALL);

    private final CelRegexCompilerLibrary.Function compilerFunction;
    private final CelRegexRuntimeLibrary.Function runtimeFunction;

    String getFunction() {
      return compilerFunction.getFunction();
    }

    Function(
        CelRegexCompilerLibrary.Function compilerFunction,
        CelRegexRuntimeLibrary.Function runtimeFunction) {
      this.compilerFunction = compilerFunction;
      this.runtimeFunction = runtimeFunction;
    }
  }

  private static final class Library implements CelExtensionLibrary<CelRegexExtensions> {
    private final ImmutableSet<CelRegexExtensions> versions;

    Library() {
      versions =
          CelRegexCompilerLibrary.library().versions().stream()
              .map(CelRegexExtensions::new)
              .collect(toImmutableSet());
    }

    @Override
    public String name() {
      return CelRegexCompilerLibrary.library().name();
    }

    @Override
    public ImmutableSet<CelRegexExtensions> versions() {
      return versions;
    }
  }

  private static final Library LIBRARY = new Library();

  static CelExtensionLibrary<CelRegexExtensions> library() {
    return LIBRARY;
  }

  private final CelRegexCompilerLibrary compilerLibrary;
  private final CelRegexRuntimeLibrary regexRuntime;

  CelRegexExtensions() {
    this(CelRegexCompilerLibrary.regex());
  }

  CelRegexExtensions(Set<Function> functions) {
    this.compilerLibrary =
        new CelRegexCompilerLibrary(
            functions.stream().map(f -> f.compilerFunction).collect(toImmutableSet()));
    this.regexRuntime =
        new CelRegexRuntimeLibrary(
            functions.stream().map(f -> f.runtimeFunction).collect(toImmutableSet()));
  }

  private CelRegexExtensions(CelRegexCompilerLibrary compilerLibrary) {
    this.compilerLibrary = compilerLibrary;
    this.regexRuntime = CelRegexRuntimeLibrary.regex(compilerLibrary.version());
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
    runtimeBuilder.addFunctionBindings(regexRuntime.newFunctionBindings());
  }
}
