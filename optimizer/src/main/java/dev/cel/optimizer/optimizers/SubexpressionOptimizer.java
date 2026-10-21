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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static dev.cel.optimizer.optimizers.DefaultOptimizerConstants.CEL_ATTRIBUTE_FUNCTION_NAME;
import static dev.cel.optimizer.optimizers.DefaultOptimizerConstants.CEL_HAS_FIELD_FUNCTION_NAME;
import static java.lang.Math.max;

import com.google.auto.value.AutoValue;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;
import com.google.common.base.Verify;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Multimaps;
import com.google.common.collect.Sets;
import com.google.common.collect.Streams;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import dev.cel.bundle.Cel;
import dev.cel.bundle.CelBuilder;
import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.CelMutableAst;
import dev.cel.common.CelMutableSource;
import dev.cel.common.CelSource;
import dev.cel.common.CelSource.Extension;
import dev.cel.common.CelSource.Extension.Component;
import dev.cel.common.CelSource.Extension.Version;
import dev.cel.common.CelValidationException;
import dev.cel.common.CelVarDecl;
import dev.cel.common.Operator;
import dev.cel.common.ast.CelBlock;
import dev.cel.common.ast.CelConstant;
import dev.cel.common.ast.CelExpr;
import dev.cel.common.ast.CelExpr.CelCall;
import dev.cel.common.ast.CelExpr.CelComprehension;
import dev.cel.common.ast.CelExpr.CelList;
import dev.cel.common.ast.CelExpr.ExprKind.Kind;
import dev.cel.common.ast.CelMutableExpr;
import dev.cel.common.ast.CelMutableExpr.CelMutableCall;
import dev.cel.common.ast.CelMutableExpr.CelMutableComprehension;
import dev.cel.common.ast.CelMutableExpr.CelMutableIdent;
import dev.cel.common.ast.CelMutableExpr.CelMutableList;
import dev.cel.common.ast.CelMutableExpr.CelMutableMap;
import dev.cel.common.ast.CelMutableExpr.CelMutableSelect;
import dev.cel.common.ast.CelMutableExpr.CelMutableStruct;
import dev.cel.common.ast.CelMutableExprConverter;
import dev.cel.common.navigation.CelNavigableExpr;
import dev.cel.common.navigation.CelNavigableExprUtil;
import dev.cel.common.navigation.CelNavigableMutableAst;
import dev.cel.common.navigation.CelNavigableMutableExpr;
import dev.cel.common.navigation.TraversalOrder;
import dev.cel.common.types.CelType;
import dev.cel.common.types.SimpleType;
import dev.cel.extensions.CelBindingsExtensions;
import dev.cel.optimizer.AstMutator;
import dev.cel.optimizer.AstMutator.MangledComprehensionAst;
import dev.cel.optimizer.CelAstOptimizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Performs Common Subexpression Elimination.
 *
 * <pre>
 * Subexpressions are extracted into `cel.@block` calls. For example, the expression below:
 *
 * {@code
 *    message.child.text_map[x].startsWith("hello") && message.child.text_map[x].endsWith("world")
 * }
 *
 * will be optimized into the following form:
 *
 * {@code
 *    cel.@block([message.child.text_map[x]],
 *        @index0.startsWith("hello") && @index0.endsWith("world"))
 * }
 * </pre>
 *
 * <p><b>Optimizer Ordering:</b> {@code SubexpressionOptimizer} does not support ASTs that have
 * already been optimized by {@link SelectOptimizer}.
 */
public final class SubexpressionOptimizer implements CelAstOptimizer {

  private static final SubexpressionOptimizer INSTANCE =
      new SubexpressionOptimizer(SubexpressionOptimizerOptions.newBuilder().build());
  private static final Extension CEL_BLOCK_AST_EXTENSION_TAG =
      Extension.create("cel_block", Version.of(1L, 1L), Component.COMPONENT_RUNTIME);

  @VisibleForTesting static final String MANGLED_COMPREHENSION_ITER_VAR_PREFIX = "@it";
  @VisibleForTesting static final String MANGLED_COMPREHENSION_ITER_VAR2_PREFIX = "@it2";
  @VisibleForTesting static final String MANGLED_COMPREHENSION_ACCU_VAR_PREFIX = "@ac";

  private final SubexpressionOptimizerOptions cseOptions;
  private final AstMutator astMutator;
  private final ImmutableSet<String> cseEliminableFunctions;

  /**
   * Returns a default instance of common subexpression elimination optimizer with preconfigured
   * defaults.
   */
  public static SubexpressionOptimizer getInstance() {
    return INSTANCE;
  }

  /**
   * Returns a new instance of common subexpression elimination optimizer configured with the
   * provided {@link SubexpressionOptimizerOptions}.
   */
  public static SubexpressionOptimizer newInstance(SubexpressionOptimizerOptions cseOptions) {
    return new SubexpressionOptimizer(cseOptions);
  }

  /**
   * {@inheritDoc}
   *
   * @throws IllegalStateException if {@code ast} contains select-optimized nodes ({@code
   *     cel.@attribute} or {@code cel.@hasField}), as common subexpression elimination must run
   *     before {@link SelectOptimizer}.
   */
  @Override
  public OptimizationResult optimize(CelAbstractSyntaxTree ast, Cel cel) {
    OptimizationResult result = optimizeUsingCelBlock(ast, cel);

    verifyOptimizedAstCorrectness(result.optimizedAst());

    return result;
  }

