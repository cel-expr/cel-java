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

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.util.concurrent.Futures.immediateFuture;
import static com.google.common.util.concurrent.MoreExecutors.newDirectExecutorService;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import dev.cel.common.CelOptions;
import dev.cel.common.values.CelValueConverter;
import dev.cel.common.values.NullValue;
import dev.cel.runtime.AccumulatedUnknowns;
import dev.cel.runtime.CelAsyncCall;
import dev.cel.runtime.CelAsyncEvaluationOptions;
import dev.cel.runtime.CelAsyncObserver;
import dev.cel.runtime.CelEvaluationException;
import dev.cel.runtime.RuntimeEquality;
import dev.cel.runtime.RuntimeHelpers;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(TestParameterInjector.class)
public final class AsyncCallStateTrackerTest {

  private final RuntimeEquality runtimeEquality =
      RuntimeEquality.create(RuntimeHelpers.create(), CelOptions.DEFAULT);
  private final ListeningExecutorService directExecutor = newDirectExecutorService();

  @Test
  @SuppressWarnings("Immutable") // Test only
  public void recordOrGet_defersDispatchUntilDispatchPendingCalls() throws Exception {
    AsyncCallStateTracker tracker = AsyncCallStateTracker.create(runtimeEquality);
    AtomicBoolean overloadCalled = new AtomicBoolean(false);

    Object result =
        tracker.recordOrGet(
            1L,
            "myFunc",
            "myFunc_overload",
            new Object[] {"arg"},
            args -> {
              overloadCalled.set(true);
              return immediateFuture("ok");
            },
            CelValueConverter.getDefaultInstance());

    assertThat(result).isInstanceOf(AccumulatedUnknowns.class);
    assertThat(overloadCalled.get()).isFalse();
    assertThat(tracker.hasInFlightCalls()).isFalse();
  }

  @Test
  @SuppressWarnings("Immutable") // Test only
  public void dispatchPendingCalls_onlyLaunchesRequiredCallIds() throws Exception {
    AsyncCallStateTracker tracker = AsyncCallStateTracker.create(runtimeEquality);
    AsyncGate gate = AsyncGate.create(10);
    CelAsyncEvaluationOptions options = CelAsyncEvaluationOptions.defaultOptions();
    AsyncCompletionCoordinator coordinator =
        AsyncCompletionCoordinator.create(options, gate, directExecutor, t -> {});
    AtomicBoolean call1Executed = new AtomicBoolean(false);
    AtomicBoolean call2Executed = new AtomicBoolean(false);
    AccumulatedUnknowns unk1 =
        (AccumulatedUnknowns)
            tracker.recordOrGet(
                1L,
                "func1",
                "func1_ov",
                new Object[] {"a"},
                args -> {
                  call1Executed.set(true);
                  return immediateFuture("res1");
                },
                CelValueConverter.getDefaultInstance());
    tracker.recordOrGet(
        2L,
        "func2",
        "func2_ov",
        new Object[] {"b"},
        args -> {
          call2Executed.set(true);
          return immediateFuture("res2");
        },
        CelValueConverter.getDefaultInstance());

    tracker.dispatchPendingCalls(
        unk1.callIds(), directExecutor, gate, coordinator, /* observer= */ null);

    assertThat(call1Executed.get()).isTrue();
    assertThat(call2Executed.get()).isFalse();
  }

  @Test
  @SuppressWarnings("Immutable") // Test only
  public void dispatchPendingCalls_cancelledBeforeRun_releasesPermitAndReturns() throws Exception {
    AsyncCallStateTracker tracker = AsyncCallStateTracker.create(runtimeEquality);
    AsyncGate gate = AsyncGate.create(1);
    CelAsyncEvaluationOptions options = CelAsyncEvaluationOptions.defaultOptions();
    List<Runnable> queuedTasks = new ArrayList<>();
    ListeningExecutorService mockExecutor =
        MoreExecutors.listeningDecorator(
            new AbstractExecutorService() {
              @Override
              public void shutdown() {}

              @Override
              public ImmutableList<Runnable> shutdownNow() {
                return ImmutableList.of();
              }

              @Override
              public boolean isShutdown() {
                return false;
              }

              @Override
              public boolean isTerminated() {
                return false;
              }

              @Override
              public boolean awaitTermination(long timeout, TimeUnit unit) {
                return true;
              }

              @Override
              public void execute(Runnable command) {
                queuedTasks.add(command);
              }
            });
    AsyncCompletionCoordinator coordinator =
        AsyncCompletionCoordinator.create(options, gate, mockExecutor, t -> {});
    AtomicBoolean overloadCalled = new AtomicBoolean(false);
    AccumulatedUnknowns unknowns =
        (AccumulatedUnknowns)
            tracker.recordOrGet(
                1L,
                "myFunc",
                "myFunc_overload",
                new Object[] {"arg"},
                args -> {
                  overloadCalled.set(true);
                  return immediateFuture("ok");
                },
                CelValueConverter.getDefaultInstance());
    tracker.dispatchPendingCalls(
        unknowns.callIds(), mockExecutor, gate, coordinator, /* observer= */ null);

    tracker.cancelInFlight();
    queuedTasks.get(0).run();

    assertThat(overloadCalled.get()).isFalse();
    assertThat(gate.activeCount()).isEqualTo(0);
    assertThat(tracker.hasInFlightCalls()).isFalse();
  }

