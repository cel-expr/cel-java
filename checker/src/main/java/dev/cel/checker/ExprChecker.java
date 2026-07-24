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

package dev.cel.checker;

import static com.google.common.base.Preconditions.checkNotNull;

import dev.cel.expr.CheckedExpr;
import dev.cel.expr.ParsedExpr;
import dev.cel.expr.Type;
import com.google.auto.value.AutoValue;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;
import com.google.errorprone.annotations.CheckReturnValue;
import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.CelContainer;
import dev.cel.common.CelFunctionDecl;
import dev.cel.common.CelMutableAst;
import dev.cel.common.CelOverloadDecl;
import dev.cel.common.CelProtoAbstractSyntaxTree;
import dev.cel.common.CelSource;
import dev.cel.common.Operator;
import dev.cel.common.annotations.Internal;
import dev.cel.common.ast.CelConstant;
import dev.cel.common.ast.CelExpr;
import dev.cel.common.ast.CelMutableExpr;
import dev.cel.common.ast.CelMutableExpr.CelMutableCall;
import dev.cel.common.ast.CelMutableExpr.CelMutableComprehension;
import dev.cel.common.ast.CelMutableExpr.CelMutableIdent;
import dev.cel.common.ast.CelMutableExpr.CelMutableList;
import dev.cel.common.ast.CelMutableExpr.CelMutableMap;
import dev.cel.common.ast.CelMutableExpr.CelMutableSelect;
import dev.cel.common.ast.CelMutableExpr.CelMutableStruct;
import dev.cel.common.ast.CelReference;
import dev.cel.common.types.CelKind;
import dev.cel.common.types.CelProtoTypes;
import dev.cel.common.types.CelType;
import dev.cel.common.types.CelTypes;
import dev.cel.common.types.ListType;
import dev.cel.common.types.MapType;
import dev.cel.common.types.OptionalType;
import dev.cel.common.types.ProtoMessageType;
import dev.cel.common.types.SimpleType;
import dev.cel.common.types.TypeType;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The expression type checker.
 *
 * <p>CEL-Java library internals. Do not use.
 *
 * @deprecated Please migrate to CEL-Java Fluent APIs instead. See {@code CelCompilerFactory}.
 */
@Internal
@Deprecated
public final class ExprChecker {
  private static final CelSource.Extension JSON_NAME_EXTENSION =
      CelSource.Extension.create(
          "json_name",
          CelSource.Extension.Version.of(1, 1),
          CelSource.Extension.Component.COMPONENT_RUNTIME);

  /**
   * Deprecated type-check API.
   *
   * @deprecated Do not use. CEL-Java users should leverage the Fluent APIs instead. See {@code
   *     CelCompilerFactory}.
   */
  @CheckReturnValue
  @Deprecated
  public static CheckedExpr check(Env env, String inContainer, ParsedExpr parsedExpr) {
    return typecheck(env, inContainer, parsedExpr, Optional.absent());
  }

  /**
   * Deprecated type-check API.
   *
   * @deprecated Do not use. CEL-Java users should leverage the Fluent APIs instead. See {@code
   *     CelCompilerFactory}.
   */
  @CheckReturnValue
  @Deprecated
  public static CheckedExpr typecheck(
      Env env, String inContainer, ParsedExpr parsedExpr, Optional<Type> expectedResultType) {
    Optional<CelType> type =
        expectedResultType.isPresent()
            ? Optional.of(CelProtoTypes.typeToCelType(expectedResultType.get()))
            : Optional.absent();
    CelAbstractSyntaxTree ast =
        typecheck(
            env,
            CelContainer.ofName(inContainer),
            CelProtoAbstractSyntaxTree.fromParsedExpr(parsedExpr).getAst(),
            type);

    if (ast.isChecked()) {
      return CelProtoAbstractSyntaxTree.fromCelAst(ast).toCheckedExpr();
    }

    return CheckedExpr.newBuilder()
        .setExpr(parsedExpr.getExpr())
        .setSourceInfo(parsedExpr.getSourceInfo())
        .build();
  }

