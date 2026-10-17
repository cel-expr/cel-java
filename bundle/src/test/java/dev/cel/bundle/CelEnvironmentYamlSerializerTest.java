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

import com.google.common.base.Ascii;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.io.Resources;
import dev.cel.bundle.CelEnvironment.ExtensionConfig;
import dev.cel.bundle.CelEnvironment.FunctionDecl;
import dev.cel.bundle.CelEnvironment.LibrarySubset;
import dev.cel.bundle.CelEnvironment.LibrarySubset.FunctionSelector;
import dev.cel.bundle.CelEnvironment.OverloadDecl;
import dev.cel.bundle.CelEnvironment.TypeDecl;
import dev.cel.bundle.CelEnvironment.VariableDecl;
import dev.cel.common.CelContainer;
import java.io.IOException;
import java.net.URL;
import java.util.Optional;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class CelEnvironmentYamlSerializerTest {

  @Test
  public void toYaml_success() throws Exception {
    CelEnvironment environment =
        CelEnvironment.newBuilder()
            .setName("dump_env")
            .setDescription("dump_env description")
            .setContainer(
                CelContainer.newBuilder()
                    .setName("test.container")
                    .addAbbreviations("abbr1.Abbr1", "abbr2.Abbr2")
                    .addAlias("alias1", "qual.name1")
                    .addAlias("alias2", "qual.name2")
                    .build())
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
            .setVariables(
                ImmutableSet.of(
                    VariableDecl.create(
                        "request", TypeDecl.create("google.rpc.context.AttributeContext.Request")),
                    VariableDecl.create(
                        "map_var",
                        TypeDecl.newBuilder()
                            .setName("map")
                            .addParams(TypeDecl.create("string"))
                            .addParams(TypeDecl.create("string"))
                            .build())))
            .setFunctions(
                ImmutableSet.of(
                    FunctionDecl.create(
                        "getOrDefault",
                        ImmutableSet.of(
                            OverloadDecl.newBuilder()
                                .setId("getOrDefault_key_value")
                                .setTarget(
                                    TypeDecl.newBuilder()
                                        .setName("map")
                                        .addParams(
                                            TypeDecl.newBuilder()
                                                .setName("K")
                                                .setIsTypeParam(true)
                                                .build())
                                        .addParams(
                                            TypeDecl.newBuilder()
                                                .setName("V")
                                                .setIsTypeParam(true)
                                                .build())
                                        .build())
                                .setArguments(
                                    ImmutableList.of(
                                        TypeDecl.newBuilder()
                                            .setName("K")
                                            .setIsTypeParam(true)
                                            .build(),
                                        TypeDecl.newBuilder()
                                            .setName("V")
                                            .setIsTypeParam(true)
                                            .build()))
                                .setReturnType(
                                    TypeDecl.newBuilder().setName("V").setIsTypeParam(true).build())
                                .build())),
                    FunctionDecl.create(
                        "zip",
                        ImmutableSet.of(
                            OverloadDecl.newBuilder()
                                .setId("zip_list_int_list_int")
                                .setArguments(
                                    ImmutableList.of(
                                        TypeDecl.newBuilder()
                                            .setName("list")
                                            .addParams(TypeDecl.create("int"))
                                            .build(),
                                        TypeDecl.newBuilder()
                                            .setName("list")
                                            .addParams(TypeDecl.create("int"))
                                            .build()))
                                .setReturnType(
                                    TypeDecl.newBuilder()
                                        .setName("list")
                                        .addParams(
                                            TypeDecl.newBuilder()
                                                .setName("list")
                                                .addParams(TypeDecl.create("int"))
                                                .build())
                                        .build())
                                .build())),
                    FunctionDecl.create(
                        "zipGeneric",
                        ImmutableSet.of(
                            OverloadDecl.newBuilder()
                                .setId("zip_list_list")
                                .setArguments(
                                    ImmutableList.of(
                                        TypeDecl.newBuilder()
                                            .setName("list")
                                            .addParams(
                                                TypeDecl.newBuilder()
                                                    .setName("T")
                                                    .setIsTypeParam(true)
                                                    .build())
                                            .build(),
                                        TypeDecl.newBuilder()
                                            .setName("list")
                                            .addParams(
                                                TypeDecl.newBuilder()
                                                    .setName("T")
                                                    .setIsTypeParam(true)
                                                    .build())
                                            .build()))
                                .setReturnType(
                                    TypeDecl.newBuilder()
                                        .setName("list")
                                        .addParams(
                                            TypeDecl.newBuilder()
                                                .setName("list")
                                                .addParams(
                                                    TypeDecl.newBuilder()
                                                        .setName("T")
                                                        .setIsTypeParam(true)
                                                        .build())
                                                .build())
                                        .build())
                                .build())),
                    FunctionDecl.create(
                        "coalesce",
                        ImmutableSet.of(
                            OverloadDecl.newBuilder()
                                .setId("coalesce_null_int")
                                .setTarget(TypeDecl.create("google.protobuf.Int64Value"))
                                .setArguments(ImmutableList.of(TypeDecl.create("int")))
                                .setReturnType(TypeDecl.create("int"))
                                .build()))))
            .setStandardLibrarySubset(
                LibrarySubset.newBuilder()
                    .setDisabled(true)
                    .setMacrosDisabled(true)
                    .setIncludedMacros(ImmutableSet.of("has", "exists"))
                    .setIncludedFunctions(
                        ImmutableSet.of(
                            FunctionSelector.create("_!=_", ImmutableSet.of()),
                            FunctionSelector.create(
                                "_+_", ImmutableSet.of("add_bytes", "add_list"))))
                    .build())
            .setFeatures(
                CelEnvironment.FeatureFlag.create("cel.feature.macro_call_tracking", true),
                CelEnvironment.FeatureFlag.create("cel.feature.backtick_escape_syntax", false))
            .setLimits(
                CelEnvironment.Limit.create("cel.limit.expression_code_points", 1000),
                CelEnvironment.Limit.create("cel.limit.parse_error_recovery", 10),
                CelEnvironment.Limit.create("cel.limit.parse_recursion_depth", 7))
            .build();

    String yamlOutput = CelEnvironmentYamlSerializer.toYaml(environment);
    try {
      String yamlFileContent = readFile("environment/dump_env.yaml");
      // Strip the license at the beginning of the file
      String expectedOutput = yamlFileContent.replaceAll("#.*\\n", "").replaceAll("^\\n", "");
      assertThat(yamlOutput).isEqualTo(expectedOutput);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private static String readFile(String path) throws IOException {
    URL url = Resources.getResource(Ascii.toLowerCase(path));
    return Resources.toString(url, UTF_8);
  }

  @Test
  public void standardLibrary_excludeMacrosAndFunctions() throws Exception {
    CelEnvironment environment =
        CelEnvironment.newBuilder()
            .setName("dump_env")
            .setStandardLibrarySubset(
                LibrarySubset.newBuilder()
                    .setDisabled(false)
                    .setExcludedMacros(ImmutableSet.of("has", "exists"))
                    .setExcludedFunctions(
                        ImmutableSet.of(
                            FunctionSelector.create("_!=_", ImmutableSet.of()),
                            FunctionSelector.create(
                                "_+_", ImmutableSet.of("add_bytes", "add_list"))))
                    .build())
            .build();

    String yamlOutput = CelEnvironmentYamlSerializer.toYaml(environment);

    String expectedYaml =
        "name: dump_env\n"
            + "stdlib:\n"
            + "  exclude_macros:\n"
            + "  - exists\n"
            + "  - has\n"
            + "  exclude_functions:\n"
            + "  - name: _!=_\n"
            + "  - name: _+_\n"
            + "    overloads:\n"
            + "    - id: add_bytes\n"
            + "    - id: add_list\n";

    assertThat(yamlOutput).isEqualTo(expectedYaml);
  }

  @Test
  public void toYaml_useTypeSignatures() throws Exception {
    CelEnvironment environment =
        CelEnvironment.newBuilder()
            .setName("signature_env")
            .setVariables(
                ImmutableSet.of(
                    VariableDecl.newBuilder()
                        .setName("msg")
                        .setDescription("message variable")
                        .setType(TypeDecl.create("google.rpc.context.AttributeContext.Request"))
                        .build(),
                    VariableDecl.create(
                        "map_var",
                        TypeDecl.newBuilder()
                            .setName("map")
                            .addParams(TypeDecl.create("string"))
                            .addParams(TypeDecl.ofTypeParam("V"))
                            .build())))
            .setFunctions(
                ImmutableSet.of(
                    FunctionDecl.newBuilder()
                        .setName("getOrDefault")
                        .setDescription("Returns the value of a key or default.")
                        .setOverloads(
                            ImmutableSet.of(
                                OverloadDecl.newBuilder()
                                    .setId("getOrDefault_key_value")
                                    .setTarget(
                                        TypeDecl.newBuilder()
                                            .setName("map")
                                            .addParams(
                                                TypeDecl.ofTypeParam("K"),
                                                TypeDecl.ofTypeParam("V"))
                                            .build())
                                    .setArguments(
                                        ImmutableList.of(
                                            TypeDecl.ofTypeParam("K"), TypeDecl.ofTypeParam("V")))
                                    .addExamples("{'a': 1}.getOrDefault('a', 0) // 1")
                                    .setReturnType(TypeDecl.ofTypeParam("V"))
                                    .build()))
                        .build(),
                    FunctionDecl.create(
                        "contains",
                        ImmutableSet.of(
                            OverloadDecl.newBuilder()
                                .setId("contains(list<~T>,~T)")
                                .setArguments(
                                    ImmutableList.of(
                                        TypeDecl.newBuilder()
                                            .setName("list")
                                            .addParams(TypeDecl.ofTypeParam("T"))
                                            .build(),
                                        TypeDecl.ofTypeParam("T")))
                                .addExamples("contains([1, 2, 3], 2) // true")
                                .setReturnType(TypeDecl.create("bool"))
                                .build())),
                    FunctionDecl.create(
                        "coalesce",
                        ImmutableSet.of(
                            OverloadDecl.newBuilder()
                                .setId("coalesce_null_int")
                                .setTarget(TypeDecl.create("google.protobuf.Int64Value"))
                                .setArguments(ImmutableList.of(TypeDecl.create("int")))
                                .setReturnType(TypeDecl.create("int"))
                                .build()))))
            .build();

    String yamlOutput =
        CelEnvironmentYamlSerializer.toYaml(environment, /* useTypeSignatures= */ true);

    String expectedYaml =
        "name: signature_env\n"
            + "variables:\n"
            + "- name: msg\n"
            + "  description: message variable\n"
            + "  type: google.rpc.context.AttributeContext.Request\n"
            + "- name: map_var\n"
            + "  type: map<string,~V>\n"
            + "functions:\n"
            + "- name: getOrDefault\n"
            + "  description: Returns the value of a key or default.\n"
            + "  overloads:\n"
            + "  - id: getOrDefault_key_value\n"
            + "    signature: map<~K,~V>.getOrDefault(~K,~V)\n"
            + "    examples:\n"
            + "    - '{''a'': 1}.getOrDefault(''a'', 0) // 1'\n"
            + "    return: ~V\n"
            + "- name: contains\n"
            + "  overloads:\n"
            + "  - signature: contains(list<~T>,~T)\n"
            + "    examples:\n"
            + "    - contains([1, 2, 3], 2) // true\n"
            + "    return: bool\n"
            + "- name: coalesce\n"
            + "  overloads:\n"
            + "  - id: coalesce_null_int\n"
            + "    signature: int_wrapper.coalesce(int)\n"
            + "    return: int\n";

    assertThat(yamlOutput).isEqualTo(expectedYaml);

    CelEnvironment roundTripped =
        CelEnvironmentYamlParser.newInstance().parse(yamlOutput).toBuilder()
            .setSource(Optional.empty())
            .build();
    assertThat(roundTripped).isEqualTo(environment);
  }
}
