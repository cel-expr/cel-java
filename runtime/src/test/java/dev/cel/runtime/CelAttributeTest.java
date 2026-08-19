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

package dev.cel.runtime;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.common.primitives.UnsignedLong;
import dev.cel.runtime.CelAttribute.Qualifier;
import java.util.ArrayList;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class CelAttributeTest {
  @Test
  public void unequal() {
    assertThat(CelAttribute.create("identifier"))
        .isNotEqualTo(CelAttribute.create("other_identifier"));

    assertThat(CelAttribute.create("identifier").qualify(CelAttribute.Qualifier.ofString("field1")))
        .isNotEqualTo(CelAttribute.create("identifier"));

    assertThat(CelAttribute.create("identifier").qualify(CelAttribute.Qualifier.ofString("field1")))
        .isNotEqualTo(
            CelAttribute.create("identifier").qualify(CelAttribute.Qualifier.ofString("field2")));

    assertThat(CelAttribute.create("identifier").qualify(CelAttribute.Qualifier.ofString("field1")))
        .isNotEqualTo(CelAttribute.create("identifier").qualify(CelAttribute.Qualifier.ofInt(1)));

    assertThat(CelAttribute.create("identifier").qualify(CelAttribute.Qualifier.ofUint(1)))
        .isNotEqualTo(CelAttribute.create("identifier").qualify(CelAttribute.Qualifier.ofInt(1)));

    assertThat(CelAttribute.create("identifier").qualify(CelAttribute.Qualifier.ofBool(true)))
        .isNotEqualTo(CelAttribute.create("identifier").qualify(CelAttribute.Qualifier.ofInt(1)));

    assertThat(
            CelAttribute.create("identifier")
                .qualify(CelAttribute.Qualifier.ofInt(1))
                .qualify(CelAttribute.Qualifier.ofString("field1")))
        .isNotEqualTo(
            CelAttribute.create("identifier")
                .qualify(CelAttribute.Qualifier.ofInt(2))
                .qualify(CelAttribute.Qualifier.ofString("field1")));
  }

  @Test
  public void equality_equal() {
    assertThat(CelAttribute.create("identifier")).isEqualTo(CelAttribute.create("identifier"));

    assertThat(CelAttribute.create("identifier").qualify(CelAttribute.Qualifier.ofInt(1)))
        .isEqualTo(CelAttribute.create("identifier").qualify(CelAttribute.Qualifier.ofInt(1)));

    assertThat(CelAttribute.create("identifier").qualify(CelAttribute.Qualifier.ofUint(1)))
        .isEqualTo(CelAttribute.create("identifier").qualify(CelAttribute.Qualifier.ofUint(1)));

    assertThat(CelAttribute.create("identifier").qualify(CelAttribute.Qualifier.ofString("1")))
        .isEqualTo(CelAttribute.create("identifier").qualify(CelAttribute.Qualifier.ofString("1")));

    assertThat(CelAttribute.create("identifier").qualify(CelAttribute.Qualifier.ofBool(true)))
        .isEqualTo(CelAttribute.create("identifier").qualify(CelAttribute.Qualifier.ofBool(true)));

    assertThat(
            CelAttribute.create("identifier")
                .qualify(CelAttribute.Qualifier.ofBool(true))
                .qualify(CelAttribute.Qualifier.ofString("field1")))
        .isEqualTo(
            CelAttribute.create("identifier")
                .qualify(CelAttribute.Qualifier.ofBool(true))
                .qualify(CelAttribute.Qualifier.ofString("field1")));
  }

  @Test
  public void create_wildcardUnsupported() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CelAttribute.create(
                ImmutableList.of(Qualifier.ofString("identifier"), Qualifier.ofWildCard())));
  }

  @Test
  public void qualify_wildcardUnsupported() {
    assertThrows(
        IllegalArgumentException.class,
        () -> CelAttribute.create("identifier").qualify(Qualifier.ofWildCard()));
  }

  @Test
  public void toString_values() {
    assertThat(CelAttribute.create("identifier").toString()).isEqualTo("identifier");

    assertThat(
            CelAttribute.create("identifier").qualify(CelAttribute.Qualifier.ofInt(1)).toString())
        .isEqualTo("identifier[1]");

    assertThat(
            CelAttribute.create("identifier").qualify(CelAttribute.Qualifier.ofUint(1)).toString())
        .isEqualTo("identifier[1u]");

    assertThat(
            CelAttribute.create("identifier")
                .qualify(CelAttribute.Qualifier.ofString("1"))
                .toString())
        .isEqualTo("identifier['1']");

    assertThat(
            CelAttribute.create("identifier")
                .qualify(CelAttribute.Qualifier.ofBool(true))
                .toString())
        .isEqualTo("identifier[true]");

    assertThat(
            CelAttribute.create("identifier")
                .qualify(CelAttribute.Qualifier.ofBool(true))
                .qualify(CelAttribute.Qualifier.ofString("field1"))
                .toString())
        .isEqualTo("identifier[true].field1");
  }

  @Test
  public void fromQualifiedIdentifier_parseIdents() {
    assertThat(CelAttribute.fromQualifiedIdentifier("identifier.field1.field2"))
        .isEqualTo(
            CelAttribute.create("identifier")
                .qualify(CelAttribute.Qualifier.ofString("field1"))
                .qualify(CelAttribute.Qualifier.ofString("field2")));
  }

  @Test
  public void fromGeneric_supportedTypes() {
    assertThat(Qualifier.fromGeneric(1)).isEqualTo(Qualifier.ofInt(1));
    assertThat(Qualifier.fromGeneric(Long.valueOf(1))).isEqualTo(Qualifier.ofInt(1));
    assertThat(Qualifier.fromGeneric(UnsignedLong.valueOf(1))).isEqualTo(Qualifier.ofUint(1));
    assertThat(Qualifier.fromGeneric("abcd")).isEqualTo(Qualifier.ofString("abcd"));
    assertThat(Qualifier.fromGeneric(Boolean.valueOf(false))).isEqualTo(Qualifier.ofBool(false));
  }

  @Test
  public void fromGeneric_integerBoundaryValues() {
    assertThat(Qualifier.fromGeneric(Integer.MAX_VALUE))
        .isEqualTo(Qualifier.ofInt(Integer.MAX_VALUE));
    assertThat(Qualifier.fromGeneric(Integer.MIN_VALUE))
        .isEqualTo(Qualifier.ofInt(Integer.MIN_VALUE));
    assertThat(Qualifier.fromGeneric(0)).isEqualTo(Qualifier.ofInt(0));
  }

  @Test
  public void fromGeneric_nullThrows() {
    assertThrows(IllegalArgumentException.class, () -> Qualifier.fromGeneric(null));
  }

  @Test
  public void fromGeneric_unsupportedTypeThrows() {
    assertThrows(
        IllegalArgumentException.class, () -> Qualifier.fromGeneric(new ArrayList<String>()));
    assertThrows(IllegalArgumentException.class, () -> Qualifier.fromGeneric(1.0));
    assertThrows(IllegalArgumentException.class, () -> Qualifier.fromGeneric(new byte[] {1, 2}));
  }

  @Test
  public void create_fromList() {
    assertThat(
            CelAttribute.create(
                ImmutableList.of(Qualifier.ofString("ident"), Qualifier.ofString("field"))))
        .isEqualTo(CelAttribute.create("ident").qualify(Qualifier.ofString("field")));
  }

  @Test
  public void create_invalidListThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CelAttribute.create(
                // EMPTY instance has special meaning so cannot be created.
                ImmutableList.of()));

    assertThrows(
        IllegalArgumentException.class,
        () ->
            CelAttribute.create(
                ImmutableList.of(Qualifier.ofString("not-a-root"), Qualifier.ofInt(1))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CelAttribute.create(
                ImmutableList.of(
                    Qualifier.ofString("root"),
                    // Wildcard unsupported in specific concrete attributes.
                    Qualifier.ofWildCard())));
  }
}
