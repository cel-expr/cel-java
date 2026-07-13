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

package dev.cel.runtime.planner;

import com.google.common.collect.ImmutableSet;
import com.google.errorprone.annotations.Immutable;
import dev.cel.common.exceptions.CelAttributeNotFoundException;
import dev.cel.runtime.GlobalResolver;

/** Represents a missing attribute that is surfaced while resolving a struct field or a map key. */
@Immutable
final class MissingAttribute implements Attribute {

  private final ImmutableSet<String> missingAttributes;
  private final Kind kind;

  @Override
  public Object resolve(long exprId, GlobalResolver ctx, ExecutionFrame frame) {
    switch (kind) {
      case ATTRIBUTE_NOT_FOUND:
        throw CelAttributeNotFoundException.forMissingAttributes(missingAttributes);
      case FIELD_NOT_FOUND:
        throw CelAttributeNotFoundException.forFieldResolution(missingAttributes);
    }

    throw new IllegalArgumentException("Unexpected kind: " + kind);
  }

  @Override
  public Attribute addQualifier(Qualifier qualifier) {
    throw new UnsupportedOperationException("Unsupported operation");
  }

  static MissingAttribute newMissingAttribute(ImmutableSet<String> attributeNames) {
    return new MissingAttribute(attributeNames, Kind.ATTRIBUTE_NOT_FOUND);
  }

  static MissingAttribute newMissingField(String... attributeNames) {
    return newMissingField(ImmutableSet.copyOf(attributeNames));
  }

  static MissingAttribute newMissingField(ImmutableSet<String> attributeNames) {
    return new MissingAttribute(attributeNames, Kind.FIELD_NOT_FOUND);
  }

  private MissingAttribute(ImmutableSet<String> missingAttributes, Kind kind) {
    this.missingAttributes = missingAttributes;
    this.kind = kind;
  }

  private enum Kind {
    ATTRIBUTE_NOT_FOUND,
    FIELD_NOT_FOUND
  }
}
