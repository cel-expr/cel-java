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

import static com.google.common.truth.Truth.assertThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertThrows;

import com.google.common.base.Ascii;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.io.Resources;
import com.google.rpc.context.AttributeContext;
import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import dev.cel.bundle.CelEnvironment.ContextVariable;
import dev.cel.bundle.CelEnvironment.ExtensionConfig;
import dev.cel.bundle.CelEnvironment.FunctionDecl;
import dev.cel.bundle.CelEnvironment.LibrarySubset;
import dev.cel.bundle.CelEnvironment.LibrarySubset.FunctionSelector;
import dev.cel.bundle.CelEnvironment.OverloadDecl;
import dev.cel.bundle.CelEnvironment.TypeDecl;
import dev.cel.bundle.CelEnvironment.VariableDecl;
import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.CelContainer;
import dev.cel.common.CelOptions;
import dev.cel.common.CelSource;
import dev.cel.common.ast.CelExpr;
import dev.cel.common.types.SimpleType;
import dev.cel.parser.CelUnparserFactory;
import dev.cel.runtime.CelEvaluationListener;
import dev.cel.runtime.CelFunctionBinding;
import dev.cel.runtime.CelLateFunctionBindings;
import java.io.IOException;
import java.net.URL;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(TestParameterInjector.class)
public final class CelEnvironmentYamlParserTest {

  private static final Cel CEL_WITH_MESSAGE_TYPES =
      CelFactory.standardCelBuilder()
          .addMessageTypes(AttributeContext.Request.getDescriptor())
          .build();

  private static final CelEnvironmentYamlParser ENVIRONMENT_PARSER =
      CelEnvironmentYamlParser.newInstance();

  @Test
  public void environment_setEmpty() throws Exception {
    assertThrows(CelEnvironmentException.class, () -> ENVIRONMENT_PARSER.parse(""));
  }

  @Test
  public void environment_setBasicProperties() throws Exception {
    String yamlConfig = "name: hello\n" + "description: empty\n" + "container: pb.pkg\n";

    CelEnvironment environment = ENVIRONMENT_PARSER.parse(yamlConfig);

    assertThat(environment)
        .isEqualTo(
            CelEnvironment.newBuilder()
                .setSource(environment.source().get())
                .setName("hello")
                .setDescription("empty")
                .setContainer("pb.pkg")
                .build());
  }

  @Test
  public void environment_setFeatures() throws Exception {
    String yamlConfig =
        "name: hello\n"
            + "description: empty\n"
            + "features:\n"
            + "  - name: 'cel.feature.macro_call_tracking'\n"
            + "    enabled: true\n"
            + "  - name: 'cel.feature.backtick_escape_syntax'\n"
            + "    enabled: false";

    CelEnvironment environment = ENVIRONMENT_PARSER.parse(yamlConfig);

    assertThat(environment)
        .isEqualTo(
            CelEnvironment.newBuilder()
                .setSource(environment.source().get())
                .setName("hello")
                .setDescription("empty")
                .setFeatures(
                    ImmutableSet.of(
                        CelEnvironment.FeatureFlag.create("cel.feature.macro_call_tracking", true),
                        CelEnvironment.FeatureFlag.create(
                            "cel.feature.backtick_escape_syntax", false)))
                .build());
  }

  @Test
  public void environment_setLimits() throws Exception {
    String yamlConfig =
        "name: hello\n"
            + "description: empty\n"
            + "limits:\n"
            + "  - name: 'cel.limit.expression_code_points'\n"
            + "    value: 1000\n"
            + "  - name: 'cel.limit.parse_error_recovery'\n"
            + "    value: 10\n"
            + "  - name: 'cel.limit.parse_recursion_depth'\n"
            + "    value: 7";

    CelEnvironment environment = ENVIRONMENT_PARSER.parse(yamlConfig);

    assertThat(environment)
        .isEqualTo(
            CelEnvironment.newBuilder()
                .setSource(environment.source().get())
                .setName("hello")
                .setDescription("empty")
                .setLimits(
                    ImmutableSet.of(
                        CelEnvironment.Limit.create("cel.limit.expression_code_points", 1000),
                        CelEnvironment.Limit.create("cel.limit.parse_error_recovery", 10),
                        CelEnvironment.Limit.create("cel.limit.parse_recursion_depth", 7)))
                .build());
  }

  @Test
  public void environment_setExtensions() throws Exception {
    String yamlConfig =
        "extensions:\n"
            + "  - name: 'bindings'\n"
            + "  - name: 'encoders'\n"
            + "  - name: 'lists'\n"
            + "  - name: 'math'\n"
            + "  - name: 'optional'\n"
            + "  - name: 'protos'\n"
            + "  - name: 'sets'\n"
            + "  - name: 'strings'\n"
            + "    version: 1";

    CelEnvironment environment = ENVIRONMENT_PARSER.parse(yamlConfig);

    assertThat(environment)
        .isEqualTo(
            CelEnvironment.newBuilder()
                .setSource(environment.source().get())
                .addExtensions(
                    ImmutableSet.of(
                        ExtensionConfig.of("bindings"),
                        ExtensionConfig.of("encoders"),
                        ExtensionConfig.of("lists"),
                        ExtensionConfig.of("math"),
                        ExtensionConfig.of("optional"),
                        ExtensionConfig.of("protos"),
                        ExtensionConfig.of("sets"),
                        ExtensionConfig.of("strings", 1)))
                .build());
    assertThat(environment.extend(CEL_WITH_MESSAGE_TYPES, CelOptions.DEFAULT)).isNotNull();
  }

  @Test
  public void environment_setExtensionVersionToLatest() throws Exception {
    String yamlConfig =
        "extensions:\n" //
            + "  - name: 'bindings'\n" //
            + "    version: latest";

    CelEnvironment environment = ENVIRONMENT_PARSER.parse(yamlConfig);

    assertThat(environment)
        .isEqualTo(
            CelEnvironment.newBuilder()
                .setSource(environment.source().get())
                .addExtensions(ImmutableSet.of(ExtensionConfig.of("bindings", Integer.MAX_VALUE)))
                .build());
    assertThat(environment.extend(CEL_WITH_MESSAGE_TYPES, CelOptions.DEFAULT)).isNotNull();
  }

