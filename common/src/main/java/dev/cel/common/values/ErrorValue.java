// Copyright 2023 Google LLC
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

package dev.cel.common.values;

import com.google.auto.value.AutoValue;
import dev.cel.common.annotations.Internal;
import dev.cel.common.types.CelType;
import dev.cel.common.types.SimpleType;

/**
 * CelErrorValue represent the intermediate error that occurs during evaluation in the form of Java
 * exception. This is used to capture the error that occurs during a non-strict evaluation, which
 * may or may not be propagated to the caller.
 *
 * <p>CEL Library Internals. Do Not Use.
 */
@AutoValue
@AutoValue.CopyAnnotations
@Internal
@SuppressWarnings(
    "Immutable") // Exception is technically not immutable as the stacktrace is malleable.
public abstract class ErrorValue extends CelValue {

  public abstract long exprId();

  @Override
  public abstract RuntimeException value();

  @Override
  public boolean isZeroValue() {
    return false;
  }

  @Override
  public CelType celType() {
    return SimpleType.ERROR;
  }

  public static ErrorValue create(long exprId, RuntimeException value) {
    return new AutoValue_ErrorValue(exprId, value);
  }

  ErrorValue() {}
}
