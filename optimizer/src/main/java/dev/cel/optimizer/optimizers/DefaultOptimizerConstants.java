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

package dev.cel.optimizer.optimizers;

import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static java.util.Arrays.stream;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Streams;
import dev.cel.checker.CelStandardDeclarations;
import dev.cel.common.Operator;
import dev.cel.extensions.CelExtensions;
import dev.cel.extensions.CelOptionalLibrary;
import dev.cel.extensions.CelOptionalLibrary.Function;

/**
 * Package-private class that holds constants that's generally applicable across canonical
 * optimizers provided from CEL.
 */
final class DefaultOptimizerConstants {

  /**
   * List of function names from standard functions and extension libraries. These are free of side
   * effects, thus amenable for optimization.
   */
  static final ImmutableSet<String> CEL_CANONICAL_FUNCTIONS =
      ImmutableSet.<String>builder()
          .addAll(CelStandardDeclarations.getAllFunctionNames())
          .addAll(
              Streams.concat(
                      stream(Operator.values()).map(Operator::getFunction),
                      stream(CelOptionalLibrary.Function.values()).map(Function::getFunction))
                  .collect(toImmutableSet()))
          .addAll(CelExtensions.getAllFunctionNames())
          .build();

  static final String CEL_ATTRIBUTE_FUNCTION_NAME = "cel.@attribute";
  static final String CEL_OPTIONAL_ATTRIBUTE_FUNCTION_NAME = "cel.@optionalAttribute";
  static final String CEL_HAS_FIELD_FUNCTION_NAME = "cel.@hasField";
  static final ImmutableSet<String> SELECT_OPTIMIZED_FUNCTION_NAMES =
      ImmutableSet.of(
          CEL_ATTRIBUTE_FUNCTION_NAME,
          CEL_OPTIONAL_ATTRIBUTE_FUNCTION_NAME,
          CEL_HAS_FIELD_FUNCTION_NAME);

  private DefaultOptimizerConstants() {}
}
