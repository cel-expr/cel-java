// Copyright 2025 Google LLC
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

package dev.cel.bundle;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableSet.toImmutableSet;

import com.google.auto.value.AutoValue;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.errorprone.annotations.CheckReturnValue;
import dev.cel.bundle.CelEnvironment.LibrarySubset.FunctionSelector;
import dev.cel.checker.CelStandardDeclarations;
import dev.cel.checker.CelStandardDeclarations.StandardFunction;
import dev.cel.checker.CelStandardDeclarations.StandardOverload;
import dev.cel.checker.ProtoTypeMask;
import dev.cel.common.CelContainer;
import dev.cel.common.CelFunctionDecl;
import dev.cel.common.CelOptions;
import dev.cel.common.CelOverloadDecl;
import dev.cel.common.CelVarDecl;
import dev.cel.common.Source;
import dev.cel.common.types.CelType;
import dev.cel.common.types.CelTypeProvider;
import dev.cel.common.types.ListType;
import dev.cel.common.types.MapType;
import dev.cel.common.types.OptionalType;
import dev.cel.common.types.SimpleType;
import dev.cel.common.types.TypeParamType;
import dev.cel.common.types.TypeType;
import dev.cel.compiler.CelCompiler;
import dev.cel.compiler.CelCompilerBuilder;
import dev.cel.compiler.CelCompilerLibrary;
import dev.cel.extensions.CelExtensions;
import dev.cel.parser.CelStandardMacro;
import dev.cel.runtime.CelRuntime;
import dev.cel.runtime.CelRuntimeBuilder;
import dev.cel.runtime.CelRuntimeLibrary;
import java.util.Arrays;
import java.util.Optional;
import java.util.function.ObjIntConsumer;

/**
 * CelEnvironment is a native representation of a CEL environment for compiler and runtime. This
 * object is amenable to being serialized into YAML, textproto or other formats as needed.
 */
@AutoValue
public abstract class CelEnvironment {

  @VisibleForTesting
  static final ImmutableMap<String, CanonicalCelExtension> CEL_EXTENSION_CONFIG_MAP =
      ImmutableMap.of(
          "bindings", CanonicalCelExtension.BINDINGS,
          "encoders", CanonicalCelExtension.ENCODERS,
          "lists", CanonicalCelExtension.LISTS,
          "math", CanonicalCelExtension.MATH,
          "optional", CanonicalCelExtension.OPTIONAL,
          "protos", CanonicalCelExtension.PROTOS,
          "regex", CanonicalCelExtension.REGEX,
          "sets", CanonicalCelExtension.SETS,
          "strings", CanonicalCelExtension.STRINGS,
          "two-var-comprehensions", CanonicalCelExtension.COMPREHENSIONS);

  private static final ImmutableMap<String, ObjIntConsumer<CelOptions.Builder>> LIMIT_HANDLERS =
      ImmutableMap.of(
          "cel.limit.expression_code_points",
          CelOptions.Builder::maxExpressionCodePointSize,
          "cel.limit.parse_error_recovery",
          CelOptions.Builder::maxParseErrorRecoveryLimit,
          "cel.limit.parse_recursion_depth",
          CelOptions.Builder::maxParseRecursionDepth,
          "cel.limit.expression_node_count",
          CelOptions.Builder::maxParseExpressionNodeCount);

  private static final ImmutableMap<String, BooleanOptionConsumer> FEATURE_HANDLERS =
      ImmutableMap.of(
          "cel.feature.macro_call_tracking",
          CelOptions.Builder::populateMacroCalls,
          "cel.feature.backtick_escape_syntax",
          CelOptions.Builder::enableQuotedIdentifierSyntax,
          "cel.feature.cross_type_numeric_comparisons",
          CelOptions.Builder::enableHeterogeneousNumericComparisons);

  /** Environment source in textual format (ex: textproto, YAML). */
  public abstract Optional<Source> source();

  /** Name of the environment. */
  public abstract String name();

  /** Container, which captures default namespace and aliases for value resolution. */
  public abstract Optional<CelContainer> container();

  /**
   * An optional description of the environment (example: location of the file containing the config
   * content).
   */
  public abstract String description();

  /** Converts this {@code CelEnvironment} object into a builder. */
  public abstract Builder toBuilder();

  /**
   * Canonical extensions to enable in the environment, such as Optional, String and Math
   * extensions.
   */
  public abstract ImmutableSet<ExtensionConfig> extensions();

  /** New variable declarations to add in the compilation environment. */
  public abstract ImmutableSet<VariableDecl> variables();

  /** New function declarations to add in the compilation environment. */
  public abstract ImmutableSet<FunctionDecl> functions();

  /** Standard library subset (which macros, functions to include/exclude) */
  public abstract Optional<LibrarySubset> standardLibrarySubset();