  @Test
  public void environment_setExtensionVersionToInvalidValue() throws Exception {
    String yamlConfig =
        "extensions:\n" //
            + "  - name: 'bindings'\n" //
            + "    version: invalid";

    CelEnvironmentException e =
        assertThrows(CelEnvironmentException.class, () -> ENVIRONMENT_PARSER.parse(yamlConfig));
    assertThat(e)
        .hasMessageThat()
        .contains(
            "ERROR: <input>:3:5: Unsupported version tag: version\n"
                + " |     version: invalid\n"
                + " | ....^");
  }

  @Test
  public void environment_setFunctions() throws Exception {
    String yamlConfig =
        "functions:\n"
            + "  - name: 'coalesce'\n"
            + "    overloads:\n"
            + "      - id: 'null_coalesce_int'\n"
            + "        target:\n"
            + "          type_name: 'null_type'\n"
            + "        args:\n"
            + "          - type_name: 'int'\n"
            + "        return:\n"
            + "          type_name: 'int'\n"
            + "      - id: 'coalesce_null_int'\n"
            + "        args:\n"
            + "          - type_name: 'null_type'\n"
            + "          - type_name: 'int'\n"
            + "        return:\n"
            + "          type_name: 'int'          \n"
            + "      - id: 'int_coalesce_int'\n"
            + "        target: \n"
            + "          type_name: 'int'\n"
            + "        args:\n"
            + "          - type_name: 'int'\n"
            + "        return: \n"
            + "          type_name: 'int'\n"
            + "      - id: 'optional_T_coalesce_T'\n"
            + "        target: \n"
            + "          type_name: 'optional_type'\n"
            + "          params:\n"
            + "            - type_name: 'T'\n"
            + "              is_type_param: true\n"
            + "        args:\n"
            + "          - type_name: 'T'\n"
            + "            is_type_param: true\n"
            + "        return: \n"
            + "          type_name: 'T'\n"
            + "          is_type_param: true";

    CelEnvironment environment = ENVIRONMENT_PARSER.parse(yamlConfig);

    assertThat(environment)
        .isEqualTo(
            CelEnvironment.newBuilder()
                .setSource(environment.source().get())
                .setFunctions(
                    ImmutableSet.of(
                        FunctionDecl.create(
                            "coalesce",
                            ImmutableSet.of(
                                OverloadDecl.newBuilder()
                                    .setId("null_coalesce_int")
                                    .setTarget(TypeDecl.create("null_type"))
                                    .addArguments(TypeDecl.create("int"))
                                    .setReturnType(TypeDecl.create("int"))
                                    .build(),
                                OverloadDecl.newBuilder()
                                    .setId("coalesce_null_int")
                                    .addArguments(
                                        TypeDecl.create("null_type"), TypeDecl.create("int"))
                                    .setReturnType(TypeDecl.create("int"))
                                    .build(),
                                OverloadDecl.newBuilder()
                                    .setId("int_coalesce_int")
                                    .setTarget(TypeDecl.create("int"))
                                    .addArguments(TypeDecl.create("int"))
                                    .setReturnType(TypeDecl.create("int"))
                                    .build(),
                                OverloadDecl.newBuilder()
                                    .setId("optional_T_coalesce_T")
                                    .setTarget(
                                        TypeDecl.newBuilder()
                                            .setName("optional_type")
                                            .addParams(
                                                TypeDecl.newBuilder()
                                                    .setName("T")
                                                    .setIsTypeParam(true)
                                                    .build())
                                            .build())
                                    .addArguments(
                                        TypeDecl.newBuilder()
                                            .setName("T")
                                            .setIsTypeParam(true)
                                            .build())
                                    .setReturnType(
                                        TypeDecl.newBuilder()
                                            .setName("T")
                                            .setIsTypeParam(true)
                                            .build())
                                    .build()))))
                .build());
    assertThat(environment.extend(CEL_WITH_MESSAGE_TYPES, CelOptions.DEFAULT)).isNotNull();
  }

  @Test
  public void environment_setListVariable() throws Exception {
    String yamlConfig =
        "variables:\n"
            + "- name: 'request'\n"
            + "  type_name: 'list'\n"
            + "  params:\n"
            + "    - type_name: 'string'";

    CelEnvironment environment = ENVIRONMENT_PARSER.parse(yamlConfig);

    assertThat(environment)
        .isEqualTo(
            CelEnvironment.newBuilder()
                .setSource(environment.source().get())
                .setVariables(
                    ImmutableSet.of(
                        VariableDecl.create(
                            "request",
                            TypeDecl.newBuilder()
                                .setName("list")
                                .addParams(TypeDecl.create("string"))
                                .build())))
                .build());
  }

  @Test
  public void environment_setMapVariable() throws Exception {
    String yamlConfig =
        "variables:\n"
            + "- name: 'request'\n"
            + "  type:\n"
            + "    type_name: 'map'\n"
            + "    params:\n"
            + "      - type_name: 'string'\n"
            + "      - type_name: 'dyn'";

    CelEnvironment environment = ENVIRONMENT_PARSER.parse(yamlConfig);

    assertThat(environment)
        .isEqualTo(
            CelEnvironment.newBuilder()
                .setSource(environment.source().get())
                .setVariables(
                    ImmutableSet.of(
                        VariableDecl.create(
                            "request",
                            TypeDecl.newBuilder()
                                .setName("map")
                                .addParams(TypeDecl.create("string"), TypeDecl.create("dyn"))
                                .build())))
                .build());
    assertThat(environment.extend(CEL_WITH_MESSAGE_TYPES, CelOptions.DEFAULT)).isNotNull();
  }

  @Test
  public void environment_setMessageVariable() throws Exception {
    String yamlConfig =
        "variables:\n"
            + "- name: 'request'\n"
            + "  type:\n"
            + "    type_name: 'google.rpc.context.AttributeContext.Request'";

    CelEnvironment environment = ENVIRONMENT_PARSER.parse(yamlConfig);

    assertThat(environment)
        .isEqualTo(
            CelEnvironment.newBuilder()
                .setSource(environment.source().get())
                .setVariables(
                    ImmutableSet.of(
                        VariableDecl.create(
                            "request",
                            TypeDecl.create("google.rpc.context.AttributeContext.Request"))))
                .build());
    assertThat(environment.extend(CEL_WITH_MESSAGE_TYPES, CelOptions.DEFAULT)).isNotNull();
  }

