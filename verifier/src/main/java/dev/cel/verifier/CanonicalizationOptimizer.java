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

package dev.cel.verifier;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableMap.toImmutableMap;

import com.google.auto.value.AutoValue;
import com.google.common.collect.ComparisonChain;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import dev.cel.bundle.Cel;
import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.CelMutableAst;
import dev.cel.common.CelMutableSource;
import dev.cel.common.Operator;
import dev.cel.common.ast.CelConstant;
import dev.cel.common.ast.CelExpr.ExprKind.Kind;
import dev.cel.common.ast.CelMutableExpr;
import dev.cel.common.ast.CelMutableExpr.CelMutableCall;
import dev.cel.common.ast.CelMutableExpr.CelMutableComprehension;
import dev.cel.common.ast.CelMutableExpr.CelMutableMap;
import dev.cel.common.ast.CelMutableExpr.CelMutableSelect;
import dev.cel.common.ast.CelMutableExpr.CelMutableStruct;
import dev.cel.common.navigation.CelNavigableExprUtil;
import dev.cel.common.navigation.CelNavigableMutableAst;
import dev.cel.common.navigation.CelNavigableMutableExpr;
import dev.cel.common.navigation.TraversalOrder;
import dev.cel.common.values.CelByteString;
import dev.cel.optimizer.AstMutator;
import dev.cel.optimizer.CelAstOptimizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Standalone AST canonicalization pass that normalizes commutative operator ordering and De Morgan
 * quantifier/logical identities.
 *
 * <p>This optimizer performs:
 *
 * <ul>
 *   <li>Deterministic sorting of symmetric/commutative operators ({@code LOGICAL_AND}, {@code
 *       LOGICAL_OR}, {@code EQUALS}, {@code NOT_EQUALS}).
 *   <li>Negation Normal Form (NNF) and De Morgan quantifier normalization ({@code !exists(x, P) ->
 *       all(x, !P)} and {@code !all(x, P) -> exists(x, !P)}).
 * </ul>
 *
 * <p><b>Caveat:</b> This is a structural normalizer intended for comparison purposes (such as
 * formal equivalence verification) and as a pre-processor for helping other optimizers (such as
 * Common Subexpression Elimination). It is not a runtime cost optimizer; lexicographical ordering
 * of calls or Negation Normal Form expansions are not designed to optimize runtime execution or
 * short-circuit latency.
 */
final class CanonicalizationOptimizer implements CelAstOptimizer {

  private final CanonicalizationOptions canonicalizationOptions;

  /**
   * Returns a new instance of canonicalization optimizer configured with the provided {@link
   * CanonicalizationOptions}.
   */
  static CanonicalizationOptimizer newInstance(CanonicalizationOptions canonicalizationOptions) {
    return new CanonicalizationOptimizer(canonicalizationOptions);
  }

  @Override
  public OptimizationResult optimize(CelAbstractSyntaxTree ast, Cel cel) {
    CelMutableAst mutableAst = CelMutableAst.fromCelAst(ast);
    mutableAst = runCanonicalizationLoop(mutableAst, CanonicalizationScope.EMPTY);
    canonicalizeMacroCalls(mutableAst);
    CelAbstractSyntaxTree optimizedAst =
        AstMutator.newInstance(canonicalizationOptions.maxIterationLimit())
            .renumberIdsConsecutively(mutableAst)
            .toParsedAst();
    return OptimizationResult.create(optimizedAst);
  }

  private void canonicalizeMacroCalls(CelMutableAst mutableAst) {
    if (mutableAst.source().getMacroCalls().isEmpty()) {
      return;
    }
    CelNavigableMutableAst navigableAst = CelNavigableMutableAst.fromAst(mutableAst);
    ImmutableMap<Long, CelNavigableMutableExpr> comprehensionNodesById =
        navigableAst
            .getRoot()
            .allNodes()
            .filter(n -> n.getKind() == Kind.COMPREHENSION)
            .collect(toImmutableMap(CelNavigableMutableExpr::id, n -> n));

    for (Map.Entry<Long, CelMutableExpr> entry :
        new HashMap<>(mutableAst.source().getMacroCalls()).entrySet()) {
      long compId = entry.getKey();
      CelNavigableMutableExpr compNode = comprehensionNodesById.get(compId);
      CanonicalizationScope compScope = CanonicalizationScope.EMPTY;
      if (compNode != null) {
        compScope =
            CanonicalizationScope.fromNavigableExpr(compNode, CanonicalizationScope.EMPTY)
                .forComprehensionLoop(compNode.expr().comprehension());
      }
      CelMutableExpr canonicalMacro = canonicalize(entry.getValue(), compScope);
      mutableAst.source().addMacroCalls(entry.getKey(), canonicalMacro);
    }
  }

