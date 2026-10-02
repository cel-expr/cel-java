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

package dev.cel.extensions;

import static com.google.common.collect.ImmutableSet.toImmutableSet;

import com.google.common.collect.ImmutableSet;
import com.google.errorprone.annotations.Immutable;
import dev.cel.checker.CelCheckerBuilder;
import dev.cel.common.CelFunctionDecl;
import dev.cel.common.CelOverloadDecl;
import dev.cel.common.types.ListType;
import dev.cel.common.types.OptionalType;
import dev.cel.common.types.SimpleType;
import dev.cel.compiler.CelCompilerLibrary;
import java.util.Set;

/** Internal implementation of CEL regex compile-time extensions. */
@Immutable
public final class CelRegexCompilerLibrary
    implements CelCompilerLibrary, CelExtensionLibrary.FeatureSet {

  private static final String REGEX_REPLACE_FUNCTION = "regex.replace";
  private static final String REGEX_EXTRACT_FUNCTION = "regex.extract";
  private static final String REGEX_EXTRACT_ALL_FUNCTION = "regex.extractAll";

  /** Supported functions for the Regex compiler extension. */
  public enum Function {
    REPLACE(
        CelFunctionDecl.newFunctionDeclaration(
            REGEX_REPLACE_FUNCTION,
            CelOverloadDecl.newGlobalOverload(
                "regex_replaceAll_string_string_string",
                "Replaces all the matched values using the given replace string.",
                SimpleType.STRING,
                SimpleType.STRING,
                SimpleType.STRING,
                SimpleType.STRING),
            CelOverloadDecl.newGlobalOverload(
                "regex_replaceCount_string_string_string_int",
                "Replaces the given number of matched values using the given replace string.",
                SimpleType.STRING,
                SimpleType.STRING,
                SimpleType.STRING,
                SimpleType.STRING,
                SimpleType.INT))),
    EXTRACT(
        CelFunctionDecl.newFunctionDeclaration(
            REGEX_EXTRACT_FUNCTION,
            CelOverloadDecl.newGlobalOverload(
                "regex_extract_string_string",
                "Returns the first substring that matches the regex.",
                OptionalType.create(SimpleType.STRING),
                SimpleType.STRING,
                SimpleType.STRING))),
    EXTRACTALL(
        CelFunctionDecl.newFunctionDeclaration(
            REGEX_EXTRACT_ALL_FUNCTION,
            CelOverloadDecl.newGlobalOverload(
                "regex_extractAll_string_string",
                "Returns an array of all substrings that match the regex.",
                ListType.create(SimpleType.STRING),
                SimpleType.STRING,
                SimpleType.STRING)));

    private final CelFunctionDecl functionDecl;

    String getFunction() {
      return functionDecl.name();
    }

    public CelFunctionDecl getFunctionDecl() {
      return functionDecl;
    }

    Function(CelFunctionDecl functionDecl) {
      this.functionDecl = functionDecl;
    }
  }

  private static final class Library implements CelExtensionLibrary<CelRegexCompilerLibrary> {
    private final CelRegexCompilerLibrary version0 =
        new CelRegexCompilerLibrary(0, ImmutableSet.copyOf(Function.values()));

    @Override
    public String name() {
      return "regex";
    }

    @Override
    public ImmutableSet<CelRegexCompilerLibrary> versions() {
      return ImmutableSet.of(version0);
    }
  }

  private static final Library LIBRARY = new Library();

  public static CelExtensionLibrary<CelRegexCompilerLibrary> library() {
    return LIBRARY;
  }

  /** Returns the latest version of the 'regex' compiler extension. */
  public static CelRegexCompilerLibrary regex() {
    return library().latest();
  }

  /** Returns the specified version of the 'regex' compiler extension. */
  public static CelRegexCompilerLibrary regex(int version) {
    return library().version(version);
  }

  /** Returns the 'regex' compiler extension with only the specified functions. */
  public static CelRegexCompilerLibrary regex(Function... functions) {
    return regex(ImmutableSet.copyOf(functions));
  }

  /** Returns the 'regex' compiler extension with only the specified functions. */
  public static CelRegexCompilerLibrary regex(Set<Function> functions) {
    return new CelRegexCompilerLibrary(functions);
  }

  private final ImmutableSet<Function> functions;
  private final int version;

  CelRegexCompilerLibrary(Set<Function> functions) {
    this(-1, functions);
  }

  private CelRegexCompilerLibrary(int version, Set<Function> functions) {
    this.version = version;
    this.functions = ImmutableSet.copyOf(functions);
  }

  @Override
  public int version() {
    return version;
  }

  @Override
  public ImmutableSet<CelFunctionDecl> functions() {
    return functions.stream().map(Function::getFunctionDecl).collect(toImmutableSet());
  }

  @Override
  public void setCheckerOptions(CelCheckerBuilder checkerBuilder) {
    functions.forEach(function -> checkerBuilder.addFunctionDeclarations(function.functionDecl));
  }
}