  @Test
  public void environment_setListVariable_shorthand() throws Exception {
    String yamlConfig =
        "variables:\n" //
            + "- name: 'request'\n" //
            + "  type: 'list<string>'";

    CelEnvironment environment = ENVIRONMENT_PARSER.parse(yamlConfig);

    assertThat(environment)
        .isEqualTo(
            CelEnvironment.newBuilder()
                .setSource(environment.source().get())
                .setVariables(
                    ImmutableSet.of(
                        VariableDecl.create(
                            "request",
                            TypeDecl.newBuilder()
                                .setName("list")
                                .addParams(TypeDecl.create("string"))
                                .build())))
                .build());
    assertThat(environment.extend(CEL_WITH_MESSAGE_TYPES, CelOptions.DEFAULT)).isNotNull();
  }

  @Test
  public void environment_setMapVariable_shorthand() throws Exception {
    String yamlConfig =
        "variables:\n" //
            + "- name: 'request'\n" //
            + "  type: 'map<string, dyn>'";

    CelEnvironment environment = ENVIRONMENT_PARSER.parse(yamlConfig);

    assertThat(environment)
        .isEqualTo(
            CelEnvironment.newBuilder()
                .setSource(environment.source().get())
                .setVariables(
                    ImmutableSet.of(
                        VariableDecl.create(
                            "request",
                            TypeDecl.newBuilder()
                                .setName("map")
                                .addParams(TypeDecl.create("string"), TypeDecl.create("dyn"))
                                .build())))
                .build());
    assertThat(environment.extend(CEL_WITH_MESSAGE_TYPES, CelOptions.DEFAULT)).isNotNull();
  }

  @Test
  public void environment_withTypeSpecifiersEnabled_handlesStructuredMapTypeDecl()
      throws Exception {
    String yamlConfig =
        "variables:\n" //
            + "- name: 'request'\n" //
            + "  type:\n" //
            + "    type_name: 'map'\n" //
            + "    params:\n" //
            + "      - type_name: 'string'\n" //
            + "      - type_name: 'dyn'";

    CelEnvironment environment = ENVIRONMENT_PARSER.parse(yamlConfig);

    assertThat(environment)
        .isEqualTo(
            CelEnvironment.newBuilder()
                .setSource(environment.source().get())
                .setVariables(
                    ImmutableSet.of(
                        VariableDecl.create(
                            "request",
                            TypeDecl.newBuilder()
                                .setName("map")
                                .addParams(TypeDecl.create("string"), TypeDecl.create("dyn"))
                                .build())))
                .build());
    assertThat(environment.extend(CEL_WITH_MESSAGE_TYPES, CelOptions.DEFAULT)).isNotNull();
  }

  @Test
  public void environment_withTypeSpecifiersEnabled_handlesBlockScalarTextTypeDecl()
      throws Exception {
    String yamlConfig =
        "variables:\n" //
            + "- name: 'request'\n" //
            + "  type: >-\n" //
            + "    list<string>";

    CelEnvironment environment = ENVIRONMENT_PARSER.parse(yamlConfig);

    assertThat(environment)
        .isEqualTo(
            CelEnvironment.newBuilder()
                .setSource(environment.source().get())
                .setVariables(
                    ImmutableSet.of(
                        VariableDecl.create(
                            "request",
                            TypeDecl.newBuilder()
                                .setName("list")
                                .addParams(TypeDecl.create("string"))
                                .build())))
                .build());
    assertThat(environment.extend(CEL_WITH_MESSAGE_TYPES, CelOptions.DEFAULT)).isNotNull();
  }

  @Test
  public void environment_setMessageVariable_shorthand() throws Exception {
    String yamlConfig =
        "variables:\n" //
            + "- name: 'request'\n" //
            + "  type: 'google.rpc.context.AttributeContext.Request'";

    CelEnvironment environment = ENVIRONMENT_PARSER.parse(yamlConfig);

    assertThat(environment)
        .isEqualTo(
            CelEnvironment.newBuilder()
                .setSource(environment.source().get())
                .setVariables(
                    ImmutableSet.of(
                        VariableDecl.create(
                            "request",
                            TypeDecl.create("google.rpc.context.AttributeContext.Request"))))
                .build());
    assertThat(environment.extend(CEL_WITH_MESSAGE_TYPES, CelOptions.DEFAULT)).isNotNull();
  }

  @Test
  public void environment_setContextVariable_type() throws Exception {
    String yamlConfig =
        "context_variable:\n" //
            + "  type: 'google.rpc.context.AttributeContext.Request'";

    CelEnvironment environment = ENVIRONMENT_PARSER.parse(yamlConfig);

    assertThat(environment)
        .isEqualTo(
            CelEnvironment.newBuilder()
                .setSource(environment.source().get())
                .setContextVariable(
                    ContextVariable.create("google.rpc.context.AttributeContext.Request"))
                .build());
    assertThat(environment.extend(CEL_WITH_MESSAGE_TYPES, CelOptions.DEFAULT)).isNotNull();
  }

  @Test
  public void environment_setFunctions_shorthand() throws Exception {
    String yamlConfig =
        "functions:\n" //
            + "- name: 'isEmpty'\n" //
            + "  overloads:\n" //
            + "  - id: 'list_isEmpty'\n" //
            + "    target: 'list<~T>'\n" //
            + "    return: 'bool'\n" //
            + "- name: 'getOrDefault'\n" //
            + "  overloads:\n" //
            + "  - id: 'map_getOrDefault'\n" //
            + "    target: 'map<~K, ~V>'\n" //
            + "    args:\n" //
            + "    - '~K'\n" //
            + "    - '~V'\n" //
            + "    return: '~V'";

    CelEnvironment environment = ENVIRONMENT_PARSER.parse(yamlConfig);

    assertThat(environment)
        .isEqualTo(
            CelEnvironment.newBuilder()
                .setSource(environment.source().get())
                .setFunctions(
                    ImmutableSet.of(
                        FunctionDecl.create(
                            "isEmpty",
                            ImmutableSet.of(
                                OverloadDecl.newBuilder()
                                    .setId("list_isEmpty")
                                    .setTarget(
                                        TypeDecl.newBuilder()
                                            .setName("list")
                                            .addParams(TypeDecl.ofTypeParam("T"))
                                            .build())
                                    .setReturnType(TypeDecl.create("bool"))
                                    .build())),
                        FunctionDecl.create(
                            "getOrDefault",
                            ImmutableSet.of(
                                OverloadDecl.newBuilder()
                                    .setId("map_getOrDefault")
                                    .setTarget(
                                        TypeDecl.newBuilder()
                                            .setName("map")
                                            .addParams(
                                                TypeDecl.ofTypeParam("K"),
                                                TypeDecl.ofTypeParam("V"))
                                            .build())
                                    .addArguments(
                                        TypeDecl.ofTypeParam("K"), TypeDecl.ofTypeParam("V"))
                                    .setReturnType(TypeDecl.ofTypeParam("V"))
                                    .build()))))
                .build());
    assertThat(environment.extend(CEL_WITH_MESSAGE_TYPES, CelOptions.DEFAULT)).isNotNull();
  }

