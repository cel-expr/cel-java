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

package dev.cel.common.internal;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.primitives.UnsignedLong;
import com.google.protobuf.Any;
import com.google.protobuf.BoolValue;
import com.google.protobuf.ByteString;
import com.google.protobuf.BytesValue;
import com.google.protobuf.DoubleValue;
import com.google.protobuf.Duration;
import com.google.protobuf.Empty;
import com.google.protobuf.FieldMask;
import com.google.protobuf.FloatValue;
import com.google.protobuf.Int64Value;
import com.google.protobuf.ListValue;
import com.google.protobuf.Message;
import com.google.protobuf.NullValue;
import com.google.protobuf.StringValue;
import com.google.protobuf.Struct;
import com.google.protobuf.Timestamp;
import com.google.protobuf.UInt32Value;
import com.google.protobuf.UInt64Value;
import com.google.protobuf.Value;
import com.google.type.Expr;
import dev.cel.common.CelOptions;
import dev.cel.common.values.CelByteString;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.Test;
import org.junit.experimental.runners.Enclosed;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;

@RunWith(Enclosed.class)
public final class ProtoAdapterTest {

  private static final DynamicProto DYNAMIC_PROTO =
      DynamicProto.create(DefaultMessageFactory.INSTANCE);

  @RunWith(Parameterized.class)
  public static class BidirectionalConversionTest {
    @Parameter(0)
    public Object value;

    @Parameter(1)
    public Message proto;

    @Parameters
    public static List<Object[]> data() {
      return Arrays.asList(
          new Object[][] {
            {
              dev.cel.common.values.NullValue.NULL_VALUE,
              Any.pack(Value.newBuilder().setNullValue(NullValue.NULL_VALUE).build()),
            },
            {true, BoolValue.of(true)},
            {true, Any.pack(BoolValue.of(true))},
            {true, Value.newBuilder().setBoolValue(true).build()},
            {
              CelByteString.copyFromUtf8("hello"), BytesValue.of(ByteString.copyFromUtf8("hello")),
            },
            {
              CelByteString.copyFromUtf8("hello"),
              Any.pack(BytesValue.of(ByteString.copyFromUtf8("hello"))),
            },
            {1.5D, DoubleValue.of(1.5D)},
            {1.5D, Any.pack(DoubleValue.of(1.5D))},
            {1.5D, Value.newBuilder().setNumberValue(1.5D).build()},
            {
              java.time.Duration.ofSeconds(123), Duration.newBuilder().setSeconds(123).build(),
            },
            {
              java.time.Duration.ofSeconds(123),
              Any.pack(Duration.newBuilder().setSeconds(123).build()),
            },
            {1L, Int64Value.of(1L)},
            {1L, Any.pack(Int64Value.of(1L))},
            {UnsignedLong.valueOf(1L), UInt64Value.of(1L)},
            {"hello", StringValue.of("hello")},
            {"hello", Any.pack(StringValue.of("hello"))},
            {"hello", Value.newBuilder().setStringValue("hello").build()},
            {
              Arrays.asList("hello", "world"),
              Any.pack(
                  ListValue.newBuilder()
                      .addValues(Value.newBuilder().setStringValue("hello"))
                      .addValues(Value.newBuilder().setStringValue("world"))
                      .build()),
            },
            {
              ImmutableMap.of("hello", "world"),
              Any.pack(
                  Struct.newBuilder()
                      .putFields("hello", Value.newBuilder().setStringValue("world").build())
                      .build()),
            },
            {
              ImmutableMap.of(
                  "list_value",
                  ImmutableList.of(false, dev.cel.common.values.NullValue.NULL_VALUE)),
              Struct.newBuilder()
                  .putFields(
                      "list_value",
                      Value.newBuilder()
                          .setListValue(
                              ListValue.newBuilder()
                                  .addValues(Value.newBuilder().setBoolValue(false))
                                  .addValues(Value.newBuilder().setNullValue(NullValue.NULL_VALUE)))
                          .build())
                  .build(),
            },
            {
              Instant.ofEpochSecond(123), Timestamp.newBuilder().setSeconds(123).build(),
            },
            {
              Instant.ofEpochSecond(123), Any.pack(Timestamp.newBuilder().setSeconds(123).build()),
            },
            {UnsignedLong.valueOf(1L), UInt64Value.of(1L)},
            {UnsignedLong.valueOf(1L), Any.pack(UInt64Value.of(1L))},
            {Empty.getDefaultInstance(), Empty.getDefaultInstance()},
            {
              FieldMask.newBuilder().addPaths("foo").build(),
              FieldMask.newBuilder().addPaths("foo").build()
            }
          });
    }

