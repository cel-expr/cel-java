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

package dev.cel.extensions;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.collect.ImmutableSet.toImmutableSet;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import dev.cel.checker.CelCheckerBuilder;
import dev.cel.common.CelFunctionDecl;
import dev.cel.common.CelIssue;
import dev.cel.common.CelOptions;
import dev.cel.common.CelOverloadDecl;
import dev.cel.common.Operator;
import dev.cel.common.ast.CelExpr;
import dev.cel.common.internal.ComparisonFunctions;
import dev.cel.common.types.ListType;
import dev.cel.common.types.SimpleType;
import dev.cel.common.types.TypeParamType;
import dev.cel.common.values.CelByteString;
import dev.cel.compiler.CelCompilerLibrary;
import dev.cel.parser.CelMacro;
import dev.cel.parser.CelMacroExprFactory;
import dev.cel.parser.CelParserBuilder;
import dev.cel.runtime.CelFunctionBinding;
import dev.cel.runtime.CelInternalRuntimeLibrary;
import dev.cel.runtime.CelRuntimeBuilder;
import dev.cel.runtime.RuntimeEquality;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Internal implementation of CEL lists extensions. */
public final class CelListsExtensions
    implements CelCompilerLibrary, CelInternalRuntimeLibrary, CelExtensionLibrary.FeatureSet {

  /** Supported functions for Lists extension library. */
  @SuppressWarnings({"unchecked"}) // Unchecked: Type-checker guarantees casting safety.
  public enum Function {
    // Note! Creating dependencies on the outer class may cause circular initialization issues.
    SLICE(
        CelFunctionDecl.newFunctionDeclaration(
            "slice",
            CelOverloadDecl.newMemberOverload(
                "list_slice",
                "Returns a new sub-list using the indices provided",
                ListType.create(TypeParamType.create("T")),
                ListType.create(TypeParamType.create("T")),
                SimpleType.INT,
                SimpleType.INT)),
        CelFunctionBinding.from(
            "list_slice",
            ImmutableList.of(Collection.class, Long.class, Long.class),
            (args) -> {
              Collection<Object> target = (Collection<Object>) args[0];
              long from = (Long) args[1];
              long to = (Long) args[2];
              return CelListsExtensions.slice(target, from, to);
            })),
    FLATTEN(
        CelFunctionDecl.newFunctionDeclaration(
            "flatten",
            CelOverloadDecl.newMemberOverload(
                "list_flatten",
                "Flattens a list by a single level",
                ListType.create(TypeParamType.create("T")),
                ListType.create(ListType.create(TypeParamType.create("T")))),
            CelOverloadDecl.newMemberOverload(
                "list_flatten_list_int",
                "Flattens a list to the specified level. A negative depth value flattens the list"
                    + " recursively to its deepest level.",
                ListType.create(SimpleType.DYN),
                ListType.create(SimpleType.DYN),
                SimpleType.INT)),
        CelFunctionBinding.from("list_flatten", Collection.class, list -> flatten(list, 1)),
        CelFunctionBinding.from(
            "list_flatten_list_int", Collection.class, Long.class, CelListsExtensions::flatten)),
    RANGE(
        CelFunctionDecl.newFunctionDeclaration(
            "lists.range",
            CelOverloadDecl.newGlobalOverload(
                "lists_range",
                "Returns a list of integers from 0 to n-1.",
                ListType.create(SimpleType.INT),
                SimpleType.INT)),
        CelFunctionBinding.from("lists_range", Long.class, CelListsExtensions::genRange)),
    DISTINCT(
        CelFunctionDecl.newFunctionDeclaration(
            "distinct",
            CelOverloadDecl.newMemberOverload(
                "list_distinct",
                "Returns the distinct elements of a list",
                ListType.create(TypeParamType.create("T")),
                ListType.create(TypeParamType.create("T"))))),
    REVERSE(
        CelFunctionDecl.newFunctionDeclaration(
            "reverse",
            CelOverloadDecl.newMemberOverload(
                "list_reverse",
                "Returns the elements of a list in reverse order",
                ListType.create(TypeParamType.create("T")),
                ListType.create(TypeParamType.create("T")))),
        CelFunctionBinding.from("list_reverse", Collection.class, CelListsExtensions::reverse)),
    SORT(
        CelFunctionDecl.newFunctionDeclaration(
            "sort",
            CelOverloadDecl.newMemberOverload(
                "list_sort",
                "Sorts a list with comparable elements.",
                ListType.create(TypeParamType.create("T")),
                ListType.create(TypeParamType.create("T")))),
        CelFunctionBinding.from("list_sort", Collection.class, CelListsExtensions::sort)),
    SORT_BY(
        CelFunctionDecl.newFunctionDeclaration(
            "@sortByAssociatedKeys",
            CelOverloadDecl.newMemberOverload(
                "list_sortByAssociatedKeys",
                "Sorts a list by associated keys. Used by the 'sortBy' macro",
                ListType.create(TypeParamType.create("T")),
                ListType.create(TypeParamType.create("T")),
                ListType.create(TypeParamType.create("U")))),
        CelFunctionBinding.from(
            "list_sortByAssociatedKeys",
            ImmutableList.of(Collection.class, Collection.class),
            (args) -> {
              Collection<Object> target = (Collection<Object>) args[0];
              Collection<Object> keys = (Collection<Object>) args[1];
              return CelListsExtensions.sortByAssociatedKeys(target, keys);
            }));

    private final CelFunctionDecl functionDecl;
    private final ImmutableSet<CelFunctionBinding> functionBindings;

    String getFunction() {
      return functionDecl.name();
    }

    Function(CelFunctionDecl functionDecl, CelFunctionBinding... functionBindings) {
      this.functionDecl = functionDecl;
      this.functionBindings =
          functionBindings.length > 0
              ? CelFunctionBinding.fromOverloads(functionDecl.name(), functionBindings)
              : ImmutableSet.of();
    }
  }

  private static final CelExtensionLibrary<CelListsExtensions> LIBRARY =
      new CelExtensionLibrary<CelListsExtensions>() {
        private final CelListsExtensions version0 =
            new CelListsExtensions(0, ImmutableSet.of(Function.SLICE));
        private final CelListsExtensions version1 =
            new CelListsExtensions(
                1,
                ImmutableSet.<Function>builder()
                    .addAll(version0.functions)
                    .add(Function.FLATTEN)
                    .build());
        private final CelListsExtensions version2 =
            new CelListsExtensions(
                2,
                ImmutableSet.<Function>builder()
                    .addAll(version1.functions)
                    .add(
                        Function.RANGE,
                        Function.DISTINCT,
                        Function.REVERSE,
                        Function.SORT,
                        Function.SORT_BY)
                    .build());

        @Override
        public String name() {
          return "lists";
        }

        @Override
        public ImmutableSet<CelListsExtensions> versions() {
          return ImmutableSet.of(version0, version1, version2);
        }
      };

  static CelExtensionLibrary<CelListsExtensions> library() {
    return LIBRARY;
  }

  private final int version;
  private final ImmutableSet<Function> functions;

  CelListsExtensions(Set<Function> functions) {
    this(-1, functions);
  }

  private CelListsExtensions(int version, Set<Function> functions) {
    this.version = version;
    this.functions = ImmutableSet.copyOf(functions);
  }

  @Override
  public int version() {
    return version;
  }

  @Override
  public ImmutableSet<CelFunctionDecl> functions() {
    return functions.stream().map(f -> f.functionDecl).collect(toImmutableSet());
  }

  @Override
  public ImmutableSet<CelMacro> macros() {
    if (version >= 2 || (version == -1 && functions.contains(Function.SORT_BY))) {
      return ImmutableSet.of(
          CelMacro.newReceiverMacro("sortBy", 2, CelListsExtensions::sortByMacro));
    }
    return ImmutableSet.of();
  }

  @Override
  public void setParserOptions(CelParserBuilder parserBuilder) {
    parserBuilder.addMacros(macros());
  }

  @Override
  public void setCheckerOptions(CelCheckerBuilder checkerBuilder) {
    functions.forEach(function -> checkerBuilder.addFunctionDeclarations(function.functionDecl));
  }

  @Override
  public void setRuntimeOptions(CelRuntimeBuilder runtimeBuilder) {
    throw new UnsupportedOperationException("Unsupported");
  }

  @SuppressWarnings("unchecked")
  @Override
  public void setRuntimeOptions(
      CelRuntimeBuilder runtimeBuilder, RuntimeEquality runtimeEquality, CelOptions celOptions) {
    functions.forEach(function -> runtimeBuilder.addFunctionBindings(function.functionBindings));

    runtimeBuilder.addFunctionBindings(
        CelFunctionBinding.fromOverloads(
            "distinct",
            CelFunctionBinding.from(
                "list_distinct", Collection.class, (list) -> distinct(list, runtimeEquality))));
  }

  private static ImmutableList<Object> slice(Collection<Object> list, long from, long to) {
    Preconditions.checkArgument(from >= 0 && to >= 0, "Negative indexes not supported");
    Preconditions.checkArgument(to >= from, "Start index must be less than or equal to end index");
    Preconditions.checkArgument(to <= list.size(), "List is length %s", list.size());
    if (list instanceof List) {
      List<Object> subList = ((List<Object>) list).subList((int) from, (int) to);
      if (subList instanceof ImmutableList) {
        return (ImmutableList<Object>) subList;
      }
      return ImmutableList.copyOf(subList);
    } else {
      ImmutableList.Builder<Object> builder = ImmutableList.builder();
      long index = 0;
      for (Iterator<Object> iterator = list.iterator(); iterator.hasNext(); index++) {
        Object element = iterator.next();
        if (index >= to) {
          break;
        }
        if (index >= from) {
          builder.add(element);
        }
      }
      return builder.build();
    }
  }

  @SuppressWarnings("unchecked")
  private static ImmutableList<Object> flatten(Collection<Object> list, long depth) {
    Preconditions.checkArgument(depth >= 0, "Level must be non-negative");
    ImmutableList.Builder<Object> builder = ImmutableList.builder();
    for (Object element : list) {
      if (!(element instanceof Collection) || depth == 0) {
        builder.add(element);
      } else {
        Collection<Object> listItem = (Collection<Object>) element;
        builder.addAll(flatten(listItem, depth - 1));
      }
    }

    return builder.build();
  }

  public static ImmutableList<Long> genRange(long end) {
    checkArgument(end >= 0, "lists.range: size must be non-negative, got %s", end);
    checkArgument(end <= 1_000_000, "lists.range: size %s exceeds maximum allowed (1000000)", end);

    ImmutableList.Builder<Long> builder = ImmutableList.builderWithExpectedSize((int) end);
    for (long i = 0; i < end; i++) {
      builder.add(i);
    }
    return builder.build();
  }

  private static class RuntimeEqualityObjectWrapper {
    private final Object object;
    private final int hashCode;
    private final RuntimeEquality runtimeEquality;

    RuntimeEqualityObjectWrapper(Object object, RuntimeEquality runtimeEquality) {
      this.object = object;
      this.runtimeEquality = runtimeEquality;
      this.hashCode = runtimeEquality.hashCode(object);
    }

    @Override
    public int hashCode() {
      return hashCode;
    }

    @Override
    public boolean equals(Object obj) {
      if (!(obj instanceof RuntimeEqualityObjectWrapper)) {
        return false;
      }
      return runtimeEquality.objectEquals(object, ((RuntimeEqualityObjectWrapper) obj).object);
    }
  }

  private static ImmutableList<Object> distinct(
      Collection<Object> list, RuntimeEquality runtimeEquality) {
    int size = list.size();
    ImmutableList.Builder<Object> builder = ImmutableList.builderWithExpectedSize(size);
    Set<RuntimeEqualityObjectWrapper> distinctValues = Sets.newHashSetWithExpectedSize(size);
    for (Object element : list) {
      if (distinctValues.add(new RuntimeEqualityObjectWrapper(element, runtimeEquality))) {
        builder.add(element);
      }
    }
    return builder.build();
  }

  private static List<Object> reverse(Collection<Object> list) {
    if (list instanceof List) {
      return Lists.reverse((List<Object>) list);
    } else {
      ImmutableList.Builder<Object> builder = ImmutableList.builderWithExpectedSize(list.size());
      Object[] objects = list.toArray();
      for (int i = objects.length - 1; i >= 0; i--) {
        builder.add(objects[i]);
      }
      return builder.build();
    }
  }

  private static ImmutableList<Object> sort(Collection<Object> objects) {
    if (objects.isEmpty()) {
      return ImmutableList.of();
    }
    for (Object element : objects) {
      if (!isSupportedComparableType(element)) {
        throw new IllegalArgumentException("List elements must be comparable");
      }
    }
    if (objects.size() < 2) {
      return ImmutableList.copyOf(objects);
    }
    return ImmutableList.sortedCopyOf(new CelObjectComparator(), objects);
  }

  private static class CelObjectComparator implements Comparator<Object> {

    CelObjectComparator() {}

    @SuppressWarnings({"unchecked"})
    @Override
    public int compare(Object o1, Object o2) {
      if (o1 == null || o2 == null) {
        throw new IllegalArgumentException("List elements must be comparable");
      }
      if (o1 instanceof Number && o2 instanceof Number) {
        return ComparisonFunctions.numericCompare((Number) o1, (Number) o2);
      }
      if (isByteType(o1) && isByteType(o2)) {
        return compareBytes(o1, o2);
      }
      if (o1.getClass() != o2.getClass()) {
        throw new IllegalArgumentException("List elements must have the same type");
      }
      return ((Comparable) o1).compareTo(o2);
    }
  }

  private static boolean isByteType(Object obj) {
    return obj instanceof CelByteString || obj instanceof byte[];
  }

  private static int compareBytes(Object o1, Object o2) {
    if (o1 instanceof CelByteString && o2 instanceof CelByteString) {
      return CelByteString.unsignedLexicographicalComparator()
          .compare((CelByteString) o1, (CelByteString) o2);
    }

    byte[] b1 = o1 instanceof CelByteString ? ((CelByteString) o1).toByteArray() : (byte[]) o1;
    byte[] b2 = o2 instanceof CelByteString ? ((CelByteString) o2).toByteArray() : (byte[]) o2;

    int minLength = Math.min(b1.length, b2.length);
    for (int i = 0; i < minLength; i++) {
      int result = Integer.compare(Byte.toUnsignedInt(b1[i]), Byte.toUnsignedInt(b2[i]));
      if (result != 0) {
        return result;
      }
    }
    return Integer.compare(b1.length, b2.length);
  }

  private static boolean isSupportedComparableType(Object obj) {
    if (obj == null) {
      return false;
    }
    if (obj instanceof Number
        || obj instanceof Boolean
        || obj instanceof String
        || obj instanceof CelByteString
        || obj instanceof byte[]
        || obj instanceof Duration
        || obj instanceof Instant) {
      return true;
    }
    return obj instanceof Comparable && !(obj instanceof Collection) && !(obj instanceof Map);
  }

  private static Optional<CelExpr> sortByMacro(
      CelMacroExprFactory exprFactory, CelExpr target, ImmutableList<CelExpr> arguments) {
    checkNotNull(exprFactory);
    checkNotNull(target);
    checkArgument(arguments.size() == 2);
    CelExpr varIdent = checkNotNull(arguments.get(0));
    if (varIdent.exprKind().getKind() != CelExpr.ExprKind.Kind.IDENT) {
      return Optional.of(
          exprFactory.reportError(
              CelIssue.formatError(
                  exprFactory.getSourceLocation(varIdent),
                  "sortBy(var, ...) variable name must be a simple identifier")));
    }

    String varName = varIdent.ident().name();
    CelExpr sortKeyExpr = checkNotNull(arguments.get(1));

    String sortByInputVar = "@__sortBy_input__";
    CelExpr sortByInputIdent = exprFactory.newIdentifier(sortByInputVar);

    // Map comprehension: target.map(varName, sortKeyExpr)
    CelExpr mapStep =
        exprFactory.newGlobalCall(
            Operator.ADD.getFunction(),
            exprFactory.newIdentifier(exprFactory.getAccumulatorVarName()),
            exprFactory.newList(sortKeyExpr));
    CelExpr mapCompr =
        exprFactory.fold(
            varName,
            sortByInputIdent,
            exprFactory.getAccumulatorVarName(),
            exprFactory.newList(),
            exprFactory.newBoolLiteral(true),
            mapStep,
            exprFactory.newIdentifier(exprFactory.getAccumulatorVarName()));

    // Receiver call: sortByInputIdent.@sortByAssociatedKeys(mapCompr)
    CelExpr callExpr =
        exprFactory.newReceiverCall(
            Function.SORT_BY.getFunction(),
            sortByInputIdent,
            mapCompr);

    // cel.bind(sortByInputVar, target, callExpr)
    CelExpr bindExpr =
        exprFactory.fold(
            "#unused",
            exprFactory.newList(),
            sortByInputVar,
            target,
            // Loop condition is false because this comprehension simulates a local variable assignment (`bind`), rather than a traditional iteration.
            exprFactory.newBoolLiteral(false),
            sortByInputIdent,
            callExpr);

    return Optional.of(bindExpr);
  }

  private static ImmutableList<Object> sortByAssociatedKeys(
      Collection<Object> list, Collection<Object> keys) {
    if (list.size() != keys.size()) {
      throw new IllegalArgumentException(
          String.format(
              "@sortByAssociatedKeys() expected a list of the same size as the associated keys"
                  + " list, but got %d and %d elements respectively.",
              list.size(), keys.size()));
    }
    if (list.isEmpty()) {
      return ImmutableList.of();
    }
    for (Object key : keys) {
      if (!isSupportedComparableType(key)) {
        throw new IllegalArgumentException("List elements must be comparable");
      }
    }
    if (list.size() < 2) {
      return ImmutableList.copyOf(list);
    }
    Object[] listArray = list.toArray();
    Object[] keysArray = keys.toArray();
    Integer[] indices = new Integer[listArray.length];
    for (int i = 0; i < indices.length; i++) {
      indices[i] = i;
    }
    CelObjectComparator comparator = new CelObjectComparator();
    Arrays.sort(indices, (i1, i2) -> comparator.compare(keysArray[i1], keysArray[i2]));
    ImmutableList.Builder<Object> builder = ImmutableList.builderWithExpectedSize(indices.length);
    for (int idx : indices) {
      builder.add(listArray[idx]);
    }
    return builder.build();
  }
}
