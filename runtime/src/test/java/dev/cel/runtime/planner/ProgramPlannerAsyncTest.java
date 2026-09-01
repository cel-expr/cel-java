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

package dev.cel.runtime.planner;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.util.concurrent.Futures.immediateFailedFuture;
import static com.google.common.util.concurrent.Futures.immediateFuture;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static dev.cel.common.CelFunctionDecl.newFunctionDeclaration;
import static dev.cel.common.CelOverloadDecl.newGlobalOverload;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.util.concurrent.ForwardingListeningExecutorService;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.CelOptions;
import dev.cel.common.CelSource;
import dev.cel.common.ast.CelConstant;
import dev.cel.common.ast.CelExpr;
import dev.cel.common.types.ListType;
import dev.cel.common.types.MapType;
import dev.cel.common.types.SimpleType;
import dev.cel.compiler.CelCompiler;
import dev.cel.compiler.CelCompilerFactory;
import dev.cel.expr.conformance.proto3.TestAllTypes;
import dev.cel.parser.CelStandardMacro;
import dev.cel.runtime.CelAsyncCall;
import dev.cel.runtime.CelAsyncDrainAction;
import dev.cel.runtime.CelAsyncDrainStrategy;
import dev.cel.runtime.CelAsyncEvaluationOptions;
import dev.cel.runtime.CelAsyncObserver;
import dev.cel.runtime.CelAttribute;
import dev.cel.runtime.CelAttributePattern;
import dev.cel.runtime.CelEvaluationException;
import dev.cel.runtime.CelFunctionBinding;
import dev.cel.runtime.CelFunctionResolver;
import dev.cel.runtime.CelResolvedOverload;
import dev.cel.runtime.CelRuntime;
import dev.cel.runtime.CelRuntimeFactory;
import dev.cel.runtime.CelUnknownSet;
import dev.cel.runtime.CelVariableResolver;
import dev.cel.runtime.PartialVars;
import dev.cel.runtime.Program;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.LongStream;
import org.jspecify.annotations.Nullable;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(TestParameterInjector.class)
public final class ProgramPlannerAsyncTest {

  private final ListeningExecutorService executor =
      MoreExecutors.listeningDecorator(Executors.newFixedThreadPool(4));

  @After
  public void tearDown() {
    executor.shutdownNow();
  }

  private static final CelCompiler CEL_COMPILER =
      CelCompilerFactory.standardCelCompilerBuilder()
          .setStandardMacros(CelStandardMacro.STANDARD_MACROS)
          .setOptions(CelOptions.current().build())
          .addVar("x", SimpleType.INT)
          .addVar("y", SimpleType.INT)
          .addVar("dx", SimpleType.DOUBLE)
          .addVar("list_var", ListType.create(SimpleType.INT))
          .addVar("map_var", MapType.create(SimpleType.STRING, SimpleType.INT))
          .addFunctionDeclarations(
              newFunctionDeclaration(
                  "asyncSquare",
                  newGlobalOverload("asyncSquare_int", SimpleType.INT, SimpleType.INT),
                  newGlobalOverload("asyncSquare_double", SimpleType.DOUBLE, SimpleType.DOUBLE)),
              newFunctionDeclaration(
                  "asyncAdd",
                  newGlobalOverload(
                      "asyncAdd_int_int", SimpleType.INT, SimpleType.INT, SimpleType.INT)),
              newFunctionDeclaration(
                  "asyncSquareCf",
                  newGlobalOverload("asyncSquareCf_int", SimpleType.INT, SimpleType.INT)),
              newFunctionDeclaration(
                  "asyncAddCf",
                  newGlobalOverload(
                      "asyncAddCf_int_int", SimpleType.INT, SimpleType.INT, SimpleType.INT)),
              newFunctionDeclaration(
                  "asyncIsEven",
                  newGlobalOverload("asyncIsEven_int", SimpleType.BOOL, SimpleType.INT)),
              newFunctionDeclaration(
                  "asyncFail", newGlobalOverload("asyncFail_int", SimpleType.INT, SimpleType.INT)),
              newFunctionDeclaration(
                  "asyncNullReturn",
                  newGlobalOverload("asyncNullReturn_int", SimpleType.INT, SimpleType.INT)),
              newFunctionDeclaration(
                  "asyncSyncThrow",
                  newGlobalOverload("asyncSyncThrow_int", SimpleType.INT, SimpleType.INT)),
              newFunctionDeclaration(
                  "lateBoundAsync",
                  newGlobalOverload("lateBoundAsync_int", SimpleType.INT, SimpleType.INT)))
          .build();

  @Test
  public void evalAsync_unaryFunction_evaluatesSuccessfully() throws Exception {
    CelAbstractSyntaxTree ast = CEL_COMPILER.compile("asyncSquare(5)").getAst();
    CelRuntime runtime =
        CelRuntimeFactory.plannerRuntimeBuilder()
            .addFunctionBindings(
                CelFunctionBinding.fromAsync(
                    "asyncSquare_int", Long.class, (Long arg) -> immediateFuture(arg * arg)))
            .build();
    Program program = runtime.createProgram(ast);

    ListenableFuture<Object> future = program.evalAsync(executor);
    Object result = future.get(5, SECONDS);

    assertThat(result).isEqualTo(25L);
  }

  @Test
  public void evalAsync_binaryFunction_evaluatesSuccessfully() throws Exception {
    CelAbstractSyntaxTree ast = CEL_COMPILER.compile("asyncAdd(10, 20)").getAst();
    CelRuntime runtime =
        CelRuntimeFactory.plannerRuntimeBuilder()
            .addFunctionBindings(
                CelFunctionBinding.fromAsync(
                    "asyncAdd_int_int",
                    Long.class,
                    Long.class,
                    (Long a, Long b) -> immediateFuture(a + b)))
            .build();
    Program program = runtime.createProgram(ast);

    ListenableFuture<Object> future = program.evalAsync(executor);
    Object result = future.get(5, SECONDS);

    assertThat(result).isEqualTo(30L);
  }

  @Test
  public void evalAsync_completableFutureBinding_evaluatesSuccessfully() throws Exception {
    CelAbstractSyntaxTree ast = CEL_COMPILER.compile("asyncSquareCf(6)").getAst();
    CelRuntime runtime =
        CelRuntimeFactory.plannerRuntimeBuilder()
            .addFunctionBindings(
                CelFunctionBinding.fromCompletableFuture(
                    "asyncSquareCf_int",
                    Long.class,
                    (Long arg) -> CompletableFuture.completedFuture(arg * arg)))
            .build();
    Program program = runtime.createProgram(ast);

    ListenableFuture<Object> future = program.evalAsync(executor);
    Object result = future.get(5, SECONDS);

    assertThat(result).isEqualTo(36L);
  }

  @Test
  public void evalAsync_binaryCompletableFuture_evaluatesSuccessfully() throws Exception {
    CelAbstractSyntaxTree ast = CEL_COMPILER.compile("asyncAddCf(10, 20)").getAst();
    CelRuntime runtime =
        CelRuntimeFactory.plannerRuntimeBuilder()
            .addFunctionBindings(
                CelFunctionBinding.fromCompletableFuture(
                    "asyncAddCf_int_int",
                    Long.class,
                    Long.class,
                    (Long a, Long b) -> CompletableFuture.completedFuture(a + b)))
            .build();
    Program program = runtime.createProgram(ast);

    ListenableFuture<Object> future = program.evalAsync(executor);
    Object result = future.get(5, SECONDS);

    assertThat(result).isEqualTo(30L);
  }

  @Test
  @SuppressWarnings("Immutable") // Test only
  public void evalAsync_binaryCompletableFuture_failureWithCompletionException_unwrapsCause()
      throws Exception {
    CelAbstractSyntaxTree ast = CEL_COMPILER.compile("asyncAddCf(10, 20)").getAst();
    CompletableFuture<Long> failedCf = new CompletableFuture<>();
    failedCf.completeExceptionally(
        new CompletionException(new IllegalArgumentException("simulated cf error")));

    CelRuntime runtime =
        CelRuntimeFactory.plannerRuntimeBuilder()
            .addFunctionBindings(
                CelFunctionBinding.fromCompletableFuture(
                    "asyncAddCf_int_int", Long.class, Long.class, (Long a, Long b) -> failedCf))
            .build();
    Program program = runtime.createProgram(ast);

    ListenableFuture<Object> future = program.evalAsync(executor);
    ExecutionException e = assertThrows(ExecutionException.class, future::get);

    assertThat(e).hasCauseThat().isInstanceOf(CelEvaluationException.class);
    assertThat(e).hasCauseThat().hasCauseThat().isInstanceOf(IllegalArgumentException.class);
    assertThat(e).hasCauseThat().hasMessageThat().contains("simulated cf error");
  }

  @Test
  @SuppressWarnings("Immutable") // Test only
  public void evalAsync_binaryCompletableFuture_failureDirectException_propagatesCause()
      throws Exception {
    CelAbstractSyntaxTree ast = CEL_COMPILER.compile("asyncAddCf(10, 20)").getAst();
    CompletableFuture<Long> failedCf = new CompletableFuture<>();
    failedCf.completeExceptionally(new IllegalStateException("direct error"));

    CelRuntime runtime =
        CelRuntimeFactory.plannerRuntimeBuilder()
            .addFunctionBindings(
                CelFunctionBinding.fromCompletableFuture(
                    "asyncAddCf_int_int", Long.class, Long.class, (Long a, Long b) -> failedCf))
            .build();
    Program program = runtime.createProgram(ast);

    ListenableFuture<Object> future = program.evalAsync(executor);
    ExecutionException e = assertThrows(ExecutionException.class, future::get);

    assertThat(e).hasCauseThat().isInstanceOf(CelEvaluationException.class);
    assertThat(e).hasCauseThat().hasCauseThat().isInstanceOf(IllegalStateException.class);
    assertThat(e).hasCauseThat().hasMessageThat().contains("direct error");
  }

  @Test
  @SuppressWarnings({"Immutable", "FutureReturnValueIgnored"}) // Test only
  public void evalAsync_binaryCompletableFuture_cancellationPropagatesToCompletableFuture()
      throws Exception {
    CelAbstractSyntaxTree ast = CEL_COMPILER.compile("asyncAddCf(10, 20)").getAst();
    CompletableFuture<Long> inFlightCf = new CompletableFuture<>();
    CountDownLatch invoked = new CountDownLatch(1);
    CountDownLatch cancelledLatch = new CountDownLatch(1);
    inFlightCf.whenComplete(
        (res, ex) -> {
          if (inFlightCf.isCancelled()) {
            cancelledLatch.countDown();
          }
        });

    CelRuntime runtime =
        CelRuntimeFactory.plannerRuntimeBuilder()
            .addFunctionBindings(
                CelFunctionBinding.fromCompletableFuture(
                    "asyncAddCf_int_int",
                    Long.class,
                    Long.class,
                    (Long a, Long b) -> {
                      invoked.countDown();
                      return inFlightCf;
                    }))
            .build();
    Program program = runtime.createProgram(ast);

    ListenableFuture<Object> future = program.evalAsync(executor);
    assertThat(invoked.await(5, SECONDS)).isTrue();

    future.cancel(/* mayInterruptIfRunning= */ true);

    assertThat(future.isCancelled()).isTrue();
    assertThat(cancelledLatch.await(5, SECONDS)).isTrue();
    assertThat(inFlightCf.isCancelled()).isTrue();
  }

  @Test
  @SuppressWarnings("Immutable") // Test only
  public void evalAsync_diamondDependency_evaluatesConcurrently() throws Exception {
    CelAbstractSyntaxTree ast = CEL_COMPILER.compile("asyncSquare(x) + asyncSquare(y)").getAst();
    AtomicInteger invocationCount = new AtomicInteger();
    CelRuntime runtime =
        CelRuntimeFactory.plannerRuntimeBuilder()
            .addFunctionBindings(
                CelFunctionBinding.fromAsync(
                    "asyncSquare_int",
                    Long.class,
                    (Long arg) -> {
                      invocationCount.incrementAndGet();
                      return immediateFuture(arg * arg);
                    }))
            .build();
    Program program = runtime.createProgram(ast);

    ListenableFuture<Object> future =
        program.evalAsync(ImmutableMap.of("x", 3L, "y", 4L), executor);
    Object result = future.get(5, SECONDS);

    assertThat(result).isEqualTo(25L);
    assertThat(invocationCount.get()).isEqualTo(2);
  }

  @Test
  @SuppressWarnings("Immutable") // Test only
  public void evalAsync_shortCircuitOr_doesNotExecuteSkippedBranch() throws Exception {
    CelAbstractSyntaxTree ast = CEL_COMPILER.compile("true || asyncSquare(5) == 25").getAst();
    AtomicBoolean asyncCalled = new AtomicBoolean(false);
    CelRuntime runtime =
        CelRuntimeFactory.plannerRuntimeBuilder()
            .addFunctionBindings(
                CelFunctionBinding.fromAsync(
                    "asyncSquare_int",
                    Long.class,
                    (Long arg) -> {
                      asyncCalled.set(true);
                      return immediateFuture(arg * arg);
                    }))
            .build();
    Program program = runtime.createProgram(ast);

    ListenableFuture<Object> future = program.evalAsync(executor);
    Object result = future.get(5, SECONDS);

    assertThat(result).isEqualTo(true);
    assertThat(asyncCalled.get()).isFalse();
  }

