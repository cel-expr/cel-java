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

import com.google.common.collect.ImmutableSet;
import com.google.errorprone.annotations.Immutable;
import dev.cel.common.CelOptions;
import dev.cel.runtime.CelFunctionBinding;
import dev.cel.runtime.CelInternalLiteRuntimeLibrary;
import dev.cel.runtime.CelLiteRuntimeBuilder;
import dev.cel.runtime.RuntimeEquality;
import java.util.Collection;
import java.util.Iterator;
import java.util.Set;

/** Runtime implementation of CEL Set extension functions. */
@Immutable
public final class CelSetsRuntimeLibrary implements CelInternalLiteRuntimeLibrary {

  /** Enumeration of functions for Set runtime extension. */
  public enum Function {
    CONTAINS("sets.contains"),
    EQUIVALENT("sets.equivalent"),
    INTERSECTS("sets.intersects");

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
        return ImmutableSet.copyOf(Function.values());
      default:
        throw new IllegalArgumentException("Unsupported 'sets' extension version " + version);
    }
  }

  private static final CelSetsRuntimeLibrary VERSION_0 =
      new CelSetsRuntimeLibrary(ImmutableSet.copyOf(Function.values()));

  /** Returns the latest version of the 'sets' runtime functions. */
  public static CelSetsRuntimeLibrary sets() {
    return VERSION_0;
  }

  /** Returns the specified version of the 'sets' runtime functions. */
  public static CelSetsRuntimeLibrary sets(int version) {
    if (version == 0 || version == Integer.MAX_VALUE) {
      return VERSION_0;
    }
    return new CelSetsRuntimeLibrary(getFunctionsForVersion(version));
  }

  /** Returns the 'sets' runtime functions with only the specified functions. */
  public static CelSetsRuntimeLibrary sets(Function... functions) {
    return sets(ImmutableSet.copyOf(functions));
  }

  /** Returns the 'sets' runtime functions with only the specified functions. */
  public static CelSetsRuntimeLibrary sets(Set<Function> functions) {
    return new CelSetsRuntimeLibrary(functions);
  }

  /**
   * Returns the latest version of the 'sets' runtime functions using {@link CelOptions#DEFAULT}.
   *
   * @deprecated Options are now plumbed via {@link CelInternalLiteRuntimeLibrary}. Use {@link
   *     #sets()} instead.
   */
  @Deprecated
  public static CelSetsRuntimeLibrary sets(CelOptions celOptions) {
    return sets();
  }

  /**
   * Returns the specified version of the 'sets' runtime functions.
   *
   * @deprecated Options are now plumbed via {@link CelInternalLiteRuntimeLibrary}. Use {@link
   *     #sets(int)} instead.
   */
  @Deprecated
  public static CelSetsRuntimeLibrary sets(CelOptions celOptions, int version) {
    return sets(version);
  }

  /**
   * Returns the 'sets' runtime functions with only the specified functions.
   *
   * @deprecated Options are now plumbed via {@link CelInternalLiteRuntimeLibrary}. Use {@link
   *     #sets(Function...)} instead.
   */
  @Deprecated
  public static CelSetsRuntimeLibrary sets(CelOptions celOptions, Function... functions) {
    return sets(functions);
  }

  /**
   * Returns the 'sets' runtime functions with only the specified functions.
   *
   * @deprecated Options are now plumbed via {@link CelInternalLiteRuntimeLibrary}. Use {@link
   *     #sets(Set)} instead.
   */
  @Deprecated
  public static CelSetsRuntimeLibrary sets(CelOptions celOptions, Set<Function> functions) {
    return sets(functions);
  }

  private final ImmutableSet<Function> functions;

  CelSetsRuntimeLibrary(Set<Function> functions) {
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

  /** Creates the {@link CelFunctionBinding}s for the configured set functions. */
  public ImmutableSet<CelFunctionBinding> newFunctionBindings(RuntimeEquality runtimeEquality) {
    ImmutableSet.Builder<CelFunctionBinding> bindingBuilder = ImmutableSet.builder();
    for (Function function : functions) {
      switch (function) {
        case CONTAINS:
          bindingBuilder.addAll(
              CelFunctionBinding.fromOverloads(
                  function.getFunction(),
                  CelFunctionBinding.from(
                      "list_sets_contains_list",
                      Collection.class,
                      Collection.class,
                      (listA, listB) -> containsAll(listA, listB, runtimeEquality))));
          break;
        case EQUIVALENT:
          bindingBuilder.addAll(
              CelFunctionBinding.fromOverloads(
                  function.getFunction(),
                  CelFunctionBinding.from(
                      "list_sets_equivalent_list",
                      Collection.class,
                      Collection.class,
                      (listA, listB) ->
                          containsAll(listA, listB, runtimeEquality)
                              && containsAll(listB, listA, runtimeEquality))));
          break;
        case INTERSECTS:
          bindingBuilder.addAll(
              CelFunctionBinding.fromOverloads(
                  function.getFunction(),
                  CelFunctionBinding.from(
                      "list_sets_intersects_list",
                      Collection.class,
                      Collection.class,
                      (listA, listB) -> setIntersects(listA, listB, runtimeEquality))));
          break;
      }
    }

    return bindingBuilder.build();
  }

  /**
   * This implementation iterates over the specified collection, checking each element returned by
   * the iterator in turn to see if it's contained in this collection. If all elements are so
   * contained <tt>true</tt> is returned, otherwise <tt>false</tt>.
   *
   * <p>This is picked verbatim as implemented in the Java standard library
   * Collections.containsAll() method.
   *
   * @see #contains(Object, Collection, RuntimeEquality)
   */
  private static boolean containsAll(
      Collection<?> list, Collection<?> subList, RuntimeEquality runtimeEquality) {
    for (Object e : subList) {
      if (!contains(e, list, runtimeEquality)) {
        return false;
      }
    }
    return true;
  }

  /**
   * This implementation iterates over the elements in the collection, checking each element in turn
   * for equality with the specified element.
   *
   * <p>This is picked verbatim as implemented in the Java standard library Collections.contains()
   * method.
   *
   * <p>Source: <a
   * href="https://hg.openjdk.org/jdk8u/jdk8u-dev/jdk/file/c5d02f908fb2/src/share/classes/java/util/AbstractCollection.java#l98">OpenJDK
   * AbstractCollection<a>
   */
  private static boolean contains(Object o, Collection<?> list, RuntimeEquality runtimeEquality) {
    Iterator<?> it = list.iterator();
    if (o == null) {
      while (it.hasNext()) {
        if (it.next() == null) {
          return true;
        }
      }
    } else {
      while (it.hasNext()) {
        Object item = it.next();
        if (objectsEquals(item, o, runtimeEquality)) {
          return true;
        }
      }
    }
    return false;
  }

  private static boolean objectsEquals(Object o1, Object o2, RuntimeEquality runtimeEquality) {
    return runtimeEquality.objectEquals(o1, o2);
  }

  private static boolean setIntersects(
      Collection<?> listA, Collection<?> listB, RuntimeEquality runtimeEquality) {
    if (listA.isEmpty() || listB.isEmpty()) {
      return false;
    }
    for (Object element : listB) {
      if (contains(element, listA, runtimeEquality)) {
        return true;
      }
    }
    return false;
  }
}