  /** Canonicalizes a single CelMutableExpr subtree. */
  private CelMutableExpr canonicalize(CelMutableExpr root, CanonicalizationScope baseScope) {
    CelMutableAst mutableAst = CelMutableAst.of(root, CelMutableSource.newInstance());
    mutableAst = runCanonicalizationLoop(mutableAst, baseScope);
    return mutableAst.expr();
  }

  private CelMutableAst runCanonicalizationLoop(
      CelMutableAst mutableAst, CanonicalizationScope baseScope) {
    AstMutator astMutator = AstMutator.newInstance(canonicalizationOptions.maxIterationLimit());
    int iterCount = 0;
    boolean continueCanonicalizing = true;
    while (continueCanonicalizing) {
      if (iterCount >= canonicalizationOptions.maxIterationLimit()) {
        throw new IllegalStateException(
            "Max iteration count reached in CanonicalizationOptimizer.");
      }
      iterCount++;
      continueCanonicalizing = false;
      ImmutableList<CelNavigableMutableExpr> candidateExprs =
          CelNavigableMutableAst.fromAst(mutableAst)
              .getRoot()
              .allNodes(TraversalOrder.POST_ORDER)
              .filter(CanonicalizationOptimizer::canCanonicalize)
              .collect(toImmutableList());
      for (CelNavigableMutableExpr candidate : candidateExprs) {
        iterCount++;
        Optional<CelMutableExpr> newExpr = maybeCanonicalize(mutableAst, candidate, baseScope);
        if (newExpr.isPresent()) {
          continueCanonicalizing = true;
          mutableAst = astMutator.replaceSubtree(mutableAst, newExpr.get(), candidate.id());
          break;
        }
      }
    }
    return mutableAst;
  }

  private static boolean canCanonicalize(CelNavigableMutableExpr navigable) {
    CelMutableExpr expr = navigable.expr();
    return isCallWithArgCount(expr, Operator.LOGICAL_AND.getFunction(), 2)
        || isCallWithArgCount(expr, Operator.LOGICAL_OR.getFunction(), 2)
        || isCallWithArgCount(expr, Operator.EQUALS.getFunction(), 2)
        || isCallWithArgCount(expr, Operator.NOT_EQUALS.getFunction(), 2)
        || isCallWithArgCount(expr, Operator.LOGICAL_NOT.getFunction(), 1);
  }

  private static Optional<CelMutableExpr> maybeCanonicalize(
      CelMutableAst mutableAst,
      CelNavigableMutableExpr navigableExpr,
      CanonicalizationScope baseScope) {
    CelMutableExpr expr = navigableExpr.expr();
    if (expr.getKind() != Kind.CALL) {
      return Optional.empty();
    }
    CelMutableCall call = expr.call();
    String functionName = call.function();
    List<CelMutableExpr> args = call.args();

    if ((functionName.equals(Operator.LOGICAL_AND.getFunction())
            || functionName.equals(Operator.LOGICAL_OR.getFunction()))
        && args.size() == 2) {
      return maybeCanonicalizeCommutativeCall(navigableExpr, functionName, baseScope);
    }

    if ((functionName.equals(Operator.EQUALS.getFunction())
            || functionName.equals(Operator.NOT_EQUALS.getFunction()))
        && args.size() == 2) {
      return maybeCanonicalizeSymmetricCall(navigableExpr, functionName, args, baseScope);
    }

    if (functionName.equals(Operator.LOGICAL_NOT.getFunction()) && args.size() == 1) {
      return maybeCanonicalizeLogicalNot(mutableAst, args.get(0));
    }

    return Optional.empty();
  }

