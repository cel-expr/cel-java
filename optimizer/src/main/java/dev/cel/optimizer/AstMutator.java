// Copyright 2023 Google LLC
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

package dev.cel.optimizer;

import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static java.lang.Math.max;
import static java.util.stream.Collectors.toCollection;

import com.google.auto.value.AutoValue;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.HashBasedTable;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Table;
import com.google.errorprone.annotations.Immutable;
import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.CelMutableAst;
import dev.cel.common.CelMutableSource;
import dev.cel.common.ast.CelExpr.ExprKind.Kind;
import dev.cel.common.ast.CelExprIdGeneratorFactory;
import dev.cel.common.ast.CelExprIdGeneratorFactory.ExprIdGenerator;
import dev.cel.common.ast.CelExprIdGeneratorFactory.StableIdGenerator;
import dev.cel.common.ast.CelMutableExpr;
import dev.cel.common.ast.CelMutableExpr.CelMutableCall;
import dev.cel.common.ast.CelMutableExpr.CelMutableComprehension;
import dev.cel.common.ast.CelMutableExpr.CelMutableList;
import dev.cel.common.navigation.CelNavigableMutableAst;
import dev.cel.common.navigation.CelNavigableMutableExpr;
import dev.cel.common.navigation.TraversalOrder;
import dev.cel.common.types.CelType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map.Entry;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/** AstMutator contains logic for mutating a {@link CelAbstractSyntaxTree}. */
@Immutable
public final class AstMutator {
  private static final ExprIdGenerator NO_OP_ID_GENERATOR = id -> id;
  private static final ExprIdGenerator UNSET_ID_GENERATOR = id -> 0;
  private final long iterationLimit;

  /**
   * Returns a new instance of an AST mutator with the iteration limit set.
   *
   * <p>Mutation is performed by walking the existing AST until the expression node to replace is
   * found, then the new subtree is walked to complete the mutation. Visiting of each node
   * increments the iteration counter. Replace subtree operations will throw an exception if this
   * counter reaches the limit.
   *
   * @param iterationLimit Must be greater than 0.
   */
  public static AstMutator newInstance(long iterationLimit) {
    return new AstMutator(iterationLimit);
  }

  private AstMutator(long iterationLimit) {
    Preconditions.checkState(iterationLimit > 0L);
    this.iterationLimit = iterationLimit;
  }

  /** Replaces all the expression IDs in the expression tree with 0. */
  public CelMutableExpr clearExprIds(CelMutableExpr expr) {
    return renumberExprIds(UNSET_ID_GENERATOR, expr);
  }

  /** Wraps the given AST and its subexpressions with a new cel.@block call. */
  public CelMutableAst wrapAstWithNewCelBlock(
      String celBlockFunction, CelMutableAst ast, List<CelMutableExpr> subexpressions) {
    long maxId = getMaxId(ast);
    CelMutableExpr blockExpr =
        CelMutableExpr.ofCall(
            ++maxId,
            CelMutableCall.create(
                celBlockFunction,
                CelMutableExpr.ofList(++maxId, CelMutableList.create(subexpressions)),
                ast.expr()));

    return CelMutableAst.of(blockExpr, ast.source());
  }

  /**
   * Constructs a new global call wrapped in an AST with the provided ASTs as its argument. This
   * will preserve all macro source information contained within the arguments.
   */
  public CelMutableAst newGlobalCall(String function, Collection<CelMutableAst> args) {
    return newCallAst(Optional.empty(), function, args);
  }

  /**
   * Constructs a new global call wrapped in an AST with the provided ASTs as its argument. This
   * will preserve all macro source information contained within the arguments.
   */
  public CelMutableAst newGlobalCall(String function, CelMutableAst... args) {
    return newGlobalCall(function, Arrays.asList(args));
  }

  /**
   * Constructs a new member call wrapped in an AST the provided ASTs as its arguments. This will
   * preserve all macro source information contained within the arguments.
   */
  public CelMutableAst newMemberCall(CelMutableAst target, String function, CelMutableAst... args) {
    return newMemberCall(target, function, Arrays.asList(args));
  }

  /**
   * Constructs a new member call wrapped in an AST the provided ASTs as its arguments. This will
   * preserve all macro source information contained within the arguments.
   */
  public CelMutableAst newMemberCall(
      CelMutableAst target, String function, Collection<CelMutableAst> args) {
    return newCallAst(Optional.of(target), function, args);
  }

  private CelMutableAst newCallAst(
      Optional<CelMutableAst> target, String function, Collection<CelMutableAst> args) {
    long maxId = 0;
    CelMutableSource combinedSource = CelMutableSource.newInstance();
    for (CelMutableAst arg : args) {
      CelMutableAst stableArg = stabilizeAst(arg, maxId);
      maxId = getMaxId(stableArg);
      combinedSource = combine(combinedSource, stableArg.source());
    }

    Optional<CelMutableAst> maybeTarget = Optional.empty();
    if (target.isPresent()) {
      CelMutableAst stableTarget = stabilizeAst(target.get(), maxId);
      combinedSource = combine(combinedSource, stableTarget.source());
      maxId = getMaxId(stableTarget);

      maybeTarget = Optional.of(stableTarget);
    }

    List<CelMutableExpr> exprArgs =
        args.stream().map(CelMutableAst::expr).collect(toCollection(ArrayList::new));
    CelMutableCall newCall =
        maybeTarget
            .map(celMutableAst -> CelMutableCall.create(celMutableAst.expr(), function, exprArgs))
            .orElseGet(() -> CelMutableCall.create(function, exprArgs));

    CelMutableExpr newCallExpr = CelMutableExpr.ofCall(++maxId, newCall);

    return CelMutableAst.of(newCallExpr, combinedSource);
  }

