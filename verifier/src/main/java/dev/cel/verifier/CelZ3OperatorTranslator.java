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

package dev.cel.verifier;

import static com.google.common.collect.ImmutableList.toImmutableList;

import com.google.common.collect.ImmutableList;
import com.microsoft.z3.ArithExpr;
import com.microsoft.z3.ArrayExpr;
import com.microsoft.z3.BoolExpr;
import com.microsoft.z3.Context;
import com.microsoft.z3.Expr;
import com.microsoft.z3.FPExpr;
import com.microsoft.z3.FuncDecl;
import com.microsoft.z3.IntExpr;
import com.microsoft.z3.SeqExpr;
import com.microsoft.z3.Sort;
import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.CelFunctionDecl;
import dev.cel.common.CelOverloadDecl;
import dev.cel.common.Operator;
import dev.cel.common.ast.CelConstant;
import dev.cel.common.ast.CelExpr;
import dev.cel.common.ast.CelExpr.ExprKind;
import dev.cel.common.ast.CelReference;
import dev.cel.common.types.CelKind;
import dev.cel.common.types.CelType;
import dev.cel.common.types.ListType;
import dev.cel.common.types.MapType;
import dev.cel.common.types.NullableType;
import dev.cel.common.types.OptionalType;
import dev.cel.common.types.SimpleType;
import dev.cel.verifier.axioms.CelZ3OverloadResult;
import dev.cel.verifier.axioms.CelZ3OverloadTranslator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/** Handles mapping CEL Operators to Z3 SMT logic. */
@SuppressWarnings({"unchecked", "rawtypes"}) // Z3 Java API uses raw types.
final class CelZ3OperatorTranslator {
  private static final String FUNC_APPROX_EQ = "!approxEq";
  private static final String FUNC_APPROX_IN = "!approxIn";
  private static final MapType DYN_MAP = MapType.create(SimpleType.DYN, SimpleType.DYN);

  private final Context ctx;
  private final CelZ3TypeSystem typeSystem;
  private final CelZ3TranslationContext context;
  private final CelZ3TypeConstraintGenerator typeConstraintGenerator;
  private final CelZ3FunctionRegistry functionRegistry;
  private final int comprehensionUnrollLimit;

  Optional<TranslatedValue> translateFunctionCall(
      String functionName, List<TranslatedValue> args, long exprId, CelAbstractSyntaxTree ast) {
    Optional<Operator> opOpt = Operator.findReverse(functionName);
    ImmutableList<Expr<?>> z3Args =
        args.stream().map(TranslatedValue::z3Expr).collect(toImmutableList());
    ImmutableList<BoolExpr> argApproximations =
        args.stream().map(TranslatedValue::isApproximate).collect(toImmutableList());

    TranslatedValue resultChain =
        opOpt
            .map(op -> translateOperatorCall(op, args, ast))
            .orElseGet(
                () ->
                    TranslatedValue.propagateStrict(
                        ctx, typeSystem, functionName, typeSystem.mkError(), args));

    CelReference reference = ast.getReferenceMap().get(exprId);
    CelFunctionDecl decl = functionRegistry.getDeclaration(functionName).orElse(null);

    if (decl == null) {
      return opOpt.isPresent() ? Optional.of(resultChain) : Optional.empty();
    }

    // A candidate overload without an axiom (e.g. a custom overload of a standard operator) is
    // treated as an uninterpreted call, rather than falling through to the type-mismatch error.
    if (reference != null
        && reference.overloadIds().stream()
            .anyMatch(id -> !functionRegistry.getTranslator(id).isPresent())) {
      return Optional.empty();
    }

    boolean matchedAny = false;
    Expr<?> currentZ3Result = resultChain.z3Expr();
    BoolExpr currentApprox = resultChain.isApproximate();
    // Z3 is bottom up, but since we are replacing errors with the next matching overload,
    // we should actually process the overloads in reverse order to build the ITE chain.
    for (CelOverloadDecl overload : decl.overloads().asList().reverse()) {
      // Bypasses the SMT ITE soup for cases where we know the exact function call signature.
      if (reference != null && !reference.overloadIds().isEmpty()) {
        if (!reference.overloadIds().contains(overload.overloadId())) {
          continue;
        }
      }

      if (overload.parameterTypes().size() != args.size()) {
        continue;
      }

      CelZ3OverloadTranslator translator =
          functionRegistry.getTranslator(overload.overloadId()).orElse(null);
      if (translator == null) {
        continue;
      }

      Optional<CelZ3OverloadResult> evaluatedResultOpt =
          translator.translate(ctx, typeSystem, context::addConstraint, z3Args, argApproximations);

      if (!evaluatedResultOpt.isPresent()) {
        continue;
      }

      CelZ3OverloadResult evaluatedResult = evaluatedResultOpt.get();

      matchedAny = true;
      List<BoolExpr> typeGuards = new ArrayList<>();
      for (int i = 0; i < z3Args.size(); i++) {
        typeGuards.add(mkTypeGuard(z3Args.get(i), overload.parameterTypes().get(i)));
      }

      BoolExpr typeGuard = CelZ3Exprs.mkAndFlattened(ctx, typeGuards);
      currentZ3Result = ctx.mkITE(typeGuard, evaluatedResult.z3Expr(), currentZ3Result);
      currentApprox =
          (BoolExpr)
              ctx.mkITE(
                  typeGuard,
                  attributeApproximation(
                      functionName, evaluatedResult.isApproximate(), argApproximations),
                  currentApprox);
    }

    if (!matchedAny) {
      return opOpt.isPresent() ? Optional.of(resultChain) : Optional.empty();
    }

    Operator op = opOpt.orElse(null);
    if (op == Operator.IN) {
      TranslatedValue lhs = args.get(0);
      TranslatedValue list = args.get(1);
      currentApprox =
          CelZ3Exprs.mkOrFlattened(ctx, currentApprox, constrainInWitnessTypes(lhs, list, ast));
      BoolExpr isList = typeSystem.isList(list.z3Expr());
      if (isExact(ListType.create(guaranteedType(lhs, ast)), guaranteedType(list, ast))) {
        // IN_LIST matched by terms, but a miss may be past the unroll limit, where elements aren't
        // constrained to the element type (e.g. `!(false in bool_list)` with a 6th element of 1).
        ArithExpr length = ctx.mkLength(typeSystem.getSeq(typeSystem.getListRef(list.z3Expr())));
        currentApprox =
            CelZ3Exprs.mkOrFlattened(
                ctx,
                currentApprox,
                context.recordUnrollLimitApproximation(
                    CelZ3Exprs.mkAndFlattened(
                        ctx,
                        isList,
                        ctx.mkGt(length, ctx.mkInt(comprehensionUnrollLimit)),
                        ctx.mkNot(typeSystem.isTrue(currentZ3Result)))));
      } else if (extractAstTypeOrDefault(list, ast).kind() != CelKind.MAP) {
        // Performance only: maps never take the list overload.
        List<BoolExpr> taints = new ArrayList<>();
        BoolExpr found = listMembership(lhs, list, ast, taints);
        currentZ3Result = ctx.mkITE(isList, typeSystem.wrapBool(found), currentZ3Result);
        currentApprox =
            CelZ3Exprs.mkOrFlattened(
                ctx, currentApprox, ctx.mkAnd(isList, CelZ3Exprs.mkOrFlattened(ctx, taints)));
      }
    }

    return Optional.of(
        TranslatedValue.create(
            typeSystem.propagateErrorAndUnknown(functionName, currentZ3Result, z3Args, z3Args),
            typeSystem,
            currentApprox));
  }

  // Returns an overload's approximation with the part it adds itself recorded as a call
  // approximation.
  private BoolExpr attributeApproximation(
      String functionName, BoolExpr approx, ImmutableList<BoolExpr> argApproximations) {
    // A literal `true` can't be substituted without also replacing the overload's own `true`.
    ImmutableList<BoolExpr> substitutable =
        argApproximations.stream().filter(a -> !a.isTrue()).collect(toImmutableList());
    // Approximations only grow with their arguments', so approx is equivalent to the overload's own
    // part (its value with exact arguments) or approx under some approximate argument.
    BoolExpr own =
        context.recordCallApproximation(
            functionName,
            (BoolExpr) context.withoutApproximation(approx, substitutable).simplify());
    BoolExpr inherited =
        CelZ3Exprs.mkAndFlattened(ctx, approx, CelZ3Exprs.mkOrFlattened(ctx, argApproximations));
    return CelZ3Exprs.mkOrFlattened(ctx, own, inherited);
  }

