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

package dev.cel.extensions;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static dev.cel.extensions.CelOptionalCompilerLibrary.Function.HAS_VALUE;
import static dev.cel.extensions.CelOptionalCompilerLibrary.Function.OPTIONAL_NONE;
import static dev.cel.extensions.CelOptionalCompilerLibrary.Function.OPTIONAL_OF;
import static dev.cel.extensions.CelOptionalCompilerLibrary.Function.OPTIONAL_OF_NON_ZERO_VALUE;
import static dev.cel.extensions.CelOptionalCompilerLibrary.Function.OPTIONAL_UNWRAP;
import static dev.cel.extensions.CelOptionalCompilerLibrary.Function.OR;
import static dev.cel.extensions.CelOptionalCompilerLibrary.Function.OR_VALUE;
import static dev.cel.extensions.CelOptionalCompilerLibrary.Function.VALUE;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.errorprone.annotations.Immutable;
import dev.cel.checker.CelCheckerBuilder;
import dev.cel.common.CelFunctionDecl;
import dev.cel.common.CelIssue;
import dev.cel.common.CelOverloadDecl;
import dev.cel.common.CelVarDecl;
import dev.cel.common.Operator;
import dev.cel.common.ast.CelExpr;
import dev.cel.common.types.ListType;
import dev.cel.common.types.MapType;
import dev.cel.common.types.OptionalType;
import dev.cel.common.types.SimpleType;
import dev.cel.common.types.TypeParamType;
import dev.cel.common.types.TypeType;
import dev.cel.compiler.CelCompilerLibrary;
import dev.cel.parser.CelMacro;
import dev.cel.parser.CelMacroExprFactory;
import dev.cel.parser.CelParserBuilder;
import java.util.Optional;
import java.util.Set;