  /** Feature flags to enable in the environment. */
  public abstract ImmutableSet<FeatureFlag> features();

  /** Limits to set in the environment. */
  public abstract ImmutableSet<Limit> limits();

  /** Context variable to enable in the environment. */
  public abstract Optional<ContextVariable> contextVariable();

  /** Builder for {@link CelEnvironment}. */
  @AutoValue.Builder
  public abstract static class Builder {

    public abstract ImmutableSet.Builder<ExtensionConfig> extensionsBuilder();

    // For testing only, to empty out the source.
    abstract Builder setSource(Optional<Source> source);

    public abstract Builder setSource(Source source);

    public abstract Builder setName(String name);

    public abstract Builder setDescription(String description);

    public abstract Builder setContainer(CelContainer container);

    @CanIgnoreReturnValue
    public Builder setContainer(String container) {
      return setContainer(CelContainer.ofName(container));
    }

    @CanIgnoreReturnValue
    public Builder addExtensions(ExtensionConfig... extensions) {
      checkNotNull(extensions);
      return addExtensions(Arrays.asList(extensions));
    }

    @CanIgnoreReturnValue
    public Builder addExtensions(Iterable<ExtensionConfig> extensions) {
      checkNotNull(extensions);
      this.extensionsBuilder().addAll(extensions);
      return this;
    }

    @CanIgnoreReturnValue
    public Builder setVariables(VariableDecl... variables) {
      return setVariables(ImmutableSet.copyOf(variables));
    }

    public abstract Builder setVariables(ImmutableSet<VariableDecl> variables);

    @CanIgnoreReturnValue
    public Builder setFunctions(FunctionDecl... functions) {
      return setFunctions(ImmutableSet.copyOf(functions));
    }

    public abstract Builder setFunctions(ImmutableSet<FunctionDecl> functions);

    public abstract Builder setStandardLibrarySubset(LibrarySubset stdLibrarySubset);

    @CanIgnoreReturnValue
    public Builder setFeatures(FeatureFlag... featureFlags) {
      return setFeatures(ImmutableSet.copyOf(featureFlags));
    }

    public abstract Builder setFeatures(ImmutableSet<FeatureFlag> featureFlags);

    @CanIgnoreReturnValue
    public Builder setLimits(Limit... limits) {
      return setLimits(ImmutableSet.copyOf(limits));
    }

    public abstract Builder setLimits(ImmutableSet<Limit> limits);

    public abstract Builder setContextVariable(ContextVariable contextVariable);

    abstract CelEnvironment autoBuild();

    @CheckReturnValue
    public final CelEnvironment build() {
      CelEnvironment env = autoBuild();
      LibrarySubset librarySubset = env.standardLibrarySubset().orElse(null);
      if (librarySubset != null) {
        if (!librarySubset.includedMacros().isEmpty()
            && !librarySubset.excludedMacros().isEmpty()) {
          throw new IllegalArgumentException(
              "Invalid subset: cannot both include and exclude macros");
        }
        if (!librarySubset.includedFunctions().isEmpty()
            && !librarySubset.excludedFunctions().isEmpty()) {
          throw new IllegalArgumentException(
              "Invalid subset: cannot both include and exclude functions");
        }
      }
      return env;
    }
  }

  /** Creates a new builder to construct a {@link CelEnvironment} instance. */
  public static Builder newBuilder() {
    return new AutoValue_CelEnvironment.Builder()
        .setName("")
        .setDescription("")
        .setVariables(ImmutableSet.of())
        .setFunctions(ImmutableSet.of())
        .setFeatures(ImmutableSet.of())
        .setLimits(ImmutableSet.of());
  }

  /** Extends the provided {@link CelCompiler} environment with this configuration. */
  public CelCompiler extend(CelCompiler celCompiler, CelOptions celOptions)
      throws CelEnvironmentException {
    celOptions = applyEnvironmentOptions(celOptions);
    try {
      CelTypeProvider celTypeProvider = celCompiler.getTypeProvider();
      CelCompilerBuilder compilerBuilder =
          celCompiler
              .toCompilerBuilder()
              .setOptions(celOptions)
              .setTypeProvider(celTypeProvider)
              .addVarDeclarations(
                  variables().stream()
                      .map(v -> v.toCelVarDecl(celTypeProvider))
                      .collect(toImmutableList()))
              .addFunctionDeclarations(
                  functions().stream()
                      .map(f -> f.toCelFunctionDecl(celTypeProvider))
                      .collect(toImmutableList()));

      container().ifPresent(compilerBuilder::setContainer);

      addAllCompilerExtensions(compilerBuilder, celOptions);

      applyStandardLibrarySubset(compilerBuilder);

      contextVariable()
          .ifPresent(
              cv ->
                  compilerBuilder.addProtoTypeMasks(
                      ProtoTypeMask.ofAllFields(cv.typeName()).withFieldsAsVariableDeclarations()));

      return compilerBuilder.build();
    } catch (RuntimeException e) {
      throw new CelEnvironmentException(e.getMessage(), e);
    }
  }

