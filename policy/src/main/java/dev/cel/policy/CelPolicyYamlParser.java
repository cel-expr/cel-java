// Copyright 2024 Google LLC
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

package dev.cel.policy;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static dev.cel.common.formats.YamlHelper.ERROR;
import static dev.cel.common.formats.YamlHelper.assertRequiredFields;
import static dev.cel.common.formats.YamlHelper.assertYamlType;

import com.google.common.collect.ImmutableSet;
import dev.cel.common.CelIssue;
import dev.cel.common.formats.ParserContext;
import dev.cel.common.formats.ValueString;
import dev.cel.common.formats.YamlHelper;
import dev.cel.common.formats.YamlHelper.YamlNodeType;
import dev.cel.common.formats.YamlParserContextImpl;
import dev.cel.common.internal.CelCodePointArray;
import dev.cel.policy.CelPolicy.Import;
import dev.cel.policy.CelPolicy.Invariant;
import dev.cel.policy.CelPolicy.Match;
import dev.cel.policy.CelPolicy.Match.Result;
import dev.cel.policy.CelPolicy.Variable;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;

final class CelPolicyYamlParser implements CelPolicyParser {

  // Sentinel values for parsing errors
  private static final ValueString ERROR_VALUE = ValueString.newBuilder().setValue(ERROR).build();
  private static final Match ERROR_MATCH =
      Match.newBuilder(0).setCondition(ERROR_VALUE).setResult(Result.ofOutput(ERROR_VALUE)).build();
  private static final Variable ERROR_VARIABLE =
      Variable.newBuilder().setExpression(ERROR_VALUE).setName(ERROR_VALUE).build();
  private static final Invariant ERROR_INVARIANT =
      Invariant.newBuilder(0).setInvariantId(ERROR_VALUE).setAssertClause(ERROR_VALUE).build();

  private final TagVisitor<Node> tagVisitor;
  private final boolean enableSimpleVariables;

  @Override
  public CelPolicy parse(String policySource) throws CelPolicyValidationException {
    return parse(policySource, "<input>");
  }

  @Override
  public CelPolicy parse(String policySource, String description)
      throws CelPolicyValidationException {
    ParserImpl parser =
        new ParserImpl(tagVisitor, enableSimpleVariables, policySource, description);
    return parser.parseYaml();
  }

  private static class ParserImpl implements PolicyParserContext<Node> {

    private final TagVisitor<Node> tagVisitor;
    private final boolean enableSimpleVariables;
    private final CelPolicySource policySource;
    private final ParserContext<Node> ctx;

    private CelPolicy parseYaml() throws CelPolicyValidationException {
      Node node;
      String policySourceString = policySource.getContent().toString();
      try {
        Node yamlNode =
            YamlHelper.parseYamlSource(policySourceString)
                .orElseThrow(
                    () ->
                        new CelPolicyValidationException(
                            String.format(
                                "YAML document empty or malformed: %s", policySourceString)));
        node = yamlNode;
      } catch (RuntimeException e) {
        throw new CelPolicyValidationException("YAML document is malformed: " + e.getMessage(), e);
      }

      CelPolicy celPolicy = parsePolicy(this, node);

      if (!ctx.getIssues().isEmpty()) {
        throw new CelPolicyValidationException(
            CelIssue.toDisplayString(ctx.getIssues(), celPolicy.policySource()));
      }

      return celPolicy;
    }

    @Override
    public NewPolicyMetadata newPolicy(Node node) {
      long id = ctx.collectMetadata(node);
      return NewPolicyMetadata.create(policySource, id);
    }

