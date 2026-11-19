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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.errorprone.annotations.Immutable;
import com.google.protobuf.Any;
import com.google.protobuf.BoolValue;
import com.google.protobuf.BytesValue;
import com.google.protobuf.DoubleValue;
import com.google.protobuf.Duration;
import com.google.protobuf.Empty;
import com.google.protobuf.FieldMask;
import com.google.protobuf.FloatValue;
import com.google.protobuf.Int32Value;
import com.google.protobuf.Int64Value;
import com.google.protobuf.ListValue;
import com.google.protobuf.MessageLite;
import com.google.protobuf.StringValue;
import com.google.protobuf.Struct;
import com.google.protobuf.Timestamp;
import com.google.protobuf.UInt32Value;
import com.google.protobuf.UInt64Value;
import com.google.protobuf.Value;
import dev.cel.common.annotations.Internal;
import dev.cel.protobuf.CelLiteDescriptor;
import dev.cel.protobuf.CelLiteDescriptor.FieldLiteDescriptor;
import dev.cel.protobuf.CelLiteDescriptor.FieldLiteDescriptor.EncodingType;
import dev.cel.protobuf.CelLiteDescriptor.MessageLiteDescriptor;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.function.Supplier;

/** Descriptor pool for {@link CelLiteDescriptor}s. */
@Immutable
@Internal
public final class DefaultLiteDescriptorPool implements CelLiteDescriptorPool {
  private static final String STRUCT_FIELDS_ENTRY = "google.protobuf.Struct.FieldsEntry";

  private final ImmutableMap<String, MessageLiteDescriptor> protoFqnToMessageInfo;
  private final ImmutableMap<Class<?>, MessageLiteDescriptor> classToMessageInfo;

  public static DefaultLiteDescriptorPool newInstance(CelLiteDescriptor... descriptors) {
    return newInstance(ImmutableSet.copyOf(descriptors));
  }

  public static DefaultLiteDescriptorPool newInstance(ImmutableSet<CelLiteDescriptor> descriptors) {
    return new DefaultLiteDescriptorPool(descriptors);
  }

  @Override
  public Optional<MessageLiteDescriptor> findDescriptor(MessageLite messageLite) {
    return Optional.ofNullable(classToMessageInfo.get(messageLite.getClass()));
  }

  @Override
  public Optional<MessageLiteDescriptor> findDescriptor(String protoTypeName) {
    return Optional.ofNullable(protoFqnToMessageInfo.get(protoTypeName));
  }

  @Override
  public MessageLiteDescriptor getDescriptorOrThrow(String protoTypeName) {
    return findDescriptor(protoTypeName)
        .orElseThrow(
            () -> new NoSuchElementException("Could not find a descriptor for: " + protoTypeName));
  }

