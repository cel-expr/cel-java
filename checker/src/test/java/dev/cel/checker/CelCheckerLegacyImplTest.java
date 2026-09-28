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

package dev.cel.checker;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import dev.cel.expr.Type;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.protobuf.Duration;
import com.google.protobuf.FieldMask;
import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import dev.cel.checker.CelCheckerLegacyImpl.LegacyBridgeCombinedTypeProvider;
import dev.cel.checker.CelStandardDeclarations.StandardFunction;
import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.CelContainer;
import dev.cel.common.CelFunctionDecl;
import dev.cel.common.CelOptions;
import dev.cel.common.CelOverloadDecl;
import dev.cel.common.CelSource;
import dev.cel.common.CelValidationException;
import dev.cel.common.CelVarDecl;
import dev.cel.common.ast.CelExpr;
import dev.cel.common.types.CelKind;
import dev.cel.common.types.CelType;
import dev.cel.common.types.CelTypeProvider;
import dev.cel.common.types.EnumType;
import dev.cel.common.types.ListType;
import dev.cel.common.types.MapType;
import dev.cel.common.types.ProtoMessageType;
import dev.cel.common.types.SimpleType;
import dev.cel.common.types.StructType;
import dev.cel.common.types.StructTypeReference;
import dev.cel.common.types.TypeType;
import dev.cel.compiler.CelCompiler;
import dev.cel.compiler.CelCompilerFactory;
import dev.cel.expr.conformance.proto2.TestAllTypesExtensions;
import dev.cel.expr.conformance.proto2.TestAllTypesProto;
import dev.cel.expr.conformance.proto3.TestAllTypes;
import java.util.Optional;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(TestParameterInjector.class)
public class CelCheckerLegacyImplTest {

  @Test
  public void toCheckerBuilder_isNewInstance() {
    CelCheckerBuilder celCheckerBuilder = CelCompilerFactory.standardCelCheckerBuilder();
    CelCheckerLegacyImpl celChecker = (CelCheckerLegacyImpl) celCheckerBuilder.build();

    CelCheckerLegacyImpl.Builder newCheckerBuilder =
        (CelCheckerLegacyImpl.Builder) celChecker.toCheckerBuilder();

    assertThat(newCheckerBuilder).isNotEqualTo(celCheckerBuilder);
  }

  @Test
  public void toCheckerBuilder_isImmutable() {
    CelCheckerBuilder originalCheckerBuilder = CelCompilerFactory.standardCelCheckerBuilder();
    CelCheckerLegacyImpl celChecker = (CelCheckerLegacyImpl) originalCheckerBuilder.build();
    originalCheckerBuilder.addLibraries(new CelCheckerLibrary() {});

    CelCheckerLegacyImpl.Builder newCheckerBuilder =
        (CelCheckerLegacyImpl.Builder) celChecker.toCheckerBuilder();

    assertThat(newCheckerBuilder.checkerLibraries().build()).isEmpty();
  }

  @Test
  public void toCheckerBuilder_singularFields_copied() {
    CelStandardDeclarations subsetDecls =
        CelStandardDeclarations.newBuilder().includeFunctions(StandardFunction.BOOL).build();
    CelOptions celOptions = CelOptions.current().build();
    CelContainer celContainer = CelContainer.ofName("foo");
    CelType expectedResultType = SimpleType.BOOL;
    TypeProvider legacyTypeProvider = new DescriptorTypeProvider();
    CelTypeProvider customTypeProvider =
        new CelTypeProvider() {
          @Override
          public ImmutableList<CelType> types() {
            return ImmutableList.of();
          }

          @Override
          public Optional<CelType> findType(String typeName) {
            return Optional.empty();
          }
        };
    CelCheckerBuilder celCheckerBuilder =
        CelCompilerFactory.standardCelCheckerBuilder()
            .setOptions(celOptions)
            .setContainer(celContainer)
            .setResultType(expectedResultType)
            .setTypeProvider(legacyTypeProvider)
            .setTypeProvider(customTypeProvider)
            .setStandardEnvironmentEnabled(false)
            .setStandardDeclarations(subsetDecls);
    CelCheckerLegacyImpl celChecker = (CelCheckerLegacyImpl) celCheckerBuilder.build();

    CelCheckerLegacyImpl.Builder newCheckerBuilder =
        (CelCheckerLegacyImpl.Builder) celChecker.toCheckerBuilder();

    assertThat(newCheckerBuilder.standardDeclarations()).isEqualTo(subsetDecls);
    assertThat(newCheckerBuilder.options()).isEqualTo(celOptions);
    assertThat(newCheckerBuilder.container()).isEqualTo(celContainer);
    assertThat(newCheckerBuilder.customTypeProvider()).isEqualTo(legacyTypeProvider);
    assertThat(newCheckerBuilder.celTypeProvider()).isEqualTo(customTypeProvider);
  }

