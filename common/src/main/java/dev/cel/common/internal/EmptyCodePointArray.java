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

import static com.google.common.base.Preconditions.checkPositionIndexes;

import com.google.common.collect.ImmutableList;
import com.google.errorprone.annotations.DoNotCall;
import com.google.errorprone.annotations.Immutable;
import dev.cel.common.annotations.Internal;

/**
 * Represents an empty code point.
 *
 * <p>CEL Library Internals. Do Not Use.
 */
@Immutable
@Internal
final class EmptyCodePointArray extends CelCodePointArray {

  static final EmptyCodePointArray INSTANCE = new EmptyCodePointArray();

  private EmptyCodePointArray() {}

  @Override
  public BasicCodePointArray slice(int i, int j) {
    if (i < 0) {
      throw new IndexOutOfBoundsException(String.format("index (%s) must not be negative", i));
    }
    throw new IndexOutOfBoundsException(
        String.format("index (%s) must not be greater than size (0)", i));
  }

  @Override
  @DoNotCall
  public int get(int index) {
    if (index < 0) {
      throw new IndexOutOfBoundsException(String.format("index (%s) must not be negative", index));
    }
    throw new IndexOutOfBoundsException(
        String.format("index (%s) must not be greater than size (0)", index));
  }

  @Override
  public String substring(int i, int j) {
    checkPositionIndexes(i, j, 0);
    return "";
  }

  @Override
  public int size() {
    return 0;
  }

  @Override
  public ImmutableList<Integer> lineOffsets() {
    return ImmutableList.of(1);
  }
}
