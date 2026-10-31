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

package dev.cel.common.internal;

import static com.google.common.truth.Truth.assertThat;
import static dev.cel.common.internal.ProtoTimeUtils.MAX_NANOS;
import static dev.cel.common.internal.ProtoTimeUtils.TIMESTAMP_SECONDS_MAX;
import static dev.cel.common.internal.ProtoTimeUtils.TIMESTAMP_SECONDS_MIN;
import static org.junit.Assert.assertThrows;

import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import com.google.testing.junit.testparameterinjector.TestParameters;
import java.time.Duration;
import java.time.Instant;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(TestParameterInjector.class)
// Parameterized tests construct Duration/Instant boundaries from raw seconds and nano adjustments.
@SuppressWarnings("GoodTime-ApiWithNumericTimeUnit")
public final class DateTimeHelpersTest {

  @Test
  @TestParameters("{text: '0', expectedSeconds: 0, expectedNanoAdjustment: 0}")
  @TestParameters("{text: '+0', expectedSeconds: 0, expectedNanoAdjustment: 0}")
  @TestParameters("{text: '-0', expectedSeconds: 0, expectedNanoAdjustment: 0}")
  @TestParameters("{text: '0s', expectedSeconds: 0, expectedNanoAdjustment: 0}")
  @TestParameters("{text: '+1s', expectedSeconds: 1, expectedNanoAdjustment: 0}")
  @TestParameters("{text: '-1s', expectedSeconds: -1, expectedNanoAdjustment: 0}")
  @TestParameters("{text: '1ns', expectedSeconds: 0, expectedNanoAdjustment: 1}")
  @TestParameters("{text: '1us', expectedSeconds: 0, expectedNanoAdjustment: 1000}")
  @TestParameters("{text: '1\u00b5s', expectedSeconds: 0, expectedNanoAdjustment: 1000}")
  @TestParameters("{text: '1\u03bcs', expectedSeconds: 0, expectedNanoAdjustment: 1000}")
  @TestParameters("{text: '1ms', expectedSeconds: 0, expectedNanoAdjustment: 1000000}")
  @TestParameters("{text: '1m', expectedSeconds: 60, expectedNanoAdjustment: 0}")
  @TestParameters("{text: '1h', expectedSeconds: 3600, expectedNanoAdjustment: 0}")
  @TestParameters("{text: '1m2s', expectedSeconds: 62, expectedNanoAdjustment: 0}")
  @TestParameters("{text: '1m.5s', expectedSeconds: 60, expectedNanoAdjustment: 500000000}")
  @TestParameters("{text: '.5s', expectedSeconds: 0, expectedNanoAdjustment: 500000000}")
  @TestParameters("{text: '1.s', expectedSeconds: 1, expectedNanoAdjustment: 0}")
  @TestParameters("{text: '0.1234567899s', expectedSeconds: 0, expectedNanoAdjustment: 123456789}")
  @TestParameters(
      "{text: '-0.1234567899s', expectedSeconds: 0, expectedNanoAdjustment: -123456789}")
  @TestParameters(
      "{text: '0.0900000000000000000s', expectedSeconds: 0, expectedNanoAdjustment: 90000000}")
  @TestParameters(
      "{text: '0.999999999999999999h', expectedSeconds: 3599, expectedNanoAdjustment: 999999999}")
  @TestParameters(
      "{text: '-9223372036854775808ns',"
          + " expectedSeconds: -9223372036, expectedNanoAdjustment: -854775808}")
  @TestParameters(
      "{text: '315576000000s', expectedSeconds: 315576000000, expectedNanoAdjustment: 0}")
  @TestParameters(
      "{text: '315576000000999999999ns',"
          + " expectedSeconds: 315576000000, expectedNanoAdjustment: 999999999}")
  @TestParameters(
      "{text: '-315576000000999999999ns',"
          + " expectedSeconds: -315576000000, expectedNanoAdjustment: -999999999}")
  @TestParameters(
      "{text: '315576000000.999999999s',"
          + " expectedSeconds: 315576000000, expectedNanoAdjustment: 999999999}")
  @TestParameters(
      "{text: '-315576000000.999999999s',"
          + " expectedSeconds: -315576000000, expectedNanoAdjustment: -999999999}")
  public void parseDuration_valid(String text, long expectedSeconds, long expectedNanoAdjustment) {
    assertThat(DateTimeHelpers.parseDuration(text))
        .isEqualTo(Duration.ofSeconds(expectedSeconds, expectedNanoAdjustment));
  }

