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
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.primitives.UnsignedLong;
import com.google.errorprone.annotations.Immutable;
import dev.cel.common.ast.CelConstant;
import dev.cel.common.ast.CelExpr;
import dev.cel.common.ast.CelExpr.CelMap;
import dev.cel.common.ast.CelExpr.ExprKind.Kind;
import dev.cel.common.types.CelTypes;
import dev.cel.common.values.CelByteString;
import dev.cel.common.values.CelValueConverter;
import dev.cel.common.values.OptimizedSelectTraversal;
import dev.cel.common.values.OptionalValue;
import dev.cel.common.values.SelectField;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Plans optimizer-rewritten select chains ({@code cel.@attribute}) and presence tests ({@code
 * cel.@hasField}) into {@link EvalAttribute} instances decorated with {@code
 * OptimizedSelectQualifier}.
 */
@Immutable
final class OptimizedSelectPlanner {

  static final String CEL_ATTRIBUTE_FUNCTION_NAME = "cel.@attribute";
  static final String CEL_HAS_FIELD_FUNCTION_NAME = "cel.@hasField";

  /**
   * Well-known message types whose CEL semantics (Any unpacking, JSON value conversion) are not
   * implemented by the optimized traversal. Wrapper types are rejected via {@link
   * CelTypes#isWrapperType}.
   */
  private static final ImmutableSet<String> UNSUPPORTED_WELL_KNOWN_TYPE_IDENTS =
      ImmutableSet.of(
          CelTypes.ANY_MESSAGE,
          CelTypes.STRUCT_MESSAGE,
          CelTypes.VALUE_MESSAGE,
          CelTypes.LIST_VALUE_MESSAGE);

  private final AttributeFactory attributeFactory;
  private final CelValueConverter celValueConverter;

  static OptimizedSelectPlanner create(
      AttributeFactory attributeFactory, CelValueConverter celValueConverter) {
    return new OptimizedSelectPlanner(attributeFactory, celValueConverter);
  }

