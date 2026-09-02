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
import static com.google.common.util.concurrent.Futures.immediateFuture;
import static com.google.common.util.concurrent.MoreExecutors.newDirectExecutorService;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import dev.cel.common.CelOptions;
import dev.cel.common.values.CelValueConverter;
import dev.cel.runtime.AccumulatedUnknowns;
import dev.cel.runtime.CelAsyncCall;
import dev.cel.runtime.CelAsyncDrainStrategy;
import dev.cel.runtime.CelAsyncEvaluationOptions;
import dev.cel.runtime.CelAsyncObserver;
import dev.cel.runtime.CelEvaluationException;
import dev.cel.runtime.RuntimeEquality;
import dev.cel.runtime.RuntimeHelpers;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(TestParameterInjector.class)
public final class AsyncCallStateTrackerTest {

  private final RuntimeEquality runtimeEquality =
      RuntimeEquality.create(RuntimeHelpers.create(), CelOptions.DEFAULT);
  private final ListeningExecutorService directExecutor = newDirectExecutorService();

  @Test
  @SuppressWarnings("Immutable") // Test only
  public void recordOrGet_cancelledBeforeRun_releasesPermitAndReturns() throws Exception {
    AsyncCallStateTracker tracker = new AsyncCallStateTracker(runtimeEquality);
    AsyncGate gate = new AsyncGate(1);
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
        new AsyncCompletionCoordinator(options, gate, mockExecutor);

    // Acquire the only permit in gate so the next task is queued
    gate.dispatch(mockExecutor, () -> {});
    assertThat(gate.activeCount()).isEqualTo(1);

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
            CelValueConverter.getDefaultInstance(),
            mockExecutor,
            gate,
            coordinator,
            /* observer= */ null);

    assertThat(result).isInstanceOf(AccumulatedUnknowns.class);
    // Permit is still held by first dummy task, new task is in gate's pendingTasks
    assertThat(gate.activeCount()).isEqualTo(1);
    assertThat(queuedTasks).isEmpty();

    // Release permit from the first task; gate drains pending task to mockExecutor
    gate.releasePermit(mockExecutor);
    assertThat(queuedTasks).hasSize(1);
    // Gate has now acquired permit for the queued task
    assertThat(gate.activeCount()).isEqualTo(1);

    // Cancel before task execution runs
    tracker.cancelInFlight();

    // Run the queued task; it should see record is cancelled and release permit
    queuedTasks.get(0).run();

