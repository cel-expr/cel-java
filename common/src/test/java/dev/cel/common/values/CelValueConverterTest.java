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

import dev.cel.common.CelErrorCode;
import dev.cel.common.exceptions.CelInvalidArgumentException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class CelValueConverterTest {
  private static final CelValueConverter CEL_VALUE_CONVERTER = new CelValueConverter() {};

  @Test
  @SuppressWarnings("unchecked") // Test only
  public void toRuntimeValue_optionalValue() {
    OptionalValue<String, String> optionalValue =
        (OptionalValue<String, String>) CEL_VALUE_CONVERTER.toRuntimeValue(Optional.of("test"));

    assertThat(optionalValue).isEqualTo(OptionalValue.create("test"));
  }

  @Test
  @SuppressWarnings("unchecked") // Test only
  public void unwrap_optionalValue() {
    Optional<Long> result =
        (Optional<Long>) CEL_VALUE_CONVERTER.maybeUnwrap(OptionalValue.create(2L));

    assertThat(result).isEqualTo(Optional.of(2L));
  }

  @Test
  @SuppressWarnings("unchecked") // Test only
  public void unwrap_emptyOptionalValue() {
    Optional<Long> result = (Optional<Long>) CEL_VALUE_CONVERTER.maybeUnwrap(OptionalValue.EMPTY);

    assertThat(result).isEqualTo(Optional.empty());
  }

  @Test
  public void toRuntimeValue_mapWithNullValue_throws() {
    Map<String, Object> map = new HashMap<>();
    map.put("key", null);

    CelInvalidArgumentException e =
        assertThrows(
            CelInvalidArgumentException.class, () -> CEL_VALUE_CONVERTER.toRuntimeValue(map));

    assertThat(e).hasMessageThat().isEqualTo("Map value cannot be null for key: key");
    assertThat(e.getErrorCode()).isEqualTo(CelErrorCode.INVALID_ARGUMENT);
  }

  @Test
  public void toRuntimeValue_mapWithNullKey_throws() {
    Map<Object, Object> map = new HashMap<>();
    map.put(null, "value");

    CelInvalidArgumentException e =
        assertThrows(
            CelInvalidArgumentException.class, () -> CEL_VALUE_CONVERTER.toRuntimeValue(map));

    assertThat(e).hasMessageThat().isEqualTo("Map key cannot be null.");
  }

  @Test
  public void toRuntimeValue_mapWithNullValueAfterAdaptedEntry_throws() {
    // The first entry normalizes (Integer -> Long), which diverts mapContainer onto its rebuild
    // path. The illegal entry is only reached by the tail loop.
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("adapted", 1);
    map.put("illegal", null);

    CelInvalidArgumentException e =
        assertThrows(
            CelInvalidArgumentException.class, () -> CEL_VALUE_CONVERTER.toRuntimeValue(map));

    assertThat(e).hasMessageThat().isEqualTo("Map value cannot be null for key: illegal");
  }

  @Test
  public void maybeUnwrap_mapWithNullValue_throws() {
    Map<String, Object> map = new HashMap<>();
    map.put("key", null);

    CelInvalidArgumentException e =
        assertThrows(CelInvalidArgumentException.class, () -> CEL_VALUE_CONVERTER.maybeUnwrap(map));

    assertThat(e).hasMessageThat().isEqualTo("Map value cannot be null for key: key");
  }

  @Test
  public void toTraversalTarget_map_returnsSameInstanceWithoutInspectingEntries() {
    Map<String, Object> map = new HashMap<>();
    map.put("illegal", null);

    Object result = CEL_VALUE_CONVERTER.toTraversalTarget(map);

    assertThat(result).isSameInstanceAs(map);
  }

  @Test
  public void toTraversalTarget_nonMap_normalizes() {
    Object result = CEL_VALUE_CONVERTER.toTraversalTarget(1);

    assertThat(result).isEqualTo(1L);
  }

  @Test
  public void findMapValue_boundKey_returnsValueAsStored() {
    Map<String, Object> map = new HashMap<>();
    map.put("key", 1);

    Optional<Object> result = CelValueConverter.findMapValue(map, "key");

    // Unadapted: the caller decides whether this hop materializes or merely traverses.
    assertThat(result).hasValue(1);
  }

  @Test
  public void findMapValue_absentKey_returnsEmpty() {
    Optional<Object> result = CelValueConverter.findMapValue(new HashMap<>(), "key");

    assertThat(result).isEmpty();
  }

  @Test
  public void findMapValue_nullBoundKey_throws() {
    Map<String, Object> map = new HashMap<>();
    map.put("key", null);

    CelInvalidArgumentException e =
        assertThrows(
            CelInvalidArgumentException.class, () -> CelValueConverter.findMapValue(map, "key"));

    assertThat(e).hasMessageThat().isEqualTo("Map value cannot be null for key: key");
    assertThat(e.getErrorCode()).isEqualTo(CelErrorCode.INVALID_ARGUMENT);
  }

  @Test
  public void containsMapKey_boundKey_returnsTrue() {
    Map<String, Object> map = new HashMap<>();
    map.put("key", "value");

    assertThat(CelValueConverter.containsMapKey(map, "key")).isTrue();
  }

  @Test
  public void containsMapKey_absentKey_returnsFalse() {
    Map<String, Object> map = new HashMap<>();
    map.put("key", "value");

    assertThat(CelValueConverter.containsMapKey(map, "absent")).isFalse();
  }

  @Test
  public void containsMapKey_nullBoundKey_throws() {
    Map<String, Object> map = new HashMap<>();
    map.put("key", null);

    CelInvalidArgumentException e =
        assertThrows(
            CelInvalidArgumentException.class, () -> CelValueConverter.containsMapKey(map, "key"));

    assertThat(e).hasMessageThat().isEqualTo("Map value cannot be null for key: key");
  }

  @Test
  public void toRuntimeValue_listWithNullElement_throws() {
    List<Object> list = Arrays.asList("a", null);

    CelInvalidArgumentException e =
        assertThrows(
            CelInvalidArgumentException.class, () -> CEL_VALUE_CONVERTER.toRuntimeValue(list));

    assertThat(e).hasMessageThat().isEqualTo("List element cannot be null at index: 1");
  }

  @Test
  public void toRuntimeValue_listWithNullElementAfterAdaptedElement_throws() {
    // The first element normalizes (Integer -> Long), which diverts mapContainer onto its rebuild
    // path. The illegal element is only reached by the tail loop.
    List<Object> list = Arrays.asList(1, null);

    CelInvalidArgumentException e =
        assertThrows(
            CelInvalidArgumentException.class, () -> CEL_VALUE_CONVERTER.toRuntimeValue(list));

    assertThat(e).hasMessageThat().isEqualTo("List element cannot be null at index: 1");
  }

  @Test
  public void toRuntimeValue_nonRandomAccessCollectionWithNullElement_throws() {
    List<Object> collection = new LinkedList<>(Arrays.asList("a", null));

    CelInvalidArgumentException e =
        assertThrows(
            CelInvalidArgumentException.class,
            () -> CEL_VALUE_CONVERTER.toRuntimeValue(collection));

    assertThat(e).hasMessageThat().isEqualTo("List element cannot be null at index: 1");
  }

  @Test
  public void maybeUnwrap_listWithNullElement_throws() {
    // Previously returned the list with the illegal element intact, because the element mapped to
    // itself and the zero-allocation path never rebuilt.
    List<Object> list = Arrays.asList("a", null);

    CelInvalidArgumentException e =
        assertThrows(
            CelInvalidArgumentException.class, () -> CEL_VALUE_CONVERTER.maybeUnwrap(list));

    assertThat(e).hasMessageThat().isEqualTo("List element cannot be null at index: 1");
  }
}
