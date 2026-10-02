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

package dev.cel.common.values;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableMap;
import com.google.common.testing.EqualsTester;
import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(TestParameterInjector.class)
public final class SelectFieldTest {

  @Test
  public void create_twoArguments_success() {
    SelectField field = SelectField.create(1L, "foo");

    assertThat(field.fieldNumber()).isEqualTo(1);
    assertThat(field.fieldName()).isEqualTo("foo");
    assertThat(field.typeCode()).isEqualTo(SelectField.NO_TYPE_CODE);
    assertThat(field.defaultValue()).isNull();
    assertThat(field.protoTypeName()).isEmpty();
  }

  @Test
  public void create_threeArguments_success() {
    SelectField field = SelectField.create(2L, "bar", 11);

    assertThat(field.fieldNumber()).isEqualTo(2);
    assertThat(field.fieldName()).isEqualTo("bar");
    assertThat(field.typeCode()).isEqualTo(11);
    assertThat(field.defaultValue()).isNull();
    assertThat(field.protoTypeName()).isEmpty();
  }

  @Test
  public void create_fourArguments_success() {
    SelectField field = SelectField.create(2L, "bar", 9, "default_str");

    assertThat(field.fieldNumber()).isEqualTo(2);
    assertThat(field.fieldName()).isEqualTo("bar");
    assertThat(field.typeCode()).isEqualTo(9);
    assertThat(field.defaultValue()).isEqualTo("default_str");
    assertThat(field.protoTypeName()).isEmpty();
  }

  @Test
  public void create_fiveArguments_success() {
    SelectField field = SelectField.create(2L, "bar", 11, null, "google.protobuf.Duration");

    assertThat(field.fieldNumber()).isEqualTo(2);
    assertThat(field.fieldName()).isEqualTo("bar");
    assertThat(field.typeCode()).isEqualTo(11);
    assertThat(field.defaultValue()).isNull();
    assertThat(field.protoTypeName()).isEqualTo("google.protobuf.Duration");
  }

  @Test
  public void create_fiveArgNullProtoTypeName_throwsNullPointerException() {
    assertThrows(NullPointerException.class, () -> SelectField.create(1L, "foo", 11, null, null));
  }

  @Test
  public void create_twoArgNullFieldName_throwsNullPointerException() {
    assertThrows(NullPointerException.class, () -> SelectField.create(1L, null));
  }

  @Test
  public void create_threeArgNullFieldName_throwsNullPointerException() {
    assertThrows(NullPointerException.class, () -> SelectField.create(1L, null, 11));
  }

  @Test
  public void create_fourArgNullFieldName_throwsNullPointerException() {
    assertThrows(NullPointerException.class, () -> SelectField.create(1L, null, 9, "default"));
  }

  @Test
  public void create_fourArgNullDefaultValue_throwsNullPointerException() {
    assertThrows(NullPointerException.class, () -> SelectField.create(1L, "foo", 9, null));
  }

  @Test
  public void create_fieldNumberBelowMinimum_throwsIllegalArgumentException() {
    IllegalArgumentException thrown =
        assertThrows(IllegalArgumentException.class, () -> SelectField.create(0L, "foo"));

    assertThat(thrown).hasMessageThat().contains("Field number out of protobuf range: 0");
  }

  @Test
  public void create_fieldNumberNegative_throwsIllegalArgumentException() {
    IllegalArgumentException thrown =
        assertThrows(IllegalArgumentException.class, () -> SelectField.create(-1L, "foo"));

    assertThat(thrown).hasMessageThat().contains("Field number out of protobuf range: -1");
  }

  @Test
  public void create_fieldNumberAboveMaximum_throwsIllegalArgumentException() {
    IllegalArgumentException thrown =
        assertThrows(IllegalArgumentException.class, () -> SelectField.create(536870912L, "foo"));

    assertThat(thrown).hasMessageThat().contains("Field number out of protobuf range: 536870912");
  }

