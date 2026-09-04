// Copyright 2026 Google LLC
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package dev.cel.parser;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.CelIssue;
import dev.cel.common.CelOptions;
import dev.cel.common.CelSource;
import dev.cel.common.CelSourceLocation;
import dev.cel.common.CelValidationResult;
import dev.cel.common.Operator;
import dev.cel.common.ast.CelConstant;
import dev.cel.common.ast.CelExpr;
import dev.cel.common.internal.Constants;
import java.text.ParseException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** Pratt parser implementation for CEL. */
final class PrattParser {

  private static final String ACCUMULATOR_NAME = "@result";
  private static final CelExpr ERROR = CelExpr.newBuilder().setConstant(Constants.ERROR).build();

  private static final class BinaryOpInfo {
    final int precedence;
    final String name;
    final boolean isLogical;
    final Lexer.TokenType type;

    BinaryOpInfo(int precedence, String name, boolean isLogical, Lexer.TokenType type) {
      this.precedence = precedence;
      this.name = name;
      this.isLogical = isLogical;
      this.type = type;
    }
  }

  private static final BinaryOpInfo LOGICAL_OR_OP =
      new BinaryOpInfo(1, Operator.LOGICAL_OR.getFunction(), true, Lexer.TokenType.LOGICAL_OR);
  private static final BinaryOpInfo LOGICAL_AND_OP =
      new BinaryOpInfo(2, Operator.LOGICAL_AND.getFunction(), true, Lexer.TokenType.LOGICAL_AND);
  private static final BinaryOpInfo LESS_OP =
      new BinaryOpInfo(3, Operator.LESS.getFunction(), false, Lexer.TokenType.LESS);
  private static final BinaryOpInfo LESS_EQUAL_OP =
      new BinaryOpInfo(3, Operator.LESS_EQUALS.getFunction(), false, Lexer.TokenType.LESS_EQUAL);
  private static final BinaryOpInfo GREATER_OP =
      new BinaryOpInfo(3, Operator.GREATER.getFunction(), false, Lexer.TokenType.GREATER);
  private static final BinaryOpInfo GREATER_EQUAL_OP =
      new BinaryOpInfo(
          3, Operator.GREATER_EQUALS.getFunction(), false, Lexer.TokenType.GREATER_EQUAL);
  private static final BinaryOpInfo EQUAL_EQUAL_OP =
      new BinaryOpInfo(3, Operator.EQUALS.getFunction(), false, Lexer.TokenType.EQUAL_EQUAL);
  private static final BinaryOpInfo EXCLAMATION_EQUAL_OP =
      new BinaryOpInfo(
          3, Operator.NOT_EQUALS.getFunction(), false, Lexer.TokenType.EXCLAMATION_EQUAL);
  private static final BinaryOpInfo IN_OP =
      new BinaryOpInfo(3, Operator.IN.getFunction(), false, Lexer.TokenType.IN);
  private static final BinaryOpInfo PLUS_OP =
      new BinaryOpInfo(4, Operator.ADD.getFunction(), false, Lexer.TokenType.PLUS);
  private static final BinaryOpInfo MINUS_OP =
      new BinaryOpInfo(4, Operator.SUBTRACT.getFunction(), false, Lexer.TokenType.MINUS);
  private static final BinaryOpInfo ASTERISK_OP =
      new BinaryOpInfo(5, Operator.MULTIPLY.getFunction(), false, Lexer.TokenType.ASTERISK);
  private static final BinaryOpInfo SLASH_OP =
      new BinaryOpInfo(5, Operator.DIVIDE.getFunction(), false, Lexer.TokenType.SLASH);
  private static final BinaryOpInfo PERCENT_OP =
      new BinaryOpInfo(5, Operator.MODULO.getFunction(), false, Lexer.TokenType.PERCENT);
  private static final BinaryOpInfo DEFAULT_OP =
      new BinaryOpInfo(0, "", false, Lexer.TokenType.ERROR);

  private static BinaryOpInfo getBinaryOpInfo(Lexer.TokenType type) {
    switch (type) {
      case LOGICAL_OR:
        return LOGICAL_OR_OP;
      case LOGICAL_AND:
        return LOGICAL_AND_OP;
      case LESS:
        return LESS_OP;
      case LESS_EQUAL:
        return LESS_EQUAL_OP;
      case GREATER:
        return GREATER_OP;
      case GREATER_EQUAL:
        return GREATER_EQUAL_OP;
      case EQUAL_EQUAL:
        return EQUAL_EQUAL_OP;
      case EXCLAMATION_EQUAL:
        return EXCLAMATION_EQUAL_OP;
      case IN:
        return IN_OP;
      case PLUS:
        return PLUS_OP;
      case MINUS:
        return MINUS_OP;
      case ASTERISK:
        return ASTERISK_OP;
      case SLASH:
        return SLASH_OP;
      case PERCENT:
        return PERCENT_OP;
      default:
        return DEFAULT_OP;
    }
  }

  private static final class UnaryOp {
    final Lexer.Token token;
    long id;

    UnaryOp(Lexer.Token token) {
      this.token = token;
      this.id = 0;
    }
  }

  private final CelSource source;
  private final CelOptions options;
  private final ImmutableMap<String, CelMacro> macros;
  private final Lexer lexer;
  private final Map<Long, Integer> positions;
  private final Map<Long, CelExpr> macroCalls;
  private final List<CelIssue> issues;
  private final PrattMacroExprFactory macroExprFactory;

  private Lexer.Token currentToken;
  private Lexer.Token peekToken;
  private int recursionDepth;
  private int currentLhsDepth;
  private long nextId;
  private boolean nodeLimitExceeded;
  private boolean recursionLimitExceeded;
  private int errorCount;

