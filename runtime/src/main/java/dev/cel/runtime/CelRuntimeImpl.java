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

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.errorprone.annotations.Immutable;
import com.google.protobuf.DescriptorProtos;
import com.google.protobuf.Descriptors;
import com.google.protobuf.ExtensionRegistry;
import com.google.protobuf.Message;
import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.CelContainer;
import dev.cel.common.CelDescriptorUtil;
import dev.cel.common.CelDescriptors;
import dev.cel.common.CelOptions;
import dev.cel.common.annotations.Internal;
import dev.cel.common.internal.CelDescriptorPool;
import dev.cel.common.internal.CombinedDescriptorPool;
import dev.cel.common.internal.DefaultDescriptorPool;
import dev.cel.common.internal.DefaultMessageFactory;
import dev.cel.common.internal.DynamicProto;
// CEL-Internal-1
import dev.cel.common.types.CelTypeProvider;
import dev.cel.common.types.DefaultTypeProvider;
import dev.cel.common.types.ProtoMessageTypeProvider;
import dev.cel.common.values.CelValueConverter;
import dev.cel.common.values.CelValueProvider;
import dev.cel.common.values.CombinedCelValueProvider;
import dev.cel.common.values.ProtoMessageValueProvider;
import dev.cel.runtime.planner.PlannedProgram;
import dev.cel.runtime.planner.ProgramPlanner;
import dev.cel.runtime.standard.TypeFunction;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * {@link CelRuntime} implementation based on the {@link ProgramPlanner}.
 *
 * <p>CEL Library Internals. Do Not Use.
 */
@AutoValue
@Internal
@Immutable
public abstract class CelRuntimeImpl implements CelRuntime {

  abstract ProgramPlanner planner();

  abstract CelOptions options();

  abstract CelContainer container();

  abstract ImmutableMap<String, CelFunctionBinding> functionBindings();

  abstract ImmutableSet<Descriptors.FileDescriptor> fileDescriptors();

  // Callers must guarantee that a custom runtime library is immutable. CEL provided ones are
  // immutable by default.
  @SuppressWarnings("Immutable")
  @AutoValue.CopyAnnotations
  abstract ImmutableSet<CelRuntimeLibrary> runtimeLibraries();

  abstract ImmutableSet<String> lateBoundFunctionNames();

  abstract CelStandardFunctions standardFunctions();

  abstract @Nullable CelTypeProvider typeProvider();

  abstract @Nullable CelValueProvider valueProvider();

  // Extension registry is unmodifiable. Just not marked as such from Protobuf's implementation.
  @SuppressWarnings("Immutable")
  @AutoValue.CopyAnnotations
  abstract @Nullable ExtensionRegistry extensionRegistry();

  @Override
  public Program createProgram(CelAbstractSyntaxTree ast) throws CelEvaluationException {
    return toRuntimeProgram(planner().plan(ast));
  }

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