  private OptimizationResult optimizeUsingCelBlock(CelAbstractSyntaxTree ast, Cel cel) {
    boolean hasSelectOptimizedNodes =
        CelNavigableExpr.fromExpr(ast.getExpr())
            .allNodes()
            .filter(node -> node.getKind() == Kind.CALL)
            .map(node -> node.expr().call().function())
            .anyMatch(
                func ->
                    func.equals(CEL_ATTRIBUTE_FUNCTION_NAME)
                        || func.equals(CEL_HAS_FIELD_FUNCTION_NAME));
    checkState(!hasSelectOptimizedNodes, "SubexpressionOptimizer must run before SelectOptimizer.");

    CelMutableAst astToModify = CelMutableAst.fromCelAst(ast);
    if (!cseOptions.populateMacroCalls()) {
      astToModify.source().clearMacroCalls();
    }
    ImmutableMap<Long, CelType> nodeTypes = ast.getTypeMap();

    boolean simplified = simplifyDominatedSubexpressions(astToModify, nodeTypes);
    CelAbstractSyntaxTree fallbackAst =
        simplified
            ? astMutator
                .renumberIdsConsecutively(CelMutableAst.fromCelAst(astToModify.toParsedAst()))
                .toParsedAst()
            : ast;

    MangledComprehensionAst mangledComprehensionAst =
        astMutator.mangleComprehensionIdentifierNames(
            astToModify,
            MANGLED_COMPREHENSION_ITER_VAR_PREFIX,
            MANGLED_COMPREHENSION_ITER_VAR2_PREFIX,
            MANGLED_COMPREHENSION_ACCU_VAR_PREFIX);
    astToModify = mangledComprehensionAst.mutableAst();
    CelMutableSource sourceToModify = astToModify.source();

    int iterCount;
    long nextId = computeMaxId(astToModify);
    if (cseOptions.subexpressionMaxRecursionDepth() == 0) {
      nextId = maybeRewriteComplementaryNotEquals(astToModify, nodeTypes, nextId);
    }
    ArrayList<CelMutableExpr> subexpressions = new ArrayList<>();

    for (iterCount = 0; iterCount < cseOptions.iterationLimit(); iterCount++) {
      CelNavigableMutableAst navAst = CelNavigableMutableAst.fromAst(astToModify);
      ValueNumberer valueNumberer = new ValueNumberer(navAst.getRoot().expr(), nodeTypes);
      ImmutableList<CelMutableExpr> cseCandidates = getCseCandidates(navAst, valueNumberer);
      if (cseCandidates.isEmpty()) {
        break;
      }

      subexpressions.add(CelMutableExpr.newInstance(cseCandidates.get(0)));

      String blockIdentifier = CelBlock.INDEX_PREFIX + iterCount;

      // Candidates in navAst are mutated in-place, which implicitly updates astToModify. Macro
      // calls in sourceToModify store separate expression trees and must be updated independently.
      Map<Long, Long> replacementIds = new HashMap<>();
      for (CelMutableExpr candidate : cseCandidates) {
        long newId = ++nextId;
        replacementIds.put(candidate.id(), newId);
        replaceWithBlockIdent(candidate, newId, blockIdentifier);
      }
      updateMacroCalls(sourceToModify, replacementIds, blockIdentifier);
    }

    if (iterCount >= cseOptions.iterationLimit()) {
      throw new IllegalStateException("Max iteration count reached.");
    }

    if (iterCount == 0) {
      return OptimizationResult.create(fallbackAst);
    }

    ImmutableList.Builder<CelVarDecl> newVarDecls = ImmutableList.builder();

    // Add all mangled comprehension identifiers to the environment, so that the subexpressions can
    // retain context to them.
    mangledComprehensionAst
        .mangledComprehensionMap()
        .forEach(
            (name, type) -> {
              type.iterVarType()
                  .ifPresent(
                      iterVarType ->
                          newVarDecls.add(
                              CelVarDecl.newVarDeclaration(name.iterVarName(), iterVarType)));
              type.iterVar2Type()
                  .ifPresent(
                      iterVar2Type ->
                          newVarDecls.add(
                              CelVarDecl.newVarDeclaration(name.iterVar2Name(), iterVar2Type)));

              newVarDecls.add(CelVarDecl.newVarDeclaration(name.resultName(), type.resultType()));
            });

    // Type-check all sub-expressions then create new block index identifiers.
    newVarDecls.addAll(newBlockIndexVariableDeclarations(cel, newVarDecls.build(), subexpressions));

    // Wrap the optimized expression in cel.block
    astToModify =
        astMutator.wrapAstWithNewCelBlock(CelBlock.FUNCTION_NAME, astToModify, subexpressions);
    astToModify = astMutator.renumberIdsConsecutively(astToModify);

    // Tag the AST with cel.block designated as an extension
    CelAbstractSyntaxTree optimizedAst = tagAstExtension(astToModify.toParsedAst());

    return OptimizationResult.create(
        optimizedAst,
        newVarDecls.build(),
        ImmutableList.of(CelBindingsExtensions.CEL_BLOCK_FUNCTION_DECL));
  }

  /**
   * Asserts that the optimized AST has no correctness issues.
   *
   * @throws com.google.common.base.VerifyException if the optimized AST is malformed.
   */
  @VisibleForTesting
  static void verifyOptimizedAstCorrectness(CelAbstractSyntaxTree ast) {
    CelBlock celBlock = CelBlock.extract(ast).orElse(null);
    if (celBlock == null) {
      return;
    }

    verifyNoInvalidScopedMangledVariables(celBlock.expr());
  }

