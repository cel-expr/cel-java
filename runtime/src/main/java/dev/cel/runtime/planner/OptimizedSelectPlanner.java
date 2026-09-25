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
import com.google.common.collect.ImmutableMap;
import com.google.common.primitives.UnsignedLong;
import com.google.errorprone.annotations.Immutable;
import dev.cel.common.ast.CelConstant;
import dev.cel.common.ast.CelExpr;
import dev.cel.common.ast.CelExpr.CelCall;
import dev.cel.common.ast.CelExpr.ExprKind.Kind;
import dev.cel.common.types.CelType;
import dev.cel.common.types.SimpleType;
import dev.cel.common.values.CelByteString;
import dev.cel.common.values.CelValueConverter;
import dev.cel.common.values.OptimizedSelectTraversal;
import dev.cel.common.values.OptionalValue;
import dev.cel.common.values.SelectField;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Plans optimizer-rewritten select chains ({@code cel.@attribute}) and presence tests ({@code
 * cel.@hasField}) into {@link EvalAttribute} instances decorated with {@code
 * OptimizedSelectQualifier}.
 */
@Immutable
final class OptimizedSelectPlanner {

  static final String CEL_ATTRIBUTE_FUNCTION_NAME = "cel.@attribute";
  static final String CEL_HAS_FIELD_FUNCTION_NAME = "cel.@hasField";

  private static final String MAP_TYPE_IDENT = "map";
  private static final String LIST_TYPE_IDENT = "list";
  private static final String DURATION_TYPE_IDENT = SimpleType.DURATION.name();
  private static final String TIMESTAMP_TYPE_IDENT = SimpleType.TIMESTAMP.name();

  private final AttributeFactory attributeFactory;
  private final CelValueConverter celValueConverter;

  PlannedInterpretable plan(CelExpr expr, String functionName, OperandPlanner operandPlanner) {
    ImmutableList<CelExpr> args = expr.call().args();
    ImmutableList<SelectField> selectFields;
    boolean isPresenceTest;
    switch (functionName) {
      case CEL_ATTRIBUTE_FUNCTION_NAME:
        {
          checkArgument(
              args.size() == 3, "Expected 3 arguments for %s, found %s", functionName, args.size());
          String typeIdent = extractQualifiedName(args.get(2));
          selectFields = unpackAttributeFields(args.get(1), typeIdent);
          isPresenceTest = false;
          break;
        }
      case CEL_HAS_FIELD_FUNCTION_NAME:
        {
          checkArgument(
              args.size() == 2, "Expected 2 arguments for %s, found %s", functionName, args.size());
          selectFields = unpackHasFieldFields(args.get(1));
          isPresenceTest = true;
          break;
        }
      default:
        throw new IllegalArgumentException(
            "Unsupported optimized select function: " + functionName);
    }

    PlannedInterpretable operand = operandPlanner.plan(args.get(0));
    Attribute qualified = PlannerHelpers.resolveBaseAttribute(operand, attributeFactory);

    int lastIndex = selectFields.size() - 1;
    for (int i = 0; i < lastIndex; i++) {
      qualified = qualified.addQualifier(new PassthroughQualifier(selectFields.get(i).fieldName()));
    }
    qualified =
        qualified.addQualifier(
            new OptimizedSelectQualifier(selectFields, celValueConverter, isPresenceTest));
    return EvalAttribute.create(expr, qualified);
  }