  /**
   * Type-checks the parsed expression within the given environment and returns a checked
   * expression. If an expected result type was given, then it verifies that that type matches the
   * actual result type. Conditions for type checking and the constructed {@link CheckedExpr} are
   * described in checked.proto.
   *
   * <p>CEL Library Internals. Do not use. CEL-Java users should use the Fluent APIs instead.
   */
  @CheckReturnValue
  @Internal
  public static CelAbstractSyntaxTree typecheck(
      Env env,
      CelContainer container,
      CelAbstractSyntaxTree ast,
      Optional<CelType> expectedResultType) {
    env.resetTypeAndRefMaps();
    final ExprChecker checker =
        new ExprChecker(
            env,
            container,
            ast.getSource().getPositionsMap(),
            new InferenceContext(),
            env.enableCompileTimeOverloadResolution(),
            env.enableHomogeneousLiterals(),
            env.enableNamespacedDeclarations());

    CelMutableAst mutableAst = CelMutableAst.fromCelAst(ast);
    checker.visit(mutableAst.expr());
    if (expectedResultType.isPresent()) {
      checker.assertType(mutableAst.expr(), expectedResultType.get());
    }
    // Walk over the final type map substituting any type parameters either by their bound value or
    // by DYN.
    Map<Long, CelType> typeMap =
        Maps.transformValues(env.getTypeMap(), checker.inferenceContext::finalize);

    CelAbstractSyntaxTree parsedAst = mutableAst.toParsedAst(/* retainSourcePositions= */ true);
    return CelAbstractSyntaxTree.newCheckedAst(
        parsedAst.getExpr(),
        parsedAst.getSource().toBuilder().addAllExtensions(checker.extensions).build(),
        env.getRefMap(),
        typeMap);
  }

  private final Env env;
  private final TypeProvider typeProvider;
  private final CelContainer container;
  private final Map<Long, Integer> positionMap;
  private final InferenceContext inferenceContext;
  private final boolean compileTimeOverloadResolution;
  private final boolean homogeneousLiterals;
  private final boolean namespacedDeclarations;
  private final Set<CelSource.Extension> extensions;

  private ExprChecker(
      Env env,
      CelContainer container,
      Map<Long, Integer> positionMap,
      InferenceContext inferenceContext,
      boolean compileTimeOverloadResolution,
      boolean homogeneousLiterals,
      boolean namespacedDeclarations) {
    this.env = checkNotNull(env);
    this.typeProvider = env.getTypeProvider();
    this.positionMap = checkNotNull(positionMap);
    this.container = checkNotNull(container);
    this.inferenceContext = checkNotNull(inferenceContext);
    this.compileTimeOverloadResolution = compileTimeOverloadResolution;
    this.homogeneousLiterals = homogeneousLiterals;
    this.namespacedDeclarations = namespacedDeclarations;
    this.extensions = new HashSet<>();
  }

  /** Visit the {@code expr} value, routing to overloads based on the kind of expression. */
  public void visit(CelMutableExpr expr) {
    switch (expr.getKind()) {
      case CONSTANT:
        visit(expr, expr.constant());
        break;
      case IDENT:
        visit(expr, expr.ident());
        break;
      case SELECT:
        visit(expr, expr.select());
        break;
      case CALL:
        visit(expr, expr.call());
        break;
      case LIST:
        visit(expr, expr.list());
        break;
      case STRUCT:
        visit(expr, expr.struct());
        break;
      case MAP:
        visit(expr, expr.map());
        break;
      case COMPREHENSION:
        visit(expr, expr.comprehension());
        break;
      default:
        throw new IllegalArgumentException("unexpected expr kind");
    }
  }

