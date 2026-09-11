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

package dev.cel.common.ast;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.collect.ImmutableList.toImmutableList;

import com.google.auto.value.AutoOneOf;
import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.errorprone.annotations.CheckReturnValue;
import com.google.errorprone.annotations.Immutable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Optional;

/**
 * An abstract representation of a common expression. Refer to {@link Expression} for details.
 *
 * <p>This is the native type equivalent of Expr message in syntax.proto.
 */
@AutoValue
@AutoValue.CopyAnnotations
@Immutable
@SuppressWarnings("unchecked") // Class ensures only the super type is used
public abstract class CelExpr implements Expression {

  /**
   * Shared instance of the {@link ExprKind.Kind#NOT_SET} kind. {@link CelNotSet} carries no state,
   * so a single instance can back every unset expression.
   */
  private static final ExprKind NOT_SET_KIND =
      AutoOneOf_CelExpr_ExprKind.notSet(new AutoValue_CelExpr_CelNotSet());

  /** Shared instance of an expression with an unset kind and a zero id. */
  private static final CelExpr NOT_SET_EXPR = ofNotSet(0L);

  @Override
  public abstract long id();

  /** Represents the variant of the expression. */
  public abstract ExprKind exprKind();

  @Override
  public ExprKind.Kind getKind() {
    return exprKind().getKind();
  }

  /**
   * {@inheritDoc}
   *
   * @throws UnsupportedOperationException if expression is not {@link Kind#CONSTANT}.
   */
  @Override
  public CelConstant constant() {
    return exprKind().constant();
  }

  /**
   * {@inheritDoc}
   *
   * @throws UnsupportedOperationException if expression is not {@link Kind#IDENT}.
   */
  @Override
  public CelIdent ident() {
    return exprKind().ident();
  }

  /**
   * {@inheritDoc}
   *
   * @throws UnsupportedOperationException if expression is not {@link Kind#SELECT}.
   */
  @Override
  public CelSelect select() {
    return exprKind().select();
  }

  /**
   * {@inheritDoc}
   *
   * @throws UnsupportedOperationException if expression is not {@link Kind#CALL}.
   */
  @Override
  public CelCall call() {
    return exprKind().call();
  }

  /**
   * {@inheritDoc}
   *
   * @throws UnsupportedOperationException if expression is not {@link Kind#LIST}.
   */
  @Override
  public CelList list() {
    return exprKind().list();
  }

  /**
   * {@inheritDoc}
   *
   * @throws UnsupportedOperationException if expression is not {@link Kind#STRUCT}.
   */
  @Override
  public CelStruct struct() {
    return exprKind().struct();
  }

  /**
   * {@inheritDoc}
   *
   * @throws UnsupportedOperationException if expression is not {@link Kind#MAP}.
   */
  @Override
  public CelMap map() {
    return exprKind().map();
  }

  /**
   * {@inheritDoc}
   *
   * @throws UnsupportedOperationException if expression is not {@link Kind#COMPREHENSION}.
   */
  @Override
  public CelComprehension comprehension() {
    return exprKind().comprehension();
  }

  /**
   * Gets the underlying constant expression or a default instance of one if expression is not
   * {@link Kind#CONSTANT}.
   */
  public CelConstant constantOrDefault() {
    return exprKind().getKind().equals(ExprKind.Kind.CONSTANT)
        ? exprKind().constant()
        : CelConstant.ofNotSet();
  }

  /**
   * Gets the underlying identifier expression or a default instance of one if expression is not
   * {@link Kind#IDENT}.
   */
  public CelIdent identOrDefault() {
    return exprKind().getKind().equals(ExprKind.Kind.IDENT)
        ? exprKind().ident()
        : CelIdent.newBuilder().build();
  }

  /**
   * Gets the underlying select expression or a default instance of one if expression is not {@link
   * Kind#SELECT}.
   */
  public CelSelect selectOrDefault() {
    return exprKind().getKind().equals(ExprKind.Kind.SELECT)
        ? exprKind().select()
        : CelSelect.newBuilder().build();
  }