  private static Optional<CelMutableExpr> maybeCanonicalizeCommutativeCall(
      CelNavigableMutableExpr navigableExpr, String functionName, CanonicalizationScope baseScope) {
    // TODO: Consider supporting associative/commutative reassociation for arithmetic
    // operators (+, *)
    List<CelNavigableMutableExpr> navigableOperands =
        flattenNavigableOperands(navigableExpr, functionName);
    if (navigableOperands.stream()
        .anyMatch(op -> AccuVarSafetyChecker.containsEnclosingAccuVar(op, navigableExpr))) {
      return Optional.empty();
    }
    List<CelMutableExpr> operands = new ArrayList<>();
    for (CelNavigableMutableExpr navOp : navigableOperands) {
      operands.add(navOp.expr());
    }
    CanonicalizationScope scope = CanonicalizationScope.fromNavigableExpr(navigableExpr, baseScope);
    AstComparator scopedComparator = AstComparator.of(scope);
    operands.sort(scopedComparator);
    List<CelMutableExpr> uniqueSorted = new ArrayList<>();
    for (CelMutableExpr op : operands) {
      if (uniqueSorted.isEmpty()
          || scopedComparator.compare(op, Iterables.getLast(uniqueSorted)) != 0) {
        uniqueSorted.add(op);
      }
    }
    CelMutableExpr rebuilt = uniqueSorted.get(0);
    for (int i = 1; i < uniqueSorted.size(); i++) {
      rebuilt =
          CelMutableExpr.ofCall(
              0, CelMutableCall.create(functionName, rebuilt, uniqueSorted.get(i)));
    }
    if (scopedComparator.compare(rebuilt, navigableExpr.expr()) == 0) {
      return Optional.empty();
    }
    return Optional.of(rebuilt);
  }

  private static Optional<CelMutableExpr> maybeCanonicalizeSymmetricCall(
      CelNavigableMutableExpr navigableExpr,
      String functionName,
      List<CelMutableExpr> args,
      CanonicalizationScope baseScope) {
    CelMutableExpr arg0 = args.get(0);
    CelMutableExpr arg1 = args.get(1);
    CanonicalizationScope scope = CanonicalizationScope.fromNavigableExpr(navigableExpr, baseScope);
    if (AstComparator.of(scope).compare(arg0, arg1) > 0) {
      return Optional.of(CelMutableExpr.ofCall(0, CelMutableCall.create(functionName, arg1, arg0)));
    }
    return Optional.empty();
  }

  private static Optional<CelMutableExpr> maybeCanonicalizeLogicalNot(
      CelMutableAst mutableAst, CelMutableExpr target) {
    if (isCallWithArgCount(target, Operator.LOGICAL_NOT.getFunction(), 1)) {
      return Optional.of(target.call().args().get(0));
    }
    if (isCallWithArgCount(target, Operator.LOGICAL_AND.getFunction(), 2)) {
      List<CelMutableExpr> subArgs = target.call().args();
      return Optional.of(
          CelMutableExpr.ofCall(
              0,
              CelMutableCall.create(
                  Operator.LOGICAL_OR.getFunction(),
                  negate(subArgs.get(0)),
                  negate(subArgs.get(1)))));
    }
    if (isCallWithArgCount(target, Operator.LOGICAL_OR.getFunction(), 2)) {
      List<CelMutableExpr> subArgs = target.call().args();
      return Optional.of(
          CelMutableExpr.ofCall(
              0,
              CelMutableCall.create(
                  Operator.LOGICAL_AND.getFunction(),
                  negate(subArgs.get(0)),
                  negate(subArgs.get(1)))));
    }
    if (isCallWithArgCount(target, Operator.EQUALS.getFunction(), 2)) {
      List<CelMutableExpr> subArgs = target.call().args();
      return Optional.of(
          CelMutableExpr.ofCall(
              0,
              CelMutableCall.create(
                  Operator.NOT_EQUALS.getFunction(), subArgs.get(0), subArgs.get(1))));
    }
    if (isCallWithArgCount(target, Operator.NOT_EQUALS.getFunction(), 2)) {
      List<CelMutableExpr> subArgs = target.call().args();
      return Optional.of(
          CelMutableExpr.ofCall(
              0,
              CelMutableCall.create(
                  Operator.EQUALS.getFunction(), subArgs.get(0), subArgs.get(1))));
    }
    return QuantifierDeMorganRewriter.maybeRewrite(mutableAst, target);
  }

  private static CelMutableExpr negate(CelMutableExpr expr) {
    return CelMutableExpr.ofCall(
        0, CelMutableCall.create(Operator.LOGICAL_NOT.getFunction(), expr));
  }