  public Program toRuntimeProgram(dev.cel.runtime.Program program) {
    return new Program() {

      @Override
      public Object eval() throws CelEvaluationException {
        return program.eval();
      }

      @Override
      public Object eval(Map<String, ?> mapValue) throws CelEvaluationException {
        return program.eval(mapValue);
      }

      @Override
      public Object eval(Map<String, ?> mapValue, CelFunctionResolver lateBoundFunctionResolver)
          throws CelEvaluationException {
        return program.eval(mapValue, lateBoundFunctionResolver);
      }

      @Override
      public Object eval(Message message) throws CelEvaluationException {
        PlannedProgram plannedProgram = (PlannedProgram) program;
        return plannedProgram.evalOrThrow(
            plannedProgram.interpretable(),
            ProtoMessageActivationFactory.fromProto(message, plannedProgram.options()),
            EMPTY_FUNCTION_RESOLVER,
            /* partialVars= */ null,
            /* listener= */ null);
      }

      @Override
      public Object eval(CelVariableResolver resolver) throws CelEvaluationException {
        return program.eval(resolver);
      }

      @Override
      public Object eval(
          CelVariableResolver resolver, CelFunctionResolver lateBoundFunctionResolver)
          throws CelEvaluationException {
        return program.eval(resolver, lateBoundFunctionResolver);
      }

      @Override
      public Object eval(PartialVars partialVars) throws CelEvaluationException {
        return program.eval(partialVars);
      }

      @Override
      public Object trace(CelEvaluationListener listener) throws CelEvaluationException {
        return ((PlannedProgram) program)
            .trace(GlobalResolver.EMPTY, EMPTY_FUNCTION_RESOLVER, null, listener);
      }

      @Override
      public Object trace(Map<String, ?> mapValue, CelEvaluationListener listener)
          throws CelEvaluationException {
        return ((PlannedProgram) program)
            .trace(Activation.copyOf(mapValue), EMPTY_FUNCTION_RESOLVER, null, listener);
      }

      @Override
      public Object trace(Message message, CelEvaluationListener listener)
          throws CelEvaluationException {
        PlannedProgram plannedProgram = (PlannedProgram) program;
        return plannedProgram.evalOrThrow(
            plannedProgram.interpretable(),
            ProtoMessageActivationFactory.fromProto(message, plannedProgram.options()),
            EMPTY_FUNCTION_RESOLVER,
            /* partialVars= */ null,
            listener);
      }

      @Override
      public Object trace(CelVariableResolver resolver, CelEvaluationListener listener)
          throws CelEvaluationException {
        return ((PlannedProgram) program)
            .trace(
                (name) -> resolver.find(name).orElse(null),
                EMPTY_FUNCTION_RESOLVER,
                null,
                listener);
      }

      @Override
      public Object trace(
          CelVariableResolver resolver,
          CelFunctionResolver lateBoundFunctionResolver,
          CelEvaluationListener listener)
          throws CelEvaluationException {
        return ((PlannedProgram) program)
            .trace(
                (name) -> resolver.find(name).orElse(null),
                lateBoundFunctionResolver,
                null,
                listener);
      }

      @Override
      public Object trace(
          Map<String, ?> mapValue,
          CelFunctionResolver lateBoundFunctionResolver,
          CelEvaluationListener listener)
          throws CelEvaluationException {
        return ((PlannedProgram) program)
            .trace(Activation.copyOf(mapValue), lateBoundFunctionResolver, null, listener);
      }

      @Override
      public Object trace(PartialVars partialVars, CelEvaluationListener listener)
          throws CelEvaluationException {
        return ((PlannedProgram) program)
            .trace(
                (name) -> partialVars.resolver().find(name).orElse(null),
                EMPTY_FUNCTION_RESOLVER,
                partialVars,
                listener);
      }

      @Override
      public Object advanceEvaluation(UnknownContext context) throws CelEvaluationException {
        throw new UnsupportedOperationException("Unsupported operation.");
      }
    };
  }

  @Override
  public abstract Builder toRuntimeBuilder();

  /**
   * CEL Library Internals. Do not use. Consumers should use {@code CelRuntimeFactory} instead.
   *
   * <p>TODO: Restrict visibility once factory is introduced
   */
  public static Builder newBuilder() {
    return new AutoValue_CelRuntimeImpl.Builder()
        .setFunctionBindings(ImmutableMap.of())
        .setStandardFunctions(CelStandardFunctions.newBuilder().build())
        .setContainer(CelContainer.newBuilder().build())
        .setExtensionRegistry(ExtensionRegistry.getEmptyRegistry());
  }

  /** Builder for {@link CelRuntimeImpl}. */
  @AutoValue.Builder
  public abstract static class Builder implements CelRuntimeBuilder {

    public abstract Builder setPlanner(ProgramPlanner planner);

    @Override
    public abstract Builder setOptions(CelOptions options);

    @Override
    public abstract Builder setStandardFunctions(CelStandardFunctions standardFunctions);

    @Override
    public abstract Builder setExtensionRegistry(ExtensionRegistry extensionRegistry);

    @Override
    public abstract Builder setTypeProvider(CelTypeProvider celTypeProvider);

    @Override
    public abstract Builder setValueProvider(CelValueProvider celValueProvider);

    @Override
    public abstract Builder setContainer(CelContainer container);

    abstract CelOptions options();

    abstract CelContainer container();

    abstract CelTypeProvider typeProvider();

    @Override
    public abstract CelValueProvider valueProvider();

    abstract CelStandardFunctions standardFunctions();

    abstract ExtensionRegistry extensionRegistry();

    abstract ImmutableMap<String, CelFunctionBinding> functionBindings();

    abstract ImmutableSet.Builder<Descriptors.FileDescriptor> fileDescriptorsBuilder();

    abstract ImmutableSet.Builder<CelRuntimeLibrary> runtimeLibrariesBuilder();

    abstract ImmutableSet.Builder<String> lateBoundFunctionNamesBuilder();

    private final Map<String, CelFunctionBinding> mutableFunctionBindings = new HashMap<>();