  private void visit(CelMutableExpr expr, CelConstant constant) {
    switch (constant.getKind()) {
      case INT64_VALUE:
        env.setType(expr, SimpleType.INT);
        break;
      case UINT64_VALUE:
        env.setType(expr, SimpleType.UINT);
        break;
      case STRING_VALUE:
        env.setType(expr, SimpleType.STRING);
        break;
      case BYTES_VALUE:
        env.setType(expr, SimpleType.BYTES);
        break;
      case BOOLEAN_VALUE:
        env.setType(expr, SimpleType.BOOL);
        break;
      case NULL_VALUE:
        env.setType(expr, SimpleType.NULL_TYPE);
        break;
      case DOUBLE_VALUE:
        env.setType(expr, SimpleType.DOUBLE);
        break;
      case TIMESTAMP_VALUE:
        env.setType(expr, SimpleType.TIMESTAMP);
        break;
      case DURATION_VALUE:
        env.setType(expr, SimpleType.DURATION);
        break;
      default:
        throw new IllegalArgumentException("unexpected constant case: " + constant.getKind());
    }
  }

  private void visit(CelMutableExpr expr, CelMutableIdent ident) {
    CelIdentDecl decl = env.lookupIdent(expr.id(), getPosition(expr), container, ident.name());
    checkNotNull(decl);
    if (decl.equals(Env.ERROR_IDENT_DECL)) {
      // error reported
      env.setType(expr, SimpleType.ERROR);
      env.setRef(expr, makeReference(decl.name(), decl));
      return;
    }

    String refName = maybeDisambiguate(ident.name(), decl.name());

    if (!refName.equals(ident.name())) {
      // Overwrite the identifier with its fully qualified name.
      expr.setIdent(CelMutableIdent.create(refName));
    }

    if (decl.isInlinable()) {
      decl.constant()
          .ifPresent(
              constant -> {
                expr.setConstant(constant);
                visit(expr, expr.constant());
              });
    } else {
      env.setType(expr, decl.type());
      env.setRef(expr, makeReference(refName, decl));
    }
  }

  private void visit(CelMutableExpr expr, CelMutableSelect select) {
    // Before traversing down the tree, try to interpret as qualified name.
    String qname = asQualifiedName(expr);
    if (qname != null) {
      CelIdentDecl decl = env.tryLookupCelIdent(container, qname);
      if (decl != null) {
        if (select.testOnly()) {
          env.reportError(expr.id(), getPosition(expr), "expression does not select a field");
          env.setType(expr, SimpleType.BOOL);
        } else {
          String refName = maybeDisambiguate(qname, decl.name());

          if (namespacedDeclarations) {
            // Rewrite the node to be a variable reference to the resolved fully-qualified
            // variable name.
            expr.setIdent(CelMutableIdent.create(refName));
          }
          env.setType(expr, decl.type());
          env.setRef(expr, makeReference(refName, decl));
        }
        return;
      }
    }
    // Interpret as field selection, first traversing down the operand.
    visit(select.operand());

    CelType resultType = visitSelectField(expr, select.operand(), select.field(), false);

    if (select.testOnly()) {
      resultType = SimpleType.BOOL;
    }
    env.setType(expr, resultType);
  }

  private void visit(CelMutableExpr expr, CelMutableCall call) {
    String functionName = call.function();
    if (Operator.OPTIONAL_SELECT.getFunction().equals(functionName)) {
      visitOptionalCall(expr, call);
      return;
    }
    // Traverse arguments.
    for (CelMutableExpr arg : call.args()) {
      visit(arg);
    }

    int position = getPosition(expr);
    OverloadResolution resolution;

    if (!call.target().isPresent()) {
      // Regular static call with simple name.
      CelFunctionDecl decl = env.lookupFunction(expr.id(), position, container, call.function());
      resolution = resolveOverload(expr.id(), position, decl, null, call.args());

      if (!decl.name().equals(call.function())) {
        if (namespacedDeclarations) {
          // Overwrite the function name with its fully qualified resolved name.
          expr.setCall(CelMutableCall.create(decl.name(), call.args()));
        }
      }
    } else {
      // Check whether the target is actually a qualified name for a static function.
      String qualifiedName = asQualifiedName(call.target().get());
      CelFunctionDecl decl =
          env.tryLookupCelFunction(container, qualifiedName + "." + call.function());
      if (decl != null) {
        resolution = resolveOverload(expr.id(), position, decl, null, call.args());

        if (namespacedDeclarations) {
          // The function name is namespaced and so preserving the target operand would
          // be an inaccurate representation of the desired evaluation behavior.
          // Overwrite with fully-qualified resolved function name sans receiver target.
          expr.setCall(CelMutableCall.create(decl.name(), call.args()));
        }
      } else {
        // Regular instance call.
        CelMutableExpr target = call.target().get();
        visit(target);
        resolution =
            resolveOverload(
                expr.id(),
                position,
                env.lookupFunction(expr.id(), getPosition(expr), container, call.function()),
                target,
                call.args());
      }
    }

    env.setType(expr, resolution.type());
    env.setRef(expr, resolution.reference());
  }

