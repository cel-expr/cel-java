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
import static dev.cel.common.CelFunctionDecl.newFunctionDeclaration;
import static dev.cel.common.CelOverloadDecl.newGlobalOverload;
import static org.junit.Assert.assertThrows;

import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import dev.cel.bundle.Cel;
import dev.cel.bundle.CelFactory;
import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.CelOptions;
import dev.cel.common.CelValidationException;
import dev.cel.common.types.SimpleType;
import dev.cel.compiler.CelCompiler;
import dev.cel.compiler.CelCompilerFactory;
import dev.cel.extensions.CelStringExtensions.Function;
import dev.cel.runtime.CelEvaluationException;
import dev.cel.runtime.CelFunctionBinding;
import dev.cel.runtime.CelLiteRuntime;
import dev.cel.runtime.CelLiteRuntimeFactory;
import dev.cel.runtime.CelRuntime;
import dev.cel.runtime.CelStandardFunctions;
import java.util.Optional;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(TestParameterInjector.class)
public class CelExtensionsTest {

  @Test
  public void addAllStringExtensions_success() throws Exception {
    Cel cel =
        CelFactory.plannerCelBuilder()
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
        CelFactory.plannerCelBuilder()
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
    Cel cel = CelFactory.plannerCelBuilder().addRuntimeLibraries(CelExtensions.strings()).build();

    CelValidationException exception =
        assertThrows(
            CelValidationException.class, () -> cel.compile("'abcd'.substring(1, 3)").getAst());

    assertThat(exception).hasMessageThat().contains("undeclared reference to 'substring'");
  }

  @Test
  public void addStringExtensionsForCompilerOnly_throwsEvaluationException() throws Exception {
    Cel cel = CelFactory.plannerCelBuilder().addCompilerLibraries(CelExtensions.strings()).build();
    CelAbstractSyntaxTree ast = cel.compile("'abcd'.substring(1, 3)").getAst();

    CelEvaluationException exception =
        assertThrows(CelEvaluationException.class, () -> cel.createProgram(ast));

    assertThat(exception)
        .hasMessageThat()
        .contains(
            "No matching overload for function 'substring'. Overload candidates:"
                + " string_substring_int_int");
  }

  @Test
  public void addAllMathExtensions_success() throws Exception {
    Cel cel =
        CelFactory.plannerCelBuilder()
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
        CelFactory.plannerCelBuilder()
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
    Cel cel =
        CelFactory.plannerCelBuilder()
            .addCompilerLibraries(CelExtensions.encoders(CelOptions.DEFAULT))
            .addRuntimeLibraries(CelExtensions.encoders(CelOptions.DEFAULT))
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
            "format",
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
            "@sortByAssociatedKeys",
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

  @Test
  public void crossExtensionSharedFunctionName_reverse_dispatchesAcrossListsAndStrings(
      @TestParameter({
            "dyn('hello').reverse() == 'olleh' && dyn([1, 2]).reverse() == [2, 1]",
            "(([['a']] + dyn(['hello']))[1]).reverse() == dyn('olleh')",
            "(('a' == '' ? 'a' : (['a'] + dyn([[1, 2]]))[1])).reverse() == dyn([2, 1])",
          })
          String expression,
      @TestParameter boolean isParseOnly)
      throws Exception {
    Cel cel =
        CelFactory.plannerCelBuilder()
            .addCompilerLibraries(CelExtensions.lists(), CelExtensions.strings())
            .addRuntimeLibraries(CelExtensions.lists(), CelExtensions.strings())
            .build();
    CelAbstractSyntaxTree ast =
        isParseOnly ? cel.parse(expression).getAst() : cel.compile(expression).getAst();

    Object result = cel.createProgram(ast).eval();

    assertThat(result).isEqualTo(true);
  }

  @Test
  public void crossExtensionSharedFunctionName_liteRuntime_reverseDispatchesAcrossListsAndStrings(
      @TestParameter boolean isParseOnly) throws Exception {
    CelCompiler compiler =
        CelCompilerFactory.standardCelCompilerBuilder()
            .addLibraries(CelExtensions.lists(), CelExtensions.strings())
            .build();
    CelLiteRuntime runtime =
        CelLiteRuntimeFactory.newLiteRuntimeBuilder()
            .setStandardFunctions(CelStandardFunctions.ALL_STANDARD_FUNCTIONS)
            .addLibraries(CelListsRuntimeLibrary.lists(), CelStringRuntimeLibrary.strings())
            .build();
    String expression = "(([['a']] + dyn(['hello']))[1]).reverse() == dyn('olleh')";
    CelAbstractSyntaxTree ast =
        isParseOnly ? compiler.parse(expression).getAst() : compiler.compile(expression).getAst();

    Object result = runtime.createProgram(ast).eval();

    assertThat(result).isEqualTo(true);
  }

  @Test
  public void toCelBuilder_mergesDerivedLibrariesAndFunctionBindings() throws Exception {
    Cel baseCel =
        CelFactory.plannerCelBuilder()
            .addCompilerLibraries(CelExtensions.lists())
            .addRuntimeLibraries(CelExtensions.lists())
            .addFunctionDeclarations(
                newFunctionDeclaration(
                    "fn",
                    newGlobalOverload("fn_int", SimpleType.INT, SimpleType.INT),
                    newGlobalOverload("fn_str", SimpleType.STRING, SimpleType.STRING)))
            .addFunctionBindings(
                CelFunctionBinding.fromOverloads(
                    "fn", CelFunctionBinding.from("fn_int", Long.class, x -> x + 1L)))
            .build();
    Cel derivedCel =
        baseCel
            .toCelBuilder()
            .addCompilerLibraries(CelExtensions.strings())
            .addRuntimeLibraries(CelExtensions.strings())
            .addFunctionBindings(
                CelFunctionBinding.fromOverloads(
                    "fn",
                    CelFunctionBinding.from("fn_int", Long.class, x -> x + 100L),
                    CelFunctionBinding.from("fn_str", String.class, s -> s + "!")))
            .build();
    String expression =
        "dyn('hello').reverse() == 'olleh' && dyn([1, 2]).reverse() == [2, 1]"
            + " && fn(dyn(1)) == 2 && fn(dyn('a')) == 'a!'";

    assertThat(derivedCel.createProgram(derivedCel.compile(expression).getAst()).eval())
        .isEqualTo(true);
    assertThat(derivedCel.createProgram(derivedCel.parse(expression).getAst()).eval())
        .isEqualTo(true);
  }

  @Test
  public void toRuntimeBuilder_optionalLibrary_updatesRuntimeEquality() throws Exception {
    Cel defaultCel =
        CelFactory.plannerCelBuilder()
            .addCompilerLibraries(CelExtensions.optional())
            .addRuntimeLibraries(CelExtensions.optional())
            .build();
    CelAbstractSyntaxTree ast = defaultCel.compile("{1: 'a'}[?dyn(1.0)]").getAst();
    CelRuntime strictMapKeyRuntime =
        defaultCel
            .toRuntimeBuilder()
            .setOptions(CelOptions.current().disableCelStandardEquality(true).build())
            .build();

    assertThat(defaultCel.createProgram(ast).eval()).isEqualTo(Optional.of("a"));
    assertThat(strictMapKeyRuntime.createProgram(ast).eval()).isEqualTo(Optional.empty());
  }
}