  /**
   * Constrains keys that {@code InAxiom} may find in a declared map to its key type, and returns
   * where the result stays approximate.
   */
  private BoolExpr constrainInWitnessTypes(
      TranslatedValue lhsTv, TranslatedValue rhsTv, CelAbstractSyntaxTree ast) {
    Expr<?> lhs = lhsTv.z3Expr();
    Expr<?> rhs = rhsTv.z3Expr();
    CelType rhsType = guaranteedType(rhsTv, ast);
    if (rhsType instanceof MapType) {
      // IN_MAP probes lhs and its numeric counterparts, which type constraints unrolled up to the
      // limit don't cover (e.g. a string found in a map with int keys).
      ArrayExpr mapPresence = (ArrayExpr) typeSystem.getMapPresence(typeSystem.getMapRef(rhs));
      for (CelZ3TypeSystem.EqualKey key : typeSystem.equalKeys(lhs)) {
        context.addConstraint(
            ctx.mkImplies(
                ctx.mkAnd(
                    typeSystem.isMap(rhs),
                    key.condition(),
                    (BoolExpr) ctx.mkSelect(mapPresence, key.key())),
                typeConstraintGenerator.createTypeConstraintForType(
                    key.key(), ((MapType) rhsType).keyType())));
      }
      return context.isTruncatedMap(rhs);
    }
    // On lists, IN_LIST is exact (lhs has the element type, which compares by terms), or else
    // listMembership compares by CEL equality.
    return ctx.mkAnd(
        typeSystem.isMap(rhs),
        CelZ3Exprs.mkOrFlattened(
            ctx,
            context.isTruncatedMap(rhs),
            mayObserveTruncatedTypeConstraint(ast, lhsTv, rhsTv)));
  }

  private BoolExpr mkTypeGuard(Expr<?> arg, CelType expectedType) {
    switch (expectedType.kind()) {
      case LIST:
        return typeSystem.isList(arg);
      case MAP:
        return typeSystem.isMap(arg);
      case ANY:
      case TYPE_PARAM:
      case DYN:
        // These match everything structurally type-wise, although we might refine this later.
        return ctx.mkTrue();
      case INT:
        return typeSystem.isInt(arg);
      case TIMESTAMP:
        return typeSystem.isTimestamp(arg);
      case DURATION:
        return typeSystem.isDuration(arg);
      case UINT:
        return typeSystem.isUint(arg);
      case DOUBLE:
        return typeSystem.isDouble(arg);
      case BOOL:
        return typeSystem.isBool(arg);
      case STRING:
        return typeSystem.isString(arg);
      case BYTES:
        return typeSystem.isBytes(arg);
      case STRUCT:
        return typeSystem.isStruct(arg);
      case NULL_TYPE:
        return typeSystem.isNull(arg);
      case OPAQUE:
        if (expectedType.name().equals(OptionalType.NAME)) {
          return typeSystem.isOptional(arg);
        }
      // Fallthrough
      default:
        throw new UnsupportedOperationException(
            "Unsupported type for dynamic type guard: " + expectedType.kind());
    }
  }

  private TranslatedValue translateOperatorCall(
      Operator op, List<TranslatedValue> args, CelAbstractSyntaxTree ast) {
    switch (op) {
      case NEGATE:
      case LOGICAL_NOT:
      case NOT_STRICTLY_FALSE:
      case OLD_NOT_STRICTLY_FALSE:
        if (args.size() != 1) {
          throw new IllegalArgumentException(
              String.format(
                  "Malformed AST: operator %s expects 1 argument, got %d", op, args.size()));
        }
        break;
      case CONDITIONAL:
        if (args.size() != 3) {
          throw new IllegalArgumentException(
              String.format(
                  "Malformed AST: operator %s expects 3 arguments, got %d", op, args.size()));
        }
        break;
      case LOGICAL_AND:
      case LOGICAL_OR:
        if (args.size() < 2) {
          throw new IllegalArgumentException(
              String.format(
                  "Malformed AST: operator %s expects at least 2 arguments, got %d",
                  op, args.size()));
        }
        break;
      default:
        // All other supported ops are binary (e.g. EQUALS, LESS, ADD, IN)
        if (args.size() != 2) {
          throw new IllegalArgumentException(
              String.format(
                  "Malformed AST: operator %s expects 2 arguments, got %d", op, args.size()));
        }
        break;
    }

    switch (op) {
      case LOGICAL_AND:
        return translateLogicalAndOr(args, true);
      case LOGICAL_OR:
        return translateLogicalAndOr(args, false);
      case LOGICAL_NOT:
        return translateLogicalNot(args, ast);
      case NEGATE:
        return translateNegate(args.get(0), ast);
      case EQUALS:
        return translateEquality(
            args.get(0), args.get(1), ast, /* isEquals= */ true, /* shallow= */ false);
      case NOT_EQUALS:
        return translateEquality(
            args.get(0), args.get(1), ast, /* isEquals= */ false, /* shallow= */ false);
      case LESS:
      case GREATER:
      case LESS_EQUALS:
      case GREATER_EQUALS:
      case ADD:
      case SUBTRACT:
      case MULTIPLY:
      case DIVIDE:
      case MODULO:
      case IN:
        // Indicates a type-mismatch in an operator that's not handled
        // by our axioms
        return TranslatedValue.propagateStrict(
            ctx, typeSystem, op.getFunction(), typeSystem.mkError(), args);
      case INDEX:
        return translateIndex(args, ast, /* isOptional= */ false);
      case OPTIONAL_INDEX:
        return translateIndex(args, ast, /* isOptional= */ true);
      case CONDITIONAL:
        return translateConditional(args, ast);
      case NOT_STRICTLY_FALSE:
      case OLD_NOT_STRICTLY_FALSE:
        return translateNotStrictlyFalse(args);
      default:
        // For operators we haven't implemented cleanly, just wrap uninterpreted for now.
        return TranslatedValue.propagateStrict(
                ctx, typeSystem, op.getFunction(), typeSystem.mkUnknown(), args)
            .withApproximation(context.recordCallApproximation(op.getFunction(), ctx.mkTrue()));
    }
  }

  private TranslatedValue translateLogicalAndOr(List<TranslatedValue> args, boolean isAnd) {
    TranslatedValue result = args.get(0);
    for (int i = 1; i < args.size(); i++) {
      result = translateBinaryLogicalAndOr(result, args.get(i), isAnd);
    }
    return result;
  }