    @Test
    public void adaptValueToProto_bidirectionalConversion() {
      DynamicProto dynamicProto = DynamicProto.create(DefaultMessageFactory.INSTANCE);
      ProtoAdapter protoAdapter = new ProtoAdapter(dynamicProto, CelOptions.current().build());
      assertThat(protoAdapter.adaptValueToProto(value, proto.getDescriptorForType().getFullName()))
          .isEqualTo(proto);
      assertThat(protoAdapter.adaptProtoToValue(proto)).isEqualTo(value);
    }
  }

  @RunWith(JUnit4.class)
  public static class BidirectionalConversionCustomMessageTest {
    @Test
    public void adaptAnyValue_hermeticTypes_bidirectionalConversion() {
      Expr expr = Expr.newBuilder().setExpression("test").build();
      ProtoAdapter protoAdapter =
          new ProtoAdapter(
              DynamicProto.create(
                  (typeName) ->
                      typeName.equals(Expr.getDescriptor().getFullName())
                          ? Optional.of(Expr.newBuilder())
                          : Optional.empty()),
              CelOptions.DEFAULT);
      assertThat(protoAdapter.adaptValueToProto(expr, Any.getDescriptor().getFullName()))
          .isEqualTo(Any.pack(expr));
      assertThat(protoAdapter.adaptProtoToValue(Any.pack(expr))).isEqualTo(expr);
    }
  }

  @RunWith(JUnit4.class)
  public static class AsymmetricConversionTest {

    @Test
    public void unpackAny_celNullValue() throws Exception {
      ProtoAdapter protoAdapter = new ProtoAdapter(DYNAMIC_PROTO, CelOptions.DEFAULT);
      Any any =
          (Any)
              protoAdapter.adaptValueToProto(
                  dev.cel.common.values.NullValue.NULL_VALUE, "google.protobuf.Any");
      Object unpacked = protoAdapter.adaptProtoToValue(any);
      assertThat(unpacked).isEqualTo(dev.cel.common.values.NullValue.NULL_VALUE);
    }

    @Test
    public void adaptValueToProto_asymmetricFloatConversion() {
      ProtoAdapter protoAdapter = new ProtoAdapter(DYNAMIC_PROTO, CelOptions.DEFAULT);
      assertThat(protoAdapter.adaptValueToProto(1.5F, Any.getDescriptor().getFullName()))
          .isEqualTo(Any.pack(FloatValue.of(1.5F)));
      assertThat(protoAdapter.adaptProtoToValue(Any.pack(FloatValue.of(1.5F)))).isEqualTo(1.5D);
    }

    @Test
    public void adaptValueToProto_asymmetricDoubleFloatConversion() {
      ProtoAdapter protoAdapter = new ProtoAdapter(DYNAMIC_PROTO, CelOptions.DEFAULT);
      assertThat(protoAdapter.adaptValueToProto(1.5D, FloatValue.getDescriptor().getFullName()))
          .isEqualTo(FloatValue.of(1.5F));
      assertThat(protoAdapter.adaptProtoToValue(FloatValue.of(1.5F))).isEqualTo(1.5D);
    }

    @Test
    public void adaptValueToProto_asymmetricFloatDoubleConversion() {
      ProtoAdapter protoAdapter = new ProtoAdapter(DYNAMIC_PROTO, CelOptions.DEFAULT);
      assertThat(protoAdapter.adaptValueToProto(1.5F, DoubleValue.getDescriptor().getFullName()))
          .isEqualTo(DoubleValue.of(1.5D));
    }

    @Test
    public void adaptValueToProto_signedUintWrappers_throws() {
      ProtoAdapter protoAdapter = new ProtoAdapter(DYNAMIC_PROTO, CelOptions.DEFAULT);
      String uint32TypeName = UInt32Value.getDescriptor().getFullName();
      String uint64TypeName = UInt64Value.getDescriptor().getFullName();

      assertThrows(
          IllegalArgumentException.class, () -> protoAdapter.adaptValueToProto(1L, uint32TypeName));
      assertThrows(
          IllegalArgumentException.class,
          () -> protoAdapter.adaptValueToProto(-1L, uint64TypeName));
    }

    @Test
    @SuppressWarnings("deprecation") // Signed uints are only enabled through a deprecated option.
    public void adaptValueToProto_unsignedLongsDisabled_acceptsSignedUintWrappers() {
      ProtoAdapter protoAdapter =
          new ProtoAdapter(DYNAMIC_PROTO, CelOptions.current().enableUnsignedLongs(false).build());

      assertThat(protoAdapter.adaptValueToProto(1L, UInt32Value.getDescriptor().getFullName()))
          .isEqualTo(UInt32Value.of(1));
      assertThat(protoAdapter.adaptValueToProto(1L, UInt64Value.getDescriptor().getFullName()))
          .isEqualTo(UInt64Value.of(1));
    }