  /** Renumbers all the expr IDs in the given AST in a consecutive manner starting from 1. */
  public CelMutableAst renumberIdsConsecutively(CelMutableAst mutableAst) {
    StableIdGenerator stableIdGenerator = CelExprIdGeneratorFactory.newStableIdGenerator(0);
    CelMutableExpr mutableExpr = renumberExprIds(stableIdGenerator::renumberId, mutableAst.expr());
    CelMutableSource newSource =
        normalizeMacroSource(
            mutableAst.source(), Integer.MIN_VALUE, mutableExpr, stableIdGenerator::renumberId);

    return CelMutableAst.of(mutableExpr, newSource);
  }

  /**
   * Replaces all comprehension identifier names with a unique name based on the given prefix.
   *
   * <p>The purpose of this is to avoid errors that can be caused by shadowed variables while
   * augmenting an AST. As an example: {@code [2, 3].exists(x, x - 1 > 3) || x - 1 > 3}. Note that
   * the scoping of `x - 1` is different between th two LOGICAL_OR branches. Iteration variable `x`
   * in `exists` will be mangled to {@code [2, 3].exists(@c0, @c0 - 1 > 3) || x - 1 > 3} to avoid
   * erroneously extracting x - 1 as common subexpression.
   *
   * <p>The expression IDs are not modified when the identifier names are changed.
   *
   * <p>Mangling occurs only if the iteration variable is referenced within the loop step.
   *
   * <p>Iteration variables in comprehensions are numbered based on their comprehension nesting
   * levels and the iteration variable's type. Examples:
   *
   * <ul>
   *   <li>{@code [true].exists(i, i) && [true].exists(j, j)} -> {@code [true].exists(@c0:0, @c0:0)
   *       && [true].exists(@c0:0, @c0:0)} // Note that i,j gets replaced to the same @c0:0 in this
   *       example as they share the same nesting level and type.
   *   <li>{@code [1].exists(i, i > 0) && [1u].exists(j, j > 0u)} -> {@code [1].exists(@c0:0, @c0:0
   *       > 0) && [1u].exists(@c0:1, @c0:1 > 0u)}
   *   <li>{@code [true].exists(i, i && [true].exists(j, j))} -> {@code [true].exists(@c0:0, @c0:0
   *       && [true].exists(@c1:0, @c1:0))}
   * </ul>
   *
   * @param ast AST containing type-checked references
   * @param newIterVarPrefix Prefix to use for new iteration variable identifier name. For example,
   *     providing @c will produce @c0:0, @c0:1, @c1:0, @c2:0... as new names.
   * @param newAccuVarPrefix Prefix to use for new accumulation variable identifier name.
   */
  public MangledComprehensionAst mangleComprehensionIdentifierNames(
      CelMutableAst ast,
      String newIterVarPrefix,
      String newIterVar2Prefix,
      String newAccuVarPrefix) {
    CelNavigableMutableAst navigableMutableAst = CelNavigableMutableAst.fromAst(ast);
    Predicate<CelNavigableMutableExpr> comprehensionIdentifierPredicate = x -> true;
    comprehensionIdentifierPredicate =
        comprehensionIdentifierPredicate
            .and(node -> node.getKind().equals(Kind.COMPREHENSION))
            .and(node -> !node.expr().comprehension().iterVar().startsWith(newIterVarPrefix + ":"))
            .and(node -> !node.expr().comprehension().accuVar().startsWith(newAccuVarPrefix + ":"))
            .and(
                node ->
                    !node.expr().comprehension().iterVar2().startsWith(newIterVar2Prefix + ":"));
    LinkedHashMap<CelNavigableMutableExpr, MangledComprehensionType> comprehensionsToMangle =
        navigableMutableAst
            .getRoot()
            // This is important - mangling needs to happen bottom-up to avoid stepping over
            // shadowed variables that are not part of the comprehension being mangled.
            .allNodes(TraversalOrder.POST_ORDER)
            .filter(comprehensionIdentifierPredicate)
            .filter(
                node -> {
                  // Ensure the iter_var or the comprehension result is actually referenced in the
                  // loop_step. If it's not, we can skip mangling.
                  String iterVar = node.expr().comprehension().iterVar();
                  String iterVar2 = node.expr().comprehension().iterVar2();
                  String result = node.expr().comprehension().result().ident().name();
                  return CelNavigableMutableExpr.fromExpr(node.expr().comprehension().loopStep())
                      .allNodes()
                      .filter(subNode -> subNode.getKind().equals(Kind.IDENT))
                      .map(subNode -> subNode.expr().ident())
                      .anyMatch(
                          ident ->
                              ident.name().contains(iterVar)
                                  || ident.name().contains(iterVar2)
                                  || ident.name().contains(result));
                })
            .collect(
                Collectors.toMap(
                    k -> k,
                    v -> {
                      CelMutableComprehension comprehension = v.expr().comprehension();
                      String iterVar = comprehension.iterVar();
                      String iterVar2 = comprehension.iterVar2();
                      // Identifiers to mangle could be the iteration variable, comprehension
                      // result or both, but at least one has to exist.
                      // As an example, [1,2].map(i, 3) would result in optional.empty for iteration
                      // variable because `i` is not actually used.
                      Optional<Long> iterVarId =
                          CelNavigableMutableExpr.fromExpr(comprehension.loopStep())
                              .allNodes()
                              .filter(
                                  loopStepNode ->
                                      loopStepNode.getKind().equals(Kind.IDENT)
                                          && loopStepNode.expr().ident().name().equals(iterVar))
                              .map(CelNavigableMutableExpr::id)
                              .findAny();
                      Optional<Long> iterVar2Id =
                          CelNavigableMutableExpr.fromExpr(comprehension.loopStep())
                              .allNodes()
                              .filter(
                                  loopStepNode ->
                                      !iterVar2.isEmpty()
                                          && loopStepNode.getKind().equals(Kind.IDENT)
                                          && loopStepNode.expr().ident().name().equals(iterVar2))
                              .map(CelNavigableMutableExpr::id)
                              .findAny();
                      Optional<CelType> iterVarType =
                          iterVarId.map(
                              id ->
                                  navigableMutableAst
                                      .getType(id)
                                      .orElseThrow(
                                          () ->
                                              new NoSuchElementException(
                                                  "Checked type not present for iteration"
                                                      + " variable: "
                                                      + iterVarId)));
                      Optional<CelType> iterVar2Type =
                          iterVar2Id.map(
                              id ->
                                  navigableMutableAst
                                      .getType(id)
                                      .orElseThrow(
                                          () ->
                                              new NoSuchElementException(
                                                  "Checked type not present for iteration"
                                                      + " variable: "
                                                      + iterVar2Id)));
                      CelType resultType =
                          navigableMutableAst
                              .getType(comprehension.result().id())
                              .orElseThrow(
                                  () ->
                                      new IllegalStateException(
                                          "Result type was not present for the comprehension ID: "
                                              + comprehension.result().id()));

                      return MangledComprehensionType.of(iterVarType, iterVar2Type, resultType);
                    },
                    (x, y) -> {
                      throw new IllegalStateException(
                          "Unexpected CelNavigableMutableExpr collision");
                    },
                    LinkedHashMap::new));

    // The map that we'll eventually return to the caller.
    HashMap<MangledComprehensionName, MangledComprehensionType> mangledIdentNamesToType =
        new HashMap<>();
    // Intermediary table used for the purposes of generating a unique mangled variable name.
    Table<Integer, MangledComprehensionType, MangledComprehensionName> comprehensionLevelToType =
        HashBasedTable.create();
    CelMutableExpr mutatedComprehensionExpr = navigableMutableAst.getAst().expr();
    CelMutableSource newSource = navigableMutableAst.getAst().source();
    int iterCount = 0;
    for (Entry<CelNavigableMutableExpr, MangledComprehensionType> comprehensionEntry :
        comprehensionsToMangle.entrySet()) {
      CelNavigableMutableExpr comprehensionNode = comprehensionEntry.getKey();
      MangledComprehensionType comprehensionEntryType = comprehensionEntry.getValue();

      CelMutableExpr comprehensionExpr = comprehensionNode.expr();
      MangledComprehensionName mangledComprehensionName =
          getMangledComprehensionName(
              newIterVarPrefix,
              newIterVar2Prefix,
              newAccuVarPrefix,
              comprehensionNode,
              comprehensionLevelToType,
              comprehensionEntryType);
      mangledIdentNamesToType.put(mangledComprehensionName, comprehensionEntryType);

      String iterVar = comprehensionExpr.comprehension().iterVar();
      String iterVar2 = comprehensionExpr.comprehension().iterVar2();
      String accuVar = comprehensionExpr.comprehension().accuVar();
      mutatedComprehensionExpr =
          mangleIdentsInComprehensionExpr(
              mutatedComprehensionExpr,
              comprehensionExpr,
              iterVar,
              iterVar2,
              accuVar,
              mangledComprehensionName);
      // Repeat the mangling process for the macro source.
      newSource =
          mangleIdentsInMacroSource(
              newSource,
              mutatedComprehensionExpr,
              iterVar,
              iterVar2,
              mangledComprehensionName,
              comprehensionExpr.id());
      iterCount++;
    }

    if (iterCount >= iterationLimit) {
      // Note that it's generally impossible to reach this for a well-formed AST. The nesting level
      // of AST being mutated is always deeper than the number of identifiers being mangled, thus
      // the mutation operation should throw before we ever reach here.
      throw new IllegalStateException("Max iteration count reached.");
    }

    return MangledComprehensionAst.of(
        CelMutableAst.of(mutatedComprehensionExpr, newSource),
        ImmutableMap.copyOf(mangledIdentNamesToType));
  }

