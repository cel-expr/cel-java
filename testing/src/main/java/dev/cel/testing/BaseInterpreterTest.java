// Copyright 2022 Google LLC
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

package dev.cel.testing;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.truth.Truth.assertThat;
import static dev.cel.runtime.CelVariableResolver.hierarchicalVariableResolver;
import static java.nio.charset.StandardCharsets.UTF_8;

import dev.cel.expr.CheckedExpr;
import dev.cel.expr.ParsedExpr;
import dev.cel.expr.Type;
import com.google.common.base.Ascii;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.io.Resources;
import com.google.common.primitives.UnsignedLong;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.errorprone.annotations.Immutable;
import com.google.protobuf.Any;
import com.google.protobuf.BoolValue;
import com.google.protobuf.ByteString;
import com.google.protobuf.BytesValue;
import com.google.protobuf.DescriptorProtos.FileDescriptorSet;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.DoubleValue;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.FloatValue;
import com.google.protobuf.Int32Value;
import com.google.protobuf.Int64Value;
import com.google.protobuf.ListValue;
import com.google.protobuf.Message;
import com.google.protobuf.NullValue;
import com.google.protobuf.StringValue;
import com.google.protobuf.Struct;
import com.google.protobuf.TextFormat;
import com.google.protobuf.Timestamp;
import com.google.protobuf.UInt32Value;
import com.google.protobuf.UInt64Value;
import com.google.protobuf.UnredactedDebugFormatForTest;
import com.google.protobuf.Value;
import com.google.protobuf.util.JsonFormat;
import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.CelContainer;
import dev.cel.common.CelOptions;
import dev.cel.common.CelProtoAbstractSyntaxTree;
import dev.cel.common.internal.DefaultDescriptorPool;
import dev.cel.common.internal.FileDescriptorSetConverter;
import dev.cel.common.internal.ProtoTimeUtils;
import dev.cel.common.types.CelType;
import dev.cel.common.types.CelTypeProvider;
import dev.cel.common.types.ListType;
import dev.cel.common.types.MapType;
import dev.cel.common.types.OpaqueType;
import dev.cel.common.types.ProtoMessageTypeProvider;
import dev.cel.common.types.SimpleType;
import dev.cel.common.types.StructTypeReference;
import dev.cel.common.types.TypeParamType;
import dev.cel.common.values.CelByteString;
import dev.cel.expr.conformance.proto3.TestAllTypes;
import dev.cel.expr.conformance.proto3.TestAllTypes.NestedEnum;
import dev.cel.expr.conformance.proto3.TestAllTypes.NestedMessage;
import dev.cel.extensions.CelExtensions;
import dev.cel.extensions.CelOptionalLibrary;
import dev.cel.runtime.CelAttributePattern;
import dev.cel.runtime.CelEvaluationException;
import dev.cel.runtime.CelFunctionBinding;
import dev.cel.runtime.CelLateFunctionBindings;
import dev.cel.runtime.CelRuntime;
import dev.cel.runtime.CelRuntimeBuilder;
import dev.cel.runtime.CelRuntimeFactory;
import dev.cel.runtime.CelUnknownSet;
import dev.cel.runtime.CelVariableResolver;
import dev.cel.runtime.PartialVars;
import dev.cel.testing.testdata.proto3.StandaloneGlobalEnum;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.LongStream;
import org.junit.Test;

/** Base class for evaluation outputs that can be stored and used as a baseline test. */
public abstract class BaseInterpreterTest extends CelBaselineTestCase {

  protected static final Descriptor TEST_ALL_TYPE_DYNAMIC_DESCRIPTOR =
      getDeserializedTestAllTypeDescriptor();

  protected static final ImmutableList<FileDescriptor> TEST_FILE_DESCRIPTORS =
      ImmutableList.of(
          dev.cel.expr.conformance.proto2.TestAllTypes.getDescriptor().getFile(),
          TestAllTypes.getDescriptor().getFile(),
          StandaloneGlobalEnum.getDescriptor().getFile(),
          TEST_ALL_TYPE_DYNAMIC_DESCRIPTOR.getFile());

  private static final CelOptions BASE_CEL_OPTIONS =
      CelOptions.current()
          .enableHeterogeneousNumericComparisons(true)
          .enableOptionalSyntax(true)
          .comprehensionMaxIterations(1_000)
          .build();
  protected CelRuntime celRuntime;
  private CelOptions celOptions;

  protected BaseInterpreterTest() {
    this.celOptions = BASE_CEL_OPTIONS;
    this.celRuntime = newBaseRuntimeBuilder(celOptions).build();
  }

  protected BaseInterpreterTest(CelRuntime celRuntime) {
    this.celRuntime = celRuntime;
    this.celOptions = BASE_CEL_OPTIONS;
  }

  protected CelRuntimeBuilder newBaseRuntimeBuilder(CelOptions celOptions) {
    return CelRuntimeFactory.standardCelRuntimeBuilder()
        .addLibraries(CelOptionalLibrary.INSTANCE)
        .addFileTypes(TEST_FILE_DESCRIPTORS)
        .setOptions(celOptions);
  }

  @Override
  protected CelAbstractSyntaxTree prepareTest(List<FileDescriptor> descriptors) {
    return prepareTest(
        ProtoMessageTypeProvider.newBuilder()
            .addFileDescriptors(descriptors)
            .setAllowJsonFieldNames(celOptions.enableJsonFieldNames())
            .build());
  }

  @Override
  protected void prepareCompiler(CelTypeProvider typeProvider) {
    super.prepareCompiler(typeProvider);
    this.celCompiler =
        celCompiler
            .toCompilerBuilder()
            .addLibraries(CelOptionalLibrary.INSTANCE, CelExtensions.bindings())
            .setOptions(celOptions)
            .build();
  }

  protected void setContainer(CelContainer container) {
    this.container = container;
  }

  private CelAbstractSyntaxTree compileTestCase() {
    CelAbstractSyntaxTree ast = prepareTest(TEST_FILE_DESCRIPTORS);
    if (ast == null) {
      return null;
    }
    assertAstRoundTrip(ast);

    return ast;
  }

  @CanIgnoreReturnValue
  private Object runTest() {
    return runTest(ImmutableMap.of());
  }

  @CanIgnoreReturnValue
  private Object runTest(CelVariableResolver variableResolver) {
    return runTestInternal(variableResolver, Optional.empty());
  }

  /** Helper to run a test for configured instance variables. */
  @CanIgnoreReturnValue
  private Object runTest(Map<String, ?> input) {
    return runTestInternal(input, Optional.empty());
  }

  /** Helper to run a test for configured instance variables. */
  @CanIgnoreReturnValue
  private Object runTest(Map<String, ?> input, CelLateFunctionBindings lateFunctionBindings) {
    return runTestInternal(input, Optional.of(lateFunctionBindings));
  }

  @CanIgnoreReturnValue
  protected Object runTest(Map<String, ?> input, CelAttributePattern... patterns) {
    return runTestInternal(input, Optional.empty(), patterns);
  }

  /**
   * Helper to run a test for configured instance variables. Input must be of type map or {@link
   * CelVariableResolver}.
   */
  private Object runTestInternal(
      Object input, Optional<CelLateFunctionBindings> lateFunctionBindings) {
    return runTestInternal(input, lateFunctionBindings, new CelAttributePattern[0]);
  }

  // Test only
  @SuppressWarnings("unchecked")
  private Object runTestInternal(
      Object input,
      Optional<CelLateFunctionBindings> lateFunctionBindings,
      CelAttributePattern... patterns) {
    CelAbstractSyntaxTree ast = compileTestCase();
    if (ast == null) {
      // Usually indicates test was not setup correctly
      println("Source compilation failed");
      return null;
    }
    printBinding(input, patterns);
    Object result = null;
    try {
      CelRuntime.Program program = celRuntime.createProgram(ast);
      if (patterns.length > 0) {
        PartialVars partialVars =
            input instanceof Map
                ? PartialVars.of((Map<String, ?>) input, patterns)
                : PartialVars.of((CelVariableResolver) input, patterns);
        result = program.eval(partialVars);
      } else if (lateFunctionBindings.isPresent()) {
        if (input instanceof Map) {
          Map<String, ?> map = ((Map<String, ?>) input);
          CelVariableResolver variableResolver = (name) -> Optional.ofNullable(map.get(name));
          result = program.eval(variableResolver, lateFunctionBindings.get());
        } else {
          result = program.eval((CelVariableResolver) input, lateFunctionBindings.get());
        }
      } else {
        result =
            input instanceof Map
                ? program.eval(((Map<String, ?>) input))
                : program.eval((CelVariableResolver) input);
      }
      if (result instanceof CelByteString) {
        // Note: this call may fail for printing byte sequences that are not valid UTF-8, but works
        // pretty well for test purposes.
        result = ((CelByteString) result).toStringUtf8();
      }
      println("result:   " + UnredactedDebugFormatForTest.unredactedToString(result));
    } catch (CelEvaluationException e) {
      println("error:    " + e.getMessage());
      println("error_code:    " + e.getErrorCode());
    }
    println("");
    return result;
  }

  @Test
  public void arithmInt64() {
    source = "1 < 2 && 1 <= 1 && 2 > 1 && 1 >= 1 && 1 == 1 && 2 != 1";
    runTest();

    declareVariable("x", SimpleType.INT);
    source = "1 + 2 - x * 3 / x + (x % 3)";
    runTest(ImmutableMap.of("x", -5L));

    declareVariable("y", SimpleType.DYN);
    source = "x + y == 1";
    runTest(extend(ImmutableMap.of("x", -5L), ImmutableMap.of("y", 6L)));
  }

  @Test
  public void arithmInt64_error() {
    source = "9223372036854775807 + 1";
    runTest();

    source = "-9223372036854775808 - 1";
    runTest();

    source = "-(-9223372036854775808)";
    runTest();

    source = "5000000000 * 5000000000";
    runTest();

    source = "(-9223372036854775808)/-1";
    runTest();

    source = "1 / 0";
    runTest();

    source = "1 % 0";
    runTest();
  }

  @Test
  public void arithmUInt64() {
    source = "1u < 2u && 1u <= 1u && 2u > 1u && 1u >= 1u && 1u == 1u && 2u != 1u";
    runTest();

    boolean useUnsignedLongs = BASE_CEL_OPTIONS.enableUnsignedLongs();
    declareVariable("x", SimpleType.UINT);
    source = "1u + 2u + x * 3u / x + (x % 3u)";
    runTest(ImmutableMap.of("x", useUnsignedLongs ? UnsignedLong.valueOf(5L) : 5L));

    declareVariable("y", SimpleType.DYN);
    source = "x + y == 11u";
    runTest(
        extend(
            ImmutableMap.of("x", useUnsignedLongs ? UnsignedLong.valueOf(5L) : 5L),
            ImmutableMap.of("y", useUnsignedLongs ? UnsignedLong.valueOf(6L) : 6)));

    source = "x - y == 1u";
    runTest(
        extend(
            ImmutableMap.of("x", useUnsignedLongs ? UnsignedLong.valueOf(6L) : 6L),
            ImmutableMap.of("y", useUnsignedLongs ? UnsignedLong.valueOf(5) : 5)));
  }

  @Test
  public void arithmUInt64_error() {
    source = "18446744073709551615u + 1u";
    runTest();

    source = "0u - 1u";
    runTest();

    source = "5000000000u * 5000000000u";
    runTest();

    source = "1u / 0u";
    runTest();

    source = "1u % 0u";
    runTest();
  }

  @Test
  public void arithmDouble() {
    source = "0.0 == -0.0";
    runTest();

    source = "1.9 < 2.0 && 1.1 <= 1.1 && 2.0 > 1.9 && 1.1 >= 1.1 && 1.1 == 1.1 && 2.0 != 1.9";
    runTest();

    declareVariable("x", SimpleType.DOUBLE);
    source = "1.0 + 2.3 + x * 3.0 / x";
    runTest(ImmutableMap.of("x", 3.33));

    declareVariable("y", SimpleType.DYN);
    source = "x + y == 9.99";
    runTest(extend(ImmutableMap.of("x", 3.33d), ImmutableMap.of("y", 6.66)));
  }

  @Test
  public void quantifiers() {
    source = "[1,-2,3].exists_one(x, x > 0)";
    runTest();

    source = "[-1,-2,3].exists_one(x, x > 0)";
    runTest();

    source = "[-1,-2,-3].exists(x, x > 0)";
    runTest();

    source = "[1,-2,3].exists(x, x > 0)";
    runTest();

    source = "[1,-2,3].all(x, x > 0)";
    runTest();

    source = "[1,2,3].all(x, x > 0)";
    runTest();
  }

