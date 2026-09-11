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

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static java.util.concurrent.TimeUnit.NANOSECONDS;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import javax.annotation.concurrent.ThreadSafe;
import com.google.errorprone.annotations.concurrent.GuardedBy;
import dev.cel.runtime.CelAsyncCall;
import dev.cel.runtime.CelAsyncDrainAction;
import dev.cel.runtime.CelAsyncDrainStrategy;
import dev.cel.runtime.CelAsyncEvaluationOptions;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * Coordinates asynchronous call completion notifications, debouncing, and re-evaluation dispatch.
 */
@ThreadSafe
final class AsyncCompletionCoordinator {

  /** Represents the result of attempting to wait for asynchronous completions. */
  enum WaitResult {
    /** Continuation registered; execution will resume asynchronously when work arrives. */
    REGISTERED,
    /** Drain strategy satisfied immediately; caller should reevaluate now via loop trampoline. */
    REEVALUATE_NOW,
    /** No calls in flight and no completions pending; evaluation cannot make further progress. */
    NO_OUTSTANDING_WORK,
    /** The coordinator has been cancelled. */
    CANCELLED
  }

  // CEL-Internal-4
  private final Object lock;

  private final CelAsyncEvaluationOptions options;
  private final AsyncGate gate;
  private final Executor continuationExecutor;

  // CEL-Internal-4
  private final Consumer<Throwable> failureCallback;

  @GuardedBy("lock")
  private final List<CelAsyncCall> completedBatch;

  @GuardedBy("lock")
  private int inFlightCount;

  @GuardedBy("lock")
  private boolean isWaiting;

  @GuardedBy("lock")
  private boolean isCancelled;

  @GuardedBy("lock")
  private @Nullable Runnable continuation;

  @GuardedBy("lock")
  private @Nullable ScheduledFuture<?> debounceTimer;

  private final ThreadLocal<Deque<Runnable>> continuationTrampoline;

  @GuardedBy("lock")
  private long cycleId;

  @GuardedBy("lock")
  private long debounceGeneration;

  @GuardedBy("lock")
  private boolean failureReported;

  static AsyncCompletionCoordinator create(
      CelAsyncEvaluationOptions options,
      AsyncGate gate,
      Executor continuationExecutor,
      Consumer<Throwable> failureCallback) {
    return new AsyncCompletionCoordinator(options, gate, continuationExecutor, failureCallback);
  }

  /** Notifies the coordinator that an asynchronous call has been initiated. */
  void callStarted() {
    synchronized (lock) {
      if (!isCancelled) {
        inFlightCount++;
      }
    }
  }

  /**
   * Notifies the coordinator that an asynchronous call has finished.
   *
   * <p>Decrements in-flight tracking, appends the call to the current batch, and evaluates the
   * configured {@link CelAsyncDrainStrategy} if currently waiting.
   */
  void callCompleted(CelAsyncCall call) {
    checkNotNull(call, "call must not be null");
    ImmutableList<CelAsyncCall> batchSnapshot;
    int inFlightSnapshot;
    long currentCycleId;
    long currentGen;

    synchronized (lock) {
      if (isCancelled) {
        return;
      }
      checkState(inFlightCount > 0, "callCompleted called with no calls in flight");
      completedBatch.add(call);
      inFlightCount--;
      if (!isWaiting) {
        return;
      }
      batchSnapshot = ImmutableList.copyOf(completedBatch);
      inFlightSnapshot = inFlightCount;
      currentCycleId = cycleId;
      currentGen = ++debounceGeneration;
    }

    CelAsyncDrainAction action;
    try {
      action =
          checkNotNull(
              options.drainStrategy().nextAction(batchSnapshot, inFlightSnapshot),
              "drainStrategy must not return null");
    } catch (Throwable t) {
      failAndCancel(t);
      return;
    }
    applyDrainAction(action, currentCycleId, currentGen, inFlightSnapshot);
  }

