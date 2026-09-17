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

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableSet;
import com.google.protobuf.FieldMask;
import com.google.rpc.context.AttributeContext;
import dev.cel.common.CelVarDecl;
import dev.cel.common.types.CelType;
import dev.cel.common.types.CelTypeProvider;
import dev.cel.common.types.MapType;
import dev.cel.common.types.ProtoMessageType;
import dev.cel.common.types.ProtoMessageTypeProvider;
import dev.cel.common.types.SimpleType;
import dev.cel.common.types.StructType.Field;
import java.util.Arrays;
import java.util.Optional;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class ProtoTypeMaskTypeProviderTest {

  private static final String ATTRIBUTE_CONTEXT_TYPE = "google.rpc.context.AttributeContext";
  private static final String RESOURCE_TYPE = "google.rpc.context.AttributeContext.Resource";
  private static final String REQUEST_TYPE = "google.rpc.context.AttributeContext.Request";
  private static final String AUTH_TYPE = "google.rpc.context.AttributeContext.Auth";

  @Test
  public void protoTypeMaskProvider_badFieldMask() {
    CelTypeProvider celTypeProvider =
        new ProtoMessageTypeProvider(Arrays.asList(AttributeContext.getDescriptor()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ProtoTypeMaskTypeProvider(
                celTypeProvider,
                ImmutableSet.of(
                    ProtoTypeMask.of(
                        "google.rpc.context.AttributeContext",
                        FieldMask.newBuilder().addPaths("missing").build()))));
  }

  @Test
  public void lookupFieldNames_undeclaredMessageType() {
    CelTypeProvider celTypeProvider = new ProtoMessageTypeProvider();
    ProtoTypeMaskTypeProvider protoTypeMaskProvider =
        new ProtoTypeMaskTypeProvider(celTypeProvider, ImmutableSet.of());
    assertThat(protoTypeMaskProvider.findType(ATTRIBUTE_CONTEXT_TYPE)).isEmpty();
  }

  @Test
  public void lookupFieldNames_noProtoDecls() {
    CelTypeProvider celTypeProvider =
        new ProtoMessageTypeProvider(ImmutableSet.of(AttributeContext.getDescriptor()));
    ProtoTypeMaskTypeProvider protoTypeMaskProvider =
        new ProtoTypeMaskTypeProvider(celTypeProvider, ImmutableSet.of());
    ProtoMessageType protoType = assertTypeFound(protoTypeMaskProvider, ATTRIBUTE_CONTEXT_TYPE);
    assertThat(protoType.fields().stream().map(Field::name).collect(toImmutableList()))
        .containsExactly(
            "resource",
            "request",
            "response",
            "origin",
            "source",
            "destination",
            "extensions",
            "api");

    ProtoMessageType origProtoType = assertTypeFound(celTypeProvider, ATTRIBUTE_CONTEXT_TYPE);
    assertThat(protoType).isSameInstanceAs(origProtoType);
  }

  @Test
  public void lookupFieldNames_allFieldsHidden() {
    CelTypeProvider celTypeProvider =
        new ProtoMessageTypeProvider(ImmutableSet.of(AttributeContext.getDescriptor()));
    ProtoTypeMaskTypeProvider protoTypeMaskProvider =
        new ProtoTypeMaskTypeProvider(
            celTypeProvider,
            ImmutableSet.of(ProtoTypeMask.ofAllFieldsHidden(ATTRIBUTE_CONTEXT_TYPE)));

    ProtoMessageType protoType = assertTypeFound(protoTypeMaskProvider, ATTRIBUTE_CONTEXT_TYPE);
    assertThat(protoType.fieldNames()).isEmpty();
    ProtoMessageType origProtoType = assertTypeFound(celTypeProvider, ATTRIBUTE_CONTEXT_TYPE);
    assertThat(protoType).isNotSameInstanceAs(origProtoType);
  }

  @Test
  public void protoTypeMaskProvider_hiddenFieldSentinelCharOnSubPath_throws() {
    CelTypeProvider celTypeProvider =
        new ProtoMessageTypeProvider(ImmutableSet.of(AttributeContext.getDescriptor()));

    IllegalArgumentException e =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new ProtoTypeMaskTypeProvider(
                    celTypeProvider,
                    ImmutableSet.of(
                        ProtoTypeMask.of(
                            "google.rpc.context.AttributeContext",
                            FieldMask.newBuilder().addPaths("resource.!").build()))));
    assertThat(e)
        .hasMessageThat()
        .contains("message google.rpc.context.AttributeContext.Resource does not declare field: !");
  }

  @Test
  public void lookupFieldNames_fullProtoDecl() {
    CelTypeProvider celTypeProvider =
        new ProtoMessageTypeProvider(ImmutableSet.of(AttributeContext.getDescriptor()));
    ProtoTypeMaskTypeProvider protoTypeMaskProvider =
        new ProtoTypeMaskTypeProvider(
            celTypeProvider, ImmutableSet.of(ProtoTypeMask.ofAllFields(ATTRIBUTE_CONTEXT_TYPE)));
    ProtoMessageType protoType = assertTypeFound(protoTypeMaskProvider, ATTRIBUTE_CONTEXT_TYPE);
    assertTypeHasFields(
        protoType,
        ImmutableSet.of(
            "resource",
            "request",
            "response",
            "origin",
            "source",
            "destination",
            "extensions",
            "api"));
    ProtoMessageType origProtoType = assertTypeFound(celTypeProvider, ATTRIBUTE_CONTEXT_TYPE);
    assertThat(protoType).isSameInstanceAs(origProtoType);
  }

  @Test
  public void lookupFieldNames_partialProtoDecl() {
    CelTypeProvider celTypeProvider =
        new ProtoMessageTypeProvider(ImmutableSet.of(AttributeContext.getDescriptor()));
    ProtoTypeMaskTypeProvider protoTypeMaskProvider =
        new ProtoTypeMaskTypeProvider(
            celTypeProvider,
            ImmutableSet.of(
                ProtoTypeMask.of(
                    "google.rpc.context.AttributeContext",
                    FieldMask.newBuilder()
                        .addPaths("resource.name")
                        .addPaths("resource.type")
                        .build())));
    ProtoMessageType maskedType = assertTypeFound(protoTypeMaskProvider, ATTRIBUTE_CONTEXT_TYPE);
    assertTypeHasFields(maskedType, ImmutableSet.of("resource"));

    ProtoMessageType fullType = assertTypeFound(celTypeProvider, ATTRIBUTE_CONTEXT_TYPE);
    assertTypeHasFields(
        fullType,
        ImmutableSet.of(
            "resource",
            "request",
            "response",
            "origin",
            "source",
            "destination",
            "extensions",
            "api"));

    maskedType = assertTypeFound(protoTypeMaskProvider, RESOURCE_TYPE);
    assertTypeHasFields(maskedType, ImmutableSet.of("name", "type"));

    fullType = assertTypeFound(celTypeProvider, RESOURCE_TYPE);
    assertTypeHasFields(
        fullType,
        ImmutableSet.of(
            "type",
            "display_name",
            "labels",
            "name",
            "annotations",
            "etag",
            "location",
            "create_time",
            "service",
            "uid",
            "update_time",
            "delete_time"));
  }

  @Test
  public void computeDecls() {
    CelTypeProvider celTypeProvider =
        new ProtoMessageTypeProvider(Arrays.asList(AttributeContext.getDescriptor()));
    ProtoTypeMaskTypeProvider protoTypeMaskProvider =
        new ProtoTypeMaskTypeProvider(
            celTypeProvider,
            ImmutableSet.of(
                ProtoTypeMask.of(
                        "google.rpc.context.AttributeContext",
                        FieldMask.newBuilder()
                            .addPaths("resource.name")
                            .addPaths("resource.type")
                            .addPaths("request.path")
                            .build())
                    .withFieldsAsVariableDeclarations()));

    ProtoMessageType resourceType =
        (ProtoMessageType) celTypeProvider.findType(RESOURCE_TYPE).get();
    ProtoMessageType requestType = (ProtoMessageType) celTypeProvider.findType(REQUEST_TYPE).get();
    assertThat(protoTypeMaskProvider.computeDeclsFromProtoTypeMasks())
        .containsExactly(
            CelVarDecl.newBuilder().setName("resource").setType(resourceType).build(),
            CelVarDecl.newBuilder().setName("request").setType(requestType).build());
  }

  @Test
  public void lookupFieldType() {
    CelTypeProvider celTypeProvider =
        new ProtoMessageTypeProvider(ImmutableSet.of(AttributeContext.getDescriptor()));
    ProtoTypeMaskTypeProvider protoTypeMaskProvider =
        new ProtoTypeMaskTypeProvider(
            celTypeProvider,
            ImmutableSet.of(
                ProtoTypeMask.of(
                    "google.rpc.context.AttributeContext",
                    FieldMask.newBuilder()
                        .addPaths("resource.name")
                        .addPaths("resource.type")
                        .addPaths("request.path")
                        .addPaths("request.auth.*")
                        .build())));
    ProtoMessageType ctxType = assertTypeFound(protoTypeMaskProvider, ATTRIBUTE_CONTEXT_TYPE);
    assertThat(ctxType.findField("resource")).isPresent();
    assertTypeHasFieldWithType(ctxType, "resource", RESOURCE_TYPE);
    assertTypeHasFieldWithType(ctxType, "request", REQUEST_TYPE);

    ProtoMessageType resourceType = (ProtoMessageType) ctxType.findField("resource").get().type();
    assertTypeHasFieldWithType(resourceType, "name", "string");
    assertTypeHasFieldWithType(resourceType, "type", "string");

    ProtoMessageType requestType = (ProtoMessageType) ctxType.findField("request").get().type();
    assertTypeHasFieldWithType(requestType, "path", "string");
    assertTypeHasFieldWithType(requestType, "auth", AUTH_TYPE);

    ProtoMessageType authType = (ProtoMessageType) requestType.findField("auth").get().type();
    assertTypeHasFieldWithType(authType, "principal", "string");
    assertTypeHasFieldWithType(authType, "claims", "map");

    assertThat(authType.findField("claims").get().type())
        .isEqualTo(MapType.create(SimpleType.STRING, SimpleType.DYN));
  }

  @Test
  public void lookupFieldType_notExposedField() {
    CelTypeProvider celTypeProvider =
        new ProtoMessageTypeProvider(ImmutableSet.of(AttributeContext.getDescriptor()));
    ProtoTypeMaskTypeProvider protoTypeMaskProvider =
        new ProtoTypeMaskTypeProvider(
            celTypeProvider,
            ImmutableSet.of(
                ProtoTypeMask.of(
                    "google.rpc.context.AttributeContext",
                    FieldMask.newBuilder().addPaths("resource.name").build())));
    ProtoMessageType resourceType = assertTypeFound(protoTypeMaskProvider, RESOURCE_TYPE);
    assertThat(resourceType.findField("type")).isEmpty();
  }

  @Test
  public void lookupType_notExposed() {
    CelTypeProvider celTypeProvider =
        new ProtoMessageTypeProvider(ImmutableSet.of(AttributeContext.getDescriptor()));
    ProtoTypeMaskTypeProvider protoTypeMaskProvider =
        new ProtoTypeMaskTypeProvider(
            celTypeProvider,
            ImmutableSet.of(
                ProtoTypeMask.of(
                    "google.rpc.context.AttributeContext",
                    FieldMask.newBuilder().addPaths("resource.name").build())));
    assertThat(protoTypeMaskProvider.findType(REQUEST_TYPE)).isPresent();
  }

  private ProtoMessageType assertTypeFound(CelTypeProvider celTypeProvider, String typeName) {
    Optional<CelType> foundType = celTypeProvider.findType(typeName);
    assertThat(foundType).isPresent();
    CelType celType = foundType.get();
    assertThat(celType).isInstanceOf(ProtoMessageType.class);
    return (ProtoMessageType) celType;
  }

  private void assertTypeHasFields(ProtoMessageType protoType, ImmutableSet<String> fields) {
    ImmutableSet<String> typeFieldNames =
        protoType.fields().stream().map(Field::name).collect(toImmutableSet());
    assertThat(typeFieldNames).containsExactlyElementsIn(fields);
  }

  private void assertTypeHasFieldWithType(
      ProtoMessageType protoType, String fieldName, String typeName) {
    assertThat(protoType.findField(fieldName)).isPresent();
    assertThat(protoType.findField(fieldName).get().type().name()).isEqualTo(typeName);
  }
}
