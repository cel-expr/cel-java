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

package dev.cel.common;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class CelOptionsTest {

  @Test
  public void current_success_celOptions() {
    CelOptions options = CelOptions.current().build();
    assertThat(options).isNotNull();
    assertThat(options.enableRegexPartialMatch()).isTrue();
  }

  @Test
  public void current_defaults() {
    // Defaults that aren't represented in deprecated CelOptions
    assertThat(CelOptions.current().build().enableUnknownTracking()).isFalse();
    assertThat(CelOptions.current().build().resolveTypeDependencies()).isTrue();
    assertThat(CelOptions.current().build().enablePrattParser()).isFalse();
  }
}
