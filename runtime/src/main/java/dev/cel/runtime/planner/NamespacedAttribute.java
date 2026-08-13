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

import static dev.cel.runtime.planner.EvalHelpers.enforceStrictnessAndAdaptUnknowns;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
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
import dev.cel.runtime.CelAttributeResolver;
import dev.cel.runtime.CelUnknownSet;
import dev.cel.runtime.GlobalResolver;
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
  public AttributeResolution resolve(long exprId, GlobalResolver ctx, ExecutionFrame frame) {
    GlobalResolver inputVars = ctx;
    // Unwrap any local activations to ensure that we reach the variables provided as input
    // to the expression in the event that we need to disambiguate between global and local
    // variables.
    if (disambiguateNames) {
      inputVars = unwrapToNonLocal(ctx);
    }

    for (Map.Entry<String, CelAttribute> entry : candidateAttributes.entrySet()) {
      String name = entry.getKey();
      CelAttribute candidateAttr = entry.getValue();
      GlobalResolver resolver = disambiguateNames ? inputVars : ctx;

      Object value;
      CelAttribute fullyQualifiedAttr = null;
      if (!isLocallyBound(resolver, name)) {
        if (frame.hasUnknownResolvers()) {
          fullyQualifiedAttr = qualify(candidateAttr, qualifiers);

          // Check if the fully-qualified attribute is resolved or unknown
          Object fullyQualifiedResult =
              maybeResolveFullyQualified(exprId, fullyQualifiedAttr, frame);
          if (fullyQualifiedResult != null) {
            return AttributeResolution.of(fullyQualifiedResult, fullyQualifiedAttr);
          }
        }

        // Resolve the base attribute (via iterative resolver or standard variable resolver)
        value = maybeResolveBaseAttribute(candidateAttr, resolver, name, frame);
      } else {
        // Locally bound variable (e.g. comprehension variable)
        Object rawValue = resolver.resolve(name);
        value = rawValue != null ? enforceStrictnessAndAdaptUnknowns(rawValue) : null;
      }

      if (value != null) {
        Object resolvedValue;
        if (value instanceof AccumulatedUnknowns && fullyQualifiedAttr != null) {
          resolvedValue =
              AccumulatedUnknowns.create(
                  ((AccumulatedUnknowns) value).exprIds(), ImmutableList.of(fullyQualifiedAttr));
        } else {
          resolvedValue = applyQualifiers(value, celValueConverter, qualifiers);
        }
        return AttributeResolution.of(resolvedValue, fullyQualifiedAttr);
      }

      // Fallback: Attempt to resolve as a qualified type name or enum value
      value = findIdent(name);
      if (value != null) {
        return AttributeResolution.ofValue(value);
      }
    }

    CelAttribute fallbackAttr =
        frame.hasUnknownResolvers() && !candidateAttributes.isEmpty()
            ? qualify(Iterables.getLast(candidateAttributes.values()), qualifiers)
            : null;
    return AttributeResolution.of(
        MissingAttribute.newMissingAttribute(candidateAttributes.keySet()), fallbackAttr);
  }

  private static CelAttribute qualify(CelAttribute baseAttr, ImmutableList<Qualifier> qualifiers) {
    CelAttribute attr = baseAttr;
    // Avoid enhanced for loop to prevent UnmodifiableIterator from being allocated
    for (int i = 0; i < qualifiers.size(); i++) {
      attr = attr.qualify(CelAttribute.Qualifier.fromGeneric(qualifiers.get(i).value()));
    }
    return attr;
  }

  private static @Nullable Object maybeResolveFullyQualified(
      long exprId, CelAttribute fullyQualifiedAttr, ExecutionFrame frame) {
    // Check iterative eval AttributeResolver
    CelAttributeResolver attributeResolver = frame.getAttributeResolver();
    if (attributeResolver != null) {
      Optional<Object> resolved = attributeResolver.resolve(fullyQualifiedAttr);
      if (resolved.isPresent()) {
        return enforceStrictnessAndAdaptUnknowns(resolved.get());
      }
    }

    // Check batch PartialVars unknown patterns
    PartialVars partialVars = frame.getPartialVars();
    if (partialVars != null) {
      ImmutableList<CelAttributePattern> patterns = partialVars.unknowns();
      CelAttributePattern match = findMatchingPattern(fullyQualifiedAttr, patterns).orElse(null);
      if (match != null) {
        return AccumulatedUnknowns.create(
            ImmutableList.of(exprId), ImmutableList.of(match.simplify(fullyQualifiedAttr)));
      }
    }

    return null;
  }

  private static @Nullable Object maybeResolveBaseAttribute(
      CelAttribute candidateAttr, GlobalResolver resolver, String name, ExecutionFrame frame) {
    // Standard variable resolution
    Object rawValue = resolver.resolve(name);
    if (rawValue != null) {
      return enforceStrictnessAndAdaptUnknowns(rawValue);
    }

    // Check iterative eval AttributeResolver
    CelAttributeResolver attributeResolver = frame.getAttributeResolver();
    if (attributeResolver != null) {
      Optional<Object> baseResolved = attributeResolver.resolve(candidateAttr);
      if (baseResolved.isPresent()) {
        return enforceStrictnessAndAdaptUnknowns(baseResolved.get());
      }

      Optional<CelUnknownSet> partialUnknown = attributeResolver.maybePartialUnknown(candidateAttr);
      if (partialUnknown.isPresent()) {
        return AccumulatedUnknowns.create(ImmutableList.of(), partialUnknown.get().attributes());
      }
    }

    return null;
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

  private static Optional<CelAttributePattern> findMatchingPattern(
      CelAttribute attr, ImmutableList<CelAttributePattern> patterns) {
    for (CelAttributePattern pattern : patterns) {
      if (pattern.isMatch(attr)) {
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