  private TranslatedValue translateBinaryLogicalAndOr(
      TranslatedValue a, TranslatedValue b, boolean isAnd) {
    BoolExpr aIsBool = a.isZ3Bool();
    BoolExpr bIsBool = b.isZ3Bool();
    BoolExpr aTrue = ctx.mkAnd(aIsBool, (BoolExpr) a.unwrapZ3Bool());
    BoolExpr bTrue = ctx.mkAnd(bIsBool, (BoolExpr) b.unwrapZ3Bool());
    BoolExpr aFalse = ctx.mkAnd(aIsBool, ctx.mkNot((BoolExpr) a.unwrapZ3Bool()));
    BoolExpr bFalse = ctx.mkAnd(bIsBool, ctx.mkNot((BoolExpr) b.unwrapZ3Bool()));

    BoolExpr hasMatch = isAnd ? ctx.mkOr(aFalse, bFalse) : ctx.mkOr(aTrue, bTrue);
    BoolExpr hasUnknown = ctx.mkOr(a.isZ3Unknown(), b.isZ3Unknown());
    // Non-boolean operands are no-overload errors.
    BoolExpr hasError =
        ctx.mkOr(
            ctx.mkNot(ctx.mkOr(aIsBool, a.isZ3Unknown())),
            ctx.mkNot(ctx.mkOr(bIsBool, b.isZ3Unknown())));

    BoolExpr aMatch = isAnd ? aFalse : aTrue;
    BoolExpr bMatch = isAnd ? bFalse : bTrue;

    BoolExpr hasSafeMatch =
        ctx.mkOr(
            ctx.mkAnd(aMatch, ctx.mkNot(a.isApproximate())),
            ctx.mkAnd(bMatch, ctx.mkNot(b.isApproximate())));

    String opName = isAnd ? Operator.LOGICAL_AND.getFunction() : Operator.LOGICAL_OR.getFunction();
    Expr<?> unknownResult =
        typeSystem.isParameterizingUnknowns()
            ? typeSystem.mkParameterizedUnknown(opName, ImmutableList.of(a.z3Expr(), b.z3Expr()))
            : ctx.mkITE(a.isZ3Unknown(), a.z3Expr(), b.z3Expr());

    Expr<?> resultZ3 =
        CelZ3Exprs.SwitchBuilder.newBuilder(ctx)
            .addCase(hasMatch, typeSystem.mkBool(!isAnd))
            .addCase(hasUnknown, unknownResult)
            .addCase(hasError, typeSystem.mkError())
            .build(typeSystem.mkBool(isAnd));

    // An exact short-circuiting operand alone decides the result. Otherwise, an approximate operand
    // may really be the absorbing value (e.g. `unknown && false` is false).
    BoolExpr resultTaint =
        CelZ3Exprs.mkAndFlattened(
            ctx,
            CelZ3Exprs.mkNotFlattened(ctx, hasSafeMatch),
            CelZ3Exprs.mkOrFlattened(ctx, a.isApproximate(), b.isApproximate()));

    return TranslatedValue.create(resultZ3, typeSystem, resultTaint);
  }

  private TranslatedValue translateLogicalNot(
      List<TranslatedValue> args, CelAbstractSyntaxTree ast) {
    TranslatedValue arg = args.get(0);
    CelType type = extractAstTypeOrDefault(arg, ast);

    Expr<?> baseResult = typeSystem.wrapBool(ctx.mkNot((BoolExpr) arg.unwrapZ3Bool()));
    if (!type.equals(SimpleType.BOOL)) {
      baseResult = typeSystem.withRuntimeError(baseResult, ctx.mkNot(arg.isZ3Bool()));
    }

    return TranslatedValue.propagateStrict(
        ctx, typeSystem, Operator.LOGICAL_NOT.getFunction(), baseResult, args);
  }

  private TranslatedValue translateNegate(TranslatedValue arg, CelAbstractSyntaxTree ast) {
    CelType type = extractAstTypeOrDefault(arg, ast);
    Expr<?> z3Expr = arg.z3Expr();

    Expr<?> result;
    if (type.equals(SimpleType.INT)) {
      ArithExpr intNeg = ctx.mkUnaryMinus(typeSystem.getInt(z3Expr));
      result =
          typeSystem.withRuntimeError(
              typeSystem.wrapInt((IntExpr) intNeg), typeSystem.checkIntOverflow(intNeg));
    } else if (type.equals(SimpleType.DOUBLE)) {
      result = typeSystem.wrapDouble(ctx.mkFPNeg(typeSystem.getDouble(z3Expr)));
    } else {
      ArithExpr intNeg = ctx.mkUnaryMinus(typeSystem.getInt(z3Expr));
      Expr<?> intResult =
          typeSystem.withRuntimeError(
              typeSystem.wrapInt((IntExpr) intNeg), typeSystem.checkIntOverflow(intNeg));
      Expr<?> doubleResult = typeSystem.wrapDouble(ctx.mkFPNeg(typeSystem.getDouble(z3Expr)));
      result =
          CelZ3Exprs.SwitchBuilder.newBuilder(ctx)
              .addCase(typeSystem.isInt(z3Expr), intResult)
              .addCase(typeSystem.isDouble(z3Expr), doubleResult)
              .build(typeSystem.mkError());
    }
    return TranslatedValue.propagateStrict(
        ctx, typeSystem, Operator.NEGATE.getFunction(), result, arg);
  }

  private BoolExpr isNumeric(Expr<?> arg) {
    return ctx.mkOr(typeSystem.isInt(arg), typeSystem.isUint(arg), typeSystem.isDouble(arg));
  }

  private BoolExpr getNumericEqualityWithConstant(
      Expr<?> symVal, CelConstant constant, CelType symType) {
    Optional<CelNumericBounds.IntRange> intRange = Optional.empty();
    Optional<CelNumericBounds.UintRange> uintRange = Optional.empty();
    double doubleVal;

    switch (constant.getKind()) {
      case INT64_VALUE:
        long vInt = constant.int64Value();
        intRange = Optional.of(CelNumericBounds.IntRange.of(vInt, vInt));
        if (vInt >= 0) {
          uintRange =
              Optional.of(CelNumericBounds.UintRange.of(Long.toString(vInt), Long.toString(vInt)));
        }
        doubleVal = (double) vInt;
        break;
      case UINT64_VALUE:
        long vUint = constant.uint64Value().longValue();
        if (vUint >= 0) {
          intRange = Optional.of(CelNumericBounds.IntRange.of(vUint, vUint));
        }
        String uStr = constant.uint64Value().toString();
        uintRange = Optional.of(CelNumericBounds.UintRange.of(uStr, uStr));
        doubleVal = constant.uint64Value().doubleValue();
        break;
      case DOUBLE_VALUE:
        double vDouble = constant.doubleValue();
        doubleVal = vDouble;
        intRange = CelNumericBounds.getMatchingIntRange(vDouble);
        uintRange = CelNumericBounds.getMatchingUintRange(vDouble);
        break;
      default:
        throw new IllegalArgumentException(
            "Unexpected numeric constant kind: " + constant.getKind());
    }
    if (isStaticallyKnown(symType)) {
      if (symType.kind() == CelKind.INT) {
        return buildIntRangeExpr(intRange, typeSystem.getInt(symVal));
      } else if (symType.kind() == CelKind.UINT) {
        return buildUintRangeExpr(uintRange, typeSystem.getUint(symVal));
      } else if (symType.kind() == CelKind.DOUBLE) {
        return ctx.mkFPEq(typeSystem.getDouble(symVal), typeSystem.mkFpDouble(doubleVal));
      }
    }

    BoolExpr intEq = buildIntRangeExpr(intRange, typeSystem.getInt(symVal));
    BoolExpr uintEq = buildUintRangeExpr(uintRange, typeSystem.getUint(symVal));
    BoolExpr doubleEq = ctx.mkFPEq(typeSystem.getDouble(symVal), typeSystem.mkFpDouble(doubleVal));

    return (BoolExpr)
        CelZ3Exprs.SwitchBuilder.newBuilder(ctx)
            .addCase(typeSystem.isInt(symVal), intEq)
            .addCase(typeSystem.isUint(symVal), uintEq)
            .addCase(typeSystem.isDouble(symVal), doubleEq)
            .build(ctx.mkFalse());
  }

  private BoolExpr buildIntRangeExpr(Optional<CelNumericBounds.IntRange> rangeOpt, IntExpr symInt) {
    return rangeOpt
        .map(range -> buildIntRangeExpr(range.min(), range.max(), symInt))
        .orElseGet(ctx::mkFalse);
  }

  private BoolExpr buildIntRangeExpr(long min, long max, IntExpr symInt) {
    return ctx.mkAnd(ctx.mkGe(symInt, ctx.mkInt(min)), ctx.mkLe(symInt, ctx.mkInt(max)));
  }

  private BoolExpr buildUintRangeExpr(
      Optional<CelNumericBounds.UintRange> rangeOpt, IntExpr symUint) {
    return rangeOpt
        .map(range -> buildUintRangeExpr(range.min(), range.max(), symUint))
        .orElseGet(ctx::mkFalse);
  }

  private BoolExpr buildUintRangeExpr(String min, String max, IntExpr symUint) {
    return ctx.mkAnd(ctx.mkGe(symUint, ctx.mkInt(min)), ctx.mkLe(symUint, ctx.mkInt(max)));
  }