  private static void verifyNoInvalidScopedMangledVariables(CelExpr celExpr) {
    CelCall celBlockCall = celExpr.call();
    CelExpr blockBody = celBlockCall.args().get(1);

    ImmutableSet<String> allMangledVariablesInBlockBody =
        CelNavigableExpr.fromExpr(blockBody)
            .allNodes()
            .map(CelNavigableExpr::expr)
            .flatMap(SubexpressionOptimizer::extractMangledNames)
            .collect(toImmutableSet());

    CelList blockIndices = celBlockCall.args().get(0).list();
    for (CelExpr blockIndex : blockIndices.elements()) {
      ImmutableSet<String> indexDeclaredCompVariables =
          CelNavigableExpr.fromExpr(blockIndex)
              .allNodes()
              .map(CelNavigableExpr::expr)
              .filter(expr -> expr.getKind() == Kind.COMPREHENSION)
              .map(CelExpr::comprehension)
              .flatMap(comp -> Stream.of(comp.iterVar(), comp.iterVar2()))
              .filter(iter -> !Strings.isNullOrEmpty(iter))
              .collect(toImmutableSet());

      boolean containsIllegalDeclaration =
          CelNavigableExpr.fromExpr(blockIndex)
              .allNodes()
              .map(CelNavigableExpr::expr)
              .filter(expr -> expr.getKind() == Kind.IDENT)
              .map(expr -> expr.ident().name())
              .filter(SubexpressionOptimizer::isMangled)
              .anyMatch(
                  ident ->
                      !indexDeclaredCompVariables.contains(ident)
                          && allMangledVariablesInBlockBody.contains(ident));

      Verify.verify(
          !containsIllegalDeclaration,
          "Illegal declared reference to a comprehension variable found in block indices. Expr: %s",
          celExpr);
    }
  }

  private static Stream<String> extractMangledNames(CelExpr expr) {
    if (expr.getKind().equals(Kind.IDENT)) {
      String name = expr.ident().name();
      return isMangled(name) ? Stream.of(name) : Stream.empty();
    }
    if (expr.getKind().equals(Kind.COMPREHENSION)) {
      CelComprehension comp = expr.comprehension();
      return Stream.of(comp.iterVar(), comp.iterVar2(), comp.accuVar())
          .filter(x -> !Strings.isNullOrEmpty(x))
          .filter(SubexpressionOptimizer::isMangled);
    }
    return Stream.empty();
  }

  private static boolean isMangled(String name) {
    return name.startsWith(MANGLED_COMPREHENSION_ITER_VAR_PREFIX)
        || name.startsWith(MANGLED_COMPREHENSION_ITER_VAR2_PREFIX);
  }

  private static CelAbstractSyntaxTree tagAstExtension(CelAbstractSyntaxTree ast) {
    // Tag the extension
    CelSource.Builder celSourceBuilder =
        ast.getSource().toBuilder().addAllExtensions(CEL_BLOCK_AST_EXTENSION_TAG);

    return CelAbstractSyntaxTree.newParsedAst(ast.getExpr(), celSourceBuilder.build());
  }

  /**
   * Creates a list of numbered identifiers from the subexpressions that act as an indexer to
   * cel.block (ex: @index0, @index1..). Each subexpressions are type-checked, then its result type
   * is used as the new identifiers' types.
   */
  private static ImmutableList<CelVarDecl> newBlockIndexVariableDeclarations(
      Cel cel, ImmutableList<CelVarDecl> mangledVarDecls, List<CelMutableExpr> subexpressions) {
    // The resulting type of the subexpressions will likely be different from the
    // entire expression's expected result type.
    CelBuilder celBuilder = cel.toCelBuilder().setResultType(SimpleType.DYN);
    // Add the mangled comprehension variables to the environment for type-checking subexpressions
    // to succeed
    celBuilder.addVarDeclarations(mangledVarDecls);

    ImmutableList.Builder<CelVarDecl> varDeclBuilder = ImmutableList.builder();
    for (int i = 0; i < subexpressions.size(); i++) {
      CelMutableExpr subexpression = subexpressions.get(i);

      CelAbstractSyntaxTree subAst =
          CelAbstractSyntaxTree.newParsedAst(
              CelMutableExprConverter.fromMutableExpr(subexpression),
              CelSource.newBuilder().build());

      try {
        subAst = celBuilder.build().check(subAst).getAst();
      } catch (CelValidationException e) {
        throw new IllegalStateException("Failed to type-check subexpression", e);
      }

      CelVarDecl indexVar = CelVarDecl.newVarDeclaration("@index" + i, subAst.getResultType());
      celBuilder.addVarDeclarations(indexVar);
      varDeclBuilder.add(indexVar);
    }

    return varDeclBuilder.build();
  }

  private static void updateMacroCalls(
      CelMutableSource source, Map<Long, Long> replacementIds, String blockIdentifier) {
    for (CelMutableExpr macroCallExpr : source.getMacroCalls().values()) {
      CelNavigableMutableExpr.fromExpr(macroCallExpr)
          .allNodes()
          .map(CelNavigableMutableExpr::expr)
          .forEach(
              expr -> {
                Long newId = replacementIds.get(expr.id());
                if (newId != null) {
                  replaceWithBlockIdent(expr, newId, blockIdentifier);
                }
              });
    }
  }

  private static void replaceWithBlockIdent(
      CelMutableExpr expr, long newId, String blockIdentifier) {
    expr.setId(newId);
    expr.setIdent(CelMutableIdent.create(blockIdentifier));
  }

  private static long computeMaxId(CelMutableAst ast) {
    long maxId = CelNavigableMutableAst.fromAst(ast).getRoot().maxId();
    for (CelMutableExpr macroCall : ast.source().getMacroCalls().values()) {
      maxId = max(maxId, CelNavigableMutableExpr.fromExpr(macroCall).maxId());
    }
    return maxId;
  }

  private ImmutableList<CelMutableExpr> getCseCandidates(
      CelNavigableMutableAst navAst, ValueNumberer valueNumberer) {
    if (cseOptions.subexpressionMaxRecursionDepth() > 0) {
      return getCseCandidatesWithRecursionDepth(
          navAst, cseOptions.subexpressionMaxRecursionDepth(), valueNumberer);
    } else {
      return getCseCandidatesWithCommonSubexpr(navAst, valueNumberer);
    }
  }

