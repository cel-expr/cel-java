#!/bin/bash
# Path: //third_party/java/cel/verifier/tools/run_verifier.sh
#
# This script is used by the cel_verifier_test macro to invoke the
# underlying Java binary (CelVerifierTool) with the arguments passed to the macro.
# It uses 'find' to locate the binary in the runfiles directory, which works
# in both Google3 and Bazel environments.

die() {
  echo "ERROR: $*" >&2
  exit 1
}

if [ -z "$1" ]; then
  die "Verifier binary path not provided as first argument."
fi

VERIFIER_BINARY="$1"
shift

# Execute the verifier binary with all passed arguments.
"$VERIFIER_BINARY" "$@"
ACTUAL_EXIT_CODE=$?

# Default to expecting 0 (VERIFIED) if env is not set
ALLOWED_CODES=${ALLOWED_EXIT_CODES:-"0"}

# Check if the actual exit code is in the allowed list
if [[ ",$ALLOWED_CODES," =~ ",$ACTUAL_EXIT_CODE," ]]; then
  echo "Verification finished with status code $ACTUAL_EXIT_CODE (Allowed: $ALLOWED_CODES)"
  exit 0
else
  echo "ERROR: Verification finished with unexpected status code $ACTUAL_EXIT_CODE (Allowed: $ALLOWED_CODES)" >&2
  exit 1
fi
