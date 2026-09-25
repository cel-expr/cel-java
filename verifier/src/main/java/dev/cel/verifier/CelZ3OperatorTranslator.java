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
import com.microsoft.z3.IntNum;
import com.microsoft.z3.SeqExpr;
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
import dev.cel.common.types.OptionalType;
import dev.cel.common.types.SimpleType;
import dev.cel.verifier.axioms.CelZ3OverloadResult;
import dev.cel.verifier.axioms.CelZ3OverloadTranslator;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Consumer;

/** Handles mapping CEL Operators to Z3 SMT logic. */
@SuppressWarnings({"unchecked", "rawtypes"}) // Z3 Java API uses raw types.
final class CelZ3OperatorTranslator {
  private final Context ctx;
  private final CelZ3TypeSystem typeSystem;
  private final Consumer<BoolExpr> constraintSink;
  private final BiFunction<Expr<?>, CelType, BoolExpr> typeConstraintGenerator;
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
          translator.translate(ctx, typeSystem, constraintSink, z3Args, argApproximations);

      if (!evaluatedResultOpt.isPresent()) {
        continue;
      }

      CelZ3OverloadResult evaluatedResult = evaluatedResultOpt.get();

      matchedAny = true;
      List<BoolExpr> typeGuards = new ArrayList<>();
      for (int i = 0; i < z3Args.size(); i++) {
        typeGuards.add(mkTypeGuard(z3Args.get(i), overload.parameterTypes().get(i)));
      }