  private static MessageLiteDescriptor newMessageInfo(WellKnownProto wellKnownProto) {
    ImmutableList.Builder<FieldLiteDescriptor> fieldDescriptors = ImmutableList.builder();
    Supplier<MessageLite.Builder> messageBuilder = null;
    switch (wellKnownProto) {
      case ANY_VALUE:
        messageBuilder = Any::newBuilder;
        fieldDescriptors
            .add(
                newPrimitiveFieldDescriptor(
                    1,
                    "type_url",
                    FieldLiteDescriptor.Type.STRING))
            .add(
                newPrimitiveFieldDescriptor(
                    2,
                    "value",
                    FieldLiteDescriptor.Type.BYTES));
        break;
      case FIELD_MASK:
        messageBuilder = FieldMask::newBuilder;
        fieldDescriptors.add(
            new FieldLiteDescriptor(
                /* fieldNumber= */ 1,
                /* fieldName= */ "paths",
                /* encodingType= */ EncodingType.LIST,
                /* protoFieldType= */ FieldLiteDescriptor.Type.STRING,
                /* isPacked= */ false,
                /* fieldProtoTypeName= */ ""));
        break;
      case BOOL_VALUE:
        messageBuilder = BoolValue::newBuilder;
        fieldDescriptors.add(
            newPrimitiveFieldDescriptor(1, "value", FieldLiteDescriptor.Type.BOOL));
        break;
      case BYTES_VALUE:
        messageBuilder = BytesValue::newBuilder;
        fieldDescriptors.add(
            newPrimitiveFieldDescriptor(
                1,
                "value",
                FieldLiteDescriptor.Type.BYTES));
        break;
      case DOUBLE_VALUE:
        messageBuilder = DoubleValue::newBuilder;
        fieldDescriptors.add(
            newPrimitiveFieldDescriptor(1, "value", FieldLiteDescriptor.Type.DOUBLE));
        break;
      case FLOAT_VALUE:
        messageBuilder = FloatValue::newBuilder;
        fieldDescriptors.add(
            newPrimitiveFieldDescriptor(1, "value", FieldLiteDescriptor.Type.FLOAT));
        break;
      case INT32_VALUE:
        messageBuilder = Int32Value::newBuilder;
        fieldDescriptors.add(
            newPrimitiveFieldDescriptor(1, "value", FieldLiteDescriptor.Type.INT32));
        break;
      case INT64_VALUE:
        messageBuilder = Int64Value::newBuilder;
        fieldDescriptors.add(
            newPrimitiveFieldDescriptor(1, "value", FieldLiteDescriptor.Type.INT64));
        break;
      case STRING_VALUE:
        messageBuilder = StringValue::newBuilder;
        fieldDescriptors.add(
            newPrimitiveFieldDescriptor(1, "value", FieldLiteDescriptor.Type.STRING));
        break;
      case UINT32_VALUE:
        messageBuilder = UInt32Value::newBuilder;
        fieldDescriptors.add(
            newPrimitiveFieldDescriptor(1, "value", FieldLiteDescriptor.Type.UINT32));
        break;
      case UINT64_VALUE:
        messageBuilder = UInt64Value::newBuilder;
        fieldDescriptors.add(
            newPrimitiveFieldDescriptor(1, "value", FieldLiteDescriptor.Type.UINT64));
        break;
      case JSON_STRUCT_VALUE:
        messageBuilder = Struct::newBuilder;
        fieldDescriptors.add(
            new FieldLiteDescriptor(
                /* fieldNumber= */ 1,
                /* fieldName= */ "fields",
                /* encodingType= */ EncodingType.MAP,
                /* protoFieldType= */ FieldLiteDescriptor.Type.MESSAGE,
                /* isPacked= */ false,
                /* fieldProtoTypeName= */ STRUCT_FIELDS_ENTRY));
        break;
      case JSON_VALUE:
        messageBuilder = Value::newBuilder;
        fieldDescriptors.add(
            new FieldLiteDescriptor(
                /* fieldNumber= */ 1,
                /* fieldName= */ "null_value",
                /* encodingType= */ EncodingType.SINGULAR,
                /* protoFieldType= */ FieldLiteDescriptor.Type.ENUM,
                /* isPacked= */ false,
                /* fieldProtoTypeName= */ "google.protobuf.NullValue"));
        fieldDescriptors.add(
            new FieldLiteDescriptor(
                /* fieldNumber= */ 2,
                /* fieldName= */ "number_value",
                /* encodingType= */ EncodingType.SINGULAR,
                /* protoFieldType= */ FieldLiteDescriptor.Type.DOUBLE,
                /* isPacked= */ false,
                /* fieldProtoTypeName= */ ""));
        fieldDescriptors.add(
            new FieldLiteDescriptor(
                /* fieldNumber= */ 3,
                /* fieldName= */ "string_value",
                /* encodingType= */ EncodingType.SINGULAR,
                /* protoFieldType= */ FieldLiteDescriptor.Type.STRING,
                /* isPacked= */ false,
                /* fieldProtoTypeName= */ ""));
        fieldDescriptors.add(
            new FieldLiteDescriptor(
                /* fieldNumber= */ 4,
                /* fieldName= */ "bool_value",
                /* encodingType= */ EncodingType.SINGULAR,
                /* protoFieldType= */ FieldLiteDescriptor.Type.BOOL,
                /* isPacked= */ false,
                /* fieldProtoTypeName= */ ""));
        fieldDescriptors.add(
            new FieldLiteDescriptor(
                /* fieldNumber= */ 5,
                /* fieldName= */ "struct_value",
                /* encodingType= */ EncodingType.SINGULAR,
                /* protoFieldType= */ FieldLiteDescriptor.Type.MESSAGE,
                /* isPacked= */ false,
                /* fieldProtoTypeName= */ "google.protobuf.Struct"));
        fieldDescriptors.add(
            new FieldLiteDescriptor(
                /* fieldNumber= */ 6,
                /* fieldName= */ "list_value",
                /* encodingType= */ EncodingType.SINGULAR,
                /* protoFieldType= */ FieldLiteDescriptor.Type.MESSAGE,
                /* isPacked= */ false,
                /* fieldProtoTypeName= */ "google.protobuf.ListValue"));
        break;
      case JSON_LIST_VALUE:
        messageBuilder = ListValue::newBuilder;
        fieldDescriptors.add(
            new FieldLiteDescriptor(
                /* fieldNumber= */ 1,
                /* fieldName= */ "values",
                /* encodingType= */ EncodingType.LIST,
                /* protoFieldType= */ FieldLiteDescriptor.Type.MESSAGE,
                /* isPacked= */ false,
                /* fieldProtoTypeName= */ "google.protobuf.Value"));
        break;
      case DURATION:
        messageBuilder = Duration::newBuilder;
        fieldDescriptors
            .add(newPrimitiveFieldDescriptor(1, "seconds", FieldLiteDescriptor.Type.INT64))
            .add(newPrimitiveFieldDescriptor(2, "nanos", FieldLiteDescriptor.Type.INT32));
        break;
      case TIMESTAMP:
        messageBuilder = Timestamp::newBuilder;
        fieldDescriptors
            .add(newPrimitiveFieldDescriptor(1, "seconds", FieldLiteDescriptor.Type.INT64))
            .add(newPrimitiveFieldDescriptor(2, "nanos", FieldLiteDescriptor.Type.INT32));
        break;
      case EMPTY:
        messageBuilder = Empty::newBuilder;
    }

    return new MessageLiteDescriptor(
        wellKnownProto.typeName(), fieldDescriptors.build(), messageBuilder);
  }

