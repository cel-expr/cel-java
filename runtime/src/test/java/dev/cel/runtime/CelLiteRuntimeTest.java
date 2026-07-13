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
import static com.google.common.truth.extensions.proto.ProtoTruth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.primitives.UnsignedLong;
import com.google.protobuf.BoolValue;
import com.google.protobuf.ByteString;
import com.google.protobuf.BytesValue;
import com.google.protobuf.DoubleValue;
import com.google.protobuf.FloatValue;
import com.google.protobuf.Int32Value;
import com.google.protobuf.Int64Value;
import com.google.protobuf.ListValue;
import com.google.protobuf.StringValue;
import com.google.protobuf.Struct;
import com.google.protobuf.UInt32Value;
import com.google.protobuf.UInt64Value;
import com.google.protobuf.util.Values;
import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import com.google.testing.junit.testparameterinjector.TestParameters;
import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.CelContainer;
import dev.cel.common.CelFunctionDecl;
import dev.cel.common.CelOverloadDecl;
import dev.cel.common.internal.ProtoTimeUtils;
import dev.cel.common.types.ProtoMessageTypeProvider;
import dev.cel.common.types.SimpleType;
import dev.cel.common.types.StructTypeReference;
import dev.cel.common.values.CelByteString;
import dev.cel.common.values.NullValue;
import dev.cel.common.values.ProtoMessageLiteValueProvider;
import dev.cel.compiler.CelCompiler;
import dev.cel.compiler.CelCompilerFactory;
import dev.cel.expr.conformance.proto3.TestAllTypes;
import dev.cel.expr.conformance.proto3.TestAllTypes.NestedEnum;
import dev.cel.expr.conformance.proto3.TestAllTypes.NestedMessage;
import dev.cel.expr.conformance.proto3.TestAllTypesCelDescriptor;
import dev.cel.parser.CelStandardMacro;
import dev.cel.testing.testdata.MessageWithEnum;
import dev.cel.testing.testdata.MessageWithEnumCelDescriptor;
import dev.cel.testing.testdata.MultiFile;
import dev.cel.testing.testdata.MultiFileCelDescriptor;
import dev.cel.testing.testdata.SimpleEnum;
import dev.cel.testing.testdata.SingleFile;
import dev.cel.testing.testdata.SingleFileCelDescriptor;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Exercises tests for CelLiteRuntime using <b>full version of protobuf messages</b>. */
@RunWith(TestParameterInjector.class)
public class CelLiteRuntimeTest {
  private static final CelContainer CEL_CONTAINER =
      CelContainer.ofName("cel.expr.conformance.proto3");

  private static final CelCompiler CEL_COMPILER =
      CelCompilerFactory.standardCelCompilerBuilder()
          .setStandardMacros(CelStandardMacro.STANDARD_MACROS)
          .addVar("msg", StructTypeReference.create(TestAllTypes.getDescriptor().getFullName()))
          .addVar("content", SimpleType.DYN)
          .addMessageTypes(TestAllTypes.getDescriptor())
          .setContainer(CEL_CONTAINER)
          .build();

  private static final CelLiteRuntime CEL_RUNTIME =
      CelLiteRuntimeFactory.newLiteRuntimeBuilder()
          .setStandardFunctions(CelStandardFunctions.ALL_STANDARD_FUNCTIONS)
          .setTypeProvider(
              ProtoMessageTypeProvider.newBuilder()
                  .addDescriptors(
                      ImmutableSet.of(
                          dev.cel.expr.conformance.proto2.TestAllTypes.getDescriptor(),
                          TestAllTypes.getDescriptor()))
                  .build())
          .setValueProvider(
              ProtoMessageLiteValueProvider.newInstance(
                  dev.cel.expr.conformance.proto2.TestAllTypesCelDescriptor.getDescriptor(),
                  TestAllTypesCelDescriptor.getDescriptor()))
          .setContainer(CEL_CONTAINER)
          .build();

  @Test
  public void messageCreation_emptyMessage() throws Exception {
    CelAbstractSyntaxTree ast = CEL_COMPILER.compile("TestAllTypes{}").getAst();

    TestAllTypes simpleTest = (TestAllTypes) CEL_RUNTIME.createProgram(ast).eval();

    assertThat(simpleTest).isEqualToDefaultInstance();
  }

