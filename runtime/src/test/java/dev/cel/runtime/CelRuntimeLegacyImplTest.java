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

package dev.cel.runtime;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.util.concurrent.MoreExecutors.newDirectExecutorService;
import static dev.cel.common.CelFunctionDecl.newFunctionDeclaration;
import static dev.cel.common.CelOverloadDecl.newGlobalOverload;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.protobuf.Any;
import com.google.protobuf.BoolValue;
import com.google.protobuf.DescriptorProtos.FileDescriptorSet;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.Message;
import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.CelErrorCode;
import dev.cel.common.CelException;
import dev.cel.common.exceptions.CelDivideByZeroException;
import dev.cel.common.types.SimpleType;
import dev.cel.compiler.CelCompiler;
import dev.cel.compiler.CelCompilerFactory;
import dev.cel.expr.conformance.proto3.TestAllTypes;
import dev.cel.runtime.CelStandardFunctions.StandardFunction;
import java.util.Optional;
import java.util.function.Function;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
@SuppressWarnings("deprecation") // Tests legacy runtime implementation.
public final class CelRuntimeLegacyImplTest {

  @Test
  public void evalException() throws CelException {
    CelCompiler compiler = CelCompilerFactory.standardCelCompilerBuilder().build();
    CelRuntime runtime = CelRuntimeFactory.legacyCelRuntimeBuilder().build();
    CelRuntime.Program program = runtime.createProgram(compiler.compile("1/0").getAst());
    CelEvaluationException e = assertThrows(CelEvaluationException.class, program::eval);
    assertThat(e).hasCauseThat().isInstanceOf(CelDivideByZeroException.class);
  }

  @Test
  public void newWellKnownTypeMessage_inDynamicMessage_withSetTypeFactory() throws Exception {
    CelCompiler celCompiler =
        CelCompilerFactory.standardCelCompilerBuilder()
            .addMessageTypes(BoolValue.getDescriptor())
            .build();
    CelRuntime celRuntime =
        CelRuntimeFactory.legacyCelRuntimeBuilder()
            // CEL-Internal-2
            .setTypeFactory(
                (typeName) ->
                    typeName.equals("google.protobuf.BoolValue")
                        ? DynamicMessage.newBuilder(BoolValue.getDescriptor())
                        : null)
            .build();

    CelAbstractSyntaxTree ast =
        celCompiler.compile("google.protobuf.BoolValue{value: false}").getAst();

    assertThat(celRuntime.createProgram(ast).eval()).isEqualTo(false);
  }

  @Test
  public void newWellKnownTypeMessage_inAnyMessage_withSetTypeFactory() throws Exception {
    FileDescriptorSet fds =
        FileDescriptorSet.newBuilder()
            // Copy the WKT descriptors
            .addFile(Any.getDescriptor().getFile().toProto())
            .addFile(BoolValue.getDescriptor().getFile().toProto())
            .build();
    CelCompiler celCompiler =
        CelCompilerFactory.standardCelCompilerBuilder().addFileTypes(fds).build();
    CelRuntime celRuntime =
        CelRuntimeFactory.legacyCelRuntimeBuilder()
            // CEL-Internal-2
            .addFileTypes(fds)
            .setTypeFactory(
                (typeName) ->
                    typeName.equals("google.protobuf.Any")
                        ? Any.newBuilder().setTypeUrl("google.protobuf.DoubleValue")
                        : null)
            .build();

    CelAbstractSyntaxTree ast =
        celCompiler
            .compile(
                "google.protobuf.Any{type_url: 'types.googleapis.com/google.protobuf.DoubleValue'}")
            .getAst();

    assertThat(celRuntime.createProgram(ast).eval()).isEqualTo(0.0d);
  }

  @Test
  public void standardEnvironmentDisabledForRuntime_throws() throws Exception {
    CelCompiler celCompiler =
        CelCompilerFactory.standardCelCompilerBuilder().setStandardEnvironmentEnabled(true).build();
    CelRuntime celRuntime =
        CelRuntimeFactory.legacyCelRuntimeBuilder().setStandardEnvironmentEnabled(false).build();
    CelAbstractSyntaxTree ast = celCompiler.compile("size('hello')").getAst();
    CelRuntime.Program program = celRuntime.createProgram(ast);

    CelEvaluationException e = assertThrows(CelEvaluationException.class, program::eval);
    assertThat(e)
        .hasMessageThat()
        .contains("No matching overload for function 'size'. Overload candidates: size_string");
  }

