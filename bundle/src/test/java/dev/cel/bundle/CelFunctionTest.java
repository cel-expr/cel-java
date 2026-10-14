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

package dev.cel.bundle;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.CelOverloadDecl;
import dev.cel.common.types.CelType;
import dev.cel.common.types.OpaqueType;
import dev.cel.common.types.SimpleType;
import dev.cel.testing.CelRuntimeFlavor;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(TestParameterInjector.class)
public final class CelFunctionTest {

  private static final CelType DECIMAL_TYPE = OpaqueType.create("decimal");

  @Test
  public void memberOverloads_unary_evaluatesAndDispatchesDynamically(
      @TestParameter CelRuntimeFlavor runtimeFlavor) throws Exception {
    CelFunction floor =
        CelFunction.newBuilder("floor")
            .addMemberOverload(
                SimpleType.INT, SimpleType.DOUBLE, Double.class, d -> (long) Math.floor(d))
            .addMemberOverload(
                SimpleType.INT,
                DECIMAL_TYPE,
                BigDecimal.class,
                bd -> bd.setScale(0, RoundingMode.FLOOR).longValue())
            .build();

    assertThat(floor.functionDecl().name()).isEqualTo("floor");
    assertThat(floor.functionDecl().overloads().stream().map(CelOverloadDecl::overloadId))
        .containsExactly("double.floor()", "decimal.floor()")
        .inOrder();

    Cel cel =
        runtimeFlavor
            .builder()
            .addVar("d", SimpleType.DOUBLE)
            .addVar("bd", DECIMAL_TYPE)
            .addVar("x", SimpleType.DYN)
            .addFunctions(floor)
            .build();

    CelAbstractSyntaxTree staticAst = cel.compile("d.floor() + bd.floor()").getAst();
    Object staticResult =
        cel.createProgram(staticAst).eval(ImmutableMap.of("d", 3.9, "bd", new BigDecimal("7.8")));
    assertThat(staticResult).isEqualTo(10L);

    // Dynamic dispatch via dyn variable
    CelAbstractSyntaxTree dynAst = cel.compile("x.floor()").getAst();
    assertThat(cel.createProgram(dynAst).eval(ImmutableMap.of("x", 5.7))).isEqualTo(5L);
    assertThat(cel.createProgram(dynAst).eval(ImmutableMap.of("x", new BigDecimal("9.9"))))
        .isEqualTo(9L);
  }

  @Test
  public void memberOverloads_binaryAndNary_evaluates(@TestParameter CelRuntimeFlavor runtimeFlavor)
      throws Exception {
    CelFunction repeat =
        CelFunction.newBuilder("repeat")
            .addMemberOverload(
                SimpleType.STRING,
                SimpleType.STRING,
                SimpleType.INT,
                String.class,
                Long.class,
                (s, count) -> {
                  StringBuilder sb = new StringBuilder();
                  for (long i = 0; i < count; i++) {
                    sb.append(s);
                  }
                  return sb.toString();
                })
            .build();

    CelFunction slice =
        CelFunction.newBuilder("slice")
            .addMemberOverload(
                SimpleType.STRING,
                ImmutableList.of(SimpleType.STRING, SimpleType.INT, SimpleType.INT),
                ImmutableList.of(String.class, Long.class, Long.class),
                args -> {
                  String s = (String) args[0];
                  int start = ((Long) args[1]).intValue();
                  int end = ((Long) args[2]).intValue();
                  return s.substring(start, end);
                })
            .build();

    assertThat(repeat.functionDecl().overloads().stream().map(CelOverloadDecl::overloadId))
        .containsExactly("string.repeat(int)");
    assertThat(slice.functionDecl().overloads().stream().map(CelOverloadDecl::overloadId))
        .containsExactly("string.slice(int,int)");

    Cel cel = runtimeFlavor.builder().addFunctions(ImmutableList.of(repeat, slice)).build();

    CelAbstractSyntaxTree ast = cel.compile("'abcdef'.slice(1, 4).repeat(2)").getAst();
    assertThat(cel.createProgram(ast).eval()).isEqualTo("bcdbcd");
  }

