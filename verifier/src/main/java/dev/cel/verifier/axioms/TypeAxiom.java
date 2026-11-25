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
import com.microsoft.z3.Context;
import com.microsoft.z3.Expr;
import dev.cel.checker.CelStandardDeclarations.StandardFunction;
import dev.cel.common.types.ListType;
import dev.cel.common.types.MapType;
import dev.cel.common.types.OptionalType;
import dev.cel.common.types.SimpleType;
import dev.cel.verifier.CelZ3Exprs;
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
                Expr<?> result = getTypeExpression(ctx, typeSystem, val);

                // An approximate argument may have a different kind (or be an error) at runtime.
                return Optional.of(CelZ3OverloadResult.create(result, argApproximations.get(0)));
              })
          .build();

  private static Expr<?> getTypeExpression(Context ctx, CelZ3TypeSystem typeSystem, Expr<?> val) {
    return CelZ3Exprs.SwitchBuilder.newBuilder(ctx)
        .addCase(
            typeSystem.isStruct(val),
            typeSystem.wrapType(typeSystem.getMsgTypeName(typeSystem.getMessageRef(val))))
        .addCase(typeSystem.isOptional(val), typeSystem.mkType(OptionalType.NAME))
        .addCase(typeSystem.isNull(val), typeSystem.mkType(SimpleType.NULL_TYPE.name()))
        .addCase(typeSystem.isTimestamp(val), typeSystem.mkType(SimpleType.TIMESTAMP.name()))
        .addCase(typeSystem.isDuration(val), typeSystem.mkType(SimpleType.DURATION.name()))
        .addCase(typeSystem.isMap(val), typeSystem.mkType(TYPE_NAME_MAP))
        .addCase(typeSystem.isList(val), typeSystem.mkType(TYPE_NAME_LIST))
        .addCase(typeSystem.isBytes(val), typeSystem.mkType(SimpleType.BYTES.name()))
        .addCase(typeSystem.isString(val), typeSystem.mkType(SimpleType.STRING.name()))
        .addCase(typeSystem.isDouble(val), typeSystem.mkType(SimpleType.DOUBLE.name()))
        .addCase(typeSystem.isUint(val), typeSystem.mkType(SimpleType.UINT.name()))
        .addCase(typeSystem.isInt(val), typeSystem.mkType(SimpleType.INT.name()))
        .addCase(typeSystem.isBool(val), typeSystem.mkType(SimpleType.BOOL.name()))
        .addCase(typeSystem.isType(val), typeSystem.mkType("type"))
        .build(typeSystem.mkError());
  }

  private TypeAxiom() {}
}
