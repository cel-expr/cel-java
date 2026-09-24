// Copyright 2022 Google LLC
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

package dev.cel.common.types;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableSet;
import com.google.errorprone.annotations.CheckReturnValue;
import com.google.errorprone.annotations.Immutable;
import dev.cel.common.annotations.Internal;
import java.util.Optional;

/**
 * The {@code ProtoMessageType} is a {@code StructType} with support for proto {@code Extension}s
 * and field masks.
 */
@CheckReturnValue
@Immutable
public final class ProtoMessageType extends StructType {

  private final StructType.FieldResolver extensionResolver;
  private final JsonNameResolver jsonNameResolver;
  private final boolean fieldNamesEnumerable;

  @Override
  public Optional<Field> findField(String fieldName) {
    if (fieldNamesEnumerable) {
      return super.findField(fieldName);
    }
    // The set of declared field names is unknown, so the resolver is the sole source of truth.
    return fieldResolver.findField(fieldName).map(type -> Field.of(fieldName, type));
  }

  @Override
  public ImmutableSet<String> fieldNames() {
    checkState(
        fieldNamesEnumerable, "fields of '%s' cannot be enumerated; use findField instead", name);
    return super.fieldNames();
  }

  @Override
  public ImmutableSet<Field> fields() {
    checkState(
        fieldNamesEnumerable, "fields of '%s' cannot be enumerated; use findField instead", name);
    return super.fields();
  }

  /** Find an {@code Extension} by its fully-qualified {@code extensionName}. */
  public Optional<Extension> findExtension(String extensionName) {
    return extensionResolver
        .findField(extensionName)
        .map(type -> Extension.of(extensionName, type, this));
  }

  /** Returns true if the field name is a json name. */
  public boolean isJsonName(String fieldName) {
    return jsonNameResolver.isJsonName(fieldName);
  }

  /**
   * Create a new instance of the {@code ProtoMessageType} using the {@code visibleFields} set as a
   * mask of the fields from the backing proto.
   *
   * <p>The returned type is always enumerable, including when this type is not: {@code
   * visibleFields} is by definition the complete set of field names the masked type exposes.
   */
  public ProtoMessageType withVisibleFields(ImmutableSet<String> visibleFields) {
    return new ProtoMessageType(
        name, visibleFields, fieldResolver, extensionResolver, jsonNameResolver);
  }

  public static ProtoMessageType create(
      String name,
      ImmutableSet<String> fieldNames,
      FieldResolver fieldResolver,
      FieldResolver extensionResolver,
      JsonNameResolver jsonNameResolver) {
    return new ProtoMessageType(
        name, fieldNames, fieldResolver, extensionResolver, jsonNameResolver);
  }

  /**
   * Creates a {@code ProtoMessageType} for a message whose set of field names cannot be enumerated
   * ahead of time, such as one backed by a lazily populated descriptor pool or by a deprecated
   * {@code dev.cel.checker.TypeProvider}.
   *
   * <p>{@link #findField} delegates every lookup directly to {@code fieldResolver} rather than
   * first consulting {@link #fieldNames()}. Enumerating the resulting type via {@link #fieldNames}
   * or {@link #fields} throws, so such a type must not be handed to utilities that iterate fields
   * (for example {@code ProtoTypeMaskTypeProvider} or {@code ConstantFoldingOptimizer}).
   *
   * <p>CEL Library Internals. Do Not Use.
   */
  @Internal
  public static ProtoMessageType createWithUnenumerableFields(
      String name, FieldResolver fieldResolver, FieldResolver extensionResolver) {
    return createWithUnenumerableFields(
        name, fieldResolver, extensionResolver, /* jsonNameResolver= */ fieldName -> false);
  }

  /**
   * Creates a {@code ProtoMessageType} for a message whose set of field names cannot be enumerated
   * ahead of time, with a custom {@code jsonNameResolver}.
   *
   * <p>CEL Library Internals. Do Not Use.
   */
  @Internal
  public static ProtoMessageType createWithUnenumerableFields(
      String name,
      FieldResolver fieldResolver,
      FieldResolver extensionResolver,
      JsonNameResolver jsonNameResolver) {
    return new ProtoMessageType(
        checkNotNull(name),
        ImmutableSet.of(),
        checkNotNull(fieldResolver),
        checkNotNull(extensionResolver),
        checkNotNull(jsonNameResolver),
        /* fieldNamesEnumerable= */ false);
  }

  private ProtoMessageType(
      String name,
      ImmutableSet<String> fieldNames,
      FieldResolver fieldResolver,
      FieldResolver extensionResolver,
      JsonNameResolver jsonNameResolver) {
    this(
        name,
        fieldNames,
        fieldResolver,
        extensionResolver,
        jsonNameResolver,
        /* fieldNamesEnumerable= */ true);
  }

  private ProtoMessageType(
      String name,
      ImmutableSet<String> fieldNames,
      FieldResolver fieldResolver,
      FieldResolver extensionResolver,
      JsonNameResolver jsonNameResolver,
      boolean fieldNamesEnumerable) {
    super(name, fieldNames, fieldResolver);
    this.extensionResolver = extensionResolver;
    this.jsonNameResolver = jsonNameResolver;
    this.fieldNamesEnumerable = fieldNamesEnumerable;
  }

  /** Functional interface for resolving whether a field name is a json name. */
  @FunctionalInterface
  @Immutable
  public interface JsonNameResolver {
    boolean isJsonName(String fieldName);
  }

  /** {@code Extension} contains the name, type, and target message type of the extension. */
  @Immutable
  @AutoValue
  public abstract static class Extension {
    public abstract String name();

    public abstract CelType type();

    public abstract CelType messageType();

    static Extension of(String name, CelType type, CelType messageType) {
      return new AutoValue_ProtoMessageType_Extension(name, type, messageType);
    }
  }
}
