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

package dev.cel.common.internal;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class ErrorsTest {

  @Test
  public void getLineOffsets() {
    // There should be one offset for each newline and one for EOF.
    Errors errors = new Errors("test", "\n\n\n\n");
    assertThat(errors.getLineOffsets().toArray()).isEqualTo(new int[] {1, 2, 3, 4, 5});
  }

  @Test
  public void getLocationAt() {
    Errors errors = new Errors("test", "hello\nworld");
    assertThat(errors.getLocationAt(0)).isEqualTo("test:1:1");
    assertThat(errors.getLocationAt(6)).isEqualTo("test:2:1");
    assertThat(errors.getLocationAt(11)).isEqualTo("test:2:6"); // EOF
    assertThat(errors.getLocationAt(12)).isEqualTo("test:3:1"); // beyond EOF
  }

  @Test
  public void getPositionLocation() {
    Errors errors = new Errors("test", "hello\nworld");
    assertThat(errors.getPositionLocation(0)).isEqualTo(Errors.SourceLocation.of(1, 1));
    assertThat(errors.getPositionLocation(6)).isEqualTo(Errors.SourceLocation.of(2, 1));
    assertThat(errors.getPositionLocation(11)).isEqualTo(Errors.SourceLocation.of(2, 6));
    assertThat(errors.getPositionLocation(12)).isEqualTo(Errors.SourceLocation.of(3, 1));
  }

  @Test
  public void getSnippet() {
    Errors errors = new Errors("test", "hello\nworld\n");
    assertThat(errors.getSnippet(1)).hasValue("hello");
    assertThat(errors.getSnippet(2)).hasValue("world");
    assertThat(errors.getSnippet(3)).hasValue("");
    assertThat(errors.getSnippet(4)).isEmpty();
  }
}
