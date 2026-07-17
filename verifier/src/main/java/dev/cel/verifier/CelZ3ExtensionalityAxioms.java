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
import com.microsoft.z3.BoolExpr;
import com.microsoft.z3.Context;
import com.microsoft.z3.Expr;
import com.microsoft.z3.FuncDecl;
import com.microsoft.z3.Sort;
import java.util.Set;

/**
 * Generates extensionality axioms for CEL Z3 reference types.
 *
 * <p>Z3 models collections and messages as references. Without these axioms, the solver performs a
 * shallow pointer equality check, which fails for identical but distinctly allocated collection
 * literals (e.g., [[1]] == [[1]]).
 *
 * <p>Uses O(N) ground instantiation with Uninterpreted Functions to avoid O(N^2) implication graphs
 * and E-matching pattern overhead.
 *
 * <p>Formal derivation of the extensionality property via ground uninterpreted function assertions:
 *
 * <ul>
 *   <li>Ground assertion: {@code f_ref(seq(R)) = R}
 *   <li>Congruence closure rule: {@code ∀X, Y : X = Y ⟹ f_ref(X) = f_ref(Y)}
 *   <li>Substituting {@code seq(R1)} and {@code seq(R2)}: {@code seq(R1) = seq(R2) ⟹ f_ref(seq(R1))
 *       = f_ref(seq(R2))}
 *   <li>Applying the ground assertion yields the extensionality property: {@code ∀R1, R2 : seq(R1)
 *       = seq(R2) ⟹ R1 = R2}
 * </ul>
 */
final class CelZ3ExtensionalityAxioms {

  private static final String FUNC_MK_LIST_REF = "!mkListRef";
  private static final String FUNC_MK_MAP_REF = "!mkMapRef";
  private static final String FUNC_MK_MSG_REF = "!mkMsgRef";

  static ImmutableList<BoolExpr> generateAxioms(
      Context ctx,
      CelZ3TypeSystem typeSystem,
      Set<Expr<?>> listRefs,
      Set<Expr<?>> mapRefs,
      Set<Expr<?>> msgRefs) {

    ImmutableList.Builder<BoolExpr> axioms = ImmutableList.builder();

    if (!listRefs.isEmpty()) {
      addListAxioms(ctx, typeSystem, listRefs, axioms);
    }
    if (!mapRefs.isEmpty()) {
      addMapAxioms(ctx, typeSystem, mapRefs, axioms);
    }
    if (!msgRefs.isEmpty()) {
      addMessageAxioms(ctx, typeSystem, msgRefs, axioms);
    }

    return axioms.build();
  }

  private static void addListAxioms(
      Context ctx,
      CelZ3TypeSystem typeSystem,
      Set<Expr<?>> refs,
      ImmutableList.Builder<BoolExpr> axioms) {

    Sort listRefSort = typeSystem.listRefSort();
    Sort seqSort = ctx.mkSeqSort(typeSystem.celValueSort());
    FuncDecl<?> mkListRef = ctx.mkFuncDecl(FUNC_MK_LIST_REF, new Sort[] {seqSort}, listRefSort);

    for (Expr<?> ref : refs) {
      if (isAppOf(ref, FUNC_MK_LIST_REF)) {
        continue;
      }
      Expr<?> ufApp = ctx.mkApp(mkListRef, typeSystem.getSeq(ref));
      // Ground assertion: f_list(seq(ref)) = ref
      BoolExpr axiom = ctx.mkEq(ufApp, ref);

      // Guard the axiom with type check if the reference is extracted from a CelValue
      if (isAppOf(ref, typeSystem.listCons().getAccessorDecls()[0])) {
        Expr<?> inner = ref.getArgs()[0];
        axiom = ctx.mkImplies(typeSystem.isList(inner), axiom);
      }
      axioms.add(axiom);
    }
  }

