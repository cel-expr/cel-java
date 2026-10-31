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

import static java.nio.charset.StandardCharsets.UTF_8;

import com.microsoft.z3.ArrayExpr;
import com.microsoft.z3.BitVecNum;
import com.microsoft.z3.Context;
import com.microsoft.z3.Expr;
import com.microsoft.z3.FPNum;
import com.microsoft.z3.FuncDecl;
import com.microsoft.z3.IntNum;
import com.microsoft.z3.Model;
import dev.cel.common.internal.ProtoTimeUtils;
import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

/** Generates human-readable counterexample strings from Z3 models. */
@SuppressWarnings({"unchecked", "rawtypes"}) // Z3 Java API uses raw types.
final class CelZ3CounterexampleGenerator {

  private static final int MAX_ELEMENTS_TO_PRINT = 15;
  private static final BigInteger NANOS_PER_SECOND =
      BigInteger.valueOf(CelZ3TypeSystem.NANOS_PER_SECOND);

  private final Context ctx;
  private final CelZ3TypeSystem typeSystem;
  private final Model model;
  private final Set<Expr<?>> valuesOnPath;

  static String generate(
      Context ctx,
      CelZ3TypeSystem typeSystem,
      Model model,
      Set<Expr<?>> inputs,
      boolean isApproximate,
      boolean isCounterexample) {
    if (inputs.isEmpty()) {
      return isCounterexample
          ? " (The expression fails unconditionally, regardless of input state)"
          : " (The expression is satisfiable unconditionally, regardless of input state)";
    }

    CelZ3CounterexampleGenerator generator =
        new CelZ3CounterexampleGenerator(ctx, typeSystem, model);
    StringBuilder sb = new StringBuilder();
    if (isCounterexample) {
      sb.append(isApproximate ? " Potential counterexample input:" : " Counterexample input:");
    } else {
      sb.append(isApproximate ? " Potential satisfying input:" : " Satisfying input:");
    }
    for (Expr<?> input : inputs) {
      sb.append("\n  ")
          .append(input.getFuncDecl().getName())
          .append(" = ")
          .append(generator.format(generator.evaluate(input)));
    }
    return sb.toString();
  }

  // Formats a model value as a CEL literal where one exists.
  private String format(Expr<?> expr) {
    // Refs are uninterpreted, so Z3 may return a cyclic model (e.g. a map containing itself).
    if (!valuesOnPath.add(expr)) {
      return "<cycle>";
    }
    String formatted = formatValue(expr);
    valuesOnPath.remove(expr);
    return formatted;
  }