  @Test
  public void toCheckerBuilder_collectionProperties_copied() {
    CelCheckerBuilder celCheckerBuilder =
        CelCompilerFactory.standardCelCheckerBuilder()
            .addFunctionDeclarations(
                CelFunctionDecl.newFunctionDeclaration(
                    "test", CelOverloadDecl.newGlobalOverload("test_id", SimpleType.INT)))
            .addVarDeclarations(CelVarDecl.newVarDeclaration("ident", SimpleType.INT))
            .addMessageTypes(TestAllTypes.getDescriptor())
            .addFileTypes(TestAllTypes.getDescriptor().getFile())
            .addProtoTypeMasks(
                ProtoTypeMask.ofAllFields("cel.expr.conformance.proto3.TestAllTypes"))
            .addLibraries(new CelCheckerLibrary() {});
    CelCheckerLegacyImpl celChecker = (CelCheckerLegacyImpl) celCheckerBuilder.build();

    CelCheckerLegacyImpl.Builder newCheckerBuilder =
        (CelCheckerLegacyImpl.Builder) celChecker.toCheckerBuilder();

    assertThat(newCheckerBuilder.functionDecls().build()).hasSize(1);
    assertThat(newCheckerBuilder.identDecls().build()).hasSize(1);
    assertThat(newCheckerBuilder.protoTypeMasks().build()).hasSize(1);
    assertThat(newCheckerBuilder.fileTypes().build())
        .hasSize(1); // MessageTypes and FileTypes deduped into the same file descriptor
    assertThat(newCheckerBuilder.checkerLibraries().build()).hasSize(1);
  }

  @Test
  public void toCheckerBuilder_collectionProperties_areImmutable() {
    CelCheckerBuilder celCheckerBuilder = CelCompilerFactory.standardCelCheckerBuilder();
    CelCheckerLegacyImpl celChecker = (CelCheckerLegacyImpl) celCheckerBuilder.build();
    CelCheckerLegacyImpl.Builder newCheckerBuilder =
        (CelCheckerLegacyImpl.Builder) celChecker.toCheckerBuilder();

    // Mutate the original builder containing collections
    celCheckerBuilder.addFunctionDeclarations(
        CelFunctionDecl.newFunctionDeclaration(
            "test", CelOverloadDecl.newGlobalOverload("test_id", SimpleType.INT)));
    celCheckerBuilder.addVarDeclarations(CelVarDecl.newVarDeclaration("ident", SimpleType.INT));
    celCheckerBuilder.addMessageTypes(TestAllTypes.getDescriptor());
    celCheckerBuilder.addFileTypes(TestAllTypes.getDescriptor().getFile());
    celCheckerBuilder.addProtoTypeMasks(
        ProtoTypeMask.ofAllFields("cel.expr.conformance.proto3.TestAllTypes"));
    celCheckerBuilder.addLibraries(new CelCheckerLibrary() {});

    assertThat(newCheckerBuilder.functionDecls().build()).isEmpty();
    assertThat(newCheckerBuilder.identDecls().build()).isEmpty();
    assertThat(newCheckerBuilder.protoTypeMasks().build()).isEmpty();
    assertThat(newCheckerBuilder.messageTypes().build()).isEmpty();
    assertThat(newCheckerBuilder.fileTypes().build()).isEmpty();
    assertThat(newCheckerBuilder.checkerLibraries().build()).isEmpty();
  }

  @Test
  public void check_wellKnownTypeStructCreation_withLegacyTypeProvider_success() throws Exception {
    TypeProvider legacyTypeProvider =
        new DescriptorTypeProvider(ImmutableList.of(Duration.getDescriptor()));
    CelCompiler celCompiler =
        CelCompilerFactory.standardCelCompilerBuilder().setTypeProvider(legacyTypeProvider).build();

    CelAbstractSyntaxTree ast =
        celCompiler.compile("google.protobuf.Duration{seconds: 10, nanos: 20}").getAst();

    assertThat(ast.getResultType()).isEqualTo(SimpleType.DURATION);
  }

