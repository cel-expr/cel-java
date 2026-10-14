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

package dev.cel.common.internal;

import com.google.auto.value.AutoValue;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.primitives.ImmutableIntArray;
import com.google.errorprone.annotations.Immutable;
import dev.cel.common.annotations.Internal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * An object which manages error reporting. Enriches error messages by source context pointing to
 * the position in the source where the error occurred.
 *
 * <p>CEL Library Internals. Do Not Use.
 */
@Internal
public final class Errors {

  private static final String NEWLINE = System.lineSeparator();
  private static final Splitter LINE_SPLITTER = Splitter.on(NEWLINE);

  /**
   * Represents an error context. A context consists of a description (like a file name), and an
   * optional source. It is capable of calculating a line/column pair if the source is provided.
   */
  @Immutable
  private static class Context {
    private final String description;
    @Nullable private final String source;
    private final ImmutableIntArray codePoints;
    private final ImmutableIntArray linePositions;

    private Context(String description, @Nullable String source) {
      this.description = description;
      this.source = source;
      this.codePoints =
          source == null
              ? ImmutableIntArray.of()
              : ImmutableIntArray.copyOf(source.codePoints().toArray());

      ImmutableIntArray.Builder linePositionsBuilder = ImmutableIntArray.builder();
      if (source != null) {
        int linePosition = 0;
        for (String line : LINE_SPLITTER.split(source)) {
          linePosition += (int) (line.codePoints().count() + 1);
          linePositionsBuilder.add(linePosition);
        }
      }
      this.linePositions = linePositionsBuilder.build();
    }

    /** Compute the source line offset positions within the input file. */
    private ImmutableIntArray getLineOffsets() {
      return linePositions;
    }

    private SourceLocation getPositionLocation(int position) {
      int line = 1;
      for (int index = 0; index < linePositions.length(); index++) {
        if (linePositions.get(index) > position) {
          break;
        }
        line++;
      }
      if (line == 1) {
        return SourceLocation.of(line, position + 1);
      }
      int lineStartPosition = linePositions.get(line - 2);
      return SourceLocation.of(line, position - lineStartPosition + 1);
    }

    /** Get the snippet of source at the given {@code line}, if present. */
    private Optional<String> getSnippet(int line) {
      if (source == null) {
        // Source not available, can't calculate.
        return Optional.empty();
      }

      if (line - 1 < linePositions.length()) {
        int lineStart = linePositions.length() == 1 || line == 1 ? 0 : linePositions.get(line - 2);
        int lineEnd = linePositions.get(line - 1);
        return Optional.of(new String(codePoints.toArray(), lineStart, (lineEnd - lineStart - 1)));
      }
      return Optional.empty();
    }
  }

  /** SourceLocation gives the line and column where an expression starts. */
  @AutoValue
  @Immutable
  public abstract static class SourceLocation {

    /** Return the line where the source appears. */
    public abstract int line();

    /** Return the column where the source starts. */
    public abstract int column();

    /** Create a new {@code SourceLocation} from {@code line} and {@code column}. */
    public static SourceLocation of(int line, int column) {
      return new AutoValue_Errors_SourceLocation(line, column);
    }
  }

  /**
   * Represents an error. An error is associated with a context, a position, and a message. This
   * information is opaque currently, and the only public method on an error is to convert it into a
   * string.
   */
  @SuppressWarnings("JavaLangClash")
  @Immutable
  public static class Error {

    private final Context context;
    private final int position;
    private final String message;
    private final long exprId;

    private Error(long exprId, Context context, int position, String message) {
      this.exprId = exprId;
      this.context = context;
      this.position = position;
      this.message = message;
    }

    /** Returns the code point position assigned to the expression with the error. */
    public int position() {
      return position;
    }

    /** Returns the raw error message without the container or line number. */
    public String rawMessage() {
      return message;
    }

    /**
     * Returns the expression ID associated with this error. May return 0 if the error is not caused
     * by an expression (ex: environment misconfiguration).
     */
    public long exprId() {
      return exprId;
    }

