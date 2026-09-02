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

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import dev.cel.common.values.CelValueConverter;
import dev.cel.runtime.AccumulatedUnknowns;
import dev.cel.runtime.CelAsyncFunctionOverload;
import dev.cel.runtime.CelAsyncObserver;
import dev.cel.runtime.CelEvaluationException;
import dev.cel.runtime.InterpreterUtil;
import dev.cel.runtime.RuntimeEquality;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
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
  private final LongSupplier nanoTimeSupplier;

  AsyncCallStateTracker(RuntimeEquality runtimeEquality) {
    this(runtimeEquality, System::nanoTime);
  }

  AsyncCallStateTracker(RuntimeEquality runtimeEquality, LongSupplier nanoTimeSupplier) {
    this.runtimeEquality = requireNonNull(runtimeEquality);
    this.nanoTimeSupplier = requireNonNull(nanoTimeSupplier);
  }

  Object recordOrGet(
      long exprId,
      String functionName,
      String overloadId,
      Object[] args,
      CelAsyncFunctionOverload overload,
      CelValueConverter celValueConverter,
      ListeningExecutorService executor,
      AsyncGate gate,
      AsyncCompletionCoordinator coordinator,
      @Nullable CelAsyncObserver observer)
      throws CelEvaluationException {
    AsyncCallKey key = AsyncCallKey.create(exprId, args, runtimeEquality);

    AsyncCallRecord existing = recordsByKey.get(key);
    if (existing != null) {
      return resolveRecord(existing, celValueConverter);
    }

    long callId = callIdGenerator.getAndIncrement();
    AsyncCallRecord newRecord = new AsyncCallRecord(callId, exprId, functionName, overloadId, args);
    AsyncCallRecord raceWinner = recordsByKey.putIfAbsent(key, newRecord);
    if (raceWinner != null) {
      return resolveRecord(raceWinner, celValueConverter);
    }

    recordsById.put(callId, newRecord);

    if (observer != null) {
      try {
        observer.onCallStarted(newRecord);
      } catch (Throwable t) {
        // Observers must not disrupt evaluation
      }
    }

    Runnable task =
        () -> {
          if (newRecord.isCancelled()) {
            gate.releasePermit(executor);
            return;
          }
          long startTimeNanos = nanoTimeSupplier.getAsLong();
          try {
            ListenableFuture<Object> future = overload.applyAsync(args);
            if (future == null) {
              throw new CelEvaluationException(
                  String.format(
                      "Async function '%s' returned a null ListenableFuture", functionName));
            }
            newRecord.setInFlightFuture(future);
            Futures.addCallback(
                future,
                new FutureCallback<Object>() {
                  @Override
                  public void onSuccess(Object result) {
                    Duration elapsed =
                        Duration.ofNanos(nanoTimeSupplier.getAsLong() - startTimeNanos);
                    newRecord.complete(result, elapsed);
                    try {
                      if (observer != null) {
                        safeNotifyCallFinished(observer, newRecord, result, null);
                      }
                    } finally {
                      gate.releasePermit(executor);
                      coordinator.notifyCallCompleted(newRecord);
                    }
                  }

                  @Override
                  public void onFailure(Throwable t) {
                    Duration elapsed =
                        Duration.ofNanos(nanoTimeSupplier.getAsLong() - startTimeNanos);
                    newRecord.fail(t, elapsed);
                    try {
                      if (observer != null) {
                        safeNotifyCallFinished(observer, newRecord, null, t);
                      }
                    } finally {
                      gate.releasePermit(executor);
                      coordinator.notifyCallCompleted(newRecord);
                    }
                  }
                },
                directExecutor());
          } catch (Throwable t) {
            Duration elapsed = Duration.ofNanos(nanoTimeSupplier.getAsLong() - startTimeNanos);
            newRecord.fail(t, elapsed);
            try {
              if (observer != null) {
                safeNotifyCallFinished(observer, newRecord, null, t);
              }
            } finally {
              gate.releasePermit(executor);
              coordinator.notifyCallCompleted(newRecord);
            }
          }
        };

    gate.dispatch(executor, task);

    if (newRecord.state() != AsyncCallRecord.State.RUNNING) {
      return resolveRecord(newRecord, celValueConverter);
    }

    return AccumulatedUnknowns.createForAsyncCall(callId);
  }

  private Object resolveRecord(AsyncCallRecord record, CelValueConverter celValueConverter)
      throws CelEvaluationException {
    switch (record.state()) {
      case SUCCESS:
        return InterpreterUtil.maybeAdaptToAccumulatedUnknowns(
            celValueConverter.maybeUnwrap(celValueConverter.toRuntimeValue(record.result())));
      case FAILURE:
        Throwable error = record.error();
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
        return AccumulatedUnknowns.createForAsyncCall(record.callId());
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
      if (record.state() == AsyncCallRecord.State.RUNNING) {
        record.cancelInFlight();
      }
    }
  }

  private static void safeNotifyCallFinished(
      CelAsyncObserver observer,
      AsyncCallRecord record,
      @Nullable Object result,
      @Nullable Throwable error) {
    if (observer == null) {
      throw new AssertionError("observer must not be null when notifying call finished");
    }
    try {
      observer.onCallFinished(record, result, error);
    } catch (Throwable obsEx) {
      // Ignore observer errors
    }
  }
}
