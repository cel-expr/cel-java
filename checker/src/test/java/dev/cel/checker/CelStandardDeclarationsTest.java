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

package dev.cel.checker;

import static com.google.common.truth.Truth.assertThat;
import static dev.cel.common.CelFunctionDecl.newFunctionDeclaration;
import static org.junit.Assert.assertThrows;

import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import com.google.testing.junit.testparameterinjector.TestParameters;
import dev.cel.checker.CelStandardDeclarations.StandardFunction;
import dev.cel.checker.CelStandardDeclarations.StandardFunction.Overload.Arithmetic;
import dev.cel.checker.CelStandardDeclarations.StandardIdentifier;
import dev.cel.common.CelOptions;
import dev.cel.common.CelValidationException;
import dev.cel.compiler.CelCompiler;
import dev.cel.compiler.CelCompilerFactory;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(TestParameterInjector.class)
public class CelStandardDeclarationsTest {

  @Test
  @TestParameters("{includeFunction: true, excludeFunction: true, filterFunction: true}")
  @TestParameters("{includeFunction: true, excludeFunction: true, filterFunction: false}")
  @TestParameters("{includeFunction: true, excludeFunction: false, filterFunction: true}")
  @TestParameters("{includeFunction: false, excludeFunction: true, filterFunction: true}")
  public void standardDeclaration_moreThanOneFunctionFilterSet_throws(
      boolean includeFunction, boolean excludeFunction, boolean filterFunction) {
    CelStandardDeclarations.Builder builder = CelStandardDeclarations.newBuilder();
    if (includeFunction) {
      builder.includeFunctions(StandardFunction.ADD);
    }
    if (excludeFunction) {
      builder.excludeFunctions(StandardFunction.SUBTRACT);
    }
    if (filterFunction) {
      builder.filterFunctions((func, over) -> true);
    }

    IllegalArgumentException e = assertThrows(IllegalArgumentException.class, builder::build);
    assertThat(e)
        .hasMessageThat()
        .contains(
            "You may only populate one of the following builder methods: includeFunctions,"
                + " excludeFunctions or filterFunctions");
  }

  @Test
  @TestParameters("{includeIdentifier: true, excludeIdentifier: true, filterIdentifier: true}")
  @TestParameters("{includeIdentifier: true, excludeIdentifier: true, filterIdentifier: false}")
  @TestParameters("{includeIdentifier: true, excludeIdentifier: false, filterIdentifier: true}")
  @TestParameters("{includeIdentifier: false, excludeIdentifier: true, filterIdentifier: true}")
  public void standardDeclaration_moreThanOneIdentifierFilterSet_throws(
      boolean includeIdentifier, boolean excludeIdentifier, boolean filterIdentifier) {
    CelStandardDeclarations.Builder builder = CelStandardDeclarations.newBuilder();
    if (includeIdentifier) {
      builder.includeIdentifiers(StandardIdentifier.MAP);
    }
    if (excludeIdentifier) {
      builder.excludeIdentifiers(StandardIdentifier.BOOL);
    }
    if (filterIdentifier) {
      builder.filterIdentifiers((ident) -> true);
    }

    IllegalArgumentException e = assertThrows(IllegalArgumentException.class, builder::build);
    assertThat(e)
        .hasMessageThat()
        .contains(
            "You may only populate one of the following builder methods: includeIdentifiers,"
                + " excludeIdentifiers or filterIdentifiers");
  }

  @Test
  public void compiler_setStandardDeclarations_overridesDefaultStandardEnvironment()
      throws Exception {
    CelCompiler compiler =
        CelCompilerFactory.standardCelCompilerBuilder()
            .setStandardDeclarations(
                CelStandardDeclarations.newBuilder()
                    .includeFunctions(StandardFunction.ADD)
                    .build())
            .build();

    assertThat(compiler.compile("1 + 1").hasError()).isFalse();
    assertThat(compiler.compile("1 - 1").hasError()).isTrue();
  }

  @Test
  public void compiler_setStandardDeclarations_withStandardEnvironmentExplicitlyEnabled()
      throws Exception {
    CelCompiler compiler =
        CelCompilerFactory.standardCelCompilerBuilder()
            .setStandardEnvironmentEnabled(true)
            .setStandardDeclarations(
                CelStandardDeclarations.newBuilder()
                    .includeFunctions(StandardFunction.ADD)
                    .build())
            .build();

    assertThat(compiler.compile("1 + 1").hasError()).isFalse();
    assertThat(compiler.compile("1 - 1").hasError()).isTrue();
  }

