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

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.extensions.proto.ProtoTruth.assertThat;
import static dev.cel.testing.utils.ExprValueUtils.fromValue;
import static dev.cel.testing.utils.ExprValueUtils.toExprValue;

import dev.cel.expr.Decl;
import dev.cel.expr.ExprValue;
import dev.cel.expr.MapValue;
import dev.cel.expr.Type;
import dev.cel.expr.Value;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.protobuf.ExtensionRegistry;
import com.google.protobuf.TypeRegistry;
import dev.cel.checker.CelChecker;
import dev.cel.common.CelContainer;
import dev.cel.common.CelOptions;
import dev.cel.common.CelValidationResult;
import dev.cel.common.types.CelProtoTypes;
import dev.cel.compiler.CelCompilerFactory;
import dev.cel.compiler.CelCompilerLibrary;
import dev.cel.expr.conformance.test.SimpleTest;
import dev.cel.extensions.CelExtensions;
import dev.cel.extensions.CelOptionalLibrary;
import dev.cel.parser.CelParser;
import dev.cel.parser.CelParserFactory;
import dev.cel.parser.CelStandardMacro;
import dev.cel.runtime.CelEvaluationException;
import dev.cel.runtime.CelRuntime;
import dev.cel.runtime.CelRuntime.Program;
import dev.cel.runtime.CelRuntimeBuilder;
import dev.cel.runtime.CelRuntimeFactory;
import dev.cel.runtime.CelRuntimeImpl;
import dev.cel.runtime.CelRuntimeLibrary;
import java.util.Map;
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
          CelExtensions.math(OPTIONS),
          CelExtensions.network(),
          CelExtensions.protos(),
          CelExtensions.sets(OPTIONS),
          CelExtensions.strings(),
          CelOptionalLibrary.INSTANCE);

  private static final ImmutableList<CelRuntimeLibrary> CANONICAL_RUNTIME_EXTENSIONS =
      ImmutableList.of(
          CelExtensions.comprehensions(),
          CelExtensions.encoders(OPTIONS),
          CelExtensions.math(OPTIONS),
          CelExtensions.network(),
          CelExtensions.sets(OPTIONS),
          CelExtensions.strings(),
          CelOptionalLibrary.INSTANCE);

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
        .addFileTypes(dev.cel.expr.conformance.proto2.TestAllTypesExtensions.getDescriptor())
        .addLibraries(CANONICAL_COMPILER_EXTENSIONS)
        .addMessageTypes(dev.cel.expr.conformance.proto2.TestAllTypes.getDescriptor())
        .addMessageTypes(dev.cel.expr.conformance.proto3.TestAllTypes.getDescriptor())
        .build();
  }

  private static CelRuntime getRuntime(SimpleTest test, boolean usePlanner) {
    CelRuntimeBuilder builder =
        usePlanner ? CelRuntimeImpl.newBuilder() : CelRuntimeFactory.standardCelRuntimeBuilder();

    builder
        // CEL-Internal-2
        .setOptions(OPTIONS)
        .addLibraries(CANONICAL_RUNTIME_EXTENSIONS)
        .setExtensionRegistry(CONFORMANCE_EXTENSION_REGISTRY)
        .addMessageTypes(dev.cel.expr.conformance.proto2.TestAllTypes.getDescriptor())
        .addMessageTypes(dev.cel.expr.conformance.proto3.TestAllTypes.getDescriptor())
        .addFileTypes(dev.cel.expr.conformance.proto2.TestAllTypesExtensions.getDescriptor());

    if (usePlanner) {
      builder.setContainer(CelContainer.ofName(test.getContainer()));
    }

    return builder.build();
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
  private final boolean usePlanner;

  public ConformanceTest(String name, SimpleTest test, boolean skip, boolean usePlanner) {
    this.name = Preconditions.checkNotNull(name);
    this.test =
        Preconditions.checkNotNull(
            defaultTestMatcherToTrueIfUnset(Preconditions.checkNotNull(test)));
    this.skip = skip;
    this.usePlanner = usePlanner;
  }

  public String getName() {
    return name;
  }

  public boolean shouldSkip() {
    return skip;
  }

  @Override
  public void evaluate() throws Throwable {
    CelValidationResult response = getParser(test).parse(test.getExpr(), test.getName());
    assertThat(response.hasError()).isFalse();
    if (!test.getDisableCheck()) {
      response = getChecker(test).check(response.getAst());
    }
    assertThat(response.hasError()).isFalse();
    Type resultType = CelProtoTypes.celTypeToType(response.getAst().getResultType());

    if (test.getCheckOnly()) {
      assertThat(test.hasTypedResult()).isTrue();
      assertThat(resultType).isEqualTo(test.getTypedResult().getDeducedType());
      return;
    }

    if (!usePlanner && test.getDisableCheck()) {
      // Only planner supports parsed-only evaluation
      return;
    }

    CelRuntime runtime = getRuntime(test, usePlanner);
    ExprValue result = null;
    CelEvaluationException error = null;
    try {
      Program program = runtime.createProgram(response.getAst());
      result = toExprValue(program.eval(getBindings(test)), response.getAst().getResultType());
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
}
