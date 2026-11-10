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

import com.microsoft.z3.ArithExpr;
import com.microsoft.z3.ArrayExpr;
import com.microsoft.z3.BoolExpr;
import com.microsoft.z3.Context;
import com.microsoft.z3.Expr;
import com.microsoft.z3.IntExpr;
import com.microsoft.z3.SeqExpr;
import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.types.CelKind;
import dev.cel.common.types.CelType;
import dev.cel.common.types.ListType;
import dev.cel.common.types.MapType;
import dev.cel.common.types.NullableType;
import dev.cel.common.types.OptionalType;
import dev.cel.common.types.SimpleType;
import java.util.ArrayList;
import java.util.List;

/** Generates Z3 constraints restricting a value to the values of a CEL type. */
@SuppressWarnings({"unchecked", "rawtypes"}) // Z3 Java API uses raw types.
final class CelZ3TypeConstraintGenerator {

  private final Context ctx;
  private final CelZ3TypeSystem typeSystem;
  private final int comprehensionUnrollLimit;

  BoolExpr createTypeConstraint(Expr<?> val, long exprId, CelAbstractSyntaxTree ast) {
    CelType type =
        ast.getType(exprId)
            .orElseThrow(
                () -> new IllegalArgumentException("Type not found for expr ID: " + exprId));
    BoolExpr typeConstraint = createTypeConstraintForType(val, type);
    return ctx.mkOr(typeSystem.isErrorOrUnknown(val), typeConstraint);
  }

