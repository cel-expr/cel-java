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

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.util.concurrent.Futures.immediateFailedFuture;
import static com.google.common.util.concurrent.Futures.immediateFuture;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static com.google.common.util.concurrent.MoreExecutors.listeningDecorator;
import static com.google.common.util.concurrent.MoreExecutors.newDirectExecutorService;
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
import com.google.common.util.concurrent.SettableFuture;
import com.google.errorprone.annotations.Immutable;
import javax.annotation.concurrent.ThreadSafe;
import com.google.protobuf.Any;
import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.CelErrorCode;
import dev.cel.common.CelOptions;
import dev.cel.common.exceptions.CelDivideByZeroException;
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
import dev.cel.runtime.CelAsyncFunctionOverload;
import dev.cel.runtime.CelAsyncObserver;
import dev.cel.runtime.CelAttribute;
import dev.cel.runtime.CelAttributePattern;
import dev.cel.runtime.CelEvaluationException;
import dev.cel.runtime.CelFunctionBinding;
import dev.cel.runtime.CelFunctionOverload;
import dev.cel.runtime.CelFunctionResolver;
import dev.cel.runtime.CelLateFunctionBindings;
import dev.cel.runtime.CelResolvedOverload;
import dev.cel.runtime.CelRuntime;
import dev.cel.runtime.CelRuntime.Program;
import dev.cel.runtime.CelRuntimeBuilder;
import dev.cel.runtime.CelRuntimeFactory;
import dev.cel.runtime.CelUnknownSet;
import dev.cel.runtime.CelVariableResolver;
import dev.cel.runtime.PartialVars;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
                  "asyncNonStrictIdentity",
                  newGlobalOverload("asyncNonStrictIdentity_dyn", SimpleType.DYN, SimpleType.DYN)),
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
                      "lateAdd_int_int", SimpleType.INT, SimpleType.INT, SimpleType.INT)),
              newFunctionDeclaration(
                  "lateBoundFunc",
                  newGlobalOverload("lateBoundFunc_int", SimpleType.INT, SimpleType.INT)),
              newFunctionDeclaration(
                  "asyncInc", newGlobalOverload("asyncInc_int", SimpleType.INT, SimpleType.INT)),
              newFunctionDeclaration(
                  "delayedRpc",
                  newGlobalOverload(
                      "delayedRpc_string_int",
                      SimpleType.STRING,
                      SimpleType.STRING,
                      SimpleType.INT)))
          .build();

  private static final CelFunctionBinding ASYNC_SQUARE_INT =
      CelFunctionBinding.fromAsync(
          "asyncSquare_int", Long.class, (Long arg) -> immediateFuture(arg * arg));

  private static final CelFunctionBinding ASYNC_ADD_INT =
      CelFunctionBinding.fromAsync(
          "asyncAdd_int_int", Long.class, Long.class, (a, b) -> immediateFuture(a + b));

  private final ListeningExecutorService executor =
      listeningDecorator(Executors.newFixedThreadPool(4));

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
    PARTIAL_VARS
  }

  @Test
  public void evalAsync_activationOverloads_evaluatesCorrectly(
      @TestParameter ActivationOverloadCase overloadCase) throws Exception {
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
  public void evalAsync_protoMessage_evaluatesCorrectly() throws Exception {
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

  @SuppressWarnings("ImmutableEnumChecker") // Test only
  private enum ExhaustiveEvalCase {
    OR_SYNC_LEFT_TRUE("true || (asyncSquare(3) == 9)", true, 0),
    OR_SYNC_RIGHT_TRUE("(asyncSquare(3) == 9) || true", true, 0),
    OR_SYNC_LEFT_FALSE("false || (asyncSquare(3) == 9)", true, 1),
    AND_SYNC_LEFT_FALSE("false && (asyncSquare(3) == 9)", false, 0),
    AND_SYNC_RIGHT_FALSE("(asyncSquare(3) == 9) && false", false, 0),
    AND_SYNC_LEFT_TRUE("true && (asyncSquare(3) == 9)", true, 1),
    CONDITIONAL_ASYNC_PRED_TRUE("asyncIsEven(2) ? asyncSquare(3) : asyncSquare(4)", 9L, 1),
    CONDITIONAL_ASYNC_PRED_FALSE("asyncIsEven(3) ? asyncSquare(3) : asyncSquare(4)", 16L, 1),
    CONDITIONAL_SYNC_PRED_TRUE("true ? asyncSquare(3) : asyncSquare(4)", 9L, 1),
    CONDITIONAL_SYNC_PRED_FALSE("false ? asyncSquare(3) : asyncSquare(4)", 16L, 1);

    private final String expression;
    private final Object expectedResult;
    private final int expectedSquareCalls;

    ExhaustiveEvalCase(String expression, Object expectedResult, int expectedSquareCalls) {
      this.expression = expression;
      this.expectedResult = expectedResult;
      this.expectedSquareCalls = expectedSquareCalls;
    }
  }

  @Test
  @SuppressWarnings("Immutable") // Test only
  public void evalAsync_exhaustiveEval_onlyAwaitsNecessaryBranches(
      @TestParameter ExhaustiveEvalCase testCase) throws Exception {
    AtomicInteger squareCalls = new AtomicInteger();
    CelAbstractSyntaxTree ast = CEL_COMPILER.compile(testCase.expression).getAst();
    CelRuntime runtime =
        plannerRuntimeBuilder()
            .setOptions(
                CelOptions.current()
                    .enableHeterogeneousNumericComparisons(true)
                    .enableShortCircuiting(false)
                    .build())
            .addFunctionBindings(
                CelFunctionBinding.fromAsync(
                    "asyncIsEven_int", Long.class, (Long arg) -> immediateFuture(arg % 2 == 0)),
                CelFunctionBinding.fromAsync(
                    "asyncSquare_int",
                    Long.class,
                    (Long arg) ->
                        executor.submit(
                            () -> {
                              squareCalls.incrementAndGet();
                              return (Object) (arg * arg);
                            })))
            .build();
    Program program = runtime.createProgram(ast);

    Object result = program.evalAsync().get(5, SECONDS);

    assertThat(result).isEqualTo(testCase.expectedResult);
    assertThat(squareCalls.get()).isEqualTo(testCase.expectedSquareCalls);
  }

  private enum ExhaustiveNonStrictErrorCase {
    AND_LHS_FALSE("false && (1 / 0 == 0)", false),
    AND_RHS_FALSE("(1 / 0 == 0) && false", false),
    OR_LHS_TRUE("true || (1 / 0 == 0)", true),
    OR_RHS_TRUE("(1 / 0 == 0) || true", true);

    private final String expression;
    private final boolean expected;

    ExhaustiveNonStrictErrorCase(String expression, boolean expected) {
      this.expression = expression;
      this.expected = expected;
    }
  }

  @Test
  public void evalAsync_exhaustiveLogicalOpsWithNonStrictErrors_evaluatesSuccessfully(
      @TestParameter ExhaustiveNonStrictErrorCase testCase) throws Exception {
    CelAbstractSyntaxTree ast = CEL_COMPILER.compile(testCase.expression).getAst();
    Program program =
        plannerRuntimeBuilder()
            .setOptions(
                CelOptions.current()
                    .enableHeterogeneousNumericComparisons(true)
                    .enableShortCircuiting(false)
                    .build())
            .build()
            .createProgram(ast);

    Object result = program.evalAsync().get(5, SECONDS);

    assertThat(result).isEqualTo(testCase.expected);
  }

  @Test
  @SuppressWarnings("Immutable") // Test only
  public void evalAsync_shortCircuitedUnknownWithAsyncCall_doesNotLeakAsyncCall() throws Exception {
    AtomicInteger callCounter = new AtomicInteger();
    Program program =
        createProgram(
            "((x + asyncSquare(3) == 0) && false) ? 0 : x",
            CelFunctionBinding.fromAsync(
                "asyncSquare_int",
                Long.class,
                (Long arg) -> {
                  callCounter.incrementAndGet();
                  return immediateFuture(arg * arg);
                }));

    Object result =
        program
            .evalAsync(PartialVars.of(CelAttributePattern.fromQualifiedIdentifier("x")))
            .get(5, SECONDS);

    assertThat(result).isInstanceOf(CelUnknownSet.class);
    assertThat(callCounter.get()).isEqualTo(0);
  }

  @Test
  @SuppressWarnings("Immutable") // Test only
  public void
      evalAsync_exhaustiveConditionalWithFailingCondition_failsWithoutDispatchingAsyncBranches(
          @TestParameter({
                "(1 / 0 == 0) ? asyncSquare(3) : asyncSquare(4)",
                "(true && (1 / 0 == 0)) ? asyncSquare(3) : asyncSquare(4)"
              })
              String expression)
          throws Exception {
    AtomicInteger squareCalls = new AtomicInteger();
    CelAbstractSyntaxTree ast = CEL_COMPILER.compile(expression).getAst();
    Program program =
        plannerRuntimeBuilder()
            .setOptions(
                CelOptions.current()
                    .enableHeterogeneousNumericComparisons(true)
                    .enableShortCircuiting(false)
                    .build())
            .addFunctionBindings(
                CelFunctionBinding.fromAsync(
                    "asyncSquare_int",
                    Long.class,
                    (Long arg) -> {
                      squareCalls.incrementAndGet();
                      return immediateFuture(arg * arg);
                    }))
            .build()
            .createProgram(ast);

    ExecutionException e =
        assertThrows(ExecutionException.class, () -> program.evalAsync().get(5, SECONDS));

    assertThat(e).hasCauseThat().isInstanceOf(CelEvaluationException.class);
    CelEvaluationException evalEx = (CelEvaluationException) e.getCause();
    assertThat(evalEx.getErrorCode()).isEqualTo(CelErrorCode.DIVIDE_BY_ZERO);
    assertThat(squareCalls.get()).isEqualTo(0);
  }

  @Test
  public void evalAsync_strictOperatorWrappingNonStrictErrorValue_throwsDivideByZero(
      @TestParameter({"[true && (1 / 0 == 0)]", "!(true && (1 / 0 == 0))"}) String expression)
      throws Exception {
    Program program = createProgram(expression);

    ExecutionException e =
        assertThrows(ExecutionException.class, () -> program.evalAsync().get(5, SECONDS));

    assertThat(e).hasCauseThat().isInstanceOf(CelEvaluationException.class);
    CelEvaluationException evalEx = (CelEvaluationException) e.getCause();
    assertThat(evalEx.getErrorCode()).isEqualTo(CelErrorCode.DIVIDE_BY_ZERO);
  }

  @Test
  public void evalAsync_lateBoundAsyncFunction_throwsCelEvaluationException() throws Exception {
    CelAbstractSyntaxTree ast = CEL_COMPILER.compile("asyncSquare(21)").getAst();
    Program program =
        plannerRuntimeBuilder().addLateBoundFunctions("asyncSquare").build().createProgram(ast);
    CelLateFunctionBindings lateBindings =
        CelLateFunctionBindings.from(
            CelFunctionBinding.fromAsync(
                "asyncSquare_int", Long.class, (Long arg) -> immediateFuture(arg * arg)));

    ExecutionException e =
        assertThrows(
            ExecutionException.class,
            () -> program.evalAsync(ImmutableMap.of(), lateBindings).get(5, SECONDS));

    assertThat(e).hasCauseThat().isInstanceOf(CelEvaluationException.class);
    assertThat(e)
        .hasCauseThat()
        .hasMessageThat()
        .contains(
            "Async function 'asyncSquare' cannot be late-bound. Late-bound functions must be"
                + " synchronous.");
  }

  @Test
  public void eval_withAsyncFunction_throwsCelEvaluationException(
      @TestParameter({"asyncSquare(4)", "1 + asyncSquare(5)"}) String expression) throws Exception {
    Program program = createProgram(expression, ASYNC_SQUARE_INT);

    CelEvaluationException ex = assertThrows(CelEvaluationException.class, program::eval);

    assertThat(ex)
        .hasMessageThat()
        .contains("Async function 'asyncSquare' evaluated in synchronous mode.");
    assertThat(ex).hasCauseThat().isNull();
  }

  @Test
  public void trace_withAsyncFunction_throwsCelEvaluationException() throws Exception {
    Program program = createProgram("asyncSquare(4)", ASYNC_SQUARE_INT);

    CelEvaluationException ex =
        assertThrows(CelEvaluationException.class, () -> program.trace((expr, res) -> {}));

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
  public void evalAsync_withSufficientIterations_succeeds() throws Exception {
    CelAsyncEvaluationOptions options =
        CelAsyncEvaluationOptions.builder().setMaxIterations(2).build();
    Program program = createProgram("asyncSquare(5)", options, ASYNC_SQUARE_INT);

    ListenableFuture<Object> future = program.evalAsync();

    assertThat(future.get(5, SECONDS)).isEqualTo(25L);
  }

  @Test
  public void evalAsync_exceedsMaxIterations_throwsCelEvaluationException() throws Exception {
    CelAsyncEvaluationOptions options =
        CelAsyncEvaluationOptions.builder().setMaxIterations(1).build();
    Program program = createProgram("asyncSquare(5)", options, ASYNC_SQUARE_INT);

    ExecutionException e =
        assertThrows(ExecutionException.class, () -> program.evalAsync().get(5, SECONDS));

    assertThat(e).hasCauseThat().isInstanceOf(CelEvaluationException.class);
    assertThat(e)
        .hasCauseThat()
        .hasMessageThat()
        .contains("Exceeded maximum async evaluation iterations: 1");
  }

  @Test
  public void evalAsync_withObserverOnSuccess_recordsLifecycleEvents() throws Exception {
    RecordingObserver observer = new RecordingObserver();
    Program program =
        createProgram(
            "asyncSquare(3)",
            CelAsyncEvaluationOptions.builder().setObserver(observer).build(),
            ASYNC_SQUARE_INT);

    ListenableFuture<Object> future = program.evalAsync();

    assertThat(future.get(5, SECONDS)).isEqualTo(9L);
    assertThat(observer.startedFunctionName()).hasValue("asyncSquare");
    assertThat(observer.startedArgs()).hasValue(ImmutableList.of(3L));
    assertThat(observer.finishedFunctionName()).hasValue("asyncSquare");
    assertThat(observer.finishedResult()).hasValue(9L);
    assertThat(observer.finishedError()).isEmpty();
  }

  private enum FailureScenario {
    ASYNC_FAILURE("asyncFail(1)", 1L, "simulated error"),
    SYNC_THROW("asyncSyncThrow(2)", 2L, "sync throw");

    private final String expression;
    private final long expectedArg;
    private final String expectedErrorSubstring;

    FailureScenario(String expression, long expectedArg, String expectedErrorSubstring) {
      this.expression = expression;
      this.expectedArg = expectedArg;
      this.expectedErrorSubstring = expectedErrorSubstring;
    }
  }

  @Test
  public void evalAsync_withObserverOnFailure_recordsLifecycleEvents(
      @TestParameter FailureScenario scenario) throws Exception {
    RecordingObserver observer = new RecordingObserver();
    Program program =
        createProgram(
            scenario.expression,
            CelAsyncEvaluationOptions.builder().setObserver(observer).build(),
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

    assertThrows(ExecutionException.class, () -> program.evalAsync().get(5, SECONDS));

    assertThat(observer.startedArgs()).hasValue(ImmutableList.of(scenario.expectedArg));
    assertThat(observer.finishedResult()).isEmpty();
    assertThat(observer.finishedError().map(Throwable::getMessage))
        .hasValue(scenario.expectedErrorSubstring);
  }

  @Test
  public void evalAsync_observerThrowsInStartCallback_failsEvaluationAndReleasesPermits()
      throws Exception {
    CelAsyncObserver observer =
        new CelAsyncObserver() {
          private final AtomicBoolean first = new AtomicBoolean(true);

          @Override
          public void onCallStarted(CelAsyncCall call, ImmutableList<Object> args) {
            if (first.compareAndSet(true, false)) {
              throw new RuntimeException("observer start failure");
            }
          }

          @Override
          public void onCallFinished(
              CelAsyncCall call, @Nullable Object result, @Nullable Throwable error) {}
        };
    Program program =
        createProgram(
            "asyncSquare(2) + asyncSquare(3)",
            CelAsyncEvaluationOptions.builder().setMaxConcurrency(1).setObserver(observer).build(),
            ASYNC_SQUARE_INT);

    ExecutionException startEx =
        assertThrows(ExecutionException.class, () -> program.evalAsync().get(5, SECONDS));

    assertThat(startEx).hasCauseThat().hasMessageThat().contains("observer start failure");
  }

  @Test
  public void evalAsync_observerThrowsInFinishCallback_completesEvaluationAndReleasesPermits()
      throws Exception {
    CelAsyncObserver observer =
        new CelAsyncObserver() {
          @Override
          public void onCallStarted(CelAsyncCall call, ImmutableList<Object> args) {}

          @Override
          public void onCallFinished(
              CelAsyncCall call, @Nullable Object result, @Nullable Throwable error) {
            throw new AssertionError("observer finish error");
          }
        };
    Program program =
        createProgram(
            "asyncSquare(2) + asyncSquare(3)",
            CelAsyncEvaluationOptions.builder().setMaxConcurrency(1).setObserver(observer).build(),
            ASYNC_SQUARE_INT);

    ListenableFuture<Object> future = program.evalAsync();

    assertThat(future.get(5, SECONDS)).isEqualTo(13L);
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
    CelCompiler compiler =
        CelCompilerFactory.standardCelCompilerBuilder()
            .addFunctionDeclarations(
                newFunctionDeclaration(
                    "asyncAnd",
                    newGlobalOverload(
                        "asyncAnd_bool_bool", SimpleType.BOOL, SimpleType.BOOL, SimpleType.BOOL)))
            .build();
    CelRuntime runtime =
        plannerRuntimeBuilder()
            .addFunctionBindings(
                CelFunctionBinding.fromAsync(
                    "asyncAnd_bool_bool",
                    Boolean.class,
                    Boolean.class,
                    (Boolean a, Boolean b) -> {
                      asyncCalls.incrementAndGet();
                      return immediateFuture(a && b);
                    }))
            .build();
    Program program =
        runtime.createProgram(
            compiler
                .compile("asyncAnd(true && (1 / 0 == 0), asyncAnd(true, true) && ([true][1]))")
                .getAst());

    ExecutionException e =
        assertThrows(ExecutionException.class, () -> program.evalAsync().get(5, SECONDS));

    assertThat(e).hasCauseThat().isInstanceOf(CelEvaluationException.class);
    assertThat(((CelEvaluationException) e.getCause()).getErrorCode())
        .isEqualTo(CelErrorCode.DIVIDE_BY_ZERO);
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
                  checkState(
                      failureMode != SiblingFailureMode.SYNC_RUNTIME_EXCEPTION,
                      "synchronous crash");
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
      CountDownLatch task1Started = new CountDownLatch(1);
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
                          task1Started.countDown();
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
      boolean started = task1Started.await(5, SECONDS) && task2Started.await(5, SECONDS);
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
      while (scheduledTask != null
          && !scheduledTask.isCancelled()
          && System.nanoTime() < deadline) {
        Thread.sleep(5);
      }
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

    ExecutionException e = assertThrows(ExecutionException.class, () -> future.get(5, SECONDS));

    assertThat(started).isTrue();
    assertThat(e).hasCauseThat().isInstanceOf(CelEvaluationException.class);
    assertThat(e).hasCauseThat().hasMessageThat().contains("/ by zero");
    assertThat(remainingCallsExecuted.get()).isEqualTo(0);
  }

  @Test
  @SuppressWarnings("Immutable") // Test only
  public void evalAsync_memoization_deduplicatesIntAcrossPasses() throws Exception {
    AtomicInteger callCounter = new AtomicInteger();
    Program program =
        createProgram(
            "asyncSquare(3) + asyncSquare(asyncSquare(4))",
            CelFunctionBinding.fromAsync(
                "asyncSquare_int",
                Long.class,
                (Long arg) -> {
                  callCounter.incrementAndGet();
                  return immediateFuture(arg * arg);
                }));

    Object result = program.evalAsync().get(5, SECONDS);

    assertThat(result).isEqualTo(265L);
    assertThat(callCounter.get()).isEqualTo(3);
  }

  @Test
  @SuppressWarnings("Immutable") // Test only
  public void evalAsync_memoization_normalizesDoubleNanAcrossPasses() throws Exception {
    AtomicInteger callCounter = new AtomicInteger();
    Program program =
        createProgram(
            "asyncSquare(dx) + asyncSquare(asyncSquare(2.0))",
            CelFunctionBinding.fromAsync(
                "asyncSquare_double",
                Double.class,
                (Double arg) -> {
                  if (Double.isNaN(arg)) {
                    callCounter.incrementAndGet();
                  }
                  return immediateFuture(arg * arg);
                }));

    Object result = program.evalAsync(ImmutableMap.of("dx", Double.NaN)).get(5, SECONDS);

    assertThat((Double) result).isNaN();
    assertThat(callCounter.get()).isEqualTo(1);
  }

  @Test
  @SuppressWarnings("Immutable") // Test only
  public void evalAsync_memoization_normalizesNegativeZeroAcrossPasses() throws Exception {
    AtomicInteger callCounter = new AtomicInteger();
    Program program =
        createProgram(
            "asyncSquare(dx) + asyncSquare(asyncSquare(2.0))",
            CelFunctionBinding.fromAsync(
                "asyncSquare_double",
                Double.class,
                (Double arg) -> {
                  if (arg == 0.0d) {
                    callCounter.incrementAndGet();
                  }
                  return immediateFuture(arg * arg);
                }));

    Object result = program.evalAsync(ImmutableMap.of("dx", -0.0d)).get(5, SECONDS);

    assertThat(result).isEqualTo(16.0d);
    assertThat(callCounter.get()).isEqualTo(1);
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

  @Immutable
  @SuppressWarnings("Immutable") // Test only
  private enum DrainStrategyCase {
    DRAIN_ALL(CelAsyncDrainStrategy.drainAll()),
    DRAIN_NONE(CelAsyncDrainStrategy.drainNone()),
    DRAIN_READY_ZERO(CelAsyncDrainStrategy.drainReady(Duration.ZERO)),
    DRAIN_READY_50MS(CelAsyncDrainStrategy.drainReady(Duration.ofMillis(50)));

    private final CelAsyncDrainStrategy strategy;

    DrainStrategyCase(CelAsyncDrainStrategy strategy) {
      this.strategy = strategy;
    }
  }

  @Test
  public void evalAsync_drainStrategies_evaluatesCorrectly(
      @TestParameter DrainStrategyCase testCase) throws Exception {
    CelAsyncEvaluationOptions options =
        CelAsyncEvaluationOptions.builder().setDrainStrategy(testCase.strategy).build();
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
            .setAsyncExecutor(newDirectExecutorService())
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

  @Test
  public void evalAsync_argEvaluatesToLocalizedErrorValue_propagatesLocalizedError()
      throws Exception {
    CelCompiler compiler =
        CelCompilerFactory.standardCelCompilerBuilder()
            .addFunctionDeclarations(
                newFunctionDeclaration(
                    "asyncCheck",
                    newGlobalOverload("asyncCheck_bool", SimpleType.BOOL, SimpleType.BOOL)))
            .build();
    CelRuntime runtime =
        plannerRuntimeBuilder()
            .addFunctionBindings(
                CelFunctionBinding.fromAsync(
                    "asyncCheck_bool", Boolean.class, (Boolean arg) -> immediateFuture(arg)))
            .build();
    Program program =
        runtime.createProgram(compiler.compile("asyncCheck(true && (1 / 0 == 1))").getAst());

    ExecutionException e =
        assertThrows(ExecutionException.class, () -> program.evalAsync().get(5, SECONDS));

    assertThat(e).hasCauseThat().isInstanceOf(CelEvaluationException.class);
    assertThat(e).hasCauseThat().hasCauseThat().isInstanceOf(CelDivideByZeroException.class);
    assertThat(e).hasCauseThat().hasMessageThat().contains("/ by zero");
  }

  @Test
  public void evalAsync_argEvaluatesToNonLocalizedErrorValue_wrapsAndPropagatesError()
      throws Exception {
    CelCompiler compiler =
        CelCompilerFactory.standardCelCompilerBuilder()
            .addFunctionDeclarations(
                newFunctionDeclaration(
                    "asyncCheck",
                    newGlobalOverload("asyncCheck_bool", SimpleType.BOOL, SimpleType.BOOL)),
                newFunctionDeclaration(
                    "syncCrash", newGlobalOverload("syncCrash_overload", SimpleType.BOOL)))
            .build();
    CelRuntime runtime =
        plannerRuntimeBuilder()
            .addFunctionBindings(
                CelFunctionBinding.fromAsync(
                    "asyncCheck_bool", Boolean.class, (Boolean arg) -> immediateFuture(arg)),
                CelFunctionBinding.from(
                    "syncCrash_overload",
                    ImmutableList.of(),
                    (args) -> {
                      throw new IllegalStateException("synchronous function crash");
                    }))
            .build();
    Program program =
        runtime.createProgram(compiler.compile("asyncCheck(true && syncCrash())").getAst());

    ExecutionException e =
        assertThrows(ExecutionException.class, () -> program.evalAsync().get(5, SECONDS));

    assertThat(e).hasCauseThat().isInstanceOf(CelEvaluationException.class);
    assertThat(e)
        .hasCauseThat()
        .hasCauseThat()
        .hasMessageThat()
        .contains("synchronous function crash");
  }

  @Test
  public void evalAsync_argEvaluatesToCelRuntimeExceptionErrorValue_wrapsWithErrorCode()
      throws Exception {
    CelCompiler compiler =
        CelCompilerFactory.standardCelCompilerBuilder()
            .addFunctionDeclarations(
                newFunctionDeclaration(
                    "asyncCheck",
                    newGlobalOverload("asyncCheck_bool", SimpleType.BOOL, SimpleType.BOOL)))
            .build();
    CelRuntime runtime =
        plannerRuntimeBuilder()
            .addFunctionBindings(
                CelFunctionBinding.fromAsync(
                    "asyncCheck_bool", Boolean.class, (Boolean arg) -> immediateFuture(arg)))
            .build();
    Program program =
        runtime.createProgram(compiler.compile("asyncCheck(true && [true][1])").getAst());

    ExecutionException e =
        assertThrows(ExecutionException.class, () -> program.evalAsync().get(5, SECONDS));

    assertThat(e).hasCauseThat().isInstanceOf(CelEvaluationException.class);
    assertThat(((CelEvaluationException) e.getCause()).getErrorCode())
        .isEqualTo(CelErrorCode.INDEX_OUT_OF_BOUNDS);
  }

  @Test
  public void evalAsync_asyncFunctionFailsWithCelRuntimeException_preservesErrorCode()
      throws Exception {
    Program program =
        createProgram(
            "asyncSquare(5)",
            CelFunctionBinding.fromAsync(
                "asyncSquare_int",
                Long.class,
                (Long arg) ->
                    immediateFailedFuture(
                        new CelDivideByZeroException(new ArithmeticException("/ by zero")))));

    ExecutionException e =
        assertThrows(ExecutionException.class, () -> program.evalAsync().get(5, SECONDS));

    assertThat(e).hasCauseThat().isInstanceOf(CelEvaluationException.class);
    assertThat(((CelEvaluationException) e.getCause()).getErrorCode())
        .isEqualTo(CelErrorCode.DIVIDE_BY_ZERO);
  }

  @Test
  public void evalAsync_argEvaluatesToGenericErrorValue_wrapsAsInternalError() throws Exception {
    CelCompiler compiler =
        CelCompilerFactory.standardCelCompilerBuilder()
            .addVar("x", SimpleType.DYN)
            .addFunctionDeclarations(
                newFunctionDeclaration(
                    "asyncCheck",
                    newGlobalOverload("asyncCheck_bool", SimpleType.BOOL, SimpleType.BOOL)))
            .build();
    CelRuntime runtime =
        plannerRuntimeBuilder()
            .addFunctionBindings(
                CelFunctionBinding.fromAsync(
                    "asyncCheck_bool", Boolean.class, (Boolean arg) -> immediateFuture(arg)))
            .build();
    Program program = runtime.createProgram(compiler.compile("asyncCheck(x && true)").getAst());

    ExecutionException e =
        assertThrows(
            ExecutionException.class,
            () -> program.evalAsync(ImmutableMap.of("x", 1L)).get(5, SECONDS));

    assertThat(e).hasCauseThat().isInstanceOf(CelEvaluationException.class);
    assertThat(((CelEvaluationException) e.getCause()).getErrorCode())
        .isEqualTo(CelErrorCode.INTERNAL_ERROR);
    assertThat(e).hasCauseThat().hasCauseThat().isInstanceOf(IllegalArgumentException.class);
    assertThat(e)
        .hasCauseThat()
        .hasCauseThat()
        .hasMessageThat()
        .contains("Expected boolean value, found: 1");
  }

  @Test
  @SuppressWarnings("Immutable") // Test only
  public void evalAsync_syncFunctionThrowsWithCause_unwrapsOriginalCause() throws Exception {
    IllegalArgumentException rootCause = new IllegalArgumentException("nested root cause");
    Program program =
        createProgram(
            "asyncSyncThrow(1)",
            CelFunctionBinding.from(
                "asyncSyncThrow_int",
                Long.class,
                (Long arg) -> {
                  throw new RuntimeException("outer wrapper", rootCause);
                }));

    ExecutionException e =
        assertThrows(ExecutionException.class, () -> program.evalAsync().get(5, SECONDS));

    assertThat(e).hasCauseThat().isInstanceOf(CelEvaluationException.class);
    assertThat(e).hasCauseThat().hasCauseThat().hasCauseThat().isSameInstanceAs(rootCause);
  }

  @Test
  @SuppressWarnings("Immutable") // Test only
  public void evalAsync_maxIterationsExceeded_cancelsInFlightCalls() throws Exception {
    SettableFuture<Object> inFlightSibling = SettableFuture.create();
    SettableFuture<Object> call1Future = SettableFuture.create();
    CountDownLatch siblingStarted = new CountDownLatch(1);
    CelAsyncEvaluationOptions options =
        CelAsyncEvaluationOptions.builder().setMaxIterations(1).build();
    Program program =
        createProgram(
            "asyncSquare(asyncSquare(2)) + asyncSquare(10)",
            options,
            CelFunctionBinding.fromAsync(
                "asyncSquare_int",
                Long.class,
                (Long arg) -> {
                  if (arg == 10L) {
                    siblingStarted.countDown();
                    return inFlightSibling;
                  }
                  return call1Future;
                }));

    ListenableFuture<Object> future = program.evalAsync();
    boolean started = siblingStarted.await(5, SECONDS);
    call1Future.set(4L);
    ExecutionException e = assertThrows(ExecutionException.class, () -> future.get(5, SECONDS));

    assertThat(started).isTrue();
    assertThat(e).hasCauseThat().isInstanceOf(CelEvaluationException.class);
    assertThat(e)
        .hasCauseThat()
        .hasMessageThat()
        .contains("Exceeded maximum async evaluation iterations: 1");
    assertThat(inFlightSibling.isCancelled()).isTrue();
  }

  @Test
  public void evalAsync_unboundedMaxIterations_succeeds() throws Exception {
    CelAsyncEvaluationOptions options =
        CelAsyncEvaluationOptions.builder().setMaxIterations(-1).build();
    Program program = createProgram("asyncSquare(5)", options, ASYNC_SQUARE_INT);

    ListenableFuture<Object> future = program.evalAsync();

    assertThat(future.get(5, SECONDS)).isEqualTo(25L);
  }

  @Test
  @SuppressWarnings("Immutable") // Test only
  public void
      evalAsync_strictAsyncFunctionWithImmediateErrorAndAsyncSibling_doesNotDispatchSibling()
          throws Exception {
    AtomicInteger siblingDispatched = new AtomicInteger();
    CelCompiler compiler =
        CelCompilerFactory.standardCelCompilerBuilder()
            .addFunctionDeclarations(
                newFunctionDeclaration(
                    "asyncAdd",
                    newGlobalOverload(
                        "asyncAdd_int_int", SimpleType.INT, SimpleType.INT, SimpleType.INT)),
                newFunctionDeclaration(
                    "asyncSquare",
                    newGlobalOverload("asyncSquare_int", SimpleType.INT, SimpleType.INT)))
            .build();
    CelRuntime runtime =
        plannerRuntimeBuilder()
            .addFunctionBindings(
                CelFunctionBinding.fromAsync(
                    "asyncAdd_int_int",
                    Long.class,
                    Long.class,
                    (Long a, Long b) -> immediateFuture(a + b)),
                CelFunctionBinding.fromAsync(
                    "asyncSquare_int",
                    Long.class,
                    (Long arg) -> {
                      siblingDispatched.incrementAndGet();
                      return immediateFuture(arg * arg);
                    }))
            .build();
    Program program =
        runtime.createProgram(
            compiler.compile("asyncAdd(true ? (1 / 0) : 0, asyncSquare(10))").getAst());

    ExecutionException e =
        assertThrows(ExecutionException.class, () -> program.evalAsync().get(5, SECONDS));

    assertThat(e).hasCauseThat().isInstanceOf(CelEvaluationException.class);
    assertThat(e).hasCauseThat().hasMessageThat().contains("/ by zero");
    assertThat(siblingDispatched.get()).isEqualTo(0);
  }

  @Test
  public void evalAsync_errorValueAtRoot_throwsCelEvaluationException() throws Exception {
    Program program =
        createProgram(
            "(1 / 0 == 0) || (asyncSquare(10) == 0)",
            CelFunctionBinding.fromAsync(
                "asyncSquare_int", Long.class, (Long arg) -> immediateFuture(100L)));

    ExecutionException e =
        assertThrows(ExecutionException.class, () -> program.evalAsync().get(5, SECONDS));

    assertThat(e).hasCauseThat().isInstanceOf(CelEvaluationException.class);
    assertThat(e).hasCauseThat().hasMessageThat().contains("/ by zero");
  }

  @Test
  @SuppressWarnings("Immutable") // Test only
  public void evalAsync_asyncCallWithUnknownArgument_returnsUnknownSetWithoutDispatchingCall()
      throws Exception {
    AtomicInteger callCounter = new AtomicInteger();
    Program program =
        createProgram(
            "asyncSquare(x)",
            CelFunctionBinding.fromAsync(
                "asyncSquare_int",
                Long.class,
                (Long arg) -> {
                  callCounter.incrementAndGet();
                  return immediateFuture(arg * arg);
                }));

    Object result =
        program
            .evalAsync(PartialVars.of(CelAttributePattern.fromQualifiedIdentifier("x")))
            .get(5, SECONDS);

    assertThat(result).isInstanceOf(CelUnknownSet.class);
    assertThat(callCounter.get()).isEqualTo(0);
  }

  @Test
  @SuppressWarnings("Immutable") // Test only
  public void evalAsync_asyncCallThrowsCelRuntimeException_preservesErrorCode() throws Exception {
    Program program =
        createProgram(
            "asyncFail(1)",
            CelFunctionBinding.fromAsync(
                "asyncFail_int",
                Long.class,
                (Long arg) -> immediateFailedFuture(new CelDivideByZeroException())));

    ExecutionException e =
        assertThrows(ExecutionException.class, () -> program.evalAsync().get(5, SECONDS));

    assertThat(e).hasCauseThat().isInstanceOf(CelEvaluationException.class);
    CelEvaluationException evalEx = (CelEvaluationException) e.getCause();
    assertThat(evalEx.getErrorCode()).isEqualTo(CelErrorCode.DIVIDE_BY_ZERO);
  }

  @Test
  public void evalAsync_comprehensionFilter_filtersCorrectly() throws Exception {
    CelAbstractSyntaxTree ast = CEL_COMPILER.compile("list_var.filter(x, asyncIsEven(x))").getAst();
    CelRuntime runtime =
        plannerRuntimeBuilder()
            .addFunctionBindings(
                CelFunctionBinding.fromAsync(
                    "asyncIsEven_int", Long.class, (Long arg) -> immediateFuture(arg % 2 == 0)))
            .build();
    Program program = runtime.createProgram(ast);

    ListenableFuture<Object> future =
        program.evalAsync(ImmutableMap.of("list_var", ImmutableList.of(1L, 2L, 3L, 4L, 5L, 6L)));
    Object result = future.get(5, SECONDS);

    assertThat((Iterable<?>) result).containsExactly(2L, 4L, 6L).inOrder();
  }

  @Test
  public void evalAsync_comprehensionPredicate_evaluatesSpeculatively(
      @TestParameter ComprehensionPredicateTestCase testCase) throws Exception {
    CelAbstractSyntaxTree ast = CEL_COMPILER.compile(testCase.expression).getAst();
    CelRuntime runtime =
        plannerRuntimeBuilder()
            .addFunctionBindings(
                CelFunctionBinding.fromAsync(
                    "asyncIsEven_int", Long.class, (Long arg) -> immediateFuture(arg % 2L == 0L)))
            .build();
    Program program = runtime.createProgram(ast);

    ListenableFuture<Object> future = program.evalAsync(testCase.variables);
    Object result = future.get(5, SECONDS);

    assertThat(result).isEqualTo(testCase.expectedResult);
  }

  @Test
  public void evalAsync_comprehensionMap_evaluatesCorrectly() throws Exception {
    CelAbstractSyntaxTree ast = CEL_COMPILER.compile("list_var.map(x, asyncSquare(x))").getAst();
    CelRuntime runtime =
        plannerRuntimeBuilder()
            .addFunctionBindings(
                CelFunctionBinding.fromAsync(
                    "asyncSquare_int", Long.class, (Long arg) -> immediateFuture(arg * arg)))
            .build();
    Program program = runtime.createProgram(ast);

    ListenableFuture<Object> future =
        program.evalAsync(ImmutableMap.of("list_var", ImmutableList.of(1L, 2L, 3L)));
    Object result = future.get(5, SECONDS);

    assertThat((Iterable<?>) result).containsExactly(1L, 4L, 9L).inOrder();
  }

  @Test
  public void syncEval_onLateBoundAsyncFunction_throwsCelEvaluationException(
      @TestParameter boolean parsedOnly) throws Exception {
    String fnName = parsedOnly ? "customLateAsync" : "lateBoundFunc";
    CelAbstractSyntaxTree ast =
        parsedOnly
            ? CEL_COMPILER.parse("customLateAsync(7)").getAst()
            : CEL_COMPILER.compile("lateBoundFunc(10)").getAst();
    CelRuntime runtime =
        parsedOnly
            ? plannerRuntimeBuilder().build()
            : plannerRuntimeBuilder().addLateBoundFunctions("lateBoundFunc").build();
    Program program = runtime.createProgram(ast);
    CelFunctionResolver lateBoundResolver =
        newLateBoundAsyncResolver(fnName, (Long arg) -> immediateFuture(100L));

    CelEvaluationException e =
        assertThrows(
            CelEvaluationException.class, () -> program.eval(ImmutableMap.of(), lateBoundResolver));

    assertThat(e)
        .hasMessageThat()
        .contains(
            String.format(
                "Async function '%s' cannot be late-bound. Late-bound functions must be"
                    + " synchronous.",
                fnName));
  }

  @Test
  public void evalAsync_comprehensionAsyncError_propagatesFailure() throws Exception {
    CelAbstractSyntaxTree ast =
        CEL_COMPILER.compile("list_var.map(i, i == 2 ? asyncFail(i) : asyncSquare(i))").getAst();
    CelRuntime runtime =
        plannerRuntimeBuilder()
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
        program.evalAsync(ImmutableMap.of("list_var", ImmutableList.of(1L, 2L, 3L)));
    ExecutionException e = assertThrows(ExecutionException.class, future::get);

    assertThat(e).hasCauseThat().isInstanceOf(CelEvaluationException.class);
    assertThat(e).hasCauseThat().hasMessageThat().contains("comprehension fail");
  }

  @Test
  @SuppressWarnings("Immutable") // Test only
  public void evalAsync_highVolumeFanout_respectsConcurrencyAndCompletes() throws Exception {
    ImmutableList<Long> items = LongStream.rangeClosed(1, 50).boxed().collect(toImmutableList());
    ImmutableList<Long> expected =
        LongStream.rangeClosed(1, 50).map(x -> x * x).boxed().collect(toImmutableList());
    CelAbstractSyntaxTree ast = CEL_COMPILER.compile("list_var.map(x, asyncSquare(x))").getAst();
    AtomicInteger activeConcurrent = new AtomicInteger();
    AtomicInteger maxConcurrent = new AtomicInteger();
    ListeningExecutorService workerPool = listeningDecorator(Executors.newFixedThreadPool(4));
    try {
      CelAsyncEvaluationOptions options =
          CelAsyncEvaluationOptions.builder().setMaxConcurrency(3).build();
      CelRuntime runtime =
          plannerRuntimeBuilder()
              .setAsyncEvaluationOptions(options)
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

      ListenableFuture<Object> future = program.evalAsync(ImmutableMap.of("list_var", items));
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
  public void evalAsync_comprehensionFilter_mergesAccumulatedUnknownsAndDispatchesInParallel(
      @TestParameter ComprehensionFilterTestCase testCase) throws Exception {
    CelAbstractSyntaxTree ast = CEL_COMPILER.compile(testCase.expression).getAst();
    Map<Long, SettableFuture<Object>> futures = new HashMap<>();
    futures.put(1L, SettableFuture.create());
    futures.put(2L, SettableFuture.create());
    CelRuntime runtime =
        plannerRuntimeBuilder()
            .addFunctionBindings(
                CelFunctionBinding.fromAsync(
                    "asyncIsEven_int", Long.class, (Long arg) -> futures.get(arg)))
            .build();
    Program program = runtime.createProgram(ast);

    ListenableFuture<Object> evalFuture = program.evalAsync(testCase.variables);
    boolean firstCancelledInitial = futures.get(1L).isCancelled();
    boolean secondCancelledInitial = futures.get(2L).isCancelled();
    boolean doneInitial = evalFuture.isDone();
    futures.get(1L).set(false);
    boolean doneAfterFirst = evalFuture.isDone();
    futures.get(2L).set(true);
    Object result = evalFuture.get(5, SECONDS);

    assertThat(firstCancelledInitial).isFalse();
    assertThat(secondCancelledInitial).isFalse();
    assertThat(doneInitial).isFalse();
    assertThat(doneAfterFirst).isFalse();
    assertThat((Iterable<?>) result).containsExactly(testCase.expectedElement);
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
        plannerRuntimeBuilder()
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
        program.evalAsync(ImmutableMap.of("map_var", ImmutableMap.of("a", 1L, "b", 4L)));
    boolean doneBeforeSet = evalFuture.isDone();
    futureA.set(false);
    futureB.set(true);
    Object result = evalFuture.get(5, SECONDS);

    assertThat(doneBeforeSet).isFalse();
    assertThat(result).isEqualTo(true);
  }

  @Test
  @SuppressWarnings("Immutable") // Test only
  public void evalAsync_comprehensionMap_withInFlightAsync_evaluatesCorrectly() throws Exception {
    CelAbstractSyntaxTree ast = CEL_COMPILER.compile("list_var.map(x, asyncSquare(x))").getAst();
    SettableFuture<Object> future1 = SettableFuture.create();
    SettableFuture<Object> future2 = SettableFuture.create();
    CelRuntime runtime =
        plannerRuntimeBuilder()
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
        program.evalAsync(ImmutableMap.of("list_var", ImmutableList.of(2L, 3L)));
    boolean doneBeforeSet = evalFuture.isDone();
    future1.set(4L);
    future2.set(9L);
    Object result = evalFuture.get(5, SECONDS);

    assertThat(doneBeforeSet).isFalse();
    assertThat((Iterable<?>) result).containsExactly(4L, 9L).inOrder();
  }

  @Test
  @SuppressWarnings("Immutable") // Test only
  public void evalAsync_evaluationCancelled_clearsPendingGateTasks() throws Exception {
    CelAbstractSyntaxTree ast = CEL_COMPILER.compile("[1, 2].map(n, asyncSquare(n))").getAst();
    SettableFuture<Object> firstFuture = SettableFuture.create();
    SettableFuture<Object> secondFuture = SettableFuture.create();
    CountDownLatch task1Started = new CountDownLatch(1);
    AtomicInteger tasksSubmitted = new AtomicInteger();
    ListeningExecutorService trackingExecutor = newTrackingExecutor(tasksSubmitted);
    CelAsyncEvaluationOptions options =
        CelAsyncEvaluationOptions.builder().setMaxConcurrency(1).build();
    CelRuntime runtime =
        plannerRuntimeBuilder()
            .setAsyncExecutor(trackingExecutor)
            .setAsyncEvaluationOptions(options)
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

    ListenableFuture<Object> evalFuture = program.evalAsync();
    boolean started = task1Started.await(5, SECONDS);
    int submittedBeforeCancel = tasksSubmitted.get();
    evalFuture.cancel(/* mayInterruptIfRunning= */ true);
    firstFuture.set(1L);

    assertThat(started).isTrue();
    assertThat(submittedBeforeCancel).isEqualTo(1);
    assertThat(tasksSubmitted.get()).isEqualTo(1);
  }

  @Test
  @SuppressWarnings("Immutable") // Test only
  public void evalAsync_evaluationError_clearsPendingGateTasks() throws Exception {
    CelAbstractSyntaxTree ast = CEL_COMPILER.compile("[1, 2].map(n, asyncSquare(n))").getAst();
    SettableFuture<Object> firstFuture = SettableFuture.create();
    SettableFuture<Object> secondFuture = SettableFuture.create();
    CountDownLatch task1Started = new CountDownLatch(1);
    AtomicInteger tasksSubmitted = new AtomicInteger();
    ListeningExecutorService trackingExecutor = newTrackingExecutor(tasksSubmitted);
    CelAsyncEvaluationOptions options =
        CelAsyncEvaluationOptions.builder().setMaxConcurrency(1).build();
    AtomicInteger task2Executed = new AtomicInteger();
    CelRuntime runtime =
        plannerRuntimeBuilder()
            .setAsyncExecutor(trackingExecutor)
            .setAsyncEvaluationOptions(options)
            .addFunctionBindings(
                CelFunctionBinding.fromAsync(
                    "asyncSquare_int",
                    Long.class,
                    (Long arg) -> {
                      if (arg == 1L) {
                        task1Started.countDown();
                        return firstFuture;
                      }
                      task2Executed.incrementAndGet();
                      return secondFuture;
                    }))
            .build();
    Program program = runtime.createProgram(ast);

    ListenableFuture<Object> evalFuture = program.evalAsync();
    boolean started = task1Started.await(5, SECONDS);
    int submittedBeforeError = tasksSubmitted.get();
    firstFuture.setException(new RuntimeException("/ by zero"));
    ExecutionException exception =
        assertThrows(ExecutionException.class, () -> evalFuture.get(5, SECONDS));

    assertThat(started).isTrue();
    assertThat(submittedBeforeError).isEqualTo(1);
    assertThat(exception).hasCauseThat().isInstanceOf(CelEvaluationException.class);
    assertThat(exception).hasCauseThat().hasMessageThat().contains("/ by zero");
    assertThat(task2Executed.get()).isEqualTo(0);
    assertThat(tasksSubmitted.get()).isEqualTo(2);
  }

  @Test
  @SuppressWarnings("Immutable") // Test only
  public void
      evalAsync_comprehensionFilter_withInFlightIteration1AndConcreteIteration2_waitsForCompletion(
          @TestParameter ComprehensionFilterTestCase testCase) throws Exception {
    CelAbstractSyntaxTree ast = CEL_COMPILER.compile(testCase.expression).getAst();
    CountDownLatch callsDispatched = new CountDownLatch(2);
    SettableFuture<Object> future1 = SettableFuture.create();
    CelRuntime runtime =
        plannerRuntimeBuilder()
            .addFunctionBindings(
                CelFunctionBinding.fromAsync(
                    "asyncIsEven_int",
                    Long.class,
                    (Long arg) -> {
                      callsDispatched.countDown();
                      if (arg == 1L) {
                        return future1;
                      }
                      return immediateFuture(true);
                    }))
            .build();
    Program program = runtime.createProgram(ast);

    ListenableFuture<Object> evalFuture = program.evalAsync(testCase.variables);
    boolean dispatched = callsDispatched.await(5, SECONDS);
    boolean doneWhileInFlight = evalFuture.isDone();
    future1.set(false);
    Object result = evalFuture.get(5, SECONDS);

    assertThat(dispatched).isTrue();
    assertThat(doneWhileInFlight).isFalse();
    assertThat((Iterable<?>) result).containsExactly(testCase.expectedElement);
  }

  @Test
  @SuppressWarnings("Immutable") // Test only
  public void evalAsync_comprehensionAll_lastElementFalseWithFirstElementInFlight_shortCircuits()
      throws Exception {
    CelAbstractSyntaxTree ast = CEL_COMPILER.compile("list_var.all(x, asyncIsEven(x))").getAst();
    SettableFuture<Object> inFlightFirstElement = SettableFuture.create();
    CelRuntime runtime =
        plannerRuntimeBuilder()
            .addFunctionBindings(
                CelFunctionBinding.fromAsync(
                    "asyncIsEven_int",
                    Long.class,
                    (Long arg) -> {
                      if (arg == 1L) {
                        return inFlightFirstElement;
                      }
                      return immediateFuture(false);
                    }))
            .build();
    Program program = runtime.createProgram(ast);

    ListenableFuture<Object> evalFuture =
        program.evalAsync(ImmutableMap.of("list_var", ImmutableList.of(1L, 2L)));
    Object result = evalFuture.get(5, SECONDS);

    assertThat(result).isEqualTo(false);
    assertThat(inFlightFirstElement.isCancelled()).isTrue();
  }

  @Test
  public void evalAsync_withLateBoundResolverAndMap_evaluatesSuccessfully() throws Exception {
    CelAbstractSyntaxTree ast = CEL_COMPILER.compile("lateBoundFunc(x)").getAst();
    CelRuntime runtime = plannerRuntimeBuilder().addLateBoundFunctions("lateBoundFunc").build();
    Program program = runtime.createProgram(ast);
    CelFunctionResolver lateResolver =
        newLateBoundSyncResolver("lateBoundFunc", (Long arg) -> arg * 10L);

    ListenableFuture<Object> future = program.evalAsync(ImmutableMap.of("x", 2L), lateResolver);
    Object result = future.get(5, SECONDS);

    assertThat(result).isEqualTo(20L);
  }

  @Test
  public void evalAsync_withLateBoundResolverAndVariableResolver_evaluatesSuccessfully()
      throws Exception {
    CelAbstractSyntaxTree ast = CEL_COMPILER.compile("lateBoundFunc(x)").getAst();
    CelRuntime runtime = plannerRuntimeBuilder().addLateBoundFunctions("lateBoundFunc").build();
    Program program = runtime.createProgram(ast);
    CelVariableResolver resolver = name -> name.equals("x") ? Optional.of(5L) : Optional.empty();
    CelFunctionResolver lateResolver =
        newLateBoundSyncResolver("lateBoundFunc", (Long arg) -> arg * 10L);

    ListenableFuture<Object> future = program.evalAsync(resolver, lateResolver);
    Object result = future.get(5, SECONDS);

    assertThat(result).isEqualTo(50L);
  }

  @Test
  public void evalAsync_withLateBoundAsyncOverload_throwsException() throws Exception {
    CelAbstractSyntaxTree ast = CEL_COMPILER.compile("lateBoundFunc(x)").getAst();
    CelRuntime runtime = plannerRuntimeBuilder().addLateBoundFunctions("lateBoundFunc").build();
    Program program = runtime.createProgram(ast);
    CelFunctionResolver lateResolver =
        newLateBoundAsyncResolver("lateBoundFunc", (Long arg) -> immediateFuture(arg * 10L));

    ListenableFuture<Object> future = program.evalAsync(ImmutableMap.of("x", 2L), lateResolver);
    ExecutionException e = assertThrows(ExecutionException.class, () -> future.get(5, SECONDS));

    assertThat(e).hasCauseThat().isInstanceOf(CelEvaluationException.class);
    assertThat(e)
        .hasCauseThat()
        .hasMessageThat()
        .contains(
            "Async function 'lateBoundFunc' cannot be late-bound. Late-bound functions must be"
                + " synchronous.");
  }

  @Test
  public void
      evalAsync_parsedOnly_distinctOverloadIdWithoutLateBoundResolver_throwsCelEvaluationException()
          throws Exception {
    CelAbstractSyntaxTree ast = CEL_COMPILER.parse("asyncSquare(5)").getAst();
    CelRuntime runtime =
        plannerRuntimeBuilder()
            .addFunctionBindings(
                CelFunctionBinding.fromAsync(
                    "asyncSquare_int", Long.class, (Long arg) -> immediateFuture(arg * arg)))
            .build();
    Program program = runtime.createProgram(ast);

    ListenableFuture<Object> future = program.evalAsync();
    ExecutionException e = assertThrows(ExecutionException.class, future::get);

    assertThat(e).hasCauseThat().isInstanceOf(CelEvaluationException.class);
    assertThat(e)
        .hasCauseThat()
        .hasMessageThat()
        .contains("No matching overload for function 'asyncSquare'");
  }

  @Test
  public void evalAsync_comprehensionAll_shortCircuitsAndSuppressesSubsequentError()
      throws Exception {
    CelCompiler compiler =
        CelCompilerFactory.standardCelCompilerBuilder()
            .setStandardMacros(CelStandardMacro.STANDARD_MACROS)
            .setOptions(CelOptions.current().build())
            .addVar("list_var", ListType.create(SimpleType.INT))
            .addFunctionDeclarations(
                newFunctionDeclaration(
                    "asyncFalse",
                    newGlobalOverload("asyncFalse_int", SimpleType.BOOL, SimpleType.INT)))
            .build();
    CelAbstractSyntaxTree ast =
        compiler.compile("list_var.all(x, x == 1 ? asyncFalse(x) : (1 / (2 - x) == 0))").getAst();
    CelRuntime runtime =
        plannerRuntimeBuilder()
            .addFunctionBindings(
                CelFunctionBinding.fromAsync(
                    "asyncFalse_int", Long.class, (Long arg) -> immediateFuture(false)))
            .build();
    Program program = runtime.createProgram(ast);

    ListenableFuture<Object> future =
        program.evalAsync(ImmutableMap.of("list_var", ImmutableList.of(1L, 2L)));
    Object result = future.get(5, SECONDS);

    assertThat(result).isEqualTo(false);
  }

  @Test
  public void evalAsync_parsedOnly_lateBoundSynchronousFunction_evaluatesSuccessfully()
      throws Exception {
    CelAbstractSyntaxTree ast = CEL_COMPILER.parse("lateBoundSync(10)").getAst();
    CelRuntime runtime = plannerRuntimeBuilder().build();
    Program program = runtime.createProgram(ast);
    CelFunctionResolver lateBoundResolver =
        newLateBoundSyncResolver("lateBoundSync", (Long arg) -> arg * 3);

    ListenableFuture<Object> future = program.evalAsync(ImmutableMap.of(), lateBoundResolver);
    Object result = future.get(5, SECONDS);

    assertThat(result).isEqualTo(30L);
  }

  @Test
  public void evalAsync_parsedOnly_lateBoundSyncWithAsyncArgument_defersAndResolves()
      throws Exception {
    CelAbstractSyntaxTree ast = CEL_COMPILER.parse("lateBoundSync(asyncSquare(5))").getAst();
    CelRuntime runtime =
        plannerRuntimeBuilder()
            .addFunctionBindings(
                CelFunctionBinding.fromAsync(
                    "asyncSquare", Long.class, (Long arg) -> immediateFuture(arg * arg)))
            .build();
    Program program = runtime.createProgram(ast);
    CelFunctionResolver lateBoundResolver =
        newLateBoundSyncResolver("lateBoundSync", (Long arg) -> arg * 3);

    ListenableFuture<Object> future = program.evalAsync(ImmutableMap.of(), lateBoundResolver);
    Object result = future.get(5, SECONDS);

    assertThat(result).isEqualTo(75L);
  }

  @Test
  @SuppressWarnings("Immutable") // Test only
  public void evalAsync_nonStrictCustomFunction_aliasingHazard_isPrevented() throws Exception {
    CelAbstractSyntaxTree ast =
        CEL_COMPILER.compile("[1, 2].map(x, asyncNonStrictIdentity(asyncSquare(x)))").getAst();
    SettableFuture<Object> firstFuture = SettableFuture.create();
    SettableFuture<Object> secondFuture = SettableFuture.create();
    CelFunctionBinding nonStrictBinding =
        new CelFunctionBinding() {
          @Override
          public String getOverloadId() {
            return "asyncNonStrictIdentity_dyn";
          }

          @Override
          public ImmutableList<Class<?>> getArgTypes() {
            return ImmutableList.of(Object.class);
          }

          @Override
          public CelFunctionOverload getDefinition() {
            return (CelAsyncFunctionOverload)
                args -> {
                  // Should receive CelUnknownSet, not AccumulatedUnknowns!
                  Object arg = args[0];
                  if (!(arg instanceof CelUnknownSet) && !(arg instanceof Long)) {
                    return immediateFailedFuture(
                        new RuntimeException(
                            "Type leakage! Expected CelUnknownSet or Long, got: "
                                + arg.getClass().getName()));
                  }
                  return immediateFuture(arg);
                };
          }

          @Override
          public boolean isStrict() {
            return false;
          }
        };
    CelRuntime runtime =
        plannerRuntimeBuilder()
            .addFunctionBindings(
                CelFunctionBinding.fromAsync(
                    "asyncSquare_int",
                    Long.class,
                    (Long arg) -> arg == 1L ? firstFuture : secondFuture),
                nonStrictBinding)
            .build();
    Program program = runtime.createProgram(ast);

    ListenableFuture<Object> evalFuture = program.evalAsync();
    // Complete them one by one. If aliasing is present, completing the first will corrupt the
    // second.
    firstFuture.set(1L);
    secondFuture.set(4L);
    Object result = evalFuture.get(5, SECONDS);

    assertThat((Iterable<?>) result).containsExactly(1L, 4L).inOrder();
  }

  @Test
  @SuppressWarnings("Immutable") // Test only
  public void evalAsync_highConcurrency_limitsInFlightUnderHeavyLoad() throws Exception {
    ListeningExecutorService pool = listeningDecorator(Executors.newFixedThreadPool(16));
    try {
      AtomicInteger currentInFlight = new AtomicInteger();
      AtomicInteger maxObservedInFlight = new AtomicInteger();
      AtomicInteger totalLaunches = new AtomicInteger();
      Random random = new Random(42);
      int totalCalls = 40;
      StringBuilder exprBuilder = new StringBuilder("[");
      for (int i = 0; i < totalCalls; i++) {
        if (i > 0) {
          exprBuilder.append(", ");
        }
        exprBuilder.append("asyncInc(").append(i).append(")");
      }
      exprBuilder.append("]");
      CelAsyncEvaluationOptions options =
          CelAsyncEvaluationOptions.builder()
              .setMaxConcurrency(5)
              .setDrainStrategy(CelAsyncDrainStrategy.drainReady(Duration.ofMillis(1)))
              .build();
      CelFunctionBinding asyncInc =
          CelFunctionBinding.fromAsync(
              "asyncInc_int",
              Long.class,
              (Long arg) ->
                  pool.submit(
                      () -> {
                        totalLaunches.incrementAndGet();
                        int active = currentInFlight.incrementAndGet();
                        maxObservedInFlight.accumulateAndGet(active, Math::max);
                        try {
                          Thread.sleep(random.nextInt(4) + 1);
                        } catch (InterruptedException e) {
                          Thread.currentThread().interrupt();
                        } finally {
                          currentInFlight.decrementAndGet();
                        }
                        return arg + 1;
                      }));
      Program program =
          plannerRuntimeBuilder()
              .setAsyncExecutor(pool)
              .setAsyncEvaluationOptions(options)
              .addFunctionBindings(asyncInc)
              .build()
              .createProgram(CEL_COMPILER.compile(exprBuilder.toString()).getAst());

      Object result = program.evalAsync().get(10, SECONDS);

      ImmutableList<Long> expected =
          LongStream.range(1, totalCalls + 1).boxed().collect(toImmutableList());
      assertThat((List<?>) result).containsExactlyElementsIn(expected).inOrder();
      assertThat(totalLaunches.get()).isEqualTo(totalCalls);
      assertThat(maxObservedInFlight.get()).isAtLeast(1);
      assertThat(maxObservedInFlight.get()).isAtMost(5);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  @SuppressWarnings("Immutable") // Test only
  public void evalAsync_highConcurrency_drainAllBatchesAllCompletionsUnderHeavyLoad()
      throws Exception {
    ListeningExecutorService pool = listeningDecorator(Executors.newFixedThreadPool(16));
    try {
      AtomicInteger currentInFlight = new AtomicInteger();
      AtomicInteger maxObservedInFlight = new AtomicInteger();
      AtomicInteger totalLaunches = new AtomicInteger();
      Random random = new Random(42);
      int totalCalls = 30;
      StringBuilder exprBuilder = new StringBuilder("[");
      for (int i = 0; i < totalCalls; i++) {
        if (i > 0) {
          exprBuilder.append(", ");
        }
        exprBuilder.append("asyncInc(").append(i).append(")");
      }
      exprBuilder.append("]");
      CelAsyncEvaluationOptions options =
          CelAsyncEvaluationOptions.builder()
              .setMaxConcurrency(4)
              .setDrainStrategy(CelAsyncDrainStrategy.drainAll())
              .build();
      CelFunctionBinding asyncInc =
          CelFunctionBinding.fromAsync(
              "asyncInc_int",
              Long.class,
              (Long arg) ->
                  pool.submit(
                      () -> {
                        totalLaunches.incrementAndGet();
                        int active = currentInFlight.incrementAndGet();
                        maxObservedInFlight.accumulateAndGet(active, Math::max);
                        try {
                          Thread.sleep(random.nextInt(3) + 1);
                        } catch (InterruptedException e) {
                          Thread.currentThread().interrupt();
                        } finally {
                          currentInFlight.decrementAndGet();
                        }
                        return arg + 1;
                      }));
      Program program =
          plannerRuntimeBuilder()
              .setAsyncExecutor(pool)
              .setAsyncEvaluationOptions(options)
              .addFunctionBindings(asyncInc)
              .build()
              .createProgram(CEL_COMPILER.compile(exprBuilder.toString()).getAst());

      Object result = program.evalAsync().get(10, SECONDS);

      ImmutableList<Long> expected =
          LongStream.range(1, totalCalls + 1).boxed().collect(toImmutableList());
      assertThat((List<?>) result).containsExactlyElementsIn(expected).inOrder();
      assertThat(totalLaunches.get()).isEqualTo(totalCalls);
      assertThat(maxObservedInFlight.get()).isAtLeast(1);
      assertThat(maxObservedInFlight.get()).isAtMost(4);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  @SuppressWarnings("Immutable") // Test only
  public void evalAsync_concurrentProgramInvocations_highLoadThreadSafety() throws Exception {
    ListeningExecutorService pool = listeningDecorator(Executors.newFixedThreadPool(16));
    try {
      int threadCount = 20;
      ExecutorService callerPool = Executors.newFixedThreadPool(threadCount);
      try {
        Random random = new Random(42);
        CelFunctionBinding asyncSquareWithDelay =
            CelFunctionBinding.fromAsync(
                "asyncSquare_int",
                Long.class,
                (Long arg) ->
                    pool.submit(
                        () -> {
                          try {
                            Thread.sleep(random.nextInt(3) + 1);
                          } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                          }
                          return arg * arg;
                        }));
        Program program =
            plannerRuntimeBuilder()
                .setAsyncExecutor(pool)
                .addFunctionBindings(asyncSquareWithDelay)
                .build()
                .createProgram(CEL_COMPILER.compile("asyncSquare(x) + 1").getAst());
        CountDownLatch startLatch = new CountDownLatch(1);
        List<Future<Long>> futures = new ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
          long xVal = (long) (i + 1) * 3;
          futures.add(
              callerPool.submit(
                  () -> {
                    startLatch.await();
                    Object res = program.evalAsync(ImmutableMap.of("x", xVal)).get(10, SECONDS);
                    return (Long) res;
                  }));
        }

        startLatch.countDown();

        for (int i = 0; i < threadCount; i++) {
          long xVal = (long) (i + 1) * 3;
          long expected = (xVal * xVal) + 1;
          assertThat(futures.get(i).get(10, SECONDS)).isEqualTo(expected);
        }
      } finally {
        callerPool.shutdownNow();
      }
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  @SuppressWarnings("Immutable") // Test only
  public void evalAsync_staggeredDelaysWithDrainReady_evaluatesSuccessfully() throws Exception {
    ListeningExecutorService pool = listeningDecorator(Executors.newFixedThreadPool(4));
    try {
      CelFunctionBinding delayedRpc =
          CelFunctionBinding.fromAsync(
              "delayedRpc_string_int",
              String.class,
              Long.class,
              (String msg, Long delayMs) ->
                  pool.submit(
                      () -> {
                        try {
                          Thread.sleep(delayMs);
                        } catch (InterruptedException e) {
                          Thread.currentThread().interrupt();
                        }
                        return msg;
                      }));
      CelAsyncEvaluationOptions options =
          CelAsyncEvaluationOptions.builder()
              .setDrainStrategy(CelAsyncDrainStrategy.drainReady(Duration.ofMillis(30)))
              .build();
      Program program =
          plannerRuntimeBuilder()
              .setAsyncExecutor(pool)
              .setAsyncEvaluationOptions(options)
              .addFunctionBindings(delayedRpc)
              .build()
              .createProgram(
                  CEL_COMPILER
                      .compile("delayedRpc('a', 5) + delayedRpc('b', 15) + delayedRpc('c', 40)")
                      .getAst());

      Object result = program.evalAsync().get(10, SECONDS);

      assertThat(result).isEqualTo("abc");
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  @SuppressWarnings("Immutable") // Test only
  public void evalAsync_comprehension_outOfOrderCompletion_preservesElementOrdering()
      throws Exception {
    ListeningExecutorService pool = listeningDecorator(Executors.newFixedThreadPool(8));
    try {
      Random random = new Random(42);
      int elementCount = 20;
      ImmutableList<Long> inputList =
          LongStream.range(0, elementCount).boxed().collect(toImmutableList());
      CelFunctionBinding asyncIncWithDelay =
          CelFunctionBinding.fromAsync(
              "asyncInc_int",
              Long.class,
              (Long arg) ->
                  pool.submit(
                      () -> {
                        try {
                          Thread.sleep(random.nextInt(4) + 1);
                        } catch (InterruptedException e) {
                          Thread.currentThread().interrupt();
                        }
                        return arg + 1;
                      }));
      CelAsyncEvaluationOptions options =
          CelAsyncEvaluationOptions.builder()
              .setDrainStrategy(CelAsyncDrainStrategy.drainReady(Duration.ofMillis(1)))
              .build();
      Program program =
          plannerRuntimeBuilder()
              .setAsyncExecutor(pool)
              .setAsyncEvaluationOptions(options)
              .addFunctionBindings(asyncIncWithDelay)
              .build()
              .createProgram(CEL_COMPILER.compile("list_var.map(x, asyncInc(x))").getAst());

      Object result = program.evalAsync(ImmutableMap.of("list_var", inputList)).get(10, SECONDS);

      ImmutableList<Long> expected =
          LongStream.range(1, elementCount + 1).boxed().collect(toImmutableList());
      assertThat((List<?>) result).containsExactlyElementsIn(expected).inOrder();
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  @SuppressWarnings("Immutable") // Test only
  public void evalAsync_comprehension_limitsInFlightUnderHeavyLoad() throws Exception {
    ListeningExecutorService pool = listeningDecorator(Executors.newFixedThreadPool(16));
    try {
      AtomicInteger currentInFlight = new AtomicInteger();
      AtomicInteger maxObservedInFlight = new AtomicInteger();
      AtomicInteger totalLaunches = new AtomicInteger();
      Random random = new Random(42);
      int totalCalls = 30;
      ImmutableList<Long> inputList =
          LongStream.range(0, totalCalls).boxed().collect(toImmutableList());
      CelAsyncEvaluationOptions options =
          CelAsyncEvaluationOptions.builder()
              .setMaxConcurrency(4)
              .setDrainStrategy(CelAsyncDrainStrategy.drainReady(Duration.ofMillis(1)))
              .build();
      CelFunctionBinding asyncInc =
          CelFunctionBinding.fromAsync(
              "asyncInc_int",
              Long.class,
              (Long arg) ->
                  pool.submit(
                      () -> {
                        totalLaunches.incrementAndGet();
                        int active = currentInFlight.incrementAndGet();
                        maxObservedInFlight.accumulateAndGet(active, Math::max);
                        try {
                          Thread.sleep(random.nextInt(3) + 1);
                        } catch (InterruptedException e) {
                          Thread.currentThread().interrupt();
                        } finally {
                          currentInFlight.decrementAndGet();
                        }
                        return arg + 1;
                      }));
      Program program =
          plannerRuntimeBuilder()
              .setAsyncExecutor(pool)
              .setAsyncEvaluationOptions(options)
              .addFunctionBindings(asyncInc)
              .build()
              .createProgram(CEL_COMPILER.compile("list_var.map(x, asyncInc(x))").getAst());

      Object result = program.evalAsync(ImmutableMap.of("list_var", inputList)).get(10, SECONDS);

      ImmutableList<Long> expected =
          LongStream.range(1, totalCalls + 1).boxed().collect(toImmutableList());
      assertThat((List<?>) result).containsExactlyElementsIn(expected).inOrder();
      assertThat(totalLaunches.get()).isEqualTo(totalCalls);
      assertThat(maxObservedInFlight.get()).isAtLeast(1);
      assertThat(maxObservedInFlight.get()).isAtMost(4);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  @SuppressWarnings("Immutable") // Test only
  public void evalAsync_comprehension_shortCircuitCancelsInFlightTasks() throws Exception {
    ListeningExecutorService pool = listeningDecorator(Executors.newFixedThreadPool(8));
    try {
      CopyOnWriteArrayList<SettableFuture<Object>> inFlightSiblings = new CopyOnWriteArrayList<>();
      int totalCalls = 20;
      ImmutableList<Long> inputList =
          LongStream.range(0, totalCalls).boxed().collect(toImmutableList());
      CelFunctionBinding asyncIsEven =
          CelFunctionBinding.fromAsync(
              "asyncIsEven_int",
              Long.class,
              (Long arg) -> {
                if (arg == 2L) {
                  return pool.submit(() -> true);
                }
                SettableFuture<Object> pendingFuture = SettableFuture.create();
                inFlightSiblings.add(pendingFuture);
                return pendingFuture;
              });
      Program program =
          plannerRuntimeBuilder()
              .setAsyncExecutor(pool)
              .addFunctionBindings(asyncIsEven)
              .build()
              .createProgram(CEL_COMPILER.compile("list_var.exists(x, asyncIsEven(x))").getAst());

      Object result = program.evalAsync(ImmutableMap.of("list_var", inputList)).get(5, SECONDS);

      assertThat(result).isEqualTo(true);
      assertThat(inFlightSiblings).hasSize(totalCalls - 1);
      for (SettableFuture<Object> sibling : inFlightSiblings) {
        assertThat(sibling.isCancelled()).isTrue();
      }
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  @SuppressWarnings("Immutable") // Test only
  public void evalAsync_concurrentProgramInvocations_comprehensionThreadSafety() throws Exception {
    ListeningExecutorService pool = listeningDecorator(Executors.newFixedThreadPool(16));
    try {
      int threadCount = 20;
      ExecutorService callerPool = Executors.newFixedThreadPool(threadCount);
      try {
        Random random = new Random(42);
        CelFunctionBinding asyncSquareWithDelay =
            CelFunctionBinding.fromAsync(
                "asyncSquare_int",
                Long.class,
                (Long arg) ->
                    pool.submit(
                        () -> {
                          try {
                            Thread.sleep(random.nextInt(3) + 1);
                          } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                          }
                          return arg * arg;
                        }));
        Program program =
            plannerRuntimeBuilder()
                .setAsyncExecutor(pool)
                .addFunctionBindings(asyncSquareWithDelay)
                .build()
                .createProgram(CEL_COMPILER.compile("list_var.map(x, asyncSquare(x))").getAst());
        CountDownLatch startLatch = new CountDownLatch(1);
        List<Future<List<?>>> futures = new ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
          long base = (long) (i + 1);
          ImmutableList<Long> inputList = ImmutableList.of(base, base + 1, base + 2);
          futures.add(
              callerPool.submit(
                  () -> {
                    startLatch.await();
                    return (List<?>)
                        program.evalAsync(ImmutableMap.of("list_var", inputList)).get(10, SECONDS);
                  }));
        }

        startLatch.countDown();

        for (int i = 0; i < threadCount; i++) {
          long base = (long) (i + 1);
          ImmutableList<Long> expected =
              ImmutableList.of(base * base, (base + 1) * (base + 1), (base + 2) * (base + 2));
          assertThat(futures.get(i).get(10, SECONDS)).containsExactlyElementsIn(expected).inOrder();
        }
      } finally {
        callerPool.shutdownNow();
      }
    } finally {
      pool.shutdownNow();
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

  private static CelFunctionResolver newLateBoundAsyncResolver(
      String functionName, CelAsyncFunctionOverload.Unary<Long> function) {
    return new CelFunctionResolver() {
      @Override
      public Optional<CelResolvedOverload> findOverloadMatchingArgs(
          String functionNameArg, Collection<String> overloadIds, Object[] args) {
        if (functionNameArg.equals(functionName) && args.length == 1 && args[0] instanceof Long) {
          return Optional.of(
              CelResolvedOverload.of(
                  functionName,
                  functionName + "_int",
                  CelFunctionBinding.fromAsync(functionName + "_int", Long.class, function)
                      .getDefinition(),
                  /* isStrict= */ true,
                  Long.class));
        }
        return Optional.empty();
      }

      @Override
      public Optional<CelResolvedOverload> findOverloadMatchingArgs(
          String functionNameArg, Object[] args) {
        return findOverloadMatchingArgs(functionNameArg, ImmutableList.of(), args);
      }
    };
  }

  private static CelFunctionResolver newLateBoundSyncResolver(
      String functionName, CelFunctionOverload.Unary<Long> function) {
    return new CelFunctionResolver() {
      @Override
      public Optional<CelResolvedOverload> findOverloadMatchingArgs(
          String functionNameArg, Collection<String> overloadIds, Object[] args) {
        if (functionNameArg.equals(functionName) && args.length == 1 && args[0] instanceof Long) {
          return Optional.of(
              CelResolvedOverload.of(
                  functionName,
                  functionName + "_int",
                  CelFunctionBinding.from(functionName + "_int", Long.class, function)
                      .getDefinition(),
                  /* isStrict= */ true,
                  Long.class));
        }
        return Optional.empty();
      }

      @Override
      public Optional<CelResolvedOverload> findOverloadMatchingArgs(
          String functionNameArg, Object[] args) {
        return findOverloadMatchingArgs(functionNameArg, ImmutableList.of(), args);
      }
    };
  }

  private ListeningExecutorService newTrackingExecutor(AtomicInteger tasksSubmitted) {
    return new ForwardingListeningExecutorService() {
      @Override
      protected ListeningExecutorService delegate() {
        return executor;
      }

      @Override
      public void execute(Runnable command) {
        tasksSubmitted.incrementAndGet();
        super.execute(command);
      }

      @Override
      public <T> ListenableFuture<T> submit(Callable<T> task) {
        tasksSubmitted.incrementAndGet();
        return super.submit(task);
      }
    };
  }

  @SuppressWarnings("ImmutableEnumChecker") // Test only
  private enum ComprehensionPredicateTestCase {
    EXISTS_LIST_TRUE(
        "list_var.exists(x, asyncIsEven(x))",
        ImmutableMap.of("list_var", ImmutableList.of(1L, 3L, 4L, 7L)),
        true),
    EXISTS_LIST_FALSE(
        "list_var.exists(x, asyncIsEven(x))",
        ImmutableMap.of("list_var", ImmutableList.of(1L, 3L, 5L, 7L)),
        false),
    EXISTS_LIST_EMPTY(
        "list_var.exists(x, asyncIsEven(x))",
        ImmutableMap.of("list_var", ImmutableList.of()),
        false),
    EXISTS_MAP_TRUE(
        "map_var.exists(k, asyncIsEven(map_var[k]))",
        ImmutableMap.of("map_var", ImmutableMap.of("a", 1L, "b", 4L)),
        true),
    EXISTS_MAP_FALSE(
        "map_var.exists(k, asyncIsEven(map_var[k]))",
        ImmutableMap.of("map_var", ImmutableMap.of("a", 1L, "b", 3L)),
        false),
    ALL_LIST_TRUE(
        "list_var.all(x, asyncIsEven(x))",
        ImmutableMap.of("list_var", ImmutableList.of(2L, 4L, 6L)),
        true),
    ALL_LIST_FALSE(
        "list_var.all(x, asyncIsEven(x))",
        ImmutableMap.of("list_var", ImmutableList.of(2L, 3L, 6L)),
        false),
    ALL_LIST_EMPTY(
        "list_var.all(x, asyncIsEven(x))", ImmutableMap.of("list_var", ImmutableList.of()), true),
    ALL_MAP_TRUE(
        "map_var.all(k, asyncIsEven(map_var[k]))",
        ImmutableMap.of("map_var", ImmutableMap.of("a", 2L, "b", 4L)),
        true),
    ALL_MAP_FALSE(
        "map_var.all(k, asyncIsEven(map_var[k]))",
        ImmutableMap.of("map_var", ImmutableMap.of("a", 2L, "b", 3L)),
        false),
    EXISTS_ONE_LIST_TRUE(
        "list_var.exists_one(x, asyncIsEven(x))",
        ImmutableMap.of("list_var", ImmutableList.of(1L, 4L, 7L)),
        true),
    EXISTS_ONE_LIST_FALSE(
        "list_var.exists_one(x, asyncIsEven(x))",
        ImmutableMap.of("list_var", ImmutableList.of(2L, 4L, 7L)),
        false),
    EXISTS_ONE_MAP_TRUE(
        "map_var.exists_one(k, asyncIsEven(map_var[k]))",
        ImmutableMap.of("map_var", ImmutableMap.of("a", 1L, "b", 4L)),
        true),
    EXISTS_ONE_MAP_FALSE(
        "map_var.exists_one(k, asyncIsEven(map_var[k]))",
        ImmutableMap.of("map_var", ImmutableMap.of("a", 2L, "b", 4L)),
        false);

    private final String expression;

    @SuppressWarnings("Immutable") // Test only
    private final ImmutableMap<String, Object> variables;

    private final boolean expectedResult;

    ComprehensionPredicateTestCase(
        String expression, ImmutableMap<String, Object> variables, boolean expectedResult) {
      this.expression = expression;
      this.variables = variables;
      this.expectedResult = expectedResult;
    }
  }

  @SuppressWarnings({"ImmutableEnumChecker", "Immutable"}) // Test only
  private enum ComprehensionFilterTestCase {
    LIST(
        "list_var.filter(x, asyncIsEven(x))",
        ImmutableMap.of("list_var", ImmutableList.of(1L, 2L)),
        2L),
    MAP(
        "map_var.filter(k, asyncIsEven(map_var[k]))",
        ImmutableMap.of("map_var", ImmutableMap.of("a", 1L, "b", 2L)),
        "b");

    private final String expression;

    @SuppressWarnings("Immutable") // Test only
    private final ImmutableMap<String, Object> variables;

    @SuppressWarnings("Immutable") // Test only
    private final Object expectedElement;

    ComprehensionFilterTestCase(
        String expression, ImmutableMap<String, Object> variables, Object expectedElement) {
      this.expression = expression;
      this.variables = variables;
      this.expectedElement = expectedElement;
    }
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

    private RecordingObserver() {}
  }

  @Immutable
  @SuppressWarnings("Immutable") // Test only
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
