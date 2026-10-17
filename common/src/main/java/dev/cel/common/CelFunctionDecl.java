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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableSet;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.errorprone.annotations.CheckReturnValue;
import com.google.errorprone.annotations.Immutable;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

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

    @CheckReturnValue
    abstract CelFunctionDecl autoBuild();

    /**
     * Builds a new instance of {@link CelFunctionDecl}.
     *
     * <p>Any {@link CelOverloadDecl} with an empty {@link CelOverloadDecl#overloadId()} will have
     * its ID automatically populated with its canonical overload signature.
     */
    @CheckReturnValue
    public CelFunctionDecl build() {
      ImmutableSet<CelOverloadDecl> currentOverloads = overloads();
      boolean needsOverloadId = false;
      for (CelOverloadDecl overload : currentOverloads) {
        if (overload.overloadId().isEmpty()) {
          needsOverloadId = true;
          break;
        }
      }
      if (!needsOverloadId) {
        return autoBuild();
      }
      String functionName = name();
      ImmutableSet.Builder<CelOverloadDecl> updatedOverloads =
          ImmutableSet.builderWithExpectedSize(currentOverloads.size());
      Set<String> seenOverloadIds = new HashSet<>();
      for (CelOverloadDecl overload : currentOverloads) {
        String overloadId = overload.overloadId();
        if (overloadId.isEmpty()) {
          overloadId =
              CelSignatureUtils.makeOverloadSignature(
                  functionName, overload.parameterTypes(), overload.isInstanceFunction());
          updatedOverloads.add(overload.toBuilder().setOverloadId(overloadId).build());
        } else {
          updatedOverloads.add(overload);
        }
        checkArgument(
            seenOverloadIds.add(overloadId), "Duplicate overload signature: %s", overloadId);
      }
      return new AutoValue_CelFunctionDecl.Builder()
          .setName(functionName)
          .setOverloads(updatedOverloads.build())
          .autoBuild();
    }
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
}
