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
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import javax.annotation.concurrent.ThreadSafe;
import dev.cel.common.exceptions.CelRuntimeException;
import dev.cel.common.values.CelValueConverter;
import dev.cel.runtime.AccumulatedUnknowns;
import dev.cel.runtime.CelAsyncFunctionOverload;
import dev.cel.runtime.CelAsyncObserver;
import dev.cel.runtime.CelEvaluationException;
import dev.cel.runtime.InterpreterUtil;
import dev.cel.runtime.RuntimeEquality;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;

/**
 * Tracks the registry and cache of all asynchronous function calls made during an expression
 * evaluation.
 */
@ThreadSafe
// CEL-Internal-4
final class AsyncCallStateTracker {
  private final AtomicLong callIdGenerator = new AtomicLong(1);
  private final ConcurrentMap<Integer, CopyOnWriteArrayList<AsyncCallRecord>> recordsByBucket =
      new ConcurrentHashMap<>();
  private final ConcurrentMap<Long, AsyncCallRecord> recordsById = new ConcurrentHashMap<>();
  private final RuntimeEquality runtimeEquality;

  static AsyncCallStateTracker create(RuntimeEquality runtimeEquality) {
    return new AsyncCallStateTracker(runtimeEquality);
  }

  /**
   * Returns the resolved result for a previously completed call matching {@code (exprId,
   * overloadId, args)}, throws a runtime exception if the call failed or was cancelled, or
   * registers and returns an {@link AccumulatedUnknowns} with the call's tracking ID if pending.
   */
  Object recordOrGet(
      long exprId,
      String functionName,
      String overloadId,
      Object[] args,
      CelAsyncFunctionOverload overload,
      CelValueConverter celValueConverter) {
    checkNotNull(functionName);
    checkNotNull(overloadId);
    checkNotNull(args);
    checkNotNull(overload);
    checkNotNull(celValueConverter);
    int bucketKey = AsyncCallRecord.hashCall(exprId, overloadId, args);
    CopyOnWriteArrayList<AsyncCallRecord> bucket = recordsByBucket.get(bucketKey);
    if (bucket != null) {
      for (int i = 0; i < bucket.size(); i++) {
        AsyncCallRecord existing = bucket.get(i);
        if (existing.matches(exprId, functionName, overloadId, args, runtimeEquality)) {
          return resolveRecord(existing, celValueConverter);
        }
      }
    }

    bucket = recordsByBucket.computeIfAbsent(bucketKey, k -> new CopyOnWriteArrayList<>());
    AsyncCallRecord record = null;
    synchronized (bucket) {
      for (int i = 0; i < bucket.size(); i++) {
        AsyncCallRecord existing = bucket.get(i);
        if (existing.matches(exprId, functionName, overloadId, args, runtimeEquality)) {
          record = existing;
          break;
        }
      }
      if (record == null) {
        long callId = callIdGenerator.getAndIncrement();
        record = AsyncCallRecord.create(callId, exprId, functionName, overloadId, args, overload);
        recordsById.put(callId, record);
        bucket.add(record);
      }
    }

    return resolveRecord(record, celValueConverter);
  }

  /**
   * Launches every not-yet-started call in {@code requiredCallIds}, subject to {@code gate}
   * admission control.
   *
   * <p>{@code executor} must run or reject each task; one that silently discards tasks strands the
   * call's concurrency permit.
   */
  void dispatchPendingCalls(
      Set<Long> requiredCallIds,
      Executor executor,
      AsyncGate gate,
      AsyncCompletionCoordinator coordinator,
      @Nullable CelAsyncObserver observer) {
    checkNotNull(requiredCallIds);
    checkNotNull(executor);
    checkNotNull(gate);
    checkNotNull(coordinator);
    for (Long callId : ImmutableList.sortedCopyOf(requiredCallIds)) {
      AsyncCallRecord record = recordsById.get(callId);
      if (record != null && record.state() == AsyncCallRecord.State.NOT_STARTED) {
        tryLaunch(record, executor, gate, coordinator, observer);
      }
    }
  }

  @VisibleForTesting
  void tryLaunch(
      AsyncCallRecord record,
      Executor executor,
      AsyncGate gate,
      AsyncCompletionCoordinator coordinator,
      @Nullable CelAsyncObserver observer) {
    if (!gate.tryAcquire()) {
      return;
    }
    if (!record.markRunning()) {
      gate.release();
      return;
    }

    try {
      if (observer != null) {
        observer.onCallStarted(record, ImmutableList.copyOf(record.args()));
      }
      executor.execute(() -> executeAsyncCall(record, coordinator, observer));
    } catch (RuntimeException e) {
      handleFailure(record, e, coordinator, observer);
    }
  }