  /**
   * Retrieves all subexpr candidates based on the recursion limit even if there's no duplicate
   * subexpr found.
   */
  private ImmutableList<CelMutableExpr> getCseCandidatesWithRecursionDepth(
      CelNavigableMutableAst navAst, int recursionLimit, ValueNumberer valueNumberer) {
    checkArgument(recursionLimit > 0);
    Set<CelMutableExpr> ineligibleExprs = getIneligibleExprsFromComprehensionBranches(navAst);
    ImmutableList<CelNavigableMutableExpr> descendants =
        navAst
            .getRoot()
            .descendants(TraversalOrder.PRE_ORDER)
            .filter(node -> node.height() <= recursionLimit)
            .filter(node -> canEliminate(node, ineligibleExprs))
            .sorted(Comparator.comparingInt(CelNavigableMutableExpr::height).reversed())
            .collect(toImmutableList());
    if (descendants.isEmpty()) {
      return ImmutableList.of();
    }

    ImmutableList<CelMutableExpr> cseCandidates =
        getCseCandidatesWithCommonSubexpr(descendants, ineligibleExprs, valueNumberer);
    if (!cseCandidates.isEmpty()) {
      return cseCandidates;
    }

    // If there's no common subexpr, just return the one with the highest height that's still below
    // the recursion limit, but only if it actually needs to be extracted due to exceeding the
    // recursion limit.
    boolean astHasMoreExtractableSubexprs =
        navAst
            .getRoot()
            .allNodes(TraversalOrder.POST_ORDER)
            .filter(node -> node.height() > recursionLimit)
            .anyMatch(node -> canEliminate(node, ineligibleExprs));
    if (astHasMoreExtractableSubexprs) {
      return ImmutableList.of(descendants.get(0).expr());
    }

    // The height of the remaining subexpression is already below the recursion limit. No need to
    // extract.
    return ImmutableList.of();
  }

  private ImmutableList<CelMutableExpr> getCseCandidatesWithCommonSubexpr(
      CelNavigableMutableAst navAst, ValueNumberer valueNumberer) {
    Set<CelMutableExpr> ineligibleExprs = getIneligibleExprsFromComprehensionBranches(navAst);
    ImmutableList<CelNavigableMutableExpr> allNodes =
        navAst.getRoot().allNodes(TraversalOrder.PRE_ORDER).collect(toImmutableList());

    return getCseCandidatesWithCommonSubexpr(allNodes, ineligibleExprs, valueNumberer);
  }

  private ImmutableList<CelMutableExpr> getCseCandidatesWithCommonSubexpr(
      ImmutableList<CelNavigableMutableExpr> allNodes,
      Set<CelMutableExpr> ineligibleExprs,
      ValueNumberer valueNumberer) {
    ImmutableListMultimap<Integer, CelNavigableMutableExpr> nodesByHeight =
        Multimaps.index(allNodes, CelNavigableMutableExpr::height);
    Set<Integer> seenVns = new HashSet<>();
    for (CelNavigableMutableExpr node : allNodes) {
      ImmutableList<CelNavigableMutableExpr> sameHeightNodes = nodesByHeight.get(node.height());
      if (sameHeightNodes.size() < 2 || !canEliminate(node, ineligibleExprs)) {
        continue;
      }
      int vn = valueNumberer.vnOf(node.expr());
      if (!seenVns.add(vn)) {
        return sameHeightNodes.stream()
            .filter(candidate -> canEliminate(candidate, ineligibleExprs))
            .map(CelNavigableMutableExpr::expr)
            .filter(expr -> valueNumberer.vnOf(expr) == vn)
            .collect(toImmutableList());
      }
    }

    return ImmutableList.of();
  }

  // Rewrites eligible _!=_(a, b) calls in-place to !(_==_(a, b)) when an eligible _==_(a, b) or
  // _==_(b, a) is also present in the AST, enabling CSE to share a single _==_ block slot.
  private long maybeRewriteComplementaryNotEquals(
      CelMutableAst astToModify, ImmutableMap<Long, CelType> nodeTypes, long nextId) {
    CelNavigableMutableAst navAst = CelNavigableMutableAst.fromAst(astToModify);
    ValueNumberer valueNumberer = new ValueNumberer(navAst.getRoot().expr(), nodeTypes);
    Set<CelMutableExpr> ineligibleExprs = getIneligibleExprsFromComprehensionBranches(navAst);
    ImmutableList<CelNavigableMutableExpr> allNodes =
        navAst.getRoot().allNodes(TraversalOrder.PRE_ORDER).collect(toImmutableList());
    Set<Integer> equalsVns = new HashSet<>();
    List<CelNavigableMutableExpr> notEqualsNodes = new ArrayList<>();
    for (CelNavigableMutableExpr node : allNodes) {
      if (node.getKind() != Kind.CALL || !canEliminate(node, ineligibleExprs)) {
        continue;
      }
      String func = node.expr().call().function();
      if (func.equals(Operator.EQUALS.getFunction())) {
        equalsVns.add(valueNumberer.vnOf(node.expr()));
      } else if (func.equals(Operator.NOT_EQUALS.getFunction())) {
        notEqualsNodes.add(node);
      }
    }
    for (CelNavigableMutableExpr node : notEqualsNodes) {
      CelMutableCall call = node.expr().call();
      int eqVn =
          valueNumberer.binaryCallVn(
              Operator.EQUALS.getFunction(),
              valueNumberer.vnOf(call.args().get(0)),
              valueNumberer.vnOf(call.args().get(1)));
      if (!equalsVns.contains(eqVn)) {
        continue;
      }
      CelMutableExpr innerEquals =
          CelMutableExpr.ofCall(
              ++nextId,
              CelMutableCall.create(
                  Operator.EQUALS.getFunction(), call.args().get(0), call.args().get(1)));
      node.expr().setCall(CelMutableCall.create(Operator.LOGICAL_NOT.getFunction(), innerEquals));
    }
    return nextId;
  }

