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

import dev.cel.expr.Decl;
import dev.cel.expr.Type;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.protobuf.DescriptorProtos.FileDescriptorSet;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.ExtensionRegistry;
import com.google.protobuf.Message;
import dev.cel.checker.CelStandardDeclarations;
import dev.cel.checker.ProtoTypeMask;
import dev.cel.checker.TypeProvider;
import dev.cel.common.CelContainer;
import dev.cel.common.CelFunctionDecl;
import dev.cel.common.CelOptions;
import dev.cel.common.CelVarDecl;
import dev.cel.common.types.CelType;
import dev.cel.common.types.CelTypeProvider;
import dev.cel.common.values.CelValueProvider;
import dev.cel.compiler.CelCompilerLibrary;
import dev.cel.parser.CelMacro;
import dev.cel.parser.CelStandardMacro;
import dev.cel.runtime.CelFunctionBinding;
import dev.cel.runtime.CelRuntimeLibrary;
import dev.cel.runtime.CelStandardFunctions;
import java.util.function.Function;

/** Interface for building an instance of Cel. */
public interface CelBuilder {

  /** Set the {@code CelOptions} used to enable fixes and feautres for this CEL instance. */
  @CanIgnoreReturnValue
  CelBuilder setOptions(CelOptions options);

  /** Set the {@link CelStandardMacro} values for use with this instance. */
  @CanIgnoreReturnValue
  CelBuilder setStandardMacros(CelStandardMacro... macros);

  /** Set the {@link CelStandardMacro} values for use with this instance. */
  @CanIgnoreReturnValue
  CelBuilder setStandardMacros(Iterable<CelStandardMacro> macros);

  /**
   * Registers the given macros, replacing any previous macros with the same key.
   *
   * <p>Use this to register a set of user-defined custom macro implementation for the parser. For
   * registering macros defined as part of CEL standard library, use {@link #setStandardMacros}
   * instead.
   *
   * <p>Custom macros should not use the same function names as the ones found in {@link
   * CelStandardMacro} (ex: has, all, exists, etc.). Build method will throw if both standard macros
   * and custom macros are set with the same name.
   */
  @CanIgnoreReturnValue
  CelBuilder addMacros(CelMacro... macros);

  /**
   * Registers the given macros, replacing any previous macros with the same key.
   *
   * <p>Use this to register a set of user-defined custom macro implementation for the parser. For
   * registering macros defined as part of CEL standard library, use {@link #setStandardMacros}
   * instead.
   *
   * <p>Custom macros should not use the same function names as the ones found in {@link
   * CelStandardMacro} (ex: has, all, exists, etc.). Build method will throw if both standard macros
   * and custom macros are set with the same name.
   */
  @CanIgnoreReturnValue
  CelBuilder addMacros(Iterable<CelMacro> macros);

  /** Retrieves the currently configured {@link CelContainer} in the builder. */
  CelContainer container();

  /**
   * Set the {@link CelContainer} to use as the namespace for resolving CEL expression variables and
   * functions.
   */
  @CanIgnoreReturnValue
  CelBuilder setContainer(CelContainer container);

  /** Add a variable declaration with a given {@code name} and {@link Type}. */
  @CanIgnoreReturnValue
  CelBuilder addVar(String name, Type type);

  /** Add a variable declaration with a given {@code name} and {@link CelType}. */
  @CanIgnoreReturnValue
  CelBuilder addVar(String name, CelType type);

  /** Add variable and function {@code declarations} to the CEL environment. */
  @CanIgnoreReturnValue
  CelBuilder addDeclarations(Decl... declarations);

  /** Add variable and function {@code declarations} to the CEL environment. */
  @CanIgnoreReturnValue
  CelBuilder addDeclarations(Iterable<Decl> declarations);

  /** Add function declaration {@code CelFunctionDecl} to the CEL environment. */
  @CanIgnoreReturnValue
  CelBuilder addFunctionDeclarations(CelFunctionDecl... celFunctionDecls);

  /** Add function declaration {@code CelFunctionDecl} to the CEL environment. */
  @CanIgnoreReturnValue
  CelBuilder addFunctionDeclarations(Iterable<CelFunctionDecl> celFunctionDecls);

  /** Add variable declaration {@code CelVarDecl} to the CEL environment. */
  @CanIgnoreReturnValue
  CelBuilder addVarDeclarations(CelVarDecl... celVarDecls);

  /** Add variable declaration {@code CelVarDecl} to the CEL environment. */
  @CanIgnoreReturnValue
  CelBuilder addVarDeclarations(Iterable<CelVarDecl> celVarDecls);