  private void visit(CelMutableExpr expr, CelMutableStruct struct) {
    // Determine the type of the message.
    CelType messageType = SimpleType.ERROR;
    CelIdentDecl decl =
        env.lookupIdent(expr.id(), getPosition(expr), container, struct.messageName());
    if (!struct.messageName().equals(decl.name())) {
      struct.setMessageName(decl.name());
    }

    env.setRef(expr, CelReference.newBuilder().setName(decl.name()).build());
    CelType type = decl.type();
    if (!type.kind().equals(CelKind.ERROR)) {
      if (!type.kind().equals(CelKind.TYPE)) {
        // expected type of types
        env.reportError(expr.id(), getPosition(expr), "'%s' is not a type", CelTypes.format(type));
      } else {
        messageType = ((TypeType) type).type();
        if (!messageType.kind().equals(CelKind.STRUCT)) {
          env.reportError(
              expr.id(),
              getPosition(expr),
              "'%s' is not a message type",
              CelTypes.format(messageType));
          messageType = SimpleType.ERROR;
        }
      }
    }

    // When the type is well-known mark the expression with the CEL type rather than the proto type.
    if (Env.isWellKnownType(messageType)) {
      env.setType(expr, Env.getWellKnownType(messageType));
    } else {
      env.setType(expr, messageType);
    }

    // Check the field initializers.
    List<CelMutableStruct.Entry> entriesList = struct.entries();
    for (CelMutableStruct.Entry entry : entriesList) {
      CelMutableExpr value = entry.value();
      visit(value);

      CelType fieldType =
          getFieldType(entry.id(), getPosition(entry), messageType, entry.fieldKey()).celType();
      CelType valueType = env.getType(value);
      if (entry.optionalEntry()) {
        if (valueType instanceof OptionalType) {
          valueType = unwrapOptional(valueType);
        } else {
          assertIsAssignable(
              value.id(), getPosition(value), valueType, OptionalType.create(valueType));
        }
      }
      if (!inferenceContext.isAssignable(fieldType, valueType)) {
        env.reportError(
            expr.id(),
            getPosition(entry),
            "expected type of field '%s' is '%s' but provided type is '%s'",
            entry.fieldKey(),
            CelTypes.format(fieldType),
            CelTypes.format(valueType));
      }
    }
  }

  private void visit(CelMutableExpr expr, CelMutableMap map) {
    CelType mapKeyType = null;
    CelType mapValueType = null;
    List<CelMutableMap.Entry> entriesList = map.entries();
    for (CelMutableMap.Entry entry : entriesList) {
      CelMutableExpr key = entry.key();
      visit(key);

      mapKeyType = joinTypes(key.id(), getPosition(key), mapKeyType, env.getType(key));

      CelMutableExpr value = entry.value();
      visit(value);

      CelType valueType = env.getType(value);
      if (entry.optionalEntry()) {
        if (valueType instanceof OptionalType) {
          valueType = unwrapOptional(valueType);
        } else {
          assertIsAssignable(
              value.id(), getPosition(value), valueType, OptionalType.create(valueType));
        }
      }

      mapValueType = joinTypes(value.id(), getPosition(value), mapValueType, valueType);
    }
    if (mapKeyType == null) {
      // If the map is empty, assign free type variables to key and value type.
      mapKeyType = inferenceContext.newTypeVar("key");
      mapValueType = inferenceContext.newTypeVar("value");
    }
    env.setType(expr, MapType.create(mapKeyType, mapValueType));
  }

