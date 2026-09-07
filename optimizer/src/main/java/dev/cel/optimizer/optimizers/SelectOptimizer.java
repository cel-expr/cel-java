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
import java.util.concurrent.atomic.AtomicLong;

/**
 * Performs field selection optimization on protobuf message select chains.
 *
 * <p>Embeds protobuf field metadata (field number, field name, type code, default value) directly
 * into qualification paths ({@code cel.@attribute} and {@code cel.@hasField}). This accelerates
 * nested field evaluation, enables reflection-free field traversal in resource-constrained runtimes
 * without descriptor tables, and provides resilience against protobuf field renames.
 *
 * <p><b>Trade-off:</b> Modestly increases serialized AST size over the wire due to the embedded
 * metadata tuples.
 *
 * <h2>AST Rewriting Semantics</h2>
 *
 * <ul>
 *   <li>Selection chains: {@code request.user.age} &rarr; {@code cel.@attribute(request,
 *       [[user_num, "user", type_code, default_val], [age_num, "age", type_code, default_val]])}
 *   <li>Presence tests: {@code has(request.user.age)} &rarr; {@code cel.@hasField(request,
 *       [[user_num, "user"], [age_num, "age"]])}
 * </ul>
 *
 * <p>Map indexing and non-protobuf selects pass through untouched.
 */
public final class SelectOptimizer implements CelAstOptimizer {

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

  private static final SelectOptimizer INSTANCE =
      new SelectOptimizer(SelectOptimizerOptions.newBuilder().build());

  private final SelectOptimizerOptions options;
  private final AstMutator astMutator;

  /** Returns a default instance of the select optimizer with preconfigured defaults. */
  public static SelectOptimizer getInstance() {
    return INSTANCE;
  }

  /** Returns a new select optimizer configured with the provided options. */
  public static SelectOptimizer newInstance(SelectOptimizerOptions options) {
    return new SelectOptimizer(options);
  }

  /** Returns a new select optimizer configured with the provided options and file descriptors. */
  public static SelectOptimizer newInstance(
      SelectOptimizerOptions options, FileDescriptor... fileDescriptors) {
    return newInstance(options, Arrays.asList(checkNotNull(fileDescriptors)));
  }

  /** Returns a new select optimizer configured with the provided options and file descriptors. */
  public static SelectOptimizer newInstance(
      SelectOptimizerOptions options, Iterable<FileDescriptor> fileDescriptors) {
    return new SelectOptimizer(
        checkNotNull(options).toBuilder().addFileDescriptors(fileDescriptors).build());
  }

  private SelectOptimizer(SelectOptimizerOptions options) {
    this.options = checkNotNull(options);
    this.astMutator = AstMutator.newInstance(options.iterationLimit());
  }

  @Override
  public OptimizationResult optimize(CelAbstractSyntaxTree ast, Cel cel) {
    checkArgument(ast.isChecked(), "AST must be type-checked.");

    CelMutableAst astToModify = CelMutableAst.fromCelAst(ast);
    if (!options.populateMacroCalls()) {
      astToModify.source().clearMacroCalls();
    }

    CelNavigableMutableAst navAst = CelNavigableMutableAst.fromAst(astToModify);
    ImmutableList<CelNavigableMutableExpr> topOfChainSelects =
        navAst
            .getRoot()
            .allNodes(TraversalOrder.POST_ORDER)
            .filter(node -> isTopOfSelectChain(navAst, node))
            .collect(toImmutableList());

    if (topOfChainSelects.isEmpty()) {
      if (!options.populateMacroCalls() && !ast.getSource().getMacroCalls().isEmpty()) {
        return OptimizationResult.create(astToModify.toParsedAst());
      }
      return OptimizationResult.create(ast);
    }

    long maxId = navAst.getRoot().allNodes().mapToLong(node -> node.expr().id()).max().orElse(0L);
    AtomicLong idCounter = new AtomicLong(maxId);

    for (CelNavigableMutableExpr topNode : topOfChainSelects) {
      rewriteSelectChain(astToModify, navAst, topNode, idCounter);
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
      AtomicLong idCounter) {
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
      if (isHasField) {
        qualifierLists.add(
            CelMutableExpr.ofList(
                idCounter.incrementAndGet(),
                CelMutableList.create(
                    CelMutableExpr.ofConstant(
                        idCounter.incrementAndGet(), CelConstant.ofValue((long) field.getNumber())),
                    CelMutableExpr.ofConstant(
                        idCounter.incrementAndGet(), CelConstant.ofValue(field.getName())))));
      } else {
        CelMutableExpr defaultValue = resolveDefaultValue(field, idCounter);
        qualifierLists.add(
            CelMutableExpr.ofList(
                idCounter.incrementAndGet(),
                CelMutableList.create(
                    CelMutableExpr.ofConstant(
                        idCounter.incrementAndGet(), CelConstant.ofValue((long) field.getNumber())),
                    CelMutableExpr.ofConstant(
                        idCounter.incrementAndGet(), CelConstant.ofValue(field.getName())),
                    CelMutableExpr.ofConstant(
                        idCounter.incrementAndGet(),
                        CelConstant.ofValue((long) field.getType().toProto().getNumber())),
                    defaultValue)));
      }
    }

    CelMutableExpr qualifiersExpr =
        CelMutableExpr.ofList(idCounter.incrementAndGet(), CelMutableList.create(qualifierLists));
    String functionName = isHasField ? CEL_HAS_FIELD_FUNCTION_NAME : CEL_ATTRIBUTE_FUNCTION_NAME;
    topNode.expr().setCall(CelMutableCall.create(functionName, currentExpr, qualifiersExpr));
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
        .flatMap(type -> options.descriptorPool().findDescriptor(type.name()))
        .map(desc -> desc.findFieldByName(select.field()));
  }

