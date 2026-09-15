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

package dev.cel.parser;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Strings.isNullOrEmpty;

import com.google.auto.value.AutoValue;
import com.google.errorprone.annotations.CheckReturnValue;
import com.google.errorprone.annotations.Immutable;

/** Describes a function signature to match and the {@link CelMacroExpander} to apply. */
@AutoValue
@Immutable
public abstract class CelMacro implements Comparable<CelMacro> {
  // Package-private default constructor to prevent extensions outside of the codebase.
  CelMacro() {}

  /** Returns the function name for this macro. */
  public abstract String getFunction();

  /** Returns the number of arguments this macro expects. For variadic macros, this is 0. */
  public abstract int getArgumentCount();

  abstract boolean getReceiverStyle();

  /** True if this macro is receiver-style, false if it is global. */
  public final boolean isReceiverStyle() {
    return getReceiverStyle();
  }

  /** Returns the unique string used to identify this macro. */
  public abstract String getKey();

  abstract boolean getVariadic();

  /** Returns true if this macro accepts any number of arguments, false otherwise. */
  public final boolean isVariadic() {
    return getVariadic();
  }

  /** Returns the expander for this macro. */
  public abstract CelMacroExpander getExpander();

  @Override
  public final int compareTo(CelMacro other) {
    if (other == null) {
      return 1;
    }
    int diff = getFunction().compareTo(other.getFunction());
    if (diff != 0) {
      return diff;
    }
    diff = Boolean.compare(!isVariadic(), !other.isVariadic());
    if (diff != 0) {
      return diff;
    }
    if (!isVariadic()) {
      diff = Integer.compare(getArgumentCount(), other.getArgumentCount());
      if (diff != 0) {
        return diff;
      }
    }
    return Boolean.compare(isReceiverStyle(), other.isReceiverStyle());
  }

  @Override
  public final String toString() {
    return getKey();
  }

  @Override
  public final int hashCode() {
    return getKey().hashCode();
  }

  @Override
  public final boolean equals(Object other) {
    return other instanceof CelMacro && getKey().equals(((CelMacro) other).getKey());
  }

  static Builder newBuilder() {
    return new AutoValue_CelMacro.Builder();
  }

  /** Creates a new global macro that accepts a fixed number of arguments. */
  public static CelMacro newGlobalMacro(String function, int argCount, CelMacroExpander expander) {
    checkArgument(!isNullOrEmpty(function));
    checkArgument(argCount >= 0);
    checkNotNull(expander);
    return newBuilder()
        .setFunction(function)
        .setArgumentCount(argCount)
        .setReceiverStyle(false)
        .setKey(formatKey(function, argCount, false))
        .setVariadic(false)
        .setExpander(expander)
        .build();
  }

  /** Creates a new global macro that accepts a variable number of arguments. */
  public static CelMacro newGlobalVarArgMacro(String function, CelMacroExpander expander) {
    checkArgument(!isNullOrEmpty(function));
    checkNotNull(expander);
    return newBuilder()
        .setFunction(function)
        .setArgumentCount(0)
        .setReceiverStyle(false)
        .setKey(formatVarArgKey(function, false))
        .setVariadic(true)
        .setExpander(expander)
        .build();
  }

  /** Creates a new receiver-style macro that accepts a fixed number of arguments. */
  public static CelMacro newReceiverMacro(
      String function, int argCount, CelMacroExpander expander) {
    checkArgument(!isNullOrEmpty(function));
    checkArgument(argCount >= 0);
    checkNotNull(expander);
    return newBuilder()
        .setFunction(function)
        .setArgumentCount(argCount)
        .setReceiverStyle(true)
        .setKey(formatKey(function, argCount, true))
        .setVariadic(false)
        .setExpander(expander)
        .build();
  }

  /** Creates a new receiver-style macro that accepts a variable number of arguments. */
  public static CelMacro newReceiverVarArgMacro(String function, CelMacroExpander expander) {
    checkArgument(!isNullOrEmpty(function));
    checkNotNull(expander);
    return newBuilder()
        .setFunction(function)
        .setArgumentCount(0)
        .setReceiverStyle(true)
        .setKey(formatVarArgKey(function, true))
        .setVariadic(true)
        .setExpander(expander)
        .build();
  }

  static String formatKey(String function, int argCount, boolean receiverStyle) {
    checkArgument(!isNullOrEmpty(function));
    checkArgument(argCount >= 0);
    return function + ":" + argCount + ":" + receiverStyle;
  }

  static String formatVarArgKey(String function, boolean receiverStyle) {
    checkArgument(!isNullOrEmpty(function));
    return function + ":*:" + receiverStyle;
  }

  @AutoValue.Builder
  abstract static class Builder {

    abstract Builder setFunction(String function);

    abstract Builder setArgumentCount(int argumentCount);

    abstract Builder setReceiverStyle(boolean receiverStyle);

    abstract Builder setKey(String key);

    abstract Builder setVariadic(boolean variadic);

    abstract Builder setExpander(CelMacroExpander expander);

    @CheckReturnValue
    abstract CelMacro build();
  }
}