  @Test
  public void check_protoTypeMask_failsClosedWithLegacyTypeProvider() throws Exception {
    TypeProvider legacyTypeProvider =
        new DescriptorTypeProvider(ImmutableList.of(TestAllTypes.getDescriptor()));
    CelCompiler celCompiler =
        CelCompilerFactory.standardCelCompilerBuilder()
            .addMessageTypes(TestAllTypes.getDescriptor())
            .addProtoTypeMasks(
                ProtoTypeMask.of(
                    "cel.expr.conformance.proto3.TestAllTypes",
                    FieldMask.newBuilder().addPaths("single_int32").build()))
            .setTypeProvider(legacyTypeProvider)
            .build();

    CelValidationException e =
        assertThrows(
            CelValidationException.class,
            () ->
                celCompiler
                    .compile(
                        "cel.expr.conformance.proto3.TestAllTypes{single_int32: 1, single_int64:"
                            + " 2}")
                    .getAst());

    assertThat(e).hasMessageThat().contains("undefined field 'single_int64'");
  }

  @Test
  public void check_extensionField_withModernTypeProvider_success() throws Exception {
    CelChecker celChecker =
        CelCompilerFactory.standardCelCheckerBuilder()
            .addFileTypes(TestAllTypesProto.getDescriptor(), TestAllTypesExtensions.getDescriptor())
            .addVarDeclarations(
                CelVarDecl.newVarDeclaration(
                    "msg", StructTypeReference.create("cel.expr.conformance.proto2.TestAllTypes")))
            .build();
    CelAbstractSyntaxTree parsedAst =
        CelAbstractSyntaxTree.newParsedAst(
            CelExpr.ofSelect(
                2L,
                CelExpr.ofIdent(1L, "msg"),
                "cel.expr.conformance.proto2.int32_ext",
                /* isTestOnly= */ false),
            CelSource.newBuilder().build());

    CelAbstractSyntaxTree checkedAst = celChecker.check(parsedAst).getAst();

    assertThat(checkedAst.getResultType()).isEqualTo(SimpleType.INT);
  }

  @Test
  public void check_extensionField_withModernMessageAndLegacyExtensionProvider_success()
      throws Exception {
    TypeProvider legacyExtensionProvider =
        new DescriptorTypeProvider(
            ImmutableList.of(
                TestAllTypesProto.getDescriptor(), TestAllTypesExtensions.getDescriptor()));
    CelChecker celChecker =
        CelCompilerFactory.standardCelCheckerBuilder()
            .addMessageTypes(TestAllTypesExtensions.int32Ext.getDescriptor().getContainingType())
            .setTypeProvider(legacyExtensionProvider)
            .addVarDeclarations(
                CelVarDecl.newVarDeclaration(
                    "msg", StructTypeReference.create("cel.expr.conformance.proto2.TestAllTypes")))
            .build();
    CelAbstractSyntaxTree parsedAst =
        CelAbstractSyntaxTree.newParsedAst(
            CelExpr.ofSelect(
                2L,
                CelExpr.ofIdent(1L, "msg"),
                "cel.expr.conformance.proto2.int32_ext",
                /* isTestOnly= */ false),
            CelSource.newBuilder().build());

    CelAbstractSyntaxTree checkedAst = celChecker.check(parsedAst).getAst();

    assertThat(checkedAst.getResultType()).isEqualTo(SimpleType.INT);
  }

  @Test
  public void check_fieldAndExtensionSelection_withLegacyTypeProviderOnly_success(
      @TestParameter({"single_int64", "cel.expr.conformance.proto2.int32_ext"}) String fieldName)
      throws Exception {
    TypeProvider legacyTypeProvider =
        new DescriptorTypeProvider(
            ImmutableList.of(
                TestAllTypesProto.getDescriptor(), TestAllTypesExtensions.getDescriptor()));
    CelChecker celChecker =
        CelCompilerFactory.standardCelCheckerBuilder()
            .setTypeProvider(legacyTypeProvider)
            .addVarDeclarations(
                CelVarDecl.newVarDeclaration(
                    "msg", StructTypeReference.create("cel.expr.conformance.proto2.TestAllTypes")))
            .build();
    CelAbstractSyntaxTree parsedAst =
        CelAbstractSyntaxTree.newParsedAst(
            CelExpr.ofSelect(2L, CelExpr.ofIdent(1L, "msg"), fieldName, /* isTestOnly= */ false),
            CelSource.newBuilder().build());

    CelAbstractSyntaxTree checkedAst = celChecker.check(parsedAst).getAst();

    assertThat(checkedAst.getResultType()).isEqualTo(SimpleType.INT);
  }

