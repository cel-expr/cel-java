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

package dev.cel.checker;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import dev.cel.common.types.CelType;
import dev.cel.common.types.ListType;
import dev.cel.common.types.MapType;
import dev.cel.common.types.NullableType;
import dev.cel.common.types.OpaqueType;
import dev.cel.common.types.OptionalType;
import dev.cel.common.types.SimpleType;
import dev.cel.common.types.StructTypeReference;
import dev.cel.common.types.TypeParamType;
import dev.cel.common.types.TypeType;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(TestParameterInjector.class)
public final class TypeInferenceTest {

  private enum DynamicTypeTestCase {
    DYN(SimpleType.DYN, true),
    ANY(SimpleType.ANY, true),
    INT(SimpleType.INT, false),
    STRING(SimpleType.STRING, false),
    BOOL(SimpleType.BOOL, false),
    ERROR(SimpleType.ERROR, false);

    final CelType type;
    final boolean expectedIsDyn;

    DynamicTypeTestCase(CelType type, boolean expectedIsDyn) {
      this.type = type;
      this.expectedIsDyn = expectedIsDyn;
    }
  }

  @Test
  public void isDyn_evaluatesCorrectly(@TestParameter DynamicTypeTestCase testCase) {
    assertThat(TypeInference.isDyn(testCase.type)).isEqualTo(testCase.expectedIsDyn);
  }

  private enum DynOrErrorTestCase {
    DYN(SimpleType.DYN, true),
    ANY(SimpleType.ANY, true),
    ERROR(SimpleType.ERROR, true),
    INT(SimpleType.INT, false),
    BOOL(SimpleType.BOOL, false);

    final CelType type;
    final boolean expectedIsDynOrError;

    DynOrErrorTestCase(CelType type, boolean expectedIsDynOrError) {
      this.type = type;
      this.expectedIsDynOrError = expectedIsDynOrError;
    }
  }

  @Test
  public void isDynOrError_evaluatesCorrectly(@TestParameter DynOrErrorTestCase testCase) {
    assertThat(TypeInference.isDynOrError(testCase.type)).isEqualTo(testCase.expectedIsDynOrError);
  }

  @Test
  public void mostGeneral_dynWithConcrete_returnsDyn() {
    assertThat(TypeInference.mostGeneral(SimpleType.DYN, SimpleType.INT)).isEqualTo(SimpleType.DYN);
    assertThat(TypeInference.mostGeneral(SimpleType.INT, SimpleType.DYN)).isEqualTo(SimpleType.DYN);
  }

  @Test
  public void mostGeneral_typeParamWithConcrete_returnsTypeParam() {
    TypeParamType typeParamT = TypeParamType.create("T");

    assertThat(TypeInference.mostGeneral(typeParamT, SimpleType.STRING)).isEqualTo(typeParamT);
    assertThat(TypeInference.mostGeneral(SimpleType.STRING, typeParamT)).isEqualTo(typeParamT);
  }

  @Test
  public void isAssignable_sameType_succeeds() {
    Map<CelType, CelType> subs = new HashMap<>();

    Map<CelType, CelType> result = TypeInference.isAssignable(subs, SimpleType.INT, SimpleType.INT);

    assertThat(result).isEmpty();
  }

  @Test
  public void isAssignable_incompatibleTypes_returnsNull() {
    Map<CelType, CelType> subs = new HashMap<>();

    Map<CelType, CelType> result =
        TypeInference.isAssignable(subs, SimpleType.INT, SimpleType.STRING);

    assertThat(result).isNull();
  }

  @Test
  public void isAssignable_typeParam_bindsConcreteType() {
    Map<CelType, CelType> subs = new HashMap<>();
    TypeParamType typeParamT = TypeParamType.create("T");

    Map<CelType, CelType> result = TypeInference.isAssignable(subs, SimpleType.INT, typeParamT);

    assertThat(result).containsExactly(typeParamT, SimpleType.INT);
  }

  @Test
  public void isAssignable_typeParamSource_bindsConcreteType() {
    Map<CelType, CelType> subs = new HashMap<>();
    TypeParamType typeParamT = TypeParamType.create("T");

    Map<CelType, CelType> result = TypeInference.isAssignable(subs, typeParamT, SimpleType.STRING);

    assertThat(result).containsExactly(typeParamT, SimpleType.STRING);
  }

  @Test
  public void isAssignable_occursCheckCycle_returnsNull() {
    Map<CelType, CelType> subs = new HashMap<>();
    TypeParamType typeParamT = TypeParamType.create("T");
    ListType listOfT = ListType.create(typeParamT);

    // T = list(T) is a cycle and must be rejected by the occurs-check
    Map<CelType, CelType> result = TypeInference.isAssignable(subs, listOfT, typeParamT);

    assertThat(result).isNull();
  }

