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

package dev.cel.optimizer;

import static com.google.common.truth.Truth.assertThat;

import dev.cel.bundle.CelFactory;
import dev.cel.compiler.CelCompilerFactory;
import dev.cel.parser.CelParserFactory;
import dev.cel.runtime.CelRuntimeFactory;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class CelOptimizerFactoryTest {

  @Test
  public void standardCelOptimizerBuilder_withParserCheckerAndRuntime() {
    CelOptimizerBuilder builder =
        CelOptimizerFactory.standardCelOptimizerBuilder(
            CelParserFactory.standardCelParserBuilder().build(),
            CelCompilerFactory.standardCelCheckerBuilder().build(),
            CelRuntimeFactory.standardCelRuntimeBuilder().build());

    assertThat(builder).isNotNull();
    assertThat(builder.build()).isNotNull();
  }

  @Test
  public void standardCelOptimizerBuilder_withCompilerAndRuntime() {
    CelOptimizerBuilder builder =
        CelOptimizerFactory.standardCelOptimizerBuilder(
            CelCompilerFactory.standardCelCompilerBuilder().build(),
            CelRuntimeFactory.standardCelRuntimeBuilder().build());

    assertThat(builder).isNotNull();
    assertThat(builder.build()).isNotNull();
  }

  @Test
  public void standardCelOptimizerBuilder_withCel() {
    CelOptimizerBuilder builder =
        CelOptimizerFactory.standardCelOptimizerBuilder(CelFactory.standardCelBuilder().build());

    assertThat(builder).isNotNull();
    assertThat(builder.build()).isNotNull();
  }
}
