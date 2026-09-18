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
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.Iterables.getOnlyElement;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.util.concurrent.Futures.immediateFailedFuture;
import static com.google.common.util.concurrent.Futures.immediateFuture;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.primitives.UnsignedLong;
import com.google.common.util.concurrent.ForwardingListenableFuture.SimpleForwardingListenableFuture;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import com.google.errorprone.annotations.Immutable;
import javax.annotation.concurrent.ThreadSafe;
import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import dev.cel.common.CelOptions;
import dev.cel.common.exceptions.CelDivideByZeroException;
import dev.cel.common.exceptions.CelRuntimeException;
import dev.cel.common.values.CelValueConverter;
import dev.cel.common.values.NullValue;
import dev.cel.runtime.AccumulatedUnknowns;
import dev.cel.runtime.CelAsyncCall;
import dev.cel.runtime.CelAsyncEvaluationOptions;
import dev.cel.runtime.CelAsyncFunctionOverload;
import dev.cel.runtime.CelAsyncObserver;
import dev.cel.runtime.RuntimeEquality;
import dev.cel.runtime.RuntimeHelpers;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(TestParameterInjector.class)
@SuppressWarnings("Immutable")
public final class AsyncCallStateTrackerTest {

  private final RuntimeEquality runtimeEquality =
      RuntimeEquality.create(RuntimeHelpers.create(), CelOptions.DEFAULT);
  private final Executor directExecutor = directExecutor();
  private final AsyncCallStateTracker tracker = AsyncCallStateTracker.create(runtimeEquality);
  private final AsyncGate gate = AsyncGate.create(1);
  private final AsyncCompletionCoordinator coordinator = newCoordinator(gate, directExecutor);
  private final RecordingObserver observer = new RecordingObserver();

  @Test
  public void dispatchPendingCalls_onlyLaunchesRequiredCallIds() throws Exception {
    AtomicBoolean call1Executed = new AtomicBoolean(false);
    AtomicBoolean call2Executed = new AtomicBoolean(false);
    AccumulatedUnknowns unk1 =
        recordCall(
            1L,
            "func1",
            "a",
            args -> {
              call1Executed.set(true);
              return immediateFuture("res1");
            });
    recordCall(
        2L,
        "func2",
        "b",
        args -> {
          call2Executed.set(true);
          return immediateFuture("res2");
        });

    tracker.dispatchPendingCalls(
        unk1.callIds(), directExecutor, gate, coordinator, /* observer= */ null);

    assertThat(call1Executed.get()).isTrue();
    assertThat(call2Executed.get()).isFalse();
  }

  @Test
  public void dispatchPendingCalls_cancelledWhileQueued_abortsOverloadAndNotifiesObserver(
      @TestParameter boolean withObserver) throws Exception {
    List<Runnable> queuedTasks = new ArrayList<>();
    AtomicBoolean overloadExecuted = new AtomicBoolean(false);
    AccumulatedUnknowns unknowns =
        recordDefaultCall(
            args -> {
              overloadExecuted.set(true);
              return immediateFuture("ok");
            });
    tracker.dispatchPendingCalls(
        unknowns.callIds(), queuedTasks::add, gate, coordinator, withObserver ? observer : null);

    tracker.cancelInFlight();
    queuedTasks.get(0).run();

    assertThat(overloadExecuted.get()).isFalse();
    assertThat(gate.activeCount()).isEqualTo(0);
    assertThat(tracker.hasInFlightCalls()).isFalse();
    if (withObserver) {
      assertThat(observer.startedCalls()).hasSize(1);
      assertThat(getOnlyElement(observer.finishedCalls()).error)
          .isInstanceOf(CancellationException.class);
    }
  }