  @Test
  public void isAssignable_listType_elementTypesAssignable() {
    Map<CelType, CelType> subs = new HashMap<>();
    TypeParamType typeParamT = TypeParamType.create("T");
    ListType listInt = ListType.create(SimpleType.INT);
    ListType listT = ListType.create(typeParamT);

    Map<CelType, CelType> result = TypeInference.isAssignable(subs, listInt, listT);

    assertThat(result).containsExactly(typeParamT, SimpleType.INT);
  }

  @Test
  public void isAssignable_mapType_keyAndValueTypesAssignable() {
    Map<CelType, CelType> subs = new HashMap<>();
    TypeParamType typeParamK = TypeParamType.create("K");
    TypeParamType typeParamV = TypeParamType.create("V");
    MapType mapIntString = MapType.create(SimpleType.INT, SimpleType.STRING);
    MapType mapKV = MapType.create(typeParamK, typeParamV);

    Map<CelType, CelType> result = TypeInference.isAssignable(subs, mapIntString, mapKV);

    assertThat(result).containsExactly(typeParamK, SimpleType.INT, typeParamV, SimpleType.STRING);
  }

  @Test
  public void isAssignable_nullTypeToStruct_succeeds() {
    Map<CelType, CelType> subs = new HashMap<>();
    CelType structType = StructTypeReference.create("my.Message");

    Map<CelType, CelType> result =
        TypeInference.isAssignable(subs, SimpleType.NULL_TYPE, structType);

    assertThat(result).isEmpty();
  }

  @Test
  public void isAssignable_nullTypeToNullable_succeeds() {
    Map<CelType, CelType> subs = new HashMap<>();
    CelType nullableInt = NullableType.create(SimpleType.INT);

    Map<CelType, CelType> result =
        TypeInference.isAssignable(subs, SimpleType.NULL_TYPE, nullableInt);

    assertThat(result).isEmpty();
  }

  @Test
  public void isAssignable_nullTypeToPrimitive_returnsNull() {
    Map<CelType, CelType> subs = new HashMap<>();

    Map<CelType, CelType> result =
        TypeInference.isAssignable(subs, SimpleType.NULL_TYPE, SimpleType.INT);

    assertThat(result).isNull();
  }

  @Test
  public void isAssignable_opaqueType_parametersMatch_succeeds() {
    Map<CelType, CelType> subs = new HashMap<>();
    TypeParamType typeParamT = TypeParamType.create("T");
    OpaqueType opaque1 = OpaqueType.create("vector", SimpleType.INT);
    OpaqueType opaque2 = OpaqueType.create("vector", typeParamT);

    Map<CelType, CelType> result = TypeInference.isAssignable(subs, opaque1, opaque2);

    assertThat(result).containsExactly(typeParamT, SimpleType.INT);
  }

  @Test
  public void isAssignable_opaqueType_differentNames_returnsNull() {
    Map<CelType, CelType> subs = new HashMap<>();
    OpaqueType opaque1 = OpaqueType.create("vector", SimpleType.INT);
    OpaqueType opaque2 = OpaqueType.create("set", SimpleType.INT);

    Map<CelType, CelType> result = TypeInference.isAssignable(subs, opaque1, opaque2);

    assertThat(result).isNull();
  }

  @Test
  public void isAssignable_typeType_concreteTypesCoassignable() {
    Map<CelType, CelType> subs = new HashMap<>();
    CelType typeInt = TypeType.create(SimpleType.INT);
    CelType typeString = TypeType.create(SimpleType.STRING);

    Map<CelType, CelType> result = TypeInference.isAssignable(subs, typeInt, typeString);

    assertThat(result).isEmpty();
  }

  @Test
  public void isAssignable_typeType_parameterizedTypeType_bindsInnerType() {
    Map<CelType, CelType> subs = new HashMap<>();
    TypeParamType typeParamT = TypeParamType.create("T");
    CelType typeOfInt = TypeType.create(SimpleType.INT);
    CelType typeOfT = TypeType.create(typeParamT);

    Map<CelType, CelType> result = TypeInference.isAssignable(subs, typeOfInt, typeOfT);

    assertThat(result).containsExactly(typeParamT, SimpleType.INT);
  }

  @Test
  public void isAssignable_pairwiseList_succeeds() {
    Map<CelType, CelType> subs = new HashMap<>();
    TypeParamType typeParamT = TypeParamType.create("T");
    ImmutableList<CelType> list1 = ImmutableList.of(SimpleType.INT, SimpleType.STRING);
    ImmutableList<CelType> list2 = ImmutableList.of(typeParamT, SimpleType.STRING);

    Map<CelType, CelType> result = TypeInference.isAssignable(subs, list1, list2);

    assertThat(result).containsExactly(typeParamT, SimpleType.INT);
  }

