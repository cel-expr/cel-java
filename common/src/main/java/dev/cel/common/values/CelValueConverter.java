// Copyright 2023 Google LLC
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

package dev.cel.common.values;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.errorprone.annotations.Immutable;
import dev.cel.common.annotations.Internal;
import dev.cel.common.exceptions.CelInvalidArgumentException;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.RandomAccess;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * {@code CelValueConverter} handles bidirectional conversion between native Java objects to {@link
 * CelValue}.
 *
 * <p>CEL Library Internals. Do Not Use.
 */
@SuppressWarnings("unchecked") // Unchecked cast of generics due to type-erasure (ex: MapValue).
@Internal
@Immutable
public class CelValueConverter {

  private static final CelValueConverter DEFAULT_INSTANCE = new CelValueConverter();

  @SuppressWarnings("Immutable") // Method reference is immutable
  private final Function<Object, Object> maybeUnwrapFunction;

  @SuppressWarnings("Immutable") // Method reference is immutable
  private final Function<Object, Object> toRuntimeValueFunction;

  public static CelValueConverter getDefaultInstance() {
    return DEFAULT_INSTANCE;
  }

  /**
   * Unwraps the {@code value} into its plain old Java Object representation.
   *
   * <p>The value may be a {@link CelValue}, a {@link Collection} or a {@link Map}.
   */
  public Object maybeUnwrap(Object value) {
    if (value instanceof CelValue || value instanceof CelPreAdaptedList) {
      return value instanceof CelValue ? unwrap((CelValue) value) : value;
    }

    return mapContainer(value, maybeUnwrapFunction);
  }

  /**
   * Maps a container (Collection or Map) by applying the provided mapper function to its elements.
   * Returns the original value if it's not a supported container.
   */
  protected Object mapContainer(Object value, Function<Object, Object> mapper) {

    // Zero allocation path for standard lists that support O(1) indexing
    // Generally, protobuf lists (backed by arrays) fall into this category
    if (value instanceof List && value instanceof RandomAccess) {
      List<Object> list = (List<Object>) value;
      for (int i = 0; i < list.size(); i++) {
        Object element = checkListElement(list.get(i), i);
        Object mapped = mapper.apply(element);

        if (mapped != element) {
          ImmutableList.Builder<Object> builder =
              ImmutableList.builderWithExpectedSize(list.size());
          for (int j = 0; j < i; j++) {
            builder.add(list.get(j));
          }
          builder.add(mapped);
          for (int j = i + 1; j < list.size(); j++) {
            builder.add(mapper.apply(checkListElement(list.get(j), j)));
          }
          return builder.build();
        }
      }

      // Zero allocations if unmodified
      return value;
    }

    // Fallback for lists that are unordered
    if (value instanceof Collection) {
      Collection<Object> collection = (Collection<Object>) value;
      ImmutableList.Builder<Object> builder =
          ImmutableList.builderWithExpectedSize(collection.size());
      int index = 0;
      for (Object element : collection) {
        builder.add(mapper.apply(checkListElement(element, index++)));
      }
      return builder.build();
    }

    if (value instanceof Map) {
      Map<Object, Object> map = (Map<Object, Object>) value;
      Iterator<Map.Entry<Object, Object>> iterator = map.entrySet().iterator();

      while (iterator.hasNext()) {
        Map.Entry<Object, Object> entry = iterator.next();
        checkMapEntry(entry);
        Object mappedKey = mapper.apply(entry.getKey());
        Object mappedValue = mapper.apply(entry.getValue());

        if (mappedKey != entry.getKey() || mappedValue != entry.getValue()) {
          ImmutableMap.Builder<Object, Object> builder =
              ImmutableMap.builderWithExpectedSize(map.size());

          for (Map.Entry<Object, Object> prevEntry : map.entrySet()) {
            if (prevEntry.getKey() == entry.getKey()) {
              break;
            }
            builder.put(mapper.apply(prevEntry.getKey()), mapper.apply(prevEntry.getValue()));
          }
          builder.put(mappedKey, mappedValue);
          while (iterator.hasNext()) {
            Map.Entry<Object, Object> nextEntry = iterator.next();
            checkMapEntry(nextEntry);
            builder.put(mapper.apply(nextEntry.getKey()), mapper.apply(nextEntry.getValue()));
          }
          return builder.buildOrThrow();
        }
      }
      return value;
    }

    return value;
  }

