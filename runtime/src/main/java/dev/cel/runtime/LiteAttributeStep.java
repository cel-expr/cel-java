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
import com.google.errorprone.annotations.Immutable;
import dev.cel.common.annotations.Internal;
import dev.cel.common.exceptions.CelAttributeNotFoundException;
import dev.cel.common.values.CelValueConverter;
import dev.cel.common.values.NullValue;
import dev.cel.common.values.OptionalValue;
import dev.cel.common.values.ProtoMessageLiteValue;
import dev.cel.common.values.RawProtoMessageLiteValue;
import dev.cel.common.values.SelectableValue;
import dev.cel.protobuf.CelLiteDescriptor.FieldLiteDescriptor;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * LiteAttributeStep provides qualification steps for evaluating optimized {@code cel.@attribute}
 * and {@code cel.@hasField} expressions on Protobuf Lite messages.
 *
 * <p>CEL Library Internals. Do Not Use.
 */
@Internal
public final class LiteAttributeStep {

  @Immutable
  private interface Step {
    Object qualify(Object value);
  }

  /** Step representing a single selection step in a {@code cel.@attribute} chain. */
  @Immutable
  private static final class LiteSelectQualifier implements Step {
    private final int fieldNumber;
    private final String fieldName;
    private final int typeCode;

    @SuppressWarnings("Immutable")
    private final Object defaultValue;

    @Override
    public Object qualify(Object obj) {
      if (obj == null || obj instanceof NullValue) {
        return defaultValue;
      }

      if (obj instanceof OptionalValue) {
        OptionalValue<?, ?> opt = (OptionalValue<?, ?>) obj;
        if (opt.isZeroValue()) {
          return OptionalValue.EMPTY;
        }
        obj = opt.value();
        if (obj == null || obj instanceof NullValue) {
          return defaultValue;
        }
      }

      if (obj instanceof ProtoMessageLiteValue) {
        ProtoMessageLiteValue msg = (ProtoMessageLiteValue) obj;
        Object fieldValue = msg.fieldValues().get(fieldName);
        if (fieldValue != null) {
          return msg.protoLiteCelValueConverter().toRuntimeValue(fieldValue);
        }

        ImmutableList<Object> unknowns = msg.unknownFields().get(fieldNumber);
        if (!unknowns.isEmpty()) {
          boolean isRepeated = defaultValue instanceof List;
          Object decoded =
              RawProtoMessageLiteValue.decodeWireEntries(
                  unknowns, typeCode, /* protoTypeName= */ fieldName, isRepeated);
          if (decoded != null) {
            return msg.protoLiteCelValueConverter().toRuntimeValue(decoded);
          }
        }

        Optional<Object> descDefault =
            msg.protoLiteCelValueConverter().findDefaultCelValue(msg.celType().name(), fieldName);
        if (descDefault.isPresent()) {
          return descDefault.get();
        }

        return defaultValue;
      }

      if (obj instanceof RawProtoMessageLiteValue) {
        RawProtoMessageLiteValue rawMsg = (RawProtoMessageLiteValue) obj;
        ImmutableList<Object> unknowns = rawMsg.unknownFields().get(fieldNumber);
        if (!unknowns.isEmpty()) {
          boolean isRepeated = defaultValue instanceof List;
          Object decoded =
              RawProtoMessageLiteValue.decodeWireEntries(
                  unknowns, typeCode, /* protoTypeName= */ fieldName, isRepeated);
          if (decoded != null) {
            return decoded;
          }
        }

        return defaultValue;
      }

      if (obj instanceof SelectableValue) {
        @SuppressWarnings("unchecked") // Safe cast: SelectableValue keys on String
        SelectableValue<String> selectable = (SelectableValue<String>) obj;
        Optional<?> found = selectable.find(fieldName);
        if (found.isPresent()) {
          return found.get();
        }
        return defaultValue;
      }

      if (obj instanceof Map) {
        Map<?, ?> map = (Map<?, ?>) obj;
        Object mapVal = map.get(fieldName);
        if (mapVal != null) {
          return mapVal;
        }
        if (map.containsKey(fieldName)) {
          return NullValue.NULL_VALUE;
        }
        throw CelAttributeNotFoundException.forMissingMapKey(fieldName);
      }

      throw CelAttributeNotFoundException.forFieldResolution(fieldName);
    }

