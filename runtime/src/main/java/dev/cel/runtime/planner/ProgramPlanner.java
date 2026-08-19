// Copyright 2025 Google LLC
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

package dev.cel.runtime.planner;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.auto.value.AutoValue;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.errorprone.annotations.CheckReturnValue;
import com.google.errorprone.annotations.Immutable;
import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.CelContainer;
import dev.cel.common.CelOptions;
import dev.cel.common.Operator;
import dev.cel.common.annotations.Internal;
import dev.cel.common.ast.CelBlock;
import dev.cel.common.ast.CelConstant;
import dev.cel.common.ast.CelExpr;
import dev.cel.common.ast.CelExpr.CelCall;
import dev.cel.common.ast.CelExpr.CelComprehension;
import dev.cel.common.ast.CelExpr.CelList;
import dev.cel.common.ast.CelExpr.CelMap;
import dev.cel.common.ast.CelExpr.CelSelect;
import dev.cel.common.ast.CelExpr.CelStruct;
import dev.cel.common.ast.CelExpr.CelStruct.Entry;
import dev.cel.common.ast.CelReference;
import dev.cel.common.exceptions.CelOverloadNotFoundException;
import dev.cel.common.types.CelKind;
import dev.cel.common.types.CelType;
import dev.cel.common.types.CelTypeProvider;
import dev.cel.common.types.SimpleType;
import dev.cel.common.types.TypeType;
import dev.cel.common.values.CelValueConverter;
import dev.cel.common.values.CelValueProvider;
import dev.cel.runtime.CelEvaluationException;
import dev.cel.runtime.CelEvaluationExceptionBuilder;
import dev.cel.runtime.CelResolvedOverload;
import dev.cel.runtime.DefaultDispatcher;
import dev.cel.runtime.Program;
import java.util.HashMap;
import java.util.NoSuchElementException;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * {@code ProgramPlanner} resolves functions, types, and identifiers at plan time given a
 * parsed-only or a type-checked expression.
 */
@Immutable
@Internal
public final class ProgramPlanner {
  private final CelTypeProvider typeProvider;
  private final CelValueProvider valueProvider;
  private final DefaultDispatcher dispatcher;
  private final AttributeFactory attributeFactory;
  private final CelContainer container;
  private final CelOptions options;
  private final CelValueConverter celValueConverter;
  private final ImmutableSet<String> lateBoundFunctionNames;

  /**
   * Plans a {@link Program} from the provided parsed-only or type-checked {@link
   * CelAbstractSyntaxTree}.
   */
  public Program plan(CelAbstractSyntaxTree ast) throws CelEvaluationException {
    PlannedInterpretable plannedInterpretable;
    ErrorMetadata errorMetadata =
        ErrorMetadata.create(ast.getSource().getPositionsMap(), ast.getSource().getDescription());
    try {
      PlannerContext ctx = PlannerContext.create(ast);
      plannedInterpretable =
          CelBlock.extract(ast)
              .map(celBlock -> planBlock(celBlock, ctx))
              .orElseGet(() -> plan(ast.getExpr(), ctx));
    } catch (RuntimeException e) {
      throw CelEvaluationExceptionBuilder.newBuilder(e.getMessage())
          .setMetadata(errorMetadata, ast.getExpr().id())
          .setCause(e)
          .build();
    }

    return PlannedProgram.create(plannedInterpretable, errorMetadata, options);
  }

  private PlannedInterpretable plan(CelExpr celExpr, PlannerContext ctx) {
    switch (celExpr.getKind()) {
      case CONSTANT:
        return planConstant(celExpr, celExpr.constant());
      case IDENT:
        return planIdent(celExpr, ctx);
      case SELECT:
        return planSelect(celExpr, ctx);
      case CALL:
        return planCall(celExpr, ctx);
      case LIST:
        return planCreateList(celExpr, ctx);
      case STRUCT:
        return planCreateStruct(celExpr, ctx);
      case MAP:
        return planCreateMap(celExpr, ctx);
      case COMPREHENSION:
        return planComprehension(celExpr, ctx);
      case NOT_SET:
        throw new UnsupportedOperationException("Unsupported kind: " + celExpr.getKind());
      default:
        throw new UnsupportedOperationException("Unexpected kind: " + celExpr.getKind());
    }
  }

