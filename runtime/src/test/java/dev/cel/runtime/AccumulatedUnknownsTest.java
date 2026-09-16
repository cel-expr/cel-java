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

package dev.cel.runtime;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import java.util.Set;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(TestParameterInjector.class)
public final class AccumulatedUnknownsTest {

  @Test
  public void createForAsyncCall_success() {
    AccumulatedUnknowns unknowns = AccumulatedUnknowns.createForAsyncCall(7L, 42L);

    assertThat(unknowns.callIds()).containsExactly(42L);
    assertThat(unknowns.exprIds()).containsExactly(7L);
    assertThat(unknowns.attributes()).isEmpty();
  }

  @Test
  public void createForAsyncCall_adaptedToCelUnknownSet_retainsExprId() {
    AccumulatedUnknowns unknowns = AccumulatedUnknowns.createForAsyncCall(7L, 42L);

    Object adapted = InterpreterUtil.maybeAdaptToCelUnknownSet(unknowns);

    assertThat(adapted).isEqualTo(CelUnknownSet.create(7L));
  }

  @Test
  public void createForAsyncCall_distinctExprIds_adaptToDistinctUnknownSets() {
    Object first =
        InterpreterUtil.maybeAdaptToCelUnknownSet(AccumulatedUnknowns.createForAsyncCall(7L, 42L));
    Object second =
        InterpreterUtil.maybeAdaptToCelUnknownSet(AccumulatedUnknowns.createForAsyncCall(8L, 43L));

    assertThat(first).isNotEqualTo(second);
  }

  @Test
  public void callIds_returnsUnmodifiableSet() {
    AccumulatedUnknowns unknowns = AccumulatedUnknowns.createForAsyncCall(7L, 42L);
    Set<Long> callIds = unknowns.callIds();

    assertThrows(UnsupportedOperationException.class, () -> callIds.add(99L));
  }

  @Test
  public void merge_mergesCallIdsAndExprIdsAndAttributes() {
    AccumulatedUnknowns u1 =
        AccumulatedUnknowns.create(ImmutableList.of(1L), ImmutableList.of(CelAttribute.EMPTY));
    u1.merge(AccumulatedUnknowns.createForAsyncCall(1L, 100L));
    AccumulatedUnknowns u2 = AccumulatedUnknowns.create(ImmutableList.of(2L), ImmutableList.of());
    u2.merge(AccumulatedUnknowns.createForAsyncCall(2L, 200L));

    AccumulatedUnknowns merged = u1.merge(u2);

    assertThat(merged).isSameInstanceAs(u1);
    assertThat(merged.exprIds()).containsExactly(1L, 2L);
    assertThat(merged.attributes()).containsExactly(CelAttribute.EMPTY);
    assertThat(merged.callIds()).containsExactly(100L, 200L);
  }

  @Test
  public void maybeMerge_withNullAccumulator_returnsNewUnknowns() {
    AccumulatedUnknowns u = AccumulatedUnknowns.createForAsyncCall(1L, 1L);

    AccumulatedUnknowns result = AccumulatedUnknowns.maybeMerge(null, u);

    assertThat(result).isSameInstanceAs(u);
  }

  @Test
  public void maybeMerge_withExistingAccumulator_mergesBoth() {
    AccumulatedUnknowns u1 = AccumulatedUnknowns.createForAsyncCall(1L, 1L);
    AccumulatedUnknowns u2 = AccumulatedUnknowns.createForAsyncCall(2L, 2L);

    AccumulatedUnknowns result = AccumulatedUnknowns.maybeMerge(u1, u2);

    assertThat(result).isSameInstanceAs(u1);
    assertThat(result.callIds()).containsExactly(1L, 2L);
    assertThat(result.exprIds()).containsExactly(1L, 2L);
  }

  @Test
  public void maybeMerge_withNonUnknownObject_returnsOriginalAccumulator() {
    AccumulatedUnknowns u = AccumulatedUnknowns.createForAsyncCall(1L, 1L);

    AccumulatedUnknowns result = AccumulatedUnknowns.maybeMerge(u, "not an unknown");

    assertThat(result).isSameInstanceAs(u);
    assertThat(result.callIds()).containsExactly(1L);
  }

  @Test
  public void create_singleExprId_populatesExprIdsOnly() {
    AccumulatedUnknowns u = AccumulatedUnknowns.create(10L);

    assertThat(u.exprIds()).containsExactly(10L);
    assertThat(u.attributes()).isEmpty();
    assertThat(u.callIds()).isEmpty();
  }

  @Test
  public void create_exprIdsAndAttributes_leavesCallIdsEmpty() {
    AccumulatedUnknowns u =
        AccumulatedUnknowns.create(
            ImmutableList.of(10L, 20L), ImmutableList.of(CelAttribute.EMPTY));

    assertThat(u.exprIds()).containsExactly(10L, 20L);
    assertThat(u.attributes()).containsExactly(CelAttribute.EMPTY);
    assertThat(u.callIds()).isEmpty();
  }
}