    @Override
    public CelPolicy parsePolicy(PolicyParserContext<Node> ctx, Node node) {
      NewPolicyMetadata newPolicyMetadata = newPolicy(node);
      CelPolicy.Builder policyBuilder = newPolicyMetadata.policyBuilder();
      if (!assertYamlType(ctx, newPolicyMetadata.id(), node, YamlNodeType.MAP)) {
        return policyBuilder.build();
      }

      MappingNode rootNode = (MappingNode) node;
      for (NodeTuple nodeTuple : rootNode.getValue()) {
        Node keyNode = nodeTuple.getKeyNode();
        long keyId = ctx.collectMetadata(keyNode);
        if (!assertYamlType(ctx, keyId, keyNode, YamlNodeType.STRING, YamlNodeType.TEXT)) {
          continue;
        }

        Node valueNode = nodeTuple.getValueNode();
        String fieldName = ((ScalarNode) keyNode).getValue();
        switch (fieldName) {
          case "imports":
            parseImports(policyBuilder, ctx, valueNode);
            break;
          case "name":
            policyBuilder.setName(ctx.newYamlString(valueNode));
            break;
          case "description":
            policyBuilder.setDescription(ctx.newYamlString(valueNode));
            break;
          case "display_name":
            policyBuilder.setDisplayName(ctx.newYamlString(valueNode));
            break;
          case "rule":
            policyBuilder.setRule(parseRule(ctx, policyBuilder, valueNode));
            break;
          case "verification":
            parseVerification(policyBuilder, ctx, valueNode);
            break;
          default:
            tagVisitor.visitPolicyTag(ctx, keyId, fieldName, valueNode, policyBuilder);
            break;
        }
      }

      ImmutableSet<String> ruleVarNames =
          policyBuilder.rule().variables().stream()
              .map(CelPolicy.Variable::name)
              .filter(name -> !name.equals(ERROR_VALUE))
              .map(ValueString::value)
              .collect(toImmutableSet());
      for (Variable verVar : policyBuilder.verificationVariables()) {
        if (!verVar.name().equals(ERROR_VALUE)
            && ruleVarNames.contains(verVar.name().value())) {
          ctx.reportError(
              verVar.name().id(),
              "Duplicate variable name '"
                  + verVar.name().value()
                  + "' in verification.variables; already defined in rule.variables");
        }
      }

      return policyBuilder
          .setPolicySource(policySource.toBuilder().setPositionsMap(ctx.getIdToOffsetMap()).build())
          .build();
    }

    private void parseVerification(
        CelPolicy.Builder policyBuilder, PolicyParserContext<Node> ctx, Node node) {
      long id = ctx.collectMetadata(node);
      if (!assertYamlType(ctx, id, node, YamlNodeType.MAP)) {
        return;
      }
      MappingNode mappingNode = (MappingNode) node;
      for (NodeTuple nodeTuple : mappingNode.getValue()) {
        Node key = nodeTuple.getKeyNode();
        long keyId = ctx.collectMetadata(key);
        if (!assertYamlType(ctx, keyId, key, YamlNodeType.STRING, YamlNodeType.TEXT)) {
          continue;
        }
        String fieldName = ((ScalarNode) key).getValue();
        Node valueNode = nodeTuple.getValueNode();
        switch (fieldName) {
          case "invariants": {
            long valueId = ctx.collectMetadata(valueNode);
            if (!assertYamlType(ctx, valueId, valueNode, YamlNodeType.LIST)) {
              continue;
            }
            SequenceNode invariantListNode = (SequenceNode) valueNode;
            for (Node invariantNode : invariantListNode.getValue()) {
              policyBuilder.addInvariant(parseInvariant(ctx, policyBuilder, invariantNode));
            }
            break;
          }
          case "variables": {
            long valueId = ctx.collectMetadata(valueNode);
            if (!assertYamlType(ctx, valueId, valueNode, YamlNodeType.LIST)) {
              continue;
            }
            SequenceNode variableListNode = (SequenceNode) valueNode;
            for (Node varNode : variableListNode.getValue()) {
              policyBuilder.addVerificationVariable(parseVariable(ctx, policyBuilder, varNode));
            }
            break;
          }
          default:
            ctx.reportError(keyId, "Unexpected key in verification block: " + fieldName);
            break;
        }
      }
    }

    private void parseImports(
        CelPolicy.Builder policyBuilder, PolicyParserContext<Node> ctx, Node node) {
      long id = ctx.collectMetadata(node);
      if (!assertYamlType(ctx, id, node, YamlNodeType.LIST)) {
        return;
      }

      SequenceNode importListNode = (SequenceNode) node;
      for (Node importNode : importListNode.getValue()) {
        parseImport(policyBuilder, ctx, importNode);
      }
    }

    private void parseImport(
        CelPolicy.Builder policyBuilder, PolicyParserContext<Node> ctx, Node node) {
      long importId = ctx.collectMetadata(node);
      if (!assertYamlType(ctx, importId, node, YamlNodeType.MAP)) {
        return;
      }

      MappingNode mappingNode = (MappingNode) node;
      for (NodeTuple nodeTuple : mappingNode.getValue()) {
        Node key = nodeTuple.getKeyNode();
        long keyId = ctx.collectMetadata(key);
        if (!assertYamlType(ctx, keyId, key, YamlNodeType.STRING, YamlNodeType.TEXT)) {
          continue;
        }

        String fieldName = ((ScalarNode) key).getValue();
        if (!fieldName.equals("name")) {
          ctx.reportError(
              keyId, String.format("Invalid import key: %s, expected 'name'", fieldName));
          continue;
        }

        Node value = nodeTuple.getValueNode();
        long valueId = ctx.collectMetadata(value);
        if (!assertYamlType(ctx, valueId, value, YamlNodeType.STRING, YamlNodeType.TEXT)) {
          continue;
        }

        policyBuilder.addImport(Import.create(valueId, ctx.newYamlString(value)));
      }
    }

