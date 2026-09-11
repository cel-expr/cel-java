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

package dev.cel.runtime;

import static com.google.common.truth.Truth.assertThat;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import org.jspecify.annotations.Nullable;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(TestParameterInjector.class)
public final class CelAsyncEvaluationOptionsTest {

  private ScheduledExecutorService customScheduler;

  @After
  public void tearDown() {
    if (customScheduler != null) {
      customScheduler.shutdown();
    }
  }

  @Test
  public void defaultOptions_returnsDefaultValues() {
    CelAsyncEvaluationOptions options = CelAsyncEvaluationOptions.defaultOptions();

    assertThat(options.maxConcurrency()).isEqualTo(100);
    assertThat(options.maxIterations()).isEqualTo(1_000);
    assertThat(options.drainStrategy()).isNotNull();
    assertThat(options.observer()).isEmpty();
    assertThat(options.scheduledExecutorService()).isEmpty();
    assertThat(options.resolveScheduledExecutorService()).isNotNull();
  }

  @Test
  public void resolveScheduledExecutorService_defaultScheduler_runsAsDaemonThread()
      throws Exception {
    CelAsyncEvaluationOptions options = CelAsyncEvaluationOptions.defaultOptions();

    Future<Boolean> isDaemonFuture =
        options.resolveScheduledExecutorService().submit(() -> Thread.currentThread().isDaemon());

    assertThat(isDaemonFuture.get(5, SECONDS)).isTrue();
  }

  @Test
  public void builder_validations() {
    CelAsyncEvaluationOptions.Builder builder = CelAsyncEvaluationOptions.builder();

    assertThrows(NullPointerException.class, () -> builder.setDrainStrategy(null));
    assertThrows(NullPointerException.class, () -> builder.setObserver(null));
    assertThrows(NullPointerException.class, () -> builder.setScheduledExecutorService(null));
  }

  @Test
  public void builder_nonPositiveMaxConcurrency_roundTripsCleanly() {
    CelAsyncEvaluationOptions unboundedZero =
        CelAsyncEvaluationOptions.builder().setMaxConcurrency(0).build();
    CelAsyncEvaluationOptions unboundedNegative =
        CelAsyncEvaluationOptions.builder().setMaxConcurrency(-1).build();

    assertThat(unboundedZero.maxConcurrency()).isEqualTo(0);
    assertThat(unboundedNegative.maxConcurrency()).isEqualTo(-1);
  }

  @Test
  public void builder_customValuesAndRoundTrip() {
    CelAsyncDrainStrategy drainStrategy = CelAsyncDrainStrategy.drainReady(Duration.ofMillis(25));
    CelAsyncObserver observer =
        new CelAsyncObserver() {
          @Override
          public void onCallStarted(CelAsyncCall call, ImmutableList<Object> args) {}

          @Override
          public void onCallFinished(
              CelAsyncCall call, @Nullable Object result, @Nullable Throwable error) {}
        };
    customScheduler = Executors.newSingleThreadScheduledExecutor();

    CelAsyncEvaluationOptions options =
        CelAsyncEvaluationOptions.builder()
            .setMaxConcurrency(8)
            .setMaxIterations(50)
            .setDrainStrategy(drainStrategy)
            .setObserver(observer)
            .setScheduledExecutorService(customScheduler)
            .build();

    assertThat(options.maxConcurrency()).isEqualTo(8);
    assertThat(options.maxIterations()).isEqualTo(50);
    assertThat(options.drainStrategy()).isSameInstanceAs(drainStrategy);
    assertThat(options.observer()).hasValue(observer);
    assertThat(options.scheduledExecutorService()).hasValue(customScheduler);
    assertThat(options.resolveScheduledExecutorService()).isSameInstanceAs(customScheduler);

    CelAsyncEvaluationOptions copy = options.toBuilder().build();
    assertThat(copy).isEqualTo(options);
  }
}