  /**
   * Add one or more {@link ProtoTypeMask} values. The {@code ProtoTypeMask} values will be used to
   * compute a set of {@code Decl} values using a protobuf message's fields as the names and types
   * of the variables if {@link ProtoTypeMask#fieldsAreVariableDeclarations} is {@code true}.
   *
   * <p>Note, this feature may not work with custom {@link TypeProvider} implementations out of the
   * box, as it requires the implementation of {@link TypeProvider#lookupFieldNames} to return the
   * set of all fields declared on the protobuf type.
   */
  @CanIgnoreReturnValue
  CelBuilder addProtoTypeMasks(ProtoTypeMask... typeMasks);

  /**
   * Add one or more {@link ProtoTypeMask} values. The {@code ProtoTypeMask} values will be used to
   * compute a set of {@code Decl} values using a protobuf message's fields as the names and types
   * of the variables if {@link ProtoTypeMask#fieldsAreVariableDeclarations} is {@code true}.
   *
   * <p>Note, this feature may not work with custom {@link TypeProvider} implementations out of the
   * box, as it requires the implementation of {@link TypeProvider#lookupFieldNames} to return the
   * set of all fields declared on the protobuf type.
   */
  @CanIgnoreReturnValue
  CelBuilder addProtoTypeMasks(Iterable<ProtoTypeMask> typeMasks);

  /**
   * Add one or more {@link CelFunctionBinding} objects to the CEL runtime.
   *
   * <p>Functions with duplicate overload ids will be replaced in favor of the new overload.
   */
  @CanIgnoreReturnValue
  CelBuilder addFunctionBindings(CelFunctionBinding... bindings);

  /**
   * Bind a collection of {@link CelFunctionBinding} objects to the runtime.
   *
   * <p>Functions with duplicate overload ids will be replaced in favor of the new overload.
   */
  @CanIgnoreReturnValue
  CelBuilder addFunctionBindings(Iterable<CelFunctionBinding> bindings);

  /** Adds bindings for functions that are allowed to be late-bound (resolved at execution time). */
  @CanIgnoreReturnValue
  CelBuilder addLateBoundFunctions(String... lateBoundFunctionNames);

  /** Adds bindings for functions that are allowed to be late-bound (resolved at execution time). */
  @CanIgnoreReturnValue
  CelBuilder addLateBoundFunctions(Iterable<String> lateBoundFunctionNames);

  /** Set the expected {@code resultType} for the type-checked expression. */
  @CanIgnoreReturnValue
  CelBuilder setResultType(CelType resultType);

  /**
   * Set the expected {@code resultType} in proto format described in checked.proto for the
   * type-checked expression.
   */
  @CanIgnoreReturnValue
  CelBuilder setProtoResultType(Type resultType);

  /**
   * Set a custom type factory for the runtime.
   *
   * <p>Note: it is valid to combine type factory methods within the runtime. Only the options which
   * have been configured will be used.
   *
   * <p>The type creation search order is as follows:
   *
   * <ul>
   *   <li/>Custom type factory ({@link #setTypeFactory})
   *   <li/>Custom descriptor set {{@link #addMessageTypes})
   * </ul>
   */
  @CanIgnoreReturnValue
  CelBuilder setTypeFactory(Function<String, Message.Builder> typeFactory);

  /**
   * Sets the {@code celValueProvider} for resolving values during evaluation. The provided value
   * provider will be used first before falling back to the built-in {@link
   * dev.cel.common.values.ProtoMessageValueProvider} for resolving protobuf messages.
   *
   * <p>Note that {@link CelOptions#enableCelValue()} must be enabled or this method will be a
   * no-op.
   */
  @CanIgnoreReturnValue
  CelBuilder setValueProvider(CelValueProvider celValueProvider);

  /** Returns the configured {@link CelValueProvider}, or null if not set. */
  CelValueProvider valueProvider();

  /**
   * Set the {@code typeProvider} for use with type-checking expressions.
   *
   * @deprecated Use {@link #setTypeProvider(CelTypeProvider)} instead.
   */
  @CanIgnoreReturnValue
  @Deprecated
  CelBuilder setTypeProvider(TypeProvider typeProvider);

  /** Set the {@code celTypeProvider} for use with type-checking expressions. */
  @CanIgnoreReturnValue
  CelBuilder setTypeProvider(CelTypeProvider celTypeProvider);