  @Test
  @SuppressWarnings("Immutable") // Test only
  public void evalAsync_shortCircuitAnd_doesNotExecuteSkippedBranch() throws Exception {
    CelAbstractSyntaxTree ast = CEL_COMPILER.compile("false && asyncSquare(5) == 25").getAst();
    AtomicBoolean asyncCalled = new AtomicBoolean(false);
    CelRuntime runtime =
        CelRuntimeFactory.plannerRuntimeBuilder()
            .addFunctionBindings(
                CelFunctionBinding.fromAsync(
                    "asyncSquare_int",
                    Long.class,
                    (Long arg) -> {
                      asyncCalled.set(true);
                      return immediateFuture(arg * arg);
                    }))
            .build();
    Program program = runtime.createProgram(ast);

    ListenableFuture<Object> future = program.evalAsync(executor);
    Object result = future.get(5, SECONDS);

    assertThat(result).isEqualTo(false);
    assertThat(asyncCalled.get()).isFalse();
  }

  @Test
  public void evalAsync_comprehensionFilter_filtersCorrectly() throws Exception {
    CelAbstractSyntaxTree ast = CEL_COMPILER.compile("list_var.filter(x, asyncIsEven(x))").getAst();
    CelRuntime runtime =
        CelRuntimeFactory.plannerRuntimeBuilder()
            .addFunctionBindings(
                CelFunctionBinding.fromAsync(
                    "asyncIsEven_int", Long.class, (Long arg) -> immediateFuture(arg % 2 == 0)))
            .build();
    Program program = runtime.createProgram(ast);

    ListenableFuture<Object> future =
        program.evalAsync(
            ImmutableMap.of("list_var", ImmutableList.of(1L, 2L, 3L, 4L, 5L, 6L)), executor);
    Object result = future.get(5, SECONDS);

    assertThat((Iterable<?>) result).containsExactly(2L, 4L, 6L).inOrder();
  }

  @Test
  public void evalAsync_comprehensionExists_evaluatesSpeculatively() throws Exception {
    CelAbstractSyntaxTree ast = CEL_COMPILER.compile("list_var.exists(x, asyncIsEven(x))").getAst();
    CelRuntime runtime =
        CelRuntimeFactory.plannerRuntimeBuilder()
            .addFunctionBindings(
                CelFunctionBinding.fromAsync(
                    "asyncIsEven_int", Long.class, (Long arg) -> immediateFuture(arg % 2 == 0)))
            .build();
    Program program = runtime.createProgram(ast);

    ListenableFuture<Object> future =
        program.evalAsync(ImmutableMap.of("list_var", ImmutableList.of(1L, 3L, 4L, 7L)), executor);
    Object result = future.get(5, SECONDS);

    assertThat(result).isEqualTo(true);
  }

  @Test
  public void evalAsync_comprehensionExistsOverMap_evaluatesSpeculatively() throws Exception {
    CelAbstractSyntaxTree ast =
        CEL_COMPILER.compile("map_var.exists(k, asyncIsEven(map_var[k]))").getAst();
    CelRuntime runtime =
        CelRuntimeFactory.plannerRuntimeBuilder()
            .addFunctionBindings(
                CelFunctionBinding.fromAsync(
                    "asyncIsEven_int", Long.class, (Long arg) -> immediateFuture(arg % 2L == 0L)))
            .build();
    Program program = runtime.createProgram(ast);

    ListenableFuture<Object> future =
        program.evalAsync(ImmutableMap.of("map_var", ImmutableMap.of("a", 1L, "b", 4L)), executor);
    Object result = future.get(5, SECONDS);

    assertThat(result).isEqualTo(true);
  }

  @Test
  public void evalAsync_comprehensionAll_evaluatesSpeculatively() throws Exception {
    CelAbstractSyntaxTree ast = CEL_COMPILER.compile("list_var.all(x, asyncIsEven(x))").getAst();
    CelRuntime runtime =
        CelRuntimeFactory.plannerRuntimeBuilder()
            .addFunctionBindings(
                CelFunctionBinding.fromAsync(
                    "asyncIsEven_int", Long.class, (Long arg) -> immediateFuture(arg % 2 == 0)))
            .build();
    Program program = runtime.createProgram(ast);

    ListenableFuture<Object> future =
        program.evalAsync(ImmutableMap.of("list_var", ImmutableList.of(2L, 4L, 6L)), executor);
    Object result = future.get(5, SECONDS);

    assertThat(result).isEqualTo(true);
  }

  @Test
  public void evalAsync_lateBoundFunction_evaluatesSuccessfully() throws Exception {
    CelAbstractSyntaxTree ast = CEL_COMPILER.compile("lateBoundAsync(7)").getAst();
    CelRuntime runtime =
        CelRuntimeFactory.plannerRuntimeBuilder().addLateBoundFunctions("lateBoundAsync").build();
    Program program = runtime.createProgram(ast);

    CelFunctionResolver lateBoundResolver =
        new CelFunctionResolver() {
          @Override
          public Optional<CelResolvedOverload> findOverloadMatchingArgs(
              String functionName, Collection<String> overloadIds, Object[] args) {
            if (functionName.equals("lateBoundAsync")) {
              return Optional.of(
                  CelResolvedOverload.of(
                      functionName,
                      "lateBoundAsync_int",
                      CelFunctionBinding.fromAsync(
                              "lateBoundAsync_int",
                              Long.class,
                              (Long arg) -> immediateFuture(arg * 10))
                          .getDefinition(),
                      /* isStrict= */ true,
                      Long.class));
            }
            return Optional.empty();
          }

          @Override
          public Optional<CelResolvedOverload> findOverloadMatchingArgs(
              String functionName, Object[] args) {
            return findOverloadMatchingArgs(functionName, ImmutableList.of(), args);
          }
        };

    ListenableFuture<Object> future =
        program.evalAsync(ImmutableMap.of(), lateBoundResolver, executor);
    Object result = future.get(5, SECONDS);

    assertThat(result).isEqualTo(70L);
  }

  @Test
  public void syncEval_onAsyncFunction_throwsCelEvaluationException() throws Exception {
    CelAbstractSyntaxTree ast = CEL_COMPILER.compile("asyncSquare(5)").getAst();
    CelRuntime runtime =
        CelRuntimeFactory.plannerRuntimeBuilder()
            .addFunctionBindings(
                CelFunctionBinding.fromAsync(
                    "asyncSquare_int", Long.class, (Long arg) -> immediateFuture(arg * arg)))
            .build();
    Program program = runtime.createProgram(ast);

    CelEvaluationException e = assertThrows(CelEvaluationException.class, program::eval);
    assertThat(e)
        .hasMessageThat()
        .contains("Asynchronous functions are only supported via evalAsync");
  }

  @Test
  @SuppressWarnings("Immutable") // Test only
  public void evalAsync_respectsMaxConcurrencyLimit() throws Exception {
    CelAbstractSyntaxTree ast =
        CEL_COMPILER
            .compile(
                "asyncSquare(1) + asyncSquare(2) + asyncSquare(3) + asyncSquare(4) +"
                    + " asyncSquare(5)")
            .getAst();

    AtomicInteger activeConcurrent = new AtomicInteger();
    AtomicInteger maxObservedConcurrent = new AtomicInteger();

    CelRuntime runtime =
        CelRuntimeFactory.plannerRuntimeBuilder()
            .addFunctionBindings(
                CelFunctionBinding.fromAsync(
                    "asyncSquare_int",
                    Long.class,
                    (Long arg) -> {
                      int cur = activeConcurrent.incrementAndGet();
                      maxObservedConcurrent.accumulateAndGet(cur, Math::max);
                      try {
                        Thread.sleep(20);
                      } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                      } finally {
                        activeConcurrent.decrementAndGet();
                      }
                      return immediateFuture(arg * arg);
                    }))
            .build();
    Program program = runtime.createProgram(ast);

    CelAsyncEvaluationOptions options =
        CelAsyncEvaluationOptions.builder()
            .setMaxConcurrency(2)
            .setDrainStrategy(CelAsyncDrainStrategy.drainAll())
            .build();

    ListenableFuture<Object> future = program.evalAsync(executor, options);
    Object result = future.get(5, SECONDS);

