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

import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static java.util.Objects.requireNonNull;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import dev.cel.common.values.CelValueConverter;
import dev.cel.common.values.NullValue;
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
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;

/**
 * Tracks the registry and cache of all asynchronous function calls made during an expression
 * evaluation.
 */
final class AsyncCallStateTracker {
  private final AtomicLong callIdGenerator = new AtomicLong(1);
  private final ConcurrentMap<AsyncCallKey, AsyncCallRecord> recordsByKey =
      new ConcurrentHashMap<>();
  private final ConcurrentMap<Long, AsyncCallRecord> recordsById = new ConcurrentHashMap<>();
  private final RuntimeEquality runtimeEquality;

  static AsyncCallStateTracker create(RuntimeEquality runtimeEquality) {
    return new AsyncCallStateTracker(runtimeEquality);
  }

  Object recordOrGet(
      long exprId,
      String functionName,
      String overloadId,
      Object[] args,
      CelAsyncFunctionOverload overload,
      CelValueConverter celValueConverter)
      throws CelEvaluationException {
    requireNonNull(celValueConverter);
    AsyncCallKey key = AsyncCallKey.create(exprId, functionName, overloadId, args, runtimeEquality);
    AsyncCallRecord record =
        recordsByKey.computeIfAbsent(
            key,
            k -> {
              long callId = callIdGenerator.getAndIncrement();
              AsyncCallRecord newRecord =
                  AsyncCallRecord.create(callId, exprId, functionName, overloadId, args, overload);
              recordsById.put(callId, newRecord);
              return newRecord;
            });

    return resolveRecord(record, celValueConverter);
  }

  void dispatchPendingCalls(
      Set<Long> requiredCallIds,
      ListeningExecutorService executor,
      AsyncGate gate,
      AsyncCompletionCoordinator coordinator,
      @Nullable CelAsyncObserver observer) {
    requireNonNull(requiredCallIds);
    requireNonNull(executor);
    requireNonNull(gate);
    requireNonNull(coordinator);
    for (Long callId : requiredCallIds) {
      AsyncCallRecord record = recordsById.get(callId);
      if (record != null && record.state() == AsyncCallRecord.State.NOT_STARTED) {
        tryLaunch(record, executor, gate, coordinator, observer);
      }
    }
  }

  @VisibleForTesting
  void tryLaunch(
      AsyncCallRecord record,
      ListeningExecutorService executor,
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

    if (observer != null) {
      safeNotifyCallStarted(observer, record, record.args());
    }

    Runnable task =
        () -> {
          if (record.isCancelled()) {
            coordinator.callCompleted(record);
            return;
          }
          ListenableFuture<Object> future;
          try {
            future = record.overload().applyAsync(record.args());
            if (future == null) {
              throw new CelEvaluationException(
                  String.format(
                      "Async function '%s' returned a null ListenableFuture",
                      record.functionName()));
            }
            record.setInFlightFuture(future);
          } catch (Throwable t) {
            handleFailure(record, t, coordinator, observer);
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
        };

    try {
      executor.execute(task);
    } catch (Throwable t) {
      handleFailure(record, t, coordinator, observer);
    }
  }

  private Object resolveRecord(AsyncCallRecord record, CelValueConverter celValueConverter)
      throws CelEvaluationException {
    switch (record.state()) {
      case SUCCESS:
        Object rawResult = record.result().orElse(null);
        Object runtimeResult =
            rawResult == null
                ? NullValue.NULL_VALUE
                : celValueConverter.maybeUnwrap(celValueConverter.toRuntimeValue(rawResult));
        return InterpreterUtil.maybeAdaptToAccumulatedUnknowns(runtimeResult);
      case FAILURE:
        Throwable error = record.error().orElse(null);
        if (error instanceof CelEvaluationException) {
          throw (CelEvaluationException) error;
        }
        String errorMessage =
            error != null && error.getMessage() != null
                ? error.getMessage()
                : (error != null ? error.getClass().getSimpleName() : "unknown error");
        throw new CelEvaluationException(
            String.format("Async function '%s' failed: %s", record.functionName(), errorMessage),
            error);
      case RUNNING:
      case NOT_STARTED:
        return AccumulatedUnknowns.createForAsyncCall(record.callId());
      case CANCELLED:
        throw new CelEvaluationException(
            String.format("Async function '%s' was cancelled", record.functionName()),
            new CancellationException());
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
      Object result,
      AsyncCompletionCoordinator coordinator,
      @Nullable CelAsyncObserver observer) {
    try {
      if (record.complete(result) && observer != null) {
        safeNotifyCallFinished(
            observer, record, result != null ? result : NullValue.NULL_VALUE, null);
      }
    } finally {
      coordinator.callCompleted(record);
    }
  }

  private static void handleFailure(
      AsyncCallRecord record,
      Throwable error,
      AsyncCompletionCoordinator coordinator,
      @Nullable CelAsyncObserver observer) {
    try {
      if (record.fail(error) && observer != null) {
        safeNotifyCallFinished(observer, record, null, error);
      }
    } finally {
      coordinator.callCompleted(record);
    }
  }

  private static void safeNotifyCallStarted(
      CelAsyncObserver observer, AsyncCallRecord record, Object[] args) {
    requireNonNull(observer);
    try {
      ImmutableList.Builder<Object> argsBuilder =
          ImmutableList.builderWithExpectedSize(args.length);
      for (Object arg : args) {
        argsBuilder.add(arg != null ? arg : NullValue.NULL_VALUE);
      }
      observer.onCallStarted(record, argsBuilder.build());
    } catch (Throwable t) {
      // Observers must not disrupt evaluation
    }
  }

  private static void safeNotifyCallFinished(
      CelAsyncObserver observer,
      AsyncCallRecord record,
      @Nullable Object result,
      @Nullable Throwable error) {
    requireNonNull(observer);
    try {
      observer.onCallFinished(record, result, error);
    } catch (Throwable obsEx) {
      // Observers must not disrupt evaluation
    }
  }

  private AsyncCallStateTracker(RuntimeEquality runtimeEquality) {
    this.runtimeEquality = requireNonNull(runtimeEquality);
  }
}