  @Test
  public void arithmTimestamp() {
    setContainer(CelContainer.ofName(Type.getDescriptor().getFile().getPackage()));
    declareVariable("ts1", SimpleType.TIMESTAMP);
    declareVariable("ts2", SimpleType.TIMESTAMP);
    declareVariable("d1", SimpleType.DURATION);
    Duration d1 = Duration.ofSeconds(15, 25);
    Instant ts1 = Instant.ofEpochSecond(25, 35);
    Instant ts2 = Instant.ofEpochSecond(10, 10);
    CelVariableResolver resolver =
        extend(
            extend(ImmutableMap.of("d1", d1), ImmutableMap.of("ts1", ts1)),
            ImmutableMap.of("ts2", ts2));

    source = "ts1 - ts2 == d1";
    runTest(resolver);

    source = "ts1 - d1 == ts2";
    runTest(resolver);

    source = "ts2 + d1 == ts1";
    runTest(resolver);

    source = "d1 + ts2 == ts1";
    runTest(resolver);
  }

  @Test
  public void arithmDuration() {
    setContainer(CelContainer.ofName(Type.getDescriptor().getFile().getPackage()));
    declareVariable("d1", SimpleType.DURATION);
    declareVariable("d2", SimpleType.DURATION);
    declareVariable("d3", SimpleType.DURATION);
    java.time.Duration d1 = java.time.Duration.ofSeconds(15, 25);
    java.time.Duration d2 = java.time.Duration.ofSeconds(10, 20);
    java.time.Duration d3 = java.time.Duration.ofSeconds(25, 45);

    CelVariableResolver resolver =
        extend(
            extend(ImmutableMap.of("d1", d1), ImmutableMap.of("d2", d2)),
            ImmutableMap.of("d3", d3));

    source = "d1 + d2 == d3";
    runTest(resolver);

    source = "d3 - d1 == d2";
    runTest(resolver);
  }

  @Test
  public void arithCrossNumericTypes() {
    if (!BASE_CEL_OPTIONS.enableUnsignedLongs()) {
      skipBaselineVerification();
      return;
    }
    source = "1.9 < 2 && 1 < 1.1 && 2u < 2.9 && 1.1 < 2u && 1 < 2u && 2u < 3";
    runTest();

    source = "1.9 <= 2 && 1 <= 1.1 && 2u <= 2.9 && 1.1 <= 2u && 2 <= 2u && 2u <= 2";
    runTest();

    source = "1.9 > 2 && 1 > 1.1 && 2u > 2.9 && 1.1 > 2u && 2 > 2u && 2u > 2";
    runTest();

    source = "1.9 >= 2 && 1 >= 1.1 && 2u >= 2.9 && 1.1 >= 2u && 2 >= 2u && 2u >= 2";
    runTest();
  }

  @Test
  public void booleans() {
    declareVariable("x", SimpleType.BOOL);
    source = "x ? 1 : 0";
    runTest(ImmutableMap.of("x", true));
    runTest(ImmutableMap.of("x", false));

    source = "(1 / 0 == 0 && false) == (false && 1 / 0 == 0)";
    runTest();

    source = "(1 / 0 == 0 || true) == (true || 1 / 0 == 0)";
    runTest();

    declareVariable("y", SimpleType.INT);
    source = "1 / y == 1 || true";
    runTest(ImmutableMap.of("y", 0L));

    source = "1 / y == 1 && false";
    runTest(ImmutableMap.of("y", 0L));

    source = "(true > false) == true";
    runTest();

    source = "(true > true) == false";
    runTest();

    source = "(false > true) == false";
    runTest();

    source = "(false > false) == false";
    runTest();

    source = "(true >= false) == true";
    runTest();

    source = "(true >= true) == true";
    runTest();

    source = "(false >= false) == true";
    runTest();

    source = "(false >= true) == false";
    runTest();

    source = "(false < true) == true";
    runTest();

    source = "(false < false) == false";
    runTest();

    source = "(true < false) == false";
    runTest();

    source = "(true < true) == false";
    runTest();

    source = "(false <= true) == true";
    runTest();

    source = "(false <= false) == true";
    runTest();

    source = "(true <= false) == false";
    runTest();

    source = "(true <= true) == true";
    runTest();
  }

  @Test
  public void booleans_error() {
    declareVariable("y", SimpleType.INT);

    source = "1 / y == 1 || false";
    runTest(ImmutableMap.of("y", 0L));

    source = "false || 1 / y == 1";
    runTest(ImmutableMap.of("y", 0L));

    source = "1 / y == 1 && true";
    runTest(ImmutableMap.of("y", 0L));

    source = "true && 1 / y == 1";
    runTest(ImmutableMap.of("y", 0L));
  }

  @Test
  public void strings() throws Exception {
    source = "'a' < 'b' && 'a' <= 'b' && 'b' > 'a' && 'a' >= 'a' && 'a' == 'a' && 'a' != 'b'";
    runTest();

    declareVariable("x", SimpleType.STRING);
    source =
        "'abc' + x == 'abcdef' && "
            + "x.endsWith('ef') && "
            + "x.startsWith('d') && "
            + "x.contains('de') && "
            + "!x.contains('abcdef')";
    runTest(ImmutableMap.of("x", "def"));
  }

  @Test
  public void messages() throws Exception {
    TestAllTypes nestedMessage =
        TestAllTypes.newBuilder()
            .setSingleNestedMessage(NestedMessage.newBuilder().setBb(43))
            .build();
    declareVariable("x", StructTypeReference.create(TestAllTypes.getDescriptor().getFullName()));
    source = "x.single_nested_message.bb == 43 && has(x.single_nested_message)";
    runTest(ImmutableMap.of("x", nestedMessage));

    declareVariable(
        "single_nested_message",
        StructTypeReference.create(NestedMessage.getDescriptor().getFullName()));
    source = "single_nested_message.bb == 43";
    runTest(ImmutableMap.of("single_nested_message", nestedMessage.getSingleNestedMessage()));

    source = "TestAllTypes{single_int64: 1, single_sfixed64: 2, single_int32: 2}.single_int32 == 2";
    setContainer(CelContainer.ofName(TestAllTypes.getDescriptor().getFile().getPackage()));
    runTest();
  }

  @Test
  public void messages_error() {
    source = "TestAllTypes{single_int32_wrapper: 12345678900}";
    setContainer(CelContainer.ofName(TestAllTypes.getDescriptor().getFile().getPackage()));
    runTest();

    source = "TestAllTypes{}.map_string_string.a";
    runTest();
  }

  @Test
  public void optional() {
    // TODO: Move existing optional tests here to also test CelValue runtime
    source = "optional.unwrap([])";
    runTest();

    declareVariable("str", SimpleType.STRING);
    source = "optional.unwrap([optional.none(), optional.of(1), optional.of(str)])";
    runTest(ImmutableMap.of("str", "foo"));
  }

  @Test
  public void optional_errors() {
    source = "optional.unwrap([dyn(1)])";
    runTest();
  }

  @Test
  public void containers() {
    setContainer(
        CelContainer.newBuilder()
            .setName("dev.cel.testing.testdata.proto3.StandaloneGlobalEnum")
            .addAlias("test_alias", TestAllTypes.getDescriptor().getFile().getPackage())
            .addAbbreviations("cel.expr.conformance.proto2", "cel.expr.conformance.proto3")
            .build());
    source = "test_alias.TestAllTypes{} == cel.expr.conformance.proto3.TestAllTypes{}";
    runTest();

    source = "proto2.TestAllTypes{} == cel.expr.conformance.proto2.TestAllTypes{}";
    runTest();

    source = "proto3.TestAllTypes{} == cel.expr.conformance.proto3.TestAllTypes{}";
    runTest();

    source = "SGAR"; // From StandaloneGlobalEnum
    runTest();
  }

  @Test
  public void has() throws Exception {
    TestAllTypes nestedMessage =
        TestAllTypes.newBuilder()
            .setSingleInt32(1)
            .setSingleInt64(0L)
            .setSingleBoolWrapper(BoolValue.newBuilder().setValue(false))
            .setSingleInt32Wrapper(Int32Value.newBuilder().setValue(42))
            .setOptionalBool(false)
            .setOneofBool(false)
            .addRepeatedInt32(1)
            .putMapInt32Int64(1, 2L)
            .setSingleNestedMessage(NestedMessage.newBuilder().setBb(43))
            .build();
    declareVariable("x", StructTypeReference.create(TestAllTypes.getDescriptor().getFullName()));
    source =
        "has(x.single_int32) && !has(x.single_int64) && has(x.single_bool_wrapper)"
            + " && has(x.single_int32_wrapper) && !has(x.single_int64_wrapper)"
            + " && has(x.repeated_int32) && !has(x.repeated_int64)"
            + " && has(x.optional_bool) && !has(x.optional_string)"
            + " && has(x.oneof_bool) && !has(x.oneof_type)"
            + " && has(x.map_int32_int64) && !has(x.map_string_string)"
            + " && has(x.single_nested_message) && !has(x.single_duration)";
    runTest(ImmutableMap.of("x", nestedMessage));
  }

  @Test
  public void duration() throws Exception {
    declareVariable("d1", SimpleType.DURATION);
    declareVariable("d2", SimpleType.DURATION);
    java.time.Duration d1010 = java.time.Duration.ofSeconds(10, 10);
    java.time.Duration d1009 = java.time.Duration.ofSeconds(10, 9);
    java.time.Duration d0910 = java.time.Duration.ofSeconds(9, 10);
    setContainer(CelContainer.ofName(Type.getDescriptor().getFile().getPackage()));

    source = "d1 < d2";
    runTest(extend(ImmutableMap.of("d1", d1010), ImmutableMap.of("d2", d1009)));
    runTest(extend(ImmutableMap.of("d1", d1010), ImmutableMap.of("d2", d0910)));
    runTest(extend(ImmutableMap.of("d1", d1010), ImmutableMap.of("d2", d1010)));
    runTest(extend(ImmutableMap.of("d1", d1009), ImmutableMap.of("d2", d1010)));
    runTest(extend(ImmutableMap.of("d1", d0910), ImmutableMap.of("d2", d1010)));

    source = "d1 <= d2";
    runTest(extend(ImmutableMap.of("d1", d1010), ImmutableMap.of("d2", d1009)));
    runTest(extend(ImmutableMap.of("d1", d1010), ImmutableMap.of("d2", d0910)));
    runTest(extend(ImmutableMap.of("d1", d1010), ImmutableMap.of("d2", d1010)));
    runTest(extend(ImmutableMap.of("d1", d1009), ImmutableMap.of("d2", d1010)));
    runTest(extend(ImmutableMap.of("d1", d0910), ImmutableMap.of("d2", d1010)));

    source = "d1 > d2";
    runTest(extend(ImmutableMap.of("d1", d1010), ImmutableMap.of("d2", d1009)));
    runTest(extend(ImmutableMap.of("d1", d1010), ImmutableMap.of("d2", d0910)));
    runTest(extend(ImmutableMap.of("d1", d1010), ImmutableMap.of("d2", d1010)));
    runTest(extend(ImmutableMap.of("d1", d1009), ImmutableMap.of("d2", d1010)));
    runTest(extend(ImmutableMap.of("d1", d0910), ImmutableMap.of("d2", d1010)));

    source = "d1 >= d2";
    runTest(extend(ImmutableMap.of("d1", d1010), ImmutableMap.of("d2", d1009)));
    runTest(extend(ImmutableMap.of("d1", d1010), ImmutableMap.of("d2", d0910)));
    runTest(extend(ImmutableMap.of("d1", d1010), ImmutableMap.of("d2", d1010)));
    runTest(extend(ImmutableMap.of("d1", d1009), ImmutableMap.of("d2", d1010)));
    runTest(extend(ImmutableMap.of("d1", d0910), ImmutableMap.of("d2", d1010)));
  }

  @Test
  public void timestamp() throws Exception {
    declareVariable("t1", SimpleType.TIMESTAMP);
    declareVariable("t2", SimpleType.TIMESTAMP);
    Instant ts1010 = Instant.ofEpochSecond(10, 10);
    Instant ts1009 = Instant.ofEpochSecond(10, 9);
    Instant ts0910 = Instant.ofEpochSecond(9, 10);
    setContainer(CelContainer.ofName(Type.getDescriptor().getFile().getPackage()));

    source = "t1 < t2";
    runTest(extend(ImmutableMap.of("t1", ts1010), ImmutableMap.of("t2", ts1009)));
    runTest(extend(ImmutableMap.of("t1", ts1010), ImmutableMap.of("t2", ts0910)));
    runTest(extend(ImmutableMap.of("t1", ts1010), ImmutableMap.of("t2", ts1010)));
    runTest(extend(ImmutableMap.of("t1", ts1009), ImmutableMap.of("t2", ts1010)));
    runTest(extend(ImmutableMap.of("t1", ts0910), ImmutableMap.of("t2", ts1010)));

    source = "t1 <= t2";
    runTest(extend(ImmutableMap.of("t1", ts1010), ImmutableMap.of("t2", ts1009)));
    runTest(extend(ImmutableMap.of("t1", ts1010), ImmutableMap.of("t2", ts0910)));
    runTest(extend(ImmutableMap.of("t1", ts1010), ImmutableMap.of("t2", ts1010)));
    runTest(extend(ImmutableMap.of("t1", ts1009), ImmutableMap.of("t2", ts1010)));
    runTest(extend(ImmutableMap.of("t1", ts0910), ImmutableMap.of("t2", ts1010)));

    source = "t1 > t2";
    runTest(extend(ImmutableMap.of("t1", ts1010), ImmutableMap.of("t2", ts1009)));
    runTest(extend(ImmutableMap.of("t1", ts1010), ImmutableMap.of("t2", ts0910)));
    runTest(extend(ImmutableMap.of("t1", ts1010), ImmutableMap.of("t2", ts1010)));
    runTest(extend(ImmutableMap.of("t1", ts1009), ImmutableMap.of("t2", ts1010)));
    runTest(extend(ImmutableMap.of("t1", ts0910), ImmutableMap.of("t2", ts1010)));

    source = "t1 >= t2";
    runTest(extend(ImmutableMap.of("t1", ts1010), ImmutableMap.of("t2", ts1009)));
    runTest(extend(ImmutableMap.of("t1", ts1010), ImmutableMap.of("t2", ts0910)));
    runTest(extend(ImmutableMap.of("t1", ts1010), ImmutableMap.of("t2", ts1010)));
    runTest(extend(ImmutableMap.of("t1", ts1009), ImmutableMap.of("t2", ts1010)));
    runTest(extend(ImmutableMap.of("t1", ts0910), ImmutableMap.of("t2", ts1010)));
  }