    @Override
    @CanIgnoreReturnValue
    public Builder addFunctionBindings(CelFunctionBinding... bindings) {
      checkNotNull(bindings);
      return addFunctionBindings(Arrays.asList(bindings));
    }

    @Override
    @CanIgnoreReturnValue
    public Builder addFunctionBindings(Iterable<CelFunctionBinding> bindings) {
      checkNotNull(bindings);
      bindings.forEach(o -> mutableFunctionBindings.putIfAbsent(o.getOverloadId(), o));
      return this;
    }

    @Override
    @CanIgnoreReturnValue
    public Builder addLateBoundFunctions(String... lateBoundFunctionNames) {
      checkNotNull(lateBoundFunctionNames);
      return addLateBoundFunctions(Arrays.asList(lateBoundFunctionNames));
    }

    @Override
    @CanIgnoreReturnValue
    public Builder addLateBoundFunctions(Iterable<String> lateBoundFunctionNames) {
      checkNotNull(lateBoundFunctionNames);
      this.lateBoundFunctionNamesBuilder().addAll(lateBoundFunctionNames);
      return this;
    }

    @Override
    @CanIgnoreReturnValue
    public Builder addMessageTypes(Descriptors.Descriptor... descriptors) {
      checkNotNull(descriptors);
      return addMessageTypes(Arrays.asList(descriptors));
    }

    @Override
    @CanIgnoreReturnValue
    public Builder addMessageTypes(Iterable<Descriptors.Descriptor> descriptors) {
      checkNotNull(descriptors);
      return addFileTypes(CelDescriptorUtil.getFileDescriptorsForDescriptors(descriptors));
    }

    @Override
    @CanIgnoreReturnValue
    public Builder addFileTypes(DescriptorProtos.FileDescriptorSet fileDescriptorSet) {
      checkNotNull(fileDescriptorSet);
      return addFileTypes(
          CelDescriptorUtil.getFileDescriptorsFromFileDescriptorSet(fileDescriptorSet));
    }

    @Override
    @CanIgnoreReturnValue
    public Builder addFileTypes(Descriptors.FileDescriptor... fileDescriptors) {
      checkNotNull(fileDescriptors);
      return addFileTypes(Arrays.asList(fileDescriptors));
    }

    @Override
    @CanIgnoreReturnValue
    public Builder addFileTypes(Iterable<Descriptors.FileDescriptor> fileDescriptors) {
      checkNotNull(fileDescriptors);
      this.fileDescriptorsBuilder().addAll(fileDescriptors);
      return this;
    }

    @Override
    @CanIgnoreReturnValue
    public Builder addLibraries(CelRuntimeLibrary... libraries) {
      checkNotNull(libraries);
      return this.addLibraries(Arrays.asList(libraries));
    }

    @Override
    @CanIgnoreReturnValue
    public Builder addLibraries(Iterable<? extends CelRuntimeLibrary> libraries) {
      checkNotNull(libraries);
      this.runtimeLibrariesBuilder().addAll(libraries);
      return this;
    }

    abstract Builder setFunctionBindings(ImmutableMap<String, CelFunctionBinding> value);

    @Override
    public Builder setTypeFactory(Function<String, Message.Builder> typeFactory) {
      throw new UnsupportedOperationException("Unsupported. Use a custom value provider instead.");
    }

    @Override
    public Builder setStandardEnvironmentEnabled(boolean value) {
      throw new UnsupportedOperationException(
          "Unsupported. Subset the environment using setStandardFunctions instead.");
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

      // Disallowed options in favor of subsetting
      String subsettingError = "Subset the environment instead using setStandardFunctions method.";

      if (!celOptions.enableTimestampEpoch()) {
        throw new IllegalArgumentException(
            prefix + "enableTimestampEpoch cannot be disabled. " + subsettingError);
      }

      if (!celOptions.enableHeterogeneousNumericComparisons()) {
        throw new IllegalArgumentException(
            prefix
                + "enableHeterogeneousNumericComparisons cannot be disabled. "
                + subsettingError);
      }
    }

    abstract CelRuntimeImpl autoBuild();

