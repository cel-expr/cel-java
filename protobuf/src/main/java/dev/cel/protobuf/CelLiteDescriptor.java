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

import static java.lang.Math.ceil;

import com.google.errorprone.annotations.Immutable;
import com.google.protobuf.ByteString;
import com.google.protobuf.MessageLite;
import com.google.protobuf.WireFormat;
import dev.cel.common.annotations.Internal;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * Base class for code generated CEL lite descriptors to extend from.
 *
 * <p>CEL Library Internals. Do Not Use.
 */
@Internal
@Immutable
public abstract class CelLiteDescriptor {
  private static final Set<Object> defaultScalarValues =
      Collections.unmodifiableSet(
          new HashSet<>(Arrays.asList(false, 0, 0L, 0.0f, 0.0d, "", ByteString.EMPTY)));

  // Map entries have no generated class to build.
  private static final Supplier<MessageLite.Builder> NO_BUILDER = () -> null;

  @SuppressWarnings("Immutable") // Copied to unmodifiable map
  private final Map<String, MessageLiteDescriptor> protoFqnToDescriptors;

  private final String version;

  public Map<String, MessageLiteDescriptor> getProtoTypeNamesToDescriptors() {
    return protoFqnToDescriptors;
  }

  /** Retrieves the CEL-Java version this descriptor was generated with */
  public String getVersion() {
    return version;
  }

  /**
   * Contains a collection of classes which describe protobuf messagelite types.
   *
   * <p>CEL Library Internals. Do Not Use.
   */
  @Internal
  @Immutable
  public static final class MessageLiteDescriptor {
    private final String fullyQualifiedProtoTypeName;

    @SuppressWarnings("Immutable") // Copied to an unmodifiable list
    private final List<FieldLiteDescriptor> fieldLiteDescriptors;

    @SuppressWarnings("Immutable") // Copied to an unmodifiable map
    private final Map<String, FieldLiteDescriptor> fieldNameToFieldDescriptors;

    @SuppressWarnings("Immutable") // Copied to an unmodifiable map
    private final Map<Integer, FieldLiteDescriptor> fieldNumberToFieldDescriptors;

    @SuppressWarnings("Immutable") // Does not alter the descriptor content
    private final Supplier<MessageLite.Builder> messageBuilderSupplier;

    // The generated descriptor declaring this message, and this message's position in it.
    @SuppressWarnings("Immutable") // Set once, before the descriptor is published
    private @Nullable CelLiteDescriptor owner;

    @SuppressWarnings("Immutable") // Set once, before the descriptor is published
    private int index;

    public String getProtoTypeName() {
      return fullyQualifiedProtoTypeName;
    }

    public List<FieldLiteDescriptor> getFieldDescriptors() {
      return fieldLiteDescriptors;
    }

    public @Nullable FieldLiteDescriptor findByFieldNumber(int fieldNumber) {
      return fieldNumberToFieldDescriptors.get(fieldNumber);
    }

    public @Nullable FieldLiteDescriptor findByFieldName(String fieldName) {
      return fieldNameToFieldDescriptors.get(fieldName);
    }

    public FieldLiteDescriptor getByFieldNameOrThrow(String fieldName) {
      return Objects.requireNonNull(fieldNameToFieldDescriptors.get(fieldName));
    }

    public FieldLiteDescriptor getByFieldNumberOrThrow(int fieldNumber) {
      FieldLiteDescriptor fd = fieldNumberToFieldDescriptors.get(fieldNumber);
      if (fd == null) {
        throw new NoSuchElementException("Could not find field number: " + fieldNumber);
      }
      return fd;
    }

    /** Gets the builder for the message. Returns null for maps. */
    public MessageLite.Builder newMessageBuilder() {
      return messageBuilderSupplier.get();
    }

    /** Whether this describes a map field's entry, which has no message builder. */
    public boolean isMapEntry() {
      return messageBuilderSupplier.equals(NO_BUILDER);
    }

    /** Whether {@link #getFieldValue} reads this message's fields with their generated getters. */
    public boolean hasGetters() {
      return owner != null;
    }

    /**
     * Reads field {@code fieldNumber} of {@code message}, an instance of this message type, with
     * its generated getter: the Java value (an open enum's number; a repeated or map field's list
     * or map, possibly empty), or null if a singular field is unset. Requires {@link #hasGetters}
     * and one of this message type's fields; throws for groups, which CEL doesn't support.
     */
    public @Nullable Object getFieldValue(MessageLite message, int fieldNumber) {
      return Objects.requireNonNull(owner).getFieldValue(index, message, fieldNumber);
    }

