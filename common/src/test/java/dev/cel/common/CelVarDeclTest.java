// Copyright 2023 Google LLC
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

package dev.cel.common;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.extensions.proto.ProtoTruth.assertThat;

import dev.cel.expr.Constant;
import dev.cel.expr.Decl;
import dev.cel.expr.Decl.IdentDecl;
import dev.cel.expr.Type;
import dev.cel.expr.Type.PrimitiveType;
import dev.cel.common.ast.CelConstant;
import dev.cel.common.types.SimpleType;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class CelVarDeclTest {

  @Test
  public void celVarBuilder_success() {
    CelVarDecl stringVar =
        CelVarDecl.newBuilder()
            .setName("ident")
            .setType(SimpleType.STRING)
            .setDoc("doc")
            .setConstant(CelConstant.ofValue("str"))
            .build();

    assertThat(stringVar.name()).isEqualTo("ident");
    assertThat(stringVar.type()).isEqualTo(SimpleType.STRING);
    assertThat(stringVar.doc()).isEqualTo("doc");
    assertThat(stringVar.constant()).hasValue(CelConstant.ofValue("str"));
  }

  @Test
  public void celVarBuilder_clearConstant() {
    CelVarDecl varDecl =
        CelVarDecl.newBuilder()
            .setName("ident")
            .setType(SimpleType.STRING)
            .setConstant(CelConstant.ofValue("str"))
            .clearConstant()
            .build();

    assertThat(varDecl.constant()).isEmpty();
  }

  @Test
  public void newVarDeclaration_success() {
    CelVarDecl intVar = CelVarDecl.newVarDeclaration("ident", SimpleType.INT);

    assertThat(intVar.name()).isEqualTo("ident");
    assertThat(intVar.type()).isEqualTo(SimpleType.INT);
    assertThat(intVar.doc()).isEmpty();
    assertThat(intVar.constant()).isEmpty();
  }

  @Test
  public void celVarDeclToDecl_success() {
    CelVarDecl stringVar =
        CelVarDecl.newBuilder()
            .setName("ident")
            .setType(SimpleType.STRING)
            .setDoc("doc")
            .setConstant(CelConstant.ofValue("str"))
            .build();

    Decl decl = CelProtoDeclConverter.celVarDeclToDecl(stringVar);

    assertThat(decl)
        .isEqualTo(
            Decl.newBuilder()
                .setName("ident")
                .setIdent(
                    IdentDecl.newBuilder()
                        .setDoc("doc")
                        .setType(Type.newBuilder().setPrimitive(PrimitiveType.STRING))
                        .setValue(Constant.newBuilder().setStringValue("str")))
                .build());
  }
}