  @Test
  @SuppressWarnings("Immutable") // Test only
  public void dispatchPendingCalls_atMaxConcurrency_leavesUnstarted() throws Exception {
    AsyncCallStateTracker tracker = AsyncCallStateTracker.create(runtimeEquality);
    AsyncGate gate = AsyncGate.create(1);
    checkState(gate.tryAcquire(), "Failed to acquire permit");
    CelAsyncEvaluationOptions options = CelAsyncEvaluationOptions.defaultOptions();
    List<Runnable> queuedTasks = new ArrayList<>();
    ListeningExecutorService mockExecutor =
        MoreExecutors.listeningDecorator(
            new AbstractExecutorService() {
              @Override
              public void shutdown() {}

              @Override
              public ImmutableList<Runnable> shutdownNow() {
                return ImmutableList.of();
              }

              @Override
              public boolean isShutdown() {
                return false;
              }

              @Override
              public boolean isTerminated() {
                return false;
              }

              @Override
              public boolean awaitTermination(long timeout, TimeUnit unit) {
                return true;
              }

              @Override
              public void execute(Runnable command) {
                queuedTasks.add(command);
              }
            });
    AsyncCompletionCoordinator coordinator =
        AsyncCompletionCoordinator.create(options, gate, mockExecutor, t -> {});
    AtomicBoolean overloadCalled = new AtomicBoolean(false);
    AccumulatedUnknowns unknowns =
        (AccumulatedUnknowns)
            tracker.recordOrGet(
                1L,
                "myFunc",
                "myFunc_overload",
                new Object[] {"arg"},
                args -> {
                  overloadCalled.set(true);
                  return immediateFuture("ok");
                },
                CelValueConverter.getDefaultInstance());

    tracker.dispatchPendingCalls(
        unknowns.callIds(), mockExecutor, gate, coordinator, /* observer= */ null);

    assertThat(queuedTasks).isEmpty();
    assertThat(overloadCalled.get()).isFalse();
    assertThat(tracker.hasInFlightCalls()).isFalse();
  }

  @Test
  @SuppressWarnings("Immutable") // Test only
  public void dispatchPendingCalls_unstartedTaskOnSubsequentStep_acquiresPermitAndDispatches()
      throws Exception {
    AsyncCallStateTracker tracker = AsyncCallStateTracker.create(runtimeEquality);
    AsyncGate gate = AsyncGate.create(1);
    checkState(gate.tryAcquire(), "Failed to acquire permit");
    CelAsyncEvaluationOptions options = CelAsyncEvaluationOptions.defaultOptions();
    List<Runnable> queuedTasks = new ArrayList<>();
    ListeningExecutorService mockExecutor =
        MoreExecutors.listeningDecorator(
            new AbstractExecutorService() {
              @Override
              public void shutdown() {}

              @Override
              public ImmutableList<Runnable> shutdownNow() {
                return ImmutableList.of();
              }

              @Override
              public boolean isShutdown() {
                return false;
              }

              @Override
              public boolean isTerminated() {
                return false;
              }

              @Override
              public boolean awaitTermination(long timeout, TimeUnit unit) {
                return true;
              }

              @Override
              public void execute(Runnable command) {
                queuedTasks.add(command);
              }
            });
    AsyncCompletionCoordinator coordinator =
        AsyncCompletionCoordinator.create(options, gate, mockExecutor, t -> {});
    AtomicBoolean overloadCalled = new AtomicBoolean(false);
    AccumulatedUnknowns unknowns =
        (AccumulatedUnknowns)
            tracker.recordOrGet(
                1L,
                "myFunc",
                "myFunc_overload",
                new Object[] {"arg"},
                args -> {
                  overloadCalled.set(true);
                  return immediateFuture("ok");
                },
                CelValueConverter.getDefaultInstance());
    tracker.dispatchPendingCalls(
        unknowns.callIds(), mockExecutor, gate, coordinator, /* observer= */ null);
    gate.release();

    tracker.dispatchPendingCalls(
        unknowns.callIds(), mockExecutor, gate, coordinator, /* observer= */ null);
    queuedTasks.get(0).run();

    assertThat(overloadCalled.get()).isTrue();
    assertThat(gate.activeCount()).isEqualTo(0);
  }

  @Test
  public void recordOrGet_deduplicatesCallsWithSameKey() throws Exception {
    AsyncCallStateTracker tracker = AsyncCallStateTracker.create(runtimeEquality);

    Object first =
        tracker.recordOrGet(
            10L,
            "myFunc",
            "myFunc_overload",
            new Object[] {"x"},
            args -> SettableFuture.create(),
            CelValueConverter.getDefaultInstance());
    Object second =
        tracker.recordOrGet(
            10L,
            "myFunc",
            "myFunc_overload",
            new Object[] {"x"},
            args -> SettableFuture.create(),
            CelValueConverter.getDefaultInstance());

    assertThat(first).isInstanceOf(AccumulatedUnknowns.class);
    assertThat(second).isInstanceOf(AccumulatedUnknowns.class);
    assertThat(((AccumulatedUnknowns) first).callIds())
        .containsExactlyElementsIn(((AccumulatedUnknowns) second).callIds());
  }

  @Test
  @SuppressWarnings("Immutable") // Test only
  public void hasInFlightCalls_withInFlightCalls_returnsTrue() throws Exception {
    AsyncCallStateTracker tracker = AsyncCallStateTracker.create(runtimeEquality);
    AsyncGate gate = AsyncGate.create(0);
    CelAsyncEvaluationOptions options = CelAsyncEvaluationOptions.defaultOptions();
    AsyncCompletionCoordinator coordinator =
        AsyncCompletionCoordinator.create(options, gate, directExecutor, t -> {});
    SettableFuture<Object> pendingFuture = SettableFuture.create();
    AccumulatedUnknowns unknowns =
        (AccumulatedUnknowns)
            tracker.recordOrGet(
                10L,
                "myFunc",
                "myFunc_overload",
                new Object[] {"x"},
                args -> pendingFuture,
                CelValueConverter.getDefaultInstance());

    tracker.dispatchPendingCalls(
        unknowns.callIds(), directExecutor, gate, coordinator, /* observer= */ null);

    assertThat(tracker.hasInFlightCalls()).isTrue();
  }

  @Test
  @SuppressWarnings("Immutable") // Test only
  public void hasInFlightCalls_whenCallsComplete_returnsFalse() throws Exception {
    AsyncCallStateTracker tracker = AsyncCallStateTracker.create(runtimeEquality);
    AsyncGate gate = AsyncGate.create(0);
    CelAsyncEvaluationOptions options = CelAsyncEvaluationOptions.defaultOptions();
    AsyncCompletionCoordinator coordinator =
        AsyncCompletionCoordinator.create(options, gate, directExecutor, t -> {});
    SettableFuture<Object> pendingFuture = SettableFuture.create();
    AccumulatedUnknowns unknowns =
        (AccumulatedUnknowns)
            tracker.recordOrGet(
                10L,
                "myFunc",
                "myFunc_overload",
                new Object[] {"x"},
                args -> pendingFuture,
                CelValueConverter.getDefaultInstance());
    tracker.dispatchPendingCalls(
        unknowns.callIds(), directExecutor, gate, coordinator, /* observer= */ null);

    pendingFuture.set("done");

    assertThat(tracker.hasInFlightCalls()).isFalse();
  }

