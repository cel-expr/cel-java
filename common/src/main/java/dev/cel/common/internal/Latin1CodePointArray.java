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

import static com.google.common.base.Preconditions.checkElementIndex;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkPositionIndexes;
import static java.nio.charset.StandardCharsets.ISO_8859_1;

import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableList;
import com.google.errorprone.annotations.Immutable;
import dev.cel.common.annotations.Internal;

/**
 * Represents an in-memory contiguous source of ISO-LATIN-1 code points.
 *
 * <p>CEL Library Internals. Do Not Use.
 */
@Immutable
@Internal
@AutoValue
@AutoValue.CopyAnnotations
@SuppressWarnings("Immutable") // byte[] is not exposed externally, thus cannot be mutated.
public abstract class Latin1CodePointArray extends CelCodePointArray {

  @SuppressWarnings("mutable")
  abstract byte[] codePoints();

  abstract int offset();

  static Latin1CodePointArray create(
      byte[] codePoints, int size, ImmutableList<Integer> lineOffsets) {
    return create(codePoints, 0, lineOffsets, size);
  }

  static Latin1CodePointArray create(
      byte[] codePoints, int offset, ImmutableList<Integer> lineOffsets, int size) {
    return new AutoValue_Latin1CodePointArray(size, checkNotNull(lineOffsets), codePoints, offset);
  }

  @Override
  public Latin1CodePointArray slice(int i, int j) {
    checkPositionIndexes(i, j, size());
    return create(codePoints(), offset() + i, lineOffsets(), j - i);
  }

  @Override
  public String substring(int i, int j) {
    checkPositionIndexes(i, j, size());
    return new String(codePoints(), offset() + i, j - i, ISO_8859_1);
  }

  @Override
  public int get(int index) {
    checkElementIndex(index, size());
    return Byte.toUnsignedInt(codePoints()[offset() + index]);
  }
}