  @Test
  public void messageCreation_fieldsPopulated() throws Exception {
    CelAbstractSyntaxTree ast = CEL_COMPILER.compile("TestAllTypes{single_int32: 4}").getAst();

    CelEvaluationException e =
        assertThrows(CelEvaluationException.class, () -> CEL_RUNTIME.createProgram(ast).eval());

    assertThat(e)
        .hasMessageThat()
        .contains("Message creation with prepopulated fields is not supported yet.");
  }

  @Test
  @TestParameters("{expression: 'msg.single_int32 == 1'}")
  @TestParameters("{expression: 'msg.single_int64 == 2'}")
  @TestParameters("{expression: 'msg.single_uint32 == 3u'}")
  @TestParameters("{expression: 'msg.single_uint64 == 4u'}")
  @TestParameters("{expression: 'msg.single_sint32 == 5'}")
  @TestParameters("{expression: 'msg.single_sint64 == 6'}")
  @TestParameters("{expression: 'msg.single_fixed32 == 7u'}")
  @TestParameters("{expression: 'msg.single_fixed64 == 8u'}")
  @TestParameters("{expression: 'msg.single_sfixed32 == 9'}")
  @TestParameters("{expression: 'msg.single_sfixed64 == 10'}")
  @TestParameters("{expression: 'msg.single_float == 1.5'}")
  @TestParameters("{expression: 'msg.single_double == 2.5'}")
  @TestParameters("{expression: 'msg.single_bool == true'}")
  @TestParameters("{expression: 'msg.single_string == \"foo\"'}")
  @TestParameters("{expression: 'msg.single_bytes == b\"abc\"'}")
  @TestParameters("{expression: 'msg.optional_bool == true'}")
  public void fieldSelection_literals(String expression) throws Exception {
    CelAbstractSyntaxTree ast = CEL_COMPILER.compile(expression).getAst();
    TestAllTypes msg =
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
            .setSingleString("foo")
            .setSingleBytes(ByteString.copyFromUtf8("abc"))
            .setOptionalBool(true)
            .build();

    boolean result = (boolean) CEL_RUNTIME.createProgram(ast).eval(ImmutableMap.of("msg", msg));

    assertThat(result).isTrue();
  }

  @Test
  @TestParameters("{expression: 'msg.single_uint32'}")
  @TestParameters("{expression: 'msg.single_uint64'}")
  public void fieldSelection_unsigned(String expression) throws Exception {
    CelAbstractSyntaxTree ast = CEL_COMPILER.compile(expression).getAst();
    TestAllTypes msg = TestAllTypes.newBuilder().setSingleUint32(4).setSingleUint64(4L).build();

    Object result = CEL_RUNTIME.createProgram(ast).eval(ImmutableMap.of("msg", msg));

    assertThat(result).isEqualTo(UnsignedLong.valueOf(4L));
  }

  @Test
  @TestParameters("{expression: 'msg.repeated_int32'}")
  @TestParameters("{expression: 'msg.repeated_int64'}")
  @SuppressWarnings("unchecked")
  public void fieldSelection_packedRepeatedInts(String expression) throws Exception {
    // Note: non-LEN delimited primitives such as ints are packed by default in proto3
    CelAbstractSyntaxTree ast = CEL_COMPILER.compile(expression).getAst();
    TestAllTypes msg =
        TestAllTypes.newBuilder()
            .addRepeatedInt32(1)
            .addRepeatedInt32(2)
            .addRepeatedInt64(1L)
            .addRepeatedInt64(2L)
            .build();

    List<Long> result =
        (List<Long>) CEL_RUNTIME.createProgram(ast).eval(ImmutableMap.of("msg", msg));

    assertThat(result).containsExactly(1L, 2L).inOrder();
  }

