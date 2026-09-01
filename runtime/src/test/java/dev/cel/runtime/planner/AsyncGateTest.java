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
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ForwardingQueue;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import java.util.AbstractQueue;
import java.util.Collections;
import java.util.Iterator;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(TestParameterInjector.class)
public final class AsyncGateTest {

  @Test
  public void unboundedConcurrency_runsImmediatelyAndTracksActive() {
    AsyncGate gate = new AsyncGate(0);
    AtomicBoolean ran = new AtomicBoolean(false);

    gate.dispatch(Runnable::run, () -> ran.set(true));

    assertThat(ran.get()).isTrue();
    assertThat(gate.activeCount()).isEqualTo(1);

    gate.releasePermit(Runnable::run);
    assertThat(gate.activeCount()).isEqualTo(0);
  }

  @Test
  public void boundedConcurrency_queuesAndDrainsOnRelease() {
    AsyncGate gate = new AsyncGate(1);
    AtomicInteger tasksExecuted = new AtomicInteger();

    gate.dispatch(Runnable::run, tasksExecuted::incrementAndGet);
    assertThat(tasksExecuted.get()).isEqualTo(1);
    assertThat(gate.activeCount()).isEqualTo(1);

    // Second task should be queued in pendingTasks because concurrency limit is 1
    gate.dispatch(Runnable::run, tasksExecuted::incrementAndGet);
    assertThat(tasksExecuted.get()).isEqualTo(1);

    // Releasing permit drains the pending task
    gate.releasePermit(Runnable::run);
    assertThat(tasksExecuted.get()).isEqualTo(2);
    assertThat(gate.activeCount()).isEqualTo(1);

    gate.releasePermit(Runnable::run);
    assertThat(gate.activeCount()).isEqualTo(0);
  }

  @Test
  public void cancel_clearsPendingTasksAndIgnoresNewTasks() {
    AsyncGate gate = new AsyncGate(1);
    AtomicInteger tasksExecuted = new AtomicInteger();

    gate.dispatch(Runnable::run, tasksExecuted::incrementAndGet);
    assertThat(tasksExecuted.get()).isEqualTo(1);

    gate.dispatch(Runnable::run, tasksExecuted::incrementAndGet);
    assertThat(tasksExecuted.get()).isEqualTo(1);

    gate.cancel();

    // Releasing permit should not run the queued task because it was cleared
    gate.releasePermit(Runnable::run);
    assertThat(tasksExecuted.get()).isEqualTo(1);

    // New dispatch after cancel is ignored
    gate.dispatch(Runnable::run, tasksExecuted::incrementAndGet);
    assertThat(tasksExecuted.get()).isEqualTo(1);
  }

  @Test
  public void cancel_withQueuedPendingTasks_clearsPendingTasksImmediately() {
    ConcurrentLinkedQueue<Runnable> pendingTasks = new ConcurrentLinkedQueue<>();
    AsyncGate gate = new AsyncGate(1, pendingTasks);

    gate.dispatch(Runnable::run, () -> {});
    gate.dispatch(Runnable::run, () -> {});
    assertThat(pendingTasks).hasSize(1);

    gate.cancel();

    assertThat(pendingTasks).isEmpty();
  }

  @Test
  public void drainPending_whenCancelled_clearsPendingTasksAndDoesNotRun() {
    ConcurrentLinkedQueue<Runnable> pendingTasks = new ConcurrentLinkedQueue<>();
    AsyncGate gate = new AsyncGate(1, pendingTasks);
    AtomicBoolean taskRan = new AtomicBoolean(false);

    gate.dispatch(Runnable::run, () -> {});
    gate.cancel();

    pendingTasks.add(() -> taskRan.set(true));

    gate.releasePermit(Runnable::run);

    assertThat(taskRan.get()).isFalse();
    assertThat(pendingTasks).isEmpty();
  }

