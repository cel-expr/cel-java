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

import com.google.errorprone.annotations.Immutable;
import java.time.Duration;
import java.util.List;

/**
 * Controls when asynchronous evaluation re-evaluates the AST after async completions.
 *
 * <p>The evaluator consults the strategy each time completions are received.
 */
@Immutable
public interface CelAsyncDrainStrategy {

  /**
   * Evaluates the current state of asynchronous evaluation and determines the next step.
   *
   * @param completedBatch The batch of async call completions accumulated so far in this drain
   *     cycle.
   * @param activeCallsCount The number of async calls currently launched but unresolved.
   */
  CelAsyncDrainAction nextAction(List<CelAsyncCall> completedBatch, int activeCallsCount);

  /**
   * Re-evaluates after a debounce window after the first completion, batching completions that
   * complete at roughly the same time.
   */
  static CelAsyncDrainStrategy drainReady(Duration debounce) {
    return new DrainReadyStrategy(debounce);
  }

  /** Re-evaluates with the default debounce window of 100 microseconds. */
  static CelAsyncDrainStrategy drainReady() {
    return drainReady(Duration.ofNanos(100_000));
  }

  /** Re-evaluates immediately as soon as any single call completes. */
  static CelAsyncDrainStrategy drainNone() {
    return (completed, active) -> {
      checkNotNull(completed, "completedBatch must not be null");
      checkArgument(active >= 0, "activeCallsCount must be non-negative: %s", active);
      return active == 0 || !completed.isEmpty()
          ? CelAsyncDrainAction.reevaluate()
          : CelAsyncDrainAction.waitForMore();
    };
  }

  /** Waits for all currently pending calls to finish before re-evaluating. */
  static CelAsyncDrainStrategy drainAll() {
    return (completed, active) -> {
      checkNotNull(completed, "completedBatch must not be null");
      checkArgument(active >= 0, "activeCallsCount must be non-negative: %s", active);
      return active == 0 ? CelAsyncDrainAction.reevaluate() : CelAsyncDrainAction.waitForMore();
    };
  }

  /** Internal implementation of the drain ready strategy with configurable debounce duration. */
  @Immutable
  final class DrainReadyStrategy implements CelAsyncDrainStrategy {
    private final Duration debounce;

    DrainReadyStrategy(Duration debounce) {
      this.debounce = checkNotNull(debounce);
      checkArgument(!debounce.isNegative(), "debounce duration must not be negative");
    }

    @Override
    public CelAsyncDrainAction nextAction(List<CelAsyncCall> completedBatch, int activeCallsCount) {
      checkNotNull(completedBatch, "completedBatch must not be null");
      checkArgument(
          activeCallsCount >= 0, "activeCallsCount must be non-negative: %s", activeCallsCount);
      if (activeCallsCount == 0) {
        return CelAsyncDrainAction.reevaluate();
      }
      if (completedBatch.isEmpty()) {
        return CelAsyncDrainAction.waitForMore();
      }
      if (debounce.isZero()) {
        return CelAsyncDrainAction.reevaluate();
      }
      return CelAsyncDrainAction.waitDuration(debounce);
    }
  }
}