  /** Extends the provided {@link Cel} environment with this configuration. */
  public Cel extend(Cel cel, CelOptions celOptions) throws CelEnvironmentException {
    celOptions = applyEnvironmentOptions(celOptions);
    try {
      // Casting is necessary to only extend the compiler here
      CelCompiler celCompiler = extend((CelCompiler) cel, celOptions);

      CelRuntime celRuntime = extendRuntime(cel, celOptions);

      return CelFactory.combine(celCompiler, celRuntime);
    } catch (RuntimeException e) {
      throw new CelEnvironmentException(e.getMessage(), e);
    }
  }

  private CelOptions applyEnvironmentOptions(CelOptions celOptions) {
    CelOptions.Builder optionsBuilder = celOptions.toBuilder();
    for (FeatureFlag featureFlag : features()) {
      BooleanOptionConsumer consumer = FEATURE_HANDLERS.get(featureFlag.name());
      if (consumer == null) {
        throw new IllegalArgumentException("Unknown feature flag: " + featureFlag.name());
      }
      consumer.accept(optionsBuilder, featureFlag.enabled());
    }
    for (Limit limit : limits()) {
      int value = limit.value() < 0 ? -1 : limit.value();
      ObjIntConsumer<CelOptions.Builder> consumer = LIMIT_HANDLERS.get(limit.name());
      if (consumer == null) {
        throw new IllegalArgumentException("Unknown limit: " + limit.name());
      }
      consumer.accept(optionsBuilder, value);
    }
    return optionsBuilder.build();
  }

  private void addAllCompilerExtensions(
      CelCompilerBuilder celCompilerBuilder, CelOptions celOptions) {
    // TODO: Add capability to accept user defined exceptions
    for (ExtensionConfig extensionConfig : extensions()) {
      CanonicalCelExtension extension = getExtensionOrThrow(extensionConfig.name());
      if (extension.compilerExtensionProvider() != null) {
        CelCompilerLibrary celCompilerLibrary =
            extension
                .compilerExtensionProvider()
                .getCelCompilerLibrary(celOptions, extensionConfig.version());
        celCompilerBuilder.addLibraries(celCompilerLibrary);
      }
    }
  }

  private CelRuntime extendRuntime(CelRuntime celRuntime, CelOptions celOptions) {
    CelRuntimeBuilder celRuntimeBuilder = celRuntime.toRuntimeBuilder();
    celRuntimeBuilder.setOptions(celOptions);
    // TODO: Add capability to accept user defined exceptions
    for (ExtensionConfig extensionConfig : extensions()) {
      CanonicalCelExtension extension = getExtensionOrThrow(extensionConfig.name());
      if (extension.runtimeExtensionProvider() != null) {
        CelRuntimeLibrary celRuntimeLibrary =
            extension
                .runtimeExtensionProvider()
                .getCelRuntimeLibrary(celOptions, extensionConfig.version());
        celRuntimeBuilder.addLibraries(celRuntimeLibrary);
      }
    }
    return celRuntimeBuilder.build();
  }

  private void applyStandardLibrarySubset(CelCompilerBuilder compilerBuilder) {
    if (!standardLibrarySubset().isPresent()) {
      return;
    }

    LibrarySubset librarySubset = standardLibrarySubset().get();
    if (librarySubset.disabled()) {
      compilerBuilder.setStandardEnvironmentEnabled(false);
      return;
    }

    if (librarySubset.macrosDisabled()) {
      compilerBuilder.setStandardMacros(ImmutableList.of());
    } else if (!librarySubset.includedMacros().isEmpty()) {
      compilerBuilder.setStandardMacros(
          librarySubset.includedMacros().stream()
              .flatMap(name -> getStandardMacrosOrThrow(name).stream())
              .collect(toImmutableSet()));
    } else if (!librarySubset.excludedMacros().isEmpty()) {
      ImmutableSet<CelStandardMacro> set =
          librarySubset.excludedMacros().stream()
              .flatMap(name -> getStandardMacrosOrThrow(name).stream())
              .collect(toImmutableSet());
      compilerBuilder.setStandardMacros(
          CelStandardMacro.STANDARD_MACROS.stream()
              .filter(macro -> !set.contains(macro))
              .collect(toImmutableSet()));
    }

    if (!librarySubset.includedFunctions().isEmpty()) {
      ImmutableSet<FunctionSelector> includedFunctions = librarySubset.includedFunctions();
      compilerBuilder
          .setStandardEnvironmentEnabled(false)
          .setStandardDeclarations(
              CelStandardDeclarations.newBuilder()
                  .filterFunctions(
                      (function, overload) ->
                          FunctionSelector.matchesAny(function, overload, includedFunctions))
                  .build());
    } else if (!librarySubset.excludedFunctions().isEmpty()) {
      ImmutableSet<FunctionSelector> excludedFunctions = librarySubset.excludedFunctions();
      compilerBuilder
          .setStandardEnvironmentEnabled(false)
          .setStandardDeclarations(
              CelStandardDeclarations.newBuilder()
                  .filterFunctions(
                      (function, overload) ->
                          !FunctionSelector.matchesAny(function, overload, excludedFunctions))
                  .build());
    }
  }

