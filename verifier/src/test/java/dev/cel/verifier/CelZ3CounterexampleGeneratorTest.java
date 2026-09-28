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

package dev.cel.verifier;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.primitives.UnsignedLong;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
// import com.google.testing.testsize.MediumTest;
import com.microsoft.z3.ArrayExpr;
import com.microsoft.z3.Context;
import com.microsoft.z3.Expr;
import com.microsoft.z3.FuncDecl;
import com.microsoft.z3.Model;
import com.microsoft.z3.Solver;
import com.microsoft.z3.Sort;
import com.microsoft.z3.Status;
import com.microsoft.z3.UninterpretedSort;
import dev.cel.bundle.Cel;
import dev.cel.bundle.CelFactory;
import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.CelContainer;
import dev.cel.common.types.CelTypeProvider;
import dev.cel.common.types.ListType;
import dev.cel.common.types.MapType;
import dev.cel.common.types.OptionalType;
import dev.cel.common.types.ProtoMessageTypeProvider;
import dev.cel.common.types.SimpleType;
import dev.cel.common.types.StructTypeReference;
import dev.cel.common.values.CelByteString;
import dev.cel.common.values.CelValueProvider;
import dev.cel.expr.conformance.proto3.TestAllTypes;
import dev.cel.extensions.CelOptionalLibrary;
import dev.cel.verifier.CelCounterexample.Binding;
import dev.cel.verifier.CelVerificationResult.VerificationStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.Test;
import org.junit.runner.RunWith;

// @MediumTest
@RunWith(TestParameterInjector.class)
public final class CelZ3CounterexampleGeneratorTest {

  private static final CelTypeProvider TYPE_PROVIDER =
      ProtoMessageTypeProvider.newBuilder()
          .addDescriptors(ImmutableList.of(TestAllTypes.getDescriptor()))
          .build();

  @Test
  public void structuredCounterexample_primitiveTypes() throws Exception {
    Cel cel =
        CelFactory.plannerCelBuilder()
            .addVar("i", SimpleType.INT)
            .addVar("u", SimpleType.UINT)
            .addVar("d", SimpleType.DOUBLE)
            .addVar("b", SimpleType.BOOL)
            .addVar("s", SimpleType.STRING)
            .addVar("bytes_val", SimpleType.BYTES)
            .addVar("ts", SimpleType.TIMESTAMP)
            .addVar("dur", SimpleType.DURATION)
            .build();
    CelAbstractSyntaxTree ast =
        cel.compile(
                "i == -42 && u == 100u && d == 3.14 && b == true && s == 'admin' && bytes_val =="
                    + " b'foo' && ts == timestamp(1767225600) && dur == (timestamp(10) -"
                    + " timestamp(0))")
            .getAst();

    CelVerifier verifier = CelVerifierFactory.newVerifier(cel).build();
    CelVerificationResult result = verifier.isSatisfiable(ast);

    assertThat(result.status()).isEqualTo(VerificationStatus.VERIFIED);
    assertThat(result.counterexampleModel()).isPresent();
    CelCounterexample ce = result.counterexampleModel().get();

    assertThat(ce.get("i")).hasValue(Binding.of("i", SimpleType.INT, -42L, "-42"));
    assertThat(ce.get("u"))
        .hasValue(Binding.of("u", SimpleType.UINT, UnsignedLong.fromLongBits(100), "100u"));
    assertThat(ce.get("d")).hasValue(Binding.of("d", SimpleType.DOUBLE, 3.14, "3.14"));
    assertThat(ce.get("b")).hasValue(Binding.of("b", SimpleType.BOOL, true, "true"));
    assertThat(ce.get("s")).hasValue(Binding.of("s", SimpleType.STRING, "admin", "\"admin\""));
    assertThat(ce.get("bytes_val"))
        .hasValue(
            Binding.of(
                "bytes_val", SimpleType.BYTES, CelByteString.copyFromUtf8("foo"), "b\"foo\""));
    assertThat(ce.get("ts"))
        .hasValue(
            Binding.of(
                "ts",
                SimpleType.TIMESTAMP,
                Instant.ofEpochSecond(1767225600),
                "timestamp(1767225600)"));
    assertThat(ce.get("dur"))
        .hasValue(
            Binding.of("dur", SimpleType.DURATION, Duration.ofSeconds(10), "duration('10s')"));

    // Test direct evaluation context with CelRuntime
    ImmutableMap<String, Object> evalContext = ce.toEvaluationContext();
    Object evalResult = cel.createProgram(ast).eval(evalContext);
    assertThat(evalResult).isEqualTo(true);
  }

