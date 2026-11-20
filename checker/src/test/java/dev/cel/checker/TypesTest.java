// Copyright 2023 Google LLC
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

package dev.cel.checker;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import dev.cel.expr.Type;
import dev.cel.expr.Type.PrimitiveType;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.protobuf.Duration;
import com.google.protobuf.Timestamp;
import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.CelContainer;
import dev.cel.common.CelFunctionDecl;
import dev.cel.common.CelOverloadDecl;
import dev.cel.common.CelValidationException;
import dev.cel.common.types.CelKind;
import dev.cel.common.types.CelProtoTypes;
import dev.cel.common.types.CelType;
import dev.cel.common.types.EnumType;
import dev.cel.common.types.ListType;
import dev.cel.common.types.MapType;
import dev.cel.common.types.NullableType;
import dev.cel.common.types.OpaqueType;
import dev.cel.common.types.OptionalType;
import dev.cel.common.types.SimpleType;
import dev.cel.common.types.StructType;
import dev.cel.common.types.StructTypeReference;
import dev.cel.common.types.TypeParamType;
import dev.cel.common.types.TypeType;
import dev.cel.compiler.CelCompiler;
import dev.cel.compiler.CelCompilerFactory;
import dev.cel.expr.conformance.proto3.TestAllTypes;
import dev.cel.extensions.CelOptionalLibrary;
import dev.cel.parser.CelStandardMacro;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(TestParameterInjector.class)
public class TypesTest {

  private static final CelType CUSTOM_INT = new CustomCelType();
  private static final CelType NULLABLE_INT = NullableType.create(SimpleType.INT);
  private static final CelCompiler CEL_COMPILER = newCelCompiler();

  @Test
  public void isAssignable_usingProtoTypes() {
    Map<Type, Type> subs = new HashMap<>();
    Type typeParamA = CelProtoTypes.createTypeParam("A");
    Type stringType = CelProtoTypes.create(PrimitiveType.STRING);

    Map<Type, Type> result = Types.isAssignable(subs, typeParamA, stringType);

    assertThat(result).containsExactly(typeParamA, stringType);
  }

  @Test
  public void isAssignable_usingCustomTypes() {
    Map<CelType, CelType> subs = new HashMap<>();
    CelType intType = SimpleType.INT;
    CelType customType = new CustomCelType();

    // A curated example where a CEL's int type can be assigned to a custom type.
    assertThat(Types.isAssignable(subs, intType, customType)).isEqualTo(subs);
    // But not the other way around.
    assertThat(Types.isAssignable(subs, customType, intType)).isNull();
  }

  @Test
  public void isAssignable_typeType_concreteTypes_legacyCoassignability() {
    Map<CelType, CelType> subs = new HashMap<>();
    CelType intType = TypeType.create(SimpleType.INT);
    CelType stringType = TypeType.create(SimpleType.STRING);

    Map<CelType, CelType> result1 = Types.isAssignable(subs, intType, stringType);
    Map<CelType, CelType> result2 = Types.isAssignable(subs, stringType, intType);

    // Concrete types are coassignable in CEL (e.g. for equality comparison type(1) == type("a"))
    assertThat(result1).isEmpty();
    assertThat(result2).isEmpty();
  }

  @Test
  public void isAssignable_typeType_mapContainerErasure() {
    Map<CelType, CelType> subs = new HashMap<>();
    CelType mapIntUint = TypeType.create(MapType.create(SimpleType.INT, SimpleType.UINT));
    CelType mapDynDyn = TypeType.create(MapType.create(SimpleType.DYN, SimpleType.DYN));

    Map<CelType, CelType> result = Types.isAssignable(subs, mapIntUint, mapDynDyn);

    // type({1: 2u}) == map
    assertThat(result).isEmpty();
  }

  @Test
  public void isAssignable_typeType_listContainerErasure() {
    Map<CelType, CelType> subs = new HashMap<>();
    CelType listInt = TypeType.create(ListType.create(SimpleType.INT));
    CelType listDyn = TypeType.create(ListType.create(SimpleType.DYN));

    Map<CelType, CelType> result = Types.isAssignable(subs, listInt, listDyn);

    // type([1]) == list
    assertThat(result).isEmpty();
  }

  @Test
  public void isAssignable_typeType_typeParamTarget_bindsConcreteType() {
    Map<CelType, CelType> subs = new HashMap<>();
    TypeParamType typeParamT = TypeParamType.create("T");
    CelType fromType = TypeType.create(SimpleType.INT);
    CelType toType = TypeType.create(typeParamT);

    Map<CelType, CelType> result = Types.isAssignable(subs, fromType, toType);

    assertThat(result).containsExactly(typeParamT, SimpleType.INT);
  }

  @Test
  public void isAssignable_typeType_typeParamSource_bindsConcreteType() {
    Map<CelType, CelType> subs = new HashMap<>();
    TypeParamType typeParamT = TypeParamType.create("T");
    CelType fromType = TypeType.create(typeParamT);
    CelType toType = TypeType.create(SimpleType.INT);

    Map<CelType, CelType> result = Types.isAssignable(subs, fromType, toType);

    assertThat(result).containsExactly(typeParamT, SimpleType.INT);
  }

  @Test
  public void isAssignable_typeType_nestedTypeParam_unifies() {
    Map<CelType, CelType> subs = new HashMap<>();
    TypeParamType typeParamT = TypeParamType.create("T");
    TypeParamType typeParamR = TypeParamType.create("R");
    CelType fromType = TypeType.create(typeParamT);
    CelType toType = TypeType.create(TypeType.create(typeParamR));

    // type(T) == type(type(R))
    Map<CelType, CelType> result = Types.isAssignable(subs, fromType, toType);

    assertThat(result).containsExactly(typeParamT, TypeType.create(typeParamR));
  }

