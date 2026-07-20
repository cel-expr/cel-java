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

import com.google.common.base.Preconditions;
import com.microsoft.z3.BoolExpr;
import com.microsoft.z3.Context;
import com.microsoft.z3.Expr;
import dev.cel.checker.CelStandardDeclarations.StandardFunction;
import dev.cel.common.types.ListType;
import dev.cel.common.types.MapType;
import dev.cel.common.types.OptionalType;
import dev.cel.common.types.SimpleType;
import dev.cel.verifier.CelZ3TypeSystem;
import java.util.Optional;

/** Axiomatization for CEL's type() function. */
final class TypeAxiom {

  private static final String TYPE_NAME_LIST = ListType.create().name();
  private static final String TYPE_NAME_MAP = MapType.create(SimpleType.DYN, SimpleType.DYN).name();

  static final CelZ3FunctionAxiom INSTANCE =
      CelZ3FunctionAxiom.newBuilder(StandardFunction.TYPE.functionDecl())
          .addOverloadTranslator(
              StandardFunction.Overload.InternalOperator.TYPE.celOverloadDecl(),
              (ctx, typeSystem, constraintSink, unwrappedArgs, argApproximations) -> {
                Preconditions.checkArgument(unwrappedArgs.size() == 1);
                Preconditions.checkArgument(argApproximations.size() == 1);

                Expr<?> val = unwrappedArgs.get(0);
                BoolExpr argApprox = argApproximations.get(0);

                Expr<?> result = getTypeExpression(ctx, typeSystem, val);

                // Custom approximation logic for type(): it is only approximate if the argument
                // is approximate AND the argument is an Error or Unknown.
                BoolExpr isErrOrUnk = ctx.mkOr(typeSystem.isError(val), typeSystem.isUnknown(val));
                BoolExpr typeApprox = ctx.mkAnd(argApprox, isErrOrUnk);

                return Optional.of(CelZ3OverloadResult.create(result, typeApprox));
              })
          .build();

  private static Expr<?> getTypeExpression(Context ctx, CelZ3TypeSystem typeSystem, Expr<?> val) {
    return CelZ3TypeSystem.SwitchBuilder.newBuilder(ctx)
        .addCase(
            typeSystem.isStruct(val),
            typeSystem.wrapString(typeSystem.getMsgTypeName(typeSystem.getMessageRef(val))))
        .addCase(typeSystem.isOptional(val), typeSystem.mkString(OptionalType.NAME))
        .addCase(typeSystem.isNull(val), typeSystem.mkString(SimpleType.NULL_TYPE.name()))
        .addCase(typeSystem.isMap(val), typeSystem.mkString(TYPE_NAME_MAP))
        .addCase(typeSystem.isList(val), typeSystem.mkString(TYPE_NAME_LIST))
        .addCase(typeSystem.isBytes(val), typeSystem.mkString(SimpleType.BYTES.name()))
        .addCase(typeSystem.isString(val), typeSystem.mkString(SimpleType.STRING.name()))
        .addCase(typeSystem.isDouble(val), typeSystem.mkString(SimpleType.DOUBLE.name()))
        .addCase(typeSystem.isUint(val), typeSystem.mkString(SimpleType.UINT.name()))
        .addCase(typeSystem.isInt(val), typeSystem.mkString(SimpleType.INT.name()))
        .addCase(typeSystem.isBool(val), typeSystem.mkString(SimpleType.BOOL.name()))
        .build(typeSystem.mkError());
  }

  private TypeAxiom() {}
}