  @Test
  public void create_typeCodeZero_throwsIllegalArgumentException() {
    IllegalArgumentException thrown =
        assertThrows(IllegalArgumentException.class, () -> SelectField.create(1L, "foo", 0));

    assertThat(thrown).hasMessageThat().contains("Invalid protobuf type code: 0");
  }

  @Test
  public void create_typeCodeAboveMaximum_throwsIllegalArgumentException() {
    IllegalArgumentException thrown =
        assertThrows(IllegalArgumentException.class, () -> SelectField.create(1L, "foo", 19));

    assertThat(thrown).hasMessageThat().contains("Invalid protobuf type code: 19");
  }

  @Test
  public void create_typeCodeBelowSentinel_throwsIllegalArgumentException() {
    IllegalArgumentException thrown =
        assertThrows(IllegalArgumentException.class, () -> SelectField.create(1L, "foo", -2));

    assertThat(thrown).hasMessageThat().contains("Invalid protobuf type code: -2");
  }

  @Test
  public void create_typeCodeGroupProto_throwsIllegalArgumentException() {
    IllegalArgumentException thrown =
        assertThrows(IllegalArgumentException.class, () -> SelectField.create(1L, "foo", 10));

    assertThat(thrown).hasMessageThat().contains("Invalid protobuf type code: 10");
  }