  @Test
  public void isAssignable_typeType_deeplyNestedTypeParam_bindsConcreteType() {
    Map<CelType, CelType> subs = new HashMap<>();
    TypeParamType typeParamT = TypeParamType.create("T");
    CelType fromType = TypeType.create(TypeType.create(SimpleType.INT));
    CelType toType = TypeType.create(TypeType.create(typeParamT));

    Map<CelType, CelType> result = Types.isAssignable(subs, fromType, toType);

    assertThat(result).containsExactly(typeParamT, SimpleType.INT);
  }

  @Test
  public void isAssignable_typeType_compositeListTypeParam_bindsConcreteType() {
    Map<CelType, CelType> subs = new HashMap<>();
    TypeParamType typeParamT = TypeParamType.create("T");
    CelType fromType = TypeType.create(ListType.create(SimpleType.INT));
    CelType toType = TypeType.create(ListType.create(typeParamT));

    Map<CelType, CelType> result = Types.isAssignable(subs, fromType, toType);

    assertThat(result).containsExactly(typeParamT, SimpleType.INT);
  }

  @Test
  public void isAssignable_typeType_compositeMapTypeParam_bindsConcreteTypes() {
    Map<CelType, CelType> subs = new HashMap<>();
    TypeParamType typeParamK = TypeParamType.create("K");
    TypeParamType typeParamV = TypeParamType.create("V");
    CelType fromType = TypeType.create(MapType.create(SimpleType.STRING, SimpleType.INT));
    CelType toType = TypeType.create(MapType.create(typeParamK, typeParamV));

    Map<CelType, CelType> result = Types.isAssignable(subs, fromType, toType);

    assertThat(result).containsExactly(typeParamK, SimpleType.STRING, typeParamV, SimpleType.INT);
  }

  @Test
  public void isAssignable_typeType_nullableTypeParam_unifies() {
    Map<CelType, CelType> subs = new HashMap<>();
    TypeParamType typeParamT = TypeParamType.create("T");
    CelType fromType = TypeType.create(NullableType.create(SimpleType.INT));
    CelType toType = TypeType.create(NullableType.create(typeParamT));

    Map<CelType, CelType> result = Types.isAssignable(subs, fromType, toType);

    assertThat(result)
        .containsExactly(NullableType.create(typeParamT), NullableType.create(SimpleType.INT));
  }

  @Test
  public void isAssignable_typeType_optionalTypeParam_unifies() {
    Map<CelType, CelType> subs = new HashMap<>();
    TypeParamType typeParamT = TypeParamType.create("T");
    CelType fromType = TypeType.create(OptionalType.create(SimpleType.INT));
    CelType toType = TypeType.create(OptionalType.create(typeParamT));

    Map<CelType, CelType> result = Types.isAssignable(subs, fromType, toType);

    assertThat(result).containsExactly(typeParamT, SimpleType.INT);
  }

  @Test
  public void isAssignable_typeType_incompatibleTypeParams_returnsNull() {
    Map<CelType, CelType> subs = new HashMap<>();
    TypeParamType typeParamT = TypeParamType.create("T");
    CelType fromType = TypeType.create(ListType.create(typeParamT));
    CelType toType = TypeType.create(SimpleType.INT);

    Map<CelType, CelType> result = Types.isAssignable(subs, fromType, toType);

    assertThat(result).isNull();
  }

  @Test
  public void isAssignable_typeType_conflictingBoundTypeParam_returnsNull() {
    Map<CelType, CelType> subs = new HashMap<>();
    TypeParamType typeParamT = TypeParamType.create("T");
    subs.put(typeParamT, SimpleType.STRING);
    CelType fromType = TypeType.create(typeParamT);
    CelType toType = TypeType.create(SimpleType.INT);

    Map<CelType, CelType> result = Types.isAssignable(subs, fromType, toType);

    assertThat(result).isNull();
  }

  @Test
  public void isAssignable_typeType_occursCheck_failsOnSelfReference() {
    Map<CelType, CelType> subs = new HashMap<>();
    TypeParamType typeParamT = TypeParamType.create("T");
    CelType fromType = TypeType.create(typeParamT);
    CelType toType = TypeType.create(TypeType.create(typeParamT));

    // Occurs check: T = type(T) is cyclic and must fail
    Map<CelType, CelType> result = Types.isAssignable(subs, fromType, toType);

    assertThat(result).isNull();
  }

  @Test
  public void isAssignable_typeType_occursCheck_failsOnTransitiveCycle() {
    Map<CelType, CelType> subs = new HashMap<>();
    TypeParamType typeParamT = TypeParamType.create("T");
    TypeParamType typeParamR = TypeParamType.create("R");
    subs.put(typeParamT, TypeType.create(typeParamR));
    // Trying to assign type(R) to type(T) would produce R = type(R) transitively through T
    CelType fromType = TypeType.create(typeParamR);
    CelType toType = TypeType.create(TypeType.create(typeParamT));

    Map<CelType, CelType> result = Types.isAssignable(subs, fromType, toType);

    assertThat(result).isNull();
  }

  @Test
  public void isAssignable_typeType_occursCheck_mapTypeParam_to_typeParam() {
    Map<CelType, CelType> subs = new HashMap<>();
    TypeParamType typeParamT = TypeParamType.create("T");
    CelType fromType = TypeType.create(MapType.create(SimpleType.STRING, typeParamT));
    CelType toType = TypeType.create(typeParamT);

    Map<CelType, CelType> result = Types.isAssignable(subs, fromType, toType);

    assertThat(result).isNull();
  }

