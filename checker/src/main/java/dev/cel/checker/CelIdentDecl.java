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

package dev.cel.checker;

import dev.cel.expr.Decl;
import dev.cel.expr.Decl.IdentDecl;
import com.google.auto.value.AutoValue;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.errorprone.annotations.CheckReturnValue;
import com.google.errorprone.annotations.Immutable;
import dev.cel.common.annotations.Internal;
import dev.cel.common.ast.CelConstant;
import dev.cel.common.ast.CelExprConverter;
import dev.cel.common.types.CelProtoTypes;
import dev.cel.common.types.CelType;
import java.util.Optional;

/**
 * Abstract representation of a CEL identifier declaration.
 *
 * <p>CEL Library Internals. Do Not Use.
 */
@AutoValue
@Immutable
@Internal
public abstract class CelIdentDecl {

  /** Fully qualified variable name. */
  public abstract String name();

  /** The type of the variable. */
  public abstract CelType type();

  /**
   * The constant value of the identifier. If not specified, the identifier must be supplied at
   * evaluation time.
   */
  public abstract Optional<CelConstant> constant();

  /** Documentation string for the identifier. */
  public abstract String doc();

  /** If set, the identifier will get inlined as a constant value during type-check. */
  abstract boolean isInlinable();

  /** Converts a {@link CelIdentDecl} to a protobuf equivalent form {@code Decl} */
  public static Decl celIdentToDecl(CelIdentDecl identDecl) {
    IdentDecl.Builder identBuilder =
        IdentDecl.newBuilder()
            .setDoc(identDecl.doc())
            .setType(CelProtoTypes.celTypeToType(identDecl.type()));
    if (identDecl.constant().isPresent()) {
      identBuilder.setValue(CelExprConverter.celConstantToExprConstant(identDecl.constant().get()));
    }
    return Decl.newBuilder().setName(identDecl.name()).setIdent(identBuilder).build();
  }

  /** Create a new {@code CelIdentDecl} with a given {@code name} and {@code type}. */
  @CheckReturnValue
  public static CelIdentDecl newIdentDeclaration(String name, CelType type) {
    return newBuilder().setName(name).setType(type).build();
  }

  public static Builder newBuilder() {
    return new AutoValue_CelIdentDecl.Builder().setDoc("").setIsInlinable(false);
  }

  /** Builder for configuring the {@link CelIdentDecl}. */
  @AutoValue.Builder
  public abstract static class Builder {
    @CanIgnoreReturnValue
    public abstract Builder setName(String name);

    @CanIgnoreReturnValue
    public abstract Builder setType(CelType name);

    @CanIgnoreReturnValue
    public abstract Builder setConstant(CelConstant constant);

    @CanIgnoreReturnValue
    public abstract Builder setConstant(Optional<CelConstant> constant);

    @CanIgnoreReturnValue
    public abstract Builder setDoc(String value);

    public abstract Builder setIsInlinable(boolean value);

    @CanIgnoreReturnValue
    public Builder clearConstant() {
      return setConstant(Optional.empty());
    }

    @CheckReturnValue
    public abstract CelIdentDecl build();
  }
}
