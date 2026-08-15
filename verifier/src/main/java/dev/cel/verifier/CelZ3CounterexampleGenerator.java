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
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.primitives.UnsignedLong;
import com.microsoft.z3.ArrayExpr;
import com.microsoft.z3.Context;
import com.microsoft.z3.Expr;
import com.microsoft.z3.FPNum;
import com.microsoft.z3.FuncDecl;
import com.microsoft.z3.IntNum;
import com.microsoft.z3.Model;
import com.microsoft.z3.RatNum;
import dev.cel.common.types.CelType;
import dev.cel.common.types.ListType;
import dev.cel.common.types.MapType;
import dev.cel.common.types.OptionalType;
import dev.cel.common.types.SimpleType;
import dev.cel.common.types.StructTypeReference;
import dev.cel.common.values.CelByteString;
import dev.cel.common.values.CelValueProvider;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/** Generates structured counterexamples and human-readable strings from Z3 models. */
@SuppressWarnings({"unchecked", "rawtypes"}) // Z3 Java API uses raw types.
final class CelZ3CounterexampleGenerator {

  private static final int MAX_ELEMENTS_TO_PRINT = 15;

  private CelZ3CounterexampleGenerator() {}

  static CelCounterexample extract(
      Context ctx,
      CelZ3TypeSystem typeSystem,
      CelValueProvider valueProvider,
      Model model,
      boolean isApproximate,
      boolean isSatisfyingInput) {
    FuncDecl[] constDecls = model.getConstDecls();

    ImmutableMap.Builder<String, CelCounterexample.Binding> bindingsBuilder =
        ImmutableMap.builder();
    List<String> bindingStrings = new ArrayList<>();
    for (FuncDecl decl : constDecls) {
      String name = decl.getName().toString();
      // Filter out internal solver-generated Skolem constants (e.g., k!1, seq.empty!0).
      // `!` is not a valid CEL identifier.
      if (name.contains("!")) {
        continue;
      }
      Expr<?> constInterp = model.getConstInterp(decl);
      if (constInterp != null) {
        ExtractedNode node = extractNode(ctx, typeSystem, valueProvider, model, constInterp);
        bindingsBuilder.put(
            name, CelCounterexample.Binding.of(name, node.type, node.nativeValue, node.celString));
        bindingStrings.add(String.format("\n  %s = %s", name, node.celString));
      }
    }

    ImmutableMap<String, CelCounterexample.Binding> bindings = bindingsBuilder.buildOrThrow();

    String displayString;
    if (bindings.isEmpty()) {
      displayString =
          isSatisfyingInput
              ? " (The expression is satisfiable unconditionally, regardless of input state)"
              : " (The expression fails unconditionally, regardless of input state)";
    } else {
      String prefix;
      if (isSatisfyingInput) {
        prefix = isApproximate ? " Potential satisfying input:" : " Satisfying input:";
      } else {
        prefix = isApproximate ? " Potential counterexample input:" : " Counterexample input:";
      }
      displayString = prefix + String.join("", bindingStrings);
    }

    return CelCounterexample.create(bindings, isApproximate, isSatisfyingInput, displayString);
  }

  static final class ExtractedNode {
    final CelType type;
    final @Nullable Object nativeValue;
    final String celString;

    ExtractedNode(CelType type, @Nullable Object nativeValue, String celString) {
      this.type = type;
      this.nativeValue = nativeValue;
      this.celString = celString;
    }
  }

