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

import com.google.auto.value.AutoValue;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.errorprone.annotations.Immutable;
import dev.cel.common.CelOptions;
import dev.cel.common.annotations.Internal;
import dev.cel.common.exceptions.CelRuntimeException;
import dev.cel.common.values.ErrorValue;
import dev.cel.runtime.Activation;
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

  @Override
  public ListenableFuture<Object> evalAsync() {
    throw new UnsupportedOperationException("evalAsync is not supported by PlannedProgram.");
  }

  @Override
  public ListenableFuture<Object> evalAsync(Map<String, ?> mapValue) {
    throw new UnsupportedOperationException("evalAsync is not supported by PlannedProgram.");
  }

  @Override
  public ListenableFuture<Object> evalAsync(
      Map<String, ?> mapValue, CelFunctionResolver lateBoundFunctionResolver) {
    throw new UnsupportedOperationException("evalAsync is not supported by PlannedProgram.");
  }

  @Override
  public ListenableFuture<Object> evalAsync(CelVariableResolver resolver) {
    throw new UnsupportedOperationException("evalAsync is not supported by PlannedProgram.");
  }

  @Override
  public ListenableFuture<Object> evalAsync(
      CelVariableResolver resolver, CelFunctionResolver lateBoundFunctionResolver) {
    throw new UnsupportedOperationException("evalAsync is not supported by PlannedProgram.");
  }

  @Override
  public ListenableFuture<Object> evalAsync(PartialVars partialVars) {
    throw new UnsupportedOperationException("evalAsync is not supported by PlannedProgram.");
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

  static Program create(
      PlannedInterpretable interpretable, ErrorMetadata metadata, CelOptions options) {
    return new AutoValue_PlannedProgram(interpretable, metadata, options);
  }
}