  @Test
  public void structuredCounterexample_collectionTypes() throws Exception {
    Cel cel =
        CelFactory.plannerCelBuilder()
            .addVar("l", ListType.create(SimpleType.INT))
            .addVar("m", MapType.create(SimpleType.STRING, SimpleType.INT))
            .build();
    CelAbstractSyntaxTree ast =
        cel.compile("l == [1, 2, 3] && m == {'key1': 10, 'key2': 20}").getAst();

    CelVerifier verifier = CelVerifierFactory.newVerifier(cel).build();
    CelVerificationResult result = verifier.isSatisfiable(ast);

    assertThat(result.status()).isEqualTo(VerificationStatus.VERIFIED);
    assertThat(result.counterexampleModel()).isPresent();
    CelCounterexample ce = result.counterexampleModel().get();

    assertThat(ce.get("l"))
        .hasValue(
            Binding.of(
                "l", ListType.create(SimpleType.INT), ImmutableList.of(1L, 2L, 3L), "[1, 2, 3]"));
    assertThat(ce.get("m"))
        .hasValue(
            Binding.of(
                "m",
                MapType.create(SimpleType.STRING, SimpleType.INT),
                ImmutableMap.of("key1", 10L, "key2", 20L),
                "{\"key1\": 10, \"key2\": 20}"));

    // Test evaluation context execution
    Object evalResult = cel.createProgram(ast).eval(ce.toEvaluationContext());
    assertThat(evalResult).isEqualTo(true);
  }

  @Test
  public void structuredCounterexample_optionalTypes() throws Exception {
    Cel cel =
        CelFactory.plannerCelBuilder()
            .addCompilerLibraries(CelOptionalLibrary.INSTANCE)
            .addRuntimeLibraries(CelOptionalLibrary.INSTANCE)
            .addVar("opt_val", OptionalType.create(SimpleType.INT))
            .addVar("opt_empty", OptionalType.create(SimpleType.INT))
            .build();
    CelAbstractSyntaxTree ast =
        cel.compile("opt_val == optional.of(42) && opt_empty == optional.none()").getAst();

    CelVerifier verifier = CelVerifierFactory.newVerifier(cel).build();
    CelVerificationResult result = verifier.isSatisfiable(ast);

    assertThat(result.status()).isEqualTo(VerificationStatus.VERIFIED);
    assertThat(result.counterexampleModel()).isPresent();
    CelCounterexample ce = result.counterexampleModel().get();

    assertThat(ce.get("opt_val"))
        .hasValue(
            Binding.of(
                "opt_val", OptionalType.create(SimpleType.INT), Optional.of(42L), "optional(42)"));
    assertThat(ce.get("opt_empty"))
        .hasValue(
            Binding.of(
                "opt_empty",
                OptionalType.create(SimpleType.DYN),
                Optional.empty(),
                "optional.none()"));
  }

  @Test
  public void structuredCounterexample_protoMessage() throws Exception {
    Cel cel =
        CelFactory.plannerCelBuilder()
            .setContainer(CelContainer.ofName("cel.expr.conformance.proto3"))
            .addMessageTypes(TestAllTypes.getDescriptor())
            .setTypeProvider(TYPE_PROVIDER)
            .addVar("msg", StructTypeReference.create(TestAllTypes.getDescriptor().getFullName()))
            .build();
    CelAbstractSyntaxTree ast =
        cel.compile("msg == TestAllTypes{single_int32: 80, single_string: 'admin'}").getAst();

    CelVerifier verifier =
        CelVerifierFactory.newVerifier(cel).setTypeProvider(TYPE_PROVIDER).build();
    CelVerificationResult result = verifier.isSatisfiable(ast);

    assertThat(result.status()).isEqualTo(VerificationStatus.VERIFIED);
    assertThat(result.counterexampleModel()).isPresent();
    CelCounterexample ce = result.counterexampleModel().get();
    TestAllTypes expectedProto =
        TestAllTypes.newBuilder().setSingleInt32(80).setSingleString("admin").build();
    assertThat(ce.get("msg"))
        .hasValue(
            Binding.of(
                "msg",
                StructTypeReference.create("cel.expr.conformance.proto3.TestAllTypes"),
                expectedProto,
                "cel.expr.conformance.proto3.TestAllTypes{single_string: \"admin\", single_int32:"
                    + " 80}"));
    assertThat(cel.createProgram(ast).eval(ce.toEvaluationContext())).isEqualTo(true);
  }

  @Test
  public void structuredCounterexample_stringEdgeCases() throws Exception {
    Cel cel =
        CelFactory.plannerCelBuilder()
            .addVar("s_empty", SimpleType.STRING)
            .addVar("s_quote", SimpleType.STRING)
            .addVar("s_single", SimpleType.STRING)
            .build();
    CelAbstractSyntaxTree ast =
        cel.compile("s_empty == '' && s_quote == '\"' && s_single == 'a'").getAst();

    CelVerifier verifier = CelVerifierFactory.newVerifier(cel).build();
    CelVerificationResult result = verifier.isSatisfiable(ast);

    assertThat(result.status()).isEqualTo(VerificationStatus.VERIFIED);
    assertThat(result.counterexampleModel()).isPresent();
    CelCounterexample ce = result.counterexampleModel().get();

    assertThat(ce.get("s_empty")).hasValue(Binding.of("s_empty", SimpleType.STRING, "", "\"\""));
    assertThat(ce.get("s_single"))
        .hasValue(Binding.of("s_single", SimpleType.STRING, "a", "\"a\""));
    assertThat(ce.get("s_quote"))
        .hasValue(Binding.of("s_quote", SimpleType.STRING, "\"", "\"\"\"\""));
  }

