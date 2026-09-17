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

package dev.cel.parser;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import dev.cel.common.CelIssue;
import dev.cel.common.Operator;
import dev.cel.common.ast.CelExpr;
import java.util.Optional;

/**
 * CelStandardMacro enum represents all of the macros defined as part of the CEL standard library.
 */
public enum CelStandardMacro {

  /** Field presence test macro */
  HAS(CelMacro.newGlobalMacro(Operator.HAS.getFunction(), 1, CelStandardMacro::expandHasMacro)),

  /**
   * Boolean comprehension which asserts that a predicate holds true for all elements in the input
   * range.
   */
  ALL(CelMacro.newReceiverMacro(Operator.ALL.getFunction(), 2, CelStandardMacro::expandAllMacro)),

  /**
   * Boolean comprehension which asserts that a predicate holds true for at least one element in the
   * input range.
   */
  EXISTS(
      CelMacro.newReceiverMacro(
          Operator.EXISTS.getFunction(), 2, CelStandardMacro::expandExistsMacro)),

  /**
   * Boolean comprehension which asserts that a predicate holds true for exactly one element in the
   * input range.
   */
  EXISTS_ONE(
      CelMacro.newReceiverMacro(
          Operator.EXISTS_ONE.getFunction(), 2, CelStandardMacro::expandExistsOneMacro)),

  /**
   * Boolean comprehension which asserts that a predicate holds true for exactly one element in the
   * input range.
   */
  EXISTS_ONE_NEW(
      CelMacro.newReceiverMacro(
          Operator.EXISTS_ONE_NEW.getFunction(), 2, CelStandardMacro::expandExistsOneMacro)),

  /**
   * Comprehension which applies a transform to each element in the input range and produces a list
   * of equivalent size as output.
   */
  MAP(CelMacro.newReceiverMacro(Operator.MAP.getFunction(), 2, CelStandardMacro::expandMapMacro)),

  /**
   * Comprehension which conditionally applies a transform to elements in the list which satisfy the
   * filter predicate.
   */
  MAP_FILTER(
      CelMacro.newReceiverMacro(Operator.MAP.getFunction(), 3, CelStandardMacro::expandMapMacro)),

  /**
   * Comprehension which produces a list containing elements in the input range which match the
   * filter.
   */
  FILTER(
      CelMacro.newReceiverMacro(
          Operator.FILTER.getFunction(), 2, CelStandardMacro::expandFilterMacro));

  /** Set of all standard macros supported by the CEL spec. */
  public static final ImmutableSet<CelStandardMacro> STANDARD_MACROS =
      ImmutableSet.of(HAS, ALL, EXISTS, EXISTS_ONE, MAP, MAP_FILTER, FILTER);

  private final CelMacro macro;

  CelStandardMacro(CelMacro macro) {
    this.macro = macro;
  }

  /** Returns the function name associated with the macro. */
  public String getFunction() {
    return macro.getFunction();
  }

  /** Returns the new-style {@code CelMacro} definition. */
  public CelMacro getDefinition() {
    return macro;
  }

  // CelMacroExpander implementation for CEL's has() macro.
  private static Optional<CelExpr> expandHasMacro(
      CelMacroExprFactory exprFactory, CelExpr target, ImmutableList<CelExpr> arguments) {
    checkNotNull(exprFactory);
    checkNotNull(target);
    checkArgument(arguments.size() == 1);
    CelExpr arg = checkNotNull(arguments.get(0));
    if (arg.exprKind().getKind() != CelExpr.ExprKind.Kind.SELECT) {
      return Optional.of(exprFactory.reportError("invalid argument to has() macro"));
    }
    return Optional.of(exprFactory.newSelect(arg.select().operand(), arg.select().field(), true));
  }

  // CelMacroExpander implementation for CEL's all() macro.
  private static Optional<CelExpr> expandAllMacro(
      CelMacroExprFactory exprFactory, CelExpr target, ImmutableList<CelExpr> arguments) {
    checkNotNull(exprFactory);
    checkNotNull(target);
    checkArgument(arguments.size() == 2);
    CelExpr arg0 = validatedIterationVariable(exprFactory, arguments.get(0));
    if (arg0.exprKind().getKind() != CelExpr.ExprKind.Kind.IDENT) {
      return Optional.of(arg0);
    }
    CelExpr arg1 = checkNotNull(arguments.get(1));
    CelExpr accuInit = exprFactory.newBoolLiteral(true);
    CelExpr condition =
        exprFactory.newGlobalCall(
            Operator.NOT_STRICTLY_FALSE.getFunction(),
            exprFactory.newIdentifier(exprFactory.getAccumulatorVarName()));
    CelExpr step =
        exprFactory.newGlobalCall(
            Operator.LOGICAL_AND.getFunction(),
            exprFactory.newIdentifier(exprFactory.getAccumulatorVarName()),
            arg1);
    CelExpr result = exprFactory.newIdentifier(exprFactory.getAccumulatorVarName());
    return Optional.of(
        exprFactory.fold(
            arg0.ident().name(),
            target,
            exprFactory.getAccumulatorVarName(),
            accuInit,
            condition,
            step,
            result));
  }