  static CelValidationResult parse(
      CelSource source, CelOptions options, Map<String, CelMacro> macros) {
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
    PrattParser prattParser = new PrattParser(source, options, macros);
    CelExpr expr = prattParser.run();
    if (prattParser.recursionLimitExceeded || prattParser.errorCount > 0) {
      return new CelValidationResult(source, ImmutableList.copyOf(prattParser.issues));
    }

    CelSource.Builder sourceBuilder = source.toBuilder();
    sourceBuilder.addPositionsMap(prattParser.positions);
    sourceBuilder.addAllMacroCalls(prattParser.macroCalls);

    return new CelValidationResult(
        CelAbstractSyntaxTree.newParsedAst(expr, sourceBuilder.build()),
        ImmutableList.copyOf(prattParser.issues));
  }

  private PrattParser(CelSource source, CelOptions options, Map<String, CelMacro> macros) {
    this.source = source;
    this.options = options;
    this.macros = ImmutableMap.copyOf(macros);
    this.lexer = new Lexer(source.getContent());
    this.positions = new HashMap<>();
    this.macroCalls = new HashMap<>();
    this.issues = new ArrayList<>();
    this.macroExprFactory = new PrattMacroExprFactory();
    this.nextId = 1;
    initTokenStream();
  }

  CelExpr run() {
    CelExpr expr = parseExpr();
    if (recursionLimitExceeded || isRecoveryLimitExceeded()) {
      return expr;
    }
    while (peekToken.type != Lexer.TokenType.END && peekToken.type != Lexer.TokenType.ERROR) {
      if (options.enableReservedIds()
          && (peekToken.type == Lexer.TokenType.RESERVED_WORD
              || peekToken.type == Lexer.TokenType.IN)) {
        Lexer.Token resTok = nextToken();
        String resText = normalizeIdent(resTok, /* allowQuoted= */ false);
        reportError(resTok.start, String.format("reserved identifier: %s", resText));
        continue;
      }
      reportSyntaxError(peekToken, "unexpected token after expression");
      break;
    }
    return expr;
  }

  private boolean isRecoveryLimitExceeded() {
    return errorCount > options.maxParseErrorRecoveryLimit();
  }

  private void initTokenStream() {
    currentToken = new Lexer.Token(Lexer.TokenType.ERROR, 0, 0);
    peekToken = nextSignificantToken(true);
  }

  private String getTokenText(Lexer.Token tok) {
    if (tok.start >= 0 && tok.end >= tok.start && tok.end <= source.getContent().size()) {
      return source.getContent().slice(tok.start, tok.end).toString();
    }
    return "";
  }

  private Lexer.Token nextSignificantToken(boolean reportError) {
    if (isRecoveryLimitExceeded()) {
      return new Lexer.Token(Lexer.TokenType.END, 0, 0);
    }
    while (true) {
      Lexer.Token tok = lexer.lex();
      if (tok.type == Lexer.TokenType.WHITESPACE || tok.type == Lexer.TokenType.COMMENT) {
        continue;
      }
      if (tok.type == Lexer.TokenType.ERROR && reportError) {
        reportSyntaxError(tok, lexer.getError().message);
        if (isRecoveryLimitExceeded()) {
          return new Lexer.Token(Lexer.TokenType.END, 0, 0);
        }
      }
      return tok;
    }
  }

  private Lexer.Token nextToken() {
    currentToken = peekToken;
    if (isRecoveryLimitExceeded()) {
      peekToken = new Lexer.Token(Lexer.TokenType.END, 0, 0);
      return currentToken;
    }
    if (peekToken.type != Lexer.TokenType.END) {
      peekToken = nextSignificantToken(true);
    }
    return currentToken;
  }

  private boolean expect(Lexer.TokenType type, String msg) {
    if (peekToken.type == type) {
      nextToken();
      return true;
    }
    if (isRecoveryLimitExceeded()) {
      return false;
    }
    if (peekToken.type != Lexer.TokenType.ERROR) {
      String errMsg;
      if (msg == null || msg.isEmpty()) {
        String tokText = getTokenText(peekToken);
        String formattedTok =
            (peekToken.type == Lexer.TokenType.END) ? "<EOF>" : "'" + tokText + "'";
        errMsg = "mismatched input " + formattedTok + " expecting '" + type.getSymbol() + "'";
      } else {
        errMsg = msg;
      }
      reportSyntaxError(peekToken, errMsg);
    }
    synchronizeOnDelimiter();
    return false;
  }

  private void synchronizeOnDelimiter() {
    if (isRecoveryLimitExceeded()) {
      peekToken = new Lexer.Token(Lexer.TokenType.END, 0, 0);
      return;
    }
    while (peekToken.type != Lexer.TokenType.END) {
      if (peekToken.type == Lexer.TokenType.COMMA
          || peekToken.type == Lexer.TokenType.RIGHT_PAREN
          || peekToken.type == Lexer.TokenType.RIGHT_BRACKET
          || peekToken.type == Lexer.TokenType.RIGHT_BRACE) {
        break;
      }
      nextToken();
    }
  }

  private long nextId(int position) {
    long id = nextId++;
    if (id > options.maxParseExpressionNodeCount() && !nodeLimitExceeded) {
      reportError(
          position,
          String.format(
              "expression node limit (%d) exceeded", options.maxParseExpressionNodeCount()));
      nodeLimitExceeded = true;
    }
    if (!nodeLimitExceeded && position >= 0) {
      positions.put(id, position);
    }
    return id;
  }

  private long nextId(Lexer.Token token) {
    return nextId(token.start);
  }

  private long nextId() {
    return nextId(-1);
  }

  private void setPosition(long id, Lexer.Token token) {
    if (token.start >= 0) {
      positions.put(id, token.start);
    }
  }

  private long copyId(long id) {
    if (id == 0) {
      return 0;
    }
    int pos = positions.getOrDefault(id, 0);
    return nextId(pos);
  }

