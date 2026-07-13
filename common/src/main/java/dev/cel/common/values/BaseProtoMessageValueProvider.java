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

package dev.cel.common.values;

import com.google.errorprone.annotations.Immutable;
import dev.cel.common.annotations.Internal;

/**
 * {@code BaseProtoMessageValueProvider} is a common parent to {@code ProtoMessageValueProvider} and
 * {@code ProtoMessageLiteValueProvider}.
 *
 * <p>CEL-Java internals. Do not use. Use one of the inherited variants mentioned above.
 */
@Internal
@Immutable
public abstract class BaseProtoMessageValueProvider implements CelValueProvider {

  public abstract BaseProtoCelValueConverter protoCelValueConverter();

  @Override
  public CelValueConverter celValueConverter() {
    return protoCelValueConverter();
  }
}