  @Test
  public void isAssignable_typeType_occursCheck_typeParam_to_mapTypeParam() {
    Map<CelType, CelType> subs = new HashMap<>();
    TypeParamType typeParamT = TypeParamType.create("T");
    CelType fromType = TypeType.create(typeParamT);
    CelType toType = TypeType.create(MapType.create(SimpleType.STRING, typeParamT));

    Map<CelType, CelType> result = Types.isAssignable(subs, fromType, toType);

    assertThat(result).isNull();
  }

  @Test
  public void isAssignable_typeType_occursCheck_mapTypeParamInKey_to_typeParam() {
    Map<CelType, CelType> subs = new HashMap<>();
    TypeParamType typeParamT = TypeParamType.create("T");
    CelType fromType = TypeType.create(MapType.create(typeParamT, SimpleType.STRING));
    CelType toType = TypeType.create(typeParamT);

    Map<CelType, CelType> result = Types.isAssignable(subs, fromType, toType);

    assertThat(result).isNull();
  }

  @Test
  public void isAssignable_typeType_occursCheck_listTypeParam_to_typeParam() {
    Map<CelType, CelType> subs = new HashMap<>();
    TypeParamType typeParamT = TypeParamType.create("T");
    CelType fromType = TypeType.create(ListType.create(typeParamT));
    CelType toType = TypeType.create(typeParamT);

    Map<CelType, CelType> result = Types.isAssignable(subs, fromType, toType);

    assertThat(result).isNull();
  }

  @Test
  public void isAssignable_typeType_occursCheck_optionalTypeParam_to_typeParam() {
    Map<CelType, CelType> subs = new HashMap<>();
    TypeParamType typeParamT = TypeParamType.create("T");
    CelType fromType = TypeType.create(OptionalType.create(typeParamT));
    CelType toType = TypeType.create(typeParamT);

    Map<CelType, CelType> result = Types.isAssignable(subs, fromType, toType);

    assertThat(result).isNull();
  }

  @Test
  public void compiler_typeParamInTypeType_resolvesReturnTypeInt() throws Exception {
    TypeParamType typeParamT = TypeParamType.create("T");
    CelCompiler celCompiler =
        CelCompilerFactory.standardCelCompilerBuilder()
            .addFunctionDeclarations(
                CelFunctionDecl.newFunctionDeclaration(
                    "cast",
                    CelOverloadDecl.newGlobalOverload(
                        "cast_t", typeParamT, SimpleType.DYN, TypeType.create(typeParamT))))
            .build();

    CelAbstractSyntaxTree ast = celCompiler.compile("cast('hello', int)").getAst();

    assertThat(ast.getResultType()).isEqualTo(SimpleType.INT);
  }

  @Test
  public void compiler_typeParamInTypeType_resolvesReturnTypeString() throws Exception {
    TypeParamType typeParamT = TypeParamType.create("T");
    CelCompiler celCompiler =
        CelCompilerFactory.standardCelCompilerBuilder()
            .addFunctionDeclarations(
                CelFunctionDecl.newFunctionDeclaration(
                    "cast",
                    CelOverloadDecl.newGlobalOverload(
                        "cast_t", typeParamT, SimpleType.DYN, TypeType.create(typeParamT))))
            .build();

    CelAbstractSyntaxTree ast = celCompiler.compile("cast(123, string)").getAst();

    assertThat(ast.getResultType()).isEqualTo(SimpleType.STRING);
  }

  private enum WellKnownTypeIdentTestCase {
    DURATION_QUALIFIED("google.protobuf.Duration", TypeType.create(SimpleType.DURATION)),
    DURATION_LEADING_DOT(".google.protobuf.Duration", TypeType.create(SimpleType.DURATION)),
    DURATION_UNQUALIFIED("Duration", TypeType.create(SimpleType.DURATION)),
    TIMESTAMP_QUALIFIED("google.protobuf.Timestamp", TypeType.create(SimpleType.TIMESTAMP)),
    TIMESTAMP_LEADING_DOT(".google.protobuf.Timestamp", TypeType.create(SimpleType.TIMESTAMP)),
    TIMESTAMP_UNQUALIFIED("Timestamp", TypeType.create(SimpleType.TIMESTAMP));

    private final String expression;
    private final CelType expectedType;

    WellKnownTypeIdentTestCase(String expression, CelType expectedType) {
      this.expression = expression;
      this.expectedType = expectedType;
    }
  }

  @Test
  public void compiler_wellKnownProtoTypeIdent_resolvesToSimpleType(
      @TestParameter WellKnownTypeIdentTestCase testCase) throws Exception {
    CelCompiler celCompiler =
        CelCompilerFactory.standardCelCompilerBuilder()
            .addMessageTypes(Duration.getDescriptor(), Timestamp.getDescriptor())
            .setContainer(CelContainer.ofName("google.protobuf"))
            .build();

    CelAbstractSyntaxTree ast = celCompiler.compile(testCase.expression).getAst();

    assertThat(ast.getResultType()).isEqualTo(testCase.expectedType);
  }

  private enum WellKnownTypeParamTestCase {
    DURATION_QUALIFIED_CAST("cast('1h', google.protobuf.Duration)", SimpleType.DURATION),
    DURATION_QUALIFIED_EQUALITY(
        "cast('1h', google.protobuf.Duration) == duration('1h')", SimpleType.BOOL),
    DURATION_UNQUALIFIED_COMPARISON("cast('1h', Duration) > duration('0s')", SimpleType.BOOL),
    TIMESTAMP_QUALIFIED_CAST("cast(0, google.protobuf.Timestamp)", SimpleType.TIMESTAMP),
    TIMESTAMP_QUALIFIED_EQUALITY(
        "cast(0, google.protobuf.Timestamp) == timestamp(0)", SimpleType.BOOL),
    TIMESTAMP_UNQUALIFIED_COMPARISON("cast(0, Timestamp) > timestamp(0)", SimpleType.BOOL);