  @Test
  public void structuredCounterexample_doubleValues() throws Exception {
    Cel cel =
        CelFactory.plannerCelBuilder()
            .addVar("d_pi", SimpleType.DOUBLE)
            .addVar("d_val", SimpleType.DOUBLE)
            .build();
    CelAbstractSyntaxTree ast = cel.compile("d_pi == 3.14159 && d_val == 2.5").getAst();

    CelVerifier verifier = CelVerifierFactory.newVerifier(cel).build();
    CelVerificationResult result = verifier.isSatisfiable(ast);

    assertThat(result.status()).isEqualTo(VerificationStatus.VERIFIED);
    assertThat(result.counterexampleModel()).isPresent();
    CelCounterexample ce = result.counterexampleModel().get();

    assertThat(ce.get("d_pi")).hasValue(Binding.of("d_pi", SimpleType.DOUBLE, 3.14159, "3.14159"));
    assertThat(ce.get("d_val")).hasValue(Binding.of("d_val", SimpleType.DOUBLE, 2.5, "2.5"));
  }

  @Test
  public void structuredCounterexample_listTruncation_printsEllipsis() throws Exception {
    Cel cel =
        CelFactory.plannerCelBuilder().addVar("l_long", ListType.create(SimpleType.INT)).build();
    CelAbstractSyntaxTree ast =
        cel.compile(
                "l_long == [0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19]")
            .getAst();

    CelVerifier verifier =
        CelVerifierFactory.newVerifier(cel).setComprehensionUnrollLimit(25).build();
    CelVerificationResult result = verifier.isSatisfiable(ast);

    assertThat(result.status()).isEqualTo(VerificationStatus.VERIFIED);
    assertThat(result.counterexampleModel()).isPresent();
    CelCounterexample ce = result.counterexampleModel().get();

    assertThat(ce.get("l_long").map(Binding::celString))
        .hasValue("[0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, ... (5 more elements)]");
    assertThat(ce.get("l_long").flatMap(Binding::nativeValue))
        .hasValue(
            ImmutableList.of(
                0L, 1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L, 11L, 12L, 13L, 14L, 15L, 16L, 17L, 18L,
                19L));
  }

  @Test
  public void structuredCounterexample_mapWithDifferentKeyTypes() throws Exception {
    Cel cel =
        CelFactory.plannerCelBuilder()
            .addVar("m_int", MapType.create(SimpleType.INT, SimpleType.STRING))
            .addVar("m_uint", MapType.create(SimpleType.UINT, SimpleType.BOOL))
            .addVar("m_bool", MapType.create(SimpleType.BOOL, SimpleType.INT))
            .build();
    CelAbstractSyntaxTree ast =
        cel.compile(
                "m_int == {1: 'one', 2: 'two'} && m_uint == {10u: true, 20u: false} && m_bool =="
                    + " {true: 100, false: 200}")
            .getAst();

    CelVerifier verifier = CelVerifierFactory.newVerifier(cel).build();
    CelVerificationResult result = verifier.isSatisfiable(ast);

    assertThat(result.status()).isEqualTo(VerificationStatus.VERIFIED);
    assertThat(result.counterexampleModel()).isPresent();
    CelCounterexample ce = result.counterexampleModel().get();

    assertThat(ce.get("m_int").flatMap(Binding::nativeValue))
        .hasValue(ImmutableMap.of(1L, "one", 2L, "two"));
    assertThat(ce.get("m_uint").flatMap(Binding::nativeValue))
        .hasValue(
            ImmutableMap.of(
                UnsignedLong.fromLongBits(10), true, UnsignedLong.fromLongBits(20), false));
    assertThat(ce.get("m_bool").flatMap(Binding::nativeValue))
        .hasValue(ImmutableMap.of(true, 100L, false, 200L));
    assertThat(cel.createProgram(ast).eval(ce.toEvaluationContext())).isEqualTo(true);
  }

  @Test
  public void structuredCounterexample_nullType() throws Exception {
    Cel cel = CelFactory.plannerCelBuilder().addVar("n", SimpleType.NULL_TYPE).build();
    CelAbstractSyntaxTree ast = cel.compile("n == null").getAst();

    CelVerifier verifier = CelVerifierFactory.newVerifier(cel).build();
    CelVerificationResult result = verifier.isSatisfiable(ast);

    assertThat(result.status()).isEqualTo(VerificationStatus.VERIFIED);
    assertThat(result.counterexampleModel()).isPresent();
    CelCounterexample ce = result.counterexampleModel().get();

    assertThat(ce.get("n")).hasValue(Binding.of("n", SimpleType.NULL_TYPE, null, "null"));
  }

