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
import com.google.common.collect.ImmutableSet;
import com.google.errorprone.annotations.Immutable;
import dev.cel.common.CelContainer;
import dev.cel.common.types.CelTypeProvider;
import dev.cel.common.values.CelValueConverter;
import org.jspecify.annotations.Nullable;

@Immutable
final class AttributeFactory {

  private final CelContainer container;
  private final CelTypeProvider typeProvider;
  private final CelValueConverter celValueConverter;

  NamespacedAttribute newAbsoluteAttribute(String... names) {
    return NamespacedAttribute.create(typeProvider, celValueConverter, ImmutableSet.copyOf(names));
  }

  RelativeAttribute newRelativeAttribute(PlannedInterpretable operand) {
    return new RelativeAttribute(operand, celValueConverter, /* rootAttribute= */ null);
  }

  RelativeAttribute newRelativeAttribute(
      PlannedInterpretable operand, @Nullable Attribute rootAttribute) {
    return new RelativeAttribute(operand, celValueConverter, rootAttribute);
  }

  MaybeAttribute newMaybeAttribute(String name) {
    // When there's a single name with a dot prefix, it indicates that the 'maybe' attribute is a
    // globally namespaced identifier.
    // Otherwise, the candidate names resolved from the container should be inferred.
    ImmutableSet<String> names =
        name.startsWith(".") ? ImmutableSet.of(name) : container.resolveCandidateNames(name);

    return new MaybeAttribute(
        this, ImmutableList.of(NamespacedAttribute.create(typeProvider, celValueConverter, names)));
  }

  static AttributeFactory newAttributeFactory(
      CelContainer celContainer,
      CelTypeProvider typeProvider,
      CelValueConverter celValueConverter) {
    return new AttributeFactory(celContainer, typeProvider, celValueConverter);
  }

  private AttributeFactory(
      CelContainer container, CelTypeProvider typeProvider, CelValueConverter celValueConverter) {
    this.container = container;
    this.typeProvider = typeProvider;
    this.celValueConverter = celValueConverter;
  }
}
