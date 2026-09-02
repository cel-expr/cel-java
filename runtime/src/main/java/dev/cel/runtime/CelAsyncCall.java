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
import java.time.Duration;

/** Describes a pending or completed asynchronous function call. */
public interface CelAsyncCall {

  /** Returns the unique incremental tracking ID assigned to this call. */
  long callId();

  /** Returns the AST expression node ID where the call is located. */
  long exprId();

  /** Returns the name of the function being invoked. */
  String functionName();

  /** Returns the specific overload ID being invoked. */
  String overloadId();

  /** Returns the arguments passed to the function call. */
  ImmutableList<Object> arguments();

  /** Returns the elapsed duration of the async function execution. */
  Duration elapsedDuration();
}