  private void eraseId(long id) {
    positions.remove(id);
    if (nextId == id + 1) {
      --nextId;
    }
  }

  private void reportError(int position, String msg) {
    CelSourceLocation loc = source.getOffsetLocation(position).orElse(CelSourceLocation.NONE);
    reportError(loc, msg);
  }

  private void reportError(CelSourceLocation loc, String msg) {
    if (errorCount > options.maxParseErrorRecoveryLimit()) {
      return;
    }
    errorCount++;
    if (errorCount == options.maxParseErrorRecoveryLimit() + 1) {
      issues.add(
          CelIssue.formatError(
              CelSourceLocation.NONE,
              String.format("More than %d parse errors.", options.maxParseErrorRecoveryLimit())));
      peekToken = new Lexer.Token(Lexer.TokenType.END, 0, 0);
    }
    if (errorCount <= options.maxParseErrorRecoveryLimit()) {
      issues.add(CelIssue.formatError(loc, msg));
    }
  }

  private void reportSyntaxError(Lexer.Token token, String msg) {
    reportError(token.start, "Syntax error: " + msg);
  }

  private boolean checkRecursion(int chainDepth, Lexer.Token token) {
    if (recursionDepth + chainDepth >= options.maxParseRecursionDepth()) {
      if (!recursionLimitExceeded) {
        recursionLimitExceeded = true;
        reportError(
            token.start,
            String.format(
                "Expression recursion limit exceeded. limit: %d",
                options.maxParseRecursionDepth()));
      }
      return true;
    }
    return false;
  }

  private CelExpr parseExpr() {
    if (recursionLimitExceeded || isRecoveryLimitExceeded()) {
      return ERROR;
    }
    if (checkRecursion(0, peekToken)) {
      return ERROR;
    }
    recursionDepth++;
    CelExpr expr = parseBinaryAndTernary(0);
    recursionDepth--;
    return expr;
  }

  private CelExpr parseBinaryAndTernary(int minPrec) {
    CelExpr lhs = parseSelectorChain();
    int chainDepth = currentLhsDepth;
    while (true) {
      Lexer.TokenType tok = peekToken.type;
      if (tok == Lexer.TokenType.QUESTION && minPrec <= 0) {
        lhs = parseTernary(lhs);
        continue;
      }

      BinaryOpInfo opInfo = getBinaryOpInfo(tok);
      if (opInfo.precedence < minPrec || opInfo.precedence == 0) {
        break;
      }

      if (opInfo.isLogical) {
        lhs = parseBalancedLogicalChain(lhs, opInfo);
        continue;
      }

      Lexer.Token opTok = nextToken();
      chainDepth++;
      if (checkRecursion(chainDepth, opTok)) {
        return ERROR;
      }
      long opId = nextId(opTok);
      CelExpr rhs = parseBinaryAndTernary(opInfo.precedence + 1);
      lhs = buildBinaryCall(opId, opInfo.name, lhs, rhs);
      currentLhsDepth = chainDepth;
    }
    return lhs;
  }

  private CelExpr parseTernary(CelExpr lhs) {
    Lexer.Token opTok = nextToken();
    long opId = nextId(opTok);
    CelExpr trueExpr = parseBinaryAndTernary(1);
    if (!expect(Lexer.TokenType.COLON, "expected ':' in conditional expression")) {
      return lhs;
    }
    CelExpr falseExpr = parseExpr();
    return CelExpr.newBuilder()
        .setId(opId)
        .setCall(
            CelExpr.CelCall.newBuilder()
                .setFunction(Operator.CONDITIONAL.getFunction())
                .addArgs(lhs)
                .addArgs(trueExpr)
                .addArgs(falseExpr)
                .build())
        .build();
  }

  private CelExpr buildBinaryCall(long opId, String opName, CelExpr lhs, CelExpr rhs) {
    return CelExpr.newBuilder()
        .setId(opId)
        .setCall(CelExpr.CelCall.newBuilder().setFunction(opName).addArgs(lhs).addArgs(rhs).build())
        .build();
  }

  private CelExpr parseBalancedLogicalChain(CelExpr lhs, BinaryOpInfo opInfo) {
    List<CelExpr> terms = new ArrayList<>();
    List<Long> ops = new ArrayList<>();
    terms.add(lhs);
    while (peekToken.type == opInfo.type) {
      Lexer.Token opTok = nextToken();
      CelExpr rhs = parseBinaryAndTernary(opInfo.precedence + 1);
      ops.add(nextId(opTok));
      terms.add(rhs);
    }
    return balancedTree(opInfo.name, terms, ops, 0, ops.size() - 1);
  }

  private CelExpr balancedTree(String op, List<CelExpr> terms, List<Long> ops, int lo, int hi) {
    int mid = (lo + hi + 1) / 2;
    CelExpr left;
    if (mid == lo) {
      left = terms.get(mid);
    } else {
      left = balancedTree(op, terms, ops, lo, mid - 1);
    }
    CelExpr right;
    if (mid == hi) {
      right = terms.get(mid + 1);
    } else {
      right = balancedTree(op, terms, ops, mid + 1, hi);
    }
    return CelExpr.newBuilder()
        .setId(ops.get(mid))
        .setCall(CelExpr.CelCall.newBuilder().setFunction(op).addArgs(left).addArgs(right).build())
        .build();
  }

  private CelExpr parseSelectorChain() {
    CelExpr lhs = parseUnary();
    currentLhsDepth = 0;
    Lexer.TokenType tok = peekToken.type;
    if (tok == Lexer.TokenType.DOT
        || tok == Lexer.TokenType.LEFT_BRACKET
        || tok == Lexer.TokenType.LEFT_BRACE) {
      lhs = parseSelectorChainTail(lhs);
    }
    return lhs;
  }

