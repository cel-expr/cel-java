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
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.protobuf.ByteString;
import dev.cel.bundle.Cel;
import dev.cel.bundle.CelFactory;
import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.CelContainer;
import dev.cel.common.CelOptions;
import dev.cel.common.ast.CelBlock;
import dev.cel.common.types.ProtoMessageTypeProvider;
import dev.cel.common.types.StructTypeReference;
import dev.cel.common.values.CelByteString;
import dev.cel.common.values.ProtoMessageLiteValueProvider;
import dev.cel.expr.conformance.proto3.TestAllTypes;
import dev.cel.expr.conformance.proto3.TestAllTypes.NestedMessage;
import dev.cel.expr.conformance.proto3.TestAllTypesCelDescriptor;
import dev.cel.optimizer.CelOptimizer;
import dev.cel.optimizer.CelOptimizerFactory;
import dev.cel.optimizer.optimizers.SelectOptimizer;
import dev.cel.optimizer.optimizers.SelectOptimizer.SelectOptimizerOptions;
import dev.cel.optimizer.optimizers.SubexpressionOptimizer;
import dev.cel.optimizer.optimizers.SubexpressionOptimizer.SubexpressionOptimizerOptions;
import dev.cel.parser.CelStandardMacro;
import dev.cel.protobuf.CelLiteDescriptor;
import dev.cel.protobuf.CelLiteDescriptor.FieldLiteDescriptor;
import dev.cel.protobuf.CelLiteDescriptor.MessageLiteDescriptor;
import java.util.ArrayList;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class CelLiteRuntimeVersionSkewTest {

  private static final CelContainer CEL_CONTAINER =
      CelContainer.ofName("cel.expr.conformance.proto3");

  private static final CelOptions CEL_OPTIONS =
      CelOptions.current()
          .populateMacroCalls(true)
          .enableHeterogeneousNumericComparisons(true)
          .build();

  private Cel cel;
  private CelOptimizer celOptimizer;
  private CelLiteRuntime v1Runtime;
  private ProtoMessageLiteValueProvider v1ValueProvider;

  @Before
  public void setUp() {
    // Schema V2 Compiler: includes single_int64 (field 2), single_nested_message (field 21),
    // single_string (field 14), single_bool (field 13), single_bytes (field 15).
    cel =
        CelFactory.standardCelBuilder()
            .setOptions(CEL_OPTIONS)
            .setStandardMacros(CelStandardMacro.STANDARD_MACROS)
            .addMessageTypes(TestAllTypes.getDescriptor())
            .addVar("msg", StructTypeReference.create(TestAllTypes.getDescriptor().getFullName()))
            .setContainer(CEL_CONTAINER)
            .build();

    celOptimizer =
        CelOptimizerFactory.standardCelOptimizerBuilder(cel)
            .addAstOptimizers(
                SelectOptimizer.newInstance(
                    SelectOptimizerOptions.newBuilder().build(),
                    TestAllTypes.getDescriptor().getFile()))
            .build();

    // Schema V1 Runtime: restricted CelLiteDescriptor omitting fields 2, 13, 14, 15, and 21.
    CelLiteDescriptor fullDescriptor = TestAllTypesCelDescriptor.getDescriptor();
    MessageLiteDescriptor fullMsgDesc =
        fullDescriptor
            .getProtoTypeNamesToDescriptors()
            .get("cel.expr.conformance.proto3.TestAllTypes");

    List<FieldLiteDescriptor> v1Fields = new ArrayList<>();
    for (FieldLiteDescriptor f : fullMsgDesc.getFieldDescriptors()) {
      String name = f.getFieldName();
      if (!name.equals("single_int64")
          && !name.equals("single_nested_message")
          && !name.equals("single_string")
          && !name.equals("single_bool")
          && !name.equals("single_bytes")) {
        v1Fields.add(f);
      }
    }

    MessageLiteDescriptor v1MsgDesc =
        new MessageLiteDescriptor(
            fullMsgDesc.getProtoTypeName(), v1Fields, fullMsgDesc::newMessageBuilder);

    List<MessageLiteDescriptor> allMsgDescs = new ArrayList<>();
    for (MessageLiteDescriptor d : fullDescriptor.getProtoTypeNamesToDescriptors().values()) {
      if (d.getProtoTypeName().equals("cel.expr.conformance.proto3.TestAllTypes")) {
        allMsgDescs.add(v1MsgDesc);
      } else {
        allMsgDescs.add(d);
      }
    }

    CelLiteDescriptor v1Descriptor = new CelLiteDescriptor("v1", allMsgDescs) {};
    v1ValueProvider = ProtoMessageLiteValueProvider.newInstance(v1Descriptor);

    v1Runtime =
        CelLiteRuntimeFactory.newLiteRuntimeBuilder()
            .setStandardFunctions(CelStandardFunctions.ALL_STANDARD_FUNCTIONS)
            .setTypeProvider(
                ProtoMessageTypeProvider.newBuilder()
                    .addDescriptors(ImmutableSet.of(TestAllTypes.getDescriptor()))
                    .build())
            .setValueProvider(v1ValueProvider)
            .setContainer(CEL_CONTAINER)
            .build();
  }

  private Object eval(String expression, TestAllTypes message) throws Exception {
    CelAbstractSyntaxTree ast = cel.compile(expression).getAst();
    CelAbstractSyntaxTree optimizedAst = celOptimizer.optimize(ast);
    Program program = v1Runtime.createProgram(optimizedAst);
    return program.eval(ImmutableMap.of("msg", message));
  }

  @Test
  public void select_unsetUnknownScalar_returnsBakedDefault() throws Exception {
    TestAllTypes msg = TestAllTypes.getDefaultInstance();

    Object result = eval("msg.single_int64", msg);

    assertThat(result).isEqualTo(0L);
  }

  @Test
  public void select_populatedUnknownScalar_decodesFromWireBytes() throws Exception {
    TestAllTypes msg = TestAllTypes.newBuilder().setSingleInt64(42L).build();

    Object result = eval("msg.single_int64", msg);

    assertThat(result).isEqualTo(42L);
  }

  @Test
  public void has_unknownScalar_whenPresentOnWire_returnsTrue() throws Exception {
    TestAllTypes msg = TestAllTypes.newBuilder().setSingleInt64(42L).build();

    Object result = eval("has(msg.single_int64)", msg);

    assertThat(result).isEqualTo(true);
  }

  @Test
  public void has_unknownScalar_whenAbsentOnWire_returnsFalse() throws Exception {
    TestAllTypes msg = TestAllTypes.getDefaultInstance();

    Object result = eval("has(msg.single_int64)", msg);

    assertThat(result).isEqualTo(false);
  }

  @Test
  public void select_unsetUnknownString_returnsBakedDefault() throws Exception {
    TestAllTypes msg = TestAllTypes.getDefaultInstance();

    Object result = eval("msg.single_string", msg);

    assertThat(result).isEqualTo("");
  }

  @Test
  public void select_populatedUnknownString_decodesFromWireBytes() throws Exception {
    TestAllTypes msg = TestAllTypes.newBuilder().setSingleString("cel-skew-test").build();

    Object result = eval("msg.single_string", msg);

    assertThat(result).isEqualTo("cel-skew-test");
  }

  @Test
  public void has_unknownString_whenPresentOnWire_returnsTrue() throws Exception {
    TestAllTypes msg = TestAllTypes.newBuilder().setSingleString("present").build();

    Object result = eval("has(msg.single_string)", msg);

    assertThat(result).isEqualTo(true);
  }

  @Test
  public void select_unsetUnknownBool_returnsBakedDefault() throws Exception {
    TestAllTypes msg = TestAllTypes.getDefaultInstance();

    Object result = eval("msg.single_bool", msg);

    assertThat(result).isEqualTo(false);
  }

  @Test
  public void select_populatedUnknownBool_decodesFromWireBytes() throws Exception {
    TestAllTypes msg = TestAllTypes.newBuilder().setSingleBool(true).build();

    Object result = eval("msg.single_bool", msg);

    assertThat(result).isEqualTo(true);
  }

  @Test
  public void select_unsetUnknownBytes_returnsBakedDefault() throws Exception {
    TestAllTypes msg = TestAllTypes.getDefaultInstance();

    Object result = eval("msg.single_bytes", msg);

    assertThat(result).isEqualTo(CelByteString.EMPTY);
  }

  @Test
  public void select_populatedUnknownBytes_decodesFromWireBytes() throws Exception {
    TestAllTypes msg =
        TestAllTypes.newBuilder().setSingleBytes(ByteString.copyFromUtf8("binary")).build();

    Object result = eval("msg.single_bytes", msg);

    assertThat(result).isEqualTo(CelByteString.of("binary".getBytes(UTF_8)));
  }

  @Test
  public void select_populatedUnknownSubmessage_traversesWireBytes() throws Exception {
    TestAllTypes msg =
        TestAllTypes.newBuilder()
            .setSingleNestedMessage(NestedMessage.newBuilder().setBb(123).build())
            .build();

    Object result = eval("msg.single_nested_message.bb", msg);

    assertThat(result).isEqualTo(123L);
  }

  @Test
  public void select_unsetUnknownSubmessage_returnsBakedDefault() throws Exception {
    TestAllTypes msg = TestAllTypes.getDefaultInstance();

    Object result = eval("msg.single_nested_message.bb", msg);

    assertThat(result).isEqualTo(0L);
  }

  @Test
  public void has_unknownSubmessageField_whenPopulated_returnsTrue() throws Exception {
    TestAllTypes msg =
        TestAllTypes.newBuilder()
            .setSingleNestedMessage(NestedMessage.newBuilder().setBb(123).build())
            .build();

    Object result = eval("has(msg.single_nested_message.bb)", msg);

    assertThat(result).isEqualTo(true);
  }

  @Test
  public void has_unknownSubmessageField_whenSubmessageUnset_returnsFalse() throws Exception {
    TestAllTypes msg = TestAllTypes.getDefaultInstance();

    Object result = eval("has(msg.single_nested_message.bb)", msg);

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

  @Test
  public void mixedExpression_versionSkewFieldWithCondition() throws Exception {
    TestAllTypes msg =
        TestAllTypes.newBuilder()
            .setSingleInt64(100L)
            .setSingleString("alpha")
            .setSingleBool(true)
            .setSingleNestedMessage(NestedMessage.newBuilder().setBb(50).build())
            .build();

    Object result =
        eval(
            "msg.single_int64 > 50 && msg.single_bool && msg.single_string == 'alpha' &&"
                + " has(msg.single_nested_message) && msg.single_nested_message.bb == 50",
            msg);

    assertThat(result).isEqualTo(true);
  }

  @Test
  public void select_mapField_indexing() throws Exception {
    TestAllTypes msg =
        TestAllTypes.newBuilder()
            .putMapInt64Message(1L, NestedMessage.newBuilder().setBb(100).build())
            .build();

    Object result = eval("msg.map_int64_message[1].bb", msg);

    assertThat(result).isEqualTo(100L);
  }

  @Test
  public void select_unsetMapField_returnsBakedDefault() throws Exception {
    TestAllTypes msg = TestAllTypes.getDefaultInstance();

    Object result = eval("msg.map_int64_message", msg);

    assertThat(result).isEqualTo(ImmutableMap.of());
  }

  @Test
  public void has_mapField_whenPresent_returnsTrue() throws Exception {
    TestAllTypes msg = TestAllTypes.newBuilder().putMapStringString("hello", "world").build();

    Object result = eval("has(msg.map_string_string)", msg);

    assertThat(result).isEqualTo(true);
  }

  @Test
  public void has_mapField_whenAbsent_returnsFalse() throws Exception {
    TestAllTypes msg = TestAllTypes.getDefaultInstance();

    Object result = eval("has(msg.map_string_string)", msg);

    assertThat(result).isEqualTo(false);
  }

  @Test
  public void mixedExpression_mapFieldWithCondition() throws Exception {
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
  public void fallbackBinding_directCall_qualifiesAttribute() throws Exception {
    TestAllTypes msg = TestAllTypes.newBuilder().setSingleInt64(42L).build();
    ImmutableList<?> qualifierList = ImmutableList.of(ImmutableList.of(2, "single_int64", 3, 0L));

    Object result =
        LiteAttributeStep.qualifyAttribute(msg, qualifierList, v1ValueProvider.celValueConverter());

    assertThat(result).isEqualTo(42L);
  }

  @Test
  public void fallbackBinding_directCall_hasField() throws Exception {
    TestAllTypes msg = TestAllTypes.newBuilder().setSingleInt64(42L).build();
    ImmutableList<?> qualifierList = ImmutableList.of(ImmutableList.of(2, "single_int64"));

    boolean hasPresent =
        LiteAttributeStep.hasField(msg, qualifierList, v1ValueProvider.celValueConverter());
    boolean hasAbsent =
        LiteAttributeStep.hasField(
            TestAllTypes.getDefaultInstance(), qualifierList, v1ValueProvider.celValueConverter());

    assertThat(hasPresent).isTrue();
    assertThat(hasAbsent).isFalse();
  }

  @Test
  public void celBlock_subexpressionThenSelectOptimizer_evaluatesRepeatedUnknownScalar()
      throws Exception {
    CelOptimizer blockOptimizer =
        CelOptimizerFactory.standardCelOptimizerBuilder(cel)
            .addAstOptimizers(
                SubexpressionOptimizer.getInstance(),
                SelectOptimizer.newInstance(
                    SelectOptimizerOptions.newBuilder().build(),
                    TestAllTypes.getDescriptor().getFile()))
            .build();

    CelAbstractSyntaxTree ast =
        cel.compile("msg.single_int64 > 10 && msg.single_int64 < 100").getAst();
    CelAbstractSyntaxTree optimizedAst = blockOptimizer.optimize(ast);

    assertThat(CelBlock.extract(optimizedAst)).isPresent();

    Program program = v1Runtime.createProgram(optimizedAst);
    Object match =
        program.eval(ImmutableMap.of("msg", TestAllTypes.newBuilder().setSingleInt64(42L).build()));
    Object noMatch = program.eval(ImmutableMap.of("msg", TestAllTypes.getDefaultInstance()));

    assertThat(match).isEqualTo(true);
    assertThat(noMatch).isEqualTo(false);
  }

  @Test
  public void celBlock_subexpressionThenSelectOptimizer_evaluatesRepeatedUnknownSubmessage()
      throws Exception {
    CelOptimizer blockOptimizer =
        CelOptimizerFactory.standardCelOptimizerBuilder(cel)
            .addAstOptimizers(
                SubexpressionOptimizer.getInstance(),
                SelectOptimizer.newInstance(
                    SelectOptimizerOptions.newBuilder().build(),
                    TestAllTypes.getDescriptor().getFile()))
            .build();

    CelAbstractSyntaxTree ast =
        cel.compile("msg.single_nested_message.bb > 10 && msg.single_nested_message.bb < 200")
            .getAst();
    CelAbstractSyntaxTree optimizedAst = blockOptimizer.optimize(ast);

    assertThat(CelBlock.extract(optimizedAst)).isPresent();

    Program program = v1Runtime.createProgram(optimizedAst);
    TestAllTypes populatedMsg =
        TestAllTypes.newBuilder()
            .setSingleNestedMessage(NestedMessage.newBuilder().setBb(123).build())
            .build();
    Object match = program.eval(ImmutableMap.of("msg", populatedMsg));
    Object noMatch = program.eval(ImmutableMap.of("msg", TestAllTypes.getDefaultInstance()));

    assertThat(match).isEqualTo(true);
    assertThat(noMatch).isEqualTo(false);
  }

  @Test
  public void celBlock_subexpressionThenSelectOptimizer_sharedSubmessageDifferentFields()
      throws Exception {
    CelOptimizer blockOptimizer =
        CelOptimizerFactory.standardCelOptimizerBuilder(cel)
            .addAstOptimizers(
                SubexpressionOptimizer.getInstance(),
                SelectOptimizer.newInstance(
                    SelectOptimizerOptions.newBuilder().build(),
                    TestAllTypes.getDescriptor().getFile()))
            .build();

    CelAbstractSyntaxTree ast =
        cel.compile("has(msg.single_nested_message.bb) && msg.single_nested_message.bb == 50")
            .getAst();
    CelAbstractSyntaxTree optimizedAst = blockOptimizer.optimize(ast);

    assertThat(CelBlock.extract(optimizedAst)).isPresent();

    Program program = v1Runtime.createProgram(optimizedAst);
    TestAllTypes populatedMsg =
        TestAllTypes.newBuilder()
            .setSingleNestedMessage(NestedMessage.newBuilder().setBb(50).build())
            .build();
    Object match = program.eval(ImmutableMap.of("msg", populatedMsg));
    Object noMatch = program.eval(ImmutableMap.of("msg", TestAllTypes.getDefaultInstance()));

    assertThat(match).isEqualTo(true);
    assertThat(noMatch).isEqualTo(false);
  }

  @Test
  public void celBlock_selectThenSubexpressionOptimizer_eliminatesAttributeCalls()
      throws Exception {
    CelOptimizer blockOptimizer =
        CelOptimizerFactory.standardCelOptimizerBuilder(cel)
            .addAstOptimizers(
                SelectOptimizer.newInstance(
                    SelectOptimizerOptions.newBuilder().build(),
                    TestAllTypes.getDescriptor().getFile()),
                SubexpressionOptimizer.newInstance(
                    SubexpressionOptimizerOptions.newBuilder()
                        .addEliminableFunctions("cel.@attribute", "cel.@hasField")
                        .build()))
            .build();

    CelAbstractSyntaxTree ast =
        cel.compile("msg.single_int64 > 10 && msg.single_int64 < 100").getAst();
    CelAbstractSyntaxTree optimizedAst = blockOptimizer.optimize(ast);

    assertThat(CelBlock.extract(optimizedAst)).isPresent();

    Program program = v1Runtime.createProgram(optimizedAst);
    Object match =
        program.eval(ImmutableMap.of("msg", TestAllTypes.newBuilder().setSingleInt64(42L).build()));
    Object noMatch = program.eval(ImmutableMap.of("msg", TestAllTypes.getDefaultInstance()));

    assertThat(match).isEqualTo(true);
    assertThat(noMatch).isEqualTo(false);
  }
}