  @Test
  public void recordOrGet_existingKey_doesNotAllocateNewCallId() throws Exception {
    AsyncCallStateTracker tracker = AsyncCallStateTracker.create(runtimeEquality);

    Object first =
        tracker.recordOrGet(
            10L,
            "fn",
            "fn_overload",
            new Object[] {"x"},
            args -> SettableFuture.create(),
            CelValueConverter.getDefaultInstance());
    Object second =
        tracker.recordOrGet(
            10L,
            "fn",
            "fn_overload",
            new Object[] {"x"},
            args -> SettableFuture.create(),
            CelValueConverter.getDefaultInstance());
    Object third =
        tracker.recordOrGet(
            20L,
            "fn",
            "fn_overload",
            new Object[] {"y"},
            args -> SettableFuture.create(),
            CelValueConverter.getDefaultInstance());

    assertThat(((AccumulatedUnknowns) first).callIds()).containsExactly(1L);
    assertThat(((AccumulatedUnknowns) second).callIds()).containsExactly(1L);
    assertThat(((AccumulatedUnknowns) third).callIds()).containsExactly(2L);
  }

  @Test
  @SuppressWarnings("Immutable") // Test only
  public void dispatchPendingCalls_successfulExecution_notifiesObserverWithArgsAndResult()
      throws Exception {
    AsyncCallStateTracker tracker = AsyncCallStateTracker.create(runtimeEquality);
    AsyncGate gate = AsyncGate.create(0);
    CelAsyncEvaluationOptions options = CelAsyncEvaluationOptions.defaultOptions();
    AsyncCompletionCoordinator coordinator =
        AsyncCompletionCoordinator.create(options, gate, directExecutor, t -> {});
    AtomicReference<CelAsyncCall> startedCall = new AtomicReference<>();
    AtomicReference<ImmutableList<Object>> startedArgs = new AtomicReference<>();
    AtomicReference<CelAsyncCall> finishedCall = new AtomicReference<>();
    AtomicReference<Object> finishedResult = new AtomicReference<>();
    CelAsyncObserver observer =
        new CelAsyncObserver() {
          @Override
          public void onCallStarted(CelAsyncCall call, ImmutableList<Object> args) {
            startedCall.set(call);
            startedArgs.set(args);
          }

          @Override
          public void onCallFinished(CelAsyncCall call, Object result, Throwable error) {
            finishedCall.set(call);
            finishedResult.set(result);
          }
        };
    SettableFuture<Object> pendingFuture = SettableFuture.create();
    AccumulatedUnknowns unknowns =
        (AccumulatedUnknowns)
            tracker.recordOrGet(
                10L,
                "fn",
                "fn_overload",
                new Object[] {"x"},
                args -> pendingFuture,
                CelValueConverter.getDefaultInstance());

    tracker.dispatchPendingCalls(unknowns.callIds(), directExecutor, gate, coordinator, observer);
    pendingFuture.set("done");

    assertThat(startedCall.get()).isNotNull();
    assertThat(startedCall.get().functionName()).isEqualTo("fn");
    assertThat(startedArgs.get()).containsExactly("x");
    assertThat(finishedCall.get()).isNotNull();
    assertThat(finishedResult.get()).isEqualTo("done");
  }

  @Test
  @SuppressWarnings("Immutable") // Test only
  public void dispatchPendingCalls_synchronousException_notifiesObserverWithArgsAndError()
      throws Exception {
    AsyncCallStateTracker tracker = AsyncCallStateTracker.create(runtimeEquality);
    AsyncGate gate = AsyncGate.create(0);
    CelAsyncEvaluationOptions options = CelAsyncEvaluationOptions.defaultOptions();
    AsyncCompletionCoordinator coordinator =
        AsyncCompletionCoordinator.create(options, gate, directExecutor, t -> {});
    AtomicReference<ImmutableList<Object>> startedArgs = new AtomicReference<>();
    AtomicReference<Throwable> finishedError = new AtomicReference<>();
    CelAsyncObserver observer =
        new CelAsyncObserver() {
          @Override
          public void onCallStarted(CelAsyncCall call, ImmutableList<Object> args) {
            startedArgs.set(args);
          }

          @Override
          public void onCallFinished(CelAsyncCall call, Object result, Throwable error) {
            finishedError.set(error);
          }
        };
    RuntimeException expectedError = new RuntimeException("fail");
    AccumulatedUnknowns unknowns =
        (AccumulatedUnknowns)
            tracker.recordOrGet(
                10L,
                "fn",
                "fn_overload",
                new Object[] {"x"},
                args -> {
                  throw expectedError;
                },
                CelValueConverter.getDefaultInstance());

    tracker.dispatchPendingCalls(unknowns.callIds(), directExecutor, gate, coordinator, observer);

    assertThat(startedArgs.get()).containsExactly("x");
    assertThat(finishedError.get()).isSameInstanceAs(expectedError);
  }

  @Test
  @SuppressWarnings("Immutable") // Test only
  public void dispatchPendingCalls_asynchronousException_notifiesObserverWithArgsAndError()
      throws Exception {
    AsyncCallStateTracker tracker = AsyncCallStateTracker.create(runtimeEquality);
    AsyncGate gate = AsyncGate.create(0);
    CelAsyncEvaluationOptions options = CelAsyncEvaluationOptions.defaultOptions();
    AsyncCompletionCoordinator coordinator =
        AsyncCompletionCoordinator.create(options, gate, directExecutor, t -> {});
    AtomicReference<ImmutableList<Object>> startedArgs = new AtomicReference<>();
    AtomicReference<Throwable> finishedError = new AtomicReference<>();
    CelAsyncObserver observer =
        new CelAsyncObserver() {
          @Override
          public void onCallStarted(CelAsyncCall call, ImmutableList<Object> args) {
            startedArgs.set(args);
          }

          @Override
          public void onCallFinished(CelAsyncCall call, Object result, Throwable error) {
            finishedError.set(error);
          }
        };
    SettableFuture<Object> pendingFuture = SettableFuture.create();
    RuntimeException expectedError = new RuntimeException("async fail");
    AccumulatedUnknowns unknowns =
        (AccumulatedUnknowns)
            tracker.recordOrGet(
                10L,
                "fn",
                "fn_overload",
                new Object[] {"x"},
                args -> pendingFuture,
                CelValueConverter.getDefaultInstance());

    tracker.dispatchPendingCalls(unknowns.callIds(), directExecutor, gate, coordinator, observer);
    pendingFuture.setException(expectedError);

    assertThat(startedArgs.get()).containsExactly("x");
    assertThat(finishedError.get()).isSameInstanceAs(expectedError);
  }

