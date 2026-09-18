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

import com.google.common.testing.EqualsTester;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class SelectFieldTest {

  @Test
  public void create_twoArguments_success() {
    SelectField field = SelectField.create(1L, "foo");

    assertThat(field.fieldNumber()).isEqualTo(1);
    assertThat(field.fieldName()).isEqualTo("foo");
    assertThat(field.typeCode()).isEqualTo(SelectField.NO_TYPE_CODE);
    assertThat(field.defaultValue()).isNull();
  }

  @Test
  public void create_fourArguments_success() {
    SelectField field = SelectField.create(2L, "bar", 9, "default_str");

    assertThat(field.fieldNumber()).isEqualTo(2);
    assertThat(field.fieldName()).isEqualTo("bar");
    assertThat(field.typeCode()).isEqualTo(9);
    assertThat(field.defaultValue()).isEqualTo("default_str");
  }

  @Test
  public void create_mapTypeCode_success() {
    SelectField field = SelectField.create(3L, "map_field", -1, null);

    assertThat(field.typeCode()).isEqualTo(-1);
  }

  @Test
  public void create_twoArgNullFieldName_throwsNullPointerException() {
    assertThrows(NullPointerException.class, () -> SelectField.create(1L, null));
  }

  @Test
  public void create_fourArgNullFieldName_throwsNullPointerException() {
    assertThrows(NullPointerException.class, () -> SelectField.create(1L, null, 9, null));
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
        assertThrows(IllegalArgumentException.class, () -> SelectField.create(1L, "foo", 0, null));

    assertThat(thrown).hasMessageThat().contains("Invalid protobuf type code: 0");
  }

  @Test
  public void create_typeCodeAboveMaximum_throwsIllegalArgumentException() {
    IllegalArgumentException thrown =
        assertThrows(IllegalArgumentException.class, () -> SelectField.create(1L, "foo", 19, null));

    assertThat(thrown).hasMessageThat().contains("Invalid protobuf type code: 19");
  }

  @Test
  public void create_typeCodeBelowSentinel_throwsIllegalArgumentException() {
    IllegalArgumentException thrown =
        assertThrows(IllegalArgumentException.class, () -> SelectField.create(1L, "foo", -2, null));

    assertThat(thrown).hasMessageThat().contains("Invalid protobuf type code: -2");
  }

  @Test
  public void create_typeCodeGroupProto_throwsIllegalArgumentException() {
    IllegalArgumentException thrown =
        assertThrows(IllegalArgumentException.class, () -> SelectField.create(1L, "foo", 10, null));

    assertThat(thrown).hasMessageThat().contains("Invalid protobuf type code: 10");
  }

  @Test
  public void equalsAndHashCode_testedProperly() {
    new EqualsTester()
        .addEqualityGroup(SelectField.create(1L, "foo"), SelectField.create(1L, "foo"))
        .addEqualityGroup(SelectField.create(2L, "foo"), SelectField.create(2L, "foo"))
        .addEqualityGroup(SelectField.create(1L, "bar"), SelectField.create(1L, "bar"))
        .addEqualityGroup(
            SelectField.create(1L, "foo", 9, "default"),
            SelectField.create(1L, "foo", 9, "default"))
        .addEqualityGroup(SelectField.create(1L, "foo", 9, "other_default"))
        .testEquals();
  }
}
