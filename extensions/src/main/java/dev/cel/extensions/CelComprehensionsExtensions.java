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
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import dev.cel.checker.CelCheckerBuilder;
import dev.cel.common.CelFunctionDecl;
import dev.cel.common.CelIssue;
import dev.cel.common.CelOptions;
import dev.cel.common.CelOverloadDecl;
import dev.cel.common.Operator;
import dev.cel.common.ast.CelExpr;
import dev.cel.common.types.MapType;
import dev.cel.common.types.TypeParamType;
import dev.cel.common.values.MutableMapValue;
import dev.cel.compiler.CelCompilerLibrary;
import dev.cel.parser.CelMacro;
import dev.cel.parser.CelMacroExprFactory;
import dev.cel.parser.CelParserBuilder;
import dev.cel.runtime.CelFunctionBinding;
import dev.cel.runtime.CelInternalRuntimeLibrary;
import dev.cel.runtime.CelRuntimeBuilder;
import dev.cel.runtime.RuntimeEquality;
import java.util.Map;
import java.util.Optional;

/** Internal implementation of CEL two variable comprehensions extensions. */
public final class CelComprehensionsExtensions
    implements CelCompilerLibrary, CelInternalRuntimeLibrary, CelExtensionLibrary.FeatureSet {

  private static final String MAP_INSERT_FUNCTION = "cel.@mapInsert";
  private static final String MAP_INSERT_OVERLOAD_MAP_MAP = "cel_@mapInsert_map_map";
  private static final String MAP_INSERT_OVERLOAD_KEY_VALUE = "cel_@mapInsert_map_key_value";
  private static final TypeParamType TYPE_PARAM_K = TypeParamType.create("K");
  private static final TypeParamType TYPE_PARAM_V = TypeParamType.create("V");
  private static final MapType MAP_KV_TYPE = MapType.create(TYPE_PARAM_K, TYPE_PARAM_V);

  /** Enumeration of functions for Comprehensions extension. */
  public enum Function {
    MAP_INSERT(
        CelFunctionDecl.newFunctionDeclaration(
            MAP_INSERT_FUNCTION,
            CelOverloadDecl.newGlobalOverload(
                MAP_INSERT_OVERLOAD_MAP_MAP,
                "Returns a map that's the result of merging given two maps.",
                MAP_KV_TYPE,
                MAP_KV_TYPE,
                MAP_KV_TYPE),
            CelOverloadDecl.newGlobalOverload(
                MAP_INSERT_OVERLOAD_KEY_VALUE,
                "Adds the given key-value pair to the map.",
                MAP_KV_TYPE,
                MAP_KV_TYPE,
                TYPE_PARAM_K,
                TYPE_PARAM_V)));

    private final CelFunctionDecl functionDecl;

    public CelFunctionDecl functionDecl() {
      return functionDecl;
    }

    String getFunction() {
      return functionDecl.name();
    }

    Function(CelFunctionDecl functionDecl) {
      this.functionDecl = functionDecl;
    }
  }

  private static final class Library implements CelExtensionLibrary<CelComprehensionsExtensions> {
    private final CelComprehensionsExtensions version0;

    Library() {
      version0 = new CelComprehensionsExtensions();
    }

    @Override
    public String name() {
      return "comprehensions";
    }

    @Override
    public ImmutableSet<CelComprehensionsExtensions> versions() {
      return ImmutableSet.of(version0);
    }
  }

  private static final Library LIBRARY = new Library();

  static CelExtensionLibrary<CelComprehensionsExtensions> library() {
    return LIBRARY;
  }

  private final ImmutableSet<Function> functions;

  CelComprehensionsExtensions() {
    this.functions = ImmutableSet.of(Function.MAP_INSERT);
  }

  @Override
  public void setCheckerOptions(CelCheckerBuilder checkerBuilder) {
    functions.forEach(function -> checkerBuilder.addFunctionDeclarations(function.functionDecl));
  }

  @Override
  public void setRuntimeOptions(CelRuntimeBuilder runtimeBuilder) {
    throw new UnsupportedOperationException("Unsupported");
  }

  @Override
  public void setRuntimeOptions(
      CelRuntimeBuilder runtimeBuilder, RuntimeEquality runtimeEquality, CelOptions celOptions) {
    runtimeBuilder.addFunctionBindings(
        CelFunctionBinding.fromOverloads(
            MAP_INSERT_FUNCTION,
            CelFunctionBinding.from(
                MAP_INSERT_OVERLOAD_MAP_MAP,
                Map.class,
                Map.class,
                (map1, map2) -> mapInsertMap(map1, map2, runtimeEquality)),
            CelFunctionBinding.from(
                MAP_INSERT_OVERLOAD_KEY_VALUE,
                ImmutableList.of(Map.class, Object.class, Object.class),
                args -> mapInsertKeyValue(args, runtimeEquality))));
  }

  @Override
  public int version() {
    return 0;
  }

  @Override
  public ImmutableSet<CelMacro> macros() {
    return ImmutableSet.of(
        CelMacro.newReceiverMacro(
            Operator.ALL.getFunction(), 3, CelComprehensionsExtensions::expandAllMacro),
        CelMacro.newReceiverMacro(
            Operator.EXISTS.getFunction(), 3, CelComprehensionsExtensions::expandExistsMacro),
        CelMacro.newReceiverMacro(
            Operator.EXISTS_ONE.getFunction(),
            3,
            CelComprehensionsExtensions::expandExistsOneMacro),
        CelMacro.newReceiverMacro(
            Operator.EXISTS_ONE_NEW.getFunction(),
            3,
            CelComprehensionsExtensions::expandExistsOneMacro),
        CelMacro.newReceiverMacro(
            "transformList", 3, CelComprehensionsExtensions::transformListMacro),
        CelMacro.newReceiverMacro(
            "transformList", 4, CelComprehensionsExtensions::transformListMacro),
        CelMacro.newReceiverMacro(
            "transformMap", 3, CelComprehensionsExtensions::transformMapMacro),
        CelMacro.newReceiverMacro(
            "transformMap", 4, CelComprehensionsExtensions::transformMapMacro),
        CelMacro.newReceiverMacro(
            "transformMapEntry", 3, CelComprehensionsExtensions::transformMapEntryMacro),
        CelMacro.newReceiverMacro(
            "transformMapEntry", 4, CelComprehensionsExtensions::transformMapEntryMacro));
  }

  @Override
  public void setParserOptions(CelParserBuilder parserBuilder) {
    parserBuilder.addMacros(macros());
  }

  private static Map<Object, Object> mapInsertMap(
      Map<?, ?> targetMap, Map<?, ?> mapToMerge, RuntimeEquality equality) {
    for (Object key : mapToMerge.keySet()) {
      checkArgument(
          !equality.findInMap(targetMap, key).isPresent(),
          "insert failed: key '%s' already exists",
          key);
    }

    if (targetMap instanceof MutableMapValue) {
      MutableMapValue wrapper = (MutableMapValue) targetMap;
      wrapper.putAll(mapToMerge);
      return wrapper;
    }

    return ImmutableMap.builderWithExpectedSize(targetMap.size() + mapToMerge.size())
        .putAll(targetMap)
        .putAll(mapToMerge)
        .buildOrThrow();
  }

  private static Map<Object, Object> mapInsertKeyValue(Object[] args, RuntimeEquality equality) {
    Map<?, ?> mapArg = (Map<?, ?>) args[0];
    Object key = args[1];
    Object value = args[2];

    checkArgument(
        !equality.findInMap(mapArg, key).isPresent(),
        "insert failed: key '%s' already exists",
        key);

    if (mapArg instanceof MutableMapValue) {
      MutableMapValue mutableMap = (MutableMapValue) mapArg;
      mutableMap.put(key, value);
      return mutableMap;
    }

    ImmutableMap.Builder<Object, Object> builder =
        ImmutableMap.builderWithExpectedSize(mapArg.size() + 1);
    return builder.put(key, value).putAll(mapArg).buildOrThrow();
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
    } else if (arg.exprKind().ident().name().equals("__result__")) {
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