  @Test
  public void check_undefinedFieldSelection_withLegacyTypeProviderOnly_throws() {
    TypeProvider legacyTypeProvider =
        new DescriptorTypeProvider(ImmutableList.of(TestAllTypes.getDescriptor()));
    CelCompiler celCompiler =
        CelCompilerFactory.standardCelCompilerBuilder()
            .setTypeProvider(legacyTypeProvider)
            .addVar("msg", StructTypeReference.create("cel.expr.conformance.proto3.TestAllTypes"))
            .build();

    CelValidationException e =
        assertThrows(
            CelValidationException.class,
            () -> celCompiler.compile("msg.undefined_field").getAst());

    assertThat(e).hasMessageThat().contains("undefined field 'undefined_field'");
  }

  @Test
  public void check_undeclaredMessageTypeFieldSelection_throws() {
    CelCompiler celCompiler =
        CelCompilerFactory.standardCelCompilerBuilder()
            .addVar("msg", StructTypeReference.create("cel.expr.conformance.proto3.TestAllTypes"))
            .build();

    CelValidationException e =
        assertThrows(
            CelValidationException.class, () -> celCompiler.compile("msg.single_int64").getAst());

    assertThat(e)
        .hasMessageThat()
        .contains(
            "Message type resolution failure while referencing field 'single_int64'. Ensure that"
                + " the descriptor for type 'cel.expr.conformance.proto3.TestAllTypes' was added to"
                + " the environment");
  }

  @Test
  public void check_fieldSelection_withLegacyTypeProvider_success() throws Exception {
    TypeProvider legacyTypeProvider =
        new DescriptorTypeProvider(ImmutableList.of(TestAllTypes.getDescriptor()));
    CelCompiler celCompiler =
        CelCompilerFactory.standardCelCompilerBuilder()
            .setTypeProvider(legacyTypeProvider)
            .addVar("msg", StructTypeReference.create("cel.expr.conformance.proto3.TestAllTypes"))
            .build();

    CelAbstractSyntaxTree ast = celCompiler.compile("msg.single_int64").getAst();

    assertThat(ast.getResultType()).isEqualTo(SimpleType.INT);
  }

  @Test
  public void check_undefinedFieldSelection_withLegacyTypeProvider_throws() {
    TypeProvider legacyTypeProvider =
        new DescriptorTypeProvider(ImmutableList.of(TestAllTypes.getDescriptor()));
    CelCompiler celCompiler =
        CelCompilerFactory.standardCelCompilerBuilder()
            .setTypeProvider(legacyTypeProvider)
            .addVar("msg", StructTypeReference.create("cel.expr.conformance.proto3.TestAllTypes"))
            .build();

    CelValidationException e =
        assertThrows(
            CelValidationException.class, () -> celCompiler.compile("msg.undefined").getAst());

    assertThat(e).hasMessageThat().contains("undefined field 'undefined'");
  }

  @Test
  public void check_messageCreation_withLegacyTypeProvider_success() throws Exception {
    TypeProvider legacyTypeProvider =
        new DescriptorTypeProvider(ImmutableList.of(TestAllTypes.getDescriptor()));
    CelCompiler celCompiler =
        CelCompilerFactory.standardCelCompilerBuilder().setTypeProvider(legacyTypeProvider).build();

    CelType resultType =
        celCompiler
            .compile("cel.expr.conformance.proto3.TestAllTypes{single_int64: 2}")
            .getAst()
            .getResultType();

    // Struct creation resolves to the type handed back by the type provider, which inherits
    // identity equality, so the assertion is on kind and name rather than on an equal instance.
    assertThat(resultType.kind()).isEqualTo(CelKind.STRUCT);
    assertThat(resultType.name()).isEqualTo("cel.expr.conformance.proto3.TestAllTypes");
  }

  @Test
  public void lookupEnumValue_legacyTypeProvider_success(
      @TestParameter({
            "cel.expr.conformance.proto3.TestAllTypes.NestedEnum.BAZ == 2",
            ".cel.expr.conformance.proto3.TestAllTypes.NestedEnum.BAZ == 2"
          })
          String expr)
      throws Exception {
    TypeProvider legacyTypeProvider =
        new DescriptorTypeProvider(ImmutableList.of(TestAllTypes.getDescriptor()));
    CelCompiler celCompiler =
        CelCompilerFactory.standardCelCompilerBuilder().setTypeProvider(legacyTypeProvider).build();

    CelAbstractSyntaxTree ast = celCompiler.compile(expr).getAst();

    assertThat(ast.getResultType()).isEqualTo(SimpleType.BOOL);
  }