  private static ImmutableList<SelectField> unpackAttributeFields(
      CelExpr qualifiersExpr, String typeIdent) {
    ImmutableList<CelExpr> elements = unpackQualifierHops(qualifiersExpr);
    ImmutableList.Builder<SelectField> fieldsBuilder =
        ImmutableList.builderWithExpectedSize(elements.size());
    for (int i = 0; i < elements.size(); i++) {
      boolean isLeaf = (i == elements.size() - 1);
      CelExpr hopExpr = elements.get(i);
      ImmutableList<CelExpr> hopElements = unpackHopElements(hopExpr);
      checkArgument(
          hopElements.size() == 3 || hopElements.size() == 4,
          "Expected qualifier hop for cel.@attribute to contain 3 or 4 elements, found: %s",
          hopElements.size());
      checkArgument(
          isLeaf || hopElements.size() == 3,
          "Non-leaf qualifier hop must not contain a default value: %s",
          hopExpr);
      long fieldNumber = parseFieldNumber(hopElements.get(0));
      String fieldName = parseFieldName(hopElements.get(1));
      long rawTypeCode = parseTypeCode(hopElements.get(2));
      checkArgument(
          SelectField.isSupportedTypeCode(rawTypeCode),
          "Invalid protobuf type code: %s",
          rawTypeCode);
      checkArgument(
          isLeaf || rawTypeCode == SelectField.MESSAGE_TYPE_CODE,
          "Non-leaf qualifier hop must have MESSAGE type code (11), found: %s",
          rawTypeCode);
      Object defaultValue =
          (hopElements.size() == 4) ? resolveDefaultValue(hopElements.get(3)) : null;
      if (isLeaf) {
        validateLeafTypeIdent((int) rawTypeCode, defaultValue, typeIdent);
      }
      fieldsBuilder.add(SelectField.create(fieldNumber, fieldName, rawTypeCode, defaultValue));
    }
    return fieldsBuilder.build();
  }

  private static ImmutableList<SelectField> unpackHasFieldFields(CelExpr qualifiersExpr) {
    ImmutableList<CelExpr> elements = unpackQualifierHops(qualifiersExpr);
    ImmutableList.Builder<SelectField> fieldsBuilder =
        ImmutableList.builderWithExpectedSize(elements.size());
    for (CelExpr hopExpr : elements) {
      ImmutableList<CelExpr> hopElements = unpackHopElements(hopExpr);
      checkArgument(
          hopElements.size() == 2,
          "Expected qualifier hop for cel.@hasField to contain 2 elements, found: %s",
          hopElements.size());
      long fieldNumber = parseFieldNumber(hopElements.get(0));
      String fieldName = parseFieldName(hopElements.get(1));
      fieldsBuilder.add(SelectField.create(fieldNumber, fieldName));
    }
    return fieldsBuilder.build();
  }

  private static ImmutableList<CelExpr> unpackQualifierHops(CelExpr qualifiersExpr) {
    checkArgument(
        qualifiersExpr.getKind() == Kind.LIST,
        "Expected qualifiers argument to be a list, found: %s",
        qualifiersExpr.getKind());
    ImmutableList<CelExpr> elements = qualifiersExpr.list().elements();
    checkArgument(!elements.isEmpty(), "Expected qualifiers list to be non-empty");
    return elements;
  }

  private static ImmutableList<CelExpr> unpackHopElements(CelExpr hopExpr) {
    checkArgument(
        hopExpr.getKind() == Kind.LIST,
        "Expected qualifier hop to be a list, found: %s",
        hopExpr.getKind());
    return hopExpr.list().elements();
  }

  private static long parseFieldNumber(CelExpr expr) {
    checkArgument(
        expr.getKind() == Kind.CONSTANT
            && expr.constant().getKind() == CelConstant.Kind.INT64_VALUE,
        "Expected qualifier hop field number to be an int64 constant, found: %s",
        expr);
    return expr.constant().int64Value();
  }

  private static String parseFieldName(CelExpr expr) {
    checkArgument(
        expr.getKind() == Kind.CONSTANT
            && expr.constant().getKind() == CelConstant.Kind.STRING_VALUE,
        "Expected qualifier hop field name to be a string constant, found: %s",
        expr);
    return expr.constant().stringValue();
  }

  private static long parseTypeCode(CelExpr expr) {
    checkArgument(
        expr.getKind() == Kind.CONSTANT
            && expr.constant().getKind() == CelConstant.Kind.INT64_VALUE,
        "Expected qualifier hop type code to be an int64 constant, found: %s",
        expr);
    return expr.constant().int64Value();
  }

