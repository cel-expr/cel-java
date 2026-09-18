// Copyright 2022 Google LLC
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

import com.google.common.primitives.UnsignedLong;
import com.google.errorprone.annotations.Immutable;
import com.google.protobuf.MessageLiteOrBuilder;
import dev.cel.common.CelOptions;
import dev.cel.common.annotations.Internal;
import dev.cel.common.exceptions.CelAttributeNotFoundException;
import dev.cel.common.internal.ComparisonFunctions;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** RuntimeEquality contains methods for performing CEL related equality checks. */
@Immutable
@Internal
public class RuntimeEquality {
  protected final RuntimeHelpers runtimeHelpers;
  protected final CelOptions celOptions;

  public static RuntimeEquality create(RuntimeHelpers runtimeHelper, CelOptions celOptions) {
    return new RuntimeEquality(runtimeHelper, celOptions);
  }

  // Functions
  // =========

  /** Determine whether the {@code list} contains the given {@code value}. */
  public <A> boolean inList(List<A> list, A value) {
    if (list.contains(value)) {
      return true;
    }
    if (value instanceof Number) {
      for (A elem : list) {
        if (objectEquals(elem, value)) {
          return true;
        }
      }
    }
    return false;
  }

  /** Bound-checked indexing of maps. */
  @SuppressWarnings("unchecked")
  public <A, B> B indexMap(Map<A, B> map, A index) {
    Optional<Object> value = findInMap(map, index);
    // Use this method rather than the standard 'orElseThrow' method because of the unchecked cast.
    if (value.isPresent()) {
      return (B) value.get();
    }

    throw CelAttributeNotFoundException.of(index.toString());
  }

  /** Determine whether the {@code map} contains the given {@code key}. */
  public <A, B> boolean inMap(Map<A, B> map, A key) {
    return findInMap(map, key).isPresent();
  }

  public Optional<Object> findInMap(Map<?, ?> map, Object index) {
    if (celOptions.disableCelStandardEquality()) {
      return Optional.ofNullable(map.get(index));
    }

    if (index instanceof MessageLiteOrBuilder) {
      index = runtimeHelpers.adaptProtoToValue((MessageLiteOrBuilder) index);
    }
    Object v = map.get(index);
    if (v != null) {
      return Optional.of(v);
    }
    if (index instanceof Long) {
      return longToUnsignedLossless((Long) index).map(map::get);
    } else if (index instanceof UnsignedLong) {
      return unsignedToLongLossless((UnsignedLong) index).map(map::get);
    } else if (index instanceof Number) {
      Number numberIndex = (Number) index;
      Optional<Object> indexValue = RuntimeHelpers.doubleToLongLossless(numberIndex).map(map::get);
      // Early return rather than use 'or()' since or is not JDK 1.8 compatible.
      if (indexValue.isPresent()) {
        return indexValue;
      }
      return doubleToUnsignedLossless(numberIndex).map(map::get);
    }
    return Optional.empty();
  }

  // Object equality
  // ===================

