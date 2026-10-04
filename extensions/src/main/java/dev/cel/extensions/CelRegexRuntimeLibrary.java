// Copyright 2025 Google LLC
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

package dev.cel.extensions;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.errorprone.annotations.Immutable;
import com.google.re2j.Matcher;
import com.google.re2j.Pattern;
import com.google.re2j.PatternSyntaxException;
import dev.cel.runtime.CelFunctionBinding;
import dev.cel.runtime.CelLiteRuntimeBuilder;
import dev.cel.runtime.CelLiteRuntimeLibrary;
import java.util.Optional;
import java.util.Set;

/** Runtime implementation of CEL regex extension functions. */
@Immutable
public final class CelRegexRuntimeLibrary implements CelLiteRuntimeLibrary {

  /** Enumeration of functions for the Regex runtime extension. */
  public enum Function {
    REPLACE(
        "regex.replace",
        ImmutableSet.of(
            CelFunctionBinding.from(
                "regex_replaceAll_string_string_string",
                ImmutableList.of(String.class, String.class, String.class),
                (args) -> {
                  String target = (String) args[0];
                  String pattern = (String) args[1];
                  String replaceStr = (String) args[2];
                  return CelRegexRuntimeLibrary.replace(target, pattern, replaceStr);
                }),
            CelFunctionBinding.from(
                "regex_replaceCount_string_string_string_int",
                ImmutableList.of(String.class, String.class, String.class, Long.class),
                (args) -> {
                  String target = (String) args[0];
                  String pattern = (String) args[1];
                  String replaceStr = (String) args[2];
                  long count = (long) args[3];
                  return CelRegexRuntimeLibrary.replaceN(target, pattern, replaceStr, count);
                }))),
    EXTRACT(
        "regex.extract",
        ImmutableSet.of(
            CelFunctionBinding.from(
                "regex_extract_string_string",
                String.class,
                String.class,
                CelRegexRuntimeLibrary::extract))),
    EXTRACTALL(
        "regex.extractAll",
        ImmutableSet.of(
            CelFunctionBinding.from(
                "regex_extractAll_string_string",
                String.class,
                String.class,
                CelRegexRuntimeLibrary::extractAll)));

    private final String functionName;
    private final ImmutableSet<CelFunctionBinding> functionBindings;

    String getFunction() {
      return functionName;
    }

    Function(String functionName, ImmutableSet<CelFunctionBinding> functionBindings) {
      this.functionName = functionName;
      this.functionBindings = functionBindings;
    }
  }

  private static final CelRegexRuntimeLibrary VERSION_0 =
      new CelRegexRuntimeLibrary(ImmutableSet.copyOf(Function.values()));

  /** Returns the latest version of the 'regex' runtime functions. */
  public static CelRegexRuntimeLibrary regex() {
    return VERSION_0;
  }

  /** Returns the specified version of the 'regex' runtime functions. */
  public static CelRegexRuntimeLibrary regex(int version) {
    switch (version) {
      case 0:
      case Integer.MAX_VALUE:
        return VERSION_0;
      default:
        throw new IllegalArgumentException("Unsupported 'regex' extension version " + version);
    }
  }

  /** Returns the 'regex' runtime functions with only the specified functions. */
  public static CelRegexRuntimeLibrary regex(Function... functions) {
    return regex(ImmutableSet.copyOf(functions));
  }

  /** Returns the 'regex' runtime functions with only the specified functions. */
  public static CelRegexRuntimeLibrary regex(Set<Function> functions) {
    return new CelRegexRuntimeLibrary(functions);
  }

  private final ImmutableSet<Function> functions;

  CelRegexRuntimeLibrary(Set<Function> functions) {
    this.functions = ImmutableSet.copyOf(functions);
  }

  @Override
  public void setRuntimeOptions(CelLiteRuntimeBuilder runtimeBuilder) {
    runtimeBuilder.addFunctionBindings(newFunctionBindings());
  }

