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

import com.google.common.collect.Lists;
import com.google.common.collect.ObjectArrays;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.microsoft.z3.ArithExpr;
import com.microsoft.z3.ArrayExpr;
import com.microsoft.z3.BoolExpr;
import com.microsoft.z3.Constructor;
import com.microsoft.z3.Context;
import com.microsoft.z3.DatatypeSort;
import com.microsoft.z3.Expr;
import com.microsoft.z3.FPExpr;
import com.microsoft.z3.FuncDecl;
import com.microsoft.z3.IntExpr;
import com.microsoft.z3.SeqExpr;
import com.microsoft.z3.SeqSort;
import com.microsoft.z3.Sort;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Type system for mapping CEL values to Z3 expressions.
 *
 * <p><b>Thread Safety & Lifetime:</b> This class is <b>NOT thread-safe</b>. Its lifetime is
 * strictly bound to a single Z3 {@link Context} during a single verification run. Instances of this
 * class must <b>never</b> be cached, stored as fields, shared across threads, or reused across
 * multiple verification runs.
 *
 * <p>When implementing custom axioms via {@code CelZ3FunctionAxiom}, only use the instance provided
 * to {@code translateOverload} for the duration of that method call. Do not leak references to this
 * type system or its produced expressions outside of the overload translation.
 */
@SuppressWarnings({"unchecked", "rawtypes", "AvoidObjectArrays"}) // Z3 Java API uses raw types.
public final class CelZ3TypeSystem {

  public static final String MIN_INT64 = "-9223372036854775808";
  public static final String MAX_INT64 = "9223372036854775807";
  public static final String MAX_UINT64 = "18446744073709551615";

  private static final String TYPE_CEL_VALUE = "CelValue";
  private static final String CONS_BOOL = "Bool";
  private static final String IS_BOOL = "isBool";
  private static final String GET_BOOL = "getBool";

  private static final String CONS_INT = "Int";
  private static final String IS_INT = "isInt";
  private static final String GET_INT = "getInt";

  private static final String CONS_UINT = "Uint";
  private static final String IS_UINT = "isUint";
  private static final String GET_UINT = "getUint";

  private static final String CONS_DOUBLE = "Double";
  private static final String IS_DOUBLE = "isDouble";
  private static final String GET_DOUBLE = "getDouble";

  private static final String CONS_STRING = "String";
  private static final String IS_STRING = "isString";
  private static final String GET_STRING = "getString";

  private static final String CONS_BYTES = "Bytes";
  private static final String IS_BYTES = "isBytes";
  private static final String GET_BYTES = "getBytes";

  private static final String CONS_ERROR = "CelError";
  private static final String IS_ERROR = "isError";

  private static final String CONS_UNKNOWN = "CelUnknown";
  private static final String IS_UNKNOWN = "isUnknown";
  private static final String GET_UNKNOWN = "getUnknownId";
  private static final String GENERIC_UNKNOWN_ID = "!generic_unknown";

  private static final String CONS_NULL = "CelNull";
  private static final String IS_NULL = "isNull";

  private static final String CONS_OPTIONAL = "Optional";
  private static final String IS_OPTIONAL = "isOptional";

  private static final String SORT_OPTIONAL_REF = "OptionalRef";
  private static final String GET_OPTIONAL_REF = "getOptionalRef";
  private static final String FUNC_OPT_VALUE = "opt_value";
  private static final String FUNC_OPT_OF_REF = "!optionalOfRef";

  private static final String SORT_LIST_REF = "ListRef";
  private static final String CONS_LIST = "List";
  private static final String IS_LIST = "isList";
  private static final String GET_LIST_REF = "getListRef";
  private static final String FUNC_AS_SEQ = "as_seq";

  private static final String SORT_MAP_REF = "MapRef";
  private static final String CONS_MAP = "Map";
  private static final String IS_MAP = "isMap";
  private static final String GET_MAP_REF = "getMapRef";

  private static final String FUNC_MAP_VALUES = "map_values";
  private static final String FUNC_MAP_KEYS = "map_keys";
  private static final String FUNC_MAP_PRESENCE = "map_presence";

  private static final String SORT_MESSAGE_REF = "MessageRef";
  private static final String CONS_MESSAGE = "Message";
  private static final String IS_MESSAGE = "isMessage";
  private static final String GET_MESSAGE_REF = "getMessageRef";

  private static final String FUNC_MSG_VALUES = "msg_values";
  private static final String FUNC_MSG_PRESENCE = "msg_presence";
  private static final String FUNC_MSG_TYPE_NAME = "msg_type_name";

  private final Context ctx;

  private static final class FuncDeclKey {
    private final String name;
    private final Sort[] domain;
    private final Sort range;