  /**
   * Gets the underlying call expression or a default instance of one if expression is not {@link
   * Kind#CALL}.
   */
  public CelCall callOrDefault() {
    return exprKind().getKind().equals(ExprKind.Kind.CALL)
        ? exprKind().call()
        : CelCall.newBuilder().build();
  }

  /**
   * Gets the underlying list expression or a default instance of one if expression is not {@link
   * Kind#LIST}.
   */
  public CelList listOrDefault() {
    return exprKind().getKind().equals(ExprKind.Kind.LIST)
        ? exprKind().list()
        : CelList.newBuilder().build();
  }

  /**
   * Gets the underlying struct expression or a default instance of one if expression is not {@link
   * Kind#STRUCT}.
   */
  public CelStruct structOrDefault() {
    return exprKind().getKind().equals(ExprKind.Kind.STRUCT)
        ? exprKind().struct()
        : CelStruct.newBuilder().build();
  }

  /**
   * Gets the underlying map expression or a default instance of one if expression is not {@link
   * Kind#MAP}.
   */
  public CelMap mapOrDefault() {
    return exprKind().getKind().equals(ExprKind.Kind.MAP)
        ? exprKind().map()
        : CelMap.newBuilder().build();
  }

  /**
   * Gets the underlying comprehension expression or a default instance of one if expression is not
   * {@link Kind#COMPREHENSION}.
   */
  public CelComprehension comprehensionOrDefault() {
    return exprKind().getKind().equals(ExprKind.Kind.COMPREHENSION)
        ? exprKind().comprehension()
        : CelComprehension.newBuilder().build();
  }

  /** Builder for CelExpr. */
  @AutoValue.Builder
  public abstract static class Builder {

    public abstract long id();

    public abstract Builder setId(long value);

    public abstract Builder setExprKind(ExprKind value);

    public abstract ExprKind exprKind();

    /**
     * Gets the underlying constant expression.
     *
     * @throws UnsupportedOperationException if expression is not {@link Kind#CONSTANT}.
     */
    public CelConstant constant() {
      return exprKind().constant();
    }

    /**
     * Gets the underlying identifier expression.
     *
     * @throws UnsupportedOperationException if expression is not {@link Kind#IDENT}.
     */
    public CelIdent ident() {
      return exprKind().ident();
    }

    /**
     * Gets the underlying select expression.
     *
     * @throws UnsupportedOperationException if expression is not {@link Kind#SELECT}.
     */
    public CelSelect select() {
      return exprKind().select();
    }

    /**
     * Gets the underlying call expression.
     *
     * @throws UnsupportedOperationException if expression is not {@link Kind#CALL}.
     */
    public CelCall call() {
      return exprKind().call();
    }

    /**
     * Gets the underlying list expression.
     *
     * @throws UnsupportedOperationException if expression is not {@link Kind#LIST}.
     */
    public CelList list() {
      return exprKind().list();
    }

    /**
     * Gets the underlying struct expression.
     *
     * @throws UnsupportedOperationException if expression is not {@link Kind#STRUCT}.
     */
    public CelStruct struct() {
      return exprKind().struct();
    }

    /**
     * Gets the underlying map expression.
     *
     * @throws UnsupportedOperationException if expression is not {@link Kind#MAP}.
     */
    public CelMap map() {
      return exprKind().map();
    }

    /**
     * Gets the underlying comprehension expression.
     *
     * @throws UnsupportedOperationException if expression is not {@link Kind#COMPREHENSION}.
     */
    public CelComprehension comprehension() {
      return exprKind().comprehension();
    }

    @CanIgnoreReturnValue
    public Builder setConstant(CelConstant constant) {
      return setExprKind(AutoOneOf_CelExpr_ExprKind.constant(constant));
    }

