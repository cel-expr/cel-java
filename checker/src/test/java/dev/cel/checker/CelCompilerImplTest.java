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

import com.google.common.collect.ImmutableList;
import com.google.common.primitives.UnsignedLong;
import com.google.protobuf.Duration;
import com.google.protobuf.Timestamp;
import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import com.google.testing.junit.testparameterinjector.TestParameterValuesProvider;
import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.CelFunctionDecl;
import dev.cel.common.CelOverloadDecl;
import dev.cel.common.CelValidationException;
import dev.cel.common.ast.CelConstant;
import dev.cel.common.ast.CelReference;
import dev.cel.common.types.CelType;
import dev.cel.common.types.SimpleType;
import dev.cel.common.values.CelByteString;
import dev.cel.common.values.NullValue;
import dev.cel.compiler.CelCompiler;
import dev.cel.compiler.CelCompilerBuilder;
import dev.cel.compiler.CelCompilerFactory;
import dev.cel.compiler.CelCompilerImpl;
import dev.cel.parser.CelUnparserFactory;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(TestParameterInjector.class)
public class CelCompilerImplTest {

  @Test
  public void toCompilerBuilder_isImmutable() {
    CelCompilerBuilder celCompilerBuilder = CelCompilerFactory.standardCelCompilerBuilder();
    CelCompilerImpl celCompiler = (CelCompilerImpl) celCompilerBuilder.build();
    celCompilerBuilder.addFunctionDeclarations(
        CelFunctionDecl.newFunctionDeclaration(
            "test", CelOverloadDecl.newGlobalOverload("test_id", SimpleType.INT)));

    CelCompilerImpl.Builder newCompilerBuilder =
        (CelCompilerImpl.Builder) celCompiler.toCompilerBuilder();

    assertThat(newCompilerBuilder).isNotEqualTo(celCompilerBuilder);
  }

  @Test
  public void addConstant_constantsInlined(@TestParameter ConstantTestCase testCase)
      throws Exception {
    CelCompiler compiler =
        CelCompilerFactory.standardCelCompilerBuilder()
            .addConstant("const_ident", testCase.constant)
            .build();

    CelAbstractSyntaxTree ast = compiler.compile("const_ident").getAst();

    String unparsed = CelUnparserFactory.newUnparser().unparse(ast);
    assertThat(unparsed).isEqualTo(testCase.unparsed);
    assertThat(ast.getResultType()).isEqualTo(testCase.celType);
  }

  @Test
  public void addConstant_unsupportedConstants_throws(
      @TestParameter(valuesProvider = UnsupportedConstantsProvider.class)
          CelConstant unsupportedConstant) {
    CelCompilerBuilder builder = CelCompilerFactory.standardCelCompilerBuilder();

    IllegalArgumentException e =
        assertThrows(
            IllegalArgumentException.class,
            () -> builder.addConstant("const_ident", unsupportedConstant));
    assertThat(e).hasMessageThat().contains("Unsupported constant");
  }

  @Test
  public void addConstant_collidesWithVariables_throws() {
    CelValidationException e =
        assertThrows(
            CelValidationException.class,
            () ->
                CelCompilerFactory.standardCelCompilerBuilder()
                    .addVar("const_ident", SimpleType.INT)
                    .addConstant("const_ident", CelConstant.ofValue(2L))
                    .build()
                    .compile("const_ident")
                    .getAst());
    assertThat(e).hasMessageThat().contains("overlapping declaration name 'const_ident'");
  }

  @Test
  public void addConstant_withinAnExpression_containsValidAstMetadata() throws Exception {
    CelCompiler compiler =
        CelCompilerFactory.standardCelCompilerBuilder()
            .addConstant("const_ident", CelConstant.ofValue(42L))
            .build();

    CelAbstractSyntaxTree ast = compiler.compile("const_ident + 2").getAst();

    String unparsed = CelUnparserFactory.newUnparser().unparse(ast);
    assertThat(unparsed).isEqualTo("42 + 2");
    assertThat(ast.getReferenceMap())
        .containsExactly(2L, CelReference.newBuilder().addOverloadIds("add_int64").build());
    assertThat(ast.getTypeMap())
        .containsExactly(1L, SimpleType.INT, 2L, SimpleType.INT, 3L, SimpleType.INT);
  }

  private enum ConstantTestCase {
    BOOL(CelConstant.ofValue(true), SimpleType.BOOL, "true"),
    INT(CelConstant.ofValue(2L), SimpleType.INT, "2"),
    UINT(CelConstant.ofValue(UnsignedLong.valueOf(3L)), SimpleType.UINT, "3u"),
    DOUBLE(CelConstant.ofValue(2.5d), SimpleType.DOUBLE, "2.5"),
    STRING(CelConstant.ofValue("hello"), SimpleType.STRING, "\"hello\""),
    BYTES(
        CelConstant.ofValue(CelByteString.copyFromUtf8("hello")),
        SimpleType.BYTES,
        "b\"\\150\\145\\154\\154\\157\""),
    NULL(CelConstant.ofValue(NullValue.NULL_VALUE), SimpleType.NULL_TYPE, "null");

    private final CelConstant constant;
    private final CelType celType;
    private final String unparsed;

    ConstantTestCase(CelConstant constant, CelType celType, String unparsed) {
      this.constant = constant;
      this.celType = celType;
      this.unparsed = unparsed;
    }
  }

  private static final class UnsupportedConstantsProvider extends TestParameterValuesProvider {
    @Override
    protected ImmutableList<?> provideValues(Context context) {
      return ImmutableList.of(
          CelConstant.ofNotSet(),
          CelConstant.ofValue(Timestamp.getDefaultInstance()),
          CelConstant.ofValue(Duration.getDefaultInstance()));
    }
  }
}