  @Test
  public void toRuntimeBuilder_isNewInstance() {
    CelRuntimeBuilder celRuntimeBuilder = CelRuntimeFactory.legacyCelRuntimeBuilder();
    CelRuntimeLegacyImpl celRuntime = (CelRuntimeLegacyImpl) celRuntimeBuilder.build();

    CelRuntimeLegacyImpl.Builder newRuntimeBuilder =
        (CelRuntimeLegacyImpl.Builder) celRuntime.toRuntimeBuilder();

    assertThat(newRuntimeBuilder).isNotEqualTo(celRuntimeBuilder);
  }

  @Test
  public void toRuntimeBuilder_isImmutable() {
    CelRuntimeBuilder originalRuntimeBuilder = CelRuntimeFactory.legacyCelRuntimeBuilder();
    CelRuntimeLegacyImpl celRuntime = (CelRuntimeLegacyImpl) originalRuntimeBuilder.build();
    originalRuntimeBuilder.addLibraries(runtimeBuilder -> {});

    CelRuntimeLegacyImpl.Builder newRuntimeBuilder =
        (CelRuntimeLegacyImpl.Builder) celRuntime.toRuntimeBuilder();

    assertThat(newRuntimeBuilder.celRuntimeLibraries.build()).isEmpty();
  }

  @Test
  public void toRuntimeBuilder_collectionProperties_copied() {
    CelRuntimeBuilder celRuntimeBuilder = CelRuntimeFactory.legacyCelRuntimeBuilder();
    celRuntimeBuilder.addMessageTypes(TestAllTypes.getDescriptor());
    celRuntimeBuilder.addFileTypes(TestAllTypes.getDescriptor().getFile());
    celRuntimeBuilder.addFunctionBindings(CelFunctionBinding.from("test", Integer.class, arg -> 1));
    celRuntimeBuilder.addLibraries(runtimeBuilder -> {});
    int originalFileTypesSize =
        ((CelRuntimeLegacyImpl.Builder) celRuntimeBuilder).fileTypes.build().size();
    CelRuntimeLegacyImpl celRuntime = (CelRuntimeLegacyImpl) celRuntimeBuilder.build();

    CelRuntimeLegacyImpl.Builder newRuntimeBuilder =
        (CelRuntimeLegacyImpl.Builder) celRuntime.toRuntimeBuilder();

    assertThat(newRuntimeBuilder.customFunctionBindings).hasSize(1);
    assertThat(newRuntimeBuilder.celRuntimeLibraries.build()).hasSize(1);
    assertThat(newRuntimeBuilder.fileTypes.build()).hasSize(originalFileTypesSize);
  }

  @Test
  public void toRuntimeBuilder_collectionProperties_areImmutable() {
    CelRuntimeBuilder celRuntimeBuilder = CelRuntimeFactory.legacyCelRuntimeBuilder();
    CelRuntimeLegacyImpl celRuntime = (CelRuntimeLegacyImpl) celRuntimeBuilder.build();
    CelRuntimeLegacyImpl.Builder newRuntimeBuilder =
        (CelRuntimeLegacyImpl.Builder) celRuntime.toRuntimeBuilder();

    // Mutate the original builder containing collections
    celRuntimeBuilder.addMessageTypes(TestAllTypes.getDescriptor());
    celRuntimeBuilder.addFileTypes(TestAllTypes.getDescriptor().getFile());
    celRuntimeBuilder.addFunctionBindings(CelFunctionBinding.from("test", Integer.class, arg -> 1));
    celRuntimeBuilder.addLibraries(runtimeBuilder -> {});

    assertThat(newRuntimeBuilder.customFunctionBindings).isEmpty();
    assertThat(newRuntimeBuilder.celRuntimeLibraries.build()).isEmpty();
    assertThat(newRuntimeBuilder.fileTypes.build()).isEmpty();
  }

  @Test
  public void toRuntimeBuilder_optionalProperties() {
    Function<String, Message.Builder> customTypeFactory = (typeName) -> TestAllTypes.newBuilder();
    CelStandardFunctions overriddenStandardFunctions =
        CelStandardFunctions.newBuilder().includeFunctions(StandardFunction.ADD).build();
    CelRuntimeBuilder celRuntimeBuilder =
        CelRuntimeFactory.legacyCelRuntimeBuilder()
            .setStandardEnvironmentEnabled(false)
            .setTypeFactory(customTypeFactory)
            .setStandardFunctions(overriddenStandardFunctions);
    CelRuntime celRuntime = celRuntimeBuilder.build();

    CelRuntimeLegacyImpl.Builder newRuntimeBuilder =
        (CelRuntimeLegacyImpl.Builder) celRuntime.toRuntimeBuilder();

    assertThat(newRuntimeBuilder.customTypeFactory).isEqualTo(customTypeFactory);
    assertThat(newRuntimeBuilder.overriddenStandardFunctions)
        .isEqualTo(overriddenStandardFunctions);
  }