  private PlannedInterpretable planSelect(CelExpr celExpr, PlannerContext ctx) {
    CelSelect select = celExpr.select();
    PlannedInterpretable operand = plan(select.operand(), ctx);

    InterpretableAttribute attribute;
    if (operand instanceof EvalAttribute) {
      attribute = (EvalAttribute) operand;
    } else {
      attribute = EvalAttribute.create(celExpr, attributeFactory.newRelativeAttribute(operand));
    }

    if (select.testOnly()) {
      attribute = EvalTestOnly.create(celExpr, attribute);
    }

    Qualifier qualifier = StringQualifier.create(select.field());

    return attribute.addQualifier(celExpr, qualifier);
  }

  private PlannedInterpretable planConstant(CelExpr expr, CelConstant celConstant) {
    switch (celConstant.getKind()) {
      case NULL_VALUE:
        return EvalConstant.create(expr, celConstant.nullValue());
      case BOOLEAN_VALUE:
        return EvalConstant.create(expr, celConstant.booleanValue());
      case INT64_VALUE:
        return EvalConstant.create(expr, celConstant.int64Value());
      case UINT64_VALUE:
        return EvalConstant.create(expr, celConstant.uint64Value());
      case DOUBLE_VALUE:
        return EvalConstant.create(expr, celConstant.doubleValue());
      case STRING_VALUE:
        return EvalConstant.create(expr, celConstant.stringValue());
      case BYTES_VALUE:
        return EvalConstant.create(expr, celConstant.bytesValue());
      default:
        throw new IllegalStateException("Unsupported kind: " + celConstant.getKind());
    }
  }

  private PlannedInterpretable planIdent(CelExpr celExpr, PlannerContext ctx) {
    CelReference ref = ctx.referenceMap().get(celExpr.id());
    if (ref != null) {
      return planCheckedIdent(celExpr, ref, ctx.typeMap());
    }

    String identName = celExpr.ident().name();
    PlannedInterpretable blockSlot = maybeInterceptBlockSlot(celExpr, identName).orElse(null);
    if (blockSlot != null) {
      return blockSlot;
    }

    if (ctx.isLocalVar(identName)) {
      return EvalAttribute.create(celExpr, attributeFactory.newAbsoluteAttribute(identName));
    }

    return EvalAttribute.create(celExpr, attributeFactory.newMaybeAttribute(identName));
  }

  private PlannedInterpretable planCheckedIdent(
      CelExpr expr, CelReference identRef, ImmutableMap<Long, CelType> typeMap) {
    if (identRef.value().isPresent()) {
      return planConstant(expr, identRef.value().get());
    }

    CelType type = typeMap.get(expr.id());
    if (type.kind().equals(CelKind.TYPE)) {
      TypeType identType =
          typeProvider
              .findType(identRef.name())
              .map(
                  t ->
                      (t instanceof TypeType)
                          // Coalesce all type(foo) "type" into a sentinel runtime type to allow for
                          // erasure based type comparisons
                          ? TypeType.create(SimpleType.DYN)
                          : TypeType.create(t))
              .orElseThrow(
                  () ->
                      new NoSuchElementException(
                          "Reference to an undefined type: " + identRef.name()));
      return EvalConstant.create(expr, identType);
    }

    String identName = identRef.name();
    PlannedInterpretable blockSlot = maybeInterceptBlockSlot(expr, identName).orElse(null);
    if (blockSlot != null) {
      return blockSlot;
    }

    return EvalAttribute.create(expr, attributeFactory.newAbsoluteAttribute(identRef.name()));
  }

  private Optional<PlannedInterpretable> maybeInterceptBlockSlot(CelExpr expr, String identName) {
    if (!identName.startsWith("@index")) {
      return Optional.empty();
    }
    if (identName.length() <= 6) {
      throw new IllegalArgumentException("Malformed block slot identifier: " + identName);
    }
    try {
      int slotIndex = Integer.parseInt(identName.substring(6));
      if (slotIndex < 0) {
        throw new IllegalArgumentException("Negative block slot index: " + identName);
      }
      return Optional.of(EvalBlock.EvalBlockSlot.create(expr, slotIndex));
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("Invalid block slot index: " + identName, e);
    }
  }

