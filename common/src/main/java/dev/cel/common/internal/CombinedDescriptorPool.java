// Copyright 2023 Google LLC
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

package dev.cel.common.internal;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.errorprone.annotations.Immutable;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.ExtensionRegistry;
import dev.cel.common.annotations.Internal;
import java.util.Optional;

/**
 * The {@link CombinedDescriptorPool} takes one or more {@link CelDescriptorPool} instances and
 * supports descriptor lookups in the order the descriptor pools are added to the constructor.
 *
 * <p>CEL Library Internals. Do Not Use.
 */
@Immutable
@Internal
public final class CombinedDescriptorPool implements CelDescriptorPool {
  private final ImmutableList<CelDescriptorPool> descriptorPools;

  // ExtensionRegistry and the memoizing Supplier are effectively immutable.
  @SuppressWarnings("Immutable")
  private final Supplier<ExtensionRegistry> extensionRegistry;

  public static CombinedDescriptorPool create(ImmutableList<CelDescriptorPool> descriptorPools) {
    return new CombinedDescriptorPool(descriptorPools);
  }

  @Override
  public Optional<Descriptor> findDescriptor(String name) {
    for (CelDescriptorPool descriptorPool : descriptorPools) {
      Optional<Descriptor> maybeDescriptor = descriptorPool.findDescriptor(name);
      if (maybeDescriptor.isPresent()) {
        return maybeDescriptor;
      }
    }

    return Optional.empty();
  }

  @Override
  public Optional<FieldDescriptor> findExtensionDescriptor(
      Descriptor containingDescriptor, String fieldName) {
    for (CelDescriptorPool descriptorPool : descriptorPools) {
      Optional<FieldDescriptor> maybeExtensionDescriptor =
          descriptorPool.findExtensionDescriptor(containingDescriptor, fieldName);
      if (maybeExtensionDescriptor.isPresent()) {
        return maybeExtensionDescriptor;
      }
    }

    return Optional.empty();
  }

  @Override
  public ExtensionRegistry getExtensionRegistry() {
    return extensionRegistry.get();
  }

  private CombinedDescriptorPool(ImmutableList<CelDescriptorPool> descriptorPools) {
    this.descriptorPools = checkNotNull(descriptorPools);
    // TODO: Combine the extension registry. This will become necessary once we accept
    // ExtensionRegistry through runtime builder. Ideally, proto team should open source this
    // implementation but we may have to create our own.
    this.extensionRegistry =
        Suppliers.memoize(
            () ->
                descriptorPools.stream()
                    .map(CelDescriptorPool::getExtensionRegistry)
                    .filter(e -> !e.equals(ExtensionRegistry.getEmptyRegistry()))
                    .findFirst()
                    .orElse(ExtensionRegistry.getEmptyRegistry()));
  }
}
