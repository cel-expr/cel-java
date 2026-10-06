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

package dev.cel.common;

import com.google.common.collect.ImmutableSet;
import com.google.protobuf.DescriptorProtos.FileDescriptorSet;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import dev.cel.common.annotations.Internal;
import dev.cel.common.internal.FileDescriptorSetConverter;
import dev.cel.common.types.CelTypes;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Utility class for working with protobuf descriptors.
 *
 * <p>CEL Library Internals. Do Not Use.
 */
@Internal
public final class CelDescriptorUtil {

  private CelDescriptorUtil() {}

  /**
   * Get the full {@code FileDescriptor} set needed to accurately instantiate the {@code
   * descriptors}.
   */
  public static ImmutableSet<FileDescriptor> getFileDescriptorsForDescriptors(
      Iterable<Descriptor> descriptors) {
    ImmutableSet.Builder<FileDescriptor> fileDescriptors = ImmutableSet.builder();
    descriptors.forEach(d -> fileDescriptors.add(d.getFile()));
    return getFileDescriptorsAndDependencies(fileDescriptors.build());
  }

  /**
   * Convert a {@code FileDescriptorSet} into a set of {@code FileDescriptor} instances.
   *
   * <p>Warning: This will produce unique FileDescriptor instances. Use with care especially in
   * hermetic environments.
   */
  public static ImmutableSet<FileDescriptor> getFileDescriptorsFromFileDescriptorSet(
      FileDescriptorSet fileDescriptorSet) {
    return FileDescriptorSetConverter.convert(fileDescriptorSet);
  }

  /**
   * Extract the full message {@code CelDescriptors} set from the input set of {@code
   * fileDescriptors}. All message type, enum, extension and file descriptors will be extracted.
   *
   * <p>Note, the input {@code fileDescriptors} set will be expanded to the complete set of
   * dependencies needed to describe the types within the provided files.
   */
  public static CelDescriptors getAllDescriptorsFromFileDescriptor(
      FileDescriptor... fileDescriptors) {
    return getAllDescriptorsFromFileDescriptor(Arrays.asList(fileDescriptors));
  }

  /**
   * Extract the full message {@code CelDescriptors} set from the input set of {@code
   * fileDescriptors}. All message type, enum, extension and file descriptors will be extracted.
   *
   * <p>Note, the input {@code fileDescriptors} set will be expanded to the complete set of
   * dependencies needed to describe the types within the provided files.
   */
  public static CelDescriptors getAllDescriptorsFromFileDescriptor(
      Iterable<FileDescriptor> fileDescriptors) {
    ImmutableSet<FileDescriptor> allFileDescriptors =
        getFileDescriptorsAndDependencies(fileDescriptors);

    CelDescriptors.Builder celDescriptorsBuilder = CelDescriptors.builder();
    allFileDescriptors.forEach(
        fd -> collectAllDescriptorsFromFileDescriptor(fd, celDescriptorsBuilder));

    return celDescriptorsBuilder.build();
  }

  /**
   * Collects all descriptors referenced by the {@code FileDescriptor}, including message types,
   * enums and extensions.
   */
  private static void collectAllDescriptorsFromFileDescriptor(
      FileDescriptor fileDescriptor, CelDescriptors.Builder celDescriptors) {
    celDescriptors.addFileDescriptors(fileDescriptor);

    collectEnumDescriptors(fileDescriptor, celDescriptors);
    collectMessageTypeDescriptors(fileDescriptor, celDescriptors);
    collectExtensions(fileDescriptor, celDescriptors);
  }

  private static void collectEnumDescriptors(
      FileDescriptor fileDescriptor, CelDescriptors.Builder celDescriptors) {
    Set<String> visitedDescriptors = new HashSet<>();
    celDescriptors.addEnumDescriptors(fileDescriptor.getEnumTypes());
    fileDescriptor
        .getMessageTypes()
        .forEach(d -> collectEnumDescriptors(d, visitedDescriptors, celDescriptors));
    fileDescriptor.getExtensions().forEach(ext -> collectEnumDescriptors(ext, celDescriptors));
  }

  private static void collectEnumDescriptors(
      Descriptor descriptor,
      Set<String> visitedDescriptors,
      CelDescriptors.Builder celDescriptors) {
    if (visitedDescriptors.contains(descriptor.getFullName())) {
      return;
    }
    visitedDescriptors.add(descriptor.getFullName());
    celDescriptors.addEnumDescriptors(descriptor.getEnumTypes());

    descriptor.getFields().forEach(field -> collectEnumDescriptors(field, celDescriptors));
    descriptor
        .getNestedTypes()
        .forEach(nested -> collectEnumDescriptors(nested, visitedDescriptors, celDescriptors));
  }