  private PlannedInterpretable planCall(CelExpr expr, PlannerContext ctx) {
    ResolvedFunction resolvedFunction = resolveFunction(expr, ctx.referenceMap());
    String functionName = resolvedFunction.functionName();

    CelExpr target = resolvedFunction.target().orElse(null);
    int argCount = expr.call().args().size();
    if (target != null) {
      argCount++;
    }

    PlannedInterpretable[] evaluatedArgs = new PlannedInterpretable[argCount];

    int offset = 0;
    if (target != null) {
      evaluatedArgs[0] = plan(target, ctx);
      offset++;
    }

    ImmutableList<CelExpr> args = expr.call().args();
    for (int argIndex = 0; argIndex < args.size(); argIndex++) {
      evaluatedArgs[argIndex + offset] = plan(args.get(argIndex), ctx);
    }

    Operator operator = Operator.findReverse(functionName).orElse(null);
    if (operator != null) {
      switch (operator) {
        case LOGICAL_OR:
          return options.enableShortCircuiting()
              ? EvalOr.create(expr, evaluatedArgs)
              : EvalExhaustiveOr.create(expr, evaluatedArgs);
        case LOGICAL_AND:
          return options.enableShortCircuiting()
              ? EvalAnd.create(expr, evaluatedArgs)
              : EvalExhaustiveAnd.create(expr, evaluatedArgs);
        case CONDITIONAL:
          return options.enableShortCircuiting()
              ? EvalConditional.create(expr, evaluatedArgs)
              : EvalExhaustiveConditional.create(expr, evaluatedArgs);
        default:
          // fall-through
      }
    }

    CelResolvedOverload resolvedOverload = null;
    if (resolvedFunction.overloadId().isPresent()) {
      resolvedOverload = dispatcher.findOverload(resolvedFunction.overloadId().get()).orElse(null);
    }

    if (resolvedOverload == null) {
      // Parsed-only function dispatch
      resolvedOverload = dispatcher.findOverload(functionName).orElse(null);
    }

    PlannedInterpretable optionalCall =
        maybeInterceptOptionalCalls(resolvedOverload, functionName, evaluatedArgs, expr)
            .orElse(null);
    if (optionalCall != null) {
      return optionalCall;
    }

    if (resolvedOverload == null) {
      boolean isLateBound = lateBoundFunctionNames.contains(functionName);
      // For type-checked ASTs, functions that are not explicitly registered as late-bound
      // must be resolved at plan time.
      // For parsed-only ASTs or late-bound functions, defer overload resolution to runtime.
      if (ctx.isChecked() && !isLateBound) {
        CelReference reference = ctx.referenceMap().get(expr.id());
        if (reference != null && !reference.overloadIds().isEmpty()) {
          throw new CelOverloadNotFoundException(functionName, reference.overloadIds());
        } else {
          throw new CelOverloadNotFoundException(functionName);
        }
      }

      ImmutableList<String> overloadIds = ImmutableList.of();
      CelReference reference = ctx.referenceMap().get(expr.id());
      if (reference != null && !reference.overloadIds().isEmpty()) {
        overloadIds = reference.overloadIds();
      } else if (resolvedFunction.overloadId().isPresent()) {
        overloadIds = ImmutableList.of(resolvedFunction.overloadId().get());
      }

      return EvalLateBoundCall.create(
          expr, functionName, overloadIds, evaluatedArgs, celValueConverter);
    }

    switch (argCount) {
      case 0:
        return EvalZeroArity.create(expr, functionName, resolvedOverload, celValueConverter);
      case 1:
        return EvalUnary.create(
            expr, functionName, resolvedOverload, evaluatedArgs[0], celValueConverter);
      case 2:
        if (functionName.equals(Operator.INDEX.getFunction())
            || functionName.equals(Operator.OPTIONAL_INDEX.getFunction())) {
          return EvalIndex.create(
              expr,
              functionName,
              resolvedOverload,
              evaluatedArgs[0],
              evaluatedArgs[1],
              celValueConverter);
        }
        return EvalBinary.create(
            expr,
            functionName,
            resolvedOverload,
            evaluatedArgs[0],
            evaluatedArgs[1],
            celValueConverter);
      default:
        return EvalVarArgsCall.create(
            expr, functionName, resolvedOverload, evaluatedArgs, celValueConverter);
    }
  }

