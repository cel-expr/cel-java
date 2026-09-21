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
  private final PartialVars partialVars;
  private final @Nullable CelEvaluationListener listener;
  private int iterationCount;
  private BlockMemoizer blockMemoizer;

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
        functionResolver, celOptions.comprehensionMaxIterations(), partialVars, listener);
  }

  Optional<PartialVars> partialVars() {
    return Optional.ofNullable(partialVars);
  }

  ExecutionFrame withoutPartialVars() {
    if (this.partialVars == null) {
      return this;
    }
    ExecutionFrame frame =
        new ExecutionFrame(
            this.functionResolver, this.comprehensionIterationLimit, null, this.listener);
    frame.iterationCount = this.iterationCount;
    frame.blockMemoizer = this.blockMemoizer;
    return frame;
  }

  void syncIterations(ExecutionFrame other) {
    this.iterationCount = other.iterationCount;
  }

  @Nullable CelEvaluationListener getListener() {
    return listener;
  }

  private ExecutionFrame(
      CelFunctionResolver functionResolver,
      int limit,
      @Nullable PartialVars partialVars,
      @Nullable CelEvaluationListener listener) {
    this.comprehensionIterationLimit = limit;
    this.functionResolver = functionResolver;
    this.partialVars = partialVars;
    this.listener = listener;
  }
}
