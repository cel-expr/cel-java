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

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.collect.ImmutableList;
import com.google.protobuf.ByteString;
import dev.cel.common.exceptions.CelAttributeNotFoundException;
import dev.cel.common.values.CelValueConverter;
import dev.cel.common.values.NullValue;
import dev.cel.common.values.OptionalValue;
import dev.cel.common.values.ProtoLiteCelValueConverter;
import dev.cel.common.values.ProtoMessageLiteValue;
import dev.cel.common.values.RawProtoMessageLiteValue;
import dev.cel.common.values.SelectableValue;
import dev.cel.protobuf.CelLiteDescriptor.FieldLiteDescriptor;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * LiteAttributeStep evaluates the optimized {@code cel.@attribute} and {@code cel.@hasField}
 * expressions emitted by {@code SelectOptimizer} against protobuf lite messages.
 *
 * <p>Each qualifier carries the protobuf field number alongside the field name, which allows fields
 * that have no generated class or descriptor in the evaluation environment (client-server version
 * skew) to be resolved directly off the wire.
 *
 * <p>CEL Library Internals. Do Not Use.
 */
final class LiteAttributeStep {

  /**
   * CEL select optimization type code for protobuf maps.
   *
   * <p>Protobuf canonical field types span {@code 1..18} ({@link FieldLiteDescriptor.Type}). A
   * negative number is deliberately chosen so that synthetic map qualifiers can never collide with
   * canonical or future protobuf type numbers.
   */
  private static final int CEL_MAP_TYPE_CODE = -1;

  private static final int MESSAGE_TYPE_CODE = FieldLiteDescriptor.Type.MESSAGE.getNumber();

  /**
   * Sentinel type name assigned to a submessage decoded straight off the wire whose declaring
   * message has no descriptor in the evaluation environment.
   *
   * <p>Nothing on the wire identifies the type of a length-delimited field, so the real name is
   * unrecoverable in this case. The sentinel is deliberately not a legal protobuf type name so that
   * it can never collide with, or be mistaken for, a resolvable type.
   */
  private static final String UNKNOWN_MESSAGE_TYPE_NAME = "cel.@unknownMessage";

  /**
   * Qualifies an attribute by applying each qualifier in {@code qualifierLists} in order.
   *
   * <p>Each qualifier is a {@code [field_number, field_name, type_code]} 3-tuple (for submessages),
   * or a {@code [field_number, field_name, type_code, default_value]} 4-tuple (for leaf fields with
   * pre-resolved default values).
   */
  static @Nullable Object qualifyAttribute(
      @Nullable Object target, List<?> qualifierLists, CelValueConverter celValueConverter) {
    checkNotNull(qualifierLists);
    checkNotNull(celValueConverter);

    if (target == null) {
      if (qualifierLists.isEmpty()) {
        return null;
      }
      List<?> first = asQualifier(qualifierLists.get(0), /* minSize= */ 3);
      throw CelAttributeNotFoundException.forFieldResolution(fieldNameOf(first));
    }

    Object obj = celValueConverter.toRuntimeValue(target);
    for (Object item : qualifierLists) {
      List<?> qualifier = asQualifier(item, /* minSize= */ 3);
      obj =
          select(
              obj,
              fieldNumberOf(qualifier),
              fieldNameOf(qualifier),
              typeCodeOf(qualifier),
              defaultValueOf(qualifier),
              celValueConverter);
      obj = celValueConverter.toRuntimeValue(obj);
    }

    return celValueConverter.maybeUnwrap(obj);
  }

  /**
   * Tests presence of an attribute by navigating the leading qualifiers in {@code qualifierLists}
   * and presence testing the last one.
   *
   * <p>Each qualifier is a {@code [field_number, field_name]} pair.
   */
  static boolean hasField(
      @Nullable Object target, List<?> qualifierLists, CelValueConverter celValueConverter) {
    checkNotNull(qualifierLists);
    checkNotNull(celValueConverter);
    if (target == null || qualifierLists.isEmpty()) {
      return false;
    }

    int lastIndex = qualifierLists.size() - 1;
    Object obj = celValueConverter.toRuntimeValue(target);
    for (int i = 0; i < lastIndex; i++) {
      List<?> qualifier = asQualifier(qualifierLists.get(i), /* minSize= */ 2);
      obj = navigate(obj, fieldNumberOf(qualifier), fieldNameOf(qualifier), celValueConverter);
      if (obj == null) {
        return false;
      }
      obj = celValueConverter.toRuntimeValue(obj);
    }

    List<?> terminalQualifier = asQualifier(qualifierLists.get(lastIndex), /* minSize= */ 2);
    return isPresent(
        obj, fieldNumberOf(terminalQualifier), fieldNameOf(terminalQualifier), celValueConverter);
  }

