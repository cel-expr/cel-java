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

package dev.cel.extensions;

import static com.google.common.base.Preconditions.checkArgument;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.errorprone.annotations.Immutable;
import dev.cel.common.CelOptions;
import dev.cel.common.internal.ComparisonFunctions;
import dev.cel.common.values.CelByteString;
import dev.cel.runtime.CelFunctionBinding;
import dev.cel.runtime.CelLiteRuntimeBuilder;
import dev.cel.runtime.CelLiteRuntimeLibrary;
import dev.cel.runtime.RuntimeEquality;
import dev.cel.runtime.RuntimeHelpers;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/** Runtime implementation of CEL List extension functions. */
@Immutable
public final class CelListsRuntimeLibrary implements CelLiteRuntimeLibrary {

  private static final CelObjectComparator OBJECT_COMPARATOR = new CelObjectComparator();
  private static final ImmutableList<String> SORT_BY_KEY_TYPE_NAMES =
      ImmutableList.of("int", "uint", "double", "bool", "string", "bytes", "duration", "timestamp");

  /** Enumeration of functions for List runtime extension. */
  public enum Function {
    SLICE("slice"),
    FLATTEN("flatten"),
    RANGE("lists.range"),
    DISTINCT("distinct"),
    REVERSE("reverse"),
    SORT("sort"),
    SORT_BY("@sortByAssociatedKeys");

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
        return ImmutableSet.of(Function.SLICE);
      case 1:
        return ImmutableSet.of(Function.SLICE, Function.FLATTEN);
      case 2:
      case Integer.MAX_VALUE:
        return ImmutableSet.copyOf(Function.values());
      default:
        throw new IllegalArgumentException("Unsupported 'lists' extension version " + version);
    }
  }

  /**
   * Returns the latest version of the 'lists' runtime functions using {@link CelOptions#DEFAULT}.
   */
  public static CelListsRuntimeLibrary lists() {
    return lists(CelOptions.DEFAULT);
  }

  /**
   * Returns the specified version of the 'lists' runtime functions using {@link
   * CelOptions#DEFAULT}.
   */
  public static CelListsRuntimeLibrary lists(int version) {
    return lists(CelOptions.DEFAULT, version);
  }

  /**
   * Returns the 'lists' runtime functions with only the specified functions using {@link
   * CelOptions#DEFAULT}.
   */
  public static CelListsRuntimeLibrary lists(Function... functions) {
    return lists(CelOptions.DEFAULT, functions);
  }

  /**
   * Returns the 'lists' runtime functions with only the specified functions using {@link
   * CelOptions#DEFAULT}.
   */
  public static CelListsRuntimeLibrary lists(Set<Function> functions) {
    return lists(CelOptions.DEFAULT, functions);
  }

  /** Returns the latest version of the 'lists' runtime functions. */
  public static CelListsRuntimeLibrary lists(CelOptions celOptions) {
    return lists(celOptions, Integer.MAX_VALUE);
  }

  /** Returns the specified version of the 'lists' runtime functions. */
  public static CelListsRuntimeLibrary lists(CelOptions celOptions, int version) {
    return lists(celOptions, getFunctionsForVersion(version));
  }

  /** Returns the 'lists' runtime functions with only the specified functions. */
  public static CelListsRuntimeLibrary lists(CelOptions celOptions, Function... functions) {
    return lists(celOptions, ImmutableSet.copyOf(functions));
  }

  /** Returns the 'lists' runtime functions with only the specified functions. */
  public static CelListsRuntimeLibrary lists(CelOptions celOptions, Set<Function> functions) {
    RuntimeEquality runtimeEquality = RuntimeEquality.create(RuntimeHelpers.create(), celOptions);
    return new CelListsRuntimeLibrary(runtimeEquality, functions);
  }

  private final RuntimeEquality runtimeEquality;
  private final ImmutableSet<Function> functions;

  CelListsRuntimeLibrary(RuntimeEquality runtimeEquality, int version) {
    this(runtimeEquality, getFunctionsForVersion(version));
  }

  CelListsRuntimeLibrary(RuntimeEquality runtimeEquality, Set<Function> functions) {
    this.runtimeEquality = runtimeEquality;
    this.functions = ImmutableSet.copyOf(functions);
  }

  @Override
  public void setRuntimeOptions(CelLiteRuntimeBuilder runtimeBuilder) {
    runtimeBuilder.addFunctionBindings(newFunctionBindings());
  }

  @SuppressWarnings("unchecked")
  public ImmutableSet<CelFunctionBinding> newFunctionBindings() {
    ImmutableSet.Builder<CelFunctionBinding> bindingBuilder = ImmutableSet.builder();
    for (Function function : functions) {
      switch (function) {
        case SLICE:
          bindingBuilder.addAll(
              CelFunctionBinding.fromOverloads(
                  function.getFunction(),
                  CelFunctionBinding.from(
                      "list_slice",
                      ImmutableList.of(Collection.class, Long.class, Long.class),
                      (args) -> {
                        Collection<Object> target = (Collection<Object>) args[0];
                        long from = (Long) args[1];
                        long to = (Long) args[2];
                        return slice(target, from, to);
                      })));
          break;
        case FLATTEN:
          bindingBuilder.addAll(
              CelFunctionBinding.fromOverloads(
                  function.getFunction(),
                  CelFunctionBinding.from(
                      "list_flatten", Collection.class, list -> flatten(list, 1)),
                  CelFunctionBinding.from(
                      "list_flatten_list_int",
                      Collection.class,
                      Long.class,
                      CelListsRuntimeLibrary::flatten)));
          break;
        case RANGE:
          bindingBuilder.addAll(
              CelFunctionBinding.fromOverloads(
                  function.getFunction(),
                  CelFunctionBinding.from(
                      "lists_range", Long.class, CelListsRuntimeLibrary::genRange)));
          break;
        case DISTINCT:
          bindingBuilder.addAll(
              CelFunctionBinding.fromOverloads(
                  function.getFunction(),
                  CelFunctionBinding.from(
                      "list_distinct",
                      Collection.class,
                      (list) -> distinct(list, runtimeEquality))));
          break;
        case REVERSE:
          bindingBuilder.addAll(
              CelFunctionBinding.fromOverloads(
                  function.getFunction(),
                  CelFunctionBinding.from(
                      "list_reverse", Collection.class, CelListsRuntimeLibrary::reverse)));
          break;
        case SORT:
          bindingBuilder.addAll(
              CelFunctionBinding.fromOverloads(
                  function.getFunction(),
                  CelFunctionBinding.from(
                      "list_sort", Collection.class, CelListsRuntimeLibrary::sort)));
          break;
        case SORT_BY:
          for (String typeName : SORT_BY_KEY_TYPE_NAMES) {
            bindingBuilder.addAll(
                CelFunctionBinding.fromOverloads(
                    function.getFunction(),
                    CelFunctionBinding.from(
                        String.format("list_%s_sortByAssociatedKeys", typeName),
                        Collection.class,
                        Collection.class,
                        CelListsRuntimeLibrary::sortByAssociatedKeys)));
          }
          break;
      }
    }
    return bindingBuilder.build();
  }

  private static ImmutableList<Object> slice(Collection<Object> list, long from, long to) {
    Preconditions.checkArgument(from >= 0 && to >= 0, "Negative indexes not supported");
    Preconditions.checkArgument(to >= from, "Start index must be less than or equal to end index");
    Preconditions.checkArgument(to <= list.size(), "List is length %s", list.size());
    if (list instanceof List) {
      List<Object> subList = ((List<Object>) list).subList((int) from, (int) to);
      if (subList instanceof ImmutableList) {
        return (ImmutableList<Object>) subList;
      }
      return ImmutableList.copyOf(subList);
    } else {
      ImmutableList.Builder<Object> builder = ImmutableList.builder();
      long index = 0;
      for (Iterator<Object> iterator = list.iterator(); iterator.hasNext(); index++) {
        Object element = iterator.next();
        if (index >= to) {
          break;
        }
        if (index >= from) {
          builder.add(element);
        }
      }
      return builder.build();
    }
  }

  @SuppressWarnings("unchecked")
  private static ImmutableList<Object> flatten(Collection<Object> list, long depth) {
    Preconditions.checkArgument(depth >= 0, "Level must be non-negative");
    ImmutableList.Builder<Object> builder = ImmutableList.builder();
    for (Object element : list) {
      if (!(element instanceof Collection) || depth == 0) {
        builder.add(element);
      } else {
        Collection<Object> listItem = (Collection<Object>) element;
        builder.addAll(flatten(listItem, depth - 1));
      }
    }

    return builder.build();
  }

  public static ImmutableList<Long> genRange(long end) {
    checkArgument(end >= 0, "lists.range: size must be non-negative, got %s", end);
    checkArgument(end <= 1_000_000, "lists.range: size %s exceeds maximum allowed (1000000)", end);

    ImmutableList.Builder<Long> builder = ImmutableList.builderWithExpectedSize((int) end);
    for (long i = 0; i < end; i++) {
      builder.add(i);
    }
    return builder.build();
  }

  private static class RuntimeEqualityObjectWrapper {
    private final Object object;
    private final int hashCode;
    private final RuntimeEquality runtimeEquality;

    RuntimeEqualityObjectWrapper(Object object, RuntimeEquality runtimeEquality) {
      this.object = object;
      this.runtimeEquality = runtimeEquality;
      this.hashCode = runtimeEquality.hashCode(object);
    }

    @Override
    public int hashCode() {
      return hashCode;
    }

    @Override
    public boolean equals(Object obj) {
      if (!(obj instanceof RuntimeEqualityObjectWrapper)) {
        return false;
      }
      return runtimeEquality.objectEquals(object, ((RuntimeEqualityObjectWrapper) obj).object);
    }
  }

  private static ImmutableList<Object> distinct(
      Collection<Object> list, RuntimeEquality runtimeEquality) {
    int size = list.size();
    ImmutableList.Builder<Object> builder = ImmutableList.builderWithExpectedSize(size);
    Set<RuntimeEqualityObjectWrapper> distinctValues = Sets.newHashSetWithExpectedSize(size);
    for (Object element : list) {
      if (distinctValues.add(new RuntimeEqualityObjectWrapper(element, runtimeEquality))) {
        builder.add(element);
      }
    }
    return builder.build();
  }

  private static List<Object> reverse(Collection<Object> list) {
    if (list instanceof List) {
      return Lists.reverse((List<Object>) list);
    } else {
      ImmutableList.Builder<Object> builder = ImmutableList.builderWithExpectedSize(list.size());
      Object[] objects = list.toArray();
      for (int i = objects.length - 1; i >= 0; i--) {
        builder.add(objects[i]);
      }
      return builder.build();
    }
  }

  private static ImmutableList<Object> sort(Collection<Object> objects) {
    if (objects.isEmpty()) {
      return ImmutableList.of();
    }
    if (objects.size() == 1) {
      Object single = objects.iterator().next();
      OBJECT_COMPARATOR.compare(single, single);
      return ImmutableList.of(single);
    }
    return ImmutableList.sortedCopyOf(OBJECT_COMPARATOR, objects);
  }

  private static class CelObjectComparator implements Comparator<Object> {

    CelObjectComparator() {}

    @SuppressWarnings({"unchecked"})
    @Override
    public int compare(Object o1, Object o2) {
      if (o1 instanceof Number && o2 instanceof Number) {
        return ComparisonFunctions.numericCompare((Number) o1, (Number) o2);
      }
      if (o1 instanceof CelByteString && o2 instanceof CelByteString) {
        return CelByteString.unsignedLexicographicalComparator()
            .compare((CelByteString) o1, (CelByteString) o2);
      }

      if (!(o1 instanceof Comparable) || !(o2 instanceof Comparable)) {
        throw new IllegalArgumentException("List elements must be comparable");
      }
      if (o1.getClass() != o2.getClass()) {
        throw new IllegalArgumentException("List elements must have the same type");
      }
      return ((Comparable) o1).compareTo(o2);
    }
  }

  private static ImmutableList<Object> sortByAssociatedKeys(
      Collection<Object> list, Collection<Object> keys) {
    checkArgument(
        list.size() == keys.size(),
        "@sortByAssociatedKeys() expected a list of the same size as the associated keys"
            + " list, but got %s in list and %s in keys",
        list.size(),
        keys.size());

    int listSize = list.size();
    if (listSize == 0) {
      return ImmutableList.of();
    }

    Object[] listArray = list.toArray();
    Object[] keysArray = keys.toArray();
    if (listSize == 1) {
      OBJECT_COMPARATOR.compare(keysArray[0], keysArray[0]);
      return ImmutableList.of(listArray[0]);
    }

    Integer[] indices = new Integer[listSize];
    for (int i = 0; i < listSize; i++) {
      indices[i] = i;
    }

    Arrays.sort(indices, (i1, i2) -> OBJECT_COMPARATOR.compare(keysArray[i1], keysArray[i2]));

    ImmutableList.Builder<Object> builder = ImmutableList.builderWithExpectedSize(listSize);
    for (int index : indices) {
      builder.add(listArray[index]);
    }
    return builder.build();
  }
}
