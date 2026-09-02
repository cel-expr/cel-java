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

import dev.cel.runtime.RuntimeEquality;

/**
 * Unique cache key for an asynchronous function invocation at a given AST expression node.
 *
 * <p>Arguments are compared for equality using {@link RuntimeEquality}. Top-level {@link
 * Double#NaN} and {@link Float#NaN} arguments are explicitly treated as equivalent across
 * evaluation iterations so that re-evaluating the same AST node with literal or computed NaN
 * arguments correctly matches existing call records.
 */
final class AsyncCallKey {
  private final long exprId;
  private final Object[] args;
  private final RuntimeEquality runtimeEquality;
  private final int hashCode;

  static AsyncCallKey create(long exprId, Object[] args, RuntimeEquality runtimeEquality) {
    return new AsyncCallKey(exprId, args, runtimeEquality);
  }

  private AsyncCallKey(long exprId, Object[] args, RuntimeEquality runtimeEquality) {
    this.exprId = exprId;
    this.args = args.clone();
    this.runtimeEquality = runtimeEquality;
    this.hashCode = computeHashCode(exprId, this.args, runtimeEquality);
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
    if (exprId != other.exprId || args.length != other.args.length) {
      return false;
    }
    for (int i = 0; i < args.length; i++) {
      Object a = args[i];
      Object b = other.args[i];
      if (a instanceof Double && b instanceof Double) {
        if (Double.isNaN((Double) a) && Double.isNaN((Double) b)) {
          continue;
        }
      } else if (a instanceof Float && b instanceof Float) {
        if (Float.isNaN((Float) a) && Float.isNaN((Float) b)) {
          continue;
        }
      }
      if (!runtimeEquality.objectEquals(a, b)) {
        return false;
      }
    }
    return true;
  }

  @Override
  public int hashCode() {
    return hashCode;
  }

  private static int computeHashCode(long exprId, Object[] args, RuntimeEquality runtimeEquality) {
    int result = (int) (exprId ^ (exprId >>> 32));
    for (Object arg : args) {
      result = 31 * result + hashArg(arg, runtimeEquality);
    }
    return result;
  }

  private static int hashArg(Object arg, RuntimeEquality runtimeEquality) {
    if (arg instanceof Number) {
      double d = ((Number) arg).doubleValue();
      if (d == 0.0d) {
        d = 0.0d;
      } else if (Double.isNaN(d)) {
        d = Double.NaN;
      }
      return Double.hashCode(d);
    }
    return runtimeEquality.hashCode(arg);
  }
}