  private static Object select(
      Object obj,
      int fieldNumber,
      String fieldName,
      int typeCode,
      @Nullable Object defaultValue,
      CelValueConverter celValueConverter) {
    // TODO: Consolidate dispatch into OptimizedSelectable.
    switch (toTargetKind(obj, fieldName)) {
      case PROTO:
        return selectProto(obj, fieldNumber, fieldName, typeCode, defaultValue, celValueConverter);
      case SELECTABLE:
        Optional<Object> found = find((SelectableValue<?>) obj, fieldName);
        if (found.isPresent()) {
          return found.get();
        }
        if (defaultValue != null) {
          return defaultValue;
        }
        throw CelAttributeNotFoundException.forFieldResolution(fieldName);
      case MAP:
        Map<?, ?> map = (Map<?, ?>) obj;
        Object mapValue = map.get(fieldName);
        if (mapValue != null) {
          return mapValue;
        }
        if (map.containsKey(fieldName)) {
          return NullValue.NULL_VALUE;
        }
        throw CelAttributeNotFoundException.forMissingMapKey(fieldName);
    }
    throw new AssertionError("Unhandled target kind: " + obj.getClass());
  }

  private static Object selectProto(
      Object protoTarget,
      int fieldNumber,
      String fieldName,
      int typeCode,
      @Nullable Object defaultValue,
      CelValueConverter celValueConverter) {
    ImmutableList<Object> unknowns;
    String declaringTypeName;
    Optional<FieldLiteDescriptor> fieldDescriptor;

    if (protoTarget instanceof ProtoMessageLiteValue) {
      ProtoMessageLiteValue msg = (ProtoMessageLiteValue) protoTarget;
      declaringTypeName = msg.celType().name();
      fieldDescriptor = findFieldDescriptor(celValueConverter, declaringTypeName, fieldNumber);
      String currentFieldName =
          fieldDescriptor.map(FieldLiteDescriptor::getFieldName).orElse(fieldName);
      Object fieldValue = msg.fieldValues().get(currentFieldName);
      if (fieldValue != null) {
        return fieldValue;
      }
      unknowns = msg.unknownFields().get(fieldNumber);
    } else {
      RawProtoMessageLiteValue raw = (RawProtoMessageLiteValue) protoTarget;
      unknowns = raw.unknownFields().get(fieldNumber);
      declaringTypeName = raw.celType().name();
      fieldDescriptor = findFieldDescriptor(celValueConverter, declaringTypeName, fieldNumber);
    }

    String protoTypeName =
        fieldDescriptor
            .map(FieldLiteDescriptor::getFieldProtoTypeName)
            .orElse(UNKNOWN_MESSAGE_TYPE_NAME);
    Object decoded =
        decodeUnknownField(
            unknowns,
            fieldNumber,
            fieldName,
            typeCode,
            /* isRepeated= */ defaultValue instanceof List,
            protoTypeName);
    if (decoded != null) {
      return decoded;
    }
    if (defaultValue != null) {
      return defaultValue;
    }
    Optional<Object> typeDefault =
        findTypeDefault(celValueConverter, declaringTypeName, fieldNumber);
    if (typeDefault.isPresent()) {
      return typeDefault.get();
    }
    if (typeCode == MESSAGE_TYPE_CODE) {
      return RawProtoMessageLiteValue.create(ByteString.EMPTY, protoTypeName);
    }
    throw CelAttributeNotFoundException.forFieldResolution(fieldName);
  }

