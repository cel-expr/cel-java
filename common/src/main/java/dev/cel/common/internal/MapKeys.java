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

package dev.cel.common.internal;

import com.google.common.primitives.UnsignedLong;
import com.google.errorprone.annotations.CheckReturnValue;
import dev.cel.common.annotations.Internal;

/**
 * Checks for CEL map keys.
 *
 * <p>CEL Library Internals. Do Not Use.
 */
@CheckReturnValue
@Internal
public final class MapKeys {

  /** Whether {@code key} has a type CEL allows for map keys: int, uint, bool or string. */
  public static boolean isValidKeyType(Object key) {
    return key instanceof Long
        || key instanceof UnsignedLong
        || key instanceof Boolean
        || key instanceof String;
  }

  /**
   * Returns {@code key}, with an int and a uint of the same value, which are the same CEL map key,
   * made equal.
   */
  public static Object normalize(Object key) {
    return key instanceof Long && (Long) key >= 0 ? UnsignedLong.valueOf((Long) key) : key;
  }

  private MapKeys() {}
}
