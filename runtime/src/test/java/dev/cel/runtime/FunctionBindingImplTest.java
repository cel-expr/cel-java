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

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.util.concurrent.Futures.immediateFuture;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import dev.cel.common.exceptions.CelOverloadNotFoundException;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(TestParameterInjector.class)
public final class FunctionBindingImplTest {

  @Test
  public void dynamicDispatch_unaryOptimizedOverload_invokesOptimizedUnaryApply() throws Exception {
    OptimizedFunctionOverload mockOverload =
        new OptimizedFunctionOverload() {
          @Override
          public Object apply(Object arg) {
            return (Long) arg * 10L;
          }

          @Override
          public Object apply(Object[] args) {
            throw new AssertionError("Should not invoke array apply for unary optimized overload!");
          }
        };
    CelFunctionBinding b1 =
        CelFunctionBinding.from(
            "custom_opt_unary_long", ImmutableList.of(Long.class), mockOverload);
    CelFunctionBinding b2 =
        CelFunctionBinding.from("custom_opt_unary_str", String.class, (String arg) -> arg + "!");
    ImmutableSet<CelFunctionBinding> bindings =
        FunctionBindingImpl.groupOverloadsToFunction("custom_opt_unary", ImmutableSet.of(b1, b2));
    OptimizedFunctionOverload overload =
        (OptimizedFunctionOverload)
            Iterables.find(bindings, b -> b.getOverloadId().equals("custom_opt_unary"))
                .getDefinition();

    assertThat(overload.apply(5L)).isEqualTo(50L);
    assertThat(overload.apply("test")).isEqualTo("test!");
  }

  @Test
  public void dynamicDispatch_binaryOptimizedOverload_invokesOptimizedBinaryApply()
      throws Exception {
    OptimizedFunctionOverload mockOverload =
        new OptimizedFunctionOverload() {
          @Override
          public Object apply(Object arg1, Object arg2) {
            return (Long) arg1 + (Long) arg2 + 100L;
          }

          @Override
          public Object apply(Object[] args) {
            throw new AssertionError(
                "Should not invoke array apply for binary optimized overload!");
          }
        };
    CelFunctionBinding b1 =
        CelFunctionBinding.from(
            "custom_opt_binary_long", ImmutableList.of(Long.class, Long.class), mockOverload);
    CelFunctionBinding b2 =
        CelFunctionBinding.from(
            "custom_opt_bin_str", String.class, String.class, (String a, String b) -> a + b);
    ImmutableSet<CelFunctionBinding> bindings =
        FunctionBindingImpl.groupOverloadsToFunction("custom_opt_binary", ImmutableSet.of(b1, b2));
    OptimizedFunctionOverload overload =
        (OptimizedFunctionOverload)
            Iterables.find(bindings, b -> b.getOverloadId().equals("custom_opt_binary"))
                .getDefinition();

    assertThat(overload.apply(10L, 20L)).isEqualTo(130L);
    assertThat(overload.apply("foo", "bar")).isEqualTo("foobar");
  }

  @Test
  public void fromAsync_unary_invokesAsyncAndAdapts() throws Exception {
    CelFunctionBinding binding =
        CelFunctionBinding.fromAsync(
            "unary_async", Long.class, (Long arg) -> immediateFuture(arg * 3L));
    CelAsyncFunctionOverload overload = (CelAsyncFunctionOverload) binding.getDefinition();

    assertThat(binding.getOverloadId()).isEqualTo("unary_async");
    assertThat(binding.getArgTypes()).containsExactly(Long.class);
    assertThat(binding.isStrict()).isTrue();
    assertThat(binding.getDefinition()).isInstanceOf(CelAsyncFunctionOverload.class);
    assertThat(overload.applyAsync(10L).get(5, SECONDS)).isEqualTo(30L);
    assertThat(overload.applyAsync(new Object[] {10L}).get(5, SECONDS)).isEqualTo(30L);
  }