  /**
   * CEL implements homogeneous equality where two values are only comparable if they have the same
   * type, otherwise an error is thrown.
   *
   * <p>If the values are of different type, the comparison fails with an error. For aggregate
   * types, equality
   *
   * <p>If lists are of different length, return false, otherwise take the CEL logical AND of
   * homogeneous equality of the elements by list position:
   *
   * <ul>
   *   <li>If any element comparison returns false, the list comparison returns false.
   *   <li>Otherwise, if all element comparisons return true, the list comparison returns true.
   *   <li>Otherwise, there are one or more errors, one of which is re-thrown.
   * </ul>
   *
   * <p>If maps have different key sets, return false, otherwise take the CEL logical AND of
   * homogeneous equality of the values by map key:
   *
   * <ul>
   *   <li>If any value comparison returns false, the map comparison returns false.
   *   <li>Otherwise, if all value comparisons return true, the map comparison returns true.
   *   <li>Otherwise, there are one or more errors, one of which is re-thrown.
   * </ul>
   *
   * <p>Heterogeneous equality differs from homogeneous equality in that two objects may be
   * comparable even if they are not of the same type, where type differences are usually trivially
   * false.
   */
  @SuppressWarnings({"rawtypes", "unchecked"})
  public boolean objectEquals(Object x, Object y) {
    if (celOptions.disableCelStandardEquality()) {
      return Objects.equals(x, y);
    }
    if (x == y) {
      return true;
    }
    x = runtimeHelpers.adaptValue(x);
    y = runtimeHelpers.adaptValue(y);
    if (x instanceof Number) {
      if (!(y instanceof Number)) {
        return false;
      }
      return ComparisonFunctions.numericEquals((Number) x, (Number) y);
    }
    if (celOptions.enableProtoDifferencerEquality()) {
      if (x instanceof MessageLiteOrBuilder) {
        if (!(y instanceof MessageLiteOrBuilder)) {
          return false;
        }
        // TODO: Implement when CelLiteDescriptor is available
        throw new UnsupportedOperationException(
            "Proto Differencer equality is not supported for MessageLite.");
      }
    }
    if (x instanceof Iterable) {
      if (!(y instanceof Iterable)) {
        return false;
      }
      Iterable<?> xIter = (Iterable<?>) x;
      Iterable<?> yIter = (Iterable<?>) y;
      Iterator<?> yElems = yIter.iterator();
      IllegalArgumentException e = null;
      for (Object xElem : xIter) {
        if (!yElems.hasNext()) {
          return false;
        }
        try {
          if (!objectEquals(xElem, yElems.next())) {
            return false;
          }
        } catch (IllegalArgumentException iae) {
          e = iae;
        }
      }
      if (yElems.hasNext()) {
        return false;
      }
      if (e != null) {
        throw e;
      }
      return true;
    }
    if (x instanceof Map) {
      if (!(y instanceof Map)) {
        return false;
      }
      Map xMap = (Map) x;
      Map yMap = (Map) y;
      if (xMap.size() != yMap.size()) {
        return false;
      }
      IllegalArgumentException e = null;
      Set<Entry> entrySet = xMap.entrySet();
      for (Map.Entry xEntry : entrySet) {
        Optional<Object> yVal = findInMap(yMap, xEntry.getKey());
        // Use isPresent() rather than isEmpty() to stay backwards compatible with Java 8.
        if (!yVal.isPresent()) {
          return false;
        }
        try {
          if (!objectEquals(xEntry.getValue(), yVal.get())) {
            return false;
          }
        } catch (IllegalArgumentException iae) {
          e = iae;
        }
      }
      if (e != null) {
        throw e;
      }
      return true;
    }
    return Objects.equals(x, y);
  }

  /**
   * Returns the hash code consistent with the {@link #objectEquals(Object, Object)} method. For
   * example, {@code hashCode(1) == hashCode(1.0)} since {@code objectEquals(1, 1.0)} is true.
   */
  public int hashCode(Object object) {
    if (object == null) {
      return 0;
    }

    if (celOptions.disableCelStandardEquality()) {
      return Objects.hashCode(object);
    }

    object = runtimeHelpers.adaptValue(object);
    if (object instanceof Number) {
      double value = ((Number) object).doubleValue();
      // Normalize -0.0 to 0.0. objectEquals reports the two as equal, so they must hash alike.
      return Double.hashCode(value == 0.0d ? 0.0d : value);
    }
    if (object instanceof Iterable) {
      int h = 1;
      Iterable<?> iter = (Iterable<?>) object;
      for (Object elem : iter) {
        h = h * 31 + hashCode(elem);
      }
      return h;
    }
    if (object instanceof Map) {
      int h = 0;
      for (Map.Entry<?, ?> entry : ((Map<?, ?>) object).entrySet()) {
        h += hashCode(entry.getKey()) ^ hashCode(entry.getValue());
      }
      return h;
    }
    return Objects.hashCode(object);
  }

  private static Optional<UnsignedLong> doubleToUnsignedLossless(Number v) {
    Optional<UnsignedLong> conv = RuntimeHelpers.doubleToUnsignedChecked(v.doubleValue());
    return conv.map(ul -> ul.longValue() == v.doubleValue() ? ul : null);
  }

  private static Optional<UnsignedLong> longToUnsignedLossless(long v) {
    if (v >= 0) {
      return Optional.of(UnsignedLong.valueOf(v));
    }
    return Optional.empty();
  }

  private static Optional<Long> unsignedToLongLossless(UnsignedLong v) {
    if (v.compareTo(UnsignedLong.valueOf(Long.MAX_VALUE)) <= 0) {
      return Optional.of(v.longValue());
    }
    return Optional.empty();
  }

  RuntimeEquality(RuntimeHelpers runtimeHelpers, CelOptions celOptions) {
    this.runtimeHelpers = runtimeHelpers;
    this.celOptions = celOptions;
  }
}
