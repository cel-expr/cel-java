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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.primitives.Ints.min;

import cel.parser.internal.CELBaseVisitor;
import cel.parser.internal.CELLexer;
import cel.parser.internal.CELParser;
import cel.parser.internal.CELParser.BoolFalseContext;
import cel.parser.internal.CELParser.BoolTrueContext;
import cel.parser.internal.CELParser.BytesContext;
import cel.parser.internal.CELParser.CalcContext;
import cel.parser.internal.CELParser.ConditionalAndContext;
import cel.parser.internal.CELParser.ConditionalOrContext;
import cel.parser.internal.CELParser.ConstantLiteralContext;
import cel.parser.internal.CELParser.CreateListContext;
import cel.parser.internal.CELParser.CreateMapContext;
import cel.parser.internal.CELParser.CreateMessageContext;
import cel.parser.internal.CELParser.DoubleContext;
import cel.parser.internal.CELParser.EscapeIdentContext;
import cel.parser.internal.CELParser.EscapedIdentifierContext;
import cel.parser.internal.CELParser.ExprContext;
import cel.parser.internal.CELParser.ExprListContext;
import cel.parser.internal.CELParser.FieldInitializerListContext;
import cel.parser.internal.CELParser.GlobalCallContext;
import cel.parser.internal.CELParser.IdentContext;
import cel.parser.internal.CELParser.IndexContext;
import cel.parser.internal.CELParser.IntContext;
import cel.parser.internal.CELParser.ListInitContext;
import cel.parser.internal.CELParser.LogicalNotContext;
import cel.parser.internal.CELParser.MapInitializerListContext;
import cel.parser.internal.CELParser.MemberCallContext;
import cel.parser.internal.CELParser.MemberExprContext;
import cel.parser.internal.CELParser.NegateContext;
import cel.parser.internal.CELParser.NestedContext;
import cel.parser.internal.CELParser.NullContext;
import cel.parser.internal.CELParser.OptExprContext;
import cel.parser.internal.CELParser.OptFieldContext;
import cel.parser.internal.CELParser.PrimaryExprContext;
import cel.parser.internal.CELParser.RelationContext;
import cel.parser.internal.CELParser.SelectContext;
import cel.parser.internal.CELParser.SimpleIdentifierContext;
import cel.parser.internal.CELParser.StartContext;
import cel.parser.internal.CELParser.StringContext;
import cel.parser.internal.CELParser.UintContext;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.errorprone.annotations.FormatMethod;
import com.google.errorprone.annotations.FormatString;
import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.CelIssue;
import dev.cel.common.CelOptions;
import dev.cel.common.CelSource;
import dev.cel.common.CelSourceLocation;
import dev.cel.common.CelValidationResult;
import dev.cel.common.Operator;
import dev.cel.common.ast.CelConstant;
import dev.cel.common.ast.CelExpr;
import dev.cel.common.internal.CodePointStream;
import dev.cel.common.internal.Constants;
import java.text.ParseException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.antlr.v4.runtime.ANTLRErrorListener;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.DefaultErrorStrategy;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.atn.ATNConfigSet;
import org.antlr.v4.runtime.dfa.DFA;
import org.antlr.v4.runtime.misc.ParseCancellationException;
import org.antlr.v4.runtime.tree.ErrorNode;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.ParseTreeListener;
import org.antlr.v4.runtime.tree.TerminalNode;

/**
 * Parses a CEL expression and returns an abstraction syntax tree in the form of
 * google.api.expr.ParsedExpr. Currently this uses ANTLRv4 for lexing and parsing.
 */
final class Parser extends CELBaseVisitor<CelExpr> {

  private static final CelExpr ERROR = CelExpr.newBuilder().setConstant(Constants.ERROR).build();
  private static final ImmutableSet<String> RESERVED_IDS =
      ImmutableSet.of(
          "as",
          "break",
          "const",
          "continue",
          "else",
          "false",
          "for",
          "function",
          "if",
          "import",
          "in",
          "let",
          "loop",
          "package",
          "namespace",
          "null",
          "return",
          "true",
          "var",
          "void",
          "while");
  private static final String ACCUMULATOR_NAME = "__result__";
  private static final String HIDDEN_ACCUMULATOR_NAME = "@result";

  static CelValidationResult parse(CelParserImpl parser, CelSource source, CelOptions options) {
    if (source.getContent().size() > options.maxExpressionCodePointSize()) {
      return new CelValidationResult(
          source,
          ImmutableList.of(
              CelIssue.formatError(
                  CelSourceLocation.NONE,
                  String.format(
                      "expression code point size exceeds limit: size: %d, limit %d",
                      source.getContent().size(), options.maxExpressionCodePointSize()))));
    }
    CELLexer antlrLexer =
        new CELLexer(new CodePointStream(source.getDescription(), source.getContent()));
    CELParser antlrParser = new CELParser(new CommonTokenStream(antlrLexer));
    CelSource.Builder sourceInfo = source.toBuilder();
    sourceInfo.setDescription(source.getDescription());
    ExprFactory exprFactory =
        new ExprFactory(
            antlrParser,
            sourceInfo,
            options.enableHiddenAccumulatorVar() ? HIDDEN_ACCUMULATOR_NAME : ACCUMULATOR_NAME,
            options.maxParseExpressionNodeCount());
    Parser parserImpl = new Parser(parser, options, sourceInfo, exprFactory);
    ErrorListener errorListener = new ErrorListener(exprFactory);
    antlrLexer.removeErrorListeners();
    antlrParser.removeErrorListeners();
    antlrLexer.addErrorListener(errorListener);
    antlrParser.addErrorListener(errorListener);
    antlrParser.addParseListener(
        new PerRuleRecursionListener(exprFactory, options.maxParseRecursionDepth()));
    antlrParser.setErrorHandler(
        new RecoveryLimitErrorStrategy(options.maxParseErrorRecoveryLimit()));
    CelExpr expr;
    try {
      StartContext context = checkNotNull(antlrParser.start());
      expr = checkNotNull(parserImpl.visit(context));
    } catch (ParseCancellationException parseFailure) {
      return new CelValidationResult(
          sourceInfo.build(), parseFailure, ImmutableList.copyOf(exprFactory.getIssuesList()));
    }
    return new CelValidationResult(
        CelAbstractSyntaxTree.newParsedAst(expr, sourceInfo.build()),
        ImmutableList.copyOf(exprFactory.getIssuesList()));
  }

