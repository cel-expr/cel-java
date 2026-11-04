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
import com.google.common.collect.Iterables;
import com.google.common.collect.Streams;
import com.microsoft.z3.ArrayExpr;
import com.microsoft.z3.BoolExpr;
import com.microsoft.z3.Context;
import com.microsoft.z3.Expr;
import com.microsoft.z3.FPExpr;
import com.microsoft.z3.IntExpr;
import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.ast.CelExpr;
import dev.cel.common.internal.ProtoTimeUtils;
import dev.cel.common.types.CelKind;
import dev.cel.common.types.CelType;
import dev.cel.common.types.CelTypeProvider;
import dev.cel.common.types.CelTypes;
import dev.cel.common.types.ListType;
import dev.cel.common.types.MapType;
import dev.cel.common.types.NullableType;
import dev.cel.common.types.OptionalType;
import dev.cel.common.types.SimpleType;
import dev.cel.common.types.StructType;
import dev.cel.common.types.StructTypeReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/** Translates CEL message construction and field selection to Z3. */
@SuppressWarnings({"unchecked", "rawtypes"}) // Z3 Java API uses raw types.
final class CelZ3StructTranslator {
  private static final String MSG_REF_PREFIX = "!msg_ref";
  private static final String EMPTY_MSG_REF_PREFIX = "!empty_msg_ref_";
  private static final String EMPTY_LIST_PREFIX = "!empty_list";
  private static final String EMPTY_MAP_PREFIX = "!empty_map";
  private static final String NULL_VALUE_FIELD = "null_value";
  private static final String SECONDS_FIELD = "seconds";
  private static final String NANOS_FIELD = "nanos";
  private static final long MAX_UINT32 = 0xFFFFFFFFL;

  private final CelZ3TranslationContext context;
  private final Context ctx;
  private final CelZ3TypeSystem typeSystem;
  private final CelZ3TypeConstraintGenerator typeConstraintGenerator;
  private final CelTypeProvider typeProvider;
  private final BiFunction<CelExpr, CelAbstractSyntaxTree, TranslatedValue> exprTranslator;
  private final Map<String, Expr<?>> emptyMessageCache;
  private Expr<?> emptyListCache;
  private Expr<?> emptyMapCache;

