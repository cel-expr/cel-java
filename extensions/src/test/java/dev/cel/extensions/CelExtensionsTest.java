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

package dev.cel.extensions;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import dev.cel.bundle.Cel;
import dev.cel.bundle.CelFactory;
import dev.cel.common.CelOptions;
import dev.cel.common.CelValidationException;
import dev.cel.extensions.CelStringExtensions.Function;
import dev.cel.runtime.CelEvaluationException;
import dev.cel.runtime.CelRuntime;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class CelExtensionsTest {

  @Test
  public void addAllStringExtensions_success() throws Exception {
    Cel cel =
        CelFactory.standardCelBuilder()
            .addCompilerLibraries(CelExtensions.strings())
            .addRuntimeLibraries(CelExtensions.strings())
            .build();
    String allStringExtExpr =
        "'test'.substring(2) == 'st' && 'hello'.charAt(1) == 'e' && 'hello'.indexOf('o') == 4 &&"
            + " 'hello'.lastIndexOf('l') == 3 && 'HELLO'.lowerAscii() == 'hello' &&"
            + " 'hello'.replace('he', 'we') == 'wello' && 'hello'.upperAscii() == 'HELLO' && '"
            + " hello '.trim() == 'hello' && ['he','llo'].join() == 'hello' && 'hi'.split('') =="
            + " ['h','i']";

    boolean evaluatedResult =
        (boolean) cel.createProgram(cel.compile(allStringExtExpr).getAst()).eval();

    assertThat(evaluatedResult).isTrue();
  }

  @Test
  public void addSubsetOfStringExtensions_success() throws Exception {
    CelStringExtensions extensions = CelExtensions.strings(Function.SUBSTRING, Function.CHAR_AT);
    Cel cel =
        CelFactory.standardCelBuilder()
            .addCompilerLibraries(extensions)
            .addRuntimeLibraries(extensions)
            .build();

    boolean evaluatedResult =
        (boolean)
            cel.createProgram(
                    cel.compile("'test'.substring(2) == 'st' && 'hello'.charAt(1) == 'e'").getAst())
                .eval();

    assertThat(evaluatedResult).isTrue();
  }

  @Test
  public void addStringExtensionsForRuntimeOnly_throwsValidationException() {
    Cel cel = CelFactory.standardCelBuilder().addRuntimeLibraries(CelExtensions.strings()).build();

    CelValidationException exception =
        assertThrows(
            CelValidationException.class, () -> cel.compile("'abcd'.substring(1, 3)").getAst());

    assertThat(exception).hasMessageThat().contains("undeclared reference to 'substring'");
  }

  @Test
  public void addStringExtensionsForCompilerOnly_throwsEvaluationException() throws Exception {
    Cel cel = CelFactory.standardCelBuilder().addCompilerLibraries(CelExtensions.strings()).build();

    CelRuntime.Program program = cel.createProgram(cel.compile("'abcd'.substring(1, 3)").getAst());
    CelEvaluationException exception = assertThrows(CelEvaluationException.class, program::eval);

    assertThat(exception)
        .hasMessageThat()
        .contains(
            "No matching overload for function 'substring'. Overload candidates:"
                + " string_substring_int_int");
  }

  @Test
  public void addAllMathExtensions_success() throws Exception {
    Cel cel =
        CelFactory.standardCelBuilder()
            .addCompilerLibraries(CelExtensions.math())
            .addRuntimeLibraries(CelExtensions.math())
            .build();
    String allMathExtExpr = "math.greatest(1, 2.0) == 2.0 && math.least(1, 2.0) == 1";

    boolean evaluatedResult =
        (boolean) cel.createProgram(cel.compile(allMathExtExpr).getAst()).eval();

    assertThat(evaluatedResult).isTrue();
  }

  @Test
  public void addSubsetOfMathExtensions_success() throws Exception {
    Cel cel =
        CelFactory.standardCelBuilder()
            .addCompilerLibraries(CelExtensions.math(CelMathExtensions.Function.MAX))
            .addRuntimeLibraries(CelExtensions.math(CelMathExtensions.Function.MAX))
            .build();

    boolean evaluatedResult =
        (boolean) cel.createProgram(cel.compile("math.greatest(1, 2.0) == 2.0").getAst()).eval();

    assertThat(evaluatedResult).isTrue();
    assertThrows(CelValidationException.class, () -> cel.compile("math.least(1,2)").getAst());
  }

  @Test
  public void addEncoderExtension_success() throws Exception {
    CelOptions celOptions = CelOptions.current().build();
    Cel cel =
        CelFactory.standardCelBuilder()
            .addCompilerLibraries(CelExtensions.encoders(celOptions))
            .addRuntimeLibraries(CelExtensions.encoders(celOptions))
            .build();

    boolean evaluatedResult =
        (boolean)
            cel.createProgram(cel.compile("base64.decode('aGVsbG8=') == b'hello'").getAst()).eval();

    assertThat(evaluatedResult).isTrue();
  }

  @Test
  public void getAllFunctionNames() {
    assertThat(CelExtensions.getAllFunctionNames())
        .containsExactly(
            "math.@max",
            "math.@min",
            "math.ceil",
            "math.floor",
            "math.round",
            "math.trunc",
            "math.isFinite",
            "math.isNaN",
            "math.isInf",
            "math.abs",
            "math.sign",
            "math.bitAnd",
            "math.bitOr",
            "math.bitXor",
            "math.bitNot",
            "math.bitShiftLeft",
            "math.bitShiftRight",
            "math.sqrt",
            "charAt",
            "indexOf",
            "join",
            "lastIndexOf",
            "lowerAscii",
            "strings.quote",
            "replace",
            "split",
            "substring",
            "trim",
            "upperAscii",
            "sets.contains",
            "sets.equivalent",
            "sets.intersects",
            "base64.decode",
            "base64.encode",
            "slice",
            "flatten",
            "lists.range",
            "distinct",
            "reverse",
            "sort",
            "lists.@sortByAssociatedKeys",
            "regex.replace",
            "regex.extract",
            "regex.extractAll",
            "value",
            "hasValue",
            "optional.none",
            "optional.of",
            "optional.unwrap",
            "optional.ofNonZeroValue",
            "cel.@mapInsert");
  }
}