  private static ImmutableSet<CelStandardMacro> getStandardMacrosOrThrow(String macroName) {
    ImmutableSet.Builder<CelStandardMacro> builder = ImmutableSet.builder();
    for (CelStandardMacro macro : CelStandardMacro.STANDARD_MACROS) {
      if (macro.getFunction().equals(macroName)) {
        builder.add(macro);
      }
    }
    ImmutableSet<CelStandardMacro> macros = builder.build();
    if (macros.isEmpty()) {
      throw new IllegalArgumentException("unrecognized standard macro `" + macroName + "'");
    }
    return macros;
  }

  private static CanonicalCelExtension getExtensionOrThrow(String extensionName) {
    CanonicalCelExtension extension = CEL_EXTENSION_CONFIG_MAP.get(extensionName);
    if (extension == null) {
      throw new IllegalArgumentException("Unrecognized extension: " + extensionName);
    }

    return extension;
  }

  /** Represents a context variable declaration. */
  @AutoValue
  public abstract static class ContextVariable {
    /** Fully qualified type name of the context variable. */
    public abstract String typeName();

    public static ContextVariable create(String typeName) {
      return new AutoValue_CelEnvironment_ContextVariable(typeName);
    }
  }

  /** Represents a policy variable declaration. */
  @AutoValue
  public abstract static class VariableDecl {

    /** Fully qualified variable name. */
    public abstract String name();

    /** The type of the variable. */
    public abstract TypeDecl type();

    public abstract Optional<String> description();

    /** Builder for {@link VariableDecl}. */
    @AutoValue.Builder
    public abstract static class Builder implements RequiredFieldsChecker {

      public abstract Optional<String> name();

      public abstract Optional<TypeDecl> type();

      public abstract VariableDecl.Builder setName(String name);

      public abstract VariableDecl.Builder setType(TypeDecl typeDecl);

      public abstract VariableDecl.Builder setDescription(String name);

      @Override
      public ImmutableList<RequiredField> requiredFields() {
        return ImmutableList.of(
            RequiredField.of("name", this::name), RequiredField.of("type", this::type));
      }

      /** Builds a new instance of {@link VariableDecl}. */
      public abstract VariableDecl build();
    }

    public static VariableDecl.Builder newBuilder() {
      return new AutoValue_CelEnvironment_VariableDecl.Builder();
    }

    /** Creates a new builder to construct a {@link VariableDecl} instance. */
    public static VariableDecl create(String name, TypeDecl type) {
      return newBuilder().setName(name).setType(type).build();
    }

    /** Converts this policy variable declaration into a {@link CelVarDecl}. */
    public CelVarDecl toCelVarDecl(CelTypeProvider celTypeProvider) {
      return CelVarDecl.newVarDeclaration(name(), type().toCelType(celTypeProvider));
    }
  }

  /** Represents a policy function declaration. */
  @AutoValue
  public abstract static class FunctionDecl {

    public abstract String name();

    public abstract Optional<String> description();

    public abstract ImmutableSet<OverloadDecl> overloads();

    /** Builder for {@link FunctionDecl}. */
    @AutoValue.Builder
    public abstract static class Builder implements RequiredFieldsChecker {

      public abstract Optional<String> name();

      public abstract Optional<ImmutableSet<OverloadDecl>> overloads();

      public abstract FunctionDecl.Builder setName(String name);

      public abstract FunctionDecl.Builder setDescription(String description);

      public abstract FunctionDecl.Builder setOverloads(ImmutableSet<OverloadDecl> overloads);

      @Override
      public ImmutableList<RequiredField> requiredFields() {
        return ImmutableList.of(
            RequiredField.of("name", this::name), RequiredField.of("overloads", this::overloads));
      }

      /** Builds a new instance of {@link FunctionDecl}. */
      public abstract FunctionDecl build();
    }

    /** Creates a new builder to construct a {@link FunctionDecl} instance. */
    public static FunctionDecl.Builder newBuilder() {
      return new AutoValue_CelEnvironment_FunctionDecl.Builder();
    }