    private final String expression;
    private final CelType expectedType;

    WellKnownTypeParamTestCase(String expression, CelType expectedType) {
      this.expression = expression;
      this.expectedType = expectedType;
    }
  }

  @Test
  public void compiler_typeParamInTypeType_withWellKnownProto_resolvesWellKnownOverloads(
      @TestParameter WellKnownTypeParamTestCase testCase) throws Exception {
    TypeParamType typeParamT = TypeParamType.create("T");
    CelCompiler celCompiler =
        CelCompilerFactory.standardCelCompilerBuilder()
            .addMessageTypes(Duration.getDescriptor(), Timestamp.getDescriptor())
            .setContainer(CelContainer.ofName("google.protobuf"))
            .addFunctionDeclarations(
                CelFunctionDecl.newFunctionDeclaration(
                    "cast",
                    CelOverloadDecl.newGlobalOverload(
                        "cast_t", typeParamT, SimpleType.DYN, TypeType.create(typeParamT))))
            .build();

    CelAbstractSyntaxTree ast = celCompiler.compile(testCase.expression).getAst();

    assertThat(ast.getResultType()).isEqualTo(testCase.expectedType);
  }

  @Test
  public void compiler_durationIdent_withoutMessageTypes_resolvesToSimpleType() throws Exception {
    CelCompiler celCompiler = CelCompilerFactory.standardCelCompilerBuilder().build();

    CelAbstractSyntaxTree ast = celCompiler.compile("google.protobuf.Duration").getAst();

    assertThat(ast.getResultType()).isEqualTo(TypeType.create(SimpleType.DURATION));
  }

  @Test
  public void compiler_timestampIdent_withoutMessageTypes_resolvesToSimpleType() throws Exception {
    CelCompiler celCompiler = CelCompilerFactory.standardCelCompilerBuilder().build();

    CelAbstractSyntaxTree ast = celCompiler.compile("google.protobuf.Timestamp").getAst();

    assertThat(ast.getResultType()).isEqualTo(TypeType.create(SimpleType.TIMESTAMP));
  }

  @Test
  public void compiler_durationStructCreation_withDescriptor_success() throws Exception {
    CelCompiler celCompiler =
        CelCompilerFactory.standardCelCompilerBuilder()
            .addMessageTypes(Duration.getDescriptor())
            .build();

    CelAbstractSyntaxTree ast =
        celCompiler.compile("google.protobuf.Duration{seconds: 10, nanos: 20}").getAst();

    assertThat(ast.getResultType()).isEqualTo(SimpleType.DURATION);
  }

  @Test
  public void compiler_timestampStructCreation_withDescriptor_success() throws Exception {
    CelCompiler celCompiler =
        CelCompilerFactory.standardCelCompilerBuilder()
            .addMessageTypes(Timestamp.getDescriptor())
            .build();

    CelAbstractSyntaxTree ast =
        celCompiler.compile("google.protobuf.Timestamp{seconds: 100, nanos: 200}").getAst();

    assertThat(ast.getResultType()).isEqualTo(SimpleType.TIMESTAMP);
  }

  @Test
  public void compiler_durationStructCreation_emptyFields_success() throws Exception {
    CelCompiler celCompiler = CelCompilerFactory.standardCelCompilerBuilder().build();

    CelAbstractSyntaxTree ast = celCompiler.compile("google.protobuf.Duration{}").getAst();

    assertThat(ast.getResultType()).isEqualTo(SimpleType.DURATION);
  }

  @Test
  public void compiler_timestampStructCreation_emptyFields_success() throws Exception {
    CelCompiler celCompiler = CelCompilerFactory.standardCelCompilerBuilder().build();

    CelAbstractSyntaxTree ast = celCompiler.compile("google.protobuf.Timestamp{}").getAst();

    assertThat(ast.getResultType()).isEqualTo(SimpleType.TIMESTAMP);
  }

  @Test
  public void compiler_durationStructCreation_withoutDescriptor_throws() {
    CelCompiler celCompiler = CelCompilerFactory.standardCelCompilerBuilder().build();

    CelValidationException e =
        assertThrows(
            CelValidationException.class,
            () -> celCompiler.compile("google.protobuf.Duration{seconds: 10}").getAst());

    assertThat(e)
        .hasMessageThat()
        .contains(
            "Message type resolution failure while referencing field 'seconds'."
                + " Ensure that the descriptor for type 'google.protobuf.Duration' was added to the"
                + " environment");
  }

  @Test
  public void compiler_timestampStructCreation_withoutDescriptor_throws() {
    CelCompiler celCompiler = CelCompilerFactory.standardCelCompilerBuilder().build();

    CelValidationException e =
        assertThrows(
            CelValidationException.class,
            () -> celCompiler.compile("google.protobuf.Timestamp{seconds: 10}").getAst());

    assertThat(e)
        .hasMessageThat()
        .contains(
            "Message type resolution failure while referencing field 'seconds'. Ensure that the"
                + " descriptor for type 'google.protobuf.Timestamp' was added to the environment");
  }

