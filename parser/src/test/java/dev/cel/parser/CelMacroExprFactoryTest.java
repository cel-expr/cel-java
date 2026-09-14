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

package dev.cel.parser;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.primitives.UnsignedLong;
import dev.cel.common.CelIssue;
import dev.cel.common.CelSourceLocation;
import dev.cel.common.ast.CelConstant;
import dev.cel.common.ast.CelExpr;
import dev.cel.common.ast.CelExpr.CelMap;
import dev.cel.common.ast.CelExpr.CelStruct.Entry;
import dev.cel.common.ast.CelExpr.ExprKind.Kind;
import dev.cel.common.internal.Constants;
import dev.cel.common.values.CelByteString;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class CelMacroExprFactoryTest {

  private static final class TestCelExprFactory extends CelMacroExprFactory {

    private long exprId;

    private TestCelExprFactory() {
      exprId = 0L;
    }

    @Override
    public long copyExprId(long unused) {
      return nextExprId();
    }

    @Override
    public long nextExprId() {
      return ++exprId;
    }

    @Override
    protected CelSourceLocation currentSourceLocationForMacro() {
      return CelSourceLocation.NONE;
    }

    @Override
    public CelExpr reportError(CelIssue issue) {
      return CelExpr.newBuilder().setId(nextExprId()).setConstant(Constants.ERROR).build();
    }

    @Override
    public String getAccumulatorVarName() {
      return "@result";
    }

    @Override
    protected CelSourceLocation getSourceLocation(long exprId) {
      return CelSourceLocation.NONE;
    }

    public void reset() {
      exprId = 0L;
    }
  }

  @Test
  public void newBoolLiteral_true_returnsBoolConstant() {
    TestCelExprFactory exprFactory = new TestCelExprFactory();
    CelExpr expr = exprFactory.newBoolLiteral(true);
    assertThat(expr.id()).isEqualTo(1L);
    assertThat(expr.exprKind().getKind()).isEqualTo(Kind.CONSTANT);
    assertThat(expr.constant().getKind()).isEqualTo(CelConstant.Kind.BOOLEAN_VALUE);
    assertThat(expr.constant().booleanValue()).isTrue();
  }

  @Test
  public void newBoolLiteral_false_returnsBoolConstant() {
    TestCelExprFactory exprFactory = new TestCelExprFactory();
    CelExpr expr = exprFactory.newBoolLiteral(false);
    assertThat(expr.id()).isEqualTo(1L);
    assertThat(expr.exprKind().getKind()).isEqualTo(Kind.CONSTANT);
    assertThat(expr.constant().getKind()).isEqualTo(CelConstant.Kind.BOOLEAN_VALUE);
    assertThat(expr.constant().booleanValue()).isFalse();
  }

  @Test
  public void newBytesLiteral_returnsBytesConstant() {
    TestCelExprFactory exprFactory = new TestCelExprFactory();
    CelExpr expr = exprFactory.newBytesLiteral("foo");
    assertThat(expr.id()).isEqualTo(1L);
    assertThat(expr.exprKind().getKind()).isEqualTo(Kind.CONSTANT);
    assertThat(expr.constant().getKind()).isEqualTo(CelConstant.Kind.BYTES_VALUE);
    assertThat(expr.constant().bytesValue()).isEqualTo(CelByteString.copyFromUtf8("foo"));
  }

  @Test
  public void newBytesLiteral_overloadsAreEquivalent() {
    TestCelExprFactory exprFactory = new TestCelExprFactory();
    CelExpr want = exprFactory.newBytesLiteral("foo");
    exprFactory.reset();
    assertThat(exprFactory.newBytesLiteral(new byte[] {'f', 'o', 'o'})).isEqualTo(want);
  }

  @Test
  public void newDoubleLiteral_returnsDoubleConstant() {
    TestCelExprFactory exprFactory = new TestCelExprFactory();
    CelExpr expr = exprFactory.newDoubleLiteral(1.0);
    assertThat(expr.id()).isEqualTo(1L);
    assertThat(expr.exprKind().getKind()).isEqualTo(Kind.CONSTANT);
    assertThat(expr.constant().getKind()).isEqualTo(CelConstant.Kind.DOUBLE_VALUE);
    assertThat(expr.constant().doubleValue()).isEqualTo(1.0);
  }

  @Test
  public void newIntLiteral_returnsInt64Constant() {
    TestCelExprFactory exprFactory = new TestCelExprFactory();
    CelExpr expr = exprFactory.newIntLiteral(2L);
    assertThat(expr.id()).isEqualTo(1L);
    assertThat(expr.exprKind().getKind()).isEqualTo(Kind.CONSTANT);
    assertThat(expr.constant().getKind()).isEqualTo(CelConstant.Kind.INT64_VALUE);
    assertThat(expr.constant().int64Value()).isEqualTo(2L);
  }

  @Test
  public void newStringLiteral_returnsStringConstant() {
    TestCelExprFactory exprFactory = new TestCelExprFactory();
    CelExpr expr = exprFactory.newStringLiteral("foo");
    assertThat(expr.id()).isEqualTo(1L);
    assertThat(expr.exprKind().getKind()).isEqualTo(Kind.CONSTANT);
    assertThat(expr.constant().getKind()).isEqualTo(CelConstant.Kind.STRING_VALUE);
    assertThat(expr.constant().stringValue()).isEqualTo("foo");
  }

  @Test
  public void newUintLiteral_returnsUint64Constant() {
    TestCelExprFactory exprFactory = new TestCelExprFactory();
    CelExpr expr = exprFactory.newUintLiteral(2L);
    assertThat(expr.id()).isEqualTo(1L);
    assertThat(expr.exprKind().getKind()).isEqualTo(Kind.CONSTANT);
    assertThat(expr.constant().getKind()).isEqualTo(CelConstant.Kind.UINT64_VALUE);
    assertThat(expr.constant().uint64Value()).isEqualTo(UnsignedLong.valueOf(2L));
  }

  @Test
  public void newList_returnsList() {
    TestCelExprFactory exprFactory = new TestCelExprFactory();
    CelExpr element = exprFactory.newStringLiteral("foo");
    CelExpr expr = exprFactory.newList(element);
    assertThat(expr.id()).isEqualTo(2L);
    assertThat(expr.exprKind().getKind()).isEqualTo(Kind.LIST);
    assertThat(expr.list().elements()).hasSize(1);
    assertThat(expr.list().elements()).containsExactly(element);
  }

  @Test
  public void newMap_returnsMap() {
    TestCelExprFactory exprFactory = new TestCelExprFactory();
    CelMap.Entry entry =
        exprFactory.newMapEntry(
            exprFactory.newStringLiteral("foo"), exprFactory.newStringLiteral("bar"));
    CelExpr expr = exprFactory.newMap(entry);
    assertThat(expr.id()).isEqualTo(4L);
    assertThat(expr.exprKind().getKind()).isEqualTo(Kind.MAP);
    assertThat(expr.map().entries()).containsExactly(entry);
  }

  @Test
  public void newMapEntry_returnsMapEntry() {
    TestCelExprFactory exprFactory = new TestCelExprFactory();
    CelExpr key = exprFactory.newStringLiteral("foo");
    CelExpr value = exprFactory.newStringLiteral("bar");
    CelMap.Entry entry = exprFactory.newMapEntry(key, value);
    assertThat(entry.id()).isEqualTo(3L);
    assertThat(entry.value()).isEqualTo(value);
  }

  @Test
  public void newMessage_returnsMessage() {
    TestCelExprFactory exprFactory = new TestCelExprFactory();
    Entry field = exprFactory.newMessageField("foo", exprFactory.newStringLiteral("bar"));
    CelExpr expr = exprFactory.newMessage("google.example.Baz", field);
    assertThat(expr.id()).isEqualTo(3L);
    assertThat(expr.exprKind().getKind()).isEqualTo(Kind.STRUCT);
    assertThat(expr.struct().messageName()).isEqualTo("google.example.Baz");
    assertThat(expr.struct().entries()).containsExactly(field);
  }

  @Test
  public void newMessageField_returnsMessageField() {
    TestCelExprFactory exprFactory = new TestCelExprFactory();
    CelExpr value = exprFactory.newStringLiteral("bar");
    Entry field = exprFactory.newMessageField("foo", value);
    assertThat(field.id()).isEqualTo(2L);
    assertThat(field.value()).isEqualTo(value);
  }

  @Test
  public void fold_returnsComprehension() {
    TestCelExprFactory exprFactory = new TestCelExprFactory();
    CelExpr iterRange = exprFactory.newList();
    CelExpr accuInit = exprFactory.newIntLiteral(0L);
    CelExpr loopCondition = exprFactory.newBoolLiteral(true);
    CelExpr loopStep = exprFactory.newIntLiteral(1L);
    CelExpr result = exprFactory.newIdentifier("foo");
    CelExpr expr = exprFactory.fold("i", iterRange, "a", accuInit, loopCondition, loopStep, result);
    assertThat(expr.id()).isEqualTo(6L);
    assertThat(expr.exprKind().getKind()).isEqualTo(Kind.COMPREHENSION);
    assertThat(expr.comprehension().iterVar()).isEqualTo("i");
    assertThat(expr.comprehension().iterVar2()).isEmpty();
    assertThat(expr.comprehension().iterRange()).isEqualTo(iterRange);
    assertThat(expr.comprehension().accuVar()).isEqualTo("a");
    assertThat(expr.comprehension().accuInit()).isEqualTo(accuInit);
    assertThat(expr.comprehension().loopCondition()).isEqualTo(loopCondition);
    assertThat(expr.comprehension().loopStep()).isEqualTo(loopStep);
    assertThat(expr.comprehension().result()).isEqualTo(result);
  }

  @Test
  public void fold_returnsTwoVariableComprehension() {
    TestCelExprFactory exprFactory = new TestCelExprFactory();
    CelExpr iterRange = exprFactory.newList();
    CelExpr accuInit = exprFactory.newIntLiteral(0L);
    CelExpr loopCondition = exprFactory.newBoolLiteral(true);
    CelExpr loopStep = exprFactory.newIntLiteral(1L);
    CelExpr result = exprFactory.newIdentifier("foo");
    CelExpr expr =
        exprFactory.fold("i", "j", iterRange, "a", accuInit, loopCondition, loopStep, result);
    assertThat(expr.id()).isEqualTo(6L);
    assertThat(expr.exprKind().getKind()).isEqualTo(Kind.COMPREHENSION);
    assertThat(expr.comprehension().iterVar()).isEqualTo("i");
    assertThat(expr.comprehension().iterVar2()).isEqualTo("j");
    assertThat(expr.comprehension().iterRange()).isEqualTo(iterRange);
    assertThat(expr.comprehension().accuVar()).isEqualTo("a");
    assertThat(expr.comprehension().accuInit()).isEqualTo(accuInit);
    assertThat(expr.comprehension().loopCondition()).isEqualTo(loopCondition);
    assertThat(expr.comprehension().loopStep()).isEqualTo(loopStep);
    assertThat(expr.comprehension().result()).isEqualTo(result);
  }

  @Test
  public void newIdentifier_returnsIdentifier() {
    TestCelExprFactory exprFactory = new TestCelExprFactory();
    CelExpr expr = exprFactory.newIdentifier("foo");
    assertThat(expr.id()).isEqualTo(1L);
    assertThat(expr.exprKind().getKind()).isEqualTo(Kind.IDENT);
    assertThat(expr.ident().name()).isEqualTo("foo");
  }

  @Test
  public void newGlobalCall_returnsGlobalCall() {
    TestCelExprFactory exprFactory = new TestCelExprFactory();
    CelExpr argument = exprFactory.newStringLiteral("bar");
    CelExpr expr = exprFactory.newGlobalCall("foo", argument);
    assertThat(expr.id()).isEqualTo(2L);
    assertThat(expr.exprKind().getKind()).isEqualTo(Kind.CALL);
    assertThat(expr.call().target()).isEmpty();
    assertThat(expr.call().function()).isEqualTo("foo");
    assertThat(expr.call().args()).containsExactly(argument);
  }

  @Test
  public void newReceiverCall_returnsReceiverCall() {
    TestCelExprFactory exprFactory = new TestCelExprFactory();
    CelExpr target = exprFactory.newIdentifier("baz");
    CelExpr argument = exprFactory.newStringLiteral("bar");
    CelExpr expr = exprFactory.newReceiverCall("foo", target, argument);
    assertThat(expr.id()).isEqualTo(3L);
    assertThat(expr.exprKind().getKind()).isEqualTo(Kind.CALL);
    assertThat(expr.call().target()).hasValue(target);
    assertThat(expr.call().function()).isEqualTo("foo");
    assertThat(expr.call().args()).containsExactly(argument);
  }

  @Test
  public void newSelect_returnsSelect() {
    TestCelExprFactory exprFactory = new TestCelExprFactory();
    CelExpr operand = exprFactory.newIdentifier("foo");
    CelExpr expr = exprFactory.newSelect(operand, "bar", false);
    assertThat(expr.id()).isEqualTo(2L);
    assertThat(expr.exprKind().getKind()).isEqualTo(Kind.SELECT);
    assertThat(expr.select().operand()).isEqualTo(operand);
    assertThat(expr.select().field()).isEqualTo("bar");
    assertThat(expr.select().testOnly()).isFalse();
  }
}
