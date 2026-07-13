# Copyright 2022 Google LLC
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

# From: https://github.com/google/guice/blob/master/test_defs.bzl
"""starlark macros to generate test suites."""

load("@rules_java//java:defs.bzl", "java_test")

_TEMPLATE = """package {VAR_PACKAGE};
import org.junit.runners.Suite;
import org.junit.runner.RunWith;

@RunWith(Suite.class)
@Suite.SuiteClasses({{{VAR_CLASSES}}})
public class {VAR_NAME} {{}}
"""

def _impl(ctx):
    classes = ",".join(sorted(ctx.attr.test_classes))

    ctx.actions.write(
        output = ctx.outputs.out,
        content = _TEMPLATE.format(
            VAR_PACKAGE = ctx.attr.package_name,
            VAR_CLASSES = classes,
            VAR_NAME = ctx.attr.name,
        ),
    )

_gen_suite = rule(
    attrs = {
        "test_classes": attr.string_list(),
        "package_name": attr.string(),
    },
    outputs = {"out": "%{name}.java"},
    implementation = _impl,
)

def junit4_test_suites(
        name,
        sizes,
        deps,
        src_dir,  # @unused.
        shard_count = None,  # @unused.
        srcs = None):
    """
    Generates tests for test file in srcs ending in "Test.java"

    Args:
      name: name of the test suite to generate
      sizes: not used, exists only so that the opensource CEL mirror exactly the internal one
      deps: list of runtime dependencies required to run the test
      src_dir: not used, exists only so that the opensource CEL mirror exactly the internal one
      shard_count: not used, exists only so that the opensource CEL mirror exactly the internal one
      srcs: list of test source files, uses 'glob(["**/*Test.java"])' if not specified
    """

    package_name = native.package_name()

    # strip the path prefix from package name so that we get the correct test class name
    # "common/src/test/java/dev/cel/common/internal" becomes "dev/cel/common/internal"
    package_name = package_name.rpartition("/test/java/")[2]

    test_files = srcs or native.glob(
        ["**/*Test.java"],
        # TODO: Inspect built JAR and derive the included test files from classpath instead (provided from java_library deps).
        exclude = [
            "**/*AndroidTest.java",
            "**/AbstractPlannerInterpreterTest.java",
        ],
    )
    test_classes = []
    for src in test_files:
        test_name = src.replace(".java", "")
        test_classes.append((package_name + "/" + test_name + ".class").replace("/", "."))

    suite_name = name
    _gen_suite(
        name = suite_name,
        test_classes = test_classes,
        package_name = package_name.replace("/", "."),
    )

    java_test(
        name = "AllTestsSuite",
        test_class = (package_name + "/" + suite_name).replace("/", "."),
        srcs = [":" + suite_name],
        deps = deps,
        tags = sizes,
    )
