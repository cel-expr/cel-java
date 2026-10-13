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

package dev.cel.protobuf;

import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableList;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import dev.cel.common.annotations.Internal;
import dev.cel.protobuf.CelLiteDescriptor.FieldLiteDescriptor;
import dev.cel.protobuf.CelLiteDescriptor.FieldLiteDescriptor.EncodingType;
import org.jspecify.annotations.Nullable;

/**
 * LiteDescriptorCodegenMetadata holds metadata collected from a full protobuf descriptor pertinent
 * for generating a {@link CelLiteDescriptor}.
 *
 * <p>The class properties here are almost identical to CelLiteDescriptor, except it contains
 * extraneous information such as the fully qualified class names to support codegen, which do not
 * need to be present on a CelLiteDescriptor instance.
 *
 * <p>Note: Properties must be of primitive types.
 *
 * <p>Note: JavaBeans prefix (e.g: getFoo) is required for compatibility with freemarker.
 *
 * <p>CEL Library Internals. Do Not Use.
 */
@AutoValue
@Internal
public abstract class LiteDescriptorCodegenMetadata {

  public abstract String getProtoTypeName();

  public abstract ImmutableList<FieldLiteDescriptorMetadata> getFieldDescriptors();

  // @Nullable note: A java class name is not populated for maps, even though it behaves like a
  // message.
  public abstract @Nullable String getJavaClassName();

  @AutoValue.Builder
  abstract static class Builder {

    abstract Builder setProtoTypeName(String protoTypeName);

    abstract Builder setJavaClassName(String javaClassName);

    abstract ImmutableList.Builder<FieldLiteDescriptorMetadata> fieldDescriptorsBuilder();

    @CanIgnoreReturnValue
    Builder addFieldDescriptor(FieldLiteDescriptorMetadata fieldDescriptor) {
      this.fieldDescriptorsBuilder().add(fieldDescriptor);
      return this;
    }

    abstract LiteDescriptorCodegenMetadata build();
  }

  static Builder newBuilder() {
    return new AutoValue_LiteDescriptorCodegenMetadata.Builder();
  }

  /**
   * Metadata collected from a protobuf message's FieldDescriptor. This is used to codegen {@link
   * FieldLiteDescriptor}.
   */
  @AutoValue
  public abstract static class FieldLiteDescriptorMetadata {

    public abstract int getFieldNumber();

    public abstract String getFieldName();

    // Fully-qualified name to the Java Type enumeration (ex:
    // dev.cel.protobuf.CelLiteDescriptor.FieldLiteDescriptor.INT)
    public String getJavaTypeEnumName() {
      return getFullyQualifiedEnumName(getJavaType());
    }

    // Fully-qualified name to the EncodingType enumeration (ex:
    // dev.cel.protobuf.CelLiteDescriptor.FieldLiteDescriptor.SINGULAR)
    public String getEncodingTypeEnumName() {
      return getFullyQualifiedEnumName(getEncodingType());
    }

    // Fully-qualified name to the Proto Type enumeration (ex:
    // dev.cel.protobuf.CelLiteDescriptor.FieldLiteDescriptor.INT)
    public String getProtoFieldTypeEnumName() {
      return getFullyQualifiedEnumName(getProtoFieldType());
    }

    public abstract boolean getIsPacked();

    public abstract String getFieldProtoTypeName();

    // Java expression reading this field from a message named m with its generated getter, or
    // empty if the field has no getter.
    public abstract String getGetterExpression();

    abstract FieldLiteDescriptor.JavaType getJavaType();

    abstract FieldLiteDescriptor.Type getProtoFieldType();

    abstract EncodingType getEncodingType();

    private static String getFullyQualifiedEnumName(Object enumValue) {
      String enumClassName = enumValue.getClass().getName();
      return (enumClassName + "." + enumValue).replace('$', '.');
    }

    @AutoValue.Builder
    abstract static class Builder {

      abstract Builder setFieldNumber(int fieldNumber);

      abstract Builder setFieldName(String fieldName);

      abstract Builder setJavaType(FieldLiteDescriptor.JavaType javaTypeEnum);

      abstract Builder setEncodingType(EncodingType encodingTypeEnum);

      abstract Builder setProtoFieldType(FieldLiteDescriptor.Type protoFieldTypeEnum);

      abstract Builder setIsPacked(boolean isPacked);

      abstract Builder setFieldProtoTypeName(String fieldProtoTypeName);

      abstract Builder setGetterExpression(String getterExpression);

      abstract FieldLiteDescriptorMetadata build();
    }

    static FieldLiteDescriptorMetadata.Builder newBuilder() {
      return new AutoValue_LiteDescriptorCodegenMetadata_FieldLiteDescriptorMetadata.Builder()
          .setFieldProtoTypeName("");
    }
  }
}
