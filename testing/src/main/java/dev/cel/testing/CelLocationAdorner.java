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

package dev.cel.testing;

import static com.google.common.base.Preconditions.checkNotNull;

import dev.cel.expr.Expr.CreateStruct.EntryOrBuilder;
import dev.cel.expr.ExprOrBuilder;
import dev.cel.expr.SourceInfo;
import dev.cel.common.CelSourceLocation;
import java.util.Map;
import java.util.Optional;

/**
 * An implementation of {@link CelAdorner} that decorates expressions with their source location
 * (line and column numbers).
 */
public final class CelLocationAdorner implements CelAdorner {

  private final SourceInfo sourceInfo;

  public CelLocationAdorner(SourceInfo sourceInfo) {
    this.sourceInfo = checkNotNull(sourceInfo);
  }

  public static CelLocationAdorner newInstance(SourceInfo sourceInfo) {
    return new CelLocationAdorner(sourceInfo);
  }

  @Override
  public String adorn(ExprOrBuilder expr) {
    return adorn(expr.getId());
  }

  @Override
  public String adorn(EntryOrBuilder entry) {
    return adorn(entry.getId());
  }

  private String adorn(long exprId) {
    return getLocation(exprId)
        .map(
            location ->
                String.format(
                    "^#%d[%d,%d]#", exprId, location.getLine(), location.getColumn()))
        .orElseGet(() -> String.format("^#%d[NO_POS]#", exprId));
  }

  public Optional<CelSourceLocation> getLocation(long exprId) {
    Map<Long, Integer> positions = sourceInfo.getPositionsMap();
    Integer position = positions.get(exprId);
    if (position == null) {
      return Optional.empty();
    }
    int line = 1;
    for (int index = 0; index < sourceInfo.getLineOffsetsCount(); index++) {
      if (sourceInfo.getLineOffsets(index) > position) {
        break;
      } else {
        line++;
      }
    }
    int column = position;
    if (line > 1) {
      column = position - sourceInfo.getLineOffsets(line - 2);
    }
    return Optional.of(CelSourceLocation.of(line, column));
  }
}
