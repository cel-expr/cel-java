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

package dev.cel.parser;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.testing.EqualsTester;
import dev.cel.common.Operator;
import java.util.EnumSet;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class CelStandardMacroTest {

  @Test
  public void getFunction() {
    assertThat(CelStandardMacro.HAS.getFunction()).isEqualTo(Operator.HAS.getFunction());
    assertThat(CelStandardMacro.ALL.getFunction()).isEqualTo(Operator.ALL.getFunction());
    assertThat(CelStandardMacro.EXISTS.getFunction()).isEqualTo(Operator.EXISTS.getFunction());
    assertThat(CelStandardMacro.EXISTS_ONE.getFunction())
        .isEqualTo(Operator.EXISTS_ONE.getFunction());
    assertThat(CelStandardMacro.EXISTS_ONE_NEW.getFunction())
        .isEqualTo(Operator.EXISTS_ONE_NEW.getFunction());
    assertThat(CelStandardMacro.FILTER.getFunction()).isEqualTo(Operator.FILTER.getFunction());
    assertThat(CelStandardMacro.MAP.getFunction()).isEqualTo(Operator.MAP.getFunction());
    assertThat(CelStandardMacro.MAP_FILTER.getFunction()).isEqualTo(Operator.MAP.getFunction());
  }

  @Test
  public void testHas() {
    assertThat(CelStandardMacro.HAS.getFunction()).isEqualTo(Operator.HAS.getFunction());
    assertThat(CelStandardMacro.HAS.getDefinition().getArgumentCount()).isEqualTo(1);
    assertThat(CelStandardMacro.HAS.getDefinition().isReceiverStyle()).isFalse();
    assertThat(CelStandardMacro.HAS.getDefinition().getKey()).isEqualTo("has:1:false");
    assertThat(CelStandardMacro.HAS.getDefinition().isVariadic()).isFalse();
    assertThat(CelStandardMacro.HAS.getDefinition().toString())
        .isEqualTo(CelStandardMacro.HAS.getDefinition().getKey());
    assertThat(CelStandardMacro.HAS.getDefinition().hashCode())
        .isEqualTo(CelStandardMacro.HAS.getDefinition().getKey().hashCode());
  }

  @Test
  public void testAll() {
    assertThat(CelStandardMacro.ALL.getFunction()).isEqualTo(Operator.ALL.getFunction());
    assertThat(CelStandardMacro.ALL.getDefinition().getArgumentCount()).isEqualTo(2);
    assertThat(CelStandardMacro.ALL.getDefinition().isReceiverStyle()).isTrue();
    assertThat(CelStandardMacro.ALL.getDefinition().getKey()).isEqualTo("all:2:true");
    assertThat(CelStandardMacro.ALL.getDefinition().isVariadic()).isFalse();
    assertThat(CelStandardMacro.ALL.getDefinition().toString())
        .isEqualTo(CelStandardMacro.ALL.getDefinition().getKey());
    assertThat(CelStandardMacro.ALL.getDefinition().hashCode())
        .isEqualTo(CelStandardMacro.ALL.getDefinition().getKey().hashCode());
  }

  @Test
  public void testExists() {
    assertThat(CelStandardMacro.EXISTS.getFunction()).isEqualTo(Operator.EXISTS.getFunction());
    assertThat(CelStandardMacro.EXISTS.getDefinition().getArgumentCount()).isEqualTo(2);
    assertThat(CelStandardMacro.EXISTS.getDefinition().isReceiverStyle()).isTrue();
    assertThat(CelStandardMacro.EXISTS.getDefinition().getKey()).isEqualTo("exists:2:true");
    assertThat(CelStandardMacro.EXISTS.getDefinition().isVariadic()).isFalse();
    assertThat(CelStandardMacro.EXISTS.getDefinition().toString())
        .isEqualTo(CelStandardMacro.EXISTS.getDefinition().getKey());
    assertThat(CelStandardMacro.EXISTS.getDefinition().hashCode())
        .isEqualTo(CelStandardMacro.EXISTS.getDefinition().getKey().hashCode());
  }

  @Test
  public void testExistsOne() {
    assertThat(CelStandardMacro.EXISTS_ONE.getFunction())
        .isEqualTo(Operator.EXISTS_ONE.getFunction());
    assertThat(CelStandardMacro.EXISTS_ONE.getDefinition().getArgumentCount()).isEqualTo(2);
    assertThat(CelStandardMacro.EXISTS_ONE.getDefinition().isReceiverStyle()).isTrue();
    assertThat(CelStandardMacro.EXISTS_ONE.getDefinition().getKey()).isEqualTo("exists_one:2:true");
    assertThat(CelStandardMacro.EXISTS_ONE.getDefinition().isVariadic()).isFalse();
    assertThat(CelStandardMacro.EXISTS_ONE.getDefinition().toString())
        .isEqualTo(CelStandardMacro.EXISTS_ONE.getDefinition().getKey());
    assertThat(CelStandardMacro.EXISTS_ONE.getDefinition().hashCode())
        .isEqualTo(CelStandardMacro.EXISTS_ONE.getDefinition().getKey().hashCode());
  }

  @Test
  public void testExistsOneNew() {
    assertThat(CelStandardMacro.EXISTS_ONE_NEW.getFunction())
        .isEqualTo(Operator.EXISTS_ONE_NEW.getFunction());
    assertThat(CelStandardMacro.EXISTS_ONE_NEW.getDefinition().getArgumentCount()).isEqualTo(2);
    assertThat(CelStandardMacro.EXISTS_ONE_NEW.getDefinition().isReceiverStyle()).isTrue();
    assertThat(CelStandardMacro.EXISTS_ONE_NEW.getDefinition().getKey())
        .isEqualTo("existsOne:2:true");
    assertThat(CelStandardMacro.EXISTS_ONE_NEW.getDefinition().isVariadic()).isFalse();
    assertThat(CelStandardMacro.EXISTS_ONE_NEW.getDefinition().toString())
        .isEqualTo(CelStandardMacro.EXISTS_ONE_NEW.getDefinition().getKey());
    assertThat(CelStandardMacro.EXISTS_ONE_NEW.getDefinition().hashCode())
        .isEqualTo(CelStandardMacro.EXISTS_ONE_NEW.getDefinition().getKey().hashCode());
  }

  @Test
  public void testMap2() {
    assertThat(CelStandardMacro.MAP.getFunction()).isEqualTo(Operator.MAP.getFunction());
    assertThat(CelStandardMacro.MAP.getDefinition().getArgumentCount()).isEqualTo(2);
    assertThat(CelStandardMacro.MAP.getDefinition().isReceiverStyle()).isTrue();
    assertThat(CelStandardMacro.MAP.getDefinition().getKey()).isEqualTo("map:2:true");
    assertThat(CelStandardMacro.MAP.getDefinition().isVariadic()).isFalse();
    assertThat(CelStandardMacro.MAP.getDefinition().toString())
        .isEqualTo(CelStandardMacro.MAP.getDefinition().getKey());
    assertThat(CelStandardMacro.MAP.getDefinition().hashCode())
        .isEqualTo(CelStandardMacro.MAP.getDefinition().getKey().hashCode());
  }

  @Test
  public void testMap3() {
    assertThat(CelStandardMacro.MAP_FILTER.getFunction()).isEqualTo(Operator.MAP.getFunction());
    assertThat(CelStandardMacro.MAP_FILTER.getDefinition().getArgumentCount()).isEqualTo(3);
    assertThat(CelStandardMacro.MAP_FILTER.getDefinition().isReceiverStyle()).isTrue();
    assertThat(CelStandardMacro.MAP_FILTER.getDefinition().getKey()).isEqualTo("map:3:true");
    assertThat(CelStandardMacro.MAP_FILTER.getDefinition().isVariadic()).isFalse();
    assertThat(CelStandardMacro.MAP_FILTER.getDefinition().toString())
        .isEqualTo(CelStandardMacro.MAP_FILTER.getDefinition().getKey());
    assertThat(CelStandardMacro.MAP_FILTER.getDefinition().hashCode())
        .isEqualTo(CelStandardMacro.MAP_FILTER.getDefinition().getKey().hashCode());
  }

  @Test
  public void testFilter() {
    assertThat(CelStandardMacro.FILTER.getFunction()).isEqualTo(Operator.FILTER.getFunction());
    assertThat(CelStandardMacro.FILTER.getDefinition().getArgumentCount()).isEqualTo(2);
    assertThat(CelStandardMacro.FILTER.getDefinition().isReceiverStyle()).isTrue();
    assertThat(CelStandardMacro.FILTER.getDefinition().getKey()).isEqualTo("filter:2:true");
    assertThat(CelStandardMacro.FILTER.getDefinition().isVariadic()).isFalse();
    assertThat(CelStandardMacro.FILTER.getDefinition().toString())
        .isEqualTo(CelStandardMacro.FILTER.getDefinition().getKey());
    assertThat(CelStandardMacro.FILTER.getDefinition().hashCode())
        .isEqualTo(CelStandardMacro.FILTER.getDefinition().getKey().hashCode());
  }

  @Test
  public void testEquals() {
    new EqualsTester()
        .addEqualityGroup(CelStandardMacro.HAS)
        .addEqualityGroup(CelStandardMacro.ALL)
        .addEqualityGroup(CelStandardMacro.EXISTS)
        .addEqualityGroup(CelStandardMacro.EXISTS_ONE)
        .addEqualityGroup(CelStandardMacro.EXISTS_ONE_NEW)
        .addEqualityGroup(CelStandardMacro.MAP)
        .addEqualityGroup(CelStandardMacro.MAP_FILTER)
        .addEqualityGroup(CelStandardMacro.FILTER)
        .testEquals();
  }

  @Test
  public void standardMacros_containsAllStandardMacros() {
    assertThat(CelStandardMacro.STANDARD_MACROS)
        .containsExactlyElementsIn(EnumSet.allOf(CelStandardMacro.class));
  }
}