  private static List<CelNavigableMutableExpr> flattenNavigableOperands(
      CelNavigableMutableExpr expr, String functionName) {
    List<CelNavigableMutableExpr> result = new ArrayList<>();
    flattenNavigableOperandsRec(expr, functionName, result);
    return result;
  }

  private static void flattenNavigableOperandsRec(
      CelNavigableMutableExpr expr, String functionName, List<CelNavigableMutableExpr> result) {
    if (expr.getKind() == Kind.CALL
        && expr.expr().call().function().equals(functionName)
        && expr.expr().call().args().size() == 2) {
      ImmutableList<CelNavigableMutableExpr> children = expr.children().collect(toImmutableList());
      if (children.size() == 2) {
        flattenNavigableOperandsRec(children.get(0), functionName, result);
        flattenNavigableOperandsRec(children.get(1), functionName, result);
        return;
      }
    }
    result.add(expr);
  }

  private static boolean isCallWithArgCount(
      CelMutableExpr expr, String functionName, int argCount) {
    return expr.getKind() == Kind.CALL
        && expr.call().function().equals(functionName)
        && expr.call().args().size() == argCount;
  }

  /**
   * Immutable lexical scope chain for tracking comprehension binder depths during canonical AST
   * ordering.
   */
  private static final class CanonicalizationScope {
    private static final CanonicalizationScope EMPTY = new CanonicalizationScope("", null);

    private final String varName;
    private final @Nullable CanonicalizationScope parent;

    private CanonicalizationScope(String varName, @Nullable CanonicalizationScope parent) {
      this.varName = checkNotNull(varName);
      this.parent = parent;
    }

    CanonicalizationScope push(String varName) {
      checkNotNull(varName);
      if (varName.isEmpty()) {
        return this;
      }
      return new CanonicalizationScope(varName, this);
    }

    CanonicalizationScope forComprehensionLoop(CelMutableComprehension comp) {
      return push(comp.iterVar()).push(comp.iterVar2()).push(comp.accuVar());
    }

    CanonicalizationScope forComprehensionResult(CelMutableComprehension comp) {
      return push(comp.accuVar());
    }

    int indexOf(String name) {
      checkNotNull(name);
      int idx = 0;
      CanonicalizationScope curr = this;
      while (curr != null && curr != EMPTY) {
        if (curr.varName.equals(name)) {
          return idx;
        }
        idx++;
        curr = curr.parent;
      }
      return -1;
    }

    @SuppressWarnings("ReferenceEquality") // Disambiguates mutable child branches
    static CanonicalizationScope fromNavigableExpr(
        CelNavigableMutableExpr node, CanonicalizationScope baseScope) {
      checkNotNull(node);
      checkNotNull(baseScope);
      if (!node.parent().isPresent()) {
        return baseScope;
      }
      CelNavigableMutableExpr parent = node.parent().get();
      CanonicalizationScope scope = fromNavigableExpr(parent, baseScope);
      if (parent.getKind() == Kind.COMPREHENSION) {
        CelMutableComprehension comp = parent.expr().comprehension();
        CelMutableExpr nodeExpr = node.expr();
        if (nodeExpr == comp.loopCondition() || nodeExpr == comp.loopStep()) {
          return scope.forComprehensionLoop(comp);
        } else if (nodeExpr == comp.result()) {
          return scope.forComprehensionResult(comp);
        }
      }
      return scope;
    }
  }

  /** Total ordering comparator for CEL mutable AST expressions. */
  private static final class AstComparator implements Comparator<CelMutableExpr> {
    private final CanonicalizationScope scope;

    private AstComparator(CanonicalizationScope scope) {
      this.scope = checkNotNull(scope);
    }

    static AstComparator of(CanonicalizationScope scope) {
      return new AstComparator(scope);
    }

    @Override
    public int compare(CelMutableExpr e1, CelMutableExpr e2) {
      int kindCmp = Integer.compare(getKindPriority(e1.getKind()), getKindPriority(e2.getKind()));
      if (kindCmp != 0) {
        return kindCmp;
      }
      switch (e1.getKind()) {
        case CONSTANT:
          return compareConstants(e1.constant(), e2.constant());
        case IDENT:
          return compareIdent(e1.ident().name(), e2.ident().name(), scope);
        case SELECT:
          return compareSelect(e1.select(), e2.select());
        case CALL:
          return compareCall(e1.call(), e2.call());
        case LIST:
          return compareList(e1.list().elements(), e2.list().elements());
        case MAP:
          return compareMap(e1.map(), e2.map());
        case STRUCT:
          return compareStruct(e1.struct(), e2.struct());
        case COMPREHENSION:
          return compareComprehension(e1.comprehension(), e2.comprehension());
        case NOT_SET:
          return 0;
      }
      throw new UnsupportedOperationException("Unsupported expression kind: " + e1.getKind());
    }