  @Test
  @SuppressWarnings("Immutable") // Test only
  public void dispatchPendingCalls_asynchronousException_withoutObserver_doesNotThrow()
      throws Exception {
    AsyncCallStateTracker tracker = AsyncCallStateTracker.create(runtimeEquality);
    AsyncGate gate = AsyncGate.create(0);
    CelAsyncEvaluationOptions options = CelAsyncEvaluationOptions.defaultOptions();
    AsyncCompletionCoordinator coordinator =
        AsyncCompletionCoordinator.create(options, gate, directExecutor, t -> {});
    SettableFuture<Object> pendingFuture = SettableFuture.create();
    AccumulatedUnknowns unknowns =
        (AccumulatedUnknowns)
            tracker.recordOrGet(
                10L,
                "fn",
                "fn_overload",
                new Object[] {"x"},
                args -> pendingFuture,
                CelValueConverter.getDefaultInstance());

    tracker.dispatchPendingCalls(
        unknowns.callIds(), directExecutor, gate, coordinator, /* observer= */ null);
    pendingFuture.setException(new RuntimeException("async fail"));

    assertThat(tracker.hasInFlightCalls()).isFalse();
  }

  @Test
  public void dispatchPendingCalls_executorRejection_releasesPermitAndFailsRecord()
      throws Exception {
    AsyncCallStateTracker tracker = AsyncCallStateTracker.create(runtimeEquality);
    AsyncGate gate = AsyncGate.create(1);
    CelAsyncEvaluationOptions options = CelAsyncEvaluationOptions.defaultOptions();
    ListeningExecutorService rejectingExecutor =
        MoreExecutors.listeningDecorator(
            new AbstractExecutorService() {
              @Override
              public void shutdown() {}

              @Override
              public ImmutableList<Runnable> shutdownNow() {
                return ImmutableList.of();
              }

              @Override
              public boolean isShutdown() {
                return false;
              }

              @Override
              public boolean isTerminated() {
                return false;
              }

              @Override
              public boolean awaitTermination(long timeout, TimeUnit unit) {
                return true;
              }

              @Override
              public void execute(Runnable command) {
                throw new RejectedExecutionException("pool full");
              }
            });
    AsyncCompletionCoordinator coordinator =
        AsyncCompletionCoordinator.create(options, gate, directExecutor, t -> {});
    AccumulatedUnknowns unknowns =
        (AccumulatedUnknowns)
            tracker.recordOrGet(
                10L,
                "fn",
                "fn_overload",
                new Object[] {"x"},
                args -> immediateFuture("done"),
                CelValueConverter.getDefaultInstance());

    tracker.dispatchPendingCalls(
        unknowns.callIds(), rejectingExecutor, gate, coordinator, /* observer= */ null);

    assertThat(gate.activeCount()).isEqualTo(0);
    assertThat(tracker.hasInFlightCalls()).isFalse();
  }

  @Test
  public void dispatchPendingCalls_applyAsyncReturnsNull_failsGracefullyAndReleasesPermit()
      throws Exception {
    AsyncCallStateTracker tracker = AsyncCallStateTracker.create(runtimeEquality);
    AsyncGate gate = AsyncGate.create(1);
    CelAsyncEvaluationOptions options = CelAsyncEvaluationOptions.defaultOptions();
    AsyncCompletionCoordinator coordinator =
        AsyncCompletionCoordinator.create(options, gate, directExecutor, t -> {});
    AccumulatedUnknowns unknowns =
        (AccumulatedUnknowns)
            tracker.recordOrGet(
                10L,
                "fn",
                "fn_overload",
                new Object[] {"x"},
                args -> null,
                CelValueConverter.getDefaultInstance());

    tracker.dispatchPendingCalls(
        unknowns.callIds(), directExecutor, gate, coordinator, /* observer= */ null);

    assertThat(gate.activeCount()).isEqualTo(0);
    assertThat(tracker.hasInFlightCalls()).isFalse();
  }

  @Test
  public void recordOrGet_afterSynchronousCompletion_returnsResultDirectly() throws Exception {
    AsyncCallStateTracker tracker = AsyncCallStateTracker.create(runtimeEquality);
    AsyncGate gate = AsyncGate.create(1);
    CelAsyncEvaluationOptions options = CelAsyncEvaluationOptions.defaultOptions();
    AsyncCompletionCoordinator coordinator =
        AsyncCompletionCoordinator.create(options, gate, directExecutor, t -> {});
    AccumulatedUnknowns unknowns =
        (AccumulatedUnknowns)
            tracker.recordOrGet(
                10L,
                "fn",
                "fn_overload",
                new Object[] {"x"},
                args -> immediateFuture("syncSuccess"),
                CelValueConverter.getDefaultInstance());
    tracker.dispatchPendingCalls(
        unknowns.callIds(), directExecutor, gate, coordinator, /* observer= */ null);

    Object result =
        tracker.recordOrGet(
            10L,
            "fn",
            "fn_overload",
            new Object[] {"x"},
            args -> immediateFuture("syncSuccess"),
            CelValueConverter.getDefaultInstance());

    assertThat(result).isEqualTo("syncSuccess");
    assertThat(gate.activeCount()).isEqualTo(0);
    assertThat(tracker.hasInFlightCalls()).isFalse();
  }

