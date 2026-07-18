#!/bin/bash
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

set -o pipefail

TARGETS=(
  "//publish:cel"
  "//publish:cel_common"
  "//publish:cel_compiler"
  "//publish:cel_runtime"
  "//publish:cel_protobuf"
  "//publish:cel_v1alpha1"
  "//publish:cel_verifier"
)

echo "------------------------------------------------"
echo "Checking for duplicates..."
echo "------------------------------------------------"

JDK8_FLAGS="--java_language_version=8 --java_runtime_version=8"
bazel build $JDK8_FLAGS "${TARGETS[@]}" || { echo "Bazel build failed"; exit 1; }

(
  for target in "${TARGETS[@]}"; do
    # Locate the jar
    jar_path=$(bazel cquery "$target" --output=files 2>/dev/null | grep '\-project.jar$')

    if [[ -z "$jar_path" ]]; then
      echo "Error: Could not find -project.jar for target $target" >&2
      exit 1
    fi

    # Fix relative paths if running from a subdir.
    if [[ ! -f "$jar_path" ]]; then
        if [[ -f "../../$jar_path" ]]; then
           jar_path="../../$jar_path"
        else
           echo "Error: File not found at $jar_path" >&2
           exit 1
        fi
    fi

    echo "Inspecting: $target" >&2

    # Extract classes and append the target name to the end of the line
    # Format: dev/cel/expr/Expr.class //publish:cel_compiler
    jar tf "$jar_path" | grep "\.class$" | awk -v tgt="$target" '{print $0, tgt}'
  done
) | awk '
  # $1 is the Class Name, $2 is the Target Name
  seen[$1] {
    print "❌ DUPLICATE FOUND: " $1
    print "   Present in: " seen[$1]
    print "   And in:     " $2
    dupe=1
    next
  }
  { seen[$1] = $2 }

  END { if (dupe) exit 2 }
'

EXIT_CODE=$?

if [ $EXIT_CODE -eq 0 ]; then
  echo "✅ Success: No duplicate classes found."
elif [ $EXIT_CODE -eq 2 ]; then
  echo "⛔ Failure: Duplicate classes detected."
else
  echo "💥 Error: An unexpected error occurred (e.g., missing jar files). Exit Code: $EXIT_CODE"
fi

exit $EXIT_CODE