  @Test
  public void compiler_durationStructCreation_typeMismatch_throws() {
    CelCompiler celCompiler =
        CelCompilerFactory.standardCelCompilerBuilder()
            .addMessageTypes(Duration.getDescriptor())
            .build();

    CelValidationException e =
        assertThrows(
            CelValidationException.class,
            () -> celCompiler.compile("google.protobuf.Duration{seconds: 'bad'}").getAst());

    assertThat(e)
        .hasMessageThat()
        .contains("expected type of field 'seconds' is 'int' but provided type is 'string'");
  }

  @Test
  public void compiler_timestampStructCreation_typeMismatch_throws() {
    CelCompiler celCompiler =
        CelCompilerFactory.standardCelCompilerBuilder()
            .addMessageTypes(Timestamp.getDescriptor())
            .build();

    CelValidationException e =
        assertThrows(
            CelValidationException.class,
            () -> celCompiler.compile("google.protobuf.Timestamp{seconds: 'bad'}").getAst());

    assertThat(e)
        .hasMessageThat()
        .contains("expected type of field 'seconds' is 'int' but provided type is 'string'");
  }

  @Test
  public void compiler_structCreation_primitiveType_throws() {
    CelCompiler celCompiler = CelCompilerFactory.standardCelCompilerBuilder().build();

    CelValidationException e =
        assertThrows(CelValidationException.class, () -> celCompiler.compile("int{}").getAst());

    assertThat(e).hasMessageThat().contains("'int' is not a message type");
  }

  @Test
  public void compiler_typeParamInCompositeTypeType_resolvesReturnType() throws Exception {
    CelAbstractSyntaxTree ast = CEL_COMPILER.compile("first_elem_type('data', type([1]))").getAst();

    assertThat(ast.getResultType()).isEqualTo(SimpleType.INT);
  }

  @Test
  public void compiler_typeComparison_mapType_succeeds() throws Exception {
    CelCompiler celCompiler = CelCompilerFactory.standardCelCompilerBuilder().build();

    CelAbstractSyntaxTree ast = celCompiler.compile("type({}) == map").getAst();

    assertThat(ast.getResultType()).isEqualTo(SimpleType.BOOL);
  }

  @Test
  public void compiler_typeComparison_compositeTypes_succeeds() throws Exception {
    CelCompiler celCompiler = CelCompilerFactory.standardCelCompilerBuilder().build();

    CelAbstractSyntaxTree ast =
        celCompiler.compile("list == type([1]) && map == type({1:2u})").getAst();

    assertThat(ast.getResultType()).isEqualTo(SimpleType.BOOL);
  }

  @Test
  public void compiler_typeComparison_differentTypesEqual_succeeds() throws Exception {
    CelCompiler celCompiler = CelCompilerFactory.standardCelCompilerBuilder().build();

    CelAbstractSyntaxTree ast = celCompiler.compile("type(1) == type('a')").getAst();

    assertThat(ast.getResultType()).isEqualTo(SimpleType.BOOL);
  }

  @Test
  public void compiler_typeComparison_differentTypesNotEqual_succeeds() throws Exception {
    CelCompiler celCompiler = CelCompilerFactory.standardCelCompilerBuilder().build();

    CelAbstractSyntaxTree ast = celCompiler.compile("type(1) != uint").getAst();

    assertThat(ast.getResultType()).isEqualTo(SimpleType.BOOL);
  }

  @Test
  public void compiler_typeComparison_type1NotEqualsType1u_succeeds() throws Exception {
    CelCompiler celCompiler = CelCompilerFactory.standardCelCompilerBuilder().build();

    CelAbstractSyntaxTree ast = celCompiler.compile("type(1) != type(1u)").getAst();

    assertThat(ast.getResultType()).isEqualTo(SimpleType.BOOL);
  }

  @Test
  public void compiler_typeParamEquality_unifiesTypeParams() throws Exception {
    TypeParamType typeParamT = TypeParamType.create("T");
    TypeParamType typeParamR = TypeParamType.create("R");
    CelCompiler celCompiler =
        CelCompilerFactory.standardCelCompilerBuilder()
            .addVar("x", TypeType.create(typeParamT))
            .addVar("y", TypeType.create(TypeType.create(typeParamR)))
            .build();

    // type(T) == type(type(R))
    CelAbstractSyntaxTree ast = celCompiler.compile("x == y").getAst();

    assertThat(ast.getResultType()).isEqualTo(SimpleType.BOOL);
  }

