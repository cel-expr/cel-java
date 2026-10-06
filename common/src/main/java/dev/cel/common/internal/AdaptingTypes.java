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

import com.google.errorprone.annotations.CheckReturnValue;
import dev.cel.common.annotations.Internal;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Collection of types which support bidirectional adaptation between CEL and Java native value
 * representations.
 *
 * <p>CEL Library Internals. Do Not Use.
 */
@CheckReturnValue
@Internal
public final class AdaptingTypes {

  private AdaptingTypes() {}

  public static <A, B> List<B> adaptingList(List<A> list, BidiConverter<A, B> bidiConverter) {
    return new AdaptingList<>(list, bidiConverter);
  }

  public static <A, B, C, D> Map<C, D> adaptingMap(
      Map<A, B> map, BidiConverter<A, C> keyConverter, BidiConverter<B, D> valueConverter) {
    return new AdaptingMap<>(map, keyConverter, valueConverter);
  }

  /** Exception thrown on write access to adapted collection. */
  private static RuntimeException readonly() {
    throw new UnsupportedOperationException("collection is unmodifiable");
  }

  private static class AdaptingIterator<A, B> implements Iterator<B> {

    private final Iterator<A> delegate;
    private final BidiConverter<A, B> bidiConverter;

    private AdaptingIterator(Iterator<A> delegate, BidiConverter<A, B> bidiConverter) {
      this.delegate = delegate;
      this.bidiConverter = bidiConverter;
    }

    @Override
    public boolean hasNext() {
      return delegate.hasNext();
    }

    @Override
    public B next() {
      return bidiConverter.forwardConverter().convert(delegate.next());
    }

    @Override
    public void remove() {
      delegate.remove();
    }
  }

  private static class AdaptingCollection<A, B> implements Collection<B> {

    protected final Collection<A> delegate;
    protected final BidiConverter<A, B> bidiConverter;

    public AdaptingCollection(Collection<A> delegate, BidiConverter<A, B> bidiConverter) {
      this.delegate = delegate;
      this.bidiConverter = bidiConverter;
    }

    protected Converter<A, B> converter() {
      return bidiConverter.forwardConverter();
    }

    protected Converter<B, A> backwardConverter() {
      return bidiConverter.backwardConverter();
    }

    @Override
    public int size() {
      return delegate.size();
    }

    @Override
    public boolean isEmpty() {
      return delegate.isEmpty();
    }

    @SuppressWarnings("unchecked")
    @Override
    public boolean contains(Object o) {
      return delegate.contains(backwardConverter().convert((B) o));
    }

    @Override
    public Iterator<B> iterator() {
      return new AdaptingIterator<>(delegate.iterator(), bidiConverter);
    }

    @Override
    public Object[] toArray() {
      Object[] result = new Object[size()];
      int i = 0;
      for (A a : delegate) {
        result[i++] = converter().convert(a);
      }
      return result;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T[] toArray(T[] ts) {
      if (ts.length < size()) {
        return (T[]) toArray();
      }
      A[] base = delegate.toArray((A[]) new Object[ts.length]);
      Arrays.setAll(ts, i -> (T) converter().convert(base[i]));
      return ts;
    }

    @Override
    public boolean add(B a) {
      throw readonly();
    }

    @Override
    public boolean remove(Object o) {
      throw readonly();
    }

    @Override
    public boolean containsAll(Collection<?> collection) {
      for (Object o : collection) {
        if (!contains(o)) {
          return false;
        }
      }
      return true;
    }

    @Override
    public boolean addAll(Collection<? extends B> collection) {
      throw readonly();
    }

    @Override
    public boolean removeAll(Collection<?> collection) {
      throw readonly();
    }

    @Override
    public boolean retainAll(Collection<?> collection) {
      throw readonly();
    }

    @Override
    public void clear() {
      throw readonly();
    }
  }

  /** Adapter for lists. */
  private static class AdaptingList<A, B> extends AdaptingCollection<A, B> implements List<B> {

    public AdaptingList(List<A> delegate, BidiConverter<A, B> bidiConverter) {
      super(delegate, bidiConverter);
    }

    @Override
    public boolean addAll(int i, Collection<? extends B> collection) {
      throw readonly();
    }

    @Override
    public B get(int i) {
      return converter().convert(((List<A>) delegate).get(i));
    }

    @Override
    public B set(int i, B a) {
      throw readonly();
    }

    @Override
    public void add(int i, B a) {
      throw readonly();
    }

    @Override
    public B remove(int i) {
      throw readonly();
    }

    @SuppressWarnings("unchecked")
    @Override
    public int indexOf(Object o) {
      List<A> list = (List<A>) delegate;
      return list.indexOf(backwardConverter().convert((B) o));
    }

