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

package dev.cel.conformance;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.extensions.proto.ProtoTruth.assertThat;
import static dev.cel.testing.utils.ExprValueUtils.fromValue;
import static dev.cel.testing.utils.ExprValueUtils.toExprValue;

import dev.cel.expr.Decl;
import dev.cel.expr.ExprValue;
import dev.cel.expr.MapValue;
import dev.cel.expr.Type;
import dev.cel.expr.Value;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.ExtensionRegistry;
import com.google.protobuf.TypeRegistry;
import dev.cel.checker.CelChecker;
import dev.cel.checker.CelCheckerBuilder;
import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.CelContainer;
import dev.cel.common.CelIssue;
import dev.cel.common.CelOptions;
import dev.cel.common.CelSource.Extension;
import dev.cel.common.CelSource.Extension.Component;
import dev.cel.common.CelSource.Extension.Version;
import dev.cel.common.CelValidationResult;
import dev.cel.common.CelVarDecl;
import dev.cel.common.ast.CelBlock;
import dev.cel.common.ast.CelConstant;
import dev.cel.common.ast.CelExpr;
import dev.cel.common.types.CelProtoTypes;
import dev.cel.common.types.CelTypeProvider;
import dev.cel.common.types.ProtoMessageTypeProvider;
import dev.cel.common.types.SimpleType;
import dev.cel.common.values.CelValueProvider;
import dev.cel.common.values.ProtoMessageLiteValueProvider;
import dev.cel.compiler.CelCompilerFactory;
import dev.cel.compiler.CelCompilerLibrary;
import dev.cel.expr.conformance.test.SimpleTest;
import dev.cel.extensions.CelBindingsExtensions;
import dev.cel.extensions.CelComprehensionsRuntimeLibrary;
import dev.cel.extensions.CelEncoderRuntimeLibrary;
import dev.cel.extensions.CelExtensions;
import dev.cel.extensions.CelListsRuntimeLibrary;
import dev.cel.extensions.CelMathRuntimeLibrary;
import dev.cel.extensions.CelOptionalLibrary;
import dev.cel.extensions.CelOptionalRuntimeLibrary;
import dev.cel.extensions.CelSetsRuntimeLibrary;
import dev.cel.extensions.CelStringRuntimeLibrary;
import dev.cel.optimizer.CelOptimizerFactory;
import dev.cel.optimizer.optimizers.SelectOptimizer;
import dev.cel.parser.CelMacro;
import dev.cel.parser.CelMacroExpander;
import dev.cel.parser.CelMacroExprFactory;
import dev.cel.parser.CelParser;
import dev.cel.parser.CelParserBuilder;
import dev.cel.parser.CelParserFactory;
import dev.cel.parser.CelStandardMacro;
import dev.cel.runtime.CelEvaluationException;
import dev.cel.runtime.CelLiteRuntimeFactory;
import dev.cel.runtime.CelLiteRuntimeLibrary;
import dev.cel.runtime.CelRuntime;
import dev.cel.runtime.CelRuntimeBuilder;
import dev.cel.runtime.CelRuntimeFactory;
import dev.cel.runtime.CelRuntimeImpl;
import dev.cel.runtime.CelRuntimeLibrary;
import dev.cel.runtime.CelStandardFunctions;
import dev.cel.runtime.Program;
import java.util.Map;
import java.util.Optional;
import org.junit.runners.model.Statement;

// Qualifying proto2/proto3 TestAllTypes makes it less clear.
@SuppressWarnings("UnnecessarilyFullyQualified")
public final class ConformanceTest extends Statement {

  private static final CelOptions OPTIONS =
      CelOptions.current()
          .enableHeterogeneousNumericComparisons(true)
          .enableProtoDifferencerEquality(true)
          .enableOptionalSyntax(true)
          .enableQuotedIdentifierSyntax(true)
          .build();