  @Test
  @SuppressWarnings("unchecked")
  public void fieldSelection_repeatedStrings() throws Exception {
    // Note: len-delimited fields, such as string and messages are not packed.
    CelAbstractSyntaxTree ast = CEL_COMPILER.compile("msg.repeated_string").getAst();
    TestAllTypes msg =
        TestAllTypes.newBuilder().addRepeatedString("hello").addRepeatedString("world").build();

    List<String> result =
        (List<String>) CEL_RUNTIME.createProgram(ast).eval(ImmutableMap.of("msg", msg));

    assertThat(result).containsExactly("hello", "world").inOrder();
  }

  @Test
  @SuppressWarnings("unchecked")
  public void fieldSelection_repeatedBoolWrappers() throws Exception {
    CelAbstractSyntaxTree ast = CEL_COMPILER.compile("msg.repeated_bool_wrapper").getAst();
    TestAllTypes msg =
        TestAllTypes.newBuilder()
            .addRepeatedBoolWrapper(BoolValue.of(true))
            .addRepeatedBoolWrapper(BoolValue.of(false))
            .addRepeatedBoolWrapper(BoolValue.of(true))
            .build();

    List<Boolean> result =
        (List<Boolean>) CEL_RUNTIME.createProgram(ast).eval(ImmutableMap.of("msg", msg));

    assertThat(result).containsExactly(true, false, true).inOrder();
  }

  @Test
  @TestParameters("{expression: 'msg.map_string_int32'}")
  @TestParameters("{expression: 'msg.map_string_int64'}")
  @SuppressWarnings("unchecked")
  public void fieldSelection_map(String expression) throws Exception {
    CelAbstractSyntaxTree ast = CEL_COMPILER.compile(expression).getAst();
    TestAllTypes msg =
        TestAllTypes.newBuilder()
            .putMapStringInt32("a", 1)
            .putMapStringInt32("b", 2)
            .putMapStringInt64("a", 1L)
            .putMapStringInt64("b", 2L)
            .build();

    Map<String, Long> result =
        (Map<String, Long>) CEL_RUNTIME.createProgram(ast).eval(ImmutableMap.of("msg", msg));

    assertThat(result).containsExactly("a", 1L, "b", 2L);
  }

  @Test
  @TestParameters("{expression: 'msg.single_int32_wrapper == 1'}")
  @TestParameters("{expression: 'msg.single_int64_wrapper == 2'}")
  @TestParameters("{expression: 'msg.single_uint32_wrapper == 3u'}")
  @TestParameters("{expression: 'msg.single_uint64_wrapper == 4u'}")
  @TestParameters("{expression: 'msg.single_float_wrapper == 1.5'}")
  @TestParameters("{expression: 'msg.single_double_wrapper == 2.5'}")
  @TestParameters("{expression: 'msg.single_bool_wrapper == true'}")
  @TestParameters("{expression: 'msg.single_string_wrapper == \"foo\"'}")
  @TestParameters("{expression: 'msg.single_bytes_wrapper == b\"abc\"'}")
  public void fieldSelection_wrappers(String expression) throws Exception {
    CelAbstractSyntaxTree ast = CEL_COMPILER.compile(expression).getAst();
    TestAllTypes msg =
        TestAllTypes.newBuilder()
            .setSingleInt32Wrapper(Int32Value.of(1))
            .setSingleInt64Wrapper(Int64Value.of(2L))
            .setSingleUint32Wrapper(UInt32Value.of(3))
            .setSingleUint64Wrapper(UInt64Value.of(4L))
            .setSingleFloatWrapper(FloatValue.of(1.5f))
            .setSingleDoubleWrapper(DoubleValue.of(2.5d))
            .setSingleBoolWrapper(BoolValue.of(true))
            .setSingleStringWrapper(StringValue.of("foo"))
            .setSingleBytesWrapper(BytesValue.of(ByteString.copyFromUtf8("abc")))
            .build();

    boolean result = (boolean) CEL_RUNTIME.createProgram(ast).eval(ImmutableMap.of("msg", msg));

    assertThat(result).isTrue();
  }

