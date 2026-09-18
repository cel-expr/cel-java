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
import static com.google.common.util.concurrent.Futures.immediateFuture;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.primitives.UnsignedLong;
import com.google.common.util.concurrent.SettableFuture;
import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import dev.cel.common.CelOptions;
import dev.cel.common.values.NullValue;
import dev.cel.runtime.CelAsyncFunctionOverload;
import dev.cel.runtime.RuntimeEquality;
import dev.cel.runtime.RuntimeHelpers;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(TestParameterInjector.class)
public final class AsyncCallRecordTest {

  private static final CelAsyncFunctionOverload DUMMY_OVERLOAD = args -> immediateFuture("ok");

  private static final RuntimeEquality RUNTIME_EQUALITY =
      RuntimeEquality.create(RuntimeHelpers.create(), CelOptions.DEFAULT);

  /** Each constant differs from the base call in exactly one component. */
  @SuppressWarnings("ImmutableEnumChecker")
  private enum CallMismatch {
    EXPR_ID(20L, "myFunc", "myFunc_overload", ImmutableList.<Object>of(1L, "a")),
    FUNCTION_NAME(10L, "otherFunc", "myFunc_overload", ImmutableList.<Object>of(1L, "a")),
    OVERLOAD_ID(10L, "myFunc", "other_overload", ImmutableList.<Object>of(1L, "a")),
    ARITY_FEWER(10L, "myFunc", "myFunc_overload", ImmutableList.<Object>of(1L)),
    ARITY_MORE(10L, "myFunc", "myFunc_overload", ImmutableList.<Object>of(1L, "a", "extra")),
    ARG_VALUE(10L, "myFunc", "myFunc_overload", ImmutableList.<Object>of(2L, "a"));

    private final long exprId;
    private final String functionName;
    private final String overloadId;
    private final ImmutableList<Object> args;

    boolean matchesAgainst(AsyncCallRecord record) {
      return record.matches(exprId, functionName, overloadId, args.toArray(), RUNTIME_EQUALITY);
    }

    CallMismatch(long exprId, String functionName, String overloadId, ImmutableList<Object> args) {
      this.exprId = exprId;
      this.functionName = functionName;
      this.overloadId = overloadId;
      this.args = args;
    }
  }

  @Test
  public void initialValues_matchConstructor() {
    AsyncCallRecord record =
        AsyncCallRecord.create(
            1L, 10L, "myFunc", "myFunc_overload", new Object[] {"arg1"}, DUMMY_OVERLOAD);

    assertThat(record.callId()).isEqualTo(1L);
    assertThat(record.exprId()).isEqualTo(10L);
    assertThat(record.functionName()).isEqualTo("myFunc");
    assertThat(record.overloadId()).isEqualTo("myFunc_overload");
    assertThat(record.args()).asList().containsExactly("arg1");
    assertThat(record.overload()).isEqualTo(DUMMY_OVERLOAD);
    assertThat(record.state()).isEqualTo(AsyncCallRecord.State.NOT_STARTED);
    assertThat(record.isCancelled()).isFalse();
    assertThat(record.result()).isEmpty();
    assertThat(record.error()).isEmpty();
  }

  @Test
  public void create_nullArguments_throwsNullPointerException() {
    assertThrows(
        NullPointerException.class,
        () -> AsyncCallRecord.create(1L, 10L, null, "overload", new Object[0], DUMMY_OVERLOAD));
    assertThrows(
        NullPointerException.class,
        () -> AsyncCallRecord.create(1L, 10L, "func", null, new Object[0], DUMMY_OVERLOAD));
    assertThrows(
        NullPointerException.class,
        () -> AsyncCallRecord.create(1L, 10L, "func", "overload", null, DUMMY_OVERLOAD));
    assertThrows(
        NullPointerException.class,
        () -> AsyncCallRecord.create(1L, 10L, "func", "overload", new Object[0], null));
  }

  @Test
  public void hashCall_celEqualNumericArgs_shareBucket() {
    // 1 == 1u == 1.0 in CEL, so all three must land in the same bucket.
    int intHash = AsyncCallRecord.hashCall(10L, "ov", new Object[] {1L});

    assertThat(AsyncCallRecord.hashCall(10L, "ov", new Object[] {UnsignedLong.ONE}))
        .isEqualTo(intHash);
    assertThat(AsyncCallRecord.hashCall(10L, "ov", new Object[] {1.0d})).isEqualTo(intHash);
  }