  private static final ImmutableList<CelCompilerLibrary> CANONICAL_COMPILER_EXTENSIONS =
      ImmutableList.of(
          CelExtensions.bindings(),
          CelExtensions.comprehensions(),
          CelExtensions.encoders(OPTIONS),
          CelExtensions.lists(),
          CelExtensions.math(),
          CelExtensions.protos(),
          CelExtensions.sets(OPTIONS),
          CelExtensions.strings(),
          CelOptionalLibrary.INSTANCE,
          new ConformanceBlockLibrary());

  private static final ImmutableList<CelRuntimeLibrary> CANONICAL_RUNTIME_EXTENSIONS =
      ImmutableList.of(
          CelExtensions.comprehensions(),
          CelExtensions.encoders(OPTIONS),
          CelExtensions.lists(),
          CelExtensions.math(),
          CelExtensions.sets(OPTIONS),
          CelExtensions.strings(),
          CelOptionalLibrary.INSTANCE);

  private static final ImmutableList<CelLiteRuntimeLibrary> CANONICAL_LITE_RUNTIME_EXTENSIONS =
      ImmutableList.of(
          CelComprehensionsRuntimeLibrary.comprehensions(),
          CelEncoderRuntimeLibrary.encoders(),
          CelListsRuntimeLibrary.lists(),
          CelMathRuntimeLibrary.math(),
          CelOptionalRuntimeLibrary.optional(),
          CelSetsRuntimeLibrary.sets(),
          CelStringRuntimeLibrary.strings());

  private static final CelValueProvider LITE_VALUE_PROVIDER =
      ProtoMessageLiteValueProvider.newInstance(
          dev.cel.expr.conformance.proto2.TestAllTypesCelDescriptor.getDescriptor(),
          dev.cel.expr.conformance.proto2.NestedTestAllTypesCelDescriptor.getDescriptor(),
          dev.cel.expr.conformance.proto2.TestRequiredCelDescriptor.getDescriptor(),
          dev.cel.expr.conformance.proto3.TestAllTypesCelDescriptor.getDescriptor(),
          dev.cel.expr.conformance.proto3.NestedTestAllTypesCelDescriptor.getDescriptor());

  private static final ImmutableList<FileDescriptor> CONFORMANCE_FILE_DESCRIPTORS =
      ImmutableList.of(
          dev.cel.expr.conformance.proto2.TestAllTypes.getDescriptor().getFile(),
          dev.cel.expr.conformance.proto3.TestAllTypes.getDescriptor().getFile(),
          dev.cel.expr.conformance.proto2.TestAllTypesExtensions.getDescriptor());

  private static final CelTypeProvider LITE_TYPE_PROVIDER =
      ProtoMessageTypeProvider.newBuilder()
          .addFileDescriptors(CONFORMANCE_FILE_DESCRIPTORS)
          .build();

  private static final SelectOptimizer SELECT_OPTIMIZER =
      SelectOptimizer.newInstance(CONFORMANCE_FILE_DESCRIPTORS);

  private static final CelRuntime UNUSED_RUNTIME =
      CelRuntimeFactory.plannerRuntimeBuilder().build();

  enum RuntimeType {
    LEGACY,
    PLANNER,
    LITE
  }

  static final TypeRegistry CONFORMANCE_TYPE_REGISTRY =
      TypeRegistry.newBuilder()
          .add(dev.cel.expr.conformance.proto2.TestAllTypes.getDescriptor())
          .add(dev.cel.expr.conformance.proto3.TestAllTypes.getDescriptor())
          .build();

  static final ExtensionRegistry CONFORMANCE_EXTENSION_REGISTRY =
      createConformanceExtensionRegistry();

  private static ExtensionRegistry createConformanceExtensionRegistry() {
    ExtensionRegistry extensionRegistry = ExtensionRegistry.newInstance();
    dev.cel.expr.conformance.proto2.TestAllTypesExtensions.registerAllExtensions(extensionRegistry);
    return extensionRegistry;
  }

  private static final CelParser PARSER_WITH_MACROS =
      CelParserFactory.standardCelParserBuilder()
          .setOptions(OPTIONS)
          .addLibraries(CANONICAL_COMPILER_EXTENSIONS)
          .setStandardMacros(CelStandardMacro.STANDARD_MACROS)
          .build();

