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

package dev.cel.parser;

import static com.google.common.truth.Truth.assertThat;

import dev.cel.common.CelValidationResult;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class CelLiteParserAndroidTest {

  @Test
  public void newLiteParserBuilder_build_isNotNull() {
    assertThat(CelLiteParserFactory.newLiteParserBuilder().build()).isNotNull();
  }

  @Test
  public void newLiteParserBuilder_parseSmokeTest() {
    CelParser parser = CelLiteParserFactory.newLiteParserBuilder().build();

    CelValidationResult result = parser.parse("1 + 1");

    assertThat(result.hasError()).isFalse();
  }
}