  private final CelParserImpl parser;
  private final CelOptions options;
  private final CelSource.Builder sourceInfo;
  private final ExprFactory exprFactory;

  private int recursionDepth;

  private Parser(
      CelParserImpl parser,
      CelOptions options,
      CelSource.Builder sourceInfo,
      ExprFactory exprFactory) {
    this.parser = parser;
    this.options = options;
    this.sourceInfo = sourceInfo;
    this.exprFactory = exprFactory;
  }

  @Override
  public CelExpr visit(ParseTree tree) {
    ParseTree unnestedNode = unnest(tree);
    boolean isLeftRecursiveNode = isLeftRecursiveForCountingDepths(unnestedNode);
    if (isLeftRecursiveNode) {
      checkAndIncrementRecursionDepth();
      CelExpr expr = super.visit(unnestedNode);
      decrementRecursionDepth();
      return expr;
    }

    return super.visit(unnestedNode);
  }

  @Override
  public CelExpr visitStart(StartContext context) {
    checkNotNull(context);
    if (context.e == null) {
      return exprFactory.ensureErrorsExist(context);
    }
    return visit(context.e);
  }

  @Override
  public CelExpr visitExpr(ExprContext context) {
    checkNotNull(context);
    if (context.e == null) {
      return exprFactory.ensureErrorsExist(context);
    }
    CelExpr condition = visit(context.e);
    if (context.op != null) {
      if (context.e1 == null || context.e2 == null) {
        return exprFactory.ensureErrorsExist(context);
      }
      condition =
          exprFactory
              .newExprBuilder(context.op)
              .setCall(
                  CelExpr.CelCall.newBuilder()
                      .setFunction(Operator.CONDITIONAL.getFunction())
                      .addArgs(condition)
                      .addArgs(visit(context.e1))
                      .addArgs(visit(context.e2))
                      .build())
              .build();
    }

    return condition;
  }

  @Override
  public CelExpr visitConditionalOr(ConditionalOrContext context) {
    checkNotNull(context);
    if (context.e == null) {
      return exprFactory.ensureErrorsExist(context);
    }
    CelExpr conditionalOr = visit(context.e);
    if (context.ops == null || context.ops.isEmpty()) {
      return conditionalOr;
    }
    ExpressionBalancer balancer =
        new ExpressionBalancer(Operator.LOGICAL_OR.getFunction(), conditionalOr);
    int index = 0;
    for (Token token : context.ops) {
      if (context.e1 == null || index >= context.e1.size()) {
        return exprFactory.reportError(context, "unexpected character, wanted '||'");
      }
      long operationId = exprFactory.newExprId(exprFactory.getPosition(token));
      CelExpr term = visit(context.e1.get(index));
      balancer.add(operationId, term);
      index++;
    }
    return balancer.balance();
  }

  @Override
  public CelExpr visitConditionalAnd(ConditionalAndContext context) {
    checkNotNull(context);
    if (context.e == null) {
      return exprFactory.ensureErrorsExist(context);
    }
    CelExpr conditionalAnd = visit(context.e);
    if (context.ops == null || context.ops.isEmpty()) {
      return conditionalAnd;
    }
    ExpressionBalancer balancer =
        new ExpressionBalancer(Operator.LOGICAL_AND.getFunction(), conditionalAnd);
    int index = 0;
    for (Token token : context.ops) {
      if (context.e1 == null || index >= context.e1.size()) {
        return exprFactory.reportError(context, "unexpected character, wanted '&&'");
      }
      long operationId = exprFactory.newExprId(exprFactory.getPosition(token));
      CelExpr term = visit(context.e1.get(index));
      balancer.add(operationId, term);
      index++;
    }
    return balancer.balance();
  }

  @Override
  public CelExpr visitRelation(RelationContext context) {
    checkNotNull(context);
    if (context.calc() != null) {
      return visit(context.calc());
    }
    if (context.relation() == null || context.relation().isEmpty() || context.op == null) {
      return exprFactory.ensureErrorsExist(context);
    }
    Optional<Operator> operator = Operator.find(context.op.getText());
    if (!operator.isPresent()) {
      return exprFactory.reportError(context, "operator not found");
    }
    CelExpr left = visit(context.relation(0));
    CelExpr.Builder exprBuilder = exprFactory.newExprBuilder(context.op);
    CelExpr right = visit(context.relation(1));
    return exprBuilder
        .setCall(
            CelExpr.CelCall.newBuilder()
                .setFunction(operator.get().getFunction())
                .addArgs(left)
                .addArgs(right)
                .build())
        .build();
  }

  @Override
  public CelExpr visitCalc(CalcContext context) {
    checkNotNull(context);
    if (context.unary() != null) {
      return visit(context.unary());
    }
    if (context.calc() == null || context.calc().isEmpty() || context.op == null) {
      return exprFactory.ensureErrorsExist(context);
    }
    Optional<Operator> operator = Operator.find(context.op.getText());
    if (!operator.isPresent()) {
      return exprFactory.reportError(context, "operator not found");
    }
    CelExpr left = visit(context.calc(0));
    CelExpr.Builder exprBuilder = exprFactory.newExprBuilder(context.op);
    CelExpr right = visit(context.calc(1));
    return exprBuilder
        .setCall(
            CelExpr.CelCall.newBuilder()
                .setFunction(operator.get().getFunction())
                .addArgs(left)
                .addArgs(right)
                .build())
        .build();
  }