  @Test
  public void environment_withTypeSpecifier_invalidSyntaxError() {
    String yamlConfig =
        "variables:\n" //
            + "- name: 'request'\n" //
            + "  type: 'list<'";

    CelEnvironmentException e =
        assertThrows(CelEnvironmentException.class, () -> ENVIRONMENT_PARSER.parse(yamlConfig));
    assertThat(e).hasMessageThat().contains("missing identifier at position 5");
  }

  @Test
  public void environment_withTypeSpecifier_invalidYamlNodeError() {
    String yamlConfig =
        "variables:\n" //
            + "- name: 'request'\n" //
            + "  type: 1";

    CelEnvironmentException e =
        assertThrows(CelEnvironmentException.class, () -> ENVIRONMENT_PARSER.parse(yamlConfig));
    assertThat(e)
        .hasMessageThat()
        .contains("wanted type(s) [tag:yaml.org,2002:str !txt tag:yaml.org,2002:map]");
  }

  @Test
  public void environment_evaluatesShorthandVariable() throws Exception {
    String yamlConfig =
        "variables:\n" //
            + "- name: 'values'\n" //
            + "  type: 'list<string>'";

    CelEnvironment environment = ENVIRONMENT_PARSER.parse(yamlConfig);
    Cel cel = environment.extend(CelFactory.standardCelBuilder().build(), CelOptions.DEFAULT);

    CelAbstractSyntaxTree ast = cel.compile("values.size() == 2 && values[0] == 'hello'").getAst();
    boolean result =
        (boolean)
            cel.createProgram(ast)
                .eval(
                    ImmutableMap.<String, Object>of("values", ImmutableList.of("hello", "world")));
    assertThat(result).isTrue();
  }

  @Test
  public void environment_setContainer() throws Exception {
    String yamlConfig =
        "container: google.rpc.context\n"
            + "variables:\n"
            + "- name: 'request'\n"
            + "  type:\n"
            + "    type_name: 'google.rpc.context.AttributeContext.Request'";

    CelEnvironment environment = ENVIRONMENT_PARSER.parse(yamlConfig);

    assertThat(environment)
        .isEqualTo(
            CelEnvironment.newBuilder()
                .setContainer("google.rpc.context")
                .setSource(environment.source().get())
                .setVariables(
                    ImmutableSet.of(
                        VariableDecl.create(
                            "request",
                            TypeDecl.create("google.rpc.context.AttributeContext.Request"))))
                .build());
    assertThat(environment.extend(CEL_WITH_MESSAGE_TYPES, CelOptions.DEFAULT)).isNotNull();
  }

  @Test
  public void environment_setContainerWithAliasesAndAbbreviations() throws Exception {
    String yamlConfig =
        "container:\n"
            + "  name: 'google.rpc.context'\n"
            + "  abbreviations:\n"
            + "    - 'pkg3.lib3.Baz'\n"
            + "    - pkg4.lib4.Qux\n"
            + "  aliases:\n"
            + "    - alias: 'foo'\n"
            + "      qualified_name: 'pkg1.lib1.Foo'\n"
            + "    - alias: bar\n"
            + "      qualified_name: pkg2.lib2.Bar\n";

    CelEnvironment environment = ENVIRONMENT_PARSER.parse(yamlConfig);

    assertThat(environment)
        .isEqualTo(
            CelEnvironment.newBuilder()
                .setContainer(
                    CelContainer.newBuilder()
                        .setName("google.rpc.context")
                        .addAbbreviations("pkg3.lib3.Baz", "pkg4.lib4.Qux")
                        .addAlias("foo", "pkg1.lib1.Foo")
                        .addAlias("bar", "pkg2.lib2.Bar")
                        .build())
                .setSource(environment.source().get())
                .build());
  }

  @Test
  public void environment_withInlinedVariableDecl() throws Exception {
    String yamlConfig =
        "variables:\n"
            + "- name: 'request'\n"
            + "  type_name: 'google.rpc.context.AttributeContext.Request'\n"
            + "- name: 'map_var'\n"
            + "  type_name: 'map'\n"
            + "  params:\n"
            + "    - type_name: 'string'\n"
            + "    - type_name: 'string'";

    CelEnvironment environment = ENVIRONMENT_PARSER.parse(yamlConfig);

    assertThat(environment)
        .isEqualTo(
            CelEnvironment.newBuilder()
                .setSource(environment.source().get())
                .setVariables(
                    ImmutableSet.of(
                        VariableDecl.create(
                            "request",
                            TypeDecl.create("google.rpc.context.AttributeContext.Request")),
                        VariableDecl.create(
                            "map_var",
                            TypeDecl.newBuilder()
                                .setName("map")
                                .addParams(TypeDecl.create("string"), TypeDecl.create("string"))
                                .build())))
                .build());
    assertThat(environment.extend(CEL_WITH_MESSAGE_TYPES, CelOptions.DEFAULT)).isNotNull();
  }

  @Test
  public void environment_parseErrors(@TestParameter EnvironmentParseErrorTestcase testCase) {
    CelEnvironmentException e =
        assertThrows(
            CelEnvironmentException.class, () -> ENVIRONMENT_PARSER.parse(testCase.yamlConfig));
    assertThat(e).hasMessageThat().isEqualTo(testCase.expectedErrorMessage);
  }

  @Test
  public void environment_extendErrors(@TestParameter EnvironmentExtendErrorTestCase testCase)
      throws Exception {
    CelEnvironment environment = ENVIRONMENT_PARSER.parse(testCase.yamlConfig);

    CelEnvironmentException e =
        assertThrows(
            CelEnvironmentException.class,
            () -> environment.extend(CEL_WITH_MESSAGE_TYPES, CelOptions.DEFAULT));
    assertThat(e).hasMessageThat().isEqualTo(testCase.expectedErrorMessage);
  }