  @Test
  public void structuredCounterexample_unconditionalSatisfiableAndFailing() throws Exception {
    Cel cel = CelFactory.plannerCelBuilder().build();

    CelVerifier verifier = CelVerifierFactory.newVerifier(cel).build();

    // Satisfiable without any variable inputs
    CelVerificationResult satResult = verifier.isSatisfiable(cel.compile("true").getAst());
    assertThat(satResult.status()).isEqualTo(VerificationStatus.VERIFIED);
    assertThat(satResult.message())
        .contains("The expression is satisfiable unconditionally, regardless of input state");

    // Unconditional violation without any variable inputs
    CelVerificationResult failResult = verifier.isAlwaysTrue(cel.compile("false").getAst());
    assertThat(failResult.status()).isEqualTo(VerificationStatus.VIOLATED);
    assertThat(failResult.message())
        .contains("The expression fails unconditionally, regardless of input state");
  }

  @Test
  public void cegarRefinement_tryCegarRefinement_mismatchedOrSpuriousModel_returnsSpurious()
      throws Exception {
    Cel cel = CelFactory.plannerCelBuilder().addVar("x", SimpleType.INT).build();
    CegarRefiner refiner = new CegarRefiner(cel);

    CelAbstractSyntaxTree astA = cel.compile("x == 1").getAst();
    CelAbstractSyntaxTree astB = cel.compile("x == 2").getAst();
    CelAbstractSyntaxTree astFailing = cel.compile("x != 1").getAst();
    CelAbstractSyntaxTree astPassing = cel.compile("x == 1").getAst();

    CelCounterexample satisfyingModel =
        CelCounterexample.create(
            ImmutableMap.of("x", Binding.of("x", SimpleType.INT, 1L, "1")),
            /* isApproximate= */ false,
            /* isSatisfyingInput= */ true,
            "");
    CelCounterexample counterexampleModel =
        CelCounterexample.create(
            ImmutableMap.of("x", Binding.of("x", SimpleType.INT, 1L, "1")),
            /* isApproximate= */ false,
            /* isSatisfyingInput= */ false,
            "");

    assertThat(refiner.refineEquivalence(astA, astB, satisfyingModel).isViolation()).isFalse();

    // Mismatched satisfying flag returns spurious
    assertThat(
            refiner
                .refineSatisfiability(
                    astFailing, /* searchForCounterexample= */ true, satisfyingModel)
                .isViolation())
        .isFalse();
    assertThat(
            refiner
                .refineSatisfiability(
                    astPassing, /* searchForCounterexample= */ false, counterexampleModel)
                .isViolation())
        .isFalse();

    // Passing AST evaluated against candidate counterexample (x=1 -> 1==1 is true) refutes the
    // counterexample as spurious
    assertThat(
            refiner
                .refineSatisfiability(
                    astPassing, /* searchForCounterexample= */ true, counterexampleModel)
                .isViolation())
        .isFalse();

    // Failing AST evaluated against candidate counterexample (x=1 -> 1!=1 is false) confirms
    // violation
    assertThat(
            refiner
                .refineSatisfiability(
                    astFailing, /* searchForCounterexample= */ true, counterexampleModel)
                .isViolation())
        .isTrue();

    assertThat(
            refiner.refineImplication(astA, astB, ImmutableMap.of(), satisfyingModel).isViolation())
        .isFalse();
  }

  @Test
  public void cegarRefinement_refineImplication_premiseFails_returnsSpurious() throws Exception {
    Cel cel = CelFactory.plannerCelBuilder().addVar("x", SimpleType.INT).build();
    CegarRefiner refiner = new CegarRefiner(cel);

    CelAbstractSyntaxTree assumeAst = cel.compile("x > 5").getAst();
    CelAbstractSyntaxTree assertAst = cel.compile("x > 3").getAst();

    // Candidate model does not satisfy the assumption (x=1 -> x > 5 is false).
    // Premise did not hold, so the model cannot serve as a counterexample.
    CelCounterexample premiseFailsModel =
        CelCounterexample.create(
            ImmutableMap.of("x", Binding.of("x", SimpleType.INT, 1L, "1")),
            /* isApproximate= */ false,
            /* isSatisfyingInput= */ false,
            "");
    assertThat(
            refiner
                .refineImplication(assumeAst, assertAst, ImmutableMap.of(), premiseFailsModel)
                .isViolation())
        .isFalse();
  }

  @Test
  public void cegarRefinement_refineImplication_premiseAndAssertionHold_returnsSpurious()
      throws Exception {
    Cel cel = CelFactory.plannerCelBuilder().addVar("x", SimpleType.INT).build();
    CegarRefiner refiner = new CegarRefiner(cel);

    CelAbstractSyntaxTree assumeAst = cel.compile("x > 5").getAst();
    CelAbstractSyntaxTree assertAst = cel.compile("x > 3").getAst();

    // Candidate model satisfies assumption and assertion (x=10 -> x > 5 is true, x > 3 is true).
    // Candidate counterexample is refuted (spurious).
    CelCounterexample bothHoldModel =
        CelCounterexample.create(
            ImmutableMap.of("x", Binding.of("x", SimpleType.INT, 10L, "10")),
            /* isApproximate= */ false,
            /* isSatisfyingInput= */ false,
            "");
    assertThat(
            refiner
                .refineImplication(assumeAst, assertAst, ImmutableMap.of(), bothHoldModel)
                .isViolation())
        .isFalse();
  }