  private static void addMapAxioms(
      Context ctx,
      CelZ3TypeSystem typeSystem,
      Set<Expr<?>> refs,
      ImmutableList.Builder<BoolExpr> axioms) {

    Sort mapRefSort = typeSystem.mapRefSort();
    Sort valuesSort = ctx.mkArraySort(typeSystem.celValueSort(), typeSystem.celValueSort());
    Sort presenceSort = ctx.mkArraySort(typeSystem.celValueSort(), ctx.getBoolSort());

    FuncDecl<?> mkMapRef =
        ctx.mkFuncDecl(FUNC_MK_MAP_REF, new Sort[] {valuesSort, presenceSort}, mapRefSort);

    for (Expr<?> ref : refs) {
      if (isAppOf(ref, FUNC_MK_MAP_REF)) {
        continue;
      }
      Expr<?> ufApp =
          ctx.mkApp(mkMapRef, typeSystem.getMapValues(ref), typeSystem.getMapPresence(ref));
      // Ground assertion: f_map(values(ref), presence(ref)) = ref
      BoolExpr axiom = ctx.mkEq(ufApp, ref);

      // Guard the axiom with type check if the reference is extracted from a CelValue
      if (isAppOf(ref, typeSystem.mapCons().getAccessorDecls()[0])) {
        Expr<?> inner = ref.getArgs()[0];
        axiom = ctx.mkImplies(typeSystem.isMap(inner), axiom);
      }
      axioms.add(axiom);
    }
  }

  private static void addMessageAxioms(
      Context ctx,
      CelZ3TypeSystem typeSystem,
      Set<Expr<?>> refs,
      ImmutableList.Builder<BoolExpr> axioms) {

    Sort msgRefSort = typeSystem.messageRefSort();
    Sort typeNameSort = ctx.getStringSort();
    Sort valuesSort = ctx.mkArraySort(ctx.getStringSort(), typeSystem.celValueSort());
    Sort presenceSort = ctx.mkArraySort(ctx.getStringSort(), ctx.getBoolSort());

    FuncDecl<?> mkMsgRef =
        ctx.mkFuncDecl(
            FUNC_MK_MSG_REF, new Sort[] {typeNameSort, valuesSort, presenceSort}, msgRefSort);

    for (Expr<?> ref : refs) {
      if (isAppOf(ref, FUNC_MK_MSG_REF)) {
        continue;
      }
      Expr<?> ufApp =
          ctx.mkApp(
              mkMsgRef,
              typeSystem.getMsgTypeName(ref),
              typeSystem.getMsgValues(ref),
              typeSystem.getMsgPresence(ref));
      // Ground assertion: f_msg(typeName(ref), values(ref), presence(ref)) = ref
      BoolExpr axiom = ctx.mkEq(ufApp, ref);

      // Guard the axiom with type check if the reference is extracted from a CelValue
      if (isAppOf(ref, typeSystem.messageCons().getAccessorDecls()[0])) {
        Expr<?> inner = ref.getArgs()[0];
        axiom = ctx.mkImplies(typeSystem.isMessage(inner), axiom);
      }
      axioms.add(axiom);
    }
  }

  /**
   * Returns true if the expression is an application of the specified uninterpreted function.
   *
   * <p>We check this to avoid generating redundant or nested extensionality axioms for references
   * that are already constructed using the maker functions (e.g., {@code !mkListRef}). Congruence
   * closure natively handles equality for these constructed references, so additional axioms are
   * unnecessary and degrade solver performance.
   */
  private static boolean isAppOf(Expr<?> expr, String funcName) {
    return expr.isApp() && expr.getFuncDecl().getName().toString().equals(funcName);
  }

  /** Returns true if the expression is an application of the specified function declaration. */
  private static boolean isAppOf(Expr<?> expr, FuncDecl<?> funcDecl) {
    return expr.isApp() && expr.getFuncDecl().equals(funcDecl);
  }

  private CelZ3ExtensionalityAxioms() {}
}
