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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.primitives.UnsignedLong;
import com.microsoft.z3.ArrayExpr;
import com.microsoft.z3.BitVecNum;
import com.microsoft.z3.Context;
import com.microsoft.z3.Expr;
import com.microsoft.z3.FPNum;
import com.microsoft.z3.FuncDecl;
import com.microsoft.z3.IntNum;
import com.microsoft.z3.Model;
import dev.cel.common.internal.ProtoTimeUtils;
import dev.cel.common.values.CelByteString;
import dev.cel.common.values.NullValue;
import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.IntStream;

/** Extracts {@link CelWitness}es from Z3 models. */
@SuppressWarnings({"unchecked", "rawtypes"}) // Z3 Java API uses raw types.
final class CelZ3CounterexampleGenerator {

  private static final int MAX_ELEMENTS_TO_PRINT = 15;
  private static final BigInteger NANOS_PER_SECOND =
      BigInteger.valueOf(CelZ3TypeSystem.NANOS_PER_SECOND);

  private final Context ctx;
  private final CelZ3TypeSystem typeSystem;
  private final Model model;
  private final int comprehensionUnrollLimit;

  static CelWitness generate(
      Context ctx,
      CelZ3TypeSystem typeSystem,
      Model model,
      int comprehensionUnrollLimit,
      Set<Expr<?>> inputs) {
    CelZ3CounterexampleGenerator generator =
        new CelZ3CounterexampleGenerator(ctx, typeSystem, model, comprehensionUnrollLimit);
    ImmutableMap.Builder<String, CelWitness.Variable> variables = ImmutableMap.builder();
    for (Expr<?> input : inputs) {
      Value value = generator.extract(generator.evaluate(input));
      variables.put(
          input.getFuncDecl().getName().toString(),
          CelWitness.Variable.create(value.runtimeValue, value.display));
    }
    return CelWitness.create(variables.buildOrThrow());
  }

  // A model value's CEL runtime representation, if it has an exact one, and its display.
  private static final class Value {
    private final Optional<Object> runtimeValue;
    private final String display;

    private static Value exact(Object runtimeValue, String display) {
      return new Value(Optional.of(runtimeValue), display);
    }

    private static Value inexact(String display) {
      return new Value(Optional.empty(), display);
    }

    private Value(Optional<Object> runtimeValue, String display) {
      this.runtimeValue = runtimeValue;
      this.display = display;
    }
  }

