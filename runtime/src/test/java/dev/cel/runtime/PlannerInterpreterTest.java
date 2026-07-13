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

package dev.cel.runtime;

import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.CelContainer;
import dev.cel.common.CelOptions;
import dev.cel.common.CelValidationException;
import dev.cel.common.types.CelTypeProvider;
import dev.cel.expr.conformance.proto3.TestAllTypes;
import dev.cel.extensions.CelExtensions;
import org.junit.runner.RunWith;

/** Interpreter tests using ProgramPlanner */
@RunWith(TestParameterInjector.class)
public class PlannerInterpreterTest extends AbstractPlannerInterpreterTest {

  @TestParameter boolean isParseOnly;

  @Override
  protected CelRuntimeBuilder newBaseRuntimeBuilder(CelOptions celOptions) {
    return CelRuntimeFactory.plannerRuntimeBuilder()
        .addLateBoundFunctions("record")
        .setOptions(celOptions)
        .addLibraries(CelExtensions.optional())
        .addFileTypes(TEST_FILE_DESCRIPTORS)
        .addMessageTypes(TestAllTypes.getDescriptor());
  }

  @Override
  protected void setContainer(CelContainer container) {
    super.setContainer(container);
    this.celRuntime = this.celRuntime.toRuntimeBuilder().setContainer(container).build();
  }

  @Override
  protected CelAbstractSyntaxTree prepareTest(CelTypeProvider typeProvider) {
    super.prepareCompiler(typeProvider);

    CelAbstractSyntaxTree ast;
    try {
      ast = celCompiler.parse(source, testSourceDescription()).getAst();
    } catch (CelValidationException e) {
      printTestValidationError(e);
      return null;
    }

    if (isParseOnly) {
      return ast;
    }

    try {
      return celCompiler.check(ast).getAst();
    } catch (CelValidationException e) {
      printTestValidationError(e);
      return null;
    }
  }
}