  static ExtractedNode extractNode(
      Context ctx,
      CelZ3TypeSystem typeSystem,
      CelValueProvider valueProvider,
      Model model,
      Expr<?> expr) {
    Preconditions.checkNotNull(expr, "Z3 failed to evaluate the expression natively.");

    if (expr.getArgs().length == 0) {
      String consName = expr.getFuncDecl().getName().toString();
      if (consName.equals(CelZ3TypeSystem.CONS_NULL)) {
        return new ExtractedNode(SimpleType.NULL_TYPE, null, "null");
      }
      if (consName.equals(CelZ3TypeSystem.CONS_ERROR)) {
        return new ExtractedNode(SimpleType.ERROR, null, "Error");
      }
      if (expr.isString()) {
        String raw = expr.toString();
        return new ExtractedNode(SimpleType.STRING, unquoteZ3String(raw), raw);
      }
      if (expr.isTrue() || expr.isFalse()) {
        boolean val = expr.isTrue();
        return new ExtractedNode(SimpleType.BOOL, val, Boolean.toString(val));
      }
      if (expr instanceof IntNum) {
        long val = ((IntNum) expr).getInt64();
        return new ExtractedNode(SimpleType.INT, val, Long.toString(val));
      }
      if (expr instanceof FPNum || expr instanceof RatNum) {
        return decodeDouble(ctx, expr);
      }
      return new ExtractedNode(SimpleType.DYN, null, expr.toString());
    }

    String consName = expr.getFuncDecl().getName().toString();
    switch (consName) {
      case CelZ3TypeSystem.CONS_INT:
        long intVal = ((IntNum) expr.getArgs()[0]).getBigInteger().longValue();
        return new ExtractedNode(SimpleType.INT, intVal, Long.toString(intVal));
      case CelZ3TypeSystem.CONS_UINT:
        UnsignedLong uVal = UnsignedLong.valueOf(((IntNum) expr.getArgs()[0]).getBigInteger());
        return new ExtractedNode(SimpleType.UINT, uVal, uVal + "u");
      case CelZ3TypeSystem.CONS_BOOL:
        boolean boolVal = expr.getArgs()[0].isTrue();
        return new ExtractedNode(SimpleType.BOOL, boolVal, Boolean.toString(boolVal));
      case CelZ3TypeSystem.CONS_STRING:
        String strVal = expr.getArgs()[0].toString();
        return new ExtractedNode(SimpleType.STRING, unquoteZ3String(strVal), strVal);
      case CelZ3TypeSystem.CONS_BYTES:
        String byteVal = expr.getArgs()[0].toString();
        return new ExtractedNode(
            SimpleType.BYTES, CelByteString.copyFromUtf8(unquoteZ3String(byteVal)), "b" + byteVal);
      case CelZ3TypeSystem.CONS_DOUBLE:
        return decodeDouble(ctx, expr.getArgs()[0]);
      case CelZ3TypeSystem.CONS_TIMESTAMP:
        long tsSeconds = ((IntNum) expr.getArgs()[0]).getBigInteger().longValue();
        return new ExtractedNode(
            SimpleType.TIMESTAMP, Instant.ofEpochSecond(tsSeconds), "timestamp(" + tsSeconds + ")");
      case CelZ3TypeSystem.CONS_DURATION:
        long durSeconds = ((IntNum) expr.getArgs()[0]).getBigInteger().longValue();
        return new ExtractedNode(
            SimpleType.DURATION, Duration.ofSeconds(durSeconds), "duration('" + durSeconds + "s')");
      case CelZ3TypeSystem.CONS_LIST:
        return reconstructList(ctx, typeSystem, valueProvider, model, expr.getArgs()[0]);
      case CelZ3TypeSystem.CONS_MAP:
        return reconstructMap(ctx, typeSystem, valueProvider, model, expr.getArgs()[0]);
      case CelZ3TypeSystem.CONS_MESSAGE:
        return reconstructMessage(ctx, typeSystem, valueProvider, model, expr.getArgs()[0]);
      case CelZ3TypeSystem.CONS_OPTIONAL:
        return decodeOptional(ctx, typeSystem, valueProvider, model, expr.getArgs()[0]);
      case CelZ3TypeSystem.CONS_UNKNOWN:
        return new ExtractedNode(SimpleType.DYN, null, "Unknown");
      default:
        return new ExtractedNode(SimpleType.DYN, null, expr.toString());
    }
  }

  static String unquoteZ3String(String raw) {
    if (raw.startsWith("\"") && raw.endsWith("\"") && raw.length() >= 2) {
      return raw.substring(1, raw.length() - 1).replace("\"\"", "\"");
    }
    return raw;
  }