  // Note: dangling comments in expressions below is to retain the newlines by preventing auto
  // formatter from compressing them in a single line.
  private enum EnvironmentParseErrorTestcase {
    MALFORMED_YAML_DOCUMENT(
        "a:\na",
        "YAML document is malformed: while scanning a simple key\n"
            + " in 'reader', line 2, column 1:\n"
            + "    a\n"
            + "    ^\n"
            + "could not find expected ':'\n"
            + " in 'reader', line 2, column 2:\n"
            + "    a\n"
            + "     ^\n"),
    ILLEGAL_YAML_TYPE_CONFIG_KEY(
        "1: test",
        "ERROR: <input>:1:1: Got yaml node type tag:yaml.org,2002:int, wanted type(s)"
            + " [tag:yaml.org,2002:str !txt]\n"
            + " | 1: test\n"
            + " | ^"),
    ILLEGAL_YAML_TYPE_CONFIG_VALUE(
        "test: 1", "ERROR: <input>:1:1: Unknown config tag: test\n" + " | test: 1\n" + " | ^"),
    ILLEGAL_YAML_TYPE_VARIABLE_LIST(
        "variables: 1",
        "ERROR: <input>:1:12: Got yaml node type tag:yaml.org,2002:int, wanted type(s)"
            + " [tag:yaml.org,2002:seq]\n"
            + " | variables: 1\n"
            + " | ...........^"),
    ILLEGAL_YAML_TYPE_VARIABLE_VALUE(
        "variables:\n - 1",
        "ERROR: <input>:2:4: Got yaml node type tag:yaml.org,2002:int, wanted type(s)"
            + " [tag:yaml.org,2002:map]\n"
            + " |  - 1\n"
            + " | ...^"),
    ILLEGAL_YAML_TYPE_FUNCTION_LIST(
        "functions: 1",
        "ERROR: <input>:1:12: Got yaml node type tag:yaml.org,2002:int, wanted type(s)"
            + " [tag:yaml.org,2002:seq]\n"
            + " | functions: 1\n"
            + " | ...........^"),
    ILLEGAL_YAML_TYPE_FUNCTION_VALUE(
        "functions:\n - 1",
        "ERROR: <input>:2:4: Got yaml node type tag:yaml.org,2002:int, wanted type(s)"
            + " [tag:yaml.org,2002:map]\n"
            + " |  - 1\n"
            + " | ...^"),
    ILLEGAL_YAML_TYPE_OVERLOAD_LIST(
        "functions:\n" //
            + " - name: foo\n" //
            + "   overloads: 1",
        "ERROR: <input>:3:15: Got yaml node type tag:yaml.org,2002:int, wanted type(s)"
            + " [tag:yaml.org,2002:seq]\n"
            + " |    overloads: 1\n"
            + " | ..............^"),
    ILLEGAL_YAML_TYPE_OVERLOAD_VALUE(
        "functions:\n" //
            + " - name: foo\n" //
            + "   overloads:\n" //
            + "    - 2",
        "ERROR: <input>:4:7: Got yaml node type tag:yaml.org,2002:int, wanted type(s)"
            + " [tag:yaml.org,2002:map]\n"
            + " |     - 2\n"
            + " | ......^"),
    ILLEGAL_YAML_TYPE_OVERLOAD_VALUE_MAP_KEY(
        "functions:\n" //
            + " - name: foo\n" //
            + "   overloads:\n" //
            + "      - 2: test",
        "ERROR: <input>:4:9: Got yaml node type tag:yaml.org,2002:int, wanted type(s)"
            + " [tag:yaml.org,2002:str !txt]\n"
            + " |       - 2: test\n"
            + " | ........^\n"
            + "ERROR: <input>:4:9: Missing required attribute(s): id, return\n"
            + " |       - 2: test\n"
            + " | ........^"),
    ILLEGAL_YAML_TYPE_EXTENSION_LIST(
        "extensions: 1",
        "ERROR: <input>:1:13: Got yaml node type tag:yaml.org,2002:int, wanted type(s)"
            + " [tag:yaml.org,2002:seq]\n"
            + " | extensions: 1\n"
            + " | ............^"),
    ILLEGAL_YAML_TYPE_EXTENSION_VALUE(
        "extensions:\n - 1",
        "ERROR: <input>:2:4: Got yaml node type tag:yaml.org,2002:int, wanted type(s)"
            + " [tag:yaml.org,2002:map]\n"
            + " |  - 1\n"
            + " | ...^"),
    ILLEGAL_YAML_TYPE_TYPE_DECL(
        "variables:\n" //
            + " - name: foo\n" //
            + "   type: 1",
        "ERROR: <input>:3:10: Got yaml node type tag:yaml.org,2002:int, wanted type(s)"
            + " [tag:yaml.org,2002:str !txt tag:yaml.org,2002:map]\n"
            + " |    type: 1\n"
            + " | .........^"),
    ILLEGAL_YAML_TYPE_TYPE_VALUE(
        "variables:\n"
            + " - name: foo\n"
            + "   type:\n"
            + "     type_name: bar\n"
            + "     1: hello",
        "ERROR: <input>:5:6: Got yaml node type tag:yaml.org,2002:int, wanted type(s)"
            + " [tag:yaml.org,2002:str !txt]\n"
            + " |      1: hello\n"
            + " | .....^"),
    ILLEGAL_YAML_TYPE_TYPE_PARAMS_LIST(
        "variables:\n"
            + " - name: foo\n"
            + "   type:\n"
            + "     type_name: bar\n"
            + "     params: 1",
        "ERROR: <input>:5:14: Got yaml node type tag:yaml.org,2002:int, wanted type(s)"
            + " [tag:yaml.org,2002:seq]\n"
            + " |      params: 1\n"
            + " | .............^"),
    ILLEGAL_YAML_TYPE_WITH_INLINED_TYPE_TAGS(
        "variables:\n"
            + " - name: foo\n"
            + "   type_name: bar\n"
            + "   type:\n"
            + "     type_name: qux\n",
        "ERROR: <input>:4:4: 'type' tag cannot be used together with inlined 'type_name',"
            + " 'is_type_param' or 'params': type\n"
            + " |    type:\n"
            + " | ...^"),
    ILLEGAL_YAML_INLINED_TYPE_VALUE(
        "variables:\n" //
            + " - name: foo\n" //
            + "   type_name: 1\n",
        "ERROR: <input>:3:15: Got yaml node type tag:yaml.org,2002:int, wanted type(s)"
            + " [tag:yaml.org,2002:str !txt]\n"
            + " |    type_name: 1\n"
            + " | ..............^"),
    UNSUPPORTED_CONFIG_TAG(
        "unsupported: test",
        "ERROR: <input>:1:1: Unknown config tag: unsupported\n"
            + " | unsupported: test\n"
            + " | ^"),
    UNSUPPORTED_EXTENSION_TAG(
        "extensions:\n" //
            + "  - name: foo\n" //
            + "    unsupported: test",
        "ERROR: <input>:3:5: Unsupported extension tag: unsupported\n"
            + " |     unsupported: test\n"
            + " | ....^"),
    UNSUPPORTED_TYPE_DECL_TAG(
        "variables:\n"
            + "- name: foo\n"
            + "  type:\n"
            + "     type_name: bar\n"
            + "     unsupported: hello",
        "ERROR: <input>:5:6: Unsupported type decl tag: unsupported\n"
            + " |      unsupported: hello\n"
            + " | .....^"),
    MISSING_VARIABLE_PROPERTIES(
        "variables:\n - illegal: 2",
        "ERROR: <input>:2:4: Unsupported variable tag: illegal\n"
            + " |  - illegal: 2\n"
            + " | ...^\n"
            + "ERROR: <input>:2:4: Missing required attribute(s): name, type\n"
            + " |  - illegal: 2\n"
            + " | ...^"),
    MISSING_OVERLOAD_RETURN(
        "functions:\n"
            + "  - name: 'missing_return'\n"
            + "    overloads:\n"
            + "      - id: 'zero_arity'\n",
        "ERROR: <input>:4:9: Missing required attribute(s): return\n"
            + " |       - id: 'zero_arity'\n"
            + " | ........^"),
    MISSING_FUNCTION_NAME(
        "functions:\n"
            + "  - overloads:\n"
            + "      - id: 'foo'\n"
            + "        return:\n"
            + "          type_name: 'string'\n",
        "ERROR: <input>:2:5: Missing required attribute(s): name\n"
            + " |   - overloads:\n"
            + " | ....^"),
    MISSING_OVERLOAD(
        "functions:\n" + "  - name: 'missing_overload'\n",
        "ERROR: <input>:2:5: Missing required attribute(s): overloads\n"
            + " |   - name: 'missing_overload'\n"
            + " | ....^"),
    MISSING_EXTENSION_NAME(
        "extensions:\n" + "- version: 0",
        "ERROR: <input>:2:3: Missing required attribute(s): name\n"
            + " | - version: 0\n"
            + " | ..^"),
    ILLEGAL_LIBRARY_SUBSET_TAG(
        "name: 'test_suite_name'\n" + "stdlib:\n" + "  unknown_tag: 'test_value'\n",
        "ERROR: <input>:3:3: Unsupported library subset tag: unknown_tag\n"
            + " |   unknown_tag: 'test_value'\n"
            + " | ..^"),
    ILLEGAL_LIBRARY_SUBSET_FUNCTION_SELECTOR_TAG(
        "name: 'test_suite_name'\n"
            + "stdlib:\n"
            + "  include_functions:\n"
            + "    - name: 'test_function'\n"
            + "      unknown_tag: 'test_value'\n",
        "ERROR: <input>:5:7: Unsupported function selector tag: unknown_tag\n"
            + " |       unknown_tag: 'test_value'\n"
            + " | ......^"),
    MISSING_LIBRARY_SUBSET_FUNCTION_SELECTOR_NAME(
        "name: 'test_suite_name'\n"
            + "stdlib:\n"
            + "  include_functions:\n"
            + "    - overloads:\n"
            + "      - id: add_bytes\n",
        "ERROR: <input>:4:7: Missing required attribute(s): name\n"
            + " |     - overloads:\n"
            + " | ......^"),
    ILLEGAL_LIBRARY_SUBSET_OVERLOAD_SELECTOR_TAG(
        "name: 'test_suite_name'\n"
            + "stdlib:\n"
            + "  include_functions:\n"
            + "    - name: _+_\n"
            + "      overloads:\n"
            + "        - id: test_overload\n"
            + "          unknown_tag: 'test_value'\n",
        "ERROR: <input>:7:11: Unsupported overload selector tag: unknown_tag\n"
            + " |           unknown_tag: 'test_value'\n"
            + " | ..........^"),
    MISSING_ALIAS_FIELDS(
        "container:\n"
            + "  name: 'test_container'\n"
            + "  aliases:\n"
            + "    - qualified_name: 'test_qualified_name'\n",
        "ERROR: <input>:4:7: Missing required attribute(s): alias\n"
            + " |     - qualified_name: 'test_qualified_name'\n"
            + " | ......^"),
    ILLEGAL_ALIAS_TAG(
        "container:\n"
            + "  name: 'test_container'\n"
            + "  aliases:\n"
            + "    - alias: 'test_alias'\n"
            + "      qualified_name: 'test_qualified_name'\n"
            + "      unknown_tag: 'test_value'\n",
        "ERROR: <input>:6:7: Unsupported alias tag: unknown_tag\n"
            + " |       unknown_tag: 'test_value'\n"
            + " | ......^"),
    UNSUPPORTED_LIMIT_TAG(
        "limits:\n"
            + "  - name: 'test_limit'\n"
            + "    unknown_tag: 'test_value'\n"
            + "    value: 100\n",
        "ERROR: <input>:3:5: Unsupported limits tag: unknown_tag\n"
            + " |     unknown_tag: 'test_value'\n"
            + " | ....^"),
    MISSING_LIMIT_NAME(
        "limits:\n" + "  - value: 100\n",
        "ERROR: <input>:2:5: Missing required attribute(s): name\n"
            + " |   - value: 100\n"
            + " | ....^"),
    MISSING_LIMIT_VALUE(
        "limits:\n" + "  - name: 'test_limit'\n",
        "ERROR: <input>:2:5: Missing required attribute(s): value\n"
            + " |   - name: 'test_limit'\n"
            + " | ....^"),
    ILLEGAL_LIMIT_VALUE(
        "limits:\n" + "  - cel.limit.foo: 'not_a_number'\n",
        "ERROR: <input>:2:21: Got yaml node type tag:yaml.org,2002:str, wanted type(s)"
            + " [tag:yaml.org,2002:int]\n"
            + " |   - cel.limit.foo: 'not_a_number'\n"
            + " | ....................^"),
    ILLEGAL_FEATURE_TAG(
        "features:\n" + "  - name: 'test_feature'\n" + "    unknown_tag: 'test_value'\n",
        "ERROR: <input>:3:5: Unsupported feature tag: unknown_tag\n"
            + " |     unknown_tag: 'test_value'\n"
            + " | ....^"),
    MISSING_FEATURE_NAME(
        "features:\n" + "  - enabled: true\n",
        "ERROR: <input>:2:5: Missing required attribute(s): name\n"
            + " |   - enabled: true\n"
            + " | ....^"),
    ;

