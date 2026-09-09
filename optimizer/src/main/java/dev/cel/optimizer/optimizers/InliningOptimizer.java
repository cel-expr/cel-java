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

import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableList;
import com.google.common.primitives.UnsignedLong;
import dev.cel.bundle.Cel;
import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.CelMutableAst;
import dev.cel.common.Operator;
import dev.cel.common.ast.CelConstant;
import dev.cel.common.ast.CelExpr.ExprKind.Kind;
import dev.cel.common.ast.CelMutableExpr;
import dev.cel.common.ast.CelMutableExpr.CelMutableCall;
import dev.cel.common.ast.CelMutableExpr.CelMutableStruct;
import dev.cel.common.navigation.CelNavigableExprUtil;
import dev.cel.common.navigation.CelNavigableMutableExpr;
import dev.cel.common.navigation.TraversalOrder;
import dev.cel.common.types.CelKind;
import dev.cel.common.types.CelType;
import dev.cel.common.types.SimpleType;
import dev.cel.common.values.NullValue;
import dev.cel.optimizer.AstMutator;
import dev.cel.optimizer.AstMutator.SubtreeReplacement;
import dev.cel.optimizer.CelAstOptimizer;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

/**
 * Performs optimization for inlining variables within function calls and select statements with
 * their associated AST.
 */
public final class InliningOptimizer implements CelAstOptimizer {

  private final ImmutableList<InlineVariable> inlineVariables;
  private final AstMutator astMutator;

  /**
   * Creates a new {@code InliningOptimizer} with one or more {@link InlineVariable}s.
   *
   * <p>Note that the variables to be inlined can be a dependency to one other based on the supplied
   * ordering. This allows for recursive inlining where a replacement value might itself contain
   * variables that need to be inlined.
   *
   * <p>For example, given a source expression {@code "a + b"} and inline variables in the following
   * order:
   *
   * <ul>
   *   <li>{@code {a: b, b: 2}}, result: {@code 2 + 2}.
   *   <li>{@code {b: 2, a: b}}, result: {@code b + 2}.
   * </ul>
   */
  public static InliningOptimizer newInstance(InlineVariable... inlineVariables) {
    return newInstance(ImmutableList.copyOf(inlineVariables));
  }

  /**
   * Creates a new {@code InliningOptimizer}.
   *
   * @see #newInstance(InlineVariable...)
   */
  public static InliningOptimizer newInstance(List<InlineVariable> inlineVariables) {
    return newInstance(InliningOptions.newBuilder().build(), ImmutableList.copyOf(inlineVariables));
  }

  /**
   * Creates a new {@code InliningOptimizer}.
   *
   * @see #newInstance(InlineVariable...)
   * @param options {@link InliningOptions} to customize the inlining behavior with.
   */
  public static InliningOptimizer newInstance(
      InliningOptions options, InlineVariable... inlineVariables) {
    return newInstance(options, ImmutableList.copyOf(inlineVariables));
  }

  /**
   * Creates a new {@code InliningOptimizer}.
   *
   * @see #newInstance(InlineVariable...)
   * @param options {@link InliningOptions} to customize the inlining behavior with.
   */
  public static InliningOptimizer newInstance(
      InliningOptions options, List<InlineVariable> inlineVariables) {
    return new InliningOptimizer(options, ImmutableList.copyOf(inlineVariables));
  }

  @Override
  @SuppressWarnings("ReferenceEquality")
  public OptimizationResult optimize(CelAbstractSyntaxTree ast, Cel cel) {
    CelMutableAst initialMutableAst = CelMutableAst.fromCelAst(ast);
    CelMutableAst mutableAst = initialMutableAst;
    for (InlineVariable inlineVariable : inlineVariables) {
      mutableAst =
          astMutator.mutateUntilFixedPoint(
              mutableAst,
              TraversalOrder.POST_ORDER,
              node -> {
                if (!canInline(node, inlineVariable.name())) {
                  return Optional.empty();
                }
                CelMutableAst inlineVariableAst = CelMutableAst.fromCelAst(inlineVariable.ast());
                CelMutableExpr replacementExpr = inlineVariableAst.expr();

                if (node.getKind().equals(Kind.SELECT) && node.expr().select().testOnly()) {
                  replacementExpr = rewritePresenceExpr(inlineVariable, replacementExpr);
                }

                return Optional.of(
                    SubtreeReplacement.of(
                        node.id(), CelMutableAst.of(replacementExpr, inlineVariableAst.source())));
              });
    }

    if (mutableAst == initialMutableAst) {
      return OptimizationResult.create(ast);
    }

    return OptimizationResult.create(astMutator.renumberIdsConsecutively(mutableAst).toParsedAst());
  }