  @Test
  @TestParameters("{expression: 'msg.single_int32_wrapper'}")
  @TestParameters("{expression: 'msg.single_int64_wrapper'}")
  @TestParameters("{expression: 'msg.single_uint32_wrapper'}")
  @TestParameters("{expression: 'msg.single_uint64_wrapper'}")
  @TestParameters("{expression: 'msg.single_float_wrapper'}")
  @TestParameters("{expression: 'msg.single_double_wrapper'}")
  @TestParameters("{expression: 'msg.single_bool_wrapper'}")
  @TestParameters("{expression: 'msg.single_string_wrapper'}")
  @TestParameters("{expression: 'msg.single_bytes_wrapper'}")
  public void fieldSelection_wrappersNullability(String expression) throws Exception {
    CelAbstractSyntaxTree ast = CEL_COMPILER.compile(expression).getAst();
    TestAllTypes msg = TestAllTypes.getDefaultInstance();

    Object result = CEL_RUNTIME.createProgram(ast).eval(ImmutableMap.of("msg", msg));

    assertThat(result).isEqualTo(NullValue.NULL_VALUE);
  }

  @Test
  public void fieldSelection_duration() throws Exception {
    String expression = "msg.single_duration";
    CelAbstractSyntaxTree ast = CEL_COMPILER.compile(expression).getAst();
    TestAllTypes msg =
        TestAllTypes.newBuilder()
            .setSingleDuration(ProtoTimeUtils.fromSecondsToDuration(600))
            .build();

    Duration result = (Duration) CEL_RUNTIME.createProgram(ast).eval(ImmutableMap.of("msg", msg));

    assertThat(result).isEqualTo(Duration.ofMinutes(10));
  }

  @Test
  public void fieldSelection_timestamp() throws Exception {
    String expression = "msg.single_timestamp";
    CelAbstractSyntaxTree ast = CEL_COMPILER.compile(expression).getAst();
    TestAllTypes msg =
        TestAllTypes.newBuilder()
            .setSingleTimestamp(ProtoTimeUtils.fromSecondsToTimestamp(50))
            .build();

    Instant result = (Instant) CEL_RUNTIME.createProgram(ast).eval(ImmutableMap.of("msg", msg));

    assertThat(result).isEqualTo(Instant.ofEpochSecond(50));
  }

  @Test
  @SuppressWarnings("unchecked")
  public void fieldSelection_jsonStruct() throws Exception {
    String expression = "msg.single_struct";
    CelAbstractSyntaxTree ast = CEL_COMPILER.compile(expression).getAst();
    TestAllTypes msg =
        TestAllTypes.newBuilder()
            .setSingleStruct(
                Struct.newBuilder()
                    .putFields("one", Values.of(1))
                    .putFields("two", Values.of(true)))
            .build();

    Map<Object, Object> result =
        (Map<Object, Object>) CEL_RUNTIME.createProgram(ast).eval(ImmutableMap.of("msg", msg));

    assertThat(result).containsExactly("one", 1.0d, "two", true).inOrder();
  }

  @Test
  public void fieldSelection_jsonValue() throws Exception {
    String expression = "msg.single_value";
    CelAbstractSyntaxTree ast = CEL_COMPILER.compile(expression).getAst();
    TestAllTypes msg = TestAllTypes.newBuilder().setSingleValue(Values.of("foo")).build();

    String result = (String) CEL_RUNTIME.createProgram(ast).eval(ImmutableMap.of("msg", msg));

    assertThat(result).isEqualTo("foo");
  }

  @Test
  @SuppressWarnings("unchecked")
  public void fieldSelection_jsonListValue() throws Exception {
    String expression = "msg.list_value";
    CelAbstractSyntaxTree ast = CEL_COMPILER.compile(expression).getAst();
    TestAllTypes msg =
        TestAllTypes.newBuilder()
            .setListValue(
                ListValue.newBuilder().addValues(Values.of(true)).addValues(Values.of("foo")))
            .build();

    List<Object> result =
        (List<Object>) CEL_RUNTIME.createProgram(ast).eval(ImmutableMap.of("msg", msg));

    assertThat(result).containsExactly(true, "foo").inOrder();
  }

