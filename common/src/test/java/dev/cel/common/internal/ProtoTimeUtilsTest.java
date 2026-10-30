// Copyright 2025 Google LLC
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
import static com.google.common.truth.extensions.proto.ProtoTruth.assertThat;
import static dev.cel.common.internal.ProtoTimeUtils.DURATION_SECONDS_MAX;
import static dev.cel.common.internal.ProtoTimeUtils.DURATION_SECONDS_MIN;
import static dev.cel.common.internal.ProtoTimeUtils.DURATION_ZERO;
import static dev.cel.common.internal.ProtoTimeUtils.MAX_NANOS;
import static dev.cel.common.internal.ProtoTimeUtils.TIMESTAMP_SECONDS_MAX;
import static dev.cel.common.internal.ProtoTimeUtils.TIMESTAMP_SECONDS_MIN;
import static org.junit.Assert.assertThrows;

import com.google.protobuf.Duration;
import com.google.protobuf.Timestamp;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import com.google.testing.junit.testparameterinjector.TestParameters;
import java.time.Instant;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(TestParameterInjector.class)
// ProtoTimeUtils operates directly on protobuf Duration/Timestamp seconds and nanos fields.
@SuppressWarnings("GoodTime-ApiWithNumericTimeUnit")
public class ProtoTimeUtilsTest {

  @Test
  public void toJavaInstant_overRfc3339Range() {
    Timestamp ts = Timestamp.newBuilder().setSeconds(TIMESTAMP_SECONDS_MAX + 1).build();

    Instant instant = ProtoTimeUtils.toJavaInstant(ts);

    assertThat(instant).isEqualTo(Instant.ofEpochSecond(TIMESTAMP_SECONDS_MAX + 1));
  }

  @Test
  public void toJavaInstant_underRfc3339Range() {
    Timestamp ts = Timestamp.newBuilder().setSeconds(TIMESTAMP_SECONDS_MIN - 1).build();

    Instant instant = ProtoTimeUtils.toJavaInstant(ts);

    assertThat(instant).isEqualTo(Instant.ofEpochSecond(TIMESTAMP_SECONDS_MIN - 1));
  }

  @Test
  public void toJavaDuration_overRfc3339Range() {
    Duration d = Duration.newBuilder().setSeconds(DURATION_SECONDS_MAX + 1).build();

    java.time.Duration duration = ProtoTimeUtils.toJavaDuration(d);

    assertThat(duration).isEqualTo(java.time.Duration.ofSeconds(DURATION_SECONDS_MAX + 1));
  }

  @Test
  public void toJavaDuration_underRfc3339Range() {
    Duration d = Duration.newBuilder().setSeconds(DURATION_SECONDS_MIN - 1).build();

    java.time.Duration duration = ProtoTimeUtils.toJavaDuration(d);

    assertThat(duration).isEqualTo(java.time.Duration.ofSeconds(DURATION_SECONDS_MIN - 1));
  }

  @Test
  @TestParameters("{seconds: -62135596800}")
  @TestParameters("{seconds: 0}")
  @TestParameters("{seconds: 253402300799}")
  public void fromSecondsToTimestamp_valid(long seconds) {
    assertThat(ProtoTimeUtils.fromSecondsToTimestamp(seconds))
        .isEqualTo(Timestamp.newBuilder().setSeconds(seconds).build());
  }

  @Test
  @TestParameters("{seconds: -62135596801}")
  @TestParameters("{seconds: 253402300800}")
  @TestParameters("{seconds: -9223372036854775808}")
  @TestParameters("{seconds: 9223372036854775807}")
  public void fromSecondsToTimestamp_outOfRangeThrows(long seconds) {
    assertThrows(
        IllegalArgumentException.class, () -> ProtoTimeUtils.fromSecondsToTimestamp(seconds));
  }

  @Test
  @TestParameters("{seconds: 0, nanos: 0, expected: '0s'}")
  @TestParameters("{seconds: 1, nanos: 100000000, expected: '1.100s'}")
  @TestParameters("{seconds: 1, nanos: 100000, expected: '1.000100s'}")
  @TestParameters("{seconds: 1, nanos: 100, expected: '1.000000100s'}")
  @TestParameters("{seconds: 0, nanos: -500000000, expected: '-0.500s'}")
  @TestParameters("{seconds: 315576000000, nanos: 999999999, expected: '315576000000.999999999s'}")
  @TestParameters(
      "{seconds: -315576000000, nanos: -999999999, expected: '-315576000000.999999999s'}")
  public void toString_duration(long seconds, int nanos, String expected) {
    Duration d = Duration.newBuilder().setSeconds(seconds).setNanos(nanos).build();

    assertThat(ProtoTimeUtils.toString(d)).isEqualTo(expected);
  }

  @Test
  @TestParameters("{seconds: -315576000001, nanos: 0}")
  @TestParameters("{seconds: 315576000001, nanos: 0}")
  @TestParameters("{seconds: 0, nanos: -1000000000}")
  @TestParameters("{seconds: 0, nanos: 1000000000}")
  @TestParameters("{seconds: 1, nanos: -1}")
  @TestParameters("{seconds: -1, nanos: 1}")
  public void toString_duration_invalidThrows(long seconds, int nanos) {
    Duration invalid = Duration.newBuilder().setSeconds(seconds).setNanos(nanos).build();

    assertThrows(IllegalArgumentException.class, () -> ProtoTimeUtils.toString(invalid));
  }