  private PlannedInterpretable planBlock(CelBlock celBlock, PlannerContext ctx) {
    ImmutableList<CelExpr> indices = celBlock.indices();

    PlannedInterpretable[] slotExprs = new PlannedInterpretable[indices.size()];
    for (int i = 0; i < slotExprs.length; i++) {
      slotExprs[i] = plan(indices.get(i), ctx);
    }
    PlannedInterpretable resultExpr = plan(celBlock.result(), ctx);
    return EvalBlock.create(celBlock.expr(), slotExprs, resultExpr);
  }

  /**
   * Intercepts a potential optional function call.
   *
   * <p>This is analogous to cel-go's decorator. This could be moved to {@code CelOptionalLibrary}
   * once we add the support for it.
   */
  private Optional<PlannedInterpretable> maybeInterceptOptionalCalls(
      @Nullable CelResolvedOverload resolvedOverload,
      String functionName,
      PlannedInterpretable[] evaluatedArgs,
      CelExpr expr) {
    if (evaluatedArgs.length != 2) {
      return Optional.empty();
    }

    String overloadId = resolvedOverload == null ? "" : resolvedOverload.getOverloadId();

    switch (functionName) {
      case "or":
        if (overloadId.isEmpty() || overloadId.equals("optional_or_optional")) {
          return Optional.of(EvalOptionalOr.create(expr, evaluatedArgs[0], evaluatedArgs[1]));
        }

        return Optional.empty();
      case "orValue":
        if (overloadId.isEmpty() || overloadId.equals("optional_orValue_value")) {
          return Optional.of(EvalOptionalOrValue.create(expr, evaluatedArgs[0], evaluatedArgs[1]));
        }

        return Optional.empty();
      default:
        break;
    }

    if (functionName.equals(Operator.OPTIONAL_SELECT.getFunction())) {
      String field = expr.call().args().get(1).constant().stringValue();
      InterpretableAttribute attribute;
      if (evaluatedArgs[0] instanceof EvalAttribute) {
        attribute = (EvalAttribute) evaluatedArgs[0];
      } else {
        attribute =
            EvalAttribute.create(expr, attributeFactory.newRelativeAttribute(evaluatedArgs[0]));
      }
      Qualifier qualifier = StringQualifier.create(field);
      PlannedInterpretable selectAttribute = attribute.addQualifier(expr, qualifier);

      return Optional.of(
          EvalOptionalSelectField.create(
              expr, evaluatedArgs[0], field, selectAttribute, celValueConverter));
    }

    return Optional.empty();
  }

  private PlannedInterpretable planCreateStruct(CelExpr celExpr, PlannerContext ctx) {
    CelStruct struct = celExpr.struct();
    CelType structType = resolveStructType(celExpr, ctx);

    ImmutableList<Entry> entries = struct.entries();
    String[] keys = new String[entries.size()];
    PlannedInterpretable[] values = new PlannedInterpretable[entries.size()];
    boolean[] isOptional = new boolean[entries.size()];

    for (int i = 0; i < entries.size(); i++) {
      Entry entry = entries.get(i);
      keys[i] = entry.fieldKey();
      values[i] = plan(entry.value(), ctx);
      isOptional[i] = entry.optionalEntry();
    }

    return EvalCreateStruct.create(celExpr, valueProvider, structType, keys, values, isOptional);
  }

  private PlannedInterpretable planCreateList(CelExpr celExpr, PlannerContext ctx) {
    CelList list = celExpr.list();
    ImmutableList<CelExpr> elements = list.elements();
    PlannedInterpretable[] values = new PlannedInterpretable[elements.size()];

    for (int i = 0; i < elements.size(); i++) {
      values[i] = plan(elements.get(i), ctx);
    }

    boolean[] isOptional = new boolean[elements.size()];
    for (int optionalIndex : list.optionalIndices()) {
      isOptional[optionalIndex] = true;
    }

    return EvalCreateList.create(celExpr, values, isOptional);
  }

  private PlannedInterpretable planCreateMap(CelExpr celExpr, PlannerContext ctx) {
    CelMap map = celExpr.map();

    ImmutableList<CelMap.Entry> entries = map.entries();
    PlannedInterpretable[] keys = new PlannedInterpretable[entries.size()];
    PlannedInterpretable[] values = new PlannedInterpretable[entries.size()];
    boolean[] isOptional = new boolean[entries.size()];

    for (int i = 0; i < entries.size(); i++) {
      CelMap.Entry entry = entries.get(i);
      keys[i] = plan(entry.key(), ctx);
      values[i] = plan(entry.value(), ctx);
      isOptional[i] = entry.optionalEntry();
    }

    return EvalCreateMap.create(celExpr, keys, values, isOptional);
  }

