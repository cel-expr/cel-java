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

package dev.cel.policy;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableSet.toImmutableSet;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import dev.cel.bundle.Cel;
import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.CelMutableAst;
import dev.cel.common.CelSource.Extension;
import dev.cel.common.CelSource.Extension.Component;
import dev.cel.common.CelSource.Extension.Version;
import dev.cel.common.CelVarDecl;
import dev.cel.common.ast.CelBlock;
import dev.cel.common.ast.CelExpr;
import dev.cel.common.ast.CelExpr.ExprKind.Kind;
import dev.cel.common.ast.CelMutableExpr;
import dev.cel.common.ast.CelMutableExpr.CelMutableList;
import dev.cel.common.navigation.CelNavigableAst;
import dev.cel.common.navigation.CelNavigableMutableExpr;
import dev.cel.common.types.CelType;
import dev.cel.extensions.CelBindingsExtensions;
import dev.cel.optimizer.AstMutator;
import dev.cel.optimizer.CelAstOptimizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Optimizer that statically links separately compiled policies, referenced by the composed policy
 * as decision identifiers, into a single {@code cel.@block}.
 */
final class PolicyDecisionLinker implements CelAstOptimizer {
  // Mirrors SubexpressionOptimizer's tag, needed when linking introduces the first cel.@block.
  private static final Extension CEL_BLOCK_AST_EXTENSION_TAG =
      Extension.create("cel_block", Version.of(1L, 1L), Component.COMPONENT_RUNTIME);

  private final ImmutableMap<String, CelAbstractSyntaxTree> decisions;
  private final int iterationLimit;

  static PolicyDecisionLinker newInstance(
      ImmutableMap<String, CelAbstractSyntaxTree> decisions, int iterationLimit) {
    return new PolicyDecisionLinker(decisions, iterationLimit);
  }

  @Override
  public OptimizationResult optimize(CelAbstractSyntaxTree ast, Cel cel) {
    ImmutableSet<String> referencedIdents = collectIdents(ast);
    ImmutableList<String> linkedNames =
        decisions.keySet().stream().filter(referencedIdents::contains).collect(toImmutableList());
    if (linkedNames.isEmpty()) {
      return OptimizationResult.create(ast);
    }

    List<CelAbstractSyntaxTree> decisionAsts = new ArrayList<>();
    for (String name : linkedNames) {
      CelAbstractSyntaxTree decisionAst = decisions.get(name);
      // Linking only resolves the composed policy's references, so a decision referencing another
      // decision would otherwise type-check here and fail at runtime as an unbound variable.
      Set<String> unresolved = Sets.intersection(collectIdents(decisionAst), decisions.keySet());
      checkArgument(
          unresolved.isEmpty(),
          "Decision '%s' references unlinked decisions %s. Link them when compiling '%s'.",
          name,
          unresolved,
          name);
      decisionAsts.add(decisionAst);
    }

    // The composed policy already fits within the iteration limit, but the linked AST also
    // includes every decision.
    AstMutator astMutator =
        AstMutator.newInstance(
            iterationLimit
                + decisionAsts.stream()
                    .mapToLong(d -> CelNavigableAst.fromAst(d).getRoot().allNodes().count())
                    .sum());
    List<CelMutableAst> mutableInputs = new ArrayList<>();
    for (CelAbstractSyntaxTree decisionAst : decisionAsts) {
      mutableInputs.add(CelMutableAst.fromCelAst(decisionAst));
    }
    // Lazily evaluated slots may be referenced inside the composed policy's comprehensions, so its
    // iteration variables must not shadow the decisions' free variables.
    mutableInputs.add(
        astMutator
            .mangleComprehensionIdentifierNames(CelMutableAst.fromCelAst(ast), "@it", "@it2", "@ac")
            .mutableAst());

    // Stabilizes all inputs into disjoint expression ID ranges while merging their macro calls.
    CelMutableAst combined = astMutator.newGlobalCall(CelBlock.FUNCTION_NAME, mutableInputs);
    CelMutableExpr linkCall = combined.expr();
    List<CelMutableExpr> pieces = linkCall.call().args();

    SlotTable slotTable = new SlotTable(astMutator);
    Map<String, String> decisionRenames = new HashMap<>();
    for (int i = 0; i < linkedNames.size(); i++) {
      CelMutableExpr body = link(decisionAsts.get(i), pieces.get(i), new HashMap<>(), slotTable);
      decisionRenames.put(
          linkedNames.get(i),
          CelBlock.INDEX_PREFIX + slotTable.add(body, decisionAsts.get(i).getResultType()));
    }
    CelMutableExpr rootBody = link(ast, pieces.get(linkedNames.size()), decisionRenames, slotTable);

    // The link call has the highest ID, so the new list node can safely take the next one.
    linkCall
        .call()
        .setArgs(
            ImmutableList.of(
                CelMutableExpr.ofList(linkCall.id() + 1, CelMutableList.create(slotTable.slots)),
                rootBody));
    combined.source().addAllExtensions(ImmutableList.of(CEL_BLOCK_AST_EXTENSION_TAG));
    return OptimizationResult.create(
        astMutator
            .renumberIdsConsecutively(CelMutableAst.of(linkCall, combined.source()))
            .toParsedAst(),
        ImmutableList.copyOf(slotTable.slotDecls),
        ImmutableList.of(CelBindingsExtensions.CEL_BLOCK_FUNCTION_DECL));
  }