  @Test
  public void hashCall_signedZeroArgs_shareBucket() {
    int zeroHash = AsyncCallRecord.hashCall(10L, "ov", new Object[] {0.0d});

    assertThat(AsyncCallRecord.hashCall(10L, "ov", new Object[] {-0.0d})).isEqualTo(zeroHash);
    assertThat(AsyncCallRecord.hashCall(10L, "ov", new Object[] {0L})).isEqualTo(zeroHash);
    assertThat(AsyncCallRecord.hashCall(10L, "ov", new Object[] {UnsignedLong.ZERO}))
        .isEqualTo(zeroHash);
  }

  @Test
  public void hashCall_distinctNanRepresentations_shareBucket() {
    int nanHash = AsyncCallRecord.hashCall(10L, "ov", new Object[] {Double.NaN});

    assertThat(
            AsyncCallRecord.hashCall(
                10L, "ov", new Object[] {Double.longBitsToDouble(0x7ff8000000000001L)}))
        .isEqualTo(nanHash);
    assertThat(AsyncCallRecord.hashCall(10L, "ov", new Object[] {Float.NaN})).isEqualTo(nanHash);
  }

  @Test
  public void hashCall_distinctCalls_produceDistinctBuckets() {
    // Distinctness is best-effort (hashCall is a bucketing hint), but verifies that call site
    // components and argument boundaries are salted.
    int base = AsyncCallRecord.hashCall(10L, "ov", new Object[] {"a", "bc"});

    assertThat(AsyncCallRecord.hashCall(11L, "ov", new Object[] {"a", "bc"})).isNotEqualTo(base);
    assertThat(AsyncCallRecord.hashCall(10L, "other", new Object[] {"a", "bc"})).isNotEqualTo(base);
    // Arguments are mixed in separately, so ("a", "bc") does not collide with ("ab", "c").
    assertThat(AsyncCallRecord.hashCall(10L, "ov", new Object[] {"ab", "c"})).isNotEqualTo(base);
    // Type markers keep the string "NaN" distinct from the double NaN.
    assertThat(AsyncCallRecord.hashCall(10L, "ov", new Object[] {Double.NaN}))
        .isNotEqualTo(AsyncCallRecord.hashCall(10L, "ov", new Object[] {"NaN"}));
  }

  @Test
  public void hashCall_complexArgs_shareSingleBucket() {
    // Complex values are deliberately excluded from the hash because they need a richer
    // equivalence than a value hash can express. They share one bucket and are separated by
    // matches() instead.
    int listHash = AsyncCallRecord.hashCall(10L, "ov", new Object[] {ImmutableList.of(1L)});

    assertThat(AsyncCallRecord.hashCall(10L, "ov", new Object[] {ImmutableList.of(2L)}))
        .isEqualTo(listHash);
    assertThat(AsyncCallRecord.hashCall(10L, "ov", new Object[] {ImmutableMap.of("k", "v")}))
        .isEqualTo(listHash);
    assertThat(AsyncCallRecord.hashCall(10L, "ov", new Object[] {NullValue.NULL_VALUE}))
        .isEqualTo(listHash);
  }

  @Test
  public void matches_identicalCall_returnsTrue() {
    AsyncCallRecord record = recordWithArgs(1L, "a");

    assertThat(matches(record, 10L, "myFunc", "myFunc_overload", 1L, "a")).isTrue();
  }

  @Test
  public void matches_mismatchedComponent_returnsFalse(@TestParameter CallMismatch mismatch) {
    AsyncCallRecord record = recordWithArgs(1L, "a");

    assertThat(mismatch.matchesAgainst(record)).isFalse();
  }

  @Test
  public void matches_crossTypeNumericArgs_returnsTrue() {
    AsyncCallRecord record = recordWithArgs(1L);

    assertThat(matches(record, 10L, "myFunc", "myFunc_overload", 1.0d)).isTrue();
    assertThat(matches(record, 10L, "myFunc", "myFunc_overload", UnsignedLong.ONE)).isTrue();
  }

  @Test
  public void matches_signedZeroArgs_returnsTrue() {
    AsyncCallRecord record = recordWithArgs(0.0d);

    assertThat(matches(record, 10L, "myFunc", "myFunc_overload", -0.0d)).isTrue();
  }

  @Test
  public void matches_nanArgs_returnsTrue() {
    // CEL defines NaN != NaN. Without the override a node re-evaluated with a NaN argument would
    // never find its record and would dispatch a fresh call on every pass.
    AsyncCallRecord record = recordWithArgs(Double.NaN);

    assertThat(matches(record, 10L, "myFunc", "myFunc_overload", Double.NaN)).isTrue();
  }