  // CelMacroExpander implementation for CEL's exists() macro.
  private static Optional<CelExpr> expandExistsMacro(
      CelMacroExprFactory exprFactory, CelExpr target, ImmutableList<CelExpr> arguments) {
    checkNotNull(exprFactory);
    checkNotNull(target);
    checkArgument(arguments.size() == 2);
    CelExpr arg0 = validatedIterationVariable(exprFactory, arguments.get(0));
    if (arg0.exprKind().getKind() != CelExpr.ExprKind.Kind.IDENT) {
      return Optional.of(arg0);
    }
    CelExpr arg1 = checkNotNull(arguments.get(1));
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
            arg1);
    CelExpr result = exprFactory.newIdentifier(exprFactory.getAccumulatorVarName());
    return Optional.of(
        exprFactory.fold(
            arg0.ident().name(),
            target,
            exprFactory.getAccumulatorVarName(),
            accuInit,
            condition,
            step,
            result));
  }

  // CelMacroExpander implementation for CEL's exists_one() macro.
  private static Optional<CelExpr> expandExistsOneMacro(
      CelMacroExprFactory exprFactory, CelExpr target, ImmutableList<CelExpr> arguments) {
    checkNotNull(exprFactory);
    checkNotNull(target);
    checkArgument(arguments.size() == 2);
    CelExpr arg0 = validatedIterationVariable(exprFactory, arguments.get(0));
    if (arg0.exprKind().getKind() != CelExpr.ExprKind.Kind.IDENT) {
      return Optional.of(arg0);
    }
    CelExpr arg1 = checkNotNull(arguments.get(1));
    CelExpr accuInit = exprFactory.newIntLiteral(0);
    CelExpr condition = exprFactory.newBoolLiteral(true);
    CelExpr step =
        exprFactory.newGlobalCall(
            Operator.CONDITIONAL.getFunction(),
            arg1,
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
            target,
            exprFactory.getAccumulatorVarName(),
            accuInit,
            condition,
            step,
            result));
  }

  // CelMacroExpander implementation for CEL's map() macro.
  private static Optional<CelExpr> expandMapMacro(
      CelMacroExprFactory exprFactory, CelExpr target, ImmutableList<CelExpr> arguments) {
    checkNotNull(exprFactory);
    checkNotNull(target);
    checkArgument(arguments.size() == 2 || arguments.size() == 3);
    CelExpr arg0 = validatedIterationVariable(exprFactory, arguments.get(0));
    if (arg0.exprKind().getKind() != CelExpr.ExprKind.Kind.IDENT) {
      return Optional.of(arg0);
    }
    CelExpr arg1;
    CelExpr arg2;
    if (arguments.size() == 3) {
      arg2 = checkNotNull(arguments.get(1));
      arg1 = checkNotNull(arguments.get(2));
    } else {
      arg1 = checkNotNull(arguments.get(1));
      arg2 = null;
    }
    CelExpr accuInit = exprFactory.newList();
    CelExpr condition = exprFactory.newBoolLiteral(true);
    CelExpr step =
        exprFactory.newGlobalCall(
            Operator.ADD.getFunction(),
            exprFactory.newIdentifier(exprFactory.getAccumulatorVarName()),
            exprFactory.newList(arg1));
    if (arg2 != null) {
      step =
          exprFactory.newGlobalCall(
              Operator.CONDITIONAL.getFunction(),
              arg2,
              step,
              exprFactory.newIdentifier(exprFactory.getAccumulatorVarName()));
    }
    return Optional.of(
        exprFactory.fold(
            arg0.ident().name(),
            target,
            exprFactory.getAccumulatorVarName(),
            accuInit,
            condition,
            step,
            exprFactory.newIdentifier(exprFactory.getAccumulatorVarName())));
  }

  // CelMacroExpander implementation for CEL's filter() macro.
  private static Optional<CelExpr> expandFilterMacro(
      CelMacroExprFactory exprFactory, CelExpr target, ImmutableList<CelExpr> arguments) {
    checkNotNull(exprFactory);
    checkNotNull(target);
    checkArgument(arguments.size() == 2);
    CelExpr arg0 = validatedIterationVariable(exprFactory, arguments.get(0));
    if (arg0.exprKind().getKind() != CelExpr.ExprKind.Kind.IDENT) {
      return Optional.of(arg0);
    }
    CelExpr arg1 = checkNotNull(arguments.get(1));
    CelExpr accuInit = exprFactory.newList();
    CelExpr condition = exprFactory.newBoolLiteral(true);
    CelExpr step =
        exprFactory.newGlobalCall(
            Operator.ADD.getFunction(),
            exprFactory.newIdentifier(exprFactory.getAccumulatorVarName()),
            exprFactory.newList(arg0));
    step =
        exprFactory.newGlobalCall(
            Operator.CONDITIONAL.getFunction(),
            arg1,
            step,
            exprFactory.newIdentifier(exprFactory.getAccumulatorVarName()));
    return Optional.of(
        exprFactory.fold(
            arg0.ident().name(),
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