  @Test
  public void packUnpackAny() {
    // The use of long values results in the incorrect type being serialized for a uint value.
    if (!BASE_CEL_OPTIONS.enableUnsignedLongs()) {
      skipBaselineVerification();
      return;
    }
    setContainer(CelContainer.ofName(TestAllTypes.getDescriptor().getFile().getPackage()));
    declareVariable("any", SimpleType.ANY);
    declareVariable("d", SimpleType.DURATION);
    declareVariable(
        "message", StructTypeReference.create(TestAllTypes.getDescriptor().getFullName()));
    declareVariable("list", ListType.create(SimpleType.DYN));
    com.google.protobuf.Duration duration = ProtoTimeUtils.toProtoDuration(Duration.ofSeconds(100));
    Any any = Any.pack(duration);
    TestAllTypes message = TestAllTypes.newBuilder().setSingleAny(any).build();

    // unpack any
    source = "any == d";
    runTest(extend(ImmutableMap.of("any", any), ImmutableMap.of("d", duration)));
    source = "any == message.single_any";
    runTest(extend(ImmutableMap.of("any", any), ImmutableMap.of("message", message)));
    source = "d == message.single_any";
    runTest(extend(ImmutableMap.of("d", duration), ImmutableMap.of("message", message)));
    source = "any.single_int64 == 1";
    runTest(ImmutableMap.of("any", TestAllTypes.newBuilder().setSingleInt64(1).build()));
    source = "any == 1";
    runTest(ImmutableMap.of("any", Any.pack(Int64Value.of(1))));
    source = "list[0] == message";
    runTest(ImmutableMap.of("list", ImmutableList.of(Any.pack(message)), "message", message));

    // pack any
    source = "TestAllTypes{single_any: d}";
    runTest(ImmutableMap.of("d", duration));
    source = "TestAllTypes{single_any: message.single_int64}";
    runTest(ImmutableMap.of("message", TestAllTypes.newBuilder().setSingleInt64(-1).build()));
    source = "TestAllTypes{single_any: message.single_uint64}";
    runTest(ImmutableMap.of("message", TestAllTypes.newBuilder().setSingleUint64(1).build()));
    source = "TestAllTypes{single_any: 1.0}";
    runTest();
    source = "TestAllTypes{single_any: true}";
    runTest();
    source = "TestAllTypes{single_any: \"happy\"}";
    runTest();
    source = "TestAllTypes{single_any: message.single_bytes}";
    runTest(
        ImmutableMap.of(
            "message",
            TestAllTypes.newBuilder().setSingleBytes(ByteString.copyFromUtf8("happy")).build()));
  }

  @Test
  public void nestedEnums() {
    TestAllTypes nestedEnum = TestAllTypes.newBuilder().setSingleNestedEnum(NestedEnum.BAR).build();
    declareVariable("x", StructTypeReference.create(TestAllTypes.getDescriptor().getFullName()));
    setContainer(CelContainer.ofName(TestAllTypes.getDescriptor().getFile().getPackage()));
    source = "x.single_nested_enum == TestAllTypes.NestedEnum.BAR";
    runTest(ImmutableMap.of("x", nestedEnum));

    declareVariable("single_nested_enum", SimpleType.INT);
    source = "single_nested_enum == TestAllTypes.NestedEnum.BAR";
    runTest(ImmutableMap.of("single_nested_enum", nestedEnum.getSingleNestedEnumValue()));

    source =
        "TestAllTypes{single_nested_enum : TestAllTypes.NestedEnum.BAR}.single_nested_enum == 1";
    runTest();
  }

  @Test
  public void globalEnums() {
    declareVariable("x", SimpleType.INT);
    source = "x == dev.cel.testing.testdata.proto3.StandaloneGlobalEnum.SGAR";
    runTest(ImmutableMap.of("x", StandaloneGlobalEnum.SGAR.getNumber()));
  }

  @Test
  public void lists() throws Exception {
    declareVariable("x", StructTypeReference.create(TestAllTypes.getDescriptor().getFullName()));
    declareVariable("y", SimpleType.INT);
    setContainer(CelContainer.ofName(TestAllTypes.getDescriptor().getFile().getPackage()));
    source = "([1, 2, 3] + x.repeated_int32)[3] == 4";
    runTest(ImmutableMap.of("x", TestAllTypes.newBuilder().addRepeatedInt32(4).build()));

    source = "!(y in [1, 2, 3]) && y in [4, 5, 6]";
    runTest(ImmutableMap.of("y", 4L));

    source = "TestAllTypes{repeated_int32: [1,2]}.repeated_int32[1] == 2";
    runTest();

    source = "1 in TestAllTypes{repeated_int32: [1,2]}.repeated_int32";
    runTest();

    source = "!(4 in [1, 2, 3]) && 1 in [1, 2, 3]";
    runTest();

    declareVariable("list", ListType.create(SimpleType.INT));

    source = "!(4 in list) && 1 in list";
    runTest(ImmutableMap.of("list", ImmutableList.of(1L, 2L, 3L)));

    source = "!(y in list)";
    runTest(ImmutableMap.of("y", 4L, "list", ImmutableList.of(1L, 2L, 3L)));

    source = "y in list";
    runTest(ImmutableMap.of("y", 1L, "list", ImmutableList.of(1L, 2L, 3L)));
  }

  @Test
  public void lists_error() {
    declareVariable("y", SimpleType.INT);
    declareVariable("list", ListType.create(SimpleType.INT));

    source = "list[3]";
    runTest(ImmutableMap.of("y", 1L, "list", ImmutableList.of(1L, 2L, 3L)));
  }

  @Test
  public void maps() throws Exception {
    declareVariable("x", StructTypeReference.create(TestAllTypes.getDescriptor().getFullName()));
    setContainer(CelContainer.ofName(TestAllTypes.getDescriptor().getFile().getPackage()));
    source = "{1: 2, 3: 4}[3] == 4";
    runTest();

    // Constant key in constant map.
    source = "3 in {1: 2, 3: 4} && !(4 in {1: 2, 3: 4})";
    runTest();

    source = "x.map_int32_int64[22] == 23";
    runTest(ImmutableMap.of("x", TestAllTypes.newBuilder().putMapInt32Int64(22, 23).build()));

    source = "TestAllTypes{map_int32_int64: {21: 22, 22: 23}}.map_int32_int64[22] == 23";
    runTest();

    source =
        "TestAllTypes{oneof_type: NestedTestAllTypes{payload: x}}"
            + ".oneof_type.payload.map_int32_int64[22] == 23";
    runTest(ImmutableMap.of("x", TestAllTypes.newBuilder().putMapInt32Int64(22, 23).build()));

    declareVariable("y", SimpleType.INT);
    declareVariable("map", MapType.create(SimpleType.INT, SimpleType.INT));

    // Constant key in variable map.
    source = "!(4 in map) && 1 in map";
    runTest(ImmutableMap.of("map", ImmutableMap.of(1L, 4L, 2L, 3L, 3L, 2L)));

    // Variable key in constant map.
    source = "!(y in {1: 4, 2: 3, 3: 2}) && y in {5: 3, 4: 2, 3: 3}";
    runTest(ImmutableMap.of("y", 4L));

    // Variable key in variable map.
    source = "!(y in map) && (y + 3) in map";
    runTest(ImmutableMap.of("y", 1L, "map", ImmutableMap.of(4L, 1L, 5L, 2L, 6L, 3L)));

    // Message value in map
    source = "TestAllTypes{map_int64_nested_type:{42:NestedTestAllTypes{payload:TestAllTypes{}}}}";
    runTest();

    // Repeated key - constant
    source = "{true: 1, false: 2, true: 3}[true]";
    runTest();

    // Repeated key - expressions
    declareVariable("b", SimpleType.BOOL);
    source = "{b: 1, !b: 2, b: 3}[true]";
    runTest(ImmutableMap.of("b", true));
  }

  @Test
  public void comprehension() throws Exception {
    source = "[0, 1, 2].map(x, x > 0, x + 1) == [2, 3]";
    runTest();

    source = "[0, 1, 2].exists(x, x > 0)";
    runTest();

    source = "[0, 1, 2].exists(x, x > 2)";
    runTest();

    declareVariable("com.x", SimpleType.INT);
    setContainer(CelContainer.ofName("com"));
    source = "[0].exists(x, x == 0)";
    runTest(ImmutableMap.of("com.x", 1));

    clearAllDeclarations();
    declareVariable("cel.example.y", SimpleType.INT);
    setContainer(CelContainer.ofName("cel.example"));
    source = "[{'z': 0}].exists(y, y.z == 0)";
    runTest(ImmutableMap.of("cel.example.y", ImmutableMap.of("z", 1)));

    clearAllDeclarations();
    declareVariable("y.z", SimpleType.INT);
    setContainer(CelContainer.ofName("y"));
    source = "[{'z': 0}].exists(y, y.z == 0 && .y.z == 1)";
    runTest(ImmutableMap.of("y.z", 1));

    clearAllDeclarations();
    declareVariable("x", SimpleType.INT);
    source = "[0].exists(x, x == 0 && .x == 1)";
    runTest(ImmutableMap.of("x", 1));

    source = "[0].exists(x, [x+1].exists(x, x == .x))";
    runTest(ImmutableMap.of("x", 1));
  }

  @Test
  public void abstractType() {
    CelType typeParam = TypeParamType.create("T");
    CelType abstractType = OpaqueType.create("vector", typeParam);

    // Declare a function to create a vector.
    declareFunction(
        "vector",
        globalOverload("vector", ImmutableList.of(ListType.create(typeParam)), abstractType));
    // Declare a function to access element of a vector.
    declareFunction(
        "at", memberOverload("at", ImmutableList.of(abstractType, SimpleType.INT), typeParam));
    // Add function bindings for above
    addFunctionBinding(
        CelFunctionBinding.from(
            "vector",
            ImmutableList.of(List.class),
            (Object[] args) -> {
              List<?> list = (List<?>) args[0];
              return list.toArray(new Object[0]);
            }),
        CelFunctionBinding.from(
            "at",
            ImmutableList.of(Object[].class, Long.class),
            (Object[] args) -> {
              Object[] array = (Object[]) args[0];
              return array[(int) (long) args[1]];
            }));

    source = "vector([1,2,3]).at(1) == 2";
    runTest();

    source = "vector([1,2,3]).at(1) + vector([7]).at(0)";
    runTest();
  }

  @Test
  public void namespacedFunctions() {
    declareFunction(
        "ns.func",
        globalOverload("ns_func_overload", ImmutableList.of(SimpleType.STRING), SimpleType.INT));
    declareFunction(
        "member",
        memberOverload(
            "ns_member_overload",
            ImmutableList.of(SimpleType.INT, SimpleType.INT),
            SimpleType.INT));
    addFunctionBinding(
        CelFunctionBinding.fromOverloads(
            "ns.func",
            CelFunctionBinding.from("ns_func_overload", String.class, s -> (long) s.length())));
    addFunctionBinding(
        CelFunctionBinding.fromOverloads(
            "member",
            CelFunctionBinding.from("ns_member_overload", Long.class, Long.class, Long::sum)));

    source = "ns.func('hello')";
    runTest();

    source = "ns.func('hello').member(ns.func('test'))";
    runTest();

    source = "{ns.func('test'): 2}";
    runTest();

    source = "{2: ns.func('test')}";
    runTest();

    source = "[ns.func('test'), 2]";
    runTest();

    source = "[ns.func('test')].map(x, x * 2)";
    runTest();

    source = "[1, 2].map(x, x * ns.func('test'))";
    runTest();

    setContainer(CelContainer.ofName("ns"));
    // Call with the container set as the function's namespace
    source = "ns.func('hello')";
    runTest();

    source = "func('hello')";
    runTest();

    source = "func('hello').member(func('test'))";
    runTest();
  }

