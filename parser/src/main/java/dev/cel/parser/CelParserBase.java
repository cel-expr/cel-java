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

package dev.cel.parser;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.errorprone.annotations.Immutable;
import dev.cel.common.CelOptions;
import dev.cel.common.CelSource;
import dev.cel.common.CelValidationResult;
import dev.cel.common.annotations.Internal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Abstract base class containing shared logic for CEL parser implementations.
 *
 * <p>CEL Library Internals. Do Not Use.
 */
@Immutable
@Internal
abstract class CelParserBase implements CelParser {

  private final ImmutableMap<String, CelMacro> macros;
  private final ImmutableMap<String, CelMacro> customMacros;
  private final CelOptions options;
  private final ImmutableList<CelStandardMacro> standardMacros;

  @SuppressWarnings("Immutable") // Interface not marked as immutable, however it should be.
  private final ImmutableSet<CelParserLibrary> parserLibraries;

  @Override
  public final CelValidationResult parse(String expression, String description) {
    return parse(CelSource.newBuilder(expression).setDescription(description).build());
  }

  /** Return the options the {@link CelParser} was originally created with. */
  final CelOptions getOptions() {
    return options;
  }

  final ImmutableMap<String, CelMacro> getMacros() {
    return macros;
  }

  final ImmutableList<CelStandardMacro> getStandardMacros() {
    return standardMacros;
  }

  final ImmutableSet<CelParserLibrary> getParserLibraries() {
    return parserLibraries;
  }

  final Optional<CelMacro> findMacro(String key) {
    return Optional.ofNullable(macros.get(key));
  }

  @CanIgnoreReturnValue
  protected final <B extends Builder<B>> B populateBuilder(B builder) {
    checkNotNull(builder);

    return builder
        .setOptions(options)
        .setStandardMacros(standardMacros)
        .addMacros(customMacros.values())
        .addLibraries(parserLibraries);
  }

  abstract static class Builder<B extends Builder<B>> implements CelParserBuilder {

    private final Set<CelStandardMacro> standardMacros;
    private final Map<String, CelMacro> macros;
    private final Set<CelParserLibrary> celParserLibraries;
    private CelOptions options;

    @SuppressWarnings("unchecked") // Safe cast for fluent builder chaining in subclasses.
    protected B self() {
      return (B) this;
    }

    @CanIgnoreReturnValue
    @Override
    public B setStandardMacros(CelStandardMacro... macros) {
      checkNotNull(macros);
      return setStandardMacros(Arrays.asList(macros));
    }

    @CanIgnoreReturnValue
    @Override
    public B setStandardMacros(Iterable<CelStandardMacro> macros) {
      checkNotNull(macros);
      this.standardMacros.clear();
      for (CelStandardMacro macro : macros) {
        this.standardMacros.add(checkNotNull(macro));
      }
      return self();
    }

    @CanIgnoreReturnValue
    @Override
    public B addMacros(CelMacro... macros) {
      checkNotNull(macros);
      return addMacros(Arrays.asList(macros));
    }

    @CanIgnoreReturnValue
    @Override
    public B addMacros(Iterable<CelMacro> macros) {
      checkNotNull(macros);
      for (CelMacro m : macros) {
        CelMacro macro = checkNotNull(m);
        this.macros.put(macro.getKey(), macro);
      }
      return self();
    }

    @CanIgnoreReturnValue
    @Override
    public B addLibraries(CelParserLibrary... libraries) {
      checkNotNull(libraries);
      return this.addLibraries(Arrays.asList(libraries));
    }

    @CanIgnoreReturnValue
    @Override
    public B addLibraries(Iterable<? extends CelParserLibrary> libraries) {
      checkNotNull(libraries);
      for (CelParserLibrary library : libraries) {
        this.celParserLibraries.add(checkNotNull(library));
      }
      return self();
    }

    @CanIgnoreReturnValue
    @Override
    public B setOptions(CelOptions options) {
      this.options = checkNotNull(options);
      return self();
    }

    @Override
    public CelOptions getOptions() {
      return this.options;
    }

    // Exists for test assertions in CelParserImplTest.
    List<CelStandardMacro> getStandardMacros() {
      return new ArrayList<>(this.standardMacros);
    }

    // Exists for test assertions in CelParserImplTest.
    Map<String, CelMacro> getMacros() {
      return this.macros;
    }

    // Exists for test assertions in CelParserImplTest.
    ImmutableSet.Builder<CelParserLibrary> getParserLibraries() {
      return ImmutableSet.<CelParserLibrary>builder().addAll(this.celParserLibraries);
    }

    protected ImmutableList<CelStandardMacro> buildStandardMacros() {
      return ImmutableList.copyOf(standardMacros);
    }

    protected ImmutableMap<String, CelMacro> buildCustomMacros() {
      return ImmutableMap.copyOf(macros);
    }

    protected ImmutableMap<String, CelMacro> buildMacroMap() {
      ImmutableMap.Builder<String, CelMacro> macroMapBuilder = ImmutableMap.builder();
      macroMapBuilder.putAll(macros);
      for (CelStandardMacro standardMacro : standardMacros) {
        CelMacro celMacro = standardMacro.getDefinition();
        macroMapBuilder.put(celMacro.getKey(), celMacro);
      }
      return macroMapBuilder.buildOrThrow();
    }

    protected ImmutableSet<CelParserLibrary> buildLibraries() {
      ImmutableSet<CelParserLibrary> parserLibrarySet = ImmutableSet.copyOf(celParserLibraries);
      parserLibrarySet.forEach(celLibrary -> celLibrary.setParserOptions(this));
      return parserLibrarySet;
    }

    protected Builder() {
      this.macros = new HashMap<>();
      this.celParserLibraries = new LinkedHashSet<>();
      this.standardMacros = new LinkedHashSet<>();
      this.options = CelOptions.DEFAULT;
    }
  }

  protected CelParserBase(
      ImmutableMap<String, CelMacro> allMacros,
      ImmutableMap<String, CelMacro> customMacros,
      CelOptions options,
      ImmutableList<CelStandardMacro> standardMacros,
      ImmutableSet<CelParserLibrary> parserLibraries) {
    this.macros = checkNotNull(allMacros);
    this.customMacros = checkNotNull(customMacros);
    this.options = checkNotNull(options);
    this.standardMacros = checkNotNull(standardMacros);
    this.parserLibraries = checkNotNull(parserLibraries);
  }
}