  private String formatValue(Expr<?> expr) {
    FuncDecl decl = expr.getFuncDecl();
    Expr<?>[] args = expr.getArgs();

    if (decl.equals(typeSystem.intCons().ConstructorDecl())) {
      return ((IntNum) args[0]).getBigInteger().toString();
    } else if (decl.equals(typeSystem.uintCons().ConstructorDecl())) {
      return ((IntNum) args[0]).getBigInteger() + "u";
    } else if (decl.equals(typeSystem.boolCons().ConstructorDecl())) {
      return args[0].toString();
    } else if (decl.equals(typeSystem.doubleCons().ConstructorDecl())) {
      return formatDouble((FPNum) args[0]);
    } else if (decl.equals(typeSystem.stringCons().ConstructorDecl())) {
      return formatString(args[0], /* isBytes= */ false);
    } else if (decl.equals(typeSystem.bytesCons().ConstructorDecl())) {
      return formatString(args[0], /* isBytes= */ true);
    } else if (decl.equals(typeSystem.timestampCons().ConstructorDecl())) {
      BigInteger nanos = ((IntNum) args[0]).getBigInteger();
      BigInteger nanoAdjustment = nanos.mod(NANOS_PER_SECOND);
      BigInteger seconds = nanos.subtract(nanoAdjustment).divide(NANOS_PER_SECOND);
      if (!isInRange(
          seconds, ProtoTimeUtils.TIMESTAMP_SECONDS_MIN, ProtoTimeUtils.TIMESTAMP_SECONDS_MAX)) {
        // No CEL timestamp is out of range, so no literal exists; show the seconds.
        return "timestamp(" + seconds + ")";
      }
      return "timestamp(\""
          + Instant.ofEpochSecond(seconds.longValue(), nanoAdjustment.longValue())
          + "\")";
    } else if (decl.equals(typeSystem.durationCons().ConstructorDecl())) {
      BigInteger[] parts = ((IntNum) args[0]).getBigInteger().divideAndRemainder(NANOS_PER_SECOND);
      if (!isInRange(
          parts[0], ProtoTimeUtils.DURATION_SECONDS_MIN, ProtoTimeUtils.DURATION_SECONDS_MAX)) {
        // No CEL duration is out of range, so no literal exists; show the seconds.
        return "duration(\"" + parts[0] + "s\")";
      }
      return "duration(\""
          + ProtoTimeUtils.formatDuration(parts[0].longValue(), parts[1].intValue())
          + "\")";
    } else if (decl.equals(typeSystem.listCons().ConstructorDecl())) {
      return formatList(args[0]);
    } else if (decl.equals(typeSystem.mapCons().ConstructorDecl())) {
      return formatMap(args[0]);
    } else if (decl.equals(typeSystem.messageCons().ConstructorDecl())) {
      return formatMessage(args[0]);
    } else if (decl.equals(typeSystem.errorCons().ConstructorDecl())) {
      return "Error";
    } else if (decl.equals(typeSystem.unknownCons().ConstructorDecl())) {
      return "Unknown";
    } else if (decl.equals(typeSystem.nullCons().ConstructorDecl())) {
      return "null";
    }

    // Optional is the only remaining constructor.
    if (!evaluate(typeSystem.optHasValue(args[0])).isTrue()) {
      return "optional.none()";
    }
    return "optional.of(" + format(evaluate(typeSystem.getOptionalValue(args[0]))) + ")";
  }

  private static boolean isInRange(BigInteger val, long min, long max) {
    return val.compareTo(BigInteger.valueOf(min)) >= 0
        && val.compareTo(BigInteger.valueOf(max)) <= 0;
  }

  private String formatDouble(FPNum fpNum) {
    if (fpNum.isNaN()) {
      return "double(\"NaN\")";
    }
    // Z3 doubles are IEEE 754 binary64, so their bits convert exactly.
    double value =
        Double.longBitsToDouble(
            ((BitVecNum) ctx.mkFPToIEEEBV(fpNum).simplify()).getBigInteger().longValue());
    return Double.isInfinite(value) ? "double(\"" + value + "\")" : Double.toString(value);
  }

  private static String formatString(Expr<?> arg, boolean isBytes) {
    return quote(decodeZ3String(arg.getString()), isBytes);
  }

  // Z3 escapes characters outside printable ASCII, and a backslash before 'u', as \\u{hex}.
  private static int[] decodeZ3String(String encoded) {
    IntStream.Builder codePoints = IntStream.builder();
    for (int i = 0; i < encoded.length(); ) {
      if (encoded.startsWith("\\u{", i)) {
        int end = encoded.indexOf('}', i);
        codePoints.add(Integer.parseInt(encoded.substring(i + 3, end), 16));
        i = end + 1;
      } else {
        codePoints.add(encoded.charAt(i++));
      }
    }
    return codePoints.build().toArray();
  }

