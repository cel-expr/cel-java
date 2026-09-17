// Copyright 2026 Google LLC
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

package dev.cel.common;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;

import dev.cel.expr.Decl;
import dev.cel.expr.Decl.FunctionDecl;
import dev.cel.expr.Decl.FunctionDecl.Overload;
import dev.cel.expr.Decl.IdentDecl;
import dev.cel.common.ast.CelExprConverter;
import dev.cel.common.types.CelProtoTypes;

/**
 * Utility class for converting between native CEL declarations ({@link CelVarDecl}, {@link
 * CelFunctionDecl}, {@link CelOverloadDecl}) and protobuf representations ({@link Decl}, {@link
 * Overload}).
 */
public final class CelProtoDeclConverter {

  /** Converts a {@link CelVarDecl} to a protobuf equivalent form {@link Decl}. */
  public static Decl celVarDeclToDecl(CelVarDecl varDecl) {
    IdentDecl.Builder identBuilder =
        IdentDecl.newBuilder()
            .setDoc(varDecl.doc())
            .setType(CelProtoTypes.celTypeToType(varDecl.type()));
    varDecl
        .constant()
        .ifPresent(c -> identBuilder.setValue(CelExprConverter.celConstantToExprConstant(c)));
    return Decl.newBuilder().setName(varDecl.name()).setIdent(identBuilder).build();
  }

  /** Converts a protobuf {@link Decl} of kind IDENT to a {@link CelVarDecl}. */
  public static CelVarDecl declToCelVarDecl(Decl decl) {
    checkArgument(decl.hasIdent(), "Decl must be of kind IDENT: %s", decl);
    CelVarDecl.Builder builder =
        CelVarDecl.newBuilder()
            .setName(decl.getName())
            .setType(CelProtoTypes.typeToCelType(decl.getIdent().getType()))
            .setDoc(decl.getIdent().getDoc());
    if (decl.getIdent().hasValue()) {
      builder.setConstant(CelExprConverter.exprConstantToCelConstant(decl.getIdent().getValue()));
    }
    return builder.build();
  }

  /** Converts a {@link CelFunctionDecl} to a protobuf equivalent form {@link Decl}. */
  public static Decl celFunctionDeclToDecl(CelFunctionDecl celFunctionDecl) {
    return Decl.newBuilder()
        .setName(celFunctionDecl.name())
        .setFunction(
            FunctionDecl.newBuilder()
                .addAllOverloads(
                    celFunctionDecl.overloads().stream()
                        .map(CelProtoDeclConverter::celOverloadToOverload)
                        .collect(toImmutableList())))
        .build();
  }

  /** Converts a protobuf {@link Decl} of kind FUNCTION to a {@link CelFunctionDecl}. */
  public static CelFunctionDecl declToCelFunctionDecl(Decl decl) {
    checkArgument(decl.hasFunction(), "Decl must be of kind FUNCTION: %s", decl);
    return CelFunctionDecl.newFunctionDeclaration(
        decl.getName(),
        decl.getFunction().getOverloadsList().stream()
            .map(CelProtoDeclConverter::overloadToCelOverload)
            .collect(toImmutableList()));
  }

  /** Converts a {@link CelOverloadDecl} to a protobuf equivalent form {@link Overload}. */
  public static Overload celOverloadToOverload(CelOverloadDecl overload) {
    return Overload.newBuilder()
        .setIsInstanceFunction(overload.isInstanceFunction())
        .setOverloadId(overload.overloadId())
        .setResultType(CelProtoTypes.celTypeToType(overload.resultType()))
        .addAllParams(
            overload.parameterTypes().stream()
                .map(CelProtoTypes::celTypeToType)
                .collect(toImmutableList()))
        .addAllTypeParams(overload.typeParameterNames())
        .setDoc(overload.doc())
        .build();
  }

  /** Converts a protobuf {@link Overload} to a {@link CelOverloadDecl}. */
  public static CelOverloadDecl overloadToCelOverload(Overload overload) {
    return CelOverloadDecl.newBuilder()
        .setIsInstanceFunction(overload.getIsInstanceFunction())
        .setOverloadId(overload.getOverloadId())
        .setResultType(CelProtoTypes.typeToCelType(overload.getResultType()))
        .setDoc(overload.getDoc())
        .addParameterTypes(
            overload.getParamsList().stream()
                .map(CelProtoTypes::typeToCelType)
                .collect(toImmutableList()))
        .build();
  }

  private CelProtoDeclConverter() {}
}