    private static int compareConstants(CelConstant c1, CelConstant c2) {
      int constKindCmp = c1.getKind().name().compareTo(c2.getKind().name());
      if (constKindCmp != 0) {
        return constKindCmp;
      }
      switch (c1.getKind()) {
        case NULL_VALUE:
        case NOT_SET:
          return 0;
        case BOOLEAN_VALUE:
          return Boolean.compare(c1.booleanValue(), c2.booleanValue());
        case INT64_VALUE:
          return Long.compare(c1.int64Value(), c2.int64Value());
        case UINT64_VALUE:
          return c1.uint64Value().compareTo(c2.uint64Value());
        case DOUBLE_VALUE:
          return Double.compare(c1.doubleValue(), c2.doubleValue());
        case STRING_VALUE:
          return c1.stringValue().compareTo(c2.stringValue());
        case BYTES_VALUE:
          return CelByteString.unsignedLexicographicalComparator()
              .compare(c1.bytesValue(), c2.bytesValue());
        default:
          throw new UnsupportedOperationException("Unsupported constant kind: " + c1.getKind());
      }
    }

    private static int compareIdent(String name1, String name2, CanonicalizationScope scope) {
      int bIdx1 = scope.indexOf(name1);
      int bIdx2 = scope.indexOf(name2);
      if (bIdx1 >= 0 && bIdx2 >= 0) {
        return Integer.compare(bIdx2, bIdx1); // Outer/earlier binder first
      }
      if (bIdx1 >= 0) {
        return -1; // Bound variable comes before free variable
      }
      if (bIdx2 >= 0) {
        return 1; // Free variable comes after bound variable
      }
      return name1.compareTo(name2);
    }

    private int compareSelect(CelMutableSelect s1, CelMutableSelect s2) {
      return ComparisonChain.start()
          .compare(s1.operand(), s2.operand(), this)
          .compare(s1.field(), s2.field())
          .compareFalseFirst(s1.testOnly(), s2.testOnly())
          .result();
    }

    private int compareCall(CelMutableCall c1, CelMutableCall c2) {
      int fnCmp = c1.function().compareTo(c2.function());
      if (fnCmp != 0) {
        return fnCmp;
      }
      boolean hasT1 = c1.target().isPresent();
      boolean hasT2 = c2.target().isPresent();
      if (hasT1 != hasT2) {
        return Boolean.compare(hasT1, hasT2);
      }
      if (hasT1) {
        int tCmp = compare(c1.target().get(), c2.target().get());
        if (tCmp != 0) {
          return tCmp;
        }
      }
      return compareList(c1.args(), c2.args());
    }

    private int compareMap(CelMutableMap m1, CelMutableMap m2) {
      int mapSizeCmp = Integer.compare(m1.entries().size(), m2.entries().size());
      if (mapSizeCmp != 0) {
        return mapSizeCmp;
      }
      Iterator<CelMutableMap.Entry> it2 = m2.entries().iterator();
      for (CelMutableMap.Entry entry1 : m1.entries()) {
        CelMutableMap.Entry entry2 = it2.next();
        int cmp =
            ComparisonChain.start()
                .compare(entry1.key(), entry2.key(), this)
                .compare(entry1.value(), entry2.value(), this)
                .result();
        if (cmp != 0) {
          return cmp;
        }
      }
      return 0;
    }

    private int compareStruct(CelMutableStruct s1, CelMutableStruct s2) {
      int msgCmp = s1.messageName().compareTo(s2.messageName());
      if (msgCmp != 0) {
        return msgCmp;
      }
      int structSizeCmp = Integer.compare(s1.entries().size(), s2.entries().size());
      if (structSizeCmp != 0) {
        return structSizeCmp;
      }
      Iterator<CelMutableStruct.Entry> it2 = s2.entries().iterator();
      for (CelMutableStruct.Entry entry1 : s1.entries()) {
        CelMutableStruct.Entry entry2 = it2.next();
        int cmp =
            ComparisonChain.start()
                .compare(entry1.fieldKey(), entry2.fieldKey())
                .compare(entry1.value(), entry2.value(), this)
                .result();
        if (cmp != 0) {
          return cmp;
        }
      }
      return 0;
    }

