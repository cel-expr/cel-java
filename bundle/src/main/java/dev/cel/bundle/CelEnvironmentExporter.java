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

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static java.util.Arrays.stream;

import dev.cel.expr.Decl;
import dev.cel.expr.Decl.FunctionDecl;
import dev.cel.expr.Decl.FunctionDecl.Overload;
import com.google.auto.value.AutoValue;
import com.google.common.base.Preconditions;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ListMultimap;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import dev.cel.bundle.CelEnvironment.ExtensionConfig;
import dev.cel.bundle.CelEnvironment.LibrarySubset;
import dev.cel.bundle.CelEnvironment.LibrarySubset.FunctionSelector;
import dev.cel.bundle.CelEnvironment.OverloadDecl;
import dev.cel.checker.CelCheckerBuilder;
import dev.cel.checker.CelStandardDeclarations.StandardFunction;
import dev.cel.checker.CelStandardDeclarations.StandardIdentifier;
import dev.cel.common.CelFunctionDecl;
import dev.cel.common.CelOptions;
import dev.cel.common.CelOverloadDecl;
import dev.cel.common.CelVarDecl;
import dev.cel.common.internal.EnvVisitable;
import dev.cel.common.internal.EnvVisitor;
import dev.cel.common.types.CelKind;
import dev.cel.common.types.CelProtoTypes;
import dev.cel.common.types.CelType;
import dev.cel.compiler.CelCompiler;
import dev.cel.extensions.CelExtensionLibrary;
import dev.cel.extensions.CelExtensions;
import dev.cel.parser.CelMacro;
import dev.cel.parser.CelStandardMacro;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code CelEnvironmentExporter} can be used to export the configuration of a {@link Cel} instance
 * as a {@link CelEnvironment} object.
 *
 * <p>This exporter captures details such as:
 *
 * <ul>
 *   <li>Standard library subset: Functions and their overloads that are either included or
 *       excluded.
 *   <li>Extension libraries: Names and versions of the extension libraries in use.
 *   <li>Custom declarations: Functions and variables not part of standard or extension libraries.
 * </ul>
 *
 * <p>The exporter provides options to control the behavior of the export process, such as the
 * maximum number of excluded standard functions and overloads before switching to an inclusion
 * strategy.
 */
@AutoValue
public abstract class CelEnvironmentExporter {

  /**
   * Maximum number of excluded standard functions and macros before switching to a format that
   * enumerates all included functions and macros. The default is 5.
   *
   * <p>This setting is primarily for stylistic preferences and the intended use of the resulting
   * YAML file.
   *
   * <p>For example, if you want almost all the standard library with only a few exceptions (e.g.,
   * to ban a specific function), you would favor an exclusion-based approach by setting an
   * appropriate threshold.
   *
   * <p>If you want full control over what is available to the CEL runtime, where no function is
   * included unless fully vetted, you would favor an inclusion-based approach by setting the
   * threshold to 0. This may result in a more verbose YAML file.
   */
  abstract int maxExcludedStandardFunctions();

  /**
   * Maximum number of excluded standard function overloads before switching to a exhaustive
   * enumeration of included overloads. The default is 15.
   */
  abstract int maxExcludedStandardFunctionOverloads();

  abstract ImmutableSet<CelExtensionLibrary<? extends CelExtensionLibrary.FeatureSet>>
      extensionLibraries();

  /** Builder for {@link CelEnvironmentExporter}. */
  @AutoValue.Builder
  public abstract static class Builder {

    public abstract Builder setMaxExcludedStandardFunctions(int count);

    public abstract Builder setMaxExcludedStandardFunctionOverloads(int count);

    abstract ImmutableSet.Builder<CelExtensionLibrary<? extends CelExtensionLibrary.FeatureSet>>
        extensionLibrariesBuilder();