  private void visit(CelMutableExpr expr, CelMutableList list) {
    CelType elemsType = null;
    List<CelMutableExpr> elementsList = list.elements();
    HashSet<Integer> optionalIndices = new HashSet<>(list.optionalIndices());
    for (int i = 0; i < elementsList.size(); i++) {
      CelMutableExpr elem = elementsList.get(i);
      visit(elem);

      CelType elemType = env.getType(elem);
      if (optionalIndices.contains(i)) {
        if (elemType instanceof OptionalType) {
          elemType = unwrapOptional(elemType);
        } else {
          assertIsAssignable(elem.id(), getPosition(elem), elemType, OptionalType.create(elemType));
        }
      }

      elemsType = joinTypes(elem.id(), getPosition(elem), elemsType, elemType);
    }
    if (elemsType == null) {
      // If the list is empty, assign free type var to elem type.
      elemsType = inferenceContext.newTypeVar("elem");
    }
    env.setType(expr, ListType.create(elemsType));
  }

  private void visit(CelMutableExpr expr, CelMutableComprehension compre) {
    visit(compre.iterRange());
    visit(compre.accuInit());
    CelType accuType = env.getType(compre.accuInit());
    CelType rangeType = inferenceContext.specialize(env.getType(compre.iterRange()));
    CelType varType;
    CelType varType2 = null;
    switch (rangeType.kind()) {
      case LIST:
        varType = ((ListType) rangeType).elemType();
        if (!Strings.isNullOrEmpty(compre.iterVar2())) {
          varType2 = varType;
          varType = SimpleType.INT;
        }
        break;
      case MAP:
        // Ranges over the keys.
        varType = ((MapType) rangeType).keyType();
        if (!Strings.isNullOrEmpty(compre.iterVar2())) {
          varType2 = ((MapType) rangeType).valueType();
        }
        break;
      case DYN:
      case ERROR:
        varType = SimpleType.DYN;
        varType2 = SimpleType.DYN;
        break;
      case TYPE_PARAM:
        // Mark the range as DYN to avoid its free variable being associated with the wrong type
        // based on an earlier or later use. The isAssignable call will ensure that type
        // substitutions are updated for the type param.
        inferenceContext.isAssignable(SimpleType.DYN, rangeType);
        // Mark the variable type as DYN.
        varType = SimpleType.DYN;
        varType2 = SimpleType.DYN;
        break;
      default:
        env.reportError(
            expr.id(),
            getPosition(compre.iterRange()),
            "expression of type '%s' cannot be range of a comprehension "
                + "(must be list, map, or dynamic)",
            CelTypes.format(rangeType));
        varType = SimpleType.DYN;
        varType2 = SimpleType.DYN;
        break;
    }

    // Declare accumulation variable on outer scope.
    env.enterScope();
    env.add(CelIdentDecl.newIdentDeclaration(compre.accuVar(), accuType));
    // Declare iteration variable on inner scope.
    env.enterScope();
    env.add(CelIdentDecl.newIdentDeclaration(compre.iterVar(), varType));
    if (!Strings.isNullOrEmpty(compre.iterVar2())) {
      env.add(CelIdentDecl.newIdentDeclaration(compre.iterVar2(), varType2));
    }
    visit(compre.loopCondition());
    assertType(compre.loopCondition(), SimpleType.BOOL);
    visit(compre.loopStep());
    assertType(compre.loopStep(), accuType);
    // Forget iteration variable, as result expression must only depend on accu.
    env.exitScope();
    visit(compre.result());
    env.exitScope();

    env.setType(expr, inferenceContext.specialize(env.getType(compre.result())));
  }