    @CanIgnoreReturnValue
    public Builder setIdent(CelIdent ident) {
      return setExprKind(AutoOneOf_CelExpr_ExprKind.ident(ident));
    }

    @CanIgnoreReturnValue
    public Builder setCall(CelCall call) {
      return setExprKind(AutoOneOf_CelExpr_ExprKind.call(call));
    }

    @CanIgnoreReturnValue
    public Builder setSelect(CelSelect select) {
      return setExprKind(AutoOneOf_CelExpr_ExprKind.select(select));
    }

    @CanIgnoreReturnValue
    public Builder setList(CelList list) {
      return setExprKind(AutoOneOf_CelExpr_ExprKind.list(list));
    }

    @CanIgnoreReturnValue
    public Builder setStruct(CelStruct struct) {
      return setExprKind(AutoOneOf_CelExpr_ExprKind.struct(struct));
    }

    @CanIgnoreReturnValue
    public Builder setMap(CelMap map) {
      return setExprKind(AutoOneOf_CelExpr_ExprKind.map(map));
    }

    @CanIgnoreReturnValue
    public Builder setComprehension(CelComprehension comprehension) {
      return setExprKind(AutoOneOf_CelExpr_ExprKind.comprehension(comprehension));
    }

    @CheckReturnValue
    public abstract CelExpr build();
  }

  public abstract Builder toBuilder();

  public static Builder newBuilder() {
    return new AutoValue_CelExpr.Builder().setId(0).setExprKind(NOT_SET_KIND);
  }

  /** Denotes the kind of the expression. An expression can only be of one kind. */
  @AutoOneOf(CelExpr.ExprKind.Kind.class)
  @Immutable
  public abstract static class ExprKind {

    /** Expression kind. */
    public enum Kind {
      NOT_SET,
      CONSTANT,
      IDENT,
      SELECT,
      CALL,
      LIST,
      STRUCT,
      MAP,
      COMPREHENSION,
    }

    public abstract ExprKind.Kind getKind();

    public abstract CelNotSet notSet();

    public abstract CelConstant constant();

    public abstract CelIdent ident();

    public abstract CelSelect select();

    public abstract CelCall call();

    public abstract CelList list();

    public abstract CelStruct struct();

    public abstract CelMap map();

    public abstract CelComprehension comprehension();
  }

  /**
   * An unset expression.
   *
   * <p>As the name implies, this expression does nothing. This only exists to maintain
   * compatibility between Expr proto to native CelExpr conversion
   */
  @AutoValue
  @Immutable
  public abstract static class CelNotSet {}

  /** An identifier expression. e.g. `request`. */
  @AutoValue
  @Immutable
  public abstract static class CelIdent implements Ident {

    @Override
    public abstract String name();

    /** Builder for CelIdent. */
    @AutoValue.Builder
    public abstract static class Builder {

      public abstract Builder setName(String value);

      @CheckReturnValue
      public abstract CelIdent build();
    }

    public abstract Builder toBuilder();

    public static Builder newBuilder() {
      return new AutoValue_CelExpr_CelIdent.Builder().setName("");
    }
  }

  /** A field selection expression. e.g. `request.auth`. */
  @AutoValue
  @Immutable
  public abstract static class CelSelect implements Expression.Select<CelExpr> {

    @Override
    public abstract CelExpr operand();

    @Override
    public abstract String field();

    @Override
    public abstract boolean testOnly();

    /** Builder for CelSelect. */
    @AutoValue.Builder
    public abstract static class Builder {
      public abstract CelExpr operand();

      public abstract String field();

      public abstract boolean testOnly();

      public abstract Builder setOperand(CelExpr value);

      public abstract Builder setField(String value);

      public abstract Builder setTestOnly(boolean value);

      @CheckReturnValue
      public abstract CelSelect build();
    }

    public abstract Builder toBuilder();

    public static Builder newBuilder() {
      return new AutoValue_CelExpr_CelSelect.Builder()
          .setField("")
          .setOperand(NOT_SET_EXPR)
          .setTestOnly(false);
    }
  }

