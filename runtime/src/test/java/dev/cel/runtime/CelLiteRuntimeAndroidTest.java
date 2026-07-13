// Copyright 2025 Google LLC
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
import static dev.cel.testing.compiled.CompiledExprUtils.readCheckedExpr;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.primitives.UnsignedLong;
import com.google.common.truth.Correspondence;
import com.google.protobuf.BoolValue;
import com.google.protobuf.ByteString;
import com.google.protobuf.BytesValue;
import com.google.protobuf.DoubleValue;
import com.google.protobuf.FloatValue;
import com.google.protobuf.Int32Value;
import com.google.protobuf.Int64Value;
import com.google.protobuf.StringValue;
import com.google.protobuf.UInt32Value;
import com.google.protobuf.UInt64Value;
import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import com.google.testing.junit.testparameterinjector.TestParameters;
import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.CelOptions;
import dev.cel.common.internal.ProtoTimeUtils;
import dev.cel.common.values.CelByteString;
import dev.cel.common.values.CelValueProvider;
import dev.cel.common.values.ProtoMessageLiteValueProvider;
import dev.cel.expr.conformance.proto3.NestedTestAllTypes;
import dev.cel.expr.conformance.proto3.NestedTestAllTypesCelLiteDescriptor;
import dev.cel.expr.conformance.proto3.TestAllTypes;
import dev.cel.expr.conformance.proto3.TestAllTypesCelLiteDescriptor;
import dev.cel.extensions.CelLiteExtensions;
import dev.cel.extensions.SetsFunction;
import dev.cel.runtime.standard.EqualsOperator;
import dev.cel.runtime.standard.IntFunction;
import dev.cel.runtime.standard.IntFunction.IntOverload;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(TestParameterInjector.class)
public class CelLiteRuntimeAndroidTest {
  private static final double DOUBLE_TOLERANCE = 0.00001d;
  private static final Correspondence<List<?>, List<?>> LIST_WITH_DOUBLE_TOLERANCE =
      Correspondence.from(
          (actualList, expectedList) -> {
            if (actualList == null
                || expectedList == null
                || actualList.size() != expectedList.size()) {
              return false;
            }
            for (int i = 0; i < actualList.size(); i++) {
              Object actual = actualList.get(i);
              Object expected = expectedList.get(i);

              if (actual instanceof Double && expected instanceof Double) {
                return Math.abs((Double) actual - (Double) expected) <= DOUBLE_TOLERANCE;
              } else if (!actual.equals(expected)) {
                return false;
              }
            }
            return true;
          },
          String.format(
              "has elements that are equal (with tolerance of %f for doubles)", DOUBLE_TOLERANCE));

  private static final Correspondence<Map<?, ?>, Map<?, ?>> MAP_WITH_DOUBLE_TOLERANCE =
      Correspondence.from(
          (actualMap, expectedMap) -> {
            if (actualMap == null
                || expectedMap == null
                || actualMap.size() != expectedMap.size()) {
              return false;
            }

            for (Map.Entry<?, ?> actualEntry : actualMap.entrySet()) {
              if (!expectedMap.containsKey(actualEntry.getKey())) {
                return false;
              }

              Object actualEntryValue = actualEntry.getValue();
              Object expectedEntryValue = expectedMap.get(actualEntry.getKey());
              if (actualEntryValue instanceof Double && expectedEntryValue instanceof Double) {
                return Math.abs((Double) actualEntryValue - (Double) expectedEntryValue)
                    <= DOUBLE_TOLERANCE;
              } else if (!actualEntryValue.equals(expectedEntryValue)) {
                return false;
              }
            }

            return true;
          },
          String.format(
              "has elements that are equal (with tolerance of %f for doubles)", DOUBLE_TOLERANCE));

  @Test
  public void toRuntimeBuilder_isNewInstance() {
    CelLiteRuntimeBuilder runtimeBuilder = CelLiteRuntimeFactory.newLiteRuntimeBuilder();
    CelLiteRuntime runtime = runtimeBuilder.build();

    CelLiteRuntimeBuilder newRuntimeBuilder = runtime.toRuntimeBuilder();

    assertThat(newRuntimeBuilder).isNotEqualTo(runtimeBuilder);
  }

