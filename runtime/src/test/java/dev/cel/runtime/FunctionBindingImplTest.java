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
import com.google.common.util.concurrent.ListenableFuture;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import dev.cel.common.exceptions.CelOverloadNotFoundException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(TestParameterInjector.class)
public final class FunctionBindingImplTest {

  @Test
  public void toListenableFuture_success_setsResult() throws Exception {
    CompletableFuture<String> cf = new CompletableFuture<>();
    ListenableFuture<?> future = FunctionBindingImpl.toListenableFuture(cf);

    cf.complete("success");

    assertThat(future.get()).isEqualTo("success");
  }

  @Test
  public void toListenableFuture_completionExceptionWithCause_unwrapsCause() {
    CompletableFuture<String> cf = new CompletableFuture<>();
    ListenableFuture<?> future = FunctionBindingImpl.toListenableFuture(cf);
    IllegalArgumentException cause = new IllegalArgumentException("unwrapped cause");

    cf.completeExceptionally(new CompletionException(cause));

    ExecutionException e = assertThrows(ExecutionException.class, future::get);
    assertThat(e).hasCauseThat().isSameInstanceAs(cause);
  }

  @Test
  public void toListenableFuture_throwableWithoutCause_setsExceptionDirectly() {
    CompletableFuture<String> cf = new CompletableFuture<>();
    ListenableFuture<?> future = FunctionBindingImpl.toListenableFuture(cf);
    IllegalArgumentException cause = new IllegalArgumentException("direct throwable");

    cf.completeExceptionally(cause);

    ExecutionException e = assertThrows(ExecutionException.class, future::get);
    assertThat(e).hasCauseThat().isSameInstanceAs(cause);
  }

  @Test
  public void toListenableFuture_cancellation_propagatesToCompletableFuture() {
    CompletableFuture<String> cf = new CompletableFuture<>();
    ListenableFuture<?> future = FunctionBindingImpl.toListenableFuture(cf);

    future.cancel(false);

    assertThat(cf.isCancelled()).isTrue();
  }

  @Test
  public void toListenableFuture_completableFutureCancelled_cancelsListenableFuture() {
    CompletableFuture<String> cf = new CompletableFuture<>();
    ListenableFuture<?> future = FunctionBindingImpl.toListenableFuture(cf);

    cf.cancel(false);

    assertThat(future.isCancelled()).isTrue();
  }

  @Test
  public void toListenableFuture_chainedCompletableFutureCancelled_cancelsListenableFuture() {
    CompletableFuture<String> upstream = new CompletableFuture<>();
    CompletableFuture<String> chained = upstream.thenApply(s -> s + "!");
    ListenableFuture<?> future = FunctionBindingImpl.toListenableFuture(chained);

    upstream.cancel(false);

    assertThat(future.isCancelled()).isTrue();
  }

  @Test
  public void toListenableFuture_completionExceptionWrappingCancellation_cancelsListenableFuture() {
    CompletableFuture<String> cf = new CompletableFuture<>();
    ListenableFuture<?> future = FunctionBindingImpl.toListenableFuture(cf);

    cf.completeExceptionally(new CompletionException(new CancellationException()));

    assertThat(future.isCancelled()).isTrue();
  }

  @Test
  public void toListenableFuture_multiLayerCompletionException_unwrapsCause() {
    CompletableFuture<String> cf = new CompletableFuture<>();
    ListenableFuture<?> future = FunctionBindingImpl.toListenableFuture(cf);
    IllegalArgumentException cause = new IllegalArgumentException("deeply nested cause");

    cf.completeExceptionally(new CompletionException(new CompletionException(cause)));

    ExecutionException e = assertThrows(ExecutionException.class, future::get);
    assertThat(e).hasCauseThat().isSameInstanceAs(cause);
  }