  private static MangledComprehensionName getMangledComprehensionName(
      String newIterVarPrefix,
      String newIterVar2Prefix,
      String newResultPrefix,
      CelNavigableMutableExpr comprehensionNode,
      Table<Integer, MangledComprehensionType, MangledComprehensionName> comprehensionLevelToType,
      MangledComprehensionType comprehensionEntryType) {
    MangledComprehensionName mangledComprehensionName;
    int comprehensionNestingLevel = countComprehensionNestingLevel(comprehensionNode);
    if (comprehensionLevelToType.contains(comprehensionNestingLevel, comprehensionEntryType)) {
      mangledComprehensionName =
          comprehensionLevelToType.get(comprehensionNestingLevel, comprehensionEntryType);
    } else {
      // First time encountering the pair of <ComprehensionLevel, CelType>. Generate a unique
      // mangled variable name for this.
      int uniqueTypeIdx = comprehensionLevelToType.row(comprehensionNestingLevel).size();
      String mangledIterVarName =
          newIterVarPrefix + ":" + comprehensionNestingLevel + ":" + uniqueTypeIdx;
      String mangledResultName =
          newResultPrefix + ":" + comprehensionNestingLevel + ":" + uniqueTypeIdx;
      String mangledIterVar2Name =
          newIterVar2Prefix + ":" + comprehensionNestingLevel + ":" + uniqueTypeIdx;

      mangledComprehensionName =
          MangledComprehensionName.of(mangledIterVarName, mangledIterVar2Name, mangledResultName);
      comprehensionLevelToType.put(
          comprehensionNestingLevel, comprehensionEntryType, mangledComprehensionName);
    }
    return mangledComprehensionName;
  }