  BoolExpr createTypeConstraintForType(Expr<?> val, CelType type) {
    if (type instanceof NullableType) {
      NullableType nullableType = (NullableType) type;
      return ctx.mkOr(
          typeSystem.isNull(val), createTypeConstraintForType(val, nullableType.targetType()));
    }
    if (type instanceof OptionalType) {
      BoolExpr isOpt = typeSystem.isOptional(val);
      CelType paramType = type.parameters().get(0);
      Expr<?> optRef = typeSystem.getOptionalRef(val);
      BoolExpr hasValue = typeSystem.optHasValue(optRef);
      Expr<?> optVal = typeSystem.getOptionalValue(optRef);
      // An input's value is never an error or unknown, even when its type is dyn.
      BoolExpr valConstraint =
          ctx.mkAnd(
              ctx.mkNot(typeSystem.isErrorOrUnknown(optVal)),
              createTypeConstraintForType(optVal, paramType));
      return ctx.mkAnd(isOpt, ctx.mkImplies(hasValue, valConstraint));
    }
    if (type.equals(SimpleType.BOOL)) {
      return typeSystem.isBool(val);
    }
    if (type.equals(SimpleType.INT)) {
      IntExpr unwrapped = typeSystem.getInt(val);
      return ctx.mkAnd(typeSystem.isInt(val), ctx.mkNot(typeSystem.checkIntOverflow(unwrapped)));
    }
    if (type.equals(SimpleType.UINT)) {
      IntExpr unwrapped = typeSystem.getUint(val);
      return ctx.mkAnd(typeSystem.isUint(val), ctx.mkNot(typeSystem.checkUintOverflow(unwrapped)));
    }
    if (type.equals(SimpleType.DOUBLE)) {
      // Inputs share wrapDouble's canonical NaN.
      return ctx.mkAnd(
          typeSystem.isDouble(val),
          ctx.mkImplies(
              ctx.mkFPIsNaN(typeSystem.getDouble(val)),
              ctx.mkEq(val, typeSystem.wrapDouble(typeSystem.getDouble(val)))));
    }
    if (type.equals(SimpleType.STRING)) {
      return typeSystem.isString(val);
    }
    if (type.equals(SimpleType.BYTES)) {
      return typeSystem.isBytes(val);
    }
    if (type.equals(SimpleType.TIMESTAMP)) {
      IntExpr nanos = typeSystem.getTimestamp(val);
      return ctx.mkAnd(
          typeSystem.isTimestamp(val), ctx.mkNot(typeSystem.checkTimestampOverflow(nanos)));
    }
    if (type.equals(SimpleType.DURATION)) {
      IntExpr nanos = typeSystem.getDuration(val);
      return ctx.mkAnd(
          typeSystem.isDuration(val), ctx.mkNot(typeSystem.checkDurationOverflow(nanos)));
    }
    if (type.equals(SimpleType.DYN) || type.equals(SimpleType.ANY)) {
      return typeSystem.isValidScalar(val);
    }

    if (type instanceof ListType) {
      // Constrain list elements using bounded unrolling up to comprehensionUnrollLimit rather
      // than Z3 forall quantifiers to prevent MBQI quantifier instantiation loops.
      // Assert: isList(val) ∧ for all unrolled 0 <= i < length: ¬isErrorOrUnknown(seq[i]) ∧
      // typeConstraint(seq[i]).
      BoolExpr isList = typeSystem.isList(val);
      CelType elemType = ((ListType) type).elemType();

      Expr<?> listRef = typeSystem.getListRef(val);
      SeqExpr seq = typeSystem.getSeq(listRef);
      Expr length = ctx.mkLength(seq);

      List<BoolExpr> boundsAndTypes = new ArrayList<>();
      boundsAndTypes.add(isList);
      // Elements beyond the limit are unconstrained. That only matters if elemType is known: dyn
      // elements past it are treated like values nested in a dyn value.
      if (CelZ3OperatorTranslator.isStaticallyKnown(elemType)) {
        boundsAndTypes.add(
            ctx.mkImplies(
                ctx.mkGt(length, ctx.mkInt(comprehensionUnrollLimit)),
                typeSystem.hasTruncatedTypeConstraint(val)));
      }
      for (int i = 0; i < comprehensionUnrollLimit; i++) {
        IntExpr idx = ctx.mkInt(i);
        Expr elem = ctx.mkNth(seq, idx);
        BoolExpr validIndex = ctx.mkLt(idx, length);
        // Elements are never errors or unknowns, even in list(dyn).
        boundsAndTypes.add(ctx.mkImplies(validIndex, ctx.mkNot(typeSystem.isErrorOrUnknown(elem))));
        BoolExpr elemConstraint = createTypeConstraintForType(elem, elemType);
        boundsAndTypes.add(ctx.mkImplies(validIndex, elemConstraint));
      }

      return CelZ3Exprs.mkAndFlattened(ctx, boundsAndTypes);
    }
    if (type instanceof MapType) {
      // Do NOT emit a for-all quantifier over map keys or values here.
      // Doing so forces MBQI into an infinite loop. Instead, constrain keys and values using
      // bounded unrolling over the key sequence up to comprehensionUnrollLimit.
      // Assert: isMap(val) ∧ for all unrolled 0 <= i < length: isPrimitiveKey(key) ∧ ¬isError(key)
      //         ∧ (presence(key) ⇒ ¬isErrorOrUnknown(val) ∧ typeConstraint(val))
      BoolExpr isMap = typeSystem.isMap(val);
      MapType mapType = (MapType) type;
      CelType keyType = mapType.keyType();
      CelType valType = mapType.valueType();

      Expr<?> mapRef = typeSystem.getMapRef(val);
      SeqExpr seq = typeSystem.getMapKeys(mapRef);
      Expr length = ctx.mkLength(seq);
      ArrayExpr mapValues = (ArrayExpr) typeSystem.getMapValues(mapRef);
      ArrayExpr mapPresence = (ArrayExpr) typeSystem.getMapPresence(mapRef);

      List<BoolExpr> boundsAndTypes = new ArrayList<>();
      boundsAndTypes.add(isMap);
      boundsAndTypes.add(getBoundedMapBijection(mapPresence, seq, (ArithExpr) length));
      // Entries beyond the limit are unconstrained, which matters if keyType or valType is known
      // (dyn entries past it are treated like values nested in a dyn value).
      if (CelZ3OperatorTranslator.isStaticallyKnown(keyType)
          || CelZ3OperatorTranslator.isStaticallyKnown(valType)) {
        boundsAndTypes.add(
            ctx.mkImplies(
                ctx.mkGt(length, ctx.mkInt(comprehensionUnrollLimit)),
                typeSystem.hasTruncatedTypeConstraint(val)));
      }

      for (int i = 0; i < comprehensionUnrollLimit; i++) {
        IntExpr idx = ctx.mkInt(i);
        Expr key = ctx.mkNth(seq, idx);
        BoolExpr validIndex = ctx.mkLt(idx, length);

        BoolExpr isKeyPrim = typeSystem.isPrimitiveKey(key);
        BoolExpr keyNotError = ctx.mkNot(typeSystem.isError(key));
        // Assert isKeyPrim ∧ ¬isError(key) so Z3 never synthesizes a non-primitive or Error key in
        // map(dyn, ...). For concrete map types, this is already implied by keyType constraints.
        boundsAndTypes.add(ctx.mkImplies(validIndex, ctx.mkAnd(isKeyPrim, keyNotError)));
        boundsAndTypes.add(ctx.mkImplies(validIndex, createTypeConstraintForType(key, keyType)));

        BoolExpr presence = (BoolExpr) ctx.mkSelect(mapPresence, key);
        BoolExpr validEntry = ctx.mkAnd(validIndex, presence);

        Expr mapVal = ctx.mkSelect(mapValues, key);
        BoolExpr valNotError = ctx.mkNot(typeSystem.isErrorOrUnknown(mapVal));
        boundsAndTypes.add(ctx.mkImplies(validEntry, valNotError));
        boundsAndTypes.add(ctx.mkImplies(validEntry, createTypeConstraintForType(mapVal, valType)));
      }

      return CelZ3Exprs.mkAndFlattened(ctx, boundsAndTypes);
    }
    if (type.kind() == CelKind.STRUCT) {
      return ctx.mkAnd(
          typeSystem.isMessage(val),
          ctx.mkEq(
              typeSystem.getMsgTypeName(typeSystem.getMessageRef(val)), ctx.mkString(type.name())));
    }
    // Fallback: no type constraint
    return ctx.mkTrue();
  }

