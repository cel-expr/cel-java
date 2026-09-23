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

import com.google.errorprone.annotations.Immutable;
import dev.cel.common.annotations.Internal;
import dev.cel.common.exceptions.CelAttributeNotFoundException;
import dev.cel.common.types.CelType;
import dev.cel.common.types.MapType;
import dev.cel.common.types.SimpleType;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A custom CelValue implementation that allows O(1) insertions for maps during comprehension.
 *
 * <p>CEL Library Internals. Do Not Use.
 */
@Internal
@Immutable
@SuppressWarnings("Immutable") // Intentionally mutable for performance reasons
public final class MutableMapValue extends CelValue
    implements SelectableValue<Object>, Map<Object, Object> {
  private final Map<Object, Object> internalMap;
  private final CelType celType;

  public static MutableMapValue create(Map<?, ?> map) {
    return new MutableMapValue(map);
  }

  @Override
  public int size() {
    return internalMap.size();
  }

  @Override
  public boolean isEmpty() {
    return internalMap.isEmpty();
  }

  @Override
  public boolean containsKey(Object key) {
    return internalMap.containsKey(key);
  }

  @Override
  public boolean containsValue(Object value) {
    return internalMap.containsValue(value);
  }

  @Override
  public Object get(Object key) {
    return internalMap.get(key);
  }

  @Override
  public Object put(Object key, Object value) {
    return internalMap.put(key, value);
  }

  @Override
  public Object remove(Object key) {
    return internalMap.remove(key);
  }

  @Override
  public void putAll(Map<?, ?> m) {
    internalMap.putAll(m);
  }

  @Override
  public void clear() {
    internalMap.clear();
  }

  @Override
  public Set<Object> keySet() {
    return internalMap.keySet();
  }

  @Override
  public Collection<Object> values() {
    return internalMap.values();
  }

  @Override
  public Set<Entry<Object, Object>> entrySet() {
    return internalMap.entrySet();
  }

  @Override
  public Object select(Object field) {
    return CelValueConverter.findMapValue(internalMap, field)
        .orElseThrow(() -> CelAttributeNotFoundException.forMissingMapKey(field.toString()));
  }

  @Override
  public Optional<?> find(Object field) {
    return CelValueConverter.findMapValue(internalMap, field);
  }

  @Override
  public Object value() {
    return this;
  }

  @Override
  public boolean isZeroValue() {
    return internalMap.isEmpty();
  }

  @Override
  public CelType celType() {
    return celType;
  }

  private MutableMapValue(Map<?, ?> map) {
    this.internalMap = new LinkedHashMap<>(map);
    this.celType = MapType.create(SimpleType.DYN, SimpleType.DYN);
  }
}