  private enum SoundnessTestCase {
    REPL_DYN_OPTIONAL_OR_VALUE("dyn(optional.of('foo')).orValue(1)", SimpleType.DYN),
    DYN_VAR_OR_VALUE("dyn_var.orValue(1)", SimpleType.DYN),
    DYN_VAR_OR_OPTIONAL("dyn_var.or(optional.of(1))", OptionalType.create(SimpleType.DYN)),
    OPTIONAL_OR_DYN_VAR("optional.of(1).or(dyn_var)", OptionalType.create(SimpleType.DYN)),
    OPTIONAL_NONE_OR_DYN_VAR("optional.none().or(dyn_var)", OptionalType.create(SimpleType.DYN)),
    DYN_VAR_VALUE("dyn_var.value()", SimpleType.DYN),
    LIST_CONCAT_DYN_RHS("['a'] + dyn_var", ListType.create(SimpleType.DYN)),
    LIST_CONCAT_DYN_LHS("dyn_var + ['a']", ListType.create(SimpleType.DYN)),
    TERNARY_INT_NULLABLE_INT("b ? 1 : nullable_int", NULLABLE_INT),
    TERNARY_NULLABLE_INT_INT("b ? nullable_int : 1", NULLABLE_INT),
    LIST_INT_NULLABLE_INT("[1, nullable_int]", ListType.create(NULLABLE_INT)),
    LIST_NULLABLE_INT_INT("[nullable_int, 1]", ListType.create(NULLABLE_INT)),
    TERNARY_NULL_NULLABLE_INT("b ? null : nullable_int", NULLABLE_INT),
    TERNARY_NULLABLE_INT_NULL("b ? nullable_int : null", NULLABLE_INT),
    LIST_NULL_NULLABLE_INT("[null, nullable_int]", ListType.create(NULLABLE_INT)),
    LIST_NULLABLE_INT_NULL("[nullable_int, null]", ListType.create(NULLABLE_INT)),
    TERNARY_DYN_NULLABLE_INT("b ? dyn_var : nullable_int", SimpleType.DYN),
    LIST_INT_NULLABLE_DYN("[1, nullable_int, dyn(1)]", ListType.create(SimpleType.DYN)),
    LIST_INT_NULL_WRAPPER("[1, null, msg.single_int64_wrapper]", ListType.create(SimpleType.DYN)),
    LIST_WRAPPER_INT_NULL("[msg.single_int64_wrapper, 1, null]", ListType.create(NULLABLE_INT)),
    LIST_INT_WRAPPER_NULL("[1, msg.single_int64_wrapper, null]", ListType.create(NULLABLE_INT)),
    NESTED_LIST_INT_NULL_WRAPPER(
        "[[1], [null], [msg.single_int64_wrapper]]", ListType.create(SimpleType.DYN)),
    NESTED_LIST_WRAPPER_INT_NULL(
        "[[msg.single_int64_wrapper], [1], [null]]",
        ListType.create(ListType.create(NULLABLE_INT))),
    NESTED_LIST_INT_WRAPPER_NULL(
        "[[1], [msg.single_int64_wrapper], [null]]",
        ListType.create(ListType.create(NULLABLE_INT))),
    LIST_THREE_ASYMMETRIC_MAPS(
        "[dyn_int_map, string_dyn_map, {'a': 1}]",
        ListType.create(MapType.create(SimpleType.DYN, SimpleType.DYN))),
    NARY_TYPE_PARAM_WRAPPER("pick_first(1, msg.single_int64_wrapper, null)", NULLABLE_INT),
    NARY_TYPE_PARAM_DYN("pick_first(msg.single_int64_wrapper, 1, dyn_var)", SimpleType.DYN),
    LIST_ANY_HOMOGENEOUS("[msg.single_any, msg.single_any]", ListType.create(SimpleType.ANY)),
    LIST_ANY_INT("[msg.single_any, 1]", ListType.create(SimpleType.DYN)),
    LIST_INT_ANY("[1, msg.single_any]", ListType.create(SimpleType.DYN)),
    LIST_ANY_DYN("[msg.single_any, dyn_var]", ListType.create(SimpleType.DYN)),
    LIST_DYN_ANY("[dyn_var, msg.single_any]", ListType.create(SimpleType.DYN)),
    TERNARY_ANY_ANY("b ? msg.single_any : msg.single_any", SimpleType.ANY),
    TERNARY_ANY_INT("b ? msg.single_any : 1", SimpleType.DYN),
    TERNARY_INT_ANY("b ? 1 : msg.single_any", SimpleType.DYN),
    ANY_OR_VALUE("msg.single_any.orValue(1)", SimpleType.DYN),
    TERNARY_MSG_NULL("b ? msg : null", SimpleType.DYN),
    TERNARY_NULL_MSG("b ? null : msg", SimpleType.DYN),
    LIST_MSG_NULL("[msg, null]", ListType.create(SimpleType.DYN)),
    LIST_OPTIONAL_NULL("[optional.of(1), null]", ListType.create(SimpleType.DYN)),
    TERNARY_ASYMMETRIC_MAPS_1(
        "b ? dyn_int_map : string_dyn_map", MapType.create(SimpleType.DYN, SimpleType.DYN)),
    TERNARY_ASYMMETRIC_MAPS_2(
        "b ? string_dyn_map : dyn_int_map", MapType.create(SimpleType.DYN, SimpleType.DYN)),
    TERNARY_TYPE_DECAY("b ? int : string", TypeType.create(SimpleType.DYN)),
    LIST_TYPE_DECAY("[int, string]", ListType.create(TypeType.create(SimpleType.DYN))),
    CHAINED_OR_VALUE_MAP_INDEX("dyn(optional.of([2])).orValue({0: 1})[0]", SimpleType.DYN),
    CHAINED_OR_VALUE_OPTIONAL_INDEX(
        "dyn(optional.of([2])).orValue(optional.of([1]))[0]", SimpleType.DYN),
    CHAINED_OR_VALUE_OPTINDEX(
        "dyn(optional.of(optional.of([2]))).orValue([1])[?0]", OptionalType.create(SimpleType.DYN)),
    CONCRETE_SIZE_GLOBAL("size(dyn_var)", SimpleType.INT),
    CONCRETE_SIZE_MEMBER("dyn_var.size()", SimpleType.INT),
    CONCRETE_EQUALS("dyn_var == 1", SimpleType.BOOL),
    CONCRETE_HAS("has(dyn_var.f)", SimpleType.BOOL),
    CONCRETE_HAS_VALUE("dyn_var.hasValue()", SimpleType.BOOL),
    CONCRETE_ADD_INT("dyn_var + 1", SimpleType.INT),
    LIST_OPTIONAL_NONE_AND_OF(
        "[optional.none(), optional.of(1)]", ListType.create(OptionalType.create(SimpleType.INT))),
    LIST_OPTIONAL_OF_AND_NONE(
        "[optional.of(1), optional.none()]", ListType.create(OptionalType.create(SimpleType.INT))),
    TYPE_PARAM_IN_TYPE_TYPE_DYN_ARG("first_elem_type('data', dyn(int))", SimpleType.DYN),
    DYN_PARAM_UPCAST_PRESERVES_INNER_TYPE(
        "[[], [1]].map(xs, first_elem_type(xs, type(xs)))", ListType.create(SimpleType.INT));

