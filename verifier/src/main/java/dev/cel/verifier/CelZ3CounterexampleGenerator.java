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

import com.google.common.base.Preconditions;
import com.microsoft.z3.ArrayExpr;
import com.microsoft.z3.Context;
import com.microsoft.z3.Expr;
import com.microsoft.z3.FPNum;
import com.microsoft.z3.FuncDecl;
import com.microsoft.z3.IntNum;
import com.microsoft.z3.Model;
import com.microsoft.z3.RatNum;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Generates human-readable counterexample strings from Z3 models. */
@SuppressWarnings({"unchecked", "rawtypes"}) // Z3 Java API uses raw types.
final class CelZ3CounterexampleGenerator {

  private static final int MAX_LIST_ELEMENTS_TO_PRINT = 15;

  private CelZ3CounterexampleGenerator() {}

  static String generate(
      Context ctx,
      CelZ3TypeSystem typeSystem,
      Model model,
      boolean isApproximate,
      boolean isCounterexample) {
    FuncDecl[] constDecls = model.getConstDecls();

    List<String> bindings = new ArrayList<>();
    for (FuncDecl decl : constDecls) {
      String name = decl.getName().toString();
      // Filter out internal solver-generated Skolem constants (e.g., k!1, seq.empty!0).
      // `!` is not a valid CEL identifier.
      if (name.contains("!")) {
        continue;
      }
      Expr<?> constInterp = model.getConstInterp(decl);
      if (constInterp != null) {
        bindings.add(
            String.format("\n  %s = %s", name, formatExpr(ctx, typeSystem, model, constInterp)));
      }
    }

    if (bindings.isEmpty()) {
      return isCounterexample
          ? " (The expression fails unconditionally, regardless of input state)"
          : " (The expression is satisfiable unconditionally, regardless of input state)";
    }

    String prefix;
    if (isCounterexample) {
      prefix = isApproximate ? " Potential counterexample input:" : " Counterexample input:";
    } else {
      prefix = isApproximate ? " Potential satisfying input:" : " Satisfying input:";
    }
    return prefix + String.join("", bindings);
  }

  private static String formatExpr(
      Context ctx, CelZ3TypeSystem typeSystem, Model model, @Nullable Expr<?> expr) {
    Preconditions.checkState(expr != null, "Z3 failed to evaluate the expression natively.");

    FuncDecl decl = expr.getFuncDecl();

    // Handle CelType constructors wrapper unwrapping
    if (decl.equals(typeSystem.intCons().ConstructorDecl())) {
      return formatExpr(ctx, typeSystem, model, expr.getArgs()[0]);
    } else if (decl.equals(typeSystem.uintCons().ConstructorDecl())) {
      return formatExpr(ctx, typeSystem, model, expr.getArgs()[0]) + "u";
    } else if (decl.equals(typeSystem.boolCons().ConstructorDecl())) {
      return formatExpr(ctx, typeSystem, model, expr.getArgs()[0]);
    } else if (decl.equals(typeSystem.stringCons().ConstructorDecl())) {
      return expr.getArgs()[0].toString();
    } else if (decl.equals(typeSystem.bytesCons().ConstructorDecl())) {
      return "b" + expr.getArgs()[0];
    } else if (decl.equals(typeSystem.doubleCons().ConstructorDecl())) {
      Expr<?> doubleArg = expr.getArgs()[0];
      if (doubleArg instanceof FPNum) {
        FPNum fpNum = (FPNum) doubleArg;
        if (fpNum.isNaN()) {
          return "NaN";
        }
        if (fpNum.isInf()) {
          return fpNum.isNegative() ? "-Infinity" : "Infinity";
        }
        if (fpNum.isZero()) {
          return fpNum.isNegative() ? "-0.0" : "0.0";
        }
        Expr<?> realExpr = ctx.mkFPToReal(fpNum).simplify();
        if (realExpr instanceof RatNum) {
          RatNum ratNum = (RatNum) realExpr;
          double val =
              ratNum.getBigIntNumerator().doubleValue()
                  / ratNum.getBigIntDenominator().doubleValue();
          return Double.toString(val);
        }
      }
      return doubleArg.toString();
    } else if (decl.equals(typeSystem.listCons().ConstructorDecl())) {
      return reconstructList(ctx, typeSystem, model, expr.getArgs()[0]);
    } else if (decl.equals(typeSystem.mapCons().ConstructorDecl())) {
      return reconstructMap(ctx, typeSystem, model, expr.getArgs()[0]);
    } else if (decl.equals(typeSystem.messageCons().ConstructorDecl())) {
      return reconstructMessage(ctx, typeSystem, model, expr.getArgs()[0]);
    } else if (decl.equals(typeSystem.errorCons().ConstructorDecl())) {
      return "Error";
    } else if (decl.equals(typeSystem.unknownCons().ConstructorDecl())) {
      return "Unknown";
    }

    return expr.toString();
  }