  private BoolExpr getNumericEquality(
      TranslatedValue arg0,
      TranslatedValue arg1,
      CelAbstractSyntaxTree ast,
      List<BoolExpr> taints,
      boolean approximateIntDouble) {
    if (arg0.isNumericConstant()) {
      return getNumericEqualityWithConstant(
          arg1.z3Expr(), arg0.celExpr().get().constant(), extractAstTypeOrDefault(arg1, ast));
    } else if (arg1.isNumericConstant()) {
      return getNumericEqualityWithConstant(
          arg0.z3Expr(), arg1.celExpr().get().constant(), extractAstTypeOrDefault(arg0, ast));
    }

    CelType type0 = extractAstTypeOrDefault(arg0, ast);
    CelType type1 = extractAstTypeOrDefault(arg1, ast);
    if (isStaticallyKnown(type0) && isStaticallyKnown(type1) && type0.kind() == type1.kind()) {
      return getStaticallyKnownNumericEquality(arg0.z3Expr(), type0, arg1.z3Expr());
    }

    return getDynamicNumericEquality(arg0.z3Expr(), arg1.z3Expr(), taints, approximateIntDouble);
  }

  /** Evaluates numeric equality when types are statically known. */
  private BoolExpr getStaticallyKnownNumericEquality(
      Expr<?> z3Expr0, CelType type0, Expr<?> z3Expr1) {
    switch (type0.kind()) {
      case INT:
        return ctx.mkEq(typeSystem.getInt(z3Expr0), typeSystem.getInt(z3Expr1));
      case UINT:
        return ctx.mkEq(typeSystem.getUint(z3Expr0), typeSystem.getUint(z3Expr1));
      case DOUBLE:
        return ctx.mkFPEq(typeSystem.getDouble(z3Expr0), typeSystem.getDouble(z3Expr1));
      default:
        return ctx.mkFalse();
    }
  }

  private BoolExpr getDynamicNumericEquality(
      Expr<?> z3Expr0, Expr<?> z3Expr1, List<BoolExpr> taints, boolean approximateIntDouble) {
    BoolExpr isIntOrUint0 = ctx.mkOr(typeSystem.isInt(z3Expr0), typeSystem.isUint(z3Expr0));
    BoolExpr isIntOrUint1 = ctx.mkOr(typeSystem.isInt(z3Expr1), typeSystem.isUint(z3Expr1));
    BoolExpr bothIntOrUint = ctx.mkAnd(isIntOrUint0, isIntOrUint1);

    // Fall back to 0 if the expression is neither an INT nor a UINT.
    // This prevents Z3 from evaluating getUint() on a DOUBLE, which causes the OSS solver to enter
    // an incomplete state.
    IntExpr val0 =
        (IntExpr)
            ctx.mkITE(
                typeSystem.isInt(z3Expr0),
                typeSystem.getInt(z3Expr0),
                ctx.mkITE(typeSystem.isUint(z3Expr0), typeSystem.getUint(z3Expr0), ctx.mkInt(0)));
    IntExpr val1 =
        (IntExpr)
            ctx.mkITE(
                typeSystem.isInt(z3Expr1),
                typeSystem.getInt(z3Expr1),
                ctx.mkITE(typeSystem.isUint(z3Expr1), typeSystem.getUint(z3Expr1), ctx.mkInt(0)));

    BoolExpr bothDouble = ctx.mkAnd(typeSystem.isDouble(z3Expr0), typeSystem.isDouble(z3Expr1));

    BoolExpr isIntOrUintAndDouble = ctx.mkAnd(isIntOrUint0, typeSystem.isDouble(z3Expr1));
    BoolExpr isDoubleAndIntOrUint = ctx.mkAnd(typeSystem.isDouble(z3Expr0), isIntOrUint1);

    CelZ3Exprs.SwitchBuilder equality =
        CelZ3Exprs.SwitchBuilder.newBuilder(ctx)
            .addCase(bothIntOrUint, ctx.mkEq(val0, val1))
            .addCase(
                bothDouble,
                ctx.mkFPEq(typeSystem.getDouble(z3Expr0), typeSystem.getDouble(z3Expr1)));
    if (approximateIntDouble) {
      return approximateEqualityWhere(
          context.recordApproximation(
              "comparisons of ints with doubles read out of lists and maps",
              ctx.mkOr(isIntOrUintAndDouble, isDoubleAndIntOrUint)),
          z3Expr0,
          z3Expr1,
          /* exact= */ (BoolExpr) equality.build(ctx.mkFalse()),
          taints);
    }
    // Likewise fall back to 0.0, which the int/double comparisons never need to define.
    FPExpr fpVal0 = guardedDouble(z3Expr0);
    FPExpr fpVal1 = guardedDouble(z3Expr1);
    return (BoolExpr)
        equality
            .addCase(isIntOrUintAndDouble, typeSystem.intEqDouble(val0, fpVal1))
            .addCase(isDoubleAndIntOrUint, typeSystem.intEqDouble(val1, fpVal0))
            .build(ctx.mkFalse());
  }

  private FPExpr guardedDouble(Expr<?> z3Expr) {
    return (FPExpr)
        ctx.mkITE(
            typeSystem.isDouble(z3Expr), typeSystem.getDouble(z3Expr), typeSystem.mkFpDouble(0.0));
  }

  // A literal with optional elements has as many elements as are present, so no fixed length.
  private static boolean isFixedLengthListLiteral(TranslatedValue list) {
    return list.isLiteral(ExprKind.Kind.LIST)
        && list.celExpr().get().list().optionalIndices().isEmpty();
  }

  // Unrolls list equality element-wise (up to the unroll limit unless either is a literal), adding
  // each element equality's approximation to taints: numerically equal elements (e.g. 1 == 1u)
  // needn't be equal terms.
  private BoolExpr unrollListEquality(
      TranslatedValue listA,
      TranslatedValue listB,
      CelAbstractSyntaxTree ast,
      List<BoolExpr> taints) {
    boolean isLiteralA = isFixedLengthListLiteral(listA);
    boolean isLiteralB = isFixedLengthListLiteral(listB);
    boolean isLiteral = isLiteralA || isLiteralB;
    SeqExpr<?> seq0 = typeSystem.getSeq(typeSystem.getListRef(listA.z3Expr()));
    SeqExpr<?> seq1 = typeSystem.getSeq(typeSystem.getListRef(listB.z3Expr()));
    ArithExpr length = ctx.mkLength(seq0);

    List<BoolExpr> equalities = new ArrayList<>();
    equalities.add(ctx.mkEq(length, ctx.mkLength(seq1)));
    int size =
        isLiteral
            ? (isLiteralA ? listA : listB).celExpr().get().list().elements().size()
            : comprehensionUnrollLimit;
    if (!isLiteral) {
      equalities.add(approximatePastUnrollLimit(length, listA.z3Expr(), listB.z3Expr(), taints));
    }
    for (int i = 0; i < size; i++) {
      TranslatedValue elemA = listElement(listA, seq0, i, isLiteralA, ast);
      TranslatedValue elemB = listElement(listB, seq1, i, isLiteralB, ast);
      TranslatedValue elemEquality =
          translateEquality(elemA, elemB, ast, /* isEquals= */ true, /* shallow= */ !isLiteral);
      BoolExpr inBounds = ctx.mkLt(ctx.mkInt(i), length);
      equalities.add(ctx.mkImplies(inBounds, typeSystem.isTrue(elemEquality.z3Expr())));
      taints.add(ctx.mkAnd(inBounds, elemEquality.isApproximate()));
    }
    return CelZ3Exprs.mkAndFlattened(ctx, equalities);
  }