  @Test
  public void dispatch_whenPermitAvailableAfterQueueing_drainPendingRunsTask() {
    AtomicBoolean taskRan = new AtomicBoolean(false);
    Semaphore semaphore = new Semaphore(1);
    ConcurrentLinkedQueue<Runnable> delegate = new ConcurrentLinkedQueue<>();
    Queue<Runnable> queue =
        new ForwardingQueue<Runnable>() {
          @Override
          protected Queue<Runnable> delegate() {
            return delegate;
          }

          @Override
          public boolean add(Runnable r) {
            boolean res = super.add(r);
            // Release permit directly on semaphore without calling drainPending.
            // This verifies that dispatch drains the queue if a permit became available after
            // queueing.
            semaphore.release();
            return res;
          }
        };
    AsyncGate gate = new AsyncGate(semaphore, queue);

    gate.dispatch(Runnable::run, () -> {});
    gate.dispatch(Runnable::run, () -> taskRan.set(true));

    assertThat(taskRan.get()).isTrue();
  }

  @Test
  public void concurrentDrainAndCancel_retainsPermitBalance() throws Exception {
    ExecutorService executor = Executors.newFixedThreadPool(4);
    try {
      for (int i = 0; i < 50; i++) {
        AsyncGate gate = new AsyncGate(1);
        CountDownLatch startLatch = new CountDownLatch(1);

        CountDownLatch doneLatch = new CountDownLatch(2);

        gate.dispatch(executor, () -> {});
        gate.dispatch(executor, () -> {});

        executor.execute(
            () -> {
              try {
                startLatch.await();
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              } finally {
                gate.releasePermit(executor);
                doneLatch.countDown();
              }
            });

        executor.execute(
            () -> {
              try {
                startLatch.await();
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              } finally {
                gate.cancel();
                doneLatch.countDown();
              }
            });

        startLatch.countDown();
        assertThat(doneLatch.await(5, SECONDS)).isTrue();
        assertThat(gate.activeCount()).isAtLeast(0);
      }
    } finally {
      executor.shutdown();
      executor.awaitTermination(5, SECONDS);
    }
  }

  @Test
  public void drainPending_whenTaskPolledIsNull_releasesPermitAndBreaks() {
    Queue<Runnable> queue =
        new AbstractQueue<Runnable>() {
          private boolean first = true;

          @Override
          public boolean offer(Runnable e) {
            return true;
          }

          @Override
          public Runnable peek() {
            return null;
          }

          @Override
          public Iterator<Runnable> iterator() {
            return Collections.emptyIterator();
          }

          @Override
          public int size() {
            return first ? 1 : 0;
          }

          @Override
          public boolean isEmpty() {
            if (first) {
              first = false;
              return false;
            }
            return true;
          }

          @Override
          public Runnable poll() {
            return null;
          }
        };
    AsyncGate gate = new AsyncGate(1, queue);
    gate.releasePermit(Runnable::run);

    assertThat(gate.activeCount()).isEqualTo(-1);
  }

  @Test
  public void dispatch_taskThrowsRuntimeException_releasesPermitAndDecrementsActiveCount() {
    AsyncGate gate = new AsyncGate(1);
    assertThrows(
        RuntimeException.class,
        () ->
            gate.dispatch(
                Runnable::run,
                () -> {
                  throw new RuntimeException("fail");
                }));

    assertThat(gate.activeCount()).isEqualTo(0);
    // Next task should be able to acquire permit immediately
    AtomicBoolean secondRan = new AtomicBoolean(false);
    gate.dispatch(Runnable::run, () -> secondRan.set(true));
    assertThat(secondRan.get()).isTrue();
    assertThat(gate.activeCount()).isEqualTo(1);
  }

  @Test
  public void drainPending_executorThrows_releasesPermitDecrementsActiveCountAndRethrows() {
    AsyncGate gate = new AsyncGate(1);
    gate.dispatch(Runnable::run, () -> {});
    gate.dispatch(Runnable::run, () -> {});

    Executor rejectingExecutor =
        r -> {
          throw new RejectedExecutionException("rejected");
        };

    assertThrows(RejectedExecutionException.class, () -> gate.releasePermit(rejectingExecutor));

    assertThat(gate.activeCount()).isEqualTo(0);
    AtomicBoolean secondRan = new AtomicBoolean(false);
    gate.dispatch(Runnable::run, () -> secondRan.set(true));
    assertThat(secondRan.get()).isTrue();
    assertThat(gate.activeCount()).isEqualTo(1);
  }
}
