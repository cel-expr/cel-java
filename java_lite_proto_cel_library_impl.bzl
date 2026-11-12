# Copyright 2025 Google LLC
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

"""
Starlark rule for generating descriptors that is compatible with Protolite Messages.
This is an implementation detail. Clients should use 'java_lite_proto_cel_library' instead.
"""

load("@rules_java//java:defs.bzl", "java_library")
load("//:cel_android_rules.bzl", "cel_android_library")
load("//publish:cel_version.bzl", "CEL_VERSION")
load("@com_google_protobuf//bazel:java_lite_proto_library.bzl", "java_lite_proto_library")
load("@com_google_protobuf//bazel/common:proto_info.bzl", "ProtoInfo")

def java_lite_proto_cel_library_impl(
        name,
        deps,
        java_proto_library_dep,
        use_android_library = False,
        java_descriptor_class_suffix = None,
        debug = False):
    """Generates a CelLiteDescriptor

    Args:
       name: Name of this target.
       deps: The list of proto_library rules to generate Java code for.
       java_descriptor_class_suffix (optional): Suffix for the Java class name of the generated CEL lite descriptor.
                                                Default is "CelLiteDescriptor".
       use_android_library: (optional) If true, generate an android_library target.
       java_proto_library_dep: (optional) Uses the provided java_lite_proto_library or java_proto_library to generate the lite descriptors.
                                If none is provided, java_lite_proto_library is used by default behind the scenes. Most use cases should not need to provide this.
       debug: (optional) If true, prints additional information during codegen for debugging purposes.
    """
    if not name:
        fail("You must provide a name.")

    if not deps or len(deps) < 1:
        fail("You must provide at least one proto_library dependency.")

    generated = name + "_cel_lite_descriptor"
    java_lite_proto_cel_library_rule(
        name = generated,
        debug = debug,
        descriptors = deps,
        java_descriptor_class_suffix = java_descriptor_class_suffix,
    )

    if not java_proto_library_dep:
        java_proto_library_dep = name + "_java_lite_proto_dep"
        java_lite_proto_library(
            name = java_proto_library_dep,
            deps = deps,
        )

    descriptor_codegen_deps = [
        "//common/annotations",
        "//protobuf:cel_lite_descriptor",
        java_proto_library_dep,
    ]

    if use_android_library:
        cel_android_library(
            name = name,
            srcs = [":" + generated],
            deps = descriptor_codegen_deps,
        )
    else:
        java_library(
            name = name,
            srcs = [":" + generated],
            deps = descriptor_codegen_deps,
        )

def _generate_cel_lite_descriptor_class(ctx):
    srcjar_output = ctx.actions.declare_file(ctx.attr.name + ".srcjar")
    java_file_path = srcjar_output.path

    direct_descriptor_set = depset(
        direct = [
            descriptor[ProtoInfo].direct_descriptor_set
            for descriptor in ctx.attr.descriptors
        ],
    )
    transitive_descriptor_set = depset(
        transitive = [
            descriptor[ProtoInfo].transitive_descriptor_sets
            for descriptor in ctx.attr.descriptors
        ],
    )

    args = ctx.actions.args()
    args.add("--version", CEL_VERSION)
    args.add_joined("--descriptor_set", direct_descriptor_set, join_with = ",")
    args.add_joined("--transitive_descriptor_set", transitive_descriptor_set, join_with = ",")
    args.add("--out", java_file_path)

    if ctx.attr.java_descriptor_class_suffix:
        args.add("--overridden_descriptor_class_suffix", ctx.attr.java_descriptor_class_suffix)

    if ctx.attr.debug:
        args.add("--debug")

    ctx.actions.run(
        mnemonic = "CelLiteDescriptorGenerator",
        arguments = [args],
        inputs = transitive_descriptor_set,
        outputs = [srcjar_output],
        progress_message = "Generating CelLiteDescriptor for: " + ctx.attr.name,
        executable = ctx.executable._tool,
    )

    return [DefaultInfo(files = depset([srcjar_output]))]

java_lite_proto_cel_library_rule = rule(
    implementation = _generate_cel_lite_descriptor_class,
    attrs = {
        "java_descriptor_class_suffix": attr.string(),
        "descriptors": attr.label_list(
            providers = [ProtoInfo],
        ),
        "debug": attr.bool(),
        "_tool": attr.label(
            executable = True,
            cfg = "exec",
            allow_files = True,
            default = Label("//protobuf:cel_lite_descriptor_generator"),
        ),
    },
)
