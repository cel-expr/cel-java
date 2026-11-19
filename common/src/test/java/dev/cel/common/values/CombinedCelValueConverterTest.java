// Copyright 2026 Google LLC
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

import com.google.common.collect.ImmutableList;
import java.util.Map;
import java.util.Optional;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class CombinedCelValueConverterTest {

  @Test
  public void toRuntimeValue_delegatesToUnderlyingConverters() {
    CustomConverter converter1 = new CustomConverter("target1", "replacement1");
    CustomConverter converter2 = new CustomConverter("target2", "replacement2");
    CelValueConverter combined =
        CombinedCelValueConverter.combine(ImmutableList.of(converter1, converter2));

    assertThat(combined.toRuntimeValue("target1")).isEqualTo("replacement1");
    assertThat(combined.toRuntimeValue("target2")).isEqualTo("replacement2");
    assertThat(combined.toRuntimeValue("unhandled")).isEqualTo("unhandled");
  }

  @Test
  public void maybeUnwrap_delegatesToUnderlyingConverters() {
    CustomConverter converter1 = new CustomConverter("target1", "replacement1");
    CustomConverter converter2 = new CustomConverter("target2", "replacement2");
    CelValueConverter combined =
        CombinedCelValueConverter.combine(ImmutableList.of(converter1, converter2));

    assertThat(combined.maybeUnwrap("replacement1")).isEqualTo("target1");
    assertThat(combined.maybeUnwrap("replacement2")).isEqualTo("target2");
    assertThat(combined.maybeUnwrap("unhandled")).isEqualTo("unhandled");
  }

  @Test
  public void combinedCelValueProvider_returnsCombinedConverter() {
    CustomConverter converter1 = new CustomConverter("target1", "replacement1");
    CustomConverter converter2 = new CustomConverter("target2", "replacement2");
    CustomProvider provider1 = new CustomProvider(converter1);
    CustomProvider provider2 = new CustomProvider(converter2);

    CombinedCelValueProvider combinedProvider =
        CombinedCelValueProvider.combine(provider1, provider2);
    CelValueConverter combinedConverter = combinedProvider.celValueConverter();

    assertThat(combinedConverter).isInstanceOf(CombinedCelValueConverter.class);
    assertThat(combinedConverter.toRuntimeValue("target1")).isEqualTo("replacement1");
    assertThat(combinedConverter.toRuntimeValue("target2")).isEqualTo("replacement2");
  }

  @Test
  public void messageEquals_delegatesInOrder() {
    CelValueConverter first =
        new CelValueConverter() {
          @Override
          public Optional<Boolean> messageEquals(Object x, Object y) {
            return x.equals("handledByFirst") ? Optional.of(true) : Optional.empty();
          }
        };
    CelValueConverter second =
        new CelValueConverter() {
          @Override
          public Optional<Boolean> messageEquals(Object x, Object y) {
            return x.equals("handledByFirst") || x.equals("handledBySecond")
                ? Optional.of(false)
                : Optional.empty();
          }
        };
    CelValueConverter combined = CombinedCelValueConverter.combine(ImmutableList.of(first, second));

    assertThat(combined.messageEquals("handledByFirst", "other")).hasValue(true);
    assertThat(combined.messageEquals("handledBySecond", "other")).hasValue(false);
    assertThat(combined.messageEquals("unhandled", "unhandled")).isEmpty();
  }

  private static class CustomConverter extends CelValueConverter {
    private final String target;
    private final String replacement;

    private CustomConverter(String target, String replacement) {
      this.target = target;
      this.replacement = replacement;
    }

    @Override
    public Object toRuntimeValue(Object value) {
      if (value.equals(target)) {
        return replacement;
      }
      return value;
    }

    @Override
    public Object maybeUnwrap(Object value) {
      if (value.equals(replacement)) {
        return target;
      }
      return value;
    }
  }

  private static class CustomProvider implements CelValueProvider {
    private final CelValueConverter converter;

    private CustomProvider(CelValueConverter converter) {
      this.converter = converter;
    }

    @Override
    public Optional<Object> newValue(String structType, Map<String, Object> fields) {
      return Optional.empty();
    }

    @Override
    public CelValueConverter celValueConverter() {
      return converter;
    }
  }
}