  private Value extract(Expr<?> expr) {
    FuncDecl decl = expr.getFuncDecl();
    Expr<?>[] args = expr.getArgs();

    if (decl.equals(typeSystem.intCons().ConstructorDecl())) {
      BigInteger val = ((IntNum) args[0]).getBigInteger();
      return isInRange(val, Long.MIN_VALUE, Long.MAX_VALUE)
          ? Value.exact(val.longValue(), val.toString())
          : Value.inexact(val.toString());
    } else if (decl.equals(typeSystem.uintCons().ConstructorDecl())) {
      BigInteger val = ((IntNum) args[0]).getBigInteger();
      String display = val + "u";
      return val.signum() >= 0 && val.bitLength() <= 64
          ? Value.exact(UnsignedLong.valueOf(val), display)
          : Value.inexact(display);
    } else if (decl.equals(typeSystem.boolCons().ConstructorDecl())) {
      return Value.exact(args[0].isTrue(), args[0].toString());
    } else if (decl.equals(typeSystem.doubleCons().ConstructorDecl())) {
      return extractDouble((FPNum) args[0]);
    } else if (decl.equals(typeSystem.stringCons().ConstructorDecl())) {
      return extractString(args[0], /* isBytes= */ false);
    } else if (decl.equals(typeSystem.bytesCons().ConstructorDecl())) {
      return extractString(args[0], /* isBytes= */ true);
    } else if (decl.equals(typeSystem.timestampCons().ConstructorDecl())) {
      BigInteger nanos = ((IntNum) args[0]).getBigInteger();
      BigInteger nanoAdjustment = nanos.mod(NANOS_PER_SECOND);
      BigInteger seconds = nanos.subtract(nanoAdjustment).divide(NANOS_PER_SECOND);
      if (!isInRange(
          seconds, ProtoTimeUtils.TIMESTAMP_SECONDS_MIN, ProtoTimeUtils.TIMESTAMP_SECONDS_MAX)) {
        // No CEL timestamp is out of range, so no literal exists; show the seconds.
        return Value.inexact("timestamp(" + seconds + ")");
      }
      Instant instant = Instant.ofEpochSecond(seconds.longValue(), nanoAdjustment.longValue());
      return Value.exact(instant, "timestamp(\"" + instant + "\")");
    } else if (decl.equals(typeSystem.durationCons().ConstructorDecl())) {
      BigInteger nanos = ((IntNum) args[0]).getBigInteger();
      String display =
          nanos.remainder(NANOS_PER_SECOND).signum() == 0
              ? "duration(\"" + nanos.divide(NANOS_PER_SECOND) + "s\")"
              : "duration(\"" + nanos + "ns\")";
      return isInRange(nanos, Long.MIN_VALUE, Long.MAX_VALUE)
          ? Value.exact(Duration.ofNanos(nanos.longValue()), display)
          : Value.inexact(display);
    } else if (decl.equals(typeSystem.listCons().ConstructorDecl())) {
      return extractList(args[0]);
    } else if (decl.equals(typeSystem.mapCons().ConstructorDecl())) {
      return extractMap(args[0]);
    } else if (decl.equals(typeSystem.messageCons().ConstructorDecl())) {
      return extractMessage(args[0]);
    } else if (decl.equals(typeSystem.errorCons().ConstructorDecl())) {
      return Value.inexact("Error");
    } else if (decl.equals(typeSystem.unknownCons().ConstructorDecl())) {
      return Value.inexact("Unknown");
    } else if (decl.equals(typeSystem.nullCons().ConstructorDecl())) {
      return Value.exact(NullValue.NULL_VALUE, "null");
    }

    // Optional is the only remaining constructor.
    if (!evaluate(typeSystem.optHasValue(args[0])).isTrue()) {
      return Value.exact(Optional.empty(), "optional.none()");
    }
    Value value = extract(evaluate(typeSystem.getOptionalValue(args[0])));
    return new Value(
        value.runtimeValue.<Object>map(Optional::of), "optional.of(" + value.display + ")");
  }

  private static boolean isInRange(BigInteger val, long min, long max) {
    return val.compareTo(BigInteger.valueOf(min)) >= 0
        && val.compareTo(BigInteger.valueOf(max)) <= 0;
  }

  private Value extractDouble(FPNum fpNum) {
    if (fpNum.isNaN()) {
      return Value.exact(Double.NaN, "double(\"NaN\")");
    }
    // Z3 doubles are IEEE 754 binary64, so their bits convert exactly.
    double value =
        Double.longBitsToDouble(
            ((BitVecNum) ctx.mkFPToIEEEBV(fpNum).simplify()).getBigInteger().longValue());
    return Value.exact(
        value, Double.isInfinite(value) ? "double(\"" + value + "\")" : Double.toString(value));
  }