    /** Creates a new {@link FunctionDecl} with the provided function name and its overloads. */
    public static FunctionDecl create(String name, ImmutableSet<OverloadDecl> overloads) {
      return newBuilder().setName(name).setOverloads(overloads).build();
    }

    /** Converts this policy function declaration into a {@link CelFunctionDecl}. */
    public CelFunctionDecl toCelFunctionDecl(CelTypeProvider celTypeProvider) {
      return CelFunctionDecl.newFunctionDeclaration(
          name(),
          overloads().stream()
              .map(o -> o.toCelOverloadDecl(celTypeProvider))
              .collect(toImmutableList()));
    }
  }

  /** Represents an overload declaration on a policy function. */
  @AutoValue
  public abstract static class OverloadDecl {

    /**
     * A unique overload ID. Required. This should follow the typical naming convention used in CEL
     * (e.g: targetType_func_argType1_argType...)
     */
    public abstract String id();

    /** Target of the function overload if it's a receiver style (example: foo in `foo.f(...)`) */
    public abstract Optional<TypeDecl> target();

    /** List of function overload type values. */
    public abstract ImmutableList<TypeDecl> arguments();

    /** Examples for the overload. */
    public abstract ImmutableList<String> examples();

    /** Return type of the overload. Required. */
    public abstract TypeDecl returnType();

    /** Builder for {@link OverloadDecl}. */
    @AutoValue.Builder
    public abstract static class Builder implements RequiredFieldsChecker {

      public abstract Optional<String> id();

      public abstract Optional<TypeDecl> returnType();

      public abstract OverloadDecl.Builder setId(String overloadId);

      public abstract OverloadDecl.Builder setTarget(TypeDecl target);

      // This should stay package-private to encourage add/set methods to be used instead.
      abstract ImmutableList.Builder<TypeDecl> argumentsBuilder();

      abstract ImmutableList.Builder<String> examplesBuilder();

      public abstract OverloadDecl.Builder setArguments(ImmutableList<TypeDecl> args);

      @CanIgnoreReturnValue
      public OverloadDecl.Builder addExamples(Iterable<String> examples) {
        this.examplesBuilder().addAll(checkNotNull(examples));
        return this;
      }

      @CanIgnoreReturnValue
      public OverloadDecl.Builder addExamples(String... examples) {
        return addExamples(Arrays.asList(examples));
      }

      @CanIgnoreReturnValue
      public OverloadDecl.Builder addArguments(Iterable<TypeDecl> args) {
        this.argumentsBuilder().addAll(checkNotNull(args));
        return this;
      }

      @CanIgnoreReturnValue
      public OverloadDecl.Builder addArguments(TypeDecl... args) {
        return addArguments(Arrays.asList(args));
      }

      public abstract OverloadDecl.Builder setReturnType(TypeDecl returnType);

      @Override
      public ImmutableList<RequiredField> requiredFields() {
        return ImmutableList.of(
            RequiredField.of("id", this::id), RequiredField.of("return", this::returnType));
      }

      /** Builds a new instance of {@link OverloadDecl}. */
      @CheckReturnValue
      public abstract OverloadDecl build();
    }

    /** Creates a new builder to construct a {@link OverloadDecl} instance. */
    public static OverloadDecl.Builder newBuilder() {
      return new AutoValue_CelEnvironment_OverloadDecl.Builder().setArguments(ImmutableList.of());
    }

    /** Converts this policy function overload into a {@link CelOverloadDecl}. */
    public CelOverloadDecl toCelOverloadDecl(CelTypeProvider celTypeProvider) {
      CelOverloadDecl.Builder builder =
          CelOverloadDecl.newBuilder()
              .setIsInstanceFunction(false)
              .setOverloadId(id())
              .setResultType(returnType().toCelType(celTypeProvider));

      target()
          .ifPresent(
              t ->
                  builder
                      .setIsInstanceFunction(true)
                      .addParameterTypes(t.toCelType(celTypeProvider)));

      for (TypeDecl type : arguments()) {
        builder.addParameterTypes(type.toCelType(celTypeProvider));
      }

      return builder.build();
    }
  }

  /**
   * Represents an abstract type declaration used to declare functions and variables in a policy.
   */
  @AutoValue
  public abstract static class TypeDecl {

    public abstract String name();

    public abstract ImmutableList<TypeDecl> params();

    public abstract boolean isTypeParam();

    /** Builder for {@link TypeDecl}. */
    @AutoValue.Builder
    public abstract static class Builder implements RequiredFieldsChecker {

      public abstract Optional<String> name();

      public abstract TypeDecl.Builder setName(String name);

