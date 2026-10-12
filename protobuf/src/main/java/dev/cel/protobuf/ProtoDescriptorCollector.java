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

import static com.google.common.collect.ImmutableList.toImmutableList;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Descriptors.FieldDescriptor.JavaType;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.GeneratorNames;
import dev.cel.common.internal.WellKnownProto;
import dev.cel.protobuf.CelLiteDescriptor.FieldLiteDescriptor;
import dev.cel.protobuf.CelLiteDescriptor.FieldLiteDescriptor.EncodingType;
import dev.cel.protobuf.LiteDescriptorCodegenMetadata.FieldLiteDescriptorMetadata;

/**
 * ProtoDescriptorCollector inspects a {@link FileDescriptor} to collect message information into
 * {@link LiteDescriptorCodegenMetadata}. This is later utilized to create an instance of {@code
 * MessageLiteDescriptor}.
 */
final class ProtoDescriptorCollector {

  private static final ImmutableSet<String> FORBIDDEN_ACCESSOR_NAMES =
      ImmutableSet.of(
          "Class",
          "DefaultInstanceForType",
          "ParserForType",
          "SerializedSize",
          "AllFields",
          "DescriptorForType",
          "InitializationErrorString",
          "UnknownFields",
          "CachedSize");

  private final DebugPrinter debugPrinter;

  ImmutableList<LiteDescriptorCodegenMetadata> collectCodegenMetadata(Descriptor descriptor) {
    ImmutableList.Builder<LiteDescriptorCodegenMetadata> descriptorListBuilder =
        ImmutableList.builder();
    ImmutableList<Descriptor> descriptorList =
        collectNested(descriptor).stream()
            // Don't collect WKTs. They are included in the default descriptor pool.
            .filter(d -> !WellKnownProto.getByTypeName(d.getFullName()).isPresent())
            .collect(toImmutableList());

    for (Descriptor messageDescriptor : descriptorList) {
      LiteDescriptorCodegenMetadata.Builder descriptorCodegenBuilder =
          LiteDescriptorCodegenMetadata.newBuilder();
      for (FieldDescriptor fieldDescriptor : messageDescriptor.getFields()) {
        FieldLiteDescriptorMetadata.Builder fieldDescriptorCodegenBuilder =
            FieldLiteDescriptorMetadata.newBuilder()
                .setFieldNumber(fieldDescriptor.getNumber())
                .setFieldName(fieldDescriptor.getName())
                .setIsPacked(fieldDescriptor.isPacked())
                .setJavaType(adaptJavaType(fieldDescriptor.getJavaType()))
                .setProtoFieldType(adaptFieldProtoType(fieldDescriptor.getType()))
                .setGetterExpression(getterExpression(fieldDescriptor));

        switch (fieldDescriptor.getJavaType()) {
          case ENUM:
            fieldDescriptorCodegenBuilder.setFieldProtoTypeName(
                fieldDescriptor.getEnumType().getFullName());
            break;
          case MESSAGE:
            fieldDescriptorCodegenBuilder.setFieldProtoTypeName(
                fieldDescriptor.getMessageType().getFullName());
            break;
          default:
            break;
        }

        if (fieldDescriptor.isMapField()) {
          fieldDescriptorCodegenBuilder.setEncodingType(EncodingType.MAP);
        } else if (fieldDescriptor.isRepeated()) {
          fieldDescriptorCodegenBuilder.setEncodingType(EncodingType.LIST);
        } else {
          fieldDescriptorCodegenBuilder.setEncodingType(EncodingType.SINGULAR);
        }

        descriptorCodegenBuilder.addFieldDescriptor(fieldDescriptorCodegenBuilder.build());

        debugPrinter.print(
            String.format(
                "Collecting message %s, for field %s, type: %s",
                messageDescriptor.getFullName(),
                fieldDescriptor.getFullName(),
                fieldDescriptor.getType()));
      }

      descriptorCodegenBuilder.setProtoTypeName(messageDescriptor.getFullName());
      // Maps are resolved as an actual Java map, and doesn't have a MessageLite.Builder associated.
      if (!messageDescriptor.getOptions().getMapEntry()) {
        String sanitizedJavaClassName =
            GeneratorNames.getBytecodeClassName(messageDescriptor).replace('$', '.');
        descriptorCodegenBuilder.setJavaClassName(sanitizedJavaClassName);
      }

      descriptorListBuilder.add(descriptorCodegenBuilder.build());
    }

    return descriptorListBuilder.build();
  }

  static ProtoDescriptorCollector newInstance(DebugPrinter debugPrinter) {
    return new ProtoDescriptorCollector(debugPrinter);
  }

  /**
   * Returns a Java expression reading {@code field} from a message named {@code m} with its
   * generated getter: null if the field is unset, and open enums as their numbers. Empty for
   * groups, which CEL doesn't support.
   */
  private static String getterExpression(FieldDescriptor field) {
    if (field.getType() == FieldDescriptor.Type.GROUP) {
      return "";
    }
    String name = accessorName(field);
    if (field.isMapField()) {
      FieldDescriptor value = field.getMessageType().findFieldByNumber(2);
      return "m.get" + name + (isOpenEnum(value) ? "Value" : "") + "Map()";
    }
    String suffix = isOpenEnum(field) ? "Value" : "";
    if (field.isRepeated()) {
      return "m.get" + name + suffix + "List()";
    }
    String getter = "m.get" + name + suffix + "()";
    return field.hasPresence()
        ? "m.has" + name + "() ? " + getter + " : null"
        : "nonDefault(" + getter + ")";
  }

