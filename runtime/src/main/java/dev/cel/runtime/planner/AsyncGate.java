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

import com.google.common.annotations.VisibleForTesting;
import com.google.errorprone.annotations.CheckReturnValue;
import javax.annotation.concurrent.ThreadSafe;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;

/**
 * Regulates the number of concurrent asynchronous function executions based on maxConcurrency.
 *
 * <p>A {@code maxConcurrency} value of {@code 0} or less represents unbounded concurrency (no limit
 * on concurrent executions).
 */
@ThreadSafe
final class AsyncGate {

  /** Null when {@code maxConcurrency <= 0}, indicating unbounded concurrency (no throttling). */
  private final @Nullable Semaphore semaphore;

  private final AtomicInteger activeCalls;
  private final AtomicBoolean cancelled;

  /**
   * Creates an {@link AsyncGate} regulating concurrent asynchronous calls.
   *
   * @param maxConcurrency the maximum number of concurrent executions allowed. A value of {@code 0}
   *     or less indicates unbounded concurrency (no concurrency limit).
   */
  static AsyncGate create(int maxConcurrency) {
    return new AsyncGate(maxConcurrency);
  }

  /**
   * Attempts to acquire a concurrency slot for an asynchronous call.
   *
   * @return true if a slot was acquired; false if the gate is cancelled or at maximum concurrency.
   */
  @CheckReturnValue
  boolean tryAcquire() {
    if (semaphore != null && !semaphore.tryAcquire()) {
      return false;
    }
    if (cancelled.get()) {
      if (semaphore != null) {
        semaphore.release();
      }
      return false;
    }
    activeCalls.incrementAndGet();
    return true;
  }

  /** Releases a previously acquired concurrency slot and decrements the active call count. */
  void release() {
    while (true) {
      int current = activeCalls.get();
      if (current <= 0) {
        return;
      }
      if (activeCalls.compareAndSet(current, current - 1)) {
        if (semaphore != null) {
          semaphore.release();
        }
        return;
      }
    }
  }

  /** Cancels the gate, preventing any future calls from acquiring permits. */
  void cancel() {
    cancelled.set(true);
  }

  /** Returns true if the gate has been cancelled. */
  boolean isCancelled() {
    return cancelled.get();
  }

  /** Returns the current number of active in-flight calls. */
  int activeCount() {
    return activeCalls.get();
  }

  /** Returns the number of currently available permits, or -1 if unbounded. */
  @VisibleForTesting
  int availablePermits() {
    return semaphore != null ? semaphore.availablePermits() : -1;
  }

  private AsyncGate(int maxConcurrency) {
    this.semaphore = maxConcurrency > 0 ? new Semaphore(maxConcurrency) : null;
    this.activeCalls = new AtomicInteger();
    this.cancelled = new AtomicBoolean(false);
  }
}
