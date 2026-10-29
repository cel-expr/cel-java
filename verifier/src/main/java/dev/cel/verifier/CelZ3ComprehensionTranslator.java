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

import com.google.common.collect.ImmutableList;
import com.microsoft.z3.ArithExpr;
import com.microsoft.z3.ArrayExpr;
import com.microsoft.z3.BoolExpr;
import com.microsoft.z3.Context;
import com.microsoft.z3.Expr;
import com.microsoft.z3.IntExpr;
import com.microsoft.z3.SeqExpr;
import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.Operator;
import dev.cel.common.ast.CelConstant;
import dev.cel.common.ast.CelExpr;
import dev.cel.common.ast.CelExpr.CelComprehension;
import dev.cel.common.ast.CelExpr.ExprKind;
import dev.cel.common.types.CelType;
import dev.cel.common.types.ListType;
import dev.cel.common.types.MapType;
import dev.cel.common.types.SimpleType;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Supplier;

/** Translates CEL comprehensions to Z3, unrolling them up to the comprehension unroll limit. */
@SuppressWarnings({"unchecked", "rawtypes"}) // Z3 Java API uses raw types.
final class CelZ3ComprehensionTranslator {

  private final CelZ3TranslationContext context;
  private final Context ctx;
  private final CelZ3TypeSystem typeSystem;
  private final int comprehensionUnrollLimit;
  private final BiFunction<CelExpr, CelAbstractSyntaxTree, TranslatedValue> exprTranslator;

  TranslatedValue translateComprehension(CelExpr celExpr, CelAbstractSyntaxTree ast) {
    CelComprehension comp = celExpr.comprehension();
    CelExpr iterRangeExpr = comp.iterRange();
    if (iterRangeExpr.exprKind().getKind() == ExprKind.Kind.IDENT) {
      iterRangeExpr =
          context
              .lookupSymbol(iterRangeExpr.ident().name())
              .flatMap(TranslatedValue::celExpr)
              .orElse(iterRangeExpr);
    }
    List<IterationElement> iterationElements = new ArrayList<>();
    List<BoolExpr> taints = new ArrayList<>();
    List<Expr<?>> allRangeElems = new ArrayList<>();

    // For statically known list/map literals, unroll them exactly.
    if (iterRangeExpr.exprKind().getKind() == ExprKind.Kind.LIST
        && iterRangeExpr.list().optionalIndices().isEmpty()) {
      ImmutableList<CelExpr> elements = iterRangeExpr.list().elements();
      for (int i = 0; i < elements.size(); i++) {
        TranslatedValue valueTv = translateExpr(elements.get(i), ast);
        Expr<?> value = valueTv.z3Expr();
        taints.add(valueTv.isApproximate());
        iterationElements.add(new IterationElement(typeSystem.mkInt(i), value));
        allRangeElems.add(value);
      }
    } else if (iterRangeExpr.exprKind().getKind() == ExprKind.Kind.MAP
        && iterRangeExpr.map().entries().stream().noneMatch(CelExpr.CelMap.Entry::optionalEntry)) {
      for (CelExpr.CelMap.Entry entry : iterRangeExpr.map().entries()) {
        TranslatedValue keyTv = translateExpr(entry.key(), ast);
        Expr<?> key = keyTv.z3Expr();
        taints.add(keyTv.isApproximate());
        TranslatedValue valueTv = translateExpr(entry.value(), ast);
        Expr<?> value = valueTv.z3Expr();
        taints.add(valueTv.isApproximate());
        iterationElements.add(new IterationElement(key, value));
        allRangeElems.add(key);
        allRangeElems.add(value);
      }
    } else {
      return translateDynamicComprehension(celExpr, ast);
    }

    TranslatedValue accuTv = translateExpr(comp.accuInit(), ast);
    Expr<?> accu = accuTv.z3Expr();
    taints.add(accuTv.isApproximate());
    boolean isMap = iterRangeExpr.exprKind().getKind() == ExprKind.Kind.MAP;
    boolean isTwoVar = !comp.iterVar2().isEmpty();

    for (IterationElement iterElem : iterationElements) {
      Expr<?> currentAccu = accu;
      accu =
          withIterationScope(
              comp,
              iterElem.keyOrIndex,
              iterElem.value,
              currentAccu,
              isMap,
              isTwoVar,
              () -> {
                TranslatedValue condTv = translateExpr(comp.loopCondition(), ast);
                TranslatedValue stepTv = translateExpr(comp.loopStep(), ast);
                taints.add(stepTv.isApproximate());
                return ctx.mkITE(
                    (BoolExpr) typeSystem.unwrapBool(condTv.z3Expr()),
                    stepTv.z3Expr(),
                    currentAccu);
              });
    }

    TranslatedValue resultTv =
        context.withScope(
            comp.accuVar(),
            TranslatedValue.create(accu, typeSystem, CelZ3Exprs.mkOrFlattened(ctx, taints)),
            () -> translateExpr(comp.result(), ast));
    Expr<?> result = resultTv.z3Expr();
    return TranslatedValue.create(
        typeSystem.propagateErrorAndUnknown(
            "comprehension",
            result,
            allRangeElems,
            ImmutableList.<Expr<?>>builder().add(result).addAll(allRangeElems).build()),
        celExpr,
        typeSystem,
        resultTv.isApproximate());
  }