    assertThat(overloadCalled.get()).isFalse();
    assertThat(gate.activeCount()).isEqualTo(0);
  }

  @Test
  @SuppressWarnings("Immutable") // Test only
  public void recordOrGet_deduplicatesCallsWithSameKey() throws Exception {
    AsyncCallStateTracker tracker = new AsyncCallStateTracker(runtimeEquality);
    AsyncGate gate = new AsyncGate(0);
    CelAsyncEvaluationOptions options =
        CelAsyncEvaluationOptions.builder()
            .setDrainStrategy(CelAsyncDrainStrategy.drainReady(Duration.ofMinutes(1)))
            .build();
    AsyncCompletionCoordinator coordinator =
        new AsyncCompletionCoordinator(options, gate, directExecutor);

    SettableFuture<Object> pendingFuture = SettableFuture.create();

    Object first =
        tracker.recordOrGet(
            10L,
            "myFunc",
            "myFunc_overload",
            new Object[] {"x"},
            args -> pendingFuture,
            CelValueConverter.getDefaultInstance(),
            directExecutor,
            gate,
            coordinator,
            /* observer= */ null);

    Object second =
        tracker.recordOrGet(
            10L,
            "myFunc",
            "myFunc_overload",
            new Object[] {"x"},
            args -> pendingFuture,
            CelValueConverter.getDefaultInstance(),
            directExecutor,
            gate,
            coordinator,
            /* observer= */ null);

    assertThat(first).isInstanceOf(AccumulatedUnknowns.class);
    assertThat(second).isInstanceOf(AccumulatedUnknowns.class);
    assertThat(((AccumulatedUnknowns) first).callIds())
        .isEqualTo(((AccumulatedUnknowns) second).callIds());
    assertThat(tracker.hasInFlightCalls()).isTrue();
  }

  @Test
  @SuppressWarnings("Immutable") // Test only
  public void hasInFlightCalls_futureCompletes_returnsFalse() throws Exception {
    AsyncCallStateTracker tracker = new AsyncCallStateTracker(runtimeEquality);
    AsyncGate gate = new AsyncGate(0);
    CelAsyncEvaluationOptions options = CelAsyncEvaluationOptions.defaultOptions();
    AsyncCompletionCoordinator coordinator =
        new AsyncCompletionCoordinator(options, gate, directExecutor);
    SettableFuture<Object> pendingFuture = SettableFuture.create();

    tracker.recordOrGet(
        10L,
        "myFunc",
        "myFunc_overload",
        new Object[] {"x"},
        args -> pendingFuture,
        CelValueConverter.getDefaultInstance(),
        directExecutor,
        gate,
        coordinator,
        /* observer= */ null);
    pendingFuture.set("done");

    assertThat(tracker.hasInFlightCalls()).isFalse();
  }

  @Test
  public void recordOrGet_existingKey_doesNotAllocateNewCallId() throws Exception {
    AsyncCallStateTracker tracker = new AsyncCallStateTracker(runtimeEquality);
    AsyncGate gate = new AsyncGate(0);
    CelAsyncEvaluationOptions options = CelAsyncEvaluationOptions.defaultOptions();
    AsyncCompletionCoordinator coordinator =
        new AsyncCompletionCoordinator(options, gate, directExecutor);

    Object first =
        tracker.recordOrGet(
            10L,
            "fn",
            "fn_overload",
            new Object[] {"x"},
            args -> SettableFuture.create(),
            CelValueConverter.getDefaultInstance(),
            directExecutor,
            gate,
            coordinator,
            /* observer= */ null);

    Object second =
        tracker.recordOrGet(
            10L,
            "fn",
            "fn_overload",
            new Object[] {"x"},
            args -> SettableFuture.create(),
            CelValueConverter.getDefaultInstance(),
            directExecutor,
            gate,
            coordinator,
            /* observer= */ null);

    Object third =
        tracker.recordOrGet(
            20L,
            "fn",
            "fn_overload",
            new Object[] {"y"},
            args -> SettableFuture.create(),
            CelValueConverter.getDefaultInstance(),
            directExecutor,
            gate,
            coordinator,
            /* observer= */ null);

    assertThat(((AccumulatedUnknowns) first).callIds()).containsExactly(1L);
    assertThat(((AccumulatedUnknowns) second).callIds()).containsExactly(1L);
    assertThat(((AccumulatedUnknowns) third).callIds()).containsExactly(2L);
  }

  @Test
  @SuppressWarnings("Immutable") // Test only
  public void recordOrGet_successfulExecution_recordsAccurateElapsedDuration() throws Exception {
    AtomicLong nanoTime = new AtomicLong(100_000L);
    AsyncCallStateTracker tracker =
        new AsyncCallStateTracker(runtimeEquality, () -> nanoTime.getAndAdd(150_000L));
    AsyncGate gate = new AsyncGate(0);
    CelAsyncEvaluationOptions options = CelAsyncEvaluationOptions.defaultOptions();
    AsyncCompletionCoordinator coordinator =
        new AsyncCompletionCoordinator(options, gate, directExecutor);
    AtomicReference<CelAsyncCall> finishedCall = new AtomicReference<>();
    CelAsyncObserver observer =
        new CelAsyncObserver() {
          @Override
          public void onCallStarted(CelAsyncCall call) {}

          @Override
          public void onCallFinished(CelAsyncCall call, Object result, Throwable error) {
            finishedCall.set(call);
          }
        };

    SettableFuture<Object> pendingFuture = SettableFuture.create();
    tracker.recordOrGet(
        10L,
        "fn",
        "fn_overload",
        new Object[] {"x"},
        args -> pendingFuture,
        CelValueConverter.getDefaultInstance(),
        directExecutor,
        gate,
        coordinator,
        observer);

    pendingFuture.set("done");

    assertThat(finishedCall.get()).isNotNull();
    assertThat(finishedCall.get().elapsedDuration()).isEqualTo(Duration.ofNanos(150_000L));
  }

  @Test
  public void recordOrGet_synchronousException_recordsAccurateElapsedDuration() throws Exception {
    AtomicLong nanoTime = new AtomicLong(100_000L);
    AsyncCallStateTracker tracker =
        new AsyncCallStateTracker(runtimeEquality, () -> nanoTime.getAndAdd(150_000L));
    AsyncGate gate = new AsyncGate(0);
    CelAsyncEvaluationOptions options = CelAsyncEvaluationOptions.defaultOptions();
    AsyncCompletionCoordinator coordinator =
        new AsyncCompletionCoordinator(options, gate, directExecutor);
    AtomicReference<CelAsyncCall> finishedCall = new AtomicReference<>();
    CelAsyncObserver observer =
        new CelAsyncObserver() {
          @Override
          public void onCallStarted(CelAsyncCall call) {}

          @Override
          public void onCallFinished(CelAsyncCall call, Object result, Throwable error) {
            finishedCall.set(call);
          }
        };

    assertThrows(
        CelEvaluationException.class,
        () ->
            tracker.recordOrGet(
                10L,
                "fn",
                "fn_overload",
                new Object[] {"x"},
                args -> {
                  throw new RuntimeException("fail");
                },
                CelValueConverter.getDefaultInstance(),
                directExecutor,
                gate,
                coordinator,
                observer));

    assertThat(finishedCall.get()).isNotNull();
    assertThat(finishedCall.get().elapsedDuration()).isEqualTo(Duration.ofNanos(150_000L));
  }

  @Test
  @SuppressWarnings("Immutable") // Test only
  public void recordOrGet_asynchronousException_recordsAccurateElapsedDuration() throws Exception {
    AtomicLong nanoTime = new AtomicLong(100_000L);
    AsyncCallStateTracker tracker =
        new AsyncCallStateTracker(runtimeEquality, () -> nanoTime.getAndAdd(150_000L));
    AsyncGate gate = new AsyncGate(0);
    CelAsyncEvaluationOptions options = CelAsyncEvaluationOptions.defaultOptions();
    AsyncCompletionCoordinator coordinator =
        new AsyncCompletionCoordinator(options, gate, directExecutor);
    AtomicReference<CelAsyncCall> finishedCall = new AtomicReference<>();
    CelAsyncObserver observer =
        new CelAsyncObserver() {
          @Override
          public void onCallStarted(CelAsyncCall call) {}

          @Override
          public void onCallFinished(CelAsyncCall call, Object result, Throwable error) {
            finishedCall.set(call);
          }
        };

    SettableFuture<Object> pendingFuture = SettableFuture.create();
    tracker.recordOrGet(
        10L,
        "fn",
        "fn_overload",
        new Object[] {"x"},
        args -> pendingFuture,
        CelValueConverter.getDefaultInstance(),
        directExecutor,
        gate,
        coordinator,
        observer);

    pendingFuture.setException(new RuntimeException("async fail"));

    assertThat(finishedCall.get()).isNotNull();
    assertThat(finishedCall.get().elapsedDuration()).isEqualTo(Duration.ofNanos(150_000L));
  }

  @Test
  @SuppressWarnings("Immutable") // Test only
  public void recordOrGet_asynchronousException_withoutObserver_doesNotThrow() throws Exception {
    AsyncCallStateTracker tracker = new AsyncCallStateTracker(runtimeEquality);
    AsyncGate gate = new AsyncGate(0);
    CelAsyncEvaluationOptions options = CelAsyncEvaluationOptions.defaultOptions();
    AsyncCompletionCoordinator coordinator =
        new AsyncCompletionCoordinator(options, gate, directExecutor);
    SettableFuture<Object> pendingFuture = SettableFuture.create();

    tracker.recordOrGet(
        10L,
        "fn",
        "fn_overload",
        new Object[] {"x"},
        args -> pendingFuture,
        CelValueConverter.getDefaultInstance(),
        directExecutor,
        gate,
        coordinator,
        /* observer= */ null);

    pendingFuture.setException(new RuntimeException("async fail"));
    assertThat(tracker.hasInFlightCalls()).isFalse();
  }

  @Test
  public void recordOrGet_synchronousException_withoutObserver_throwsCelEvaluationException() {
    AsyncCallStateTracker tracker = new AsyncCallStateTracker(runtimeEquality);
    AsyncGate gate = new AsyncGate(0);
    CelAsyncEvaluationOptions options = CelAsyncEvaluationOptions.defaultOptions();
    AsyncCompletionCoordinator coordinator =
        new AsyncCompletionCoordinator(options, gate, directExecutor);

    assertThrows(
        CelEvaluationException.class,
        () ->
            tracker.recordOrGet(
                10L,
                "fn",
                "fn_overload",
                new Object[] {"x"},
                args -> {
                  throw new RuntimeException("sync fail");
                },
                CelValueConverter.getDefaultInstance(),
                directExecutor,
                gate,
                coordinator,
                /* observer= */ null));
  }
}
