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

package dev.cel.extensions;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.primitives.Ints;
import com.google.common.primitives.UnsignedLong;
import com.google.errorprone.annotations.Immutable;
import com.google.protobuf.MessageLite;
import dev.cel.common.CelOptions;
import dev.cel.common.Operator;
import dev.cel.common.values.CelByteString;
import dev.cel.common.values.CelValue;
import dev.cel.common.values.NullValue;
import dev.cel.runtime.CelFunctionBinding;
import dev.cel.runtime.CelInternalLiteRuntimeLibrary;
import dev.cel.runtime.CelLiteRuntimeBuilder;
import dev.cel.runtime.RuntimeEquality;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Runtime implementation of CEL Optional extension functions. */
@Immutable
public final class CelOptionalRuntimeLibrary implements CelInternalLiteRuntimeLibrary {

  /** Supported functions for Optional extension library. */
  public enum Function {
    VALUE("value"),
    HAS_VALUE("hasValue"),
    OPTIONAL_NONE("optional.none"),
    OPTIONAL_OF("optional.of"),
    OPTIONAL_UNWRAP("optional.unwrap"),
    OPTIONAL_OF_NON_ZERO_VALUE("optional.ofNonZeroValue"),
    OR("or"),
    OR_VALUE("orValue"),
    FIRST("first"),
    LAST("last");

    private final String functionName;

    public String getFunction() {
      return functionName;
    }

    Function(String functionName) {
      this.functionName = functionName;
    }
  }

  private static final CelOptionalRuntimeLibrary VERSION_1 =
      new CelOptionalRuntimeLibrary(
          ImmutableSet.of(
              Function.OPTIONAL_OF,
              Function.OPTIONAL_OF_NON_ZERO_VALUE,
              Function.OPTIONAL_NONE,
              Function.VALUE,
              Function.HAS_VALUE,
              Function.OPTIONAL_UNWRAP,
              Function.OR,
              Function.OR_VALUE));

  private static final CelOptionalRuntimeLibrary VERSION_2 =
      new CelOptionalRuntimeLibrary(ImmutableSet.copyOf(Function.values()));

  /** Returns the latest version of the 'optional' runtime functions. */
  public static CelOptionalRuntimeLibrary optional() {
    return VERSION_2;
  }

  /** Returns the specified version of the 'optional' runtime functions. */
  public static CelOptionalRuntimeLibrary optional(int version) {
    switch (version) {
      case 0:
      case 1:
        return VERSION_1;
      case 2:
      case Integer.MAX_VALUE:
        return VERSION_2;
      default:
        throw new IllegalArgumentException("Unsupported 'optional' extension version " + version);
    }
  }

  /** Returns the 'optional' runtime functions with only the specified functions. */
  public static CelOptionalRuntimeLibrary optional(Function... functions) {
    return optional(ImmutableSet.copyOf(functions));
  }

  /** Returns the 'optional' runtime functions with only the specified functions. */
  public static CelOptionalRuntimeLibrary optional(Set<Function> functions) {
    return new CelOptionalRuntimeLibrary(functions);
  }

  private final ImmutableSet<Function> functions;

  CelOptionalRuntimeLibrary(Set<Function> functions) {
    this.functions = ImmutableSet.copyOf(functions);
  }

  ImmutableSet<Function> functions() {
    return functions;
  }

  @Override
  public void setRuntimeOptions(CelLiteRuntimeBuilder runtimeBuilder) {
    throw new UnsupportedOperationException("Unsupported");
  }

  @Override
  public void setRuntimeOptions(
      CelLiteRuntimeBuilder runtimeBuilder,
      RuntimeEquality runtimeEquality,
      CelOptions celOptions) {
    runtimeBuilder.addFunctionBindings(newFunctionBindings(runtimeEquality));
  }

