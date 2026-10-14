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

import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.EnumDescriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import java.util.Arrays;

/** Value object containing multiple set of descriptors to be provided into the CEL environment. */
@AutoValue
public abstract class CelDescriptors {

  /** Descriptors of type loaded from {@link FileDescriptor#getMessageTypes()}. */
  public abstract ImmutableSet<Descriptor> messageTypeDescriptors();

  public abstract ImmutableSet<EnumDescriptor> enumDescriptors();

  /**
   * Set of field descriptors that are part of a descriptor's extensions. Key: Containing type's
   * full name, Value: Extension's field descriptors
   */
  public abstract ImmutableMultimap<String, FieldDescriptor> extensionDescriptors();

  /** File descriptors that were used to load the message type, enum descriptors and extensions. */
  public abstract ImmutableSet<FileDescriptor> fileDescriptors();

  public static Builder builder() {
    return new AutoValue_CelDescriptors.Builder();
  }

  /** Builder for configuring the CelDescriptors */
  @AutoValue.Builder
  public abstract static class Builder {

    protected abstract ImmutableSet.Builder<Descriptor> messageTypeDescriptorsBuilder();

    protected abstract ImmutableSet.Builder<EnumDescriptor> enumDescriptorsBuilder();

    protected abstract ImmutableMultimap.Builder<String, FieldDescriptor>
        extensionDescriptorsBuilder();

    protected abstract ImmutableSet.Builder<FileDescriptor> fileDescriptorsBuilder();

    public abstract CelDescriptors build();

    @CanIgnoreReturnValue
    final Builder addMessageTypeDescriptors(Descriptor... descriptors) {
      addMessageTypeDescriptors(Arrays.asList(descriptors));
      return this;
    }

    @CanIgnoreReturnValue
    final Builder addMessageTypeDescriptors(Iterable<Descriptor> descriptors) {
      messageTypeDescriptorsBuilder().addAll(descriptors);
      return this;
    }

    @CanIgnoreReturnValue
    final Builder addEnumDescriptors(EnumDescriptor... enumDescriptors) {
      addEnumDescriptors(Arrays.asList(enumDescriptors));
      return this;
    }

    @CanIgnoreReturnValue
    final Builder addEnumDescriptors(Iterable<EnumDescriptor> enumDescriptors) {
      enumDescriptorsBuilder().addAll(enumDescriptors);
      return this;
    }

    @CanIgnoreReturnValue
    final Builder addExtensionDescriptors(Iterable<FieldDescriptor> extensionDescriptors) {
      extensionDescriptors.forEach(
          d -> extensionDescriptorsBuilder().put(d.getContainingType().getFullName(), d));
      return this;
    }

    @CanIgnoreReturnValue
    final Builder addFileDescriptors(FileDescriptor... fileDescriptors) {
      addFileDescriptors(Arrays.asList(fileDescriptors));
      return this;
    }

    @CanIgnoreReturnValue
    final Builder addFileDescriptors(Iterable<FileDescriptor> fileDescriptors) {
      fileDescriptorsBuilder().addAll(fileDescriptors);
      return this;
    }
  }
}
