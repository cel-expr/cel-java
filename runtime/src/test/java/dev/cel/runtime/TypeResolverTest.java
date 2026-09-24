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

package dev.cel.runtime;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.primitives.UnsignedLong;
import com.google.protobuf.NullValue;
import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import dev.cel.common.internal.ProtoTimeUtils;
import dev.cel.common.types.ListType;
import dev.cel.common.types.MapType;
import dev.cel.common.types.OptionalType;
import dev.cel.common.types.SimpleType;
import dev.cel.common.types.TypeType;
import dev.cel.common.values.CelValueConverter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Optional;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(TestParameterInjector.class)
public class TypeResolverTest {
  private static final TypeResolver TYPE_RESOLVER =
      TypeResolver.create(CelValueConverter.getDefaultInstance());

  @Test
  public void resolveWellKnownObjectType_sentinelRuntimeType() {
    Optional<TypeType> resolvedType =
        TYPE_RESOLVER.resolveWellKnownObjectType(TypeType.create(SimpleType.INT));

    assertThat(resolvedType).hasValue(TypeResolver.RUNTIME_TYPE_TYPE);
  }

  @Test
  public void resolveWellKnownObjectType_commonType(
      @TestParameter WellKnownObjectTestCase testCase) {
    Optional<TypeType> resolvedType = TYPE_RESOLVER.resolveWellKnownObjectType(testCase.obj);

    assertThat(resolvedType).hasValue(testCase.expectedTypeType);
  }

  @Test
  public void resolveWellKnownObjectType_unknownObjectType_returnsEmpty() {
    Optional<TypeType> resolvedType = TYPE_RESOLVER.resolveWellKnownObjectType(new Object());

    assertThat(resolvedType).isEmpty();
  }

  @SuppressWarnings("ImmutableEnumChecker") // Test only
  private enum WellKnownObjectTestCase {
    BOOLEAN(true, TypeType.create(SimpleType.BOOL)),
    DOUBLE(1.0, TypeType.create(SimpleType.DOUBLE)),
    LONG(1L, TypeType.create(SimpleType.INT)),
    UNSIGNED_LONG(UnsignedLong.valueOf(1L), TypeType.create(SimpleType.UINT)),
    STRING("test", TypeType.create(SimpleType.STRING)),
    NULL(NullValue.NULL_VALUE, TypeType.create(SimpleType.NULL_TYPE)),
    DURATION(ProtoTimeUtils.fromSecondsToDuration(1), TypeType.create(SimpleType.DURATION)),
    TIMESTAMP(ProtoTimeUtils.fromSecondsToTimestamp(1), TypeType.create(SimpleType.TIMESTAMP)),
    ARRAY_LIST(new ArrayList<>(), TypeType.create(ListType.create(SimpleType.DYN))),
    IMMUTABLE_LIST(ImmutableList.of(), TypeType.create(ListType.create(SimpleType.DYN))),
    HASH_MAP(new HashMap<>(), TypeType.create(MapType.create(SimpleType.DYN, SimpleType.DYN))),
    IMMUTABLE_MAP(
        ImmutableMap.of(), TypeType.create(MapType.create(SimpleType.DYN, SimpleType.DYN))),
    OPTIONAL(Optional.empty(), TypeType.create(OptionalType.create(SimpleType.DYN)));
    ;

    private final Object obj;
    private final TypeType expectedTypeType;

    WellKnownObjectTestCase(Object obj, TypeType expectedTypeType) {
      this.obj = obj;
      this.expectedTypeType = expectedTypeType;
    }
  }
}
