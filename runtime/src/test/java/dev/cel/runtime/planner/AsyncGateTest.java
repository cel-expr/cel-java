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

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class AsyncGateTest {

  @Test
  public void tryAcquire_withAvailablePermits_returnsTrueAndIncrementsActiveCount() {
    AsyncGate gate = AsyncGate.create(2);

    boolean firstAcquired = gate.tryAcquire();
    boolean secondAcquired = gate.tryAcquire();

    assertThat(firstAcquired).isTrue();
    assertThat(secondAcquired).isTrue();
    assertThat(gate.activeCount()).isEqualTo(2);
    assertThat(gate.availablePermits()).isEqualTo(0);
  }

  @Test
  public void tryAcquire_atMaxConcurrency_returnsFalseAndDoesNotIncrementActiveCount() {
    AsyncGate gate = AsyncGate.create(1);
    assertThat(gate.tryAcquire()).isTrue();

    boolean acquired = gate.tryAcquire();

    assertThat(acquired).isFalse();
    assertThat(gate.activeCount()).isEqualTo(1);
    assertThat(gate.availablePermits()).isEqualTo(0);
  }

  @Test
  public void tryAcquire_unbounded_alwaysSucceeds() {
    AsyncGate gate = AsyncGate.create(0);

    boolean first = gate.tryAcquire();
    boolean second = gate.tryAcquire();
    boolean third = gate.tryAcquire();

    assertThat(first).isTrue();
    assertThat(second).isTrue();
    assertThat(third).isTrue();
    assertThat(gate.activeCount()).isEqualTo(3);
    assertThat(gate.availablePermits()).isEqualTo(-1);
  }

  @Test
  public void tryAcquire_negativeMaxConcurrency_treatedAsUnbounded() {
    AsyncGate gate = AsyncGate.create(-1);

    boolean acquired = gate.tryAcquire();

    assertThat(acquired).isTrue();
    assertThat(gate.activeCount()).isEqualTo(1);
    assertThat(gate.availablePermits()).isEqualTo(-1);
  }

  @Test
  public void tryAcquire_whenCancelled_returnsFalse() {
    AsyncGate gate = AsyncGate.create(2);
    gate.cancel();

    boolean acquired = gate.tryAcquire();

    assertThat(acquired).isFalse();
    assertThat(gate.activeCount()).isEqualTo(0);
    assertThat(gate.availablePermits()).isEqualTo(2);
  }

  @Test
  public void tryAcquire_unboundedWhenCancelled_returnsFalse() {
    AsyncGate gate = AsyncGate.create(0);
    gate.cancel();

    boolean acquired = gate.tryAcquire();

    assertThat(acquired).isFalse();
    assertThat(gate.activeCount()).isEqualTo(0);
  }

  @Test
  public void release_decrementsActiveCountAndFreesPermit() {
    AsyncGate gate = AsyncGate.create(2);
    assertThat(gate.tryAcquire()).isTrue();
    assertThat(gate.tryAcquire()).isTrue();

    gate.release();

    assertThat(gate.activeCount()).isEqualTo(1);
    assertThat(gate.availablePermits()).isEqualTo(1);
  }

  @Test
  public void release_unbounded_decrementsActiveCount() {
    AsyncGate gate = AsyncGate.create(0);
    assertThat(gate.tryAcquire()).isTrue();

    gate.release();

    assertThat(gate.activeCount()).isEqualTo(0);
  }

  @Test
  public void release_allowsSubsequentTryAcquire() {
    AsyncGate gate = AsyncGate.create(1);
    assertThat(gate.tryAcquire()).isTrue();

    gate.release();

    assertThat(gate.tryAcquire()).isTrue();
    assertThat(gate.activeCount()).isEqualTo(1);
  }

  @Test
  public void release_withoutPriorAcquire_doesNotExceedMaxConcurrency() {
    AsyncGate gate = AsyncGate.create(2);

    gate.release();

    assertThat(gate.availablePermits()).isEqualTo(2);
    assertThat(gate.activeCount()).isEqualTo(0);
  }

  @Test
  public void release_withoutPriorAcquire_doesNotUnderflowActiveCount() {
    AsyncGate gate = AsyncGate.create(0);

    gate.release();
    gate.release();

    assertThat(gate.activeCount()).isEqualTo(0);
  }

  @Test
  public void release_calledMoreThanAcquires_onlyReleasesAcquiredPermits() {
    AsyncGate gate = AsyncGate.create(1);
    assertThat(gate.tryAcquire()).isTrue();

    gate.release();
    gate.release();

    assertThat(gate.availablePermits()).isEqualTo(1);
    assertThat(gate.activeCount()).isEqualTo(0);
  }

  @Test
  public void cancel_setsIsCancelledToTrue() {
    AsyncGate gate = AsyncGate.create(1);

    gate.cancel();

    assertThat(gate.isCancelled()).isTrue();
  }

  @Test
  public void cancel_idempotent() {
    AsyncGate gate = AsyncGate.create(1);

    gate.cancel();
    gate.cancel();

    assertThat(gate.isCancelled()).isTrue();
  }

  @Test
  public void create_factoryMethod_returnsConfiguredGate() {
    AsyncGate gate = AsyncGate.create(5);

    assertThat(gate.availablePermits()).isEqualTo(5);
    assertThat(gate.activeCount()).isEqualTo(0);
    assertThat(gate.isCancelled()).isFalse();
  }

  @Test
  public void create_withMaxInteger_initializesCorrectly() {
    AsyncGate gate = AsyncGate.create(Integer.MAX_VALUE);

    assertThat(gate.availablePermits()).isEqualTo(Integer.MAX_VALUE);
    assertThat(gate.tryAcquire()).isTrue();
    assertThat(gate.activeCount()).isEqualTo(1);
  }

  @Test
  public void concurrentTryAcquireAndRelease_neverExceedsMaxConcurrency()
      throws InterruptedException {
    int maxConcurrency = 4;
    int taskCount = 32;
    AsyncGate gate = AsyncGate.create(maxConcurrency);
    AtomicInteger peakConcurrency = new AtomicInteger();
    ExecutorService executor = Executors.newFixedThreadPool(8);
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch doneLatch = new CountDownLatch(taskCount);

    try {
      for (int i = 0; i < taskCount; i++) {
        executor.execute(
            () -> {
              try {
                startLatch.await();
                while (!gate.tryAcquire()) {
                  Thread.sleep(1);
                }
                int current = gate.activeCount();
                peakConcurrency.accumulateAndGet(current, Math::max);
                Thread.sleep(2);
                gate.release();
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              } finally {
                doneLatch.countDown();
              }
            });
      }

      startLatch.countDown();
      boolean completed = doneLatch.await(5, SECONDS);

      assertThat(completed).isTrue();
      assertThat(peakConcurrency.get()).isAtMost(maxConcurrency);
      assertThat(gate.activeCount()).isEqualTo(0);
    } finally {
      executor.shutdown();
    }
  }
}
