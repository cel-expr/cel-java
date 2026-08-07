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

package dev.cel.bundle;

import static com.google.common.base.Preconditions.checkNotNull;

import dev.cel.expr.Decl;
import dev.cel.expr.Type;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableSet;
import com.google.errorprone.annotations.Immutable;
import com.google.protobuf.DescriptorProtos.FileDescriptorSet;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.ExtensionRegistry;
import com.google.protobuf.Message;
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
import dev.cel.common.ast.CelConstant;
import dev.cel.common.internal.EnvVisitable;
import dev.cel.common.internal.EnvVisitor;
import dev.cel.common.internal.FileDescriptorSetConverter;
import dev.cel.common.types.CelProtoTypes;
import dev.cel.common.types.CelType;
import dev.cel.common.types.CelTypeProvider;
import dev.cel.common.values.CelValueProvider;
import dev.cel.compiler.CelCompiler;
import dev.cel.compiler.CelCompilerBuilder;
import dev.cel.compiler.CelCompilerLibrary;
import dev.cel.parser.CelMacro;
import dev.cel.parser.CelParserBuilder;
import dev.cel.parser.CelStandardMacro;
import dev.cel.runtime.CelEvaluationException;
import dev.cel.runtime.CelRuntime;
import dev.cel.runtime.CelRuntimeBuilder;
import dev.cel.runtime.CelRuntimeImpl;
import dev.cel.runtime.CelRuntimeLibrary;
import dev.cel.runtime.CelStandardFunctions;
import java.util.Arrays;
import java.util.function.Function;

/**
 * Implementation of the synchronous CEL stack.
 *
 * <p>Note, the underlying {@link CelCompiler} and {@link CelRuntime} values are constructed lazily.
 */
@Immutable
final class CelImpl implements Cel, EnvVisitable {

  // The lazily constructed compiler and runtime values are memoized and guaranteed to be
  // constructed only once without side effects, thus making them effectively immutable.
  @SuppressWarnings("Immutable")
  private final Supplier<CelCompiler> compiler;

  @SuppressWarnings("Immutable")
  private final Supplier<CelRuntime> runtime;

  @Override
  public CelValidationResult parse(String expression, String description) {
    return compiler.get().parse(expression, description);
  }

  @Override
  public CelValidationResult parse(CelSource source) {
    return compiler.get().parse(source);
  }

  @Override
  public CelValidationResult check(CelAbstractSyntaxTree ast) {
    return compiler.get().check(ast);
  }

  @Override
  public CelTypeProvider getTypeProvider() {
    return compiler.get().getTypeProvider();
  }

  @Override
  public CelRuntime.Program createProgram(CelAbstractSyntaxTree ast) throws CelEvaluationException {
    return runtime.get().createProgram(ast);
  }

  @Override
  public void accept(EnvVisitor envVisitor) {
    CelCompiler celCompiler = compiler.get();
    if (celCompiler instanceof EnvVisitable) {
      ((EnvVisitable) celCompiler).accept(envVisitor);
    }
  }

  @Override
  public CelBuilder toCelBuilder() {
    return newBuilder(toCompilerBuilder(), toRuntimeBuilder());
  }

  @Override
  public CelParserBuilder toParserBuilder() {
    return compiler.get().toParserBuilder();
  }

  @Override
  public CelCheckerBuilder toCheckerBuilder() {
    return compiler.get().toCheckerBuilder();
  }

  @Override
  public CelCompilerBuilder toCompilerBuilder() {
    return compiler.get().toCompilerBuilder();
  }

  @Override
  public CelRuntimeBuilder toRuntimeBuilder() {
    return runtime.get().toRuntimeBuilder();
  }

  /** Combines a prebuilt {@link CelCompiler} and {@link CelRuntime} into {@link CelImpl}. */
  static CelImpl combine(CelCompiler compiler, CelRuntime runtime) {
    return new CelImpl(Suppliers.memoize(() -> compiler), Suppliers.memoize(() -> runtime));
  }

  /**
   * Create a new builder for constructing a {@code CelImpl} instance.
   *
   * <p>By default, {@link CelOptions#DEFAULT} are enabled, as is the CEL standard environment.
   *
   * <p>CEL Library Internals. Do Not Use. Consumers should use {@code CelFactory} instead.
   */
  static CelBuilder newBuilder(
      CelCompilerBuilder compilerBuilder, CelRuntimeBuilder celRuntimeBuilder) {
    return new CelImpl.Builder(compilerBuilder, celRuntimeBuilder);
  }

  /** Builder class for CelImpl instances. */
  public static final class Builder implements CelBuilder {

