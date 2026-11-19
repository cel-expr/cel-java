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

package dev.cel.common.types;

import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableMap;
import com.google.errorprone.annotations.Immutable;
import java.util.Optional;

/** {@code DefaultTypeProvider} is a registry of common CEL types. */
@Immutable
public class DefaultTypeProvider implements CelTypeProvider {

  private static final DefaultTypeProvider INSTANCE = new DefaultTypeProvider();
  private final ImmutableMap<String, CelType> commonTypes;

  @Override
  public ImmutableCollection<CelType> types() {
    return commonTypes.values();
  }

  @Override
  public Optional<CelType> findType(String typeName) {
    return Optional.ofNullable(commonTypes.get(typeName));
  }

  public static DefaultTypeProvider getInstance() {
    return INSTANCE;
  }

  private DefaultTypeProvider() {
    ImmutableMap.Builder<String, CelType> typeMapBuilder = ImmutableMap.builder();
    typeMapBuilder.putAll(SimpleType.TYPE_MAP);
    typeMapBuilder.put("list", ListType.create(SimpleType.DYN));
    typeMapBuilder.put("map", MapType.create(SimpleType.DYN, SimpleType.DYN));
    typeMapBuilder.put("type", TypeType.create(SimpleType.DYN));
    typeMapBuilder.put(
        "optional_type",
        // TODO: Move to CelOptionalLibrary and register it on demand
        OptionalType.create(SimpleType.DYN));
    typeMapBuilder.put(
        "net.IP",
        // TODO: Move to CelNetworkExtensions and register it on demand
        OpaqueType.create("net.IP"));
    typeMapBuilder.put(
        "net.CIDR",
        // TODO: Move to CelNetworkExtensions and register it on demand
        OpaqueType.create("net.CIDR"));
    this.commonTypes = typeMapBuilder.buildOrThrow();
  }
}