    assertThat(result).isEqualTo(1L + 4L + 9L + 16L + 25L);
    assertThat(maxObservedConcurrent.get()).isAtMost(2);
  }

  @Test
  public void evalAsync_drainStrategyDrainNone_reevaluatesImmediately() throws Exception {
    CelAbstractSyntaxTree ast = CEL_COMPILER.compile("asyncSquare(2) + asyncSquare(3)").getAst();

    CelRuntime runtime =
        CelRuntimeFactory.plannerRuntimeBuilder()
            .addFunctionBindings(
                CelFunctionBinding.fromAsync(
                    "asyncSquare_int", Long.class, (Long arg) -> immediateFuture(arg * arg)))
            .build();
    Program program = runtime.createProgram(ast);

    CelAsyncEvaluationOptions options =
        CelAsyncEvaluationOptions.builder()
            .setDrainStrategy(CelAsyncDrainStrategy.drainNone())
            .build();

    ListenableFuture<Object> future = program.evalAsync(executor, options);
    Object result = future.get(5, SECONDS);

    assertThat(result).isEqualTo(13L);
  }

  @Test
  public void evalAsync_observerReceivesLifecycleEvents() throws Exception {
    CelAbstractSyntaxTree ast = CEL_COMPILER.compile("asyncSquare(4)").getAst();

    List<String> events = Collections.synchronizedList(new ArrayList<>());
    CelAsyncObserver observer =
        new CelAsyncObserver() {
          @Override
          public void onCallStarted(CelAsyncCall call) {
            events.add("started:" + call.functionName());
          }

          @Override
          public void onCallFinished(
              CelAsyncCall call, @Nullable Object result, @Nullable Throwable error) {
            events.add("finished:" + call.functionName() + ":" + result);
          }
        };

    CelRuntime runtime =
        CelRuntimeFactory.plannerRuntimeBuilder()
            .addFunctionBindings(
                CelFunctionBinding.fromAsync(
                    "asyncSquare_int", Long.class, (Long arg) -> immediateFuture(arg * arg)))
            .build();
    Program program = runtime.createProgram(ast);

    CelAsyncEvaluationOptions options =
        CelAsyncEvaluationOptions.builder().setObserver(observer).build();

    ListenableFuture<Object> future = program.evalAsync(executor, options);
    Object result = future.get(5, SECONDS);

    assertThat(result).isEqualTo(16L);
    assertThat(events).containsExactly("started:asyncSquare", "finished:asyncSquare:16").inOrder();
  }

  @Test
  public void evalAsync_functionFailure_propagatesCelEvaluationException() throws Exception {
    CelAbstractSyntaxTree ast = CEL_COMPILER.compile("asyncFail(1)").getAst();
    CelRuntime runtime =
        CelRuntimeFactory.plannerRuntimeBuilder()
            .addFunctionBindings(
                CelFunctionBinding.fromAsync(
                    "asyncFail_int",
                    Long.class,
                    (Long arg) ->
                        immediateFailedFuture(
                            new IllegalArgumentException("simulated async error"))))
            .build();
    Program program = runtime.createProgram(ast);

    ListenableFuture<Object> future = program.evalAsync(executor);
    ExecutionException e = assertThrows(ExecutionException.class, future::get);

    assertThat(e).hasCauseThat().isInstanceOf(CelEvaluationException.class);
    assertThat(e).hasCauseThat().hasMessageThat().contains("simulated async error");
  }

  @Test
  @SuppressWarnings("Immutable") // Test only
  public void evalAsync_futureCancellation_cancelsInFlightTasks() throws Exception {
    CelAbstractSyntaxTree ast = CEL_COMPILER.compile("asyncSquare(10)").getAst();
    SettableFuture<Object> inFlight = SettableFuture.create();
    CountDownLatch invoked = new CountDownLatch(1);
    CountDownLatch cancelledLatch = new CountDownLatch(1);
    AtomicBoolean cancelled = new AtomicBoolean(false);
    inFlight.addListener(
        () -> {
          if (inFlight.isCancelled()) {
            cancelled.set(true);
            cancelledLatch.countDown();
          }
        },
        directExecutor());

    CelRuntime runtime =
        CelRuntimeFactory.plannerRuntimeBuilder()
            .addFunctionBindings(
                CelFunctionBinding.fromAsync(
                    "asyncSquare_int",
                    Long.class,
                    (Long arg) -> {
                      invoked.countDown();
                      return inFlight;
                    }))
            .build();
    Program program = runtime.createProgram(ast);

    ListenableFuture<Object> future = program.evalAsync(executor);
    assertThat(invoked.await(5, SECONDS)).isTrue();

    future.cancel(/* mayInterruptIfRunning= */ true);

    assertThat(future.isCancelled()).isTrue();
    assertThat(cancelledLatch.await(5, SECONDS)).isTrue();
    assertThat(cancelled.get()).isTrue();
  }

  @Test
  public void evalAsync_maxIterationsExceeded_throwsCelEvaluationException() throws Exception {
    // When maxIterations is 0, evaluation immediately fails iteration safety check
    CelAbstractSyntaxTree ast = CEL_COMPILER.compile("asyncSquare(5)").getAst();
    CelRuntime runtime =
        CelRuntimeFactory.plannerRuntimeBuilder()
            .addFunctionBindings(
                CelFunctionBinding.fromAsync(
                    "asyncSquare_int", Long.class, (Long arg) -> immediateFuture(arg * arg)))
            .build();
    Program program = runtime.createProgram(ast);

    CelAsyncEvaluationOptions options =
        CelAsyncEvaluationOptions.builder().setMaxIterations(0).build();

    ListenableFuture<Object> future = program.evalAsync(executor, options);
    ExecutionException e = assertThrows(ExecutionException.class, future::get);

    assertThat(e).hasCauseThat().isInstanceOf(CelEvaluationException.class);
    assertThat(e)
        .hasCauseThat()
        .hasMessageThat()
        .contains("Exceeded maximum async evaluation iterations");
  }

  @Test
  @SuppressWarnings("Immutable") // Test only
  public void evalAsync_nanArgument_memoizesCallWithoutInfiniteLoop() throws Exception {
    CelAbstractSyntaxTree ast = CEL_COMPILER.compile("asyncSquare(dx) + 1.0").getAst();
    AtomicInteger callCount = new AtomicInteger();
    CelRuntime runtime =
        CelRuntimeFactory.plannerRuntimeBuilder()
            .addFunctionBindings(
                CelFunctionBinding.fromAsync(
                    "asyncSquare_double",
                    Double.class,
                    (Double arg) -> {
                      callCount.incrementAndGet();
                      return immediateFuture(arg * arg);
                    }))
            .build();
    Program program = runtime.createProgram(ast);

    ListenableFuture<Object> future =
        program.evalAsync(ImmutableMap.of("dx", Double.NaN), executor);
    Object result = future.get(5, SECONDS);

    assertThat(result).isInstanceOf(Double.class);
    assertThat((Double) result).isNaN();
    // Must be memoized across passes without infinite loop or double-execution
    assertThat(callCount.get()).isEqualTo(1);
  }

  @Test
  public void evalAsync_drainReadyZeroDebounce_reevaluatesImmediately() throws Exception {
    CelAbstractSyntaxTree ast = CEL_COMPILER.compile("asyncSquare(3) + asyncSquare(4)").getAst();
    CelRuntime runtime =
        CelRuntimeFactory.plannerRuntimeBuilder()
            .addFunctionBindings(
                CelFunctionBinding.fromAsync(
                    "asyncSquare_int", Long.class, (Long arg) -> immediateFuture(arg * arg)))
            .build();
    Program program = runtime.createProgram(ast);

    CelAsyncEvaluationOptions options =
        CelAsyncEvaluationOptions.builder()
            .setDrainStrategy(CelAsyncDrainStrategy.drainReady(Duration.ZERO))
            .build();

    ListenableFuture<Object> future = program.evalAsync(executor, options);
    Object result = future.get(5, SECONDS);

    assertThat(result).isEqualTo(25L);
  }

  @Test
  public void evalAsync_applyAsyncReturnsNull_failsWithDescriptiveException() throws Exception {
    CelAbstractSyntaxTree ast = CEL_COMPILER.compile("asyncNullReturn(1)").getAst();
    CelRuntime runtime =
        CelRuntimeFactory.plannerRuntimeBuilder()
            .addFunctionBindings(
                CelFunctionBinding.fromAsync("asyncNullReturn_int", Long.class, (Long arg) -> null))
            .build();
    Program program = runtime.createProgram(ast);

    ListenableFuture<Object> future = program.evalAsync(executor);
    ExecutionException e = assertThrows(ExecutionException.class, future::get);

    assertThat(e).hasCauseThat().isInstanceOf(CelEvaluationException.class);
    assertThat(e).hasCauseThat().hasMessageThat().contains("returned a null ListenableFuture");
  }

  @Test
  @SuppressWarnings("Immutable") // Test only
  public void evalAsync_synchronousException_propagatesAndCancelsSiblings() throws Exception {
    CelAbstractSyntaxTree ast =
        CEL_COMPILER.compile("asyncSquare(10) + asyncSyncThrow(5)").getAst();
    SettableFuture<Object> inFlight = SettableFuture.create();
    AtomicBoolean siblingCancelled = new AtomicBoolean(false);
    CountDownLatch siblingCancelledLatch = new CountDownLatch(1);
    inFlight.addListener(
        () -> {
          if (inFlight.isCancelled()) {
            siblingCancelled.set(true);
            siblingCancelledLatch.countDown();
          }
        },
        directExecutor());

    CelRuntime runtime =
        CelRuntimeFactory.plannerRuntimeBuilder()
            .addFunctionBindings(
                CelFunctionBinding.fromAsync("asyncSquare_int", Long.class, (Long arg) -> inFlight),
                CelFunctionBinding.fromAsync(
                    "asyncSyncThrow_int",
                    Long.class,
                    (Long arg) -> {
                      throw new IllegalStateException("synchronous crash");
                    }))
            .build();
    Program program = runtime.createProgram(ast);

    ListenableFuture<Object> future = program.evalAsync(executor);
    ExecutionException e = assertThrows(ExecutionException.class, future::get);

    assertThat(e).hasCauseThat().isInstanceOf(CelEvaluationException.class);
    assertThat(e).hasCauseThat().hasMessageThat().contains("synchronous crash");
    assertThat(siblingCancelledLatch.await(5, SECONDS)).isTrue();
    assertThat(siblingCancelled.get()).isTrue();
  }

  @Test
  @SuppressWarnings("Immutable") // Test only
  public void evalAsync_evaluationError_cancelsInFlightSiblings() throws Exception {
    CelAbstractSyntaxTree ast = CEL_COMPILER.compile("asyncSquare(10) + (1 / 0)").getAst();
    SettableFuture<Object> inFlight = SettableFuture.create();
    AtomicBoolean siblingCancelled = new AtomicBoolean(false);
    CountDownLatch siblingCancelledLatch = new CountDownLatch(1);
    inFlight.addListener(
        () -> {
          if (inFlight.isCancelled()) {
            siblingCancelled.set(true);
            siblingCancelledLatch.countDown();
          }
        },
        directExecutor());

    CelRuntime runtime =
        CelRuntimeFactory.plannerRuntimeBuilder()
            .addFunctionBindings(
                CelFunctionBinding.fromAsync("asyncSquare_int", Long.class, (Long arg) -> inFlight))
            .build();
    Program program = runtime.createProgram(ast);

    ListenableFuture<Object> future = program.evalAsync(executor);
    ExecutionException e = assertThrows(ExecutionException.class, future::get);

    assertThat(e).hasCauseThat().isInstanceOf(CelEvaluationException.class);
    assertThat(siblingCancelledLatch.await(5, SECONDS)).isTrue();
    assertThat(siblingCancelled.get()).isTrue();
  }

  @Test
  @SuppressWarnings("Immutable") // Test only
  public void evalAsync_cancellation_clearsPendingGateTasks() throws Exception {
    CelAbstractSyntaxTree ast =
        CEL_COMPILER.compile("asyncSquare(1) + asyncSquare(2) + asyncSquare(3)").getAst();
    SettableFuture<Object> task1Future = SettableFuture.create();
    CountDownLatch task1Started = new CountDownLatch(1);
    AtomicInteger queuedExecuted = new AtomicInteger();

    CelRuntime runtime =
        CelRuntimeFactory.plannerRuntimeBuilder()
            .addFunctionBindings(
                CelFunctionBinding.fromAsync(
                    "asyncSquare_int",
                    Long.class,
                    (Long arg) -> {
                      if (arg == 1L) {
                        task1Started.countDown();
                        return task1Future;
                      }
                      queuedExecuted.incrementAndGet();
                      return immediateFuture(arg * arg);
                    }))
            .build();
    Program program = runtime.createProgram(ast);

    CelAsyncEvaluationOptions options =
        CelAsyncEvaluationOptions.builder().setMaxConcurrency(1).build();

    ListenableFuture<Object> future = program.evalAsync(executor, options);
    assertThat(task1Started.await(5, SECONDS)).isTrue();

    // Cancel while task 1 is running and tasks 2 and 3 are queued in AsyncGate
    future.cancel(/* mayInterruptIfRunning= */ true);

    assertThat(future.isCancelled()).isTrue();
    // Queued tasks should never execute
    assertThat(queuedExecuted.get()).isEqualTo(0);
  }

  @Test
  @SuppressWarnings("Immutable") // Test only
  public void evalAsync_observerThrowsError_doesNotLeakPermits() throws Exception {
    CelAbstractSyntaxTree ast = CEL_COMPILER.compile("asyncSquare(2) + asyncSquare(3)").getAst();
    CelRuntime runtime =
        CelRuntimeFactory.plannerRuntimeBuilder()
            .addFunctionBindings(
                CelFunctionBinding.fromAsync(
                    "asyncSquare_int", Long.class, (Long arg) -> immediateFuture(arg * arg)))
            .build();
    Program program = runtime.createProgram(ast);

    CelAsyncObserver faultyObserver =
        new CelAsyncObserver() {
          @Override
          public void onCallStarted(CelAsyncCall call) {}

          @Override
          public void onCallFinished(
              CelAsyncCall call, @Nullable Object result, @Nullable Throwable error) {
            throw new AssertionError("Simulated test harness assertion error in observer");
          }
        };

    CelAsyncEvaluationOptions options =
        CelAsyncEvaluationOptions.builder()
            .setMaxConcurrency(1)
            .setObserver(faultyObserver)
            .build();

    ListenableFuture<Object> future = program.evalAsync(executor, options);
    Object result = future.get(5, SECONDS);

    assertThat(result).isEqualTo(13L);
  }

  @Test
  @SuppressWarnings("Immutable") // Test only
  public void evalAsync_negativeZeroDouble_normalizesInCacheKey() throws Exception {
    CelAbstractSyntaxTree ast = CEL_COMPILER.compile("asyncSquare(dx)").getAst();
    AtomicInteger callCount = new AtomicInteger();
    CelRuntime runtime =
        CelRuntimeFactory.plannerRuntimeBuilder()
            .addFunctionBindings(
                CelFunctionBinding.fromAsync(
                    "asyncSquare_double",
                    Double.class,
                    (Double arg) -> {
                      callCount.incrementAndGet();
                      return immediateFuture(arg * arg);
                    }))
            .build();
    Program program = runtime.createProgram(ast);

    ListenableFuture<Object> future = program.evalAsync(ImmutableMap.of("dx", -0.0d), executor);
    Object result = future.get(5, SECONDS);

    assertThat(result).isEqualTo(0.0d);
    assertThat(callCount.get()).isEqualTo(1);
  }

  @Test
  @SuppressWarnings({"Immutable", "FutureReturnValueIgnored"}) // Test only
  public void celFunctionBinding_fromCompletableFuture_cancellationPropagates() throws Exception {
    CompletableFuture<Object> cf = new CompletableFuture<>();
    CountDownLatch invoked = new CountDownLatch(1);
    CountDownLatch cancelledLatch = new CountDownLatch(1);
    cf.whenComplete((res, ex) -> cancelledLatch.countDown());
    CelFunctionBinding binding =
        CelFunctionBinding.fromCompletableFuture(
            "asyncSquareCf_int",
            Long.class,
            (Long arg) -> {
              invoked.countDown();
              return cf;
            });

    CelAbstractSyntaxTree ast = CEL_COMPILER.compile("asyncSquareCf(5)").getAst();
    CelRuntime runtime =
        CelRuntimeFactory.plannerRuntimeBuilder().addFunctionBindings(binding).build();
    Program program = runtime.createProgram(ast);

    ListenableFuture<Object> future = program.evalAsync(executor);
    assertThat(invoked.await(5, SECONDS)).isTrue();
    future.cancel(/* mayInterruptIfRunning= */ false);

    assertThat(future.isCancelled()).isTrue();
    assertThat(cancelledLatch.await(5, SECONDS)).isTrue();
    assertThat(cf.isCancelled()).isTrue();
  }

  @Test
  @SuppressWarnings({"Immutable", "FutureReturnValueIgnored"}) // Test only
  public void celFunctionBinding_fromCompletableFuture_failedWithCompletionException_unwrapsCause()
      throws Exception {
    CompletableFuture<Object> cf = new CompletableFuture<>();
    cf.completeExceptionally(
        new CompletionException(new IllegalArgumentException("cf wrapped fail")));
    CelFunctionBinding binding =
        CelFunctionBinding.fromCompletableFuture("asyncSquareCf_int", Long.class, (Long arg) -> cf);

    CelAbstractSyntaxTree ast = CEL_COMPILER.compile("asyncSquareCf(5)").getAst();
    CelRuntime runtime =
        CelRuntimeFactory.plannerRuntimeBuilder().addFunctionBindings(binding).build();
    Program program = runtime.createProgram(ast);

    ListenableFuture<Object> future = program.evalAsync(executor);
    ExecutionException e = assertThrows(ExecutionException.class, future::get);

    assertThat(e).hasCauseThat().isInstanceOf(CelEvaluationException.class);
    assertThat(e).hasCauseThat().hasCauseThat().isInstanceOf(IllegalArgumentException.class);
    assertThat(e).hasCauseThat().hasMessageThat().contains("cf wrapped fail");
  }

  @Test
  @SuppressWarnings({"Immutable", "FutureReturnValueIgnored"}) // Test only
  public void celFunctionBinding_fromCompletableFuture_failedDirectly_propagatesCause()
      throws Exception {
    CompletableFuture<Object> cf = new CompletableFuture<>();
    cf.completeExceptionally(new IllegalArgumentException("cf direct fail"));
    CelFunctionBinding binding =
        CelFunctionBinding.fromCompletableFuture("asyncSquareCf_int", Long.class, (Long arg) -> cf);

    CelAbstractSyntaxTree ast = CEL_COMPILER.compile("asyncSquareCf(5)").getAst();
    CelRuntime runtime =
        CelRuntimeFactory.plannerRuntimeBuilder().addFunctionBindings(binding).build();
    Program program = runtime.createProgram(ast);

    ListenableFuture<Object> future = program.evalAsync(executor);
    ExecutionException e = assertThrows(ExecutionException.class, future::get);

    assertThat(e).hasCauseThat().isInstanceOf(CelEvaluationException.class);
    assertThat(e).hasCauseThat().hasCauseThat().isInstanceOf(IllegalArgumentException.class);
    assertThat(e).hasCauseThat().hasMessageThat().contains("cf direct fail");
  }

  @Test
  public void celFunctionBinding_fromAsyncWithIterableArgTypes_evaluates() throws Exception {
    CelFunctionBinding binding =
        CelFunctionBinding.fromAsync(
            "asyncSquare_int",
            ImmutableList.of(Long.class),
            (args) -> immediateFuture(((Long) args[0]) * ((Long) args[0])));

    CelAbstractSyntaxTree ast = CEL_COMPILER.compile("asyncSquare(4)").getAst();
    CelRuntime runtime =
        CelRuntimeFactory.plannerRuntimeBuilder().addFunctionBindings(binding).build();
    Program program = runtime.createProgram(ast);

    ListenableFuture<Object> future = program.evalAsync(executor);
    assertThat(future.get(5, SECONDS)).isEqualTo(16L);
  }

  @Test
  public void evalAsync_nestedAsyncCalls_evaluatesCorrectly() throws Exception {
    CelAbstractSyntaxTree ast = CEL_COMPILER.compile("asyncSquare(asyncSquare(3))").getAst();
    CelRuntime runtime =
        CelRuntimeFactory.plannerRuntimeBuilder()
            .addFunctionBindings(
                CelFunctionBinding.fromAsync(
                    "asyncSquare_int", Long.class, (Long arg) -> immediateFuture(arg * arg)))
            .build();
    Program program = runtime.createProgram(ast);

    ListenableFuture<Object> future = program.evalAsync(executor);
    Object result = future.get(5, SECONDS);

    assertThat(result).isEqualTo(81L);
  }

  @Test
  public void evalAsync_comprehensionMap_evaluatesCorrectly() throws Exception {
    CelAbstractSyntaxTree ast = CEL_COMPILER.compile("list_var.map(x, asyncSquare(x))").getAst();
    CelRuntime runtime =
        CelRuntimeFactory.plannerRuntimeBuilder()
            .addFunctionBindings(
                CelFunctionBinding.fromAsync(
                    "asyncSquare_int", Long.class, (Long arg) -> immediateFuture(arg * arg)))
            .build();
    Program program = runtime.createProgram(ast);

    ListenableFuture<Object> future =
        program.evalAsync(ImmutableMap.of("list_var", ImmutableList.of(1L, 2L, 3L)), executor);
    Object result = future.get(5, SECONDS);

    assertThat((Iterable<?>) result).containsExactly(1L, 4L, 9L).inOrder();
  }

  @Test
  public void syncEval_onLateBoundAsyncFunction_throwsCelEvaluationException() throws Exception {
    CelAbstractSyntaxTree ast = CEL_COMPILER.compile("lateBoundAsync(10)").getAst();
    CelRuntime runtime =
        CelRuntimeFactory.plannerRuntimeBuilder().addLateBoundFunctions("lateBoundAsync").build();
    Program program = runtime.createProgram(ast);

    CelFunctionResolver lateBoundResolver =
        new CelFunctionResolver() {
          @Override
          public Optional<CelResolvedOverload> findOverloadMatchingArgs(
              String functionName, Collection<String> overloadIds, Object[] args) {
            if (functionName.equals("lateBoundAsync")) {
              return Optional.of(
                  CelResolvedOverload.of(
                      functionName,
                      "lateBoundAsync_int",
                      CelFunctionBinding.fromAsync(
                              "lateBoundAsync_int", Long.class, (Long arg) -> immediateFuture(100L))
                          .getDefinition(),
                      /* isStrict= */ true,
                      Long.class));
            }
            return Optional.empty();
          }

          @Override
          public Optional<CelResolvedOverload> findOverloadMatchingArgs(
              String functionName, Object[] args) {
            return findOverloadMatchingArgs(functionName, ImmutableList.of(), args);
          }
        };

    CelEvaluationException e =
        assertThrows(
            CelEvaluationException.class, () -> program.eval(ImmutableMap.of(), lateBoundResolver));
    assertThat(e).hasMessageThat().contains("evaluated in synchronous mode");
  }

  @Test
  public void evalAsync_partialVarsUnknown_returnsUnknownSet() throws Exception {
    CelAbstractSyntaxTree ast = CEL_COMPILER.compile("x").getAst();
    CelRuntime runtime = CelRuntimeFactory.plannerRuntimeBuilder().build();
    Program program = runtime.createProgram(ast);
    PartialVars partialVars = PartialVars.of(CelAttributePattern.create("x"));

    ListenableFuture<Object> future = program.evalAsync(partialVars, executor);
    Object result = future.get(5, SECONDS);

    assertThat(result).isInstanceOf(CelUnknownSet.class);
  }

  @Test
  public void evalAsync_comprehensionMap_evaluatesSpeculatively() throws Exception {
    CelAbstractSyntaxTree ast =
        CEL_COMPILER.compile("{\"a\": 2, \"b\": 4}.all(k, asyncIsEven(2))").getAst();
    CelRuntime runtime =
        CelRuntimeFactory.plannerRuntimeBuilder()
            .addFunctionBindings(
                CelFunctionBinding.fromAsync(
                    "asyncIsEven_int", Long.class, (Long arg) -> immediateFuture(arg % 2 == 0)))
            .build();
    Program program = runtime.createProgram(ast);

    ListenableFuture<Object> future = program.evalAsync(executor);
    Object result = future.get(5, SECONDS);

    assertThat(result).isEqualTo(true);
  }

  @Test
  public void evalAsync_comprehensionListMap_evaluatesSpeculatively() throws Exception {
    CelAbstractSyntaxTree ast = CEL_COMPILER.compile("list_var.map(x, asyncSquare(x))").getAst();
    CelRuntime runtime =
        CelRuntimeFactory.plannerRuntimeBuilder()
            .addFunctionBindings(
                CelFunctionBinding.fromAsync(
                    "asyncSquare_int", Long.class, (Long arg) -> immediateFuture(arg * arg)))
            .build();
    Program program = runtime.createProgram(ast);

    ListenableFuture<Object> future =
        program.evalAsync(ImmutableMap.of("list_var", ImmutableList.of(2L, 4L)), executor);
    Object result = future.get(5, SECONDS);

    assertThat(result).isEqualTo(ImmutableList.of(4L, 16L));
  }

  @Test
  public void evalAsync_maxIterationsExact_succeeds() throws Exception {
    CelAbstractSyntaxTree ast = CEL_COMPILER.compile("asyncSquare(5)").getAst();
    CelRuntime runtime =
        CelRuntimeFactory.plannerRuntimeBuilder()
            .addFunctionBindings(
                CelFunctionBinding.fromAsync(
                    "asyncSquare_int", Long.class, (Long arg) -> immediateFuture(arg * arg)))
            .build();
    Program program = runtime.createProgram(ast);

    CelAsyncEvaluationOptions options =
        CelAsyncEvaluationOptions.builder().setMaxIterations(2).build();

    ListenableFuture<Object> future = program.evalAsync(executor, options);
    Object result = future.get(5, SECONDS);

    assertThat(result).isEqualTo(25L);
  }

  @Test
  @SuppressWarnings("Immutable") // Test only
  public void evalAsync_maxIterationsExceeded_cancelsInFlightCalls() throws Exception {
    SettableFuture<Object> callFuture = SettableFuture.create();
    CelAbstractSyntaxTree ast = CEL_COMPILER.compile("asyncSquare(5) + asyncSquare(10)").getAst();
    CelRuntime runtime =
        CelRuntimeFactory.plannerRuntimeBuilder()
            .addFunctionBindings(
                CelFunctionBinding.fromAsync(
                    "asyncSquare_int", Long.class, (Long arg) -> callFuture))
            .build();
    Program program = runtime.createProgram(ast);

    CelAsyncEvaluationOptions options =
        CelAsyncEvaluationOptions.builder().setMaxIterations(0).build();

    ListenableFuture<Object> future = program.evalAsync(executor, options);
    ExecutionException e = assertThrows(ExecutionException.class, future::get);

    assertThat(e).hasCauseThat().isInstanceOf(CelEvaluationException.class);
    assertThat(e)
        .hasCauseThat()
        .hasMessageThat()
        .contains("Exceeded maximum async evaluation iterations");
  }

  @Test
  public void evalAsync_withObserver_recordsElapsedDurationForSuccessAndFailure() throws Exception {
    List<Duration> elapsedDurations = Collections.synchronizedList(new ArrayList<>());
    CelAsyncObserver observer =
        new CelAsyncObserver() {
          @Override
          public void onCallStarted(CelAsyncCall call) {}

          @Override
          public void onCallFinished(
              CelAsyncCall call, @Nullable Object result, @Nullable Throwable error) {
            elapsedDurations.add(call.elapsedDuration());
          }
        };

    CelAbstractSyntaxTree ast = CEL_COMPILER.compile("asyncSquare(3)").getAst();
    CelRuntime runtime =
        CelRuntimeFactory.plannerRuntimeBuilder()
            .addFunctionBindings(
                CelFunctionBinding.fromAsync(
                    "asyncSquare_int", Long.class, (Long arg) -> immediateFuture(arg * arg)))
            .build();
    Program program = runtime.createProgram(ast);

    CelAsyncEvaluationOptions options =
        CelAsyncEvaluationOptions.builder().setObserver(observer).build();

    ListenableFuture<Object> future = program.evalAsync(executor, options);
    assertThat(future.get(5, SECONDS)).isEqualTo(9L);

    assertThat(Iterables.getOnlyElement(elapsedDurations)).isAtLeast(Duration.ZERO);
  }

  @Test
  public void evalAsync_withObserver_recordsElapsedDurationOnFailure() throws Exception {
    List<Duration> elapsedDurations = Collections.synchronizedList(new ArrayList<>());
    CelAsyncObserver observer =
        new CelAsyncObserver() {
          @Override
          public void onCallStarted(CelAsyncCall call) {}

          @Override
          public void onCallFinished(
              CelAsyncCall call, @Nullable Object result, @Nullable Throwable error) {
            elapsedDurations.add(call.elapsedDuration());
          }
        };

    CelAbstractSyntaxTree ast = CEL_COMPILER.compile("asyncFail(1)").getAst();
    CelRuntime runtime =
        CelRuntimeFactory.plannerRuntimeBuilder()
            .addFunctionBindings(
                CelFunctionBinding.fromAsync(
                    "asyncFail_int",
                    Long.class,
                    (Long arg) ->
                        immediateFailedFuture(
                            new IllegalArgumentException("simulated async error"))))
            .build();
    Program program = runtime.createProgram(ast);

    CelAsyncEvaluationOptions options =
        CelAsyncEvaluationOptions.builder().setObserver(observer).build();

    ListenableFuture<Object> future = program.evalAsync(executor, options);
    assertThrows(ExecutionException.class, future::get);

    assertThat(Iterables.getOnlyElement(elapsedDurations)).isAtLeast(Duration.ZERO);
  }

  @Test
  public void evalAsync_withObserver_recordsElapsedDurationOnSyncThrow() throws Exception {
    List<Duration> elapsedDurations = Collections.synchronizedList(new ArrayList<>());
    CelAsyncObserver observer =
        new CelAsyncObserver() {
          @Override
          public void onCallStarted(CelAsyncCall call) {}

          @Override
          public void onCallFinished(
              CelAsyncCall call, @Nullable Object result, @Nullable Throwable error) {
            elapsedDurations.add(call.elapsedDuration());
          }
        };

    CelAbstractSyntaxTree ast = CEL_COMPILER.compile("asyncSyncThrow(1)").getAst();
    CelRuntime runtime =
        CelRuntimeFactory.plannerRuntimeBuilder()
            .addFunctionBindings(
                CelFunctionBinding.fromAsync(
                    "asyncSyncThrow_int",
                    Long.class,
                    (Long arg) -> {
                      throw new RuntimeException("sync boom");
                    }))
            .build();
    Program program = runtime.createProgram(ast);

    CelAsyncEvaluationOptions options =
        CelAsyncEvaluationOptions.builder().setObserver(observer).build();

    ListenableFuture<Object> future = program.evalAsync(executor, options);
    assertThrows(ExecutionException.class, future::get);

    assertThat(Iterables.getOnlyElement(elapsedDurations)).isAtLeast(Duration.ZERO);
  }

  @Test
  public void evalAsync_programThreadSafety_evaluatesConcurrentlyOnMultipleThreads()
      throws Exception {
    CelAbstractSyntaxTree ast = CEL_COMPILER.compile("asyncSquare(x) + 1").getAst();
    CelRuntime runtime =
        CelRuntimeFactory.plannerRuntimeBuilder()
            .addFunctionBindings(
                CelFunctionBinding.fromAsync(
                    "asyncSquare_int",
                    Long.class,
                    (Long arg) -> {
                      try {
                        Thread.sleep(5);
                      } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                      }
                      return immediateFuture(arg * arg);
                    }))
            .build();
    Program program = runtime.createProgram(ast);

    int numThreads = 10;
    ListeningExecutorService clientExecutor =
        MoreExecutors.listeningDecorator(Executors.newFixedThreadPool(numThreads));
    try {
      CountDownLatch startGate = new CountDownLatch(1);
      List<ListenableFuture<Long>> futures = new ArrayList<>();
      for (int i = 0; i < numThreads; i++) {
        long xVal = i * 10L;
        futures.add(
            clientExecutor.submit(
                () -> {
                  startGate.await();
                  return (Long)
                      program.evalAsync(ImmutableMap.of("x", xVal), executor).get(5, SECONDS);
                }));
      }
      startGate.countDown();

      List<Long> results = Futures.allAsList(futures).get(5, SECONDS);
      ImmutableList<Long> expected =
          LongStream.range(0, numThreads)
              .map(i -> (i * 10L) * (i * 10L) + 1L)
              .boxed()
              .collect(toImmutableList());
      assertThat(results).containsExactlyElementsIn(expected).inOrder();
    } finally {
      clientExecutor.shutdownNow();
    }
  }

  @Test
  @SuppressWarnings("Immutable") // Test only
  public void evalAsync_shortCircuitTernary_falseCondition_doesNotExecuteTrueBranch()
      throws Exception {
    CelAbstractSyntaxTree ast = CEL_COMPILER.compile("false ? asyncSquare(5) : 42").getAst();
    AtomicBoolean asyncCalled = new AtomicBoolean(false);
    CelRuntime runtime =
        CelRuntimeFactory.plannerRuntimeBuilder()
            .addFunctionBindings(
                CelFunctionBinding.fromAsync(
                    "asyncSquare_int",
                    Long.class,
                    (Long arg) -> {
                      asyncCalled.set(true);
                      return immediateFuture(arg * arg);
                    }))
            .build();
    Program program = runtime.createProgram(ast);

    ListenableFuture<Object> future = program.evalAsync(executor);
    Object result = future.get(5, SECONDS);

    assertThat(result).isEqualTo(42L);
    assertThat(asyncCalled.get()).isFalse();
  }

  @Test
  @SuppressWarnings("Immutable") // Test only
  public void evalAsync_shortCircuitTernary_trueCondition_doesNotExecuteFalseBranch()
      throws Exception {
    CelAbstractSyntaxTree ast = CEL_COMPILER.compile("true ? 42 : asyncSquare(5)").getAst();
    AtomicBoolean asyncCalled = new AtomicBoolean(false);
    CelRuntime runtime =
        CelRuntimeFactory.plannerRuntimeBuilder()
            .addFunctionBindings(
                CelFunctionBinding.fromAsync(
                    "asyncSquare_int",
                    Long.class,
                    (Long arg) -> {
                      asyncCalled.set(true);
                      return immediateFuture(arg * arg);
                    }))
            .build();
    Program program = runtime.createProgram(ast);

    ListenableFuture<Object> future = program.evalAsync(executor);
    Object result = future.get(5, SECONDS);

    assertThat(result).isEqualTo(42L);
    assertThat(asyncCalled.get()).isFalse();
  }

  @Test
  @SuppressWarnings("Immutable") // Test only
  public void evalAsync_shortCircuitTernary_asyncConditionEven_evaluatesOnlyTrueBranch()
      throws Exception {
    CelAbstractSyntaxTree ast =
        CEL_COMPILER.compile("asyncIsEven(x) ? asyncSquare(5) : asyncSquare(10)").getAst();
    List<Long> squaresCalled = Collections.synchronizedList(new ArrayList<>());
    CelRuntime runtime =
        CelRuntimeFactory.plannerRuntimeBuilder()
            .addFunctionBindings(
                CelFunctionBinding.fromAsync(
                    "asyncIsEven_int", Long.class, (Long arg) -> immediateFuture(arg % 2L == 0L)),
                CelFunctionBinding.fromAsync(
                    "asyncSquare_int",
                    Long.class,
                    (Long arg) -> {
                      squaresCalled.add(arg);
                      return immediateFuture(arg * arg);
                    }))
            .build();
    Program program = runtime.createProgram(ast);

    ListenableFuture<Object> future = program.evalAsync(ImmutableMap.of("x", 2L), executor);
    Object result = future.get(5, SECONDS);

    assertThat(result).isEqualTo(25L);
    assertThat(squaresCalled).containsExactly(5L);
  }

  @Test
  @SuppressWarnings("Immutable") // Test only
  public void evalAsync_shortCircuitTernary_asyncConditionOdd_evaluatesOnlyFalseBranch()
      throws Exception {
    CelAbstractSyntaxTree ast =
        CEL_COMPILER.compile("asyncIsEven(x) ? asyncSquare(5) : asyncSquare(10)").getAst();
    List<Long> squaresCalled = Collections.synchronizedList(new ArrayList<>());
    CelRuntime runtime =
        CelRuntimeFactory.plannerRuntimeBuilder()
            .addFunctionBindings(
                CelFunctionBinding.fromAsync(
                    "asyncIsEven_int", Long.class, (Long arg) -> immediateFuture(arg % 2L == 0L)),
                CelFunctionBinding.fromAsync(
                    "asyncSquare_int",
                    Long.class,
                    (Long arg) -> {
                      squaresCalled.add(arg);
                      return immediateFuture(arg * arg);
                    }))
            .build();
    Program program = runtime.createProgram(ast);

    ListenableFuture<Object> future = program.evalAsync(ImmutableMap.of("x", 3L), executor);
    Object result = future.get(5, SECONDS);

    assertThat(result).isEqualTo(100L);
    assertThat(squaresCalled).containsExactly(10L);
  }

  @Test
  @SuppressWarnings("Immutable") // Test only
  public void evalAsync_threeValuedLogicOr_resolvesWithoutWaitingForAsync() throws Exception {
    CelAbstractSyntaxTree ast =
        CEL_COMPILER.compile("(asyncSquare(10) == 100) || (x == 1)").getAst();
    SettableFuture<Object> slowFuture = SettableFuture.create();
    CelRuntime runtime =
        CelRuntimeFactory.plannerRuntimeBuilder()
            .addFunctionBindings(
                CelFunctionBinding.fromAsync(
                    "asyncSquare_int", Long.class, (Long arg) -> slowFuture))
            .build();
    Program program = runtime.createProgram(ast);

    ListenableFuture<Object> future = program.evalAsync(ImmutableMap.of("x", 1L), executor);
    Object result = future.get(5, SECONDS);

    assertThat((Boolean) result).isTrue();
  }

  @Test
  @SuppressWarnings("Immutable") // Test only
  public void evalAsync_threeValuedLogicOr_falseBranch_waitsForAsync() throws Exception {
    CelAbstractSyntaxTree ast =
        CEL_COMPILER.compile("(asyncSquare(10) == 100) || (x == 1)").getAst();
    SettableFuture<Object> asyncFuture = SettableFuture.create();
    CelRuntime runtime =
        CelRuntimeFactory.plannerRuntimeBuilder()
            .addFunctionBindings(
                CelFunctionBinding.fromAsync(
                    "asyncSquare_int", Long.class, (Long arg) -> asyncFuture))
            .build();
    Program program = runtime.createProgram(ast);

    ListenableFuture<Object> future = program.evalAsync(ImmutableMap.of("x", 0L), executor);
    assertThat(future.isDone()).isFalse();

    asyncFuture.set(100L);
    Object result = future.get(5, SECONDS);
    assertThat((Boolean) result).isTrue();
  }

  @Test
  @SuppressWarnings("Immutable") // Test only
  public void evalAsync_threeValuedLogicAnd_resolvesWithoutWaitingForAsync() throws Exception {
    CelAbstractSyntaxTree ast =
        CEL_COMPILER.compile("(asyncSquare(10) == 100) && (x == 1)").getAst();
    SettableFuture<Object> slowFuture = SettableFuture.create();
    CelRuntime runtime =
        CelRuntimeFactory.plannerRuntimeBuilder()
            .addFunctionBindings(
                CelFunctionBinding.fromAsync(
                    "asyncSquare_int", Long.class, (Long arg) -> slowFuture))
            .build();
    Program program = runtime.createProgram(ast);

    ListenableFuture<Object> future = program.evalAsync(ImmutableMap.of("x", 0L), executor);
    Object result = future.get(5, SECONDS);

    assertThat((Boolean) result).isFalse();
  }

  @Test
  @SuppressWarnings("Immutable") // Test only
  public void evalAsync_threeValuedLogicAnd_trueBranch_waitsForAsync() throws Exception {
    CelAbstractSyntaxTree ast =
        CEL_COMPILER.compile("(asyncSquare(10) == 100) && (x == 1)").getAst();
    SettableFuture<Object> asyncFuture = SettableFuture.create();
    CelRuntime runtime =
        CelRuntimeFactory.plannerRuntimeBuilder()
            .addFunctionBindings(
                CelFunctionBinding.fromAsync(
                    "asyncSquare_int", Long.class, (Long arg) -> asyncFuture))
            .build();
    Program program = runtime.createProgram(ast);

    ListenableFuture<Object> future = program.evalAsync(ImmutableMap.of("x", 1L), executor);
    assertThat(future.isDone()).isFalse();

    asyncFuture.set(100L);
    Object result = future.get(5, SECONDS);
    assertThat((Boolean) result).isTrue();
  }

  @Test
  public void evalAsync_comprehensionAsyncError_propagatesFailure() throws Exception {
    CelAbstractSyntaxTree ast =
        CEL_COMPILER.compile("list_var.map(i, i == 2 ? asyncFail(i) : asyncSquare(i))").getAst();
    CelRuntime runtime =
        CelRuntimeFactory.plannerRuntimeBuilder()
            .addFunctionBindings(
                CelFunctionBinding.fromAsync(
                    "asyncSquare_int", Long.class, (Long arg) -> immediateFuture(arg * arg)),
                CelFunctionBinding.fromAsync(
                    "asyncFail_int",
                    Long.class,
                    (Long arg) ->
                        immediateFailedFuture(new IllegalArgumentException("comprehension fail"))))
            .build();
    Program program = runtime.createProgram(ast);

    ListenableFuture<Object> future =
        program.evalAsync(ImmutableMap.of("list_var", ImmutableList.of(1L, 2L, 3L)), executor);
    ExecutionException e = assertThrows(ExecutionException.class, future::get);

    assertThat(e).hasCauseThat().isInstanceOf(CelEvaluationException.class);
    assertThat(e).hasCauseThat().hasMessageThat().contains("comprehension fail");
  }

  @Test
  @SuppressWarnings("Immutable") // Test only
  public void evalAsync_mixedPartialUnknownAndAsync_resolvesAsyncAndReturnsUnknownSet()
      throws Exception {
    CelAbstractSyntaxTree ast = CEL_COMPILER.compile("asyncSquare(5) + x").getAst();
    AtomicBoolean asyncCalled = new AtomicBoolean(false);
    CelRuntime runtime =
        CelRuntimeFactory.plannerRuntimeBuilder()
            .addFunctionBindings(
                CelFunctionBinding.fromAsync(
                    "asyncSquare_int",
                    Long.class,
                    (Long arg) -> {
                      asyncCalled.set(true);
                      return immediateFuture(arg * arg);
                    }))
            .build();
    Program program = runtime.createProgram(ast);
    PartialVars partialVars = PartialVars.of(CelAttributePattern.create("x"));

    ListenableFuture<Object> future = program.evalAsync(partialVars, executor);
    Object result = future.get(5, SECONDS);

    assertThat(asyncCalled.get()).isTrue();
    assertThat(result).isInstanceOf(CelUnknownSet.class);
    CelUnknownSet unknownSet = (CelUnknownSet) result;
    assertThat(unknownSet.attributes()).containsExactly(CelAttribute.fromQualifiedIdentifier("x"));
  }

  @Test
  @SuppressWarnings("Immutable") // Test only
  public void evalAsync_highVolumeFanout_respectsConcurrencyAndCompletes() throws Exception {
    ImmutableList.Builder<Long> itemsBuilder = ImmutableList.builder();
    for (long i = 1; i <= 50; i++) {
      itemsBuilder.add(i);
    }
    ImmutableList<Long> items = itemsBuilder.build();
    ImmutableList<Long> expected =
        LongStream.rangeClosed(1, 50).map(x -> x * x).boxed().collect(toImmutableList());

    CelAbstractSyntaxTree ast = CEL_COMPILER.compile("list_var.map(x, asyncSquare(x))").getAst();
    AtomicInteger activeConcurrent = new AtomicInteger();
    AtomicInteger maxConcurrent = new AtomicInteger();
    ListeningExecutorService workerPool =
        MoreExecutors.listeningDecorator(Executors.newFixedThreadPool(4));
    try {
      CelRuntime runtime =
          CelRuntimeFactory.plannerRuntimeBuilder()
              .addFunctionBindings(
                  CelFunctionBinding.fromAsync(
                      "asyncSquare_int",
                      Long.class,
                      (Long arg) ->
                          workerPool.submit(
                              () -> {
                                int cur = activeConcurrent.incrementAndGet();
                                maxConcurrent.accumulateAndGet(cur, Math::max);
                                try {
                                  Thread.sleep(5);
                                  return arg * arg;
                                } finally {
                                  activeConcurrent.decrementAndGet();
                                }
                              })))
              .build();
      Program program = runtime.createProgram(ast);

      CelAsyncEvaluationOptions options =
          CelAsyncEvaluationOptions.builder().setMaxConcurrency(3).build();

      ListenableFuture<Object> future =
          program.evalAsync(ImmutableMap.of("list_var", items), executor, options);
      Object result = future.get(10, SECONDS);

      assertThat((Iterable<?>) result).containsExactlyElementsIn(expected).inOrder();
      assertThat(maxConcurrent.get()).isAtLeast(2);
      assertThat(maxConcurrent.get()).isAtMost(3);
    } finally {
      workerPool.shutdownNow();
    }
  }

  @Test
  @SuppressWarnings("Immutable") // Test only
  public void evalAsync_mapComprehensionLoopStepAsync_mergesAccumulatedUnknownsAcrossIterations()
      throws Exception {
    CelExpr comprehensionExpr =
        CelExpr.ofComprehension(
            1L,
            "k",
            "",
            CelExpr.ofMap(
                2L,
                ImmutableList.of(
                    CelExpr.ofMapEntry(
                        3L,
                        CelExpr.ofConstant(4L, CelConstant.ofValue("a")),
                        CelExpr.ofConstant(5L, CelConstant.ofValue(1L)),
                        false),
                    CelExpr.ofMapEntry(
                        6L,
                        CelExpr.ofConstant(7L, CelConstant.ofValue("b")),
                        CelExpr.ofConstant(8L, CelConstant.ofValue(2L)),
                        false))),
            "acc",
            CelExpr.ofConstant(9L, CelConstant.ofValue(0L)),
            CelExpr.ofConstant(10L, CelConstant.ofValue(true)),
            CelExpr.ofCall(
                11L, Optional.empty(), "asyncEcho", ImmutableList.of(CelExpr.ofIdent(12L, "k"))),
            CelExpr.ofIdent(13L, "acc"));
    CelAbstractSyntaxTree ast =
        CelAbstractSyntaxTree.newParsedAst(comprehensionExpr, CelSource.newBuilder().build());

    Map<String, SettableFuture<Object>> futures = new HashMap<>();
    futures.put("a", SettableFuture.create());
    futures.put("b", SettableFuture.create());

    CelRuntime runtime =
        CelRuntimeFactory.plannerRuntimeBuilder()
            .addFunctionBindings(
                CelFunctionBinding.fromAsync(
                    "asyncEcho", String.class, (String arg) -> futures.get(arg)))
            .build();
    Program program = runtime.createProgram(ast);

    ListenableFuture<Object> evalFuture = program.evalAsync(executor);

    // Both calls for "a" and "b" should have been speculatively dispatched in the comprehension
    assertThat(futures.get("a").isCancelled()).isFalse();
    assertThat(futures.get("b").isCancelled()).isFalse();
    assertThat(evalFuture.isDone()).isFalse();

    // Complete "a"
    futures.get("a").set(10L);
    // evalFuture must not be done yet because "b" is still pending
    assertThat(evalFuture.isDone()).isFalse();

    // Complete "b"
    futures.get("b").set(20L);
    Object result = evalFuture.get(5, SECONDS);
    assertThat(result).isEqualTo(20L);
  }

  @Test
  @SuppressWarnings("Immutable") // Test only
  public void evalAsync_evalException_clearsPendingGateTasks() throws Exception {
    CelAbstractSyntaxTree ast =
        CEL_COMPILER.compile("asyncSquare(1) + asyncSquare(2) + (1 / 0)").getAst();
    SettableFuture<Object> task1Future = SettableFuture.create();
    CountDownLatch task1Started = new CountDownLatch(1);
    AtomicInteger task2Executed = new AtomicInteger();

    CelRuntime runtime =
        CelRuntimeFactory.plannerRuntimeBuilder()
            .addFunctionBindings(
                CelFunctionBinding.fromAsync(
                    "asyncSquare_int",
                    Long.class,
                    (Long arg) -> {
                      if (arg == 1L) {
                        task1Started.countDown();
                        return task1Future;
                      }
                      task2Executed.incrementAndGet();
                      return immediateFuture(arg * arg);
                    }))
            .build();
    Program program = runtime.createProgram(ast);

    CelAsyncEvaluationOptions options =
        CelAsyncEvaluationOptions.builder().setMaxConcurrency(1).build();

    ListenableFuture<Object> future = program.evalAsync(executor, options);
    assertThat(task1Started.await(5, SECONDS)).isTrue();

    // 1 / 0 throws CelEvaluationException synchronously during eval, calling cancelAll()
    ExecutionException e = assertThrows(ExecutionException.class, future::get);
    assertThat(e).hasCauseThat().isInstanceOf(CelEvaluationException.class);
    assertThat(e).hasCauseThat().hasMessageThat().contains("/ by zero");

    // Task 1 was in-flight and must be cancelled by cancelAll()
    assertThat(task1Future.isCancelled()).isTrue();

    // Task 1 now finishes. Gate must be cancelled so queued task 2 never executes
    task1Future.set(1L);

    assertThat(task2Executed.get()).isEqualTo(0);
  }

  @Test
  @SuppressWarnings("Immutable") // Test only
  public void evalAsync_mapComprehensionConditionAsync_mergesAccumulatedUnknownsAcrossIterations()
      throws Exception {
    CelExpr comprehensionExpr =
        CelExpr.ofComprehension(
            1L,
            "k",
            "",
            CelExpr.ofMap(
                2L,
                ImmutableList.of(
                    CelExpr.ofMapEntry(
                        3L,
                        CelExpr.ofConstant(4L, CelConstant.ofValue("a")),
                        CelExpr.ofConstant(5L, CelConstant.ofValue(1L)),
                        false),
                    CelExpr.ofMapEntry(
                        6L,
                        CelExpr.ofConstant(7L, CelConstant.ofValue("b")),
                        CelExpr.ofConstant(8L, CelConstant.ofValue(2L)),
                        false))),
            "acc",
            CelExpr.ofConstant(9L, CelConstant.ofValue(0L)),
            CelExpr.ofCall(
                10L, Optional.empty(), "asyncCond", ImmutableList.of(CelExpr.ofIdent(11L, "k"))),
            CelExpr.ofCall(
                12L, Optional.empty(), "asyncEcho", ImmutableList.of(CelExpr.ofIdent(13L, "k"))),
            CelExpr.ofIdent(14L, "acc"));
    CelAbstractSyntaxTree ast =
        CelAbstractSyntaxTree.newParsedAst(comprehensionExpr, CelSource.newBuilder().build());

    Map<String, SettableFuture<Object>> condFutures = new HashMap<>();
    condFutures.put("a", SettableFuture.create());
    condFutures.put("b", SettableFuture.create());

    CelRuntime runtime =
        CelRuntimeFactory.plannerRuntimeBuilder()
            .addFunctionBindings(
                CelFunctionBinding.fromAsync(
                    "asyncCond", String.class, (String arg) -> condFutures.get(arg)),
                CelFunctionBinding.fromAsync(
                    "asyncEcho", String.class, (String arg) -> immediateFuture(100L)))
            .build();
    Program program = runtime.createProgram(ast);

    ListenableFuture<Object> evalFuture = program.evalAsync(executor);

    // Both condition evaluations for "a" and "b" should have been speculatively dispatched
    assertThat(condFutures.get("a").isCancelled()).isFalse();
    assertThat(condFutures.get("b").isCancelled()).isFalse();
    assertThat(evalFuture.isDone()).isFalse();

    // Complete "a" condition with true
    condFutures.get("a").set(true);
    assertThat(evalFuture.isDone()).isFalse();

    // Complete "b" condition with true
    condFutures.get("b").set(true);

    Object result = evalFuture.get(5, SECONDS);
    assertThat(result).isNotNull();
  }

  @Test
  @SuppressWarnings("Immutable") // Test only
  public void evalAsync_shortCircuitOr_cancelsOrphanedInFlightFuture() throws Exception {
    CelAbstractSyntaxTree ast = CEL_COMPILER.compile("asyncIsEven(y) || asyncIsEven(x)").getAst();
    SettableFuture<Object> yFuture = SettableFuture.create();
    SettableFuture<Object> xFuture = SettableFuture.create();
    CountDownLatch xStarted = new CountDownLatch(1);

    CelRuntime runtime =
        CelRuntimeFactory.plannerRuntimeBuilder()
            .addFunctionBindings(
                CelFunctionBinding.fromAsync(
                    "asyncIsEven_int",
                    Long.class,
                    (Long arg) -> {
                      if (arg == 2L) {
                        return yFuture;
                      }
                      xStarted.countDown();
                      return xFuture;
                    }))
            .build();
    Program program = runtime.createProgram(ast);

    ListenableFuture<Object> evalFuture =
        program.evalAsync(ImmutableMap.of("y", 2L, "x", 3L), executor);

    // Wait until x has been dispatched and is actively in-flight
    assertThat(xStarted.await(5, SECONDS)).isTrue();
    assertThat(evalFuture.isDone()).isFalse();

    // Complete y with true, which resolves the OR expression
    yFuture.set(true);

    Object result = evalFuture.get(5, SECONDS);

    assertThat(result).isEqualTo(true);
    // The in-flight speculative task xFuture must be cancelled
    assertThat(xFuture.isCancelled()).isTrue();
  }

  @Test
  @SuppressWarnings("Immutable") // Test only
  public void evalAsync_nestedAsyncCalls_withInFlightInnerCall_evaluatesCorrectly()
      throws Exception {
    CelAbstractSyntaxTree ast = CEL_COMPILER.compile("asyncSquare(asyncSquare(3))").getAst();
    SettableFuture<Object> innerFuture = SettableFuture.create();
    SettableFuture<Object> outerFuture = SettableFuture.create();

    CelRuntime runtime =
        CelRuntimeFactory.plannerRuntimeBuilder()
            .addFunctionBindings(
                CelFunctionBinding.fromAsync(
                    "asyncSquare_int",
                    Long.class,
                    (Long arg) -> {
                      if (arg == 3L) {
                        return innerFuture;
                      }
                      return outerFuture;
                    }))
            .build();
    Program program = runtime.createProgram(ast);

    ListenableFuture<Object> evalFuture = program.evalAsync(executor);

    assertThat(evalFuture.isDone()).isFalse();

    innerFuture.set(9L);
    outerFuture.set(81L);

    Object result = evalFuture.get(5, SECONDS);
    assertThat(result).isEqualTo(81L);
  }

  @Test
  @SuppressWarnings("Immutable") // Test only
  public void evalAsync_comprehensionExistsOverMap_withInFlightAsync_evaluatesCorrectly()
      throws Exception {
    CelAbstractSyntaxTree ast =
        CEL_COMPILER.compile("map_var.exists(k, asyncIsEven(map_var[k]))").getAst();
    SettableFuture<Object> futureA = SettableFuture.create();
    SettableFuture<Object> futureB = SettableFuture.create();

    CelRuntime runtime =
        CelRuntimeFactory.plannerRuntimeBuilder()
            .addFunctionBindings(
                CelFunctionBinding.fromAsync(
                    "asyncIsEven_int",
                    Long.class,
                    (Long arg) -> {
                      if (arg == 1L) {
                        return futureA;
                      }
                      return futureB;
                    }))
            .build();
    Program program = runtime.createProgram(ast);

    ListenableFuture<Object> evalFuture =
        program.evalAsync(ImmutableMap.of("map_var", ImmutableMap.of("a", 1L, "b", 4L)), executor);

    assertThat(evalFuture.isDone()).isFalse();

    futureA.set(false);
    futureB.set(true);

    Object result = evalFuture.get(5, SECONDS);
    assertThat(result).isEqualTo(true);
  }

  @Test
  @SuppressWarnings("Immutable") // Test only
  public void evalAsync_comprehensionMap_withInFlightAsync_evaluatesCorrectly() throws Exception {
    CelAbstractSyntaxTree ast = CEL_COMPILER.compile("list_var.map(x, asyncSquare(x))").getAst();
    SettableFuture<Object> future1 = SettableFuture.create();
    SettableFuture<Object> future2 = SettableFuture.create();

    CelRuntime runtime =
        CelRuntimeFactory.plannerRuntimeBuilder()
            .addFunctionBindings(
                CelFunctionBinding.fromAsync(
                    "asyncSquare_int",
                    Long.class,
                    (Long arg) -> {
                      if (arg == 2L) {
                        return future1;
                      }
                      return future2;
                    }))
            .build();
    Program program = runtime.createProgram(ast);

    ListenableFuture<Object> evalFuture =
        program.evalAsync(ImmutableMap.of("list_var", ImmutableList.of(2L, 3L)), executor);

    assertThat(evalFuture.isDone()).isFalse();

    future1.set(4L);
    future2.set(9L);

    Object result = evalFuture.get(5, SECONDS);
    assertThat((Iterable<?>) result).containsExactly(4L, 9L).inOrder();
  }

  @Test
  @SuppressWarnings("Immutable") // Test only
  public void evalAsync_maxIterationsExceeded_cancelsInFlightTasksAndEnforcesLimit()
      throws Exception {
    CelAbstractSyntaxTree ast = CEL_COMPILER.compile("asyncSquare(1) + asyncSquare(2)").getAst();
    SettableFuture<Object> firstFuture = SettableFuture.create();
    SettableFuture<Object> secondFuture = SettableFuture.create();

    CelRuntime runtime =
        CelRuntimeFactory.plannerRuntimeBuilder()
            .addFunctionBindings(
                CelFunctionBinding.fromAsync(
                    "asyncSquare_int",
                    Long.class,
                    (Long arg) -> {
                      if (arg == 1L) {
                        return firstFuture;
                      }
                      return secondFuture;
                    }))
            .build();
    Program program = runtime.createProgram(ast);

    CelAsyncEvaluationOptions options =
        CelAsyncEvaluationOptions.builder().setMaxIterations(1).build();
    ListenableFuture<Object> evalFuture = program.evalAsync(ImmutableMap.of(), executor, options);

    firstFuture.set(1L);

    ExecutionException e = assertThrows(ExecutionException.class, () -> evalFuture.get(5, SECONDS));
    assertThat(e).hasCauseThat().isInstanceOf(CelEvaluationException.class);
    assertThat(e)
        .hasCauseThat()
        .hasMessageThat()
        .contains("Exceeded maximum async evaluation iterations: 1");
    assertThat(secondFuture.isCancelled()).isTrue();
  }

  @Test
  @SuppressWarnings("Immutable") // Test only
  public void evalAsync_zeroMaxIterations_failsImmediatelyWithoutEvaluating() throws Exception {
    CelAbstractSyntaxTree ast = CEL_COMPILER.compile("asyncSquare(1)").getAst();
    AtomicBoolean functionCalled = new AtomicBoolean(false);

    CelRuntime runtime =
        CelRuntimeFactory.plannerRuntimeBuilder()
            .addFunctionBindings(
                CelFunctionBinding.fromAsync(
                    "asyncSquare_int",
                    Long.class,
                    (Long arg) -> {
                      functionCalled.set(true);
                      return SettableFuture.create();
                    }))
            .build();
    Program program = runtime.createProgram(ast);

    CelAsyncEvaluationOptions options =
        CelAsyncEvaluationOptions.builder().setMaxIterations(0).build();
    ListenableFuture<Object> evalFuture = program.evalAsync(ImmutableMap.of(), executor, options);

    ExecutionException e = assertThrows(ExecutionException.class, () -> evalFuture.get(5, SECONDS));
    assertThat(e).hasCauseThat().isInstanceOf(CelEvaluationException.class);
    assertThat(e)
        .hasCauseThat()
        .hasMessageThat()
        .contains("Exceeded maximum async evaluation iterations: 0");
    assertThat(functionCalled.get()).isFalse();
  }

  @Test
  @SuppressWarnings("Immutable") // Test only
  public void evalAsync_binaryAsyncCall_withMultipleInFlightArguments_evaluatesCorrectly()
      throws Exception {
    CelAbstractSyntaxTree ast =
        CEL_COMPILER.compile("asyncAdd(asyncSquare(2), asyncSquare(3))").getAst();
    SettableFuture<Object> square2Future = SettableFuture.create();
    SettableFuture<Object> square3Future = SettableFuture.create();
    CountDownLatch square2Invoked = new CountDownLatch(1);
    CountDownLatch square3Invoked = new CountDownLatch(1);
    AtomicInteger outerCallCount = new AtomicInteger();

    CelRuntime runtime =
        CelRuntimeFactory.plannerRuntimeBuilder()
            .addFunctionBindings(
                CelFunctionBinding.fromAsync(
                    "asyncSquare_int",
                    Long.class,
                    (Long arg) -> {
                      if (arg == 2L) {
                        square2Invoked.countDown();
                        return square2Future;
                      }
                      square3Invoked.countDown();
                      return square3Future;
                    }),
                CelFunctionBinding.fromAsync(
                    "asyncAdd_int_int",
                    Long.class,
                    Long.class,
                    (Long a, Long b) -> {
                      outerCallCount.incrementAndGet();
                      return immediateFuture(a + b);
                    }))
            .build();
    Program program = runtime.createProgram(ast);

    ListenableFuture<Object> evalFuture = program.evalAsync(executor);

    assertThat(square2Invoked.await(5, SECONDS)).isTrue();
    assertThat(square3Invoked.await(5, SECONDS)).isTrue();
    assertThat(evalFuture.isDone()).isFalse();
    assertThat(outerCallCount.get()).isEqualTo(0);

    square2Future.set(4L);
    square3Future.set(9L);

    Object result = evalFuture.get(5, SECONDS);

    assertThat(result).isEqualTo(13L);
    assertThat(outerCallCount.get()).isEqualTo(1);
  }

  @Test
  @SuppressWarnings("Immutable") // Test only
  public void evalAsync_evaluationCancelled_clearsPendingGateTasks() throws Exception {
    CelAbstractSyntaxTree ast = CEL_COMPILER.compile("[1, 2].map(n, asyncSquare(n))").getAst();
    SettableFuture<Object> firstFuture = SettableFuture.create();
    SettableFuture<Object> secondFuture = SettableFuture.create();
    CountDownLatch task1Started = new CountDownLatch(1);
    AtomicInteger tasksSubmitted = new AtomicInteger();
    ListeningExecutorService trackingExecutor =
        new ForwardingListeningExecutorService() {
          @Override
          protected ListeningExecutorService delegate() {
            return executor;
          }

          @Override
          public void execute(Runnable r) {
            tasksSubmitted.incrementAndGet();
            super.execute(r);
          }
        };

    CelRuntime runtime =
        CelRuntimeFactory.plannerRuntimeBuilder()
            .addFunctionBindings(
                CelFunctionBinding.fromAsync(
                    "asyncSquare_int",
                    Long.class,
                    (Long arg) -> {
                      if (arg == 1L) {
                        task1Started.countDown();
                        return firstFuture;
                      }
                      return secondFuture;
                    }))
            .build();
    Program program = runtime.createProgram(ast);

    // Concurrency limit of 1: task 1 acquires permit, task 2 is queued in gate via comprehension
    // fanout
    CelAsyncEvaluationOptions options =
        CelAsyncEvaluationOptions.builder().setMaxConcurrency(1).build();
    ListenableFuture<Object> evalFuture = program.evalAsync(trackingExecutor, options);

    // Wait until task 1 is running and holding the permit
    assertThat(task1Started.await(5, SECONDS)).isTrue();

    // Verify task 1 was submitted and task 2 is queued in gate
    assertThat(tasksSubmitted.get()).isEqualTo(1);

    // Cancel evaluation before task 1 finishes. cancelAll() should cancel gate and clear pending
    // tasks.
    evalFuture.cancel(/* mayInterruptIfRunning= */ true);

    // Complete task 1 so its callback runs gate.releasePermit().
    firstFuture.set(1L);

    // If gate.cancel() was called, pending tasks were cleared and no further tasks are submitted.
    assertThat(tasksSubmitted.get()).isEqualTo(1);
  }

  @Test
  @SuppressWarnings("Immutable") // Test only
  public void evalAsync_evaluationError_clearsPendingGateTasks() throws Exception {
    CelAbstractSyntaxTree ast =
        CEL_COMPILER.compile("[1, 2].map(n, asyncSquare(n)) + [1 / 0]").getAst();
    SettableFuture<Object> firstFuture = SettableFuture.create();
    SettableFuture<Object> secondFuture = SettableFuture.create();
    CountDownLatch task1Started = new CountDownLatch(1);
    AtomicInteger tasksSubmitted = new AtomicInteger();
    ListeningExecutorService trackingExecutor =
        new ForwardingListeningExecutorService() {
          @Override
          protected ListeningExecutorService delegate() {
            return executor;
          }

          @Override
          public void execute(Runnable r) {
            tasksSubmitted.incrementAndGet();
            super.execute(r);
          }
        };

    CelRuntime runtime =
        CelRuntimeFactory.plannerRuntimeBuilder()
            .addFunctionBindings(
                CelFunctionBinding.fromAsync(
                    "asyncSquare_int",
                    Long.class,
                    (Long arg) -> {
                      if (arg == 1L) {
                        task1Started.countDown();
                        return firstFuture;
                      }
                      return secondFuture;
                    }))
            .build();
    Program program = runtime.createProgram(ast);

    // Concurrency limit of 1: task 1 acquires permit, task 2 is queued in gate
    CelAsyncEvaluationOptions options =
        CelAsyncEvaluationOptions.builder().setMaxConcurrency(1).build();
    ListenableFuture<Object> evalFuture = program.evalAsync(trackingExecutor, options);

    // Wait until task 1 is running and holding the permit (task 2 is queued in gate)
    assertThat(task1Started.await(5, SECONDS)).isTrue();
    assertThat(tasksSubmitted.get()).isEqualTo(1);

    // Evaluation failed due to division by zero: cancelAll() should cancel gate and clear pending
    // tasks.
    ExecutionException exception =
        assertThrows(ExecutionException.class, () -> evalFuture.get(5, SECONDS));
    assertThat(exception).hasCauseThat().isInstanceOf(CelEvaluationException.class);
    assertThat(exception).hasCauseThat().hasMessageThat().contains("/ by zero");

    // Complete task 1 so its callback runs gate.releasePermit().
    firstFuture.set(1L);

    // If gate.cancel() was called in cancelAll(), pending tasks were cleared and task 2 is never
    // submitted.
    assertThat(tasksSubmitted.get()).isEqualTo(1);
  }

  @Test
  @SuppressWarnings("Immutable") // Test only
  public void evalAsync_evaluationCancelled_cancelsScheduledDebounceTimer() throws Exception {
    ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1);
    try {
      CelAbstractSyntaxTree ast = CEL_COMPILER.compile("asyncSquare(1) + asyncSquare(2)").getAst();
      SettableFuture<Object> firstFuture = SettableFuture.create();
      SettableFuture<Object> secondFuture = SettableFuture.create();
      CountDownLatch callsDispatched = new CountDownLatch(2);

      CelRuntime runtime =
          CelRuntimeFactory.plannerRuntimeBuilder()
              .addFunctionBindings(
                  CelFunctionBinding.fromAsync(
                      "asyncSquare_int",
                      Long.class,
                      (Long arg) -> {
                        callsDispatched.countDown();
                        if (arg == 1L) {
                          return firstFuture;
                        }
                        return secondFuture;
                      }))
              .build();
      Program program = runtime.createProgram(ast);

      // Use a drain strategy that does not reevaluate on zero active count (so cancelling
      // secondFuture
      // does not trigger reevaluation / cancelDebounceTimer as a side effect).
      CelAsyncDrainStrategy neverReevaluateDrainStrategy =
          (completedBatch, activeCount) -> CelAsyncDrainAction.waitDuration(Duration.ofMinutes(10));
      CelAsyncEvaluationOptions options =
          CelAsyncEvaluationOptions.builder()
              .setDrainStrategy(neverReevaluateDrainStrategy)
              .setScheduledExecutorService(scheduler)
              .setMaxConcurrency(2)
              .build();
      ListenableFuture<Object> evalFuture = program.evalAsync(executor, options);

      // Wait for both calls to be dispatched
      assertThat(callsDispatched.await(5, SECONDS)).isTrue();

      // Complete first future; with second future in flight, drain strategy schedules debounce
      // timer
      firstFuture.set(1L);

      // Verify timer was scheduled in scheduler queue
      ScheduledFuture<?> scheduledTask = (ScheduledFuture<?>) scheduler.getQueue().peek();
      assertThat(scheduledTask).isNotNull();
      assertThat(scheduledTask.isCancelled()).isFalse();

      // Cancel evaluation. cancelAll() should call coordinator.cancel(), cancelling debounce timer.
      evalFuture.cancel(/* mayInterruptIfRunning= */ true);

      assertThat(scheduledTask.isCancelled()).isTrue();
    } finally {
      scheduler.shutdownNow();
    }
  }

  @Test
  @SuppressWarnings("Immutable") // Test only
  public void
      evalAsync_mapComprehensionConditionAsync_withInFlightIteration1AndConcreteIteration2_waitsForCompletion()
          throws Exception {
    CelExpr comprehensionExpr =
        CelExpr.ofComprehension(
            1L,
            "k",
            "",
            CelExpr.ofMap(
                2L,
                ImmutableList.of(
                    CelExpr.ofMapEntry(
                        3L,
                        CelExpr.ofConstant(4L, CelConstant.ofValue("a")),
                        CelExpr.ofConstant(5L, CelConstant.ofValue(1L)),
                        false),
                    CelExpr.ofMapEntry(
                        6L,
                        CelExpr.ofConstant(7L, CelConstant.ofValue("b")),
                        CelExpr.ofConstant(8L, CelConstant.ofValue(2L)),
                        false))),
            "acc",
            CelExpr.ofConstant(9L, CelConstant.ofValue(0L)),
            CelExpr.ofCall(
                10L, Optional.empty(), "asyncCond", ImmutableList.of(CelExpr.ofIdent(11L, "k"))),
            CelExpr.ofConstant(12L, CelConstant.ofValue(100L)),
            CelExpr.ofIdent(13L, "acc"));
    CelAbstractSyntaxTree ast =
        CelAbstractSyntaxTree.newParsedAst(comprehensionExpr, CelSource.newBuilder().build());

    CountDownLatch callsDispatched = new CountDownLatch(2);
    SettableFuture<Object> condFutureA = SettableFuture.create();

    CelRuntime runtime =
        CelRuntimeFactory.plannerRuntimeBuilder()
            .addFunctionBindings(
                CelFunctionBinding.fromAsync(
                    "asyncCond",
                    String.class,
                    (String arg) -> {
                      callsDispatched.countDown();
                      if (arg.equals("a")) {
                        return condFutureA;
                      }
                      return immediateFuture(true);
                    }))
            .build();
    Program program = runtime.createProgram(ast);

    ListenableFuture<Object> evalFuture = program.evalAsync(executor);

    // Wait until both iterations have been dispatched
    assertThat(callsDispatched.await(5, SECONDS)).isTrue();

    // When condition evaluation in iteration "a" is in-flight, it accumulates as an unknown.
    // Even if iteration "b" condition evaluates immediately to true and step evaluates to concrete
    // 100L,
    // the overall comprehension result must wait for iteration "a" to resolve before proceeding.
    assertThat(evalFuture.isDone()).isFalse();

    condFutureA.set(true);
    Object result = evalFuture.get(5, SECONDS);

    assertThat(result).isEqualTo(100L);
  }

  @Test
  @SuppressWarnings("Immutable") // Test only
  public void
      evalAsync_mapComprehensionLoopStepAsync_withInFlightIteration1AndConcreteIteration2_waitsForCompletion()
          throws Exception {
    CelExpr comprehensionExpr =
        CelExpr.ofComprehension(
            1L,
            "k",
            "",
            CelExpr.ofMap(
                2L,
                ImmutableList.of(
                    CelExpr.ofMapEntry(
                        3L,
                        CelExpr.ofConstant(4L, CelConstant.ofValue("a")),
                        CelExpr.ofConstant(5L, CelConstant.ofValue(1L)),
                        false),
                    CelExpr.ofMapEntry(
                        6L,
                        CelExpr.ofConstant(7L, CelConstant.ofValue("b")),
                        CelExpr.ofConstant(8L, CelConstant.ofValue(2L)),
                        false))),
            "acc",
            CelExpr.ofConstant(9L, CelConstant.ofValue(0L)),
            CelExpr.ofConstant(10L, CelConstant.ofValue(true)),
            CelExpr.ofCall(
                11L, Optional.empty(), "asyncEcho", ImmutableList.of(CelExpr.ofIdent(12L, "k"))),
            CelExpr.ofIdent(13L, "acc"));
    CelAbstractSyntaxTree ast =
        CelAbstractSyntaxTree.newParsedAst(comprehensionExpr, CelSource.newBuilder().build());

    CountDownLatch callsDispatched = new CountDownLatch(2);
    SettableFuture<Object> futureA = SettableFuture.create();

    CelRuntime runtime =
        CelRuntimeFactory.plannerRuntimeBuilder()
            .addFunctionBindings(
                CelFunctionBinding.fromAsync(
                    "asyncEcho",
                    String.class,
                    (String arg) -> {
                      callsDispatched.countDown();
                      if (arg.equals("a")) {
                        return futureA;
                      }
                      return immediateFuture(20L);
                    }))
            .build();
    Program program = runtime.createProgram(ast);

    ListenableFuture<Object> evalFuture = program.evalAsync(executor);

    // Wait until both iterations have been dispatched
    assertThat(callsDispatched.await(5, SECONDS)).isTrue();

    // With futureA in flight from iteration "a", even if iteration "b" evaluates to concrete 20L,
    // comprehension evaluation must wait for the pending async loop step in iteration "a" to
    // complete.
    assertThat(evalFuture.isDone()).isFalse();

    futureA.set(10L);
    Object result = evalFuture.get(5, SECONDS);

    assertThat(result).isEqualTo(20L);
  }

  @Test
  @SuppressWarnings("Immutable") // Test only
  public void
      evalAsync_listComprehensionLoopStepAsync_withInFlightIteration1AndConcreteIteration2_waitsForCompletion()
          throws Exception {
    CelExpr comprehensionExpr =
        CelExpr.ofComprehension(
            1L,
            "x",
            "",
            CelExpr.ofList(
                2L,
                ImmutableList.of(
                    CelExpr.ofConstant(3L, CelConstant.ofValue(1L)),
                    CelExpr.ofConstant(4L, CelConstant.ofValue(2L))),
                ImmutableList.of()),
            "acc",
            CelExpr.ofConstant(5L, CelConstant.ofValue(0L)),
            CelExpr.ofConstant(6L, CelConstant.ofValue(true)),
            CelExpr.ofCall(
                7L, Optional.empty(), "asyncSquare", ImmutableList.of(CelExpr.ofIdent(8L, "x"))),
            CelExpr.ofIdent(9L, "acc"));
    CelAbstractSyntaxTree ast =
        CelAbstractSyntaxTree.newParsedAst(comprehensionExpr, CelSource.newBuilder().build());

    CountDownLatch callsDispatched = new CountDownLatch(2);
    SettableFuture<Object> future1 = SettableFuture.create();

    CelRuntime runtime =
        CelRuntimeFactory.plannerRuntimeBuilder()
            .addFunctionBindings(
                CelFunctionBinding.fromAsync(
                    "asyncSquare",
                    Long.class,
                    (Long arg) -> {
                      callsDispatched.countDown();
                      if (arg == 1L) {
                        return future1;
                      }
                      return immediateFuture(20L);
                    }))
            .build();
    Program program = runtime.createProgram(ast);

    ListenableFuture<Object> evalFuture = program.evalAsync(executor);

    // Wait until both iterations have been dispatched
    assertThat(callsDispatched.await(5, SECONDS)).isTrue();

    // With future1 in flight from iteration 1, even if iteration 2 evaluates to concrete 20L,
    // list comprehension evaluation must wait for the pending async loop step in iteration 1 to
    // complete.
    assertThat(evalFuture.isDone()).isFalse();

    future1.set(10L);
    Object result = evalFuture.get(5, SECONDS);

    assertThat(result).isEqualTo(20L);
  }

  @Test
  public void eval_asyncFunctionInSynchronousMode_throwsCelEvaluationException() throws Exception {
    CelAbstractSyntaxTree ast = CEL_COMPILER.compile("asyncSquare(2)").getAst();
    CelRuntime runtime =
        CelRuntimeFactory.plannerRuntimeBuilder()
            .addFunctionBindings(
                CelFunctionBinding.fromAsync(
                    "asyncSquare_int", Long.class, (Long arg) -> immediateFuture(arg * arg)))
            .build();
    Program program = runtime.createProgram(ast);

    CelEvaluationException e = assertThrows(CelEvaluationException.class, program::eval);
    assertThat(e)
        .hasMessageThat()
        .contains("Async function 'asyncSquare' evaluated in synchronous mode");
  }

  @Test
  @SuppressWarnings("Immutable") // Test only
  public void evalAsync_programOverloads_evaluateSuccessfully() throws Exception {
    CelAbstractSyntaxTree ast = CEL_COMPILER.compile("x + 1").getAst();
    CelRuntime runtime = CelRuntimeFactory.plannerRuntimeBuilder().build();
    Program program = runtime.createProgram(ast);
    CelAsyncEvaluationOptions options = CelAsyncEvaluationOptions.defaultOptions();

    // evalAsync(Map, executor, options)
    assertThat(program.evalAsync(ImmutableMap.of("x", 2L), executor, options).get(5, SECONDS))
        .isEqualTo(3L);

    // evalAsync(CelVariableResolver, executor)
    CelVariableResolver resolver = name -> name.equals("x") ? Optional.of(5L) : Optional.empty();
    assertThat(program.evalAsync(resolver, executor).get(5, SECONDS)).isEqualTo(6L);

    // evalAsync(CelVariableResolver, executor, options)
    assertThat(program.evalAsync(resolver, executor, options).get(5, SECONDS)).isEqualTo(6L);

    // evalAsync(PartialVars, executor, options)
    PartialVars partialVars =
        PartialVars.of(
            name -> name.equals("x") ? Optional.of(10L) : Optional.empty(), ImmutableList.of());
    assertThat(program.evalAsync(partialVars, executor, options).get(5, SECONDS)).isEqualTo(11L);

    // Late-bound function resolver overloads
    CelAbstractSyntaxTree lateAst = CEL_COMPILER.compile("lateBoundAsync(x)").getAst();
    CelRuntime lateRuntime =
        CelRuntimeFactory.plannerRuntimeBuilder().addLateBoundFunctions("lateBoundAsync").build();
    Program lateProgram = lateRuntime.createProgram(lateAst);
    CelFunctionResolver lateResolver =
        new CelFunctionResolver() {
          @Override
          public Optional<CelResolvedOverload> findOverloadMatchingArgs(
              String functionName, Collection<String> overloadIds, Object[] args) {
            if (functionName.equals("lateBoundAsync")) {
              return Optional.of(
                  CelResolvedOverload.of(
                      functionName,
                      "lateBoundAsync_int",
                      CelFunctionBinding.fromAsync(
                              "lateBoundAsync_int",
                              Long.class,
                              (Long arg) -> immediateFuture(arg * 10L))
                          .getDefinition(),
                      /* isStrict= */ true,
                      Long.class));
            }
            return Optional.empty();
          }

          @Override
          public Optional<CelResolvedOverload> findOverloadMatchingArgs(
              String functionName, Object[] args) {
            return findOverloadMatchingArgs(functionName, ImmutableList.of(), args);
          }
        };

    // evalAsync(Map, lateBoundResolver, executor)
    assertThat(
            lateProgram.evalAsync(ImmutableMap.of("x", 2L), lateResolver, executor).get(5, SECONDS))
        .isEqualTo(20L);

    // evalAsync(Map, lateBoundResolver, executor, options)
    assertThat(
            lateProgram
                .evalAsync(ImmutableMap.of("x", 3L), lateResolver, executor, options)
                .get(5, SECONDS))
        .isEqualTo(30L);

    // evalAsync(CelVariableResolver, lateBoundResolver, executor)
    assertThat(lateProgram.evalAsync(resolver, lateResolver, executor).get(5, SECONDS))
        .isEqualTo(50L);

    // evalAsync(CelVariableResolver, lateBoundResolver, executor, options)
    assertThat(lateProgram.evalAsync(resolver, lateResolver, executor, options).get(5, SECONDS))
        .isEqualTo(50L);

    // Protobuf Message overloads
    CelCompiler protoCompiler =
        CelCompilerFactory.standardCelCompilerBuilder()
            .addVar("single_int64", SimpleType.INT)
            .build();
    CelAbstractSyntaxTree protoAst = protoCompiler.compile("single_int64").getAst();
    CelRuntime protoRuntime =
        CelRuntimeFactory.plannerRuntimeBuilder()
            .addMessageTypes(TestAllTypes.getDescriptor())
            .build();
    CelRuntime.Program protoProgram = protoRuntime.createProgram(protoAst);
    TestAllTypes message = TestAllTypes.newBuilder().setSingleInt64(42L).build();

    assertThat(protoProgram.evalAsync(message, executor).get(5, SECONDS)).isEqualTo(42L);
    assertThat(protoProgram.evalAsync(message, executor, options).get(5, SECONDS)).isEqualTo(42L);
  }

  @Test
  public void evalAsync_defaultImplementation_throwsUnsupportedOperationException() {
    Program program =
        new Program() {
          @Override
          public Object eval() {
            return null;
          }

          @Override
          public Object eval(Map<String, ?> mapValue) {
            return null;
          }

          @Override
          public Object eval(
              Map<String, ?> mapValue, CelFunctionResolver lateBoundFunctionResolver) {
            return null;
          }

          @Override
          public Object eval(CelVariableResolver resolver) {
            return null;
          }

          @Override
          public Object eval(
              CelVariableResolver resolver, CelFunctionResolver lateBoundFunctionResolver) {
            return null;
          }

          @Override
          public Object eval(PartialVars partialVars) {
            return null;
          }
        };

    UnsupportedOperationException e =
        assertThrows(UnsupportedOperationException.class, () -> program.evalAsync(executor));
    assertThat(e)
        .hasMessageThat()
        .contains("evalAsync is not supported by this Program implementation.");
  }
}