  private static int countComprehensionNestingLevel(CelNavigableMutableExpr comprehensionExpr) {
    return comprehensionExpr
        .descendants()
        .filter(node -> node.getKind().equals(Kind.COMPREHENSION))
        .mapToInt(
            node -> {
              int nestedLevel = 1;
              CelNavigableMutableExpr maybeParent = node.parent().orElse(null);
              while (maybeParent != null && maybeParent.id() != comprehensionExpr.id()) {
                if (maybeParent.getKind().equals(Kind.COMPREHENSION)) {
                  nestedLevel++;
                }
                maybeParent = maybeParent.parent().orElse(null);
              }
              return nestedLevel;
            })
        .max()
        .orElse(0);
  }

  /**
   * Replaces a subtree in the given expression node. This operation is intended for AST
   * optimization purposes.
   *
   * <p>This is a very dangerous operation. Callers must re-typecheck the mutated AST and
   * additionally verify that the resulting AST is semantically valid.
   *
   * <p>All expression IDs will be renumbered in a stable manner to ensure there's no ID collision
   * between the nodes. The renumbering occurs even if the subtree was not replaced.
   *
   * <p>If the ability to unparse an expression containing a macro call must be retained, use {@link
   * #replaceSubtree(CelMutableAst, CelMutableAst, long) instead.}
   *
   * @param root Original expression node to rewrite.
   * @param newExpr New CelExpr to replace the subtree with.
   * @param exprIdToReplace Expression id of the subtree that is getting replaced.
   */
  public CelMutableAst replaceSubtree(
      CelMutableExpr root, CelMutableExpr newExpr, long exprIdToReplace) {
    return replaceSubtree(
        CelMutableAst.of(root, CelMutableSource.newInstance()), newExpr, exprIdToReplace);
  }

  /**
   * Replaces a subtree in the given AST. This operation is intended for AST optimization purposes.
   *
   * <p>This is a very dangerous operation. Callers must re-typecheck the mutated AST and
   * additionally verify that the resulting AST is semantically valid.
   *
   * <p>All expression IDs will be renumbered in a stable manner to ensure there's no ID collision
   * between the nodes. The renumbering occurs even if the subtree was not replaced.
   *
   * <p>This will scrub out the description, positions and line offsets from {@code CelSource}. If
   * the source contains macro calls, its call IDs will be to be consistent with the renumbered IDs
   * in the AST.
   *
   * @param ast Original ast to mutate.
   * @param newExpr New CelExpr to replace the subtree with.
   * @param exprIdToReplace Expression id of the subtree that is getting replaced.
   */
  public CelMutableAst replaceSubtree(
      CelMutableAst ast, CelMutableExpr newExpr, long exprIdToReplace) {
    return replaceSubtree(
        ast,
        CelMutableAst.of(
            newExpr,
            // Copy the macro call information to the new AST such that macro call map can be
            // normalized post-replacement.
            ast.source()),
        exprIdToReplace);
  }

