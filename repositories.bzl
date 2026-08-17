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

"""repositories for loading non bzlmod dependencies"""

load("@bazel_tools//tools/build_defs/repo:http.bzl", "http_archive", "http_jar")

def antlr4_jar_dependency():
    http_jar(
        name = "antlr4_jar",
        sha256 = "eae2dfa119a64327444672aff63e9ec35a20180dc5b8090b7a6ab85125df4d76",
        urls = ["https://www.antlr.org/download/antlr-4.13.2-complete.jar"],
    )

def bazel_common_dependency():
    bazel_common_tag = "768dbe0b3247e2e5def0b9ac6c4cde95e214f18a"
    bazel_common_sha = "b3f1fe7e26ade37712b00b82a0ab3760bb340e9307d57166872dacc679b78da1"
    http_archive(
        name = "bazel_common",
        sha256 = bazel_common_sha,
        strip_prefix = "bazel-common-%s" % bazel_common_tag,
        url = "https://github.com/google/bazel-common/archive/%s.tar.gz" % bazel_common_tag,
    )

def cel_policy_dependency():
    cel_policy_tag = "01bcc1c3f7c9c5e442fa940013cd6d029af2baf7"
    cel_policy_sha = "8e3ddc74e918c2a5910794387354a236da601694dbf7b6921f8a7babf7b78181"
    http_archive(
        name = "cel_policy",
        sha256 = cel_policy_sha,
        strip_prefix = "cel-policy-%s" % cel_policy_tag,
        url = "https://github.com/cel-expr/cel-policy/archive/%s.tar.gz" % cel_policy_tag,
    )

def _non_module_dependencies_impl(_ctx):
    antlr4_jar_dependency()
    bazel_common_dependency()
    cel_policy_dependency()

non_module_dependencies = module_extension(
    implementation = _non_module_dependencies_impl,
)