  @Test
  public void matches_floatNanArgs_returnsTrue() {
    AsyncCallRecord record = recordWithArgs(Float.NaN);

    assertThat(matches(record, 10L, "myFunc", "myFunc_overload", Float.NaN)).isTrue();
    assertThat(matches(record, 10L, "myFunc", "myFunc_overload", Double.NaN)).isTrue();
  }

  @Test
  public void matches_nanVsNonNan_returnsFalse() {
    AsyncCallRecord record = recordWithArgs(Double.NaN);

    assertThat(matches(record, 10L, "myFunc", "myFunc_overload", 0.0d)).isFalse();
    assertThat(matches(record, 10L, "myFunc", "myFunc_overload", 1.0d)).isFalse();
  }

  @Test
  public void matches_celEqualCollectionArgs_returnsTrue() {
    AsyncCallRecord record = recordWithArgs(ImmutableList.of(1L, 2L), ImmutableMap.of(1L, "v"));

    assertThat(
            matches(
                record,
                10L,
                "myFunc",
                "myFunc_overload",
                ImmutableList.of(1.0d, 2.0d),
                ImmutableMap.of(UnsignedLong.ONE, "v")))
        .isTrue();
  }

  @Test
  public void matches_differingCollectionArgs_returnsFalse() {
    AsyncCallRecord record = recordWithArgs(ImmutableList.of(1L, 2L), ImmutableMap.of(1L, "v"));

    assertThat(
            matches(
                record,
                10L,
                "myFunc",
                "myFunc_overload",
                ImmutableList.of(1L, 3L),
                ImmutableMap.of(1L, "v")))
        .isFalse();
  }

  @Test
  public void matches_differentArgTypes_returnsFalse() {
    AsyncCallRecord record = recordWithArgs("notANumber");

    assertThat(matches(record, 10L, "myFunc", "myFunc_overload", 1L)).isFalse();
  }

  @Test
  public void markRunning_successFromNotStarted() {
    AsyncCallRecord record = createRecord(AsyncCallRecord.State.NOT_STARTED);

    boolean marked = record.markRunning();

    assertThat(marked).isTrue();
    assertThat(record.state()).isEqualTo(AsyncCallRecord.State.RUNNING);
  }

  @Test
  public void markRunning_whenAlreadyTerminalOrRunning_returnsFalse(
      @TestParameter({"RUNNING", "SUCCESS", "FAILURE", "CANCELLED"}) AsyncCallRecord.State state) {
    AsyncCallRecord record = createRecord(state);

    boolean marked = record.markRunning();

    assertThat(marked).isFalse();
    assertThat(record.state()).isEqualTo(state);
  }

  @Test
  public void concurrentMarkRunning_exactlyOneSucceeds() throws Exception {
    int numThreads = 4;
    for (int i = 0; i < 100; i++) {
      AsyncCallRecord record = createRecord(AsyncCallRecord.State.NOT_STARTED);
      CountDownLatch startLatch = new CountDownLatch(1);
      AtomicInteger successCount = new AtomicInteger();
      Thread[] threads = new Thread[numThreads];

      for (int t = 0; t < numThreads; t++) {
        threads[t] =
            new Thread(
                () -> {
                  try {
                    startLatch.await();
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                  }
                  if (record.markRunning()) {
                    successCount.incrementAndGet();
                  }
                });
        threads[t].start();
      }

      startLatch.countDown();
      for (Thread thread : threads) {
        thread.join();
      }

      assertThat(successCount.get()).isEqualTo(1);
      assertThat(record.state()).isEqualTo(AsyncCallRecord.State.RUNNING);
    }
  }

  @Test
  public void concurrentMarkRunningAndCancelInFlight_alwaysCancelsSuccessfully() throws Exception {
    for (int i = 0; i < 100; i++) {
      AsyncCallRecord record = createRecord(AsyncCallRecord.State.NOT_STARTED);
      CountDownLatch startLatch = new CountDownLatch(1);
      AtomicBoolean markedRunning = new AtomicBoolean();
      AtomicBoolean cancelled = new AtomicBoolean();

      Thread t1 =
          new Thread(
              () -> {
                try {
                  startLatch.await();
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                  return;
                }
                markedRunning.set(record.markRunning());
              });
      Thread t2 =
          new Thread(
              () -> {
                try {
                  startLatch.await();
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                  return;
                }
                cancelled.set(record.cancelInFlight());
              });

      t1.start();
      t2.start();
      startLatch.countDown();
      t1.join();
      t2.join();

      assertThat(cancelled.get()).isTrue();
      assertThat(record.isCancelled()).isTrue();
      assertThat(record.state()).isEqualTo(AsyncCallRecord.State.CANCELLED);
    }
  }