  @Test
  public void lookupEnumValue_modernTypeProvider_success(
      @TestParameter({
            "cel.expr.conformance.proto3.TestAllTypes.NestedEnum.BAZ == 2",
            ".cel.expr.conformance.proto3.TestAllTypes.NestedEnum.BAZ == 2"
          })
          String expr)
      throws Exception {
    CelCompiler celCompiler =
        CelCompilerFactory.standardCelCompilerBuilder()
            .addMessageTypes(TestAllTypes.getDescriptor())
            .build();

    CelAbstractSyntaxTree ast = celCompiler.compile(expr).getAst();

    assertThat(ast.getResultType()).isEqualTo(SimpleType.BOOL);
  }

  @Test
  public void lookupEnumValue_dynamicTypeProviderKeyedByValueName_success() throws Exception {
    CelTypeProvider dynamicEnumProvider =
        new CelTypeProvider() {
          @Override
          public ImmutableList<CelType> types() {
            return ImmutableList.of();
          }

          @Override
          public Optional<CelType> findType(String typeName) {
            if (typeName.equals("custom.Enum.VALUE")) {
              return Optional.of(EnumType.create("custom.Enum", ImmutableMap.of("VALUE", 42)));
            }
            return Optional.empty();
          }
        };
    CelCompiler celCompiler =
        CelCompilerFactory.standardCelCompilerBuilder()
            .addMessageTypes(TestAllTypes.getDescriptor())
            .setTypeProvider(dynamicEnumProvider)
            .build();

    CelAbstractSyntaxTree ast = celCompiler.compile("custom.Enum.VALUE == 42").getAst();

    assertThat(ast.getResultType()).isEqualTo(SimpleType.BOOL);
  }

  @Test
  public void check_customTypeProviderReturningPreWrappedType_doesNotDoubleWrap() throws Exception {
    TypeType preWrappedType = TypeType.create(StructTypeReference.create("custom.MyType"));
    CelTypeProvider customTypeProvider =
        new CelTypeProvider() {
          @Override
          public ImmutableList<CelType> types() {
            return ImmutableList.of();
          }

          @Override
          public Optional<CelType> findType(String typeName) {
            return typeName.equals("custom.MyType")
                ? Optional.of(preWrappedType)
                : Optional.empty();
          }
        };
    CelCompiler celCompiler =
        CelCompilerFactory.standardCelCompilerBuilder().setTypeProvider(customTypeProvider).build();

    CelAbstractSyntaxTree ast = celCompiler.compile("custom.MyType").getAst();

    assertThat(ast.getResultType()).isEqualTo(preWrappedType);
  }

  @Test
  public void check_legacyTypeProviderDeclaringNonStructType_success() throws Exception {
    TypeProvider customLegacyProvider =
        new TypeProvider() {
          @Override
          public Type lookupType(String typeName) {
            if (typeName.equals("user.org_units")) {
              return Type.newBuilder()
                  .setListType(
                      Type.ListType.newBuilder()
                          .setElemType(Type.newBuilder().setPrimitive(Type.PrimitiveType.INT64)))
                  .build();
            }
            return null;
          }

          @Override
          public Integer lookupEnumValue(String enumName) {
            return null;
          }

          @Override
          public FieldType lookupFieldType(Type type, String fieldName) {
            return null;
          }
        };
    CelCompiler celCompiler =
        CelCompilerFactory.standardCelCompilerBuilder()
            .setTypeProvider(customLegacyProvider)
            .build();

    CelAbstractSyntaxTree ast = celCompiler.compile("size(user.org_units) == 0").getAst();

    assertThat(ast.getResultType()).isEqualTo(SimpleType.BOOL);
  }

  private static CelCompiler newCompilerWithUnenumerableJsonNameMessage() {
    ProtoMessageType modernUnenumerable =
        ProtoMessageType.createWithUnenumerableFields(
            "custom.UnenumerableMessage",
            fieldName ->
                fieldName.equals("myField") || fieldName.equals("my_field")
                    ? Optional.of(SimpleType.INT)
                    : Optional.empty(),
            extensionName -> Optional.empty(),
            "myField"::equals);
    CelTypeProvider modernProvider =
        new CelTypeProvider() {
          @Override
          public ImmutableList<CelType> types() {
            return ImmutableList.of();
          }

          @Override
          public Optional<CelType> findType(String typeName) {
            return typeName.equals("custom.UnenumerableMessage")
                ? Optional.of(modernUnenumerable)
                : Optional.empty();
          }
        };
    TypeProvider legacyTypeProvider =
        new TypeProvider() {
          @Override
          public Type lookupType(String typeName) {
            return null;
          }

          @Override
          public Integer lookupEnumValue(String enumName) {
            return null;
          }

          @Override
          public FieldType lookupFieldType(Type type, String fieldName) {
            return null;
          }
        };
    return CelCompilerFactory.standardCelCompilerBuilder()
        .setTypeProvider(modernProvider)
        .setTypeProvider(legacyTypeProvider)
        .addVar("msg", StructTypeReference.create("custom.UnenumerableMessage"))
        .build();
  }