    private int compareComprehension(CelMutableComprehension c1, CelMutableComprehension c2) {
      int cmp =
          ComparisonChain.start()
              .compare(c1.iterRange(), c2.iterRange(), this)
              .compare(c1.accuInit(), c2.accuInit(), this)
              .compareTrueFirst(!c1.accuVar().isEmpty(), !c2.accuVar().isEmpty())
              .compareTrueFirst(!c1.iterVar().isEmpty(), !c2.iterVar().isEmpty())
              .compareFalseFirst(!c1.iterVar2().isEmpty(), !c2.iterVar2().isEmpty())
              .result();
      if (cmp != 0) {
        return cmp;
      }

      AstComparator loopComparator = AstComparator.of(scope.forComprehensionLoop(c1));
      cmp = loopComparator.compare(c1.loopCondition(), c2.loopCondition());
      if (cmp != 0) {
        return cmp;
      }
      cmp = loopComparator.compare(c1.loopStep(), c2.loopStep());
      if (cmp != 0) {
        return cmp;
      }

      AstComparator resultComparator = AstComparator.of(scope.forComprehensionResult(c1));
      return resultComparator.compare(c1.result(), c2.result());
    }

    private int compareList(List<CelMutableExpr> l1, List<CelMutableExpr> l2) {
      int sizeCmp = Integer.compare(l1.size(), l2.size());
      if (sizeCmp != 0) {
        return sizeCmp;
      }
      Iterator<CelMutableExpr> it2 = l2.iterator();
      for (CelMutableExpr elem1 : l1) {
        int cmp = compare(elem1, it2.next());
        if (cmp != 0) {
          return cmp;
        }
      }
      return 0;
    }

    private static int getKindPriority(Kind kind) {
      switch (kind) {
        case IDENT:
          return 1;
        case SELECT:
          return 2;
        case CALL:
          return 3;
        case LIST:
          return 4;
        case MAP:
          return 5;
        case STRUCT:
          return 6;
        case COMPREHENSION:
          return 7;
        case CONSTANT:
          return 8;
        default:
          return 99;
      }
    }
  }

  /**
   * Safety analyzer for verifying whether an operand contains references to enclosing comprehension
   * accumulator variables.
   */
  private static final class AccuVarSafetyChecker {

    static boolean containsEnclosingAccuVar(
        CelNavigableMutableExpr operand, CelNavigableMutableExpr contextExpr) {
      List<CelNavigableMutableExpr> enclosingComprehensions =
          collectEnclosingComprehensions(contextExpr);
      if (enclosingComprehensions.isEmpty()) {
        return false;
      }
      return operand
          .allNodes()
          .filter(node -> node.getKind() == Kind.IDENT)
          .anyMatch(
              identNode -> {
                String varName = identNode.expr().ident().name();
                Optional<CelNavigableMutableExpr> declaringComp =
                    CelNavigableExprUtil.findDeclaringComprehension(identNode, varName);
                return declaringComp.isPresent()
                    && declaringComp.get().expr().comprehension().accuVar().equals(varName)
                    && enclosingComprehensions.contains(declaringComp.get());
              });
    }

    @SuppressWarnings("ReferenceEquality") // Disambiguates mutable child branches
    private static List<CelNavigableMutableExpr> collectEnclosingComprehensions(
        CelNavigableMutableExpr contextExpr) {
      List<CelNavigableMutableExpr> comps = new ArrayList<>();
      CelNavigableMutableExpr curr = contextExpr;
      Optional<CelNavigableMutableExpr> maybeParent = curr.parent();
      while (maybeParent.isPresent()) {
        CelNavigableMutableExpr parent = maybeParent.get();
        if (parent.getKind() == Kind.COMPREHENSION) {
          CelMutableComprehension comp = parent.expr().comprehension();
          CelMutableExpr currExpr = curr.expr();
          if ((currExpr == comp.loopCondition() || currExpr == comp.loopStep())
              && !comp.accuVar().isEmpty()) {
            comps.add(parent);
          }
        }
        curr = parent;
        maybeParent = parent.parent();
      }
      return comps;
    }
  }

