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

package dev.cel.common;

import static com.google.common.truth.Truth.assertThat;
import static dev.cel.common.CelSignatureUtils.makeOverloadSignature;
import static dev.cel.common.CelSignatureUtils.makeTypeSignature;
import static dev.cel.common.CelSignatureUtils.parseFunctionSignature;
import static dev.cel.common.CelSignatureUtils.parseTypeSignature;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import com.google.testing.junit.testparameterinjector.TestParameters;
import dev.cel.common.CelSignatureUtils.ParsedFunctionOverload;
import dev.cel.common.types.CelType;
import dev.cel.common.types.CelTypeProvider;
import dev.cel.common.types.CelTypes;
import dev.cel.common.types.EnumType;
import dev.cel.common.types.ListType;
import dev.cel.common.types.MapType;
import dev.cel.common.types.NullableType;
import dev.cel.common.types.OpaqueType;
import dev.cel.common.types.OptionalType;
import dev.cel.common.types.SimpleType;
import dev.cel.common.types.StructTypeReference;
import dev.cel.common.types.TypeParamType;
import dev.cel.common.types.TypeType;
import dev.cel.common.types.UnspecifiedType;
import java.util.Optional;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(TestParameterInjector.class)
public final class CelSignatureUtilsTest {

  private enum RoundTripTypeTestCase {
    NULL(SimpleType.NULL_TYPE, "null"),
    DYN(SimpleType.DYN, "dyn"),
    BOOL(SimpleType.BOOL, "bool"),
    INT(SimpleType.INT, "int"),
    UINT(SimpleType.UINT, "uint"),
    DOUBLE(SimpleType.DOUBLE, "double"),
    STRING(SimpleType.STRING, "string"),
    BYTES(SimpleType.BYTES, "bytes"),
    ANY(SimpleType.ANY, "any"),
    TIMESTAMP(SimpleType.TIMESTAMP, "timestamp"),
    DURATION(SimpleType.DURATION, "duration"),
    BOOL_WRAPPER(NullableType.create(SimpleType.BOOL), "bool_wrapper"),
    INT_WRAPPER(NullableType.create(SimpleType.INT), "int_wrapper"),
    UINT_WRAPPER(NullableType.create(SimpleType.UINT), "uint_wrapper"),
    DOUBLE_WRAPPER(NullableType.create(SimpleType.DOUBLE), "double_wrapper"),
    STRING_WRAPPER(NullableType.create(SimpleType.STRING), "string_wrapper"),
    BYTES_WRAPPER(NullableType.create(SimpleType.BYTES), "bytes_wrapper"),
    LIST_INT(ListType.create(SimpleType.INT), "list<int>"),
    LIST_DYN(ListType.create(SimpleType.DYN), "list<dyn>"),
    MAP_STRING_INT(MapType.create(SimpleType.STRING, SimpleType.INT), "map<string,int>"),
    TYPE_INT(TypeType.create(SimpleType.INT), "type<int>"),
    TYPE_DYN(TypeType.create(SimpleType.DYN), "type<dyn>"),
    TYPE_PARAM_T(TypeParamType.create("T"), "~T"),
    TYPE_PARAM_ESCAPED(TypeParamType.create("foo.bar<baz>"), "~foo\\.bar\\<baz\\>"),
    OPTIONAL_STRING(OptionalType.create(SimpleType.STRING), "optional_type<string>"),
    OPAQUE_SIMPLE(OpaqueType.create("my.pkg.CustomType"), "my.pkg.CustomType"),
    OPAQUE_PARAMETERIZED(
        OpaqueType.create("my.pkg.CustomType", SimpleType.INT, TypeParamType.create("T")),
        "my.pkg.CustomType<int,~T>"),
    FUNCTION_NO_ARGS(
        CelTypes.createFunctionType(SimpleType.INT, ImmutableList.of()), "function<int>"),
    FUNCTION_WITH_ARGS(
        CelTypes.createFunctionType(
            SimpleType.BOOL, ImmutableList.of(SimpleType.STRING, SimpleType.INT)),
        "function<bool,string,int>"),
    NESTED_COMPLEX(
        MapType.create(
            SimpleType.STRING, ListType.create(OptionalType.create(TypeParamType.create("V")))),
        "map<string,list<optional_type<~V>>>");

    private final CelType type;
    private final String expectedSignature;

    RoundTripTypeTestCase(CelType type, String expectedSignature) {
      this.type = type;
      this.expectedSignature = expectedSignature;
    }
  }