  private boolean simplifyDominatedSubexpressions(
      CelMutableAst astToModify, ImmutableMap<Long, CelType> nodeTypes) {
    ValueNumberer valueNumberer = new ValueNumberer(astToModify.expr(), nodeTypes);
    DominatorSimplifier simplifier =
        new DominatorSimplifier(valueNumberer, nodeTypes, astToModify.source());
    simplifier.simplifySubtree(astToModify.expr());
    return simplifier.modCount > 0;
  }

  private static boolean isPotentiallyFloat(
      CelMutableExpr expr, ImmutableMap<Long, CelType> nodeTypes) {
    CelType type = nodeTypes.getOrDefault(expr.id(), SimpleType.DYN);
    return type.equals(SimpleType.DOUBLE) || type.equals(SimpleType.DYN);
  }

  private boolean canEliminate(
      CelNavigableMutableExpr navigableExpr, Set<CelMutableExpr> ineligibleExprs) {
    return !navigableExpr.getKind().equals(Kind.CONSTANT)
        && !navigableExpr.getKind().equals(Kind.IDENT)
        // Exclude empty lists (cel.bind sets this for iterRange).
        && !(navigableExpr.getKind().equals(Kind.LIST)
            && navigableExpr.expr().list().elements().isEmpty())
        && !ineligibleExprs.contains(navigableExpr.expr())
        && containsEliminableFunctionOnly(navigableExpr)
        && containsValidComprehensionIdents(navigableExpr);
  }

  private static boolean containsValidComprehensionIdents(CelNavigableMutableExpr navExpr) {
    // For nested comprehensions of form [1].exists(x, [2].exists(y, x == y)), the inner
    // comprehension [2].exists(y, x == y) should not be extracted out into a block index, as it
    // causes issues with scoping. Non-comprehension subexpressions cannot reference any mangled
    // comprehension variables.
    Predicate<String> isInvalidIdent =
        navExpr.getKind().equals(Kind.COMPREHENSION)
            ? CelNavigableExprUtil.getEnclosingComprehensionVariables(navExpr)::contains
            : name ->
                name.startsWith(MANGLED_COMPREHENSION_ITER_VAR_PREFIX)
                    || name.startsWith(MANGLED_COMPREHENSION_ACCU_VAR_PREFIX);
    return navExpr
        .descendants()
        .filter(node -> node.getKind().equals(Kind.IDENT))
        .map(node -> node.expr().ident().name())
        .noneMatch(isInvalidIdent);
  }

  /**
   * Collects a set of nodes that are not eligible to be optimized from comprehension branches.
   *
   * <p>All nodes from accumulator initializer and loop condition are not eligible to be optimized
   * as that can interfere with scoping of shadowed variables.
   */
  private static Set<CelMutableExpr> getIneligibleExprsFromComprehensionBranches(
      CelNavigableMutableAst navAst) {
    // Use reference identity to avoid recursive O(subtree) CelMutableExpr.hashCode() on every
    // contains() check and structural equality collisions on unnumbered (id=0) ASTs.
    Set<CelMutableExpr> ineligibleExprs = Sets.newIdentityHashSet();
    navAst
        .getRoot()
        .allNodes()
        .filter(node -> node.getKind().equals(Kind.COMPREHENSION))
        .forEach(
            node ->
                Streams.concat(
                        CelNavigableMutableExpr.fromExpr(node.expr().comprehension().accuInit())
                            .allNodes(),
                        CelNavigableMutableExpr.fromExpr(
                                node.expr().comprehension().loopCondition())
                            .allNodes())
                    .map(CelNavigableMutableExpr::expr)
                    .forEach(ineligibleExprs::add));

    return ineligibleExprs;
  }

  private boolean containsEliminableFunctionOnly(CelNavigableMutableExpr navigableExpr) {
    return navigableExpr
        .allNodes()
        .allMatch(
            node -> {
              if (node.getKind().equals(Kind.CALL)) {
                return cseEliminableFunctions.contains(node.expr().call().function());
              }

              return true;
            });
  }

  // Simplifies subexpressions using path-sensitive boolean predicate facts established by
  // dominating ternary conditions.
  private final class DominatorSimplifier {
    private final ValueNumberer valueNumberer;
    private final ImmutableMap<Long, CelType> nodeTypes;
    private final CelMutableSource source;
    private final Map<Integer, Boolean> knownBoolByVn;
    private int modCount;

    private CelMutableExpr simplifySubtree(CelMutableExpr expr) {
      if (expr.getKind() != Kind.CONSTANT) {
        Boolean knownBool = knownBoolByVn.get(valueNumberer.vnOf(expr));
        if (knownBool != null) {
          source.clearMacroCall(expr.id());
          expr.setConstant(CelConstant.ofValue(knownBool));
          modCount++;
          return expr;
        }
      }

      int prevModCount = modCount;
      switch (expr.getKind()) {
        case CALL:
          simplifyCallChildren(expr);
          break;
        case SELECT:
          expr.select().setOperand(simplifySubtree(expr.select().operand()));
          break;
        case LIST:
          expr.list().elements().replaceAll(this::simplifySubtree);
          break;
        case MAP:
          for (CelMutableMap.Entry entry : expr.map().entries()) {
            entry.setKey(simplifySubtree(entry.key()));
            entry.setValue(simplifySubtree(entry.value()));
          }
          break;
        case STRUCT:
          for (CelMutableStruct.Entry entry : expr.struct().entries()) {
            entry.setValue(simplifySubtree(entry.value()));
          }
          break;
        case COMPREHENSION:
          CelMutableComprehension comp = expr.comprehension();
          comp.setIterRange(simplifySubtree(comp.iterRange()));
          if (comp.loopStep().getKind() == Kind.CALL) {
            simplifyCallChildren(comp.loopStep());
          }
          break;
        default:
          break;
      }

      if (modCount != prevModCount && expr.getKind() == Kind.CALL) {
        return foldModifiedCall(expr);
      }
      return expr;
    }

