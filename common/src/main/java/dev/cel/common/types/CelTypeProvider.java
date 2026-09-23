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

import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.errorprone.annotations.CheckReturnValue;
import com.google.errorprone.annotations.Immutable;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The {@code CelTypeProvider} is used to lookup {@code CelType} definitions by name, as well as
 * enumerate supported {@link #types()}.
 */
@CheckReturnValue
@Immutable
public interface CelTypeProvider {

  /** Return the set of {@code CelType} instances supported by this provider. */
  ImmutableCollection<CelType> types();

  /** Return the {@code CelType} for the given {@code typeName} if supported. */
  Optional<CelType> findType(String typeName);

  /**
   * The {@code CombinedCelTypeProvider} implements the {@code CelTypeProvider} interface by merging
   * the {@code CelType} instances supported by each together.
   *
   * <p>If there is more than one {@code CelType} for a given type, the first definition found is
   * the one supported in the {@code CombinedCelTypeProvider}.
   */
  @Immutable
  final class CombinedCelTypeProvider implements CelTypeProvider {

    private final ImmutableList<CelTypeProvider> typeProviders;
    private final ImmutableMap<String, CelType> allTypes;

    @Override
    public ImmutableCollection<CelType> types() {
      return allTypes.values();
    }

    @Override
    public Optional<CelType> findType(String typeName) {
      for (CelTypeProvider typeProvider : typeProviders) {
        Optional<CelType> resolvedType = typeProvider.findType(typeName);
        if (resolvedType.isPresent()) {
          return resolvedType;
        }
      }
      return Optional.empty();
    }

    public CombinedCelTypeProvider(CelTypeProvider first, CelTypeProvider second) {
      this(ImmutableList.of(first, second));
    }

    public CombinedCelTypeProvider(ImmutableList<CelTypeProvider> typeProviders) {
      this.typeProviders = Objects.requireNonNull(typeProviders);
      Map<String, CelType> allTypes = new LinkedHashMap<>();
      typeProviders.forEach(
          typeProvider ->
              typeProvider.types().forEach(type -> allTypes.putIfAbsent(type.name(), type)));
      this.allTypes = ImmutableMap.copyOf(allTypes);
    }
  }
}
