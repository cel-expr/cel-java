// Copyright 2025 Google LLC
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
import dev.cel.checker.CelCheckerBuilder;
import dev.cel.common.CelFunctionDecl;
import dev.cel.common.CelIssue;
import dev.cel.common.CelOverloadDecl;
import dev.cel.common.Operator;
import dev.cel.common.ast.CelExpr;
import dev.cel.common.types.MapType;
import dev.cel.common.types.TypeParamType;
import dev.cel.compiler.CelCompilerLibrary;
import dev.cel.parser.CelMacro;
import dev.cel.parser.CelMacroExprFactory;
import dev.cel.parser.CelParserBuilder;
import java.util.Arrays;
import java.util.Collection;
import java.util.Optional;

/** Internal implementation of CEL two variable comprehensions compile-time extensions. */
@Immutable
public final class CelComprehensionsCompilerLibrary
    implements CelCompilerLibrary, CelExtensionLibrary.FeatureSet {

  private static final String MAP_INSERT_FUNCTION = "cel.@mapInsert";
  private static final String MAP_INSERT_OVERLOAD_MAP_MAP = "cel_@mapInsert_map_map";
  private static final String MAP_INSERT_OVERLOAD_KEY_VALUE = "cel_@mapInsert_map_key_value";

  private static final class Types {
    private static final TypeParamType TYPE_PARAM_K = TypeParamType.create("K");
    private static final TypeParamType TYPE_PARAM_V = TypeParamType.create("V");
    private static final MapType MAP_KV_TYPE = MapType.create(TYPE_PARAM_K, TYPE_PARAM_V);
  }

  /** Enumeration of functions for Comprehensions compiler extension. */
  public enum Function {
    MAP_INSERT(
        CelFunctionDecl.newFunctionDeclaration(
            MAP_INSERT_FUNCTION,
            CelOverloadDecl.newGlobalOverload(
                MAP_INSERT_OVERLOAD_MAP_MAP,
                "Returns a map that's the result of merging given two maps.",
                Types.MAP_KV_TYPE,
                Types.MAP_KV_TYPE,
                Types.MAP_KV_TYPE),
            CelOverloadDecl.newGlobalOverload(
                MAP_INSERT_OVERLOAD_KEY_VALUE,
                "Adds the given key-value pair to the map.",
                Types.MAP_KV_TYPE,
                Types.MAP_KV_TYPE,
                Types.TYPE_PARAM_K,
                Types.TYPE_PARAM_V)));

    private final CelFunctionDecl functionDecl;

    public CelFunctionDecl functionDecl() {
      return functionDecl;
    }

    public CelFunctionDecl getFunctionDecl() {
      return functionDecl;
    }

    public String getFunction() {
      return functionDecl.name();
    }

    Function(CelFunctionDecl functionDecl) {
      this.functionDecl = functionDecl;
    }
  }

  private static ImmutableSet<Function> getFunctionsForVersion(int version) {
    switch (version) {
      case 0:
      case Integer.MAX_VALUE:
        return ImmutableSet.of(Function.MAP_INSERT);
      default:
        throw new IllegalArgumentException(
            "Unsupported 'comprehensions' extension version " + version);
    }
  }

  /** Returns the latest version of the 'comprehensions' compile-time extensions. */
  public static CelComprehensionsCompilerLibrary comprehensions() {
    return comprehensions(Integer.MAX_VALUE);
  }

  /** Returns the specified version of the 'comprehensions' compile-time extensions. */
  public static CelComprehensionsCompilerLibrary comprehensions(int version) {
    return new CelComprehensionsCompilerLibrary(version);
  }

  /** Returns the 'comprehensions' compile-time extensions with only the specified functions. */
  public static CelComprehensionsCompilerLibrary comprehensions(Function... functions) {
    return comprehensions(Arrays.asList(functions));
  }

  /** Returns the 'comprehensions' compile-time extensions with only the specified functions. */
  public static CelComprehensionsCompilerLibrary comprehensions(Collection<Function> functions) {
    return new CelComprehensionsCompilerLibrary(functions);
  }

  private static final class Library
      implements CelExtensionLibrary<CelComprehensionsCompilerLibrary> {
    private final CelComprehensionsCompilerLibrary version0;

    Library() {
      version0 = new CelComprehensionsCompilerLibrary(0);
    }

    @Override
    public String name() {
      return "comprehensions";
    }

    @Override
    public ImmutableSet<CelComprehensionsCompilerLibrary> versions() {
      return ImmutableSet.of(version0);
    }
  }

  private static final Library LIBRARY = new Library();

  public static CelExtensionLibrary<CelComprehensionsCompilerLibrary> library() {
    return LIBRARY;
  }

  private final int version;
  private final ImmutableSet<Function> functions;

  CelComprehensionsCompilerLibrary(int version) {
    this(version, getFunctionsForVersion(version));
  }

  CelComprehensionsCompilerLibrary(Collection<Function> functions) {
    this(-1, functions);
  }

  private CelComprehensionsCompilerLibrary(int version, Collection<Function> functions) {
    this.version = version;
    this.functions = ImmutableSet.copyOf(functions);
  }

  @Override
  public void setCheckerOptions(CelCheckerBuilder checkerBuilder) {
    functions.forEach(function -> checkerBuilder.addFunctionDeclarations(function.functionDecl()));
  }

  @Override
  public int version() {
    return version;
  }

  @Override
  public ImmutableSet<CelMacro> macros() {
    return ImmutableSet.of(
        CelMacro.newReceiverMacro(
            Operator.ALL.getFunction(), 3, CelComprehensionsCompilerLibrary::expandAllMacro),
        CelMacro.newReceiverMacro(
            Operator.EXISTS.getFunction(), 3, CelComprehensionsCompilerLibrary::expandExistsMacro),
        CelMacro.newReceiverMacro(
            Operator.EXISTS_ONE.getFunction(),
            3,
            CelComprehensionsCompilerLibrary::expandExistsOneMacro),
        CelMacro.newReceiverMacro(
            Operator.EXISTS_ONE_NEW.getFunction(),
            3,
            CelComprehensionsCompilerLibrary::expandExistsOneMacro),
        CelMacro.newReceiverMacro(
            "transformList", 3, CelComprehensionsCompilerLibrary::transformListMacro),
        CelMacro.newReceiverMacro(
            "transformList", 4, CelComprehensionsCompilerLibrary::transformListMacro),
        CelMacro.newReceiverMacro(
            "transformMap", 3, CelComprehensionsCompilerLibrary::transformMapMacro),
        CelMacro.newReceiverMacro(
            "transformMap", 4, CelComprehensionsCompilerLibrary::transformMapMacro),
        CelMacro.newReceiverMacro(
            "transformMapEntry", 3, CelComprehensionsCompilerLibrary::transformMapEntryMacro),
        CelMacro.newReceiverMacro(
            "transformMapEntry", 4, CelComprehensionsCompilerLibrary::transformMapEntryMacro));
  }

  @Override
  public void setParserOptions(CelParserBuilder parserBuilder) {
    parserBuilder.addMacros(macros());
  }

  private static Optional<CelExpr> expandAllMacro(
      CelMacroExprFactory exprFactory, CelExpr target, ImmutableList<CelExpr> arguments) {
    checkNotNull(exprFactory);
    checkNotNull(target);
    checkArgument(arguments.size() == 3);
    CelExpr arg0 = validatedIterationVariable(exprFactory, arguments.get(0));
    if (arg0.exprKind().getKind() != CelExpr.ExprKind.Kind.IDENT) {
      return Optional.of(arg0);
    }
    CelExpr arg1 = validatedIterationVariable(exprFactory, arguments.get(1));
    if (arg1.exprKind().getKind() != CelExpr.ExprKind.Kind.IDENT) {
      return Optional.of(arg1);
    }
    CelExpr arg2 = checkNotNull(arguments.get(2));
    CelExpr accuInit = exprFactory.newBoolLiteral(true);
    CelExpr condition =
        exprFactory.newGlobalCall(
            Operator.NOT_STRICTLY_FALSE.getFunction(),
            exprFactory.newIdentifier(exprFactory.getAccumulatorVarName()));
    CelExpr step =
        exprFactory.newGlobalCall(
            Operator.LOGICAL_AND.getFunction(),
            exprFactory.newIdentifier(exprFactory.getAccumulatorVarName()),
            arg2);
    CelExpr result = exprFactory.newIdentifier(exprFactory.getAccumulatorVarName());
    return Optional.of(
        exprFactory.fold(
            arg0.ident().name(),
            arg1.ident().name(),
            target,
            exprFactory.getAccumulatorVarName(),
            accuInit,
            condition,
            step,
            result));
  }

  private static Optional<CelExpr> expandExistsMacro(
      CelMacroExprFactory exprFactory, CelExpr target, ImmutableList<CelExpr> arguments) {
    checkNotNull(exprFactory);
    checkNotNull(target);
    checkArgument(arguments.size() == 3);
    CelExpr arg0 = validatedIterationVariable(exprFactory, arguments.get(0));
    if (arg0.exprKind().getKind() != CelExpr.ExprKind.Kind.IDENT) {
      return Optional.of(arg0);
    }
    CelExpr arg1 = validatedIterationVariable(exprFactory, arguments.get(1));
    if (arg1.exprKind().getKind() != CelExpr.ExprKind.Kind.IDENT) {
      return Optional.of(arg1);
    }
    CelExpr arg2 = checkNotNull(arguments.get(2));
    CelExpr accuInit = exprFactory.newBoolLiteral(false);
    CelExpr condition =
        exprFactory.newGlobalCall(
            Operator.NOT_STRICTLY_FALSE.getFunction(),
            exprFactory.newGlobalCall(
                Operator.LOGICAL_NOT.getFunction(),
                exprFactory.newIdentifier(exprFactory.getAccumulatorVarName())));
    CelExpr step =
        exprFactory.newGlobalCall(
            Operator.LOGICAL_OR.getFunction(),
            exprFactory.newIdentifier(exprFactory.getAccumulatorVarName()),
            arg2);
    CelExpr result = exprFactory.newIdentifier(exprFactory.getAccumulatorVarName());
    return Optional.of(
        exprFactory.fold(
            arg0.ident().name(),
            arg1.ident().name(),
            target,
            exprFactory.getAccumulatorVarName(),
            accuInit,
            condition,
            step,
            result));
  }

  private static Optional<CelExpr> expandExistsOneMacro(
      CelMacroExprFactory exprFactory, CelExpr target, ImmutableList<CelExpr> arguments) {
    checkNotNull(exprFactory);
    checkNotNull(target);
    checkArgument(arguments.size() == 3);
    CelExpr arg0 = validatedIterationVariable(exprFactory, arguments.get(0));
    if (arg0.exprKind().getKind() != CelExpr.ExprKind.Kind.IDENT) {
      return Optional.of(arg0);
    }
    CelExpr arg1 = validatedIterationVariable(exprFactory, arguments.get(1));
    if (arg1.exprKind().getKind() != CelExpr.ExprKind.Kind.IDENT) {
      return Optional.of(arg1);
    }
    CelExpr arg2 = checkNotNull(arguments.get(2));
    CelExpr accuInit = exprFactory.newIntLiteral(0);
    CelExpr condition = exprFactory.newBoolLiteral(true);
    CelExpr step =
        exprFactory.newGlobalCall(
            Operator.CONDITIONAL.getFunction(),
            arg2,
            exprFactory.newGlobalCall(
                Operator.ADD.getFunction(),
                exprFactory.newIdentifier(exprFactory.getAccumulatorVarName()),
                exprFactory.newIntLiteral(1)),
            exprFactory.newIdentifier(exprFactory.getAccumulatorVarName()));
    CelExpr result =
        exprFactory.newGlobalCall(
            Operator.EQUALS.getFunction(),
            exprFactory.newIdentifier(exprFactory.getAccumulatorVarName()),
            exprFactory.newIntLiteral(1));
    return Optional.of(
        exprFactory.fold(
            arg0.ident().name(),
            arg1.ident().name(),
            target,
            exprFactory.getAccumulatorVarName(),
            accuInit,
            condition,
            step,
            result));
  }

  private static Optional<CelExpr> transformListMacro(
      CelMacroExprFactory exprFactory, CelExpr target, ImmutableList<CelExpr> arguments) {
    checkNotNull(exprFactory);
    checkNotNull(target);
    checkArgument(arguments.size() == 3 || arguments.size() == 4);
    CelExpr arg0 = validatedIterationVariable(exprFactory, arguments.get(0));
    if (arg0.exprKind().getKind() != CelExpr.ExprKind.Kind.IDENT) {
      return Optional.of(arg0);
    }
    CelExpr arg1 = validatedIterationVariable(exprFactory, arguments.get(1));
    if (arg1.exprKind().getKind() != CelExpr.ExprKind.Kind.IDENT) {
      return Optional.of(arg1);
    }
    CelExpr transform;
    CelExpr filter = null;
    if (arguments.size() == 4) {
      filter = checkNotNull(arguments.get(2));
      transform = checkNotNull(arguments.get(3));
    } else {
      transform = checkNotNull(arguments.get(2));
    }
    CelExpr accuInit = exprFactory.newList();
    CelExpr condition = exprFactory.newBoolLiteral(true);
    CelExpr step =
        exprFactory.newGlobalCall(
            Operator.ADD.getFunction(),
            exprFactory.newIdentifier(exprFactory.getAccumulatorVarName()),
            exprFactory.newList(transform));
    if (filter != null) {
      step =
          exprFactory.newGlobalCall(
              Operator.CONDITIONAL.getFunction(),
              filter,
              step,
              exprFactory.newIdentifier(exprFactory.getAccumulatorVarName()));
    }
    return Optional.of(
        exprFactory.fold(
            arg0.ident().name(),
            arg1.ident().name(),
            target,
            exprFactory.getAccumulatorVarName(),
            accuInit,
            condition,
            step,
            exprFactory.newIdentifier(exprFactory.getAccumulatorVarName())));
  }

  private static Optional<CelExpr> transformMapMacro(
      CelMacroExprFactory exprFactory, CelExpr target, ImmutableList<CelExpr> arguments) {
    checkNotNull(exprFactory);
    checkNotNull(target);
    checkArgument(arguments.size() == 3 || arguments.size() == 4);
    CelExpr arg0 = validatedIterationVariable(exprFactory, arguments.get(0));
    if (arg0.exprKind().getKind() != CelExpr.ExprKind.Kind.IDENT) {
      return Optional.of(arg0);
    }
    CelExpr arg1 = validatedIterationVariable(exprFactory, arguments.get(1));
    if (arg1.exprKind().getKind() != CelExpr.ExprKind.Kind.IDENT) {
      return Optional.of(arg1);
    }
    CelExpr transform;
    CelExpr filter = null;
    if (arguments.size() == 4) {
      filter = checkNotNull(arguments.get(2));
      transform = checkNotNull(arguments.get(3));
    } else {
      transform = checkNotNull(arguments.get(2));
    }
    CelExpr accuInit = exprFactory.newMap();
    CelExpr condition = exprFactory.newBoolLiteral(true);
    CelExpr step =
        exprFactory.newGlobalCall(
            MAP_INSERT_FUNCTION,
            exprFactory.newIdentifier(exprFactory.getAccumulatorVarName()),
            arg0,
            transform);
    if (filter != null) {
      step =
          exprFactory.newGlobalCall(
              Operator.CONDITIONAL.getFunction(),
              filter,
              step,
              exprFactory.newIdentifier(exprFactory.getAccumulatorVarName()));
    }
    return Optional.of(
        exprFactory.fold(
            arg0.ident().name(),
            arg1.ident().name(),
            target,
            exprFactory.getAccumulatorVarName(),
            accuInit,
            condition,
            step,
            exprFactory.newIdentifier(exprFactory.getAccumulatorVarName())));
  }

  private static Optional<CelExpr> transformMapEntryMacro(
      CelMacroExprFactory exprFactory, CelExpr target, ImmutableList<CelExpr> arguments) {
    checkNotNull(exprFactory);
    checkNotNull(target);
    checkArgument(arguments.size() == 3 || arguments.size() == 4);
    CelExpr arg0 = validatedIterationVariable(exprFactory, arguments.get(0));
    if (arg0.exprKind().getKind() != CelExpr.ExprKind.Kind.IDENT) {
      return Optional.of(arg0);
    }
    CelExpr arg1 = validatedIterationVariable(exprFactory, arguments.get(1));
    if (arg1.exprKind().getKind() != CelExpr.ExprKind.Kind.IDENT) {
      return Optional.of(arg1);
    }
    CelExpr transform;
    CelExpr filter = null;
    if (arguments.size() == 4) {
      filter = checkNotNull(arguments.get(2));
      transform = checkNotNull(arguments.get(3));
    } else {
      transform = checkNotNull(arguments.get(2));
    }
    CelExpr accuInit = exprFactory.newMap();
    CelExpr condition = exprFactory.newBoolLiteral(true);
    CelExpr step =
        exprFactory.newGlobalCall(
            MAP_INSERT_FUNCTION,
            exprFactory.newIdentifier(exprFactory.getAccumulatorVarName()),
            transform);
    if (filter != null) {
      step =
          exprFactory.newGlobalCall(
              Operator.CONDITIONAL.getFunction(),
              filter,
              step,
              exprFactory.newIdentifier(exprFactory.getAccumulatorVarName()));
    }
    return Optional.of(
        exprFactory.fold(
            arg0.ident().name(),
            arg1.ident().name(),
            target,
            exprFactory.getAccumulatorVarName(),
            accuInit,
            condition,
            step,
            exprFactory.newIdentifier(exprFactory.getAccumulatorVarName())));
  }

  private static CelExpr validatedIterationVariable(
      CelMacroExprFactory exprFactory, CelExpr argument) {
    CelExpr arg = checkNotNull(argument);
    if (!isSimpleIdentifier(arg)) {
      return reportArgumentError(exprFactory, arg);
    } else if (arg.exprKind().ident().name().equals(exprFactory.getAccumulatorVarName())
        || arg.exprKind().ident().name().equals("__result__")) {
      return reportAccumulatorOverwriteError(exprFactory, arg);
    } else {
      return arg;
    }
  }

  private static boolean isSimpleIdentifier(CelExpr expr) {
    return expr.getKind() == CelExpr.ExprKind.Kind.IDENT
        && !expr.ident().name().isEmpty()
        && !expr.ident().name().startsWith(".");
  }

  private static CelExpr reportArgumentError(CelMacroExprFactory exprFactory, CelExpr argument) {
    return exprFactory.reportError(
        CelIssue.formatError(
            exprFactory.getSourceLocation(argument), "The argument must be a simple name"));
  }

  private static CelExpr reportAccumulatorOverwriteError(
      CelMacroExprFactory exprFactory, CelExpr argument) {
    return exprFactory.reportError(
        CelIssue.formatError(
            exprFactory.getSourceLocation(argument),
            String.format(
                "The iteration variable %s overwrites accumulator variable",
                argument.ident().name())));
  }
}
