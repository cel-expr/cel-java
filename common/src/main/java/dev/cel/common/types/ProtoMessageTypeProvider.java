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

import static com.google.common.collect.ImmutableMap.toImmutableMap;

import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.errorprone.annotations.CheckReturnValue;
import com.google.errorprone.annotations.Immutable;
import com.google.protobuf.DescriptorProtos.FileDescriptorSet;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.EnumDescriptor;
import com.google.protobuf.Descriptors.EnumValueDescriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import dev.cel.common.CelDescriptorUtil;
import dev.cel.common.CelDescriptors;
import dev.cel.common.internal.FileDescriptorSetConverter;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * The {@code ProtoMessageTypeProvider} implements the {@link CelTypeProvider} interface to provide
 * {@code CelType} objects for the core CEL types and a list of protobuf message types.
 */
@CheckReturnValue
@Immutable
public final class ProtoMessageTypeProvider implements CelTypeProvider {

  private static final ImmutableMap<FieldDescriptor.Type, CelType> PROTO_TYPE_TO_CEL_TYPE =
      ImmutableMap.<FieldDescriptor.Type, CelType>builder()
          .put(FieldDescriptor.Type.BOOL, SimpleType.BOOL)
          .put(FieldDescriptor.Type.BYTES, SimpleType.BYTES)
          .put(FieldDescriptor.Type.DOUBLE, SimpleType.DOUBLE)
          .put(FieldDescriptor.Type.FLOAT, SimpleType.DOUBLE)
          .put(FieldDescriptor.Type.FIXED32, SimpleType.UINT)
          .put(FieldDescriptor.Type.FIXED64, SimpleType.UINT)
          .put(FieldDescriptor.Type.INT32, SimpleType.INT)
          .put(FieldDescriptor.Type.INT64, SimpleType.INT)
          .put(FieldDescriptor.Type.SFIXED32, SimpleType.INT)
          .put(FieldDescriptor.Type.SFIXED64, SimpleType.INT)
          .put(FieldDescriptor.Type.SINT32, SimpleType.INT)
          .put(FieldDescriptor.Type.SINT64, SimpleType.INT)
          .put(FieldDescriptor.Type.STRING, SimpleType.STRING)
          .put(FieldDescriptor.Type.UINT32, SimpleType.UINT)
          .put(FieldDescriptor.Type.UINT64, SimpleType.UINT)
          .buildOrThrow();

  private final ImmutableMap<String, CelType> allTypes;
  private final boolean allowJsonFieldNames;

  /** Returns a new builder for {@link ProtoMessageTypeProvider}. */
  public static Builder newBuilder() {
    return new Builder();
  }

  /**
   * @deprecated Use {@link #newBuilder()} instead.
   */
  @Deprecated
  public ProtoMessageTypeProvider() {
    this(CelDescriptors.builder().build(), false);
  }

  /**
   * @deprecated Use {@link #newBuilder()} instead.
   */
  @Deprecated
  public ProtoMessageTypeProvider(FileDescriptorSet descriptorSet) {
    this(
        CelDescriptorUtil.getAllDescriptorsFromFileDescriptor(
            FileDescriptorSetConverter.convert(descriptorSet)),
        false);
  }

  /**
   * @deprecated Use {@link #newBuilder()} instead.
   */
  @Deprecated
  public ProtoMessageTypeProvider(Iterable<Descriptor> descriptors) {
    this(
        CelDescriptorUtil.getAllDescriptorsFromFileDescriptor(
            ImmutableSet.copyOf(Iterables.transform(descriptors, Descriptor::getFile))),
        false);
  }

  /**
   * @deprecated Use {@link #newBuilder()} instead.
   */
  @Deprecated
  public ProtoMessageTypeProvider(ImmutableSet<FileDescriptor> fileDescriptors) {
    this(CelDescriptorUtil.getAllDescriptorsFromFileDescriptor(fileDescriptors), false);
  }

  private ProtoMessageTypeProvider(CelDescriptors celDescriptors, boolean allowJsonFieldNames) {
    this.allowJsonFieldNames = allowJsonFieldNames;
    this.allTypes =
        ImmutableMap.<String, CelType>builder()
            .putAll(createEnumTypes(celDescriptors.enumDescriptors()))
            .putAll(
                createProtoMessageTypes(
                    celDescriptors.messageTypeDescriptors(), celDescriptors.extensionDescriptors()))
            .buildOrThrow();
  }

  @Override
  public ImmutableCollection<CelType> types() {
    return allTypes.values();
  }