  @Test
  @TestParameters("{expression: 'has(msg.single_int32)'}")
  @TestParameters("{expression: 'has(msg.single_int64)'}")
  @TestParameters("{expression: 'has(msg.single_int32_wrapper)'}")
  @TestParameters("{expression: 'has(msg.single_int64_wrapper)'}")
  @TestParameters("{expression: 'has(msg.repeated_int32)'}")
  @TestParameters("{expression: 'has(msg.repeated_int64)'}")
  @TestParameters("{expression: 'has(msg.repeated_int32_wrapper)'}")
  @TestParameters("{expression: 'has(msg.repeated_int64_wrapper)'}")
  @TestParameters("{expression: 'has(msg.map_string_int32)'}")
  @TestParameters("{expression: 'has(msg.map_string_int64)'}")
  @TestParameters("{expression: 'has(msg.map_bool_int32_wrapper)'}")
  @TestParameters("{expression: 'has(msg.map_bool_int64_wrapper)'}")
  public void presenceTest_proto2_evaluatesToFalse(String expression) throws Exception {
    CelAbstractSyntaxTree ast = CEL_COMPILER.compile(expression).getAst();
    dev.cel.expr.conformance.proto2.TestAllTypes msg =
        dev.cel.expr.conformance.proto2.TestAllTypes.newBuilder()
            .addAllRepeatedInt32(ImmutableList.of())
            .addAllRepeatedInt32Wrapper(ImmutableList.of())
            .putAllMapBoolInt32(ImmutableMap.of())
            .putAllMapBoolInt32Wrapper(ImmutableMap.of())
            .build();

    boolean result = (boolean) CEL_RUNTIME.createProgram(ast).eval(ImmutableMap.of("msg", msg));

    assertThat(result).isFalse();
  }

  @Test
  @TestParameters("{expression: 'has(msg.single_int32)'}")
  @TestParameters("{expression: 'has(msg.single_int64)'}")
  @TestParameters("{expression: 'has(msg.single_int32_wrapper)'}")
  @TestParameters("{expression: 'has(msg.single_int64_wrapper)'}")
  @TestParameters("{expression: 'has(msg.repeated_int32)'}")
  @TestParameters("{expression: 'has(msg.repeated_int64)'}")
  @TestParameters("{expression: 'has(msg.repeated_int32_wrapper)'}")
  @TestParameters("{expression: 'has(msg.repeated_int64_wrapper)'}")
  @TestParameters("{expression: 'has(msg.map_string_int32)'}")
  @TestParameters("{expression: 'has(msg.map_string_int64)'}")
  @TestParameters("{expression: 'has(msg.map_string_int32_wrapper)'}")
  @TestParameters("{expression: 'has(msg.map_string_int64_wrapper)'}")
  public void presenceTest_proto2_evaluatesToTrue(String expression) throws Exception {
    CelAbstractSyntaxTree ast = CEL_COMPILER.compile(expression).getAst();
    dev.cel.expr.conformance.proto2.TestAllTypes msg =
        dev.cel.expr.conformance.proto2.TestAllTypes.newBuilder()
            .setSingleInt32(0)
            .setSingleInt64(0)
            .setSingleInt32Wrapper(Int32Value.of(0))
            .setSingleInt64Wrapper(Int64Value.of(0))
            .addAllRepeatedInt32(ImmutableList.of(1))
            .addAllRepeatedInt64(ImmutableList.of(2L))
            .addAllRepeatedInt32Wrapper(ImmutableList.of(Int32Value.of(0)))
            .addAllRepeatedInt64Wrapper(ImmutableList.of(Int64Value.of(0L)))
            .putAllMapStringInt32Wrapper(ImmutableMap.of("a", Int32Value.of(1)))
            .putAllMapStringInt64Wrapper(ImmutableMap.of("b", Int64Value.of(2L)))
            .putMapStringInt32("a", 1)
            .putMapStringInt64("b", 2)
            .build();

    boolean result = (boolean) CEL_RUNTIME.createProgram(ast).eval(ImmutableMap.of("msg", msg));

    assertThat(result).isTrue();
  }

