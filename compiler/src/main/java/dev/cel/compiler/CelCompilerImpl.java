// Copyright 2022 Google LLC
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

package dev.cel.compiler;

import static com.google.common.base.Preconditions.checkNotNull;

import dev.cel.expr.Decl;
import dev.cel.expr.Type;
import com.google.errorprone.annotations.CheckReturnValue;
import com.google.errorprone.annotations.Immutable;
import com.google.protobuf.DescriptorProtos.FileDescriptorSet;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import dev.cel.checker.CelChecker;
import dev.cel.checker.CelCheckerBuilder;
import dev.cel.checker.CelStandardDeclarations;
import dev.cel.checker.ProtoTypeMask;
import dev.cel.checker.TypeProvider;
import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.CelContainer;
import dev.cel.common.CelFunctionDecl;
import dev.cel.common.CelOptions;
import dev.cel.common.CelSource;
import dev.cel.common.CelValidationResult;
import dev.cel.common.CelVarDecl;
import dev.cel.common.annotations.Internal;
import dev.cel.common.ast.CelConstant;
import dev.cel.common.internal.EnvVisitable;
import dev.cel.common.internal.EnvVisitor;
import dev.cel.common.types.CelProtoTypes;
import dev.cel.common.types.CelType;
import dev.cel.common.types.CelTypeProvider;
import dev.cel.parser.CelMacro;
import dev.cel.parser.CelParser;
import dev.cel.parser.CelParserBuilder;
import dev.cel.parser.CelStandardMacro;
import java.util.Arrays;

/**
 * CelCompiler implementation which uses either the legacy or modernized CEL-Java stack to offer a
 * stream-lined expression parse/type-check experience, via a single {@code compile} method.
 *
 * <p>CEL Library Internals. Do Not Use. Consumers should use factories, such as {@link
 * CelCompilerFactory} instead to instantiate a compiler.
 */
@Immutable
@Internal
public final class CelCompilerImpl implements CelCompiler, EnvVisitable {

  private final CelParser parser;
  private final CelChecker checker;

  @Override
  public CelValidationResult parse(String expression, String description) {
    return parser.parse(expression, description);
  }

  @Override
  public CelValidationResult parse(CelSource source) {
    return parser.parse(source);
  }

  @Override
  public CelValidationResult check(CelAbstractSyntaxTree ast) {
    return checker.check(ast);
  }

  @Override
  public CelTypeProvider getTypeProvider() {
    return checker.getTypeProvider();
  }

  @Override
  public void accept(EnvVisitor envVisitor) {
    if (checker instanceof EnvVisitable) {
      ((EnvVisitable) checker).accept(envVisitor);
    }
    if (parser instanceof EnvVisitable) {
      ((EnvVisitable) parser).accept(envVisitor);
    }
  }

  @Override
  public CelCompilerBuilder toCompilerBuilder() {
    return newBuilder(toParserBuilder(), toCheckerBuilder());
  }

  @Override
  public CelCheckerBuilder toCheckerBuilder() {
    return checker.toCheckerBuilder();
  }

  @Override
  public CelParserBuilder toParserBuilder() {
    return parser.toParserBuilder();
  }

  /** Combines a prebuilt {@link CelParser} and {@link CelChecker} into {@link CelCompilerImpl}. */
  static CelCompilerImpl combine(CelParser parser, CelChecker checker) {
    return new CelCompilerImpl(parser, checker);
  }

  /**
   * Create a new builder for constructing a {@code CelCompiler} instance.
   *
   * <p>By default, {@link CelOptions#DEFAULT} are enabled, as is the CEL standard environment.
   */
  public static CelCompilerBuilder newBuilder(
      CelParserBuilder parserBuilder, CelCheckerBuilder checkerBuilder) {
    return new Builder(parserBuilder, checkerBuilder);
  }

  /** Builder for {@code CelCompilerImpl} */
  public static final class Builder implements CelCompilerBuilder {
    private final CelParserBuilder parserBuilder;
    private final CelCheckerBuilder checkerBuilder;

    @Override
    public CelCompilerBuilder setOptions(CelOptions options) {
      parserBuilder.setOptions(options);
      checkerBuilder.setOptions(options);
      return this;
    }

    @Override
    public CelCompilerBuilder setStandardMacros(CelStandardMacro... macros) {
      parserBuilder.setStandardMacros(macros);
      return this;
    }

    @Override
    public CelCompilerBuilder setStandardMacros(Iterable<CelStandardMacro> macros) {
      parserBuilder.setStandardMacros(macros);
      return this;
    }

    @Override
    public CelCompilerBuilder addMacros(CelMacro... macros) {
      checkNotNull(macros);
      return addMacros(Arrays.asList(macros));
    }

    @Override
    public CelCompilerBuilder addMacros(Iterable<CelMacro> macros) {
      checkNotNull(macros);
      parserBuilder.addMacros(macros);
      return this;
    }

    @Override
    public CelCompilerBuilder setContainer(CelContainer container) {
      checkerBuilder.setContainer(container);
      return this;
    }

    @Override
    public CelContainer container() {
      return checkerBuilder.container();
    }