  @Test
  public void cegarRefinement_refineImplication_premiseHoldsAndAssertionFails_returnsViolation()
      throws Exception {
    Cel cel = CelFactory.plannerCelBuilder().addVar("x", SimpleType.INT).build();
    CegarRefiner refiner = new CegarRefiner(cel);

    CelAbstractSyntaxTree assumeAst = cel.compile("x > 5").getAst();
    CelAbstractSyntaxTree assertFailingAst = cel.compile("x < 0").getAst();

    // Candidate model satisfies assumption but violates assertion (x=10 -> x > 5 is true, x < 0 is
    // false). Valid counterexample confirmed.
    CelCounterexample violationModel =
        CelCounterexample.create(
            ImmutableMap.of("x", Binding.of("x", SimpleType.INT, 10L, "10")),
            /* isApproximate= */ false,
            /* isSatisfyingInput= */ false,
            "");
    assertThat(
            refiner
                .refineImplication(assumeAst, assertFailingAst, ImmutableMap.of(), violationModel)
                .isViolation())
        .isTrue();
  }

  @Test
  public void cegarRefinement_refineImplication_withBoundSymbols_returnsViolation()
      throws Exception {
    Cel cel =
        CelFactory.plannerCelBuilder()
            .addVar("x", SimpleType.INT)
            .addVar("y", SimpleType.INT)
            .build();
    CegarRefiner refiner = new CegarRefiner(cel);

    // Implication with boundSymbols: y := x + 1, assume y > 5, assert y > 10.
    // x=5 -> y=6 (y > 5 is true, y > 10 is false) -> violation.
    CelAbstractSyntaxTree boundSymbolAst = cel.compile("x + 1").getAst();
    CelAbstractSyntaxTree boundAssumeAst = cel.compile("y > 5").getAst();
    CelAbstractSyntaxTree boundAssertAst = cel.compile("y > 10").getAst();
    CelCounterexample boundSymbolModel =
        CelCounterexample.create(
            ImmutableMap.of("x", Binding.of("x", SimpleType.INT, 5L, "5")),
            /* isApproximate= */ false,
            /* isSatisfyingInput= */ false,
            "");
    assertThat(
            refiner
                .refineImplication(
                    boundAssumeAst,
                    boundAssertAst,
                    ImmutableMap.of("y", boundSymbolAst),
                    boundSymbolModel)
                .isViolation())
        .isTrue();
  }

  @Test
  // Generic Z3 ArrayExpr and mkStore APIs use raw ArraySort types in Java bindings.
  @SuppressWarnings({"unchecked", "rawtypes"})
  public void reconstructMap_truncation_limitsPrintedEntries() {
    Cel cel = CelFactory.plannerCelBuilder().build();
    CelValueProvider valueProvider = cel.toCelBuilder().valueProvider();

    try (Context ctx = new Context()) {
      CelZ3TypeSystem typeSystem = new CelZ3TypeSystem(ctx);
      Expr<?> mapRef = typeSystem.mkMapRefConst("test_map");

      // Construct a sequence of 18 keys (0 to 17) and presence/values arrays
      Expr<?> keysSeq = ctx.mkEmptySeq(ctx.mkSeqSort(typeSystem.celValueSort()));
      ArrayExpr presenceArray = ctx.mkConstArray(typeSystem.celValueSort(), ctx.mkFalse());
      ArrayExpr valuesArray = ctx.mkConstArray(typeSystem.celValueSort(), typeSystem.mkNull());

      for (int i = 0; i < 18; i++) {
        Expr<?> key = typeSystem.mkInt(i);
        Expr<?> val = typeSystem.mkInt(i * 10);
        keysSeq = typeSystem.mkConcatSafe(keysSeq, ctx.mkUnit(key));
        presenceArray = ctx.mkStore(presenceArray, key, ctx.mkTrue());
        valuesArray = ctx.mkStore(valuesArray, key, val);
      }

      Solver solver = ctx.mkSolver();
      solver.add(ctx.mkEq(typeSystem.getMapKeys(mapRef), keysSeq));
      solver.add(ctx.mkEq(typeSystem.getMapPresence(mapRef), presenceArray));
      solver.add(ctx.mkEq(typeSystem.getMapValues(mapRef), valuesArray));
      solver.check();
      Model model = solver.getModel();

      CelZ3CounterexampleGenerator.ExtractedNode node =
          CelZ3CounterexampleGenerator.reconstructMap(
              ctx, typeSystem, valueProvider, model, mapRef);

      assertThat(node.celString).contains("... (3 more entries)");
      assertThat(node.celString).contains("0: 0");
      assertThat(node.celString).contains("14: 140");
      // Key 15, 16, 17 must NOT be in the string preview
      assertThat(node.celString).doesNotContain("15: 150");
      assertThat(node.celString).doesNotContain("16: 160");
      assertThat(node.celString).doesNotContain("17: 170");

      // Full native map still contains all 18 entries
      assertThat((ImmutableMap<?, ?>) node.nativeValue).hasSize(18);
    }
  }