  private static final class BoundedIteration {
    private final BoolExpr inBounds;
    private final TranslatedValue stepResult;

    private BoundedIteration(BoolExpr inBounds, TranslatedValue stepResult) {
      this.inBounds = inBounds;
      this.stepResult = stepResult;
    }
  }

  private TranslatedValue translateDynamicComprehension(
      CelExpr celExpr, CelAbstractSyntaxTree ast) {
    CelComprehension comp = celExpr.comprehension();
    TranslatedValue iterRangeTv = translateExpr(comp.iterRange(), ast);
    Expr<?> iterRange = iterRangeTv.z3Expr();
    CelType rangeType = ast.getType(comp.iterRange().id()).orElse(SimpleType.DYN);

    boolean isList = rangeType instanceof ListType;
    boolean isMap = rangeType instanceof MapType;
    if (!isList && !isMap) {
      BoolExpr isRuntimeListOrMap =
          ctx.mkOr(typeSystem.isList(iterRange), typeSystem.isMap(iterRange));
      Expr<?> result =
          ctx.mkITE(isRuntimeListOrMap, mkParameterizedUnknown(celExpr, ast), typeSystem.mkError());
      return TranslatedValue.create(result, celExpr, typeSystem, isRuntimeListOrMap);
    }

    SeqExpr<?> seq =
        isMap
            ? typeSystem.getMapKeys(typeSystem.getMapRef(iterRange))
            : typeSystem.getSeq(typeSystem.getListRef(iterRange));

    ArithExpr lengthExpr = ctx.mkLength(seq);
    ArrayExpr mapPresence =
        isMap ? (ArrayExpr) typeSystem.getMapPresence(typeSystem.getMapRef(iterRange)) : null;

    BoolExpr isValidRange = isMap ? typeSystem.isMap(iterRange) : typeSystem.isList(iterRange);
    BoolExpr isTruncated =
        ctx.mkAnd(isValidRange, ctx.mkGt(lengthExpr, ctx.mkInt(comprehensionUnrollLimit)));
    context.addTruncationCondition(isTruncated);

    if (isAllMacro(comp) || isExistsMacro(comp)) {
      return unrollAllAndExists(
          celExpr, ast, seq, lengthExpr, mapPresence, isTruncated, iterRangeTv);
    } else {
      return unrollMapAndFilter(
          celExpr, ast, seq, lengthExpr, mapPresence, isTruncated, iterRangeTv);
    }
  }

