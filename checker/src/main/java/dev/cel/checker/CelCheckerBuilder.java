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

package dev.cel.checker;

import dev.cel.expr.Decl;
import dev.cel.expr.Type;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.errorprone.annotations.CheckReturnValue;
import com.google.protobuf.DescriptorProtos.FileDescriptorSet;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import dev.cel.common.CelContainer;
import dev.cel.common.CelFunctionDecl;
import dev.cel.common.CelOptions;
import dev.cel.common.CelVarDecl;
import dev.cel.common.ast.CelConstant;
import dev.cel.common.types.CelType;
import dev.cel.common.types.CelTypeProvider;

/** Interface for building an instance of CelChecker */
public interface CelCheckerBuilder {

  /** Set the {@code CelOptions} used to enable fixes and features for this CEL instances. */
  @CanIgnoreReturnValue
  CelCheckerBuilder setOptions(CelOptions options);

  /** Retrieves the currently configured {@link CelOptions} in the builder. */
  CelOptions options();

  /**
   * Set the {@link CelContainer} to use as the namespace for resolving CEL expression variables and
   * functions.
   */
  @CanIgnoreReturnValue
  CelCheckerBuilder setContainer(CelContainer container);

  /** Retrieves the currently configured {@link CelContainer} in the builder. */
  CelContainer container();

  /** Add variable and function {@code declarations} to the CEL environment. */
  @CanIgnoreReturnValue
  CelCheckerBuilder addDeclarations(Decl... declarations);

  /** Add variable and function {@code declarations} to the CEL environment. */
  @CanIgnoreReturnValue
  CelCheckerBuilder addDeclarations(Iterable<Decl> declarations);

  /** Add function declaration {@code CelFunctionDecl} to the CEL environment. */
  @CanIgnoreReturnValue
  CelCheckerBuilder addFunctionDeclarations(CelFunctionDecl... celFunctionDecls);

  /** Add function declaration {@code CelFunctionDecl} to the CEL environment. */
  @CanIgnoreReturnValue
  CelCheckerBuilder addFunctionDeclarations(Iterable<CelFunctionDecl> celFunctionDecls);

  /** Add variable declaration {@code CelVarDecl} to the CEL environment. */
  @CanIgnoreReturnValue
  CelCheckerBuilder addVarDeclarations(CelVarDecl... celVarDecls);

  /** Add variable declaration {@code CelVarDecl} to the CEL environment. */
  @CanIgnoreReturnValue
  CelCheckerBuilder addVarDeclarations(Iterable<CelVarDecl> celVarDecls);

  /**
   * Adds a named constant declaration to the CEL environment. The declared constant will be inlined
   * into the expression during type-check.
   */
  @CanIgnoreReturnValue
  CelCheckerBuilder addConstant(String name, CelConstant celConstant);

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
  CelCheckerBuilder addProtoTypeMasks(ProtoTypeMask... typeMasks);

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
  CelCheckerBuilder addProtoTypeMasks(Iterable<ProtoTypeMask> typeMasks);

  /** Set the expected {@code resultType} for the type-checked expression. */
  @CanIgnoreReturnValue
  CelCheckerBuilder setResultType(CelType resultType);

  /**
   * Set the expected {@code resultType} in proto format described in checked.proto for the
   * type-checked expression.
   */
  @CanIgnoreReturnValue
  CelCheckerBuilder setProtoResultType(Type resultType);

  /**
   * Set the {@code typeProvider} for use with type-checking expressions.
   *
   * @deprecated Use {@link #setTypeProvider(CelTypeProvider)} instead.
   */
  @CanIgnoreReturnValue
  @Deprecated
  CelCheckerBuilder setTypeProvider(TypeProvider celTypeProvider);

  /** Set the {@code celTypeProvider} for use with type-checking expressions. */
  @CanIgnoreReturnValue
  CelCheckerBuilder setTypeProvider(CelTypeProvider celTypeProvider);

  /**
   * Add message {@link Descriptor}s to the use for type-checking and object creation at
   * interpretation time.
   */
  @CanIgnoreReturnValue
  CelCheckerBuilder addMessageTypes(Descriptor... descriptors);

  /**
   * Add message {@link Descriptor}s to the use for type-checking and object creation at
   * interpretation time.
   */
  @CanIgnoreReturnValue
  CelCheckerBuilder addMessageTypes(Iterable<Descriptor> descriptors);

  /**
   * Add {@link FileDescriptor}s to the use for type-checking, and for object creation at
   * interpretation time.
   */
  @CanIgnoreReturnValue
  CelCheckerBuilder addFileTypes(FileDescriptor... fileDescriptors);

  /**
   * Add {@link FileDescriptor}s to the use for type-checking, and for object creation at
   * interpretation time.
   */
  @CanIgnoreReturnValue
  CelCheckerBuilder addFileTypes(Iterable<FileDescriptor> fileDescriptors);

  /**
   * Add all of the {@link FileDescriptor}s in a {@code FileDescriptorSet} to the use for
   * type-checking, and for object creation at interpretation time.
   */
  @CanIgnoreReturnValue
  CelCheckerBuilder addFileTypes(FileDescriptorSet fileDescriptorSet);

  /** Enable or disable the standard CEL library functions and variables */
  @CanIgnoreReturnValue
  CelCheckerBuilder setStandardEnvironmentEnabled(boolean value);

  /**
   * Override the standard declarations for the type-checker. This can be used to subset the
   * standard environment to only expose the desired declarations to the type-checker. {@link
   * #setStandardEnvironmentEnabled(boolean)} must be set to false for this to take effect.
   */
  @CanIgnoreReturnValue
  CelCheckerBuilder setStandardDeclarations(CelStandardDeclarations standardDeclarations);

  /** Adds one or more libraries for parsing and type-checking. */
  @CanIgnoreReturnValue
  CelCheckerBuilder addLibraries(CelCheckerLibrary... libraries);

  /** Adds a collection of libraries for parsing and type-checking. */
  @CanIgnoreReturnValue
  CelCheckerBuilder addLibraries(Iterable<? extends CelCheckerLibrary> libraries);

  /** Build a new instance of the {@code CelChecker}. */
  @CheckReturnValue
  CelChecker build();
}