  @Override
  public Optional<CelType> findType(String typeName) {
    return Optional.ofNullable(allTypes.get(typeName));
  }

  // This method reference implements @Immutable interface FieldResolver, but the declaration of
  // type 'com.google.api.expr.cel.ProtoMessageTypeProvider.FieldResolver' is not annotated with
  // @com.google.errorprone.annotations.Immutable
  @SuppressWarnings("Immutable")
  private ImmutableMap<String, CelType> createProtoMessageTypes(
      Collection<Descriptor> descriptors, ImmutableMultimap<String, FieldDescriptor> extensionMap) {
    Map<String, CelType> protoMessageTypes = new HashMap<>();
    for (Descriptor descriptor : descriptors) {
      if (protoMessageTypes.containsKey(descriptor.getFullName())) {
        continue;
      }

      ImmutableSet.Builder<String> fieldNamesBuilder = ImmutableSet.builder();
      ImmutableSet.Builder<String> jsonNamesBuilder = ImmutableSet.builder();
      for (FieldDescriptor fd : descriptor.getFields()) {
        if (allowJsonFieldNames) {
          fieldNamesBuilder.add(fd.getJsonName());
          jsonNamesBuilder.add(fd.getJsonName());
        } else {
          fieldNamesBuilder.add(fd.getName());
        }
      }
      ImmutableSet<String> jsonNames = jsonNamesBuilder.build();

      Map<String, FieldDescriptor> extensionFields = new HashMap<>();
      for (FieldDescriptor extension : extensionMap.get(descriptor.getFullName())) {
        extensionFields.putIfAbsent(extension.getFullName(), extension);
      }
      ImmutableMap<String, FieldDescriptor> extensions = ImmutableMap.copyOf(extensionFields);

      protoMessageTypes.put(
          descriptor.getFullName(),
          ProtoMessageType.create(
              descriptor.getFullName(),
              fieldNamesBuilder.build(),
              new FieldResolver(this, descriptor)::findField,
              new FieldResolver(this, extensions)::findField,
              jsonNames::contains));
    }
    return ImmutableMap.copyOf(protoMessageTypes);
  }

  private ImmutableMap<String, CelType> createEnumTypes(
      Collection<EnumDescriptor> enumDescriptors) {
    HashMap<String, CelType> enumTypes = new HashMap<>();
    for (EnumDescriptor enumDescriptor : enumDescriptors) {
      if (enumTypes.containsKey(enumDescriptor.getFullName())) {
        continue;
      }
      ImmutableMap<String, Integer> values =
          enumDescriptor.getValues().stream()
              .collect(
                  toImmutableMap(EnumValueDescriptor::getName, EnumValueDescriptor::getNumber));
      enumTypes.put(
          enumDescriptor.getFullName(), EnumType.create(enumDescriptor.getFullName(), values));
    }
    return ImmutableMap.copyOf(enumTypes);
  }

  private static class FieldResolver {
    private final ProtoMessageTypeProvider protoMessageTypeProvider;
    private final ImmutableMap<String, FieldDescriptor> fields;

    private static ImmutableMap<String, FieldDescriptor> collectJsonFieldDescriptorMap(
        Descriptor descriptor) {
      ImmutableMap.Builder<String, FieldDescriptor> builder = ImmutableMap.builder();
      for (FieldDescriptor fd : descriptor.getFields()) {
        if (!fd.getJsonName().isEmpty()) {
          builder.put(fd.getJsonName(), fd);
        } else {
          builder.put(fd.getName(), fd);
        }
      }

      return builder.buildOrThrow();
    }

    private static ImmutableMap<String, FieldDescriptor> collectFieldDescriptorMap(
        Descriptor descriptor) {
      return descriptor.getFields().stream()
          .collect(toImmutableMap(FieldDescriptor::getName, Function.identity()));
    }

    private FieldResolver(
        ProtoMessageTypeProvider protoMessageTypeProvider, Descriptor descriptor) {
      this(
          protoMessageTypeProvider,
          protoMessageTypeProvider.allowJsonFieldNames
              ? collectJsonFieldDescriptorMap(descriptor)
              : collectFieldDescriptorMap(descriptor));
    }

    private FieldResolver(
        ProtoMessageTypeProvider protoMessageTypeProvider,
        ImmutableMap<String, FieldDescriptor> fields) {
      this.protoMessageTypeProvider = protoMessageTypeProvider;
      this.fields = fields;
    }