  private static boolean isOpenEnum(FieldDescriptor field) {
    return field.getJavaType() == JavaType.ENUM && !field.legacyEnumFieldTreatedAsClosed();
  }

  // Mirrors protobuf's Java generator (compiler/java/context.cc): fields whose accessors would
  // clash with another field's get their numbers appended.
  private static String accessorName(FieldDescriptor field) {
    String name = capitalizedName(field);
    for (FieldDescriptor other : field.getContainingType().getFields()) {
      if (other != field) {
        String otherName = capitalizedName(other);
        if (clashesOneWay(field, name, other, otherName)
            || clashesOneWay(other, otherName, field, name)) {
          return name + field.getNumber();
        }
      }
    }
    return name;
  }

  // Whether other's getter is named like field's; or, if only field is repeated, like field's
  // getXCount() or getXList(); or, if field is an open enum, like its getXValue().
  private static boolean clashesOneWay(
      FieldDescriptor field, String name, FieldDescriptor other, String otherName) {
    return otherName.equals(name)
        || (field.isRepeated()
            && !other.isRepeated()
            && (otherName.equals(name + "Count") || otherName.equals(name + "List")))
        || (isOpenEnum(field) && otherName.equals(name + "Value"));
  }

  // Mirrors protobuf's Java generator (compiler/java/names.cc): underscores and digits capitalize
  // the next letter, and names clashing with inherited methods get a trailing underscore.
  private static String capitalizedName(FieldDescriptor field) {
    String fieldName = field.getName();
    StringBuilder name = new StringBuilder(fieldName.length() + 1);
    boolean capitalizeNext = true;
    for (char c : fieldName.toCharArray()) {
      if (c == '_') {
        capitalizeNext = true;
      } else {
        name.append(capitalizeNext ? Character.toUpperCase(c) : c);
        capitalizeNext = Character.isDigit(c);
      }
    }
    if (FORBIDDEN_ACCESSOR_NAMES.contains(name.toString())) {
      name.append('_');
    }
    return name.toString();
  }

  private static FieldLiteDescriptor.Type adaptFieldProtoType(FieldDescriptor.Type type) {
    switch (type) {
      case DOUBLE:
        return FieldLiteDescriptor.Type.DOUBLE;
      case FLOAT:
        return FieldLiteDescriptor.Type.FLOAT;
      case INT64:
        return FieldLiteDescriptor.Type.INT64;
      case UINT64:
        return FieldLiteDescriptor.Type.UINT64;
      case INT32:
        return FieldLiteDescriptor.Type.INT32;
      case FIXED64:
        return FieldLiteDescriptor.Type.FIXED64;
      case FIXED32:
        return FieldLiteDescriptor.Type.FIXED32;
      case BOOL:
        return FieldLiteDescriptor.Type.BOOL;
      case STRING:
        return FieldLiteDescriptor.Type.STRING;
      case MESSAGE:
        return FieldLiteDescriptor.Type.MESSAGE;
      case BYTES:
        return FieldLiteDescriptor.Type.BYTES;
      case UINT32:
        return FieldLiteDescriptor.Type.UINT32;
      case ENUM:
        return FieldLiteDescriptor.Type.ENUM;
      case SFIXED32:
        return FieldLiteDescriptor.Type.SFIXED32;
      case SFIXED64:
        return FieldLiteDescriptor.Type.SFIXED64;
      case SINT32:
        return FieldLiteDescriptor.Type.SINT32;
      case SINT64:
        return FieldLiteDescriptor.Type.SINT64;
      case GROUP:
        return FieldLiteDescriptor.Type.GROUP;
    }

    throw new IllegalArgumentException("Unknown Type: " + type);
  }

  private static FieldLiteDescriptor.JavaType adaptJavaType(JavaType javaType) {
    switch (javaType) {
      case INT:
        return FieldLiteDescriptor.JavaType.INT;
      case LONG:
        return FieldLiteDescriptor.JavaType.LONG;
      case FLOAT:
        return FieldLiteDescriptor.JavaType.FLOAT;
      case DOUBLE:
        return FieldLiteDescriptor.JavaType.DOUBLE;
      case BOOLEAN:
        return FieldLiteDescriptor.JavaType.BOOLEAN;
      case STRING:
        return FieldLiteDescriptor.JavaType.STRING;
      case BYTE_STRING:
        return FieldLiteDescriptor.JavaType.BYTE_STRING;
      case ENUM:
        return FieldLiteDescriptor.JavaType.ENUM;
      case MESSAGE:
        return FieldLiteDescriptor.JavaType.MESSAGE;
    }

    throw new IllegalArgumentException("Unknown JavaType: " + javaType);
  }

  private static ImmutableSet<Descriptor> collectNested(Descriptor descriptor) {
    ImmutableSet.Builder<Descriptor> builder = ImmutableSet.builder();
    collectNested(builder, descriptor);
    return builder.build();
  }

  private static void collectNested(
      ImmutableSet.Builder<Descriptor> builder, Descriptor descriptor) {
    builder.add(descriptor);
    for (Descriptor nested : descriptor.getNestedTypes()) {
      collectNested(builder, nested);
    }
  }

  private ProtoDescriptorCollector(DebugPrinter debugPrinter) {
    this.debugPrinter = debugPrinter;
  }
}
