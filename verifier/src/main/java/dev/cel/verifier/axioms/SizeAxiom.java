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

import com.microsoft.z3.BoolExpr;
import com.microsoft.z3.Context;
import com.microsoft.z3.Expr;
import com.microsoft.z3.IntExpr;
import com.microsoft.z3.SeqExpr;
import dev.cel.checker.CelStandardDeclarations.StandardFunction;
import dev.cel.checker.CelStandardDeclarations.StandardFunction.Overload.Size;
import dev.cel.verifier.CelZ3TypeSystem;
import java.util.Optional;
import java.util.function.Consumer;

/** Axiomatization for CEL's size operator/function. */
@SuppressWarnings({"unchecked", "rawtypes"}) // Z3 Java API uses raw types.
final class SizeAxiom {

  private static final int MAX_CONTAINER_SIZE = Integer.MAX_VALUE;

  static final CelZ3FunctionAxiom INSTANCE =
      CelZ3FunctionAxiom.newBuilder(StandardFunction.SIZE.functionDecl())
          .addUnaryOverloadTranslator(
              Size.SIZE_STRING.celOverloadDecl(),
              (ctx, ts, sink, val) -> buildBoundedLength(ts.getString(val), ctx, ts, sink))
          .addUnaryOverloadTranslator(
              Size.STRING_SIZE.celOverloadDecl(),
              (ctx, ts, sink, val) -> buildBoundedLength(ts.getString(val), ctx, ts, sink))
          .addUnaryOverloadTranslator(
              Size.SIZE_BYTES.celOverloadDecl(),
              (ctx, ts, sink, val) -> buildBoundedLength(ts.getBytes(val), ctx, ts, sink))
          .addUnaryOverloadTranslator(
              Size.BYTES_SIZE.celOverloadDecl(),
              (ctx, ts, sink, val) -> buildBoundedLength(ts.getBytes(val), ctx, ts, sink))
          .addUnaryOverloadTranslator(
              Size.SIZE_LIST.celOverloadDecl(),
              (ctx, ts, sink, val) ->
                  buildBoundedLength(ts.getSeq(ts.getListRef(val)), ctx, ts, sink))
          .addUnaryOverloadTranslator(
              Size.LIST_SIZE.celOverloadDecl(),
              (ctx, ts, sink, val) ->
                  buildBoundedLength(ts.getSeq(ts.getListRef(val)), ctx, ts, sink))
          .addUnaryOverloadTranslator(
              Size.SIZE_MAP.celOverloadDecl(),
              (ctx, ts, sink, val) ->
                  buildBoundedLength(ts.getMapKeys(ts.getMapRef(val)), ctx, ts, sink))
          .addUnaryOverloadTranslator(
              Size.MAP_SIZE.celOverloadDecl(),
              (ctx, ts, sink, val) ->
                  buildBoundedLength(ts.getMapKeys(ts.getMapRef(val)), ctx, ts, sink))
          .build();

  private static Optional<Expr<?>> buildBoundedLength(
      Expr<?> seq, Context ctx, CelZ3TypeSystem typeSystem, Consumer<BoolExpr> constraintSink) {
    IntExpr length = ctx.mkLength((SeqExpr) seq);
    constraintSink.accept(ctx.mkLe(length, ctx.mkInt(MAX_CONTAINER_SIZE)));
    return Optional.of(typeSystem.wrapInt(length));
  }

  private SizeAxiom() {}
}