  @Test
  public void check_unenumerableMessageType_withJsonName_addsJsonNameExtension() throws Exception {
    CelCompiler celCompiler = newCompilerWithUnenumerableJsonNameMessage();

    CelAbstractSyntaxTree ast = celCompiler.compile("msg.myField == 1").getAst();

    assertThat(ast.getResultType()).isEqualTo(SimpleType.BOOL);
    assertThat(ast.getSource().getExtensions())
        .contains(
            CelSource.Extension.create(
                "json_name",
                CelSource.Extension.Version.of(1, 1),
                CelSource.Extension.Component.COMPONENT_RUNTIME));
  }

  @Test
  public void check_unenumerableMessageType_withoutJsonName_doesNotAddExtension() throws Exception {
    CelCompiler celCompiler = newCompilerWithUnenumerableJsonNameMessage();

    CelAbstractSyntaxTree nonJsonAst = celCompiler.compile("msg.my_field == 1").getAst();

    assertThat(nonJsonAst.getResultType()).isEqualTo(SimpleType.BOOL);
    assertThat(nonJsonAst.getSource().getExtensions()).isEmpty();
  }

  @Test
  public void check_extensionField_withUnenumerableModernMessageAndLegacyExtensionProvider_success()
      throws Exception {
    ProtoMessageType unenumerableMessage =
        ProtoMessageType.createWithUnenumerableFields(
            "cel.expr.conformance.proto2.TestAllTypes",
            fieldName -> Optional.empty(),
            extensionName -> Optional.empty(),
            unused -> false);
    CelTypeProvider modernProvider =
        new CelTypeProvider() {
          @Override
          public ImmutableList<CelType> types() {
            return ImmutableList.of();
          }

          @Override
          public Optional<CelType> findType(String typeName) {
            return typeName.equals("cel.expr.conformance.proto2.TestAllTypes")
                ? Optional.of(unenumerableMessage)
                : Optional.empty();
          }
        };
    TypeProvider legacyExtensionProvider =
        new DescriptorTypeProvider(
            ImmutableList.of(
                TestAllTypesProto.getDescriptor(), TestAllTypesExtensions.getDescriptor()));
    CelChecker celChecker =
        CelCompilerFactory.standardCelCheckerBuilder()
            .setTypeProvider(modernProvider)
            .setTypeProvider(legacyExtensionProvider)
            .addVarDeclarations(
                CelVarDecl.newVarDeclaration(
                    "msg", StructTypeReference.create("cel.expr.conformance.proto2.TestAllTypes")))
            .build();
    CelAbstractSyntaxTree parsedAst =
        CelAbstractSyntaxTree.newParsedAst(
            CelExpr.ofSelect(
                2L,
                CelExpr.ofIdent(1L, "msg"),
                "cel.expr.conformance.proto2.int32_ext",
                /* isTestOnly= */ false),
            CelSource.newBuilder().build());

    CelAbstractSyntaxTree checkedAst = celChecker.check(parsedAst).getAst();

    assertThat(checkedAst.getResultType()).isEqualTo(SimpleType.INT);
  }

  @Test
  public void combinedTypeProvider_enumerableModernMessage_preservesFieldNames() {
    ProtoMessageType modernMessage =
        ProtoMessageType.create(
            "cel.expr.conformance.proto3.TestAllTypes",
            ImmutableSet.of("single_int32", "single_int64"),
            fieldName -> Optional.of(SimpleType.INT),
            extensionName -> Optional.empty(),
            /* jsonNameResolver= */ fieldName -> false);
    CelTypeProvider modernProvider =
        new CelTypeProvider() {
          @Override
          public ImmutableList<CelType> types() {
            return ImmutableList.of();
          }

          @Override
          public Optional<CelType> findType(String typeName) {
            return typeName.equals("cel.expr.conformance.proto3.TestAllTypes")
                ? Optional.of(modernMessage)
                : Optional.empty();
          }
        };
    LegacyTypeProviderBridge legacyBridge =
        new LegacyTypeProviderBridge(
            new DescriptorTypeProvider(ImmutableList.of(TestAllTypes.getDescriptor())));
    LegacyBridgeCombinedTypeProvider combinedProvider =
        new LegacyBridgeCombinedTypeProvider(modernProvider, legacyBridge);

    Optional<CelType> resolvedType =
        combinedProvider.findType("cel.expr.conformance.proto3.TestAllTypes");

    assertThat(
            resolvedType
                .filter(t -> t instanceof ProtoMessageType)
                .map(t -> ((ProtoMessageType) t).fieldNames()))
        .hasValue(ImmutableSet.of("single_int32", "single_int64"));
    assertThat(
            resolvedType
                .filter(t -> t instanceof ProtoMessageType)
                .flatMap(t -> ((ProtoMessageType) t).findField("single_int32"))
                .map(StructType.Field::type))
        .hasValue(SimpleType.INT);
  }

