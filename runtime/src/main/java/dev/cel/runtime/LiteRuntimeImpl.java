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

package dev.cel.runtime;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import javax.annotation.concurrent.ThreadSafe;
import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.CelContainer;
import dev.cel.common.CelOptions;
import dev.cel.common.types.CelTypeProvider;
import dev.cel.common.types.DefaultTypeProvider;
import dev.cel.common.values.CelValueConverter;
import dev.cel.common.values.CelValueProvider;
import dev.cel.runtime.planner.ProgramPlanner;
import dev.cel.runtime.standard.CelStandardFunction;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@ThreadSafe
final class LiteRuntimeImpl implements CelLiteRuntime {
  private final ProgramPlanner planner;
  private final CelOptions celOptions;
  private final ImmutableList<CelFunctionBinding> customFunctionBindings;
  private final ImmutableSet<CelStandardFunction> celStandardFunctions;
  private final CelValueProvider celValueProvider;
  private final CelTypeProvider celTypeProvider;
  private final CelContainer celContainer;

  // CEL-Internal-4
  private final ImmutableSet<CelLiteRuntimeLibrary> runtimeLibraries;

  @Override
  public Program createProgram(CelAbstractSyntaxTree ast) throws CelEvaluationException {
    return planner.plan(ast);
  }

  @Override
  public CelLiteRuntimeBuilder toRuntimeBuilder() {
    CelLiteRuntimeBuilder builder =
        new Builder()
            .setOptions(celOptions)
            .setStandardFunctions(celStandardFunctions)
            .addFunctionBindings(customFunctionBindings)
            .addLibraries(runtimeLibraries)
            .setContainer(celContainer);

    if (celValueProvider != null) {
      builder.setValueProvider(celValueProvider);
    }

    if (celTypeProvider != null) {
      builder.setTypeProvider(celTypeProvider);
    }

    return builder;
  }

  static final class Builder implements CelLiteRuntimeBuilder {

    // Following is visible to test `toBuilder`.
    @VisibleForTesting CelOptions celOptions;
    @VisibleForTesting final HashMap<String, CelFunctionBinding> customFunctionBindings;
    @VisibleForTesting final ImmutableSet.Builder<CelLiteRuntimeLibrary> runtimeLibrariesBuilder;
    @VisibleForTesting final ImmutableSet.Builder<CelStandardFunction> standardFunctionBuilder;
    @VisibleForTesting CelValueProvider celValueProvider;
    private CelContainer container;
    @VisibleForTesting final ImmutableSet.Builder<String> lateBoundFunctionNamesBuilder;
    @VisibleForTesting CelTypeProvider celTypeProvider;

    @Override
    public CelLiteRuntimeBuilder setOptions(CelOptions celOptions) {
      Preconditions.checkArgument(
          celOptions.enableUnsignedLongs(),
          "CelLiteRuntime requires CelOptions.enableUnsignedLongs(true).");
      Preconditions.checkArgument(
          celOptions.unwrapWellKnownTypesOnFunctionDispatch(),
          "CelLiteRuntime requires CelOptions.unwrapWellKnownTypesOnFunctionDispatch(true).");
      this.celOptions = celOptions;
      return this;
    }

    @Override
    public CelLiteRuntimeBuilder setStandardFunctions(CelStandardFunction... standardFunctions) {
      return setStandardFunctions(Arrays.asList(standardFunctions));
    }

    @Override
    public CelLiteRuntimeBuilder setStandardFunctions(
        Iterable<? extends CelStandardFunction> standardFunctions) {
      standardFunctionBuilder.addAll(standardFunctions);
      return this;
    }

    @Override
    public CelLiteRuntimeBuilder addFunctionBindings(CelFunctionBinding... bindings) {
      return addFunctionBindings(Arrays.asList(bindings));
    }

    @Override
    public CelLiteRuntimeBuilder addFunctionBindings(Iterable<CelFunctionBinding> bindings) {
      bindings.forEach(o -> customFunctionBindings.putIfAbsent(o.getOverloadId(), o));
      return this;
    }

    @Override
    public CelLiteRuntimeBuilder setValueProvider(CelValueProvider celValueProvider) {
      this.celValueProvider = celValueProvider;
      return this;
    }

    @Override
    public CelLiteRuntimeBuilder addLibraries(CelLiteRuntimeLibrary... libraries) {
      return addLibraries(Arrays.asList(checkNotNull(libraries)));
    }

    @Override
    public CelLiteRuntimeBuilder addLibraries(Iterable<? extends CelLiteRuntimeLibrary> libraries) {
      this.runtimeLibrariesBuilder.addAll(checkNotNull(libraries));
      return this;
    }

    @Override
    public CelLiteRuntimeBuilder addLateBoundFunctions(String... lateBoundFunctionNames) {
      return addLateBoundFunctions(Arrays.asList(lateBoundFunctionNames));
    }

    @Override
    public CelLiteRuntimeBuilder addLateBoundFunctions(Iterable<String> lateBoundFunctionNames) {
      lateBoundFunctionNamesBuilder.addAll(lateBoundFunctionNames);
      return this;
    }