  private static Value extractString(Expr<?> arg, boolean isBytes) {
    int[] codePoints = decodeZ3String(arg.getString());
    // Bytes are modeled as their UTF-8 decoding, which is lossy for non-ASCII bytes. Java merges
    // adjacent surrogates into one code point, which would change the string's size.
    boolean isExact =
        Arrays.stream(codePoints)
            .allMatch(
                isBytes
                    ? c -> c < 0x80
                    : c -> c < Character.MIN_SURROGATE || c > Character.MAX_SURROGATE);
    String str = new String(codePoints, 0, codePoints.length);
    String display = quote(codePoints, isBytes);
    if (!isExact) {
      return Value.inexact(display);
    }
    return Value.exact(isBytes ? CelByteString.copyFromUtf8(str) : str, display);
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

  private Value extractList(Expr<?> listRef) {
    long length = evaluateLength(ctx.mkLength(typeSystem.getSeq(listRef)));
    // Elements past the unroll limit are unconstrained, so they may violate the declared type.
    boolean isExact = length <= comprehensionUnrollLimit;
    long limit = isExact ? length : Math.min(length, MAX_ELEMENTS_TO_PRINT);
    ImmutableList.Builder<Object> values = ImmutableList.builder();
    List<String> elements = new ArrayList<>();
    for (long i = 0; i < limit; i++) {
      Value elem = extract(evaluate(ctx.mkNth(typeSystem.getSeq(listRef), ctx.mkInt(i))));
      if (elem.runtimeValue.isPresent()) {
        values.add(elem.runtimeValue.get());
      } else {
        isExact = false;
      }
      elements.add(elem.display);
    }
    String display = join("[", elements, length, "elements", "]");
    return isExact ? Value.exact(values.build(), display) : Value.inexact(display);
  }

  private Value extractMap(Expr<?> mapRef) {
    long length = evaluateLength(ctx.mkLength(typeSystem.getMapKeys(mapRef)));
    // Entries past the unroll limit are unconstrained, so they may violate the declared type.
    boolean isExact = length <= comprehensionUnrollLimit;
    long limit = isExact ? length : Math.min(length, MAX_ELEMENTS_TO_PRINT);
    ImmutableMap.Builder<Object, Object> values = ImmutableMap.builder();
    List<String> entries = new ArrayList<>();
    Set<Expr<?>> seenKeys = new HashSet<>();
    ArrayExpr keySet = ctx.mkConstArray(typeSystem.celValueSort(), ctx.mkFalse());
    for (long i = 0; i < limit; i++) {
      Expr<?> key = evaluate(ctx.mkNth(typeSystem.getMapKeys(mapRef), ctx.mkInt(i)));
      keySet = ctx.mkStore(keySet, key, ctx.mkTrue());
      if (!seenKeys.add(key)
          || !evaluate(ctx.mkSelect((ArrayExpr) typeSystem.getMapPresence(mapRef), key)).isTrue()) {
        continue;
      }
      Value keyValue = extract(key);
      Value value =
          extract(evaluate(ctx.mkSelect((ArrayExpr) typeSystem.getMapValues(mapRef), key)));
      if (keyValue.runtimeValue.isPresent() && value.runtimeValue.isPresent()) {
        values.put(keyValue.runtimeValue.get(), value.runtimeValue.get());
      } else {
        isExact = false;
      }
      entries.add(keyValue.display + ": " + value.display);
    }
    // Maps nested in dyn values aren't constrained to be well formed: the size comes from the key
    // sequence and membership from the presence array, so they must agree.
    isExact =
        isExact
            && seenKeys.size() == length
            && evaluate(ctx.mkEq(typeSystem.getMapPresence(mapRef), keySet)).isTrue();

    String display = join("{", entries, length, "entries", "}");
    return isExact ? Value.exact(values.buildOrThrow(), display) : Value.inexact(display);
  }

  private static String join(
      String prefix, List<String> displayed, long length, String noun, String suffix) {
    List<String> shown =
        new ArrayList<>(displayed.subList(0, Math.min(displayed.size(), MAX_ELEMENTS_TO_PRINT)));
    if (length > MAX_ELEMENTS_TO_PRINT) {
      shown.add("... (" + (length - MAX_ELEMENTS_TO_PRINT) + " more " + noun + ")");
    }
    return prefix + String.join(", ", shown) + suffix;
  }

  // TODO: Build message values.
  private Value extractMessage(Expr<?> msgRef) {
    Expr<?> presenceArray = evaluate(typeSystem.getMsgPresence(msgRef));
    String typeName = evaluate(typeSystem.getMsgTypeName(msgRef)).getString();

    Set<Expr<?>> keys = new LinkedHashSet<>();
    extractKeys(presenceArray, keys);

    List<String> entries = new ArrayList<>();
    for (Expr<?> key : keys) {
      if (evaluate(ctx.mkSelect((ArrayExpr) typeSystem.getMsgPresence(msgRef), key)).isTrue()) {
        Expr<?> value = evaluate(ctx.mkSelect((ArrayExpr) typeSystem.getMsgValues(msgRef), key));
        entries.add(key.getString() + ": " + extract(value).display);
      }
    }

    return Value.inexact(typeName + "{" + String.join(", ", entries) + "}");
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

  private CelZ3CounterexampleGenerator(
      Context ctx, CelZ3TypeSystem typeSystem, Model model, int comprehensionUnrollLimit) {
    this.ctx = ctx;
    this.typeSystem = typeSystem;
    this.model = model;
    this.comprehensionUnrollLimit = comprehensionUnrollLimit;
  }
}