  @Override
  public CelExpr visitMemberExpr(MemberExprContext context) {
    checkNotNull(context);
    if (context.member() == null) {
      return exprFactory.ensureErrorsExist(context);
    }
    return visit(context.member());
  }

  @Override
  public CelExpr visitLogicalNot(LogicalNotContext context) {
    checkNotNull(context);
    if (context.member() == null) {
      return exprFactory.ensureErrorsExist(context);
    }
    if (context.ops != null && options.retainRepeatedUnaryOperators()) {
      CelExpr expr = visit(context.member());
      for (int index = context.ops.size(); index > 0; --index) {
        expr =
            exprFactory
                .newExprBuilder(context.ops.get(index - 1))
                .setCall(
                    CelExpr.CelCall.newBuilder()
                        .setFunction(Operator.LOGICAL_NOT.getFunction())
                        .addArgs(expr)
                        .build())
                .build();
      }
      return expr;
    } else if (context.ops == null || context.ops.size() % 2 == 0) {
      return visit(context.member());
    }
    CelExpr.Builder exprBuilder = exprFactory.newExprBuilder(context.ops.get(0));
    CelExpr member = visit(context.member());
    return exprBuilder
        .setCall(
            CelExpr.CelCall.newBuilder()
                .setFunction(Operator.LOGICAL_NOT.getFunction())
                .addArgs(member)
                .build())
        .build();
  }

  @Override
  public CelExpr visitNegate(NegateContext context) {
    checkNotNull(context);
    if (context.member() == null) {
      return exprFactory.ensureErrorsExist(context);
    }
    if (context.ops != null && options.retainRepeatedUnaryOperators()) {
      CelExpr expr = visit(context.member());
      for (int index = context.ops.size(); index > 0; --index) {
        expr =
            exprFactory
                .newExprBuilder(context.ops.get(index - 1))
                .setCall(
                    CelExpr.CelCall.newBuilder()
                        .setFunction(Operator.NEGATE.getFunction())
                        .addArgs(expr)
                        .build())
                .build();
      }
      return expr;
    } else if (context.ops == null || context.ops.size() % 2 == 0) {
      return visit(context.member());
    }
    CelExpr.Builder exprBuilder = exprFactory.newExprBuilder(context.ops.get(0));
    CelExpr member = visit(context.member());
    return exprBuilder
        .setCall(
            CelExpr.CelCall.newBuilder()
                .setFunction(Operator.NEGATE.getFunction())
                .addArgs(member)
                .build())
        .build();
  }

  @Override
  public CelExpr visitPrimaryExpr(PrimaryExprContext context) {
    checkNotNull(context);
    if (context.primary() == null) {
      return exprFactory.ensureErrorsExist(context);
    }
    return visit(context.primary());
  }

  @Override
  public CelExpr visitSelect(SelectContext context) {
    checkNotNull(context);
    if (context.member() == null) {
      return exprFactory.ensureErrorsExist(context);
    }
    CelExpr member = visit(context.member());
    if (context.id == null) {
      return exprFactory.newExprBuilder(context).build();
    }
    String id = normalizeEscapedIdent(context.id);

    if (context.opt != null && context.opt.getText().equals("?")) {
      if (!options.enableOptionalSyntax()) {
        return exprFactory.reportError(context.op, "unsupported syntax '.?'");
      }

      CelExpr.Builder exprBuilder = exprFactory.newExprBuilder(exprFactory.getPosition(context.op));
      CelExpr.CelCall callExpr =
          CelExpr.CelCall.newBuilder()
              .setFunction(Operator.OPTIONAL_SELECT.getFunction())
              .addArgs(
                  Arrays.asList(
                      member,
                      exprFactory
                          .newExprBuilder(context)
                          .setConstant(CelConstant.ofValue(id))
                          .build()))
              .build();

      return exprBuilder.setCall(callExpr).build();
    }

    return exprFactory
        .newExprBuilder(context.op)
        .setSelect(CelExpr.CelSelect.newBuilder().setOperand(member).setField(id).build())
        .build();
  }

  @Override
  public CelExpr visitMemberCall(MemberCallContext context) {
    checkNotNull(context);
    if (context.member() == null) {
      return exprFactory.ensureErrorsExist(context);
    }
    CelExpr member = visit(context.member());
    if (context.id == null) {
      return exprFactory.newExprBuilder(context).build();
    }
    String id = context.id.getText();
    return receiverCallOrMacro(context, id, member);
  }

  @Override
  public CelExpr visitIndex(IndexContext context) {
    checkNotNull(context);
    if (context.member() == null || context.index == null) {
      return exprFactory.ensureErrorsExist(context);
    }
    CelExpr member = visit(context.member());
    CelExpr.Builder exprBuilder = exprFactory.newExprBuilder(context.op);
    CelExpr index = visit(context.index);
    Operator indexOperator = Operator.INDEX;

    if (context.opt != null && context.opt.getText().equals("?")) {
      if (!options.enableOptionalSyntax()) {
        return exprFactory.reportError(context.op, "unsupported syntax '[?'");
      }
      indexOperator = Operator.OPTIONAL_INDEX;
    }

    return exprBuilder
        .setCall(
            CelExpr.CelCall.newBuilder()
                .setFunction(indexOperator.getFunction())
                .addArgs(member)
                .addArgs(index)
                .build())
        .build();
  }

  @Override
  public CelExpr visitCreateMessage(CreateMessageContext context) {
    checkNotNull(context);
    StringBuilder msgNameBuilder = new StringBuilder();
    for (Token token : context.ids) {
      if (msgNameBuilder.length() > 0) {
        msgNameBuilder.append(".");
      }
      msgNameBuilder.append(token.getText());
    }

    if (context.leadingDot != null) {
      msgNameBuilder.insert(0, ".");
    }

    String messageName = msgNameBuilder.toString();
    if (messageName.isEmpty()) {
      return exprFactory.ensureErrorsExist(context);
    }

    CelExpr.Builder exprBuilder = exprFactory.newExprBuilder(context.op);
    CelExpr.CelStruct.Builder structExpr = visitStructFields(context.entries);
    return exprBuilder.setStruct(structExpr.setMessageName(messageName).build()).build();
  }

