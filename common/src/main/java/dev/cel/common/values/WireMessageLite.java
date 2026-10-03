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

import com.google.errorprone.annotations.Immutable;
import com.google.protobuf.ByteString;
import dev.cel.common.annotations.Beta;

/**
 * Represents a protobuf message evaluation result in {@code CelLiteRuntime} when no {@code
 * CelLiteDescriptor} is registered for the message type.
 *
 * <p>When a message-typed expression is evaluated in {@code CelLiteRuntime}:
 *
 * <ul>
 *   <li>If a {@code CelLiteDescriptor} is registered for the message type, evaluation produces a
 *       {@code MessageLite} instance.
 *   <li>Otherwise, evaluation produces a {@code WireMessageLite} carrying the message's protobuf
 *       type name and wire-encoded payload.
 * </ul>
 */
@Immutable
@Beta
public interface WireMessageLite {

  /**
   * Returns the fully-qualified protobuf message type name (e.g. {@code
   * "cel.expr.conformance.proto3.TestAllTypes.NestedMessage"}), or {@code "cel.@unknownMessage"} if
   * the message type name is not known at runtime.
   */
  String protoTypeName();

  /** Serializes the message to a {@link ByteString} in protobuf wire format. */
  ByteString toByteString();
}