  @Test
  public void create_mapTypeCode_throwsIllegalArgumentException() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> SelectField.create(1L, "foo", SelectField.CEL_MAP_TYPE_CODE));

    assertThat(thrown).hasMessageThat().contains("Map fields must be created via createMap: foo");
  }

  @Test
  public void createMap_threeArguments_success() {
    SelectField.MapEntrySpec mapEntrySpec = SelectField.MapEntrySpec.create(9, 9);

    SelectField field = SelectField.createMap(3L, "map_field", mapEntrySpec);

    assertThat(field.fieldNumber()).isEqualTo(3);
    assertThat(field.fieldName()).isEqualTo("map_field");
    assertThat(field.typeCode()).isEqualTo(SelectField.CEL_MAP_TYPE_CODE);
    assertThat(field.defaultValue()).isEqualTo(ImmutableMap.of());
    assertThat(field.protoTypeName()).isEmpty();
    assertThat(field.mapEntrySpec()).isEqualTo(mapEntrySpec);
  }

  @Test
  public void createMap_fourArguments_success() {
    SelectField.MapEntrySpec mapEntrySpec = SelectField.MapEntrySpec.create(9, 11);

    SelectField field =
        SelectField.createMap(
            3L, "map_field", mapEntrySpec, "cel.expr.conformance.proto3.TestAllTypes");

    assertThat(field.fieldNumber()).isEqualTo(3);
    assertThat(field.fieldName()).isEqualTo("map_field");
    assertThat(field.typeCode()).isEqualTo(SelectField.CEL_MAP_TYPE_CODE);
    assertThat(field.defaultValue()).isEqualTo(ImmutableMap.of());
    assertThat(field.protoTypeName()).isEqualTo("cel.expr.conformance.proto3.TestAllTypes");
    assertThat(field.mapEntrySpec()).isEqualTo(mapEntrySpec);
  }

  @Test
  public void mapEntrySpec_create_success() {
    SelectField.MapEntrySpec spec = SelectField.MapEntrySpec.create(9, 3);

    assertThat(spec.keyTypeCode()).isEqualTo(9);
    assertThat(spec.valueTypeCode()).isEqualTo(3);
  }

  @Test
  public void mapEntrySpec_validKeyTypeCodes_success(
      @TestParameter({
            "3", // INT64
            "4", // UINT64
            "5", // INT32
            "6", // FIXED64
            "7", // FIXED32
            "8", // BOOL
            "9", // STRING
            "13", // UINT32
            "15", // SFIXED32
            "16", // SFIXED64
            "17", // SINT32
            "18" // SINT64
          })
          long keyTypeCode) {
    SelectField.MapEntrySpec spec = SelectField.MapEntrySpec.create(keyTypeCode, 3);

    assertThat(spec.keyTypeCode()).isEqualTo(keyTypeCode);
    assertThat(spec.valueTypeCode()).isEqualTo(3);
  }

  @Test
  public void mapEntrySpec_invalidKeyTypeCode_throwsIllegalArgumentException(
      @TestParameter({
            "-1", // CEL_MAP_TYPE_CODE
            "0", // NO_TYPE_CODE
            "1", // DOUBLE
            "2", // FLOAT
            "10", // GROUP
            "11", // MESSAGE
            "12", // BYTES
            "14", // ENUM
            "19" // OUT_OF_BOUNDS
          })
          long keyTypeCode) {
    assertThrows(
        IllegalArgumentException.class, () -> SelectField.MapEntrySpec.create(keyTypeCode, 3));
  }

  @Test
  public void mapEntrySpec_invalidValueTypeCode_throwsIllegalArgumentException(
      @TestParameter({
            "-1", // CEL_MAP_TYPE_CODE
            "0", // NO_TYPE_CODE
            "10", // GROUP
            "19" // OUT_OF_BOUNDS
          })
          long valueTypeCode) {
    assertThrows(
        IllegalArgumentException.class, () -> SelectField.MapEntrySpec.create(9, valueTypeCode));
  }

  @Test
  public void equalsAndHashCode_testedProperly() {
    SelectField.MapEntrySpec spec1 = SelectField.MapEntrySpec.create(9, 3);
    SelectField.MapEntrySpec spec2 = SelectField.MapEntrySpec.create(9, 11);

    new EqualsTester()
        .addEqualityGroup(spec1, SelectField.MapEntrySpec.create(9, 3))
        .addEqualityGroup(spec2, SelectField.MapEntrySpec.create(9, 11))
        .addEqualityGroup(SelectField.create(1L, "foo"), SelectField.create(1L, "foo"))
        .addEqualityGroup(SelectField.create(2L, "foo"), SelectField.create(2L, "foo"))
        .addEqualityGroup(SelectField.create(1L, "bar"), SelectField.create(1L, "bar"))
        .addEqualityGroup(SelectField.create(1L, "foo", 11), SelectField.create(1L, "foo", 11))
        .addEqualityGroup(
            SelectField.create(1L, "foo", 9, "default"),
            SelectField.create(1L, "foo", 9, "default"))
        .addEqualityGroup(SelectField.create(1L, "foo", 9, "other_default"))
        .addEqualityGroup(
            SelectField.create(1L, "foo", 11, null, "google.protobuf.Duration"),
            SelectField.create(1L, "foo", 11, null, "google.protobuf.Duration"))
        .addEqualityGroup(SelectField.create(1L, "foo", 11, null, "google.protobuf.Timestamp"))
        .addEqualityGroup(
            SelectField.createMap(1L, "foo", spec1), SelectField.createMap(1L, "foo", spec1))
        .addEqualityGroup(SelectField.createMap(1L, "foo", spec2, "proto.Name"))
        .addEqualityGroup(SelectField.createMap(1L, "foo", spec2, "proto.OtherName"))
        .testEquals();
  }

  @Test
  public void createMap_protoTypeNameInconsistentWithValueTypeCode_throws(
      @TestParameter InconsistentMapValueTestCase testCase) {
    SelectField.MapEntrySpec spec = SelectField.MapEntrySpec.create(9, testCase.valueTypeCode);

    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> SelectField.createMap(1L, "map_field", spec, testCase.protoTypeName));

    assertThat(thrown)
        .hasMessageThat()
        .contains("Map value proto type name '" + testCase.protoTypeName + "' is inconsistent");
  }

  private enum InconsistentMapValueTestCase {
    MESSAGE_VALUE_WITHOUT_TYPE_NAME(SelectField.MESSAGE_TYPE_CODE, ""),
    SCALAR_VALUE_WITH_TYPE_NAME(9, "cel.expr.conformance.proto3.TestAllTypes");

    private final long valueTypeCode;
    private final String protoTypeName;

    InconsistentMapValueTestCase(long valueTypeCode, String protoTypeName) {
      this.valueTypeCode = valueTypeCode;
      this.protoTypeName = protoTypeName;
    }
  }
}
