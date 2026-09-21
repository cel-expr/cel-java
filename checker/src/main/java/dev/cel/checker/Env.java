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

import dev.cel.expr.Constant;
import dev.cel.expr.Decl;
import dev.cel.expr.Decl.FunctionDecl.Overload;
import dev.cel.expr.Expr;
import dev.cel.expr.Type;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.errorprone.annotations.CheckReturnValue;
import dev.cel.checker.CelStandardDeclarations.StandardFunction.Overload.Comparison;
import dev.cel.checker.CelStandardDeclarations.StandardFunction.Overload.Conversions;
import dev.cel.common.CelContainer;
import dev.cel.common.CelFunctionDecl;
import dev.cel.common.CelOptions;
import dev.cel.common.CelOverloadDecl;
import dev.cel.common.CelProtoDeclConverter;
import dev.cel.common.CelVarDecl;
import dev.cel.common.annotations.Internal;
import dev.cel.common.ast.CelConstant;
import dev.cel.common.ast.CelExpr;
import dev.cel.common.ast.CelExprConverter;
import dev.cel.common.ast.CelMutableExpr;
import dev.cel.common.ast.CelReference;
import dev.cel.common.internal.Errors;
import dev.cel.common.types.CelKind;
import dev.cel.common.types.CelProtoTypes;
import dev.cel.common.types.CelType;
import dev.cel.common.types.CelTypeProvider;
import dev.cel.common.types.CelTypes;
import dev.cel.common.types.EnumType;
import dev.cel.common.types.OpaqueType;
import dev.cel.common.types.SimpleType;
import dev.cel.common.types.StructType;
import dev.cel.common.types.StructTypeReference;
import dev.cel.common.types.TypeType;
import dev.cel.parser.CelStandardMacro;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Environment used during checking of expressions. Provides name resolution and error reporting.
 *
 * <p>Note: the environment is not thread-safe. Create multiple envs from scratch for working with
 * different threads.
 *
 * <p>CEL Library Internals. Do Not Use. CEL-Java users should leverage the Fluent APIs instead. See
 * {@code CelCompilerFactory}.
 */
@Internal
public class Env {

  /** The top-most scope in the environment, for use with {@link #getDeclGroup(int)}. */
  public static final int ROOT_SCOPE = 0;

  /** An ident declaration to represent an error. */
  public static final CelVarDecl ERROR_IDENT_DECL =
      CelVarDecl.newBuilder().setName("*error*").setType(SimpleType.ERROR).build();

  /** A function declaration to represent an error. */
  public static final CelFunctionDecl ERROR_FUNCTION_DECL =
      CelFunctionDecl.newBuilder().setName("*error*").build();

  private static final CelTypeProvider EMPTY_TYPE_PROVIDER =
      new CelTypeProvider.CombinedCelTypeProvider(ImmutableList.of());

  /** Type provider responsible for resolving CEL types. */
  private final CelTypeProvider celTypeProvider;

  // TypeProvider is deprecated, but preserved for backwards compatibility.
  @SuppressWarnings("deprecation")
  private final @Nullable TypeProvider legacyTypeProvider;

  /**
   * Stack of declaration groups where each entry in stack represents a scope capable of hinding
   * declarations lower in the stack.
   */
  private final ArrayList<DeclGroup> decls = new ArrayList<>();

  /** A map from expression ids into references resolved for the overall tree so far. */
  private final Map<Long, CelReference> referenceMap = new LinkedHashMap<>();

  /** A map from expression ids into types resolved for the overall tree so far. */
  private final Map<Long, CelType> typeMap = new LinkedHashMap<>();

  /** Object used for error reporting. */
  private final Errors errors;

  /** CEL Feature flags. */
  private final CelOptions celOptions;

  private static final CelOptions LEGACY_TYPE_CHECKER_OPTIONS =
      CelOptions.newBuilder()
          .disableCelStandardEquality(false)
          .enableNamespacedDeclarations(false)
          .build();

  /**
   * @deprecated Do not use. This exists for compatibility reasons. Migrate to CEL-Java fluent APIs.
   *     See {@code CelCompilerFactory}.
   */
  @Deprecated
  public static Env unconfigured(Errors errors) {
    return unconfigured(errors, LEGACY_TYPE_CHECKER_OPTIONS);
  }

  /**
   * Creates an unconfigured {@code Env} value without the standard CEL types, functions, and
   * operators with a reference to the configured {@code celOptions}.
   */
  @VisibleForTesting
  static Env unconfigured(Errors errors, CelOptions celOptions) {
    return unconfigured(errors, new DescriptorTypeProvider(), celOptions);
  }

  /**
   * Creates an unconfigured {@code Env} value without the standard CEL types, functions, and
   * operators using a custom {@code celTypeProvider}.
   */
  static Env unconfigured(Errors errors, CelTypeProvider celTypeProvider, CelOptions celOptions) {
    return unconfigured(errors, celTypeProvider, null, celOptions);
  }