  @Test
  public void namespacedVariables() {
    setContainer(CelContainer.ofName("ns"));
    declareVariable("ns.x", SimpleType.INT);
    source = "x";
    runTest(ImmutableMap.of("ns.x", 2));

    setContainer(CelContainer.ofName("dev.cel.testing.testdata.proto3"));
    CelType messageType = StructTypeReference.create("cel.expr.conformance.proto3.TestAllTypes");
    declareVariable("dev.cel.testing.testdata.proto3.msgVar", messageType);
    source = "msgVar.single_int32";
    runTest(
        ImmutableMap.of(
            "dev.cel.testing.testdata.proto3.msgVar",
            TestAllTypes.newBuilder().setSingleInt32(5).build()));
  }

  @Test
  public void durationFunctions() {
    declareVariable("d1", SimpleType.DURATION);
    long totalSeconds = 25 * 3600 + 59 * 60 + 1;
    long nanos = 11000000;
    Duration d1 = Duration.ofSeconds(totalSeconds, nanos);
    Duration d2 = Duration.ofSeconds(-totalSeconds, -nanos);

    setContainer(CelContainer.ofName(Type.getDescriptor().getFile().getPackage()));

    source = "d1.getHours()";
    runTest(ImmutableMap.of("d1", d1));
    runTest(ImmutableMap.of("d1", d2));

    source = "d1.getMinutes()";
    runTest(ImmutableMap.of("d1", d1));
    runTest(ImmutableMap.of("d1", d2));

    source = "d1.getSeconds()";
    runTest(ImmutableMap.of("d1", d1));
    runTest(ImmutableMap.of("d1", d2));

    source = "d1.getMilliseconds()";
    runTest(ImmutableMap.of("d1", d1));
    runTest(ImmutableMap.of("d1", d2));

    declareVariable("val", SimpleType.INT);
    source = "d1.getHours() < val";
    runTest(extend(ImmutableMap.of("d1", d1), ImmutableMap.of("val", 30L)));
    source = "d1.getMinutes() > val";
    runTest(extend(ImmutableMap.of("d1", d1), ImmutableMap.of("val", 30L)));
    source = "d1.getSeconds() > val";
    runTest(extend(ImmutableMap.of("d1", d1), ImmutableMap.of("val", 30L)));
    source = "d1.getMilliseconds() < val";
    runTest(extend(ImmutableMap.of("d1", d1), ImmutableMap.of("val", 30L)));
  }

  @Test
  public void timestampFunctions() {
    declareVariable("ts1", SimpleType.TIMESTAMP);
    setContainer(CelContainer.ofName(Type.getDescriptor().getFile().getPackage()));
    Instant ts1 = Instant.ofEpochSecond(1, 11000000);
    Instant ts2 = Instant.ofEpochSecond(-1, 0);

    source = "ts1.getFullYear(\"America/Los_Angeles\")";
    runTest(ImmutableMap.of("ts1", ts1));
    source = "ts1.getFullYear()";
    runTest(ImmutableMap.of("ts1", ts1));
    runTest(ImmutableMap.of("ts1", ts2));
    source = "ts1.getFullYear(\"Indian/Cocos\")";
    runTest(ImmutableMap.of("ts1", ts1));
    source = "ts1.getFullYear(\"2:00\")";
    runTest(ImmutableMap.of("ts1", ts1));

    source = "ts1.getMonth(\"America/Los_Angeles\")";
    runTest(ImmutableMap.of("ts1", ts1));
    source = "ts1.getMonth()";
    runTest(ImmutableMap.of("ts1", ts1));
    runTest(ImmutableMap.of("ts1", ts2));
    source = "ts1.getMonth(\"Indian/Cocos\")";
    runTest(ImmutableMap.of("ts1", ts1));
    source = "ts1.getMonth(\"-8:15\")";
    runTest(ImmutableMap.of("ts1", ts1));

    source = "ts1.getDayOfYear(\"America/Los_Angeles\")";
    runTest(ImmutableMap.of("ts1", ts1));
    source = "ts1.getDayOfYear()";
    runTest(ImmutableMap.of("ts1", ts1));
    runTest(ImmutableMap.of("ts1", ts2));
    source = "ts1.getDayOfYear(\"Indian/Cocos\")";
    runTest(ImmutableMap.of("ts1", ts1));
    source = "ts1.getDayOfYear(\"-9:00\")";
    runTest(ImmutableMap.of("ts1", ts1));

    source = "ts1.getDayOfMonth(\"America/Los_Angeles\")";
    runTest(ImmutableMap.of("ts1", ts1));
    source = "ts1.getDayOfMonth()";
    runTest(ImmutableMap.of("ts1", ts1));
    runTest(ImmutableMap.of("ts1", ts2));
    source = "ts1.getDayOfMonth(\"Indian/Cocos\")";
    runTest(ImmutableMap.of("ts1", ts1));
    source = "ts1.getDayOfMonth(\"8:00\")";
    runTest(ImmutableMap.of("ts1", ts1));

    source = "ts1.getDate(\"America/Los_Angeles\")";
    runTest(ImmutableMap.of("ts1", ts1));
    source = "ts1.getDate()";
    runTest(ImmutableMap.of("ts1", ts1));
    runTest(ImmutableMap.of("ts1", ts2));
    source = "ts1.getDate(\"Indian/Cocos\")";
    runTest(ImmutableMap.of("ts1", ts1));
    source = "ts1.getDate(\"9:30\")";
    runTest(ImmutableMap.of("ts1", ts1));

    Instant tsSunday = Instant.ofEpochSecond(3 * 24 * 3600);
    source = "ts1.getDayOfWeek(\"America/Los_Angeles\")";
    runTest(ImmutableMap.of("ts1", tsSunday));
    source = "ts1.getDayOfWeek()";
    runTest(ImmutableMap.of("ts1", tsSunday));
    runTest(ImmutableMap.of("ts1", ts2));
    source = "ts1.getDayOfWeek(\"Indian/Cocos\")";
    runTest(ImmutableMap.of("ts1", tsSunday));
    source = "ts1.getDayOfWeek(\"-9:30\")";
    runTest(ImmutableMap.of("ts1", tsSunday));

    source = "ts1.getHours(\"America/Los_Angeles\")";
    runTest(ImmutableMap.of("ts1", ts1));
    source = "ts1.getHours()";
    runTest(ImmutableMap.of("ts1", ts1));
    runTest(ImmutableMap.of("ts1", ts2));
    source = "ts1.getHours(\"Indian/Cocos\")";
    runTest(ImmutableMap.of("ts1", ts1));
    source = "ts1.getHours(\"6:30\")";
    runTest(ImmutableMap.of("ts1", ts1));

    source = "ts1.getMinutes(\"America/Los_Angeles\")";
    runTest(ImmutableMap.of("ts1", ts1));
    source = "ts1.getMinutes()";
    runTest(ImmutableMap.of("ts1", ts1));
    runTest(ImmutableMap.of("ts1", ts2));
    source = "ts1.getMinutes(\"Indian/Cocos\")";
    runTest(ImmutableMap.of("ts1", ts1));
    source = "ts1.getMinutes(\"-8:00\")";
    runTest(ImmutableMap.of("ts1", ts1));

    source = "ts1.getSeconds(\"America/Los_Angeles\")";
    runTest(ImmutableMap.of("ts1", ts1));
    source = "ts1.getSeconds()";
    runTest(ImmutableMap.of("ts1", ts1));
    runTest(ImmutableMap.of("ts1", ts2));
    source = "ts1.getSeconds(\"Indian/Cocos\")";
    runTest(ImmutableMap.of("ts1", ts1));
    source = "ts1.getSeconds(\"-8:00\")";
    runTest(ImmutableMap.of("ts1", ts1));

    source = "ts1.getMilliseconds(\"America/Los_Angeles\")";
    runTest(ImmutableMap.of("ts1", ts1));
    source = "ts1.getMilliseconds()";
    runTest(ImmutableMap.of("ts1", ts1));
    source = "ts1.getMilliseconds(\"Indian/Cocos\")";
    runTest(ImmutableMap.of("ts1", ts1));
    source = "ts1.getMilliseconds(\"-8:00\")";
    runTest(ImmutableMap.of("ts1", ts1));

    declareVariable("val", SimpleType.INT);
    source = "ts1.getFullYear() < val";
    runTest(extend(ImmutableMap.of("ts1", ts1), ImmutableMap.of("val", 2013L)));
    source = "ts1.getMonth() < val";
    runTest(extend(ImmutableMap.of("ts1", ts1), ImmutableMap.of("val", 12L)));
    source = "ts1.getDayOfYear() < val";
    runTest(extend(ImmutableMap.of("ts1", ts1), ImmutableMap.of("val", 13L)));
    source = "ts1.getDayOfMonth() < val";
    runTest(extend(ImmutableMap.of("ts1", ts1), ImmutableMap.of("val", 10L)));
    source = "ts1.getDate() < val";
    runTest(extend(ImmutableMap.of("ts1", ts1), ImmutableMap.of("val", 15L)));
    source = "ts1.getDayOfWeek() < val";
    runTest(extend(ImmutableMap.of("ts1", ts1), ImmutableMap.of("val", 15L)));
    source = "ts1.getHours() < val";
    runTest(extend(ImmutableMap.of("ts1", ts1), ImmutableMap.of("val", 15L)));
    source = "ts1.getMinutes() < val";
    runTest(extend(ImmutableMap.of("ts1", ts1), ImmutableMap.of("val", 15L)));
    source = "ts1.getSeconds() < val";
    runTest(extend(ImmutableMap.of("ts1", ts1), ImmutableMap.of("val", 15L)));
    source = "ts1.getMilliseconds() < val";
    runTest(extend(ImmutableMap.of("ts1", ts1), ImmutableMap.of("val", 15L)));
  }

  @Test
  public void unknownField() {
    setContainer(CelContainer.ofName(TestAllTypes.getDescriptor().getFile().getPackage()));
    declareVariable("x", StructTypeReference.create(TestAllTypes.getDescriptor().getFullName()));

    // Unknown field is accessed.
    source = "x.single_int32";
    runTest();

    source = "x.map_int32_int64[22]";
    runTest();

    source = "x.repeated_nested_message[1]";
    runTest();

    // Function call for an unknown field.
    source = "x.single_timestamp.getSeconds()";
    runTest();

    // Unknown field in a nested message
    source = "x.single_nested_message.bb";
    runTest();

    // Unknown field access in a map.
    source = "{1: x.single_int32}";
    runTest();

    // Unknown field access in a list.
    source = "[1, x.single_int32]";
    runTest();
  }

