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

package dev.cel.testing;

import static com.google.common.truth.Truth.assertThat;

import dev.cel.expr.Expr;
import dev.cel.expr.SourceInfo;
import dev.cel.common.CelSourceLocation;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class CelLocationAdornerTest {

  @Test
  public void getLocation_missingPosition_returnsEmpty() {
    SourceInfo sourceInfo = SourceInfo.getDefaultInstance();
    CelLocationAdorner adorner = new CelLocationAdorner(sourceInfo);

    assertThat(adorner.getLocation(1L)).isEmpty();
    assertThat(adorner.adorn(Expr.newBuilder().setId(1L).build())).isEqualTo("^#1[NO_POS]#");
  }

  @Test
  public void getLocation_singleLineExpression() {
    SourceInfo sourceInfo =
        SourceInfo.newBuilder().putPositions(1L, 0).putPositions(2L, 4).build();
    CelLocationAdorner adorner = new CelLocationAdorner(sourceInfo);

    assertThat(adorner.getLocation(1L)).hasValue(CelSourceLocation.of(1, 0));
    assertThat(adorner.getLocation(2L)).hasValue(CelSourceLocation.of(1, 4));
    assertThat(adorner.adorn(Expr.newBuilder().setId(1L).build())).isEqualTo("^#1[1,0]#");
    assertThat(adorner.adorn(Expr.newBuilder().setId(2L).build())).isEqualTo("^#2[1,4]#");
  }

  @Test
  public void getLocation_multiLineExpression() {
    // Expression:
    // Line 1: "foo\n"      -> line offset 4 (first character after newline is index 4)
    // Line 2: "  + bar\n"  -> line offset 12 (4 + 8 = 12)
    // Line 3: "  + baz"    -> end
    SourceInfo sourceInfo =
        SourceInfo.newBuilder()
            .addLineOffsets(4)
            .addLineOffsets(12)
            .putPositions(1L, 0) // 'foo' on line 1, col 0
            .putPositions(2L, 8) // 'bar' on line 2, col (8 - 4) = 4
            .putPositions(3L, 16) // 'baz' on line 3, col (16 - 12) = 4
            .build();
    CelLocationAdorner adorner = new CelLocationAdorner(sourceInfo);

    assertThat(adorner.getLocation(1L)).hasValue(CelSourceLocation.of(1, 0));
    assertThat(adorner.getLocation(2L)).hasValue(CelSourceLocation.of(2, 4));
    assertThat(adorner.getLocation(3L)).hasValue(CelSourceLocation.of(3, 4));
    assertThat(adorner.adorn(Expr.newBuilder().setId(2L).build())).isEqualTo("^#2[2,4]#");
    assertThat(adorner.adorn(Expr.newBuilder().setId(3L).build())).isEqualTo("^#3[3,4]#");
  }
}