    @Override
    public CelLiteRuntimeBuilder setTypeProvider(CelTypeProvider celTypeProvider) {
      this.celTypeProvider = celTypeProvider;
      return this;
    }

    @Override
    public CelLiteRuntimeBuilder setContainer(CelContainer container) {
      this.container = checkNotNull(container);
      return this;
    }

    /** Throws if an unsupported flag in CelOptions is toggled. */
    private static void assertAllowedCelOptions(CelOptions celOptions) {
      String prefix = "Misconfigured CelOptions: ";
      if (!celOptions.enableUnsignedLongs()) {
        throw new IllegalArgumentException(prefix + "enableUnsignedLongs cannot be disabled.");
      }
      if (!celOptions.unwrapWellKnownTypesOnFunctionDispatch()) {
        throw new IllegalArgumentException(
            prefix + "unwrapWellKnownTypesOnFunctionDispatch cannot be disabled.");
      }
    }

    @Override
    public CelLiteRuntime build() {
      assertAllowedCelOptions(celOptions);
      ImmutableSet<CelLiteRuntimeLibrary> runtimeLibs = runtimeLibrariesBuilder.build();
      runtimeLibs.forEach(lib -> lib.setRuntimeOptions(this));

      ImmutableMap.Builder<String, CelFunctionBinding> functionBindingsBuilder =
          ImmutableMap.builder();

      ImmutableSet<CelStandardFunction> standardFunctions = standardFunctionBuilder.build();
      if (!standardFunctions.isEmpty()) {
        RuntimeHelpers runtimeHelpers = RuntimeHelpers.create();
        RuntimeEquality runtimeEquality = RuntimeEquality.create(runtimeHelpers, celOptions);
        for (CelStandardFunction standardFunction : standardFunctions) {
          ImmutableSet<CelFunctionBinding> standardFunctionBinding =
              standardFunction.newFunctionBindings(celOptions, runtimeEquality);
          for (CelFunctionBinding func : standardFunctionBinding) {
            functionBindingsBuilder.put(func.getOverloadId(), func);
          }
        }
      }

      functionBindingsBuilder.putAll(customFunctionBindings);

      DefaultDispatcher.Builder dispatcherBuilder = DefaultDispatcher.newBuilder();
      functionBindingsBuilder
          .buildOrThrow()
          .forEach(
              (String overloadId, CelFunctionBinding func) -> {
                String functionName = func.getOverloadId();
                if (func instanceof InternalCelFunctionBinding) {
                  functionName = ((InternalCelFunctionBinding) func).getFunctionName();
                }
                dispatcherBuilder.addOverload(
                    functionName,
                    overloadId,
                    func.getArgTypes(),
                    func.isStrict(),
                    func.getDefinition());
              });

      CelTypeProvider celTypeProvider = DefaultTypeProvider.getInstance();
      if (this.celTypeProvider != null) {
        celTypeProvider =
            new CelTypeProvider.CombinedCelTypeProvider(celTypeProvider, this.celTypeProvider);
      }

      ProgramPlanner planner =
          ProgramPlanner.newPlanner(
              celTypeProvider,
              celValueProvider,
              dispatcherBuilder.build(),
              celValueProvider.celValueConverter(),
              container,
              celOptions,
              lateBoundFunctionNamesBuilder.build());

      return new LiteRuntimeImpl(
          planner,
          celOptions,
          customFunctionBindings.values(),
          standardFunctions,
          runtimeLibs,
          celValueProvider,
          celTypeProvider,
          container);
    }

    private Builder() {
      this.celOptions = CelOptions.DEFAULT;
      this.celValueProvider =
          new CelValueProvider() {
            @Override
            public Optional<Object> newValue(String structType, Map<String, Object> fields) {
              return Optional.empty();
            }

            @Override
            public CelValueConverter celValueConverter() {
              return CelValueConverter.getDefaultInstance();
            }
          };
      this.customFunctionBindings = new HashMap<>();
      this.standardFunctionBuilder = ImmutableSet.builder();
      this.runtimeLibrariesBuilder = ImmutableSet.builder();
      this.lateBoundFunctionNamesBuilder = ImmutableSet.builder();
      this.container = CelContainer.newBuilder().build();
    }
  }

  static CelLiteRuntimeBuilder newBuilder() {
    return new Builder();
  }

  private LiteRuntimeImpl(
      ProgramPlanner planner,
      CelOptions celOptions,
      Iterable<CelFunctionBinding> customFunctionBindings,
      ImmutableSet<CelStandardFunction> celStandardFunctions,
      ImmutableSet<CelLiteRuntimeLibrary> runtimeLibraries,
      CelValueProvider celValueProvider,
      CelTypeProvider celTypeProvider,
      CelContainer celContainer) {
    this.planner = planner;
    this.celOptions = celOptions;
    this.customFunctionBindings = ImmutableList.copyOf(customFunctionBindings);
    this.celStandardFunctions = celStandardFunctions;
    this.runtimeLibraries = runtimeLibraries;
    this.celValueProvider = celValueProvider;
    this.celTypeProvider = celTypeProvider;
    this.celContainer = celContainer;
  }
}
