// Copyright 2026 Google LLC
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

package dev.cel.testing;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import dev.cel.expr.Constant;
import dev.cel.expr.Expr;
import dev.cel.expr.SourceInfo;
import com.google.protobuf.Descriptors;
import com.google.protobuf.NullValue;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class CelExprKindAndIdAdornerTest {

  @Test
  public void findOneofByName_notFound_throwsException() {
    Descriptors.Descriptor descriptor = Constant.getDescriptor();
    assertThrows(
        IllegalArgumentException.class,
        () -> CelExprKindAndIdAdorner.findOneofByName(descriptor, "unknown_oneof"));
  }

  @Test
  public void getContainedName_nestedEnum_returnsContainedName() {
    assertThat(
            CelExprKindAndIdAdorner.getContainedName(
                SourceInfo.Extension.Component.getDescriptor()))
        .isEqualTo("SourceInfo.Extension.Component");
  }

  @Test
  public void adorn_constantNull() {
    CelExprKindAndIdAdorner adorner = new CelExprKindAndIdAdorner();
    Expr expr =
        Expr.newBuilder()
            .setId(1L)
            .setConstExpr(Constant.newBuilder().setNullValue(NullValue.NULL_VALUE))
            .build();

    assertThat(adorner.adorn(expr)).isEqualTo("^#1:NullValue#");
  }

  @Test
  public void adorn_constantPrimitive() {
    CelExprKindAndIdAdorner adorner = new CelExprKindAndIdAdorner();
    Expr expr =
        Expr.newBuilder()
            .setId(2L)
            .setConstExpr(Constant.newBuilder().setInt64Value(42L))
            .build();

    assertThat(adorner.adorn(expr)).isEqualTo("^#2:int64#");
  }

  @Test
  public void adorn_exprKind() {
    CelExprKindAndIdAdorner adorner = new CelExprKindAndIdAdorner();
    Expr expr =
        Expr.newBuilder()
            .setId(3L)
            .setIdentExpr(Expr.Ident.newBuilder().setName("foo"))
            .build();

    assertThat(adorner.adorn(expr)).isEqualTo("^#3:Expr.Ident#");
  }

  @Test
  public void adorn_structEntry() {
    CelExprKindAndIdAdorner adorner = new CelExprKindAndIdAdorner();
    Expr.CreateStruct.Entry entry =
        Expr.CreateStruct.Entry.newBuilder().setId(4L).build();

    assertThat(adorner.adorn(entry)).isEqualTo("^#4:Expr.CreateStruct.Entry#");
  }

  @Test
  public void adorn_macroCall() {
    SourceInfo sourceInfo =
        SourceInfo.newBuilder()
            .putMacroCalls(
                5L,
                Expr.newBuilder()
                    .setCallExpr(Expr.Call.newBuilder().setFunction("has"))
                    .build())
            .build();
    CelExprKindAndIdAdorner adorner = new CelExprKindAndIdAdorner(sourceInfo);
    Expr expr = Expr.newBuilder().setId(5L).build();

    assertThat(adorner.adorn(expr)).isEqualTo("^#5:has#");
  }

  @Test
  public void convertMacroCallsToString() {
    SourceInfo sourceInfo =
        SourceInfo.newBuilder()
            .putMacroCalls(
                1L,
                Expr.newBuilder()
                    .setId(1L)
                    .setCallExpr(Expr.Call.newBuilder().setFunction("has"))
                    .build())
            .build();

    assertThat(CelExprKindAndIdAdorner.convertMacroCallsToString(sourceInfo))
        .contains("^#1:has#");
  }
}