  // TypeProvider is deprecated, but preserved for backwards compatibility.
  @SuppressWarnings("deprecation")
  static Env unconfigured(
      Errors errors,
      CelTypeProvider celTypeProvider,
      @Nullable TypeProvider legacyTypeProvider,
      CelOptions celOptions) {
    return new Env(errors, celTypeProvider, legacyTypeProvider, new DeclGroup(), celOptions);
  }

  /**
   * Creates an unconfigured {@code Env} value without the standard CEL types, functions, and
   * operators using a custom {@code typeProvider}.
   *
   * @deprecated Do not use. This exists for compatibility reasons. Migrate to CEL-Java fluent APIs.
   *     See {@code CelCompilerFactory}.
   */
  @Deprecated
  public static Env unconfigured(Errors errors, TypeProvider typeProvider, CelOptions celOptions) {
    return unconfigured(errors, EMPTY_TYPE_PROVIDER, typeProvider, celOptions);
  }

  /**
   * @deprecated Do not use. This exists for compatibility reasons. Migrate to CEL-Java fluent APIs.
   *     See {@code CelCompilerFactory}.
   */
  @Deprecated
  public static Env standard(Errors errors) {
    return standard(errors, new DescriptorTypeProvider());
  }

  /**
   * @deprecated Do not use. This exists for compatibility reasons. Migrate to CEL-Java fluent APIs.
   *     See {@code CelCompilerFactory}.
   */
  @Deprecated
  public static Env standard(Errors errors, TypeProvider typeProvider) {
    return standard(errors, typeProvider, LEGACY_TYPE_CHECKER_OPTIONS);
  }

  static Env standard(Errors errors, CelTypeProvider celTypeProvider, CelOptions celOptions) {
    return standard(errors, celTypeProvider, null, celOptions);
  }

  // TypeProvider is deprecated, but preserved for backwards compatibility.
  @SuppressWarnings("deprecation")
  static Env standard(
      Errors errors,
      CelTypeProvider celTypeProvider,
      @Nullable TypeProvider legacyTypeProvider,
      CelOptions celOptions) {
    CelStandardDeclarations celStandardDeclaration = newStandardDeclarations(celOptions);
    return standard(
        celStandardDeclaration, errors, celTypeProvider, legacyTypeProvider, celOptions);
  }

  /**
   * Creates an {@code Env} value configured with the standard types, functions, and operators,
   * configured with a custom {@code typeProvider} and a reference to the {@code celOptions} to use
   * within the environment.
   *
   * <p>Note: standard declarations are configured in an isolated scope, and may be shadowed by
   * subsequent declarations with the same signature.
   *
   * @deprecated Do not use. This exists for compatibility reasons. Migrate to CEL-Java fluent APIs.
   *     See {@code CelCompilerFactory}.
   */
  @Deprecated
  public static Env standard(Errors errors, TypeProvider typeProvider, CelOptions celOptions) {
    return standard(
        newStandardDeclarations(celOptions), errors, EMPTY_TYPE_PROVIDER, typeProvider, celOptions);
  }

  static Env standard(
      CelStandardDeclarations celStandardDeclaration,
      Errors errors,
      CelTypeProvider celTypeProvider,
      CelOptions celOptions) {
    return standard(celStandardDeclaration, errors, celTypeProvider, null, celOptions);
  }

  // TypeProvider is deprecated, but preserved for backwards compatibility.
  @SuppressWarnings("deprecation")
  static Env standard(
      CelStandardDeclarations celStandardDeclaration,
      Errors errors,
      CelTypeProvider celTypeProvider,
      @Nullable TypeProvider legacyTypeProvider,
      CelOptions celOptions) {
    Env env = Env.unconfigured(errors, celTypeProvider, legacyTypeProvider, celOptions);
    // Isolate the standard declarations into their own scope for forward compatibility.
    celStandardDeclaration.functionDecls().forEach(env::add);
    celStandardDeclaration.identifierDecls().forEach(env::add);

    env.enterScope();
    return env;
  }

  @Deprecated
  public static Env standard(
      CelStandardDeclarations celStandardDeclaration,
      Errors errors,
      TypeProvider typeProvider,
      CelOptions celOptions) {
    return standard(celStandardDeclaration, errors, EMPTY_TYPE_PROVIDER, typeProvider, celOptions);
  }

  private static CelStandardDeclarations newStandardDeclarations(CelOptions celOptions) {
    return CelStandardDeclarations.newBuilder()
        .filterFunctions(
            (function, overload) -> {
              switch (function) {
                case INT:
                  if (!celOptions.enableUnsignedLongs()
                      && overload.equals(Conversions.INT64_TO_INT64)) {
                    return false;
                  }
                  break;
                case TIMESTAMP:
                  // TODO: Remove this flag guard once the feature has been
                  // auto-enabled.
                  if (!celOptions.enableTimestampEpoch()
                      && overload.equals(Conversions.INT64_TO_TIMESTAMP)) {
                    return false;
                  }
                  break;
                default:
                  if (!celOptions.enableHeterogeneousNumericComparisons()
                      && overload instanceof Comparison) {
                    Comparison comparison = (Comparison) overload;
                    if (comparison.isHeterogeneousComparison()) {
                      return false;
                    }
                  }
                  break;
              }
              return true;
            })
        .build();
  }

