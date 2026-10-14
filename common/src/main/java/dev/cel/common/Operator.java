// Copyright 2025 Google LLC
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

package dev.cel.common;

import com.google.common.collect.ImmutableMap;
import java.util.Optional;

/**
 * Package-private enumeration of Common Expression Language operators.
 *
 * <p>Equivalent to <a
 * href="https://pkg.go.dev/github.com/google/cel-go/common/operators">operators<a>.
 */
public enum Operator {
  CONDITIONAL("_?_:_"),
  LOGICAL_AND("_&&_", "&&"),
  LOGICAL_OR("_||_", "||"),
  LOGICAL_NOT("!_", "!"),
  EQUALS("_==_", "=="),
  NOT_EQUALS("_!=_", "!="),
  LESS("_<_", "<"),
  LESS_EQUALS("_<=_", "<="),
  GREATER("_>_", ">"),
  GREATER_EQUALS("_>=_", ">="),
  ADD("_+_", "+"),
  SUBTRACT("_-_", "-"),
  MULTIPLY("_*_", "*"),
  DIVIDE("_/_", "/"),
  MODULO("_%_", "%"),
  NEGATE("-_", "-"),
  INDEX("_[_]"),
  HAS("has"),
  ALL("all"),
  EXISTS("exists"),
  @Deprecated // Prefer EXISTS_ONE_NEW.
  EXISTS_ONE("exists_one"),
  EXISTS_ONE_NEW("existsOne"),
  MAP("map"),
  FILTER("filter"),
  NOT_STRICTLY_FALSE("@not_strictly_false"),
  IN("@in", "in"),
  OPTIONAL_INDEX("_[?_]"),
  OPTIONAL_SELECT("_?._"),
  @Deprecated // Prefer NOT_STRICTLY_FALSE.
  OLD_NOT_STRICTLY_FALSE("__not_strictly_false__"),
  @Deprecated // Prefer IN.
  OLD_IN("_in_");

  private final String functionName;
  private final String displayName;

  Operator(String functionName) {
    this(functionName, "");
  }

  Operator(String functionName, String displayName) {
    this.functionName = functionName;
    this.displayName = displayName;
  }

  /** Returns the mangled operator name, as used within the AST. */
  public String getFunction() {
    return functionName;
  }

  /** Returns the unmangled operator name, as used within the source text of an expression. */
  String getSymbol() {
    return displayName;
  }

  private static final ImmutableMap<String, Operator> OPERATORS =
      ImmutableMap.<String, Operator>builder()
          .put(ADD.getSymbol(), ADD)
          .put(DIVIDE.getSymbol(), DIVIDE)
          .put(EQUALS.getSymbol(), EQUALS)
          .put(GREATER.getSymbol(), GREATER)
          .put(GREATER_EQUALS.getSymbol(), GREATER_EQUALS)
          .put(IN.getSymbol(), IN)
          .put(LESS.getSymbol(), LESS)
          .put(LESS_EQUALS.getSymbol(), LESS_EQUALS)
          .put(MODULO.getSymbol(), MODULO)
          .put(MULTIPLY.getSymbol(), MULTIPLY)
          .put(NOT_EQUALS.getSymbol(), NOT_EQUALS)
          .put(SUBTRACT.getSymbol(), SUBTRACT)
          .buildOrThrow();

  /** Lookup an operator by its unmangled name, as used with the source text of an expression. */
  public static Optional<Operator> find(String text) {
    return Optional.ofNullable(OPERATORS.get(text));
  }