  /**
   * Navigates into an intermediate submessage of a {@code cel.@hasField} chain.
   *
   * <p>Unlike {@link #select}, an absent intermediate is not an error: it yields {@code null},
   * which short-circuits the presence test to {@code false}.
   */
  private static @Nullable Object navigate(
      Object obj, int fieldNumber, String fieldName, CelValueConverter celValueConverter) {
    switch (toTargetKind(obj, fieldName)) {
      case PROTO:
        return navigateProto(obj, fieldNumber, fieldName, celValueConverter);
      case SELECTABLE:
        return find((SelectableValue<?>) obj, fieldName).orElse(null);
      case MAP:
        return ((Map<?, ?>) obj).get(fieldName);
    }
    throw new AssertionError("Unhandled target kind: " + obj.getClass());
  }

  private static @Nullable Object navigateProto(
      Object protoTarget, int fieldNumber, String fieldName, CelValueConverter celValueConverter) {
    ImmutableList<Object> unknowns;
    String declaringTypeName;
    Optional<FieldLiteDescriptor> fieldDescriptor;

    if (protoTarget instanceof ProtoMessageLiteValue) {
      ProtoMessageLiteValue msg = (ProtoMessageLiteValue) protoTarget;
      declaringTypeName = msg.celType().name();
      fieldDescriptor = findFieldDescriptor(celValueConverter, declaringTypeName, fieldNumber);
      String currentFieldName =
          fieldDescriptor.map(FieldLiteDescriptor::getFieldName).orElse(fieldName);
      Object fieldValue = msg.fieldValues().get(currentFieldName);
      if (fieldValue != null) {
        return fieldValue;
      }
      unknowns = msg.unknownFields().get(fieldNumber);
    } else {
      RawProtoMessageLiteValue raw = (RawProtoMessageLiteValue) protoTarget;
      unknowns = raw.unknownFields().get(fieldNumber);
      declaringTypeName = raw.celType().name();
      fieldDescriptor = findFieldDescriptor(celValueConverter, declaringTypeName, fieldNumber);
    }

    String protoTypeName =
        fieldDescriptor
            .map(FieldLiteDescriptor::getFieldProtoTypeName)
            .orElse(UNKNOWN_MESSAGE_TYPE_NAME);
    return decodeUnknownField(
        unknowns,
        fieldNumber,
        fieldName,
        MESSAGE_TYPE_CODE,
        /* isRepeated= */ false,
        protoTypeName);
  }

  /** Presence tests the terminal field of a {@code cel.@hasField} chain. */
  private static boolean isPresent(
      Object obj, int fieldNumber, String fieldName, CelValueConverter celValueConverter) {
    switch (toTargetKind(obj, fieldName)) {
      case PROTO:
        return isPresentInProto(obj, fieldNumber, fieldName, celValueConverter);
      case SELECTABLE:
        return find((SelectableValue<?>) obj, fieldName).isPresent();
      case MAP:
        return ((Map<?, ?>) obj).containsKey(fieldName);
    }
    throw new AssertionError("Unhandled target kind: " + obj.getClass());
  }

  private static boolean isPresentInProto(
      Object protoTarget, int fieldNumber, String fieldName, CelValueConverter celValueConverter) {
    if (protoTarget instanceof ProtoMessageLiteValue) {
      ProtoMessageLiteValue msg = (ProtoMessageLiteValue) protoTarget;
      String currentFieldName =
          findFieldDescriptor(celValueConverter, msg.celType().name(), fieldNumber)
              .map(FieldLiteDescriptor::getFieldName)
              .orElse(fieldName);
      Object fieldValue = msg.fieldValues().get(currentFieldName);
      if (fieldValue instanceof Collection) {
        return !((Collection<?>) fieldValue).isEmpty();
      }
      if (fieldValue instanceof Map) {
        return !((Map<?, ?>) fieldValue).isEmpty();
      }
      return fieldValue != null || msg.unknownFields().containsKey(fieldNumber);
    }
    return ((RawProtoMessageLiteValue) protoTarget).hasField(fieldNumber);
  }

