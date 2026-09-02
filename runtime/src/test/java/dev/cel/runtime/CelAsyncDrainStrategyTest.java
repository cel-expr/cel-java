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
import java.time.Duration;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class CelAsyncDrainStrategyTest {

  private static final CelAsyncCall DUMMY_CALL =
      new CelAsyncCall() {
        @Override
        public long callId() {
          return 1L;
        }

        @Override
        public long exprId() {
          return 10L;
        }

        @Override
        public String functionName() {
          return "fn";
        }

        @Override
        public String overloadId() {
          return "fn_overload";
        }

        @Override
        public ImmutableList<Object> arguments() {
          return ImmutableList.of();
        }

        @Override
        public Duration elapsedDuration() {
          return Duration.ZERO;
        }
      };

  @Test
  public void drainReady_defaultDebounce_noActiveCalls_reevaluates() {
    CelAsyncDrainStrategy strategy = CelAsyncDrainStrategy.drainReady();

    CelAsyncDrainAction action = strategy.nextAction(ImmutableList.of(), 0);

    assertThat(action.shouldReevaluate()).isTrue();
    assertThat(action.waitDuration()).isEqualTo(Duration.ZERO);
  }

  @Test
  public void drainReady_defaultDebounce_emptyBatchWithActiveCalls_waitsForMore() {
    CelAsyncDrainStrategy strategy = CelAsyncDrainStrategy.drainReady();

    CelAsyncDrainAction action = strategy.nextAction(ImmutableList.of(), 3);

    assertThat(action.shouldReevaluate()).isFalse();
    assertThat(action.waitDuration()).isEqualTo(Duration.ZERO);
  }

  @Test
  public void drainReady_defaultDebounce_hasBatchWithActiveCalls_waitsDefaultDuration() {
    CelAsyncDrainStrategy strategy = CelAsyncDrainStrategy.drainReady();

    CelAsyncDrainAction action = strategy.nextAction(ImmutableList.of(DUMMY_CALL), 2);

    assertThat(action.shouldReevaluate()).isFalse();
    assertThat(action.waitDuration()).isEqualTo(Duration.ofNanos(100_000));
  }

  @Test
  public void drainReady_zeroDebounce_alwaysReevaluates() {
    CelAsyncDrainStrategy strategy = CelAsyncDrainStrategy.drainReady(Duration.ZERO);

    CelAsyncDrainAction actionWithActive = strategy.nextAction(ImmutableList.of(DUMMY_CALL), 5);

    assertThat(actionWithActive.shouldReevaluate()).isTrue();
    assertThat(actionWithActive.waitDuration()).isEqualTo(Duration.ZERO);
  }

  @Test
  public void drainReady_withDebounce_activeZero_reevaluates() {
    CelAsyncDrainStrategy strategy = CelAsyncDrainStrategy.drainReady(Duration.ofMillis(50));

    CelAsyncDrainAction action = strategy.nextAction(ImmutableList.of(), 0);

    assertThat(action.shouldReevaluate()).isTrue();
    assertThat(action.waitDuration()).isEqualTo(Duration.ZERO);
  }

  @Test
  public void drainReady_withDebounce_emptyBatchAndActiveCalls_waitsForMore() {
    CelAsyncDrainStrategy strategy = CelAsyncDrainStrategy.drainReady(Duration.ofMillis(50));

    CelAsyncDrainAction action = strategy.nextAction(ImmutableList.of(), 3);

    assertThat(action.shouldReevaluate()).isFalse();
    assertThat(action.waitDuration()).isEqualTo(Duration.ZERO);
  }

  @Test
  public void drainReady_withDebounce_hasCompletedBatchAndActiveCalls_waitsDuration() {
    CelAsyncDrainStrategy strategy = CelAsyncDrainStrategy.drainReady(Duration.ofMillis(50));

    CelAsyncDrainAction action = strategy.nextAction(ImmutableList.of(DUMMY_CALL), 2);

    assertThat(action.shouldReevaluate()).isFalse();
    assertThat(action.waitDuration()).isEqualTo(Duration.ofMillis(50));
  }

  @Test
  public void drainNone_behavior() {
    CelAsyncDrainStrategy strategy = CelAsyncDrainStrategy.drainNone();

    assertThat(strategy.nextAction(ImmutableList.of(), 0).shouldReevaluate()).isTrue();
    assertThat(strategy.nextAction(ImmutableList.of(DUMMY_CALL), 2).shouldReevaluate()).isTrue();
    assertThat(strategy.nextAction(ImmutableList.of(), 2).shouldReevaluate()).isFalse();
  }

  @Test
  public void drainAll_behavior() {
    CelAsyncDrainStrategy strategy = CelAsyncDrainStrategy.drainAll();

    assertThat(strategy.nextAction(ImmutableList.of(), 0).shouldReevaluate()).isTrue();
    assertThat(strategy.nextAction(ImmutableList.of(DUMMY_CALL), 0).shouldReevaluate()).isTrue();
    assertThat(strategy.nextAction(ImmutableList.of(DUMMY_CALL), 1).shouldReevaluate()).isFalse();
    assertThat(strategy.nextAction(ImmutableList.of(), 1).shouldReevaluate()).isFalse();
  }

  @Test
  public void drainReady_negativeDebounce_throwsException() {
    assertThrows(
        IllegalArgumentException.class,
        () -> CelAsyncDrainStrategy.drainReady(Duration.ofMillis(-1)));
  }

  @Test
  public void drainAction_reevaluate() {
    CelAsyncDrainAction action = CelAsyncDrainAction.reevaluate();

    assertThat(action.shouldReevaluate()).isTrue();
    assertThat(action.waitDuration()).isEqualTo(Duration.ZERO);
  }

  @Test
  public void drainAction_waitForMore() {
    CelAsyncDrainAction action = CelAsyncDrainAction.waitForMore();

    assertThat(action.shouldReevaluate()).isFalse();
    assertThat(action.waitDuration()).isEqualTo(Duration.ZERO);
  }

  @Test
  public void drainAction_waitDuration_success() {
    CelAsyncDrainAction action = CelAsyncDrainAction.waitDuration(Duration.ofSeconds(2));

    assertThat(action.shouldReevaluate()).isFalse();
    assertThat(action.waitDuration()).isEqualTo(Duration.ofSeconds(2));
  }

  @Test
  public void drainAction_waitZero_reevaluates() {
    CelAsyncDrainAction action = CelAsyncDrainAction.waitDuration(Duration.ZERO);

    assertThat(action.shouldReevaluate()).isTrue();
    assertThat(action.waitDuration()).isEqualTo(Duration.ZERO);
  }

  @Test
  public void drainAction_waitDuration_negative_throwsException() {
    assertThrows(
        IllegalArgumentException.class,
        () -> CelAsyncDrainAction.waitDuration(Duration.ofMillis(-5)));
  }
}
