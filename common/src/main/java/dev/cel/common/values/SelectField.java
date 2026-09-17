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

package dev.cel.common.values;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import com.google.auto.value.AutoValue;
import com.google.errorprone.annotations.Immutable;
import dev.cel.common.annotations.Internal;
import org.jspecify.annotations.Nullable;

/**
 * Represents a single field selection in an optimized selection chain.
 *
 * <p>CEL Library Internals. Do Not Use.
 */
@Internal
@AutoValue
@AutoValue.CopyAnnotations
@Immutable
@SuppressWarnings("Immutable") // Default value is an immutable CEL literal or null
public abstract class SelectField {

  private static final int MAX_FIELD_NUMBER = 536870911;

  /** CEL-specific type code used to encode CEL maps on the wire. */
  public static final int CEL_MAP_TYPE_CODE = -1;

  /** Sentinel for a presence-test qualifier, whose 2-tuple carries no type code. */
  public static final int NO_TYPE_CODE = 0;

  /** Protobuf field type code for {@code TYPE_MESSAGE} ({@code FieldDescriptorProto.Type}). */
  public static final int MESSAGE_TYPE_CODE = 11;

  // Mirrors FieldDescriptorProto.Type. Not validated against a protobuf enum because the :values
  // target is deliberately protobuf-free; keep in sync with CelLiteDescriptor.FieldLiteDescriptor.
  private static final int MIN_PROTO_TYPE_CODE = 1; // TYPE_DOUBLE
  private static final int MAX_PROTO_TYPE_CODE = 18; // TYPE_SINT64
  private static final int GROUP_PROTO_TYPE_CODE = 10; // Unsupported by CEL.

  /** Protobuf field number of this hop. */
  public abstract int fieldNumber();

  /** Protobuf field name or map key of this hop. */
  public abstract String fieldName();

  /**
   * Protobuf wire type code (1..18, except 10), {@link #CEL_MAP_TYPE_CODE}, or {@link
   * #NO_TYPE_CODE}.
   */
  public abstract int typeCode();

  /**
   * Default value for this hop, or null if unspecified. When non-null, this must be an immutable
   * CEL literal value.
   */
  public abstract @Nullable Object defaultValue();

  /**
   * Creates a presence-test qualifier hop.
   *
   * @param fieldNumber Protobuf field number. Takes {@code long} for compatibility with CEL's int64
   *     constant representations.
   * @param fieldName Protobuf field name.
   */
  public static SelectField create(long fieldNumber, String fieldName) {
    checkArgument(
        fieldNumber >= 1 && fieldNumber <= MAX_FIELD_NUMBER,
        "Field number out of protobuf range: %s",
        fieldNumber);
    checkNotNull(fieldName);
    return new AutoValue_SelectField(
        (int) fieldNumber, fieldName, NO_TYPE_CODE, /* defaultValue= */ null);
  }

  /**
   * Creates a fully-specified field selection hop with type code and optional default value.
   *
   * @param fieldNumber Protobuf field number. Takes {@code long} for compatibility with CEL's int64
   *     constant representations.
   * @param fieldName Protobuf field name.
   * @param typeCode Protobuf wire type code or {@link #CEL_MAP_TYPE_CODE}. Takes {@code long} for
   *     compatibility with CEL's int64 constant representations.
   * @param defaultValue Default value for the field, or null if unspecified.
   */
  public static SelectField create(
      long fieldNumber, String fieldName, long typeCode, @Nullable Object defaultValue) {
    checkArgument(
        fieldNumber >= 1 && fieldNumber <= MAX_FIELD_NUMBER,
        "Field number out of protobuf range: %s",
        fieldNumber);
    checkNotNull(fieldName);
    checkArgument(isSupportedTypeCode(typeCode), "Invalid protobuf type code: %s", typeCode);
    return new AutoValue_SelectField((int) fieldNumber, fieldName, (int) typeCode, defaultValue);
  }

  /**
   * Returns whether {@code typeCode} is a protobuf field type code CEL supports, or the {@link
   * #CEL_MAP_TYPE_CODE} sentinel.
   */
  public static boolean isSupportedTypeCode(long typeCode) {
    if (typeCode == CEL_MAP_TYPE_CODE) {
      return true;
    }
    return typeCode >= MIN_PROTO_TYPE_CODE
        && typeCode <= MAX_PROTO_TYPE_CODE
        && typeCode != GROUP_PROTO_TYPE_CODE;
  }

  SelectField() {}
}
