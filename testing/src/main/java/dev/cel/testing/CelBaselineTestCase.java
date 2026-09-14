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

package dev.cel.testing;

import static com.google.common.truth.Truth.assertWithMessage;
import static dev.cel.common.CelFunctionDecl.newFunctionDeclaration;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.protobuf.DescriptorProtos.FileDescriptorSet;
import com.google.protobuf.Descriptors.FileDescriptor;
import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.CelContainer;
import dev.cel.common.CelFunctionDecl;
import dev.cel.common.CelOptions;
import dev.cel.common.CelOverloadDecl;
import dev.cel.common.CelValidationException;
import dev.cel.common.CelVarDecl;
import dev.cel.common.types.CelType;
import dev.cel.common.types.CelTypeProvider;
import dev.cel.common.types.CelTypes;
import dev.cel.common.types.ProtoMessageTypeProvider;
import dev.cel.compiler.CelCompiler;
import dev.cel.compiler.CelCompilerBuilder;
import dev.cel.compiler.CelCompilerFactory;
import dev.cel.extensions.CelExtensions;
import dev.cel.parser.CelStandardMacro;
import java.util.ArrayList;
import java.util.List;

/**
 * Base class for CEL tests which generate a string output that can be stored and used as a baseline
 * to ensure consistent behavior on future test runs.
 */
public abstract class CelBaselineTestCase extends BaselineTestCase {
  private final List<CelVarDecl> varDecls = new ArrayList<>();
  private final List<CelFunctionDecl> functionDecls = new ArrayList<>();

  protected String source;
  protected CelContainer container = CelContainer.ofName("");
  protected CelType expectedType;
  protected CelCompiler celCompiler;

  protected static final int COMPREHENSION_MAX_ITERATIONS = 1_000;
  protected static final CelOptions TEST_OPTIONS =
      CelOptions.current()
          .enableHeterogeneousNumericComparisons(true)
          .enableOptionalSyntax(true)
          .comprehensionMaxIterations(1_000)
          .build();

  protected CelBaselineTestCase() {}

  protected CelAbstractSyntaxTree prepareTest(List<FileDescriptor> descriptors) {
    return prepareTest(new ProtoMessageTypeProvider(ImmutableSet.copyOf(descriptors)));
  }

  protected CelAbstractSyntaxTree prepareTest(FileDescriptorSet descriptorSet) {
    return prepareTest(new ProtoMessageTypeProvider(descriptorSet));
  }

  protected CelAbstractSyntaxTree prepareTest(CelTypeProvider typeProvider) {
    prepareCompiler(typeProvider);

    CelAbstractSyntaxTree ast;
    try {
      ast = celCompiler.parse(source, testSourceDescription()).getAst();
    } catch (CelValidationException e) {
      printTestValidationError(e);
      return null;
    }

    try {
      return celCompiler.check(ast).getAst();
    } catch (CelValidationException e) {
      printTestValidationError(e);
      return null;
    }
  }

  private void validateTestSetup() {
    assertWithMessage("The source field must be non-null").that(source).isNotNull();
  }

  protected void prepareCompiler(CelTypeProvider typeProvider) {
    validateTestSetup();
    printTestSetup();

    CelCompilerBuilder celCompilerBuilder =
        CelCompilerFactory.standardCelCompilerBuilder()
            .setOptions(TEST_OPTIONS)
            .setStandardMacros(CelStandardMacro.STANDARD_MACROS)
            .addLibraries(CelExtensions.comprehensions())
            .setContainer(container)
            .setTypeProvider(typeProvider);

    if (expectedType != null) {
      celCompilerBuilder.setResultType(expectedType);
    }

    varDecls.forEach(celCompilerBuilder::addVarDeclarations);
    functionDecls.forEach(celCompilerBuilder::addFunctionDeclarations);

    celCompiler = celCompilerBuilder.build();
  }

  /**
   * Declares a variable with a given {@code name} and {@code type}.
   *
   * @param name Variable name
   */
  protected void declareVariable(String name, CelType type) {
    varDecls.add(CelVarDecl.newVarDeclaration(name, type));
  }

  /** Clears all function and variable declarations. */
  protected void clearAllDeclarations() {
    functionDecls.clear();
    varDecls.clear();
  }

  /** Returns the test source description. */
  protected String testSourceDescription() {
    return "test_location";
  }

  protected void printTestSetup() {
    // Print the source.
    testOutput().printf("Source: %s%n", source);
    for (CelVarDecl varDecl : varDecls) {
      testOutput().println(formatVarDecl(varDecl));
    }
    for (CelFunctionDecl functionDecl : functionDecls) {
      testOutput().println(formatFunctionDecl(functionDecl));
    }

    testOutput().println("=====>");
  }

  protected String formatFunctionDecl(CelFunctionDecl decl) {
    StringBuilder declStr = new StringBuilder();
    declStr.append(String.format("declare %s {%n", decl.name()));
    for (CelOverloadDecl overload : decl.overloads()) {
      declStr.append(
          String.format(
              "  function %s %s%n",
              overload.overloadId(),
              CelTypes.formatFunction(
                  overload.resultType(),
                  ImmutableList.copyOf(overload.parameterTypes()),
                  overload.isInstanceFunction(),
                  /* typeParamToDyn= */ false)));
    }
    declStr.append("}");
    return declStr.toString();
  }

  protected String formatVarDecl(CelVarDecl decl) {
    StringBuilder declStr = new StringBuilder();
    declStr.append(String.format("declare %s {%n", decl.name()));
    declStr.append(String.format("  value %s%n", CelTypes.format(decl.type())));
    declStr.append("}");
    return declStr.toString();
  }

  /**
   * Declares a function with one or more overloads
   *
   * @param functionName Function name
   * @param overloads Function overloads in protobuf representation.
   */
  protected void declareFunction(String functionName, CelOverloadDecl... overloads) {
    this.functionDecls.add(newFunctionDeclaration(functionName, overloads));
  }

  protected void declareGlobalFunction(String name, List<CelType> paramTypes, CelType resultType) {
    declareFunction(name, globalOverload(name, paramTypes, resultType));
  }

  protected void declareMemberFunction(String name, List<CelType> paramTypes, CelType resultType) {
    declareFunction(name, memberOverload(name, paramTypes, resultType));
  }

  protected CelOverloadDecl memberOverload(
      String overloadId, List<CelType> paramTypes, CelType resultType) {
    return overloadBuilder(overloadId, paramTypes, resultType).setIsInstanceFunction(true).build();
  }

  protected CelOverloadDecl globalOverload(
      String overloadId, List<CelType> paramTypes, CelType resultType) {
    return overloadBuilder(overloadId, paramTypes, resultType).setIsInstanceFunction(false).build();
  }

  private CelOverloadDecl.Builder overloadBuilder(
      String overloadId, List<CelType> paramTypes, CelType resultType) {
    return CelOverloadDecl.newBuilder()
        .setOverloadId(overloadId)
        .setResultType(resultType)
        .addParameterTypes(paramTypes);
  }

  protected void printTestValidationError(CelValidationException error) {
    testOutput().println(error.getMessage());
  }
}