  /** Returns the current Errors object. */
  public Errors getErrorContext() {
    return errors;
  }

  /** Returns the {@code CelTypeProvider}. */
  public CelTypeProvider getCelTypeProvider() {
    return celTypeProvider;
  }

  /**
   * Returns the {@code TypeProvider}, or {@code null} if only modern {@link CelTypeProvider} was
   * configured.
   *
   * @deprecated Use {@link #getCelTypeProvider()} instead.
   */
  @Deprecated
  // TypeProvider is deprecated, but preserved for backwards compatibility.
  @SuppressWarnings("deprecation")
  @Nullable TypeProvider getTypeProvider() {
    return legacyTypeProvider;
  }

  /**
   * Enters a new scope. All new declarations added to the environment exist only in this scope, and
   * will shadow declarations of the same name in outer scopes. This includes overloads in outer
   * scope (overloads from different scopes are not merged).
   */
  public void enterScope() {
    decls.add(new DeclGroup());
  }

  /** Exits a previously opened scope, forgetting all declarations created in this scope. */
  public void exitScope() {
    Preconditions.checkState(!decls.isEmpty(), "Cannot exit top-level environment scope");
    decls.remove(decls.size() - 1);
  }

  /** Return the current scope depth for the environment. */
  public int scopeDepth() {
    return decls.size() - 1;
  }

  /** Returns the top-most declaration scope. */
  public DeclGroup getDeclGroup() {
    return Iterables.getLast(decls);
  }

  /**
   * Returns the {@code DeclGroup} at the given {@code scopeDepth}, where depth of {@code 0}
   * represents root scope.
   */
  public DeclGroup getDeclGroup(int scopeDepth) {
    Preconditions.checkArgument(
        scopeDepth <= scopeDepth() && scopeDepth >= 0, "Invalid scope depth.");
    return decls.get(scopeDepth);
  }

  /** Reset type and ref maps. This must be called before type checking an expression. */
  public void resetTypeAndRefMaps() {
    typeMap.clear();
    referenceMap.clear();
  }

  /** Returns the reference map. */
  public Map<Long, CelReference> getRefMap() {
    return referenceMap;
  }

  /** Returns the type map. */
  public Map<Long, CelType> getTypeMap() {
    return typeMap;
  }

  /**
   * Returns the type associated with an expression by expression id. It's an error to call this
   * method if the type is not present.
   *
   * @deprecated Do not use. Migrate to CEL-Java fluent APIs.
   */
  @Deprecated
  public Type getType(Expr expr) {
    Preconditions.checkNotNull(expr);
    CelExpr celExpr = CelExprConverter.fromExpr(expr);
    CelType celType =
        Preconditions.checkNotNull(typeMap.get(celExpr.id()), "expression has no type");
    return CelProtoTypes.celTypeToType(celType);
  }

  /**
   * Returns the type associated with a mutable expression by expression id. It's an error to call
   * this method if the type is not present.
   */
  CelType getType(CelMutableExpr expr) {
    return Preconditions.checkNotNull(typeMap.get(expr.id()), "expression has no type");
  }

  /**
   * Sets the type associated with a mutable expression by id. It's an error if the type is already
   * set and is different than the provided one. Returns the expression parameter.
   */
  @CanIgnoreReturnValue
  CelMutableExpr setType(CelMutableExpr expr, CelType type) {
    CelType oldType = typeMap.put(expr.id(), type);
    Preconditions.checkState(
        oldType == null || oldType.equals(type),
        "expression already has a type which is incompatible.\n old: %s\n new: %s",
        oldType,
        type);
    return expr;
  }

  /**
   * Sets the reference associated with a mutable expression. It's an error if the reference is
   * already set and is different.
   */
  void setRef(CelMutableExpr expr, CelReference reference) {
    CelReference oldReference = referenceMap.put(expr.id(), reference);
    Preconditions.checkState(
        oldReference == null || oldReference.equals(reference),
        "expression already has a reference which is incompatible");
  }

  /**
   * Adds a declaration to the environment, based on the Decl proto. Will report errors if the
   * declaration overlaps with an existing one, or clashes with a macro.
   *
   * @deprecated Migrate to the CEL-Java fluent APIs and leverage the publicly available native
   *     types (e.g: {@code CelCompilerFactory} accepts {@code CelFunctionDecl} and {@code
   *     CelVarDecl}).
   */
  @CanIgnoreReturnValue
  @Deprecated
  public Env add(Decl decl) {
    switch (decl.getDeclKindCase()) {
      case IDENT:
        return add(CelProtoDeclConverter.declToCelVarDecl(decl));
      case FUNCTION:
        return add(CelProtoDeclConverter.declToCelFunctionDecl(decl));
      default:
        break;
    }
    return this;
  }