  @Test
  public void compiler_setStandardDeclarations_withStandardEnvironmentExplicitlyDisabled()
      throws Exception {
    CelCompiler compiler =
        CelCompilerFactory.standardCelCompilerBuilder()
            .setStandardEnvironmentEnabled(false)
            .setStandardDeclarations(
                CelStandardDeclarations.newBuilder()
                    .includeFunctions(StandardFunction.ADD)
                    .build())
            .build();

    assertThat(compiler.compile("1 + 1").hasError()).isFalse();
    assertThat(compiler.compile("1 - 1").hasError()).isTrue();
  }

  @Test
  public void compiler_setStandardDeclarations_emptyDisablesAllStandardDeclarations()
      throws Exception {
    CelCompiler compiler =
        CelCompilerFactory.standardCelCompilerBuilder()
            .setStandardDeclarations(CelStandardDeclarations.EMPTY)
            .build();

    assertThat(compiler.compile("1 + 1").hasError()).isTrue();
    assertThat(compiler.compile("1 - 1").hasError()).isTrue();
    assertThat(compiler.compile("size([1])").hasError()).isTrue();
  }

  @Test
  public void standardDeclarations_includeFunctions() {
    CelStandardDeclarations celStandardDeclaration =
        CelStandardDeclarations.newBuilder()
            .includeFunctions(StandardFunction.ADD, StandardFunction.SUBTRACT)
            .build();

    assertThat(celStandardDeclaration.functionDecls())
        .containsExactly(
            StandardFunction.ADD.functionDecl(), StandardFunction.SUBTRACT.functionDecl());
  }

  @Test
  public void standardDeclarations_excludeFunctions() {
    CelStandardDeclarations celStandardDeclaration =
        CelStandardDeclarations.newBuilder()
            .excludeFunctions(StandardFunction.ADD, StandardFunction.SUBTRACT)
            .build();

    assertThat(celStandardDeclaration.functionDecls())
        .doesNotContain(StandardFunction.ADD.functionDecl());
    assertThat(celStandardDeclaration.functionDecls())
        .doesNotContain(StandardFunction.SUBTRACT.functionDecl());
  }

  @Test
  public void standardDeclarations_filterFunctions() {
    CelStandardDeclarations celStandardDeclaration =
        CelStandardDeclarations.newBuilder()
            .filterFunctions(
                (func, over) -> {
                  if (func.equals(StandardFunction.ADD) && over.equals(Arithmetic.ADD_INT64)) {
                    return true;
                  }

                  if (func.equals(StandardFunction.SUBTRACT)
                      && over.equals(Arithmetic.SUBTRACT_INT64)) {
                    return true;
                  }

                  return false;
                })
            .build();

    assertThat(celStandardDeclaration.functionDecls())
        .containsExactly(
            newFunctionDeclaration(
                StandardFunction.ADD.functionName(), Arithmetic.ADD_INT64.celOverloadDecl()),
            newFunctionDeclaration(
                StandardFunction.SUBTRACT.functionName(),
                Arithmetic.SUBTRACT_INT64.celOverloadDecl()));
  }

  @Test
  public void standardDeclarations_includeIdentifiers() {
    CelStandardDeclarations celStandardDeclaration =
        CelStandardDeclarations.newBuilder()
            .includeIdentifiers(StandardIdentifier.INT, StandardIdentifier.UINT)
            .build();

    assertThat(celStandardDeclaration.identifierDecls())
        .containsExactly(StandardIdentifier.INT.identDecl(), StandardIdentifier.UINT.identDecl());
  }

  @Test
  public void standardDeclarations_excludeIdentifiers() {
    CelStandardDeclarations celStandardDeclaration =
        CelStandardDeclarations.newBuilder()
            .excludeIdentifiers(StandardIdentifier.INT, StandardIdentifier.UINT)
            .build();

    assertThat(celStandardDeclaration.identifierDecls())
        .doesNotContain(StandardIdentifier.INT.identDecl());
    assertThat(celStandardDeclaration.identifierDecls())
        .doesNotContain(StandardIdentifier.UINT.identDecl());
  }