  private static String reconstructList(
      Context ctx, CelZ3TypeSystem typeSystem, Model model, Expr<?> listRef) {
    Expr<?> lenExpr =
        evaluateStrict(
            model,
            ctx.mkLength(typeSystem.getSeq(listRef)),
            String.format("Z3 failed to evaluate length for list %s", listRef));
    int length = ((IntNum) lenExpr).getInt();
    int printLimit = Math.min(length, MAX_LIST_ELEMENTS_TO_PRINT);
    List<String> elements = new ArrayList<>();
    for (int i = 0; i < printLimit; i++) {
      Expr<?> elem =
          evaluateStrict(
              model,
              ctx.mkNth(typeSystem.getSeq(listRef), ctx.mkInt(i)),
              String.format(
                  "Z3 failed to evaluate list element at index %d for list %s", i, listRef));
      elements.add(formatExpr(ctx, typeSystem, model, elem));
    }
    if (length > printLimit) {
      elements.add("... (" + (length - printLimit) + " more elements)");
    }

    return "[" + String.join(", ", elements) + "]";
  }

  private static String reconstructMap(
      Context ctx, CelZ3TypeSystem typeSystem, Model model, Expr<?> mapRef) {
    Expr<?> presenceArray =
        evaluateStrict(
            model,
            typeSystem.getMapPresence(mapRef),
            String.format("Z3 failed to evaluate presence array natively for map %s", mapRef));

    List<Expr<?>> keys = new ArrayList<>();
    extractKeys(presenceArray, keys);

    List<String> entries = new ArrayList<>();
    for (Expr<?> key : keys) {
      Expr<?> presence =
          evaluateStrict(
              model,
              ctx.mkSelect((ArrayExpr) typeSystem.getMapPresence(mapRef), key),
              String.format(
                  "Z3 failed to evaluate map presence for key %s in map %s", key, mapRef));

      if (presence.isTrue()) {
        Expr<?> value =
            evaluateStrict(
                model,
                ctx.mkSelect((ArrayExpr) typeSystem.getMapValues(mapRef), key),
                String.format("Z3 failed to evaluate map value for key %s in map %s", key, mapRef));
        entries.add(
            formatExpr(ctx, typeSystem, model, key)
                + ": "
                + formatExpr(ctx, typeSystem, model, value));
      }
    }

    return "{" + String.join(", ", entries) + "}";
  }

  private static String reconstructMessage(
      Context ctx, CelZ3TypeSystem typeSystem, Model model, Expr<?> msgRef) {
    Expr<?> valuesArray =
        evaluateStrict(
            model,
            typeSystem.getMsgValues(msgRef),
            String.format("Z3 failed to evaluate values array natively for msg %s", msgRef));

    Expr<?> typeNameExpr =
        evaluateStrict(
            model,
            typeSystem.getMsgTypeName(msgRef),
            String.format("Z3 failed to evaluate type name natively for msg %s", msgRef));

    String typeName = formatExpr(ctx, typeSystem, model, typeNameExpr).replace("\"", "");

    List<Expr<?>> keys = new ArrayList<>();
    extractKeys(valuesArray, keys);

    List<String> entries = new ArrayList<>();
    for (Expr<?> key : keys) {
      Expr<?> presence =
          evaluateStrict(
              model,
              ctx.mkSelect((ArrayExpr) typeSystem.getMsgPresence(msgRef), key),
              String.format(
                  "Z3 failed to evaluate msg presence for key %s in msg %s", key, msgRef));

      if (presence.isTrue()) {
        Expr<?> value =
            evaluateStrict(
                model,
                ctx.mkSelect((ArrayExpr) typeSystem.getMsgValues(msgRef), key),
                String.format("Z3 failed to evaluate msg value for key %s in msg %s", key, msgRef));

        String fieldName = formatExpr(ctx, typeSystem, model, key).replace("\"", "");
        entries.add(fieldName + ": " + formatExpr(ctx, typeSystem, model, value));
      }
    }

    return typeName + "{" + String.join(", ", entries) + "}";
  }

  private static void extractKeys(Expr<?> arrayExpr, List<Expr<?>> keys) {
    int iterations = 0;
    while (true) {
      if (++iterations > 100_000) {
        throw new IllegalStateException("Exceeded maximum number of extractKeys iterations.");
      }
      if (!arrayExpr.isApp()) {
        break;
      }
      FuncDecl<?> decl = arrayExpr.getFuncDecl();
      String declName = decl.getName().toString();

      if (!declName.equals("store")) {
        break;
      }

      Expr<?>[] args = arrayExpr.getArgs();
      Preconditions.checkState(
          args.length == 3, "Z3 store array operation must have exactly 3 arguments");
      keys.add(args[1]);

      arrayExpr = args[0];
    }
  }

  private static Expr<?> evaluateStrict(Model model, Expr<?> expr, String errorMessage) {
    // There are no free variables remaining after the solver is ran, so the completion flag has
    // no effect.
    Expr<?> evaluated = model.evaluate(expr, /* completion= */ true);
    if (evaluated == null) {
      throw new IllegalStateException(errorMessage);
    }
    return evaluated;
  }
}