  @Test
  public void dispatchPendingCalls_whenGateFull_defersUntilPermitReleased(
      @TestParameter boolean releaseAndRetry) throws Exception {
    checkState(gate.tryAcquire(), "Failed to acquire permit");
    List<Runnable> queuedTasks = new ArrayList<>();
    AtomicBoolean overloadCalled = new AtomicBoolean(false);
    AccumulatedUnknowns unknowns =
        recordDefaultCall(
            args -> {
              overloadCalled.set(true);
              return immediateFuture("ok");
            });

    tracker.dispatchPendingCalls(
        unknowns.callIds(), queuedTasks::add, gate, coordinator, /* observer= */ null);
    if (releaseAndRetry) {
      gate.release();
      tracker.dispatchPendingCalls(
          unknowns.callIds(), queuedTasks::add, gate, coordinator, /* observer= */ null);
      queuedTasks.get(0).run();
    }

    assertThat(overloadCalled.get()).isEqualTo(releaseAndRetry);
    assertThat(gate.activeCount()).isEqualTo(releaseAndRetry ? 0 : 1);
    assertThat(tracker.hasInFlightCalls()).isFalse();
  }

  @Test
  public void recordOrGet_existingKey_reusesCallIdAndAllocatesNewIdForDistinctKey()
      throws Exception {
    AccumulatedUnknowns first = recordCall(10L, "fn", "x", args -> SettableFuture.create());
    AccumulatedUnknowns second = recordCall(10L, "fn", "x", args -> SettableFuture.create());
    AccumulatedUnknowns third = recordCall(20L, "fn", "y", args -> SettableFuture.create());

    assertThat(first.callIds()).containsExactly(1L);
    assertThat(second.callIds()).containsExactly(1L);
    assertThat(third.callIds()).containsExactly(2L);
  }

  @Test
  public void recordOrGet_bucketHashCollision_disambiguatesViaMatches() throws Exception {
    // Both arguments are complex types (lists) so hashArg yields COMPLEX_HASH_MARKER for both,
    // causing a bucket collision under the same (exprId, overloadId).
    AccumulatedUnknowns first =
        recordCall(10L, "fn", ImmutableList.of("a"), args -> SettableFuture.create());
    AccumulatedUnknowns second =
        recordCall(10L, "fn", ImmutableList.of("b"), args -> SettableFuture.create());
    AccumulatedUnknowns firstAgain =
        recordCall(10L, "fn", ImmutableList.of("a"), args -> SettableFuture.create());
    AccumulatedUnknowns secondAgain =
        recordCall(10L, "fn", ImmutableList.of("b"), args -> SettableFuture.create());

    assertThat(first.callIds()).containsExactly(1L);
    assertThat(second.callIds()).containsExactly(2L);
    assertThat(firstAgain.callIds()).containsExactly(1L);
    assertThat(secondAgain.callIds()).containsExactly(2L);
  }

  @Test
  public void recordOrGet_celEqualArguments_reusesCallId() throws Exception {
    AccumulatedUnknowns first = recordCall(10L, "fn", 1L, args -> SettableFuture.create());
    AccumulatedUnknowns second = recordCall(10L, "fn", 1.0d, args -> SettableFuture.create());

    assertThat(first.callIds()).containsExactly(1L);
    assertThat(second.callIds()).containsExactly(1L);
  }

  @Test
  public void recordOrGet_nanArguments_reusesCallId() throws Exception {
    AccumulatedUnknowns first = recordCall(10L, "fn", Double.NaN, args -> SettableFuture.create());
    AccumulatedUnknowns second = recordCall(10L, "fn", Float.NaN, args -> SettableFuture.create());

    assertThat(first.callIds()).containsExactly(1L);
    assertThat(second.callIds()).containsExactly(1L);
  }

  @Test
  public void recordOrGet_signedZeroArguments_reusesCallId() throws Exception {
    AccumulatedUnknowns first = recordCall(10L, "fn", 0.0d, args -> SettableFuture.create());
    AccumulatedUnknowns second = recordCall(10L, "fn", -0.0d, args -> SettableFuture.create());

    assertThat(first.callIds()).containsExactly(1L);
    assertThat(second.callIds()).containsExactly(1L);
  }

