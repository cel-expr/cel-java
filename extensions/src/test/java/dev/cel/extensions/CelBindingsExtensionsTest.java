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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import com.google.testing.junit.testparameterinjector.TestParameters;
import dev.cel.bundle.Cel;
import dev.cel.common.CelFunctionDecl;
import dev.cel.common.CelOptions;
import dev.cel.common.CelOverloadDecl;
import dev.cel.common.CelValidationException;
import dev.cel.common.exceptions.CelDivideByZeroException;
import dev.cel.common.types.SimpleType;
import dev.cel.common.types.StructTypeReference;
import dev.cel.expr.conformance.proto3.TestAllTypes;
import dev.cel.parser.CelMacro;
import dev.cel.parser.CelStandardMacro;
import dev.cel.runtime.CelEvaluationException;
import dev.cel.runtime.CelFunctionBinding;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(TestParameterInjector.class)
public final class CelBindingsExtensionsTest extends CelExtensionTestBase {

  @Override
  protected Cel newCelEnv() {
    return runtimeFlavor
        .builder()
        .setStandardMacros(CelStandardMacro.STANDARD_MACROS)
        .addCompilerLibraries(CelOptionalLibrary.INSTANCE, CelExtensions.bindings())
        .addRuntimeLibraries(CelOptionalLibrary.INSTANCE)
        .build();
  }

  @Test
  public void library() {
    CelExtensionLibrary<?> library =
        CelExtensions.getExtensionLibrary("bindings", CelOptions.DEFAULT);
    assertThat(library.name()).isEqualTo("bindings");
    assertThat(library.latest().version()).isEqualTo(0);
    assertThat(library.version(0).functions().stream().map(CelFunctionDecl::name))
        .containsExactly("cel.@block");
    assertThat(library.version(0).macros().stream().map(CelMacro::getFunction))
        .containsExactly("bind");
  }

  private enum BindingTestCase {
    BOOL_LITERAL("cel.bind(t, true, t)"),
    STRING_CONCAT("cel.bind(msg, \"hello\", msg + msg + msg) == \"hellohellohello\""),
    NESTED_BINDS("cel.bind(t1, true, cel.bind(t2, true, t1 && t2))"),
    NESTED_BINDS_SPECIFIER_ONLY(
        "cel.bind(x, cel.bind(x, \"a\", x + x), x + \":\" + x) == \"aa:aa\""),
    NESTED_BINDS_SPECIFIER_AND_VALUE(
        "cel.bind(x, cel.bind(x, \"a\", x + x), cel.bind(y, x + x, y + \":\" + y)) =="
            + " \"aaaa:aaaa\""),
    BIND_WITH_EXISTS_TRUE(
        "cel.bind(valid_elems, [1, 2, 3], [3, 4, 5].exists(e, e in valid_elems))"),
    BIND_WITH_EXISTS_FALSE("cel.bind(valid_elems, [1, 2, 3], ![4, 5].exists(e, e in valid_elems))"),
    BIND_WITH_MAP("[1,2,3].map(x, cel.bind(y, x + x, [y, y])) == [[2, 2], [4, 4], [6, 6]]"),
    BIND_OPTIONAL_LIST("cel.bind(r0, optional.none(), [?r0, ?r0]) == []");

    private final String source;

    BindingTestCase(String source) {
      this.source = source;
    }
  }

  @Test
  public void binding_success(@TestParameter BindingTestCase testCase) throws Exception {
    boolean evaluatedResult = (boolean) eval(testCase.source);

    assertThat(evaluatedResult).isTrue();
  }

  @Test
  @TestParameters("{expr: 'false.bind(false, false, false)'}")
  public void binding_nonCelNamespace_success(String expr) throws Exception {
    Cel customCel =
        runtimeFlavor
            .builder()
            .addCompilerLibraries(CelExtensions.bindings())
            .addFunctionDeclarations(
                CelFunctionDecl.newFunctionDeclaration(
                    "bind",
                    CelOverloadDecl.newMemberOverload(
                        "bool_bind_bool_bool_bool",
                        SimpleType.BOOL,
                        SimpleType.BOOL,
                        SimpleType.BOOL,
                        SimpleType.BOOL,
                        SimpleType.BOOL)))
            .addFunctionBindings(
                CelFunctionBinding.fromOverloads(
                    "bind",
                    CelFunctionBinding.from(
                        "bool_bind_bool_bool_bool",
                        Arrays.asList(Boolean.class, Boolean.class, Boolean.class, Boolean.class),
                        (args) -> true)))
            .build();

    boolean result = (boolean) eval(customCel, expr);
    assertThat(result).isTrue();
  }

  @Test
  @TestParameters("{expr: 'cel.bind(bad.name, true, bad.name)'}")
  public void binding_throwsCompilationException(String expr) throws Exception {
    CelValidationException e =
        assertThrows(CelValidationException.class, () -> cel.compile(expr).getAst());

    assertThat(e).hasMessageThat().contains("cel.bind() variable name must be a simple identifier");
  }

