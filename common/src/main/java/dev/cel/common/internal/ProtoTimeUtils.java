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

import static com.google.common.math.LongMath.checkedAdd;
import static com.google.common.math.LongMath.checkedMultiply;
import static com.google.common.math.LongMath.checkedSubtract;

import com.google.common.base.Strings;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.protobuf.Duration;
import com.google.protobuf.Timestamp;
import dev.cel.common.annotations.Internal;
import java.io.Serializable;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.util.Comparator;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Utility methods for handling {@code protobuf/duration.proto} and {@code
 * protobuf/timestamp.proto}.
 *
 * <p>Forked from com.google.protobuf.util package. These exist because there's not an equivalent
 * util JAR published in maven central that's compatible with protolite. <a
 * href="https://github.com/protocolbuffers/protobuf/issues/21488">See relevant github issue.</a>
 *
 * <p>CEL Library Internals. Do Not Use.
 */
@Internal
// Forked from protobuf-java-utils. Retaining units/date API for parity.
@SuppressWarnings({"GoodTime-ApiWithNumericTimeUnit", "JavaUtilDate"})
public final class ProtoTimeUtils {

  // Timestamp for "0001-01-01T00:00:00Z"
  public static final long TIMESTAMP_SECONDS_MIN = -62135596800L;
  // Timestamp for "9999-12-31T23:59:59Z"
  public static final long TIMESTAMP_SECONDS_MAX = 253402300799L;
  public static final long DURATION_SECONDS_MIN = -315576000000L;
  public static final long DURATION_SECONDS_MAX = 315576000000L;

  private static final int MILLIS_PER_SECOND = 1000;

  private static final int NANOS_PER_SECOND = 1000000000;
  private static final int NANOS_PER_MILLISECOND = 1000000;
  private static final int NANOS_PER_MICROSECOND = 1000;

  private static final long SECONDS_PER_MINUTE = 60L;
  private static final long SECONDS_PER_HOUR = SECONDS_PER_MINUTE * 60;

  private static final ThreadLocal<SimpleDateFormat> TIMESTAMP_FORMAT =
      new ThreadLocal<SimpleDateFormat>() {
        @Override
        protected SimpleDateFormat initialValue() {
          return createTimestampFormat();
        }
      };

  private enum TimestampComparator implements Comparator<Timestamp>, Serializable {
    INSTANCE;

    @Override
    public int compare(Timestamp t1, Timestamp t2) {
      checkValid(t1);
      checkValid(t2);
      int secDiff = Long.compare(t1.getSeconds(), t2.getSeconds());
      return (secDiff != 0) ? secDiff : Integer.compare(t1.getNanos(), t2.getNanos());
    }
  }

  private enum DurationComparator implements Comparator<Duration>, Serializable {
    INSTANCE;

    @Override
    public int compare(Duration d1, Duration d2) {
      checkValid(d1);
      checkValid(d2);
      int secDiff = Long.compare(d1.getSeconds(), d2.getSeconds());
      return (secDiff != 0) ? secDiff : Integer.compare(d1.getNanos(), d2.getNanos());
    }
  }

  /**
   * A constant holding the {@link Timestamp} of epoch time, {@code 1970-01-01T00:00:00.000000000Z}.
   */
  public static final Timestamp TIMESTAMP_EPOCH =
      Timestamp.newBuilder().setSeconds(0).setNanos(0).build();

  /** A constant holding the duration of zero. */
  public static final Duration DURATION_ZERO =
      Duration.newBuilder().setSeconds(0L).setNanos(0).build();