  @Test
  public void toRuntimeBuilder_propertiesCopied() {
    CelOptions celOptions = CelOptions.current().enableCelValue(true).build();
    CelLiteRuntimeLibrary runtimeExtension =
        CelLiteExtensions.sets(celOptions, SetsFunction.INTERSECTS);
    CelValueProvider celValueProvider = ProtoMessageLiteValueProvider.newInstance();
    IntFunction intFunction = IntFunction.create(IntOverload.INT64_TO_INT64);
    EqualsOperator equalsOperator = EqualsOperator.create();
    CelLiteRuntimeBuilder runtimeBuilder =
        CelLiteRuntimeFactory.newLiteRuntimeBuilder()
            .setOptions(celOptions)
            .setStandardFunctions(intFunction, equalsOperator)
            .addFunctionBindings(
                CelFunctionBinding.from("string_isEmpty", String.class, String::isEmpty))
            .setValueProvider(celValueProvider)
            .addLibraries(runtimeExtension);
    CelLiteRuntime runtime = runtimeBuilder.build();

    LiteRuntimeImpl.Builder newRuntimeBuilder =
        (LiteRuntimeImpl.Builder) runtime.toRuntimeBuilder();

    assertThat(newRuntimeBuilder.celOptions).isEqualTo(celOptions);
    assertThat(newRuntimeBuilder.celValueProvider).isSameInstanceAs(celValueProvider);
    assertThat(newRuntimeBuilder.runtimeLibrariesBuilder.build()).containsExactly(runtimeExtension);
    assertThat(newRuntimeBuilder.standardFunctionBuilder.build())
        .containsExactly(intFunction, equalsOperator)
        .inOrder();
    assertThat(newRuntimeBuilder.customFunctionBindings).hasSize(3);
    assertThat(newRuntimeBuilder.customFunctionBindings).containsKey("string_isEmpty");
    assertThat(newRuntimeBuilder.customFunctionBindings).containsKey("list_sets_intersects_list");
    assertThat(newRuntimeBuilder.customFunctionBindings).containsKey("sets.intersects");
  }