    private final CelCompilerBuilder compilerBuilder;
    private final CelRuntimeBuilder runtimeBuilder;

    private Builder(CelCompilerBuilder celCompilerBuilder, CelRuntimeBuilder celRuntimeBuilder) {
      this.compilerBuilder = celCompilerBuilder;
      this.runtimeBuilder = celRuntimeBuilder;
    }

    @Override
    public CelBuilder setOptions(CelOptions options) {
      compilerBuilder.setOptions(options);
      runtimeBuilder.setOptions(options);
      return this;
    }

    @Override
    public CelBuilder setStandardMacros(CelStandardMacro... macros) {
      compilerBuilder.setStandardMacros(macros);
      return this;
    }

    @Override
    public CelBuilder setStandardMacros(Iterable<CelStandardMacro> macros) {
      compilerBuilder.setStandardMacros(macros);
      return this;
    }

    @Override
    public CelBuilder addMacros(CelMacro... macros) {
      checkNotNull(macros);
      return addMacros(Arrays.asList(macros));
    }

    @Override
    public CelBuilder addMacros(Iterable<CelMacro> macros) {
      checkNotNull(macros);
      compilerBuilder.addMacros(macros);
      return this;
    }

    @Override
    public CelContainer container() {
      return compilerBuilder.container();
    }

    @Override
    public CelBuilder setContainer(CelContainer container) {
      compilerBuilder.setContainer(container);
      if (runtimeBuilder instanceof CelRuntimeImpl.Builder) {
        runtimeBuilder.setContainer(container);
      }

      return this;
    }

    @Override
    public CelBuilder addVar(String name, Type type) {
      compilerBuilder.addVar(name, type);
      return this;
    }

    @Override
    public CelBuilder addVar(String name, CelType type) {
      compilerBuilder.addVar(name, type);
      return this;
    }

    @Override
    public CelBuilder addConstant(String name, CelConstant celConstant) {
      compilerBuilder.addConstant(name, celConstant);
      return this;
    }

    @Override
    public CelBuilder addDeclarations(Decl... declarations) {
      compilerBuilder.addDeclarations(declarations);
      return this;
    }

    @Override
    public CelBuilder addDeclarations(Iterable<Decl> declarations) {
      compilerBuilder.addDeclarations(declarations);
      return this;
    }

    @Override
    public CelBuilder addFunctionDeclarations(CelFunctionDecl... celFunctionDecls) {
      compilerBuilder.addFunctionDeclarations(celFunctionDecls);
      return this;
    }

    @Override
    public CelBuilder addFunctionDeclarations(Iterable<CelFunctionDecl> celFunctionDecls) {
      compilerBuilder.addFunctionDeclarations(celFunctionDecls);
      return this;
    }

    @Override
    public CelBuilder addVarDeclarations(CelVarDecl... celVarDecls) {
      compilerBuilder.addVarDeclarations(celVarDecls);
      return this;
    }

    @Override
    public CelBuilder addVarDeclarations(Iterable<CelVarDecl> celVarDecls) {
      compilerBuilder.addVarDeclarations(celVarDecls);
      return this;
    }

    @Override
    public CelBuilder addProtoTypeMasks(ProtoTypeMask... typeMasks) {
      compilerBuilder.addProtoTypeMasks(typeMasks);
      return this;
    }

    @Override
    public CelBuilder addProtoTypeMasks(Iterable<ProtoTypeMask> typeMasks) {
      compilerBuilder.addProtoTypeMasks(typeMasks);
      return this;
    }

    @Override
    public CelBuilder addFunctionBindings(dev.cel.runtime.CelFunctionBinding... bindings) {
      runtimeBuilder.addFunctionBindings(bindings);
      return this;
    }

    @Override
    public CelBuilder addFunctionBindings(Iterable<dev.cel.runtime.CelFunctionBinding> bindings) {
      runtimeBuilder.addFunctionBindings(bindings);
      return this;
    }

    @Override
    public CelBuilder addLateBoundFunctions(String... lateBoundFunctionNames) {
      runtimeBuilder.addLateBoundFunctions(lateBoundFunctionNames);
      return this;
    }

    @Override
    public CelBuilder addLateBoundFunctions(Iterable<String> lateBoundFunctionNames) {
      runtimeBuilder.addLateBoundFunctions(lateBoundFunctionNames);
      return this;
    }

    @Override
    public CelBuilder setResultType(CelType resultType) {
      checkNotNull(resultType);
      return setProtoResultType(CelProtoTypes.celTypeToType(resultType));
    }

    @Override
    public CelBuilder setProtoResultType(Type resultType) {
      compilerBuilder.setProtoResultType(resultType);
      return this;
    }

