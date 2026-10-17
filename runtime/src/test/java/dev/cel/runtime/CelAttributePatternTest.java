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
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class CelAttributePatternTest {
  @Test
  public void equality_unequal() {
    assertThat(CelAttributePattern.create("identifier"))
        .isNotEqualTo(CelAttributePattern.create("other_identifier"));

    assertThat(CelAttributePattern.create("identifier").qualify(Qualifier.ofString("field1")))
        .isNotEqualTo(CelAttributePattern.create("identifier"));

    assertThat(CelAttributePattern.create("identifier").qualify(Qualifier.ofString("field1")))
        .isNotEqualTo(
            CelAttributePattern.create("identifier").qualify(Qualifier.ofString("field2")));

    assertThat(CelAttributePattern.create("identifier").qualify(Qualifier.ofString("field1")))
        .isNotEqualTo(CelAttributePattern.create("identifier").qualify(Qualifier.ofInt(1)));

    assertThat(CelAttributePattern.create("identifier").qualify(Qualifier.ofUint(1)))
        .isNotEqualTo(CelAttributePattern.create("identifier").qualify(Qualifier.ofInt(1)));

    assertThat(CelAttributePattern.create("identifier").qualify(Qualifier.ofBool(true)))
        .isNotEqualTo(CelAttributePattern.create("identifier").qualify(Qualifier.ofInt(1)));

    assertThat(CelAttributePattern.create("identifier").qualify(Qualifier.ofWildCard()))
        .isNotEqualTo(CelAttributePattern.create("identifier").qualify(Qualifier.ofInt(1)));

    assertThat(
            CelAttributePattern.create("identifier")
                .qualify(Qualifier.ofInt(1))
                .qualify(Qualifier.ofString("field1")))
        .isNotEqualTo(
            CelAttributePattern.create("identifier")
                .qualify(Qualifier.ofInt(2))
                .qualify(Qualifier.ofString("field1")));
  }

  @Test
  public void equality_equal() {
    assertThat(CelAttributePattern.create("identifier"))
        .isEqualTo(CelAttributePattern.create("identifier"));

    assertThat(CelAttributePattern.create("identifier").qualify(Qualifier.ofInt(1)))
        .isEqualTo(CelAttributePattern.create("identifier").qualify(Qualifier.ofInt(1)));

    assertThat(CelAttributePattern.create("identifier").qualify(Qualifier.ofUint(1)))
        .isEqualTo(CelAttributePattern.create("identifier").qualify(Qualifier.ofUint(1)));

    assertThat(CelAttributePattern.create("identifier").qualify(Qualifier.ofString("1")))
        .isEqualTo(CelAttributePattern.create("identifier").qualify(Qualifier.ofString("1")));

    assertThat(CelAttributePattern.create("identifier").qualify(Qualifier.ofBool(true)))
        .isEqualTo(CelAttributePattern.create("identifier").qualify(Qualifier.ofBool(true)));

    assertThat(CelAttributePattern.create("identifier").qualify(Qualifier.ofWildCard()))
        .isEqualTo(CelAttributePattern.create("identifier").qualify(Qualifier.ofWildCard()));

    assertThat(
            CelAttributePattern.create("identifier")
                .qualify(Qualifier.ofBool(true))
                .qualify(Qualifier.ofString("field1")))
        .isEqualTo(
            CelAttributePattern.create("identifier")
                .qualify(Qualifier.ofBool(true))
                .qualify(Qualifier.ofString("field1")));
  }

  @Test
  public void toString_values() {
    assertThat(CelAttributePattern.create("identifier").toString()).isEqualTo("identifier");

    assertThat(CelAttributePattern.create("identifier").qualify(Qualifier.ofInt(1)).toString())
        .isEqualTo("identifier[1]");

    assertThat(CelAttributePattern.create("identifier").qualify(Qualifier.ofUint(1)).toString())
        .isEqualTo("identifier[1u]");

    assertThat(CelAttributePattern.create("identifier").qualify(Qualifier.ofString("1")).toString())
        .isEqualTo("identifier['1']");

    assertThat(CelAttributePattern.create("identifier").qualify(Qualifier.ofBool(true)).toString())
        .isEqualTo("identifier[true]");

    assertThat(CelAttributePattern.create("identifier").qualify(Qualifier.ofWildCard()).toString())
        .isEqualTo("identifier.*");

    assertThat(
            CelAttributePattern.create("identifier")
                .qualify(Qualifier.ofBool(true))
                .qualify(Qualifier.ofString("field1"))
                .toString())
        .isEqualTo("identifier[true].field1");
  }

  @Test
  public void isMatch_matches() {
    assertThat(CelAttributePattern.create("identifier").isMatch(CelAttribute.create("identifier")))
        .isTrue();

    assertThat(
            CelAttributePattern.create("identifier")
                .isMatch(CelAttribute.create("identifier").qualify(Qualifier.ofString("field1"))))
        .isTrue();

    assertThat(
            CelAttributePattern.create("identifier")
                .qualify(Qualifier.ofWildCard())
                .isMatch(CelAttribute.create("identifier").qualify(Qualifier.ofString("field1"))))
        .isTrue();

    assertThat(
            CelAttributePattern.create("identifier")
                .qualify(Qualifier.ofWildCard())
                .isMatch(CelAttribute.create("identifier").qualify(Qualifier.ofString("field1"))))
        .isTrue();

    assertThat(
            CelAttributePattern.create("identifier")
                .qualify(Qualifier.ofWildCard())
                .isMatch(
                    CelAttribute.create("identifier")
                        .qualify(Qualifier.ofInt(1))
                        .qualify(Qualifier.ofString("field1"))))
        .isTrue();
  }

  @Test
  public void isMatch_doesNotMatch() {
    assertThat(CelAttributePattern.create("identifier").isMatch(CelAttribute.create("identifier2")))
        .isFalse();

    assertThat(
            CelAttributePattern.create("identifier")
                .isMatch(CelAttribute.create("identifier2").qualify(Qualifier.ofString("field1"))))
        .isFalse();

    assertThat(
            CelAttributePattern.create("identifier")
                .qualify(Qualifier.ofString("field1"))
                .isMatch(CelAttribute.create("identifier").qualify(Qualifier.ofString("field2"))))
        .isFalse();

    // Partial match
    assertThat(
            CelAttributePattern.create("identifier")
                .qualify(Qualifier.ofWildCard())
                .qualify(Qualifier.ofString("field1"))
                .isMatch(CelAttribute.create("identifier").qualify(Qualifier.ofInt(1))))
        .isFalse();
  }

  @Test
  public void isPartialMatch_matches() {
    assertThat(
            CelAttributePattern.create("identifier")
                .isPartialMatch(CelAttribute.create("identifier")))
        .isTrue();

    assertThat(
            CelAttributePattern.create("identifier")
                .isPartialMatch(
                    CelAttribute.create("identifier").qualify(Qualifier.ofString("field1"))))
        .isTrue();

    assertThat(
            CelAttributePattern.create("identifier")
                .qualify(Qualifier.ofWildCard())
                .isPartialMatch(
                    CelAttribute.create("identifier").qualify(Qualifier.ofString("field1"))))
        .isTrue();

    assertThat(
            CelAttributePattern.create("identifier")
                .qualify(Qualifier.ofWildCard())
                .isPartialMatch(
                    CelAttribute.create("identifier").qualify(Qualifier.ofString("field1"))))
        .isTrue();

    assertThat(
            CelAttributePattern.create("identifier")
                .qualify(Qualifier.ofWildCard())
                .isPartialMatch(
                    CelAttribute.create("identifier")
                        .qualify(Qualifier.ofInt(1))
                        .qualify(Qualifier.ofString("field1"))))
        .isTrue();

    assertThat(
            CelAttributePattern.create("identifier")
                .qualify(Qualifier.ofWildCard())
                .qualify(Qualifier.ofString("field1"))
                .isPartialMatch(CelAttribute.create("identifier").qualify(Qualifier.ofInt(1))))
        .isTrue();
  }

  @Test
  public void isPartialMatch_doesNotMatch() {
    assertThat(
            CelAttributePattern.create("identifier")
                .isPartialMatch(CelAttribute.create("identifier2")))
        .isFalse();

    assertThat(
            CelAttributePattern.create("identifier")
                .isPartialMatch(
                    CelAttribute.create("identifier2").qualify(Qualifier.ofString("field1"))))
        .isFalse();

    assertThat(
            CelAttributePattern.create("identifier")
                .qualify(Qualifier.ofString("field1"))
                .isPartialMatch(
                    CelAttribute.create("identifier").qualify(Qualifier.ofString("field2"))))
        .isFalse();

    assertThat(
            CelAttributePattern.create("identifier")
                .qualify(Qualifier.ofWildCard())
                .isPartialMatch(CelAttribute.EMPTY))
        .isFalse();
  }

  @Test
  public void isMatch_intAndUintQualifiersWithSameValue_match() {
    CelAttributePattern intPattern =
        CelAttributePattern.create("identifier").qualify(Qualifier.ofInt(1));
    CelAttributePattern uintPattern =
        CelAttributePattern.create("identifier").qualify(Qualifier.ofUint(1));
    CelAttribute intAttribute = CelAttribute.create("identifier").qualify(Qualifier.ofInt(1));
    CelAttribute uintAttribute = CelAttribute.create("identifier").qualify(Qualifier.ofUint(1));

    assertThat(intPattern.isMatch(uintAttribute)).isTrue();
    assertThat(uintPattern.isMatch(intAttribute)).isTrue();
    assertThat(intPattern.isPartialMatch(uintAttribute)).isTrue();
    assertThat(uintPattern.isPartialMatch(intAttribute)).isTrue();
  }

  @Test
  public void isMatch_negativeIntAndUintWithSameBits_doNotMatch() {
    CelAttributePattern intPattern =
        CelAttributePattern.create("identifier").qualify(Qualifier.ofInt(-1));
    CelAttributePattern uintPattern =
        CelAttributePattern.create("identifier")
            .qualify(Qualifier.ofUint(UnsignedLong.fromLongBits(-1)));
    CelAttribute intAttribute = CelAttribute.create("identifier").qualify(Qualifier.ofInt(-1));
    CelAttribute uintAttribute =
        CelAttribute.create("identifier").qualify(Qualifier.ofUint(UnsignedLong.fromLongBits(-1)));

    assertThat(intPattern.isMatch(uintAttribute)).isFalse();
    assertThat(uintPattern.isMatch(intAttribute)).isFalse();
  }

  @Test
  public void simplify_simplifies() {
    assertThat(
            CelAttributePattern.create("identifier")
                .qualify(Qualifier.ofWildCard())
                .simplify(CelAttribute.create("identifier").qualify(Qualifier.ofInt(2))))
        .isEqualTo(CelAttribute.create("identifier").qualify(Qualifier.ofInt(2)));

    assertThat(
            CelAttributePattern.create("identifier")
                .qualify(Qualifier.ofWildCard())
                .simplify(
                    CelAttribute.create("identifier")
                        .qualify(Qualifier.ofInt(2))
                        .qualify(Qualifier.ofString("12345"))))
        .isEqualTo(CelAttribute.create("identifier").qualify(Qualifier.ofInt(2)));
  }

  @Test
  public void fromQualifiedIdentifier_parseIdents() {
    assertThat(CelAttributePattern.fromQualifiedIdentifier("identifier.field1.field2"))
        .isEqualTo(
            CelAttributePattern.create("identifier")
                .qualify(Qualifier.ofString("field1"))
                .qualify(Qualifier.ofString("field2")));
  }

  @Test
  public void create_fromList() {
    assertThat(
            CelAttributePattern.create(
                ImmutableList.of(Qualifier.ofString("ident"), Qualifier.ofString("field"))))
        .isEqualTo(CelAttributePattern.create("ident").qualify(Qualifier.ofString("field")));
  }

  @Test
  public void create_invalidListThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CelAttribute.create(
                // EMPTY has special meaning so cannot be created.
                ImmutableList.of()));

    assertThrows(
        IllegalArgumentException.class,
        () ->
            CelAttributePattern.create(
                ImmutableList.of(Qualifier.ofString("not-a-root"), Qualifier.ofInt(1))));
  }
}