  /** A call expression. See {@link Expression.Call} */
  @AutoValue
  @Immutable
  public abstract static class CelCall implements Expression.Call<CelExpr> {

    @Override
    public abstract Optional<CelExpr> target();

    @Override
    public abstract String function();

    @Override
    public abstract ImmutableList<CelExpr> args();

    /** Builder for CelCall. */
    @AutoValue.Builder
    public abstract static class Builder {
      private java.util.List<CelExpr> mutableArgs = new ArrayList<>();

      // Not public. This only exists to make AutoValue.Builder work.
      abstract ImmutableList<CelExpr> args();

      public abstract Builder setTarget(CelExpr value);

      public abstract Builder setTarget(Optional<CelExpr> value);

      public abstract Builder setFunction(String value);

      public abstract Optional<CelExpr> target();

      // Not public. This only exists to make AutoValue.Builder work.
      abstract Builder setArgs(ImmutableList<CelExpr> value);

      /** Returns an immutable copy of the current mutable arguments present in the builder. */
      public ImmutableList<CelExpr> getArgs() {
        return ImmutableList.copyOf(mutableArgs);
      }

      /** Returns an immutable copy of the builders from the current mutable arguments. */
      public ImmutableList<CelExpr.Builder> getArgsBuilders() {
        return mutableArgs.stream().map(CelExpr::toBuilder).collect(toImmutableList());
      }

      @CanIgnoreReturnValue
      public Builder clearArgs() {
        mutableArgs.clear();
        return this;
      }

      @CanIgnoreReturnValue
      public Builder setArg(int index, CelExpr arg) {
        checkNotNull(arg);
        mutableArgs.set(index, arg);
        return this;
      }

      public Builder clearTarget() {
        return setTarget(Optional.empty());
      }

      @CanIgnoreReturnValue
      public Builder addArgs(CelExpr... args) {
        checkNotNull(args);
        Collections.addAll(mutableArgs, args);
        return this;
      }

      @CanIgnoreReturnValue
      public Builder addArgs(Iterable<CelExpr> args) {
        checkNotNull(args);
        Iterables.addAll(mutableArgs, args);
        return this;
      }

      @CheckReturnValue
      // Not public due to overridden build logic.
      abstract CelCall autoBuild();

      public CelCall build() {
        setArgs(ImmutableList.copyOf(mutableArgs));
        return autoBuild();
      }
    }

    // Not public due to overridden build logic.
    abstract Builder autoToBuilder();

    public Builder toBuilder() {
      Builder builder = autoToBuilder();
      builder.mutableArgs = new ArrayList<>(builder.args());
      return builder;
    }

    public static Builder newBuilder() {
      return new AutoValue_CelExpr_CelCall.Builder().setFunction("");
    }
  }

  /** A list creation expression. See {@link List} */
  @AutoValue
  @Immutable
  public abstract static class CelList implements List<CelExpr> {
    @Override
    public abstract ImmutableList<CelExpr> elements();

    @Override
    public abstract ImmutableList<Integer> optionalIndices();

    /** Builder for CelList. */
    @AutoValue.Builder
    public abstract static class Builder {
      private java.util.List<CelExpr> mutableElements = new ArrayList<>();

      // Not public. This only exists to make AutoValue.Builder work.
      abstract ImmutableList<CelExpr> elements();

      // Not public. This only exists to make AutoValue.Builder work.
      abstract ImmutableList.Builder<Integer> optionalIndicesBuilder();

      // Not public. This only exists to make AutoValue.Builder work.
      @CanIgnoreReturnValue
      abstract Builder setElements(ImmutableList<CelExpr> elements);

      /** Returns an immutable copy of the current mutable elements present in the builder. */
      public ImmutableList<CelExpr> getElements() {
        return ImmutableList.copyOf(mutableElements);
      }