  public Object toRuntimeValue(Object value) {
    Preconditions.checkNotNull(value);

    if (value instanceof CelValue || value instanceof CelPreAdaptedList) {
      return value;
    }

    Object mapped = mapContainer(value, toRuntimeValueFunction);
    if (mapped != value) {
      return mapped;
    }

    if (value instanceof Optional) {
      Optional<Object> optionalValue = (Optional<Object>) value;
      return optionalValue
          .map(toRuntimeValueFunction)
          .map(OptionalValue::create)
          .orElse(OptionalValue.EMPTY);
    }

    return normalizePrimitive(value);
  }

  /**
   * Adapts {@code value} for an intermediate field selection hop.
   *
   * <p>{@link Map} instances are returned as-is to avoid O(N) whole-map normalization per hop; the
   * accessed entry is validated on lookup via {@link #findMapValue} or {@link #containsMapKey}.
   * Callers materializing a final evaluation result must use {@link #toRuntimeValue} instead.
   */
  public final Object toTraversalTarget(Object value) {
    if (value instanceof Map) {
      return value;
    }

    return toRuntimeValue(value);
  }

  /**
   * Returns the unadapted value bound to {@code key} in {@code map}, or {@link Optional#empty()} if
   * absent.
   *
   * @throws CelInvalidArgumentException if {@code key} is bound to {@code null}.
   */
  public static Optional<Object> findMapValue(Map<?, ?> map, Object key) {
    Object value = map.get(key);
    if (value != null) {
      return Optional.of(value);
    }

    if (map.containsKey(key)) {
      throw new CelInvalidArgumentException(
          String.format("Map value cannot be null for key: %s", key));
    }

    return Optional.empty();
  }

  /**
   * Returns whether {@code key} is present in {@code map}.
   *
   * @throws CelInvalidArgumentException if {@code key} is bound to {@code null}.
   */
  public static boolean containsMapKey(Map<?, ?> map, Object key) {
    if (map.get(key) != null) {
      return true;
    }

    if (map.containsKey(key)) {
      throw new CelInvalidArgumentException(
          String.format("Map value cannot be null for key: %s", key));
    }

    return false;
  }

  protected Object normalizePrimitive(Object value) {
    Preconditions.checkNotNull(value);

    if (value instanceof Integer) {
      return ((Integer) value).longValue();
    } else if (value instanceof byte[]) {
      return CelByteString.of((byte[]) value);
    } else if (value instanceof Float) {
      return ((Float) value).doubleValue();
    }

    return value;
  }

  /** Adapts a {@link CelValue} to a plain old Java Object. */
  private Object unwrap(CelValue celValue) {
    Preconditions.checkNotNull(celValue);

    if (celValue instanceof OptionalValue) {
      OptionalValue<Object, Object> optionalValue = (OptionalValue<Object, Object>) celValue;
      if (optionalValue.isZeroValue()) {
        return Optional.empty();
      }

      return Optional.of(maybeUnwrap(optionalValue.value()));
    }

    if (celValue instanceof ErrorValue) {
      return celValue;
    }

    return celValue.value();
  }

  private static void checkMapEntry(Map.Entry<?, ?> entry) {
    Object key = entry.getKey();
    if (key == null) {
      throw new CelInvalidArgumentException("Map key cannot be null.");
    }

    if (entry.getValue() == null) {
      throw new CelInvalidArgumentException(
          String.format("Map value cannot be null for key: %s", key));
    }
  }

  @CanIgnoreReturnValue
  private static Object checkListElement(@Nullable Object element, int index) {
    if (element == null) {
      throw new CelInvalidArgumentException(
          String.format("List element cannot be null at index: %d", index));
    }

    return element;
  }

  protected CelValueConverter() {
    this.maybeUnwrapFunction = this::maybeUnwrap;
    this.toRuntimeValueFunction = this::toRuntimeValue;
  }
}
