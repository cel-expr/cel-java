// Copyright 2025 Google LLC
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

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;

import com.google.auto.value.AutoValue;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.SettableFuture;
import com.google.errorprone.annotations.Immutable;
import dev.cel.common.CelOptions;
import dev.cel.common.annotations.Internal;
import dev.cel.common.exceptions.CelRuntimeException;
import dev.cel.common.values.ErrorValue;
import dev.cel.runtime.AccumulatedUnknowns;
import dev.cel.runtime.Activation;
import dev.cel.runtime.CelAsyncEvaluationOptions;
import dev.cel.runtime.CelEvaluationException;
import dev.cel.runtime.CelEvaluationExceptionBuilder;
import dev.cel.runtime.CelEvaluationListener;
import dev.cel.runtime.CelFunctionResolver;
import dev.cel.runtime.CelVariableResolver;
import dev.cel.runtime.GlobalResolver;
import dev.cel.runtime.InterpreterUtil;
import dev.cel.runtime.PartialVars;
import dev.cel.runtime.Program;
import dev.cel.runtime.RuntimeEquality;
import dev.cel.runtime.planner.AsyncCompletionCoordinator.WaitResult;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;

/**
 * Internal implementation of a {@link Program} that executes a planned interpretable tree.
 *
 * <p>CEL-Java internals. Do not use.
 */
@Internal
@Immutable
@AutoValue
public abstract class PlannedProgram implements Program {

  abstract PlannedInterpretable interpretable();

  abstract ErrorMetadata metadata();

  public abstract CelOptions options();

  abstract RuntimeEquality runtimeEquality();

  // CelAsyncEvaluationOptions is an immutable value object.
  @SuppressWarnings("Immutable")
  @AutoValue.CopyAnnotations
  abstract CelAsyncEvaluationOptions asyncOptions();

  // The executor service is an externally managed, thread-safe asynchronous execution pool.
  @SuppressWarnings("Immutable")
  @AutoValue.CopyAnnotations
  abstract Optional<ListeningExecutorService> asyncExecutor();

  static PlannedProgram create(
      PlannedInterpretable interpretable,
      ErrorMetadata metadata,
      CelOptions options,
      RuntimeEquality runtimeEquality,
      CelAsyncEvaluationOptions asyncOptions,
      @Nullable ListeningExecutorService asyncExecutor) {
    return new AutoValue_PlannedProgram(
        interpretable,
        metadata,
        options,
        runtimeEquality,
        asyncOptions,
        Optional.ofNullable(asyncExecutor));
  }

  @Override
  public Object eval() throws CelEvaluationException {
    return evalOrThrow(
        GlobalResolver.EMPTY,
        CelFunctionResolver.EMPTY,
        /* partialVars= */ null,
        /* listener= */ null);
  }

  @Override
  public Object eval(Map<String, ?> mapValue) throws CelEvaluationException {
    return evalOrThrow(
        Activation.copyOf(mapValue),
        CelFunctionResolver.EMPTY,
        /* partialVars= */ null,
        /* listener= */ null);
  }

  @Override
  public Object eval(Map<String, ?> mapValue, CelFunctionResolver lateBoundFunctionResolver)
      throws CelEvaluationException {
    return evalOrThrow(
        Activation.copyOf(mapValue),
        lateBoundFunctionResolver,
        /* partialVars= */ null,
        /* listener= */ null);
  }

  @Override
  public Object eval(CelVariableResolver resolver) throws CelEvaluationException {
    return evalOrThrow(
        (name) -> resolver.find(name).orElse(null),
        CelFunctionResolver.EMPTY,
        /* partialVars= */ null,
        /* listener= */ null);
  }

  @Override
  public Object eval(CelVariableResolver resolver, CelFunctionResolver lateBoundFunctionResolver)
      throws CelEvaluationException {
    return evalOrThrow(
        (name) -> resolver.find(name).orElse(null),
        lateBoundFunctionResolver,
        /* partialVars= */ null,
        /* listener= */ null);
  }

  @Override
  public Object eval(PartialVars partialVars) throws CelEvaluationException {
    return evalOrThrow(
        (name) -> partialVars.resolver().find(name).orElse(null),
        CelFunctionResolver.EMPTY,
        partialVars,
        /* listener= */ null);
  }

  @Override
  public ListenableFuture<Object> evalAsync() {
    return evalAsync(GlobalResolver.EMPTY, CelFunctionResolver.EMPTY, /* partialVars= */ null);
  }

  @Override
  public ListenableFuture<Object> evalAsync(Map<String, ?> mapValue) {
    checkNotNull(mapValue, "mapValue");
    return evalAsync(
        Activation.copyOf(mapValue), CelFunctionResolver.EMPTY, /* partialVars= */ null);
  }