  /**
   * Moves the input's block slots into the slot table and returns its renamed body. {@code renames}
   * is extended with the input's local slot renames.
   */
  private static CelMutableExpr link(
      CelAbstractSyntaxTree input,
      CelMutableExpr piece,
      Map<String, String> renames,
      SlotTable slotTable) {
    Optional<CelBlock> block = CelBlock.extract(input);
    if (!block.isPresent()) {
      return rename(piece, renames);
    }
    List<CelMutableExpr> localSlots = piece.call().args().get(0).list().elements();
    ImmutableList<CelExpr> typedSlots = block.get().indices();
    for (int k = 0; k < localSlots.size(); k++) {
      int index =
          slotTable.add(
              rename(localSlots.get(k), renames), input.getTypeOrThrow(typedSlots.get(k).id()));
      renames.put(CelBlock.INDEX_PREFIX + k, CelBlock.INDEX_PREFIX + index);
    }
    return rename(piece.call().args().get(1), renames);
  }

  @CanIgnoreReturnValue
  private static CelMutableExpr rename(CelMutableExpr expr, Map<String, String> renames) {
    CelNavigableMutableExpr.fromExpr(expr)
        .allNodes()
        .filter(node -> node.getKind().equals(Kind.IDENT))
        .forEach(
            node -> {
              String newName = renames.get(node.expr().ident().name());
              if (newName != null) {
                node.expr().ident().setName(newName);
              }
            });
    return expr;
  }

  private static ImmutableSet<String> collectIdents(CelAbstractSyntaxTree ast) {
    return CelNavigableAst.fromAst(ast)
        .getRoot()
        .allNodes()
        .filter(node -> node.getKind().equals(Kind.IDENT))
        .map(node -> node.expr().ident().name())
        .collect(toImmutableSet());
  }

  // Merged slots across all linked ASTs, deduplicated by shape so that a policy reached through
  // multiple paths (e.g. a diamond dependency) is emitted and evaluated at most once.
  private static final class SlotTable {
    private final AstMutator astMutator;
    private final List<CelMutableExpr> slots;
    private final List<CelVarDecl> slotDecls;
    private final Map<CelMutableExpr, Integer> slotIndexByShape;

    private int add(CelMutableExpr slot, CelType type) {
      CelMutableExpr shape = astMutator.clearExprIds(CelMutableExpr.newInstance(slot));
      Integer existingIndex = slotIndexByShape.putIfAbsent(shape, slots.size());
      if (existingIndex != null) {
        return existingIndex;
      }
      slotDecls.add(CelVarDecl.newVarDeclaration(CelBlock.INDEX_PREFIX + slots.size(), type));
      slots.add(slot);
      return slots.size() - 1;
    }

    private SlotTable(AstMutator astMutator) {
      this.astMutator = astMutator;
      this.slots = new ArrayList<>();
      this.slotDecls = new ArrayList<>();
      this.slotIndexByShape = new HashMap<>();
    }
  }

  private PolicyDecisionLinker(
      ImmutableMap<String, CelAbstractSyntaxTree> decisions, int iterationLimit) {
    this.decisions = decisions;
    this.iterationLimit = iterationLimit;
  }
}
