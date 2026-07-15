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

import com.microsoft.z3.BoolExpr;
import com.microsoft.z3.Expr;
import com.microsoft.z3.FPExpr;
import com.microsoft.z3.FuncDecl;
import com.microsoft.z3.IntExpr;
import com.microsoft.z3.SeqExpr;
import com.microsoft.z3.Sort;
import dev.cel.checker.CelStandardDeclarations.StandardFunction;
import java.util.Optional;

/** Axiomatization for CEL's addition operator (+). */
final class AddAxiom {

  static final CelZ3FunctionAxiom INSTANCE =
      CelZ3FunctionAxiom.newBuilder(StandardFunction.ADD.functionDecl())
          .addBinaryOverloadTranslator(
              StandardFunction.Overload.Arithmetic.ADD_INT64.celOverloadDecl(),
              (ctx, ts, sink, l, r) -> {
                IntExpr a1 = ts.getInt(l);
                IntExpr a2 = ts.getInt(r);
                Expr<?> result = ts.wrapInt((IntExpr) ctx.mkAdd(a1, a2));
                BoolExpr overflow = ts.checkIntOverflow(ctx.mkAdd(a1, a2));
                return Optional.of(ts.withRuntimeError(result, overflow));
              })
          .addBinaryOverloadTranslator(
              StandardFunction.Overload.Arithmetic.ADD_UINT64.celOverloadDecl(),
              (ctx, ts, sink, l, r) -> {
                IntExpr a1 = ts.getUint(l);
                IntExpr a2 = ts.getUint(r);
                Expr<?> result = ts.wrapUint((IntExpr) ctx.mkAdd(a1, a2));
                BoolExpr overflow = ts.checkUintOverflow(ctx.mkAdd(a1, a2));
                return Optional.of(ts.withRuntimeError(result, overflow));
              })
          .addBinaryOverloadTranslator(
              StandardFunction.Overload.Arithmetic.ADD_DOUBLE.celOverloadDecl(),
              (ctx, ts, sink, l, r) ->
                  Optional.of(
                      ts.wrapDouble(
                          ctx.mkFPAdd(
                              ctx.mkFPRoundNearestTiesToEven(),
                              (FPExpr) ts.getDouble(l),
                              (FPExpr) ts.getDouble(r)))))
          .addBinaryOverloadTranslator(
              StandardFunction.Overload.Arithmetic.ADD_STRING.celOverloadDecl(),
              (ctx, ts, sink, l, r) ->
                  Optional.of(ts.wrapString(ts.mkConcatSafe(ts.getString(l), ts.getString(r)))))
          .addBinaryOverloadTranslator(
              StandardFunction.Overload.Arithmetic.ADD_BYTES.celOverloadDecl(),
              (ctx, ts, sink, l, r) ->
                  Optional.of(ts.wrapBytes(ts.mkConcatSafe(ts.getBytes(l), ts.getBytes(r)))))
          .addBinaryOverloadTranslator(
              StandardFunction.Overload.Arithmetic.ADD_LIST.celOverloadDecl(),
              (ctx, ts, sink, l, r) -> {
                Expr<?> listRef1 = ts.getListRef(l);
                Expr<?> listRef2 = ts.getListRef(r);

                FuncDecl<?> listAddFunc =
                    ts.internFuncDecl(
                        StandardFunction.Overload.Arithmetic.ADD_LIST
                            .celOverloadDecl()
                            .overloadId(),
                        new Sort[] {ts.listRefSort(), ts.listRefSort()},
                        ts.listRefSort());

                // The result of list_add(l1, l2) is a new list
                Expr<?> resultListRef = ctx.mkApp(listAddFunc, listRef1, listRef2);
                Expr<?> resultCelValue = ts.wrapList(resultListRef);

                SeqExpr<?> seq1 = ts.getSeq(listRef1);
                SeqExpr<?> seq2 = ts.getSeq(listRef2);
                SeqExpr<?> seqRes = ts.getSeq(resultListRef);

                // We add a constraint that the sequence representation of the result is the
                // concatenation of the sequence representation of l1 and l2.
                BoolExpr typeGuard = ctx.mkAnd(ts.isList(l), ts.isList(r));
                BoolExpr constraint = ctx.mkEq(seqRes, ts.mkConcatSafe(seq1, seq2));
                sink.accept(ctx.mkImplies(typeGuard, constraint));

                return Optional.of(resultCelValue);
              })
          .build();

  private AddAxiom() {}
}