    private void simplifyCallChildren(CelMutableExpr expr) {
      CelMutableCall call = expr.call();
      List<CelMutableExpr> args = call.args();
      if (call.function().equals(Operator.CONDITIONAL.getFunction())) {
        CelMutableExpr cond = simplifySubtree(args.get(0));
        args.set(0, cond);
        Map<Integer, Boolean> savedBools = new HashMap<>(knownBoolByVn);

        recordTruth(cond, true);
        args.set(1, simplifySubtree(args.get(1)));

        knownBoolByVn.clear();
        knownBoolByVn.putAll(savedBools);

        recordTruth(cond, false);
        args.set(2, simplifySubtree(args.get(2)));

        knownBoolByVn.clear();
        knownBoolByVn.putAll(savedBools);
        return;
      }
      call.target().ifPresent(target -> call.setTarget(simplifySubtree(target)));
      args.replaceAll(this::simplifySubtree);
    }

    private CelMutableExpr foldModifiedCall(CelMutableExpr expr) {
      CelMutableCall call = expr.call();
      if (!call.function().equals(Operator.CONDITIONAL.getFunction())) {
        return expr;
      }
      List<CelMutableExpr> args = call.args();
      CelMutableExpr cond = args.get(0);
      if (cond.getKind() != Kind.CONSTANT) {
        return expr;
      }
      return cond.constant().booleanValue() ? args.get(1) : args.get(2);
    }

    private void recordTruth(CelMutableExpr expr, boolean isTrue) {
      if (expr.getKind() == Kind.CALL) {
        CelMutableCall call = expr.call();
        String func = call.function();
        List<CelMutableExpr> args = call.args();
        if (func.equals(Operator.LOGICAL_NOT.getFunction())) {
          recordTruth(args.get(0), !isTrue);
          return;
        }
        if (func.equals((isTrue ? Operator.LOGICAL_AND : Operator.LOGICAL_OR).getFunction())) {
          args.forEach(arg -> recordTruth(arg, isTrue));
          return;
        }
      }

      if (!containsEliminableFunctionOnly(CelNavigableMutableExpr.fromExpr(expr))) {
        return;
      }

      int vn = valueNumberer.vnOf(expr);
      knownBoolByVn.put(vn, isTrue);

      if (expr.getKind() != Kind.CALL) {
        return;
      }
      CelMutableCall call = expr.call();
      String func = call.function();
      List<CelMutableExpr> args = call.args();
      if (func.equals(Operator.EQUALS.getFunction())
          || func.equals(Operator.NOT_EQUALS.getFunction())) {
        boolean isEqualsOp = func.equals(Operator.EQUALS.getFunction());
        recordBinaryBool(
            (isEqualsOp ? Operator.NOT_EQUALS : Operator.EQUALS).getFunction(),
            valueNumberer.vnOf(args.get(0)),
            valueNumberer.vnOf(args.get(1)),
            !isTrue);
        return;
      }

      boolean isStrict;
      boolean swapOperands;
      if (func.equals(Operator.LESS.getFunction())) {
        isStrict = true;
        swapOperands = false;
      } else if (func.equals(Operator.GREATER.getFunction())) {
        isStrict = true;
        swapOperands = true;
      } else if (func.equals(Operator.LESS_EQUALS.getFunction())) {
        isStrict = false;
        swapOperands = false;
      } else if (func.equals(Operator.GREATER_EQUALS.getFunction())) {
        isStrict = false;
        swapOperands = true;
      } else {
        return;
      }

      CelMutableExpr lhs = args.get(0);
      CelMutableExpr rhs = args.get(1);
      int lhsVn = valueNumberer.vnOf(lhs);
      int rhsVn = valueNumberer.vnOf(rhs);
      int aVn = swapOperands ? rhsVn : lhsVn;
      int bVn = swapOperands ? lhsVn : rhsVn;
      if (isTrue) {
        recordOrderFacts(aVn, bVn, isStrict);
      } else if (!isPotentiallyFloat(lhs, nodeTypes) && !isPotentiallyFloat(rhs, nodeTypes)) {
        // Under a total order (no NaN), !(a < b) <=> b <= a and !(a <= b) <=> b < a.
        recordOrderFacts(bVn, aVn, !isStrict);
      }
    }

    private void recordOrderFacts(int aVn, int bVn, boolean isStrict) {
      // a < b (or a <= b) == true guarantees neither operand is NaN.
      recordBinaryBool(Operator.LESS_EQUALS.getFunction(), aVn, bVn, true);
      recordBinaryBool(Operator.LESS.getFunction(), bVn, aVn, false);
      if (isStrict) {
        recordBinaryBool(Operator.LESS.getFunction(), aVn, bVn, true);
        recordBinaryBool(Operator.LESS_EQUALS.getFunction(), bVn, aVn, false);
        recordBinaryBool(Operator.EQUALS.getFunction(), aVn, bVn, false);
        recordBinaryBool(Operator.NOT_EQUALS.getFunction(), aVn, bVn, true);
      }
    }

    private void recordBinaryBool(String func, int arg0Vn, int arg1Vn, boolean value) {
      knownBoolByVn.put(valueNumberer.binaryCallVn(func, arg0Vn, arg1Vn), value);
    }

