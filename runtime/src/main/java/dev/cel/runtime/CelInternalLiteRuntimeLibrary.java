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

package dev.cel.runtime;

import dev.cel.common.CelOptions;
import dev.cel.common.annotations.Internal;

/**
 * CelInternalLiteRuntimeLibrary defines the interface to extend functionalities beyond the CEL
 * standard functions for {@link CelLiteRuntime}, with access to runtime internals. This is not
 * intended for general use.
 *
 * <p>CEL Library Internals. Do Not Use.
 */
@Internal
public interface CelInternalLiteRuntimeLibrary extends CelLiteRuntimeLibrary {

  /**
   * Configures the runtime to support the library implementation, such as adding function bindings.
   */
  void setRuntimeOptions(
      CelLiteRuntimeBuilder runtimeBuilder, RuntimeEquality runtimeEquality, CelOptions celOptions);
}