  private static CelMutableExpr resolveDefaultValue(FieldDescriptor field, AtomicLong idCounter) {
    if (field.isMapField()) {
      return CelMutableExpr.ofMap(
          idCounter.incrementAndGet(), CelMutableMap.create(ImmutableList.of()));
    }
    if (field.isRepeated()) {
      return CelMutableExpr.ofList(idCounter.incrementAndGet(), CelMutableList.create());
    }
    if (field.getType() == FieldDescriptor.Type.MESSAGE
        || field.getType() == FieldDescriptor.Type.GROUP) {
      String messageFullName = field.getMessageType().getFullName();
      switch (messageFullName) {
        case "google.protobuf.Duration":
          return CelMutableExpr.ofCall(
              idCounter.incrementAndGet(),
              CelMutableCall.create(
                  "duration",
                  CelMutableExpr.ofConstant(
                      idCounter.incrementAndGet(), CelConstant.ofValue("0s"))));
        case "google.protobuf.Timestamp":
          return CelMutableExpr.ofCall(
              idCounter.incrementAndGet(),
              CelMutableCall.create(
                  "timestamp",
                  CelMutableExpr.ofConstant(
                      idCounter.incrementAndGet(), CelConstant.ofValue("1970-01-01T00:00:00Z"))));
        case "google.protobuf.Struct":
          return CelMutableExpr.ofMap(
              idCounter.incrementAndGet(), CelMutableMap.create(ImmutableList.of()));
        case "google.protobuf.ListValue":
          return CelMutableExpr.ofList(idCounter.incrementAndGet(), CelMutableList.create());
        default:
          return CelMutableExpr.ofConstant(
              idCounter.incrementAndGet(), CelConstant.ofValue(NullValue.NULL_VALUE));
      }
    }

    Object def = field.getDefaultValue();
    switch (field.getType()) {
      case DOUBLE:
        return CelMutableExpr.ofConstant(
            idCounter.incrementAndGet(), CelConstant.ofValue((Double) def));
      case FLOAT:
        return CelMutableExpr.ofConstant(
            idCounter.incrementAndGet(), CelConstant.ofValue(((Float) def).doubleValue()));
      case INT64:
      case SINT64:
      case SFIXED64:
        return CelMutableExpr.ofConstant(
            idCounter.incrementAndGet(), CelConstant.ofValue((Long) def));
      case UINT64:
      case FIXED64:
        return CelMutableExpr.ofConstant(
            idCounter.incrementAndGet(),
            CelConstant.ofValue(UnsignedLong.fromLongBits((Long) def)));
      case INT32:
      case SINT32:
      case SFIXED32:
        return CelMutableExpr.ofConstant(
            idCounter.incrementAndGet(), CelConstant.ofValue(((Integer) def).longValue()));
      case UINT32:
      case FIXED32:
        return CelMutableExpr.ofConstant(
            idCounter.incrementAndGet(),
            CelConstant.ofValue(UnsignedLong.fromLongBits(Integer.toUnsignedLong((Integer) def))));
      case BOOL:
        return CelMutableExpr.ofConstant(
            idCounter.incrementAndGet(), CelConstant.ofValue((Boolean) def));
      case STRING:
        return CelMutableExpr.ofConstant(
            idCounter.incrementAndGet(), CelConstant.ofValue((String) def));
      case BYTES:
        ByteString byteString = (ByteString) def;
        return CelMutableExpr.ofConstant(
            idCounter.incrementAndGet(),
            byteString.isEmpty()
                ? CelConstant.ofValue(CelByteString.EMPTY)
                : CelConstant.ofValue(CelByteString.of(byteString.toByteArray())));
      case ENUM:
        EnumValueDescriptor enumValue = (EnumValueDescriptor) def;
        return CelMutableExpr.ofConstant(
            idCounter.incrementAndGet(), CelConstant.ofValue((long) enumValue.getNumber()));
      default:
        throw new IllegalArgumentException("Unsupported protobuf field type: " + field.getType());
    }
  }