  private static CelMutableExpr rewritePresenceExpr(
      InlineVariable inlineVariable, CelMutableExpr replacementExpr) {
    if (replacementExpr.getKind().equals(Kind.SELECT)) {
      // Preserve testOnly property for Select replacements (has(A) -> has(B))
      replacementExpr.select().setTestOnly(true);
      return replacementExpr;
    }

    CelType replacementType =
        inlineVariable
            .ast()
            .getType(replacementExpr.id())
            .orElseThrow(() -> new NoSuchElementException("Type is not present."));

    if (isSizerType(replacementType)) {
      // has(X) -> X.size() != 0
      return createNotEquals(
          CelMutableExpr.ofCall(CelMutableCall.create(replacementExpr, "size")),
          CelMutableExpr.ofConstant(CelConstant.ofValue(0)));
    }

    if (replacementType.isAssignableFrom(SimpleType.NULL_TYPE)) {
      // has(X) -> X != null
      // This covers well-known wrapper types

      return createNotEquals(
          replacementExpr, CelMutableExpr.ofConstant(CelConstant.ofValue(NullValue.NULL_VALUE)));
    }

    return getZeroValueExpr(replacementType, replacementExpr)
        .map(zeroValue -> createNotEquals(replacementExpr, zeroValue))
        .orElseThrow(
            () ->
                new IllegalArgumentException(
                    String.format(
                        "Unable to inline expression type %s into presence test",
                        replacementType.name())));
  }

  private static Optional<CelMutableExpr> getZeroValueExpr(
      CelType type, CelMutableExpr replacementExpr) {
    switch (type.kind()) {
      case BOOL:
        return Optional.of(CelMutableExpr.ofConstant(CelConstant.ofValue(false)));
      case DOUBLE:
        return Optional.of(CelMutableExpr.ofConstant(CelConstant.ofValue(0.0d)));
      case INT:
        return Optional.of(CelMutableExpr.ofConstant(CelConstant.ofValue(0)));
      case UINT:
        return Optional.of(CelMutableExpr.ofConstant(CelConstant.ofValue(UnsignedLong.ZERO)));
      case TIMESTAMP:
        return Optional.of(
            CelMutableExpr.ofCall(
                CelMutableCall.create(
                    "timestamp", CelMutableExpr.ofConstant(CelConstant.ofValue(0)))));
      case DURATION:
        return Optional.of(
            CelMutableExpr.ofCall(
                CelMutableCall.create(
                    "duration", CelMutableExpr.ofConstant(CelConstant.ofValue("0")))));
      case STRUCT:
        return Optional.of(
            CelMutableExpr.ofStruct(
                CelMutableStruct.create(
                    replacementExpr.struct().messageName(), new ArrayList<>())));
      default:
        return Optional.empty();
    }
  }

  private static CelMutableExpr createNotEquals(CelMutableExpr left, CelMutableExpr right) {
    return CelMutableExpr.ofCall(
        CelMutableCall.create(Operator.NOT_EQUALS.getFunction(), left, right));
  }

  private static boolean isSizerType(CelType type) {
    return type.kind().equals(CelKind.LIST)
        || type.kind().equals(CelKind.MAP)
        || type.equals(SimpleType.STRING)
        || type.equals(SimpleType.BYTES);
  }

  private static boolean canInline(CelNavigableMutableExpr node, String identifier) {
    boolean matches = maybeToQualifiedName(node).map(name -> name.equals(identifier)).orElse(false);

    if (!matches) {
      return false;
    }

    return !CelNavigableExprUtil.isVariableShadowed(node, identifier);
  }

  private static Optional<String> maybeToQualifiedName(CelNavigableMutableExpr node) {
    if (node.getKind().equals(Kind.IDENT)) {
      return Optional.of(node.expr().ident().name());
    }

    if (node.getKind().equals(Kind.SELECT)) {
      return node.children()
          .findFirst()
          .flatMap(InliningOptimizer::maybeToQualifiedName)
          .map(operandName -> operandName + "." + node.expr().select().field());
    }

    return Optional.empty();
  }

  /** Represents a variable to be inlined. */
  @AutoValue
  public abstract static class InlineVariable {
    public abstract String name();

    public abstract CelAbstractSyntaxTree ast();

    /**
     * Creates a new {@link InlineVariable} with the given name and AST.
     *
     * <p>The name must be a simple identifier or a qualified name (e.g. "a.b.c") and cannot be an
     * internal variable (starting with @).
     */
    public static InlineVariable of(String name, CelAbstractSyntaxTree ast) {
      if (name.startsWith("@")) {
        throw new IllegalArgumentException("Internal variables cannot be inlined: " + name);
      }
      return new AutoValue_InliningOptimizer_InlineVariable(name, ast);
    }
  }

  /** Options to configure how Inlining behaves. */
  @AutoValue
  public abstract static class InliningOptions {
    public abstract int maxIterationLimit();

    /** Builder for configuring the {@link InliningOptimizer.InliningOptions}. */
    @AutoValue.Builder
    public abstract static class Builder {

      /**
       * Limit the number of iteration while inlining variables. An exception is thrown if the
       * iteration count exceeds the set value.
       */
      public abstract InliningOptions.Builder maxIterationLimit(int value);

      public abstract InliningOptimizer.InliningOptions build();

      Builder() {}
    }

    /** Returns a new options builder with recommended defaults pre-configured. */
    public static InliningOptimizer.InliningOptions.Builder newBuilder() {
      return new AutoValue_InliningOptimizer_InliningOptions.Builder().maxIterationLimit(400);
    }

    InliningOptions() {}
  }

  private InliningOptimizer(
      InliningOptions options, ImmutableList<InlineVariable> inlineVariables) {
    this.inlineVariables = inlineVariables;
    this.astMutator = AstMutator.newInstance(options.maxIterationLimit());
  }
}
