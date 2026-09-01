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
import dev.cel.runtime.CelResolvedOverload;
import dev.cel.runtime.CelVariableResolver;
import dev.cel.runtime.GlobalResolver;
import dev.cel.runtime.InterpreterUtil;
import dev.cel.runtime.PartialVars;
import dev.cel.runtime.Program;
import dev.cel.runtime.RuntimeEquality;
import dev.cel.runtime.RuntimeHelpers;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
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

  PlannedProgram() {}

  private static final CelFunctionResolver EMPTY_FUNCTION_RESOLVER =
      new CelFunctionResolver() {
        @Override
        public Optional<CelResolvedOverload> findOverloadMatchingArgs(
            String functionName, Collection<String> overloadIds, Object[] args) {
          return Optional.empty();
        }

        @Override
        public Optional<CelResolvedOverload> findOverloadMatchingArgs(
            String functionName, Object[] args) {
          return Optional.empty();
        }
      };

  public abstract PlannedInterpretable interpretable();

  abstract ErrorMetadata metadata();

  public abstract CelOptions options();

  @Override
  public Object eval() throws CelEvaluationException {
    return evalOrThrow(
        interpretable(),
        GlobalResolver.EMPTY,
        EMPTY_FUNCTION_RESOLVER,
        /* partialVars= */ null,
        /* listener= */ null);
  }

  @Override
  public Object eval(Map<String, ?> mapValue) throws CelEvaluationException {
    return evalOrThrow(
        interpretable(),
        Activation.copyOf(mapValue),
        EMPTY_FUNCTION_RESOLVER,
        /* partialVars= */ null,
        /* listener= */ null);
  }

  @Override
  public Object eval(Map<String, ?> mapValue, CelFunctionResolver lateBoundFunctionResolver)
      throws CelEvaluationException {
    return evalOrThrow(
        interpretable(),
        Activation.copyOf(mapValue),
        lateBoundFunctionResolver,
        /* partialVars= */ null,
        /* listener= */ null);
  }

  @Override
  public Object eval(CelVariableResolver resolver) throws CelEvaluationException {
    return evalOrThrow(
        interpretable(),
        (name) -> resolver.find(name).orElse(null),
        EMPTY_FUNCTION_RESOLVER,
        /* partialVars= */ null,
        /* listener= */ null);
  }

  @Override
  public Object eval(CelVariableResolver resolver, CelFunctionResolver lateBoundFunctionResolver)
      throws CelEvaluationException {
    return evalOrThrow(
        interpretable(),
        (name) -> resolver.find(name).orElse(null),
        lateBoundFunctionResolver,
        /* partialVars= */ null,
        /* listener= */ null);
  }

  @Override
  public Object eval(PartialVars partialVars) throws CelEvaluationException {
    return evalOrThrow(
        interpretable(),
        (name) -> partialVars.resolver().find(name).orElse(null),
        EMPTY_FUNCTION_RESOLVER,
        partialVars,
        /* listener= */ null);
  }

  public Object evalOrThrow(
      PlannedInterpretable interpretable,
      GlobalResolver resolver,
      CelFunctionResolver functionResolver,
      @Nullable PartialVars partialVars,
      @Nullable CelEvaluationListener listener)
      throws CelEvaluationException {
    try {
      ExecutionFrame frame =
          ExecutionFrame.create(functionResolver, options(), partialVars, listener);
      Object evalResult = interpretable.eval(resolver, frame);
      if (evalResult instanceof ErrorValue) {
        ErrorValue errorValue = (ErrorValue) evalResult;
        throw newCelEvaluationException(errorValue.exprId(), errorValue.value());
      }

      return InterpreterUtil.maybeAdaptToCelUnknownSet(evalResult);
    } catch (RuntimeException e) {
      throw newCelEvaluationException(interpretable.expr().id(), e);
    }
  }

  public Object trace(
      GlobalResolver resolver,
      CelFunctionResolver functionResolver,
      PartialVars partialVars,
      CelEvaluationListener listener)
      throws CelEvaluationException {
    return evalOrThrow(interpretable(), resolver, functionResolver, partialVars, listener);
  }

  @Override
  public ListenableFuture<Object> evalAsync(
      GlobalResolver resolver,
      CelFunctionResolver lateBoundResolver,
      @Nullable PartialVars partialVars,
      ListeningExecutorService executor,
      CelAsyncEvaluationOptions asyncOptions) {
    SettableFuture<Object> resultFuture = SettableFuture.create();
    RuntimeEquality runtimeEquality = RuntimeEquality.create(RuntimeHelpers.create(), options());
    AsyncCallStateTracker tracker = new AsyncCallStateTracker(runtimeEquality);
    AsyncGate gate = new AsyncGate(asyncOptions.maxConcurrency());
    AsyncCompletionCoordinator coordinator =
        new AsyncCompletionCoordinator(asyncOptions, gate, executor);

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
            asyncOptions,
            tracker,
            gate,
            coordinator,
            executor,
            resultFuture);
    driver.scheduleNextStep();
    return resultFuture;
  }

  private final class AsyncDriver {
    private final PlannedInterpretable interpretable;
    private final GlobalResolver resolver;
    private final CelFunctionResolver lateBoundResolver;
    private final @Nullable PartialVars partialVars;
    private final CelAsyncEvaluationOptions options;
    private final AsyncCallStateTracker tracker;
    private final AsyncGate gate;
    private final AsyncCompletionCoordinator coordinator;
    private final ListeningExecutorService executor;
    private final SettableFuture<Object> resultFuture;
    private int iterationCount = 0;

    AsyncDriver(
        PlannedInterpretable interpretable,
        GlobalResolver resolver,
        CelFunctionResolver lateBoundResolver,
        @Nullable PartialVars partialVars,
        CelAsyncEvaluationOptions options,
        AsyncCallStateTracker tracker,
        AsyncGate gate,
        AsyncCompletionCoordinator coordinator,
        ListeningExecutorService executor,
        SettableFuture<Object> resultFuture) {
      this.interpretable = interpretable;
      this.resolver = resolver;
      this.lateBoundResolver = lateBoundResolver;
      this.partialVars = partialVars;
      this.options = options;
      this.tracker = tracker;
      this.gate = gate;
      this.coordinator = coordinator;
      this.executor = executor;
      this.resultFuture = resultFuture;
    }

    void scheduleNextStep() {
      if (resultFuture.isDone()) {
        return;
      }
      executor.execute(this::step);
    }

    private void step() {
      if (resultFuture.isDone()) {
        return;
      }

      if (options.maxIterations() >= 0 && ++iterationCount > options.maxIterations()) {
        cancelAll();
        resultFuture.setException(
            new CelEvaluationException(
                "Exceeded maximum async evaluation iterations: " + options.maxIterations()));
        return;
      }

      Object evalResult;
      try {
        ExecutionFrame frame =
            ExecutionFrame.createForAsync(
                lateBoundResolver,
                options(),
                partialVars,
                /* listener= */ null,
                tracker,
                gate,
                coordinator,
                executor,
                options.observer().orElse(null));
        evalResult = interpretable.eval(resolver, frame);
      } catch (Exception e) {
        cancelAll();
        resultFuture.setException(newCelEvaluationException(interpretable.expr().id(), e));
        return;
      }

      if (evalResult instanceof ErrorValue) {
        cancelAll();
        ErrorValue errorValue = (ErrorValue) evalResult;
        resultFuture.setException(
            newCelEvaluationException(errorValue.exprId(), errorValue.value()));
        return;
      }

      if (evalResult instanceof AccumulatedUnknowns) {
        AccumulatedUnknowns unknowns = (AccumulatedUnknowns) evalResult;
        if (!unknowns.hasCallIds()) {
          cancelAll();
          resultFuture.set(InterpreterUtil.maybeAdaptToCelUnknownSet(evalResult));
          return;
        }

        // Defensive fail-safe invariant: fail evaluation if unresolved async calls remain but
        // concurrency tracking indicates zero in-flight or queued work.
        if (gate.activeCount() == 0
            && !tracker.hasInFlightCalls()
            && !coordinator.hasPendingBatch()) {
          cancelAll();
          resultFuture.setException(
              new CelEvaluationException(
                  "Asynchronous evaluation stalled: unresolved async calls remain but no tasks are"
                      + " in-flight."));
          return;
        }

        coordinator.waitForCompletions(this::scheduleNextStep);
        return;
      }

      cancelAll();
      resultFuture.set(InterpreterUtil.maybeAdaptToCelUnknownSet(evalResult));
    }

    private void cancelAll() {
      gate.cancel();
      tracker.cancelInFlight();
    }
  }

  private CelEvaluationException newCelEvaluationException(long exprId, Exception e) {
    CelEvaluationExceptionBuilder builder;
    if (e instanceof LocalizedEvaluationException) {
      // Use the localized expr ID (most specific error location)
      LocalizedEvaluationException localized = (LocalizedEvaluationException) e;
      exprId = localized.exprId();
      Throwable cause = localized.getCause();
      if (cause instanceof CelRuntimeException) {
        builder =
            CelEvaluationExceptionBuilder.newBuilder((CelRuntimeException) localized.getCause());
      } else {
        builder = CelEvaluationExceptionBuilder.newBuilder(cause.getMessage()).setCause(cause);
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

  static PlannedProgram create(
      PlannedInterpretable interpretable, ErrorMetadata metadata, CelOptions options) {
    return new AutoValue_PlannedProgram(interpretable, metadata, options);
  }
}