  TranslatedValue translateStruct(CelExpr celExpr, CelAbstractSyntaxTree ast) {
    CelExpr.CelStruct createStruct = celExpr.struct();
    if (isJsonWkt(createStruct.messageName())) {
      return translateJsonWktStruct(celExpr, createStruct, ast);
    }
    if (CelTypes.isWrapperType(createStruct.messageName())) {
      return translateWrapperWktStruct(celExpr, createStruct, ast);
    }
    if (createStruct.messageName().equals(CelTypes.TIMESTAMP_MESSAGE)) {
      return translateTimestampWktStruct(celExpr, createStruct, ast);
    }
    if (createStruct.messageName().equals(CelTypes.DURATION_MESSAGE)) {
      return translateDurationWktStruct(celExpr, createStruct, ast);
    }

    // Bypass SMT when the struct is empty (return the cached SMT default pointer)
    if (createStruct.entries().isEmpty()) {
      return TranslatedValue.create(
          getDefaultValueForType(StructTypeReference.create(createStruct.messageName())),
          celExpr,
          typeSystem,
          ctx.mkFalse());
    }

    Expr<?> msgRef = typeSystem.mkMessageRefConst(MSG_REF_PREFIX);

    // Initialize the values array with Unknown. Missing fields and explicit primitive defaults
    // will both bypass the store and fall back to this Unknown base. However, their msgPresence
    // will evaluate to false, allowing translateSelect to properly return the default value.
    ArrayExpr msgValues = ctx.mkConstArray(ctx.getStringSort(), typeSystem.mkUnknown());
    ArrayExpr msgPresence = ctx.mkConstArray(ctx.getStringSort(), ctx.mkFalse());

    List<TranslatedValue> elementsTv = new ArrayList<>();

    for (CelExpr.CelStruct.Entry entryAst : createStruct.entries()) {
      Expr<?> key = ctx.mkString(entryAst.fieldKey());
      TranslatedValue valueTv = translateExpr(entryAst.value(), ast);
      Expr<?> value = valueTv.z3Expr();

      CelType fieldType =
          typeProvider
              .findType(createStruct.messageName())
              .filter(t -> t instanceof StructType)
              .map(t -> (StructType) t)
              .flatMap(t -> t.findField(entryAst.fieldKey()))
              .map(StructType.Field::type)
              .orElseGet(() -> extractAstTypeOrDefault(ast, entryAst.value().id()));
      Expr<?> defaultVal = getDefaultValueForType(fieldType);

      Expr<?> finalValue = value;
      BoolExpr optionalHasValue = ctx.mkTrue();
      if (entryAst.optionalEntry()) {
        Expr<?> checkedValue =
            typeSystem.withRuntimeError(value, ctx.mkNot(typeSystem.isOptional(value)));
        valueTv =
            TranslatedValue.create(
                checkedValue, entryAst.value(), typeSystem, valueTv.isApproximate());
        Expr<?> optRef = typeSystem.getOptionalRef(checkedValue);
        optionalHasValue = typeSystem.optHasValue(optRef);
        finalValue = typeSystem.getOptionalValue(optRef);
      }
      elementsTv.add(valueTv);

      // Canonicalization Trick:
      //
      // We avoid storing explicit default values (e.g. `single_int32: 0`)
      // in `msgValues`, leaving them as `Unknown` (identical to missing fields).
      // This structurally aligns defaults with missing fields, allowing Z3's native array equality
      // (`msg1 == msg2`) to work without using quantifiers (which avoids MBQI loops).
      // Because proto3 singular primitives do not have field presence, we also skip setting
      // `msgPresence`.
      BoolExpr isDefaultPrimitive =
          fieldType.kind().isPrimitive() ? ctx.mkEq(finalValue, defaultVal) : ctx.mkFalse();

      BoolExpr shouldBypass = ctx.mkOr(ctx.mkNot(optionalHasValue), isDefaultPrimitive);

      msgValues =
          (ArrayExpr) ctx.mkITE(shouldBypass, msgValues, ctx.mkStore(msgValues, key, finalValue));

      msgPresence =
          (ArrayExpr)
              ctx.mkITE(shouldBypass, msgPresence, ctx.mkStore(msgPresence, key, ctx.mkTrue()));
    }

    context.addConstraint(
        ctx.mkEq(typeSystem.getMsgTypeName(msgRef), ctx.mkString(createStruct.messageName())));
    context.addConstraint(ctx.mkEq(typeSystem.getMsgValues(msgRef), msgValues));
    context.addConstraint(ctx.mkEq(typeSystem.getMsgPresence(msgRef), msgPresence));

    Expr<?> result = typeSystem.wrapMessage(msgRef);
    return TranslatedValue.propagateStrict(ctx, typeSystem, result, celExpr, elementsTv);
  }

  private static boolean isJsonWkt(String messageName) {
    return messageName.equals(CelTypes.VALUE_MESSAGE)
        || messageName.equals(CelTypes.LIST_VALUE_MESSAGE)
        || messageName.equals(CelTypes.STRUCT_MESSAGE);
  }

  // Concretize JSON WKT unwrapping directly into native Z3 primitives to avoid
  // sort incompatibilities (Message == String) and solver performance penalties (quantifiers).
  private TranslatedValue translateJsonWktStruct(
      CelExpr celExpr, CelExpr.CelStruct createStruct, CelAbstractSyntaxTree ast) {
    Expr<?> fallback;
    if (createStruct.messageName().equals(CelTypes.VALUE_MESSAGE)) {
      fallback = typeSystem.mkNull();
    } else if (createStruct.messageName().equals(CelTypes.LIST_VALUE_MESSAGE)) {
      fallback = getDefaultValueForType(ListType.create(SimpleType.DYN));
    } else {
      fallback = getDefaultValueForType(MapType.create(SimpleType.STRING, SimpleType.DYN));
    }

    if (createStruct.entries().isEmpty()) {
      return TranslatedValue.create(fallback, celExpr, typeSystem, ctx.mkFalse());
    }

    // JSON WKT messages (gp.Struct, gp.Value, gp.ListValue) can only have a single top-level
    // field entry in non-empty creation literals (e.g., 'fields' for Struct, 'values' for
    // ListValue, or a single 'oneof' field for Value).
    CelExpr.CelStruct.Entry entry = Iterables.getOnlyElement(createStruct.entries());

    // Translate the value to properly capture approximations and Optionals
    TranslatedValue entryTv = translateExpr(entry.value(), ast);
    Expr<?> finalVal = entryTv.z3Expr();

    boolean isNullValueField =
        createStruct.messageName().equals(CelTypes.VALUE_MESSAGE)
            && entry.fieldKey().equals(NULL_VALUE_FIELD);

    if (entry.optionalEntry()) {
      Expr<?> checkedVal =
          typeSystem.withRuntimeError(finalVal, ctx.mkNot(typeSystem.isOptional(finalVal)));
      entryTv =
          TranslatedValue.create(checkedVal, entry.value(), typeSystem, entryTv.isApproximate());
      Expr<?> optRef = typeSystem.getOptionalRef(checkedVal);
      BoolExpr hasValue = typeSystem.optHasValue(optRef);

      Expr<?> unpackedVal = typeSystem.getOptionalValue(optRef);
      if (isNullValueField) {
        unpackedVal = typeSystem.mkNull();
      }
      finalVal = ctx.mkITE(hasValue, unpackedVal, fallback);
    } else if (isNullValueField) {
      finalVal = typeSystem.mkNull();
    }

    return TranslatedValue.propagateStrict(
        ctx, typeSystem, finalVal, celExpr, ImmutableList.of(entryTv));
  }