  private CelReference makeReference(String name, CelIdentDecl decl) {
    CelReference.Builder ref = CelReference.newBuilder().setName(name);
    if (decl.constant().isPresent()) {
      ref.setValue(decl.constant().get());
    }
    return ref.build();
  }

  /**
   * Returns the reference name, prefixed with a leading dot only if disambiguation is needed.
   * Disambiguation is needed when: the original name had a leading dot, and there's a local
   * variable that would shadow the resolved name.
   */
  private String maybeDisambiguate(String originalName, String resolvedName) {
    if (!originalName.startsWith(".")) {
      return resolvedName;
    }
    String simpleName = originalName.substring(1);
    int dotIndex = simpleName.indexOf('.');
    String localName = dotIndex > 0 ? simpleName.substring(0, dotIndex) : simpleName;
    if (env.tryLookupCelIdentFromLocalScopes(localName) != null) {
      return "." + resolvedName;
    }
    return resolvedName;
  }

  private OverloadResolution resolveOverload(
      long callExprId,
      int position,
      @Nullable CelFunctionDecl function,
      @Nullable CelMutableExpr target,
      List<CelMutableExpr> args) {
    if (function == null || function.equals(Env.ERROR_FUNCTION_DECL)) {
      // Error reported, just return error value.
      return OverloadResolution.of(CelReference.newBuilder().build(), SimpleType.ERROR);
    }
    List<CelType> argTypes = new ArrayList<>();
    if (target != null) {
      argTypes.add(env.getType(target));
    }
    for (CelMutableExpr arg : args) {
      argTypes.add(env.getType(arg));
    }
    CelType resultType = null; // For most common result type.
    String firstCandString = null;
    CelReference.Builder refBuilder = CelReference.newBuilder();
    List<String> excludedCands = new ArrayList<>();
    String expectedString =
        TypeFormatter.formatFunction(inferenceContext.specialize(argTypes), target != null);
    for (CelOverloadDecl overload : function.overloads()) {
      boolean isInstance = overload.isInstanceFunction();
      if ((target == null && isInstance) || (target != null && !isInstance)) {
        // not a compatible call style.
        continue;
      }
      CelType overloadType =
          CelTypes.createFunctionType(overload.resultType(), overload.parameterTypes());
      if (!overload.typeParameterNames().isEmpty()) {
        // Instantiate overload's type with fresh type variables.
        overloadType = inferenceContext.newInstance(overload.typeParameterNames(), overloadType);
      }
      ImmutableList<CelType> candArgTypes =
          overloadType.parameters().subList(1, overloadType.parameters().size());
      String candString =
          TypeFormatter.formatFunction(inferenceContext.specialize(candArgTypes), target != null);
      if (inferenceContext.isAssignable(argTypes, candArgTypes)) {
        // Collect overload id.
        refBuilder.addOverloadIds(overload.overloadId());
        if (resultType == null) {
          // First matching overload, determines result type.
          resultType = inferenceContext.specialize(overloadType.parameters().get(0));
          firstCandString = candString;
        } else {
          // More than one matching overload in non-strict mode, narrow result type to DYN unless
          // the overload type matches the previous result type.
          CelType fnResultType = inferenceContext.specialize(overloadType).parameters().get(0);
          if (!Types.isDyn(resultType) && !resultType.equals(fnResultType)) {
            // TODO: Consider joining result types of successful candidates when the
            // types are assignable, but not the same. Note, type assignability checks here seem to
            // mutate the type substitutions list in unexpected ways that result in errant results.
            resultType = SimpleType.DYN;
          }
          if (compileTimeOverloadResolution) {
            // In compile-time overload resolution mode report this situation as an error.
            env.reportError(
                callExprId,
                position,
                "found more than one matching overload for '%s' applied to '%s': %s and also %s",
                function.name(),
                expectedString,
                firstCandString,
                candString);
          }
        }
      } else {
        excludedCands.add(candString);
      }
    }
    if (resultType == null) {
      env.reportError(
          callExprId,
          position,
          "found no matching overload for '%s' applied to '%s'%s",
          function.name(),
          expectedString,
          excludedCands.isEmpty()
              ? ""
              : " (candidates: " + Joiner.on(',').join(excludedCands) + ")");
      resultType = SimpleType.ERROR;
    }
    return OverloadResolution.of(refBuilder.build(), resultType);
  }

