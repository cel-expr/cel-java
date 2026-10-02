// Copyright 2024 Google LLC
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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import dev.cel.bundle.Cel;
import dev.cel.bundle.CelFactory;
import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.CelContainer;
import dev.cel.common.types.OpaqueType;
import dev.cel.common.types.OptionalType;
import dev.cel.common.types.ProtoMessageTypeProvider;
import dev.cel.common.types.SimpleType;
import dev.cel.common.types.TypeType;
import dev.cel.common.values.OpaqueValue;
import dev.cel.expr.conformance.proto3.TestAllTypes;
import dev.cel.extensions.CelOptionalLibrary;
import java.util.Optional;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(TestParameterInjector.class)
public class DescriptorTypeResolverTest {

  private static final ProtoMessageTypeProvider PROTO_MESSAGE_TYPE_PROVIDER =
      new ProtoMessageTypeProvider(ImmutableList.of(TestAllTypes.getDescriptor()));

  private static final Cel CEL =
      CelFactory.plannerCelBuilder()
          .setTypeProvider(PROTO_MESSAGE_TYPE_PROVIDER)
          .setValueProvider(
              (structType, fields) ->
                  structType.equals(TestAllTypes.getDescriptor().getFullName())
                      ? Optional.of(TestAllTypes.getDefaultInstance())
                      : Optional.empty())
          .addCompilerLibraries(CelOptionalLibrary.INSTANCE)
          .addRuntimeLibraries(CelOptionalLibrary.INSTANCE)
          .setContainer(CelContainer.ofName(TestAllTypes.getDescriptor().getFullName()))
          .build();

  @SuppressWarnings("ImmutableEnumChecker") // Test only
  private enum TypeLiteralTestCase {
    BOOL("bool", TypeType.create(SimpleType.BOOL)),
    BYTES("bytes", TypeType.create(SimpleType.BYTES)),
    DOUBLE("double", TypeType.create(SimpleType.DOUBLE)),
    DURATION("google.protobuf.Duration", TypeType.create(SimpleType.DURATION)),
    INT("int", TypeType.create(SimpleType.INT)),
    STRING("string", TypeType.create(SimpleType.STRING)),
    TIMESTAMP("google.protobuf.Timestamp", TypeType.create(SimpleType.TIMESTAMP)),
    UINT("uint", TypeType.create(SimpleType.UINT)),

    NULL_TYPE("null_type", TypeType.create(SimpleType.NULL_TYPE)),
    OPTIONAL_TYPE("optional_type", TypeType.create(OptionalType.create(SimpleType.DYN))),
    PROTO_MESSAGE_TYPE(
        "TestAllTypes",
        TypeType.create(
            PROTO_MESSAGE_TYPE_PROVIDER
                .findType(TestAllTypes.getDescriptor().getFullName())
                .get()));

    private final String expression;

    private final TypeType celRuntimeType;

    TypeLiteralTestCase(String expression, TypeType celRuntimeType) {
      this.expression = expression;
      this.celRuntimeType = celRuntimeType;
    }
  }

  @Test
  public void typeLiteral_success(@TestParameter TypeLiteralTestCase testCase) throws Exception {
    CelAbstractSyntaxTree ast = CEL.compile(testCase.expression).getAst();

    assertThat(CEL.createProgram(ast).eval()).isEqualTo(testCase.celRuntimeType);
  }

  @Test
  public void typeLiteral_wrappedInDyn_success(@TestParameter TypeLiteralTestCase testCase)
      throws Exception {
    CelAbstractSyntaxTree ast = CEL.compile(String.format("dyn(%s)", testCase.expression)).getAst();

    assertThat(CEL.createProgram(ast).eval()).isEqualTo(testCase.celRuntimeType);
  }

  @Test
  public void typeLiteral_equality(@TestParameter TypeLiteralTestCase testCase) throws Exception {
    CelAbstractSyntaxTree ast =
        CEL.compile(String.format("type(%s) == type", testCase.expression)).getAst();

    assertThat(CEL.createProgram(ast).eval()).isEqualTo(true);
  }

  @SuppressWarnings("ImmutableEnumChecker") // Test only
  private enum TypeCallTestCase {
    ANY(
        "google.protobuf.Any{type_url: 'types.googleapis.com/google.protobuf.DoubleValue'}",
        TypeType.create(SimpleType.DOUBLE)),
    BOOL("true", TypeType.create(SimpleType.BOOL)),
    BYTES("b'hi'", TypeType.create(SimpleType.BYTES)),
    DOUBLE("1.5", TypeType.create(SimpleType.DOUBLE)),
    DURATION("duration('1h')", TypeType.create(SimpleType.DURATION)),
    INT("1", TypeType.create(SimpleType.INT)),
    STRING("'test'", TypeType.create(SimpleType.STRING)),
    TIMESTAMP("timestamp(123)", TypeType.create(SimpleType.TIMESTAMP)),
    UINT("1u", TypeType.create(SimpleType.UINT)),
    PROTO_MESSAGE(
        "TestAllTypes{}",
        TypeType.create(
            PROTO_MESSAGE_TYPE_PROVIDER
                .findType(TestAllTypes.getDescriptor().getFullName())
                .get())),
    OPTIONAL_TYPE("optional.of(1)", TypeType.create(OptionalType.create(SimpleType.DYN)));

    private final String expression;

    private final TypeType celRuntimeType;

    TypeCallTestCase(String expression, TypeType celRuntimeType) {
      this.expression = expression;
      this.celRuntimeType = celRuntimeType;
    }
  }

  @Test
  public void typeCall_success(@TestParameter TypeCallTestCase testCase) throws Exception {
    CelAbstractSyntaxTree ast =
        CEL.compile(String.format("type(%s)", testCase.expression)).getAst();

    assertThat(CEL.createProgram(ast).eval()).isEqualTo(testCase.celRuntimeType);
  }

  @Test
  public void typeOfTypeCall_success(@TestParameter TypeCallTestCase testCase) throws Exception {
    CelAbstractSyntaxTree ast =
        CEL.compile(String.format("type(type(%s))", testCase.expression)).getAst();

    assertThat(CEL.createProgram(ast).eval()).isEqualTo(TypeType.create(SimpleType.DYN));
  }

  @Test
  public void typeCall_wrappedInDyn_evaluatesToUnderlyingType(
      @TestParameter TypeCallTestCase testCase) throws Exception {
    CelAbstractSyntaxTree ast =
        CEL.compile(String.format("type(dyn(%s))", testCase.expression)).getAst();

    assertThat(CEL.createProgram(ast).eval()).isEqualTo(testCase.celRuntimeType);
  }

  @Test
  public void typeCall_opaqueVar() throws Exception {
    OpaqueType opaqueType = OpaqueType.create("opaque_type");
    Cel cel = CEL.toCelBuilder().addVar("opaque_var", opaqueType).build();
    CelAbstractSyntaxTree ast = cel.compile("type(opaque_var)").getAst();
    final class CustomClass extends OpaqueValue {
      @Override
      public Object value() {
        return this;
      }

      @Override
      public OpaqueType celType() {
        return opaqueType;
      }
    }

    assertThat(cel.createProgram(ast).eval(ImmutableMap.of("opaque_var", new CustomClass())))
        .isEqualTo(TypeType.create(opaqueType));
  }
}