  @CanIgnoreReturnValue
  public Env add(CelFunctionDecl celFunctionDecl) {
    return addFunction(sanitizeFunction(celFunctionDecl));
  }

  @CanIgnoreReturnValue
  public Env add(CelVarDecl celVarDecl) {
    return addIdent(sanitizeIdent(celVarDecl));
  }

  /**
   * Adds simple name declaration to the environment for a non-function.
   *
   * @deprecated Migrate to the CEL-Java fluent APIs and leverage the publicly available native
   *     types (e.g: {@code CelCompilerFactory} accepts {@code CelFunctionDecl} and {@code
   *     CelVarDecl}).
   */
  @CanIgnoreReturnValue
  @Deprecated
  public Env add(String name, Type type) {
    return add(CelVarDecl.newVarDeclaration(name, CelProtoTypes.typeToCelType(type)));
  }

  /**
   * Note: Used by legacy type-checker users
   *
   * @deprecated Use {@link #tryLookupCelFunction} instead.
   */
  @Deprecated
  public @Nullable Decl tryLookupFunction(String container, String name) {
    CelFunctionDecl decl = tryLookupCelFunction(CelContainer.ofName(container), name);
    if (decl == null) {
      return null;
    }

    return CelProtoDeclConverter.celFunctionDeclToDecl(decl);
  }

  /**
   * Try to lookup a function with the given {@code name} within a {@code container}.
   *
   * <p>For protos, the {@code container} may be a package or message name. The code tries to
   * resolve the {@code name} first in the container, then within the container parent, and so on
   * until the root package is reached. If {@code container} starts with {@code .}, the resolution
   * is in the root container only.
   *
   * <p>Returns {@code null} if the function cannot be found.
   */
  public @Nullable CelFunctionDecl tryLookupCelFunction(CelContainer container, String name) {
    for (String cand : container.resolveCandidateNames(name)) {
      // First determine whether we know this name already.
      CelFunctionDecl decl = findFunctionDecl(cand);
      if (decl != null) {
        return decl;
      }
    }
    return null;
  }

  /**
   * @deprecated Use {@link #tryLookupCelIdent} instead.
   */
  @Deprecated
  public @Nullable Decl tryLookupIdent(CelContainer container, String name) {
    CelVarDecl decl = tryLookupCelIdent(container, name);
    if (decl == null) {
      return null;
    }

    return CelProtoDeclConverter.celVarDeclToDecl(decl);
  }

  /**
   * Try to lookup an identifier with the given {@code name} within a {@code container}.
   *
   * <p>For protos, the {@code container} may be a package or message name. The code tries to
   * resolve the {@code name} first in the container, then within the container parent, and so on
   * until the root package is reached. If {@code container} starts with {@code .}, the resolution
   * is in the root container only.
   *
   * <p>Returns {@code null} if the ident cannot be found.
   */
  public @Nullable CelVarDecl tryLookupCelIdent(CelContainer container, String name) {
    // A name with a leading '.' always resolves in the root scope, bypassing local scopes.
    if (!name.startsWith(".")) {
      // Check if this is a qualified ident, or a field selection.
      String simpleName = name;
      int dotIndex = name.indexOf('.');
      if (dotIndex > 0) {
        simpleName = name.substring(0, dotIndex);
      }

      // Attempt to find the decl with just the ident name to account for shadowed variables.
      CelVarDecl decl = tryLookupCelIdentFromLocalScopes(simpleName);
      if (decl != null) {
        // Appears to be a field selection on a local.
        // Return null instead of attempting to resolve qualified name at the root scope
        return dotIndex > 0 ? null : decl;
      }
    }

    for (String cand : container.resolveCandidateNames(name)) {
      CelVarDecl decl = tryLookupCelIdent(cand);
      if (decl != null) {
        return decl;
      }
    }

    return null;
  }

  private @Nullable CelVarDecl tryLookupCelIdent(String cand) {
    // First determine whether we know this name already.
    CelVarDecl decl = findIdentDecl(cand);
    if (decl != null) {
      return decl;
    }

    // Next try to import the name as a reference to a message type.
    // This is done via the type provider.
    Optional<CelType> type =
        celTypeProvider
            .findType(cand)
            .filter(t -> !(t instanceof EnumType) || t.name().equals(cand))
            .map(Env::wrapAsTypeIfNeeded);
    if (!type.isPresent() && legacyTypeProvider != null) {
      type = legacyTypeProvider.lookupCelType(cand);
    }
    if (type.isPresent()) {
      decl = CelVarDecl.newVarDeclaration(cand, type.get());
      decls.get(0).putIdent(decl);
      return decl;
    }

    // Next try to import this as an enum value by splitting the name in a type prefix and
    // the enum inside.
    Optional<Integer> enumValue = lookupEnumValue(cand);
    if (enumValue.isPresent()) {
      decl =
          CelVarDecl.newBuilder()
              .setName(cand)
              .setType(SimpleType.INT)
              .setConstant(CelConstant.ofValue(enumValue.get()))
              .build();

      decls.get(0).putIdent(decl);
      return decl;
    }
    return null;
  }