  private TranslatedValue translateWrapperWktStruct(
      CelExpr celExpr, CelExpr.CelStruct createStruct, CelAbstractSyntaxTree ast) {
    String typeName = createStruct.messageName();
    CelType wrapperType =
        CelTypes.getWellKnownCelType(typeName)
            .orElseThrow(
                () -> new IllegalArgumentException("Unexpected wrapper type: " + typeName));
    CelType targetType = ((NullableType) wrapperType).targetType();
    Expr<?> currentVal = getDefaultValueForType(targetType);

    List<TranslatedValue> entryTvs = new ArrayList<>();
    for (CelExpr.CelStruct.Entry entry : createStruct.entries()) {
      TranslatedValue entryTv = translateExpr(entry.value(), ast);
      Expr<?> val = entryTv.z3Expr();
      if (entry.optionalEntry()) {
        Expr<?> checkedVal =
            typeSystem.withRuntimeError(val, ctx.mkNot(typeSystem.isOptional(val)));
        entryTv =
            TranslatedValue.create(checkedVal, entry.value(), typeSystem, entryTv.isApproximate());
        Expr<?> optRef = typeSystem.getOptionalRef(checkedVal);
        BoolExpr hasValue = typeSystem.optHasValue(optRef);
        currentVal = ctx.mkITE(hasValue, typeSystem.getOptionalValue(optRef), currentVal);
      } else {
        currentVal = val;
      }
      entryTvs.add(entryTv);
    }
    BoolExpr validValue =
        typeConstraintGenerator.createTypeConstraintForType(currentVal, targetType);
    if (typeName.equals(CelTypes.INT32_WRAPPER_MESSAGE)) {
      IntExpr intVal = typeSystem.getInt(currentVal);
      validValue =
          ctx.mkAnd(
              validValue,
              ctx.mkGe(intVal, ctx.mkInt(Integer.MIN_VALUE)),
              ctx.mkLe(intVal, ctx.mkInt(Integer.MAX_VALUE)));
    } else if (typeName.equals(CelTypes.UINT32_WRAPPER_MESSAGE)) {
      IntExpr uintVal = typeSystem.getUint(currentVal);
      validValue =
          ctx.mkAnd(
              validValue,
              ctx.mkGe(uintVal, ctx.mkInt(0)),
              ctx.mkLe(uintVal, ctx.mkInt(MAX_UINT32)));
    } else if (typeName.equals(CelTypes.FLOAT_WRAPPER_MESSAGE)) {
      FPExpr doubleVal = typeSystem.getDouble(currentVal);
      FPExpr narrowedFloat =
          ctx.mkFPToFP(ctx.mkFPRoundNearestTiesToEven(), doubleVal, ctx.mkFPSortSingle());
      FPExpr widenedDouble =
          ctx.mkFPToFP(ctx.mkFPRoundNearestTiesToEven(), narrowedFloat, ctx.mkFPSortDouble());
      currentVal = typeSystem.wrapDouble(widenedDouble);
    }
    currentVal = typeSystem.withRuntimeError(currentVal, ctx.mkNot(validValue));
    return TranslatedValue.propagateStrict(ctx, typeSystem, currentVal, celExpr, entryTvs);
  }

  @FunctionalInterface
  private interface TimeWktValueFactory {
    Expr<?> create(IntExpr seconds, IntExpr nanos, IntExpr totalNanos);
  }

