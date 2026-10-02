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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.collect.ImmutableSet.toImmutableSet;

import com.google.common.base.Ascii;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.errorprone.annotations.Immutable;
import dev.cel.checker.CelCheckerBuilder;
import dev.cel.common.CelFunctionDecl;
import dev.cel.common.CelIssue;
import dev.cel.common.CelOverloadDecl;
import dev.cel.common.Operator;
import dev.cel.common.ast.CelExpr;
import dev.cel.common.types.CelType;
import dev.cel.common.types.ListType;
import dev.cel.common.types.SimpleType;
import dev.cel.common.types.TypeParamType;
import dev.cel.compiler.CelCompilerLibrary;
import dev.cel.parser.CelMacro;
import dev.cel.parser.CelMacroExprFactory;
import dev.cel.parser.CelParserBuilder;
import java.util.Optional;
import java.util.Set;

/** Internal implementation of CEL lists compile-time extensions. */
@Immutable
public final class CelListsCompilerLibrary
    implements CelCompilerLibrary, CelExtensionLibrary.FeatureSet {

  private static final String UNUSED_ITER_VAR = "#unused";
  private static final String SORT_BY_INPUT_VAR = "@__sortBy_input__";

  /** Supported functions for Lists extension library. */
  public enum Function {
    SLICE(
        CelFunctionDecl.newFunctionDeclaration(
            "slice",
            CelOverloadDecl.newMemberOverload(
                "list_slice",
                "Returns a new sub-list using the indices provided",
                ListType.create(TypeParamType.create("T")),
                ListType.create(TypeParamType.create("T")),
                SimpleType.INT,
                SimpleType.INT))),
    FLATTEN(
        CelFunctionDecl.newFunctionDeclaration(
            "flatten",
            CelOverloadDecl.newMemberOverload(
                "list_flatten",
                "Flattens a list by a single level",
                ListType.create(TypeParamType.create("T")),
                ListType.create(ListType.create(TypeParamType.create("T")))),
            CelOverloadDecl.newMemberOverload(
                "list_flatten_list_int",
                "Flattens a list to the specified level. A negative depth value flattens the list"
                    + " recursively to its deepest level.",
                ListType.create(SimpleType.DYN),
                ListType.create(SimpleType.DYN),
                SimpleType.INT))),
    RANGE(
        CelFunctionDecl.newFunctionDeclaration(
            "lists.range",
            CelOverloadDecl.newGlobalOverload(
                "lists_range",
                "Returns a list of integers from 0 to n-1.",
                ListType.create(SimpleType.INT),
                SimpleType.INT))),
    DISTINCT(
        CelFunctionDecl.newFunctionDeclaration(
            "distinct",
            CelOverloadDecl.newMemberOverload(
                "list_distinct",
                "Returns the distinct elements of a list",
                ListType.create(TypeParamType.create("T")),
                ListType.create(TypeParamType.create("T"))))),
    REVERSE(
        CelFunctionDecl.newFunctionDeclaration(
            "reverse",
            CelOverloadDecl.newMemberOverload(
                "list_reverse",
                "Returns the elements of a list in reverse order",
                ListType.create(TypeParamType.create("T")),
                ListType.create(TypeParamType.create("T"))))),
    SORT(
        CelFunctionDecl.newFunctionDeclaration(
            "sort",
            CelOverloadDecl.newMemberOverload(
                "list_sort",
                "Sorts a list with comparable elements.",
                ListType.create(TypeParamType.create("T")),
                ListType.create(TypeParamType.create("T"))))),
    SORT_BY(createSortByFunctionDecl(comparableSortKeyTypes()));

    private static ImmutableList<CelType> comparableSortKeyTypes() {
      return ImmutableList.of(
          SimpleType.INT,
          SimpleType.UINT,
          SimpleType.DOUBLE,
          SimpleType.BOOL,
          SimpleType.STRING,
          SimpleType.BYTES,
          SimpleType.DURATION,
          SimpleType.TIMESTAMP);
    }

    private static CelFunctionDecl createSortByFunctionDecl(ImmutableList<CelType> keyTypes) {
      ImmutableList.Builder<CelOverloadDecl> overloads = ImmutableList.builder();
      for (CelType type : keyTypes) {
        String typeName = Ascii.toLowerCase(type.kind().name());
        overloads.add(
            CelOverloadDecl.newMemberOverload(
                String.format("list_%s_sortByAssociatedKeys", typeName),
                "Sorts a list by an associated list of keys. Used by the 'sortBy' macro",
                ListType.create(TypeParamType.create("T")),
                ListType.create(TypeParamType.create("T")),
                ListType.create(type)));
      }
      return CelFunctionDecl.newFunctionDeclaration("@sortByAssociatedKeys", overloads.build());
    }

    private final CelFunctionDecl functionDecl;

    public String getFunction() {
      return functionDecl.name();
    }

    public CelFunctionDecl getFunctionDecl() {
      return functionDecl;
    }

    Function(CelFunctionDecl functionDecl) {
      this.functionDecl = functionDecl;
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

  private static final class Library implements CelExtensionLibrary<CelListsCompilerLibrary> {
    private final CelListsCompilerLibrary version0 =
        new CelListsCompilerLibrary(0, getFunctionsForVersion(0));
    private final CelListsCompilerLibrary version1 =
        new CelListsCompilerLibrary(1, getFunctionsForVersion(1));
    private final CelListsCompilerLibrary version2 =
        new CelListsCompilerLibrary(2, getFunctionsForVersion(2));

    @Override
    public String name() {
      return "lists";
    }

    @Override
    public ImmutableSet<CelListsCompilerLibrary> versions() {
      return ImmutableSet.of(version0, version1, version2);
    }
  }

  private static final Library LIBRARY = new Library();

  public static CelExtensionLibrary<CelListsCompilerLibrary> library() {
    return LIBRARY;
  }

  /** Returns the latest version of the 'lists' compiler extension. */
  public static CelListsCompilerLibrary lists() {
    return library().latest();
  }

  /** Returns the specified version of the 'lists' compiler extension. */
  public static CelListsCompilerLibrary lists(int version) {
    return library().version(version);
  }

  /** Returns the 'lists' compiler extension with only the specified functions. */
  public static CelListsCompilerLibrary lists(Function... functions) {
    return lists(ImmutableSet.copyOf(functions));
  }

  /** Returns the 'lists' compiler extension with only the specified functions. */
  public static CelListsCompilerLibrary lists(Set<Function> functions) {
    return new CelListsCompilerLibrary(functions);
  }

  private final ImmutableSet<Function> functions;
  private final int version;

  CelListsCompilerLibrary(Set<Function> functions) {
    this(-1, functions);
  }

  private CelListsCompilerLibrary(int version, Set<Function> functions) {
    this.version = version;
    this.functions = ImmutableSet.copyOf(functions);
  }

  @Override
  public int version() {
    return version;
  }

  @Override
  public ImmutableSet<CelFunctionDecl> functions() {
    return functions.stream().map(Function::getFunctionDecl).collect(toImmutableSet());
  }

  @Override
  public ImmutableSet<CelMacro> macros() {
    if (functions.contains(Function.SORT_BY)) {
      return ImmutableSet.of(
          CelMacro.newReceiverMacro("sortBy", 2, CelListsCompilerLibrary::sortByMacro));
    }
    return ImmutableSet.of();
  }

  @Override
  public void setParserOptions(CelParserBuilder parserBuilder) {
    parserBuilder.addMacros(macros());
  }

  @Override
  public void setCheckerOptions(CelCheckerBuilder checkerBuilder) {
    functions.forEach(function -> checkerBuilder.addFunctionDeclarations(function.functionDecl));
  }

  private static Optional<CelExpr> sortByMacro(
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
                  "sortBy(var, ...) variable name must be a simple identifier")));
    }

    String varName = varIdent.ident().name();
    CelExpr sortKeyExpr = checkNotNull(arguments.get(1));

    // Build map comprehension: @__sortBy_input__.map(varName, sortKeyExpr)
    CelExpr targetIdent = exprFactory.newIdentifier(SORT_BY_INPUT_VAR);
    CelExpr mapStep =
        exprFactory.newGlobalCall(
            Operator.ADD.getFunction(),
            exprFactory.newIdentifier(exprFactory.getAccumulatorVarName()),
            exprFactory.newList(sortKeyExpr));
    CelExpr mapCompr =
        exprFactory.fold(
            varName,
            targetIdent,
            exprFactory.getAccumulatorVarName(),
            exprFactory.newList(),
            exprFactory.newBoolLiteral(true),
            mapStep,
            exprFactory.newIdentifier(exprFactory.getAccumulatorVarName()));

    // Build call: @__sortBy_input__.@sortByAssociatedKeys(mapCompr)
    CelExpr callExpr =
        exprFactory.newReceiverCall(
            Function.SORT_BY.getFunction(), exprFactory.newIdentifier(SORT_BY_INPUT_VAR), mapCompr);

    // Build bind: cel.bind(@__sortBy_input__, target, callExpr)
    CelExpr bindExpr =
        exprFactory.fold(
            UNUSED_ITER_VAR,
            exprFactory.newList(),
            SORT_BY_INPUT_VAR,
            target,
            exprFactory.newBoolLiteral(false),
            exprFactory.newIdentifier(SORT_BY_INPUT_VAR),
            callExpr);

    return Optional.of(bindExpr);
  }
}