  @Test
  public void toRuntimeBuilder_asyncProperties_copied() {
    ListeningExecutorService executor = newDirectExecutorService();
    CelAsyncEvaluationOptions options =
        CelAsyncEvaluationOptions.newBuilder().setMaxConcurrency(5).build();
    CelRuntimeBuilder celRuntimeBuilder =
        CelRuntimeFactory.legacyCelRuntimeBuilder()
            .setAsyncEvaluationOptions(options)
            .setAsyncExecutor(executor);
    CelRuntime celRuntime = celRuntimeBuilder.build();

    CelRuntimeLegacyImpl.Builder newRuntimeBuilder =
        (CelRuntimeLegacyImpl.Builder) celRuntime.toRuntimeBuilder();

    assertThat(newRuntimeBuilder.asyncEvaluationOptions).isEqualTo(options);
    assertThat(newRuntimeBuilder.asyncExecutor).isEqualTo(executor);
  }

  @Test
  public void evalAsync_legacyInterpreter_throwsUnsupportedOperationException() throws Exception {
    CelCompiler compiler = CelCompilerFactory.standardCelCompilerBuilder().build();
    CelRuntime runtime = CelRuntimeFactory.legacyCelRuntimeBuilder().build();
    CelRuntime.Program program = runtime.createProgram(compiler.compile("1 + 1").getAst());
    CelVariableResolver resolver = name -> Optional.of(1L);

    assertThrows(UnsupportedOperationException.class, program::evalAsync);
    assertThrows(UnsupportedOperationException.class, () -> program.evalAsync(ImmutableMap.of()));
    assertThrows(
        UnsupportedOperationException.class,
        () -> program.evalAsync(ImmutableMap.of(), CelFunctionResolver.EMPTY));
    assertThrows(
        UnsupportedOperationException.class,
        () -> program.evalAsync(TestAllTypes.getDefaultInstance()));
    assertThrows(UnsupportedOperationException.class, () -> program.evalAsync(resolver));
    assertThrows(
        UnsupportedOperationException.class,
        () -> program.evalAsync(resolver, CelFunctionResolver.EMPTY));
    assertThrows(UnsupportedOperationException.class, () -> program.evalAsync((PartialVars) null));
  }

  @Test
  public void eval_narrowedDynOverloadFallback_dispatchesByRuntimeValueType() throws Exception {
    CelCompiler compiler = CelCompilerFactory.standardCelCompilerBuilder().build();
    CelRuntime runtime = CelRuntimeFactory.legacyCelRuntimeBuilder().build();
    CelRuntime.Program program =
        runtime.createProgram(compiler.compile("-([1] + dyn([1.5]))[1]").getAst());

    Object result = program.eval();

    assertThat(result).isEqualTo(-1.5d);
  }

  @Test
  public void eval_standaloneBindingsWithDuplicateRuntimeArgTypes_throwsAmbiguousOverload()
      throws Exception {
    CelCompiler compiler =
        CelCompilerFactory.standardCelCompilerBuilder()
            .addFunctionDeclarations(
                newFunctionDeclaration(
                    "dup",
                    newGlobalOverload("dup_int", SimpleType.INT, SimpleType.INT),
                    newGlobalOverload("dup_str", SimpleType.INT, SimpleType.STRING)))
            .build();
    CelRuntime runtime =
        CelRuntimeFactory.legacyCelRuntimeBuilder()
            .addFunctionBindings(
                CelFunctionBinding.from("dup_int", Long.class, x -> x),
                CelFunctionBinding.from("dup_str", Long.class, x -> x))
            .build();
    CelRuntime.Program program = runtime.createProgram(compiler.compile("dup(dyn(1))").getAst());

    CelEvaluationException e = assertThrows(CelEvaluationException.class, program::eval);

    assertThat(e.getErrorCode()).isEqualTo(CelErrorCode.AMBIGUOUS_OVERLOAD);
  }
}
