# Copyright 2026 Google LLC
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

"""Macros for CEL Formal Verifier."""

load("@rules_shell//shell:sh_test.bzl", "sh_test")
load("@bazel_skylib//lib:shell.bzl", "shell")

# Mapping of friendly status names to CelVerifierTool exit codes
_STATUS_TO_EXIT_CODE = {
    "VERIFIED": "0",
    "VIOLATED": "1",
    "INCONCLUSIVE": "2",
    "ERROR": "3",
}

def _make_verifier_args(
        command,
        expression = None,
        expression_b = None,
        policy_file = None,
        variables = {},
        unknowns = [],
        timeout = 10,
        unroll_limit = 5):
    args = []
    srcs = []

    if command not in ["check-sat", "check-valid", "verify-equiv", "verify-policy"]:
        fail("Unsupported command: " + command)

    args.append(command)

    if command in ["check-sat", "check-valid"]:
        if not expression:
            fail("expression is required for " + command)
        args.append("--expr")
        args.append(expression)
    elif command == "verify-equiv":
        if not expression or not expression_b:
            fail("expression and expression_b are required for verify-equiv")
        args.append("--expr1")
        args.append(expression)
        args.append("--expr2")
        args.append(expression_b)
    elif command == "verify-policy":
        if not policy_file:
            fail("policy_file is required for verify-policy")
        args.append("--file")
        args.append("$(rootpath %s)" % policy_file)
        srcs.append(policy_file)

    for var_name, var_type in variables.items():
        args.append("--var")
        args.append("%s:%s" % (var_name, var_type))

    for unknown in unknowns:
        args.append("--unknown")
        args.append(unknown)

    args.append("--timeout")
    args.append(str(timeout))

    args.append("--unroll-limit")
    args.append(str(unroll_limit))

    args.append("--output_format")
    args.append("TEXT")

    return args, srcs

def cel_verifier_test(
        name,
        command,
        expression = None,
        expression_b = None,
        policy_file = None,
        variables = {},
        unknowns = [],
        timeout = 10,
        unroll_limit = 5,
        expected_status = ["VERIFIED"],
        **kwargs):
    """Verifies a CEL expression or policy via a test rule.

    See //verifier/tools/README.md for more details on CLI commands and options.

    Args:
      name: str name for the test
      command: str verification command. Supported commands:
        - 'check-sat': Verify satisfiability of a CEL expression & generate witness model.
        - 'check-valid': Verify validity (isAlwaysTrue) of a CEL expression & generate counterexample.
        - 'verify-equiv': Prove logical equivalence between two CEL expressions.
        - 'verify-policy': Verify policy invariants defined in a YAML policy file.
      expression: str CEL expression to verify (required for check-sat, check-valid, verify-equiv)
      expression_b: str second CEL expression for equivalence check (required for verify-equiv)
      policy_file: label of a YAML policy file to verify (required for verify-policy)
      variables: dict of var_name -> type_string (e.g., {"port": "int"})
      unknowns: list of str unknown identifiers
      timeout: int solver timeout in seconds (default 10)
      unroll_limit: int comprehension unroll limit (default 5)
      expected_status: list of str expected verification statuses (default ["VERIFIED"]).
        Supported statuses: 'VERIFIED', 'VIOLATED', 'INCONCLUSIVE', 'ERROR'.
      **kwargs: other standard Bazel attributes
    """

    args, srcs = _make_verifier_args(
        command = command,
        expression = expression,
        expression_b = expression_b,
        policy_file = policy_file,
        variables = variables,
        unknowns = unknowns,
        timeout = timeout,
        unroll_limit = unroll_limit,
    )

    tags = kwargs.pop("tags", [])
    if "nomsan" not in tags:
        tags = tags + ["nomsan"]

    allowed_codes = []
    for status in expected_status:
        if status not in _STATUS_TO_EXIT_CODE:
            fail("Unsupported status in expected_status: " + status)
        allowed_codes.append(_STATUS_TO_EXIT_CODE[status])

    allowed_codes_str = ",".join(allowed_codes)

    env = kwargs.pop("env", {})
    env["ALLOWED_EXIT_CODES"] = allowed_codes_str

    sh_test(
        name = name,
        srcs = ["//verifier/tools:run_verifier.sh"],
        args = [shell.quote("$(rootpath //verifier/src/main/java/dev/cel/verifier/tools:cel_verifier_tool)")] + [shell.quote(a) for a in args],
        data = [
            "//verifier/src/main/java/dev/cel/verifier/tools:cel_verifier_tool",
        ] + srcs,
        tags = tags,
        env = env,
        **kwargs
    )
