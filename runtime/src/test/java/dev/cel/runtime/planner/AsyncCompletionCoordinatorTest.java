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

import dev.cel.runtime.CelAsyncCall;
import dev.cel.runtime.CelAsyncDrainAction;
import dev.cel.runtime.CelAsyncDrainStrategy;
import dev.cel.runtime.CelAsyncEvaluationOptions;
import dev.cel.runtime.planner.AsyncCompletionCoordinator.WaitResult;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
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
  public void waitForCompletions_whenNoCallsInFlightAndEmptyBatch_returnsNoOutstandingWork() {
    AsyncGate gate = AsyncGate.create(1);
    CelAsyncEvaluationOptions options = CelAsyncEvaluationOptions.builder().build();
    AsyncCompletionCoordinator coordinator =
        AsyncCompletionCoordinator.create(options, gate, Runnable::run, t -> {});
    AtomicBoolean continuationRan = new AtomicBoolean(false);

    WaitResult result = coordinator.waitForCompletions(() -> continuationRan.set(true));

    assertThat(result).isEqualTo(WaitResult.NO_OUTSTANDING_WORK);
    assertThat(continuationRan.get()).isFalse();
    assertThat(coordinator.isWaiting()).isFalse();
  }

  @Test
  public void waitForCompletions_whenCallsInFlightAndEmptyBatch_returnsRegistered() {
    AsyncGate gate = AsyncGate.create(1);
    CelAsyncEvaluationOptions options = CelAsyncEvaluationOptions.builder().build();
    AsyncCompletionCoordinator coordinator =
        AsyncCompletionCoordinator.create(options, gate, Runnable::run, t -> {});
    coordinator.callStarted();
    AtomicBoolean continuationRan = new AtomicBoolean(false);

    WaitResult result = coordinator.waitForCompletions(() -> continuationRan.set(true));

    assertThat(result).isEqualTo(WaitResult.REGISTERED);
    assertThat(coordinator.isWaiting()).isTrue();
    assertThat(continuationRan.get()).isFalse();
  }

  @Test
  public void
      waitForCompletions_whenDrainStrategySatisfiedImmediately_returnsReevaluateNowWithoutDispatch() {
    AsyncGate gate = AsyncGate.create(1);
    CelAsyncEvaluationOptions options =
        CelAsyncEvaluationOptions.builder()
            .setDrainStrategy(CelAsyncDrainStrategy.drainAll())
            .build();
    AsyncCompletionCoordinator coordinator =
        AsyncCompletionCoordinator.create(options, gate, Runnable::run, t -> {});
    coordinator.callStarted();
    coordinator.callCompleted(DUMMY_CALL);
    AtomicBoolean continuationRan = new AtomicBoolean(false);

    WaitResult result = coordinator.waitForCompletions(() -> continuationRan.set(true));

    assertThat(result).isEqualTo(WaitResult.REEVALUATE_NOW);
    assertThat(continuationRan.get()).isFalse();
    assertThat(coordinator.isWaiting()).isFalse();
    assertThat(coordinator.hasPendingBatch()).isFalse();
    assertThat(coordinator.hasScheduledDebounceTimer()).isFalse();
  }

  @Test
  public void waitForCompletions_whenDebounceRequested_schedulesTimerAndReturnsRegistered() {
    ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1);
    try {
      AsyncGate gate = AsyncGate.create(2);
      CelAsyncEvaluationOptions options =
          CelAsyncEvaluationOptions.builder()
              .setDrainStrategy(CelAsyncDrainStrategy.drainReady(Duration.ofMinutes(10)))
              .setScheduledExecutorService(scheduler)
              .build();
      AsyncCompletionCoordinator coordinator =
          AsyncCompletionCoordinator.create(options, gate, Runnable::run, t -> {});
      coordinator.callStarted();
      coordinator.callStarted();
      coordinator.callCompleted(DUMMY_CALL);
      AtomicBoolean continuationRan = new AtomicBoolean(false);

      WaitResult result = coordinator.waitForCompletions(() -> continuationRan.set(true));

      assertThat(result).isEqualTo(WaitResult.REGISTERED);
      assertThat(coordinator.isWaiting()).isTrue();
      assertThat(coordinator.hasScheduledDebounceTimer()).isTrue();
      assertThat(continuationRan.get()).isFalse();
    } finally {
      scheduler.shutdownNow();
    }
  }

  @Test
  public void waitForCompletions_whenAlreadyWaiting_throwsIllegalStateException() {
    AsyncGate gate = AsyncGate.create(1);
    CelAsyncEvaluationOptions options = CelAsyncEvaluationOptions.builder().build();
    AsyncCompletionCoordinator coordinator =
        AsyncCompletionCoordinator.create(options, gate, Runnable::run, t -> {});
    coordinator.callStarted();
    coordinator.waitForCompletions(() -> {});

    IllegalStateException thrown =
        assertThrows(IllegalStateException.class, () -> coordinator.waitForCompletions(() -> {}));

    assertThat(thrown).hasMessageThat().contains("Coordinator is already waiting for completions");
  }

  @Test
  public void waitForCompletions_whenCoordinatorCancelled_returnsCancelled() {
    AsyncGate gate = AsyncGate.create(1);
    CelAsyncEvaluationOptions options = CelAsyncEvaluationOptions.builder().build();
    AsyncCompletionCoordinator coordinator =
        AsyncCompletionCoordinator.create(options, gate, Runnable::run, t -> {});
    coordinator.cancel();
    AtomicBoolean continuationRan = new AtomicBoolean(false);

    WaitResult result = coordinator.waitForCompletions(() -> continuationRan.set(true));

    assertThat(result).isEqualTo(WaitResult.CANCELLED);
    assertThat(continuationRan.get()).isFalse();
  }

  @Test
  public void callStarted_incrementsInFlightCount() {
    AsyncGate gate = AsyncGate.create(1);
    CelAsyncEvaluationOptions options = CelAsyncEvaluationOptions.builder().build();
    AsyncCompletionCoordinator coordinator =
        AsyncCompletionCoordinator.create(options, gate, Runnable::run, t -> {});

    coordinator.callStarted();
    coordinator.callStarted();

    assertThat(coordinator.inFlightCount()).isEqualTo(2);
  }

  @Test
  public void callCompleted_decrementsInFlightCountAndAddsToBatch() {
    AsyncGate gate = AsyncGate.create(1);
    CelAsyncEvaluationOptions options = CelAsyncEvaluationOptions.builder().build();
    AsyncCompletionCoordinator coordinator =
        AsyncCompletionCoordinator.create(options, gate, Runnable::run, t -> {});
    coordinator.callStarted();
    coordinator.callStarted();

    coordinator.callCompleted(DUMMY_CALL);

    assertThat(coordinator.inFlightCount()).isEqualTo(1);
    assertThat(coordinator.hasPendingBatch()).isTrue();
  }

  @Test
  public void callCompleted_whenCancelled_ignoresCall() {
    AsyncGate gate = AsyncGate.create(1);
    CelAsyncEvaluationOptions options = CelAsyncEvaluationOptions.builder().build();
    AsyncCompletionCoordinator coordinator =
        AsyncCompletionCoordinator.create(options, gate, Runnable::run, t -> {});
    coordinator.callStarted();
    coordinator.cancel();

    coordinator.callCompleted(DUMMY_CALL);

    assertThat(coordinator.inFlightCount()).isEqualTo(0);
    assertThat(coordinator.hasPendingBatch()).isFalse();
  }

  @Test
  public void callCompleted_whenWaitingWithPendingCalls_schedulesDebounceTimer() {
    ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1);
    try {
      CelAsyncEvaluationOptions options =
          CelAsyncEvaluationOptions.builder()
              .setDrainStrategy(CelAsyncDrainStrategy.drainReady(Duration.ofMinutes(10)))
              .setScheduledExecutorService(scheduler)
              .build();
      AsyncGate gate = AsyncGate.create(2);
      AsyncCompletionCoordinator coordinator =
          AsyncCompletionCoordinator.create(options, gate, Runnable::run, t -> {});
      coordinator.callStarted();
      coordinator.callStarted();
      AtomicBoolean continuationRan = new AtomicBoolean(false);
      coordinator.waitForCompletions(() -> continuationRan.set(true));

      coordinator.callCompleted(DUMMY_CALL);

      assertThat(continuationRan.get()).isFalse();
      assertThat(coordinator.isWaiting()).isTrue();
      assertThat(coordinator.hasScheduledDebounceTimer()).isTrue();
      assertThat(scheduler.getQueue()).isNotEmpty();
    } finally {
      scheduler.shutdownNow();
    }
  }

  @Test
  public void callCompleted_whenWaitingWithDrainAllStrategy_waitsWhileCallsRemainInFlight() {
    CelAsyncEvaluationOptions options =
        CelAsyncEvaluationOptions.builder()
            .setDrainStrategy(CelAsyncDrainStrategy.drainAll())
            .build();
    AsyncGate gate = AsyncGate.create(2);
    AsyncCompletionCoordinator coordinator =
        AsyncCompletionCoordinator.create(options, gate, Runnable::run, t -> {});
    coordinator.callStarted();
    coordinator.callStarted();
    AtomicBoolean continuationRan = new AtomicBoolean(false);
    coordinator.waitForCompletions(() -> continuationRan.set(true));

    coordinator.callCompleted(DUMMY_CALL);

    assertThat(continuationRan.get()).isFalse();
    assertThat(coordinator.isWaiting()).isTrue();
  }

  @Test
  public void
      callCompleted_whenWaitingWithDrainAllStrategy_triggersContinuationWhenFinalCallCompletes() {
    CelAsyncEvaluationOptions options =
        CelAsyncEvaluationOptions.builder()
            .setDrainStrategy(CelAsyncDrainStrategy.drainAll())
            .build();
    AsyncGate gate = AsyncGate.create(2);
    AsyncCompletionCoordinator coordinator =
        AsyncCompletionCoordinator.create(options, gate, Runnable::run, t -> {});
    coordinator.callStarted();
    coordinator.callStarted();
    AtomicBoolean continuationRan = new AtomicBoolean(false);
    coordinator.waitForCompletions(() -> continuationRan.set(true));
    coordinator.callCompleted(DUMMY_CALL);

    coordinator.callCompleted(DUMMY_CALL);

    assertThat(continuationRan.get()).isTrue();
    assertThat(coordinator.isWaiting()).isFalse();
  }

  @Test
  public void callCompleted_whenDebounceTimerPending_resetsDebounceTimerForSlidingWindow() {
    ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1);
    try {
      CelAsyncEvaluationOptions options =
          CelAsyncEvaluationOptions.builder()
              .setDrainStrategy(CelAsyncDrainStrategy.drainReady(Duration.ofMinutes(10)))
              .setScheduledExecutorService(scheduler)
              .build();
      AsyncGate gate = AsyncGate.create(3);
      AsyncCompletionCoordinator coordinator =
          AsyncCompletionCoordinator.create(options, gate, Runnable::run, t -> {});
      coordinator.callStarted();
      coordinator.callStarted();
      coordinator.callStarted();
      coordinator.waitForCompletions(() -> {});

      coordinator.callCompleted(DUMMY_CALL);
      ScheduledFuture<?> firstTimer = (ScheduledFuture<?>) scheduler.getQueue().peek();
      coordinator.callCompleted(DUMMY_CALL);

      assertThat(firstTimer).isNotNull();
      assertThat(firstTimer.isCancelled()).isTrue();
      assertThat(coordinator.hasScheduledDebounceTimer()).isTrue();
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
      AsyncGate gate = AsyncGate.create(2);
      AsyncCompletionCoordinator coordinator =
          AsyncCompletionCoordinator.create(options, gate, Runnable::run, t -> {});
      coordinator.callStarted();
      coordinator.callStarted();
      AtomicBoolean continuationRan = new AtomicBoolean(false);
      coordinator.waitForCompletions(() -> continuationRan.set(true));
      coordinator.callCompleted(DUMMY_CALL);
      ScheduledFuture<?> scheduledTask = (ScheduledFuture<?>) scheduler.getQueue().peek();

      assertThat(scheduledTask).isNotNull();
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
  public void onDebounceFired_whenCycleMismatch_doesNotExecuteContinuation() {
    AtomicInteger executedCount = new AtomicInteger();
    Executor rejectingNullExecutor =
        task -> {
          requireNonNull(task, "task must not be null");
          executedCount.incrementAndGet();
          task.run();
        };
    CelAsyncEvaluationOptions options = CelAsyncEvaluationOptions.builder().build();
    AsyncGate gate = AsyncGate.create(1);
    AsyncCompletionCoordinator coordinator =
        AsyncCompletionCoordinator.create(options, gate, rejectingNullExecutor, t -> {});
    coordinator.callStarted();
    coordinator.waitForCompletions(() -> {});

    coordinator.onDebounceFired(coordinator.cycleId() - 1, coordinator.debounceGeneration());

    assertThat(executedCount.get()).isEqualTo(0);
    assertThat(coordinator.isWaiting()).isTrue();
    assertThat(coordinator.hasContinuation()).isTrue();
  }

  @Test
  public void onDebounceFired_whenDebounceGenerationMismatch_doesNotExecuteContinuation() {
    ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1);
    try {
      CelAsyncEvaluationOptions options =
          CelAsyncEvaluationOptions.builder()
              .setDrainStrategy(CelAsyncDrainStrategy.drainReady(Duration.ofMinutes(10)))
              .setScheduledExecutorService(scheduler)
              .build();
      AsyncGate gate = AsyncGate.create(3);
      AsyncCompletionCoordinator coordinator =
          AsyncCompletionCoordinator.create(options, gate, Runnable::run, t -> {});
      coordinator.callStarted();
      coordinator.callStarted();
      coordinator.callStarted();
      AtomicInteger continuationRan = new AtomicInteger();
      coordinator.waitForCompletions(continuationRan::incrementAndGet);
      coordinator.callCompleted(DUMMY_CALL);
      long staleGen = coordinator.debounceGeneration();
      coordinator.callCompleted(DUMMY_CALL);

      coordinator.onDebounceFired(coordinator.cycleId(), staleGen);

      assertThat(continuationRan.get()).isEqualTo(0);
      assertThat(coordinator.isWaiting()).isTrue();
    } finally {
      scheduler.shutdownNow();
    }
  }

  @Test
  public void cancel_cancelsDebounceTimerAndPreventsContinuation() {
    ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1);
    try {
      CelAsyncEvaluationOptions options =
          CelAsyncEvaluationOptions.builder()
              .setDrainStrategy(CelAsyncDrainStrategy.drainReady(Duration.ofMinutes(10)))
              .setScheduledExecutorService(scheduler)
              .build();
      AsyncGate gate = AsyncGate.create(2);
      AsyncCompletionCoordinator coordinator =
          AsyncCompletionCoordinator.create(options, gate, Runnable::run, t -> {});
      coordinator.callStarted();
      coordinator.callStarted();
      AtomicBoolean continuationRan = new AtomicBoolean(false);
      coordinator.waitForCompletions(() -> continuationRan.set(true));
      coordinator.callCompleted(DUMMY_CALL);
      ScheduledFuture<?> scheduledTask = (ScheduledFuture<?>) scheduler.getQueue().peek();

      coordinator.cancel();

      assertThat(coordinator.hasPendingBatch()).isFalse();
      assertThat(coordinator.isWaiting()).isFalse();
      assertThat(coordinator.hasContinuation()).isFalse();
      assertThat(coordinator.hasScheduledDebounceTimer()).isFalse();
      assertThat(scheduledTask).isNotNull();
      assertThat(scheduledTask.isCancelled()).isTrue();
      assertThat(continuationRan.get()).isFalse();
    } finally {
      scheduler.shutdownNow();
    }
  }

  @Test
  public void onDebounceFired_whenCancelled_doesNotTriggerContinuation() {
    ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1);
    try {
      CelAsyncEvaluationOptions options =
          CelAsyncEvaluationOptions.builder()
              .setDrainStrategy(CelAsyncDrainStrategy.drainReady(Duration.ofMinutes(10)))
              .setScheduledExecutorService(scheduler)
              .build();
      AsyncGate gate = AsyncGate.create(2);
      AsyncCompletionCoordinator coordinator =
          AsyncCompletionCoordinator.create(options, gate, Runnable::run, t -> {});
      coordinator.callStarted();
      coordinator.callStarted();
      AtomicBoolean continuationRan = new AtomicBoolean(false);
      coordinator.waitForCompletions(() -> continuationRan.set(true));
      coordinator.callCompleted(DUMMY_CALL);
      ScheduledFuture<?> scheduledTask = (ScheduledFuture<?>) scheduler.getQueue().peek();
      coordinator.cancel();

      assertThat(scheduledTask).isNotNull();
      ((Runnable) scheduledTask).run();

      assertThat(continuationRan.get()).isFalse();
    } finally {
      scheduler.shutdownNow();
    }
  }

  @Test
  public void cancel_cancelsAssociatedGate() {
    AsyncGate gate = AsyncGate.create(1);
    CelAsyncEvaluationOptions options = CelAsyncEvaluationOptions.builder().build();
    AsyncCompletionCoordinator coordinator =
        AsyncCompletionCoordinator.create(options, gate, Runnable::run, t -> {});

    coordinator.cancel();

    assertThat(gate.isCancelled()).isTrue();
  }

  @Test
  public void applyDrainAction_whenInFlightZeroAndStrategyWaits_forcesReevaluation() {
    CelAsyncDrainStrategy alwaysWaitStrategy = (batch, active) -> CelAsyncDrainAction.waitForMore();
    CelAsyncEvaluationOptions options =
        CelAsyncEvaluationOptions.builder().setDrainStrategy(alwaysWaitStrategy).build();
    AsyncGate gate = AsyncGate.create(1);
    AsyncCompletionCoordinator coordinator =
        AsyncCompletionCoordinator.create(options, gate, Runnable::run, t -> {});
    coordinator.callStarted();
    AtomicBoolean continuationRan = new AtomicBoolean(false);
    coordinator.waitForCompletions(() -> continuationRan.set(true));

    coordinator.callCompleted(DUMMY_CALL);

    assertThat(continuationRan.get()).isTrue();
    assertThat(coordinator.isWaiting()).isFalse();
  }

  @Test
  public void dispatchContinuation_whenExecutorThrows_invokesFailureCallback() {
    Executor rejectingExecutor =
        r -> {
          throw new RejectedExecutionException("rejected");
        };
    AtomicReference<Throwable> failure = new AtomicReference<>();
    CelAsyncEvaluationOptions options = CelAsyncEvaluationOptions.builder().build();
    AsyncGate gate = AsyncGate.create(1);
    AsyncCompletionCoordinator coordinator =
        AsyncCompletionCoordinator.create(options, gate, rejectingExecutor, failure::set);
    coordinator.callStarted();
    coordinator.waitForCompletions(() -> {});

    coordinator.callCompleted(DUMMY_CALL);

    assertThat(failure.get()).isInstanceOf(RejectedExecutionException.class);
  }

  @Test
  public void scheduleDebounce_whenSchedulerThrows_invokesFailureCallback() {
    ScheduledThreadPoolExecutor rejectingScheduler =
        new ScheduledThreadPoolExecutor(1) {
          @Override
          public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            throw new RejectedExecutionException("scheduler rejected");
          }
        };
    try {
      AtomicReference<Throwable> failure = new AtomicReference<>();
      CelAsyncEvaluationOptions options =
          CelAsyncEvaluationOptions.builder()
              .setDrainStrategy(CelAsyncDrainStrategy.drainReady(Duration.ofMinutes(10)))
              .setScheduledExecutorService(rejectingScheduler)
              .build();
      AsyncGate gate = AsyncGate.create(2);
      AsyncCompletionCoordinator coordinator =
          AsyncCompletionCoordinator.create(options, gate, Runnable::run, failure::set);
      coordinator.callStarted();
      coordinator.callStarted();
      coordinator.waitForCompletions(() -> {});

      coordinator.callCompleted(DUMMY_CALL);

      assertThat(failure.get()).isInstanceOf(RejectedExecutionException.class);
    } finally {
      rejectingScheduler.shutdownNow();
    }
  }

  @Test
  public void
      scheduleDebounce_whenCoordinatorCancelledConcurrently_cancelsScheduledFutureWithoutInterrupt() {
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
            return task;
          }
        };

    try {
      CelAsyncEvaluationOptions options =
          CelAsyncEvaluationOptions.builder()
              .setDrainStrategy(CelAsyncDrainStrategy.drainReady(Duration.ofMinutes(10)))
              .setScheduledExecutorService(scheduler)
              .build();
      AsyncGate gate = AsyncGate.create(2);
      AsyncCompletionCoordinator coordinator =
          AsyncCompletionCoordinator.create(options, gate, Runnable::run, t -> {});
      coordinatorHolder[0] = coordinator;
      coordinator.callStarted();
      coordinator.callStarted();
      coordinator.callCompleted(DUMMY_CALL);
      coordinator.waitForCompletions(() -> {});

      ScheduledFuture<?> scheduledTask = (ScheduledFuture<?>) scheduler.getQueue().peek();

      assertThat(scheduledTask).isNotNull();
      assertThat(scheduledTask.isCancelled()).isTrue();
    } finally {
      scheduler.shutdownNow();
    }
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
      AsyncGate gate = AsyncGate.create(workerCount);
      AsyncCompletionCoordinator coordinator =
          AsyncCompletionCoordinator.create(options, gate, workers, t -> {});
      for (int i = 0; i < workerCount; i++) {
        coordinator.callStarted();
      }
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
                coordinator.callCompleted(DUMMY_CALL);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
            });
      }

      readyLatch.await(5, SECONDS);
      startLatch.countDown();
      boolean continuationReached = continuationLatch.await(5, SECONDS);
      workers.shutdown();
      boolean workersTerminated = workers.awaitTermination(5, SECONDS);

      assertThat(continuationReached).isTrue();
      assertThat(workersTerminated).isTrue();
      assertThat(continuationDispatches.get()).isEqualTo(1);
      assertThat(coordinator.isWaiting()).isFalse();
    } finally {
      workers.shutdownNow();
      scheduler.shutdownNow();
    }
  }

  @Test
  public void create_nullOptions_throwsNullPointerException() {
    AsyncGate gate = AsyncGate.create(1);

    NullPointerException thrown =
        assertThrows(
            NullPointerException.class,
            () -> AsyncCompletionCoordinator.create(null, gate, Runnable::run, t -> {}));

    assertThat(thrown).hasMessageThat().contains("options must not be null");
  }

  @Test
  public void create_nullGate_throwsNullPointerException() {
    CelAsyncEvaluationOptions options = CelAsyncEvaluationOptions.builder().build();

    NullPointerException thrown =
        assertThrows(
            NullPointerException.class,
            () -> AsyncCompletionCoordinator.create(options, null, Runnable::run, t -> {}));

    assertThat(thrown).hasMessageThat().contains("gate must not be null");
  }

  @Test
  public void create_nullExecutor_throwsNullPointerException() {
    CelAsyncEvaluationOptions options = CelAsyncEvaluationOptions.builder().build();
    AsyncGate gate = AsyncGate.create(1);

    NullPointerException thrown =
        assertThrows(
            NullPointerException.class,
            () -> AsyncCompletionCoordinator.create(options, gate, null, t -> {}));

    assertThat(thrown).hasMessageThat().contains("continuationExecutor must not be null");
  }

  @Test
  public void create_nullFailureCallback_throwsNullPointerException() {
    CelAsyncEvaluationOptions options = CelAsyncEvaluationOptions.builder().build();
    AsyncGate gate = AsyncGate.create(1);

    NullPointerException thrown =
        assertThrows(
            NullPointerException.class,
            () -> AsyncCompletionCoordinator.create(options, gate, Runnable::run, null));

    assertThat(thrown).hasMessageThat().contains("failureCallback must not be null");
  }

  @Test
  public void callCompleted_nullCall_throwsNullPointerException() {
    CelAsyncEvaluationOptions options = CelAsyncEvaluationOptions.builder().build();
    AsyncGate gate = AsyncGate.create(1);
    AsyncCompletionCoordinator coordinator =
        AsyncCompletionCoordinator.create(options, gate, Runnable::run, t -> {});

    NullPointerException thrown =
        assertThrows(NullPointerException.class, () -> coordinator.callCompleted(null));

    assertThat(thrown).hasMessageThat().contains("call must not be null");
  }

  @Test
  public void waitForCompletions_nullContinuation_throwsNullPointerException() {
    CelAsyncEvaluationOptions options = CelAsyncEvaluationOptions.builder().build();
    AsyncGate gate = AsyncGate.create(1);
    AsyncCompletionCoordinator coordinator =
        AsyncCompletionCoordinator.create(options, gate, Runnable::run, t -> {});

    NullPointerException thrown =
        assertThrows(NullPointerException.class, () -> coordinator.waitForCompletions(null));

    assertThat(thrown).hasMessageThat().contains("continuationCallback must not be null");
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
      AsyncGate gate = AsyncGate.create(2);
      AsyncCompletionCoordinator coordinator =
          AsyncCompletionCoordinator.create(options, gate, Runnable::run, t -> {});
      coordinator.callStarted();
      coordinator.callStarted();
      AtomicInteger pass1Count = new AtomicInteger();
      coordinator.waitForCompletions(pass1Count::incrementAndGet);
      coordinator.callCompleted(DUMMY_CALL);
      ScheduledFuture<?> pass1Timer = (ScheduledFuture<?>) scheduler.getQueue().peek();
      coordinator.callCompleted(DUMMY_CALL);
      coordinator.callStarted();
      AtomicInteger pass2Count = new AtomicInteger();
      coordinator.waitForCompletions(pass2Count::incrementAndGet);

      assertThat(pass1Timer).isNotNull();
      ((Runnable) pass1Timer).run();

      assertThat(pass2Count.get()).isEqualTo(0);
      assertThat(coordinator.isWaiting()).isTrue();
    } finally {
      scheduler.shutdownNow();
    }
  }

  @Test
  public void drainAndReset_incrementsCycleIdAndClearsContinuation() {
    CelAsyncEvaluationOptions options = CelAsyncEvaluationOptions.builder().build();
    AsyncGate gate = AsyncGate.create(1);
    AsyncCompletionCoordinator coordinator =
        AsyncCompletionCoordinator.create(options, gate, Runnable::run, t -> {});
    coordinator.callStarted();
    coordinator.waitForCompletions(() -> {});
    long initialCycleId = coordinator.cycleId();

    coordinator.callCompleted(DUMMY_CALL);

    assertThat(coordinator.cycleId()).isGreaterThan(initialCycleId);
    assertThat(coordinator.hasContinuation()).isFalse();
  }

  @Test
  public void
      waitForCompletions_lastCallCompletesDuringDrainStrategyEvaluation_executesContinuationAndReturnsRegistered() {
    AtomicReference<AsyncCompletionCoordinator> coordinatorRef = new AtomicReference<>();
    CelAsyncDrainStrategy racingStrategy = new RacingDrainStrategy(coordinatorRef);
    CelAsyncEvaluationOptions options =
        CelAsyncEvaluationOptions.builder().setDrainStrategy(racingStrategy).build();
    AsyncGate gate = AsyncGate.create(2);
    AsyncCompletionCoordinator coordinator =
        AsyncCompletionCoordinator.create(options, gate, Runnable::run, t -> {});
    coordinatorRef.set(coordinator);
    coordinator.callStarted();
    coordinator.callStarted();
    coordinator.callCompleted(DUMMY_CALL);
    AtomicBoolean continuationRan = new AtomicBoolean(false);

    WaitResult result = coordinator.waitForCompletions(() -> continuationRan.set(true));

    assertThat(result).isEqualTo(WaitResult.REGISTERED);
    assertThat(continuationRan.get()).isTrue();
    assertThat(coordinator.isWaiting()).isFalse();
  }

  @Test
  public void dispatchContinuation_directExecutorReentrantCompletions_doesNotCauseStackOverflow() {
    CelAsyncEvaluationOptions options = CelAsyncEvaluationOptions.builder().build();
    AsyncGate gate = AsyncGate.create(1);
    AtomicInteger step = new AtomicInteger();
    int targetSteps = 1000;
    AtomicReference<AsyncCompletionCoordinator> coordinatorRef = new AtomicReference<>();
    AsyncCompletionCoordinator coordinator =
        AsyncCompletionCoordinator.create(options, gate, Runnable::run, t -> {});
    coordinatorRef.set(coordinator);
    coordinator.callStarted();
    coordinator.waitForCompletions(
        new Runnable() {
          @Override
          public void run() {
            if (step.incrementAndGet() < targetSteps) {
              coordinatorRef.get().callStarted();
              coordinatorRef.get().waitForCompletions(this);
              coordinatorRef.get().callCompleted(DUMMY_CALL);
            }
          }
        });

    coordinator.callCompleted(DUMMY_CALL);

    assertThat(step.get()).isEqualTo(targetSteps);
  }

  @Test
  public void dispatchContinuation_nestedCoordinatorsOnSameThread_doesNotHijackExecutor() {
    AtomicBoolean coordinator2ExecutorUsed = new AtomicBoolean(false);
    AsyncGate gate1 = AsyncGate.create(1);
    AsyncGate gate2 = AsyncGate.create(1);
    CelAsyncEvaluationOptions options = CelAsyncEvaluationOptions.builder().build();
    AsyncCompletionCoordinator coordinator1 =
        AsyncCompletionCoordinator.create(options, gate1, Runnable::run, t -> {});
    AsyncCompletionCoordinator coordinator2 =
        AsyncCompletionCoordinator.create(
            options,
            gate2,
            task -> {
              coordinator2ExecutorUsed.set(true);
              task.run();
            },
            t -> {});
    coordinator1.callStarted();
    coordinator2.callStarted();
    coordinator1.waitForCompletions(
        () -> {
          coordinator2.waitForCompletions(() -> {});
          coordinator2.callCompleted(DUMMY_CALL);
        });

    coordinator1.callCompleted(DUMMY_CALL);

    assertThat(coordinator2ExecutorUsed.get()).isTrue();
  }

  @Test
  public void callStarted_whenCancelled_doesNotIncrementInFlightCount() {
    AsyncGate gate = AsyncGate.create(1);
    CelAsyncEvaluationOptions options = CelAsyncEvaluationOptions.builder().build();
    AsyncCompletionCoordinator coordinator =
        AsyncCompletionCoordinator.create(options, gate, Runnable::run, t -> {});
    coordinator.cancel();

    coordinator.callStarted();

    assertThat(coordinator.inFlightCount()).isEqualTo(0);
  }

  @Test
  public void callCompleted_withoutPriorCallStarted_throwsIllegalStateException() {
    AsyncGate gate = AsyncGate.create(1);
    CelAsyncEvaluationOptions options = CelAsyncEvaluationOptions.builder().build();
    AsyncCompletionCoordinator coordinator =
        AsyncCompletionCoordinator.create(options, gate, Runnable::run, t -> {});

    IllegalStateException thrown =
        assertThrows(IllegalStateException.class, () -> coordinator.callCompleted(DUMMY_CALL));

    assertThat(thrown).hasMessageThat().contains("callCompleted called with no calls in flight");
  }

  @Test
  public void callCompleted_whenDrainStrategyThrows_invokesFailureCallbackAndCancels() {
    RuntimeException failure = new RuntimeException("strategy failed");
    CelAsyncEvaluationOptions options =
        CelAsyncEvaluationOptions.builder()
            .setDrainStrategy(new FailingDrainStrategy(failure))
            .build();
    AsyncGate gate = AsyncGate.create(1);
    AtomicReference<Throwable> capturedFailure = new AtomicReference<>();
    AsyncCompletionCoordinator coordinator =
        AsyncCompletionCoordinator.create(options, gate, Runnable::run, capturedFailure::set);
    coordinator.callStarted();
    coordinator.waitForCompletions(() -> {});

    coordinator.callCompleted(DUMMY_CALL);

    assertThat(capturedFailure.get()).isSameInstanceAs(failure);
    assertThat(coordinator.isCancelled()).isTrue();
    assertThat(gate.isCancelled()).isTrue();
  }

  @Test
  public void waitForCompletions_whenDrainStrategyThrows_invokesFailureCallbackAndCancels() {
    RuntimeException failure = new RuntimeException("strategy failed");
    CelAsyncEvaluationOptions options =
        CelAsyncEvaluationOptions.builder()
            .setDrainStrategy(new FailingDrainStrategy(failure))
            .build();
    AsyncGate gate = AsyncGate.create(1);
    AtomicReference<Throwable> capturedFailure = new AtomicReference<>();
    AsyncCompletionCoordinator coordinator =
        AsyncCompletionCoordinator.create(options, gate, Runnable::run, capturedFailure::set);
    coordinator.callStarted();
    coordinator.callCompleted(DUMMY_CALL);
    coordinator.callStarted();

    WaitResult result = coordinator.waitForCompletions(() -> {});

    assertThat(result).isEqualTo(WaitResult.CANCELLED);
    assertThat(capturedFailure.get()).isSameInstanceAs(failure);
    assertThat(coordinator.isCancelled()).isTrue();
  }

  @Test
  public void scheduleDebounce_whenSchedulerThrows_cancelsCoordinator() {
    ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1);
    scheduler.shutdown();
    try {
      CelAsyncEvaluationOptions options =
          CelAsyncEvaluationOptions.builder()
              .setDrainStrategy(CelAsyncDrainStrategy.drainReady(Duration.ofMinutes(10)))
              .setScheduledExecutorService(scheduler)
              .build();
      AsyncGate gate = AsyncGate.create(2);
      AtomicReference<Throwable> capturedFailure = new AtomicReference<>();
      AsyncCompletionCoordinator coordinator =
          AsyncCompletionCoordinator.create(options, gate, Runnable::run, capturedFailure::set);
      coordinator.callStarted();
      coordinator.callStarted();
      coordinator.waitForCompletions(() -> {});

      coordinator.callCompleted(DUMMY_CALL);

      assertThat(capturedFailure.get()).isInstanceOf(RejectedExecutionException.class);
      assertThat(coordinator.isCancelled()).isTrue();
      assertThat(gate.isCancelled()).isTrue();
    } finally {
      scheduler.shutdownNow();
    }
  }

  @Test
  public void waitForCompletions_whenIntermediateCallArrivesDuringStrategyEval_preservesDebounce() {
    ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1);
    try {
      AtomicReference<AsyncCompletionCoordinator> coordinatorRef = new AtomicReference<>();
      CelAsyncDrainStrategy racingStrategy =
          new SingleShotRacingDrainStrategy(
              coordinatorRef, CelAsyncDrainAction.waitDuration(Duration.ofMinutes(5)));
      CelAsyncEvaluationOptions options =
          CelAsyncEvaluationOptions.builder()
              .setDrainStrategy(racingStrategy)
              .setScheduledExecutorService(scheduler)
              .build();
      AsyncGate gate = AsyncGate.create(3);
      AsyncCompletionCoordinator coordinator =
          AsyncCompletionCoordinator.create(options, gate, Runnable::run, t -> {});
      coordinatorRef.set(coordinator);
      coordinator.callStarted();
      coordinator.callStarted();
      coordinator.callStarted();
      coordinator.callCompleted(DUMMY_CALL);

      WaitResult result = coordinator.waitForCompletions(() -> {});

      assertThat(result).isEqualTo(WaitResult.REGISTERED);
      assertThat(coordinator.isWaiting()).isTrue();
      assertThat(coordinator.hasScheduledDebounceTimer()).isTrue();
    } finally {
      scheduler.shutdownNow();
    }
  }

  @Test
  public void dispatchContinuation_whenExecutorThrows_cancelsCoordinatorAndNotifiesCallback() {
    RejectedExecutionException failure = new RejectedExecutionException("rejected");
    Executor rejectingExecutor =
        task -> {
          throw failure;
        };
    AsyncGate gate = AsyncGate.create(1);
    AtomicReference<Throwable> capturedFailure = new AtomicReference<>();
    CelAsyncEvaluationOptions options = CelAsyncEvaluationOptions.builder().build();
    AsyncCompletionCoordinator coordinator =
        AsyncCompletionCoordinator.create(options, gate, rejectingExecutor, capturedFailure::set);
    coordinator.callStarted();
    coordinator.waitForCompletions(() -> {});

    coordinator.callCompleted(DUMMY_CALL);

    assertThat(capturedFailure.get()).isSameInstanceAs(failure);
    assertThat(coordinator.isCancelled()).isTrue();
    assertThat(gate.isCancelled()).isTrue();
  }

  @Test
  public void failAndCancel_concurrentFailures_notifiesCallbackAtMostOnce() {
    RuntimeException failure1 = new RuntimeException("error 1");
    AtomicInteger callbackCount = new AtomicInteger();
    AsyncGate gate = AsyncGate.create(2);
    FailingDrainStrategy failingStrategy = new FailingDrainStrategy(failure1);
    CelAsyncEvaluationOptions options =
        CelAsyncEvaluationOptions.builder().setDrainStrategy(failingStrategy).build();
    AsyncCompletionCoordinator coordinator =
        AsyncCompletionCoordinator.create(
            options, gate, Runnable::run, t -> callbackCount.incrementAndGet());
    coordinator.callStarted();
    coordinator.waitForCompletions(() -> {});

    coordinator.callCompleted(DUMMY_CALL);

    assertThat(callbackCount.get()).isEqualTo(1);
    assertThat(coordinator.isCancelled()).isTrue();
  }

  @Test
  public void applyDrainAction_whenGenerationStale_discardsStaleAction() {
    ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1);
    try {
      AtomicReference<AsyncCompletionCoordinator> coordinatorRef = new AtomicReference<>();
      StaleReevaluateRacingDrainStrategy strategy =
          new StaleReevaluateRacingDrainStrategy(coordinatorRef);
      CelAsyncEvaluationOptions options =
          CelAsyncEvaluationOptions.builder()
              .setDrainStrategy(strategy)
              .setScheduledExecutorService(scheduler)
              .build();
      AsyncGate gate = AsyncGate.create(3);
      AsyncCompletionCoordinator coordinator =
          AsyncCompletionCoordinator.create(options, gate, Runnable::run, t -> {});
      coordinatorRef.set(coordinator);
      coordinator.callStarted();
      coordinator.callStarted();
      coordinator.callStarted();
      coordinator.waitForCompletions(() -> {});

      coordinator.callCompleted(DUMMY_CALL);

      assertThat(coordinator.isWaiting()).isTrue();
      assertThat(coordinator.hasScheduledDebounceTimer()).isTrue();
    } finally {
      scheduler.shutdownNow();
    }
  }

  private static final class StaleReevaluateRacingDrainStrategy implements CelAsyncDrainStrategy {
    @SuppressWarnings("Immutable")
    private final AtomicReference<AsyncCompletionCoordinator> coordinatorRef;

    @SuppressWarnings("Immutable")
    private final AtomicBoolean first = new AtomicBoolean(true);

    @Override
    public CelAsyncDrainAction nextAction(List<CelAsyncCall> batch, int active) {
      if (first.compareAndSet(true, false)) {
        coordinatorRef.get().callCompleted(DUMMY_CALL);
        return CelAsyncDrainAction.reevaluate();
      }
      return CelAsyncDrainAction.waitDuration(Duration.ofMinutes(5));
    }

    private StaleReevaluateRacingDrainStrategy(
        AtomicReference<AsyncCompletionCoordinator> coordinatorRef) {
      this.coordinatorRef = coordinatorRef;
    }
  }

  private static final class SingleShotRacingDrainStrategy implements CelAsyncDrainStrategy {
    @SuppressWarnings("Immutable")
    private final AtomicReference<AsyncCompletionCoordinator> coordinatorRef;

    @SuppressWarnings("Immutable")
    private final AtomicBoolean completed;

    private final CelAsyncDrainAction returnAction;

    @Override
    public CelAsyncDrainAction nextAction(List<CelAsyncCall> batch, int active) {
      if (completed.compareAndSet(false, true)) {
        coordinatorRef.get().callCompleted(DUMMY_CALL);
      }
      return returnAction;
    }

    private SingleShotRacingDrainStrategy(
        AtomicReference<AsyncCompletionCoordinator> coordinatorRef,
        CelAsyncDrainAction returnAction) {
      this.coordinatorRef = coordinatorRef;
      this.completed = new AtomicBoolean(false);
      this.returnAction = returnAction;
    }
  }

  private static final class FailingDrainStrategy implements CelAsyncDrainStrategy {
    @SuppressWarnings("Immutable")
    private final RuntimeException failure;

    @Override
    public CelAsyncDrainAction nextAction(List<CelAsyncCall> batch, int active) {
      throw failure;
    }

    private FailingDrainStrategy(RuntimeException failure) {
      this.failure = failure;
    }
  }

  private static final class RacingDrainStrategy implements CelAsyncDrainStrategy {
    @SuppressWarnings("Immutable")
    private final AtomicReference<AsyncCompletionCoordinator> coordinatorRef;

    @Override
    public CelAsyncDrainAction nextAction(List<CelAsyncCall> batch, int active) {
      if (active > 0) {
        coordinatorRef.get().callCompleted(DUMMY_CALL);
      }
      return CelAsyncDrainAction.waitForMore();
    }

    private RacingDrainStrategy(AtomicReference<AsyncCompletionCoordinator> coordinatorRef) {
      this.coordinatorRef = coordinatorRef;
    }
  }
}