  @Test
  public void structuredCounterexample_rawZ3Terms_extractsDirectly() {
    Cel cel = CelFactory.plannerCelBuilder().build();
    CelValueProvider valueProvider = cel.toCelBuilder().valueProvider();

    try (Context ctx = new Context()) {
      CelZ3TypeSystem typeSystem = new CelZ3TypeSystem(ctx);
      Solver solver = ctx.mkSolver();

      // Create constants for raw Z3 terms
      Expr<?> rawInt = ctx.mkConst("raw_int", ctx.getIntSort());
      Expr<?> rawString = ctx.mkConst("raw_str", ctx.getStringSort());
      Expr<?> rawBool = ctx.mkConst("raw_bool", ctx.getBoolSort());
      Expr<?> rawFp = ctx.mkConst("raw_fp", ctx.mkFPSortDouble());
      Expr<?> rawFpNaN = ctx.mkConst("raw_fp_nan", ctx.mkFPSortDouble());
      Expr<?> rawFpPosInf = ctx.mkConst("raw_fp_pos_inf", ctx.mkFPSortDouble());
      Expr<?> rawFpNegInf = ctx.mkConst("raw_fp_neg_inf", ctx.mkFPSortDouble());
      Expr<?> rawFpNegZero = ctx.mkConst("raw_fp_neg_zero", ctx.mkFPSortDouble());
      Expr<?> rawNull = ctx.mkConst("raw_null", typeSystem.celValueSort());
      Expr<?> rawError = ctx.mkConst("raw_error", typeSystem.celValueSort());
      Expr<?> rawUnknown = ctx.mkConst("raw_unknown", typeSystem.celValueSort());
      Expr<?> skolemVar = ctx.mkConst("k!123", ctx.getIntSort());

      solver.add(ctx.mkEq(rawInt, ctx.mkInt(999)));
      solver.add(ctx.mkEq(rawString, ctx.mkString("hello \"world\"")));
      solver.add(ctx.mkEq(rawBool, ctx.mkTrue()));
      solver.add(ctx.mkEq(rawFp, ctx.mkFP(2.5, ctx.mkFPSortDouble())));
      solver.add(ctx.mkEq(rawFpNaN, ctx.mkFPNaN(ctx.mkFPSortDouble())));
      solver.add(ctx.mkEq(rawFpPosInf, ctx.mkFPInf(ctx.mkFPSortDouble(), false)));
      solver.add(ctx.mkEq(rawFpNegInf, ctx.mkFPInf(ctx.mkFPSortDouble(), true)));
      solver.add(ctx.mkEq(rawFpNegZero, ctx.mkFPZero(ctx.mkFPSortDouble(), true)));
      solver.add(ctx.mkEq(rawNull, typeSystem.mkNull()));
      solver.add(ctx.mkEq(rawError, typeSystem.mkError()));
      solver.add(ctx.mkEq(rawUnknown, typeSystem.mkUnknown()));
      solver.add(ctx.mkEq(skolemVar, ctx.mkInt(1)));

      Status status = solver.check();
      assertThat(status).isEqualTo(Status.SATISFIABLE);

      Model model = solver.getModel();

      // Test CelZ3CounterexampleGenerator.extract
      CelCounterexample ce =
          CelZ3CounterexampleGenerator.extract(
              ctx,
              typeSystem,
              valueProvider,
              model,
              /* isApproximate= */ false,
              /* isSatisfyingInput= */ true);

      // Verify skolem constant is filtered out
      assertThat(ce.get("k!123")).isEmpty();

      // Verify raw int
      assertThat(ce.get("raw_int")).hasValue(Binding.of("raw_int", SimpleType.INT, 999L, "999"));

      // Verify raw string with escaped quotes unquoted
      assertThat(ce.get("raw_str"))
          .hasValue(
              Binding.of(
                  "raw_str", SimpleType.STRING, "hello \"world\"", "\"hello \"\"world\"\"\""));

      // Verify raw bool
      assertThat(ce.get("raw_bool"))
          .hasValue(Binding.of("raw_bool", SimpleType.BOOL, true, "true"));

      // Verify raw double (FPNum)
      assertThat(ce.get("raw_fp")).hasValue(Binding.of("raw_fp", SimpleType.DOUBLE, 2.5, "2.5"));

      // Verify double special values: NaN, +Inf, -Inf, -0.0
      Binding nanBinding = ce.get("raw_fp_nan").get();
      assertThat(nanBinding.type()).isEqualTo(SimpleType.DOUBLE);
      assertThat(((Double) nanBinding.nativeValue().get()).isNaN()).isTrue();
      assertThat(nanBinding.celString()).isEqualTo("NaN");

      assertThat(ce.get("raw_fp_pos_inf").flatMap(Binding::nativeValue))
          .hasValue(Double.POSITIVE_INFINITY);
      assertThat(ce.get("raw_fp_pos_inf").map(Binding::celString)).hasValue("Infinity");

      assertThat(ce.get("raw_fp_neg_inf").flatMap(Binding::nativeValue))
          .hasValue(Double.NEGATIVE_INFINITY);
      assertThat(ce.get("raw_fp_neg_inf").map(Binding::celString)).hasValue("-Infinity");

      assertThat(ce.get("raw_fp_neg_zero").flatMap(Binding::nativeValue)).hasValue(-0.0);
      assertThat(ce.get("raw_fp_neg_zero").map(Binding::celString)).hasValue("-0.0");

      // Verify null, error, unknown
      assertThat(ce.get("raw_null"))
          .hasValue(Binding.of("raw_null", SimpleType.NULL_TYPE, null, "null"));

      assertThat(ce.get("raw_error"))
          .hasValue(Binding.of("raw_error", SimpleType.ERROR, null, "Error"));

      assertThat(ce.get("raw_unknown"))
          .hasValue(Binding.of("raw_unknown", SimpleType.DYN, null, "Unknown"));
    }
  }

