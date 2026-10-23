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
import com.google.common.collect.HashMultiset;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Multiset;
import com.google.common.collect.Sets;
import com.google.common.collect.Streams;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import dev.cel.bundle.Cel;
import dev.cel.bundle.CelBuilder;
import dev.cel.checker.CelStandardDeclarations.StandardFunction.Overload.Arithmetic;
import dev.cel.checker.CelStandardDeclarations.StandardFunction.Overload.BooleanOperator;
import dev.cel.checker.CelStandardDeclarations.StandardFunction.Overload.Comparison;
import dev.cel.checker.CelStandardDeclarations.StandardFunction.Overload.Relation;
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
import dev.cel.common.ast.CelReference;
import dev.cel.common.navigation.CelNavigableExpr;
import dev.cel.common.navigation.CelNavigableExprUtil;
import dev.cel.common.navigation.CelNavigableMutableAst;
import dev.cel.common.navigation.CelNavigableMutableExpr;
import dev.cel.common.navigation.TraversalOrder;
import dev.cel.common.types.SimpleType;
import dev.cel.extensions.CelBindingsExtensions;
import dev.cel.optimizer.AstMutator;
import dev.cel.optimizer.AstMutator.MangledComprehensionAst;
import dev.cel.optimizer.CelAstOptimizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    MangledComprehensionAst mangledComprehensionAst =
        astMutator.mangleComprehensionIdentifierNames(
            astToModify,
            MANGLED_COMPREHENSION_ITER_VAR_PREFIX,
            MANGLED_COMPREHENSION_ITER_VAR2_PREFIX,
            MANGLED_COMPREHENSION_ACCU_VAR_PREFIX);
    astToModify = mangledComprehensionAst.mutableAst();
    CelMutableSource sourceToModify = astToModify.source();

    int iterCount;
    long nextId = 0;
    ArrayList<CelMutableExpr> subexpressions = new ArrayList<>();

    for (iterCount = 0; iterCount < cseOptions.iterationLimit(); iterCount++) {
      CelNavigableMutableAst navAst = CelNavigableMutableAst.fromAst(astToModify);
      ValueNumberer valueNumberer =
          new ValueNumberer(navAst.getRoot().expr(), ast.getReferenceMap());
      ImmutableList<CelMutableExpr> cseCandidates = getCseCandidates(navAst, valueNumberer);
      if (cseCandidates.isEmpty()) {
        break;
      }
      if (iterCount == 0) {
        nextId = navAst.getRoot().maxId();
        for (CelMutableExpr macroCall : sourceToModify.getMacroCalls().values()) {
          nextId = max(nextId, CelNavigableMutableExpr.fromExpr(macroCall).maxId());
        }
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
      // No modification has been made.
      return OptimizationResult.create(ast);
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

  private ImmutableList<CelMutableExpr> getCseCandidates(
      CelNavigableMutableAst navAst, ValueNumberer valueNumberer) {
    Set<CelMutableExpr> ineligibleExprs = getIneligibleExprsFromComprehensionBranches(navAst);
    ImmutableList<CelNavigableMutableExpr> allNodes =
        navAst.getRoot().allNodes(TraversalOrder.PRE_ORDER).collect(toImmutableList());
    // Loop-invariant subexpressions are extracted even if they occur once: block slots are lazily
    // memoized, so they then run at most once and only if reached, instead of once per iteration.
    @SuppressWarnings("ValueClassIdentity") // Integer values are only unboxed, never compared.
    IdentityHashMap<CelMutableExpr, Integer> loopDepths = new IdentityHashMap<>();
    for (CelNavigableMutableExpr node : allNodes) {
      if (node.getKind().equals(Kind.COMPREHENSION)) {
        CelNavigableMutableExpr.fromExpr(node.expr().comprehension().loopStep())
            .allNodes()
            .forEach(step -> loopDepths.merge(step.expr(), 1, Integer::sum));
      }
    }
    Set<CelMutableExpr> eligibleExprs = getEligibleExprs(allNodes, ineligibleExprs);
    Multiset<Integer> vnCounts = HashMultiset.create();
    eligibleExprs.forEach(expr -> vnCounts.add(valueNumberer.vnOf(expr)));
    Set<Integer> seenVns = new HashSet<>();
    for (CelNavigableMutableExpr node : allNodes) {
      CelMutableExpr expr = node.expr();
      if (eligibleExprs.contains(expr)
          && (loopDepths.containsKey(expr) || !seenVns.add(valueNumberer.vnOf(expr)))) {
        int vn =
            valueNumberer.vnOf(
                extractionTarget(node, loopDepths, vnCounts, valueNumberer, eligibleExprs).expr());
        return allNodes.stream()
            .map(CelNavigableMutableExpr::expr)
            .filter(e -> eligibleExprs.contains(e) && valueNumberer.vnOf(e) == vn)
            .collect(toImmutableList());
      }
    }

    return ImmutableList.of();
  }

  // Slots are never revisited, so before extracting `node` this first extracts any eligible
  // descendant that also occurs outside of it, or (for a loop-step node) that is invariant to a
  // loop nested inside it.
  private static CelNavigableMutableExpr extractionTarget(
      CelNavigableMutableExpr node,
      IdentityHashMap<CelMutableExpr, Integer> loopDepths,
      Multiset<Integer> vnCounts,
      ValueNumberer valueNumberer,
      Set<CelMutableExpr> eligibleExprs) {
    ImmutableList<CelNavigableMutableExpr> descendants =
        node.descendants(TraversalOrder.PRE_ORDER)
            .filter(d -> eligibleExprs.contains(d.expr()))
            .collect(toImmutableList());
    Multiset<Integer> innerVnCounts = HashMultiset.create();
    descendants.forEach(d -> innerVnCounts.add(valueNumberer.vnOf(d.expr())));
    // Equivalent copies of `node` are extracted along with it, so occurrences inside them are fine.
    int copies = vnCounts.count(valueNumberer.vnOf(node.expr()));
    Integer depth = loopDepths.get(node.expr());
    return descendants.stream()
        .filter(
            d -> {
              int vn = valueNumberer.vnOf(d.expr());
              return vnCounts.count(vn) > innerVnCounts.count(vn) * copies
                  || (depth != null && loopDepths.get(d.expr()) > depth);
            })
        .findFirst()
        .map(d -> extractionTarget(d, loopDepths, vnCounts, valueNumberer, eligibleExprs))
        .orElse(node);
  }

  // The planner accumulates `@ac + [elem]` steps (map, filter) in place, so `[elem]` is not
  // extracted.
  private static boolean isAccumulation(CelMutableExpr expr) {
    if (!expr.getKind().equals(Kind.CALL)
        || !expr.call().function().equals(Operator.ADD.getFunction())) {
      return false;
    }
    CelMutableExpr accu = expr.call().args().get(0);
    return accu.getKind().equals(Kind.IDENT)
        && accu.ident().name().startsWith(MANGLED_COMPREHENSION_ACCU_VAR_PREFIX);
  }

  // Computed bottom-up, as checking every node's subtree separately is quadratic. Only
  // comprehensions still walk their own subtree.
  private Set<CelMutableExpr> getEligibleExprs(
      ImmutableList<CelNavigableMutableExpr> preOrderNodes, Set<CelMutableExpr> ineligibleExprs) {
    // Subtrees containing an ineliminable call, or any mangled comprehension variable.
    Set<CelMutableExpr> impureExprs = Sets.newIdentityHashSet();
    Set<CelMutableExpr> mangledExprs = Sets.newIdentityHashSet();
    Set<CelMutableExpr> eligibleExprs = Sets.newIdentityHashSet();
    // Reverse pre-order visits children before their parents.
    for (CelNavigableMutableExpr node : preOrderNodes.reverse()) {
      CelMutableExpr expr = node.expr();
      Kind kind = node.getKind();
      if ((kind.equals(Kind.CALL) && !cseEliminableFunctions.contains(expr.call().function()))
          || node.children().anyMatch(child -> impureExprs.contains(child.expr()))) {
        impureExprs.add(expr);
      }
      if ((kind.equals(Kind.IDENT)
              && (expr.ident().name().startsWith(MANGLED_COMPREHENSION_ITER_VAR_PREFIX)
                  || expr.ident().name().startsWith(MANGLED_COMPREHENSION_ACCU_VAR_PREFIX)))
          || node.children().anyMatch(child -> mangledExprs.contains(child.expr()))) {
        mangledExprs.add(expr);
      }
      if (!kind.equals(Kind.CONSTANT)
          && !kind.equals(Kind.IDENT)
          // Exclude empty lists (cel.bind sets this for iterRange).
          && !(kind.equals(Kind.LIST) && expr.list().elements().isEmpty())
          && !ineligibleExprs.contains(expr)
          && !impureExprs.contains(expr)
          && (kind.equals(Kind.COMPREHENSION)
              ? !referencesEnclosingComprehensionVars(node)
              : !mangledExprs.contains(expr))) {
        eligibleExprs.add(expr);
      }
    }
    return eligibleExprs;
  }

  // Comprehensions may reference their own variables, but not those of enclosing ones (e.g.
  // [2].exists(y, x == y) in [1].exists(x, [2].exists(y, x == y))), as that breaks scoping.
  private static boolean referencesEnclosingComprehensionVars(CelNavigableMutableExpr node) {
    return node.descendants()
        .filter(d -> d.getKind().equals(Kind.IDENT))
        .map(d -> d.expr().ident().name())
        .anyMatch(CelNavigableExprUtil.getEnclosingComprehensionVariables(node)::contains);
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
    navAst
        .getRoot()
        .allNodes()
        .map(CelNavigableMutableExpr::expr)
        .filter(SubexpressionOptimizer::isAccumulation)
        .forEach(expr -> ineligibleExprs.add(expr.call().args().get(1)));

    return ineligibleExprs;
  }

  // Assigns equal value numbers to equivalent subexpressions, including commuted and converse calls
  // with only standard overloads. If both operands fail, either error may be reported, which CEL's
  // unspecified evaluation order permits.
  private static final class ValueNumberer {
    private static final ImmutableMap<String, String> SWAPPED_FUNCTIONS =
        ImmutableMap.<String, String>builder()
            .put(Operator.ADD.getFunction(), Operator.ADD.getFunction())
            .put(Operator.MULTIPLY.getFunction(), Operator.MULTIPLY.getFunction())
            .put(Operator.EQUALS.getFunction(), Operator.EQUALS.getFunction())
            .put(Operator.NOT_EQUALS.getFunction(), Operator.NOT_EQUALS.getFunction())
            .put(Operator.LOGICAL_AND.getFunction(), Operator.LOGICAL_AND.getFunction())
            .put(Operator.LOGICAL_OR.getFunction(), Operator.LOGICAL_OR.getFunction())
            .put(Operator.GREATER.getFunction(), Operator.LESS.getFunction())
            .put(Operator.GREATER_EQUALS.getFunction(), Operator.LESS_EQUALS.getFunction())
            .buildOrThrow();
    private static final ImmutableSet<String> SWAPPABLE_OVERLOAD_IDS =
        Stream.concat(
                Stream.of(
                    Arithmetic.ADD_INT64,
                    Arithmetic.ADD_UINT64,
                    Arithmetic.ADD_DOUBLE,
                    Arithmetic.ADD_DURATION_DURATION,
                    Arithmetic.ADD_TIMESTAMP_DURATION,
                    Arithmetic.ADD_DURATION_TIMESTAMP,
                    Arithmetic.MULTIPLY_INT64,
                    Arithmetic.MULTIPLY_UINT64,
                    Arithmetic.MULTIPLY_DOUBLE,
                    Relation.EQUALS,
                    Relation.NOT_EQUALS,
                    BooleanOperator.LOGICAL_AND,
                    BooleanOperator.LOGICAL_OR),
                EnumSet.allOf(Comparison.class).stream())
            .map(overload -> overload.celOverloadDecl().overloadId())
            .collect(toImmutableSet());

    private final ImmutableMap<Long, CelReference> references;
    private final Map<ImmutableList<?>, Integer> keyToVn;
    private final IdentityHashMap<CelMutableExpr, Integer> exprToVn;

    private int vnOf(CelMutableExpr expr) {
      return Verify.verifyNotNull(exprToVn.get(expr));
    }

    private int computeVn(CelMutableExpr expr) {
      ImmutableList<?> key = buildKey(expr);
      int vn = keyToVn.computeIfAbsent(key, k -> keyToVn.size());
      exprToVn.put(expr, vn);
      return vn;
    }

    private ImmutableList<Integer> computeVns(List<CelMutableExpr> exprs) {
      ImmutableList.Builder<Integer> vns = ImmutableList.builderWithExpectedSize(exprs.size());
      for (CelMutableExpr expr : exprs) {
        vns.add(computeVn(expr));
      }
      return vns.build();
    }

    private ImmutableList<?> buildKey(CelMutableExpr expr) {
      switch (expr.getKind()) {
        case CONSTANT:
          return ImmutableList.of(Kind.CONSTANT, expr.constant());
        case IDENT:
          // Runs after identifiers are mangled.
          return ImmutableList.of(Kind.IDENT, expr.ident().name());
        case SELECT:
          CelMutableSelect select = expr.select();
          return ImmutableList.of(
              Kind.SELECT, computeVn(select.operand()), select.field(), select.testOnly());
        case CALL:
          CelMutableCall call = expr.call();
          String function = call.function();
          // VNs are non-negative, so -1 marks a global call.
          int targetVn = call.target().map(this::computeVn).orElse(-1);
          ImmutableList<Integer> argVns = computeVns(call.args());
          // Keyed even when no swap applies (e.g. _<_), so a call that also resolves to custom
          // overloads (x < 5 on dyn) never merges with a canonicalized standard-only one (5 > x).
          boolean swappable = hasOnlySwappableOverloads(expr.id());
          String swapped = SWAPPED_FUNCTIONS.get(function);
          if (swappable
              && swapped != null
              && (!swapped.equals(function) || argVns.get(0) > argVns.get(1))) {
            function = swapped;
            argVns = argVns.reverse();
          }
          return ImmutableList.of(Kind.CALL, targetVn, function, argVns, swappable);
        case LIST:
          CelMutableList list = expr.list();
          return ImmutableList.of(
              Kind.LIST, computeVns(list.elements()), ImmutableList.copyOf(list.optionalIndices()));
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
          return ImmutableList.of(
              Kind.COMPREHENSION,
              comp.iterVar(),
              comp.iterVar2(),
              comp.accuVar(),
              computeVn(comp.iterRange()),
              computeVn(comp.accuInit()),
              computeVn(comp.loopCondition()),
              computeVn(comp.loopStep()),
              computeVn(comp.result()));
        default:
          throw new IllegalArgumentException("Unexpected ExprKind: " + expr.getKind());
      }
    }

    private boolean hasOnlySwappableOverloads(long id) {
      ImmutableList<String> overloadIds = references.get(id).overloadIds();
      // Fail closed: containsAll is vacuously true for an empty list.
      return !overloadIds.isEmpty() && SWAPPABLE_OVERLOAD_IDS.containsAll(overloadIds);
    }

    @SuppressWarnings("ValueClassIdentity") // Integer values are only unboxed, never compared.
    private ValueNumberer(CelMutableExpr root, ImmutableMap<Long, CelReference> references) {
      this.references = references;
      this.keyToVn = new HashMap<>();
      this.exprToVn = new IdentityHashMap<>();
      computeVn(root);
    }
  }

  /** Options to configure how Common Subexpression Elimination behave. */
  @AutoValue
  public abstract static class SubexpressionOptimizerOptions {
    public abstract int iterationLimit();

    public abstract boolean populateMacroCalls();

    public abstract boolean enableCelBlock();

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

    /** Returns a new options builder with recommended defaults pre-configured. */
    public static Builder newBuilder() {
      return new AutoValue_SubexpressionOptimizer_SubexpressionOptimizerOptions.Builder()
          .iterationLimit(500)
          .enableCelBlock(true)
          .populateMacroCalls(false);
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