  @Test
  public void dispatchPendingCalls_observerThrowsException_doesNotDisruptEvaluationOrPermits()
      throws Exception {
    AsyncCallStateTracker tracker = AsyncCallStateTracker.create(runtimeEquality);
    AsyncGate gate = AsyncGate.create(1);
    CelAsyncEvaluationOptions options = CelAsyncEvaluationOptions.defaultOptions();
    AsyncCompletionCoordinator coordinator =
        AsyncCompletionCoordinator.create(options, gate, directExecutor, t -> {});
    CelAsyncObserver throwingObserver =
        new CelAsyncObserver() {
          @Override
          public void onCallStarted(CelAsyncCall call, ImmutableList<Object> args) {
            throw new RuntimeException("observer started failure");
          }

          @Override
          public void onCallFinished(CelAsyncCall call, Object result, Throwable error) {
            throw new RuntimeException("observer finished failure");
          }
        };
    AccumulatedUnknowns unknowns =
        (AccumulatedUnknowns)
            tracker.recordOrGet(
                10L,
                "fn",
                "fn_overload",
                new Object[] {"x"},
                args -> immediateFuture("res"),
                CelValueConverter.getDefaultInstance());

    tracker.dispatchPendingCalls(
        unknowns.callIds(), directExecutor, gate, coordinator, throwingObserver);
    Object result =
        tracker.recordOrGet(
            10L,
            "fn",
            "fn_overload",
            new Object[] {"x"},
            args -> immediateFuture("res"),
            CelValueConverter.getDefaultInstance());

    assertThat(result).isEqualTo("res");
    assertThat(gate.activeCount()).isEqualTo(0);
    assertThat(tracker.hasInFlightCalls()).isFalse();
  }

  @Test
  @SuppressWarnings("Immutable") // Test only
  public void dispatchPendingCalls_concurrentRace_threadContentionHandledSafely() throws Exception {
    AsyncCallStateTracker tracker = AsyncCallStateTracker.create(runtimeEquality);
    AsyncGate gate = AsyncGate.create(1);
    CelAsyncEvaluationOptions options = CelAsyncEvaluationOptions.defaultOptions();
    AsyncCompletionCoordinator coordinator =
        AsyncCompletionCoordinator.create(options, gate, directExecutor, t -> {});
    AtomicInteger callsDispatched = new AtomicInteger(0);
    AccumulatedUnknowns unknowns =
        (AccumulatedUnknowns)
            tracker.recordOrGet(
                10L,
                "fn",
                "fn_overload",
                new Object[] {"sameArg"},
                args -> {
                  callsDispatched.incrementAndGet();
                  return immediateFuture("result");
                },
                CelValueConverter.getDefaultInstance());
    int threadCount = 8;
    ExecutorService pool = Executors.newFixedThreadPool(threadCount);
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch doneLatch = new CountDownLatch(threadCount);
    for (int i = 0; i < threadCount; i++) {
      pool.execute(
          () -> {
            try {
              startLatch.await();
              tracker.dispatchPendingCalls(
                  unknowns.callIds(), directExecutor, gate, coordinator, /* observer= */ null);
            } catch (Exception e) {
              throw new RuntimeException(e);
            } finally {
              doneLatch.countDown();
            }
          });
    }

    startLatch.countDown();
    boolean completed = doneLatch.await(5, TimeUnit.SECONDS);
    pool.shutdown();

    assertThat(completed).isTrue();
    assertThat(callsDispatched.get()).isEqualTo(1);
    assertThat(gate.activeCount()).isEqualTo(0);
    assertThat(tracker.hasInFlightCalls()).isFalse();
  }

  @Test
  @SuppressWarnings("Immutable") // Test only
  public void
      dispatchPendingCalls_whenCancelledInFlightFutureCompletesNormally_releasesPermitWithoutLeaking()
          throws Exception {
    AsyncCallStateTracker tracker = AsyncCallStateTracker.create(runtimeEquality);
    AsyncGate gate = AsyncGate.create(1);
    CelAsyncEvaluationOptions options = CelAsyncEvaluationOptions.defaultOptions();
    AsyncCompletionCoordinator coordinator =
        AsyncCompletionCoordinator.create(options, gate, directExecutor, t -> {});
    SettableFuture<Object> pendingFuture = SettableFuture.create();
    AccumulatedUnknowns unknowns =
        (AccumulatedUnknowns)
            tracker.recordOrGet(
                10L,
                "fn",
                "fn_overload",
                new Object[] {"x"},
                args -> pendingFuture,
                CelValueConverter.getDefaultInstance());

    tracker.dispatchPendingCalls(
        unknowns.callIds(), directExecutor, gate, coordinator, /* observer= */ null);
    tracker.cancelInFlight();
    pendingFuture.set("done");

    assertThat(gate.activeCount()).isEqualTo(0);
    assertThat(tracker.hasInFlightCalls()).isFalse();
  }

  @Test
  @SuppressWarnings("Immutable") // Test only
  public void dispatchPendingCalls_whenCancelledInFlightFutureFails_releasesPermitWithoutLeaking()
      throws Exception {
    AsyncCallStateTracker tracker = AsyncCallStateTracker.create(runtimeEquality);
    AsyncGate gate = AsyncGate.create(1);
    CelAsyncEvaluationOptions options = CelAsyncEvaluationOptions.defaultOptions();
    AsyncCompletionCoordinator coordinator =
        AsyncCompletionCoordinator.create(options, gate, directExecutor, t -> {});
    SettableFuture<Object> pendingFuture = SettableFuture.create();
    AccumulatedUnknowns unknowns =
        (AccumulatedUnknowns)
            tracker.recordOrGet(
                10L,
                "fn",
                "fn_overload",
                new Object[] {"x"},
                args -> pendingFuture,
                CelValueConverter.getDefaultInstance());

    tracker.dispatchPendingCalls(
        unknowns.callIds(), directExecutor, gate, coordinator, /* observer= */ null);
    tracker.cancelInFlight();
    pendingFuture.setException(new RuntimeException("failed"));

    assertThat(gate.activeCount()).isEqualTo(0);
    assertThat(tracker.hasInFlightCalls()).isFalse();
  }

  @Test
  @SuppressWarnings("Immutable") // Test only
  public void recordOrGet_whenEncounteringCancelledRecord_throwsCelEvaluationException()
      throws Exception {
    AsyncCallStateTracker tracker = AsyncCallStateTracker.create(runtimeEquality);
    tracker.recordOrGet(
        10L,
        "fn",
        "fn_overload",
        new Object[] {"x"},
        args -> immediateFuture("done"),
        CelValueConverter.getDefaultInstance());

    tracker.cancelInFlight();

    CelEvaluationException e =
        assertThrows(
            CelEvaluationException.class,
            () ->
                tracker.recordOrGet(
                    10L,
                    "fn",
                    "fn_overload",
                    new Object[] {"x"},
                    args -> immediateFuture("done"),
                    CelValueConverter.getDefaultInstance()));
    assertThat(e).hasMessageThat().contains("was cancelled");
    assertThat(e).hasCauseThat().isInstanceOf(CancellationException.class);
  }

