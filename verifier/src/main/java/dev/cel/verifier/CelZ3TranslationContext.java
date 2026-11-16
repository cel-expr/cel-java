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
import com.google.common.collect.ImmutableMap;
import com.microsoft.z3.BoolExpr;
import com.microsoft.z3.Context;
import com.microsoft.z3.Expr;
import com.microsoft.z3.Model;
import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.Operator;
import dev.cel.common.ast.CelBlock;
import dev.cel.common.ast.CelExpr;
import dev.cel.common.types.CelKind;
import dev.cel.common.types.CelType;
import dev.cel.common.types.SimpleType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

/** State shared by the translators of a single verification query. */
final class CelZ3TranslationContext {

  private final Context ctx;
  private final CelZ3TypeSystem typeSystem;
  private final CelZ3TypeConstraintGenerator typeConstraintGenerator;
  private final int comprehensionUnrollLimit;
  private final Set<BoolExpr> constraints;
  private final Map<Expr<?>, CelType> inputVariableTypes;
  private final List<BoolExpr> unmodeledResults;
  private final Map<String, List<BoolExpr>> approximationTerms;
  private final Map<String, TranslatedValue> symbolTable;
  private final Map<String, TranslatedValue> localScope;
  private final IdentityHashMap<CelAbstractSyntaxTree, Set<Long>> narrowedExprIds;
  private final Set<String> narrowedSymbols;

  Context ctx() {
    return ctx;
  }

  CelZ3TypeSystem typeSystem() {
    return typeSystem;
  }

  CelZ3TypeConstraintGenerator typeConstraintGenerator() {
    return typeConstraintGenerator;
  }

  int comprehensionUnrollLimit() {
    return comprehensionUnrollLimit;
  }

  /** Returns the constraints asserted so far, in insertion order. */
  Set<BoolExpr> constraints() {
    return Collections.unmodifiableSet(constraints);
  }

  void addConstraint(BoolExpr constraint) {
    constraints.add(constraint);
  }

  /** Constrains {@code value} to be a CEL value whenever {@code guard} holds. */
  void constrainValidValue(BoolExpr guard, Expr<?> value) {
    addConstraint(ctx.mkImplies(guard, typeSystem.isValidValue(value)));
  }

  /** Returns an optional holding {@code value}. */
  Expr<?> mkOptionalOf(Expr<?> value) {
    Expr<?> optRef = ctx.mkApp(typeSystem.optionalOfRefFunc(), value);
    addConstraint(ctx.mkEq(typeSystem.getOptionalValue(optRef), value));
    addConstraint(typeSystem.optHasValue(optRef));
    return typeSystem.mkOptionalOf(optRef);
  }

  void addInputVariable(Expr<?> variable, CelType declaredType) {
    inputVariableTypes.put(variable, declaredType);
  }

  /** Returns the input variables, in order of first appearance. */
  Set<Expr<?>> inputVariables() {
    return Collections.unmodifiableSet(inputVariableTypes.keySet());
  }

  /** Returns the declared type of an input variable, or dyn for any other value. */
  CelType guaranteedType(Expr<?> value) {
    return inputVariableTypes.getOrDefault(value, SimpleType.DYN);
  }

  /**
   * Whether the type checker may have narrowed the static type of {@code expr} by unifying a type
   * parameter with dyn or null (e.g. {@code int_list + dyn_var} is typed list(int) but may hold
   * doubles).
   */
  boolean mayBeNarrowed(CelExpr expr, CelAbstractSyntaxTree ast) {
    return narrowedExprIds
        .computeIfAbsent(
            ast,
            a -> {
              Set<Long> ids = new HashSet<>();
              collectNarrowed(a.getExpr(), a, ImmutableMap.of(), ids);
              return ids;
            })
        .contains(expr.id());
  }

  /** The static type of {@code expr}, or dyn if the type checker may have narrowed it. */
  CelType trustedType(CelExpr expr, CelAbstractSyntaxTree ast) {
    return mayBeNarrowed(expr, ast)
        ? SimpleType.DYN
        : ast.getType(expr.id()).orElse(SimpleType.DYN);
  }