    @SuppressWarnings("unchecked")
    @Override
    public int lastIndexOf(Object o) {
      List<A> list = (List<A>) delegate;
      return list.lastIndexOf(backwardConverter().convert((B) o));
    }

    @Override
    public ListIterator<B> listIterator() {
      // This is very rarely used, and CEL doesn't use it for sure. See
      // if someone complains before implementing it.
      throw new UnsupportedOperationException("listIterator not supported");
    }

    @Override
    public ListIterator<B> listIterator(int i) {
      throw new UnsupportedOperationException("listIterator not supported");
    }

    @Override
    public List<B> subList(int i, int i1) {
      return new AdaptingList<>(((List<A>) delegate).subList(i, i1), bidiConverter);
    }
  }

  private static class AdaptingSet<A, B> extends AdaptingCollection<A, B> implements Set<B> {
    private AdaptingSet(Set<A> delegate, BidiConverter<A, B> bidiConverter) {
      super(delegate, bidiConverter);
    }
  }

  private static class AdaptingEntry<A, B, C, D> implements Map.Entry<C, D> {

    private final Map.Entry<A, B> delegate;
    private final Converter<A, C> keyConverter;
    private final Converter<B, D> valueConverter;

    public AdaptingEntry(
        Map.Entry<A, B> delegate, Converter<A, C> keyConverter, Converter<B, D> valueConverter) {
      this.delegate = delegate;
      this.keyConverter = keyConverter;
      this.valueConverter = valueConverter;
    }

    @Override
    public C getKey() {
      return keyConverter.convert(delegate.getKey());
    }

    @Override
    public D getValue() {
      return valueConverter.convert(delegate.getValue());
    }

    @Override
    public D setValue(D y) {
      throw readonly();
    }
  }

  /** Adapter for maps. */
  private static class AdaptingMap<A, B, C, D> implements Map<C, D> {

    private final Map<A, B> delegate;
    private final BidiConverter<A, C> keyBidiConverter;
    private final BidiConverter<B, D> valueBidiConverter;

    public AdaptingMap(
        Map<A, B> delegate,
        BidiConverter<A, C> keyBidiConverter,
        BidiConverter<B, D> valueBidiConverter) {
      this.delegate = delegate;
      this.keyBidiConverter = keyBidiConverter;
      this.valueBidiConverter = valueBidiConverter;
    }

    protected Converter<A, C> keyConverter() {
      return keyBidiConverter.forwardConverter();
    }

    protected Converter<C, A> keyBackwardConverter() {
      return keyBidiConverter.backwardConverter();
    }

    protected Converter<B, D> valueConverter() {
      return valueBidiConverter.forwardConverter();
    }

    protected Converter<D, B> valueBackwardConverter() {
      return valueBidiConverter.backwardConverter();
    }

    @Override
    public int size() {
      return delegate.size();
    }

    @Override
    public boolean isEmpty() {
      return delegate.isEmpty();
    }

    @SuppressWarnings("unchecked")
    @Override
    public boolean containsKey(Object o) {
      return delegate.containsKey(keyBackwardConverter().convert((C) o));
    }

    @SuppressWarnings("unchecked")
    @Override
    public boolean containsValue(Object o) {
      return delegate.containsValue(valueBackwardConverter().convert((D) o));
    }

    @SuppressWarnings("unchecked")
    @Override
    public @Nullable D get(Object o) {
      return valueConverter().convert(delegate.get(keyBackwardConverter().convert((C) o)));
    }

    @Override
    public @Nullable D put(C a, D b) {
      throw readonly();
    }

    @Override
    public @Nullable D remove(Object o) {
      throw readonly();
    }

    @Override
    public void putAll(Map<? extends C, ? extends D> map) {
      throw readonly();
    }

    @Override
    public void clear() {
      throw readonly();
    }

    @Override
    public Set<C> keySet() {
      return new AdaptingSet<>(delegate.keySet(), keyBidiConverter);
    }

    @Override
    public Collection<D> values() {
      return new AdaptingCollection<>(delegate.values(), valueBidiConverter);
    }

    @Override
    public Set<Map.Entry<C, D>> entrySet() {
      Converter<Map.Entry<A, B>, Map.Entry<C, D>> entryConverter =
          (Map.Entry<A, B> entry) -> new AdaptingEntry<>(entry, keyConverter(), valueConverter());
      Converter<Map.Entry<C, D>, Map.Entry<A, B>> entryBackwardConverter =
          (Map.Entry<C, D> entry) ->
              new AdaptingEntry<>(entry, keyBackwardConverter(), valueBackwardConverter());
      return new AdaptingSet<>(
          delegate.entrySet(), BidiConverter.of(entryConverter, entryBackwardConverter));
    }
  }
}