  @Test
  public void makeAndParseTypeSignature_roundTrip(@TestParameter RoundTripTypeTestCase testCase) {
    String signature = makeTypeSignature(testCase.type);
    assertThat(signature).isEqualTo(testCase.expectedSignature);
    assertThat(parseTypeSignature(signature)).isEqualTo(testCase.type);
  }

  @Test
  public void makeTypeSignature_structAndEnum() {
    CelType structType = StructTypeReference.create("cel.expr.conformance.proto3.TestAllTypes");
    CelType enumType =
        EnumType.create(
            "cel.expr.conformance.proto3.GlobalEnum", ImmutableMap.of("GOO", 0, "BAR", 1));

    assertThat(makeTypeSignature(structType)).isEqualTo("cel.expr.conformance.proto3.TestAllTypes");
    assertThat(makeTypeSignature(enumType)).isEqualTo("cel.expr.conformance.proto3.GlobalEnum");
  }

  @Test
  public void makeTypeSignature_unparameterizedListAndFunction() {
    assertThat(makeTypeSignature(ListType.create())).isEqualTo("list<dyn>");
    assertThat(makeTypeSignature(OpaqueType.create("function"))).isEqualTo("function<dyn>");
  }

  @Test
  public void makeTypeSignature_unsupportedTypes_throws() {
    assertThrows(IllegalArgumentException.class, () -> makeTypeSignature(SimpleType.ERROR));
    assertThrows(IllegalArgumentException.class, () -> makeTypeSignature(UnspecifiedType.create()));
  }

  @Test
  public void parseTypeSignature_wellKnownAliasesAndDefaults() {
    assertThat(parseTypeSignature("null_type")).isEqualTo(SimpleType.NULL_TYPE);
    assertThat(parseTypeSignature("google.protobuf.Any")).isEqualTo(SimpleType.ANY);
    assertThat(parseTypeSignature("google.protobuf.Timestamp")).isEqualTo(SimpleType.TIMESTAMP);
    assertThat(parseTypeSignature("google.protobuf.Duration")).isEqualTo(SimpleType.DURATION);
    assertThat(parseTypeSignature("google.protobuf.Value")).isEqualTo(SimpleType.DYN);
    assertThat(parseTypeSignature("google.protobuf.BoolValue"))
        .isEqualTo(NullableType.create(SimpleType.BOOL));
    assertThat(parseTypeSignature("google.protobuf.Int64Value"))
        .isEqualTo(NullableType.create(SimpleType.INT));
    assertThat(parseTypeSignature("google.protobuf.Int32Value"))
        .isEqualTo(NullableType.create(SimpleType.INT));
    assertThat(parseTypeSignature("google.protobuf.UInt64Value"))
        .isEqualTo(NullableType.create(SimpleType.UINT));
    assertThat(parseTypeSignature("google.protobuf.UInt32Value"))
        .isEqualTo(NullableType.create(SimpleType.UINT));
    assertThat(parseTypeSignature("google.protobuf.DoubleValue"))
        .isEqualTo(NullableType.create(SimpleType.DOUBLE));
    assertThat(parseTypeSignature("google.protobuf.FloatValue"))
        .isEqualTo(NullableType.create(SimpleType.DOUBLE));
    assertThat(parseTypeSignature("google.protobuf.StringValue"))
        .isEqualTo(NullableType.create(SimpleType.STRING));
    assertThat(parseTypeSignature("google.protobuf.BytesValue"))
        .isEqualTo(NullableType.create(SimpleType.BYTES));
    assertThat(parseTypeSignature("google.protobuf.ListValue"))
        .isEqualTo(ListType.create(SimpleType.DYN));
    assertThat(parseTypeSignature("google.protobuf.Struct"))
        .isEqualTo(MapType.create(SimpleType.STRING, SimpleType.DYN));

    // Parameterless composite types default to dyn
    assertThat(parseTypeSignature("list")).isEqualTo(ListType.create(SimpleType.DYN));
    assertThat(parseTypeSignature("map"))
        .isEqualTo(
            MapType.create(SimpleType.STRING, SimpleType.DYN)
                .withParameters(ImmutableList.of(SimpleType.DYN, SimpleType.DYN)));
    assertThat(parseTypeSignature("type")).isEqualTo(TypeType.create(SimpleType.DYN));
    assertThat(parseTypeSignature("function"))
        .isEqualTo(CelTypes.createFunctionType(SimpleType.DYN, ImmutableList.of()));
  }

