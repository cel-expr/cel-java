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

package dev.cel.common.values;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.collect.ImmutableList;
import dev.cel.common.annotations.Internal;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * {@code CombinedCelValueConverter} delegates value conversion to a list of underlying {@link
 * CelValueConverter}s.
 */
@Internal
public final class CombinedCelValueConverter extends CelValueConverter {
  private final ImmutableList<CelValueConverter> converters;

  public static CombinedCelValueConverter combine(ImmutableList<CelValueConverter> converters) {
    return new CombinedCelValueConverter(converters);
  }

  private CombinedCelValueConverter(ImmutableList<CelValueConverter> converters) {
    this.converters = checkNotNull(converters);
  }

  @Override
  public @Nullable Object toRuntimeValue(Object value) {
    if (value == null) {
      return null;
    }

    // Let the base class handle CelValues, Optionals, Collections, Maps, and primitives.
    Object baseResult = super.toRuntimeValue(value);
    if (baseResult != value) {
      return baseResult;
    }

    // If the base class left the object unchanged (e.g. a raw POJO), try the delegates.
    for (CelValueConverter converter : converters) {
      Object result = converter.toRuntimeValue(value);
      if (result != value) {
        return result;
      }
    }

    return value;
  }

  @Override
  public @Nullable Object maybeUnwrap(Object value) {
    if (value == null) {
      return null;
    }

    // Let the base class handle standard unwrapping and container unrolling.
    Object baseResult = super.maybeUnwrap(value);
    if (baseResult != value) {
      return baseResult;
    }

    // Try delegates for specialized unwrapping.
    for (CelValueConverter converter : converters) {
      Object result = converter.maybeUnwrap(value);
      if (result != value) {
        return result;
      }
    }

    return value;
  }

  @Override
  public Optional<Boolean> messageEquals(Object x, Object y) {
    for (CelValueConverter converter : converters) {
      Optional<Boolean> result = converter.messageEquals(x, y);
      if (result.isPresent()) {
        return result;
      }
    }
    return Optional.empty();
  }
}
