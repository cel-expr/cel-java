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

import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.ListenableFuture;
import dev.cel.runtime.CelAsyncCall;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;

/** Tracks the execution state and result of a single asynchronous function call. */
final class AsyncCallRecord implements CelAsyncCall {

  enum State {
    RUNNING,
    SUCCESS,
    FAILURE
  }

  private final long callId;
  private final long exprId;
  private final String functionName;
  private final String overloadId;
  private final Object[] args;

  private final AtomicReference<State> state = new AtomicReference<>(State.RUNNING);
  private final AtomicBoolean cancelled = new AtomicBoolean(false);
  private volatile @Nullable Object result;
  private volatile @Nullable Throwable error;
  private volatile Duration elapsedDuration = Duration.ZERO;
  private volatile @Nullable ListenableFuture<?> inFlightFuture;

  AsyncCallRecord(long callId, long exprId, String functionName, String overloadId, Object[] args) {
    this.callId = callId;
    this.exprId = exprId;
    this.functionName = functionName;
    this.overloadId = overloadId;
    this.args = args.clone();
  }

  void setInFlightFuture(ListenableFuture<?> future) {
    this.inFlightFuture = future;
    if (cancelled.get() && !future.isDone()) {
      future.cancel(/* mayInterruptIfRunning= */ false);
    }
  }

  void cancelInFlight() {
    this.cancelled.set(true);
    ListenableFuture<?> future = this.inFlightFuture;
    if (future != null && !future.isDone()) {
      future.cancel(/* mayInterruptIfRunning= */ false);
    }
  }

  boolean isCancelled() {
    return cancelled.get();
  }

  void complete(Object result, Duration elapsed) {
    this.result = result;
    this.elapsedDuration = elapsed;
    this.state.set(State.SUCCESS);
  }

  void fail(Throwable error, Duration elapsed) {
    this.error = error;
    this.elapsedDuration = elapsed;
    this.state.set(State.FAILURE);
  }

  State state() {
    return state.get();
  }

  @Nullable Object result() {
    return result;
  }

  @Nullable Throwable error() {
    return error;
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

  @Override
  public ImmutableList<Object> arguments() {
    return ImmutableList.copyOf(args);
  }

  @Override
  public Duration elapsedDuration() {
    return elapsedDuration;
  }
}
