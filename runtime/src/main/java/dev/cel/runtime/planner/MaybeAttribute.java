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

import com.google.common.collect.ImmutableList;
import com.google.errorprone.annotations.Immutable;
import dev.cel.runtime.AccumulatedUnknowns;
import dev.cel.runtime.GlobalResolver;
import java.util.Optional;

/**
 * An attribute that attempts to resolve a variable against a list of potential namespaced
 * attributes. This is used during parsed-only evaluation.
 */
@Immutable
final class MaybeAttribute implements Attribute {
  private final AttributeFactory attrFactory;
  private final ImmutableList<NamespacedAttribute> attributes;

  @Override
  public Object resolve(long exprId, GlobalResolver ctx, ExecutionFrame frame) {
    MissingAttribute maybeError = null;
    for (NamespacedAttribute attr : attributes) {
      Object value = attr.resolve(exprId, ctx, frame);
      if (value == null) {
        continue;
      }

      if (value instanceof MissingAttribute) {
        maybeError = (MissingAttribute) value;
        // When the variable is missing in a maybe attribute, defer erroring.
        // The variable may exist in other namespaced attributes.
        continue;
      }

      return value;
    }

    return maybeError;
  }

  @Override
  public Attribute addQualifier(Qualifier qualifier) {
    Object strQualifier = qualifier.value();
    ImmutableList.Builder<String> augmentedNamesBuilder = ImmutableList.builder();
    ImmutableList.Builder<NamespacedAttribute> attributesBuilder = ImmutableList.builder();
    for (NamespacedAttribute attr : attributes) {
      if (strQualifier instanceof String && attr.qualifiers().isEmpty()) {
        for (String varName : attr.candidateVariableNames()) {
          augmentedNamesBuilder.add(varName + "." + strQualifier);
        }
      }

      attributesBuilder.add(attr.addQualifier(qualifier));
    }
    ImmutableList<String> augmentedNames = augmentedNamesBuilder.build();
    ImmutableList.Builder<NamespacedAttribute> namespacedAttributeBuilder = ImmutableList.builder();
    if (!augmentedNames.isEmpty()) {
      namespacedAttributeBuilder.add(
          attrFactory.newAbsoluteAttribute(augmentedNames.toArray(new String[0])));
    }

    namespacedAttributeBuilder.addAll(attributesBuilder.build());
    return new MaybeAttribute(attrFactory, namespacedAttributeBuilder.build());
  }

  /**
   * A known value resolved by any candidate takes precedence over an unknown reported by another.
   * Candidates are therefore scanned exhaustively rather than short-circuiting on the first
   * unknown, because {@link #addQualifier} front-loads the name-augmented candidates (for example
   * {@code b.field}) ahead of the qualified originals (for example {@code a.b} plus qualifier
   * {@code field}) that may well resolve to a concrete value.
   */
  @Override
  public Optional<AccumulatedUnknowns> findUnknown(
      long exprId, GlobalResolver resolver, ExecutionFrame frame) {
    if (!frame.partialVars().isPresent()) {
      return Optional.empty();
    }
    AccumulatedUnknowns unknown = null;
    for (int i = 0; i < attributes.size(); i++) {
      Object result =
          attributes.get(i).resolveInternal(exprId, resolver, frame, /* applyQualifiers= */ false);
      if (result == null || result instanceof MissingAttribute) {
        continue;
      }
      if (result instanceof AccumulatedUnknowns) {
        if (unknown == null) {
          unknown = (AccumulatedUnknowns) result;
        }
        continue;
      }
      return Optional.empty();
    }
    return Optional.ofNullable(unknown);
  }

  MaybeAttribute(AttributeFactory attrFactory, ImmutableList<NamespacedAttribute> attributes) {
    this.attrFactory = attrFactory;
    this.attributes = attributes;
  }
}
