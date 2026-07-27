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

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.collect.ImmutableList.toImmutableList;

import dev.cel.expr.Decl;
import dev.cel.expr.Decl.FunctionDecl;
import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableSet;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.errorprone.annotations.CheckReturnValue;
import com.google.errorprone.annotations.Immutable;
import dev.cel.common.annotations.Internal;
import java.util.Arrays;

/** Abstract representation of a CEL Function declaration. */
@AutoValue
@Immutable
public abstract class CelFunctionDecl {

  /** Fully qualified name of the function. */
  public abstract String name();

  /** Required. List of function overloads. Must contain at least one overload. */
  public abstract ImmutableSet<CelOverloadDecl> overloads();

  /** General interface for defining an extension function overload or standard declaration. */
  @Immutable
  public interface Declarer {
    CelFunctionDecl functionDecl();
  }

  /** Builder for configuring the {@link CelFunctionDecl}. */
  @AutoValue.Builder
  public abstract static class Builder {
    public abstract String name();

    /** Sets the function name {@link #name()} */
    public abstract Builder setName(String name);

    public abstract ImmutableSet<CelOverloadDecl> overloads();

    public abstract ImmutableSet.Builder<CelOverloadDecl> overloadsBuilder();

    @CanIgnoreReturnValue
    public abstract Builder setOverloads(ImmutableSet<CelOverloadDecl> overloads);

    /** Adds one or more function overloads */
    @CanIgnoreReturnValue
    public Builder addOverloads(CelOverloadDecl... overloads) {
      checkNotNull(overloads);
      return addOverloads(Arrays.asList(overloads));
    }

    /** Adds a collection of overloads */
    @CanIgnoreReturnValue
    public Builder addOverloads(Iterable<CelOverloadDecl> overloads) {
      checkNotNull(overloads);
      this.overloadsBuilder().addAll(overloads);
      return this;
    }

    /** Builds a new instance of {@link CelFunctionDecl} */
    @CheckReturnValue
    public abstract CelFunctionDecl build();
  }

  public abstract Builder toBuilder();

  /** Create a new builder to construct a {@code CelFunctionDecl} instance. */
  public static Builder newBuilder() {
    return new AutoValue_CelFunctionDecl.Builder().setOverloads(ImmutableSet.of());
  }

  /** Constructs a function declaration with any number of {@link CelOverloadDecl} */
  public static CelFunctionDecl newFunctionDeclaration(
      String functionName, CelOverloadDecl... overloads) {
    return newFunctionDeclaration(functionName, Arrays.asList(overloads));
  }

  /** Constructs a function declaration with an iterable of {@link CelOverloadDecl} */
  public static CelFunctionDecl newFunctionDeclaration(
      String functionName, Iterable<CelOverloadDecl> overloads) {
    return CelFunctionDecl.newBuilder().setName(functionName).addOverloads(overloads).build();
  }

  /** Converts a {@link CelFunctionDecl} to a protobuf equivalent form {@link FunctionDecl} */
  @Internal
  public static Decl celFunctionDeclToDecl(CelFunctionDecl celFunctionDecl) {
    return Decl.newBuilder()
        .setName(celFunctionDecl.name())
        .setFunction(
            FunctionDecl.newBuilder()
                .addAllOverloads(
                    celFunctionDecl.overloads().stream()
                        .map(CelOverloadDecl::celOverloadToOverload)
                        .collect(toImmutableList())))
        .build();
  }
}
