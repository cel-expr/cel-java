// Copyright 2022 Google LLC
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

import com.google.errorprone.annotations.Immutable;
import com.google.protobuf.ByteString;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Message;
import com.google.protobuf.MessageOrBuilder;
import com.google.protobuf.NullValue;
import dev.cel.common.CelOptions;
import dev.cel.common.annotations.Internal;
import dev.cel.common.exceptions.CelAttributeNotFoundException;
import dev.cel.common.internal.DynamicProto;
import dev.cel.common.internal.ProtoAdapter;
import dev.cel.common.internal.ProtoMessageFactory;
import dev.cel.common.internal.ProtoTimeUtils;
import dev.cel.common.types.CelTypes;
import dev.cel.common.values.CelByteString;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * An implementation of {@link RuntimeTypeProvider} which relies on proto descriptors.
 *
 * <p>This can handle all messages providable by the given {@link MessageFactory}. In addition, one
 * can provide message descriptors for messages external to the program which are provided via a
 * {@link DynamicMessageFactory}.
 *
 * <p>CEL Library Internals. Do Not Use.
 */
@Immutable
@Internal
public final class DescriptorMessageProvider implements RuntimeTypeProvider {
  private final ProtoMessageFactory protoMessageFactory;
  private final CelOptions celOptions;

  @SuppressWarnings("Immutable")
  private final ProtoAdapter protoAdapter;

  /**
   * Creates a new message provider with the given message factory.
   *
   * @deprecated Migrate to the CEL-Java fluent APIs. See {@code CelRuntimeFactory}.
   */
  @Deprecated
  public DescriptorMessageProvider(MessageFactory messageFactory) {
    this(messageFactory.toProtoMessageFactory(), CelOptions.LEGACY);
  }

  /**
   * Create a new message provider with a given message factory and custom descriptor set to use
   * when adapting from proto to CEL and vice versa.
   */
  public DescriptorMessageProvider(ProtoMessageFactory protoMessageFactory, CelOptions celOptions) {
    this.protoMessageFactory = protoMessageFactory;
    this.celOptions = celOptions;
    this.protoAdapter = new ProtoAdapter(DynamicProto.create(protoMessageFactory), celOptions);
  }

  @Override
  public @Nullable Object createMessage(String messageName, Map<String, Object> values) {
    Message.Builder builder =
        protoMessageFactory
            .newBuilder(messageName)
            .orElseThrow(
                () ->
                    CelAttributeNotFoundException.of(
                        String.format("cannot resolve '%s' as a message", messageName)));

    try {
      Descriptor descriptor = builder.getDescriptorForType();
      for (Map.Entry<String, Object> entry : values.entrySet()) {
        FieldDescriptor fieldDescriptor = findField(descriptor, entry.getKey());
        Optional<Object> fieldValue =
            protoAdapter.adaptValueToFieldType(fieldDescriptor, entry.getValue());
        fieldValue.ifPresent(o -> builder.setField(fieldDescriptor, o));
      }
      return protoAdapter.adaptProtoToValue(ProtoTimeUtils.validateWellKnownProto(builder.build()));
    } catch (ArrayIndexOutOfBoundsException e) {
      throw new IllegalArgumentException(e.getMessage());
    }
  }

  @Override
  @SuppressWarnings("unchecked")
  public @Nullable Object selectField(Object message, String fieldName) {
    boolean isOptionalMessage = false;
    if (message instanceof Optional) {
      isOptionalMessage = true;
      Optional<Object> optionalMessage = (Optional<Object>) message;
      if (!optionalMessage.isPresent()) {
        return Optional.empty();
      }

      message = optionalMessage.get();
    }

    if (message instanceof Map) {
      Map<?, ?> map = (Map<?, ?>) message;
      if (map.containsKey(fieldName)) {
        Object mapValue = map.get(fieldName);
        return isOptionalMessage ? Optional.of(mapValue) : mapValue;
      }

      if (isOptionalMessage) {
        return Optional.empty();
      } else {
        throw CelAttributeNotFoundException.forMissingMapKey(fieldName);
      }
    }

    MessageOrBuilder typedMessage = assertFullProtoMessage(message, fieldName);
    FieldDescriptor fieldDescriptor = findField(typedMessage.getDescriptorForType(), fieldName);
    // check whether the field is a wrapper type, then test has and return null
    if (isWrapperType(fieldDescriptor)
        && !fieldDescriptor.isRepeated()
        && !typedMessage.hasField(fieldDescriptor)) {
      return NullValue.NULL_VALUE;
    }
    Object value = typedMessage.getField(fieldDescriptor);
    return protoAdapter.adaptFieldToValue(fieldDescriptor, value).orElse(null);
  }

  /** Adapt object to its message value. */
  @Override
  public Object adapt(String messageName, Object message) {
    if (message instanceof Message) {
      return protoAdapter.adaptProtoToValue((Message) message);
    }

    if (celOptions.evaluateCanonicalTypesToNativeValues() && message instanceof ByteString) {
      return CelByteString.of(((ByteString) message).toByteArray());
    }

    return message;
  }

  @Override
  public Object hasField(Object message, String fieldName) {
    if (message instanceof Optional<?>) {
      Optional<?> optionalMessage = (Optional<?>) message;
      if (!optionalMessage.isPresent()) {
        return false;
      }
      message = optionalMessage.get();
    }

    if (message instanceof Map) {
      Map<?, ?> map = (Map<?, ?>) message;
      return map.containsKey(fieldName);
    }

    MessageOrBuilder typedMessage = assertFullProtoMessage(message, fieldName);
    FieldDescriptor fieldDescriptor = findField(typedMessage.getDescriptorForType(), fieldName);
    if (fieldDescriptor.isRepeated()) {
      return typedMessage.getRepeatedFieldCount(fieldDescriptor) > 0;
    }
    return typedMessage.hasField(fieldDescriptor);
  }

  private FieldDescriptor findField(Descriptor descriptor, String fieldName) {
    if (celOptions.enableJsonFieldNames()) {
      for (FieldDescriptor fd : descriptor.getFields()) {
        if (fd.getJsonName().equals(fieldName)) {
          return fd;
        }
      }
    }

    FieldDescriptor fieldDescriptor = descriptor.findFieldByName(fieldName);
    if (fieldDescriptor != null) {
      return fieldDescriptor;
    }
    fieldDescriptor =
        protoMessageFactory.getDescriptorPool().findExtensionDescriptor(descriptor, fieldName).orElse(null);
    if (fieldDescriptor != null) {
      return fieldDescriptor;
    }


    throw new IllegalArgumentException(
        String.format(
            "field '%s' is not declared in message '%s'", fieldName, descriptor.getFullName()));
  }

  private static MessageOrBuilder assertFullProtoMessage(Object candidate, String fieldName) {
    if (!(candidate instanceof MessageOrBuilder)) {
      // This can happen when the field selection is done on dyn, and it is not a message.
      throw CelAttributeNotFoundException.forFieldResolution(fieldName);
    }
    return (MessageOrBuilder) candidate;
  }

  private static boolean isWrapperType(FieldDescriptor field) {
    if (field.getType() != FieldDescriptor.Type.MESSAGE) {
      return false;
    }
    String fieldTypeName = field.getMessageType().getFullName();

    return CelTypes.isWrapperType(fieldTypeName);
  }
}
