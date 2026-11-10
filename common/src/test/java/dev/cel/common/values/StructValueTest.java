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

package dev.cel.common.values;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import com.google.testing.junit.testparameterinjector.TestParameters;
import dev.cel.bundle.Cel;
import dev.cel.bundle.CelFactory;
import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.CelOptions;
import dev.cel.common.internal.DynamicProto;
import dev.cel.common.types.CelType;
import dev.cel.common.types.CelTypeProvider;
import dev.cel.common.types.SimpleType;
import dev.cel.common.types.StructType;
import java.util.Map;
import java.util.Optional;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(TestParameterInjector.class)
public final class StructValueTest {
  private static final StructType CUSTOM_STRUCT_TYPE =
      StructType.create(
          "custom_struct",
          ImmutableSet.of("data"),
          fieldName -> fieldName.equals("data") ? Optional.of(SimpleType.INT) : Optional.empty());
  private static final CelTypeProvider CUSTOM_STRUCT_TYPE_PROVIDER =
      new CelTypeProvider() {
        @Override
        public ImmutableList<CelType> types() {
          return ImmutableList.of(CUSTOM_STRUCT_TYPE);
        }

        @Override
        public Optional<CelType> findType(String typeName) {
          return typeName.equals(CUSTOM_STRUCT_TYPE.name())
              ? Optional.of(CUSTOM_STRUCT_TYPE)
              : Optional.empty();
        }
      };

  private static final CelValueProvider CUSTOM_STRUCT_VALUE_PROVIDER =
      new CelValueProvider() {
        @Override
        public Optional<Object> newValue(String structType, Map<String, Object> fields) {
          if (structType.equals(CUSTOM_STRUCT_TYPE.name())) {
            return Optional.of(new CelCustomStructValue(fields));
          }
          return Optional.empty();
        }

        @Override
        public CelValueConverter celValueConverter() {
          return new CelValueConverter() {
            @Override
            public Object toRuntimeValue(Object value) {
              if (value instanceof CustomPojo) {
                return new CelCustomStructValue((CustomPojo) value);
              }
              return super.toRuntimeValue(value);
            }
          };
        }
      };

  @Test
  public void emptyStruct() {
    CelCustomStructValue celCustomStruct = new CelCustomStructValue(0);

    assertThat(celCustomStruct.value().getData()).isEqualTo(0L);
    assertThat(celCustomStruct.isZeroValue()).isTrue();
  }

  @Test
  public void constructStruct() {
    CelCustomStructValue celCustomStruct = new CelCustomStructValue(5);

    assertThat(celCustomStruct.value().getData()).isEqualTo(5L);
    assertThat(celCustomStruct.isZeroValue()).isFalse();
  }

  @Test
  public void selectField_success() {
    CelCustomStructValue celCustomStruct = new CelCustomStructValue(5);

    assertThat(celCustomStruct.select("data")).isEqualTo(5L);
  }

  @Test
  public void selectField_nonExistentField_throws() {
    CelCustomStructValue celCustomStruct = new CelCustomStructValue(5);

    assertThrows(IllegalArgumentException.class, () -> celCustomStruct.select("bogus"));
  }

  @Test
  @TestParameters("{fieldName: 'data', expectedResult: true}")
  @TestParameters("{fieldName: 'bogus', expectedResult: false}")
  public void findField_success(String fieldName, boolean expectedResult) {
    CelCustomStructValue celCustomStruct = new CelCustomStructValue(5);

    assertThat(celCustomStruct.find(fieldName).isPresent()).isEqualTo(expectedResult);
  }

  @Test
  public void celTypeTest() {
    CelCustomStructValue value = new CelCustomStructValue(0);

    assertThat(value.celType()).isEqualTo(CUSTOM_STRUCT_TYPE);
  }

  @Test
  public void evaluate_typeOfCustomStruct() throws Exception {
    Cel cel =
        CelFactory.plannerCelBuilder()
            .addVar("a", CUSTOM_STRUCT_TYPE)
            .setTypeProvider(CUSTOM_STRUCT_TYPE_PROVIDER)
            .setValueProvider(CUSTOM_STRUCT_VALUE_PROVIDER)
            .build();
    CelAbstractSyntaxTree ast = cel.compile("type(a) == custom_struct").getAst();

    Object result = cel.createProgram(ast).eval(ImmutableMap.of("a", new CelCustomStructValue(20)));

    assertThat(result).isEqualTo(true);
  }