  @Test
  public void standardDeclarations_filterIdentifiers() {
    CelStandardDeclarations celStandardDeclaration =
        CelStandardDeclarations.newBuilder()
            .filterIdentifiers(ident -> ident.equals(StandardIdentifier.MAP))
            .build();

    assertThat(celStandardDeclaration.identifierDecls())
        .containsExactly(StandardIdentifier.MAP.identDecl());
  }

  @Test
  public void standardEnvironment_subsetEnvironment() throws Exception {
    CelCompiler celCompiler =
        CelCompilerFactory.standardCelCompilerBuilder()
            .setStandardEnvironmentEnabled(false)
            .setStandardDeclarations(
                CelStandardDeclarations.newBuilder()
                    .includeFunctions(StandardFunction.ADD, StandardFunction.SUBTRACT)
                    .build())
            .build();

    assertThat(celCompiler.compile("1 + 2 - 3").getAst()).isNotNull();
    CelValidationException e =
        assertThrows(CelValidationException.class, () -> celCompiler.compile("1 * 2 / 3").getAst());
    assertThat(e).hasMessageThat().contains("undeclared reference to '_*_'");
    assertThat(e).hasMessageThat().contains("undeclared reference to '_/_'");
  }

  @Test
  @TestParameters("{expression: '1 > 2.0'}")
  @TestParameters("{expression: '2.0 > 1'}")
  @TestParameters("{expression: '1 > 2u'}")
  @TestParameters("{expression: '2u > 1'}")
  @TestParameters("{expression: '2u > 1.0'}")
  @TestParameters("{expression: '1.0 > 2u'}")
  @TestParameters("{expression: '1 >= 2.0'}")
  @TestParameters("{expression: '2.0 >= 1'}")
  @TestParameters("{expression: '1 >= 2u'}")
  @TestParameters("{expression: '2u >= 1'}")
  @TestParameters("{expression: '2u >= 1.0'}")
  @TestParameters("{expression: '1.0 >= 2u'}")
  @TestParameters("{expression: '1 < 2.0'}")
  @TestParameters("{expression: '2.0 < 1'}")
  @TestParameters("{expression: '1 < 2u'}")
  @TestParameters("{expression: '2u < 1'}")
  @TestParameters("{expression: '2u < 1.0'}")
  @TestParameters("{expression: '1.0 < 2u'}")
  @TestParameters("{expression: '1 <= 2.0'}")
  @TestParameters("{expression: '2.0 <= 1'}")
  @TestParameters("{expression: '1 <= 2u'}")
  @TestParameters("{expression: '2u <= 1'}")
  @TestParameters("{expression: '2u <= 1.0'}")
  @TestParameters("{expression: '1.0 <= 2u'}")
  public void heterogeneousEqualityDisabled_mixedTypeComparisons_throws(String expression) {
    CelCompiler celCompiler =
        CelCompilerFactory.standardCelCompilerBuilder()
            .setOptions(CelOptions.current().enableHeterogeneousNumericComparisons(false).build())
            .build();

    CelValidationException e =
        assertThrows(CelValidationException.class, () -> celCompiler.compile(expression).getAst());
    assertThat(e).hasMessageThat().contains("found no matching overload for");
  }

  @Test
  public void unsignedLongsDisabled_int64Identity_throws() {
    CelCompiler celCompiler =
        CelCompilerFactory.standardCelCompilerBuilder()
            .setOptions(CelOptions.current().enableUnsignedLongs(false).build())
            .build();

    CelValidationException e =
        assertThrows(CelValidationException.class, () -> celCompiler.compile("int(1)").getAst());
    assertThat(e).hasMessageThat().contains("found no matching overload for");
  }

  @Test
  public void timestampEpochDisabled_int64Identity_throws() {
    CelCompiler celCompiler =
        CelCompilerFactory.standardCelCompilerBuilder()
            .setOptions(CelOptions.current().enableTimestampEpoch(false).build())
            .build();

    CelValidationException e =
        assertThrows(
            CelValidationException.class, () -> celCompiler.compile("timestamp(10000)").getAst());
    assertThat(e).hasMessageThat().contains("found no matching overload for");
  }
}
