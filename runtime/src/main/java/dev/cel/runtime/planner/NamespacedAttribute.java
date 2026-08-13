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
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.errorprone.annotations.Immutable;
import dev.cel.common.types.CelType;
import dev.cel.common.types.CelTypeProvider;
import dev.cel.common.types.EnumType;
import dev.cel.common.types.SimpleType;
import dev.cel.common.types.TypeType;
import dev.cel.common.values.CelValueConverter;
import dev.cel.runtime.AccumulatedUnknowns;
import dev.cel.runtime.CelAttribute;
import dev.cel.runtime.CelAttributePattern;
import dev.cel.runtime.GlobalResolver;
import dev.cel.runtime.InterpreterUtil;
import dev.cel.runtime.PartialVars;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

@Immutable
final class NamespacedAttribute implements Attribute {
  private final boolean disambiguateNames;
  private final ImmutableMap<String, CelAttribute> candidateAttributes;
  private final ImmutableList<Qualifier> qualifiers;
  private final CelValueConverter celValueConverter;
  private final CelTypeProvider typeProvider;

  ImmutableList<Qualifier> qualifiers() {
    return qualifiers;
  }

  ImmutableSet<String> candidateVariableNames() {
    return candidateAttributes.keySet();
  }

  @Override
  public Object resolve(long exprId, GlobalResolver ctx, ExecutionFrame frame) {
    GlobalResolver inputVars = ctx;
    // Unwrap any local activations to ensure that we reach the variables provided as input
    // to the expression in the event that we need to disambiguate between global and local
    // variables.
    if (disambiguateNames) {
      inputVars = unwrapToNonLocal(ctx);
    }

    for (Map.Entry<String, CelAttribute> entry : candidateAttributes.entrySet()) {
      String name = entry.getKey();
      CelAttribute attr = entry.getValue();

      GlobalResolver resolver = ctx;
      if (disambiguateNames) {
        resolver = inputVars;
      }

      Object value = resolver.resolve(name);
      value = InterpreterUtil.maybeAdaptToAccumulatedUnknowns(value);

      PartialVars partialVars = frame.partialVars().orElse(null);

      if (partialVars != null && !isLocallyBound(resolver, name)) {
        ImmutableList<CelAttributePattern> patterns = partialVars.unknowns();
        // Avoid enhanced for loop to prevent UnmodifiableIterator from being allocated
        for (int i = 0; i < qualifiers.size(); i++) {
          attr = attr.qualify(CelAttribute.Qualifier.fromGeneric(qualifiers.get(i).value()));
        }

        CelAttributePattern partialMatch = findPartialMatchingPattern(attr, patterns).orElse(null);
        if (partialMatch != null) {
          return AccumulatedUnknowns.create(
              ImmutableList.of(exprId), ImmutableList.of(partialMatch.simplify(attr)));
        }
      }

      if (value != null) {
        return applyQualifiers(value, celValueConverter, qualifiers);
      }

      // Attempt to resolve the qualify type name if the name is not a variable identifier
      value = findIdent(name);
      if (value != null) {
        return value;
      }
    }

    return MissingAttribute.newMissingAttribute(candidateAttributes.keySet());
  }

  private @Nullable Object findIdent(String name) {
    CelType type = typeProvider.findType(name).orElse(null);
    // If the name resolves directly, this is a fully qualified type name
    // (ex: 'int' or 'google.protobuf.Timestamp')
    if (type != null) {
      if (qualifiers.isEmpty()) {
        // Resolution of a fully qualified type name: foo.bar.baz
        if (type instanceof TypeType) {
          // Coalesce all type(foo) "type" into a sentinel runtime type to allow for
          // erasure based type comparisons
          return TypeType.create(SimpleType.DYN);
        }

        return TypeType.create(type);
      }

      throw new IllegalStateException(
          "Unexpected type resolution when there were remaining qualifiers: " + type.name());
    }

    // The name itself could be a fully qualified reference to an enum value
    // (e.g: my.enum_type.BAR)
    int lastDotIndex = name.lastIndexOf('.');
    if (lastDotIndex > 0) {
      String enumTypeName = name.substring(0, lastDotIndex);
      String enumValueQualifier = name.substring(lastDotIndex + 1);

      return typeProvider
          .findType(enumTypeName)
          .filter(EnumType.class::isInstance)
          .map(EnumType.class::cast)
          .map(enumType -> getEnumValue(enumType, enumValueQualifier))
          .orElse(null);
    }

    return null;
  }

