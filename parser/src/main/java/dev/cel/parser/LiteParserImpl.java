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
import com.google.errorprone.annotations.CheckReturnValue;
import com.google.errorprone.annotations.Immutable;
import dev.cel.common.CelOptions;
import dev.cel.common.CelSource;
import dev.cel.common.CelValidationResult;
import dev.cel.common.annotations.Internal;

/**
 * Modernized lite parser implementation for CEL using the Pratt parser.
 *
 * <p>CEL Library Internals. Do Not Use. Consumers should use {@link CelLiteParserFactory} instead.
 */
@Immutable
@Internal
final class LiteParserImpl extends CelParserBase {

  static Builder newBuilder() {
    return new Builder();
  }

  @Override
  public CelValidationResult parse(CelSource source) {
    return PrattParser.parse(checkNotNull(source), getOptions(), getMacros());
  }

  @Override
  public CelParserBuilder toParserBuilder() {
    return populateBuilder(new Builder());
  }

  static final class Builder extends CelParserBase.Builder<Builder> {

    /** Throws if an unsupported flag in CelOptions is toggled. */
    private static void assertAllowedCelOptions(CelOptions celOptions) {
      String prefix = "Misconfigured CelOptions: ";
      if (!celOptions.enablePrattParser()) {
        throw new IllegalArgumentException(prefix + "enablePrattParser cannot be disabled.");
      }
    }

    @Override
    @CheckReturnValue
    public CelParser build() {
      ImmutableSet<CelParserLibrary> parserLibrarySet = buildLibraries();
      assertAllowedCelOptions(getOptions());

      return new LiteParserImpl(
          buildMacroMap(),
          buildCustomMacros(),
          getOptions(),
          buildStandardMacros(),
          parserLibrarySet);
    }

    private Builder() {
      setOptions(CelOptions.current().enablePrattParser(true).build());
    }
  }

  private LiteParserImpl(
      ImmutableMap<String, CelMacro> macros,
      ImmutableMap<String, CelMacro> customMacros,
      CelOptions options,
      ImmutableList<CelStandardMacro> standardMacros,
      ImmutableSet<CelParserLibrary> parserLibraries) {
    super(macros, customMacros, options, standardMacros, parserLibraries);
  }
}
