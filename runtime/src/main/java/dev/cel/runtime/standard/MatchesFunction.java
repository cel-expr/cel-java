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

package dev.cel.runtime.standard;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.errorprone.annotations.Immutable;
import com.google.re2j.Pattern;
import dev.cel.common.CelOptions;
import dev.cel.common.annotations.Internal;
import dev.cel.runtime.CelFunctionBinding;
import dev.cel.runtime.CelFunctionOverload;
import dev.cel.runtime.CelResolvedOverload;
import dev.cel.runtime.RuntimeEquality;
import dev.cel.runtime.RuntimeHelpers;
import java.util.Arrays;

/** Standard function for {@code matches}. */
public final class MatchesFunction extends CelStandardFunction {
  private static final String MATCHES_FUNCTION = "matches";
  private static final MatchesFunction ALL_OVERLOADS = create(MatchesOverload.values());

  public static MatchesFunction create() {
    return ALL_OVERLOADS;
  }

  public static MatchesFunction create(MatchesFunction.MatchesOverload... overloads) {
    return create(Arrays.asList(overloads));
  }

  public static MatchesFunction create(Iterable<MatchesFunction.MatchesOverload> overloads) {
    return new MatchesFunction(ImmutableSet.copyOf(overloads));
  }

  @Internal
  public static boolean isMatchesOverload(CelResolvedOverload resolvedOverload) {
    return resolvedOverload.getDefinition() instanceof StandardMatchesOverload;
  }

  @Internal
  public static CelResolvedOverload newPrecompiledOverload(
      CelResolvedOverload resolvedOverload, String regexPattern) {
    CelOptions celOptions = ((StandardMatchesOverload) resolvedOverload.getDefinition()).celOptions;
    Pattern compiledPattern = RuntimeHelpers.compileRegexPattern(regexPattern, celOptions);
    String overloadId = resolvedOverload.getOverloadId();
    CelFunctionBinding binding =
        CelFunctionBinding.from(
            overloadId,
            String.class,
            target -> RuntimeHelpers.matches(target, compiledPattern, celOptions));
    return CelResolvedOverload.of(
        MATCHES_FUNCTION,
        overloadId,
        binding.getDefinition(),
        binding.isStrict(),
        binding.getArgTypes());
  }

  /** Overloads for the standard function. */
  public enum MatchesOverload implements CelStandardOverload {
    MATCHES(MATCHES_FUNCTION),
    // Duplicate receiver-style matches overload.
    MATCHES_STRING("matches_string"),
    ;

    private final String overloadId;

    @Override
    public CelFunctionBinding newFunctionBinding(
        CelOptions celOptions, RuntimeEquality runtimeEquality) {
      return CelFunctionBinding.from(
          overloadId,
          ImmutableList.of(String.class, String.class),
          new StandardMatchesOverload(celOptions));
    }

    MatchesOverload(String overloadId) {
      this.overloadId = overloadId;
    }
  }

  @Immutable
  private static final class StandardMatchesOverload implements CelFunctionOverload {
    private final CelOptions celOptions;

    @Override
    public Object apply(Object[] args) {
      return RuntimeHelpers.matches((String) args[0], (String) args[1], celOptions);
    }

    private StandardMatchesOverload(CelOptions celOptions) {
      this.celOptions = celOptions;
    }
  }

  private MatchesFunction(ImmutableSet<CelStandardOverload> overloads) {
    super(MATCHES_FUNCTION, overloads);
  }
}