    private static LiteSelectQualifier create(
        int fieldNumber, String fieldName, int typeCode, Object defaultValue) {
      return new LiteSelectQualifier(
          fieldNumber,
          fieldName,
          typeCode,
          defaultValue == null ? NullValue.NULL_VALUE : defaultValue);
    }

    private LiteSelectQualifier(
        int fieldNumber, String fieldName, int typeCode, Object defaultValue) {
      this.fieldNumber = fieldNumber;
      this.fieldName = checkNotNull(fieldName);
      this.typeCode = typeCode;
      this.defaultValue = defaultValue;
    }
  }

  /** Step representing an intermediate submessage navigation step in {@code cel.@hasField}. */
  @Immutable
  private static final class LiteSubmessageQualifier implements Step {
    private final int fieldNumber;
    private final String fieldName;

    @Override
    public Object qualify(Object obj) {
      if (obj == null || obj instanceof NullValue) {
        return NullValue.NULL_VALUE;
      }

      if (obj instanceof OptionalValue) {
        OptionalValue<?, ?> opt = (OptionalValue<?, ?>) obj;
        if (opt.isZeroValue()) {
          return NullValue.NULL_VALUE;
        }
        obj = opt.value();
        if (obj == null || obj instanceof NullValue) {
          return NullValue.NULL_VALUE;
        }
      }

      if (obj instanceof ProtoMessageLiteValue) {
        ProtoMessageLiteValue msg = (ProtoMessageLiteValue) obj;
        Object fieldValue = msg.fieldValues().get(fieldName);
        if (fieldValue != null) {
          return msg.protoLiteCelValueConverter().toRuntimeValue(fieldValue);
        }

        ImmutableList<Object> unknowns = msg.unknownFields().get(fieldNumber);
        if (!unknowns.isEmpty()) {
          Object decoded =
              RawProtoMessageLiteValue.decodeWireEntries(
                  unknowns,
                  FieldLiteDescriptor.Type.MESSAGE.getNumber(),
                  /* protoTypeName= */ fieldName,
                  /* isRepeated= */ false);
          if (decoded != null) {
            return msg.protoLiteCelValueConverter().toRuntimeValue(decoded);
          }
        }

        Optional<Object> descDefault =
            msg.protoLiteCelValueConverter().findDefaultCelValue(msg.celType().name(), fieldName);
        if (descDefault.isPresent()) {
          return descDefault.get();
        }

        return NullValue.NULL_VALUE;
      }

      if (obj instanceof RawProtoMessageLiteValue) {
        RawProtoMessageLiteValue rawMsg = (RawProtoMessageLiteValue) obj;
        ImmutableList<Object> unknowns = rawMsg.unknownFields().get(fieldNumber);
        if (!unknowns.isEmpty()) {
          Object decoded =
              RawProtoMessageLiteValue.decodeWireEntries(
                  unknowns,
                  FieldLiteDescriptor.Type.MESSAGE.getNumber(),
                  /* protoTypeName= */ fieldName,
                  /* isRepeated= */ false);
          if (decoded != null) {
            return decoded;
          }
        }

        return NullValue.NULL_VALUE;
      }

      if (obj instanceof SelectableValue) {
        @SuppressWarnings("unchecked") // Safe cast: SelectableValue keys on String
        SelectableValue<String> selectable = (SelectableValue<String>) obj;
        Optional<?> found = selectable.find(fieldName);
        return found.isPresent() ? found.get() : NullValue.NULL_VALUE;
      }

      if (obj instanceof Map) {
        Map<?, ?> map = (Map<?, ?>) obj;
        Object mapVal = map.get(fieldName);
        return mapVal != null ? mapVal : NullValue.NULL_VALUE;
      }

      return NullValue.NULL_VALUE;
    }

    private static LiteSubmessageQualifier create(int fieldNumber, String fieldName) {
      return new LiteSubmessageQualifier(fieldNumber, fieldName);
    }

    private LiteSubmessageQualifier(int fieldNumber, String fieldName) {
      this.fieldNumber = fieldNumber;
      this.fieldName = checkNotNull(fieldName);
    }
  }

  /** Step representing the terminal presence test step in {@code cel.@hasField}. */
  @Immutable
  private static final class LitePresenceQualifier implements Step {
    private final int fieldNumber;
    private final String fieldName;