  @Test
  public void isAssignable_pairwiseList_differentSizes_returnsNull() {
    Map<CelType, CelType> subs = new HashMap<>();
    ImmutableList<CelType> list1 = ImmutableList.of(SimpleType.INT);
    ImmutableList<CelType> list2 = ImmutableList.of(SimpleType.INT, SimpleType.STRING);

    Map<CelType, CelType> result = TypeInference.isAssignable(subs, list1, list2);

    assertThat(result).isNull();
  }

  @Test
  public void substitute_boundTypeParam_replacesWithBinding() {
    TypeParamType typeParamT = TypeParamType.create("T");
    Map<CelType, CelType> subs = ImmutableMap.of(typeParamT, SimpleType.INT);

    CelType result = TypeInference.substitute(subs, typeParamT, /* typeParamToDyn= */ false);

    assertThat(result).isEqualTo(SimpleType.INT);
  }

  @Test
  public void substitute_unboundTypeParam_typeParamToDynTrue_replacesWithDyn() {
    TypeParamType typeParamT = TypeParamType.create("T");
    Map<CelType, CelType> subs = ImmutableMap.of();

    CelType result = TypeInference.substitute(subs, typeParamT, /* typeParamToDyn= */ true);

    assertThat(result).isEqualTo(SimpleType.DYN);
  }

  @Test
  public void substitute_unboundTypeParam_typeParamToDynFalse_preservesTypeParam() {
    TypeParamType typeParamT = TypeParamType.create("T");
    Map<CelType, CelType> subs = ImmutableMap.of();

    CelType result = TypeInference.substitute(subs, typeParamT, /* typeParamToDyn= */ false);

    assertThat(result).isEqualTo(typeParamT);
  }

  @Test
  public void substitute_nestedTypes_boundTypeParam_substitutesRecursively() {
    TypeParamType typeParamT = TypeParamType.create("T");
    Map<CelType, CelType> subs = ImmutableMap.of(typeParamT, SimpleType.INT);

    ListType listType = ListType.create(typeParamT);
    MapType mapType = MapType.create(SimpleType.STRING, typeParamT);
    OptionalType optionalType = OptionalType.create(typeParamT);
    TypeType typeType = TypeType.create(typeParamT);
    OpaqueType opaqueType = OpaqueType.create("custom", typeParamT);

    assertThat(TypeInference.substitute(subs, listType, false))
        .isEqualTo(ListType.create(SimpleType.INT));
    assertThat(TypeInference.substitute(subs, mapType, false))
        .isEqualTo(MapType.create(SimpleType.STRING, SimpleType.INT));
    assertThat(TypeInference.substitute(subs, optionalType, false))
        .isEqualTo(OptionalType.create(SimpleType.INT));
    assertThat(TypeInference.substitute(subs, typeType, false))
        .isEqualTo(TypeType.create(SimpleType.INT));
    assertThat(TypeInference.substitute(subs, opaqueType, false))
        .isEqualTo(OpaqueType.create("custom", SimpleType.INT));
  }

  @Test
  public void substitute_nestedTypes_unboundTypeParam_typeParamToDynTrue_substitutesDyn() {
    TypeParamType typeParamT = TypeParamType.create("T");
    Map<CelType, CelType> subs = ImmutableMap.of();

    ListType listType = ListType.create(typeParamT);
    MapType mapType = MapType.create(SimpleType.STRING, typeParamT);
    OptionalType optionalType = OptionalType.create(typeParamT);

    assertThat(TypeInference.substitute(subs, listType, /* typeParamToDyn= */ true))
        .isEqualTo(ListType.create(SimpleType.DYN));
    assertThat(TypeInference.substitute(subs, mapType, /* typeParamToDyn= */ true))
        .isEqualTo(MapType.create(SimpleType.STRING, SimpleType.DYN));
    assertThat(TypeInference.substitute(subs, optionalType, /* typeParamToDyn= */ true))
        .isEqualTo(OptionalType.create(SimpleType.DYN));
  }