  private BoolExpr getBoundedMapBijection(
      ArrayExpr mapPresence, SeqExpr<?> seq, ArithExpr lengthExpr) {
    List<BoolExpr> constraints = new ArrayList<>();
    for (int i = 0; i < comprehensionUnrollLimit; i++) {
      for (int j = i + 1; j < comprehensionUnrollLimit; j++) {
        BoolExpr validPair = ctx.mkLt(ctx.mkInt(j), lengthExpr);
        BoolExpr notEqual =
            ctx.mkNot(ctx.mkEq(ctx.mkNth(seq, ctx.mkInt(i)), ctx.mkNth(seq, ctx.mkInt(j))));
        constraints.add(ctx.mkImplies(validPair, notEqual));
      }
    }

    BoolExpr isNotTruncated = ctx.mkLe(lengthExpr, ctx.mkInt(comprehensionUnrollLimit));

    ArrayExpr seqMap = ctx.mkConstArray(typeSystem.celValueSort(), ctx.mkFalse());
    for (int i = 0; i < comprehensionUnrollLimit; i++) {
      seqMap =
          (ArrayExpr)
              ctx.mkITE(
                  ctx.mkLt(ctx.mkInt(i), lengthExpr),
                  ctx.mkStore(seqMap, ctx.mkNth(seq, ctx.mkInt(i)), ctx.mkTrue()),
                  seqMap);
    }
    constraints.add(ctx.mkImplies(isNotTruncated, ctx.mkEq(mapPresence, seqMap)));
    return CelZ3Exprs.mkAndFlattened(ctx, constraints);
  }

  CelZ3TypeConstraintGenerator(
      Context ctx, CelZ3TypeSystem typeSystem, int comprehensionUnrollLimit) {
    this.ctx = ctx;
    this.typeSystem = typeSystem;
    this.comprehensionUnrollLimit = comprehensionUnrollLimit;
  }
}