  private Optional<Integer> lookupEnumValue(String enumName) {
    int dotIndex = enumName.lastIndexOf(".");
    if (dotIndex > 0 && dotIndex < enumName.length() - 1) {
      String enumTypeName = enumName.substring(0, dotIndex);
      String localEnumName = enumName.substring(dotIndex + 1);
      Optional<Integer> enumValue =
          celTypeProvider
              .findType(enumTypeName)
              .filter(t -> t instanceof EnumType)
              .flatMap(t -> ((EnumType) t).findNumberByName(localEnumName));
      if (enumValue.isPresent()) {
        return enumValue;
      }
      enumValue =
          celTypeProvider
              .findType(enumName)
              .filter(t -> t instanceof EnumType)
              .flatMap(t -> ((EnumType) t).findNumberByName(localEnumName));
      if (enumValue.isPresent()) {
        return enumValue;
      }
    }
    if (legacyTypeProvider != null) {
      return Optional.ofNullable(legacyTypeProvider.lookupEnumValue(enumName));
    }
    return Optional.empty();
  }

  private static CelType wrapAsTypeIfNeeded(CelType type) {
    if (type instanceof StructType
        || type instanceof StructTypeReference
        || type instanceof EnumType
        || type instanceof OpaqueType) {
      return TypeType.create(type);
    }
    return type;
  }

  /**
   * Lookup a local identifier by name. This searches only comprehension scopes, bypassing standard
   * environment or user-defined environment.
   *
   * <p>Returns {@code null} if not found in local scopes.
   */
  @Nullable CelVarDecl tryLookupCelIdentFromLocalScopes(String name) {
    int firstUserSpaceScope = 2;
    // Iterate from the top of the stack down to the first local scope.
    // Note that:
    // Scope 0: Standard environment
    // Scope 1: User defined environment
    // Scope 2 and onwards: comprehension scopes
    for (int i = decls.size() - 1; i >= firstUserSpaceScope; i--) {
      CelVarDecl ident = decls.get(i).getIdent(name);
      if (ident != null) {
        return ident;
      }
    }
    return null;
  }

  /**
   * Lookup a name like {@link #tryLookupCelIdent}, but report an error if the name is not found and
   * return the {@link #ERROR_IDENT_DECL}.
   */
  public CelVarDecl lookupIdent(long exprId, int position, CelContainer container, String name) {
    CelVarDecl result = tryLookupCelIdent(container, name);
    if (result == null) {
      reportError(
          exprId,
          position,
          "undeclared reference to '%s' (in container '%s')",
          name,
          container.name());
      return ERROR_IDENT_DECL;
    }
    return result;
  }

  /**
   * Lookup a name like {@link #tryLookupCelFunction} but report an error if the name is not found
   * and return the {@link #ERROR_FUNCTION_DECL}.
   */
  public CelFunctionDecl lookupFunction(
      long exprId, int position, CelContainer container, String name) {
    CelFunctionDecl result = tryLookupCelFunction(container, name);
    if (result == null) {
      reportError(
          exprId,
          position,
          "undeclared reference to '%s' (in container '%s')",
          name,
          container.name());
      return ERROR_FUNCTION_DECL;
    }
    return result;
  }

  /**
   * Note: Used by legacy type-checker users
   *
   * @deprecated Use {@link #reportError(long, int, String, Object...) instead.}
   */
  @Deprecated
  public void reportError(int position, String message, Object... args) {
    reportError(0L, position, message, args);
  }

  /** Reports an error. */
  public void reportError(long exprId, int position, String message, Object... args) {
    errors.reportError(exprId, position, message, args);
  }

  boolean enableCompileTimeOverloadResolution() {
    return celOptions.enableCompileTimeOverloadResolution();
  }

  boolean enableHomogeneousLiterals() {
    return celOptions.enableHomogeneousLiterals();
  }

  boolean enableNamespacedDeclarations() {
    return celOptions.enableNamespacedDeclarations();
  }

  /** Add an identifier {@code decl} to the environment. */
  @CanIgnoreReturnValue
  private Env addIdent(CelVarDecl celVarDecl) {
    CelVarDecl current = getDeclGroup().getIdent(celVarDecl.name());
    if (current == null) {
      getDeclGroup().putIdent(celVarDecl);
    } else {
      reportError(
          /* exprId= */ 0,
          /* position= */ 0,
          "overlapping declaration name '%s' (type '%s' cannot be distinguished from '%s')",
          celVarDecl.name(),
          CelTypes.format(current.type()),
          CelTypes.format(celVarDecl.type()));
    }
    return this;
  }

  /** Add a function {@code decl} to the environment. */
  @CanIgnoreReturnValue
  private Env addFunction(CelFunctionDecl decl) {
    CelFunctionDecl current = getDeclGroup().getFunction(decl.name());
    CelFunctionDecl.Builder builder =
        current != null ? current.toBuilder() : CelFunctionDecl.newBuilder().setName(decl.name());
    for (CelOverloadDecl overload : decl.overloads()) {
      addOverload(builder, overload);
    }
    getDeclGroup().putFunction(builder.build());
    return this;
  }