    @Override
    public CelCompilerBuilder addVar(String name, Type type) {
      return addVar(name, CelProtoTypes.typeToCelType(type));
    }

    @Override
    public CelCompilerBuilder addVar(String name, CelType type) {
      return addVarDeclarations(CelVarDecl.newVarDeclaration(name, type));
    }

    @Override
    public CelCompilerBuilder addConstant(String name, CelConstant celConstant) {
      checkerBuilder.addConstant(name, celConstant);
      return this;
    }

    @Override
    public CelCompilerBuilder addDeclarations(Decl... declarations) {
      checkerBuilder.addDeclarations(declarations);
      return this;
    }

    @Override
    public CelCompilerBuilder addDeclarations(Iterable<Decl> declarations) {
      checkerBuilder.addDeclarations(declarations);
      return this;
    }

    @Override
    public CelCompilerBuilder addFunctionDeclarations(CelFunctionDecl... celFunctionDecls) {
      checkerBuilder.addFunctionDeclarations(celFunctionDecls);
      return this;
    }

    @Override
    public CelCompilerBuilder addFunctionDeclarations(Iterable<CelFunctionDecl> celFunctionDecls) {
      checkerBuilder.addFunctionDeclarations(celFunctionDecls);
      return this;
    }

    @Override
    public CelCompilerBuilder addVarDeclarations(CelVarDecl... celVarDecls) {
      checkerBuilder.addVarDeclarations(celVarDecls);
      return this;
    }

    @Override
    public CelCompilerBuilder addVarDeclarations(Iterable<CelVarDecl> celVarDecls) {
      checkerBuilder.addVarDeclarations(celVarDecls);
      return this;
    }

    @Override
    public CelCompilerBuilder addProtoTypeMasks(ProtoTypeMask... typeMasks) {
      checkerBuilder.addProtoTypeMasks(typeMasks);
      return this;
    }

    @Override
    public CelCompilerBuilder addProtoTypeMasks(Iterable<ProtoTypeMask> typeMasks) {
      checkerBuilder.addProtoTypeMasks(typeMasks);
      return this;
    }

    @Override
    public CelCompilerBuilder setResultType(CelType resultType) {
      checkNotNull(resultType);
      return setProtoResultType(CelProtoTypes.celTypeToType(resultType));
    }

    @Override
    public CelCompilerBuilder setProtoResultType(Type resultType) {
      checkerBuilder.setProtoResultType(resultType);
      return this;
    }

    @Override
    @Deprecated
    public CelCompilerBuilder setTypeProvider(TypeProvider typeProvider) {
      checkerBuilder.setTypeProvider(typeProvider);
      return this;
    }

    @Override
    public CelCompilerBuilder setTypeProvider(CelTypeProvider celTypeProvider) {
      checkerBuilder.setTypeProvider(celTypeProvider);
      return this;
    }

    @Override
    public CelCompilerBuilder addMessageTypes(Descriptor... descriptors) {
      checkerBuilder.addMessageTypes(descriptors);
      return this;
    }

    @Override
    public CelCompilerBuilder addMessageTypes(Iterable<Descriptor> descriptors) {
      checkerBuilder.addMessageTypes(descriptors);
      return this;
    }

    @Override
    public CelCompilerBuilder addFileTypes(FileDescriptor... fileDescriptors) {
      checkerBuilder.addFileTypes(fileDescriptors);
      return this;
    }

    @Override
    public CelCompilerBuilder addFileTypes(Iterable<FileDescriptor> fileDescriptors) {
      checkerBuilder.addFileTypes(fileDescriptors);
      return this;
    }

    @Override
    public CelCompilerBuilder addFileTypes(FileDescriptorSet fileDescriptorSet) {
      checkerBuilder.addFileTypes(fileDescriptorSet);
      return this;
    }

    @Override
    public CelCompilerBuilder setStandardEnvironmentEnabled(boolean value) {
      checkerBuilder.setStandardEnvironmentEnabled(value);
      return this;
    }

    @Override
    public CelCompilerBuilder setStandardDeclarations(
        CelStandardDeclarations standardDeclarations) {
      checkerBuilder.setStandardDeclarations(standardDeclarations);
      return this;
    }

    @Override
    public CelCompilerBuilder addLibraries(CelCompilerLibrary... libraries) {
      checkNotNull(libraries);
      return this.addLibraries(Arrays.asList(libraries));
    }

    @Override
    public CelCompilerBuilder addLibraries(Iterable<? extends CelCompilerLibrary> libraries) {
      checkNotNull(libraries);
      parserBuilder.addLibraries(libraries);
      checkerBuilder.addLibraries(libraries);
      return this;
    }

    @Override
    @CheckReturnValue
    public CelCompilerImpl build() {
      return new CelCompilerImpl(parserBuilder.build(), checkerBuilder.build());
    }

    private Builder(CelParserBuilder parserBuilder, CelCheckerBuilder checkerBuilder) {
      this.parserBuilder = parserBuilder;
      this.checkerBuilder = checkerBuilder;
    }
  }

  private CelCompilerImpl(CelParser parser, CelChecker checker) {
    this.parser = parser;
    this.checker = checker;
  }
}
