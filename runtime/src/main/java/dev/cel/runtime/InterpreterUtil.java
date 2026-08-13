// Copyright 2022 Google LLC
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

package dev.cel.runtime;

import com.google.common.collect.ImmutableSet;
import com.google.errorprone.annotations.CheckReturnValue;
import com.google.errorprone.annotations.InlineMe;
import dev.cel.common.annotations.Internal;
import org.jspecify.annotations.Nullable;

/**
 * Util class for CEL interpreter.
 *
 * <p>CEL Library Internals. Do Not Use.
 */
@Internal
public final class InterpreterUtil {

  /**
   * Enforces strictness. The outcome of a failed computation is represented by the value being a
   * {@link Throwable}. Applying {@code strict()} to such a value-or-throwable will re-throw the
   * proper exception.
   */
  @CheckReturnValue
  public static Object strict(Object valueOrThrowable) throws CelEvaluationException {
    if (!(valueOrThrowable instanceof Throwable)) {
      return valueOrThrowable;
    }
    if (valueOrThrowable instanceof CelEvaluationException) {
      throw (CelEvaluationException) valueOrThrowable;
    }
    if (valueOrThrowable instanceof RuntimeException) {
      throw (RuntimeException) valueOrThrowable;
    }
    throw new RuntimeException((Throwable) valueOrThrowable);
  }

  /**
   * Check if raw object is {@link CelUnknownSet}.
   *
   * @param obj Object to check.
   * @return boolean value if object is unknown.
   * @deprecated Perform {@code obj instanceof CelUnknownSet} directly instead.
   */
  @Deprecated
  @InlineMe(replacement = "obj instanceof CelUnknownSet", imports = "dev.cel.runtime.CelUnknownSet")
  public static boolean isUnknown(Object obj) {
    return obj instanceof CelUnknownSet;
  }

  /** If the argument is {@link CelUnknownSet}, adapts it into {@link AccumulatedUnknowns} */
  public static Object maybeAdaptToAccumulatedUnknowns(Object val) {
    if (!(val instanceof CelUnknownSet)) {
      return val;
    }

    return adaptToAccumulatedUnknowns((CelUnknownSet) val);
  }

  public static AccumulatedUnknowns adaptToAccumulatedUnknowns(CelUnknownSet unknowns) {
    return AccumulatedUnknowns.create(unknowns.unknownExprIds(), unknowns.attributes());
  }

  public static Object maybeAdaptToCelUnknownSet(Object val) {
    if (!(val instanceof AccumulatedUnknowns)) {
      return val;
    }

    AccumulatedUnknowns unknowns = (AccumulatedUnknowns) val;
    return CelUnknownSet.create(
        ImmutableSet.copyOf(unknowns.attributes()), ImmutableSet.copyOf(unknowns.exprIds()));
  }

  /**
   * Enforces strictness on both lhs/rhs arguments from logical operators (i.e: intentionally throws
   * an appropriate exception when {@link Throwable} is encountered as part of evaluated result.
   */
  public static Object enforceStrictness(Object left, Object right) throws CelEvaluationException {
    // Throw left or right side exception for now, should combine them into ErrorSet.
    // <error> <op> <error> ==> <error>
    if (left instanceof Throwable) {
      return InterpreterUtil.strict(left);
    }
    if (right instanceof Throwable) {
      return InterpreterUtil.strict(right);
    }
    throw new RuntimeException(
        "Left or/and right object is neither bool, unknown nor error, unexpected behavior.");
  }

  public static Object valueOrUnknown(@Nullable Object valueOrThrowable, Long id) {
    // Handle the unknown value case.
    if (valueOrThrowable instanceof AccumulatedUnknowns) {
      return AccumulatedUnknowns.create(id);
    }
    // Handle the null value case.
    if (valueOrThrowable == null) {
      return AccumulatedUnknowns.create(id);
    }
    return valueOrThrowable;
  }

  private InterpreterUtil() {}
}
