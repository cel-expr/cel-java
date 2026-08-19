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

package dev.cel.runtime;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableSet;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import com.google.testing.junit.testparameterinjector.TestParameters;
import dev.cel.common.CelOptions;
import dev.cel.compiler.CelCompiler;
import dev.cel.compiler.CelCompilerFactory;
import dev.cel.runtime.CelStandardFunctions.StandardFunction;
import dev.cel.runtime.standard.AddOperator.AddOverload;
import dev.cel.runtime.standard.CelStandardOverload;
import dev.cel.runtime.standard.SubtractOperator.SubtractOverload;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(TestParameterInjector.class)
public class CelStandardFunctionsTest {

  @Test
  @TestParameters("{includeFunction: true, excludeFunction: true, filterFunction: true}")
  @TestParameters("{includeFunction: true, excludeFunction: true, filterFunction: false}")
  @TestParameters("{includeFunction: true, excludeFunction: false, filterFunction: true}")
  @TestParameters("{includeFunction: false, excludeFunction: true, filterFunction: true}")
  public void standardFunction_moreThanOneFunctionFilterSet_throws(
      boolean includeFunction, boolean excludeFunction, boolean filterFunction) {
    CelStandardFunctions.Builder builder = CelStandardFunctions.newBuilder();
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
  public void runtime_standardEnvironmentEnabled_throwsWhenOverridingFunctions() {
    IllegalArgumentException e =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                CelRuntimeFactory.standardCelRuntimeBuilder()
                    .setStandardEnvironmentEnabled(true)
                    .setStandardFunctions(
                        CelStandardFunctions.newBuilder()
                            .includeFunctions(StandardFunction.ADD, StandardFunction.SUBTRACT)
                            .build())
                    .build());

    assertThat(e)
        .hasMessageThat()
        .contains(
            "setStandardEnvironmentEnabled must be set to false to override standard"
                + " function bindings");
  }

  @Test
  public void standardFunctions_includeFunctions() {
    CelStandardFunctions celStandardFunctions =
        CelStandardFunctions.newBuilder()
            .includeFunctions(
                CelStandardFunctions.StandardFunction.ADD,
                CelStandardFunctions.StandardFunction.SUBTRACT)
            .build();

    assertThat(celStandardFunctions.getOverloads())
        .containsExactlyElementsIn(
            ImmutableSet.<CelStandardOverload>builder()
                .addAll(CelStandardFunctions.StandardFunction.ADD.getOverloads())
                .addAll(CelStandardFunctions.StandardFunction.SUBTRACT.getOverloads())
                .build());
  }

  @Test
  public void standardFunctions_excludeFunctions() {
    CelStandardFunctions celStandardFunction =
        CelStandardFunctions.newBuilder()
            .excludeFunctions(
                CelStandardFunctions.StandardFunction.ADD,
                CelStandardFunctions.StandardFunction.SUBTRACT)
            .build();

    assertThat(celStandardFunction.getOverloads())
        .doesNotContain(CelStandardFunctions.StandardFunction.ADD.getOverloads());
    assertThat(celStandardFunction.getOverloads())
        .doesNotContain(CelStandardFunctions.StandardFunction.SUBTRACT.getOverloads());
  }

  @Test
  public void standardFunctions_filterFunctions() {
    CelStandardFunctions celStandardFunction =
        CelStandardFunctions.newBuilder()
            .filterFunctions(
                (func, over) -> {
                  if (func.equals(CelStandardFunctions.StandardFunction.ADD)
                      && over.equals(AddOverload.ADD_INT64)) {
                    return true;
                  }

                  if (func.equals(CelStandardFunctions.StandardFunction.SUBTRACT)
                      && over.equals(SubtractOverload.SUBTRACT_INT64)) {
                    return true;
                  }

                  return false;
                })
            .build();

    assertThat(celStandardFunction.getOverloads())
        .containsExactly(AddOverload.ADD_INT64, SubtractOverload.SUBTRACT_INT64);
  }

  @Test
  public void standardFunctions_empty() {
    assertThat(CelStandardFunctions.EMPTY.getOverloads()).isEmpty();
  }

  @Test
  public void standardEnvironment_subsetEnvironment() throws Exception {
    CelCompiler celCompiler = CelCompilerFactory.standardCelCompilerBuilder().build();
    CelRuntime celRuntime =
        CelRuntimeFactory.standardCelRuntimeBuilder()
            .setStandardEnvironmentEnabled(false)
            .setStandardFunctions(
                CelStandardFunctions.newBuilder()
                    .includeFunctions(StandardFunction.ADD, StandardFunction.SUBTRACT)
                    .build())
            .build();
    assertThat(celRuntime.createProgram(celCompiler.compile("1 + 2 - 3").getAst()).eval())
        .isEqualTo(0);
    CelEvaluationException e =
        assertThrows(
            CelEvaluationException.class,
            () -> celRuntime.createProgram(celCompiler.compile("1 * 2 / 3").getAst()).eval());
    assertThat(e)
        .hasMessageThat()
        .contains("No matching overload for function '_*_'. Overload candidates: multiply_int64");
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
            .setOptions(CelOptions.current().enableHeterogeneousNumericComparisons(true).build())
            .build();
    CelRuntime celRuntime =
        CelRuntimeFactory.standardCelRuntimeBuilder()
            .setOptions(CelOptions.current().enableHeterogeneousNumericComparisons(false).build())
            .build();

    CelEvaluationException e =
        assertThrows(
            CelEvaluationException.class,
            () -> celRuntime.createProgram(celCompiler.compile(expression).getAst()).eval());
    assertThat(e).hasMessageThat().contains("No matching overload for function");
  }

  @Test
  public void unsignedLongsDisabled_int64Identity_throws() {
    CelCompiler celCompiler =
        CelCompilerFactory.standardCelCompilerBuilder()
            .setOptions(CelOptions.current().enableUnsignedLongs(true).build())
            .build();
    CelRuntime celRuntime =
        CelRuntimeFactory.standardCelRuntimeBuilder()
            .setOptions(CelOptions.current().enableUnsignedLongs(false).build())
            .build();

    CelEvaluationException e =
        assertThrows(
            CelEvaluationException.class,
            () -> celRuntime.createProgram(celCompiler.compile("int(1)").getAst()).eval());
    assertThat(e)
        .hasMessageThat()
        .contains("No matching overload for function 'int'. Overload candidates: int64_to_int64");
  }

  @Test
  public void timestampEpochDisabled_int64Identity_throws() {
    CelCompiler celCompiler =
        CelCompilerFactory.standardCelCompilerBuilder()
            .setOptions(CelOptions.current().build())
            .build();
    CelRuntime celRuntime =
        CelRuntimeFactory.standardCelRuntimeBuilder()
            .setOptions(CelOptions.current().enableTimestampEpoch(false).build())
            .build();

    CelEvaluationException e =
        assertThrows(
            CelEvaluationException.class,
            () ->
                celRuntime.createProgram(celCompiler.compile("timestamp(10000)").getAst()).eval());
    assertThat(e)
        .hasMessageThat()
        .contains(
            "No matching overload for function 'timestamp'. Overload candidates:"
                + " int64_to_timestamp");
  }
}