  @Test
  @TestParameters("{text: ''}")
  @TestParameters("{text: '+'}")
  @TestParameters("{text: '-'}")
  @TestParameters("{text: '1'}")
  @TestParameters("{text: '1.'}")
  @TestParameters("{text: '1.5'}")
  @TestParameters("{text: '.'}")
  @TestParameters("{text: '00'}")
  @TestParameters("{text: '0x'}")
  @TestParameters("{text: '.s'}")
  @TestParameters("{text: 's'}")
  @TestParameters("{text: '/s'}")
  @TestParameters("{text: ':s'}")
  @TestParameters("{text: '-1-1s'}")
  @TestParameters("{text: 'bad'}")
  @TestParameters("{text: 'inf'}")
  @TestParameters("{text: '1d'}")
  @TestParameters("{text: '1ma'}")
  @TestParameters("{text: '1xs'}")
  @TestParameters("{text: '999999999999999999999x'}")
  public void parseDuration_invalidFormatThrows(String text) {
    IllegalArgumentException e =
        assertThrows(IllegalArgumentException.class, () -> DateTimeHelpers.parseDuration(text));

    assertThat(e).hasMessageThat().isEqualTo("invalid duration format");
  }

  @Test
  @TestParameters("{text: '315576000001s'}")
  @TestParameters("{text: '-315576000001s'}")
  @TestParameters("{text: '315576000000s1000000000ns'}")
  @TestParameters("{text: '315576000000.5s0.5s'}")
  @TestParameters("{text: '315576000000.999999999s1ns'}")
  @TestParameters("{text: '-315576000000.999999999s1ns'}")
  @TestParameters("{text: '18446744073709551616s'}")
  public void parseDuration_outOfRangeThrows(String text) {
    IllegalArgumentException e =
        assertThrows(IllegalArgumentException.class, () -> DateTimeHelpers.parseDuration(text));

    assertThat(e).hasMessageThat().contains("range");
  }

  @Test
  @TestParameters("{seconds: -62135596800}")
  @TestParameters("{seconds: 0}")
  @TestParameters("{seconds: 253402300799}")
  public void fromSecondsToInstant_valid(long seconds) {
    assertThat(DateTimeHelpers.fromSecondsToInstant(seconds))
        .isEqualTo(Instant.ofEpochSecond(seconds));
  }

  @Test
  @TestParameters("{seconds: -62135596801}")
  @TestParameters("{seconds: 253402300800}")
  @TestParameters("{seconds: -9223372036854775808}")
  @TestParameters("{seconds: 9223372036854775807}")
  public void fromSecondsToInstant_outOfRangeThrows(long seconds) {
    assertThrows(
        IllegalArgumentException.class, () -> DateTimeHelpers.fromSecondsToInstant(seconds));
  }

  @Test
  public void between_fullTimestampRange() {
    Instant minTs = Instant.ofEpochSecond(TIMESTAMP_SECONDS_MIN);
    Instant maxTs = Instant.ofEpochSecond(TIMESTAMP_SECONDS_MAX, MAX_NANOS);

    assertThat(DateTimeHelpers.between(minTs, maxTs))
        .isEqualTo(Duration.ofSeconds(TIMESTAMP_SECONDS_MAX - TIMESTAMP_SECONDS_MIN, MAX_NANOS));
    assertThat(DateTimeHelpers.between(maxTs, minTs))
        .isEqualTo(Duration.ofSeconds(TIMESTAMP_SECONDS_MIN - TIMESTAMP_SECONDS_MAX, -MAX_NANOS));
  }

  @Test
  @TestParameters("{endSeconds: 315576000001}")
  @TestParameters("{endSeconds: -315576000001}")
  public void between_outOfRangeThrows(long endSeconds) {
    Instant end = Instant.ofEpochSecond(endSeconds);

    assertThrows(IllegalArgumentException.class, () -> DateTimeHelpers.between(Instant.EPOCH, end));
  }

  @Test
  @TestParameters("{seconds: 0, nanoAdjustment: 0, expected: '0s'}")
  @TestParameters("{seconds: 0, nanoAdjustment: 500, expected: '0.000000500s'}")
  @TestParameters("{seconds: 0, nanoAdjustment: -500, expected: '-0.000000500s'}")
  @TestParameters("{seconds: -1, nanoAdjustment: 0, expected: '-1s'}")
  @TestParameters("{seconds: -1, nanoAdjustment: -500, expected: '-1.000000500s'}")
  @TestParameters(
      "{seconds: 315576000000, nanoAdjustment: 999999999, expected: '315576000000.999999999s'}")
  @TestParameters(
      "{seconds: -315576000000, nanoAdjustment: -999999999, expected: '-315576000000.999999999s'}")
  public void toString_duration(long seconds, long nanoAdjustment, String expected) {
    assertThat(DateTimeHelpers.toString(Duration.ofSeconds(seconds, nanoAdjustment)))
        .isEqualTo(expected);
  }

  @Test
  @TestParameters("{seconds: 315576000000, nanoAdjustment: 1000000000}")
  @TestParameters("{seconds: -315576000000, nanoAdjustment: -1000000000}")
  @TestParameters("{seconds: -9223372036854775808, nanoAdjustment: 0}")
  public void toString_duration_outOfRangeThrows(long seconds, long nanoAdjustment) {
    Duration duration = Duration.ofSeconds(seconds, nanoAdjustment);

    assertThrows(IllegalArgumentException.class, () -> DateTimeHelpers.toString(duration));
  }
}