  // Return value from visit is not needed as the subtree is not rewritten here.
  @SuppressWarnings("CheckReturnValue")
  private CelType visitSelectField(
      CelMutableExpr expr, CelMutableExpr operand, String field, boolean isOptional) {
    CelType operandType = inferenceContext.specialize(env.getType(operand));
    CelType resultType = SimpleType.ERROR;

    if (operandType instanceof OptionalType) {
      isOptional = true;
      operandType = unwrapOptional(operandType);
    }

    if (!Types.isDynOrError(operandType)) {
      if (operandType.kind().equals(CelKind.STRUCT)) {
        TypeProvider.FieldType fieldType =
            getFieldType(expr.id(), getPosition(expr), operandType, field);
        ProtoMessageType protoMessageType = resolveProtoMessageType(operandType);
        if (protoMessageType != null && protoMessageType.isJsonName(field)) {
          extensions.add(JSON_NAME_EXTENSION);
        }
        // Type of the field
        resultType = fieldType.celType();
      } else if (operandType.kind().equals(CelKind.MAP)) {
        resultType = ((MapType) operandType).valueType();
      } else if (operandType.kind().equals(CelKind.TYPE_PARAM)) {
        // Mark the operand as type DYN to avoid cases where the free type variable might take on
        // an incorrect type if used in multiple locations.
        //
        // The assignability test will update the type substitutions for the type parameter with the
        // type DYN. This ensures that the operand type is appropriately flagged as DYN even though
        // it has already been assigned a TypeParam type from an earlier call flow.
        inferenceContext.isAssignable(SimpleType.DYN, operandType);

        // Mark the result type as DYN since no meaningful type inferences can be made from this
        // field selection.
        resultType = SimpleType.DYN;
      } else {
        env.reportError(
            expr.id(),
            getPosition(expr),
            "type '%s' does not support field selection",
            CelTypes.format(operandType));
      }
    } else {
      resultType = SimpleType.DYN;
    }

    // If the target type was optional coming in, then the result must be optional going out.
    if (isOptional) {
      resultType = OptionalType.create(resultType);
    }
    return resultType;
  }

  private @Nullable ProtoMessageType resolveProtoMessageType(CelType operandType) {
    if (operandType instanceof ProtoMessageType) {
      return (ProtoMessageType) operandType;
    }

    if (operandType.kind().equals(CelKind.STRUCT)) {
      // This is either a StructTypeReference or just a Struct. Attempt to search for
      // ProtoMessageType that may exist in in the type provider.
      TypeType typeDef =
          typeProvider
              .lookupCelType(operandType.name())
              .filter(t -> t instanceof TypeType)
              .map(TypeType.class::cast)
              .orElse(null);
      if (typeDef == null || typeDef.parameters().size() != 1) {
        return null;
      }

      CelType maybeProtoMessageType = typeDef.parameters().get(0);
      if (maybeProtoMessageType instanceof ProtoMessageType) {
        return (ProtoMessageType) maybeProtoMessageType;
      }
    }

    return null;
  }

  private void visitOptionalCall(CelMutableExpr expr, CelMutableCall call) {
    CelMutableExpr operand = call.args().get(0);
    CelMutableExpr field = call.args().get(1);
    if (!field.getKind().equals(CelExpr.ExprKind.Kind.CONSTANT)
        || !field.constant().getKind().equals(CelConstant.Kind.STRING_VALUE)) {

      env.reportError(expr.id(), getPosition(field), "unsupported optional field selection");
      return;
    }

    visit(operand);

    CelType resultType = visitSelectField(expr, operand, field.constant().stringValue(), true);
    env.setType(expr, resultType);
    env.setRef(expr, CelReference.newBuilder().addOverloadIds("select_optional_field").build());
  }