      // This should stay package-private to encourage add/set methods to be used instead.
      abstract ImmutableList.Builder<TypeDecl> paramsBuilder();

      public abstract TypeDecl.Builder setParams(ImmutableList<TypeDecl> typeDecls);

      @CanIgnoreReturnValue
      public TypeDecl.Builder addParams(TypeDecl... params) {
        return addParams(Arrays.asList(params));
      }

      @CanIgnoreReturnValue
      public TypeDecl.Builder addParams(Iterable<TypeDecl> params) {
        this.paramsBuilder().addAll(checkNotNull(params));
        return this;
      }

      public abstract TypeDecl.Builder setIsTypeParam(boolean isTypeParam);

      @Override
      public ImmutableList<RequiredField> requiredFields() {
        return ImmutableList.of(RequiredField.of("type_name", this::name));
      }

      @CheckReturnValue
      public abstract TypeDecl build();
    }

    /** Creates a new {@link TypeDecl} with the provided name. */
    public static TypeDecl create(String name) {
      return newBuilder().setName(name).build();
    }

    public static TypeDecl.Builder newBuilder() {
      return new AutoValue_CelEnvironment_TypeDecl.Builder().setIsTypeParam(false);
    }

    /** Converts this type declaration into a {@link CelType}. */
    public CelType toCelType(CelTypeProvider celTypeProvider) {
      switch (name()) {
        case "list":
          if (params().size() != 1) {
            throw new IllegalArgumentException(
                "List type has unexpected param count: " + params().size());
          }

          CelType elementType = params().get(0).toCelType(celTypeProvider);
          return ListType.create(elementType);
        case "map":
          if (params().size() != 2) {
            throw new IllegalArgumentException(
                "Map type has unexpected param count: " + params().size());
          }

          CelType keyType = params().get(0).toCelType(celTypeProvider);
          CelType valueType = params().get(1).toCelType(celTypeProvider);
          return MapType.create(keyType, valueType);
        case "type":
          checkState(
              params().size() == 1, "Expected 1 parameter for type, got %s", params().size());
          return TypeType.create(params().get(0).toCelType(celTypeProvider));
        default:
          if (isTypeParam()) {
            return TypeParamType.create(name());
          }

          if (name().equals("dyn")) {
            return SimpleType.DYN;
          }

          CelType simpleType = SimpleType.findByName(name()).orElse(null);
          if (simpleType != null) {
            return simpleType;
          }

          if (name().equals(OptionalType.NAME)) {
            checkState(
                params().size() == 1,
                "Optional type must have exactly 1 parameter. Found %s",
                params().size());
            return OptionalType.create(params().get(0).toCelType(celTypeProvider));
          }

          return celTypeProvider
              .findType(name())
              .orElseThrow(() -> new IllegalArgumentException("Undefined type name: " + name()));
      }
    }
  }

  /** Represents a feature flag that can be enabled in the environment. */
  @AutoValue
  public abstract static class FeatureFlag {
    /** Normalized name of the feature flag. */
    public abstract String name();

    /** Whether the feature is enabled or disabled. */
    public abstract boolean enabled();

    public static FeatureFlag create(String name, boolean enabled) {
      return new AutoValue_CelEnvironment_FeatureFlag(name, enabled);
    }
  }

  /**
   * Represents a configurable limit in the environment.
   *
   * <p>A negative value indicates no limit. If not specified, the limit should be set to the
   * library default.
   */
  @AutoValue
  public abstract static class Limit {
    /** Normalized name of the limit (e.g. cel.limit.expression_code_points */
    public abstract String name();

    /** The value of the limit, -1 means no limit. */
    public abstract int value();

    public static Limit create(String name, int value) {
      return new AutoValue_CelEnvironment_Limit(name, value);
    }
  }

  /**
   * Represents a configuration for a canonical CEL extension that can be enabled in the
   * environment.
   */
  @AutoValue
  public abstract static class ExtensionConfig {

    /** Name of the extension (ex: bindings, optional, math, etc).". */
    public abstract String name();

    /**
     * Version of the extension. Presently, this field is ignored as CEL-Java extensions are not
     * versioned.
     */
    public abstract int version();

    /** Builder for {@link ExtensionConfig}. */
    @AutoValue.Builder
    public abstract static class Builder implements RequiredFieldsChecker {

      public abstract Optional<String> name();

      public abstract Optional<Integer> version();

      public abstract ExtensionConfig.Builder setName(String name);

      public abstract ExtensionConfig.Builder setVersion(int version);

      @Override
      public ImmutableList<RequiredField> requiredFields() {
        return ImmutableList.of(RequiredField.of("name", this::name));
      }

      /** Builds a new instance of {@link ExtensionConfig}. */
      public abstract ExtensionConfig build();
    }