    @Override
    public Object qualify(Object obj) {
      if (obj == null || obj instanceof NullValue) {
        return false;
      }

      if (obj instanceof OptionalValue) {
        OptionalValue<?, ?> opt = (OptionalValue<?, ?>) obj;
        if (opt.isZeroValue()) {
          return false;
        }
        obj = opt.value();
        if (obj == null || obj instanceof NullValue) {
          return false;
        }
      }

      if (obj instanceof ProtoMessageLiteValue) {
        ProtoMessageLiteValue msg = (ProtoMessageLiteValue) obj;
        if (msg.fieldValues().containsKey(fieldName)) {
          return true;
        }
        if (msg.unknownFields().containsKey(fieldNumber)) {
          return true;
        }
        return false;
      }

      if (obj instanceof RawProtoMessageLiteValue) {
        RawProtoMessageLiteValue rawMsg = (RawProtoMessageLiteValue) obj;
        return rawMsg.unknownFields().containsKey(fieldNumber);
      }

      if (obj instanceof SelectableValue) {
        @SuppressWarnings("unchecked") // Safe cast: SelectableValue keys on Object
        SelectableValue<Object> selectable = (SelectableValue<Object>) obj;
        return selectable.find(fieldName).isPresent();
      }

      if (obj instanceof Map) {
        Map<?, ?> map = (Map<?, ?>) obj;
        return map.containsKey(fieldName);
      }

      return false;
    }

    private static LitePresenceQualifier create(int fieldNumber, String fieldName) {
      return new LitePresenceQualifier(fieldNumber, fieldName);
    }

    private LitePresenceQualifier(int fieldNumber, String fieldName) {
      this.fieldNumber = fieldNumber;
      this.fieldName = checkNotNull(fieldName);
    }
  }

  /**
   * Qualifies an attribute dynamically by applying the sequence of qualifiers in {@code
   * qualifierLists}.
   */
  @Internal
  public static Object qualifyAttribute(
      Object target, List<?> qualifierLists, CelValueConverter celValueConverter) {
    checkNotNull(qualifierLists);
    checkNotNull(celValueConverter);
    if (target == null) {
      target = NullValue.NULL_VALUE;
    }

    Object obj = celValueConverter.toRuntimeValue(target);
    for (Object item : qualifierLists) {
      if (!(item instanceof List)) {
        throw new IllegalArgumentException("Expected qualifier list, got: " + item);
      }
      List<?> qualifier = (List<?>) item;
      int fieldNumber = ((Number) qualifier.get(0)).intValue();
      String fieldName = (String) qualifier.get(1);
      int typeCode = ((Number) qualifier.get(2)).intValue();
      Object defaultValue = qualifier.size() > 3 ? qualifier.get(3) : NullValue.NULL_VALUE;
      Step step = LiteSelectQualifier.create(fieldNumber, fieldName, typeCode, defaultValue);
      obj = step.qualify(obj);
      obj = celValueConverter.toRuntimeValue(obj);
    }
    return celValueConverter.maybeUnwrap(obj);
  }

  /**
   * Tests presence of an attribute dynamically by navigating qualifiers in {@code qualifierLists}
   * and checking presence at the final step.
   */
  @Internal
  public static boolean hasField(
      Object target, List<?> qualifierLists, CelValueConverter celValueConverter) {
    checkNotNull(qualifierLists);
    checkNotNull(celValueConverter);
    if (target == null) {
      return false;
    }

    Object obj = celValueConverter.toRuntimeValue(target);
    int size = qualifierLists.size();
    for (int i = 0; i < size; i++) {
      Object item = qualifierLists.get(i);
      if (!(item instanceof List)) {
        throw new IllegalArgumentException("Expected qualifier list, got: " + item);
      }
      List<?> qualifier = (List<?>) item;
      int fieldNumber = ((Number) qualifier.get(0)).intValue();
      String fieldName = (String) qualifier.get(1);
      if (i < size - 1) {
        Step step = LiteSubmessageQualifier.create(fieldNumber, fieldName);
        obj = step.qualify(obj);
        if (obj == null || obj instanceof NullValue) {
          return false;
        }
        obj = celValueConverter.toRuntimeValue(obj);
      } else {
        Step step = LitePresenceQualifier.create(fieldNumber, fieldName);
        Object result = step.qualify(obj);
        return Objects.equals(result, true);
      }
    }
    return false;
  }

  private LiteAttributeStep() {}
}
