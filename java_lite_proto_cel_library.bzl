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

"""Starlark rule for generating descriptors that is compatible with Protolite Messages."""

load("//:java_lite_proto_cel_library_impl.bzl", "java_lite_proto_cel_library_impl")
load("@com_google_protobuf//bazel:java_lite_proto_library.bzl", "java_lite_proto_library")

def java_lite_proto_cel_library(
        name,
        deps,
        java_descriptor_class_suffix = None,
        debug = False):
    """Generates a CelLiteDescriptor

    Args:
       name: name of this target.
       deps: The list of proto_library rules to generate Java code for.
       java_descriptor_class_suffix (optional): Suffix for the Java class name of the generated CEL lite descriptor.
                                                Default is "CelLiteDescriptor".
       debug: (optional) If true, prints additional information during codegen for debugging purposes.
    """
    if not name:
        fail("You must provide a name.")

    if not deps or len(deps) < 1:
        fail("You must provide at least one proto_library dependency.")

    java_proto_library_dep = name + "_java_lite_proto_dep"
    java_lite_proto_library(
        name = java_proto_library_dep,
        deps = deps,
    )

    java_lite_proto_cel_library_impl(
        name = name,
        deps = deps,
        # used_by_android
        java_descriptor_class_suffix = java_descriptor_class_suffix,
        java_proto_library_dep = java_proto_library_dep,
        debug = debug,
    )
