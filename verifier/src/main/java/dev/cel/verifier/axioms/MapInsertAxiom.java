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

package dev.cel.verifier.axioms;


import com.microsoft.z3.ArrayExpr;
import com.microsoft.z3.BoolExpr;
import com.microsoft.z3.Expr;
import com.microsoft.z3.SeqExpr;
import dev.cel.extensions.CelComprehensionsExtensions;
import java.util.Optional;

/** Axiomatization for CEL's cel.@mapInsert function. */
@SuppressWarnings({"unchecked", "rawtypes"}) // Z3 Java API uses raw types.
final class MapInsertAxiom {

  static final CelZ3FunctionAxiom INSTANCE =
      CelZ3FunctionAxiom.newBuilder(CelComprehensionsExtensions.Function.MAP_INSERT.functionDecl())
          .addTernaryOverloadTranslator(
              "cel_@mapInsert_map_key_value",
              (ctx, typeSystem, constraintSink, mapVal, keyVal, valueVal) -> {
                BoolExpr isMap = typeSystem.isMap(mapVal);
                BoolExpr isValidKey =
                    ctx.mkOr(
                        typeSystem.isString(keyVal),
                        typeSystem.isInt(keyVal),
                        typeSystem.isUint(keyVal),
                        typeSystem.isBool(keyVal));
                BoolExpr isMapAndValidKey = ctx.mkAnd(isMap, isValidKey);

                // CEL maps are immutable; we represent insertion by creating a new MapRef
                // constrained to the
                // updated data.
                Expr<?> newMapRef = typeSystem.mkMapRefConst("mapInsertRef");

                Expr<?> oldMapRef = typeSystem.getMapRef(mapVal);
                ArrayExpr oldValues = (ArrayExpr) typeSystem.getMapValues(oldMapRef);
                ArrayExpr oldPresence = (ArrayExpr) typeSystem.getMapPresence(oldMapRef);

                ArrayExpr newValues = ctx.mkStore(oldValues, keyVal, valueVal);
                ArrayExpr newPresence = ctx.mkStore(oldPresence, keyVal, ctx.mkTrue());

                BoolExpr keyAlreadyPresent = (BoolExpr) ctx.mkSelect(oldPresence, keyVal);
                SeqExpr<?> oldKeys = typeSystem.getMapKeys(oldMapRef);
                Expr<?> newKeys =
                    ctx.mkITE(
                        keyAlreadyPresent,
                        oldKeys,
                        typeSystem.mkConcatSafe(oldKeys, ctx.mkUnit(keyVal)));

                constraintSink.accept(
                    ctx.mkImplies(
                        isMapAndValidKey, ctx.mkEq(typeSystem.getMapValues(newMapRef), newValues)));
                constraintSink.accept(
                    ctx.mkImplies(
                        isMapAndValidKey,
                        ctx.mkEq(typeSystem.getMapPresence(newMapRef), newPresence)));
                constraintSink.accept(
                    ctx.mkImplies(
                        isMapAndValidKey, ctx.mkEq(typeSystem.getMapKeys(newMapRef), newKeys)));

                Expr<?> result =
                    ctx.mkITE(
                        isMapAndValidKey, typeSystem.wrapMap(newMapRef), typeSystem.mkError());
                return Optional.of(result);
              })
          .build();

  private MapInsertAxiom() {}
}