  private <T> T withIterationScope(
      CelComprehension comp,
      Expr<?> keyOrIndex,
      Expr<?> value,
      Expr<?> currentAccu,
      boolean isMap,
      boolean isTwoVar,
      Supplier<T> action) {
    Supplier<T> bindAccu =
        () ->
            context.withScope(
                comp.accuVar(),
                TranslatedValue.create(currentAccu, typeSystem, ctx.mkFalse()),
                action);

    if (isTwoVar) {
      return context.withScope(
          comp.iterVar(),
          TranslatedValue.create(keyOrIndex, typeSystem, ctx.mkFalse()),
          () ->
              context.withScope(
                  comp.iterVar2(),
                  TranslatedValue.create(value, typeSystem, ctx.mkFalse()),
                  bindAccu));
    } else {
      Expr<?> iterVal = isMap ? keyOrIndex : value;
      return context.withScope(
          comp.iterVar(), TranslatedValue.create(iterVal, typeSystem, ctx.mkFalse()), bindAccu);
    }
  }

  private IterationElement getIterationElement(
      Expr<?> xVal, IntExpr idx, Expr<?> iterRange, boolean isMap, boolean isTwoVar) {
    Expr<?> keyOrIndex;
    Expr<?> value;

    if (isMap) {
      keyOrIndex = xVal;
      if (isTwoVar) {
        Expr<?> mapRef = typeSystem.getMapRef(iterRange);
        ArrayExpr mapValues = (ArrayExpr) typeSystem.getMapValues(mapRef);
        value = ctx.mkSelect(mapValues, keyOrIndex);
      } else {
        value = null;
      }
    } else {
      if (isTwoVar) {
        keyOrIndex = typeSystem.wrapInt(idx);
        value = xVal;
      } else {
        keyOrIndex = null;
        value = xVal;
      }
    }
    return new IterationElement(keyOrIndex, value);
  }

  private TranslatedValue unrollAllAndExists(
      CelExpr celExpr,
      CelAbstractSyntaxTree ast,
      SeqExpr<?> seq,
      ArithExpr lengthExpr,
      ArrayExpr mapPresence,
      BoolExpr isTruncated,
      TranslatedValue iterRangeTv) {
    CelComprehension comp = celExpr.comprehension();
    List<BoundedIteration> iterations = new ArrayList<>();
    TranslatedValue accuInitTv = translateExpr(comp.accuInit(), ast);
    Expr<?> accuInitExpr = accuInitTv.z3Expr();

    boolean isMap = mapPresence != null;
    boolean isTwoVar = !comp.iterVar2().isEmpty();

    for (int i = 0; i < comprehensionUnrollLimit; i++) {
      IntExpr idx = ctx.mkInt(i);
      BoolExpr inBounds = ctx.mkLt(idx, lengthExpr);
      Expr<?> xVal = ctx.mkNth(seq, idx);

      constrainIterationElement(idx, lengthExpr, xVal, inBounds, mapPresence);

      IterationElement iterElem =
          getIterationElement(xVal, idx, iterRangeTv.z3Expr(), isMap, isTwoVar);

      TranslatedValue stepTv =
          withIterationScope(
              comp,
              iterElem.keyOrIndex,
              iterElem.value,
              accuInitExpr,
              isMap,
              isTwoVar,
              () -> translateExpr(comp.loopStep(), ast));
      iterations.add(new BoundedIteration(inBounds, stepTv));
    }

    return reduceAllOrExists(iterations, isTruncated, iterRangeTv, isAllMacro(comp), celExpr, ast);
  }