  @Test
  @TestParameters("{expression: 'has(msg.single_int32)'}")
  @TestParameters("{expression: 'has(msg.single_int64)'}")
  @TestParameters("{expression: 'has(msg.single_int32_wrapper)'}")
  @TestParameters("{expression: 'has(msg.single_int64_wrapper)'}")
  @TestParameters("{expression: 'has(msg.repeated_int32)'}")
  @TestParameters("{expression: 'has(msg.repeated_int64)'}")
  @TestParameters("{expression: 'has(msg.repeated_int32_wrapper)'}")
  @TestParameters("{expression: 'has(msg.repeated_int64_wrapper)'}")
  @TestParameters("{expression: 'has(msg.map_string_int32)'}")
  @TestParameters("{expression: 'has(msg.map_string_int64)'}")
  @TestParameters("{expression: 'has(msg.map_bool_int32_wrapper)'}")
  @TestParameters("{expression: 'has(msg.map_bool_int64_wrapper)'}")
  public void presenceTest_proto3_evaluatesToFalse(String expression) throws Exception {
    CelAbstractSyntaxTree ast = CEL_COMPILER.compile(expression).getAst();
    TestAllTypes msg =
        TestAllTypes.newBuilder()
            .setSingleInt32(0)
            .addAllRepeatedInt32(ImmutableList.of())
            .addAllRepeatedInt32Wrapper(ImmutableList.of())
            .putAllMapBoolInt32(ImmutableMap.of())
            .putAllMapBoolInt32Wrapper(ImmutableMap.of())
            .build();

    boolean result = (boolean) CEL_RUNTIME.createProgram(ast).eval(ImmutableMap.of("msg", msg));

    assertThat(result).isFalse();
  }

  @Test
  @TestParameters("{expression: 'has(msg.single_int32)'}")
  @TestParameters("{expression: 'has(msg.single_int64)'}")
  @TestParameters("{expression: 'has(msg.single_int32_wrapper)'}")
  @TestParameters("{expression: 'has(msg.single_int64_wrapper)'}")
  @TestParameters("{expression: 'has(msg.repeated_int32)'}")
  @TestParameters("{expression: 'has(msg.repeated_int64)'}")
  @TestParameters("{expression: 'has(msg.repeated_int32_wrapper)'}")
  @TestParameters("{expression: 'has(msg.repeated_int64_wrapper)'}")
  @TestParameters("{expression: 'has(msg.map_string_int32)'}")
  @TestParameters("{expression: 'has(msg.map_string_int64)'}")
  @TestParameters("{expression: 'has(msg.map_string_int32_wrapper)'}")
  @TestParameters("{expression: 'has(msg.map_string_int64_wrapper)'}")
  public void presenceTest_proto3_evaluatesToTrue(String expression) throws Exception {
    CelAbstractSyntaxTree ast = CEL_COMPILER.compile(expression).getAst();
    TestAllTypes msg =
        TestAllTypes.newBuilder()
            .setSingleInt32(1)
            .setSingleInt64(2)
            .setSingleInt32Wrapper(Int32Value.of(0))
            .setSingleInt64Wrapper(Int64Value.of(0))
            .addAllRepeatedInt32(ImmutableList.of(1))
            .addAllRepeatedInt64(ImmutableList.of(2L))
            .addAllRepeatedInt32Wrapper(ImmutableList.of(Int32Value.of(0)))
            .addAllRepeatedInt64Wrapper(ImmutableList.of(Int64Value.of(0L)))
            .putAllMapStringInt32Wrapper(ImmutableMap.of("a", Int32Value.of(1)))
            .putAllMapStringInt64Wrapper(ImmutableMap.of("b", Int64Value.of(2L)))
            .putMapStringInt32("a", 1)
            .putMapStringInt64("b", 2)
            .build();

    boolean result = (boolean) CEL_RUNTIME.createProgram(ast).eval(ImmutableMap.of("msg", msg));

    assertThat(result).isTrue();
  }

  @Test
  public void nestedMessage_traversalThroughSetField() throws Exception {
    CelAbstractSyntaxTree ast =
        CEL_COMPILER
            .compile("msg.single_nested_message.bb == 43 && has(msg.single_nested_message)")
            .getAst();
    TestAllTypes nestedMessage =
        TestAllTypes.newBuilder()
            .setSingleNestedMessage(NestedMessage.newBuilder().setBb(43))
            .build();

    boolean result =
        (boolean) CEL_RUNTIME.createProgram(ast).eval(ImmutableMap.of("msg", nestedMessage));

    assertThat(result).isTrue();
  }

