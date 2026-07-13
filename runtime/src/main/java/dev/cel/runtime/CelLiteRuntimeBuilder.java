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

import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.errorprone.annotations.CheckReturnValue;
import dev.cel.common.CelContainer;
import dev.cel.common.CelOptions;
import dev.cel.common.types.CelTypeProvider;
import dev.cel.common.values.CelValueProvider;
import dev.cel.runtime.standard.CelStandardFunction;

/** Interface for building an instance of {@link CelLiteRuntime} */
public interface CelLiteRuntimeBuilder {

  /** Set the {@code CelOptions} used to enable fixes and features for this CEL instance. */
  @CanIgnoreReturnValue
  CelLiteRuntimeBuilder setOptions(CelOptions options);

  /**
   * Set the standard functions to enable in the runtime. These can be found in {@code
   * dev.cel.runtime.standard} package. By default, lite runtime does not include any standard
   * functions on its own.
   */
  @CanIgnoreReturnValue
  CelLiteRuntimeBuilder setStandardFunctions(CelStandardFunction... standardFunctions);

  @CanIgnoreReturnValue
  CelLiteRuntimeBuilder setStandardFunctions(
      Iterable<? extends CelStandardFunction> standardFunctions);

  /** Add one or more {@link CelFunctionBinding} objects to the CEL runtime. */
  @CanIgnoreReturnValue
  CelLiteRuntimeBuilder addFunctionBindings(CelFunctionBinding... bindings);

  /** Bind a collection of {@link CelFunctionBinding} objects to the runtime. */
  @CanIgnoreReturnValue
  CelLiteRuntimeBuilder addFunctionBindings(Iterable<CelFunctionBinding> bindings);

  /**
   * Sets the {@link CelValueProvider} for resolving struct values during evaluation. Multiple
   * providers can be combined using {@code CombinedCelValueProvider}. Note that if you intend to
   * support proto messages in addition to custom struct values, protobuf value provider must be
   * configured first before the custom value provider.
   */
  @CanIgnoreReturnValue
  CelLiteRuntimeBuilder setValueProvider(CelValueProvider celValueProvider);

  @CanIgnoreReturnValue
  CelLiteRuntimeBuilder addLibraries(CelLiteRuntimeLibrary... libraries);

  @CanIgnoreReturnValue
  CelLiteRuntimeBuilder addLibraries(Iterable<? extends CelLiteRuntimeLibrary> libraries);

  /**
   * Sets the {@link CelTypeProvider} for resolving CEL types during evaluation, such as a fully
   * qualified type name to a struct or an enum value.
   */
  @CanIgnoreReturnValue
  CelLiteRuntimeBuilder setTypeProvider(CelTypeProvider celTypeProvider);

  /**
   * Set the {@link CelContainer} to use as the namespace for resolving CEL expression variables and
   * functions.
   */
  @CanIgnoreReturnValue
  CelLiteRuntimeBuilder setContainer(CelContainer container);

  /** Adds bindings for functions that are allowed to be late-bound (resolved at execution time). */
  @CanIgnoreReturnValue
  CelLiteRuntimeBuilder addLateBoundFunctions(String... lateBoundFunctionNames);

  /** Adds bindings for functions that are allowed to be late-bound (resolved at execution time). */
  @CanIgnoreReturnValue
  CelLiteRuntimeBuilder addLateBoundFunctions(Iterable<String> lateBoundFunctionNames);

  @CheckReturnValue
  CelLiteRuntime build();
}