  /**
   * Replaces a subtree in the given AST. This operation is intended for AST optimization purposes.
   *
   * <p>This is a very dangerous operation. Callers must re-typecheck the mutated AST and
   * additionally verify that the resulting AST is semantically valid.
   *
   * <p>All expression IDs will be renumbered in a stable manner to ensure there's no ID collision
   * between the nodes. The renumbering occurs even if the subtree was not replaced.
   *
   * <p>This will scrub out the description, positions and line offsets from {@code CelSource}. If
   * the source contains macro calls, its call IDs will be to be consistent with the renumbered IDs
   * in the AST.
   *
   * @param ast Original ast to mutate.
   * @param newAst New AST to replace the subtree with.
   * @param exprIdToReplace Expression id of the subtree that is getting replaced.
   */
  public CelMutableAst replaceSubtree(
      CelMutableAst ast, CelMutableAst newAst, long exprIdToReplace) {
    return replaceSubtree(
        CelNavigableMutableAst.fromAst(ast),
        CelNavigableMutableAst.fromAst(newAst),
        exprIdToReplace);
  }

  /**
   * Replaces a subtree in the given AST. This operation is intended for AST optimization purposes.
   *
   * <p>This is a very dangerous operation. Callers must re-typecheck the mutated AST and
   * additionally verify that the resulting AST is semantically valid.
   *
   * <p>All expression IDs will be renumbered in a stable manner to ensure there's no ID collision
   * between the nodes. The renumbering occurs even if the subtree was not replaced.
   *
   * <p>This will scrub out the description, positions and line offsets from {@code CelSource}. If
   * the source contains macro calls, its call IDs will be to be consistent with the renumbered IDs
   * in the AST.
   *
   * @param navAst Original navigable ast to mutate.
   * @param navNewAst New navigable AST to replace the subtree with.
   * @param exprIdToReplace Expression id of the subtree that is getting replaced.
   */
  public CelMutableAst replaceSubtree(
      CelNavigableMutableAst navAst, CelNavigableMutableAst navNewAst, long exprIdToReplace) {
    // Stabilize the incoming AST by renumbering all of its expression IDs.
    long maxId = max(getMaxId(navAst), getMaxId(navNewAst));
    CelMutableAst ast = navAst.getAst();
    CelMutableAst newAst = navNewAst.getAst();
    newAst = stabilizeAst(newAst, maxId);
    long stablizedNewExprRootId = newAst.expr().id();

    // Mutate the AST root with the new subtree. All the existing expr IDs are renumbered in the
    // process, but its original IDs are memoized so that we can normalize the expr IDs
    // in the macro source map.
    StableIdGenerator stableIdGenerator =
        CelExprIdGeneratorFactory.newStableIdGenerator(getMaxId(newAst));

    CelMutableExpr mutatedRoot =
        mutateExpr(stableIdGenerator::renumberId, ast.expr(), newAst.expr(), exprIdToReplace);
    CelMutableSource newAstSource =
        CelMutableSource.newInstance().setDescription(ast.source().getDescription());
    if (!ast.source().getMacroCalls().isEmpty()) {
      newAstSource = combine(newAstSource, ast.source());
    }

    if (!newAst.source().getMacroCalls().isEmpty()) {
      stableIdGenerator.memoize(
          stablizedNewExprRootId, stableIdGenerator.renumberId(exprIdToReplace));
      newAstSource = combine(newAstSource, newAst.source());
    }

    newAstSource =
        normalizeMacroSource(
            newAstSource, exprIdToReplace, mutatedRoot, stableIdGenerator::renumberId);
    return CelMutableAst.of(mutatedRoot, newAstSource);
  }

  private CelMutableExpr mangleIdentsInComprehensionExpr(
      CelMutableExpr root,
      CelMutableExpr comprehensionExpr,
      String originalIterVar,
      String originalIterVar2,
      String originalAccuVar,
      MangledComprehensionName mangledComprehensionName) {
    CelMutableComprehension comprehension = comprehensionExpr.comprehension();
    replaceIdentName(
        comprehension.loopStep(), originalIterVar, mangledComprehensionName.iterVarName());
    replaceIdentName(comprehensionExpr, originalAccuVar, mangledComprehensionName.resultName());

    comprehension.setIterVar(mangledComprehensionName.iterVarName());

    // Most standard macros set accu_var as __result__, but not all (ex: cel.bind).
    if (comprehension.accuVar().equals(originalAccuVar)) {
      comprehension.setAccuVar(mangledComprehensionName.resultName());
    }

    if (!originalIterVar2.isEmpty()) {
      comprehension.setIterVar2(mangledComprehensionName.iterVar2Name());
      replaceIdentName(
          comprehension.loopStep(), originalIterVar2, mangledComprehensionName.iterVar2Name());
    }

    return mutateExpr(NO_OP_ID_GENERATOR, root, comprehensionExpr, comprehensionExpr.id());
  }

  private void replaceIdentName(
      CelMutableExpr comprehensionExpr, String originalIdentName, String newIdentName) {
    int iterCount;
    for (iterCount = 0; iterCount < iterationLimit; iterCount++) {
      CelMutableExpr identToMangle =
          CelNavigableMutableExpr.fromExpr(comprehensionExpr)
              .descendants()
              .map(CelNavigableMutableExpr::expr)
              .filter(
                  node ->
                      node.getKind().equals(Kind.IDENT)
                          && node.ident().name().equals(originalIdentName))
              .findAny()
              .orElse(null);
      if (identToMangle == null) {
        break;
      }

      comprehensionExpr =
          mutateExpr(
              NO_OP_ID_GENERATOR,
              comprehensionExpr,
              CelMutableExpr.ofIdent(newIdentName),
              identToMangle.id());
    }

    if (iterCount >= iterationLimit) {
      throw new IllegalStateException("Max iteration count reached.");
    }
  }