  /**
   * Attempt to extend the declaration with a new overload. This reports an error if the overload
   * overlaps with existing ones or with a macro.
   */
  private void addOverload(CelFunctionDecl.Builder builder, CelOverloadDecl overload) {
    // Compute the type of the overload with all type parameters replaced by DYN.
    // We are using a property of Types.substitute which replaces all unbound type
    // parameters by DYN.
    ImmutableMap<CelType, CelType> emptySubs = ImmutableMap.of();

    CelType overloadFunction =
        CelTypes.createFunctionType(overload.resultType(), overload.parameterTypes());
    CelType overloadTypeErased = Types.substitute(emptySubs, overloadFunction, true);

    // Loop over existing overloads to find any overlap.
    for (CelOverloadDecl existing : builder.overloads()) {
      CelType existingFunction =
          CelTypes.createFunctionType(existing.resultType(), existing.parameterTypes());
      CelType existingTypeErased = Types.substitute(emptySubs, existingFunction, true);
      boolean overlap =
          Types.isAssignable(emptySubs, overloadTypeErased, existingTypeErased) != null
              || Types.isAssignable(emptySubs, existingTypeErased, overloadTypeErased) != null;
      if (overlap && existing.isInstanceFunction() == overload.isInstanceFunction()) {
        reportError(
            /* exprId= */ 0,
            /* position= */ 0,
            "overlapping overload for name '%s' (type '%s' cannot be distinguished from '%s')",
            builder.name(),
            CelTypes.format(existingFunction),
            CelTypes.format(overloadFunction));
        return;
      }
    }

    // If this is a function, loop over macros to detect any clash.
    for (CelStandardMacro macro : CelStandardMacro.STANDARD_MACROS) {
      if (macro.getFunction().equals(builder.name())
          && macro.getDefinition().isReceiverStyle() == overload.isInstanceFunction()
          && macro.getDefinition().getArgumentCount() == overload.parameterTypes().size()) {
        reportError(
            /* exprId= */ 0,
            /* position= */ 0,
            "overload for name '%s' with %s argument(s) overlaps with predefined macro",
            builder.name(),
            macro.getDefinition().getArgumentCount());
        return;
      }
    }
    builder.addOverloads(overload);
  }

  /** Search for the named identifier declaration. */
  private @Nullable CelVarDecl findIdentDecl(String name) {
    for (DeclGroup declGroup : Lists.reverse(decls)) {
      CelVarDecl ident = declGroup.getIdent(name);
      if (ident != null) {
        return ident;
      }
    }
    return null;
  }

  /** Search for the named function declaration. */
  private @Nullable CelFunctionDecl findFunctionDecl(String name) {
    // Search bottom-up for the matching function declarations.
    List<CelFunctionDecl> functions = new ArrayList<>();
    for (DeclGroup declGroup : Lists.reverse(decls)) {
      CelFunctionDecl function = declGroup.getFunction(name);
      if (function != null) {
        functions.add(function);
      }
    }
    // If no functions have the declared name, return null.
    if (functions.isEmpty()) {
      return null;
    }
    // If only one function declaration has the specified name, return it.
    if (functions.size() == 1) {
      return functions.get(0);
    }
    // Otherwise form a composite view of the overloads, most specific first, as indicated by the
    // bottom-up traversal order of the declaration scopes.
    Map<String, CelOverloadDecl> overloadSignatureMap = new HashMap<>();
    for (CelFunctionDecl function : functions) {
      for (CelOverloadDecl overload : function.overloads()) {
        // The input signature is enough to disambiguate overloads. When two or more functions in
        // different scopes share the same signature, the function in the lowest scope will shadow
        // its ancestors.
        //
        // Note: declaring a function with the same signature in the same scope is an error.
        String overloadSignature =
            TypeFormatter.formatFunction(
                /* resultType= */ null,
                overload.parameterTypes(),
                overload.isInstanceFunction(),
                /* typeParamToDyn= */ true);
        overloadSignatureMap.putIfAbsent(overloadSignature, overload);
      }
    }

    return CelFunctionDecl.newBuilder()
        .setName(name)
        .addOverloads(overloadSignatureMap.values())
        .build();
  }

  /**
   * A helper class for constructing identifier declarations.
   *
   * @deprecated Use {@code CelVarDecl#newBuilder()} instead.
   */
  @Deprecated
  public static final class IdentBuilder {
    private final CelVarDecl.Builder builder = CelVarDecl.newBuilder();

    /** Create an identifier builder. */
    public IdentBuilder(String name) {
      builder.setName(Preconditions.checkNotNull(name));
    }

    /** Set the identifier type. */
    @CanIgnoreReturnValue
    public IdentBuilder type(Type value) {
      Preconditions.checkNotNull(value);
      builder.setType(CelProtoTypes.typeToCelType(Preconditions.checkNotNull(value)));
      return this;
    }