      /** Returns an immutable copy of the builders from the current mutable elements. */
      public ImmutableList<CelExpr.Builder> getElementsBuilders() {
        return mutableElements.stream().map(CelExpr::toBuilder).collect(toImmutableList());
      }

      @CanIgnoreReturnValue
      public Builder setElement(int index, CelExpr element) {
        checkNotNull(element);
        mutableElements.set(index, element);
        return this;
      }

      @CanIgnoreReturnValue
      public Builder addElements(CelExpr... elements) {
        checkNotNull(elements);
        Collections.addAll(mutableElements, elements);
        return this;
      }

      @CanIgnoreReturnValue
      public Builder addElements(Iterable<CelExpr> elements) {
        checkNotNull(elements);
        Iterables.addAll(mutableElements, elements);
        return this;
      }

      @CanIgnoreReturnValue
      public Builder addOptionalIndices(Integer... indices) {
        checkNotNull(indices);
        return addOptionalIndices(Arrays.asList(indices));
      }

      @CanIgnoreReturnValue
      public Builder addOptionalIndices(Iterable<Integer> indices) {
        checkNotNull(indices);
        this.optionalIndicesBuilder().addAll(indices);
        return this;
      }

      // Not public due to overridden build logic.
      abstract CelList autoBuild();

      @CheckReturnValue
      public CelList build() {
        setElements(ImmutableList.copyOf(mutableElements));
        return autoBuild();
      }
    }

    // Not public due to overridden build logic.
    abstract Builder autoToBuilder();

    public Builder toBuilder() {
      Builder builder = autoToBuilder();
      builder.mutableElements = new ArrayList<>(builder.elements());
      return builder;
    }

    public static Builder newBuilder() {
      return new AutoValue_CelExpr_CelList.Builder();
    }
  }

  /** A message creation expression. See {@link Expression.Struct} */
  @AutoValue
  @Immutable
  public abstract static class CelStruct implements Expression.Struct<CelStruct.Entry> {
    @Override
    public abstract String messageName();

    @Override
    public abstract ImmutableList<CelStruct.Entry> entries();

    /** Builder for CelStruct. */
    @AutoValue.Builder
    public abstract static class Builder {
      private java.util.List<CelStruct.Entry> mutableEntries = new ArrayList<>();

      // Not public. This only exists to make AutoValue.Builder work.
      abstract ImmutableList<CelStruct.Entry> entries();

      @CanIgnoreReturnValue
      public abstract Builder setMessageName(String value);

      // Not public. This only exists to make AutoValue.Builder work.
      @CanIgnoreReturnValue
      abstract Builder setEntries(ImmutableList<CelStruct.Entry> entries);

      /** Returns an immutable copy of the current mutable entries present in the builder. */
      public ImmutableList<CelStruct.Entry> getEntries() {
        return ImmutableList.copyOf(mutableEntries);
      }

      /** Returns an immutable copy of the builders from the current mutable entries. */
      public ImmutableList<CelStruct.Entry.Builder> getEntriesBuilders() {
        return mutableEntries.stream().map(CelStruct.Entry::toBuilder).collect(toImmutableList());
      }

      @CanIgnoreReturnValue
      public Builder setEntry(int index, CelStruct.Entry entry) {
        checkNotNull(entry);
        mutableEntries.set(index, entry);
        return this;
      }

      @CanIgnoreReturnValue
      public Builder addEntries(CelStruct.Entry... entries) {
        checkNotNull(entries);
        Collections.addAll(mutableEntries, entries);
        return this;
      }

      @CanIgnoreReturnValue
      public Builder addEntries(Iterable<CelStruct.Entry> entries) {
        checkNotNull(entries);
        Iterables.addAll(mutableEntries, entries);
        return this;
      }

      // Not public due to overridden build logic.
      abstract CelStruct autoBuild();

      @CheckReturnValue
      public CelStruct build() {
        setEntries(ImmutableList.copyOf(mutableEntries));
        return autoBuild();
      }
    }

    // Not public due to overridden build logic.
    abstract Builder autoToBuilder();