    @Override
    public CelPolicy.Rule parseRule(
        PolicyParserContext<Node> ctx, CelPolicy.Builder policyBuilder, Node node) {
      long valueId = ctx.collectMetadata(node);
      CelPolicy.Rule.Builder ruleBuilder = CelPolicy.Rule.newBuilder(valueId);
      if (!assertYamlType(ctx, valueId, node, YamlNodeType.MAP)) {
        return ruleBuilder.build();
      }

      for (NodeTuple nodeTuple : ((MappingNode) node).getValue()) {
        Node key = nodeTuple.getKeyNode();
        long tagId = ctx.collectMetadata(key);
        if (!assertYamlType(ctx, tagId, key, YamlNodeType.STRING, YamlNodeType.TEXT)) {
          continue;
        }
        String fieldName = ((ScalarNode) key).getValue();
        Node value = nodeTuple.getValueNode();
        switch (fieldName) {
          case "id":
            ruleBuilder.setRuleId(ctx.newYamlString(value));
            break;
          case "description":
            ruleBuilder.setDescription(ctx.newYamlString(value));
            break;
          case "variables":
            ruleBuilder.addVariables(parseVariables(ctx, policyBuilder, value));
            break;
          case "match":
            ruleBuilder.addMatches(parseMatches(ctx, policyBuilder, value));
            break;
          default:
            tagVisitor.visitRuleTag(ctx, tagId, fieldName, value, policyBuilder, ruleBuilder);
            break;
        }
      }
      return ruleBuilder.build();
    }

    private ImmutableSet<CelPolicy.Match> parseMatches(
        PolicyParserContext<Node> ctx, CelPolicy.Builder policyBuilder, Node node) {
      long valueId = ctx.collectMetadata(node);
      ImmutableSet.Builder<CelPolicy.Match> matchesBuilder = ImmutableSet.builder();
      if (!assertYamlType(ctx, valueId, node, YamlNodeType.LIST)) {
        return matchesBuilder.build();
      }

      SequenceNode matchListNode = (SequenceNode) node;
      for (Node elementNode : matchListNode.getValue()) {
        matchesBuilder.add(parseMatch(ctx, policyBuilder, elementNode));
      }

      return matchesBuilder.build();
    }

    @Override
    public CelPolicy.Match parseMatch(
        PolicyParserContext<Node> ctx, CelPolicy.Builder policyBuilder, Node node) {
      long nodeId = ctx.collectMetadata(node);
      if (!assertYamlType(ctx, nodeId, node, YamlNodeType.MAP)) {
        return ERROR_MATCH;
      }
      MappingNode matchNode = (MappingNode) node;
      CelPolicy.Match.Builder matchBuilder =
          CelPolicy.Match.newBuilder(nodeId).setCondition(ValueString.of(ctx.nextId(), "true"));
      for (NodeTuple nodeTuple : matchNode.getValue()) {
        Node key = nodeTuple.getKeyNode();
        long tagId = ctx.collectMetadata(key);
        if (!assertYamlType(ctx, tagId, key, YamlNodeType.STRING, YamlNodeType.TEXT)) {
          continue;
        }
        String fieldName = ((ScalarNode) key).getValue();
        Node value = nodeTuple.getValueNode();
        switch (fieldName) {
          case "condition":
            matchBuilder.setCondition(ctx.newSourceString(value));
            break;
          case "output":
            matchBuilder
                .result()
                .filter(result -> result.kind().equals(Match.Result.Kind.RULE))
                .ifPresent(
                    result -> ctx.reportError(tagId, "Only the rule or the output may be set"));
            matchBuilder.setResult(Match.Result.ofOutput(ctx.newSourceString(value)));
            break;
          case "explanation":
            matchBuilder
                .result()
                .filter(result -> result.kind().equals(Match.Result.Kind.RULE))
                .ifPresent(
                    result ->
                        ctx.reportError(
                            tagId,
                            "Explanation can only be set on output match cases, not nested rules"));
            matchBuilder.setExplanation(ctx.newYamlString(value));
            break;
          case "rule":
            matchBuilder
                .result()
                .filter(result -> result.kind().equals(Match.Result.Kind.OUTPUT))
                .ifPresent(
                    result -> ctx.reportError(tagId, "Only the rule or the output may be set"));
            matchBuilder
                .explanation()
                .ifPresent(
                    result ->
                        ctx.reportError(
                            result.id(),
                            "Explanation can only be set on output match cases, not nested rules"));
            matchBuilder.setResult(Match.Result.ofRule(parseRule(ctx, policyBuilder, value)));
            break;
          default:
            tagVisitor.visitMatchTag(ctx, tagId, fieldName, value, policyBuilder, matchBuilder);
            break;
        }
      }

      if (!assertRequiredFields(ctx, nodeId, matchBuilder.getMissingRequiredFieldNames())) {
        return ERROR_MATCH;
      }

      return matchBuilder.build();
    }