  /**
   * Add message {@link Descriptor}s to the use for type-checking and object creation at
   * interpretation time.
   *
   * <p>This method may be safely combined with {@link #setTypeFactory}, {@link #setTypeProvider},
   * and {@link #addFileTypes} calls.
   *
   * <p>If either a {@code typeFactory} or {@code typeProvider} are configured, these classes will
   * take precedence over any dynamic resolution of data related to the descriptors.
   */
  @CanIgnoreReturnValue
  CelBuilder addMessageTypes(Descriptor... descriptors);

  /**
   * Add message {@link Descriptor}s to the use for type-checking and object creation at
   * interpretation time.
   *
   * <p>This method may be safely combined with {@link #setTypeFactory}, {@link #setTypeProvider},
   * and {@link #addFileTypes} calls.
   *
   * <p>If either a {@code typeFactory} or {@code typeProvider} are configured, these classes will
   * take precedence over any dynamic resolution of data related to the descriptors.
   */
  @CanIgnoreReturnValue
  CelBuilder addMessageTypes(Iterable<Descriptor> descriptors);

  /**
   * Add {@link FileDescriptor}s to the use for type-checking, and for object creation at
   * interpretation time.
   *
   * <p>This method may be safely combined with {@link #setTypeFactory}, {@link #setTypeProvider},
   * and {@link #addMessageTypes} calls.
   *
   * <p>If either a {@code typeFactory} or {@code typeProvider} are configured, these classes will
   * take precedence over any dynamic resolution of data related to the descriptors.
   */
  @CanIgnoreReturnValue
  CelBuilder addFileTypes(FileDescriptor... fileDescriptors);

  /**
   * Add {@link FileDescriptor}s to the use for type-checking, and for object creation at
   * interpretation time.
   *
   * <p>This method may be safely combined with {@link #setTypeFactory}, {@link #setTypeProvider},
   * and {@link #addMessageTypes} calls.
   *
   * <p>If either a {@code typeFactory} or {@code typeProvider} are configured, these classes will
   * take precedence over any dynamic resolution of data related to the descriptors.
   */
  @CanIgnoreReturnValue
  CelBuilder addFileTypes(Iterable<FileDescriptor> fileDescriptors);

  /**
   * Add all of the {@link FileDescriptor}s in a {@code FileDescriptorSet} to the use for
   * type-checking, and for object creation at interpretation time.
   *
   * <p>This method may be safely combined with {@link #setTypeFactory}, {@link #setTypeProvider},
   * and {@link #addMessageTypes} calls.
   *
   * <p>If either a {@code typeFactory} or {@code typeProvider} are configured, these classes will
   * take precedence over any dynamic resolution of data related to the descriptors.
   */
  @CanIgnoreReturnValue
  CelBuilder addFileTypes(FileDescriptorSet fileDescriptorSet);

  /** Enable or disable the standard CEL library functions and variables */
  @CanIgnoreReturnValue
  CelBuilder setStandardEnvironmentEnabled(boolean value);

  /** Adds one or more libraries for parsing and type-checking. */
  @CanIgnoreReturnValue
  CelBuilder addCompilerLibraries(CelCompilerLibrary... libraries);

  /** Adds a collection of libraries for parsing and type-checking. */
  @CanIgnoreReturnValue
  CelBuilder addCompilerLibraries(Iterable<CelCompilerLibrary> libraries);

  /** Adds one or more libraries for runtime. */
  @CanIgnoreReturnValue
  CelBuilder addRuntimeLibraries(CelRuntimeLibrary... libraries);

  /** Adds a collection of libraries for runtime. */
  @CanIgnoreReturnValue
  CelBuilder addRuntimeLibraries(Iterable<CelRuntimeLibrary> libraries);

  /**
   * Override the standard declarations for the type-checker. This can be used to subset the
   * standard environment to only expose the desired declarations to the type-checker. {@link
   * #setStandardEnvironmentEnabled(boolean)} must be set to false for this to take effect.
   */
  @CanIgnoreReturnValue
  CelBuilder setStandardDeclarations(CelStandardDeclarations standardDeclarations);

  /**
   * Override the standard functions for the runtime. This can be used to subset the standard
   * environment to only expose the desired function overloads to the runtime.
   *
   * <p>{@link #setStandardEnvironmentEnabled(boolean)} must be set to false for this to take
   * effect.
   */
  @CanIgnoreReturnValue
  CelBuilder setStandardFunctions(CelStandardFunctions standardFunctions);

  /**
   * Sets a proto ExtensionRegistry to assist with unpacking Any messages containing a proto2
   extension field.
   */
  @CanIgnoreReturnValue
  CelBuilder setExtensionRegistry(ExtensionRegistry extensionRegistry);

  /** Construct a new {@code Cel} instance from the provided configuration. */
  Cel build();
}