  @Test
  public void recordOrGet_concurrentRegistrationSameKey_deduplicatesToSingleCallId()
      throws Exception {
    AsyncCallStateTracker tracker = AsyncCallStateTracker.create(runtimeEquality);
    int threadCount = 16;
    ExecutorService pool = Executors.newFixedThreadPool(threadCount);
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch doneLatch = new CountDownLatch(threadCount);
    List<AccumulatedUnknowns> results = new ArrayList<>();

    for (int i = 0; i < threadCount; i++) {
      pool.execute(
          () -> {
            try {
              startLatch.await();
              AccumulatedUnknowns unknowns =
                  (AccumulatedUnknowns)
                      tracker.recordOrGet(
                          10L,
                          "fn",
                          "fn_overload",
                          new Object[] {"arg"},
                          args -> immediateFuture("done"),
                          CelValueConverter.getDefaultInstance());
              synchronized (results) {
                results.add(unknowns);
              }
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
              throw new AssertionError("Interrupted during test", e);
            } catch (CelEvaluationException e) {
              throw new AssertionError("Unexpected evaluation exception", e);
            } finally {
              doneLatch.countDown();
            }
          });
    }

    startLatch.countDown();
    boolean completed = doneLatch.await(5, TimeUnit.SECONDS);
    pool.shutdown();

    assertThat(completed).isTrue();
    assertThat(results).hasSize(threadCount);
    long canonicalCallId = results.get(0).callIds().iterator().next();
    for (AccumulatedUnknowns result : results) {
      assertThat(result.callIds()).containsExactly(canonicalCallId);
    }
  }

  @Test
  @SuppressWarnings("Immutable") // Test only
  public void
      dispatchPendingCalls_whenRecordCancelledWhileQueuedInExecutor_releasesPermitWithoutExecutingOverload()
          throws Exception {
    AsyncCallStateTracker tracker = AsyncCallStateTracker.create(runtimeEquality);
    AsyncGate gate = AsyncGate.create(1);
    CelAsyncEvaluationOptions options = CelAsyncEvaluationOptions.defaultOptions();
    AsyncCompletionCoordinator coordinator =
        AsyncCompletionCoordinator.create(options, gate, directExecutor, t -> {});
    AtomicBoolean overloadExecuted = new AtomicBoolean(false);
    AccumulatedUnknowns unknowns =
        (AccumulatedUnknowns)
            tracker.recordOrGet(
                10L,
                "fn",
                "fn_overload",
                new Object[] {"x"},
                args -> {
                  overloadExecuted.set(true);
                  return immediateFuture("done");
                },
                CelValueConverter.getDefaultInstance());
    AtomicReference<Runnable> capturedTask = new AtomicReference<>();
    ListeningExecutorService manualExecutor =
        MoreExecutors.listeningDecorator(
            new AbstractExecutorService() {
              @Override
              public void execute(Runnable command) {
                capturedTask.set(command);
              }

              @Override
              public void shutdown() {}

              @Override
              public List<Runnable> shutdownNow() {
                return ImmutableList.of();
              }

              @Override
              public boolean isShutdown() {
                return false;
              }

              @Override
              public boolean isTerminated() {
                return false;
              }

              @Override
              public boolean awaitTermination(long timeout, TimeUnit unit) {
                return true;
              }
            });
    tracker.dispatchPendingCalls(
        unknowns.callIds(), manualExecutor, gate, coordinator, /* observer= */ null);
    checkState(capturedTask.get() != null, "Task must be captured by executor");

    tracker.cancelInFlight();
    capturedTask.get().run();

    assertThat(gate.activeCount()).isEqualTo(0);
    assertThat(overloadExecuted.get()).isFalse();
    assertThat(tracker.hasInFlightCalls()).isFalse();
  }

  @Test
  @SuppressWarnings("Immutable") // Test only
  public void handleFailure_whenRecordCancelledInFlightBeforeFailure_doesNotNotifyObserver()
      throws Exception {
    AsyncCallStateTracker tracker = AsyncCallStateTracker.create(runtimeEquality);
    AsyncGate gate = AsyncGate.create(1);
    CelAsyncEvaluationOptions options = CelAsyncEvaluationOptions.defaultOptions();
    AsyncCompletionCoordinator coordinator =
        AsyncCompletionCoordinator.create(options, gate, directExecutor, t -> {});
    SettableFuture<Object> future = SettableFuture.create();
    AtomicBoolean observerNotified = new AtomicBoolean(false);
    CelAsyncObserver observer =
        new CelAsyncObserver() {
          @Override
          public void onCallStarted(CelAsyncCall call, ImmutableList<Object> args) {}

          @Override
          public void onCallFinished(
              CelAsyncCall call, @Nullable Object result, @Nullable Throwable error) {
            observerNotified.set(true);
          }
        };
    AccumulatedUnknowns unknowns =
        (AccumulatedUnknowns)
            tracker.recordOrGet(
                10L,
                "fn",
                "fn_overload",
                new Object[] {"x"},
                args -> future,
                CelValueConverter.getDefaultInstance());
    tracker.dispatchPendingCalls(unknowns.callIds(), directExecutor, gate, coordinator, observer);

    tracker.cancelInFlight();
    future.setException(new RuntimeException("Late failure after cancel"));

    assertThat(observerNotified.get()).isFalse();
    assertThat(gate.activeCount()).isEqualTo(0);
  }

  @Test
  public void
      tryLaunch_whenRecordCancelledPriorToLaunch_releasesGatePermitWithoutDispatchingTask() {
    AsyncCallStateTracker tracker = AsyncCallStateTracker.create(runtimeEquality);
    AsyncGate gate = AsyncGate.create(1);
    CelAsyncEvaluationOptions options = CelAsyncEvaluationOptions.defaultOptions();
    AsyncCompletionCoordinator coordinator =
        AsyncCompletionCoordinator.create(options, gate, directExecutor, t -> {});
    AsyncCallRecord record =
        AsyncCallRecord.create(
            100L, 10L, "fn", "fn_overload", new Object[] {"x"}, args -> immediateFuture("done"));
    record.cancel();

    tracker.tryLaunch(record, directExecutor, gate, coordinator, /* observer= */ null);

    assertThat(gate.activeCount()).isEqualTo(0);
    assertThat(record.state()).isEqualTo(AsyncCallRecord.State.CANCELLED);
  }