  /**
   * Waits for pending asynchronous completions or triggers immediate re-evaluation.
   *
   * @param continuationCallback callback invoked when the drain strategy allows re-evaluation.
   * @return {@link WaitResult} indicating how the caller should proceed.
   */
  WaitResult waitForCompletions(Runnable continuationCallback) {
    checkNotNull(continuationCallback, "continuationCallback must not be null");
    ImmutableList<CelAsyncCall> batchSnapshot;
    int inFlightSnapshot;
    long currentCycleId;
    long currentGen;

    synchronized (lock) {
      if (isCancelled) {
        return WaitResult.CANCELLED;
      }
      checkState(!isWaiting, "Coordinator is already waiting for completions");

      if (inFlightCount == 0 && completedBatch.isEmpty()) {
        return WaitResult.NO_OUTSTANDING_WORK;
      }

      this.isWaiting = true;
      this.continuation = continuationCallback;

      if (completedBatch.isEmpty()) {
        return WaitResult.REGISTERED;
      }

      batchSnapshot = ImmutableList.copyOf(completedBatch);
      inFlightSnapshot = inFlightCount;
      currentCycleId = this.cycleId;
      currentGen = ++this.debounceGeneration;
    }

    CelAsyncDrainAction action;
    try {
      action =
          checkNotNull(
              options.drainStrategy().nextAction(batchSnapshot, inFlightSnapshot),
              "drainStrategy must not return null");
    } catch (Throwable t) {
      failAndCancel(t);
      return WaitResult.CANCELLED;
    }

    ScheduledFuture<?> timerToCancel = null;
    boolean reevaluateNow = false;
    synchronized (lock) {
      if (isCancelled) {
        return WaitResult.CANCELLED;
      }
      if (this.cycleId != currentCycleId) {
        return WaitResult.REGISTERED;
      }

      if (this.debounceGeneration == currentGen) {
        if (action.shouldReevaluate() || inFlightCount == 0) {
          timerToCancel = cancelDebounceTimerUnderLock();
          drainAndResetUnderLock();
          reevaluateNow = true;
        }
      } else {
        return WaitResult.REGISTERED;
      }
    }
    if (timerToCancel != null) {
      timerToCancel.cancel(false);
    }
    if (reevaluateNow) {
      return WaitResult.REEVALUATE_NOW;
    }

    Duration waitDuration = action.waitDuration();
    if (!waitDuration.isZero()) {
      scheduleDebounce(waitDuration.toNanos(), currentCycleId, currentGen);
    } else {
      ScheduledFuture<?> timer = null;
      synchronized (lock) {
        if (!isCancelled
            && isWaiting
            && this.cycleId == currentCycleId
            && this.debounceGeneration == currentGen) {
          timer = cancelDebounceTimerUnderLock();
        }
      }
      if (timer != null) {
        timer.cancel(false);
      }
    }
    return WaitResult.REGISTERED;
  }

  private void applyDrainAction(
      CelAsyncDrainAction action, long expectedCycleId, long expectedGen, int inFlightSnapshot) {
    if (action.shouldReevaluate() || inFlightSnapshot == 0) {
      Runnable toRun = null;
      ScheduledFuture<?> timerToCancel = null;
      synchronized (lock) {
        if (!isCancelled
            && isWaiting
            && this.cycleId == expectedCycleId
            && this.debounceGeneration == expectedGen) {
          timerToCancel = cancelDebounceTimerUnderLock();
          toRun = drainAndResetUnderLock();
        }
      }
      if (timerToCancel != null) {
        timerToCancel.cancel(false);
      }
      if (toRun != null) {
        dispatchContinuation(toRun);
      }
      return;
    }

    Duration waitDuration = action.waitDuration();
    if (!waitDuration.isZero()) {
      scheduleDebounce(waitDuration.toNanos(), expectedCycleId, expectedGen);
    } else {
      ScheduledFuture<?> timerToCancel = null;
      synchronized (lock) {
        if (!isCancelled
            && isWaiting
            && this.cycleId == expectedCycleId
            && this.debounceGeneration == expectedGen) {
          timerToCancel = cancelDebounceTimerUnderLock();
        }
      }
      if (timerToCancel != null) {
        timerToCancel.cancel(false);
      }
    }
  }