      BoolExpr typeGuard = CelZ3TypeSystem.mkAndFlattened(ctx, typeGuards);
      currentZ3Result = ctx.mkITE(typeGuard, evaluatedResult.z3Expr(), currentZ3Result);
      currentApprox =
          (BoolExpr) ctx.mkITE(typeGuard, evaluatedResult.isApproximate(), currentApprox);
    }

    if (!matchedAny) {
      return opOpt.isPresent() ? Optional.of(resultChain) : Optional.empty();
    }

    if (opOpt.isPresent() && opOpt.get() == Operator.IN) {
      constrainInWitnessTypes(
          z3Args.get(0), z3Args.get(1), extractAstTypeOrDefault(args.get(1), ast));
    }

    return Optional.of(
        TranslatedValue.create(
            typeSystem.propagateErrorAndUnknown(functionName, currentZ3Result, z3Args, z3Args),
            typeSystem,
            currentApprox));
  }

  /**
   * Constrains every value that {@code InAxiom} may find in {@code rhs} to the static element (or
   * key) type.
   *
   * <p>Container type constraints are only unrolled up to the comprehension unroll limit, so
   * without this, a match beyond it could be an ill-typed value (e.g. a string found in a list of
   * ints).
   */
  private void constrainInWitnessTypes(Expr<?> lhs, Expr<?> rhs, CelType rhsType) {
    if (rhsType instanceof ListType) {
      // IN_LIST probes lhs itself, its int/uint reinterpretation for cross-type numeric equality,
      // and every zero (0.0 == -0.0 == 0 == 0u).
      IntExpr intVal =
          (IntExpr)
              ctx.mkITE(typeSystem.isInt(lhs), typeSystem.getInt(lhs), typeSystem.getUint(lhs));
      ImmutableList<Expr<?>> candidates =
          ImmutableList.of(
              lhs,
              typeSystem.wrapInt(intVal),
              typeSystem.wrapUint(intVal),
              typeSystem.mkDouble(0.0),
              typeSystem.mkDouble(-0.0),
              typeSystem.mkInt(0),
              typeSystem.mkUint(0));
      CelType elemType = ((ListType) rhsType).elemType();
      SeqExpr<?> seq = typeSystem.getSeq(typeSystem.getListRef(rhs));
      for (Expr<?> cand : candidates) {
        BoolExpr structContains = ctx.mkContains((Expr) seq, (Expr) ctx.mkUnit(cand));
        constraintSink.accept(
            ctx.mkImplies(
                ctx.mkAnd(typeSystem.isList(rhs), structContains),
                typeConstraintGenerator.apply(cand, elemType)));
      }
    } else if (rhsType instanceof MapType) {
      // IN_MAP only probes the presence of lhs itself.
      ArrayExpr mapPresence = (ArrayExpr) typeSystem.getMapPresence(typeSystem.getMapRef(rhs));
      BoolExpr inMap = (BoolExpr) ctx.mkSelect(mapPresence, lhs);
      constraintSink.accept(
          ctx.mkImplies(
              ctx.mkAnd(typeSystem.isMap(rhs), inMap),
              typeConstraintGenerator.apply(lhs, ((MapType) rhsType).keyType())));
    }
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
        return translateEquality(args.get(0), args.get(1), ast, /* isEquals= */ true);
      case NOT_EQUALS:
        return translateEquality(args.get(0), args.get(1), ast, /* isEquals= */ false);
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
        return translateIndex(args, ast, false);
      case OPTIONAL_INDEX:
        return translateIndex(args, ast, true);
      case CONDITIONAL:
        return translateConditional(args, ast);
      case NOT_STRICTLY_FALSE:
      case OLD_NOT_STRICTLY_FALSE:
        return translateNotStrictlyFalse(args);
      default:
        // For operators we haven't implemented cleanly, just wrap uninterpreted for now.
        return TranslatedValue.propagateStrict(
                ctx, typeSystem, op.getFunction(), typeSystem.mkUnknown(), args)
            .withApproximation(ctx.mkTrue());
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
    BoolExpr hasError = ctx.mkOr(a.isZ3Error(), b.isZ3Error());

    BoolExpr aMatch = isAnd ? aFalse : aTrue;
    BoolExpr bMatch = isAnd ? bFalse : bTrue;

    BoolExpr hasSafeMatch =
        ctx.mkOr(
            ctx.mkAnd(aMatch, ctx.mkNot(a.isApproximate())),
            ctx.mkAnd(bMatch, ctx.mkNot(b.isApproximate())));

    BoolExpr hasSafeError =
        ctx.mkOr(
            ctx.mkAnd(a.isZ3Error(), ctx.mkNot(a.isApproximate())),
            ctx.mkAnd(b.isZ3Error(), ctx.mkNot(b.isApproximate())));

    BoolExpr hasSafeUnknown =
        ctx.mkOr(
            ctx.mkAnd(a.isZ3Unknown(), ctx.mkNot(a.isApproximate())),
            ctx.mkAnd(b.isZ3Unknown(), ctx.mkNot(b.isApproximate())));

    String opName = isAnd ? Operator.LOGICAL_AND.getFunction() : Operator.LOGICAL_OR.getFunction();
    Expr<?> unknownResult =
        typeSystem.isParameterizingUnknowns()
            ? typeSystem.mkPropagatedUnknown(opName, ImmutableList.of(a.z3Expr(), b.z3Expr()))
            : ctx.mkITE(a.isZ3Unknown(), a.z3Expr(), b.z3Expr());

    Expr<?> resultZ3 =
        CelZ3TypeSystem.SwitchBuilder.newBuilder(ctx)
            .addCase(hasMatch, typeSystem.mkBool(!isAnd))
            .addCase(hasUnknown, unknownResult)
            .addCase(hasError, typeSystem.mkError())
            .build(typeSystem.mkBool(isAnd));

    BoolExpr unknownTaint =
        typeSystem.isParameterizingUnknowns()
            ? ctx.mkOr(a.isApproximate(), b.isApproximate())
            : ctx.mkNot(hasSafeUnknown);

    BoolExpr resultTaint =
        (BoolExpr)
            CelZ3TypeSystem.SwitchBuilder.newBuilder(ctx)
                .addCase(hasMatch, ctx.mkNot(hasSafeMatch))
                .addCase(hasUnknown, unknownTaint)
                .addCase(hasError, ctx.mkNot(hasSafeError))
                .build(ctx.mkOr(a.isApproximate(), b.isApproximate()));

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
          CelZ3TypeSystem.SwitchBuilder.newBuilder(ctx)
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
        CelZ3TypeSystem.SwitchBuilder.newBuilder(ctx)
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
      TranslatedValue arg0, TranslatedValue arg1, CelAbstractSyntaxTree ast) {
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

    return getDynamicNumericEquality(arg0.z3Expr(), arg1.z3Expr());
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

  private BoolExpr mkIsFiniteDouble(Expr<?> z3Expr) {
    FPExpr fpVal = typeSystem.getDouble(z3Expr);
    return ctx.mkAnd(
        typeSystem.isDouble(z3Expr),
        ctx.mkNot(ctx.mkOr(ctx.mkFPIsNaN(fpVal), ctx.mkFPIsInfinite(fpVal))));
  }

  private BoolExpr getDynamicNumericEquality(Expr<?> z3Expr0, Expr<?> z3Expr1) {
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

    FPExpr fpVal1 = typeSystem.getDouble(z3Expr1);
    ArithExpr<?> realVal0 = ctx.mkInt2Real(val0);
    BoolExpr intDoubleEq =
        ctx.mkAnd(
            mkIsFiniteDouble(z3Expr1),
            ctx.mkLe(realVal0, ctx.mkFPToReal(fpVal1)),
            ctx.mkLe(ctx.mkFPToReal(fpVal1), realVal0));

    FPExpr fpVal0 = typeSystem.getDouble(z3Expr0);
    ArithExpr<?> realVal1 = ctx.mkInt2Real(val1);
    BoolExpr doubleIntEq =
        ctx.mkAnd(
            mkIsFiniteDouble(z3Expr0),
            ctx.mkLe(realVal1, ctx.mkFPToReal(fpVal0)),
            ctx.mkLe(ctx.mkFPToReal(fpVal0), realVal1));

    return (BoolExpr)
        CelZ3TypeSystem.SwitchBuilder.newBuilder(ctx)
            .addCase(bothIntOrUint, ctx.mkEq(val0, val1))
            .addCase(
                bothDouble,
                ctx.mkFPEq(typeSystem.getDouble(z3Expr0), typeSystem.getDouble(z3Expr1)))
            .addCase(isIntOrUintAndDouble, intDoubleEq)
            .addCase(isDoubleAndIntOrUint, doubleIntEq)
            .build(ctx.mkFalse());
  }

  private boolean hasOptionalElements(TranslatedValue arg) {
    return arg.isLiteral(ExprKind.Kind.LIST)
        && !arg.celExpr().get().list().optionalIndices().isEmpty();
  }

  private BoolExpr unrollListEquality(
      TranslatedValue listA, TranslatedValue listB, CelAbstractSyntaxTree ast) {
    CelExpr literalListAst =
        listA.isLiteral(ExprKind.Kind.LIST) ? listA.celExpr().get() : listB.celExpr().get();
    CelType type0 = extractAstTypeOrDefault(listA, ast);
    CelType type1 = extractAstTypeOrDefault(listB, ast);

    SeqExpr<?> seq0 = typeSystem.getSeq(typeSystem.getListRef(listA.z3Expr()));
    SeqExpr<?> seq1 = typeSystem.getSeq(typeSystem.getListRef(listB.z3Expr()));

    List<BoolExpr> equalities = new ArrayList<>();
    equalities.add(ctx.mkEq(ctx.mkLength(seq0), ctx.mkLength(seq1)));

    int size = literalListAst.list().elements().size();
    for (int i = 0; i < size; i++) {
      IntExpr idx = ctx.mkInt(i);
      Expr<?> elem0 = ctx.mkNth(seq0, idx);
      Expr<?> elem1 = ctx.mkNth(seq1, idx);

      if (i >= comprehensionUnrollLimit) {
        constrainListElement(seq0, idx, elem0, type0);
        constrainListElement(seq1, idx, elem1, type1);
      }

      TranslatedValue elemA =
          TranslatedValue.create(elem0, listA.listElementAt(i), typeSystem, listA.isApproximate());
      TranslatedValue elemB =
          TranslatedValue.create(elem1, listB.listElementAt(i), typeSystem, listB.isApproximate());

      TranslatedValue elemEquality = translateEquality(elemA, elemB, ast, /* isEquals= */ true);
      Expr<?> eqZ3 = elemEquality.z3Expr();

      BoolExpr isBool = typeSystem.isBool(eqZ3);
      BoolExpr isTrue = ctx.mkAnd(isBool, (BoolExpr) typeSystem.unwrapBool(eqZ3));

      equalities.add(isTrue);
    }

    return CelZ3TypeSystem.mkAndFlattened(ctx, equalities);
  }

  private void constrainListElement(SeqExpr<?> seq, IntExpr idx, Expr<?> elem, CelType listType) {
    if (listType instanceof ListType) {
      constraintSink.accept(
          ctx.mkImplies(
              ctx.mkLt(idx, ctx.mkLength(seq)),
              typeConstraintGenerator.apply(elem, ((ListType) listType).elemType())));
    }
  }

  private TranslatedValue translateEquality(
      TranslatedValue arg0, TranslatedValue arg1, CelAbstractSyntaxTree ast, boolean isEquals) {
    Expr<?> z3Arg0 = arg0.z3Expr();
    Expr<?> z3Arg1 = arg1.z3Expr();

    CelType type0 = extractAstTypeOrDefault(arg0, ast);
    CelType type1 = extractAstTypeOrDefault(arg1, ast);

    BoolExpr equality;

    if (isNumericType(type0) && isNumericType(type1)) {
      equality = getNumericEquality(arg0, arg1, ast);
    } else if (type0.kind() == CelKind.LIST
        && type1.kind() == CelKind.LIST
        && (arg0.isLiteral(ExprKind.Kind.LIST) || arg1.isLiteral(ExprKind.Kind.LIST))
        && !hasOptionalElements(arg0)
        && !hasOptionalElements(arg1)) {
      equality = unrollListEquality(arg0, arg1, ast);
    } else if (isStaticallyKnown(type0) && isStaticallyKnown(type1)) {
      equality = typeSystem.getStructuralEquality(z3Arg0, z3Arg1);
    } else {
      boolean canBeNumeric0 = type0.kind() == CelKind.DYN || isNumericType(type0);
      boolean canBeNumeric1 = type1.kind() == CelKind.DYN || isNumericType(type1);

      // Check if one side is an explicit LIST that we can unroll
      BoolExpr structuralEq = typeSystem.getStructuralEquality(z3Arg0, z3Arg1);
      if ((arg0.isLiteral(ExprKind.Kind.LIST) || arg1.isLiteral(ExprKind.Kind.LIST))
          && !hasOptionalElements(arg0)
          && !hasOptionalElements(arg1)) {
        structuralEq =
            (BoolExpr)
                ctx.mkITE(
                    ctx.mkAnd(typeSystem.isList(z3Arg0), typeSystem.isList(z3Arg1)),
                    unrollListEquality(arg0, arg1, ast),
                    structuralEq);
      }

      if (canBeNumeric0 && canBeNumeric1) {
        BoolExpr bothNumeric = ctx.mkAnd(isNumeric(z3Arg0), isNumeric(z3Arg1));
        equality =
            (BoolExpr) ctx.mkITE(bothNumeric, getNumericEquality(arg0, arg1, ast), structuralEq);
      } else {
        equality = structuralEq;
      }
    }

    if (!isEquals) {
      equality = ctx.mkNot(equality);
    }

    Expr<?> equalityExpr = typeSystem.wrapBool(equality);
    String opName = isEquals ? Operator.EQUALS.getFunction() : Operator.NOT_EQUALS.getFunction();

    return TranslatedValue.propagateStrict(ctx, typeSystem, opName, equalityExpr, arg0, arg1);
  }

  private Expr<?> buildListIndex(
      Expr<?> lhsTrans, Expr<?> rhsTrans, BoolExpr typeGuard, boolean isOptional) {
    Expr<?> listRef = typeSystem.getListRef(lhsTrans);
    SeqExpr<?> seq = typeSystem.getSeq(listRef);
    Expr<?> index = typeSystem.getInt(rhsTrans);
    BoolExpr inBounds =
        ctx.mkAnd(
            ctx.mkGe((ArithExpr) index, ctx.mkInt(0)),
            ctx.mkLt((ArithExpr) index, ctx.mkLength(seq)));

    Expr<?> val = ctx.mkNth(seq, (ArithExpr) index);
    constrainValidElement(ctx.mkAnd(typeGuard, inBounds), val);

    if (isOptional) {
      Expr<?> resultOptRef = ctx.mkApp(typeSystem.optionalOfRefFunc(), val);
      constraintSink.accept(ctx.mkEq(typeSystem.getOptionalValue(resultOptRef), val));
      constraintSink.accept(typeSystem.optHasValue(resultOptRef));
      return ctx.mkITE(
          inBounds, typeSystem.mkOptionalOf(resultOptRef), typeSystem.mkOptionalNone());
    }

    return ctx.mkITE(inBounds, val, typeSystem.mkError());
  }

  private void constrainValidElement(BoolExpr guard, Expr<?> val) {
    constraintSink.accept(ctx.mkImplies(guard, ctx.mkNot(typeSystem.isErrorOrUnknown(val))));
  }

  private Optional<Long> extractIntNumSafe(Expr<?> expr) {
    Expr<?> simplified = expr.simplify();

    if (!simplified.isApp()) {
      return Optional.empty();
    }

    FuncDecl<?> decl = simplified.getFuncDecl();
    if (decl.equals(typeSystem.intCons().ConstructorDecl())
        || decl.equals(typeSystem.uintCons().ConstructorDecl())) {
      return Optional.of(simplified.getArgs()[0])
          .filter(IntNum.class::isInstance)
          .map(IntNum.class::cast)
          .map(IntNum::getInt64);
    }

    return Optional.empty();
  }

  private static final class ProbeResult {
    private final BoolExpr altInMap;
    private final Expr<?> altVal;

    private ProbeResult(BoolExpr altInMap, Expr<?> altVal) {
      this.altInMap = altInMap;
      this.altVal = altVal;
    }
  }

  private ProbeResult createProbeResult(
      BoolExpr inMapOrig,
      Expr<?> valOrig,
      BoolExpr cond1,
      Expr<?> key1,
      BoolExpr cond2,
      Expr<?> key2,
      ArrayExpr mapPresence,
      ArrayExpr mapValues) {
    BoolExpr inMap1 = (BoolExpr) ctx.mkSelect(mapPresence, key1);
    Expr<?> val1 = ctx.mkSelect(mapValues, key1);
    BoolExpr inMap2 = (BoolExpr) ctx.mkSelect(mapPresence, key2);
    Expr<?> val2 = ctx.mkSelect(mapValues, key2);

    BoolExpr condMap1 = CelZ3TypeSystem.mkAndFlattened(ctx, cond1, inMap1);
    BoolExpr condMap2 = CelZ3TypeSystem.mkAndFlattened(ctx, cond2, inMap2);

    BoolExpr altInMap = CelZ3TypeSystem.mkOrFlattened(ctx, inMapOrig, condMap1, condMap2);
    Expr<?> altVal =
        CelZ3TypeSystem.SwitchBuilder.newBuilder(ctx)
            .addCase(inMapOrig, valOrig)
            .addCase(condMap1, val1)
            .addCase(condMap2, val2)
            .build(valOrig);

    return new ProbeResult(altInMap, altVal);
  }

  private Expr<?> buildMapIndex(
      Expr<?> lhsTrans, Expr<?> rhsTrans, BoolExpr typeGuard, boolean isOptional) {
    Expr<?> mapRef = typeSystem.getMapRef(lhsTrans);
    ArrayExpr mapValues = (ArrayExpr) typeSystem.getMapValues(mapRef);
    ArrayExpr mapPresence = (ArrayExpr) typeSystem.getMapPresence(mapRef);

    BoolExpr inMapOrig = (BoolExpr) ctx.mkSelect(mapPresence, rhsTrans);
    Expr<?> valOrig = ctx.mkSelect(mapValues, rhsTrans);

    BoolExpr isInt = typeSystem.isInt(rhsTrans);
    BoolExpr isUint = typeSystem.isUint(rhsTrans);
    BoolExpr isDouble = typeSystem.isDouble(rhsTrans);

    // Common double key for both int and uint
    FPExpr intUintFp;
    Optional<Long> rhsNum = extractIntNumSafe(rhsTrans);
    boolean hasExactDouble = rhsNum.isPresent();
    if (hasExactDouble) {
      intUintFp = typeSystem.mkFpDouble((double) rhsNum.get());
    } else {
      intUintFp = typeSystem.mkFpDouble(0.0);
    }
    Expr<?> intUintDoubleKey = typeSystem.wrapDouble(intUintFp);

    // Int probes
    IntExpr rawInt = (IntExpr) ctx.mkITE(isInt, typeSystem.getInt(rhsTrans), ctx.mkInt(0));
    BoolExpr intHasUint = ctx.mkGe(rawInt, ctx.mkInt(0));
    Expr<?> intUintKey = typeSystem.wrapUint(rawInt);

    BoolExpr intHasDouble = hasExactDouble ? isInt : ctx.mkFalse();
    ProbeResult intProbe =
        createProbeResult(
            inMapOrig,
            valOrig,
            intHasUint,
            intUintKey,
            intHasDouble,
            intUintDoubleKey,
            mapPresence,
            mapValues);

    // Uint probes
    IntExpr rawUint = (IntExpr) ctx.mkITE(isUint, typeSystem.getUint(rhsTrans), ctx.mkInt(0));
    BoolExpr uintHasInt = ctx.mkLe(rawUint, ctx.mkInt(CelNumericBounds.MAX_INT64));
    Expr<?> uintIntKey = typeSystem.wrapInt(rawUint);

    BoolExpr uintHasDouble = hasExactDouble ? isUint : ctx.mkFalse();
    ProbeResult uintProbe =
        createProbeResult(
            inMapOrig,
            valOrig,
            uintHasInt,
            uintIntKey,
            uintHasDouble,
            intUintDoubleKey,
            mapPresence,
            mapValues);

    // Double probes
    FPExpr dVal =
        (FPExpr)
            (Expr) ctx.mkITE(isDouble, typeSystem.getDouble(rhsTrans), typeSystem.mkFpDouble(0.0));
    IntExpr dInt = ctx.mkReal2Int(ctx.mkFPToReal(dVal));
    BoolExpr doubleIsExact =
        ctx.mkAnd(
            isDouble,
            ctx.mkNot(ctx.mkOr(ctx.mkFPIsNaN(dVal), ctx.mkFPIsInfinite(dVal))),
            ctx.mkEq(ctx.mkInt2Real(dInt), ctx.mkFPToReal(dVal)));
    Expr<?> doubleIntKey = typeSystem.wrapInt(dInt);
    Expr<?> doubleUintKey = typeSystem.wrapUint(dInt);
    BoolExpr doubleHasUint = ctx.mkAnd(doubleIsExact, ctx.mkGe(dInt, ctx.mkInt(0)));
    ProbeResult doubleProbe =
        createProbeResult(
            inMapOrig,
            valOrig,
            doubleIsExact,
            doubleIntKey,
            doubleHasUint,
            doubleUintKey,
            mapPresence,
            mapValues);

    BoolExpr finalInMap =
        (BoolExpr)
            CelZ3TypeSystem.SwitchBuilder.newBuilder(ctx)
                .addCase(isInt, intProbe.altInMap)
                .addCase(isUint, uintProbe.altInMap)
                .addCase(isDouble, doubleProbe.altInMap)
                .build(inMapOrig);

    Expr<?> finalVal =
        CelZ3TypeSystem.SwitchBuilder.newBuilder(ctx)
            .addCase(isInt, intProbe.altVal)
            .addCase(isUint, uintProbe.altVal)
            .addCase(isDouble, doubleProbe.altVal)
            .build(valOrig);

    constrainValidElement(ctx.mkAnd(typeGuard, finalInMap), finalVal);

    if (isOptional) {
      Expr<?> resultOptRef = ctx.mkApp(typeSystem.optionalOfRefFunc(), finalVal);
      constraintSink.accept(ctx.mkEq(typeSystem.getOptionalValue(resultOptRef), finalVal));
      constraintSink.accept(typeSystem.optHasValue(resultOptRef));
      return ctx.mkITE(
          finalInMap, typeSystem.mkOptionalOf(resultOptRef), typeSystem.mkOptionalNone());
    }

    return ctx.mkITE(finalInMap, finalVal, typeSystem.mkError());
  }

  private TranslatedValue translateIndex(
      List<TranslatedValue> args, CelAbstractSyntaxTree ast, boolean isOptional) {
    TranslatedValue lhs = args.get(0);
    TranslatedValue rhs = args.get(1);
    CelType lhsType = extractAstTypeOrDefault(lhs, ast);

    Expr<?> lhsTrans = lhs.z3Expr();
    Expr<?> rhsTrans = rhs.z3Expr();

    BoolExpr isLhsOpt = ctx.mkFalse();
    BoolExpr lhsHasValue = ctx.mkFalse();
    BoolExpr shouldEvaluate = ctx.mkTrue();

    if (isOptional) {
      isLhsOpt = typeSystem.isOptional(lhsTrans);
      Expr<?> optRef = typeSystem.getOptionalRef(lhsTrans);
      lhsHasValue = typeSystem.optHasValue(optRef);

      lhsTrans = ctx.mkITE(isLhsOpt, typeSystem.getOptionalValue(optRef), lhsTrans);
      shouldEvaluate = (BoolExpr) ctx.mkITE(isLhsOpt, lhsHasValue, ctx.mkTrue());

      if (lhsType instanceof OptionalType) {
        lhsType = lhsType.parameters().get(0);
      }
    }

    Expr<?> actualValue =
        buildAndConstrainIndex(lhsTrans, rhsTrans, lhsType, shouldEvaluate, isOptional);

    if (isOptional) {
      actualValue =
          ctx.mkITE(
              ctx.mkAnd(isLhsOpt, ctx.mkNot(lhsHasValue)),
              typeSystem.mkOptionalNone(),
              actualValue);
    }

    String opName =
        isOptional ? Operator.OPTIONAL_INDEX.getFunction() : Operator.INDEX.getFunction();
    BoolExpr isListIndexTruncated =
        ctx.mkAnd(
            shouldEvaluate,
            typeSystem.isList(lhsTrans),
            typeSystem.isInt(rhsTrans),
            ctx.mkGe(typeSystem.getInt(rhsTrans), ctx.mkInt(comprehensionUnrollLimit)),
            ctx.mkLt(
                typeSystem.getInt(rhsTrans),
                ctx.mkLength(typeSystem.getSeq(typeSystem.getListRef(lhsTrans)))));
    BoolExpr isMapIndexTruncated =
        ctx.mkAnd(
            shouldEvaluate,
            typeSystem.isMap(lhsTrans),
            ctx.mkGt(
                ctx.mkLength(typeSystem.getMapKeys(typeSystem.getMapRef(lhsTrans))),
                ctx.mkInt(comprehensionUnrollLimit)));
    return TranslatedValue.propagateStrict(ctx, typeSystem, opName, actualValue, args)
        .withApproximation(ctx.mkOr(isListIndexTruncated, isMapIndexTruncated));
  }

  private Expr<?> buildAndConstrainIndex(
      Expr<?> lhsTrans,
      Expr<?> rhsTrans,
      CelType lhsType,
      BoolExpr shouldEvaluate,
      boolean isOptional) {
    CelType expectedElemType = null;
    if (lhsType.kind() == CelKind.LIST) {
      expectedElemType = ((ListType) lhsType).elemType();
    } else if (lhsType.kind() == CelKind.MAP) {
      expectedElemType = ((MapType) lhsType).valueType();
    }

    if (expectedElemType != null) {
      Expr<?> actualValue;
      if (lhsType.kind() == CelKind.MAP) {
        actualValue = buildMapIndex(lhsTrans, rhsTrans, shouldEvaluate, isOptional);
      } else {
        BoolExpr isIntIndex = typeSystem.isInt(rhsTrans);
        actualValue =
            ctx.mkITE(
                isIntIndex,
                buildListIndex(
                    lhsTrans, rhsTrans, ctx.mkAnd(shouldEvaluate, isIntIndex), isOptional),
                typeSystem.mkError());
      }

      CelType finalType = isOptional ? OptionalType.create(expectedElemType) : expectedElemType;

      constraintSink.accept(
          ctx.mkImplies(
              ctx.mkAnd(shouldEvaluate, ctx.mkNot(typeSystem.isError(actualValue))),
              typeConstraintGenerator.apply(actualValue, finalType)));

      return actualValue;
    }

    BoolExpr isListGuard =
        ctx.mkAnd(shouldEvaluate, typeSystem.isList(lhsTrans), typeSystem.isInt(rhsTrans));
    BoolExpr isMapGuard = ctx.mkAnd(shouldEvaluate, typeSystem.isMap(lhsTrans));

    return CelZ3TypeSystem.SwitchBuilder.newBuilder(ctx)
        .addCase(isListGuard, buildListIndex(lhsTrans, rhsTrans, isListGuard, isOptional))
        .addCase(isMapGuard, buildMapIndex(lhsTrans, rhsTrans, isMapGuard, isOptional))
        .build(typeSystem.mkError());
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
            ? typeSystem.mkPropagatedUnknown(
                Operator.CONDITIONAL.getFunction(),
                ImmutableList.of(cond.z3Expr(), trueBranch.z3Expr(), falseBranch.z3Expr()))
            : cond.z3Expr();

    Expr<?> resultZ3 =
        CelZ3TypeSystem.SwitchBuilder.newBuilder(ctx)
            .addCase(hasUnknown, unknownResult)
            .addCase(hasError, typeSystem.mkError())
            .addCase(condTrue, trueBranch.z3Expr())
            .build(falseBranch.z3Expr());

    BoolExpr hasSafeError = ctx.mkAnd(hasError, ctx.mkNot(cond.isApproximate()));
    BoolExpr hasSafeUnknown = ctx.mkAnd(hasUnknown, ctx.mkNot(cond.isApproximate()));
    BoolExpr unknownTaint =
        typeSystem.isParameterizingUnknowns()
            ? ctx.mkOr(
                cond.isApproximate(), trueBranch.isApproximate(), falseBranch.isApproximate())
            : ctx.mkNot(hasSafeUnknown);

    BoolExpr resultTaint =
        (BoolExpr)
            CelZ3TypeSystem.SwitchBuilder.newBuilder(ctx)
                .addCase(hasUnknown, unknownTaint)
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

  private static boolean isStaticallyKnown(CelType type) {
    CelKind kind = type.kind();
    return !kind.isDyn() && !kind.isTypeParam();
  }

  private static boolean isNumericType(CelType type) {
    CelKind kind = type.kind();
    return kind == CelKind.INT || kind == CelKind.UINT || kind == CelKind.DOUBLE;
  }

  CelZ3OperatorTranslator(
      Context ctx,
      CelZ3TypeSystem typeSystem,
      Consumer<BoolExpr> constraintSink,
      BiFunction<Expr<?>, CelType, BoolExpr> typeConstraintGenerator,
      CelZ3FunctionRegistry functionRegistry,
      int comprehensionUnrollLimit) {
    this.ctx = ctx;
    this.typeSystem = typeSystem;
    this.constraintSink = constraintSink;
    this.typeConstraintGenerator = typeConstraintGenerator;
    this.functionRegistry = functionRegistry;
    this.comprehensionUnrollLimit = comprehensionUnrollLimit;
  }
}
