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
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.microsoft.z3.ArrayExpr;
import com.microsoft.z3.BoolExpr;
import com.microsoft.z3.Context;
import com.microsoft.z3.Expr;
import com.microsoft.z3.FuncDecl;
import com.microsoft.z3.Quantifier;
import com.microsoft.z3.SeqExpr;
import com.microsoft.z3.Sort;
import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.Operator;
import dev.cel.common.ast.CelBlock;
import dev.cel.common.ast.CelConstant;
import dev.cel.common.ast.CelExpr;
import dev.cel.common.ast.CelExpr.CelList;
import dev.cel.common.ast.CelExpr.ExprKind;
import dev.cel.common.types.CelKind;
import dev.cel.common.types.CelType;
import dev.cel.common.types.CelTypeProvider;
import dev.cel.common.types.TypeType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Internal translator from CEL AST to Z3 SMT expressions using a 4-valued logic tagged union.
 *
 * <p><b>Lifetime & Mutability:</b> This translator is stateful and highly mutable. It accumulates
 * constraints and symbol bindings in a shared {@link CelZ3TranslationContext}, and literal caches
 * in itself and its delegate translators.
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
  private static final String FUNCTION_PREFIX = "!fn_";
  private final Context ctx;
  private final CelZ3TypeSystem typeSystem;
  private final CelZ3TranslationContext context;
  private final CelZ3TypeConstraintGenerator typeConstraintGenerator;
  private final CelZ3OperatorTranslator operatorTranslator;
  private final CelZ3ComprehensionTranslator comprehensionTranslator;
  private final CelZ3StructTranslator structTranslator;
  private final ImmutableSet<String> unknownIdentifiers;
  private final Map<Object, Expr<?>> listLiteralCache;

  /**
   * Returns a set of Z3 boolean expressions representing type constraints for the translated AST.
   */
  Set<BoolExpr> getTypeConstraints() {
    return context.constraints();
  }

  BoolExpr hasTruncation() {
    return context.hasTruncation();
  }

  CelZ3TypeSystem getTypeSystem() {
    return typeSystem;
  }

  /** Returns the input variables, in order of first appearance. */
  Set<Expr<?>> getInputVariables() {
    return context.inputVariables();
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
    context.bindSymbol(varName, value);
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

    for (Expr<?> constraint : context.constraints()) {
      collectReferences(constraint, visited, listRefs, mapRefs, msgRefs);
    }
    collectReferences(result.z3Expr(), visited, listRefs, mapRefs, msgRefs);

    CelZ3ExtensionalityAxioms.generateAxioms(ctx, typeSystem, listRefs, mapRefs, msgRefs)
        .forEach(context::addConstraint);

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
        return structTranslator.translateSelect(celExpr, ast);
      case CALL:
        return translateCall(celExpr, ast);
      case LIST:
        return translateList(celExpr, ast);
      case MAP:
        return translateMap(celExpr, ast);
      case STRUCT:
        return structTranslator.translateStruct(celExpr, ast);
      case COMPREHENSION:
        return comprehensionTranslator.translateComprehension(celExpr, ast);
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
        context.computeSymbolIfAbsent(
            name,
            n -> {
              Expr<?> v = ctx.mkConst(n, typeSystem.celValueSort());
              context.addInputVariable(v, type);

              // Variables at rest can never be pre-cooked Errors
              // Basically prevents error being a counterexample of var == var
              context.addConstraint(ctx.mkNot(typeSystem.isError(v)));

              BoolExpr constraint = typeConstraintGenerator.createTypeConstraint(v, exprId, ast);
              if (unknownIdentifiers.contains(name)) {
                context.addConstraint(ctx.mkOr(typeSystem.isUnknown(v), constraint));
              } else {
                context.addConstraint(ctx.mkNot(typeSystem.isUnknown(v)));
                context.addConstraint(constraint);
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
      List<Expr<?>> values = new ArrayList<>();
      ImmutableList<Integer> optionalIndices = createList.optionalIndices();
      ImmutableList<CelExpr> elements = createList.elements();
      for (int i = 0; i < elements.size(); i++) {
        CelExpr element = elements.get(i);
        TranslatedValue elem = translateExpr(element, ast);

        if (optionalIndices.contains(i)) {
          Expr<?> checkedValue =
              typeSystem.withRuntimeError(
                  elem.z3Expr(), ctx.mkNot(typeSystem.isOptional(elem.z3Expr())));
          elem = TranslatedValue.create(checkedValue, element, typeSystem, elem.isApproximate());

          Expr<?> optRef = typeSystem.getOptionalRef(elem.z3Expr());
          Expr<?> value = typeSystem.getOptionalValue(optRef);
          seq =
              (SeqExpr)
                  ctx.mkITE(
                      typeSystem.optHasValue(optRef),
                      typeSystem.mkConcatSafe(seq, ctx.mkUnit(value)),
                      seq);
          values.add(value);
        } else {
          seq = typeSystem.mkConcatSafe(seq, ctx.mkUnit(elem.z3Expr()));
          values.add(elem.z3Expr());
        }
        elementsTv.add(elem);
      }
      listRef = typeSystem.mkListRefConst(LIST_REF_PREFIX);
      context.addConstraint(ctx.mkEq(typeSystem.getSeq(listRef), seq));
      propagateTruncatedTypeConstraint(typeSystem.wrapList(listRef), values);
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
    List<Expr<?>> values = new ArrayList<>();

    for (CelExpr.CelMap.Entry entryAst : createMap.entries()) {
      TranslatedValue keyTv = translateExpr(entryAst.key(), ast);
      Expr<?> key = keyTv.z3Expr();
      elementsTv.add(keyTv);
      TranslatedValue valueTv = translateExpr(entryAst.value(), ast);
      Expr<?> value = valueTv.z3Expr();

      Expr<?> finalValue = value;
      BoolExpr finalPresence = ctx.mkTrue();
      if (entryAst.optionalEntry()) {
        Expr<?> checkedValue =
            typeSystem.withRuntimeError(value, ctx.mkNot(typeSystem.isOptional(value)));
        valueTv =
            TranslatedValue.create(
                checkedValue, entryAst.value(), typeSystem, valueTv.isApproximate());
        Expr<?> optRef = typeSystem.getOptionalRef(checkedValue);
        finalPresence = typeSystem.optHasValue(optRef);
        finalValue = typeSystem.getOptionalValue(optRef);
      }
      elementsTv.add(valueTv);
      values.add(finalValue);

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

    context.addConstraint(ctx.mkEq(typeSystem.getMapValues(mapRef), mapValues));
    context.addConstraint(ctx.mkEq(typeSystem.getMapPresence(mapRef), mapPresence));
    context.addConstraint(ctx.mkEq(typeSystem.getMapKeys(mapRef), keysSeq));

    Expr<?> result = typeSystem.wrapMap(mapRef);
    propagateTruncatedTypeConstraint(result, values);
    return TranslatedValue.propagateStrict(ctx, typeSystem, result, celExpr, elementsTv);
  }

  /**
   * Marks {@code container} as having a truncated type constraint if any of {@code elements} has
   * one, unless it is an error or unknown: those are shared terms that hold no elements.
   */
  private void propagateTruncatedTypeConstraint(Expr<?> container, Iterable<Expr<?>> elements) {
    BoolExpr hasTruncated =
        ctx.mkOr(
            typeSystem.isErrorOrUnknown(container),
            typeSystem.hasTruncatedTypeConstraint(container));
    for (Expr<?> element : elements) {
      context.addConstraint(
          ctx.mkImplies(typeSystem.hasTruncatedTypeConstraint(element), hasTruncated));
    }
  }

  private TranslatedValue translateBlock(
      List<CelExpr> boundExprs, int currentIndex, CelExpr resultExpr, CelAbstractSyntaxTree ast) {
    if (currentIndex >= boundExprs.size()) {
      return translateExpr(resultExpr, ast);
    }

    CelExpr subExprAst = boundExprs.get(currentIndex);
    TranslatedValue subExprVal = translateExpr(subExprAst, ast);
    String varName = CelBlock.INDEX_PREFIX + currentIndex;

    return context.withScope(
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
        .map(
            tv -> {
              if (mayEmbedArgs(functionName, ast.getTypeOrThrow(exprId))) {
                propagateTruncatedTypeConstraint(
                    tv.z3Expr(), Iterables.transform(args, TranslatedValue::z3Expr));
              }
              return TranslatedValue.create(tv.z3Expr(), expr, typeSystem, tv.isApproximate());
            })
        .orElseGet(
            () -> {
              // Uninterpreted function
              Sort[] argSorts = new Sort[args.size()];
              Arrays.fill(argSorts, 0, args.size(), typeSystem.celValueSort());
              FuncDecl<?> funcDecl =
                  typeSystem.internFuncDecl(
                      uninterpretedFunctionSymbol(exprId, ast),
                      argSorts,
                      typeSystem.celValueSort());

              Expr<?>[] exprArgs = args.stream().map(TranslatedValue::z3Expr).toArray(Expr[]::new);
              Expr<?> callRes = ctx.mkApp(funcDecl, exprArgs);
              context.addConstraint(
                  typeConstraintGenerator.createTypeConstraint(callRes, exprId, ast));
              context.addConstraint(ctx.mkNot(typeSystem.isUnknown(callRes)));
              context.addConstraint(ctx.mkNot(typeSystem.isError(callRes)));

              // The solver picks the result freely, so a model depending on it is never exact.
              return TranslatedValue.propagateStrict(
                  ctx,
                  typeSystem,
                  functionName,
                  callRes,
                  Optional.of(expr),
                  /* baseTaint= */ ctx.mkTrue(),
                  args);
            });
  }

  /**
   * Returns the Z3 symbol for an uninterpreted call. Each set of candidate overloads gets its own
   * symbol, since distinct overloads may have different implementations even on equal values (e.g.
   * empty lists of different element types). The prefix keeps it apart from variables and internal
   * symbols.
   */
  private static String uninterpretedFunctionSymbol(long exprId, CelAbstractSyntaxTree ast) {
    ImmutableList<String> overloadIds = ast.getReferenceOrThrow(exprId).overloadIds();
    return FUNCTION_PREFIX + String.join("|", ImmutableSortedSet.copyOf(overloadIds));
  }

  /**
   * Whether a call may construct a container that embeds its args (e.g. list concatenation, map
   * insertion or optional.of). Indexing and conditionals return existing values instead, so
   * propagating to them would needlessly taint e.g. an element whenever its container has a
   * truncated type constraint.
   */
  private static boolean mayEmbedArgs(String functionName, CelType resultType) {
    return (!CelZ3OperatorTranslator.isStaticallyKnown(resultType)
            || !resultType.parameters().isEmpty())
        && !functionName.equals(Operator.INDEX.getFunction())
        && !functionName.equals(Operator.CONDITIONAL.getFunction());
  }

  private Optional<Object> toCacheKey(CelExpr expr) {
    switch (expr.exprKind().getKind()) {
      case CONSTANT:
        return Optional.of(expr.constant());
      case LIST:
        if (!expr.list().optionalIndices().isEmpty()) {
          // Do not cache lists with optional elements. Optional elements conditionally alter
          // sequence length and presence via ITE branches at runtime; caching would collide
          // [1, 2] with [?1, 2] and freeze conditional evaluations to a static reference.
          return Optional.empty();
        }
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

  CelAstToZ3Translator(
      Context ctx,
      int comprehensionUnrollLimit,
      ImmutableSet<String> unknownIdentifiers,
      CelZ3FunctionRegistry functionRegistry,
      CelTypeProvider typeProvider,
      boolean parameterizeUnknowns) {
    this.ctx = ctx;
    this.typeSystem = new CelZ3TypeSystem(ctx, parameterizeUnknowns);
    this.context = new CelZ3TranslationContext(ctx, typeSystem, comprehensionUnrollLimit);
    this.typeConstraintGenerator = context.typeConstraintGenerator();
    this.operatorTranslator = new CelZ3OperatorTranslator(context, functionRegistry);
    this.comprehensionTranslator = new CelZ3ComprehensionTranslator(context, this::translateExpr);
    this.structTranslator = new CelZ3StructTranslator(context, typeProvider, this::translateExpr);
    this.unknownIdentifiers = unknownIdentifiers;
    this.listLiteralCache = new HashMap<>();
  }
}
