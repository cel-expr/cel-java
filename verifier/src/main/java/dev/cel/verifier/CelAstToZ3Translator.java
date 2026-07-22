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
import com.google.common.collect.ImmutableSet;
import com.microsoft.z3.ArithExpr;
import com.microsoft.z3.ArrayExpr;
import com.microsoft.z3.BoolExpr;
import com.microsoft.z3.Context;
import com.microsoft.z3.Expr;
import com.microsoft.z3.FuncDecl;
import com.microsoft.z3.IntExpr;
import com.microsoft.z3.Pattern;
import com.microsoft.z3.Quantifier;
import com.microsoft.z3.SeqExpr;
import com.microsoft.z3.Sort;
import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.Operator;
import dev.cel.common.ast.CelBlock;
import dev.cel.common.ast.CelConstant;
import dev.cel.common.ast.CelExpr;
import dev.cel.common.ast.CelExpr.CelComprehension;
import dev.cel.common.ast.CelExpr.CelList;
import dev.cel.common.ast.CelExpr.ExprKind;
import dev.cel.common.types.CelKind;
import dev.cel.common.types.CelType;
import dev.cel.common.types.CelTypeProvider;
import dev.cel.common.types.ListType;
import dev.cel.common.types.MapType;
import dev.cel.common.types.NullableType;
import dev.cel.common.types.SimpleType;
import dev.cel.common.types.StructType;
import dev.cel.common.types.StructTypeReference;
import dev.cel.common.types.TypeType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Internal translator from CEL AST to Z3 SMT expressions using a 4-valued logic tagged union.
 *
 * <p><b>Lifetime & Mutability:</b> This translator is stateful and highly mutable. It accumulates
 * type constraints, loop variables (via its symbol table), and uninterpreted functions (via field
 * caches) during AST traversal.
 *
 * <ul>
 *   <li>It is <b>not thread-safe</b>.
 *   <li>It is strictly bound to the lifecycle of a single Z3 {@link Context}.
 *   <li>It should be instantiated once per verification task.
 *   <li>For equivalence checks involving multiple ASTs, the <b>same</b> translator instance must be
 *       used to translate all ASTs. This ensures that uninterpreted functions (like field accesses)
 *       and type constraints are correctly shared and unified across the expressions.
 * </ul>
 */
@SuppressWarnings({"unchecked", "rawtypes"}) // Z3 Java API uses raw types.
final class CelAstToZ3Translator {
  private static final String MAP_REF_PREFIX = "!mapRef_";
  private static final String LIST_REF_PREFIX = "!listRef_";
  private static final String MSG_REF_PREFIX = "!msg_ref";
  private static final String EMPTY_MSG_REF_PREFIX = "!empty_msg_ref_";
  private static final String EMPTY_LIST_PREFIX = "!empty_list";
  private static final String EMPTY_MAP_PREFIX = "!empty_map";
  private static final String MAP_BIJECTION_PREFIX = "k_map_bijection";
  private final Context ctx;
  private final CelZ3TypeSystem typeSystem;
  private final CelZ3OperatorTranslator operatorTranslator;
  private final Map<String, TranslatedValue> symbolTable;
  private final Set<BoolExpr> typeConstraints;
  private final ImmutableSet<String> unknownIdentifiers;
  private final int comprehensionUnrollLimit;
  private final CelTypeProvider typeProvider;
  private final List<BoolExpr> truncationConditions;
  private final Map<String, Expr<?>> emptyMessageCache;
  private final Map<Object, Expr<?>> listLiteralCache;
  private Expr<?> emptyListCache;
  private Expr<?> emptyMapCache;

  /**
   * Returns a set of Z3 boolean expressions representing type constraints for the translated AST.
   */
  Set<BoolExpr> getTypeConstraints() {
    return typeConstraints;
  }

  BoolExpr hasTruncation() {
    return CelZ3TypeSystem.mkOrFlattened(ctx, truncationConditions);
  }

  CelZ3TypeSystem getTypeSystem() {
    return typeSystem;
  }

  /**
   * Returns a Z3 boolean expression asserting that the provided CEL value is a boolean and is true.
   */
  BoolExpr isTrue(Expr<?> celValue) {
    return ctx.mkAnd(typeSystem.isBool(celValue), (BoolExpr) typeSystem.unwrapBool(celValue));
  }

  /**
   * Binds an identifier name in the symbol table to an already-translated value. Used to bind
   * reserved policy symbols such as 'rule.result' to the composed policy graph.
   */
  void bindSymbol(String varName, TranslatedValue value) {
    symbolTable.put(varName, value);
  }

  TranslatedValue translate(CelAbstractSyntaxTree ast) {
    TranslatedValue result;
    CelBlock celBlock = CelBlock.extract(ast).orElse(null);
    if (celBlock != null) {
      result = translateBlock(celBlock.indices(), 0, celBlock.result(), ast);
    } else {
      result = translateExpr(ast.getExpr(), ast);
    }

    Set<Expr<?>> visited = new LinkedHashSet<>();
    Set<Expr<?>> listRefs = new LinkedHashSet<>();
    Set<Expr<?>> mapRefs = new LinkedHashSet<>();
    Set<Expr<?>> msgRefs = new LinkedHashSet<>();

    for (Expr<?> constraint : typeConstraints) {
      collectReferences(constraint, visited, listRefs, mapRefs, msgRefs);
    }
    collectReferences(result.z3Expr(), visited, listRefs, mapRefs, msgRefs);

    this.typeConstraints.addAll(
        CelZ3ExtensionalityAxioms.generateAxioms(ctx, typeSystem, listRefs, mapRefs, msgRefs));

    return result;
  }