  @Override
  public CelExpr visitIdent(IdentContext context) {
    checkNotNull(context);
    if (context.id == null) {
      return exprFactory.newExprBuilder(context).build();
    }
    String id = context.id.getText();
    if (options.enableReservedIds() && RESERVED_IDS.contains(id)) {
      return exprFactory.reportError(context, "reserved identifier: %s", id);
    }
    if (context.leadingDot != null) {
      id = "." + id;
    }

    return exprFactory
        .newExprBuilder(context.id)
        .setIdent(CelExpr.CelIdent.newBuilder().setName(id).build())
        .build();
  }

  @Override
  public CelExpr visitGlobalCall(GlobalCallContext context) {
    checkNotNull(context);
    if (context.id == null) {
      return exprFactory.newExprBuilder(context).build();
    }
    String id = context.id.getText();
    if (options.enableReservedIds() && RESERVED_IDS.contains(id)) {
      return exprFactory.reportError(context, "reserved identifier: %s", id);
    }
    if (context.leadingDot != null) {
      id = "." + id;
    }

    return globalCallOrMacro(context, id);
  }

  @Override
  public CelExpr visitNested(NestedContext context) {
    checkNotNull(context);
    if (context.e == null) {
      return exprFactory.ensureErrorsExist(context);
    }
    return visit(context.e);
  }

  @Override
  public CelExpr visitCreateList(CreateListContext context) {
    checkNotNull(context);
    CelExpr.Builder exprBuilder = exprFactory.newExprBuilder(context.op);
    CelExpr.CelList createListExpr = visitListInitElements(context.listInit());

    return exprBuilder.setList(createListExpr).build();
  }

  private CelExpr.CelList visitListInitElements(ListInitContext context) {
    CelExpr.CelList.Builder listExpr = CelExpr.CelList.newBuilder();
    if (context == null) {
      return listExpr.build();
    }

    for (int index = 0; index < context.elems.size(); index++) {
      OptExprContext elem = context.elems.get(index);
      listExpr.addElements(visit(elem.e));

      if (elem.opt != null) {
        if (!options.enableOptionalSyntax()) {
          exprFactory.reportError(elem.opt, "unsupported syntax '?'");
          continue;
        }
        listExpr.addOptionalIndices(index);
      }
    }

    return listExpr.build();
  }

  @Override
  public CelExpr visitCreateMap(CreateMapContext context) {
    checkNotNull(context);
    CelExpr.Builder exprBuilder = exprFactory.newExprBuilder(context.op);
    CelExpr.CelMap.Builder createMapExpr = visitMapEntries(context.entries);
    return exprBuilder.setMap(createMapExpr.build()).build();
  }

  private CelExpr buildMacroCallArgs(CelExpr expr) {
    CelExpr.Builder resultExpr = CelExpr.newBuilder().setId(expr.id());
    if (sourceInfo.containsMacroCalls(expr.id())) {
      return resultExpr.build();
    }
    // Call expression could have args or sub-args that are also macros found in macro calls
    if (expr.exprKind().getKind() == CelExpr.ExprKind.Kind.CALL) {
      CelExpr.CelCall.Builder callExpr =
          CelExpr.CelCall.newBuilder().setFunction(expr.call().function());
      // Iterate the AST from `expr` recursively looking for macros. Because we are at most
      // starting from the top level macro, this recursion is bounded by the size of the AST. This
      // means that the depth check on the AST during parsing will catch recursion overflows
      // before we get to here.
      expr.call().args().forEach(arg -> callExpr.addArgs(buildMacroCallArgs(arg)));
      expr.call().target().ifPresent(target -> callExpr.setTarget(buildMacroCallArgs(target)));
      return resultExpr.setCall(callExpr.build()).build();
    }
    return expr;
  }

  /**
   * Returns the expanded AST after visiting a macro. Optional.empty is returned instead if the
   * implementation decides that an expansion should not be performed, in which case we should just
   * default to call.
   */
  private Optional<CelExpr> visitMacro(
      CelExpr.Builder expr,
      String id,
      ImmutableList<CelExpr> args,
      Optional<CelExpr> target,
      CelMacro macro) {
    if (exprFactory.isNodeLimitExceeded()) {
      return Optional.of(
          exprFactory.reportError(
              exprFactory.getPosition(expr.id()),
              "could not expand macro: expression node limit exceeded"));
    }

    Optional<CelExpr> expandedMacro =
        expandMacro(
            exprFactory.getPosition(expr.id()),
            macro,
            target.orElse(CelExpr.newBuilder().build()),
            args);
    if (!expandedMacro.isPresent()) {
      return Optional.empty();
    }
    CelExpr.CelCall.Builder callExpr = CelExpr.CelCall.newBuilder().setFunction(id);
    if (target.isPresent()) {
      if (sourceInfo.containsMacroCalls(target.get().id())) {
        callExpr.setTarget(CelExpr.newBuilder().setId(target.get().id()).build());
      } else {
        callExpr.setTarget(target.get());
      }
    }
    for (CelExpr arg : args) {
      callExpr.addArgs(buildMacroCallArgs(arg));
    }

    if (options.populateMacroCalls()) {
      sourceInfo.addMacroCalls(
          expandedMacro.get().id(),
          // Note: A macro id MUST NOT be assigned to the call expr placed into the macro calls map.
          // This can cause an infinite loop in some of the call chains that try to figure out
          // whether the current expression is expanded to a macro.
          CelExpr.newBuilder().setCall(callExpr.build()).build());
    }

    sourceInfo.removePositions(expr.id());
    return expandedMacro;
  }