  @Test
  public void unknownResultSet() {
    setContainer(CelContainer.ofName(TestAllTypes.getDescriptor().getFile().getPackage()));
    declareVariable("x", StructTypeReference.create(TestAllTypes.getDescriptor().getFullName()));
    TestAllTypes message =
        TestAllTypes.newBuilder()
            .setSingleString("test")
            .setSingleTimestamp(Timestamp.newBuilder().setSeconds(15))
            .build();

    // unknown && true ==> unknown
    source = "x.single_int32 == 1 && true";
    runTest();

    // unknown && false ==> false
    source = "x.single_int32 == 1 && false";
    runTest();

    // unknown && Unknown ==> UnknownSet
    source = "x.single_int32 == 1 && x.single_int64 == 1";
    runTest();

    // unknown && error ==> unknown
    source = "x.single_int32 == 1 && x.single_timestamp <= timestamp(\"bad timestamp string\")";
    runTest();

    // true && unknown ==> unknown
    source = "true && x.single_int32 == 1";
    runTest();

    // false && unknown ==> false
    source = "false && x.single_int32 == 1";
    runTest();

    // error && unknown ==> unknown
    source = "x.single_timestamp <= timestamp(\"bad timestamp string\") && x.single_int32 == 1";
    runTest();

    // error && error ==> error
    source =
        "x.single_timestamp <= timestamp(\"bad timestamp string\") "
            + "&& x.single_timestamp > timestamp(\"another bad timestamp string\")";
    runTest();

    // unknown || true ==> true
    source = "x.single_int32 == 1 || x.single_string == \"test\"";
    runTest();

    // unknown || false ==> unknown
    source = "x.single_int32 == 1 || x.single_string != \"test\"";
    runTest();

    // unknown || unknown ==> UnknownSet
    source = "x.single_int32 == 1 || x.single_int64 == 1";
    runTest();

    // unknown || error ==> unknown
    source = "x.single_int32 == 1 || x.single_timestamp <= timestamp(\"bad timestamp string\")";
    runTest();

    // true || unknown ==> true
    source = "true || x.single_int32 == 1";
    runTest();

    // false || unknown ==> unknown
    source = "false || x.single_int32 == 1";
    runTest();

    // error || unknown ==> unknown
    source = "x.single_timestamp <= timestamp(\"bad timestamp string\") || x.single_int32 == 1";
    runTest();

    // error || error ==> error
    source =
        "x.single_timestamp <= timestamp(\"bad timestamp string\") "
            + "|| x.single_timestamp > timestamp(\"another bad timestamp string\")";
    runTest();

    // dispatch test
    declareFunction(
        "f", memberOverload("f", Arrays.asList(SimpleType.INT, SimpleType.INT), SimpleType.BOOL));
    addFunctionBinding(CelFunctionBinding.from("f", Integer.class, Integer.class, Objects::equals));

    // dispatch: unknown.f(1)  ==> unknown
    source = "x.single_int32.f(1)";
    runTest();

    // dispatch: 1.f(unknown)  ==> unknown
    source = "1.f(x.single_int32)";
    runTest();

    // dispatch: unknown.f(unknown)  ==> unknownSet
    source = "x.single_int64.f(x.single_int32)";
    runTest();

    // ident is null(x is unbound) ==> unknown
    source = "x";
    runTest(ImmutableMap.of("y", message));

    // ident is unknown ==> unknown
    source = "x";
    CelUnknownSet unknownMessage = CelUnknownSet.create(1L);
    runTest(ImmutableMap.of("x", unknownMessage));

    // comprehension test
    // iteRange is unknown => unknown
    source = "x.map_int32_int64.map(x, x > 0, x + 1)";
    runTest();

    // exists, loop condition encounters unknown => skip unknown and check other element
    source = "[0, 2, 4].exists(z, z == 2 || z == x.single_int32)";
    runTest();

    // exists, loop condition encounters unknown => skip unknown and check other element, no dupe id
    // in result
    source = "[0, 2, 4].exists(z, z == x.single_int32)";
    runTest();

    // exists_one, loop condition encounters unknown => collect all unknowns
    source =
        "[0, 2, 4].exists_one(z, z == 0 || (z == 2 && z == x.single_int32) "
            + "|| (z == 4 && z == x.single_int64))";
    runTest();

    // all, loop condition encounters unknown => skip unknown and check other element
    source = "[0, 2].all(z, z == 2 || z == x.single_int32)";
    runTest();

    // filter, loop condition encounters unknown => skip unknown and check other element
    source =
        "[0, 2, 4].filter(z, z == 0 || (z == 2 && z == x.single_int32) "
            + "|| (z == 4 && z == x.single_int64))";
    runTest();

    // map, loop condition encounters unknown => skip unknown and check other element
    source =
        "[0, 2, 4].map(z, z == 0 || (z == 2 && z == x.single_int32) "
            + "|| (z == 4 && z == x.single_int64))";
    runTest();

    // conditional test
    // unknown ? 1 : 2 ==> unknown
    source = "x.single_int32 == 1 ? 1 : 2";
    runTest();

    // true ? unknown : 2  ==> unknown
    source = "true ? x.single_int32 : 2";
    runTest();

    // true ? 1 : unknown  ==> 1
    source = "true ? 1 : x.single_int32";
    runTest();

    // false ? unknown : 2 ==> 2
    source = "false ? x.single_int32 : 2";
    runTest();

    // false ? 1 : unknown ==> unknown
    source = "false ? 1 : x.single_int32";
    runTest();

    // unknown condition ? unknown : unknown ==> unknown condition
    source = "x.single_int64 == 1 ? x.single_int32 : x.single_int32";
    runTest();

    // map with unknown key => unknown
    source = "{x.single_int32: 2, 3: 4}";
    runTest();

    // map with unknown value => unknown
    source = "{1: x.single_int32, 3: 4}";
    runTest();

    // map with unknown key and value => unknownSet
    source = "{1: x.single_int32, x.single_int64: 4}";
    runTest();

    // list with unknown => unknown
    source = "[1, x.single_int32, 3, 4]";
    runTest();

    // list with multiple unknowns => unknownSet
    source = "[1, x.single_int32, x.single_int64, 4]";
    runTest();

    // message with unknown => unknown
    source = "TestAllTypes{single_int32: x.single_int32}.single_int32 == 2";
    runTest();

    // message with multiple unknowns => unknownSet
    source = "TestAllTypes{single_int32: x.single_int32, single_int64: x.single_int64}";
    runTest();

    // type(unknown) -> unknown
    source = "type(x.single_int32)";
    runTest();

    // type(error) -> error
    source = "type(1 / 0 > 2)";
    runTest();
  }

  @Test
  public void timeConversions() {
    setContainer(CelContainer.ofName(Type.getDescriptor().getFile().getPackage()));
    declareVariable("t1", SimpleType.TIMESTAMP);

    source = "timestamp(\"1972-01-01T10:00:20.021-05:00\")";
    runTest();

    source = "timestamp(123)";
    runTest();

    source = "duration(\"15.11s\")";
    runTest();

    source = "int(t1) == 100";
    runTest(ImmutableMap.of("t1", Instant.ofEpochSecond(100)));

    source = "duration(\"1h2m3.4s\")";
    runTest();

    source = "duration(duration('15.0s'))"; // Identity
    runTest();

    source = "timestamp(timestamp(123))"; // Identity
    runTest();
  }

  @Test
  public void timeConversions_error() {
    source = "duration('inf')";
    runTest();
  }

  @Test
  public void sizeTests() {
    setContainer(CelContainer.ofName(Type.getDescriptor().getFile().getPackage()));
    declareVariable("str", SimpleType.STRING);
    declareVariable("b", SimpleType.BYTES);

    source = "size(b) == 5 && b.size() == 5";
    runTest(ImmutableMap.of("b", CelByteString.copyFromUtf8("happy")));

    source = "size(str) == 5 && str.size() == 5";
    runTest(ImmutableMap.of("str", "happy"));
    runTest(ImmutableMap.of("str", "happ\uDBFF\uDFFC"));

    source = "size({1:14, 2:15}) == 2 && {1:14, 2:15}.size() == 2";
    runTest();

    source = "size([1,2,3]) == 3 && [1,2,3].size() == 3";
    runTest();
  }

  @Test
  public void nonstrictQuantifierTests() {
    // Plain tests.  Everything is constant.
    source = "[0, 2, 4].exists(x, 4/x == 2 && 4/(4-x) == 2)";
    runTest();

    source = "![0, 2, 4].all(x, 4/x != 2 && 4/(4-x) != 2)";
    runTest();

    declareVariable("four", SimpleType.INT);

    // Condition is dynamic.
    source = "[0, 2, 4].exists(x, four/x == 2 && four/(four-x) == 2)";
    runTest(ImmutableMap.of("four", 4L));

    source = "![0, 2, 4].all(x, four/x != 2 && four/(four-x) != 2)";
    runTest(ImmutableMap.of("four", 4L));

    // Both range and condition are dynamic.
    source = "[0, 2, four].exists(x, four/x == 2 && four/(four-x) == 2)";
    runTest(ImmutableMap.of("four", 4L));

    source = "![0, 2, four].all(x, four/x != 2 && four/(four-x) != 2)";
    runTest(ImmutableMap.of("four", 4L));

    // Unknown argument
    source = "[0, 1].exists(x, x > four || true)";
    runTest();
  }

  @Test
  public void regexpMatchingTests() {
    // Constant everything.
    source = "matches(\"alpha\", \"^al.*\") == true";
    runTest();

    source = "matches(\"alpha\", \"^.al.*\") == false";
    runTest();

    source = "matches(\"alpha\", \".*ha$\") == true";
    runTest();

    source = "matches(\"alpha\", \"^.*ha.$\") == false";
    runTest();

    source = "matches(\"alpha\", \"\") == true";
    runTest();

    source = "matches(\"alpha\", \"ph\") == true";
    runTest();

    source = "matches(\"alpha\", \"^ph\") == false";
    runTest();

    source = "matches(\"alpha\", \"ph$\") == false";
    runTest();

    // Constant everything, receiver-style.
    source = "\"alpha\".matches(\"^al.*\") == true";
    runTest();

    source = "\"alpha\".matches(\"^.al.*\") == false";
    runTest();

    source = "\"alpha\".matches(\".*ha$\") == true";
    runTest();

    source = "\"alpha\".matches(\".*ha.$\") == false";
    runTest();

    source = "\"alpha\".matches(\"\") == true";
    runTest();

    source = "\"alpha\".matches(\"ph\") == true";
    runTest();

    source = "\"alpha\".matches(\"^ph\") == false";
    runTest();

    source = "\"alpha\".matches(\"ph$\") == false";
    runTest();

    // Constant string.
    declareVariable("regexp", SimpleType.STRING);

    source = "matches(\"alpha\", regexp) == true";
    runTest(ImmutableMap.of("regexp", "^al.*"));

    source = "matches(\"alpha\", regexp) == false";
    runTest(ImmutableMap.of("regexp", "^.al.*"));

    source = "matches(\"alpha\", regexp) == true";
    runTest(ImmutableMap.of("regexp", ".*ha$"));

    source = "matches(\"alpha\", regexp) == false";
    runTest(ImmutableMap.of("regexp", ".*ha.$"));

    // Constant string, receiver-style.
    source = "\"alpha\".matches(regexp) == true";
    runTest(ImmutableMap.of("regexp", "^al.*"));

    source = "\"alpha\".matches(regexp) == false";
    runTest(ImmutableMap.of("regexp", "^.al.*"));

    source = "\"alpha\".matches(regexp) == true";
    runTest(ImmutableMap.of("regexp", ".*ha$"));

    source = "\"alpha\".matches(regexp) == false";
    runTest(ImmutableMap.of("regexp", ".*ha.$"));

    // Constant regexp.
    declareVariable("s", SimpleType.STRING);

    source = "matches(s, \"^al.*\") == true";
    runTest(ImmutableMap.of("s", "alpha"));

    source = "matches(s, \"^.al.*\") == false";
    runTest(ImmutableMap.of("s", "alpha"));

    source = "matches(s, \".*ha$\") == true";
    runTest(ImmutableMap.of("s", "alpha"));

    source = "matches(s, \"^.*ha.$\") == false";
    runTest(ImmutableMap.of("s", "alpha"));

    // Constant regexp, receiver-style.
    source = "s.matches(\"^al.*\") == true";
    runTest(ImmutableMap.of("s", "alpha"));

    source = "s.matches(\"^.al.*\") == false";
    runTest(ImmutableMap.of("s", "alpha"));

    source = "s.matches(\".*ha$\") == true";
    runTest(ImmutableMap.of("s", "alpha"));

    source = "s.matches(\"^.*ha.$\") == false";
    runTest(ImmutableMap.of("s", "alpha"));

    // No constants.
    source = "matches(s, regexp) == true";
    runTest(ImmutableMap.of("s", "alpha", "regexp", "^al.*"));
    runTest(ImmutableMap.of("s", "alpha", "regexp", ".*ha$"));

    source = "matches(s, regexp) == false";
    runTest(ImmutableMap.of("s", "alpha", "regexp", "^.al.*"));
    runTest(ImmutableMap.of("s", "alpha", "regexp", ".*ha.$"));

    // No constants, receiver-style.
    source = "s.matches(regexp) == true";
    runTest(ImmutableMap.of("s", "alpha", "regexp", "^al.*"));
    runTest(ImmutableMap.of("s", "alpha", "regexp", ".*ha$"));

    source = "s.matches(regexp) == false";
    runTest(ImmutableMap.of("s", "alpha", "regexp", "^.al.*"));
    runTest(ImmutableMap.of("s", "alpha", "regexp", ".*ha.$"));
  }

  @Test
  public void regexpMatches_error() {
    source = "matches(\"alpha\", \"**\")";
    runTest();

    source = "\"alpha\".matches(\"**\")";
    runTest();
  }

  @Test
  public void int64Conversions() {
    source = "int('-1')"; // string converts to -1
    runTest();

    source = "int(2.1)"; // double converts to 2
    runTest();

    source = "int(42u)"; // converts to 42
    runTest();
  }

  @Test
  public void int64Conversions_error() {
    source = "int(18446744073709551615u)"; // 2^64-1 should error
    runTest();

    source = "int(1e99)"; // out of range should error
    runTest();
  }

  @Test
  public void uint64Conversions() {
    // The test case `uint(1e19)` succeeds with unsigned longs and fails with longs in a way that
    // cannot be easily tested.
    if (!BASE_CEL_OPTIONS.enableUnsignedLongs()) {
      skipBaselineVerification();
      return;
    }
    source = "uint('1')"; // string converts to 1u
    runTest();

    source = "uint(2.1)"; // double converts to 2u
    runTest();

    source = "uint(1e19)"; // valid uint but outside of int range
    runTest();

    source = "uint(42)"; // int converts to 42u
    runTest();

    source = "uint(1u)"; // identity
    runTest();

    source = "uint(dyn(1u))"; // identity, check dynamic dispatch
    runTest();
  }

  @Test
  public void uint64Conversions_error() {
    source = "uint(-1)"; // should error
    runTest();

    source = "uint(6.022e23)"; // outside uint range
    runTest();

    source = "uint('f1')"; // should error
    runTest();
  }

  @Test
  public void doubleConversions() {
    source = "double('1.1')"; // string converts to 1.1
    runTest();

    source = "double(2u)"; // uint converts to 2.0
    runTest();

    source = "double(-1)"; // int converts to -1.0
    runTest();

    source = "double(1.5)"; // Identity
    runTest();
  }

  @Test
  public void doubleConversions_error() {
    source = "double('bad')";
    runTest();
  }

