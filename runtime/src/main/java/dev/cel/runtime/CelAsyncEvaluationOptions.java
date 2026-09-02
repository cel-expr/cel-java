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

import com.google.auto.value.AutoValue;
import javax.annotation.concurrent.ThreadSafe;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;

/** Options for configuring asynchronous CEL evaluation. */
@AutoValue
@ThreadSafe
public abstract class CelAsyncEvaluationOptions {

  CelAsyncEvaluationOptions() {}

  public static final int DEFAULT_MAX_CONCURRENCY = 100;
  public static final int DEFAULT_MAX_ITERATIONS = 1_000;

  /**
   * Maximum number of concurrent async function calls in-flight simultaneously. A value &lt;= 0
   * indicates unbounded concurrency.
   */
  public abstract int maxConcurrency();

  /** Strategy governing when to trigger re-evaluation after async call completions. */
  public abstract CelAsyncDrainStrategy drainStrategy();

  /** Safety cap on the maximum number of AST re-evaluation passes before aborting. */
  public abstract int maxIterations();

  abstract @Nullable ScheduledExecutorService customScheduledExecutorService();

  abstract @Nullable CelAsyncObserver customObserver();

  /** Returns the configured lifecycle observer, if present. */
  public Optional<CelAsyncObserver> observer() {
    return Optional.ofNullable(customObserver());
  }

  /**
   * Resolves the {@link ScheduledExecutorService} used for debounce timers, falling back to the
   * shared daemon scheduler if not custom-configured.
   */
  public ScheduledExecutorService resolveScheduledExecutorService() {
    ScheduledExecutorService custom = customScheduledExecutorService();
    return custom != null ? custom : DefaultDebounceSchedulerHolder.INSTANCE;
  }

  public abstract Builder toBuilder();

  public static Builder newBuilder() {
    return new AutoValue_CelAsyncEvaluationOptions.Builder()
        .setMaxConcurrency(DEFAULT_MAX_CONCURRENCY)
        .setDrainStrategy(CelAsyncDrainStrategy.drainReady())
        .setMaxIterations(DEFAULT_MAX_ITERATIONS);
  }

  public static Builder builder() {
    return newBuilder();
  }

  public static CelAsyncEvaluationOptions defaultOptions() {
    return newBuilder().build();
  }

  private static final class DefaultDebounceSchedulerHolder {
    private static final AtomicLong counter = new AtomicLong();
    private static final ScheduledExecutorService INSTANCE =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r);
              t.setName("cel-async-debounce-" + counter.getAndIncrement());
              t.setDaemon(true);
              return t;
            });
  }

  /** Builder for {@link CelAsyncEvaluationOptions}. */
  @AutoValue.Builder
  public abstract static class Builder {
    public abstract Builder setMaxConcurrency(int maxConcurrency);

    public abstract Builder setDrainStrategy(CelAsyncDrainStrategy drainStrategy);

    public abstract Builder setMaxIterations(int maxIterations);

    abstract Builder setCustomScheduledExecutorService(
        @Nullable ScheduledExecutorService scheduledExecutorService);

    abstract Builder setCustomObserver(@Nullable CelAsyncObserver observer);

    public Builder setScheduledExecutorService(ScheduledExecutorService scheduledExecutorService) {
      return setCustomScheduledExecutorService(Objects.requireNonNull(scheduledExecutorService));
    }

    public Builder setObserver(CelAsyncObserver observer) {
      return setCustomObserver(Objects.requireNonNull(observer));
    }

    public abstract CelAsyncEvaluationOptions build();
  }
}
