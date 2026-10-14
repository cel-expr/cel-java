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

package dev.cel.common.internal;

import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static java.util.Arrays.stream;

import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
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
import com.google.protobuf.StringValue;
import com.google.protobuf.Struct;
import com.google.protobuf.Timestamp;
import com.google.protobuf.UInt32Value;
import com.google.protobuf.UInt64Value;
import com.google.protobuf.Value;
import dev.cel.common.annotations.Internal;
import java.util.Optional;
import java.util.function.Function;

/**
 * WellKnownProto types used throughout CEL. These types are specially handled to ensure that
 * bidirectional conversion between CEL native values and these well-known types is performed
 * consistently across runtimes.
 */
@Internal
public enum WellKnownProto {
  ANY_VALUE("google.protobuf.Any", "google/protobuf/any.proto", Any.class),
  DURATION("google.protobuf.Duration", "google/protobuf/duration.proto", Duration.class),
  JSON_LIST_VALUE("google.protobuf.ListValue", "google/protobuf/struct.proto", ListValue.class),
  JSON_STRUCT_VALUE("google.protobuf.Struct", "google/protobuf/struct.proto", Struct.class),
  JSON_VALUE("google.protobuf.Value", "google/protobuf/struct.proto", Value.class),
  TIMESTAMP("google.protobuf.Timestamp", "google/protobuf/timestamp.proto", Timestamp.class),
  // Wrapper types
  FLOAT_VALUE(
      "google.protobuf.FloatValue",
      "google/protobuf/wrappers.proto",
      FloatValue.class,
      /* isWrapperType= */ true),
  INT32_VALUE(
      "google.protobuf.Int32Value",
      "google/protobuf/wrappers.proto",
      Int32Value.class,
      /* isWrapperType= */ true),
  INT64_VALUE(
      "google.protobuf.Int64Value",
      "google/protobuf/wrappers.proto",
      Int64Value.class,
      /* isWrapperType= */ true),
  STRING_VALUE(
      "google.protobuf.StringValue",
      "google/protobuf/wrappers.proto",
      StringValue.class,
      /* isWrapperType= */ true),
  BOOL_VALUE(
      "google.protobuf.BoolValue",
      "google/protobuf/wrappers.proto",
      BoolValue.class,
      /* isWrapperType= */ true),
  BYTES_VALUE(
      "google.protobuf.BytesValue",
      "google/protobuf/wrappers.proto",
      BytesValue.class,
      /* isWrapperType= */ true),
  DOUBLE_VALUE(
      "google.protobuf.DoubleValue",
      "google/protobuf/wrappers.proto",
      DoubleValue.class,
      /* isWrapperType= */ true),
  UINT32_VALUE(
      "google.protobuf.UInt32Value",
      "google/protobuf/wrappers.proto",
      UInt32Value.class,
      /* isWrapperType= */ true),
  UINT64_VALUE(
      "google.protobuf.UInt64Value",
      "google/protobuf/wrappers.proto",
      UInt64Value.class,
      /* isWrapperType= */ true),
  // These aren't explicitly called out as wrapper types in the spec, but behave like one, because
  // they are still converted into an equivalent primitive type.

  EMPTY(
      "google.protobuf.Empty",
      "google/protobuf/empty.proto",
      Empty.class,
      /* isWrapperType= */ true),
  FIELD_MASK(
      "google.protobuf.FieldMask",
      "google/protobuf/field_mask.proto",
      FieldMask.class,
      /* isWrapperType= */ true);

  private static final ImmutableMap<String, WellKnownProto> TYPE_NAME_TO_WELL_KNOWN_PROTO_MAP =
      stream(WellKnownProto.values())
          .collect(toImmutableMap(WellKnownProto::typeName, Function.identity()));

  private static final ImmutableMultimap<String, WellKnownProto> PATH_NAME_TO_WELL_KNOWN_PROTO_MAP =
      initPathNameMap();

  private static ImmutableMultimap<String, WellKnownProto> initPathNameMap() {
    ImmutableMultimap.Builder<String, WellKnownProto> builder = ImmutableMultimap.builder();
    for (WellKnownProto proto : values()) {
      builder.put(proto.pathName(), proto);
    }
    return builder.build();
  }

  private final String wellKnownProtoTypeName;
  private final String pathName;
  private final Class<?> clazz;
  private final boolean isWrapperType;

  /** Gets the full proto path name (ex: google/protobuf/any.proto) */
  public String pathName() {
    return pathName;
  }

  /** Gets the fully qualified prototype name (ex: google.protobuf.FloatValue) */
  public String typeName() {
    return wellKnownProtoTypeName;
  }

  /** Gets the underlying java class for this WellKnownProto. */
  public Class<?> messageClass() {
    return clazz;
  }

  /**
   * Returns the well known proto given the full proto path (example:
   * google/protobuf/timestamp.proto)
   */
  public static ImmutableCollection<WellKnownProto> getByPathName(String typeName) {
    return PATH_NAME_TO_WELL_KNOWN_PROTO_MAP.get(typeName);
  }

  public static Optional<WellKnownProto> getByTypeName(String typeName) {
    return Optional.ofNullable(TYPE_NAME_TO_WELL_KNOWN_PROTO_MAP.get(typeName));
  }

  /**
   * Returns true if the provided {@code typeName} is a well known type, and it's a wrapper. False
   * otherwise.
   */
  public static boolean isWrapperType(String typeName) {
    return getByTypeName(typeName).map(WellKnownProto::isWrapperType).orElse(false);
  }

  public boolean isWrapperType() {
    return isWrapperType;
  }

  WellKnownProto(String wellKnownProtoTypeName, String pathName, Class<?> clazz) {
    this(wellKnownProtoTypeName, pathName, clazz, /* isWrapperType= */ false);
  }

  WellKnownProto(
      String wellKnownProtoFullName, String pathName, Class<?> clazz, boolean isWrapperType) {
    this.wellKnownProtoTypeName = wellKnownProtoFullName;
    this.pathName = pathName;
    this.clazz = clazz;
    this.isWrapperType = isWrapperType;
  }
}
