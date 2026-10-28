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
import dev.cel.common.CelOptions;
import dev.cel.common.CelVarDecl;
import dev.cel.compiler.CelCompilerLibrary;
import dev.cel.parser.CelMacro;
import dev.cel.parser.CelParserBuilder;
import dev.cel.runtime.CelInternalRuntimeLibrary;
import dev.cel.runtime.CelRuntimeBuilder;
import dev.cel.runtime.RuntimeEquality;
import java.util.Set;

/** Internal implementation of CEL optional values. */
@Immutable
public final class CelOptionalLibrary
    implements CelCompilerLibrary, CelInternalRuntimeLibrary, CelExtensionLibrary.FeatureSet {

  /** Enumerations of function names used for supporting optionals. */
  public enum Function {
    VALUE(CelOptionalCompilerLibrary.Function.VALUE, CelOptionalRuntimeLibrary.Function.VALUE),
    HAS_VALUE(
        CelOptionalCompilerLibrary.Function.HAS_VALUE,
        CelOptionalRuntimeLibrary.Function.HAS_VALUE),
    OPTIONAL_NONE(
        CelOptionalCompilerLibrary.Function.OPTIONAL_NONE,
        CelOptionalRuntimeLibrary.Function.OPTIONAL_NONE),
    OPTIONAL_OF(
        CelOptionalCompilerLibrary.Function.OPTIONAL_OF,
        CelOptionalRuntimeLibrary.Function.OPTIONAL_OF),
    OPTIONAL_UNWRAP(
        CelOptionalCompilerLibrary.Function.OPTIONAL_UNWRAP,
        CelOptionalRuntimeLibrary.Function.OPTIONAL_UNWRAP),
    OPTIONAL_OF_NON_ZERO_VALUE(
        CelOptionalCompilerLibrary.Function.OPTIONAL_OF_NON_ZERO_VALUE,
        CelOptionalRuntimeLibrary.Function.OPTIONAL_OF_NON_ZERO_VALUE),
    OR(CelOptionalCompilerLibrary.Function.OR, CelOptionalRuntimeLibrary.Function.OR),
    OR_VALUE(
        CelOptionalCompilerLibrary.Function.OR_VALUE, CelOptionalRuntimeLibrary.Function.OR_VALUE),
    FIRST(CelOptionalCompilerLibrary.Function.FIRST, CelOptionalRuntimeLibrary.Function.FIRST),
    LAST(CelOptionalCompilerLibrary.Function.LAST, CelOptionalRuntimeLibrary.Function.LAST);

    private final CelOptionalCompilerLibrary.Function compilerFunction;
    private final CelOptionalRuntimeLibrary.Function runtimeFunction;

    public String getFunction() {
      return compilerFunction.getFunction();
    }

    Function(
        CelOptionalCompilerLibrary.Function compilerFunction,
        CelOptionalRuntimeLibrary.Function runtimeFunction) {
      this.compilerFunction = compilerFunction;
      this.runtimeFunction = runtimeFunction;
    }
  }

  /** Declarations for the optional extension library. */
  public enum OptionalDeclaration implements CelFunctionDecl.Declarer {
    OPTIONAL_OF(CelOptionalCompilerLibrary.OptionalDeclaration.OPTIONAL_OF),
    OPTIONAL_OF_NON_ZERO_VALUE(
        CelOptionalCompilerLibrary.OptionalDeclaration.OPTIONAL_OF_NON_ZERO_VALUE),
    OPTIONAL_NONE(CelOptionalCompilerLibrary.OptionalDeclaration.OPTIONAL_NONE),
    OPTIONAL_VALUE(CelOptionalCompilerLibrary.OptionalDeclaration.OPTIONAL_VALUE),
    OPTIONAL_HAS_VALUE(CelOptionalCompilerLibrary.OptionalDeclaration.OPTIONAL_HAS_VALUE),
    OPTIONAL_UNWRAP(CelOptionalCompilerLibrary.OptionalDeclaration.OPTIONAL_UNWRAP),
    OPTIONAL_OR(CelOptionalCompilerLibrary.OptionalDeclaration.OPTIONAL_OR),
    OPTIONAL_OR_VALUE(CelOptionalCompilerLibrary.OptionalDeclaration.OPTIONAL_OR_VALUE),
    OPTIONAL_SELECT(CelOptionalCompilerLibrary.OptionalDeclaration.OPTIONAL_SELECT),
    OPTIONAL_INDEX(CelOptionalCompilerLibrary.OptionalDeclaration.OPTIONAL_INDEX),
    OPTIONAL_INDEX_OPERAND(CelOptionalCompilerLibrary.OptionalDeclaration.OPTIONAL_INDEX_OPERAND);

    private final CelOptionalCompilerLibrary.OptionalDeclaration declaration;

    OptionalDeclaration(CelOptionalCompilerLibrary.OptionalDeclaration declaration) {
      this.declaration = declaration;
    }

    @Override
    public CelFunctionDecl functionDecl() {
      return declaration.functionDecl();
    }
  }

  private static final class Library implements CelExtensionLibrary<CelOptionalLibrary> {
    private final ImmutableSet<CelOptionalLibrary> versions;

    Library() {
      versions =
          CelOptionalCompilerLibrary.library().versions().stream()
              .map(CelOptionalLibrary::new)
              .collect(toImmutableSet());
    }

    @Override
    public String name() {
      return CelOptionalCompilerLibrary.library().name();
    }

    @Override
    public ImmutableSet<CelOptionalLibrary> versions() {
      return versions;
    }
  }

  private static final Library LIBRARY = new Library();

  static CelExtensionLibrary<CelOptionalLibrary> library() {
    return LIBRARY;
  }

  // TODO migrate from this constant to the CelExtensions.optional()
  public static final CelOptionalLibrary INSTANCE = CelOptionalLibrary.library().latest();

  private final CelOptionalCompilerLibrary compilerLibrary;
  private final CelOptionalRuntimeLibrary runtimeLibrary;

  CelOptionalLibrary() {
    this(CelOptionalCompilerLibrary.optional());
  }

  CelOptionalLibrary(Set<Function> functions) {
    this.compilerLibrary =
        new CelOptionalCompilerLibrary(
            functions.stream().map(f -> f.compilerFunction).collect(toImmutableSet()));
    this.runtimeLibrary =
        new CelOptionalRuntimeLibrary(
            functions.stream().map(f -> f.runtimeFunction).collect(toImmutableSet()));
  }

  private CelOptionalLibrary(CelOptionalCompilerLibrary compilerLibrary) {
    this.compilerLibrary = compilerLibrary;
    this.runtimeLibrary = CelOptionalRuntimeLibrary.optional(compilerLibrary.version());
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
  public ImmutableSet<CelVarDecl> variables() {
    return compilerLibrary.variables();
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
