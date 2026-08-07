#!/bin/bash
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

#
# Publishes CEL to a Maven repository.
#
# To push locally, just run the script. On MacOS by default, the artifact lives in ~/.m2/repository
# To publish a SNAPSHOT version, run with -snapshot (-s) flag: "publish.sh --snapshot". Snapshots can be found in https://s01.oss.sonatype.org/#nexus-search;quick~dev.cel
# To publish a RELEASE version remotely, run with -release (-r) flag: "publish.sh --release". Note that released maven artifacts are permanent and can never be deleted under any circumstances.

# Note, to publish remotely:
# 1. You must create a pgp certificate and upload it to keyserver.ubuntu.com. See https://blog.sonatype.com/2010/01/how-to-generate-pgp-signatures-with-maven/
# 2. You will need to enter the key's password. The prompt appears in GUI, not in terminal. The publish operation will eventually timeout if the password is not entered.

# Note, to run script: Bazel and jq are required

ALL_TARGETS=("//publish:cel_common.publish" "//publish:cel.publish" "//publish:cel_compiler.publish" "//publish:cel_runtime.publish" "//publish:cel_v1alpha1.publish" "//publish:cel_protobuf.publish" "//publish:cel_runtime_android.publish" "//publish:cel_verifier.publish" "//publish:cel_verifier_cli.publish")
JDK8_FLAGS="--java_language_version=8 --java_runtime_version=8"

function publish_maven_remote() {
  maven_repo_url=$1
  # Credentials should be read from maven config (settings.xml) once it
  # is supported by bazelbuild:
  # https://github.com/bazelbuild/rules_jvm_external/issues/679
  read -p "maven_user: " maven_user
  read -s -p "maven_password: " maven_password

  # Upload artifacts to staging repository
  for PUBLISH_TARGET in "${ALL_TARGETS[@]}"
  do
       bazel run --stamp \
         --define "maven_repo=$maven_repo_url" \
         --define gpg_sign=true \
         --define "maven_user=$maven_user" \
         --define "maven_password=$maven_password" \
         $PUBLISH_TARGET \
         $JDK8_FLAGS
  done

  # Begin creating a staging deployment in central maven
  auth_token=$(printf "%s:%s" "$maven_user" "$maven_password" | base64)
  repository_key=$(curl -s -X GET \
    -H "Authorization:Bearer $auth_token" \
    "https://ossrh-staging-api.central.sonatype.com/manual/search/repositories?ip=any&profile_id=dev.cel" | \
    jq -r '.repositories[] | select(.state=="open") | .key' | head -n 1)
  echo ""
  if [[ -n "$repository_key" ]]; then
    echo "Open repository key:"
    echo "$repository_key"

    echo "Creating deployment..."
    post_response=$(curl -s -w "\n%{http_code}" -X POST \
      -H "Authorization: Bearer $auth_token" \
      "https://ossrh-staging-api.central.sonatype.com/manual/upload/repository/$repository_key")

    http_code=$(tail -n1 <<< "$post_response")
    response_body=$(sed '$ d' <<< "$post_response")

    echo "----------------------------------------"
    echo "Deployment API Response (HTTP Status: $http_code):"
    echo "$response_body"
    echo "----------------------------------------"
    echo ""
    echo "Proceed to https://central.sonatype.com/publishing/deployments to finalize publishing."
  else
    echo "No open repository was found. Likely an indication that artifacts were not uploaded."
  fi
}

version=$(<cel_version.bzl)
version=$(echo $version | cut -f2 -d = | xargs)

flag=$1
if [ "$flag" == "--snapshot" ] || [ "$flag" == "-s" ]; then
  if ! [[ $version == *"-SNAPSHOT"* ]]; then
    echo "Unable to publish. Please append -SNAPSHOT suffix to CEL Version"
    exit 1;
  fi
  echo "Publishing a SNAPSHOT version: $version to remote Maven repository"
  publish_maven_remote "https://central.sonatype.com/repository/maven-snapshots/"
elif [ "$flag" == "--release" ] || [ "$flag" == "-r" ]; then
  if [[ $version == *"-SNAPSHOT"* ]]; then
   echo "Unable to publish. Please remove -SNAPSHOT suffix from CEL Version"
   exit 1;
  fi
  printf "This will publish the following CEL Version to the Central Maven Repository:\n\n%s\n\nTHIS CANNOT BE UNDONE! PLEASE DOUBLE CHECK THE VERSION AT THIS TIME:\n(I.E: Check that the version is appropriately incremented based on Semantic Versioning).\n\n" "$version"
 while true; do
     read -p "Proceed (Y/N)? " yn
     case $yn in
         [Yy]* )
           publish_maven_remote "https://ossrh-staging-api.central.sonatype.com/service/local/staging/deploy/maven2/"
           break;;
         [Nn]* ) exit;;
         * ) echo "Please answer yes or no.";;
     esac
 done
else
 local_maven_repo=$HOME/.m2/repository
 echo "Pushing to local Maven repository $local_maven_repo"
  for PUBLISH_TARGET in "${ALL_TARGETS[@]}"
  do
     bazel run --define "maven_repo=file://$local_maven_repo" $PUBLISH_TARGET $JDK8_FLAGS
  done
fi
