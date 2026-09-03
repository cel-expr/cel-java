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

import static java.util.Objects.requireNonNull;

import com.google.common.util.concurrent.ListenableFuture;
import javax.annotation.concurrent.ThreadSafe;
import dev.cel.runtime.CelAsyncCall;
import dev.cel.runtime.CelAsyncFunctionOverload;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;

/** Tracks the execution state and result of a single asynchronous function call. */
@ThreadSafe
// CEL-Internal-4
final class AsyncCallRecord implements CelAsyncCall {

  enum State {
    NOT_STARTED,
    RUNNING,
    SUCCESS,
    FAILURE,
    CANCELLED
  }

  private final long callId;
  private final long exprId;
  private final String functionName;
  private final String overloadId;

  @SuppressWarnings("Immutable") // Array not mutated after construction
  private final Object[] args;

  private final CelAsyncFunctionOverload overload;

  private final AtomicReference<State> state = new AtomicReference<>(State.NOT_STARTED);
  private volatile @Nullable Object result;
  private volatile @Nullable Throwable error;
  private volatile @Nullable ListenableFuture<?> inFlightFuture;

  static AsyncCallRecord create(
      long callId,
      long exprId,
      String functionName,
      String overloadId,
      Object[] args,
      CelAsyncFunctionOverload overload) {
    return new AsyncCallRecord(callId, exprId, functionName, overloadId, args, overload);
  }

  boolean markRunning() {
    synchronized (state) {
      if (state.get() == State.NOT_STARTED) {
        state.set(State.RUNNING);
        return true;
      }
      return false;
    }
  }

  void setInFlightFuture(ListenableFuture<?> future) {
    this.inFlightFuture = requireNonNull(future);
    if (state.get() == State.CANCELLED && !future.isDone()) {
      future.cancel(/* mayInterruptIfRunning= */ false);
    }
  }

  boolean cancelInFlight() {
    synchronized (state) {
      if (this.state.get() == State.NOT_STARTED || this.state.get() == State.RUNNING) {
        this.state.set(State.CANCELLED);
        ListenableFuture<?> future = this.inFlightFuture;
        if (future != null && !future.isDone()) {
          future.cancel(/* mayInterruptIfRunning= */ false);
        }
        return true;
      }
      return false;
    }
  }

  boolean cancel() {
    return cancelInFlight();
  }

  boolean isCancelled() {
    return state.get() == State.CANCELLED;
  }

  boolean complete(@Nullable Object result) {
    synchronized (state) {
      if (this.state.get() == State.RUNNING || this.state.get() == State.NOT_STARTED) {
        this.result = result;
        this.state.set(State.SUCCESS);
        return true;
      }
      return false;
    }
  }

  boolean fail(Throwable error) {
    requireNonNull(error);
    synchronized (state) {
      if (this.state.get() == State.RUNNING || this.state.get() == State.NOT_STARTED) {
        this.error = error;
        this.state.set(State.FAILURE);
        return true;
      }
      return false;
    }
  }

  Object[] args() {
    return args.clone();
  }

  CelAsyncFunctionOverload overload() {
    return overload;
  }

  State state() {
    return state.get();
  }

  Optional<Object> result() {
    return Optional.ofNullable(result);
  }

  Optional<Throwable> error() {
    return Optional.ofNullable(error);
  }

  @Override
  public long callId() {
    return callId;
  }

  @Override
  public long exprId() {
    return exprId;
  }

  @Override
  public String functionName() {
    return functionName;
  }

  @Override
  public String overloadId() {
    return overloadId;
  }

  private AsyncCallRecord(
      long callId,
      long exprId,
      String functionName,
      String overloadId,
      Object[] args,
      CelAsyncFunctionOverload overload) {
    this.callId = callId;
    this.exprId = exprId;
    this.functionName = requireNonNull(functionName);
    this.overloadId = requireNonNull(overloadId);
    this.args = args.clone();
    this.overload = requireNonNull(overload);
  }
}
