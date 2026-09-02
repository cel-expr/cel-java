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

import com.google.common.util.concurrent.SettableFuture;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import java.time.Duration;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(TestParameterInjector.class)
public final class AsyncCallRecordTest {

  @Test
  public void initialValues_matchConstructor() {
    AsyncCallRecord record =
        new AsyncCallRecord(1L, 10L, "myFunc", "myFunc_overload", new Object[] {"arg1", 2});

    assertThat(record.callId()).isEqualTo(1L);
    assertThat(record.exprId()).isEqualTo(10L);
    assertThat(record.functionName()).isEqualTo("myFunc");
    assertThat(record.overloadId()).isEqualTo("myFunc_overload");
    assertThat(record.arguments()).containsExactly("arg1", 2).inOrder();
    assertThat(record.state()).isEqualTo(AsyncCallRecord.State.RUNNING);
    assertThat(record.isCancelled()).isFalse();
    assertThat(record.result()).isNull();
    assertThat(record.error()).isNull();
    assertThat(record.elapsedDuration()).isEqualTo(Duration.ZERO);
  }

  @Test
  public void complete_updatesStateAndResultAndElapsed() {
    AsyncCallRecord record =
        new AsyncCallRecord(1L, 10L, "myFunc", "myFunc_overload", new Object[] {});
    Duration elapsed = Duration.ofMillis(123);

    record.complete("successResult", elapsed);

    assertThat(record.state()).isEqualTo(AsyncCallRecord.State.SUCCESS);
    assertThat(record.result()).isEqualTo("successResult");
    assertThat(record.error()).isNull();
    assertThat(record.elapsedDuration()).isEqualTo(elapsed);
  }

  @Test
  public void fail_updatesStateAndErrorAndElapsed() {
    AsyncCallRecord record =
        new AsyncCallRecord(1L, 10L, "myFunc", "myFunc_overload", new Object[] {});
    Duration elapsed = Duration.ofMillis(456);
    RuntimeException error = new RuntimeException("test error");

    record.fail(error, elapsed);

    assertThat(record.state()).isEqualTo(AsyncCallRecord.State.FAILURE);
    assertThat(record.error()).isSameInstanceAs(error);
    assertThat(record.result()).isNull();
    assertThat(record.elapsedDuration()).isEqualTo(elapsed);
  }

  @Test
  public void cancelInFlight_cancelsFutureAndSetsFlag() {
    AsyncCallRecord record =
        new AsyncCallRecord(1L, 10L, "myFunc", "myFunc_overload", new Object[] {});
    SettableFuture<String> future = SettableFuture.create();
    record.setInFlightFuture(future);

    record.cancelInFlight();

    assertThat(record.isCancelled()).isTrue();
    assertThat(future.isCancelled()).isTrue();
  }

  @Test
  public void setInFlightFuture_afterCancelled_cancelsImmediately() {
    AsyncCallRecord record =
        new AsyncCallRecord(1L, 10L, "myFunc", "myFunc_overload", new Object[] {});
    record.cancelInFlight();

    SettableFuture<String> future = SettableFuture.create();
    record.setInFlightFuture(future);

    assertThat(future.isCancelled()).isTrue();
  }
}