  private String normalizeEscapedIdent(EscapeIdentContext context) {
    String identifier = context.getText();
    if (context instanceof SimpleIdentifierContext) {
      return identifier;
    } else if (context instanceof EscapedIdentifierContext) {
      if (!options.enableQuotedIdentifierSyntax()) {
        exprFactory.reportError(context, "unsupported syntax '`'");
        return identifier;
      }
      return identifier.substring(1, identifier.length() - 1);
    }

    // This is normally unreachable, but might happen if the parser is in an error state or if the
    // grammar is updated and not handled here.
    exprFactory.reportError(context, "unsupported identifier");
    return identifier;
  }

  private CelExpr.CelStruct.Builder visitStructFields(FieldInitializerListContext context) {
    if (context == null
        || context.cols == null
        || context.fields == null
        || context.values == null) {
      return CelExpr.CelStruct.newBuilder();
    }
    int entryCount = min(context.cols.size(), context.fields.size(), context.values.size());
    CelExpr.CelStruct.Builder structExpr = CelExpr.CelStruct.newBuilder();
    for (int index = 0; index < entryCount; index++) {
      OptFieldContext fieldContext = context.fields.get(index);
      boolean isOptionalEntry = false;
      if (fieldContext.opt != null) {
        if (!options.enableOptionalSyntax()) {
          exprFactory.reportError(fieldContext.opt, "unsupported syntax '?'");
        } else {
          isOptionalEntry = true;
        }
      }

      // The field may be empty due to a prior error.
      if (fieldContext.escapeIdent() == null) {
        return CelExpr.CelStruct.newBuilder();
      }
      String fieldName = normalizeEscapedIdent(fieldContext.escapeIdent());

      CelExpr.CelStruct.Entry.Builder exprBuilder =
          CelExpr.CelStruct.Entry.newBuilder()
              .setId(exprFactory.newExprId(exprFactory.getPosition(context.cols.get(index))));
      structExpr.addEntries(
          exprBuilder
              .setFieldKey(fieldName)
              .setValue(visit(context.values.get(index)))
              .setOptionalEntry(isOptionalEntry)
              .build());
    }
    return structExpr;
  }

  private CelExpr.CelMap.Builder visitMapEntries(MapInitializerListContext context) {
    if (context == null || context.cols == null || context.keys == null || context.values == null) {
      return CelExpr.CelMap.newBuilder();
    }
    int entryCount = min(context.cols.size(), context.keys.size(), context.values.size());
    CelExpr.CelMap.Builder mapExpr = CelExpr.CelMap.newBuilder();
    for (int index = 0; index < entryCount; index++) {
      OptExprContext keyContext = context.keys.get(index);
      boolean isOptionalEntry = false;
      if (keyContext.opt != null) {
        if (!options.enableOptionalSyntax()) {
          exprFactory.reportError(keyContext.opt, "unsupported syntax '?'");
        } else {
          isOptionalEntry = true;
        }
      }
      CelExpr.CelMap.Entry.Builder exprBuilder =
          CelExpr.CelMap.Entry.newBuilder()
              .setId(exprFactory.newExprId(exprFactory.getPosition(context.cols.get(index))));
      mapExpr.addEntries(
          exprBuilder
              .setKey(visit(keyContext.e))
              .setValue(visit(context.values.get(index)))
              .setOptionalEntry(isOptionalEntry)
              .build());
    }
    return mapExpr;
  }

  @Override
  protected CelExpr defaultResult() {
    // visitTerminalNode and visitErrorNode call this method.
    return exprFactory.ensureErrorsExist(
        () -> "Abstract syntax tree in an unexpected state, this is likely a bug.");
  }

  @Override
  public CelExpr visitConstantLiteral(ConstantLiteralContext context) {
    checkNotNull(context);
    if (context.literal() == null) {
      return exprFactory.ensureErrorsExist(context);
    }
    return visit(context.literal());
  }

  @Override
  public CelExpr visitExprList(ExprListContext context) {
    // We should never get here, as we do not directly visit expression lists.
    return exprFactory.ensureErrorsExist(context);
  }

  @Override
  public CelExpr visitFieldInitializerList(FieldInitializerListContext context) {
    // We should never get here, as we do not directly visit field initializer lists.
    return exprFactory.ensureErrorsExist(context);
  }

  @Override
  public CelExpr visitMapInitializerList(MapInitializerListContext context) {
    // We should never get here, as we do not directly visit map initializer lists.
    return exprFactory.ensureErrorsExist(context);
  }

  @Override
  public CelExpr visitListInit(ListInitContext context) {
    // We should never get here, as we do not directly visit list initializer.
    return exprFactory.ensureErrorsExist(context);
  }

  @Override
  public CelExpr visitInt(IntContext context) {
    checkNotNull(context);
    CelConstant constExpr;
    try {
      constExpr = Constants.parseInt(context.getText());
    } catch (ParseException e) {
      return exprFactory.reportError(context, e.getMessage());
    }

    return exprFactory.newExprBuilder(context.tok).setConstant(constExpr).build();
  }

  @Override
  public CelExpr visitUint(UintContext context) {
    checkNotNull(context);
    CelConstant constExpr;
    try {
      constExpr = Constants.parseUint(context.getText());
    } catch (ParseException e) {
      return exprFactory.reportError(context, e.getMessage());
    }
    return exprFactory.newExprBuilder(context).setConstant(constExpr).build();
  }

  @Override
  public CelExpr visitDouble(DoubleContext context) {
    checkNotNull(context);
    CelConstant constExpr;
    try {
      constExpr = Constants.parseDouble(context.getText());
    } catch (ParseException e) {
      return exprFactory.reportError(context, e.getMessage());
    }
    return exprFactory.newExprBuilder(context.tok).setConstant(constExpr).build();
  }

