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
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicLong;

/** Options for configuring asynchronous CEL evaluation. */
@AutoValue
@ThreadSafe
public abstract class CelAsyncEvaluationOptions {

  private static final int DEFAULT_MAX_CONCURRENCY = 100;
  private static final int DEFAULT_MAX_ITERATIONS = 1_000;

  /**
   * Maximum number of concurrent async function calls in-flight simultaneously. A value &lt;= 0
   * indicates unbounded concurrency.
   */
  public abstract int maxConcurrency();

  /** Strategy governing when to trigger re-evaluation after async call completions. */
  public abstract CelAsyncDrainStrategy drainStrategy();

  /** Safety cap on the maximum number of AST re-evaluation passes before aborting. */
  public abstract int maxIterations();

  /**
   * Returns the custom configured {@link ScheduledExecutorService}, if present.
   *
   * <p>If absent, {@link #resolveScheduledExecutorService()} falls back to an internal, shared
   * single-threaded daemon scheduler.
   */
  public abstract Optional<ScheduledExecutorService> scheduledExecutorService();

  /** Returns the configured lifecycle observer, if present. */
  public abstract Optional<CelAsyncObserver> observer();

  /**
   * Resolves the {@link ScheduledExecutorService} used for debounce timers, falling back to a
   * shared, lazily initialized single-threaded daemon scheduler (named {@code
   * cel-async-debounce-*}) if not custom-configured.
   *
   * <p>The scheduler is used exclusively as an alarm clock to trigger continuation wakeups; it does
   * not execute CEL evaluation tasks.
   */
  public ScheduledExecutorService resolveScheduledExecutorService() {
    return scheduledExecutorService().orElse(DefaultDebounceSchedulerHolder.INSTANCE);
  }

  public abstract Builder toBuilder();

  /**
   * Returns a new {@link Builder} initialized with standard default options:
   *
   * <ul>
   *   <li>Maximum concurrency: 100 in-flight calls
   *   <li>Maximum iterations: 1,000 evaluation passes
   *   <li>Drain strategy: {@link CelAsyncDrainStrategy#drainReady()} (100-microsecond debounce
   *       window)
   *   <li>Scheduled executor service: A shared, lazily initialized single-threaded daemon scheduler
   *       used exclusively for debounce timer wakeups.
   * </ul>
   */
  public static Builder newBuilder() {
    return new AutoValue_CelAsyncEvaluationOptions.Builder()
        .setMaxConcurrency(DEFAULT_MAX_CONCURRENCY)
        .setDrainStrategy(CelAsyncDrainStrategy.drainReady())
        .setMaxIterations(DEFAULT_MAX_ITERATIONS);
  }

  /**
   * Returns a new {@link Builder} initialized with standard default options.
   *
   * <p>Equivalent to calling {@link #newBuilder()}.
   */
  public static Builder builder() {
    return newBuilder();
  }

  /**
   * Returns a {@link CelAsyncEvaluationOptions} instance with the {@link #newBuilder() default
   * configuration}.
   */
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

    /**
     * Sets a custom {@link ScheduledExecutorService} for debounce timers.
     *
     * <p>If not set, defaults to an internal, shared single-threaded daemon scheduler.
     */
    public abstract Builder setScheduledExecutorService(
        ScheduledExecutorService scheduledExecutorService);

    public abstract Builder setObserver(CelAsyncObserver observer);

    public abstract CelAsyncEvaluationOptions build();
  }

  // Package-private constructor prevents extension outside package while allowing AutoValue.
  CelAsyncEvaluationOptions() {}
}