  // TODO: Remove the instanceof ladder once consolidated into OptimizedSelectable.
  private static TargetKind toTargetKind(Object obj, String fieldName) {
    if (obj instanceof OptionalValue) {
      throw new UnsupportedOperationException(
          "Optional operands are not yet supported by the lite select-optimized runtime");
    }
    // ProtoMessageLiteValue and RawProtoMessageLiteValue extend StructValue, which implements
    // SelectableValue. PROTO must be checked before SELECTABLE to ensure number-keyed descriptor
    // resolution is performed for protos.
    if (obj instanceof ProtoMessageLiteValue || obj instanceof RawProtoMessageLiteValue) {
      return TargetKind.PROTO;
    }
    if (obj instanceof SelectableValue) {
      return TargetKind.SELECTABLE;
    }
    if (obj instanceof Map) {
      return TargetKind.MAP;
    }
    throw CelAttributeNotFoundException.forFieldResolution(fieldName);
  }

  /**
   * Decodes a field that has no generated class or descriptor directly from its wire entries.
   *
   * @return the decoded value, or {@code null} if the field is not present on the wire.
   */
  private static @Nullable Object decodeUnknownField(
      ImmutableList<Object> wireEntries,
      int fieldNumber,
      String fieldName,
      int typeCode,
      boolean isRepeated,
      String protoTypeName) {
    if (wireEntries.isEmpty()) {
      return null;
    }
    if (typeCode == CEL_MAP_TYPE_CODE) {
      // Map entries are indistinguishable from repeated submessages on the wire without a
      // descriptor to supply the key and value types.
      throw new UnsupportedOperationException(
          "Decoding unknown map field from wire bytes is unsupported: " + fieldName);
    }

    return RawProtoMessageLiteValue.decodeWireEntries(
        wireEntries, typeCode, protoTypeName, isRepeated);
  }

  private static Optional<FieldLiteDescriptor> findFieldDescriptor(
      CelValueConverter celValueConverter, String declaringTypeName, int fieldNumber) {
    if (!(celValueConverter instanceof ProtoLiteCelValueConverter)) {
      return Optional.empty();
    }
    return ((ProtoLiteCelValueConverter) celValueConverter)
        .findFieldDescriptor(declaringTypeName, fieldNumber);
  }

  /** Returns the protobuf type default of {@code fieldNumber}, if a descriptor is available. */
  private static Optional<Object> findTypeDefault(
      CelValueConverter celValueConverter, String declaringTypeName, int fieldNumber) {
    if (!(celValueConverter instanceof ProtoLiteCelValueConverter)) {
      return Optional.empty();
    }
    try {
      return ((ProtoLiteCelValueConverter) celValueConverter)
          .findDefaultCelValue(declaringTypeName, fieldNumber);
    } catch (NoSuchElementException e) {
      return Optional.empty();
    }
  }

  @SuppressWarnings("unchecked") // Structs and maps qualified by a select chain are String keyed.
  private static Optional<Object> find(SelectableValue<?> selectable, String fieldName) {
    return (Optional<Object>) ((SelectableValue<String>) selectable).find(fieldName);
  }

  /** Validates and returns a single {@code [field_number, field_name, ...]} qualifier tuple. */
  private static List<?> asQualifier(Object item, int minSize) {
    if (!(item instanceof List)) {
      throw new IllegalArgumentException("Expected qualifier list, got: " + item);
    }
    List<?> qualifier = (List<?>) item;
    if (qualifier.size() < minSize
        || !(qualifier.get(0) instanceof Number)
        || !(qualifier.get(1) instanceof String)
        || (minSize > 2 && !(qualifier.get(2) instanceof Number))) {
      throw new IllegalArgumentException("Invalid qualifier format: " + qualifier);
    }
    return qualifier;
  }

  private static int fieldNumberOf(List<?> qualifier) {
    return ((Number) qualifier.get(0)).intValue();
  }

  private static String fieldNameOf(List<?> qualifier) {
    return (String) qualifier.get(1);
  }

  private static int typeCodeOf(List<?> qualifier) {
    return ((Number) qualifier.get(2)).intValue();
  }

  /**
   * Returns the pre-resolved default value from a 4-tuple qualifier {@code [field_number,
   * field_name, type_code, default_value]}, or {@code null} for 3-tuple qualifiers (submessages
   * without an inlined default).
   */
  private static @Nullable Object defaultValueOf(List<?> qualifier) {
    if (qualifier.size() > 3) {
      return qualifier.get(3);
    }
    return null;
  }

  private enum TargetKind {
    PROTO,
    SELECTABLE,
    MAP,
  }

  private LiteAttributeStep() {}
}
