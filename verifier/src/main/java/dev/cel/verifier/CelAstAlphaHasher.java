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

import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableList;
import com.google.common.hash.HashFunction;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
import dev.cel.common.ast.CelConstant;
import dev.cel.common.ast.CelExpr;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Utility for computing alpha-equivalence signatures of CEL expressions.
 *
 * <p>An alpha-equivalence signature uniquely identifies the structure of a CEL expression up to the
 * renaming of bound variables (e.g., variables introduced by comprehensions).
 *
 * <p>The signature consists of:
 *
 * <ul>
 *   <li>A static hash of the expression's abstract syntax tree (AST). In this hash, bound variables
 *       are represented by their relative binder depth (de Bruijn index) rather than their string
 *       names, making the hash invariant to bound variable renaming.
 *   <li>A list of free variables encountered during the traversal of the AST, in the order of their
 *       appearance.
 * </ul>
 *
 * <p>This utility is used by the verifier to parameterize Z3 unknowns generated during loop
 * truncation. By using the alpha-equivalence signature, the verifier can identify when two
 * different loop expressions are structurally identical and share the same free variables, allowing
 * Z3 to treat their truncated outputs as equivalent.
 */
final class CelAstAlphaHasher {

  private static final HashFunction HASH_FUNCTION = Hashing.farmHashFingerprint64();

  @AutoValue
  abstract static class AlphaSignature {
    abstract long staticHash();

    abstract ImmutableList<CelExpr> freeVariables();

    static AlphaSignature create(long staticHash, ImmutableList<CelExpr> freeVariables) {
      return new AutoValue_CelAstAlphaHasher_AlphaSignature(staticHash, freeVariables);
    }
  }

  static AlphaSignature computeSignature(CelExpr expr) {
    HasherContext context = new HasherContext(HASH_FUNCTION);
    hashAst(expr, /* scope= */ null, context);
    return AlphaSignature.create(
        context.hasher.hash().asLong(), ImmutableList.copyOf(context.freeVars));
  }

