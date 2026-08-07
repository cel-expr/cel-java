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

import dev.cel.expr.Decl;
import dev.cel.expr.Type;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.errorprone.annotations.CheckReturnValue;
import com.google.protobuf.DescriptorProtos.FileDescriptorSet;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import dev.cel.checker.CelStandardDeclarations;
import dev.cel.checker.ProtoTypeMask;
import dev.cel.checker.TypeProvider;
import dev.cel.common.CelContainer;
import dev.cel.common.CelFunctionDecl;
import dev.cel.common.CelOptions;
import dev.cel.common.CelVarDecl;
import dev.cel.common.ast.CelConstant;
import dev.cel.common.types.CelType;
import dev.cel.common.types.CelTypeProvider;
import dev.cel.parser.CelMacro;
import dev.cel.parser.CelStandardMacro;

/** Interface for building an instance of CelCompiler */
public interface CelCompilerBuilder {
  /** Sets the macro set for the parser, replacing the macros from any prior call. */
  @CanIgnoreReturnValue
  CelCompilerBuilder setStandardMacros(CelStandardMacro... macros);

  /** Sets the macro set for the parser, replacing the macros from any prior call. */
  @CanIgnoreReturnValue
  CelCompilerBuilder setStandardMacros(Iterable<CelStandardMacro> macros);

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
  CelCompilerBuilder addMacros(CelMacro... macros);

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
  CelCompilerBuilder addMacros(Iterable<CelMacro> macros);

  /** Set the {@code CelOptions} used to enable fixes and features for this CEL instances. */
  @CanIgnoreReturnValue
  CelCompilerBuilder setOptions(CelOptions options);

  /**
   * Set the {@link CelContainer} to use as the namespace for resolving CEL expression variables and
   * functions.
   */
  @CanIgnoreReturnValue
  CelCompilerBuilder setContainer(CelContainer container);

  /** Retrieves the currently configured {@link CelContainer} in the builder. */
  CelContainer container();

  /** Add a variable declaration with a given {@code name} and proto based {@link Type}. */
  @CanIgnoreReturnValue
  CelCompilerBuilder addVar(String name, Type type);

  /** Add a variable declaration with a given {@code name} and {@link CelType}. */
  @CanIgnoreReturnValue
  CelCompilerBuilder addVar(String name, CelType type);

  /**
   * Adds a named constant declaration to the CEL environment. The declared constant will be inlined
   * into the expression during type-check.
   */
  @CanIgnoreReturnValue
  CelCompilerBuilder addConstant(String name, CelConstant celConstant);

  /** Add variable and function {@code declarations} to the CEL environment. */
  @CanIgnoreReturnValue
  CelCompilerBuilder addDeclarations(Decl... declarations);

  /** Add variable and function {@code declarations} to the CEL environment. */
  @CanIgnoreReturnValue
  CelCompilerBuilder addDeclarations(Iterable<Decl> declarations);

  /** Add function declaration {@code CelFunctionDecl} to the CEL environment */
  @CanIgnoreReturnValue
  CelCompilerBuilder addFunctionDeclarations(CelFunctionDecl... celFunctionDecls);

  /** Add function declaration {@code CelFunctionDecl} to the CEL environment */
  @CanIgnoreReturnValue
  CelCompilerBuilder addFunctionDeclarations(Iterable<CelFunctionDecl> celFunctionDecls);

  /** Add variable declaration {@code CelVarDecl} to the CEL environment. */
  @CanIgnoreReturnValue
  CelCompilerBuilder addVarDeclarations(CelVarDecl... varDecl);

  /** Add variable declaration {@code CelVarDecl} to the CEL environment. */
  @CanIgnoreReturnValue
  CelCompilerBuilder addVarDeclarations(Iterable<CelVarDecl> varDecl);

  /**
   * Add one or more {@link ProtoTypeMask} values. The {@code ProtoTypeMask} values will be used to
   * compute a set of {@code Decl} values using a protobuf message's fields as the names and types
   * of the variables if {@link ProtoTypeMask#fieldsAreVariableDeclarations} is {@code true}.
   *
   * <p>Note, this feature may not work with custom {@code TypeProvider} implementations out of the
   * box, as it requires the implementation of {@code TypeProvider#lookupFieldNames} to return the
   * set of all fields declared on the protobuf type.
   */
  @CanIgnoreReturnValue
  CelCompilerBuilder addProtoTypeMasks(ProtoTypeMask... typeMasks);

