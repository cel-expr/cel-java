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

import static dev.cel.common.internal.ProtoTimeUtils.DURATION_SECONDS_MAX;
import static dev.cel.common.internal.ProtoTimeUtils.DURATION_SECONDS_MIN;
import static dev.cel.common.internal.ProtoTimeUtils.MAX_NANOS;
import static dev.cel.common.internal.ProtoTimeUtils.NANOS_PER_SECOND;
import static dev.cel.common.internal.ProtoTimeUtils.TIMESTAMP_SECONDS_MAX;
import static dev.cel.common.internal.ProtoTimeUtils.TIMESTAMP_SECONDS_MIN;

import com.google.common.base.Strings;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.protobuf.Timestamp;
import dev.cel.common.annotations.Internal;
import dev.cel.common.exceptions.CelBadFormatException;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Locale;

/** Collection of utility methods for CEL datetime handlings. */
@Internal
@SuppressWarnings("JavaInstantGetSecondsGetNano") // Intended within CEL.
public final class DateTimeHelpers {
  public static final String UTC = "UTC";

  private static final Duration DURATION_MIN = Duration.ofSeconds(DURATION_SECONDS_MIN, -MAX_NANOS);
  private static final Duration DURATION_MAX = Duration.ofSeconds(DURATION_SECONDS_MAX, MAX_NANOS);

  /**
   * Constructs a new {@link LocalDateTime} instance
   *
   * @param ts Timestamp protobuf object
   * @param tz Timezone based on the CEL specification. This is either the canonical name from tz
   *     database or a standard offset represented in (+/-)HH:MM. Few valid examples are:
   *     <ul>
   *       <li>UTC
   *       <li>America/Los_Angeles
   *       <li>-09:30 or -9:30 (Leading zeroes can be omitted though not allowed by spec)
   *     </ul>
   *
   * @return If an Invalid timezone is supplied.
   */
  public static LocalDateTime newLocalDateTime(Timestamp ts, String tz) {
    return Instant.ofEpochSecond(ts.getSeconds(), ts.getNanos())
        .atZone(timeZone(tz))
        .toLocalDateTime();
  }

  /**
   * Constructs a new {@link LocalDateTime} instance from a Java Instant.
   *
   * @param instant Instant object
   * @param tz Timezone based on the CEL specification. This is either the canonical name from tz
   *     database or a standard offset represented in (+/-)HH:MM. Few valid examples are:
   *     <ul>
   *       <li>UTC
   *       <li>America/Los_Angeles
   *       <li>-09:30 or -9:30 (Leading zeroes can be omitted though not allowed by spec)
   *     </ul>
   *
   * @return A new {@link LocalDateTime} instance.
   */
  public static LocalDateTime newLocalDateTime(Instant instant, String tz) {
    return instant.atZone(timeZone(tz)).toLocalDateTime();
  }

  /** Creates an {@link Instant} from epoch seconds, validating CEL timestamp bounds. */
  // CEL's timestamp(int) conversion accepts epoch seconds as an int64.
  @SuppressWarnings("GoodTime-ApiWithNumericTimeUnit")
  public static Instant fromSecondsToInstant(long seconds) {
    checkSecondsValid(seconds);
    return Instant.ofEpochSecond(seconds);
  }

  /**
   * Parse from RFC 3339 date string to {@link java.time.Instant}.
   *
   * <p>Example of accepted format: "1972-01-01T10:00:20.021-05:00"
   */
  public static Instant parse(String text) {
    OffsetDateTime offsetDateTime = OffsetDateTime.parse(text);
    Instant instant = offsetDateTime.toInstant();
    checkValid(instant);

    return instant;
  }

  /** Parses a CEL duration string (following Go {@code time.ParseDuration} syntax). */
  public static Duration parseDuration(String text) {
    if (text.isEmpty()) {
      throw invalidDurationFormat();
    }
    int len = text.length();
    int i = 0;
    char first = text.charAt(0);
    boolean negative = (first == '-');
    if (negative || first == '+') {
      i++;
    }
    if (i == len) {
      throw invalidDurationFormat();
    }
    if (len - i == 1 && text.charAt(i) == '0') {
      return Duration.ZERO;
    }
    long totalSeconds = 0;
    long totalNanos = 0;
    while (i < len) {
      int intStart = i;
      int intEnd = scanAsciiDigits(text, intStart);
      int fracStart = intEnd;
      int fracEnd = intEnd;
      if (intEnd < len && text.charAt(intEnd) == '.') {
        fracStart = intEnd + 1;
        fracEnd = scanAsciiDigits(text, fracStart);
      }
      if (intStart == intEnd && fracStart == fracEnd) {
        throw invalidDurationFormat();
      }
      i = fracEnd;
      int unitStart = i;
      while (i < len && text.charAt(i) != '.' && !isAsciiDigit(text.charAt(i))) {
        i++;
      }
      long unitNanos = parseUnitNanos(text, unitStart, i);

      long segSeconds = 0;
      long segNanos = 0;
      for (int p = intStart; p < intEnd; p++) {
        int digit = Character.digit(text.charAt(p), 10);
        segSeconds *= 10;
        segNanos = segNanos * 10 + digit * unitNanos;
        segSeconds += segNanos / NANOS_PER_SECOND;
        segNanos %= NANOS_PER_SECOND;
        if (segSeconds > DURATION_SECONDS_MAX) {
          throw outOfRangeDuration(text);
        }
      }

      long fracNanos = 0;
      for (int p = fracEnd - 1; p >= fracStart; p--) {
        fracNanos = (Character.digit(text.charAt(p), 10) * unitNanos + fracNanos) / 10;
      }

      totalNanos += segNanos + fracNanos;
      totalSeconds += segSeconds + totalNanos / NANOS_PER_SECOND;
      totalNanos %= NANOS_PER_SECOND;
      if (totalSeconds > DURATION_SECONDS_MAX) {
        throw outOfRangeDuration(text);
      }
    }
    return negative
        ? Duration.ofSeconds(-totalSeconds, -totalNanos)
        : Duration.ofSeconds(totalSeconds, totalNanos);
  }