  @Test
  public void setCelOptions_unallowedOptionsSet_throws(@TestParameter CelOptionsTestCase testCase) {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CelLiteRuntimeFactory.newLiteRuntimeBuilder().setOptions(testCase.celOptions).build());
  }

  @Test
  public void standardEnvironment_disabledByDefault() throws Exception {
    CelLiteRuntime runtime = CelLiteRuntimeFactory.newLiteRuntimeBuilder().build();
    // Expr: 1 + 2
    CelAbstractSyntaxTree ast = readCheckedExpr("compiled_one_plus_two");

    CelEvaluationException e =
        assertThrows(CelEvaluationException.class, () -> runtime.createProgram(ast).eval());
    assertThat(e)
        .hasMessageThat()
        .contains(
            "evaluation error at <input>:2: No matching overload for function '_+_'. Overload"
                + " candidates: add_int64");
  }

  @Test
  public void eval_add() throws Exception {
    CelLiteRuntime runtime =
        CelLiteRuntimeFactory.newLiteRuntimeBuilder()
            .setStandardFunctions(CelStandardFunctions.ALL_STANDARD_FUNCTIONS)
            .build();
    // Expr: 1 + 2
    CelAbstractSyntaxTree ast = readCheckedExpr("compiled_one_plus_two");

    assertThat(runtime.createProgram(ast).eval()).isEqualTo(3L);
  }

  @Test
  public void eval_stringLiteral() throws Exception {
    CelLiteRuntime runtime =
        CelLiteRuntimeFactory.newLiteRuntimeBuilder()
            .setStandardFunctions(CelStandardFunctions.ALL_STANDARD_FUNCTIONS)
            .build();
    // Expr: 'hello world'
    CelAbstractSyntaxTree ast = readCheckedExpr("compiled_hello_world");
    Program program = runtime.createProgram(ast);

    String result = (String) program.eval();

    assertThat(result).isEqualTo("hello world");
  }

  @Test
  @SuppressWarnings("unchecked")
  public void eval_listLiteral() throws Exception {
    CelLiteRuntime runtime =
        CelLiteRuntimeFactory.newLiteRuntimeBuilder()
            .setStandardFunctions(CelStandardFunctions.ALL_STANDARD_FUNCTIONS)
            .build();
    // Expr: ['a', 1, 2u, 3.5]
    CelAbstractSyntaxTree ast = readCheckedExpr("compiled_list_literal");
    Program program = runtime.createProgram(ast);

    List<Object> result = (List<Object>) program.eval();

    assertThat(result).containsExactly("a", 1L, UnsignedLong.valueOf(2L), 3.5d).inOrder();
  }

  @Test
  public void eval_comprehensionExists() throws Exception {
    CelLiteRuntime runtime =
        CelLiteRuntimeFactory.newLiteRuntimeBuilder()
            .setStandardFunctions(CelStandardFunctions.ALL_STANDARD_FUNCTIONS)
            .build();
    // Expr: [1,2,3].exists(x, x == 3)
    CelAbstractSyntaxTree ast = readCheckedExpr("compiled_comprehension_exists");
    Program program = runtime.createProgram(ast);

    boolean result = (boolean) program.eval();

    assertThat(result).isTrue();
  }

  @Test
  public void eval_primitiveVariables() throws Exception {
    CelLiteRuntime runtime =
        CelLiteRuntimeFactory.newLiteRuntimeBuilder()
            .setStandardFunctions(CelStandardFunctions.ALL_STANDARD_FUNCTIONS)
            .build();
    // Expr: bool_var && bytes_var == b'abc' && double_var == 1.0 && int_var == 42 && uint_var ==
    //       42u && str_var == 'foo'
    CelAbstractSyntaxTree ast = readCheckedExpr("compiled_primitive_variables");
    Program program = runtime.createProgram(ast);

    boolean result =
        (boolean)
            program.eval(
                ImmutableMap.of(
                    "bool_var",
                    true,
                    "bytes_var",
                    CelByteString.copyFromUtf8("abc"),
                    "double_var",
                    1.0,
                    "int_var",
                    42L,
                    "uint_var",
                    UnsignedLong.valueOf(42L),
                    "str_var",
                    "foo"));

    assertThat(result).isTrue();
  }

  @Test
  @SuppressWarnings("rawtypes")
  public void eval_customFunctions() throws Exception {
    CelLiteRuntime runtime =
        CelLiteRuntimeFactory.newLiteRuntimeBuilder()
            .addFunctionBindings(
                CelFunctionBinding.from("string_isEmpty", String.class, String::isEmpty),
                CelFunctionBinding.from("list_isEmpty", List.class, List::isEmpty))
            .setStandardFunctions(CelStandardFunctions.ALL_STANDARD_FUNCTIONS)
            .build();
    // Expr: ''.isEmpty() && [].isEmpty()
    CelAbstractSyntaxTree ast = readCheckedExpr("compiled_custom_functions");
    Program program = runtime.createProgram(ast);

    boolean result = (boolean) program.eval();

    assertThat(result).isTrue();
  }

  @Test
  @SuppressWarnings("rawtypes")
  public void eval_customFunctions_asLateBoundFunctions() throws Exception {
    CelLiteRuntime runtime =
        CelLiteRuntimeFactory.newLiteRuntimeBuilder()
            .addFunctionBindings(CelFunctionBinding.from("list_isEmpty", List.class, List::isEmpty))
            .addLateBoundFunctions("isEmpty")
            .setStandardFunctions(CelStandardFunctions.ALL_STANDARD_FUNCTIONS)
            .build();
    // Expr: ''.isEmpty() && [].isEmpty()
    CelAbstractSyntaxTree ast = readCheckedExpr("compiled_custom_functions");
    Program program = runtime.createProgram(ast);

    boolean result =
        (boolean)
            program.eval(
                ImmutableMap.of(),
                CelLateFunctionBindings.from(
                    CelFunctionBinding.from("string_isEmpty", String.class, String::isEmpty),
                    CelFunctionBinding.from("list_isEmpty", List.class, List::isEmpty)));

    assertThat(result).isTrue();
  }

  @Test
  @TestParameters("{checkedExpr: 'compiled_proto2_select_primitives'}")
  @TestParameters("{checkedExpr: 'compiled_proto3_select_primitives'}")
  public void eval_protoMessage_unknowns(String checkedExpr) throws Exception {
    CelLiteRuntime runtime =
        CelLiteRuntimeFactory.newLiteRuntimeBuilder()
            .setStandardFunctions(CelStandardFunctions.ALL_STANDARD_FUNCTIONS)
            .build();
    CelAbstractSyntaxTree ast = readCheckedExpr(checkedExpr);
    Program program = runtime.createProgram(ast);

    CelUnknownSet result =
        (CelUnknownSet)
            program.eval(
                PartialVars.of(
                    CelAttributePattern.create("proto2"), CelAttributePattern.create("proto3")));

    assertThat(result.unknownExprIds()).hasSize(15);
  }

  @Test
  @TestParameters("{checkedExpr: 'compiled_proto2_select_primitives_all_ored'}")
  @TestParameters("{checkedExpr: 'compiled_proto3_select_primitives_all_ored'}")
  public void eval_protoMessage_primitiveWithDefaults(String checkedExpr) throws Exception {
    CelLiteRuntime runtime =
        CelLiteRuntimeFactory.newLiteRuntimeBuilder()
            .setStandardFunctions(CelStandardFunctions.ALL_STANDARD_FUNCTIONS)
            .setValueProvider(
                ProtoMessageLiteValueProvider.newInstance(
                    dev.cel.expr.conformance.proto2.TestAllTypesCelLiteDescriptor.getDescriptor(),
                    dev.cel.expr.conformance.proto2.NestedTestAllTypesCelLiteDescriptor
                        .getDescriptor(),
                    TestAllTypesCelLiteDescriptor.getDescriptor(),
                    NestedTestAllTypesCelLiteDescriptor.getDescriptor()))
            .build();
    // Ensures that all branches of the OR conditions are evaluated, and that appropriate defaults
    // are returned for primitives.
    CelAbstractSyntaxTree ast = readCheckedExpr(checkedExpr);
    Program program = runtime.createProgram(ast);

    boolean result =
        (boolean)
            program.eval(
                ImmutableMap.of(
                    "proto2", dev.cel.expr.conformance.proto2.TestAllTypes.getDefaultInstance(),
                    "proto3", TestAllTypes.getDefaultInstance()));

    assertThat(result).isFalse(); // False should be returned to avoid short circuiting.
  }

  @Test
  @TestParameters("{checkedExpr: 'compiled_proto2_select_primitives'}")
  @TestParameters("{checkedExpr: 'compiled_proto3_select_primitives'}")
  public void eval_protoMessage_primitives(String checkedExpr) throws Exception {
    CelLiteRuntime runtime =
        CelLiteRuntimeFactory.newLiteRuntimeBuilder()
            .setStandardFunctions(CelStandardFunctions.ALL_STANDARD_FUNCTIONS)
            .setValueProvider(
                ProtoMessageLiteValueProvider.newInstance(
                    dev.cel.expr.conformance.proto2.TestAllTypesCelLiteDescriptor.getDescriptor(),
                    TestAllTypesCelLiteDescriptor.getDescriptor()))
            .build();
    CelAbstractSyntaxTree ast = readCheckedExpr(checkedExpr);
    Program program = runtime.createProgram(ast);

    boolean result =
        (boolean)
            program.eval(
                ImmutableMap.of(
                    "proto2",
                    dev.cel.expr.conformance.proto2.TestAllTypes.newBuilder()
                        .setSingleInt32(1)
                        .setSingleInt64(2L)
                        .setSingleUint32(3)
                        .setSingleUint64(4L)
                        .setSingleSint32(5)
                        .setSingleSint64(6L)
                        .setSingleFixed32(7)
                        .setSingleFixed64(8L)
                        .setSingleSfixed32(9)
                        .setSingleSfixed64(10L)
                        .setSingleFloat(1.5f)
                        .setSingleDouble(2.5d)
                        .setSingleBool(true)
                        .setSingleString("hello world")
                        .setSingleBytes(ByteString.copyFromUtf8("abc"))
                        .build(),
                    "proto3",
                    TestAllTypes.newBuilder()
                        .setSingleInt32(1)
                        .setSingleInt64(2L)
                        .setSingleUint32(3)
                        .setSingleUint64(4L)
                        .setSingleSint32(5)
                        .setSingleSint64(6L)
                        .setSingleFixed32(7)
                        .setSingleFixed64(8L)
                        .setSingleSfixed32(9)
                        .setSingleSfixed64(10L)
                        .setSingleFloat(1.5f)
                        .setSingleDouble(2.5d)
                        .setSingleBool(true)
                        .setSingleString("hello world")
                        .setSingleBytes(ByteString.copyFromUtf8("abc"))
                        .build()));

    assertThat(result).isTrue();
  }

  @Test
  @TestParameters("{checkedExpr: 'compiled_proto2_select_wrappers'}")
  @TestParameters("{checkedExpr: 'compiled_proto3_select_wrappers'}")
  public void eval_protoMessage_wrappers(String checkedExpr) throws Exception {
    CelLiteRuntime runtime =
        CelLiteRuntimeFactory.newLiteRuntimeBuilder()
            .setStandardFunctions(CelStandardFunctions.ALL_STANDARD_FUNCTIONS)
            .setValueProvider(
                ProtoMessageLiteValueProvider.newInstance(
                    dev.cel.expr.conformance.proto2.TestAllTypesCelLiteDescriptor.getDescriptor(),
                    TestAllTypesCelLiteDescriptor.getDescriptor()))
            .build();
    CelAbstractSyntaxTree ast = readCheckedExpr(checkedExpr);
    Program program = runtime.createProgram(ast);

    boolean result =
        (boolean)
            program.eval(
                ImmutableMap.of(
                    "proto2",
                    dev.cel.expr.conformance.proto2.TestAllTypes.newBuilder()
                        .setSingleInt32Wrapper(Int32Value.of(1))
                        .setSingleInt64Wrapper(Int64Value.of(2L))
                        .setSingleUint32Wrapper(UInt32Value.of(3))
                        .setSingleUint64Wrapper(UInt64Value.of(4L))
                        .setSingleFloatWrapper(FloatValue.of(1.5f))
                        .setSingleDoubleWrapper(DoubleValue.of(2.5d))
                        .setSingleBoolWrapper(BoolValue.of(true))
                        .setSingleStringWrapper(StringValue.of("hello world"))
                        .setSingleBytesWrapper(BytesValue.of(ByteString.copyFromUtf8("abc")))
                        .build(),
                    "proto3",
                    TestAllTypes.newBuilder()
                        .setSingleInt32Wrapper(Int32Value.of(1))
                        .setSingleInt64Wrapper(Int64Value.of(2L))
                        .setSingleUint32Wrapper(UInt32Value.of(3))
                        .setSingleUint64Wrapper(UInt64Value.of(4L))
                        .setSingleFloatWrapper(FloatValue.of(1.5f))
                        .setSingleDoubleWrapper(DoubleValue.of(2.5d))
                        .setSingleBoolWrapper(BoolValue.of(true))
                        .setSingleStringWrapper(StringValue.of("hello world"))
                        .setSingleBytesWrapper(BytesValue.of(ByteString.copyFromUtf8("abc")))
                        .build()));

    assertThat(result).isTrue();
  }

  @Test
  @SuppressWarnings("unchecked")
  @TestParameters("{checkedExpr: 'compiled_proto2_deep_traversal'}")
  @TestParameters("{checkedExpr: 'compiled_proto3_deep_traversal'}")
  public void eval_protoMessage_safeTraversal(String checkedExpr) throws Exception {
    CelLiteRuntime runtime =
        CelLiteRuntimeFactory.newLiteRuntimeBuilder()
            .setStandardFunctions(CelStandardFunctions.ALL_STANDARD_FUNCTIONS)
            .setValueProvider(
                ProtoMessageLiteValueProvider.newInstance(
                    dev.cel.expr.conformance.proto2.TestAllTypesCelLiteDescriptor.getDescriptor(),
                    dev.cel.expr.conformance.proto2.NestedTestAllTypesCelLiteDescriptor
                        .getDescriptor(),
                    TestAllTypesCelLiteDescriptor.getDescriptor(),
                    NestedTestAllTypesCelLiteDescriptor.getDescriptor()))
            .build();
    // Expr: proto2.oneof_type.payload.repeated_string
    CelAbstractSyntaxTree ast = readCheckedExpr(checkedExpr);

    Program program = runtime.createProgram(ast);

    List<String> result =
        (List<String>)
            program.eval(
                ImmutableMap.of(
                    "proto2", dev.cel.expr.conformance.proto2.TestAllTypes.getDefaultInstance(),
                    "proto3", TestAllTypes.getDefaultInstance()));

    assertThat(result).isEmpty();
  }

  @Test
  @SuppressWarnings("unchecked")
  @TestParameters("{checkedExpr: 'compiled_proto2_deep_traversal'}")
  @TestParameters("{checkedExpr: 'compiled_proto3_deep_traversal'}")
  public void eval_protoMessage_deepTraversalReturnsRepeatedStrings(String checkedExpr)
      throws Exception {
    CelLiteRuntime runtime =
        CelLiteRuntimeFactory.newLiteRuntimeBuilder()
            .setStandardFunctions(CelStandardFunctions.ALL_STANDARD_FUNCTIONS)
            .setValueProvider(
                ProtoMessageLiteValueProvider.newInstance(
                    dev.cel.expr.conformance.proto2.TestAllTypesCelLiteDescriptor.getDescriptor(),
                    dev.cel.expr.conformance.proto2.NestedTestAllTypesCelLiteDescriptor
                        .getDescriptor(),
                    TestAllTypesCelLiteDescriptor.getDescriptor(),
                    NestedTestAllTypesCelLiteDescriptor.getDescriptor()))
            .build();
    // Expr: proto2.oneof_type.payload.repeated_string
    CelAbstractSyntaxTree ast = readCheckedExpr(checkedExpr);
    Program program = runtime.createProgram(ast);
    ImmutableList<String> data = ImmutableList.of("hello", "world");

    List<String> result =
        (List<String>)
            program.eval(
                ImmutableMap.of(
                    "proto2",
                        dev.cel.expr.conformance.proto2.TestAllTypes.newBuilder()
                            .setOneofType(
                                dev.cel.expr.conformance.proto2.NestedTestAllTypes.newBuilder()
                                    .setPayload(
                                        dev.cel.expr.conformance.proto2.TestAllTypes.newBuilder()
                                            .addAllRepeatedString(data)
                                            .build()))
                            .build(),
                    "proto3",
                        TestAllTypes.newBuilder()
                            .setOneofType(
                                NestedTestAllTypes.newBuilder()
                                    .setPayload(
                                        TestAllTypes.newBuilder()
                                            .addAllRepeatedString(data)
                                            .build()))
                            .build()));

    assertThat(result).isEqualTo(data);
  }

  @Test
  @SuppressWarnings("unchecked")
  @TestParameters("{checkedExpr: 'compiled_proto2_select_repeated_fields'}")
  @TestParameters("{checkedExpr: 'compiled_proto3_select_repeated_fields'}")
  public void eval_protoMessage_repeatedFields(String checkedExpr) throws Exception {
    CelLiteRuntime runtime =
        CelLiteRuntimeFactory.newLiteRuntimeBuilder()
            .setStandardFunctions(CelStandardFunctions.ALL_STANDARD_FUNCTIONS)
            .setValueProvider(
                ProtoMessageLiteValueProvider.newInstance(
                    dev.cel.expr.conformance.proto2.TestAllTypesCelLiteDescriptor.getDescriptor(),
                    TestAllTypesCelLiteDescriptor.getDescriptor()))
            .build();
    dev.cel.expr.conformance.proto2.TestAllTypes proto2TestMsg =
        dev.cel.expr.conformance.proto2.TestAllTypes.newBuilder()
            .addAllRepeatedInt32(ImmutableList.of(1, 2))
            .addAllRepeatedInt64(ImmutableList.of(3L, 4L))
            .addAllRepeatedUint32(ImmutableList.of(5, 6))
            .addAllRepeatedUint64(ImmutableList.of(7L, 8L))
            .addAllRepeatedSint32(ImmutableList.of(9, 10))
            .addAllRepeatedSint64(ImmutableList.of(11L, 12L))
            .addAllRepeatedFixed32(ImmutableList.of(13, 14))
            .addAllRepeatedFixed64(ImmutableList.of(15L, 16L))
            .addAllRepeatedSfixed32(ImmutableList.of(17, 18))
            .addAllRepeatedSfixed64(ImmutableList.of(19L, 20L))
            .addAllRepeatedFloat(ImmutableList.of(21.1f, 22.2f))
            .addAllRepeatedDouble(ImmutableList.of(23.3, 24.4))
            .addAllRepeatedBool(ImmutableList.of(true, false))
            .addAllRepeatedString(ImmutableList.of("alpha", "beta"))
            .addAllRepeatedBytes(
                ImmutableList.of(
                    ByteString.copyFromUtf8("gamma"), ByteString.copyFromUtf8("delta")))
            .build();
    TestAllTypes proto3TestMsg =
        TestAllTypes.newBuilder()
            .addAllRepeatedInt32(ImmutableList.of(1, 2))
            .addAllRepeatedInt64(ImmutableList.of(3L, 4L))
            .addAllRepeatedUint32(ImmutableList.of(5, 6))
            .addAllRepeatedUint64(ImmutableList.of(7L, 8L))
            .addAllRepeatedSint32(ImmutableList.of(9, 10))
            .addAllRepeatedSint64(ImmutableList.of(11L, 12L))
            .addAllRepeatedFixed32(ImmutableList.of(13, 14))
            .addAllRepeatedFixed64(ImmutableList.of(15L, 16L))
            .addAllRepeatedSfixed32(ImmutableList.of(17, 18))
            .addAllRepeatedSfixed64(ImmutableList.of(19L, 20L))
            .addAllRepeatedFloat(ImmutableList.of(21.1f, 22.2f))
            .addAllRepeatedDouble(ImmutableList.of(23.3, 24.4))
            .addAllRepeatedBool(ImmutableList.of(true, false))
            .addAllRepeatedString(ImmutableList.of("alpha", "beta"))
            .addAllRepeatedBytes(
                ImmutableList.of(
                    ByteString.copyFromUtf8("gamma"), ByteString.copyFromUtf8("delta")))
            .build();
    CelAbstractSyntaxTree ast = readCheckedExpr(checkedExpr);
    Program program = runtime.createProgram(ast);

    List<Object> result =
        (List<Object>)
            program.eval(ImmutableMap.of("proto2", proto2TestMsg, "proto3", proto3TestMsg));

    assertThat(result)
        .comparingElementsUsing(LIST_WITH_DOUBLE_TOLERANCE)
        .containsExactly(
            ImmutableList.of(1L, 2L),
            ImmutableList.of(3L, 4L),
            ImmutableList.of(UnsignedLong.valueOf(5L), UnsignedLong.valueOf(6L)),
            ImmutableList.of(UnsignedLong.valueOf(7L), UnsignedLong.valueOf(8L)),
            ImmutableList.of(9L, 10L),
            ImmutableList.of(11L, 12L),
            ImmutableList.of(13L, 14L),
            ImmutableList.of(15L, 16L),
            ImmutableList.of(17L, 18L),
            ImmutableList.of(19L, 20L),
            ImmutableList.of(21.1d, 22.2d),
            ImmutableList.of(23.3d, 24.4d),
            ImmutableList.of(true, false),
            ImmutableList.of("alpha", "beta"),
            ImmutableList.of(
                CelByteString.copyFromUtf8("gamma"), CelByteString.copyFromUtf8("delta")))
        .inOrder();
  }

  @Test
  // leave proto2.TestAllTypes qualification as is for clarity
  @SuppressWarnings({"UnnecessarilyFullyQualified", "unchecked"})
  @TestParameters("{checkedExpr: 'compiled_proto2_select_map_fields'}")
  @TestParameters("{checkedExpr: 'compiled_proto3_select_map_fields'}")
  public void eval_protoMessage_mapFields(String checkedExpr) throws Exception {
    CelLiteRuntime runtime =
        CelLiteRuntimeFactory.newLiteRuntimeBuilder()
            .setStandardFunctions(CelStandardFunctions.ALL_STANDARD_FUNCTIONS)
            .setValueProvider(
                ProtoMessageLiteValueProvider.newInstance(
                    dev.cel.expr.conformance.proto2.TestAllTypesCelLiteDescriptor.getDescriptor(),
                    TestAllTypesCelLiteDescriptor.getDescriptor()))
            .build();
    dev.cel.expr.conformance.proto2.TestAllTypes proto2TestMsg =
        dev.cel.expr.conformance.proto2.TestAllTypes.newBuilder()
            .putAllMapBoolBool(ImmutableMap.of(true, false, false, true))
            .putAllMapBoolString(ImmutableMap.of(true, "foo", false, "bar"))
            .putAllMapBoolBytes(
                ImmutableMap.of(
                    true, ByteString.copyFromUtf8("baz"), false, ByteString.copyFromUtf8("qux")))
            .putAllMapBoolInt32(ImmutableMap.of(true, 1, false, 2))
            .putAllMapBoolInt64(ImmutableMap.of(true, 3L, false, 4L))
            .putAllMapBoolUint32(ImmutableMap.of(true, 5, false, 6))
            .putAllMapBoolUint64(ImmutableMap.of(true, 7L, false, 8L))
            .putAllMapBoolFloat(ImmutableMap.of(true, 9.1f, false, 10.2f))
            .putAllMapBoolDouble(ImmutableMap.of(true, 11.3, false, 12.4))
            .putAllMapBoolEnum(
                ImmutableMap.of(
                    true,
                    dev.cel.expr.conformance.proto2.TestAllTypes.NestedEnum.BAR,
                    false,
                    dev.cel.expr.conformance.proto2.TestAllTypes.NestedEnum.BAZ))
            .putAllMapBoolDuration(
                ImmutableMap.of(
                    true,
                    ProtoTimeUtils.fromSecondsToDuration(15),
                    false,
                    ProtoTimeUtils.fromSecondsToDuration(16)))
            .putAllMapBoolTimestamp(
                ImmutableMap.of(
                    true,
                    ProtoTimeUtils.fromSecondsToTimestamp(17),
                    false,
                    ProtoTimeUtils.fromSecondsToTimestamp(18)))
            .build();
    TestAllTypes proto3TestMsg =
        TestAllTypes.newBuilder()
            .putAllMapBoolBool(ImmutableMap.of(true, false, false, true))
            .putAllMapBoolString(ImmutableMap.of(true, "foo", false, "bar"))
            .putAllMapBoolBytes(
                ImmutableMap.of(
                    true, ByteString.copyFromUtf8("baz"), false, ByteString.copyFromUtf8("qux")))
            .putAllMapBoolInt32(ImmutableMap.of(true, 1, false, 2))
            .putAllMapBoolInt64(ImmutableMap.of(true, 3L, false, 4L))
            .putAllMapBoolUint32(ImmutableMap.of(true, 5, false, 6))
            .putAllMapBoolUint64(ImmutableMap.of(true, 7L, false, 8L))
            .putAllMapBoolFloat(ImmutableMap.of(true, 9.1f, false, 10.2f))
            .putAllMapBoolDouble(ImmutableMap.of(true, 11.3, false, 12.4))
            .putAllMapBoolEnum(
                ImmutableMap.of(
                    true, TestAllTypes.NestedEnum.BAR, false, TestAllTypes.NestedEnum.BAZ))
            .putAllMapBoolDuration(
                ImmutableMap.of(
                    true,
                    ProtoTimeUtils.fromSecondsToDuration(15),
                    false,
                    ProtoTimeUtils.fromSecondsToDuration(16)))
            .putAllMapBoolTimestamp(
                ImmutableMap.of(
                    true,
                    ProtoTimeUtils.fromSecondsToTimestamp(17),
                    false,
                    ProtoTimeUtils.fromSecondsToTimestamp(18)))
            .build();
    CelAbstractSyntaxTree ast = readCheckedExpr(checkedExpr);
    Program program = runtime.createProgram(ast);

    List<Object> result =
        (List<Object>)
            program.eval(ImmutableMap.of("proto2", proto2TestMsg, "proto3", proto3TestMsg));

    assertThat(result)
        .comparingElementsUsing(MAP_WITH_DOUBLE_TOLERANCE)
        .containsExactly(
            ImmutableMap.of(true, false, false, true),
            ImmutableMap.of(true, "foo", false, "bar"),
            ImmutableMap.of(
                true, CelByteString.copyFromUtf8("baz"), false, CelByteString.copyFromUtf8("qux")),
            ImmutableMap.of(true, 1L, false, 2L),
            ImmutableMap.of(true, 3L, false, 4L),
            ImmutableMap.of(true, UnsignedLong.valueOf(5), false, UnsignedLong.valueOf(6)),
            ImmutableMap.of(true, UnsignedLong.valueOf(7L), false, UnsignedLong.valueOf(8L)),
            ImmutableMap.of(true, 9.1d, false, 10.2d),
            ImmutableMap.of(true, 11.3d, false, 12.4d),
            ImmutableMap.of(true, 1L, false, 2L), // Note: Enums are converted into integers
            ImmutableMap.of(true, Duration.ofSeconds(15), false, Duration.ofSeconds(16)),
            ImmutableMap.of(true, Instant.ofEpochSecond(17), false, Instant.ofEpochSecond(18)))
        .inOrder();
  }

  private enum CelOptionsTestCase {
    UNSIGNED_LONG_DISABLED(newBaseTestOptions().enableUnsignedLongs(false).build()),
    UNWRAP_WKT_DISABLED(newBaseTestOptions().unwrapWellKnownTypesOnFunctionDispatch(false).build()),
    ;

    private final CelOptions celOptions;

    private static CelOptions.Builder newBaseTestOptions() {
      return CelOptions.current().enableCelValue(true);
    }

    CelOptionsTestCase(CelOptions celOptions) {
      this.celOptions = celOptions;
    }
  }
}