  @Override
  public CelExpr visitString(StringContext context) {
    checkNotNull(context);
    CelConstant constExpr;
    try {
      constExpr = Constants.parseString(context.getText());
    } catch (ParseException e) {
      return exprFactory.reportError(context, e.getMessage());
    }
    return exprFactory.newExprBuilder(context).setConstant(constExpr).build();
  }

  @Override
  public CelExpr visitBytes(BytesContext context) {
    checkNotNull(context);
    CelConstant constExpr;
    try {
      constExpr = Constants.parseBytes(context.getText());
    } catch (ParseException e) {
      return exprFactory.reportError(context, e.getMessage());
    }
    return exprFactory.newExprBuilder(context).setConstant(constExpr).build();
  }

  @Override
  public CelExpr visitBoolTrue(BoolTrueContext context) {
    checkNotNull(context);
    return exprFactory.newExprBuilder(context).setConstant(Constants.TRUE).build();
  }

  @Override
  public CelExpr visitBoolFalse(BoolFalseContext context) {
    checkNotNull(context);
    return exprFactory.newExprBuilder(context).setConstant(Constants.FALSE).build();
  }

  @Override
  public CelExpr visitNull(NullContext context) {
    checkNotNull(context);
    return exprFactory.newExprBuilder(context).setConstant(Constants.NULL).build();
  }

  private Optional<CelExpr> expandMacro(
      int position, CelMacro macro, CelExpr target, ImmutableList<CelExpr> arguments) {
    exprFactory.pushPosition(position);
    try {
      return macro.getExpander().expandMacro(exprFactory, target, arguments);
    } finally {
      exprFactory.popPosition();
    }
  }

  private CelExpr receiverCallOrMacro(MemberCallContext context, String id, CelExpr member) {
    return macroOrCall(context.args, context.open, id, Optional.of(member), true);
  }

  private CelExpr globalCallOrMacro(GlobalCallContext context, String id) {
    return macroOrCall(context.args, context.op, id, Optional.empty(), false);
  }

  private ImmutableList<CelExpr> visitExprListContext(ExprListContext args) {
    int argCount = args != null && args.e != null ? args.e.size() : 0;
    if (argCount == 0) {
      return ImmutableList.of();
    }

    ImmutableList.Builder<CelExpr> argumentsBuilder =
        ImmutableList.builderWithExpectedSize(argCount);
    for (ExprContext argExprCtx : args.e) {
      argumentsBuilder.add(visit(argExprCtx));
    }
    return argumentsBuilder.build();
  }

  private CelExpr macroOrCall(
      ExprListContext args,
      Token open,
      String id,
      Optional<CelExpr> member,
      boolean isReceiverStyle) {
    int argCount = args != null && args.e != null ? args.e.size() : 0;
    Optional<CelMacro> macro = lookupMacro(id, argCount, isReceiverStyle);
    CelExpr.Builder exprBuilder = exprFactory.newExprBuilder(open);

    ImmutableList<CelExpr> arguments = visitExprListContext(args);
    Optional<CelExpr> errorArg = arguments.stream().filter(ERROR::equals).findAny();
    if (errorArg.isPresent()) {
      sourceInfo.removePositions(exprBuilder.id());
      // Any arguments passed in to the macro may fail parsing.
      // Stop the macro expansion in this case as the result of the macro will be a parse failure.
      return ERROR;
    }

    if (macro.isPresent()) {
      Optional<CelExpr> expandedMacro = visitMacro(exprBuilder, id, arguments, member, macro.get());
      if (expandedMacro.isPresent()) {
        return expandedMacro.get();
      }
    }

    CelExpr.CelCall.Builder callExpr =
        CelExpr.CelCall.newBuilder().setFunction(id).addArgs(arguments);
    member.ifPresent(callExpr::setTarget);

    return exprBuilder.setCall(callExpr.build()).build();
  }

  private Optional<CelMacro> lookupMacro(String id, int argCount, boolean receiverStlye) {
    String key = CelMacro.formatKey(id, argCount, receiverStlye);
    Optional<CelMacro> macro = parser.findMacro(key);
    if (macro.isPresent()) {
      return macro;
    }
    key = CelMacro.formatVarArgKey(id, receiverStlye);
    return parser.findMacro(key);
  }

  /**
   * Checks whether a given parse tree node is left recursive for the purposes of counting recursion
   * depths.
   */
  private boolean isLeftRecursiveForCountingDepths(ParseTree node) {
    // There are certainly more left recursive nodes than what's shown below.
    // We try to catch the specific node types that explodes the number of recursive visit calls and
    // of those that cannot be caught by PerRuleRecursionListener.
    return node instanceof ExprContext
        || node instanceof CalcContext
        || node instanceof RelationContext
        || node instanceof SelectContext
        || node instanceof MemberCallContext
        || node instanceof IndexContext;
  }

  private void checkAndIncrementRecursionDepth() {
    recursionDepth++;
    if (recursionDepth > options.maxParseRecursionDepth()) {
      String errorMessage =
          String.format(
              "Expression recursion limit exceeded. limit: %d", options.maxParseRecursionDepth());
      exprFactory.reportError(CelIssue.formatError(CelSourceLocation.of(1, 0), errorMessage));
      throw new ParseCancellationException(errorMessage);
    }
  }

  private void decrementRecursionDepth() {
    recursionDepth--;
  }

