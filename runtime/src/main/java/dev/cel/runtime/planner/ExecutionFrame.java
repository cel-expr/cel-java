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

import static com.google.common.base.Preconditions.checkState;

import com.google.common.util.concurrent.ListeningExecutorService;
import dev.cel.common.CelOptions;
import dev.cel.common.exceptions.CelIterationLimitExceededException;
import dev.cel.runtime.CelAsyncObserver;
import dev.cel.runtime.CelEvaluationException;
import dev.cel.runtime.CelEvaluationListener;
import dev.cel.runtime.CelFunctionResolver;
import dev.cel.runtime.CelResolvedOverload;
import dev.cel.runtime.PartialVars;
import java.util.Collection;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** Tracks execution context within a planned program. */
final class ExecutionFrame {

  private final int comprehensionIterationLimit;
  private final CelFunctionResolver functionResolver;
  private final @Nullable PartialVars partialVars;
  private final @Nullable CelEvaluationListener listener;
  private final @Nullable AsyncCallStateTracker asyncTracker;
  private final @Nullable AsyncGate asyncGate;
  private final @Nullable AsyncCompletionCoordinator asyncCoordinator;
  private final @Nullable ListeningExecutorService asyncExecutor;
  private final @Nullable CelAsyncObserver asyncObserver;
  private int iterationCount;
  private @Nullable BlockMemoizer blockMemoizer;

  Optional<CelResolvedOverload> findOverload(
      String functionName, Collection<String> overloadIds, Object[] args)
      throws CelEvaluationException {
    if (overloadIds.isEmpty()) {
      return functionResolver.findOverloadMatchingArgs(functionName, args);
    }
    return functionResolver.findOverloadMatchingArgs(functionName, overloadIds, args);
  }

  void incrementIterations() {
    if (comprehensionIterationLimit < 0) {
      return;
    }
    if (++iterationCount > comprehensionIterationLimit) {
      throw new CelIterationLimitExceededException(comprehensionIterationLimit);
    }
  }

  void setBlockMemoizer(BlockMemoizer blockMemoizer) {
    if (this.blockMemoizer != null) {
      throw new IllegalStateException("BlockMemoizer is already initialized");
    }
    this.blockMemoizer = blockMemoizer;
  }

  BlockMemoizer getBlockMemoizer() {
    return blockMemoizer;
  }

  static ExecutionFrame create(
      CelFunctionResolver functionResolver,
      CelOptions celOptions,
      @Nullable PartialVars partialVars,
      @Nullable CelEvaluationListener listener) {
    return new ExecutionFrame(
        functionResolver,
        celOptions.comprehensionMaxIterations(),
        partialVars,
        listener,
        /* asyncTracker= */ null,
        /* asyncGate= */ null,
        /* asyncCoordinator= */ null,
        /* asyncExecutor= */ null,
        /* asyncObserver= */ null);
  }

  static ExecutionFrame createForAsync(
      CelFunctionResolver functionResolver,
      CelOptions celOptions,
      @Nullable PartialVars partialVars,
      @Nullable CelEvaluationListener listener,
      AsyncCallStateTracker asyncTracker,
      AsyncGate asyncGate,
      AsyncCompletionCoordinator asyncCoordinator,
      ListeningExecutorService asyncExecutor,
      @Nullable CelAsyncObserver asyncObserver) {
    return new ExecutionFrame(
        functionResolver,
        celOptions.comprehensionMaxIterations(),
        partialVars,
        listener,
        asyncTracker,
        asyncGate,
        asyncCoordinator,
        asyncExecutor,
        asyncObserver);
  }

  boolean isAsync() {
    return asyncTracker != null;
  }

  AsyncCallStateTracker asyncTracker() {
    checkState(asyncTracker != null, "Not in async execution mode");
    return asyncTracker;
  }

  AsyncGate asyncGate() {
    checkState(asyncGate != null, "Not in async execution mode");
    return asyncGate;
  }

  AsyncCompletionCoordinator asyncCoordinator() {
    checkState(asyncCoordinator != null, "Not in async execution mode");
    return asyncCoordinator;
  }

  ListeningExecutorService asyncExecutor() {
    checkState(asyncExecutor != null, "Not in async execution mode");
    return asyncExecutor;
  }

  Optional<CelAsyncObserver> asyncObserver() {
    return Optional.ofNullable(asyncObserver);
  }

  Optional<PartialVars> partialVars() {
    return Optional.ofNullable(partialVars);
  }

  @Nullable CelEvaluationListener getListener() {
    return listener;
  }

  private ExecutionFrame(
      CelFunctionResolver functionResolver,
      int limit,
      @Nullable PartialVars partialVars,
      @Nullable CelEvaluationListener listener,
      @Nullable AsyncCallStateTracker asyncTracker,
      @Nullable AsyncGate asyncGate,
      @Nullable AsyncCompletionCoordinator asyncCoordinator,
      @Nullable ListeningExecutorService asyncExecutor,
      @Nullable CelAsyncObserver asyncObserver) {
    this.comprehensionIterationLimit = limit;
    this.functionResolver = functionResolver;
    this.partialVars = partialVars;
    this.listener = listener;
    this.asyncTracker = asyncTracker;
    this.asyncGate = asyncGate;
    this.asyncCoordinator = asyncCoordinator;
    this.asyncExecutor = asyncExecutor;
    this.asyncObserver = asyncObserver;
  }
}