  @Test
  public void directExtractNode_uninterpretedSorts_returnsDynFallback() {
    Cel cel = CelFactory.plannerCelBuilder().build();
    CelValueProvider valueProvider = cel.toCelBuilder().valueProvider();

    try (Context ctx = new Context()) {
      CelZ3TypeSystem typeSystem = new CelZ3TypeSystem(ctx);
      UninterpretedSort customSort = ctx.mkUninterpretedSort("CustomSort");
      Expr<?> customConst = ctx.mkConst("custom_val", customSort);

      Solver solver = ctx.mkSolver();
      solver.check();
      Model model = solver.getModel();

      CelZ3CounterexampleGenerator.ExtractedNode node =
          CelZ3CounterexampleGenerator.extractNode(
              ctx, typeSystem, valueProvider, model, customConst);
      assertThat(node.type).isEqualTo(SimpleType.DYN);
      assertThat(node.nativeValue).isNull();
      assertThat(node.celString).isEqualTo("custom_val");
    }
  }

  @Test
  public void unquoteZ3String_edgeCases() {
    assertThat(CelZ3CounterexampleGenerator.unquoteZ3String("unquoted")).isEqualTo("unquoted");
    assertThat(CelZ3CounterexampleGenerator.unquoteZ3String("")).isEmpty();
    assertThat(CelZ3CounterexampleGenerator.unquoteZ3String("\"")).isEqualTo("\"");
    assertThat(CelZ3CounterexampleGenerator.unquoteZ3String("\"\"")).isEmpty();
    assertThat(CelZ3CounterexampleGenerator.unquoteZ3String("\"hello\"")).isEqualTo("hello");
    assertThat(CelZ3CounterexampleGenerator.unquoteZ3String("\"hello \"\"world\"\"\""))
        .isEqualTo("hello \"world\"");
  }

  @Test
  public void decodeDouble_nonFpNum_returnsFallback() {
    try (Context ctx = new Context()) {
      Expr<?> uninterpretedDouble = ctx.mkConst("unresolved_double", ctx.mkFPSortDouble());
      CelZ3CounterexampleGenerator.ExtractedNode node =
          CelZ3CounterexampleGenerator.decodeDouble(ctx, uninterpretedDouble);
      assertThat(node.type).isEqualTo(SimpleType.DOUBLE);
      assertThat(node.nativeValue).isNull();
      assertThat(node.celString).isEqualTo("unresolved_double");
    }
  }

  @Test
  public void decodeOptional_noneRef_returnsNone() {
    Cel cel = CelFactory.plannerCelBuilder().build();
    CelValueProvider valueProvider = cel.toCelBuilder().valueProvider();

    try (Context ctx = new Context()) {
      CelZ3TypeSystem typeSystem = new CelZ3TypeSystem(ctx);
      Expr<?> optRef = typeSystem.mkNoneOptionalRef();
      Solver solver = ctx.mkSolver();
      solver.check();
      Model model = solver.getModel();

      CelZ3CounterexampleGenerator.ExtractedNode node =
          CelZ3CounterexampleGenerator.decodeOptional(
              ctx, typeSystem, valueProvider, model, optRef);
      assertThat(node.type).isEqualTo(OptionalType.create(SimpleType.DYN));
      assertThat((Optional<?>) node.nativeValue).isEmpty();
      assertThat(node.celString).isEqualTo("optional.none()");
    }
  }

  @Test
  public void structuredCounterexample_displayPrefixes() {
    Cel cel = CelFactory.plannerCelBuilder().build();
    CelValueProvider valueProvider = cel.toCelBuilder().valueProvider();

    try (Context ctx = new Context()) {
      CelZ3TypeSystem typeSystem = new CelZ3TypeSystem(ctx);
      Solver solver = ctx.mkSolver();
      Expr<?> x = ctx.mkConst("x", ctx.getIntSort());
      solver.add(ctx.mkEq(x, ctx.mkInt(1)));
      solver.check();
      Model model = solver.getModel();

      CelCounterexample exactSat =
          CelZ3CounterexampleGenerator.extract(
              ctx,
              typeSystem,
              valueProvider,
              model,
              /* isApproximate= */ false,
              /* isSatisfyingInput= */ true);
      assertThat(exactSat.toDisplayString()).startsWith(" Satisfying input:\n  x = 1");

      CelCounterexample approxSat =
          CelZ3CounterexampleGenerator.extract(
              ctx,
              typeSystem,
              valueProvider,
              model,
              /* isApproximate= */ true,
              /* isSatisfyingInput= */ true);
      assertThat(approxSat.toDisplayString()).startsWith(" Potential satisfying input:\n  x = 1");

      CelCounterexample exactCounter =
          CelZ3CounterexampleGenerator.extract(
              ctx,
              typeSystem,
              valueProvider,
              model,
              /* isApproximate= */ false,
              /* isSatisfyingInput= */ false);
      assertThat(exactCounter.toDisplayString()).startsWith(" Counterexample input:\n  x = 1");

      CelCounterexample approxCounter =
          CelZ3CounterexampleGenerator.extract(
              ctx,
              typeSystem,
              valueProvider,
              model,
              /* isApproximate= */ true,
              /* isSatisfyingInput= */ false);
      assertThat(approxCounter.toDisplayString())
          .startsWith(" Potential counterexample input:\n  x = 1");
    }
  }

