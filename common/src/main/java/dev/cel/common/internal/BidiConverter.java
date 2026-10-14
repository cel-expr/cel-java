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

import com.google.auto.value.AutoValue;
import com.google.errorprone.annotations.CheckReturnValue;
import dev.cel.common.annotations.Internal;

/**
 * The {@code BidiConverter} is a bidirectional converter which contains {@code Converter} objects
 * to forward convert from proto to CEL and backward convert from CEL to proto.
 *
 * <p>Use the {@code BidiConverter} when dealing with reading and constructing proto messages during
 * expression interpretation as CEL numeric primitives are wider than many of the proto primitives.
 *
 * <p>CEL Library Internals. Do Not Use.
 *
 * @param <A> The proto value type.
 * @param <B> The CEL value type.
 */
@AutoValue
@CheckReturnValue
@Internal
public abstract class BidiConverter<A, B> {

  /** A {@code BidiConverter} used for objects which do not need conversion. */
  public static final BidiConverter<Object, Object> IDENTITY =
      BidiConverter.of(value -> value, value -> value);

  /** Return the {@code Converter} which adapts from proto values to CEL values. */
  public abstract Converter<A, B> forwardConverter();

  /** Return the {@code Converter} which adapts from CEL values to proto values. */
  public abstract Converter<B, A> backwardConverter();

  /** Return a {@code BidiConverter} with the converter references reversed. */
  public BidiConverter<B, A> reverse() {
    return of(backwardConverter(), forwardConverter());
  }

  /**
   * Create a new {@code BidiConverter} from the {@code forwardConverter} and {@code
   * backwardConverter} {@link Converter} objects.
   */
  public static <A, B> BidiConverter<A, B> of(
      Converter<A, B> forwardConverter, Converter<B, A> backwardConverter) {
    return new AutoValue_BidiConverter<>(forwardConverter, backwardConverter);
  }
}