  private TranslatedValue translateTimestampWktStruct(
      CelExpr celExpr, CelExpr.CelStruct createStruct, CelAbstractSyntaxTree ast) {
    return translateTimeWktStruct(
        celExpr,
        createStruct,
        ast,
        /* minNanos= */ 0,
        (seconds, nanos, totalNanos) ->
            typeSystem.withRuntimeError(
                typeSystem.wrapTimestamp(totalNanos),
                typeSystem.checkTimestampOverflow(totalNanos)));
  }

  private TranslatedValue translateDurationWktStruct(
      CelExpr celExpr, CelExpr.CelStruct createStruct, CelAbstractSyntaxTree ast) {
    return translateTimeWktStruct(
        celExpr,
        createStruct,
        ast,
        -ProtoTimeUtils.MAX_NANOS,
        (seconds, nanos, totalNanos) -> {
          BoolExpr signMismatch =
              ctx.mkOr(
                  ctx.mkAnd(ctx.mkGt(seconds, ctx.mkInt(0)), ctx.mkLt(nanos, ctx.mkInt(0))),
                  ctx.mkAnd(ctx.mkLt(seconds, ctx.mkInt(0)), ctx.mkGt(nanos, ctx.mkInt(0))));
          return typeSystem.withRuntimeError(
              typeSystem.wrapDuration(totalNanos),
              signMismatch,
              typeSystem.checkDurationOverflow(totalNanos));
        });
  }

  private TranslatedValue translateTimeWktStruct(
      CelExpr celExpr,
      CelExpr.CelStruct createStruct,
      CelAbstractSyntaxTree ast,
      int minNanos,
      TimeWktValueFactory valueFactory) {
    List<TranslatedValue> entryTvs = new ArrayList<>();
    Expr<?> secondsVal =
        resolveStructFieldValue(createStruct, SECONDS_FIELD, typeSystem.mkInt(0), ast, entryTvs);
    Expr<?> nanosVal =
        resolveStructFieldValue(createStruct, NANOS_FIELD, typeSystem.mkInt(0), ast, entryTvs);

    IntExpr seconds = typeSystem.getInt(secondsVal);
    IntExpr nanos = typeSystem.getInt(nanosVal);
    IntExpr totalNanos =
        (IntExpr) ctx.mkAdd(ctx.mkMul(seconds, ctx.mkInt(CelZ3TypeSystem.NANOS_PER_SECOND)), nanos);

    BoolExpr typeError =
        ctx.mkOr(ctx.mkNot(typeSystem.isInt(secondsVal)), ctx.mkNot(typeSystem.isInt(nanosVal)));
    BoolExpr nanosOutOfRange =
        ctx.mkOr(
            ctx.mkLt(nanos, ctx.mkInt(minNanos)),
            ctx.mkGe(nanos, ctx.mkInt(CelZ3TypeSystem.NANOS_PER_SECOND)));

    Expr<?> result =
        typeSystem.withRuntimeError(
            valueFactory.create(seconds, nanos, totalNanos), typeError, nanosOutOfRange);
    return TranslatedValue.propagateStrict(ctx, typeSystem, result, celExpr, entryTvs);
  }

  private Expr<?> resolveStructFieldValue(
      CelExpr.CelStruct createStruct,
      String fieldName,
      Expr<?> defaultValue,
      CelAbstractSyntaxTree ast,
      List<TranslatedValue> entryTvs) {
    return Streams.findLast(
            createStruct.entries().stream().filter(entry -> entry.fieldKey().equals(fieldName)))
        .<Expr<?>>map(
            entry -> {
              TranslatedValue entryTv = translateExpr(entry.value(), ast);
              Expr<?> val = entryTv.z3Expr();
              if (entry.optionalEntry()) {
                Expr<?> checkedVal =
                    typeSystem.withRuntimeError(val, ctx.mkNot(typeSystem.isOptional(val)));
                entryTv =
                    TranslatedValue.create(
                        checkedVal, entry.value(), typeSystem, entryTv.isApproximate());
                Expr<?> optRef = typeSystem.getOptionalRef(checkedVal);
                val =
                    ctx.mkITE(
                        typeSystem.optHasValue(optRef),
                        typeSystem.getOptionalValue(optRef),
                        defaultValue);
              }
              entryTvs.add(entryTv);
              return val;
            })
        .orElse(defaultValue);
  }