    /** Set the identifier to a {@code Constant} value. */
    @CanIgnoreReturnValue
    public IdentBuilder value(@Nullable Constant value) {
      if (value == null) {
        builder.clearConstant();
      } else {
        builder.setConstant(CelExprConverter.exprConstantToCelConstant(value));
      }
      return this;
    }

    /** Set the documentation for the identifier. */
    @CanIgnoreReturnValue
    public IdentBuilder doc(@Nullable String value) {
      if (value == null) {
        builder.setDoc("");
      } else {
        builder.setDoc(value);
      }
      return this;
    }

    /** Build the ident {@code Decl}. */
    public Decl build() {
      return CelProtoDeclConverter.celVarDeclToDecl(builder.build());
    }
  }

  /**
   * A helper class for building declarations.
   *
   * @deprecated Use {@link CelFunctionDecl#newBuilder()} instead.
   */
  @Deprecated
  public static final class FunctionBuilder {

    private final String name;
    private final List<CelOverloadDecl> overloads = new ArrayList<>();
    private final boolean isInstance;

    /** Create a global function builder. */
    public FunctionBuilder(String name) {
      this(name, false);
    }

    /** Create an instance function builder. */
    public FunctionBuilder(String name, boolean isInstance) {
      this.name = Preconditions.checkNotNull(name);
      this.isInstance = isInstance;
    }

    /**
     * Add the overloads of another function to this function, after replacing the overload id as
     * specified.
     */
    @CanIgnoreReturnValue
    public FunctionBuilder sameAs(Decl func, String idPart, String idPartReplace) {
      Preconditions.checkNotNull(func);
      for (Overload overload : func.getFunction().getOverloadsList()) {
        this.overloads.add(
            CelProtoDeclConverter.overloadToCelOverload(overload).toBuilder()
                .setOverloadId(overload.getOverloadId().replace(idPart, idPartReplace))
                .build());
      }
      return this;
    }

    /** Add an overload. */
    @CanIgnoreReturnValue
    public FunctionBuilder add(String id, Type resultType, Type... argTypes) {
      return add(id, resultType, ImmutableList.copyOf(argTypes));
    }

    /** Add an overload. */
    @CanIgnoreReturnValue
    public FunctionBuilder add(String id, Type resultType, Iterable<Type> argTypes) {
      ImmutableList.Builder<CelType> argumentBuilder = new ImmutableList.Builder<>();
      for (Type type : argTypes) {
        argumentBuilder.add(CelProtoTypes.typeToCelType(type));
      }
      this.overloads.add(
          CelOverloadDecl.newBuilder()
              .setOverloadId(id)
              .setResultType(CelProtoTypes.typeToCelType(resultType))
              .addParameterTypes(argumentBuilder.build())
              .setIsInstanceFunction(isInstance)
              .build());
      return this;
    }

    /** Add an overload, with type params. */
    @CanIgnoreReturnValue
    public FunctionBuilder add(
        String id, List<String> typeParams, Type resultType, Type... argTypes) {
      return add(id, typeParams, resultType, ImmutableList.copyOf(argTypes));
    }

    /** Add an overload, with type params. */
    @CanIgnoreReturnValue
    public FunctionBuilder add(
        String id, List<String> typeParams, Type resultType, Iterable<Type> argTypes) {
      ImmutableList.Builder<CelType> argumentBuilder = new ImmutableList.Builder<>();
      for (Type type : argTypes) {
        argumentBuilder.add(CelProtoTypes.typeToCelType(type));
      }
      this.overloads.add(
          CelOverloadDecl.newBuilder()
              .setOverloadId(id)
              .setResultType(CelProtoTypes.typeToCelType(resultType))
              .addParameterTypes(argumentBuilder.build())
              .setIsInstanceFunction(isInstance)
              .build());
      return this;
    }

    /** Adds documentation to the last added overload. */
    @CanIgnoreReturnValue
    public FunctionBuilder doc(@Nullable String value) {
      int current = this.overloads.size() - 1;
      CelOverloadDecl.Builder builder = this.overloads.get(current).toBuilder();
      if (value == null) {
        builder.setDoc("");
      } else {
        builder.setDoc(value);
      }
      this.overloads.set(current, builder.build());
      return this;
    }

    /** Build the function {@code Decl}. */
    @CheckReturnValue
    public Decl build() {
      return CelProtoDeclConverter.celFunctionDeclToDecl(
          CelFunctionDecl.newBuilder().setName(name).addOverloads(overloads).build());
    }
  }

  /**
   * Object for managing a group of declarations within a scope.
   *
   * <p>Identifiers and functions can share the same declaration name, so a simple map will not
   * suffice for tracking declaration overloads.
   *
   * <p>Whether a given {@code DeclGroup} is mutable or immutable depends on whether the maps
   * supplied as input to the group are standard {@code Map} implementations or {@code ImmutableMap}
   * implementations. The {DeclGroup#immutableCopy} method is provided as a convenience to make it
   * easy to create an instance of the group which will honor the developer's intent.
   */
  public static class DeclGroup {