  /**
   * Attempt to interpret an expression as a qualified name. This traverses select and getIdent
   * expression and returns the name they constitute, or null if the expression cannot be
   * interpreted like this.
   */
  private @Nullable String asQualifiedName(CelMutableExpr expr) {
    switch (expr.getKind()) {
      case IDENT:
        return expr.ident().name();
      case SELECT:
        String qname = asQualifiedName(expr.select().operand());
        if (qname != null) {
          return qname + "." + expr.select().field();
        }
        return null;
      default:
        return null;
    }
  }

  /** Returns the field type give a type instance and field name. */
  private TypeProvider.FieldType getFieldType(
      long exprId, int position, CelType type, String fieldName) {
    String typeName = type.name();
    if (typeProvider.lookupCelType(typeName).isPresent()) {
      TypeProvider.FieldType fieldType = typeProvider.lookupFieldType(type, fieldName);
      if (fieldType != null) {
        return fieldType;
      }
      TypeProvider.ExtensionFieldType extensionFieldType =
          typeProvider.lookupExtensionType(fieldName);
      if (extensionFieldType != null) {
        return extensionFieldType.fieldType();
      }
      env.reportError(exprId, position, "undefined field '%s'", fieldName);
    } else {
      // Proto message was added as a variable to the environment but the descriptor was not
      // provided
      String errorMessage =
          String.format("Message type resolution failure while referencing field '%s'.", fieldName);
      if (type.kind().equals(CelKind.STRUCT)) {
        errorMessage +=
            String.format(
                " Ensure that the descriptor for type '%s' was added to the environment", typeName);
      }
      env.reportError(exprId, position, errorMessage, fieldName, typeName);
    }
    return ERROR;
  }

  /** Checks compatibility of joined types, and returns the most general common type. */
  private CelType joinTypes(long exprId, int position, CelType previousType, CelType type) {
    if (previousType == null) {
      return type;
    }
    if (homogeneousLiterals) {
      assertIsAssignable(exprId, position, type, previousType);
    } else if (!inferenceContext.isAssignable(previousType, type)) {
      return SimpleType.DYN;
    }
    return Types.mostGeneral(previousType, type);
  }

  private void assertIsAssignable(long exprId, int position, CelType actual, CelType expected) {
    if (!inferenceContext.isAssignable(expected, actual)) {
      env.reportError(
          exprId,
          position,
          "expected type '%s' but found '%s'",
          CelTypes.format(expected),
          CelTypes.format(actual));
    }
  }

  private CelType unwrapOptional(CelType type) {
    return type.parameters().get(0);
  }

  private void assertType(CelMutableExpr expr, CelType type) {
    assertIsAssignable(expr.id(), getPosition(expr), env.getType(expr), type);
  }

  private int getPosition(CelMutableExpr expr) {
    Integer pos = positionMap.get(expr.id());
    return pos == null ? 0 : pos;
  }

  private int getPosition(CelMutableStruct.Entry entry) {
    Integer pos = positionMap.get(entry.id());
    return pos == null ? 0 : pos;
  }

  /** Helper object for holding an overload resolution result. */
  @AutoValue
  protected abstract static class OverloadResolution {

    /** The {@code Reference} to the declaration name and overload id. */
    public abstract CelReference reference();

    /** The {@code Type} of the result associated with the overload. */
    public abstract CelType type();

    /** Construct a new {@code OverloadResolution} from a {@code reference} and {@code type}. */
    public static OverloadResolution of(CelReference reference, CelType type) {
      return new AutoValue_ExprChecker_OverloadResolution(reference, type);
    }
  }

  /** Helper object to represent a {@link TypeProvider.FieldType} lookup failure. */
  private static final TypeProvider.FieldType ERROR = TypeProvider.FieldType.of(Types.ERROR);
}