  /**
   * unnest traverses down the left-hand side of the parse graph until it encounters the first
   * compound parse node or the first leaf in the parse graph.
   */
  private ParseTree unnest(ParseTree tree) {
    while (tree != null) {
      if (tree instanceof ExprContext) {
        // conditionalOr op='?' conditionalOr : expr
        ExprContext context = (ExprContext) tree;
        if (context.op != null) {
          return tree;
        }
        // conditionalOr
        tree = context.e;
      } else if (tree instanceof ConditionalOrContext) {
        // conditionalAnd (ops=|| conditionalAnd)*
        ConditionalOrContext context = (ConditionalOrContext) tree;
        if (context.ops != null && !context.ops.isEmpty()) {
          return tree;
        }
        // conditionalAnd
        tree = context.e;
      } else if (tree instanceof ConditionalAndContext) {
        // relation (ops=&& relation)*
        ConditionalAndContext context = (ConditionalAndContext) tree;
        if (context.ops != null && !context.ops.isEmpty()) {
          return tree;
        }

        // relation
        tree = context.e;
      } else if (tree instanceof RelationContext) {
        // relation op relation
        RelationContext context = (RelationContext) tree;
        if (context.op != null) {
          return tree;
        }
        // calc
        tree = context.calc();
      } else if (tree instanceof CalcContext) {
        // calc op calc
        CalcContext context = (CalcContext) tree;
        if (context.op != null) {
          return tree;
        }

        // unary
        tree = context.unary();
      } else if (tree instanceof MemberExprContext) {
        // member expands to one of: primary, select, index, or create message
        tree = ((MemberExprContext) tree).member();
      } else if (tree instanceof PrimaryExprContext) {
        // primary expands to one of identifier, nested, create list, create struct, literal
        tree = ((PrimaryExprContext) tree).primary();
      } else if (tree instanceof NestedContext) {
        // contains a nested 'expr'
        tree = ((NestedContext) tree).e;
      } else if (tree instanceof ConstantLiteralContext) {
        // expands to a primitive literal
        tree = ((ConstantLiteralContext) tree).literal();
      } else {
        return tree;
      }
    }

    return tree;
  }

  /** Implementation of {@link CelMacroExprFactory}. */
  private static final class ExprFactory extends CelMacroExprFactory {

    private final org.antlr.v4.runtime.Parser recognizer;
    private final CelSource.Builder sourceInfo;
    private final ArrayList<CelIssue> issues;
    private final ArrayDeque<Integer> positions;
    private final String accumulatorVarName;
    private final int maxExpressionNodeCount;
    private boolean nodeLimitExceeded;

    private ExprFactory(
        org.antlr.v4.runtime.Parser recognizer,
        CelSource.Builder sourceInfo,
        String accumulatorVarName,
        int maxExpressionNodeCount) {
      this.recognizer = recognizer;
      this.sourceInfo = sourceInfo;
      this.issues = new ArrayList<>();
      this.positions = new ArrayDeque<>(1); // Currently this usually contains at most 1 position.
      this.accumulatorVarName = accumulatorVarName;
      this.maxExpressionNodeCount = maxExpressionNodeCount;
    }

    // Implementation of CelExprFactory.

    @Override
    protected CelSourceLocation getSourceLocation(long exprId) {
      checkArgument(exprId > 0L);
      return getLocation(getPosition(exprId));
    }

    @CanIgnoreReturnValue
    @Override
    public CelExpr reportError(CelIssue error) {
      checkNotNull(error);
      issues.add(error);
      if (!CelSourceLocation.NONE.equals(error.getSourceLocation())) {
        Optional<Integer> offset = sourceInfo.getLocationOffset(error.getSourceLocation());
        checkState(offset.isPresent()); // A valid location should always return a valid offset.
        return newExpr(offset.get());
      }
      return ERROR;
    }

    @FormatMethod
    @CanIgnoreReturnValue
    private CelExpr reportError(
        ParserRuleContext context, @FormatString String format, Object... args) {
      return reportError(context, String.format(format, args));
    }

    @CanIgnoreReturnValue
    private CelExpr reportError(ParserRuleContext context, String message) {
      return reportError(CelIssue.formatError(getLocation(context), message));
    }

    @CanIgnoreReturnValue
    private CelExpr reportError(Token token, String message) {
      return reportError(CelIssue.formatError(getLocation(token), message));
    }

    @CanIgnoreReturnValue
    private CelExpr reportError(int position, String message) {
      return reportError(CelIssue.formatError(getLocation(position), message));
    }

    // Implementation of CelExprFactory.

    @Override
    public String getAccumulatorVarName() {
      return accumulatorVarName;
    }

    @Override
    protected CelSourceLocation currentSourceLocationForMacro() {
      checkState(!positions.isEmpty()); // Should only be called while expanding macros.
      return getLocation(peekPosition());
    }

    // Internal methods used by the parser but not part of the public API.

    private boolean isNodeLimitExceeded() {
      return nodeLimitExceeded;
    }

    private void pushPosition(int position) {
      positions.addLast(position);
    }

    private void popPosition() {
      checkState(!positions.isEmpty());
      positions.removeLast();
    }

    private int peekPosition() {
      checkState(!positions.isEmpty());
      return positions.peekLast();
    }

    private long nextExprId(int position) {
      long exprId = super.nextExprId();
      if (exprId > maxExpressionNodeCount && !nodeLimitExceeded) {
        nodeLimitExceeded = true;
        reportError(
            position, String.format("expression node limit (%d) exceeded", maxExpressionNodeCount));
      }
      if (position != -1) {
        sourceInfo.addPositions(exprId, position);
      }
      return exprId;
    }

    @Override
    public long nextExprId() {
      checkState(!positions.isEmpty()); // Should only be called while expanding macros.
      // Do not call this method directly from within the parser, use nextExprId(int).
      return nextExprId(peekPosition());
    }

    @Override
    public long copyExprId(long id) {
      return nextExprId(getPosition(id));
    }

    private List<CelIssue> getIssuesList() {
      return issues;
    }

    private int getPosition(long exprId) {
      return Optional.ofNullable(sourceInfo.getPositionsMap().get(exprId)).orElse(-1);
    }

    private int getPosition(Token token) {
      return sourceInfo
          .getLocationOffset(token.getLine(), token.getCharPositionInLine())
          .orElse(-1);
    }

    private int getPosition(ParserRuleContext context) {
      return getPosition(context.getStart());
    }

