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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.errorprone.annotations.Immutable;
import dev.cel.common.CelFunctionDecl;
import dev.cel.common.CelIssue;
import dev.cel.common.CelOverloadDecl;
import dev.cel.common.ast.CelBlock;
import dev.cel.common.ast.CelExpr;
import dev.cel.common.types.ListType;
import dev.cel.common.types.SimpleType;
import dev.cel.common.types.TypeParamType;
import dev.cel.compiler.CelCompilerLibrary;
import dev.cel.parser.CelMacro;
import dev.cel.parser.CelMacroExprFactory;
import dev.cel.parser.CelParserBuilder;
import java.util.Optional;

/** Internal implementation of the CEL local binding extensions. */
@Immutable
public final class CelBindingsExtensions
    implements CelCompilerLibrary, CelExtensionLibrary.FeatureSet {
  private static final String CEL_NAMESPACE = "cel";
  private static final String UNUSED_ITER_VAR = "#unused";

  private static final CelExtensionLibrary<CelBindingsExtensions> LIBRARY =
      new CelExtensionLibrary<CelBindingsExtensions>() {
        private final CelBindingsExtensions version0 = new CelBindingsExtensions();

        @Override
        public String name() {
          return "bindings";
        }

        @Override
        public ImmutableSet<CelBindingsExtensions> versions() {
          return ImmutableSet.of(version0);
        }
      };

  static CelExtensionLibrary<CelBindingsExtensions> library() {
    return LIBRARY;
  }

  public static final CelFunctionDecl CEL_BLOCK_FUNCTION_DECL =
      CelFunctionDecl.newFunctionDeclaration(
          CelBlock.FUNCTION_NAME,
          CelOverloadDecl.newGlobalOverload(
              "cel_block_list",
              TypeParamType.create("T"),
              ListType.create(SimpleType.DYN),
              TypeParamType.create("T")));

  @Override
  public int version() {
    return 0;
  }

  @Override
  public ImmutableSet<CelFunctionDecl> functions() {
    // TODO: Add bindings for block once decorator support is available.
    return ImmutableSet.of(CEL_BLOCK_FUNCTION_DECL);
  }

  @Override
  public ImmutableSet<CelMacro> macros() {
    return ImmutableSet.of(CelMacro.newReceiverMacro("bind", 3, CelBindingsExtensions::expandBind));
  }

  @Override
  public void setParserOptions(CelParserBuilder parserBuilder) {
    parserBuilder.addMacros(macros());
  }

  /**
   * The {@code expandBind} maps a variable name to an initialization expression, allowing the
   * variable to be used in the subsequent result expression.
   */
  private static Optional<CelExpr> expandBind(
      CelMacroExprFactory exprFactory, CelExpr target, ImmutableList<CelExpr> arguments) {
    checkNotNull(target);
    if (!isTargetInNamespace(target)) {
      // Return empty to indicate that we're not interested in expanding this macro, and
      // that the parser should default to a function call on the receiver.
      return Optional.empty();
    }

    checkNotNull(exprFactory);
    checkArgument(arguments.size() == 3);

    CelExpr varIdent = checkNotNull(arguments.get(0));
    if (varIdent.exprKind().getKind() != CelExpr.ExprKind.Kind.IDENT) {
      return Optional.of(
          exprFactory.reportError(
              CelIssue.formatError(
                  exprFactory.getSourceLocation(varIdent),
                  "cel.bind() variable name must be a simple identifier")));
    }
    String varName = varIdent.ident().name();
    CelExpr varInit = checkNotNull(arguments.get(1));
    CelExpr resultExpr = checkNotNull(arguments.get(2));

    return Optional.of(
        exprFactory.fold(
            UNUSED_ITER_VAR,
            exprFactory.newList(),
            varName,
            varInit,
            exprFactory.newBoolLiteral(false),
            exprFactory.newIdentifier(varName),
            resultExpr));
  }

  private static boolean isTargetInNamespace(CelExpr target) {
    return target.exprKind().getKind().equals(CelExpr.ExprKind.Kind.IDENT)
        && target.ident().name().equals(CEL_NAMESPACE);
  }

  CelBindingsExtensions() {}
}
