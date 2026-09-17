// Copyright 2022 Google LLC
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

import com.google.auto.value.AutoValue;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.errorprone.annotations.CheckReturnValue;
import com.google.errorprone.annotations.Immutable;
import dev.cel.common.ast.CelConstant;
import dev.cel.common.types.CelType;
import java.util.Optional;

/** Abstract representation of a CEL variable declaration. */
@AutoValue
@Immutable
public abstract class CelVarDecl {

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

  /** Converts this instance into a builder. */
  public abstract Builder toBuilder();

  /** Create a new {@code CelVarDecl} with a given {@code name} and {@code type}. */
  @CheckReturnValue
  public static CelVarDecl newVarDeclaration(String name, CelType type) {
    return newBuilder().setName(name).setType(type).build();
  }

  /** Create a new builder to construct a {@link CelVarDecl} instance. */
  public static Builder newBuilder() {
    return new AutoValue_CelVarDecl.Builder().setDoc("");
  }

  /** Builder for configuring the {@link CelVarDecl}. */
  @AutoValue.Builder
  public abstract static class Builder {
    @CanIgnoreReturnValue
    public abstract Builder setName(String name);

    @CanIgnoreReturnValue
    public abstract Builder setType(CelType type);

    @CanIgnoreReturnValue
    public abstract Builder setConstant(CelConstant constant);

    @CanIgnoreReturnValue
    public abstract Builder setConstant(Optional<CelConstant> constant);

    @CanIgnoreReturnValue
    public abstract Builder setDoc(String doc);

    @CanIgnoreReturnValue
    public Builder clearConstant() {
      return setConstant(Optional.empty());
    }

    @CheckReturnValue
    public abstract CelVarDecl build();

    Builder() {}
  }

  CelVarDecl() {}
}