  private Expr<?> getDefaultValueForType(CelType type) {
    if (type instanceof NullableType) {
      return typeSystem.mkNull();
    }
    if (type instanceof OptionalType) {
      return typeSystem.mkOptionalNone();
    }
    if (type.equals(SimpleType.INT)) {
      return typeSystem.mkInt(0);
    }
    if (type.equals(SimpleType.BOOL)) {
      return typeSystem.wrapBool(ctx.mkFalse());
    }
    if (type.equals(SimpleType.STRING)) {
      return typeSystem.mkString("");
    }
    if (type.equals(SimpleType.BYTES)) {
      return typeSystem.mkBytes("");
    }
    if (type.equals(SimpleType.DOUBLE)) {
      return typeSystem.mkDouble(0.0);
    }
    if (type.equals(SimpleType.UINT)) {
      return typeSystem.mkUint(0);
    }
    if (type.equals(SimpleType.TIMESTAMP)) {
      return typeSystem.wrapTimestamp(ctx.mkInt(0));
    }
    if (type.equals(SimpleType.DURATION)) {
      return typeSystem.wrapDuration(ctx.mkInt(0));
    }
    if (type instanceof ListType) {
      if (emptyListCache == null) {
        emptyListCache = typeSystem.mkListRefConst(EMPTY_LIST_PREFIX);
        context.addConstraint(
            ctx.mkEq(
                typeSystem.getSeq(emptyListCache),
                ctx.mkEmptySeq(ctx.mkSeqSort(typeSystem.celValueSort()))));
      }
      return typeSystem.wrapList(emptyListCache);
    }
    if (type instanceof MapType) {
      if (emptyMapCache == null) {
        emptyMapCache = typeSystem.mkMapRefConst(EMPTY_MAP_PREFIX);
        context.addConstraint(
            ctx.mkEq(
                typeSystem.getMapPresence(emptyMapCache),
                ctx.mkConstArray(typeSystem.celValueSort(), ctx.mkFalse())));
        context.addConstraint(
            ctx.mkEq(
                typeSystem.getMapValues(emptyMapCache),
                ctx.mkConstArray(typeSystem.celValueSort(), typeSystem.mkUnknown())));
        context.addConstraint(
            ctx.mkEq(
                typeSystem.getMapKeys(emptyMapCache),
                ctx.mkEmptySeq(ctx.mkSeqSort(typeSystem.celValueSort()))));
      }
      return typeSystem.wrapMap(emptyMapCache);
    }
    if (type.kind() == CelKind.STRUCT) {
      String messageName = type.name();
      Expr<?> msgRef =
          emptyMessageCache.computeIfAbsent(
              messageName,
              name -> {
                Expr<?> ref = typeSystem.mkMessageRefConst(EMPTY_MSG_REF_PREFIX + name);
                context.addConstraint(
                    ctx.mkEq(
                        typeSystem.getMsgPresence(ref),
                        ctx.mkConstArray(ctx.getStringSort(), ctx.mkFalse())));
                context.addConstraint(
                    ctx.mkEq(
                        typeSystem.getMsgValues(ref),
                        ctx.mkConstArray(ctx.getStringSort(), typeSystem.mkUnknown())));
                context.addConstraint(ctx.mkEq(typeSystem.getMsgTypeName(ref), ctx.mkString(name)));
                return ref;
              });
      return typeSystem.wrapMessage(msgRef);
    }
    return typeSystem.mkUnknown();
  }

  private static final class FieldAccess {
    private final BoolExpr presence;
    private final Expr<?> value;

    private FieldAccess(BoolExpr presence, Expr<?> value) {
      this.presence = presence;
      this.value = value;
    }
  }

  private FieldAccess getMapAccess(Expr<?> operand, String field, BoolExpr typeGuard) {
    Expr<?> mapRef = typeSystem.getMapRef(operand);
    Expr<?> mapFieldZ3Str = typeSystem.mkString(field);
    BoolExpr presence =
        (BoolExpr) ctx.mkSelect((ArrayExpr) typeSystem.getMapPresence(mapRef), mapFieldZ3Str);
    Expr<?> value = ctx.mkSelect((ArrayExpr) typeSystem.getMapValues(mapRef), mapFieldZ3Str);
    context.constrainValidValue(CelZ3Exprs.mkAndFlattened(ctx, typeGuard, presence), value);
    return new FieldAccess(presence, value);
  }

