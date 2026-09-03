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

import com.google.common.util.concurrent.SettableFuture;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import dev.cel.runtime.CelAsyncFunctionOverload;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(TestParameterInjector.class)
public final class AsyncCallRecordTest {

  private static final CelAsyncFunctionOverload DUMMY_OVERLOAD = args -> immediateFuture("ok");

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
  public void markRunning_success() {
    AsyncCallRecord record =
        AsyncCallRecord.create(1L, 10L, "myFunc", "myFunc_overload", new Object[0], DUMMY_OVERLOAD);

    boolean marked = record.markRunning();

    assertThat(marked).isTrue();
    assertThat(record.state()).isEqualTo(AsyncCallRecord.State.RUNNING);
  }

  @Test
  public void markRunning_afterCancelled_returnsFalseAndTransitionsToCancelled() {
    AsyncCallRecord record =
        AsyncCallRecord.create(1L, 10L, "myFunc", "myFunc_overload", new Object[0], DUMMY_OVERLOAD);
    record.cancelInFlight();

    boolean marked = record.markRunning();

    assertThat(marked).isFalse();
    assertThat(record.state()).isEqualTo(AsyncCallRecord.State.CANCELLED);
  }

  @Test
  public void complete_updatesStateAndResult() {
    AsyncCallRecord record =
        AsyncCallRecord.create(1L, 10L, "myFunc", "myFunc_overload", new Object[0], DUMMY_OVERLOAD);

    record.complete("successResult");

    assertThat(record.state()).isEqualTo(AsyncCallRecord.State.SUCCESS);
    assertThat(record.result()).hasValue("successResult");
    assertThat(record.error()).isEmpty();
  }

  @Test
  public void fail_updatesStateAndError() {
    AsyncCallRecord record =
        AsyncCallRecord.create(1L, 10L, "myFunc", "myFunc_overload", new Object[0], DUMMY_OVERLOAD);
    RuntimeException error = new RuntimeException("test error");

    record.fail(error);

    assertThat(record.state()).isEqualTo(AsyncCallRecord.State.FAILURE);
    assertThat(record.error()).hasValue(error);
    assertThat(record.result()).isEmpty();
  }

  @Test
  public void cancelInFlight_cancelsFutureAndTransitionsToCancelled() {
    AsyncCallRecord record =
        AsyncCallRecord.create(1L, 10L, "myFunc", "myFunc_overload", new Object[0], DUMMY_OVERLOAD);
    SettableFuture<String> future = SettableFuture.create();
    record.setInFlightFuture(future);

    record.cancelInFlight();

    assertThat(record.isCancelled()).isTrue();
    assertThat(record.state()).isEqualTo(AsyncCallRecord.State.CANCELLED);
    assertThat(future.isCancelled()).isTrue();
  }

  @Test
  public void setInFlightFuture_afterCancelled_cancelsImmediately() {
    AsyncCallRecord record =
        AsyncCallRecord.create(1L, 10L, "myFunc", "myFunc_overload", new Object[0], DUMMY_OVERLOAD);
    record.cancelInFlight();
    SettableFuture<String> future = SettableFuture.create();

    record.setInFlightFuture(future);

    assertThat(future.isCancelled()).isTrue();
  }

  @Test
  public void complete_whenAlreadyCompleted_returnsFalseAndDoesNotOverwrite() {
    AsyncCallRecord record =
        AsyncCallRecord.create(1L, 10L, "myFunc", "myFunc_overload", new Object[0], DUMMY_OVERLOAD);
    record.markRunning();
    record.complete("firstResult");

    boolean secondCompleted = record.complete("secondResult");

    assertThat(secondCompleted).isFalse();
    assertThat(record.result()).hasValue("firstResult");
    assertThat(record.state()).isEqualTo(AsyncCallRecord.State.SUCCESS);
  }

  @Test
  public void fail_whenAlreadyCompleted_returnsFalseAndDoesNotOverwrite() {
    AsyncCallRecord record =
        AsyncCallRecord.create(1L, 10L, "myFunc", "myFunc_overload", new Object[0], DUMMY_OVERLOAD);
    record.markRunning();
    record.complete("firstResult");

    boolean failed = record.fail(new RuntimeException("subsequent failure"));

    assertThat(failed).isFalse();
    assertThat(record.result()).hasValue("firstResult");
    assertThat(record.error()).isEmpty();
    assertThat(record.state()).isEqualTo(AsyncCallRecord.State.SUCCESS);
  }

  @Test
  public void cancel_transitionsRunningToCancelled() {
    AsyncCallRecord record =
        AsyncCallRecord.create(1L, 10L, "myFunc", "myFunc_overload", new Object[0], DUMMY_OVERLOAD);
    record.markRunning();

    boolean cancelled = record.cancel();

    assertThat(cancelled).isTrue();
    assertThat(record.isCancelled()).isTrue();
    assertThat(record.state()).isEqualTo(AsyncCallRecord.State.CANCELLED);
  }

  @Test
  public void cancelInFlight_onSucceededRecord_doesNotTransitionToCancelled() {
    AsyncCallRecord record =
        AsyncCallRecord.create(1L, 10L, "myFunc", "myFunc_overload", new Object[0], DUMMY_OVERLOAD);
    record.markRunning();
    record.complete("success");

    boolean cancelled = record.cancelInFlight();

    assertThat(cancelled).isFalse();
    assertThat(record.isCancelled()).isFalse();
    assertThat(record.state()).isEqualTo(AsyncCallRecord.State.SUCCESS);
  }

  @Test
  public void cancelInFlight_onFailedRecord_doesNotTransitionToCancelled() {
    AsyncCallRecord record =
        AsyncCallRecord.create(1L, 10L, "myFunc", "myFunc_overload", new Object[0], DUMMY_OVERLOAD);
    record.markRunning();
    record.fail(new RuntimeException("failed"));

    boolean cancelled = record.cancelInFlight();

    assertThat(cancelled).isFalse();
    assertThat(record.isCancelled()).isFalse();
    assertThat(record.state()).isEqualTo(AsyncCallRecord.State.FAILURE);
  }

  @Test
  public void args_returnsDefensiveCopy() {
    AsyncCallRecord record =
        AsyncCallRecord.create(
            1L, 10L, "myFunc", "myFunc_overload", new Object[] {"original"}, DUMMY_OVERLOAD);

    Object[] returnedArgs = record.args();
    returnedArgs[0] = "mutated";

    assertThat(record.args()[0]).isEqualTo("original");
  }
}
