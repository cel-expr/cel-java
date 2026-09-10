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

package dev.cel.parser;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.stream.Collectors.toCollection;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.errorprone.annotations.CheckReturnValue;
import com.google.errorprone.annotations.Immutable;
import dev.cel.common.CelOptions;
import dev.cel.common.CelSource;
import dev.cel.common.CelValidationResult;
import dev.cel.common.annotations.Internal;
import dev.cel.common.internal.EnvVisitable;
import dev.cel.common.internal.EnvVisitor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Modernized parser implementation for CEL.
 *
 * <p>CEL Library Internals. Do Not Use. Consumers should use factories, such as {@link
 * CelParserFactory} instead to instantiate a parser.
 */
@Immutable
@Internal
public final class CelParserImpl implements CelParser, EnvVisitable {

  // Common feature flags to be used with all calls.

  // Set of macros configured for parsing.
  private final ImmutableMap<String, CelMacro> macros;

  // Specific options for limits on parsing power.
  private final CelOptions options;

  private final ImmutableList<CelStandardMacro> standardMacros;

  @SuppressWarnings("Immutable") // Interface not marked as immutable, however it should be.
  private final ImmutableSet<CelParserLibrary> parserLibraries;

  /** Creates a new {@link Builder}. */
  public static CelParserBuilder newBuilder() {
    return new Builder().setOptions(CelOptions.DEFAULT);
  }

  @Override
  public CelValidationResult parse(String expression, String description) {
    return parse(CelSource.newBuilder(expression).setDescription(description).build());
  }

  @Override
  public CelValidationResult parse(CelSource source) {
    return Parser.parse(this, source, getOptions());
  }

  @Override
  public CelParserBuilder toParserBuilder() {
    HashSet<String> standardMacroKeys =
        standardMacros.stream()
            .map(s -> s.getDefinition().getKey())
            .collect(Collectors.toCollection(HashSet::new));

    return new Builder()
        .setOptions(options)
        .setStandardMacros(standardMacros)
        .addMacros(
            // Separate standard macros from the custom macros before constructing the builder
            macros.values().stream()
                .filter(m -> !standardMacroKeys.contains(m.getKey()))
                .collect(toCollection(ArrayList::new)))
        .addLibraries(parserLibraries);
  }

  Optional<CelMacro> findMacro(String key) {
    return Optional.ofNullable(macros.get(key));
  }

  ImmutableMap<String, CelMacro> getMacros() {
    return macros;
  }

  /** Return the options the {@link CelParser} was originally created with. */
  public CelOptions getOptions() {
    return options;
  }

  /** Builder for {@link CelParserImpl}. */
  public static final class Builder implements CelParserBuilder {

    private final List<CelStandardMacro> standardMacros;
    private final Map<String, CelMacro> macros;
    private final ImmutableSet.Builder<CelParserLibrary> celParserLibraries;
    private CelOptions options;

    @Override
    public CelParserBuilder setStandardMacros(CelStandardMacro... macros) {
      checkNotNull(macros);
      return setStandardMacros(Arrays.asList(macros));
    }

    @Override
    public CelParserBuilder setStandardMacros(Iterable<CelStandardMacro> macros) {
      checkNotNull(macros);
      this.standardMacros.clear();
      Iterables.addAll(this.standardMacros, macros);
      return this;
    }

    @Override
    public CelParserBuilder addMacros(CelMacro... macros) {
      checkNotNull(macros);
      return addMacros(Arrays.asList(macros));
    }

    @Override
    public CelParserBuilder addMacros(Iterable<CelMacro> macros) {
      checkNotNull(macros);
      for (CelMacro m : macros) {
        CelMacro macro = checkNotNull(m);
        this.macros.put(macro.getKey(), macro);
      }
      return this;
    }

    @Override
    public CelParserBuilder addLibraries(CelParserLibrary... libraries) {
      checkNotNull(libraries);
      return this.addLibraries(Arrays.asList(libraries));
    }

    @Override
    public CelParserBuilder addLibraries(Iterable<? extends CelParserLibrary> libraries) {
      checkNotNull(libraries);
      this.celParserLibraries.addAll(libraries);
      return this;
    }

    @CanIgnoreReturnValue
    @Override
    public Builder setOptions(CelOptions options) {
      this.options = checkNotNull(options);
      return this;
    }

    @Override
    public CelOptions getOptions() {
      return this.options;
    }

    // The following getters exist for asserting immutability for collections held by this builder,
    // and shouldn't be exposed to the public.
    @VisibleForTesting
    List<CelStandardMacro> getStandardMacros() {
      return this.standardMacros;
    }

    @VisibleForTesting
    Map<String, CelMacro> getMacros() {
      return this.macros;
    }

    @VisibleForTesting
    ImmutableSet.Builder<CelParserLibrary> getParserLibraries() {
      return this.celParserLibraries;
    }

    @Override
    @CheckReturnValue
    public CelParserImpl build() {
      ImmutableSet<CelParserLibrary> parserLibrarySet = celParserLibraries.build();

      // Add libraries, such as extensions
      parserLibrarySet.forEach(celLibrary -> celLibrary.setParserOptions(this));

      ImmutableMap.Builder<String, CelMacro> macroMapBuilder = ImmutableMap.builder();
      macroMapBuilder.putAll(macros);
      standardMacros.stream()
          .map(CelStandardMacro::getDefinition)
          .forEach(celMacro -> macroMapBuilder.put(celMacro.getKey(), celMacro));

      return new CelParserImpl(
          macroMapBuilder.buildOrThrow(),
          options,
          ImmutableList.copyOf(standardMacros),
          celParserLibraries.build());
    }

    private Builder() {
      this.macros = new HashMap<>();
      this.celParserLibraries = ImmutableSet.builder();
      this.standardMacros = new ArrayList<>();
    }
  }

  private CelParserImpl(
      ImmutableMap<String, CelMacro> macros,
      CelOptions options,
      ImmutableList<CelStandardMacro> standardMacros,
      ImmutableSet<CelParserLibrary> parserLibraries) {
    this.macros = macros;
    this.options = checkNotNull(options);
    this.standardMacros = standardMacros;
    this.parserLibraries = parserLibraries;
  }

  @Override
  public void accept(EnvVisitor visitor) {
    macros.forEach((name, macro) -> visitor.visitMacro(macro));
  }
}