  static ExtractedNode decodeDouble(Context ctx, Expr<?> doubleArg) {
    if (doubleArg instanceof FPNum) {
      FPNum fpNum = (FPNum) doubleArg;
      if (fpNum.isNaN()) {
        return new ExtractedNode(SimpleType.DOUBLE, Double.NaN, "NaN");
      }
      if (fpNum.isInf()) {
        double val = fpNum.isNegative() ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
        return new ExtractedNode(
            SimpleType.DOUBLE, val, fpNum.isNegative() ? "-Infinity" : "Infinity");
      }
      if (fpNum.isZero()) {
        double val = fpNum.isNegative() ? -0.0 : 0.0;
        return new ExtractedNode(SimpleType.DOUBLE, val, fpNum.isNegative() ? "-0.0" : "0.0");
      }
      Expr<?> realExpr = ctx.mkFPToReal(fpNum).simplify();
      if (realExpr instanceof RatNum) {
        RatNum ratNum = (RatNum) realExpr;
        double val =
            ratNum.getBigIntNumerator().doubleValue() / ratNum.getBigIntDenominator().doubleValue();
        return new ExtractedNode(SimpleType.DOUBLE, val, Double.toString(val));
      }
    }
    return new ExtractedNode(SimpleType.DOUBLE, null, doubleArg.toString());
  }

  static ExtractedNode decodeOptional(
      Context ctx,
      CelZ3TypeSystem typeSystem,
      CelValueProvider valueProvider,
      Model model,
      Expr<?> optRef) {
    Expr<?> hasValueExpr =
        evaluateStrict(
            model,
            typeSystem.optHasValue(optRef),
            String.format("Z3 failed to evaluate optHasValue natively for %s", optRef));
    if (hasValueExpr.isTrue()) {
      Expr<?> valueExpr =
          evaluateStrict(
              model,
              typeSystem.getOptionalValue(optRef),
              String.format("Z3 failed to evaluate optValue natively for %s", optRef));
      ExtractedNode valueNode = extractNode(ctx, typeSystem, valueProvider, model, valueExpr);
      return new ExtractedNode(
          OptionalType.create(valueNode.type),
          Optional.ofNullable(valueNode.nativeValue),
          "optional(" + valueNode.celString + ")");
    }
    return new ExtractedNode(
        OptionalType.create(SimpleType.DYN), Optional.empty(), "optional.none()");
  }

  private static ExtractedNode reconstructList(
      Context ctx,
      CelZ3TypeSystem typeSystem,
      CelValueProvider valueProvider,
      Model model,
      Expr<?> listRef) {
    Expr<?> lenExpr =
        evaluateStrict(
            model,
            ctx.mkLength(typeSystem.getSeq(listRef)),
            String.format("Z3 failed to evaluate length for list %s", listRef));
    Preconditions.checkState(
        lenExpr instanceof IntNum, "Expected IntNum length for list %s, got %s", listRef, lenExpr);
    long length = ((IntNum) lenExpr).getInt64();
    int printLimit = (int) Math.min(length, (long) MAX_ELEMENTS_TO_PRINT);
    List<String> elementStrings = new ArrayList<>();
    ImmutableList.Builder<Object> nativeElements = ImmutableList.builder();
    CelType elemType = SimpleType.DYN;
    for (int i = 0; i < length; i++) {
      Expr<?> elem =
          evaluateStrict(
              model,
              ctx.mkNth(typeSystem.getSeq(listRef), ctx.mkInt(i)),
              String.format(
                  "Z3 failed to evaluate list element at index %d for list %s", i, listRef));
      ExtractedNode elemNode = extractNode(ctx, typeSystem, valueProvider, model, elem);
      if (elemNode.nativeValue != null) {
        nativeElements.add(elemNode.nativeValue);
      }
      if (i < printLimit) {
        elementStrings.add(elemNode.celString);
      }
      elemType = elemNode.type;
    }
    if (length > printLimit) {
      elementStrings.add("... (" + (length - printLimit) + " more elements)");
    }

    ImmutableList<Object> builtList = nativeElements.build();
    Object adaptedList = valueProvider.celValueConverter().toRuntimeValue(builtList);

    return new ExtractedNode(
        ListType.create(elemType), adaptedList, "[" + String.join(", ", elementStrings) + "]");
  }

