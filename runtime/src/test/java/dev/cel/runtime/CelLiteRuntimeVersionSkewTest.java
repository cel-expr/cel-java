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

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.truth.Truth.assertThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertThrows;

import dev.cel.expr.CheckedExpr;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.primitives.UnsignedLong;
import com.google.protobuf.ByteString;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.MessageOptions;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.Empty;
import com.google.protobuf.ExtensionRegistryLite;
import com.google.protobuf.FieldMask;
import com.google.protobuf.Int64Value;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.ListValue;
import com.google.protobuf.MessageLite;
import com.google.protobuf.NullValue;
import com.google.protobuf.Struct;
import com.google.protobuf.UnknownFieldSet;
import com.google.protobuf.Value;
import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import com.google.testing.junit.testparameterinjector.TestParameters;
import dev.cel.bundle.Cel;
import dev.cel.bundle.CelFactory;
import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.CelContainer;
import dev.cel.common.CelFunctionDecl;
import dev.cel.common.CelOptions;
import dev.cel.common.CelOverloadDecl;
import dev.cel.common.CelProtoAbstractSyntaxTree;
import dev.cel.common.ast.CelBlock;
import dev.cel.common.internal.ProtoTimeUtils;
import dev.cel.common.types.ListType;
import dev.cel.common.types.SimpleType;
import dev.cel.common.types.StructTypeReference;
import dev.cel.common.values.CelByteString;
import dev.cel.common.values.ProtoMessageLiteValueProvider;
import dev.cel.common.values.WireMessageLite;
import dev.cel.expr.conformance.proto3.NestedTestAllTypes;
import dev.cel.expr.conformance.proto3.NestedTestAllTypesCelDescriptor;
import dev.cel.expr.conformance.proto3.TestAllTypes;
import dev.cel.expr.conformance.proto3.TestAllTypes.NestedEnum;
import dev.cel.expr.conformance.proto3.TestAllTypes.NestedMessage;
import dev.cel.expr.conformance.proto3.TestAllTypesCelDescriptor;
import dev.cel.extensions.CelExtensions;
import dev.cel.optimizer.CelOptimizer;
import dev.cel.optimizer.CelOptimizerFactory;
import dev.cel.optimizer.optimizers.SelectOptimizer;
import dev.cel.optimizer.optimizers.SelectOptimizer.SelectOptimizerOptions;
import dev.cel.optimizer.optimizers.SubexpressionOptimizer;
import dev.cel.parser.CelStandardMacro;
import dev.cel.protobuf.CelLiteDescriptor;
import dev.cel.protobuf.CelLiteDescriptor.FieldLiteDescriptor;
import dev.cel.protobuf.CelLiteDescriptor.MessageLiteDescriptor;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Simulates proto schema version skew between a server and an Android client.
 *
 * <ul>
 *   <li><b>Server (schema V2)</b>: owns the newest {@code TestAllTypes} definition. It compiles and
 *       optimizes CEL expressions against full V2 descriptors ({@code serverCompiler}, {@code
 *       serverOptimizer}), then ships the checked AST to the client.
 *   <li><b>Android client (schema V1)</b>: an APK built earlier against an older {@code
 *       TestAllTypes}. It evaluates the server's AST with a descriptorless {@link CelLiteRuntime}
 *       ({@code clientRuntime}) whose {@link CelLiteDescriptor} lacks {@link
 *       #SERVER_ONLY_FIELD_NAMES} and knows {@link #CLIENT_RENAMED_FIELD_NAMES} under their old
 *       names.
 * </ul>
 *
 * <p>Input messages are built with the server's V2 classes, so fields the client does not know
 * reach its runtime only as unknown wire bytes.
 */
@RunWith(TestParameterInjector.class)
public final class CelLiteRuntimeVersionSkewTest {

  private static final CelContainer CEL_CONTAINER =
      CelContainer.ofName("cel.expr.conformance.proto3");

  private static final CelOptions CEL_OPTIONS =
      CelOptions.current()
          .populateMacroCalls(true)
          .enableHeterogeneousNumericComparisons(true)
          .build();

  private static final ImmutableSet<String> SERVER_ONLY_FIELD_NAMES =
      ImmutableSet.of(
          "single_int64",
          "single_uint32",
          "single_uint64",
          "single_sint32",
          "single_sint64",
          "single_fixed32",
          "single_fixed64",
          "single_sfixed32",
          "single_sfixed64",
          "single_float",
          "single_double",
          "single_bool",
          "single_string",
          "single_bytes",
          "single_nested_message",
          "single_nested_enum",
          "standalone_enum",
          "single_duration",
          "single_timestamp",
          "single_struct",
          "single_value",
          "single_int64_wrapper",
          "single_string_wrapper",
          "list_value",
          "field_mask",
          "empty",
          "oneof_type",
          "oneof_bool",
          "repeated_int64",
          "repeated_uint32",
          "repeated_sint32",
          "repeated_sint64",
          "repeated_fixed32",
          "repeated_fixed64",
          "repeated_sfixed32",
          "repeated_sfixed64",
          "repeated_float",
          "repeated_double",
          "repeated_bool",
          "repeated_string",
          "repeated_bytes",
          "repeated_nested_message",
          "repeated_nested_enum",
          "repeated_duration",
          "repeated_timestamp",
          "repeated_int64_wrapper",
          "map_bool_string",
          "map_int32_int32",
          "map_int64_nested_type",
          "map_uint32_string",
          "map_uint64_string",
          "map_string_message",
          "map_string_duration",
          "map_string_timestamp",
          "map_string_int64_wrapper");

  private static final ImmutableMap<String, String> CLIENT_RENAMED_FIELD_NAMES =
      ImmutableMap.of(
          "single_int32", "v1_single_int32",
          "standalone_message", "v1_standalone_message",
          "repeated_int32", "v1_repeated_int32",
          "map_string_string", "v1_map_string_string",
          "map_int64_message", "v1_map_int64_message");

  private static final TestAllTypes POPULATED_SERVER_MESSAGE =
      TestAllTypes.newBuilder()
          .setSingleInt64(-42L)
          .setSingleUint32(123)
          .setSingleUint64(999L)
          .setSingleSint32(-15)
          .setSingleSint64(-250L)
          .setSingleFixed32(320)
          .setSingleFixed64(640L)
          .setSingleSfixed32(-32)
          .setSingleSfixed64(-64L)
          .setSingleFloat(1.5f)
          .setSingleDouble(0.85d)
          .setSingleBool(true)
          .setSingleString("cel-skew-test")
          .setSingleBytes(ByteString.copyFromUtf8("binary"))
          .setSingleNestedMessage(NestedMessage.newBuilder().setBb(123).build())
          .setStandaloneEnum(NestedEnum.BAZ)
          .setSingleDuration(ProtoTimeUtils.toProtoDuration(Duration.ofHours(1)))
          .setSingleTimestamp(
              ProtoTimeUtils.toProtoTimestamp(Instant.ofEpochSecond(1700000000L, 500L)))
          .setOneofBool(true)
          .addRepeatedInt64(10L)
          .addRepeatedInt64(20L)
          .addRepeatedUint32(10)
          .addRepeatedUint32(-1)
          .addRepeatedSint32(-100)
          .addRepeatedSint32(200)
          .addRepeatedSint64(-1000L)
          .addRepeatedSint64(2000L)
          .addRepeatedFixed32(300)
          .addRepeatedFixed32(-1)
          .addRepeatedFixed64(4000L)
          .addRepeatedFixed64(-1L)
          .addRepeatedSfixed32(-300)
          .addRepeatedSfixed32(400)
          .addRepeatedSfixed64(-3000L)
          .addRepeatedSfixed64(4000L)
          .addRepeatedFloat(1.5f)
          .addRepeatedFloat(-2.5f)
          .addRepeatedDouble(0.25d)
          .addRepeatedDouble(0.75d)
          .addRepeatedBool(true)
          .addRepeatedBool(false)
          .addRepeatedString("foo")
          .addRepeatedString("bar")
          .addRepeatedBytes(ByteString.copyFromUtf8("b1"))
          .addRepeatedBytes(ByteString.EMPTY)
          .addRepeatedNestedEnum(NestedEnum.BAR)
          .addRepeatedNestedEnum(NestedEnum.BAZ)
          .addRepeatedDuration(ProtoTimeUtils.toProtoDuration(Duration.ofMinutes(5)))
          .addRepeatedDuration(
              ProtoTimeUtils.toProtoDuration(Duration.ofSeconds(-10, -500_000_000)))
          .addRepeatedTimestamp(
              ProtoTimeUtils.toProtoTimestamp(Instant.ofEpochSecond(1700000000L, 500L)))
          .addRepeatedTimestamp(ProtoTimeUtils.toProtoTimestamp(Instant.EPOCH))
          .addRepeatedNestedMessage(NestedMessage.newBuilder().setBb(10).build())
          .addRepeatedNestedMessage(NestedMessage.newBuilder().setBb(20).build())
          .putMapInt32Int32(1, 2)
          .putMapStringMessage("m1", NestedMessage.newBuilder().setBb(55).build())
          .putMapStringDuration("d", ProtoTimeUtils.toProtoDuration(Duration.ofMinutes(5)))
          .putMapStringTimestamp(
              "t", ProtoTimeUtils.toProtoTimestamp(Instant.ofEpochSecond(1700000000L, 500L)))
          .putMapInt64NestedType(
              1L,
              NestedTestAllTypes.newBuilder()
                  .setPayload(
                      TestAllTypes.newBuilder()
                          .setSingleInt64(100L)
                          .addRepeatedInt64(7L)
                          .addRepeatedInt64(8L))
                  .setChild(
                      NestedTestAllTypes.newBuilder()
                          .setPayload(
                              TestAllTypes.newBuilder()
                                  .addRepeatedNestedMessage(NestedMessage.newBuilder().setBb(10))
                                  .addRepeatedNestedMessage(NestedMessage.newBuilder().setBb(20))
                                  .putMapInt32Int32(10, 100)
                                  .putMapInt32Int32(20, 200)))
                  .build())
          .putMapInt64NestedType(
              2L,
              NestedTestAllTypes.newBuilder()
                  .setPayload(TestAllTypes.newBuilder().setSingleInt64(200L).addRepeatedInt64(9L))
                  .build())
          .build();

  private static final TestAllTypes POPULATED_RENAMED_MESSAGE =
      TestAllTypes.newBuilder()
          .setSingleInt32(42)
          .setStandaloneMessage(NestedMessage.newBuilder().setBb(77).build())
          .addRepeatedInt32(10)
          .addRepeatedInt32(20)
          .putMapStringString("k", "v")
          .putMapInt64Message(1L, NestedMessage.newBuilder().setBb(100).build())
          .build();

  private Cel serverCompiler;
  private CelOptimizer serverOptimizer;
  private CelLiteRuntime clientRuntime;

  @Before
  public void setUp() {
    // Server (schema V2): compiles and optimizes expressions against the newest descriptors.
    serverCompiler =
        CelFactory.plannerCelBuilder()
            .setOptions(CEL_OPTIONS)
            .setStandardMacros(CelStandardMacro.STANDARD_MACROS)
            .addCompilerLibraries(CelExtensions.bindings())
            .addMessageTypes(TestAllTypes.getDescriptor(), NestedTestAllTypes.getDescriptor())
            .addVar("msg", StructTypeReference.create(TestAllTypes.getDescriptor().getFullName()))
            .addVar(
                "nested_msg",
                StructTypeReference.create(NestedTestAllTypes.getDescriptor().getFullName()))
            .setContainer(CEL_CONTAINER)
            .build();

    serverOptimizer =
        CelOptimizerFactory.standardCelOptimizerBuilder(serverCompiler)
            .addAstOptimizers(
                SelectOptimizer.newInstance(
                    SelectOptimizerOptions.newBuilder().build(),
                    TestAllTypes.getDescriptor().getFile()))
            .build();

    // Android client (schema V1): an older CelLiteDescriptor that omits SERVER_ONLY_FIELD_NAMES and
    // knows CLIENT_RENAMED_FIELD_NAMES under their old names (same field numbers). Configured
    // without ProtoMessageTypeProvider to match Android's descriptorless CelLiteRuntime.
    CelLiteDescriptor fullDescriptor = TestAllTypesCelDescriptor.getDescriptor();
    MessageLiteDescriptor clientTestAllTypesDesc =
        buildClientTestAllTypesDescriptor(
            fullDescriptor
                .getProtoTypeNamesToDescriptors()
                .get(TestAllTypes.getDescriptor().getFullName()));

    ImmutableList.Builder<MessageLiteDescriptor> allMsgDescs = ImmutableList.builder();
    for (MessageLiteDescriptor d : fullDescriptor.getProtoTypeNamesToDescriptors().values()) {
      if (d.getProtoTypeName().equals(TestAllTypes.getDescriptor().getFullName())) {
        allMsgDescs.add(clientTestAllTypesDesc);
      } else {
        allMsgDescs.add(d);
      }
    }

    CelLiteDescriptor clientDescriptor =
        newClientCelLiteDescriptor("v1", fullDescriptor, allMsgDescs.build());
    ProtoMessageLiteValueProvider clientValueProvider =
        ProtoMessageLiteValueProvider.newInstance(
            clientDescriptor, NestedTestAllTypesCelDescriptor.getDescriptor());

    clientRuntime =
        CelLiteRuntimeFactory.newLiteRuntimeBuilder()
            .setStandardFunctions(CelStandardFunctions.ALL_STANDARD_FUNCTIONS)
            .setValueProvider(clientValueProvider)
            .setContainer(CEL_CONTAINER)
            .build();
  }

  @SuppressWarnings("ImmutableEnumChecker") // Test only
  private enum UnknownScalarTestCase {
    INT64("msg.single_int64", 0L, -42L),
    UINT32("msg.single_uint32", UnsignedLong.ZERO, UnsignedLong.valueOf(123L)),
    UINT64("msg.single_uint64", UnsignedLong.ZERO, UnsignedLong.valueOf(999L)),
    SINT32("msg.single_sint32", 0L, -15L),
    SINT64("msg.single_sint64", 0L, -250L),
    FIXED32("msg.single_fixed32", UnsignedLong.ZERO, UnsignedLong.valueOf(320L)),
    FIXED64("msg.single_fixed64", UnsignedLong.ZERO, UnsignedLong.valueOf(640L)),
    SFIXED32("msg.single_sfixed32", 0L, -32L),
    SFIXED64("msg.single_sfixed64", 0L, -64L),
    FLOAT("msg.single_float", 0.0d, 1.5d),
    DOUBLE("msg.single_double", 0.0d, 0.85d),
    BOOL("msg.single_bool", false, true),
    STRING("msg.single_string", "", "cel-skew-test"),
    BYTES("msg.single_bytes", CelByteString.EMPTY, CelByteString.of("binary".getBytes(UTF_8))),
    STANDALONE_ENUM("msg.standalone_enum", 0L, (long) NestedEnum.BAZ.getNumber()),
    DURATION("msg.single_duration", Duration.ZERO, Duration.ofHours(1)),
    TIMESTAMP("msg.single_timestamp", Instant.EPOCH, Instant.ofEpochSecond(1700000000L, 500L)),
    ONEOF_BOOL("msg.oneof_bool", false, true),
    SUBMESSAGE_SCALAR("msg.single_nested_message.bb", 0L, 123L);

    private final String expression;
    private final Object expectedDefault;
    private final Object expectedPopulated;

    UnknownScalarTestCase(String expression, Object expectedDefault, Object expectedPopulated) {
      this.expression = expression;
      this.expectedDefault = expectedDefault;
      this.expectedPopulated = expectedPopulated;
    }
  }

  @Test
  public void select_unsetUnknownScalar_returnsBakedDefault(
      @TestParameter UnknownScalarTestCase testCase) throws Exception {
    TestAllTypes msg = TestAllTypes.getDefaultInstance();

    Object result = eval(testCase.expression, msg);

    assertThat(result).isEqualTo(testCase.expectedDefault);
  }

  @Test
  public void select_populatedUnknownScalar_decodesFromWireBytes(
      @TestParameter UnknownScalarTestCase testCase) throws Exception {
    Object result = eval(testCase.expression, POPULATED_SERVER_MESSAGE);

    assertThat(result).isEqualTo(testCase.expectedPopulated);
  }

  @Test
  public void select_populatedUnknownOneofEnum_decodesFromWireBytes() throws Exception {
    TestAllTypes msg = TestAllTypes.newBuilder().setSingleNestedEnum(NestedEnum.BAR).build();

    Object result = eval("msg.single_nested_enum == TestAllTypes.NestedEnum.BAR", msg);

    assertThat(result).isEqualTo(true);
  }

  @Test
  public void select_explicitZeroUnknownOneofEnum_evaluatesPresenceAndZeroValue() throws Exception {
    TestAllTypes msg = TestAllTypes.newBuilder().setSingleNestedEnum(NestedEnum.FOO).build();

    Object result =
        eval(
            "has(msg.single_nested_enum) && msg.single_nested_enum == TestAllTypes.NestedEnum.FOO",
            msg);

    assertThat(result).isEqualTo(true);
  }

  @Test
  public void select_unsetUnknownOneofEnum_evaluatesAbsentAndDefaultZeroValue() throws Exception {
    TestAllTypes msg = TestAllTypes.getDefaultInstance();

    Object result =
        eval(
            "!has(msg.single_nested_enum) && msg.single_nested_enum == TestAllTypes.NestedEnum.FOO",
            msg);

    assertThat(result).isEqualTo(true);
  }

  @Test
  public void select_populatedUnknownOneofSubmessage_decodesFromWireBytes() throws Exception {
    TestAllTypes msg =
        TestAllTypes.newBuilder()
            .setOneofType(
                NestedTestAllTypes.newBuilder()
                    .setPayload(TestAllTypes.newBuilder().setSingleInt64(88L)))
            .build();

    Object result = eval("msg.oneof_type.payload.single_int64", msg);

    assertThat(result).isEqualTo(88L);
  }

  @Test
  public void select_unsetUnknownOneofSubmessage_returnsBakedDefault() throws Exception {
    TestAllTypes msg = TestAllTypes.getDefaultInstance();

    Object result = eval("msg.oneof_type.payload.single_int64", msg);

    assertThat(result).isEqualTo(0L);
  }

  @SuppressWarnings("ImmutableEnumChecker") // Test only
  private enum UnknownFieldPresenceTestCase {
    INT64("has(msg.single_int64)"),
    UINT64("has(msg.single_uint64)"),
    DOUBLE("has(msg.single_double)"),
    BOOL("has(msg.single_bool)"),
    STRING("has(msg.single_string)"),
    BYTES("has(msg.single_bytes)"),
    STANDALONE_ENUM("has(msg.standalone_enum)"),
    DURATION("has(msg.single_duration)"),
    TIMESTAMP("has(msg.single_timestamp)"),
    ONEOF_BOOL("has(msg.oneof_bool)"),
    SUBMESSAGE("has(msg.single_nested_message)"),
    SUBMESSAGE_SCALAR("has(msg.single_nested_message.bb)"),
    REPEATED_INT64("has(msg.repeated_int64)"),
    REPEATED_STRING("has(msg.repeated_string)"),
    REPEATED_SUBMESSAGE("has(msg.repeated_nested_message)"),
    MAP_INT32_INT32("has(msg.map_int32_int32)");

    private final String expression;

    UnknownFieldPresenceTestCase(String expression) {
      this.expression = expression;
    }
  }

  @Test
  public void has_unknownField_whenPresentOnWire_returnsTrue(
      @TestParameter UnknownFieldPresenceTestCase testCase) throws Exception {
    Object result = eval(testCase.expression, POPULATED_SERVER_MESSAGE);

    assertThat(result).isEqualTo(true);
  }

  @Test
  public void has_unknownField_whenAbsentOnWire_returnsFalse(
      @TestParameter UnknownFieldPresenceTestCase testCase) throws Exception {
    TestAllTypes msg = TestAllTypes.getDefaultInstance();

    Object result = eval(testCase.expression, msg);

    assertThat(result).isEqualTo(false);
  }

  @Test
  public void has_unknownSubmessageField_whenSubmessagePresentButFieldUnset_returnsFalse()
      throws Exception {
    TestAllTypes msg =
        TestAllTypes.newBuilder()
            .setSingleNestedMessage(NestedMessage.getDefaultInstance())
            .build();

    Object result = eval("has(msg.single_nested_message.bb)", msg);

    assertThat(result).isEqualTo(false);
  }

  @SuppressWarnings("ImmutableEnumChecker") // Test only
  private enum UnknownRepeatedScalarTestCase {
    REPEATED_INT64("msg.repeated_int64", ImmutableList.of(10L, 20L)),
    REPEATED_UINT32(
        "msg.repeated_uint32",
        ImmutableList.of(UnsignedLong.valueOf(10L), UnsignedLong.valueOf(0xFFFFFFFFL))),
    REPEATED_SINT32("msg.repeated_sint32", ImmutableList.of(-100L, 200L)),
    REPEATED_SINT64("msg.repeated_sint64", ImmutableList.of(-1000L, 2000L)),
    REPEATED_FIXED32(
        "msg.repeated_fixed32",
        ImmutableList.of(UnsignedLong.valueOf(300L), UnsignedLong.valueOf(0xFFFFFFFFL))),
    REPEATED_FIXED64(
        "msg.repeated_fixed64",
        ImmutableList.of(UnsignedLong.valueOf(4000L), UnsignedLong.MAX_VALUE)),
    REPEATED_SFIXED32("msg.repeated_sfixed32", ImmutableList.of(-300L, 400L)),
    REPEATED_SFIXED64("msg.repeated_sfixed64", ImmutableList.of(-3000L, 4000L)),
    REPEATED_FLOAT("msg.repeated_float", ImmutableList.of(1.5d, -2.5d)),
    REPEATED_DOUBLE("msg.repeated_double", ImmutableList.of(0.25d, 0.75d)),
    REPEATED_BOOL("msg.repeated_bool", ImmutableList.of(true, false)),
    REPEATED_STRING("msg.repeated_string", ImmutableList.of("foo", "bar")),
    REPEATED_BYTES(
        "msg.repeated_bytes",
        ImmutableList.of(CelByteString.of("b1".getBytes(UTF_8)), CelByteString.EMPTY)),
    REPEATED_NESTED_ENUM(
        "msg.repeated_nested_enum",
        ImmutableList.of((long) NestedEnum.BAR.getNumber(), (long) NestedEnum.BAZ.getNumber())),
    REPEATED_DURATION(
        "msg.repeated_duration",
        ImmutableList.of(Duration.ofMinutes(5), Duration.ofSeconds(-10, -500_000_000))),
    REPEATED_TIMESTAMP(
        "msg.repeated_timestamp",
        ImmutableList.of(Instant.ofEpochSecond(1700000000L, 500L), Instant.EPOCH));

    private final String expression;
    private final ImmutableList<Object> expectedElements;

    UnknownRepeatedScalarTestCase(String expression, ImmutableList<Object> expectedElements) {
      this.expression = expression;
      this.expectedElements = expectedElements;
    }
  }

  @Test
  public void select_unsetUnknownRepeatedScalar_returnsBakedEmptyList(
      @TestParameter UnknownRepeatedScalarTestCase testCase) throws Exception {
    TestAllTypes msg = TestAllTypes.getDefaultInstance();

    Object result = eval(testCase.expression, msg);

    assertThat((List<?>) result).isEmpty();
  }

  @Test
  public void select_populatedUnknownRepeatedScalar_decodesFromWireBytes(
      @TestParameter UnknownRepeatedScalarTestCase testCase) throws Exception {
    Object result = eval(testCase.expression, POPULATED_SERVER_MESSAGE);

    assertThat((List<?>) result).containsExactlyElementsIn(testCase.expectedElements).inOrder();
  }

  @Test
  public void select_populatedUnknownSubmessageLeaf_withKnownSubmessageType_returnsMessageLite()
      throws Exception {
    NestedMessage nested = NestedMessage.newBuilder().setBb(123).build();
    TestAllTypes msg = TestAllTypes.newBuilder().setSingleNestedMessage(nested).build();

    Object result = eval("msg.single_nested_message", msg);

    assertThat(result).isEqualTo(nested);
  }

  @Test
  public void select_unsetUnknownSubmessageLeaf_withKnownSubmessageType_returnsDefaultMessageLite()
      throws Exception {
    TestAllTypes msg = TestAllTypes.getDefaultInstance();

    Object result = eval("msg.single_nested_message", msg);

    assertThat(result).isEqualTo(NestedMessage.getDefaultInstance());
  }

  @Test
  public void
      select_populatedUnknownSubmessageLeaf_withoutSubmessageDescriptor_returnsWireMessageLite()
          throws Exception {
    NestedMessage nested = NestedMessage.newBuilder().setBb(123).build();
    TestAllTypes msg = TestAllTypes.newBuilder().setSingleNestedMessage(nested).build();
    CelLiteRuntime runtimeWithoutNestedDesc = newRuntimeWithoutNestedMessageDescriptor();
    CelAbstractSyntaxTree optimizedAst =
        serverOptimizer.optimize(serverCompiler.compile("msg.single_nested_message").getAst());
    Program program = runtimeWithoutNestedDesc.createProgram(optimizedAst);

    Object result = program.eval(ImmutableMap.of("msg", msg));

    assertThat(result).isInstanceOf(WireMessageLite.class);
    WireMessageLite wireMsg = (WireMessageLite) result;
    assertThat(wireMsg.protoTypeName())
        .isEqualTo("cel.expr.conformance.proto3.TestAllTypes.NestedMessage");
    assertThat(wireMsg.toByteString()).isEqualTo(nested.toByteString());
  }

  @Test
  public void
      select_unsetUnknownSubmessageLeaf_withoutSubmessageDescriptor_returnsEmptyWireMessageLite()
          throws Exception {
    TestAllTypes msg = TestAllTypes.getDefaultInstance();
    CelLiteRuntime runtimeWithoutNestedDesc = newRuntimeWithoutNestedMessageDescriptor();
    CelAbstractSyntaxTree optimizedAst =
        serverOptimizer.optimize(serverCompiler.compile("msg.single_nested_message").getAst());
    Program program = runtimeWithoutNestedDesc.createProgram(optimizedAst);

    Object result = program.eval(ImmutableMap.of("msg", msg));

    assertThat(result).isInstanceOf(WireMessageLite.class);
    WireMessageLite wireMsg = (WireMessageLite) result;
    assertThat(wireMsg.protoTypeName())
        .isEqualTo("cel.expr.conformance.proto3.TestAllTypes.NestedMessage");
    assertThat(wireMsg.toByteString()).isEqualTo(ByteString.EMPTY);
  }

  @Test
  public void
      select_populatedUnknownRepeatedSubmessageLeaf_withKnownSubmessageType_returnsMessageLiteList()
          throws Exception {
    Object result = eval("msg.repeated_nested_message", POPULATED_SERVER_MESSAGE);

    assertThat(result)
        .isEqualTo(
            ImmutableList.of(
                NestedMessage.newBuilder().setBb(10).build(),
                NestedMessage.newBuilder().setBb(20).build()));
  }

  @Test
  public void
      select_populatedUnknownMapSubmessageLeaf_withKnownSubmessageType_returnsMessageLiteMap()
          throws Exception {
    Object result = eval("msg.map_string_message", POPULATED_SERVER_MESSAGE);

    assertThat(result)
        .isEqualTo(ImmutableMap.of("m1", NestedMessage.newBuilder().setBb(55).build()));
  }

  @Test
  public void select_unsetUnknownMapField_returnsBakedDefault() throws Exception {
    TestAllTypes msg = TestAllTypes.getDefaultInstance();

    Object result = eval("msg.map_int32_int32", msg);

    assertThat(result).isEqualTo(ImmutableMap.of());
  }

  @Test
  public void select_populatedUnknownMapField_decodesMapFromWire() throws Exception {
    TestAllTypes msg = TestAllTypes.newBuilder().putMapInt32Int32(1, 2).build();

    Object result = eval("msg.map_int32_int32", msg);

    assertThat(result).isEqualTo(ImmutableMap.of(1L, 2L));
  }

  @SuppressWarnings("ImmutableEnumChecker") // Test only
  private enum RenamedFieldTestCase {
    SCALAR_INT32("msg.single_int32", "has(msg.single_int32)", 0L, 42L),
    SUBMESSAGE_LEAF(
        "msg.standalone_message",
        "has(msg.standalone_message)",
        NestedMessage.getDefaultInstance(),
        NestedMessage.newBuilder().setBb(77).build()),
    SUBMESSAGE_SCALAR("msg.standalone_message.bb", "has(msg.standalone_message.bb)", 0L, 77L),
    REPEATED_INT32(
        "msg.repeated_int32",
        "has(msg.repeated_int32)",
        ImmutableList.of(),
        ImmutableList.of(10L, 20L)),
    MAP_STRING_STRING(
        "msg.map_string_string",
        "has(msg.map_string_string)",
        ImmutableMap.of(),
        ImmutableMap.of("k", "v")),
    MAP_INT64_MESSAGE(
        "msg.map_int64_message",
        "has(msg.map_int64_message)",
        ImmutableMap.of(),
        ImmutableMap.of(1L, NestedMessage.newBuilder().setBb(100).build()));

    private final String selectExpression;
    private final String hasExpression;
    private final Object expectedDefault;
    private final Object expectedPopulated;

    RenamedFieldTestCase(
        String selectExpression,
        String hasExpression,
        Object expectedDefault,
        Object expectedPopulated) {
      this.selectExpression = selectExpression;
      this.hasExpression = hasExpression;
      this.expectedDefault = expectedDefault;
      this.expectedPopulated = expectedPopulated;
    }
  }

  @Test
  public void select_renamedField_whenPopulated_resolvesByFieldNumber(
      @TestParameter RenamedFieldTestCase testCase) throws Exception {
    Object result = eval(testCase.selectExpression, POPULATED_RENAMED_MESSAGE);

    assertThat(result).isEqualTo(testCase.expectedPopulated);
  }

  @Test
  public void select_renamedField_whenUnset_returnsDefault(
      @TestParameter RenamedFieldTestCase testCase) throws Exception {
    TestAllTypes msg = TestAllTypes.getDefaultInstance();

    Object result = eval(testCase.selectExpression, msg);

    assertThat(result).isEqualTo(testCase.expectedDefault);
  }

  @Test
  public void has_renamedField_whenPresent_returnsTrue(@TestParameter RenamedFieldTestCase testCase)
      throws Exception {
    Object result = eval(testCase.hasExpression, POPULATED_RENAMED_MESSAGE);

    assertThat(result).isEqualTo(true);
  }

  @Test
  public void has_renamedField_whenAbsent_returnsFalse(@TestParameter RenamedFieldTestCase testCase)
      throws Exception {
    TestAllTypes msg = TestAllTypes.getDefaultInstance();

    Object result = eval(testCase.hasExpression, msg);

    assertThat(result).isEqualTo(false);
  }

  @Test
  public void has_renamedSubmessageField_whenSubmessagePresentButFieldUnset_returnsFalse()
      throws Exception {
    TestAllTypes msg =
        TestAllTypes.newBuilder().setStandaloneMessage(NestedMessage.getDefaultInstance()).build();

    Object result = eval("has(msg.standalone_message.bb)", msg);

    assertThat(result).isEqualTo(false);
  }

  @Test
  public void select_renamedMapField_indexing() throws Exception {
    Object result = eval("msg.map_int64_message[1].bb", POPULATED_RENAMED_MESSAGE);

    assertThat(result).isEqualTo(100L);
  }

  @Test
  @TestParameters({
    "{expression: 'has(msg.repeated_nested_message[1].bb)', expected: true}",
    "{expression: 'has(msg.map_int64_nested_type[1].child.payload)', expected: true}",
    "{expression: 'has(msg.map_int64_nested_type[2].child.payload)', expected: false}"
  })
  public void has_throughUnknownRepeatedAndMapIndices_decodesFromWireBytes(
      String expression, boolean expected) throws Exception {
    Object result = eval(expression, POPULATED_SERVER_MESSAGE);

    assertThat(result).isEqualTo(expected);
  }

  @Test
  public void has_renamedRepeatedField_emptyPackedWireBytes_returnsFalse() throws Exception {
    // Evaluated via newOlderClassV1Runtime() on nested_msg.payload so payload is a wire-backed
    // ProtoMessageLiteValue (rawValue == null, !canReadByGetter()) with a known packable
    // FieldLiteDescriptor (v1_repeated_int32, tag 31).
    TestAllTypes wirePayload =
        TestAllTypes.newBuilder()
            .setUnknownFields(
                UnknownFieldSet.newBuilder()
                    .addField(
                        TestAllTypes.REPEATED_INT32_FIELD_NUMBER,
                        UnknownFieldSet.Field.newBuilder()
                            .addLengthDelimited(ByteString.EMPTY)
                            .build())
                    .build())
            .build();
    NestedTestAllTypes nestedMsg = NestedTestAllTypes.newBuilder().setPayload(wirePayload).build();
    Program program =
        newOlderClassV1Runtime()
            .createProgram(
                serverOptimizer.optimize(
                    serverCompiler.compile("has(nested_msg.payload.repeated_int32)").getAst()));

    Object result = program.eval(ImmutableMap.of("nested_msg", nestedMsg));

    assertThat(result).isEqualTo(false);
  }

  @Test
  public void has_renamedRepeatedField_emptyThenPopulatedPackedBytes_returnsTrue()
      throws Exception {
    TestAllTypes wirePayload =
        TestAllTypes.newBuilder()
            .setUnknownFields(
                UnknownFieldSet.newBuilder()
                    .addField(
                        TestAllTypes.REPEATED_INT32_FIELD_NUMBER,
                        UnknownFieldSet.Field.newBuilder()
                            .addLengthDelimited(ByteString.EMPTY)
                            .addLengthDelimited(ByteString.copyFrom(new byte[] {42}))
                            .build())
                    .build())
            .build();
    NestedTestAllTypes nestedMsg = NestedTestAllTypes.newBuilder().setPayload(wirePayload).build();
    Program program =
        newOlderClassV1Runtime()
            .createProgram(
                serverOptimizer.optimize(
                    serverCompiler
                        .compile(
                            "has(nested_msg.payload.repeated_int32) &&"
                                + " nested_msg.payload.repeated_int32 == [42]")
                        .getAst()));

    Object result = program.eval(ImmutableMap.of("nested_msg", nestedMsg));

    assertThat(result).isEqualTo(true);
  }

  @Test
  public void mixedExpression_versionSkewFieldsWithConditions() throws Exception {
    Object result =
        eval(
            "msg.single_int64 < 0 && msg.single_bool && msg.single_string == 'cel-skew-test' &&"
                + " msg.single_double > 0.7 && msg.standalone_enum == TestAllTypes.NestedEnum.BAZ"
                + " && msg.single_duration == duration('1h') && msg.single_timestamp > timestamp(0)"
                + " && has(msg.single_nested_message) && msg.single_nested_message.bb == 123",
            POPULATED_SERVER_MESSAGE);

    assertThat(result).isEqualTo(true);
  }

  @Test
  public void mixedExpression_renamedMapFieldsWithCondition() throws Exception {
    TestAllTypes msg =
        TestAllTypes.newBuilder()
            .putMapStringString("env", "prod")
            .putMapInt64Message(42L, NestedMessage.newBuilder().setBb(99).build())
            .build();

    Object result =
        eval(
            "has(msg.map_string_string) && msg.map_string_string['env'] == 'prod' &&"
                + " msg.map_int64_message[42].bb == 99",
            msg);

    assertThat(result).isEqualTo(true);
  }

  @Test
  public void fixedRootInput_populatedClientFieldsAndUnsetServerOnlyFields_evaluatesCleanly()
      throws Exception {
    // Models b/555872831#comment14: the client APK builds a fixed root payload that populates only
    // the fields it knows, leaving server-only fields (scalars, submessages, lists, maps,
    // durations) unset.
    TestAllTypes clientPayload =
        TestAllTypes.newBuilder()
            .setOptionalString("us")
            .setOptionalBool(true)
            .addRepeatedUint64(10L)
            .build();

    Object result =
        eval(
            "has(msg.optional_string) && msg.optional_string == 'us' &&"
                + " has(msg.optional_bool) && msg.optional_bool &&"
                + " msg.repeated_uint64 == [10u] &&"
                + " !has(msg.single_bool) && !msg.single_bool &&"
                + " msg.single_int64 == 0 && msg.single_double < 0.7 &&"
                + " msg.standalone_enum == TestAllTypes.NestedEnum.FOO &&"
                + " msg.single_duration == duration('0s') &&"
                + " msg.single_timestamp == timestamp(0) &&"
                + " !has(msg.single_nested_message) && msg.single_nested_message.bb == 0 &&"
                + " size(msg.repeated_nested_message) == 0 &&"
                + " size(msg.map_int32_int32) == 0",
            clientPayload);

    assertThat(result).isEqualTo(true);
  }

  @Test
  public void knownSubmessageChain_withUnsetServerOnlySubfieldsInChildPayload_returnsBakedDefaults()
      throws Exception {
    // Models b/555872831#comment14: root NestedTestAllTypes and intermediate hops (child.payload)
    // are known to the client, while skew occurs on server-only fields inside the child payload.
    NestedTestAllTypes nestedMsg =
        NestedTestAllTypes.newBuilder()
            .setChild(
                NestedTestAllTypes.newBuilder()
                    .setPayload(
                        TestAllTypes.newBuilder()
                            .setOptionalString("v1-child")
                            .setOptionalBool(true)
                            .build()))
            .build();

    Object result =
        evalNested(
            "nested_msg.child.payload.optional_string == 'v1-child' &&"
                + " nested_msg.child.payload.optional_bool &&"
                + " !has(nested_msg.child.payload.single_int64) &&"
                + " nested_msg.child.payload.single_int64 == 0 &&"
                + " !has(nested_msg.child.payload.single_nested_message.bb) &&"
                + " nested_msg.child.payload.single_nested_message.bb == 0 &&"
                + " size(nested_msg.child.payload.repeated_string) == 0",
            nestedMsg);

    assertThat(result).isEqualTo(true);
  }

  @Test
  public void knownSubmessageChain_whenIntermediateChildIsUnset_returnsBakedDefaults()
      throws Exception {
    NestedTestAllTypes emptyNestedMsg = NestedTestAllTypes.getDefaultInstance();

    Object result =
        evalNested(
            "!has(nested_msg.child.payload.single_int64) &&"
                + " nested_msg.child.payload.single_int64 == 0 &&"
                + " !has(nested_msg.child.payload.single_nested_message.bb) &&"
                + " nested_msg.child.payload.single_nested_message.bb == 0 &&"
                + " nested_msg.child.payload.single_duration == duration('0s')",
            emptyNestedMsg);

    assertThat(result).isEqualTo(true);
  }

  @Test
  public void
      knownSubmessageChain_withPopulatedServerOnlySubfieldsInChildPayload_decodesFromWireBytes()
          throws Exception {
    NestedTestAllTypes nestedMsg =
        NestedTestAllTypes.newBuilder()
            .setChild(NestedTestAllTypes.newBuilder().setPayload(POPULATED_SERVER_MESSAGE))
            .build();

    Object result =
        evalNested(
            "has(nested_msg.child.payload.single_int64) &&"
                + " nested_msg.child.payload.single_int64 == -42 &&"
                + " has(nested_msg.child.payload.single_nested_message.bb) &&"
                + " nested_msg.child.payload.single_nested_message.bb == 123 &&"
                + " nested_msg.child.payload.single_duration == duration('1h') &&"
                + " nested_msg.child.payload.repeated_string == ['foo', 'bar']",
            nestedMsg);

    assertThat(result).isEqualTo(true);
  }

  @Test
  public void submessageDescriptorAbsentFromPool_whenPopulated_evaluatesViaRawBytes()
      throws Exception {
    // Simulates a server-only submessage type (NestedMessage) whose MessageLiteDescriptor is
    // completely absent from the client's CelLiteDescriptorPool. Binding msg.single_nested_message
    // as a leaf hop produces a RawProtoMessageLiteValue, and sub.bb resolves the field directly
    // from wire bytes via SelectField metadata.
    CelLiteRuntime runtimeWithoutNestedDesc = newRuntimeWithoutNestedMessageDescriptor();
    CelAbstractSyntaxTree optimizedAst =
        serverOptimizer.optimize(
            serverCompiler
                .compile("cel.bind(sub, msg.single_nested_message, has(sub.bb) ? sub.bb : -1)")
                .getAst());
    Program program = runtimeWithoutNestedDesc.createProgram(optimizedAst);

    Object result = program.eval(ImmutableMap.of("msg", POPULATED_SERVER_MESSAGE));

    assertThat(result).isEqualTo(123L);
  }

  @Test
  public void submessageDescriptorAbsentFromPool_whenUnset_evaluatesDefaultViaRawBytes()
      throws Exception {
    CelLiteRuntime runtimeWithoutNestedDesc = newRuntimeWithoutNestedMessageDescriptor();
    CelAbstractSyntaxTree optimizedAst =
        serverOptimizer.optimize(
            serverCompiler
                .compile(
                    "cel.bind(sub, msg.single_nested_message, has(sub.bb) ? sub.bb : sub.bb - 1)")
                .getAst());
    Program program = runtimeWithoutNestedDesc.createProgram(optimizedAst);

    Object result = program.eval(ImmutableMap.of("msg", TestAllTypes.getDefaultInstance()));

    assertThat(result).isEqualTo(-1L);
  }

  @SuppressWarnings("ImmutableEnumChecker") // Test only
  private enum ComprehensionTestCase {
    EXISTS_MATCHING_SUBMESSAGE(
        "msg.repeated_nested_message.exists(x, x.bb == 20)", POPULATED_SERVER_MESSAGE, true),
    EXISTS_NON_MATCHING_SUBMESSAGE(
        "msg.repeated_nested_message.exists(x, x.bb == 99)", POPULATED_SERVER_MESSAGE, false),
    EXISTS_UNSET_SUBMESSAGE_LIST(
        "msg.repeated_nested_message.exists(x, x.bb == 20)",
        TestAllTypes.getDefaultInstance(),
        false),
    ALL_MATCHING_SUBMESSAGE(
        "msg.repeated_nested_message.all(x, x.bb > 0)", POPULATED_SERVER_MESSAGE, true),
    ALL_NON_MATCHING_SUBMESSAGE(
        "msg.repeated_nested_message.all(x, x.bb == 10)", POPULATED_SERVER_MESSAGE, false),
    ALL_UNSET_SUBMESSAGE_LIST(
        "msg.repeated_nested_message.all(x, x.bb > 0)", TestAllTypes.getDefaultInstance(), true),
    EXISTS_ONE_SUBMESSAGE(
        "msg.repeated_nested_message.exists_one(x, x.bb == 10)", POPULATED_SERVER_MESSAGE, true),
    EXISTS_ONE_MULTIPLE_MATCHING_SUBMESSAGE(
        "msg.repeated_nested_message.exists_one(x, x.bb > 0)", POPULATED_SERVER_MESSAGE, false),
    FILTER_AND_MAP_SUBMESSAGE(
        "msg.repeated_nested_message.filter(x, x.bb > 10).map(x, x.bb * 2)",
        POPULATED_SERVER_MESSAGE,
        ImmutableList.of(40L)),
    MAP_COMBINING_OUTER_SCALAR_AND_INNER_SUBMESSAGE(
        "msg.repeated_nested_message.map(x, x.bb + msg.single_int64)",
        POPULATED_SERVER_MESSAGE,
        ImmutableList.of(-32L, -22L)),
    MAP_UNSET_SUBMESSAGE_LIST(
        "msg.repeated_nested_message.map(x, x.bb + msg.single_int64)",
        TestAllTypes.getDefaultInstance(),
        ImmutableList.of()),
    EXISTS_REPEATED_STRING(
        "msg.repeated_string.exists(s, s == 'bar')", POPULATED_SERVER_MESSAGE, true),
    EXISTS_UNKNOWN_MAP(
        "msg.map_int32_int32.exists(k, msg.map_int32_int32[k] == 2)",
        POPULATED_SERVER_MESSAGE,
        true),
    ALL_UNKNOWN_MAP_OF_SUBMESSAGES(
        "msg.map_int64_nested_type.all(k,"
            + " msg.map_int64_nested_type[k].payload.single_int64 >= 100)",
        POPULATED_SERVER_MESSAGE,
        true),
    EXISTS_COMBINING_REPEATED_SUBMESSAGE_AND_POPULATED_UNKNOWN_MAP(
        "msg.repeated_nested_message.exists(x, x.bb == 20 && size(msg.map_int32_int32) == 1 &&"
            + " msg.map_int32_int32[1] == 2)",
        POPULATED_SERVER_MESSAGE,
        true),
    SHORT_CIRCUIT_SKIPPING_MISSING_UNKNOWN_MAP_KEY(
        "msg.repeated_nested_message.exists(x, x.bb == 10 || msg.map_int32_int32[999] > 0) &&"
            + " !msg.repeated_nested_message.all(x, x.bb > 15 && msg.map_int32_int32[999] > 0)",
        POPULATED_SERVER_MESSAGE,
        true);

    private final String expression;
    private final TestAllTypes message;
    private final Object expectedResult;

    ComprehensionTestCase(String expression, TestAllTypes message, Object expectedResult) {
      this.expression = expression;
      this.message = message;
      this.expectedResult = expectedResult;
    }
  }

  @Test
  public void comprehension_overUnknownRepeatedFields_evaluatesExpectedResult(
      @TestParameter ComprehensionTestCase testCase) throws Exception {
    Object result = eval(testCase.expression, testCase.message);

    assertThat(result).isEqualTo(testCase.expectedResult);
  }

  @Test
  public void nestedComprehension_acrossKnownAndUnknownRepeatedSubmessages_evaluatesCorrectly()
      throws Exception {
    NestedTestAllTypes nestedMsg =
        NestedTestAllTypes.newBuilder()
            .setPayload(TestAllTypes.newBuilder().addRepeatedInt32(5).addRepeatedInt32(20))
            .setChild(NestedTestAllTypes.newBuilder().setPayload(POPULATED_SERVER_MESSAGE))
            .build();

    Object result =
        evalNested(
            "nested_msg.payload.repeated_int32.exists(x,"
                + " nested_msg.child.payload.repeated_nested_message.exists(y, x == y.bb))",
            nestedMsg);

    assertThat(result).isEqualTo(true);
  }

  @Test
  public void celBind_unknownSubmessage_whenPopulated_evaluatesBoundSubfields() throws Exception {
    Object result =
        eval(
            "cel.bind(sub, msg.single_nested_message, has(sub.bb) ? sub.bb : -1)",
            POPULATED_SERVER_MESSAGE);

    assertThat(result).isEqualTo(123L);
  }

  @Test
  public void celBind_unknownSubmessage_whenUnset_evaluatesDefaultFallback() throws Exception {
    Object result =
        eval(
            "cel.bind(sub, msg.single_nested_message, has(sub.bb) ? sub.bb : -1)",
            TestAllTypes.getDefaultInstance());

    assertThat(result).isEqualTo(-1L);
  }

  @Test
  public void celBind_unknownSubmessageWithUnknownInnerField_whenPopulated_decodesFromWireBytes()
      throws Exception {
    TestAllTypes msg =
        TestAllTypes.newBuilder()
            .setOneofType(NestedTestAllTypes.newBuilder().setPayload(POPULATED_SERVER_MESSAGE))
            .build();

    Object result =
        eval("cel.bind(p, msg.oneof_type.payload, has(p.single_int64) ? p.single_int64 : -1)", msg);

    assertThat(result).isEqualTo(-42L);
  }

  @Test
  public void celBind_unknownSubmessageWithUnknownInnerField_whenUnset_evaluatesDefaultFallback()
      throws Exception {
    Object result =
        eval(
            "cel.bind(p, msg.oneof_type.payload, has(p.single_int64) ? p.single_int64 : -1)",
            TestAllTypes.getDefaultInstance());

    assertThat(result).isEqualTo(-1L);
  }

  @Test
  public void shortCircuiting_hasGuardOnUnsetUnknownMap_evaluatesEmptyMapSize() throws Exception {
    Object result =
        eval(
            "!has(msg.map_int32_int32) ? size(msg.map_int32_int32) : 99",
            TestAllTypes.getDefaultInstance());

    assertThat(result).isEqualTo(0L);
  }

  @Test
  public void has_multiHopUnknownSubmessage_whenUnset_returnsFalse() throws Exception {
    TestAllTypes msg = TestAllTypes.getDefaultInstance();

    Object result =
        eval("!has(msg.oneof_type.payload) && !has(msg.oneof_type.payload.single_int64)", msg);

    assertThat(result).isEqualTo(true);
  }

  @Test
  public void has_multiHopUnknownSubmessage_whenIntermediateSetButLeafUnset_evaluatesPresence()
      throws Exception {
    TestAllTypes msg =
        TestAllTypes.newBuilder().setOneofType(NestedTestAllTypes.getDefaultInstance()).build();

    Object result =
        eval(
            "has(msg.oneof_type) && !has(msg.oneof_type.payload) &&"
                + " !has(msg.oneof_type.payload.single_int64)",
            msg);

    assertThat(result).isEqualTo(true);
  }

  @Test
  public void
      has_multiHopUnknownSubmessage_whenIntermediatePayloadSetButLeafScalarUnset_evaluatesPresence()
          throws Exception {
    TestAllTypes msg =
        TestAllTypes.newBuilder()
            .setOneofType(
                NestedTestAllTypes.newBuilder().setPayload(TestAllTypes.getDefaultInstance()))
            .build();

    Object result =
        eval("has(msg.oneof_type.payload) && !has(msg.oneof_type.payload.single_int64)", msg);

    assertThat(result).isEqualTo(true);
  }

  @Test
  public void has_multiHopUnknownSubmessage_whenAllHopsPopulated_returnsTrue() throws Exception {
    TestAllTypes msg =
        TestAllTypes.newBuilder()
            .setOneofType(
                NestedTestAllTypes.newBuilder()
                    .setPayload(TestAllTypes.newBuilder().setSingleInt64(42L)))
            .build();

    Object result =
        eval(
            "has(msg.oneof_type) && has(msg.oneof_type.payload) &&"
                + " has(msg.oneof_type.payload.single_int64) &&"
                + " msg.oneof_type.payload.single_int64 == 42",
            msg);

    assertThat(result).isEqualTo(true);
  }

  @Test
  public void oneof_activeDiscriminator_whenUnknownVariantPopulated_evaluatesPresenceAndFallback()
      throws Exception {
    // oneof_bool is unknown to the client descriptor, oneof_msg is known to it.
    TestAllTypes msg = TestAllTypes.newBuilder().setOneofBool(true).build();

    Object result =
        eval(
            "has(msg.oneof_bool) && msg.oneof_bool && !has(msg.oneof_msg) && msg.oneof_msg.bb == 0",
            msg);

    assertThat(result).isEqualTo(true);
  }

  @Test
  public void oneof_activeDiscriminator_whenKnownVariantPopulated_evaluatesPresenceAndFallback()
      throws Exception {
    TestAllTypes msg =
        TestAllTypes.newBuilder().setOneofMsg(NestedMessage.newBuilder().setBb(77).build()).build();

    Object result =
        eval(
            "has(msg.oneof_msg) && msg.oneof_msg.bb == 77 && !has(msg.oneof_bool) &&"
                + " !msg.oneof_bool",
            msg);

    assertThat(result).isEqualTo(true);
  }

  @Test
  public void oneof_activeDiscriminator_whenExplicitFalseSetOnUnknownVariant_evaluatesPresenceTrue()
      throws Exception {
    TestAllTypes msg = TestAllTypes.newBuilder().setOneofBool(false).build();

    Object result = eval("has(msg.oneof_bool) && !msg.oneof_bool", msg);

    assertThat(result).isEqualTo(true);
  }

  @Test
  public void select_unknownEnumValueBeyondEnumRange_decodesRawNumericValue() throws Exception {
    TestAllTypes msg = TestAllTypes.newBuilder().setStandaloneEnumValue(99).build();

    Object result = eval("has(msg.standalone_enum) ? msg.standalone_enum : -1", msg);

    assertThat(result).isEqualTo(99L);
  }

  @Test
  public void select_negativeInt32InPopulatedMessage_evaluatesCorrectly() throws Exception {
    TestAllTypes msg = TestAllTypes.newBuilder().setSingleInt32(-42).build();

    Object result = eval("msg.single_int32 == -42 && msg.single_int32 < 0", msg);

    assertThat(result).isEqualTo(true);
  }

  @Test
  public void submessageEquality_withKnownSubmessageDescriptor_throwsUnsupportedOperationException(
      @TestParameter({
            "msg.single_nested_message == msg.single_nested_message",
            "msg.repeated_nested_message == msg.repeated_nested_message"
          })
          String expr)
      throws Exception {
    CelAbstractSyntaxTree optimizedAst =
        serverOptimizer.optimize(serverCompiler.compile(expr).getAst());
    Program program = clientRuntime.createProgram(optimizedAst);

    CelEvaluationException thrown =
        assertThrows(
            CelEvaluationException.class,
            () -> program.eval(ImmutableMap.of("msg", POPULATED_SERVER_MESSAGE)));

    assertThat(thrown).hasCauseThat().isInstanceOf(UnsupportedOperationException.class);
    assertThat(thrown).hasCauseThat().hasMessageThat().contains("Not implemented yet");
  }

  @Test
  public void submessageEquality_withoutSubmessageDescriptor_throwsUnsupportedOperationException(
      @TestParameter({
            "msg.single_nested_message == msg.single_nested_message",
            "msg.repeated_nested_message == msg.repeated_nested_message"
          })
          String expr)
      throws Exception {
    CelLiteRuntime runtimeWithoutNestedDesc = newRuntimeWithoutNestedMessageDescriptor();
    CelAbstractSyntaxTree optimizedAst =
        serverOptimizer.optimize(serverCompiler.compile(expr).getAst());
    Program program = runtimeWithoutNestedDesc.createProgram(optimizedAst);

    CelEvaluationException thrown =
        assertThrows(
            CelEvaluationException.class,
            () -> program.eval(ImmutableMap.of("msg", POPULATED_SERVER_MESSAGE)));

    assertThat(thrown).hasCauseThat().isInstanceOf(UnsupportedOperationException.class);
    assertThat(thrown)
        .hasCauseThat()
        .hasMessageThat()
        .contains("Message equality is not supported");
  }

  @Test
  public void select_unknownNegativeInt32_decodesFromRawWireBytes() throws Exception {
    CelLiteRuntime runtimeWithoutInt32 = newRuntimeWithoutFieldDescriptor("single_int32");
    CelAbstractSyntaxTree ast = serverCompiler.compile("msg.single_int32").getAst();
    CelAbstractSyntaxTree optimizedAst = serverOptimizer.optimize(ast);
    Program program = runtimeWithoutInt32.createProgram(optimizedAst);
    TestAllTypes msg = TestAllTypes.newBuilder().setSingleInt32(-42).build();

    Object result = program.eval(ImmutableMap.of("msg", msg));

    assertThat(result).isEqualTo(-42L);
  }

  @Test
  public void select_unknownRepeatedNegativeInt32_decodesTenByteVarintsFromPackedWireBytes()
      throws Exception {
    CelLiteRuntime runtimeWithoutRepeatedInt32 = newRuntimeWithoutFieldDescriptor("repeated_int32");
    CelAbstractSyntaxTree ast = serverCompiler.compile("msg.repeated_int32").getAst();
    CelAbstractSyntaxTree optimizedAst = serverOptimizer.optimize(ast);
    Program program = runtimeWithoutRepeatedInt32.createProgram(optimizedAst);
    TestAllTypes msg =
        TestAllTypes.newBuilder()
            .addRepeatedInt32(-42)
            .addRepeatedInt32(Integer.MIN_VALUE)
            .addRepeatedInt32(Integer.MAX_VALUE)
            .build();

    Object result = program.eval(ImmutableMap.of("msg", msg));

    assertThat((List<?>) result)
        .containsExactly(-42L, (long) Integer.MIN_VALUE, (long) Integer.MAX_VALUE)
        .inOrder();
  }

  @SuppressWarnings("ImmutableEnumChecker") // Test only
  private enum NumericBoundaryTestCase {
    UINT32_MAX_HIGH_BIT(
        "msg.single_uint32",
        TestAllTypes.newBuilder().setSingleUint32(-1).build(),
        UnsignedLong.valueOf(0xFFFFFFFFL)),
    FIXED32_MAX_HIGH_BIT(
        "msg.single_fixed32",
        TestAllTypes.newBuilder().setSingleFixed32(-1).build(),
        UnsignedLong.valueOf(0xFFFFFFFFL)),
    UINT64_MAX(
        "msg.single_uint64",
        TestAllTypes.newBuilder().setSingleUint64(-1L).build(),
        UnsignedLong.MAX_VALUE),
    FIXED64_MAX(
        "msg.single_fixed64",
        TestAllTypes.newBuilder().setSingleFixed64(-1L).build(),
        UnsignedLong.MAX_VALUE),
    INT64_MIN(
        "msg.single_int64",
        TestAllTypes.newBuilder().setSingleInt64(Long.MIN_VALUE).build(),
        Long.MIN_VALUE),
    INT64_MAX(
        "msg.single_int64",
        TestAllTypes.newBuilder().setSingleInt64(Long.MAX_VALUE).build(),
        Long.MAX_VALUE),
    SINT64_MIN_ZIGZAG(
        "msg.single_sint64",
        TestAllTypes.newBuilder().setSingleSint64(Long.MIN_VALUE).build(),
        Long.MIN_VALUE),
    SINT64_MAX_ZIGZAG(
        "msg.single_sint64",
        TestAllTypes.newBuilder().setSingleSint64(Long.MAX_VALUE).build(),
        Long.MAX_VALUE),
    SFIXED64_MIN(
        "msg.single_sfixed64",
        TestAllTypes.newBuilder().setSingleSfixed64(Long.MIN_VALUE).build(),
        Long.MIN_VALUE),
    SFIXED64_MAX(
        "msg.single_sfixed64",
        TestAllTypes.newBuilder().setSingleSfixed64(Long.MAX_VALUE).build(),
        Long.MAX_VALUE),
    SINT32_MIN_ZIGZAG(
        "msg.single_sint32",
        TestAllTypes.newBuilder().setSingleSint32(Integer.MIN_VALUE).build(),
        (long) Integer.MIN_VALUE),
    SINT32_MAX_ZIGZAG(
        "msg.single_sint32",
        TestAllTypes.newBuilder().setSingleSint32(Integer.MAX_VALUE).build(),
        (long) Integer.MAX_VALUE),
    SFIXED32_MIN(
        "msg.single_sfixed32",
        TestAllTypes.newBuilder().setSingleSfixed32(Integer.MIN_VALUE).build(),
        (long) Integer.MIN_VALUE),
    SFIXED32_MAX(
        "msg.single_sfixed32",
        TestAllTypes.newBuilder().setSingleSfixed32(Integer.MAX_VALUE).build(),
        (long) Integer.MAX_VALUE),
    DOUBLE_POSITIVE_INFINITY(
        "msg.single_double",
        TestAllTypes.newBuilder().setSingleDouble(Double.POSITIVE_INFINITY).build(),
        Double.POSITIVE_INFINITY),
    DOUBLE_NEGATIVE_INFINITY(
        "msg.single_double",
        TestAllTypes.newBuilder().setSingleDouble(Double.NEGATIVE_INFINITY).build(),
        Double.NEGATIVE_INFINITY),
    DOUBLE_NEGATIVE_ZERO(
        "msg.single_double", TestAllTypes.newBuilder().setSingleDouble(-0.0d).build(), -0.0d),
    FLOAT_POSITIVE_INFINITY(
        "msg.single_float",
        TestAllTypes.newBuilder().setSingleFloat(Float.POSITIVE_INFINITY).build(),
        Double.POSITIVE_INFINITY),
    FLOAT_NEGATIVE_INFINITY(
        "msg.single_float",
        TestAllTypes.newBuilder().setSingleFloat(Float.NEGATIVE_INFINITY).build(),
        Double.NEGATIVE_INFINITY),
    FLOAT_NEGATIVE_ZERO(
        "msg.single_float", TestAllTypes.newBuilder().setSingleFloat(-0.0f).build(), -0.0d);

    private final String expression;
    private final TestAllTypes message;
    private final Object expectedValue;

    NumericBoundaryTestCase(String expression, TestAllTypes message, Object expectedValue) {
      this.expression = expression;
      this.message = message;
      this.expectedValue = expectedValue;
    }
  }

  @Test
  public void select_unknownNumericBoundaryValues_decodesWithoutSignExtensionCorruption(
      @TestParameter NumericBoundaryTestCase testCase) throws Exception {
    Object result = eval(testCase.expression, testCase.message);

    assertThat(result).isEqualTo(testCase.expectedValue);
  }

  @Test
  public void select_unknownFloatingPointNaN_preservesCelNaNInequalitySemantics() throws Exception {
    TestAllTypes msg =
        TestAllTypes.newBuilder().setSingleDouble(Double.NaN).setSingleFloat(Float.NaN).build();

    Object result =
        eval(
            "msg.single_double != msg.single_double && !(msg.single_double == msg.single_double) &&"
                + " msg.single_float != msg.single_float && !(msg.single_float =="
                + " msg.single_float)",
            msg);

    assertThat(result).isEqualTo(true);
  }

  @Test
  public void select_unknownFloatingPointNegativeZero_preservesCelZeroEqualityAndPresenceSemantics()
      throws Exception {
    TestAllTypes msg =
        TestAllTypes.newBuilder().setSingleDouble(-0.0d).setSingleFloat(-0.0f).build();

    Object result =
        eval(
            "has(msg.single_double) && msg.single_double == 0.0 &&"
                + " has(msg.single_float) && msg.single_float == 0.0",
            msg);

    assertThat(result).isEqualTo(true);
  }

  @Test
  public void has_unknownEmptyStringAndBytesOnWire_returnsTrue() throws Exception {
    TestAllTypes msg =
        TestAllTypes.newBuilder()
            .setUnknownFields(
                UnknownFieldSet.newBuilder()
                    .addField(
                        TestAllTypes.SINGLE_STRING_FIELD_NUMBER,
                        UnknownFieldSet.Field.newBuilder()
                            .addLengthDelimited(ByteString.EMPTY)
                            .build())
                    .addField(
                        TestAllTypes.SINGLE_BYTES_FIELD_NUMBER,
                        UnknownFieldSet.Field.newBuilder()
                            .addLengthDelimited(ByteString.EMPTY)
                            .build())
                    .build())
            .build();

    Object result =
        eval(
            "has(msg.single_string) && msg.single_string == '' &&"
                + " has(msg.single_bytes) && msg.single_bytes == b''",
            msg);

    assertThat(result).isEqualTo(true);
  }

  @Test
  public void select_fragmentedUnknownSubmessageAcrossMultipleWireRecords_mergesAllChunksInOrder()
      throws Exception {
    ByteString chunk1 =
        NestedTestAllTypes.newBuilder()
            .setChild(
                NestedTestAllTypes.newBuilder()
                    .setPayload(TestAllTypes.newBuilder().setSingleInt32(10)))
            .setPayload(TestAllTypes.newBuilder().setSingleInt64(100L))
            .build()
            .toByteString();
    ByteString chunk2 =
        NestedTestAllTypes.newBuilder()
            .setPayload(TestAllTypes.newBuilder().setSingleInt64(20L))
            .build()
            .toByteString();
    TestAllTypes msg =
        TestAllTypes.newBuilder()
            .setUnknownFields(
                UnknownFieldSet.newBuilder()
                    .addField(
                        TestAllTypes.ONEOF_TYPE_FIELD_NUMBER,
                        UnknownFieldSet.Field.newBuilder()
                            .addLengthDelimited(chunk1)
                            .addLengthDelimited(chunk2)
                            .build())
                    .build())
            .build();

    Object result =
        eval(
            "has(msg.oneof_type.child.payload.single_int32) &&"
                + " msg.oneof_type.child.payload.single_int32 == 10 &&"
                + " has(msg.oneof_type.payload.single_int64) &&"
                + " msg.oneof_type.payload.single_int64 == 20",
            msg);

    assertThat(result).isEqualTo(true);
  }

  @Test
  public void select_duplicateUnknownScalarWireRecords_lastRecordWinsEvenWhenDefaultValue()
      throws Exception {
    TestAllTypes msg =
        TestAllTypes.newBuilder()
            .setUnknownFields(
                UnknownFieldSet.newBuilder()
                    .addField(
                        TestAllTypes.SINGLE_BOOL_FIELD_NUMBER,
                        UnknownFieldSet.Field.newBuilder().addVarint(1L).addVarint(0L).build())
                    .addField(
                        TestAllTypes.SINGLE_INT64_FIELD_NUMBER,
                        UnknownFieldSet.Field.newBuilder().addVarint(99L).addVarint(0L).build())
                    .addField(
                        TestAllTypes.SINGLE_UINT64_FIELD_NUMBER,
                        UnknownFieldSet.Field.newBuilder().addVarint(11L).addVarint(22L).build())
                    .addField(
                        TestAllTypes.SINGLE_STRING_FIELD_NUMBER,
                        UnknownFieldSet.Field.newBuilder()
                            .addLengthDelimited(ByteString.copyFromUtf8("first"))
                            .addLengthDelimited(ByteString.EMPTY)
                            .build())
                    .build())
            .build();

    Object result =
        eval(
            "has(msg.single_bool) && !msg.single_bool &&"
                + " has(msg.single_int64) && msg.single_int64 == 0 &&"
                + " has(msg.single_uint64) && msg.single_uint64 == 22u &&"
                + " has(msg.single_string) && msg.single_string == ''",
            msg);

    assertThat(result).isEqualTo(true);
  }

  @Test
  public void select_mixedUnpackedAndMultiChunkPackedUnknownRepeatedField_preservesElementOrder()
      throws Exception {
    ByteArrayOutputStream packedChunk1 = new ByteArrayOutputStream();
    CodedOutputStream cos1 = CodedOutputStream.newInstance(packedChunk1);
    cos1.writeInt64NoTag(20L);
    cos1.writeInt64NoTag(30L);
    cos1.flush();
    ByteArrayOutputStream packedChunk2 = new ByteArrayOutputStream();
    CodedOutputStream cos2 = CodedOutputStream.newInstance(packedChunk2);
    cos2.writeInt64NoTag(-50L);
    cos2.flush();
    ByteArrayOutputStream payloadStream = new ByteArrayOutputStream();
    CodedOutputStream payloadCos = CodedOutputStream.newInstance(payloadStream);
    payloadCos.writeInt64(TestAllTypes.REPEATED_INT64_FIELD_NUMBER, 10L);
    payloadCos.writeBytes(
        TestAllTypes.REPEATED_INT64_FIELD_NUMBER, ByteString.copyFrom(packedChunk1.toByteArray()));
    payloadCos.writeBytes(TestAllTypes.REPEATED_INT64_FIELD_NUMBER, ByteString.EMPTY);
    payloadCos.writeInt64(TestAllTypes.REPEATED_INT64_FIELD_NUMBER, 40L);
    payloadCos.writeBytes(
        TestAllTypes.REPEATED_INT64_FIELD_NUMBER, ByteString.copyFrom(packedChunk2.toByteArray()));
    payloadCos.flush();
    TestAllTypes msg =
        TestAllTypes.newBuilder()
            .setOneofType(
                NestedTestAllTypes.newBuilder()
                    .setUnknownFields(
                        UnknownFieldSet.newBuilder()
                            .addField(
                                NestedTestAllTypes.PAYLOAD_FIELD_NUMBER,
                                UnknownFieldSet.Field.newBuilder()
                                    .addLengthDelimited(
                                        ByteString.copyFrom(payloadStream.toByteArray()))
                                    .build())
                            .build()))
            .build();

    Object result =
        eval(
            "has(msg.oneof_type.payload.repeated_int64) &&"
                + " size(msg.oneof_type.payload.repeated_int64) == 5 &&"
                + " msg.oneof_type.payload.repeated_int64 == [10, 20, 30, 40, -50]",
            msg);

    assertThat(result).isEqualTo(true);
  }

  @Test
  public void select_unknownRepeatedFieldWithEmptyPackedWireBytes_returnsEmptyList()
      throws Exception {
    TestAllTypes msg =
        TestAllTypes.newBuilder()
            .setUnknownFields(
                UnknownFieldSet.newBuilder()
                    .addField(
                        TestAllTypes.REPEATED_INT64_FIELD_NUMBER,
                        UnknownFieldSet.Field.newBuilder()
                            .addLengthDelimited(ByteString.EMPTY)
                            .build())
                    .build())
            .build();

    Object result = eval("size(msg.repeated_int64) == 0 && msg.repeated_int64 == []", msg);

    assertThat(result).isEqualTo(true);
  }

  @Test
  public void celBind_unknownOuterSubmessageWithKnownInnerMapFields_decodesMapEntriesFromWireBytes()
      throws Exception {
    TestAllTypes msg =
        TestAllTypes.newBuilder()
            .setOneofType(NestedTestAllTypes.newBuilder().setPayload(POPULATED_RENAMED_MESSAGE))
            .build();

    Object result =
        eval(
            "cel.bind(sub, msg.oneof_type,"
                + " has(sub.payload.map_string_string) &&"
                + " sub.payload.map_string_string['k'] == 'v' &&"
                + " has(sub.payload.map_string_string.k) &&"
                + " !has(sub.payload.map_string_string.missing) &&"
                + " sub.payload.map_int64_message[1].bb == 100)",
            msg);

    assertThat(result).isEqualTo(true);
  }

  @Test
  public void select_unknownOuterSubmessageWithKnownInnerMapFields_decodesMapEntriesWithoutCelBind()
      throws Exception {
    // Direct multi-hop traversal without cel.bind: map leaf hops carry their MapEntrySpec and
    // value protoTypeName (if message value) so RawProtoMessageLiteValue decodes map entries
    // directly from wire bytes even when reached through unknown intermediate hops.
    TestAllTypes msg =
        TestAllTypes.newBuilder()
            .setOneofType(NestedTestAllTypes.newBuilder().setPayload(POPULATED_RENAMED_MESSAGE))
            .build();

    Object result =
        eval(
            "has(msg.oneof_type.payload.map_string_string) &&"
                + " msg.oneof_type.payload.map_string_string['k'] == 'v' &&"
                + " has(msg.oneof_type.payload.map_string_string.k) &&"
                + " !has(msg.oneof_type.payload.map_string_string.missing) &&"
                + " msg.oneof_type.payload.map_int64_message[1].bb == 100",
            msg);

    assertThat(result).isEqualTo(true);
  }

  @Test
  public void
      celBind_unknownOuterSubmessageWithWireMapAnomalies_handlesDuplicateKeysAndMissingEntryFields()
          throws Exception {
    // MapEntry wire format: tag 1 = key, tag 2 = value.
    ByteString dupEntry1 = encodeStringMapEntry("dup", "first");
    ByteString dupEntry2 = encodeStringMapEntry("dup", "second");
    ByteString missingKeyEntry = encodeStringMapEntry(/* key= */ null, "val_for_default_key");
    ByteString missingValueEntry = encodeStringMapEntry("key_with_default_val", /* value= */ null);
    TestAllTypes innerPayload =
        TestAllTypes.newBuilder()
            .setUnknownFields(
                UnknownFieldSet.newBuilder()
                    .addField(
                        TestAllTypes.MAP_STRING_STRING_FIELD_NUMBER,
                        UnknownFieldSet.Field.newBuilder()
                            .addLengthDelimited(dupEntry1)
                            .addLengthDelimited(dupEntry2)
                            .addLengthDelimited(missingKeyEntry)
                            .addLengthDelimited(missingValueEntry)
                            .build())
                    .build())
            .build();
    TestAllTypes msg =
        TestAllTypes.newBuilder()
            .setOneofType(NestedTestAllTypes.newBuilder().setPayload(innerPayload))
            .build();

    Object result =
        eval(
            "cel.bind(sub, msg.oneof_type,"
                + " size(sub.payload.map_string_string) == 3 &&"
                + " sub.payload.map_string_string['dup'] == 'second' &&"
                + " sub.payload.map_string_string[''] == 'val_for_default_key' &&"
                + " sub.payload.map_string_string['key_with_default_val'] == '')",
            msg);

    assertThat(result).isEqualTo(true);
  }

  @SuppressWarnings("ImmutableEnumChecker") // Test only
  private enum Proto2CustomDefaultTestCase {
    INT32("proto2_msg.single_int32", "has(proto2_msg.single_int32)", -32L, 0L),
    INT64("proto2_msg.single_int64", "has(proto2_msg.single_int64)", -64L, 0L),
    UINT32(
        "proto2_msg.single_uint32",
        "has(proto2_msg.single_uint32)",
        UnsignedLong.valueOf(32L),
        UnsignedLong.ZERO),
    UINT64(
        "proto2_msg.single_uint64",
        "has(proto2_msg.single_uint64)",
        UnsignedLong.valueOf(64L),
        UnsignedLong.ZERO),
    FLOAT("proto2_msg.single_float", "has(proto2_msg.single_float)", 3.0d, 0.0d),
    DOUBLE("proto2_msg.single_double", "has(proto2_msg.single_double)", 6.4d, 0.0d),
    BOOL("proto2_msg.single_bool", "has(proto2_msg.single_bool)", true, false),
    STRING("proto2_msg.single_string", "has(proto2_msg.single_string)", "empty", ""),
    BYTES(
        "proto2_msg.single_bytes",
        "has(proto2_msg.single_bytes)",
        CelByteString.of("none".getBytes(UTF_8)),
        CelByteString.EMPTY),
    NESTED_ENUM(
        "proto2_msg.single_nested_enum",
        "has(proto2_msg.single_nested_enum)",
        (long) dev.cel.expr.conformance.proto2.TestAllTypes.NestedEnum.BAR.getNumber(),
        (long) dev.cel.expr.conformance.proto2.TestAllTypes.NestedEnum.FOO.getNumber());

    private final String selectExpression;
    private final String hasExpression;
    private final Object expectedCustomDefault;
    private final Object expectedExplicitZero;

    Proto2CustomDefaultTestCase(
        String selectExpression,
        String hasExpression,
        Object expectedCustomDefault,
        Object expectedExplicitZero) {
      this.selectExpression = selectExpression;
      this.hasExpression = hasExpression;
      this.expectedCustomDefault = expectedCustomDefault;
      this.expectedExplicitZero = expectedExplicitZero;
    }
  }

  @Test
  public void select_unsetUnknownProto2Scalar_returnsBakedCustomSchemaDefault(
      @TestParameter Proto2CustomDefaultTestCase testCase) throws Exception {
    dev.cel.expr.conformance.proto2.TestAllTypes emptyProto2Msg =
        dev.cel.expr.conformance.proto2.TestAllTypes.getDefaultInstance();

    Object result = evalProto2(testCase.selectExpression, emptyProto2Msg);

    assertThat(result).isEqualTo(testCase.expectedCustomDefault);
  }

  @Test
  public void has_unsetUnknownProto2Scalar_returnsFalse(
      @TestParameter Proto2CustomDefaultTestCase testCase) throws Exception {
    dev.cel.expr.conformance.proto2.TestAllTypes emptyProto2Msg =
        dev.cel.expr.conformance.proto2.TestAllTypes.getDefaultInstance();

    Object result = evalProto2(testCase.hasExpression, emptyProto2Msg);

    assertThat(result).isEqualTo(false);
  }

  @Test
  public void select_explicitZeroUnknownProto2ScalarOnWire_decodesZeroInsteadOfCustomDefault(
      @TestParameter Proto2CustomDefaultTestCase testCase) throws Exception {
    dev.cel.expr.conformance.proto2.TestAllTypes explicitZeroProto2Msg =
        dev.cel.expr.conformance.proto2.TestAllTypes.newBuilder()
            .setSingleInt32(0)
            .setSingleInt64(0L)
            .setSingleUint32(0)
            .setSingleUint64(0L)
            .setSingleFloat(0.0f)
            .setSingleDouble(0.0d)
            .setSingleBool(false)
            .setSingleString("")
            .setSingleBytes(ByteString.EMPTY)
            .setSingleNestedEnum(dev.cel.expr.conformance.proto2.TestAllTypes.NestedEnum.FOO)
            .build();

    Object result = evalProto2(testCase.selectExpression, explicitZeroProto2Msg);

    assertThat(result).isEqualTo(testCase.expectedExplicitZero);
  }

  @Test
  public void has_explicitZeroUnknownProto2ScalarOnWire_returnsTrue(
      @TestParameter Proto2CustomDefaultTestCase testCase) throws Exception {
    dev.cel.expr.conformance.proto2.TestAllTypes explicitZeroProto2Msg =
        dev.cel.expr.conformance.proto2.TestAllTypes.newBuilder()
            .setSingleInt32(0)
            .setSingleInt64(0L)
            .setSingleUint32(0)
            .setSingleUint64(0L)
            .setSingleFloat(0.0f)
            .setSingleDouble(0.0d)
            .setSingleBool(false)
            .setSingleString("")
            .setSingleBytes(ByteString.EMPTY)
            .setSingleNestedEnum(dev.cel.expr.conformance.proto2.TestAllTypes.NestedEnum.FOO)
            .build();

    Object result = evalProto2(testCase.hasExpression, explicitZeroProto2Msg);

    assertThat(result).isEqualTo(true);
  }

  @Test
  public void lateBoundFunction_withPopulatedUnknownFields_evaluatesCorrectly() throws Exception {
    Program program = compileScoreModelLateBoundProgram();
    CelLateFunctionBindings lateBindings = newScoreModelLateBindings();

    Object result = program.eval(ImmutableMap.of("msg", POPULATED_SERVER_MESSAGE), lateBindings);

    assertThat(result).isEqualTo(13.0d);
  }

  @Test
  public void lateBoundFunction_withUnsetUnknownFields_evaluatesDefaultFallback() throws Exception {
    Program program = compileScoreModelLateBoundProgram();
    CelLateFunctionBindings lateBindings = newScoreModelLateBindings();

    Object result =
        program.eval(ImmutableMap.of("msg", TestAllTypes.getDefaultInstance()), lateBindings);

    assertThat(result).isEqualTo(-1.0d);
  }

  @Test
  public void wellKnownTypes_populatedUnknownFields_evaluatesMemberAccessorsAndArithmetic()
      throws Exception {
    String expression =
        "msg.single_timestamp.getFullYear() == 2023 &&"
            + " msg.single_duration.getSeconds() == 3600 &&"
            + " int(msg.single_timestamp + msg.single_duration) == 1700003600";

    Object result = eval(expression, POPULATED_SERVER_MESSAGE);

    assertThat(result).isEqualTo(true);
  }

  @Test
  public void wellKnownTypes_unsetUnknownFields_evaluatesEpochAndZeroDefaults() throws Exception {
    String expression =
        "msg.single_timestamp.getFullYear() == 1970 &&"
            + " msg.single_duration.getSeconds() == 0 &&"
            + " (msg.single_timestamp + msg.single_duration) == timestamp(0)";

    Object result = eval(expression, TestAllTypes.getDefaultInstance());

    assertThat(result).isEqualTo(true);
  }

  @Test
  public void wellKnownTypes_negativeDurationAndPreEpochTimestampOnUnknownFields_decodesCorrectly()
      throws Exception {
    Duration negativeDuration = Duration.ofSeconds(-10L, -500_000_000L);
    Instant preEpochInstant = Instant.ofEpochSecond(-86400L, 123_456_789L);
    TestAllTypes msg =
        TestAllTypes.newBuilder()
            .setSingleDuration(ProtoTimeUtils.toProtoDuration(negativeDuration))
            .setSingleTimestamp(ProtoTimeUtils.toProtoTimestamp(preEpochInstant))
            .build();

    Object result =
        eval(
            "msg.single_duration == duration('-10.5s') &&"
                + " msg.single_duration < duration('0s') &&"
                + " msg.single_timestamp < timestamp(0) &&"
                + " msg.single_timestamp.getFullYear() == 1969 &&"
                + " msg.single_timestamp.getMilliseconds() == 123",
            msg);

    assertThat(result).isEqualTo(true);
  }

  @Test
  public void
      wellKnownTypes_unknownRepeatedDurationAndTimestamp_evaluatesMemberAccessorsAndComparisons()
          throws Exception {
    String expression =
        "has(msg.repeated_duration) &&"
            + " size(msg.repeated_duration) == 2 &&"
            + " msg.repeated_duration[0] == duration('5m') &&"
            + " msg.repeated_duration[0].getMinutes() == 5 &&"
            + " msg.repeated_duration[1] == duration('-10.5s') &&"
            + " msg.repeated_duration.exists(d, d < duration('0s')) &&"
            + " has(msg.repeated_timestamp) &&"
            + " size(msg.repeated_timestamp) == 2 &&"
            + " msg.repeated_timestamp[0].getFullYear() == 2023 &&"
            + " msg.repeated_timestamp[1] == timestamp(0) &&"
            + " msg.repeated_timestamp.exists(t, t == timestamp(0))";

    Object result = eval(expression, POPULATED_SERVER_MESSAGE);

    assertThat(result).isEqualTo(true);
  }

  @Test
  public void heterogeneousNumericComparisons_onUnknownWireDecodedFields_evaluatesCorrectly()
      throws Exception {
    String expression =
        "dyn(msg.single_int64) == -42.0 &&"
            + " dyn(msg.single_uint64) == 999 &&"
            + " dyn(msg.single_uint32) == 123.0 &&"
            + " msg.single_double in [-42, 999u, 0.85] &&"
            + " msg.single_int64 in [-42.0, 0u] &&"
            + " msg.single_uint64 in [999, 0.0]";

    Object result = eval(expression, POPULATED_SERVER_MESSAGE);

    assertThat(result).isEqualTo(true);
  }

  @Test
  public void checkedExprWireRoundTrip_preservesOptimizedSelectsUnderVersionSkew()
      throws Exception {
    CelAbstractSyntaxTree ast =
        serverCompiler
            .compile(
                "has(msg.single_nested_message.bb) && msg.single_nested_message.bb == 123 &&"
                    + " msg.single_duration == duration('1h') &&"
                    + " msg.repeated_string.exists(s, s == 'foo')")
            .getAst();
    CelAbstractSyntaxTree optimizedAst = serverOptimizer.optimize(ast);
    byte[] serializedCheckedExpr =
        CelProtoAbstractSyntaxTree.fromCelAst(optimizedAst).toCheckedExpr().toByteArray();
    CheckedExpr deserializedCheckedExpr =
        CheckedExpr.parseFrom(serializedCheckedExpr, ExtensionRegistryLite.getEmptyRegistry());
    CelAbstractSyntaxTree deserializedAst =
        CelProtoAbstractSyntaxTree.fromCheckedExpr(deserializedCheckedExpr).getAst();

    Object result =
        clientRuntime
            .createProgram(deserializedAst)
            .eval(ImmutableMap.of("msg", POPULATED_SERVER_MESSAGE));

    assertThat(result).isEqualTo(true);
  }

  @Test
  public void select_parsedOnlyAst_dispatchesDynamicallyByFunctionName() throws Exception {
    TestAllTypes msg = TestAllTypes.newBuilder().setSingleInt64(99L).build();
    CelAbstractSyntaxTree ast = serverCompiler.compile("msg.single_int64").getAst();
    CelAbstractSyntaxTree optimizedAst = serverOptimizer.optimize(ast);
    CelAbstractSyntaxTree parsedOptimizedAst =
        CelAbstractSyntaxTree.newParsedAst(optimizedAst.getExpr(), optimizedAst.getSource());

    Program program = clientRuntime.createProgram(parsedOptimizedAst);
    Object result = program.eval(ImmutableMap.of("msg", msg));

    assertThat(result).isEqualTo(99L);
  }

  @Test
  public void has_parsedOnlyAst_dispatchesDynamicallyByFunctionName() throws Exception {
    TestAllTypes msg = TestAllTypes.newBuilder().setSingleInt64(99L).build();
    CelAbstractSyntaxTree ast = serverCompiler.compile("has(msg.single_int64)").getAst();
    CelAbstractSyntaxTree optimizedAst = serverOptimizer.optimize(ast);
    CelAbstractSyntaxTree parsedOptimizedAst =
        CelAbstractSyntaxTree.newParsedAst(optimizedAst.getExpr(), optimizedAst.getSource());

    Program program = clientRuntime.createProgram(parsedOptimizedAst);
    Object result = program.eval(ImmutableMap.of("msg", msg));

    assertThat(result).isEqualTo(true);
  }

  @Test
  public void
      nestedMapAndRepeatedMessages_unknownMapToChildToUnknownRepeatedMessageAndInnerUnknownMap_evaluatesCorrectly()
          throws Exception {
    String expression =
        "has(msg.map_int64_nested_type) &&"
            + " (1 in msg.map_int64_nested_type) &&"
            + " !(99 in msg.map_int64_nested_type) &&"
            + " msg.map_int64_nested_type[1].child.payload.repeated_nested_message.exists(x, x.bb"
            + " == 20) &&"
            + " msg.map_int64_nested_type[1].child.payload.repeated_nested_message.map(x, x.bb +"
            + " msg.map_int64_nested_type[1].payload.single_int64) == [110, 120] &&"
            + " msg.map_int64_nested_type[1].child.payload.repeated_nested_message.map(x,"
            + " msg.map_int64_nested_type[1].child.payload.map_int32_int32[x.bb]) == [100, 200] &&"
            + " [1, 2].map(k, msg.map_int64_nested_type[k].payload.repeated_int64) == [[7, 8],"
            + " [9]] &&"
            + " msg.map_string_message['m1'].bb == 55 &&"
            + " has(msg.map_string_message.m1.bb)";

    Object result = eval(expression, POPULATED_SERVER_MESSAGE);

    assertThat(result).isEqualTo(true);
  }

  @Test
  public void
      wellKnownTypes_unknownMapOfDurationAndTimestamp_evaluatesMemberAccessorsAndComparisons()
          throws Exception {
    String expression =
        "has(msg.map_string_duration) &&"
            + " ('d' in msg.map_string_duration) &&"
            + " msg.map_string_duration['d'] == duration('5m') &&"
            + " msg.map_string_duration['d'].getMinutes() == 5 &&"
            + " has(msg.map_string_timestamp) &&"
            + " ('t' in msg.map_string_timestamp) &&"
            + " msg.map_string_timestamp['t'].getFullYear() == 2023 &&"
            + " msg.map_string_timestamp['t'] > timestamp(0)";

    Object result = eval(expression, POPULATED_SERVER_MESSAGE);

    assertThat(result).isEqualTo(true);
  }

  @Test
  public void
      select_unknownMapWithFragmentedSubmessageAndDuplicateKeys_mergesWithinEntryAndLastEntryWins()
          throws Exception {
    ByteString entry1Chunk1 =
        NestedTestAllTypes.newBuilder()
            .setPayload(TestAllTypes.newBuilder().setSingleInt64(111L))
            .build()
            .toByteString();
    ByteString entry2Chunk1 =
        NestedTestAllTypes.newBuilder()
            .setChild(
                NestedTestAllTypes.newBuilder()
                    .setPayload(TestAllTypes.newBuilder().setSingleInt32(33)))
            .setPayload(TestAllTypes.newBuilder().setSingleInt64(222L))
            .build()
            .toByteString();
    ByteString entry2Chunk2 =
        NestedTestAllTypes.newBuilder()
            .setPayload(
                TestAllTypes.newBuilder()
                    .setSingleInt64(333L)
                    .addRepeatedNestedMessage(NestedMessage.newBuilder().setBb(44)))
            .build()
            .toByteString();
    ByteArrayOutputStream entry1Stream = new ByteArrayOutputStream();
    CodedOutputStream entry1Cos = CodedOutputStream.newInstance(entry1Stream);
    entry1Cos.writeInt64(1, 7L);
    entry1Cos.writeBytes(2, entry1Chunk1);
    entry1Cos.flush();
    ByteArrayOutputStream entry2Stream = new ByteArrayOutputStream();
    CodedOutputStream entry2Cos = CodedOutputStream.newInstance(entry2Stream);
    entry2Cos.writeInt64(1, 7L);
    entry2Cos.writeBytes(2, entry2Chunk1);
    entry2Cos.writeBytes(2, entry2Chunk2);
    entry2Cos.flush();
    TestAllTypes msg =
        TestAllTypes.newBuilder()
            .setUnknownFields(
                UnknownFieldSet.newBuilder()
                    .addField(
                        TestAllTypes.MAP_INT64_NESTED_TYPE_FIELD_NUMBER,
                        UnknownFieldSet.Field.newBuilder()
                            .addLengthDelimited(ByteString.copyFrom(entry1Stream.toByteArray()))
                            .addLengthDelimited(ByteString.copyFrom(entry2Stream.toByteArray()))
                            .build())
                    .build())
            .build();

    Object result =
        eval(
            "size(msg.map_int64_nested_type) == 1 &&"
                + " msg.map_int64_nested_type[7].child.payload.single_int32 == 33 &&"
                + " msg.map_int64_nested_type[7].payload.single_int64 == 333 &&"
                + " msg.map_int64_nested_type[7].payload.repeated_nested_message[0].bb == 44",
            msg);

    assertThat(result).isEqualTo(true);
  }

  @SuppressWarnings("ImmutableEnumChecker") // Test only
  private enum CelBlockOptimizationTestCase {
    REPEATED_UNKNOWN_SCALAR_MATCHING(
        "msg.single_int64 < -10 && msg.single_int64 > -100", POPULATED_SERVER_MESSAGE, true),
    REPEATED_UNKNOWN_SCALAR_NON_MATCHING(
        "msg.single_int64 >= 0 && msg.single_int64 > 10", TestAllTypes.getDefaultInstance(), false),
    REPEATED_UNKNOWN_SUBMESSAGE_MATCHING(
        "msg.single_nested_message.bb > 10 && msg.single_nested_message.bb < 200",
        POPULATED_SERVER_MESSAGE,
        true),
    REPEATED_UNKNOWN_SUBMESSAGE_NON_MATCHING(
        "msg.single_nested_message.bb >= 0 && msg.single_nested_message.bb > 10",
        TestAllTypes.getDefaultInstance(),
        false),
    REPEATED_UNKNOWN_HAS_FIELD_MATCHING(
        "has(msg.single_nested_message.bb) && (has(msg.single_nested_message.bb) ||"
            + " msg.single_int64 > 0)",
        POPULATED_SERVER_MESSAGE,
        true),
    REPEATED_UNKNOWN_HAS_FIELD_NON_MATCHING(
        "!has(msg.single_nested_message.bb) && has(msg.single_nested_message.bb)",
        TestAllTypes.getDefaultInstance(),
        false),
    REPEATED_UNKNOWN_DURATION_MATCHING(
        "msg.single_duration > duration('1s') && msg.single_duration < duration('2h')",
        POPULATED_SERVER_MESSAGE,
        true),
    REPEATED_UNKNOWN_DURATION_NON_MATCHING(
        "msg.single_duration < duration('1s') && msg.single_duration > duration('2h')",
        TestAllTypes.getDefaultInstance(),
        false),
    SHARED_INTERMEDIATE_UNKNOWN_SUBMESSAGE_MATCHING(
        "msg.oneof_type.payload.single_int64 == -42 &&"
            + " msg.oneof_type.payload.single_string == 'cel-skew-test'",
        TestAllTypes.newBuilder()
            .setOneofType(NestedTestAllTypes.newBuilder().setPayload(POPULATED_SERVER_MESSAGE))
            .build(),
        true),
    SHARED_UNKNOWN_REPEATED_MESSAGE_LIST_MATCHING(
        "size(msg.repeated_nested_message) > 0 && msg.repeated_nested_message[0].bb == 10 &&"
            + " msg.repeated_nested_message[1].bb == 20",
        POPULATED_SERVER_MESSAGE,
        true),
    SHARED_UNKNOWN_MAP_OF_NESTED_MESSAGES_MATCHING(
        "(1 in msg.map_int64_nested_type) &&"
            + " msg.map_int64_nested_type[1].payload.single_int64 == 100 &&"
            + " msg.map_int64_nested_type[1].child.payload.repeated_nested_message[0].bb == 10",
        POPULATED_SERVER_MESSAGE,
        true);

    private final String expression;
    private final TestAllTypes message;
    private final boolean expectedResult;

    CelBlockOptimizationTestCase(String expression, TestAllTypes message, boolean expectedResult) {
      this.expression = expression;
      this.message = message;
      this.expectedResult = expectedResult;
    }
  }

  @Test
  public void celBlock_subexpressionOptimizer_evaluatesExpectedResult(
      @TestParameter CelBlockOptimizationTestCase testCase) throws Exception {
    CelOptimizer blockOptimizer = newSubexpressionOptimizer();
    CelAbstractSyntaxTree ast = serverCompiler.compile(testCase.expression).getAst();

    CelAbstractSyntaxTree optimizedAst = blockOptimizer.optimize(ast);
    Object result =
        clientRuntime.createProgram(optimizedAst).eval(ImmutableMap.of("msg", testCase.message));

    assertThat(CelBlock.extract(optimizedAst)).isPresent();
    assertThat(result).isEqualTo(testCase.expectedResult);
  }

  @Test
  public void celBlock_subexpressionOptimizer_whenPopulated_sharesSubmessageAcrossHasAndSelect()
      throws Exception {
    CelOptimizer blockOptimizer = newSubexpressionOptimizer();
    CelAbstractSyntaxTree ast =
        serverCompiler
            .compile(
                "has(msg.single_nested_message.bb) ? msg.single_nested_message.bb :"
                    + " msg.single_nested_message.bb - 1")
            .getAst();

    CelAbstractSyntaxTree optimizedAst = blockOptimizer.optimize(ast);
    Object result =
        clientRuntime
            .createProgram(optimizedAst)
            .eval(ImmutableMap.of("msg", POPULATED_SERVER_MESSAGE));

    assertThat(CelBlock.extract(optimizedAst)).isPresent();
    assertThat(result).isEqualTo(123L);
  }

  @Test
  public void celBlock_subexpressionOptimizer_whenUnset_sharesSubmessageAcrossHasAndSelect()
      throws Exception {
    CelOptimizer blockOptimizer = newSubexpressionOptimizer();
    CelAbstractSyntaxTree ast =
        serverCompiler
            .compile(
                "has(msg.single_nested_message.bb) ? msg.single_nested_message.bb :"
                    + " msg.single_nested_message.bb - 1")
            .getAst();

    CelAbstractSyntaxTree optimizedAst = blockOptimizer.optimize(ast);
    Object result =
        clientRuntime
            .createProgram(optimizedAst)
            .eval(ImmutableMap.of("msg", TestAllTypes.getDefaultInstance()));

    assertThat(CelBlock.extract(optimizedAst)).isPresent();
    assertThat(result).isEqualTo(-1L);
  }

  @SuppressWarnings("ImmutableEnumChecker") // Test only
  private enum DescriptorlessEvaluationTestCase {
    SCALAR_FIELDS(
        "msg.single_int64 == -42 && msg.single_string == 'cel-skew-test' && msg.single_bool",
        POPULATED_SERVER_MESSAGE,
        true),
    UNSET_SCALAR_DEFAULT(
        "msg.single_int64 == 0 && msg.single_string == '' && !has(msg.single_int64)",
        TestAllTypes.getDefaultInstance(),
        true),
    UNSET_SUBMESSAGE_DEFAULT(
        "msg.oneof_type.payload.single_int64 == 0 && !has(msg.oneof_type)"
            + " && !has(msg.oneof_type.payload)",
        TestAllTypes.getDefaultInstance(),
        true),
    POPULATED_ONEOF_SUBMESSAGE(
        "msg.oneof_type.payload.single_int64 == 77 && has(msg.oneof_type)"
            + " && has(msg.oneof_type.payload) && has(msg.oneof_type.payload.single_int64)",
        TestAllTypes.newBuilder()
            .setOneofType(
                NestedTestAllTypes.newBuilder()
                    .setPayload(TestAllTypes.newBuilder().setSingleInt64(77L)))
            .build(),
        true),
    PRESENCE_POPULATED(
        "has(msg.single_int64) && has(msg.single_nested_message)"
            + " && has(msg.single_nested_message.bb)",
        POPULATED_SERVER_MESSAGE,
        true),
    SUBMESSAGE_SCALAR("msg.single_nested_message.bb", POPULATED_SERVER_MESSAGE, 123L),
    REPEATED_SCALAR(
        "msg.repeated_string == ['foo', 'bar'] && msg.repeated_int64[1] == 20",
        POPULATED_SERVER_MESSAGE,
        true),
    REPEATED_SUBMESSAGE_COMPREHENSION(
        "msg.repeated_nested_message[0].bb == 10"
            + " && msg.repeated_nested_message.exists(x, x.bb == 20)",
        POPULATED_SERVER_MESSAGE,
        true),
    MAP_SCALAR_AND_SUBMESSAGE(
        "msg.map_int32_int32[1] == 2 && msg.map_string_message['m1'].bb == 55"
            + " && msg.map_int64_nested_type[1].payload.single_int64 == 100",
        POPULATED_SERVER_MESSAGE,
        true),
    WELL_KNOWN_TYPES(
        "msg.single_duration == duration('1h')"
            + " && msg.single_timestamp == timestamp('2023-11-14T22:13:20.000000500Z')"
            + " && msg.repeated_duration[0] == duration('5m')"
            + " && msg.map_string_duration['d'] == duration('5m')",
        POPULATED_SERVER_MESSAGE,
        true),
    WRAPPERS(
        "msg.single_int64_wrapper == 0 && has(msg.single_int64_wrapper)"
            + " && msg.single_string_wrapper == null && msg.repeated_int64_wrapper == [1, 0]",
        TestAllTypes.newBuilder()
            .setSingleInt64Wrapper(Int64Value.of(0))
            .addRepeatedInt64Wrapper(Int64Value.of(1))
            .addRepeatedInt64Wrapper(Int64Value.of(0))
            .build(),
        true),
    JSON_TYPES(
        "msg.single_struct.a == 1.0 && msg.single_value == null && has(msg.single_value)"
            + " && msg.list_value == [] && !has(msg.list_value) && msg.repeated_value == [null]",
        TestAllTypes.newBuilder()
            .setSingleStruct(
                Struct.newBuilder().putFields("a", Value.newBuilder().setNumberValue(1).build()))
            .setSingleValue(Value.newBuilder().setNullValue(NullValue.NULL_VALUE))
            .addRepeatedValue(Value.newBuilder().setNullValue(NullValue.NULL_VALUE))
            .build(),
        true),
    ;

    private final String expression;
    private final TestAllTypes message;
    private final Object expectedResult;

    DescriptorlessEvaluationTestCase(
        String expression, TestAllTypes message, Object expectedResult) {
      this.expression = expression;
      this.message = message;
      this.expectedResult = expectedResult;
    }
  }

  @Test
  public void descriptorlessRuntime_zeroRegisteredDescriptors_evaluatesFromWireBytes(
      @TestParameter DescriptorlessEvaluationTestCase testCase) throws Exception {
    CelLiteRuntime descriptorlessRuntime = newDescriptorlessRuntime();
    CelAbstractSyntaxTree optimizedAst =
        serverOptimizer.optimize(serverCompiler.compile(testCase.expression).getAst());
    Program program = descriptorlessRuntime.createProgram(optimizedAst);

    Object result = program.eval(ImmutableMap.of("msg", testCase.message));

    assertThat(result).isEqualTo(testCase.expectedResult);
  }

  @Test
  public void partialDescriptorRuntime_missingChildDescriptor_evaluatesFromWireBytes(
      @TestParameter DescriptorlessEvaluationTestCase testCase) throws Exception {
    ImmutableList<MessageLiteDescriptor> withoutChildMessages =
        TestAllTypesCelDescriptor.getDescriptor().getProtoTypeNamesToDescriptors().values().stream()
            .filter(d -> !d.getProtoTypeName().equals(NestedMessage.getDescriptor().getFullName()))
            .collect(toImmutableList());
    CelLiteRuntime partialRuntime =
        CelLiteRuntimeFactory.newLiteRuntimeBuilder()
            .setStandardFunctions(CelStandardFunctions.ALL_STANDARD_FUNCTIONS)
            .setValueProvider(
                ProtoMessageLiteValueProvider.newInstance(
                    newClientCelLiteDescriptor(
                        "partial",
                        TestAllTypesCelDescriptor.getDescriptor(),
                        withoutChildMessages)))
            .setContainer(CEL_CONTAINER)
            .build();
    CelAbstractSyntaxTree optimizedAst =
        serverOptimizer.optimize(serverCompiler.compile(testCase.expression).getAst());
    Program program = partialRuntime.createProgram(optimizedAst);

    Object result = program.eval(ImmutableMap.of("msg", testCase.message));

    assertThat(result).isEqualTo(testCase.expectedResult);
  }

  @Test
  public void descriptorlessRuntime_unoptimizedAst_throwsDescriptiveException(
      @TestParameter({"msg.single_int64", "has(msg.single_int64)"}) String expression)
      throws Exception {
    CelLiteRuntime descriptorlessRuntime = newDescriptorlessRuntime();
    CelAbstractSyntaxTree unoptimizedAst = serverCompiler.compile(expression).getAst();
    Program program = descriptorlessRuntime.createProgram(unoptimizedAst);

    CelEvaluationException thrown =
        assertThrows(
            CelEvaluationException.class,
            () -> program.eval(ImmutableMap.of("msg", POPULATED_SERVER_MESSAGE)));

    assertThat(thrown)
        .hasCauseThat()
        .hasMessageThat()
        .isEqualTo(
            "Error resolving field 'single_int64' on 'cel.@unknownMessage'. Field selection by name"
                + " is not supported on raw proto wire bytes; register its CelLiteDescriptor or"
                + " enable SelectOptimizer.");
  }

  @Test
  public void partialDescriptorRuntime_unoptimizedAst_throwsDescriptiveException(
      @TestParameter({"msg.single_nested_message.bb", "has(msg.single_nested_message.bb)"})
          String expression)
      throws Exception {
    ImmutableList<MessageLiteDescriptor> withoutChildMessages =
        TestAllTypesCelDescriptor.getDescriptor().getProtoTypeNamesToDescriptors().values().stream()
            .filter(d -> !d.getProtoTypeName().equals(NestedMessage.getDescriptor().getFullName()))
            .collect(toImmutableList());
    CelLiteRuntime partialRuntime =
        CelLiteRuntimeFactory.newLiteRuntimeBuilder()
            .setStandardFunctions(CelStandardFunctions.ALL_STANDARD_FUNCTIONS)
            .setValueProvider(
                ProtoMessageLiteValueProvider.newInstance(
                    newClientCelLiteDescriptor(
                        "partial",
                        TestAllTypesCelDescriptor.getDescriptor(),
                        withoutChildMessages)))
            .setContainer(CEL_CONTAINER)
            .build();
    CelAbstractSyntaxTree unoptimizedAst = serverCompiler.compile(expression).getAst();
    Program program = partialRuntime.createProgram(unoptimizedAst);

    CelEvaluationException thrown =
        assertThrows(
            CelEvaluationException.class,
            () -> program.eval(ImmutableMap.of("msg", POPULATED_SERVER_MESSAGE)));

    assertThat(thrown)
        .hasCauseThat()
        .hasMessageThat()
        .isEqualTo(
            "Error resolving field 'bb' on"
                + " 'cel.expr.conformance.proto3.TestAllTypes.NestedMessage'. Field selection by"
                + " name is not supported on raw proto wire bytes; register its CelLiteDescriptor"
                + " or enable SelectOptimizer.");
  }

  @Test
  public void descriptorlessRuntime_rootMessageResult_returnsWireMessageLite() throws Exception {
    CelLiteRuntime descriptorlessRuntime = newDescriptorlessRuntime();
    CelAbstractSyntaxTree ast = serverCompiler.compile("msg").getAst();
    Program program = descriptorlessRuntime.createProgram(ast);

    Object result = program.eval(ImmutableMap.of("msg", POPULATED_SERVER_MESSAGE));

    assertThat(result).isInstanceOf(WireMessageLite.class);
    WireMessageLite wireMsg = (WireMessageLite) result;
    assertThat(wireMsg.protoTypeName()).isEqualTo("cel.@unknownMessage");
    assertThat(wireMsg.toByteString()).isEqualTo(POPULATED_SERVER_MESSAGE.toByteString());
  }

  @Test
  public void olderClassRoundTrip_descriptorlessRuntime_evaluatesFromReserializedUnknownFields(
      @TestParameter DescriptorlessEvaluationTestCase testCase) throws Exception {
    // NestedMessage only defines field 1 (int32 bb = 1); parsing a V2 TestAllTypes payload into it
    // populates its known field (tag 1) alongside unknown V2 fields (tags 2..402) before
    // re-serialization.
    TestAllTypes v2Message =
        testCase.message.equals(TestAllTypes.getDefaultInstance())
            ? testCase.message
            : testCase.message.toBuilder().setSingleInt32(42).build();
    NestedMessage olderClassMsg =
        NestedMessage.parseFrom(v2Message.toByteString(), ExtensionRegistryLite.getEmptyRegistry());
    CelLiteRuntime descriptorlessRuntime = newDescriptorlessRuntime();
    CelAbstractSyntaxTree optimizedAst =
        serverOptimizer.optimize(serverCompiler.compile(testCase.expression).getAst());
    Program program = descriptorlessRuntime.createProgram(optimizedAst);

    Object result = program.eval(ImmutableMap.of("msg", olderClassMsg));

    assertThat(result).isEqualTo(testCase.expectedResult);
  }

  @Test
  public void
      olderClassRoundTrip_v1ClassDescriptor_evaluatesKnownAndUnknownFieldsAcrossSubmessages()
          throws Exception {
    TestAllTypes v2Payload = POPULATED_SERVER_MESSAGE.toBuilder().setSingleInt32(42).build();
    // Parse V2 TestAllTypes wire bytes into NestedMessage (which only knows field 1: int32 bb = 1).
    // Field 1 is stored in NestedMessage's generated Java field, while all V2 fields (tags 2..402)
    // are retained in NestedMessage's unknown fields.
    NestedMessage v1RootMsg =
        NestedMessage.parseFrom(v2Payload.toByteString(), ExtensionRegistryLite.getEmptyRegistry());
    NestedTestAllTypes v2NestedMsg = NestedTestAllTypes.newBuilder().setPayload(v2Payload).build();
    CelLiteRuntime v1OlderClassRuntime = newOlderClassV1Runtime();
    CelAbstractSyntaxTree optimizedAst =
        serverOptimizer.optimize(
            serverCompiler
                .compile(
                    "has(msg.single_int32) && msg.single_int32 == 42"
                        + " && has(msg.single_int64) && msg.single_int64 == -42"
                        + " && msg.single_string == 'cel-skew-test'"
                        + " && msg.repeated_int64 == [10, 20]"
                        + " && msg.map_int32_int32[1] == 2"
                        + " && msg.single_duration == duration('1h')"
                        + " && has(nested_msg.payload.single_int32)"
                        + " && nested_msg.payload.single_int32 == 42"
                        + " && has(nested_msg.payload.single_int64)"
                        + " && nested_msg.payload.single_int64 == -42"
                        + " && nested_msg.payload.single_nested_message.bb == 123"
                        + " && nested_msg.payload.repeated_string == ['foo', 'bar']"
                        + " && nested_msg.payload.map_string_duration['d'] == duration('5m')")
                .getAst());
    Program program = v1OlderClassRuntime.createProgram(optimizedAst);

    Object result = program.eval(ImmutableMap.of("msg", v1RootMsg, "nested_msg", v2NestedMsg));

    assertThat(result).isEqualTo(true);
  }

  @Test
  public void
      olderClassRoundTrip_v1ClassDescriptor_evaluatesUnsetDefaultsAndDecodesLeafSubmessageIntoOlderClass()
          throws Exception {
    TestAllTypes v2Payload = POPULATED_SERVER_MESSAGE.toBuilder().setSingleInt32(42).build();
    NestedMessage expectedV1Msg =
        NestedMessage.parseFrom(v2Payload.toByteString(), ExtensionRegistryLite.getEmptyRegistry());
    NestedTestAllTypes v2NestedMsg = NestedTestAllTypes.newBuilder().setPayload(v2Payload).build();
    CelLiteRuntime v1OlderClassRuntime = newOlderClassV1Runtime();
    Program defaultCheckProgram =
        v1OlderClassRuntime.createProgram(
            serverOptimizer.optimize(
                serverCompiler
                    .compile(
                        "!has(msg.single_int32) && msg.single_int32 == 0"
                            + " && !has(msg.single_int64) && msg.single_int64 == 0")
                    .getAst()));
    Program leafSubmessageProgram =
        v1OlderClassRuntime.createProgram(
            serverOptimizer.optimize(serverCompiler.compile("nested_msg.payload").getAst()));

    Object defaultCheckResult =
        defaultCheckProgram.eval(ImmutableMap.of("msg", NestedMessage.getDefaultInstance()));
    Object leafSubmessageResult =
        leafSubmessageProgram.eval(ImmutableMap.of("nested_msg", v2NestedMsg));

    assertThat(defaultCheckResult).isEqualTo(true);
    assertThat(leafSubmessageResult).isEqualTo(expectedV1Msg);
  }

  @Test
  public void has_unknownProto3OptionalScalars_explicitDefaultVsUnset() throws Exception {
    CelLiteRuntime runtime = newRuntimeWithoutFieldDescriptor("optional_bool", "optional_string");
    Program program =
        runtime.createProgram(
            serverOptimizer.optimize(
                serverCompiler
                    .compile(
                        "[has(msg.optional_bool), msg.optional_bool,"
                            + " has(msg.optional_string), msg.optional_string]")
                    .getAst()));
    TestAllTypes explicitZeroMsg =
        TestAllTypes.newBuilder().setOptionalBool(false).setOptionalString("").build();

    Object explicitZeroResult = program.eval(ImmutableMap.of("msg", explicitZeroMsg));
    Object unsetResult = program.eval(ImmutableMap.of("msg", TestAllTypes.getDefaultInstance()));

    assertThat(explicitZeroResult).isEqualTo(ImmutableList.of(true, false, true, ""));
    assertThat(unsetResult).isEqualTo(ImmutableList.of(false, false, false, ""));
  }

  @Test
  @SuppressWarnings("unchecked") // Program.eval returns Object.
  public void select_unknownSubmessageContainers_withoutSubmessageDescriptor() throws Exception {
    CelLiteRuntime runtimeWithoutNestedDesc = newRuntimeWithoutNestedMessageDescriptor();
    Program repeatedProgram =
        runtimeWithoutNestedDesc.createProgram(
            serverOptimizer.optimize(
                serverCompiler.compile("msg.repeated_nested_message").getAst()));
    Program mapProgram =
        runtimeWithoutNestedDesc.createProgram(
            serverOptimizer.optimize(serverCompiler.compile("msg.map_string_message").getAst()));

    List<WireMessageLite> repeatedResult =
        (List<WireMessageLite>)
            repeatedProgram.eval(ImmutableMap.of("msg", POPULATED_SERVER_MESSAGE));
    Map<String, WireMessageLite> mapResult =
        (Map<String, WireMessageLite>)
            mapProgram.eval(ImmutableMap.of("msg", POPULATED_SERVER_MESSAGE));

    assertThat(repeatedResult).hasSize(2);
    assertThat(repeatedResult.get(0).protoTypeName())
        .isEqualTo("cel.expr.conformance.proto3.TestAllTypes.NestedMessage");
    assertThat(repeatedResult.get(0).toByteString())
        .isEqualTo(NestedMessage.newBuilder().setBb(10).build().toByteString());
    assertThat(repeatedResult.get(1).toByteString())
        .isEqualTo(NestedMessage.newBuilder().setBb(20).build().toByteString());
    assertThat(mapResult).containsKey("m1");
    assertThat(mapResult.get("m1").protoTypeName())
        .isEqualTo("cel.expr.conformance.proto3.TestAllTypes.NestedMessage");
    assertThat(mapResult.get("m1").toByteString())
        .isEqualTo(NestedMessage.newBuilder().setBb(55).build().toByteString());
  }

  @Test
  public void wellKnownTypes_unknownWrapperFields_decodesFromWire() throws Exception {
    // Encode a map<string, Int64Value> entry with key="omitted_val" and no tag 2 value record,
    // which must decode to the wrapper's default value (0L) rather than null.
    ByteString omittedWrapperValueEntry = encodeStringMapEntry("omitted_val", /* value= */ null);
    TestAllTypes msg =
        TestAllTypes.newBuilder()
            .setSingleInt64Wrapper(Int64Value.of(0L))
            .addRepeatedInt64Wrapper(Int64Value.of(10L))
            .addRepeatedInt64Wrapper(Int64Value.of(0L))
            .putMapStringInt64Wrapper("explicit", Int64Value.of(42L))
            .setUnknownFields(
                UnknownFieldSet.newBuilder()
                    .addField(
                        TestAllTypes.MAP_STRING_INT64_WRAPPER_FIELD_NUMBER,
                        UnknownFieldSet.Field.newBuilder()
                            .addLengthDelimited(omittedWrapperValueEntry)
                            .build())
                    .build())
            .build();

    Object result =
        eval(
            "has(msg.single_int64_wrapper) && msg.single_int64_wrapper == 0 &&"
                + " !has(msg.single_string_wrapper) && msg.single_string_wrapper == null &&"
                + " msg.repeated_int64_wrapper == [10, 0] &&"
                + " msg.map_string_int64_wrapper['explicit'] == 42 &&"
                + " msg.map_string_int64_wrapper['omitted_val'] == 0",
            msg);

    assertThat(result).isEqualTo(true);
  }

  @Test
  public void wellKnownTypes_unknownJsonFieldMaskAndEmpty_decodesFromWire() throws Exception {
    TestAllTypes populatedMsg =
        TestAllTypes.newBuilder()
            .setSingleStruct(
                Struct.newBuilder()
                    .putFields("k", Value.newBuilder().setStringValue("v").build())
                    .putFields("n", Value.newBuilder().setNullValue(NullValue.NULL_VALUE).build()))
            .setSingleValue(Value.newBuilder().setNumberValue(3.5d))
            .setListValue(
                ListValue.newBuilder()
                    .addValues(Value.newBuilder().setBoolValue(true))
                    .addValues(Value.newBuilder().setNullValue(NullValue.NULL_VALUE)))
            .setFieldMask(FieldMask.newBuilder().addPaths("a.b").addPaths("c.d"))
            .setEmpty(Empty.getDefaultInstance())
            .build();

    Object populatedResult =
        eval(
            "has(msg.single_struct) && msg.single_struct.k == 'v' && msg.single_struct.n == null &&"
                + " has(msg.single_value) && msg.single_value == 3.5 &&"
                + " has(msg.list_value) && msg.list_value == [true, null] &&"
                + " has(msg.field_mask) && msg.field_mask.paths == ['a.b', 'c.d'] &&"
                + " has(msg.empty)",
            populatedMsg);
    Object unsetResult =
        eval(
            "!has(msg.single_struct) && msg.single_struct == {} &&"
                + " !has(msg.single_value) && msg.single_value == null &&"
                + " !has(msg.list_value) && msg.list_value == [] &&"
                + " !has(msg.field_mask) && msg.field_mask.paths == [] &&"
                + " !has(msg.empty)",
            TestAllTypes.getDefaultInstance());

    assertThat(populatedResult).isEqualTo(true);
    assertThat(unsetResult).isEqualTo(true);
  }

  @Test
  public void select_unknownMap_unsignedBoolKeysAndOutOfOrderEntryTags() throws Exception {
    // Encode a map<int32, int32> entry with tag 2 (value = 99) before tag 1 (key = 7), plus an
    // unknown tag 3 varint inside the MapEntry message that readMapField must skip.
    ByteArrayOutputStream outOfOrderEntry = new ByteArrayOutputStream();
    CodedOutputStream cos = CodedOutputStream.newInstance(outOfOrderEntry);
    cos.writeInt32(2, 99);
    cos.writeInt32(3, 12345);
    cos.writeInt32(1, 7);
    cos.flush();
    TestAllTypes msg =
        TestAllTypes.newBuilder()
            .putMapUint64String(-1L, "max_u64")
            .putMapUint32String(-1, "max_u32")
            .putMapBoolString(true, "yes")
            .putMapBoolString(false, "no")
            .setUnknownFields(
                UnknownFieldSet.newBuilder()
                    .addField(
                        TestAllTypes.MAP_INT32_INT32_FIELD_NUMBER,
                        UnknownFieldSet.Field.newBuilder()
                            .addLengthDelimited(ByteString.copyFrom(outOfOrderEntry.toByteArray()))
                            .build())
                    .build())
            .build();

    Object result =
        eval(
            "msg.map_uint64_string[18446744073709551615u] == 'max_u64' &&"
                + " msg.map_uint32_string[4294967295u] == 'max_u32' &&"
                + " msg.map_bool_string[true] == 'yes' &&"
                + " msg.map_bool_string[false] == 'no' &&"
                + " msg.map_int32_int32[7] == 99",
            msg);

    assertThat(result).isEqualTo(true);
  }

  @Test
  public void select_unsetKnownProto2Scalar_returnsEvolvedServerDefault() throws Exception {
    // Server V2 evolves single_int64's [default = -64] to [default = -999], while the V1 client
    // still knows single_int64 via its V1 CelLiteDescriptor. Because
    // CelLiteDescriptor.getFieldValue
    // returns null for unset proto2 scalars (!hasSingleInt64()), SelectOptimizer's V2 baked default
    // (-999) takes precedence when the field is unset.
    FileDescriptorProto.Builder v2Proto2FileBuilder =
        dev.cel.expr.conformance.proto2.TestAllTypes.getDescriptor()
            .getFile()
            .toProto()
            .toBuilder();
    for (FieldDescriptorProto.Builder fieldBuilder :
        v2Proto2FileBuilder.getMessageTypeBuilder(0).getFieldBuilderList()) {
      if (fieldBuilder.getName().equals("single_int64")) {
        fieldBuilder.setDefaultValue("-999");
      }
    }
    FileDescriptor v2Proto2File =
        FileDescriptor.buildFrom(
            v2Proto2FileBuilder.build(),
            dev.cel.expr.conformance.proto2.TestAllTypes.getDescriptor()
                .getFile()
                .getDependencies()
                .toArray(new FileDescriptor[0]));
    Program program =
        createEvolvedServerProgram(
            v2Proto2File,
            v2Proto2File.findMessageTypeByName("TestAllTypes"),
            "proto2_msg",
            CelContainer.ofName("cel.expr.conformance.proto2"),
            "[has(proto2_msg.single_int64), proto2_msg.single_int64]",
            dev.cel.expr.conformance.proto2.TestAllTypesCelDescriptor.getDescriptor());

    Object unsetResult =
        program.eval(
            ImmutableMap.of(
                "proto2_msg", dev.cel.expr.conformance.proto2.TestAllTypes.getDefaultInstance()));
    Object explicitZeroResult =
        program.eval(
            ImmutableMap.of(
                "proto2_msg",
                dev.cel.expr.conformance.proto2.TestAllTypes.newBuilder()
                    .setSingleInt64(0L)
                    .build()));

    assertThat(unsetResult).isEqualTo(ImmutableList.of(false, -999L));
    assertThat(explicitZeroResult).isEqualTo(ImmutableList.of(true, 0L));
  }

  @Test
  public void select_unknownFieldWithIncompatibleWireType_throws(
      @TestParameter({"msg.single_string", "has(msg.oneof_type.payload)"}) String expression)
      throws Exception {
    // single_string (field 14) and oneof_type (field 400) expect WIRETYPE_LENGTH_DELIMITED, but
    // wire carries WIRETYPE_VARINT.
    TestAllTypes corruptedMsg =
        TestAllTypes.newBuilder()
            .setUnknownFields(
                UnknownFieldSet.newBuilder()
                    .addField(
                        TestAllTypes.SINGLE_STRING_FIELD_NUMBER,
                        UnknownFieldSet.Field.newBuilder().addVarint(123L).build())
                    .addField(
                        TestAllTypes.ONEOF_TYPE_FIELD_NUMBER,
                        UnknownFieldSet.Field.newBuilder().addVarint(123L).build())
                    .build())
            .build();

    CelEvaluationException thrown =
        assertThrows(CelEvaluationException.class, () -> eval(expression, corruptedMsg));

    assertThat(thrown).hasCauseThat().isInstanceOf(InvalidProtocolBufferException.class);
  }

  @Test
  public void partialVars_unknownAttributeOnSkewedField_shortCircuits() throws Exception {
    CelAttribute attribute = CelAttribute.fromQualifiedIdentifier("msg.single_int64");
    CelAttributePattern pattern = CelAttributePattern.fromQualifiedIdentifier("msg.single_int64");
    Program program =
        clientRuntime.createProgram(
            serverOptimizer.optimize(
                serverCompiler.compile("msg.single_bool || msg.single_int64 > 0").getAst()));
    PartialVars shortCircuitVars =
        PartialVars.of(ImmutableMap.of("msg", POPULATED_SERVER_MESSAGE), pattern);
    PartialVars nonShortCircuitVars =
        PartialVars.of(ImmutableMap.of("msg", TestAllTypes.getDefaultInstance()), pattern);

    Object shortCircuitResult = program.eval(shortCircuitVars);
    Object unknownResult = program.eval(nonShortCircuitVars);

    assertThat(shortCircuitResult).isEqualTo(true);
    assertThat(unknownResult).isInstanceOf(CelUnknownSet.class);
    assertThat(((CelUnknownSet) unknownResult).attributes()).containsExactly(attribute);
  }

  @Test
  public void crossSerialization_fullProtoDynamicMessageToV1Lite() throws Exception {
    // Evolve TestAllTypes.getDescriptor() into a V2 server schema in memory:
    // - Rename field 1 (single_int32 -> v2_single_int32)
    // - Add V2 field v2_note (tag 2) to NestedMessage
    // - Add V2 root fields: v2_int64 (601), v2_nested (602), v2_tags (603), v2_scores (604), and
    //   oneof kind variant v2_oneof_str (605, sharing oneof kind with known V1 oneof_msg = 401).
    FileDescriptorProto.Builder v2FileBuilder =
        TestAllTypes.getDescriptor().getFile().toProto().toBuilder();
    DescriptorProto.Builder msgBuilder = v2FileBuilder.getMessageTypeBuilder(0);
    msgBuilder.getFieldBuilder(0).setName("v2_single_int32");
    msgBuilder
        .getNestedTypeBuilder(0)
        .addField(
            newFieldProto(
                "v2_note",
                2,
                FieldDescriptorProto.Label.LABEL_OPTIONAL,
                FieldDescriptorProto.Type.TYPE_STRING));
    msgBuilder
        .addNestedType(
            DescriptorProto.newBuilder()
                .setName("V2ScoresEntry")
                .setOptions(MessageOptions.newBuilder().setMapEntry(true))
                .addField(
                    newFieldProto(
                        "key",
                        1,
                        FieldDescriptorProto.Label.LABEL_OPTIONAL,
                        FieldDescriptorProto.Type.TYPE_STRING))
                .addField(
                    newFieldProto(
                        "value",
                        2,
                        FieldDescriptorProto.Label.LABEL_OPTIONAL,
                        FieldDescriptorProto.Type.TYPE_INT64)))
        .addField(
            newFieldProto(
                "v2_int64",
                601,
                FieldDescriptorProto.Label.LABEL_OPTIONAL,
                FieldDescriptorProto.Type.TYPE_INT64))
        .addField(
            newFieldProto(
                    "v2_nested",
                    602,
                    FieldDescriptorProto.Label.LABEL_OPTIONAL,
                    FieldDescriptorProto.Type.TYPE_MESSAGE)
                .setTypeName(".cel.expr.conformance.proto3.TestAllTypes.NestedMessage"))
        .addField(
            newFieldProto(
                "v2_tags",
                603,
                FieldDescriptorProto.Label.LABEL_REPEATED,
                FieldDescriptorProto.Type.TYPE_STRING))
        .addField(
            newFieldProto(
                    "v2_scores",
                    604,
                    FieldDescriptorProto.Label.LABEL_REPEATED,
                    FieldDescriptorProto.Type.TYPE_MESSAGE)
                .setTypeName(".cel.expr.conformance.proto3.TestAllTypes.V2ScoresEntry"))
        .addField(
            newFieldProto(
                    "v2_oneof_str",
                    605,
                    FieldDescriptorProto.Label.LABEL_OPTIONAL,
                    FieldDescriptorProto.Type.TYPE_STRING)
                .setOneofIndex(1));
    FileDescriptor v2FileDesc =
        FileDescriptor.buildFrom(
            v2FileBuilder.build(),
            TestAllTypes.getDescriptor()
                .getFile()
                .getDependencies()
                .toArray(new FileDescriptor[0]));
    Descriptor v2RootDesc = v2FileDesc.findMessageTypeByName("TestAllTypes");
    Descriptor v2NestedDesc = v2RootDesc.findNestedTypeByName("NestedMessage");
    Descriptor v2ScoresEntryDesc = v2RootDesc.findNestedTypeByName("V2ScoresEntry");
    DynamicMessage v2FullProtoMsg =
        DynamicMessage.newBuilder(v2RootDesc)
            .setField(v2RootDesc.findFieldByName("v2_single_int32"), 77)
            .setField(v2RootDesc.findFieldByName("single_string"), "known-v1")
            .setField(
                v2RootDesc.findFieldByName("standalone_message"),
                DynamicMessage.newBuilder(v2NestedDesc)
                    .setField(v2NestedDesc.findFieldByName("bb"), 7)
                    .setField(v2NestedDesc.findFieldByName("v2_note"), "from-full")
                    .build())
            .setField(v2RootDesc.findFieldByName("v2_int64"), -999L)
            .setField(
                v2RootDesc.findFieldByName("v2_nested"),
                DynamicMessage.newBuilder(v2NestedDesc)
                    .setField(v2NestedDesc.findFieldByName("bb"), 42)
                    .setField(v2NestedDesc.findFieldByName("v2_note"), "sub-note")
                    .build())
            .addRepeatedField(v2RootDesc.findFieldByName("v2_tags"), "t1")
            .addRepeatedField(v2RootDesc.findFieldByName("v2_tags"), "t2")
            .addRepeatedField(
                v2RootDesc.findFieldByName("v2_scores"),
                DynamicMessage.newBuilder(v2ScoresEntryDesc)
                    .setField(v2ScoresEntryDesc.findFieldByName("key"), "s1")
                    .setField(v2ScoresEntryDesc.findFieldByName("value"), 100L)
                    .build())
            .setField(v2RootDesc.findFieldByName("v2_oneof_str"), "picked")
            .build();
    TestAllTypes v1ClientMsg =
        TestAllTypes.parseFrom(
            v2FullProtoMsg.toByteArray(), ExtensionRegistryLite.getEmptyRegistry());
    Program evalProgram =
        createEvolvedServerProgram(
            v2FileDesc,
            v2RootDesc,
            "msg",
            CEL_CONTAINER,
            "has(msg.v2_single_int32) && msg.v2_single_int32 == 77 &&"
                + " msg.single_string == 'known-v1' &&"
                + " msg.standalone_message.bb == 7 &&"
                + " has(msg.standalone_message.v2_note) &&"
                + " msg.standalone_message.v2_note == 'from-full' &&"
                + " has(msg.v2_int64) && msg.v2_int64 == -999 &&"
                + " msg.v2_nested.bb == 42 &&"
                + " msg.v2_nested.v2_note == 'sub-note' &&"
                + " msg.v2_tags == ['t1', 't2'] &&"
                + " msg.v2_scores['s1'] == 100 &&"
                + " has(msg.v2_oneof_str) &&"
                + " msg.v2_oneof_str == 'picked' &&"
                + " !has(msg.oneof_msg) &&"
                + " !has(msg.oneof_bool)",
            TestAllTypesCelDescriptor.getDescriptor(),
            NestedTestAllTypesCelDescriptor.getDescriptor());
    Program leafSubmessageProgram =
        createEvolvedServerProgram(
            v2FileDesc,
            v2RootDesc,
            "msg",
            CEL_CONTAINER,
            "msg.v2_nested",
            TestAllTypesCelDescriptor.getDescriptor(),
            NestedTestAllTypesCelDescriptor.getDescriptor());

    Object evalResult = evalProgram.eval(ImmutableMap.of("msg", v1ClientMsg));
    Object v2NestedLeaf = leafSubmessageProgram.eval(ImmutableMap.of("msg", v1ClientMsg));

    assertThat(evalResult).isEqualTo(true);
    assertThat(v2NestedLeaf).isInstanceOf(NestedMessage.class);
    assertThat(((NestedMessage) v2NestedLeaf).getBb()).isEqualTo(42);
  }

  private static FieldDescriptorProto.Builder newFieldProto(
      String name, int number, FieldDescriptorProto.Label label, FieldDescriptorProto.Type type) {
    return FieldDescriptorProto.newBuilder()
        .setName(name)
        .setNumber(number)
        .setLabel(label)
        .setType(type);
  }

  private static Program createEvolvedServerProgram(
      FileDescriptor v2File,
      Descriptor v2RootDesc,
      String varName,
      CelContainer container,
      String expression,
      CelLiteDescriptor... clientDescriptors)
      throws Exception {
    Cel serverV2Compiler =
        CelFactory.plannerCelBuilder()
            .setOptions(CEL_OPTIONS)
            .setStandardMacros(CelStandardMacro.STANDARD_MACROS)
            .addMessageTypes(v2RootDesc)
            .addVar(varName, StructTypeReference.create(v2RootDesc.getFullName()))
            .setContainer(container)
            .build();
    CelOptimizer serverV2Optimizer =
        CelOptimizerFactory.standardCelOptimizerBuilder(serverV2Compiler)
            .addAstOptimizers(
                SelectOptimizer.newInstance(
                    SelectOptimizerOptions.newBuilder().enableLinkedMessageTypes(false).build(),
                    v2File))
            .build();
    CelLiteRuntime clientV1Runtime =
        CelLiteRuntimeFactory.newLiteRuntimeBuilder()
            .setStandardFunctions(CelStandardFunctions.ALL_STANDARD_FUNCTIONS)
            .setValueProvider(ProtoMessageLiteValueProvider.newInstance(clientDescriptors))
            .setContainer(container)
            .build();
    return clientV1Runtime.createProgram(
        serverV2Optimizer.optimize(serverV2Compiler.compile(expression).getAst()));
  }

  private Program compileScoreModelLateBoundProgram() throws Exception {
    Cel celWithLateFunc =
        serverCompiler
            .toCelBuilder()
            .addFunctionDeclarations(
                CelFunctionDecl.newFunctionDeclaration(
                    "scoreModel",
                    CelOverloadDecl.newGlobalOverload(
                        "scoreModel_string_list",
                        SimpleType.DOUBLE,
                        SimpleType.STRING,
                        ListType.create(SimpleType.DOUBLE))))
            .build();
    CelOptimizer optimizer =
        CelOptimizerFactory.standardCelOptimizerBuilder(celWithLateFunc)
            .addAstOptimizers(
                SelectOptimizer.newInstance(
                    SelectOptimizerOptions.newBuilder().build(),
                    TestAllTypes.getDescriptor().getFile()))
            .build();
    CelLiteRuntime runtimeWithLateFunc =
        clientRuntime.toRuntimeBuilder().addLateBoundFunctions("scoreModel").build();
    CelAbstractSyntaxTree optimizedAst =
        optimizer.optimize(
            celWithLateFunc.compile("scoreModel(msg.single_string, msg.repeated_double)").getAst());
    return runtimeWithLateFunc.createProgram(optimizedAst);
  }

  @SuppressWarnings("unchecked") // test only
  private static CelLateFunctionBindings newScoreModelLateBindings() {
    return CelLateFunctionBindings.from(
        CelFunctionBinding.from(
            "scoreModel_string_list",
            String.class,
            List.class,
            (label, weights) -> {
              double sum = 0.0d;
              for (Double w : (List<Double>) weights) {
                sum += w;
              }
              return label.isEmpty() ? -1.0d : sum * label.length();
            }));
  }

  private Object eval(String expression, TestAllTypes message) throws Exception {
    CelAbstractSyntaxTree ast = serverCompiler.compile(expression).getAst();
    CelAbstractSyntaxTree optimizedAst = serverOptimizer.optimize(ast);
    Program program = clientRuntime.createProgram(optimizedAst);
    return program.eval(ImmutableMap.of("msg", message));
  }

  private Object evalNested(String expression, NestedTestAllTypes nestedMessage) throws Exception {
    CelAbstractSyntaxTree ast = serverCompiler.compile(expression).getAst();
    CelAbstractSyntaxTree optimizedAst = serverOptimizer.optimize(ast);
    Program program = clientRuntime.createProgram(optimizedAst);
    return program.eval(ImmutableMap.of("nested_msg", nestedMessage));
  }

  private static Object evalProto2(
      String expression, dev.cel.expr.conformance.proto2.TestAllTypes proto2Message)
      throws Exception {
    Cel proto2Cel =
        CelFactory.plannerCelBuilder()
            .setOptions(CEL_OPTIONS)
            .setStandardMacros(CelStandardMacro.STANDARD_MACROS)
            .addMessageTypes(dev.cel.expr.conformance.proto2.TestAllTypes.getDescriptor())
            .addVar(
                "proto2_msg",
                StructTypeReference.create(
                    dev.cel.expr.conformance.proto2.TestAllTypes.getDescriptor().getFullName()))
            .setContainer(CelContainer.ofName("cel.expr.conformance.proto2"))
            .build();
    CelOptimizer proto2Optimizer =
        CelOptimizerFactory.standardCelOptimizerBuilder(proto2Cel)
            .addAstOptimizers(
                SelectOptimizer.newInstance(
                    SelectOptimizerOptions.newBuilder().build(),
                    dev.cel.expr.conformance.proto2.TestAllTypes.getDescriptor().getFile()))
            .build();
    CelLiteDescriptor fullProto2Descriptor =
        dev.cel.expr.conformance.proto2.TestAllTypesCelDescriptor.getDescriptor();
    MessageLiteDescriptor fullProto2MsgDesc =
        fullProto2Descriptor
            .getProtoTypeNamesToDescriptors()
            .get(dev.cel.expr.conformance.proto2.TestAllTypes.getDescriptor().getFullName());
    MessageLiteDescriptor clientProto2MsgDesc =
        buildClientTestAllTypesDescriptor(fullProto2MsgDesc, "single_int32");
    CelLiteDescriptor clientProto2Descriptor =
        newClientCelLiteDescriptor(
            "v1_proto2", fullProto2Descriptor, ImmutableList.of(clientProto2MsgDesc));
    CelLiteRuntime clientProto2Runtime =
        CelLiteRuntimeFactory.newLiteRuntimeBuilder()
            .setStandardFunctions(CelStandardFunctions.ALL_STANDARD_FUNCTIONS)
            .setValueProvider(ProtoMessageLiteValueProvider.newInstance(clientProto2Descriptor))
            .setContainer(CelContainer.ofName("cel.expr.conformance.proto2"))
            .build();
    CelAbstractSyntaxTree optimizedAst =
        proto2Optimizer.optimize(proto2Cel.compile(expression).getAst());
    return clientProto2Runtime
        .createProgram(optimizedAst)
        .eval(ImmutableMap.of("proto2_msg", proto2Message));
  }

  private static ByteString encodeStringMapEntry(@Nullable String key, @Nullable String value)
      throws Exception {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    CodedOutputStream cos = CodedOutputStream.newInstance(baos);
    if (key != null) {
      cos.writeString(1, key);
    }
    if (value != null) {
      cos.writeString(2, value);
    }
    cos.flush();
    return ByteString.copyFrom(baos.toByteArray());
  }

  private static MessageLiteDescriptor buildClientTestAllTypesDescriptor(
      MessageLiteDescriptor fullMsgDesc, String... additionalExcludedFields) {
    ImmutableSet<String> extraExcluded = ImmutableSet.copyOf(additionalExcludedFields);
    ImmutableList.Builder<FieldLiteDescriptor> clientFields = ImmutableList.builder();
    for (FieldLiteDescriptor f : fullMsgDesc.getFieldDescriptors()) {
      if (SERVER_ONLY_FIELD_NAMES.contains(f.getFieldName())
          || extraExcluded.contains(f.getFieldName())) {
        continue;
      }
      String clientFieldName =
          CLIENT_RENAMED_FIELD_NAMES.getOrDefault(f.getFieldName(), f.getFieldName());
      if (!clientFieldName.equals(f.getFieldName())) {
        clientFields.add(
            new FieldLiteDescriptor(
                f.getFieldNumber(),
                clientFieldName,
                f.getEncodingType(),
                f.getProtoFieldType(),
                f.getIsPacked(),
                f.getFieldProtoTypeName()));
      } else {
        clientFields.add(f);
      }
    }
    return new MessageLiteDescriptor(
        fullMsgDesc.getProtoTypeName(), clientFields.build(), fullMsgDesc::newMessageBuilder);
  }

  // Reads the client's message types through the full descriptor's getters for the same types.
  private static CelLiteDescriptor newClientCelLiteDescriptor(
      String version,
      CelLiteDescriptor fullDescriptor,
      ImmutableList<MessageLiteDescriptor> descriptors) {
    return new CelLiteDescriptor(version, descriptors) {
      @Override
      protected @Nullable Object getFieldValue(int messageIndex, Object message, int fieldNumber) {
        MessageLiteDescriptor clientDesc = descriptors.get(messageIndex);
        // Like the client's generated code, which has no getter for a field its schema lacks.
        if (clientDesc.findByFieldNumber(fieldNumber) == null) {
          return null;
        }
        return fullDescriptor
            .getProtoTypeNamesToDescriptors()
            .get(clientDesc.getProtoTypeName())
            .getFieldValue((MessageLite) message, fieldNumber);
      }
    };
  }

  private static CelLiteRuntime newRuntimeWithoutNestedMessageDescriptor() {
    return newRuntimeWithoutFieldDescriptor();
  }

  private static CelLiteRuntime newRuntimeWithoutFieldDescriptor(String... excludedFieldNames) {
    CelLiteDescriptor fullDescriptor = TestAllTypesCelDescriptor.getDescriptor();
    MessageLiteDescriptor clientTestAllTypesDesc =
        buildClientTestAllTypesDescriptor(
            fullDescriptor
                .getProtoTypeNamesToDescriptors()
                .get(TestAllTypes.getDescriptor().getFullName()),
            excludedFieldNames);
    CelLiteDescriptor partialDescriptor =
        newClientCelLiteDescriptor(
            "v1_partial", fullDescriptor, ImmutableList.of(clientTestAllTypesDesc));
    return CelLiteRuntimeFactory.newLiteRuntimeBuilder()
        .setStandardFunctions(CelStandardFunctions.ALL_STANDARD_FUNCTIONS)
        .setValueProvider(ProtoMessageLiteValueProvider.newInstance(partialDescriptor))
        .setContainer(CEL_CONTAINER)
        .build();
  }

  private static CelLiteRuntime newDescriptorlessRuntime() {
    return CelLiteRuntimeFactory.newLiteRuntimeBuilder()
        .setStandardFunctions(CelStandardFunctions.ALL_STANDARD_FUNCTIONS)
        .setValueProvider(ProtoMessageLiteValueProvider.newInstance())
        .setContainer(CEL_CONTAINER)
        .build();
  }

  private static CelLiteRuntime newOlderClassV1Runtime() {
    FieldLiteDescriptor v1SingleInt32Field =
        new FieldLiteDescriptor(
            /* fieldNumber= */ 1,
            /* fieldName= */ "v1_single_int32",
            /* encodingType= */ FieldLiteDescriptor.EncodingType.SINGULAR,
            /* protoFieldType= */ FieldLiteDescriptor.Type.INT32,
            /* isPacked= */ false,
            /* fieldProtoTypeName= */ "");
    FieldLiteDescriptor v1RepeatedInt32Field =
        new FieldLiteDescriptor(
            /* fieldNumber= */ TestAllTypes.REPEATED_INT32_FIELD_NUMBER,
            /* fieldName= */ "v1_repeated_int32",
            /* encodingType= */ FieldLiteDescriptor.EncodingType.LIST,
            /* protoFieldType= */ FieldLiteDescriptor.Type.INT32,
            /* isPacked= */ true,
            /* fieldProtoTypeName= */ "");
    MessageLiteDescriptor v1OlderClassTestAllTypesDesc =
        new MessageLiteDescriptor(
            TestAllTypes.getDescriptor().getFullName(),
            ImmutableList.of(v1SingleInt32Field, v1RepeatedInt32Field),
            NestedMessage::newBuilder);
    CelLiteDescriptor v1OlderClassDescriptor =
        new CelLiteDescriptor("v1_older_class", ImmutableList.of(v1OlderClassTestAllTypesDesc)) {
          @Override
          protected @Nullable Object getFieldValue(
              int messageIndex, Object message, int fieldNumber) {
            return fieldNumber == 1 ? nonDefault(((NestedMessage) message).getBb()) : null;
          }
        };
    return CelLiteRuntimeFactory.newLiteRuntimeBuilder()
        .setStandardFunctions(CelStandardFunctions.ALL_STANDARD_FUNCTIONS)
        .setValueProvider(
            ProtoMessageLiteValueProvider.newInstance(
                v1OlderClassDescriptor, NestedTestAllTypesCelDescriptor.getDescriptor()))
        .setContainer(CEL_CONTAINER)
        .build();
  }

  private CelOptimizer newSubexpressionOptimizer() {
    return CelOptimizerFactory.standardCelOptimizerBuilder(serverCompiler)
        .addAstOptimizers(
            SubexpressionOptimizer.getInstance(),
            SelectOptimizer.newInstance(
                SelectOptimizerOptions.newBuilder().build(),
                TestAllTypes.getDescriptor().getFile()))
        .build();
  }
}