  private CelExpr parseSelectorChainTail(CelExpr initialLhs) {
    CelExpr lhs = initialLhs;
    int chainDepth = 0;
    while (true) {
      Lexer.TokenType tok = peekToken.type;
      if (tok == Lexer.TokenType.DOT) {
        chainDepth++;
        if (checkRecursion(chainDepth, peekToken)) {
          return ERROR;
        }
        Lexer.Token dotTok = nextToken();
        boolean optional = false;
        if (peekToken.type == Lexer.TokenType.QUESTION) {
          nextToken();
          optional = true;
          if (!options.enableOptionalSyntax()) {
            reportError(dotTok.start, "unsupported syntax '.?'");
          }
        }
        Lexer.Token idTok = nextToken();
        if (idTok.type != Lexer.TokenType.IDENT
            && idTok.type != Lexer.TokenType.RESERVED_WORD
            && idTok.type != Lexer.TokenType.IN) {
          if (idTok.type != Lexer.TokenType.ERROR) {
            reportSyntaxError(idTok, "expected identifier after '.'");
          }
          synchronizeOnDelimiter();
          currentLhsDepth = chainDepth;
          return lhs;
        }
        boolean isMemberCall = (peekToken.type == Lexer.TokenType.LEFT_PAREN);
        String idText = normalizeIdent(idTok, /* allowQuoted= */ !isMemberCall);
        if (optional) {
          long opId = nextId(dotTok);
          CelExpr arg1 = lhs;
          CelExpr arg2 =
              CelExpr.newBuilder()
                  .setId(nextId(idTok))
                  .setConstant(CelConstant.ofValue(idText))
                  .build();
          lhs =
              CelExpr.newBuilder()
                  .setId(opId)
                  .setCall(
                      CelExpr.CelCall.newBuilder()
                          .setFunction(Operator.OPTIONAL_SELECT.getFunction())
                          .addArgs(arg1)
                          .addArgs(arg2)
                          .build())
                  .build();
        } else if (peekToken.type == Lexer.TokenType.LEFT_PAREN) {
          Lexer.Token lparen = nextToken();
          long callId = nextId(lparen);
          ImmutableList<CelExpr> args = parseArguments(Lexer.TokenType.RIGHT_PAREN);
          Optional<CelExpr> expanded = tryExpandMacro(callId, idText, lhs, args);
          if (expanded.isPresent()) {
            lhs = expanded.get();
          } else {
            lhs =
                CelExpr.newBuilder()
                    .setId(callId)
                    .setCall(
                        CelExpr.CelCall.newBuilder()
                            .setFunction(idText)
                            .setTarget(lhs)
                            .addArgs(args)
                            .build())
                    .build();
          }
        } else {
          lhs =
              CelExpr.newBuilder()
                  .setId(nextId(dotTok))
                  .setSelect(
                      CelExpr.CelSelect.newBuilder().setOperand(lhs).setField(idText).build())
                  .build();
        }
      } else if (tok == Lexer.TokenType.LEFT_BRACKET) {
        chainDepth++;
        if (checkRecursion(chainDepth, peekToken)) {
          return ERROR;
        }
        Lexer.Token bracketTok = nextToken();
        long opId = nextId(bracketTok);
        boolean optional = false;
        if (peekToken.type == Lexer.TokenType.QUESTION) {
          nextToken();
          optional = true;
          if (!options.enableOptionalSyntax()) {
            reportError(bracketTok.start, "unsupported syntax '?'");
          }
        }
        CelExpr index = parseExpr();
        expect(Lexer.TokenType.RIGHT_BRACKET, "expected ']'");
        String opName =
            optional ? Operator.OPTIONAL_INDEX.getFunction() : Operator.INDEX.getFunction();
        lhs =
            CelExpr.newBuilder()
                .setId(opId)
                .setCall(
                    CelExpr.CelCall.newBuilder()
                        .setFunction(opName)
                        .addArgs(lhs)
                        .addArgs(index)
                        .build())
                .build();
      } else if (tok == Lexer.TokenType.LEFT_BRACE) {
        int structPos = getLeftmostPosition(lhs);
        Optional<String> structName = extractStructName(lhs);
        if (structName.isPresent()) {
          lhs = parseStruct(nextId(structPos), structName.get());
        } else {
          break;
        }
      } else {
        break;
      }
    }
    currentLhsDepth = chainDepth;
    return lhs;
  }

  private CelExpr parseUnary() {
    Lexer.TokenType tok = peekToken.type;
    if (tok == Lexer.TokenType.EXCLAMATION || tok == Lexer.TokenType.MINUS) {
      return parseUnaryOps();
    }
    return parsePrimary();
  }

  private CelExpr parseUnaryOps() {
    Lexer.Token op = nextToken();
    Lexer.TokenType opType = op.type;
    if (peekToken.type == Lexer.TokenType.EXCLAMATION || peekToken.type == Lexer.TokenType.MINUS) {
      return parseUnaryOpsChain(op);
    }

    if (opType == Lexer.TokenType.MINUS) {
      if (peekToken.type == Lexer.TokenType.INT) {
        return parseIntLiteral(nextId(op), /* isNegative= */ true);
      }
      if (peekToken.type == Lexer.TokenType.FLOAT) {
        return parseDoubleLiteral(nextId(op), /* isNegative= */ true);
      }
    }

    if (checkRecursion(1, op)) {
      return ERROR;
    }

    long opId = nextId(op);
    recursionDepth++;
    CelExpr operand = parseSelectorChain();
    recursionDepth--;
    if (recursionLimitExceeded) {
      return ERROR;
    }

    String opName =
        (opType == Lexer.TokenType.EXCLAMATION)
            ? Operator.LOGICAL_NOT.getFunction()
            : Operator.NEGATE.getFunction();
    return CelExpr.newBuilder()
        .setId(opId)
        .setCall(CelExpr.CelCall.newBuilder().setFunction(opName).addArgs(operand).build())
        .build();
  }