  @Test
  public void
      toListenableFuture_multiLayerCompletionExceptionWrappingCancellation_cancelsListenableFuture() {
    CompletableFuture<String> cf = new CompletableFuture<>();
    ListenableFuture<?> future = FunctionBindingImpl.toListenableFuture(cf);

    cf.completeExceptionally(
        new CompletionException(new CompletionException(new CancellationException())));

    assertThat(future.isCancelled()).isTrue();
  }

  @Test
  public void toListenableFuture_null_returnsNull() {
    assertThat(FunctionBindingImpl.toListenableFuture(null)).isNull();
  }

  @Test
  public void dynamicDispatch_unaryNonOptimizedOverload_invokesArrayApply() throws Exception {
    CelFunctionBinding b1 =
        CelFunctionBinding.from(
            "custom_int",
            ImmutableList.of(Long.class),
            (CelFunctionOverload) (Object[] args) -> ((Long) args[0]) * 2L);
    CelFunctionBinding b2 =
        CelFunctionBinding.from(
            "custom_string",
            ImmutableList.of(String.class),
            (CelFunctionOverload) (Object[] args) -> args[0] + "!");
    ImmutableSet<CelFunctionBinding> bindings =
        FunctionBindingImpl.groupOverloadsToFunction("custom", ImmutableSet.of(b1, b2));
    OptimizedFunctionOverload overload =
        (OptimizedFunctionOverload)
            Iterables.find(bindings, b -> b.getOverloadId().equals("custom")).getDefinition();

    assertThat(overload.apply(21L)).isEqualTo(42L);
    assertThat(overload.apply("hello")).isEqualTo("hello!");
    assertThrows(CelOverloadNotFoundException.class, () -> overload.apply(true));
  }