    private Optional<CelType> findField(String fieldName) {
      FieldDescriptor fieldDescriptor = fields.get(fieldName);
      if (fieldDescriptor == null) {
        return Optional.empty();
      }
      return findFieldInternal(fieldDescriptor);
    }

    private Optional<CelType> findFieldInternal(FieldDescriptor fieldDescriptor) {
      boolean isRepeated = fieldDescriptor.isRepeated();
      CelType fieldType;
      switch (fieldDescriptor.getType()) {
        case GROUP:
        case MESSAGE:
          Descriptor descriptor = fieldDescriptor.getMessageType();
          if (fieldDescriptor.isMapField()) {
            // The key and value types are always guaranteed to be at index 0 and 1 respectively.
            FieldDescriptor keyField = descriptor.getFields().get(0);
            FieldDescriptor valueField = descriptor.getFields().get(1);
            Optional<CelType> key = findFieldInternal(keyField);
            Optional<CelType> value = findFieldInternal(valueField);
            if (key.isPresent() && value.isPresent()) {
              return Optional.of(MapType.create(key.get(), value.get()));
            }
            return Optional.empty();
          }
          String messageName = descriptor.getFullName();
          fieldType =
              CelTypes.getWellKnownCelType(messageName)
                  .orElse(protoMessageTypeProvider.findType(descriptor.getFullName()).orElse(null));
          break;
        case ENUM:
          EnumDescriptor enumDescriptor = fieldDescriptor.getEnumType();
          fieldType = protoMessageTypeProvider.findType(enumDescriptor.getFullName()).orElse(null);
          break;
        default:
          fieldType = PROTO_TYPE_TO_CEL_TYPE.get(fieldDescriptor.getType());
          break;
      }
      if (fieldType == null) {
        return Optional.empty();
      }
      if (isRepeated) {
        return Optional.of(ListType.create(fieldType));
      }
      return Optional.of(fieldType);
    }
  }

  /** Builder for {@link ProtoMessageTypeProvider}. */
  public static final class Builder {
    private final ImmutableSet.Builder<FileDescriptor> fileDescriptors = ImmutableSet.builder();
    private boolean allowJsonFieldNames;
    private CelDescriptors celDescriptors;

    /** Adds a {@link FileDescriptor} to the provider. */
    @CanIgnoreReturnValue
    public Builder addFileDescriptors(FileDescriptor... fileDescriptors) {
      return addFileDescriptors(Arrays.asList(fileDescriptors));
    }

    /** Adds a collection of {@link FileDescriptor}s to the provider. */
    @CanIgnoreReturnValue
    public Builder addFileDescriptors(Iterable<FileDescriptor> fileDescriptors) {
      this.fileDescriptors.addAll(fileDescriptors);
      return this;
    }

    /** Adds a collection of {@link Descriptor}s. The parent file of each descriptor is added. */
    @CanIgnoreReturnValue
    public Builder addDescriptors(Iterable<Descriptor> descriptors) {
      this.fileDescriptors.addAll(Iterables.transform(descriptors, Descriptor::getFile));
      return this;
    }

    /**
     * Use the `json_name` field option on a protobuf message as the name of the field.
     *
     * <p>If enabled, the type checker will only accept the `json_name` and will no longer recognize
     * the original protobuf field name. This is to avoid ambiguity between the two names.
     */
    @CanIgnoreReturnValue
    public Builder setAllowJsonFieldNames(boolean allowJsonFieldNames) {
      this.allowJsonFieldNames = allowJsonFieldNames;
      return this;
    }

    /**
     * Sets the CEL descriptors. Note this cannot be used in conjunction with other descriptor
     * adders such as {@link #addDescriptors}.
     */
    @CanIgnoreReturnValue
    public Builder setCelDescriptors(CelDescriptors celDescriptors) {
      this.celDescriptors = celDescriptors;
      return this;
    }

    /** Builds the {@link ProtoMessageTypeProvider}. */
    public ProtoMessageTypeProvider build() {
      ImmutableSet<FileDescriptor> fds = fileDescriptors.build();
      if (celDescriptors != null && !fds.isEmpty()) {
        throw new IllegalArgumentException(
            "Both CelDescriptors and FileDescriptors cannot be set at the same time.");
      }

      if (celDescriptors == null) {
        celDescriptors = CelDescriptorUtil.getAllDescriptorsFromFileDescriptor(fds);
      }

      return new ProtoMessageTypeProvider(celDescriptors, allowJsonFieldNames);
    }

    private Builder() {}
  }
}
