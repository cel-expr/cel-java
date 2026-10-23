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

import com.microsoft.z3.BoolExpr;
import com.microsoft.z3.Context;
import com.microsoft.z3.Expr;
import dev.cel.common.types.CelType;
import dev.cel.common.types.SimpleType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
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
  private final List<BoolExpr> truncationConditions;
  private final Map<String, TranslatedValue> symbolTable;

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

  /** Constrains {@code value} to be neither an error nor unknown whenever {@code guard} holds. */
  void constrainValidValue(BoolExpr guard, Expr<?> value) {
    addConstraint(ctx.mkImplies(guard, ctx.mkNot(typeSystem.isErrorOrUnknown(value))));
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
   * Whether {@code val} is a map with more keys than the unroll limit, beyond which its presence is
   * not linked to its keys.
   */
  BoolExpr isTruncatedMap(Expr<?> val) {
    return ctx.mkAnd(
        typeSystem.isMap(val),
        ctx.mkGt(
            ctx.mkLength(typeSystem.getMapKeys(typeSystem.getMapRef(val))),
            ctx.mkInt(comprehensionUnrollLimit)));
  }

  void addTruncationCondition(BoolExpr condition) {
    truncationConditions.add(condition);
  }

  BoolExpr hasTruncation() {
    return CelZ3Exprs.mkOrFlattened(ctx, truncationConditions);
  }

  void bindSymbol(String name, TranslatedValue value) {
    symbolTable.put(name, value);
  }

  Optional<TranslatedValue> lookupSymbol(String name) {
    return Optional.ofNullable(symbolTable.get(name));
  }

  TranslatedValue computeSymbolIfAbsent(
      String name, Function<String, TranslatedValue> mappingFunction) {
    return symbolTable.computeIfAbsent(name, mappingFunction);
  }

  /** Binds {@code varName} to {@code value} while running {@code action}, then restores it. */
  <T> T withScope(String varName, TranslatedValue value, Supplier<T> action) {
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

  CelZ3TranslationContext(Context ctx, CelZ3TypeSystem typeSystem, int comprehensionUnrollLimit) {
    this.ctx = ctx;
    this.typeSystem = typeSystem;
    this.typeConstraintGenerator =
        new CelZ3TypeConstraintGenerator(ctx, typeSystem, comprehensionUnrollLimit);
    this.comprehensionUnrollLimit = comprehensionUnrollLimit;
    this.constraints = new LinkedHashSet<>();
    this.inputVariableTypes = new LinkedHashMap<>();
    this.truncationConditions = new ArrayList<>();
    this.symbolTable = new HashMap<>();
  }
}
