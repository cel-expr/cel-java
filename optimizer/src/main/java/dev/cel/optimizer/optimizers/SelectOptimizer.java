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

package dev.cel.optimizer.optimizers;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.collect.ImmutableList.toImmutableList;

import com.google.auto.value.AutoValue;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.primitives.UnsignedLong;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.protobuf.ByteString;
import com.google.protobuf.Descriptors.EnumValueDescriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import dev.cel.bundle.Cel;
import dev.cel.checker.CelStandardDeclarations.StandardFunction;
import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.CelDescriptorUtil;
import dev.cel.common.CelDescriptors;
import dev.cel.common.CelFunctionDecl;
import dev.cel.common.CelMutableAst;
import dev.cel.common.CelOverloadDecl;
import dev.cel.common.CelSource;
import dev.cel.common.CelSource.Extension;
import dev.cel.common.CelSource.Extension.Component;
import dev.cel.common.CelSource.Extension.Version;
import dev.cel.common.ast.CelConstant;
import dev.cel.common.ast.CelExpr.ExprKind.Kind;
import dev.cel.common.ast.CelExprIdGeneratorFactory;
import dev.cel.common.ast.CelExprIdGeneratorFactory.MonotonicIdGenerator;
import dev.cel.common.ast.CelMutableExpr;
import dev.cel.common.ast.CelMutableExpr.CelMutableCall;
import dev.cel.common.ast.CelMutableExpr.CelMutableList;
import dev.cel.common.ast.CelMutableExpr.CelMutableMap;
import dev.cel.common.ast.CelMutableExpr.CelMutableSelect;
import dev.cel.common.internal.CelDescriptorPool;
import dev.cel.common.internal.CombinedDescriptorPool;
import dev.cel.common.internal.DefaultDescriptorPool;
// CEL-Internal-1
import dev.cel.common.navigation.CelNavigableMutableAst;
import dev.cel.common.navigation.CelNavigableMutableExpr;
import dev.cel.common.navigation.TraversalOrder;
import dev.cel.common.types.CelKind;
import dev.cel.common.types.CelTypes;
import dev.cel.common.types.ListType;
import dev.cel.common.types.SimpleType;
import dev.cel.common.values.CelByteString;
import dev.cel.common.values.NullValue;
import dev.cel.optimizer.AstMutator;
import dev.cel.optimizer.CelAstOptimizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Performs field selection optimization on protobuf message select chains.
 *
 * <p>Embeds protobuf field metadata (field number, field name, type code, default value) directly
 * into qualification paths ({@code cel.@attribute}) and field presence paths ({@code cel.@hasField}
 * with field number and field name). This accelerates nested field evaluation, enables
 * reflection-free field traversal in resource-constrained runtimes without descriptor tables, and
 * provides resilience against protobuf field renames.
 *
 * <p><b>WARNING:</b> Evaluating optimized ASTs requires explicit runtime support for {@code
 * cel.@attribute} and {@code cel.@hasField}. Ensure that the target evaluation environment (in
 * Java, C++, Go, or other language runtimes) supports these select optimization functions before
 * applying this optimizer. Evaluating an optimized AST in an unsupported runtime will result in an
 * evaluation error due to missing function overloads.
 *
 * <p><b>Trade-off:</b> Modestly increases serialized AST size over the wire due to the embedded
 * metadata tuples.
 *
 * <p>Expressions are rewritten into the following forms:
 *
 * <pre>
 *   // Selection chains
 *   request.user.age -&gt; cel.@attribute(request,
 *       [[user_num, "user", type_code, default_val], [age_num, "age", type_code, default_val]])
 *
 *   // Presence tests
 *   has(request.user.age) -&gt; cel.@hasField(request,
 *       [[user_num, "user"], [age_num, "age"]])
 * </pre>
 *
 * <p>Map indexing and non-protobuf selects pass through untouched.
 */
public final class SelectOptimizer implements CelAstOptimizer {

  /**
   * CEL select optimization type code for protobuf maps.
   *
   * <p>Protobuf wire format encodes maps as repeated message entries ({@code MapEntry}). To avoid
   * wire-decoding ambiguities with singular submessages, maps use this dedicated type code.
   */
  private static final long CEL_MAP_TYPE_CODE = 20L;

  private static final String CEL_ATTRIBUTE_FUNCTION_NAME = "cel.@attribute";
  private static final String CEL_HAS_FIELD_FUNCTION_NAME = "cel.@hasField";