  private static final ImmutableMap<String, Operator> REVERSE_OPERATORS =
      ImmutableMap.<String, Operator>builder()
          .put(ADD.getFunction(), ADD)
          .put(ALL.getFunction(), ALL)
          .put(CONDITIONAL.getFunction(), CONDITIONAL)
          .put(DIVIDE.getFunction(), DIVIDE)
          .put(EQUALS.getFunction(), EQUALS)
          .put(EXISTS.getFunction(), EXISTS)
          .put(EXISTS_ONE.getFunction(), EXISTS_ONE)
          .put(EXISTS_ONE_NEW.getFunction(), EXISTS_ONE_NEW)
          .put(FILTER.getFunction(), FILTER)
          .put(GREATER.getFunction(), GREATER)
          .put(GREATER_EQUALS.getFunction(), GREATER_EQUALS)
          .put(HAS.getFunction(), HAS)
          .put(IN.getFunction(), IN)
          .put(INDEX.getFunction(), INDEX)
          .put(LESS.getFunction(), LESS)
          .put(LESS_EQUALS.getFunction(), LESS_EQUALS)
          .put(LOGICAL_AND.getFunction(), LOGICAL_AND)
          .put(LOGICAL_NOT.getFunction(), LOGICAL_NOT)
          .put(LOGICAL_OR.getFunction(), LOGICAL_OR)
          .put(MAP.getFunction(), MAP)
          .put(MODULO.getFunction(), MODULO)
          .put(MULTIPLY.getFunction(), MULTIPLY)
          .put(NEGATE.getFunction(), NEGATE)
          .put(NOT_EQUALS.getFunction(), NOT_EQUALS)
          .put(NOT_STRICTLY_FALSE.getFunction(), NOT_STRICTLY_FALSE)
          .put(OLD_IN.getFunction(), OLD_IN)
          .put(OLD_NOT_STRICTLY_FALSE.getFunction(), OLD_NOT_STRICTLY_FALSE)
          .put(OPTIONAL_INDEX.getFunction(), OPTIONAL_INDEX)
          .put(OPTIONAL_SELECT.getFunction(), OPTIONAL_SELECT)
          .put(SUBTRACT.getFunction(), SUBTRACT)
          .buildOrThrow();

  // precedence of the operator, where the higher value means higher.
  private static final ImmutableMap<String, Integer> PRECEDENCES =
      ImmutableMap.<String, Integer>builder()
          .put(CONDITIONAL.getFunction(), 8)
          .put(LOGICAL_OR.getFunction(), 7)
          .put(LOGICAL_AND.getFunction(), 6)
          .put(EQUALS.getFunction(), 5)
          .put(GREATER.getFunction(), 5)
          .put(GREATER_EQUALS.getFunction(), 5)
          .put(IN.getFunction(), 5)
          .put(LESS.getFunction(), 5)
          .put(LESS_EQUALS.getFunction(), 5)
          .put(NOT_EQUALS.getFunction(), 5)
          .put(ADD.getFunction(), 4)
          .put(SUBTRACT.getFunction(), 4)
          .put(DIVIDE.getFunction(), 3)
          .put(MODULO.getFunction(), 3)
          .put(MULTIPLY.getFunction(), 3)
          .put(LOGICAL_NOT.getFunction(), 2)
          .put(NEGATE.getFunction(), 2)
          .put(INDEX.getFunction(), 1)
          .buildOrThrow();

  private static final ImmutableMap<String, String> UNARY_OPERATORS =
      ImmutableMap.<String, String>builder()
          .put(NEGATE.getFunction(), "-")
          .put(LOGICAL_NOT.getFunction(), "!")
          .buildOrThrow();

  private static final ImmutableMap<String, String> BINARY_OPERATORS =
      ImmutableMap.<String, String>builder()
          .put(LOGICAL_OR.getFunction(), "||")
          .put(LOGICAL_AND.getFunction(), "&&")
          .put(LESS_EQUALS.getFunction(), "<=")
          .put(LESS.getFunction(), "<")
          .put(GREATER_EQUALS.getFunction(), ">=")
          .put(GREATER.getFunction(), ">")
          .put(EQUALS.getFunction(), "==")
          .put(NOT_EQUALS.getFunction(), "!=")
          .put(IN.getFunction(), "in")
          .put(ADD.getFunction(), "+")
          .put(SUBTRACT.getFunction(), "-")
          .put(MULTIPLY.getFunction(), "*")
          .put(DIVIDE.getFunction(), "/")
          .put(MODULO.getFunction(), "%")
          .buildOrThrow();

  /** Lookup an operator by its mangled name (ex: _&&_), as used within the AST. */
  public static Optional<Operator> findReverse(String op) {
    return Optional.ofNullable(REVERSE_OPERATORS.get(op));
  }

  /**
   * Returns the precedence of the operator.
   *
   * @return Integer value describing precedence. Higher value means higher precedence. Returns 0 if
   *     the operator is not found.
   */
  public static int lookupPrecedence(String op) {
    return PRECEDENCES.getOrDefault(op, 0);
  }

  /** Looks up the associated unary operator based on its function name (Ex: !_ -> !) */
  public static Optional<String> lookupUnaryOperator(String op) {
    return Optional.ofNullable(UNARY_OPERATORS.get(op));
  }

  /** Looks up the associated binary operator based on its function name (Ex: _||_ -> ||) */
  public static Optional<String> lookupBinaryOperator(String op) {
    return Optional.ofNullable(BINARY_OPERATORS.get(op));
  }
}