  private static final CelParser PARSER_WITHOUT_MACROS =
      CelParserFactory.standardCelParserBuilder()
          .setOptions(OPTIONS)
          .addLibraries(CANONICAL_COMPILER_EXTENSIONS)
          .setStandardMacros()
          .build();

  private static CelParser getParser(SimpleTest test) {
    return test.getDisableMacros() ? PARSER_WITHOUT_MACROS : PARSER_WITH_MACROS;
  }

  private static CelChecker getChecker(SimpleTest test) throws Exception {
    ImmutableList.Builder<Decl> decls =
        ImmutableList.builderWithExpectedSize(test.getTypeEnvCount());
    for (dev.cel.expr.Decl decl : test.getTypeEnvList()) {
      decls.add(Decl.parseFrom(decl.toByteArray(), CONFORMANCE_EXTENSION_REGISTRY));
    }
    return CelCompilerFactory.standardCelCheckerBuilder()
        .setOptions(OPTIONS)
        .setContainer(CelContainer.ofName(test.getContainer()))
        .addDeclarations(decls.build())
        .addFileTypes(CONFORMANCE_FILE_DESCRIPTORS)
        .addLibraries(CANONICAL_COMPILER_EXTENSIONS)
        .build();
  }

  private static ImmutableMap<String, Object> getBindings(SimpleTest test) throws Exception {
    ImmutableMap.Builder<String, Object> bindings =
        ImmutableMap.builderWithExpectedSize(test.getBindingsCount());
    for (Map.Entry<String, ExprValue> entry : test.getBindingsMap().entrySet()) {
      bindings.put(entry.getKey(), fromExprValue(entry.getValue()));
    }
    return bindings.buildOrThrow();
  }

  private static Object fromExprValue(ExprValue value) throws Exception {
    switch (value.getKindCase()) {
      case VALUE:
        return fromValue(
            value.getValue(), CONFORMANCE_TYPE_REGISTRY, CONFORMANCE_EXTENSION_REGISTRY);
      default:
        throw new IllegalArgumentException(
            String.format("Unexpected binding value kind: %s", value.getKindCase()));
    }
  }

  private static SimpleTest defaultTestMatcherToTrueIfUnset(SimpleTest test) {
    if (test.getResultMatcherCase() == SimpleTest.ResultMatcherCase.RESULTMATCHER_NOT_SET) {
      return test.toBuilder().setValue(Value.newBuilder().setBoolValue(true).build()).build();
    }
    return test;
  }

  private final String name;
  private final SimpleTest test;
  private final boolean skip;
  private final RuntimeType runtimeType;
  private final boolean enableSelectOptimization;

  public String getName() {
    return name;
  }

  public boolean shouldSkip() {
    return skip;
  }

