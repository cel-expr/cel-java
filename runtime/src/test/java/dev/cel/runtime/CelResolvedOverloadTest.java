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

import dev.cel.common.exceptions.CelOverloadNotFoundException;
import dev.cel.expr.conformance.proto3.TestAllTypes;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link CelResolvedOverload}. */
@RunWith(JUnit4.class)
public final class CelResolvedOverloadTest {

  CelResolvedOverload getIncrementIntOverload() {
    return CelResolvedOverload.of(
        /* functionName= */ "increment_int",
        /* overloadId= */ "increment_int_overload",
        (CelFunctionOverload)
            (args) -> {
              Long arg = (Long) args[0];
              return arg + 1;
            },
        /* isStrict= */ true,
        Long.class);
  }

  @Test
  public void canHandle_matchingTypes_returnsTrue() {
    assertThat(getIncrementIntOverload().canHandle(new Object[] {1L})).isTrue();
  }

  @Test
  public void canHandle_nullMessageType_returnsFalse() {
    CelResolvedOverload overload =
        CelResolvedOverload.of(
            /* functionName= */ "identity",
            /* overloadId= */ "identity_overload",
            (CelFunctionOverload) (args) -> args[0],
            /* isStrict= */ true,
            TestAllTypes.class);
    assertThat(overload.canHandle(new Object[] {null})).isFalse();
  }

  @Test
  public void canHandle_nullPrimitive_returnsFalse() {
    CelResolvedOverload overload =
        CelResolvedOverload.of(
            /* functionName= */ "identity",
            /* overloadId= */ "identity_overload",
            (CelFunctionOverload) (args) -> args[0],
            /* isStrict= */ true,
            Long.class);
    assertThat(overload.canHandle(new Object[] {null})).isFalse();
  }

  @Test
  public void canHandle_nonMatchingTypes_returnsFalse() {
    assertThat(getIncrementIntOverload().canHandle(new Object[] {1.0})).isFalse();
  }

  @Test
  public void canHandle_nonMatchingArgCount_returnsFalse() {
    assertThat(getIncrementIntOverload().canHandle(new Object[] {1L, 2L})).isFalse();
  }

  @Test
  public void canHandle_nonStrictOverload_returnsTrue() {
    CelResolvedOverload nonStrictOverload =
        CelResolvedOverload.of(
            /* functionName= */ "non_strict",
            /* overloadId= */ "non_strict_overload",
            (CelFunctionOverload)
                (args) -> {
                  return false;
                },
            /* isStrict= */ false,
            Long.class,
            Long.class);
    assertThat(
            nonStrictOverload.canHandle(
                new Object[] {new RuntimeException(), CelUnknownSet.create()}))
        .isTrue();
  }

  @Test
  public void canHandle_nonStrictOverload_returnsFalse() {
    CelResolvedOverload nonStrictOverload =
        CelResolvedOverload.of(
            /* functionName= */ "non_strict",
            /* overloadId= */ "non_strict_overload",
            (CelFunctionOverload)
                (args) -> {
                  return false;
                },
            /* isStrict= */ false,
            Long.class,
            Long.class);
    assertThat(nonStrictOverload.canHandle(new Object[] {new RuntimeException(), "Foo"})).isFalse();
  }

  @Test
  public void invoke_fallbackOverloadMatches_dispatchesAcrossArities() throws Exception {
    DefaultDispatcher dispatcher = newFallbackTestDispatcher();
    CelResolvedOverload unary = dispatcher.findOverload("fn_int").get();
    CelResolvedOverload binary = dispatcher.findOverload("fn_bin_int").get();

    Object primaryUnary = unary.invoke(1L);
    Object fallbackUnary = unary.invoke("hi");
    Object fallbackVarargs = unary.invoke(new Object[] {"hi"});
    Object fallbackBinary = binary.invoke("a", "b");

    assertThat(primaryUnary).isEqualTo(2L);
    assertThat(fallbackUnary).isEqualTo("hi!");
    assertThat(fallbackVarargs).isEqualTo("hi!");
    assertThat(fallbackBinary).isEqualTo("ab");
  }

  @Test
  public void invoke_noFallbackMatch_preservesPrimaryCandidateInException() {
    DefaultDispatcher dispatcher = newFallbackTestDispatcher();
    CelResolvedOverload unary = dispatcher.findOverload("fn_int").get();
    CelResolvedOverload binary = dispatcher.findOverload("fn_bin_int").get();
    CelResolvedOverload noFallback = getIncrementIntOverload();
    Object[] badArgs = new Object[] {"bad"};

    CelOverloadNotFoundException unaryErr =
        assertThrows(CelOverloadNotFoundException.class, () -> unary.invoke(true));
    CelOverloadNotFoundException varargsErr =
        assertThrows(CelOverloadNotFoundException.class, () -> unary.invoke(new Object[] {true}));
    CelOverloadNotFoundException binaryErr =
        assertThrows(CelOverloadNotFoundException.class, () -> binary.invoke(true, false));
    CelOverloadNotFoundException noFallbackVarargsErr =
        assertThrows(CelOverloadNotFoundException.class, () -> noFallback.invoke(badArgs));

    assertThat(unaryErr).hasMessageThat().contains("Overload candidates: fn_int");
    assertThat(varargsErr).hasMessageThat().contains("Overload candidates: fn_int");
    assertThat(binaryErr).hasMessageThat().contains("Overload candidates: fn_bin_int");
    assertThat(noFallbackVarargsErr)
        .hasMessageThat()
        .contains("Overload candidates: increment_int_overload");
  }

  private static DefaultDispatcher newFallbackTestDispatcher() {
    DefaultDispatcher.Builder builder = DefaultDispatcher.newBuilder();
    for (CelFunctionBinding binding :
        CelFunctionBinding.fromOverloads(
            "fn",
            CelFunctionBinding.from("fn_int", Long.class, x -> x + 1L),
            CelFunctionBinding.from("fn_str", String.class, s -> s + "!"),
            CelFunctionBinding.from("fn_bin_int", Long.class, Long.class, Long::sum),
            CelFunctionBinding.from("fn_bin_str", String.class, String.class, String::concat))) {
      builder.addOverload(
          ((InternalCelFunctionBinding) binding).getFunctionName(),
          binding.getOverloadId(),
          binding.getArgTypes(),
          binding.isStrict(),
          binding.getDefinition());
    }
    return builder.build();
  }
}