  // Element i of list, typed by the literal's element if list is a literal.
  private TranslatedValue listElement(
      TranslatedValue list, SeqExpr<?> seq, int i, boolean isLiteral, CelAbstractSyntaxTree ast) {
    Expr<?> elem = ctx.mkNth(seq, ctx.mkInt(i));
    // A literal's elements are already CEL values (performance only).
    if (!isLiteral) {
      BoolExpr inBounds = ctx.mkLt(ctx.mkInt(i), ctx.mkLength(seq));
      context.constrainValidValue(ctx.mkAnd(typeSystem.isList(list.z3Expr()), inBounds), elem);
      CelType listType = guaranteedType(list, ast);
      // Type constraints are only unrolled up to the limit.
      if (i >= comprehensionUnrollLimit && listType instanceof ListType) {
        context.addConstraint(
            ctx.mkImplies(
                inBounds,
                typeConstraintGenerator.createTypeConstraintForType(
                    elem, ((ListType) listType).elemType())));
      }
    }
    return TranslatedValue.create(
        elem,
        isLiteral ? list.listElementAt(i) : Optional.empty(),
        typeSystem,
        list.isApproximate());
  }

  // Unrolled over a map literal's keys, or else mapA's up to the unroll limit: the other map has as
  // many keys, and an equal key and value for each.
  private BoolExpr unrollMapEquality(
      TranslatedValue mapA,
      TranslatedValue mapB,
      CelAbstractSyntaxTree ast,
      List<BoolExpr> taints) {
    boolean isLiteral = mapA.isLiteral(ExprKind.Kind.MAP) || mapB.isLiteral(ExprKind.Kind.MAP);
    // Prefer a literal whose values are list or map literals, which compare element-wise.
    boolean isMapA =
        !mapB.isLiteral(ExprKind.Kind.MAP)
            || (mapA.isLiteral(ExprKind.Kind.MAP) && !hasContainerLiteralValue(mapB));
    TranslatedValue map = isMapA ? mapA : mapB;
    TranslatedValue otherMap = isMapA ? mapB : mapA;
    Expr<?> other = otherMap.z3Expr();
    BoolExpr isOtherMap = typeSystem.isMap(other);
    Expr<?> mapRef = typeSystem.getMapRef(map.z3Expr());
    SeqExpr<?> keys = typeSystem.getMapKeys(mapRef);
    ArrayExpr values = (ArrayExpr) typeSystem.getMapValues(mapRef);
    ArithExpr length = ctx.mkLength(keys);
    Expr<?> otherRef = typeSystem.getMapRef(other);
    SeqExpr<?> otherKeys = typeSystem.getMapKeys(otherRef);

    List<BoolExpr> equalities = new ArrayList<>();
    equalities.add(ctx.mkEq(length, ctx.mkLength(otherKeys)));
    ImmutableList<CelExpr.CelMap.Entry> entries =
        isLiteral ? map.celExpr().get().map().entries() : ImmutableList.of();
    // The keys are in entry order unless an optional entry is absent (repeated keys are errors).
    boolean inEntryOrder =
        isLiteral && entries.stream().noneMatch(CelExpr.CelMap.Entry::optionalEntry);
    int size = isLiteral ? entries.size() : comprehensionUnrollLimit;
    if (!isLiteral) {
      equalities.add(approximatePastUnrollLimit(length, map.z3Expr(), other, taints));
      // A map that isn't a literal or of a declared map type isn't otherwise known to be
      // well-formed (distinct keys matching its presence, valid keys and values).
      for (TranslatedValue operand : ImmutableList.of(mapA, mapB)) {
        if (!(guaranteedType(operand, ast) instanceof MapType)) {
          context.addConstraint(
              ctx.mkImplies(
                  typeSystem.isMap(operand.z3Expr()),
                  typeConstraintGenerator.createTypeConstraintForType(operand.z3Expr(), DYN_MAP)));
        }
      }
    }
    for (int i = 0; i < size; i++) {
      BoolExpr inBounds = ctx.mkLt(ctx.mkInt(i), length);
      Expr<?> key = ctx.mkNth(keys, ctx.mkInt(i)).simplify();
      ImmutableList<CelZ3TypeSystem.EqualKey> equalKeys = typeSystem.equalKeys(key);
      BoolExpr hasKey = typeSystem.hasEqualKey(other, equalKeys);
      Expr<?> otherValue = typeSystem.valueAtEqualKey(other, equalKeys);
      context.constrainValidValue(ctx.mkAnd(isOtherMap, inBounds, hasKey), otherValue);
      TranslatedValue valueEquality =
          translateEquality(
              TranslatedValue.create(
                  ctx.mkSelect(values, key),
                  inEntryOrder ? Optional.of(entries.get(i).value()) : Optional.empty(),
                  typeSystem,
                  map.isApproximate()),
              TranslatedValue.create(otherValue, typeSystem, otherMap.isApproximate()),
              ast,
              /* isEquals= */ true,
              /* shallow= */ !isLiteral);
      taints.add(ctx.mkAnd(inBounds, valueEquality.isApproximate()));
      equalities.add(
          ctx.mkImplies(inBounds, ctx.mkAnd(hasKey, typeSystem.isTrue(valueEquality.z3Expr()))));
    }
    if (isLiteral) {
      // The other map's keys are exactly the present ones, so each equals a literal key: as many
      // as the literal's, which are all present.
      ArrayExpr presence = ctx.mkConstArray(typeSystem.celValueSort(), ctx.mkFalse());
      for (int j = 0; j < entries.size(); j++) {
        Expr<?> otherKey = ctx.mkNth(otherKeys, ctx.mkInt(j));
        presence =
            (ArrayExpr)
                ctx.mkITE(
                    ctx.mkLt(ctx.mkInt(j), length),
                    ctx.mkStore(presence, otherKey, ctx.mkTrue()),
                    presence);
      }
      equalities.add(ctx.mkEq(typeSystem.getMapPresence(otherRef), presence));
    }
    return CelZ3Exprs.mkAndFlattened(ctx, equalities);
  }

  // Maps whose keys and values compare by their terms are equal when they have the same keys, and
  // the same values at the literal's keys (performance only).
  private BoolExpr mapEqualityAtLiteralKeys(TranslatedValue mapA, TranslatedValue mapB) {
    boolean isLiteralA = mapA.isLiteral(ExprKind.Kind.MAP);
    Expr<?> literalRef = typeSystem.getMapRef((isLiteralA ? mapA : mapB).z3Expr());
    Expr<?> otherRef = typeSystem.getMapRef((isLiteralA ? mapB : mapA).z3Expr());
    SeqExpr<?> keys = typeSystem.getMapKeys(literalRef);
    ArrayExpr values = (ArrayExpr) typeSystem.getMapValues(literalRef);
    ArrayExpr otherValues = (ArrayExpr) typeSystem.getMapValues(otherRef);
    List<BoolExpr> equalities = new ArrayList<>();
    // Same size and same keys. Past the unroll limit, a map's presence isn't tied to its keys, so
    // matching presence alone doesn't rule out extra keys (e.g. a 6-key map equal to {'a': 1}).
    equalities.add(ctx.mkEq(ctx.mkLength(keys), ctx.mkLength(typeSystem.getMapKeys(otherRef))));
    equalities.add(
        ctx.mkEq(typeSystem.getMapPresence(literalRef), typeSystem.getMapPresence(otherRef)));
    int size = (isLiteralA ? mapA : mapB).celExpr().get().map().entries().size();
    for (int i = 0; i < size; i++) {
      Expr<?> key = ctx.mkNth(keys, ctx.mkInt(i));
      equalities.add(
          ctx.mkImplies(
              ctx.mkLt(ctx.mkInt(i), ctx.mkLength(keys)),
              typeSystem.getStructuralEquality(
                  ctx.mkSelect(values, key), ctx.mkSelect(otherValues, key))));
    }
    return CelZ3Exprs.mkAndFlattened(ctx, equalities);
  }

