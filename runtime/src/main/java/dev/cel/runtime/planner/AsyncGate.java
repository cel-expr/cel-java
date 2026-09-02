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

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;

/** Regulates the number of concurrent asynchronous function executions based on maxConcurrency. */
final class AsyncGate {
  private final @Nullable Semaphore semaphore;
  private final Queue<Runnable> pendingTasks;
  private final AtomicInteger activeCount = new AtomicInteger();
  private final AtomicBoolean cancelled = new AtomicBoolean(false);

  AsyncGate(int maxConcurrency) {
    this(maxConcurrency, new ConcurrentLinkedQueue<>());
  }

  AsyncGate(int maxConcurrency, Queue<Runnable> pendingTasks) {
    this(maxConcurrency > 0 ? new Semaphore(maxConcurrency) : null, pendingTasks);
  }

  AsyncGate(@Nullable Semaphore semaphore, Queue<Runnable> pendingTasks) {
    this.semaphore = semaphore;
    this.pendingTasks = requireNonNull(pendingTasks);
  }

  void cancel() {
    cancelled.set(true);
    pendingTasks.clear();
  }

  void dispatch(Executor executor, Runnable task) {
    if (cancelled.get()) {
      return;
    }

    if (semaphore == null) {
      activeCount.incrementAndGet();
      try {
        task.run();
      } catch (Throwable e) {
        activeCount.decrementAndGet();
        throw e;
      }
      return;
    }

    if (semaphore.tryAcquire()) {
      activeCount.incrementAndGet();
      try {
        task.run();
      } catch (Throwable e) {
        activeCount.decrementAndGet();
        semaphore.release();
        throw e;
      }
      return;
    }

    pendingTasks.add(task);
    drainPending(executor);
  }

  void releasePermit(Executor executor) {
    activeCount.decrementAndGet();
    if (semaphore != null) {
      semaphore.release();
      drainPending(executor);
    }
  }

  private void drainPending(Executor executor) {
    if (cancelled.get()) {
      pendingTasks.clear();
      return;
    }
    while (!pendingTasks.isEmpty()) {
      if (!semaphore.tryAcquire()) {
        break;
      }
      Runnable task = pendingTasks.poll();
      if (task != null) {
        activeCount.incrementAndGet();
        try {
          executor.execute(task);
        } catch (Throwable e) {
          activeCount.decrementAndGet();
          semaphore.release();
          throw e;
        }
      } else {
        semaphore.release();
        break;
      }
    }
  }

  int activeCount() {
    return activeCount.get();
  }
}