  PlannedInterpretable plan(CelExpr expr, String functionName, OperandPlanner operandPlanner) {
    ImmutableList<CelExpr> args = expr.call().args();
    ImmutableList<SelectField> selectFields;
    boolean isPresenceTest;
    switch (functionName) {
      case CEL_ATTRIBUTE_FUNCTION_NAME:
        {
          checkArgument(
              args.size() == 3, "Expected 3 arguments for %s, found %s", functionName, args.size());
          selectFields = unpackAttributeFields(args.get(1), args.get(2));
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
      CelExpr qualifiersExpr, CelExpr dummyOrDefExpr) {
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
      long fieldNumber = parseFieldNumber(hopElements.get(0));
      String fieldName = parseFieldName(hopElements.get(1));
      long rawTypeCode = parseTypeCode(hopElements.get(2));
      checkArgument(
          SelectField.isSupportedTypeCode(rawTypeCode),
          "Invalid protobuf type code: %s",
          rawTypeCode);

      if (!isLeaf) {
        checkArgument(
            hopElements.size() == 3,
            "Non-leaf qualifier hop must contain exactly 3 elements: %s",
            hopExpr);
        checkArgument(
            rawTypeCode == SelectField.MESSAGE_TYPE_CODE,
            "Non-leaf qualifier hop must have MESSAGE type code (11), found: %s",
            rawTypeCode);
        fieldsBuilder.add(SelectField.create(fieldNumber, fieldName, rawTypeCode));
      } else if (rawTypeCode == SelectField.CEL_MAP_TYPE_CODE) {
        checkArgument(
            hopElements.size() == 4,
            "Map qualifier hop must contain exactly 4 elements: %s",
            hopExpr);
        fieldsBuilder.add(parseMapLeaf(fieldNumber, fieldName, hopElements.get(3), dummyOrDefExpr));
      } else {
        checkArgument(
            hopElements.size() == 3,
            "Leaf qualifier hop must contain exactly 3 elements, found: %s",
            hopExpr);
        fieldsBuilder.add(
            dummyOrDefExpr.getKind() == Kind.LIST
                ? parseRepeatedLeaf(fieldNumber, fieldName, rawTypeCode, dummyOrDefExpr)
                : parseSingularLeaf(fieldNumber, fieldName, rawTypeCode, dummyOrDefExpr));
      }
    }
    return fieldsBuilder.build();
  }

  private static SelectField parseMapLeaf(
      long fieldNumber, String fieldName, CelExpr mapEntrySpecExpr, CelExpr dummyExpr) {
    SelectField.MapEntrySpec mapEntrySpec = parseMapEntrySpec(mapEntrySpecExpr);
    checkArgument(
        dummyExpr.getKind() == Kind.MAP && dummyExpr.map().entries().size() == 1,
        "Expected map dummy/default value with a single entry, found: %s",
        dummyExpr);
    CelMap.Entry entry = dummyExpr.map().entries().get(0);
    validateScalarDummy(mapEntrySpec.keyTypeCode(), entry.key());
    if (mapEntrySpec.valueTypeCode() == SelectField.MESSAGE_TYPE_CODE) {
      // Unlike leaf fields, map values of Any, Struct, Value, and wrapper types are permitted:
      // the traversal only produces the map, and indexing it applies standard CEL conversion.
      return SelectField.createMap(
          fieldNumber, fieldName, mapEntrySpec, parseMessageProtoTypeName(entry.value()));
    }
    validateScalarDummy(mapEntrySpec.valueTypeCode(), entry.value());
    return SelectField.createMap(fieldNumber, fieldName, mapEntrySpec);
  }

  private static SelectField parseRepeatedLeaf(
      long fieldNumber, String fieldName, long typeCode, CelExpr dummyExpr) {
    checkArgument(
        dummyExpr.list().elements().size() == 1,
        "Expected repeated dummy/default value with a single element, found: %s",
        dummyExpr);
    CelExpr elemExpr = dummyExpr.list().elements().get(0);
    if (typeCode == SelectField.MESSAGE_TYPE_CODE) {
      return SelectField.create(
          fieldNumber,
          fieldName,
          typeCode,
          /* defaultValue= */ ImmutableList.of(),
          parseLeafMessageProtoTypeName(elemExpr));
    }
    validateScalarDummy(typeCode, elemExpr);
    return SelectField.create(
        fieldNumber, fieldName, typeCode, /* defaultValue= */ ImmutableList.of());
  }

  private static SelectField parseSingularLeaf(
      long fieldNumber, String fieldName, long typeCode, CelExpr defaultExpr) {
    if (typeCode != SelectField.MESSAGE_TYPE_CODE) {
      return SelectField.create(
          fieldNumber, fieldName, typeCode, resolveScalarDefaultValue(typeCode, defaultExpr));
    }
    String protoTypeName = parseLeafMessageProtoTypeName(defaultExpr);
    Object defaultValue = null;
    if (protoTypeName.equals(CelTypes.DURATION_MESSAGE)) {
      defaultValue = Duration.ZERO;
    } else if (protoTypeName.equals(CelTypes.TIMESTAMP_MESSAGE)) {
      defaultValue = Instant.EPOCH;
    }
    return SelectField.create(fieldNumber, fieldName, typeCode, defaultValue, protoTypeName);
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

  private static SelectField.MapEntrySpec parseMapEntrySpec(CelExpr expr) {
    checkArgument(
        expr.getKind() == Kind.LIST,
        "Expected map entry spec to be a list, found: %s",
        expr.getKind());
    ImmutableList<CelExpr> elements = expr.list().elements();
    checkArgument(
        elements.size() == 2,
        "Expected map entry spec list to contain exactly 2 elements (key_type_code, val_type_code),"
            + " found: %s",
        elements.size());
    long keyTypeCode = parseTypeCode(elements.get(0));
    long valTypeCode = parseTypeCode(elements.get(1));
    return SelectField.MapEntrySpec.create(keyTypeCode, valTypeCode);
  }

  private static String parseMessageProtoTypeName(CelExpr expr) {
    checkArgument(
        expr.getKind() == Kind.STRUCT, "Expected struct for message type, found: %s", expr);
    String protoTypeName = expr.struct().messageName();
    checkArgument(!protoTypeName.isEmpty(), "Protobuf message type name must not be empty");
    return protoTypeName;
  }

  private static String parseLeafMessageProtoTypeName(CelExpr expr) {
    String protoTypeName = parseMessageProtoTypeName(expr);
    checkArgument(
        !CelTypes.isWrapperType(protoTypeName)
            && !UNSUPPORTED_WELL_KNOWN_TYPE_IDENTS.contains(protoTypeName),
        "Leaf well-known type '%s' is not supported by the select-optimized runtime",
        protoTypeName);
    return protoTypeName;
  }

  private static Object resolveScalarDefaultValue(long typeCode, CelExpr defaultExpr) {
    checkArgument(
        defaultExpr.getKind() == Kind.CONSTANT
            && defaultExpr.constant().getKind() != CelConstant.Kind.NULL_VALUE,
        "Expected non-null constant default value expression, found: %s",
        defaultExpr);
    Object value = PlannerHelpers.resolveConstant(defaultExpr.constant());
    ScalarType expectedScalar = ScalarType.fromTypeCode((int) typeCode);
    checkArgument(
        expectedScalar.valueClass.isInstance(value),
        "Scalar default value %s is incompatible with type code %s",
        value,
        typeCode);
    return value;
  }

  private static void validateScalarDummy(long typeCode, CelExpr dummyExpr) {
    resolveScalarDefaultValue(typeCode, dummyExpr);
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
    BOOL(Boolean.class),
    INT(Long.class),
    UINT(UnsignedLong.class),
    DOUBLE(Double.class),
    STRING(String.class),
    BYTES(CelByteString.class);

    private final Class<?> valueClass;

    ScalarType(Class<?> valueClass) {
      this.valueClass = valueClass;
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
          throw new IllegalArgumentException("Unexpected leaf type code: " + leafTypeCode);
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
      return Iterables.getLast(fields).fieldName();
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

  private OptimizedSelectPlanner(
      AttributeFactory attributeFactory, CelValueConverter celValueConverter) {
    this.attributeFactory = checkNotNull(attributeFactory);
    this.celValueConverter = checkNotNull(celValueConverter);
  }
}