  // `in` on a list compares each element with lhs by CEL equality, unrolled over a literal's
  // elements or else up to the unroll limit, past which a match is approximate.
  private BoolExpr listMembership(
      TranslatedValue lhs, TranslatedValue list, CelAbstractSyntaxTree ast, List<BoolExpr> taints) {
    boolean isLiteral = isFixedLengthListLiteral(list);
    SeqExpr<?> seq = typeSystem.getSeq(typeSystem.getListRef(list.z3Expr()));
    ArithExpr length = ctx.mkLength(seq);
    List<BoolExpr> matches = new ArrayList<>();
    int size = isLiteral ? list.celExpr().get().list().elements().size() : comprehensionUnrollLimit;
    for (int i = 0; i < size; i++) {
      BoolExpr inBounds = ctx.mkLt(ctx.mkInt(i), length);
      // The runtime compares each element with lhs, in that order.
      TranslatedValue equality =
          translateEquality(
              listElement(list, seq, i, isLiteral, ast),
              lhs,
              ast,
              /* isEquals= */ true,
              /* shallow= */ !isLiteral);
      matches.add(ctx.mkAnd(inBounds, typeSystem.isTrue(equality.z3Expr())));
      taints.add(ctx.mkAnd(inBounds, equality.isApproximate()));
    }
    BoolExpr found = CelZ3Exprs.mkOrFlattened(ctx, matches);
    if (isLiteral) {
      return found;
    }
    CelType listType = guaranteedType(list, ast);
    CelType elemType =
        listType instanceof ListType ? ((ListType) listType).elemType() : SimpleType.DYN;
    return approximateWhere(
        context.recordUnrollLimitApproximation(
            CelZ3Exprs.mkAndFlattened(
                ctx,
                ctx.mkGt(length, ctx.mkInt(comprehensionUnrollLimit)),
                ctx.mkNot(found),
                mayEqualElement(lhs.z3Expr(), elemType))),
        uninterpreted(FUNC_APPROX_IN, lhs.z3Expr(), list.z3Expr()),
        found,
        taints);
  }

  // Whether lhs may equal an element of elemType: a number only across numeric types, and a list
  // only another list (anything else conservatively).
  private BoolExpr mayEqualElement(Expr<?> lhs, CelType elemType) {
    if (elemType instanceof NullableType) {
      return ctx.mkTrue();
    }
    if (isNumericType(elemType)) {
      return isNumeric(lhs);
    }
    return elemType.kind() == CelKind.LIST ? typeSystem.isList(lhs) : ctx.mkTrue();
  }

  // Where the recorded approximation holds, the verifier doesn't model the result, so it's the
  // given uninterpreted relation there.
  private BoolExpr approximateWhere(
      BoolExpr approximation, BoolExpr relation, BoolExpr exact, List<BoolExpr> taints) {
    taints.add(approximation);
    return (BoolExpr) ctx.mkITE(approximation, relation, exact);
  }

  private BoolExpr uninterpreted(String name, Expr<?> z3Expr0, Expr<?> z3Expr1) {
    Sort sort = typeSystem.celValueSort();
    FuncDecl<?> relation =
        typeSystem.internFuncDecl(name, new Sort[] {sort, sort}, ctx.getBoolSort());
    return (BoolExpr) ctx.mkApp(relation, z3Expr0, z3Expr1);
  }

  // Like approximateWhere, for CEL equality, which is symmetric.
  private BoolExpr approximateEqualityWhere(
      BoolExpr approximation,
      Expr<?> z3Expr0,
      Expr<?> z3Expr1,
      BoolExpr exact,
      List<BoolExpr> taints) {
    // Ordered by term id (in either direction) so that x == y and y == x share one application.
    // Asserting both orders instead doubles the applications, which made nested comparisons slow.
    boolean swap = z3Expr0.getId() > z3Expr1.getId();
    BoolExpr relation =
        uninterpreted(FUNC_APPROX_EQ, swap ? z3Expr1 : z3Expr0, swap ? z3Expr0 : z3Expr1);
    return approximateWhere(approximation, relation, exact, taints);
  }

  // Lists and maps compared up to the unroll limit may differ past it.
  private BoolExpr approximatePastUnrollLimit(
      ArithExpr length, Expr<?> z3Expr0, Expr<?> z3Expr1, List<BoolExpr> taints) {
    return approximateEqualityWhere(
        context.recordUnrollLimitApproximation(
            ctx.mkGt(length, ctx.mkInt(comprehensionUnrollLimit))),
        z3Expr0,
        z3Expr1,
        /* exact= */ ctx.mkTrue(),
        taints);
  }

  private static boolean hasContainerLiteralValue(TranslatedValue map) {
    return map.celExpr().get().map().entries().stream()
        .anyMatch(e -> isContainerLiteral(e.value()));
  }

  private static boolean isContainerLiteral(CelExpr expr) {
    return expr.getKind() == ExprKind.Kind.LIST || expr.getKind() == ExprKind.Kind.MAP;
  }

  private static boolean isContainerLiteral(TranslatedValue val) {
    return val.isLiteral(ExprKind.Kind.LIST) || val.isLiteral(ExprKind.Kind.MAP);
  }

  // CEL equality. Values read out of lists and maps that aren't literals (shallow, unless either is
  // a list or map literal) compare numbers numerically, but ints and doubles approximately, and
  // lists, maps, messages and optionals approximately (performance only).
  private TranslatedValue translateEquality(
      TranslatedValue arg0,
      TranslatedValue arg1,
      CelAbstractSyntaxTree ast,
      boolean isEquals,
      boolean shallow) {
    boolean isShallow = shallow && !isContainerLiteral(arg0) && !isContainerLiteral(arg1);
    Expr<?> z3Arg0 = arg0.z3Expr();
    Expr<?> z3Arg1 = arg1.z3Expr();

    CelType type0 = extractAstTypeOrDefault(arg0, ast);
    CelType type1 = extractAstTypeOrDefault(arg1, ast);

    BoolExpr equality;
    List<BoolExpr> taints = new ArrayList<>();
    if (!isShallow) {
      taints.add(mayObserveTruncatedTypeConstraint(ast, arg0, arg1));
    }

    if (isNumericType(type0) && isNumericType(type1)) {
      equality = getNumericEquality(arg0, arg1, ast, taints, /* approximateIntDouble= */ false);
    } else {
      BoolExpr bothLists = ctx.mkAnd(typeSystem.isList(z3Arg0), typeSystem.isList(z3Arg1));
      BoolExpr bothMaps = ctx.mkAnd(typeSystem.isMap(z3Arg0), typeSystem.isMap(z3Arg1));
      BoolExpr structuralEq;
      if (isShallow) {
        structuralEq =
            approximateEqualityWhere(
                context.recordApproximation(
                    "comparisons of lists, maps, messages or optionals read out of lists and maps",
                    CelZ3Exprs.mkOrFlattened(
                        ctx,
                        bothLists,
                        bothMaps,
                        ctx.mkAnd(typeSystem.isMessage(z3Arg0), typeSystem.isMessage(z3Arg1)),
                        ctx.mkAnd(typeSystem.isOptional(z3Arg0), typeSystem.isOptional(z3Arg1)))),
                z3Arg0,
                z3Arg1,
                /* exact= */ ctx.mkEq(z3Arg0, z3Arg1),
                taints);
      } else {
        // Lists and maps compare by CEL equality, unless their guaranteed types make it their
        // terms'.
        structuralEq = typeSystem.getStructuralEquality(z3Arg0, z3Arg1);
        if (!isExact(guaranteedType(arg0, ast), guaranteedType(arg1, ast))) {
          if (type0.kind() == CelKind.LIST || type1.kind() == CelKind.LIST) {
            structuralEq =
                (BoolExpr)
                    ctx.mkITE(bothLists, unrollListEquality(arg0, arg1, ast, taints), structuralEq);
          } else if (type0.kind() == CelKind.MAP || type1.kind() == CelKind.MAP) {
            BoolExpr mapEquality =
                isExact(type0, type1)
                        && (arg0.isLiteral(ExprKind.Kind.MAP) || arg1.isLiteral(ExprKind.Kind.MAP))
                    ? mapEqualityAtLiteralKeys(arg0, arg1)
                    : unrollMapEquality(arg0, arg1, ast, taints);
            structuralEq = (BoolExpr) ctx.mkITE(bothMaps, mapEquality, structuralEq);
          } else if (!isStaticallyKnown(type0) && !isStaticallyKnown(type1)) {
            // Performance only: a side with a known type isn't a list or map here.
            // TODO: Unless the type checker narrowed it, e.g. `(int_list +
            // dyn_var)[0]`.
            structuralEq =
                approximateEqualityWhere(
                    context.recordApproximation(
                        "comparisons of lists or maps typed dyn", ctx.mkOr(bothLists, bothMaps)),
                    z3Arg0,
                    z3Arg1,
                    structuralEq,
                    taints);
          }
        }
      }
      equality =
          (BoolExpr)
              ctx.mkITE(
                  ctx.mkAnd(isNumeric(z3Arg0), isNumeric(z3Arg1)),
                  getNumericEquality(
                      arg0, arg1, ast, taints, /* approximateIntDouble= */ isShallow),
                  structuralEq);
    }

    if (!isEquals) {
      equality = ctx.mkNot(equality);
    }

    Expr<?> equalityExpr = typeSystem.wrapBool(equality);
    String opName = isEquals ? Operator.EQUALS.getFunction() : Operator.NOT_EQUALS.getFunction();

    return TranslatedValue.propagateStrict(ctx, typeSystem, opName, equalityExpr, arg0, arg1)
        .withApproximation(CelZ3Exprs.mkOrFlattened(ctx, taints));
  }