  @Test
  public void combinedTypeProvider_unenumerableModernMessage_fieldNamesThrows() {
    ProtoMessageType modernMessage =
        ProtoMessageType.createWithUnenumerableFields(
            "cel.expr.conformance.proto3.TestAllTypes",
            fieldName -> Optional.empty(),
            extensionName -> Optional.empty(),
            fieldName -> false);
    CelTypeProvider modernProvider =
        new CelTypeProvider() {
          @Override
          public ImmutableList<CelType> types() {
            return ImmutableList.of();
          }

          @Override
          public Optional<CelType> findType(String typeName) {
            return typeName.equals("cel.expr.conformance.proto3.TestAllTypes")
                ? Optional.of(modernMessage)
                : Optional.empty();
          }
        };
    LegacyTypeProviderBridge legacyBridge =
        new LegacyTypeProviderBridge(
            new DescriptorTypeProvider(ImmutableList.of(TestAllTypes.getDescriptor())));
    LegacyBridgeCombinedTypeProvider combinedProvider =
        new LegacyBridgeCombinedTypeProvider(modernProvider, legacyBridge);

    Optional<CelType> resolvedType =
        combinedProvider.findType("cel.expr.conformance.proto3.TestAllTypes");

    assertThat(resolvedType.map(CelType::kind)).hasValue(CelKind.STRUCT);
    ProtoMessageType protoMessageType = (ProtoMessageType) resolvedType.get();
    IllegalStateException thrown =
        assertThrows(IllegalStateException.class, protoMessageType::fieldNames);
    assertThat(thrown).hasMessageThat().contains("cannot be enumerated");
    assertThrows(IllegalStateException.class, protoMessageType::fields);
  }

  @Test
  public void combinedTypeProvider_unenumerableModernMessage_delegatesJsonNameResolverToModern() {
    ProtoMessageType modernMessage =
        ProtoMessageType.createWithUnenumerableFields(
            "cel.expr.conformance.proto3.TestAllTypes",
            fieldName ->
                fieldName.equals("modernField") ? Optional.of(SimpleType.STRING) : Optional.empty(),
            extensionName -> Optional.empty(),
            "modernJsonField"::equals);
    CelTypeProvider modernProvider =
        new CelTypeProvider() {
          @Override
          public ImmutableList<CelType> types() {
            return ImmutableList.of();
          }

          @Override
          public Optional<CelType> findType(String typeName) {
            return typeName.equals("cel.expr.conformance.proto3.TestAllTypes")
                ? Optional.of(modernMessage)
                : Optional.empty();
          }
        };
    LegacyTypeProviderBridge legacyBridge =
        new LegacyTypeProviderBridge(
            new DescriptorTypeProvider(ImmutableList.of(TestAllTypes.getDescriptor())));
    LegacyBridgeCombinedTypeProvider combinedProvider =
        new LegacyBridgeCombinedTypeProvider(modernProvider, legacyBridge);

    Optional<CelType> resolvedType =
        combinedProvider.findType("cel.expr.conformance.proto3.TestAllTypes");

    assertThat(
            resolvedType
                .filter(t -> t instanceof ProtoMessageType)
                .flatMap(t -> ((ProtoMessageType) t).findField("modernField"))
                .map(StructType.Field::type))
        .hasValue(SimpleType.STRING);
    assertThat(
            resolvedType
                .filter(t -> t instanceof ProtoMessageType)
                .map(t -> ((ProtoMessageType) t).isJsonName("modernJsonField")))
        .hasValue(true);
    assertThat(
            resolvedType
                .filter(t -> t instanceof ProtoMessageType)
                .map(t -> ((ProtoMessageType) t).isJsonName("nonExistent")))
        .hasValue(false);
  }