  @VisibleForTesting
  static final CelFunctionDecl CEL_ATTRIBUTE_FUNCTION_DECL =
      CelFunctionDecl.newFunctionDeclaration(
          CEL_ATTRIBUTE_FUNCTION_NAME,
          CelOverloadDecl.newGlobalOverload(
              "cel_attribute_list",
              SimpleType.DYN,
              SimpleType.DYN,
              ListType.create(SimpleType.DYN)));

  @VisibleForTesting
  static final CelFunctionDecl CEL_HAS_FIELD_FUNCTION_DECL =
      CelFunctionDecl.newFunctionDeclaration(
          CEL_HAS_FIELD_FUNCTION_NAME,
          CelOverloadDecl.newGlobalOverload(
              "cel_has_field_list",
              SimpleType.BOOL,
              SimpleType.DYN,
              ListType.create(SimpleType.DYN)));

  @VisibleForTesting
  static final Extension SELECT_OPTIMIZATION_AST_EXTENSION_TAG =
      Extension.create("select_optimization", Version.of(1L, 0L), Component.COMPONENT_RUNTIME);

  private final SelectOptimizerOptions options;
  private final AstMutator astMutator;
  private final CelDescriptorPool descriptorPool;

  /** Returns a new select optimizer configured with the provided file descriptors. */
  public static SelectOptimizer newInstance(FileDescriptor... fileDescriptors) {
    return newInstance(SelectOptimizerOptions.newBuilder().build(), fileDescriptors);
  }

  /** Returns a new select optimizer configured with the provided file descriptors. */
  public static SelectOptimizer newInstance(Iterable<FileDescriptor> fileDescriptors) {
    return newInstance(SelectOptimizerOptions.newBuilder().build(), fileDescriptors);
  }

  /** Returns a new select optimizer configured with the provided options and file descriptors. */
  public static SelectOptimizer newInstance(
      SelectOptimizerOptions options, FileDescriptor... fileDescriptors) {
    return newInstance(options, Arrays.asList(checkNotNull(fileDescriptors)));
  }

  /** Returns a new select optimizer configured with the provided options and file descriptors. */
  public static SelectOptimizer newInstance(
      SelectOptimizerOptions options, Iterable<FileDescriptor> fileDescriptors) {
    checkNotNull(options);
    checkNotNull(fileDescriptors);
    return new SelectOptimizer(options, fileDescriptors);
  }

  @Override
  public OptimizationResult optimize(CelAbstractSyntaxTree ast, Cel cel) {
    checkArgument(ast.isChecked(), "AST must be type-checked.");

    CelMutableAst astToModify = CelMutableAst.fromCelAst(ast);
    CelNavigableMutableAst navAst = CelNavigableMutableAst.fromAst(astToModify);
    ImmutableList<CelNavigableMutableExpr> topOfChainSelects =
        navAst
            .getRoot()
            .allNodes(TraversalOrder.POST_ORDER)
            .filter(node -> isTopOfSelectChain(navAst, node))
            .collect(toImmutableList());

    if (topOfChainSelects.isEmpty()) {
      return OptimizationResult.create(ast);
    }

    MonotonicIdGenerator idGenerator =
        CelExprIdGeneratorFactory.newMonotonicIdGenerator(navAst.getRoot().maxId());

    int iterationCount = 0;
    for (CelNavigableMutableExpr topNode : topOfChainSelects) {
      if (++iterationCount > options.iterationLimit()) {
        throw new IllegalStateException("Max iteration count reached.");
      }
      rewriteSelectChain(astToModify, navAst, topNode, idGenerator);
    }

    astToModify = astMutator.renumberIdsConsecutively(astToModify);
    CelAbstractSyntaxTree optimizedAst = tagAstExtension(astToModify.toParsedAst());

    return OptimizationResult.create(
        optimizedAst,
        ImmutableList.of(),
        ImmutableList.of(CEL_ATTRIBUTE_FUNCTION_DECL, CEL_HAS_FIELD_FUNCTION_DECL));
  }

