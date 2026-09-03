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

import com.google.common.collect.ImmutableList;
import javax.annotation.concurrent.ThreadSafe;
import org.jspecify.annotations.Nullable;

/**
 * Provides callbacks for monitoring the lifecycle of asynchronous function calls.
 *
 * <p>Implementations must be thread-safe: {@code onCallStarted} is invoked from the thread
 * dispatching the call, while {@code onCallFinished} is invoked from the call's completion thread.
 */
@ThreadSafe
public interface CelAsyncObserver {

  /**
   * Invoked when an asynchronous function call is first dispatched.
   *
   * @param call The call description.
   * @param args The evaluated arguments passed to the function call.
   */
  void onCallStarted(CelAsyncCall call, ImmutableList<Object> args);

  /**
   * Invoked when an asynchronous function call completes with either a result or an exception.
   *
   * @param call The call description.
   * @param result The result of the call if successful, or null if failed.
   * @param error The failure cause if the call failed, or null if successful.
   */
  void onCallFinished(CelAsyncCall call, @Nullable Object result, @Nullable Throwable error);
}
