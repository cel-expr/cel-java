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

package dev.cel.checker;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.errorprone.annotations.Immutable;
import dev.cel.common.types.CelKind;
import dev.cel.common.types.CelType;
import dev.cel.common.types.CelTypeProvider;
import dev.cel.common.types.EnumType;
import dev.cel.common.types.ProtoMessageType;
import dev.cel.common.types.StructTypeReference;
import dev.cel.common.types.TypeType;
import java.util.Optional;

/**
 * Adapts a deprecated {@link TypeProvider} onto the modern {@link CelTypeProvider} interface.
 *
 * <p>It exists so that the checker internals ({@link Env} and {@link ExprChecker}) can be expressed
 * purely in terms of {@link CelTypeProvider} while callers continue to supply a {@link
 * TypeProvider}. The adaptation is applied at the legacy entrypoints, so no downstream migration is
 * required.
 *
 * <h2>Mapping the two contracts</h2>
 *
 * <p>The two interfaces disagree on what a type lookup returns. {@link
 * TypeProvider#lookupType(String)} returns the type of the <i>identifier</i> naming the type (e.g.
 * {@code type(foo.Bar)} for a message), whereas {@link CelTypeProvider#findType(String)} returns
 * the type <i>itself</i> (e.g. the struct {@code foo.Bar}), which {@link Env} subsequently wraps.
 * This bridge therefore unwraps {@code type(T)} before handing the result back.
 *
 * <p>Legacy providers which return an unwrapped struct from {@code lookupType} would previously
 * declare the name as a <i>variable</i> of that message type. Such a provider is instead treated
 * here as declaring a type name, matching {@code DescriptorTypeProvider}. No provider in google3 is
 * known to do this.
 *
 * <p>CEL Library Internals. Do Not Use.
 */
@Immutable
final class LegacyTypeProviderBridge implements CelTypeProvider {

  // TypeProvider is a deprecated public interface that predates @Immutable annotations. Its
  // implementations are required to be effectively immutable by the type-checker contract.
  @SuppressWarnings("Immutable")
  private final TypeProvider legacyTypeProvider;

  /**
   * Returns an empty list: a {@link TypeProvider} resolves types lazily by name and cannot be
   * enumerated, so callers must rely on {@link #findType} instead.
   *
   * <p>Consequently this provider must not be used with utilities that enumerate {@code types()}
   * (for example {@code ProtoTypeMaskTypeProvider}), as they would silently observe no types at
   * all. {@code CelCheckerLegacyImpl} keeps it off those paths by confining it to the provider it
   * hands to {@link Env}.
   */
  @Override
  public ImmutableList<CelType> types() {
    return ImmutableList.of();
  }

  @Override
  public Optional<CelType> findType(String typeName) {
    return resolveType(typeName);
  }

  private Optional<CelType> resolveType(String typeName) {
    Optional<CelType> declaredType = legacyTypeProvider.lookupCelType(typeName);
    if (declaredType.isPresent()) {
      return declaredType.map(this::adaptDeclaredType);
    }
    return resolveEnumValueType(typeName);
  }

  /** Converts an identifier's declared type, as returned by the legacy provider, into a type. */
  private CelType adaptDeclaredType(CelType declaredType) {
    CelType targetType =
        declaredType instanceof TypeType ? ((TypeType) declaredType).type() : declaredType;
    if (targetType.kind().equals(CelKind.STRUCT)) {
      return newStructType(targetType.name());
    }
    // Not a struct, so Env will not re-wrap it. Hand back the declared type verbatim to preserve
    // the legacy provider's intent, whether that is a type value (e.g. type(int)) or the declared
    // type of a dynamically resolved qualified identifier (e.g. list(foo.Bar)).
    return declaredType;
  }

  private ProtoMessageType newStructType(String typeName) {
    // Hoisted out of the resolver: the type-checker re-resolves the declaring type on every field
    // selection, and the legacy lookup is keyed by a proto type rather than by name.
    StructTypeReference typeReference = StructTypeReference.create(typeName);
    return ProtoMessageType.createWithUnenumerableFields(
        typeName,
        fieldName -> findFieldType(typeReference, fieldName),
        extensionName -> findExtensionType(typeName, extensionName));
  }

  private Optional<CelType> findFieldType(StructTypeReference typeReference, String fieldName) {
    TypeProvider.FieldType fieldType = legacyTypeProvider.lookupFieldType(typeReference, fieldName);
    return Optional.ofNullable(fieldType).map(TypeProvider.FieldType::celType);
  }

  private Optional<CelType> findExtensionType(String typeName, String extensionName) {
    TypeProvider.ExtensionFieldType extensionFieldType =
        legacyTypeProvider.lookupExtensionType(extensionName);
    if (extensionFieldType == null
        || !extensionFieldType.messageType().getMessageType().equals(typeName)) {
      return Optional.empty();
    }
    return Optional.of(extensionFieldType.fieldType().celType());
  }

  /**
   * Resolves a fully qualified enum value name (e.g. {@code foo.Bar.MyEnum.VALUE}) into an {@link
   * EnumType} holding just that value.
   *
   * <p>A {@link TypeProvider} can only resolve enums one value at a time via {@link
   * TypeProvider#lookupEnumValue}; it cannot enumerate an enum's values given only the enum's type
   * name. The resulting single-valued {@code EnumType} is named after the enum type rather than the
   * value, which is what allows {@code Env} to distinguish it from a type reference and resolve it
   * as an enum constant instead.
   */
  private Optional<CelType> resolveEnumValueType(String enumValueName) {
    int dotIndex = enumValueName.lastIndexOf('.');
    if (dotIndex <= 0 || dotIndex == enumValueName.length() - 1) {
      return Optional.empty();
    }
    Integer enumValue = legacyTypeProvider.lookupEnumValue(enumValueName);
    if (enumValue == null) {
      return Optional.empty();
    }
    String enumTypeName = enumValueName.substring(0, dotIndex);
    String localName = enumValueName.substring(dotIndex + 1);
    return Optional.of(EnumType.create(enumTypeName, ImmutableMap.of(localName, enumValue)));
  }

  LegacyTypeProviderBridge(TypeProvider legacyTypeProvider) {
    this.legacyTypeProvider = checkNotNull(legacyTypeProvider);
  }
}
