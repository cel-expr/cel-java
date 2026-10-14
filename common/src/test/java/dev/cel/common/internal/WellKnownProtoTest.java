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

package dev.cel.common.internal;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ImmutableList;
import com.google.protobuf.Any;
import com.google.protobuf.BoolValue;
import com.google.protobuf.BytesValue;
import com.google.protobuf.DoubleValue;
import com.google.protobuf.Duration;
import com.google.protobuf.Empty;
import com.google.protobuf.FieldMask;
import com.google.protobuf.FloatValue;
import com.google.protobuf.Int32Value;
import com.google.protobuf.Int64Value;
import com.google.protobuf.ListValue;
import com.google.protobuf.StringValue;
import com.google.protobuf.Struct;
import com.google.protobuf.Timestamp;
import com.google.protobuf.UInt32Value;
import com.google.protobuf.UInt64Value;
import com.google.protobuf.Value;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import com.google.testing.junit.testparameterinjector.TestParameters;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(TestParameterInjector.class)
public class WellKnownProtoTest {

  @Test
  @TestParameters("{typeName: 'google.protobuf.FloatValue'}")
  @TestParameters("{typeName: 'google.protobuf.Int32Value'}")
  @TestParameters("{typeName: 'google.protobuf.Int64Value'}")
  @TestParameters("{typeName: 'google.protobuf.StringValue'}")
  @TestParameters("{typeName: 'google.protobuf.BoolValue'}")
  @TestParameters("{typeName: 'google.protobuf.BytesValue'}")
  @TestParameters("{typeName: 'google.protobuf.DoubleValue'}")
  @TestParameters("{typeName: 'google.protobuf.UInt32Value'}")
  @TestParameters("{typeName: 'google.protobuf.UInt64Value'}")
  @TestParameters("{typeName: 'google.protobuf.Empty'}")
  @TestParameters("{typeName: 'google.protobuf.FieldMask'}")
  public void isWrapperType_withTypeName_true(String typeName) {
    assertThat(WellKnownProto.isWrapperType(typeName)).isTrue();
  }

  @Test
  @TestParameters("{typeName: 'not.wellknown.type'}")
  @TestParameters("{typeName: 'google.protobuf.Any'}")
  @TestParameters("{typeName: 'google.protobuf.Duration'}")
  @TestParameters("{typeName: 'google.protobuf.ListValue'}")
  @TestParameters("{typeName: 'google.protobuf.Struct'}")
  @TestParameters("{typeName: 'google.protobuf.Value'}")
  @TestParameters("{typeName: 'google.protobuf.Timestamp'}")
  public void isWrapperType_withTypeName_false(String typeName) {
    assertThat(WellKnownProto.isWrapperType(typeName)).isFalse();
  }

  @Test
  public void getByPathName_singular() {
    assertThat(WellKnownProto.getByPathName(Any.getDescriptor().getFile().getName()))
        .containsExactly(WellKnownProto.ANY_VALUE);
    assertThat(WellKnownProto.getByPathName(Duration.getDescriptor().getFile().getName()))
        .containsExactly(WellKnownProto.DURATION);
    assertThat(WellKnownProto.getByPathName(Timestamp.getDescriptor().getFile().getName()))
        .containsExactly(WellKnownProto.TIMESTAMP);
    assertThat(WellKnownProto.getByPathName(Empty.getDescriptor().getFile().getName()))
        .containsExactly(WellKnownProto.EMPTY);
    assertThat(WellKnownProto.getByPathName(FieldMask.getDescriptor().getFile().getName()))
        .containsExactly(WellKnownProto.FIELD_MASK);
  }

  @Test
  public void getByPathName_json() {
    ImmutableList<WellKnownProto> expectedWellKnownProtos =
        ImmutableList.of(
            WellKnownProto.JSON_STRUCT_VALUE,
            WellKnownProto.JSON_VALUE,
            WellKnownProto.JSON_LIST_VALUE);
    assertThat(WellKnownProto.getByPathName(Struct.getDescriptor().getFile().getName()))
        .containsExactlyElementsIn(expectedWellKnownProtos);
    assertThat(WellKnownProto.getByPathName(Value.getDescriptor().getFile().getName()))
        .containsExactlyElementsIn(expectedWellKnownProtos);
    assertThat(WellKnownProto.getByPathName(ListValue.getDescriptor().getFile().getName()))
        .containsExactlyElementsIn(expectedWellKnownProtos);
  }

  @Test
  public void getByPathName_wrappers() {
    ImmutableList<WellKnownProto> expectedWellKnownProtos =
        ImmutableList.of(
            WellKnownProto.FLOAT_VALUE,
            WellKnownProto.DOUBLE_VALUE,
            WellKnownProto.INT32_VALUE,
            WellKnownProto.INT64_VALUE,
            WellKnownProto.UINT32_VALUE,
            WellKnownProto.UINT64_VALUE,
            WellKnownProto.BOOL_VALUE,
            WellKnownProto.STRING_VALUE,
            WellKnownProto.BYTES_VALUE);

    assertThat(WellKnownProto.getByPathName(FloatValue.getDescriptor().getFile().getName()))
        .containsExactlyElementsIn(expectedWellKnownProtos);
    assertThat(WellKnownProto.getByPathName(DoubleValue.getDescriptor().getFile().getName()))
        .containsExactlyElementsIn(expectedWellKnownProtos);
    assertThat(WellKnownProto.getByPathName(Int32Value.getDescriptor().getFile().getName()))
        .containsExactlyElementsIn(expectedWellKnownProtos);
    assertThat(WellKnownProto.getByPathName(Int64Value.getDescriptor().getFile().getName()))
        .containsExactlyElementsIn(expectedWellKnownProtos);
    assertThat(WellKnownProto.getByPathName(UInt32Value.getDescriptor().getFile().getName()))
        .containsExactlyElementsIn(expectedWellKnownProtos);
    assertThat(WellKnownProto.getByPathName(UInt64Value.getDescriptor().getFile().getName()))
        .containsExactlyElementsIn(expectedWellKnownProtos);
    assertThat(WellKnownProto.getByPathName(BoolValue.getDescriptor().getFile().getName()))
        .containsExactlyElementsIn(expectedWellKnownProtos);
    assertThat(WellKnownProto.getByPathName(StringValue.getDescriptor().getFile().getName()))
        .containsExactlyElementsIn(expectedWellKnownProtos);
    assertThat(WellKnownProto.getByPathName(BytesValue.getDescriptor().getFile().getName()))
        .containsExactlyElementsIn(expectedWellKnownProtos);
  }
}