    private CelSourceLocation getLocation(int position) {
      return sourceInfo.getOffsetLocation(position).orElse(CelSourceLocation.NONE);
    }

    private CelSourceLocation getLocation(Token token) {
      return CelSourceLocation.of(token.getLine(), token.getCharPositionInLine());
    }

    private CelSourceLocation getLocation(ParserRuleContext context) {
      return getLocation(context.getStart());
    }

    @CanIgnoreReturnValue
    private long newExprId(int position) {
      return nextExprId(position);
    }

    private CelExpr.Builder newExprBuilder(int position) {
      return CelExpr.newBuilder().setId(newExprId(position));
    }

    private CelExpr.Builder newExprBuilder(Token token) {
      return newExprBuilder(getPosition(token));
    }

    private CelExpr.Builder newExprBuilder(ParserRuleContext context) {
      return newExprBuilder(getPosition(context));
    }

    private CelExpr newExpr(int position) {
      return newExprBuilder(position).build();
    }

    private CelExpr ensureErrorsExist(Supplier<String> message) {
      // Because we do not treat syntax errors as fatal during parsing, the parse tree is often in
      // an abnormal state. We call this function to ensure we have recorded syntax errors. If we
      // have we return the special error node otherwise we bail and mention that this is likely a
      // bug.
      if (issues.isEmpty()) {
        // If we reach here, this is an unexpected error and highly likely to be a bug. At least one
        // syntax error or another error should have occurred because the parse tree is in an
        // unexpected state.
        throw new ParseCancellationException(
            String.format(
                "Abstract syntax tree in an unexpected state, this is likely a bug: %s",
                message.get()));
      }
      return ERROR;
    }

    private CelExpr ensureErrorsExist(ParserRuleContext context) {
      return ensureErrorsExist(() -> context.toInfoString(recognizer));
    }
  }

  /**
   * Listener that enforces a maximum recursion depth, to avoid accidental stack overflow issues
   * when parsing large expressions.
   */
  private static final class PerRuleRecursionListener implements ParseTreeListener {

    private final ExprFactory exprFactory;
    private final int maxRecursionDepth;
    private final Map<Integer, Integer> ruleTypeDepth;

    private PerRuleRecursionListener(ExprFactory exprFactory, int maxRecursionDepth) {
      this.exprFactory = exprFactory;
      this.maxRecursionDepth = maxRecursionDepth;
      this.ruleTypeDepth = new HashMap<>();
    }

    @Override
    public void enterEveryRule(ParserRuleContext context) {
      int ruleDepth = ruleTypeDepth.getOrDefault(context.getRuleIndex(), 0) + 1;
      ruleTypeDepth.put(context.getRuleIndex(), ruleDepth);
      if (ruleDepth > maxRecursionDepth) {
        String errorMessage =
            String.format("Expression recursion limit exceeded. limit: %d", maxRecursionDepth);
        exprFactory.reportError(CelIssue.formatError(CelSourceLocation.of(1, 0), errorMessage));
        throw new ParseCancellationException(errorMessage);
      }
    }

    @Override
    public void exitEveryRule(ParserRuleContext context) {
      int ruleDepth = ruleTypeDepth.get(context.getRuleIndex()) - 1;
      ruleTypeDepth.put(context.getRuleIndex(), ruleDepth);
    }

    @Override
    public void visitErrorNode(ErrorNode node) {}

    @Override
    public void visitTerminal(TerminalNode node) {}
  }

  /** Error strategy that limits the number of recovery attempts. */
  private static final class RecoveryLimitErrorStrategy extends DefaultErrorStrategy {

    private final int recoveryLimit;
    private int recoveryAttempts;

    private RecoveryLimitErrorStrategy(int recoveryLimit) {
      this.recoveryLimit = recoveryLimit;
      recoveryAttempts = 0;
    }

    @Override
    public void recover(org.antlr.v4.runtime.Parser recognizer, RecognitionException e) {
      checkRecoveryLimit(recognizer);
      super.recover(recognizer, e);
    }

    @Override
    public Token recoverInline(org.antlr.v4.runtime.Parser recognizer) {
      checkRecoveryLimit(recognizer);
      return super.recoverInline(recognizer);
    }

    private void checkRecoveryLimit(org.antlr.v4.runtime.Parser recognizer) {
      if (recoveryAttempts++ >= recoveryLimit) {
        String tooManyErrors = String.format("More than %d parse errors.", recoveryLimit);
        recognizer.notifyErrorListeners(tooManyErrors);
        throw new ParseCancellationException(tooManyErrors);
      }
    }
  }

  private static final class ErrorListener implements ANTLRErrorListener {

    private final ExprFactory exprFactory;

    private ErrorListener(ExprFactory exprFactory) {
      this.exprFactory = exprFactory;
    }

    @Override
    public void reportAmbiguity(
        org.antlr.v4.runtime.Parser recognizer,
        DFA dfa,
        int startIndex,
        int stopIndex,
        boolean exact,
        BitSet ambigAlts,
        ATNConfigSet configs) {
      // Intentional.
    }

    @Override
    public void reportAttemptingFullContext(
        org.antlr.v4.runtime.Parser recognizer,
        DFA dfa,
        int startIndex,
        int stopIndex,
        BitSet ambigAlts,
        ATNConfigSet configs) {
      // Intentional.
    }

    @Override
    public void reportContextSensitivity(
        org.antlr.v4.runtime.Parser recognizer,
        DFA dfa,
        int startIndex,
        int stopIndex,
        int prediction,
        ATNConfigSet configs) {
      // Intentional.
    }

    @Override
    public void syntaxError(
        Recognizer<?, ?> recognizer,
        Object offendingSymbol,
        int line,
        int charPositionInLine,
        String msg,
        RecognitionException e) {
      msg = msg.replace("%", "%%");
      exprFactory.reportError(
          CelIssue.formatError(CelSourceLocation.of(line, charPositionInLine), msg));
    }
  }
}