  private CelExpr parseUnaryOpsChain(Lexer.Token firstOp) {
    List<UnaryOp> ops = new ArrayList<>();
    ops.add(new UnaryOp(firstOp));
    while (peekToken.type == Lexer.TokenType.EXCLAMATION
        || peekToken.type == Lexer.TokenType.MINUS) {
      ops.add(new UnaryOp(nextToken()));
    }

    boolean hasSolitaryTrailingMinus =
        !ops.isEmpty()
            && Iterables.getLast(ops).token.type == Lexer.TokenType.MINUS
            && (ops.size() == 1 || ops.get(ops.size() - 2).token.type != Lexer.TokenType.MINUS);

    if (!options.retainRepeatedUnaryOperators()) {
      int write = 0;
      for (int read = 0; read < ops.size(); ) {
        int next = read;
        while (next < ops.size() && ops.get(next).token.type == ops.get(read).token.type) {
          next++;
        }
        if ((next - read) % 2 != 0) {
          ops.set(write++, ops.get(read));
        }
        read = next;
      }
      ops = new ArrayList<>(ops.subList(0, write));
    }

    for (UnaryOp op : ops) {
      op.id = nextId(op.token);
    }

    boolean isNegativeNumericLiteral =
        hasSolitaryTrailingMinus
            && (peekToken.type == Lexer.TokenType.INT || peekToken.type == Lexer.TokenType.FLOAT);
    long negativeLiteralOpId = 0;
    if (isNegativeNumericLiteral) {
      negativeLiteralOpId = Iterables.getLast(ops).id;
      ops.remove(ops.size() - 1);
    }

    int chainDepth = 0;
    for (UnaryOp op : ops) {
      chainDepth++;
      if (checkRecursion(chainDepth, op.token)) {
        return ERROR;
      }
    }

    recursionDepth += ops.size();
    CelExpr operand;
    if (isNegativeNumericLiteral) {
      operand =
          (peekToken.type == Lexer.TokenType.INT)
              ? parseIntLiteral(negativeLiteralOpId, /* isNegative= */ true)
              : parseDoubleLiteral(negativeLiteralOpId, /* isNegative= */ true);
      operand = parseSelectorChainTail(operand);
    } else {
      operand = parseSelectorChain();
    }
    recursionDepth -= ops.size();

    if (recursionLimitExceeded) {
      return ERROR;
    }

    for (int i = ops.size() - 1; i >= 0; --i) {
      String opName =
          (ops.get(i).token.type == Lexer.TokenType.EXCLAMATION)
              ? Operator.LOGICAL_NOT.getFunction()
              : Operator.NEGATE.getFunction();
      operand =
          CelExpr.newBuilder()
              .setId(ops.get(i).id)
              .setCall(CelExpr.CelCall.newBuilder().setFunction(opName).addArgs(operand).build())
              .build();
    }

    return operand;
  }

  private CelExpr parseIdentOrCall() {
    Lexer.TokenType tokType = peekToken.type;
    boolean leadingDot = false;
    Lexer.Token firstTok = peekToken;
    if (tokType == Lexer.TokenType.DOT) {
      nextToken();
      leadingDot = true;
    }
    Lexer.Token idTok = nextToken();
    if (idTok.type != Lexer.TokenType.IDENT && idTok.type != Lexer.TokenType.RESERVED_WORD) {
      if (idTok.type != Lexer.TokenType.ERROR) {
        reportSyntaxError(idTok, "expected identifier");
      }
      return CelExpr.newBuilder().setId(nextId(idTok)).build();
    }
    String idText = normalizeIdent(idTok, /* allowQuoted= */ false);
    if (idTok.type == Lexer.TokenType.RESERVED_WORD && options.enableReservedIds()) {
      reportError(idTok.start, String.format("reserved identifier: %s", idText));
    }
    String name = leadingDot ? "." + idText : idText;
    if (peekToken.type == Lexer.TokenType.LEFT_PAREN) {
      Lexer.Token lparen = nextToken();
      long callId = nextId(lparen);
      ImmutableList<CelExpr> args = parseArguments(Lexer.TokenType.RIGHT_PAREN);
      Optional<CelExpr> expanded = tryExpandMacro(callId, name, null, args);
      if (expanded.isPresent()) {
        return expanded.get();
      }
      return CelExpr.newBuilder()
          .setId(callId)
          .setCall(CelExpr.CelCall.newBuilder().setFunction(name).addArgs(args).build())
          .build();
    }
    long id = nextId(leadingDot ? firstTok : idTok);
    return CelExpr.newBuilder()
        .setId(id)
        .setIdent(CelExpr.CelIdent.newBuilder().setName(name).build())
        .build();
  }

  private CelExpr parsePrimary() {
    switch (peekToken.type) {
      case LEFT_PAREN:
        {
          int groupingParenCount = countGroupingParentheses();
          for (int i = 0; i < groupingParenCount; ++i) {
            nextToken();
          }
          CelExpr expr = parseExpr();
          for (int i = 0; i < groupingParenCount; ++i) {
            expect(Lexer.TokenType.RIGHT_PAREN, "");
          }
          return expr;
        }
      case NULL:
        return CelExpr.newBuilder().setId(nextId(nextToken())).setConstant(Constants.NULL).build();
      case TRUE:
      case FALSE:
        {
          Lexer.Token tok = nextToken();
          return CelExpr.newBuilder()
              .setId(nextId(tok))
              .setConstant(tok.type == Lexer.TokenType.TRUE ? Constants.TRUE : Constants.FALSE)
              .build();
        }
      case INT:
        return parseIntLiteral(/* nodeId= */ -1, /* isNegative= */ false);
      case UINT:
        return parseUintLiteral();
      case FLOAT:
        return parseDoubleLiteral(/* nodeId= */ -1, /* isNegative= */ false);
      case STRING:
        return parseStringLiteral();
      case BYTES:
        return parseBytesLiteral();
      case LEFT_BRACKET:
        return parseList();
      case LEFT_BRACE:
        return parseMap();
      case DOT:
      case IDENT:
      case RESERVED_WORD:
        return parseIdentOrCall();
      default:
        {
          Lexer.Token badTok = nextToken();
          if (badTok.type != Lexer.TokenType.ERROR) {
            if (badTok.type == Lexer.TokenType.END) {
              reportSyntaxError(badTok, "mismatched input '<EOF>' expecting expression");
            } else {
              reportSyntaxError(badTok, "unexpected token");
            }
          }
          return CelExpr.newBuilder().setId(nextId(badTok)).build();
        }
    }
  }