  @Test
  public void recordOrGet_unsignedLongAndLongArguments_reusesCallIdWhenEqual() throws Exception {
    AccumulatedUnknowns first =
        recordCall(10L, "fn", UnsignedLong.valueOf(42L), args -> SettableFuture.create());
    AccumulatedUnknowns second = recordCall(10L, "fn", 42L, args -> SettableFuture.create());

    assertThat(first.callIds()).containsExactly(1L);
    assertThat(second.callIds()).containsExactly(1L);
  }

  @Test
  public void recordOrGet_optimisticRead_doesNotBlockOnBucketLock() throws Exception {
    CelAsyncFunctionOverload overload = args -> SettableFuture.create();
    // Seeds a second entry in the same bucket so the optimistic read iterates past one element.
    recordCall(1L, "fn", ImmutableList.of("a"), overload);
    AccumulatedUnknowns second = recordCall(1L, "fn", ImmutableList.of("b"), overload);
    int bucketKey =
        AsyncCallRecord.hashCall(1L, "fn_overload", new Object[] {ImmutableList.of("b")});
    CopyOnWriteArrayList<AsyncCallRecord> bucket = tracker.recordsByBucket().get(bucketKey);
    checkNotNull(bucket, "Bucket must not be null");
    checkState(bucket.size() >= 2, "Bucket must contain at least two colliding calls");

    CountDownLatch lockAcquired = new CountDownLatch(1);
    CountDownLatch releaseLock = new CountDownLatch(1);
    Thread blockerThread =
        new Thread(
            () -> {
              synchronized (bucket) {
                lockAcquired.countDown();
                try {
                  releaseLock.await(5, SECONDS);
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                }
              }
            });
    SettableFuture<AccumulatedUnknowns> readResult = SettableFuture.create();
    Thread readerThread =
        new Thread(
            () -> {
              try {
                // Read the second colliding record to ensure iteration covers multiple elements
                // lock-free
                readResult.set(recordCall(1L, "fn", ImmutableList.of("b"), overload));
              } catch (Throwable t) {
                readResult.setException(t);
              }
            });
    try {
      blockerThread.start();
      checkState(lockAcquired.await(5, SECONDS), "blockerThread failed to acquire bucket lock");

      readerThread.start();
      AccumulatedUnknowns result = readResult.get(1, SECONDS);

      assertThat(result.callIds()).containsExactlyElementsIn(second.callIds());
    } finally {
      releaseLock.countDown();
      blockerThread.join(5000);
      readerThread.join(5000);
    }
  }