    private ImmutableSet<CelPolicy.Variable> parseVariables(
        PolicyParserContext<Node> ctx, CelPolicy.Builder policyBuilder, Node node) {
      long valueId = ctx.collectMetadata(node);
      ImmutableSet.Builder<CelPolicy.Variable> variableBuilder = ImmutableSet.builder();
      if (!assertYamlType(ctx, valueId, node, YamlNodeType.LIST)) {
        return variableBuilder.build();
      }

      SequenceNode variableListNode = (SequenceNode) node;
      for (Node elementNode : variableListNode.getValue()) {
        variableBuilder.add(parseVariable(ctx, policyBuilder, elementNode));
      }

      return variableBuilder.build();
    }

    @Override
    public CelPolicy.Variable parseVariable(
        PolicyParserContext<Node> ctx, CelPolicy.Builder policyBuilder, Node node) {
      long id = ctx.collectMetadata(node);
      if (!assertYamlType(ctx, id, node, YamlNodeType.MAP)) {
        return ERROR_VARIABLE;
      }

      MappingNode variableMap = (MappingNode) node;
      Variable.Builder builder = Variable.newBuilder();

      if (enableSimpleVariables) {
        return parseVariableInline(ctx, id, variableMap, builder);
      }
      return parseVariableObject(ctx, policyBuilder, id, variableMap, builder);
    }

    private Variable parseVariableInline(
        PolicyParserContext<Node> ctx, long id, MappingNode variableMap, Variable.Builder builder) {
      int iterations = 0;
      for (NodeTuple nodeTuple : variableMap.getValue()) {
        Node keyNode = nodeTuple.getKeyNode();
        long keyId = ctx.collectMetadata(keyNode);
        builder
            .setName(ctx.newYamlString(keyNode))
            .setExpression(ctx.newSourceString(nodeTuple.getValueNode()));
        iterations++;

        if (iterations > 1) {
          ctx.reportError(keyId, "Only one variable may be defined inline");
        }
      }

      if (!assertRequiredFields(ctx, id, builder.getMissingRequiredFieldNames())) {
        return ERROR_VARIABLE;
      }

      return builder.build();
    }

    private Variable parseVariableObject(
        PolicyParserContext<Node> ctx,
        CelPolicy.Builder policyBuilder,
        long id,
        MappingNode variableMap,
        Variable.Builder builder) {
      for (NodeTuple nodeTuple : variableMap.getValue()) {
        Node keyNode = nodeTuple.getKeyNode();
        long keyId = ctx.collectMetadata(keyNode);
        Node valueNode = nodeTuple.getValueNode();
        String keyName = ((ScalarNode) keyNode).getValue();
        switch (keyName) {
          case "name":
            builder.setName(ctx.newYamlString(valueNode));
            break;
          case "expression":
            builder.setExpression(ctx.newSourceString(valueNode));
            break;
          case "description":
            builder.setDescription(ctx.newYamlString(valueNode));
            break;
          case "display_name":
            builder.setDisplayName(ctx.newYamlString(valueNode));
            break;
          default:
            tagVisitor.visitVariableTag(ctx, keyId, keyName, valueNode, policyBuilder, builder);
            break;
        }
      }

      if (!assertRequiredFields(ctx, id, builder.getMissingRequiredFieldNames())) {
        return ERROR_VARIABLE;
      }

      return builder.build();
    }

