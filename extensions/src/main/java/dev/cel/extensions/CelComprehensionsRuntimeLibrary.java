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

package dev.cel.extensions;

import static com.google.common.base.Preconditions.checkArgument;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.errorprone.annotations.Immutable;
import dev.cel.common.CelOptions;
import dev.cel.common.values.MutableMapValue;
import dev.cel.runtime.CelFunctionBinding;
import dev.cel.runtime.CelLiteRuntimeBuilder;
import dev.cel.runtime.CelLiteRuntimeLibrary;
import dev.cel.runtime.RuntimeEquality;
import dev.cel.runtime.RuntimeHelpers;
import java.util.Map;
import java.util.Set;

/** Runtime implementation of CEL two variable comprehensions extensions. */
@Immutable
public final class CelComprehensionsRuntimeLibrary implements CelLiteRuntimeLibrary {

  private static final String MAP_INSERT_FUNCTION = "cel.@mapInsert";
  private static final String MAP_INSERT_OVERLOAD_MAP_MAP = "cel_@mapInsert_map_map";
  private static final String MAP_INSERT_OVERLOAD_KEY_VALUE = "cel_@mapInsert_map_key_value";

  /** Enumeration of functions for Comprehensions runtime extension. */
  public enum Function {
    MAP_INSERT("cel.@mapInsert");

    private final String functionName;

    public String getFunction() {
      return functionName;
    }

    Function(String functionName) {
      this.functionName = functionName;
    }
  }

  private static ImmutableSet<Function> getFunctionsForVersion(int version) {
    switch (version) {
      case 0:
      case Integer.MAX_VALUE:
        return ImmutableSet.of(Function.MAP_INSERT);
      default:
        throw new IllegalArgumentException(
            "Unsupported 'comprehensions' extension version " + version);
    }
  }

  /**
   * Returns the latest version of the 'comprehensions' runtime functions using {@link
   * CelOptions#DEFAULT}.
   */
  public static CelComprehensionsRuntimeLibrary comprehensions() {
    return comprehensions(CelOptions.DEFAULT);
  }

  /**
   * Returns the specified version of the 'comprehensions' runtime functions using {@link
   * CelOptions#DEFAULT}.
   */
  public static CelComprehensionsRuntimeLibrary comprehensions(int version) {
    return comprehensions(CelOptions.DEFAULT, version);
  }

  /**
   * Returns the 'comprehensions' runtime functions with only the specified functions using {@link
   * CelOptions#DEFAULT}.
   */
  public static CelComprehensionsRuntimeLibrary comprehensions(Function... functions) {
    return comprehensions(CelOptions.DEFAULT, functions);
  }

  /**
   * Returns the 'comprehensions' runtime functions with only the specified functions using {@link
   * CelOptions#DEFAULT}.
   */
  public static CelComprehensionsRuntimeLibrary comprehensions(Set<Function> functions) {
    return comprehensions(CelOptions.DEFAULT, functions);
  }

  /** Returns the latest version of the 'comprehensions' runtime functions. */
  public static CelComprehensionsRuntimeLibrary comprehensions(CelOptions celOptions) {
    return comprehensions(celOptions, Integer.MAX_VALUE);
  }

  /** Returns the specified version of the 'comprehensions' runtime functions. */
  public static CelComprehensionsRuntimeLibrary comprehensions(CelOptions celOptions, int version) {
    return comprehensions(celOptions, getFunctionsForVersion(version));
  }

  /** Returns the 'comprehensions' runtime functions with only the specified functions. */
  public static CelComprehensionsRuntimeLibrary comprehensions(
      CelOptions celOptions, Function... functions) {
    return comprehensions(celOptions, ImmutableSet.copyOf(functions));
  }

  /** Returns the 'comprehensions' runtime functions with only the specified functions. */
  public static CelComprehensionsRuntimeLibrary comprehensions(
      CelOptions celOptions, Set<Function> functions) {
    RuntimeEquality runtimeEquality = RuntimeEquality.create(RuntimeHelpers.create(), celOptions);
    return new CelComprehensionsRuntimeLibrary(runtimeEquality, functions);
  }

  private final RuntimeEquality runtimeEquality;
  private final ImmutableSet<Function> functions;

  CelComprehensionsRuntimeLibrary(Set<Function> functions) {
    this(RuntimeEquality.create(RuntimeHelpers.create(), CelOptions.DEFAULT), functions);
  }

  CelComprehensionsRuntimeLibrary(RuntimeEquality runtimeEquality, int version) {
    this(runtimeEquality, getFunctionsForVersion(version));
  }

  CelComprehensionsRuntimeLibrary(RuntimeEquality runtimeEquality, Set<Function> functions) {
    this.runtimeEquality = runtimeEquality;
    this.functions = ImmutableSet.copyOf(functions);
  }

  ImmutableSet<Function> functions() {
    return functions;
  }

  @Override
  public void setRuntimeOptions(CelLiteRuntimeBuilder runtimeBuilder) {
    runtimeBuilder.addFunctionBindings(newFunctionBindings());
  }

  ImmutableList<CelFunctionBinding> newFunctionBindings() {
    ImmutableList.Builder<CelFunctionBinding> bindings = ImmutableList.builder();
    if (functions.contains(Function.MAP_INSERT)) {
      bindings.addAll(
          CelFunctionBinding.fromOverloads(
              MAP_INSERT_FUNCTION,
              CelFunctionBinding.from(
                  MAP_INSERT_OVERLOAD_MAP_MAP,
                  Map.class,
                  Map.class,
                  (map1, map2) -> mapInsertMap(map1, map2, runtimeEquality)),
              CelFunctionBinding.from(
                  MAP_INSERT_OVERLOAD_KEY_VALUE,
                  ImmutableList.of(Map.class, Object.class, Object.class),
                  args -> mapInsertKeyValue(args, runtimeEquality))));
    }
    return bindings.build();
  }

  private static Map<Object, Object> mapInsertMap(
      Map<?, ?> targetMap, Map<?, ?> mapToMerge, RuntimeEquality equality) {
    for (Object key : mapToMerge.keySet()) {
      checkArgument(
          !equality.findInMap(targetMap, key).isPresent(),
          "insert failed: key '%s' already exists",
          key);
    }

    if (targetMap instanceof MutableMapValue) {
      MutableMapValue wrapper = (MutableMapValue) targetMap;
      wrapper.putAll(mapToMerge);
      return wrapper;
    }

    return ImmutableMap.builderWithExpectedSize(targetMap.size() + mapToMerge.size())
        .putAll(targetMap)
        .putAll(mapToMerge)
        .buildOrThrow();
  }

  private static Map<Object, Object> mapInsertKeyValue(Object[] args, RuntimeEquality equality) {
    Map<?, ?> mapArg = (Map<?, ?>) args[0];
    Object key = args[1];
    Object value = args[2];

    checkArgument(
        !equality.findInMap(mapArg, key).isPresent(),
        "insert failed: key '%s' already exists",
        key);

    if (mapArg instanceof MutableMapValue) {
      MutableMapValue mutableMap = (MutableMapValue) mapArg;
      mutableMap.put(key, value);
      return mutableMap;
    }

    ImmutableMap.Builder<Object, Object> builder =
        ImmutableMap.builderWithExpectedSize(mapArg.size() + 1);
    return builder.put(key, value).putAll(mapArg).buildOrThrow();
  }
}