  @Test
  public void recordOrGet_concurrentRegistrationRace_reusesExistingRecordInSlowPath()
      throws Exception {
    CelAsyncFunctionOverload overload = args -> SettableFuture.create();
    Object[] args = new Object[] {1L};
    int bucketKey = AsyncCallRecord.hashCall(1L, "fn_overload", args);
    CopyOnWriteArrayList<AsyncCallRecord> bucket = new CopyOnWriteArrayList<>();
    tracker.recordsByBucket().put(bucketKey, bucket);

    CountDownLatch blockerLocked = new CountDownLatch(1);
    CountDownLatch populateAndRelease = new CountDownLatch(1);
    Thread blockerThread =
        new Thread(
            () -> {
              synchronized (bucket) {
                blockerLocked.countDown();
                try {
                  populateAndRelease.await(5, SECONDS);
                  AsyncCallRecord preExisting =
                      AsyncCallRecord.create(99L, 1L, "fn", "fn_overload", args, overload);
                  tracker.recordsById().put(99L, preExisting);
                  bucket.add(preExisting);
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                }
              }
            });
    SettableFuture<AccumulatedUnknowns> callerResult = SettableFuture.create();
    Thread callerThread =
        new Thread(
            () -> {
              try {
                callerResult.set(
                    (AccumulatedUnknowns)
                        tracker.recordOrGet(
                            1L,
                            "fn",
                            "fn_overload",
                            args,
                            overload,
                            CelValueConverter.getDefaultInstance()));
              } catch (Throwable t) {
                callerResult.setException(t);
              }
            });
    try {
      blockerThread.start();
      checkState(blockerLocked.await(5, SECONDS), "blockerThread failed to acquire lock");

      callerThread.start();
      long deadline = System.currentTimeMillis() + 5000;
      while (callerThread.getState() != Thread.State.BLOCKED) {
        if (callerThread.getState() == Thread.State.TERMINATED) {
          callerResult.get();
          throw new AssertionError("callerThread terminated unexpectedly without blocking");
        }
        if (System.currentTimeMillis() > deadline) {
          throw new AssertionError(
              "callerThread never entered BLOCKED state; state is " + callerThread.getState());
        }
        Thread.sleep(10);
      }
      populateAndRelease.countDown();
      AccumulatedUnknowns unknowns = callerResult.get(5, SECONDS);

      assertThat(unknowns.callIds()).containsExactly(99L);
    } finally {
      populateAndRelease.countDown();
      blockerThread.join(5000);
      callerThread.join(5000);
    }
  }

  @Test
  public void recordOrGet_concurrentBucketHashCollision_registersAllCallsSafely() throws Exception {
    List<AccumulatedUnknowns> results = new CopyOnWriteArrayList<>();

    runConcurrently(
        16,
        () -> {
          int id = results.size();
          results.add(
              recordCall(
                  10L, "fn", ImmutableList.of("arg_" + id), args -> SettableFuture.create()));
        });

    assertThat(results).hasSize(16);
    assertThat(tracker.hasInFlightCalls()).isFalse();
  }

  private enum OverloadFailureMode {
    THROWS_SYNCHRONOUSLY,
    FAILED_FUTURE,
    RETURNS_NULL_FUTURE,
    FUTURE_COMPLETES_WITH_NULL
  }

  @Test
  public void dispatchPendingCalls_overloadFails_notifiesObserverAndReleasesPermit(
      @TestParameter OverloadFailureMode failureMode, @TestParameter boolean withObserver)
      throws Exception {
    RuntimeException expectedError = new RuntimeException("fail");
    AccumulatedUnknowns unknowns =
        recordDefaultCall(
            args -> {
              switch (failureMode) {
                case THROWS_SYNCHRONOUSLY:
                  throw expectedError;
                case FAILED_FUTURE:
                  return immediateFailedFuture(expectedError);
                case RETURNS_NULL_FUTURE:
                  return null;
                case FUTURE_COMPLETES_WITH_NULL:
                  return immediateFuture(null);
              }
              throw new AssertionError();
            });

    tracker.dispatchPendingCalls(
        unknowns.callIds(), directExecutor, gate, coordinator, withObserver ? observer : null);

    boolean isNullFailure =
        failureMode == OverloadFailureMode.RETURNS_NULL_FUTURE
            || failureMode == OverloadFailureMode.FUTURE_COMPLETES_WITH_NULL;
    assertThat(gate.activeCount()).isEqualTo(0);
    assertThat(tracker.hasInFlightCalls()).isFalse();
    if (withObserver) {
      assertThat(getOnlyElement(observer.startedArgs())).containsExactly("x");
      Throwable recordedError = getOnlyElement(observer.finishedCalls()).error;
      if (isNullFailure) {
        assertThat(recordedError).isInstanceOf(NullPointerException.class);
      } else {
        assertThat(recordedError).isSameInstanceAs(expectedError);
      }
    }
    IllegalArgumentException evalException =
        assertThrows(
            IllegalArgumentException.class,
            () -> getDefaultCall(args -> immediateFuture("unused")));
    if (isNullFailure) {
      assertThat(evalException).hasCauseThat().isInstanceOf(NullPointerException.class);
    } else {
      assertThat(evalException).hasCauseThat().isSameInstanceAs(expectedError);
    }
  }

