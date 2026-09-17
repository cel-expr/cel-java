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

import static com.google.common.base.Preconditions.checkArgument;

import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.errorprone.annotations.Immutable;
import dev.cel.common.CelVarDecl;
import dev.cel.common.types.CelKind;
import dev.cel.common.types.CelType;
import dev.cel.common.types.CelTypeProvider;
import dev.cel.common.types.ProtoMessageType;
import dev.cel.common.types.StructType;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The {@code ProtoTypeMaskTypeProvider} binds a set of {@code ProtoTypeMask} instances to type
 * definitions and ensures that only fields which have been explicitly listed by the set of {@code
 * ProtoTypeMask} values is exposed within the CEL type system.
 */
@Immutable
public final class ProtoTypeMaskTypeProvider implements CelTypeProvider {

  // The set of visible fields by type is not exposed after it is configured.
  @SuppressWarnings("Immutable")
  private final ImmutableMap<String, CelType> allTypes;

  private final ImmutableSet<ProtoTypeMask> protoTypeMasks;

  ProtoTypeMaskTypeProvider(
      CelTypeProvider delegateProvider, ImmutableSet<ProtoTypeMask> protoTypeMasks) {
    this.protoTypeMasks = protoTypeMasks;
    this.allTypes = computeVisibleFieldsMap(delegateProvider, protoTypeMasks);
  }

  @Override
  public ImmutableCollection<CelType> types() {
    return allTypes.values();
  }

  @Override
  public Optional<CelType> findType(String typeName) {
    return Optional.ofNullable(allTypes.get(typeName));
  }

  /**
   * Compute the list of {@code Decl} values derived from the input set of {@code ProtoTypeMask}
   * values.
   *
   * <p>All top-level fields in {@link ProtoTypeMask#getTypeName} definition which are also exposed
   * via a {@code FieldMask} are converted into {@code Decl} values.
   */
  ImmutableList<CelVarDecl> computeDeclsFromProtoTypeMasks() {
    ImmutableList.Builder<CelVarDecl> decls = ImmutableList.builder();
    for (ProtoTypeMask typeMask : protoTypeMasks) {
      if (!typeMask.fieldsAreVariableDeclarations()) {
        continue;
      }
      Optional<CelType> celType = findType(typeMask.getTypeName());
      if (!celType.isPresent() || celType.get().kind() != CelKind.STRUCT) {
        continue;
      }
      StructType celStruct = (StructType) celType.get();
      // The fieldNames cannot be null based on the checking provided by the computeVisibleFieldsMap
      for (StructType.Field field : celStruct.fields()) {
        decls.add(CelVarDecl.newVarDeclaration(field.name(), field.type()));
      }
    }
    return decls.build();
  }

  private static ImmutableMap<String, CelType> computeVisibleFieldsMap(
      CelTypeProvider delegateProvider, ImmutableSet<ProtoTypeMask> protoTypeMasks) {
    Map<String, Set<String>> fieldMap = new HashMap<>();
    for (ProtoTypeMask typeMask : protoTypeMasks) {
      String typeName = typeMask.getTypeName();
      Optional<CelType> rootType = delegateProvider.findType(typeName);
      checkArgument(rootType.isPresent(), "message not registered: %s", typeName);
      if (typeMask.areAllFieldPathsExposed()) {
        continue;
      }
      if (typeMask.areAllFieldPathsHidden()) {
        fieldMap.put(typeName, ImmutableSet.of());
        continue;
      }
      // Unroll the type(messageType) to just messageType.
      CelType type = rootType.get();
      checkArgument(type instanceof ProtoMessageType, "type is not a protobuf: %s", type.name());
      for (ProtoTypeMask.FieldPath fieldPath : typeMask.getFieldPathsExposed()) {
        CelType targetType = type;
        for (String fieldName : fieldPath.getFieldSelection()) {
          checkArgument(
              targetType instanceof ProtoMessageType,
              "could not select field %s from type %s",
              fieldName,
              targetType.name());
          ProtoMessageType messageType = (ProtoMessageType) targetType;
          String messageTypeName = targetType.name();
          if (fieldName.equals(ProtoTypeMask.WILDCARD_FIELD)) {
            break;
          }
          Optional<StructType.Field> fieldType = messageType.findField(fieldName);
          checkArgument(
              fieldType.isPresent(),
              "message %s does not declare field: %s",
              messageTypeName,
              fieldName);
          Set<String> fields =
              fieldMap.computeIfAbsent(messageTypeName, (unused) -> new HashSet<>());
          fields.add(fieldName);
          targetType = fieldType.get().type();
        }
      }
    }

    ImmutableMap.Builder<String, CelType> maskedTypes = ImmutableMap.builder();
    for (CelType type : delegateProvider.types()) {
      if (fieldMap.containsKey(type.name())) {
        ProtoMessageType messageType = (ProtoMessageType) type;
        messageType = messageType.withVisibleFields(ImmutableSet.copyOf(fieldMap.get(type.name())));
        maskedTypes.put(type.name(), messageType);
      } else {
        maskedTypes.put(type.name(), type);
      }
    }
    return maskedTypes.buildOrThrow();
  }
}