/** Internal implementation of CEL optional compile-time extensions. */
@Immutable
public final class CelOptionalCompilerLibrary
    implements CelCompilerLibrary, CelExtensionLibrary.FeatureSet {

  private static final class Types {
    private static final TypeParamType PARAM_TYPE_K = TypeParamType.create("K");
    private static final TypeParamType PARAM_TYPE_V = TypeParamType.create("V");
    private static final OptionalType OPTIONAL_TYPE_V = OptionalType.create(PARAM_TYPE_V);
    private static final ListType LIST_TYPE_V = ListType.create(PARAM_TYPE_V);
    private static final MapType MAP_TYPE_KV = MapType.create(PARAM_TYPE_K, PARAM_TYPE_V);
  }

  /** Declarations for the optional extension library. */
  public enum OptionalDeclaration implements CelFunctionDecl.Declarer {
    OPTIONAL_OF(
        CelFunctionDecl.newFunctionDeclaration(
            "optional.of",
            CelOverloadDecl.newGlobalOverload(
                "optional_of", Types.OPTIONAL_TYPE_V, Types.PARAM_TYPE_V))),
    OPTIONAL_OF_NON_ZERO_VALUE(
        CelFunctionDecl.newFunctionDeclaration(
            "optional.ofNonZeroValue",
            CelOverloadDecl.newGlobalOverload(
                "optional_ofNonZeroValue", Types.OPTIONAL_TYPE_V, Types.PARAM_TYPE_V))),
    OPTIONAL_NONE(
        CelFunctionDecl.newFunctionDeclaration(
            "optional.none",
            CelOverloadDecl.newGlobalOverload("optional_none", Types.OPTIONAL_TYPE_V))),
    OPTIONAL_VALUE(
        CelFunctionDecl.newFunctionDeclaration(
            "value",
            CelOverloadDecl.newMemberOverload(
                "optional_value", Types.PARAM_TYPE_V, Types.OPTIONAL_TYPE_V))),
    OPTIONAL_HAS_VALUE(
        CelFunctionDecl.newFunctionDeclaration(
            "hasValue",
            CelOverloadDecl.newMemberOverload(
                "optional_hasValue", SimpleType.BOOL, Types.OPTIONAL_TYPE_V))),
    OPTIONAL_UNWRAP(
        CelFunctionDecl.newFunctionDeclaration(
            "optional.unwrap",
            CelOverloadDecl.newGlobalOverload(
                "optional_unwrap_list",
                Types.LIST_TYPE_V,
                ListType.create(Types.OPTIONAL_TYPE_V)))),
    OPTIONAL_OR(
        CelFunctionDecl.newFunctionDeclaration(
            "or",
            CelOverloadDecl.newMemberOverload(
                "optional_or_optional",
                Types.OPTIONAL_TYPE_V,
                Types.OPTIONAL_TYPE_V,
                Types.OPTIONAL_TYPE_V))),
    OPTIONAL_OR_VALUE(
        CelFunctionDecl.newFunctionDeclaration(
            "orValue",
            CelOverloadDecl.newMemberOverload(
                "optional_orValue_value",
                Types.PARAM_TYPE_V,
                Types.OPTIONAL_TYPE_V,
                Types.PARAM_TYPE_V))),
    OPTIONAL_SELECT(
        CelFunctionDecl.newFunctionDeclaration(
            Operator.OPTIONAL_SELECT.getFunction(),
            CelOverloadDecl.newGlobalOverload(
                "select_optional_field",
                Types.OPTIONAL_TYPE_V,
                SimpleType.DYN,
                SimpleType.STRING))),
    OPTIONAL_INDEX(
        CelFunctionDecl.newFunctionDeclaration(
            Operator.OPTIONAL_INDEX.getFunction(),
            CelOverloadDecl.newGlobalOverload(
                "list_optindex_optional_int",
                Types.OPTIONAL_TYPE_V,
                Types.LIST_TYPE_V,
                SimpleType.INT),
            CelOverloadDecl.newGlobalOverload(
                "optional_list_optindex_optional_int",
                Types.OPTIONAL_TYPE_V,
                OptionalType.create(Types.LIST_TYPE_V),
                SimpleType.INT),
            CelOverloadDecl.newGlobalOverload(
                "map_optindex_optional_value",
                Types.OPTIONAL_TYPE_V,
                Types.MAP_TYPE_KV,
                Types.PARAM_TYPE_K),
            CelOverloadDecl.newGlobalOverload(
                "optional_map_optindex_optional_value",
                Types.OPTIONAL_TYPE_V,
                OptionalType.create(Types.MAP_TYPE_KV),
                Types.PARAM_TYPE_K))),
    OPTIONAL_INDEX_OPERAND(
        CelFunctionDecl.newFunctionDeclaration(
            Operator.INDEX.getFunction(),
            CelOverloadDecl.newGlobalOverload(
                "optional_list_index_int",
                Types.OPTIONAL_TYPE_V,
                OptionalType.create(Types.LIST_TYPE_V),
                SimpleType.INT),
            CelOverloadDecl.newGlobalOverload(
                "optional_map_index_value",
                Types.OPTIONAL_TYPE_V,
                OptionalType.create(Types.MAP_TYPE_KV),
                Types.PARAM_TYPE_K)));

    private final CelFunctionDecl celFunctionDecl;

    OptionalDeclaration(CelFunctionDecl celFunctionDecl) {
      this.celFunctionDecl = celFunctionDecl;
    }

    @Override
    public CelFunctionDecl functionDecl() {
      return celFunctionDecl;
    }
  }

  /** Enumerations of function names used for supporting optionals. */
  public enum Function {
    VALUE(OptionalDeclaration.OPTIONAL_VALUE.functionDecl()),
    HAS_VALUE(OptionalDeclaration.OPTIONAL_HAS_VALUE.functionDecl()),
    OPTIONAL_NONE(OptionalDeclaration.OPTIONAL_NONE.functionDecl()),
    OPTIONAL_OF(OptionalDeclaration.OPTIONAL_OF.functionDecl()),
    OPTIONAL_UNWRAP(OptionalDeclaration.OPTIONAL_UNWRAP.functionDecl()),
    OPTIONAL_OF_NON_ZERO_VALUE(OptionalDeclaration.OPTIONAL_OF_NON_ZERO_VALUE.functionDecl()),
    OR(OptionalDeclaration.OPTIONAL_OR.functionDecl()),
    OR_VALUE(OptionalDeclaration.OPTIONAL_OR_VALUE.functionDecl()),
    FIRST(
        CelFunctionDecl.newFunctionDeclaration(
            "first",
            CelOverloadDecl.newMemberOverload(
                "optional_list_first",
                "Return the first value in a list if present, otherwise optional.none()",
                Types.OPTIONAL_TYPE_V,
                Types.LIST_TYPE_V))),
    LAST(
        CelFunctionDecl.newFunctionDeclaration(
            "last",
            CelOverloadDecl.newMemberOverload(
                "optional_list_last",
                "Return the last value in a list if present, otherwise optional.none()",
                Types.OPTIONAL_TYPE_V,
                Types.LIST_TYPE_V)));

    private final CelFunctionDecl functionDecl;

    public String getFunction() {
      return functionDecl.name();
    }

    CelFunctionDecl getFunctionDecl() {
      return functionDecl;
    }

    Function(CelFunctionDecl functionDecl) {
      this.functionDecl = functionDecl;
    }
  }

  private static final String UNUSED_ITER_VAR = "#unused";
  private static final String OPTIONAL_MAP_VAR = "@target";

  private static final ImmutableSet<CelFunctionDecl> OPERATOR_FUNCTION_DECLS =
      ImmutableSet.of(
          OptionalDeclaration.OPTIONAL_SELECT.functionDecl(),
          OptionalDeclaration.OPTIONAL_INDEX.functionDecl(),
          OptionalDeclaration.OPTIONAL_INDEX_OPERAND.functionDecl());

  private static final class Library implements CelExtensionLibrary<CelOptionalCompilerLibrary> {
    private final CelOptionalCompilerLibrary version0 =
        new CelOptionalCompilerLibrary(
            0,
            ImmutableSet.of(
                OPTIONAL_OF,
                OPTIONAL_OF_NON_ZERO_VALUE,
                OPTIONAL_NONE,
                VALUE,
                HAS_VALUE,
                OPTIONAL_UNWRAP,
                OR,
                OR_VALUE),
            ImmutableSet.of(
                CelMacro.newReceiverMacro("optMap", 2, CelOptionalCompilerLibrary::expandOptMap)),
            ImmutableSet.of(
                CelVarDecl.newVarDeclaration(
                    OptionalType.NAME, TypeType.create(Types.OPTIONAL_TYPE_V))));

    private final CelOptionalCompilerLibrary version1 =
        new CelOptionalCompilerLibrary(
            1,
            ImmutableSet.of(
                OPTIONAL_OF,
                OPTIONAL_OF_NON_ZERO_VALUE,
                OPTIONAL_NONE,
                VALUE,
                HAS_VALUE,
                OPTIONAL_UNWRAP,
                OR,
                OR_VALUE),
            ImmutableSet.<CelMacro>builder()
                .addAll(version0.macros)
                .add(
                    CelMacro.newReceiverMacro(
                        "optFlatMap", 2, CelOptionalCompilerLibrary::expandOptFlatMap))
                .build(),
            version0.variables);

    private final CelOptionalCompilerLibrary version2 =
        new CelOptionalCompilerLibrary(
            2, ImmutableSet.copyOf(Function.values()), version1.macros, version1.variables);

    @Override
    public String name() {
      return "optional";
    }

    @Override
    public ImmutableSet<CelOptionalCompilerLibrary> versions() {
      return ImmutableSet.of(version0, version1, version2);
    }
  }

  private static final Library LIBRARY = new Library();

  public static CelExtensionLibrary<CelOptionalCompilerLibrary> library() {
    return LIBRARY;
  }

  /** Returns the latest version of the 'optional' compiler extension. */
  public static CelOptionalCompilerLibrary optional() {
    return library().latest();
  }

  /** Returns the specified version of the 'optional' compiler extension. */
  public static CelOptionalCompilerLibrary optional(int version) {
    return library().version(version);
  }

  /** Returns the 'optional' compiler extension with only the specified functions. */
  public static CelOptionalCompilerLibrary optional(Function... functions) {
    return optional(ImmutableSet.copyOf(functions));
  }

  /** Returns the 'optional' compiler extension with only the specified functions. */
  public static CelOptionalCompilerLibrary optional(Set<Function> functions) {
    return new CelOptionalCompilerLibrary(functions);
  }

  private final int version;
  private final ImmutableSet<CelFunctionDecl> functions;
  private final ImmutableSet<CelMacro> macros;
  private final ImmutableSet<CelVarDecl> variables;

  CelOptionalCompilerLibrary(Set<Function> functions) {
    this(
        -1,
        functions,
        ImmutableSet.of(
            CelMacro.newReceiverMacro("optMap", 2, CelOptionalCompilerLibrary::expandOptMap),
            CelMacro.newReceiverMacro(
                "optFlatMap", 2, CelOptionalCompilerLibrary::expandOptFlatMap)),
        ImmutableSet.of(
            CelVarDecl.newVarDeclaration(
                OptionalType.NAME, TypeType.create(Types.OPTIONAL_TYPE_V))));
  }

  private CelOptionalCompilerLibrary(
      int version, Set<Function> functions, Set<CelMacro> macros, Set<CelVarDecl> variables) {
    this.version = version;
    this.functions =
        ImmutableSet.<CelFunctionDecl>builder()
            .addAll(functions.stream().map(Function::getFunctionDecl).collect(toImmutableSet()))
            .addAll(OPERATOR_FUNCTION_DECLS)
            .build();
    this.macros = ImmutableSet.copyOf(macros);
    this.variables = ImmutableSet.copyOf(variables);
  }

  @Override
  public int version() {
    return version;
  }

  @Override
  public ImmutableSet<CelFunctionDecl> functions() {
    return functions;
  }

  @Override
  public ImmutableSet<CelVarDecl> variables() {
    return variables;
  }

  @Override
  public ImmutableSet<CelMacro> macros() {
    return macros;
  }

  @Override
  public void setParserOptions(CelParserBuilder parserBuilder) {
    if (!parserBuilder.getOptions().enableOptionalSyntax()) {
      // Enable optional syntax by default if the optional library has been added to the
      // environment
      parserBuilder.setOptions(
          parserBuilder.getOptions().toBuilder().enableOptionalSyntax(true).build());
    }
    parserBuilder.addMacros(macros());
  }

  @Override
  public void setCheckerOptions(CelCheckerBuilder checkerBuilder) {
    checkerBuilder.addVarDeclarations(variables());
    checkerBuilder.addFunctionDeclarations(functions());
  }

  private static Optional<CelExpr> expandOptMap(
      CelMacroExprFactory exprFactory, CelExpr target, ImmutableList<CelExpr> arguments) {
    checkNotNull(exprFactory);
    checkNotNull(target);
    checkArgument(arguments.size() == 2);

    CelExpr varIdent = checkNotNull(arguments.get(0));
    if (varIdent.exprKind().getKind() != CelExpr.ExprKind.Kind.IDENT) {
      return Optional.of(
          exprFactory.reportError(
              CelIssue.formatError(
                  exprFactory.getSourceLocation(varIdent),
                  "optMap() variable name must be a simple identifier")));
    }
    CelExpr mapExpr = checkNotNull(arguments.get(1));
    String varName = varIdent.ident().name();

    if (target.exprKind().getKind() == CelExpr.ExprKind.Kind.IDENT) {
      return Optional.of(
          exprFactory.newGlobalCall(
              Operator.CONDITIONAL.getFunction(),
              exprFactory.newReceiverCall(HAS_VALUE.getFunction(), target),
              exprFactory.newGlobalCall(
                  OPTIONAL_OF.getFunction(),
                  exprFactory.fold(
                      UNUSED_ITER_VAR,
                      exprFactory.newList(),
                      varName,
                      exprFactory.newReceiverCall(VALUE.getFunction(), exprFactory.copy(target)),
                      exprFactory.newBoolLiteral(true),
                      exprFactory.newIdentifier(varName),
                      mapExpr)),
              exprFactory.newGlobalCall(OPTIONAL_NONE.getFunction())));
    }

    CelExpr localVar = exprFactory.newIdentifier(OPTIONAL_MAP_VAR);
    CelExpr localVarCopy = exprFactory.copy(localVar);
    CelExpr conditionalExpr =
        exprFactory.newGlobalCall(
            Operator.CONDITIONAL.getFunction(),
            exprFactory.newReceiverCall(HAS_VALUE.getFunction(), localVar),
            exprFactory.newGlobalCall(
                OPTIONAL_OF.getFunction(),
                exprFactory.fold(
                    UNUSED_ITER_VAR,
                    exprFactory.newList(),
                    varName,
                    exprFactory.newReceiverCall(VALUE.getFunction(), localVarCopy),
                    exprFactory.newBoolLiteral(true),
                    exprFactory.newIdentifier(varName),
                    mapExpr)),
            exprFactory.newGlobalCall(OPTIONAL_NONE.getFunction()));

    return Optional.of(
        exprFactory.fold(
            UNUSED_ITER_VAR,
            exprFactory.newList(),
            OPTIONAL_MAP_VAR,
            target,
            exprFactory.newBoolLiteral(false),
            exprFactory.newIdentifier(OPTIONAL_MAP_VAR),
            conditionalExpr));
  }

  private static Optional<CelExpr> expandOptFlatMap(
      CelMacroExprFactory exprFactory, CelExpr target, ImmutableList<CelExpr> arguments) {
    checkNotNull(exprFactory);
    checkNotNull(target);
    checkArgument(arguments.size() == 2);

    CelExpr varIdent = checkNotNull(arguments.get(0));
    if (varIdent.exprKind().getKind() != CelExpr.ExprKind.Kind.IDENT) {
      return Optional.of(
          exprFactory.reportError(
              CelIssue.formatError(
                  exprFactory.getSourceLocation(varIdent),
                  "optFlatMap() variable name must be a simple identifier")));
    }
    CelExpr mapExpr = checkNotNull(arguments.get(1));
    String varName = varIdent.ident().name();

    if (target.exprKind().getKind() == CelExpr.ExprKind.Kind.IDENT) {
      return Optional.of(
          exprFactory.newGlobalCall(
              Operator.CONDITIONAL.getFunction(),
              exprFactory.newReceiverCall(HAS_VALUE.getFunction(), target),
              exprFactory.fold(
                  UNUSED_ITER_VAR,
                  exprFactory.newList(),
                  varName,
                  exprFactory.newReceiverCall(VALUE.getFunction(), exprFactory.copy(target)),
                  exprFactory.newBoolLiteral(true),
                  exprFactory.newIdentifier(varName),
                  mapExpr),
              exprFactory.newGlobalCall(OPTIONAL_NONE.getFunction())));
    }

    CelExpr localVar = exprFactory.newIdentifier(OPTIONAL_MAP_VAR);
    CelExpr localVarCopy = exprFactory.copy(localVar);
    CelExpr conditionalExpr =
        exprFactory.newGlobalCall(
            Operator.CONDITIONAL.getFunction(),
            exprFactory.newReceiverCall(HAS_VALUE.getFunction(), localVar),
            exprFactory.fold(
                UNUSED_ITER_VAR,
                exprFactory.newList(),
                varName,
                exprFactory.newReceiverCall(VALUE.getFunction(), localVarCopy),
                exprFactory.newBoolLiteral(true),
                exprFactory.newIdentifier(varName),
                mapExpr),
            exprFactory.newGlobalCall(OPTIONAL_NONE.getFunction()));

    return Optional.of(
        exprFactory.fold(
            UNUSED_ITER_VAR,
            exprFactory.newList(),
            OPTIONAL_MAP_VAR,
            target,
            exprFactory.newBoolLiteral(false),
            exprFactory.newIdentifier(OPTIONAL_MAP_VAR),
            conditionalExpr));
  }
}