    private final String expression;
    private final CelType expectedType;

    SoundnessTestCase(String expression, CelType expectedType) {
      this.expression = expression;
      this.expectedType = expectedType;
    }
  }

  @Test
  public void compiler_typeDeductionSoundness(@TestParameter SoundnessTestCase testCase)
      throws Exception {
    CelAbstractSyntaxTree ast = CEL_COMPILER.compile(testCase.expression).getAst();

    assertThat(ast.getResultType()).isEqualTo(testCase.expectedType);
  }

  @Test
  public void compiler_setResultTypeDyn_preservesInferredElementTypes() throws Exception {
    CelCompiler compilerWithDynResult =
        CEL_COMPILER.toCompilerBuilder().setResultType(SimpleType.DYN).build();

    assertThat(compilerWithDynResult.compile("[[], [1]]").getAst().getResultType())
        .isEqualTo(ListType.create(ListType.create(SimpleType.INT)));
    assertThat(
            compilerWithDynResult
                .compile("[optional.none(), optional.of(1)]")
                .getAst()
                .getResultType())
        .isEqualTo(ListType.create(OptionalType.create(SimpleType.INT)));
  }

  @Test
  public void compiler_dynUpcast_doesNotWidenBoundTypeParams(
      @TestParameter({
            "[[]].exists(xs, cel.expr.conformance.proto3.TestAllTypes{single_value: xs}"
                + " != null && xs == [1] && xs[0] == 'bad')",
            "[[]].exists(xs, xs == [1] && xs == dyn_var && xs[0] == 'bad')",
            "[[]].exists(xs, xs == dyn_var && xs == [1] && xs[0] == 'bad')",
            "[[]].exists(xs, xs == msg.single_any && xs == [1] && xs[0] == 'bad')",
            "[[]].exists(xs, [dyn_var, xs].size() > 0 && xs == [1] && xs[0] == 'bad')",
            "[[]].exists(xs, [msg.single_any, xs].size() > 0 && xs == [1] && xs[0] == 'bad')",
            "[[]].exists(xs, xs == [1] && [[dyn_var], xs].size() > 0 && xs[0] == 'bad')"
          })
          String expression) {
    CelValidationException thrown =
        assertThrows(CelValidationException.class, () -> CEL_COMPILER.compile(expression).getAst());

    assertThat(thrown)
        .hasMessageThat()
        .contains("found no matching overload for '_==_' applied to '(int, string)'");
  }

  private enum MostGeneralTestCase {
    IDENTICAL_STRING(SimpleType.STRING, SimpleType.STRING, SimpleType.STRING),
    IDENTICAL_ANY(SimpleType.ANY, SimpleType.ANY, SimpleType.ANY),
    ERROR_RECOVERY(SimpleType.ERROR, SimpleType.INT, SimpleType.INT),
    ERROR_AND_ANY(SimpleType.ERROR, SimpleType.ANY, SimpleType.ANY),
    DYN_WIDENS(SimpleType.DYN, SimpleType.INT, SimpleType.DYN),
    ANY_WIDENS(SimpleType.ANY, SimpleType.INT, SimpleType.DYN),
    ANY_AND_DYN(SimpleType.ANY, SimpleType.DYN, SimpleType.DYN),
    DYN_OVERRIDES_TYPE_PARAM(SimpleType.DYN, TypeParamType.create("T"), SimpleType.DYN),
    ANY_OVERRIDES_TYPE_PARAM(SimpleType.ANY, TypeParamType.create("T"), SimpleType.DYN),
    TYPE_PARAM_PRESERVED(TypeParamType.create("T"), SimpleType.INT, TypeParamType.create("T")),
    TYPE_PARAM_AND_NULLABLE(TypeParamType.create("T"), NULLABLE_INT, TypeParamType.create("T")),
    NULLABLE_AND_PRIMITIVE(NULLABLE_INT, SimpleType.INT, NULLABLE_INT),
    NULLABLE_AND_NULL(NULLABLE_INT, SimpleType.NULL_TYPE, NULLABLE_INT),
    NULLABLE_AND_MISMATCHED_PRIMITIVE(NULLABLE_INT, SimpleType.STRING, SimpleType.DYN),
    NULLABLE_AND_NULLABLE_MISMATCH(
        NULLABLE_INT, NullableType.create(SimpleType.STRING), SimpleType.DYN),
    NULL_AND_STRUCT(SimpleType.NULL_TYPE, StructTypeReference.create("Foo"), SimpleType.DYN),
    DIFFERENT_KINDS(SimpleType.INT, SimpleType.STRING, SimpleType.DYN),
    UNPARAMETERIZED_LIST(ListType.create(), ListType.create(SimpleType.INT), SimpleType.DYN),
    NESTED_LIST_OF_MAPS(
        ListType.create(MapType.create(SimpleType.DYN, SimpleType.INT)),
        ListType.create(MapType.create(SimpleType.STRING, SimpleType.DYN)),
        ListType.create(MapType.create(SimpleType.DYN, SimpleType.DYN))),
    OPAQUE_AND_OPTIONAL(
        OpaqueType.create(OptionalType.NAME, SimpleType.DYN),
        OptionalType.create(SimpleType.INT),
        OptionalType.create(SimpleType.DYN)),
    OPAQUE_ASYMMETRIC_JOIN(
        OpaqueType.create("pair", SimpleType.DYN, SimpleType.INT),
        OpaqueType.create("pair", SimpleType.STRING, SimpleType.DYN),
        OpaqueType.create("pair", SimpleType.DYN, SimpleType.DYN)),
    OPAQUE_DIFFERENT_NAMES(
        OpaqueType.create("foo", SimpleType.INT),
        OpaqueType.create("bar", SimpleType.INT),
        SimpleType.DYN),
    OPAQUE_DIFFERENT_ARITY(
        OpaqueType.create("tuple", SimpleType.INT),
        OpaqueType.create("tuple", SimpleType.INT, SimpleType.STRING),
        SimpleType.DYN),
    OPAQUE_AND_LIST_SAME_NAME(
        OpaqueType.create("list", SimpleType.INT), ListType.create(SimpleType.INT), SimpleType.DYN),
    TYPE_DECAY(
        TypeType.create(SimpleType.INT),
        TypeType.create(SimpleType.STRING),
        TypeType.create(SimpleType.DYN)),
    STRUCT_AND_STRUCT_REF(
        StructType.create("Foo", ImmutableSet.of(), f -> Optional.empty()),
        StructTypeReference.create("Foo"),
        StructTypeReference.create("Foo")),
    STRUCT_DIFFERENT_NAMES(
        StructTypeReference.create("Foo"), StructTypeReference.create("Bar"), SimpleType.DYN),
    ENUM_AND_INT(
        EnumType.create("MyEnum", ImmutableMap.of("A", 1)), SimpleType.INT, SimpleType.INT),
    CUSTOM_INT_AND_INT(CUSTOM_INT, SimpleType.INT, CUSTOM_INT),
    INCOMPATIBLE_CUSTOM_INTS(CUSTOM_INT, new CustomCelType(), SimpleType.DYN);