  @Test
  public void fromAsync_binary_invokesAsyncAndAdapts() throws Exception {
    CelFunctionBinding binding =
        CelFunctionBinding.fromAsync(
            "binary_async", Long.class, Long.class, (Long a, Long b) -> immediateFuture(a + b));
    CelAsyncFunctionOverload overload = (CelAsyncFunctionOverload) binding.getDefinition();

    assertThat(binding.getOverloadId()).isEqualTo("binary_async");
    assertThat(binding.getArgTypes()).containsExactly(Long.class, Long.class).inOrder();
    assertThat(binding.isStrict()).isTrue();
    assertThat(binding.getDefinition()).isInstanceOf(CelAsyncFunctionOverload.class);
    assertThat(overload.applyAsync(15L, 25L).get(5, SECONDS)).isEqualTo(40L);
    assertThat(overload.applyAsync(new Object[] {15L, 25L}).get(5, SECONDS)).isEqualTo(40L);
  }

  @Test
  public void fromAsync_nullArguments_throwsNullPointerException() {
    assertThrows(
        NullPointerException.class,
        () -> CelFunctionBinding.fromAsync(null, Long.class, (Long x) -> immediateFuture(x)));
    assertThrows(
        NullPointerException.class,
        () -> CelFunctionBinding.fromAsync("id", null, (Long x) -> immediateFuture(x)));
    assertThrows(
        NullPointerException.class,
        () ->
            CelFunctionBinding.fromAsync(
                "id", Long.class, (CelAsyncFunctionOverload.Unary<Long>) null));

    assertThrows(
        NullPointerException.class,
        () ->
            CelFunctionBinding.fromAsync(
                null, Long.class, String.class, (a, b) -> immediateFuture(a)));
    assertThrows(
        NullPointerException.class,
        () -> CelFunctionBinding.fromAsync("id", null, String.class, (a, b) -> immediateFuture(a)));
    assertThrows(
        NullPointerException.class,
        () -> CelFunctionBinding.fromAsync("id", Long.class, null, (a, b) -> immediateFuture(a)));
    assertThrows(
        NullPointerException.class,
        () ->
            CelFunctionBinding.fromAsync(
                "id",
                Long.class,
                String.class,
                (CelAsyncFunctionOverload.Binary<Long, String>) null));

    assertThrows(
        NullPointerException.class,
        () ->
            CelFunctionBinding.fromAsync(
                null, ImmutableList.of(Long.class), args -> immediateFuture(1L)));
    assertThrows(
        NullPointerException.class,
        () ->
            CelFunctionBinding.fromAsync(
                "id", (Iterable<Class<?>>) null, args -> immediateFuture(1L)));
    assertThrows(
        NullPointerException.class,
        () ->
            CelFunctionBinding.fromAsync(
                "id", ImmutableList.of(Long.class), (CelAsyncFunctionOverload) null));
  }

  @Test
  public void fromAsync_unary_lambdaReturnsNull_returnsNullFuture() throws Exception {
    CelFunctionBinding unaryBinding =
        CelFunctionBinding.fromAsync(
            "null_async", Long.class, (CelAsyncFunctionOverload.Unary<Long>) (Long arg) -> null);
    CelAsyncFunctionOverload unaryOverload =
        (CelAsyncFunctionOverload) unaryBinding.getDefinition();

    assertThat(unaryOverload.applyAsync(1L)).isNull();
    assertThat(unaryOverload.applyAsync(new Object[] {1L})).isNull();
  }

  @Test
  public void fromAsync_binary_lambdaReturnsNull_returnsNullFuture() throws Exception {
    CelFunctionBinding binaryBinding =
        CelFunctionBinding.fromAsync(
            "null_async_bin",
            Long.class,
            String.class,
            (CelAsyncFunctionOverload.Binary<Long, String>) (Long a, String b) -> null);
    CelAsyncFunctionOverload binaryOverload =
        (CelAsyncFunctionOverload) binaryBinding.getDefinition();

    assertThat(binaryOverload.applyAsync(1L, "a")).isNull();
    assertThat(binaryOverload.applyAsync(new Object[] {1L, "a"})).isNull();
  }