  private PlannedInterpretable planComprehension(CelExpr expr, PlannerContext ctx) {
    CelComprehension comprehension = expr.comprehension();

    PlannedInterpretable accuInit = plan(comprehension.accuInit(), ctx);
    PlannedInterpretable iterRange = plan(comprehension.iterRange(), ctx);

    ctx.pushLocalVars(comprehension.accuVar(), comprehension.iterVar(), comprehension.iterVar2());

    PlannedInterpretable loopCondition = plan(comprehension.loopCondition(), ctx);
    PlannedInterpretable loopStep = plan(comprehension.loopStep(), ctx);

    ctx.popLocalVars(comprehension.iterVar(), comprehension.iterVar2());

    PlannedInterpretable result = plan(comprehension.result(), ctx);

    ctx.popLocalVars(comprehension.accuVar());

    return EvalFold.create(
        expr,
        comprehension.accuVar(),
        accuInit,
        comprehension.iterVar(),
        comprehension.iterVar2(),
        iterRange,
        loopCondition,
        loopStep,
        result);
  }

  /**
   * resolveFunction determines the call target, function name, and overload name (when unambiguous)
   * from the given call expr.
   */
  private ResolvedFunction resolveFunction(
      CelExpr expr, ImmutableMap<Long, CelReference> referenceMap) {
    CelCall call = expr.call();
    Optional<CelExpr> maybeTarget = call.target();
    String functionName = call.function();

    CelReference reference = referenceMap.get(expr.id());
    if (reference != null) {
      // Checked expression
      if (reference.overloadIds().size() == 1) {
        ResolvedFunction.Builder builder =
            ResolvedFunction.newBuilder()
                .setFunctionName(functionName)
                .setOverloadId(reference.overloadIds().get(0));

        maybeTarget.ifPresent(builder::setTarget);

        return builder.build();
      }
    }

    // Parsed-only function resolution.
    //
    // There are two distinct cases we must handle:
    //
    // 1. Non-qualified function calls. This will resolve into either:
    //    - A simple global call foo()
    //    - A fully qualified global call through normal container resolution foo.bar.qux()
    // 2. Qualified function calls:
    //    - A member call on an identifier foo.bar()
    //    - A fully qualified global call, through normal container resolution or abbreviations
    //      foo.bar.qux()
    if (!maybeTarget.isPresent()) {
      for (String cand : container.resolveCandidateNames(functionName)) {
        CelResolvedOverload overload = dispatcher.findOverload(cand).orElse(null);
        if (overload != null) {
          return ResolvedFunction.newBuilder().setFunctionName(cand).build();
        }
      }

      // Normal global call
      return ResolvedFunction.newBuilder().setFunctionName(functionName).build();
    }

    CelExpr target = maybeTarget.get();
    String qualifiedPrefix = toQualifiedName(target).orElse(null);
    if (qualifiedPrefix != null) {
      String qualifiedName = qualifiedPrefix + "." + functionName;
      for (String cand : container.resolveCandidateNames(qualifiedName)) {
        CelResolvedOverload overload = dispatcher.findOverload(cand).orElse(null);
        if (overload != null) {
          return ResolvedFunction.newBuilder().setFunctionName(cand).build();
        }
      }
    }

    // Normal member call
    return ResolvedFunction.newBuilder().setFunctionName(functionName).setTarget(target).build();
  }

  private CelType resolveStructType(CelExpr expr, PlannerContext ctx) {
    CelType checkedType = ctx.typeMap().get(expr.id());
    if (checkedType != null) {
      CelKind kind = checkedType.kind();
      // Type-checked ASTs do not need a type-provider lookup as long as it's of expected kind.
      if (isValidStructKind(kind)) {
        return checkedType;
      }
    }

    CelStruct struct = expr.struct();
    String messageName = struct.messageName();
    for (String typeName : container.resolveCandidateNames(messageName)) {
      CelType structType = typeProvider.findType(typeName).orElse(null);
      if (structType == null) {
        continue;
      }

      CelKind kind = structType.kind();

      if (!isValidStructKind(kind)) {
        throw new IllegalArgumentException(
            String.format(
                "Expected struct type for %s, got %s", structType.name(), structType.kind()));
      }

      return structType;
    }

    throw new IllegalArgumentException("Undefined type name: " + messageName);
  }