    @CanIgnoreReturnValue
    public Builder addStandardExtensions(CelOptions options) {
      addExtensionLibraries(
          CelExtensions.getExtensionLibrary("bindings", options),
          CelExtensions.getExtensionLibrary("encoders", options),
          CelExtensions.getExtensionLibrary("lists", options),
          CelExtensions.getExtensionLibrary("math", options),
          CelExtensions.getExtensionLibrary("protos", options),
          CelExtensions.getExtensionLibrary("regex", options),
          CelExtensions.getExtensionLibrary("sets", options),
          CelExtensions.getExtensionLibrary("strings", options));
      // TODO: add support for remaining standard extensions
      return this;
    }

    @CanIgnoreReturnValue
    public Builder addExtensionLibraries(
        CelExtensionLibrary<? extends CelExtensionLibrary.FeatureSet>... libraries) {
      extensionLibrariesBuilder().add(libraries);
      return this;
    }

    abstract CelEnvironmentExporter autoBuild();

    public CelEnvironmentExporter build() {
      return autoBuild();
    }
  }

  /** Creates a new builder to construct a {@link CelEnvironmentExporter} instance. */
  public static CelEnvironmentExporter.Builder newBuilder() {
    return new AutoValue_CelEnvironmentExporter.Builder()
        .setMaxExcludedStandardFunctions(5)
        .setMaxExcludedStandardFunctionOverloads(15);
  }

  /**
   * Exports a {@link CelEnvironment} that describes the configuration of the given {@link Cel}
   * instance.
   *
   * <p>The exported environment includes:
   *
   * <ul>
   *   <li>Standard library subset: functions and their overloads that are either included or
   *       excluded from the standard library.
   *   <li>Extension libraries: names and versions of the extension libraries that are used.
   *   <li>Custom declarations: functions and variables that are not part of the standard library or
   *       any of the extension libraries.
   * </ul>
   */
  public CelEnvironment export(Cel cel) {
    return export((CelCompiler) cel);
  }

  /**
   * Exports a {@link CelEnvironment} that describes the configuration of the given {@link
   * CelCompiler} instance.
   *
   * <p>The exported environment includes:
   *
   * <ul>
   *   <li>Standard library subset: functions and their overloads that are either included or
   *       excluded from the standard library.
   *   <li>Extension libraries: names and versions of the extension libraries that are used.
   *   <li>Custom declarations: functions and variables that are not part of the standard library or
   *       any of the extension libraries.
   * </ul>
   */
  public CelEnvironment export(CelCompiler cel) {
    // Inventory is a full set of declarations and macros that are found in the configuration of
    // the supplied CEL instance.
    //
    // Once we have the inventory, we attempt to identify sources of these declarations as
    // standard library and extensions. The identified subsets will be removed from the inventory.
    //
    // Whatever is left will be included in the Environment as custom declarations.

    // Checker builder is used to access some parts of the config not exposed in the EnvVisitable
    // interface.
    CelCheckerBuilder checkerBuilder = cel.toCheckerBuilder();

    CelEnvironment.Builder envBuilder =
        CelEnvironment.newBuilder().setContainer(checkerBuilder.container());
    addOptions(envBuilder, checkerBuilder.options());

    Set<Object> inventory = new HashSet<>();
    collectInventory(inventory, cel);
    addExtensionConfigsAndRemoveFromInventory(envBuilder, inventory);
    addStandardLibrarySubsetAndRemoveFromInventory(envBuilder, inventory);
    addCustomDecls(envBuilder, inventory);
    return envBuilder.build();
  }