    @Override
    public CelBuilder setTypeFactory(Function<String, Message.Builder> typeFactory) {
      runtimeBuilder.setTypeFactory(typeFactory);
      return this;
    }

    @Override
    public CelBuilder setValueProvider(CelValueProvider celValueProvider) {
      runtimeBuilder.setValueProvider(celValueProvider);
      return this;
    }

    @Override
    public CelValueProvider valueProvider() {
      return runtimeBuilder.valueProvider();
    }

    @Override
    @Deprecated
    public Builder setTypeProvider(TypeProvider typeProvider) {
      compilerBuilder.setTypeProvider(typeProvider);
      return this;
    }

    @Override
    public CelBuilder setTypeProvider(CelTypeProvider celTypeProvider) {
      compilerBuilder.setTypeProvider(celTypeProvider);
      if (runtimeBuilder instanceof CelRuntimeImpl.Builder) {
        runtimeBuilder.setTypeProvider(celTypeProvider);
      }
      return this;
    }

    @Override
    public CelBuilder addMessageTypes(Descriptor... descriptors) {
      compilerBuilder.addMessageTypes(descriptors);
      runtimeBuilder.addMessageTypes(descriptors);
      return this;
    }

    @Override
    public CelBuilder addMessageTypes(Iterable<Descriptor> descriptors) {
      compilerBuilder.addMessageTypes(descriptors);
      runtimeBuilder.addMessageTypes(descriptors);
      return this;
    }

    @Override
    public CelBuilder addFileTypes(FileDescriptor... fileDescriptors) {
      compilerBuilder.addFileTypes(fileDescriptors);
      runtimeBuilder.addFileTypes(fileDescriptors);
      return this;
    }

    @Override
    public CelBuilder addFileTypes(Iterable<FileDescriptor> fileDescriptors) {
      compilerBuilder.addFileTypes(fileDescriptors);
      runtimeBuilder.addFileTypes(fileDescriptors);
      return this;
    }

    @Override
    public CelBuilder addFileTypes(FileDescriptorSet fileDescriptorSet) {
      // Note: The convert method here constructs unique instances of FileDescriptors, so we convert
      // it here in advance and pass it down to compiler/runtime to avoid creating additional
      // instances.
      ImmutableSet<FileDescriptor> fileDescriptors =
          FileDescriptorSetConverter.convert(fileDescriptorSet);
      compilerBuilder.addFileTypes(fileDescriptors);
      runtimeBuilder.addFileTypes(fileDescriptors);
      return this;
    }

    @Override
    public CelBuilder setStandardEnvironmentEnabled(boolean value) {
      compilerBuilder.setStandardEnvironmentEnabled(value);
      runtimeBuilder.setStandardEnvironmentEnabled(value);
      return this;
    }

    @Override
    public CelBuilder addCompilerLibraries(CelCompilerLibrary... libraries) {
      checkNotNull(libraries);
      return this.addCompilerLibraries(Arrays.asList(libraries));
    }

    @Override
    public CelBuilder addCompilerLibraries(Iterable<CelCompilerLibrary> libraries) {
      checkNotNull(libraries);
      compilerBuilder.addLibraries(libraries);
      return this;
    }

    @Override
    public CelBuilder addRuntimeLibraries(CelRuntimeLibrary... libraries) {
      checkNotNull(libraries);
      return this.addRuntimeLibraries(Arrays.asList(libraries));
    }

    @Override
    public CelBuilder addRuntimeLibraries(Iterable<CelRuntimeLibrary> libraries) {
      checkNotNull(libraries);
      runtimeBuilder.addLibraries(libraries);
      return this;
    }

    @Override
    public CelBuilder setStandardDeclarations(CelStandardDeclarations standardDeclarations) {
      checkNotNull(standardDeclarations);
      compilerBuilder.setStandardDeclarations(standardDeclarations);
      return this;
    }

    @Override
    public CelBuilder setStandardFunctions(CelStandardFunctions standardFunctions) {
      checkNotNull(standardFunctions);
      runtimeBuilder.setStandardFunctions(standardFunctions);
      return this;
    }

    @Override
    public CelBuilder setExtensionRegistry(ExtensionRegistry extensionRegistry) {
      checkNotNull(extensionRegistry);
      runtimeBuilder.setExtensionRegistry(extensionRegistry);
      return this;
    }

    @Override
    public Cel build() {
      return new CelImpl(
          Suppliers.memoize(compilerBuilder::build), Suppliers.memoize(runtimeBuilder::build));
    }
  }

  private CelImpl(Supplier<CelCompiler> compiler, Supplier<CelRuntime> runtime) {
    this.compiler = compiler;
    this.runtime = runtime;
  }
}