  // Adds expr to ids if it may be narrowed: a subexpression, or a variable it reads, is typed dyn
  // or null, or is narrowed. Returns whether expr is either.
  private boolean collectNarrowed(
      CelExpr expr, CelAbstractSyntaxTree ast, Map<String, Boolean> boundVars, Set<Long> ids) {
    boolean narrowed = false;
    switch (expr.getKind()) {
      case IDENT:
        String name = expr.ident().name();
        narrowed = boundVars.getOrDefault(name, narrowedSymbols.contains(name));
        break;
      case SELECT:
        narrowed = collectNarrowed(expr.select().operand(), ast, boundVars, ids);
        break;
      case CALL:
        CelExpr.CelCall call = expr.call();
        ImmutableList<CelExpr> args = call.args();
        if (call.function().equals(CelBlock.FUNCTION_NAME)) {
          // `cel.@block([e0, e1, ...], result)` binds @index0 to e0, and so on.
          Map<String, Boolean> scope = new HashMap<>(boundVars);
          ImmutableList<CelExpr> slots = args.get(0).list().elements();
          for (int i = 0; i < slots.size(); i++) {
            scope.put(CelBlock.INDEX_PREFIX + i, collectNarrowed(slots.get(i), ast, scope, ids));
          }
          narrowed = collectNarrowed(args.get(1), ast, scope, ids);
          break;
        }
        if (call.target().isPresent()) {
          narrowed = collectNarrowed(call.target().get(), ast, boundVars, ids);
        }
        // The field name of `e.?f` isn't typed.
        if (call.function().equals(Operator.OPTIONAL_SELECT.getFunction())) {
          args = args.subList(0, 1);
        }
        for (CelExpr arg : args) {
          narrowed |= collectNarrowed(arg, ast, boundVars, ids);
        }
        break;
      case LIST:
        for (CelExpr element : expr.list().elements()) {
          narrowed |= collectNarrowed(element, ast, boundVars, ids);
        }
        break;
      case MAP:
        for (CelExpr.CelMap.Entry entry : expr.map().entries()) {
          narrowed |= collectNarrowed(entry.key(), ast, boundVars, ids);
          narrowed |= collectNarrowed(entry.value(), ast, boundVars, ids);
        }
        break;
      case STRUCT:
        for (CelExpr.CelStruct.Entry entry : expr.struct().entries()) {
          narrowed |= collectNarrowed(entry.value(), ast, boundVars, ids);
        }
        break;
      case COMPREHENSION:
        // Its value is the result's, which reads the variables.
        CelExpr.CelComprehension comp = expr.comprehension();
        boolean range = collectNarrowed(comp.iterRange(), ast, boundVars, ids);
        Map<String, Boolean> scope = new HashMap<>(boundVars);
        scope.put(comp.iterVar(), range);
        scope.put(comp.iterVar2(), range);
        scope.put(comp.accuVar(), collectNarrowed(comp.accuInit(), ast, boundVars, ids));
        // The accumulator also holds what each step computes.
        if (collectNarrowed(comp.loopStep(), ast, scope, ids) && !scope.get(comp.accuVar())) {
          scope.put(comp.accuVar(), true);
          collectNarrowed(comp.loopStep(), ast, scope, ids);
        }
        collectNarrowed(comp.loopCondition(), ast, scope, ids);
        narrowed = collectNarrowed(comp.result(), ast, scope, ids);
        break;
      default:
        break;
    }
    if (narrowed) {
      ids.add(expr.id());
    }
    return narrowed || involvesDynOrNull(ast.getType(expr.id()).orElse(SimpleType.DYN));
  }

  private static boolean involvesDynOrNull(CelType type) {
    return type.kind().isDyn()
        || type.kind().isTypeParam()
        || type.kind() == CelKind.NULL_TYPE
        || type.parameters().stream().anyMatch(CelZ3TranslationContext::involvesDynOrNull);
  }

  /**
   * Whether {@code val} is a map with more keys than the unroll limit, beyond which its presence is
   * not linked to its keys.
   */
  BoolExpr isTruncatedMap(Expr<?> val) {
    return recordUnrollLimitApproximation(
        ctx.mkAnd(
            typeSystem.isMap(val),
            ctx.mkGt(
                ctx.mkLength(typeSystem.getMapKeys(typeSystem.getMapRef(val))),
                ctx.mkInt(comprehensionUnrollLimit))));
  }