  private TranslatedValue unrollMapAndFilter(
      CelExpr celExpr,
      CelAbstractSyntaxTree ast,
      SeqExpr<?> seq,
      ArithExpr lengthExpr,
      ArrayExpr mapPresence,
      BoolExpr isTruncated,
      TranslatedValue iterRangeTv) {
    CelComprehension comp = celExpr.comprehension();
    // For macros like map and filter, we must sequentially thread the accumulator through the loop
    TranslatedValue chainedAccuTv = translateExpr(comp.accuInit(), ast);
    Expr<?> chainedAccu = chainedAccuTv.z3Expr();

    boolean isMap = mapPresence != null;
    boolean isTwoVar = !comp.iterVar2().isEmpty();

    List<BoolExpr> taints = new ArrayList<>();
    taints.add(chainedAccuTv.isApproximate());
    taints.add(iterRangeTv.isApproximate());

    for (int i = 0; i < comprehensionUnrollLimit; i++) {
      IntExpr idx = ctx.mkInt(i);
      BoolExpr inBounds = ctx.mkLt(idx, lengthExpr);
      Expr<?> xVal = ctx.mkNth(seq, idx);

      constrainIterationElement(idx, lengthExpr, xVal, inBounds, mapPresence);

      Expr<?> currentAccu = chainedAccu;
      IterationElement iterElem =
          getIterationElement(xVal, idx, iterRangeTv.z3Expr(), isMap, isTwoVar);

      TranslatedValue stepTv =
          withIterationScope(
              comp,
              iterElem.keyOrIndex,
              iterElem.value,
              currentAccu,
              isMap,
              isTwoVar,
              () -> translateExpr(comp.loopStep(), ast));

      taints.add(ctx.mkAnd(inBounds, stepTv.isApproximate()));
      chainedAccu = ctx.mkITE(inBounds, stepTv.z3Expr(), currentAccu);
    }

    TranslatedValue resultTv =
        context.withScope(
            comp.accuVar(),
            TranslatedValue.create(chainedAccu, typeSystem, CelZ3Exprs.mkOrFlattened(ctx, taints)),
            () -> translateExpr(comp.result(), ast));

    Expr<?> iterRangeExpr = iterRangeTv.z3Expr();
    BoolExpr rangeIsError = typeSystem.isError(iterRangeExpr);
    BoolExpr rangeIsUnknown = typeSystem.isUnknown(iterRangeExpr);

    // TODO: Model the loop condition for custom macros; until then, abstract the
    // result of any non-true condition, since the unrolled value ignores early exits.
    BoolExpr isConditionUnmodeled = ctx.mkBool(!isLiteralTrue(comp.loopCondition()));
    BoolExpr isAbstracted =
        CelZ3Exprs.mkOrFlattened(ctx, rangeIsUnknown, isTruncated, isConditionUnmodeled);
    // An error is not final when truncated: an unknown from a later iteration overrides it.
    Expr<?> finalResult =
        CelZ3Exprs.SwitchBuilder.newBuilder(ctx)
            .addCase(rangeIsError, typeSystem.mkError())
            .addCase(isAbstracted, mkParameterizedUnknown(celExpr, ast))
            .build(resultTv.z3Expr());

    BoolExpr finalTaint =
        (BoolExpr)
            ctx.mkITE(
                ctx.mkOr(rangeIsError, rangeIsUnknown),
                iterRangeTv.isApproximate(),
                CelZ3Exprs.mkOrFlattened(
                    ctx, resultTv.isApproximate(), isTruncated, isConditionUnmodeled));

    return TranslatedValue.create(finalResult, celExpr, typeSystem, finalTaint);
  }

  private void constrainIterationElement(
      IntExpr idx, ArithExpr lengthExpr, Expr<?> xVal, BoolExpr inBounds, ArrayExpr mapPresence) {
    // Constrain out-of-bounds elements to prevent MBQI from infinitely instantiating list/map
    // axioms
    BoolExpr outOfBounds = ctx.mkGe(idx, lengthExpr);
    context.addConstraint(ctx.mkImplies(outOfBounds, ctx.mkEq(xVal, typeSystem.mkUnknown())));

    // If we are iterating over a map, we can optionally bind the sequence elements
    // to the map presence array to ensure the solver knows they correspond.
    if (mapPresence != null) {
      Expr<?> selectExpr = ctx.mkSelect(mapPresence, xVal);
      context.addConstraint(ctx.mkImplies(inBounds, (BoolExpr) selectExpr));
    }
  }