  private static Long getEnumValue(EnumType enumType, String field) {
    return enumType
        .findNumberByName(field)
        .map(Integer::longValue)
        .orElseThrow(
            () ->
                new NoSuchElementException(
                    String.format("Field %s was not found on enum %s", enumType.name(), field)));
  }

  private boolean isLocallyBound(GlobalResolver resolver, String name) {
    while (resolver instanceof ActivationWrapper) {
      ActivationWrapper wrapper = (ActivationWrapper) resolver;
      if (wrapper.isLocallyBound(name)) {
        return true;
      }
      resolver = wrapper.unwrap();
    }
    return false;
  }

  private GlobalResolver unwrapToNonLocal(GlobalResolver resolver) {
    while (resolver instanceof ActivationWrapper) {
      resolver = ((ActivationWrapper) resolver).unwrap();
    }
    return resolver;
  }

  @Override
  public NamespacedAttribute addQualifier(Qualifier qualifier) {
    return new NamespacedAttribute(
        typeProvider,
        celValueConverter,
        candidateAttributes,
        disambiguateNames,
        ImmutableList.<Qualifier>builderWithExpectedSize(qualifiers.size() + 1)
            .addAll(qualifiers)
            .add(qualifier)
            .build());
  }

  private static Object applyQualifiers(
      Object value, CelValueConverter celValueConverter, ImmutableList<Qualifier> qualifiers) {
    if (value instanceof AccumulatedUnknowns) {
      return value;
    }
    Object obj = celValueConverter.toRuntimeValue(value);

    // Avoid enhanced for loop to prevent UnmodifiableIterator from being allocated
    for (int i = 0; i < qualifiers.size(); i++) {
      Qualifier element = qualifiers.get(i);
      obj = element.qualify(obj);
      obj = celValueConverter.toRuntimeValue(obj);
    }

    return celValueConverter.maybeUnwrap(obj);
  }

  private static Optional<CelAttributePattern> findPartialMatchingPattern(
      CelAttribute attr, ImmutableList<CelAttributePattern> patterns) {
    for (CelAttributePattern pattern : patterns) {
      if (pattern.isPartialMatch(attr)) {
        return Optional.of(pattern);
      }
    }
    return Optional.empty();
  }

  static NamespacedAttribute create(
      CelTypeProvider typeProvider,
      CelValueConverter celValueConverter,
      ImmutableSet<String> namespacedNames) {
    ImmutableMap.Builder<String, CelAttribute> attributesBuilder = ImmutableMap.builder();
    boolean disambiguateNames = false;

    for (String name : namespacedNames) {
      String baseName = name;
      if (name.startsWith(".")) {
        disambiguateNames = true;
        baseName = name.substring(1);
      }
      attributesBuilder.put(baseName, CelAttribute.fromQualifiedIdentifier(baseName));
    }

    return new NamespacedAttribute(
        typeProvider,
        celValueConverter,
        attributesBuilder.buildOrThrow(),
        disambiguateNames,
        ImmutableList.of());
  }

  private NamespacedAttribute(
      CelTypeProvider typeProvider,
      CelValueConverter celValueConverter,
      ImmutableMap<String, CelAttribute> candidateAttributes,
      boolean disambiguateNames,
      ImmutableList<Qualifier> qualifiers) {
    this.typeProvider = typeProvider;
    this.celValueConverter = celValueConverter;
    this.candidateAttributes = candidateAttributes;
    this.disambiguateNames = disambiguateNames;
    this.qualifiers = qualifiers;
  }
}