  private void addOptions(CelEnvironment.Builder envBuilder, CelOptions options) {
    // The set of features supported in the exported environment in Go is pretty limited right now.
    ImmutableSet.Builder<CelEnvironment.FeatureFlag> featureFlags = ImmutableSet.builder();
    if (options.enableHeterogeneousNumericComparisons()) {
      featureFlags.add(
          CelEnvironment.FeatureFlag.create("cel.feature.cross_type_numeric_comparisons", true));
    }
    if (options.enableQuotedIdentifierSyntax()) {
      featureFlags.add(
          CelEnvironment.FeatureFlag.create("cel.feature.backtick_escape_syntax", true));
    }
    if (options.populateMacroCalls()) {
      featureFlags.add(CelEnvironment.FeatureFlag.create("cel.feature.macro_call_tracking", true));
    }
    envBuilder.setFeatures(featureFlags.build());
    ImmutableSet.Builder<CelEnvironment.Limit> limits = ImmutableSet.builder();
    if (options.maxExpressionCodePointSize() != CelOptions.DEFAULT.maxExpressionCodePointSize()) {
      limits.add(
          CelEnvironment.Limit.create(
              "cel.limit.expression_code_points", options.maxExpressionCodePointSize()));
    }
    if (options.maxParseErrorRecoveryLimit() != CelOptions.DEFAULT.maxParseErrorRecoveryLimit()) {
      limits.add(
          CelEnvironment.Limit.create(
              "cel.limit.parse_error_recovery", options.maxParseErrorRecoveryLimit()));
    }
    if (options.maxParseRecursionDepth() != CelOptions.DEFAULT.maxParseRecursionDepth()) {
      limits.add(
          CelEnvironment.Limit.create(
              "cel.limit.parse_recursion_depth", options.maxParseRecursionDepth()));
    }
    if (options.maxParseExpressionNodeCount() != CelOptions.DEFAULT.maxParseExpressionNodeCount()) {
      limits.add(
          CelEnvironment.Limit.create(
              "cel.limit.expression_node_count", options.maxParseExpressionNodeCount()));
    }
    envBuilder.setLimits(limits.build());
  }

  /**
   * Collects all function overloads, variable declarations and macros from the given {@link Cel}
   * instance and stores them in a map.
   */
  private void collectInventory(Set<Object> inventory, CelCompiler cel) {
    Preconditions.checkArgument(cel instanceof EnvVisitable);
    ((EnvVisitable) cel)
        .accept(
            new EnvVisitor() {
              @Override
              public void visitDecl(String name, List<Decl> decls) {
                for (Decl decl : decls) {
                  if (decl.hasFunction()) {
                    FunctionDecl function = decl.getFunction();
                    for (Overload overload : function.getOverloadsList()) {
                      inventory.add(
                          NamedOverload.create(
                              decl.getName(), CelOverloadDecl.overloadToCelOverload(overload)));
                    }
                  } else if (decl.hasIdent()) {
                    inventory.add(
                        CelVarDecl.newVarDeclaration(
                            decl.getName(),
                            CelProtoTypes.typeToCelType(decl.getIdent().getType())));
                  }
                }
              }

              @Override
              public void visitMacro(CelMacro macro) {
                inventory.add(macro);
              }
            });
  }

  /**
   * Iterates through the available extension libraries, checks if they are included in the
   * inventory, and adds them to the environment builder. Only the highest version of a library is
   * added to the builder. If the extension is identified, all corresponding items are removed from
   * the inventory.
   */
  private void addExtensionConfigsAndRemoveFromInventory(
      CelEnvironment.Builder envBuilder, Set<Object> inventory) {
    ArrayList<NamedFeatureSet> featureSets = new ArrayList<>();

    for (CelExtensionLibrary<? extends CelExtensionLibrary.FeatureSet> extensionLibrary :
        extensionLibraries()) {
      for (CelExtensionLibrary.FeatureSet featureSet : extensionLibrary.versions()) {
        featureSets.add(NamedFeatureSet.create(extensionLibrary.name(), featureSet));
      }
    }

    featureSets.sort(
        Comparator.comparing(NamedFeatureSet::name)
            .thenComparing(nfs -> nfs.featureSet().version())
            .reversed());

    Set<String> includedExtensions = new HashSet<>();
    for (NamedFeatureSet lib : featureSets) {
      if (includedExtensions.contains(lib.name())) {
        // We only need to infer the highest version library, so we can skip lower versions
        continue;
      }

      if (checkIfExtensionIsIncludedAndRemoveFromInventory(inventory, lib.featureSet())) {
        envBuilder.addExtensions(ExtensionConfig.of(lib.name(), lib.featureSet().version()));
        includedExtensions.add(lib.name());
      }
    }
  }