    private DominatorSimplifier(
        ValueNumberer valueNumberer,
        ImmutableMap<Long, CelType> nodeTypes,
        CelMutableSource source) {
      this.valueNumberer = valueNumberer;
      this.nodeTypes = nodeTypes;
      this.source = source;
      this.knownBoolByVn = new HashMap<>();
      this.modCount = 0;
    }
  }

  // Bottom-up hash-consing value numberer with algebraic canonicalization for commutative and
  // converse operators and De Bruijn level scoping for comprehension variables.
  private static final class ValueNumberer {
    private static final ImmutableSet<String> ALWAYS_COMMUTATIVE_FUNCTIONS =
        ImmutableSet.of(
            Operator.EQUALS.getFunction(),
            Operator.NOT_EQUALS.getFunction(),
            Operator.LOGICAL_AND.getFunction(),
            Operator.LOGICAL_OR.getFunction());
    private static final ImmutableSet<CelType> NUMERIC_TYPES =
        ImmutableSet.of(SimpleType.INT, SimpleType.UINT, SimpleType.DOUBLE);
    private static final ImmutableSet<CelType> COMMUTATIVE_ADD_TYPES =
        ImmutableSet.of(
            SimpleType.INT,
            SimpleType.UINT,
            SimpleType.DOUBLE,
            SimpleType.DURATION,
            SimpleType.TIMESTAMP);

    private final ImmutableMap<Long, CelType> nodeTypes;
    private final Map<Object, Integer> keyToVn;
    private final IdentityHashMap<CelMutableExpr, Integer> exprToVn;
    private ImmutableSet<String> scopedVars;

    private int vnOf(CelMutableExpr expr) {
      return checkNotNull(exprToVn.get(expr));
    }

    private int computeVn(CelMutableExpr expr) {
      int vn = internKey(buildNodeKey(expr));
      exprToVn.put(expr, vn);
      return vn;
    }

    private int binaryCallVn(String function, int arg0Vn, int arg1Vn) {
      return internKey(buildBinaryCallKey(function, arg0Vn, arg1Vn, SimpleType.BOOL));
    }

    private int internKey(Object key) {
      return keyToVn.computeIfAbsent(key, k -> keyToVn.size());
    }

    private Object buildNodeKey(CelMutableExpr expr) {
      switch (expr.getKind()) {
        case CONSTANT:
          return ImmutableList.of(Kind.CONSTANT, expr.constant());
        case IDENT:
          String name = expr.ident().name();
          return ImmutableList.of(Kind.IDENT, name, scopedVars.contains(name));
        case SELECT:
          CelMutableSelect select = expr.select();
          return ImmutableList.of(
              Kind.SELECT, computeVn(select.operand()), select.field(), select.testOnly());
        case CALL:
          return buildCallKey(expr);
        case LIST:
          CelMutableList list = expr.list();
          ImmutableList.Builder<Integer> elemVns =
              ImmutableList.builderWithExpectedSize(list.elements().size());
          for (CelMutableExpr elem : list.elements()) {
            elemVns.add(computeVn(elem));
          }
          return ImmutableList.of(Kind.LIST, elemVns.build(), list.optionalIndices());
        case MAP:
          CelMutableMap map = expr.map();
          ImmutableList.Builder<Object> entryVns =
              ImmutableList.builderWithExpectedSize(map.entries().size());
          for (CelMutableMap.Entry entry : map.entries()) {
            entryVns.add(
                ImmutableList.of(
                    computeVn(entry.key()), computeVn(entry.value()), entry.optionalEntry()));
          }
          return ImmutableList.of(Kind.MAP, entryVns.build());
        case STRUCT:
          CelMutableStruct struct = expr.struct();
          ImmutableList.Builder<Object> fieldVns =
              ImmutableList.builderWithExpectedSize(struct.entries().size());
          for (CelMutableStruct.Entry entry : struct.entries()) {
            fieldVns.add(
                ImmutableList.of(
                    entry.fieldKey(), computeVn(entry.value()), entry.optionalEntry()));
          }
          return ImmutableList.of(Kind.STRUCT, struct.messageName(), fieldVns.build());
        case COMPREHENSION:
          CelMutableComprehension comp = expr.comprehension();
          int iterRangeVn = computeVn(comp.iterRange());
          int accuInitVn = computeVn(comp.accuInit());
          ImmutableSet<String> savedScope = scopedVars;
          scopedVars = ImmutableSet.of(comp.accuVar(), comp.iterVar(), comp.iterVar2());
          int loopConditionVn = computeVn(comp.loopCondition());
          int loopStepVn = computeVn(comp.loopStep());
          int resultVn = computeVn(comp.result());
          scopedVars = savedScope;
          return ImmutableList.of(
              Kind.COMPREHENSION,
              comp.iterVar(),
              comp.iterVar2(),
              comp.accuVar(),
              iterRangeVn,
              accuInitVn,
              loopConditionVn,
              loopStepVn,
              resultVn);
        default:
          throw new IllegalArgumentException("Unexpected ExprKind: " + expr.getKind());
      }
    }

    private Object buildCallKey(CelMutableExpr expr) {
      CelMutableCall call = expr.call();
      String function = call.function();
      List<CelMutableExpr> args = call.args();
      if (!call.target().isPresent() && args.size() == 2) {
        return buildBinaryCallKey(
            function,
            computeVn(args.get(0)),
            computeVn(args.get(1)),
            nodeTypes.getOrDefault(expr.id(), SimpleType.DYN));
      }
      Optional<Integer> targetVn = call.target().map(this::computeVn);
      ImmutableList.Builder<Integer> argVns = ImmutableList.builderWithExpectedSize(args.size());
      for (CelMutableExpr arg : args) {
        argVns.add(computeVn(arg));
      }
      return ImmutableList.of(Kind.CALL, targetVn, function, argVns.build());
    }