  private TranslatedValue reduceAllOrExists(
      List<BoundedIteration> iterations,
      BoolExpr isTruncated,
      TranslatedValue iterRangeTv,
      boolean isAll,
      CelExpr compExpr,
      CelAbstractSyntaxTree ast) {
    List<BoolExpr> hasMatchList = new ArrayList<>();
    List<BoolExpr> hasErrorList = new ArrayList<>();
    List<BoolExpr> hasUnknownList = new ArrayList<>();

    List<BoolExpr> hasSafeMatchList = new ArrayList<>();
    List<BoolExpr> hasSafeErrorList = new ArrayList<>();
    List<BoolExpr> hasSafeUnknownList = new ArrayList<>();
    List<BoolExpr> activeTaints = new ArrayList<>();

    for (BoundedIteration iter : iterations) {
      TranslatedValue stepTv = iter.stepResult;
      Expr<?> step = stepTv.z3Expr();
      BoolExpr isBool = typeSystem.isBool(step);
      BoolExpr unwrapStep = (BoolExpr) typeSystem.unwrapBool(step);

      // 'all' short-circuits on False, 'exists' short-circuits on True
      BoolExpr matchCond = isAll ? ctx.mkNot(unwrapStep) : unwrapStep;
      BoolExpr isMatch = ctx.mkAnd(isBool, matchCond);

      BoolExpr isU = typeSystem.isUnknown(step);
      BoolExpr isE =
          ctx.mkOr(typeSystem.isError(step), ctx.mkAnd(ctx.mkNot(isBool), ctx.mkNot(isU)));

      BoolExpr isActive = iter.inBounds;

      hasMatchList.add(CelZ3Exprs.mkAndFlattened(ctx, isActive, isMatch));
      hasErrorList.add(CelZ3Exprs.mkAndFlattened(ctx, isActive, isE));
      hasUnknownList.add(CelZ3Exprs.mkAndFlattened(ctx, isActive, isU));

      hasSafeMatchList.add(
          CelZ3Exprs.mkAndFlattened(
              ctx, isActive, isMatch, CelZ3Exprs.mkNotFlattened(ctx, stepTv.isApproximate())));
      hasSafeErrorList.add(
          CelZ3Exprs.mkAndFlattened(
              ctx, isActive, isE, CelZ3Exprs.mkNotFlattened(ctx, stepTv.isApproximate())));
      hasSafeUnknownList.add(
          CelZ3Exprs.mkAndFlattened(
              ctx, isActive, isU, CelZ3Exprs.mkNotFlattened(ctx, stepTv.isApproximate())));
      activeTaints.add(CelZ3Exprs.mkAndFlattened(ctx, isActive, stepTv.isApproximate()));
    }

    BoolExpr hasMatch = CelZ3Exprs.mkOrFlattened(ctx, hasMatchList);
    BoolExpr hasError = CelZ3Exprs.mkOrFlattened(ctx, hasErrorList);
    BoolExpr hasUnknown = CelZ3Exprs.mkOrFlattened(ctx, hasUnknownList);
    BoolExpr rangeIsError = typeSystem.isError(iterRangeTv.z3Expr());
    BoolExpr rangeIsUnknown = typeSystem.isUnknown(iterRangeTv.z3Expr());

    BoolExpr hasSafeMatch = CelZ3Exprs.mkOrFlattened(ctx, hasSafeMatchList);
    BoolExpr hasSafeError = CelZ3Exprs.mkOrFlattened(ctx, hasSafeErrorList);
    BoolExpr hasSafeUnknown = CelZ3Exprs.mkOrFlattened(ctx, hasSafeUnknownList);
    BoolExpr anyActiveTaint = CelZ3Exprs.mkOrFlattened(ctx, activeTaints);

    Expr<?> comprehensionUnknown = mkParameterizedUnknown(compExpr, ast);
    Expr<?> result =
        CelZ3Exprs.SwitchBuilder.newBuilder(ctx)
            .addCase(rangeIsError, typeSystem.mkError())
            .addCase(rangeIsUnknown, comprehensionUnknown)
            .addCase(hasMatch, typeSystem.mkBool(!isAll))
            .addCase(ctx.mkOr(hasUnknown, isTruncated), comprehensionUnknown)
            .addCase(hasError, typeSystem.mkError())
            .build(typeSystem.mkBool(isAll));

    BoolExpr baseTaint = CelZ3Exprs.mkOrFlattened(ctx, anyActiveTaint, iterRangeTv.isApproximate());

    // Taint identically shadows the value flow's short-circuit control structure
    BoolExpr resultTaint =
        (BoolExpr)
            CelZ3Exprs.SwitchBuilder.newBuilder(ctx)
                .addCase(ctx.mkOr(rangeIsError, rangeIsUnknown), iterRangeTv.isApproximate())
                .addCase(hasMatch, ctx.mkOr(iterRangeTv.isApproximate(), ctx.mkNot(hasSafeMatch)))
                .addCase(
                    ctx.mkOr(hasUnknown, isTruncated),
                    ctx.mkOr(iterRangeTv.isApproximate(), isTruncated, ctx.mkNot(hasSafeUnknown)))
                .addCase(hasError, ctx.mkOr(iterRangeTv.isApproximate(), ctx.mkNot(hasSafeError)))
                .build(baseTaint);

    return TranslatedValue.create(result, compExpr, typeSystem, resultTaint);
  }

