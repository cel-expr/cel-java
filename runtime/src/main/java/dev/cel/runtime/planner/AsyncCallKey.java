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

package dev.cel.runtime.planner;

import static java.util.Objects.requireNonNull;

import dev.cel.runtime.RuntimeEquality;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Unique cache key for an asynchronous function invocation at a given AST expression node.
 *
 * <p>Arguments are compared for equality using {@link RuntimeEquality}. Top-level and nested {@link
 * Double#NaN} and {@link Float#NaN} arguments are explicitly treated as equivalent across
 * evaluation iterations so that re-evaluating the same AST node with literal or computed NaN
 * arguments correctly matches existing call records.
 */
final class AsyncCallKey {
  private final long exprId;
  private final String functionName;
  private final String overloadId;
  private final Object[] args;
  private final RuntimeEquality runtimeEquality;
  private final int hashCode;

  static AsyncCallKey create(
      long exprId,
      String functionName,
      String overloadId,
      Object[] args,
      RuntimeEquality runtimeEquality) {
    return new AsyncCallKey(exprId, functionName, overloadId, args, runtimeEquality);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof AsyncCallKey)) {
      return false;
    }
    AsyncCallKey other = (AsyncCallKey) o;
    if (exprId != other.exprId
        || !functionName.equals(other.functionName)
        || !overloadId.equals(other.overloadId)
        || args.length != other.args.length) {
      return false;
    }
    for (int i = 0; i < args.length; i++) {
      if (!argEquals(args[i], other.args[i], runtimeEquality)) {
        return false;
      }
    }
    return true;
  }

  @Override
  public int hashCode() {
    return hashCode;
  }

  private static boolean argEquals(Object a, Object b, RuntimeEquality runtimeEquality) {
    if (a == b) {
      return true;
    }
    if (a == null || b == null) {
      return false;
    }
    if (a instanceof Number && b instanceof Number) {
      double da = ((Number) a).doubleValue();
      double db = ((Number) b).doubleValue();
      if (Double.isNaN(da) && Double.isNaN(db)) {
        return true;
      }
      if (da == 0.0d && db == 0.0d) {
        return true;
      }
      return runtimeEquality.objectEquals(a, b);
    }
    if (a instanceof List && b instanceof List) {
      List<?> listA = (List<?>) a;
      List<?> listB = (List<?>) b;
      if (listA.size() != listB.size()) {
        return false;
      }
      Iterator<?> iterA = listA.iterator();
      Iterator<?> iterB = listB.iterator();
      while (iterA.hasNext() && iterB.hasNext()) {
        if (!argEquals(iterA.next(), iterB.next(), runtimeEquality)) {
          return false;
        }
      }
      return true;
    }
    if (a instanceof Map && b instanceof Map) {
      Map<?, ?> mapA = (Map<?, ?>) a;
      Map<?, ?> mapB = (Map<?, ?>) b;
      if (mapA.size() != mapB.size()) {
        return false;
      }
      for (Map.Entry<?, ?> entry : mapA.entrySet()) {
        Optional<Object> valB = runtimeEquality.findInMap(mapB, entry.getKey());
        if (valB.isPresent()) {
          if (!argEquals(entry.getValue(), valB.get(), runtimeEquality)) {
            return false;
          }
        } else {
          if (!mapB.containsKey(entry.getKey())
              || entry.getValue() != null
              || mapB.get(entry.getKey()) != null) {
            return false;
          }
        }
      }
      return true;
    }
    if (a instanceof byte[] && b instanceof byte[]) {
      return Arrays.equals((byte[]) a, (byte[]) b);
    }
    if (a instanceof Object[] && b instanceof Object[]) {
      return Arrays.deepEquals((Object[]) a, (Object[]) b);
    }
    return runtimeEquality.objectEquals(a, b);
  }

  private static int computeHashCode(
      long exprId,
      String functionName,
      String overloadId,
      Object[] args,
      RuntimeEquality runtimeEquality) {
    int result = (int) (exprId ^ (exprId >>> 32));
    result = 31 * result + functionName.hashCode();
    result = 31 * result + overloadId.hashCode();
    for (Object arg : args) {
      result = 31 * result + hashArg(arg, runtimeEquality);
    }
    return result;
  }

  private static int hashArg(Object arg, RuntimeEquality runtimeEquality) {
    if (arg == null) {
      return 0;
    }
    if (arg instanceof Number) {
      double d = ((Number) arg).doubleValue();
      // Normalize -0.0d to +0.0d so they produce identical hash codes.
      if (d == 0.0d) {
        d = 0.0d;
      }
      return Double.hashCode(d);
    }
    if (arg instanceof Iterable) {
      int h = 1;
      for (Object elem : (Iterable<?>) arg) {
        h = h * 31 + hashArg(elem, runtimeEquality);
      }
      return h;
    }
    if (arg instanceof Map) {
      int h = 0;
      for (Map.Entry<?, ?> entry : ((Map<?, ?>) arg).entrySet()) {
        h += hashArg(entry.getKey(), runtimeEquality) ^ hashArg(entry.getValue(), runtimeEquality);
      }
      return h;
    }
    if (arg instanceof byte[]) {
      return Arrays.hashCode((byte[]) arg);
    }
    if (arg instanceof Object[]) {
      return Arrays.deepHashCode((Object[]) arg);
    }
    return runtimeEquality.hashCode(arg);
  }

  private AsyncCallKey(
      long exprId,
      String functionName,
      String overloadId,
      Object[] args,
      RuntimeEquality runtimeEquality) {
    this.exprId = exprId;
    this.functionName = requireNonNull(functionName);
    this.overloadId = requireNonNull(overloadId);
    this.args = args.clone();
    this.runtimeEquality = requireNonNull(runtimeEquality);
    this.hashCode = computeHashCode(exprId, functionName, overloadId, this.args, runtimeEquality);
  }
}