  @Test
  public void complete_successFromActiveState(
      @TestParameter({"NOT_STARTED", "RUNNING"}) AsyncCallRecord.State state) {
    AsyncCallRecord record = createRecord(state);

    boolean completed = record.complete("successResult");

    assertThat(completed).isTrue();
    assertThat(record.state()).isEqualTo(AsyncCallRecord.State.SUCCESS);
    assertThat(record.result()).hasValue("successResult");
    assertThat(record.error()).isEmpty();
  }

  @Test
  public void complete_whenAlreadyCompleted_returnsFalseAndDoesNotOverwrite() {
    AsyncCallRecord record = createRecord(AsyncCallRecord.State.RUNNING);
    record.complete("firstResult");

    boolean secondCompleted = record.complete("secondResult");

    assertThat(secondCompleted).isFalse();
    assertThat(record.result()).hasValue("firstResult");
    assertThat(record.state()).isEqualTo(AsyncCallRecord.State.SUCCESS);
  }

  @Test
  public void fail_successFromActiveState(
      @TestParameter({"NOT_STARTED", "RUNNING"}) AsyncCallRecord.State state) {
    AsyncCallRecord record = createRecord(state);
    RuntimeException error = new RuntimeException("test error");

    boolean failed = record.fail(error);

    assertThat(failed).isTrue();
    assertThat(record.state()).isEqualTo(AsyncCallRecord.State.FAILURE);
    assertThat(record.error()).hasValue(error);
    assertThat(record.result()).isEmpty();
  }

  @Test
  public void fail_whenAlreadyCompleted_returnsFalseAndDoesNotOverwrite() {
    AsyncCallRecord record = createRecord(AsyncCallRecord.State.RUNNING);
    record.complete("firstResult");

    boolean failed = record.fail(new RuntimeException("subsequent failure"));

    assertThat(failed).isFalse();
    assertThat(record.result()).hasValue("firstResult");
    assertThat(record.error()).isEmpty();
    assertThat(record.state()).isEqualTo(AsyncCallRecord.State.SUCCESS);
  }

  @Test
  public void cancelInFlight_activeState_cancelsFutureAndTransitionsToCancelled(
      @TestParameter({"NOT_STARTED", "RUNNING"}) AsyncCallRecord.State state) {
    AsyncCallRecord record = createRecord(state);
    SettableFuture<String> future = SettableFuture.create();
    record.setInFlightFuture(future);

    boolean cancelled = record.cancelInFlight();

    assertThat(cancelled).isTrue();
    assertThat(record.isCancelled()).isTrue();
    assertThat(record.state()).isEqualTo(AsyncCallRecord.State.CANCELLED);
    assertThat(future.isCancelled()).isTrue();
  }

  @Test
  public void cancelInFlight_completedOrFailed_returnsFalseAndPreservesState(
      @TestParameter({"SUCCESS", "FAILURE"}) AsyncCallRecord.State state) {
    AsyncCallRecord record = createRecord(state);
    SettableFuture<String> future = SettableFuture.create();
    record.setInFlightFuture(future);

    boolean cancelled = record.cancelInFlight();

    assertThat(cancelled).isFalse();
    assertThat(record.isCancelled()).isFalse();
    assertThat(record.state()).isEqualTo(state);
    assertThat(future.isCancelled()).isFalse();
  }

  @Test
  public void cancelInFlight_alreadyCancelled_returnsFalse() {
    AsyncCallRecord record = createRecord(AsyncCallRecord.State.CANCELLED);

    boolean cancelled = record.cancelInFlight();

    assertThat(cancelled).isFalse();
    assertThat(record.isCancelled()).isTrue();
    assertThat(record.state()).isEqualTo(AsyncCallRecord.State.CANCELLED);
  }

  @Test
  public void setInFlightFuture_afterCancelled_cancelsImmediately() {
    AsyncCallRecord record = createRecord(AsyncCallRecord.State.CANCELLED);
    SettableFuture<String> future = SettableFuture.create();

    record.setInFlightFuture(future);

    assertThat(future.isCancelled()).isTrue();
  }