  private void collectReferences(
      Expr<?> expr,
      Set<Expr<?>> visited,
      Set<Expr<?>> listRefs,
      Set<Expr<?>> mapRefs,
      Set<Expr<?>> msgRefs) {
    if (!visited.add(expr)) {
      return;
    }
    Sort sort = expr.getSort();
    if (sort.equals(typeSystem.listRefSort())) {
      listRefs.add(expr);
    } else if (sort.equals(typeSystem.mapRefSort())) {
      mapRefs.add(expr);
    } else if (sort.equals(typeSystem.messageRefSort())) {
      msgRefs.add(expr);
    }

    if (expr.isApp()) {
      for (Expr<?> arg : expr.getArgs()) {
        collectReferences(arg, visited, listRefs, mapRefs, msgRefs);
      }
    } else if (expr.isQuantifier()) {
      collectReferences(((Quantifier) expr).getBody(), visited, listRefs, mapRefs, msgRefs);
    }
  }

  private TranslatedValue translateExpr(CelExpr celExpr, CelAbstractSyntaxTree ast) {
    ExprKind.Kind kind = celExpr.exprKind().getKind();

    switch (kind) {
      case CONSTANT:
        return translateConstant(celExpr);
      case IDENT:
        return translateIdent(celExpr, ast);
      case SELECT:
        return translateSelect(celExpr, ast);
      case CALL:
        return translateCall(celExpr, ast);
      case LIST:
        return translateList(celExpr, ast);
      case MAP:
        return translateMap(celExpr, ast);
      case STRUCT:
        return translateStruct(celExpr, ast);
      case COMPREHENSION:
        return translateComprehension(celExpr, ast);
      default:
        throw new IllegalArgumentException("Unsupported expression kind: " + kind);
    }
  }

  private TranslatedValue translateConstant(CelExpr celExpr) {
    CelConstant constant = celExpr.constant();
    Expr<?> val;
    switch (constant.getKind()) {
      case BOOLEAN_VALUE:
        val = typeSystem.mkBool(constant.booleanValue());
        break;
      case INT64_VALUE:
        val = typeSystem.mkInt(constant.int64Value());
        break;
      case UINT64_VALUE:
        val = typeSystem.mkUint(constant.uint64Value().longValue());
        break;
      case DOUBLE_VALUE:
        val = typeSystem.mkDouble(constant.doubleValue());
        break;
      case STRING_VALUE:
        val = typeSystem.mkString(constant.stringValue());
        break;
      case BYTES_VALUE:
        val = typeSystem.mkBytes(constant.bytesValue().toStringUtf8());
        break;
      case NULL_VALUE:
        val = typeSystem.mkNull();
        break;
      default:
        throw new UnsupportedOperationException("Unsupported constant: " + constant.getKind());
    }
    return TranslatedValue.create(val, celExpr, typeSystem, ctx.mkFalse());
  }

  private TranslatedValue translateIdent(CelExpr celExpr, CelAbstractSyntaxTree ast) {
    CelExpr.CelIdent ident = celExpr.ident();
    long exprId = celExpr.id();
    String name = ident.name();
    CelType type = ast.getTypeOrThrow(exprId);
    if (type instanceof TypeType) {
      TypeType typeType = (TypeType) type;
      if (typeType.type().kind() != CelKind.DYN) {
        return TranslatedValue.create(
            typeSystem.mkString(typeType.containingTypeName()), celExpr, typeSystem, ctx.mkFalse());
      }
    }
    TranslatedValue tv =
        symbolTable.computeIfAbsent(
            name,
            n -> {
              Expr<?> v = ctx.mkConst(n, typeSystem.celValueSort());

              // Variables at rest can never be pre-cooked Errors
              // Basically prevents error being a counterexample of var == var
              typeConstraints.add(ctx.mkNot(typeSystem.isError(v)));

              BoolExpr constraint = createTypeConstraint(v, exprId, ast);
              if (unknownIdentifiers.contains(name)) {
                typeConstraints.add(ctx.mkOr(typeSystem.isUnknown(v), constraint));
              } else {
                typeConstraints.add(ctx.mkNot(typeSystem.isUnknown(v)));
                typeConstraints.add(constraint);
              }

              return TranslatedValue.create(
                  v,
                  CelExpr.newBuilder().setIdent(ident).setId(exprId).build(),
                  typeSystem,
                  /* isApproximate= */ ctx.mkFalse());
            });

    return TranslatedValue.create(tv.z3Expr(), celExpr, typeSystem, tv.isApproximate());
  }

  private TranslatedValue translateList(CelExpr celExpr, CelAbstractSyntaxTree ast) {
    CelList createList = celExpr.list();
    Optional<Object> cacheKey = toCacheKey(celExpr);
    List<TranslatedValue> elementsTv = new ArrayList<>();
    Expr<?> listRef = cacheKey.map(listLiteralCache::get).orElse(null);

    // Cache list literals containing only constants. If the same list literal appears multiple
    // times (e.g., in equality checks like `[1, 2] == [1, 2]`), caching allows us to reuse the
    // same Z3 constant. This avoids expensive sequence equality reasoning in Z3 by reducing the
    // check to a trivial identity check (e.g., `list_ref_0 == list_ref_0`).
    if (listRef == null) {
      SeqExpr seq = ctx.mkEmptySeq(ctx.mkSeqSort(typeSystem.celValueSort()));
      ImmutableList<Integer> optionalIndices = createList.optionalIndices();
      ImmutableList<CelExpr> elements = createList.elements();
      for (int i = 0; i < elements.size(); i++) {
        CelExpr element = elements.get(i);
        TranslatedValue elem = translateExpr(element, ast);
        elementsTv.add(elem);

        if (optionalIndices.contains(i)) {
          Expr<?> optRef = typeSystem.getOptionalRef(elem.z3Expr());
          seq =
              (SeqExpr)
                  ctx.mkITE(
                      typeSystem.optHasValue(optRef),
                      typeSystem.mkConcatSafe(seq, ctx.mkUnit(typeSystem.getOptionalValue(optRef))),
                      seq);
        } else {
          seq = typeSystem.mkConcatSafe(seq, ctx.mkUnit(elem.z3Expr()));
        }
      }
      listRef = typeSystem.mkListRefConst(LIST_REF_PREFIX);
      typeConstraints.add(ctx.mkEq(typeSystem.getSeq(listRef), seq));
      Expr<?> finalListRef = listRef;
      cacheKey.ifPresent(key -> listLiteralCache.put(key, finalListRef));
    }

    Expr<?> result = typeSystem.wrapList(listRef);
    return TranslatedValue.propagateStrict(ctx, typeSystem, result, celExpr, elementsTv);
  }