    @Test
    public void adaptValueToProto_asymmetricJsonConversion() {
      ProtoAdapter protoAdapter = new ProtoAdapter(DYNAMIC_PROTO, CelOptions.DEFAULT);
      assertThat(
              protoAdapter.adaptValueToProto(
                  UnsignedLong.valueOf(1L), Value.getDescriptor().getFullName()))
          .isEqualTo(Value.newBuilder().setNumberValue(1).build());
      assertThat(
              protoAdapter.adaptValueToProto(
                  UnsignedLong.fromLongBits(-1L), Value.getDescriptor().getFullName()))
          .isEqualTo(Value.newBuilder().setStringValue(Long.toUnsignedString(-1L)).build());
      assertThat(protoAdapter.adaptValueToProto(1L, Value.getDescriptor().getFullName()))
          .isEqualTo(Value.newBuilder().setNumberValue(1).build());
      assertThat(
              protoAdapter.adaptValueToProto(Long.MAX_VALUE, Value.getDescriptor().getFullName()))
          .isEqualTo(Value.newBuilder().setStringValue(Long.toString(Long.MAX_VALUE)).build());
      assertThat(
              protoAdapter.adaptValueToProto(
                  CelByteString.copyFromUtf8("foo"), Value.getDescriptor().getFullName()))
          .isEqualTo(Value.newBuilder().setStringValue("Zm9v").build());
    }

    @Test
    public void adaptValueToProto_unsupportedJsonConversion() {
      ProtoAdapter protoAdapter = new ProtoAdapter(DYNAMIC_PROTO, CelOptions.DEFAULT);

      assertThrows(
          ClassCastException.class,
          () ->
              protoAdapter.adaptValueToProto(
                  ImmutableMap.of(1, 1), Any.getDescriptor().getFullName()));
    }

    @Test
    public void adaptValueToProto_unsupportedJsonListConversion() {
      ProtoAdapter protoAdapter = new ProtoAdapter(DYNAMIC_PROTO, CelOptions.DEFAULT);

      assertThrows(
          ClassCastException.class,
          () ->
              protoAdapter.adaptValueToProto(
                  ImmutableMap.of(1, 1), ListValue.getDescriptor().getFullName()));
    }

    @Test
    public void adaptValueToProto_unsupportedConversion() {
      ProtoAdapter protoAdapter = new ProtoAdapter(DYNAMIC_PROTO, CelOptions.DEFAULT);

      assertThrows(
          IllegalStateException.class,
          () -> protoAdapter.adaptValueToProto("Hello", Expr.getDescriptor().getFullName()));
    }

    @Test
    public void signedUint32Converter_bidiSignedLong() {
      BidiConverter<Number, Number> converter = ProtoAdapter.SIGNED_UINT32_CONVERTER;
      assertThat(converter.forwardConverter().convert(12)).isEqualTo(12L);
      assertThat(converter.forwardConverter().convert(Integer.MIN_VALUE))
          .isEqualTo(Integer.MAX_VALUE + 1L);
      assertThat(converter.backwardConverter().convert(12L)).isEqualTo(12);
      assertThat(converter.backwardConverter().convert(Integer.MAX_VALUE + 1L))
          .isEqualTo(Integer.MIN_VALUE);
    }

    @Test
    public void unsignedUint32Converter_bidiUnsignedLong() {
      BidiConverter<Number, Number> converter = ProtoAdapter.UNSIGNED_UINT32_CONVERTER;
      assertThat(converter.forwardConverter().convert(12)).isEqualTo(UnsignedLong.valueOf(12L));
      assertThat(converter.forwardConverter().convert(Integer.MIN_VALUE))
          .isEqualTo(UnsignedLong.fromLongBits(Integer.MAX_VALUE + 1L));
      assertThat(converter.backwardConverter().convert(UnsignedLong.valueOf(12L))).isEqualTo(12);
      assertThat(
              converter
                  .backwardConverter()
                  .convert(UnsignedLong.fromLongBits(Integer.MAX_VALUE + 1L)))
          .isEqualTo(Integer.MIN_VALUE);
    }

    @Test
    public void unsignedUint64Converter() {
      BidiConverter<Number, Number> converter = ProtoAdapter.UNSIGNED_UINT64_CONVERTER;
      assertThat(converter.forwardConverter().convert(12L)).isEqualTo(UnsignedLong.valueOf(12L));
      assertThat(converter.forwardConverter().convert(Long.MIN_VALUE))
          .isEqualTo(UnsignedLong.fromLongBits(Long.MIN_VALUE));
      assertThat(converter.backwardConverter().convert(UnsignedLong.valueOf(12L))).isEqualTo(12L);
      assertThat(converter.backwardConverter().convert(UnsignedLong.fromLongBits(Long.MIN_VALUE)))
          .isEqualTo(Long.MIN_VALUE);
    }
  }
}