  private void rewriteSelectChain(
      CelMutableAst astToModify,
      CelNavigableMutableAst navAst,
      CelNavigableMutableExpr topNode,
      MonotonicIdGenerator idGenerator) {
    boolean isHasField = topNode.expr().select().testOnly();
    astToModify.source().getMacroCalls().remove(topNode.expr().id());

    List<FieldDescriptor> fields = new ArrayList<>();
    FieldDescriptor topField =
        getOptimizableField(navAst, topNode)
            .orElseThrow(
                () -> new IllegalStateException("Expected optimizable field on select node"));
    fields.add(topField);

    CelMutableExpr currentExpr = topNode.expr().select().operand();
    while (currentExpr.getKind() == Kind.SELECT) {
      CelMutableSelect select = currentExpr.select();
      FieldDescriptor field = getOptimizableFieldForExpr(navAst, select).orElse(null);
      if (field == null) {
        break;
      }
      fields.add(field);
      currentExpr = select.operand();
    }

    Collections.reverse(fields);

    List<CelMutableExpr> qualifierLists = new ArrayList<>(fields.size());
    for (FieldDescriptor field : fields) {
      if (field.getType() == FieldDescriptor.Type.GROUP) {
        throw new UnsupportedOperationException(
            "Optimization of Group fields is unsupported: " + field.getFullName());
      }
      if (field.getType() == FieldDescriptor.Type.MESSAGE) {
        String messageFullName = field.getMessageType().getFullName();
        if (messageFullName.equals(CelTypes.STRUCT_MESSAGE)) {
          throw new UnsupportedOperationException(
              "Optimization of Struct fields is currently unimplemented: " + field.getFullName());
        }
        if (messageFullName.equals(CelTypes.LIST_VALUE_MESSAGE)) {
          throw new UnsupportedOperationException(
              "Optimization of ListValue fields is currently unimplemented: "
                  + field.getFullName());
        }
        if (CelTypes.isWrapperType(messageFullName)) {
          throw new UnsupportedOperationException(
              "Optimization of wrapper fields is currently unimplemented: " + field.getFullName());
        }
      }

      CelMutableList qualifierElements =
          CelMutableList.create(
              CelMutableExpr.ofConstant(
                  idGenerator.nextExprId(), CelConstant.ofValue((long) field.getNumber())),
              CelMutableExpr.ofConstant(
                  idGenerator.nextExprId(), CelConstant.ofValue(field.getName())));
      if (!isHasField) {
        qualifierElements
            .elements()
            .add(
                CelMutableExpr.ofConstant(
                    idGenerator.nextExprId(), CelConstant.ofValue(resolveTypeCode(field))));
        qualifierElements.elements().add(resolveDefaultValue(field, idGenerator));
      }
      qualifierLists.add(CelMutableExpr.ofList(idGenerator.nextExprId(), qualifierElements));
    }

    CelMutableExpr qualifiersExpr =
        CelMutableExpr.ofList(idGenerator.nextExprId(), CelMutableList.create(qualifierLists));
    String functionName = isHasField ? CEL_HAS_FIELD_FUNCTION_NAME : CEL_ATTRIBUTE_FUNCTION_NAME;
    topNode.expr().setCall(CelMutableCall.create(functionName, currentExpr, qualifiersExpr));
  }

  private static long resolveTypeCode(FieldDescriptor field) {
    if (field.isMapField()) {
      return CEL_MAP_TYPE_CODE;
    }
    return field.getType().toProto().getNumber();
  }

  private boolean isTopOfSelectChain(CelNavigableMutableAst navAst, CelNavigableMutableExpr node) {
    return getOptimizableField(navAst, node).isPresent()
        && !node.parent().flatMap(parent -> getOptimizableField(navAst, parent)).isPresent();
  }

  private Optional<FieldDescriptor> getOptimizableField(
      CelNavigableMutableAst navAst, CelNavigableMutableExpr node) {
    if (node.getKind() != Kind.SELECT) {
      return Optional.empty();
    }
    return getOptimizableFieldForExpr(navAst, node.expr().select());
  }

  private Optional<FieldDescriptor> getOptimizableFieldForExpr(
      CelNavigableMutableAst navAst, CelMutableSelect select) {
    return navAst
        .getType(select.operand().id())
        .filter(type -> type.kind() == CelKind.STRUCT)
        .flatMap(type -> descriptorPool.findDescriptor(type.name()))
        .map(desc -> desc.findFieldByName(select.field()));
  }

  private static CelMutableExpr resolveDefaultValue(
      FieldDescriptor field, MonotonicIdGenerator idGenerator) {
    if (field.isMapField()) {
      return CelMutableExpr.ofMap(
          idGenerator.nextExprId(), CelMutableMap.create(ImmutableList.of()));
    }
    if (field.isRepeated()) {
      return CelMutableExpr.ofList(idGenerator.nextExprId(), CelMutableList.create());
    }
    if (field.getType() == FieldDescriptor.Type.MESSAGE) {
      String messageFullName = field.getMessageType().getFullName();
      switch (messageFullName) {
        case CelTypes.DURATION_MESSAGE:
          return CelMutableExpr.ofCall(
              idGenerator.nextExprId(),
              CelMutableCall.create(
                  StandardFunction.DURATION.functionName(),
                  CelMutableExpr.ofConstant(idGenerator.nextExprId(), CelConstant.ofValue("0s"))));
        case CelTypes.TIMESTAMP_MESSAGE:
          return CelMutableExpr.ofCall(
              idGenerator.nextExprId(),
              CelMutableCall.create(
                  StandardFunction.TIMESTAMP.functionName(),
                  CelMutableExpr.ofConstant(idGenerator.nextExprId(), CelConstant.ofValue(0L))));
        // TODO: Support STRUCT_MESSAGE, LIST_VALUE_MESSAGE, and wrapper types.
        default:
          return CelMutableExpr.ofConstant(
              idGenerator.nextExprId(), CelConstant.ofValue(NullValue.NULL_VALUE));
      }
    }

    return CelMutableExpr.ofConstant(idGenerator.nextExprId(), resolveConstantDefaultValue(field));
  }