  @Test
  public void parseTypeSignature_withTypeProvider() {
    CelType structType = StructTypeReference.create("my.pkg.MyMessage");
    CelType genericOpaque = OpaqueType.create("my.pkg.Box", TypeParamType.create("T"));
    CelTypeProvider provider =
        new CelTypeProvider() {
          @Override
          public ImmutableList<CelType> types() {
            return ImmutableList.of(structType, genericOpaque);
          }

          @Override
          public Optional<CelType> findType(String typeName) {
            if (typeName.equals("my.pkg.MyMessage")) {
              return Optional.of(structType);
            }
            if (typeName.equals("my.pkg.Box")) {
              return Optional.of(genericOpaque);
            }
            return Optional.empty();
          }
        };

    assertThat(parseTypeSignature("my.pkg.MyMessage", provider)).isEqualTo(structType);
    assertThat(parseTypeSignature("my.pkg.Box<int>", provider))
        .isEqualTo(OpaqueType.create("my.pkg.Box", SimpleType.INT));
    assertThat(parseTypeSignature("my.pkg.Unknown", provider))
        .isEqualTo(OpaqueType.create("my.pkg.Unknown"));

    IllegalArgumentException e =
        assertThrows(
            IllegalArgumentException.class,
            () -> parseTypeSignature("my.pkg.MyMessage<int>", provider));
    assertThat(e).hasMessageThat().contains("cannot have type parameters");
  }

  @Test
  @TestParameters({
    "{signature: '', expectedError: 'Empty type signature'}",
    "{signature: '   ', expectedError: 'Empty type signature'}",
    "{signature: '~', expectedError: 'Empty type parameter name'}",
    "{signature: '~a<b', expectedError: 'Invalid type parameter name: a<b'}",
    "{signature: '~a,b', expectedError: 'Invalid type parameter name: a,b'}",
    "{signature: 'list<>', expectedError: 'Empty type parameters in signature: list<>'}",
    "{signature: 'list<int', expectedError: \"Invalid type signature, missing closing '>':"
        + " list<int\"}",
    "{signature: 'list<int>>', expectedError: 'Invalid type parameters: mismatched brackets'}",
    "{signature: 'list<<int>', expectedError: 'Invalid type parameters: mismatched brackets'}",
    "{signature: 'list<int>extra', "
        + "expectedError: \"Invalid type signature, missing closing '>': list<int>extra\"}",
    "{signature: 'list<int, string>', "
        + "expectedError: 'Invalid type signature: list expected at most 1 parameter'}",
    "{signature: 'map<int>', "
        + "expectedError: 'Invalid type signature: map expected 0 or 2 parameters'}",
    "{signature: 'map<int, string, bool>', "
        + "expectedError: 'Invalid type signature: map expected 0 or 2 parameters'}",
    "{signature: 'type<int, string>', "
        + "expectedError: 'Invalid type signature: type expected at most 1 parameter'}",
    "{signature: 'optional_type', "
        + "expectedError: 'Invalid type signature: optional_type expected 1 parameter'}",
    "{signature: 'optional_type<int, string>', "
        + "expectedError: 'Invalid type signature: optional_type expected 1 parameter'}",
    "{signature: 'int<string>', "
        + "expectedError: 'Invalid type signature: int cannot have type parameters'}",
    "{signature: 'int_wrapper<string>', "
        + "expectedError: 'Invalid type signature: int_wrapper cannot have type parameters'}",
    "{signature: 'foo..bar', expectedError: 'Invalid type name: foo..bar'}",
    "{signature: '<int>', expectedError: 'Invalid type name: '}",
  })
  public void parseTypeSignature_invalid_throws(String signature, String expectedError) {
    IllegalArgumentException e =
        assertThrows(IllegalArgumentException.class, () -> parseTypeSignature(signature));
    assertThat(e).hasMessageThat().isEqualTo(expectedError);
  }

  @Test
  public void parseTypeSignature_recursionLimit() {
    StringBuilder valid = new StringBuilder();
    for (int i = 0; i < 64; i++) {
      valid.append("list<");
    }
    valid.append("int");
    for (int i = 0; i < 64; i++) {
      valid.append(">");
    }
    assertThat(parseTypeSignature(valid.toString())).isNotNull();

    String exceeded = "list<" + valid + ">";
    IllegalArgumentException e =
        assertThrows(IllegalArgumentException.class, () -> parseTypeSignature(exceeded));
    assertThat(e).hasMessageThat().contains("Exceeded maximum recursion depth");
  }