  private static CelAbstractSyntaxTree tagAstExtension(CelAbstractSyntaxTree ast) {
    CelSource.Builder celSourceBuilder =
        ast.getSource().toBuilder().addAllExtensions(SELECT_OPTIMIZATION_AST_EXTENSION_TAG);
    return CelAbstractSyntaxTree.newParsedAst(ast.getExpr(), celSourceBuilder.build());
  }

  /** Options configuring the behavior of {@link SelectOptimizer}. */
  @AutoValue
  public abstract static class SelectOptimizerOptions {

    public abstract int iterationLimit();

    public abstract boolean populateMacroCalls();

    abstract CelDescriptorPool descriptorPool();

    /** Builder for configuring {@link SelectOptimizerOptions}. */
    @AutoValue.Builder
    public abstract static class Builder {

      public abstract Builder iterationLimit(int value);

      public abstract Builder populateMacroCalls(boolean value);

      abstract Builder descriptorPool(CelDescriptorPool descriptorPool);

      abstract Optional<CelDescriptorPool> descriptorPool();

      private final List<FileDescriptor> fileDescriptors;
      private boolean linkedMessageTypesEnabled;

      /**
       * Sets whether to resolve compiled linked message types in the descriptor pool.
       *
       * <p>Note: This setting is only applied when the initial descriptor pool is constructed. It
       * has no effect when configuring an options instance from {@link #toBuilder()} whose
       * descriptor pool has already been initialized.
       */
      @CanIgnoreReturnValue
      public Builder enableLinkedMessageTypes(boolean enable) {
        this.linkedMessageTypesEnabled = enable;
        return this;
      }

      /** Adds file descriptors to the descriptor pool. */
      @CanIgnoreReturnValue
      public Builder addFileDescriptors(FileDescriptor... fileDescriptors) {
        return addFileDescriptors(Arrays.asList(checkNotNull(fileDescriptors)));
      }

      /** Adds file descriptors to the descriptor pool. */
      @CanIgnoreReturnValue
      public Builder addFileDescriptors(Iterable<FileDescriptor> fileDescriptors) {
        checkNotNull(fileDescriptors);
        for (FileDescriptor fileDescriptor : fileDescriptors) {
          this.fileDescriptors.add(checkNotNull(fileDescriptor));
        }
        return this;
      }

      abstract SelectOptimizerOptions autoBuild();

      public SelectOptimizerOptions build() {
        CelDescriptorPool pool =
            descriptorPool()
                .map(
                    existingPool -> {
                      if (fileDescriptors.isEmpty()) {
                        return existingPool;
                      }
                      CelDescriptors descriptors =
                          CelDescriptorUtil.getAllDescriptorsFromFileDescriptor(fileDescriptors);
                      fileDescriptors.clear();
                      return CombinedDescriptorPool.create(
                          ImmutableList.of(
                              DefaultDescriptorPool.create(descriptors), existingPool));
                    })
                .orElseGet(
                    () -> {
                      ImmutableList.Builder<CelDescriptorPool> pools = ImmutableList.builder();
                      if (!fileDescriptors.isEmpty()) {
                        CelDescriptors descriptors =
                            CelDescriptorUtil.getAllDescriptorsFromFileDescriptor(fileDescriptors);
                        pools.add(DefaultDescriptorPool.create(descriptors));
                      }

                      pools.add(DefaultDescriptorPool.INSTANCE);
                      fileDescriptors.clear();
                      return CombinedDescriptorPool.create(pools.build());
                    });
        descriptorPool(pool);
        return autoBuild();
      }

      Builder() {
        this.fileDescriptors = new ArrayList<>();
        this.linkedMessageTypesEnabled = true;
      }
    }

    abstract Builder toBuilder();

    /** Returns a new options builder with recommended defaults. */
    public static Builder newBuilder() {
      return new AutoValue_SelectOptimizer_SelectOptimizerOptions.Builder()
          .iterationLimit(500)
          .populateMacroCalls(true);
    }

    // Package-private constructor to prevent external extension, required by @AutoValue.
    SelectOptimizerOptions() {}
  }
}