  private static boolean isAllMacro(CelComprehension comp) {
    return isBooleanAccuInit(comp, true)
        && isNotStrictlyFalseLoopCondition(comp, /* expectNot= */ false);
  }

  private static boolean isExistsMacro(CelComprehension comp) {
    return isBooleanAccuInit(comp, false)
        && isNotStrictlyFalseLoopCondition(comp, /* expectNot= */ true);
  }

  private static boolean isBooleanAccuInit(CelComprehension comp, boolean expectedValue) {
    return comp.accuInit().constantOrDefault().getKind() == CelConstant.Kind.BOOLEAN_VALUE
        && comp.accuInit().constant().booleanValue() == expectedValue;
  }

  private static boolean isLiteralTrue(CelExpr expr) {
    CelConstant constant = expr.constantOrDefault();
    return constant.getKind() == CelConstant.Kind.BOOLEAN_VALUE && constant.booleanValue();
  }

  private static boolean isNotStrictlyFalseLoopCondition(CelComprehension comp, boolean expectNot) {
    CelExpr.CelCall call = comp.loopCondition().callOrDefault();
    if (!call.function().equals(Operator.NOT_STRICTLY_FALSE.getFunction())
        && !call.function().equals(Operator.OLD_NOT_STRICTLY_FALSE.getFunction())) {
      return false;
    }
    CelExpr arg = call.args().get(0);
    if (expectNot) {
      CelExpr.CelCall notCall = arg.callOrDefault();
      if (!notCall.function().equals(Operator.LOGICAL_NOT.getFunction())) {
        return false;
      }
      arg = notCall.args().get(0);
    }
    return arg.identOrDefault().name().equals(comp.accuVar());
  }

  private static final class IterationElement {
    private final Expr<?> keyOrIndex;
    private final Expr<?> value;

    private IterationElement(Expr<?> keyOrIndex, Expr<?> value) {
      this.keyOrIndex = keyOrIndex;
      this.value = value;
    }
  }

  private Expr<?> mkParameterizedUnknown(CelExpr expr, CelAbstractSyntaxTree ast) {
    CelAstAlphaHasher.AlphaSignature sig = CelAstAlphaHasher.computeSignature(expr, ast);

    ImmutableList.Builder<Expr<?>> smtArgs = ImmutableList.builder();
    for (CelExpr freeVar : sig.freeVariables()) {
      smtArgs.add(translateExpr(freeVar, ast).z3Expr());
    }

    return typeSystem.mkParameterizedUnknown(
        "!trunc_" + Long.toHexString(sig.staticHash()), smtArgs.build());
  }

  private TranslatedValue translateExpr(CelExpr expr, CelAbstractSyntaxTree ast) {
    return exprTranslator.apply(expr, ast);
  }

  CelZ3ComprehensionTranslator(
      CelZ3TranslationContext context,
      BiFunction<CelExpr, CelAbstractSyntaxTree, TranslatedValue> exprTranslator) {
    this.context = context;
    this.ctx = context.ctx();
    this.typeSystem = context.typeSystem();
    this.comprehensionUnrollLimit = context.comprehensionUnrollLimit();
    this.exprTranslator = exprTranslator;
  }
}