    /** Formats the error into a string which indicates where it occurs within the expression. */
    public String toDisplayString(@Nullable ErrorFormatter formatter) {
      String marker = formatter != null ? formatter.formatError("ERROR") : "ERROR";
      SourceLocation location = context.getPositionLocation(position);
      Optional<String> sourceLine = context.getSnippet(location.line());
      if (!sourceLine.isPresent()) {
        // Without source information, report error with absolute position.
        return String.format("%s: %s:%s: %s", marker, context.description, position, message);
      }
      int line = location.line();
      int column = location.column();
      StringBuilder result =
          new StringBuilder(
              String.format(
                  "%s: %s:%s:%s: %s", marker, context.description, line, column, message));
      result.append(NEWLINE);
      result.append("  | ");
      result.append(sourceLine.get().replace("%", "%%"));
      result.append(NEWLINE);
      result.append("  | ");
      result.append(Strings.repeat(".", column - 1));
      result.append("^");
      return result.toString();
    }

    @Override
    public String toString() {
      return toDisplayString(null);
    }
  }

  // The list of errors reported so far.
  private final List<Error> errors = new ArrayList<>();

  // A stack of error contexts.
  private final Deque<Context> context = new ArrayDeque<>();

  /**
   * Creates an errors object.
   *
   * @param description description of the root error context used in error messages, e.g. a
   *     filename.
   * @param source the (optional) source string associated with the root error context.
   */
  public Errors(String description, @Nullable String source) {
    enterContext(description, source);
  }

  /**
   * Enters a new error context. Errors reported in this context will use given description and
   * source.
   */
  public void enterContext(String description, @Nullable String source) {
    context.addFirst(new Context(Preconditions.checkNotNull(description), source));
  }

  /** Exits the last entered error context. */
  public void exitContext() {
    Preconditions.checkState(!context.isEmpty(), "cannot exit top-level error context");
    context.removeFirst();
  }

  /** Returns the line offet positions for the currently active error context. */
  public ImmutableIntArray getLineOffsets() {
    return context.peekFirst().getLineOffsets();
  }

  public String getContent() {
    return context.peekFirst().source;
  }

  /** Returns description of the currently active error context. */
  public String getDescription() {
    return context.peek().description;
  }

  /** Returns a location description at the current offset position. */
  public String getLocationAt(int position) {
    Context current = context.peekFirst();
    SourceLocation location = current.getPositionLocation(position);
    int line = location.line();
    int column = location.column();
    return String.format("%s:%d:%d", current.description, line, column);
  }

  /** Returns the {@code SourceLocation} for the given codepoint offset {@code position}. */
  public SourceLocation getPositionLocation(int position) {
    Context current = context.peekFirst();
    return current.getPositionLocation(position);
  }

  /** Returns the snippet of source at the given line, if present. */
  public Optional<String> getSnippet(int line) {
    Context current = context.peekFirst();
    return current.getSnippet(line);
  }

  /** Returns the number of errors reported via this object. */
  public int getErrorCount() {
    return errors.size();
  }

  /** Return the list of errors reported so far. */
  public ImmutableList<Error> getErrors() {
    return ImmutableList.copyOf(errors);
  }

  /** Returns a string will all errors reported via this object. */
  public String getAllErrorsAsString() {
    return Joiner.on(NEWLINE).join(errors);
  }

  /**
   * Note: Used by codegen
   *
   * @deprecated Use {@link #reportError(long, int, String, Object...) instead}
   */
  @Deprecated
  public void reportError(int position, String message, Object... args) {
    reportError(0L, position, message, args);
  }

  /** Reports an error. */
  // TODO: Consider adding @FormatMethod here and updating all upstream callers.
  public void reportError(long exprId, int position, String message, Object... args) {
    if (args.length > 0) {
      message = String.format(message, args);
    }
    errors.add(new Error(exprId, context.peekFirst(), position, message));
  }

  /**
   * Helper interface to format an error string.
   *
   * <p>For example, this interface may be used to alter the code-coding of an error string.
   */
  public interface ErrorFormatter {
    String formatError(String errorMessage);
  }
}
