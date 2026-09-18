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

package dev.cel.runtime.planner;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.collect.ImmutableList;
import com.google.errorprone.annotations.Immutable;
import dev.cel.common.ast.CelExpr;
import dev.cel.common.exceptions.CelAttributeNotFoundException;
import dev.cel.common.values.CelValueConverter;
import dev.cel.common.values.NullValue;
import dev.cel.common.values.OptimizedSelectTraversal;
import dev.cel.common.values.OptionalValue;
import dev.cel.common.values.SelectField;
import dev.cel.runtime.GlobalResolver;

/**
 * An interpretable that evaluates an optimized select chain ({@code cel.@attribute}) or presence
 * test ({@code cel.@hasField}) on an operand.
 */
@Immutable
final class EvalOptimizedSelect extends PlannedInterpretable {

  private final Attribute attribute;

  @Override
  Object evalInternal(GlobalResolver resolver, ExecutionFrame frame) {
    Object resolved = attribute.resolve(expr().id(), resolver, frame);
    if (resolved instanceof MissingAttribute) {
      ((MissingAttribute) resolved).resolve(expr().id(), resolver, frame);
    }
    return resolved;
  }

  Attribute attribute() {
    return attribute;
  }

  static EvalOptimizedSelect createQualify(
      CelExpr expr,
      ImmutableList<SelectField> fields,
      Attribute attribute,
      CelValueConverter celValueConverter) {
    return create(expr, fields, attribute, celValueConverter, /* isPresenceTest= */ false);
  }

  static EvalOptimizedSelect createHasField(
      CelExpr expr,
      ImmutableList<SelectField> fields,
      Attribute attribute,
      CelValueConverter celValueConverter) {
    return create(expr, fields, attribute, celValueConverter, /* isPresenceTest= */ true);
  }

  private static EvalOptimizedSelect create(
      CelExpr expr,
      ImmutableList<SelectField> fields,
      Attribute attribute,
      CelValueConverter celValueConverter,
      boolean isPresenceTest) {
    checkArgument(!fields.isEmpty(), "Expected qualifier fields to be non-empty");
    checkNotNull(celValueConverter);
    Attribute qualified = checkNotNull(attribute);
    int lastIndex = fields.size() - 1;
    for (int i = 0; i < lastIndex; i++) {
      qualified = qualified.addQualifier(new PassthroughQualifier(fields.get(i).fieldName()));
    }
    qualified =
        qualified.addQualifier(
            new OptimizedSelectQualifier(fields, celValueConverter, isPresenceTest));
    return new EvalOptimizedSelect(expr, qualified);
  }

  private static void validateTarget(Object target, SelectField field) {
    if (target instanceof NullValue) {
      throw CelAttributeNotFoundException.forFieldResolution(field.fieldName());
    }
    if (target instanceof OptionalValue) {
      throw new UnsupportedOperationException(
          "Optional operands are not yet supported by the select-optimized runtime");
    }
  }

  @Immutable
  private static final class PassthroughQualifier implements Qualifier {
    private final String fieldName;

    private PassthroughQualifier(String fieldName) {
      this.fieldName = fieldName;
    }

    @Override
    public Object value() {
      return fieldName;
    }

    @Override
    public Object qualify(Object value) {
      return value;
    }
  }

  @Immutable
  private static final class OptimizedSelectQualifier implements Qualifier {
    private final ImmutableList<SelectField> fields;
    private final CelValueConverter celValueConverter;
    private final boolean isPresenceTest;

    private OptimizedSelectQualifier(
        ImmutableList<SelectField> fields,
        CelValueConverter celValueConverter,
        boolean isPresenceTest) {
      this.fields = fields;
      this.celValueConverter = celValueConverter;
      this.isPresenceTest = isPresenceTest;
    }

    @Override
    public Object value() {
      return fields.get(fields.size() - 1).fieldName();
    }

    @Override
    public Object qualify(Object value) {
      validateTarget(value, fields.get(0));
      if (isPresenceTest) {
        return OptimizedSelectTraversal.hasField(value, fields, celValueConverter);
      }
      return OptimizedSelectTraversal.qualify(value, fields, celValueConverter);
    }
  }

  private EvalOptimizedSelect(CelExpr expr, Attribute attribute) {
    super(expr);
    this.attribute = checkNotNull(attribute);
  }
}
