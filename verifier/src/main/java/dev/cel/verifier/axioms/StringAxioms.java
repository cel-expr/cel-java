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
// See the License for the applicable language governing permissions and
// limitations under the License.

package dev.cel.verifier.axioms;

import com.google.common.collect.ImmutableList;
import com.microsoft.z3.BoolExpr;
import com.microsoft.z3.Expr;
import dev.cel.checker.CelStandardDeclarations.StandardFunction;
import dev.cel.checker.CelStandardDeclarations.StandardFunction.Overload.StringMatchers;
import dev.cel.common.CelOverloadDecl;
import java.util.Optional;

/** Axiomatization for CEL's string functions. */
@SuppressWarnings({"unchecked", "rawtypes"}) // Z3 Java API uses raw types.
final class StringAxioms {

  static final ImmutableList<CelZ3FunctionAxiom> ALL_AXIOMS =
      ImmutableList.of(
          createBinaryAxiom(
              StandardFunction.CONTAINS,
              StringMatchers.CONTAINS_STRING.celOverloadDecl(),
              (ctx, ts, sink, str, substr) -> {
                BoolExpr res =
                    ctx.mkContains((Expr) ts.getString(str), (Expr) ts.getString(substr));
                return Optional.of(ts.wrapBool(res));
              }),
          createBinaryAxiom(
              StandardFunction.STARTS_WITH,
              StringMatchers.STARTS_WITH_STRING.celOverloadDecl(),
              (ctx, ts, sink, str, prefix) -> {
                BoolExpr res =
                    ctx.mkPrefixOf((Expr) ts.getString(prefix), (Expr) ts.getString(str));
                return Optional.of(ts.wrapBool(res));
              }),
          createBinaryAxiom(
              StandardFunction.ENDS_WITH,
              StringMatchers.ENDS_WITH_STRING.celOverloadDecl(),
              (ctx, ts, sink, str, suffix) -> {
                BoolExpr res =
                    ctx.mkSuffixOf((Expr) ts.getString(suffix), (Expr) ts.getString(str));
                return Optional.of(ts.wrapBool(res));
              }));

  private static CelZ3FunctionAxiom createBinaryAxiom(
      StandardFunction stdFunc,
      CelOverloadDecl overloadDecl,
      CelZ3FunctionAxiom.BinaryTranslator translator) {
    return CelZ3FunctionAxiom.newBuilder(stdFunc.functionDecl())
        .addBinaryOverloadTranslator(overloadDecl, translator)
        .build();
  }

  private StringAxioms() {}
}
