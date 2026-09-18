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

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.util.concurrent.Futures.immediateFailedFuture;
import static com.google.common.util.concurrent.Futures.immediateFuture;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static dev.cel.common.CelFunctionDecl.newFunctionDeclaration;
import static dev.cel.common.CelOverloadDecl.newGlobalOverload;
import static dev.cel.common.CelOverloadDecl.newMemberOverload;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.ForwardingListeningExecutorService;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;
import com.google.errorprone.annotations.Immutable;
import javax.annotation.concurrent.ThreadSafe;
import com.google.protobuf.Any;
import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.CelOptions;
import dev.cel.common.exceptions.CelOverloadNotFoundException;
import dev.cel.common.types.ListType;
import dev.cel.common.types.MapType;
import dev.cel.common.types.OpaqueType;
import dev.cel.common.types.SimpleType;
import dev.cel.compiler.CelCompiler;
import dev.cel.compiler.CelCompilerFactory;
import dev.cel.expr.conformance.proto3.TestAllTypes;
import dev.cel.parser.CelStandardMacro;
import dev.cel.runtime.AccumulatedUnknowns;
import dev.cel.runtime.CelAsyncCall;
import dev.cel.runtime.CelAsyncDrainAction;
import dev.cel.runtime.CelAsyncDrainStrategy;
import dev.cel.runtime.CelAsyncEvaluationOptions;
import dev.cel.runtime.CelAsyncObserver;
import dev.cel.runtime.CelAttribute;
import dev.cel.runtime.CelAttributePattern;
import dev.cel.runtime.CelEvaluationException;
import dev.cel.runtime.CelFunctionBinding;
import dev.cel.runtime.CelLateFunctionBindings;
import dev.cel.runtime.CelRuntime;
import dev.cel.runtime.CelRuntime.Program;
import dev.cel.runtime.CelRuntimeBuilder;
import dev.cel.runtime.CelRuntimeFactory;
import dev.cel.runtime.CelUnknownSet;
import dev.cel.runtime.CelVariableResolver;
import dev.cel.runtime.PartialVars;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(TestParameterInjector.class)
public final class ProgramPlannerAsyncTest {

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
                  "asyncSum3",
                  newGlobalOverload(
                      "asyncSum3_int",
                      SimpleType.INT,
                      SimpleType.INT,
                      SimpleType.INT,
                      SimpleType.INT)),
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
                  "asyncListSize",
                  newGlobalOverload(
                      "asyncListSize_list", SimpleType.INT, ListType.create(SimpleType.INT))),
              newFunctionDeclaration(
                  "asyncMapSize",
                  newGlobalOverload(
                      "asyncMapSize_map",
                      SimpleType.INT,
                      MapType.create(SimpleType.STRING, SimpleType.INT))),
              newFunctionDeclaration(
                  "lateAdd",
                  newGlobalOverload(
                      "lateAdd_int_int", SimpleType.INT, SimpleType.INT, SimpleType.INT)))
          .build();

  private static final CelFunctionBinding ASYNC_SQUARE_INT =
      CelFunctionBinding.fromAsync(
          "asyncSquare_int", Long.class, (Long arg) -> immediateFuture(arg * arg));

  private static final CelFunctionBinding ASYNC_ADD_INT =
      CelFunctionBinding.fromAsync(
          "asyncAdd_int_int", Long.class, Long.class, (a, b) -> immediateFuture(a + b));

  private final ListeningExecutorService executor =
      MoreExecutors.listeningDecorator(Executors.newFixedThreadPool(4));

  @After
  public void tearDown() {
    executor.shutdownNow();
  }

  private enum BasicAsyncCase {
    UNARY("asyncSquare(4) + 1", 17L),
    BINARY("asyncAdd(10, 20) * 2", 60L),
    NESTED("asyncSquare(asyncSquare(3))", 81L);

    private final String expression;
    private final long expectedResult;

    BasicAsyncCase(String expression, long expectedResult) {
      this.expression = expression;
      this.expectedResult = expectedResult;
    }
  }

  @Test
  public void evalAsync_basicFunctions_evaluatesSuccessfully(
      @TestParameter BasicAsyncCase testCase, @TestParameter boolean parsedOnly) throws Exception {
    CelAbstractSyntaxTree ast =
        parsedOnly
            ? CEL_COMPILER.parse(testCase.expression).getAst()
            : CEL_COMPILER.compile(testCase.expression).getAst();
    CelRuntime runtime =
        plannerRuntimeBuilder()
            .addFunctionBindings(
                CelFunctionBinding.fromAsync(
                    parsedOnly ? "asyncSquare" : "asyncSquare_int",
                    Long.class,
                    (Long arg) -> immediateFuture(arg * arg)),
                CelFunctionBinding.fromAsync(
                    parsedOnly ? "asyncAdd" : "asyncAdd_int_int",
                    Long.class,
                    Long.class,
                    (Long a, Long b) -> immediateFuture(a + b)))
            .build();
    Program program = runtime.createProgram(ast);

    Object result = program.evalAsync().get(5, SECONDS);

    assertThat(result).isEqualTo(testCase.expectedResult);
  }

  @Test
  public void evalAsync_varargsFunction_evaluatesSuccessfully() throws Exception {
    Program program =
        createProgram(
            "asyncSum3(1, 2, 3)",
            CelFunctionBinding.fromAsync(
                "asyncSum3_int",
                ImmutableList.of(Long.class, Long.class, Long.class),
                (Object[] args) ->
                    immediateFuture((Long) args[0] + (Long) args[1] + (Long) args[2])));

    Object result = program.evalAsync().get(5, SECONDS);

    assertThat(result).isEqualTo(6L);
  }

  @Test
  public void evalAsync_syncProgram_evaluatesSuccessfully() throws Exception {
    Program program = createProgram("1 + 2 * 3");

    Object result = program.evalAsync().get(5, SECONDS);

    assertThat(result).isEqualTo(7L);
  }

  private enum ActivationOverloadCase {
    MAP,
    MAP_WITH_LATE_BOUND_RESOLVER,
    VARIABLE_RESOLVER,
    VARIABLE_RESOLVER_WITH_LATE_BOUND_RESOLVER,
    PARTIAL_VARS,
    PROTO_MESSAGE
  }

  @Test
  public void evalAsync_activationOverloads_evaluatesCorrectly(
      @TestParameter ActivationOverloadCase overloadCase) throws Exception {
    if (overloadCase == ActivationOverloadCase.PROTO_MESSAGE) {
      CelCompiler protoCompiler =
          CelCompilerFactory.standardCelCompilerBuilder()
              .addVar("single_int64", SimpleType.INT)
              .addFunctionDeclarations(
                  newFunctionDeclaration(
                      "asyncSquare",
                      newGlobalOverload("asyncSquare_int", SimpleType.INT, SimpleType.INT)))
              .build();
      CelAbstractSyntaxTree protoAst =
          protoCompiler.compile("asyncSquare(single_int64) - 1").getAst();
      Program protoProgram =
          plannerRuntimeBuilder()
              .addMessageTypes(TestAllTypes.getDescriptor())
              .addFunctionBindings(ASYNC_SQUARE_INT)
              .build()
              .createProgram(protoAst);
      TestAllTypes message = TestAllTypes.newBuilder().setSingleInt64(6L).build();

      Object protoResult = protoProgram.evalAsync(message).get(5, SECONDS);

      assertThat(protoResult).isEqualTo(35L);
      return;
    }
    boolean useLateBound =
        overloadCase == ActivationOverloadCase.MAP_WITH_LATE_BOUND_RESOLVER
            || overloadCase == ActivationOverloadCase.VARIABLE_RESOLVER_WITH_LATE_BOUND_RESOLVER;
    CelAbstractSyntaxTree ast =
        CEL_COMPILER
            .compile(useLateBound ? "asyncSquare(x) + lateAdd(y, 0)" : "asyncSquare(x) + y")
            .getAst();
    CelRuntimeBuilder runtimeBuilder =
        plannerRuntimeBuilder().addFunctionBindings(ASYNC_SQUARE_INT);
    if (useLateBound) {
      runtimeBuilder.addLateBoundFunctions("lateAdd");
    }
    Program program = runtimeBuilder.build().createProgram(ast);
    ImmutableMap<String, Object> mapActivation = ImmutableMap.of("x", 5L, "y", 10L);
    CelVariableResolver varResolver = (name) -> Optional.ofNullable(mapActivation.get(name));
    CelLateFunctionBindings lateBoundResolver =
        CelLateFunctionBindings.from(
            CelFunctionBinding.from("lateAdd_int_int", Long.class, Long.class, Long::sum));

    ListenableFuture<Object> future;
    switch (overloadCase) {
      case MAP:
        future = program.evalAsync(mapActivation);
        break;
      case MAP_WITH_LATE_BOUND_RESOLVER:
        future = program.evalAsync(mapActivation, lateBoundResolver);
        break;
      case VARIABLE_RESOLVER:
        future = program.evalAsync(varResolver);
        break;
      case VARIABLE_RESOLVER_WITH_LATE_BOUND_RESOLVER:
        future = program.evalAsync(varResolver, lateBoundResolver);
        break;
      case PARTIAL_VARS:
        future =
            program.evalAsync(
                PartialVars.of(varResolver, CelAttributePattern.fromQualifiedIdentifier("unused")));
        break;
      default:
        throw new AssertionError(overloadCase);
    }

    assertThat(future.get(5, SECONDS)).isEqualTo(35L);
  }

  @Test
  public void evalAsync_nullProtoMessage_throwsNullPointerException() throws Exception {
    Program program = createProgram("1");

    assertThrows(NullPointerException.class, () -> program.evalAsync((TestAllTypes) null));
  }

  @Test
  @SuppressWarnings("Immutable") // Test only
  public void evalAsync_conditionalBranching_onlyEvaluatesTakenBranch() throws Exception {
    AtomicInteger untakenBranchCalls = new AtomicInteger();
    Program program =
        createProgram(
            "asyncIsEven(4) ? asyncSquare(3) : asyncFail(1)",
            CelFunctionBinding.fromAsync(
                "asyncIsEven_int", Long.class, (Long arg) -> immediateFuture(arg % 2 == 0)),
            ASYNC_SQUARE_INT,
            CelFunctionBinding.fromAsync(
                "asyncFail_int",
                Long.class,
                (Long arg) -> {
                  untakenBranchCalls.incrementAndGet();
                  return immediateFailedFuture(new AssertionError("Should not be called"));
                }));

    Object result = program.evalAsync().get(5, SECONDS);

    assertThat(result).isEqualTo(9L);
    assertThat(untakenBranchCalls.get()).isEqualTo(0);
  }

  private enum ShortCircuitOperator {
    OR("asyncIsEven(2) || (asyncSquare(10) == 100)", true),
    AND("asyncIsEven(3) && (asyncSquare(10) == 100)", false);

    private final String expression;
    private final boolean expectedResult;

    ShortCircuitOperator(String expression, boolean expectedResult) {
      this.expression = expression;
      this.expectedResult = expectedResult;
    }
  }

  @Test
  @SuppressWarnings("Immutable") // Test only
  public void evalAsync_shortCircuiting_cancelsUnneededSibling(
      @TestParameter ShortCircuitOperator op) throws Exception {
    SettableFuture<Object> firstBranchFuture = SettableFuture.create();
    SettableFuture<Object> slowSibling = SettableFuture.create();
    CountDownLatch siblingStarted = new CountDownLatch(1);
    CountDownLatch siblingCancelledLatch = new CountDownLatch(1);
    slowSibling.addListener(
        () -> {
          if (slowSibling.isCancelled()) {
            siblingCancelledLatch.countDown();
          }
        },
        directExecutor());
    CelAsyncEvaluationOptions options =
        CelAsyncEvaluationOptions.builder()
            .setDrainStrategy(CelAsyncDrainStrategy.drainNone())
            .build();
    Program program =
        createProgram(
            op.expression,
            options,
            CelFunctionBinding.fromAsync(
                "asyncIsEven_int", Long.class, (Long arg) -> firstBranchFuture),
            CelFunctionBinding.fromAsync(
                "asyncSquare_int",
                Long.class,
                (Long arg) -> {
                  siblingStarted.countDown();
                  return slowSibling;
                }));

    ListenableFuture<Object> evalFuture = program.evalAsync();
    boolean started = siblingStarted.await(5, SECONDS);
    firstBranchFuture.set(op.expectedResult);

    assertThat(started).isTrue();
    assertThat(evalFuture.get(5, SECONDS)).isEqualTo(op.expectedResult);
    assertThat(siblingCancelledLatch.await(5, SECONDS)).isTrue();
  }

  @Test
  @SuppressWarnings("Immutable") // Test only
  public void evalAsync_exhaustiveEval_waitsForAllBranchesBeforeCompleting(
      @TestParameter({
            "asyncIsEven(2) || (asyncSquare(3) == 9)",
            "true ? asyncSquare(3) : asyncSquare(4)"
          })
          String expression)
      throws Exception {
    AtomicInteger squareCalls = new AtomicInteger();
    CelAbstractSyntaxTree ast = CEL_COMPILER.compile(expression).getAst();
    CelRuntime runtime =
        plannerRuntimeBuilder()
            .setOptions(
                CelOptions.current()
                    .enableTimestampEpoch(true)
                    .enableHeterogeneousNumericComparisons(true)
                    .enableShortCircuiting(false)
                    .build())
            .addFunctionBindings(
                CelFunctionBinding.fromAsync(
                    "asyncIsEven_int", Long.class, (Long arg) -> immediateFuture(arg % 2 == 0)),
                CelFunctionBinding.fromAsync(
                    "asyncSquare_int",
                    Long.class,
                    (Long arg) -> {
                      squareCalls.incrementAndGet();
                      return immediateFuture(arg * arg);
                    }))
            .build();
    Program program = runtime.createProgram(ast);

    Object result = program.evalAsync().get(5, SECONDS);

    assertThat(result).isIn(ImmutableList.of(true, 9L));
    assertThat(squareCalls.get()).isAtLeast(1);
  }

  @Test
  public void syncEvalOrTrace_withAsyncFunction_throwsCelEvaluationException(
      @TestParameter boolean useTrace) throws Exception {
    Program program = createProgram("asyncSquare(4)", ASYNC_SQUARE_INT);

    CelEvaluationException ex =
        assertThrows(
            CelEvaluationException.class,
            () -> {
              if (useTrace) {
                program.trace((expr, res) -> {});
              } else {
                program.eval();
              }
            });

    assertThat(ex).hasMessageThat().contains("Async function 'asyncSquare'");
  }

  @Test
  public void evalAsync_noExecutorProvided_throwsIllegalStateException() throws Exception {
    CelAbstractSyntaxTree ast = CEL_COMPILER.compile("1 + 1").getAst();
    CelRuntime runtime = CelRuntimeFactory.plannerRuntimeBuilder().build();
    Program program = runtime.createProgram(ast);

    IllegalStateException e = assertThrows(IllegalStateException.class, program::evalAsync);

    assertThat(e).hasMessageThat().contains("No async executor was configured");
  }

  @Test
  public void evalAsync_maxIterations_enforcesLimit(@TestParameter boolean allowEnoughIterations)
      throws Exception {
    CelAsyncEvaluationOptions options =
        CelAsyncEvaluationOptions.builder().setMaxIterations(allowEnoughIterations ? 2 : 1).build();
    Program program = createProgram("asyncSquare(5)", options, ASYNC_SQUARE_INT);

    ListenableFuture<Object> future = program.evalAsync();

    if (allowEnoughIterations) {
      assertThat(future.get(5, SECONDS)).isEqualTo(25L);
    } else {
      ExecutionException e = assertThrows(ExecutionException.class, future::get);
      assertThat(e).hasCauseThat().isInstanceOf(CelEvaluationException.class);
      assertThat(e)
          .hasCauseThat()
          .hasMessageThat()
          .contains("Exceeded maximum async evaluation iterations: 1");
    }
  }

  private enum ObserverScenario {
    SUCCESS("asyncSquare(3)", 3L, null),
    ASYNC_FAILURE("asyncFail(1)", 1L, "simulated error"),
    SYNC_THROW("asyncSyncThrow(2)", 2L, "sync throw");

    private final String expression;
    private final long expectedArg;
    private final @Nullable String expectedErrorSubstring;

    ObserverScenario(String expression, long expectedArg, @Nullable String expectedErrorSubstring) {
      this.expression = expression;
      this.expectedArg = expectedArg;
      this.expectedErrorSubstring = expectedErrorSubstring;
    }
  }

  @Test
  public void evalAsync_withObserver_recordsLifecycleEvents(
      @TestParameter ObserverScenario scenario) throws Exception {
    RecordingObserver observer = new RecordingObserver();
    Program program =
        createProgram(
            scenario.expression,
            CelAsyncEvaluationOptions.builder().setObserver(observer).build(),
            ASYNC_SQUARE_INT,
            CelFunctionBinding.fromAsync(
                "asyncFail_int",
                Long.class,
                (Long arg) ->
                    immediateFailedFuture(new IllegalArgumentException("simulated error"))),
            CelFunctionBinding.fromAsync(
                "asyncSyncThrow_int",
                Long.class,
                (Long arg) -> {
                  throw new IllegalStateException("sync throw");
                }));

    ListenableFuture<Object> future = program.evalAsync();

    if (scenario.expectedErrorSubstring == null) {
      assertThat(future.get(5, SECONDS)).isEqualTo(9L);
      assertThat(observer.startedFunctionName()).hasValue("asyncSquare");
      assertThat(observer.startedArgs()).hasValue(ImmutableList.of(scenario.expectedArg));
      assertThat(observer.finishedFunctionName()).hasValue("asyncSquare");
      assertThat(observer.finishedResult()).hasValue(9L);
      assertThat(observer.finishedError()).isEmpty();
    } else {
      assertThrows(ExecutionException.class, () -> future.get(5, SECONDS));
      assertThat(observer.startedArgs()).hasValue(ImmutableList.of(scenario.expectedArg));
      assertThat(observer.finishedResult()).isEmpty();
      assertThat(observer.finishedError().map(Throwable::getMessage))
          .hasValue(scenario.expectedErrorSubstring);
    }
  }

  @Test
  @SuppressWarnings("Immutable") // Test only
  public void evalAsync_observerThrowsInCallbacks_doesNotLeakPermits(
      @TestParameter boolean throwInStart) throws Exception {
    CelAsyncObserver observer =
        new CelAsyncObserver() {
          private final AtomicBoolean first = new AtomicBoolean(true);

          @Override
          public void onCallStarted(CelAsyncCall call, ImmutableList<Object> args) {
            if (throwInStart && first.compareAndSet(true, false)) {
              throw new RuntimeException("observer start failure");
            }
          }

          @Override
          public void onCallFinished(
              CelAsyncCall call, @Nullable Object result, @Nullable Throwable error) {
            if (!throwInStart) {
              throw new AssertionError("observer finish error");
            }
          }
        };
    Program program =
        createProgram(
            "asyncSquare(2) + asyncSquare(3)",
            CelAsyncEvaluationOptions.builder().setMaxConcurrency(1).setObserver(observer).build(),
            ASYNC_SQUARE_INT);

    ListenableFuture<Object> future = program.evalAsync();

    if (throwInStart) {
      ExecutionException startEx =
          assertThrows(ExecutionException.class, () -> future.get(5, SECONDS));
      assertThat(startEx).hasCauseThat().hasMessageThat().contains("observer start failure");
    } else {
      assertThat(future.get(5, SECONDS)).isEqualTo(13L);
    }
  }

  @Test
  public void evalAsync_applyAsyncReturnsNull_failsWithDescriptiveException() throws Exception {
    Program program =
        createProgram(
            "asyncNullReturn(1)",
            CelFunctionBinding.fromAsync("asyncNullReturn_int", Long.class, (Long arg) -> null));

    ExecutionException e =
        assertThrows(ExecutionException.class, () -> program.evalAsync().get(5, SECONDS));

    assertThat(e).hasCauseThat().isInstanceOf(CelEvaluationException.class);
    assertThat(e).hasCauseThat().hasMessageThat().contains("returned a null ListenableFuture");
  }

  @Test
  @SuppressWarnings("Immutable") // Test only
  public void evalAsync_argumentEvaluatesToErrorValue_propagatesWithoutInvokingAsyncFunction()
      throws Exception {
    AtomicInteger asyncCalls = new AtomicInteger();
    Program program =
        createProgram(
            "asyncIsEven(true && (1 / 0 == 0) ? 1 : 2) == true",
            CelFunctionBinding.fromAsync(
                "asyncIsEven_int",
                Long.class,
                (Long arg) -> {
                  asyncCalls.incrementAndGet();
                  return immediateFuture(arg % 2 == 0);
                }));

    ExecutionException e =
        assertThrows(ExecutionException.class, () -> program.evalAsync().get(5, SECONDS));

    assertThat(e).hasCauseThat().isInstanceOf(CelEvaluationException.class);
    assertThat(e).hasCauseThat().hasMessageThat().contains("/ by zero");
    assertThat(asyncCalls.get()).isEqualTo(0);
  }

  @Test
  public void evalAsync_parsedOnlyTypeOrArityMismatch_throwsOverloadNotFoundException(
      @TestParameter({"asyncSquare('bad')", "asyncSquare(4, 'extra')"}) String expression)
      throws Exception {
    CelAbstractSyntaxTree ast = CEL_COMPILER.parse(expression).getAst();
    Program program =
        plannerRuntimeBuilder()
            .addFunctionBindings(
                CelFunctionBinding.fromAsync(
                    "asyncSquare", Long.class, (Long arg) -> immediateFuture(arg * arg)))
            .build()
            .createProgram(ast);

    ExecutionException e =
        assertThrows(ExecutionException.class, () -> program.evalAsync().get(5, SECONDS));

    assertThat(e).hasCauseThat().isInstanceOf(CelEvaluationException.class);
    assertThat(e).hasCauseThat().hasCauseThat().isInstanceOf(CelOverloadNotFoundException.class);
  }

  private enum SiblingFailureMode {
    ASYNC_FAILED_FUTURE,
    SYNC_RUNTIME_EXCEPTION,
    PASS2_DIVISION_BY_ZERO,
    DRAIN_STRATEGY_EXCEPTION
  }

  @Test
  @SuppressWarnings("Immutable") // Test only
  public void evalAsync_failureModes_cancelsInFlightSiblingsAndPropagatesCelEvaluationException(
      @TestParameter SiblingFailureMode failureMode) throws Exception {
    SettableFuture<Object> inFlightSibling = SettableFuture.create();
    CountDownLatch siblingStarted = new CountDownLatch(1);
    CountDownLatch siblingCancelledLatch = new CountDownLatch(1);
    inFlightSibling.addListener(
        () -> {
          if (inFlightSibling.isCancelled()) {
            siblingCancelledLatch.countDown();
          }
        },
        directExecutor());
    CelAsyncDrainStrategy drainStrategy =
        failureMode == SiblingFailureMode.DRAIN_STRATEGY_EXCEPTION
            ? (completedBatch, activeCount) -> {
              throw new IllegalStateException("custom drain strategy failure");
            }
            : CelAsyncDrainStrategy.drainNone();
    CelAsyncEvaluationOptions options =
        CelAsyncEvaluationOptions.builder().setDrainStrategy(drainStrategy).build();
    String expr =
        failureMode == SiblingFailureMode.PASS2_DIVISION_BY_ZERO
            ? "asyncSquare(10) + (1 / asyncSquare(0))"
            : "asyncSquare(10) + asyncFail(5)";
    Program program =
        createProgram(
            expr,
            options,
            CelFunctionBinding.fromAsync(
                "asyncSquare_int",
                Long.class,
                (Long arg) -> {
                  if (arg == 10L) {
                    siblingStarted.countDown();
                    return inFlightSibling;
                  }
                  try {
                    siblingStarted.await(5, SECONDS);
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                  }
                  return immediateFuture(0L);
                }),
            CelFunctionBinding.fromAsync(
                "asyncFail_int",
                Long.class,
                (Long arg) -> {
                  try {
                    siblingStarted.await(5, SECONDS);
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                  }
                  if (failureMode == SiblingFailureMode.SYNC_RUNTIME_EXCEPTION) {
                    throw new IllegalStateException("synchronous crash");
                  }
                  if (failureMode == SiblingFailureMode.DRAIN_STRATEGY_EXCEPTION) {
                    return immediateFuture(1L);
                  }
                  return immediateFailedFuture(
                      new IllegalArgumentException("simulated async error"));
                }));

    ExecutionException e =
        assertThrows(ExecutionException.class, () -> program.evalAsync().get(5, SECONDS));

    assertThat(e).hasCauseThat().isInstanceOf(CelEvaluationException.class);
    assertThat(siblingCancelledLatch.await(5, SECONDS)).isTrue();
  }

  @Test
  @SuppressWarnings("Immutable") // Test only
  public void evalAsync_futureCancellation_cancelsInFlightGateAndDebounceTimer() throws Exception {
    ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1);
    try {
      SettableFuture<Object> task1Future = SettableFuture.create();
      SettableFuture<Object> task2Future = SettableFuture.create();
      SettableFuture<Object> task3Future = SettableFuture.create();
      CountDownLatch task2Started = new CountDownLatch(1);
      CountDownLatch task2Cancelled = new CountDownLatch(1);
      AtomicInteger tasksSubmitted = new AtomicInteger();
      AtomicInteger task4Executed = new AtomicInteger();
      ListeningExecutorService trackingExecutor =
          new ForwardingListeningExecutorService() {
            @Override
            protected ListeningExecutorService delegate() {
              return executor;
            }

            @Override
            public void execute(Runnable command) {
              tasksSubmitted.incrementAndGet();
              super.execute(command);
            }
          };
      task2Future.addListener(
          () -> {
            if (task2Future.isCancelled()) {
              task2Cancelled.countDown();
            }
          },
          directExecutor());
      CelAsyncDrainStrategy longDebounceStrategy =
          (completedBatch, activeCount) -> CelAsyncDrainAction.waitDuration(Duration.ofMinutes(10));
      CelAsyncEvaluationOptions options =
          CelAsyncEvaluationOptions.builder()
              .setDrainStrategy(longDebounceStrategy)
              .setScheduledExecutorService(scheduler)
              .setMaxConcurrency(2)
              .build();
      CelAbstractSyntaxTree ast =
          CEL_COMPILER
              .compile("asyncSquare(1) + asyncSquare(2) + asyncSquare(3) + asyncSquare(4)")
              .getAst();
      Program program =
          plannerRuntimeBuilder()
              .setAsyncExecutor(trackingExecutor)
              .setAsyncEvaluationOptions(options)
              .addFunctionBindings(
                  CelFunctionBinding.fromAsync(
                      "asyncSquare_int",
                      Long.class,
                      (Long arg) -> {
                        if (arg == 1L) {
                          return task1Future;
                        }
                        if (arg == 2L) {
                          task2Started.countDown();
                          return task2Future;
                        }
                        if (arg == 3L) {
                          return task3Future;
                        }
                        task4Executed.incrementAndGet();
                        return immediateFuture(arg * arg);
                      }))
              .build()
              .createProgram(ast);

      ListenableFuture<Object> evalFuture = program.evalAsync();
      boolean started = task2Started.await(5, SECONDS);
      task1Future.set(1L);
      ScheduledFuture<?> scheduledTask = null;
      long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
      while (System.nanoTime() < deadline) {
        scheduledTask = (ScheduledFuture<?>) scheduler.getQueue().peek();
        if (scheduledTask != null) {
          break;
        }
        Thread.sleep(5);
      }
      evalFuture.cancel(/* mayInterruptIfRunning= */ true);
      task3Future.set(9L);

      assertThat(started).isTrue();
      assertThat(scheduledTask).isNotNull();
      assertThat(evalFuture.isCancelled()).isTrue();
      assertThat(scheduledTask.isCancelled()).isTrue();
      assertThat(task2Cancelled.await(5, SECONDS)).isTrue();
      assertThat(task4Executed.get()).isEqualTo(0);
      assertThat(tasksSubmitted.get()).isEqualTo(2);
    } finally {
      scheduler.shutdownNow();
    }
  }

  @Test
  @SuppressWarnings("Immutable") // Test only
  public void evalAsync_evalExceptionInPass2_doesNotExecuteRemainingCallsUnderMaxConcurrency()
      throws Exception {
    SettableFuture<Object> call1Future = SettableFuture.create();
    CountDownLatch call1Started = new CountDownLatch(1);
    AtomicInteger remainingCallsExecuted = new AtomicInteger();
    CelAsyncEvaluationOptions options =
        CelAsyncEvaluationOptions.builder()
            .setMaxConcurrency(1)
            .setDrainStrategy(CelAsyncDrainStrategy.drainNone())
            .build();
    Program program =
        createProgram(
            "(1 / asyncSquare(1)) + asyncSquare(2) + asyncSquare(3)",
            options,
            CelFunctionBinding.fromAsync(
                "asyncSquare_int",
                Long.class,
                (Long arg) -> {
                  if (arg == 1L) {
                    call1Started.countDown();
                    return call1Future;
                  }
                  remainingCallsExecuted.incrementAndGet();
                  return immediateFuture(arg * arg);
                }));

    ListenableFuture<Object> future = program.evalAsync();
    boolean started = call1Started.await(5, SECONDS);
    call1Future.set(0L);

    assertThat(started).isTrue();
    ExecutionException e = assertThrows(ExecutionException.class, () -> future.get(5, SECONDS));
    assertThat(e).hasCauseThat().isInstanceOf(CelEvaluationException.class);
    assertThat(e).hasCauseThat().hasMessageThat().contains("/ by zero");
    assertThat(remainingCallsExecuted.get()).isEqualTo(0);
  }

  private enum MemoizationCase {
    INT,
    DOUBLE_NAN,
    DOUBLE_NEGATIVE_ZERO
  }

  @Test
  @SuppressWarnings("Immutable") // Test only
  public void evalAsync_memoizationAndKeyNormalization_deduplicatesAcrossPasses(
      @TestParameter MemoizationCase memoizationCase) throws Exception {
    AtomicInteger callCounter = new AtomicInteger();
    Program program =
        createProgram(
            memoizationCase == MemoizationCase.INT
                ? "asyncSquare(3) + asyncSquare(asyncSquare(4))"
                : "asyncSquare(dx) + asyncSquare(asyncSquare(2.0))",
            CelFunctionBinding.fromAsync(
                "asyncSquare_int",
                Long.class,
                (Long arg) -> {
                  callCounter.incrementAndGet();
                  return immediateFuture(arg * arg);
                }),
            CelFunctionBinding.fromAsync(
                "asyncSquare_double",
                Double.class,
                (Double arg) -> {
                  if (memoizationCase == MemoizationCase.DOUBLE_NAN && Double.isNaN(arg)) {
                    callCounter.incrementAndGet();
                  } else if (memoizationCase == MemoizationCase.DOUBLE_NEGATIVE_ZERO
                      && arg == 0.0d) {
                    callCounter.incrementAndGet();
                  }
                  return immediateFuture(arg * arg);
                }));
    ImmutableMap<String, Object> activation =
        memoizationCase == MemoizationCase.DOUBLE_NAN
            ? ImmutableMap.of("dx", Double.NaN)
            : ImmutableMap.of("dx", -0.0d);

    Object result = program.evalAsync(activation).get(5, SECONDS);

    if (memoizationCase == MemoizationCase.INT) {
      assertThat(result).isEqualTo(265L);
      assertThat(callCounter.get()).isEqualTo(3);
    } else if (memoizationCase == MemoizationCase.DOUBLE_NAN) {
      assertThat((Double) result).isNaN();
      assertThat(callCounter.get()).isEqualTo(1);
    } else {
      assertThat(result).isEqualTo(16.0d);
      assertThat(callCounter.get()).isEqualTo(1);
    }
  }

  @Test
  @SuppressWarnings("Immutable") // Test only
  public void evalAsync_protoDifferencerEquality_deduplicatesEquivalentProtoArgs()
      throws Exception {
    AtomicInteger protoCalls = new AtomicInteger();
    AtomicInteger msgLookups = new AtomicInteger();
    CelCompiler protoCompiler =
        CelCompilerFactory.standardCelCompilerBuilder()
            .addMessageTypes(TestAllTypes.getDescriptor())
            .addVar("msg", OpaqueType.create("cel.expr.conformance.proto3.TestAllTypes"))
            .addFunctionDeclarations(
                newFunctionDeclaration(
                    "asyncProtoVal",
                    newGlobalOverload(
                        "asyncProtoVal_msg",
                        SimpleType.INT,
                        OpaqueType.create("cel.expr.conformance.proto3.TestAllTypes"))),
                newFunctionDeclaration(
                    "asyncSquare",
                    newGlobalOverload("asyncSquare_int", SimpleType.INT, SimpleType.INT)))
            .build();
    CelAbstractSyntaxTree ast =
        protoCompiler.compile("asyncProtoVal(msg) + asyncSquare(asyncSquare(2))").getAst();
    Program program =
        plannerRuntimeBuilder()
            .setOptions(
                CelOptions.current()
                    .enableHeterogeneousNumericComparisons(true)
                    .enableProtoDifferencerEquality(true)
                    .build())
            .addMessageTypes(TestAllTypes.getDescriptor())
            .addFunctionBindings(
                ASYNC_SQUARE_INT,
                CelFunctionBinding.fromAsync(
                    "asyncProtoVal_msg",
                    TestAllTypes.class,
                    (TestAllTypes msg) -> {
                      protoCalls.incrementAndGet();
                      return immediateFuture(26L);
                    }))
            .build()
            .createProgram(ast);
    TestAllTypes inner1 = TestAllTypes.newBuilder().setSingleInt32(1).setSingleInt64(2L).build();
    TestAllTypes inner2 = TestAllTypes.newBuilder().setSingleInt64(2L).setSingleInt32(1).build();
    TestAllTypes msg1 =
        TestAllTypes.newBuilder()
            .setSingleAny(
                Any.newBuilder()
                    .setTypeUrl("type.googleapis.com/cel.expr.conformance.proto3.TestAllTypes")
                    .setValue(inner1.toByteString()))
            .build();
    TestAllTypes msg2 =
        TestAllTypes.newBuilder()
            .setSingleAny(
                Any.newBuilder()
                    .setTypeUrl("type.googleapis.com/cel.expr.conformance.proto3.TestAllTypes")
                    .setValue(inner2.toByteString().concat(inner1.toByteString())))
            .build();
    CelVariableResolver resolver =
        (String name) ->
            name.equals("msg")
                ? Optional.of(msgLookups.getAndIncrement() == 0 ? msg1 : msg2)
                : Optional.empty();

    Object result = program.evalAsync(resolver).get(5, SECONDS);

    assertThat(result).isEqualTo(42L);
    assertThat(protoCalls.get()).isEqualTo(1);
  }

  @Test
  @SuppressWarnings({"Immutable", "rawtypes"}) // Test only
  public void evalAsync_containerCacheKeys_memoizesAcrossPasses() throws Exception {
    AtomicInteger listCalls = new AtomicInteger();
    AtomicInteger mapCalls = new AtomicInteger();
    Program program =
        createProgram(
            "asyncListSize([1, 2]) + asyncMapSize({'a': 1}) + asyncSquare(asyncSquare(3))",
            ASYNC_SQUARE_INT,
            CelFunctionBinding.fromAsync(
                "asyncListSize_list",
                List.class,
                (List arg) -> {
                  listCalls.incrementAndGet();
                  return immediateFuture((long) arg.size());
                }),
            CelFunctionBinding.fromAsync(
                "asyncMapSize_map",
                Map.class,
                (Map arg) -> {
                  mapCalls.incrementAndGet();
                  return immediateFuture((long) arg.size());
                }));

    Object result = program.evalAsync().get(5, SECONDS);

    assertThat(result).isEqualTo(84L);
    assertThat(listCalls.get()).isEqualTo(1);
    assertThat(mapCalls.get()).isEqualTo(1);
  }

  @Test
  public void evalAsync_memberFunction_evaluatesSuccessfully() throws Exception {
    CelCompiler compiler =
        CelCompilerFactory.standardCelCompilerBuilder()
            .addVar("x", SimpleType.INT)
            .addFunctionDeclarations(
                newFunctionDeclaration(
                    "asyncMemberSquare",
                    newMemberOverload("int_asyncMemberSquare", SimpleType.INT, SimpleType.INT)))
            .build();
    Program memberProgram =
        plannerRuntimeBuilder()
            .addFunctionBindings(
                CelFunctionBinding.fromAsync(
                    "int_asyncMemberSquare", Long.class, (Long arg) -> immediateFuture(arg * arg)))
            .build()
            .createProgram(compiler.compile("x.asyncMemberSquare()").getAst());

    Object result = memberProgram.evalAsync(ImmutableMap.of("x", 7L)).get(5, SECONDS);

    assertThat(result).isEqualTo(49L);
  }

  @Test
  public void evalAsync_stalledEvaluation_throwsCelEvaluationException() throws Exception {
    CelCompiler compiler =
        CelCompilerFactory.standardCelCompilerBuilder()
            .addFunctionDeclarations(
                newFunctionDeclaration(
                    "stalledCall", newGlobalOverload("stalledCall_overload", SimpleType.INT)))
            .build();
    Program stalledProgram =
        plannerRuntimeBuilder()
            .addFunctionBindings(
                CelFunctionBinding.from(
                    "stalledCall_overload",
                    ImmutableList.of(),
                    (args) -> AccumulatedUnknowns.createForAsyncCall(10L, 9999L)))
            .build()
            .createProgram(compiler.compile("stalledCall()").getAst());

    ExecutionException stalledEx =
        assertThrows(ExecutionException.class, () -> stalledProgram.evalAsync().get(5, SECONDS));

    assertThat(stalledEx)
        .hasCauseThat()
        .hasMessageThat()
        .contains("Asynchronous evaluation stalled");
  }

  @Test
  public void evalAsync_nonStrictErrorAtRoot_throwsCelEvaluationException() throws Exception {
    Program nonStrictErrorProgram = createProgram("true && (1 / 0 == 0)");

    ExecutionException errEx =
        assertThrows(
            ExecutionException.class, () -> nonStrictErrorProgram.evalAsync().get(5, SECONDS));

    assertThat(errEx).hasCauseThat().hasMessageThat().contains("/ by zero");
  }

  @Test
  @SuppressWarnings("Immutable") // Test only
  public void evalAsync_drainStrategies_evaluatesCorrectly(
      @TestParameter({"DRAIN_ALL", "DRAIN_NONE", "DRAIN_READY_ZERO", "DRAIN_READY_50MS"})
          String strategyName)
      throws Exception {
    CelAsyncDrainStrategy strategy;
    switch (strategyName) {
      case "DRAIN_ALL":
        strategy = CelAsyncDrainStrategy.drainAll();
        break;
      case "DRAIN_NONE":
        strategy = CelAsyncDrainStrategy.drainNone();
        break;
      case "DRAIN_READY_ZERO":
        strategy = CelAsyncDrainStrategy.drainReady(Duration.ZERO);
        break;
      case "DRAIN_READY_50MS":
        strategy = CelAsyncDrainStrategy.drainReady(Duration.ofMillis(50));
        break;
      default:
        throw new AssertionError(strategyName);
    }
    CelAsyncEvaluationOptions options =
        CelAsyncEvaluationOptions.builder().setDrainStrategy(strategy).build();
    Program program = createProgram("asyncSquare(3) + asyncSquare(4)", options, ASYNC_SQUARE_INT);

    Object result = program.evalAsync().get(5, SECONDS);

    assertThat(result).isEqualTo(25L);
  }

  @Test
  @SuppressWarnings("Immutable") // Test only
  public void evalAsync_maxConcurrency_limitsSimultaneousInFlightCalls() throws Exception {
    AtomicInteger currentInFlight = new AtomicInteger();
    AtomicInteger maxObservedInFlight = new AtomicInteger();
    CountDownLatch twoInFlightLatch = new CountDownLatch(2);
    SettableFuture<Object> barrier = SettableFuture.create();
    CelAsyncEvaluationOptions options =
        CelAsyncEvaluationOptions.builder().setMaxConcurrency(2).build();
    Program program =
        createProgram(
            "asyncSquare(1) + asyncSquare(2) + asyncSquare(3) + asyncSquare(4)",
            options,
            CelFunctionBinding.fromAsync(
                "asyncSquare_int",
                Long.class,
                (Long arg) -> {
                  int active = currentInFlight.incrementAndGet();
                  maxObservedInFlight.accumulateAndGet(active, Math::max);
                  twoInFlightLatch.countDown();
                  return executor.submit(
                      () -> {
                        try {
                          barrier.get(5, SECONDS);
                          return arg * arg;
                        } finally {
                          currentInFlight.decrementAndGet();
                        }
                      });
                }));

    ListenableFuture<Object> future = program.evalAsync();
    boolean twoStarted = twoInFlightLatch.await(5, SECONDS);
    barrier.set(null);

    assertThat(twoStarted).isTrue();
    assertThat(future.get(5, SECONDS)).isEqualTo(30L);
    assertThat(maxObservedInFlight.get()).isAtMost(2);
  }

  @Test
  public void evalAsync_partialVars_returnsCelUnknownSet(
      @TestParameter({"x", "asyncSquare(3) + x"}) String expression) throws Exception {
    Program program = createProgram(expression, ASYNC_SQUARE_INT);

    Object result =
        program.evalAsync(PartialVars.of(CelAttributePattern.create("x"))).get(5, SECONDS);

    assertThat(result).isInstanceOf(CelUnknownSet.class);
    assertThat(((CelUnknownSet) result).attributes()).containsExactly(CelAttribute.create("x"));
  }

  @Test
  @SuppressWarnings("Immutable") // Test only
  public void evalAsync_partialVarsReachedInPass2_cancelsInFlightSiblingAndReturnsUnknownSet()
      throws Exception {
    SettableFuture<Object> condFuture = SettableFuture.create();
    SettableFuture<Object> slowSibling = SettableFuture.create();
    CountDownLatch siblingStarted = new CountDownLatch(1);
    CountDownLatch siblingCancelledLatch = new CountDownLatch(1);
    slowSibling.addListener(
        () -> {
          if (slowSibling.isCancelled()) {
            siblingCancelledLatch.countDown();
          }
        },
        directExecutor());
    Program shortCircuitUnknownProgram =
        createProgram(
            "(asyncIsEven(2) || (asyncSquare(10) == 100)) ? x : 0",
            CelAsyncEvaluationOptions.builder()
                .setDrainStrategy(CelAsyncDrainStrategy.drainNone())
                .build(),
            CelFunctionBinding.fromAsync("asyncIsEven_int", Long.class, (Long arg) -> condFuture),
            CelFunctionBinding.fromAsync(
                "asyncSquare_int",
                Long.class,
                (Long arg) -> {
                  siblingStarted.countDown();
                  return slowSibling;
                }));

    ListenableFuture<Object> scFuture =
        shortCircuitUnknownProgram.evalAsync(PartialVars.of(CelAttributePattern.create("x")));
    boolean started = siblingStarted.await(5, SECONDS);
    condFuture.set(true);

    assertThat(started).isTrue();
    assertThat(scFuture.get(5, SECONDS)).isInstanceOf(CelUnknownSet.class);
    assertThat(siblingCancelledLatch.await(5, SECONDS)).isTrue();
  }

  @Test
  public void evalAsync_deepSequentialChainOnDirectExecutor_completesWithoutStackOverflow()
      throws Exception {
    StringBuilder expr = new StringBuilder("0");
    for (int i = 0; i < 40; i++) {
      expr = new StringBuilder("asyncAdd(").append(expr).append(", 1)");
    }
    CelAbstractSyntaxTree ast = CEL_COMPILER.compile(expr.toString()).getAst();
    CelRuntime runtime =
        CelRuntimeFactory.plannerRuntimeBuilder()
            .setAsyncExecutor(MoreExecutors.newDirectExecutorService())
            .addFunctionBindings(ASYNC_ADD_INT)
            .build();
    Program program = runtime.createProgram(ast);

    Object result = program.evalAsync().get(5, SECONDS);

    assertThat(result).isEqualTo(40L);
  }

  @Test
  public void evalAsync_concurrentProgramInvocations_isolatesStatePerRun() throws Exception {
    Program program = createProgram("asyncSquare(x) + 1", ASYNC_SQUARE_INT);
    List<ListenableFuture<Object>> futures = new ArrayList<>();

    for (long i = 1; i <= 10; i++) {
      futures.add(program.evalAsync(ImmutableMap.of("x", i)));
    }

    long deadlineNanos = System.nanoTime() + Duration.ofSeconds(5).toNanos();
    for (int i = 0; i < futures.size(); i++) {
      long expected = (long) (i + 1) * (i + 1) + 1;
      long remainingNanos = Math.max(1L, deadlineNanos - System.nanoTime());
      assertThat(futures.get(i).get(remainingNanos, NANOSECONDS)).isEqualTo(expected);
    }
  }

  private CelRuntimeBuilder plannerRuntimeBuilder() {
    return CelRuntimeFactory.plannerRuntimeBuilder().setAsyncExecutor(executor);
  }

  private Program createProgram(String expression, CelFunctionBinding... bindings)
      throws Exception {
    CelAbstractSyntaxTree ast = CEL_COMPILER.compile(expression).getAst();
    return plannerRuntimeBuilder().addFunctionBindings(bindings).build().createProgram(ast);
  }

  private Program createProgram(
      String expression, CelAsyncEvaluationOptions asyncOptions, CelFunctionBinding... bindings)
      throws Exception {
    CelAbstractSyntaxTree ast = CEL_COMPILER.compile(expression).getAst();
    return plannerRuntimeBuilder()
        .setAsyncEvaluationOptions(asyncOptions)
        .addFunctionBindings(bindings)
        .build()
        .createProgram(ast);
  }

  @ThreadSafe
  private static final class RecordingObserver implements CelAsyncObserver {
    private final CopyOnWriteArrayList<RecordedCall> startedCalls = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<RecordedCall> finishedCalls = new CopyOnWriteArrayList<>();

    @Override
    public void onCallStarted(CelAsyncCall call, ImmutableList<Object> args) {
      startedCalls.add(new RecordedCall(call, args, /* result= */ null, /* error= */ null));
    }

    @Override
    public void onCallFinished(
        CelAsyncCall call, @Nullable Object result, @Nullable Throwable error) {
      finishedCalls.add(new RecordedCall(call, ImmutableList.of(), result, error));
    }

    Optional<String> startedFunctionName() {
      return startedCalls.isEmpty()
          ? Optional.empty()
          : Optional.of(startedCalls.get(0).call.functionName());
    }

    Optional<ImmutableList<Object>> startedArgs() {
      return startedCalls.isEmpty() ? Optional.empty() : Optional.of(startedCalls.get(0).args);
    }

    Optional<String> finishedFunctionName() {
      return finishedCalls.isEmpty()
          ? Optional.empty()
          : Optional.of(finishedCalls.get(0).call.functionName());
    }

    Optional<Object> finishedResult() {
      return finishedCalls.isEmpty()
          ? Optional.empty()
          : Optional.ofNullable(finishedCalls.get(0).result);
    }

    Optional<Throwable> finishedError() {
      return finishedCalls.isEmpty()
          ? Optional.empty()
          : Optional.ofNullable(finishedCalls.get(0).error);
    }
  }

  @Immutable
  @SuppressWarnings("Immutable")
  private static final class RecordedCall {
    private final CelAsyncCall call;
    private final ImmutableList<Object> args;
    private final @Nullable Object result;
    private final @Nullable Throwable error;

    private RecordedCall(
        CelAsyncCall call,
        ImmutableList<Object> args,
        @Nullable Object result,
        @Nullable Throwable error) {
      this.call = call;
      this.args = args;
      this.result = result;
      this.error = error;
    }
  }
}