  @Test
  public void fromAsync_unary_synchronousApplyThrowsUnsupportedOperationException() {
    CelFunctionBinding binding =
        CelFunctionBinding.fromAsync("unary_async", Long.class, (Long arg) -> immediateFuture(arg));
    CelFunctionOverload overload = binding.getDefinition();

    UnsupportedOperationException e =
        assertThrows(UnsupportedOperationException.class, () -> overload.apply(new Object[] {10L}));

    assertThat(e)
        .hasMessageThat()
        .contains("Async overload cannot be evaluated synchronously. Use evalAsync instead.");
  }

  @Test
  public void fromAsync_varargs_invokesAsyncAndAdapts() throws Exception {
    CelFunctionBinding binding =
        CelFunctionBinding.fromAsync(
            "custom_async_varargs",
            ImmutableList.of(Long.class, String.class),
            (Object[] args) -> immediateFuture((Long) args[0] + (String) args[1]));
    CelAsyncFunctionOverload overload = (CelAsyncFunctionOverload) binding.getDefinition();

    assertThat(binding.getOverloadId()).isEqualTo("custom_async_varargs");
    assertThat(binding.getArgTypes()).containsExactly(Long.class, String.class).inOrder();
    assertThat(binding.isStrict()).isTrue();
    assertThat(binding.getDefinition()).isInstanceOf(CelAsyncFunctionOverload.class);
    assertThat(overload.applyAsync(new Object[] {10L, "test"}).get(5, SECONDS)).isEqualTo("10test");
  }

  @Test
  public void fromOverloads_asyncBinding_throwsIllegalArgumentException() {
    CelFunctionBinding asyncBinding =
        CelFunctionBinding.fromAsync("async_fn", Long.class, (Long arg) -> immediateFuture(arg));

    IllegalArgumentException e =
        assertThrows(
            IllegalArgumentException.class,
            () -> CelFunctionBinding.fromOverloads("async_fn", asyncBinding));

    assertThat(e)
        .hasMessageThat()
        .contains("Asynchronous function overloads cannot be grouped using fromOverloads.");
  }

  @Test
  public void fromAsync_withIterableArgTypes_success() throws Exception {
    CelAsyncFunctionOverload overload =
        args -> immediateFuture((Long) args[0] + (Long) args[1] + (Long) args[2]);

    CelFunctionBinding binding =
        CelFunctionBinding.fromAsync(
            "ternary_async", ImmutableList.of(Long.class, Long.class, Long.class), overload);

    assertThat(binding.getOverloadId()).isEqualTo("ternary_async");
    assertThat(binding.getArgTypes()).containsExactly(Long.class, Long.class, Long.class).inOrder();
    assertThat(binding.isStrict()).isTrue();
    assertThat(binding.getDefinition()).isSameInstanceAs(overload);
    CelAsyncFunctionOverload bindingDef = (CelAsyncFunctionOverload) binding.getDefinition();
    assertThat(bindingDef.applyAsync(new Object[] {10L, 20L, 30L}).get(5, SECONDS)).isEqualTo(60L);
  }

  @Test
  public void asyncFunctionOverload_defaultMethods_delegatesToVarargsAndThrowsOnSync()
      throws Exception {
    CelAsyncFunctionOverload overload =
        args -> {
          long sum = 0L;
          for (Object arg : args) {
            sum += (Long) arg;
          }
          return immediateFuture(sum);
        };

    assertThat(overload.applyAsync(42L).get(5, SECONDS)).isEqualTo(42L);
    assertThat(overload.applyAsync(10L, 20L).get(5, SECONDS)).isEqualTo(30L);

    UnsupportedOperationException thrown =
        assertThrows(UnsupportedOperationException.class, () -> overload.apply(new Object[] {1L}));
    assertThat(thrown)
        .hasMessageThat()
        .contains("Async overload cannot be evaluated synchronously. Use evalAsync instead.");
  }

