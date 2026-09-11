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

package dev.cel.common.internal;

import static com.google.common.base.Strings.isNullOrEmpty;

import com.google.common.collect.ImmutableList;
import com.google.errorprone.annotations.Immutable;
import dev.cel.common.annotations.Internal;
import java.util.PrimitiveIterator;

/**
 * Represents an in-memory read-only contiguous source of Unicode code points.
 *
 * <p>CEL Library Internals. Do Not Use.
 */
@Immutable
@Internal
public abstract class CelCodePointArray {

  // Package-private default constructor to prevent extensions outside of the codebase.
  CelCodePointArray() {}

  /** Returns a new {@link CelCodePointArray} that is a subview of this between [i, j). */
  public abstract CelCodePointArray slice(int i, int j);

  /**
   * Returns the code points between [i, j) as a string.
   *
   * <p>Equivalent to {@code slice(i, j).toString()}, but does not materialize the intermediate
   * view. Lexing and parsing call this for every literal and identifier.
   */
  public abstract String substring(int i, int j);

  /** Get the code point at the given index. */
  public abstract int get(int index);

  /** Returns the number of code points. */
  public abstract int size();

  /** Returns the line offsets. */
  public abstract ImmutableList<Integer> lineOffsets();

  public final int length() {
    return size();
  }

  /** Returns true if empty, false otherwise. */
  public boolean isEmpty() {
    return length() == 0;
  }

  @Override
  public final String toString() {
    return substring(0, size());
  }

  public static CelCodePointArray fromString(String text) {
    if (isNullOrEmpty(text)) {
      return EmptyCodePointArray.INSTANCE;
    }
    PrimitiveIterator.OfInt codePoints = text.codePoints().iterator();
    byte[] byteArray = new byte[text.length()];
    int byteIndex = 0;

    LineOffsetContext lineOffsetContext = new LineOffsetContext();
    while (codePoints.hasNext()) {
      int codePoint = codePoints.nextInt();
      lineOffsetContext.process(codePoint);
      if (codePoint <= 0xff) {
        byteArray[byteIndex++] = (byte) codePoint;
        continue;
      }
      if (codePoint <= 0xffff) {
        char[] charArray = new char[text.length()];
        int charIndex = 0;
        for (; charIndex < byteIndex; charIndex++) {
          charArray[charIndex] = (char) (byteArray[charIndex] & 0xff);
        }
        byteArray = null;
        charArray[charIndex++] = (char) codePoint;
        while (codePoints.hasNext()) {
          codePoint = codePoints.nextInt();
          lineOffsetContext.process(codePoint);
          if (codePoint <= 0xffff) {
            charArray[charIndex++] = (char) codePoint;
            continue;
          }
          int[] intArray = new int[text.length()];
          int intIndex = 0;
          for (; intIndex < charIndex; intIndex++) {
            intArray[intIndex] = charArray[intIndex] & 0xffff;
          }
          charArray = null;
          intArray[intIndex++] = codePoint;
          while (codePoints.hasNext()) {
            codePoint = codePoints.nextInt();
            lineOffsetContext.process(codePoint);
            intArray[intIndex++] = codePoint;
          }

          return SupplementalCodePointArray.create(
              intArray, intIndex, lineOffsetContext.buildLineOffsets());
        }

        return BasicCodePointArray.create(
            charArray, charIndex, lineOffsetContext.buildLineOffsets());
      }
      int[] intArray = new int[text.length()];
      int intIndex = 0;
      for (; intIndex < byteIndex; intIndex++) {
        intArray[intIndex] = byteArray[intIndex] & 0xff;
      }
      byteArray = null;
      intArray[intIndex++] = codePoint;
      while (codePoints.hasNext()) {
        codePoint = codePoints.nextInt();
        lineOffsetContext.process(codePoint);
        intArray[intIndex++] = codePoint;
      }

      return SupplementalCodePointArray.create(
          intArray, intIndex, lineOffsetContext.buildLineOffsets());
    }

    return Latin1CodePointArray.create(byteArray, byteIndex, lineOffsetContext.buildLineOffsets());
  }

  private static class LineOffsetContext {
    private static final int NEWLINE_CODE_POINT = 10;

    private final ImmutableList.Builder<Integer> lineOffsetBuilder;
    private int lineOffsetCodePoints;

    private void process(int codePoint) {
      lineOffsetCodePoints++;
      if (codePoint == NEWLINE_CODE_POINT) {
        lineOffsetBuilder.add(lineOffsetCodePoints);
      }
    }

    private ImmutableList<Integer> buildLineOffsets() {
      return lineOffsetBuilder.add(lineOffsetCodePoints + 1).build();
    }

    private LineOffsetContext() {
      this.lineOffsetBuilder = ImmutableList.builder();
    }
  }
}