  private void scheduleDebounce(long nanos, long scheduledCycleId, long scheduledGen) {
    ScheduledExecutorService scheduler;
    try {
      scheduler = options.resolveScheduledExecutorService();
    } catch (Throwable t) {
      failAndCancel(t);
      return;
    }

    ScheduledFuture<?> future;
    try {
      future =
          scheduler.schedule(
              () -> onDebounceFired(scheduledCycleId, scheduledGen), nanos, NANOSECONDS);
    } catch (Throwable t) {
      failAndCancel(t);
      return;
    }

    ScheduledFuture<?> redundantFuture = null;
    synchronized (lock) {
      if (!isCancelled
          && isWaiting
          && this.cycleId == scheduledCycleId
          && this.debounceGeneration == scheduledGen) {
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

  void onDebounceFired(long firedCycleId, long firedGen) {
    Runnable toRun = null;
    ScheduledFuture<?> timerToCancel = null;
    synchronized (lock) {
      if (!isCancelled
          && isWaiting
          && this.cycleId == firedCycleId
          && this.debounceGeneration == firedGen) {
        timerToCancel = cancelDebounceTimerUnderLock();
        toRun = drainAndResetUnderLock();
      }
    }
    if (timerToCancel != null) {
      timerToCancel.cancel(false);
    }
    if (toRun != null) {
      dispatchContinuation(toRun);
    }
  }

  private void dispatchContinuation(@Nullable Runnable run) {
    if (run == null) {
      return;
    }
    Deque<Runnable> queue = continuationTrampoline.get();
    queue.add(run);
    if (queue.size() > 1) {
      return;
    }
    try {
      while (!queue.isEmpty()) {
        Runnable next = queue.peek();
        try {
          continuationExecutor.execute(next);
        } catch (Throwable t) {
          failAndCancel(t);
        } finally {
          queue.poll();
        }
      }
    } finally {
      queue.clear();
      continuationTrampoline.remove();
    }
  }

  /**
   * Cancels the coordinator and associated concurrency gate.
   *
   * <p>Any registered continuation callback is discarded without being executed. The caller or
   * owner of this coordinator is responsible for completing or failing the outer evaluation future
   * itself; calling {@code cancel()} does not notify the continuation callback.
   */
  void cancel() {
    ScheduledFuture<?> timerToCancel;
    synchronized (lock) {
      if (isCancelled) {
        return;
      }
      isCancelled = true;
      isWaiting = false;
      continuation = null;
      completedBatch.clear();
      inFlightCount = 0;
      timerToCancel = cancelDebounceTimerUnderLock();
    }
    if (timerToCancel != null) {
      timerToCancel.cancel(false);
    }
    gate.cancel();
  }

  private void failAndCancel(Throwable t) {
    synchronized (lock) {
      if (failureReported) {
        return;
      }
      failureReported = true;
    }
    cancel();
    failureCallback.accept(t);
  }

  @GuardedBy("lock")
  private @Nullable Runnable drainAndResetUnderLock() {
    cycleId++;
    debounceGeneration++;
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

  @VisibleForTesting
  boolean hasPendingBatch() {
    synchronized (lock) {
      return !completedBatch.isEmpty();
    }
  }

  @VisibleForTesting
  int inFlightCount() {
    synchronized (lock) {
      return inFlightCount;
    }
  }

  @VisibleForTesting
  boolean isWaiting() {
    synchronized (lock) {
      return isWaiting;
    }
  }

  @VisibleForTesting
  boolean hasContinuation() {
    synchronized (lock) {
      return continuation != null;
    }
  }

  @VisibleForTesting
  boolean hasScheduledDebounceTimer() {
    synchronized (lock) {
      return debounceTimer != null;
    }
  }

  @VisibleForTesting
  long cycleId() {
    synchronized (lock) {
      return cycleId;
    }
  }

  @VisibleForTesting
  long debounceGeneration() {
    synchronized (lock) {
      return debounceGeneration;
    }
  }

  @VisibleForTesting
  boolean isCancelled() {
    synchronized (lock) {
      return isCancelled;
    }
  }

  private AsyncCompletionCoordinator(
      CelAsyncEvaluationOptions options,
      AsyncGate gate,
      Executor continuationExecutor,
      Consumer<Throwable> failureCallback) {
    this.options = checkNotNull(options, "options must not be null");
    this.gate = checkNotNull(gate, "gate must not be null");
    this.continuationExecutor =
        checkNotNull(continuationExecutor, "continuationExecutor must not be null");
    this.failureCallback = checkNotNull(failureCallback, "failureCallback must not be null");
    this.lock = new Object();
    this.completedBatch = new ArrayList<>();
    this.continuationTrampoline =
        new ThreadLocal<Deque<Runnable>>() {
          @Override
          protected Deque<Runnable> initialValue() {
            return new ArrayDeque<>();
          }
        };
    this.isWaiting = false;
    this.isCancelled = false;
    this.failureReported = false;
    this.cycleId = 0;
    this.debounceGeneration = 0;
    this.inFlightCount = 0;
  }
}