  @Test
  public void celFunctionResolver_empty_alwaysReturnsEmpty() throws Exception {
    CelFunctionResolver resolver = CelFunctionResolver.EMPTY;

    assertThat(resolver.findOverloadMatchingArgs("fn", new Object[] {1L})).isEmpty();
    assertThat(
            resolver.findOverloadMatchingArgs(
                "fn", ImmutableList.of("fn_overload"), new Object[] {1L}))
        .isEmpty();
  }

  @Test
  public void dynamicDispatch_applyVarargs_matchesCorrectOverload() throws Exception {
    CelFunctionBinding binding1 =
        CelFunctionBinding.from(
            "sum_three_longs",
            ImmutableList.of(Long.class, Long.class, Long.class),
            args -> (Long) args[0] + (Long) args[1] + (Long) args[2]);
    CelFunctionBinding binding2 =
        CelFunctionBinding.from(
            "concat_three_strings",
            ImmutableList.of(String.class, String.class, String.class),
            args -> (String) args[0] + (String) args[1] + (String) args[2]);

    ImmutableSet<CelFunctionBinding> overloads =
        CelFunctionBinding.fromOverloads("add3", binding1, binding2);
    OptimizedFunctionOverload dispatchOverload =
        (OptimizedFunctionOverload)
            Iterables.find(overloads, b -> b.getOverloadId().equals("add3")).getDefinition();

    assertThat(dispatchOverload.apply(new Object[] {1L, 2L, 3L})).isEqualTo(6L);
    assertThat(dispatchOverload.apply(new Object[] {"a", "b", "c"})).isEqualTo("abc");

    CelOverloadNotFoundException thrown =
        assertThrows(
            CelOverloadNotFoundException.class,
            () -> dispatchOverload.apply(new Object[] {1L, "b", 3L}));
    assertThat(thrown)
        .hasMessageThat()
        .contains(
            "No matching overload for function 'add3'. Overload candidates: sum_three_longs,"
                + " concat_three_strings");
  }

  @Test
  public void fromOverloads_nullOrEmptyFunctionName_throwsIllegalArgumentException() {
    CelFunctionBinding binding = CelFunctionBinding.from("fn_1", Long.class, (Long arg) -> 1L);

    assertThrows(
        IllegalArgumentException.class, () -> CelFunctionBinding.fromOverloads(null, binding));
    assertThrows(
        IllegalArgumentException.class, () -> CelFunctionBinding.fromOverloads("", binding));
  }

  @Test
  public void fromOverloads_emptyOverloadBindings_throwsIllegalArgumentException() {
    assertThrows(
        IllegalArgumentException.class,
        () -> CelFunctionBinding.fromOverloads("fn", ImmutableList.of()));
    assertThrows(IllegalArgumentException.class, () -> CelFunctionBinding.fromOverloads("fn"));
  }

  @Test
  public void fromOverloads_varargsAndCollection_success() {
    CelFunctionBinding binding1 =
        CelFunctionBinding.from("unary_fn", Long.class, (Long arg) -> arg + 1L);
    CelFunctionBinding binding2 =
        CelFunctionBinding.from("binary_fn", Long.class, Long.class, (Long a, Long b) -> a + b);

    ImmutableSet<CelFunctionBinding> varargsBindings =
        CelFunctionBinding.fromOverloads("poly_fn", binding1, binding2);
    ImmutableSet<CelFunctionBinding> collectionBindings =
        CelFunctionBinding.fromOverloads("poly_fn", ImmutableList.of(binding1, binding2));

    assertThat(varargsBindings).isNotEmpty();
    assertThat(collectionBindings).isNotEmpty();
  }
}
