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

package dev.cel.runtime.async;

import dev.cel.runtime.CelRuntimeFactory;

/**
 * Factory for default {@link CelAsyncRuntime}.
 *
 * @deprecated Migrate to the planner's asynchronous evaluation ({@link
 *     CelRuntimeFactory#plannerRuntimeBuilder()}, {@code CelFunctionBinding.fromAsync}, and {@code
 *     Program.evalAsync}).
 */
@Deprecated
public final class CelAsyncRuntimeFactory {

  /**
   * @deprecated See {@link CelAsyncRuntimeFactory}.
   */
  @Deprecated
  @SuppressWarnings("deprecation") // Retained on deprecated CelAsyncRuntimeFactory
  public static CelAsyncRuntimeBuilder defaultAsyncRuntime() {
    return CelAsyncRuntimeImpl.newBuilder();
  }

  private CelAsyncRuntimeFactory() {}
}
