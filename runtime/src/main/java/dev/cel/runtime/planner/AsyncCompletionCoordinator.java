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
import com.google.errorprone.annotations.CheckReturnValue;
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
    /**
     * No calls in flight and no completions pending; evaluation cannot make further progress.
     *
     * <p>Liveness fail-safe for a caller that waits with nothing left to wake it. Callers must
     * treat this as an error rather than as a completed evaluation.
     */
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
  private boolean isWaiting;

  @GuardedBy("lock")
  private boolean isCancelled;

  @GuardedBy("lock")
  private @Nullable Runnable continuation;

  @GuardedBy("lock")
  private @Nullable ScheduledFuture<?> debounceTimer;

  private final ThreadLocal<Deque<Runnable>> continuationTrampoline;

  /** Monotonic staleness token bumped on every drain and snapshot. */
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

  /**
   * Notifies the coordinator that an asynchronous call has finished.
   *
   * <p>Releases the concurrency permit in {@link AsyncGate}, appends the call to the current batch,
   * and evaluates the configured {@link CelAsyncDrainStrategy} if currently waiting.
   */
  void callCompleted(CelAsyncCall call) {
    checkNotNull(call, "call must not be null");
    // The unbalanced flag is acted on after the lock is released, because failAndCancel() runs the
    // user-supplied failure callback, which must never execute while holding the coordinator lock.
    boolean unbalanced = false;
    CompletionSnapshot snapshot = null;

    synchronized (lock) {
      // Check cancellation before activeCount() so late-finishing calls do not trip the unbalanced
      // fail-safe on an evaluation that was already cancelled.
      if (isCancelled) {
        gate.release();
        return;
      }
      // Check activeCount() <= 0 BEFORE release() to detect unbalanced completion misuse.
      if (gate.activeCount() <= 0) {
        unbalanced = true;
      } else {
        gate.release();
        completedBatch.add(call);
        if (!isWaiting) {
          return;
        }
        // Take inFlight AFTER release() to capture remaining active calls for the drain strategy.
        snapshot =
            new CompletionSnapshot(
                ImmutableList.copyOf(completedBatch), gate.activeCount(), ++debounceGeneration);
      }
    }

    if (unbalanced) {
      failAndCancel(new IllegalStateException("callCompleted called with no active calls"));
      return;
    }

    CelAsyncDrainAction action;
    try {
      action =
          checkNotNull(
              options.drainStrategy().nextAction(snapshot.batch, snapshot.inFlight),
              "drainStrategy must not return null");
    } catch (Throwable t) {
      failAndCancel(t);
      return;
    }
    applyDrainAction(action, snapshot.debounceGeneration);
  }

  /**
   * Waits for pending asynchronous completions or triggers immediate re-evaluation.
   *
   * @param continuationCallback callback invoked when the drain strategy allows re-evaluation.
   * @return {@link WaitResult} indicating how the caller should proceed.
   */
  @CheckReturnValue
  WaitResult waitForCompletions(Runnable continuationCallback) {
    checkNotNull(continuationCallback, "continuationCallback must not be null");
    CompletionSnapshot snapshot;

    synchronized (lock) {
      if (isCancelled) {
        return WaitResult.CANCELLED;
      }
      checkState(!isWaiting, "Coordinator is already waiting for completions");

      // Liveness fail-safe: no completion can ever arrive to resume the continuation.
      if (gate.activeCount() == 0 && completedBatch.isEmpty()) {
        return WaitResult.NO_OUTSTANDING_WORK;
      }

      this.isWaiting = true;
      this.continuation = continuationCallback;

      if (completedBatch.isEmpty()) {
        return WaitResult.REGISTERED;
      }

      snapshot =
          new CompletionSnapshot(
              ImmutableList.copyOf(completedBatch), gate.activeCount(), ++this.debounceGeneration);
    }

    CelAsyncDrainAction action;
    try {
      action =
          checkNotNull(
              options.drainStrategy().nextAction(snapshot.batch, snapshot.inFlight),
              "drainStrategy must not return null");
    } catch (Throwable t) {
      failAndCancel(t);
      return WaitResult.CANCELLED;
    }

    boolean reevaluateNow = false;
    ScheduledFuture<?> timerToCancel = null;
    synchronized (lock) {
      if (isCancelled) {
        return WaitResult.CANCELLED;
      }

      if (this.debounceGeneration == snapshot.debounceGeneration) {
        // If strategy says reevaluate, OR if activeCount is 0 (escape hatch preventing indefinite
        // stall with custom drain strategies when all in-flight calls finish).
        if (action.shouldReevaluate() || gate.activeCount() == 0) {
          // The continuation in DrainResult is intentionally not dispatched here because
          // WaitResult.REEVALUATE_NOW instructs the calling thread to re-evaluate synchronously.
          timerToCancel = drainAndResetUnderLock().timer;
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
    if (waitDuration.isZero()) {
      return WaitResult.REGISTERED;
    }
    long delayNanos;
    try {
      delayNanos = waitDuration.toNanos();
    } catch (ArithmeticException e) {
      failAndCancel(e);
      return WaitResult.CANCELLED;
    }
    return scheduleDebounce(delayNanos, snapshot.debounceGeneration)
        ? WaitResult.REGISTERED
        : WaitResult.CANCELLED;
  }

  private void applyDrainAction(CelAsyncDrainAction action, long expectedGen) {
    DrainResult drainResult = null;
    synchronized (lock) {
      // Bail out if a newer generation has superseded this action.
      if (!isCurrentUnderLock(expectedGen)) {
        return;
      }
      // Live read: check gate.activeCount() == 0 under lock so we do not schedule an unnecessary
      // timer if all remaining calls completed while evaluating nextAction().
      if (action.shouldReevaluate() || gate.activeCount() == 0) {
        drainResult = drainAndResetUnderLock();
      }
    }
    if (drainResult != null) {
      if (drainResult.timer != null) {
        drainResult.timer.cancel(false);
      }
      if (drainResult.continuation != null) {
        dispatchContinuation(drainResult.continuation);
      }
      return;
    }

    Duration waitDuration = action.waitDuration();
    if (!waitDuration.isZero()) {
      long delayNanos;
      try {
        delayNanos = waitDuration.toNanos();
      } catch (ArithmeticException e) {
        failAndCancel(e);
        return;
      }
      scheduleDebounce(delayNanos, expectedGen);
      return;
    }

    ScheduledFuture<?> timerToCancel = null;
    synchronized (lock) {
      if (isCurrentUnderLock(expectedGen)) {
        timerToCancel = cancelDebounceTimerUnderLock();
      }
    }
    if (timerToCancel != null) {
      timerToCancel.cancel(false);
    }
  }

  /**
   * Schedules a debounce timer that fires {@link #onDebounceFired} after {@code nanos}.
   *
   * @return false if the timer could not be scheduled, in which case the coordinator has already
   *     been failed and cancelled.
   */
  private boolean scheduleDebounce(long nanos, long scheduledGen) {
    synchronized (lock) {
      if (isCancelled) {
        return false;
      }
      if (!isCurrentUnderLock(scheduledGen)) {
        return true;
      }
    }
    ScheduledFuture<?> future;
    try {
      future =
          options
              .resolveScheduledExecutorService()
              .schedule(() -> onDebounceFired(scheduledGen), nanos, NANOSECONDS);
    } catch (Throwable t) {
      boolean shouldFail;
      synchronized (lock) {
        if (isCancelled) {
          return false;
        }
        shouldFail = isCurrentUnderLock(scheduledGen);
      }
      if (shouldFail) {
        failAndCancel(t);
        return false;
      }
      return true;
    }

    ScheduledFuture<?> redundantFuture = null;
    synchronized (lock) {
      if (isCurrentUnderLock(scheduledGen)) {
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
    return true;
  }

  /**
   * Returns true if the coordinator is still actively waiting on the debounce generation that
   * produced the in-flight action, meaning the action is not stale.
   */
  @GuardedBy("lock")
  private boolean isCurrentUnderLock(long expectedGen) {
    return !isCancelled && isWaiting && this.debounceGeneration == expectedGen;
  }

  @VisibleForTesting
  void onDebounceFired(long firedGen) {
    DrainResult drainResult = null;
    synchronized (lock) {
      if (isCurrentUnderLock(firedGen)) {
        drainResult = drainAndResetUnderLock();
      }
    }
    if (drainResult != null) {
      if (drainResult.timer != null) {
        drainResult.timer.cancel(false);
      }
      if (drainResult.continuation != null) {
        dispatchContinuation(drainResult.continuation);
      }
    }
  }

  private void dispatchContinuation(Runnable run) {
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
          break;
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
      timerToCancel = cancelDebounceTimerUnderLock();
    }
    if (timerToCancel != null) {
      timerToCancel.cancel(false);
    }
    gate.cancel();
  }

  @SuppressWarnings("ReferenceEquality") // Identity comparison avoids Throwable self-suppression.
  private void failAndCancel(Throwable t) {
    synchronized (lock) {
      if (failureReported) {
        return;
      }
      failureReported = true;
    }
    cancel();
    try {
      failureCallback.accept(t);
    } catch (Throwable callbackFailure) {
      if (t != callbackFailure) {
        t.addSuppressed(callbackFailure);
      }
    }
  }

  @GuardedBy("lock")
  @CheckReturnValue
  private DrainResult drainAndResetUnderLock() {
    debounceGeneration++;
    isWaiting = false;
    completedBatch.clear();
    Runnable run = continuation;
    continuation = null;
    ScheduledFuture<?> timer = cancelDebounceTimerUnderLock();
    return new DrainResult(run, timer);
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

  private static final class CompletionSnapshot {
    final ImmutableList<CelAsyncCall> batch;
    final int inFlight;
    final long debounceGeneration;

    private CompletionSnapshot(
        ImmutableList<CelAsyncCall> batch, int inFlight, long debounceGeneration) {
      this.batch = checkNotNull(batch, "batch must not be null");
      this.inFlight = inFlight;
      this.debounceGeneration = debounceGeneration;
    }
  }

  private static final class DrainResult {
    private final @Nullable Runnable continuation;
    private final @Nullable ScheduledFuture<?> timer;

    private DrainResult(@Nullable Runnable continuation, @Nullable ScheduledFuture<?> timer) {
      this.continuation = continuation;
      this.timer = timer;
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
  }
}