  @Test
  public void nestedMessage_safeTraversal() throws Exception {
    CelAbstractSyntaxTree ast = CEL_COMPILER.compile("msg.single_nested_message.bb == 43").getAst();
    TestAllTypes nestedMessage =
        TestAllTypes.newBuilder()
            .setSingleNestedMessage(NestedMessage.getDefaultInstance())
            .build();

    boolean result =
        (boolean) CEL_RUNTIME.createProgram(ast).eval(ImmutableMap.of("msg", nestedMessage));

    assertThat(result).isFalse();
  }

  @Test
  public void enumSelection() throws Exception {
    CelAbstractSyntaxTree ast = CEL_COMPILER.compile("msg.single_nested_enum").getAst();
    TestAllTypes nestedMessage =
        TestAllTypes.newBuilder().setSingleNestedEnum(NestedEnum.BAR).build();
    Long result = (Long) CEL_RUNTIME.createProgram(ast).eval(ImmutableMap.of("msg", nestedMessage));

    assertThat(result).isEqualTo(NestedEnum.BAR.getNumber());
  }

  @SuppressWarnings("ImmutableEnumChecker") // Test only
  private enum DefaultValueTestCase {
    INT32("msg.single_int32", 0L),
    INT64("msg.single_int64", 0L),
    UINT32("msg.single_uint32", UnsignedLong.ZERO),
    UINT64("msg.single_uint64", UnsignedLong.ZERO),
    SINT32("msg.single_sint32", 0L),
    SINT64("msg.single_sint64", 0L),
    FIXED32("msg.single_fixed32", 0L),
    FIXED64("msg.single_fixed64", 0L),
    SFIXED32("msg.single_sfixed32", 0L),
    SFIXED64("msg.single_sfixed64", 0L),
    FLOAT("msg.single_float", 0.0d),
    DOUBLE("msg.single_double", 0.0d),
    BOOL("msg.single_bool", false),
    STRING("msg.single_string", ""),
    BYTES("msg.single_bytes", CelByteString.EMPTY),
    ENUM("msg.standalone_enum", 0L),
    NESTED_MESSAGE("msg.single_nested_message", NestedMessage.getDefaultInstance()),
    OPTIONAL_BOOL("msg.optional_bool", false),
    REPEATED_STRING("msg.repeated_string", Collections.unmodifiableList(new ArrayList<>())),
    MAP_INT32_BOOL("msg.map_int32_bool", Collections.unmodifiableMap(new HashMap<>())),
    ;

    private final String expression;
    private final Object expectedValue;

    DefaultValueTestCase(String expression, Object expectedValue) {
      this.expression = expression;
      this.expectedValue = expectedValue;
    }
  }

  @Test
  public void unsetField_defaultValue(@TestParameter DefaultValueTestCase testCase)
      throws Exception {
    CelAbstractSyntaxTree ast = CEL_COMPILER.compile(testCase.expression).getAst();

    Object result =
        CEL_RUNTIME
            .createProgram(ast)
            .eval(ImmutableMap.of("msg", TestAllTypes.getDefaultInstance()));

    assertThat(result).isEqualTo(testCase.expectedValue);
  }

  @Test
  public void nestedMessage_fromImportedProto() throws Exception {
    CelCompiler celCompiler =
        CelCompilerFactory.standardCelCompilerBuilder()
            .addVar(
                "multiFile", StructTypeReference.create(MultiFile.getDescriptor().getFullName()))
            .addMessageTypes(MultiFile.getDescriptor())
            .build();
    CelLiteRuntime celRuntime =
        CelLiteRuntimeFactory.newLiteRuntimeBuilder()
            .setStandardFunctions(CelStandardFunctions.ALL_STANDARD_FUNCTIONS)
            .setValueProvider(
                ProtoMessageLiteValueProvider.newInstance(
                    SingleFileCelDescriptor.getDescriptor(),
                    MultiFileCelDescriptor.getDescriptor()))
            .setTypeProvider(
                ProtoMessageTypeProvider.newBuilder()
                    .addDescriptors(
                        ImmutableList.of(SingleFile.getDescriptor(), MultiFile.getDescriptor()))
                    .build())
            .build();

    CelAbstractSyntaxTree ast = celCompiler.compile("multiFile.nested_single_file.name").getAst();

    String result =
        (String)
            celRuntime
                .createProgram(ast)
                .eval(
                    ImmutableMap.of(
                        "multiFile",
                        MultiFile.newBuilder()
                            .setNestedSingleFile(SingleFile.newBuilder().setName("foo").build())
                            .build()));

    assertThat(result).isEqualTo("foo");
  }