  private static void collectEnumDescriptors(
      FieldDescriptor fieldDescriptor, CelDescriptors.Builder celDescriptors) {
    if (!fieldDescriptor.getType().equals(FieldDescriptor.Type.ENUM)) {
      return;
    }
    celDescriptors.addEnumDescriptors(fieldDescriptor.getEnumType());
  }

  private static void collectMessageTypeDescriptors(
      FileDescriptor descriptor, CelDescriptors.Builder celDescriptors) {
    Set<String> visited = new HashSet<>();
    descriptor
        .getMessageTypes()
        .forEach((d) -> collectMessageTypeDescriptors(d, visited, celDescriptors));
  }

  private static void collectMessageTypeDescriptors(
      Descriptor descriptor, Set<String> visited, CelDescriptors.Builder celDescriptors) {
    String messageName = descriptor.getFullName();
    if (visited.contains(messageName)) {
      return;
    }

    if (!descriptor.getOptions().getMapEntry()) {
      visited.add(messageName);
      celDescriptors.addMessageTypeDescriptors(descriptor);
    }

    if (CelTypes.getWellKnownCelType(messageName).isPresent()) {
      return;
    }

    descriptor
        .getNestedTypes()
        .forEach(nested -> collectMessageTypeDescriptors(nested, visited, celDescriptors));
    descriptor
        .getFields()
        .forEach(field -> collectFieldDescriptors(field, visited, celDescriptors));

    celDescriptors.addExtensionDescriptors(descriptor.getExtensions());
  }

  private static void collectExtensions(
      FileDescriptor fileDescriptor, CelDescriptors.Builder celDescriptors) {
    celDescriptors.addExtensionDescriptors(fileDescriptor.getExtensions());
    Set<String> visited = new HashSet<>();
    fileDescriptor
        .getExtensions()
        .forEach((d) -> collectFieldDescriptors(d, visited, celDescriptors));
  }

  /**
   * Enumerates all message types in a field descriptor. This is used for handling nested type
   * entries within a message.
   */
  private static void collectFieldDescriptors(
      FieldDescriptor fieldDescriptor, Set<String> visited, CelDescriptors.Builder celDescriptors) {
    if (!fieldDescriptor.getType().equals(FieldDescriptor.Type.MESSAGE)) {
      return;
    }
    Descriptor fieldMessageType = fieldDescriptor.getMessageType();
    if (fieldDescriptor.isMapField()) {
      FieldDescriptor valueDescriptor = fieldDescriptor.getMessageType().getFields().get(1);
      if (valueDescriptor.getType().equals(FieldDescriptor.Type.MESSAGE)) {
        collectMessageTypeDescriptors(valueDescriptor.getMessageType(), visited, celDescriptors);
      }
    } else {
      collectMessageTypeDescriptors(fieldMessageType, visited, celDescriptors);
    }
  }

  /**
   * Get the full {@code FileDescriptor} set needed by the referenced {@code fileDescriptors}.
   *
   * <p>If the {@code FileDescriptor} is provided which contains the {@code AttributeContext}
   * message definition, then the expanded {@code FileDescriptor} set should also include the files
   * defining {@code Duration}, {@code Timestamp}, and {@code Struct}.
   */
  private static ImmutableSet<FileDescriptor> getFileDescriptorsAndDependencies(
      Iterable<FileDescriptor> fileDescriptors) {
    Set<String> visited = new HashSet<>();
    ImmutableSet.Builder<FileDescriptor> expandedFileDescriptors = ImmutableSet.builder();
    ImmutableSet.copyOf(fileDescriptors)
        .forEach((fd) -> copyToFileDescriptorSet(visited, fd, expandedFileDescriptors));
    return expandedFileDescriptors.build();
  }

  private static void copyToFileDescriptorSet(
      Set<String> visited, FileDescriptor fd, ImmutableSet.Builder<FileDescriptor> files) {
    if (visited.contains(fd.getFullName())) {
      return;
    }

    visited.add(fd.getFullName());
    for (FileDescriptor dep : fd.getDependencies()) {
      copyToFileDescriptorSet(visited, dep, files);
    }
    files.add(fd);
  }
}