  @Test
  public void dynamicDispatch_binaryNonOptimizedOverload_invokesArrayApply() throws Exception {
    CelFunctionBinding b1 =
        CelFunctionBinding.from(
            "custom_add_int_int",
            ImmutableList.of(Long.class, Long.class),
            (CelFunctionOverload) (Object[] args) -> ((Long) args[0]) + ((Long) args[1]));
    CelFunctionBinding b2 =
        CelFunctionBinding.from(
            "custom_add_string_string",
            ImmutableList.of(String.class, String.class),
            (CelFunctionOverload) (Object[] args) -> (String) args[0] + (String) args[1]);
    ImmutableSet<CelFunctionBinding> bindings =
        FunctionBindingImpl.groupOverloadsToFunction("custom_add", ImmutableSet.of(b1, b2));
    OptimizedFunctionOverload overload =
        (OptimizedFunctionOverload)
            Iterables.find(bindings, b -> b.getOverloadId().equals("custom_add")).getDefinition();

    assertThat(overload.apply(10L, 20L)).isEqualTo(30L);
    assertThat(overload.apply("foo", "bar")).isEqualTo("foobar");
    assertThrows(CelOverloadNotFoundException.class, () -> overload.apply(10L, "bar"));
  }

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
        CelFunctionBinding.from(
            "custom_opt_unary_str",
            ImmutableList.of(String.class),
            (CelFunctionOverload) (Object[] args) -> args[0] + "!");
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
            "custom_opt_bin_str",
            ImmutableList.of(String.class, String.class),
            (CelFunctionOverload) (Object[] args) -> (String) args[0] + (String) args[1]);
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
  public void fromCompletableFuture_unary_invokesCompletableFutureAndAdapts() throws Exception {
    CelFunctionBinding binding =
        CelFunctionBinding.fromCompletableFuture(
            "unary_cf", Long.class, (Long arg) -> CompletableFuture.completedFuture(arg * 2L));
    CelAsyncFunctionOverload overload = (CelAsyncFunctionOverload) binding.getDefinition();

    assertThat(binding.getOverloadId()).isEqualTo("unary_cf");
    assertThat(binding.getArgTypes()).containsExactly(Long.class);
    assertThat(binding.isStrict()).isTrue();
    assertThat(binding.getDefinition()).isInstanceOf(CelAsyncFunctionOverload.class);
    assertThat(overload.applyAsync(21L).get(5, SECONDS)).isEqualTo(42L);
    assertThat(overload.applyAsync(new Object[] {21L}).get(5, SECONDS)).isEqualTo(42L);
  }

  @Test
  public void fromCompletableFuture_binary_invokesCompletableFutureAndAdapts() throws Exception {
    CelFunctionBinding binding =
        CelFunctionBinding.fromCompletableFuture(
            "binary_cf",
            Long.class,
            String.class,
            (Long a, String b) -> CompletableFuture.completedFuture(b + a));
    CelAsyncFunctionOverload overload = (CelAsyncFunctionOverload) binding.getDefinition();

    assertThat(binding.getOverloadId()).isEqualTo("binary_cf");
    assertThat(binding.getArgTypes()).containsExactly(Long.class, String.class).inOrder();
    assertThat(binding.isStrict()).isTrue();
    assertThat(binding.getDefinition()).isInstanceOf(CelAsyncFunctionOverload.class);
    assertThat(overload.applyAsync(5L, "val:").get(5, SECONDS)).isEqualTo("val:5");
    assertThat(overload.applyAsync(new Object[] {5L, "val:"}).get(5, SECONDS)).isEqualTo("val:5");
  }

  @Test
  public void fromCompletableFuture_nullArguments_throwsNullPointerException() {
    assertThrows(
        NullPointerException.class,
        () ->
            CelFunctionBinding.fromCompletableFuture(
                null, Long.class, (Long x) -> CompletableFuture.completedFuture(x)));
    assertThrows(
        NullPointerException.class,
        () ->
            CelFunctionBinding.fromCompletableFuture(
                "id", null, (Long x) -> CompletableFuture.completedFuture(x)));
    assertThrows(
        NullPointerException.class,
        () -> CelFunctionBinding.fromCompletableFuture("id", Long.class, null));

    assertThrows(
        NullPointerException.class,
        () ->
            CelFunctionBinding.fromCompletableFuture(
                null, Long.class, String.class, (a, b) -> CompletableFuture.completedFuture(a)));
    assertThrows(
        NullPointerException.class,
        () ->
            CelFunctionBinding.fromCompletableFuture(
                "id", null, String.class, (a, b) -> CompletableFuture.completedFuture(a)));
    assertThrows(
        NullPointerException.class,
        () ->
            CelFunctionBinding.fromCompletableFuture(
                "id", Long.class, null, (a, b) -> CompletableFuture.completedFuture(a)));
    assertThrows(
        NullPointerException.class,
        () -> CelFunctionBinding.fromCompletableFuture("id", Long.class, String.class, null));
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
  public void fromCompletableFuture_unary_lambdaReturnsNull_returnsNullFuture() throws Exception {
    CelFunctionBinding unaryBinding =
        CelFunctionBinding.fromCompletableFuture("null_cf", Long.class, (Long arg) -> null);
    CelAsyncFunctionOverload unaryOverload =
        (CelAsyncFunctionOverload) unaryBinding.getDefinition();

    assertThat(unaryOverload.applyAsync(1L)).isNull();
    assertThat(unaryOverload.applyAsync(new Object[] {1L})).isNull();
  }

  @Test
  public void fromCompletableFuture_binary_lambdaReturnsNull_returnsNullFuture() throws Exception {
    CelFunctionBinding binaryBinding =
        CelFunctionBinding.fromCompletableFuture(
            "null_cf_bin", Long.class, String.class, (Long a, String b) -> null);
    CelAsyncFunctionOverload binaryOverload =
        (CelAsyncFunctionOverload) binaryBinding.getDefinition();

    assertThat(binaryOverload.applyAsync(1L, "a")).isNull();
    assertThat(binaryOverload.applyAsync(new Object[] {1L, "a"})).isNull();
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
}