  private CelExpr parseList() {
    Lexer.Token openTok = nextToken();
    long listId = nextId(openTok);
    CelExpr.CelList.Builder listBuilder = CelExpr.CelList.newBuilder();
    int elemIndex = 0;
    while (peekToken.type != Lexer.TokenType.RIGHT_BRACKET
        && peekToken.type != Lexer.TokenType.END) {
      boolean optional = false;
      if (peekToken.type == Lexer.TokenType.QUESTION) {
        Lexer.Token q = nextToken();
        optional = true;
        if (!options.enableOptionalSyntax()) {
          reportError(q.start, "unsupported syntax '?'");
        }
      }
      listBuilder.addElements(parseExpr());
      if (optional) {
        listBuilder.addOptionalIndices(elemIndex);
      }
      elemIndex++;
      if (peekToken.type == Lexer.TokenType.COMMA) {
        nextToken();
      } else {
        break;
      }
    }
    expect(Lexer.TokenType.RIGHT_BRACKET, "expected ']'");
    return CelExpr.newBuilder().setId(listId).setList(listBuilder.build()).build();
  }

  private CelExpr parseMap() {
    Lexer.Token openTok = nextToken();
    long mapId = nextId(openTok);
    CelExpr.CelMap.Builder mapBuilder = CelExpr.CelMap.newBuilder();
    while (peekToken.type != Lexer.TokenType.RIGHT_BRACE && peekToken.type != Lexer.TokenType.END) {
      boolean optional = false;
      Lexer.Token keyStart = peekToken;
      if (keyStart.type == Lexer.TokenType.QUESTION) {
        Lexer.Token q = nextToken();
        optional = true;
        if (!options.enableOptionalSyntax()) {
          reportError(q.start, "unsupported syntax '?'");
        }
        keyStart = peekToken;
      }
      long entryId = nextId();
      CelExpr key = parseExpr();
      Lexer.Token colon = peekToken;
      if (!expect(Lexer.TokenType.COLON, "expected ':' in map entry")) {
        break;
      }
      setPosition(entryId, colon);
      CelExpr value = parseExpr();
      mapBuilder.addEntries(
          CelExpr.CelMap.Entry.newBuilder()
              .setId(entryId)
              .setKey(key)
              .setValue(value)
              .setOptionalEntry(optional)
              .build());
      if (peekToken.type == Lexer.TokenType.COMMA) {
        nextToken();
      } else {
        break;
      }
    }
    expect(Lexer.TokenType.RIGHT_BRACE, "expected '}'");
    return CelExpr.newBuilder().setId(mapId).setMap(mapBuilder.build()).build();
  }

  private CelExpr parseStruct(long objId, String structName) {
    nextToken();
    CelExpr.CelStruct.Builder structBuilder =
        CelExpr.CelStruct.newBuilder().setMessageName(structName);
    while (peekToken.type != Lexer.TokenType.RIGHT_BRACE && peekToken.type != Lexer.TokenType.END) {
      boolean optional = false;
      if (peekToken.type == Lexer.TokenType.QUESTION) {
        Lexer.Token q = nextToken();
        optional = true;
        if (!options.enableOptionalSyntax()) {
          reportError(q.start, "unsupported syntax '?'");
        }
      }
      Lexer.Token fieldTok = nextToken();
      if (fieldTok.type != Lexer.TokenType.IDENT
          && fieldTok.type != Lexer.TokenType.RESERVED_WORD) {
        reportSyntaxError(fieldTok, "expected struct field name");
        synchronizeOnDelimiter();
        break;
      }
      String fieldName = normalizeIdent(fieldTok, /* allowQuoted= */ true);
      Lexer.Token colon = peekToken;
      if (!expect(Lexer.TokenType.COLON, "expected ':' in struct field")) {
        break;
      }
      long fieldId = nextId(colon);
      CelExpr value = parseExpr();
      structBuilder.addEntries(
          CelExpr.CelStruct.Entry.newBuilder()
              .setId(fieldId)
              .setFieldKey(fieldName)
              .setValue(value)
              .setOptionalEntry(optional)
              .build());
      if (peekToken.type == Lexer.TokenType.COMMA) {
        nextToken();
      } else {
        break;
      }
    }
    expect(Lexer.TokenType.RIGHT_BRACE, "expected '}'");
    return CelExpr.newBuilder().setId(objId).setStruct(structBuilder.build()).build();
  }

  private ImmutableList<CelExpr> parseArguments(Lexer.TokenType closeToken) {
    ImmutableList.Builder<CelExpr> args = ImmutableList.builder();
    if (peekToken.type != closeToken && peekToken.type != Lexer.TokenType.END) {
      while (true) {
        args.add(parseExpr());
        if (peekToken.type == Lexer.TokenType.COMMA) {
          nextToken();
          if (peekToken.type == closeToken) {
            reportError(peekToken.start, "unexpected token");
            break;
          }
          continue;
        }
        break;
      }
    }
    expect(closeToken, "");
    return args.build();
  }