  static ExtractedNode reconstructMap(
      Context ctx,
      CelZ3TypeSystem typeSystem,
      CelValueProvider valueProvider,
      Model model,
      Expr<?> mapRef) {
    Expr<?> lenExpr =
        evaluateStrict(
            model,
            ctx.mkLength(typeSystem.getMapKeys(mapRef)),
            String.format("Z3 failed to evaluate length for map %s", mapRef));
    Preconditions.checkState(
        lenExpr instanceof IntNum, "Expected IntNum length for map %s, got %s", mapRef, lenExpr);

    long length = ((IntNum) lenExpr).getInt64();
    int printLimit = (int) Math.min(length, (long) MAX_ELEMENTS_TO_PRINT);
    List<String> entryStrings = new ArrayList<>();
    ImmutableMap.Builder<Object, Object> nativeMap = ImmutableMap.builder();
    Set<Expr<?>> seenKeys = new HashSet<>();
    CelType keyType = SimpleType.DYN;
    CelType valType = SimpleType.DYN;
    for (int i = 0; i < length; i++) {
      Expr<?> key =
          evaluateStrict(
              model,
              ctx.mkNth(typeSystem.getMapKeys(mapRef), ctx.mkInt(i)),
              String.format("Z3 failed to evaluate map key at index %d for map %s", i, mapRef));
      if (!seenKeys.add(key)) {
        continue;
      }
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
        ExtractedNode keyNode = extractNode(ctx, typeSystem, valueProvider, model, key);
        ExtractedNode valNode = extractNode(ctx, typeSystem, valueProvider, model, value);
        if (keyNode.nativeValue != null && valNode.nativeValue != null) {
          nativeMap.put(keyNode.nativeValue, valNode.nativeValue);
        }
        if (entryStrings.size() < printLimit) {
          entryStrings.add(keyNode.celString + ": " + valNode.celString);
        }
        keyType = keyNode.type;
        valType = valNode.type;
      }
    }
    if (length > printLimit) {
      entryStrings.add("... (" + (length - printLimit) + " more entries)");
    }

    ImmutableMap<Object, Object> builtMap = nativeMap.buildOrThrow();
    Object adaptedMap = valueProvider.celValueConverter().toRuntimeValue(builtMap);

    return new ExtractedNode(
        MapType.create(keyType, valType), adaptedMap, "{" + String.join(", ", entryStrings) + "}");
  }

  static ExtractedNode reconstructMessage(
      Context ctx,
      CelZ3TypeSystem typeSystem,
      CelValueProvider valueProvider,
      Model model,
      Expr<?> msgRef) {
    Expr<?> presenceArray =
        evaluateStrict(
            model,
            typeSystem.getMsgPresence(msgRef),
            String.format("Z3 failed to evaluate presence array natively for msg %s", msgRef));

    Expr<?> typeNameExpr =
        evaluateStrict(
            model,
            typeSystem.getMsgTypeName(msgRef),
            String.format("Z3 failed to evaluate type name natively for msg %s", msgRef));

    String typeName =
        extractNode(ctx, typeSystem, valueProvider, model, typeNameExpr)
            .celString
            .replace("\"", "");

    Set<Expr<?>> keys = new LinkedHashSet<>();
    extractKeys(presenceArray, keys);

    List<String> entryStrings = new ArrayList<>();
    ImmutableMap.Builder<String, Object> fieldMap = ImmutableMap.builder();
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

        String fieldName =
            extractNode(ctx, typeSystem, valueProvider, model, key).celString.replace("\"", "");
        ExtractedNode valNode = extractNode(ctx, typeSystem, valueProvider, model, value);
        if (valNode.nativeValue != null) {
          fieldMap.put(fieldName, valNode.nativeValue);
        }
        entryStrings.add(fieldName + ": " + valNode.celString);
      }
    }

    ImmutableMap<String, Object> builtFieldMap = fieldMap.buildOrThrow();
    Object nativeVal = null;
    try {
      Optional<Object> structVal = valueProvider.newValue(typeName, builtFieldMap);
      if (structVal.isPresent()) {
        nativeVal = valueProvider.celValueConverter().maybeUnwrap(structVal.get());
      }
    } catch (IllegalArgumentException | UnsupportedOperationException e) {
      // Z3 solver may assign values to non-existent field names for unconstrained message sorts.
      nativeVal = null;
    }
    if (nativeVal == null) {
      nativeVal = builtFieldMap;
    }

    return new ExtractedNode(
        StructTypeReference.create(typeName),
        nativeVal,
        typeName + "{" + String.join(", ", entryStrings) + "}");
  }

  private static void extractKeys(Expr<?> arrayExpr, Set<Expr<?>> keys) {
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

      if (declName.equals("store")) {
        Expr<?>[] args = arrayExpr.getArgs();
        Preconditions.checkState(
            args.length == 3, "Z3 store array operation must have exactly 3 arguments");
        keys.add(args[1]);
        arrayExpr = args[0];
        continue;
      }
      break;
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