  @Override
  public ListenableFuture<Object> evalAsync(
      Map<String, ?> mapValue, CelFunctionResolver lateBoundFunctionResolver) {
    checkNotNull(mapValue, "mapValue");
    checkNotNull(lateBoundFunctionResolver, "lateBoundFunctionResolver");
    return evalAsync(
        Activation.copyOf(mapValue), lateBoundFunctionResolver, /* partialVars= */ null);
  }

  @Override
  public ListenableFuture<Object> evalAsync(CelVariableResolver resolver) {
    checkNotNull(resolver, "resolver");
    return evalAsync(
        (name) -> resolver.find(name).orElse(null),
        CelFunctionResolver.EMPTY,
        /* partialVars= */ null);
  }

  @Override
  public ListenableFuture<Object> evalAsync(
      CelVariableResolver resolver, CelFunctionResolver lateBoundFunctionResolver) {
    checkNotNull(resolver, "resolver");
    checkNotNull(lateBoundFunctionResolver, "lateBoundFunctionResolver");
    return evalAsync(
        (name) -> resolver.find(name).orElse(null),
        lateBoundFunctionResolver,
        /* partialVars= */ null);
  }

  @Override
  public ListenableFuture<Object> evalAsync(PartialVars partialVars) {
    checkNotNull(partialVars, "partialVars");
    return evalAsync(
        (name) -> partialVars.resolver().find(name).orElse(null),
        CelFunctionResolver.EMPTY,
        partialVars);
  }