  private CelExpr parseIntLiteral(long nodeId, boolean isNegative) {
    Lexer.Token tok = nextToken();
    String text = isNegative ? "-" + getTokenText(tok) : getTokenText(tok);
    long id = nodeId == -1 ? nextId(tok) : nodeId;
    try {
      CelConstant constExpr = Constants.parseInt(text);
      return CelExpr.newBuilder().setId(id).setConstant(constExpr).build();
    } catch (ParseException e) {
      reportSyntaxError(tok, "invalid int literal");
      return CelExpr.newBuilder().setId(nextId(tok)).build();
    }
  }

  private CelExpr parseUintLiteral() {
    Lexer.Token tok = nextToken();
    String value = getTokenText(tok);
    try {
      CelConstant constExpr = Constants.parseUint(value);
      return CelExpr.newBuilder().setId(nextId(tok)).setConstant(constExpr).build();
    } catch (ParseException e) {
      reportSyntaxError(tok, "invalid uint literal");
      return CelExpr.newBuilder().setId(nextId(tok)).build();
    }
  }

  private CelExpr parseDoubleLiteral(long nodeId, boolean isNegative) {
    Lexer.Token tok = nextToken();
    String text = isNegative ? "-" + getTokenText(tok) : getTokenText(tok);
    long id = nodeId == -1 ? nextId(tok) : nodeId;
    try {
      CelConstant constExpr = Constants.parseDouble(text);
      if (Double.isInfinite(constExpr.doubleValue())) {
        reportSyntaxError(tok, "invalid double literal");
        return CelExpr.newBuilder().setId(id).build();
      }
      return CelExpr.newBuilder().setId(id).setConstant(constExpr).build();
    } catch (ParseException e) {
      reportSyntaxError(tok, "invalid double literal");
      return CelExpr.newBuilder().setId(nextId(tok)).build();
    }
  }

  private CelExpr parseStringLiteral() {
    Lexer.Token tok = nextToken();
    String value = getTokenText(tok);
    try {
      CelConstant constExpr = Constants.parseString(value);
      return CelExpr.newBuilder().setId(nextId(tok)).setConstant(constExpr).build();
    } catch (ParseException e) {
      reportError(tok.start, e.getMessage());
      return CelExpr.newBuilder().setId(nextId(tok)).build();
    }
  }

  private CelExpr parseBytesLiteral() {
    Lexer.Token tok = nextToken();
    String value = getTokenText(tok);
    try {
      CelConstant constExpr = Constants.parseBytes(value);
      return CelExpr.newBuilder().setId(nextId(tok)).setConstant(constExpr).build();
    } catch (ParseException e) {
      reportError(tok.start, e.getMessage());
      return CelExpr.newBuilder().setId(nextId(tok)).build();
    }
  }

  private String normalizeIdent(Lexer.Token tok, boolean allowQuoted) {
    String text = getTokenText(tok);
    if (text.isEmpty()) {
      return "";
    }
    if (text.charAt(0) == '`') {
      if (!allowQuoted) {
        reportError(tok.start, "unexpected quoted identifier");
        return "";
      }
      if (!options.enableQuotedIdentifierSyntax()) {
        reportError(tok.start, "unsupported syntax '`'");
      }
      if (text.length() < 2 || text.charAt(text.length() - 1) != '`') {
        reportError(tok.start, "unterminated quoted identifier");
        return "";
      }
      String inner = text.substring(1, text.length() - 1);
      if (inner.isEmpty()) {
        reportError(tok.start, "unexpected quoted identifier");
        return "";
      }
      for (int i = 0; i < inner.length(); i++) {
        char c = inner.charAt(i);
        if (!isAsciiAlphanumeric(c) && c != '_' && c != '.' && c != '-' && c != '/' && c != ' ') {
          reportError(tok.start, "unexpected quoted identifier");
          return "";
        }
      }
      return inner;
    }
    return text;
  }

  private static boolean isAsciiAlphanumeric(char c) {
    return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
  }

  private Optional<String> extractStructName(CelExpr expr) {
    if (expr.exprKind().getKind() == CelExpr.ExprKind.Kind.IDENT) {
      String name = expr.ident().name();
      eraseId(expr.id());
      return Optional.of(name);
    }
    if (expr.exprKind().getKind() == CelExpr.ExprKind.Kind.SELECT) {
      if (expr.select().testOnly()) {
        return Optional.empty();
      }
      CelExpr operand = expr.select().operand();
      eraseId(expr.id());
      Optional<String> prefix = extractStructName(operand);
      if (!prefix.isPresent()) {
        return Optional.empty();
      }
      return Optional.of(prefix.get() + "." + expr.select().field());
    }
    return Optional.empty();
  }

  private int getLeftmostPosition(CelExpr expr) {
    if (expr.exprKind().getKind() == CelExpr.ExprKind.Kind.IDENT) {
      return positions.getOrDefault(expr.id(), 0);
    }
    if (expr.exprKind().getKind() == CelExpr.ExprKind.Kind.SELECT) {
      return getLeftmostPosition(expr.select().operand());
    }
    return positions.getOrDefault(expr.id(), 0);
  }

  private Optional<CelMacro> lookupMacro(String id, int argCount, boolean receiverStyle) {
    String key = CelMacro.formatKey(id, argCount, receiverStyle);
    CelMacro macro = macros.get(key);
    if (macro != null) {
      return Optional.of(macro);
    }
    key = CelMacro.formatVarArgKey(id, receiverStyle);
    return Optional.ofNullable(macros.get(key));
  }

