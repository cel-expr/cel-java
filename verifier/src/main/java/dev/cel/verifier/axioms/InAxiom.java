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

import com.microsoft.z3.Expr;
import dev.cel.checker.CelStandardDeclarations.StandardFunction;
import java.util.Optional;

/** Axiomatization for CEL's in operator/function (list and map overloads). */
@SuppressWarnings({"unchecked", "rawtypes"}) // Z3 Java API uses raw types.
final class InAxiom {

  static final CelZ3FunctionAxiom INSTANCE =
      CelZ3FunctionAxiom.newBuilder(StandardFunction.IN.functionDecl())
          // Matches by terms, which is CEL equality when lhs has the list's element type and that
          // type compares by terms; the operator translator compares other lists by CEL equality.
          .addBinaryOverloadTranslator(
              StandardFunction.Overload.InternalOperator.IN_LIST.celOverloadDecl(),
              (ctx, typeSystem, constraintSink, lhsTrans, rhsTrans) ->
                  Optional.of(
                      typeSystem.wrapBool(
                          ctx.mkContains(
                              (Expr) typeSystem.getSeq(typeSystem.getListRef(rhsTrans)),
                              (Expr) ctx.mkUnit(lhsTrans)))))
          .addBinaryOverloadTranslator(
              StandardFunction.Overload.InternalOperator.IN_MAP.celOverloadDecl(),
              (ctx, typeSystem, constraintSink, lhsTrans, rhsTrans) ->
                  Optional.of(typeSystem.wrapBool(typeSystem.hasEqualKey(rhsTrans, lhsTrans))))
          .build();

  private InAxiom() {}
}