  @Override
  public void evaluate() throws Throwable {
    CelParser parser = getParser(test);
    CelValidationResult response = parser.parse(test.getExpr(), test.getName());
    assertThat(response.getErrors()).isEmpty();
    CelAbstractSyntaxTree ast = response.getAst();
    if (!test.getDisableCheck()) {
      CelChecker checker = getChecker(test);
      response = checker.check(ast);
      assertThat(response.getErrors()).isEmpty();
      ast = response.getAst();
      if (enableSelectOptimization) {
        ast =
            CelOptimizerFactory.standardCelOptimizerBuilder(parser, checker, UNUSED_RUNTIME)
                .addAstOptimizers(SELECT_OPTIMIZER)
                .build()
                .optimize(ast);
      }
    }
    Type resultType = CelProtoTypes.celTypeToType(ast.getResultType());

    if (test.getCheckOnly()) {
      assertThat(test.hasTypedResult()).isTrue();
      assertThat(resultType).isEqualTo(test.getTypedResult().getDeducedType());
      return;
    }

    if (runtimeType.equals(RuntimeType.LEGACY) && test.getDisableCheck()) {
      // Only planner and lite runtime support parsed-only evaluation
      return;
    }

    if (ast.getExpr().callOrDefault().function().equals(CelBlock.FUNCTION_NAME)) {
      ast =
          CelAbstractSyntaxTree.newCheckedAst(
              ast.getExpr(),
              ast.getSource().toBuilder()
                  .addAllExtensions(
                      Extension.create("cel_block", Version.of(1, 1), Component.COMPONENT_RUNTIME))
                  .build(),
              ast.getReferenceMap(),
              ast.getTypeMap());
    }

    ExprValue result = null;
    CelEvaluationException error = null;
    try {
      Program program = createProgram(ast);
      result = toExprValue(program.eval(getBindings(test)), ast.getResultType());
    } catch (CelEvaluationException e) {
      error = e;
    }
    switch (test.getResultMatcherCase()) {
      case VALUE:
        assertThat(error).isNull();
        assertThat(result).isNotNull();
        assertThat(result)
            .ignoringRepeatedFieldOrderOfFieldDescriptors(
                MapValue.getDescriptor().findFieldByName("entries"))
            .unpackingAnyUsing(CONFORMANCE_TYPE_REGISTRY, CONFORMANCE_EXTENSION_REGISTRY)
            .isEqualTo(ExprValue.newBuilder().setValue(test.getValue()).build());
        break;
      case EVAL_ERROR:
        assertThat(result).isNull();
        assertThat(error).isNotNull();
        break;
      case TYPED_RESULT:
        assertThat(error).isNull();
        assertThat(result).isNotNull();
        assertThat(result)
            .ignoringRepeatedFieldOrderOfFieldDescriptors(
                MapValue.getDescriptor().findFieldByName("entries"))
            .unpackingAnyUsing(CONFORMANCE_TYPE_REGISTRY, CONFORMANCE_EXTENSION_REGISTRY)
            .isEqualTo(ExprValue.newBuilder().setValue(test.getTypedResult().getResult()).build());
        assertThat(resultType).isEqualTo(test.getTypedResult().getDeducedType());
        break;
      default:
        throw new IllegalStateException(
            String.format("Unexpected matcher kind: %s", test.getResultMatcherCase()));
    }
  }

  @SuppressWarnings("deprecation") // Intentionally testing the legacy interpreter runtime.
  private Program createProgram(CelAbstractSyntaxTree ast) throws CelEvaluationException {
    if (runtimeType.equals(RuntimeType.LITE)) {
      return CelLiteRuntimeFactory.newLiteRuntimeBuilder()
          .setOptions(OPTIONS)
          .setStandardFunctions(CelStandardFunctions.ALL_STANDARD_FUNCTIONS)
          .addLibraries(CANONICAL_LITE_RUNTIME_EXTENSIONS)
          .setValueProvider(LITE_VALUE_PROVIDER)
          .setTypeProvider(LITE_TYPE_PROVIDER)
          .setContainer(CelContainer.ofName(test.getContainer()))
          .build()
          .createProgram(ast);
    }

    CelRuntimeBuilder builder =
        runtimeType.equals(RuntimeType.PLANNER)
            ? CelRuntimeImpl.newBuilder()
            : CelRuntimeFactory.standardCelRuntimeBuilder();

    builder
        // CEL-Internal-2
        .setOptions(OPTIONS)
        .addLibraries(CANONICAL_RUNTIME_EXTENSIONS)
        .setExtensionRegistry(CONFORMANCE_EXTENSION_REGISTRY)
        .addFileTypes(CONFORMANCE_FILE_DESCRIPTORS);

    if (runtimeType.equals(RuntimeType.PLANNER)) {
      builder.setContainer(CelContainer.ofName(test.getContainer()));
    }

    return builder.build().createProgram(ast);
  }

  ConformanceTest(
      String name,
      SimpleTest test,
      boolean skip,
      RuntimeType runtimeType,
      boolean enableSelectOptimization) {
    this.name = checkNotNull(name);
    this.test = defaultTestMatcherToTrueIfUnset(checkNotNull(test));
    this.skip = skip;
    this.runtimeType = checkNotNull(runtimeType);
    this.enableSelectOptimization = enableSelectOptimization;
  }

