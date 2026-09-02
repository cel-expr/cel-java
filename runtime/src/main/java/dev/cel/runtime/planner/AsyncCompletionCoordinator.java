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
import java.time.Duration;
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
  private final Object lock;
  private final CelAsyncEvaluationOptions options;
  private final AsyncGate gate;
  private final Executor executor;

  @GuardedBy("lock")
  private final List<CelAsyncCall> completedBatch;

  @GuardedBy("lock")
  private @Nullable Runnable continuation;

  @GuardedBy("lock")
  private @Nullable ScheduledFuture<?> debounceTimer;

  @GuardedBy("lock")
  private boolean isWaiting;

  @GuardedBy("lock")
  private boolean isCancelled;

  @GuardedBy("lock")
  private long cycleId;

  AsyncCompletionCoordinator(CelAsyncEvaluationOptions options, AsyncGate gate, Executor executor) {
    this.lock = new Object();
    this.options = requireNonNull(options, "options must not be null");
    this.gate = requireNonNull(gate, "gate must not be null");
    this.executor = requireNonNull(executor, "executor must not be null");
    this.completedBatch = new ArrayList<>();
    this.isWaiting = false;
    this.isCancelled = false;
    this.cycleId = 0;
  }

  boolean hasPendingBatch() {
    synchronized (lock) {
      return !completedBatch.isEmpty();
    }
  }

  void notifyCallCompleted(CelAsyncCall call) {
    requireNonNull(call, "call must not be null");
    ImmutableList<CelAsyncCall> batchSnapshot;
    int activeCount;
    long currentCycleId;

    synchronized (lock) {
      if (isCancelled) {
        return;
      }
      completedBatch.add(call);
      if (!isWaiting) {
        return;
      }
      batchSnapshot = ImmutableList.copyOf(completedBatch);
      activeCount = gate.activeCount();
      currentCycleId = cycleId;
    }

    // Alien code: evaluate drain strategy outside monitor lock
    CelAsyncDrainAction action = options.drainStrategy().nextAction(batchSnapshot, activeCount);
    applyDrainAction(action, currentCycleId);
  }

  void waitForCompletions(Runnable continuationCallback) {
    requireNonNull(continuationCallback, "continuationCallback must not be null");
    ImmutableList<CelAsyncCall> batchSnapshot;
    int activeCount;
    long currentCycleId;

    synchronized (lock) {
      if (isCancelled) {
        throw new IllegalStateException("Coordinator has been cancelled");
      }
      if (isWaiting) {
        throw new IllegalStateException("Coordinator is already waiting for completions");
      }
      this.continuation = continuationCallback;
      this.isWaiting = true;
      this.cycleId++;
      batchSnapshot = ImmutableList.copyOf(completedBatch);
      activeCount = gate.activeCount();
      currentCycleId = this.cycleId;
      if (batchSnapshot.isEmpty() && activeCount > 0) {
        return;
      }
    }

    // Alien code: evaluate drain strategy outside monitor lock
    CelAsyncDrainAction action = options.drainStrategy().nextAction(batchSnapshot, activeCount);
    applyDrainAction(action, currentCycleId);
  }

  private void applyDrainAction(CelAsyncDrainAction action, long currentCycleId) {
    Runnable toRun = null;
    ScheduledFuture<?> timerToCancel = null;
    DebounceRequest debounceRequest = null;

    synchronized (lock) {
      if (!isCancelled && isWaiting && this.cycleId == currentCycleId) {
        if (action.shouldReevaluate()) {
          timerToCancel = cancelDebounceTimerUnderLock();
          toRun = drainAndResetUnderLock();
        } else if (action.waitDuration().isZero()) {
          // Indefinite wait for next completion: cancel any pending timer
          timerToCancel = cancelDebounceTimerUnderLock();
        } else {
          // Sliding window debounce: reset existing timer and reschedule for new wait duration
          timerToCancel = cancelDebounceTimerUnderLock();
          debounceRequest = new DebounceRequest(action.waitDuration(), this.cycleId);
        }
      }
    }

    if (timerToCancel != null) {
      timerToCancel.cancel(false);
    }
    if (toRun != null) {
      executor.execute(toRun);
    } else if (debounceRequest != null) {
      scheduleDebounce(debounceRequest.waitDuration().toNanos(), debounceRequest.cycleId());
    }
  }

  void cancel() {
    ScheduledFuture<?> timerToCancel;
    synchronized (lock) {
      isCancelled = true;
      cycleId++;
      timerToCancel = cancelDebounceTimerUnderLock();
      isWaiting = false;
      continuation = null;
      completedBatch.clear();
    }
    if (timerToCancel != null) {
      timerToCancel.cancel(false);
    }
  }

  private void scheduleDebounce(long nanos, long scheduledCycleId) {
    ScheduledExecutorService scheduler = options.resolveScheduledExecutorService();
    ScheduledFuture<?> future =
        scheduler.schedule(() -> onDebounceFired(scheduledCycleId), nanos, NANOSECONDS);
    ScheduledFuture<?> redundantFuture = null;
    synchronized (lock) {
      if (!isCancelled && isWaiting && this.cycleId == scheduledCycleId) {
        if (debounceTimer != null) {
          redundantFuture = debounceTimer;
        }
        debounceTimer = future;
      } else {
        redundantFuture = future;
      }
    }
    if (redundantFuture != null) {
      redundantFuture.cancel(false);
    }
  }

  void onDebounceFired(long firedCycleId) {
    Runnable toRun = null;
    synchronized (lock) {
      if (!isCancelled && isWaiting && this.cycleId == firedCycleId) {
        debounceTimer = null;
        toRun = drainAndResetUnderLock();
      }
    }
    if (toRun != null) {
      executor.execute(toRun);
    }
  }

  @GuardedBy("lock")
  private @Nullable Runnable drainAndResetUnderLock() {
    cycleId++;
    isWaiting = false;
    completedBatch.clear();
    Runnable run = continuation;
    continuation = null;
    return run;
  }

  @GuardedBy("lock")
  private @Nullable ScheduledFuture<?> cancelDebounceTimerUnderLock() {
    ScheduledFuture<?> timer = debounceTimer;
    debounceTimer = null;
    return timer;
  }

  boolean isWaiting() {
    synchronized (lock) {
      return isWaiting;
    }
  }

  boolean hasContinuation() {
    synchronized (lock) {
      return continuation != null;
    }
  }

  boolean hasScheduledDebounceTimer() {
    synchronized (lock) {
      return debounceTimer != null;
    }
  }

  long cycleId() {
    synchronized (lock) {
      return cycleId;
    }
  }

  private static final class DebounceRequest {
    private final Duration waitDuration;
    private final long cycleId;

    DebounceRequest(Duration waitDuration, long cycleId) {
      this.waitDuration = requireNonNull(waitDuration, "waitDuration must not be null");
      this.cycleId = cycleId;
    }

    Duration waitDuration() {
      return waitDuration;
    }

    long cycleId() {
      return cycleId;
    }
  }
}