    /**
     * CEL Library Internals. Do not use.
     *
     * <p>Public visibility due to codegen.
     */
    @Internal
    public MessageLiteDescriptor(
        String fullyQualifiedProtoTypeName,
        List<FieldLiteDescriptor> fieldLiteDescriptors,
        Supplier<MessageLite.Builder> messageBuilderSupplier) {
      this.fullyQualifiedProtoTypeName = Objects.requireNonNull(fullyQualifiedProtoTypeName);
      // This is a cheap operation. View over the existing map with mutators disabled.
      this.fieldLiteDescriptors =
          Collections.unmodifiableList(Objects.requireNonNull(fieldLiteDescriptors));
      this.messageBuilderSupplier = Objects.requireNonNull(messageBuilderSupplier);

      Map<String, FieldLiteDescriptor> fieldNameMap =
          new HashMap<>(getMapInitialCapacity(fieldLiteDescriptors.size()));
      Map<Integer, FieldLiteDescriptor> fieldNumberMap =
          new HashMap<>(getMapInitialCapacity(fieldLiteDescriptors.size()));
      for (FieldLiteDescriptor fd : fieldLiteDescriptors) {
        fieldNameMap.put(fd.fieldName, fd);
        fieldNumberMap.put(fd.fieldNumber, fd);
      }
      this.fieldNameToFieldDescriptors = Collections.unmodifiableMap(fieldNameMap);
      this.fieldNumberToFieldDescriptors = Collections.unmodifiableMap(fieldNumberMap);
    }
  }

  /**
   * Describes a field of a protobuf messagelite type.
   *
   * <p>CEL Library Internals. Do Not Use.
   */
  @Internal
  @Immutable
  public static final class FieldLiteDescriptor {
    private final int fieldNumber;
    private final String fieldName;
    private final String fieldProtoTypeName;
    private final Type protoFieldType;
    private final EncodingType encodingType;
    private final boolean isPacked;

    /**
     * Enumeration of encoding type. This describes how CEL should deserialize the encoded message
     * bytes using protobuf's wire format. This is analogous to the following from field
     * descriptors:
     *
     * <ul>
     *   <li>LIST: Repeated Field
     *   <li>MAP: Map Field
     *   <li>SINGULAR: Neither of above (scalars, messages)
     * </ul>
     */
    public enum EncodingType {
      SINGULAR,
      LIST,
      MAP
    }

    /**
     * Enumeration of the java type.
     *
     * <p>This is exactly the same as com.google.protobuf.Descriptors#JavaType
     */
    public enum JavaType {
      INT,
      LONG,
      FLOAT,
      DOUBLE,
      BOOLEAN,
      STRING,
      BYTE_STRING,
      ENUM,
      MESSAGE
    }

    /**
     * Enumeration of the protobuf type.
     *
     * <p>This is exactly the same as com.google.protobuf.Descriptors#Type
     */
    public enum Type {
      DOUBLE(1, WireFormat.FieldType.DOUBLE),
      FLOAT(2, WireFormat.FieldType.FLOAT),
      INT64(3, WireFormat.FieldType.INT64),
      UINT64(4, WireFormat.FieldType.UINT64),
      INT32(5, WireFormat.FieldType.INT32),
      FIXED64(6, WireFormat.FieldType.FIXED64),
      FIXED32(7, WireFormat.FieldType.FIXED32),
      BOOL(8, WireFormat.FieldType.BOOL),
      STRING(9, WireFormat.FieldType.STRING),
      GROUP(10, WireFormat.FieldType.GROUP),
      MESSAGE(11, WireFormat.FieldType.MESSAGE),
      BYTES(12, WireFormat.FieldType.BYTES),
      UINT32(13, WireFormat.FieldType.UINT32),
      ENUM(14, WireFormat.FieldType.ENUM),
      SFIXED32(15, WireFormat.FieldType.SFIXED32),
      SFIXED64(16, WireFormat.FieldType.SFIXED64),
      SINT32(17, WireFormat.FieldType.SINT32),
      SINT64(18, WireFormat.FieldType.SINT64);

      private final int number;
      private final WireFormat.FieldType wireFormatFieldType;
      private final JavaType javaType;

      /** Gets the type number corresponding to {@code FieldDescriptorProto.Type#getNumber()}. */
      public int getNumber() {
        return number;
      }

      /** Converts this type to the corresponding {@link WireFormat.FieldType}. */
      public WireFormat.FieldType toWireFormatFieldType() {
        return wireFormatFieldType;
      }

      /**
       * Returns the {@link Type} for the specified protobuf type number.
       *
       * @throws IllegalArgumentException if the number does not correspond to a valid protobuf
       *     type.
       */
      public static Type forNumber(int number) {
        switch (number) {
          case 1:
            return DOUBLE;
          case 2:
            return FLOAT;
          case 3:
            return INT64;
          case 4:
            return UINT64;
          case 5:
            return INT32;
          case 6:
            return FIXED64;
          case 7:
            return FIXED32;
          case 8:
            return BOOL;
          case 9:
            return STRING;
          case 10:
            return GROUP;
          case 11:
            return MESSAGE;
          case 12:
            return BYTES;
          case 13:
            return UINT32;
          case 14:
            return ENUM;
          case 15:
            return SFIXED32;
          case 16:
            return SFIXED64;
          case 17:
            return SINT32;
          case 18:
            return SINT64;
          default:
            throw new IllegalArgumentException("Unsupported proto type code: " + number);
        }
      }

      private Type(int number, WireFormat.FieldType wireFormatFieldType) {
        this.number = number;
        this.wireFormatFieldType = Objects.requireNonNull(wireFormatFieldType);
        // JavaType's constants match WireFormat.JavaType's by name.
        this.javaType = JavaType.valueOf(wireFormatFieldType.getJavaType().name());
      }
    }