    private final Map<String, CelVarDecl> idents;
    private final Map<String, CelFunctionDecl> functions;

    /** Construct an empty {@code DeclGroup}. */
    public DeclGroup() {
      this(new HashMap<>(), new HashMap<>());
    }

    /** Construct a new {@code DeclGroup} from the input {@code idents} and {@code functions}. */
    public DeclGroup(Map<String, CelVarDecl> idents, Map<String, CelFunctionDecl> functions) {
      this.functions = functions;
      this.idents = idents;
    }

    /**
     * Get an immutable map of the identifiers in the {@code DeclGroup} keyed by declaration name.
     */
    public Map<String, CelVarDecl> getIdents() {
      return ImmutableMap.copyOf(idents);
    }

    /** Get an immutable map of the functions in the {@code DeclGroup} keyed by declaration name. */
    public Map<String, CelFunctionDecl> getFunctions() {
      return ImmutableMap.copyOf(functions);
    }

    /** Get an identifier declaration by {@code name}. Returns {@code null} if absent. */
    public @Nullable CelVarDecl getIdent(String name) {
      return idents.get(name);
    }

    /** Put an identifier declaration into the {@code DeclGroup}. */
    public void putIdent(CelVarDecl ident) {
      idents.put(ident.name(), ident);
    }

    /** Get a function declaration by {@code name}. Returns {@code null} if absent. */
    public @Nullable CelFunctionDecl getFunction(String name) {
      return functions.get(name);
    }

    /** Put a function declaration into the {@code DeclGroup}. */
    public void putFunction(CelFunctionDecl function) {
      functions.put(function.name(), function);
    }

    /** Create a copy of the {@code DeclGroup} with immutable identifier and function maps. */
    public DeclGroup immutableCopy() {
      return new DeclGroup(getIdents(), getFunctions());
    }
  }

  /**
   * Sanitize the identifier declaration type making sure that proto-based message names are mapped
   * to the appropriate CEL type.
   */
  private static CelVarDecl sanitizeIdent(CelVarDecl decl) {
    CelType type = decl.type();
    if (!isWellKnownType(type)) {
      return decl;
    }

    return decl.toBuilder().setType(getWellKnownType(decl.type())).build();
  }

  /**
   * Sanitize the function declaration type making sure that proto-based message names appearing in
   * the result or parameter types are mapped to the appropriate CEL types.
   */
  private static CelFunctionDecl sanitizeFunction(CelFunctionDecl func) {
    boolean needsSanitizing = false;
    for (CelOverloadDecl o : func.overloads()) {
      if (isWellKnownType(o.resultType())) {
        needsSanitizing = true;
        break;
      }
      for (CelType p : o.parameterTypes()) {
        if (isWellKnownType(p)) {
          needsSanitizing = true;
          break;
        }
      }
    }
    if (!needsSanitizing) {
      return func;
    }

    CelFunctionDecl.Builder funcBuilder = func.toBuilder();
    ImmutableSet.Builder<CelOverloadDecl> overloadsBuilder = new ImmutableSet.Builder<>();
    for (CelOverloadDecl overloadDecl : funcBuilder.overloads()) {
      CelOverloadDecl.Builder overloadBuilder = overloadDecl.toBuilder();
      CelType resultType = overloadBuilder.build().resultType();
      if (isWellKnownType(resultType)) {
        overloadBuilder.setResultType(getWellKnownType(resultType));
      }

      ImmutableList.Builder<CelType> parameterTypeBuilder = ImmutableList.builder();
      for (CelType paramType : overloadBuilder.parameterTypes()) {
        if (isWellKnownType(paramType)) {
          parameterTypeBuilder.add(getWellKnownType(paramType));
        } else {
          parameterTypeBuilder.add(paramType);
        }
      }
      overloadBuilder.setParameterTypes(parameterTypeBuilder.build());
      overloadsBuilder.add(overloadBuilder.build());
    }
    return funcBuilder.setOverloads(overloadsBuilder.build()).build();
  }

  static boolean isWellKnownType(CelType type) {
    return type.kind() == CelKind.STRUCT && CelTypes.isWellKnownType(type.name());
  }

  static CelType getWellKnownType(CelType type) {
    Preconditions.checkArgument(type.kind() == CelKind.STRUCT);
    return CelTypes.getWellKnownCelType(type.name()).get();
  }

  // TypeProvider is deprecated, but preserved for backwards compatibility.
  @SuppressWarnings("deprecation")
  private Env(
      Errors errors,
      CelTypeProvider celTypeProvider,
      @Nullable TypeProvider legacyTypeProvider,
      DeclGroup declGroup,
      CelOptions celOptions) {
    this.celOptions = Preconditions.checkNotNull(celOptions);
    this.errors = Preconditions.checkNotNull(errors);
    this.celTypeProvider = Preconditions.checkNotNull(celTypeProvider);
    this.legacyTypeProvider = legacyTypeProvider;
    this.decls.add(Preconditions.checkNotNull(declGroup));
  }
}