  private static void validateLeafTypeIdent(
      int leafTypeCode, @Nullable Object defaultValue, String typeIdent) {
    checkArgument(
        defaultValue != null || leafTypeCode == SelectField.MESSAGE_TYPE_CODE,
        "Leaf hop with type code %s must specify a default value",
        leafTypeCode);
    checkArgument(
        (leafTypeCode == SelectField.CEL_MAP_TYPE_CODE) == typeIdent.equals(MAP_TYPE_IDENT),
        "Leaf type code %s is incompatible with typeIdent '%s'",
        leafTypeCode,
        typeIdent);
    checkArgument(
        (defaultValue instanceof Map) == typeIdent.equals(MAP_TYPE_IDENT),
        "Leaf default value %s is incompatible with typeIdent '%s'",
        defaultValue,
        typeIdent);
    checkArgument(
        (defaultValue instanceof List) == typeIdent.equals(LIST_TYPE_IDENT),
        "Leaf default value %s is incompatible with typeIdent '%s'",
        defaultValue,
        typeIdent);
    if (typeIdent.equals(MAP_TYPE_IDENT) || typeIdent.equals(LIST_TYPE_IDENT)) {
      return;
    }
    if (leafTypeCode == SelectField.MESSAGE_TYPE_CODE) {
      checkArgument(
          !ScalarType.isScalarTypeIdent(typeIdent),
          "Leaf MESSAGE type code (11) is incompatible with scalar typeIdent '%s'",
          typeIdent);
      if (typeIdent.equals(DURATION_TYPE_IDENT)) {
        checkArgument(
            Objects.equals(defaultValue, Duration.ZERO),
            "Leaf default value for message type '%s' is invalid or missing: %s",
            typeIdent,
            defaultValue);
      } else if (typeIdent.equals(TIMESTAMP_TYPE_IDENT)) {
        checkArgument(
            Objects.equals(defaultValue, Instant.EPOCH),
            "Leaf default value for message type '%s' is invalid or missing: %s",
            typeIdent,
            defaultValue);
      } else {
        checkArgument(
            defaultValue == null,
            "Leaf default value for message type '%s' is invalid or missing: %s",
            typeIdent,
            defaultValue);
      }
      return;
    }
    ScalarType expectedScalar = ScalarType.fromTypeCode(leafTypeCode);
    checkArgument(
        typeIdent.equals(expectedScalar.typeIdent),
        "Leaf type code %s (expected '%s') is incompatible with typeIdent '%s'",
        leafTypeCode,
        expectedScalar.typeIdent,
        typeIdent);
    checkArgument(
        expectedScalar.valueClass.isInstance(defaultValue),
        "Leaf default value %s is incompatible with typeIdent '%s'",
        defaultValue,
        expectedScalar.typeIdent);
  }

  private static Object resolveDefaultValue(CelExpr defaultExpr) {
    switch (defaultExpr.getKind()) {
      case CONSTANT:
        if (defaultExpr.constant().getKind() != CelConstant.Kind.NULL_VALUE) {
          return PlannerHelpers.resolveConstant(defaultExpr.constant());
        }
        break;
      case LIST:
        if (defaultExpr.list().elements().isEmpty()) {
          return ImmutableList.of();
        }
        break;
      case MAP:
        if (defaultExpr.map().entries().isEmpty()) {
          return ImmutableMap.of();
        }
        break;
      case CALL:
        CelCall call = defaultExpr.call();
        if (call.function().equals("duration")
            && call.args().size() == 1
            && call.args().get(0).getKind() == Kind.CONSTANT
            && call.args().get(0).constant().getKind() == CelConstant.Kind.STRING_VALUE
            && call.args().get(0).constant().stringValue().equals("0s")) {
          return Duration.ZERO;
        }
        if (call.function().equals("timestamp")
            && call.args().size() == 1
            && call.args().get(0).getKind() == Kind.CONSTANT
            && call.args().get(0).constant().getKind() == CelConstant.Kind.INT64_VALUE
            && call.args().get(0).constant().int64Value() == 0L) {
          return Instant.EPOCH;
        }
        break;
      default:
        break;
    }
    throw new IllegalArgumentException("Unsupported default value expression: " + defaultExpr);
  }

  private static String extractQualifiedName(CelExpr expr) {
    if (expr.getKind() == Kind.IDENT) {
      return expr.ident().name();
    }
    if (expr.getKind() == Kind.SELECT && !expr.select().testOnly()) {
      return extractQualifiedName(expr.select().operand()) + "." + expr.select().field();
    }
    throw new IllegalArgumentException(
        "Expected type identifier argument to be an IDENT or non-testOnly SELECT, found: " + expr);
  }

