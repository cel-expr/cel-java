// Copyright 2024 Google LLC
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

import com.google.errorprone.annotations.Immutable;
import java.util.Arrays;
import java.util.Collection;
import java.util.Optional;

/**
 * Collection of {@link CelFunctionBinding} values which are intended to be created once
 * per-evaluation, rather than once per-program setup.
 */
@Immutable
public final class CelLateFunctionBindings implements CelFunctionResolver {

  private final DefaultDispatcher dispatcher;

  @Override
  public Optional<CelResolvedOverload> findOverloadMatchingArgs(
      String functionName, Collection<String> overloadIds, Object[] args)
      throws CelEvaluationException {
    return dispatcher.findOverloadMatchingArgs(functionName, overloadIds, args);
  }

  @Override
  public Optional<CelResolvedOverload> findOverloadMatchingArgs(String functionName, Object[] args)
      throws CelEvaluationException {
    return dispatcher.findOverloadMatchingArgs(functionName, args);
  }

  public static CelLateFunctionBindings from(CelFunctionBinding... functions) {
    return from(Arrays.asList(functions));
  }

  public static CelLateFunctionBindings from(Collection<CelFunctionBinding> functions) {
    DefaultDispatcher.Builder builder = DefaultDispatcher.newBuilder();
    for (CelFunctionBinding binding : functions) {
      String functionName =
          binding instanceof InternalCelFunctionBinding
              ? ((InternalCelFunctionBinding) binding).getFunctionName()
              : binding.getOverloadId();
      builder.addOverload(
          functionName,
          binding.getOverloadId(),
          binding.getArgTypes(),
          binding.isStrict(),
          binding.getDefinition());
    }
    return new CelLateFunctionBindings(builder.build());
  }

  private CelLateFunctionBindings(DefaultDispatcher dispatcher) {
    this.dispatcher = dispatcher;
  }
}
