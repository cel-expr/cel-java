// Copyright 2022 Google LLC
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package dev.cel.checker;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.extensions.proto.ProtoTruth.assertThat;
import static dev.cel.common.CelOverloadDecl.newGlobalOverload;
import static dev.cel.common.CelOverloadDecl.newMemberOverload;

import dev.cel.expr.Decl.FunctionDecl.Overload;
import com.google.common.collect.ImmutableList;
import dev.cel.common.CelOverloadDecl;
import dev.cel.common.CelProtoDeclConverter;
import dev.cel.common.types.CelProtoTypes;
import dev.cel.common.types.SimpleType;
import dev.cel.common.types.TypeParamType;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class CelOverloadDeclTest {
  @Test
  public void newGlobalFunction_success() {
    CelOverloadDecl overloadDecl =
        newGlobalOverload(
            "overloadId",
            TypeParamType.create("B"),
            SimpleType.STRING,
            SimpleType.ANY,
            TypeParamType.create("A"));

    assertThat(overloadDecl.overloadId()).isEqualTo("overloadId");
    assertThat(overloadDecl.isInstanceFunction()).isFalse();
    assertThat(overloadDecl.resultType()).isEqualTo(TypeParamType.create("B"));
    assertThat(overloadDecl.parameterTypes())
        .containsExactly(SimpleType.STRING, SimpleType.ANY, TypeParamType.create("A"));
    assertThat(overloadDecl.typeParameterNames()).containsExactly("A", "B");
  }

  @Test
  public void newMemberFunction_success() {
    CelOverloadDecl overloadDecl =
        newMemberOverload(
            "overloadId",
            TypeParamType.create("B"),
            SimpleType.STRING,
            SimpleType.ANY,
            TypeParamType.create("A"));

    assertThat(overloadDecl.overloadId()).isEqualTo("overloadId");
    assertThat(overloadDecl.isInstanceFunction()).isTrue();
    assertThat(overloadDecl.resultType()).isEqualTo(TypeParamType.create("B"));
    assertThat(overloadDecl.parameterTypes())
        .containsExactly(SimpleType.STRING, SimpleType.ANY, TypeParamType.create("A"));
    assertThat(overloadDecl.typeParameterNames()).containsExactly("A", "B");
  }

  @Test
  public void toProtoOverload_withTypeParams() {
    CelOverloadDecl celOverloadDecl =
        CelOverloadDecl.newBuilder()
            .setOverloadId("overloadId")
            .setResultType(TypeParamType.create("A"))
            .addParameterTypes(SimpleType.STRING, SimpleType.DOUBLE, TypeParamType.create("B"))
            .setIsInstanceFunction(true)
            .build();

    Overload protoOverload = CelProtoDeclConverter.celOverloadToOverload(celOverloadDecl);

    assertThat(protoOverload.getOverloadId()).isEqualTo("overloadId");
    assertThat(protoOverload.getIsInstanceFunction()).isTrue();
    assertThat(protoOverload.getResultType()).isEqualTo(CelProtoTypes.createTypeParam("A"));
    assertThat(protoOverload.getParamsList())
        .containsExactly(
            CelProtoTypes.STRING, CelProtoTypes.DOUBLE, CelProtoTypes.createTypeParam("B"));
    assertThat(protoOverload.getTypeParamsList()).containsExactly("A", "B");
  }

  @Test
  public void setParameterTypes_doesNotDedupe() {
    CelOverloadDecl overloadDecl =
        CelOverloadDecl.newBuilder()
            .setParameterTypes(
                ImmutableList.of(
                    SimpleType.STRING, SimpleType.STRING, SimpleType.STRING, SimpleType.INT))
            .setOverloadId("overload_id")
            .setIsInstanceFunction(true)
            .setResultType(SimpleType.DYN)
            .build();

    assertThat(overloadDecl.parameterTypes())
        .containsExactly(SimpleType.STRING, SimpleType.STRING, SimpleType.STRING, SimpleType.INT)
        .inOrder();
  }
}