  private CelMutableSource mangleIdentsInMacroSource(
      CelMutableSource sourceBuilder,
      CelMutableExpr mutatedComprehensionExpr,
      String originalIterVar,
      String originalIterVar2,
      MangledComprehensionName mangledComprehensionName,
      long originalComprehensionId) {
    if (!sourceBuilder.getMacroCalls().containsKey(originalComprehensionId)) {
      return sourceBuilder;
    }

    // First, normalize the macro source.
    // ex: [x].exists(x, [x].exists(x, x == 1)) -> [x].exists(x, [@c1].exists(x, @c0 == 1)).
    CelMutableSource newSource =
        normalizeMacroSource(sourceBuilder, -1, mutatedComprehensionExpr, (id) -> id);

    // Note that in the above example, the iteration variable is not replaced after normalization.
    // This is because populating a macro call map upon parse generates a new unique identifier
    // that does not exist in the main AST. Thus, we need to manually replace the identifier.
    // Also note that this only applies when the macro is at leaf. For nested macros, the iteration
    // variable actually exists in the main AST thus, this step isn't needed.
    // ex: [1].map(x, [2].filter(y, x == y). Here, the variable declaration `x` exists in the AST
    // but not `y`.
    CelMutableExpr macroExpr = newSource.getMacroCalls().get(originalComprehensionId);
    // By convention, the iteration variable is always the first argument of the
    // macro call expression.
    CelMutableExpr identToMangle = macroExpr.call().args().get(0);
    if (identToMangle.ident().name().equals(originalIterVar)) {
      macroExpr =
          mutateExpr(
              NO_OP_ID_GENERATOR,
              macroExpr,
              CelMutableExpr.ofIdent(mangledComprehensionName.iterVarName()),
              identToMangle.id());
    }
    if (!originalIterVar2.isEmpty()) {
      // Similarly by convention, iter_var2 is always the second argument of the macro call.
      identToMangle = macroExpr.call().args().get(1);
      if (identToMangle.ident().name().equals(originalIterVar2)) {
        macroExpr =
            mutateExpr(
                NO_OP_ID_GENERATOR,
                macroExpr,
                CelMutableExpr.ofIdent(mangledComprehensionName.iterVar2Name()),
                identToMangle.id());
      }
    }

    newSource.addMacroCalls(originalComprehensionId, macroExpr);

    return newSource;
  }

  /** Combines two {@link CelMutableSource} instances into a single new instance. */
  public static CelMutableSource combine(CelMutableSource celSource1, CelMutableSource celSource2) {
    return CelMutableSource.newInstance()
        .setDescription(
            Strings.isNullOrEmpty(celSource1.getDescription())
                ? celSource2.getDescription()
                : celSource1.getDescription())
        .addAllExtensions(celSource1.getExtensions())
        .addAllExtensions(celSource2.getExtensions())
        .addAllMacroCalls(celSource1.getMacroCalls())
        .addAllMacroCalls(celSource2.getMacroCalls());
  }


  /**
   * Stabilizes the incoming AST by ensuring that all of expr IDs are consistently renumbered
   * (monotonically increased) from the starting seed ID. If the AST contains any macro calls, its
   * IDs are also normalized.
   */
  private CelMutableAst stabilizeAst(CelMutableAst mutableAst, long seedExprId) {
    CelMutableExpr mutableExpr = mutableAst.expr();
    CelMutableSource source = mutableAst.source();
    StableIdGenerator stableIdGenerator =
        CelExprIdGeneratorFactory.newStableIdGenerator(seedExprId);
    CelMutableExpr mutatedExpr = renumberExprIds(stableIdGenerator::nextExprId, mutableExpr);

    CelMutableSource sourceBuilder =
        CelMutableSource.newInstance().addAllExtensions(source.getExtensions());
    // Update the macro call IDs and their call IDs
    for (Entry<Long, CelMutableExpr> macroCall : source.getMacroCalls().entrySet()) {
      long macroId = macroCall.getKey();
      long newCallId = stableIdGenerator.renumberId(macroId);
      CelMutableExpr existingMacroCallExpr = CelMutableExpr.newInstance(macroCall.getValue());

      CelMutableExpr newCall =
          renumberExprIds(stableIdGenerator::renumberId, existingMacroCallExpr);

      sourceBuilder.addMacroCalls(newCallId, newCall);
    }

    return CelMutableAst.of(mutatedExpr, sourceBuilder);
  }