    public Builder toBuilder() {
      Builder builder = autoToBuilder();
      builder.mutableEntries = new ArrayList<>(builder.entries());
      return builder;
    }

    public static Builder newBuilder() {
      return new AutoValue_CelExpr_CelStruct.Builder().setMessageName("");
    }

    /** Represents an entry of the struct */
    @AutoValue
    @Immutable
    public abstract static class Entry implements Expression.Struct.Entry<CelExpr> {

      @Override
      public abstract long id();

      @Override
      public abstract String fieldKey();

      @Override
      public abstract CelExpr value();

      @Override
      public abstract boolean optionalEntry();

      /** Builder for CelStruct.Entry. */
      @AutoValue.Builder
      public abstract static class Builder {

        public abstract long id();

        public abstract CelExpr value();

        public abstract Builder setId(long value);

        public abstract Builder setFieldKey(String value);

        public abstract Builder setValue(CelExpr value);

        public abstract Builder setOptionalEntry(boolean value);

        @CheckReturnValue
        public abstract CelStruct.Entry build();
      }

      public abstract Builder toBuilder();

      public static Builder newBuilder() {
        return new AutoValue_CelExpr_CelStruct_Entry.Builder().setId(0).setOptionalEntry(false);
      }
    }
  }

  /** A map creation expression. See {@link Expression.Map} */
  @AutoValue
  @Immutable
  public abstract static class CelMap implements Expression.Map<CelMap.Entry> {
    /** The entries in the creation expression. */
    @Override
    public abstract ImmutableList<CelMap.Entry> entries();

    /** Builder for CelMap. */
    @AutoValue.Builder
    public abstract static class Builder {

      private java.util.List<CelMap.Entry> mutableEntries = new ArrayList<>();

      // Not public. This only exists to make AutoValue.Builder work.
      abstract ImmutableList<CelMap.Entry> entries();

      // Not public. This only exists to make AutoValue.Builder work.
      @CanIgnoreReturnValue
      abstract Builder setEntries(ImmutableList<CelMap.Entry> entries);

      /** Returns an immutable copy of the current mutable entries present in the builder. */
      public ImmutableList<CelMap.Entry> getEntries() {
        return ImmutableList.copyOf(mutableEntries);
      }

      /** Returns an immutable copy of the builders from the current mutable entries. */
      public ImmutableList<CelMap.Entry.Builder> getEntriesBuilders() {
        return mutableEntries.stream().map(CelMap.Entry::toBuilder).collect(toImmutableList());
      }

      @CanIgnoreReturnValue
      public Builder setEntry(int index, CelMap.Entry entry) {
        checkNotNull(entry);
        mutableEntries.set(index, entry);
        return this;
      }

      @CanIgnoreReturnValue
      public Builder addEntries(CelMap.Entry... entries) {
        checkNotNull(entries);
        Collections.addAll(mutableEntries, entries);
        return this;
      }

      @CanIgnoreReturnValue
      public Builder addEntries(Iterable<CelMap.Entry> entries) {
        checkNotNull(entries);
        Iterables.addAll(mutableEntries, entries);
        return this;
      }

      // Not public due to overridden build logic.
      abstract CelMap autoBuild();

      @CheckReturnValue
      public CelMap build() {
        setEntries(ImmutableList.copyOf(mutableEntries));
        return autoBuild();
      }
    }

    // Not public due to overridden build logic.
    abstract Builder autoToBuilder();

    public Builder toBuilder() {
      Builder builder = autoToBuilder();
      builder.mutableEntries = new ArrayList<>(builder.entries());
      return builder;
    }

    public static Builder newBuilder() {
      return new AutoValue_CelExpr_CelMap.Builder();
    }

    /** Represents an entry of the map. */
    @AutoValue
    @Immutable
    public abstract static class Entry implements Expression.Map.Entry<CelExpr> {

      @Override
      public abstract long id();

      @Override
      public abstract CelExpr key();