  /**
   * Rewriter for De Morgan quantifier dualities over single-variable and two-variable
   * comprehensions.
   */
  private static final class QuantifierDeMorganRewriter {

    static Optional<CelMutableExpr> maybeRewrite(
        CelMutableAst mutableAst, CelMutableExpr notTargetExpr) {
      if (notTargetExpr.getKind() != Kind.COMPREHENSION) {
        return Optional.empty();
      }
      CelMutableComprehension comp = notTargetExpr.comprehension();
      long compId = notTargetExpr.id();
      if (isExistsMacro(mutableAst, compId, comp)) {
        return Optional.of(negateComprehension(mutableAst, compId, comp, /* isExists= */ true));
      } else if (isAllMacro(mutableAst, compId, comp)) {
        return Optional.of(negateComprehension(mutableAst, compId, comp, /* isExists= */ false));
      }
      return Optional.empty();
    }

    private static CelMutableExpr negateComprehension(
        CelMutableAst mutableAst, long compId, CelMutableComprehension comp, boolean isExists) {
      CelMutableCall stepCall = comp.loopStep().call();
      CelMutableExpr predicate = getPredicateFromLoopStep(stepCall);
      CelMutableExpr newLoopStep =
          CelMutableExpr.ofCall(
              comp.loopStep().id(),
              CelMutableCall.create(
                  (isExists ? Operator.LOGICAL_AND : Operator.LOGICAL_OR).getFunction(),
                  CelMutableExpr.ofIdent(comp.accuVar()),
                  negate(predicate)));
      CelMutableExpr newAccuInit = CelMutableExpr.ofConstant(CelConstant.ofValue(isExists));
      CelMutableExpr newLoopCondition =
          CelMutableExpr.ofCall(
              comp.loopCondition().id(),
              CelMutableCall.create(
                  Operator.NOT_STRICTLY_FALSE.getFunction(),
                  isExists
                      ? CelMutableExpr.ofIdent(comp.accuVar())
                      : negate(CelMutableExpr.ofIdent(comp.accuVar()))));
      CelMutableComprehension newComp =
          CelMutableComprehension.create(
              comp.iterVar(),
              comp.iterVar2(),
              comp.iterRange(),
              comp.accuVar(),
              newAccuInit,
              newLoopCondition,
              newLoopStep,
              comp.result());
      updateMacroCallForQuantifier(
          mutableAst, compId, (isExists ? Operator.ALL : Operator.EXISTS).getFunction());
      return CelMutableExpr.ofComprehension(compId, newComp);
    }

    private static void updateMacroCallForQuantifier(
        CelMutableAst mutableAst, long compId, String newFunctionName) {
      if (!mutableAst.source().getMacroCalls().containsKey(compId)) {
        return;
      }
      CelMutableExpr macroCall = mutableAst.source().getMacroCalls().get(compId);
      if (macroCall.getKind() != Kind.CALL) {
        throw new IllegalStateException(
            "Expected macro call to be of kind CALL, but got: " + macroCall.getKind());
      }
      CelMutableCall call = macroCall.call();
      if (call.args().size() < 2) {
        throw new IllegalStateException(
            "Expected macro call to have at least 2 arguments, but got: " + call.args().size());
      }
      CelMutableExpr predicateArg = Iterables.getLast(call.args());
      CelMutableExpr notPredicate;
      if (isCallWithArgCount(predicateArg, Operator.LOGICAL_NOT.getFunction(), 1)) {
        notPredicate = predicateArg.call().args().get(0);
      } else {
        notPredicate =
            CelMutableExpr.ofCall(
                0, CelMutableCall.create(Operator.LOGICAL_NOT.getFunction(), predicateArg));
      }
      List<CelMutableExpr> newArgs = new ArrayList<>(call.args());
      newArgs.set(newArgs.size() - 1, notPredicate);
      CelMutableCall newCall =
          call.target().isPresent()
              ? CelMutableCall.create(call.target().get(), newFunctionName, newArgs)
              : CelMutableCall.create(newFunctionName, newArgs);
      mutableAst.source().addMacroCalls(compId, CelMutableExpr.ofCall(macroCall.id(), newCall));
    }

    private static CelMutableExpr getPredicateFromLoopStep(CelMutableCall stepCall) {
      return stepCall.args().get(1);
    }

