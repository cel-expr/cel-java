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

package dev.cel.common.ast;

import com.google.common.base.Preconditions;
import java.util.HashMap;

/** Factory for populating expression IDs */
public final class CelExprIdGeneratorFactory {

  /**
   * MonotonicIdGenerator increments expression IDs from an initial seed value.
   *
   * @param exprId Seed value. Must be non-negative. For example, if 1 is provided {@link
   *     MonotonicIdGenerator#nextExprId()} will return 2.
   */
  public static MonotonicIdGenerator newMonotonicIdGenerator(long exprId) {
    return new MonotonicIdGenerator(exprId);
  }

  /**
   * StableIdGenerator ensures new IDs are only created the first time they are encountered.
   *
   * @param exprId Seed value. Must be non-negative. For example, if 1 is provided {@link
   *     StableIdGenerator#renumberId(long)} will return 2.
   */
  public static StableIdGenerator newStableIdGenerator(long exprId) {
    return new StableIdGenerator(exprId);
  }

  /** MonotonicIdGenerator increments expression IDs from an initial seed value. */
  public static class MonotonicIdGenerator {
    private long exprId;

    public long nextExprId() {
      return ++exprId;
    }

    private MonotonicIdGenerator(long exprId) {
      Preconditions.checkArgument(exprId >= 0);
      this.exprId = exprId;
    }
  }

  /** StableIdGenerator ensures new IDs are only created the first time they are encountered. */
  public static class StableIdGenerator {
    private final HashMap<Long, Long> idSet;
    private long exprId;

    /** Generates the next available ID. */
    public long nextExprId() {
      return ++exprId;
    }

    /**
     * Generate the next available ID while memoizing the existing ID.
     *
     * <p>The main purpose of this is to sanitize a new AST to replace an existing AST's node with.
     * The incoming AST may not have its IDs consistently numbered (often, the expr IDs are just
     * zeroes). In those cases, we just want to return an incremented expr ID.
     *
     * <p>The memoization becomes necessary if the incoming AST contains an expression with macro
     * map populated, requiring a normalization pass. In this case, the method behaves largely the
     * same as {@link #renumberId}.
     *
     * @param id Existing ID to memoize. Providing 0 or less will skip the memoization, in which
     *     case this behaves just like a {@link MonotonicIdGenerator}.
     */
    public long nextExprId(long id) {
      long nextExprId = ++exprId;
      if (id > 0) {
        idSet.put(id, nextExprId);
      }
      return nextExprId;
    }

    /** Memoize a given expression ID with a newly generated ID. */
    public void memoize(long existingId, long newId) {
      idSet.put(existingId, newId);
    }

    /**
     * Renumbers the existing expression ID to a newly generated unique ID. The existing ID is
     * memoized, and calling this method again with the same ID will always return the same
     * generated ID.
     */
    public long renumberId(long id) {
      Preconditions.checkArgument(id >= 0, "Expr ID must be positive. Got: %s", id);
      if (id == 0) {
        return 0;
      }

      if (idSet.containsKey(id)) {
        return idSet.get(id);
      }

      long nextExprId = ++exprId;
      idSet.put(id, nextExprId);
      return nextExprId;
    }

    private StableIdGenerator(long exprId) {
      Preconditions.checkArgument(exprId >= 0);
      this.idSet = new HashMap<>();
      this.exprId = exprId;
    }
  }

  /** Functional interface for generating the next unique expression ID. */
  @FunctionalInterface
  public interface ExprIdGenerator {

    /** Generates an expression ID with the provided expr ID as the context. */
    long generate(long exprId);
  }

  private CelExprIdGeneratorFactory() {}
}