  @Test
  public void stringConversions() {
    source = "string(1.1)"; // double converts to '1.1'
    runTest();

    source = "string(2u)"; // uint converts to '2'
    runTest();

    source = "string(-1)"; // int converts to '-1'
    runTest();

    source = "string(true)"; // bool converts to 'true'
    runTest();

    // Byte literals in Google SQL only take the leading byte of an escape character.
    // This means that to translate a byte literal to a UTF-8 encoded string, all bytes must be
    // encoded in the literal as they would be laid out in memory for UTF-8, hence the extra octal
    // escape to achieve parity with the bidi test below.
    source = "string(b'abc\\303\\203')";
    runTest(); // bytes convert to 'abcÃ'

    // Bi-di conversion for strings and bytes for 'abcÃ', note the difference between the string
    // and byte literal values.
    source = "string(bytes('abc\\303'))";
    runTest();

    source = "string(timestamp('2009-02-13T23:31:30Z'))";
    runTest();

    source = "string(duration('1000000s'))";
    runTest();

    source = "string('hello')"; // Identity
    runTest();
  }

  @Test
  public void stringConversions_error() throws Exception {
    source = "string(b'\\xff')";
    runTest();
  }

  @Test
  public void bytes() throws Exception {
    source =
        "b'a' < b'b' && b'a' <= b'b' && b'b' > b'a' && b'a' >= b'a' && b'a' == b'a' && b'a' !="
            + " b'b'";
    runTest();
  }

  @Test
  public void boolConversions() {
    source = "bool(true)";
    runTest(); // Identity

    source = "bool('true') && bool('TRUE') && bool('True') && bool('t') && bool('1')";
    runTest(); // result is true

    source = "bool('false') || bool('FALSE') || bool('False') || bool('f') || bool('0')";
    runTest(); // result is false
  }

  @Test
  public void boolConversions_error() {
    source = "bool('TrUe')";
    runTest();

    source = "bool('FaLsE')";
    runTest();
  }

  @Test
  public void bytesConversions() {
    source = "bytes('abc\\303')";
    runTest(); // string converts to abcÃ in bytes form.

    source = "bytes(bytes('abc\\303'))"; // Identity
    runTest();
  }

  @Test
  public void dynConversions() {
    source = "dyn(42)";
    runTest();

    source = "dyn({'a':1, 'b':2})";
    runTest();
  }

  @Test
  public void dyn_error() {
    source = "dyn('hello').invalid";
    runTest();

    source = "has(dyn('hello').invalid)";
    runTest();

    source = "dyn([]).invalid";
    runTest();

    source = "has(dyn([]).invalid)";
    runTest();
  }

  @Test
  public void jsonValueTypes() {
    setContainer(CelContainer.ofName(TestAllTypes.getDescriptor().getFile().getPackage()));
    declareVariable("x", StructTypeReference.create(TestAllTypes.getDescriptor().getFullName()));

    // JSON bool selection.
    TestAllTypes xBool =
        TestAllTypes.newBuilder().setSingleValue(Value.newBuilder().setBoolValue(true)).build();
    source = "x.single_value";
    runTest(ImmutableMap.of("x", xBool));

    // JSON number selection with int comparison.
    TestAllTypes xInt =
        TestAllTypes.newBuilder().setSingleValue(Value.newBuilder().setNumberValue(1)).build();
    source = "x.single_value == double(1)";
    runTest(ImmutableMap.of("x", xInt));

    // JSON number selection with float comparison.
    TestAllTypes xFloat =
        TestAllTypes.newBuilder().setSingleValue(Value.newBuilder().setNumberValue(1.1)).build();
    source = "x.single_value == 1.1";
    runTest(ImmutableMap.of("x", xFloat));

    // JSON null selection.
    TestAllTypes xNull =
        TestAllTypes.newBuilder()
            .setSingleValue(Value.newBuilder().setNullValue(NullValue.NULL_VALUE))
            .build();
    source = "x.single_value == null";
    runTest(ImmutableMap.of("x", xNull));

    // JSON string selection.
    TestAllTypes xString =
        TestAllTypes.newBuilder()
            .setSingleValue(Value.newBuilder().setStringValue("hello"))
            .build();
    source = "x.single_value == 'hello'";
    runTest(ImmutableMap.of("x", xString));

    // json manual construction
    source = "google.protobuf.Value{string_value: 'hello'} == 'hello'";
    runTest();

    source = "google.protobuf.Value{number_value: 1.1} == 1.1";
    runTest();

    source = "google.protobuf.Value{null_value: google.protobuf.NullValue.NULL_VALUE} == null";
    runTest();

    // NULL_VALUE is not the same as null.
    source = "TestAllTypes{null_value: google.protobuf.NullValue.NULL_VALUE}.null_value == 0";
    runTest();
    source =
        "TestAllTypes{null_value: google.protobuf.NullValue.NULL_VALUE}.null_value != dyn(null)";
    runTest();

    // JSON list equality.
    TestAllTypes xList =
        TestAllTypes.newBuilder()
            .setSingleValue(
                Value.newBuilder()
                    .setListValue(
                        ListValue.newBuilder()
                            .addValues(
                                Value.newBuilder()
                                    .setListValue(
                                        ListValue.newBuilder()
                                            .addValues(Value.newBuilder().setStringValue("hello"))))
                            .addValues(Value.newBuilder().setNumberValue(-1.1))))
            .build();
    source = "x.single_value[0] == [['hello'], -1.1][0]";
    runTest(ImmutableMap.of("x", xList));

    // JSON struct equality.
    TestAllTypes xStruct =
        TestAllTypes.newBuilder()
            .setSingleStruct(
                Struct.newBuilder()
                    .putFields(
                        "str",
                        Value.newBuilder()
                            .setListValue(
                                ListValue.newBuilder()
                                    .addValues(Value.newBuilder().setStringValue("hello")))
                            .build())
                    .putFields("num", Value.newBuilder().setNumberValue(-1.1).build()))
            .build();
    source = "x.single_struct.num == {'str': ['hello'], 'num': -1.1}['num']";
    runTest(ImmutableMap.of("x", xStruct));

    // Build a proto message using a dynamically constructed map and assign the map to a struct
    // value.
    source =
        "TestAllTypes{"
            + "single_struct: "
            + "TestAllTypes{single_value: {'str': ['hello']}}.single_value"
            + "}";
    runTest();

    // Ensure that types are being wrapped and unwrapped on function dispatch.
    declareFunction(
        "pair",
        globalOverload(
            "pair", ImmutableList.of(SimpleType.STRING, SimpleType.STRING), SimpleType.DYN));
    addFunctionBinding(
        CelFunctionBinding.from(
            "pair",
            ImmutableList.of(String.class, String.class),
            (Object[] args) -> {
              String key = (String) args[0];
              String val = (String) args[1];
              return Value.newBuilder()
                  .setStructValue(
                      Struct.newBuilder()
                          .putFields(key, Value.newBuilder().setStringValue(val).build()))
                  .build();
            }));
    source = "pair(x.single_struct.str[0], 'val')";
    runTest(ImmutableMap.of("x", xStruct));
  }

  @Test
  public void jsonConversions() {
    declareVariable("ts", SimpleType.TIMESTAMP);
    declareVariable("du", SimpleType.DURATION);
    source = "google.protobuf.Struct { fields: {'timestamp': ts, 'duration': du } }";
    runTest(
        ImmutableMap.of(
            "ts",
            ProtoTimeUtils.fromSecondsToTimestamp(100),
            "du",
            ProtoTimeUtils.toProtoDuration(Duration.ofMillis(200))));
  }

  @Test
  public void typeComparisons() {
    setContainer(CelContainer.ofName(TestAllTypes.getDescriptor().getFile().getPackage()));

    // Test numeric types.
    source =
        "type(1) == int && type(1u) == uint && "
            + "type(1u) != int && type(1) != uint && "
            + "type(uint(1.1)) == uint && "
            + "type(1.1) == double";
    runTest();

    // Test string and bytes types.
    source = "type('hello') == string && type(b'\277') == bytes";
    runTest();

    // Test list and map types.
    source = "type([1, 2, 3]) == list && type({'a': 1, 'b': 2}) == map";
    runTest();

    // Test bool types.
    source = "type(true) == bool && type(false) == bool";
    runTest();

    // Test well-known proto-based types.
    source = "type(duration('10s')) == google.protobuf.Duration";
    runTest();

    // Test external proto-based types with container resolution.
    source =
        "type(TestAllTypes{}) == TestAllTypes && "
            + "type(TestAllTypes{}) == proto3.TestAllTypes && "
            + "type(TestAllTypes{}) == .cel.expr.conformance.proto3.TestAllTypes && "
            + "type(proto3.TestAllTypes{}) == TestAllTypes && "
            + "type(proto3.TestAllTypes{}) == proto3.TestAllTypes && "
            + "type(proto3.TestAllTypes{}) == .cel.expr.conformance.proto3.TestAllTypes && "
            + "type(.cel.expr.conformance.proto3.TestAllTypes{}) == TestAllTypes && "
            + "type(.cel.expr.conformance.proto3.TestAllTypes{}) == proto3.TestAllTypes && "
            + "type(.cel.expr.conformance.proto3.TestAllTypes{}) == "
            + ".cel.expr.conformance.proto3.TestAllTypes";
    runTest();

    // Test whether a type name is recognized as a type.
    source = "type(TestAllTypes) == type";
    runTest();

    // Test whether the type resolution of a proto object is recognized as the message's type.
    source = "type(TestAllTypes{}) == TestAllTypes";
    runTest();

    // Test whether null resolves to null_type.
    source = "type(null) == null_type";
    runTest();

    // Test runtime resolution of types
    source =
        "type(duration) == google.protobuf.Duration && "
            + "type(timestamp) == google.protobuf.Timestamp";
    // Intentionally declare as dyns
    declareVariable("duration", SimpleType.DYN);
    declareVariable("timestamp", SimpleType.DYN);
    runTest(ImmutableMap.of("duration", java.time.Duration.ZERO, "timestamp", Instant.EPOCH));
  }