  private TranslatedValue translateMap(CelExpr celExpr, CelAbstractSyntaxTree ast) {
    CelExpr.CelMap createMap = celExpr.map();
    Expr<?> mapRef = typeSystem.mkMapRefConst(MAP_REF_PREFIX);

    ArrayExpr mapValues = ctx.mkConstArray(typeSystem.celValueSort(), typeSystem.mkUnknown());
    ArrayExpr mapPresence = ctx.mkConstArray(typeSystem.celValueSort(), ctx.mkFalse());
    Expr<?> keysSeq = ctx.mkEmptySeq(ctx.mkSeqSort(typeSystem.celValueSort()));

    List<TranslatedValue> elementsTv = new ArrayList<>();

    for (CelExpr.CelMap.Entry entryAst : createMap.entries()) {
      TranslatedValue keyTv = translateExpr(entryAst.key(), ast);
      Expr<?> key = keyTv.z3Expr();
      elementsTv.add(keyTv);
      TranslatedValue valueTv = translateExpr(entryAst.value(), ast);
      Expr<?> value = valueTv.z3Expr();
      elementsTv.add(valueTv);

      Expr<?> finalValue = value;
      BoolExpr finalPresence = ctx.mkTrue();
      if (entryAst.optionalEntry()) {
        Expr<?> optRef = typeSystem.getOptionalRef(value);
        finalPresence = typeSystem.optHasValue(optRef);
        finalValue = typeSystem.getOptionalValue(optRef);
      }

      BoolExpr keyAlreadyPresent = (BoolExpr) ctx.mkSelect(mapPresence, key);
      BoolExpr shouldInsertKey = ctx.mkAnd(ctx.mkNot(keyAlreadyPresent), finalPresence);
      keysSeq =
          ctx.mkITE(shouldInsertKey, typeSystem.mkConcatSafe(keysSeq, ctx.mkUnit(key)), keysSeq);

      mapValues =
          (ArrayExpr) ctx.mkITE(finalPresence, ctx.mkStore(mapValues, key, finalValue), mapValues);
      mapPresence =
          (ArrayExpr)
              ctx.mkITE(finalPresence, ctx.mkStore(mapPresence, key, ctx.mkTrue()), mapPresence);
    }

    typeConstraints.add(ctx.mkEq(typeSystem.getMapValues(mapRef), mapValues));
    typeConstraints.add(ctx.mkEq(typeSystem.getMapPresence(mapRef), mapPresence));
    typeConstraints.add(ctx.mkEq(typeSystem.getMapKeys(mapRef), keysSeq));

    Expr<?> result = typeSystem.wrapMap(mapRef);
    return TranslatedValue.propagateStrict(ctx, typeSystem, result, celExpr, elementsTv);
  }

  private TranslatedValue translateStruct(CelExpr celExpr, CelAbstractSyntaxTree ast) {
    CelExpr.CelStruct createStruct = celExpr.struct();
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
      elementsTv.add(valueTv);

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
        Expr<?> optRef = typeSystem.getOptionalRef(value);
        optionalHasValue = typeSystem.optHasValue(optRef);
        finalValue = typeSystem.getOptionalValue(optRef);
      }

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

    typeConstraints.add(
        ctx.mkEq(typeSystem.getMsgTypeName(msgRef), ctx.mkString(createStruct.messageName())));
    typeConstraints.add(ctx.mkEq(typeSystem.getMsgValues(msgRef), msgValues));
    typeConstraints.add(ctx.mkEq(typeSystem.getMsgPresence(msgRef), msgPresence));