  private boolean checkIfExtensionIsIncludedAndRemoveFromInventory(
      Set<Object> inventory, CelExtensionLibrary.FeatureSet featureSet) {
    ImmutableSet<CelFunctionDecl> functions = featureSet.functions();
    ArrayList<Object> includedFeatures = new ArrayList<>(functions.size());
    for (CelFunctionDecl function : functions) {
      for (CelOverloadDecl overload : function.overloads()) {
        NamedOverload feature = NamedOverload.create(function.name(), overload);
        if (!inventory.contains(feature)) {
          return false;
        }
        includedFeatures.add(feature);
      }
    }

    ImmutableSet<CelMacro> macros = featureSet.macros();
    for (CelMacro macro : macros) {
      if (!inventory.contains(macro)) {
        return false;
      }
      includedFeatures.add(macro);
    }

    // TODO - Add checks for variables.

    inventory.removeAll(includedFeatures);
    return true;
  }

  private void addStandardLibrarySubsetAndRemoveFromInventory(
      CelEnvironment.Builder envBuilder, Set<Object> inventory) {
    // Claim standard identifiers for the standard library
    for (StandardIdentifier value : StandardIdentifier.values()) {
      inventory.remove(
          CelVarDecl.newVarDeclaration(value.identDecl().name(), value.identDecl().type()));
    }

    Set<String> excludedFunctions = new HashSet<>();
    Set<String> includedFunctions = new HashSet<>();
    ListMultimap<String, String> excludedOverloads = ArrayListMultimap.create();
    ListMultimap<String, String> includedOverloads = ArrayListMultimap.create();

    stream(StandardFunction.values())
        .map(StandardFunction::functionDecl)
        .forEach(
            decl -> {
              String functionName = decl.name();
              boolean anyOverloadIncluded = false;
              boolean allOverloadsIncluded = true;
              for (CelOverloadDecl overload : decl.overloads()) {
                NamedOverload item = NamedOverload.create(functionName, overload);
                if (inventory.remove(item)) {
                  anyOverloadIncluded = true;
                  includedOverloads.put(functionName, overload.overloadId());
                } else {
                  allOverloadsIncluded = false;
                  excludedOverloads.put(functionName, overload.overloadId());
                }
              }
              if (!anyOverloadIncluded) {
                excludedFunctions.add(functionName);
              }
              if (allOverloadsIncluded) {
                includedFunctions.add(functionName);
              }
            });

    Set<String> excludedMacros = new HashSet<>();
    Set<String> includedMacros = new HashSet<>();
    stream(CelStandardMacro.values())
        .map(celStandardMacro -> celStandardMacro.getDefinition())
        .forEach(
            macro -> {
              if (inventory.remove(macro)) {
                includedMacros.add(macro.getFunction());
              } else {
                excludedMacros.add(macro.getFunction());
              }
            });

    LibrarySubset.Builder subsetBuilder = LibrarySubset.newBuilder().setDisabled(false);
    if (excludedFunctions.size() + excludedMacros.size() <= maxExcludedStandardFunctions()
        && excludedOverloads.size() <= maxExcludedStandardFunctionOverloads()) {
      subsetBuilder
          .setExcludedFunctions(buildFunctionSelectors(excludedFunctions, excludedOverloads))
          .setExcludedMacros(ImmutableSet.copyOf(excludedMacros));
    } else {
      subsetBuilder
          .setIncludedFunctions(buildFunctionSelectors(includedFunctions, includedOverloads))
          .setIncludedMacros(ImmutableSet.copyOf(includedMacros));
    }

    envBuilder.setStandardLibrarySubset(subsetBuilder.build());
  }

  private ImmutableSet<FunctionSelector> buildFunctionSelectors(
      Set<String> functions, ListMultimap<String, String> functionToOverloadsMap) {
    ImmutableSet.Builder<FunctionSelector> functionSelectors = ImmutableSet.builder();
    for (String excludedFunction : functions) {
      functionSelectors.add(FunctionSelector.create(excludedFunction, ImmutableSet.of()));
    }

    for (String functionName : functionToOverloadsMap.keySet()) {
      if (functions.contains(functionName)) {
        continue;
      }
      functionSelectors.add(
          FunctionSelector.create(
              functionName, ImmutableSet.copyOf(functionToOverloadsMap.get(functionName))));
    }
    return functionSelectors.build();
  }