      @Override
      public abstract CelExpr value();

      @Override
      public abstract boolean optionalEntry();

      /** Builder for CelMap.Entry. */
      @AutoValue.Builder
      public abstract static class Builder {
        public abstract long id();

        public abstract CelExpr key();

        public abstract CelExpr value();

        public abstract CelMap.Entry.Builder setId(long value);

        public abstract CelMap.Entry.Builder setKey(CelExpr value);

        public abstract CelMap.Entry.Builder setValue(CelExpr value);

        public abstract CelMap.Entry.Builder setOptionalEntry(boolean value);

        @CheckReturnValue
        public abstract CelMap.Entry build();
      }

      public abstract CelMap.Entry.Builder toBuilder();

      public static CelMap.Entry.Builder newBuilder() {
        return new AutoValue_CelExpr_CelMap_Entry.Builder().setId(0).setOptionalEntry(false);
      }
    }
  }

  /** A comprehension expression applied to a list or map. See {@link Expression.Comprehension} */
  @AutoValue
  @Immutable
  public abstract static class CelComprehension implements Expression.Comprehension<CelExpr> {
    @Override
    public abstract String iterVar();

    @Override
    public abstract String iterVar2();

    @Override
    public abstract CelExpr iterRange();

    @Override
    public abstract String accuVar();

    @Override
    public abstract CelExpr accuInit();

    @Override
    public abstract CelExpr loopCondition();

    @Override
    public abstract CelExpr loopStep();

    @Override
    public abstract CelExpr result();

    /** Builder for Comprehension. */
    @AutoValue.Builder
    public abstract static class Builder {
      public abstract String accuVar();

      public abstract CelExpr iterRange();

      public abstract CelExpr accuInit();

      public abstract CelExpr loopCondition();

      public abstract CelExpr loopStep();

      public abstract CelExpr result();

      public abstract Builder setIterVar(String value);

      public abstract Builder setIterVar2(String value);

      public abstract Builder setIterRange(CelExpr value);

      public abstract Builder setAccuVar(String value);

      public abstract Builder setAccuInit(CelExpr value);

      public abstract Builder setLoopCondition(CelExpr value);

      public abstract Builder setLoopStep(CelExpr value);

      public abstract Builder setResult(CelExpr value);

      @CheckReturnValue
      public abstract CelComprehension build();
    }

    public abstract Builder toBuilder();

    public static Builder newBuilder() {
      return new AutoValue_CelExpr_CelComprehension.Builder()
          .setIterVar("")
          .setIterVar2("")
          .setIterRange(NOT_SET_EXPR)
          .setAccuVar("")
          .setAccuInit(NOT_SET_EXPR)
          .setLoopCondition(NOT_SET_EXPR)
          .setLoopStep(NOT_SET_EXPR)
          .setResult(NOT_SET_EXPR);
    }
  }

  public static CelExpr ofNotSet(long id) {
    return newBuilder().setId(id).setExprKind(NOT_SET_KIND).build();
  }

  public static CelExpr ofConstant(long id, CelConstant celConstant) {
    return newBuilder()
        .setId(id)
        .setExprKind(AutoOneOf_CelExpr_ExprKind.constant(celConstant))
        .build();
  }

  public static CelExpr ofIdent(long id, String identName) {
    return newBuilder()
        .setId(id)
        .setExprKind(
            AutoOneOf_CelExpr_ExprKind.ident(CelIdent.newBuilder().setName(identName).build()))
        .build();
  }

  public static CelExpr ofSelect(long id, CelExpr operandExpr, String field, boolean isTestOnly) {
    return newBuilder()
        .setId(id)
        .setExprKind(
            AutoOneOf_CelExpr_ExprKind.select(
                CelSelect.newBuilder()
                    .setOperand(operandExpr)
                    .setField(field)
                    .setTestOnly(isTestOnly)
                    .build()))
        .build();
  }