  @Test
  public void globalOverloads_unaryBinaryAndNary_evaluates(
      @TestParameter CelRuntimeFlavor runtimeFlavor) throws Exception {
    CelFunction doubleIt =
        CelFunction.newBuilder("doubleIt")
            .addGlobalOverload(SimpleType.INT, SimpleType.INT, Long.class, x -> x * 2)
            .addGlobalOverload(SimpleType.STRING, SimpleType.STRING, String.class, s -> s + s)
            .build();

    CelFunction joinWith =
        CelFunction.newBuilder("joinWith")
            .addGlobalOverload(
                SimpleType.STRING,
                SimpleType.STRING,
                SimpleType.STRING,
                String.class,
                String.class,
                (a, b) -> a + ":" + b)
            .build();

    CelFunction clamp =
        CelFunction.newBuilder("clamp")
            .addGlobalOverload(SimpleType.INT, ImmutableList.of(), ImmutableList.of(), args -> 0L)
            .addGlobalOverload(
                SimpleType.INT,
                ImmutableList.of(SimpleType.INT, SimpleType.INT, SimpleType.INT),
                ImmutableList.of(Long.class, Long.class, Long.class),
                args -> {
                  long val = (Long) args[0];
                  long min = (Long) args[1];
                  long max = (Long) args[2];
                  return Math.max(min, Math.min(max, val));
                })
            .build();

    assertThat(doubleIt.functionDecl().overloads().stream().map(CelOverloadDecl::overloadId))
        .containsExactly("doubleIt(int)", "doubleIt(string)")
        .inOrder();
    assertThat(joinWith.functionDecl().overloads().stream().map(CelOverloadDecl::overloadId))
        .containsExactly("joinWith(string,string)");
    assertThat(clamp.functionDecl().overloads().stream().map(CelOverloadDecl::overloadId))
        .containsExactly("clamp()", "clamp(int,int,int)")
        .inOrder();

    Cel cel =
        runtimeFlavor
            .builder()
            .addVar("dynVal", SimpleType.DYN)
            .addFunctions(doubleIt, joinWith, clamp)
            .build();

    CelAbstractSyntaxTree ast =
        cel.compile("joinWith(doubleIt('a'), string(doubleIt(clamp(25, clamp(), 10))))").getAst();
    assertThat(cel.createProgram(ast).eval()).isEqualTo("aa:20");

    // Dynamic dispatch on global overloaded function
    CelAbstractSyntaxTree dynAst = cel.compile("doubleIt(dynVal)").getAst();
    assertThat(cel.createProgram(dynAst).eval(ImmutableMap.of("dynVal", 21L))).isEqualTo(42L);
    assertThat(cel.createProgram(dynAst).eval(ImmutableMap.of("dynVal", "hi"))).isEqualTo("hihi");
  }

  @Test
  public void builder_invalidInputs_throws() {
    IllegalArgumentException emptyName =
        assertThrows(IllegalArgumentException.class, () -> CelFunction.newBuilder(""));
    assertThat(emptyName).hasMessageThat().isEqualTo("Function name cannot be null or empty");

    CelFunction.Builder emptyBuilder = CelFunction.newBuilder("empty");
    IllegalArgumentException noOverloads =
        assertThrows(IllegalArgumentException.class, emptyBuilder::build);
    assertThat(noOverloads)
        .hasMessageThat()
        .isEqualTo("Function 'empty' must have at least one overload");

    CelFunction.Builder duplicateOverloadBuilder =
        CelFunction.newBuilder("dup")
            .addGlobalOverload(SimpleType.INT, SimpleType.INT, Long.class, x -> x)
            .addGlobalOverload(SimpleType.INT, SimpleType.INT, Long.class, x -> x + 1);
    IllegalArgumentException duplicateOverload =
        assertThrows(IllegalArgumentException.class, duplicateOverloadBuilder::build);
    assertThat(duplicateOverload)
        .hasMessageThat()
        .isEqualTo("Duplicate overload signature: dup(int)");

    CelFunction.Builder duplicateOverloadDifferentReturnBuilder =
        CelFunction.newBuilder("dup")
            .addGlobalOverload(SimpleType.INT, SimpleType.INT, Long.class, x -> x)
            .addGlobalOverload(SimpleType.STRING, SimpleType.INT, Long.class, x -> "a");
    IllegalArgumentException duplicateOverloadDifferentReturn =
        assertThrows(
            IllegalArgumentException.class, duplicateOverloadDifferentReturnBuilder::build);
    assertThat(duplicateOverloadDifferentReturn)
        .hasMessageThat()
        .isEqualTo("Duplicate overload signature: dup(int)");

    CelFunction.Builder memberNoArgsBuilder = CelFunction.newBuilder("badMember");
    IllegalArgumentException memberNoArgs =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                memberNoArgsBuilder.addMemberOverload(
                    SimpleType.INT, ImmutableList.of(), ImmutableList.of(), args -> 0L));
    assertThat(memberNoArgs).hasMessageThat().isEqualTo("Member function with no receiver");

    CelFunction.Builder mismatchedArityBuilder = CelFunction.newBuilder("mismatch");
    IllegalArgumentException mismatchedArity =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                mismatchedArityBuilder.addGlobalOverload(
                    SimpleType.INT,
                    ImmutableList.of(SimpleType.INT, SimpleType.INT),
                    ImmutableList.of(Long.class),
                    args -> 0L));
    assertThat(mismatchedArity)
        .hasMessageThat()
        .isEqualTo("Number of CelTypes (2) must match number of Java classes (1)");
  }
}