  @Test
  public void dispatchPendingCalls_executorRejection_releasesPermitAndFailsRecord()
      throws Exception {
    AccumulatedUnknowns unknowns = recordDefaultCall(args -> immediateFuture("done"));
    Executor rejectingExecutor =
        cmd -> {
          throw new RejectedExecutionException("pool full");
        };

    tracker.dispatchPendingCalls(
        unknowns.callIds(), rejectingExecutor, gate, coordinator, /* observer= */ null);

    assertThat(gate.activeCount()).isEqualTo(0);
    assertThat(tracker.hasInFlightCalls()).isFalse();
  }

  @Test
  public void recordOrGet_afterSuccess_returnsResolvedValueAndNotifiesObserver(
      @TestParameter boolean nullSentinelValues) throws Exception {
    Object arg = nullSentinelValues ? NullValue.NULL_VALUE : "x";
    Object expectedResult = nullSentinelValues ? NullValue.NULL_VALUE : "syncSuccess";
    SettableFuture<Object> future = SettableFuture.create();
    AccumulatedUnknowns unknowns = recordCall(10L, "fn", arg, args -> future);
    tracker.dispatchPendingCalls(unknowns.callIds(), directExecutor, gate, coordinator, observer);
    future.set(expectedResult);

    Object result = recordOrGetCall(10L, "fn", arg, args -> future);

    FinishedCall finished = getOnlyElement(observer.finishedCalls());
    assertThat(result).isEqualTo(expectedResult);
    assertThat(getOnlyElement(observer.startedCalls()).call.functionName()).isEqualTo("fn");
    assertThat(getOnlyElement(observer.startedArgs())).containsExactly(arg);
    assertThat(finished.result).isEqualTo(expectedResult);
    assertThat(finished.call.functionName()).isEqualTo("fn");
    assertThat(gate.activeCount()).isEqualTo(0);
    assertThat(tracker.hasInFlightCalls()).isFalse();
  }

  @Test
  public void dispatchPendingCalls_concurrentRace_threadContentionHandledSafely() throws Exception {
    AtomicInteger callsDispatched = new AtomicInteger(0);
    AccumulatedUnknowns unknowns =
        recordDefaultCall(
            args -> {
              callsDispatched.incrementAndGet();
              return immediateFuture("result");
            });

    runConcurrently(8, () -> dispatchDefaultCalls(unknowns));

    assertThat(callsDispatched.get()).isEqualTo(1);
    assertThat(gate.activeCount()).isEqualTo(0);
    assertThat(tracker.hasInFlightCalls()).isFalse();
  }

  @Test
  public void cancelInFlight_beforeFutureCompletes_releasesPermitAndNotifiesObserver(
      @TestParameter boolean succeedsAfterCancel) throws Exception {
    SettableFuture<Object> underlyingFuture = SettableFuture.create();
    dispatchCallWithObserver(args -> nonCancellableFuture(underlyingFuture));
    RuntimeException lateError = new RuntimeException("late_failure");

    tracker.cancelInFlight();
    if (succeedsAfterCancel) {
      underlyingFuture.set("late_success");
    } else {
      underlyingFuture.setException(lateError);
    }

    assertThat(observer.startedCalls()).hasSize(1);
    FinishedCall finished = getOnlyElement(observer.finishedCalls());
    assertThat(finished.result).isEqualTo(succeedsAfterCancel ? "late_success" : null);
    assertThat(finished.error).isEqualTo(succeedsAfterCancel ? null : lateError);
    assertThat(finished.call.functionName()).isEqualTo("fn");
    assertThat(gate.activeCount()).isEqualTo(0);
    assertThat(tracker.hasInFlightCalls()).isFalse();
  }

