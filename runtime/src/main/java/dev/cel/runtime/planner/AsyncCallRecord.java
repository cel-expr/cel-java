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

import com.google.common.util.concurrent.ListenableFuture;
import javax.annotation.concurrent.ThreadSafe;
import dev.cel.runtime.CelAsyncCall;
import dev.cel.runtime.CelAsyncFunctionOverload;
import dev.cel.runtime.RuntimeEquality;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
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

  // Type markers keep values of different kinds from colliding in the bucket hash, e.g. the
  // string "NaN" and the double NaN. Collisions remain harmless because matches() disambiguates the
  // bucket.
  private static final int STRING_HASH_MARKER = 's';
  private static final int BOOL_HASH_MARKER = 'b';
  private static final int NUMBER_HASH_MARKER = 'n';
  private static final int COMPLEX_HASH_MARKER = 'x';

  private final long callId;
  private final long exprId;
  private final String functionName;
  private final String overloadId;

  @SuppressWarnings("Immutable") // Array not mutated after construction
  private final Object[] args;

  private final CelAsyncFunctionOverload overload;

  private final Object lock = new Object();
  private final AtomicBoolean completionReported = new AtomicBoolean(false);
  private volatile State state = State.NOT_STARTED;
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

  /**
   * Computes the bucket hash under which a call is tracked.
   *
   * <p>This is a bucketing hint, not an identity: calls that {@link #matches} considers identical
   * hash alike, but distinct calls may share a bucket. Resolve the exact call via {@link #matches}.
   */
  static int hashCall(long exprId, String overloadId, Object[] args) {
    int result = 31 * Long.hashCode(exprId) + overloadId.hashCode();
    for (Object arg : args) {
      result = result * 31 + hashArg(arg);
    }
    return result;
  }

  /**
   * Returns whether this record tracks a call to the same expression node, function, overload, and
   * arguments.
   *
   * <p>Arguments are compared under CEL equality, except that NaN compares equal to itself so that
   * a node re-evaluated with a NaN argument can find its existing record.
   */
  boolean matches(
      long exprId,
      String functionName,
      String overloadId,
      Object[] args,
      RuntimeEquality runtimeEquality) {
    if (this.exprId != exprId
        || !this.functionName.equals(functionName)
        || !this.overloadId.equals(overloadId)
        || this.args.length != args.length) {
      return false;
    }
    for (int i = 0; i < this.args.length; i++) {
      Object arg = this.args[i];
      Object otherArg = args[i];
      if (!runtimeEquality.objectEquals(arg, otherArg) && !(isNan(arg) && isNan(otherArg))) {
        return false;
      }
    }
    return true;
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

  /**
   * Transitions the call state from {@link State#NOT_STARTED} to {@link State#RUNNING}.
   *
   * @return true if the transition succeeded, false if the call was already running, completed, or
   *     cancelled.
   */
  boolean markRunning() {
    synchronized (lock) {
      if (state != State.NOT_STARTED) {
        return false;
      }
      state = State.RUNNING;
      return true;
    }
  }

  void setInFlightFuture(ListenableFuture<?> future) {
    checkNotNull(future);
    boolean shouldCancel;
    synchronized (lock) {
      inFlightFuture = future;
      shouldCancel = (state == State.CANCELLED && !future.isDone());
    }
    if (shouldCancel) {
      future.cancel(/* mayInterruptIfRunning= */ false);
    }
  }

  boolean cancelInFlight() {
    ListenableFuture<?> futureToCancel = null;
    synchronized (lock) {
      if (!isPending()) {
        return false;
      }
      state = State.CANCELLED;
      ListenableFuture<?> future = inFlightFuture;
      if (future != null && !future.isDone()) {
        futureToCancel = future;
      }
    }
    if (futureToCancel != null) {
      futureToCancel.cancel(/* mayInterruptIfRunning= */ false);
    }
    return true;
  }

  /**
   * Claims the right to report this call's completion, returning true for the first caller only.
   *
   * <p>Tracked separately from {@link State} because a call cancelled after dispatch still holds a
   * concurrency permit and must release it exactly once.
   */
  boolean markCompletionReported() {
    return completionReported.compareAndSet(false, true);
  }

  boolean isCancelled() {
    return state == State.CANCELLED;
  }

  boolean complete(@Nullable Object result) {
    synchronized (lock) {
      if (!isPending()) {
        return false;
      }
      this.result = result;
      state = State.SUCCESS;
      return true;
    }
  }

  boolean fail(Throwable error) {
    checkNotNull(error);
    synchronized (lock) {
      if (!isPending()) {
        return false;
      }
      this.error = error;
      state = State.FAILURE;
      return true;
    }
  }

  Object[] args() {
    return args.clone();
  }

  CelAsyncFunctionOverload overload() {
    return overload;
  }

  State state() {
    return state;
  }

  /**
   * Returns the completed result, if present.
   *
   * <p>Note: If a call completed successfully with a {@code null} value, this method returns {@code
   * Optional.empty()}. Callers should check {@link #state()} to distinguish between a call that has
   * not completed and one that succeeded with {@code null}.
   */
  Optional<Object> result() {
    return Optional.ofNullable(result);
  }

  Optional<Throwable> error() {
    return Optional.ofNullable(error);
  }

  private static int hashArg(@Nullable Object arg) {
    if (arg instanceof String) {
      return STRING_HASH_MARKER * 31 + arg.hashCode();
    }
    if (arg instanceof Boolean) {
      return BOOL_HASH_MARKER * 31 + arg.hashCode();
    }
    if (arg instanceof Number) {
      // Hash int, uint, and double through a common double representation so that values CEL
      // considers equal (1 == 1u == 1.0) share a bucket. NaN needs no special case because
      // Double.hashCode(NaN) is a constant across all double and float NaN representations.
      double value = ((Number) arg).doubleValue();
      // Normalize -0.0 to 0.0, which CEL considers equal to 0.0.
      return NUMBER_HASH_MARKER * 31 + Double.hashCode(value == 0.0d ? 0.0d : value);
    }
    return COMPLEX_HASH_MARKER;
  }

  private static boolean isNan(@Nullable Object value) {
    return value instanceof Number && Double.isNaN(((Number) value).doubleValue());
  }

  private boolean isPending() {
    return state == State.NOT_STARTED || state == State.RUNNING;
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
    this.functionName = checkNotNull(functionName);
    this.overloadId = checkNotNull(overloadId);
    this.args = checkNotNull(args).clone();
    this.overload = checkNotNull(overload);
  }
}