  @Test
  public void substitute_nullableType_boundTypeParam_substitutesInnerType() {
    TypeParamType typeParamT = TypeParamType.create("T");
    TypeParamType typeParamK = TypeParamType.create("K");
    TypeParamType typeParamV = TypeParamType.create("V");
    Map<CelType, CelType> subs =
        ImmutableMap.of(
            typeParamT, SimpleType.INT, typeParamK, SimpleType.INT, typeParamV, SimpleType.STRING);

    NullableType nullableTypeParam = NullableType.create(typeParamT);
    NullableType nullableList = NullableType.create(ListType.create(typeParamT));
    NullableType nullableMap = NullableType.create(MapType.create(typeParamK, typeParamV));
    NullableType nullableTypeType = NullableType.create(TypeType.create(typeParamT));

    assertThat(TypeInference.substitute(subs, nullableTypeParam, false))
        .isEqualTo(NullableType.create(SimpleType.INT));
    assertThat(TypeInference.substitute(subs, nullableList, false))
        .isEqualTo(NullableType.create(ListType.create(SimpleType.INT)));
    assertThat(TypeInference.substitute(subs, nullableMap, false))
        .isEqualTo(NullableType.create(MapType.create(SimpleType.INT, SimpleType.STRING)));
    assertThat(TypeInference.substitute(subs, nullableTypeType, false))
        .isEqualTo(NullableType.create(TypeType.create(SimpleType.INT)));
  }

  @Test
  public void substitute_nullableType_unboundTypeParam_typeParamToDynTrue_substitutesDyn() {
    TypeParamType typeParamT = TypeParamType.create("T");
    Map<CelType, CelType> subs = ImmutableMap.of();
    NullableType nullableTypeParam = NullableType.create(typeParamT);
    NullableType nullableList = NullableType.create(ListType.create(typeParamT));

    assertThat(TypeInference.substitute(subs, nullableTypeParam, /* typeParamToDyn= */ true))
        .isEqualTo(NullableType.create(SimpleType.DYN));
    assertThat(TypeInference.substitute(subs, nullableList, /* typeParamToDyn= */ true))
        .isEqualTo(NullableType.create(ListType.create(SimpleType.DYN)));
  }

  @Test
  public void isAssignable_typeType_typeParamInSource_bindsInnerType() {
    Map<CelType, CelType> subs = new HashMap<>();
    TypeParamType typeParamT = TypeParamType.create("T");
    CelType typeOfT = TypeType.create(typeParamT);
    CelType typeOfInt = TypeType.create(SimpleType.INT);

    Map<CelType, CelType> result = TypeInference.isAssignable(subs, typeOfT, typeOfInt);

    assertThat(result).containsExactly(typeParamT, SimpleType.INT);
  }

  @Test
  public void isEqualOrLessSpecific_evaluatesCorrectly() {
    TypeParamType typeParamT = TypeParamType.create("T");
    CelType nullableInt = NullableType.create(SimpleType.INT);
    CelType nullableT = NullableType.create(typeParamT);

    assertThat(TypeInference.isEqualOrLessSpecific(SimpleType.DYN, SimpleType.INT)).isTrue();
    assertThat(TypeInference.isEqualOrLessSpecific(SimpleType.INT, SimpleType.DYN)).isFalse();
    assertThat(TypeInference.isEqualOrLessSpecific(typeParamT, SimpleType.INT)).isTrue();
    assertThat(TypeInference.isEqualOrLessSpecific(SimpleType.INT, typeParamT)).isFalse();
    assertThat(TypeInference.isEqualOrLessSpecific(SimpleType.INT, SimpleType.STRING)).isFalse();
    assertThat(TypeInference.isEqualOrLessSpecific(nullableInt, nullableInt)).isTrue();
    assertThat(TypeInference.isEqualOrLessSpecific(nullableT, nullableInt)).isTrue();
    assertThat(TypeInference.isEqualOrLessSpecific(nullableInt, nullableT)).isFalse();
    assertThat(TypeInference.isEqualOrLessSpecific(nullableInt, SimpleType.INT)).isFalse();
    assertThat(TypeInference.isEqualOrLessSpecific(SimpleType.INT, nullableInt)).isFalse();
    assertThat(
            TypeInference.isEqualOrLessSpecific(
                ListType.create(typeParamT), ListType.create(SimpleType.INT)))
        .isTrue();
    assertThat(
            TypeInference.isEqualOrLessSpecific(
                ListType.create(SimpleType.INT), ListType.create(typeParamT)))
        .isFalse();
    assertThat(
            TypeInference.isEqualOrLessSpecific(
                TypeType.create(typeParamT), TypeType.create(SimpleType.INT)))
        .isTrue();
  }

  @Test
  public void isEqualOrLessSpecific_nullArguments_throwsNullPointerException() {
    assertThrows(
        NullPointerException.class,
        () -> TypeInference.isEqualOrLessSpecific(null, SimpleType.INT));
    assertThrows(
        NullPointerException.class,
        () -> TypeInference.isEqualOrLessSpecific(SimpleType.INT, null));
  }
}
