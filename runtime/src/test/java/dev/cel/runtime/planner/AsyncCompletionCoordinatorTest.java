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

import com.google.common.collect.ImmutableList;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import dev.cel.runtime.CelAsyncCall;
import dev.cel.runtime.CelAsyncDrainStrategy;
import dev.cel.runtime.CelAsyncEvaluationOptions;
import java.time.Duration;
import java.util.concurrent.Delayed;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
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
          return "fn";
        }

        @Override
        public String overloadId() {
          return "fn_overload";
        }

        @Override
        public ImmutableList<Object> arguments() {
          return ImmutableList.of();
        }

        @Override
        public Duration elapsedDuration() {
          return Duration.ZERO;
        }
      };

  @Test
  public void initialStatus_emptyBatch() {
    CelAsyncEvaluationOptions options = CelAsyncEvaluationOptions.defaultOptions();
    AsyncGate gate = new AsyncGate(0);
    AsyncCompletionCoordinator coordinator =
        new AsyncCompletionCoordinator(options, gate, Runnable::run);

    assertThat(coordinator.hasPendingBatch()).isFalse();
  }

  @Test
  public void notifyCallCompleted_addsToBatch() {
    CelAsyncEvaluationOptions options = CelAsyncEvaluationOptions.defaultOptions();
    AsyncGate gate = new AsyncGate(0);
    AsyncCompletionCoordinator coordinator =
        new AsyncCompletionCoordinator(options, gate, Runnable::run);

    coordinator.notifyCallCompleted(DUMMY_CALL);

    assertThat(coordinator.hasPendingBatch()).isTrue();
  }

  @Test
  public void waitForCompletions_immediateReevaluation_triggersContinuation() {
    CelAsyncEvaluationOptions options =
        CelAsyncEvaluationOptions.builder()
            .setDrainStrategy(CelAsyncDrainStrategy.drainReady(Duration.ZERO))
            .build();
    AsyncGate gate = new AsyncGate(0);
    AsyncCompletionCoordinator coordinator =
        new AsyncCompletionCoordinator(options, gate, Runnable::run);
    coordinator.notifyCallCompleted(DUMMY_CALL);

    AtomicBoolean continuationRan = new AtomicBoolean(false);
    coordinator.waitForCompletions(() -> continuationRan.set(true));

    assertThat(continuationRan.get()).isTrue();
    assertThat(coordinator.hasPendingBatch()).isFalse();
    assertThat(coordinator.isWaiting()).isFalse();
    assertThat(coordinator.hasContinuation()).isFalse();
  }

  @Test
  public void cancel_cancelsDebounceTimerAndClearsBatch() {
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
      // Simulate an active call in the gate so drainReady chooses waitDuration instead of
      // reevaluate
      gate.dispatch(Runnable::run, () -> {});

      AsyncCompletionCoordinator coordinator =
          new AsyncCompletionCoordinator(options, gate, Runnable::run);
      coordinator.notifyCallCompleted(DUMMY_CALL);

      AtomicBoolean continuationRan = new AtomicBoolean(false);
      coordinator.waitForCompletions(() -> continuationRan.set(true));

      // Continuation shouldn't have run because it scheduled a 10 min debounce timer
      assertThat(continuationRan.get()).isFalse();
      assertThat(coordinator.isWaiting()).isTrue();
      assertThat(coordinator.hasContinuation()).isTrue();
      assertThat(coordinator.hasScheduledDebounceTimer()).isTrue();
      ScheduledFuture<?> scheduledTask = (ScheduledFuture<?>) scheduler.getQueue().peek();
      assertThat(scheduledTask).isNotNull();
      assertThat(scheduledTask.isCancelled()).isFalse();

      coordinator.cancel();

      assertThat(coordinator.hasPendingBatch()).isFalse();
      assertThat(coordinator.isWaiting()).isFalse();
      assertThat(coordinator.hasContinuation()).isFalse();
      assertThat(coordinator.hasScheduledDebounceTimer()).isFalse();
      // Verify debounce timer was cancelled with mayInterruptIfRunning = false
      assertThat(scheduledTask.isCancelled()).isTrue();
      assertThat(mayInterruptArg.get()).isFalse();
      // Verify continuation was cancelled and does not run
      assertThat(continuationRan.get()).isFalse();

      // Subsequent completions after cancellation must not trigger the cancelled continuation
      gate.releasePermit(Runnable::run);
      coordinator.notifyCallCompleted(DUMMY_CALL);
      assertThat(continuationRan.get()).isFalse();

      // If scheduled task runnable executes after cancel, it must not run the continuation
      ((Runnable) scheduledTask).run();
      assertThat(continuationRan.get()).isFalse();
    } finally {
      scheduler.shutdownNow();
    }
  }

  @Test
  public void triggerContinuation_whenDebounceTimerPending_cancelsDebounceTimer() {
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
      assertThat(coordinator.hasScheduledDebounceTimer()).isTrue();

      // Release gate permit so that subsequent notification causes drainReady to return
      // shouldReevaluate() == true
      gate.releasePermit(Runnable::run);
      coordinator.notifyCallCompleted(DUMMY_CALL);

      // cancelDebounceTimer() in triggerContinuation must cancel the timer and clear reference
      assertThat(continuationRan.get()).isTrue();
      assertThat(scheduledTask.isCancelled()).isTrue();
      assertThat(mayInterruptArg.get()).isFalse();
      assertThat(coordinator.hasScheduledDebounceTimer()).isFalse();
    } finally {
      scheduler.shutdownNow();
    }
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