  private Expr<?> buildListIndex(
      Expr<?> lhsTrans, Expr<?> rhsTrans, BoolExpr typeGuard, BoolExpr returnOptional) {
    Expr<?> listRef = typeSystem.getListRef(lhsTrans);
    SeqExpr<?> seq = typeSystem.getSeq(listRef);
    Expr<?> index = typeSystem.getInt(rhsTrans);
    BoolExpr inBounds =
        ctx.mkAnd(
            ctx.mkGe((ArithExpr) index, ctx.mkInt(0)),
            ctx.mkLt((ArithExpr) index, ctx.mkLength(seq)));

    return finishIndex(inBounds, ctx.mkNth(seq, (ArithExpr) index), typeGuard, returnOptional);
  }

  private Expr<?> buildMapIndex(
      Expr<?> lhsTrans, Expr<?> rhsTrans, BoolExpr typeGuard, BoolExpr returnOptional) {
    ImmutableList<CelZ3TypeSystem.EqualKey> equalKeys = typeSystem.equalKeys(rhsTrans);
    return finishIndex(
        typeSystem.hasEqualKey(lhsTrans, equalKeys),
        typeSystem.valueAtEqualKey(lhsTrans, equalKeys),
        typeGuard,
        returnOptional);
  }

  // Returns val where found, or else an error, as an optional (empty if not found) where
  // returnOptional holds.
  private Expr<?> finishIndex(
      BoolExpr found, Expr<?> val, BoolExpr typeGuard, BoolExpr returnOptional) {
    context.constrainValidValue(ctx.mkAnd(typeGuard, found), val);
    Expr<?> plain = ctx.mkITE(found, val, typeSystem.mkError());
    // Performance only: skips building an optional that isn't returned.
    if (returnOptional.isFalse()) {
      return plain;
    }
    Expr<?> optional = ctx.mkITE(found, context.mkOptionalOf(val), typeSystem.mkOptionalNone());
    return returnOptional.isTrue() ? optional : ctx.mkITE(returnOptional, optional, plain);
  }

  /**
   * Translates {@code lhs[rhs]}, or {@code lhs[?rhs]} if isOptional. An optional lhs is indexed
   * optionally (e.g. {@code optional.of([])[0]} is {@code optional.none()}).
   */
  private TranslatedValue translateIndex(
      List<TranslatedValue> args, CelAbstractSyntaxTree ast, boolean isOptional) {
    TranslatedValue lhs = args.get(0);
    TranslatedValue rhs = args.get(1);
    CelType lhsType = extractAstTypeOrDefault(lhs, ast);
    boolean isLhsTypeGuaranteed = lhsType.equals(guaranteedType(lhs, ast));

    Expr<?> lhsTrans = lhs.z3Expr();
    Expr<?> rhsTrans = rhs.z3Expr();

    BoolExpr isLhsOpt = ctx.mkFalse();
    BoolExpr lhsHasValue = ctx.mkFalse();
    BoolExpr shouldEvaluate = ctx.mkTrue();
    BoolExpr returnOptional = isOptional ? ctx.mkTrue() : ctx.mkFalse();

    // Performance only: an lhs of a known type other than optional isn't one.
    if (lhsType instanceof OptionalType || !isStaticallyKnown(lhsType)) {
      isLhsOpt = typeSystem.isOptional(lhsTrans);
      Expr<?> optRef = typeSystem.getOptionalRef(lhsTrans);
      lhsHasValue = typeSystem.optHasValue(optRef);

      lhsTrans = ctx.mkITE(isLhsOpt, typeSystem.getOptionalValue(optRef), lhsTrans);
      shouldEvaluate = (BoolExpr) ctx.mkITE(isLhsOpt, lhsHasValue, ctx.mkTrue());

      if (lhsType instanceof OptionalType) {
        lhsType = lhsType.parameters().get(0);
        returnOptional = ctx.mkTrue();
      } else if (!isOptional) {
        returnOptional = isLhsOpt;
      }
    }

    TranslatedValue element =
        buildAndConstrainIndex(
            lhsTrans, rhsTrans, lhsType, isLhsTypeGuaranteed, shouldEvaluate, returnOptional);
    // An empty optional gives an empty optional, unless no overload takes the index (e.g. a string
    // indexing an optional list).
    BoolExpr takesIndex =
        lhsType.kind() == CelKind.LIST ? typeSystem.isInt(rhsTrans) : ctx.mkTrue();
    Expr<?> actualValue =
        ctx.mkITE(
            CelZ3Exprs.mkAndFlattened(ctx, isLhsOpt, ctx.mkNot(lhsHasValue), takesIndex),
            typeSystem.mkOptionalNone(),
            element.z3Expr());

    String opName =
        isOptional ? Operator.OPTIONAL_INDEX.getFunction() : Operator.INDEX.getFunction();
    BoolExpr isListIndexTruncated =
        context.recordUnrollLimitApproximation(
            ctx.mkAnd(
                shouldEvaluate,
                typeSystem.isList(lhsTrans),
                typeSystem.isInt(rhsTrans),
                ctx.mkGe(typeSystem.getInt(rhsTrans), ctx.mkInt(comprehensionUnrollLimit)),
                ctx.mkLt(
                    typeSystem.getInt(rhsTrans),
                    ctx.mkLength(typeSystem.getSeq(typeSystem.getListRef(lhsTrans))))));
    BoolExpr isMapIndexTruncated = ctx.mkAnd(shouldEvaluate, context.isTruncatedMap(lhsTrans));
    return TranslatedValue.propagateStrict(ctx, typeSystem, opName, actualValue, args)
        .withApproximation(
            CelZ3Exprs.mkOrFlattened(
                ctx, isListIndexTruncated, isMapIndexTruncated, element.isApproximate()));
  }

