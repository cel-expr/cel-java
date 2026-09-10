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

import dev.cel.expr.Type;
import dev.cel.expr.Type.PrimitiveType;
import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.CelFunctionDecl;
import dev.cel.common.CelOverloadDecl;
import dev.cel.common.types.CelKind;
import dev.cel.common.types.CelProtoTypes;
import dev.cel.common.types.CelType;
import dev.cel.common.types.ListType;
import dev.cel.common.types.MapType;
import dev.cel.common.types.NullableType;
import dev.cel.common.types.OptionalType;
import dev.cel.common.types.SimpleType;
import dev.cel.common.types.TypeParamType;
import dev.cel.common.types.TypeType;
import dev.cel.compiler.CelCompiler;
import dev.cel.compiler.CelCompilerFactory;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class TypesTest {

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

  @Test
  public void compiler_typeParamInCompositeTypeType_resolvesReturnType() throws Exception {
    TypeParamType typeParamT = TypeParamType.create("T");
    CelCompiler celCompiler =
        CelCompilerFactory.standardCelCompilerBuilder()
            .addFunctionDeclarations(
                CelFunctionDecl.newFunctionDeclaration(
                    "first_elem_type",
                    CelOverloadDecl.newGlobalOverload(
                        "first_elem_type_overload",
                        typeParamT,
                        SimpleType.DYN,
                        TypeType.create(ListType.create(typeParamT)))))
            .build();

    CelAbstractSyntaxTree ast = celCompiler.compile("first_elem_type('data', type([1]))").getAst();

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