  /**
   * Add one or more {@link ProtoTypeMask} values. The {@code ProtoTypeMask} values will be used to
   * compute a set of {@code Decl} values using a protobuf message's fields as the names and types
   * of the variables if {@link ProtoTypeMask#fieldsAreVariableDeclarations} is {@code true}.
   *
   * <p>Note, this feature may not work with custom {@code TypeProvider} implementations out of the
   * box, as it requires the implementation of {@code TypeProvider#lookupFieldNames} to return the
   * set of all fields declared on the protobuf type.
   */
  @CanIgnoreReturnValue
  CelCompilerBuilder addProtoTypeMasks(Iterable<ProtoTypeMask> typeMasks);

  /** Set the expected {@code resultType} for the type-checked expression. */
  @CanIgnoreReturnValue
  CelCompilerBuilder setResultType(CelType resultType);

  /**
   * Set the expected {@code resultType} in proto format described in checked.proto for the
   * type-checked expression.
   */
  @CanIgnoreReturnValue
  CelCompilerBuilder setProtoResultType(Type resultType);

  /**
   * Set the {@code typeProvider} for use with type-checking expressions.
   *
   * @deprecated Use {@link #setTypeProvider(CelTypeProvider)} instead.
   */
  @CanIgnoreReturnValue
  @Deprecated
  CelCompilerBuilder setTypeProvider(TypeProvider typeProvider);

  /** Set the {@code celTypeProvider} for use with type-checking expressions. */
  @CanIgnoreReturnValue
  CelCompilerBuilder setTypeProvider(CelTypeProvider celTypeProvider);

  /**
   * Add message {@link Descriptor}s to the use for type-checking and object creation at
   * interpretation time.
   */
  @CanIgnoreReturnValue
  CelCompilerBuilder addMessageTypes(Descriptor... descriptors);

  /**
   * Add message {@link Descriptor}s to the use for type-checking and object creation at
   * interpretation time.
   */
  @CanIgnoreReturnValue
  CelCompilerBuilder addMessageTypes(Iterable<Descriptor> descriptors);

  /**
   * Add {@link FileDescriptor}s to the use for type-checking, and for object creation at
   * interpretation time.
   */
  @CanIgnoreReturnValue
  CelCompilerBuilder addFileTypes(FileDescriptor... fileDescriptors);

  /**
   * Add {@link FileDescriptor}s to the use for type-checking, and for object creation at
   * interpretation time.
   */
  @CanIgnoreReturnValue
  CelCompilerBuilder addFileTypes(Iterable<FileDescriptor> fileDescriptors);

  /**
   * Add all of the {@link FileDescriptor}s in a {@code FileDescriptorSet} to the use for
   * type-checking, and for object creation at interpretation time.
   */
  @CanIgnoreReturnValue
  CelCompilerBuilder addFileTypes(FileDescriptorSet fileDescriptorSet);

  /** Enable or disable the standard CEL library functions and variables */
  @CanIgnoreReturnValue
  CelCompilerBuilder setStandardEnvironmentEnabled(boolean value);

  /**
   * Override the standard declarations for the type-checker. This can be used to subset the
   * standard environment to only expose the desired declarations to the type-checker. {@link
   * #setStandardEnvironmentEnabled(boolean)} must be set to false for this to take effect.
   */
  @CanIgnoreReturnValue
  CelCompilerBuilder setStandardDeclarations(CelStandardDeclarations standardDeclarations);

  /** Adds one or more libraries for parsing and type-checking. */
  @CanIgnoreReturnValue
  CelCompilerBuilder addLibraries(CelCompilerLibrary... libraries);

  /** Adds a collection of libraries for parsing and type-checking. */
  @CanIgnoreReturnValue
  CelCompilerBuilder addLibraries(Iterable<? extends CelCompilerLibrary> libraries);

  /** Build a new instance of the {@code CelCompiler}. */
  @CheckReturnValue
  CelCompiler build();
}