  @Test
  public void recordOrGet_whenInFlight_returnsAccumulatedUnknownsWithSameCallId() throws Exception {
    SettableFuture<Object> pendingFuture = SettableFuture.create();
    AccumulatedUnknowns initial = recordDefaultCall(args -> pendingFuture);
    dispatchDefaultCalls(initial);

    AccumulatedUnknowns whileRunning = recordDefaultCall(args -> pendingFuture);

    assertThat(whileRunning.callIds()).containsExactlyElementsIn(initial.callIds());
    assertThat(tracker.hasInFlightCalls()).isTrue();
  }

  @Test
  public void recordOrGet_concurrentRegistrationSameKey_deduplicatesToSingleCallId()
      throws Exception {
    List<AccumulatedUnknowns> results = new CopyOnWriteArrayList<>();

    runConcurrently(16, () -> results.add(recordDefaultCall(args -> immediateFuture("done"))));

    assertThat(results).hasSize(16);
    long canonicalCallId = getOnlyElement(results.get(0).callIds());
    for (AccumulatedUnknowns result : results) {
      assertThat(result.callIds()).containsExactly(canonicalCallId);
    }
  }

  @Test
  public void tryLaunch_whenRecordCannotTransitionToRunning_releasesPermitWithoutDispatch(
      @TestParameter boolean alreadyRunning) {
    AtomicInteger tasksExecuted = new AtomicInteger(0);
    AsyncCallRecord record =
        defaultRecord(
            args -> {
              tasksExecuted.incrementAndGet();
              return immediateFuture("done");
            });
    if (alreadyRunning) {
      checkState(record.markRunning());
    } else {
      record.cancelInFlight();
    }

    tracker.tryLaunch(record, directExecutor, gate, coordinator, observer);

    assertThat(gate.activeCount()).isEqualTo(0);
    assertThat(tasksExecuted.get()).isEqualTo(0);
    assertThat(observer.startedCalls()).isEmpty();
  }

  @Test
  public void tryLaunch_whenFutureNotifiesListenersTwice_releasesGatePermitOnce() {
    AsyncGate twoPermitGate = AsyncGate.create(2);
    checkState(twoPermitGate.tryAcquire());
    AsyncCallRecord record = defaultRecord(args -> doubleNotifyingFuture());

    tracker.tryLaunch(
        record,
        directExecutor,
        twoPermitGate,
        newCoordinator(twoPermitGate, directExecutor),
        observer);

    assertThat(observer.finishedCalls()).hasSize(1);
    assertThat(twoPermitGate.activeCount()).isEqualTo(1);
  }

  @Test
  public void tryLaunch_observerThrowsOnStart_failsRecordAndReleasesPermit() {
    RuntimeException expected = new RuntimeException("observer start failure");
    AtomicReference<Throwable> reportedError = new AtomicReference<>();
    CelAsyncObserver throwingObserver =
        new CelAsyncObserver() {
          @Override
          public void onCallStarted(CelAsyncCall call, ImmutableList<Object> args) {
            throw expected;
          }

          @Override
          public void onCallFinished(
              CelAsyncCall call, @Nullable Object result, @Nullable Throwable error) {
            reportedError.set(error);
          }
        };
    AsyncCallRecord record = defaultRecord(args -> immediateFuture("done"));

    tracker.tryLaunch(record, directExecutor, gate, coordinator, throwingObserver);

    assertThat(record.state()).isEqualTo(AsyncCallRecord.State.FAILURE);
    assertThat(record.error()).hasValue(expected);
    assertThat(reportedError.get()).isSameInstanceAs(expected);
    assertThat(gate.activeCount()).isEqualTo(0);
  }