  @Test
  public void wrappers() throws Exception {
    TestAllTypes.Builder wrapperBindings =
        TestAllTypes.newBuilder()
            .setSingleBoolWrapper(BoolValue.of(true))
            .setSingleBytesWrapper(BytesValue.of(ByteString.copyFrom(new byte[] {'h', 'i'})))
            .setSingleDoubleWrapper(DoubleValue.of(-3.0))
            .setSingleFloatWrapper(FloatValue.of(1.5f))
            .setSingleInt32Wrapper(Int32Value.of(-12))
            .setSingleInt64Wrapper(Int64Value.of(-34))
            .setSingleStringWrapper(StringValue.of("hello"))
            .setSingleUint32Wrapper(UInt32Value.of(12))
            .setSingleUint64Wrapper(UInt64Value.of(34));

    declareVariable("x", StructTypeReference.create(TestAllTypes.getDescriptor().getFullName()));
    source =
        "x.single_bool_wrapper == true && "
            + "x.single_bytes_wrapper == b'hi' && "
            + "x.single_double_wrapper == -3.0 && "
            + "x.single_float_wrapper == 1.5 && "
            + "x.single_int32_wrapper == -12 && "
            + "x.single_int64_wrapper == -34 && "
            + "x.single_string_wrapper == 'hello' && "
            + "x.single_uint32_wrapper == 12u && "
            + "x.single_uint64_wrapper == 34u";
    runTest(ImmutableMap.of("x", wrapperBindings));

    source =
        "x.single_bool_wrapper == google.protobuf.BoolValue{} && "
            + "x.single_bytes_wrapper == google.protobuf.BytesValue{value: b'hi'} && "
            + "x.single_double_wrapper == google.protobuf.DoubleValue{value: -3.0} && "
            + "x.single_float_wrapper == google.protobuf.FloatValue{value: 1.5} && "
            + "x.single_int32_wrapper == google.protobuf.Int32Value{value: -12} && "
            + "x.single_int64_wrapper == google.protobuf.Int64Value{value: -34} && "
            + "x.single_string_wrapper == google.protobuf.StringValue{} && "
            + "x.single_uint32_wrapper == google.protobuf.UInt32Value{value: 12u} && "
            + "x.single_uint64_wrapper == google.protobuf.UInt64Value{value: 34u}";
    runTest(
        ImmutableMap.of(
            "x",
            wrapperBindings
                .setSingleBoolWrapper(BoolValue.getDefaultInstance())
                .setSingleStringWrapper(StringValue.getDefaultInstance())));

    source =
        "x.repeated_int32_wrapper == [1,2] && "
            + "x.repeated_int64_wrapper == [3] && "
            + "x.repeated_float_wrapper == [1.5, 2.5] && "
            + "x.repeated_double_wrapper == [3.5, 4.5] && "
            + "x.repeated_string_wrapper == ['foo', 'bar'] && "
            + "x.repeated_bool_wrapper == [true] && "
            + "x.repeated_uint32_wrapper == [1u, 2u] && "
            + "x.repeated_uint64_wrapper == []";

    runTest(
        ImmutableMap.of(
            "x",
            wrapperBindings
                .addRepeatedInt32Wrapper(Int32Value.of(1))
                .addRepeatedInt32Wrapper(Int32Value.of(2))
                .addRepeatedInt64Wrapper(Int64Value.of(3))
                .addRepeatedFloatWrapper(FloatValue.of(1.5f))
                .addRepeatedFloatWrapper(FloatValue.of(2.5f))
                .addRepeatedDoubleWrapper(DoubleValue.of(3.5f))
                .addRepeatedDoubleWrapper(DoubleValue.of(4.5f))
                .addRepeatedStringWrapper(StringValue.of("foo"))
                .addRepeatedStringWrapper(StringValue.of("bar"))
                .addRepeatedBoolWrapper(BoolValue.of(true))
                .addRepeatedUint32Wrapper(UInt32Value.of(1))
                .addRepeatedUint32Wrapper(UInt32Value.of(2))));

    source =
        "x.single_bool_wrapper == null && "
            + "x.single_bytes_wrapper == null && "
            + "x.single_double_wrapper == null && "
            + "x.single_float_wrapper == null && "
            + "x.single_int32_wrapper == null && "
            + "x.single_int64_wrapper == null && "
            + "x.single_string_wrapper == null && "
            + "x.single_uint32_wrapper == null && "
            + "x.single_uint64_wrapper == null";
    runTest(ImmutableMap.of("x", TestAllTypes.getDefaultInstance()));

    declareVariable("dyn_var", SimpleType.DYN);
    source = "dyn_var";
    runTest(ImmutableMap.of("dyn_var", NullValue.NULL_VALUE));

    clearAllDeclarations();
    declareVariable("int32_list", ListType.create(SimpleType.INT));
    declareVariable("int64_list", ListType.create(SimpleType.INT));
    declareVariable("uint32_list", ListType.create(SimpleType.UINT));
    declareVariable("uint64_list", ListType.create(SimpleType.UINT));
    declareVariable("float_list", ListType.create(SimpleType.DOUBLE));
    declareVariable("double_list", ListType.create(SimpleType.DOUBLE));
    declareVariable("bool_list", ListType.create(SimpleType.BOOL));
    declareVariable("string_list", ListType.create(SimpleType.STRING));
    declareVariable("bytes_list", ListType.create(SimpleType.BYTES));

    setContainer(CelContainer.ofName(TestAllTypes.getDescriptor().getFullName()));
    source =
        "TestAllTypes{repeated_int32: int32_list}.repeated_int32 == [1] && "
            + "TestAllTypes{repeated_int64: int64_list}.repeated_int64 == [2] && "
            + "TestAllTypes{repeated_uint32: uint32_list}.repeated_uint32 == [3u] && "
            + "TestAllTypes{repeated_uint64: uint64_list}.repeated_uint64 == [4u] && "
            + "TestAllTypes{repeated_float: float_list}.repeated_float == [5.5] && "
            + "TestAllTypes{repeated_double: double_list}.repeated_double == [6.6] && "
            + "TestAllTypes{repeated_bool: bool_list}.repeated_bool == [true] && "
            + "TestAllTypes{repeated_string: string_list}.repeated_string == ['hello'] && "
            + "TestAllTypes{repeated_bytes: bytes_list}.repeated_bytes == [b'world']";

    runTest(
        ImmutableMap.<String, Object>builder()
            .put("int32_list", ImmutableList.of(Int32Value.of(1)))
            .put("int64_list", ImmutableList.of(Int64Value.of(2)))
            .put("uint32_list", ImmutableList.of(UInt32Value.of(3)))
            .put("uint64_list", ImmutableList.of(UInt64Value.of(4)))
            .put("float_list", ImmutableList.of(FloatValue.of(5.5f)))
            .put("double_list", ImmutableList.of(DoubleValue.of(6.6)))
            .put("bool_list", ImmutableList.of(BoolValue.of(true)))
            .put("string_list", ImmutableList.of(StringValue.of("hello")))
            .put("bytes_list", ImmutableList.of(BytesValue.of(ByteString.copyFromUtf8("world"))))
            .buildOrThrow());

    clearAllDeclarations();
    // Currently allowed, but will be an error
    // See https://github.com/google/cel-spec/pull/501
    source = "google.protobuf.Timestamp{ seconds: 253402300800 }";
    runTest();
  }

  @Test
  public void nullAssignability() throws Exception {
    setContainer(CelContainer.ofName(TestAllTypes.getDescriptor().getFile().getPackage()));
    source = "TestAllTypes{single_int64_wrapper: null}.single_int64_wrapper == null";
    runTest();

    source = "TestAllTypes{}.single_int64_wrapper == null";
    runTest();

    source = "has(TestAllTypes{single_int64_wrapper: null}.single_int64_wrapper)";
    runTest();

    source = "TestAllTypes{single_value: null}.single_value == null";
    runTest();

    source = "has(TestAllTypes{single_value: null}.single_value)";
    runTest();

    source = "TestAllTypes{single_timestamp: null}.single_timestamp == timestamp(0)";
    runTest();

    source = "has(TestAllTypes{single_timestamp: null}.single_timestamp)";
    runTest();

    source =
        "TestAllTypes{repeated_timestamp: [timestamp(1), null]}.repeated_timestamp =="
            + " [timestamp(1)]";
    runTest();

    source =
        "TestAllTypes{map_bool_timestamp: {true: null, false: timestamp(1)}}.map_bool_timestamp =="
            + " {false: timestamp(1)}";
    runTest();

    source = "TestAllTypes{repeated_any: [1, null]}.repeated_any == [1, null]";
    runTest();

    source =
        "TestAllTypes{map_bool_any: {true: null, false: 1}}.map_bool_any == {true: null, false: 1}";
    runTest();

    source =
        "TestAllTypes{repeated_value: [google.protobuf.Value{bool_value: true},"
            + " null]}.repeated_value == [true, null]";
    runTest();

    source =
        "TestAllTypes{map_bool_value: {true: null, false: google.protobuf.Value{bool_value:"
            + " true}}}.map_bool_value == {true: null, false: true}";
    runTest();
  }

  @Test
  public void longComprehension() {
    ImmutableList<Long> l = LongStream.range(0L, 1000L).boxed().collect(toImmutableList());
    addFunctionBinding(
        CelFunctionBinding.from("constantLongList", ImmutableList.of(), unused -> l));

    // Comprehension over compile-time constant long list.
    declareFunction(
        "constantLongList",
        globalOverload("constantLongList", ImmutableList.of(), ListType.create(SimpleType.INT)));
    source = "size(constantLongList().map(x, x+1)) == 1000";
    runTest();

    // Comprehension over long list that is not compile-time constant.
    declareVariable("longlist", ListType.create(SimpleType.INT));
    source = "size(longlist.map(x, x+1)) == 1000";
    runTest(ImmutableMap.of("longlist", l));

    // Comprehension over long list where the computation is very slow.
    // (This is here pro-forma only since in the synchronous interpreter there
    // is no notion of a computation being slow so that another computation can
    // build up a stack while waiting.)
    addFunctionBinding(
        CelFunctionBinding.from("f_slow_inc", Long.class, n -> n + 1L),
        CelFunctionBinding.from("f_unleash", Object.class, x -> x));
    declareFunction(
        "f_slow_inc",
        globalOverload("f_slow_inc", ImmutableList.of(SimpleType.INT), SimpleType.INT));
    declareFunction(
        "f_unleash",
        globalOverload(
            "f_unleash", ImmutableList.of(TypeParamType.create("A")), TypeParamType.create("A")));
    source = "f_unleash(longlist.map(x, f_slow_inc(x)))[0] == 1";
    runTest(ImmutableMap.of("longlist", l));
  }

  @Test
  public void maxComprehension() {
    // Comprehension over long list that is not compile-time constant.
    declareVariable("longlist", ListType.create(SimpleType.INT));
    source = "size(longlist.map(x, x+1)) == 1000";

    // Comprehension which exceeds the configured iteration limit.
    ImmutableList<Long> tooLongList =
        LongStream.range(0L, COMPREHENSION_MAX_ITERATIONS + 1).boxed().collect(toImmutableList());
    runTest(ImmutableMap.of("longlist", tooLongList));

    // Sequential iterations within the collective limit of 1000.
    source = "longlist.filter(i, i % 2 == 0).map(i, i * 2).map(i, i / 2).size() == 250";
    ImmutableList<Long> l =
        LongStream.range(0L, COMPREHENSION_MAX_ITERATIONS / 2).boxed().collect(toImmutableList());
    runTest(ImmutableMap.of("longlist", l));

    // Sequential iterations outside the limit of 1000.
    source = "(longlist + [0]).filter(i, i % 2 == 0).map(i, i * 2).map(i, i / 2).size() == 251";
    runTest(ImmutableMap.of("longlist", l));

    // Nested iteration within the iteration limit.
    // Note, there is some double-counting of the inner-loops which causes the iteration limit to
    // get tripped sooner than one might expect for the nested case.
    source = "longlist.map(i, longlist.map(j, longlist.map(k, [i, j, k]))).size() == 9";
    l = LongStream.range(0L, 9).boxed().collect(toImmutableList());
    runTest(ImmutableMap.of("longlist", l));

    // Nested iteration which exceeds the iteration limit. This result may be surprising, but the
    // limit is tripped precisely because each complete iteration of an inner-loop counts as inner-
    // loop + 1 as there's not a clean way to deduct an iteration and only count the inner most
    // loop.
    l = LongStream.range(0L, 10).boxed().collect(toImmutableList());
    runTest(ImmutableMap.of("longlist", l));
  }

  @Test
  public void dynamicMessage_adapted() throws Exception {
    TestAllTypes wrapperBindings =
        TestAllTypes.newBuilder()
            .setSingleAny(Any.pack(NestedMessage.newBuilder().setBb(42).build()))
            .setSingleBoolWrapper(BoolValue.of(true))
            .setSingleBytesWrapper(BytesValue.of(ByteString.copyFrom(new byte[] {'h', 'i'})))
            .setSingleDoubleWrapper(DoubleValue.of(-3.0))
            .setSingleFloatWrapper(FloatValue.of(1.5f))
            .setSingleInt32Wrapper(Int32Value.of(-12))
            .setSingleInt64Wrapper(Int64Value.of(-34))
            .setSingleStringWrapper(StringValue.of("hello"))
            .setSingleUint32Wrapper(UInt32Value.of(12))
            .setSingleUint64Wrapper(UInt64Value.of(34))
            .setSingleDuration(
                com.google.protobuf.Duration.newBuilder().setSeconds(10).setNanos(20))
            .setSingleTimestamp(Timestamp.newBuilder().setSeconds(100).setNanos(200))
            .setSingleValue(Value.newBuilder().setStringValue("a"))
            .setSingleStruct(
                Struct.newBuilder().putFields("b", Value.newBuilder().setStringValue("c").build()))
            .setListValue(
                ListValue.newBuilder().addValues(Value.newBuilder().setStringValue("d")).build())
            .build();

    ImmutableMap<String, Object> input =
        ImmutableMap.of(
            "msg",
            DynamicMessage.parseFrom(
                TestAllTypes.getDescriptor(),
                wrapperBindings.toByteArray(),
                DefaultDescriptorPool.INSTANCE.getExtensionRegistry()));

    declareVariable("msg", StructTypeReference.create(TestAllTypes.getDescriptor().getFullName()));

    source = "msg.single_any";
    assertThat(runTest(input)).isInstanceOf(NestedMessage.class);

    source = "msg.single_bool_wrapper";
    assertThat(runTest(input)).isInstanceOf(Boolean.class);

    source = "msg.single_bytes_wrapper";
    assertThat(runTest(input)).isInstanceOf(String.class);

    source = "msg.single_double_wrapper";
    assertThat(runTest(input)).isInstanceOf(Double.class);

    source = "msg.single_float_wrapper";
    assertThat(runTest(input)).isInstanceOf(Double.class);

    source = "msg.single_int32_wrapper";
    assertThat(runTest(input)).isInstanceOf(Long.class);

    source = "msg.single_int64_wrapper";
    assertThat(runTest(input)).isInstanceOf(Long.class);

    source = "msg.single_string_wrapper";
    assertThat(runTest(input)).isInstanceOf(String.class);

    source = "msg.single_uint32_wrapper";
    assertThat(runTest(input))
        .isInstanceOf(BASE_CEL_OPTIONS.enableUnsignedLongs() ? UnsignedLong.class : Long.class);

    source = "msg.single_uint64_wrapper";
    assertThat(runTest(input))
        .isInstanceOf(BASE_CEL_OPTIONS.enableUnsignedLongs() ? UnsignedLong.class : Long.class);

    source = "msg.single_duration";
    assertThat(runTest(input)).isInstanceOf(java.time.Duration.class);

    source = "msg.single_timestamp";
    assertThat(runTest(input)).isInstanceOf(Instant.class);

    source = "msg.single_value";
    assertThat(runTest(input)).isInstanceOf(String.class);

    source = "msg.single_struct";
    assertThat(runTest(input)).isInstanceOf(Map.class);

    source = "msg.list_value";
    assertThat(runTest(input)).isInstanceOf(List.class);
  }