    @Override
    public CelPolicy.Invariant parseInvariant(
        PolicyParserContext<Node> ctx, CelPolicy.Builder policyBuilder, Node node) {
      long id = ctx.collectMetadata(node);
      Invariant.Builder builder = Invariant.newBuilder(id);
      if (!assertYamlType(ctx, id, node, YamlNodeType.MAP)) {
        return ERROR_INVARIANT;
      }

      MappingNode invariantMap = (MappingNode) node;
      for (NodeTuple nodeTuple : invariantMap.getValue()) {
        Node keyNode = nodeTuple.getKeyNode();
        long keyId = ctx.collectMetadata(keyNode);
        if (!assertYamlType(ctx, keyId, keyNode, YamlNodeType.STRING, YamlNodeType.TEXT)) {
          continue;
        }
        Node valueNode = nodeTuple.getValueNode();
        String keyName = ((ScalarNode) keyNode).getValue();
        switch (keyName) {
          case "id":
            builder.setInvariantId(ctx.newYamlString(valueNode));
            break;
          case "description":
            builder.setDescription(ctx.newYamlString(valueNode));
            break;
          case "assume": {
            if (!assertYamlType(
                ctx,
                ctx.collectMetadata(valueNode),
                valueNode,
                YamlNodeType.STRING,
                YamlNodeType.TEXT,
                YamlNodeType.LIST)) {
              break;
            }
            if (valueNode instanceof SequenceNode) {
              for (Node itemNode : ((SequenceNode) valueNode).getValue()) {
                builder.addAssume(ctx.newSourceString(itemNode));
              }
            } else {
              builder.addAssume(ctx.newSourceString(valueNode));
            }
            break;
          }
          case "assert": {
            if (!assertYamlType(
                ctx,
                ctx.collectMetadata(valueNode),
                valueNode,
                YamlNodeType.STRING,
                YamlNodeType.TEXT,
                YamlNodeType.LIST)) {
              break;
            }
            if (valueNode instanceof SequenceNode) {
              for (Node itemNode : ((SequenceNode) valueNode).getValue()) {
                builder.addAssertClause(ctx.newSourceString(itemNode));
              }
            } else {
              builder.addAssertClause(ctx.newSourceString(valueNode));
            }
            break;
          }
          default:
            ctx.reportError(keyId, "Unexpected key in invariant block: " + keyName);
            break;
        }
      }

      if (!assertRequiredFields(ctx, id, builder.getMissingRequiredFieldNames())) {
        return ERROR_INVARIANT;
      }

      return builder.build();
    }

    private ParserImpl(
        TagVisitor<Node> tagVisitor,
        boolean enableSimpleVariables,
        String source,
        String description) {
      this.tagVisitor = tagVisitor;
      this.enableSimpleVariables = enableSimpleVariables;
      this.policySource =
          CelPolicySource.newBuilder(CelCodePointArray.fromString(source))
              .setDescription(description)
              .build();
      this.ctx = YamlParserContextImpl.newInstance(policySource);
    }

    @Override
    public long nextId() {
      return ctx.nextId();
    }

    @Override
    public long collectMetadata(Node node) {
      return ctx.collectMetadata(node);
    }

    @Override
    public void reportError(long id, String message) {
      ctx.reportError(id, message);
    }

    @Override
    public List<CelIssue> getIssues() {
      return ctx.getIssues();
    }

    @Override
    public Map<Long, Integer> getIdToOffsetMap() {
      return ctx.getIdToOffsetMap();
    }

    @Override
    public ValueString newYamlString(Node node) {
      return ctx.newYamlString(node);
    }

    @Override
    public ValueString newSourceString(Node node) {
      return ctx.newSourceString(node);
    }
  }

  static final class Builder implements CelPolicyParserBuilder<Node> {

    private TagVisitor<Node> tagVisitor;
    private boolean enableSimpleVariables;

    private Builder() {
      this.tagVisitor = new TagVisitor<Node>() {};
      this.enableSimpleVariables = false;
    }

    @Override
    public CelPolicyParserBuilder<Node> addTagVisitor(TagVisitor<Node> tagVisitor) {
      this.tagVisitor = tagVisitor;
      return this;
    }

    @Override
    public CelPolicyParserBuilder<Node> enableSimpleVariables(boolean enable) {
      this.enableSimpleVariables = enable;
      return this;
    }

    @Override
    public CelPolicyParser build() {
      return new CelPolicyYamlParser(tagVisitor, enableSimpleVariables);
    }
  }

  static CelPolicyParserBuilder<Node> newBuilder() {
    return new Builder().enableSimpleVariables(false);
  }

  private CelPolicyYamlParser(TagVisitor<Node> tagVisitor, boolean enableSimpleVariables) {
    this.tagVisitor = checkNotNull(tagVisitor);
    this.enableSimpleVariables = enableSimpleVariables;
  }
}