  private static FieldLiteDescriptor newPrimitiveFieldDescriptor(
      int fieldNumber,
      String fieldName,
      FieldLiteDescriptor.Type protoFieldType) {
    return new FieldLiteDescriptor(
        /* fieldNumber= */ fieldNumber,
        /* fieldName= */ fieldName,
        /* encodingType= */ EncodingType.SINGULAR,
        /* protoFieldType= */ protoFieldType,
        /* isPacked= */ false,
        /* fieldProtoTypeName= */ "");
  }

  private DefaultLiteDescriptorPool(ImmutableSet<CelLiteDescriptor> descriptors) {
    ImmutableMap.Builder<String, MessageLiteDescriptor> protoFqnMapBuilder = ImmutableMap.builder();
    ImmutableMap.Builder<Class<?>, MessageLiteDescriptor> classMapBuilder = ImmutableMap.builder();
    for (WellKnownProto wellKnownProto : WellKnownProto.values()) {
      MessageLiteDescriptor wktMessageInfo = newMessageInfo(wellKnownProto);
      protoFqnMapBuilder.put(wellKnownProto.typeName(), wktMessageInfo);
      classMapBuilder.put(wellKnownProto.messageClass(), wktMessageInfo);
    }
    // Struct's map entry, which `google.protobuf.Struct{fields: ...}` literals are encoded with.
    protoFqnMapBuilder.put(
        STRUCT_FIELDS_ENTRY,
        CelLiteDescriptor.mapEntry(
            /* fullName= */ STRUCT_FIELDS_ENTRY,
            /* keyType= */ FieldLiteDescriptor.Type.STRING,
            /* valueType= */ FieldLiteDescriptor.Type.MESSAGE,
            /* valueTypeName= */ WellKnownProto.JSON_VALUE.typeName()));

    for (CelLiteDescriptor descriptor : descriptors) {
      protoFqnMapBuilder.putAll(descriptor.getProtoTypeNamesToDescriptors());

      for (MessageLiteDescriptor messageLiteDescriptor :
          descriptor.getProtoTypeNamesToDescriptors().values()) {
        // Note: message builder is null for proto maps.
        Optional.ofNullable(messageLiteDescriptor.newMessageBuilder())
            .ifPresent(
                builder ->
                    classMapBuilder.put(
                        builder.getDefaultInstanceForType().getClass(), messageLiteDescriptor));
      }
    }

    this.protoFqnToMessageInfo = protoFqnMapBuilder.buildOrThrow();
    this.classToMessageInfo = classMapBuilder.buildOrThrow();
  }
}