  /** Adds a duration to an instant. */
  public static Instant add(Instant ts, Duration dur) {
    Instant newInstant = ts.plus(dur);
    checkValid(newInstant);

    return newInstant;
  }

  /** Adds two durations */
  public static Duration add(Duration d1, Duration d2) {
    return checkValid(d1.plus(d2));
  }

  /** Subtracts a duration to an instant. */
  public static Instant subtract(Instant ts, Duration dur) {
    Instant newInstant = ts.minus(dur);
    checkValid(newInstant);

    return newInstant;
  }

  /** Subtract a duration from another. */
  public static Duration subtract(Duration d1, Duration d2) {
    return checkValid(d1.minus(d2));
  }

  /** Calculates the difference between two instants. */
  public static Duration between(Instant d1, Instant d2) {
    return checkValid(Duration.between(d1, d2));
  }

  /** Formats a {@link Duration} into a minimal seconds-based representation. */
  public static String toString(Duration duration) {
    long seconds = duration.getSeconds();
    int nanos = duration.getNano();
    if (seconds < 0 && nanos > 0) {
      seconds++;
      nanos -= NANOS_PER_SECOND;
    }
    return ProtoTimeUtils.formatDuration(seconds, nanos);
  }

  private static int scanAsciiDigits(String text, int i) {
    while (i < text.length() && isAsciiDigit(text.charAt(i))) {
      i++;
    }
    return i;
  }

  private static boolean isAsciiDigit(char c) {
    return c >= '0' && c <= '9';
  }

  private static long parseUnitNanos(String text, int start, int end) {
    int len = end - start;
    if (len == 1) {
      switch (text.charAt(start)) {
        case 's':
          return NANOS_PER_SECOND;
        case 'm':
          return 60L * NANOS_PER_SECOND;
        case 'h':
          return 3600L * NANOS_PER_SECOND;
        default:
          break;
      }
    } else if (len == 2 && text.charAt(start + 1) == 's') {
      switch (text.charAt(start)) {
        case 'n':
          return 1L;
        case 'u':
        case '\u00b5':
        case '\u03bc':
          return 1_000L;
        case 'm':
          return 1_000_000L;
        default:
          break;
      }
    }
    throw invalidDurationFormat();
  }

  private static IllegalArgumentException invalidDurationFormat() {
    return new IllegalArgumentException("invalid duration format");
  }

  private static IllegalArgumentException outOfRangeDuration(String text) {
    return new IllegalArgumentException("Duration is out of valid range: " + text);
  }

  /**
   * Get the DateTimeZone Instance.
   *
   * @param tz the ID of the datetime zone
   * @return the ZoneId object
   */
  private static ZoneId timeZone(String tz) {
    try {
      return ZoneId.of(tz);
    } catch (DateTimeException e) {
      // If timezone is not a string name (for example, 'US/Central'), it should be a numerical
      // offset from UTC in the format [+/-]HH:MM.
      try {
        int ind = tz.indexOf(":");
        if (ind == -1) {
          throw new CelBadFormatException(e);
        }

        int hourOffset = Integer.parseInt(tz.substring(0, ind));
        int minOffset = Integer.parseInt(tz.substring(ind + 1));
        // Ensures that the offset are properly formatted in [+/-]HH:MM to conform with
        // ZoneOffset's format requirements.
        // Example: "-9:30" -> "-09:30" and "9:30" -> "+09:30"
        String formattedOffset =
            ((hourOffset < 0) ? "-" : "+")
                + String.format(Locale.US, "%02d:%02d", Math.abs(hourOffset), minOffset);

        return ZoneId.of(formattedOffset);

      } catch (DateTimeException e2) {
        throw new CelBadFormatException(e2);
      }
    }
  }

  /** Throws an {@link IllegalArgumentException} if the given {@link Timestamp} is not valid. */
  public static void checkValid(Instant instant) {
    checkSecondsValid(instant.getEpochSecond());
  }

  @CanIgnoreReturnValue
  private static Duration checkValid(Duration duration) {
    if (duration.compareTo(DURATION_MIN) < 0 || duration.compareTo(DURATION_MAX) > 0) {
      throw new IllegalArgumentException(
          Strings.lenientFormat(
              "Duration is out of valid range [-315,576,000,000.999999999s,"
                  + " +315,576,000,000.999999999s]: %s",
              duration));
    }
    return duration;
  }

  private static void checkSecondsValid(long seconds) {
    if (seconds < TIMESTAMP_SECONDS_MIN || seconds > TIMESTAMP_SECONDS_MAX) {
      throw new IllegalArgumentException(
          Strings.lenientFormat(
              "Timestamp is not valid. Seconds (%s) must be in range [-62,135,596,800,"
                  + " +253,402,300,799].",
              seconds));
    }
  }

  private DateTimeHelpers() {}
}
