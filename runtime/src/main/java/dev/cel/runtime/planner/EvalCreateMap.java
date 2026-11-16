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

import static com.google.common.base.Preconditions.checkArgument;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Sets;
import com.google.errorprone.annotations.Immutable;
import dev.cel.common.ast.CelExpr;
import dev.cel.common.exceptions.CelDuplicateKeyException;
import dev.cel.common.exceptions.CelInvalidArgumentException;
import dev.cel.common.internal.MapKeys;
import dev.cel.runtime.AccumulatedUnknowns;
import dev.cel.runtime.GlobalResolver;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

@Immutable
final class EvalCreateMap extends PlannedInterpretable {

  // Array contents are not mutated
  @SuppressWarnings("Immutable")
  private final PlannedInterpretable[] keys;

  // Array contents are not mutated
  @SuppressWarnings("Immutable")
  private final PlannedInterpretable[] values;

  // Array contents are not mutated
  @SuppressWarnings("Immutable")
  private final boolean[] isOptional;

  @Override
  Object evalInternal(GlobalResolver resolver, ExecutionFrame frame) {
    ImmutableMap.Builder<Object, Object> builder =
        ImmutableMap.builderWithExpectedSize(keys.length);
    HashSet<Object> keysSeen = Sets.newHashSetWithExpectedSize(keys.length);
    AccumulatedUnknowns unknowns = null;

    for (int i = 0; i < keys.length; i++) {
      PlannedInterpretable keyInterpretable = keys[i];
      Object key = EvalHelpers.evalStrictly(keyInterpretable, resolver, frame);

      if (key instanceof AccumulatedUnknowns) {
        unknowns = AccumulatedUnknowns.maybeMerge(unknowns, key);
      } else {
        validateAndTrackKey(keysSeen, keyInterpretable, key);
      }

      Object val = EvalHelpers.evalStrictly(values[i], resolver, frame);

      unknowns = AccumulatedUnknowns.maybeMerge(unknowns, val);
      if (unknowns != null) {
        continue;
      }

      if (isOptional[i]) {
        if (!(val instanceof Optional)) {
          throw new IllegalArgumentException(
              String.format(
                  "Cannot initialize optional entry '%s' from non-optional value %s", key, val));
        }

        Optional<?> opt = (Optional<?>) val;
        if (!opt.isPresent()) {
          // The key isn't set, so a later entry may use it.
          keysSeen.remove(MapKeys.normalize(key));
          continue;
        }
        val = opt.get();
      }

      builder.put(key, val);
    }

    if (unknowns != null) {
      return unknowns;
    }

    return builder.buildOrThrow();
  }

  private static void validateAndTrackKey(
      Set<Object> keysSeen, PlannedInterpretable keyInterpretable, Object key) {
    if (!MapKeys.isValidKeyType(key)) {
      throw new LocalizedEvaluationException(
          new CelInvalidArgumentException("Unsupported key type: " + key),
          keyInterpretable.expr().id());
    }

    if (!keysSeen.add(MapKeys.normalize(key))) {
      throw new LocalizedEvaluationException(
          CelDuplicateKeyException.of(key), keyInterpretable.expr().id());
    }
  }

  static PlannedInterpretable create(
      CelExpr expr,
      PlannedInterpretable[] keys,
      PlannedInterpretable[] values,
      boolean[] isOptional) {
    for (int i = 0; i < keys.length; i++) {
      if (!(keys[i] instanceof EvalConstant)
          || !(values[i] instanceof EvalConstant)
          || isOptional[i]) {
        return new EvalCreateMap(expr, keys, values, isOptional);
      }
    }
    ImmutableMap.Builder<Object, Object> builder =
        ImmutableMap.builderWithExpectedSize(keys.length);
    HashSet<Object> keysSeen = Sets.newHashSetWithExpectedSize(keys.length);
    try {
      for (int i = 0; i < keys.length; i++) {
        Object key = ((EvalConstant) keys[i]).constantValue();
        validateAndTrackKey(keysSeen, keys[i], key);
        builder.put(key, ((EvalConstant) values[i]).constantValue());
      }
    } catch (LocalizedEvaluationException e) {
      return new EvalCreateMap(expr, keys, values, isOptional);
    }
    return EvalConstant.create(expr, builder.buildOrThrow());
  }

  private EvalCreateMap(
      CelExpr expr,
      PlannedInterpretable[] keys,
      PlannedInterpretable[] values,
      boolean[] isOptional) {
    super(expr);
    checkArgument(keys.length == values.length);
    checkArgument(keys.length == isOptional.length);
    this.keys = keys;
    this.values = values;
    this.isOptional = isOptional;
  }
}