    /** Creates a new builder to construct a {@link ExtensionConfig} instance. */
    public static ExtensionConfig.Builder newBuilder() {
      return new AutoValue_CelEnvironment_ExtensionConfig.Builder().setVersion(0);
    }

    /** Create a new extension config with the specified name and version set to 0. */
    public static ExtensionConfig of(String name) {
      return of(name, 0);
    }

    /** Create a new extension config with the specified name and version. */
    public static ExtensionConfig of(String name, int version) {
      return newBuilder().setName(name).setVersion(version).build();
    }

    /** Create a new extension config with the specified name and the latest version. */
    public static ExtensionConfig latest(String name) {
      return of(name, Integer.MAX_VALUE);
    }
  }

  @AutoValue
  abstract static class Alias {
    abstract String alias();

    abstract String qualifiedName();

    static Builder newBuilder() {
      return new AutoValue_CelEnvironment_Alias.Builder();
    }

    @AutoValue.Builder
    abstract static class Builder implements RequiredFieldsChecker {

      abstract Optional<String> alias();

      abstract Optional<String> qualifiedName();

      abstract Builder setAlias(String alias);

      abstract Builder setQualifiedName(String qualifiedName);

      abstract Alias build();

      @Override
      public ImmutableList<RequiredField> requiredFields() {
        return ImmutableList.of(
            RequiredField.of("alias", this::alias),
            RequiredField.of("qualified_name", this::qualifiedName));
      }
    }
  }

  @VisibleForTesting
  enum CanonicalCelExtension {
    BINDINGS((options, version) -> CelExtensions.bindings()),
    PROTOS((options, version) -> CelExtensions.protos()),
    ENCODERS(
        (options, version) -> CelExtensions.encoders(options),
        (options, version) -> CelExtensions.encoders(options)),
    MATH(
        (options, version) -> CelExtensions.math(options, version),
        (options, version) -> CelExtensions.math(options, version)),
    OPTIONAL(
        (options, version) -> CelExtensions.optional(version),
        (options, version) -> CelExtensions.optional(version)),
    STRINGS(
        (options, version) -> CelExtensions.strings(),
        (options, version) -> CelExtensions.strings()),
    SETS(
        (options, version) -> CelExtensions.sets(options),
        (options, version) -> CelExtensions.sets(options)),
    REGEX((options, version) -> CelExtensions.regex(), (options, version) -> CelExtensions.regex()),
    LISTS((options, version) -> CelExtensions.lists(), (options, version) -> CelExtensions.lists()),
    COMPREHENSIONS(
        (options, version) -> CelExtensions.comprehensions(),
        (options, version) -> CelExtensions.comprehensions());

    @SuppressWarnings("ImmutableEnumChecker")
    private final CompilerExtensionProvider compilerExtensionProvider;

    @SuppressWarnings("ImmutableEnumChecker")
    private final RuntimeExtensionProvider runtimeExtensionProvider;

    interface CompilerExtensionProvider {
      CelCompilerLibrary getCelCompilerLibrary(CelOptions options, int version);
    }

    interface RuntimeExtensionProvider {
      CelRuntimeLibrary getCelRuntimeLibrary(CelOptions options, int version);
    }

    CompilerExtensionProvider compilerExtensionProvider() {
      return compilerExtensionProvider;
    }

    RuntimeExtensionProvider runtimeExtensionProvider() {
      return runtimeExtensionProvider;
    }

    CanonicalCelExtension(CompilerExtensionProvider compilerExtensionProvider) {
      this.compilerExtensionProvider = compilerExtensionProvider;
      this.runtimeExtensionProvider = null; // Not all extensions augment the runtime.
    }

    CanonicalCelExtension(
        CompilerExtensionProvider compilerExtensionProvider,
        RuntimeExtensionProvider runtimeExtensionProvider) {
      this.compilerExtensionProvider = compilerExtensionProvider;
      this.runtimeExtensionProvider = runtimeExtensionProvider;
    }
  }

  /**
   * LibrarySubset indicates a subset of the macros and function supported by a subsettable library.
   */
  @AutoValue
  public abstract static class LibrarySubset {

    /**
     * Disabled indicates whether the library has been disabled, typically only used for
     * default-enabled libraries like stdlib.
     */
    public abstract boolean disabled();

    /** DisableMacros disables macros for the given library. */
    public abstract boolean macrosDisabled();

    /** IncludeMacros specifies a set of macro function names to include in the subset. */
    public abstract ImmutableSet<String> includedMacros();

    /**
     * ExcludeMacros specifies a set of macro function names to exclude from the subset.
     *
     * <p>Note: if IncludedMacros is non-empty, then ExcludedMacros is ignored.
     */
    public abstract ImmutableSet<String> excludedMacros();