  @Test
  public void extractNode_celUnknown_returnsUnknownNode() {
    Cel cel = CelFactory.plannerCelBuilder().build();
    CelValueProvider valueProvider = cel.toCelBuilder().valueProvider();

    try (Context ctx = new Context()) {
      CelZ3TypeSystem typeSystem = new CelZ3TypeSystem(ctx);
      Expr<?> unknownVal = typeSystem.mkUnknown();
      Solver solver = ctx.mkSolver();
      solver.check();
      Model model = solver.getModel();

      CelZ3CounterexampleGenerator.ExtractedNode node =
          CelZ3CounterexampleGenerator.extractNode(
              ctx, typeSystem, valueProvider, model, unknownVal);
      assertThat(node.type).isEqualTo(SimpleType.DYN);
      assertThat(node.nativeValue).isNull();
      assertThat(node.celString).isEqualTo("Unknown");
    }
  }

  @Test
  public void extractNode_unrecognizedConstructor_returnsFallbackDynNode() {
    Cel cel = CelFactory.plannerCelBuilder().build();
    CelValueProvider valueProvider = cel.toCelBuilder().valueProvider();

    try (Context ctx = new Context()) {
      CelZ3TypeSystem typeSystem = new CelZ3TypeSystem(ctx);
      UninterpretedSort customSort = ctx.mkUninterpretedSort("CustomSort");
      FuncDecl<?> customCons =
          ctx.mkFuncDecl("CustomCons", new Sort[] {typeSystem.celValueSort()}, customSort);
      Expr<?> customTerm = ctx.mkApp(customCons, typeSystem.mkInt(42));
      Solver solver = ctx.mkSolver();
      solver.check();
      Model model = solver.getModel();

      CelZ3CounterexampleGenerator.ExtractedNode node =
          CelZ3CounterexampleGenerator.extractNode(
              ctx, typeSystem, valueProvider, model, customTerm);
      assertThat(node.type).isEqualTo(SimpleType.DYN);
      assertThat(node.nativeValue).isNull();
      assertThat(node.celString).contains("CustomCons");
    }
  }

  @Test
  // Generic Z3 ArrayExpr and mkStore APIs use raw ArraySort types in Java bindings.
  @SuppressWarnings({"unchecked", "rawtypes"})
  public void reconstructMessage_unregisteredType_fallsBackToFieldMap() {
    Cel cel = CelFactory.plannerCelBuilder().build();
    CelValueProvider valueProvider = cel.toCelBuilder().valueProvider();

    try (Context ctx = new Context()) {
      CelZ3TypeSystem typeSystem = new CelZ3TypeSystem(ctx);
      Expr<?> msgRef = typeSystem.mkMessageRefConst("test_msg");

      Expr<?> fieldKey = ctx.mkString("field_a");
      Expr<?> fieldValue = typeSystem.mkInt(100);

      ArrayExpr presenceArray = ctx.mkConstArray(ctx.getStringSort(), ctx.mkFalse());
      presenceArray = ctx.mkStore(presenceArray, fieldKey, ctx.mkTrue());

      ArrayExpr valuesArray = ctx.mkConstArray(ctx.getStringSort(), typeSystem.mkNull());
      valuesArray = ctx.mkStore(valuesArray, fieldKey, fieldValue);

      Solver solver = ctx.mkSolver();
      solver.add(ctx.mkEq(typeSystem.getMsgTypeName(msgRef), ctx.mkString("custom.DynamicStruct")));
      solver.add(ctx.mkEq(typeSystem.getMsgPresence(msgRef), presenceArray));
      solver.add(ctx.mkEq(typeSystem.getMsgValues(msgRef), valuesArray));
      solver.check();
      Model model = solver.getModel();

      CelZ3CounterexampleGenerator.ExtractedNode node =
          CelZ3CounterexampleGenerator.reconstructMessage(
              ctx, typeSystem, valueProvider, model, msgRef);

      assertThat(node.type).isEqualTo(StructTypeReference.create("custom.DynamicStruct"));
      assertThat(node.nativeValue).isEqualTo(ImmutableMap.of("field_a", 100L));
      assertThat(node.celString).isEqualTo("custom.DynamicStruct{field_a: 100}");
    }
  }
}
