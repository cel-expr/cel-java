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

import static com.google.common.collect.ImmutableList.toImmutableList;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.errorprone.annotations.Immutable;
import dev.cel.common.exceptions.CelOverloadNotFoundException;

@Immutable
final class FunctionBindingImpl implements InternalCelFunctionBinding {

  private final String functionName;

  private final String overloadId;

  private final ImmutableList<Class<?>> argTypes;

  private final CelFunctionOverload definition;

  private final OptimizedFunctionOverload optimizedDefinition;

  private final boolean isStrict;

  @Override
  public String getFunctionName() {
    return functionName;
  }

  @Override
  public String getOverloadId() {
    return overloadId;
  }

  @Override
  public ImmutableList<Class<?>> getArgTypes() {
    return argTypes;
  }

  @Override
  public CelFunctionOverload getDefinition() {
    return definition;
  }

  OptimizedFunctionOverload getOptimizedDefinition() {
    return optimizedDefinition;
  }

  @Override
  public boolean isStrict() {
    return isStrict;
  }

  FunctionBindingImpl(
      String functionName,
      String overloadId,
      ImmutableList<Class<?>> argTypes,
      CelFunctionOverload definition,
      boolean isStrict) {
    this.functionName = functionName;
    this.overloadId = overloadId;
    this.argTypes = argTypes;
    this.definition = definition;
    this.optimizedDefinition = OptimizedFunctionOverload.of(definition);
    this.isStrict = isStrict;
  }

  FunctionBindingImpl(
      String overloadId,
      ImmutableList<Class<?>> argTypes,
      CelFunctionOverload definition,
      boolean isStrict) {
    this(overloadId, overloadId, argTypes, definition, isStrict);
  }

  static ImmutableSet<CelFunctionBinding> groupOverloadsToFunction(
      String functionName, ImmutableSet<CelFunctionBinding> overloadBindings) {
    ImmutableSet.Builder<FunctionBindingImpl> functionBindingsBuilder = ImmutableSet.builder();
    for (CelFunctionBinding b : overloadBindings) {
      functionBindingsBuilder.add(
          new FunctionBindingImpl(
              functionName, b.getOverloadId(), b.getArgTypes(), b.getDefinition(), b.isStrict()));
    }
    ImmutableSet<FunctionBindingImpl> functionBindings = functionBindingsBuilder.build();
    ImmutableSet.Builder<CelFunctionBinding> builder =
        ImmutableSet.<CelFunctionBinding>builder().addAll(functionBindings);

    // If there is already a binding with the same name as the function, we treat it as a
    // "Singleton" binding and do not create a dynamic dispatch wrapper for it.
    // (Ex: "matches" function)
    boolean hasSingletonBinding =
        overloadBindings.stream().anyMatch(b -> b.getOverloadId().equals(functionName));

    if (!hasSingletonBinding) {
      if (overloadBindings.size() == 1) {
        CelFunctionBinding singleBinding = Iterables.getOnlyElement(overloadBindings);
        builder.add(
            new FunctionBindingImpl(
                functionName,
                functionName,
                singleBinding.getArgTypes(),
                singleBinding.getDefinition(),
                singleBinding.isStrict()));
      } else if (overloadBindings.size() > 1) {
        builder.add(new DynamicDispatchBinding(functionName, functionBindings));
      }
    }

    return builder.build();
  }

  @Immutable
  static final class DynamicDispatchBinding implements InternalCelFunctionBinding {

    private final boolean isStrict;
    private final DynamicDispatchOverload dynamicDispatchOverload;

    @Override
    public String getOverloadId() {
      return dynamicDispatchOverload.functionName;
    }

    @Override
    public String getFunctionName() {
      return dynamicDispatchOverload.functionName;
    }

    @Override
    public ImmutableList<Class<?>> getArgTypes() {
      return ImmutableList.of();
    }

    @Override
    public CelFunctionOverload getDefinition() {
      return dynamicDispatchOverload;
    }

    @Override
    public boolean isStrict() {
      return isStrict;
    }

    private DynamicDispatchBinding(
        String functionName, ImmutableSet<FunctionBindingImpl> overloadBindings) {
      this.isStrict = overloadBindings.stream().allMatch(CelFunctionBinding::isStrict);
      this.dynamicDispatchOverload = new DynamicDispatchOverload(functionName, overloadBindings);
    }
  }

  @Immutable
  static final class DynamicDispatchOverload implements OptimizedFunctionOverload {
    private final String functionName;
    private final ImmutableSet<FunctionBindingImpl> overloadBindings;

    @Override
    public Object apply(Object[] args) throws CelEvaluationException {
      for (FunctionBindingImpl overload : overloadBindings) {
        if (CelFunctionOverload.canHandle(args, overload.getArgTypes(), overload.isStrict())) {
          return overload.getDefinition().apply(args);
        }
      }

      throw new CelOverloadNotFoundException(
          functionName,
          overloadBindings.stream()
              .map(CelFunctionBinding::getOverloadId)
              .collect(toImmutableList()));
    }

    @Override
    public Object apply(Object arg) throws CelEvaluationException {
      for (FunctionBindingImpl overload : overloadBindings) {
        if (CelFunctionOverload.canHandle(arg, overload.getArgTypes(), overload.isStrict())) {
          return overload.getOptimizedDefinition().apply(arg);
        }
      }
      throw new CelOverloadNotFoundException(
          functionName,
          overloadBindings.stream()
              .map(CelFunctionBinding::getOverloadId)
              .collect(toImmutableList()));
    }

    @Override
    public Object apply(Object arg1, Object arg2) throws CelEvaluationException {
      for (FunctionBindingImpl overload : overloadBindings) {
        if (CelFunctionOverload.canHandle(
            arg1, arg2, overload.getArgTypes(), overload.isStrict())) {
          return overload.getOptimizedDefinition().apply(arg1, arg2);
        }
      }
      throw new CelOverloadNotFoundException(
          functionName,
          overloadBindings.stream()
              .map(CelFunctionBinding::getOverloadId)
              .collect(toImmutableList()));
    }

    ImmutableSet<FunctionBindingImpl> getOverloadBindings() {
      return overloadBindings;
    }

    DynamicDispatchOverload(
        String functionName, ImmutableSet<FunctionBindingImpl> overloadBindings) {
      this.functionName = functionName;
      this.overloadBindings = overloadBindings;
    }
  }
}