  /** Creates a global (non receiver-style) call expression. */
  public static CelExpr ofCall(long id, String function, ImmutableList<CelExpr> arguments) {
    return ofCall(id, Optional.empty(), function, arguments);
  }

  public static CelExpr ofCall(
      long id, Optional<CelExpr> targetExpr, String function, ImmutableList<CelExpr> arguments) {
    // setArgs/autoBuild are used in place of addArgs/build so that the already-immutable argument
    // list is handed straight to the value class, skipping a copy through the builder's mutable
    // list. This is on the hot path of every parse.
    CelCall celCall =
        CelCall.newBuilder()
            .setFunction(function)
            .setTarget(targetExpr)
            .setArgs(arguments)
            .autoBuild();
    return newBuilder().setId(id).setExprKind(AutoOneOf_CelExpr_ExprKind.call(celCall)).build();
  }

  public static CelExpr ofList(
      long id, ImmutableList<CelExpr> elements, ImmutableList<Integer> optionalIndices) {
    CelList celList =
        CelList.newBuilder()
            .setElements(elements)
            .addOptionalIndices(optionalIndices)
            .autoBuild();
    return newBuilder().setId(id).setExprKind(AutoOneOf_CelExpr_ExprKind.list(celList)).build();
  }

  public static CelExpr ofStruct(
      long id, String messageName, ImmutableList<CelStruct.Entry> entries) {
    CelStruct celStruct =
        CelStruct.newBuilder().setMessageName(messageName).setEntries(entries).autoBuild();
    return newBuilder().setId(id).setExprKind(AutoOneOf_CelExpr_ExprKind.struct(celStruct)).build();
  }

  public static CelExpr ofMap(long id, ImmutableList<CelMap.Entry> entries) {
    CelMap celMap = CelMap.newBuilder().setEntries(entries).autoBuild();
    return newBuilder().setId(id).setExprKind(AutoOneOf_CelExpr_ExprKind.map(celMap)).build();
  }

  public static CelStruct.Entry ofStructEntry(
      long id, String fieldKey, CelExpr value, boolean isOptionalEntry) {
    return CelStruct.Entry.newBuilder()
        .setId(id)
        .setFieldKey(fieldKey)
        .setValue(value)
        .setOptionalEntry(isOptionalEntry)
        .build();
  }

  public static CelMap.Entry ofMapEntry(
      long id, CelExpr mapKey, CelExpr value, boolean isOptionalEntry) {
    return CelMap.Entry.newBuilder()
        .setId(id)
        .setKey(mapKey)
        .setValue(value)
        .setOptionalEntry(isOptionalEntry)
        .build();
  }

  public static CelExpr ofComprehension(
      long id,
      String iterVar,
      CelExpr iterRange,
      String accuVar,
      CelExpr accuInit,
      CelExpr loopCondition,
      CelExpr loopStep,
      CelExpr result) {
    return ofComprehension(
        id,
        iterVar,
        /* iterVar2= */ "",
        iterRange,
        accuVar,
        accuInit,
        loopCondition,
        loopStep,
        result);
  }

  @SuppressWarnings("TooManyParameters")
  public static CelExpr ofComprehension(
      long id,
      String iterVar,
      String iterVar2,
      CelExpr iterRange,
      String accuVar,
      CelExpr accuInit,
      CelExpr loopCondition,
      CelExpr loopStep,
      CelExpr result) {
    return newBuilder()
        .setId(id)
        .setExprKind(
            AutoOneOf_CelExpr_ExprKind.comprehension(
                CelComprehension.newBuilder()
                    .setIterVar(iterVar)
                    .setIterVar2(iterVar2)
                    .setIterRange(iterRange)
                    .setAccuVar(accuVar)
                    .setAccuInit(accuInit)
                    .setLoopCondition(loopCondition)
                    .setLoopStep(loopStep)
                    .setResult(result)
                    .build()))
        .build();
  }

  @Override
  public final String toString() {
    return CelExprFormatter.format(this);
  }
}
