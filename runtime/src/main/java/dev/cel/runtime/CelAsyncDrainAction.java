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

package dev.cel.runtime;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import com.google.auto.value.AutoValue;
import com.google.errorprone.annotations.Immutable;
import java.time.Duration;

/** Dictates what asynchronous evaluation should do after inspecting completions. */
@AutoValue
@Immutable
public abstract class CelAsyncDrainAction {

  CelAsyncDrainAction() {}

  /** Indicates that the AST should be re-evaluated immediately. */
  public abstract boolean shouldReevaluate();

  /**
   * Indicates how long the evaluator should wait for additional completions before deciding to
   * re-evaluate. A duration of ZERO with reevaluate=false means wait indefinitely for the next
   * completion.
   */
  public abstract Duration waitDuration();

  public static CelAsyncDrainAction waitDuration(Duration duration) {
    checkNotNull(duration);
    checkArgument(!duration.isNegative(), "duration must not be negative");
    if (duration.isZero()) {
      return reevaluate();
    }
    return new AutoValue_CelAsyncDrainAction(false, duration);
  }

  public static CelAsyncDrainAction reevaluate() {
    return new AutoValue_CelAsyncDrainAction(true, Duration.ZERO);
  }

  public static CelAsyncDrainAction waitForMore() {
    return new AutoValue_CelAsyncDrainAction(false, Duration.ZERO);
  }
}