  // Takes code points rather than a String, which would merge adjacent surrogates.
  private static String quote(int[] codePoints, boolean isBytes) {
    StringBuilder sb = new StringBuilder(isBytes ? "b\"" : "\"");
    for (int codePoint : codePoints) {
      if (codePoint == '"' || codePoint == '\\') {
        sb.append('\\').appendCodePoint(codePoint);
      } else if (isBytes && (codePoint < 0x20 || codePoint >= 0x7f)) {
        // Bytes literals don't accept Unicode escapes, so write the character's UTF-8 bytes.
        for (byte b : new String(Character.toChars(codePoint)).getBytes(UTF_8)) {
          sb.append(String.format("\\x%02x", b & 0xff));
        }
      } else if (Character.isISOControl(codePoint)
          || Character.getType(codePoint) == Character.SURROGATE) {
        // CEL rejects lone surrogates even when escaped, so those are shown, not evaluable.
        sb.append(String.format("\\u%04x", codePoint));
      } else {
        sb.appendCodePoint(codePoint);
      }
    }
    return sb.append('"').toString();
  }

  private String formatList(Expr<?> listRef) {
    long length = evaluateLength(ctx.mkLength(typeSystem.getSeq(listRef)));
    List<String> elements = new ArrayList<>();
    for (long i = 0; i < Math.min(length, MAX_ELEMENTS_TO_PRINT); i++) {
      elements.add(format(evaluate(ctx.mkNth(typeSystem.getSeq(listRef), ctx.mkInt(i)))));
    }
    return join("[", elements, length, "elements", "]");
  }

  private String formatMap(Expr<?> mapRef) {
    long length = evaluateLength(ctx.mkLength(typeSystem.getMapKeys(mapRef)));
    List<String> entries = new ArrayList<>();
    Set<Expr<?>> seenKeys = new HashSet<>();
    for (long i = 0; i < Math.min(length, MAX_ELEMENTS_TO_PRINT); i++) {
      Expr<?> key = evaluate(ctx.mkNth(typeSystem.getMapKeys(mapRef), ctx.mkInt(i)));
      if (!seenKeys.add(key)
          || !evaluate(ctx.mkSelect((ArrayExpr) typeSystem.getMapPresence(mapRef), key)).isTrue()) {
        continue;
      }
      Expr<?> value = evaluate(ctx.mkSelect((ArrayExpr) typeSystem.getMapValues(mapRef), key));
      entries.add(format(key) + ": " + format(value));
    }
    return join("{", entries, length, "entries", "}");
  }

  private static String join(
      String prefix, List<String> displayed, long length, String noun, String suffix) {
    if (length > MAX_ELEMENTS_TO_PRINT) {
      displayed.add("... (" + (length - MAX_ELEMENTS_TO_PRINT) + " more " + noun + ")");
    }
    return prefix + String.join(", ", displayed) + suffix;
  }

  private String formatMessage(Expr<?> msgRef) {
    Expr<?> presenceArray = evaluate(typeSystem.getMsgPresence(msgRef));
    String typeName = evaluate(typeSystem.getMsgTypeName(msgRef)).getString();

    Set<Expr<?>> keys = new LinkedHashSet<>();
    extractKeys(presenceArray, keys);

    List<String> entries = new ArrayList<>();
    for (Expr<?> key : keys) {
      if (evaluate(ctx.mkSelect((ArrayExpr) typeSystem.getMsgPresence(msgRef), key)).isTrue()) {
        Expr<?> value = evaluate(ctx.mkSelect((ArrayExpr) typeSystem.getMsgValues(msgRef), key));
        entries.add(key.getString() + ": " + format(value));
      }
    }

    return typeName + "{" + String.join(", ", entries) + "}";
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
        keys.add(args[1]);
        arrayExpr = args[0];
        continue;
      }
      break;
    }
  }

  private long evaluateLength(Expr<?> lengthExpr) {
    return ((IntNum) evaluate(lengthExpr)).getInt64();
  }

  private Expr<?> evaluate(Expr<?> expr) {
    // Model completion assigns a value to any input the model leaves unconstrained.
    return model.evaluate(expr, /* completion= */ true);
  }

  private CelZ3CounterexampleGenerator(Context ctx, CelZ3TypeSystem typeSystem, Model model) {
    this.ctx = ctx;
    this.typeSystem = typeSystem;
    this.model = model;
    this.valuesOnPath = new HashSet<>();
  }
}