  /**
   * Records {@code description} as a reason results are approximate where {@code condition} holds,
   * and returns the term to use for {@code condition}, so that it can be told apart in a taint.
   */
  BoolExpr recordApproximation(String description, BoolExpr condition) {
    BoolExpr term = condition;
    if (condition.isTrue()) {
      // A literal `true` is shared by every other unconditional approximation.
      term = (BoolExpr) ctx.mkFreshConst("!approx", ctx.getBoolSort());
      constraints.add(term);
    }
    approximationTerms.computeIfAbsent(description, k -> new ArrayList<>()).add(term);
    return term;
  }

  BoolExpr recordUnrollLimitApproximation(BoolExpr condition) {
    return recordApproximation(
        "lists and maps longer than the unroll limit (" + comprehensionUnrollLimit + ")",
        condition);
  }

  BoolExpr recordCallApproximation(String functionName, BoolExpr condition) {
    return recordApproximation("calls to `" + functionName + "`", condition);
  }

  /**
   * Returns the descriptions of the approximations that are enough on their own to make {@code
   * taint} hold in {@code model}, which leaves out those in short-circuited operands or untaken
   * branches.
   */
  ImmutableList<String> approximationSources(Model model, BoolExpr taint) {
    return approximationTerms.keySet().stream()
        .filter(description -> holds(model, withoutApproximation(taint, termsExcept(description))))
        .collect(toImmutableList());
  }

  private ImmutableList<BoolExpr> termsExcept(String description) {
    return approximationTerms.entrySet().stream()
        .filter(source -> !source.getKey().equals(description))
        .flatMap(source -> source.getValue().stream())
        .collect(toImmutableList());
  }

  BoolExpr withoutApproximation(BoolExpr expr, List<BoolExpr> terms) {
    BoolExpr[] falses = new BoolExpr[terms.size()];
    Arrays.fill(falses, ctx.mkFalse());
    return (BoolExpr) expr.substitute(terms.toArray(new BoolExpr[0]), falses);
  }

  private static boolean holds(Model model, BoolExpr condition) {
    return model.evaluate(condition, /* completion= */ true).isTrue();
  }

  /** Records where a result is an unknown standing in for a value the verifier doesn't compute. */
  void addUnmodeledResult(BoolExpr condition) {
    unmodeledResults.add(condition);
  }

  BoolExpr hasUnmodeledResult() {
    return CelZ3Exprs.mkOrFlattened(ctx, unmodeledResults);
  }

  /** Binds {@code name} to {@code value}, the result of {@code ast}. */
  void bindSymbol(String name, TranslatedValue value, CelAbstractSyntaxTree ast) {
    localScope.put(name, value);
    // Other expressions read it with its static type, which may be narrowed.
    if (mayBeNarrowed(ast.getExpr(), ast) || involvesDynOrNull(ast.getResultType())) {
      narrowedSymbols.add(name);
    }
  }

  Optional<TranslatedValue> lookupSymbol(String name) {
    return Optional.ofNullable(localScope.get(name));
  }

  TranslatedValue computeSymbolIfAbsent(
      String rawName, Function<String, TranslatedValue> mappingFunction) {
    if (!rawName.startsWith(".") && localScope.containsKey(rawName)) {
      return localScope.get(rawName);
    }
    return symbolTable.computeIfAbsent(
        rawName.startsWith(".") ? rawName.substring(1) : rawName, mappingFunction);
  }

  /** Binds {@code varName} to {@code value} while running {@code action}, then restores it. */
  <T> T withScope(String varName, TranslatedValue value, Supplier<T> action) {
    TranslatedValue prev = localScope.put(varName, value);
    try {
      return action.get();
    } finally {
      if (prev != null) {
        localScope.put(varName, prev);
      } else {
        localScope.remove(varName);
      }
    }
  }

  CelZ3TranslationContext(Context ctx, CelZ3TypeSystem typeSystem, int comprehensionUnrollLimit) {
    this.ctx = ctx;
    this.typeSystem = typeSystem;
    this.typeConstraintGenerator =
        new CelZ3TypeConstraintGenerator(ctx, typeSystem, comprehensionUnrollLimit);
    this.comprehensionUnrollLimit = comprehensionUnrollLimit;
    this.constraints = new LinkedHashSet<>();
    this.inputVariableTypes = new LinkedHashMap<>();
    this.unmodeledResults = new ArrayList<>();
    this.approximationTerms = new LinkedHashMap<>();
    this.symbolTable = new HashMap<>();
    this.localScope = new HashMap<>();
    this.narrowedExprIds = new IdentityHashMap<>();
    this.narrowedSymbols = new HashSet<>();
  }
}