  @Test
  public void tryLaunch_observerThrowsOnFinish_preservesResultAndReleasesPermit() {
    CelAsyncObserver throwingObserver =
        new CelAsyncObserver() {
          @Override
          public void onCallStarted(CelAsyncCall call, ImmutableList<Object> args) {}

          @Override
          public void onCallFinished(
              CelAsyncCall call, @Nullable Object result, @Nullable Throwable error) {
            throw new RuntimeException("observer finish failure");
          }
        };
    AsyncCallRecord record = defaultRecord(args -> immediateFuture("done"));

    tracker.tryLaunch(record, directExecutor, gate, coordinator, throwingObserver);

    assertThat(record.state()).isEqualTo(AsyncCallRecord.State.SUCCESS);
    assertThat(record.result()).hasValue("done");
    assertThat(gate.activeCount()).isEqualTo(0);
  }

  private enum FailureCase {
    CHECKED_OR_RUNTIME(new IllegalArgumentException("computation failed"), "computation failed"),
    NULL_MESSAGE(new IllegalStateException((String) null), "IllegalStateException"),
    CEL_RUNTIME_EXCEPTION(new CelDivideByZeroException(), "/ by zero"),
    CANCELLED(new CancellationException(), "was cancelled");

    private final Throwable cause;
    private final String expectedMessage;

    FailureCase(Throwable cause, String expectedMessage) {
      this.cause = cause;
      this.expectedMessage = expectedMessage;
    }
  }

  @Test
  public void recordOrGet_whenRecordFailed_throwsExpectedException(
      @TestParameter FailureCase failureCase) throws Exception {
    ListenableFuture<Object> future = immediateFailedFuture(failureCase.cause);
    if (failureCase == FailureCase.CANCELLED) {
      recordDefaultCall(args -> future);
      tracker.cancelInFlight();
      CancellationException e =
          assertThrows(CancellationException.class, () -> recordDefaultCall(args -> future));
      assertThat(e).hasMessageThat().contains(failureCase.expectedMessage);
    } else if (failureCase == FailureCase.CEL_RUNTIME_EXCEPTION) {
      dispatchDefaultCalls(recordDefaultCall(args -> future));
      CelRuntimeException e =
          assertThrows(CelRuntimeException.class, () -> recordDefaultCall(args -> future));
      assertThat(e).isSameInstanceAs(failureCase.cause);
    } else {
      dispatchDefaultCalls(recordDefaultCall(args -> future));
      IllegalArgumentException e =
          assertThrows(IllegalArgumentException.class, () -> recordDefaultCall(args -> future));
      assertThat(e).hasMessageThat().contains(failureCase.expectedMessage);
      assertThat(e).hasCauseThat().isInstanceOf(failureCase.cause.getClass());
    }
  }