    private final CelType type1;
    private final CelType type2;
    private final CelType expected;

    MostGeneralTestCase(CelType type1, CelType type2, CelType expected) {
      this.type1 = type1;
      this.type2 = type2;
      this.expected = expected;
    }
  }

  @Test
  public void mostGeneral_isCommutative(@TestParameter MostGeneralTestCase testCase) {
    CelType forward = Types.mostGeneral(testCase.type1, testCase.type2);
    CelType reverse = Types.mostGeneral(testCase.type2, testCase.type1);

    assertThat(forward).isEqualTo(testCase.expected);
    assertThat(reverse).isEqualTo(testCase.expected);
  }

  @Test
  public void isAssignable_dynWithParameterizedType_onlyWidensTargetTypeParam() {
    TypeParamType typeParamT0 = TypeParamType.create("T0");
    TypeParamType typeParamT1 = TypeParamType.create("T1");
    ImmutableMap<CelType, CelType> subs =
        ImmutableMap.of(typeParamT1, typeParamT0, typeParamT0, SimpleType.INT);

    Map<CelType, CelType> dynToTarget =
        Types.isAssignable(subs, SimpleType.DYN, OptionalType.create(typeParamT1));
    Map<CelType, CelType> upcastToDyn =
        Types.isAssignable(subs, OptionalType.create(typeParamT1), SimpleType.DYN);

    assertThat(dynToTarget)
        .containsExactly(typeParamT0, SimpleType.INT, typeParamT1, SimpleType.DYN);
    assertThat(upcastToDyn).isEqualTo(subs);
  }

  @Test
  public void isAssignable_errorWithParameterizedType_preservesUnboundTypeParam() {
    TypeParamType typeParamT = TypeParamType.create("T");

    Map<CelType, CelType> result1 =
        Types.isAssignable(ImmutableMap.of(), SimpleType.ERROR, OptionalType.create(typeParamT));
    Map<CelType, CelType> result2 =
        Types.isAssignable(ImmutableMap.of(), OptionalType.create(typeParamT), SimpleType.ERROR);

    assertThat(result1).isEmpty();
    assertThat(result2).isEmpty();
  }

  private static CelCompiler newCelCompiler() {
    TypeParamType typeParamT = TypeParamType.create("T");
    return CelCompilerFactory.standardCelCompilerBuilder()
        .setStandardMacros(CelStandardMacro.STANDARD_MACROS)
        .addLibraries(CelOptionalLibrary.INSTANCE)
        .addMessageTypes(TestAllTypes.getDescriptor())
        .addVar("dyn_var", SimpleType.DYN)
        .addVar("nullable_int", NULLABLE_INT)
        .addVar("b", SimpleType.BOOL)
        .addVar("dyn_int_map", MapType.create(SimpleType.DYN, SimpleType.INT))
        .addVar("string_dyn_map", MapType.create(SimpleType.STRING, SimpleType.DYN))
        .addVar("msg", StructTypeReference.create(TestAllTypes.getDescriptor().getFullName()))
        .addFunctionDeclarations(
            CelFunctionDecl.newFunctionDeclaration(
                "first_elem_type",
                CelOverloadDecl.newGlobalOverload(
                    "first_elem_type_overload",
                    typeParamT,
                    SimpleType.DYN,
                    TypeType.create(ListType.create(typeParamT)))),
            CelFunctionDecl.newFunctionDeclaration(
                "pick_first",
                CelOverloadDecl.newGlobalOverload(
                    "pick_first_overload",
                    typeParamT,
                    ImmutableList.of(typeParamT, typeParamT, typeParamT))))
        .build();
  }

  private static final class CustomCelType extends CelType {

    @Override
    public CelKind kind() {
      return CelKind.INT;
    }

    @Override
    public String name() {
      return "customInt";
    }

    @Override
    public boolean isAssignableFrom(CelType other) {
      return super.isAssignableFrom(other) || other.equals(SimpleType.INT);
    }
  }
}