    private static DefaultDispatcher newDispatcher(
        CelStandardFunctions standardFunctions,
        Collection<CelFunctionBinding> customFunctionBindings,
        RuntimeEquality runtimeEquality,
        CelOptions options) {
      DefaultDispatcher.Builder builder = DefaultDispatcher.newBuilder();
      for (CelFunctionBinding binding :
          standardFunctions.newFunctionBindings(runtimeEquality, options)) {
        String functionName = binding.getOverloadId();
        if (binding instanceof InternalCelFunctionBinding) {
          functionName = ((InternalCelFunctionBinding) binding).getFunctionName();
        }
        builder.addOverload(
            functionName,
            binding.getOverloadId(),
            binding.getArgTypes(),
            binding.isStrict(),
            binding.getDefinition());
      }

      for (CelFunctionBinding binding : customFunctionBindings) {
        String functionName = binding.getOverloadId();
        if (binding instanceof InternalCelFunctionBinding) {
          functionName = ((InternalCelFunctionBinding) binding).getFunctionName();
        }
        builder.addOverload(
            functionName,
            binding.getOverloadId(),
            binding.getArgTypes(),
            binding.isStrict(),
            binding.getDefinition());
      }

      return builder.build();
    }

    private static CelDescriptorPool newDescriptorPool(
        CelDescriptors celDescriptors,
        ExtensionRegistry extensionRegistry) {
      ImmutableList.Builder<CelDescriptorPool> descriptorPools = new ImmutableList.Builder<>();

      descriptorPools.add(DefaultDescriptorPool.create(celDescriptors, extensionRegistry));

      return CombinedDescriptorPool.create(descriptorPools.build());
    }

    @Override
    public CelRuntime build() {
      CelOptions options = options();
      assertAllowedCelOptions(options);
      CelDescriptors celDescriptors =
          CelDescriptorUtil.getAllDescriptorsFromFileDescriptor(fileDescriptorsBuilder().build());

      CelDescriptorPool descriptorPool =
          newDescriptorPool(
              celDescriptors,
              extensionRegistry());
      DefaultMessageFactory defaultMessageFactory = DefaultMessageFactory.create(descriptorPool);
      DynamicProto dynamicProto = DynamicProto.create(defaultMessageFactory);
      CelValueProvider protoMessageValueProvider =
          ProtoMessageValueProvider.newInstance(options(), dynamicProto);
      RuntimeEquality runtimeEquality = ProtoMessageRuntimeEquality.create(dynamicProto, options());
      ImmutableSet<CelRuntimeLibrary> runtimeLibraries = runtimeLibrariesBuilder().build();
      // Add libraries, such as extensions
      for (CelRuntimeLibrary celLibrary : runtimeLibraries) {
        if (celLibrary instanceof CelInternalRuntimeLibrary) {
          ((CelInternalRuntimeLibrary) celLibrary)
              .setRuntimeOptions(this, runtimeEquality, options());
        } else {
          celLibrary.setRuntimeOptions(this);
        }
      }

      if (valueProvider() != null) {
        protoMessageValueProvider =
            CombinedCelValueProvider.combine(protoMessageValueProvider, valueProvider());
      }
      setValueProvider(protoMessageValueProvider);
      CelValueConverter celValueConverter = protoMessageValueProvider.celValueConverter();

      CelTypeProvider messageTypeProvider =
          ProtoMessageTypeProvider.newBuilder()
              .setCelDescriptors(celDescriptors)
              .setAllowJsonFieldNames(options().enableJsonFieldNames())
              .setResolveTypeDependencies(options().resolveTypeDependencies())
              .build();

      CelTypeProvider combinedTypeProvider =
          new CelTypeProvider.CombinedCelTypeProvider(
              DefaultTypeProvider.getInstance(), messageTypeProvider);
      if (typeProvider() != null) {
        combinedTypeProvider =
            new CelTypeProvider.CombinedCelTypeProvider(combinedTypeProvider, typeProvider());
      }

      DescriptorTypeResolver descriptorTypeResolver =
          DescriptorTypeResolver.create(combinedTypeProvider, celValueConverter);
      TypeFunction typeFunction = TypeFunction.create(descriptorTypeResolver);

      mutableFunctionBindings.putAll(functionBindings());

      for (CelFunctionBinding binding :
          typeFunction.newFunctionBindings(options(), runtimeEquality)) {
        mutableFunctionBindings.put(binding.getOverloadId(), binding);
      }

      DefaultDispatcher dispatcher =
          newDispatcher(
              standardFunctions(), mutableFunctionBindings.values(), runtimeEquality, options());

      ProgramPlanner planner =
          ProgramPlanner.newPlanner(
              combinedTypeProvider,
              protoMessageValueProvider,
              dispatcher,
              celValueConverter,
              container(),
              options(),
              lateBoundFunctionNamesBuilder().build());
      setPlanner(planner);

      setFunctionBindings(ImmutableMap.copyOf(mutableFunctionBindings));
      return autoBuild();
    }
  }
}