  /**
   * Returns the element of {@code lhsTrans} at {@code rhsTrans}, which is approximate if it may not
   * conform to a static element type that is not guaranteed.
   */
  private TranslatedValue buildAndConstrainIndex(
      Expr<?> lhsTrans,
      Expr<?> rhsTrans,
      CelType lhsType,
      boolean isLhsTypeGuaranteed,
      BoolExpr shouldEvaluate,
      BoolExpr returnOptional) {
    CelType expectedElemType = null;
    if (lhsType.kind() == CelKind.LIST) {
      expectedElemType = ((ListType) lhsType).elemType();
    } else if (lhsType.kind() == CelKind.MAP) {
      expectedElemType = ((MapType) lhsType).valueType();
    }

    if (expectedElemType != null) {
      Expr<?> actualValue;
      if (lhsType.kind() == CelKind.MAP) {
        actualValue = buildMapIndex(lhsTrans, rhsTrans, shouldEvaluate, returnOptional);
      } else {
        BoolExpr isIntIndex = typeSystem.isInt(rhsTrans);
        actualValue =
            ctx.mkITE(
                isIntIndex,
                buildListIndex(
                    lhsTrans, rhsTrans, ctx.mkAnd(shouldEvaluate, isIntIndex), returnOptional),
                typeSystem.mkError());
      }

      CelType finalType =
          returnOptional.isTrue() ? OptionalType.create(expectedElemType) : expectedElemType;
      BoolExpr isDefined = ctx.mkAnd(shouldEvaluate, ctx.mkNot(typeSystem.isError(actualValue)));
      BoolExpr typeConstraint =
          typeConstraintGenerator.createTypeConstraintForType(actualValue, finalType);
      if (isLhsTypeGuaranteed) {
        context.addConstraint(ctx.mkImplies(isDefined, typeConstraint));
        return TranslatedValue.create(actualValue, typeSystem, ctx.mkFalse());
      }
      // The type checker may narrow other static types (e.g. `(['a'] + dyn_var)[1]` is typed
      // string), so the element may not conform.
      return TranslatedValue.create(
          actualValue,
          typeSystem,
          CelZ3Exprs.mkAndFlattened(
              ctx, isDefined, CelZ3Exprs.mkNotFlattened(ctx, typeConstraint)));
    }

    BoolExpr isListGuard =
        ctx.mkAnd(shouldEvaluate, typeSystem.isList(lhsTrans), typeSystem.isInt(rhsTrans));
    BoolExpr isMapGuard = ctx.mkAnd(shouldEvaluate, typeSystem.isMap(lhsTrans));

    return TranslatedValue.create(
        CelZ3Exprs.SwitchBuilder.newBuilder(ctx)
            .addCase(isListGuard, buildListIndex(lhsTrans, rhsTrans, isListGuard, returnOptional))
            .addCase(isMapGuard, buildMapIndex(lhsTrans, rhsTrans, isMapGuard, returnOptional))
            .build(typeSystem.mkError()),
        typeSystem,
        ctx.mkFalse());
  }

  private TranslatedValue translateConditional(
      List<TranslatedValue> args, CelAbstractSyntaxTree ast) {
    TranslatedValue cond = args.get(0);
    TranslatedValue trueBranch = args.get(1);
    TranslatedValue falseBranch = args.get(2);
    CelType condType = extractAstTypeOrDefault(cond, ast);

    BoolExpr condTrue = ctx.mkAnd(cond.isZ3Bool(), (BoolExpr) cond.unwrapZ3Bool());

    BoolExpr hasError = cond.isZ3Error();
    BoolExpr hasUnknown = cond.isZ3Unknown();
    if (!condType.equals(SimpleType.BOOL)) {
      hasError = ctx.mkOr(hasError, ctx.mkNot(cond.isZ3Bool()));
    }

    Expr<?> unknownResult =
        typeSystem.isParameterizingUnknowns()
            ? typeSystem.mkParameterizedUnknown(
                Operator.CONDITIONAL.getFunction(),
                ImmutableList.of(cond.z3Expr(), trueBranch.z3Expr(), falseBranch.z3Expr()))
            : cond.z3Expr();

    Expr<?> resultZ3 =
        CelZ3Exprs.SwitchBuilder.newBuilder(ctx)
            .addCase(hasUnknown, unknownResult)
            .addCase(hasError, typeSystem.mkError())
            .addCase(condTrue, trueBranch.z3Expr())
            .build(falseBranch.z3Expr());

    BoolExpr hasSafeError = ctx.mkAnd(hasError, ctx.mkNot(cond.isApproximate()));
    BoolExpr hasSafeUnknown = ctx.mkAnd(hasUnknown, ctx.mkNot(cond.isApproximate()));

    BoolExpr resultTaint =
        (BoolExpr)
            CelZ3Exprs.SwitchBuilder.newBuilder(ctx)
                .addCase(hasUnknown, ctx.mkNot(hasSafeUnknown))
                .addCase(hasError, ctx.mkNot(hasSafeError))
                .addCase(condTrue, ctx.mkOr(cond.isApproximate(), trueBranch.isApproximate()))
                .build(ctx.mkOr(cond.isApproximate(), falseBranch.isApproximate()));

    return TranslatedValue.create(resultZ3, typeSystem, resultTaint);
  }

  private TranslatedValue translateNotStrictlyFalse(List<TranslatedValue> args) {
    TranslatedValue arg = args.get(0);
    BoolExpr isFalse = ctx.mkAnd(arg.isZ3Bool(), ctx.mkNot((BoolExpr) arg.unwrapZ3Bool()));
    return TranslatedValue.create(
        typeSystem.wrapBool(ctx.mkNot(isFalse)), typeSystem, arg.isApproximate());
  }

  private static CelType extractAstTypeOrDefault(TranslatedValue val, CelAbstractSyntaxTree ast) {
    return val.celExpr().map(node -> ast.getTypeOrThrow(node.id())).orElse(SimpleType.DYN);
  }

  /**
   * Returns the type that {@code val} is guaranteed to conform to at runtime: a constant's, the
   * declared type of an input variable, or else dyn. Global constraints may only be derived from
   * guaranteed types: the type checker may narrow static types (e.g. {@code ['a'] + dyn_var} is
   * typed list(string)), so constraints derived from those could exclude valid inputs.
   */
  private CelType guaranteedType(TranslatedValue val, CelAbstractSyntaxTree ast) {
    return val.isLiteral(ExprKind.Kind.CONSTANT)
        ? extractAstTypeOrDefault(val, ast)
        : context.guaranteedType(val.z3Expr());
  }

  /**
   * Whether {@code type} itself is neither dyn nor a type parameter. Unlike {@link #isConcrete},
   * its type parameters may still be (e.g. {@code list(dyn)}).
   */
  static boolean isStaticallyKnown(CelType type) {
    CelKind kind = type.kind();
    return !kind.isDyn() && !kind.isTypeParam();
  }

  /** Whether {@code type} is a list or map whose elements (or keys) have a concrete type. */
  static boolean hasConcreteElements(CelType type) {
    return (type instanceof ListType || type instanceof MapType)
        && isConcrete(type.parameters().get(0));
  }

  /** Whether {@code type} and, recursively, its type parameters are statically known. */
  private static boolean isConcrete(CelType type) {
    return isStaticallyKnown(type)
        && type.parameters().stream().allMatch(CelZ3OperatorTranslator::isConcrete);
  }

  private static boolean isExact(CelType type0, CelType type1) {
    return type0.equals(type1) && hasStructuralEquality(type0);
  }

  /** Whether values of {@code type} are equal exactly when their terms are. */
  private static boolean hasStructuralEquality(CelType type) {
    switch (type.kind()) {
      case BOOL:
      case INT:
      case UINT:
      case STRING:
      case BYTES:
      case TIMESTAMP:
      case DURATION:
        return true;
      case LIST:
      case MAP:
        return type.parameters().stream().allMatch(CelZ3OperatorTranslator::hasStructuralEquality);
      default:
        // Doubles (NaN, -0.0), dyn, messages and the rest compare by CEL equality.
        return false;
    }
  }

  private static boolean isNumericType(CelType type) {
    CelKind kind = type.kind();
    return kind == CelKind.INT || kind == CelKind.UINT || kind == CelKind.DOUBLE;
  }

  /**
   * Whether observing {@code args} together may expose a value beyond a truncated type constraint
   * in any of them. Type constraints at the point of observation rule this out only if every arg
   * has a concrete static type (unlike e.g. {@code dyn(int_list)}).
   */
  private BoolExpr mayObserveTruncatedTypeConstraint(
      CelAbstractSyntaxTree ast, TranslatedValue... args) {
    if (Arrays.stream(args).allMatch(arg -> isConcrete(extractAstTypeOrDefault(arg, ast)))) {
      return ctx.mkFalse();
    }
    return context.recordUnrollLimitApproximation(
        CelZ3Exprs.mkOrFlattened(
            ctx,
            Arrays.stream(args)
                .map(arg -> typeSystem.hasTruncatedTypeConstraint(arg.z3Expr()))
                .collect(toImmutableList())));
  }

  CelZ3OperatorTranslator(CelZ3TranslationContext context, CelZ3FunctionRegistry functionRegistry) {
    this.ctx = context.ctx();
    this.typeSystem = context.typeSystem();
    this.context = context;
    this.typeConstraintGenerator = context.typeConstraintGenerator();
    this.functionRegistry = functionRegistry;
    this.comprehensionUnrollLimit = context.comprehensionUnrollLimit();
  }
}