  public ImmutableSet<CelFunctionBinding> newFunctionBindings(RuntimeEquality runtimeEquality) {
    ImmutableSet.Builder<CelFunctionBinding> bindingBuilder = ImmutableSet.builder();
    for (Function function : functions) {
      switch (function) {
        case OPTIONAL_OF:
          bindingBuilder.addAll(
              CelFunctionBinding.fromOverloads(
                  function.getFunction(),
                  CelFunctionBinding.from("optional_of", Object.class, Optional::of)));
          break;
        case OPTIONAL_OF_NON_ZERO_VALUE:
          bindingBuilder.addAll(
              CelFunctionBinding.fromOverloads(
                  function.getFunction(),
                  CelFunctionBinding.from(
                      "optional_ofNonZeroValue",
                      Object.class,
                      val -> isZeroValue(val) ? Optional.empty() : Optional.of(val))));
          break;
        case OPTIONAL_UNWRAP:
          bindingBuilder.addAll(
              CelFunctionBinding.fromOverloads(
                  function.getFunction(),
                  CelFunctionBinding.from(
                      "optional_unwrap_list",
                      Collection.class,
                      CelOptionalRuntimeLibrary::elideOptionalCollection)));
          break;
        case OPTIONAL_NONE:
          bindingBuilder.addAll(
              CelFunctionBinding.fromOverloads(
                  function.getFunction(),
                  CelFunctionBinding.from(
                      "optional_none", ImmutableList.of(), val -> Optional.empty())));
          break;
        case VALUE:
          bindingBuilder.addAll(
              CelFunctionBinding.fromOverloads(
                  function.getFunction(),
                  CelFunctionBinding.from(
                      "optional_value", Object.class, val -> ((Optional<?>) val).get())));
          break;
        case HAS_VALUE:
          bindingBuilder.addAll(
              CelFunctionBinding.fromOverloads(
                  function.getFunction(),
                  CelFunctionBinding.from(
                      "optional_hasValue", Object.class, val -> ((Optional<?>) val).isPresent())));
          break;
        case OR:
        case OR_VALUE:
          // Note: Implementation of "or" and "orValue" are special-cased inside the interpreter.
          // Hence, their bindings are not provided here.
          break;
        case FIRST:
          bindingBuilder.addAll(
              CelFunctionBinding.fromOverloads(
                  function.getFunction(),
                  CelFunctionBinding.from(
                      "optional_list_first",
                      Collection.class,
                      CelOptionalRuntimeLibrary::listOptionalFirst)));
          break;
        case LAST:
          bindingBuilder.addAll(
              CelFunctionBinding.fromOverloads(
                  function.getFunction(),
                  CelFunctionBinding.from(
                      "optional_list_last",
                      Collection.class,
                      CelOptionalRuntimeLibrary::listOptionalLast)));
          break;
      }
    }

    bindingBuilder.addAll(
        CelFunctionBinding.fromOverloads(
            Operator.OPTIONAL_SELECT.getFunction(),
            CelFunctionBinding.from(
                "select_optional_field", // This only handles map selection. Proto selection is
                // special cased inside the interpreter.
                Map.class,
                String.class,
                runtimeEquality::findInMap)));

    bindingBuilder.addAll(
        CelFunctionBinding.fromOverloads(
            Operator.OPTIONAL_INDEX.getFunction(),
            CelFunctionBinding.from(
                "list_optindex_optional_int",
                List.class,
                Long.class,
                CelOptionalRuntimeLibrary::indexList),
            CelFunctionBinding.from(
                "optional_list_optindex_optional_int",
                Optional.class,
                Long.class,
                CelOptionalRuntimeLibrary::indexOptionalList),
            CelFunctionBinding.from(
                "map_optindex_optional_value", Map.class, Object.class, runtimeEquality::findInMap),
            CelFunctionBinding.from(
                "optional_map_optindex_optional_value",
                Optional.class,
                Object.class,
                (optionalMap, key) -> indexOptionalMap(optionalMap, key, runtimeEquality))));

    bindingBuilder.addAll(
        CelFunctionBinding.fromOverloads(
            Operator.INDEX.getFunction(),
            CelFunctionBinding.from(
                "optional_list_index_int",
                Optional.class,
                Long.class,
                CelOptionalRuntimeLibrary::indexOptionalList),
            CelFunctionBinding.from(
                "optional_map_index_value",
                Optional.class,
                Object.class,
                (optionalMap, key) -> indexOptionalMap(optionalMap, key, runtimeEquality))));

    return bindingBuilder.build();
  }

  private static ImmutableList<Object> elideOptionalCollection(Collection<?> list) {
    ImmutableList.Builder<Object> builder = ImmutableList.builder();
    for (Object element : list) {
      Optional<?> opt = (Optional<?>) element;
      if (opt.isPresent()) {
        builder.add(opt.get());
      }
    }
    return builder.build();
  }

  private static boolean isZeroValue(Object val) {
    if (val instanceof Boolean) {
      return !((Boolean) val);
    } else if (val instanceof Long) {
      return (Long) val == 0L;
    } else if (val instanceof Double) {
      return (Double) val == 0.0d;
    } else if (val instanceof String) {
      return ((String) val).isEmpty();
    } else if (val instanceof UnsignedLong) {
      return val.equals(UnsignedLong.ZERO);
    } else if (val instanceof Collection<?>) {
      return ((Collection<?>) val).isEmpty();
    } else if (val instanceof Map<?, ?>) {
      return ((Map<?, ?>) val).isEmpty();
    } else if (val instanceof CelByteString) {
      return ((CelByteString) val).isEmpty();
    } else if (val instanceof MessageLite) {
      return val.equals(((MessageLite) val).getDefaultInstanceForType());
    } else if (val instanceof NullValue) {
      // A null value always represents an absent value
      return true;
    } else if (val instanceof Instant) {
      return val.equals(Instant.EPOCH);
    } else if (val instanceof Duration) {
      return val.equals(Duration.ZERO);
    } else if (val instanceof CelValue) {
      return ((CelValue) val).isZeroValue();
    }

    // Unknown. Assume that it is non-zero.
    return false;
  }

  private static Object indexOptionalMap(
      Optional<?> optionalMap, Object key, RuntimeEquality runtimeEquality) {
    if (!optionalMap.isPresent()) {
      return Optional.empty();
    }

    Map<?, ?> map = (Map<?, ?>) optionalMap.get();

    return runtimeEquality.findInMap(map, key);
  }

  private static Object indexList(List<?> list, long index) {
    int castIndex = Ints.checkedCast(index);
    if (castIndex < 0 || castIndex >= list.size()) {
      return Optional.empty();
    }
    return Optional.of(list.get(castIndex));
  }

  private static Object indexOptionalList(Optional<?> optionalList, long index) {
    if (!optionalList.isPresent()) {
      return Optional.empty();
    }
    List<?> list = (List<?>) optionalList.get();
    return indexList(list, index);
  }

  private static Object listOptionalFirst(Collection<?> list) {
    return Optional.ofNullable(Iterables.getFirst(list, null));
  }

  private static Object listOptionalLast(Collection<?> list) {
    return Optional.ofNullable(Iterables.getLast(list, null));
  }
}
