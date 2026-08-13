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

package dev.cel.runtime.planner;

import com.google.errorprone.annotations.Immutable;
import dev.cel.runtime.CelAttribute;
import org.jspecify.annotations.Nullable;

/** Bundles a resolved value and its corresponding {@link CelAttribute} trail. */
@Immutable
final class AttributeResolution {

  @SuppressWarnings("Immutable")
  private final @Nullable Object value;

  private final @Nullable CelAttribute attribute;

  static AttributeResolution of(@Nullable Object value, @Nullable CelAttribute attribute) {
    return new AttributeResolution(value, attribute);
  }

  static AttributeResolution ofValue(@Nullable Object value) {
    return new AttributeResolution(value, null);
  }

  @Nullable Object value() {
    return value;
  }

  @Nullable CelAttribute attribute() {
    return attribute;
  }

  private AttributeResolution(@Nullable Object value, @Nullable CelAttribute attribute) {
    this.value = value;
    this.attribute = attribute;
  }
}