    private final String yamlConfig;
    private final String expectedErrorMessage;

    EnvironmentParseErrorTestcase(String yamlConfig, String expectedErrorMessage) {
      this.yamlConfig = yamlConfig;
      this.expectedErrorMessage = expectedErrorMessage;
    }
  }

  private enum EnvironmentExtendErrorTestCase {
    BAD_EXTENSION("extensions:\n" + "  - name: 'bad_name'", "Unrecognized extension: bad_name"),
    BAD_TYPE(
        "variables:\n" + "- name: 'bad_type'\n" + "  type:\n" + "    type_name: 'strings'",
        "Undefined type name: strings"),
    BAD_LIST(
        "variables:\n" + "  - name: 'bad_list'\n" + "    type:\n" + "      type_name: 'list'",
        "List type has unexpected param count: 0"),
    BAD_MAP(
        "variables:\n"
            + "  - name: 'bad_map'\n"
            + "    type:\n"
            + "      type_name: 'map'\n"
            + "      params:\n"
            + "        - type_name: 'string'",
        "Map type has unexpected param count: 1"),
    BAD_LIST_TYPE_PARAM(
        "variables:\n"
            + "  - name: 'bad_list_type_param'\n"
            + "    type:\n"
            + "      type_name: 'list'\n"
            + "      params:\n"
            + "        - type_name: 'number'",
        "Undefined type name: number"),
    BAD_MAP_TYPE_PARAM(
        "variables:\n"
            + "  - name: 'bad_map_type_param'\n"
            + "    type:\n"
            + "      type_name: 'map'\n"
            + "      params:\n"
            + "        - type_name: 'string'\n"
            + "        - type_name: 'optional'",
        "Undefined type name: optional"),
    BAD_RETURN(
        "functions:\n"
            + "  - name: 'bad_return'\n"
            + "    overloads:\n"
            + "      - id: 'zero_arity'\n"
            + "        return:\n"
            + "          type_name: 'mystery'",
        "Undefined type name: mystery"),
    BAD_OVERLOAD_TARGET(
        "functions:\n"
            + "  - name: 'bad_target'\n"
            + "    overloads:\n"
            + "      - id: 'unary_member'\n"
            + "        target:\n"
            + "          type_name: 'unknown'\n"
            + "        return:\n"
            + "          type_name: 'null_type'",
        "Undefined type name: unknown"),
    BAD_OVERLOAD_ARG(
        "functions:\n"
            + "  - name: 'bad_arg'\n"
            + "    overloads:\n"
            + "      - id: 'unary_global'\n"
            + "        args:\n"
            + "          - type_name: 'unknown'\n"
            + "        return:\n"
            + "          type_name: 'null_type'",
        "Undefined type name: unknown"),
    ;