  private static CelConstant resolveConstantDefaultValue(FieldDescriptor field) {
    Object def = field.getDefaultValue();
    switch (field.getType()) {
      case DOUBLE:
        return CelConstant.ofValue((Double) def);
      case FLOAT:
        return CelConstant.ofValue(((Float) def).doubleValue());
      case INT64:
      case SINT64:
      case SFIXED64:
        return CelConstant.ofValue((Long) def);
      case UINT64:
      case FIXED64:
        return CelConstant.ofValue(UnsignedLong.fromLongBits((Long) def));
      case INT32:
      case SINT32:
      case SFIXED32:
        return CelConstant.ofValue(((Integer) def).longValue());
      case UINT32:
      case FIXED32:
        return CelConstant.ofValue(
            UnsignedLong.fromLongBits(Integer.toUnsignedLong((Integer) def)));
      case BOOL:
        return CelConstant.ofValue((Boolean) def);
      case STRING:
        return CelConstant.ofValue((String) def);
      case BYTES:
        ByteString byteString = (ByteString) def;
        return byteString.isEmpty()
            ? CelConstant.ofValue(CelByteString.EMPTY)
            : CelConstant.ofValue(CelByteString.of(byteString.toByteArray()));
      case ENUM:
        EnumValueDescriptor enumValue = (EnumValueDescriptor) def;
        return CelConstant.ofValue((long) enumValue.getNumber());
      default:
        throw new IllegalArgumentException("Unsupported protobuf field type: " + field.getType());
    }
  }

  private static CelAbstractSyntaxTree tagAstExtension(CelAbstractSyntaxTree ast) {
    CelSource.Builder celSourceBuilder =
        ast.getSource().toBuilder().addAllExtensions(SELECT_OPTIMIZATION_AST_EXTENSION_TAG);
    return CelAbstractSyntaxTree.newParsedAst(ast.getExpr(), celSourceBuilder.build());
  }

  private SelectOptimizer(
      SelectOptimizerOptions options, Iterable<FileDescriptor> fileDescriptors) {
    this.options = checkNotNull(options);
    this.astMutator = AstMutator.newInstance(options.iterationLimit());
    this.descriptorPool = newDescriptorPool(options, checkNotNull(fileDescriptors));
  }

  private static CelDescriptorPool newDescriptorPool(
      SelectOptimizerOptions options, Iterable<FileDescriptor> fileDescriptors) {
    CelDescriptors celDescriptors =
        CelDescriptorUtil.getAllDescriptorsFromFileDescriptor(checkNotNull(fileDescriptors));
    ImmutableList.Builder<CelDescriptorPool> descriptorPools = ImmutableList.builder();

    descriptorPools.add(DefaultDescriptorPool.create(celDescriptors));

    return CombinedDescriptorPool.create(descriptorPools.build());
  }

  /** Options configuring the behavior of {@link SelectOptimizer}. */
  @AutoValue
  public abstract static class SelectOptimizerOptions {

    public abstract int iterationLimit();

    public abstract boolean enableLinkedMessageTypes();

    /** Builder for configuring {@link SelectOptimizerOptions}. */
    @AutoValue.Builder
    public abstract static class Builder {

      @CanIgnoreReturnValue
      public abstract Builder iterationLimit(int value);

      @CanIgnoreReturnValue
      public abstract Builder enableLinkedMessageTypes(boolean enable);

      public abstract SelectOptimizerOptions build();

      Builder() {}
    }

    public abstract Builder toBuilder();

    /** Returns a new options builder with recommended defaults. */
    public static Builder newBuilder() {
      return new AutoValue_SelectOptimizer_SelectOptimizerOptions.Builder()
          .iterationLimit(500)
          .enableLinkedMessageTypes(true);
    }

    // Package-private constructor to prevent external extension, required by @AutoValue.
    SelectOptimizerOptions() {}
  }
}