  @Test
  public void markCompletionReported_onlyFirstCallerSucceeds() {
    AsyncCallRecord record = createRecord(AsyncCallRecord.State.RUNNING);

    boolean firstReport = record.markCompletionReported();
    boolean secondReport = record.markCompletionReported();

    assertThat(firstReport).isTrue();
    assertThat(secondReport).isFalse();
  }

  @Test
  public void markCompletionReported_afterCancellation_stillSucceedsOnce() {
    AsyncCallRecord record = createRecord(AsyncCallRecord.State.RUNNING);
    record.cancelInFlight();

    boolean firstReport = record.markCompletionReported();
    boolean secondReport = record.markCompletionReported();

    assertThat(firstReport).isTrue();
    assertThat(secondReport).isFalse();
  }

  @Test
  public void concurrentCancellationAndSetInFlightFuture_futureIsAlwaysCancelled()
      throws Exception {
    for (int i = 0; i < 100; i++) {
      AsyncCallRecord record = createRecord(AsyncCallRecord.State.RUNNING);
      SettableFuture<String> future = SettableFuture.create();
      CountDownLatch startLatch = new CountDownLatch(1);
      AtomicBoolean cancelled = new AtomicBoolean();

      Thread cancelThread =
          new Thread(
              () -> {
                try {
                  startLatch.await();
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                }
                cancelled.set(record.cancelInFlight());
              });
      Thread setFutureThread =
          new Thread(
              () -> {
                try {
                  startLatch.await();
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                }
                record.setInFlightFuture(future);
              });

      cancelThread.start();
      setFutureThread.start();
      startLatch.countDown();
      cancelThread.join();
      setFutureThread.join();

      assertThat(record.isCancelled()).isTrue();
      assertThat(record.state()).isEqualTo(AsyncCallRecord.State.CANCELLED);
      assertThat(future.isCancelled()).isTrue();
    }
  }

  @Test
  public void concurrentCompleteAndCancelInFlight_exactlyOneWinner() throws Exception {
    for (int i = 0; i < 100; i++) {
      AsyncCallRecord record = createRecord(AsyncCallRecord.State.RUNNING);
      CountDownLatch startLatch = new CountDownLatch(1);
      AtomicBoolean completed = new AtomicBoolean();
      AtomicBoolean cancelled = new AtomicBoolean();

      Thread completeThread =
          new Thread(
              () -> {
                try {
                  startLatch.await();
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                }
                completed.set(record.complete("success"));
              });
      Thread cancelThread =
          new Thread(
              () -> {
                try {
                  startLatch.await();
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                }
                cancelled.set(record.cancelInFlight());
              });

      completeThread.start();
      cancelThread.start();
      startLatch.countDown();
      completeThread.join();
      cancelThread.join();

      assertThat(completed.get() ^ cancelled.get()).isTrue();
      if (completed.get()) {
        assertThat(record.state()).isEqualTo(AsyncCallRecord.State.SUCCESS);
        assertThat(record.result()).hasValue("success");
      } else {
        assertThat(record.state()).isEqualTo(AsyncCallRecord.State.CANCELLED);
        assertThat(record.result()).isEmpty();
      }
    }
  }

  @Test
  public void args_returnsDefensiveCopy() {
    AsyncCallRecord record =
        AsyncCallRecord.create(
            1L, 10L, "myFunc", "myFunc_overload", new Object[] {"original"}, DUMMY_OVERLOAD);

    Object[] returnedArgs = record.args();
    returnedArgs[0] = "mutated";

    assertThat(record.args()).asList().containsExactly("original");
  }

  private static AsyncCallRecord recordWithArgs(Object... args) {
    return AsyncCallRecord.create(1L, 10L, "myFunc", "myFunc_overload", args, DUMMY_OVERLOAD);
  }

  private static boolean matches(
      AsyncCallRecord record, long exprId, String functionName, String overloadId, Object... args) {
    return record.matches(exprId, functionName, overloadId, args, RUNTIME_EQUALITY);
  }

  private static AsyncCallRecord createRecord(AsyncCallRecord.State state) {
    AsyncCallRecord record =
        AsyncCallRecord.create(1L, 10L, "myFunc", "myFunc_overload", new Object[0], DUMMY_OVERLOAD);
    switch (state) {
      case NOT_STARTED:
        break;
      case RUNNING:
        record.markRunning();
        break;
      case SUCCESS:
        record.complete("success");
        break;
      case FAILURE:
        record.fail(new RuntimeException("failed"));
        break;
      case CANCELLED:
        record.cancelInFlight();
        break;
    }
    return record;
  }
}
