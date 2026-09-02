// Copyright 2024 Google LLC
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

import javax.annotation.concurrent.ThreadSafe;
import java.util.Collection;
import java.util.Optional;

/**
 * Interface to a resolver for CEL functions based on the function name, overload ids, and
 * arguments.
 */
@ThreadSafe
public interface CelFunctionResolver {

  /** An empty function resolver that resolves no overloads. */
  CelFunctionResolver EMPTY =
      new CelFunctionResolver() {
        @Override
        public Optional<CelResolvedOverload> findOverloadMatchingArgs(
            String functionName, Collection<String> overloadIds, Object[] args) {
          return Optional.empty();
        }

        @Override
        public Optional<CelResolvedOverload> findOverloadMatchingArgs(
            String functionName, Object[] args) {
          return Optional.empty();
        }
      };

  /**
   * Finds a specific function overload to invoke based on given parameters.
   *
   * @param functionName the logical name of the function being invoked.
   * @param overloadIds A list of function overload ids. The dispatcher selects the unique overload
   *     from this list with matching arguments.
   * @param args The arguments to pass to the function.
   * @return an optional value of the resolved overload.
   * @throws CelEvaluationException if the overload resolution is ambiguous,
   */
  @SuppressWarnings("AvoidObjectArrays") // Low-level interpreter argument passing matches
  // CelFunctionOverload.apply(Object[])
  Optional<CelResolvedOverload> findOverloadMatchingArgs(
      String functionName, Collection<String> overloadIds, Object[] args)
      throws CelEvaluationException;

  /**
   * Finds a specific function overload to invoke based on given parameters, scanning all available
   * overloads if necessary.
   *
   * @param functionName the logical name of the function being invoked.
   * @param args The arguments to pass to the function.
   * @return an optional value of the resolved overload.
   * @throws CelEvaluationException if the overload resolution is ambiguous.
   */
  @SuppressWarnings("AvoidObjectArrays") // Low-level interpreter argument passing matches
  // CelFunctionOverload.apply(Object[])
  Optional<CelResolvedOverload> findOverloadMatchingArgs(String functionName, Object[] args)
      throws CelEvaluationException;
}
