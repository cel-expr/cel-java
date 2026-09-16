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
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Represents a single field selection hop in an optimized selection chain.
 *
 * <p>CEL Library Internals. Do Not Use.
 */
@Internal
@AutoValue
@AutoValue.CopyAnnotations
@Immutable
@SuppressWarnings("Immutable") // Default value is an immutable CEL literal or null
public abstract class SelectField {

  public static final long MAX_FIELD_NUMBER = 536870911L;
  public static final int CEL_MAP_TYPE_CODE = -1;

  static final int NO_TYPE_CODE = 0;

  public abstract int fieldNumber();

  public abstract String fieldName();

  public abstract int typeCode();

  public abstract @Nullable Object defaultValue();

  public static SelectField create(long fieldNumber, String fieldName) {
    checkArgument(
        fieldNumber >= 1 && fieldNumber <= MAX_FIELD_NUMBER,
        "Field number out of protobuf range: %s",
        fieldNumber);
    checkNotNull(fieldName);
    return new AutoValue_SelectField(
        (int) fieldNumber, fieldName, NO_TYPE_CODE, /* defaultValue= */ null);
  }

  public static SelectField create(
      long fieldNumber, String fieldName, int typeCode, @Nullable Object defaultValue) {
    checkArgument(
        fieldNumber >= 1 && fieldNumber <= MAX_FIELD_NUMBER,
        "Field number out of protobuf range: %s",
        fieldNumber);
    checkNotNull(fieldName);
    checkArgument(
        typeCode == CEL_MAP_TYPE_CODE || (typeCode >= 1 && typeCode <= 18),
        "Invalid protobuf type code: %s",
        typeCode);
    return new AutoValue_SelectField((int) fieldNumber, fieldName, typeCode, defaultValue);
  }

  @SuppressWarnings("unchecked") // Structs and maps qualified by a select chain are String keyed.
  static Optional<Object> findField(SelectableValue<?> selectable, String fieldName) {
    return (Optional<Object>) ((SelectableValue<String>) selectable).find(fieldName);
  }

  // Package-private constructor to prevent subclassing outside the package.
  SelectField() {}
}