  @Test
  @SuppressWarnings("Immutable") // Test only
  public void lazyBinding_bindingVarNeverReferenced() throws Exception {

    AtomicInteger invocation = new AtomicInteger();
    Cel customCel =
        runtimeFlavor
            .builder()
            .setStandardMacros(CelStandardMacro.HAS)
            .addMessageTypes(TestAllTypes.getDescriptor())
            .addVar("msg", StructTypeReference.create(TestAllTypes.getDescriptor().getFullName()))
            .addCompilerLibraries(CelExtensions.bindings())
            .addFunctionDeclarations(
                CelFunctionDecl.newFunctionDeclaration(
                    "get_true",
                    CelOverloadDecl.newGlobalOverload("get_true_overload", SimpleType.BOOL)))
            .addFunctionBindings(
                CelFunctionBinding.fromOverloads(
                    "get_true",
                    CelFunctionBinding.from(
                        "get_true_overload",
                        ImmutableList.of(),
                        arg -> {
                          invocation.getAndIncrement();
                          return true;
                        })))
            .build();
    boolean result =
        (boolean)
            eval(
                customCel,
                "cel.bind(t, get_true(), has(msg.single_int64) ? t : false)",
                ImmutableMap.of("msg", TestAllTypes.getDefaultInstance()));

    assertThat(result).isFalse();
    assertThat(invocation.get()).isEqualTo(0);
  }

  @Test
  public void lazyBinding_throwsEvaluationException() throws Exception {
    CelEvaluationException e =
        assertThrows(CelEvaluationException.class, () -> eval(cel, "cel.bind(t, 1 / 0, t)"));

    assertThat(e).hasMessageThat().contains("/ by zero");
    assertThat(e).hasCauseThat().isInstanceOf(CelDivideByZeroException.class);
  }

  @Test
  @SuppressWarnings("Immutable") // Test only
  public void lazyBinding_accuInitEvaluatedOnce() throws Exception {
    AtomicInteger invocation = new AtomicInteger();
    Cel customCel =
        runtimeFlavor
            .builder()
            .addCompilerLibraries(CelExtensions.bindings())
            .addFunctionDeclarations(
                CelFunctionDecl.newFunctionDeclaration(
                    "get_true",
                    CelOverloadDecl.newGlobalOverload("get_true_overload", SimpleType.BOOL)))
            .addFunctionBindings(
                CelFunctionBinding.fromOverloads(
                    "get_true",
                    CelFunctionBinding.from(
                        "get_true_overload",
                        ImmutableList.of(),
                        arg -> {
                          invocation.getAndIncrement();
                          return true;
                        })))
            .build();
    boolean result = (boolean) eval(customCel, "cel.bind(t, get_true(), t && t && t && t)");

    assertThat(result).isTrue();
    assertThat(invocation.get()).isEqualTo(1);
  }

  @Test
  @SuppressWarnings("Immutable") // Test only
  public void lazyBinding_withNestedBinds() throws Exception {
    AtomicInteger invocation = new AtomicInteger();
    Cel customCel =
        runtimeFlavor
            .builder()
            .addCompilerLibraries(CelExtensions.bindings())
            .addFunctionDeclarations(
                CelFunctionDecl.newFunctionDeclaration(
                    "get_true",
                    CelOverloadDecl.newGlobalOverload("get_true_overload", SimpleType.BOOL)))
            .addFunctionBindings(
                CelFunctionBinding.fromOverloads(
                    "get_true",
                    CelFunctionBinding.from(
                        "get_true_overload",
                        ImmutableList.of(),
                        arg -> {
                          invocation.getAndIncrement();
                          return true;
                        })))
            .build();
    boolean result =
        (boolean)
            eval(
                customCel,
                "cel.bind(t1, get_true(), cel.bind(t2, get_true(), t1 && t2 && t1 && t2))");

    assertThat(result).isTrue();
    assertThat(invocation.get()).isEqualTo(2);
  }

  @Test
  @SuppressWarnings({"Immutable", "unchecked"}) // Test only
  public void lazyBinding_boundAttributeInComprehension() throws Exception {
    AtomicInteger invocation = new AtomicInteger();
    Cel customCel =
        runtimeFlavor
            .builder()
            .setStandardMacros(CelStandardMacro.MAP)
            .addCompilerLibraries(CelExtensions.bindings())
            .addFunctionDeclarations(
                CelFunctionDecl.newFunctionDeclaration(
                    "get_true",
                    CelOverloadDecl.newGlobalOverload("get_true_overload", SimpleType.BOOL)))
            .addFunctionBindings(
                CelFunctionBinding.fromOverloads(
                    "get_true",
                    CelFunctionBinding.from(
                        "get_true_overload",
                        ImmutableList.of(),
                        arg -> {
                          invocation.getAndIncrement();
                          return true;
                        })))
            .build();

    List<Boolean> result =
        (List<Boolean>) eval(customCel, "cel.bind(x, get_true(), [1,2,3].map(y, y < 0 || x))");

    assertThat(result).containsExactly(true, true, true);
    assertThat(invocation.get()).isEqualTo(1);
  }

  @Test
  @SuppressWarnings({"Immutable"}) // Test only
  public void lazyBinding_boundAttributeInNestedComprehension() throws Exception {
    AtomicInteger invocation = new AtomicInteger();
    Cel customCel =
        runtimeFlavor
            .builder()
            .setStandardMacros(CelStandardMacro.EXISTS)
            .addCompilerLibraries(CelExtensions.bindings())
            .addFunctionDeclarations(
                CelFunctionDecl.newFunctionDeclaration(
                    "get_true",
                    CelOverloadDecl.newGlobalOverload("get_true_overload", SimpleType.BOOL)))
            .addFunctionBindings(
                CelFunctionBinding.fromOverloads(
                    "get_true",
                    CelFunctionBinding.from(
                        "get_true_overload",
                        ImmutableList.of(),
                        arg -> {
                          invocation.getAndIncrement();
                          return true;
                        })))
            .build();

    boolean result =
        (boolean)
            eval(
                customCel,
                "cel.bind(x, get_true(), [1,2,3].exists(unused, x && "
                    + "['a','b','c'].exists(unused_2, x)))");

    assertThat(result).isTrue();
    assertThat(invocation.get()).isEqualTo(1);
  }


}