  private CelMutableSource normalizeMacroSource(
      CelMutableSource source,
      long exprIdToReplace,
      CelMutableExpr mutatedRoot,
      ExprIdGenerator idGenerator) {
    // Remove the macro metadata that no longer exists in the AST due to being replaced.
    source.clearMacroCall(exprIdToReplace);
    if (source.getMacroCalls().isEmpty()) {
      return source;
    }

    ImmutableMap<Long, CelMutableExpr> allExprs =
        CelNavigableMutableExpr.fromExpr(mutatedRoot)
            .allNodes()
            .map(CelNavigableMutableExpr::expr)
            .collect(
                toImmutableMap(
                    CelMutableExpr::id,
                    expr -> expr,
                    (expr1, expr2) -> {
                      // Comprehensions can reuse same expression (result). We just need to ensure
                      // that they are identical.
                      if (expr1.equals(expr2)) {
                        return expr1;
                      }
                      throw new IllegalStateException(
                          "Expected expressions to be the same for id: " + expr1.id());
                    }));

    CelMutableSource newMacroSource =
        CelMutableSource.newInstance()
            .setDescription(source.getDescription())
            .addAllExtensions(source.getExtensions());
    // Update the macro call IDs and their call references
    for (Entry<Long, CelMutableExpr> existingMacroCall : source.getMacroCalls().entrySet()) {
      long macroId = existingMacroCall.getKey();
      long callId = idGenerator.generate(macroId);

      if (!allExprs.containsKey(callId)) {
        continue;
      }

      CelMutableExpr existingMacroCallExpr =
          CelMutableExpr.newInstance(existingMacroCall.getValue());
      CelMutableExpr newMacroCallExpr = renumberExprIds(idGenerator, existingMacroCallExpr);

      CelNavigableMutableExpr callNav = CelNavigableMutableExpr.fromExpr(newMacroCallExpr);
      ArrayList<CelMutableExpr> callDescendants =
          callNav
              .descendants()
              .map(CelNavigableMutableExpr::expr)
              .collect(toCollection(ArrayList::new));

      for (CelMutableExpr callChild : callDescendants) {
        if (!allExprs.containsKey(callChild.id())) {
          continue;
        }

        CelMutableExpr mutatedExpr = allExprs.get(callChild.id());
        if (!callChild.equals(mutatedExpr)) {
          newMacroCallExpr =
              mutateExpr(NO_OP_ID_GENERATOR, newMacroCallExpr, mutatedExpr, callChild.id());
        }
      }

      if (exprIdToReplace > 0) {
        long replacedId = idGenerator.generate(exprIdToReplace);
        boolean isListExprBeingReplaced =
            allExprs.containsKey(replacedId)
                && allExprs.get(replacedId).getKind().equals(Kind.LIST);
        if (isListExprBeingReplaced) {
          unwrapListArgumentsInMacroCallExpr(
              allExprs.get(callId).comprehension(), newMacroCallExpr);
        }
      }

      newMacroSource.addMacroCalls(callId, newMacroCallExpr);
    }

    // Replace comprehension nodes with a NOT_SET reference to reduce AST size.
    for (Entry<Long, CelMutableExpr> macroCall : newMacroSource.getMacroCalls().entrySet()) {
      CelMutableExpr macroCallExpr = macroCall.getValue();
      CelNavigableMutableExpr.fromExpr(macroCallExpr)
          .allNodes()
          .filter(node -> node.getKind().equals(Kind.COMPREHENSION))
          .map(CelNavigableMutableExpr::expr)
          .forEach(
              node -> {
                CelMutableExpr mutatedNode =
                    mutateExpr(
                        NO_OP_ID_GENERATOR,
                        macroCallExpr,
                        CelMutableExpr.ofNotSet(node.id()),
                        node.id());
                macroCall.setValue(mutatedNode);
              });

      // Prune any NOT_SET (comprehension) nodes that no longer exist in the main AST
      // This can occur from pulling out a nested comprehension into a separate cel.block index
      CelNavigableMutableExpr.fromExpr(macroCallExpr)
          .allNodes()
          .filter(node -> node.getKind().equals(Kind.NOT_SET))
          .map(CelNavigableMutableExpr::id)
          .filter(id -> !allExprs.containsKey(id))
          .forEach(
              id -> {
                ArrayList<CelMutableExpr> newCallArgs =
                    macroCallExpr.call().args().stream()
                        .filter(node -> node.id() != id)
                        .collect(toCollection(ArrayList::new));
                CelMutableCall call = macroCallExpr.call();
                call.setArgs(newCallArgs);
              });
    }

    return newMacroSource;
  }