  private static SimpleDateFormat createTimestampFormat() {
    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ENGLISH);
    GregorianCalendar calendar = new GregorianCalendar(TimeZone.getTimeZone("UTC"));
    // We use Proleptic Gregorian Calendar (i.e., Gregorian calendar extends
    // backwards to year one) for timestamp formatting.
    calendar.setGregorianChange(new Date(Long.MIN_VALUE));
    sdf.setCalendar(calendar);
    return sdf;
  }

  /** Convert a {@link Instant} object to proto-based {@link Timestamp}. */
  public static Timestamp toProtoTimestamp(Instant instant) {
    return normalizedTimestamp(instant.getEpochSecond(), instant.getNano());
  }

  /** Convert a {@link java.time.Duration} object to proto-based {@link Duration}. */
  public static Duration toProtoDuration(java.time.Duration duration) {
    return normalizedDuration(duration.getSeconds(), duration.getNano());
  }

  /** Convert a {@link Timestamp} object to java-based {@link Instant}. */
  public static Instant toJavaInstant(Timestamp timestamp) {
    timestamp = normalizedTimestamp(timestamp.getSeconds(), timestamp.getNanos());
    return Instant.ofEpochSecond(timestamp.getSeconds(), timestamp.getNanos());
  }

  /** Convert a {@link Duration} object to java-based {@link java.time.Duration}. */
  public static java.time.Duration toJavaDuration(Duration duration) {
    duration = normalizedDuration(duration.getSeconds(), duration.getNanos());
    return java.time.Duration.ofSeconds(duration.getSeconds(), duration.getNanos());
  }

  /** Convert a Timestamp to the number of seconds elapsed from the epoch. */
  public static long toSeconds(Timestamp timestamp) {
    return checkValid(timestamp).getSeconds();
  }

  /**
   * Convert a Duration to the number of seconds. The result will be rounded towards 0 to the
   * nearest second. E.g., if the duration represents -1 nanosecond, it will be rounded to 0.
   */
  public static long toSeconds(Duration duration) {
    return checkValid(duration).getSeconds();
  }

  /**
   * Convert a Duration to the number of hours. The result will be rounded towards 0 to the nearest
   * hour.
   */
  public static long toHours(Duration duration) {
    return checkValid(duration).getSeconds() / SECONDS_PER_HOUR;
  }

  /**
   * Convert a Duration to the number of minutes. The result will be rounded towards 0 to the
   * nearest minute.
   */
  public static long toMinutes(Duration duration) {
    return checkValid(duration).getSeconds() / SECONDS_PER_MINUTE;
  }

  /**
   * Convert a Duration to the number of milliseconds. The result will be rounded towards 0 to the
   * nearest millisecond. E.g., if the duration represents -1 nanosecond, it will be rounded to 0.
   */
  public static long toMillis(Duration duration) {
    checkValid(duration);
    return checkedAdd(
        checkedMultiply(duration.getSeconds(), MILLIS_PER_SECOND),
        duration.getNanos() / NANOS_PER_MILLISECOND);
  }

  /** Create a Timestamp from the number of seconds elapsed from the epoch. */
  public static Timestamp fromSecondsToTimestamp(long seconds) {
    return normalizedTimestamp(seconds, 0);
  }

  /** Create a Timestamp from the number of seconds elapsed from the epoch. */
  public static Duration fromSecondsToDuration(long seconds) {
    return normalizedDuration(seconds, 0);
  }

  /** Create a Timestamp from the number of seconds elapsed from the epoch. */
  public static Duration fromMillisToDuration(long milliseconds) {
    return normalizedDuration(
        milliseconds / MILLIS_PER_SECOND,
        (int) (milliseconds % MILLIS_PER_SECOND * NANOS_PER_MILLISECOND));
  }

  /** Throws an {@link IllegalArgumentException} if the given {@link Duration} is not valid. */
  @CanIgnoreReturnValue
  private static Duration checkValid(Duration duration) {
    long seconds = duration.getSeconds();
    int nanos = duration.getNanos();
    if (!isDurationValid(seconds, nanos)) {
      throw new IllegalArgumentException(
          Strings.lenientFormat(
              "Duration is not valid. See proto definition for valid values. "
                  + "Seconds (%s) must be in range [-315,576,000,000, +315,576,000,000]. "
                  + "Nanos (%s) must be in range [-999,999,999, +999,999,999]. "
                  + "Nanos must have the same sign as seconds",
              seconds, nanos));
    }
    return duration;
  }

  /** Throws an {@link IllegalArgumentException} if the given {@link Timestamp} is not valid. */
  @CanIgnoreReturnValue
  private static Timestamp checkValid(Timestamp timestamp) {
    long seconds = timestamp.getSeconds();
    int nanos = timestamp.getNanos();
    if (!isTimestampValid(seconds, nanos)) {
      throw new IllegalArgumentException(
          Strings.lenientFormat(
              "Timestamp is not valid. See proto definition for valid values. "
                  + "Seconds (%s) must be in range [-62,135,596,800, +253,402,300,799]. "
                  + "Nanos (%s) must be in range [0, +999,999,999].",
              seconds, nanos));
    }
    return timestamp;
  }

  /**
   * Convert Timestamp to RFC 3339 date string format. The output will always be Z-normalized and
   * uses 0, 3, 6 or 9 fractional digits as required to represent the exact value. Note that
   * Timestamp can only represent time from 0001-01-01T00:00:00Z to 9999-12-31T23:59:59.999999999Z.
   * See https://www.ietf.org/rfc/rfc3339.txt
   *
   * <p>Example of generated format: "1972-01-01T10:00:20.021Z"
   *
   * @return The string representation of the given timestamp.
   * @throws IllegalArgumentException if the given timestamp is not in the valid range.
   */
  public static String toString(Timestamp timestamp) {
    checkValid(timestamp);

    long seconds = timestamp.getSeconds();
    int nanos = timestamp.getNanos();

    StringBuilder result = new StringBuilder();
    // Format the seconds part.
    Date date = new Date(seconds * MILLIS_PER_SECOND);
    result.append(TIMESTAMP_FORMAT.get().format(date));
    // Format the nanos part.
    if (nanos != 0) {
      result.append(".");
      result.append(formatNanos(nanos));
    }
    result.append("Z");
    return result.toString();
  }

  /**
   * Convert Duration to string format. The string format will contains 3, 6, or 9 fractional digits
   * depending on the precision required to represent the exact Duration value. For example: "1s",
   * "1.010s", "1.000000100s", "-3.100s" The range that can be represented by Duration is from
   * -315,576,000,000 to +315,576,000,000 inclusive (in seconds).
   *
   * @return The string representation of the given duration.
   * @throws IllegalArgumentException if the given duration is not in the valid range.
   */
  public static String toString(Duration duration) {
    checkValid(duration);

    long seconds = duration.getSeconds();
    int nanos = duration.getNanos();

    StringBuilder result = new StringBuilder();
    if (seconds < 0 || nanos < 0) {
      result.append("-");
      seconds = -seconds;
      nanos = -nanos;
    }
    result.append(seconds);
    if (nanos != 0) {
      result.append(".");
      result.append(formatNanos(nanos));
    }
    result.append("s");
    return result.toString();
  }

  /**
   * Parse from RFC 3339 date string to Timestamp. This method accepts all outputs of {@link
   * #toString(Timestamp)} and it also accepts any fractional digits (or none) and any offset as
   * long as they fit into nano-seconds precision.
   *
   * <p>Example of accepted format: "1972-01-01T10:00:20.021-05:00"
   *
   * @return a Timestamp parsed from the string
   * @throws ParseException if parsing fails
   */
  public static Timestamp parse(String value) throws ParseException {
    int dayOffset = value.indexOf('T');
    if (dayOffset == -1) {
      throw new ParseException("Failed to parse timestamp: invalid timestamp \"" + value + "\"", 0);
    }
    int timezoneOffsetPosition = value.indexOf('Z', dayOffset);
    if (timezoneOffsetPosition == -1) {
      timezoneOffsetPosition = value.indexOf('+', dayOffset);
    }
    if (timezoneOffsetPosition == -1) {
      timezoneOffsetPosition = value.indexOf('-', dayOffset);
    }
    if (timezoneOffsetPosition == -1) {
      throw new ParseException("Failed to parse timestamp: missing valid timezone offset.", 0);
    }
    // Parse seconds and nanos.
    String timeValue = value.substring(0, timezoneOffsetPosition);
    String secondValue = timeValue;
    String nanoValue = "";
    int pointPosition = timeValue.indexOf('.');
    if (pointPosition != -1) {
      secondValue = timeValue.substring(0, pointPosition);
      nanoValue = timeValue.substring(pointPosition + 1);
    }
    Date date = TIMESTAMP_FORMAT.get().parse(secondValue);
    long seconds = date.getTime() / MILLIS_PER_SECOND;
    int nanos = nanoValue.isEmpty() ? 0 : parseNanos(nanoValue);
    // Parse timezone offsets.
    if (value.charAt(timezoneOffsetPosition) == 'Z') {
      if (value.length() != timezoneOffsetPosition + 1) {
        throw new ParseException(
            "Failed to parse timestamp: invalid trailing data \""
                + value.substring(timezoneOffsetPosition)
                + "\"",
            0);
      }
    } else {
      String offsetValue = value.substring(timezoneOffsetPosition + 1);
      long offset = parseTimezoneOffset(offsetValue);
      if (value.charAt(timezoneOffsetPosition) == '+') {
        seconds -= offset;
      } else {
        seconds += offset;
      }
    }
    try {
      Timestamp timestamp = normalizedTimestamp(seconds, nanos);
      return checkValid(timestamp);
    } catch (IllegalArgumentException e) {
      ParseException ex =
          new ParseException(
              "Failed to parse timestamp " + value + " Timestamp is out of range.", 0);
      ex.initCause(e);
      throw ex;
    }
  }

  /** Adds two durations */
  public static Duration add(Duration d1, Duration d2) {
    java.time.Duration javaDuration1 = ProtoTimeUtils.toJavaDuration(checkValid(d1));
    java.time.Duration javaDuration2 = ProtoTimeUtils.toJavaDuration(checkValid(d2));

    java.time.Duration sum = javaDuration1.plus(javaDuration2);

    return ProtoTimeUtils.toProtoDuration(sum);
  }

  /** Adds two timestamps. */
  public static Timestamp add(Timestamp ts, Duration dur) {
    Instant javaInstant = ProtoTimeUtils.toJavaInstant(checkValid(ts));
    java.time.Duration javaDuration = ProtoTimeUtils.toJavaDuration(checkValid(dur));

    Instant newInstant = javaInstant.plus(javaDuration);

    return ProtoTimeUtils.toProtoTimestamp(newInstant);
  }

  /** Subtract a duration from another. */
  public static Duration subtract(Duration d1, Duration d2) {
    java.time.Duration javaDuration1 = ProtoTimeUtils.toJavaDuration(checkValid(d1));
    java.time.Duration javaDuration2 = ProtoTimeUtils.toJavaDuration(checkValid(d2));

    java.time.Duration sum = javaDuration1.minus(javaDuration2);

    return ProtoTimeUtils.toProtoDuration(sum);
  }

  /** Subtracts two timestamps */
  public static Timestamp subtract(Timestamp ts, Duration dur) {
    Instant javaInstant = ProtoTimeUtils.toJavaInstant(checkValid(ts));
    java.time.Duration javaDuration = ProtoTimeUtils.toJavaDuration(checkValid(dur));

    Instant newInstant = javaInstant.minus(javaDuration);

    return ProtoTimeUtils.toProtoTimestamp(newInstant);
  }

  /** Calculate the difference between two timestamps. */
  public static Duration between(Timestamp from, Timestamp to) {
    return between(from, to, /* validateOverflow= */ true);
  }

  /** Calculate the difference between two timestamps. */
  public static Duration between(Timestamp from, Timestamp to, boolean validateOverflow) {
    Instant javaFrom = ProtoTimeUtils.toJavaInstant(checkValid(from));
    Instant javaTo = ProtoTimeUtils.toJavaInstant(checkValid(to));

    java.time.Duration between = java.time.Duration.between(javaFrom, javaTo);
    if (validateOverflow) {
      // Call toNanos() to validate 64-bit nanosecond overflow (throws ArithmeticException).
      // Suppress unused variable warning as the duration object itself is returned.
      @SuppressWarnings("unused")
      long unused = between.toNanos();
    }

    return ProtoTimeUtils.toProtoDuration(between);
  }

  /**
   * Compares two durations. The value returned is identical to what would be returned by: {@code
   * Durations.comparator().compare(x, y)}.
   *
   * @return the value {@code 0} if {@code x == y}; a value less than {@code 0} if {@code x < y};
   *     and a value greater than {@code 0} if {@code x > y}
   */
  public static int compare(Duration x, Duration y) {
    return DurationComparator.INSTANCE.compare(x, y);
  }

  /**
   * Compares two timestamps. The value returned is identical to what would be returned by: {@code
   * Timestamps.comparator().compare(x, y)}.
   *
   * @return the value {@code 0} if {@code x == y}; a value less than {@code 0} if {@code x < y};
   *     and a value greater than {@code 0} if {@code x > y}
   */
  public static int compare(Timestamp x, Timestamp y) {
    return TimestampComparator.INSTANCE.compare(x, y);
  }

  /**
   * Create a {@link Timestamp} using the best-available (in terms of precision) system clock.
   *
   * <p><b>Note:</b> that while this API is convenient, it may harm the testability of your code, as
   * you're unable to mock the current time. Instead, you may want to consider injecting a clock
   * instance to read the current time.
   */
  public static Timestamp now() {
    Instant nowInstant = Instant.now();

    return Timestamp.newBuilder()
        .setSeconds(nowInstant.getEpochSecond())
        .setNanos(nowInstant.getNano())
        .build();
  }

  private static long parseTimezoneOffset(String value) throws ParseException {
    int pos = value.indexOf(':');
    if (pos == -1) {
      throw new ParseException("Invalid offset value: " + value, 0);
    }
    String hours = value.substring(0, pos);
    String minutes = value.substring(pos + 1);
    try {
      return (Long.parseLong(hours) * 60 + Long.parseLong(minutes)) * 60;
    } catch (NumberFormatException e) {
      ParseException ex = new ParseException("Invalid offset value: " + value, 0);
      ex.initCause(e);
      throw ex;
    }
  }

  private static int parseNanos(String value) throws ParseException {
    int result = 0;
    for (int i = 0; i < 9; ++i) {
      result = result * 10;
      if (i < value.length()) {
        if (value.charAt(i) < '0' || value.charAt(i) > '9') {
          throw new ParseException("Invalid nanoseconds.", 0);
        }
        result += value.charAt(i) - '0';
      }
    }
    return result;
  }

  /**
   * Returns true if the given number of seconds and nanos is a valid {@link Duration}. The {@code
   * seconds} value must be in the range [-315,576,000,000, +315,576,000,000]. The {@code nanos}
   * value must be in the range [-999,999,999, +999,999,999].
   *
   * <p><b>Note:</b> Durations less than one second are represented with a 0 {@code seconds} field
   * and a positive or negative {@code nanos} field. For durations of one second or more, a non-zero
   * value for the {@code nanos} field must be of the same sign as the {@code seconds} field.
   */
  private static boolean isDurationValid(long seconds, int nanos) {
    if (seconds < DURATION_SECONDS_MIN || seconds > DURATION_SECONDS_MAX) {
      return false;
    }
    if (nanos < -999999999L || nanos >= NANOS_PER_SECOND) {
      return false;
    }
    if (seconds < 0 || nanos < 0) {
      if (seconds > 0 || nanos > 0) {
        return false;
      }
    }
    return true;
  }

  /**
   * Returns true if the given number of seconds and nanos is a valid {@link Timestamp}. The {@code
   * seconds} value must be in the range [-62,135,596,800, +253,402,300,799] (i.e., between
   * 0001-01-01T00:00:00Z and 9999-12-31T23:59:59Z). The {@code nanos} value must be in the range
   * [0, +999,999,999].
   *
   * <p><b>Note:</b> Negative second values with fractional seconds must still have non-negative
   * nanos values that count forward in time.
   */
  private static boolean isTimestampValid(long seconds, int nanos) {
    if (!isTimestampSecondsValid(seconds)) {
      return false;
    }

    return nanos >= 0 && nanos < NANOS_PER_SECOND;
  }

  /**
   * Returns true if the given number of seconds is valid, if combined with a valid number of nanos.
   * The {@code seconds} value must be in the range [-62,135,596,800, +253,402,300,799] (i.e.,
   * between 0001-01-01T00:00:00Z and 9999-12-31T23:59:59Z).
   */
  @SuppressWarnings("GoodTime") // this is a legacy conversion API
  private static boolean isTimestampSecondsValid(long seconds) {
    return seconds >= TIMESTAMP_SECONDS_MIN && seconds <= TIMESTAMP_SECONDS_MAX;
  }

  private static Timestamp normalizedTimestamp(long seconds, int nanos) {
    if (nanos <= -NANOS_PER_SECOND || nanos >= NANOS_PER_SECOND) {
      seconds = checkedAdd(seconds, nanos / NANOS_PER_SECOND);
      nanos = nanos % NANOS_PER_SECOND;
    }
    if (nanos < 0) {
      nanos = nanos + NANOS_PER_SECOND; // no overflow since nanos is negative (and we're adding)
      seconds = checkedSubtract(seconds, 1);
    }
    return Timestamp.newBuilder().setSeconds(seconds).setNanos(nanos).build();
  }

  private static Duration normalizedDuration(long seconds, int nanos) {
    if (nanos <= -NANOS_PER_SECOND || nanos >= NANOS_PER_SECOND) {
      seconds = checkedAdd(seconds, nanos / NANOS_PER_SECOND);
      nanos %= NANOS_PER_SECOND;
    }
    if (seconds > 0 && nanos < 0) {
      nanos += NANOS_PER_SECOND; // no overflow since nanos is negative (and we're adding)
      seconds--; // no overflow since seconds is positive (and we're decrementing)
    }
    if (seconds < 0 && nanos > 0) {
      nanos -= NANOS_PER_SECOND; // no overflow since nanos is positive (and we're subtracting)
      seconds++; // no overflow since seconds is negative (and we're incrementing)
    }
    return Duration.newBuilder().setSeconds(seconds).setNanos(nanos).build();
  }

  private static String formatNanos(int nanos) {
    // Determine whether to use 3, 6, or 9 digits for the nano part.
    if (nanos % NANOS_PER_MILLISECOND == 0) {
      return String.format(Locale.ENGLISH, "%1$03d", nanos / NANOS_PER_MILLISECOND);
    } else if (nanos % NANOS_PER_MICROSECOND == 0) {
      return String.format(Locale.ENGLISH, "%1$06d", nanos / NANOS_PER_MICROSECOND);
    } else {
      return String.format(Locale.ENGLISH, "%1$09d", nanos);
    }
  }

  private ProtoTimeUtils() {}
}
