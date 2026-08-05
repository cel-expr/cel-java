#!/bin/bash
# Copyright 2024 Google LLC
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

# Script ran as part of Github CEL-Java CI to verify that the runtime jar does not contain unwanted dependencies.

function checkUnwantedDeps {
  target="$1"
  unwanted_dep="$2"

  query="bazel query 'deps(${target})' --notool_deps --noimplicit_deps --output graph"
  deps=$(eval $query)

  if echo "$deps" | grep "$unwanted_dep" > /dev/null; then
    echo -e "$target contains unwanted dependency: $unwanted_dep!\n"
    echo "$(echo "$deps" | grep "$unwanted_dep")"
    exit 1
  fi
}

# Do not include generated CEL protos in the jar
checkUnwantedDeps '//publish:cel_runtime' '@cel_spec'

# cel_runtime does not support protolite
checkUnwantedDeps '//publish:cel_runtime' 'protobuf_java_util'
checkUnwantedDeps '//publish:cel' 'protobuf_java_util'

# cel_runtime shouldn't depend on antlr
checkUnwantedDeps '//publish:cel_runtime' '@maven//:org_antlr_antlr4_runtime'

# cel_runtime shouldn't depend on the protobuf_lite runtime
checkUnwantedDeps '//publish:cel_runtime' '@maven_android//:com_google_protobuf_protobuf_javalite'
checkUnwantedDeps '//publish:cel' '@maven_android//:com_google_protobuf_protobuf_javalite'

# cel_runtime_android shouldn't depend on the full protobuf runtime or antlr
checkUnwantedDeps '//publish:cel_runtime_android' '@maven//:com_google_protobuf_protobuf_java'
checkUnwantedDeps '//publish:cel_runtime_android' '@maven//:org_antlr_antlr4_runtime'

# cel shouldn't depend on the verifier
checkUnwantedDeps '//publish:cel' '//verifier/'

exit 0
