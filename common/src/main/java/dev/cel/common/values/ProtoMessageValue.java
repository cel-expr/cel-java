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

package dev.cel.common.values;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.auto.value.AutoValue;
import com.google.errorprone.annotations.Immutable;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Message;
import dev.cel.common.internal.CelDescriptorPool;
import dev.cel.common.types.CelType;
import dev.cel.common.types.StructTypeReference;
import java.util.Optional;

/** ProtoMessageValue is a struct value with protobuf support. */
@AutoValue
@Immutable
public abstract class ProtoMessageValue extends StructValue<String, Message>
    implements OptimizedSelectable {

  @Override
  public abstract Message value();

  @Override
  public abstract CelType celType();

  abstract CelDescriptorPool celDescriptorPool();

  abstract ProtoCelValueConverter protoCelValueConverter();

  abstract boolean enableJsonFieldNames();

  @Override
  public boolean isZeroValue() {
    return value().getDefaultInstanceForType().equals(value());
  }

  @Override
  public Object select(String field) {
    FieldDescriptor fieldDescriptor =
        findField(celDescriptorPool(), value().getDescriptorForType(), field);

    return protoCelValueConverter().fromProtoMessageFieldToCelValue(value(), fieldDescriptor);
  }

  @Override
  public Optional<Object> find(String field) {
    FieldDescriptor fieldDescriptor =
        findField(celDescriptorPool(), value().getDescriptorForType(), field);

    return findFieldValue(fieldDescriptor);
  }

  @Override
  public Object selectByFieldNumber(SelectField field) {
    FieldDescriptor fieldDescriptor = findFieldByNumber(value().getDescriptorForType(), field);

    return protoCelValueConverter().fromProtoMessageFieldToCelValue(value(), fieldDescriptor);
  }

  @Override
  public boolean hasFieldByNumber(SelectField field) {
    FieldDescriptor fieldDescriptor = findFieldByNumber(value().getDescriptorForType(), field);

    return isFieldPresent(fieldDescriptor);
  }

  @Override
  public Optional<Object> findByFieldNumber(SelectField field) {
    FieldDescriptor fieldDescriptor = findFieldByNumber(value().getDescriptorForType(), field);

    return findFieldValue(fieldDescriptor);
  }

  public static ProtoMessageValue create(
      Message value,
      CelDescriptorPool celDescriptorPool,
      ProtoCelValueConverter protoCelValueConverter,
      boolean enableJsonFieldNames) {
    checkNotNull(value);
    checkNotNull(celDescriptorPool);
    checkNotNull(protoCelValueConverter);
    return new AutoValue_ProtoMessageValue(
        value,
        StructTypeReference.create(value.getDescriptorForType().getFullName()),
        celDescriptorPool,
        protoCelValueConverter,
        enableJsonFieldNames);
  }

  private Optional<Object> findFieldValue(FieldDescriptor fieldDescriptor) {
    if (!isFieldPresent(fieldDescriptor)) {
      return Optional.empty();
    }

    return Optional.of(
        protoCelValueConverter().fromProtoMessageFieldToCelValue(value(), fieldDescriptor));
  }

  private boolean isFieldPresent(FieldDescriptor fieldDescriptor) {
    // Selecting a field on a protobuf message yields a default value even if the field is not
    // declared. Therefore, we must exhaustively test whether they are actually declared.
    if (fieldDescriptor.isRepeated()) {
      return value().getRepeatedFieldCount(fieldDescriptor) > 0;
    }
    return value().hasField(fieldDescriptor);
  }

  private static FieldDescriptor findFieldByNumber(Descriptor descriptor, SelectField field) {
    FieldDescriptor fieldDescriptor = descriptor.findFieldByNumber(field.fieldNumber());
    if (fieldDescriptor != null) {
      return fieldDescriptor;
    }

    throw new IllegalArgumentException(
        String.format(
            "field '%s' (number %d) is not declared in message '%s'",
            field.fieldName(), field.fieldNumber(), descriptor.getFullName()));
  }

  private FieldDescriptor findField(
      CelDescriptorPool celDescriptorPool, Descriptor descriptor, String fieldName) {
    if (enableJsonFieldNames()) {
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

    return celDescriptorPool
        .findExtensionDescriptor(descriptor, fieldName)
        .orElseThrow(
            () ->
                new IllegalArgumentException(
                    String.format(
                        "field '%s' is not declared in message '%s'",
                        fieldName, descriptor.getFullName())));
  }

  ProtoMessageValue() {}
}