    public int getFieldNumber() {
      return fieldNumber;
    }

    public String getFieldName() {
      return fieldName;
    }

    /**
     * Gets the field's java type.
     *
     * <p>This is exactly the same as com.google.protobuf.Descriptors#JavaType
     */
    public JavaType getJavaType() {
      return protoFieldType.javaType;
    }

    public EncodingType getEncodingType() {
      return encodingType;
    }

    /**
     * Gets the field's protobuf type.
     *
     * <p>This is exactly the same as com.google.protobuf.Descriptors#Type
     */
    public Type getProtoFieldType() {
      return protoFieldType;
    }

    /** Checks whether the repeated field is packed. */
    public boolean getIsPacked() {
      return isPacked;
    }

    /**
     * Gets the fully qualified protobuf type name for the field, including its package name (ex:
     * cel.expr.conformance.proto3.TestAllTypes.SingleStringWrapper). Returns an empty string for
     * primitives.
     */
    public String getFieldProtoTypeName() {
      return fieldProtoTypeName;
    }

    /**
     * Must be public, used for codegen only. Do not use.
     *
     * @param fieldNumber Field index
     * @param fieldName Name of the field
     * @param encodingType Describes whether the field is a singular (primitives or messages), list
     *     or a map with respect to CEL.
     * @param protoFieldType Protobuf Field Type (ex: INT32, SINT32, GROUP, MESSAGE... see
     *     com.google.protobuf.Descriptors#Type)
     * @param fieldProtoTypeName Fully qualified protobuf type name for the field. Empty if the
     *     field is a primitive.
     */
    @Internal
    public FieldLiteDescriptor(
        int fieldNumber,
        String fieldName,
        EncodingType encodingType, // LIST, MAP, SINGULAR
        Type protoFieldType, // INT32, SINT32, GROUP, MESSAGE... (See Descriptors#Type)
        boolean isPacked,
        String fieldProtoTypeName) {
      this.fieldNumber = fieldNumber;
      this.fieldName = Objects.requireNonNull(fieldName);
      this.encodingType = Objects.requireNonNull(encodingType);
      this.protoFieldType = Objects.requireNonNull(protoFieldType);
      this.isPacked = isPacked;
      this.fieldProtoTypeName = Objects.requireNonNull(fieldProtoTypeName);
    }
  }

  /**
   * Implemented by generated descriptors to read field {@code fieldNumber} of {@code message}, an
   * instance of their {@code messageIndex}th message type, with its generated getter. See {@link
   * MessageLiteDescriptor#getFieldValue}.
   */
  protected abstract @Nullable Object getFieldValue(
      int messageIndex, Object message, int fieldNumber);

  /** Returns {@code value}, or null if it's its type's default (unset, for implicit presence). */
  protected static @Nullable Object nonDefault(Object value) {
    // Float and Double equality compares bits, so -0.0 and NaN count as set, as in protobuf.
    return defaultScalarValues.contains(value) ? null : value;
  }

  /** Describes map entry type {@code fullName}, whose key and value are fields 1 and 2. */
  protected static MessageLiteDescriptor mapEntry(
      String fullName,
      FieldLiteDescriptor.Type keyType,
      FieldLiteDescriptor.Type valueType,
      String valueTypeName) {
    return new MessageLiteDescriptor(
        fullName,
        Arrays.asList(
            new FieldLiteDescriptor(
                1, "key", FieldLiteDescriptor.EncodingType.SINGULAR, keyType, false, ""),
            new FieldLiteDescriptor(
                2,
                "value",
                FieldLiteDescriptor.EncodingType.SINGULAR,
                valueType,
                false,
                valueTypeName)),
        NO_BUILDER);
  }

  protected CelLiteDescriptor(String version, List<MessageLiteDescriptor> messageInfoList) {
    Map<String, MessageLiteDescriptor> protoFqnMap =
        new HashMap<>(getMapInitialCapacity(messageInfoList.size()));
    for (int i = 0; i < messageInfoList.size(); i++) {
      MessageLiteDescriptor msgInfo = messageInfoList.get(i);
      // A descriptor reused from another CelLiteDescriptor keeps reading through that one.
      if (msgInfo.owner == null) {
        msgInfo.owner = this;
        msgInfo.index = i;
      }
      protoFqnMap.put(msgInfo.getProtoTypeName(), msgInfo);
    }

    this.version = version;
    this.protoFqnToDescriptors = Collections.unmodifiableMap(protoFqnMap);
  }

  /**
   * Returns a capacity that is sufficient to keep the map from being resized as long as it grows no
   * larger than expectedSize and the load factor is ≥ its default (0.75).
   */
  private static int getMapInitialCapacity(int expectedSize) {
    if (expectedSize < 3) {
      return expectedSize + 1;
    }

    // See https://github.com/openjdk/jdk/commit/3e393047e12147a81e2899784b943923fc34da8e. 0.75 is
    // used as a load factor.
    return (int) ceil(expectedSize / 0.75);
  }
}