    private static Object buildBinaryCallKey(
        String function, int arg0Vn, int arg1Vn, CelType exprType) {
      String canonicalFunc = function;
      int firstVn = arg0Vn;
      int secondVn = arg1Vn;
      if (function.equals(Operator.GREATER.getFunction())) {
        canonicalFunc = Operator.LESS.getFunction();
        firstVn = arg1Vn;
        secondVn = arg0Vn;
      } else if (function.equals(Operator.GREATER_EQUALS.getFunction())) {
        canonicalFunc = Operator.LESS_EQUALS.getFunction();
        firstVn = arg1Vn;
        secondVn = arg0Vn;
      } else if (isCommutativeBinaryCall(function, exprType) && firstVn > secondVn) {
        firstVn = arg1Vn;
        secondVn = arg0Vn;
      }
      return ImmutableList.of(
          Kind.CALL, Optional.empty(), canonicalFunc, ImmutableList.of(firstVn, secondVn));
    }

    private static boolean isCommutativeBinaryCall(String function, CelType exprType) {
      return ALWAYS_COMMUTATIVE_FUNCTIONS.contains(function)
          || (function.equals(Operator.MULTIPLY.getFunction()) && NUMERIC_TYPES.contains(exprType))
          || (function.equals(Operator.ADD.getFunction())
              && COMMUTATIVE_ADD_TYPES.contains(exprType));
    }

    @SuppressWarnings("ValueClassIdentity") // Only keys (CelMutableExpr) use reference identity
    private ValueNumberer(CelMutableExpr root, ImmutableMap<Long, CelType> nodeTypes) {
      this.nodeTypes = nodeTypes;
      this.keyToVn = new HashMap<>();
      this.exprToVn = new IdentityHashMap<>();
      this.scopedVars = ImmutableSet.of();
      computeVn(root);
    }
  }

  /** Options to configure how Common Subexpression Elimination behave. */
  @AutoValue
  public abstract static class SubexpressionOptimizerOptions {
    public abstract int iterationLimit();

    public abstract boolean populateMacroCalls();

    public abstract boolean enableCelBlock();

    public abstract int subexpressionMaxRecursionDepth();

    public abstract ImmutableSet<String> eliminableFunctions();

    /** Builder for configuring the {@link SubexpressionOptimizerOptions}. */
    @AutoValue.Builder
    public abstract static class Builder {

      /**
       * Limit the number of iteration while performing CSE. An exception is thrown if the iteration
       * count exceeds the set value.
       */
      public abstract Builder iterationLimit(int value);

      /**
       * Populate the macro_calls map in source_info with macro calls on the resulting optimized
       * AST.
       */
      public abstract Builder populateMacroCalls(boolean value);

      /**
       * @deprecated This option is a no-op. cel.@block is always enabled.
       */
      @Deprecated
      public abstract Builder enableCelBlock(boolean value);

      /**
       * Ensures all extracted subexpressions do not exceed the maximum depth of designated value.
       * The purpose of this is to guarantee evaluation and deserialization safety by preventing
       * deeply nested ASTs. The trade-off is increased memory usage due to memoizing additional
       * block indices during lazy evaluation.
       *
       * <p>As a general note, root of a node has a depth of 0. An expression `x.y.z` has a depth of
       * 2.
       *
       * <p>Note that expressions containing no common subexpressions may become a candidate for
       * extraction to satisfy the max depth requirement.
       *
       * <p>This is a no-op if the configured value is less than 1, or no subexpression needs to be
       * extracted because the entire expression is already under the designated limit.
       *
       * <p>Examples:
       *
       * <ol>
       *   <li>a.b.c with depth 1 -> cel.@block([x.b, @index0.c], @index1)
       *   <li>a.b.c with depth 3 -> a.b.c
       *   <li>a.b + a.b.c.d with depth 3 -> cel.@block([a.b, @index0.c.d], @index0 + @index1)
       * </ol>
       *
       * <p>
       */
      public abstract Builder subexpressionMaxRecursionDepth(int value);

      abstract ImmutableSet.Builder<String> eliminableFunctionsBuilder();

      /**
       * Adds a collection of custom functions that will be a candidate for common subexpression
       * elimination. By default, standard functions are eliminable.
       *
       * <p>Note that the implementation of custom functions must be free of side effects.
       */
      @CanIgnoreReturnValue
      public Builder addEliminableFunctions(Iterable<String> functions) {
        checkNotNull(functions);
        this.eliminableFunctionsBuilder().addAll(functions);
        return this;
      }

      /** See {@link #addEliminableFunctions(Iterable)}. */
      @CanIgnoreReturnValue
      public Builder addEliminableFunctions(String... functions) {
        return addEliminableFunctions(Arrays.asList(functions));
      }

      public abstract SubexpressionOptimizerOptions build();

      Builder() {}
    }

    abstract Builder toBuilder();

    /** Returns a new options builder with recommended defaults pre-configured. */
    public static Builder newBuilder() {
      return new AutoValue_SubexpressionOptimizer_SubexpressionOptimizerOptions.Builder()
          .iterationLimit(500)
          .enableCelBlock(true)
          .populateMacroCalls(false)
          .subexpressionMaxRecursionDepth(0);
    }

    SubexpressionOptimizerOptions() {}
  }

  private SubexpressionOptimizer(SubexpressionOptimizerOptions cseOptions) {
    this.cseOptions = cseOptions;
    this.astMutator = AstMutator.newInstance(cseOptions.iterationLimit());
    this.cseEliminableFunctions =
        ImmutableSet.<String>builder()
            .addAll(DefaultOptimizerConstants.CEL_CANONICAL_FUNCTIONS)
            .addAll(cseOptions.eliminableFunctions())
            .build();
  }
}