  /** Creates the {@link CelFunctionBinding}s for the configured regex functions. */
  public ImmutableSet<CelFunctionBinding> newFunctionBindings() {
    ImmutableSet.Builder<CelFunctionBinding> builder = ImmutableSet.builder();
    for (Function function : functions) {
      if (!function.functionBindings.isEmpty()) {
        builder.addAll(
            CelFunctionBinding.fromOverloads(function.functionName, function.functionBindings));
      }
    }
    return builder.build();
  }

  private static Pattern compileRegexPattern(String regex) {
    try {
      return Pattern.compile(regex);
    } catch (PatternSyntaxException e) {
      throw new IllegalArgumentException("Failed to compile regex: " + regex, e);
    }
  }

  private static String replace(String target, String regex, String replaceStr) {
    return replaceN(target, regex, replaceStr, -1);
  }

  private static String replaceN(
      String target, String regex, String replaceStr, long replaceCount) {
    if (replaceCount == 0) {
      return target;
    }
    // For all negative replaceCount, do a replaceAll
    if (replaceCount < 0) {
      replaceCount = -1;
    }

    Pattern pattern = compileRegexPattern(regex);
    Matcher matcher = pattern.matcher(target);
    StringBuffer sb = new StringBuffer();
    int counter = 0;

    while (matcher.find()) {
      if (replaceCount != -1 && counter >= replaceCount) {
        break;
      }

      String processedReplacement = replaceStrValidator(matcher, replaceStr);
      matcher.appendReplacement(sb, Matcher.quoteReplacement(processedReplacement));
      counter++;
    }
    matcher.appendTail(sb);

    return sb.toString();
  }

  private static String replaceStrValidator(Matcher matcher, String replacement) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < replacement.length(); i++) {
      char c = replacement.charAt(i);

      if (c != '\\') {
        sb.append(c);
        continue;
      }

      if (i + 1 >= replacement.length()) {
        throw new IllegalArgumentException("Invalid replacement string: \\ not allowed at end");
      }

      char nextChar = replacement.charAt(++i);

      if (Character.isDigit(nextChar)) {
        int groupNum = Character.digit(nextChar, 10);
        int groupCount = matcher.groupCount();

        if (groupNum > groupCount) {
          throw new IllegalArgumentException(
              "Replacement string references group "
                  + groupNum
                  + " but regex has only "
                  + groupCount
                  + " group(s)");
        }

        String groupValue = matcher.group(groupNum);
        if (groupValue != null) {
          sb.append(groupValue);
        }
      } else if (nextChar == '\\') {
        sb.append('\\');
      } else {
        throw new IllegalArgumentException(
            "Invalid replacement string: \\ must be followed by a digit");
      }
    }
    return sb.toString();
  }

  private static Optional<String> extract(String target, String regex) {
    Pattern pattern = compileRegexPattern(regex);
    Matcher matcher = pattern.matcher(target);

    if (!matcher.find()) {
      return Optional.empty();
    }

    int groupCount = matcher.groupCount();
    if (groupCount > 1) {
      throw new IllegalArgumentException(
          "Regular expression has more than one capturing group: " + regex);
    }

    String result = (groupCount == 1) ? matcher.group(1) : matcher.group(0);

    return Optional.ofNullable(result);
  }

  private static ImmutableList<String> extractAll(String target, String regex) {
    Pattern pattern = compileRegexPattern(regex);
    Matcher matcher = pattern.matcher(target);

    if (matcher.groupCount() > 1) {
      throw new IllegalArgumentException(
          "Regular expression has more than one capturing group: " + regex);
    }

    ImmutableList.Builder<String> builder = ImmutableList.builder();
    boolean hasOneGroup = matcher.groupCount() == 1;

    while (matcher.find()) {
      if (hasOneGroup) {
        String group = matcher.group(1);
        if (group != null) {
          builder.add(group);
        }
      } else {
        builder.add(matcher.group(0));
      }
    }

    return builder.build();
  }
}