    FuncDeclKey(String name, Sort[] domain, Sort range) {
      this.name = name;
      this.domain = domain;
      this.range = range;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof FuncDeclKey)) {
        return false;
      }
      FuncDeclKey that = (FuncDeclKey) o;
      return name.equals(that.name)
          && Arrays.equals(domain, that.domain)
          && range.equals(that.range);
    }

    @Override
    public int hashCode() {
      int result = name.hashCode();
      result = 31 * result + Arrays.hashCode(domain);
      result = 31 * result + range.hashCode();
      return result;
    }
  }

  private final Map<FuncDeclKey, FuncDecl<?>> funcDeclCache = new HashMap<>();

  private final DatatypeSort celValueSort;
  private final Constructor boolCons;
  private final Constructor intCons;
  private final Constructor uintCons;
  private final Constructor doubleCons;
  private final Constructor stringCons;
  private final Constructor bytesCons;
  private final Constructor errorCons;
  private final Constructor unknownCons;
  private final Constructor nullCons;
  private final Constructor optionalCons;

  private final Sort unknownIdSort;
  private final Sort optionalRefSort;
  private final FuncDecl<?> optionalValueFunc;
  private final FuncDecl<?> optionalOfRefFunc;

  private final Sort listRefSort;
  private final Constructor listCons;
  private final FuncDecl<?> asSeqFunc;

  private final Sort mapRefSort;
  private final Constructor mapCons;
  private final FuncDecl mapValuesFunc;
  private final FuncDecl mapKeysFunc;
  private final FuncDecl mapPresenceFunc;

  private final Sort messageRefSort;
  private final Constructor messageCons;
  private final FuncDecl msgValuesFunc;
  private final FuncDecl msgPresenceFunc;
  private final FuncDecl msgTypeNameFunc;

  public Expr<?> mkListRefConst(String prefix) {
    return ctx.mkFreshConst(prefix, listRefSort);
  }

  public Expr<?> mkMapRefConst(String prefix) {
    return ctx.mkFreshConst(prefix, mapRefSort);
  }

  public Expr<?> mkMessageRefConst(String prefix) {
    return ctx.mkFreshConst(prefix, messageRefSort);
  }

  /**
   * Interns and retrieves a Z3 function declaration by name and signature.
   *
   * <p>Repeated calls with the same name will return the same cached {@link FuncDecl} instance,
   * avoiding redundant JNI calls to Z3.
   */
  public FuncDecl<?> internFuncDecl(String name, Sort[] domain, Sort range) {
    FuncDeclKey cacheKey = new FuncDeclKey(name, domain, range);
    return funcDeclCache.computeIfAbsent(cacheKey, k -> ctx.mkFuncDecl(name, domain, range));
  }

  /** Gets the underlying Z3 datatype sort representing a CEL value. */
  public DatatypeSort celValueSort() {
    return celValueSort;
  }

  public Context ctx() {
    return ctx;
  }

  /** Gets the uninterpreted sort used as a reference to a list. */
  public Sort listRefSort() {
    return listRefSort;
  }

  Constructor boolCons() {
    return boolCons;
  }

  Constructor intCons() {
    return intCons;
  }

  Constructor uintCons() {
    return uintCons;
  }

  Constructor doubleCons() {
    return doubleCons;
  }

  Constructor stringCons() {
    return stringCons;
  }

  Constructor bytesCons() {
    return bytesCons;
  }

  /** Creates a CelValue containing a boolean. */
  public Expr<?> mkBool(boolean val) {
    return ctx.mkApp(boolCons.ConstructorDecl(), ctx.mkBool(val));
  }

  /** Wraps a Z3 boolean expression into a CelValue. */
  public Expr<?> wrapBool(Expr<?> expr) {
    return ctx.mkApp(boolCons.ConstructorDecl(), expr);
  }

  /** Wraps a Z3 integer expression into a CelValue. */
  public Expr<?> wrapInt(IntExpr expr) {
    return ctx.mkApp(intCons.ConstructorDecl(), expr);
  }

  /** Wraps a Z3 string expression into a CelValue. */
  public Expr<?> wrapString(Expr<?> expr) {
    return ctx.mkApp(stringCons.ConstructorDecl(), expr);
  }

  /** Wraps a Z3 unsigned integer expression into a CelValue. */
  public Expr<?> wrapUint(IntExpr expr) {
    return ctx.mkApp(uintCons.ConstructorDecl(), expr);
  }

  /** Wraps a Z3 double (floating-point) expression into a CelValue. */
  public Expr<?> wrapDouble(FPExpr expr) {
    return ctx.mkApp(doubleCons.ConstructorDecl(), expr);
  }

  /** Wraps a Z3 bytes sequence expression into a CelValue. */
  public Expr<?> wrapBytes(Expr<?> expr) {
    return ctx.mkApp(bytesCons.ConstructorDecl(), expr);
  }

  /** Creates a CelValue containing an integer. */
  public Expr<?> mkInt(long val) {
    return ctx.mkApp(intCons.ConstructorDecl(), ctx.mkInt(val));
  }

  /** Creates a CelValue containing an unsigned integer. */
  public Expr<?> mkUint(long val) {
    return ctx.mkApp(uintCons.ConstructorDecl(), ctx.mkInt(val));
  }

  /** Creates a CelValue containing a double. */
  public Expr<?> mkDouble(double val) {
    return ctx.mkApp(doubleCons.ConstructorDecl(), mkFpDouble(val));
  }

  public FPExpr mkFpDouble(double val) {
    return ctx.mkFP(val, ctx.mkFPSortDouble());
  }

  /** Checks if the given CelValue is a double. */
  public BoolExpr isDouble(Expr<?> val) {
    return (BoolExpr) ctx.mkApp(doubleCons.getTesterDecl(), val);
  }

  /** Extracts the double reference from a double CelValue. */
  public Expr<?> getDouble(Expr<?> val) {
    return ctx.mkApp(doubleCons.getAccessorDecls()[0], val);
  }

  /**
   * Returns a Z3 boolean expression asserting structural equality between two CEL values.
   *
   * <p>In CEL, equality for collections and messages is structural (comparing contents), whereas Z3
   * models them as uninterpreted references. This method explicitly unrolls the equality check into
   * their underlying Z3 sequences, arrays, and properties, and strictly enforces IEEE-754 semantics
   * for floating-point comparisons.
   */
  public BoolExpr getStructuralEquality(Expr<?> arg0, Expr<?> arg1) {
    // Lists are backed by Z3 Sequences. We assert equality on the underlying Seq objects.
    BoolExpr isListEq = ctx.mkAnd(isList(arg0), isList(arg1));
    BoolExpr seqEq = ctx.mkEq(getSeq(getListRef(arg0)), getSeq(getListRef(arg1)));

    // Maps are backed by Z3 Arrays for both values and key presence.
    // Two maps are equal if and only if both their presence arrays and value arrays are identical.
    BoolExpr isMapEq = ctx.mkAnd(isMap(arg0), isMap(arg1));
    Expr<?> mapRef0 = getMapRef(arg0);
    Expr<?> mapRef1 = getMapRef(arg1);

    ArrayExpr mapValues0 = (ArrayExpr) getMapValues(mapRef0);
    ArrayExpr mapValues1 = (ArrayExpr) getMapValues(mapRef1);
    ArrayExpr mapPresence0 = (ArrayExpr) getMapPresence(mapRef0);
    ArrayExpr mapPresence1 = (ArrayExpr) getMapPresence(mapRef1);

    BoolExpr presenceEq = ctx.mkEq(mapPresence0, mapPresence1);
    BoolExpr valuesEq = ctx.mkEq(mapValues0, mapValues1);
    BoolExpr mapEq = ctx.mkAnd(presenceEq, valuesEq);

    // Messages are backed by Z3 Arrays for field values and presence, plus a type name.
    // Two messages are equal if their type names, presence arrays, and value arrays match.
    BoolExpr isMsgEq = ctx.mkAnd(isMessage(arg0), isMessage(arg1));
    Expr<?> msgRef0 = getMessageRef(arg0);
    Expr<?> msgRef1 = getMessageRef(arg1);

    BoolExpr msgTypeNameEq = ctx.mkEq(getMsgTypeName(msgRef0), getMsgTypeName(msgRef1));
    BoolExpr msgValuesEq = ctx.mkEq(getMsgValues(msgRef0), getMsgValues(msgRef1));
    BoolExpr msgPresenceEq = ctx.mkEq(getMsgPresence(msgRef0), getMsgPresence(msgRef1));
    BoolExpr msgEq = ctx.mkAnd(msgTypeNameEq, msgValuesEq, msgPresenceEq);

    // Doubles must be compared using native floating-point equality to follow IEEE-754.
    // Z3's structural mkEq evaluates NaN == NaN as true and 0.0 == -0.0 as false.
    BoolExpr isDoubleEq = ctx.mkAnd(isDouble(arg0), isDouble(arg1));
    BoolExpr doubleEq = ctx.mkFPEq((FPExpr) getDouble(arg0), (FPExpr) getDouble(arg1));

    // For primitives, generic equality matches the direct Z3 datatype wrapper.
    BoolExpr genericEq = ctx.mkEq(arg0, arg1);
    return (BoolExpr)
        SwitchBuilder.newBuilder(ctx)
            .addCase(isListEq, seqEq)
            .addCase(isMapEq, mapEq)
            .addCase(isMsgEq, msgEq)
            .addCase(isDoubleEq, doubleEq)
            .build(genericEq);
  }

  /** Creates a CelValue containing a string. */
  public Expr<?> mkString(String val) {
    return ctx.mkApp(stringCons.ConstructorDecl(), ctx.mkString(val));
  }

  /** Creates a CelValue containing bytes. */
  public Expr<?> mkBytes(String val) {
    return ctx.mkApp(bytesCons.ConstructorDecl(), ctx.mkString(val));
  }

  Expr<?> wrap(Constructor cons, Expr<?> expr) {
    return ctx.mkApp(cons.ConstructorDecl(), expr);
  }

  /** Creates a CelValue representing an error. */
  public Expr<?> mkError() {
    return ctx.mkConst(errorCons.ConstructorDecl());
  }

  /** Creates a CelValue representing null. */
  public Expr<?> mkNull() {
    return ctx.mkConst(nullCons.ConstructorDecl());
  }

  Constructor errorCons() {
    return errorCons;
  }

  /** Creates a CelValue representing an unknown value. */
  public Expr<?> mkUnknown() {
    return mkUnknown(ctx.mkConst(GENERIC_UNKNOWN_ID, unknownIdSort));
  }

  /** Creates a CelValue representing an unknown value with a specific ID. */
  public Expr<?> mkUnknown(Expr<?> unknownId) {
    return ctx.mkApp(unknownCons.ConstructorDecl(), unknownId);
  }

  /** Creates a parameterized unknown representing a truncated comprehension. */
  public Expr<?> mkParameterizedUnknown(long staticHash, List<Expr<?>> smtArgs) {
    Sort[] domain = new Sort[smtArgs.size()];
    for (int i = 0; i < smtArgs.size(); i++) {
      domain[i] = celValueSort();
    }

    String ufName = "!trunc_" + Long.toHexString(staticHash);
    FuncDecl<?> truncUf = internFuncDecl(ufName, domain, unknownIdSort());

    Expr<?> uniqueUnknownId =
        smtArgs.isEmpty()
            ? ctx.mkConst(ufName, unknownIdSort())
            : ctx.mkApp(truncUf, smtArgs.toArray(new Expr<?>[0]));

    return mkUnknown(uniqueUnknownId);
  }

  /** Gets the sort used for unknown identifiers. */
  public Sort unknownIdSort() {
    return unknownIdSort;
  }

  /**
   * Wraps the result in an ITE expression that short-circuits to Error or Unknown.
   *
   * @see #propagateErrorAndUnknown(Expr, Collection)
   */
  Expr<?> propagateErrorAndUnknown(Expr<?> result, Expr<?>... args) {
    return propagateErrorAndUnknown(result, Arrays.asList(args));
  }

  /**
   * Wraps the result in an ITE expression that short-circuits to Error or Unknown if any of the
   * provided arguments evaluate to Error or Unknown.
   */
  Expr<?> propagateErrorAndUnknown(Expr<?> result, Collection<Expr<?>> args) {
    if (args.isEmpty()) {
      return result;
    }
    List<Expr<?>> argsList = new ArrayList<>(args);
    BoolExpr[] errors = new BoolExpr[argsList.size()];
    BoolExpr[] unknowns = new BoolExpr[argsList.size()];
    Expr<?> unknownResult = mkUnknown();
    // Walk backwards to preserve the earliest unknown in case of multiple unknowns (applicable for
    // nested ITE chain)
    for (int i = argsList.size() - 1; i >= 0; i--) {
      Expr<?> arg = argsList.get(i);
      errors[i] = isError(arg);
      BoolExpr isUnknown = isUnknown(arg);
      unknowns[i] = isUnknown;
      unknownResult = ctx.mkITE(isUnknown, arg, unknownResult);
    }
    BoolExpr hasError = ctx.mkOr(errors);
    BoolExpr hasUnknown = ctx.mkOr(unknowns);
    // Unknowns have higher precedence than error
    return SwitchBuilder.newBuilder(ctx)
        .addCase(hasUnknown, unknownResult)
        .addCase(hasError, mkError())
        .build(result);
  }

  /**
   * Wraps the result in an ITE expression that short-circuits to Error if any of the provided
   * runtime error conditions are true.
   */
  public Expr<?> withRuntimeError(
      Expr<?> result, BoolExpr firstCondition, BoolExpr... remainingConditions) {
    BoolExpr condition =
        remainingConditions.length == 0
            ? firstCondition
            : ctx.mkOr(ObjectArrays.concat(firstCondition, remainingConditions));
    return ctx.mkITE(condition, mkError(), result);
  }

  Constructor unknownCons() {
    return unknownCons;
  }

  /** Checks if the given CelValue is an error. */
  public BoolExpr isError(Expr<?> val) {
    return (BoolExpr) ctx.mkApp(errorCons.getTesterDecl(), val);
  }

  /** Checks if the given CelValue is an unknown value. */
  public BoolExpr isUnknown(Expr<?> val) {
    return (BoolExpr) ctx.mkApp(unknownCons.getTesterDecl(), val);
  }

  /** Checks if the given CelValue is a boolean. */
  public BoolExpr isBool(Expr<?> val) {
    return (BoolExpr) ctx.mkApp(boolCons.getTesterDecl(), val);
  }

  /** Extracts the Z3 boolean expression from a boolean CelValue. */
  public Expr<?> unwrapBool(Expr<?> val) {
    return ctx.mkApp(boolCons.getAccessorDecls()[0], val);
  }

  /** Creates a CelValue representing optional.none(). */
  public Expr<?> mkOptionalNone() {
    return ctx.mkApp(optionalCons.ConstructorDecl(), mkNoneOptionalRef());
  }

  /** Gets the globally unique constant representing the reference of an empty optional. */
  public Expr<?> mkNoneOptionalRef() {
    return ctx.mkConst("!optionalNoneRef", optionalRefSort);
  }

  /** Creates a CelValue representing optional.of(ref). */
  public Expr<?> mkOptionalOf(Expr<?> ref) {
    return ctx.mkApp(optionalCons.ConstructorDecl(), ref);
  }

  /** Gets the uninterpreted function used to compute the optional reference for a given value. */
  public FuncDecl<?> optionalOfRefFunc() {
    return optionalOfRefFunc;
  }

  /** Checks if the given CelValue is an optional. */
  public BoolExpr isOptional(Expr<?> val) {
    return (BoolExpr) ctx.mkApp(optionalCons.getTesterDecl(), val);
  }

  /** Extracts the OptionalRef expression from an optional CelValue. */
  public Expr<?> getOptionalRef(Expr<?> val) {
    return ctx.mkApp(optionalCons.getAccessorDecls()[0], val);
  }

  /** Checks if the given optional reference contains a value. */
  public BoolExpr optHasValue(Expr<?> optRef) {
    return ctx.mkNot(ctx.mkEq(optRef, mkNoneOptionalRef()));
  }

  /** Gets the value of the optional from its reference. */
  public Expr<?> getOptionalValue(Expr<?> optRef) {
    return ctx.mkApp(optionalValueFunc, optRef);
  }

  /** Checks if the given CelValue is an integer. */
  public BoolExpr isInt(Expr<?> val) {
    return (BoolExpr) ctx.mkApp(intCons.getTesterDecl(), val);
  }

  /** Extracts the integer expression from an integer CelValue. */
  public IntExpr getInt(Expr<?> val) {
    return (IntExpr) ctx.mkApp(intCons.getAccessorDecls()[0], val);
  }

  /** Checks if the given CelValue is an unsigned integer. */
  public BoolExpr isUint(Expr<?> val) {
    return (BoolExpr) ctx.mkApp(uintCons.getTesterDecl(), val);
  }

  /** Extracts the integer expression from an unsigned integer CelValue. */
  public IntExpr getUint(Expr<?> val) {
    return (IntExpr) ctx.mkApp(uintCons.getAccessorDecls()[0], val);
  }

  /** Checks if the given CelValue is a string. */
  public BoolExpr isString(Expr<?> val) {
    return (BoolExpr) ctx.mkApp(stringCons.getTesterDecl(), val);
  }

  /** Checks if the given CelValue is bytes. */
  public BoolExpr isBytes(Expr<?> val) {
    return (BoolExpr) ctx.mkApp(bytesCons.getTesterDecl(), val);
  }

  /** Extracts the Z3 sequence expression from a string CelValue. */
  public Expr<?> getString(Expr<?> val) {
    return ctx.mkApp(stringCons.getAccessorDecls()[0], val);
  }

  /** Extracts the Z3 sequence expression from a bytes CelValue. */
  public Expr<?> getBytes(Expr<?> val) {
    return ctx.mkApp(bytesCons.getAccessorDecls()[0], val);
  }

  /** Checks if the given CelValue is a struct (message). */
  public BoolExpr isStruct(Expr<?> val) {
    return isMessage(val);
  }

  /** Checks if the given CelValue is null. */
  public BoolExpr isNull(Expr<?> val) {
    return (BoolExpr) ctx.mkApp(nullCons.getTesterDecl(), val);
  }

  /** Wraps a list reference into a CelValue. */
  public Expr<?> wrapList(Expr<?> listRef) {
    return ctx.mkApp(listCons.ConstructorDecl(), listRef);
  }

  Constructor listCons() {
    return listCons;
  }

  /** Checks if the given CelValue is a list. */
  public BoolExpr isList(Expr<?> val) {
    return (BoolExpr) ctx.mkApp(listCons.getTesterDecl(), val);
  }

  /** Extracts the list reference from a list CelValue. */
  public Expr<?> getListRef(Expr<?> val) {
    return ctx.mkApp(listCons.getAccessorDecls()[0], val);
  }

  /** Gets the sequence expression corresponding to the given list reference. */
  public SeqExpr<?> getSeq(Expr<?> listRef) {
    return (SeqExpr<?>) ctx.mkApp(asSeqFunc, listRef);
  }

  /** Gets the uninterpreted sort used as a reference to a map. */
  public Sort mapRefSort() {
    return mapRefSort;
  }

  Constructor mapCons() {
    return mapCons;
  }

  /** Wraps a map reference into a CelValue. */
  public Expr<?> wrapMap(Expr<?> mapRef) {
    return ctx.mkApp(mapCons.ConstructorDecl(), mapRef);
  }

  /** Checks if the given CelValue is a map. */
  public BoolExpr isMap(Expr<?> val) {
    return (BoolExpr) ctx.mkApp(mapCons.getTesterDecl(), val);
  }

  /** Extracts the map reference from a map CelValue. */
  public Expr<?> getMapRef(Expr<?> val) {
    return ctx.mkApp(mapCons.getAccessorDecls()[0], val);
  }

  /** Gets the array of values corresponding to the given map reference. */
  public Expr<?> getMapValues(Expr<?> mapRef) {
    return ctx.mkApp(mapValuesFunc, mapRef);
  }

  /** Gets the sequence of keys corresponding to the given map reference. */
  public SeqExpr<?> getMapKeys(Expr<?> mapRef) {
    return (SeqExpr<?>) ctx.mkApp(mapKeysFunc, mapRef);
  }

  /** Gets the presence array corresponding to the given map reference. */
  public Expr<?> getMapPresence(Expr<?> mapRef) {
    return ctx.mkApp(mapPresenceFunc, mapRef);
  }

  /** Gets the uninterpreted sort used as a reference to a message. */
  public Sort messageRefSort() {
    return messageRefSort;
  }

  Constructor messageCons() {
    return messageCons;
  }

  /** Wraps a message reference into a CelValue. */
  public Expr<?> wrapMessage(Expr<?> msgRef) {
    return ctx.mkApp(messageCons.ConstructorDecl(), msgRef);
  }

  /** Checks if the given CelValue is a message. */
  public BoolExpr isMessage(Expr<?> val) {
    return (BoolExpr) ctx.mkApp(messageCons.getTesterDecl(), val);
  }

  /** Extracts the message reference from a message CelValue. */
  public Expr<?> getMessageRef(Expr<?> val) {
    return ctx.mkApp(messageCons.getAccessorDecls()[0], val);
  }

  /** Gets the array of field values corresponding to the given message reference. */
  public Expr<?> getMsgValues(Expr<?> msgRef) {
    return ctx.mkApp(msgValuesFunc, msgRef);
  }

  /** Gets the presence array corresponding to the given message reference. */
  public Expr<?> getMsgPresence(Expr<?> msgRef) {
    return ctx.mkApp(msgPresenceFunc, msgRef);
  }

  /** Gets the type name string expression corresponding to the given message reference. */
  public Expr<?> getMsgTypeName(Expr<?> msgRef) {
    return ctx.mkApp(msgTypeNameFunc, msgRef);
  }

  /** Checks if the given arithmetic expression overflows a 64-bit integer. */
  public BoolExpr checkIntOverflow(ArithExpr result) {
    return ctx.mkOr(ctx.mkGt(result, ctx.mkInt(MAX_INT64)), ctx.mkLt(result, ctx.mkInt(MIN_INT64)));
  }

  /** Checks if the given arithmetic expression overflows a 64-bit unsigned integer. */
  public BoolExpr checkUintOverflow(ArithExpr result) {
    return ctx.mkOr(ctx.mkGt(result, ctx.mkInt(MAX_UINT64)), ctx.mkLt(result, ctx.mkInt(0)));
  }

  /** Safely concatenates two Z3 sequences. */
  @SuppressWarnings("unchecked") // Callers verify arguments are SeqExprs.
  public <R extends Sort> SeqExpr<R> mkConcatSafe(Expr<?> arg1, Expr<?> arg2) {
    return ctx.mkConcat((Expr<SeqSort<R>>) arg1, (Expr<SeqSort<R>>) arg2);
  }

  /**
   * Helper to build a chain of nested ITE (If-Then-Else) conditions.
   *
   * <p>Conditions are evaluated in the order they are added.
   */
  public static final class SwitchBuilder {

    private static final class SwitchCase {
      final BoolExpr condition;
      final Expr<?> value;

      SwitchCase(BoolExpr condition, Expr<?> value) {
        this.condition = condition;
        this.value = value;
      }
    }

    private final Context ctx;
    private final List<SwitchCase> cases;

    public static SwitchBuilder newBuilder(Context ctx) {
      return new SwitchBuilder(ctx);
    }

    @CanIgnoreReturnValue
    public SwitchBuilder addCase(BoolExpr condition, Expr<?> value) {
      cases.add(new SwitchCase(condition, value));
      return this;
    }

    public Expr<?> build(Expr<?> defaultFallback) {
      Expr<?> result = defaultFallback;
      for (SwitchCase c : Lists.reverse(cases)) {
        result = ctx.mkITE(c.condition, c.value, result);
      }
      return result;
    }

    private SwitchBuilder(Context ctx) {
      this.ctx = ctx;
      this.cases = new ArrayList<>();
    }
  }

  /**
   * Helper to construct a flattened logical OR expression to avoid deep left-leaning ASTs.
   *
   * <p>Returns {@code false} if the list is empty.
   */
  public static BoolExpr mkOrFlattened(Context ctx, List<BoolExpr> args) {
    // Pruning true/false constants in Java is significantly faster than building
    // larger ASTs and letting Z3 process them natively.
    List<BoolExpr> filteredArgs = new ArrayList<>();
    for (BoolExpr arg : args) {
      if (arg.isTrue()) {
        return ctx.mkTrue();
      }
      if (!arg.isFalse()) {
        filteredArgs.add(arg);
      }
    }
    if (filteredArgs.isEmpty()) {
      return ctx.mkFalse();
    }
    if (filteredArgs.size() == 1) {
      return filteredArgs.get(0);
    }
    return ctx.mkOr(filteredArgs.toArray(new BoolExpr[0]));
  }

  /**
   * Helper to construct a flattened logical AND expression to avoid deep left-leaning ASTs.
   *
   * <p>Returns {@code true} if the list is empty.
   */
  public static BoolExpr mkAndFlattened(Context ctx, BoolExpr... args) {
    return mkAndFlattened(ctx, Arrays.asList(args));
  }

  public static BoolExpr mkAndFlattened(Context ctx, List<BoolExpr> args) {
    // Pruning true/false constants in Java is significantly faster than building
    // larger ASTs and letting Z3 process them natively.
    List<BoolExpr> filteredArgs = new ArrayList<>();
    for (BoolExpr arg : args) {
      if (arg.isFalse()) {
        return ctx.mkFalse();
      }
      if (!arg.isTrue()) {
        filteredArgs.add(arg);
      }
    }
    if (filteredArgs.isEmpty()) {
      return ctx.mkTrue();
    }
    if (filteredArgs.size() == 1) {
      return filteredArgs.get(0);
    }
    return ctx.mkAnd(filteredArgs.toArray(new BoolExpr[0]));
  }

  /** Helper to construct a logical NOT expression while avoiding redundant NOT operations. */
  public static BoolExpr mkNotFlattened(Context ctx, BoolExpr arg) {
    if (arg.isTrue()) {
      return ctx.mkFalse();
    }
    if (arg.isFalse()) {
      return ctx.mkTrue();
    }
    return ctx.mkNot(arg);
  }

  CelZ3TypeSystem(Context ctx) {
    this.ctx = ctx;
    this.boolCons =
        ctx.mkConstructor(
            CONS_BOOL, IS_BOOL, new String[] {GET_BOOL}, new Sort[] {ctx.getBoolSort()}, null);
    // Note: Z3's IntSort models unbounded mathematical integers. We do not currently use
    // BitVecSort(64), which means CEL integer overflow semantics are not natively modeled,
    // and bitwise operations are unsupported. We enforce 64-bit value bounds explicitly
    // during variable constraint generation instead.
    this.intCons =
        ctx.mkConstructor(
            CONS_INT, IS_INT, new String[] {GET_INT}, new Sort[] {ctx.getIntSort()}, null);
    this.uintCons =
        ctx.mkConstructor(
            CONS_UINT, IS_UINT, new String[] {GET_UINT}, new Sort[] {ctx.getIntSort()}, null);
    this.doubleCons =
        ctx.mkConstructor(
            CONS_DOUBLE,
            IS_DOUBLE,
            new String[] {GET_DOUBLE},
            new Sort[] {ctx.mkFPSortDouble()},
            null);
    this.stringCons =
        ctx.mkConstructor(
            CONS_STRING,
            IS_STRING,
            new String[] {GET_STRING},
            new Sort[] {ctx.getStringSort()},
            null);
    this.bytesCons =
        ctx.mkConstructor(
            CONS_BYTES, IS_BYTES, new String[] {GET_BYTES}, new Sort[] {ctx.getStringSort()}, null);
    this.errorCons = ctx.mkConstructor(CONS_ERROR, IS_ERROR, null, null, null);

    this.unknownIdSort = ctx.mkUninterpretedSort("UnknownId");
    this.unknownCons =
        ctx.mkConstructor(
            CONS_UNKNOWN, IS_UNKNOWN, new String[] {GET_UNKNOWN}, new Sort[] {unknownIdSort}, null);

    this.nullCons = ctx.mkConstructor(CONS_NULL, IS_NULL, null, null, null);
    this.optionalRefSort = ctx.mkUninterpretedSort(SORT_OPTIONAL_REF);
    this.optionalCons =
        ctx.mkConstructor(
            CONS_OPTIONAL,
            IS_OPTIONAL,
            new String[] {GET_OPTIONAL_REF},
            new Sort[] {optionalRefSort},
            null);

    this.listRefSort = ctx.mkUninterpretedSort(SORT_LIST_REF);
    this.listCons =
        ctx.mkConstructor(
            CONS_LIST, IS_LIST, new String[] {GET_LIST_REF}, new Sort[] {this.listRefSort}, null);

    this.mapRefSort = ctx.mkUninterpretedSort(SORT_MAP_REF);
    this.mapCons =
        ctx.mkConstructor(
            CONS_MAP, IS_MAP, new String[] {GET_MAP_REF}, new Sort[] {this.mapRefSort}, null);

    this.messageRefSort = ctx.mkUninterpretedSort(SORT_MESSAGE_REF);
    this.messageCons =
        ctx.mkConstructor(
            CONS_MESSAGE,
            IS_MESSAGE,
            new String[] {GET_MESSAGE_REF},
            new Sort[] {this.messageRefSort},
            null);

    this.celValueSort =
        ctx.mkDatatypeSort(
            TYPE_CEL_VALUE,
            new Constructor[] {
              this.boolCons,
              this.intCons,
              this.uintCons,
              this.doubleCons,
              this.stringCons,
              this.bytesCons,
              this.errorCons,
              this.unknownCons,
              this.optionalCons,
              this.listCons,
              this.mapCons,
              this.messageCons,
              this.nullCons
            });

    this.optionalValueFunc =
        ctx.mkFuncDecl(FUNC_OPT_VALUE, new Sort[] {this.optionalRefSort}, this.celValueSort);
    this.optionalOfRefFunc =
        ctx.mkFuncDecl(FUNC_OPT_OF_REF, new Sort[] {this.celValueSort}, this.optionalRefSort);

    // Java specific workaround: Z3 Java API prevents recursive Datatype constructors from taking
    // SeqSort or ArraySort.
    // We wrap an opaque 'ListRef' or 'MapRef' inside the datatype instead, and map them
    // to native Z3 sequences/arrays using these uninterpreted functions.
    this.asSeqFunc =
        ctx.mkFuncDecl(
            FUNC_AS_SEQ, new Sort[] {this.listRefSort}, ctx.mkSeqSort(this.celValueSort));

    this.mapValuesFunc =
        ctx.mkFuncDecl(
            FUNC_MAP_VALUES,
            new Sort[] {this.mapRefSort},
            ctx.mkArraySort(this.celValueSort, this.celValueSort));
    this.mapKeysFunc =
        ctx.mkFuncDecl(
            FUNC_MAP_KEYS, new Sort[] {this.mapRefSort}, ctx.mkSeqSort(this.celValueSort));
    this.mapPresenceFunc =
        ctx.mkFuncDecl(
            FUNC_MAP_PRESENCE,
            new Sort[] {this.mapRefSort},
            ctx.mkArraySort(this.celValueSort, ctx.getBoolSort()));

    this.msgValuesFunc =
        ctx.mkFuncDecl(
            FUNC_MSG_VALUES,
            new Sort[] {this.messageRefSort},
            ctx.mkArraySort(ctx.getStringSort(), this.celValueSort));
    this.msgPresenceFunc =
        ctx.mkFuncDecl(
            FUNC_MSG_PRESENCE,
            new Sort[] {this.messageRefSort},
            ctx.mkArraySort(ctx.getStringSort(), ctx.getBoolSort()));
    this.msgTypeNameFunc =
        ctx.mkFuncDecl(FUNC_MSG_TYPE_NAME, new Sort[] {this.messageRefSort}, ctx.getStringSort());
  }
}