  private static void validateTarget(Object target) {
    if (target instanceof OptionalValue || target instanceof Optional) {
      throw new UnsupportedOperationException(
          "Optional operands are not yet supported by the select-optimized runtime");
    }
  }

  /**
   * Functional interface for planning an operand expression into a {@link PlannedInterpretable}.
   */
  @FunctionalInterface
  interface OperandPlanner {
    PlannedInterpretable plan(CelExpr expr);
  }

  private enum ScalarType {
    BOOL(SimpleType.BOOL, Boolean.class),
    INT(SimpleType.INT, Long.class),
    UINT(SimpleType.UINT, UnsignedLong.class),
    DOUBLE(SimpleType.DOUBLE, Double.class),
    STRING(SimpleType.STRING, String.class),
    BYTES(SimpleType.BYTES, CelByteString.class);

    private final String typeIdent;
    private final Class<?> valueClass;

    ScalarType(CelType celType, Class<?> valueClass) {
      this.typeIdent = celType.name();
      this.valueClass = valueClass;
    }

    static boolean isScalarTypeIdent(String typeIdent) {
      for (ScalarType type : values()) {
        if (type.typeIdent.equals(typeIdent)) {
          return true;
        }
      }
      return false;
    }

    static ScalarType fromTypeCode(int leafTypeCode) {
      switch (leafTypeCode) {
        case 1: // DOUBLE
        case 2: // FLOAT
          return DOUBLE;
        case 3: // INT64
        case 5: // INT32
        case 14: // ENUM
        case 15: // SFIXED32
        case 16: // SFIXED64
        case 17: // SINT32
        case 18: // SINT64
          return INT;
        case 4: // UINT64
        case 6: // FIXED64
        case 7: // FIXED32
        case 13: // UINT32
          return UINT;
        case 8: // BOOL
          return BOOL;
        case 9: // STRING
          return STRING;
        case 12: // BYTES
          return BYTES;
        default:
          throw new IllegalStateException("Unexpected leaf type code: " + leafTypeCode);
      }
    }
  }

  @Immutable
  private static final class PassthroughQualifier implements Qualifier {
    private final String fieldName;

    @Override
    public Object value() {
      return fieldName;
    }

    /**
     * Returns the operand unchanged: this qualifier exists only to contribute {@code fieldName} to
     * the attribute path for unknown resolution. The operand is already a traversal target, so
     * returning it verbatim upholds the {@link Qualifier#qualify} contract.
     */
    @Override
    public Object qualify(Object operand) {
      return operand;
    }

    private PassthroughQualifier(String fieldName) {
      this.fieldName = checkNotNull(fieldName);
    }
  }

  @Immutable
  private static final class OptimizedSelectQualifier implements Qualifier {
    private final ImmutableList<SelectField> fields;
    private final CelValueConverter celValueConverter;
    private final boolean isPresenceTest;

    @Override
    public Object value() {
      return fields.get(fields.size() - 1).fieldName();
    }

    @Override
    public Object qualify(Object operand) {
      validateTarget(operand);
      if (isPresenceTest) {
        // A boolean is already a valid traversal target.
        return OptimizedSelectTraversal.hasField(operand, fields);
      }
      return celValueConverter.toTraversalTarget(OptimizedSelectTraversal.qualify(operand, fields));
    }

    private OptimizedSelectQualifier(
        ImmutableList<SelectField> fields,
        CelValueConverter celValueConverter,
        boolean isPresenceTest) {
      this.fields = checkNotNull(fields);
      this.celValueConverter = checkNotNull(celValueConverter);
      this.isPresenceTest = isPresenceTest;
    }
  }

  static OptimizedSelectPlanner create(
      AttributeFactory attributeFactory, CelValueConverter celValueConverter) {
    return new OptimizedSelectPlanner(attributeFactory, celValueConverter);
  }

  private OptimizedSelectPlanner(
      AttributeFactory attributeFactory, CelValueConverter celValueConverter) {
    this.attributeFactory = checkNotNull(attributeFactory);
    this.celValueConverter = checkNotNull(celValueConverter);
  }
}