  @Test
  public void makeAndParseFunctionSignature_roundTrip() {
    // Global no-arg function
    String globalNoArg = makeOverloadSignature("now", /* isMember= */ false);
    assertThat(globalNoArg).isEqualTo("now()");
    ParsedFunctionOverload parsedGlobalNoArg = parseFunctionSignature(globalNoArg);
    assertThat(parsedGlobalNoArg.functionName()).isEqualTo("now");
    assertThat(parsedGlobalNoArg.isMember()).isFalse();
    assertThat(parsedGlobalNoArg.argTypes()).isEmpty();
    assertThat(parsedGlobalNoArg.signatureType())
        .isEqualTo(CelTypes.createFunctionType(SimpleType.DYN, ImmutableList.of()));

    // Global multi-arg function
    String globalMultiArg =
        makeOverloadSignature(
            "add", /* isMember= */ false, SimpleType.INT, ListType.create(SimpleType.STRING));
    assertThat(globalMultiArg).isEqualTo("add(int,list<string>)");
    ParsedFunctionOverload parsedGlobalMultiArg =
        parseFunctionSignature(" add ( int , list < string > ) ");
    assertThat(parsedGlobalMultiArg.functionName()).isEqualTo("add");
    assertThat(parsedGlobalMultiArg.isMember()).isFalse();
    assertThat(parsedGlobalMultiArg.argTypes())
        .containsExactly(SimpleType.INT, ListType.create(SimpleType.STRING))
        .inOrder();

    // Member function with parameterized receiver
    String memberSig =
        makeOverloadSignature(
            "get",
            /* isMember= */ true,
            MapType.create(TypeParamType.create("K"), TypeParamType.create("V")),
            TypeParamType.create("K"));
    assertThat(memberSig).isEqualTo("map<~K,~V>.get(~K)");
    ParsedFunctionOverload parsedMember = parseFunctionSignature(memberSig);
    assertThat(parsedMember.functionName()).isEqualTo("get");
    assertThat(parsedMember.isMember()).isTrue();
    assertThat(parsedMember.argTypes())
        .containsExactly(
            MapType.create(TypeParamType.create("K"), TypeParamType.create("V")),
            TypeParamType.create("K"))
        .inOrder();

    // Global function with dot in name (escaped)
    String namespacedGlobal =
        makeOverloadSignature(
            "math.greatest", /* isMember= */ false, SimpleType.INT, SimpleType.INT);
    assertThat(namespacedGlobal).isEqualTo("math\\.greatest(int,int)");
    ParsedFunctionOverload parsedNamespaced = parseFunctionSignature(namespacedGlobal);
    assertThat(parsedNamespaced.functionName()).isEqualTo("math.greatest");
    assertThat(parsedNamespaced.isMember()).isFalse();
    assertThat(parsedNamespaced.argTypes())
        .containsExactly(SimpleType.INT, SimpleType.INT)
        .inOrder();
  }

  @Test
  public void makeOverloadSignature_memberWithNoArgs_throws() {
    assertThrows(
        IllegalArgumentException.class, () -> makeOverloadSignature("size", /* isMember= */ true));
  }

  @Test
  @TestParameters({
    "{signature: '', expectedError: 'Empty function signature'}",
    "{signature: 'foo', expectedError: \"Invalid function signature, missing '(': foo\"}",
    "{signature: 'foo(int', "
        + "expectedError: \"Invalid function signature, missing closing ')': foo(int\"}",
    "{signature: 'foo(int)extra', "
        + "expectedError: \"Invalid function signature, missing closing ')': foo(int)extra\"}",
    "{signature: '(int)', expectedError: 'Invalid function signature: empty function name'}",
    "{signature: 'string.(int)', "
        + "expectedError: 'Invalid function signature: empty function name'}",
    "{signature: 'list<int.foo()', "
        + "expectedError: 'Invalid function signature: mismatched brackets'}",
    "{signature: 'foo(list<int)', "
        + "expectedError: 'Invalid function arguments: mismatched brackets'}",
  })
  public void parseFunctionSignature_invalid_throws(String signature, String expectedError) {
    IllegalArgumentException e =
        assertThrows(IllegalArgumentException.class, () -> parseFunctionSignature(signature));
    assertThat(e).hasMessageThat().isEqualTo(expectedError);
  }
}