  @Test
  @SuppressWarnings("Immutable") // Test only
  public void recordOrGet_whenEncounteringFailedRecord_throwsCelEvaluationException()
      throws Exception {
    AsyncCallStateTracker tracker = AsyncCallStateTracker.create(runtimeEquality);
    AsyncGate gate = AsyncGate.create(1);
    CelAsyncEvaluationOptions options = CelAsyncEvaluationOptions.defaultOptions();
    AsyncCompletionCoordinator coordinator =
        AsyncCompletionCoordinator.create(options, gate, directExecutor, t -> {});
    SettableFuture<Object> future = SettableFuture.create();
    AccumulatedUnknowns unknowns =
        (AccumulatedUnknowns)
            tracker.recordOrGet(
                10L,
                "fn",
                "fn_overload",
                new Object[] {"x"},
                args -> future,
                CelValueConverter.getDefaultInstance());
    tracker.dispatchPendingCalls(
        unknowns.callIds(), directExecutor, gate, coordinator, /* observer= */ null);
    future.setException(new IllegalArgumentException("computation failed"));

    CelEvaluationException e =
        assertThrows(
            CelEvaluationException.class,
            () ->
                tracker.recordOrGet(
                    10L,
                    "fn",
                    "fn_overload",
                    new Object[] {"x"},
                    args -> future,
                    CelValueConverter.getDefaultInstance()));
    assertThat(e).hasMessageThat().contains("computation failed");
    assertThat(e).hasCauseThat().isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  public void recordOrGet_nullCelValueConverter_throwsNullPointerException() {
    AsyncCallStateTracker tracker = AsyncCallStateTracker.create(runtimeEquality);

    assertThrows(
        NullPointerException.class,
        () ->
            tracker.recordOrGet(
                10L,
                "fn",
                "fn_overload",
                new Object[] {"x"},
                args -> immediateFuture("done"),
                null));
  }

  @Test
  @SuppressWarnings("Immutable") // Test only
  public void tryLaunch_whenRecordAlreadyMarkedRunning_releasesGatePermitAndDoesNotExecuteTask() {
    AsyncCallStateTracker tracker = AsyncCallStateTracker.create(runtimeEquality);
    AsyncGate gate = AsyncGate.create(1);
    CelAsyncEvaluationOptions options = CelAsyncEvaluationOptions.defaultOptions();
    AsyncCompletionCoordinator coordinator =
        AsyncCompletionCoordinator.create(options, gate, directExecutor, t -> {});
    AtomicInteger tasksExecuted = new AtomicInteger(0);
    AtomicBoolean observerStartedCalled = new AtomicBoolean(false);
    CelAsyncObserver observer =
        new CelAsyncObserver() {
          @Override
          public void onCallStarted(CelAsyncCall call, ImmutableList<Object> args) {
            observerStartedCalled.set(true);
          }

          @Override
          public void onCallFinished(
              CelAsyncCall call, @Nullable Object result, @Nullable Throwable error) {}
        };
    AsyncCallRecord record =
        AsyncCallRecord.create(
            1L,
            10L,
            "func",
            "func_ov",
            new Object[] {"x"},
            args -> {
              tasksExecuted.incrementAndGet();
              return immediateFuture("res");
            });
    checkState(record.markRunning(), "Failed to mark record running");

    tracker.tryLaunch(record, directExecutor, gate, coordinator, observer);

    assertThat(gate.activeCount()).isEqualTo(0);
    assertThat(tasksExecuted.get()).isEqualTo(0);
    assertThat(observerStartedCalled.get()).isFalse();
  }

  @Test
  @SuppressWarnings("Immutable") // Test only
  public void handleSuccess_whenRecordCancelledPriorToCompletion_doesNotNotifyObserver()
      throws Exception {
    AsyncCallStateTracker tracker = AsyncCallStateTracker.create(runtimeEquality);
    AsyncGate gate = AsyncGate.create(1);
    CelAsyncEvaluationOptions options = CelAsyncEvaluationOptions.defaultOptions();
    AsyncCompletionCoordinator coordinator =
        AsyncCompletionCoordinator.create(options, gate, directExecutor, t -> {});
    SettableFuture<Object> future = SettableFuture.create();
    AtomicBoolean callFinishedCalled = new AtomicBoolean(false);
    CelAsyncObserver observer =
        new CelAsyncObserver() {
          @Override
          public void onCallStarted(CelAsyncCall call, ImmutableList<Object> args) {}

          @Override
          public void onCallFinished(
              CelAsyncCall call, @Nullable Object result, @Nullable Throwable error) {
            callFinishedCalled.set(true);
          }
        };
    AccumulatedUnknowns unknowns =
        (AccumulatedUnknowns)
            tracker.recordOrGet(
                10L,
                "func",
                "func_ov",
                new Object[] {"x"},
                args -> future,
                CelValueConverter.getDefaultInstance());
    tracker.dispatchPendingCalls(unknowns.callIds(), directExecutor, gate, coordinator, observer);
    tracker.cancelInFlight();

    future.set("late_success");

    assertThat(callFinishedCalled.get()).isFalse();
  }

  @Test
  @SuppressWarnings("Immutable") // Test only
  public void handleFailure_whenRecordCancelledPriorToCompletion_doesNotNotifyObserver()
      throws Exception {
    AsyncCallStateTracker tracker = AsyncCallStateTracker.create(runtimeEquality);
    AsyncGate gate = AsyncGate.create(1);
    CelAsyncEvaluationOptions options = CelAsyncEvaluationOptions.defaultOptions();
    AsyncCompletionCoordinator coordinator =
        AsyncCompletionCoordinator.create(options, gate, directExecutor, t -> {});
    SettableFuture<Object> future = SettableFuture.create();
    AtomicBoolean callFinishedCalled = new AtomicBoolean(false);
    CelAsyncObserver observer =
        new CelAsyncObserver() {
          @Override
          public void onCallStarted(CelAsyncCall call, ImmutableList<Object> args) {}

          @Override
          public void onCallFinished(
              CelAsyncCall call, @Nullable Object result, @Nullable Throwable error) {
            callFinishedCalled.set(true);
          }
        };
    AccumulatedUnknowns unknowns =
        (AccumulatedUnknowns)
            tracker.recordOrGet(
                10L,
                "func",
                "func_ov",
                new Object[] {"x"},
                args -> future,
                CelValueConverter.getDefaultInstance());
    tracker.dispatchPendingCalls(unknowns.callIds(), directExecutor, gate, coordinator, observer);
    tracker.cancelInFlight();

    future.setException(new RuntimeException("late_failure"));

    assertThat(callFinishedCalled.get()).isFalse();
  }

  @Test
  @SuppressWarnings("Immutable") // Test only
  public void recordOrGet_whenAsyncFunctionCompletesWithCelEvaluationException_rethrowsSameInstance()
      throws Exception {
    AsyncCallStateTracker tracker = AsyncCallStateTracker.create(runtimeEquality);
    AsyncGate gate = AsyncGate.create(1);
    CelAsyncEvaluationOptions options = CelAsyncEvaluationOptions.defaultOptions();
    AsyncCompletionCoordinator coordinator =
        AsyncCompletionCoordinator.create(options, gate, directExecutor, t -> {});
    CelEvaluationException originalException = new CelEvaluationException("custom eval error");
    SettableFuture<Object> future = SettableFuture.create();
    future.setException(originalException);
    AccumulatedUnknowns unknowns =
        (AccumulatedUnknowns)
            tracker.recordOrGet(
                10L,
                "func",
                "func_ov",
                new Object[] {"x"},
                args -> future,
                CelValueConverter.getDefaultInstance());
    tracker.dispatchPendingCalls(
        unknowns.callIds(), directExecutor, gate, coordinator, /* observer= */ null);

    CelEvaluationException thrown =
        assertThrows(
            CelEvaluationException.class,
            () ->
                tracker.recordOrGet(
                    10L,
                    "func",
                    "func_ov",
                    new Object[] {"x"},
                    args -> future,
                    CelValueConverter.getDefaultInstance()));

    assertThat(thrown).isSameInstanceAs(originalException);
  }

  @Test
  @SuppressWarnings("Immutable") // Test only
  public void recordOrGet_whenAsyncFunctionCompletesWithNull_returnsNullValue() throws Exception {
    AsyncCallStateTracker tracker = AsyncCallStateTracker.create(runtimeEquality);
    AsyncGate gate = AsyncGate.create(1);
    CelAsyncEvaluationOptions options = CelAsyncEvaluationOptions.defaultOptions();
    AsyncCompletionCoordinator coordinator =
        AsyncCompletionCoordinator.create(options, gate, directExecutor, t -> {});
    SettableFuture<Object> future = SettableFuture.create();
    future.set(null);
    AccumulatedUnknowns unknowns =
        (AccumulatedUnknowns)
            tracker.recordOrGet(
                10L,
                "func",
                "func_ov",
                new Object[] {"x"},
                args -> future,
                CelValueConverter.getDefaultInstance());
    tracker.dispatchPendingCalls(
        unknowns.callIds(), directExecutor, gate, coordinator, /* observer= */ null);

    Object result =
        tracker.recordOrGet(
            10L,
            "func",
            "func_ov",
            new Object[] {"x"},
            args -> future,
            CelValueConverter.getDefaultInstance());

    assertThat(result).isEqualTo(NullValue.NULL_VALUE);
  }

  @Test
  @SuppressWarnings("Immutable") // Test only
  public void recordOrGet_whenExceptionHasNullMessage_usesClassSimpleName() throws Exception {
    AsyncCallStateTracker tracker = AsyncCallStateTracker.create(runtimeEquality);
    AsyncGate gate = AsyncGate.create(1);
    CelAsyncEvaluationOptions options = CelAsyncEvaluationOptions.defaultOptions();
    AsyncCompletionCoordinator coordinator =
        AsyncCompletionCoordinator.create(options, gate, directExecutor, t -> {});
    SettableFuture<Object> future = SettableFuture.create();
    future.setException(new IllegalStateException((String) null));
    AccumulatedUnknowns unknowns =
        (AccumulatedUnknowns)
            tracker.recordOrGet(
                10L,
                "func",
                "func_ov",
                new Object[] {"x"},
                args -> future,
                CelValueConverter.getDefaultInstance());
    tracker.dispatchPendingCalls(
        unknowns.callIds(), directExecutor, gate, coordinator, /* observer= */ null);

    CelEvaluationException thrown =
        assertThrows(
            CelEvaluationException.class,
            () ->
                tracker.recordOrGet(
                    10L,
                    "func",
                    "func_ov",
                    new Object[] {"x"},
                    args -> future,
                    CelValueConverter.getDefaultInstance()));

    assertThat(thrown).hasMessageThat().contains("IllegalStateException");
  }

  @Test
  public void dispatchPendingCalls_withUnknownCallId_doesNotThrow() {
    AsyncCallStateTracker tracker = AsyncCallStateTracker.create(runtimeEquality);
    AsyncGate gate = AsyncGate.create(1);
    CelAsyncEvaluationOptions options = CelAsyncEvaluationOptions.defaultOptions();
    AsyncCompletionCoordinator coordinator =
        AsyncCompletionCoordinator.create(options, gate, directExecutor, t -> {});

    tracker.dispatchPendingCalls(
        ImmutableSet.of(9999L), directExecutor, gate, coordinator, /* observer= */ null);

    assertThat(gate.activeCount()).isEqualTo(0);
  }

  @Test
  public void tryLaunch_withNullArgument_passesNullValueToObserver() {
    AsyncCallStateTracker tracker = AsyncCallStateTracker.create(runtimeEquality);
    AsyncGate gate = AsyncGate.create(1);
    CelAsyncEvaluationOptions options = CelAsyncEvaluationOptions.defaultOptions();
    AsyncCompletionCoordinator coordinator =
        AsyncCompletionCoordinator.create(options, gate, directExecutor, t -> {});
    AtomicReference<ImmutableList<Object>> observedArgs = new AtomicReference<>();
    CelAsyncObserver observer =
        new CelAsyncObserver() {
          @Override
          public void onCallStarted(CelAsyncCall call, ImmutableList<Object> args) {
            observedArgs.set(args);
          }

          @Override
          public void onCallFinished(
              CelAsyncCall call, @Nullable Object result, @Nullable Throwable error) {}
        };
    AsyncCallRecord record =
        AsyncCallRecord.create(
            1L, 10L, "func", "func_ov", new Object[] {null}, args -> immediateFuture("ok"));

    tracker.tryLaunch(record, directExecutor, gate, coordinator, observer);

    assertThat(observedArgs.get()).containsExactly(NullValue.NULL_VALUE);
  }
}