  private static boolean isValidStructKind(CelKind kind) {
    return kind.equals(CelKind.STRUCT)
        || kind.equals(CelKind.TIMESTAMP)
        || kind.equals(CelKind.DURATION);
  }

  /** Converts a given expression into a qualified name, if possible. */
  private Optional<String> toQualifiedName(CelExpr operand) {
    switch (operand.getKind()) {
      case IDENT:
        return Optional.of(operand.ident().name());
      case SELECT:
        CelSelect select = operand.select();
        String maybeQualified = toQualifiedName(select.operand()).orElse(null);
        if (maybeQualified != null) {
          return Optional.of(maybeQualified + "." + select.field());
        }

        break;
      default:
        // fall-through
    }

    return Optional.empty();
  }

  @AutoValue
  abstract static class ResolvedFunction {

    abstract String functionName();

    abstract Optional<CelExpr> target();

    abstract Optional<String> overloadId();

    @AutoValue.Builder
    abstract static class Builder {
      abstract Builder setFunctionName(String functionName);

      abstract Builder setTarget(CelExpr target);

      abstract Builder setOverloadId(String overloadId);

      @CheckReturnValue
      abstract ResolvedFunction build();
    }

    private static Builder newBuilder() {
      return new AutoValue_ProgramPlanner_ResolvedFunction.Builder();
    }
  }

  static final class PlannerContext {
    private final CelAbstractSyntaxTree ast;
    private final HashMap<String, Integer> localVars = new HashMap<>();

    CelAbstractSyntaxTree ast() {
      return ast;
    }

    ImmutableMap<Long, CelReference> referenceMap() {
      return ast.getReferenceMap();
    }

    ImmutableMap<Long, CelType> typeMap() {
      return ast.getTypeMap();
    }

    boolean isChecked() {
      return ast.isChecked();
    }

    private void pushLocalVars(String... names) {
      for (String name : names) {
        if (Strings.isNullOrEmpty(name)) {
          continue;
        }
        localVars.merge(name, 1, Integer::sum);
      }
    }

    private void popLocalVars(String... names) {
      for (String name : names) {
        if (Strings.isNullOrEmpty(name)) {
          continue;
        }
        Integer count = localVars.get(name);
        if (count != null) {
          if (count == 1) {
            localVars.remove(name);
          } else {
            localVars.put(name, count - 1);
          }
        }
      }
    }

    /** Checks if the given name is a local variable in the current scope. */
    private boolean isLocalVar(String name) {
      return localVars.containsKey(name);
    }

    private PlannerContext(CelAbstractSyntaxTree ast) {
      this.ast = checkNotNull(ast);
    }

    static PlannerContext create(CelAbstractSyntaxTree ast) {
      return new PlannerContext(ast);
    }
  }

  public static ProgramPlanner newPlanner(
      CelTypeProvider typeProvider,
      CelValueProvider valueProvider,
      DefaultDispatcher dispatcher,
      CelValueConverter celValueConverter,
      CelContainer container,
      CelOptions options,
      ImmutableSet<String> lateBoundFunctionNames) {
    return new ProgramPlanner(
        typeProvider,
        valueProvider,
        dispatcher,
        celValueConverter,
        container,
        options,
        lateBoundFunctionNames);
  }

  private ProgramPlanner(
      CelTypeProvider typeProvider,
      CelValueProvider valueProvider,
      DefaultDispatcher dispatcher,
      CelValueConverter celValueConverter,
      CelContainer container,
      CelOptions options,
      ImmutableSet<String> lateBoundFunctionNames) {
    this.typeProvider = typeProvider;
    this.valueProvider = valueProvider;
    this.dispatcher = dispatcher;
    this.celValueConverter = celValueConverter;
    this.container = container;
    this.options = options;
    this.lateBoundFunctionNames = lateBoundFunctionNames;
    this.attributeFactory =
        AttributeFactory.newAttributeFactory(container, typeProvider, celValueConverter);
  }
}
