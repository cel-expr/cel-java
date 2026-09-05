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

import com.google.common.collect.ImmutableList;
import com.google.errorprone.annotations.Immutable;
import dev.cel.common.values.CelValueConverter;
import dev.cel.runtime.AccumulatedUnknowns;
import dev.cel.runtime.GlobalResolver;
import java.util.Map;

/**
 * An attribute resolved relative to a base expression (operand) by applying a sequence of
 * qualifiers.
 */
@Immutable
final class RelativeAttribute implements Attribute {

  private final PlannedInterpretable operand;
  private final CelValueConverter celValueConverter;
  private final ImmutableList<Qualifier> qualifiers;

  @Override
  public Object resolve(long exprId, GlobalResolver ctx, ExecutionFrame frame) {
    Object obj = EvalHelpers.evalStrictly(operand, ctx, frame);
    if (obj instanceof AccumulatedUnknowns) {
      return obj;
    }

    obj = (obj instanceof Map) ? obj : celValueConverter.toRuntimeValue(obj);

    // Avoid enhanced for loop to prevent UnmodifiableIterator from being allocated
    for (int i = 0; i < qualifiers.size(); i++) {
      Qualifier element = qualifiers.get(i);
      obj = element.qualify(obj);
      obj = (obj instanceof Map) ? obj : celValueConverter.toRuntimeValue(obj);
    }

    if (obj instanceof Map) {
      obj = celValueConverter.toRuntimeValue(obj);
    }

    return celValueConverter.maybeUnwrap(obj);
  }

  @Override
  public Attribute addQualifier(Qualifier qualifier) {
    return new RelativeAttribute(
        this.operand,
        celValueConverter,
        ImmutableList.<Qualifier>builderWithExpectedSize(qualifiers.size() + 1)
            .addAll(this.qualifiers)
            .add(qualifier)
            .build());
  }

  RelativeAttribute(PlannedInterpretable operand, CelValueConverter celValueConverter) {
    this(operand, celValueConverter, ImmutableList.of());
  }

  private RelativeAttribute(
      PlannedInterpretable operand,
      CelValueConverter celValueConverter,
      ImmutableList<Qualifier> qualifiers) {
    this.operand = operand;
    this.celValueConverter = celValueConverter;
    this.qualifiers = qualifiers;
  }
}
