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

package dev.cel.runtime;

import com.google.errorprone.annotations.Immutable;
import com.google.protobuf.Message;
import dev.cel.common.CelOptions;
import dev.cel.common.annotations.Internal;
import dev.cel.common.internal.DynamicProto;
import dev.cel.common.internal.ProtoEquality;
import java.util.Objects;

/**
 * ProtoMessageRuntimeEquality contains methods for performing CEL related equality checks,
 * including full protobuf messages by leveraging descriptors.
 *
 * <p>CEL Library Internals. Do Not Use.
 */
@Internal
@Immutable
public final class ProtoMessageRuntimeEquality extends RuntimeEquality {

  private final ProtoEquality protoEquality;

  @Internal
  public static ProtoMessageRuntimeEquality create(
      DynamicProto dynamicProto, CelOptions celOptions) {
    return new ProtoMessageRuntimeEquality(dynamicProto, celOptions);
  }

  @Override
  public boolean objectEquals(Object x, Object y) {
    if (celOptions.disableCelStandardEquality()) {
      return Objects.equals(x, y);
    }

    if (celOptions.enableProtoDifferencerEquality()) {
      x = runtimeHelpers.adaptValue(x);
      y = runtimeHelpers.adaptValue(y);
      if (x instanceof Message) {
        if (!(y instanceof Message)) {
          return false;
        }
        return protoEquality.equals((Message) x, (Message) y);
      }
    }

    return super.objectEquals(x, y);
  }

  @Override
  public int hashCode(Object object) {
    if (!celOptions.disableCelStandardEquality() && celOptions.enableProtoDifferencerEquality()) {
      object = runtimeHelpers.adaptValue(object);
      if (object instanceof Message) {
        return protoEquality.hashCode((Message) object);
      }
    }
    return super.hashCode(object);
  }

  private ProtoMessageRuntimeEquality(DynamicProto dynamicProto, CelOptions celOptions) {
    super(ProtoMessageRuntimeHelpers.create(dynamicProto, celOptions), celOptions);
    this.protoEquality = new ProtoEquality(dynamicProto);
  }
}