  @Test
  public void combinedTypeProvider_modernExtensionTakesPrecedenceOverLegacy() {
    ProtoMessageType modernMessage =
        ProtoMessageType.createWithUnenumerableFields(
            "cel.expr.conformance.proto2.TestAllTypes",
            fieldName -> Optional.empty(),
            extensionName ->
                extensionName.equals("cel.expr.conformance.proto2.int32_ext")
                    ? Optional.of(SimpleType.STRING)
                    : Optional.empty(),
            fieldName -> false);
    CelTypeProvider modernProvider =
        new CelTypeProvider() {
          @Override
          public ImmutableList<CelType> types() {
            return ImmutableList.of();
          }

          @Override
          public Optional<CelType> findType(String typeName) {
            return typeName.equals("cel.expr.conformance.proto2.TestAllTypes")
                ? Optional.of(modernMessage)
                : Optional.empty();
          }
        };
    LegacyTypeProviderBridge legacyBridge =
        new LegacyTypeProviderBridge(
            new DescriptorTypeProvider(
                ImmutableList.of(
                    TestAllTypesProto.getDescriptor(), TestAllTypesExtensions.getDescriptor())));
    LegacyBridgeCombinedTypeProvider combinedProvider =
        new LegacyBridgeCombinedTypeProvider(modernProvider, legacyBridge);

    Optional<CelType> resolvedType =
        combinedProvider.findType("cel.expr.conformance.proto2.TestAllTypes");

    assertThat(
            resolvedType
                .filter(t -> t instanceof ProtoMessageType)
                .map(t -> (ProtoMessageType) t)
                .flatMap(t -> t.findExtension("cel.expr.conformance.proto2.int32_ext"))
                .map(ProtoMessageType.Extension::type))
        .hasValue(SimpleType.STRING);
  }

  @Test
  public void check_regularField_withModernMessageAndLegacyTypeProvider_success() throws Exception {
    TypeProvider legacyTypeProvider =
        new DescriptorTypeProvider(ImmutableList.of(TestAllTypes.getDescriptor()));
    CelCompiler celCompiler =
        CelCompilerFactory.standardCelCompilerBuilder()
            .addMessageTypes(TestAllTypes.getDescriptor())
            .setTypeProvider(legacyTypeProvider)
            .addVar("msg", StructTypeReference.create("cel.expr.conformance.proto3.TestAllTypes"))
            .build();

    CelAbstractSyntaxTree ast = celCompiler.compile("msg.single_int32 == 1").getAst();

    assertThat(ast.getResultType()).isEqualTo(SimpleType.BOOL);
  }

  private enum FieldTypeTestCase {
    REPEATED_PRIMITIVE("msg.repeated_int64", ListType.create(SimpleType.INT)),
    MAP_PRIMITIVE("msg.map_string_string", MapType.create(SimpleType.STRING, SimpleType.STRING)),
    SINGLE_ENUM("msg.single_nested_enum", SimpleType.INT),
    REPEATED_ENUM("msg.repeated_nested_enum", ListType.create(SimpleType.INT)),
    MAP_ENUM("msg.map_bool_enum", MapType.create(SimpleType.BOOL, SimpleType.INT)),
    SINGLE_MESSAGE(
        "msg.single_nested_message",
        StructTypeReference.create("cel.expr.conformance.proto3.TestAllTypes.NestedMessage")),
    REPEATED_MESSAGE(
        "msg.repeated_nested_message",
        ListType.create(
            StructTypeReference.create("cel.expr.conformance.proto3.TestAllTypes.NestedMessage"))),
    MAP_MESSAGE(
        "msg.map_bool_message",
        MapType.create(
            SimpleType.BOOL,
            StructTypeReference.create("cel.expr.conformance.proto3.TestAllTypes.NestedMessage")));

    private final String expr;
    private final CelType expectedType;

    FieldTypeTestCase(String expr, CelType expectedType) {
      this.expr = expr;
      this.expectedType = expectedType;
    }
  }

  @Test
  public void check_enumAndMessageFields_retainsIntAndStructTypeReference(
      @TestParameter FieldTypeTestCase testCase) throws Exception {
    CelCompiler celCompiler =
        CelCompilerFactory.standardCelCompilerBuilder()
            .addMessageTypes(TestAllTypes.getDescriptor())
            .addVar("msg", StructTypeReference.create("cel.expr.conformance.proto3.TestAllTypes"))
            .build();

    CelAbstractSyntaxTree ast = celCompiler.compile(testCase.expr).getAst();

    assertThat(ast.getResultType()).isEqualTo(testCase.expectedType);
  }
}