  @Test
  public void dynamicMessage_dynamicDescriptor() throws Exception {
    setContainer(CelContainer.ofName("dev.cel.testing.testdata.serialized.proto3"));

    source = "TestAllTypes {}";
    assertThat(runTest()).isInstanceOf(DynamicMessage.class);
    source = "TestAllTypes { single_int32: 1, single_int64: 2, single_string: 'hello'}";
    assertThat(runTest()).isInstanceOf(DynamicMessage.class);
    source =
        "TestAllTypes { single_int32: 1, single_int64: 2, single_string: 'hello'}.single_string";
    assertThat(runTest()).isInstanceOf(String.class);

    // Test wrappers
    source = "TestAllTypes { single_int32_wrapper: 3 }.single_int32_wrapper";
    assertThat(runTest()).isInstanceOf(Long.class);
    source = "TestAllTypes { single_int64_wrapper: 3 }.single_int64_wrapper";
    assertThat(runTest()).isInstanceOf(Long.class);
    source = "TestAllTypes { single_bool_wrapper: true }.single_bool_wrapper";
    assertThat(runTest()).isInstanceOf(Boolean.class);
    source = "TestAllTypes { single_bytes_wrapper: b'abc' }.single_bytes_wrapper";
    assertThat(runTest()).isInstanceOf(String.class);
    source = "TestAllTypes { single_float_wrapper: 1.1 }.single_float_wrapper";
    assertThat(runTest()).isInstanceOf(Double.class);
    source = "TestAllTypes { single_double_wrapper: 1.1 }.single_double_wrapper";
    assertThat(runTest()).isInstanceOf(Double.class);
    source = "TestAllTypes { single_uint32_wrapper: 2u}.single_uint32_wrapper";
    assertThat(runTest())
        .isInstanceOf(BASE_CEL_OPTIONS.enableUnsignedLongs() ? UnsignedLong.class : Long.class);
    source = "TestAllTypes { single_uint64_wrapper: 2u}.single_uint64_wrapper";
    assertThat(runTest())
        .isInstanceOf(BASE_CEL_OPTIONS.enableUnsignedLongs() ? UnsignedLong.class : Long.class);
    source = "TestAllTypes { single_list_value: ['a', 1.5, true] }.single_list_value";
    assertThat(runTest()).isInstanceOf(List.class);

    // Test nested messages
    source =
        "TestAllTypes { standalone_message: TestAllTypes.NestedMessage { } }.standalone_message";
    assertThat(runTest()).isInstanceOf(DynamicMessage.class);
    source =
        "TestAllTypes { standalone_message: TestAllTypes.NestedMessage { bb: 5}"
            + " }.standalone_message.bb";
    assertThat(runTest()).isInstanceOf(Long.class);
    source = "TestAllTypes { standalone_enum: TestAllTypes.NestedEnum.BAR }.standalone_enum";
    assertThat(runTest()).isInstanceOf(Long.class);
    source = "TestAllTypes { map_string_string: {'key': 'value'}}";
    assertThat(runTest()).isInstanceOf(DynamicMessage.class);
    source = "TestAllTypes { map_string_string: {'key': 'value'}}.map_string_string";
    assertThat(runTest()).isInstanceOf(Map.class);
    source = "TestAllTypes { map_string_string: {'key': 'value'}}.map_string_string['key']";
    assertThat(runTest()).isInstanceOf(String.class);

    // Test any unpacking
    // With well-known type
    Any anyDuration = Any.pack(ProtoTimeUtils.toProtoDuration(Duration.ofSeconds(100)));
    declareVariable("dur", SimpleType.TIMESTAMP);
    source = "TestAllTypes { single_any: dur }.single_any";
    assertThat(runTest(ImmutableMap.of("dur", anyDuration))).isInstanceOf(Duration.class);
    // with custom message
    clearAllDeclarations();
    Any anyTestMsg = Any.pack(TestAllTypes.newBuilder().setSingleString("hello").build());
    declareVariable(
        "any_packed_test_msg",
        StructTypeReference.create(TestAllTypes.getDescriptor().getFullName()));
    source = "TestAllTypes { single_any: any_packed_test_msg }.single_any";
    assertThat(runTest(ImmutableMap.of("any_packed_test_msg", anyTestMsg)))
        .isInstanceOf(TestAllTypes.class);

    // Test JSON map behavior
    declareVariable(
        "test_msg", StructTypeReference.create(TestAllTypes.getDescriptor().getFullName()));
    declareVariable(
        "dynamic_msg", StructTypeReference.create(TEST_ALL_TYPE_DYNAMIC_DESCRIPTOR.getFullName()));
    DynamicMessage.Builder dynamicMessageBuilder =
        DynamicMessage.newBuilder(TEST_ALL_TYPE_DYNAMIC_DESCRIPTOR);
    JsonFormat.parser().merge("{ 'map_string_string' : { 'foo' : 'bar' } }", dynamicMessageBuilder);
    ImmutableMap<String, Message> input =
        ImmutableMap.of("dynamic_msg", dynamicMessageBuilder.build());

    source = "dynamic_msg";
    assertThat(runTest(input)).isInstanceOf(DynamicMessage.class);
    source = "dynamic_msg.map_string_string";
    assertThat(runTest(input)).isInstanceOf(Map.class);
    source = "dynamic_msg.map_string_string['foo']";
    assertThat(runTest(input)).isInstanceOf(String.class);

    // Test function dispatch
    declareFunction(
        "f_msg",
        globalOverload(
            "f_msg_generated",
            ImmutableList.of(
                StructTypeReference.create(TestAllTypes.getDescriptor().getFullName())),
            SimpleType.BOOL),
        globalOverload(
            "f_msg_dynamic",
            ImmutableList.of(
                StructTypeReference.create(TEST_ALL_TYPE_DYNAMIC_DESCRIPTOR.getFullName())),
            SimpleType.BOOL));
    addFunctionBinding(
        CelFunctionBinding.fromOverloads(
            "f_msg",
            CelFunctionBinding.from("f_msg_generated", TestAllTypes.class, unused -> true),
            CelFunctionBinding.from("f_msg_dynamic", DynamicMessage.class, unused -> true)));
    input =
        ImmutableMap.of(
            "dynamic_msg", dynamicMessageBuilder.build(),
            "test_msg", TestAllTypes.newBuilder().setSingleInt64(10L).build());

    source = "f_msg(dynamic_msg)";
    assertThat(runTest(input)).isInstanceOf(Boolean.class);
    source = "f_msg(test_msg)";
    assertThat(runTest(input)).isInstanceOf(Boolean.class);
  }

  @Immutable
  private static final class RecordedValues {
    @SuppressWarnings("Immutable")
    private final Map<String, Object> recordedValues = new HashMap<>();

    @CanIgnoreReturnValue
    private Object record(String key, Object value) {
      recordedValues.put(key, value);
      return value;
    }

    private ImmutableMap<String, Object> getRecordedValues() {
      return ImmutableMap.copyOf(recordedValues);
    }
  }

  @Test
  public void lateBoundFunctions() throws Exception {
    RecordedValues recordedValues = new RecordedValues();
    CelLateFunctionBindings lateBindings =
        CelLateFunctionBindings.from(
            CelFunctionBinding.from(
                "record_string_dyn", String.class, Object.class, recordedValues::record));
    declareFunction(
        "record",
        globalOverload(
            "record_string_dyn",
            ImmutableList.of(SimpleType.STRING, SimpleType.DYN),
            SimpleType.DYN));
    source = "record('foo', 'bar')";
    assertThat(runTest(ImmutableMap.of(), lateBindings)).isEqualTo("bar");
    assertThat(recordedValues.getRecordedValues()).containsExactly("foo", "bar");
  }

  @Test
  public void jsonFieldNames() throws Exception {
    this.celOptions = celOptions.toBuilder().enableJsonFieldNames(true).build();
    this.celRuntime = newBaseRuntimeBuilder(celOptions).build();

    TestAllTypes message = TestAllTypes.newBuilder().setSingleInt32(42).build();
    declareVariable("x", StructTypeReference.create(TestAllTypes.getDescriptor().getFullName()));

    source = "x.singleInt32 == 42";
    assertThat(runTest(ImmutableMap.of("x", message))).isEqualTo(true);

    source = "TestAllTypes{singleInt32: 42}.singleInt32 == 42";
    setContainer(CelContainer.ofName(TestAllTypes.getDescriptor().getFile().getPackage()));
    assertThat(runTest()).isEqualTo(true);

    skipBaselineVerification();
  }

  /**
   * Checks that the CheckedExpr produced by CelCompiler is equal to the one reproduced by the
   * native CelAbstractSyntaxTree
   */
  private static void assertAstRoundTrip(CelAbstractSyntaxTree ast) {
    if (ast.isChecked()) {
      CheckedExpr checkedExpr = CelProtoAbstractSyntaxTree.fromCelAst(ast).toCheckedExpr();
      CelProtoAbstractSyntaxTree protoAst = CelProtoAbstractSyntaxTree.fromCelAst(ast);
      assertThat(checkedExpr).isEqualTo(protoAst.toCheckedExpr());
    } else {
      ParsedExpr parsedExpr = CelProtoAbstractSyntaxTree.fromCelAst(ast).toParsedExpr();
      CelProtoAbstractSyntaxTree protoAst = CelProtoAbstractSyntaxTree.fromCelAst(ast);
      assertThat(parsedExpr).isEqualTo(protoAst.toParsedExpr());
    }
  }

  private static String readResourceContent(String path) throws IOException {
    return Resources.toString(Resources.getResource(Ascii.toLowerCase(path)), UTF_8);
  }

  @SuppressWarnings("unchecked")
  private void printBinding(Object input, CelAttributePattern... patterns) {
    if (input instanceof Map) {
      Map<String, Object> inputMap = (Map<String, Object>) input;
      if (inputMap.isEmpty() && patterns.length == 0) {
        println("bindings: {}");
        return;
      }

      boolean first = true;
      StringBuilder sb = new StringBuilder().append("{");
      for (Map.Entry<String, Object> entry : inputMap.entrySet()) {
        if (!first) {
          sb.append(", ");
        }
        first = false;
        sb.append(entry.getKey());
        sb.append("=");
        Object value = entry.getValue();
        if (value instanceof CelByteString) {
          sb.append(getHumanReadableString((CelByteString) value));
        } else {
          sb.append(UnredactedDebugFormatForTest.unredactedToString(entry.getValue()));
        }
      }
      if (patterns.length > 0) {
        if (!inputMap.isEmpty()) {
          sb.append(", ");
        }
        sb.append("unknown_attributes=");
        sb.append(Arrays.toString(patterns));
      }
      sb.append("}");
      println("bindings: " + sb);
    } else {
      if (patterns.length > 0) {
        println("bindings: " + input + ", unknown_attributes=" + Arrays.toString(patterns));
      } else {
        println("bindings: " + input);
      }
    }
  }

  private static String getHumanReadableString(CelByteString byteString) {
    // Very unfortunate we have to do this at all
    StringBuilder sb = new StringBuilder();
    sb.append("[");
    byte[] bytes = byteString.toByteArray();
    for (int i = 0; i < bytes.length; i++) {
      byte b = bytes[i];
      sb.append(b);
      if (i < bytes.length - 1) {
        sb.append(", ");
      }
    }
    sb.append("]");
    return sb.toString();
  }

  private static final class TestOnlyVariableResolver implements CelVariableResolver {
    private final Map<String, ?> map;

    private static TestOnlyVariableResolver newInstance(Map<String, ?> map) {
      return new TestOnlyVariableResolver(map);
    }

    @Override
    public Optional<Object> find(String name) {
      return Optional.ofNullable(map.get(name));
    }

    @Override
    public String toString() {
      return UnredactedDebugFormatForTest.unredactedToString(map);
    }

    private TestOnlyVariableResolver(Map<String, ?> map) {
      this.map = map;
    }
  }

  private static CelVariableResolver extend(Map<String, ?> primary, Map<String, ?> secondary) {
    return hierarchicalVariableResolver(
        TestOnlyVariableResolver.newInstance(primary),
        TestOnlyVariableResolver.newInstance(secondary));
  }

  private static CelVariableResolver extend(CelVariableResolver primary, Map<String, ?> secondary) {
    return hierarchicalVariableResolver(primary, TestOnlyVariableResolver.newInstance(secondary));
  }

  private void addFunctionBinding(CelFunctionBinding... functionBindings) {
    addFunctionBinding(Arrays.asList(functionBindings));
  }

  private void addFunctionBinding(Collection<CelFunctionBinding> functionBindings) {
    celRuntime = celRuntime.toRuntimeBuilder().addFunctionBindings(functionBindings).build();
  }

  private static Descriptor getDeserializedTestAllTypeDescriptor() {
    try {
      String fdsContent = readResourceContent("testdata/proto3/test_all_types.fds");
      FileDescriptorSet fds = TextFormat.parse(fdsContent, FileDescriptorSet.class);
      ImmutableSet<FileDescriptor> fileDescriptors = FileDescriptorSetConverter.convert(fds);

      return fileDescriptors.stream()
          .flatMap(f -> f.getMessageTypes().stream())
          .filter(
              x ->
                  x.getFullName().equals("dev.cel.testing.testdata.serialized.proto3.TestAllTypes"))
          .findAny()
          .orElseThrow(
              () ->
                  new IllegalStateException(
                      "Could not find deserialized TestAllTypes descriptor."));
    } catch (IOException e) {
      throw new RuntimeException("Error loading TestAllTypes descriptor", e);
    }
  }
}
