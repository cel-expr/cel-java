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

package dev.cel.runtime.planner;

import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.NANOSECONDS;

import com.google.common.collect.ImmutableList;
import com.google.errorprone.annotations.concurrent.GuardedBy;
import dev.cel.runtime.CelAsyncCall;
import dev.cel.runtime.CelAsyncDrainAction;
import dev.cel.runtime.CelAsyncEvaluationOptions;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import org.jspecify.annotations.Nullable;

/**
 * Coordinates asynchronous call completion notifications, debouncing, and re-evaluation dispatch.
 */
final class AsyncCompletionCoordinator {
  private final CelAsyncEvaluationOptions options;
  private final AsyncGate gate;
  private final Executor executor;

  @GuardedBy("this")
  private final List<CelAsyncCall> completedBatch = new ArrayList<>();

  @GuardedBy("this")
  private @Nullable Runnable continuation;

  @GuardedBy("this")
  private @Nullable ScheduledFuture<?> debounceTimer;

  @GuardedBy("this")
  private boolean isWaiting = false;

  AsyncCompletionCoordinator(CelAsyncEvaluationOptions options, AsyncGate gate, Executor executor) {
    this.options = options;
    this.gate = gate;
    this.executor = executor;
  }

  synchronized boolean hasPendingBatch() {
    return !completedBatch.isEmpty();
  }

  synchronized void notifyCallCompleted(CelAsyncCall call) {
    completedBatch.add(call);
    if (!isWaiting) {
      return;
    }

    CelAsyncDrainAction action =
        options
            .drainStrategy()
            .nextAction(ImmutableList.copyOf(completedBatch), gate.activeCount());
    if (action.shouldReevaluate()) {
      triggerContinuation();
    } else if (action.waitDuration().isZero()) {
      // Indefinite wait for next completion
    } else {
      scheduleDebounce(action.waitDuration().toNanos());
    }
  }

  synchronized void waitForCompletions(Runnable continuationCallback) {
    this.continuation = requireNonNull(continuationCallback);
    this.isWaiting = true;

    CelAsyncDrainAction action =
        options
            .drainStrategy()
            .nextAction(ImmutableList.copyOf(completedBatch), gate.activeCount());
    if (action.shouldReevaluate()) {
      triggerContinuation();
    } else if (!action.waitDuration().isZero()) {
      scheduleDebounce(action.waitDuration().toNanos());
    }
  }

  synchronized void cancel() {
    cancelDebounceTimer();
    isWaiting = false;
    continuation = null;
    completedBatch.clear();
  }

  @GuardedBy("this")
  private synchronized void scheduleDebounce(long nanos) {
    if (debounceTimer == null) {
      ScheduledExecutorService scheduler = options.resolveScheduledExecutorService();
      debounceTimer =
          scheduler.schedule(
              () -> {
                synchronized (AsyncCompletionCoordinator.this) {
                  if (isWaiting) {
                    triggerContinuation();
                  }
                }
              },
              nanos,
              NANOSECONDS);
    }
  }

  @GuardedBy("this")
  private synchronized void triggerContinuation() {
    cancelDebounceTimer();
    isWaiting = false;
    completedBatch.clear();
    Runnable run = continuation;
    continuation = null;
    executor.execute(run);
  }

  @GuardedBy("this")
  private synchronized void cancelDebounceTimer() {
    if (debounceTimer != null) {
      debounceTimer.cancel(false);
      debounceTimer = null;
    }
  }

  synchronized boolean isWaiting() {
    return isWaiting;
  }

  synchronized boolean hasContinuation() {
    return continuation != null;
  }

  synchronized boolean hasScheduledDebounceTimer() {
    return debounceTimer != null;
  }
}