  private FieldAccess getMsgAccess(Expr<?> operand, String field, BoolExpr typeGuard) {
    Expr<?> msgRef = typeSystem.getMessageRef(operand);
    Expr<?> msgFieldZ3Str = ctx.mkString(field);
    BoolExpr presence =
        (BoolExpr) ctx.mkSelect((ArrayExpr) typeSystem.getMsgPresence(msgRef), msgFieldZ3Str);
    Expr<?> value = ctx.mkSelect((ArrayExpr) typeSystem.getMsgValues(msgRef), msgFieldZ3Str);
    context.constrainValidValue(CelZ3Exprs.mkAndFlattened(ctx, typeGuard, presence), value);
    return new FieldAccess(presence, value);
  }

  TranslatedValue translateSelect(CelExpr celExpr, CelAbstractSyntaxTree ast) {
    CelExpr.CelSelect select = celExpr.select();
    long exprId = celExpr.id();
    TranslatedValue operandTv = translateExpr(select.operand(), ast);
    Expr<?> operand = operandTv.z3Expr();
    String field = select.field();
    CelType operandType = extractAstTypeOrDefault(ast, select.operand().id());

    Expr<?> presenceResult;
    Expr<?> valueResult;

    if (operandType instanceof MapType) {
      FieldAccess mapAcc = getMapAccess(operand, field, ctx.mkTrue());
      presenceResult = mapAcc.presence;
      valueResult = ctx.mkITE(mapAcc.presence, mapAcc.value, typeSystem.mkError());
    } else if (operandType.kind() == CelKind.STRUCT) {
      FieldAccess msgAcc = getMsgAccess(operand, field, ctx.mkTrue());
      presenceResult = msgAcc.presence;
      Expr<?> defaultVal = getDefaultValueForType(extractAstTypeOrDefault(ast, exprId));
      valueResult = ctx.mkITE(msgAcc.presence, msgAcc.value, defaultVal);
    } else {
      // Dynamic type: generate the full SMT decision tree
      BoolExpr isMap = typeSystem.isMap(operand);
      BoolExpr isMessage = typeSystem.isMessage(operand);

      FieldAccess mapAcc = getMapAccess(operand, field, isMap);
      FieldAccess msgAcc = getMsgAccess(operand, field, isMessage);

      presenceResult =
          CelZ3Exprs.SwitchBuilder.newBuilder(ctx)
              .addCase(isMessage, msgAcc.presence)
              .addCase(isMap, mapAcc.presence)
              .build(ctx.mkFalse());

      Expr<?> defaultVal = getDefaultValueForType(extractAstTypeOrDefault(ast, exprId));
      Expr<?> msgRead = ctx.mkITE(msgAcc.presence, msgAcc.value, defaultVal);
      Expr<?> mapRead = ctx.mkITE(mapAcc.presence, mapAcc.value, typeSystem.mkError());

      valueResult =
          CelZ3Exprs.SwitchBuilder.newBuilder(ctx)
              .addCase(isMessage, msgRead)
              .addCase(isMap, mapRead)
              .build(typeSystem.mkError());
    }

    Expr<?> fieldAccess = select.testOnly() ? typeSystem.wrapBool(presenceResult) : valueResult;

    context.addConstraint(typeConstraintGenerator.createTypeConstraint(fieldAccess, exprId, ast));

    TranslatedValue result =
        TranslatedValue.propagateStrict(
            ctx, typeSystem, fieldAccess, celExpr, ImmutableList.of(operandTv));
    // Map presence is only linked to the keys up to the unroll limit.
    return operandType.kind() == CelKind.STRUCT
        ? result
        : result.withApproximation(context.isTruncatedMap(operand));
  }

  private static CelType extractAstTypeOrDefault(CelAbstractSyntaxTree ast, long id) {
    return ast.getType(id).orElse(SimpleType.DYN);
  }

  private TranslatedValue translateExpr(CelExpr expr, CelAbstractSyntaxTree ast) {
    return exprTranslator.apply(expr, ast);
  }

  CelZ3StructTranslator(
      CelZ3TranslationContext context,
      CelTypeProvider typeProvider,
      BiFunction<CelExpr, CelAbstractSyntaxTree, TranslatedValue> exprTranslator) {
    this.context = context;
    this.ctx = context.ctx();
    this.typeSystem = context.typeSystem();
    this.typeConstraintGenerator = context.typeConstraintGenerator();
    this.typeProvider = typeProvider;
    this.exprTranslator = exprTranslator;
    this.emptyMessageCache = new HashMap<>();
  }
}