  /**
   * Unwraps the arguments in the extraneous list_expr which is present in the AST but does not
   * exist in the macro call map. `map`, `filter` are examples of such.
   *
   * <p>This method inspects the comprehension's accumulator initializer to infer that the list_expr
   * solely exists to match the expected result type of the macro call signature.
   *
   * @param comprehension Comprehension in the main AST to extract the macro call arguments from
   *     (loop step).
   * @param newMacroCallExpr (Output parameter) Modified macro call expression with the call
   *     arguments unwrapped.
   */
  private static void unwrapListArgumentsInMacroCallExpr(
      CelMutableComprehension comprehension, CelMutableExpr newMacroCallExpr) {
    CelMutableExpr accuInit = comprehension.accuInit();
    if (!accuInit.getKind().equals(Kind.LIST) || !accuInit.list().elements().isEmpty()) {
      // Does not contain an extraneous list.
      return;
    }

    CelMutableExpr loopStepExpr = comprehension.loopStep();
    List<CelMutableExpr> loopStepArgs = loopStepExpr.call().args();
    if (loopStepArgs.size() != 2 && loopStepArgs.size() != 3) {
      throw new IllegalArgumentException(
          String.format(
              "Expected exactly 2 or 3 arguments but got %d instead on expr id: %d",
              loopStepArgs.size(), loopStepExpr.id()));
    }

    CelMutableCall existingMacroCall = newMacroCallExpr.call();
    CelMutableCall newMacroCall =
        existingMacroCall.target().isPresent()
            ? CelMutableCall.create(existingMacroCall.target().get(), existingMacroCall.function())
            : CelMutableCall.create(existingMacroCall.function());
    newMacroCall.addArgs(
        existingMacroCall.args().get(0)); // iter_var is first argument of the call by convention

    CelMutableList extraneousList;
    if (loopStepArgs.size() == 2) {
      extraneousList = loopStepArgs.get(1).list();
    } else {
      newMacroCall.addArgs(loopStepArgs.get(0));
      // For map(x,y,z), z is wrapped in a _+_(@result, [z])
      extraneousList = loopStepArgs.get(1).call().args().get(1).list();
    }

    newMacroCall.addArgs(extraneousList.elements());

    newMacroCallExpr.setCall(newMacroCall);
  }

  private CelMutableExpr mutateExpr(
      ExprIdGenerator idGenerator,
      CelMutableExpr root,
      CelMutableExpr newExpr,
      long exprIdToReplace) {
    MutableExprVisitor mutableAst =
        MutableExprVisitor.newInstance(idGenerator, newExpr, exprIdToReplace, iterationLimit);
    return mutableAst.visit(root);
  }

  private CelMutableExpr renumberExprIds(ExprIdGenerator idGenerator, CelMutableExpr root) {
    MutableExprVisitor mutableAst =
        MutableExprVisitor.newInstance(idGenerator, root, Integer.MIN_VALUE, iterationLimit);
    return mutableAst.visit(root);
  }

  private static long getMaxId(CelMutableAst mutableAst) {
    return getMaxId(CelNavigableMutableAst.fromAst(mutableAst));
  }

  private static long getMaxId(CelNavigableMutableAst navAst) {
    long maxId = navAst.getRoot().maxId();
    for (Entry<Long, CelMutableExpr> macroCall :
        navAst.getAst().source().getMacroCalls().entrySet()) {
      maxId = max(maxId, getMaxId(macroCall.getValue()));
    }

    return maxId;
  }

  private static long getMaxId(CelMutableExpr mutableExpr) {
    return CelNavigableMutableExpr.fromExpr(mutableExpr)
        .allNodes()
        .mapToLong(CelNavigableMutableExpr::id)
        .max()
        .orElseThrow(NoSuchElementException::new);
  }

  /**
   * Intermediate value class to store the mangled identifiers for iteration variable and the
   * comprehension result.
   */
  @AutoValue
  public abstract static class MangledComprehensionAst {

    /** AST after the iteration variables have been mangled. */
    public abstract CelMutableAst mutableAst();

    /** Map containing the mangled identifier names to their types. */
    public abstract ImmutableMap<MangledComprehensionName, MangledComprehensionType>
        mangledComprehensionMap();

    private static MangledComprehensionAst of(
        CelMutableAst ast,
        ImmutableMap<MangledComprehensionName, MangledComprehensionType> mangledComprehensionMap) {
      return new AutoValue_AstMutator_MangledComprehensionAst(ast, mangledComprehensionMap);
    }
  }

  /**
   * Intermediate value class to store the types for iter_var and comprehension result of which its
   * identifier names are being mangled.
   */
  @AutoValue
  public abstract static class MangledComprehensionType {

    /**
     * Type of iter_var. Empty if iter_var is not referenced in the expression anywhere (ex: "i" in
     * "[1].exists(i, true)"
     */
    public abstract Optional<CelType> iterVarType();

    /** Type of iter_var2. */
    public abstract Optional<CelType> iterVar2Type();

    /** Type of comprehension result */
    public abstract CelType resultType();

    private static MangledComprehensionType of(
        Optional<CelType> iterVarType, Optional<CelType> iterVarType2, CelType resultType) {
      return new AutoValue_AstMutator_MangledComprehensionType(
          iterVarType, iterVarType2, resultType);
    }
  }

  /**
   * Intermediate value class to store the mangled names for iteration variable and the
   * comprehension result.
   */
  @AutoValue
  public abstract static class MangledComprehensionName {

    /** Mangled name for iter_var */
    public abstract String iterVarName();

    /** Mangled name for iter_var2 */
    public abstract String iterVar2Name();

    /** Mangled name for comprehension result */
    public abstract String resultName();

    private static MangledComprehensionName of(
        String iterVarName, String iterVar2Name, String resultName) {
      return new AutoValue_AstMutator_MangledComprehensionName(
          iterVarName, iterVar2Name, resultName);
    }
  }
}