  /**
   * Conformance-only library providing macros for the {@code block_ext} test suite.
   *
   * <p>These macros ({@code cel.block}, {@code cel.index}, {@code cel.iterVar}, and {@code
   * cel.accuVar}) are strictly used for conformance testing to represent block expressions in text
   * form. In production, AST optimization passes (such as common subexpression elimination)
   * directly generate the {@code cel.@block} call and {@code @index} / {@code @it} / {@code @ac}
   * variable nodes without going through these macros.
   */
  private static final class ConformanceBlockLibrary implements CelCompilerLibrary {
    private static final int MAX_INDICES = 30;

    @Override
    public void setParserOptions(CelParserBuilder parserBuilder) {
      parserBuilder.addMacros(
          CelMacro.newReceiverMacro("block", 2, ConformanceBlockLibrary::expandBlock),
          CelMacro.newReceiverMacro("index", 1, ConformanceBlockLibrary::expandIndex),
          CelMacro.newReceiverMacro("iterVar", 2, expandCompreVar("cel.iterVar", "@it")),
          CelMacro.newReceiverMacro("accuVar", 2, expandCompreVar("cel.accuVar", "@ac")));
    }

    @Override
    public void setCheckerOptions(CelCheckerBuilder checkerBuilder) {
      checkerBuilder.addFunctionDeclarations(CelBindingsExtensions.CEL_BLOCK_FUNCTION_DECL);
      for (int i = 0; i < MAX_INDICES; i++) {
        checkerBuilder.addVarDeclarations(
            CelVarDecl.newVarDeclaration(CelBlock.INDEX_PREFIX + i, SimpleType.DYN));
      }
    }

    private static Optional<CelExpr> expandBlock(
        CelMacroExprFactory exprFactory, CelExpr target, ImmutableList<CelExpr> args) {
      if (!isCelNamespace(target)) {
        return Optional.empty();
      }
      CelExpr bindings = args.get(0);
      if (!bindings.exprKind().getKind().equals(CelExpr.ExprKind.Kind.LIST)) {
        return Optional.of(
            exprFactory.reportError(
                CelIssue.formatError(
                    exprFactory.getSourceLocation(bindings),
                    "cel.block requires the first arg to be a list literal")));
      }
      return Optional.of(exprFactory.newGlobalCall(CelBlock.FUNCTION_NAME, args));
    }

    private static Optional<CelExpr> expandIndex(
        CelMacroExprFactory exprFactory, CelExpr target, ImmutableList<CelExpr> args) {
      if (!isCelNamespace(target)) {
        return Optional.empty();
      }
      CelExpr index = args.get(0);
      if (!isNonNegativeInt(index)) {
        return Optional.of(
            exprFactory.reportError(
                CelIssue.formatError(
                    exprFactory.getSourceLocation(index),
                    "cel.index requires a single non-negative int constant arg")));
      }
      return Optional.of(
          exprFactory.newIdentifier(CelBlock.INDEX_PREFIX + index.constant().int64Value()));
    }

    private static CelMacroExpander expandCompreVar(String macroName, String prefix) {
      return (exprFactory, target, args) -> {
        if (!isCelNamespace(target)) {
          return Optional.empty();
        }
        for (CelExpr arg : args) {
          if (!isNonNegativeInt(arg)) {
            return Optional.of(
                exprFactory.reportError(
                    CelIssue.formatError(
                        exprFactory.getSourceLocation(arg),
                        macroName + " requires two non-negative int constant args")));
          }
        }
        return Optional.of(
            exprFactory.newIdentifier(
                String.format(
                    "%s:%d:%d",
                    prefix,
                    args.get(0).constant().int64Value(),
                    args.get(1).constant().int64Value())));
      };
    }

    private static boolean isNonNegativeInt(CelExpr expr) {
      return expr.exprKind().getKind().equals(CelExpr.ExprKind.Kind.CONSTANT)
          && expr.constant().getKind().equals(CelConstant.Kind.INT64_VALUE)
          && expr.constant().int64Value() >= 0;
    }

    private static boolean isCelNamespace(CelExpr target) {
      return target.exprKind().getKind().equals(CelExpr.ExprKind.Kind.IDENT)
          && target.ident().name().equals("cel");
    }
  }
}