    private static boolean isExistsMacro(
        CelMutableAst mutableAst, long compId, CelMutableComprehension comp) {
      return isStandardMacroCall(mutableAst, compId, Operator.EXISTS.getFunction())
          && isBooleanAccuInit(comp, false)
          && isNotStrictlyFalseLoopCondition(comp, true)
          && isLoopStepWithAccuVar(comp, Operator.LOGICAL_OR.getFunction());
    }

    private static boolean isAllMacro(
        CelMutableAst mutableAst, long compId, CelMutableComprehension comp) {
      return isStandardMacroCall(mutableAst, compId, Operator.ALL.getFunction())
          && isBooleanAccuInit(comp, true)
          && isNotStrictlyFalseLoopCondition(comp, false)
          && isLoopStepWithAccuVar(comp, Operator.LOGICAL_AND.getFunction());
    }

    private static boolean isStandardMacroCall(
        CelMutableAst mutableAst, long compId, String expectedMacroFunction) {
      if (!mutableAst.source().getMacroCalls().containsKey(compId)) {
        return true;
      }
      CelMutableExpr macroCall = mutableAst.source().getMacroCalls().get(compId);
      return macroCall.getKind() == Kind.CALL
          && macroCall.call().function().equals(expectedMacroFunction);
    }

    private static boolean isBooleanAccuInit(CelMutableComprehension comp, boolean expectedValue) {
      return comp.accuInit().getKind() == Kind.CONSTANT
          && comp.accuInit().constant().getKind() == CelConstant.Kind.BOOLEAN_VALUE
          && comp.accuInit().constant().booleanValue() == expectedValue;
    }

    private static boolean isNotStrictlyFalseLoopCondition(
        CelMutableComprehension comp, boolean expectNot) {
      if (comp.loopCondition().getKind() != Kind.CALL) {
        throw new IllegalStateException(
            "Expected comprehension loopCondition to be a CALL, but got: "
                + comp.loopCondition().getKind());
      }
      CelMutableCall call = comp.loopCondition().call();
      if (!call.function().equals(Operator.NOT_STRICTLY_FALSE.getFunction())
          && !call.function().equals(Operator.OLD_NOT_STRICTLY_FALSE.getFunction())) {
        throw new IllegalStateException(
            "Expected comprehension loopCondition to be @not_strictly_false, but got: "
                + call.function());
      }
      if (call.args().size() != 1) {
        throw new IllegalStateException(
            "Expected @not_strictly_false to have exactly 1 argument, but got: "
                + call.args().size());
      }
      CelMutableExpr arg = call.args().get(0);
      if (expectNot) {
        if (!isCallWithArgCount(arg, Operator.LOGICAL_NOT.getFunction(), 1)) {
          return false;
        }
        arg = arg.call().args().get(0);
      }
      return isIdent(arg, comp.accuVar());
    }

    private static boolean isLoopStepWithAccuVar(
        CelMutableComprehension comp, String expectedFunction) {
      if (!isCallWithArgCount(comp.loopStep(), expectedFunction, 2)) {
        return false;
      }
      List<CelMutableExpr> args = comp.loopStep().call().args();
      return isIdent(args.get(0), comp.accuVar()) || isIdent(args.get(1), comp.accuVar());
    }

    private static boolean isIdent(CelMutableExpr expr, String name) {
      return expr.getKind() == Kind.IDENT && expr.ident().name().equals(name);
    }
  }

  /** Options to configure how Canonicalization behaves. */
  @AutoValue
  abstract static class CanonicalizationOptions {
    abstract int maxIterationLimit();

    /** Builder for configuring the {@link CanonicalizationOptions}. */
    @AutoValue.Builder
    abstract static class Builder {

      /**
       * Limit the number of iterations while performing canonicalization. An exception is thrown if
       * the iteration count exceeds the set value.
       */
      abstract Builder maxIterationLimit(int value);

      abstract CanonicalizationOptions build();

      Builder() {}
    }

    /** Returns a new options builder with recommended defaults pre-configured. */
    static Builder newBuilder() {
      return new AutoValue_CanonicalizationOptimizer_CanonicalizationOptions.Builder()
          .maxIterationLimit(500);
    }

    CanonicalizationOptions() {}
  }

  private CanonicalizationOptimizer(CanonicalizationOptions canonicalizationOptions) {
    this.canonicalizationOptions = canonicalizationOptions;
  }
}