  private Optional<CelExpr> tryExpandMacro(
      long exprId, String function, @Nullable CelExpr target, ImmutableList<CelExpr> args) {
    if (function.isEmpty()) {
      return Optional.empty();
    }
    boolean isReceiver = (target != null);
    int argCount = args.size();
    Optional<CelMacro> macro = lookupMacro(function, argCount, isReceiver);
    if (!macro.isPresent()) {
      return Optional.empty();
    }
    if (nodeLimitExceeded) {
      reportError(
          positions.getOrDefault(exprId, 0),
          "could not expand macro: expression node limit exceeded");
      return Optional.empty();
    }

    Optional<CelExpr> errorArg = args.stream().filter(ERROR::equals).findAny();
    if (errorArg.isPresent() || (target != null && target.equals(ERROR))) {
      eraseId(exprId);
      return Optional.of(ERROR);
    }

    int macroPosition = positions.getOrDefault(exprId, 0);
    CelExpr targetExpr = (target != null ? target : CelExpr.newBuilder().build());
    Optional<CelExpr> expandedExpr = expandMacro(macroPosition, macro.get(), targetExpr, args);

    if (expandedExpr.isPresent()) {
      if (options.populateMacroCalls()) {
        recordMacroCall(expandedExpr.get().id(), function, target, args);
      }
      eraseId(exprId);
      return expandedExpr;
    }
    return Optional.empty();
  }

  private Optional<CelExpr> expandMacro(
      int position, CelMacro macro, CelExpr target, ImmutableList<CelExpr> arguments) {
    macroExprFactory.pushPosition(position);
    try {
      return macro.getExpander().expandMacro(macroExprFactory, target, arguments);
    } finally {
      macroExprFactory.popPosition();
    }
  }

  private void recordMacroCall(
      long macroId, String function, CelExpr target, ImmutableList<CelExpr> args) {
    CelExpr.CelCall.Builder callExpr = CelExpr.CelCall.newBuilder().setFunction(function);
    if (target != null) {
      if (macroCalls.containsKey(target.id())) {
        callExpr.setTarget(CelExpr.newBuilder().setId(target.id()).build());
      } else {
        callExpr.setTarget(buildMacroCallArgs(target));
      }
    }
    for (CelExpr arg : args) {
      callExpr.addArgs(buildMacroCallArgs(arg));
    }
    macroCalls.put(macroId, CelExpr.newBuilder().setCall(callExpr.build()).build());
  }

  private CelExpr buildMacroCallArgs(CelExpr expr) {
    CelExpr.Builder resultExpr = CelExpr.newBuilder().setId(expr.id());
    if (macroCalls.containsKey(expr.id())) {
      return resultExpr.build();
    }
    if (expr.exprKind().getKind() == CelExpr.ExprKind.Kind.CALL) {
      CelExpr.CelCall.Builder callExpr =
          CelExpr.CelCall.newBuilder().setFunction(expr.call().function());
      expr.call().args().forEach(arg -> callExpr.addArgs(buildMacroCallArgs(arg)));
      expr.call().target().ifPresent(target -> callExpr.setTarget(buildMacroCallArgs(target)));
      return resultExpr.setCall(callExpr.build()).build();
    }
    return expr;
  }

  private int countGroupingParentheses() {
    if (peekToken.type != Lexer.TokenType.LEFT_PAREN) {
      return 0;
    }

    int savedPos = lexer.savePosition();
    try {
      int leadingOpenParens = 1;
      Lexer.Token tok = nextSignificantToken(/* reportError= */ false);
      while (tok.type == Lexer.TokenType.LEFT_PAREN) {
        leadingOpenParens++;
        tok = nextSignificantToken(/* reportError= */ false);
      }
      if (leadingOpenParens == 1) {
        return 1;
      }

      int openParens = leadingOpenParens;
      int consecutiveLeadingClosed = 0;

      while (openParens > 0) {
        if (tok.type == Lexer.TokenType.END || tok.type == Lexer.TokenType.ERROR) {
          return 1;
        }

        if (tok.type == Lexer.TokenType.LEFT_PAREN) {
          openParens++;
          consecutiveLeadingClosed = 0;
        } else if (tok.type == Lexer.TokenType.RIGHT_PAREN) {
          if (leadingOpenParens == openParens) {
            leadingOpenParens--;
            consecutiveLeadingClosed++;
          } else {
            consecutiveLeadingClosed = 0;
          }
          openParens--;
        } else {
          consecutiveLeadingClosed = 0;
        }

        if (openParens > 0) {
          tok = nextSignificantToken(/* reportError= */ false);
        }
      }

      return Math.max(1, consecutiveLeadingClosed);
    } finally {
      lexer.restorePosition(savedPos);
    }
  }

  private final class PrattMacroExprFactory extends CelMacroExprFactory {
    private final ArrayDeque<Integer> macroPositions = new ArrayDeque<>(1);

    void pushPosition(int position) {
      macroPositions.addLast(position);
    }

    void popPosition() {
      macroPositions.removeLast();
    }

    int peekPosition() {
      return macroPositions.peekLast();
    }

    @Override
    public CelExpr reportError(CelIssue error) {
      issues.add(error);
      if (!error.getSourceLocation().equals(CelSourceLocation.NONE)) {
        Optional<Integer> offset = source.getLocationOffset(error.getSourceLocation());
        if (offset.isPresent()) {
          return CelExpr.newBuilder().setId(nextId(offset.get())).build();
        }
      }
      return ERROR;
    }

    @Override
    public String getAccumulatorVarName() {
      return ACCUMULATOR_NAME;
    }

    @Override
    protected CelSourceLocation getSourceLocation(long exprId) {
      int pos = positions.getOrDefault(exprId, -1);
      return source.getOffsetLocation(pos).orElse(CelSourceLocation.NONE);
    }

    @Override
    protected CelSourceLocation currentSourceLocationForMacro() {
      int pos =
          !macroPositions.isEmpty()
              ? peekPosition()
              : (currentToken != null ? currentToken.start : 0);
      return source.getOffsetLocation(pos).orElse(CelSourceLocation.NONE);
    }

    @Override
    protected long copyExprId(long id) {
      return copyId(id);
    }

    @Override
    public long nextExprId() {
      int pos = !macroPositions.isEmpty() ? peekPosition() : -1;
      return nextId(pos);
    }
  }
}