    private final String yamlConfig;
    private final String expectedErrorMessage;

    EnvironmentExtendErrorTestCase(String yamlConfig, String expectedErrorMessage) {
      this.yamlConfig = yamlConfig;
      this.expectedErrorMessage = expectedErrorMessage;
    }
  }

  @SuppressWarnings("ImmutableEnumChecker") // Test only
  private enum EnvironmentYamlResourceTestCase {
    EXTENDED_ENV(
        "environment/extended_env.yaml",
        CelEnvironment.newBuilder()
            .setName("extended-env")
            .setContainer("cel.expr")
            .addExtensions(
                ImmutableSet.of(
                    ExtensionConfig.of("optional", 2),
                    ExtensionConfig.of("math", Integer.MAX_VALUE)))
            .setVariables(
                VariableDecl.newBuilder()
                    .setName("msg")
                    .setDescription(
                        "msg represents all possible type permutation which CEL understands from a"
                            + " proto perspective")
                    .setType(TypeDecl.create("cel.expr.conformance.proto3.TestAllTypes"))
                    .build())
            .setFunctions(
                FunctionDecl.newBuilder()
                    .setName("isEmpty")
                    .setDescription(
                        "determines whether a list is empty,\nor a string has no characters")
                    .setOverloads(
                        ImmutableSet.of(
                            OverloadDecl.newBuilder()
                                .setId("wrapper_string_isEmpty")
                                .setTarget(TypeDecl.create("google.protobuf.StringValue"))
                                .addExamples("''.isEmpty() // true")
                                .setReturnType(TypeDecl.create("bool"))
                                .build(),
                            OverloadDecl.newBuilder()
                                .setId("list_isEmpty")
                                .addExamples("[].isEmpty() // true")
                                .addExamples("[1].isEmpty() // false")
                                .setTarget(
                                    TypeDecl.newBuilder()
                                        .setName("list")
                                        .addParams(
                                            TypeDecl.newBuilder()
                                                .setName("T")
                                                .setIsTypeParam(true)
                                                .build())
                                        .build())
                                .setReturnType(TypeDecl.create("bool"))
                                .build()))
                    .build(),
                FunctionDecl.newBuilder()
                    .setName("isEmptyAlt")
                    .setDescription(
                        "determines whether a list is empty,\nor a string has no characters")
                    .setOverloads(
                        ImmutableSet.of(
                            OverloadDecl.newBuilder()
                                .setId("wrapper_string_isEmpty")
                                .setTarget(TypeDecl.create("google.protobuf.StringValue"))
                                .addExamples("''.isEmptyAlt() // true")
                                .setReturnType(TypeDecl.create("bool"))
                                .build(),
                            OverloadDecl.newBuilder()
                                .setId("list_isEmpty")
                                .addExamples("[].isEmptyAlt() // true")
                                .addExamples("[1].isEmptyAlt() // false")
                                .setTarget(
                                    TypeDecl.newBuilder()
                                        .setName("list")
                                        .addParams(TypeDecl.ofTypeParam("T"))
                                        .build())
                                .setReturnType(TypeDecl.create("bool"))
                                .build()))
                    .build(),
                FunctionDecl.newBuilder()
                    .setName("getOrDefault")
                    .setDescription(
                        "Returns the value of a key in a map or the provided\ndefault value.")
                    .setOverloads(
                        ImmutableSet.of(
                            OverloadDecl.newBuilder()
                                .setId("map_getOrDefault")
                                .setTarget(
                                    TypeDecl.newBuilder()
                                        .setName("map")
                                        .addParams(
                                            TypeDecl.ofTypeParam("K"), TypeDecl.ofTypeParam("V"))
                                        .build())
                                .addArguments(TypeDecl.ofTypeParam("K"), TypeDecl.ofTypeParam("V"))
                                .setReturnType(TypeDecl.ofTypeParam("V"))
                                .build()))
                    .build())
            .setFeatures(CelEnvironment.FeatureFlag.create("cel.feature.macro_call_tracking", true))
            .setLimits(
                ImmutableSet.of(
                    CelEnvironment.Limit.create("cel.limit.expression_code_points", 1000),
                    CelEnvironment.Limit.create("cel.limit.parse_recursion_depth", 7)))
            .build()),

