// Copyright 2024 Google LLC
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

package dev.cel.extensions;

import static com.google.common.collect.ImmutableSet.toImmutableSet;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Streams;
import com.google.errorprone.annotations.InlineMe;
import dev.cel.common.CelOptions;
import dev.cel.extensions.CelMathExtensions.Function;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Collections of CEL Extensions.
 *
 * <p>To use, supply the desired extensions into {@code CelCompiler} and {@code CelRuntime} through
 * their builders: {@code CelCompilerBuilder#addLibraries} and {@code
 * CelRuntimeBuilder#addLibraries}.
 */
public final class CelExtensions {
  private static final CelStringExtensions STRING_EXTENSIONS_ALL = new CelStringExtensions();
  private static final CelProtoExtensions PROTO_EXTENSIONS = new CelProtoExtensions();
  private static final CelBindingsExtensions BINDINGS_EXTENSIONS = new CelBindingsExtensions();
  private static final CelRegexExtensions REGEX_EXTENSIONS = new CelRegexExtensions();
  private static final CelComprehensionsExtensions COMPREHENSIONS_EXTENSIONS =
      new CelComprehensionsExtensions();

  /**
   * Implementation of optional values.
   *
   * <p>Refer to README.md for available functions.
   */
  public static CelOptionalLibrary optional() {
    return CelOptionalLibrary.library().latest();
  }

  /**
   * Implementation of optional values.
   *
   * <p>Refer to README.md for available functions for each supported version.
   */
  public static CelOptionalLibrary optional(int version) {
    return CelOptionalLibrary.library().version(version);
  }

  /**
   * Extended functions for string manipulation.
   *
   * <p>Refer to README.md for available functions.
   *
   * <p>This will include all functions denoted in {@link CelStringExtensions.Function}, including
   * any future additions. To expose only a subset of functions, use {@link
   * #strings(CelStringExtensions.Function...)} instead.
   */
  public static CelStringExtensions strings() {
    return STRING_EXTENSIONS_ALL;
  }

  /**
   * Extended functions for string manipulation.
   *
   * <p>Refer to README.md for available functions.
   *
   * <p>This will include only the specific functions denoted by {@link
   * CelStringExtensions.Function}.
   */
  public static CelStringExtensions strings(CelStringExtensions.Function... functions) {
    return strings(ImmutableSet.copyOf(functions));
  }

  /**
   * Extended functions for string manipulation.
   *
   * <p>Refer to README.md for available functions.
   *
   * <p>This will include only the specific functions denoted by {@link
   * CelStringExtensions.Function}.
   */
  public static CelStringExtensions strings(Set<CelStringExtensions.Function> functions) {
    return new CelStringExtensions(functions);
  }

  /**
   * Extended macros for proto manipulation.
   *
   * <p>This adds {@code proto.getExt} and {@code proto.hasExt}. See README.md for their
   * documentation.
   *
   * <p>Note, these functions use the 'proto' namespace; however, at the time of macro expansion the
   * namespace looks just like any other identifier. If you are currently using a variable named
   * 'proto', the macro will likely work just as intended; however, there is some chance for
   * collision.
   */
  public static CelProtoExtensions protos() {
    return PROTO_EXTENSIONS;
  }

  /**
   * Extended math helper macros and functions.
   *
   * <p>This adds {@code math.greatest} and {@code math.least}. See README.md for their
   * documentation.
   *
   * <p>Note, all macros use the 'math' namespace; however, at the time of macro expansion the
   * namespace looks just like any other identifier. If you are currently using a variable named
   * 'math', the macro will likely work just as intended; however, there is some chance for
   * collision.
   *
   * <p>This will include all functions denoted in {@link CelMathExtensions.Function}, including any
   * future additions. To expose only a subset of these, use {@link #math(CelOptions,
   * CelMathExtensions.Function...)} or {@link #math(CelOptions,int)} instead.
   */
  public static CelMathExtensions math() {
    return CelMathExtensions.library().latest();
  }

  /**
   * Returns the specified version of the 'math' extension.
   *
   * <p>Refer to README.md for functions available in each version.
   */
  public static CelMathExtensions math(int version) {
    return CelMathExtensions.library().version(version);
  }

  /**
   * Extended math helper macros and functions.
   *
   * <p>This adds {@code math.greatest} and {@code math.least}. See README.md for their
   * documentation.
   *
   * <p>Note, all macros use the 'math' namespace; however, at the time of macro * expansion the
   * namespace looks just like any other identifier. If you are currently using a variable named
   * 'math', the macro will likely work just as intended; however, there is some chance for
   * collision.
   *
   * <p>This will include only the specific functions denoted by {@link CelMathExtensions.Function}.
   */
  public static CelMathExtensions math(CelMathExtensions.Function... functions) {
    return math(ImmutableSet.copyOf(functions));
  }

  /**
   * Extended math helper macros and functions.
   *
   * <p>This adds {@code math.greatest} and {@code math.least}. See README.md for their
   * documentation.
   *
   * <p>Note, all macros use the 'math' namespace; however, at the time of macro * expansion the
   * namespace looks just like any other identifier. If you are currently using a variable named
   * 'math', the macro will likely work just as intended; however, there is some chance for
   * collision.
   *
   * <p>This will include only the specific functions denoted by {@link CelMathExtensions.Function}.
   */
  public static CelMathExtensions math(Set<CelMathExtensions.Function> functions) {
    return new CelMathExtensions(functions);
  }

  /**
   * @deprecated Use {@link #math()} instead.
   */
  @Deprecated
  @InlineMe(replacement = "CelExtensions.math()", imports = "dev.cel.extensions.CelExtensions")
  public static CelMathExtensions math(CelOptions unused) {
    return math();
  }

  /**
   * @deprecated Use {@link #math(int)} instead.
   */
  @Deprecated
  @InlineMe(
      replacement = "CelExtensions.math(version)",
      imports = "dev.cel.extensions.CelExtensions")
  public static CelMathExtensions math(CelOptions unused, int version) {
    return math(version);
  }

  /**
   * @deprecated Use {@link #math(Function...)} instead.
   */
  @Deprecated
  public static CelMathExtensions math(CelOptions unused, CelMathExtensions.Function... functions) {
    return math(ImmutableSet.copyOf(functions));
  }

  /**
   * @deprecated Use {@link #math(Set)} instead.
   */
  @Deprecated
  @InlineMe(
      replacement = "CelExtensions.math(functions)",
      imports = "dev.cel.extensions.CelExtensions")
  public static CelMathExtensions math(
      CelOptions unused, Set<CelMathExtensions.Function> functions) {
    return math(functions);
  }

  /**
   * Local variable binding macro(s).
   *
   * <p>This exposes {@code cel.bind} as a macro for use within expressions. See README.md for its
   * documentation.
   *
   * <p>This change does not introduce any new functions, but rather leverages the comprehension AST
   * to produce an expression which will introduce a new variable into the expression.
   */
  public static CelBindingsExtensions bindings() {
    return BINDINGS_EXTENSIONS;
  }

  /**
   * @deprecated Use {@link #encoders(CelOptions) instead.}
   */
  @Deprecated
  public static CelEncoderExtensions encoders() {
    return new CelEncoderExtensions(CelOptions.DEFAULT);
  }

  /**
   * Extended functions for string, byte and object encodings.
   *
   * <p>This adds {@code base64.encode} and {@code base64.decode} functions. See README.md for their
   * documentation.
   *
   * @param celOptions This should be the same {@link CelOptions} object used to configure
   *     compilation/runtime environments.
   */
  public static CelEncoderExtensions encoders(CelOptions celOptions) {
    return new CelEncoderExtensions(celOptions);
  }

  /**
   * Extended functions for Set manipulation.
   *
   * <p>Refer to README.md for available functions.
   *
   * <p>This will include all functions denoted in {@link SetsFunction}, including any future
   * additions. To expose only a subset of functions, use {@link #sets(CelOptions, SetsFunction...)}
   * instead.
   */
  public static CelSetsExtensions sets(CelOptions celOptions) {
    return new CelSetsExtensions(celOptions);
  }

  /**
   * Extended functions for Set manipulation.
   *
   * <p>Refer to README.md for available functions.
   *
   * <p>This will include only the specific functions denoted by {@link SetsFunction}.
   */
  public static CelSetsExtensions sets(CelOptions celOptions, SetsFunction... functions) {
    return sets(celOptions, ImmutableSet.copyOf(functions));
  }

  /**
   * Extended functions for Set manipulation.
   *
   * <p>Refer to README.md for available functions.
   *
   * <p>This will include only the specific functions denoted by {@link SetsFunction}.
   */
  public static CelSetsExtensions sets(CelOptions celOptions, Set<SetsFunction> functions) {
    return new CelSetsExtensions(celOptions, functions);
  }

  /**
   * Extended functions for List manipulation.
   *
   * <p>Refer to README.md for available functions.
   *
   * <p>This will include only the specific functions denoted by {@link
   * CelListsExtensions.Function}.
   */
  public static CelListsExtensions lists() {
    return CelListsExtensions.library().latest();
  }

  /**
   * Extended functions for List manipulation.
   *
   * <p>Refer to README.md for functions available in each version.
   */
  public static CelListsExtensions lists(int version) {
    return CelListsExtensions.library().version(version);
  }

  /**
   * Extended functions for List manipulation.
   *
   * <p>Refer to README.md for available functions.
   *
   * <p>This will include only the specific functions denoted by {@link
   * CelListsExtensions.Function}.
   */
  public static CelListsExtensions lists(CelListsExtensions.Function... functions) {
    return lists(ImmutableSet.copyOf(functions));
  }

  /**
   * Extended functions for List manipulation.
   *
   * <p>Refer to README.md for available functions.
   *
   * <p>This will include all functions denoted in {@link CelListsExtensions.Function}, including
   * any future additions. To expose only a subset of functions, use {@link
   * #lists(CelListsExtensions.Function...)} instead.
   */
  public static CelListsExtensions lists(Set<CelListsExtensions.Function> functions) {
    return new CelListsExtensions(functions);
  }

  /**
   * Extended functions for Regular Expressions.
   *
   * <p>Refer to README.md for available functions.
   *
   * <p>This will include all functions denoted in {@link CelRegexExtensions.Function}, including
   * any future additions.
   */
  public static CelRegexExtensions regex() {
    return REGEX_EXTENSIONS;
  }

  /**
   * Extended functions for Two Variable Comprehensions Expressions.
   *
   * <p>Refer to README.md for available functions.
   *
   * <p>This will include all functions denoted in {@link CelComprehensionsExtensions.Function},
   * including any future additions.
   */
  public static CelComprehensionsExtensions comprehensions() {
    return COMPREHENSIONS_EXTENSIONS;
  }

  /**
   * Extensions for supporting native Java types (POJOs) in CEL.
   *
   * <p>Refer to README.md for details on property discovery, type mapping, and limitations.
   *
   * <p>Note: Passing classes with unsupported types or anonymous/local classes will result in an
   * {@link IllegalArgumentException} when the runtime is built.
   */
  public static CelNativeTypesExtensions nativeTypes(Class<?>... classes) {
    return CelNativeTypesExtensions.nativeTypes(classes);
  }

  /**
   * Retrieves all function names used by every extension libraries.
   *
   * <p>Note: Certain extensions such as {@link CelProtoExtensions} and {@link
   * CelBindingsExtensions} are implemented via macros, not functions, and those are not included
   * here.
   */
  public static ImmutableSet<String> getAllFunctionNames() {
    return Streams.concat(
            EnumSet.allOf(Function.class).stream().map(CelMathExtensions.Function::getFunction),
            EnumSet.allOf(CelStringExtensions.Function.class).stream()
                .map(CelStringExtensions.Function::getFunction),
            EnumSet.allOf(SetsFunction.class).stream().map(SetsFunction::getFunction),
            EnumSet.allOf(CelEncoderExtensions.Function.class).stream()
                .map(CelEncoderExtensions.Function::getFunction),
            EnumSet.allOf(CelListsExtensions.Function.class).stream()
                .map(CelListsExtensions.Function::getFunction),
            EnumSet.allOf(CelRegexExtensions.Function.class).stream()
                .map(CelRegexExtensions.Function::getFunction),
            Stream.of(
                    CelOptionalLibrary.Function.VALUE,
                    CelOptionalLibrary.Function.HAS_VALUE,
                    CelOptionalLibrary.Function.OPTIONAL_NONE,
                    CelOptionalLibrary.Function.OPTIONAL_OF,
                    CelOptionalLibrary.Function.OPTIONAL_UNWRAP,
                    CelOptionalLibrary.Function.OPTIONAL_OF_NON_ZERO_VALUE)
                .map(CelOptionalLibrary.Function::getFunction),
            EnumSet.allOf(CelComprehensionsExtensions.Function.class).stream()
                .map(CelComprehensionsExtensions.Function::getFunction))
        .collect(toImmutableSet());
  }

  public static CelExtensionLibrary<? extends CelExtensionLibrary.FeatureSet> getExtensionLibrary(
      String name, CelOptions options) {
    switch (name) {
      case "bindings":
        return CelBindingsExtensions.library();
      case "encoders":
        return CelEncoderExtensions.library(options);
      case "lists":
        return CelListsExtensions.library();
      case "math":
        return CelMathExtensions.library();
      case "optional":
        return CelOptionalLibrary.library();
      case "protos":
        return CelProtoExtensions.library();
      case "regex":
        return CelRegexExtensions.library();
      case "sets":
        return CelSetsExtensions.library(options);
      case "strings":
        return CelStringExtensions.library();
      case "comprehensions":
        return CelComprehensionsExtensions.library();
      // TODO: add support for remaining standard extensions
      default:
        throw new IllegalArgumentException("Unknown standard extension '" + name + "'");
    }
  }

  private CelExtensions() {}
}
