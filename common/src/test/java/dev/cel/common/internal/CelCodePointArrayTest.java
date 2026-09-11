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

package dev.cel.common.internal;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableList;
import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import com.google.testing.junit.testparameterinjector.TestParameterValuesProvider;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(TestParameterInjector.class)
public final class CelCodePointArrayTest {

  @Test
  public void computeLineOffset(
      @TestParameter(valuesProvider = LineOffsetDataProvider.class) LineOffsetTestCase testCase) {
    CelCodePointArray codePointArray = CelCodePointArray.fromString(testCase.text());

    assertThat(codePointArray.lineOffsets())
        .containsExactlyElementsIn(testCase.offsets())
        .inOrder();
  }

  @Test
  public void substring_empty() {
    CelCodePointArray empty = CelCodePointArray.fromString("");
    assertThat(empty).isInstanceOf(EmptyCodePointArray.class);

    assertThat(empty.substring(0, 0)).isEmpty();
    assertThrows(IndexOutOfBoundsException.class, () -> empty.substring(0, 1));
    assertThrows(IndexOutOfBoundsException.class, () -> empty.substring(-1, 0));
    assertThrows(IndexOutOfBoundsException.class, () -> empty.substring(1, 0));
    assertThrows(IndexOutOfBoundsException.class, () -> empty.substring(1, 1));
  }

  @Test
  public void substring_latin1() {
    CelCodePointArray latin1 = CelCodePointArray.fromString("hello world");
    assertThat(latin1).isInstanceOf(Latin1CodePointArray.class);

    assertThat(latin1.substring(0, 5)).isEqualTo("hello");
    assertThat(latin1.substring(6, 11)).isEqualTo("world");
    assertThat(latin1.substring(0, 11)).isEqualTo("hello world");
    assertThat(latin1.substring(3, 3)).isEmpty();

    assertThrows(IndexOutOfBoundsException.class, () -> latin1.substring(-1, 5));
    assertThrows(IndexOutOfBoundsException.class, () -> latin1.substring(0, 12));
    assertThrows(IndexOutOfBoundsException.class, () -> latin1.substring(5, 4));

    // Test on a sliced subview to ensure bounds are checked against size(), not the backing buffer
    // length
    CelCodePointArray sliced = latin1.slice(1, 4); // "ell", size = 3, buffer length = 11
    assertThat(sliced.substring(0, 3)).isEqualTo("ell");
    assertThat(sliced.substring(1, 2)).isEqualTo("l");

    assertThrows(IndexOutOfBoundsException.class, () -> sliced.substring(-1, 2));
    assertThrows(IndexOutOfBoundsException.class, () -> sliced.substring(0, 4));
    assertThrows(IndexOutOfBoundsException.class, () -> sliced.substring(2, 1));
  }

  @Test
  public void substring_basic() {
    CelCodePointArray basic = CelCodePointArray.fromString("abc \uff20 def");
    assertThat(basic).isInstanceOf(BasicCodePointArray.class);

    assertThat(basic.substring(0, 3)).isEqualTo("abc");
    assertThat(basic.substring(4, 5)).isEqualTo("\uff20");
    assertThat(basic.substring(6, 9)).isEqualTo("def");
    assertThat(basic.substring(0, 9)).isEqualTo("abc \uff20 def");
    assertThat(basic.substring(3, 3)).isEmpty();

    assertThrows(IndexOutOfBoundsException.class, () -> basic.substring(-1, 5));
    assertThrows(IndexOutOfBoundsException.class, () -> basic.substring(0, 10));
    assertThrows(IndexOutOfBoundsException.class, () -> basic.substring(5, 4));

    // Test on a sliced subview to ensure bounds are checked against size(), not the backing buffer
    // length
    CelCodePointArray sliced = basic.slice(1, 5); // "bc \uff20", size = 4, buffer length = 9
    assertThat(sliced.substring(0, 4)).isEqualTo("bc \uff20");

    assertThrows(IndexOutOfBoundsException.class, () -> sliced.substring(-1, 2));
    assertThrows(IndexOutOfBoundsException.class, () -> sliced.substring(0, 5));
    assertThrows(IndexOutOfBoundsException.class, () -> sliced.substring(2, 1));
  }

  @Test
  public void substring_supplemental() {
    CelCodePointArray supp = CelCodePointArray.fromString(" text 가나다 😦😁😑 ");
    assertThat(supp).isInstanceOf(SupplementalCodePointArray.class);

    assertThat(supp.substring(0, 5)).isEqualTo(" text");
    assertThat(supp.substring(10, 13)).isEqualTo("😦😁😑");
    assertThat(supp.substring(0, supp.size())).isEqualTo(" text 가나다 😦😁😑 ");
    assertThat(supp.substring(3, 3)).isEmpty();

    assertThrows(IndexOutOfBoundsException.class, () -> supp.substring(-1, 5));
    int greaterThanSize = supp.size() + 1;
    assertThrows(IndexOutOfBoundsException.class, () -> supp.substring(0, greaterThanSize));
    assertThrows(IndexOutOfBoundsException.class, () -> supp.substring(5, 4));

    // Test on a sliced subview to ensure bounds are checked against size(), not the backing buffer
    // length
    CelCodePointArray sliced = supp.slice(1, 5); // "text", size = 4, buffer length = 15
    assertThat(sliced.substring(0, 4)).isEqualTo("text");

    assertThrows(IndexOutOfBoundsException.class, () -> sliced.substring(-1, 2));
    assertThrows(IndexOutOfBoundsException.class, () -> sliced.substring(0, 5));
    assertThrows(IndexOutOfBoundsException.class, () -> sliced.substring(2, 1));
  }

  @AutoValue
  abstract static class LineOffsetTestCase {
    abstract String text();

    abstract ImmutableList<Integer> offsets();

    static LineOffsetTestCase of(String text, Integer... offsets) {
      return of(text, Arrays.asList(offsets));
    }

    static LineOffsetTestCase of(String text, List<Integer> offsets) {
      return new AutoValue_CelCodePointArrayTest_LineOffsetTestCase(
          text, ImmutableList.copyOf(offsets));
    }
  }

  private static final class LineOffsetDataProvider extends TestParameterValuesProvider {

    @Override
    protected List<LineOffsetTestCase> provideValues(Context context) {
      return Arrays.asList(
          // Empty
          LineOffsetTestCase.of("", 1),
          // ISO-8859-1
          LineOffsetTestCase.of("hello world", 12),
          LineOffsetTestCase.of("hello\nworld", 6, 12),
          LineOffsetTestCase.of("hello\nworld\n\nfoo\n", 6, 12, 13, 17, 18),
          // BMP
          LineOffsetTestCase.of("abc 가나다", 8),
          LineOffsetTestCase.of("abc\n가나다\n我b很好\n", 4, 8, 13, 14),
          // SMP
          LineOffsetTestCase.of(" text 가나다 😦😁😑 ", 15),
          LineOffsetTestCase.of(" text\n가나다 \n😦😁😑\n\n", 6, 11, 15, 16, 17));
    }
  }
}