  private static void hashAst(CelExpr expr, @Nullable Scope scope, HasherContext context) {
    context.hasher.putString(expr.exprKind().getKind().name(), UTF_8);
    switch (expr.exprKind().getKind()) {
      case CONSTANT:
        hashConstant(expr.constant(), context);
        break;
      case IDENT:
        String name = expr.ident().name();
        int bIdx = scope == null ? -1 : scope.indexOf(name);
        if (bIdx >= 0) {
          context.hasher.putByte((byte) 0); // 0 = bound
          context.hasher.putInt(bIdx);
        } else {
          int fIdx = -1;
          for (int i = 0; i < context.freeVars.size(); i++) {
            if (context.freeVars.get(i).ident().name().equals(name)) {
              fIdx = i;
              break;
            }
          }
          if (fIdx == -1) {
            context.freeVars.add(expr);
            fIdx = context.freeVars.size() - 1;
          }
          context.hasher.putByte((byte) 1); // 1 = free
          context.hasher.putInt(fIdx);
        }
        break;
      case SELECT:
        hashAst(expr.select().operand(), scope, context);
        context.hasher.putInt(expr.select().field().length());
        context.hasher.putString(expr.select().field(), UTF_8);
        context.hasher.putBoolean(expr.select().testOnly());
        break;
      case CALL:
        context.hasher.putInt(expr.call().function().length());
        context.hasher.putString(expr.call().function(), UTF_8);
        context.hasher.putBoolean(expr.call().target().isPresent());
        if (expr.call().target().isPresent()) {
          hashAst(expr.call().target().get(), scope, context);
        }
        context.hasher.putInt(expr.call().args().size());
        for (CelExpr arg : expr.call().args()) {
          hashAst(arg, scope, context);
        }
        break;
      case LIST:
        context.hasher.putInt(expr.list().elements().size());
        for (int i = 0; i < expr.list().elements().size(); i++) {
          CelExpr elem = expr.list().elements().get(i);
          context.hasher.putBoolean(expr.list().optionalIndices().contains(i));
          hashAst(elem, scope, context);
        }
        break;
      case STRUCT:
        context.hasher.putString(expr.struct().messageName(), UTF_8);
        context.hasher.putInt(expr.struct().entries().size());
        for (CelExpr.CelStruct.Entry entry : expr.struct().entries()) {
          context.hasher.putString(entry.fieldKey(), UTF_8);
          context.hasher.putBoolean(entry.optionalEntry());
          hashAst(entry.value(), scope, context);
        }
        break;
      case MAP:
        context.hasher.putInt(expr.map().entries().size());
        for (CelExpr.CelMap.Entry entry : expr.map().entries()) {
          context.hasher.putBoolean(entry.optionalEntry());
          hashAst(entry.key(), scope, context);
          hashAst(entry.value(), scope, context);
        }
        break;
      case COMPREHENSION:
        CelExpr.CelComprehension comp = expr.comprehension();
        hashAst(comp.iterRange(), scope, context);
        hashAst(comp.accuInit(), scope, context);

        context.hasher.putBoolean(!comp.accuVar().isEmpty());
        context.hasher.putBoolean(!comp.iterVar().isEmpty());
        context.hasher.putBoolean(!comp.iterVar2().isEmpty());

        Scope loopScope = scope;
        if (!comp.iterVar().isEmpty()) {
          loopScope = new Scope(comp.iterVar(), loopScope);
        }
        if (!comp.iterVar2().isEmpty()) {
          loopScope = new Scope(comp.iterVar2(), loopScope);
        }
        if (!comp.accuVar().isEmpty()) {
          loopScope = new Scope(comp.accuVar(), loopScope);
        }

        hashAst(comp.loopCondition(), loopScope, context);
        hashAst(comp.loopStep(), loopScope, context);

        Scope resultScope = scope;
        if (!comp.accuVar().isEmpty()) {
          resultScope = new Scope(comp.accuVar(), resultScope);
        }
        hashAst(comp.result(), resultScope, context);
        break;
      case NOT_SET:
        break;
    }
  }

  private static void hashConstant(CelConstant constant, HasherContext context) {
    context.hasher.putString(constant.getKind().name(), UTF_8);
    switch (constant.getKind()) {
      case NULL_VALUE:
        context.hasher.putInt(0);
        break;
      case BOOLEAN_VALUE:
        context.hasher.putBoolean(constant.booleanValue());
        break;
      case INT64_VALUE:
        context.hasher.putLong(constant.int64Value());
        break;
      case UINT64_VALUE:
        context.hasher.putLong(constant.uint64Value().longValue());
        break;
      case DOUBLE_VALUE:
        context.hasher.putDouble(constant.doubleValue());
        break;
      case STRING_VALUE:
        context.hasher.putInt(constant.stringValue().length());
        context.hasher.putString(constant.stringValue(), UTF_8);
        break;
      case BYTES_VALUE:
        context.hasher.putInt(constant.bytesValue().size());
        context.hasher.putBytes(constant.bytesValue().toByteArray());
        break;
      case NOT_SET:
        break;
      default:
        throw new UnsupportedOperationException("Unsupported constant kind: " + constant.getKind());
    }
  }

  private static final class HasherContext {
    final Hasher hasher;
    final List<CelExpr> freeVars = new ArrayList<>();

    HasherContext(HashFunction hashFunction) {
      this.hasher = hashFunction.newHasher();
    }
  }

  private static final class Scope {
    final String varName;
    final Scope parent;

    Scope(String varName, Scope parent) {
      this.varName = varName;
      this.parent = parent;
    }

    int indexOf(String name) {
      int idx = 0;
      Scope curr = this;
      while (curr != null) {
        if (curr.varName.equals(name)) {
          return idx;
        }
        idx++;
        curr = curr.parent;
      }
      return -1;
    }
  }

  private CelAstAlphaHasher() {}
}