  @Test
  public void eval_withLateBoundFunction() throws Exception {
    CelCompiler celCompiler =
        CelCompilerFactory.standardCelCompilerBuilder()
            .addFunctionDeclarations(
                CelFunctionDecl.newFunctionDeclaration(
                    "lateBoundFunc",
                    CelOverloadDecl.newGlobalOverload(
                        "lateBoundFunc_string", SimpleType.STRING, SimpleType.STRING)))
            .build();
    CelLiteRuntime celRuntime =
        CelLiteRuntimeFactory.newLiteRuntimeBuilder()
            .addLateBoundFunctions("lateBoundFunc")
            .build();
    CelAbstractSyntaxTree ast = celCompiler.compile("lateBoundFunc('hello')").getAst();

    String result =
        (String)
            celRuntime
                .createProgram(ast)
                .eval(
                    ImmutableMap.of(),
                    CelLateFunctionBindings.from(
                        CelFunctionBinding.from(
                            "lateBoundFunc_string", String.class, arg -> arg + " world")));

    assertThat(result).isEqualTo("hello world");
  }

  @Test
  public void eval_dynFunctionReturnsProto() throws Exception {
    CelCompiler celCompiler =
        CelCompilerFactory.standardCelCompilerBuilder()
            .addFunctionDeclarations(
                CelFunctionDecl.newFunctionDeclaration(
                    "func", CelOverloadDecl.newGlobalOverload("func_identity", SimpleType.DYN)))
            .build();
    CelLiteRuntime celRuntime =
        CelLiteRuntimeFactory.newLiteRuntimeBuilder()
            .setValueProvider(
                ProtoMessageLiteValueProvider.newInstance(
                    TestAllTypesCelDescriptor.getDescriptor()))
            .addFunctionBindings(
                CelFunctionBinding.from(
                    "func_identity",
                    ImmutableList.of(),
                    unused -> TestAllTypes.getDefaultInstance()))
            .build();

    CelAbstractSyntaxTree ast = celCompiler.compile("func()").getAst();

    TestAllTypes result = (TestAllTypes) celRuntime.createProgram(ast).eval();

    assertThat(result).isEqualToDefaultInstance();
  }

  @Test
  public void eval_withEnumField() throws Exception {
    CelCompiler celCompiler =
        CelCompilerFactory.standardCelCompilerBuilder()
            .addVar(
                "msg", StructTypeReference.create(MessageWithEnum.getDescriptor().getFullName()))
            .addMessageTypes(MessageWithEnum.getDescriptor())
            .build();
    CelLiteRuntime celLiteRuntime =
        CelLiteRuntimeFactory.newLiteRuntimeBuilder()
            .setStandardFunctions(CelStandardFunctions.ALL_STANDARD_FUNCTIONS)
            .setValueProvider(
                ProtoMessageLiteValueProvider.newInstance(
                    MessageWithEnumCelDescriptor.getDescriptor()))
            .setTypeProvider(
                ProtoMessageTypeProvider.newBuilder()
                    .addFileDescriptors(ImmutableList.of(MessageWithEnum.getDescriptor().getFile()))
                    .build())
            .build();
    CelAbstractSyntaxTree ast = celCompiler.compile("msg.simple_enum").getAst();

    Long result =
        (Long)
            celLiteRuntime
                .createProgram(ast)
                .eval(
                    ImmutableMap.of(
                        "msg", MessageWithEnum.newBuilder().setSimpleEnum(SimpleEnum.BAR).build()));

    assertThat(result).isEqualTo(SimpleEnum.BAR.getNumber());
  }
}