    LIBRARY_SUBSET_ENV(
        "environment/subset_env.yaml",
        CelEnvironment.newBuilder()
            .setName("subset-env")
            .setStandardLibrarySubset(
                LibrarySubset.newBuilder()
                    .setDisabled(false)
                    .setExcludedMacros(ImmutableSet.of("map", "filter"))
                    .setExcludedFunctions(
                        ImmutableSet.of(
                            FunctionSelector.create(
                                "_+_", ImmutableSet.of("add_bytes", "add_list", "add_string")),
                            FunctionSelector.create("matches", ImmutableSet.of()),
                            FunctionSelector.create(
                                "timestamp", ImmutableSet.of("string_to_timestamp")),
                            FunctionSelector.create(
                                "duration", ImmutableSet.of("string_to_duration"))))
                    .build())
            .setVariables(
                VariableDecl.create("x", TypeDecl.create("int")),
                VariableDecl.create("y", TypeDecl.create("double")),
                VariableDecl.create("z", TypeDecl.create("uint")))
            .build()),
    ;

    private final String yamlFileContent;
    private final CelEnvironment expectedEnvironment;

    EnvironmentYamlResourceTestCase(String yamlResourcePath, CelEnvironment expectedEnvironment) {
      try {
        this.yamlFileContent = readFile(yamlResourcePath);
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
      this.expectedEnvironment = expectedEnvironment;
    }
  }

  @Test
  public void environment_withYamlResource(@TestParameter EnvironmentYamlResourceTestCase testCase)
      throws Exception {
    CelEnvironment environment = ENVIRONMENT_PARSER.parse(testCase.yamlFileContent);

    // Empty out the parsed yaml source, as it's not relevant in the assertion here, and that it's
    // only obtainable through the yaml parser.
    environment = environment.toBuilder().setSource(Optional.empty()).build();
    assertThat(environment).isEqualTo(testCase.expectedEnvironment);
  }

  @Test
  public void lateBoundFunction_evaluate_callExpr() throws Exception {
    String configSource =
        "name: late_bound_function_config\n"
            + "functions:\n"
            + "  - name: 'test'\n"
            + "    overloads:\n"
            + "      - id: 'test_bool'\n"
            + "        args:\n"
            + "          - type_name: 'bool'\n"
            + "        return:\n"
            + "          type_name: 'bool'";
    CelEnvironment celEnvironment = ENVIRONMENT_PARSER.parse(configSource);
    Cel celDetails =
        CelFactory.standardCelBuilder()
            .addVar("a", SimpleType.INT)
            .addVar("b", SimpleType.INT)
            .addVar("c", SimpleType.INT)
            .build();
    Cel cel = celEnvironment.extend(celDetails, CelOptions.DEFAULT);
    CelAbstractSyntaxTree ast = cel.compile("a < 0 && b < 0 && c < 0 && test(a<0)").getAst();
    CelLateFunctionBindings bindings =
        CelLateFunctionBindings.from(
            CelFunctionBinding.from("test_bool", Boolean.class, result -> result));

    boolean result =
        (boolean) cel.createProgram(ast).eval(ImmutableMap.of("a", -1, "b", -1, "c", -4), bindings);

    assertThat(result).isTrue();
  }

  @Test
  public void lateBoundFunction_trace_callExpr_identifyFalseBranch() throws Exception {
    AtomicReference<CelExpr> capturedExpr = new AtomicReference<>();
    CelEvaluationListener listener =
        (expr, res) -> {
          if (res instanceof Boolean && !(boolean) res && capturedExpr.get() == null) {
            capturedExpr.set(expr);
          }
        };

    String configSource =
        "name: late_bound_function_config\n"
            + "functions:\n"
            + "  - name: 'test'\n"
            + "    overloads:\n"
            + "      - id: 'test_bool'\n"
            + "        args:\n"
            + "          - type_name: 'bool'\n"
            + "        return:\n"
            + "          type_name: 'bool'";

    CelEnvironment celEnvironment = ENVIRONMENT_PARSER.parse(configSource);
    Cel celDetails =
        CelFactory.standardCelBuilder()
            .addVar("a", SimpleType.INT)
            .addVar("b", SimpleType.INT)
            .addVar("c", SimpleType.INT)
            .build();
    Cel cel = celEnvironment.extend(celDetails, CelOptions.DEFAULT);
    CelAbstractSyntaxTree ast = cel.compile("a < 0 && b < 0 && c < 0 && test(a<0)").getAst();
    CelLateFunctionBindings bindings =
        CelLateFunctionBindings.from(
            CelFunctionBinding.from("test_bool", Boolean.class, result -> result));

    boolean result =
        (boolean)
            cel.createProgram(ast)
                .trace(ImmutableMap.of("a", -1, "b", 1, "c", -4), bindings, listener);

    assertThat(result).isFalse();
    // Demonstrate that "b < 0" is what caused the expression to be false
    CelAbstractSyntaxTree subtree =
        CelAbstractSyntaxTree.newParsedAst(capturedExpr.get(), CelSource.newBuilder().build());
    assertThat(CelUnparserFactory.newUnparser().unparse(subtree)).isEqualTo("b < 0");
  }

  private static String readFile(String path) throws IOException {
    URL url = Resources.getResource(Ascii.toLowerCase(path));
    return Resources.toString(url, UTF_8);
  }
}