  private static void executeAsyncCall(
      AsyncCallRecord record,
      AsyncCompletionCoordinator coordinator,
      @Nullable CelAsyncObserver observer) {
    ListenableFuture<Object> future;
    try {
      if (record.isCancelled()) {
        throw new CancellationException("Async call was cancelled before dispatch");
      }
      future =
          checkNotNull(
              record.overload().applyAsync(record.args()),
              "Async function '%s' returned a null ListenableFuture",
              record.functionName());
      record.setInFlightFuture(future);
    } catch (CelEvaluationException | RuntimeException e) {
      handleFailure(record, e, coordinator, observer);
      return;
    }

    Futures.addCallback(
        future,
        new FutureCallback<Object>() {
          @Override
          public void onSuccess(Object result) {
            handleSuccess(record, result, coordinator, observer);
          }

          @Override
          public void onFailure(Throwable t) {
            handleFailure(record, t, coordinator, observer);
          }
        },
        directExecutor());
  }

  private Object resolveRecord(AsyncCallRecord record, CelValueConverter celValueConverter) {
    switch (record.state()) {
      case SUCCESS:
        Object rawResult = record.result().orElseThrow(AssertionError::new);
        return InterpreterUtil.maybeAdaptToAccumulatedUnknowns(
            celValueConverter.maybeUnwrap(celValueConverter.toRuntimeValue(rawResult)));
      case FAILURE:
        Throwable error = record.error().orElseThrow(AssertionError::new);
        if (error instanceof CelRuntimeException) {
          throw (CelRuntimeException) error;
        }
        String errorMessage =
            error.getMessage() != null ? error.getMessage() : error.getClass().getSimpleName();
        throw new IllegalArgumentException(
            String.format("Async function '%s' failed: %s", record.functionName(), errorMessage),
            error);
      case RUNNING:
      case NOT_STARTED:
        return AccumulatedUnknowns.createForAsyncCall(record.exprId(), record.callId());
      case CANCELLED:
        throw new CancellationException(
            String.format("Async function '%s' was cancelled", record.functionName()));
    }
    throw new AssertionError("Unexpected record state: " + record.state());
  }

  boolean hasInFlightCalls() {
    for (AsyncCallRecord record : recordsById.values()) {
      if (record.state() == AsyncCallRecord.State.RUNNING) {
        return true;
      }
    }
    return false;
  }

  void cancelInFlight() {
    for (AsyncCallRecord record : recordsById.values()) {
      record.cancelInFlight();
    }
  }

  private static void handleSuccess(
      AsyncCallRecord record,
      @Nullable Object result,
      AsyncCompletionCoordinator coordinator,
      @Nullable CelAsyncObserver observer) {
    if (result == null) {
      handleFailure(
          record,
          new NullPointerException(
              String.format("Async function '%s' returned a null result", record.functionName())),
          coordinator,
          observer);
      return;
    }
    record.complete(result);
    reportCompletion(record, result, /* error= */ null, coordinator, observer);
  }

  private static void handleFailure(
      AsyncCallRecord record,
      Throwable error,
      AsyncCompletionCoordinator coordinator,
      @Nullable CelAsyncObserver observer) {
    record.fail(error);
    reportCompletion(record, /* result= */ null, error, coordinator, observer);
  }

  /**
   * Notifies the observer and completion coordinator of a launched call's terminal outcome at most
   * once.
   */
  private static void reportCompletion(
      AsyncCallRecord record,
      @Nullable Object result,
      @Nullable Throwable error,
      AsyncCompletionCoordinator coordinator,
      @Nullable CelAsyncObserver observer) {
    if (!record.markCompletionReported()) {
      return;
    }
    try {
      if (observer != null) {
        observer.onCallFinished(record, result, error);
      }
    } finally {
      coordinator.callCompleted(record);
    }
  }

  @VisibleForTesting
  ConcurrentMap<Integer, CopyOnWriteArrayList<AsyncCallRecord>> recordsByBucket() {
    return recordsByBucket;
  }

  @VisibleForTesting
  ConcurrentMap<Long, AsyncCallRecord> recordsById() {
    return recordsById;
  }

  private AsyncCallStateTracker(RuntimeEquality runtimeEquality) {
    this.runtimeEquality = checkNotNull(runtimeEquality);
  }
}
