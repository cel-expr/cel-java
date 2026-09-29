# Copyright 2024 Google LLC
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     https://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

"""
This module contains build rules for generating the conformance test targets.
"""

load("@rules_java//java:defs.bzl", "java_test")
load("@rules_shell//shell:sh_test.bzl", "sh_test")

# Converts the list of tests to skip from the format used by the original Go test runner to a single
# flag value where each test is separated by a comma. It also performs expansion, for example
# `foo/bar,baz` becomes two entries which are `foo/bar` and `foo/baz`.
def _expand_tests_to_skip(tests_to_skip):
    result = []
    for test_to_skip in tests_to_skip:
        comma = test_to_skip.find(",")
        if comma == -1:
            result.append(test_to_skip)
            continue
        slash = test_to_skip.rfind("/", 0, comma)
        if slash == -1:
            slash = 0
        else:
            slash = slash + 1
        for part in test_to_skip[slash:].split(","):
            result.append(test_to_skip[0:slash] + part)
    return result

def _conformance_test_args(data, skip_tests, use_planner):
    return [
        "-Ddev.cel.conformance.ConformanceTests.skip_tests={}".format(",".join(_expand_tests_to_skip(skip_tests))),
        "-Ddev.cel.conformance.ConformanceTests.tests={}".format(",".join(["$(location {})".format(t) for t in data])),
        "-Ddev.cel.conformance.ConformanceTests.use_planner={}".format("true" if use_planner else "false"),
    ]

MODE = struct(
    # Standard test execution against HEAD
    TEST = "test",
    # Executes conformance test against published jar in maven central
    MAVEN_TEST = "maven_test",
    # Dashboard mode
    DASHBOARD = "dashboard",
)

def conformance_test(name, data, mode = MODE.TEST, skip_tests = [], use_planner = False):
    """Executes conformance tests

    Args:
       name: unique label for the java_test
       data: A list of test data files
       mode: An enum that determines the test configuration.
           - `MODE.TEST` (default): Runs the conformance tests
           - `MODE.DASHBOARD`: Runs the conformance tests for displaying on dashboard.
           - `MODE.MAVEN_TEST`: Runs the conformance tests against published JAR in maven central.
       skip_tests: A list of strings, where each string is the name of a test file to
                   exclude from the run.
    """
    if mode == MODE.DASHBOARD:
        java_test(
            name = "_" + name,
            jvm_flags = _conformance_test_args(data, skip_tests, use_planner),
            data = data,
            size = "small",
            test_class = "dev.cel.conformance.ConformanceTests",
            runtime_deps = ["//conformance/src/test/java/dev/cel/conformance:run"],
            tags = [
                "manual",
                "notap",
            ],
        )

        sh_test(
            name = name,
            size = "small",
            srcs = ["//conformance/src/test/java/dev/cel/conformance:conformance_test.sh"],
            args = ["$(location :_" + name + ")"],
            data = [":_" + name],
            tags = [
                "guitar",
                "notap",
            ],
        )
    elif mode == MODE.TEST:
        java_test(
            name = name,
            jvm_flags = _conformance_test_args(data, skip_tests, use_planner),
            data = data,
            size = "small",
            test_class = "dev.cel.conformance.ConformanceTests",
            runtime_deps = ["//conformance/src/test/java/dev/cel/conformance:run"],
        )
    elif mode == MODE.MAVEN_TEST:
        java_test(
            name = name,
            jvm_flags = _conformance_test_args(data, skip_tests, use_planner),
            data = data,
            size = "small",
            test_class = "dev.cel.conformance.ConformanceTests",
            tags = ["conformance_maven"],
            runtime_deps = ["//conformance/src/test/java/dev/cel/conformance:run_maven_jar"],
        )
    else:
        fail("Unknown mode specified: %s." % mode)
