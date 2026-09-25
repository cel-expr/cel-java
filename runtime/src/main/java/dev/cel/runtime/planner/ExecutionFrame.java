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
import static com.google.common.base.Preconditions.checkState;

import dev.cel.common.CelOptions;
import dev.cel.common.exceptions.CelIterationLimitExceededException;
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
  private int iterationCount;
  private @Nullable BlockMemoizer blockMemoizer;

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
        /* asyncTracker= */ null);
  }

  static ExecutionFrame createForAsync(
      CelFunctionResolver functionResolver,
      CelOptions celOptions,
      @Nullable PartialVars partialVars,
      @Nullable CelEvaluationListener listener,
      AsyncCallStateTracker asyncTracker) {
    checkNotNull(asyncTracker, "asyncTracker");
    return new ExecutionFrame(
        functionResolver,
        celOptions.comprehensionMaxIterations(),
        partialVars,
        listener,
        asyncTracker);
  }

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

  boolean isAsync() {
    return asyncTracker != null;
  }

  AsyncCallStateTracker asyncTracker() {
    checkState(asyncTracker != null, "Not in async execution mode");
    return asyncTracker;
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
      @Nullable AsyncCallStateTracker asyncTracker) {
    this.comprehensionIterationLimit = limit;
    this.functionResolver = functionResolver;
    this.partialVars = partialVars;
    this.listener = listener;
    this.asyncTracker = asyncTracker;
  }
}
