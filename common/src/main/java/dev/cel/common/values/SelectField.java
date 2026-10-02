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
import java.util.Collections;
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
  static final int NO_TYPE_CODE = 0;

  /** Protobuf field type code for {@code TYPE_MESSAGE} ({@code FieldDescriptorProto.Type}). */
  public static final int MESSAGE_TYPE_CODE = 11;

  // Mirrors FieldDescriptorProto.Type. Not validated against a protobuf enum because the :values
  // target is deliberately protobuf-free; keep in sync with CelLiteDescriptor.FieldLiteDescriptor.
  private static final int MIN_PROTO_TYPE_CODE = 1; // TYPE_DOUBLE
  private static final int MAX_PROTO_TYPE_CODE = 18; // TYPE_SINT64
  private static final int GROUP_PROTO_TYPE_CODE = 10; // Unsupported by CEL.

  /** Protobuf field number of this hop. */
  abstract int fieldNumber();

  /** Protobuf field name or map key of this hop. */
  public abstract String fieldName();

  /**
   * Protobuf field type code (1..18, except 10), {@link #CEL_MAP_TYPE_CODE}, or {@link
   * #NO_TYPE_CODE}.
   */
  abstract int typeCode();

  /**
   * Default value for this hop, or null if unspecified. When non-null, this must be an immutable
   * CEL literal value.
   */
  abstract @Nullable Object defaultValue();

  /**
   * Protobuf message type name associated with this hop (e.g. {@code "google.protobuf.Duration"} or
   * {@code "com.example.User"} for singular/repeated message fields, or the map value's message
   * type name for map fields with {@link #CEL_MAP_TYPE_CODE}), or empty string if unspecified.
   */
  abstract String protoTypeName();

  /**
   * Map entry decoding specification. Non-null if and only if this is a map field ({@link
   * #CEL_MAP_TYPE_CODE}).
   */
  abstract @Nullable MapEntrySpec mapEntrySpec();

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
        (int) fieldNumber,
        fieldName,
        NO_TYPE_CODE,
        /* defaultValue= */ null,
        /* protoTypeName= */ "",
        /* mapEntrySpec= */ null);
  }

  /**
   * Creates a non-map field selection hop with type code and no default value or protobuf type
   * name. Use {@link #createMap} for map fields.
   *
   * @param fieldNumber Protobuf field number. Takes {@code long} for compatibility with CEL's int64
   *     constant representations.
   * @param fieldName Protobuf field name.
   * @param typeCode Protobuf field type code. Takes {@code long} for compatibility with CEL's int64
   *     constant representations.
   */
  public static SelectField create(long fieldNumber, String fieldName, long typeCode) {
    return create(
        fieldNumber, fieldName, typeCode, /* defaultValue= */ null, /* protoTypeName= */ "");
  }

  /**
   * Creates a non-map field selection hop with type code and default value. Use {@link #createMap}
   * for map fields.
   *
   * @param fieldNumber Protobuf field number. Takes {@code long} for compatibility with CEL's int64
   *     constant representations.
   * @param fieldName Protobuf field name.
   * @param typeCode Protobuf field type code. Takes {@code long} for compatibility with CEL's int64
   *     constant representations.
   * @param defaultValue Default value for the field.
   */
  public static SelectField create(
      long fieldNumber, String fieldName, long typeCode, Object defaultValue) {
    checkNotNull(defaultValue);
    return create(fieldNumber, fieldName, typeCode, defaultValue, /* protoTypeName= */ "");
  }

  /**
   * Creates a fully-specified non-map field selection hop with type code, optional default value,
   * and protobuf type name. Use {@link #createMap} for map fields.
   *
   * @param fieldNumber Protobuf field number. Takes {@code long} for compatibility with CEL's int64
   *     constant representations.
   * @param fieldName Protobuf field name.
   * @param typeCode Protobuf field type code. Takes {@code long} for compatibility with CEL's int64
   *     constant representations.
   * @param defaultValue Default value for the field, or null if unspecified.
   * @param protoTypeName Protobuf message type name, or empty string if unspecified.
   */
  public static SelectField create(
      long fieldNumber,
      String fieldName,
      long typeCode,
      @Nullable Object defaultValue,
      String protoTypeName) {
    checkArgument(
        typeCode != CEL_MAP_TYPE_CODE, "Map fields must be created via createMap: %s", fieldName);
    return newInstance(
        fieldNumber, fieldName, typeCode, defaultValue, protoTypeName, /* mapEntrySpec= */ null);
  }

  /**
   * Creates a field selection hop for a map field with scalar values.
   *
   * @param fieldNumber Protobuf field number.
   * @param fieldName Protobuf field name.
   * @param mapEntrySpec Specification for decoding map entries.
   */
  public static SelectField createMap(
      long fieldNumber, String fieldName, MapEntrySpec mapEntrySpec) {
    return createMap(fieldNumber, fieldName, mapEntrySpec, /* protoTypeName= */ "");
  }

  /**
   * Creates a field selection hop for a map field.
   *
   * @param fieldNumber Protobuf field number.
   * @param fieldName Protobuf field name.
   * @param mapEntrySpec Specification for decoding map entries.
   * @param protoTypeName Protobuf message type name of the map value. Must be non-empty if and only
   *     if the map value is a message.
   */
  public static SelectField createMap(
      long fieldNumber, String fieldName, MapEntrySpec mapEntrySpec, String protoTypeName) {
    checkNotNull(mapEntrySpec);
    checkNotNull(protoTypeName);
    checkArgument(
        (mapEntrySpec.valueTypeCode() == MESSAGE_TYPE_CODE) != protoTypeName.isEmpty(),
        "Map value proto type name '%s' is inconsistent with value type code %s",
        protoTypeName,
        mapEntrySpec.valueTypeCode());
    return newInstance(
        fieldNumber,
        fieldName,
        CEL_MAP_TYPE_CODE,
        /* defaultValue= */ Collections.emptyMap(),
        protoTypeName,
        mapEntrySpec);
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

  /** Specification for decoding map entries directly from the wire. */
  @AutoValue
  @AutoValue.CopyAnnotations
  @Immutable
  public abstract static class MapEntrySpec {

    // Mirrors the integral, bool, and string type numbers in
    // dev.cel.protobuf.CelLiteDescriptor.FieldLiteDescriptor.Type
    // Duplicated as int constants to keep :select_field and :planner free of protobuf_lite deps.
    private static final int TYPE_INT64 = 3;
    private static final int TYPE_UINT64 = 4;
    private static final int TYPE_INT32 = 5;
    private static final int TYPE_FIXED64 = 6;
    private static final int TYPE_FIXED32 = 7;
    private static final int TYPE_BOOL = 8;
    private static final int TYPE_STRING = 9;
    private static final int TYPE_UINT32 = 13;
    private static final int TYPE_SFIXED32 = 15;
    private static final int TYPE_SFIXED64 = 16;
    private static final int TYPE_SINT32 = 17;
    private static final int TYPE_SINT64 = 18;

    /** Protobuf field type code ({@code FieldDescriptorProto.Type}) of the map key (tag 1). */
    public abstract int keyTypeCode();

    /** Protobuf field type code ({@code FieldDescriptorProto.Type}) of the map value (tag 2). */
    public abstract int valueTypeCode();

    /**
     * Creates a new map entry specification.
     *
     * @param keyTypeCode Protobuf field type code of the map key. Takes {@code long} for CEL int64
     *     constant representation.
     * @param valueTypeCode Protobuf field type code of the map value. Takes {@code long} for CEL
     *     int64 constant representation.
     */
    public static MapEntrySpec create(long keyTypeCode, long valueTypeCode) {
      checkArgument(
          isValidMapKeyTypeCode(keyTypeCode), "Invalid map key type code: %s", keyTypeCode);
      checkArgument(
          isSupportedTypeCode(valueTypeCode) && valueTypeCode != CEL_MAP_TYPE_CODE,
          "Invalid map value type code: %s",
          valueTypeCode);
      return new AutoValue_SelectField_MapEntrySpec((int) keyTypeCode, (int) valueTypeCode);
    }

    /** Returns whether {@code typeCode} is an integral, bool, or string type permitted as a key. */
    private static boolean isValidMapKeyTypeCode(long typeCode) {
      return typeCode == TYPE_INT64
          || typeCode == TYPE_UINT64
          || typeCode == TYPE_INT32
          || typeCode == TYPE_FIXED64
          || typeCode == TYPE_FIXED32
          || typeCode == TYPE_BOOL
          || typeCode == TYPE_STRING
          || typeCode == TYPE_UINT32
          || typeCode == TYPE_SFIXED32
          || typeCode == TYPE_SFIXED64
          || typeCode == TYPE_SINT32
          || typeCode == TYPE_SINT64;
    }

    MapEntrySpec() {}
  }

  private static SelectField newInstance(
      long fieldNumber,
      String fieldName,
      long typeCode,
      @Nullable Object defaultValue,
      String protoTypeName,
      @Nullable MapEntrySpec mapEntrySpec) {
    checkArgument(
        fieldNumber >= 1 && fieldNumber <= MAX_FIELD_NUMBER,
        "Field number out of protobuf range: %s",
        fieldNumber);
    checkNotNull(fieldName);
    checkArgument(isSupportedTypeCode(typeCode), "Invalid protobuf type code: %s", typeCode);
    checkNotNull(protoTypeName);
    return new AutoValue_SelectField(
        (int) fieldNumber, fieldName, (int) typeCode, defaultValue, protoTypeName, mapEntrySpec);
  }

  SelectField() {}
}
