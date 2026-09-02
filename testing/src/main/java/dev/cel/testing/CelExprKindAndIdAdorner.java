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
import static java.util.Collections.reverseOrder;
import static java.util.Map.Entry.comparingByKey;
import static java.util.stream.Collectors.joining;

import dev.cel.expr.Constant;
import dev.cel.expr.Expr;
import dev.cel.expr.Expr.CreateStruct.EntryOrBuilder;
import dev.cel.expr.ExprOrBuilder;
import dev.cel.expr.SourceInfo;
import com.google.common.base.Ascii;
import com.google.common.base.Joiner;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.EnumDescriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Descriptors.OneofDescriptor;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * An implementation of {@link CelAdorner} that decorates expressions with their ID and expression
 * kind (or macro call name if applicable).
 */
public final class CelExprKindAndIdAdorner implements CelAdorner {

  private static final Joiner JOINER = Joiner.on('.');

  private final SourceInfo sourceInfo;

  public CelExprKindAndIdAdorner() {
    this(SourceInfo.getDefaultInstance());
  }

  public CelExprKindAndIdAdorner(SourceInfo sourceInfo) {
    this.sourceInfo = checkNotNull(sourceInfo);
  }

  public static CelExprKindAndIdAdorner newInstance() {
    return new CelExprKindAndIdAdorner();
  }

  public static CelExprKindAndIdAdorner newInstance(SourceInfo sourceInfo) {
    return new CelExprKindAndIdAdorner(sourceInfo);
  }

  /**
   * Formats the macro calls from {@link SourceInfo} to an adorned debug string, sorted in
   * ascending order of expression ID.
   */
  public static String convertMacroCallsToString(SourceInfo sourceInfo) {
    CelExprKindAndIdAdorner macroCallsAdorner = new CelExprKindAndIdAdorner(sourceInfo);
    // Sort in ascending order so that nested macro calls are always in the same order for tests
    // output debug string. Ascending order keeps the macro calls map in order from outermost/first
    // macro to the innermost/last macro for readability.
    return sourceInfo.getMacroCallsMap().entrySet().stream()
        .sorted(reverseOrder(comparingByKey()))
        .map((entry) -> CelDebug.toAdornedDebugString(entry.getValue(), macroCallsAdorner))
        .collect(joining(",\n"));
  }

  @Override
  public String adorn(ExprOrBuilder expr) {
    if (this.sourceInfo.containsMacroCalls(expr.getId())) {
      return String.format(
          "^#%d:%s#",
          expr.getId(),
          this.sourceInfo.getMacroCallsOrThrow(expr.getId()).getCallExpr().getFunction());
    }

    if (expr.hasConstExpr()) {
      Constant constExpr = expr.getConstExpr();
      Descriptor descriptor = Constant.getDescriptor();
      OneofDescriptor oneof = findOneofByName(descriptor, "constant_kind");
      FieldDescriptor field = constExpr.getOneofFieldDescriptor(oneof);
      if (field.getType() == FieldDescriptor.Type.ENUM) {
        return String.format("^#%d:%s#", expr.getId(), getContainedName(field.getEnumType()));
      } else {
        return String.format(
            "^#%d:%s#", expr.getId(), Ascii.toLowerCase(field.getType().toString()));
      }
    }
    Descriptor descriptor = Expr.getDescriptor();
    OneofDescriptor oneof = findOneofByName(descriptor, "expr_kind");
    FieldDescriptor field = expr.getOneofFieldDescriptor(oneof);
    return String.format("^#%d:%s#", expr.getId(), getContainedName(field.getMessageType()));
  }

  @Override
  public String adorn(EntryOrBuilder entry) {
    return String.format("^#%d:Expr.CreateStruct.Entry#", entry.getId());
  }

  private static OneofDescriptor findOneofByName(Descriptor descriptor, String name) {
    for (OneofDescriptor oneof : descriptor.getOneofs()) {
      if (oneof.getName().equals(name)) {
        return oneof;
      }
    }
    return null;
  }

  private static String getContainedName(Descriptor descriptor) {
    Deque<String> parts = new ArrayDeque<>();
    parts.addFirst(descriptor.getName());
    Descriptor containing = descriptor.getContainingType();
    while (containing != null) {
      parts.addFirst(containing.getName());
      containing = containing.getContainingType();
    }
    return JOINER.join(parts);
  }

  private static String getContainedName(EnumDescriptor descriptor) {
    Deque<String> parts = new ArrayDeque<>();
    parts.addFirst(descriptor.getName());
    Descriptor containing = descriptor.getContainingType();
    while (containing != null) {
      parts.addFirst(containing.getName());
      containing = containing.getContainingType();
    }
    return JOINER.join(parts);
  }
}