    Expr<?> result = typeSystem.wrapMessage(msgRef);
    return TranslatedValue.propagateStrict(ctx, typeSystem, result, celExpr, elementsTv);
  }

  private Expr<?> getDefaultValueForType(CelType type) {
    if (type instanceof NullableType) {
      return typeSystem.mkNull();
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
    if (type instanceof ListType) {
      if (emptyListCache == null) {
        emptyListCache = typeSystem.mkListRefConst(EMPTY_LIST_PREFIX);
        typeConstraints.add(
            ctx.mkEq(
                typeSystem.getSeq(emptyListCache),
                ctx.mkEmptySeq(ctx.mkSeqSort(typeSystem.celValueSort()))));
      }
      return typeSystem.wrapList(emptyListCache);
    }
    if (type instanceof MapType) {
      if (emptyMapCache == null) {
        emptyMapCache = typeSystem.mkMapRefConst(EMPTY_MAP_PREFIX);
        typeConstraints.add(
            ctx.mkEq(
                typeSystem.getMapPresence(emptyMapCache),
                ctx.mkConstArray(typeSystem.celValueSort(), ctx.mkFalse())));
        typeConstraints.add(
            ctx.mkEq(
                typeSystem.getMapValues(emptyMapCache),
                ctx.mkConstArray(typeSystem.celValueSort(), typeSystem.mkUnknown())));
        typeConstraints.add(
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
                typeConstraints.add(
                    ctx.mkEq(
                        typeSystem.getMsgPresence(ref),
                        ctx.mkConstArray(ctx.getStringSort(), ctx.mkFalse())));
                typeConstraints.add(
                    ctx.mkEq(
                        typeSystem.getMsgValues(ref),
                        ctx.mkConstArray(ctx.getStringSort(), typeSystem.mkUnknown())));
                typeConstraints.add(ctx.mkEq(typeSystem.getMsgTypeName(ref), ctx.mkString(name)));
                return ref;
              });
      return typeSystem.wrapMessage(msgRef);
    }
    return typeSystem.mkUnknown();
  }

  private static final class FieldAccess {
    final Expr<?> presence;
    final Expr<?> value;

    FieldAccess(Expr<?> presence, Expr<?> value) {
      this.presence = presence;
      this.value = value;
    }
  }

  private FieldAccess getMapAccess(Expr<?> operand, String field, BoolExpr typeGuard) {
    Expr<?> mapRef = typeSystem.getMapRef(operand);
    Expr<?> mapFieldZ3Str = typeSystem.mkString(field);
    Expr<?> presence = ctx.mkSelect((ArrayExpr) typeSystem.getMapPresence(mapRef), mapFieldZ3Str);
    Expr<?> value = ctx.mkSelect((ArrayExpr) typeSystem.getMapValues(mapRef), mapFieldZ3Str);

    BoolExpr valNotError = ctx.mkNot(ctx.mkEq(value, typeSystem.mkError()));
    typeConstraints.add(
        ctx.mkImplies(
            CelZ3TypeSystem.mkAndFlattened(ctx, typeGuard, (BoolExpr) presence), valNotError));
    if (unknownIdentifiers.isEmpty()) {
      BoolExpr valNotUnknown = ctx.mkNot(typeSystem.isUnknown(value));
      typeConstraints.add(
          ctx.mkImplies(
              CelZ3TypeSystem.mkAndFlattened(ctx, typeGuard, (BoolExpr) presence), valNotUnknown));
    }

    return new FieldAccess(presence, value);
  }

  private FieldAccess getMsgAccess(Expr<?> operand, String field, BoolExpr typeGuard) {
    Expr<?> msgRef = typeSystem.getMessageRef(operand);
    Expr<?> msgFieldZ3Str = ctx.mkString(field);
    Expr<?> presence = ctx.mkSelect((ArrayExpr) typeSystem.getMsgPresence(msgRef), msgFieldZ3Str);
    Expr<?> value = ctx.mkSelect((ArrayExpr) typeSystem.getMsgValues(msgRef), msgFieldZ3Str);

    BoolExpr valNotError = ctx.mkNot(ctx.mkEq(value, typeSystem.mkError()));
    typeConstraints.add(
        ctx.mkImplies(
            CelZ3TypeSystem.mkAndFlattened(ctx, typeGuard, (BoolExpr) presence), valNotError));
    if (unknownIdentifiers.isEmpty()) {
      BoolExpr valNotUnknown = ctx.mkNot(typeSystem.isUnknown(value));
      typeConstraints.add(
          ctx.mkImplies(
              CelZ3TypeSystem.mkAndFlattened(ctx, typeGuard, (BoolExpr) presence), valNotUnknown));
    }

    return new FieldAccess(presence, value);
  }

  private TranslatedValue translateSelect(CelExpr celExpr, CelAbstractSyntaxTree ast) {
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
      valueResult = ctx.mkITE((BoolExpr) mapAcc.presence, mapAcc.value, typeSystem.mkError());
    } else if (operandType.kind() == CelKind.STRUCT) {
      FieldAccess msgAcc = getMsgAccess(operand, field, ctx.mkTrue());
      presenceResult = msgAcc.presence;
      Expr<?> defaultVal = getDefaultValueForType(extractAstTypeOrDefault(ast, exprId));
      valueResult = ctx.mkITE((BoolExpr) msgAcc.presence, msgAcc.value, defaultVal);
    } else {
      // Dynamic type: generate the full SMT decision tree
      BoolExpr isMap = typeSystem.isMap(operand);
      BoolExpr isMessage = typeSystem.isMessage(operand);

      FieldAccess mapAcc = getMapAccess(operand, field, isMap);
      FieldAccess msgAcc = getMsgAccess(operand, field, isMessage);

      presenceResult =
          CelZ3TypeSystem.SwitchBuilder.newBuilder(ctx)
              .addCase(isMessage, msgAcc.presence)
              .addCase(isMap, mapAcc.presence)
              .build(ctx.mkFalse());

      Expr<?> defaultVal = getDefaultValueForType(extractAstTypeOrDefault(ast, exprId));
      Expr<?> msgRead = ctx.mkITE((BoolExpr) msgAcc.presence, msgAcc.value, defaultVal);
      Expr<?> mapRead = ctx.mkITE((BoolExpr) mapAcc.presence, mapAcc.value, typeSystem.mkError());

      valueResult =
          CelZ3TypeSystem.SwitchBuilder.newBuilder(ctx)
              .addCase(isMessage, msgRead)
              .addCase(isMap, mapRead)
              .build(typeSystem.mkError());
    }

    Expr<?> fieldAccess = select.testOnly() ? typeSystem.wrapBool(presenceResult) : valueResult;

    typeConstraints.add(createTypeConstraint(fieldAccess, exprId, ast));

    return TranslatedValue.propagateStrict(
        ctx, typeSystem, fieldAccess, celExpr, ImmutableList.of(operandTv));
  }

  private TranslatedValue translateBlock(
      List<CelExpr> boundExprs, int currentIndex, CelExpr resultExpr, CelAbstractSyntaxTree ast) {
    if (currentIndex >= boundExprs.size()) {
      return translateExpr(resultExpr, ast);
    }

    CelExpr subExprAst = boundExprs.get(currentIndex);
    TranslatedValue subExprVal = translateExpr(subExprAst, ast);
    String varName = CelBlock.INDEX_PREFIX + currentIndex;

    return withScope(
        varName, subExprVal, () -> translateBlock(boundExprs, currentIndex + 1, resultExpr, ast));
  }

  private TranslatedValue translateCall(CelExpr expr, CelAbstractSyntaxTree ast) {
    CelExpr.CelCall call = expr.call();
    long exprId = expr.id();
    String functionName = call.function();
    ImmutableList<CelExpr> argsAst = call.args();

    List<TranslatedValue> args = new ArrayList<>();

    if (call.target().isPresent()) {
      CelExpr targetAst = call.target().get();
      args.add(translateExpr(targetAst, ast));
    }

    for (CelExpr argAst : argsAst) {
      args.add(translateExpr(argAst, ast));
    }

    return operatorTranslator
        .translateFunctionCall(functionName, args, exprId, ast)
        .map(tv -> TranslatedValue.create(tv.z3Expr(), expr, typeSystem, tv.isApproximate()))
        .orElseGet(
            () -> {
              // Uninterpreted function
              Sort[] argSorts = new Sort[args.size()];
              Arrays.fill(argSorts, 0, args.size(), typeSystem.celValueSort());
              FuncDecl<?> funcDecl =
                  typeSystem.internFuncDecl(functionName, argSorts, typeSystem.celValueSort());

              Expr<?>[] exprArgs = args.stream().map(TranslatedValue::z3Expr).toArray(Expr[]::new);
              Expr<?> callRes = ctx.mkApp(funcDecl, exprArgs);
              typeConstraints.add(createTypeConstraint(callRes, exprId, ast));
              typeConstraints.add(ctx.mkNot(typeSystem.isUnknown(callRes)));
              typeConstraints.add(ctx.mkNot(typeSystem.isError(callRes)));

              return TranslatedValue.propagateStrict(
                  ctx, typeSystem, callRes, Optional.of(expr), ctx.mkTrue(), args);
            });
  }

  private <T> T withScope(String varName, TranslatedValue value, Supplier<T> action) {
    TranslatedValue prev = symbolTable.put(varName, value);
    try {
      return action.get();
    } finally {
      if (prev != null) {
        symbolTable.put(varName, prev);
      } else {
        symbolTable.remove(varName);
      }
    }
  }

  private TranslatedValue translateComprehension(CelExpr celExpr, CelAbstractSyntaxTree ast) {
    CelComprehension comp = celExpr.comprehension();
    CelExpr iterRangeExpr = comp.iterRange();
    if (iterRangeExpr.exprKind().getKind() == ExprKind.Kind.IDENT) {
      TranslatedValue boundTv = symbolTable.get(iterRangeExpr.ident().name());
      if (boundTv != null) {
        iterRangeExpr = boundTv.celExpr().orElse(iterRangeExpr);
      }
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
      TranslatedValue[] condAndStep =
          evaluateLoopCondAndStep(
              comp, ast, iterElem.keyOrIndex, iterElem.value, currentAccu, isMap, isTwoVar);
      Expr<?> condition = condAndStep[0].z3Expr();
      Expr<?> step = condAndStep[1].z3Expr();
      taints.add(condAndStep[1].isApproximate());

      Expr<?> stepVal = ctx.mkITE((BoolExpr) typeSystem.unwrapBool(condition), step, currentAccu);
      Expr<?> typeErrorOrStep =
          typeSystem.withRuntimeError(stepVal, ctx.mkNot(typeSystem.isBool(condition)));

      accu = typeSystem.propagateErrorAndUnknown(typeErrorOrStep, condition);
    }

    TranslatedValue resultTv =
        withScope(
            comp.accuVar(),
            TranslatedValue.create(accu, typeSystem, ctx.mkFalse()),
            () -> translateExpr(comp.result(), ast));
    taints.add(resultTv.isApproximate());
    Expr<?> result = resultTv.z3Expr();
    return TranslatedValue.create(
        typeSystem.propagateErrorAndUnknown(result, allRangeElems),
        celExpr,
        typeSystem,
        CelZ3TypeSystem.mkOrFlattened(ctx, taints));
  }

  private static CelType extractAstTypeOrDefault(CelAbstractSyntaxTree ast, long id) {
    return ast.getType(id).orElse(SimpleType.DYN);
  }

  private static final class BoundedIteration {
    final BoolExpr inBounds;
    final TranslatedValue stepResult;

    BoundedIteration(BoolExpr inBounds, TranslatedValue stepResult) {
      this.inBounds = inBounds;
      this.stepResult = stepResult;
    }
  }

  private TranslatedValue translateDynamicComprehension(
      CelExpr celExpr, CelAbstractSyntaxTree ast) {
    CelComprehension comp = celExpr.comprehension();
    TranslatedValue iterRangeTv = translateExpr(comp.iterRange(), ast);
    Expr<?> iterRange = iterRangeTv.z3Expr();
    CelType rangeType = extractAstTypeOrDefault(ast, comp.iterRange().id());

    boolean isList = rangeType instanceof ListType;
    boolean isMap = rangeType instanceof MapType;
    if (!isList && !isMap) {
      BoolExpr isRuntimeListOrMap =
          ctx.mkOr(typeSystem.isList(iterRange), typeSystem.isMap(iterRange));
      Expr<?> result = ctx.mkITE(isRuntimeListOrMap, typeSystem.mkUnknown(), typeSystem.mkError());
      return TranslatedValue.create(result, celExpr, typeSystem, isRuntimeListOrMap);
    }

    SeqExpr<?> seq =
        isMap
            ? typeSystem.getMapKeys(typeSystem.getMapRef(iterRange))
            : typeSystem.getSeq(typeSystem.getListRef(iterRange));

    ArithExpr lengthExpr = ctx.mkLength(seq);
    ArrayExpr mapPresence =
        isMap ? (ArrayExpr) typeSystem.getMapPresence(typeSystem.getMapRef(iterRange)) : null;

    if (isMap) {
      applyBoundedMapBijection(mapPresence, seq, lengthExpr);
    }

    BoolExpr isTruncated = ctx.mkGt(lengthExpr, ctx.mkInt(comprehensionUnrollLimit));
    truncationConditions.add(isTruncated);

    if (isAllMacro(comp) || isExistsMacro(comp)) {
      return unrollAllAndExists(
          celExpr, ast, seq, lengthExpr, mapPresence, isTruncated, iterRangeTv);
    } else {
      return unrollMapAndFilter(
          celExpr, ast, seq, lengthExpr, mapPresence, isTruncated, iterRangeTv);
    }
  }

  private void applyBoundedMapBijection(
      ArrayExpr mapPresence, SeqExpr<?> seq, ArithExpr lengthExpr) {
    for (int i = 0; i < comprehensionUnrollLimit; i++) {
      for (int j = i + 1; j < comprehensionUnrollLimit; j++) {
        BoolExpr validPair = ctx.mkLt(ctx.mkInt(j), lengthExpr);
        BoolExpr notEqual =
            ctx.mkNot(ctx.mkEq(ctx.mkNth(seq, ctx.mkInt(i)), ctx.mkNth(seq, ctx.mkInt(j))));
        typeConstraints.add(ctx.mkImplies(validPair, notEqual));
      }
    }

    Expr<?> kVar = ctx.mkFreshConst(MAP_BIJECTION_PREFIX, typeSystem.celValueSort());
    BoolExpr isValidKey =
        ctx.mkOr(
            typeSystem.isInt(kVar), typeSystem.isUint(kVar),
            typeSystem.isBool(kVar), typeSystem.isString(kVar));
    BoolExpr inMap = (BoolExpr) ctx.mkSelect(mapPresence, kVar);

    List<BoolExpr> inSeqMatches = new ArrayList<>();
    for (int i = 0; i < comprehensionUnrollLimit; i++) {
      BoolExpr match =
          ctx.mkAnd(
              ctx.mkLt(ctx.mkInt(i), lengthExpr), ctx.mkEq(kVar, ctx.mkNth(seq, ctx.mkInt(i))));
      inSeqMatches.add(match);
    }
    BoolExpr inSeq = CelZ3TypeSystem.mkOrFlattened(ctx, inSeqMatches);

    BoolExpr isNotTruncated = ctx.mkLe(lengthExpr, ctx.mkInt(comprehensionUnrollLimit));

    Pattern inMapPattern = ctx.mkPattern(inMap);

    BoolExpr completeness =
        ctx.mkForall(
            new Expr<?>[] {kVar},
            ctx.mkImplies(ctx.mkAnd(isNotTruncated, isValidKey, inMap), inSeq),
            1,
            new Pattern[] {inMapPattern},
            null,
            null,
            null);
    typeConstraints.add(completeness);
  }

  private TranslatedValue[] evaluateLoopCondAndStep(
      CelComprehension comp,
      CelAbstractSyntaxTree ast,
      Expr<?> keyOrIndex,
      Expr<?> value,
      Expr<?> currentAccu,
      boolean isMap,
      boolean isTwoVar) {
    Supplier<TranslatedValue[]> evalBody =
        () ->
            new TranslatedValue[] {
              translateExpr(comp.loopCondition(), ast), translateExpr(comp.loopStep(), ast)
            };

    Supplier<TranslatedValue[]> bindAccu =
        () ->
            withScope(
                comp.accuVar(),
                TranslatedValue.create(currentAccu, typeSystem, ctx.mkFalse()),
                evalBody);

    if (isTwoVar) {
      return withScope(
          comp.iterVar(),
          TranslatedValue.create(keyOrIndex, typeSystem, ctx.mkFalse()),
          () ->
              withScope(
                  comp.iterVar2(),
                  TranslatedValue.create(value, typeSystem, ctx.mkFalse()),
                  bindAccu));
    } else {
      Expr<?> iterVal = isMap ? keyOrIndex : value;
      return withScope(
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

      TranslatedValue[] condAndStep =
          evaluateLoopCondAndStep(
              comp, ast, iterElem.keyOrIndex, iterElem.value, accuInitExpr, isMap, isTwoVar);
      iterations.add(new BoundedIteration(inBounds, condAndStep[1]));
    }

    TranslatedValue reducedTv =
        reduceAllOrExists(iterations, isTruncated, iterRangeTv, isAllMacro(comp), celExpr, ast);
    return TranslatedValue.create(
        typeSystem.propagateErrorAndUnknown(reducedTv.z3Expr(), iterRangeTv.z3Expr()),
        celExpr,
        typeSystem,
        reducedTv.isApproximate());
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

    List<BoolExpr> brokeConds = new ArrayList<>();
    List<BoolExpr> taints = new ArrayList<>();
    taints.add(chainedAccuTv.isApproximate());
    taints.add(iterRangeTv.isApproximate());

    for (int i = 0; i < comprehensionUnrollLimit; i++) {
      IntExpr idx = ctx.mkInt(i);
      BoolExpr inBounds = ctx.mkLt(idx, lengthExpr);
      Expr<?> xVal = ctx.mkNth(seq, idx);

      constrainIterationElement(idx, lengthExpr, xVal, inBounds, mapPresence);

      Expr<?> currentAccu = chainedAccu;
      BoolExpr currentHasBroken = CelZ3TypeSystem.mkOrFlattened(ctx, brokeConds);

      IterationElement iterElem =
          getIterationElement(xVal, idx, iterRangeTv.z3Expr(), isMap, isTwoVar);

      TranslatedValue[] condAndStep =
          evaluateLoopCondAndStep(
              comp, ast, iterElem.keyOrIndex, iterElem.value, currentAccu, isMap, isTwoVar);
      Expr<?> condExpr = condAndStep[0].z3Expr();
      Expr<?> stepExpr = condAndStep[1].z3Expr();

      BoolExpr condIsBool = typeSystem.isBool(condExpr);
      BoolExpr condIsTrue = ctx.mkAnd(condIsBool, (BoolExpr) typeSystem.unwrapBool(condExpr));
      BoolExpr condIsNotTrue = ctx.mkNot(condIsTrue);

      BoolExpr isActive = ctx.mkAnd(inBounds, ctx.mkNot(currentHasBroken));

      Expr<?> stepVal =
          ctx.mkITE((BoolExpr) typeSystem.unwrapBool(condExpr), stepExpr, currentAccu);
      Expr<?> typeErrorOrStep = typeSystem.withRuntimeError(stepVal, ctx.mkNot(condIsBool));
      // Standard macros' loop condition can't be approximate. However, we still
      // keep the check here for custom macros to be safe.
      taints.add(ctx.mkAnd(isActive, condAndStep[0].isApproximate()));
      taints.add(ctx.mkAnd(isActive, condAndStep[1].isApproximate()));

      chainedAccu =
          ctx.mkITE(
              isActive,
              typeSystem.propagateErrorAndUnknown(typeErrorOrStep, condExpr),
              currentAccu);

      brokeConds.add(ctx.mkAnd(inBounds, condIsNotTrue));
    }

    TranslatedValue resultTv =
        withScope(
            comp.accuVar(),
            TranslatedValue.create(chainedAccu, typeSystem, ctx.mkFalse()),
            () -> translateExpr(comp.result(), ast));

    taints.add(resultTv.isApproximate());

    BoolExpr isNotError = ctx.mkNot(typeSystem.isError(resultTv.z3Expr()));
    BoolExpr shouldYieldUnknown = ctx.mkAnd(isTruncated, isNotError);
    taints.add(shouldYieldUnknown);

    return TranslatedValue.create(
        typeSystem.propagateErrorAndUnknown(
            ctx.mkITE(shouldYieldUnknown, mkParameterizedUnknown(celExpr, ast), resultTv.z3Expr()),
            iterRangeTv.z3Expr()),
        celExpr,
        typeSystem,
        CelZ3TypeSystem.mkOrFlattened(ctx, taints));
  }

  private void constrainIterationElement(
      IntExpr idx, ArithExpr lengthExpr, Expr<?> xVal, BoolExpr inBounds, ArrayExpr mapPresence) {
    // Constrain out-of-bounds elements to prevent MBQI from infinitely instantiating list/map
    // axioms
    BoolExpr outOfBounds = ctx.mkGe(idx, lengthExpr);
    typeConstraints.add(ctx.mkImplies(outOfBounds, ctx.mkEq(xVal, typeSystem.mkUnknown())));

    // If we are iterating over a map, we can optionally bind the sequence elements
    // to the map presence array to ensure the solver knows they correspond.
    if (mapPresence != null) {
      Expr<?> selectExpr = ctx.mkSelect(mapPresence, xVal);
      typeConstraints.add(ctx.mkImplies(inBounds, (BoolExpr) selectExpr));
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

      hasMatchList.add(CelZ3TypeSystem.mkAndFlattened(ctx, isActive, isMatch));
      hasErrorList.add(CelZ3TypeSystem.mkAndFlattened(ctx, isActive, isE));
      hasUnknownList.add(CelZ3TypeSystem.mkAndFlattened(ctx, isActive, isU));

      hasSafeMatchList.add(
          CelZ3TypeSystem.mkAndFlattened(
              ctx, isActive, isMatch, CelZ3TypeSystem.mkNotFlattened(ctx, stepTv.isApproximate())));
      hasSafeErrorList.add(
          CelZ3TypeSystem.mkAndFlattened(
              ctx, isActive, isE, CelZ3TypeSystem.mkNotFlattened(ctx, stepTv.isApproximate())));
      hasSafeUnknownList.add(
          CelZ3TypeSystem.mkAndFlattened(
              ctx, isActive, isU, CelZ3TypeSystem.mkNotFlattened(ctx, stepTv.isApproximate())));
      activeTaints.add(CelZ3TypeSystem.mkAndFlattened(ctx, isActive, stepTv.isApproximate()));
    }

    BoolExpr hasMatch = CelZ3TypeSystem.mkOrFlattened(ctx, hasMatchList);
    BoolExpr hasError = CelZ3TypeSystem.mkOrFlattened(ctx, hasErrorList);
    BoolExpr hasUnknown = CelZ3TypeSystem.mkOrFlattened(ctx, hasUnknownList);

    BoolExpr hasSafeMatch = CelZ3TypeSystem.mkOrFlattened(ctx, hasSafeMatchList);
    BoolExpr hasSafeError = CelZ3TypeSystem.mkOrFlattened(ctx, hasSafeErrorList);
    BoolExpr hasSafeUnknown = CelZ3TypeSystem.mkOrFlattened(ctx, hasSafeUnknownList);
    BoolExpr anyActiveTaint = CelZ3TypeSystem.mkOrFlattened(ctx, activeTaints);

    Expr<?> result =
        CelZ3TypeSystem.SwitchBuilder.newBuilder(ctx)
            .addCase(hasMatch, typeSystem.mkBool(!isAll))
            .addCase(ctx.mkOr(hasUnknown, isTruncated), mkParameterizedUnknown(compExpr, ast))
            .addCase(hasError, typeSystem.mkError())
            .build(typeSystem.mkBool(isAll));

    BoolExpr baseTaint =
        CelZ3TypeSystem.mkOrFlattened(
            ctx, Arrays.asList(anyActiveTaint, iterRangeTv.isApproximate()));

    // Taint identically shadows the value flow's short-circuit control structure
    BoolExpr resultTaint =
        (BoolExpr)
            CelZ3TypeSystem.SwitchBuilder.newBuilder(ctx)
                .addCase(hasMatch, ctx.mkNot(hasSafeMatch))
                .addCase(
                    ctx.mkOr(hasUnknown, isTruncated),
                    ctx.mkOr(isTruncated, ctx.mkNot(hasSafeUnknown)))
                .addCase(hasError, ctx.mkNot(hasSafeError))
                .build(baseTaint);

    return TranslatedValue.create(result, typeSystem, resultTaint);
  }

  private static boolean isAllMacro(CelComprehension comp) {
    return isBooleanAccuInit(comp, true) && isNotStrictlyFalseLoopCondition(comp);
  }

  private static boolean isExistsMacro(CelComprehension comp) {
    return isBooleanAccuInit(comp, false) && isNotStrictlyFalseLoopCondition(comp);
  }

  private static boolean isBooleanAccuInit(CelComprehension comp, boolean expectedValue) {
    return comp.accuInit().constantOrDefault().getKind() == CelConstant.Kind.BOOLEAN_VALUE
        && comp.accuInit().constant().booleanValue() == expectedValue;
  }

  private static boolean isNotStrictlyFalseLoopCondition(CelComprehension comp) {
    CelExpr.CelCall call = comp.loopCondition().callOrDefault();
    return (call.function().equals(Operator.NOT_STRICTLY_FALSE.getFunction())
            || call.function().equals(Operator.OLD_NOT_STRICTLY_FALSE.getFunction()))
        && call.args().size() == 1
        && call.args().get(0).identOrDefault().name().equals(comp.accuVar());
  }

  private BoolExpr createTypeConstraint(Expr<?> val, long exprId, CelAbstractSyntaxTree ast) {
    CelType type =
        ast.getType(exprId)
            .orElseThrow(
                () -> new IllegalArgumentException("Type not found for expr ID: " + exprId));
    BoolExpr typeConstraint = createTypeConstraintForType(val, type);
    return ctx.mkOr(typeSystem.isError(val), typeSystem.isUnknown(val), typeConstraint);
  }

  private BoolExpr createTypeConstraintForType(Expr<?> val, CelType type) {
    if (type.equals(SimpleType.BOOL)) {
      return (BoolExpr) ctx.mkApp(typeSystem.boolCons().getTesterDecl(), val);
    }
    if (type.equals(SimpleType.INT)) {
      Expr<?> unwrapped = ctx.mkApp(typeSystem.intCons().getAccessorDecls()[0], val);
      return ctx.mkAnd(
          ctx.mkApp(typeSystem.intCons().getTesterDecl(), val),
          ctx.mkGe((ArithExpr) unwrapped, ctx.mkInt(CelZ3TypeSystem.MIN_INT64)),
          ctx.mkLe((ArithExpr) unwrapped, ctx.mkInt(CelZ3TypeSystem.MAX_INT64)));
    }
    if (type.equals(SimpleType.UINT)) {
      Expr<?> unwrapped = ctx.mkApp(typeSystem.uintCons().getAccessorDecls()[0], val);
      return ctx.mkAnd(
          ctx.mkApp(typeSystem.uintCons().getTesterDecl(), val),
          ctx.mkGe((ArithExpr) unwrapped, ctx.mkInt(0)),
          ctx.mkLe((ArithExpr) unwrapped, ctx.mkInt(CelZ3TypeSystem.MAX_UINT64)));
    }
    if (type.equals(SimpleType.DOUBLE)) {
      return (BoolExpr) ctx.mkApp(typeSystem.doubleCons().getTesterDecl(), val);
    }
    if (type.equals(SimpleType.STRING)) {
      return (BoolExpr) ctx.mkApp(typeSystem.stringCons().getTesterDecl(), val);
    }
    if (type.equals(SimpleType.BYTES)) {
      return (BoolExpr) ctx.mkApp(typeSystem.bytesCons().getTesterDecl(), val);
    }
    if (type instanceof ListType) {
      // Lists are explicitly bounded (sequence theory). We're safe in using for-all quantifiers
      // here.
      BoolExpr isList = typeSystem.isList(val);
      CelType elemType = ((ListType) type).elemType();
      if (elemType.equals(SimpleType.DYN)) {
        return isList;
      }

      // isList(val) ∧ ∀i. (0 <= i < length) ⇒ elemType(seq[i])
      Expr<?> listRef = typeSystem.getListRef(val);
      SeqExpr seq = typeSystem.getSeq(listRef);
      Expr length = ctx.mkLength(seq);

      List<BoolExpr> boundsAndTypes = new ArrayList<>();
      boundsAndTypes.add(isList);
      for (int i = 0; i < comprehensionUnrollLimit; i++) {
        IntExpr idx = ctx.mkInt(i);
        Expr elem = ctx.mkNth(seq, idx);
        BoolExpr elemConstraint = createTypeConstraintForType(elem, elemType);
        BoolExpr validIndex = ctx.mkLt(idx, length);
        boundsAndTypes.add(ctx.mkImplies(validIndex, elemConstraint));
        BoolExpr outOfBounds = ctx.mkGe(idx, length);
        boundsAndTypes.add(ctx.mkImplies(outOfBounds, ctx.mkEq(elem, typeSystem.mkUnknown())));
      }

      return CelZ3TypeSystem.mkAndFlattened(ctx, boundsAndTypes);
    }
    if (type instanceof MapType) {
      // Do NOT emit a for-all quantifier over map keys here.
      // Doing so forces MBQI into an infinite loop. Structural equivalence of dynamic keys is
      // naturally constrained by the primitive key assertions in getStructuralEquality().
      return typeSystem.isMap(val);
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

  CelAstToZ3Translator(
      Context ctx,
      int comprehensionUnrollLimit,
      ImmutableSet<String> unknownIdentifiers,
      CelZ3FunctionRegistry functionRegistry,
      CelTypeProvider typeProvider) {
    this.ctx = ctx;
    this.comprehensionUnrollLimit = comprehensionUnrollLimit;
    this.typeSystem = new CelZ3TypeSystem(ctx);
    this.typeConstraints = new LinkedHashSet<>();
    this.operatorTranslator =
        new CelZ3OperatorTranslator(
            ctx,
            typeSystem,
            this.typeConstraints::add,
            this::createTypeConstraintForType,
            !unknownIdentifiers.isEmpty(),
            functionRegistry);
    this.symbolTable = new HashMap<>();
    this.unknownIdentifiers = unknownIdentifiers;
    this.emptyMessageCache = new HashMap<>();
    this.listLiteralCache = new HashMap<>();
    this.typeProvider = typeProvider;
    this.truncationConditions = new ArrayList<>();
  }

  private static class IterationElement {
    final Expr<?> keyOrIndex;
    final Expr<?> value;

    IterationElement(Expr<?> keyOrIndex, Expr<?> value) {
      this.keyOrIndex = keyOrIndex;
      this.value = value;
    }
  }

  private Optional<Object> toCacheKey(CelExpr expr) {
    switch (expr.exprKind().getKind()) {
      case CONSTANT:
        return Optional.of(expr.constant());
      case LIST:
        ImmutableList.Builder<Object> builder = ImmutableList.builder();
        for (CelExpr elem : expr.list().elements()) {
          Optional<Object> elemKey = toCacheKey(elem);
          if (!elemKey.isPresent()) {
            return Optional.empty(); // Contains non-constants
          }
          builder.add(elemKey.get());
        }
        return Optional.of(builder.build());
      default:
        return Optional.empty();
    }
  }

  private Expr<?> mkParameterizedUnknown(CelExpr expr, CelAbstractSyntaxTree ast) {
    CelAstAlphaHasher.AlphaSignature sig = CelAstAlphaHasher.computeSignature(expr);

    ImmutableList.Builder<Expr<?>> smtArgs = ImmutableList.builder();
    for (CelExpr freeVar : sig.freeVariables()) {
      smtArgs.add(translateExpr(freeVar, ast).z3Expr());
    }

    return typeSystem.mkParameterizedUnknown(sig.staticHash(), smtArgs.build());
  }
}
