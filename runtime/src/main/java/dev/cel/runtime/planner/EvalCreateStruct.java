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

package dev.cel.runtime.planner;

import com.google.common.collect.Maps;
import com.google.errorprone.annotations.Immutable;
import dev.cel.common.ast.CelExpr;
import dev.cel.common.types.CelType;
import dev.cel.common.values.CelValueProvider;
import dev.cel.common.values.StructValue;
import dev.cel.runtime.AccumulatedUnknowns;
import dev.cel.runtime.GlobalResolver;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;

@Immutable
final class EvalCreateStruct extends PlannedInterpretable {

  private final CelValueProvider valueProvider;
  private final CelType structType;

  // Array contents are not mutated
  @SuppressWarnings("Immutable")
  private final String[] keys;

  // Array contents are not mutated
  @SuppressWarnings("Immutable")
  private final PlannedInterpretable[] values;

  // Array contents are not mutated
  @SuppressWarnings("Immutable")
  private final boolean[] isOptional;

  @Override
  Object evalInternal(GlobalResolver resolver, ExecutionFrame frame) {
    // In source order, so that the last of several fields set in one oneof wins, as in protobuf.
    Map<String, Object> fieldValues = Maps.newLinkedHashMapWithExpectedSize(keys.length);
    AccumulatedUnknowns unknowns = null;
    for (int i = 0; i < keys.length; i++) {
      Object value = EvalHelpers.evalStrictly(values[i], resolver, frame);

      if (value instanceof AccumulatedUnknowns) {
        unknowns = AccumulatedUnknowns.maybeMerge(unknowns, value);
        continue;
      }

      if (isOptional[i]) {
        if (!(value instanceof Optional)) {
          throw new IllegalArgumentException(
              String.format(
                  "Cannot initialize optional entry '%s' from non-optional value" + " %s",
                  keys[i], value));
        }

        Optional<?> opt = (Optional<?>) value;
        if (!opt.isPresent()) {
          // This is a no-op currently but will be semantically correct when extended proto
          // support allows proto mutation.
          fieldValues.remove(keys[i]);
          continue;
        }
        value = opt.get();
      }

      fieldValues.put(keys[i], value);
    }

    if (unknowns != null) {
      return unknowns;
    }

    // Either a primitive (wrappers) or a struct is produced
    Object value =
        valueProvider
            .newValue(structType.name(), Collections.unmodifiableMap(fieldValues))
            .orElseThrow(
                () -> new IllegalArgumentException("Type name not found: " + structType.name()));
    if (value instanceof StructValue) {
      return ((StructValue<?, ?>) value).value();
    }

    return value;
  }

  static EvalCreateStruct create(
      CelExpr expr,
      CelValueProvider valueProvider,
      CelType structType,
      String[] keys,
      PlannedInterpretable[] values,
      boolean[] isOptional) {
    return new EvalCreateStruct(expr, valueProvider, structType, keys, values, isOptional);
  }

  private EvalCreateStruct(
      CelExpr expr,
      CelValueProvider valueProvider,
      CelType structType,
      String[] keys,
      PlannedInterpretable[] values,
      boolean[] isOptional) {
    super(expr);
    this.valueProvider = valueProvider;
    this.structType = structType;
    this.keys = keys;
    this.values = values;
    this.isOptional = isOptional;
  }
}