    /**
     * IncludeFunctions specifies a set of functions to include in the subset.
     *
     * <p>Note: the overloads specified in the subset need only specify their ID.
     *
     * <p>Note: if IncludedFunctions is non-empty, then ExcludedFunctions is ignored.
     */
    public abstract ImmutableSet<FunctionSelector> includedFunctions();

    /**
     * ExcludeFunctions specifies the set of functions to exclude from the subset.
     *
     * <p>Note: the overloads specified in the subset need only specify their ID.
     */
    public abstract ImmutableSet<FunctionSelector> excludedFunctions();

    public static Builder newBuilder() {
      return new AutoValue_CelEnvironment_LibrarySubset.Builder()
          .setMacrosDisabled(false)
          .setIncludedMacros(ImmutableSet.of())
          .setExcludedMacros(ImmutableSet.of())
          .setIncludedFunctions(ImmutableSet.of())
          .setExcludedFunctions(ImmutableSet.of());
    }

    /** Builder for {@link LibrarySubset}. */
    @AutoValue.Builder
    public abstract static class Builder {

      public abstract Builder setDisabled(boolean disabled);

      public abstract Builder setMacrosDisabled(boolean disabled);

      public abstract Builder setIncludedMacros(ImmutableSet<String> includedMacros);

      public abstract Builder setExcludedMacros(ImmutableSet<String> excludedMacros);

      public abstract Builder setIncludedFunctions(
          ImmutableSet<FunctionSelector> includedFunctions);

      public abstract Builder setExcludedFunctions(
          ImmutableSet<FunctionSelector> excludedFunctions);

      @CheckReturnValue
      public abstract LibrarySubset build();
    }

    /**
     * Represents a function selector, which can be used to configure included/excluded library
     * functions.
     */
    @AutoValue
    public abstract static class FunctionSelector {

      public abstract String name();

      public abstract ImmutableSet<OverloadSelector> overloads();

      /** Builder for {@link FunctionSelector}. */
      @AutoValue.Builder
      public abstract static class Builder implements RequiredFieldsChecker {

        public abstract Optional<String> name();

        public abstract Builder setName(String name);

        public abstract Builder setOverloads(ImmutableSet<OverloadSelector> overloads);

        @Override
        public ImmutableList<RequiredField> requiredFields() {
          return ImmutableList.of(RequiredField.of("name", this::name));
        }

        /** Builds a new instance of {@link FunctionSelector}. */
        public abstract FunctionSelector build();
      }

      /** Creates a new builder to construct a {@link FunctionSelector} instance. */
      public static FunctionSelector.Builder newBuilder() {
        return new AutoValue_CelEnvironment_LibrarySubset_FunctionSelector.Builder()
            .setOverloads(ImmutableSet.of());
      }

      public static FunctionSelector create(String name, ImmutableSet<String> overloads) {
        return newBuilder()
            .setName(name)
            .setOverloads(
                overloads.stream()
                    .map(id -> OverloadSelector.newBuilder().setId(id).build())
                    .collect(toImmutableSet()))
            .build();
      }

      private static boolean matchesAny(
          StandardFunction function,
          StandardOverload overload,
          ImmutableSet<FunctionSelector> selectors) {
        String functionName = function.functionDecl().name();
        for (FunctionSelector functionSelector : selectors) {
          if (!functionSelector.name().equals(functionName)) {
            continue;
          }

          if (functionSelector.overloads().isEmpty()) {
            return true;
          }

          String overloadId = overload.celOverloadDecl().overloadId();
          for (OverloadSelector overloadSelector : functionSelector.overloads()) {
            if (overloadSelector.id().equals(overloadId)) {
              return true;
            }
          }
        }
        return false;
      }
    }

    /** Represents an overload selector on a function selector. */
    @AutoValue
    public abstract static class OverloadSelector {

      /** An overload ID. Required. Follows the same format as {@link OverloadDecl#id()} */
      public abstract String id();

      /** Builder for {@link OverloadSelector}. */
      @AutoValue.Builder
      public abstract static class Builder implements RequiredFieldsChecker {

        public abstract Optional<String> id();

        public abstract Builder setId(String overloadId);

        @Override
        public ImmutableList<RequiredField> requiredFields() {
          return ImmutableList.of(RequiredField.of("id", this::id));
        }

        /** Builds a new instance of {@link OverloadSelector}. */
        @CheckReturnValue
        public abstract OverloadSelector build();
      }

      /** Creates a new builder to construct a {@link OverloadSelector} instance. */
      public static OverloadSelector.Builder newBuilder() {
        return new AutoValue_CelEnvironment_LibrarySubset_OverloadSelector.Builder();
      }
    }
  }

  @FunctionalInterface
  private interface BooleanOptionConsumer {
    void accept(CelOptions.Builder options, boolean value);
  }
}
