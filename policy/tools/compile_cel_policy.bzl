# Copyright 2026 Google LLC
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      https://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
"""Rule for compiling CEL policies at build time."""

load("@rules_proto//proto:defs.bzl", "proto_descriptor_set")

def compile_cel_policy(
        name,
        policy,
        config,
        base_config = None,
        proto_srcs = [],
        file_descriptor_set = None,
        output = None,
        output_format = "binarypb",
        output_version = "canonical",
        optimize_field_selection = False,
        simple_variables = False,
        visibility = None):
    """Compiles a CEL policy into a CheckedExpr binarypb or textpb with optional select optimization.

    This macro wraps an invocation of cel_policy_compiler_tool with a genrule. The rule output
    will be a CheckedExpr message in the requested version (canonical or v1alpha1) and format
    (binarypb, textpb, or textproto).

    Args:
      name: str name for the generated artifact
      policy: label of a file describing a CEL policy (.celpolicy or .yaml)
      config: label of a file describing the CEL policy environment in YAML
      base_config: (optional) label of a file describing the base environment configuration in YAML
      proto_srcs: (optional) list of str label(s) pointing to proto_library rule(s)
      file_descriptor_set: (optional) str label or filename pointing to a FileDescriptorSet message
      output: (optional) str file name for the output checked expression (default derived from label name and format)
      output_format: (optional) str either "binarypb", "textpb", or "textproto" (default "binarypb")
      output_version: (optional) str either "canonical" or "v1alpha1" (default "canonical")
      optimize_field_selection: (optional) bool whether to enable AST select optimization (default False)
      simple_variables: (optional) bool whether to enable simple variables parsing (default False)
      visibility: (optional) visibility to use on the genrule macro (default None)
    """
    if output_format not in ("binarypb", "textpb", "textproto"):
        fail("output_format only supports 'binarypb', 'textpb', and 'textproto'")

    if output_version not in ("canonical", "v1alpha1"):
        fail("output_version only supports 'canonical' and 'v1alpha1'")

    if output == None:
        output = name + "." + output_format

    args = []
    srcs = [policy, config]

    args.append("--policy=$(location %s)" % policy)
    args.append("--config=$(location %s)" % config)

    if base_config != None:
        args.append("--base_config=$(location %s)" % base_config)
        srcs.append(base_config)

    if len(proto_srcs) > 0 and file_descriptor_set != None:
        fail("Cannot specify both proto_srcs and file_descriptor_set in compile_cel_policy")

    if len(proto_srcs) > 0:
        transitive_descriptor_set_name = "%s_transitive_descriptor_set" % name
        proto_descriptor_set(
            name = transitive_descriptor_set_name,
            deps = proto_srcs,
        )
        file_descriptor_set = transitive_descriptor_set_name

    if file_descriptor_set != None:
        args.append("--file_descriptor_set=$(location %s)" % file_descriptor_set)
        srcs.append(file_descriptor_set)

    args.append("--output=$(location %s)" % output)
    args.append("--output_format=" + output_format)
    args.append("--output_version=" + output_version)

    if optimize_field_selection:
        args.append("--optimize_field_selection")

    if simple_variables:
        args.append("--simple_variables")

    cmd = (
        "$(location //policy/tools:cel_policy_compiler_tool) " +
        " ".join(args)
    )

    native.genrule(
        name = name,
        cmd = cmd,
        srcs = srcs,
        outs = [output],
        tools = ["//policy/tools:cel_policy_compiler_tool"],
        visibility = visibility,
    )
