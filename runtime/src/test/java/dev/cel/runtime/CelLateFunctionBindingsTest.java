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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.primitives.UnsignedLong;
import dev.cel.common.CelErrorCode;
import dev.cel.expr.conformance.proto3.TestAllTypes;
import java.util.Optional;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link CelLateFunctionBindings}. */
@RunWith(JUnit4.class)
public final class CelLateFunctionBindingsTest {

  @Test
  public void findOverload_singleMatchingFunction_isPresent() throws Exception {
    CelLateFunctionBindings bindings =
        CelLateFunctionBindings.from(
            CelFunctionBinding.from("increment_int", Long.class, (arg) -> arg + 1),
            CelFunctionBinding.from(
                "increment_uint", UnsignedLong.class, (arg) -> arg.plus(UnsignedLong.ONE)));
    Optional<CelResolvedOverload> overload =
        bindings.findOverloadMatchingArgs(
            "increment", ImmutableList.of("increment_int", "increment_uint"), new Object[] {1L});
    assertThat(overload).isPresent();
    assertThat(overload.get().getOverloadId()).isEqualTo("increment_int");
    assertThat(overload.get().getParameterTypes()).containsExactly(Long.class);
    assertThat(overload.get().getDefinition().apply(new Object[] {1L})).isEqualTo(2L);
  }

  @Test
  public void findOverload_noMatchingFunctionSameArgCount_isEmpty() throws Exception {
    CelLateFunctionBindings bindings =
        CelLateFunctionBindings.from(
            CelFunctionBinding.from("increment_int", Long.class, (arg) -> arg + 1),
            CelFunctionBinding.from(
                "increment_uint", UnsignedLong.class, (arg) -> arg.plus(UnsignedLong.ONE)));
    Optional<CelResolvedOverload> overload =
        bindings.findOverloadMatchingArgs(
            "increment", ImmutableList.of("increment_int", "increment_uint"), new Object[] {1.0});
    assertThat(overload).isEmpty();
  }

  @Test
  public void findOverload_noMatchingFunctionDifferentArgCount_isEmpty() throws Exception {
    CelLateFunctionBindings bindings =
        CelLateFunctionBindings.from(
            CelFunctionBinding.from("increment_int", Long.class, (arg) -> arg + 1),
            CelFunctionBinding.from(
                "increment_uint", UnsignedLong.class, (arg) -> arg.plus(UnsignedLong.ONE)));
    Optional<CelResolvedOverload> overload =
        bindings.findOverloadMatchingArgs(
            "increment",
            ImmutableList.of("increment_int", "increment_uint"),
            new Object[] {1.0, 1.0});
    assertThat(overload).isEmpty();
  }

  @Test
  public void findOverload_badInput_throwsException() throws Exception {
    CelLateFunctionBindings bindings =
        CelLateFunctionBindings.from(
            CelFunctionBinding.from(
                "increment_uint",
                UnsignedLong.class,
                (arg) -> {
                  if (arg.equals(UnsignedLong.MAX_VALUE)) {
                    throw new CelEvaluationException(
                        "numeric overflow", null, CelErrorCode.NUMERIC_OVERFLOW);
                  }
                  return arg.plus(UnsignedLong.ONE);
                }));
    Optional<CelResolvedOverload> overload =
        bindings.findOverloadMatchingArgs(
            "increment", ImmutableList.of("increment_uint"), new Object[] {UnsignedLong.MAX_VALUE});
    assertThat(overload).isPresent();
    assertThat(overload.get().getOverloadId()).isEqualTo("increment_uint");
    assertThat(overload.get().getParameterTypes()).containsExactly(UnsignedLong.class);
    CelEvaluationException e =
        Assert.assertThrows(
            CelEvaluationException.class,
            () -> overload.get().getDefinition().apply(new Object[] {UnsignedLong.MAX_VALUE}));
    assertThat(e.getErrorCode()).isEqualTo(CelErrorCode.NUMERIC_OVERFLOW);
  }

  @Test
  public void findOverload_multipleMatchingFunctions_throwsException() throws Exception {
    CelLateFunctionBindings bindings =
        CelLateFunctionBindings.from(
            CelFunctionBinding.from("increment_int", Long.class, (arg) -> arg + 1),
            CelFunctionBinding.from("increment_uint", Long.class, (arg) -> arg + 2));
    CelEvaluationException e =
        Assert.assertThrows(
            CelEvaluationException.class,
            () ->
                bindings.findOverloadMatchingArgs(
                    "increment",
                    ImmutableList.of("increment_int", "increment_uint"),
                    new Object[] {1L}));
    assertThat(e).hasMessageThat().contains("Ambiguous overloads for function 'increment'");
  }

  @Test
  public void findOverload_nullPrimitiveArg_isEmpty() throws Exception {
    CelLateFunctionBindings bindings =
        CelLateFunctionBindings.from(
            CelFunctionBinding.from("identity_int", Long.class, (arg) -> arg));
    Optional<CelResolvedOverload> overload =
        bindings.findOverloadMatchingArgs(
            "identity", ImmutableList.of("identity_int"), new Object[] {null});
    assertThat(overload).isEmpty();
  }

  @Test
  public void findOverload_nullMessageArg_isEmpty() throws Exception {
    CelLateFunctionBindings bindings =
        CelLateFunctionBindings.from(
            CelFunctionBinding.from("identity_msg", TestAllTypes.class, (arg) -> arg));
    Optional<CelResolvedOverload> overload =
        bindings.findOverloadMatchingArgs(
            "identity", ImmutableList.of("identity_msg"), new Object[] {null});
    assertThat(overload).isEmpty();
  }

  @Test
  public void findOverload_parsedOnlyFromOverloads_scopesToMatchingFunctionName() throws Exception {
    CelLateFunctionBindings bindings =
        CelLateFunctionBindings.from(
            ImmutableSet.<CelFunctionBinding>builder()
                .addAll(
                    CelFunctionBinding.fromOverloads(
                        "fnA", CelFunctionBinding.from("fnA_str", String.class, s -> "A:" + s)))
                .addAll(
                    CelFunctionBinding.fromOverloads(
                        "fnB", CelFunctionBinding.from("fnB_str", String.class, s -> "B:" + s)))
                .addAll(
                    CelFunctionBinding.fromOverloads(
                        "fnC", CelFunctionBinding.from("fnC_int", Long.class, x -> x)))
                .build());

    Optional<CelResolvedOverload> fnBMatch =
        bindings.findOverloadMatchingArgs("fnB", new Object[] {"hi"});
    Optional<CelResolvedOverload> fnCMismatch =
        bindings.findOverloadMatchingArgs("fnC", new Object[] {"hi"});
    Optional<CelResolvedOverload> zeroArgMismatch =
        bindings.findOverloadMatchingArgs("fnC", new Object[0]);

    assertThat(fnBMatch.map(CelResolvedOverload::getOverloadId)).hasValue("fnB_str");
    assertThat(fnCMismatch).isEmpty();
    assertThat(zeroArgMismatch).isEmpty();
  }
}