  @Test
  public void addAndSubtract_duration_atBounds() {
    Duration max =
        Duration.newBuilder().setSeconds(DURATION_SECONDS_MAX).setNanos(MAX_NANOS).build();
    Duration min =
        Duration.newBuilder().setSeconds(DURATION_SECONDS_MIN).setNanos(-MAX_NANOS).build();

    assertThat(ProtoTimeUtils.add(max, DURATION_ZERO)).isEqualTo(max);
    assertThat(ProtoTimeUtils.subtract(min, DURATION_ZERO)).isEqualTo(min);
  }

  @Test
  @TestParameters("{d1Seconds: 315576000000, d1Nanos: 999999999, d2Nanos: 1}")
  @TestParameters("{d1Seconds: -315576000000, d1Nanos: -999999999, d2Nanos: -1}")
  public void add_duration_outOfRangeThrows(long d1Seconds, int d1Nanos, int d2Nanos) {
    Duration d1 = Duration.newBuilder().setSeconds(d1Seconds).setNanos(d1Nanos).build();
    Duration d2 = Duration.newBuilder().setNanos(d2Nanos).build();

    assertThrows(IllegalArgumentException.class, () -> ProtoTimeUtils.add(d1, d2));
  }

  @Test
  @TestParameters("{d1Seconds: -315576000000, d1Nanos: -999999999, d2Nanos: 1}")
  @TestParameters("{d1Seconds: 315576000000, d1Nanos: 999999999, d2Nanos: -1}")
  public void subtract_duration_outOfRangeThrows(long d1Seconds, int d1Nanos, int d2Nanos) {
    Duration d1 = Duration.newBuilder().setSeconds(d1Seconds).setNanos(d1Nanos).build();
    Duration d2 = Duration.newBuilder().setNanos(d2Nanos).build();

    assertThrows(IllegalArgumentException.class, () -> ProtoTimeUtils.subtract(d1, d2));
  }

  @Test
  public void addAndSubtract_timestamp_atBounds() {
    Timestamp max =
        Timestamp.newBuilder().setSeconds(TIMESTAMP_SECONDS_MAX).setNanos(MAX_NANOS).build();
    Timestamp min = Timestamp.newBuilder().setSeconds(TIMESTAMP_SECONDS_MIN).build();

    assertThat(ProtoTimeUtils.add(max, DURATION_ZERO)).isEqualTo(max);
    assertThat(ProtoTimeUtils.subtract(min, DURATION_ZERO)).isEqualTo(min);
  }

  @Test
  @TestParameters("{tsSeconds: 253402300799, tsNanos: 999999999, durNanos: 1}")
  @TestParameters("{tsSeconds: -62135596800, tsNanos: 0, durNanos: -1}")
  public void add_timestamp_outOfRangeThrows(long tsSeconds, int tsNanos, int durNanos) {
    Timestamp ts = Timestamp.newBuilder().setSeconds(tsSeconds).setNanos(tsNanos).build();
    Duration dur = Duration.newBuilder().setNanos(durNanos).build();

    assertThrows(IllegalArgumentException.class, () -> ProtoTimeUtils.add(ts, dur));
  }

  @Test
  @TestParameters("{tsSeconds: -62135596800, tsNanos: 0, durNanos: 1}")
  @TestParameters("{tsSeconds: 253402300799, tsNanos: 999999999, durNanos: -1}")
  public void subtract_timestamp_outOfRangeThrows(long tsSeconds, int tsNanos, int durNanos) {
    Timestamp ts = Timestamp.newBuilder().setSeconds(tsSeconds).setNanos(tsNanos).build();
    Duration dur = Duration.newBuilder().setNanos(durNanos).build();

    assertThrows(IllegalArgumentException.class, () -> ProtoTimeUtils.subtract(ts, dur));
  }

  @Test
  public void between_fullTimestampRange() {
    Timestamp min = Timestamp.newBuilder().setSeconds(TIMESTAMP_SECONDS_MIN).build();
    Timestamp max =
        Timestamp.newBuilder().setSeconds(TIMESTAMP_SECONDS_MAX).setNanos(MAX_NANOS).build();

    assertThat(ProtoTimeUtils.between(min, max))
        .isEqualTo(
            Duration.newBuilder()
                .setSeconds(TIMESTAMP_SECONDS_MAX - TIMESTAMP_SECONDS_MIN)
                .setNanos(MAX_NANOS)
                .build());
    assertThat(ProtoTimeUtils.between(max, min))
        .isEqualTo(
            Duration.newBuilder()
                .setSeconds(TIMESTAMP_SECONDS_MIN - TIMESTAMP_SECONDS_MAX)
                .setNanos(-MAX_NANOS)
                .build());
  }

  @Test
  @TestParameters("{fromSeconds: 0, fromNanos: 1000000000, toSeconds: 253402300799}")
  @TestParameters("{fromSeconds: -62135596800, fromNanos: 0, toSeconds: 253402300800}")
  public void between_invalidTimestampThrows(long fromSeconds, int fromNanos, long toSeconds) {
    Timestamp from = Timestamp.newBuilder().setSeconds(fromSeconds).setNanos(fromNanos).build();
    Timestamp to = Timestamp.newBuilder().setSeconds(toSeconds).build();

    assertThrows(IllegalArgumentException.class, () -> ProtoTimeUtils.between(from, to));
  }
}
