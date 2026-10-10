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

package dev.cel.common;

import com.google.common.collect.ImmutableMap;
import dev.cel.common.ast.CelMutableExpr;
import dev.cel.common.ast.CelMutableExprConverter;
import dev.cel.common.ast.CelReference;
import dev.cel.common.types.CelType;
import java.util.Map;
import java.util.Optional;

/**
 * An abstract representation of CEL Abstract Syntax tree that allows mutation in any of its
 * properties. This class is semantically the same as that of the immutable {@link
 * CelAbstractSyntaxTree}.
 *
 * <p>This should only be used within optimizers to augment an AST.
 */
public final class CelMutableAst {
  private final CelMutableExpr mutatedExpr;
  private final CelMutableSource source;
  private final ImmutableMap<Long, CelReference> references;
  private final ImmutableMap<Long, CelType> types;

  /** Returns the underlying {@link CelMutableExpr} representation of the abstract syntax tree. */
  public CelMutableExpr expr() {
    return mutatedExpr;
  }

  /**
   * Returns the {@link CelMutableSource} that was used during construction of the abstract syntax
   * tree.
   */
  public CelMutableSource source() {
    return source;
  }

  /**
   * Returns the resolved reference to a declaration at expression ID for a type-checked AST.
   *
   * @return Optional of {@link CelReference} or {@link Optional#empty} if the reference does not
   *     exist at the ID.
   */
  public Optional<CelReference> getReference(long exprId) {
    return Optional.ofNullable(references.get(exprId));
  }

  /**
   * Returns the type of the expression node for a type-checked AST.
   *
   * @return Optional of {@link CelType} or {@link Optional#empty} if the type does not exist at the
   *     ID.
   */
  public Optional<CelType> getType(long exprId) {
    return Optional.ofNullable(types.get(exprId));
  }

  /** Returns the type map of the abstract syntax tree. */
  public ImmutableMap<Long, CelType> getTypeMap() {
    return types;
  }

  /** Converts this mutable AST into a parsed {@link CelAbstractSyntaxTree}. */
  public CelAbstractSyntaxTree toParsedAst() {
    return toParsedAst(false);
  }

  /**
   * Converts this mutable AST into a parsed {@link CelAbstractSyntaxTree}.
   *
   * @param retainSourcePositions If true, the source positions (line offsets, code points) will be
   *     retained in the resulting AST. If false, they will be scrubbed.
   */
  public CelAbstractSyntaxTree toParsedAst(boolean retainSourcePositions) {
    return CelAbstractSyntaxTree.newParsedAst(
        CelMutableExprConverter.fromMutableExpr(mutatedExpr),
        source.toCelSource(retainSourcePositions));
  }

  /**
   * Constructs an instance of {@link CelMutableAst} with the incoming {@link
   * CelAbstractSyntaxTree}.
   */
  public static CelMutableAst fromCelAst(CelAbstractSyntaxTree ast) {
    return new CelMutableAst(
        CelMutableExprConverter.fromCelExpr(ast.getExpr()),
        CelMutableSource.fromCelSource(ast.getSource()),
        ast.getReferenceMap(),
        ast.getTypeMap());
  }

  /**
   * Constructs an instance of {@link CelMutableAst} with the mutable expression and its source
   * builder.
   */
  public static CelMutableAst of(CelMutableExpr mutableExpr, CelMutableSource mutableSource) {
    return new CelMutableAst(mutableExpr, mutableSource, ImmutableMap.of(), ImmutableMap.of());
  }

  /**
   * Constructs an instance of {@link CelMutableAst} with the mutable expression, its source
   * builder, and node types.
   */
  public static CelMutableAst of(
      CelMutableExpr mutableExpr, CelMutableSource mutableSource, Map<Long, CelType> types) {
    return new CelMutableAst(mutableExpr, mutableSource, ImmutableMap.of(), types);
  }

  private CelMutableAst(
      CelMutableExpr mutatedExpr,
      CelMutableSource mutableSource,
      Map<Long, CelReference> references,
      Map<Long, CelType> types) {
    this.mutatedExpr = mutatedExpr;
    this.source = mutableSource;
    this.references = ImmutableMap.copyOf(references);
    this.types = ImmutableMap.copyOf(types);
  }
}