  public ListenableFuture<Object> evalAsync(
      GlobalResolver resolver,
      CelFunctionResolver lateBoundResolver,
      @Nullable PartialVars partialVars) {
    checkNotNull(resolver, "resolver");
    checkNotNull(lateBoundResolver, "lateBoundResolver");
    ListeningExecutorService effectiveExecutor =
        asyncExecutor()
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "No async executor was configured for evalAsync. You must provide a"
                            + " ListeningExecutorService when configuring the CelRuntime (via"
                            + " setAsyncExecutor)."));

    SettableFuture<Object> resultFuture = SettableFuture.create();
    AsyncCallStateTracker tracker = AsyncCallStateTracker.create(runtimeEquality());
    AsyncGate gate = AsyncGate.create(asyncOptions().maxConcurrency());
    AsyncCompletionCoordinator coordinator =
        AsyncCompletionCoordinator.create(
            asyncOptions(),
            gate,
            effectiveExecutor,
            t -> {
              tracker.cancelInFlight();
              resultFuture.setException(newCelEvaluationException(interpretable().expr().id(), t));
            });

    resultFuture.addListener(
        () -> {
          if (resultFuture.isCancelled()) {
            gate.cancel();
            coordinator.cancel();
            tracker.cancelInFlight();
          }
        },
        directExecutor());

    AsyncDriver driver =
        new AsyncDriver(
            interpretable(),
            resolver,
            lateBoundResolver,
            partialVars,
            asyncOptions(),
            tracker,
            gate,
            coordinator,
            effectiveExecutor,
            resultFuture);
    driver.step();
    return resultFuture;
  }

  public Object evalOrThrow(
      GlobalResolver resolver,
      CelFunctionResolver functionResolver,
      @Nullable PartialVars partialVars,
      @Nullable CelEvaluationListener listener)
      throws CelEvaluationException {
    try {
      ExecutionFrame frame =
          ExecutionFrame.create(functionResolver, options(), partialVars, listener);
      Object evalResult = interpretable().eval(resolver, frame);
      if (evalResult instanceof ErrorValue) {
        ErrorValue errorValue = (ErrorValue) evalResult;
        throw newCelEvaluationException(errorValue.exprId(), errorValue.value());
      }

      return InterpreterUtil.maybeAdaptToCelUnknownSet(evalResult);
    } catch (RuntimeException e) {
      throw newCelEvaluationException(interpretable().expr().id(), e);
    }
  }

  public Object trace(
      GlobalResolver resolver,
      CelFunctionResolver functionResolver,
      @Nullable PartialVars partialVars,
      @Nullable CelEvaluationListener listener)
      throws CelEvaluationException {
    return evalOrThrow(resolver, functionResolver, partialVars, listener);
  }

  private CelEvaluationException newCelEvaluationException(long exprId, Throwable e) {
    if (e instanceof CelEvaluationException) {
      return (CelEvaluationException) e;
    }
    CelEvaluationExceptionBuilder builder;
    if (e instanceof LocalizedEvaluationException) {
      // Use the localized expr ID (most specific error location)
      LocalizedEvaluationException localized = (LocalizedEvaluationException) e;
      exprId = localized.exprId();
      Throwable cause = localized.getCause();
      if (cause instanceof CelRuntimeException) {
        builder = CelEvaluationExceptionBuilder.newBuilder((CelRuntimeException) cause);
      } else {
        Throwable innerCause = cause.getCause() != null ? cause.getCause() : cause;
        builder = CelEvaluationExceptionBuilder.newBuilder(cause.getMessage()).setCause(innerCause);
      }
    } else if (e instanceof CelRuntimeException) {
      builder = CelEvaluationExceptionBuilder.newBuilder((CelRuntimeException) e);
    } else {
      // Unhandled function dispatch failures wraps the original exception with a descriptive
      // message
      // (e.g: "Function foo failed with...")
      // We need to unwrap the cause here to preserve the original exception message and its cause.
      Throwable cause = e.getCause() != null ? e.getCause() : e;
      builder = CelEvaluationExceptionBuilder.newBuilder(e.getMessage()).setCause(cause);
    }

    return builder.setMetadata(metadata(), exprId).build();
  }

  private final class AsyncDriver {
    private final PlannedInterpretable interpretable;
    private final GlobalResolver resolver;
    private final CelFunctionResolver lateBoundResolver;
    private final @Nullable PartialVars partialVars;
    private final CelAsyncEvaluationOptions asyncOptions;
    private final AsyncCallStateTracker tracker;
    private final AsyncGate gate;
    private final AsyncCompletionCoordinator coordinator;
    private final ListeningExecutorService executor;
    private final SettableFuture<Object> resultFuture;
    private final AtomicInteger iterationCount = new AtomicInteger(0);

    private void step() {
      while (!resultFuture.isDone()) {
        if (asyncOptions.maxIterations() >= 0
            && iterationCount.incrementAndGet() > asyncOptions.maxIterations()) {
          tracker.cancelInFlight();
          resultFuture.setException(
              new CelEvaluationException(
                  "Exceeded maximum async evaluation iterations: " + asyncOptions.maxIterations()));
          return;
        }

        Object evalResult;
        try {
          ExecutionFrame frame =
              ExecutionFrame.createForAsync(
                  lateBoundResolver, options(), partialVars, /* listener= */ null, tracker);
          evalResult = interpretable.eval(resolver, frame);
        } catch (Throwable t) {
          tracker.cancelInFlight();
          resultFuture.setException(newCelEvaluationException(interpretable.expr().id(), t));
          return;
        }

        if (evalResult instanceof ErrorValue) {
          tracker.cancelInFlight();
          ErrorValue errorValue = (ErrorValue) evalResult;
          resultFuture.setException(
              newCelEvaluationException(errorValue.exprId(), errorValue.value()));
          return;
        }

        if (evalResult instanceof AccumulatedUnknowns) {
          AccumulatedUnknowns unknowns = (AccumulatedUnknowns) evalResult;
          if (unknowns.callIds().isEmpty()) {
            tracker.cancelInFlight();
            resultFuture.set(InterpreterUtil.maybeAdaptToCelUnknownSet(evalResult));
            return;
          }

          tracker.dispatchPendingCalls(
              unknowns.callIds(),
              executor,
              gate,
              coordinator,
              asyncOptions.observer().orElse(null));

          WaitResult waitResult = coordinator.waitForCompletions(this::step);
          switch (waitResult) {
            case REEVALUATE_NOW:
              continue;
            case NO_OUTSTANDING_WORK:
              tracker.cancelInFlight();
              resultFuture.setException(
                  new CelEvaluationException(
                      "Asynchronous evaluation stalled: unresolved async calls remain but no tasks"
                          + " are in-flight."));
              return;
            case REGISTERED:
            case CANCELLED:
              return;
          }
        }

        tracker.cancelInFlight();
        resultFuture.set(InterpreterUtil.maybeAdaptToCelUnknownSet(evalResult));
        return;
      }
    }

    private AsyncDriver(
        PlannedInterpretable interpretable,
        GlobalResolver resolver,
        CelFunctionResolver lateBoundResolver,
        @Nullable PartialVars partialVars,
        CelAsyncEvaluationOptions asyncOptions,
        AsyncCallStateTracker tracker,
        AsyncGate gate,
        AsyncCompletionCoordinator coordinator,
        ListeningExecutorService executor,
        SettableFuture<Object> resultFuture) {
      this.interpretable = interpretable;
      this.resolver = resolver;
      this.lateBoundResolver = lateBoundResolver;
      this.partialVars = partialVars;
      this.asyncOptions = asyncOptions;
      this.tracker = tracker;
      this.gate = gate;
      this.coordinator = coordinator;
      this.executor = executor;
      this.resultFuture = resultFuture;
    }
  }

  PlannedProgram() {}
}
