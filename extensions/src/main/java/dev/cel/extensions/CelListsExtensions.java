// Copyright 2024 Google LLC
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

/** Internal implementation of CEL lists extensions. */
@Immutable
public final class CelListsExtensions
    implements CelCompilerLibrary, CelInternalRuntimeLibrary, CelExtensionLibrary.FeatureSet {

  /** Supported functions for Lists extension library. */
  public enum Function {
    SLICE(CelListsCompilerLibrary.Function.SLICE, CelListsRuntimeLibrary.Function.SLICE),
    FLATTEN(CelListsCompilerLibrary.Function.FLATTEN, CelListsRuntimeLibrary.Function.FLATTEN),
    RANGE(CelListsCompilerLibrary.Function.RANGE, CelListsRuntimeLibrary.Function.RANGE),
    DISTINCT(CelListsCompilerLibrary.Function.DISTINCT, CelListsRuntimeLibrary.Function.DISTINCT),
    REVERSE(CelListsCompilerLibrary.Function.REVERSE, CelListsRuntimeLibrary.Function.REVERSE),
    SORT(CelListsCompilerLibrary.Function.SORT, CelListsRuntimeLibrary.Function.SORT),
    SORT_BY(CelListsCompilerLibrary.Function.SORT_BY, CelListsRuntimeLibrary.Function.SORT_BY);

    private final CelListsCompilerLibrary.Function compilerFunction;
    private final CelListsRuntimeLibrary.Function runtimeFunction;

    String getFunction() {
      return compilerFunction.getFunction();
    }

    Function(
        CelListsCompilerLibrary.Function compilerFunction,
        CelListsRuntimeLibrary.Function runtimeFunction) {
      this.compilerFunction = compilerFunction;
      this.runtimeFunction = runtimeFunction;
    }
  }

  private static ImmutableSet<Function> getFunctionsForVersion(int version) {
    switch (version) {
      case 0:
        return ImmutableSet.of(Function.SLICE);
      case 1:
        return ImmutableSet.of(Function.SLICE, Function.FLATTEN);
      case 2:
      case Integer.MAX_VALUE:
        return ImmutableSet.copyOf(Function.values());
      default:
        throw new IllegalArgumentException("Unsupported 'lists' extension version " + version);
    }
  }

  private static final class Library implements CelExtensionLibrary<CelListsExtensions> {
    private final ImmutableSet<CelListsExtensions> versions;

    Library() {
      versions =
          CelListsCompilerLibrary.library().versions().stream()
              .map(CelListsExtensions::new)
              .collect(toImmutableSet());
    }

    @Override
    public String name() {
      return CelListsCompilerLibrary.library().name();
    }

    @Override
    public ImmutableSet<CelListsExtensions> versions() {
      return versions;
    }
  }

  private static final Library LIBRARY = new Library();

  static CelExtensionLibrary<CelListsExtensions> library() {
    return LIBRARY;
  }

  private final CelListsCompilerLibrary compilerLibrary;
  private final ImmutableSet<Function> functions;

  CelListsExtensions() {
    this(CelListsCompilerLibrary.lists());
  }

  CelListsExtensions(Set<Function> functions) {
    this.compilerLibrary =
        new CelListsCompilerLibrary(
            functions.stream().map(f -> f.compilerFunction).collect(toImmutableSet()));
    this.functions = ImmutableSet.copyOf(functions);
  }

  private CelListsExtensions(CelListsCompilerLibrary compilerLibrary) {
    this.compilerLibrary = compilerLibrary;
    this.functions = getFunctionsForVersion(compilerLibrary.version());
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
    CelListsRuntimeLibrary listsRuntime =
        new CelListsRuntimeLibrary(
            runtimeEquality,
            functions.stream().map(f -> f.runtimeFunction).collect(toImmutableSet()));
    runtimeBuilder.addFunctionBindings(listsRuntime.newFunctionBindings());
  }
}