  private void addCustomDecls(CelEnvironment.Builder envBuilder, Set<Object> inventory) {
    // Group "orphaned" function overloads and vars by their names
    ListMultimap<String, CelOverloadDecl> extraOverloads = ArrayListMultimap.create();
    Map<String, CelType> extraVars = new HashMap<>();
    for (Object item : inventory) {
      if (item instanceof NamedOverload) {
        extraOverloads.put(
            ((NamedOverload) item).functionName(), ((NamedOverload) item).overload());
      } else if (item instanceof CelVarDecl) {
        extraVars.put(((CelVarDecl) item).name(), ((CelVarDecl) item).type());
      }
    }

    if (!extraOverloads.isEmpty()) {
      ImmutableSet.Builder<CelEnvironment.FunctionDecl> functionDeclBuilder =
          ImmutableSet.builder();
      for (String functionName : extraOverloads.keySet()) {
        functionDeclBuilder.add(
            CelEnvironment.FunctionDecl.create(
                functionName,
                extraOverloads.get(functionName).stream()
                    .map(this::toCelEnvOverloadDecl)
                    .collect(toImmutableSet())));
      }
      envBuilder.setFunctions(functionDeclBuilder.build());
    }

    if (!extraVars.isEmpty()) {
      ImmutableSet.Builder<CelEnvironment.VariableDecl> varDeclBuilder = ImmutableSet.builder();
      for (String ident : extraVars.keySet()) {
        varDeclBuilder.add(
            CelEnvironment.VariableDecl.create(ident, toCelEnvTypeDecl(extraVars.get(ident))));
      }
      envBuilder.setVariables(varDeclBuilder.build());
    }
  }

  private CelEnvironment.OverloadDecl toCelEnvOverloadDecl(CelOverloadDecl overload) {
    OverloadDecl.Builder builder =
        OverloadDecl.newBuilder()
            .setId(overload.overloadId())
            .setReturnType(toCelEnvTypeDecl(overload.resultType()));

    if (overload.isInstanceFunction()) {
      builder
          .setTarget(toCelEnvTypeDecl(overload.parameterTypes().get(0)))
          .setArguments(
              overload.parameterTypes().stream()
                  .skip(1)
                  .map(this::toCelEnvTypeDecl)
                  .collect(toImmutableList()));
    } else {
      builder.setArguments(
          overload.parameterTypes().stream()
              .map(this::toCelEnvTypeDecl)
              .collect(toImmutableList()));
    }
    return builder.build();
  }

  private CelEnvironment.TypeDecl toCelEnvTypeDecl(CelType type) {
    return CelEnvironment.TypeDecl.newBuilder()
        .setName(type.name())
        .setIsTypeParam(type.kind() == CelKind.TYPE_PARAM)
        .addParams(
            type.parameters().stream().map(this::toCelEnvTypeDecl).collect(toImmutableList()))
        .build();
  }

  /** Wrapper for CelOverloadDecl, associating it with the corresponding function name. */
  @AutoValue
  abstract static class NamedOverload {
    abstract String functionName();

    abstract CelOverloadDecl overload();

    static NamedOverload create(String functionName, CelOverloadDecl overload) {
      return new AutoValue_CelEnvironmentExporter_NamedOverload(functionName, overload);
    }
  }

  /**
   * Wrapper for CelExtensionLibrary.FeatureSet, associating it with the corresponding library name.
   */
  @AutoValue
  abstract static class NamedFeatureSet {
    abstract String name();

    abstract CelExtensionLibrary.FeatureSet featureSet();

    static NamedFeatureSet create(String name, CelExtensionLibrary.FeatureSet featureSet) {
      return new AutoValue_CelEnvironmentExporter_NamedFeatureSet(name, featureSet);
    }
  }
}