  @Test
  public void recordOrGet_nullCelValueConverter_throwsNullPointerException() {
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
  public void dispatchPendingCalls_withUnknownCallId_doesNotThrow() {
    tracker.dispatchPendingCalls(
        ImmutableSet.of(9999L), directExecutor, gate, coordinator, /* observer= */ null);

    assertThat(gate.activeCount()).isEqualTo(0);
  }

  private void dispatchCallWithObserver(CelAsyncFunctionOverload overload) {
    tracker.dispatchPendingCalls(
        recordDefaultCall(overload).callIds(), directExecutor, gate, coordinator, observer);
  }

  private void dispatchDefaultCalls(AccumulatedUnknowns unknowns) {
    tracker.dispatchPendingCalls(
        unknowns.callIds(), directExecutor, gate, coordinator, /* observer= */ null);
  }

  private AccumulatedUnknowns recordDefaultCall(CelAsyncFunctionOverload overload) {
    return (AccumulatedUnknowns) getDefaultCall(overload);
  }

  private Object getDefaultCall(CelAsyncFunctionOverload overload) {
    return recordOrGetCall(10L, "fn", "x", overload);
  }

  private AccumulatedUnknowns recordCall(
      long exprId, String functionName, Object arg, CelAsyncFunctionOverload overload) {
    return (AccumulatedUnknowns) recordOrGetCall(exprId, functionName, arg, overload);
  }

  private Object recordOrGetCall(
      long exprId, String functionName, Object arg, CelAsyncFunctionOverload overload) {
    return tracker.recordOrGet(
        exprId,
        functionName,
        functionName + "_overload",
        new Object[] {arg},
        overload,
        CelValueConverter.getDefaultInstance());
  }

  private static AsyncCallRecord defaultRecord(CelAsyncFunctionOverload overload) {
    return AsyncCallRecord.create(100L, 10L, "fn", "fn_overload", new Object[] {"x"}, overload);
  }

  private static AsyncCompletionCoordinator newCoordinator(AsyncGate gate, Executor executor) {
    return AsyncCompletionCoordinator.create(
        CelAsyncEvaluationOptions.defaultOptions(), gate, executor, t -> {});
  }

  private interface ThrowingRunnable {
    void run() throws Exception;
  }

  private static void runConcurrently(int threads, ThrowingRunnable action)
      throws InterruptedException {
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    CountDownLatch start = new CountDownLatch(1);
    CountDownLatch done = new CountDownLatch(threads);
    for (int i = 0; i < threads; i++) {
      pool.execute(
          () -> {
            try {
              start.await();
              action.run();
            } catch (Exception e) {
              if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
              }
              throw new AssertionError(e);
            } finally {
              done.countDown();
            }
          });
    }
    start.countDown();
    assertThat(done.await(5, SECONDS)).isTrue();
    pool.shutdown();
  }

  private static ListenableFuture<Object> nonCancellableFuture(ListenableFuture<Object> delegate) {
    return new SimpleForwardingListenableFuture<Object>(delegate) {
      @Override
      public boolean cancel(boolean mayInterruptIfRunning) {
        return false;
      }
    };
  }

  private static ListenableFuture<Object> doubleNotifyingFuture() {
    return new SimpleForwardingListenableFuture<Object>(immediateFuture("done")) {
      @Override
      public void addListener(Runnable listener, Executor executor) {
        super.addListener(listener, executor);
        super.addListener(listener, executor);
      }
    };
  }

  @ThreadSafe
  private static final class RecordingObserver implements CelAsyncObserver {
    private final CopyOnWriteArrayList<StartedCall> startedCalls = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<FinishedCall> finishedCalls = new CopyOnWriteArrayList<>();

    @Override
    public void onCallStarted(CelAsyncCall call, ImmutableList<Object> args) {
      startedCalls.add(new StartedCall(call, args));
    }

    @Override
    public void onCallFinished(
        CelAsyncCall call, @Nullable Object result, @Nullable Throwable error) {
      finishedCalls.add(new FinishedCall(call, result, error));
    }

    ImmutableList<StartedCall> startedCalls() {
      return ImmutableList.copyOf(startedCalls);
    }

    ImmutableList<ImmutableList<Object>> startedArgs() {
      return startedCalls.stream().map(s -> s.args).collect(toImmutableList());
    }

    ImmutableList<FinishedCall> finishedCalls() {
      return ImmutableList.copyOf(finishedCalls);
    }
  }

  @Immutable
  @SuppressWarnings("Immutable")
  private static final class StartedCall {
    private final CelAsyncCall call;
    private final ImmutableList<Object> args;

    private StartedCall(CelAsyncCall call, ImmutableList<Object> args) {
      this.call = call;
      this.args = args;
    }
  }

  @Immutable
  @SuppressWarnings("Immutable")
  private static final class FinishedCall {
    private final CelAsyncCall call;
    private final @Nullable Object result;
    private final @Nullable Throwable error;

    private FinishedCall(CelAsyncCall call, @Nullable Object result, @Nullable Throwable error) {
      this.call = call;
      this.result = result;
      this.error = error;
    }
  }
}