  @Test
  public void evaluate_usingCustomClass_createNewStruct() throws Exception {
    Cel cel =
        CelFactory.plannerCelBuilder()
            .setTypeProvider(CUSTOM_STRUCT_TYPE_PROVIDER)
            .setValueProvider(CUSTOM_STRUCT_VALUE_PROVIDER)
            .build();
    CelAbstractSyntaxTree ast = cel.compile("custom_struct{data: 50}").getAst();

    CustomPojo result = (CustomPojo) cel.createProgram(ast).eval();

    assertThat(result.getData()).isEqualTo(50);
  }

  @Test
  public void evaluate_usingCustomClass_asVariable() throws Exception {
    Cel cel =
        CelFactory.plannerCelBuilder()
            .addVar("a", CUSTOM_STRUCT_TYPE)
            .setTypeProvider(CUSTOM_STRUCT_TYPE_PROVIDER)
            .setValueProvider(CUSTOM_STRUCT_VALUE_PROVIDER)
            .build();
    CelAbstractSyntaxTree ast = cel.compile("a").getAst();

    CustomPojo result =
        (CustomPojo)
            cel.createProgram(ast).eval(ImmutableMap.of("a", new CelCustomStructValue(10)));

    assertThat(result.getData()).isEqualTo(10);
  }

  @Test
  public void evaluate_usingCustomClass_asVariableSelectField() throws Exception {
    Cel cel =
        CelFactory.plannerCelBuilder()
            .addVar("a", CUSTOM_STRUCT_TYPE)
            .setTypeProvider(CUSTOM_STRUCT_TYPE_PROVIDER)
            .setValueProvider(CUSTOM_STRUCT_VALUE_PROVIDER)
            .build();
    CelAbstractSyntaxTree ast = cel.compile("a.data").getAst();

    assertThat(cel.createProgram(ast).eval(ImmutableMap.of("a", new CelCustomStructValue(20))))
        .isEqualTo(20L);
  }

  @Test
  public void evaluate_usingCustomClass_selectField() throws Exception {
    Cel cel =
        CelFactory.plannerCelBuilder()
            .setTypeProvider(CUSTOM_STRUCT_TYPE_PROVIDER)
            .setValueProvider(CUSTOM_STRUCT_VALUE_PROVIDER)
            .build();
    CelAbstractSyntaxTree ast = cel.compile("custom_struct{data: 5}.data").getAst();

    Object result = cel.createProgram(ast).eval();

    assertThat(result).isEqualTo(5L);
  }

  @Test
  public void evaluate_usingMultipleProviders_selectFieldFromCustomClass() throws Exception {
    Cel cel =
        CelFactory.plannerCelBuilder()
            .setTypeProvider(CUSTOM_STRUCT_TYPE_PROVIDER)
            .setValueProvider(
                CombinedCelValueProvider.combine(
                    ProtoMessageValueProvider.newInstance(
                        CelOptions.DEFAULT, DynamicProto.create(unused -> Optional.empty())),
                    CUSTOM_STRUCT_VALUE_PROVIDER))
            .build();
    CelAbstractSyntaxTree ast = cel.compile("custom_struct{data: 5}.data").getAst();

    Object result = cel.createProgram(ast).eval();

    assertThat(result).isEqualTo(5L);
  }

  // TODO: Bring back evaluate_usingMultipleProviders_selectFieldFromProtobufMessage
  // once planner is exposed from factory

  private static class CustomPojo {
    private final long data;

    CustomPojo(long data) {
      this.data = data;
    }

    long getData() {
      return data;
    }
  }

  @SuppressWarnings("Immutable") // Test only
  private static class CelCustomStructValue extends StructValue<String, CustomPojo> {

    private final CustomPojo pojo;

    @Override
    public CustomPojo value() {
      return pojo;
    }

    @Override
    public boolean isZeroValue() {
      return pojo.getData() == 0;
    }

    @Override
    public CelType celType() {
      return CUSTOM_STRUCT_TYPE;
    }

    @Override
    public Object select(String field) {
      return find(field)
          .orElseThrow(() -> new IllegalArgumentException("Invalid field name: " + field));
    }

    @Override
    public Optional<Object> find(String field) {
      if (field.equals("data")) {
        return Optional.of(pojo.getData());
      }

      return Optional.empty();
    }

    private CelCustomStructValue(Map<String, Object> fields) {
      this((long) fields.get("data"));
    }

    private CelCustomStructValue(long data) {
      this.pojo = new CustomPojo(data);
    }

    private CelCustomStructValue(CustomPojo pojo) {
      this.pojo = pojo;
    }
  }
}
