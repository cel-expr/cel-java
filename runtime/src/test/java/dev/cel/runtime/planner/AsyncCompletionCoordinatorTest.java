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

import static com.google.common.truth.Truth.assertThat;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.Assert.assertThrows;

import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import dev.cel.runtime.CelAsyncCall;
import dev.cel.runtime.CelAsyncDrainStrategy;
import dev.cel.runtime.CelAsyncEvaluationOptions;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Delayed;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(TestParameterInjector.class)
public final class AsyncCompletionCoordinatorTest {

  private static final CelAsyncCall DUMMY_CALL =
      new CelAsyncCall() {
        @Override
        public long callId() {
          return 1L;
        }

        @Override
        public long exprId() {
          return 10L;
        }

        @Override
        public String functionName() {
          return "testFn";
        }

        @Override
        public String overloadId() {
          return "testFn_overload";
        }
      };

  @Test
  public void notifyCallCompleted_whenWaitingWithPendingActiveCalls_schedulesDebounceTimer() {
    ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1);
    try {
      CelAsyncEvaluationOptions options =
          CelAsyncEvaluationOptions.builder()
              .setDrainStrategy(CelAsyncDrainStrategy.drainReady(Duration.ofMinutes(10)))
              .setScheduledExecutorService(scheduler)
              .build();
      AsyncGate gate = new AsyncGate(1);
      gate.dispatch(Runnable::run, () -> {});

      AsyncCompletionCoordinator coordinator =
          new AsyncCompletionCoordinator(options, gate, Runnable::run);
      AtomicBoolean continuationRan = new AtomicBoolean(false);

      coordinator.waitForCompletions(() -> continuationRan.set(true));

      coordinator.notifyCallCompleted(DUMMY_CALL);

      assertThat(continuationRan.get()).isFalse();
      assertThat(coordinator.isWaiting()).isTrue();
      assertThat(coordinator.hasScheduledDebounceTimer()).isTrue();
      assertThat(scheduler.getQueue()).isNotEmpty();
    } finally {
      scheduler.shutdownNow();
    }
  }

  @Test
  public void
      notifyCallCompleted_whenWaitingWithDrainAllStrategy_waitsIndefinitelyWithoutSchedulingTimer() {
    ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1);
    try {
      CelAsyncEvaluationOptions options =
          CelAsyncEvaluationOptions.builder()
              .setDrainStrategy(CelAsyncDrainStrategy.drainAll())
              .setScheduledExecutorService(scheduler)
              .build();
      AsyncGate gate = new AsyncGate(2);
      gate.dispatch(Runnable::run, () -> {});
      gate.dispatch(Runnable::run, () -> {});

      AsyncCompletionCoordinator coordinator =
          new AsyncCompletionCoordinator(options, gate, Runnable::run);
      AtomicBoolean continuationRan = new AtomicBoolean(false);

      coordinator.waitForCompletions(() -> continuationRan.set(true));

      gate.releasePermit(Runnable::run);
      coordinator.notifyCallCompleted(DUMMY_CALL);

      assertThat(continuationRan.get()).isFalse();
      assertThat(coordinator.isWaiting()).isTrue();
      assertThat(coordinator.hasScheduledDebounceTimer()).isFalse();
      assertThat(scheduler.getQueue()).isEmpty();
    } finally {
      scheduler.shutdownNow();
    }
  }

  @Test
  public void notifyCallCompleted_whenDebounceTimerPending_resetsDebounceTimerForSlidingWindow() {
    ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1);
    try {
      CelAsyncEvaluationOptions options =
          CelAsyncEvaluationOptions.builder()
              .setDrainStrategy(CelAsyncDrainStrategy.drainReady(Duration.ofMinutes(10)))
              .setScheduledExecutorService(scheduler)
              .build();
      AsyncGate gate = new AsyncGate(2);
      gate.dispatch(Runnable::run, () -> {});
      gate.dispatch(Runnable::run, () -> {});

      AsyncCompletionCoordinator coordinator =
          new AsyncCompletionCoordinator(options, gate, Runnable::run);
      AtomicBoolean continuationRan = new AtomicBoolean(false);

      coordinator.waitForCompletions(() -> continuationRan.set(true));

      // First completion schedules timer 1
      coordinator.notifyCallCompleted(DUMMY_CALL);
      ScheduledFuture<?> firstTimer = (ScheduledFuture<?>) scheduler.getQueue().peek();
      assertThat(firstTimer).isNotNull();
      assertThat(firstTimer.isCancelled()).isFalse();

      // Second completion arrives while timer 1 is pending: sliding window cancels timer 1 and
      // reschedules
      coordinator.notifyCallCompleted(DUMMY_CALL);
      assertThat(firstTimer.isCancelled()).isTrue();
      assertThat(coordinator.hasScheduledDebounceTimer()).isTrue();
      assertThat(continuationRan.get()).isFalse();
    } finally {
      scheduler.shutdownNow();
    }
  }

  @Test
  public void onDebounceFired_whenWaiting_triggersContinuation() {
    ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1);
    try {
      CelAsyncEvaluationOptions options =
          CelAsyncEvaluationOptions.builder()
              .setDrainStrategy(CelAsyncDrainStrategy.drainReady(Duration.ofMinutes(10)))
              .setScheduledExecutorService(scheduler)
              .build();
      AsyncGate gate = new AsyncGate(1);
      gate.dispatch(Runnable::run, () -> {});

      AsyncCompletionCoordinator coordinator =
          new AsyncCompletionCoordinator(options, gate, Runnable::run);
      coordinator.notifyCallCompleted(DUMMY_CALL);

      AtomicBoolean continuationRan = new AtomicBoolean(false);
      coordinator.waitForCompletions(() -> continuationRan.set(true));

      ScheduledFuture<?> scheduledTask =
          requireNonNull((ScheduledFuture<?>) scheduler.getQueue().peek());

      ((Runnable) scheduledTask).run();

      assertThat(continuationRan.get()).isTrue();
      assertThat(coordinator.isWaiting()).isFalse();
      assertThat(coordinator.hasPendingBatch()).isFalse();
      assertThat(coordinator.hasScheduledDebounceTimer()).isFalse();
      assertThat(coordinator.hasContinuation()).isFalse();
    } finally {
      scheduler.shutdownNow();
    }
  }

  @Test
  public void cancel_cancelsDebounceTimerAndPreventsContinuation() {
    AtomicBoolean mayInterruptArg = new AtomicBoolean(true);
    ScheduledThreadPoolExecutor scheduler =
        new ScheduledThreadPoolExecutor(1) {
          @Override
          public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            ScheduledFuture<?> task = super.schedule(command, delay, unit);
            return new CapturingScheduledFuture<>(task, mayInterruptArg);
          }
        };
    try {
      CelAsyncEvaluationOptions options =
          CelAsyncEvaluationOptions.builder()
              .setDrainStrategy(CelAsyncDrainStrategy.drainReady(Duration.ofMinutes(10)))
              .setScheduledExecutorService(scheduler)
              .build();
      AsyncGate gate = new AsyncGate(1);
      gate.dispatch(Runnable::run, () -> {});

      AsyncCompletionCoordinator coordinator =
          new AsyncCompletionCoordinator(options, gate, Runnable::run);
      coordinator.notifyCallCompleted(DUMMY_CALL);

      AtomicBoolean continuationRan = new AtomicBoolean(false);
      coordinator.waitForCompletions(() -> continuationRan.set(true));

      ScheduledFuture<?> scheduledTask = (ScheduledFuture<?>) scheduler.getQueue().peek();
      assertThat(scheduledTask).isNotNull();
      assertThat(scheduledTask.isCancelled()).isFalse();

      coordinator.cancel();

      assertThat(coordinator.hasPendingBatch()).isFalse();
      assertThat(coordinator.isWaiting()).isFalse();
      assertThat(coordinator.hasContinuation()).isFalse();
      assertThat(coordinator.hasScheduledDebounceTimer()).isFalse();
      assertThat(scheduledTask.isCancelled()).isTrue();
      assertThat(mayInterruptArg.get()).isFalse();
      assertThat(continuationRan.get()).isFalse();

      // Executing cancelled scheduled task must not trigger the continuation
      ((Runnable) scheduledTask).run();
      assertThat(continuationRan.get()).isFalse();
    } finally {
      scheduler.shutdownNow();
    }
  }

  @Test
  public void staleTimerFromPreviousPass_doesNotTriggerContinuationOnSubsequentPass() {
    ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1);
    try {
      CelAsyncEvaluationOptions options =
          CelAsyncEvaluationOptions.builder()
              .setDrainStrategy(CelAsyncDrainStrategy.drainReady(Duration.ofMinutes(10)))
              .setScheduledExecutorService(scheduler)
              .build();
      AsyncGate gate = new AsyncGate(1);
      gate.dispatch(Runnable::run, () -> {});

      AsyncCompletionCoordinator coordinator =
          new AsyncCompletionCoordinator(options, gate, Runnable::run);

      // Pass 1: start waiting and schedule a debounce timer
      coordinator.notifyCallCompleted(DUMMY_CALL);
      AtomicInteger pass1Count = new AtomicInteger();
      coordinator.waitForCompletions(pass1Count::incrementAndGet);

      ScheduledFuture<?> pass1Timer = (ScheduledFuture<?>) scheduler.getQueue().peek();
      assertThat(pass1Timer).isNotNull();

      // In-flight call completes causing immediate re-evaluation: pass 1 continuation runs
      gate.releasePermit(Runnable::run);
      coordinator.notifyCallCompleted(DUMMY_CALL);
      assertThat(pass1Count.get()).isEqualTo(1);
      assertThat(coordinator.isWaiting()).isFalse();

      // Pass 2 begins
      gate.dispatch(Runnable::run, () -> {});
      AtomicInteger pass2Count = new AtomicInteger();
      coordinator.waitForCompletions(pass2Count::incrementAndGet);
      assertThat(coordinator.isWaiting()).isTrue();

      // Stale timer from Pass 1 executes now
      ((Runnable) pass1Timer).run();

      // Pass 2 must NOT have been triggered by the stale timer
      assertThat(pass2Count.get()).isEqualTo(0);
      assertThat(coordinator.isWaiting()).isTrue();
    } finally {
      scheduler.shutdownNow();
    }
  }

  @Test
  public void waitForCompletions_whenCoordinatorCancelled_throwsIllegalStateException() {
    CelAsyncEvaluationOptions options = CelAsyncEvaluationOptions.builder().build();
    AsyncGate gate = new AsyncGate(1);
    AsyncCompletionCoordinator coordinator =
        new AsyncCompletionCoordinator(options, gate, Runnable::run);

    coordinator.cancel();

    assertThrows(IllegalStateException.class, () -> coordinator.waitForCompletions(() -> {}));
  }

  @Test
  public void multiThreadedConcurrentCompletions_retainsSingleContinuationDispatch()
      throws Exception {
    int workerCount = 10;
    ExecutorService workers = Executors.newFixedThreadPool(workerCount);
    ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    try {
      CelAsyncEvaluationOptions options =
          CelAsyncEvaluationOptions.builder()
              .setDrainStrategy(CelAsyncDrainStrategy.drainAll())
              .setScheduledExecutorService(scheduler)
              .build();
      AsyncGate gate = new AsyncGate(workerCount);
      for (int i = 0; i < workerCount; i++) {
        gate.dispatch(workers, () -> {});
      }

      AsyncCompletionCoordinator coordinator =
          new AsyncCompletionCoordinator(options, gate, workers);
      AtomicInteger continuationDispatches = new AtomicInteger();
      CountDownLatch continuationLatch = new CountDownLatch(1);
      CountDownLatch readyLatch = new CountDownLatch(workerCount);
      CountDownLatch startLatch = new CountDownLatch(1);

      coordinator.waitForCompletions(
          () -> {
            continuationDispatches.incrementAndGet();
            continuationLatch.countDown();
          });

      for (int i = 0; i < workerCount; i++) {
        workers.execute(
            () -> {
              readyLatch.countDown();
              try {
                startLatch.await();
                gate.releasePermit(Runnable::run);
                coordinator.notifyCallCompleted(DUMMY_CALL);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
            });
      }

      readyLatch.await(5, SECONDS);
      startLatch.countDown();

      assertThat(continuationLatch.await(5, SECONDS)).isTrue();

      workers.shutdown();
      assertThat(workers.awaitTermination(5, SECONDS)).isTrue();

      assertThat(continuationDispatches.get()).isEqualTo(1);
      assertThat(coordinator.isWaiting()).isFalse();
    } finally {
      workers.shutdownNow();
      scheduler.shutdownNow();
    }
  }

  @Test
  public void waitForCompletions_whenAlreadyWaiting_throwsIllegalStateException() {
    CelAsyncEvaluationOptions options = CelAsyncEvaluationOptions.builder().build();
    AsyncGate gate = new AsyncGate(1);
    gate.dispatch(Runnable::run, () -> {});
    AsyncCompletionCoordinator coordinator =
        new AsyncCompletionCoordinator(options, gate, Runnable::run);

    coordinator.waitForCompletions(() -> {});

    assertThrows(IllegalStateException.class, () -> coordinator.waitForCompletions(() -> {}));
  }

  @Test
  public void notifyCallCompleted_whenCancelled_ignoresCompletion() {
    CelAsyncEvaluationOptions options = CelAsyncEvaluationOptions.builder().build();
    AsyncGate gate = new AsyncGate(1);
    AsyncCompletionCoordinator coordinator =
        new AsyncCompletionCoordinator(options, gate, Runnable::run);

    coordinator.cancel();
    coordinator.notifyCallCompleted(DUMMY_CALL);

    assertThat(coordinator.hasPendingBatch()).isFalse();
  }

  @Test
  public void constructorAndMethods_nullArguments_throwsNullPointerException() {
    CelAsyncEvaluationOptions options = CelAsyncEvaluationOptions.builder().build();
    AsyncGate gate = new AsyncGate(1);

    assertThrows(
        NullPointerException.class,
        () -> new AsyncCompletionCoordinator(null, gate, Runnable::run));
    assertThrows(
        NullPointerException.class,
        () -> new AsyncCompletionCoordinator(options, null, Runnable::run));
    assertThrows(
        NullPointerException.class, () -> new AsyncCompletionCoordinator(options, gate, null));

    AsyncCompletionCoordinator coordinator =
        new AsyncCompletionCoordinator(options, gate, Runnable::run);
    assertThrows(NullPointerException.class, () -> coordinator.notifyCallCompleted(null));
    assertThrows(NullPointerException.class, () -> coordinator.waitForCompletions(null));
  }

  @Test
  public void
      waitForCompletions_whenDrainStrategySatisfiedImmediately_dispatchesContinuationWithoutTimer() {
    CelAsyncEvaluationOptions options =
        CelAsyncEvaluationOptions.builder()
            .setDrainStrategy(CelAsyncDrainStrategy.drainAll())
            .build();
    AsyncGate gate = new AsyncGate(1);
    AsyncCompletionCoordinator coordinator =
        new AsyncCompletionCoordinator(options, gate, Runnable::run);

    // Call completed while not waiting and activeCount is 0
    coordinator.notifyCallCompleted(DUMMY_CALL);
    AtomicBoolean continuationRan = new AtomicBoolean(false);

    coordinator.waitForCompletions(() -> continuationRan.set(true));

    assertThat(continuationRan.get()).isTrue();
    assertThat(coordinator.isWaiting()).isFalse();
    assertThat(coordinator.hasPendingBatch()).isFalse();
    assertThat(coordinator.hasScheduledDebounceTimer()).isFalse();
  }

  @Test
  public void constructor_initializesCycleIdToZero() {
    CelAsyncEvaluationOptions options = CelAsyncEvaluationOptions.builder().build();
    AsyncGate gate = new AsyncGate(1);

    AsyncCompletionCoordinator coordinator =
        new AsyncCompletionCoordinator(options, gate, Runnable::run);

    assertThat(coordinator.cycleId()).isEqualTo(0);
  }

  @Test
  public void notifyCallCompleted_whenDebounceTimerPending_cancelsExistingTimerWithoutInterrupt() {
    AtomicBoolean mayInterruptArg = new AtomicBoolean(true);
    ScheduledThreadPoolExecutor scheduler =
        new ScheduledThreadPoolExecutor(1) {
          @Override
          public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            ScheduledFuture<?> task = super.schedule(command, delay, unit);
            return new CapturingScheduledFuture<>(task, mayInterruptArg);
          }
        };
    try {
      CelAsyncEvaluationOptions options =
          CelAsyncEvaluationOptions.builder()
              .setDrainStrategy(CelAsyncDrainStrategy.drainReady(Duration.ofMinutes(10)))
              .setScheduledExecutorService(scheduler)
              .build();
      AsyncGate gate = new AsyncGate(1);
      gate.dispatch(Runnable::run, () -> {});

      AsyncCompletionCoordinator coordinator =
          new AsyncCompletionCoordinator(options, gate, Runnable::run);
      coordinator.notifyCallCompleted(DUMMY_CALL);
      coordinator.waitForCompletions(() -> {});

      // Second completion extends debounce window and cancels previous timer
      coordinator.notifyCallCompleted(DUMMY_CALL);

      assertThat(mayInterruptArg.get()).isFalse();
    } finally {
      scheduler.shutdownNow();
    }
  }

  @Test
  public void
      scheduleDebounce_whenCoordinatorCancelledConcurrently_cancelsScheduledFutureWithoutInterrupt() {
    AtomicBoolean mayInterruptArg = new AtomicBoolean(true);
    AtomicBoolean cancelledInsideScheduler = new AtomicBoolean(false);
    AsyncCompletionCoordinator[] coordinatorHolder = new AsyncCompletionCoordinator[1];

    ScheduledThreadPoolExecutor scheduler =
        new ScheduledThreadPoolExecutor(1) {
          @Override
          public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            ScheduledFuture<?> task = super.schedule(command, delay, unit);
            if (coordinatorHolder[0] != null && !cancelledInsideScheduler.get()) {
              cancelledInsideScheduler.set(true);
              coordinatorHolder[0].cancel();
            }
            return new CapturingScheduledFuture<>(task, mayInterruptArg);
          }
        };

    try {
      CelAsyncEvaluationOptions options =
          CelAsyncEvaluationOptions.builder()
              .setDrainStrategy(CelAsyncDrainStrategy.drainReady(Duration.ofMinutes(10)))
              .setScheduledExecutorService(scheduler)
              .build();
      AsyncGate gate = new AsyncGate(1);
      gate.dispatch(Runnable::run, () -> {});

      AsyncCompletionCoordinator coordinator =
          new AsyncCompletionCoordinator(options, gate, Runnable::run);
      coordinatorHolder[0] = coordinator;

      coordinator.notifyCallCompleted(DUMMY_CALL);
      coordinator.waitForCompletions(() -> {});

      ScheduledFuture<?> scheduledTask = (ScheduledFuture<?>) scheduler.getQueue().peek();
      assertThat(scheduledTask).isNotNull();
      assertThat(scheduledTask.isCancelled()).isTrue();
      assertThat(mayInterruptArg.get()).isFalse();
    } finally {
      scheduler.shutdownNow();
    }
  }

  @Test
  public void drainAndReset_incrementsCycleId() {
    CelAsyncEvaluationOptions options = CelAsyncEvaluationOptions.builder().build();
    AsyncGate gate = new AsyncGate(1);
    gate.dispatch(Runnable::run, () -> {});
    AsyncCompletionCoordinator coordinator =
        new AsyncCompletionCoordinator(options, gate, Runnable::run);

    coordinator.waitForCompletions(() -> {});
    long initialCycleId = coordinator.cycleId();

    gate.releasePermit(Runnable::run);
    coordinator.notifyCallCompleted(DUMMY_CALL);

    assertThat(coordinator.cycleId()).isGreaterThan(initialCycleId);
  }

  @Test
  public void drainAndReset_clearsContinuation() {
    CelAsyncEvaluationOptions options = CelAsyncEvaluationOptions.builder().build();
    AsyncGate gate = new AsyncGate(1);
    gate.dispatch(Runnable::run, () -> {});
    AsyncCompletionCoordinator coordinator =
        new AsyncCompletionCoordinator(options, gate, Runnable::run);

    coordinator.waitForCompletions(() -> {});

    assertThat(coordinator.hasContinuation()).isTrue();

    gate.releasePermit(Runnable::run);
    coordinator.notifyCallCompleted(DUMMY_CALL);

    assertThat(coordinator.hasContinuation()).isFalse();
  }

  @Test
  public void onDebounceFired_whenCycleMismatch_doesNotExecuteContinuation() {
    AtomicInteger executedCount = new AtomicInteger();
    Executor rejectingNullExecutor =
        task -> {
          requireNonNull(task, "task must not be null");
          executedCount.incrementAndGet();
          task.run();
        };
    CelAsyncEvaluationOptions options = CelAsyncEvaluationOptions.builder().build();
    AsyncGate gate = new AsyncGate(1);
    gate.dispatch(Runnable::run, () -> {});
    AsyncCompletionCoordinator coordinator =
        new AsyncCompletionCoordinator(options, gate, rejectingNullExecutor);

    coordinator.waitForCompletions(() -> {});

    // Trigger debounce callback with a mismatched cycle ID (e.g. from an earlier pass)
    coordinator.onDebounceFired(coordinator.cycleId() - 1);

    assertThat(executedCount.get()).isEqualTo(0);
    assertThat(coordinator.isWaiting()).isTrue();
    assertThat(coordinator.hasContinuation()).isTrue();
  }

  @Test
  public void waitForCompletions_whenNoCallsInFlightAndEmptyBatch_evaluatesDrainStrategyAndInvokesContinuation() {
    AsyncGate gate = new AsyncGate(1);
    CelAsyncEvaluationOptions options =
        CelAsyncEvaluationOptions.builder()
            .setDrainStrategy(CelAsyncDrainStrategy.drainReady(Duration.ofMillis(50)))
            .build();
    AsyncCompletionCoordinator coordinator =
        new AsyncCompletionCoordinator(options, gate, Runnable::run);
    AtomicBoolean continuationRan = new AtomicBoolean(false);

    coordinator.waitForCompletions(() -> continuationRan.set(true));

    assertThat(continuationRan.get()).isTrue();
    assertThat(coordinator.isWaiting()).isFalse();
  }

  private static final class CapturingScheduledFuture<V> implements ScheduledFuture<V> {
    private final ScheduledFuture<V> delegate;
    private final AtomicBoolean capturedMayInterrupt;

    CapturingScheduledFuture(ScheduledFuture<V> delegate, AtomicBoolean capturedMayInterrupt) {
      this.delegate = delegate;
      this.capturedMayInterrupt = capturedMayInterrupt;
    }

    @Override
    public long getDelay(TimeUnit unit) {
      return delegate.getDelay(unit);
    }

    @Override
    public int compareTo(Delayed o) {
      return delegate.compareTo(o);
    }

    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
      capturedMayInterrupt.set(mayInterruptIfRunning);
      return delegate.cancel(mayInterruptIfRunning);
    }

    @Override
    public boolean isCancelled() {
      return delegate.isCancelled();
    }

    @Override
    public boolean isDone() {
      return delegate.isDone();
    }

    @Override
    public V get() throws InterruptedException, ExecutionException {
      return delegate.get();
    }

    @Override
    public V get(long timeout, TimeUnit unit)
        throws InterruptedException, ExecutionException, TimeoutException {
      return delegate.get(timeout, unit);
    }
  }
}
