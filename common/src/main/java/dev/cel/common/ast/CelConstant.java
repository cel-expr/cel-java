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

package dev.cel.common.ast;

import com.google.auto.value.AutoOneOf;
import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableSet;
import com.google.common.primitives.UnsignedLong;
import com.google.errorprone.annotations.Immutable;
import com.google.protobuf.ByteString;
import com.google.protobuf.Duration;
import com.google.protobuf.Timestamp;
import dev.cel.common.values.CelByteString;
import dev.cel.common.values.NullValue;

/**
 * Represents a primitive literal.
 *
 * <p>This is the CEL-Java native type equivalent of Constant message type from syntax.proto.
 */
@AutoOneOf(CelConstant.Kind.class)
@Immutable
public abstract class CelConstant {
  private static final ImmutableSet<Class<?>> CONSTANT_CLASSES =
      ImmutableSet.of(
          NullValue.class,
          Boolean.class,
          Long.class,
          UnsignedLong.class,
          Double.class,
          String.class,
          CelByteString.class);

  /** Represents the type of the Constant */
  public enum Kind {
    NOT_SET,
    NULL_VALUE,
    BOOLEAN_VALUE,
    INT64_VALUE,
    UINT64_VALUE,
    DOUBLE_VALUE,
    STRING_VALUE,
    BYTES_VALUE,
    /**
     * @deprecated Do not use. Timestamp is no longer built-in CEL type.
     */
    @Deprecated
    TIMESTAMP_VALUE,
    /**
     * @deprecated Do not use. Duration is no longer built-in CEL type.
     */
    @Deprecated
    DURATION_VALUE
  }

  public abstract Kind getKind();

  /**
   * An unset constant.
   *
   * <p>As the name implies, this constant does nothing. This is used to represent a default
   * instance of CelConstant.
   */
  @AutoValue
  @Immutable
  public abstract static class CelConstantNotSet {}

  public abstract CelConstantNotSet notSet();

  public abstract NullValue nullValue();

  public abstract boolean booleanValue();

  public abstract long int64Value();

  public abstract UnsignedLong uint64Value();

  public abstract double doubleValue();

  public abstract String stringValue();

  public abstract CelByteString bytesValue();

  /**
   * @deprecated Do not use. Timestamp is no longer built-in CEL type.
   */
  @Deprecated
  public abstract Timestamp timestampValue();

  /**
   * @deprecated Do not use. Duration is no longer built-in CEL type.
   */
  @Deprecated
  public abstract Duration durationValue();

  public static CelConstant ofNotSet() {
    return AutoOneOf_CelConstant.notSet(new AutoValue_CelConstant_CelConstantNotSet());
  }

  public static CelConstant ofValue(NullValue value) {
    return AutoOneOf_CelConstant.nullValue(value);
  }

  public static CelConstant ofValue(boolean value) {
    return AutoOneOf_CelConstant.booleanValue(value);
  }

  public static CelConstant ofValue(long value) {
    return AutoOneOf_CelConstant.int64Value(value);
  }

  public static CelConstant ofValue(UnsignedLong value) {
    return AutoOneOf_CelConstant.uint64Value(value);
  }

  public static CelConstant ofValue(double value) {
    return AutoOneOf_CelConstant.doubleValue(value);
  }

  public static CelConstant ofValue(String value) {
    return AutoOneOf_CelConstant.stringValue(value);
  }

  public static CelConstant ofValue(CelByteString value) {
    return AutoOneOf_CelConstant.bytesValue(value);
  }

  /**
   * @deprecated Use native type equivalent {@link #ofValue(CelByteString)} instead.
   */
  @Deprecated
  public static CelConstant ofValue(ByteString value) {
    CelByteString celByteString = CelByteString.of(value.toByteArray());
    return ofValue(celByteString);
  }

  /**
   * @deprecated Do not use. Duration is no longer built-in CEL type.
   */
  @Deprecated
  public static CelConstant ofValue(Duration value) {
    return AutoOneOf_CelConstant.durationValue(value);
  }

  /**
   * @deprecated Do not use. Timestamp is no longer built-in CEL type.
   */
  @Deprecated
  public static CelConstant ofValue(Timestamp value) {
    return AutoOneOf_CelConstant.timestampValue(value);
  }

  /** Checks whether the provided Java object is a valid CelConstant value. */
  public static boolean isConstantValue(Object value) {
    return CONSTANT_CLASSES.contains(value.getClass());
  }

  /**
   * Converts the given Java object into a CelConstant value. This is equivalent of calling {@link
   * CelConstant#ofValue} with concrete types.
   *
   * @throws IllegalArgumentException If the value is not a supported CelConstant. This includes the
   *     deprecated duration and timestamp values.
   */
  public static CelConstant ofObjectValue(Object value) {
    if (value instanceof NullValue) {
      return ofValue((NullValue) value);
    } else if (value instanceof Boolean) {
      return ofValue((boolean) value);
    } else if (value instanceof Long) {
      return ofValue((long) value);
    } else if (value instanceof UnsignedLong) {
      return ofValue((UnsignedLong) value);
    } else if (value instanceof Double) {
      return ofValue((double) value);
    } else if (value instanceof String) {
      return ofValue((String) value);
    } else if (value instanceof CelByteString) {
      return ofValue((CelByteString) value);
    }

    throw new IllegalArgumentException("Value is not a CelConstant: " + value);
  }
}
